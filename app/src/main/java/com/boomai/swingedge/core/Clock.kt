package com.boomai.swingedge.core

/** IST (UTC+5:30, no DST) market clock. Pure functions of epoch milliseconds. */
object Clock {
    const val IST_OFFSET_MS = 19_800_000L
    const val DAY_MS = 86_400_000L
    const val OPEN_MIN = 9 * 60 + 15
    const val CLOSE_MIN = 15 * 60 + 30
    const val SESSION_MIN = CLOSE_MIN - OPEN_MIN
    const val BRIEF_MIN = 8 * 60 + 55
    const val FIRST_SCAN_MIN = 9 * 60 + 20
    const val EOD_MIN = 15 * 60 + 40
    /** After this minute a weekday with no index bar is treated as a market holiday. */
    const val HOLIDAY_CHECK_MIN = 9 * 60 + 30

    fun epochDay(ms: Long): Int = Math.floorDiv(ms + IST_OFFSET_MS, DAY_MS).toInt()
    fun minuteOfDay(ms: Long): Int = (Math.floorMod(ms + IST_OFFSET_MS, DAY_MS) / 60_000L).toInt()
    fun msAt(epochDay: Int, minute: Int): Long = epochDay.toLong() * DAY_MS + minute * 60_000L - IST_OFFSET_MS
    /** 0 = Monday ... 6 = Sunday. */
    fun dow(epochDay: Int): Int = Math.floorMod(epochDay + 3, 7)
    fun isWeekday(epochDay: Int): Boolean = dow(epochDay) < 5
    /** Monday-based week number; two days are in the same trading week iff equal. */
    fun weekIndex(epochDay: Int): Int = Math.floorDiv(epochDay + 3, 7)

    fun isMarketOpen(ms: Long): Boolean {
        val m = minuteOfDay(ms)
        return isWeekday(epochDay(ms)) && m >= OPEN_MIN && m < CLOSE_MIN
    }

    /** Scan slot within a day: 0 for 09:20-09:30, then one per quarter hour (09:31.., last 15:16-15:30); -1 outside. */
    fun scanSlot(minute: Int): Int {
        if (minute < FIRST_SCAN_MIN || minute > CLOSE_MIN) return -1
        return (minute - (OPEN_MIN + 1)) / 15
    }

    /** Unique key of the scan slot containing [ms], or -1 if no scan slot is active. */
    fun scanSlotKey(ms: Long): Long {
        val d = epochDay(ms)
        if (!isWeekday(d)) return -1
        val s = scanSlot(minuteOfDay(ms))
        return if (s < 0) -1 else d.toLong() * 100 + s
    }

    private val wakeMinutes: IntArray by lazy {
        val l = ArrayList<Int>()
        l.add(BRIEF_MIN)
        l.add(FIRST_SCAN_MIN)
        var q = OPEN_MIN + 15
        while (q < CLOSE_MIN) {
            l.add(q + 1)
            q += 15
        }
        l.add(EOD_MIN)
        l.toIntArray()
    }

    /** Next wake strictly after [ms]: 08:55 brief, 09:20 first scan, every quarter hour + 1 min to 15:16, 15:40 EOD; weekdays only. */
    fun nextWake(ms: Long): Long {
        val d0 = epochDay(ms)
        for (d in d0..d0 + 8) {
            if (!isWeekday(d)) continue
            for (m in wakeMinutes) {
                val t = msAt(d, m)
                if (t > ms) return t
            }
        }
        return ms + 15 * 60_000L
    }

    fun nextWeekday(epochDay: Int): Int {
        var d = epochDay + 1
        while (!isWeekday(d)) d++
        return d
    }

    /** Fraction of the cash session elapsed at [ms] on its own day, 0..1. */
    fun sessionFraction(ms: Long): Double {
        val m = minuteOfDay(ms)
        return ((m - OPEN_MIN).toDouble() / SESSION_MIN).coerceIn(0.0, 1.0)
    }

    fun hhmm(minute: Int): String {
        val h = minute / 60
        val m = minute % 60
        return (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
    }

    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "Mon 10 Nov" for an IST epoch day. */
    fun dayLabel(epochDay: Int): String {
        return try {
            val ld = java.time.LocalDate.ofEpochDay(epochDay.toLong())
            DAYS[dow(epochDay)] + " " + ld.dayOfMonth + " " + MONTHS[ld.monthValue - 1]
        } catch (t: Throwable) {
            "day $epochDay"
        }
    }
}
