package com.copix.androidtaktracker.core.cot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CotEventBuilderTest {

    private fun sampleFix(
        source: GpsSourceKind = GpsSourceKind.FUSED,
        isHeld: Boolean = false,
    ) = GpsFix(
        latitude = 38.8895,
        longitude = -77.0353,
        altitudeMeters = 12.0,
        speedMetersPerSecond = 3.0,
        courseDegrees = 90.0,
        accuracyMeters = 5.0,
        timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        source = source,
        isHeld = isHeld,
    )

    private fun sampleIdentity(phone: String? = null, remarks: String? = null, battery: Int? = null) = CotIdentity(
        uid = "ANDROIDTAKTRACKER-abc123",
        callsign = "ALPHA-1",
        team = "Cyan",
        role = "Team Member",
        cotType = CotEventBuilder.GROUND_UNIT_TYPE,
        phone = phone,
        remarks = remarks,
        batteryPercent = battery,
    )

    @Test
    fun `build emits ATAK-shaped self-SA contact, group, and takv fields`() {
        val xml = CotEventBuilder.build(sampleFix(), sampleIdentity(), Duration.ofSeconds(60), deviceModel = "Pixel 9")

        assertTrue(xml.contains("uid=\"ANDROIDTAKTRACKER-abc123\""))
        assertTrue(xml.contains("type=\"a-f-G-U-C-I\""))
        assertTrue(xml.contains("<contact callsign=\"ALPHA-1\" endpoint=\"*:-1:stcp\"/>"))
        assertTrue(xml.contains("<uid Droid=\"ALPHA-1\"/>"))
        assertTrue(xml.contains("<__group name=\"Cyan\" role=\"Team Member\"/>"))
        assertTrue(xml.contains("platform=\"AndroidTAKTracker\""))
        assertTrue(xml.contains("device=\"Pixel 9\""))
        assertTrue(xml.contains("<precisionlocation altsrc=\"GPS\" geopointsrc=\"GPS\"/>"))
        assertTrue(xml.endsWith("</event>\n"))
    }

    @Test
    fun `build escapes special characters in callsign and remarks`() {
        val identity = sampleIdentity(remarks = "Squad <Alpha> & \"Bravo\"").copy(callsign = "A&B<team>")
        val xml = CotEventBuilder.build(sampleFix(), identity, Duration.ofSeconds(60))

        assertFalse(xml.contains("A&B<team>"))
        assertTrue(xml.contains("A&amp;B&lt;team&gt;"))
        assertTrue(xml.contains("Squad &lt;Alpha&gt; &amp; &quot;Bravo&quot;"))
    }

    @Test
    fun `build includes phone only when present`() {
        val withPhone = CotEventBuilder.build(sampleFix(), sampleIdentity(phone = "555-1234"), Duration.ofSeconds(60))
        assertTrue(withPhone.contains("phone=\"555-1234\""))

        val withoutPhone = CotEventBuilder.build(sampleFix(), sampleIdentity(), Duration.ofSeconds(60))
        assertFalse(withoutPhone.contains("phone="))
    }

    @Test
    fun `build includes battery status only when present`() {
        val withBattery = CotEventBuilder.build(sampleFix(), sampleIdentity(battery = 42), Duration.ofSeconds(60))
        assertTrue(withBattery.contains("<status battery=\"42\"/>"))

        val withoutBattery = CotEventBuilder.build(sampleFix(), sampleIdentity(), Duration.ofSeconds(60))
        assertFalse(withoutBattery.contains("<status"))
    }

    @Test
    fun `network ip and held fixes use estimated how and USER geopointsrc`() {
        val xml = CotEventBuilder.build(sampleFix(source = GpsSourceKind.NETWORK_IP), sampleIdentity(), Duration.ofSeconds(60))
        assertTrue(xml.contains("how=\"h-e\""))
        assertTrue(xml.contains("<precisionlocation altsrc=\"DTED0\" geopointsrc=\"USER\"/>"))
    }

    @Test
    fun `fused fixes use measured-gps how and GPS geopointsrc`() {
        val xml = CotEventBuilder.build(sampleFix(source = GpsSourceKind.FUSED), sampleIdentity(), Duration.ofSeconds(60))
        assertTrue(xml.contains("how=\"m-g\""))
    }

    @Test
    fun `normalizeCourse wraps into 0 to 360`() {
        assertEquals(10.0, CotEventBuilder.normalizeCourse(370.0), 0.0001)
        assertEquals(350.0, CotEventBuilder.normalizeCourse(-10.0), 0.0001)
        assertEquals(0.0, CotEventBuilder.normalizeCourse(360.0), 0.0001)
    }

    @Test
    fun `course offset is applied and normalized`() {
        val xml = CotEventBuilder.build(sampleFix(), sampleIdentity(), Duration.ofSeconds(60), courseOffsetDegrees = 300.0)
        // course 90 + offset 300 = 390 -> normalized to 30
        assertTrue(xml.contains("course=\"30\""))
    }

    @Test
    fun `escapeXml handles all five predefined entities`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", CotEventBuilder.escapeXml("&<>\"'"))
        assertEquals("plain", CotEventBuilder.escapeXml("plain"))
    }
}
