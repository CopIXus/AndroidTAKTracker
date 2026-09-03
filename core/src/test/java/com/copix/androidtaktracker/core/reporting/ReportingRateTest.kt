package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.config.ReportingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportingRateTest {

    private val settings = ReportingSettings()
    private val adaptive = AdaptiveReportingRate(settings)
    private val constant = ConstantReportingRate(settings)

    @Test
    fun `stationary on a reliable path uses the 180s keepalive`() {
        val interval = adaptive.getInterval(ReportingPath.RELIABLE, 0.0, 0.0)
        assertEquals(180L, interval.seconds)
        assertTrue(adaptive.getStale(interval).seconds > interval.seconds)
        assertTrue(adaptive.getStale(interval).seconds >= MotionPolicy.MIN_STALE_SECONDS)
    }

    @Test
    fun `gps jitter under 20m stays on the stationary cadence`() {
        val still = adaptive.getInterval(ReportingPath.RELIABLE, 2.8, 8.0)
        assertEquals(180L, still.seconds)
    }

    @Test
    fun `walking after a real relocate is slower than the old 20s vehicle floor`() {
        val walk = adaptive.getInterval(ReportingPath.RELIABLE, 3.0, 30.0)
        assertTrue(walk.seconds in 30L..50L)
    }

    @Test
    fun `highway speed uses the 5s minimum`() {
        val fast = adaptive.getInterval(ReportingPath.RELIABLE, 65.0, 200.0)
        assertEquals(5L, fast.seconds)
        assertEquals(90L, adaptive.getStale(fast).seconds)
    }

    @Test
    fun `unreliable path keeps a shorter stationary keepalive for mesh`() {
        val interval = adaptive.getInterval(ReportingPath.UNRELIABLE, 0.0, 0.0)
        assertEquals(30L, interval.seconds)
        assertEquals(90L, adaptive.getStale(interval).seconds)
    }

    @Test
    fun `ASAP fires on altitude or speed jumps`() {
        assertTrue(adaptive.shouldReportAsap(10.0, 70.0, 5.0, 5.0))
        assertTrue(adaptive.shouldReportAsap(10.0, 12.0, 5.0, 13.0))
        assertFalse(adaptive.shouldReportAsap(10.0, 12.0, 5.0, 6.0))
    }

    @Test
    fun `constant strategy ignores speed and displacement`() {
        assertEquals(10L, constant.getInterval(ReportingPath.RELIABLE, 0.0, 0.0).seconds)
        assertEquals(10L, constant.getInterval(ReportingPath.RELIABLE, 70.0, 500.0).seconds)
        assertFalse(constant.shouldReportAsap(0.0, 100.0, 0.0, 50.0))
    }

    @Test
    fun `constant strategy keeps its original stale formula`() {
        // Pre-existing behaviour: 2 × interval + 15 s, no 90 s floor.
        val interval = constant.getInterval(ReportingPath.RELIABLE, 0.0, null)
        assertEquals(35L, constant.getStale(interval).seconds)
        val custom = ConstantReportingRate(ReportingSettings(constantIntervalSeconds = 60))
        assertEquals(135L, custom.getStale(custom.getInterval(ReportingPath.UNRELIABLE, 0.0, null)).seconds)
        assertTrue(constant.getStale(interval).seconds > interval.seconds)
    }

    @Test
    fun `dynamic stale always exceeds the interval across every band`() {
        val cases = listOf(
            adaptive.getInterval(ReportingPath.RELIABLE, 0.0, 0.0),        // stationary
            adaptive.getInterval(ReportingPath.RELIABLE, 3.0, null),       // walking
            adaptive.getInterval(ReportingPath.RELIABLE, 15.0, null),      // slow vehicle
            adaptive.getInterval(ReportingPath.RELIABLE, 65.0, null),      // highway
            adaptive.getInterval(ReportingPath.UNRELIABLE, 0.0, 0.0),      // mesh stationary
            java.time.Duration.ofSeconds(MotionPolicy.applyBatteryMultiplier(180, 10, false)),
        )
        cases.forEach { interval ->
            val stale = adaptive.getStale(interval)
            assertTrue("stale ${stale.seconds} <= interval ${interval.seconds}", stale.seconds > interval.seconds)
            assertTrue(stale.seconds >= MotionPolicy.MIN_STALE_SECONDS)
        }
    }

    @Test
    fun `displacement below the threshold keeps a walking-speed jitter on the stationary cadence`() {
        // Anchored, 8 m from the anchor, GPS says 3 mph — still parked.
        assertEquals(180L, adaptive.getInterval(ReportingPath.RELIABLE, 3.0, 8.0).seconds)
        // Not anchored (null) at 3 mph — genuinely walking.
        assertTrue(adaptive.getInterval(ReportingPath.RELIABLE, 3.0, null).seconds in 30L..50L)
    }

    @Test
    fun `factory selects constant only when asked`() {
        val dyn = ReportingRateFactory.create(ReportingSettings(strategy = "Dynamic"))
        val con = ReportingRateFactory.create(ReportingSettings(strategy = "Constant"))
        assertTrue(dyn is AdaptiveReportingRate)
        assertTrue(con is ConstantReportingRate)
    }
}
