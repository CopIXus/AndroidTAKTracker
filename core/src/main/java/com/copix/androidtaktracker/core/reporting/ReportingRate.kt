package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.config.ReportingSettings
import java.time.Duration

enum class ReportingPath { RELIABLE, UNRELIABLE }

interface ReportingRate {
    fun getInterval(path: ReportingPath, speedMph: Double, metersSinceLastPli: Double? = null): Duration
    fun shouldReportAsap(previousAltM: Double?, currentAltM: Double?, previousSpeedMph: Double?, currentSpeedMph: Double): Boolean
    fun getStale(interval: Duration): Duration
}

/** ATAK-style Dynamic reporting rate (reliable vs unreliable paths). */
class AdaptiveReportingRate(private val settings: ReportingSettings) : ReportingRate {
    override fun getInterval(path: ReportingPath, speedMph: Double, metersSinceLastPli: Double?): Duration {
        val speed = MotionPolicy.sanitizeSpeedMph(speedMph)

        val stationary = if (path == ReportingPath.RELIABLE) settings.reliableStationarySeconds else settings.unreliableStationarySeconds
        val min = if (path == ReportingPath.RELIABLE) settings.reliableMinSeconds else settings.unreliableMinSeconds
        val maxMove = if (path == ReportingPath.RELIABLE) settings.reliableMaxMoveSeconds else settings.unreliableMaxMoveSeconds

        val significant = settings.significantMoveMeters
        if (MotionPolicy.treatAsStationary(speed, metersSinceLastPli, significant)) {
            return Duration.ofSeconds(maxOf(MotionPolicy.MIN_INTERVAL_SECONDS, stationary.toLong()))
        }

        // Floor at 5s so Dynamic rates never hammer TAK/mesh.
        if (speed >= MotionPolicy.FAST_SPEED_MPH) {
            return Duration.ofSeconds(maxOf(MotionPolicy.MIN_INTERVAL_SECONDS, min.toLong()))
        }

        val slowMove = MotionPolicy.slowMoveSeconds(stationary, maxMove)
        val seconds = if (speed < MotionPolicy.WALKING_SPEED_MPH) {
            // 2.5–8 mph: slowMove → maxMove (walk, do not treat as a vehicle)
            val span = MotionPolicy.WALKING_SPEED_MPH - MotionPolicy.STATIONARY_SPEED_MPH
            val t = ((speed - MotionPolicy.STATIONARY_SPEED_MPH) / span).coerceIn(0.0, 1.0)
            slowMove + (maxMove - slowMove) * t
        } else {
            // 8–30 mph: maxMove → min
            val span = MotionPolicy.FAST_SPEED_MPH - MotionPolicy.WALKING_SPEED_MPH
            val t = ((speed - MotionPolicy.WALKING_SPEED_MPH) / span).coerceIn(0.0, 1.0)
            maxMove + (min - maxMove) * t
        }
        val safe = if (seconds.isNaN() || seconds.isInfinite()) stationary.toDouble() else seconds
        return Duration.ofSeconds(maxOf(MotionPolicy.MIN_INTERVAL_SECONDS.toDouble(), safe).toLong())
    }

    override fun shouldReportAsap(
        previousAltM: Double?,
        currentAltM: Double?,
        previousSpeedMph: Double?,
        currentSpeedMph: Double,
    ): Boolean {
        if (previousAltM != null && currentAltM != null && kotlin.math.abs(currentAltM - previousAltM) > 50) return true
        if (previousSpeedMph != null && kotlin.math.abs(currentSpeedMph - previousSpeedMph) > 7) return true
        return false
    }

    override fun getStale(interval: Duration): Duration =
        Duration.ofSeconds(MotionPolicy.staleDurationSeconds(interval.seconds.coerceAtLeast(0)))
}

/** Fixed-interval reporting for both paths. */
class ConstantReportingRate(private val settings: ReportingSettings) : ReportingRate {
    override fun getInterval(path: ReportingPath, speedMph: Double, metersSinceLastPli: Double?): Duration =
        Duration.ofSeconds(maxOf(MotionPolicy.MIN_INTERVAL_SECONDS, settings.constantIntervalSeconds.toLong()))

    override fun shouldReportAsap(
        previousAltM: Double?,
        currentAltM: Double?,
        previousSpeedMph: Double?,
        currentSpeedMph: Double,
    ): Boolean = false

    override fun getStale(interval: Duration): Duration =
        Duration.ofSeconds(MotionPolicy.staleDurationSeconds(interval.seconds.coerceAtLeast(0)))
}

object ReportingRateFactory {
    fun create(settings: ReportingSettings): ReportingRate =
        if (settings.strategy.equals("Constant", ignoreCase = true)) ConstantReportingRate(settings)
        else AdaptiveReportingRate(settings)
}
