package com.boomai.swingedge.core

/**
 * Real-time whole-market scan (market hours only). The nightly data/scan.csv gives every stock its reference levels
 * ([LiveRef]: close, SMA50, 52-week high/low, 20-day turnover, ATR plan levels, label / rank); live quotes are
 * re-evaluated against them for intraday events ([LiveScan.detect]).
 *
 * Sources, in order of preference ([LiveEngine.plan]):
 *  1. TradingView screener (POST scanner.tradingview.com/india/scan): every NSE + BSE stock in ONE call (~8,000 listed;
 *     volume > 0 rows, 10 columns, gzip), every 2 min in the foreground / 5 min in the background. Free data is 15 minutes
 *     delayed (update_mode delayed_streaming_900): its events say so, and its rows never overwrite a live Groww / broker
 *     quote of the same session.
 *  2. m.Stock batch quotes when the broker is connected: the hot list (user stocks + top-ranked + liquid), <= 200 a call.
 *  3. Groww batch (80 symbols a call, ~1 s fresh): the hot list + TO20-liquid stocks in rotation while TradingView is
 *     healthy; the whole NSE list in rotation (hot -> liquid -> rest, ~45 calls a sweep) when it is not. BSE-only stocks
 *     are tried through Groww's "BSE" key with scrip codes; it switches itself off for the day after 3 empty replies.
 * Budget: never more than one call per [LiveScan.FG_GAP_MS] (1 s) in the foreground, [LiveScan.BG_GAP_MS] in the background.
 *
 * Live score ([LiveScan.liveComp]): of the five ranked components only PROX52 (close / 52-week high) and LOW52
 * (close / 52-week low) depend on today's price; RAM, R6_1 and R12_1 use closes lagged >= 21 sessions (and a 126-day
 * return volatility) that one live bar cannot recompute without full history, so they keep their nightly values. The live
 * table re-ranks the whole cross-section with live PROX52 / LOW52 (52-week high / low extended by today's range) and the
 * live close for the BUY test (close > SMA50, upper-circuit lock). With ltp == the nightly close it reproduces the nightly
 * table exactly.
 *
 * Pure Kotlin: the network is injected, so everything here is unit-tested. Never throws from public calls.
 */

/** One live quote. NaN = not supplied by the source. [src]: tv | groww | broker. */
class LiveQuote(
    val sym: String, val ltp: Double, val open: Double, val high: Double, val low: Double, val prev: Double,
    val volume: Double, val tsMs: Long, val src: String,
    val upper: Double = Double.NaN, val lower: Double = Double.NaN,
    /** day change % as sent by the source (NaN -> derived from prev) */
    val chgPct0: Double = Double.NaN,
    /** average daily volume in shares (TradingView), for the pace when the scan has no turnover */
    val avgVol: Double = Double.NaN,
    /** TradingView relative volume (today vs 10-day average at this time of day), NaN when absent */
    val relVol: Double = Double.NaN,
    val at: Long = 0L
) {
    val chgPct: Double get() = if (!chgPct0.isNaN()) chgPct0 else if (prev > 0) (ltp / prev - 1) * 100 else Double.NaN
    /** keeps the fields the newer quote lacks (a broker quote has no volume) */
    fun mergedOver(old: LiveQuote?): LiveQuote {
        if (old == null) return this
        fun pick(a: Double, b: Double) = if (a.isNaN() || (a <= 0 && b > 0)) b else a
        return LiveQuote(sym, ltp, pick(open, old.open), Math.max(pick(high, old.high), ltp), if (pick(low, old.low) > 0) Math.min(pick(low, old.low), ltp) else ltp,
            pick(prev, old.prev), if (volume.isNaN() || volume < old.volume) pick(volume, old.volume).let { if (!old.volume.isNaN() && (it.isNaN() || it < old.volume)) old.volume else it } else volume,
            tsMs, src, pick(upper, old.upper), pick(lower, old.lower), chgPct0, pick(avgVol, old.avgVol), relVol, at)
    }
}

/** Nightly reference levels of one stock (from scan.csv). */
class LiveRef(
    val sym: String, val exch: String, val code: String, val name: String,
    val close: Double, val sma50: Double, val high: Double, val hi252: Double, val lo252: Double,
    /** mean daily turnover of the last 20 sessions, Rs crore */
    val to20: Double,
    val entryLow: Double, val entryHigh: Double, val stop: Double, val t1: Double, val t2: Double,
    val label: String, val score: Double, val rank: Int,
    val comp: ScoreComp? = null, val inTable: Boolean = false
) {
    val liquid: Boolean get() = to20 >= SetupScore.MIN_TO20_CR
}

class LiveEvent(val at: Long, val sym: String, val kind: String, val price: Double, val pct: Double, val text: String, val rel: Boolean, val pace: Double = Double.NaN) {
    fun toMap(name: String? = null): Map<String, Any?> = linkedMapOf("at" to at, "sym" to sym, "name" to name, "kind" to kind, "label" to LiveScan.LABEL[kind],
        "price" to Fmt.r2(price), "pct" to (if (pct.isNaN()) null else Fmt.r2(pct)), "text" to text, "rel" to rel, "pace" to (if (pace.isNaN()) null else Fmt.r1(pace)))
    companion object {
        fun from(m: Map<*, *>): LiveEvent? = try {
            LiveEvent((m["at"] as Number).toLong(), m["sym"] as String, m["kind"] as String, (m["price"] as? Number)?.toDouble() ?: 0.0,
                (m["pct"] as? Number)?.toDouble() ?: Double.NaN, m["text"] as? String ?: "", m["rel"] == true, (m["pace"] as? Number)?.toDouble() ?: Double.NaN)
        } catch (t: Throwable) { null }
    }
}

/** One planned network call. */
class LivePlan(val src: String, val syms: List<String>)

/** Wrap-around cursor over a list (the cold rotation). */
class Rotation(list: List<String> = emptyList()) {
    var list: List<String> = list; private set
    var pos = 0; private set
    /** completed passes over the list */
    var laps = 0; private set
    /** Replaces the list, keeping the cursor on the same symbol when it is still there. */
    fun reset(l: List<String>) {
        val cur = list.getOrNull(pos)
        list = l
        pos = if (cur != null) l.indexOf(cur).coerceAtLeast(0) else 0
        if (pos >= l.size) pos = 0
    }
    /** The next [n] symbols (fewer when the list is shorter); wraps, counting a lap. */
    fun next(n: Int): List<String> {
        if (list.isEmpty() || n <= 0) return emptyList()
        val out = ArrayList<String>(Math.min(n, list.size))
        while (out.size < n && out.size < list.size) {
            out.add(list[pos]); pos++
            if (pos >= list.size) { pos = 0; laps++ }
        }
        return out
    }
}

object LiveScan {
    const val FILE = "live_scan.json"
    const val TV_URL = "https://scanner.tradingview.com/india/scan"
    val TV_HEADERS = mapOf("Content-Type" to "application/json", "Origin" to "https://in.tradingview.com", "Referer" to "https://in.tradingview.com/", "Accept" to "application/json")
    /** Columns probed live from GitHub Actions (2026-11-27): name, close, change (%), volume, open, high, low. */
    val TV_CORE = listOf("name", "close", "change", "volume", "open", "high", "low")
    /** Also verified; a reply that rejects them is retried with [TV_CORE] only (columns may be renamed). update_mode = "delayed_streaming_900". */
    val TV_EXTRA = listOf("average_volume_30d_calc", "relative_volume_10d_calc", "update_mode")
    /** free TradingView India data is 15 minutes delayed (update_mode delayed_streaming_900) until a reply says otherwise */
    const val TV_DEFAULT_DELAY_MS = 15 * 60_000L
    const val TV_RANGE = 6000

    const val GROWW_BATCH = 80
    const val BROKER_BATCH = 200
    const val HOT_MAX = 240
    const val FG_GAP_MS = 1_000L
    const val BG_GAP_MS = 4_000L
    const val TV_FG_MS = 120_000L
    const val TV_BG_MS = 300_000L
    /** with TradingView healthy, Groww / broker refresh the hot + liquid list one batch per this gap */
    const val HOT_FG_MS = 2_000L
    const val HOT_BG_MS = 10_000L
    /** TradingView counts as healthy for this long after a good reply (>= [TV_MIN_ROWS] rows) */
    const val TV_HEALTHY_MS = 420_000L
    const val TV_MIN_ROWS = 300
    /** after a failure TradingView is retried after this long (doubles up to 15 min) */
    const val TV_RETRY_MS = 120_000L
    const val SCREEN_OFF_PAUSE_MS = 10 * 60_000L
    const val UI_ACTIVE_MS = 15_000L
    /** a quote older than this does not count as swept / does not drive the live score */
    const val FRESH_MS = 5 * 60_000L

    // event thresholds
    const val GAP_PCT = 3.0
    const val BREAKOUT_PACE = 1.5
    const val SHOCK_PACE = 2.5
    /** volume shock needs at least this turnover today (Rs crore) and a session at least 15 minutes old */
    const val SHOCK_MIN_CR = 1.0
    const val BIG_MOVE_PCT = 5.0
    const val CIRCUIT_NEAR = 0.005
    val BANDS = doubleArrayOf(5.0, 10.0, 20.0)

    // notification throttle
    const val NOTE_SYM_MS = 30 * 60_000L
    const val NOTE_WINDOW_MS = 10 * 60_000L
    const val NOTE_WINDOW_MAX = 4
    const val RING_MAX = 300

    val LABEL = mapOf("ZONE" to "In buy zone", "BREAKOUT" to "52-week high breakout", "STOP" to "Stop hit", "TARGET" to "Target 1 hit", "TARGET2" to "Target 2 hit",
        "GAP_UP" to "Gap up", "GAP_DOWN" to "Gap down", "VOL_SHOCK" to "Volume shock", "UC_NEAR" to "Near upper circuit", "LC_NEAR" to "Near lower circuit",
        "BIG_UP" to "Up 5%+", "BIG_DOWN" to "Down 5%+")
    /** kinds that may notify a relevant stock; the rest only go to the in-app list */
    val NOTIFY_KINDS = setOf("ZONE", "BREAKOUT", "STOP", "TARGET", "TARGET2", "GAP_DOWN", "VOL_SHOCK", "UC_NEAR", "LC_NEAR")

    /**
     * Expected share of a day's volume traded by minute-of-day [m] (NSE U-shape: heavy first 30 minutes and last hour).
     * Piecewise-linear over (minutes since 09:15 -> cumulative share); floored at 4% so the first minutes do not explode.
     */
    private val PROFILE = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(15.0, 0.12), doubleArrayOf(45.0, 0.22), doubleArrayOf(105.0, 0.36),
        doubleArrayOf(165.0, 0.47), doubleArrayOf(225.0, 0.57), doubleArrayOf(285.0, 0.69), doubleArrayOf(345.0, 0.86), doubleArrayOf(375.0, 1.0))
    fun dayShare(m: Int): Double {
        val x = (m - Clock.OPEN_MIN).toDouble()
        if (x >= 375) return 1.0
        var f = 0.0
        for (k in 1 until PROFILE.size) if (x <= PROFILE[k][0]) { val a = PROFILE[k - 1]; val b = PROFILE[k]; f = a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]); break }
        return Math.max(0.04, f)
    }

    /** Volume pace: today's volume vs the volume a typical day has traded by now (1.0 = normal). NaN when unknown. */
    fun pace(r: LiveRef, q: LiveQuote, m: Int): Double {
        if (q.volume.isNaN() || q.volume < 0 || !(q.ltp > 0)) return Double.NaN
        val share = dayShare(m)
        // turnover is split-invariant (adjusted close x adjusted volume), so it is compared with today's volume x price
        if (r.to20 > 0) return (q.volume * q.ltp / 1e7) / (r.to20 * share)
        if (q.avgVol > 0) return q.volume / (q.avgVol * share)
        if (q.relVol > 0) return q.relVol
        return Double.NaN
    }

    /** Circuit band the move is pressing against: +band / -band, or 0. Uses the exchange limits when the source sent them. */
    fun circuit(q: LiveQuote): Double {
        val l = q.ltp; if (!(l > 0)) return 0.0
        if (q.upper > 0 && l >= q.upper * (1 - CIRCUIT_NEAR)) return Fmt.r2((q.upper / (if (q.prev > 0) q.prev else l) - 1) * 100)
        if (q.lower > 0 && l <= q.lower * (1 + CIRCUIT_NEAR)) return Fmt.r2((q.lower / (if (q.prev > 0) q.prev else l) - 1) * 100)
        if (q.upper > 0 || q.lower > 0 || !(q.prev > 0)) return 0.0
        val pct = (l / q.prev - 1) * 100
        for (b in BANDS) {
            if (pct >= b - 0.5 && pct <= b + 0.05 && (q.high.isNaN() || l >= q.high * 0.999)) return b
            if (pct <= -b + 0.5 && pct >= -b - 0.05 && (q.low.isNaN() || q.low <= 0 || l <= q.low * 1.001)) return -b
        }
        return 0.0
    }

    /** Event kinds this quote shows for this stock right now (each is reported once a day by the engine). */
    fun detect(r: LiveRef, q: LiveQuote, m: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val l = q.ltp
        if (!(l > 0) || m < Clock.OPEN_MIN) return out
        val pc = q.chgPct
        val pace = pace(r, q, m)
        fun rp(x: Double) = "₹" + Fmt.r2(x)
        if (r.label == "BUY" && r.entryLow > 0 && r.entryHigh > 0 && l >= r.entryLow && l <= r.entryHigh && (r.sma50.isNaN() || l > r.sma50))
            out.add("ZONE" to "In the buy zone ${rp(r.entryLow)}–${rp(r.entryHigh)}")
        val hi = if (r.hi252 > 0) Math.max(r.hi252, if (r.high > 0) r.high else 0.0) else Double.NaN
        if (!hi.isNaN() && l > hi && !(pace < BREAKOUT_PACE))
            out.add("BREAKOUT" to "New 52-week high above ${rp(hi)}" + (if (pace.isNaN()) "" else ", volume ${Fmt.r1(pace)}× normal"))
        if (r.stop > 0 && l <= r.stop) out.add("STOP" to "At or below the plan stop ${rp(r.stop)}")
        if (r.t2 > 0 && l >= r.t2) out.add("TARGET2" to "Reached target 2 ${rp(r.t2)}")
        else if (r.t1 > 0 && l >= r.t1) out.add("TARGET" to "Reached target 1 ${rp(r.t1)}")
        val prev = if (q.prev > 0) q.prev else r.close
        if (q.open > 0 && prev > 0) {
            val g = (q.open / prev - 1) * 100
            if (g >= GAP_PCT) out.add("GAP_UP" to "Opened ${Fmt.r1(g)}% above the last close")
            if (g <= -GAP_PCT) out.add("GAP_DOWN" to "Opened ${Fmt.r1(-g)}% below the last close")
        }
        if (!pace.isNaN() && pace >= SHOCK_PACE && m >= Clock.OPEN_MIN + 15 && q.volume * l / 1e7 >= SHOCK_MIN_CR)
            out.add("VOL_SHOCK" to "Volume ${Fmt.r1(pace)}× a normal day by now")
        val c = circuit(q)
        if (c > 0) out.add("UC_NEAR" to "Pressing the upper circuit (~${Fmt.r1(c)}%)")
        if (c < 0) out.add("LC_NEAR" to "Pressing the lower circuit (~${Fmt.r1(-c)}%)")
        if (!pc.isNaN() && pc >= BIG_MOVE_PCT) out.add("BIG_UP" to "Up ${Fmt.r1(pc)}% today")
        if (!pc.isNaN() && pc <= -BIG_MOVE_PCT) out.add("BIG_DOWN" to "Down ${Fmt.r1(-pc)}% today")
        if (q.src == "tv") return out.map { Pair(it.first, it.second + " (15-min delayed data)") }
        return out
    }

    /** The nightly components with today's live price (see the file comment for what can and cannot move). */
    fun liveComp(c: ScoreComp, q: LiveQuote): ScoreComp {
        val l = q.ltp
        if (!(l > 0) || !(c.close > 0)) return c
        val dh = if (q.high > 0) Math.max(q.high, l) else l
        val dl = if (q.low > 0) Math.min(q.low, l) else l
        val hi = if (c.hi252.isNaN()) Double.NaN else Math.max(c.hi252, dh)
        val lo = if (c.lo252.isNaN()) Double.NaN else Math.min(c.lo252, dl)
        val raw = if (c.rawClose > 0) l * (c.rawClose / c.close) else l
        val r1 = l / c.close - 1
        val uc = l >= dh * 0.9995 && minOf(Math.abs(r1 - 0.05), Math.abs(r1 - 0.10), Math.abs(r1 - 0.20)) < 0.003
        fun n(x: Double) = if (x.isNaN() || x.isInfinite()) Double.NaN else x + 0.0
        if (l == c.close && dh <= c.high && (c.lo252.isNaN() || dl >= c.lo252)) return c
        return ScoreComp(c.nbars, c.day, l, c.close, dh, raw, c.to20, c.sma50, c.atr14, c.r6, c.r12, c.vol126, c.ram,
            hi, lo, if (hi.isNaN()) Double.NaN else n(l / hi), if (lo.isNaN()) Double.NaN else n(l / lo), uc, sma200 = c.sma200)
    }

    // ------------------------------------------------------------------ sources

    private fun num(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull() ?: Double.NaN; else -> Double.NaN }.let { if (it.isInfinite()) Double.NaN else it }

    fun growwBody(syms: List<String>, exch: String = "NSE") = Json.stringify(mapOf("exchangeAggReqMap" to mapOf(exch to mapOf("priceSymbolList" to syms, "indexSymbolList" to emptyList<String>()))))

    /** Groww batch reply -> quotes keyed by the symbol asked for ([alias] maps a BSE scrip code back to the app symbol). */
    fun parseGroww(text: String?, exch: String = "NSE", now: Long = 0L, alias: Map<String, String> = emptyMap()): Map<String, LiveQuote> = try {
        val m = (((Json.parseObject(text ?: "")?.get("exchangeAggRespMap") as? Map<*, *>)?.get(exch) as? Map<*, *>)?.get("priceLivePointsMap")) as? Map<*, *> ?: emptyMap<Any, Any>()
        val out = HashMap<String, LiveQuote>()
        for ((k, v) in m) {
            val r = v as? Map<*, *> ?: continue
            val l = num(r["ltp"]).let { if (it.isNaN()) num(r["value"]) else it }
            if (!(l > 0)) continue
            val sym = alias[k.toString()] ?: k.toString()
            var ts = num(r["tsInMillis"]); if (ts > 0 && ts < 1e11) ts *= 1000
            // circuit limits: field names need a live probe; any of these is used when present
            val up = listOf("upperCircuitLimit", "highPriceRange", "upperCircuit", "highPriceBand").map { num(r[it]) }.firstOrNull { it > 0 } ?: Double.NaN
            val dn = listOf("lowerCircuitLimit", "lowPriceRange", "lowerCircuit", "lowPriceBand").map { num(r[it]) }.firstOrNull { it > 0 } ?: Double.NaN
            out[sym] = LiveQuote(sym, l, num(r["open"]), num(r["high"]), num(r["low"]), num(r["close"]), num(r["volume"]).let { if (it.isNaN()) num(r["totalTradedVolume"]) else it },
                if (ts > 0) ts.toLong() else now, "groww", up, dn, num(r["dayChangePerc"]), at = now)
        }
        out
    } catch (t: Throwable) { emptyMap() }

    fun tvBody(cols: List<String>, range: Int = TV_RANGE): String = Json.stringify(linkedMapOf(
        "filter" to listOf(linkedMapOf("left" to "type", "operation" to "equal", "right" to "stock"), linkedMapOf("left" to "volume", "operation" to "greater", "right" to 0)),
        "options" to mapOf("lang" to "en"), "markets" to listOf("india"),
        "symbols" to linkedMapOf("query" to mapOf("types" to emptyList<String>()), "tickers" to emptyList<String>()),
        "columns" to cols, "sort" to linkedMapOf("sortBy" to "volume", "sortOrder" to "desc"), "range" to listOf(0, range)))

    /**
     * TradingView reply -> quotes keyed by app symbol. Rows are {"s":"NSE:RELIANCE","d":[values in [cols] order]}.
     * NSE rows map by symbol (TradingView writes '&' as '_', e.g. NSE:M_M); BSE rows map through [bseIds] (BSE ticker or
     * scrip code -> app symbol) and only fill stocks no NSE row gave. Null when the reply is not a screener answer.
     */
    fun parseTv(text: String?, cols: List<String>, now: Long, bseIds: Map<String, String> = emptyMap(), delayMs: Long = 0L): Map<String, LiveQuote>? = try {
        val o = Json.parseObject(text ?: "")
        val data = o?.get("data") as? List<*>
        if (o == null || data == null) null else {
            val ix = HashMap<String, Int>(); for ((k, c) in cols.withIndex()) ix[c] = k
            val nse = HashMap<String, LiveQuote>(); val bse = HashMap<String, LiveQuote>()
            for (row in data) {
                val r = row as? Map<*, *> ?: continue
                val s = r["s"] as? String ?: continue
                val d = r["d"] as? List<*> ?: continue
                fun v(c: String): Double = ix[c]?.let { num(d.getOrNull(it)) } ?: Double.NaN
                val ex = s.substringBefore(':', ""); val tk = s.substringAfter(':')
                val close = v("close"); if (!(close > 0)) continue
                val chg = v("change")
                val prev = if (!chg.isNaN() && chg > -100) close / (1 + chg / 100) else Double.NaN
                val mk = { sym: String -> LiveQuote(sym, close, v("open"), v("high"), v("low"), prev, v("volume"), now - delayMs, "tv", chgPct0 = chg, avgVol = v("average_volume_30d_calc"), relVol = v("relative_volume_10d_calc"), at = now) }
                when (ex) {
                    "NSE" -> { val sym = tk.replace('_', '&').uppercase(); nse[sym] = mk(sym) }
                    "BSE" -> { val sym = bseIds[tk.uppercase()] ?: continue; if (!bse.containsKey(sym)) bse[sym] = mk(sym) }
                }
            }
            for ((k, q) in bse) if (!nse.containsKey(k)) nse[k] = q
            nse
        }
    } catch (t: Throwable) { null }

    /** "delayed_streaming_900" -> 900 000 ms; 0 when real-time or unknown. */
    fun tvDelayMs(text: String?, cols: List<String>): Long = try {
        val k = cols.indexOf("update_mode")
        if (k < 0) TV_DEFAULT_DELAY_MS else {
            val first = ((Json.parseObject(text ?: "")?.get("data") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("d") as? List<*>
            val m = first?.getOrNull(k) as? String ?: ""
            if (m.startsWith("delayed")) (m.substringAfterLast('_').toLongOrNull() ?: 0L) * 1000 else 0L
        }
    } catch (t: Throwable) { 0L }

    /** Screen / app state -> 2 foreground, 1 background, 0 paused. */
    fun mode(now: Long, uiActiveAt: Long, screenOn: Boolean, screenOffSince: Long, bgEnabled: Boolean): Int = when {
        now - uiActiveAt <= UI_ACTIVE_MS -> 2
        screenOn -> 1
        bgEnabled -> 1
        screenOffSince > 0 && now - screenOffSince <= SCREEN_OFF_PAUSE_MS -> 1
        else -> 0
    }
}

/**
 * The live-scan state machine. [plan] picks the next call (or null when the budget says wait), the caller runs it, then
 * [ingest] stores the quotes and records new events. Not thread-safe: SwingApp guards it with one monitor and keeps the
 * network call outside it.
 */
class LiveEngine {
    var refs: Map<String, LiveRef> = emptyMap(); private set
    /** BSE ticker / scrip code -> app symbol, for TradingView BSE rows and Groww's BSE key */
    private var bseIds: Map<String, String> = emptyMap()
    private var bseCodes: Map<String, String> = emptyMap()
    var refsDay = 0; private set
    val quotes = HashMap<String, LiveQuote>()
    val ring = ArrayDeque<LiveEvent>()
    /** "SYM|KIND" -> epoch day it last fired (each kind once a day per stock) */
    val fired = HashMap<String, Int>()
    val noteAt = HashMap<String, Long>()
    val noteTimes = ArrayDeque<Long>()
    var relevant: Set<String> = emptySet()
    var top: Set<String> = emptySet()

    private var hot: List<String> = emptyList()
    val rot = Rotation()
    /** hot + liquid NSE stocks (the Groww walk while TradingView is healthy) */
    val rotLiquid = Rotation()
    private val bseRot = Rotation()
    var lastCallAt = 0L; private set
    var tvTryAt = 0L; private set
    var tvOkAt = 0L; private set
    var tvRows = 0; private set
    var tvFails = 0; private set
    var tvMinimal = false; private set
    var tvDelay = LiveScan.TV_DEFAULT_DELAY_MS; private set
    private var tvNextAt = 0L
    var bseEmpty = 0; private set
    private var bseOffDay = -1
    var calls = 0L; private set
    val callTimes = ArrayDeque<Long>()
    var bytes = 0L; private set
    var sweepStartAt = 0L; private set
    var lastSweepMs = 0L; private set
    private var lapsSeen = 0
    var day = 0; private set
    var liveTable: ScoreTable? = null; private set
    private var liveTableAt = 0L
    var lastIngestAt = 0L; private set

    /** Installs the nightly references; [relevant] = the user's stocks; [top] = top-ranked ideas. Rebuilds the rotation. */
    fun setRefs(list: List<LiveRef>, refDay: Int, relevant: Set<String>, top: Set<String>) {
        if (refDay != refsDay || list.size != refs.size) {
            val m = HashMap<String, LiveRef>(list.size * 2); for (r in list) m[r.sym] = r
            refs = m; refsDay = refDay
            val bi = HashMap<String, String>(); val bc = HashMap<String, String>()
            for (r in list) if (r.exch == Bhav.BSE) { bi[r.sym] = r.sym; if (r.code.isNotEmpty()) { bi[r.code] = r.sym; bc[r.sym] = r.code } }
            bseIds = bi; bseCodes = bc
            liveTable = null
        }
        this.relevant = relevant; this.top = top
        val nse = refs.values.filter { it.exch != Bhav.BSE }
        val rel = (relevant + top).filter { refs[it]?.exch != Bhav.BSE }.sorted()
        val liquid = nse.filter { it.liquid }.sortedByDescending { it.to20 }.map { it.sym }
        val h = LinkedHashSet<String>(); h.addAll(rel)
        for (s in liquid) { if (h.size >= LiveScan.HOT_MAX) break; h.add(s) }
        hot = h.toList()
        val all = LinkedHashSet<String>(); all.addAll(hot); all.addAll(liquid)
        nse.map { it.sym }.sorted().forEach { all.add(it) }
        rot.reset(all.toList())
        rotLiquid.reset(LinkedHashSet<String>().also { it.addAll(hot); it.addAll(liquid) }.toList())
        bseRot.reset(refs.values.filter { it.exch == Bhav.BSE && it.code.isNotEmpty() }.map { it.sym }.sorted())
    }

    val hotList: List<String> get() = hot
    fun tvHealthy(now: Long) = tvOkAt > 0 && now - tvOkAt <= LiveScan.TV_HEALTHY_MS
    /** the rotation Groww walks: hot + liquid while TradingView covers the rest, every NSE stock otherwise */
    fun activeRot(now: Long): Rotation = if (tvHealthy(now)) rotLiquid else rot

    /**
     * Next call or null (wait). [fg] = foreground pacing; [broker] = a connected broker can be asked; [tv] = TradingView allowed.
     */
    fun plan(now: Long, fg: Boolean, broker: Boolean, tv: Boolean = true): LivePlan? {
        if (refs.isEmpty()) return null
        if (now - lastCallAt < (if (fg) LiveScan.FG_GAP_MS else LiveScan.BG_GAP_MS)) return null
        if (tv && now >= tvNextAt && now - tvTryAt >= (if (fg) LiveScan.TV_FG_MS else LiveScan.TV_BG_MS)) return LivePlan("tv", emptyList())
        if (broker && hot.isNotEmpty() && now - brokerAt >= (if (fg) 5_000L else 20_000L)) return LivePlan("broker", hot.take(LiveScan.BROKER_BATCH))
        val healthy = tvHealthy(now)
        if (healthy && now - growwAt < (if (fg) LiveScan.HOT_FG_MS else LiveScan.HOT_BG_MS)) return null
        if (!healthy && bseOffDay != Clock.epochDay(now) && bseRot.list.isNotEmpty() && growwCalls % 10 == 9L) {
            val b = bseRot.next(LiveScan.GROWW_BATCH); if (b.isNotEmpty()) return LivePlan("growwBse", b)
        }
        val r = activeRot(now)
        val out = r.next(LiveScan.GROWW_BATCH)
        return if (out.isEmpty()) null else LivePlan("groww", out)
    }
    private var brokerAt = 0L
    private var growwAt = 0L
    private var growwCalls = 0L
    /** BSE scrip code for a BSE-only app symbol (Groww's BSE key) */
    fun bseCode(sym: String): String? = bseCodes[sym]

    /** Records a finished call ([got] = null on failure) and returns the new events. [m] minute of day IST. */
    fun ingest(plan: LivePlan, got: Map<String, LiveQuote>?, now: Long, size: Int = 0): List<LiveEvent> {
        lastCallAt = now; calls++; bytes += size
        callTimes.addLast(now); while (callTimes.isNotEmpty() && now - callTimes.first() > 60_000L) callTimes.removeFirst()
        when (plan.src) {
            "tv" -> {
                tvTryAt = now
                if (got != null && got.size >= LiveScan.TV_MIN_ROWS) { tvOkAt = now; tvRows = got.size; tvFails = 0; tvNextAt = 0L }
                else { tvFails++; tvNextAt = now + Math.min(15 * 60_000L, LiveScan.TV_RETRY_MS shl Math.min(3, tvFails - 1)) }
            }
            "broker" -> brokerAt = now
            "growwBse" -> { growwAt = now; growwCalls++; if (got.isNullOrEmpty()) { bseEmpty++; if (bseEmpty >= 3) bseOffDay = Clock.epochDay(now) } else bseEmpty = 0 }
            else -> { growwAt = now; growwCalls++ }
        }
        val laps = rot.laps + rotLiquid.laps
        if (laps != lapsSeen) { lapsSeen = laps; if (sweepStartAt > 0) lastSweepMs = now - sweepStartAt; sweepStartAt = now }
        if (sweepStartAt == 0L) sweepStartAt = now
        if (got.isNullOrEmpty()) return emptyList()
        lastIngestAt = now
        val d = Clock.epochDay(now); val m = Clock.minuteOfDay(now)
        if (d != day) { day = d; fired.entries.removeAll { it.value != d } }
        val out = ArrayList<LiveEvent>()
        for ((sym, q0) in got) {
            val r = refs[sym] ?: continue
            val old = quotes[sym]
            val q = if (old != null && Clock.epochDay(old.at) == d) q0.mergedOver(old) else q0
            // a delayed whole-market row never overwrites a live quote of the same session (it is older by construction)
            if (old != null && q0.src == "tv" && old.src != "tv" && Clock.epochDay(old.at) == d && old.tsMs >= q0.tsMs) continue
            quotes[sym] = q
            for ((kind, text) in LiveScan.detect(r, q, m)) {
                val key = "$sym|$kind"
                if (fired[key] == d) continue
                fired[key] = d
                val e = LiveEvent(now, sym, kind, q.ltp, q.chgPct, text, sym in relevant || sym in top, LiveScan.pace(r, q, m))
                out.add(e)
                ring.addFirst(e)
            }
        }
        while (ring.size > LiveScan.RING_MAX) ring.removeLast()
        return out
    }

    /** Which of [ev] to push as phone notifications: relevant stocks, notifying kinds, per-stock and global throttles. */
    fun toNotify(ev: List<LiveEvent>, now: Long): List<LiveEvent> {
        while (noteTimes.isNotEmpty() && now - noteTimes.first() > LiveScan.NOTE_WINDOW_MS) noteTimes.removeFirst()
        val out = ArrayList<LiveEvent>()
        for (e in ev.sortedBy { if (e0(it.kind)) 0 else 1 }) {
            if (!e.rel || e.kind !in LiveScan.NOTIFY_KINDS) continue
            if (noteTimes.size >= LiveScan.NOTE_WINDOW_MAX) break
            val last = noteAt[e.sym]
            if (last != null && now - last < LiveScan.NOTE_SYM_MS) continue
            noteAt[e.sym] = now; noteTimes.addLast(now); out.add(e)
        }
        return out
    }
    private fun e0(k: String) = k == "STOP" || k == "TARGET" || k == "TARGET2"

    fun fresh(now: Long): Int = quotes.values.count { now - it.at <= LiveScan.FRESH_MS }

    /** Live score table (cached [maxAgeMs]): the nightly cross-section with live PROX52 / LOW52 / close. */
    fun table(now: Long, maxAgeMs: Long = 20_000L): ScoreTable? {
        liveTable?.let { if (now - liveTableAt < maxAgeMs) return it }
        val items = ArrayList<Pair<String, ScoreComp>>()
        for (r in refs.values) {
            if (!r.inTable) continue
            val c = r.comp ?: continue
            val q = quotes[r.sym]
            items.add(Pair(r.sym, if (q != null && now - q.at <= LiveScan.FRESH_MS) LiveScan.liveComp(c, q) else c))
        }
        if (items.isEmpty()) return null
        val t = SetupScore.buildTable(items, now)
        liveTable = t; liveTableAt = now
        return t
    }

    fun tvNote(now: Long): String = when {
        tvHealthy(now) -> "Whole market: TradingView" + (if (tvDelay > 0) ", ${tvDelay / 60_000} min delayed" else "") + " · real-time: ${hot.size} top and your stocks"
        tvTryAt > 0 -> "Live quotes in rotation (TradingView unavailable)"
        else -> "Live quotes in rotation"
    }
    fun setTvState(minimal: Boolean, delay: Long) { tvMinimal = minimal; if (delay >= 0) tvDelay = delay }

    /** Minimal persisted state: the day's fired keys, the ring, notification times. */
    fun persist(): Map<String, Any?> = linkedMapOf("day" to day, "fired" to fired.filterValues { it == day }.keys.toList(),
        "ring" to ring.take(120).map { it.toMap() }, "noteAt" to noteAt.filterValues { lastIngestAt - it < LiveScan.NOTE_SYM_MS }, "tvMinimal" to tvMinimal)

    fun restore(m: Map<String, Any?>?, today: Int) {
        if (m == null) return
        try {
            val d = (m["day"] as? Number)?.toInt() ?: 0
            if (d != today) return
            day = d
            for (k in (m["fired"] as? List<*>).orEmpty()) (k as? String)?.let { fired[it] = d }
            for (x in (m["ring"] as? List<*>).orEmpty()) (x as? Map<*, *>)?.let { LiveEvent.from(it) }?.let { ring.addLast(it) }
            for ((k, v) in (m["noteAt"] as? Map<*, *>).orEmpty()) if (k is String && v is Number) noteAt[k] = v.toLong()
            tvMinimal = m["tvMinimal"] == true
        } catch (t: Throwable) {}
    }
}
