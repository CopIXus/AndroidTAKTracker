package com.copix.androidtaktracker.core.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorPausePolicyTest {
    @Test
    fun `unmanaged device can pause`() {
        assertTrue(OperatorPausePolicy.allowed(false, false, false, false, false))
    }

    @Test
    fun `settings lock blocks pause until unlock`() {
        assertFalse(OperatorPausePolicy.allowed(true, false, false, true, false))
        assertTrue(OperatorPausePolicy.allowed(true, true, false, true, false))
    }

    @Test
    fun `mdm blocks pause unless allowTrackingPause is set`() {
        assertFalse(OperatorPausePolicy.allowed(false, false, true, false, false))
        assertTrue(OperatorPausePolicy.allowed(false, false, true, true, false))
    }

    @Test
    fun `remote mdm pause cannot be cleared locally`() {
        assertFalse(OperatorPausePolicy.allowed(false, true, true, true, true))
    }
}
