package com.copix.androidtaktracker.core.reporting

import com.copix.androidtaktracker.core.config.ReportingSettings
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.cot.GpsSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MotionClassifierTest {

    private var nowMs = 1_000_000L
    private val classifier = MotionClassifier(clock = { nowMs })
    private val rate = AdaptiveReportingRate(ReportingSettings())

    private val baseLat = 38.0
    private val baseLon = -77.0
    private val mps = 0.44704 // 1 mph

    /** ~1 m of latitude in degrees. */
    private val metersLat = 1.0 / 111_000.0

    private fun fix(northMeters: Double, speedMph: Double, held: Boolean = false) = GpsFix(
        latitude = baseLat + northMeters * metersLat,
        longitude = baseLon,
        speedMetersPerSecond = speedMph * mps,
        accuracyMeters = 5.0,
        timestamp = Instant.ofEpochMilli(nowMs),
        source = if (held) GpsSourceKind.HELD else GpsSourceKind.FUSED,
        isHeld = held,
    )

    private fun advance(ms: Long) { nowMs += ms }

    /** Feed jittery parked samples until the classifier anchors. */
    private fun settle(): MotionDecision {
        var d = classifier.observe(fix(0.0, 0.0))
        repeat(20) {
            advance(2_000)
            // 0–3 mph jitter and a few meters of wander — what a parked phone really reports.
            d = classifier.observe(fix((it % 3) * 2.0, (it % 4) * 0.8))
        }
        return d
    }

    @Test
    fun `parked with GPS jitter settles to STATIONARY, LOW duty and the 180s keepalive`() {
        val d = settle()
        assertEquals(MotionState.STATIONARY, d.state)
        assertEquals(GpsDuty.LOW, d.gpsDuty)
        assertFalse(d.transitionedToMoving)
        val interval = rate.getInterval(ReportingPath.RELIABLE, 2.4, d.metersFromAnchor)
        assertEquals(180L, interval.seconds)
    }

    @Test
    fun `first fix keeps HIGH duty until the settle window passes`() {
        val d = classifier.observe(fix(0.0, 0.0))
        assertEquals(MotionState.UNKNOWN, d.state)
        assertEquals(GpsDuty.HIGH, d.gpsDuty)
        advance(10_000)
        assertEquals(GpsDuty.HIGH, classifier.observe(fix(1.0, 0.5)).gpsDuty)
        advance(25_000)
        assertEquals(MotionState.STATIONARY, classifier.observe(fix(1.0, 0.5)).state)
    }

    @Test
    fun `stationary to moving fires exactly once and snaps GPS to HIGH even without speed`() {
        settle()
        advance(15_000)
        // Balanced-power fix: relocated 40 m but the provider reported no speed.
        val moved = classifier.observe(fix(40.0, 0.0))
        assertTrue(moved.transitionedToMoving)
        assertEquals(MotionState.WALKING, moved.state)
        assertEquals(GpsDuty.HIGH, moved.gpsDuty)
        assertNull(moved.metersFromAnchor)

        advance(2_000)
        val next = classifier.observe(fix(42.0, 3.0))
        assertFalse(next.transitionedToMoving)
    }

    @Test
    fun `stationary to driving via speed alone also transitions`() {
        settle()
        advance(5_000)
        val d = classifier.observe(fix(3.0, 20.0))
        assertTrue(d.transitionedToMoving)
        assertEquals(MotionState.DRIVING, d.state)
        assertEquals(GpsDuty.HIGH, d.gpsDuty)
    }

    @Test
    fun `sustained walking stays WALKING and reports in the 30-45s band not every 20m`() {
        classifier.observe(fix(0.0, 3.0))
        var north = 0.0
        var d: MotionDecision? = null
        // 3 mph ≈ 1.34 m/s; sample every 5 s for two minutes.
        repeat(24) {
            advance(5_000)
            north += 1.34 * 5
            d = classifier.observe(fix(north, 3.0))
        }
        val decision = d!!
        assertEquals(MotionState.WALKING, decision.state)
        assertNull(decision.metersFromAnchor)
        val interval = rate.getInterval(ReportingPath.RELIABLE, 3.0, decision.metersFromAnchor)
        assertTrue("walk interval was ${interval.seconds}", interval.seconds in 30L..50L)
        assertEquals(GpsDuty.BALANCED, decision.gpsDuty)
    }

    @Test
    fun `driving reports at 5s with HIGH duty`() {
        classifier.observe(fix(0.0, 45.0))
        advance(2_000)
        val d = classifier.observe(fix(40.0, 45.0))
        assertEquals(MotionState.DRIVING, d.state)
        assertEquals(GpsDuty.HIGH, d.gpsDuty)
        assertEquals(5L, rate.getInterval(ReportingPath.RELIABLE, 45.0, d.metersFromAnchor).seconds)
    }

    @Test
    fun `a stoplight does not drop GNSS but a long stop after driving does`() {
        classifier.observe(fix(0.0, 30.0))
        advance(2_000)
        classifier.observe(fix(30.0, 30.0))
        // Stopped at a light: 45 s of zero speed at the same spot.
        var d: MotionDecision? = null
        repeat(22) {
            advance(2_000)
            d = classifier.observe(fix(30.0, 0.0))
        }
        assertEquals(MotionState.DRIVING, d!!.state)
        assertEquals(GpsDuty.HIGH, d!!.gpsDuty)
        // Still parked at 90 s → stationary.
        repeat(25) {
            advance(2_000)
            d = classifier.observe(fix(31.0, 0.0))
        }
        assertEquals(MotionState.STATIONARY, d!!.state)
        assertEquals(GpsDuty.LOW, d!!.gpsDuty)
    }

    @Test
    fun `held or IP fixes never change state and keep stationary cheap`() {
        settle()
        advance(60_000)
        val d = classifier.observe(fix(0.0, 0.0, held = true))
        assertEquals(MotionState.STATIONARY, d.state)
        assertEquals(GpsDuty.LOW, d.gpsDuty)
        assertFalse(d.transitionedToMoving)
        assertEquals(GpsDuty.HIGH, MotionClassifier(clock = { nowMs }).observe(null).gpsDuty)
    }

    @Test
    fun `reset forgets the anchor so a restart re-acquires on HIGH`() {
        settle()
        classifier.reset()
        val d = classifier.observe(fix(0.0, 0.0))
        assertEquals(MotionState.UNKNOWN, d.state)
        assertEquals(GpsDuty.HIGH, d.gpsDuty)
    }
}
