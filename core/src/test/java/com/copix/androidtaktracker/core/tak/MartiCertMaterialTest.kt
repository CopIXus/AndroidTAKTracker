package com.copix.androidtaktracker.core.tak

import com.copix.androidtaktracker.core.config.ConfigStore
import com.copix.androidtaktracker.core.util.NoopRedactedLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MartiCertMaterialTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): ConfigStore = ConfigStore(tmp.newFolder("store"))

    @Test
    fun persist_repairsPrivateKey_andLoadsWithAtakatak() {
        val store = store()
        val kp = MartiCertMaterial.generateRsaKeyPair(2048)
        val pem = MartiCertMaterial.selfSignedCertPem(kp, "UNIT")
        val result = MartiCertMaterial.persist(pem, emptyList(), kp.private, "profile1", store, NoopRedactedLogger)

        assertTrue(result.success)
        assertEquals("profile1-client.p12", result.clientCertFileName)
        assertEquals("profile1-certpwd", result.certPasswordBlobName)
        assertEquals(MartiCertMaterial.DEFAULT_P12_PASSWORD, store.readSecret(result.certPasswordBlobName!!))

        val bytes = java.io.File(store.certsDirectory, result.clientCertFileName!!).readBytes()
        MartiCertMaterial.verifyClientPkcs12(bytes, MartiCertMaterial.DEFAULT_P12_PASSWORD)
    }

    @Test
    fun persist_writesTrustStore_forCaChain() {
        val store = store()
        val leafKp = MartiCertMaterial.generateRsaKeyPair(2048)
        val caKp = MartiCertMaterial.generateRsaKeyPair(2048)
        val leafPem = MartiCertMaterial.selfSignedCertPem(leafKp, "LEAF")
        val caPem = MartiCertMaterial.selfSignedCertPem(caKp, "CA")

        val result = MartiCertMaterial.persist(
            leafPem,
            listOf(caPem),
            leafKp.private,
            "p2",
            store,
            NoopRedactedLogger,
        )
        assertTrue(result.success)
        assertNotNull(result.trustStoreFileName)
        assertEquals(MartiCertMaterial.DEFAULT_P12_PASSWORD, store.readSecret(result.trustPasswordBlobName!!))
        val trust = java.io.File(store.certsDirectory, result.trustStoreFileName!!).readBytes()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(trust), MartiCertMaterial.DEFAULT_P12_PASSWORD.toCharArray())
        assertTrue(ks.aliases().hasMoreElements())
    }

    @Test
    fun tryBuildFromV2Json_buildsUsableClientPkcs12() {
        val store = store()
        val kp = MartiCertMaterial.generateRsaKeyPair(2048)
        val pem = MartiCertMaterial.selfSignedCertPem(kp, "V2")
        // Strip PEM headers → base64 body like some Marti v2 payloads
        val b64 = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
        val caPem = MartiCertMaterial.selfSignedCertPem(MartiCertMaterial.generateRsaKeyPair(2048), "CA0")
        val caB64 = caPem.lines().filter { !it.startsWith("-----") }.joinToString("")
        val json = """{"signedCert":"$b64","ca0":"$caB64"}"""

        val result = MartiCertMaterial.tryBuildFromV2Json(
            json.toByteArray(),
            kp,
            "v2id",
            store,
            NoopRedactedLogger,
        )
        assertTrue(result.error ?: "ok", result.success)
        MartiCertMaterial.verifyClientPkcs12(
            java.io.File(store.certsDirectory, result.clientCertFileName!!).readBytes(),
            MartiCertMaterial.DEFAULT_P12_PASSWORD,
        )
    }

    @Test
    fun softCertImport_persistsClientAndDefaultPassword() {
        val store = store()
        val config = com.copix.androidtaktracker.core.config.AppConfig()
        val kp = MartiCertMaterial.generateRsaKeyPair(2048)
        val pem = MartiCertMaterial.selfSignedCertPem(kp, "SOFT")
        val built = MartiCertMaterial.persist(pem, emptyList(), kp.private, "tmp", store, NoopRedactedLogger)
        val clientBytes = java.io.File(store.certsDirectory, built.clientCertFileName!!).readBytes()

        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("config.pref"))
            zos.write(
                """
                <preferences>
                  <entry key="connectString0">tak.example.com:8089:ssl</entry>
                  <entry key="description">Unit SoftCert</entry>
                </preferences>
                """.trimIndent().toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("client.p12"))
            zos.write(clientBytes)
            zos.closeEntry()
        }

        // Wipe temp persist artifacts so SoftCert path is the only writer under a clean profile.
        store.certsDirectory.listFiles()?.forEach { it.delete() }

        val importer = SoftCertImporter(store, NoopRedactedLogger)
        val result = importer.importZip(zip.toByteArray(), config)
        assertTrue(result.message, result.success)
        val profile = config.servers.single()
        assertNotNull(profile.clientCertFileName)
        assertNotNull(profile.certPasswordBlobName)
        assertEquals(MartiCertMaterial.DEFAULT_P12_PASSWORD, store.readSecret(profile.certPasswordBlobName!!))
        MartiCertMaterial.verifyClientPkcs12(
            java.io.File(store.certsDirectory, profile.clientCertFileName!!).readBytes(),
            store.readSecret(profile.certPasswordBlobName!!)!!,
        )
    }

    @Test
    fun softCertImport_failsWithoutClientP12() {
        val store = store()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { zos ->
            zos.putNextEntry(ZipEntry("config.pref"))
            zos.write("""<entry key="connectString0">tak.example.com:8089:ssl</entry>""".toByteArray())
            zos.closeEntry()
        }
        val result = SoftCertImporter(store, NoopRedactedLogger).importZip(
            zip.toByteArray(),
            com.copix.androidtaktracker.core.config.AppConfig(),
        )
        assertTrue(!result.success)
        assertTrue(result.message.contains("client certificate", ignoreCase = true))
    }

    @Test
    fun ensurePem_wrapsBareBase64() {
        val raw = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val pem = MartiCertMaterial.ensurePem(raw, "CERTIFICATE")
        assertTrue(pem.contains("BEGIN CERTIFICATE"))
        assertTrue(pem.contains("END CERTIFICATE"))
    }
}
