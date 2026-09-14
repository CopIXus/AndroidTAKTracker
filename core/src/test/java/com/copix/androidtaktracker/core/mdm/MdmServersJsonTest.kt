package com.copix.androidtaktracker.core.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdmServersJsonTest {

    @Test
    fun `array is the managed server set`() {
        val doc = MdmServersJson.parse(
            """[{"host":"tak.example.com","port":"8089","username":"USER","password":"TOKEN","name":"Example"}]""",
        )
        assertTrue(doc.serversAuthoritative)
        assertNull(doc.parseError)
        assertEquals(1, doc.servers.size)
        assertEquals("tak.example.com", doc.servers[0].host)
        assertEquals(8089, doc.servers[0].port)
        assertEquals("TOKEN", doc.servers[0].secret())
        assertEquals("Example", doc.servers[0].name)
    }

    @Test
    fun `object overlays lock identity and servers`() {
        val doc = MdmServersJson.parse(
            """
            {"settingsLock":"LOCK","callsign":"%NUMBER%","team":"Cyan","role":"Team Member",
             "allowInsecureTlsSoftAccept":true,"allowTrackingPause":false,"servers":[
               {"host":"a.example.com","token":"T1"},
               {"host":"b.example.com","password":"T2","enrollPort":9446,"protocol":"tcp"}
             ]}
            """.trimIndent(),
        )
        assertTrue(doc.serversAuthoritative)
        assertEquals("LOCK", doc.settingsLock)
        assertEquals("%NUMBER%", doc.callsign)
        assertEquals("Cyan", doc.team)
        assertEquals("Team Member", doc.role)
        assertEquals(true, doc.allowInsecureTlsSoftAccept)
        assertEquals(false, doc.allowTrackingPause)
        assertEquals(2, doc.servers.size)
        assertEquals("T1", doc.servers[0].secret())
        assertEquals(9446, doc.servers[1].enrollPort)
        assertEquals("tcp", doc.servers[1].protocol)
    }

    @Test
    fun `invalid json is not authoritative so flat serverHost can still enroll`() {
        val doc = MdmServersJson.parse("{not json")
        assertFalse(doc.serversAuthoritative)
        assertTrue(doc.parseError!!.contains("not valid JSON"))
    }

    @Test
    fun `duplicate host port protocol is kept once`() {
        val doc = MdmServersJson.parse(
            """[{"host":"TAK.example.com","port":8089},{"host":"tak.example.com","port":8089,"protocol":"ssl"},{"host":"tak.example.com","port":8088}]""",
        )
        assertEquals(2, doc.servers.size)
        assertEquals(8089, doc.servers[0].port)
        assertEquals(8088, doc.servers[1].port)
    }

    @Test
    fun `blank json is not authoritative`() {
        val doc = MdmServersJson.parse("  ")
        assertFalse(doc.serversAuthoritative)
        assertTrue(doc.servers.isEmpty())
    }
}
