package com.copix.androidtaktracker.core.portal

/**
 * Parsed Marti / ATAK fileshare announce from inbound CoT (Portal Pref package delivery).
 */
data class FileShareOffer(
    val filename: String? = null,
    val sha256: String? = null,
    val senderUrl: String? = null,
    val sizeInBytes: Long? = null,
) {
    val looksLikePreferencePackage: Boolean
        get() = !filename.isNullOrBlank() && filename.startsWith("Pref-", ignoreCase = true)
}

/** Extracts fileshare / enterprise-sync offers from CoT XML. */
object FileShareCotParser {
    fun looksLikeFileShareEvent(xml: String): Boolean =
        xml.contains("fileshare", ignoreCase = true) ||
            xml.contains("senderUrl", ignoreCase = true) ||
            (xml.contains("b-f-t", ignoreCase = true) &&
                (xml.contains("sha256", ignoreCase = true) || xml.contains("hash", ignoreCase = true)))

    fun tryParse(xml: String): FileShareOffer? {
        if (!looksLikeFileShareEvent(xml)) return null

        val filename = attr(xml, "filename") ?: attr(xml, "name")
        val sha = attr(xml, "sha256") ?: attr(xml, "sha256hash") ?: attr(xml, "hash")
        val url = attr(xml, "senderUrl") ?: attr(xml, "url")
        val size = attr(xml, "sizeInBytes")?.toLongOrNull() ?: attr(xml, "size")?.toLongOrNull()

        if (filename.isNullOrBlank() && sha.isNullOrBlank() && url.isNullOrBlank()) return null
        return FileShareOffer(filename, sha, url, size)
    }

    private fun attr(xml: String, name: String): String? {
        val escaped = Regex.escape(name)
        return Regex("""$escaped\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
    }
}
