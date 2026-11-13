package com.boomai.swingedge.core

/**
 * Inputs of the stockInfo `panel` (SPEC5 §2). [call] is the finished `call` object (read only: the panel never changes it).
 * [e] = the stock's score entry when rated (own or inserted), [table] = the last scan's published score table.
 * [breadth] = the last scan's share of stocks above their 50-day average (0..1, NaN unknown) when the table has none.
 */
class PanelInput(
    val s: Series, val e: ScoreEntry?, val table: ScoreTable?, val call: Map<String, Any?>,
    val nifty: Series?, val breadth: Double, val capital: Double, val riskPct: Double, val costPct: Double,
    /** listing day from the stock list (IST epoch day, 0 unknown) and today's IST epoch day */
    val listedDay: Int, val today: Int
)

/** Every number the managers, rebuttals and the chair use. NaN = unknown. Percentiles are 0..1, returns fractions, the rest as named. */
class PanelVals {
    var close = Double.NaN; var sma50 = Double.NaN; var sma200 = Double.NaN; var sma200Prev = Double.NaN
    /** % above the 200-day average, % of the 50-day above the 200-day, % change of the 200-day over 21 bars */
    var dist200 = Double.NaN; var d50v200 = Double.NaN; var slope200 = Double.NaN; var dist200P = Double.NaN
    var r6 = Double.NaN; var r12 = Double.NaN; var momP = Double.NaN
    /** daily volatility (fraction), its percentile, ATR (₹) and ATR % of price */
    var vol = Double.NaN; var volP = Double.NaN; var atr = Double.NaN; var atrPct = Double.NaN
    /** 5-ATR stop distance in % and ₹ a share; shares and ₹ at risk at the user's size */
    var stopPct = Double.NaN; var stopRs = Double.NaN; var qty = 0; var riskRs = Double.NaN; var budgetRs = Double.NaN
    var to20 = Double.NaN; var to20P = Double.NaN
    /** (C - SMA50) / ATR14, its universe percentile and the universe's 90th percentile */
    var stretch = Double.NaN; var stretchP = Double.NaN; var stretchP90 = Double.NaN; var rsi2 = Double.NaN
    var niftyDist = Double.NaN; var breadth = Double.NaN
    var bars = 0; var listedDay = 0; var listedKnown = false; var sinceListPct = Double.NaN
    var ranked = false
}

/** Sorted columns of the rated universe of one score table (cached per table). */
class PanelUniverse(val vol: DoubleArray, val to20: DoubleArray, val stretch: DoubleArray, val dist200: DoubleArray, val breadth: Double, val n: Int)

object Panel {
    val IDS = listOf("trend", "momentum", "risk", "liquidity", "timing", "market", "newlisting")
    const val FOR = "FOR"
    const val AGAINST = "AGAINST"
    const val NEUTRAL = "NEUTRAL"

    @Volatile private var cache: Pair<ScoreTable, PanelUniverse>? = null

    private fun ok(x: Double) = !x.isNaN() && !x.isInfinite()

    fun universe(t: ScoreTable?): PanelUniverse? {
        if (t == null || !t.usable) return null
        cache?.let { if (it.first === t) return it.second }
        val r = t.ranked
        val vol = DoubleArray(r.size) { r[it].comp.vol126 }
        val to = DoubleArray(r.size) { r[it].comp.to20 }
        val st = DoubleArray(r.size) { val c = r[it].comp; if (c.atr14 > 0) (c.close - c.sma50) / c.atr14 else Double.NaN }
        val d2 = DoubleArray(r.size) { val c = r[it].comp; if (c.sma200 > 0) c.close / c.sma200 - 1 else Double.NaN }
        var above = 0; var known = 0
        for (e in r) { val c = e.comp; if (ok(c.sma50) && c.sma50 > 0) { known++; if (c.close > c.sma50) above++ } }
        val u = PanelUniverse(SetupScore.column(vol), SetupScore.column(to), SetupScore.column(st), SetupScore.column(d2),
            if (known > 0) above.toDouble() / known else Double.NaN, r.size)
        cache = Pair(t, u)
        return u
    }

    private fun pctIn(sorted: DoubleArray?, x: Double): Double =
        if (sorted == null || sorted.isEmpty() || !ok(x)) Double.NaN else SetupScore.pct(sorted, x, false)

    private fun quantile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val k = Math.min(sorted.size - 1, Math.max(0, Math.ceil(q * sorted.size).toInt() - 1))
        return sorted[k]
    }

    /** 1..3 by how far into its tail the stock sits ([tail] = share of the universe more extreme). */
    fun strength(tail: Double): Int = when {
        !ok(tail) -> 1
        tail <= 0.05 -> 3
        tail <= 0.15 -> 2
        else -> 1
    }

    /** All the numbers, from the page's own series plus the table's percentiles. */
    fun values(x: PanelInput): PanelVals {
        val v = PanelVals()
        val s = x.s
        val n = s.n
        v.bars = n
        if (n == 0) return v
        val last = n - 1
        val comp = SetupScore.compute(s)
        val u = universe(x.table)
        v.ranked = u != null
        v.close = s.c[last]
        v.sma50 = comp.sma50; v.sma200 = comp.sma200
        v.sma200Prev = if (last - 21 >= 0) Ind.sma(s.c, last - 21, 200) else Double.NaN
        if (ok(v.sma200) && v.sma200 > 0) {
            v.dist200 = (v.close / v.sma200 - 1) * 100.0
            v.dist200P = pctIn(u?.dist200, v.close / v.sma200 - 1)
            if (ok(v.sma50)) v.d50v200 = (v.sma50 / v.sma200 - 1) * 100.0
            if (ok(v.sma200Prev) && v.sma200Prev > 0) v.slope200 = (v.sma200 / v.sma200Prev - 1) * 100.0
        }
        v.r6 = comp.r6; v.r12 = comp.r12
        val t = x.table
        val e = x.e
        val ps = ArrayList<Double>()
        if (e != null && e.rated && ok(e.p[1]) && ok(e.p[2])) { ps.add(e.p[1]); ps.add(e.p[2]) }
        else if (t != null && t.usable) {
            pctIn(t.cols[1], comp.r6).let { if (ok(it)) ps.add(it) }
            pctIn(t.cols[2], comp.r12).let { if (ok(it)) ps.add(it) }
        }
        if (ps.isNotEmpty()) v.momP = ps.average()
        v.vol = comp.vol126
        v.volP = pctIn(u?.vol, comp.vol126)
        v.atr = comp.atr14
        if (ok(v.atr) && v.close > 0) {
            v.atrPct = v.atr / v.close * 100.0
            v.stopRs = SetupScore.STOP_ATR * v.atr
            v.stopPct = v.stopRs / v.close * 100.0
            val lv = SetupScore.levels(v.close, v.atr)
            v.qty = SwingApp.positionSize(x.capital, x.riskPct, x.costPct, lv[0], lv[1])
            v.budgetRs = x.capital * x.riskPct / 100.0
            if (v.qty >= 1) v.riskRs = v.qty * (lv[0] - lv[1]) + lv[0] * v.qty * x.costPct / 100.0
            if (ok(v.sma50) && v.atr > 0) {
                v.stretch = (v.close - v.sma50) / v.atr
                if (u != null && u.stretch.isNotEmpty()) { v.stretchP = pctIn(u.stretch, v.stretch); v.stretchP90 = quantile(u.stretch, 0.9) }
            }
        }
        v.to20 = comp.to20
        v.to20P = pctIn(u?.to20, comp.to20)
        v.rsi2 = Ind.rsi(s.c, last, 2)
        val nf = x.nifty
        if (nf != null && nf.n >= 200) {
            val a = Ind.sma(nf.c, nf.n - 1, 200)
            if (ok(a) && a > 0) v.niftyDist = (nf.c[nf.n - 1] / a - 1) * 100.0
        }
        v.breadth = u?.breadth?.takeIf { ok(it) } ?: x.breadth
        v.listedDay = if (x.listedDay > 0) x.listedDay else s.d[0]
        v.listedKnown = x.listedDay > 0
        if (s.o[0] > 0) v.sinceListPct = (v.close / s.o[0] - 1) * 100.0
        return v
    }

    private class M(val id: String, val stance: String, val strength: Int, val text: String, val metrics: List<Pair<String, String>>)

    private fun managers(v: PanelVals): List<M> {
        val out = ArrayList<M>(7)
        // trend
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.dist200)) metrics.add("vs 200-day" to Fmt.pct(v.dist200))
            if (ok(v.d50v200)) metrics.add("50-day vs 200-day" to Fmt.pct(v.d50v200))
            if (ok(v.slope200)) metrics.add("200-day, 21 days" to Fmt.pct(v.slope200))
            if (!ok(v.sma200) || !ok(v.slope200) || !ok(v.sma50)) out.add(M("trend", NEUTRAL, 0, PanelTexts.trendNA(v.bars), metrics))
            else {
                val rising = v.slope200 > 0
                val stance = when {
                    v.close > v.sma50 && v.sma50 > v.sma200 && rising -> FOR
                    v.close < v.sma200 && v.slope200 < 0 -> AGAINST
                    else -> NEUTRAL
                }
                val str = when {
                    stance == NEUTRAL -> 0
                    ok(v.dist200P) -> strength(if (stance == FOR) 1 - v.dist200P else v.dist200P)
                    Math.abs(v.dist200) >= 25 -> 3
                    Math.abs(v.dist200) >= 10 -> 2
                    else -> 1
                }
                out.add(M("trend", stance, str, PanelTexts.trend(v), metrics))
            }
        }
        // momentum
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.r6)) metrics.add("6-month return" to Fmt.pct(v.r6 * 100.0))
            if (ok(v.r12)) metrics.add("12-month return" to Fmt.pct(v.r12 * 100.0))
            if (ok(v.momP)) metrics.add("ahead of" to (Math.round(v.momP * 100.0)).toString() + "%")
            if (ok(v.momP)) {
                val stance = if (v.momP >= 0.7) FOR else if (v.momP <= 0.3) AGAINST else NEUTRAL
                val str = when (stance) { FOR -> strength(1 - v.momP); AGAINST -> strength(v.momP); else -> 0 }
                out.add(M("momentum", stance, str, PanelTexts.momentum(v, true), metrics))
            } else if (ok(v.r6)) {
                val avg = if (ok(v.r12)) (v.r6 + v.r12) / 2 else v.r6
                val stance = if (avg >= PanelTexts.MOM_ABS_FOR) FOR else if (avg <= PanelTexts.MOM_ABS_AGAINST) AGAINST else NEUTRAL
                out.add(M("momentum", stance, if (stance == NEUTRAL) 0 else 1, PanelTexts.momentum(v, false), metrics))
            } else out.add(M("momentum", NEUTRAL, 0, PanelTexts.momentumNA(v.bars), metrics))
        }
        // risk
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.vol)) metrics.add("daily swing" to Fmt.p1(v.vol * 100.0) + "%")
            if (ok(v.atrPct)) metrics.add("ATR" to Fmt.p1(v.atrPct) + "%")
            if (ok(v.stopPct)) metrics.add("5-ATR stop" to "-" + Fmt.p1(v.stopPct) + "%")
            if (ok(v.riskRs)) metrics.add("at risk" to Fmt.rs0(v.riskRs))
            if (!ok(v.stopPct)) out.add(M("risk", NEUTRAL, 0, PanelTexts.riskNA(v.bars), metrics))
            else {
                var stance = NEUTRAL; var str = 0
                if (ok(v.volP)) {
                    if (v.volP <= 1.0 / 3) { stance = FOR; str = strength(v.volP) } else if (v.volP >= 2.0 / 3) { stance = AGAINST; str = strength(1 - v.volP) }
                } else if (ok(v.vol)) {
                    if (v.vol <= PanelTexts.VOL_ABS_FOR) stance = FOR else if (v.vol >= PanelTexts.VOL_ABS_AGAINST) stance = AGAINST
                    if (stance != NEUTRAL) str = 1
                } else {
                    if (v.atrPct <= PanelTexts.ATR_ABS_FOR) stance = FOR else if (v.atrPct >= PanelTexts.ATR_ABS_AGAINST) stance = AGAINST
                    if (stance != NEUTRAL) str = 1
                }
                out.add(M("risk", stance, str, PanelTexts.risk(v), metrics))
            }
        }
        // liquidity
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.to20)) metrics.add("turnover" to "₹" + Fmt.p1(v.to20) + " cr/day")
            if (!ok(v.to20)) out.add(M("liquidity", NEUTRAL, 0, PanelTexts.liquidityNA(v.bars), metrics))
            else {
                val stance = if (v.to20 >= PanelTexts.TO_FOR_CR) FOR else if (v.to20 < SetupScore.MIN_TO20_CR) AGAINST else NEUTRAL
                val str = when (stance) {
                    FOR -> if (ok(v.to20P)) strength(1 - v.to20P) else 1
                    AGAINST -> if (v.to20 < 5) 3 else if (v.to20 < 10) 2 else 1
                    else -> 0
                }
                out.add(M("liquidity", stance, str, PanelTexts.liquidity(v), metrics))
            }
        }
        // timing
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.stretch)) metrics.add("vs 50-day" to (if (v.stretch < 0) "-" else "+") + Fmt.p1(Math.abs(v.stretch)) + " ATR")
            if (ok(v.rsi2)) metrics.add("RSI(2)" to Fmt.p1(v.rsi2))
            if (!ok(v.stretch)) out.add(M("timing", NEUTRAL, 0, PanelTexts.timingNA(v.bars), metrics))
            else {
                val stretched = v.stretch > PanelTexts.STRETCH_MAX || (ok(v.stretchP90) && v.stretch >= v.stretchP90)
                val stance = if (stretched) AGAINST else if (v.stretch >= 0 && v.stretch <= 2) FOR else NEUTRAL
                val str = when (stance) {
                    AGAINST -> if (ok(v.stretchP)) strength(1 - v.stretchP) else if (v.stretch > 2 * PanelTexts.STRETCH_MAX) 3 else 1
                    FOR -> 1
                    else -> 0
                }
                out.add(M("timing", stance, str, PanelTexts.timing(v, stretched), metrics))
            }
        }
        // market
        run {
            val metrics = ArrayList<Pair<String, String>>()
            if (ok(v.niftyDist)) metrics.add("Nifty vs 200-day" to Fmt.pct(v.niftyDist))
            if (ok(v.breadth)) metrics.add("above 50-day" to Math.round(v.breadth * 100.0).toString() + "%")
            if (!ok(v.niftyDist) || !ok(v.breadth)) out.add(M("market", NEUTRAL, 0, PanelTexts.marketNA(ok(v.niftyDist)), metrics))
            else {
                val stance = if (v.niftyDist > 0 && v.breadth > 0.5) FOR else if (v.niftyDist < 0 && v.breadth < 0.4) AGAINST else NEUTRAL
                val str = if (stance == NEUTRAL) 0 else 1 + (if (v.breadth >= 0.65 || v.breadth <= 0.25) 1 else 0) + (if (Math.abs(v.niftyDist) >= 5) 1 else 0)
                out.add(M("market", stance, str, PanelTexts.market(v), metrics))
            }
        }
        // newlisting
        run {
            val metrics = ArrayList<Pair<String, String>>()
            metrics.add("trading days" to v.bars.toString())
            if (ok(v.sinceListPct) && v.bars < SetupScore.MIN_RATED_BARS) metrics.add("since listing" to Fmt.pct(v.sinceListPct))
            if (v.bars < SetupScore.MIN_RATED_BARS) out.add(M("newlisting", AGAINST, 1, PanelTexts.newListing(v), metrics))
            else out.add(M("newlisting", NEUTRAL, 0, PanelTexts.notNew(v), metrics))
        }
        return out
    }

    /** The whole `panel` object. Never throws; never changes [PanelInput.call]. */
    fun build(x: PanelInput): Map<String, Any?> {
        val label = (x.call["label"] as? String) ?: "NA"
        val proven = x.call["proven"] == true
        val asOf = if (x.s.n > 0) StockPage.yyyymmdd(x.s.d[x.s.n - 1]) else 0
        try {
            val v = values(x)
            val ms = managers(v)
            var nf = 0; var na = 0; var nn = 0
            for (m in ms) when (m.stance) { FOR -> nf++; AGAINST -> na++; else -> nn++ }
            val rows = ms.map { m ->
                val opp = if (m.stance == NEUTRAL) null else ms.filter { it.stance != NEUTRAL && it.stance != m.stance }.maxByOrNull { it.strength * 10 - IDS.indexOf(it.id) }
                val reb = if (opp == null) "" else try { PanelTexts.rebuttal(m.id, m.stance, opp.id, v) } catch (t: Throwable) { "" }
                linkedMapOf<String, Any?>("id" to m.id, "name" to PanelTexts.name(m.id), "stance" to m.stance, "strength" to m.strength.coerceIn(0, 3),
                    "evidence" to PanelTexts.evidence(m.id), "text" to m.text, "answers" to (opp?.id ?: ""), "rebuttal" to reb,
                    "metrics" to m.metrics.map { linkedMapOf<String, Any?>("label" to it.first, "value" to it.second) })
            }
            val pros = ms.filter { it.stance == FOR }.sortedByDescending { it.strength }.take(4).map { PanelTexts.pro(it.id, it.text) }
            val cons = ms.filter { it.stance == AGAINST }.sortedByDescending { it.strength }.take(4).map { PanelTexts.con(it.id, it.text) }
            val e = if (x.e != null && x.e.rated) x.e else null
            val chair = linkedMapOf<String, Any?>("label" to label, "proven" to proven,
                "text" to PanelTexts.chairText(label, (x.call["headline"] as? String) ?: "", nf, na, nn),
                "pros" to pros, "cons" to cons,
                "flipUp" to PanelTexts.flipUp(label, e, if (label == "NA") naKind(x) else null, v.bars, v.to20, proven), "flipDown" to PanelTexts.flipDown(label, e))
            return linkedMapOf("tally" to linkedMapOf("for" to nf, "against" to na, "neutral" to nn), "managers" to rows, "chair" to chair, "asOfDay" to asOf)
        } catch (t: Throwable) {
            return empty(label, proven, asOf)
        }
    }

    private fun naKind(x: PanelInput): String {
        if (x.table == null || !x.table.usable) return ScoreTexts.NA_NO_TABLE
        return (x.e?.comp ?: SetupScore.compute(x.s)).naKind() ?: ScoreTexts.NA_NO_TABLE
    }

    /** Every manager NEUTRAL (used only if building the panel failed). */
    fun empty(label: String, proven: Boolean, asOf: Int): Map<String, Any?> = linkedMapOf(
        "tally" to linkedMapOf("for" to 0, "against" to 0, "neutral" to IDS.size),
        "managers" to IDS.map { linkedMapOf<String, Any?>("id" to it, "name" to PanelTexts.name(it), "stance" to NEUTRAL, "strength" to 0,
            "evidence" to PanelTexts.evidence(it), "text" to PanelTexts.CANNOT, "answers" to "", "rebuttal" to "", "metrics" to emptyList<Any?>()) },
        "chair" to linkedMapOf<String, Any?>("label" to label, "proven" to proven, "text" to PanelTexts.chairText(label, "", 0, 0, IDS.size),
            "pros" to emptyList<Any?>(), "cons" to emptyList<Any?>(), "flipUp" to "", "flipDown" to ""),
        "asOfDay" to asOf)

    /** stockInfo `listing` (SPEC5 §3); null when the stock has a full year of bars. */
    fun listing(s: Series, listedDay: Int, today: Int): Map<String, Any?>? {
        try {
            val n = s.n
            if (n >= SetupScore.MIN_RATED_BARS || n <= 0) return null
            val last = n - 1
            val listed = if (listedDay > 0) listedDay else s.d[0]
            var hi = s.h[0]; var hiK = 0; var lo = s.l[0]; var loK = 0
            var peak = s.c[0]; var dd = 0.0
            for (k in 0 until n) {
                if (s.h[k] > hi) { hi = s.h[k]; hiK = k }
                if (s.l[k] < lo) { lo = s.l[k]; loK = k }
                if (s.c[k] > peak) peak = s.c[k]
                if (peak > 0) dd = Math.max(dd, (peak - s.c[k]) / peak * 100.0)
            }
            val ltp = s.c[last]
            val lp = s.o[0]
            var sum = 0.0
            val from = Math.max(0, n - 20)
            for (k in from until n) sum += s.c[k] * s.v[k] / 1e7
            val days = Math.max(0, today - listed)
            return linkedMapOf(
                "isNew" to (days <= SymbolIndex.NEW_DAYS), "listedDay" to StockPage.yyyymmdd(listed), "daysListed" to days, "bars" to n,
                "listPrice" to Fmt.r2(lp), "ltp" to Fmt.r2(ltp), "sinceListingPct" to (if (lp > 0) Fmt.r2((ltp / lp - 1) * 100.0) else 0.0),
                "highSince" to Fmt.r2(hi), "highDay" to StockPage.yyyymmdd(s.d[hiK]), "lowSince" to Fmt.r2(lo), "lowDay" to StockPage.yyyymmdd(s.d[loK]),
                "rangePos" to (if (hi > lo) Fmt.r1((ltp - lo) / (hi - lo) * 100.0) else 0.0),
                "maxDdPct" to Fmt.r2(dd), "avgValueCr" to Fmt.r2(sum / (n - from)), "barsToRated" to (SetupScore.MIN_RATED_BARS - n),
                "baseRate" to PanelTexts.BASE_RATE)
        } catch (t: Throwable) {
            return null
        }
    }
}
