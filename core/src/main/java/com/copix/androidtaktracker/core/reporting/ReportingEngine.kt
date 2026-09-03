package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.cot.CotEventBuilder
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.identity.IdentityResolver
import com.copix.androidtaktracker.core.mesh.MeshSaBroadcaster
import com.copix.androidtaktracker.core.tak.TakConnectionManager
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Why the engine is not sending right now, for the operator Status screen. */
enum class SuppressReason { NONE, PAUSED, ATAK_DEFER, NO_PATH }

/** Runtime reporting decisions, published every engine tick so the UI never reconstructs them. */
data class ReportingSnapshot(
    val motion: MotionState = MotionState.UNKNOWN,
    val strategy: String = "Dynamic",
    val intervalSeconds: Long = 0L,
    val staleSeconds: Long = 0L,
    /** True when a low battery stretched the Dynamic interval. */
    val batteryStretched: Boolean = false,
    val lastPliEpochMs: Long = 0L,
    /** Epoch when the next scheduled report is due (0 = unknown / not scheduled). */
    val nextDueEpochMs: Long = 0L,
    val gpsDuty: GpsDuty = GpsDuty.HIGH,
    val suppressed: SuppressReason = SuppressReason.NONE,
    /** True when at least one TAK server is connected (reliable path). */
    val takConnected: Boolean = false,
    /** True when Mesh SA is the active path for this tick. */
    val meshActive: Boolean = false,
)

class ReportingEngine(
    private val log: RedactedLogger,
    private val tak: TakConnectionManager,
    private val mesh: MeshSaBroadcaster,
    private val configProvider: () -> AppConfig,
    private val fixProvider: () -> GpsFix?,
    private val paused: () -> Boolean,
    private val deferringToAtak: () -> Boolean,
    private val batteryPercent: () -> Int?,
    private val charging: () -> Boolean = { false },
    private val deviceModel: () -> String,
    private val osVersion: () -> String,
    private val appVersion: () -> String,
    private val motion: MotionClassifier = MotionClassifier(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    @Volatile private var asap = false
    @Volatile private var identityDirty = false
    @Volatile var lastPliEpochMs: Long = 0L
        private set

    private val _snapshot = MutableStateFlow(ReportingSnapshot())
    val snapshot: StateFlow<ReportingSnapshot> = _snapshot

    private var lastSpeedMph = 0.0
    private var lastAlt = 0.0

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            delay(1_500)
            requestAsap()
            while (isActive) {
                try {
                    tick()
                } catch (ex: Exception) {
                    log.warn("Reporting", "Tick failed: ${ex.javaClass.simpleName}")
                }
                delay(1_000)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun requestAsap() {
        asap = true
    }

    fun noteIdentityChanged() {
        identityDirty = true
        asap = true
    }

    /** Forget the stationary anchor (GPS restarted after pause / ATAK defer / settings change). */
    fun resetMotion() {
        motion.reset()
    }

    private suspend fun tick() {
        val config = configProvider()
        val fix = fixProvider()
        val now = System.currentTimeMillis()

        // Classify every tick — even while paused — so GPS duty and the Status screen track
        // reality; a stationary → moving transition is the one displacement-driven ASAP.
        val decision = motion.observe(fix, config.reporting.significantMoveMeters)
        if (decision.transitionedToMoving) asap = true

        val suppressed = when {
            paused() -> SuppressReason.PAUSED
            deferringToAtak() -> SuppressReason.ATAK_DEFER
            else -> SuppressReason.NONE
        }
        val connected = tak.anyConnected
        val meshWanted = shouldSendMesh(config, connected)
        val noPath = !connected && !meshWanted

        val rate = ReportingRateFactory.create(config.reporting)
        val path = if (connected) ReportingPath.RELIABLE else ReportingPath.UNRELIABLE
        val speedMph = fix?.speedMph ?: 0.0
        val interval = rate.getInterval(path, speedMph, decision.metersFromAnchor)
        val baseSec = interval.seconds.coerceAtLeast(MotionPolicy.MIN_INTERVAL_SECONDS)
        val isConstant = config.reporting.strategy.equals("Constant", ignoreCase = true)
        val intervalSec = if (isConstant) baseSec
        else MotionPolicy.applyBatteryMultiplier(baseSec, batteryPercent(), charging())
        val stale = rate.getStale(Duration.ofSeconds(intervalSec))

        _snapshot.value = ReportingSnapshot(
            motion = decision.state,
            strategy = if (isConstant) "Constant" else "Dynamic",
            intervalSeconds = intervalSec,
            staleSeconds = stale.seconds,
            batteryStretched = intervalSec > baseSec,
            lastPliEpochMs = lastPliEpochMs,
            nextDueEpochMs = when {
                suppressed != SuppressReason.NONE || noPath -> 0L
                lastPliEpochMs == 0L || asap || identityDirty -> now
                else -> lastPliEpochMs + intervalSec * 1000L
            },
            gpsDuty = decision.gpsDuty,
            suppressed = if (suppressed == SuppressReason.NONE && noPath) SuppressReason.NO_PATH else suppressed,
            takConnected = connected,
            meshActive = meshWanted,
        )

        if (suppressed != SuppressReason.NONE) return
        if (noPath && !asap && !identityDirty) return

        val due = asap || identityDirty ||
            lastPliEpochMs == 0L ||
            now - lastPliEpochMs >= intervalSec * 1000L
        if (!due) return

        val useFix = fix ?: GpsFix(
            latitude = 0.0,
            longitude = 0.0,
            timestamp = Instant.now(),
            source = GpsSourceKind.NETWORK_IP,
            isHeld = true,
        )
        val active = IdentityResolver.resolve(config, deviceModel())
        val battery = batteryPercent()
        val model = deviceModel()
        val os = osVersion()

        var sent = 0
        if (connected) {
            sent += tak.sendToAll { profile ->
                val identity = CotEventBuilder.fromActiveIdentity(
                    config, active, profile, battery, model,
                ).copy(version = appVersion())
                CotEventBuilder.build(useFix, identity, stale, config.gps.courseOffsetDegrees, model, os)
            }
        }
        if (meshWanted) {
            val identity = CotEventBuilder.fromActiveIdentity(
                config, active, null, battery, model,
            ).copy(version = appVersion())
            val xml = CotEventBuilder.build(useFix, identity, stale, config.gps.courseOffsetDegrees, model, os)
            if (mesh.trySend(xml)) sent++
        }

        if (sent > 0) {
            lastPliEpochMs = System.currentTimeMillis()
            asap = false
            identityDirty = false
            lastSpeedMph = useFix.speedMph
            lastAlt = useFix.altitudeMeters ?: 0.0
            _snapshot.value = _snapshot.value.copy(
                lastPliEpochMs = lastPliEpochMs,
                nextDueEpochMs = lastPliEpochMs + intervalSec * 1000L,
            )
        }

        val alt = fix?.altitudeMeters
        if (rate.shouldReportAsap(lastAlt, alt, lastSpeedMph, speedMph)) asap = true
    }

    private fun shouldSendMesh(config: AppConfig, connected: Boolean): Boolean {
        if (!config.meshSa.enabled) return false
        return when (config.meshSa.mode.lowercase()) {
            "always" -> true
            else -> !connected
        }
    }
}
