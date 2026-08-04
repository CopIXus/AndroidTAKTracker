package com.copix.androidtaktracker.core.identity

import com.copix.androidtaktracker.core.config.AppConfig

/**
 * Applies Portal / enrollment remote identity (callsign + team color name) to the single
 * interactive user identity. Callsigns for AndroidTAKTracker get an `.att` suffix so they are
 * distinct on the TAK network from ATAK / WinTAKTracker (`.wtt`) clients.
 */
object RemoteIdentityApply {
    const val CALLSIGN_SUFFIX = ".att"

    val KNOWN_TEAMS: List<String> = listOf(
        "Cyan", "Blue", "Green", "Yellow", "Orange", "Red",
        "Purple", "Magenta", "Maroon", "Teal", "White",
    )

    data class Result(
        val applied: Boolean,
        val callsign: String? = null,
        val team: String? = null,
        val role: String? = null,
        val target: String = "",
        val message: String = "",
    )

    /** Append `.att` when missing (idempotent; strips trailing dots first). */
    fun ensureAttSuffix(callsign: String): String {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.endsWith(CALLSIGN_SUFFIX, ignoreCase = true))
            return trimmed.dropLast(CALLSIGN_SUFFIX.length).trimEnd('.') + CALLSIGN_SUFFIX
        return trimmed + CALLSIGN_SUFFIX
    }

    /** Normalize ATAK team color name (title-case known colors; otherwise trim as-is). */
    fun normalizeTeam(team: String?): String? {
        if (team.isNullOrBlank()) return null
        val t = team.trim()
        return KNOWN_TEAMS.firstOrNull { it.equals(t, ignoreCase = true) } ?: t
    }

    /** Write callsign/team/role into the single user identity from a Portal / enroll payload. */
    fun apply(config: AppConfig, callsign: String?, team: String?, role: String?): Result {
        val hasCallsign = !callsign.isNullOrBlank()
        val hasTeam = !team.isNullOrBlank()
        val hasRole = !role.isNullOrBlank()
        if (!hasCallsign && !hasTeam && !hasRole) {
            return Result(applied = false, message = "No callsign, team, or role in remote configuration.")
        }

        val normalizedCallsign = if (hasCallsign) ensureAttSuffix(callsign!!) else null
        val normalizedTeam = normalizeTeam(team)
        val normalizedRole = role?.takeIf { it.isNotBlank() }?.trim()

        val user = config.userIdentity
        val unchanged =
            (normalizedCallsign == null || user.callsign.equals(normalizedCallsign, ignoreCase = true)) &&
                (normalizedTeam == null || user.team.equals(normalizedTeam, ignoreCase = true)) &&
                (normalizedRole == null || user.role.equals(normalizedRole, ignoreCase = true))

        if (unchanged && user.hasCallsign) {
            return Result(
                applied = false,
                callsign = user.callsign,
                team = user.team,
                role = user.role,
                target = "user",
                message = "Remote identity unchanged; skip apply.",
            )
        }

        if (normalizedCallsign != null) user.callsign = normalizedCallsign
        if (normalizedTeam != null) user.team = normalizedTeam
        if (normalizedRole != null) user.role = normalizedRole
        user.setupPromptDismissed = false

        return Result(
            applied = true,
            callsign = user.callsign,
            team = user.team,
            role = user.role,
            target = "user",
            message = "Remote identity applied to user callsign.",
        )
    }
}
