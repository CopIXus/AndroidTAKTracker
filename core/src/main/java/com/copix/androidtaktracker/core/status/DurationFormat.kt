package com.copix.androidtaktracker.core.status

/** Operator-friendly durations: "12 sec ago", "2m 14s", "1h 05m". */
object DurationFormat {
    fun age(epochMs: Long, nowMs: Long): String {
        if (epochMs <= 0L) return "Never"
        val ms = nowMs - epochMs
        if (ms < 1_000L) return "Just now"
        return "${compact(ms)} ago"
    }

    /** Countdown to [dueEpochMs]; "Now" once due, "—" when nothing is scheduled. */
    fun countdown(dueEpochMs: Long, nowMs: Long): String {
        if (dueEpochMs <= 0L) return "—"
        val ms = dueEpochMs - nowMs
        if (ms <= 0L) return "Now"
        return compact(ms)
    }

    fun compact(ms: Long): String {
        val totalSec = (ms / 1_000L).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h %02dm".format(m)
            m > 0 -> "${m}m %02ds".format(s)
            else -> "$s sec"
        }
    }

    /** "5 sec" / "3 min" / "1h 30m" for an interval length. */
    fun interval(seconds: Long): String = when {
        seconds < 60 -> "$seconds sec"
        seconds % 60 == 0L && seconds < 3600 -> "${seconds / 60} min"
        else -> compact(seconds * 1_000L)
    }
}
