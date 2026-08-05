package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.config.ServerProfile
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class EnrollmentApplyResult(
    val success: Boolean,
    val message: String,
    val profileId: String? = null,
)

/**
 * Portal / SoftCert / Marti enrollment. Certificate material is written under
 * [ConfigStore.certsDirectory] and passwords under [ConfigStore] secrets so mTLS
 * reconnects work after process death (ATAK SoftCert / Marti model).
 */
class EnrollmentService(
    private val store: ConfigStore,
    private val log: RedactedLogger,
) {
    private val softCert = SoftCertImporter(store, log)

    suspend fun applyAsync(input: String, config: AppConfig): EnrollmentApplyResult = withContext(Dispatchers.IO) {
        val parsed = EnrollmentUriParser.parse(input)
        if (!parsed.success) {
            return@withContext EnrollmentApplyResult(false, parsed.error ?: "Parse failed.")
        }
        when (parsed.kind) {
            EnrollmentKind.TAK_PREFERENCE -> {
                val r = RemoteIdentityApply.apply(config, parsed.callsign, parsed.team, parsed.role)
                store.save(config)
                EnrollmentApplyResult(true, r.message)
            }
            EnrollmentKind.TAK_IMPORT_URL -> {
                val url = parsed.importUrl ?: return@withContext EnrollmentApplyResult(false, "Missing import URL.")
                val softAccept = config.diagnostics.allowInsecureTlsSoftAccept
                val bytes = download(url, softAccept) ?: return@withContext EnrollmentApplyResult(false, "Download failed.")
                val r = softCert.importZip(bytes, config)
                if (r.success) store.save(config)
                EnrollmentApplyResult(r.success, r.message, r.profileId)
            }
            EnrollmentKind.ITAK_CSV -> {
                val id = UUID.randomUUID().toString().replace("-", "")
                config.servers.add(
                    ServerProfile(
                        id = id,
                        displayName = parsed.displayName ?: parsed.host ?: "Server",
                        host = parsed.host ?: "",
                        port = parsed.port ?: 8089,
                        protocol = parsed.protocol,
                    ),
                )
                store.save(config)
                EnrollmentApplyResult(true, "Added server ${parsed.host}", id)
            }
            EnrollmentKind.OPEN_TAK_TRACKER_ENROLL, EnrollmentKind.TAK_ENROLL -> {
                enrollWithToken(parsed, config)
            }
            EnrollmentKind.UNKNOWN -> EnrollmentApplyResult(false, "Unknown enrollment kind.")
        }
    }

    fun importSoftCertZip(bytes: ByteArray, config: AppConfig): EnrollmentApplyResult {
        val r = softCert.importZip(bytes, config)
        if (r.success) store.save(config)
        return EnrollmentApplyResult(r.success, r.message, r.profileId)
    }

    /**
     * Manual enrollment: typed host / username / password (ATAK Quick Connect style). Runs the
     * same Marti CSR path as URL/QR enrollment without ever building a credentialed URL string
     * that could leak into logs.
     */
    suspend fun enrollManual(
        host: String,
        username: String,
        password: String,
        config: AppConfig,
        streamPort: Int = 8089,
        enrollPort: Int = 8446,
    ): EnrollmentApplyResult = withContext(Dispatchers.IO) {
        val parsed = EnrollmentParseResult(
            kind = EnrollmentKind.TAK_ENROLL,
            host = host.trim(),
            username = username.trim(),
            token = password,
            port = if (streamPort > 0) streamPort else 8089,
            enrollmentPort = if (enrollPort > 0) enrollPort else 8446,
            protocol = "ssl",
        )
        enrollWithToken(parsed, config)
    }

    private fun enrollWithToken(parsed: EnrollmentParseResult, config: AppConfig): EnrollmentApplyResult {
        val host = parsed.host ?: return EnrollmentApplyResult(false, "Missing host.")
        val user = parsed.username ?: return EnrollmentApplyResult(false, "Missing username.")
        val token = parsed.token ?: return EnrollmentApplyResult(false, "Missing token.")
        val enrollPort = if (parsed.enrollmentPort > 0) parsed.enrollmentPort else 8446
        val softAccept = config.diagnostics.allowInsecureTlsSoftAccept
        val profileId = UUID.randomUUID().toString().replace("-", "")
        val tokenBlob = "$profileId-token"

        store.ensureDirectories()
        store.writeSecret(tokenBlob, token)

        return try {
            // Must run before CSR: Android's stub "BC" provider causes OperatorCreationException.
            MartiCertMaterial.ensureBc()
            val client = httpClient(softAccept)
            val cred = Credentials.basic(user, token)
            val keyPair = MartiCertMaterial.generateRsaKeyPair(4096)

            val tlsConfig = fetchTlsConfig(client, cred, host, enrollPort).toMutableMap()
            tlsConfig["CN"] = user
            val subject = MartiCertMaterial.buildSubjectDn(tlsConfig, user)
            val csrPem = MartiCertMaterial.createCsrPem(subject, keyPair)
            val uid = sanitizeClientUid(config.deviceUid ?: "ANDROIDTAKTRACKER")

            val v2Url = "https://$host:$enrollPort/Marti/api/tls/signClient/v2?clientUid=$uid"
            val v1Url = "https://$host:$enrollPort/Marti/api/tls/signClient?clientUid=$uid"

            var lastError: String? = null
            val v2 = postCsr(client, cred, v2Url, csrPem)
            when {
                v2.code == 401 || v2.code == 403 -> {
                    cleanupPartial(profileId, tokenBlob, null, null, null, null)
                    return EnrollmentApplyResult(false, describeAuthFailure(v2.code, v2.text))
                }
                v2.ok -> {
                    val built = MartiCertMaterial.tryBuildFromV2Json(v2.bytes, keyPair, profileId, store, log)
                    if (built.success) {
                        return finishProfile(parsed, config, host, user, profileId, tokenBlob, built)
                    }
                    lastError = built.error
                    log.info("Enroll", "signClient/v2 response not usable; trying v1.")
                }
                else -> lastError = describeHttpFailure(v2.code, v2.text, enrollPort)
            }

            val v1 = postCsr(client, cred, v1Url, csrPem)
            when {
                v1.code == 401 || v1.code == 403 -> {
                    cleanupPartial(profileId, tokenBlob, null, null, null, null)
                    return EnrollmentApplyResult(false, describeAuthFailure(v1.code, v1.text))
                }
                v1.ok -> {
                    val built = MartiCertMaterial.tryBuildFromV1Body(v1.bytes, keyPair, profileId, store, log)
                    if (built.success) {
                        return finishProfile(parsed, config, host, user, profileId, tokenBlob, built)
                    }
                    lastError = built.error
                }
                else -> lastError = describeHttpFailure(v1.code, v1.text, enrollPort)
            }

            cleanupPartial(profileId, tokenBlob, null, null, null, null)
            EnrollmentApplyResult(
                false,
                lastError ?: "Certificate enrollment failed on both signClient/v2 and v1.",
            )
        } catch (ex: Exception) {
            cleanupPartial(profileId, tokenBlob, null, null, null, null)
            log.warn("Enroll", "Enrollment failed: ${ex.javaClass.simpleName}")
            EnrollmentApplyResult(false, describeEnrollException(ex, enrollPort))
        }
    }

    private fun finishProfile(
        parsed: EnrollmentParseResult,
        config: AppConfig,
        host: String,
        user: String,
        profileId: String,
        tokenBlob: String,
        built: MartiCertMaterial.PersistResult,
    ): EnrollmentApplyResult {
        config.servers.add(
            ServerProfile(
                id = profileId,
                displayName = host,
                host = host,
                port = parsed.port ?: 8089,
                protocol = parsed.protocol.ifBlank { "ssl" },
                username = user,
                secretBlobName = tokenBlob,
                clientCertFileName = built.clientCertFileName,
                trustStoreFileName = built.trustStoreFileName,
                certPasswordBlobName = built.certPasswordBlobName,
                trustPasswordBlobName = built.trustPasswordBlobName,
            ),
        )
        RemoteIdentityApply.apply(config, parsed.callsign, parsed.team, parsed.role)
        store.save(config)
        log.info("Enroll", "Marti CSR enrollment succeeded; client/trust PKCS12 persisted.")
        return EnrollmentApplyResult(
            true,
            "Certificate enrolled. Server profile ready for SSL CoT on port ${parsed.port ?: 8089}.",
            profileId,
        )
    }

    private fun cleanupPartial(
        profileId: String,
        tokenBlob: String?,
        clientFile: String?,
        trustFile: String?,
        certPwd: String?,
        trustPwd: String?,
    ) {
        try {
            tokenBlob?.let { store.deleteSecret(it) }
            certPwd?.let { store.deleteSecret(it) }
            trustPwd?.let { store.deleteSecret(it) }
            fun del(name: String?) {
                if (name.isNullOrBlank()) return
                val f = File(store.certsDirectory, name)
                if (f.exists()) f.delete()
            }
            del(clientFile)
            del(trustFile)
            del("$profileId-client.p12")
            del("$profileId-trust.p12")
            del("$profileId-trust-chain.pem")
        } catch (_: Exception) { /* best-effort */ }
    }

    private data class HttpBody(val ok: Boolean, val code: Int, val bytes: ByteArray, val text: String?)

    private fun postCsr(client: OkHttpClient, auth: String, url: String, csrPem: String): HttpBody {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("User-Agent", "AndroidTAKTracker/0.1")
            .post(csrPem.toRequestBody("application/pkcs10".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val text = if (bytes.isNotEmpty() && bytes.size < 512_000 && MartiCertMaterial.looksLikeText(bytes)) {
                String(bytes, Charsets.UTF_8)
            } else null
            return HttpBody(resp.isSuccessful, resp.code, bytes, text)
        }
    }

    private fun fetchTlsConfig(client: OkHttpClient, auth: String, host: String, enrollPort: Int): Map<String, String> {
        return try {
            val req = Request.Builder()
                .url("https://$host:$enrollPort/Marti/api/tls/config")
                .header("Authorization", auth)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("Enroll", "tls/config HTTP ${resp.code}; using CN only.")
                    return emptyMap()
                }
                val xml = resp.body?.string().orEmpty()
                MartiCertMaterial.parseTlsConfigXml(xml)
            }
        } catch (ex: Exception) {
            log.warn("Enroll", "tls/config failed: ${ex.javaClass.simpleName}; using CN only.")
            emptyMap()
        }
    }

    private fun download(url: String, softAccept: Boolean): ByteArray? {
        return try {
            httpClient(softAccept).newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun httpClient(softAccept: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
        if (softAccept) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            builder.sslSocketFactory(ctx.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
            log.warn("Enroll", "HTTPS soft-accept during enrollment (Diagnostics).")
        }
        return builder.build()
    }

    private fun sanitizeClientUid(uid: String): String {
        val cleaned = uid.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        return cleaned.ifBlank { UUID.randomUUID().toString().replace("-", "") }
    }

    private fun describeAuthFailure(code: Int, body: String?): String {
        val hint = if (code == 401) {
            "Unauthorized — enroll token may be expired or already used (Portal tokens are typically valid ~15 minutes)."
        } else {
            "Forbidden — enrollment credentials rejected."
        }
        if (!body.isNullOrBlank() && body.length < 200 && !looksLikeSecret(body)) {
            return "$hint Server said: ${body.trim()}"
        }
        return hint
    }

    private fun describeHttpFailure(code: Int, body: String?, enrollmentPort: Int): String {
        var msg = "Enrollment HTTP $code on port $enrollmentPort."
        if (code == 404) msg += " Server may not expose Marti certificate enrollment."
        if (!body.isNullOrBlank() && body.length < 200 && !looksLikeSecret(body)) {
            msg += " ${body.trim()}"
        }
        return msg
    }

    private fun describeEnrollException(ex: Exception, enrollmentPort: Int): String = when (ex) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "Enrollment port unreachable (network/DNS/TLS). Confirm host and that $enrollmentPort is open."
        is java.net.SocketTimeoutException ->
            "Enrollment timed out. Confirm $enrollmentPort is reachable and paste a fresh Portal token promptly."
        is javax.net.ssl.SSLHandshakeException ->
            "Enrollment TLS failed. Enable Diagnostics → TLS soft-accept for private lab CAs, then retry."
        else -> {
            val detail = (ex.message ?: ex.cause?.message)?.take(160)?.trim()
            if (detail.isNullOrBlank()) "Enrollment failed (${ex.javaClass.simpleName})."
            else "Enrollment failed (${ex.javaClass.simpleName}): $detail"
        }
    }

    private fun looksLikeSecret(text: String): Boolean =
        text.contains("token=", ignoreCase = true) ||
            text.contains("password", ignoreCase = true) ||
            (text.length > 64 && text.all { it.isLetterOrDigit() || it == '-' || it == '_' })
}
