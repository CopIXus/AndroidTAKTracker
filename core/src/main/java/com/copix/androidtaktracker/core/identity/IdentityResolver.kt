package com.copix.androidtaktracker.core.identity

import com.copix.androidtaktracker.core.config.AppConfig

/** Resolved CoT identity for the current session (single-user device: user vs device fallback). */
data class ActiveIdentity(
    val callsign: String,
    val team: String,
    val role: String,
    val cotType: String,
    /** Optional phone for ATAK Call; empty means omit from CoT contact. */
    val phone: String = "",
    /** Device | User */
    val source: String,
)

object IdentityResolver {
    /**
     * Resolve the active CoT identity.
     * Preference: interactive user with a saved callsign, else the device identity
     * (falling back to [deviceName] when no device callsign has been set).
     */
    fun resolve(config: AppConfig, deviceName: String = defaultDeviceName(config)): ActiveIdentity {
        config.ensureIdentityDefaults(deviceName)

        val user = config.userIdentity
        if (user.hasCallsign) {
            val device = config.deviceIdentity
            return ActiveIdentity(
                callsign = user.callsign.trim(),
                team = user.team.ifBlank { device.team },
                role = user.role.ifBlank { device.role },
                cotType = user.cotType.ifBlank { device.cotType },
                phone = user.phone.takeIf { it.isNotBlank() }?.trim() ?: "",
                source = "User",
            )
        }

        val device = config.deviceIdentity
        return ActiveIdentity(
            callsign = device.getEffectiveCallsign(deviceName),
            team = device.team,
            role = device.role,
            cotType = device.cotType,
            phone = device.phone.takeIf { it.isNotBlank() }?.trim() ?: "",
            source = "Device",
        )
    }

    /**
     * True when the interactive user has no callsign yet and has not dismissed the setup prompt.
     * Call on first app start so new installs get prompted once.
     */
    fun userNeedsSetup(config: AppConfig): Boolean {
        val user = config.userIdentity
        if (user.hasCallsign) return false
        return !user.setupPromptDismissed
    }

    private fun defaultDeviceName(config: AppConfig): String =
        config.deviceIdentity.callsign.takeIf { it.isNotBlank() } ?: "ANDROID-TRACKER"
}
