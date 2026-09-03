package com.copix.androidtaktracker.core.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {

    @Test
    fun `sanitizeSpeedMph treats garbage as zero`() {
        assertEquals(0.0, MotionPolicy.sanitizeSpeedMph(Double.NaN), 0.0)
        assertEquals(0.0, MotionPolicy.sanitizeSpeedMph(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, MotionPolicy.sanitizeSpeedMph(-3.0), 0.0)
        assertEquals(12.5, MotionPolicy.sanitizeSpeedMph(12.5), 0.0)
    }

    @Test
    fun `gpsDuty buckets speed`() {
        assertEquals(GpsDuty.LOW, MotionPolicy.gpsDuty(0.0))
        assertEquals(GpsDuty.LOW, MotionPolicy.gpsDuty(2.4))
        assertEquals(GpsDuty.BALANCED, MotionPolicy.gpsDuty(2.5))
        assertEquals(GpsDuty.BALANCED, MotionPolicy.gpsDuty(7.9))
        assertEquals(GpsDuty.HIGH, MotionPolicy.gpsDuty(8.0))
        assertEquals(GpsDuty.HIGH, MotionPolicy.gpsDuty(55.0))
    }

    @Test
    fun `nextGpsDuty upgrades immediately and holds high through a brief stop`() {
        assertEquals(GpsDuty.HIGH, MotionPolicy.nextGpsDuty(GpsDuty.LOW, 20.0, 60_000))
        assertEquals(
            GpsDuty.HIGH,
            MotionPolicy.nextGpsDuty(GpsDuty.HIGH, 0.0, 10_000),
        )
        assertEquals(
            GpsDuty.LOW,
            MotionPolicy.nextGpsDuty(GpsDuty.HIGH, 0.0, 46_000),
        )
        assertEquals(
            GpsDuty.BALANCED,
            MotionPolicy.nextGpsDuty(GpsDuty.HIGH, 4.0, 46_000),
        )
    }

    @Test
    fun `treatAsStationary uses displacement over noisy walking-speed GPS`() {
        assertTrue(MotionPolicy.treatAsStationary(0.0, null))
        assertTrue(MotionPolicy.treatAsStationary(2.4, null))
        assertFalse(MotionPolicy.treatAsStationary(3.0, null))
        assertTrue(MotionPolicy.treatAsStationary(3.0, 5.0))
        assertFalse(MotionPolicy.treatAsStationary(3.0, 25.0))
        assertFalse(MotionPolicy.treatAsStationary(12.0, 5.0))
    }

    @Test
    fun `haversineMeters is zero at the same point and ~111 km per degree latitude`() {
        assertEquals(0.0, MotionPolicy.haversineMeters(38.0, -77.0, 38.0, -77.0), 0.01)
        val oneDegree = MotionPolicy.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_000.0, oneDegree, 2_000.0)
    }

    @Test
    fun `battery multiplier only applies when discharging and low`() {
        assertEquals(1.0, MotionPolicy.batteryIntervalMultiplier(10, charging = true), 0.0)
        assertEquals(1.0, MotionPolicy.batteryIntervalMultiplier(null, charging = false), 0.0)
        assertEquals(1.0, MotionPolicy.batteryIntervalMultiplier(50, charging = false), 0.0)
        assertEquals(1.5, MotionPolicy.batteryIntervalMultiplier(20, charging = false), 0.0)
        assertEquals(2.0, MotionPolicy.batteryIntervalMultiplier(10, charging = false), 0.0)
        assertEquals(270L, MotionPolicy.applyBatteryMultiplier(180, 20, charging = false))
        assertEquals(5L, MotionPolicy.applyBatteryMultiplier(5, 10, charging = false))
        assertEquals(20L, MotionPolicy.applyBatteryMultiplier(10, 10, charging = false))
    }

    @Test
    fun `low battery never opens a gap longer than five minutes`() {
        // 2× on the 180 s keepalive would be 360 s — capped at 300 s.
        assertEquals(300L, MotionPolicy.applyBatteryMultiplier(180, 10, charging = false))
        assertEquals(MotionPolicy.MAX_LOW_BATTERY_INTERVAL_SECONDS, MotionPolicy.applyBatteryMultiplier(250, 5, charging = false))
        // An operator-configured interval above the cap is left alone, not shrunk.
        assertEquals(400L, MotionPolicy.applyBatteryMultiplier(400, 10, charging = false))
        // Charging or healthy battery: untouched.
        assertEquals(180L, MotionPolicy.applyBatteryMultiplier(180, 10, charging = true))
        assertEquals(180L, MotionPolicy.applyBatteryMultiplier(180, 80, charging = false))
        // Stale still clears the stretched interval.
        assertTrue(MotionPolicy.staleDurationSeconds(300) > 300)
    }

    @Test
    fun `stale is at least 90s and always longer than the send interval`() {
        assertEquals(90L, MotionPolicy.staleDurationSeconds(5))
        assertEquals(90L, MotionPolicy.staleDurationSeconds(30))
        assertEquals(375L, MotionPolicy.staleDurationSeconds(180))
        assertTrue(MotionPolicy.staleDurationSeconds(180) > 180)
        assertTrue(MotionPolicy.staleDurationSeconds(5) > 5)
    }

    @Test
    fun `slowMoveSeconds sits between max-move and a quarter of stationary`() {
        assertEquals(45.0, MotionPolicy.slowMoveSeconds(180, 20), 0.0)
        assertEquals(20.0, MotionPolicy.slowMoveSeconds(40, 20), 0.0)
        assertEquals(60.0, MotionPolicy.slowMoveSeconds(400, 20), 0.0)
    }
}
