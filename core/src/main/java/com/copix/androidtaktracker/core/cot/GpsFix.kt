package com.copix.androidtaktracker.core.cot

import java.time.Instant

enum class GpsSourceKind {
    NONE,
    /** Android FusedLocationProviderClient (GPS/Wi-Fi/cell fused fix). */
    FUSED,
    /** Approximate IP-based geolocation (large CE; not precision GPS). */
    NETWORK_IP,
    /** Last known fix held past its normal validity window. */
    HELD,
}

data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    /** Speed in meters/second. */
    val speedMetersPerSecond: Double? = null,
    /** Course in degrees true. */
    val courseDegrees: Double? = null,
    /** Horizontal accuracy / CE in meters. */
    val accuracyMeters: Double? = null,
    val hdop: Double? = null,
    val timestamp: Instant = Instant.now(),
    val source: GpsSourceKind = GpsSourceKind.NONE,
    val isHeld: Boolean = false,
) {
    val hasFix: Boolean get() = !latitude.isNaN() && !longitude.isNaN()

    val speedMph: Double
        get() {
            val mps = speedMetersPerSecond ?: 0.0
            if (mps.isNaN() || mps.isInfinite()) return 0.0
            return mps * 2.23693629
        }

    val sourceDisplayName: String
        get() = when (source) {
            GpsSourceKind.FUSED -> "Fused (GPS/Wi-Fi/cell)"
            GpsSourceKind.NETWORK_IP -> "Network IP (approximate)"
            GpsSourceKind.HELD -> "Held (last fix)"
            GpsSourceKind.NONE -> "None"
        }

    /** Snapshot marked as held (past validity) with a wider accuracy circle. */
    fun asHeld(): GpsFix = copy(
        accuracyMeters = accuracyMeters?.times(2) ?: 50.0,
        source = GpsSourceKind.HELD,
        isHeld = true,
    )
}

enum class GpsPermissionState { UNKNOWN, ALLOWED, DENIED, NOT_AVAILABLE }
