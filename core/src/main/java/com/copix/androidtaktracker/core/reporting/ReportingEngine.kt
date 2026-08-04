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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

class ReportingEngine(
    private val log: RedactedLogger,
    private val tak: TakConnectionManager,
    private val mesh: MeshSaBroadcaster,
    private val configProvider: () -> AppConfig,
    private val fixProvider: () -> GpsFix?,
    private val paused: () -> Boolean,
    private val deferringToAtak: () -> Boolean,
    private val batteryPercent: () -> Int?,
    private val deviceModel: () -> String,
    private val osVersion: () -> String,
    private val appVersion: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    @Volatile private var asap = false
    @Volatile private var identityDirty = false
    @Volatile var lastPliEpochMs: Long = 0L
        private set

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

    private suspend fun tick() {
        if (paused()) return
        if (deferringToAtak()) return

        val config = configProvider()
        val fix = fixProvider()
        val connected = tak.anyConnected
        val meshWanted = shouldSendMesh(config, connected)

        if (!connected && !meshWanted) {
            if (!asap && !identityDirty) return
        }

        val intervalSec = computeIntervalSeconds(config, fix)
        val due = asap || identityDirty ||
            lastPliEpochMs == 0L ||
            System.currentTimeMillis() - lastPliEpochMs >= intervalSec * 1000L

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
        val stale = Duration.ofSeconds((intervalSec * 3L).coerceAtLeast(30))
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
        }

        val speedMph = fix?.speedMph ?: 0.0
        val alt = fix?.altitudeMeters ?: 0.0
        if (abs(speedMph - lastSpeedMph) > 7.0) asap = true
        if (abs(alt - lastAlt) > 50.0) asap = true
    }

    private fun computeIntervalSeconds(config: AppConfig, fix: GpsFix?): Int {
        val r = config.reporting
        if (r.strategy.equals("Constant", true)) {
            return r.constantIntervalSeconds.coerceAtLeast(5)
        }
        val speedMph = fix?.speedMph ?: 0.0
        val stationary = speedMph < 1.0
        val reliable = tak.anyConnected
        return if (reliable) {
            if (stationary) r.reliableStationarySeconds.coerceAtLeast(5)
            else r.reliableMinSeconds.coerceAtLeast(5).coerceAtMost(r.reliableMaxMoveSeconds)
        } else {
            if (stationary) r.unreliableStationarySeconds.coerceAtLeast(5)
            else r.unreliableMinSeconds.coerceAtLeast(5).coerceAtMost(r.unreliableMaxMoveSeconds)
        }
    }

    private fun shouldSendMesh(config: AppConfig, connected: Boolean): Boolean {
        if (!config.meshSa.enabled) return false
        return when (config.meshSa.mode.lowercase()) {
            "always" -> true
            else -> !connected
        }
    }
}
