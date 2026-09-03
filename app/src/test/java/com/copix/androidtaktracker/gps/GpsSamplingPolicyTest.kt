package com.copix.androidtaktracker.gps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSamplingPolicyTest {

    @Test
    fun `tracking normally samples GPS`() {
        assertTrue(GpsSamplingPolicy.shouldSample(paused = false, remotePauseRequested = false, deferringToAtak = false))
    }

    @Test
    fun `operator pause stops the fused provider`() {
        assertFalse(GpsSamplingPolicy.shouldSample(paused = true, remotePauseRequested = false, deferringToAtak = false))
    }

    @Test
    fun `MDM remote pause stops the fused provider`() {
        assertFalse(GpsSamplingPolicy.shouldSample(paused = false, remotePauseRequested = true, deferringToAtak = false))
    }

    @Test
    fun `ATAK owning presence stops the fused provider`() {
        assertFalse(GpsSamplingPolicy.shouldSample(paused = false, remotePauseRequested = false, deferringToAtak = true))
    }
}
