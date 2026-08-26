package com.copix.androidtaktracker.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.copix.androidtaktracker.core.config.GpsSettings
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.gps.NetworkIpGeolocation
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.copix.androidtaktracker.core.reporting.MotionPolicy
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
    val duty: StateFlow<GpsDuty> = _duty

    private var settings = GpsSettings()
    private var holdJob: Job? = null
    private var armJob: Job? = null
    private var callback: LocationCallback? = null
    private var lastRequestKey: String? = null
    private var lastHighSpeedMs = 0L
    private val networkIp = NetworkIpGeolocation(scope).also { geo ->
        geo.onFixReceived = { ipFix ->
            val cur = _fix.value
            if (cur == null || cur.isHeld || cur.source != GpsSourceKind.FUSED) {
                _fix.value = ipFix
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(gps: GpsSettings) {
        settings = gps
        stop()
        lastHighSpeedMs = System.currentTimeMillis()
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
                networkIp.stop()
                scheduleHold()
                adaptDuty(next.speedMph)
            }
        }
        callback = cb
        requestUpdates(_duty.value)
        if (callback == null) return
        if (gps.enableNetworkFallback || gps.sourcePriority.contains("Network", true)) {
            armNetwork(immediate = false)
        }
    }

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
        } catch (_: SecurityException) {
            callback = null
            lastRequestKey = null
            armNetwork(immediate = true)
        }
    }

    private fun adaptDuty(speedMph: Double) {
        if (!settings.adaptToMotion) {
            if (_duty.value != GpsDuty.HIGH) {
                _duty.value = GpsDuty.HIGH
                requestUpdates(GpsDuty.HIGH)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (MotionPolicy.gpsDuty(speedMph) == GpsDuty.HIGH) lastHighSpeedMs = now
        val next = MotionPolicy.nextGpsDuty(_duty.value, speedMph, now - lastHighSpeedMs)
        if (next == _duty.value && lastRequestKey != null) return
        _duty.value = next
        requestUpdates(next)
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
                        minDistanceMeters = maxOf(gps.minDistanceMeters, 8f),
                    )
                    GpsDuty.LOW -> LocationSpec(
                        priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        intervalMs = 20_000L,
                        minDistanceMeters = maxOf(gps.minDistanceMeters, 15f),
                        minIntervalMs = 10_000L,
                    )
                }
            }
        }
    }
}
