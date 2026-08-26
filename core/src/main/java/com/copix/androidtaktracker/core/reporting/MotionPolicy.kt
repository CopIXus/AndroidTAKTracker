package com.copix.androidtaktracker.core.reporting

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Fused location power profile — HIGH burns GNSS; LOW/BALANCED save battery. */
enum class GpsDuty {
    /** GNSS-heavy: driving, unknown, or first fix. */
    HIGH,
    /** Walking: balanced fused updates. */
    BALANCED,
    /** Still or GPS-jitter only. */
    LOW,
}

/**
 * Shared motion thresholds for Dynamic CoT and adaptive GPS.
 *
 * GPS speed while standing still commonly jitters 0–3 mph; a 1 mph cutoff therefore
 * looked like "moving" and hammered TAK + GNSS. Displacement is the source of truth
 * for "did I actually relocate?"; speed only selects the moving cadence.
 */
object MotionPolicy {
    const val STATIONARY_SPEED_MPH = 2.5
    const val WALKING_SPEED_MPH = 8.0
    const val FAST_SPEED_MPH = 30.0
    const val DEFAULT_SIGNIFICANT_MOVE_METERS = 20.0
    const val MIN_STALE_SECONDS = 90L
    const val GPS_DOWNGRADE_HOLD_MS = 45_000L
    const val LOW_BATTERY_PERCENT = 20
    const val CRITICAL_BATTERY_PERCENT = 15
    const val MIN_INTERVAL_SECONDS = 5L

    fun sanitizeSpeedMph(speedMph: Double): Double {
        if (speedMph.isNaN() || speedMph.isInfinite() || speedMph < 0.0) return 0.0
        return speedMph
    }

    fun gpsDuty(speedMph: Double): GpsDuty {
        val speed = sanitizeSpeedMph(speedMph)
        return when {
            speed >= WALKING_SPEED_MPH -> GpsDuty.HIGH
            speed >= STATIONARY_SPEED_MPH -> GpsDuty.BALANCED
            else -> GpsDuty.LOW
        }
    }

    /**
     * Upgrade GNSS immediately when speed says we are moving; hold HIGH for
     * [downgradeHoldMs] after the last high-speed sample so a stoplight does not
     * drop GPS before we know we stopped.
     */
    fun nextGpsDuty(
        current: GpsDuty,
        sampleSpeedMph: Double,
        millisSinceHighSpeed: Long,
        downgradeHoldMs: Long = GPS_DOWNGRADE_HOLD_MS,
    ): GpsDuty {
        val desired = gpsDuty(sampleSpeedMph)
        if (desired == GpsDuty.HIGH) return GpsDuty.HIGH
        if (millisSinceHighSpeed < downgradeHoldMs && current == GpsDuty.HIGH) return GpsDuty.HIGH
        return desired
    }

    /**
     * True when the operator has not meaningfully relocated. Walking+ speed always
     * counts as moving even if the last PLI was a few meters ago (first samples).
     */
    fun treatAsStationary(
        speedMph: Double,
        metersSinceLastPli: Double?,
        significantMoveMeters: Double = DEFAULT_SIGNIFICANT_MOVE_METERS,
    ): Boolean {
        val speed = sanitizeSpeedMph(speedMph)
        if (speed >= WALKING_SPEED_MPH) return false
        val threshold = significantMoveMeters.let { if (it.isNaN() || it <= 0.0) DEFAULT_SIGNIFICANT_MOVE_METERS else it }
        val moved = metersSinceLastPli
        return if (moved == null) speed < STATIONARY_SPEED_MPH
        else moved < threshold
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1.isNaN() || lon1.isNaN() || lat2.isNaN() || lon2.isNaN()) return Double.POSITIVE_INFINITY
        val earth = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return earth * c
    }

    /** Stretch Dynamic intervals when the battery is low; charging restores 1.0. */
    fun batteryIntervalMultiplier(batteryPercent: Int?, charging: Boolean): Double {
        if (charging || batteryPercent == null) return 1.0
        if (batteryPercent !in 0..100) return 1.0
        if (batteryPercent <= CRITICAL_BATTERY_PERCENT) return 2.0
        if (batteryPercent <= LOW_BATTERY_PERCENT) return 1.5
        return 1.0
    }

    fun applyBatteryMultiplier(intervalSeconds: Long, batteryPercent: Int?, charging: Boolean): Long {
        // Driving already uses the 5s floor — do not stretch that; only slow keepalives.
        if (intervalSeconds <= MIN_INTERVAL_SECONDS) return MIN_INTERVAL_SECONDS
        val scaled = intervalSeconds * batteryIntervalMultiplier(batteryPercent, charging)
        return maxOf(MIN_INTERVAL_SECONDS, scaled.toLong())
    }

    fun staleDurationSeconds(intervalSeconds: Long): Long {
        val computed = intervalSeconds * 2 + 15
        return maxOf(MIN_STALE_SECONDS, computed)
    }

    /**
     * Walking band sits between [maxMoveSeconds] and a slower "slow move" derived from
     * the stationary setting so 3 mph is not treated like a vehicle.
     */
    fun slowMoveSeconds(stationarySeconds: Int, maxMoveSeconds: Int): Double {
        val cap = minOf(stationarySeconds / 4.0, 60.0)
        return maxOf(maxMoveSeconds.toDouble(), cap)
    }
}
