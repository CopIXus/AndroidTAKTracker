package com.copix.androidtaktracker.core.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Minimum severity written to disk / logcat. */
enum class LogLevel { TRACE, DEBUG, INFORMATION, WARNING, ERROR }

/**
 * Simple logging façade shared by every core service. Implementations should never write raw
 * enroll URLs, tokens, or private keys to disk — use [RedactedLogger.redact] on free-form text.
 */
interface RedactedLogger {
    fun setMinLevel(level: LogLevel)
    fun setMaxTotalSizeMb(megabytes: Int)
    fun info(category: String, message: String)
    fun warn(category: String, message: String)
    fun error(category: String, message: String, ex: Throwable? = null)
    fun debug(category: String, message: String)
    val logsDirectory: String
    fun clearOldLogs(olderThanMs: Long)
    fun enforceSizeLimit()
}

/** No-op logger — useful for unit tests and previews. */
object NoopRedactedLogger : RedactedLogger {
    override fun setMinLevel(level: LogLevel) {}
    override fun setMaxTotalSizeMb(megabytes: Int) {}
    override fun info(category: String, message: String) {}
    override fun warn(category: String, message: String) {}
    override fun error(category: String, message: String, ex: Throwable?) {}
    override fun debug(category: String, message: String) {}
    override val logsDirectory: String = ""
    override fun clearOldLogs(olderThanMs: Long) {}
    override fun enforceSizeLimit() {}
}

/**
 * Rotating file logger that redacts tokens, passwords, and enroll URLs before they ever hit disk.
 * Pure JVM/File-based (no Android dependency) so it can be exercised from unit tests.
 */
class RedactedFileLogger(logsDirectory: File) : RedactedLogger {
    private val logsDir: File = logsDirectory.also { it.mkdirs() }
    private val gate = Object()
    @Volatile private var minLevel: LogLevel = LogLevel.ERROR
    @Volatile private var maxTotalBytes: Long = 30L * 1024 * 1024
    private var writer: Writer? = null
    private var currentPath: File? = null
    private var currentDay: LocalDate = LocalDate.MIN
    private var writesSinceTrim = 0

    override val logsDirectory: String get() = logsDir.absolutePath

    override fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    override fun setMaxTotalSizeMb(megabytes: Int) {
        val mb = megabytes.coerceIn(1, 1024)
        synchronized(gate) { maxTotalBytes = mb * 1024L * 1024L }
    }

    override fun info(category: String, message: String) = write(LogLevel.INFORMATION, category, message)
    override fun warn(category: String, message: String) = write(LogLevel.WARNING, category, message)
    override fun debug(category: String, message: String) = write(LogLevel.DEBUG, category, message)

    override fun error(category: String, message: String, ex: Throwable?) {
        val full = if (ex == null) message else "$message | ${ex.javaClass.simpleName}: ${ex.message}"
        write(LogLevel.ERROR, category, full)
    }

    override fun clearOldLogs(olderThanMs: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        logsDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".log") }?.forEach { file ->
            try {
                if (file.lastModified() < cutoff) file.delete()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    override fun enforceSizeLimit() {
        synchronized(gate) { trimToLimitLocked() }
    }

    private fun write(level: LogLevel, category: String, message: String) {
        // Diagnostics always keep Information+ regardless of min level, everything else honors it.
        val diagnosticsOps = category.equals("Diagnostics", ignoreCase = true)
        if (diagnosticsOps) {
            if (level.ordinal < LogLevel.INFORMATION.ordinal) return
        } else if (level.ordinal < minLevel.ordinal) {
            return
        }

        val redacted = redact(message)
        val stamp = OffsetDateTime.now().format(TIMESTAMP_FORMAT)
        val line = "$stamp [$level] $category: $redacted"
        synchronized(gate) {
            ensureWriter()
            writer?.let {
                it.write(line)
                it.write("\n")
                it.flush()
            }
            writesSinceTrim++
            if (writesSinceTrim >= 25) {
                writesSinceTrim = 0
                trimToLimitLocked()
            }
        }
    }

    private fun ensureWriter() {
        val today = LocalDate.now()
        if (writer != null && currentDay == today) return
        try { writer?.close() } catch (_: Exception) { /* ignore */ }
        currentDay = today
        val path = File(logsDir, "$FILE_PREFIX${today.format(DAY_FORMAT)}.log")
        currentPath = path
        writer = OutputStreamWriter(FileOutputStream(path, true), Charsets.UTF_8)
    }

    private fun trimToLimitLocked() {
        try {
            val files = (logsDir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".log") } ?: emptyArray())
                .sortedBy { it.lastModified() }
            var total = files.sumOf { it.length() }
            if (total <= maxTotalBytes) return

            for (file in files) {
                if (total <= maxTotalBytes) break
                if (file == currentPath) continue
                try {
                    val len = file.length()
                    if (file.delete()) total -= len
                } catch (_: Exception) { /* ignore */ }
            }

            val active = currentPath
            if (total > maxTotalBytes && active != null && active.exists()) {
                try {
                    writer?.close()
                    writer = null
                    val keep = (maxTotalBytes / 2).coerceAtLeast(256L * 1024)
                    val bytes = active.readBytes()
                    if (bytes.size > keep) {
                        var start = bytes.size - keep.toInt()
                        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
                        if (start < bytes.size) start++
                        active.writeBytes(bytes.copyOfRange(start, bytes.size))
                    }
                    currentDay = LocalDate.MIN
                    ensureWriter()
                } catch (_: Exception) {
                    currentDay = LocalDate.MIN
                    ensureWriter()
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    fun close() {
        synchronized(gate) {
            try { writer?.close() } catch (_: Exception) { /* ignore */ }
            writer = null
        }
    }

    companion object {
        private const val FILE_PREFIX = "androidtaktracker-"
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)

        private val SECRET_PATTERN = Regex(
            "(?i)(password|passwd|pwd|token|secret|authorization)=([^\\s&\"']+)|" +
                "(opentaktracker://\\S+)|(tak://\\S+)|" +
                "(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)"
        )
        private val HOST_PATTERN = Regex("(?i)\\b(host=)([A-Za-z0-9.\\-]+)")

        /** Masks tokens/passwords/enroll URLs/private keys and partially masks host= values. */
        fun redact(input: String): String {
            if (input.isEmpty()) return input
            var result = SECRET_PATTERN.replace(input) { m ->
                when {
                    m.groups[1] != null -> "${m.groupValues[1]}=***"
                    m.groups[3] != null || m.groups[4] != null -> "[REDACTED_ENROLL_URL]"
                    else -> "[REDACTED_KEY_MATERIAL]"
                }
            }
            result = HOST_PATTERN.replace(result) { m ->
                val host = m.groupValues[2]
                val masked = if (host.length <= 4) "***" else host.take(2) + "***" + host.takeLast(2)
                m.groupValues[1] + masked
            }
            return result
        }
    }
}
