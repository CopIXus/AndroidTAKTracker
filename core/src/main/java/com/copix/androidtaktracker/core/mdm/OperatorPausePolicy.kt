package com.copix.androidtaktracker.core.mdm

/**
 * Whether the operator may pause or resume tracking from the Status screen.
 * Settings lock blocks the control. An MDM-managed device also blocks it unless
 * `allowTrackingPause` is explicitly true. A remote MDM pause cannot be cleared locally.
 */
object OperatorPausePolicy {
    fun allowed(
        settingsLocked: Boolean,
        settingsUnlocked: Boolean,
        mdmPresent: Boolean,
        allowTrackingPause: Boolean,
        remotePause: Boolean,
    ): Boolean {
        if (settingsLocked && !settingsUnlocked) return false
        if (remotePause) return false
        if (mdmPresent && !allowTrackingPause) return false
        return true
    }
}
