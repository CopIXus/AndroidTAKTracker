package com.copix.androidtaktracker.core.update

import com.copix.androidtaktracker.core.config.UpdateSettings
import com.copix.androidtaktracker.core.util.RedactedLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.time.Duration

enum class UpdateAssetKind { NONE, APK }

data class UpdateCheckResult(
    val success: Boolean,
    val error: String? = null,
    val currentVersion: String = "0.1.0",
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    /** CHANGELOG.md section for the new version (plain text), when it could be fetched. */
    val changelogNotes: String? = null,
    val downloadUrl: String? = null,
    val assetName: String? = null,
    val assetKind: UpdateAssetKind = UpdateAssetKind.NONE,
    val sha256Url: String? = null,
    val sha256Expected: String? = null,
    val updateAvailable: Boolean = false,
)

interface UpdateService {
    val currentVersion: String
    suspend fun check(): UpdateCheckResult
    suspend fun verifySha256(bytes: ByteArray, expected: String?): Boolean
}

/** GitHub Releases updater for CopIXus/AndroidTAKTracker (APK asset named AndroidTAKTracker.apk). */
class GitHubUpdateService(
    private val settings: () -> UpdateSettings,
    private val log: RedactedLogger,
    override val currentVersion: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(Duration.ofMinutes(5)).build(),
) : UpdateService {

    companion object {
        private const val APK_ASSET_NAME = "AndroidTAKTracker.apk"
        private val LEADING_SEMVER = Regex("""^(?<ver>\d+(?:\.\d+){0,3})""")

        /**
         * Prefer a releases list so we can pick the newest tag that actually ships the needed
         * asset. Configured `.../releases/latest` is rewritten to `.../releases?per_page=20`.
         */
        internal fun resolveReleasesListUrl(configuredUrl: String?): String {
            if (configuredUrl.isNullOrBlank())
                return "https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases?per_page=20"
            val trimmed = configuredUrl.trimEnd('/')
            if (trimmed.endsWith("/releases/latest", ignoreCase = true))
                return trimmed.removeSuffix("/latest") + "?per_page=20"
            return configuredUrl
        }

        internal fun isNewer(latest: String, current: String): Boolean {
            val l = parseVersion(normalize(latest)) ?: return false
            val c = parseVersion(normalize(current)) ?: return true
            return compareVersions(l, c) > 0
        }

        /** Normalize tags like build-0.1.5, v0.1.5, 0.1.5+sha, 0.1.5-beta to 0.1.5. */
        internal fun normalize(v: String): String {
            var value = v.trim()
            value = when {
                value.startsWith("build-", ignoreCase = true) -> value.substring("build-".length)
                value.length > 1 && (value[0] == 'v' || value[0] == 'V') && value[1].isDigit() -> value.substring(1)
                else -> value
            }

            val plus = value.indexOf('+')
            if (plus >= 0) value = value.substring(0, plus)

            val m = LEADING_SEMVER.find(value) ?: return "0.0.0"
            val parts = m.groups["ver"]!!.value.split('.').filter { it.isNotEmpty() }
            return when (parts.size) {
                0 -> "0.0.0"
                1 -> "${parts[0]}.0.0"
                2 -> "${parts[0]}.${parts[1]}.0"
                else -> "${parts[0]}.${parts[1]}.${parts[2]}"
            }
        }

        private fun parseVersion(v: String): IntArray? =
            try {
                v.split('.').map { it.toInt() }.toIntArray()
            } catch (_: Exception) {
                null
            }

        private fun compareVersions(a: IntArray, b: IntArray): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val av = a.getOrElse(i) { 0 }
                val bv = b.getOrElse(i) { 0 }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }

        /** Raw CHANGELOG.md URL at the release tag, derived from the GitHub releases API URL. */
        internal fun tryBuildChangelogUrl(configuredApiUrl: String?, tag: String): String? {
            if (tag.isBlank()) return null
            val api = configuredApiUrl?.takeIf { it.isNotBlank() }
                ?: "https://api.github.com/repos/CopIXus/AndroidTAKTracker/releases"
            val m = Regex("""api\.github\.com/repos/(?<owner>[^/]+)/(?<repo>[^/?#]+)""", RegexOption.IGNORE_CASE)
                .find(api) ?: return null
            val owner = m.groups["owner"]!!.value
            val repo = m.groups["repo"]!!.value
            val encodedTag = java.net.URLEncoder.encode(tag, "UTF-8")
            return "https://raw.githubusercontent.com/$owner/$repo/$encodedTag/CHANGELOG.md"
        }

        /**
         * Keep-a-Changelog section for [version]; continuous builds have no versioned heading,
         * so fall back to the [Unreleased] section (their pending changes).
         */
        internal fun extractChangelogSection(markdown: String, version: String): String? {
            val lines = markdown.replace("\r\n", "\n").split('\n')
            val headings = mutableListOf<Pair<Int, String>>()
            for (i in lines.indices) {
                if (lines[i].startsWith("## ")) headings.add(i to lines[i])
            }
            if (headings.isEmpty()) return null

            var startIdx = headings.indexOfFirst {
                it.second.contains("[$version]", ignoreCase = true) || it.second.contains(" $version ", ignoreCase = true)
            }
            if (startIdx < 0) startIdx = headings.indexOfFirst { it.second.contains("unreleased", ignoreCase = true) }
            if (startIdx < 0) return null

            // Skip the heading itself — callers render their own "What's new in {version}" title.
            val from = headings[startIdx].first + 1
            val to = if (startIdx + 1 < headings.size) headings[startIdx + 1].first else lines.size
            val section = lines.subList(from.coerceAtMost(lines.size), to.coerceAtMost(lines.size)).joinToString("\n").trim()
            return section.ifEmpty { null }
        }

        /** Light markdown cleanup for display: links -> text, no emphasis/backticks. */
        internal fun markdownToPlainText(markdown: String): String {
            var text = Regex("""\[([^]]+)]\([^)]*\)""").replace(markdown) { it.groupValues[1] }
            text = text.replace("**", "").replace("`", "")
            text = Regex("""^###\s+(.+)$""", RegexOption.MULTILINE).replace(text) { "${it.groupValues[1]}:" }
            text = Regex("""^##\s+""", RegexOption.MULTILINE).replace(text, "")
            text = Regex("""\n{3,}""").replace(text, "\n\n")
            return text.trim()
        }

        private fun truncate(s: String?, max: Int): String? =
            if (s == null) null else if (s.length <= max) s else s.take(max) + "…"
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("body") val body: String? = null,
        @SerialName("draft") val draft: Boolean = false,
        @SerialName("assets") val assets: List<GitHubAsset>? = null,
    )

    @Serializable
    private data class GitHubAsset(
        @SerialName("name") val name: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val current = currentVersion
        try {
            val configuredUrl = settings().releasesApiUrl
            val url = resolveReleasesListUrl(configuredUrl)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AndroidTAKTracker/0.1")
                .header("Accept", "application/vnd.github+json")
                .build()

            http.newCall(request).execute().use { resp ->
                if (resp.code == 403 || resp.code == 429) {
                    return@withContext UpdateCheckResult(
                        success = false,
                        error = "GitHub rate limit or access denied. Try again later.",
                        currentVersion = current,
                    )
                }
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult(success = false, error = "GitHub HTTP ${resp.code}.", currentVersion = current)
                }

                val body = resp.body?.string().orEmpty()
                val releases = parseReleases(body)
                if (releases.isEmpty())
                    return@withContext UpdateCheckResult(success = false, error = "Empty release response.", currentVersion = current)

                val selected = selectBestRelease(releases)
                    ?: return@withContext UpdateCheckResult(
                        success = false,
                        error = "No release with a $APK_ASSET_NAME asset was found.",
                        currentVersion = current,
                    )

                val (release, tag, normalized, asset, shaAsset) = selected
                var expectedSha: String? = null
                val shaUrl = shaAsset?.browserDownloadUrl
                if (!shaUrl.isNullOrBlank()) {
                    expectedSha = try {
                        val shaText = http.newCall(Request.Builder().url(shaUrl).build()).execute().use { it.body?.string() }
                        shaText?.let { extractSha256(it) }
                    } catch (_: Exception) {
                        log.warn("Update", "Could not download SHA256 sidecar; integrity check will be skipped.")
                        null
                    }
                }

                val newer = isNewer(normalized, current)
                val downloadUrl = asset.browserDownloadUrl
                val error = if (newer && downloadUrl.isNullOrBlank())
                    "Version $normalized is available but ${asset.name} was not found on the release."
                else null

                val changelog = if (newer) tryFetchChangelog(configuredUrl, tag, normalized) else null

                UpdateCheckResult(
                    success = true,
                    currentVersion = current,
                    latestVersion = normalized,
                    releaseNotes = truncate(release.body, 800),
                    changelogNotes = changelog,
                    downloadUrl = downloadUrl,
                    assetName = asset.name,
                    assetKind = UpdateAssetKind.APK,
                    sha256Url = shaUrl,
                    sha256Expected = expectedSha,
                    updateAvailable = newer && !downloadUrl.isNullOrBlank(),
                    error = error,
                )
            }
        } catch (ex: Exception) {
            log.warn("Update", "Check failed: ${ex.javaClass.simpleName}")
            UpdateCheckResult(success = false, error = "Update check failed (${ex.javaClass.simpleName}).", currentVersion = current)
        }
    }

    override suspend fun verifySha256(bytes: ByteArray, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        return actual.equals(expected, ignoreCase = true)
    }

    private fun parseReleases(body: String): List<GitHubRelease> = try {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitHubRelease.serializer()), body)
    } catch (_: Exception) {
        try {
            listOf(json.decodeFromString(GitHubRelease.serializer(), body))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class SelectedRelease(
        val release: GitHubRelease,
        val tag: String,
        val normalized: String,
        val asset: GitHubAsset,
        val shaAsset: GitHubAsset?,
    )

    private fun selectBestRelease(releases: List<GitHubRelease>): SelectedRelease? {
        var best: SelectedRelease? = null
        var bestVersion: IntArray? = null

        for (release in releases) {
            if (release.draft) continue
            val tag = release.tagName?.trim().orEmpty()
            if (tag.isEmpty()) continue

            val normalized = normalize(tag)
            val version = try {
                normalized.split('.').map { it.toInt() }.toIntArray()
            } catch (_: Exception) {
                continue
            }

            val asset = release.assets?.firstOrNull { it.name.equals(APK_ASSET_NAME, ignoreCase = true) } ?: continue
            if (asset.browserDownloadUrl.isNullOrBlank()) continue
            val sha = release.assets.firstOrNull { it.name.equals("$APK_ASSET_NAME.sha256", ignoreCase = true) }
                ?: release.assets.firstOrNull { it.name?.endsWith(".sha256", ignoreCase = true) == true }

            if (best == null || compareVersions(version, bestVersion!!) > 0) {
                best = SelectedRelease(release, tag, normalized, asset, sha)
                bestVersion = version
            }
        }

        return best
    }

    private fun extractSha256(text: String): String? =
        Regex("""\b[a-fA-F0-9]{64}\b""").find(text.trim())?.value

    private suspend fun tryFetchChangelog(configuredApiUrl: String?, tag: String, normalizedVersion: String): String? {
        val url = tryBuildChangelogUrl(configuredApiUrl, tag) ?: return null
        return try {
            val request = Request.Builder().url(url).build()
            val markdown = http.newCall(request).execute().use { it.body?.string() } ?: return null
            val section = extractChangelogSection(markdown, normalizedVersion) ?: return null
            truncate(markdownToPlainText(section), 6000)
        } catch (_: Exception) {
            log.warn("Update", "Could not fetch CHANGELOG.md for the new version; showing release body only.")
            null
        }
    }
}
