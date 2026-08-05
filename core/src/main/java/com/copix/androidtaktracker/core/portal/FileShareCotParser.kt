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

        // Prefer attributes scoped to the <fileshare .../> element itself — a whole-document
        // regex can grab same-named attributes from sibling detail elements or nested URLs.
        val fileshareTag = Regex("""<fileshare\b[^>]*>""", RegexOption.IGNORE_CASE).find(xml)?.value

        fun scoped(vararg names: String): String? {
            for (n in names) {
                if (fileshareTag != null) attr(fileshareTag, n)?.let { return it }
            }
            for (n in names) attr(xml, n)?.let { return it }
            return null
        }

        val filename = scoped("filename", "name")
        // Inside <fileshare> the hash attribute is authoritative; for the whole-document
        // fallback require hex so we never mistake a uid for the hash.
        val sha = fileshareTag?.let { attr(it, "sha256") ?: attr(it, "sha256hash") ?: attr(it, "hash") }
            ?: hexAttr(xml, "sha256") ?: hexAttr(xml, "sha256hash") ?: hexAttr(xml, "hash")
        val url = scoped("senderUrl", "url")
        val size = scoped("sizeInBytes", "size")?.toLongOrNull()

        if (filename.isNullOrBlank() && sha.isNullOrBlank() && url.isNullOrBlank()) return null
        return FileShareOffer(filename, sha, url, size)
    }

    private fun attr(xml: String, name: String): String? {
        val escaped = Regex.escape(name)
        return Regex("""\b$escaped\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
    }

    private fun hexAttr(xml: String, name: String): String? {
        val escaped = Regex.escape(name)
        return Regex("""\b$escaped\s*=\s*["']([0-9a-fA-F]{16,64})["']""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)
    }
}
