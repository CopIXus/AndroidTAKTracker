package com.copix.androidtaktracker.core.diagnostics

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.cot.GpsFix
import com.copix.androidtaktracker.core.tak.ServerConnectionStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** Redacted status snapshot for share/export (no tokens, passwords, or enroll URLs). */
object StatusExporter {
    private val json = Json { prettyPrint = true }

    fun export(
        config: AppConfig,
        serverStates: List<ServerConnectionStatus>,
        fix: GpsFix?,
        paused: Boolean,
        deferringToAtak: Boolean,
        appVersion: String,
        lastPliEpochMs: Long,
    ): String {
        val root = buildJsonObject {
            put("exportedUtc", Instant.now().toString())
            put("app", "AndroidTAKTracker")
            put("version", appVersion)
            put("deviceUid", config.deviceUid)
            put("paused", paused)
            put("deferringToAtak", deferringToAtak)
            put(
                "callsign",
                config.userIdentity.callsign.takeIf { it.isNotBlank() } ?: config.deviceIdentity.callsign,
            )
            put("team", config.userIdentity.team.ifBlank { config.deviceIdentity.team })
            put("role", config.userIdentity.role.ifBlank { config.deviceIdentity.role })
            put("reportingStrategy", config.reporting.strategy)
            put("meshSaEnabled", config.meshSa.enabled)
            put("deferToAtak", config.atak.deferToAtak)
            if (lastPliEpochMs > 0) {
                put("lastPliUtc", Instant.ofEpochMilli(lastPliEpochMs).toString())
            }
            putJsonObject("gps") {
                if (fix == null) {
                    put("hasFix", false)
                } else {
                    put("hasFix", true)
                    put("source", fix.source.name)
                    put("lat", fix.latitude)
                    put("lon", fix.longitude)
                    fix.accuracyMeters?.let { put("accuracyM", it) }
                    put("held", fix.isHeld)
                }
            }
            putJsonArray("servers") {
                for (s in serverStates) {
                    addJsonObject {
                        put("id", s.profileId)
                        put("name", s.displayName)
                        put("enabled", s.enabled)
                        put("protocol", s.protocol)
                        put("state", s.state.name)
                        s.lastErrorCode?.let { put("lastError", it) }
                    }
                }
            }
        }
        return json.encodeToString(root)
    }
}
