package com.copix.androidtaktracker.gps

import com.copix.androidtaktracker.core.config.GpsSettings
import com.copix.androidtaktracker.core.reporting.GpsDuty
import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSpecTest {

    @Test
    fun `high duty uses GNSS and the configured interval`() {
        val spec = FusedGpsRepository.LocationSpec.from(GpsSettings(), GpsDuty.HIGH)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, spec.priority)
        assertEquals(2_000L, spec.intervalMs)
        assertEquals(0f, spec.minDistanceMeters, 0f)
    }

    @Test
    fun `balanced and low duties step down power and slow the cadence without distance filters`() {
        val settings = GpsSettings()
        val balanced = FusedGpsRepository.LocationSpec.from(settings, GpsDuty.BALANCED)
        val low = FusedGpsRepository.LocationSpec.from(settings, GpsDuty.LOW)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, balanced.priority)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, low.priority)
        // The motion classifier needs periodic samples to notice settling / movement, and the
        // fix must not age into "held" while parked — so no distance filter is imposed.
        assertEquals(settings.minDistanceMeters, balanced.minDistanceMeters, 0f)
        assertEquals(settings.minDistanceMeters, low.minDistanceMeters, 0f)
        assertTrue(low.intervalMs > balanced.intervalMs)
        assertTrue(balanced.intervalMs > specHighInterval())
        // LOW must still refresh inside the default 30 s last-fix hold window.
        assertTrue(low.intervalMs < GpsSettings().lastFixHoldSeconds * 1000L)
    }

    @Test
    fun `request keys differ per duty so a duty change re-requests updates`() {
        val settings = GpsSettings()
        val keys = GpsDuty.entries.map { FusedGpsRepository.LocationSpec.from(settings, it).key }.toSet()
        assertEquals(3, keys.size)
    }

    @Test
    fun `adaptToMotion off keeps high accuracy regardless of duty`() {
        val settings = GpsSettings(adaptToMotion = false)
        val low = FusedGpsRepository.LocationSpec.from(settings, GpsDuty.LOW)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, low.priority)
        assertEquals(2_000L, low.intervalMs)
    }

    private fun specHighInterval(): Long =
        FusedGpsRepository.LocationSpec.from(GpsSettings(), GpsDuty.HIGH).intervalMs
}
