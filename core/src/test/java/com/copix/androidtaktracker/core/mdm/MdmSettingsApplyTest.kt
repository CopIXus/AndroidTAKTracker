package com.copix.androidtaktracker.core.mdm

import com.copix.androidtaktracker.core.config.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdmSettingsApplyTest {

    @Test
    fun `credential prefers token over password`() {
        val keys = mapOf("token" to "TOKEN", "password" to "PASSWORD")
        assertEquals("TOKEN", MdmSettingsApply.credential(keys))
    }

    @Test
    fun `credential falls back to password`() {
        assertEquals("PASSWORD", MdmSettingsApply.credential(mapOf("password" to "PASSWORD")))
        assertNull(MdmSettingsApply.credential(mapOf("username" to "USER")))
        assertNull(MdmSettingsApply.credential(mapOf("password" to "  ")))
    }

    @Test
    fun `resolveCallsign uses Headwind device id for blank sentinel and placeholder`() {
        assertEquals("h0001", MdmSettingsApply.resolveCallsign(null, "h0001"))
        assertEquals("h0001", MdmSettingsApply.resolveCallsign("", "h0001"))
        assertEquals("h0001", MdmSettingsApply.resolveCallsign("mdmDeviceId", "h0001"))
        assertEquals("h0001", MdmSettingsApply.resolveCallsign("MDMDEVICEID", "h0001"))
        assertEquals("h0001", MdmSettingsApply.resolveCallsign("%NUMBER%", "h0001"))
        assertNull(MdmSettingsApply.resolveCallsign("mdmDeviceId", null))
        assertNull(MdmSettingsApply.resolveCallsign("", "  "))
    }

    @Test
    fun `resolveCallsign keeps explicit operator value`() {
        assertEquals("ALPHA-12", MdmSettingsApply.resolveCallsign("ALPHA-12", "h0001"))
    }

    @Test
    fun `normalize aliases password and inserts synthesized callsign`() {
        val out = MdmSettingsApply.normalize(
            mapOf(
                "serverHost" to "tak.example.com",
                "username" to "USER",
                "password" to "SECRET",
            ),
            deviceId = "h0001",
        )
        assertEquals("SECRET", out["token"])
        assertEquals("SECRET", out["password"])
        assertEquals("h0001", out["callsign"])
        assertEquals("tak.example.com", out["serverHost"])
    }

    @Test
    fun `normalize does not invent callsign without a device id`() {
        val out = MdmSettingsApply.normalize(mapOf("serverHost" to "tak.example.com"), deviceId = null)
        assertFalse(out.containsKey("callsign"))
        assertFalse(out.containsKey("token"))
    }

    @Test
    fun `shouldEnroll when new host has credentials`() {
        assertTrue(MdmSettingsApply.shouldEnroll(null, "USER", "SECRET"))
        assertFalse(MdmSettingsApply.shouldEnroll(null, "USER", null))
        assertFalse(MdmSettingsApply.shouldEnroll(null, "", "SECRET"))
    }

    @Test
    fun `shouldEnroll when existing profile has no client cert`() {
        val bare = ServerProfile(host = "tak.example.com", username = "USER")
        assertTrue(MdmSettingsApply.shouldEnroll(bare, "USER", "SECRET"))
        val enrolled = ServerProfile(
            host = "tak.example.com",
            username = "USER",
            clientCertFileName = "abc-client.p12",
        )
        assertFalse(MdmSettingsApply.shouldEnroll(enrolled, "USER", "SECRET"))
    }

    @Test
    fun `parsePort falls back to default`() {
        assertEquals(8089, MdmSettingsApply.parsePort(null, 8089))
        assertEquals(8089, MdmSettingsApply.parsePort("0", 8089))
        assertEquals(8446, MdmSettingsApply.parsePort("8446", 8089))
    }
}
