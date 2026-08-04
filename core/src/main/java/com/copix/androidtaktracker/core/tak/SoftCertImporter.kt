package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import com.copix.androidtaktracker.core.util.RedactedLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

data class SoftCertImportResult(
    val success: Boolean,
    val message: String,
    val profileId: String? = null,
)

class SoftCertImporter(
    private val store: ConfigStore,
    private val log: RedactedLogger,
) {
    fun importZip(zipBytes: ByteArray, config: AppConfig): SoftCertImportResult {
        return try {
            var prefText: String? = null
            var p12Bytes: ByteArray? = null
            var trustBytes: ByteArray? = null

            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    val data = zis.readBytes()
                    when {
                        name.endsWith(".pref", true) || name.equals("config.pref", true) ->
                            prefText = data.toString(Charsets.UTF_8)
                        name.endsWith(".p12", true) || name.endsWith(".pfx", true) -> {
                            if (name.contains("trust", true)) trustBytes = data
                            else p12Bytes = data
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            val prefs = parsePref(prefText ?: "")
            val host = prefs["connectString0"]?.substringBefore(':')
                ?: prefs["host"]
                ?: return SoftCertImportResult(false, "SoftCert ZIP missing connect host.")
            val port = prefs["connectString0"]?.split(':')?.getOrNull(1)?.toIntOrNull() ?: 8089
            val protocol = if (prefs["connectString0"]?.contains("tcp", true) == true) "tcp" else "ssl"

            val id = UUID.randomUUID().toString().replace("-", "")
            val certFile = "client-$id.p12"
            if (p12Bytes != null) {
                File(store.certsDirectory, certFile).writeBytes(p12Bytes!!)
            }
            var trustFile: String? = null
            if (trustBytes != null) {
                trustFile = "trust-$id.p12"
                File(store.certsDirectory, trustFile).writeBytes(trustBytes!!)
            }

            val password = prefs["password"] ?: prefs["clientPassword"] ?: ""
            val secretName = "cert-pass-$id"
            if (password.isNotBlank()) store.writeSecret(secretName, password)

            config.servers.add(
                ServerProfile(
                    id = id,
                    displayName = prefs["description"] ?: host,
                    host = host,
                    port = port,
                    protocol = protocol,
                    username = prefs["username"],
                    clientCertFileName = if (p12Bytes != null) certFile else null,
                    trustStoreFileName = trustFile,
                    certPasswordBlobName = if (password.isNotBlank()) secretName else null,
                ),
            )

            RemoteIdentityApply.apply(
                config,
                prefs["locationCallsign"] ?: prefs["callsign"],
                prefs["locationTeam"] ?: prefs["team"],
                prefs["locationRole"] ?: prefs["role"],
            )

            SoftCertImportResult(true, "Imported SoftCert for $host", id)
        } catch (ex: Exception) {
            log.warn("Enroll", "SoftCert import failed: ${ex.javaClass.simpleName}")
            SoftCertImportResult(false, "SoftCert import failed: ${ex.message}")
        }
    }

    private fun parsePref(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val entryRe = Regex("""(?i)<entry\s+key="([^"]+)"[^>]*>([^<]*)</entry>""")
        for (m in entryRe.findAll(text)) {
            map[m.groupValues[1]] = m.groupValues[2].trim()
        }
        for (line in text.lines()) {
            val idx = line.indexOf('=')
            if (idx > 0 && !line.trimStart().startsWith('<')) {
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }
}
