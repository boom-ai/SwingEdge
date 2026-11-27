package com.boomai.swingedge.core

/**
 * "Intraday calls for tomorrow" (nightly-calls).
 *
 *  - [Holidays]: NSE trading holidays (bundled fallback + data/holidays.json from the workflow) and next-trading-day maths.
 *  - [NightCalls]: mechanical next-day setups from daily bars (NR7, NR7 + inside day, narrow CPR, previous-day-high break,
 *    gap-fade / gap-and-go as conditional triggers, plus three short swing entries), each back-tested on the same history
 *    the server job holds (~259 sessions), ranked by tested expectancy, written to data/intraday.csv by scan/ScanMain.kt.
 *  - [NightDesk]: the phone side: downloads intraday.csv after 20:30 IST, posts one notification at 22:00 IST on the
 *    evening before a trading day, and serves the "Tomorrow" card.
 *
 * Daily-bar back-test approximation (documented in the file and on the card):
 *  - OHLC path assumption: an up-close day trades open -> low -> high -> close, a down-close day open -> high -> low ->
 *    close (see [NightCalls.walk]); a buy-stop fills where the path first crosses the trigger (a gap through fills at the
 *    open), and stop / target are whichever the path reaches first after the fill; a sell-stop mirrors it;
 *  - no stop/target hit -> exit at the day's close (proxy for the 15:15 square-off);
 *  - costs 0.10% of the entry per round trip (intraday) / 0.25% (delivery swing), charged in R.
 */
object Holidays {
    const val FILE = "holidays.json"
    const val URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/holidays.json"

    /**
     * Fallback NSE equity-segment trading holidays (weekdays only). 2026: NSE circular "Trading holidays for the calendar
     * year 2026" (Dec 2025) incl. the 15 Jan 2026 Maharashtra municipal-election closure. 2027: NSE had not published its
     * list when this was written; only fixed-date holidays falling on weekdays are listed (provisional) — the workflow
     * replaces this with the NSE API list as soon as it exists.
     */
    val BUNDLED_LIST: List<Pair<String, String>> = listOf(
        "2026-01-15" to "Municipal Corporation elections (Maharashtra)", "2026-01-26" to "Republic Day", "2026-03-03" to "Holi",
        "2026-03-26" to "Shri Ram Navami", "2026-03-31" to "Shri Mahavir Jayanti", "2026-04-03" to "Good Friday",
        "2026-04-14" to "Dr. Baba Saheb Ambedkar Jayanti", "2026-05-01" to "Maharashtra Day", "2026-05-28" to "Bakri Id",
        "2026-06-26" to "Muharram", "2026-09-14" to "Ganesh Chaturthi", "2026-10-02" to "Mahatma Gandhi Jayanti",
        "2026-10-20" to "Dussehra", "2026-11-10" to "Diwali Balipratipada", "2026-11-24" to "Prakash Gurpurb Sri Guru Nanak Dev",
        "2026-12-25" to "Christmas",
        "2027-01-26" to "Republic Day (provisional)", "2027-03-26" to "Good Friday (provisional)", "2027-04-14" to "Dr. Baba Saheb Ambedkar Jayanti (provisional)"
    )
    val BUNDLED: Set<Int> = BUNDLED_LIST.map { Bhav.parseDay(it.first) }.filter { it > 0 }.toHashSet()

    /** Days from the downloaded / committed holidays.json (empty until one is installed). */
    @Volatile var extra: Set<Int> = emptySet()
    @Volatile var names: Map<Int, String> = BUNDLED_LIST.associate { Bhav.parseDay(it.first) to it.second }

    /** Off only in simulations that run on a synthetic calendar (the legacy test harness); the app always has it on. */
    @Volatile var enabled = true
    fun all(): Set<Int> = if (!enabled) emptySet() else if (extra.isEmpty()) BUNDLED else BUNDLED + extra
    fun isHoliday(day: Int, hol: Set<Int> = all()): Boolean = Clock.isWeekday(day) && hol.contains(day)
    fun isTradingDay(day: Int, hol: Set<Int> = all()): Boolean = Clock.isWeekday(day) && !hol.contains(day)

    /** First trading day strictly after [day]. */
    fun nextTradingDay(day: Int, hol: Set<Int> = all()): Int {
        var d = day + 1
        var k = 0
        while (!isTradingDay(d, hol) && k++ < 40) d++
        return d
    }

    /** Last trading day strictly before [day]. */
    fun prevTradingDay(day: Int, hol: Set<Int> = all()): Int {
        var d = day - 1
        var k = 0
        while (!isTradingDay(d, hol) && k++ < 40) d--
        return d
    }

    /**
     * Parses our holidays.json ({"days":[{"date":"2026-01-26","name":"..."}]}) or the raw NSE API answer
     * ({"CM":[{"tradingDate":"26-Jan-2026","description":"..."}], "FO":[...]}; the CM list is used). Weekend dates are
     * dropped. Empty map when nothing usable. Never throws.
     */
    fun parse(text: String?): Map<Int, String> {
        val out = java.util.TreeMap<Int, String>()
        try {
            val o = Json.parseObject(text) ?: return out
            val list = when {
                o["days"] is List<*> -> o["days"].asList()
                o["CM"] is List<*> -> o["CM"].asList()
                else -> emptyList()
            }
            for (x in list) {
                val m = x.asObj()
                val d = Bhav.parseDay(m.str("date").ifEmpty { m.str("tradingDate") })
                if (d > 0 && Clock.isWeekday(d)) out[d] = m.str("name").ifEmpty { m.str("description") }.trim()
            }
        } catch (t: Throwable) {}
        return out
    }

    /** holidays.json text. */
    fun encode(days: Map<Int, String>, source: String, updatedAt: String): String {
        val list = days.toSortedMap().map { (d, n) -> linkedMapOf("date" to java.time.LocalDate.ofEpochDay(d.toLong()).toString(), "name" to n) }
        return Json.stringify(linkedMapOf("source" to source, "updatedAt" to updatedAt, "count" to list.size, "days" to list)) + "\n"
    }

    /** Installs a downloaded / saved holidays.json; false when it holds no day. */
    fun install(text: String?): Boolean {
        val m = parse(text)
        if (m.isEmpty()) return false
        extra = m.keys.toHashSet()
        val nm = HashMap(names); nm.putAll(m); names = nm
        return true
    }
}

/** One rule's back-test record. avgR / winPct are after costs. */
class RuleStat(val id: String, val kind: String, val name: String, val text: String) {
    var trades = 0
    var wins = 0
    var sumR = 0.0
    /** the same trades with the stop assumed first whenever the day's range touched it (worst case of the daily-bar model) */
    var sumWorst = 0.0
    val avgWorst: Double get() = if (trades > 0) sumWorst / trades else 0.0
    val winPct: Double get() = if (trades > 0) wins * 100.0 / trades else 0.0
    val avgR: Double get() = if (trades > 0) sumR / trades else 0.0
    /** TESTED+ (>= MIN_TRADES, positive), TESTED- (>= MIN_TRADES, not positive), FEW (too few trades to judge). */
    val status: String get() = if (trades < NightCalls.MIN_TRADES) "FEW" else if (avgR > 0) "TESTED+" else "TESTED-"
    fun add(r: Double, worst: Double = r) { trades++; if (r > 0) wins++; sumR += r; sumWorst += worst }
}

/** One call for the next session. side BUY/SELL; [cond] = the human trigger text; prices in rupees. */
class NightSetup(
    val sym: String, val name: String, val rule: String, val kind: String, val side: String,
    val trigger: Double, val stop: Double, val target: Double, val exit: String, val qty: Int, val riskRs: Double,
    val fno: Boolean, val cond: String, val score: Double
)

/** Index levels for the next session (classic floor pivots + CPR). */
class IndexLevels(val name: String, val pdh: Double, val pdl: Double, val pdc: Double) {
    val pivot = (pdh + pdl + pdc) / 3
    val bc = (pdh + pdl) / 2
    val tc = 2 * pivot - bc
    val r1 = 2 * pivot - pdl
    val s1 = 2 * pivot - pdh
    val r2 = pivot + (pdh - pdl)
    val s2 = pivot - (pdh - pdl)
    val cprPct = if (pdc > 0) Math.abs(tc - bc) / pdc * 100 else 0.0
}

class NightData(
    val asof: Int, val forDay: Int, val builtAt: Long, val sessions: Int, val stocks: Int,
    val rules: List<RuleStat>, val setups: List<NightSetup>, val index: List<IndexLevels>, val notes: List<String>
)

object NightCalls {
    const val FILE = "intraday.csv"
    const val URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/intraday.csv"
    const val MAGIC = "#SWINGEDGE_INTRADAY"
    const val VERSION = 1
    const val CAPITAL = 100_000.0
    const val RISK_PCT = 1.0
    /** a rule needs at least this many back-test trades before its record counts */
    const val MIN_TRADES = 30
    const val MAX_SETUPS = 15
    const val MAX_PER_RULE = 5
    const val MAX_SWING = 5
    const val COST_INTRADAY = 0.001
    const val COST_SWING = 0.0025
    /** liquidity screen for intraday: price and 20-day average turnover (rupees) */
    const val MIN_PRICE = 50.0
    const val MIN_TURNOVER = 25e7
    const val SWING_MIN_TURNOVER = 10e7
    const val NARROW_CPR_PCT = 0.15

    // IST minutes of the evening schedule (phone)
    const val FETCH_MIN = 20 * 60 + 35
    const val NOTIFY_MIN = 22 * 60
    const val LAST_TRY_MIN = 23 * 60 + 30
    val WAKE_MINUTES = intArrayOf(FETCH_MIN, NOTIFY_MIN, 22 * 60 + 30, 23 * 60, LAST_TRY_MIN)

    val RULES: List<Triple<String, String, String>> = listOf(
        Triple("nr7", "NR7 breakout", "Yesterday was the narrowest range of 7 days. With the trend (close vs 50-day average): buy above its high / sell below its low; stop at the bar's midpoint; target 2R; else exit 15:15."),
        Triple("nr7id", "NR7 inside-day breakout", "NR7 that is also an inside day (Crabel's NR7ID). Same orders as NR7."),
        Triple("cpr", "Narrow CPR breakout", "Central pivot range under 0.15% of price (trend day expected). Close above the CPR and the 20-day average: buy above the previous high, stop below the CPR; mirror for shorts; target 2R; else exit 15:15."),
        Triple("pdh", "Previous-day high break", "Uptrend (close > 20-day > 50-day average) and closed in the top 30% of its range: buy above the previous high, stop 0.5 ATR below it, target 1 ATR (2R); mirror for downtrends."),
        Triple("gapfade", "Small-gap fade", "Only knowable at the open: if it opens 0.3–1% away from the previous close, trade back toward that close at 9:30 (target = previous close, stop = one gap beyond the open); else no trade."),
        Triple("gapgo", "Gap-and-go", "Only knowable at the open: in an uptrend, if it opens at least 1.5% up and above the previous high, buy above the open + 0.1 ATR after 9:30 (opening-range high proxy), stop 0.3 ATR under the open, target 2R; exit 15:15."),
        Triple("washout", "Washout dip (swing)", "OMEGA washout: market RSI(2) ≤ 10; stock relative strength ≥ 80th pct, above its 200-day average, RSI(2) ≤ 5. Buy next open; stop 2 ATR, target 3 ATR, max 8 days."),
        Triple("quick", "Quick bounce (swing)", "OMEGA quick: market RSI(2) ≤ 5 and stock RSI(2) ≤ 2 above its 200-day average. Buy next open; sell on the first close with RSI(2) > 70 or on day 5; 3 ATR disaster stop."),
        Triple("daily", "Daily leader dip (swing)", "OMEGA daily (non-washout days): top 10% strength, above its 200-day average, RSI(2) ≤ 1. Half size. Buy next open; exit RSI(2) > 70 or day 5; 3 ATR disaster stop.")
    )
    fun isSwing(rule: String) = rule == "washout" || rule == "quick" || rule == "daily"

    // ------------------------------------------------------------------ evening schedule (pure)

    /** True when the 22:00 notification belongs to the evening of [day]: a weekday whose next weekday is a trading day. */
    fun notifyEvening(day: Int, hol: Set<Int> = Holidays.all()): Boolean =
        Clock.isWeekday(day) && Holidays.isTradingDay(Clock.nextWeekday(day), hol)

    /** The session the evening of [day] announces (only meaningful when [notifyEvening]). */
    fun targetOf(day: Int): Int = Clock.nextWeekday(day)

    /**
     * Next evening wake strictly after [now]: 20:35 (download) and 22:00 (+ retries to 23:30) on notify evenings whose
     * target session has not been announced yet ([doneFor] = last announced session).
     */
    fun nextWake(now: Long, doneFor: Int, hol: Set<Int> = Holidays.all()): Long {
        val d0 = Clock.epochDay(now)
        for (d in d0..d0 + 10) {
            if (!notifyEvening(d, hol) || targetOf(d) <= doneFor) continue
            for (m in WAKE_MINUTES) { val t = Clock.msAt(d, m); if (t > now) return t }
        }
        return Long.MAX_VALUE
    }

    // ------------------------------------------------------------------ sizing

    /** Shares for [CAPITAL] at [RISK_PCT] risk (x [frac] for half size), capped at the capital itself. */
    fun qty(entry: Double, stop: Double, frac: Double = 1.0): Int {
        val r = Math.abs(entry - stop)
        if (!(entry > 0) || !(r > 0)) return 0
        val byRisk = Math.floor(CAPITAL * RISK_PCT / 100.0 * frac / r + 1e-9)
        val byCap = Math.floor(CAPITAL / entry + 1e-9)
        val q = minOf(byRisk, byCap)
        return if (q < 1 || q.isNaN()) 0 else q.toInt()
    }

    // ------------------------------------------------------------------ per-bar features

    /** Indicator arrays of one series, computed once. */
    class Feat(val s: Series) {
        val n = s.n
        val atr = DoubleArray(n) { Double.NaN }
        val sma20 = DoubleArray(n) { Double.NaN }
        val sma50 = DoubleArray(n) { Double.NaN }
        val sma200 = DoubleArray(n) { Double.NaN }
        val rsi2 = DoubleArray(n) { Double.NaN }
        val to20 = DoubleArray(n) { Double.NaN }
        val r126 = DoubleArray(n) { Double.NaN }
        init {
            var tr = 0.0; var a = Double.NaN; var g = 0.0; var ls = 0.0
            var s20 = 0.0; var s50 = 0.0; var s200 = 0.0; var t20 = 0.0
            for (i in 0 until n) {
                val c = s.c[i]
                val trv = if (i == 0) s.h[i] - s.l[i] else maxOf(s.h[i] - s.l[i], Math.abs(s.h[i] - s.c[i - 1]), Math.abs(s.l[i] - s.c[i - 1]))
                if (i < 14) { tr += trv; if (i == 13) a = tr / 14 } else a = (a * 13 + trv) / 14
                atr[i] = a
                s20 += c; s50 += c; s200 += c; t20 += c * s.v[i]
                if (i >= 20) { s20 -= s.c[i - 20]; t20 -= s.c[i - 20] * s.v[i - 20] }
                if (i >= 50) s50 -= s.c[i - 50]
                if (i >= 200) s200 -= s.c[i - 200]
                if (i >= 19) { sma20[i] = s20 / 20; to20[i] = t20 / 20 }
                if (i >= 49) sma50[i] = s50 / 50
                if (i >= 199) sma200[i] = s200 / 200
                if (i >= 1) {
                    val d = c - s.c[i - 1]; val up = if (d > 0) d else 0.0; val dn = if (d < 0) -d else 0.0
                    if (i <= 2) { g += up / 2; ls += dn / 2 } else { g = (g + up) / 2; ls = (ls + dn) / 2 }
                    if (i >= 2) rsi2[i] = if (ls <= 0) (if (g <= 0) 50.0 else 100.0) else 100 - 100 / (1 + g / ls)
                }
                if (i >= 126 && s.c[i - 126] > 0) r126[i] = c / s.c[i - 126] - 1
            }
        }
        fun range(i: Int) = s.h[i] - s.l[i]
        fun nr7(i: Int): Boolean {
            if (i < 6) return false
            val r = range(i); if (!(r > 0)) return false
            for (k in i - 6 until i) if (range(k) <= r) return false
            return true
        }
        fun inside(i: Int) = i >= 1 && s.h[i] < s.h[i - 1] && s.l[i] > s.l[i - 1]
        fun cprPct(i: Int): Double {
            val p = (s.h[i] + s.l[i] + s.c[i]) / 3; val bc = (s.h[i] + s.l[i]) / 2
            return Math.abs(2 * p - 2 * bc) / s.c[i] * 100
        }
    }

    /** A next-session order: STOP = stop-entry at [trigger]; GAPFADE / GAPGO depend on the open. */
    class Sig(val rule: String, val side: String, val trigger: Double, val stop: Double, val target: Double, val cond: String, val frac: Double = 1.0)

    private fun tick(x: Double) = Math.round(x * 20) / 20.0
    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    /** Intraday stop-entry signals built from bar i (for session i+1). Liquidity is checked by the caller. */
    fun stopSignals(f: Feat, i: Int): List<Sig> {
        val s = f.s
        val out = ArrayList<Sig>(3)
        val a = f.atr[i]; val c = s.c[i]; val h = s.h[i]; val l = s.l[i]
        if (!(a > 0) || f.sma50[i].isNaN() || f.sma20[i].isNaN()) return out
        val up = c > f.sma50[i]
        val buf = maxOf(0.05, c * 0.0005)
        if (f.nr7(i)) {
            val mid = (h + l) / 2
            val id = if (f.inside(i)) "nr7id" else "nr7"
            if (up) { val t = tick(h + buf); val r = t - mid; if (r > 0) out.add(Sig(id, "BUY", t, tick(mid), tick(t + 2 * r), "buy above ₹${fmt(t)}")) }
            else { val t = tick(l - buf); val r = mid - t; if (r > 0) out.add(Sig(id, "SELL", t, tick(mid), tick(t - 2 * r), "sell below ₹${fmt(t)}")) }
        }
        val cp = f.cprPct(i)
        if (cp <= NARROW_CPR_PCT) {
            val p = (h + l + c) / 3; val bc = (h + l) / 2; val tc = 2 * p - bc
            val top = maxOf(bc, tc); val bot = minOf(bc, tc)
            if (c > top && c > f.sma20[i]) { val t = tick(h + buf); val st = tick(bot - buf); val r = t - st; if (r > 0 && r <= a) out.add(Sig("cpr", "BUY", t, st, tick(t + 2 * r), "buy above ₹${fmt(t)}")) }
            else if (c < bot && c < f.sma20[i]) { val t = tick(l - buf); val st = tick(top + buf); val r = st - t; if (r > 0 && r <= a) out.add(Sig("cpr", "SELL", t, st, tick(t - 2 * r), "sell below ₹${fmt(t)}")) }
        }
        val rng = h - l
        val atrPct = a / c * 100
        if (rng > 0 && atrPct in 1.5..6.0) {
            val pos = (c - l) / rng
            if (c > f.sma20[i] && f.sma20[i] > f.sma50[i] && pos >= 0.7) { val t = tick(h + buf); out.add(Sig("pdh", "BUY", t, tick(t - 0.5 * a), tick(t + a), "buy above ₹${fmt(t)}")) }
            else if (c < f.sma20[i] && f.sma20[i] < f.sma50[i] && pos <= 0.3) { val t = tick(l - buf); out.add(Sig("pdh", "SELL", t, tick(t + 0.5 * a), tick(t - a), "sell below ₹${fmt(t)}")) }
        }
        return out
    }

    /** Gap rules are templates: they need tomorrow's open. Candidates: gap-fade on calm range-bound stocks, gap-and-go on trending ones. */
    fun gapCandidates(f: Feat, i: Int): List<String> {
        val a = f.atr[i]; val c = f.s.c[i]
        if (!(a > 0) || f.sma20[i].isNaN() || f.sma50[i].isNaN()) return emptyList()
        val out = ArrayList<String>(2)
        if (a / c * 100 < 2.5 && Math.abs(c - f.sma20[i]) < a) out.add("gapfade")
        if (c > f.sma20[i] && f.sma20[i] > f.sma50[i]) out.add("gapgo")
        return out
    }

    /**
     * Walks one daily bar along the usual OHLC path assumption: an up-close day (close >= open) goes open -> low -> high ->
     * close, a down-close day open -> high -> low -> close. [entered] = entry price when already in the trade (NaN = wait
     * for [trigger]; NaN trigger = enter at the open). Returns (entry, exit) where exit = stop / target / NaN (still open
     * at the close), or null when never entered.
     */
    fun walk(o: Double, h: Double, l: Double, c: Double, long: Boolean, trigger: Double, stop: Double, target: Double, entered: Double = Double.NaN): Pair<Double, Double>? {
        var e = entered
        if (e.isNaN() && (trigger.isNaN() || (long && o >= trigger) || (!long && o <= trigger))) e = o
        if (!e.isNaN()) {
            if (long) { if (o <= stop) return Pair(e, o); if (o >= target) return Pair(e, o) }
            else { if (o >= stop) return Pair(e, o); if (o <= target) return Pair(e, o) }
        }
        val pts = if (c >= o) doubleArrayOf(o, l, h, c) else doubleArrayOf(o, h, l, c)
        for (k in 0 until 3) {
            val a = pts[k]; val b = pts[k + 1]
            if (e.isNaN()) {
                val cross = if (long) a < trigger && b >= trigger else a > trigger && b <= trigger
                if (!cross) continue
                e = trigger
            }
            if (long) { if (b < a && b <= stop) return Pair(e, stop); if (b > a && b >= target) return Pair(e, target) }
            else { if (b > a && b >= stop) return Pair(e, stop); if (b < a && b <= target) return Pair(e, target) }
        }
        return if (e.isNaN()) null else Pair(e, Double.NaN)
    }

    private fun rOf(long: Boolean, e: Double, x: Double, risk: Double, cost: Double) = (if (long) x - e else e - x) / risk - cost * e / risk

    /** R multiple (after costs) of a stop-entry signal on the next bar; null when not triggered. */
    fun simStop(sg: Sig, o: Double, h: Double, l: Double, c: Double, worst: Boolean = false): Double? {
        val long = sg.side == "BUY"
        val w = walk(o, h, l, c, long, sg.trigger, sg.stop, sg.target) ?: return null
        val risk = Math.abs(w.first - sg.stop)
        if (!(risk > 0)) return null
        return rOf(long, w.first, exitOf(w, long, sg.stop, h, l, c, worst), risk, COST_INTRADAY)
    }

    /** Exit price: the path model's, or with [worst] the stop whenever the day's range reached it at all (bounds the path assumption from below). */
    private fun exitOf(w: Pair<Double, Double>, long: Boolean, stop: Double, h: Double, l: Double, c: Double, worst: Boolean): Double =
        if (worst && (if (long) l <= stop else h >= stop)) stop else if (w.second.isNaN()) c else w.second

    /** Gap-fade on the next bar given the previous close; null when the open is not a 0.3-1% gap. 1R = the gap. */
    fun simGapFade(pdc: Double, o: Double, h: Double, l: Double, c: Double, worst: Boolean = false): Double? {
        val g = o / pdc - 1
        if (Math.abs(g) < 0.003 || Math.abs(g) > 0.01) return null
        val gap = Math.abs(o - pdc); val long = g < 0
        val st = if (long) o - gap else o + gap
        val w = walk(o, h, l, c, long, Double.NaN, st, pdc) ?: return null
        return rOf(long, o, exitOf(w, long, st, h, l, c, worst), gap, COST_INTRADAY)
    }

    /** Gap-and-go on the next bar (candidate already checked on the previous bar). */
    fun simGapGo(pdc: Double, pdh: Double, atr: Double, o: Double, h: Double, l: Double, c: Double, worst: Boolean = false): Double? {
        if (!(o >= pdc * 1.015 && o > pdh && atr > 0)) return null
        val t = o + 0.1 * atr; val st = o - 0.3 * atr; val r = t - st
        val w = walk(o, h, l, c, true, t, st, t + 2 * r) ?: return null
        return rOf(true, w.first, exitOf(w, true, st, h, l, c, worst), w.first - st, COST_INTRADAY)
    }

    /** Swing trade bought at the open of bar [e]: R after costs. Stop/target in ATRs (target NaN = none); null if still open at the data's end. */
    fun simSwing(f: Feat, e: Int, atr: Double, stopAtr: Double, tgtAtr: Double, maxDays: Int, rsiExit: Boolean): Double? {
        val s = f.s
        if (e >= s.n || !(atr > 0)) return null
        val entry = s.o[e]; val st = entry - stopAtr * atr; val tg = if (tgtAtr.isNaN()) Double.MAX_VALUE else entry + tgtAtr * atr
        val r = entry - st
        var k = e
        while (k < s.n && k < e + maxDays) {
            val w = walk(s.o[k], s.h[k], s.l[k], s.c[k], true, Double.NaN, st, tg, entry)
            if (w != null && !w.second.isNaN()) return rOf(true, entry, w.second, r, COST_SWING)
            if (rsiExit && f.rsi2[k] > 70) return rOf(true, entry, s.c[k], r, COST_SWING)
            k++
        }
        if (k < e + maxDays) return null
        return rOf(true, entry, s.c[k - 1], r, COST_SWING)
    }

    // ------------------------------------------------------------------ whole run

    /**
     * Builds the next-session calls from adjusted daily series. [fno] = F&O underlyings; [index] = Nifty / Bank Nifty
     * levels of the last session (may be empty); [hol] = trading holidays.
     */
    fun build(stocks: List<MarketStock>, fno: Set<String>, index: List<IndexLevels>, hol: Set<Int>, builtAt: Long): NightData {
        val stats = LinkedHashMap<String, RuleStat>()
        for ((id, nm, tx) in RULES) stats[id] = RuleStat(id, if (isSwing(id)) "swing" else "intraday", nm, tx)
        val have = stocks.filter { it.s.n > 30 }
        if (have.isEmpty()) return NightData(0, 0, builtAt, 0, 0, stats.values.toList(), emptyList(), index, emptyList())
        val count = HashMap<Int, Int>()
        for (x in have) count[x.s.lastDay()] = (count[x.s.lastDay()] ?: 0) + 1
        val asof = count.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).key
        val forDay = Holidays.nextTradingDay(asof, hol)
        val feats = have.filter { it.s.lastDay() == asof }.map { Pair(it, Feat(it.s)) }

        // sessions + market proxy (equal-weight daily return of liquid stocks) + cross-sectional 6-month strength percentile
        val days = java.util.TreeSet<Int>(); for ((_, f) in feats) for (k in 0 until f.n) days.add(f.s.d[k])
        val dayList = days.toIntArray(); val pos = HashMap<Int, Int>(); for ((k, d) in dayList.withIndex()) pos[d] = k
        val nd = dayList.size
        val sumR = DoubleArray(nd); val cntR = IntArray(nd)
        val rsLists = Array(nd) { ArrayList<Double>() }
        for ((x, f) in feats) {
            if (x.exch != Bhav.NSE) continue
            for (k in 1 until f.n) {
                val t = f.to20[k - 1]
                if (!(t >= SWING_MIN_TURNOVER) || !(f.s.c[k - 1] > 0)) continue
                val p = pos[f.s.d[k]]!!
                val r = f.s.c[k] / f.s.c[k - 1] - 1
                if (Math.abs(r) < 0.3) { sumR[p] += r; cntR[p]++ }
                if (!f.r126[k].isNaN()) rsLists[p].add(f.r126[k])
            }
        }
        val mkt = DoubleArray(nd); var lvl = 1000.0
        for (p in 0 until nd) { if (cntR[p] > 0) lvl *= 1 + sumR[p] / cntR[p]; mkt[p] = lvl }
        val mser = Series("MKT", nd); for (p in 0 until nd) mser.add(dayList[p], mkt[p], mkt[p], mkt[p], mkt[p], 1.0)
        val mf = Feat(mser)
        val sorted = rsLists.map { it.sorted().toDoubleArray() }
        fun rsPct(p: Int, v: Double): Double {
            val a = sorted[p]; if (a.size < 20 || v.isNaN()) return Double.NaN
            var lo = 0; var hi = a.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
            return lo * 100.0 / a.size
        }

        val live = ArrayList<Pair<NightSetup, String>>()
        val gapLive = ArrayList<Pair<NightSetup, Double>>()
        for ((x, f) in feats) {
            if (x.exch != Bhav.NSE || (x.series != "EQ" && x.series != "BE")) continue
            val s = f.s
            val last = s.n - 1
            for (i in 50 until s.n) {
                val liquid = s.c[i] >= MIN_PRICE && f.to20[i] >= MIN_TURNOVER
                val swingLiquid = s.c[i] >= MIN_PRICE && f.to20[i] >= SWING_MIN_TURNOVER
                val p = pos[s.d[i]]!!
                // intraday stop-entry rules
                if (liquid) {
                    val sigs = stopSignals(f, i)
                    if (i < last) for (sg in sigs) simStop(sg, s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1])?.let { stats[sg.rule]!!.add(it, simStop(sg, s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1], true) ?: it) }
                    else for (sg in sigs) live.add(Pair(setup(x, sg, fno), sg.rule))
                    val gc = gapCandidates(f, i)
                    if (i < last) {
                        if ("gapfade" in gc) simGapFade(s.c[i], s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1])?.let { stats["gapfade"]!!.add(it, simGapFade(s.c[i], s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1], true) ?: it) }
                        if ("gapgo" in gc) simGapGo(s.c[i], s.h[i], f.atr[i], s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1])?.let { stats["gapgo"]!!.add(it, simGapGo(s.c[i], s.h[i], f.atr[i], s.o[i + 1], s.h[i + 1], s.l[i + 1], s.c[i + 1], true) ?: it) }
                    } else {
                        val pdc = s.c[i]; val a = f.atr[i]
                        if ("gapfade" in gc) {
                            val lo = tick(pdc * 1.003); val hi = tick(pdc * 1.01)
                            gapLive.add(Pair(NightSetup(x.sym, x.name, "gapfade", "intraday", "IF GAP", tick(pdc), 0.0, tick(pdc), "15:15", qty(pdc * 1.006, pdc * 1.012), 0.0, x.sym in fno,
                                "If it opens ₹${fmt(lo)}–₹${fmt(hi)} (0.3–1% up): sell at 9:30, target ₹${fmt(tick(pdc))}, stop = open + gap. If it opens 0.3–1% down: buy, target ₹${fmt(tick(pdc))}.", f.to20[i]), f.to20[i]))
                        }
                        if ("gapgo" in gc && a > 0) {
                            val gmin = tick(maxOf(pdc * 1.015, s.h[i] + 0.05))
                            gapLive.add(Pair(NightSetup(x.sym, x.name, "gapgo", "intraday", "IF GAP", gmin, 0.0, 0.0, "15:15", qty(gmin + 0.1 * a, gmin - 0.3 * a), r2(0.4 * a), x.sym in fno,
                                "If it opens at or above ₹${fmt(gmin)}: buy above open + ₹${fmt(r2(0.1 * a))} after 9:30, stop open − ₹${fmt(r2(0.3 * a))}, target 2R, exit 15:15.", f.to20[i]), f.to20[i]))
                        }
                    }
                }
                // swing entries (OMEGA modes)
                if (swingLiquid && !f.sma200[i].isNaN() && s.c[i] > f.sma200[i] && !f.rsi2[i].isNaN()) {
                    val mr = mf.rsi2[p]; val rs = rsPct(p, f.r126[i]); val a = f.atr[i]
                    if (mr.isNaN() || rs.isNaN() || !(a > 0)) continue
                    val modes = ArrayList<String>(2)
                    if (mr <= 10 && rs >= 80 && f.rsi2[i] <= 5) modes.add("washout")
                    if (mr <= 5 && f.rsi2[i] <= 2) modes.add("quick")
                    if (mr > 10 && rs >= 90 && f.rsi2[i] <= 1) modes.add("daily")
                    for (m in modes) {
                        val (sa, ta, md, rx) = when (m) { "washout" -> Quad(2.0, 3.0, 8, false); "quick" -> Quad(3.0, Double.NaN, 5, true); else -> Quad(3.0, Double.NaN, 5, true) }
                        if (i < last) simSwing(f, i + 1, a, sa, ta, md, rx)?.let { stats[m]!!.add(it) }
                        else {
                            val e = s.c[i]; val st = tick(e - sa * a); val tg = if (ta.isNaN()) 0.0 else tick(e + ta * a)
                            val frac = if (m == "daily") 0.5 else 1.0
                            val q = qty(e, st, frac)
                            live.add(Pair(NightSetup(x.sym, x.name, m, "swing", "BUY", tick(e), st, tg, "max $md days" + if (rx) " or RSI(2) > 70" else "", q, r2(q * (e - st)), x.sym in fno,
                                "Buy at the open (near ₹${fmt(tick(e))})" + if (frac < 1) ", half size" else "", f.to20[i]), m))
                        }
                    }
                }
            }
        }
        // rank: rules with a positive tested record only, best expectancy first; within a rule F&O then most liquid
        fun ok(rule: String) = stats[rule]!!.status == "TESTED+"
        fun exp(rule: String) = stats[rule]!!.avgR
        val intra = (live.filter { !isSwing(it.second) }.map { it.first } + gapLive.map { it.first })
            .filter { ok(it.rule) }
            .sortedWith(compareByDescending<NightSetup> { exp(it.rule) }.thenByDescending { it.fno }.thenByDescending { it.score }.thenBy { it.sym })
        val per = HashMap<String, Int>(); val seen = HashSet<String>()
        val pick = ArrayList<NightSetup>()
        for (x in intra) {
            if (pick.size >= MAX_SETUPS) break
            if ((per[x.rule] ?: 0) >= MAX_PER_RULE || !seen.add(x.sym)) continue
            per[x.rule] = (per[x.rule] ?: 0) + 1; pick.add(x)
        }
        val swing = live.filter { isSwing(it.second) && ok(it.second) }.map { it.first }
            .sortedWith(compareByDescending<NightSetup> { exp(it.rule) }.thenByDescending { it.score }.thenBy { it.sym })
            .distinctBy { it.sym }.take(MAX_SWING)
        val notes = listOf(
            "Back-test on ${nd} sessions of NSE bhavcopies (daily bars). Fill/exit order from the OHLC path assumption (up-close day: open-low-high-close; down-close day: open-high-low-close); a gap through the trigger fills at the open; no stop/target hit = exit at the close (15:15 proxy). Costs 0.10% (intraday) / 0.25% (swing) per round trip.",
            "Only rules with >= $MIN_TRADES back-test trades and a positive average R (path model) are listed; the worst-case column assumes the stop was hit whenever the day reached it. Past results on one year of data are not a promise. Size: ₹1,00,000 capital, 1% (₹1,000) risk per trade.",
            "Market proxy for the swing modes: equal-weight index of liquid NSE stocks (not the official Nifty 500)."
        )
        return NightData(asof, forDay, builtAt, nd, feats.size, stats.values.toList(), pick + swing, index, notes)
    }

    private data class Quad(val a: Double, val b: Double, val c: Int, val d: Boolean)

    private fun setup(x: MarketStock, sg: Sig, fno: Set<String>): NightSetup {
        val q = qty(sg.trigger, sg.stop, sg.frac)
        return NightSetup(x.sym, x.name, sg.rule, "intraday", sg.side, sg.trigger, sg.stop, sg.target, "15:15", q, r2(q * Math.abs(sg.trigger - sg.stop)), x.sym in fno,
            "After 9:30: ${sg.cond}, stop ₹${fmt(sg.stop)}, target ₹${fmt(sg.target)}, exit 15:15", 0.0)
    }

    fun fmt(x: Double): String = if (x >= 1000) indian(Math.round(x)) else String.format(java.util.Locale.US, "%.2f", x)
    private fun indian(v: Long): String {
        val s = v.toString(); if (s.length <= 3) return s
        val last3 = s.takeLast(3); var rest = s.dropLast(3); val parts = ArrayList<String>()
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return parts.joinToString(",") + "," + last3
    }

    /** F&O underlyings from data/fo_lots.csv (second column), indices excluded. */
    fun fnoSymbols(text: String?): Set<String> {
        val out = HashSet<String>()
        try { for (l in (text ?: return out).split('\n').drop(1)) { val c = l.split(','); val s = c.getOrNull(1)?.trim()?.uppercase() ?: continue; if (s.isNotEmpty() && s != "SYMBOL" && !s.contains("NIFTY") && s != "SENSEX" && s != "BANKEX") out.add(s) } } catch (t: Throwable) {}
        return out
    }

    /** Nifty 50 / Nifty Bank levels from NSE ind_close_all_DDMMYYYY.csv. */
    fun indexLevels(text: String?): List<IndexLevels> {
        val out = ArrayList<IndexLevels>()
        try {
            val lines = (text ?: return out).split('\n')
            val h = StockList.splitCsv(lines[0]).map { it.trim().uppercase() }
            val ni = h.indexOf("INDEX NAME"); val hi = h.indexOf("HIGH INDEX VALUE"); val lo = h.indexOf("LOW INDEX VALUE"); val ci = h.indexOf("CLOSING INDEX VALUE")
            if (ni < 0 || hi < 0 || lo < 0 || ci < 0) return out
            val want = linkedMapOf("NIFTY 50" to "NIFTY", "NIFTY BANK" to "BANKNIFTY")
            val got = HashMap<String, IndexLevels>()
            for (l in lines.drop(1)) {
                val r = StockList.splitCsv(l.trimEnd('\r'))
                val nm = want[r.getOrNull(ni)?.trim()?.uppercase() ?: continue] ?: continue
                val H = r.getOrNull(hi)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: continue
                val L = r.getOrNull(lo)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: continue
                val C = r.getOrNull(ci)?.trim()?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (H > 0 && L > 0 && C > 0 && H >= L) got[nm] = IndexLevels(nm, H, L, C)
            }
            for (k in want.values) got[k]?.let { out.add(it) }
        } catch (t: Throwable) {}
        return out
    }

    // ------------------------------------------------------------------ file format

    private fun q(s: String): String = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\n", " ").replace("\"", "\"\"") + "\"" else s
    private fun iso(d: Int) = if (d > 0) java.time.LocalDate.ofEpochDay(d.toLong()).toString() else ""
    val COLS = listOf("RANK", "SYMBOL", "NAME", "RULE", "KIND", "SIDE", "TRIGGER", "STOP", "TARGET", "EXIT", "QTY", "RISK_RS", "FNO", "WHEN")

    fun encode(d: NightData): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append(",v=").append(VERSION).append(",asof=").append(iso(d.asof)).append(",for=").append(iso(d.forDay))
            .append(",builtAt=").append(d.builtAt).append(",sessions=").append(d.sessions).append(",stocks=").append(d.stocks)
            .append(",capital=").append(CAPITAL.toLong()).append(",riskPct=").append(RISK_PCT).append('\n')
        for (r in d.rules) sb.append("#RULE,").append(r.id).append(',').append(r.kind).append(',').append(r.trades).append(',').append(r2(r.winPct))
            .append(',').append(Math.round(r.avgR * 1000) / 1000.0).append(',').append(r2(r.sumR)).append(',').append(r.status).append(',').append(q(r.name)).append(',').append(q(r.text)).append(',').append(Math.round(r.avgWorst * 1000) / 1000.0).append('\n')
        for (x in d.index) sb.append("#INDEX,").append(x.name).append(',').append(listOf(x.pdh, x.pdl, x.pdc, x.pivot, x.bc, x.tc, x.r1, x.s1, x.r2, x.s2).joinToString(",") { r2(it).toString() })
            .append(',').append(Math.round(x.cprPct * 1000) / 1000.0).append('\n')
        for (n in d.notes) sb.append("#NOTE,").append(q(n)).append('\n')
        sb.append(COLS.joinToString(",")).append('\n')
        for ((k, x) in d.setups.withIndex()) sb.append(listOf((k + 1).toString(), x.sym, q(x.name), x.rule, x.kind, x.side, r2(x.trigger).toString(), r2(x.stop).toString(), r2(x.target).toString(),
            q(x.exit), x.qty.toString(), r2(x.riskRs).toString(), if (x.fno) "1" else "0", q(x.cond)).joinToString(",")).append('\n')
        return sb.toString()
    }

    /** Parses intraday.csv; null when it is not one. Never throws. */
    fun parse(text: String?): NightData? {
        try {
            if (text == null || !text.startsWith(MAGIC)) return null
            val lines = text.split('\n').map { it.trimEnd('\r') }
            val meta = HashMap<String, String>()
            for (kv in lines[0].split(',').drop(1)) { val k = kv.indexOf('='); if (k > 0) meta[kv.substring(0, k)] = kv.substring(k + 1) }
            if ((meta["v"]?.toIntOrNull() ?: 0) != VERSION) return null
            val asof = Bhav.parseDay(meta["asof"]); val forDay = Bhav.parseDay(meta["for"])
            if (asof <= 0 || forDay <= asof) return null
            val rules = ArrayList<RuleStat>(); val index = ArrayList<IndexLevels>(); val notes = ArrayList<String>(); val setups = ArrayList<NightSetup>()
            var ix: Map<String, Int>? = null
            for (l in lines.drop(1)) {
                if (l.isBlank()) continue
                val r = StockList.splitCsv(l)
                when {
                    l.startsWith("#RULE,") -> {
                        val st = RuleStat(r[1], r[2], r.getOrElse(8) { r[1] }, r.getOrElse(9) { "" })
                        st.trades = r[3].toIntOrNull() ?: 0
                        st.wins = Math.round((r[4].toDoubleOrNull() ?: 0.0) * st.trades / 100.0).toInt()
                        st.sumR = (r[5].toDoubleOrNull() ?: 0.0) * st.trades
                        st.sumWorst = (r.getOrNull(10)?.toDoubleOrNull() ?: (r[5].toDoubleOrNull() ?: 0.0)) * st.trades
                        rules.add(st)
                    }
                    l.startsWith("#INDEX,") -> { val v = r.drop(2).map { it.toDoubleOrNull() ?: 0.0 }; if (v.size >= 3 && v[0] > 0) index.add(IndexLevels(r[1], v[0], v[1], v[2])) }
                    l.startsWith("#NOTE,") -> notes.add(r.getOrElse(1) { "" })
                    l.startsWith("#") -> {}
                    ix == null -> { val m = HashMap<String, Int>(); for ((k, h) in r.withIndex()) m[h.trim()] = k; if (!COLS.all { m.containsKey(it) }) return null; ix = m }
                    else -> {
                        val m = ix!!
                        fun s(c: String) = r.getOrNull(m[c]!!)?.trim() ?: ""
                        fun d(c: String) = s(c).toDoubleOrNull() ?: 0.0
                        val sym = s("SYMBOL").uppercase(); if (sym.isEmpty() || sym.length > 25) continue
                        setups.add(NightSetup(sym, s("NAME").ifEmpty { sym }, s("RULE"), s("KIND"), s("SIDE"), d("TRIGGER"), d("STOP"), d("TARGET"), s("EXIT"),
                            s("QTY").toIntOrNull() ?: 0, d("RISK_RS"), s("FNO") == "1", s("WHEN"), 0.0))
                    }
                }
            }
            if (ix == null) return null
            return NightData(asof, forDay, meta["builtAt"]?.toLongOrNull() ?: 0L, meta["sessions"]?.toIntOrNull() ?: 0, meta["stocks"]?.toIntOrNull() ?: 0, rules, setups, index, notes)
        } catch (t: Throwable) { return null }
    }

    /** JSON view for the "Tomorrow" card. */
    fun view(d: NightData?, now: Long, notifiedFor: Int): Map<String, Any?> {
        if (d == null) return linkedMapOf("ok" to true, "none" to true, "nextHoliday" to nextHoliday(Clock.epochDay(now)))
        val today = Clock.epochDay(now)
        val names = HashMap<String, RuleStat>(); for (r in d.rules) names[r.id] = r
        return linkedMapOf("ok" to true, "none" to false, "asof" to iso(d.asof), "forDay" to iso(d.forDay), "forLabel" to Clock.dayLabel(d.forDay),
            "forMs" to Clock.msAt(d.forDay, Clock.OPEN_MIN), "isToday" to (d.forDay == today), "stale" to (d.forDay < today),
            "builtAt" to d.builtAt, "sessions" to d.sessions, "notified" to (notifiedFor >= d.forDay), "capital" to CAPITAL, "riskPct" to RISK_PCT,
            "rules" to d.rules.map { linkedMapOf("id" to it.id, "kind" to it.kind, "name" to it.name, "text" to it.text, "trades" to it.trades, "winPct" to r2(it.winPct), "avgR" to Math.round(it.avgR * 100) / 100.0, "avgWorst" to Math.round(it.avgWorst * 100) / 100.0, "status" to it.status) },
            "setups" to d.setups.map { x -> linkedMapOf("sym" to x.sym, "name" to x.name, "rule" to x.rule, "ruleName" to (names[x.rule]?.name ?: x.rule), "kind" to x.kind, "side" to x.side,
                "trigger" to x.trigger, "stop" to x.stop, "target" to x.target, "exit" to x.exit, "qty" to x.qty, "riskRs" to x.riskRs, "fno" to x.fno, "when" to x.cond,
                "winPct" to r2(names[x.rule]?.winPct ?: 0.0), "avgR" to Math.round((names[x.rule]?.avgR ?: 0.0) * 100) / 100.0, "trades" to (names[x.rule]?.trades ?: 0)) },
            "index" to d.index.map { linkedMapOf("name" to it.name, "pdh" to r2(it.pdh), "pdl" to r2(it.pdl), "pdc" to r2(it.pdc), "pivot" to r2(it.pivot), "bc" to r2(minOf(it.bc, it.tc)), "tc" to r2(maxOf(it.bc, it.tc)),
                "r1" to r2(it.r1), "s1" to r2(it.s1), "cprPct" to Math.round(it.cprPct * 1000) / 1000.0) },
            "notes" to d.notes, "nextHoliday" to nextHoliday(today))
    }

    private fun nextHoliday(today: Int): Map<String, Any?>? {
        val h = Holidays.all().filter { it >= today }.minOrNull() ?: return null
        return linkedMapOf("day" to iso(h), "label" to Clock.dayLabel(h), "name" to (Holidays.names[h] ?: ""))
    }

    /** "Tomorrow's intraday calls (Tue 2 Dec): 6 setups" */
    fun title(d: NightData): String = "Tomorrow's intraday calls (" + Clock.dayLabel(d.forDay) + "): " + Fmt.plural(d.setups.size, "setup")

    fun body(d: NightData): String {
        if (d.setups.isEmpty()) return "No rule has a positive back-test record right now — sit out tomorrow."
        val top = d.setups.take(3).joinToString(" · ") { (if (it.side == "SELL") "sell " else if (it.side == "IF GAP") "gap " else "buy ") + it.sym + (if (it.trigger > 0 && it.side != "IF GAP") " " + fmt(it.trigger) else "") }
        return "$top. Untested live; back-tested on ${d.sessions} sessions. Exit intraday by 15:15."
    }
}

/**
 * Phone side. Owns its small state file (night.json): when intraday.csv was last fetched and which session was last
 * announced. All methods are called under SwingApp's lock (tick) except [view] (read-only snapshot).
 */
class NightDesk(private val p: Platform) {
    @Volatile var data: NightData? = null
        private set
    @Volatile var notifiedFor = 0
        private set
    private var fetchedAt = 0L
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val m = Json.parseObject(p.read(STATE)) ?: emptyMap()
            notifiedFor = m.int("notifiedFor"); fetchedAt = m.long("fetchedAt")
        } catch (t: Throwable) {}
        try { Holidays.install(p.read(Holidays.FILE)) } catch (t: Throwable) {}
        try { data = NightCalls.parse(p.read(NightCalls.FILE)) } catch (t: Throwable) {}
    }
    private fun save() { try { p.write(STATE, Json.stringify(linkedMapOf("notifiedFor" to notifiedFor, "fetchedAt" to fetchedAt))) } catch (t: Throwable) {} }

    fun nextWake(now: Long): Long { load(); return NightCalls.nextWake(now, notifiedFor) }

    /** Downloads intraday.csv + holidays.json (at most every 20 minutes). True when a newer file was installed. */
    fun fetch(now: Long): Boolean {
        load()
        if (now - fetchedAt in 0 until FETCH_GAP_MS) return false
        fetchedAt = now; save()
        val r = try { p.httpGetAll(listOf(NightCalls.URL, Holidays.URL)) } catch (t: Throwable) { emptyList() }
        r.getOrNull(1)?.let { if (Holidays.install(it)) try { p.write(Holidays.FILE, it) } catch (t: Throwable) {} }
        val text = r.getOrNull(0) ?: return false
        val d = NightCalls.parse(text) ?: return false
        val cur = data
        if (cur != null && (d.asof < cur.asof || (d.asof == cur.asof && d.builtAt < cur.builtAt))) return false
        try { p.write(NightCalls.FILE, text) } catch (t: Throwable) {}
        data = d
        return true
    }

    /**
     * Evening step. Returns (title, body) when the notification is due now, else null. From 20:35 on a notify evening it
     * fetches until the file for the next session is there; at 22:00 it announces it once; past 23:30 it gives up quietly.
     */
    fun step(now: Long): Pair<String, String>? {
        load()
        val day = Clock.epochDay(now); val min = Clock.minuteOfDay(now)
        if (!NightCalls.notifyEvening(day) || min < NightCalls.FETCH_MIN) return null
        val target = NightCalls.targetOf(day)
        if (notifiedFor >= target) return null
        if ((data?.forDay ?: 0) < target) fetch(now)
        if (min < NightCalls.NOTIFY_MIN) return null
        val d = data
        if (d != null && d.forDay == target) { notifiedFor = target; save(); return Pair(NightCalls.title(d), NightCalls.body(d)) }
        if (min >= NightCalls.LAST_TRY_MIN) { notifiedFor = target; save(); return Pair("Tomorrow's intraday calls (" + Clock.dayLabel(target) + ")", "Tonight's list did not arrive from the server. No calls for tomorrow.") }
        return null
    }

    fun view(now: Long): Map<String, Any?> { load(); return NightCalls.view(data, now, notifiedFor) }

    companion object {
        const val STATE = "night.json"
        const val FETCH_GAP_MS = 20L * 60 * 1000
    }
}
