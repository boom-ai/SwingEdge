package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.Clock
import com.boomai.swingedge.core.Platform
import java.util.Random

class Note(val channel: String, val id: Int, val title: String, val body: String, val at: Long)

/** Price behaviour of a synthetic symbol. */
class Profile(val drift: Double, val vol: Double, val gap: Double = 0.2, val start: Double = 400.0, val baseVol: Double = 3_000_000.0)

/**
 * Deterministic synthetic Yahoo chart server. Every weekday from [baseDay] has a bar per symbol (holidays are simply
 * not served). 15-minute bars are derived from the daily bar so that aggregating a full day gives back that daily bar.
 */
class FakeYahoo {
    val baseDay = 19700            // a Monday-ish anchor well before the simulations (Dec 2023)
    val horizon = 1300             // weekdays generated
    val holidays = HashSet<Int>()
    val profiles = HashMap<String, Profile>()
    /** sym -> day -> [o,h,l,c,v] replaces the generated bar. */
    val overrides = HashMap<String, HashMap<Int, DoubleArray>>()
    /** Special behaviours by symbol. */
    val erroring = HashSet<String>()      // chart.error payload
    val garbage = HashSet<String>()       // not JSON
    val dead = HashSet<String>()          // null on both hosts
    val host2Only = HashSet<String>()     // null on query1, fine on query2
    val nully = HashSet<String>()         // null entries sprinkled in the arrays
    val shortHistory = HashMap<String, Int>() // only the last N days exist
    var defaultProfile: Profile? = null   // for unknown symbols (null => 404/null)

    private val weekdays: IntArray
    private val posOf = HashMap<Int, Int>()
    private val paths = HashMap<String, Array<DoubleArray>>()

    init {
        val l = ArrayList<Int>()
        var d = baseDay
        while (l.size < horizon) {
            if (Clock.isWeekday(d)) l.add(d)
            d++
        }
        weekdays = l.toIntArray()
        for ((k, x) in weekdays.withIndex()) posOf[x] = k
    }

    fun knows(sym: String) = profiles.containsKey(sym) || defaultProfile != null

    private fun path(sym: String): Array<DoubleArray> = paths.getOrPut(sym) {
        val pr = profiles[sym] ?: defaultProfile!!
        val r = Random(sym.hashCode().toLong() * 31 + 7)
        var prev = pr.start * (0.6 + r.nextDouble())
        Array(horizon) {
            val o = prev * (1 + r.nextGaussian() * pr.vol / 100 * pr.gap)
            val c = prev * (1 + pr.drift / 100 + r.nextGaussian() * pr.vol / 100)
            val h = maxOf(o, c) * (1 + Math.abs(r.nextGaussian()) * pr.vol / 100 * 0.5)
            val lo = minOf(o, c) * (1 - Math.abs(r.nextGaussian()) * pr.vol / 100 * 0.5)
            val v = pr.baseVol * (0.6 + r.nextDouble() * 0.8) * (if (r.nextInt(25) == 0) 3.0 else 1.0)
            prev = c
            doubleArrayOf(o, h, lo, c, Math.rint(v))
        }
    }

    fun isTradingDay(day: Int) = Clock.isWeekday(day) && !holidays.contains(day) && posOf.containsKey(day)

    /** [o,h,l,c,v] for a trading day, or null. */
    fun bar(sym: String, day: Int): DoubleArray? {
        if (!isTradingDay(day) || !knows(sym)) return null
        overrides[sym]?.get(day)?.let { return it }
        return path(sym)[posOf[day]!!]
    }

    fun setBar(sym: String, day: Int, o: Double, h: Double, l: Double, c: Double, v: Double) {
        overrides.getOrPut(sym) { HashMap() }[day] = doubleArrayOf(o, h, l, c, v)
    }

    fun tradingDaysUpTo(day: Int, count: Int): List<Int> {
        val out = ArrayList<Int>()
        var d = day
        while (out.size < count && d >= baseDay) {
            if (isTradingDay(d)) out.add(d)
            d--
        }
        return out.reversed()
    }

    fun nextTradingDay(day: Int): Int {
        var d = day + 1
        while (!isTradingDay(d)) d++
        return d
    }

    /** The 25 fifteen-minute bars of a day: [startMinute, o,h,l,c,v]. */
    fun intradayBars(sym: String, day: Int): List<DoubleArray> {
        val b = bar(sym, day) ?: return emptyList()
        val hsh = Math.floorMod(sym.hashCode() * 31 + day, 1000)
        val kH = 3 + hsh % 9          // 3..11
        val kL = 13 + hsh % 8         // 13..20
        val out = ArrayList<DoubleArray>(25)
        var prev = b[0]
        for (k in 0 until 25) {
            val c = if (k == 24) b[3] else b[0] + (b[3] - b[0]) * (k + 1) / 25.0
            var h = maxOf(prev, c)
            var l = minOf(prev, c)
            if (k == kH) h = b[1]
            if (k == kL) l = b[2]
            out.add(doubleArrayOf((Clock.OPEN_MIN + 15 * k).toDouble(), prev, h, l, c, Math.rint(b[4] / 25.0)))
            prev = c
        }
        return out
    }

    private fun num(sb: StringBuilder, x: Double, isNull: Boolean) {
        if (isNull) sb.append("null") else sb.append(Math.round(x * 100.0) / 100.0)
    }

    private fun payload(sym: String, ts: List<Long>, bars: List<DoubleArray>, off: Int, metaPrice: Double, metaTime: Long): String {
        val sb = StringBuilder(bars.size * 60 + 400)
        sb.append("{\"chart\":{\"result\":[{\"meta\":{\"currency\":\"INR\",\"symbol\":\"").append(sym).append("\",\"regularMarketPrice\":")
        sb.append(Math.round(metaPrice * 100.0) / 100.0).append(",\"regularMarketTime\":").append(metaTime).append(",\"gmtoffset\":19800},\"timestamp\":[")
        sb.append(ts.joinToString(","))
        sb.append("],\"indicators\":{\"quote\":[{")
        val names = arrayOf("open", "high", "low", "close", "volume")
        val holes = nully.contains(sym)
        for ((f, nm) in names.withIndex()) {
            if (f > 0) sb.append(',')
            sb.append('"').append(nm).append("\":[")
            for ((k, b) in bars.withIndex()) {
                if (k > 0) sb.append(',')
                val isNull = holes && (k % 7 == 3 || (k % 11 == 5 && f == 1))
                if (f == 4 && !isNull) sb.append(Math.round(b[off + f])) else num(sb, b[off + f], isNull)
            }
            sb.append(']')
        }
        sb.append("}]}}],\"error\":null}}")
        return sb.toString()
    }

    fun respond(url: String, nowMs: Long): String? {
        val host2 = url.contains("query2.")
        val m = Regex("/v8/finance/chart/([^?]+)\\?range=(\\w+)&interval=(\\w+)").find(url) ?: return null
        val ticker = java.net.URLDecoder.decode(m.groupValues[1].replace("+", "%2B"), "UTF-8")
        val sym = if (ticker.startsWith("^")) ticker else if (ticker.endsWith(".NS")) ticker.substring(0, ticker.length - 3) else return null
        if (dead.contains(sym)) return null
        if (host2Only.contains(sym) && !host2) return null
        if (garbage.contains(sym)) return "<html><body>Too Many Requests</body></html>"
        if (erroring.contains(sym)) return "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found, symbol may be delisted\"}}}"
        if (!knows(sym)) return "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\",\"description\":\"No data found\"}}}"
        val today = Clock.epochDay(nowMs)
        val min = Clock.minuteOfDay(nowMs)
        val interval = m.groupValues[3]
        if (interval == "1d") {
            var days = tradingDaysUpTo(today, 2000).filter { it >= today - 730 && (it < today || min >= Clock.OPEN_MIN) }
            shortHistory[sym]?.let { days = days.takeLast(it) }
            val bars = ArrayList<DoubleArray>()
            val ts = ArrayList<Long>()
            for (d in days) {
                val b = if (d == today && min < Clock.CLOSE_MIN) partial(sym, d, min) else bar(sym, d)
                if (b == null) continue
                bars.add(b)
                ts.add(Clock.msAt(d, Clock.OPEN_MIN) / 1000)
            }
            if (bars.isEmpty()) return "{\"chart\":{\"result\":[],\"error\":null}}"
            return payload(sym, ts, bars, 0, bars.last()[3], nowMs / 1000)
        }
        // 15m, last 5 trading days
        val days = tradingDaysUpTo(today, 5)
        val bars = ArrayList<DoubleArray>()
        val ts = ArrayList<Long>()
        var lastEnd = 0L
        for (d in days) for (b in intradayBars(sym, d)) {
            val startMin = b[0].toInt()
            if (d == today && startMin > min) break
            bars.add(b)
            ts.add(Clock.msAt(d, startMin) / 1000)
            lastEnd = Clock.msAt(d, startMin + 15) / 1000
        }
        if (bars.isEmpty()) return "{\"chart\":{\"result\":[],\"error\":null}}"
        return payload(sym, ts, bars, 1, bars.last()[4], minOf(lastEnd, nowMs / 1000))
    }

    /** Aggregate of the 15m bars started by [min] on [day] (what a daily feed shows mid-session). */
    fun partial(sym: String, day: Int, min: Int): DoubleArray? {
        val bs = intradayBars(sym, day).filter { it[0].toInt() <= min }
        if (bs.isEmpty()) return null
        var h = bs[0][2]
        var l = bs[0][3]
        var v = 0.0
        for (b in bs) {
            if (b[2] > h) h = b[2]
            if (b[3] < l) l = b[3]
            v += b[5]
        }
        return doubleArrayOf(bs[0][1], h, l, bs.last()[4], v)
    }
}

class FakePlatform : Platform {
    var nowMs = 0L
    val files = HashMap<String, String>()
    val notes = ArrayList<Note>()
    val logs = ArrayList<String>()
    val assets = HashMap<String, String>()
    val yahoo = FakeYahoo()
    var offline = false
    var httpDelayMs = 0L
    var httpCalls = 0
    var urlCount = 0
    val urls = ArrayList<String>()
    var keepUrls = false
    var failWrites = false

    override fun now(): Long = nowMs
    override fun httpGetAll(urls: List<String>): List<String?> {
        httpCalls++
        urlCount += urls.size
        if (keepUrls) this.urls.addAll(urls)
        if (httpDelayMs > 0) Thread.sleep(httpDelayMs)
        if (offline) return urls.map { null }
        return urls.map { try { yahoo.respond(it, nowMs) } catch (t: Throwable) { null } }
    }
    override fun read(name: String): String? = synchronized(files) { files[name] }
    override fun write(name: String, text: String) {
        if (failWrites) throw RuntimeException("disk full")
        synchronized(files) { files[name] = text }
    }
    override fun notify(channel: String, id: Int, title: String, body: String) { synchronized(notes) { notes.add(Note(channel, id, title, body, nowMs)) } }
    override fun asset(name: String): String? = assets[name]
    override fun log(msg: String) { synchronized(logs) { logs.add(msg) } }

    fun setTime(day: Int, minute: Int, sec: Int = 0) { nowMs = Clock.msAt(day, minute) + sec * 1000L }

    companion object {
        /** A platform with a mixed synthetic universe: n strong uptrends, plus fallers, flats, gappy, wild and broken symbols. */
        fun standard(nUp: Int = 16): FakePlatform {
            val p = FakePlatform()
            val y = p.yahoo
            val sb = StringBuilder()
            fun add(sym: String, name: String, pr: Profile?) {
                sb.append(sym).append(',').append(name).append('\n')
                if (pr != null) y.profiles[sym] = pr
            }
            for (k in 1..nUp) add("UP%02d".format(k), "Uptrend $k Ltd", Profile(0.25, 0.8, start = 150.0 + 20 * k))
            for (k in 1..5) add("DN%02d".format(k), "Faller $k Ltd", Profile(-0.25, 0.6, start = 900.0))
            for (k in 1..5) add("FL%02d".format(k), "Flat $k Ltd", Profile(0.0, 0.8))
            for (k in 1..4) add("GP%02d".format(k), "Gappy $k Ltd", Profile(0.08, 1.5, gap = 1.2))
            for (k in 1..4) add("HB%02d".format(k), "High Beta $k Ltd", Profile(0.15, 2.8))
            add("M&M", "Mahindra & Mahindra Ltd", Profile(0.15, 1.2))
            add("BAJAJ-AUTO", "Bajaj Auto Ltd", Profile(0.12, 1.1))
            add("PENNY", "Penny Stock Ltd", Profile(0.05, 2.0, start = 12.0))
            add("THIN", "Thinly Traded Ltd", Profile(0.2, 1.2, baseVol = 20_000.0))
            add("NULLY", "Null Holes Ltd", Profile(0.15, 1.2)); y.nully.add("NULLY")
            add("ERRSYM", "Delisted Ltd", null); y.erroring.add("ERRSYM")
            add("GARB", "Garbage Ltd", Profile(0.1, 1.0)); y.garbage.add("GARB")
            add("DEAD", "Dead Ltd", Profile(0.1, 1.0)); y.dead.add("DEAD")
            add("H2ONLY", "Fallback Host Ltd", Profile(0.15, 1.2)); y.host2Only.add("H2ONLY")
            add("NEWIPO", "New Listing Ltd", Profile(0.1, 1.5)); y.shortHistory["NEWIPO"] = 30
            y.profiles["^NSEI"] = Profile(0.06, 0.7, start = 15000.0, baseVol = 0.0)
            y.profiles["XTRA1"] = Profile(0.18, 1.0)   // a holding outside the universe
            p.assets["universe.csv"] = sb.toString()
            return p
        }
    }
}
