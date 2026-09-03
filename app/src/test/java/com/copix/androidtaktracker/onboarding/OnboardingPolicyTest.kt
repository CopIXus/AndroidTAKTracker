package com.copix.androidtaktracker.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPolicyTest {

    @Test
    fun `skip wizard when already completed`() {
        assertTrue(OnboardingPolicy.shouldSkipWizard(true, mdmPresent = false, trackingReady = false))
    }

    @Test
    fun `skip wizard when MDM granted tracking permissions`() {
        assertTrue(OnboardingPolicy.shouldSkipWizard(false, mdmPresent = true, trackingReady = true))
        assertFalse(OnboardingPolicy.shouldSkipWizard(false, mdmPresent = true, trackingReady = false))
        assertFalse(OnboardingPolicy.shouldSkipWizard(false, mdmPresent = false, trackingReady = true))
    }

    @Test
    fun `skip callsign setup when user is set or MDM owns identity`() {
        assertTrue(OnboardingPolicy.shouldSkipCallsignSetup(userNeedsSetup = false, mdmOwnsIdentity = false))
        assertTrue(OnboardingPolicy.shouldSkipCallsignSetup(userNeedsSetup = true, mdmOwnsIdentity = true))
        assertFalse(OnboardingPolicy.shouldSkipCallsignSetup(userNeedsSetup = true, mdmOwnsIdentity = false))
    }
}
