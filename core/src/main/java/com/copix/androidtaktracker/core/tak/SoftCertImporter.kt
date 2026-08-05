package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import com.copix.androidtaktracker.core.util.RedactedLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipInputStream

data class SoftCertImportResult(
    val success: Boolean,
    val message: String,
    val profileId: String? = null,
)

/**
 * Imports ATAK SoftCert / preference ZIP (config.pref + client .p12 + optional trust .p12).
 * Persists PKCS12 under [ConfigStore.certsDirectory] and passwords in the secret store so
 * reconnects never re-download SoftCert (same model as WinTAKTracker / ATAK).
 */
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
                    val name = entry.name.substringAfterLast('\\').substringAfterLast('/')
                    val data = zis.readBytes()
                    when {
                        name.endsWith(".pref", true) || name.equals("config.pref", true) ->
                            prefText = data.toString(Charsets.UTF_8)
                        name.endsWith(".p12", true) || name.endsWith(".pfx", true) -> {
                            if (name.contains("trust", true) || name.contains("ca", true)) {
                                trustBytes = data
                            } else if (p12Bytes == null) {
                                p12Bytes = data
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // Some SoftCert packs only ship one p12 named with "trust"/"ca" — use it as client.
            if (p12Bytes == null && trustBytes != null) {
                p12Bytes = trustBytes
            }

            if (p12Bytes == null || p12Bytes!!.isEmpty()) {
                return SoftCertImportResult(false, "SoftCert ZIP did not contain a client certificate (.p12/.pfx).")
            }

            val prefs = parsePref(prefText ?: "")
            val connect = prefs.entries.firstOrNull { (k, _) ->
                k.contains("connectString", true) || k.contains("TAKConnection", true)
            }?.value
            val connectMatch = connect?.let {
                Regex("""([^:]+):(\d+):(\w+)""").find(it)
            }
            val host = connectMatch?.groupValues?.get(1)
                ?: prefs["host"]
                ?: prefs.entries.firstOrNull { it.key.equals("address", true) }?.value
                ?: Regex("""([A-Za-z0-9.\-]+):(\d+):(ssl|tcp)""", RegexOption.IGNORE_CASE)
                    .find(prefText ?: "")?.groupValues?.get(1)
                ?: return SoftCertImportResult(false, "Could not find server host in SoftCert preferences.")
            val port = connectMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: prefs["port"]?.toIntOrNull()
                ?: 8089
            val protocol = when {
                connectMatch?.groupValues?.get(3)?.equals("tcp", true) == true -> "tcp"
                prefs["connectString0"]?.contains("tcp", true) == true -> "tcp"
                else -> "ssl"
            }

            val prefPassword = prefs.entries.firstOrNull { (k, _) ->
                k.contains("password", true) || k.contains("clientPassword", true)
            }?.value
            val password = prefPassword?.takeIf { it.isNotBlank() } ?: MartiCertMaterial.DEFAULT_P12_PASSWORD

            // Prove the PKCS12 loads with the password we will persist (atakatak default if omitted).
            val workingPassword = resolveWorkingPassword(p12Bytes!!, password)
                ?: return SoftCertImportResult(
                    false,
                    "Client certificate password rejected (tried preference password and atakatak).",
                )

            store.ensureDirectories()
            val id = UUID.randomUUID().toString().replace("-", "")
            val certFile = "$id-client.p12"
            File(store.certsDirectory, certFile).writeBytes(p12Bytes!!)

            var trustFile: String? = null
            var trustPwdBlob: String? = null
            if (trustBytes != null && trustBytes !== p12Bytes) {
                trustFile = "$id-trust.p12"
                File(store.certsDirectory, trustFile).writeBytes(trustBytes!!)
                trustPwdBlob = "$id-trustpwd"
                // SoftCert trust packs almost always use atakatak; fall back to client password.
                val trustPwd = resolveWorkingPassword(trustBytes!!, workingPassword)
                    ?: MartiCertMaterial.DEFAULT_P12_PASSWORD
                store.writeSecret(trustPwdBlob, trustPwd)
            }

            val certPwdBlob = "$id-certpwd"
            store.writeSecret(certPwdBlob, workingPassword)

            config.servers.add(
                ServerProfile(
                    id = id,
                    displayName = prefs["description"] ?: "SoftCert $host",
                    host = host,
                    port = port,
                    protocol = protocol,
                    username = prefs["username"],
                    clientCertFileName = certFile,
                    trustStoreFileName = trustFile,
                    certPasswordBlobName = certPwdBlob,
                    trustPasswordBlobName = trustPwdBlob,
                ),
            )

            RemoteIdentityApply.apply(
                config,
                prefs["locationCallsign"] ?: prefs["callsign"],
                prefs["locationTeam"] ?: prefs["team"],
                prefs["locationRole"] ?: prefs["role"],
            )

            log.info("Enroll", "SoftCert ZIP imported; client PKCS12 persisted for reconnect.")
            SoftCertImportResult(true, "Imported SoftCert for $host", id)
        } catch (ex: Exception) {
            log.warn("Enroll", "SoftCert import failed: ${ex.javaClass.simpleName}")
            SoftCertImportResult(false, "SoftCert import failed: ${ex.message}")
        }
    }

    private fun resolveWorkingPassword(p12: ByteArray, preferred: String): String? {
        for (pwd in listOf(preferred, MartiCertMaterial.DEFAULT_P12_PASSWORD, "").distinct()) {
            try {
                MartiCertMaterial.verifyClientPkcs12(p12, pwd)
                return pwd
            } catch (_: Exception) {
                // try next
            }
            // Trust-only stores may have certificates without a private key.
            try {
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(ByteArrayInputStream(p12), pwd.toCharArray())
                if (ks.aliases().hasMoreElements()) return pwd
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun parsePref(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val entryRe = Regex("""(?i)<entry\s+key="([^"]+)"[^>]*>([^<]*)</entry>""")
        for (m in entryRe.findAll(text)) {
            map[m.groupValues[1]] = m.groupValues[2].trim()
        }
        val attrRe = Regex("""(?i)<entry\s+key="([^"]+)"\s+value="([^"]*)"\s*/?>""")
        for (m in attrRe.findAll(text)) {
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
