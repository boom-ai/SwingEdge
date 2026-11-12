package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*
import java.io.File

/** SPEC3 + v3.1: setup score (REPORT4), stockInfo.call, ideas, user targets. */
object V3Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v3-score-fixtures" to ::fixtures, "v3-score-history-json" to ::historyJson, "v3-score-unit" to ::unit,
        "v3-position-size" to ::sizing, "v3-score-scan" to ::scan, "v3-score-na" to ::naPaths, "v3-targets" to ::targets,
        "v3-concurrent" to ::concurrent
    )

    private val BANNED = listOf("guaranteed", "guarantee", "sure shot", "sure-shot", "risk-free", "risk free", "assured", "no risk", "will reach", "will hit", "will go")
    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()
    private val NaN = Double.NaN

    private fun act(app: SwingApp, name: String, args: String): Map<String, Any?> {
        val txt = app.action(name, args)
        val r = Json.parseObject(txt)
        T.check(r != null && r["ok"] is Boolean, "action $name returns {ok:..}: " + txt.take(100))
        T.check(!txt.contains("NaN") && !txt.contains("Infinity"), "no NaN/Infinity in $name")
        return r ?: emptyMap()
    }

    private fun relNear(a: Double, b: Double, rel: Double, msg: String) =
        T.check(!a.isNaN() && Math.abs(a - b) <= rel * maxOf(1.0, Math.abs(b)), "$msg — expected $b got $a")

    // ------------------------------------------------------------------ test-only CSV reader
    private class Csv(val head: List<String>, val rows: List<List<String>>) {
        private val ix = head.withIndex().associate { it.value to it.index }
        fun get(r: List<String>, k: String): String = r.getOrNull(ix[k] ?: -1) ?: ""
        fun num(r: List<String>, k: String): Double = get(r, k).let { if (it.isEmpty() || it == "nan") NaN else it.toDouble() }
    }

    private fun readCsv(path: String): Csv {
        val lines = File(path).readLines().filter { it.isNotBlank() }
        return Csv(lines[0].split(','), lines.drop(1).map { it.split(',') })
    }

    private const val FIX = "coretest/fixtures/"

    /** A ScoreComp from a fixture_universe.csv row: only the ranked components matter (liquid by construction). */
    private fun uniComp(nbars: Int, x: DoubleArray) =
        ScoreComp(nbars, 0, 100.0, 100.0, 100.0, 100.0, 100.0, 90.0, 1.0, x[1], x[2], 0.01, x[0], 1.0, 1.0, x[3], x[4], false)

    // ------------------------------------------------------------------ fixtures (REPORT4 test vectors)
    fun fixtures() {
        val fx = readCsv(FIX + "fixtures.csv")
        val px = readCsv(FIX + "fixture_prices.csv")
        val uni = readCsv(FIX + "fixture_universe.csv")
        val bars = HashMap<String, ArrayList<List<String>>>()
        for (r in px.rows) if (px.get(r, "symbol") != "NIFTY50") bars.getOrPut(px.get(r, "symbol")) { ArrayList() }.add(r)
        val uniByDate = HashMap<String, ArrayList<List<String>>>()
        for (r in uni.rows) uniByDate.getOrPut(uni.get(r, "date")) { ArrayList() }.add(r)
        val comps = listOf("ram", "r6_1", "r12_1", "prox52", "low52")
        val pcols = listOf("p_ram", "p_r6_1", "p_r12_1", "p_prox52", "p_low52")
        var passed = 0
        val labels = HashMap<String, Int>()
        for (f in fx.rows) {
            val sym = fx.get(f, "symbol")
            val date = fx.get(f, "date")
            val tag = sym + " " + date
            val fails0 = T.fail
            val b = bars[sym]!!.filter { px.get(it, "date") <= date }.sortedBy { px.get(it, "date") }
            val n = b.size
            val h = DoubleArray(n) { px.num(b[it], "h") }
            val l = DoubleArray(n) { px.num(b[it], "l") }
            val c = DoubleArray(n) { px.num(b[it], "c") }
            val to = DoubleArray(n) { px.num(b[it], "turnover_cr") }
            val nb = fx.get(f, "nbars").toInt()
            val comp = SetupScore.compute(h, l, c, px.num(b[n - 1], "close_raw"), to, n - 1, nb)
            relNear(comp.atr14, fx.num(f, "atr14"), 1e-6, "$tag atr14")
            if (!fx.num(f, "sma50").isNaN()) relNear(comp.sma50, fx.num(f, "sma50"), 1e-6, "$tag sma50")
            relNear(comp.to20, fx.num(f, "to20_cr"), 1e-6, "$tag to20")
            T.eq(comp.liquid, fx.get(f, "liquid") == "True", "$tag liquid")
            // the cross-section: every other universe stock of that day, the fixture stock inserted (as the reference does)
            val others = uniByDate[date]!!.filter { uni.get(it, "symbol") != sym }
            val cols = Array(5) { k -> SetupScore.column(DoubleArray(others.size) { uni.num(others[it], comps[k]) }) }
            val tbl = ScoreTable(0L, emptyMap(), cols, emptyList(), others.size)
            val e = SetupScore.insert(tbl, sym, comp)
            // round 5: a low score above the 200-day average is WAIT, not AVOID
            val want = fx.get(f, "label").let { if (it == "NOT_RATED") "NA" else it }.let { if (it == "AVOID" && !comp.sma200.isNaN() && !(comp.close < comp.sma200)) "WAIT" else it }
            T.eq(e.label, want, "$tag label")
            labels[want] = (labels[want] ?: 0) + 1
            if (want != "NA") {
                for (k in 0 until 5) relNear(comp.x(k), fx.num(f, if (k == 1) "r6_1" else if (k == 2) "r12_1" else comps[k]), 1e-8, "$tag ${comps[k]}")
                for (k in 0 until 5) T.near(e.p[k], fx.num(f, pcols[k]), 1e-9, "$tag ${pcols[k]}")
                T.check(e.score == fx.num(f, "score"), "$tag score exactly: expected ${fx.num(f, "score")} got ${e.score}")
                T.eq(e.band, fx.get(f, "band"), "$tag band")
                T.eq(comp.uclock, fx.get(f, "upper_circuit_lock") == "True", "$tag upper circuit lock")
                // the whole-table path (the stock as one of the members) gives bit-identical percentiles and score
                val items = others.map { Pair(uni.get(it, "symbol"), uniComp(uni.get(it, "nbars").toInt(), DoubleArray(5) { k -> uni.num(it, comps[k]) })) } + Pair(sym, comp)
                val te = SetupScore.buildTable(items, 0L).entries[sym]!!
                T.check((0 until 5).all { te.p[it] == e.p[it] } && te.score == e.score && te.label == e.label, "$tag table path = insert path")
                if (fx.get(f, "entry_max").isNotEmpty()) {
                    val lv = SetupScore.levels(comp.close, comp.atr14)
                    relNear(lv[0], fx.num(f, "entry_max"), 1e-6, "$tag entry_max")
                    relNear(lv[1], comp.close - SetupScore.STOP_ATR * fx.num(f, "atr14"), 1e-6, "$tag stop (round 5: 5 ATR)")
                    relNear(comp.close - 3 * comp.atr14, fx.num(f, "stop_est"), 1e-6, "$tag R4 stop_est")
                    relNear(lv[2], fx.num(f, "t1_est"), 1e-6, "$tag t1_est")
                    relNear(lv[3], fx.num(f, "t2_est"), 1e-6, "$tag t2_est")
                }
            } else {
                T.check(comp.naKind() != null, "$tag not rateable")
            }
            if (T.fail == fails0) passed++
        }
        println("      fixtures: $passed/${fx.rows.size} pass · labels $labels")
        T.eq(fx.rows.size, 40, "40 fixtures")
        T.eq(passed, fx.rows.size, "every fixture reproduced")
    }

    // ------------------------------------------------------------------ embedded history = score_bands.json
    fun historyJson() {
        val j = Json.parseObject(File(FIX + "score_bands.json").readText())!!
        T.eq(j.str("version"), ScoreHistory.VERSION, "version")
        T.eq(j.int("horizonDays"), ScoreHistory.HORIZON_DAYS, "horizon")
        T.eq(j.bool("descriptiveOnly"), true, "descriptive only")
        T.eq(ScoreHistory.BANDS.map { it.key }, listOf("0-39", "40-54", "55-69", "70-84", "85-100"), "bands")
        for (b in ScoreHistory.BANDS) {
            val raw = j.obj("bands").obj(b.key).obj("OOS_raw")
            val ex = j.obj("bands").obj(b.key).obj("OOS_excess")
            T.eq(b.n, raw.int("n"), "${b.key} n")
            T.eq(b.winPct, raw.num("winPct"), "${b.key} winPct")
            T.eq(b.avgPct, raw.num("avgPct"), "${b.key} avgPct")
            T.eq(b.medianPct, raw.num("medianPct"), "${b.key} medianPct")
            T.eq(b.worstPct, raw.num("worstPct"), "${b.key} worstPct")
            T.eq(b.excessAvgPct, ex.num("avgPct"), "${b.key} excess avg")
            T.eq(b.excessT, ex.num("tNeweyWest"), "${b.key} excess t")
        }
        val pl = j.obj("tradePlan").obj("OOS")
        T.eq(ScoreHistory.Plan.TRADES, pl.int("trades"), "plan trades")
        T.eq(ScoreHistory.Plan.WIN_PCT, pl.num("winPct"), "plan win")
        T.eq(ScoreHistory.Plan.EXPECTANCY_PCT, pl.num("expectancyPct"), "plan avg")
        T.eq(ScoreHistory.Plan.MEDIAN_PCT, pl.num("medianPct"), "plan median")
        T.eq(ScoreHistory.Plan.WORST_PCT, pl.num("worstPct"), "plan p5")
        T.eq(ScoreHistory.Plan.WORST_SINGLE_PCT, pl.num("worstSingleTradePct"), "plan worst")
        T.eq(ScoreHistory.Plan.AVG_HOLD_DAYS, pl.num("avgHoldDays"), "plan hold")
        T.eq(ScoreHistory.Plan.PROFIT_FACTOR, pl.num("profitFactor"), "plan pf")
        T.eq(ScoreHistory.Plan.STOP_HIT_PCT, pl.num("stopHitPct"), "plan stop hit")
        T.eq(ScoreHistory.Plan.T1_TOUCHED_PCT, pl.num("t1TouchedPct"), "plan t1")
        T.eq(ScoreHistory.Plan.T2_TOUCHED_PCT, pl.num("t2TouchedPct"), "plan t2")
        val pf = j.obj("portfolio_100k").obj("4slots_OOS")
        T.eq(ScoreHistory.Portfolio.CAGR_PCT, pf.num("cagrPct"), "4-slot CAGR")
        T.eq(ScoreHistory.Portfolio.MAX_DD_PCT, pf.num("maxDrawdownPct"), "4-slot drawdown")
        val nf = j.obj("nifty50_price").obj("OOS")
        T.eq(ScoreHistory.Portfolio.NIFTY_CAGR_PCT, nf.num("cagrPct"), "Nifty CAGR")
        T.eq(ScoreHistory.Portfolio.NIFTY_MAX_DD_PCT, nf.num("maxDrawdownPct"), "Nifty drawdown")
        // the texts carry these numbers
        val ev = ScoreTexts.evidence("BUY", ScoreHistory.band("85-100"))
        T.eq(ev, R5.BUY_EVIDENCE, "BUY evidence wording")
        val av = ScoreTexts.evidence("AVOID", ScoreHistory.band("0-39"))
        T.eq(av, R5.AVOID_EVIDENCE, "AVOID evidence wording")
        T.check(!R5.BUY_EVIDENCE.contains("guarantee") && R5.BUY_EVIDENCE.contains("no proven edge"), "BUY evidence stays honest")
        val rb = j.obj("portfolioRobustness").list("rows").map { r -> r.asObj() }
            .first { it.str("plan") == "X10" && it.str("period") == "OOS" && it.int("slots") == 4 }
        T.eq(ScoreHistory.Robust.CAGR_MIN, rb.num("score_min"), "robust min"); T.eq(ScoreHistory.Robust.CAGR_MAX, rb.num("score_max"), "robust max")
        T.eq(ScoreHistory.Robust.CAGR_MEDIAN, rb.num("score_median_over_starts"), "robust median"); T.eq(ScoreHistory.Robust.MDD_MEDIAN, rb.num("score_mdd_median"), "robust mdd")
        val ht = ScoreTexts.histText(ScoreHistory.band("85-100")!!)
        for (w in listOf("+4.4%", "+0.7%", "60 trading days", "1 in 20 lost 30.2%", "+1.40% more than the average liquid stock", "not statistically reliable", "t = 1.5")) T.check(ht.contains(w), "hist text has '$w': $ht")
        T.check(ScoreTexts.histText(ScoreHistory.band("55-69")!!).contains("a statistically reliable difference (t = 3.2)"), "55-69 excess is reliable by the t-stat")
        for (s in listOf(ev, av, ht, ScoreTexts.EXIT_RULE, ScoreTexts.CAVEAT)) for (w in BANNED) T.check(!s.lowercase().contains(w), "banned '$w'")
    }

    // ------------------------------------------------------------------ pure functions, hand-checked
    private fun hc(x: DoubleArray, nbars: Int = 300, to20: Double = 100.0, close: Double = 100.0, sma: Double = 90.0, uclock: Boolean = false) =
        ScoreComp(nbars, 0, close, 99.0, close, close, to20, sma, 2.0, x[1], x[2], 0.01, x[0], 110.0, 80.0, x[3], x[4], uclock)

    fun unit() {
        // percentiles with ties and an undefined component
        val items = listOf(
            "A" to hc(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)), "B" to hc(doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0)),
            "C" to hc(doubleArrayOf(2.0, 2.0, NaN, 2.0, 2.0), nbars = 252), "D" to hc(doubleArrayOf(3.0, 3.0, 3.0, 3.0, 3.0)),
            "E" to hc(doubleArrayOf(9.0, 9.0, 9.0, 9.0, 9.0), to20 = 1.0)
        )
        val t = SetupScore.buildTable(items, 1L)
        T.eq(t.universeN, 4, "E (illiquid) is not in U; C (252 bars) is")
        T.eq(t.cols.map { it.size }, listOf(4, 4, 3, 4, 4), "C's undefined 12-month return is left out of that column only")
        val b = t.entries["B"]!!
        T.eq(b.p[0], (1 + (2 + 1) / 2.0) / 4, "B ties C: (1 + 3/2) / 4 = 0.625")
        T.eq(b.p[2], (1 + (1 + 1) / 2.0) / 3, "B in the 3-member column: 2/3")
        T.eq(b.raw, (100.0 * (0.625 + 0.625 + (1 + (1 + 1) / 2.0) / 3 + 0.625 + 0.625)) / 5.0, "raw summed in order")
        T.eq(b.score, Math.rint(b.raw * 10) / 10, "score")
        T.eq(t.entries["A"]!!.p[0], (0 + (1 + 1) / 2.0) / 4, "lowest: (0 + 1) / n")
        T.eq(t.entries["D"]!!.score, 100.0, "highest everywhere = 100")
        T.eq(t.entries["D"]!!.label, "BUY", "100, above SMA50 -> BUY")
        T.eq(t.entries["C"]!!.label, "NA", "252 bars: not rated")
        T.eq(t.entries["E"]!!.label, "NA", "illiquid: not rated")
        T.eq(t.entries["E"]!!.comp.naKind(), SetupScore.NA_ILLIQUID, "illiquid kind")
        T.eq(t.ranked.map { it.sym }, listOf("D", "B", "A"), "ranked by score")
        T.eq(t.ranked.map { it.rank }, listOf(1, 2, 3), "ranks")
        // insert-rank of an outside stock
        val x = SetupScore.insert(t, "X", hc(doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0)))
        T.eq(x.p[0], (1 + (2 + 1 + 1) / 2.0) / 5, "inserted: ties with B and C, n + 1")
        T.eq(x.p[2], (1 + (1 + 1 + 1) / 2.0) / 4, "inserted into the 3-member column")
        T.check(x.rank == 3 && x.of == 4, "X ranks 3 of 4: ${x.rank} of ${x.of}")
        T.eq(SetupScore.insert(t, "Y", hc(doubleArrayOf(2.0, 2.0, NaN, 2.0, 2.0))).label, "NA", "outside stock with an undefined component: NA")
        // equal scores order by symbol; -0.0 ties with 0.0
        val tw = SetupScore.buildTable(listOf("B2" to hc(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)), "B1" to hc(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)), "Z" to hc(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0)), "Q" to hc(doubleArrayOf(-0.0, -0.0, -0.0, -0.0, -0.0))), 1L)
        T.eq(tw.ranked.map { it.sym }, listOf("B1", "B2", "Q", "Z"), "ties broken by symbol")
        T.check(tw.entries["Q"]!!.p[0] == tw.entries["Z"]!!.p[0], "-0.0 and 0.0 are equal values")
        T.eq(SetupScore.pct(doubleArrayOf(0.0, 1.0), -0.0, false), (0 + (1 + 1 + 1) / 2.0) / 3, "-0.0 inserted ties with 0.0")
        // rounding half to even, bands, labels
        T.eq(SetupScore.round1(1.25), 1.2, "rint 12.5 -> 12"); T.eq(SetupScore.round1(1.75), 1.8, "rint 17.5 -> 18"); T.eq(SetupScore.round1(84.96), 85.0, "84.96 -> 85.0")
        for ((s, bd) in listOf(0.0 to "0-39", 39.9 to "0-39", 40.0 to "40-54", 54.9 to "40-54", 55.0 to "55-69", 69.9 to "55-69", 70.0 to "70-84", 84.9 to "70-84", 85.0 to "85-100", 100.0 to "85-100"))
            T.eq(SetupScore.band(s), bd, "band of $s")
        T.eq(SetupScore.label(85.0, 101.0, 100.0, false), "BUY", "85 above SMA -> BUY")
        T.eq(SetupScore.label(84.9, 101.0, 100.0, false), "WAIT", "84.9 -> WAIT")
        T.eq(SetupScore.label(90.0, 100.0, 100.0, false), "WAIT", "close == SMA50 -> WAIT")
        T.eq(SetupScore.label(90.0, 101.0, 100.0, true), "WAIT", "upper-circuit lock -> WAIT")
        T.eq(SetupScore.label(54.9, 101.0, 100.0, false), "AVOID", "54.9 -> AVOID")
        T.eq(SetupScore.label(55.0, 90.0, 100.0, false), "WAIT", "55 -> WAIT")
        T.eq(SetupScore.label(40.0, 101.0, 100.0, false, 100.0), "WAIT", "round 5: low score above SMA200 -> WAIT")
        T.eq(SetupScore.label(40.0, 99.0, 100.0, false, 100.0), "AVOID", "round 5: low score below SMA200 -> AVOID")
        // upper-circuit detection and windows on a synthetic series
        val n = 300
        val c = DoubleArray(n) { 100.0 + it * 0.1 }
        val h = DoubleArray(n) { c[it] * 1.01 }
        val l = DoubleArray(n) { c[it] * 0.99 }
        val to = DoubleArray(n) { 30.0 }
        c[n - 1] = c[n - 2] * 1.10; h[n - 1] = c[n - 1]; l[n - 1] = c[n - 2]
        T.eq(SetupScore.compute(h, l, c, c[n - 1], to, n - 1, n).uclock, true, "+10% close at the high = upper-circuit lock")
        c[n - 1] = c[n - 2] * 1.07; h[n - 1] = c[n - 1]
        T.eq(SetupScore.compute(h, l, c, c[n - 1], to, n - 1, n).uclock, false, "+7% is not a circuit band")
        val k = SetupScore.compute(h, l, c, c[n - 1], to, n - 1, n)
        T.eq(k.r6, c[n - 22] / c[n - 127] - 1, "R6_1 = C[i-21]/C[i-126]-1")
        T.eq(k.r12, c[n - 22] / c[n - 253] - 1, "R12_1 = C[i-21]/C[i-252]-1")
        T.eq(k.hi252, h.copyOfRange(n - 252, n).max(), "HI252")
        T.check(SetupScore.compute(h, l, c, c[251], to, 251, 252).r12.isNaN() && SetupScore.compute(h, l, c, c[252], to, 252, 253).let { !it.r12.isNaN() && it.rateable }, "252 bars: no R12; 253: rated")
        T.check(SetupScore.compute(h, l, c, c[n - 1], to, n - 1, n).rateable && !SetupScore.compute(h, l, c, 40.0, to, n - 1, n).rateable, "price under 50 not rated")
        T.check(SetupScore.compute(h, l, c, 40.0, to, n - 1, n).let { it.lowPriceRateable && it.naKind() == null }, "liquid, full history, under Rs 50: scored by insertion")
        T.eq(SetupScore.compute(h, l, c, 40.0, to, 199, 200).naKind(), SetupScore.NA_CHEAP, "under Rs 50 with short history: CHEAP kind")
        T.eq(SetupScore.compute(doubleArrayOf(), doubleArrayOf(), doubleArrayOf(), 0.0, doubleArrayOf(), 5, 6).naKind(), SetupScore.NA_ILLIQUID, "bad index does not throw")
        // topBuys: order, ties, cap
        val many = (1..30).map { j -> String.format("S%02d", j) to hc(doubleArrayOf(j / 10.0, j / 10.0, j / 10.0, j / 10.0, j / 10.0)) } +
            (1..120).map { j -> String.format("F%03d", j) to hc(doubleArrayOf(-1.0, -1.0, -1.0, -1.0, -1.0)) } +
            listOf("S31" to hc(doubleArrayOf(3.0, 3.0, 3.0, 3.0, 3.0)), "S00" to hc(doubleArrayOf(3.0, 3.0, 3.0, 3.0, 3.0)))
        val tb = SetupScore.buildTable(many, 1L)
        val top = SetupScore.topBuys(tb, 15)
        T.eq(top.size, 15, "capped at 15")
        T.check(top.all { it.label == "BUY" } && top.zipWithNext().all { SetupScore.ORDER.compare(it.first, it.second) < 0 }, "BUY only, score desc then symbol")
        T.eq(top.take(3).map { it.sym }, listOf("S00", "S30", "S31"), "three-way tie at the top broken by symbol")
        val smallParams = Params.SCORE_MIN_RATED
        Params.SCORE_MIN_RATED = 1000
        T.eq(SetupScore.topBuys(tb, 15).size, 0, "no ideas when too few stocks are rated")
        Params.SCORE_MIN_RATED = smallParams
        // the call for each label from pure inputs
        for (e in listOf(tb.entries["S00"]!!, tb.entries["S10"]!!, tb.entries["F001"]!!)) {
            val m = BuyCall.call(e, e.comp, null, e.comp.close, e.comp.close, tb.rated, 100000.0, 0.75, 0.25)
            T.eq(m["label"], e.label, "call label ${e.sym}")
            T.check((m["reasons"] as List<*>).size in 3..6, "3-6 reasons")
        }
    }

    // ------------------------------------------------------------------ position size never risks more than riskPct incl. charges
    fun sizing() {
        val r = java.util.Random(7)
        var n = 0
        for (k in 0 until 20000) {
            val cap = 1000.0 + r.nextDouble() * 5e6
            val risk = 0.05 + r.nextDouble() * 4.95
            val cost = r.nextDouble() * 3
            val entry = Fmt.r2(5 + r.nextDouble() * 20000)
            val stop = Fmt.r2(entry * (0.5 + r.nextDouble() * 0.499))
            val q = SwingApp.positionSize(cap, risk, cost, entry, stop)
            if (q < 1) continue
            n++
            val lossInclCost = q * (entry - stop) + entry * q * cost / 100.0
            if (lossInclCost > cap * risk / 100.0 * (1 + 1e-9)) T.check(false, "risk $lossInclCost > ${cap * risk / 100} ($cap $risk $cost $entry $stop q=$q)")
            if (q * entry > cap * Params.MAX_POSITION_FRACTION * (1 + 1e-9)) T.check(false, "position over 25% of capital")
        }
        T.check(n > 5000, "sized $n random cases")
    }

    // ------------------------------------------------------------------ a scan over a 130-stock fake universe
    private val CALL_KEYS = setOf("label", "score", "headline", "reasons", "entryLow", "entryHigh", "stop", "t1", "t2", "stopPct", "t1Pct", "t2Pct", "rr",
        "horizonDays", "qty", "investRs", "riskRs", "hist", "waitFor", "caveat", "proven", "evidence", "band", "points", "exitRule")

    /** Platform with >= 100 rated stocks, identical twins, a 252-bar and a 200-bar stock, a cheap liquid stock. */
    fun bigPlatform(): FakePlatform {
        val p = FakePlatform.standard(100)
        val y = p.yahoo
        val extra = StringBuilder()
        y.profiles["TWINA"] = Profile(0.2, 1.0)
        y.profiles["TWINB"] = Profile(0.2, 1.0)
        y.profiles["S252"] = Profile(0.2, 1.0); y.shortHistory["S252"] = 252
        y.profiles["S200"] = Profile(0.2, 1.0); y.shortHistory["S200"] = 200
        y.profiles["CHEAPO"] = Profile(-0.05, 1.0, start = 20.0, baseVol = 4e7)
        for (s in listOf("TWINA", "TWINB", "S252", "S200", "CHEAPO")) extra.append(s).append(',').append(s).append(" Ltd\n")
        p.assets["universe.csv"] = p.assets["universe.csv"] + extra
        for (d in y.tradingDaysUpTo(y.baseDay + 1290, 2000)) { val b = y.bar("TWINA", d)!!; y.setBar("TWINB", d, b[0], b[1], b[2], b[3], b[4]) }
        return p
    }

    private fun naiveP(vals: List<Double>, x: Double): Double {
        val d = vals.filter { !it.isNaN() }
        return (d.count { it < x } + (d.count { it == x } + 1) / 2.0) / d.size
    }

    private fun checkCall(c: Map<String, Any?>, ltp: Double, settings: DoubleArray, where: String) {
        T.eq(c.keys, CALL_KEYS, "call keys $where")
        val lab = c.str("label")
        val rs = c.strList("reasons")
        T.check(rs.size in 3..6 && rs.all { it.isNotBlank() }, "3-6 reasons $where: $rs")
        T.check(c.str("headline").isNotBlank() && c.str("evidence").isNotBlank() && c.str("caveat").isNotBlank(), "texts present $where")
        T.eq(c.int("horizonDays"), 60, "horizon 60 $where")
        T.eq(c.obj("hist").int("horizonDays"), 60, "hist horizon $where")
        val pts = c.list("points").map { it.asObj() }
        T.eq(pts.map { it.str("label") }, ScoreTexts.POINT_LABELS, "5 points with plain labels $where")
        T.check(pts.all { it.num("max") == 20.0 && it.num("pts") in 0.0..20.0 }, "pts within 0..20 $where")
        val low = Json.stringify(c).lowercase()
        for (w in BANNED) T.check(!low.contains(w), "banned '$w' in call $where")
        T.eq(c.bool("proven"), lab == "AVOID", "proven only for AVOID $where")
        if (lab == "NA") {
            T.check(c.num("score") == 0.0 && c.str("band") == "" && pts.all { it.num("pts") == 0.0 } && c.num("stop") == 0.0 && c.num("entryHigh") == 0.0 && c.int("qty") == 0 && c.obj("hist").int("n") == 0, "NA has zeros $where")
            return
        }
        T.check(Math.abs(pts.sumOf { it.num("pts") } - c.num("score")) <= 0.31, "points add up to the score $where")
        T.eq(c.str("band"), SetupScore.band(c.num("score")), "band $where")
        T.eq(c.obj("hist").int("n"), ScoreHistory.band(c.str("band"))!!.n, "hist from the band table $where")
        T.check(c.obj("hist").str("text").contains("statistically reliable"), "hist text speaks to reliability $where")
        if (lab == "AVOID") {
            T.check(listOf("entryLow", "entryHigh", "stop", "t1", "t2", "stopPct", "t1Pct", "t2Pct", "rr", "investRs", "riskRs").all { c.num(it) == 0.0 } && c.int("qty") == 0, "AVOID: no levels $where")
            T.check(c.str("evidence").contains("200-day average") && c.num("score") < 55, "AVOID evidence and score $where")
            return
        }
        val lo = c.num("entryLow"); val hi = c.num("entryHigh"); val sl = c.num("stop"); val t1 = c.num("t1"); val t2 = c.num("t2")
        T.check(sl > 0 && sl < lo && lo < hi && hi < t1 && t1 < t2, "levels ordered $where: $sl $lo $hi $t1 $t2")
        val atr = (t2 - lo) / 9
        T.check(Math.abs((hi - lo) - 0.5 * atr) < 0.02 && Math.abs((lo - sl) - SetupScore.STOP_ATR * atr) < 0.02 && Math.abs((t1 - lo) - 6 * atr) < 0.02, "levels = C + 0.5/-5/+6/+9 ATR $where")
        T.near(c.num("stopPct"), Fmt.r2((sl / ltp - 1) * 100), 0.006, "stopPct from ltp $where")
        T.near(c.num("t1Pct"), Fmt.r2((t1 / ltp - 1) * 100), 0.006, "t1Pct from ltp $where")
        if (ltp > sl && t1 > ltp) T.near(c.num("rr"), Fmt.r2((t1 - ltp) / (ltp - sl)), 0.006, "rr $where")
        T.eq(c.str("exitRule"), ScoreTexts.EXIT_RULE, "exit rule $where")
        if (lab == "WAIT") {
            T.check(c.str("waitFor").isNotBlank() && c.int("qty") == 0, "WAIT says what it waits for, no size $where: ${c.str("waitFor")}")
            T.check(c.str("evidence").startsWith("WAIT is not a buy signal"), "WAIT evidence $where")
        } else {
            T.check(c.num("score") >= 85 && c.str("waitFor") == "", "BUY score $where")
            T.check(c.str("evidence") == R5.BUY_EVIDENCE, "BUY evidence $where")
            val q = c.int("qty")
            val want = SwingApp.positionSize(settings[0], settings[1], settings[2], hi, sl)
            T.eq(q, want, "BUY qty from the user's settings $where")
            if (q > 0) {
                T.check(c.num("riskRs") <= settings[0] * settings[1] / 100.0 + 0.01, "BUY risk within riskPct incl. charges $where: ${c.num("riskRs")}")
                T.check(c.num("investRs") <= settings[0] * Params.MAX_POSITION_FRACTION + 0.01, "BUY under 25% of capital $where")
                T.near(c.num("riskRs"), Fmt.r2(q * (hi - sl) + hi * q * settings[2] / 100.0), 0.011, "riskRs incl. charges $where")
            }
        }
    }

    fun scan() {
        val p = bigPlatform()
        val d0 = day(2026, 10, 7)
        p.setTime(d0, 15 * 60 + 45)
        val app = SwingApp(p)
        // no table yet
        var r = V2Tests.info(app, "UP01", "before any scan")
        T.check(r.obj("call").str("label") == "NA" && r.obj("call").str("headline").contains("full scan"), "before the first scan: NA, needs a full scan: " + r.obj("call").str("headline"))
        T.eq(SimTests.state(app, "before scan").list("ideas").size, 0, "ideas empty before the first scan")
        T.eq(act(app, "scanNow", "{}")["ok"], true, "manual scan after the close")
        val t = app.debugScoreTable()!!
        println("      table: ${t.entries.size} stocks, U=${t.universeN}, rated=${t.rated}")
        T.check(t.usable && t.rated >= 100, "at least 100 rated")
        val members = t.entries.values.filter { it.comp.inUniverse }
        T.eq(members.size, t.universeN, "U count")
        T.check(t.entries["S252"]!!.let { it.label == "NA" && it.comp.inUniverse && it.comp.r12.isNaN() && !it.comp.ram.isNaN() }, "252-bar stock: in U, contributes, not rated")
        T.eq(t.cols[2].size, t.universeN - members.count { it.comp.r12.isNaN() }, "undefined R12 left out of that column only")
        T.eq(t.cols[0].size, members.count { !it.comp.ram.isNaN() }, "RAM column")
        // every rated entry against a naive O(n^2) recomputation
        var bad = 0
        for (e in t.ranked) {
            for (k in 0 until 5) if (e.p[k] != naiveP(members.map { it.comp.x(k) }, e.comp.x(k))) bad++
            if (e.score != Math.rint((100.0 * (e.p[0] + e.p[1] + e.p[2] + e.p[3] + e.p[4])) / 5.0 * 10.0) / 10.0) bad++
            if (e.label != SetupScore.label(e.score, e.comp.close, e.comp.sma50, e.comp.uclock, e.comp.sma200)) bad++
        }
        T.eq(bad, 0, "table percentiles/scores/labels = naive recomputation over ${t.rated} stocks")
        val a = t.entries["TWINA"]!!; val b = t.entries["TWINB"]!!
        T.check(a.rated && (0 until 5).all { a.p[it] == b.p[it] } && a.score == b.score && b.rank == a.rank + 1, "identical twins tie everywhere; TWINA ranks just above TWINB")
        T.check((0 until 5).all { k -> a.p[k] == naiveP(members.map { it.comp.x(k) }, a.comp.x(k)) && members.count { it.comp.x(k) == a.comp.x(k) } >= 2 }, "tied values share the average rank")
        T.eq(t.ranked.map { it.rank }, (1..t.rated).toList(), "ranks 1..M")
        T.check(t.ranked.zipWithNext().all { SetupScore.ORDER.compare(it.first, it.second) < 0 }, "ranked by score then symbol")
        // the comps the app used = the production compute on the same bars (via getChart's full history)
        for (sym in listOf("UP07", "DN02", "TWINA", "M&M")) {
            val ch = act(app, "getChart", """{"sym":"$sym","bars":5000}""")
            val n = ch.list("c").size
            val s = Series(sym, n)
            for (k in 0 until n) s.add(Clock.epochDay((ch.list("d")[k] as Double).toLong()), ch.list("o")[k] as Double, ch.list("h")[k] as Double, ch.list("l")[k] as Double, ch.list("c")[k] as Double, ch.list("v")[k] as Double)
            val c = SetupScore.compute(s)
            val e = t.entries[sym]!!
            T.check(c.ram == e.comp.ram && c.r6 == e.comp.r6 && c.r12 == e.comp.r12 && c.prox52 == e.comp.prox52 && c.low52 == e.comp.low52 && c.atr14 == e.comp.atr14 && c.sma50 == e.comp.sma50, "$sym: components from its own bars")
        }
        // ideas = top BUYs of the table
        val st = SimTests.state(app, "after scan")
        val ideas = st.list("ideas").map { it.asObj() }
        val buys = SetupScore.topBuys(t, 15)
        T.check(buys.isNotEmpty(), "the fake universe has BUY candidates (${buys.size})")
        T.eq(ideas.map { it.str("sym") }, buys.map { it.sym }, "ideas = top BUYs, score desc then symbol")
        T.check(ideas.size <= 15 && ideas.all { it.str("label") == "BUY" && it.num("score") >= 85 && !it.bool("proven") && it.int("horizonDays") == 60 }, "ideas: BUY, >= 85, proven false, 60 days")
        T.check(ideas.zipWithNext().all { it.first.num("score") >= it.second.num("score") }, "ideas sorted by score")
        for (x in ideas) {
            val e = t.entries[x.str("sym")]!!
            val lv = BuyCall.levels(e)
            T.check(x.num("entryLow") == lv[0] && x.num("entryHigh") == lv[1] && x.num("stop") == lv[2] && x.num("t1") == lv[3] && x.num("t2") == lv[4], "idea levels ${x.str("sym")}")
            T.check(x.list("spark").size == 30 && x.int("barDay") == StockPage.yyyymmdd(d0) && x.num("ltp") == Fmt.r2(e.comp.close) && x.str("headline").isNotBlank() && x.str("name").isNotBlank(), "idea content ${x.str("sym")}")
            T.near(x.num("rr"), 1.2, 0.011, "rr from the close = 6/5 (5-ATR stop)")
        }
        // stockInfo for every universe stock: the call matches its table entry; all four labels appear
        val settings = doubleArrayOf(Params.DEF_CAPITAL, Params.DEF_RISK_PCT, Params.DEF_COST_PCT)
        val seen = HashMap<String, Int>()
        for (sym in t.entries.keys.sorted()) {
            r = V2Tests.info(app, sym, "after scan")
            if (r["ok"] != true) continue
            val c = r.obj("call")
            checkCall(c, r.num("ltp"), settings, sym)
            val e = t.entries[sym]!!
            T.eq(c.str("label"), e.label, "$sym call label = table")
            if (e.rated) T.eq(c.num("score"), Fmt.r1(e.score), "$sym score = table")
            if (sym == "CHEAPO") T.check(e.rated && c.list("reasons").any { it.toString().contains("under ₹50") } && t.ranked.none { it.sym == sym }, "under Rs 50: scored, caveat shown, kept out of the ranked list")
            seen[c.str("label")] = (seen[c.str("label")] ?: 0) + 1
        }
        println("      calls by label: $seen")
        for (lab in listOf("BUY", "WAIT", "AVOID", "NA")) T.check((seen[lab] ?: 0) > 0, "some $lab call")
        // WAIT texts
        val w85 = t.ranked.firstOrNull { it.label == "WAIT" && it.score >= 85 }
        if (w85 != null) T.check(V2Tests.info(app, w85.sym, "wait85").obj("call").str("waitFor").contains("50-day average"), "WAIT >= 85: waits for a close above the 50-day average")
        val wMid = t.ranked.first { it.label == "WAIT" && it.score < 85 }
        T.check(V2Tests.info(app, wMid.sym, "waitMid").obj("call").str("waitFor").contains("ranked " + wMid.rank + " of " + t.rated), "WAIT 55-84: says the rank")
        // the user's settings size BUYs
        T.eq(act(app, "saveSettings", """{"capital":500000,"riskPct":1.0,"costPct":0.5}""")["ok"], true, "settings")
        val bsym = buys.first().sym
        r = V2Tests.info(app, bsym, "big capital")
        checkCall(r.obj("call"), r.num("ltp"), doubleArrayOf(500000.0, 1.0, 0.5), "$bsym with new settings")
        T.check(r.obj("call").int("qty") > 0, "BUY sized")
        // a non-universe stock is ranked into the table by insertion
        r = V2Tests.info(app, "XTRA1", "non-universe")
        val c = r.obj("call")
        val ch = act(app, "getChart", """{"sym":"XTRA1","bars":5000}""")
        val n = ch.list("c").size
        val s = Series("XTRA1", n)
        for (k in 0 until n) s.add(Clock.epochDay((ch.list("d")[k] as Double).toLong()), ch.list("o")[k] as Double, ch.list("h")[k] as Double, ch.list("l")[k] as Double, ch.list("c")[k] as Double, ch.list("v")[k] as Double)
        val xc = SetupScore.compute(s)
        T.check(xc.rateable, "XTRA1 is rateable")
        val ps = DoubleArray(5) { k -> naiveP(members.map { it.comp.x(k) } + xc.x(k), xc.x(k)) }
        val sc = Math.rint((100.0 * (ps[0] + ps[1] + ps[2] + ps[3] + ps[4])) / 5.0 * 10.0) / 10.0
        T.eq(c.num("score"), sc, "XTRA1 score = naive percentile with it added to M")
        T.eq(c.str("label"), SetupScore.label(sc, xc.close, xc.sma50, xc.uclock, xc.sma200), "XTRA1 label")
        T.check(c.list("points").map { it.asObj().num("pts") } == ps.map { Fmt.r1(20 * it) }, "XTRA1 points = 20 x percentile")
        checkCall(c, r.num("ltp"), doubleArrayOf(500000.0, 1.0, 0.5), "XTRA1")
        T.check(!t.entries.containsKey("XTRA1"), "XTRA1 is not part of the universe table")
    }

    fun naPaths() {
        // too few rated stocks (the standard 47-stock universe)
        val p = FakePlatform.standard()
        val d0 = day(2026, 10, 7)
        p.setTime(d0, 10 * 60 + 1)
        val app = SwingApp(p)
        app.tick("alarm")
        val t = app.debugScoreTable()!!
        T.check(t.rated in 1 until Params.SCORE_MIN_RATED && !t.usable, "small universe: ${t.rated} rated (< 100)")
        val c = V2Tests.info(app, "UP01", "few rated").obj("call")
        T.check(c.str("label") == "NA" && c.str("headline").contains("full scan") && c.strList("reasons").any { it.contains("only " + t.rated) }, "fewer than 100 rated: NA, says so: " + c.strList("reasons"))
        T.eq(SimTests.state(app, "few rated").list("ideas").size, 0, "no ideas from an unusable table")
        // intrinsic NA reasons work without any table
        val big = bigPlatform()
        big.setTime(d0, 10 * 60 + 1)
        val a2 = SwingApp(big)
        val thin = V2Tests.info(a2, "THIN", "thin").obj("call")
        T.check(thin.str("label") == "NA" && thin.str("headline").contains("thinly traded"), "illiquid: NA: " + thin.str("headline"))
        val short = V2Tests.info(a2, "S200", "short").obj("call")
        T.check(short.str("label") == "NA" && short.str("headline").contains("253"), "under 253 bars: NA: " + short.str("headline"))
        val s252 = V2Tests.info(a2, "S252", "252").obj("call")
        T.check(s252.str("label") == "NA" && s252.str("headline").contains("has 252"), "exactly 252 bars: NA: " + s252.str("headline"))
        val ipo = V2Tests.info(a2, "NEWIPO", "ipo").obj("call")
        T.eq(ipo.str("label"), "NA", "30-bar listing")
        val ch = V2Tests.info(a2, "CHEAPO", "cheap")
        if (ch.num("ltp") < 50) {
            val cc = ch.obj("call")
            T.check(cc.str("label") == "NA" && cc.str("headline").contains("full scan"), "liquid but under Rs 50, before the first scan: needs a full scan: " + cc.str("headline"))
        }
        T.check(V2Tests.info(a2, "UP01", "no table").obj("call").str("headline").contains("full scan"), "liquid stock before the first scan: needs a full scan")
        for (x in listOf(thin, short, s252, ipo)) checkCall(x, 1.0, doubleArrayOf(1e5, 0.75, 0.25), "NA")
    }

    // ------------------------------------------------------------------ user targets
    private fun row(app: SwingApp, sym: String) = SimTests.state(app, "targets").list("targets").map { it.asObj() }.firstOrNull { it.str("sym") == sym }

    fun targets() {
        val savedFull = Params.FULL_QUOTE_MAX_UNIVERSE
        Params.FULL_QUOTE_MAX_UNIVERSE = 0     // tiered quotes outside the buy window
        try {
            val p = FakePlatform.standard()
            val y = p.yahoo
            val d0 = day(2026, 10, 6)
            p.setTime(d0, 10 * 60 + 1)
            var app = SwingApp(p)
            app.tick("alarm")
            // validation
            for (bad in listOf("""{"sym":"UP01"}""", """{"sym":"UP01","target":0,"stop":0}""", """{"sym":"UP01","target":-5}""", """{"sym":"UP01","target":"abc"}""",
                    """{"sym":"A B","target":10}""", """{"sym":"","target":10}""", """{"sym":"UP01","target":1e9}""", "garbage"))
                T.eq(act(app, "setTarget", bad)["ok"], false, "rejected: $bad")
            T.eq(act(app, "setTarget", """{"sym":"xtra1","target":1,"stop":null,"note":"  first  "}""")["ok"], true, "non-universe symbol accepted (lower case)")
            var x = row(app, "XTRA1")!!
            val ref = x.num("refPrice")
            T.check(ref > 0 && x.num("ltp") == ref && x.str("status") == "ACTIVE" && x.long("setAt") == p.nowMs && x.str("note") == "first", "refPrice = last price at set time: $x")
            // replace: one row per symbol
            T.eq(act(app, "setTarget", """{"sym":"XTRA1","target":${Fmt.r2(ref * 1.02)},"stop":${Fmt.r2(ref * 0.97)},"note":"${"n".repeat(200)}"}""")["ok"], true, "replace")
            x = row(app, "XTRA1")!!
            T.check(SimTests.state(app, "one row").list("targets").count { it.asObj().str("sym") == "XTRA1" } == 1 && x.num("target") == Fmt.r2(ref * 1.02) && x.str("note").length == 80, "replaced, note capped at 80")
            T.near(x.num("toTargetPct"), Fmt.r2((Fmt.r2(ref * 1.02) / ref - 1) * 100), 0.011, "toTargetPct")
            T.near(x.num("toStopPct"), Fmt.r2((Fmt.r2(ref * 0.97) / ref - 1) * 100), 0.011, "toStopPct")
            T.eq(x.num("progressPct"), 0.0, "progress 0 at the start")
            val mt = V2Tests.info(app, "XTRA1", "myTarget").obj("myTarget")
            T.check(mt.num("target") == Fmt.r2(ref * 1.02) && mt.num("stop") == Fmt.r2(ref * 0.97) && mt.str("status") == "ACTIVE" && mt.str("note").length == 80, "stockInfo.myTarget: $mt")
            T.eq(V2Tests.info(app, "UP01", "no target").obj("myTarget"), mapOf("target" to 0.0, "stop" to 0.0, "note" to "", "status" to ""), "myTarget zeros when none")
            T.eq(act(app, "setTarget", """{"sym":"UP03","stop":10}""")["ok"], true, "stop only")
            // quotes for target symbols in a non-full scan
            p.keepUrls = true; p.urls.clear()
            p.setTime(d0, 10 * 60 + 16); app.tick("alarm")
            T.check(p.urls.any { it.contains("/XTRA1.NS?range=5d") } && p.urls.any { it.contains("/UP03.NS?range=5d") } && p.urls.none { it.contains("/UP04.NS?range=5d") }, "target symbols quoted in a non-full scan")
            T.check(p.files.entries.any { it.key.startsWith("daily_cache_") && it.value.contains("\"XTRA1\"") }, "target history kept in the file cache")
            T.check(row(app, "XTRA1")!!.num("ltp") > 0, "row price updated by the scan")
            // targets set after the close on d0; prices on d1 are scripted flat
            p.setTime(d0, 15 * 60 + 45); app.tick("manual")
            val P = HashMap<String, Double>()
            val plan = linkedMapOf("UP02" to "up-target", "UP05" to "down-target", "UP06" to "stop-below", "UP07" to "stop-above", "UP08" to "no-hit", "UP09" to "muted")
            for (sym in plan.keys) P[sym] = Fmt.r2(y.bar(sym, d0)!![3])
            fun set(sym: String, tg: Double, sp: Double) = T.eq(act(app, "setTarget", """{"sym":"$sym","target":${Fmt.r2(tg)},"stop":${Fmt.r2(sp)}}""")["ok"], true, "set $sym")
            p.setTime(d0, 15 * 60 + 50)
            set("UP02", P["UP02"]!! * 1.03, P["UP02"]!! * 0.9)
            set("UP05", P["UP05"]!! * 0.96, 0.0)
            set("UP06", 0.0, P["UP06"]!! * 0.97)
            set("UP07", 0.0, P["UP07"]!! * 1.03)
            set("UP08", P["UP08"]!! * 1.10, P["UP08"]!! * 0.90)
            for (sym in plan.keys.filter { it != "UP09" }) T.eq(row(app, sym)!!.num("refPrice"), P[sym]!!, "$sym refPrice = the close")
            val d1 = y.nextTradingDay(d0)
            fun flat(sym: String, f: Double) { val v = P[sym]!! * f; y.setBar(sym, d1, v, v, v, v, 3e6) }
            flat("UP02", 1.05); flat("UP05", 0.95); flat("UP06", 0.95); flat("UP07", 1.05); flat("UP08", 1.0)
            // a repeat tick on d0 (old prices, set before) fires nothing
            app.tick("manual")
            T.check(plan.keys.all { row(app, it)?.str("status") ?: "ACTIVE" == "ACTIVE" }, "no hits from prices older than the target")
            val notes0 = p.notes.size
            val feed0 = SimTests.state(app, "feed0").list("feed").size
            p.setTime(d1, 9 * 60 + 31); app.tick("manual")
            val st = SimTests.state(app, "hits")
            fun st(sym: String) = st.list("targets").map { it.asObj() }.first { it.str("sym") == sym }
            T.eq(st("UP02").str("status"), "HIT_TARGET", "upside target hit (>=)")
            T.eq(st("UP05").str("status"), "HIT_TARGET", "downside 'falls to' target hit (<=)")
            T.eq(st("UP06").str("status"), "HIT_STOP", "stop below hit (<=)")
            T.eq(st("UP07").str("status"), "HIT_STOP", "stop above the reference hit (>=)")
            T.eq(st("UP08").str("status"), "ACTIVE", "inside the range: still active")
            T.check(st("UP02").long("hitAt") == p.nowMs && st("UP02").num("progressPct") == 100.0 && st("UP08").long("hitAt") == 0L, "hitAt and progress")
            T.near(st("UP08").num("progressPct"), 0.0, 0.01, "no progress at the reference price")
            val hitsNotes = p.notes.drop(notes0).filter { it.title.contains("hit your") }
            T.eq(hitsNotes.map { it.title.substringBefore(" ") }.sorted(), listOf("UP02", "UP05", "UP06", "UP07"), "one notification per hit")
            T.check(hitsNotes.all { it.channel == "sell" } && hitsNotes.first { it.title.startsWith("UP02") }.title == "UP02 hit your target " + Fmt.rs(Fmt.r2(P["UP02"]!! * 1.03)), "channel sell, title: " + hitsNotes.map { it.title })
            T.check(hitsNotes.map { it.id }.toSet().size == 4 && hitsNotes.first { it.title.startsWith("UP02") }.id == ("target:UP02".hashCode() and 0x7fffffff), "stable id per symbol")
            T.eq(st.list("feed").size - feed0, 4, "four feed entries")
            // idempotent: more ticks, a restart, more ticks
            p.setTime(d1, 9 * 60 + 46); app.tick("alarm")
            p.setTime(d1, 10 * 60 + 1); app.tick("manual")
            app = SwingApp(p)
            T.check(SimTests.state(app, "restart").list("targets").map { it.asObj() }.filter { it.str("status") != "ACTIVE" }.map { it.str("sym") }.sorted() == listOf("UP02", "UP05", "UP06", "UP07"), "HIT rows persist across a restart")
            p.setTime(d1, 10 * 60 + 16); app.tick("alarm")
            p.setTime(d1 + 1, 10 * 60 + 1); app.tick("manual")
            T.eq(p.notes.drop(notes0).count { it.title.contains("hit your") }, 4, "still exactly one notification per hit")
            T.eq(SimTests.state(app, "after").list("feed").map { it.asObj() }.count { it.str("title").contains("hit your") }, 4, "still exactly one feed entry per hit")
            // notifySell off: a hit still gets its feed entry, but no phone notification
            T.eq(act(app, "saveSettings", """{"notifySell":false}""")["ok"], true, "mute sell")
            p.setTime(d1 + 1, 10 * 60 + 5)
            T.eq(act(app, "setTarget", """{"sym":"UP09","target":1}""")["ok"], true, "UP09 target (to learn its price)")
            val ref9 = row(app, "UP09")!!.num("refPrice")
            T.eq(act(app, "setTarget", """{"sym":"UP09","target":${Fmt.r2(ref9 * 1.03)}}""")["ok"], true, "muted target")
            val dY = y.nextTradingDay(d1 + 1)
            y.setBar("UP09", dY, ref9 * 1.05, ref9 * 1.05, ref9 * 1.05, ref9 * 1.05, 3e6)
            p.setTime(dY, 9 * 60 + 31); app.tick("manual")
            T.eq(row(app, "UP09")!!.str("status"), "HIT_TARGET", "muted target hit")
            T.check(p.notes.none { it.title.startsWith("UP09") }, "no notification while notifySell is off")
            T.check(SimTests.state(app, "muted").list("feed").map { it.asObj() }.count { it.str("title").startsWith("UP09 hit your target") && it.str("kind") == "sell" } == 1, "one feed entry even when muted")
            T.eq(act(app, "saveSettings", """{"notifySell":true}""")["ok"], true, "unmute")
            // a HIT row is replaced by a new ACTIVE one
            T.eq(act(app, "setTarget", """{"sym":"UP02","target":99999}""")["ok"], true, "re-set after a hit")
            T.check(row(app, "UP02")!!.let { it.str("status") == "ACTIVE" && it.long("hitAt") == 0L && it.num("stop") == 0.0 }, "new ACTIVE row")
            // clear
            T.eq(act(app, "clearTarget", """{"sym":"UP05"}""")["ok"], true, "clearTarget")
            T.eq(row(app, "UP05"), null, "row gone")
            T.eq(act(app, "clearTarget", """{"sym":"UP05"}""")["ok"], true, "clearing again is fine")
            T.eq(V2Tests.info(app, "UP05", "cleared").obj("myTarget").str("status"), "", "myTarget empty after clear")
            // unknown price: accepted with refPrice 0, reference taken at the first quote
            T.eq(act(app, "setTarget", """{"sym":"NEWCO","target":500}""")["ok"], true, "unknown symbol accepted")
            T.eq(row(app, "NEWCO")!!.num("refPrice"), 0.0, "refPrice 0")
            y.profiles["NEWCO"] = Profile(0.1, 1.0)
            p.setTime(dY, 10 * 60 + 16); app.tick("alarm")
            x = row(app, "NEWCO")!!
            T.check(x.num("refPrice") > 0 && x.num("refPrice") == x.num("ltp") && x.str("status") == "ACTIVE", "reference from the first quote, no hit: $x")
            // persistence
            val saved = Json.parseObject(p.files[SwingApp.STATE_FILE])!!
            T.check(saved.list("targets").size == SimTests.state(app, "n").list("targets").size, "persisted as 'targets'")
            app = SwingApp(p)
            T.eq(row(app, "NEWCO")!!.num("refPrice"), x.num("refPrice"), "restart keeps refPrice")
            // cap of 50
            val have = SimTests.state(app, "cap").list("targets").size
            for (k in have + 1..50) T.eq(act(app, "setTarget", """{"sym":"W$k","target":10}""")["ok"], true, "add W$k")
            val full = act(app, "setTarget", """{"sym":"W51","target":10}""")
            T.check(full["ok"] == false && full.str("error").contains("50"), "51st refused: $full")
            T.eq(act(app, "setTarget", """{"sym":"W50","target":11}""")["ok"], true, "replacing one when full is fine")
            T.eq(SimTests.state(app, "50").list("targets").size, 50, "50 rows")
            // old state files and junk
            val m = LinkedHashMap(Json.parseObject(p.files[SwingApp.STATE_FILE])!!)
            m.remove("targets")
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            T.check(SimTests.state(SwingApp(p), "old file").list("targets").isEmpty(), "old state file: no targets")
            m["targets"] = listOf(null, 5, "x", mapOf("sym" to "UP01"), mapOf("sym" to "A B", "target" to 5), mapOf("sym" to "UP01", "target" to -1),
                mapOf("sym" to "up01", "target" to 10, "status" to "WEIRD", "hitAt" to 99), mapOf("sym" to "UP01", "target" to 20), mapOf("sym" to "UP02", "stop" to "7", "refPrice" to "nan"))
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            val rows = SimTests.state(SwingApp(p), "junk").list("targets").map { it.asObj() }
            T.eq(rows.map { it.str("sym") }, listOf("UP01", "UP02"), "junk dropped, duplicates dropped")
            T.check(rows[0].str("status") == "ACTIVE" && rows[0].long("hitAt") == 0L && rows[0].num("target") == 10.0 && rows[1].num("stop") == 7.0 && rows[1].num("refPrice") == 0.0, "tolerant values: $rows")
            m["targets"] = "nope"
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            T.eq(SimTests.state(SwingApp(p), "not a list").list("targets").size, 0, "non-list ignored")
        } finally {
            Params.FULL_QUOTE_MAX_UNIVERSE = savedFull
        }
    }

    // ------------------------------------------------------------------ reads while the table is republished
    fun concurrent() {
        val p = bigPlatform()
        p.setTime(day(2026, 10, 7), 10 * 60 + 1)
        val app = SwingApp(p)
        T.eq(act(app, "scanNow", "{}")["ok"], true, "first scan")
        T.check(app.debugScoreTable()!!.usable, "table usable")
        p.httpDelayMs = 5
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        val tables = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ScoreTable, Boolean>()))
        fun check(name: String, txt: String, needCall: Boolean) {
            val o = Json.parseObject(txt)
            if (o == null || o["ok"] !is Boolean) errs.add("$name: bad result " + txt.take(80))
            else if (needCall && o["ok"] == true && (o.obj("call").keys != CALL_KEYS || o.obj("myTarget").size != 4)) errs.add("$name: call missing " + txt.take(80))
            if (txt.contains("Something went wrong") || txt.contains("NaN") || txt.contains("Infinity")) errs.add("$name: " + txt.take(120))
        }
        val syms = listOf("UP01", "UP50", "XTRA1", "TWINA", "THIN", "S252", "DN03", "NOSUCHX", "UP99", "M&M")
        val threads = listOf(
            Thread { for (k in 0 until 3) { val r = app.tick("manual"); if (r.startsWith("Error")) errs.add("tick: $r"); app.debugScoreTable()?.let { tables.add(it) } } },
            Thread { for (k in 0 until 30) check("stockInfo", app.action("stockInfo", """{"sym":"${syms[k % syms.size]}"}"""), true) },
            Thread { for (k in 0 until 30) check("stockInfo2", app.action("stockInfo", """{"sym":"${syms[(k + 5) % syms.size]}"}"""), true) },
            Thread { for (k in 0 until 20) { check("setTarget", app.action("setTarget", """{"sym":"${syms[k % 4]}","target":${100 + k}}"""), false); check("clearTarget", app.action("clearTarget", """{"sym":"${syms[(k + 1) % 4]}"}"""), false) } },
            Thread { for (k in 0 until 200) { val o = Json.parseObject(app.stateJson()); if (o == null || o["ideas"] !is List<*> || o["targets"] !is List<*>) errs.add("stateJson: bad"); Thread.sleep(2) } }
        )
        threads.forEach { it.start() }
        threads.forEach { it.join(120_000) }
        p.httpDelayMs = 0
        T.check(threads.none { it.isAlive }, "all threads finished")
        T.check(errs.isEmpty(), "no errors under concurrency: " + errs.take(3))
        T.check(tables.size >= 2, "the table was republished during the reads (${tables.size} tables)")
        SimTests.state(app, "after concurrency")
    }
}
