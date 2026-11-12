package com.boomai.swingedge.core

/**
 * Per-stock inputs of the setup score at one bar, exactly as research/REPORT4.md "Exact implementation spec" defines them.
 * NaN = undefined (not enough bars). The five ranked components are RAM, R6_1, R12_1, PROX52, LOW52 (in that order).
 */
class ScoreComp(
    val nbars: Int,
    /** epoch day of the bar (0 when unknown, e.g. in tests) */
    val day: Int,
    val close: Double, val prevClose: Double, val high: Double,
    /** unadjusted close of the bar (for the >= Rs 50 test) */
    val rawClose: Double,
    /** mean turnover of the last 20 bars, Rs crore */
    val to20: Double,
    val sma50: Double, val atr14: Double,
    val r6: Double, val r12: Double, val vol126: Double, val ram: Double,
    val hi252: Double, val lo252: Double, val prox52: Double, val low52: Double,
    val uclock: Boolean,
    /** SMA200 of the bar (round 5: AVOID needs score < 55 AND close below it); NaN = not enough bars */
    val sma200: Double = Double.NaN
) {
    /** TO20 >= 25 cr and price >= Rs 50 (NaN fails). */
    val liquid: Boolean get() = to20 >= SetupScore.MIN_TO20_CR && rawClose >= SetupScore.MIN_PRICE

    /** Member of the cross-section U[D]: liquid and at least 252 bars (its defined components are ranked against). */
    val inUniverse: Boolean get() = liquid && nbars >= SetupScore.MIN_UNIVERSE_BARS

    /** Component k: 0 RAM, 1 R6_1, 2 R12_1, 3 PROX52, 4 LOW52. */
    fun x(k: Int): Double = when (k) { 0 -> ram; 1 -> r6; 2 -> r12; 3 -> prox52; else -> low52 }

    val allDefined: Boolean get() = (0 until 5).all { !x(it).isNaN() }

    /** Gets a score: in U and every component defined. */
    val rateable: Boolean get() = inUniverse && allDefined

    /**
     * Liquid by turnover, enough history, every component defined, but priced under Rs 50. Not part of the cross-section
     * (the research universe excludes it), but scored by insertion against it so the user still gets a rating; the
     * call texts say the tested history covers Rs 50+ stocks only.
     */
    val lowPriceRateable: Boolean get() = !rateable && to20 >= SetupScore.MIN_TO20_CR && !(rawClose >= SetupScore.MIN_PRICE) &&
        nbars >= SetupScore.MIN_RATED_BARS && allDefined

    /** Why it is not rated: null if rateable, else ILLIQUID | CHEAP | SHORT | INCOMPLETE. */
    fun naKind(): String? = when {
        rateable || lowPriceRateable -> null
        !(to20 >= SetupScore.MIN_TO20_CR) -> SetupScore.NA_ILLIQUID
        !(rawClose >= SetupScore.MIN_PRICE) -> SetupScore.NA_CHEAP
        nbars < SetupScore.MIN_RATED_BARS || r12.isNaN() -> SetupScore.NA_SHORT
        else -> SetupScore.NA_INCOMPLETE
    }
}

/** One stock's score. [p] holds the five percentiles (NaN when not rated). label: BUY | WAIT | AVOID | NA. */
class ScoreEntry(
    val sym: String, val comp: ScoreComp, val p: DoubleArray, val raw: Double, val score: Double,
    val label: String, val band: String,
    /** 1-based place among the rated stocks (score descending, ties by symbol); 0 when not rated */
    val rank: Int,
    /** number of rated stocks the rank is out of */
    val of: Int
) {
    val rated: Boolean get() = label != "NA"
}

/**
 * Immutable cross-sectional score table of one scan. [cols] are the sorted defined values of each component over the
 * members of U (used to rank an outside stock by insertion). Built once per scan and published as a whole.
 */
class ScoreTable(
    val builtAt: Long,
    val entries: Map<String, ScoreEntry>,
    val cols: Array<DoubleArray>,
    /** rated stocks, score descending then symbol ascending */
    val ranked: List<ScoreEntry>,
    val universeN: Int
) {
    val rated: Int get() = ranked.size
    /** Enough rated stocks for the ranking to mean something. */
    val usable: Boolean get() = ranked.size >= Params.SCORE_MIN_RATED
}

/**
 * The researched setup score (REPORT4): five momentum components, ranked cross-sectionally with average ranks,
 * summed, rounded with Math.rint to one decimal, then labelled. Pure functions on plain arrays; never throw.
 */
object SetupScore {
    const val MIN_TO20_CR = 25.0
    const val MIN_PRICE = 50.0
    /** U[D] needs nbars >= 252 ... */
    const val MIN_UNIVERSE_BARS = 252
    /** ... but R12_1 needs bar i-252, so a rated stock has at least 253 bars. */
    const val MIN_RATED_BARS = 253
    const val HORIZON_DAYS = 60
    const val BUY_MIN = 85.0
    const val AVOID_BELOW = 55.0

    const val NA_ILLIQUID = "ILLIQUID"
    const val NA_CHEAP = "CHEAP"
    const val NA_SHORT = "SHORT"
    const val NA_INCOMPLETE = "INCOMPLETE"

    private val NaN = Double.NaN
    private val SQRT105 = Math.sqrt(105.0)

    /** -0.0 -> +0.0 (so sorting and == agree); non-finite -> NaN (undefined). */
    private fun norm(x: Double): Double = if (x.isNaN() || x.isInfinite()) NaN else x + 0.0

    /**
     * Indicators at bar [i] of one stock. [h],[l],[c] are its own bars oldest first (split-adjusted); [rawClose] the
     * unadjusted close of bar i; [toCr] per-bar turnover in Rs crore; [nbars] bars since listing (i+1 unless the arrays
     * start after the listing). Windows count bars; sums run oldest to newest in plain doubles, like the reference.
     */
    fun compute(h: DoubleArray, l: DoubleArray, c: DoubleArray, rawClose: Double, toCr: DoubleArray, i: Int, nbars: Int, day: Int = 0): ScoreComp {
        try {
            if (i < 0 || i >= c.size || i >= h.size || i >= l.size) return empty(nbars, day)
            val to20 = if (i >= 19 && i < toCr.size) { var s = 0.0; for (k in i - 19..i) s += toCr[k]; s / 20 } else NaN
            val sma50 = if (i >= 49) { var s = 0.0; for (k in i - 49..i) s += c[k]; s / 50 } else NaN
            val sma200 = if (i >= 199) { var s = 0.0; for (k in i - 199..i) s += c[k]; s / 200 } else NaN
            val atr14 = if (i >= 13) {
                var s = 0.0
                for (k in i - 13..i) {
                    val tr = if (k == 0) h[0] - l[0] else { val pc = c[k - 1]; maxOf(h[k] - l[k], Math.abs(h[k] - pc), Math.abs(l[k] - pc)) }
                    s += tr
                }
                s / 14
            } else NaN
            val r6 = if (i >= 126) c[i - 21] / c[i - 126] - 1 else NaN
            val r12 = if (i >= 252) c[i - 21] / c[i - 252] - 1 else NaN
            var vol = NaN
            if (i >= 126) {
                var sum = 0.0
                for (k in i - 125..i) sum += c[k] / c[k - 1] - 1
                val m = sum / 126
                var ss = 0.0
                for (k in i - 125..i) { val d = (c[k] / c[k - 1] - 1) - m; ss += d * d }
                vol = Math.sqrt(ss / 125)
            }
            val ram = r6 / (vol * SQRT105)
            var hi = NaN
            var lo = NaN
            if (i >= 251) {
                hi = h[i - 251]; lo = l[i - 251]
                for (k in i - 250..i) { if (h[k] > hi) hi = h[k]; if (l[k] < lo) lo = l[k] }
            }
            val r1 = if (i >= 1) c[i] / c[i - 1] - 1 else NaN
            val uclock = c[i] >= h[i] * 0.9995 && minOf(Math.abs(r1 - 0.05), Math.abs(r1 - 0.10), Math.abs(r1 - 0.20)) < 0.003
            return ScoreComp(nbars, day, c[i], if (i >= 1) c[i - 1] else 0.0, h[i], rawClose, to20, sma50, atr14,
                norm(r6), norm(r12), vol, norm(ram), hi, lo, norm(c[i] / hi), norm(c[i] / lo), uclock, sma200 = sma200)
        } catch (t: Throwable) {
            return empty(nbars, day)
        }
    }

    /** [compute] on the latest bar of a Series; turnover from the series' own close x volume (see SwingApp notes). */
    fun compute(s: Series): ScoreComp {
        val n = s.n
        if (n <= 0) return empty(0, 0)
        val to = DoubleArray(n) { s.c[it] * s.v[it] / 1e7 }
        return compute(s.h, s.l, s.c, s.c[n - 1], to, n - 1, n, s.d[n - 1])
    }

    fun empty(nbars: Int, day: Int) = ScoreComp(nbars, day, 0.0, 0.0, 0.0, 0.0, NaN, NaN, NaN, NaN, NaN, NaN, NaN, NaN, NaN, NaN, NaN, false)

    /** Sorted defined values (the M of one component). */
    fun column(values: DoubleArray): DoubleArray {
        var n = 0
        for (v in values) if (!v.isNaN()) n++
        val out = DoubleArray(n)
        var k = 0
        for (v in values) if (!v.isNaN()) out[k++] = v + 0.0
        java.util.Arrays.sort(out)
        return out
    }

    /**
     * Average-rank percentile of [x] among [sorted] (defined values of M):
     * (#{m < x} + (#{m == x} + 1) / 2) / n with s counting itself. [selfIn] = x is already one of the values; otherwise
     * it is inserted (n + 1, one more equal). NaN when x is undefined or M is empty.
     */
    fun pct(sorted: DoubleArray, x: Double, selfIn: Boolean): Double {
        if (x.isNaN()) return NaN
        val v = x + 0.0
        var lo = 0
        var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sorted[mid] < v) lo = mid + 1 else hi = mid }
        val less = lo
        var up = lo
        hi = sorted.size
        while (up < hi) { val mid = (up + hi) ushr 1; if (sorted[mid] <= v) up = mid + 1 else hi = mid }
        var eq = up - less
        var n = sorted.size
        if (!selfIn) { eq += 1; n += 1 }
        if (n <= 0) return NaN
        return (less + (eq + 1) / 2.0) / n
    }

    /** raw = (100 * (pRAM + pR6_1 + pR12_1 + pPROX52 + pLOW52)) / 5, added in exactly this order. */
    fun raw(p: DoubleArray): Double = (100.0 * (p[0] + p[1] + p[2] + p[3] + p[4])) / 5.0

    /** S = rint(raw * 10) / 10 (round half to even). */
    fun round1(raw: Double): Double = Math.rint(raw * 10.0) / 10.0

    fun band(score: Double): String = when {
        score < 40 -> "0-39"
        score < 55 -> "40-54"
        score < 70 -> "55-69"
        score < 85 -> "70-84"
        else -> "85-100"
    }

    /** Round 5 (research/out_r5): AVOID = score < 55 AND close below SMA200; a low score above SMA200 is WAIT. NaN SMA200 keeps the R4 rule. */
    fun label(score: Double, close: Double, sma50: Double, uclock: Boolean, sma200: Double = Double.NaN): String = when {
        score < AVOID_BELOW && (sma200.isNaN() || close < sma200) -> "AVOID"
        score < AVOID_BELOW -> "WAIT"
        score >= BUY_MIN && close > sma50 && !uclock -> "BUY"
        else -> "WAIT"
    }

    /** Round 5 plan P2: fixed disaster stop this many ATR below the entry. */
    const val STOP_ATR = 5.0
    /** [entryHigh, stop, t1, t2] = C + 0.5 ATR, C - 5 ATR (plan P2), C + 6 ATR, C + 9 ATR (reference levels only). */
    fun levels(close: Double, atr14: Double): DoubleArray =
        doubleArrayOf(close + 0.5 * atr14, close - STOP_ATR * atr14, close + 6 * atr14, close + 9 * atr14)

    private fun na(sym: String, comp: ScoreComp) = ScoreEntry(sym, comp, DoubleArray(5) { NaN }, NaN, 0.0, "NA", "", 0, 0)

    private fun scored(sym: String, comp: ScoreComp, p: DoubleArray, rank: Int, of: Int): ScoreEntry {
        val raw = raw(p)
        val s = round1(raw)
        return ScoreEntry(sym, comp, p, raw, s, label(s, comp.close, comp.sma50, comp.uclock, comp.sma200), band(s), rank, of)
    }

    /** Score descending, then symbol ascending (byte order for these ASCII symbols). */
    val ORDER: Comparator<ScoreEntry> = compareByDescending<ScoreEntry> { it.score }.thenBy { it.sym }

    /** Builds the table for one cross-section. Every item gets an entry (NA when not rateable). */
    fun buildTable(items: List<Pair<String, ScoreComp>>, builtAt: Long): ScoreTable {
        val members = items.filter { it.second.inUniverse }
        val cols = Array(5) { k -> column(DoubleArray(members.size) { members[it].second.x(k) }) }
        val pre = ArrayList<ScoreEntry>()
        val out = HashMap<String, ScoreEntry>()
        val lowPrice = ArrayList<Pair<String, ScoreComp>>()
        for ((sym, comp) in items) {
            if (comp.lowPriceRateable) { lowPrice.add(Pair(sym, comp)); continue }
            if (!comp.rateable) { out[sym] = na(sym, comp); continue }
            val p = DoubleArray(5) { k -> pct(cols[k], comp.x(k), true) }
            pre.add(scored(sym, comp, p, 0, 0))
        }
        pre.sortWith(ORDER)
        val ranked = ArrayList<ScoreEntry>(pre.size)
        for ((k, e) in pre.withIndex()) {
            val r = ScoreEntry(e.sym, e.comp, e.p, e.raw, e.score, e.label, e.band, k + 1, pre.size)
            ranked.add(r)
            out[e.sym] = r
        }
        val t = ScoreTable(builtAt, out, cols, ranked, members.size)
        for ((sym, comp) in lowPrice) out[sym] = insert(t, sym, comp)
        return t
    }

    /**
     * Scores a stock that is not part of [t] by inserting it into each component's M (n + 1). The rank is its place
     * among the table's rated stocks plus itself.
     */
    fun insert(t: ScoreTable, sym: String, comp: ScoreComp): ScoreEntry {
        if (!comp.rateable && !comp.lowPriceRateable) return na(sym, comp)
        val p = DoubleArray(5) { k -> pct(t.cols[k], comp.x(k), false) }
        if (p.any { it.isNaN() }) return na(sym, comp)
        val e = scored(sym, comp, p, 0, 0)
        var above = 0
        for (x in t.ranked) if (ORDER.compare(x, e) < 0) above++ else break
        return ScoreEntry(e.sym, e.comp, e.p, e.raw, e.score, e.label, e.band, above + 1, t.ranked.size + 1)
    }

    /** BUY entries of the table, highest score first (ties by symbol), at most [max]. */
    fun topBuys(t: ScoreTable, max: Int = 15): List<ScoreEntry> {
        if (!t.usable) return emptyList()
        val out = ArrayList<ScoreEntry>()
        for (e in t.ranked) { if (out.size >= max) break; if (e.label == "BUY") out.add(e) }
        return out
    }
}
