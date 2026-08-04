package com.copix.androidtaktracker.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshMulticastSupportTest {
    @Test
    fun detectsForeignAtakSelfSa() {
        val xml =
            """<event version="2.0" uid="ANDROID-abc" type="a-f-G-U-C"><detail>""" +
                """<contact callsign="ATAK1" endpoint="*:-1:stcp"/>""" +
                """<takv platform="ATAK-CIV" version="4.10"/></detail></event>"""
        assertTrue(MeshMulticastSupport.looksLikeForeignAtakSelfSa(xml, "ANDROIDTAKTRACKER-xyz"))
    }

    @Test
    fun ignoresOwnUid() {
        val uid = "ANDROIDTAKTRACKER-xyz"
        val xml =
            """<event version="2.0" uid="$uid" type="a-f-G-U-C"><detail>""" +
                """<contact callsign="ME.att"/><takv platform="AndroidTAKTracker"/></detail></event>"""
        assertFalse(MeshMulticastSupport.looksLikeForeignAtakSelfSa(xml, uid))
    }
}
