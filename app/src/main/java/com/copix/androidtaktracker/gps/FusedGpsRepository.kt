package com.copix.androidtaktracker.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.copix.androidtaktracker.core.config.GpsSettings
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import com.copix.androidtaktracker.core.gps.NetworkIpGeolocation
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
 */
class FusedGpsRepository(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix

    private var settings = GpsSettings()
    private var holdJob: Job? = null
    private var armJob: Job? = null
    private var callback: LocationCallback? = null
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
        if (gps.sourcePriority.equals("NetworkOnly", true)) {
            armNetwork(immediate = true)
            return
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            gps.minIntervalMs.coerceAtLeast(1000L),
        )
            .setMinUpdateIntervalMillis(2_000L)
            .setMinUpdateDistanceMeters(gps.minDistanceMeters)
            .setWaitForAccurateLocation(false)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _fix.value = GpsFix(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                    accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    speedMetersPerSecond = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                    courseDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                    timestamp = Instant.ofEpochMilli(if (loc.time > 0) loc.time else System.currentTimeMillis()),
                    source = GpsSourceKind.FUSED,
                )
                networkIp.stop()
                scheduleHold()
            }
        }
        callback = cb
        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (_: SecurityException) {
            armNetwork(immediate = true)
            return
        }
        if (gps.enableNetworkFallback || gps.sourcePriority.contains("Network", true)) {
            armNetwork(immediate = false)
        }
    }

    fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
        holdJob?.cancel()
        armJob?.cancel()
        networkIp.stop()
    }

    fun applySettings(gps: GpsSettings) {
        start(gps)
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
}
