package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.config.ReportingSettings
import java.time.Duration

enum class ReportingPath { RELIABLE, UNRELIABLE }

interface ReportingRate {
    fun getInterval(path: ReportingPath, speedMph: Double): Duration
    fun shouldReportAsap(previousAltM: Double?, currentAltM: Double?, previousSpeedMph: Double?, currentSpeedMph: Double): Boolean
    fun getStale(interval: Duration): Duration
}

/** ATAK-style Dynamic reporting rate (reliable vs unreliable paths). */
class AdaptiveReportingRate(private val settings: ReportingSettings) : ReportingRate {
    override fun getInterval(path: ReportingPath, speedMph: Double): Duration {
        var speed = speedMph
        if (speed.isNaN() || speed.isInfinite()) speed = 0.0

        val stationary = if (path == ReportingPath.RELIABLE) settings.reliableStationarySeconds else settings.unreliableStationarySeconds
        val min = if (path == ReportingPath.RELIABLE) settings.reliableMinSeconds else settings.unreliableMinSeconds
        val maxMove = if (path == ReportingPath.RELIABLE) settings.reliableMaxMoveSeconds else settings.unreliableMaxMoveSeconds

        // Floor at 5s so Dynamic rates never hammer TAK/mesh.
        if (speed < 1.0) return Duration.ofSeconds(maxOf(5, stationary).toLong())
        if (speed >= 30.0) return Duration.ofSeconds(maxOf(5, min).toLong())

        // Linear interpolate 1-30 mph: maxMove -> min
        val t = (speed - 1.0) / 29.0
        var seconds = maxMove + (min - maxMove) * t
        if (seconds.isNaN() || seconds.isInfinite()) seconds = stationary.toDouble()
        return Duration.ofSeconds(maxOf(5.0, seconds).toLong())
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

    override fun getStale(interval: Duration): Duration = interval.multipliedBy(2).plusSeconds(15)
}

/** Fixed-interval reporting for both paths. */
class ConstantReportingRate(private val settings: ReportingSettings) : ReportingRate {
    override fun getInterval(path: ReportingPath, speedMph: Double): Duration =
        Duration.ofSeconds(maxOf(5, settings.constantIntervalSeconds).toLong())

    override fun shouldReportAsap(
        previousAltM: Double?,
        currentAltM: Double?,
        previousSpeedMph: Double?,
        currentSpeedMph: Double,
    ): Boolean = false

    override fun getStale(interval: Duration): Duration = interval.multipliedBy(2).plusSeconds(15)
}

object ReportingRateFactory {
    fun create(settings: ReportingSettings): ReportingRate =
        if (settings.strategy.equals("Constant", ignoreCase = true)) ConstantReportingRate(settings)
        else AdaptiveReportingRate(settings)
}
