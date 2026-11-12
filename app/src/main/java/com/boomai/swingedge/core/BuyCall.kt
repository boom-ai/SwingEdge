package com.boomai.swingedge.core

/** Builds the stockInfo `call` object (SPEC3 §1 + v3.1) and the `ideas` rows (§2) from a [ScoreEntry]. Pure; never throws. */
object BuyCall {

    /**
     * [e] = the stock's score entry (label NA when not rateable); [naKind] = why it is NA when [e] is null or NA
     * (ScoreTexts.NA_NO_TABLE when no usable ranking exists). [ltp] = the price the page shows; [pageDay]/[pageClose] = its
     * latest bar (to say when the score is from an older scan bar). [settings] = capital, riskPct, costPct.
     */
    fun call(e: ScoreEntry?, comp: ScoreComp, naKind: String?, ltp: Double, pageClose: Double, ratedInTable: Int,
             capital: Double, riskPct: Double, costPct: Double): Map<String, Any?> {
        try {
            if (e == null || !e.rated || naKind != null) {
                val kind = naKind ?: comp.naKind() ?: ScoreTexts.NA_NO_TABLE
                return na(comp, kind, ratedInTable)
            }
            return rated(e, ltp, pageClose, capital, riskPct, costPct)
        } catch (t: Throwable) {
            return na(comp, ScoreTexts.NA_NO_TABLE, ratedInTable)
        }
    }

    private fun points(p: DoubleArray?): List<Map<String, Any?>> = ScoreTexts.POINT_LABELS.mapIndexed { k, lab ->
        val v = if (p == null || p[k].isNaN()) 0.0 else Fmt.r1(20.0 * p[k])
        linkedMapOf<String, Any?>("label" to lab, "pts" to v, "max" to 20.0)
    }

    private fun hist(b: ScoreHistory.Band?): Map<String, Any?> =
        if (b == null) linkedMapOf("n" to 0, "winPct" to 0.0, "avgPct" to 0.0, "medianPct" to 0.0, "worstPct" to 0.0, "horizonDays" to ScoreHistory.HORIZON_DAYS, "text" to ScoreTexts.HIST_NA)
        else linkedMapOf("n" to b.n, "winPct" to b.winPct, "avgPct" to b.avgPct, "medianPct" to b.medianPct, "worstPct" to b.worstPct,
            "horizonDays" to ScoreHistory.HORIZON_DAYS, "text" to ScoreTexts.histText(b))

    private fun base(label: String, score: Double, headline: String, reasons: List<String>): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["label"] = label; m["score"] = score; m["headline"] = headline; m["reasons"] = reasons
        m["entryLow"] = 0.0; m["entryHigh"] = 0.0; m["stop"] = 0.0; m["t1"] = 0.0; m["t2"] = 0.0
        m["stopPct"] = 0.0; m["t1Pct"] = 0.0; m["t2Pct"] = 0.0; m["rr"] = 0.0
        m["horizonDays"] = SetupScore.HORIZON_DAYS
        m["qty"] = 0; m["investRs"] = 0.0; m["riskRs"] = 0.0
        return m
    }

    private fun na(comp: ScoreComp, kind: String, ratedInTable: Int): Map<String, Any?> {
        val m = base("NA", 0.0, ScoreTexts.naHeadline(kind, comp), ScoreTexts.naReasons(kind, comp, ratedInTable))
        m["hist"] = hist(null); m["waitFor"] = ""; m["caveat"] = ScoreTexts.CAVEAT
        m["proven"] = false; m["evidence"] = ScoreTexts.evidence("NA", null); m["band"] = ""; m["points"] = points(null); m["exitRule"] = ""
        return m
    }

    /** Levels of a rated entry, rounded to the paisa: [entryLow, entryHigh, stop, t1, t2]. */
    fun levels(e: ScoreEntry): DoubleArray {
        val c = e.comp.close
        val lv = SetupScore.levels(c, e.comp.atr14)
        return doubleArrayOf(Fmt.r2(c), Fmt.r2(lv[0]), Fmt.r2(lv[1]), Fmt.r2(lv[2]), Fmt.r2(lv[3]))
    }

    /** Reward-to-risk of t1 against the stop, both measured from [px]; 0 when not meaningful. */
    fun rr(px: Double, stop: Double, t1: Double): Double = if (px > stop && t1 > px && stop > 0) Fmt.r2((t1 - px) / (px - stop)) else 0.0

    fun reasons(e: ScoreEntry, stale: Boolean): List<String> {
        val c = e.comp
        val out = ArrayList<String>()
        if (stale) out.add(ScoreTexts.staleLine(c.close, c.day))
        if (c.lowPriceRateable) out.add(ScoreTexts.LOW_PRICE_LINE)
        if (c.uclock && e.score >= SetupScore.BUY_MIN) out.add(ScoreTexts.UCLOCK_LINE)
        if (!c.sma50.isNaN() && c.sma50 > 0) out.add(ScoreTexts.trendLine(c.close, c.sma50))
        // components: strongest first, weakest first for AVOID ("why it fails")
        val ks = (0 until 5).sortedWith(compareBy<Int> { if (e.label == "AVOID") e.p[it] else -e.p[it] }.thenBy { it })
        for (k in ks) { if (out.size >= 6) break; out.add(ScoreTexts.componentLine(k, c, e.p[k])) }
        return out
    }

    private fun rated(e: ScoreEntry, ltp: Double, pageClose: Double, capital: Double, riskPct: Double, costPct: Double): Map<String, Any?> {
        val c = e.comp
        val stale = pageClose > 0 && Math.abs(pageClose - c.close) >= 0.005
        val m = base(e.label, Fmt.r1(e.score), ScoreTexts.headline(e.label, e.score, c.close > c.sma50, c.uclock), reasons(e, stale))
        val b = ScoreHistory.band(e.band)
        if (e.label != "AVOID") {
            val lv = levels(e)
            val px = if (ltp > 0) ltp else lv[0]
            m["entryLow"] = lv[0]; m["entryHigh"] = lv[1]; m["stop"] = lv[2]; m["t1"] = lv[3]; m["t2"] = lv[4]
            fun pc(v: Double) = if (px > 0 && v > 0) Fmt.r2((v / px - 1) * 100.0) else 0.0
            m["stopPct"] = pc(lv[2]); m["t1Pct"] = pc(lv[3]); m["t2Pct"] = pc(lv[4])
            m["rr"] = rr(px, lv[2], lv[3])
            if (e.label == "BUY") {
                // sized at the top of the entry zone, so any fill inside the zone risks at most riskPct incl. charges
                val qty = SwingApp.positionSize(capital, riskPct, costPct, lv[1], lv[2])
                if (qty >= 1) {
                    m["qty"] = qty
                    m["investRs"] = Fmt.r2(qty * lv[1])
                    m["riskRs"] = Fmt.r2(qty * (lv[1] - lv[2]) + lv[1] * qty * costPct / 100.0)
                }
            }
        }
        m["hist"] = hist(b)
        m["waitFor"] = ScoreTexts.waitFor(e)
        m["caveat"] = ScoreTexts.CAVEAT
        m["proven"] = e.label == "AVOID"
        m["evidence"] = ScoreTexts.evidence(e.label, b)
        m["band"] = e.band
        m["points"] = points(e.p)
        m["exitRule"] = if (e.label == "AVOID") "" else ScoreTexts.EXIT_RULE
        return m
    }

    /** One `ideas` row (SPEC3 §2 + v3.1). [s] = the series the entry was scored on (for spark and day change). */
    fun idea(e: ScoreEntry, name: String, s: Series?): Map<String, Any?> {
        val c = e.comp
        val lv = levels(e)
        val spark = ArrayList<Any?>(30)
        if (s != null) for (j in maxOf(0, s.n - 30) until s.n) spark.add(Fmt.r2(s.c[j]))
        return linkedMapOf(
            "sym" to e.sym, "name" to name, "ltp" to Fmt.r2(c.close),
            "dayChgPct" to (if (c.prevClose > 0) Fmt.r2((c.close / c.prevClose - 1) * 100.0) else 0.0),
            "score" to Fmt.r1(e.score), "label" to e.label, "headline" to ScoreTexts.headline(e.label, e.score, c.close > c.sma50, c.uclock),
            "entryLow" to lv[0], "entryHigh" to lv[1], "stop" to lv[2], "t1" to lv[3], "t2" to lv[4], "rr" to rr(lv[0], lv[2], lv[3]),
            "horizonDays" to SetupScore.HORIZON_DAYS, "spark" to spark, "barDay" to (if (c.day > 0) StockPage.yyyymmdd(c.day) else 0), "proven" to false
        )
    }
}
