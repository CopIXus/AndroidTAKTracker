package com.copix.androidtaktracker.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogExtractTest {

    private val sampleChangelog = """
        # Changelog

        ## [Unreleased]
        ### Added
        - Pending work in progress.

        ## [0.2.0] - 2026-01-15
        ### Added
        - Mesh SA multicast support.
        - Network IP geolocation fallback.

        ### Fixed
        - Reconnect backoff no longer spins on TLS failures.

        ## [0.1.0] - 2025-12-01
        ### Added
        - Initial release.
    """.trimIndent()

    @Test
    fun `extractChangelogSection finds the exact version heading`() {
        val section = GitHubUpdateService.extractChangelogSection(sampleChangelog, "0.2.0")
        assertTrue(section != null)
        assertTrue(section!!.contains("Mesh SA multicast support."))
        assertTrue(section.contains("Reconnect backoff no longer spins on TLS failures."))
        assertTrue(!section.contains("Initial release."))
        assertTrue("heading itself should be skipped", !section.contains("## [0.2.0]"))
    }

    @Test
    fun `extractChangelogSection falls back to unreleased when version not found`() {
        val section = GitHubUpdateService.extractChangelogSection(sampleChangelog, "9.9.9")
        assertTrue(section != null)
        assertTrue(section!!.contains("Pending work in progress."))
    }

    @Test
    fun `extractChangelogSection returns null when no headings exist`() {
        val section = GitHubUpdateService.extractChangelogSection("Just plain text, no headings.", "0.2.0")
        assertNull(section)
    }

    @Test
    fun `markdownToPlainText strips links, emphasis, and heading markers`() {
        val text = GitHubUpdateService.markdownToPlainText(
            "### Added\n- Support for [Mesh SA](https://example.com/mesh) via **UDP** multicast with `code`.",
        )
        assertTrue(text.contains("Added:"))
        assertTrue(text.contains("Support for Mesh SA via UDP multicast with code."))
        assertTrue(!text.contains("**"))
        assertTrue(!text.contains("`"))
        assertTrue(!text.contains("["))
    }

    @Test
    fun `normalize strips build and v prefixes and pre-release suffixes`() {
        assertEquals("0.1.5", GitHubUpdateService.normalize("build-0.1.5"))
        assertEquals("0.1.5", GitHubUpdateService.normalize("v0.1.5"))
        assertEquals("0.1.5", GitHubUpdateService.normalize("0.1.5+abcdef"))
        assertEquals("0.1.5", GitHubUpdateService.normalize("0.1.5-beta"))
        assertEquals("0.2.0", GitHubUpdateService.normalize("0.2"))
        assertEquals("3.0.0", GitHubUpdateService.normalize("3"))
    }

    @Test
    fun `isNewer compares normalized semantic versions`() {
        assertTrue(GitHubUpdateService.isNewer("0.2.0", "0.1.9"))
        assertTrue(GitHubUpdateService.isNewer("v1.0.0", "build-0.9.9"))
        assertTrue(!GitHubUpdateService.isNewer("0.1.0", "0.1.0"))
        assertTrue(!GitHubUpdateService.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `tryBuildChangelogUrl derives raw githubusercontent url from api url`() {
        val url = GitHubUpdateService.tryBuildChangelogUrl(
            "https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases",
            "0.2.0",
        )
        assertEquals("https://raw.githubusercontent.com/CopIXus/AndroidTAKTracker/0.2.0/CHANGELOG.md", url)
    }

    @Test
    fun `resolveReleasesListUrl rewrites latest to a paged list`() {
        val url = GitHubUpdateService.resolveReleasesListUrl(
            "https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases/latest",
        )
        assertEquals("https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases?per_page=20", url)
    }
}
