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
import java.security.KeyPairGenerator
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
                val bytes = download(url) ?: return@withContext EnrollmentApplyResult(false, "Download failed.")
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

    private fun enrollWithToken(parsed: EnrollmentParseResult, config: AppConfig): EnrollmentApplyResult {
        val host = parsed.host ?: return EnrollmentApplyResult(false, "Missing host.")
        val user = parsed.username ?: return EnrollmentApplyResult(false, "Missing username.")
        val token = parsed.token ?: return EnrollmentApplyResult(false, "Missing token.")
        val enrollPort = parsed.enrollmentPort
        return try {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val cn = config.deviceUid ?: "ANDROIDTAKTRACKER"
            val csrPem = buildMinimalCsrPem(kp.public.encoded, kp.private, cn)

            val client = trustAllClient()
            val cred = Credentials.basic(user, token)
            val url = "https://$host:$enrollPort/Marti/api/tls/signClient?clientUid=$cn"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", cred)
                .post(csrPem.toRequestBody("application/pkcs10".toMediaType()))
                .build()

            val p12Bytes: ByteArray
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.warn("Enroll", "CSR enroll HTTP ${resp.code}; saving profile credentials only.")
                    return saveCredentialProfile(parsed, config, host, user, token)
                }
                p12Bytes = resp.body?.bytes() ?: return EnrollmentApplyResult(false, "Empty enroll response.")
            }

            val id = UUID.randomUUID().toString().replace("-", "")
            val certFile = "client-$id.p12"
            java.io.File(store.certsDirectory, certFile).writeBytes(p12Bytes)
            val passName = "cert-pass-$id"
            store.writeSecret(passName, "")

            config.servers.add(
                ServerProfile(
                    id = id,
                    displayName = host,
                    host = host,
                    port = parsed.port ?: 8089,
                    protocol = parsed.protocol,
                    username = user,
                    clientCertFileName = certFile,
                    certPasswordBlobName = passName,
                ),
            )
            RemoteIdentityApply.apply(config, parsed.callsign, parsed.team, parsed.role)
            store.save(config)
            EnrollmentApplyResult(true, "Enrolled to $host", id)
        } catch (ex: Exception) {
            log.warn("Enroll", "Enrollment failed: ${ex.javaClass.simpleName}")
            saveCredentialProfile(parsed, config, host, user, token)
        }
    }

    private fun saveCredentialProfile(
        parsed: EnrollmentParseResult,
        config: AppConfig,
        host: String,
        user: String,
        token: String,
    ): EnrollmentApplyResult {
        val id = UUID.randomUUID().toString().replace("-", "")
        val secret = "token-$id"
        store.writeSecret(secret, token)
        config.servers.add(
            ServerProfile(
                id = id,
                displayName = host,
                host = host,
                port = parsed.port ?: 8089,
                protocol = parsed.protocol,
                username = user,
                secretBlobName = secret,
            ),
        )
        RemoteIdentityApply.apply(config, parsed.callsign, parsed.team, parsed.role)
        store.save(config)
        return EnrollmentApplyResult(
            true,
            "Saved server $host (certificate enrollment incomplete — import SoftCert if required).",
            id,
        )
    }

    private fun buildMinimalCsrPem(
        publicKey: ByteArray,
        privateKey: java.security.PrivateKey,
        cn: String,
    ): String {
        return try {
            val clazz = Class.forName("sun.security.pkcs10.PKCS10")
            val ctor = clazz.getConstructor(java.security.PublicKey::class.java)
            val pkcs10 = ctor.newInstance(
                java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(publicKey)),
            )
            val signMethod = clazz.getMethod(
                "encodeAndSign",
                Class.forName("sun.security.x509.X500Name"),
                java.security.PrivateKey::class.java,
                String::class.java,
            )
            val x500 = Class.forName("sun.security.x509.X500Name")
                .getConstructor(String::class.java)
                .newInstance("CN=$cn")
            signMethod.invoke(pkcs10, x500, privateKey, "SHA256withRSA")
            val baos = java.io.ByteArrayOutputStream()
            clazz.getMethod("print", java.io.PrintStream::class.java)
                .invoke(pkcs10, java.io.PrintStream(baos))
            baos.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            val b64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKey)
            "-----BEGIN CERTIFICATE REQUEST-----\n$b64\n-----END CERTIFICATE REQUEST-----\n"
        }
    }

    private fun download(url: String): ByteArray? {
        return try {
            trustAllClient().newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        } catch (_: Exception) {
            null
        }
    }

    private fun trustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
