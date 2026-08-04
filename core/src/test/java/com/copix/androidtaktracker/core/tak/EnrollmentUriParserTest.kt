package com.copix.androidtaktracker.core.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentUriParserTest {

    @Test
    fun `opentaktracker enroll uri parses host, port, protocol, and identity`() {
        val result = EnrollmentUriParser.parse(
            "opentaktracker://enroll?host=tak.example.com&port=8089&protocol=ssl" +
                "&username=USER&token=TOKEN&callsign=CALLSIGN&team=Cyan&role=Team%20Member",
        )

        assertTrue(result.success)
        assertEquals(EnrollmentKind.OPEN_TAK_TRACKER_ENROLL, result.kind)
        assertEquals("tak.example.com", result.host)
        assertEquals(8089, result.port)
        assertEquals("ssl", result.protocol)
        assertEquals("USER", result.username)
        assertEquals("TOKEN", result.token)
        assertEquals("CALLSIGN", result.callsign)
        assertEquals("Cyan", result.team)
        assertEquals("Team Member", result.role)
    }

    @Test
    fun `tak enroll uri with connect-string host field splits port and protocol`() {
        val result = EnrollmentUriParser.parse("tak://com.atakmap.app/enroll?host=tak.example.com:8089:ssl&callsign=FOO")

        assertTrue(result.success)
        assertEquals(EnrollmentKind.TAK_ENROLL, result.kind)
        assertEquals("tak.example.com", result.host)
        assertEquals(8089, result.port)
        assertEquals("ssl", result.protocol)
        assertEquals("FOO", result.callsign)
    }

    @Test
    fun `host field with 8446 is treated as enrollment port not stream port`() {
        val split = EnrollmentUriParser.splitHostField("tak.example.com:8446", null, null, null)
        assertEquals("tak.example.com", split.host)
        assertNull(split.streamPort)
        assertEquals(8446, split.enrollmentPort)
    }

    @Test
    fun `host field with plain port is treated as stream port`() {
        val split = EnrollmentUriParser.splitHostField("tak.example.com:8089", null, null, null)
        assertEquals("tak.example.com", split.host)
        assertEquals(8089, split.streamPort)
        assertNull(split.enrollmentPort)
    }

    @Test
    fun `tak preference uri extracts location prefs`() {
        val result = EnrollmentUriParser.parse("tak://com.atakmap.app/preference?locationCallsign=BRAVO&locationTeam=Blue&locationRole=RTO")

        assertTrue(result.success)
        assertEquals(EnrollmentKind.TAK_PREFERENCE, result.kind)
        assertEquals("BRAVO", result.callsign)
        assertEquals("Blue", result.team)
        assertEquals("RTO", result.role)
    }

    @Test
    fun `tak import uri extracts url`() {
        val result = EnrollmentUriParser.parse("tak://com.atakmap.app/import?url=https%3A%2F%2Fexample.com%2Fpackage.zip")

        assertTrue(result.success)
        assertEquals(EnrollmentKind.TAK_IMPORT_URL, result.kind)
        assertEquals("https://example.com/package.zip", result.importUrl)
    }

    @Test
    fun `itak csv parses name host port protocol`() {
        val result = EnrollmentUriParser.parse("My Server,tak.example.com,8089,ssl")

        assertTrue(result.success)
        assertEquals(EnrollmentKind.ITAK_CSV, result.kind)
        assertEquals("My Server", result.displayName)
        assertEquals("tak.example.com", result.host)
        assertEquals(8089, result.port)
        assertEquals("ssl", result.protocol)
    }

    @Test
    fun `blank input fails`() {
        val result = EnrollmentUriParser.parse("")
        assertFalse(result.success)
    }

    @Test
    fun `unsupported scheme fails`() {
        val result = EnrollmentUriParser.parse("https://example.com/enroll")
        assertFalse(result.success)
    }

    @Test
    fun `protocol normalization maps https and tls to ssl`() {
        val split = EnrollmentUriParser.splitHostField("tak.example.com:8089:tls", null, null, null)
        assertEquals("tls", split.protocol)
    }
}
