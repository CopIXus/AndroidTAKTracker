package com.copix.androidtaktracker.core.portal

import com.copix.androidtaktracker.core.config.AppConfig
import com.copix.androidtaktracker.core.identity.RemoteIdentityApply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PreferencePackageParserTest {

    private val configPref = """
        <?xml version='1.0' encoding='ASCII' standalone='yes'?>
        <preferences>
          <preference version="1" name="com.atakmap.app_civ_preferences">
            <entry key="locationCallsign" class="class java.lang.String">ANDROID-NAMEHERE</entry>
            <entry key="locationTeam" class="class java.lang.String">Dark Green</entry>
            <entry key="atakRoleType" class="class java.lang.String">Team Member</entry>
          </preference>
          <preference version="1" name="com.atakmap.app_preferences">
            <entry key="locationCallsign" class="class java.lang.String">ANDROID-NAMEHERE</entry>
            <entry key="locationTeam" class="class java.lang.String">Dark Green</entry>
            <entry key="atakRoleType" class="class java.lang.String">Team Member</entry>
          </preference>
        </preferences>
    """.trimIndent()

    private val manifest = """
        <MissionPackageManifest version="2">
          <Configuration>
            <Parameter name="uid" value="11111111-2222-3333-4444-555555555555"/>
            <Parameter name="name" value="Pref-ANDROID-NAMEHERE-Dark-Green-Team-Member.zip"/>
            <Parameter name="onReceiveImport" value="true"/>
            <Parameter name="onReceiveDelete" value="false"/>
          </Configuration>
          <Contents>
            <Content ignore="false" zipEntry="certs/config.pref">
              <Parameter name="name" value="Preference Configuration"/>
            </Content>
          </Contents>
        </MissionPackageManifest>
    """.trimIndent()

    private fun prefZip(onReceiveImport: String = "true"): ByteArray {
        val rebuilt = ByteArrayOutputStream()
        ZipOutputStream(rebuilt).use { zos ->
            val man = manifest.replace(
                """<Parameter name="onReceiveImport" value="true"/>""",
                """<Parameter name="onReceiveImport" value="$onReceiveImport"/>""",
            )
            zos.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            zos.write(man.toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("certs/config.pref"))
            zos.write(configPref.toByteArray())
            zos.closeEntry()
        }
        return rebuilt.toByteArray()
    }

    @Test
    fun parsePrefXml_readsAtakRoleTypeAndDarkGreen() {
        val prefs = PreferencePackageParser.parsePrefXml(configPref)
        assertEquals("ANDROID-NAMEHERE", prefs.callsign)
        assertEquals("Dark Green", prefs.team)
        assertEquals("Team Member", prefs.role)
    }

    @Test
    fun parseZip_prefPackage_autoImportTrue() {
        val bytes = prefZip("true")
        assertTrue(PreferencePackageParser.isPreferencePackage(bytes, "Pref-ANDROID-NAMEHERE-Dark-Green-Team-Member.zip"))
        val prefs = PreferencePackageParser.parseZipBytes(bytes)
        assertTrue(prefs.hasAny)
        assertEquals(true, prefs.onReceiveImport)
        assertTrue(PreferencePackageParser.shouldAutoImport(prefs))
        assertEquals("ANDROID-NAMEHERE", prefs.callsign)
        assertEquals("Dark Green", prefs.team)
        assertEquals("Team Member", prefs.role)
    }

    @Test
    fun parseZip_onReceiveImportFalse_skipsAutoImport() {
        val prefs = PreferencePackageParser.parseZipBytes(prefZip("false"))
        assertEquals(false, prefs.onReceiveImport)
        assertFalse(PreferencePackageParser.shouldAutoImport(prefs))
    }

    @Test
    fun apply_appendsAttSuffix() {
        val cfg = AppConfig()
        val result = RemoteIdentityApply.apply(cfg, "ANDROID-NAMEHERE", "Dark Green", "Team Member")
        assertTrue(result.applied)
        assertEquals("ANDROID-NAMEHERE.att", cfg.userIdentity.callsign)
        assertEquals("Dark Green", cfg.userIdentity.team)
        assertEquals("Team Member", cfg.userIdentity.role)
    }

    @Test
    fun fileShareCot_parsesPrefAnnounce() {
        val xml = """
            <event version="2.0" uid="fs-1" type="b-f-t-r" how="h-e" time="2026-01-01T00:00:00.000Z" start="2026-01-01T00:00:00.000Z" stale="2026-01-01T00:01:00.000Z">
              <point lat="0" lon="0" hae="0" ce="9999999" le="9999999"/>
              <detail>
                <fileshare filename="Pref-ANDROID-NAMEHERE-Dark-Green-Team-Member.zip"
                           senderUrl="https://tak.example.com:8443/Marti/sync/content?hash=abc123"
                           sha256="abc123" sizeInBytes="2048"/>
              </detail>
            </event>
        """.trimIndent()
        assertTrue(FileShareCotParser.looksLikeFileShareEvent(xml))
        val offer = FileShareCotParser.tryParse(xml)!!
        assertTrue(offer.looksLikePreferencePackage)
        assertEquals("abc123", offer.sha256)
        assertTrue(offer.senderUrl!!.contains("Marti/sync/content"))
    }
}
