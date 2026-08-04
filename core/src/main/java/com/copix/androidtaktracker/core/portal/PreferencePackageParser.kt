package com.copix.androidtaktracker.core.portal

import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts callsign / team / role from ATAK-style `*.pref` XML or data-package ZIPs
 * (Portal / OpenTAK "Send Configuration" / device-profile responses).
 */
object PreferencePackageParser {

    data class IdentityPrefs(
        val callsign: String? = null,
        val team: String? = null,
        val role: String? = null,
    ) {
        val hasAny: Boolean get() = !callsign.isNullOrBlank() || !team.isNullOrBlank() || !role.isNullOrBlank()
    }

    private val ENTRY_REGEX = Regex(
        """<(?:entry|preference)\b([^>]*?)(?:/>|>([^<]*)</(?:entry|preference)>)""",
        RegexOption.IGNORE_CASE,
    )

    fun parsePrefXml(xml: String): IdentityPrefs {
        var callsign: String? = null
        var team: String? = null
        var role: String? = null

        try {
            for (m in ENTRY_REGEX.findAll(xml)) {
                val attrs = m.groupValues[1]
                val key = attr(attrs, "key") ?: attr(attrs, "name") ?: continue
                val value = (attr(attrs, "value") ?: m.groupValues.getOrNull(2))?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) continue

                val parsed = applyKey(key, value)
                callsign = callsign ?: parsed.callsign
                team = team ?: parsed.team
                role = role ?: parsed.role
            }
        } catch (_: Exception) {
            // fall through to loose scan below
        }

        // Loose scan for common ATAK preference keys in non-well-formed blobs.
        callsign = callsign ?: matchPref(xml, "locationCallsign") ?: matchPref(xml, "callsign")
        team = team ?: matchPref(xml, "locationTeam") ?: matchPref(xml, "teamColor") ?: matchPref(xml, "team")
        role = role ?: matchPref(xml, "locationRole") ?: matchPref(xml, "role")

        return IdentityPrefs(callsign, RemoteIdentityApply.normalizeTeam(team), role)
    }

    fun parseZipBytes(bytes: ByteArray): IdentityPrefs {
        var callsign: String? = null
        var team: String? = null
        var role: String? = null

        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (name.endsWith(".pref", true) || name.endsWith(".xml", true) || name.endsWith("config.pref", true)) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        if (text.isNotBlank()) {
                            val prefs = parsePrefXml(text)
                            callsign = callsign ?: prefs.callsign
                            team = team ?: prefs.team
                            role = role ?: prefs.role
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            // Not a ZIP — try as raw pref XML.
            return try {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains('<')) parsePrefXml(text) else IdentityPrefs()
            } catch (_: Exception) {
                IdentityPrefs()
            }
        }

        return IdentityPrefs(callsign, RemoteIdentityApply.normalizeTeam(team), role)
    }

    private fun applyKey(key: String, value: String): IdentityPrefs {
        if (value.contains("://")) return IdentityPrefs()
        if (key.contains("connect", ignoreCase = true)) return IdentityPrefs()

        if (key.contains("callsign", ignoreCase = true)) return IdentityPrefs(callsign = value)

        // ATAK locationTeam / Portal teamColor — team name is the marker color (Cyan, Blue, ...).
        if (key.equals("locationTeam", true) || key.equals("team", true) || key.equals("teamColor", true) ||
            key.equals("locationTeamColor", true) ||
            (key.contains("team", true) && !key.contains("steam", true))
        ) {
            return IdentityPrefs(team = value)
        }

        if (key.contains("role", ignoreCase = true) && !key.contains("enroll", ignoreCase = true))
            return IdentityPrefs(role = value)

        return IdentityPrefs()
    }

    private fun attr(attrs: String, name: String): String? {
        val nameEscaped = Regex.escape(name)
        val dq = Regex("""$nameEscaped\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(attrs)
        if (dq != null) return dq.groupValues[1]
        val sq = Regex("""$nameEscaped\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE).find(attrs)
        return sq?.groupValues?.get(1)
    }

    private fun matchPref(xml: String, key: String): String? {
        val escaped = Regex.escape(key)
        val withValueAttr = Regex(
            """(?:key|name)\s*=\s*["']$escaped["'][^>]*value\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(xml)
        if (withValueAttr != null) return withValueAttr.groupValues[1]

        val withInnerText = Regex(
            """(?:key|name)\s*=\s*["']$escaped["'][^>]*>([^<]+)<""",
            RegexOption.IGNORE_CASE,
        ).find(xml)
        return withInnerText?.groupValues?.get(1)?.trim()
    }
}
