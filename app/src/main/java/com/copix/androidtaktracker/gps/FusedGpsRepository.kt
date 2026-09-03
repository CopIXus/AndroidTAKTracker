package com.copix.androidtaktracker.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.copix.androidtaktracker.core.config.GpsSettings
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.gps.NetworkIpGeolocation
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * FusedLocationProvider + last-fix hold + delayed IP geolocation fallback (ipwho.is).
 * Matches WinTAKTracker GPS priority / arm / re-arm behaviour where Android APIs allow.
 *
 * When [GpsSettings.adaptToMotion] is on, the location request steps down from
 * [Priority.PRIORITY_HIGH_ACCURACY] while still or walking and snaps back on a real move.
 */
class FusedGpsRepository(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix

    private val _duty = MutableStateFlow(GpsDuty.HIGH)
    /** Power profile currently requested from the fused provider (driven by the reporting engine). */
    val duty: StateFlow<GpsDuty> = _duty

    private val _permissionMissing = MutableStateFlow(false)
    /** True when the last location request was rejected for lack of a location permission. */
    val permissionMissing: StateFlow<Boolean> = _permissionMissing

    private var settings = GpsSettings()
    private var holdJob: Job? = null
    private var armJob: Job? = null
    private var callback: LocationCallback? = null
    private var lastRequestKey: String? = null
    private val networkIp = NetworkIpGeolocation(scope).also { geo ->
        geo.onFixReceived = { ipFix ->
            val cur = _fix.value
            if (cur == null || cur.isHeld || cur.source != GpsSourceKind.FUSED) {
                _fix.value = ipFix
            }
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(gps: GpsSettings) {
        settings = gps
        stop()
        // Always open on high accuracy so the first fix after (re)start is a real one; the
        // reporting engine steps the duty down once it knows we are still.
        _duty.value = GpsDuty.HIGH
        lastRequestKey = null
        if (gps.sourcePriority.equals("NetworkOnly", true)) {
            armNetwork(immediate = true)
            return
        }
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val next = GpsFix(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    speedMetersPerSecond = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    courseDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    timestamp = Instant.ofEpochMilli(if (loc.time > 0) loc.time else System.currentTimeMillis()),
                    source = GpsSourceKind.FUSED,
                )
                _fix.value = next
                _permissionMissing.value = false
                networkIp.stop()
                scheduleHold()
            }
        }
        callback = cb
        requestUpdates(_duty.value)
        if (callback == null) return
        if (gps.enableNetworkFallback || gps.sourcePriority.contains("Network", true)) {
            armNetwork(immediate = false)
        }
    }

    @Synchronized
    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        lastRequestKey = null
        holdJob?.cancel()
        armJob?.cancel()
        networkIp.stop()
    }

    fun applySettings(gps: GpsSettings) {
        start(gps)
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(duty: GpsDuty) {
        val cb = callback ?: return
        val spec = LocationSpec.from(settings, duty)
        if (spec.key == lastRequestKey) return
        lastRequestKey = spec.key
        val request = LocationRequest.Builder(spec.priority, spec.intervalMs)
            .setMinUpdateIntervalMillis(spec.minIntervalMs)
            .setMinUpdateDistanceMeters(spec.minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            _permissionMissing.value = false
        } catch (_: SecurityException) {
            callback = null
            lastRequestKey = null
            _permissionMissing.value = true
            armNetwork(immediate = true)
        }
    }

    /**
     * Apply the power profile chosen by the reporting engine's motion classifier. A no-op
     * unless the resulting request differs; ignored while GNSS is stopped or when the
     * operator turned off [GpsSettings.adaptToMotion].
     */
    @Synchronized
    fun setDuty(duty: GpsDuty) {
        val effective = if (settings.adaptToMotion) duty else GpsDuty.HIGH
        if (_duty.value == effective && lastRequestKey != null) return
        _duty.value = effective
        requestUpdates(effective)
    }

    private fun scheduleHold() {
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(settings.lastFixHoldSeconds.coerceAtLeast(5) * 1000L)
            val current = _fix.value
            if (current != null && current.source == GpsSourceKind.FUSED && !current.isHeld) {
                _fix.value = current.asHeld()
                if (settings.enableNetworkFallback) armNetwork(immediate = true, rearm = true)
            }
        }
    }

    private fun armNetwork(immediate: Boolean, rearm: Boolean = false) {
        armJob?.cancel()
        armJob = scope.launch {
            if (!immediate) delay(18_000)
            if (!rearm) {
                val cur = _fix.value
                if (cur != null && !cur.isHeld && cur.source == GpsSourceKind.FUSED) return@launch
            }
            if (!networkIp.isRunning) networkIp.start()
            else networkIp.refresh()
        }
    }

    internal data class LocationSpec(
        val priority: Int,
        val intervalMs: Long,
        val minIntervalMs: Long,
        val minDistanceMeters: Float,
    ) {
        val key: String get() = "$priority:$intervalMs:$minIntervalMs:$minDistanceMeters"

        companion object {
            /**
             * No extra distance filters: the motion classifier needs periodic samples to
             * notice that we settled or started moving again, and a suppressed callback
             * would otherwise let the fix age into "held" while parked.
             */
            fun from(gps: GpsSettings, duty: GpsDuty): LocationSpec {
                val effective = if (gps.adaptToMotion) duty else GpsDuty.HIGH
                return when (effective) {
                    GpsDuty.HIGH -> LocationSpec(
                        priority = Priority.PRIORITY_HIGH_ACCURACY,
                        intervalMs = gps.minIntervalMs.coerceAtLeast(1_000L),
                        minIntervalMs = 2_000L,
                        minDistanceMeters = gps.minDistanceMeters,
                    )
                    GpsDuty.BALANCED -> LocationSpec(
                        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        intervalMs = 8_000L,
                        minIntervalMs = 5_000L,
                        minDistanceMeters = gps.minDistanceMeters,
                    )
                    GpsDuty.LOW -> LocationSpec(
                        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        intervalMs = 15_000L,
                        minIntervalMs = 10_000L,
                        minDistanceMeters = gps.minDistanceMeters,
                    )
                }
            }
        }
    }
}
