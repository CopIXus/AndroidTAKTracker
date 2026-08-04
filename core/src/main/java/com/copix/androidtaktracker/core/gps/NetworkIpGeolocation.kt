package com.copix.androidtaktracker.core.gps

import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Approximate IP geolocation fallback (browser-style), used only when Fused location has no fix.
 * Provider: ipwho.is over HTTPS (no API key). Accuracy is city/region scale — never treated as
 * precision GPS. Mirrors WinTAKTracker's NetworkIpGeolocationGps polling shape.
 */
class NetworkIpGeolocation(
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(15))
        .build(),
) {
    companion object {
        /** Default horizontal CE for IP-based fixes (meters). Intentionally large. */
        const val DEFAULT_ACCURACY_METERS = 25_000.0
        private const val ENDPOINT = "https://ipwho.is/"
        private val REFRESH_INTERVAL_MS = Duration.ofMinutes(5).toMillis()

        private val LAT_PATTERN = Regex(""""latitude"\s*:\s*(-?\d+(?:\.\d+)?)""")
        private val LON_PATTERN = Regex(""""longitude"\s*:\s*(-?\d+(?:\.\d+)?)""")
        private val SUCCESS_FALSE_PATTERN = Regex(""""success"\s*:\s*false""")
        private val MESSAGE_PATTERN = Regex(""""message"\s*:\s*"([^"]*)"""")

        /** Extracts latitude/longitude from an ipwho.is JSON body without a full JSON parser. */
        internal fun parseLatLon(body: String): Pair<Double, Double>? {
            val lat = LAT_PATTERN.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            val lon = LON_PATTERN.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return lat to lon
        }

        internal fun failureMessage(body: String): String? {
            if (!SUCCESS_FALSE_PATTERN.containsMatchIn(body)) return null
            return MESSAGE_PATTERN.find(body)?.groupValues?.get(1) ?: "lookup failed"
        }
    }

    private var refreshJob: Job? = null
    private val refreshing = AtomicBoolean(false)
    @Volatile private var running = false

    var onFixReceived: ((GpsFix) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    @Volatile var lastFix: GpsFix? = null
        private set

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        refreshJob = scope.launch {
            refresh()
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    fun stop() {
        running = false
        refreshJob?.cancel()
        refreshJob = null
    }

    suspend fun refresh() {
        if (!running || !refreshing.compareAndSet(false, true)) return
        try {
            val body = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("User-Agent", "AndroidTAKTracker/0.1")
                    .header("Accept", "application/json")
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        onError?.invoke("IP geolocation HTTP ${resp.code}")
                        return@withContext null
                    }
                    resp.body?.string()
                }
            } ?: return

            val failure = failureMessage(body)
            if (failure != null) {
                onError?.invoke("IP geolocation: $failure")
                return
            }

            val coords = parseLatLon(body)
            if (coords == null) {
                onError?.invoke("IP geolocation response missing coordinates.")
                return
            }

            val (lat, lon) = coords
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                onError?.invoke("IP geolocation returned invalid coordinates.")
                return
            }

            val fix = GpsFix(
                latitude = lat,
                longitude = lon,
                accuracyMeters = DEFAULT_ACCURACY_METERS,
                timestamp = Instant.now(),
                source = GpsSourceKind.NETWORK_IP,
            )
            lastFix = fix
            onFixReceived?.invoke(fix)
        } catch (ex: Exception) {
            onError?.invoke(ex.message ?: ex.javaClass.simpleName)
        } finally {
            refreshing.set(false)
        }
    }
}
