package com.copix.androidtaktracker.core.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerStreamsTest {
    @Test
    fun `uniqueEnabled keeps the first stream and drops later duplicates`() {
        val first = ServerProfile(id = "a", host = "Tak.Example.com", port = 8089, protocol = "ssl")
        val dup = ServerProfile(id = "b", host = "tak.example.com", port = 8089, protocol = "SSL")
        val otherPort = ServerProfile(id = "c", host = "tak.example.com", port = 8088, protocol = "ssl")
        val disabled = ServerProfile(id = "d", host = "other.example.com", port = 8089, enabled = false)
        val kept = ServerStreams.uniqueEnabled(listOf(first, dup, otherPort, disabled))
        assertEquals(listOf("a", "c"), kept.map { it.id })
    }
}
