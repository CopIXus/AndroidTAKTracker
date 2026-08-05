package com.copix.androidtaktracker.host

import com.copix.androidtaktracker.core.util.LogLevel
import com.copix.androidtaktracker.core.util.RedactedFileLogger
import com.copix.androidtaktracker.core.util.RedactedLogger
import java.io.File

/** Logcat + rotating file logger. */
class AndroidLogger(logsDir: File) : RedactedLogger {
    private val file = RedactedFileLogger(logsDir)

    override fun setMinLevel(level: LogLevel) = file.setMinLevel(level)
    override fun setMaxTotalSizeMb(megabytes: Int) = file.setMaxTotalSizeMb(megabytes)
    override val logsDirectory: String get() = file.logsDirectory
    override fun clearOldLogs(olderThanMs: Long) = file.clearOldLogs(olderThanMs)
    override fun enforceSizeLimit() = file.enforceSizeLimit()

    fun readRecentText(maxBytes: Int = 64 * 1024): String = file.readRecentText(maxBytes)

    override fun info(category: String, message: String) {
        android.util.Log.i(category, RedactedFileLogger.redact(message))
        file.info(category, message)
    }

    override fun warn(category: String, message: String) {
        android.util.Log.w(category, RedactedFileLogger.redact(message))
        file.warn(category, message)
    }

    override fun error(category: String, message: String, ex: Throwable?) {
        android.util.Log.e(category, RedactedFileLogger.redact(message), ex)
        file.error(category, message, ex)
    }

    override fun debug(category: String, message: String) {
        android.util.Log.d(category, RedactedFileLogger.redact(message))
        file.debug(category, message)
    }
}
