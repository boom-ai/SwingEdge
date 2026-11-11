package com.boomai.swingedge.core

/** Raw chart arrays from one Yahoo response. NaN marks a null/missing value. */
class Chart(
    val n: Int, val ts: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val v: DoubleArray,
    val metaPrice: Double, val metaTime: Long
)

/** Intraday view of a symbol: one aggregated bar per recent day plus the latest price. */
class Intraday(val days: Series, val ltp: Double, val lastTsMs: Long)

/** Yahoo Finance chart API: URL building and defensive parsing. Nothing here throws. */
object Yahoo {

    fun encode(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xFF)
            val c = ch.toChar()
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '.' || c == '_' || c == '~') sb.append(c)
            else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[ch shr 4])
                sb.append("0123456789ABCDEF"[ch and 15])
            }
        }
        return sb.toString()
    }

    /** NSE symbol -> Yahoo ticker ("^NSEI" stays an index, everything else gets ".NS"). */
    fun ticker(sym: String): String = if (sym.startsWith("^")) sym else "$sym.NS"

    fun url(host: String, sym: String, range: String, interval: String): String =
        host + "/v8/finance/chart/" + encode(ticker(sym)) + "?range=" + range + "&interval=" + interval

    fun dailyUrl(host: String, sym: String) = url(host, sym, "2y", "1d")
    fun intradayUrl(host: String, sym: String) = url(host, sym, "5d", "15m")

    private fun dbl(x: Any?): Double {
        val d = (x as? Number)?.toDouble() ?: return Double.NaN
        return if (d.isInfinite()) Double.NaN else d
    }

    /** Parses a chart response; null for garbage, chart.error, empty results or no usable bar. */
    fun parse(text: String?): Chart? {
        if (text == null || text.length < 20) return null
        try {
            val root = Json.parseObject(text) ?: return null
            val chart = root.obj("chart")
            if (chart["error"] != null) return null
            val res = chart.list("result").firstOrNull().asObj()
            if (res.isEmpty()) return null
            val tsL = res.list("timestamp")
            if (tsL.isEmpty()) return null
            val q = res.obj("indicators").list("quote").firstOrNull().asObj()
            val oL = q.list("open"); val hL = q.list("high"); val lL = q.list("low"); val cL = q.list("close"); val vL = q.list("volume")
            if (cL.isEmpty()) return null
            val n = tsL.size
            val ts = LongArray(n)
            val o = DoubleArray(n); val h = DoubleArray(n); val l = DoubleArray(n); val c = DoubleArray(n); val v = DoubleArray(n)
            var good = 0
            for (i in 0 until n) {
                val t = dbl(tsL[i])
                ts[i] = if (t.isNaN()) 0L else t.toLong()
                o[i] = dbl(oL.getOrNull(i)); h[i] = dbl(hL.getOrNull(i)); l[i] = dbl(lL.getOrNull(i)); c[i] = dbl(cL.getOrNull(i)); v[i] = dbl(vL.getOrNull(i))
                if (ts[i] > 0 && c[i] > 0) good++
            }
            if (good == 0) return null
            val meta = res.obj("meta")
            return Chart(n, ts, o, h, l, c, v, meta.num("regularMarketPrice", Double.NaN), meta.long("regularMarketTime", 0L))
        } catch (t: Throwable) {
            return null
        }
    }

    /**
     * Groups chart bars by IST day into one bar per day (open of the first, max high, min low, close of the last, summed volume).
     * Bars with a null close are skipped. Only days strictly before [beforeDay] are kept (pass Int.MAX_VALUE to keep all).
     */
    fun aggregate(sym: String, ch: Chart, beforeDay: Int): Series {
        val s = Series(sym, if (ch.n > 600) 600 else maxOf(ch.n, 8))
        var curDay = Int.MIN_VALUE
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0
        var lastTs = Long.MIN_VALUE
        for (i in 0 until ch.n) {
            val t = ch.ts[i]
            val cl = ch.c[i]
            if (t <= 0 || !(cl > 0) || t < lastTs) continue
            lastTs = t
            val day = Clock.epochDay(t * 1000L)
            val op = if (ch.o[i] > 0) ch.o[i] else cl
            var hi = if (ch.h[i] > 0) ch.h[i] else maxOf(op, cl)
            var lo = if (ch.l[i] > 0) ch.l[i] else minOf(op, cl)
            if (hi < maxOf(op, cl)) hi = maxOf(op, cl)
            if (lo > minOf(op, cl)) lo = minOf(op, cl)
            val vol = if (ch.v[i] > 0) ch.v[i] else 0.0
            if (day != curDay) {
                if (curDay != Int.MIN_VALUE && curDay < beforeDay) s.add(curDay, o, h, l, c, v)
                curDay = day; o = op; h = hi; l = lo; c = cl; v = vol
            } else {
                if (hi > h) h = hi
                if (lo < l) l = lo
                c = cl
                v += vol
            }
        }
        if (curDay != Int.MIN_VALUE && curDay < beforeDay) s.add(curDay, o, h, l, c, v)
        return s
    }

    /** Completed daily bars (every day before [today]) from a range=2y&interval=1d response, capped to the newest bars. */
    fun daily(sym: String, text: String?, today: Int): Series? {
        val ch = parse(text) ?: return null
        val s = aggregate(sym, ch, today)
        if (s.n == 0) return null
        return s.tail(Params.DAILY_BARS_CAP)
    }

    /** Per-day aggregates and latest price from a range=5d&interval=15m response. */
    fun intraday(sym: String, text: String?): Intraday? {
        val ch = parse(text) ?: return null
        val s = aggregate(sym, ch, Int.MAX_VALUE)
        if (s.n == 0) return null
        var lastTs = 0L
        for (i in 0 until ch.n) if (ch.c[i] > 0 && ch.ts[i] > lastTs) lastTs = ch.ts[i]
        val i = s.n - 1
        // the quote in meta can be fresher than the last 15-minute bar; fold it into the last day if it is the same day
        if (ch.metaPrice > 0 && ch.metaTime >= lastTs && Clock.epochDay(ch.metaTime * 1000L) == s.d[i]) {
            s.c[i] = ch.metaPrice
            if (ch.metaPrice > s.h[i]) s.h[i] = ch.metaPrice
            if (ch.metaPrice < s.l[i]) s.l[i] = ch.metaPrice
            lastTs = ch.metaTime
        }
        return Intraday(s, s.c[i], lastTs * 1000L)
    }
}

/**
 * File-backed cache of completed daily bars, split over a few shard files. Loaded lazily, written only when changed.
 * Thread-safe: the map is guarded by this object's monitor (short holds only); [flush] builds the shard texts under
 * that monitor but writes the files outside it, serialised by a separate lock so a newer shard is never overwritten
 * by an older one. Series objects stored here are never modified afterwards.
 */
class DailyCache(private val p: Platform) {
    private class Entry(val series: Series, val key: Int)

    private val map = HashMap<String, Entry>()
    @Volatile private var loaded = false
    private val dirty = BooleanArray(64)
    private val flushLock = Any()

    private fun shardOf(sym: String): Int = Math.floorMod(sym.hashCode(), Params.CACHE_SHARDS.coerceIn(1, 64))
    private fun fileName(k: Int) = "daily_cache_$k.json"

    /** True once the shard files have been read (cheap check that never touches storage). */
    fun isLoaded(): Boolean = loaded

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        for (k in 0 until Params.CACHE_SHARDS.coerceIn(1, 64)) {
            try {
                val m = Json.parseObject(p.read(fileName(k))) ?: continue
                for ((sym, v) in m.obj("s")) {
                    val e = v.asObj()
                    val s = Series.decode(sym, e.str("b"))
                    map[sym] = Entry(s, e.int("k"))
                }
            } catch (t: Throwable) {
                try { p.log("cache shard $k unreadable: $t") } catch (x: Throwable) {}
            }
        }
    }

    @Synchronized
    fun get(sym: String): Series? {
        ensureLoaded()
        val s = map[sym]?.series
        return if (s != null && s.n > 0) s else null
    }

    /** Day key under which the symbol's history was last fetched (0 if never). */
    @Synchronized
    fun keyOf(sym: String): Int {
        ensureLoaded()
        return map[sym]?.key ?: 0
    }

    @Synchronized
    fun put(sym: String, s: Series, key: Int) {
        ensureLoaded()
        map[sym] = Entry(s, key)
        dirty[shardOf(sym)] = true
    }

    @Synchronized
    fun size(): Int {
        ensureLoaded()
        return map.size
    }

    /** Drops symbols that are no longer needed (keeps the files small). */
    @Synchronized
    fun retain(keep: Set<String>) {
        ensureLoaded()
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (!keep.contains(k)) {
                it.remove()
                dirty[shardOf(k)] = true
            }
        }
    }

    fun flush() {
        if (!loaded) return
        synchronized(flushLock) {
            val shards = Params.CACHE_SHARDS.coerceIn(1, 64)
            val texts = ArrayList<Pair<Int, String>>()
            synchronized(this) {
                for (k in 0 until shards) {
                    if (!dirty[k]) continue
                    try {
                        val sb = StringBuilder(1 shl 16)
                        sb.append("{\"v\":1,\"s\":{")
                        var first = true
                        for ((sym, e) in map) {
                            if (shardOf(sym) != k) continue
                            if (!first) sb.append(',')
                            first = false
                            Json.quote(sb, sym)
                            sb.append(":{\"k\":").append(e.key).append(",\"b\":\"").append(e.series.encode()).append("\"}")
                        }
                        sb.append("}}")
                        texts.add(Pair(k, sb.toString()))
                        dirty[k] = false
                    } catch (t: Throwable) {
                        try { p.log("cache shard $k write failed: $t") } catch (x: Throwable) {}
                    }
                }
            }
            for ((k, text) in texts) {
                try {
                    p.write(fileName(k), text)
                } catch (t: Throwable) {
                    synchronized(this) { dirty[k] = true }
                    try { p.log("cache shard $k write failed: $t") } catch (x: Throwable) {}
                }
            }
        }
    }
}
