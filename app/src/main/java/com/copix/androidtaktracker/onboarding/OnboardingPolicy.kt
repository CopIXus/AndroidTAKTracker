package com.copix.androidtaktracker.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** First-run wizard / callsign prompt gates for MDM-managed fleets. */
object OnboardingPolicy {
    fun trackingPermissionsGranted(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT < 29) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }
        val notif = if (Build.VERSION.SDK_INT < 33) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return fine && bg && notif
    }

    /** Skip the permission wizard once done, or when MDM already granted tracking perms. */
    fun shouldSkipWizard(
        onboardingDone: Boolean,
        mdmPresent: Boolean,
        trackingReady: Boolean,
    ): Boolean {
        if (onboardingDone) return true
        return mdmPresent && trackingReady
    }

    /** Skip the first-run callsign screen when the user already has one or MDM owns identity. */
    fun shouldSkipCallsignSetup(
        userNeedsSetup: Boolean,
        mdmOwnsIdentity: Boolean,
    ): Boolean = !userNeedsSetup || mdmOwnsIdentity
}
