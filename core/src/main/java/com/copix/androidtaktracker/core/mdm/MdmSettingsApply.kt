package com.copix.androidtaktracker.core.mdm

import com.copix.androidtaktracker.core.config.ServerProfile

/**
 * Pure mapping for MDM / Headwind Application Settings → tracker config.
 * Headwind expands `%NUMBER%` on the server; the sentinel [DEVICE_ID_SENTINEL] and a
 * leftover `%NUMBER%` are resolved here from [HeadwindAgentClient.getDeviceId].
 */
object MdmSettingsApply {
    const val DEVICE_ID_SENTINEL = "mdmDeviceId"
    const val NUMBER_PLACEHOLDER = "%NUMBER%"

    val PREFERENCE_KEYS: List<String> = listOf(
        "enrollUrl",
        "serverHost",
        "serverPort",
        "serverProtocol",
        "serverName",
        "username",
        "token",
        "password",
        "enrollPort",
        "callsign",
        "team",
        "role",
        "reportingStrategy",
        "deferToAtak",
        "pause",
        "serversJson",
        "settingsLock",
        "settingsLockClear",
        "allowInsecureTlsSoftAccept",
        "requestBatteryExemption",
        "preventSleepWhileTracking",
        "allowTrackingPause",
    )

    /** Keys that mean the server list is owned by MDM (add, remove, enable, enroll). */
    val SERVER_KEYS: Set<String> = setOf(
        "serversJson",
        "serverHost",
        "serverPort",
        "serverProtocol",
        "serverName",
        "enrollPort",
        "enrollUrl",
        "username",
        "password",
        "token",
    )

    fun serversManaged(keys: Set<String>): Boolean = keys.any { it in SERVER_KEYS }

    /** Prefer `token`, then `password` (Headwind / Quick Connect alias). */
    fun credential(keys: Map<String, String>): String? {
        val token = keys["token"]?.trim()?.takeIf { it.isNotEmpty() }
        val password = keys["password"]?.trim()?.takeIf { it.isNotEmpty() }
        return token ?: password
    }

    /**
     * Blank, `mdmDeviceId`, or an unexpanded `%NUMBER%` → Headwind device ID.
     * Any other non-blank value is the operator-set callsign (`.att` is applied later).
     */
    fun resolveCallsign(raw: String?, deviceId: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        val useDeviceId = trimmed.isEmpty() ||
            trimmed.equals(DEVICE_ID_SENTINEL, ignoreCase = true) ||
            trimmed.equals(NUMBER_PLACEHOLDER, ignoreCase = true)
        return if (useDeviceId) deviceId?.trim()?.takeIf { it.isNotEmpty() } else trimmed
    }

    /**
     * Alias `password` → `token` and resolve callsign. A synthesized callsign is written
     * back so [com.copix.androidtaktracker.mdm.MdmConfigBridge.managedKeys] includes it
     * and Portal cannot overwrite MDM identity.
     */
    fun normalize(source: Map<String, String>, deviceId: String?): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in source) {
            if (v.isNotBlank()) out[k] = v
        }
        val secret = credential(out)
        if (!secret.isNullOrBlank()) out["token"] = secret
        val callsign = resolveCallsign(out["callsign"], deviceId)
        if (!callsign.isNullOrBlank()) out["callsign"] = callsign
        return out
    }

    /**
     * Enroll when credentials exist and there is no profile yet, or the existing
     * profile has no client certificate (first apply or failed prior enroll).
     */
    fun shouldEnroll(existing: ServerProfile?, username: String?, secret: String?): Boolean {
        if (username.isNullOrBlank() || secret.isNullOrBlank()) return false
        if (existing == null) return true
        return existing.clientCertFileName.isNullOrBlank()
    }

    fun parsePort(raw: String?, default: Int): Int =
        raw?.toIntOrNull()?.takeIf { it > 0 } ?: default
}
