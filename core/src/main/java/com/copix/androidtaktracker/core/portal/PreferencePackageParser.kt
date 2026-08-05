package com.copix.androidtaktracker.core.portal

import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Extracts callsign / team / role from ATAK SoftCert-style `*.pref` XML or Portal Pref
 * mission packages (`Pref-*.zip` with `MANIFEST/manifest.xml` + `certs/config.pref`).
 */
object PreferencePackageParser {

    data class IdentityPrefs(
        val callsign: String? = null,
        val team: String? = null,
        val role: String? = null,
        /** From MANIFEST `onReceiveImport`; null when no MANIFEST. */
        val onReceiveImport: Boolean? = null,
    ) {
        val hasAny: Boolean get() = !callsign.isNullOrBlank() || !team.isNullOrBlank() || !role.isNullOrBlank()
    }

    private val ENTRY_REGEX = Regex(
        """<(?:entry)\b([^>]*?)(?:/>|>([^<]*)</(?:entry)>)""",
        RegexOption.IGNORE_CASE,
    )

    fun parsePrefXml(xml: String): IdentityPrefs {
        var callsign: String? = null
        var team: String? = null
        var role: String? = null

        try {
            // Later entries win — Portal duplicates keys under app_civ and app preference blocks.
            for (m in ENTRY_REGEX.findAll(xml)) {
                val attrs = m.groupValues[1]
                val key = attr(attrs, "key") ?: attr(attrs, "name") ?: continue
                val value = (attr(attrs, "value") ?: m.groupValues.getOrNull(2))?.trim().orEmpty()
                if (key.isEmpty() || value.isEmpty()) continue
                val parsed = applyKey(key, value)
                if (parsed.callsign != null) callsign = parsed.callsign
                if (parsed.team != null) team = parsed.team
                if (parsed.role != null) role = parsed.role
            }
        } catch (_: Exception) {
            // fall through
        }

        callsign = callsign ?: matchPref(xml, "locationCallsign") ?: matchPref(xml, "callsign")
        team = team ?: matchPref(xml, "locationTeam") ?: matchPref(xml, "teamColor") ?: matchPref(xml, "team")
        role = role ?: matchPref(xml, "atakRoleType") ?: matchPref(xml, "locationRole") ?: matchPref(xml, "role")

        return IdentityPrefs(callsign, RemoteIdentityApply.normalizeTeam(team), role)
    }

    fun parseZipBytes(bytes: ByteArray): IdentityPrefs {
        var callsign: String? = null
        var team: String? = null
        var role: String? = null
        var onReceiveImport: Boolean? = null

        try {
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    entries[name] = zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val manifestBytes = entries.entries.firstOrNull { (k, _) ->
                k.equals("MANIFEST/manifest.xml", true) || k.equals("manifest.xml", true)
            }?.value
            if (manifestBytes != null) {
                onReceiveImport = parseOnReceiveImport(manifestBytes.toString(Charsets.UTF_8))
            }

            val prefPaths = entries.keys
                .filter { it.endsWith(".pref", true) || it.endsWith("config.pref", true) }
                .sortedWith(
                    compareBy<String> { if (it.endsWith("certs/config.pref", true)) 0 else 1 }
                        .thenBy { it.lowercase() },
                )

            for (path in prefPaths) {
                val text = entries[path]?.toString(Charsets.UTF_8).orEmpty()
                if (text.isBlank()) continue
                val prefs = parsePrefXml(text)
                if (!prefs.callsign.isNullOrBlank()) callsign = prefs.callsign
                if (!prefs.team.isNullOrBlank()) team = prefs.team
                if (!prefs.role.isNullOrBlank()) role = prefs.role
            }
        } catch (_: Exception) {
            return try {
                val text = bytes.toString(Charsets.UTF_8)
                if (text.contains('<')) parsePrefXml(text).copy(onReceiveImport = onReceiveImport)
                else IdentityPrefs(onReceiveImport = onReceiveImport)
            } catch (_: Exception) {
                IdentityPrefs(onReceiveImport = onReceiveImport)
            }
        }

        return IdentityPrefs(
            callsign,
            RemoteIdentityApply.normalizeTeam(team),
            role,
            onReceiveImport,
        )
    }

    fun isPreferencePackage(bytes: ByteArray, filenameHint: String? = null): Boolean {
        if (!filenameHint.isNullOrBlank() && filenameHint.startsWith("Pref-", ignoreCase = true)) return true
        return try {
            val paths = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    paths += entry.name.replace('\\', '/')
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val hasConfig = paths.any {
                it.endsWith("certs/config.pref", true) ||
                    it.endsWith("/config.pref", true) ||
                    it.equals("config.pref", true)
            }
            val hasManifest = paths.any {
                it.endsWith("MANIFEST/manifest.xml", true) || it.endsWith("manifest.xml", true)
            }
            if (hasConfig && hasManifest) return true
            hasConfig && parseZipBytes(bytes).hasAny
        } catch (_: Exception) {
            false
        }
    }

    /** Auto-import when MANIFEST says so, or when no MANIFEST (device-profile / SoftCert). */
    fun shouldAutoImport(prefs: IdentityPrefs): Boolean = prefs.onReceiveImport != false

    private fun parseOnReceiveImport(manifestXml: String): Boolean? {
        val m = Regex(
            """name\s*=\s*["']onReceiveImport["'][^>]*value\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(manifestXml)
        return when (m?.groupValues?.get(1)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun applyKey(key: String, value: String): IdentityPrefs {
        if (value.contains("://")) return IdentityPrefs()
        if (key.contains("connect", ignoreCase = true)) return IdentityPrefs()

        if (key.contains("callsign", ignoreCase = true)) return IdentityPrefs(callsign = value)

        if (key.equals("locationTeam", true) || key.equals("team", true) || key.equals("teamColor", true) ||
            key.equals("locationTeamColor", true) ||
            (key.contains("team", true) && !key.contains("steam", true))
        ) {
            return IdentityPrefs(team = value)
        }

        if (key.equals("atakRoleType", true) ||
            (key.contains("role", ignoreCase = true) && !key.contains("enroll", ignoreCase = true))
        ) {
            return IdentityPrefs(role = value)
        }

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
