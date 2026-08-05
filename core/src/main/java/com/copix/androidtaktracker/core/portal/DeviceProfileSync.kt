package com.copix.androidtaktracker.core.portal

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import com.copix.androidtaktracker.core.tak.ClientCertificateMaterial
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Supplies TLS material for HTTPS device-profile / enrollment calls (Keystore-backed in `app`). */
interface ServerCertificateProvider {
    fun clientCertificate(profile: ServerProfile): ClientCertificateMaterial?
    fun trustStorePkcs12(profile: ServerProfile): Pair<ByteArray, CharArray>?
}

/**
 * Fetches TAK Server / OpenTAK device profile updates after connect
 * (`GET /Marti/api/device/profile/connection`), matching ATAK's "Apply TAK Server Profile
 * Updates" path used by Portal "Send Configuration". Only callsign/team/role identity prefs are
 * applied (tracking-only client).
 */
class DeviceProfileSync(
    private val credentials: ServerCertificateProvider,
    private val log: RedactedLogger,
) {
    private companion object {
        const val DOWNLOAD_ATTEMPTS = 3
    }

    private val lastAttemptUtc = HashMap<String, Instant>()
    private val gate = Object()

    var onIdentityApplied: ((RemoteIdentityApply.Result) -> Unit)? = null

    /** Best-effort profile pull. Never throws; failures are logged and ignored. */
    suspend fun trySync(profile: ServerProfile, config: AppConfig, saveConfig: (AppConfig) -> Unit) {
        if (!config.applyRemoteIdentityFromPortal) return
        if (profile.host.isBlank()) return
        if (profile.protocol.equals("ssl", ignoreCase = true) && profile.clientCertFileName.isNullOrBlank()) return

        synchronized(gate) {
            val last = lastAttemptUtc[profile.id]
            if (last != null && Duration.between(last, Instant.now()) < Duration.ofMinutes(2)) return
            lastAttemptUtc[profile.id] = Instant.now()
        }

        try {
            val bytes = downloadProfilePackage(profile, config)
            if (bytes == null || bytes.isEmpty()) {
                log.info("Profile", "No device profile package from server (empty or unsupported).")
                return
            }

            applyParsedPrefs(bytes, config, saveConfig, filenameHint = null)
        } catch (ex: Exception) {
            log.warn("Profile", "Device profile sync skipped: ${ex.javaClass.simpleName}")
        }
    }

    /**
     * Handle inbound Marti fileshare CoT for Portal Pref-* packages (missioncreate → contact).
     * Downloads via Enterprise Sync and applies identity when `onReceiveImport` allows.
     */
    suspend fun tryHandleFileShareCot(
        profile: ServerProfile,
        config: AppConfig,
        cotXml: String,
        saveConfig: (AppConfig) -> Unit,
    ) {
        if (!config.applyRemoteIdentityFromPortal) return

        val offer = FileShareCotParser.tryParse(cotXml) ?: return
        val looksPref = offer.looksLikePreferencePackage ||
            (offer.filename?.endsWith(".zip", true) == true &&
                offer.filename.contains("Pref", ignoreCase = true))
        if (!looksPref) return

        try {
            val bytes = downloadSyncContent(profile, config, offer)
            if (bytes == null || bytes.isEmpty()) {
                log.warn("Profile", "Pref package download empty or failed.")
                return
            }
            if (!PreferencePackageParser.isPreferencePackage(bytes, offer.filename)) {
                log.info("Profile", "Downloaded fileshare was not a Pref preference package — ignored.")
                return
            }
            applyParsedPrefs(bytes, config, saveConfig, offer.filename)
        } catch (ex: Exception) {
            log.warn("Profile", "Pref package import skipped: ${ex.javaClass.simpleName}")
        }
    }

    fun tryApplyPreferencePackageBytes(
        bytes: ByteArray,
        config: AppConfig,
        saveConfig: (AppConfig) -> Unit,
        filenameHint: String? = null,
    ): Boolean = applyParsedPrefs(bytes, config, saveConfig, filenameHint)

    private fun applyParsedPrefs(
        bytes: ByteArray,
        config: AppConfig,
        saveConfig: (AppConfig) -> Unit,
        filenameHint: String?,
    ): Boolean {
        val prefs = PreferencePackageParser.parseZipBytes(bytes)
        if (!prefs.hasAny) {
            log.info("Profile", "Preference package had no callsign/team/role prefs.")
            return false
        }
        if (!PreferencePackageParser.shouldAutoImport(prefs)) {
            log.info("Profile", "Preference package onReceiveImport=false — skipped auto-import.")
            return false
        }

        val result = RemoteIdentityApply.apply(config, prefs.callsign, prefs.team, prefs.role)
        if (!result.applied) return false

        saveConfig(config)
        log.info(
            "Profile",
            "Remote identity applied (${result.target}) from ${filenameHint ?: "preference package"}: callsign/team/role updated.",
        )
        onIdentityApplied?.invoke(result)
        return true
    }

    private suspend fun downloadSyncContent(
        profile: ServerProfile,
        config: AppConfig,
        offer: FileShareOffer,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val urls = mutableListOf<String>()
        val sender = offer.senderUrl
        if (!sender.isNullOrBlank()) {
            try {
                val host = java.net.URI(sender).host
                if (host.equals(profile.host, ignoreCase = true)) urls += sender
            } catch (_: Exception) { /* ignore */ }
        }
        if (!offer.sha256.isNullOrBlank()) {
            val hash = URLEncoder.encode(offer.sha256, "UTF-8")
            for (port in intArrayOf(8443, 8446)) {
                urls += "https://${profile.host}:$port/Marti/sync/content?hash=$hash"
                urls += "https://${profile.host}:$port/Marti/api/sync/metadata/$hash/content"
            }
        }

        // Portal deletes the package from Marti shortly after send — a transient failure on the
        // first pass means it's gone for good, so retry the whole URL list a few times.
        val candidates = urls.distinct()
        var lastFailure: String? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            for (url in candidates) {
                try {
                    val client = buildHttpClient(profile, config)
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "AndroidTAKTracker/0.1")
                        .header("Accept", "*/*")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            lastFailure = "HTTP ${resp.code}"
                            return@use
                        }
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) return@withContext bytes
                    }
                } catch (ex: Exception) {
                    lastFailure = ex.javaClass.simpleName
                }
            }
            if (attempt < DOWNLOAD_ATTEMPTS) kotlinx.coroutines.delay(attempt * 2_000L)
        }
        log.warn(
            "Profile",
            "Pref package download failed after $DOWNLOAD_ATTEMPTS attempts (${candidates.size} URL(s), last: ${lastFailure ?: "no response"}).",
        )
        null
    }

    private suspend fun downloadProfilePackage(profile: ServerProfile, config: AppConfig): ByteArray? =
        withContext(Dispatchers.IO) {
            val clientUid = config.deviceUid ?: "android-tracker"
            // ATAK uses HTTPS API ports; try common Marti HTTPS ports with client cert when present.
            for (port in intArrayOf(8443, 8446)) {
                try {
                    val client = buildHttpClient(profile, config)
                    val url =
                        "https://${profile.host}:$port/Marti/api/device/profile/connection?clientUid=" +
                            URLEncoder.encode(clientUid, "UTF-8")
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "AndroidTAKTracker/0.1")
                        .header("Accept", "*/*")
                        .build()

                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) return@withContext bytes
                    }
                } catch (_: Exception) {
                    // try next port
                }
            }
            null
        }

    private fun buildHttpClient(profile: ServerProfile, config: AppConfig): OkHttpClient {
        val softAccept = profile.allowInsecureTlsSoftAccept ?: config.diagnostics.allowInsecureTlsSoftAccept
        val trustManager = buildTrustManager(profile, softAccept)
        val sslContext = SSLContext.getInstance("TLS")

        val cert = credentials.clientCertificate(profile)
        val keyManagers = if (cert != null) {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(cert.pkcs12Bytes.inputStream(), cert.password)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, cert.password)
            kmf.keyManagers
        } else {
            null
        }

        sslContext.init(keyManagers, arrayOf(trustManager), SecureRandom())
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

        return OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, session -> if (softAccept) true else defaultVerifier.verify(hostname, session) }
            .build()
    }

    private fun buildTrustManager(profile: ServerProfile, softAccept: Boolean): X509TrustManager {
        val custom = credentials.trustStorePkcs12(profile)
        val base: X509TrustManager = if (custom != null) {
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(custom.first.inputStream(), custom.second)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        } else {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    base.checkServerTrusted(chain, authType)
                } catch (ex: Exception) {
                    if (!softAccept) {
                        log.warn("Profile", "HTTPS rejected (${ex.javaClass.simpleName}) — soft-accept disabled.")
                        throw ex
                    }
                    log.warn("Profile", "HTTPS soft-accept (${ex.javaClass.simpleName}).")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
        }
    }
}
