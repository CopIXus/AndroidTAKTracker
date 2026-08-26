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
    fun `balanced and low duties step down power and add a distance floor`() {
        val settings = GpsSettings()
        val balanced = FusedGpsRepository.LocationSpec.from(settings, GpsDuty.BALANCED)
        val low = FusedGpsRepository.LocationSpec.from(settings, GpsDuty.LOW)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, balanced.priority)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, low.priority)
        assertEquals(8f, balanced.minDistanceMeters, 0f)
        assertEquals(15f, low.minDistanceMeters, 0f)
        assertTrue(low.intervalMs > balanced.intervalMs)
        assertTrue(balanced.intervalMs > specHighInterval())
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
