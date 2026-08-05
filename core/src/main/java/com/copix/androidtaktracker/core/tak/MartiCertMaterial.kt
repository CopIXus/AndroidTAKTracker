package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.util.RedactedLogger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemReader
import org.bouncycastle.util.io.pem.PemWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Builds and persists Marti / ATAK-compatible PKCS#12 client + trust stores the same way
 * WinTAKTracker does: re-pair the local CSR private key with the signed cert, password
 * [DEFAULT_P12_PASSWORD], files under [ConfigStore.certsDirectory].
 */
object MartiCertMaterial {
    const val DEFAULT_P12_PASSWORD = "atakatak"

    data class PersistResult(
        val success: Boolean,
        val error: String? = null,
        val clientCertFileName: String? = null,
        val trustStoreFileName: String? = null,
        val certPasswordBlobName: String? = null,
        val trustPasswordBlobName: String? = null,
    )

    fun ensureBc() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generateRsaKeyPair(bits: Int = 4096): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(bits)
        return kpg.generateKeyPair()
    }

    fun createCsrPem(subjectDn: String, keyPair: KeyPair): String {
        ensureBc()
        val subject = X500Name(subjectDn)
        val builder = org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder(subject, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val csr = builder.build(signer)
        val pem = PemObject("CERTIFICATE REQUEST", csr.encoded)
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(pem) }
        return sw.toString()
    }

    fun buildSubjectDn(tlsConfig: Map<String, String>, cnFallback: String): String {
        fun escape(v: String) = v.replace("\\", "\\\\").replace(",", "\\,")
        val parts = mutableListOf<String>()
        fun append(key: String, label: String) {
            val v = tlsConfig[key]?.takeIf { it.isNotBlank() } ?: return
            parts += "$label=${escape(v)}"
        }
        append("CN", "CN")
        append("O", "O")
        append("OU", "OU")
        append("C", "C")
        append("ST", "ST")
        append("L", "L")
        if (parts.isEmpty()) parts += "CN=${escape(cnFallback)}"
        return parts.joinToString(", ")
    }

    fun tryBuildFromV2Json(body: ByteArray, keyPair: KeyPair, profileId: String, store: ConfigStore, log: RedactedLogger): PersistResult {
        return try {
            val root = Json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
            val signedEl = root["signedCert"]?.jsonPrimitive?.contentOrNull
                ?: return PersistResult(false, "signClient/v2 response missing signedCert.")
            val signedPem = ensurePem(signedEl, "CERTIFICATE")
            val caPems = mutableListOf<String>()
            var i = 0
            while (true) {
                val ca = root["ca$i"]?.jsonPrimitive?.contentOrNull ?: break
                if (ca.isNotBlank()) caPems += ensurePem(ca, "CERTIFICATE")
                i++
            }
            persist(signedPem, caPems, keyPair.private, profileId, store, log)
        } catch (ex: Exception) {
            PersistResult(false, "signClient/v2 did not return usable JSON (${ex.javaClass.simpleName}).")
        }
    }

    fun tryBuildFromV1Body(body: ByteArray, keyPair: KeyPair, profileId: String, store: ConfigStore, log: RedactedLogger): PersistResult {
        if (looksLikeText(body)) {
            val text = String(body, Charsets.UTF_8).trim()
            if (text.contains("BEGIN CERTIFICATE", ignoreCase = true) ||
                text.contains("BEGIN PKCS", ignoreCase = true)
            ) {
                return try {
                    persist(ensurePem(text, "CERTIFICATE"), emptyList(), keyPair.private, profileId, store, log)
                } catch (ex: Exception) {
                    PersistResult(false, "Failed to parse PEM enrollment response: ${ex.javaClass.simpleName}.")
                }
            }
        }

        for (pwd in listOf(DEFAULT_P12_PASSWORD, "")) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(ByteArrayInputStream(body), pwd.toCharArray())
                val aliases = ks.aliases().toList()
                if (aliases.isEmpty()) continue

                var leaf: X509Certificate? = null
                val cas = mutableListOf<X509Certificate>()
                for (alias in aliases) {
                    val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
                    if (leaf == null && ks.isKeyEntry(alias)) leaf = cert
                    else if (leaf == null) leaf = cert
                    else cas += cert
                }
                leaf = leaf ?: (ks.getCertificate(aliases.first()) as X509Certificate)
                val signedPem = certToPem(leaf)
                val caPems = cas.map { certToPem(it) }
                return persist(signedPem, caPems, keyPair.private, profileId, store, log)
            } catch (_: Exception) {
                // try next password
            }
        }
        return PersistResult(false, "Could not parse signClient v1 response as PKCS12 or PEM.")
    }

    fun persist(
        signedCertPem: String,
        caPems: List<String>,
        privateKey: PrivateKey,
        profileId: String,
        store: ConfigStore,
        log: RedactedLogger,
    ): PersistResult {
        ensureBc()
        store.ensureDirectories()
        val factory = CertificateFactory.getInstance("X.509")
        val leaf = factory.generateCertificate(signedCertPem.byteInputStream()) as X509Certificate

        // Client PKCS12 holds leaf + private key only. CAs go in the trust store — Android's
        // PKCS12KeyStore rejects setKeyEntry when a caller-supplied chain is not cryptographically
        // linked (and Marti often returns intermediates that SoftCert packs separately).
        val clientFile = "$profileId-client.p12"
        val clientKs = KeyStore.getInstance("PKCS12")
        clientKs.load(null, null)
        clientKs.setKeyEntry(
            "client",
            privateKey,
            DEFAULT_P12_PASSWORD.toCharArray(),
            arrayOf<Certificate>(leaf),
        )
        val clientBytes = ByteArrayOutputStream().use { out ->
            clientKs.store(out, DEFAULT_P12_PASSWORD.toCharArray())
            out.toByteArray()
        }
        // Verify round-trip before advertising success — avoids "enrolled" profiles that cannot load.
        verifyClientPkcs12(clientBytes, DEFAULT_P12_PASSWORD)
        File(store.certsDirectory, clientFile).writeBytes(clientBytes)

        val certPwdBlob = "$profileId-certpwd"
        store.writeSecret(certPwdBlob, DEFAULT_P12_PASSWORD)

        var trustFile: String? = null
        var trustPwdBlob: String? = null
        if (caPems.isNotEmpty()) {
            try {
                trustFile = "$profileId-trust.p12"
                val trustKs = KeyStore.getInstance("PKCS12")
                trustKs.load(null, null)
                caPems.forEachIndexed { idx, pem ->
                    val ca = factory.generateCertificate(pem.byteInputStream()) as X509Certificate
                    trustKs.setCertificateEntry("ca$idx", ca)
                }
                val trustBytes = ByteArrayOutputStream().use { out ->
                    trustKs.store(out, DEFAULT_P12_PASSWORD.toCharArray())
                    out.toByteArray()
                }
                File(store.certsDirectory, trustFile).writeBytes(trustBytes)
                File(store.certsDirectory, "$profileId-trust-chain.pem")
                    .writeText(caPems.joinToString("\n"), Charsets.UTF_8)
                trustPwdBlob = "$profileId-trustpwd"
                store.writeSecret(trustPwdBlob, DEFAULT_P12_PASSWORD)
            } catch (ex: Exception) {
                log.warn("Enroll", "Trust store save failed: ${ex.javaClass.simpleName}")
                trustFile = null
                trustPwdBlob = null
            }
        }

        return PersistResult(
            success = true,
            clientCertFileName = clientFile,
            trustStoreFileName = trustFile,
            certPasswordBlobName = certPwdBlob,
            trustPasswordBlobName = trustPwdBlob,
        )
    }

    /** Self-signed leaf for unit tests (not used in production enroll). */
    fun selfSignedCertPem(keyPair: KeyPair, cn: String = "TEST"): String {
        ensureBc()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 60_000)
        val notAfter = Date(now + 365L * 24 * 60 * 60 * 1000)
        val owner = X500Name("CN=$cn")
        val builder = JcaX509v3CertificateBuilder(
            owner,
            BigInteger(64, SecureRandom()),
            notBefore,
            notAfter,
            owner,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val holder = builder.build(signer)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(holder)
        return certToPem(cert)
    }

    fun verifyClientPkcs12(bytes: ByteArray, password: String) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), password.toCharArray())
        val aliases = ks.aliases().toList()
        require(aliases.isNotEmpty()) { "PKCS12 has no aliases" }
        val keyAlias = aliases.firstOrNull { ks.isKeyEntry(it) }
            ?: error("PKCS12 has no private key entry")
        requireNotNull(ks.getKey(keyAlias, password.toCharArray())) { "Private key missing" }
        requireNotNull(ks.getCertificate(keyAlias)) { "Certificate missing" }
    }

    fun ensurePem(raw: String, label: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("BEGIN ")) return trimmed
        val b64 = trimmed.replace("\r", "").replace("\n", "").replace(" ", "")
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val end = (i + 64).coerceAtMost(b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append("-----END ").append(label).append("-----\n")
        return sb.toString()
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = minOf(bytes.size, 64)
        var texty = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xff
            if (b == 9 || b == 10 || b == 13 || (b in 32 until 127)) texty++
        }
        return texty >= sample * 0.9
    }

    fun parseTlsConfigXml(xml: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        val re = Regex("""(?i)<(?:nameEntry|entry)\s+name="([^"]+)"(?:\s+value="([^"]*)")?[^>]*>([^<]*)</""")
        for (m in re.findAll(xml)) {
            val name = m.groupValues[1]
            val value = m.groupValues[2].ifBlank { m.groupValues[3] }.trim()
            if (name.isNotBlank() && value.isNotBlank()) map[name] = value
        }
        // attribute-only form: <nameEntry name="CN" value="user"/>
        val attrRe = Regex("""(?i)<(?:nameEntry|entry)\s+name="([^"]+)"\s+value="([^"]*)"\s*/?>""")
        for (m in attrRe.findAll(xml)) {
            if (m.groupValues[1].isNotBlank() && m.groupValues[2].isNotBlank()) {
                map[m.groupValues[1]] = m.groupValues[2]
            }
        }
        return map
    }

    private fun certToPem(cert: X509Certificate): String {
        val sw = StringWriter()
        PemWriter(sw).use { it.writeObject(PemObject("CERTIFICATE", cert.encoded)) }
        return sw.toString()
    }

    /** Read first CERTIFICATE PEM block (unused helper kept for tests / debugging). */
    fun readFirstCertPem(pem: String): ByteArray {
        PemReader(StringReader(pem)).use { reader ->
            var obj = reader.readPemObject()
            while (obj != null) {
                if (obj.type.equals("CERTIFICATE", ignoreCase = true)) return obj.content
                obj = reader.readPemObject()
            }
        }
        error("No CERTIFICATE PEM block")
    }
}
