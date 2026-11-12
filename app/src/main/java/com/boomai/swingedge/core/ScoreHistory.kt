package com.boomai.swingedge.core

/**
 * Out-of-sample research numbers behind the setup score, transcribed from research/out_r4/score_bands.json
 * (version r4-2026-11-12; OOS = signal days 2022-01 .. 2026-08, 60 sessions, entry next open, after 0.25% costs).
 * A unit test parses that JSON and checks every constant here, so they cannot drift.
 */
object ScoreHistory {
    const val VERSION = "r4-2026-11-12"
    const val HORIZON_DAYS = 60

    /** One score band: OOS_raw n/winPct/avgPct/medianPct/worstPct (5th percentile) and OOS_excess avgPct + Newey-West t. */
    class Band(
        val key: String, val n: Int, val winPct: Double, val avgPct: Double, val medianPct: Double, val worstPct: Double,
        val excessAvgPct: Double, val excessT: Double
    )

    val BANDS: List<Band> = listOf(
        Band("0-39", 164592, 49.7, 1.99, -0.11, -23.47, -1.51, -3.17),
        Band("40-54", 86416, 51.2, 2.9, 0.53, -23.61, -0.62, -2.81),
        Band("55-69", 89307, 53.8, 4.31, 1.68, -23.75, 0.74, 3.15),
        Band("70-84", 80160, 53.5, 4.72, 1.66, -25.54, 0.95, 1.64),
        Band("85-100", 33334, 51.3, 4.41, 0.69, -30.22, 1.4, 1.52)
    )

    fun band(key: String): Band? = BANDS.firstOrNull { it.key == key }

    /** Trade plan X10 out-of-sample ("tradePlan" -> "OOS"). */
    object Plan {
        const val TRADES = 2144
        const val WIN_PCT = 31.8
        const val EXPECTANCY_PCT = 1.75
        const val MEDIAN_PCT = -3.16
        const val WORST_PCT = -15.72
        const val WORST_SINGLE_PCT = -23.92
        const val AVG_HOLD_DAYS = 23.1
        const val PROFIT_FACTOR = 1.36
        const val STOP_HIT_PCT = 15.0
        const val T1_TOUCHED_PCT = 21.1
        const val T2_TOUCHED_PCT = 12.8
    }

    /** Rs 1 lakh, 4 slots, out-of-sample ("portfolio_100k" -> "4slots_OOS") and the Nifty 50 over the same years. */
    object Portfolio {
        const val CAGR_PCT = 3.9
        const val MAX_DD_PCT = -53.1
        const val NIFTY_CAGR_PCT = 7.5
        const val NIFTY_MAX_DD_PCT = -16.5
    }

    /** 20 staggered start dates, plan X10, 4 slots, OOS ("portfolioRobustness" rows): CAGR min / median / max and median drawdown. */
    object Robust {
        const val CAGR_MIN = 3.6
        const val CAGR_MEDIAN = 6.3
        const val CAGR_MAX = 10.9
        const val MDD_MEDIAN = -53.1
    }

    // ---- figures from the sceptic's review (not in score_bands.json, so not drift-tested; edit here if the research changes)
    /** Average per trade at realistic (~0.40%) costs, rounded. */
    const val PLAN_AVG_REALISTIC_PCT = 1.5
    /** Equal-weight liquid basket CAGR 2022-26, approx. */
    const val EQUAL_WEIGHT_CAGR_PCT = 13.0
    /** Scores below 55: lag vs the average liquid stock over 60 sessions (full period, approx. points), years behind, OOS lag, raw average. */
    const val LOW_LAG_PTS = 1.0
    const val LAG_YEARS_NEG = 13
    const val LAG_YEARS = 14
    const val LOW_LAG_OOS_PTS = -0.9
    const val LOW_RAW_AVG_PCT = 2.4

    /** |t| at or above this is called statistically reliable in the texts. */
    const val T_RELIABLE = 2.0
}

/**
 * EVERY user-facing string of the stockInfo `call` and the `ideas` rows, in one place so they are easy to review and edit.
 * Wording rules: plain English with real numbers; never "guaranteed" / "sure shot" / "risk-free"; never say or imply
 * that a target will be reached.
 */
object ScoreTexts {
    val POINT_LABELS = listOf("6-month momentum (risk-adjusted)", "6-month return", "12-month return", "Near its 52-week high", "Above its 52-week low")

    const val EXIT_RULE = "Sell if it closes below its 50-day average, or after 60 trading days. Targets are reference levels, not sell orders."
    const val CAVEAT = "Rule-based and tested on past data. Losses happen; the stop limits them, it does not remove them."

    private fun s1(x: Double) = Fmt.pct(x)                       // +4.4%
    private fun s2(x: Double): String {                           // +1.75%
        val r = Math.round(Math.abs(x) * 100.0)
        val body = (r / 100).toString() + "." + (r % 100).toString().padStart(2, '0')
        return (if (x < 0 && r != 0L) "-" else "+") + body + "%"
    }
    private fun t1(x: Double) = (if (x < 0) "-" else "") + Fmt.p1(Math.abs(x))
    private fun bandName(key: String) = key.replace("-", "–")
    private fun scoreTxt(s: Double) = Fmt.p1(s).removeSuffix(".0")
    private fun reliable(t: Double) = Math.abs(t) >= ScoreHistory.T_RELIABLE

    // ------------------------------------------------------------------ headlines
    fun headline(label: String, score: Double, aboveSma: Boolean, uclock: Boolean): String = when (label) {
        "BUY" -> "Scores " + scoreTxt(score) + " of 100 and is above its 50-day average — a buy candidate; its tested record is below"
        "WAIT" -> when {
            score >= SetupScore.BUY_MIN && !aboveSma -> "Scores " + scoreTxt(score) + " of 100 but is below its 50-day average — not an entry yet"
            score >= SetupScore.BUY_MIN && uclock -> "Scores " + scoreTxt(score) + " of 100 but closed locked at its upper circuit — not an entry today"
            else -> "Scores " + scoreTxt(score) + " of 100 — above average, but under the 85 a buy candidate needs"
        }
        else -> "Scores " + scoreTxt(score) + " of 100 — in the low group that has lagged the market"
    }

    fun naHeadline(kind: String, comp: ScoreComp): String = when (kind) {
        SetupScore.NA_ILLIQUID -> "Not rated — too thinly traded (about ₹" + Fmt.p1(z(comp.to20)) + " cr a day; needs ₹" + Fmt.num(SetupScore.MIN_TO20_CR) + " cr)"
        SetupScore.NA_CHEAP -> "Not rated — price is under ₹" + Fmt.num(SetupScore.MIN_PRICE)
        SetupScore.NA_SHORT -> "Not rated — needs at least " + SetupScore.MIN_RATED_BARS + " days of price history (has " + comp.nbars + ")"
        SetupScore.NA_INCOMPLETE -> "Not rated — its price history is incomplete"
        else -> "Not rated yet — the ranking needs a full scan of the market first"
    }

    private fun z(x: Double) = if (x.isNaN() || x.isInfinite()) 0.0 else x

    fun naReasons(kind: String, comp: ScoreComp, ratedInTable: Int): List<String> {
        val out = ArrayList<String>()
        if (kind == NA_NO_TABLE) {
            out.add("The score ranks a stock against every liquid stock the app scans, so it needs that full ranking first")
            out.add(if (ratedInTable > 0) "The last scan could rate only " + ratedInTable + " stocks (needs " + Params.SCORE_MIN_RATED + ")"
                else "No scan has built the ranking since the app started")
            out.add("Tap Scan now, or wait for the next scheduled scan")
            return out
        }
        out.add("Average turnover over 20 days: ₹" + Fmt.p1(z(comp.to20)) + " cr a day (needs ₹" + Fmt.num(SetupScore.MIN_TO20_CR) + " cr)" + (if (comp.to20 >= SetupScore.MIN_TO20_CR) " — OK" else " — too low"))
        out.add("Price ₹" + Fmt.num(z(comp.rawClose)) + " (needs ₹" + Fmt.num(SetupScore.MIN_PRICE) + " or more)" + (if (comp.rawClose >= SetupScore.MIN_PRICE) " — OK" else " — too low"))
        out.add("Price history: " + comp.nbars + " daily bars (needs " + SetupScore.MIN_RATED_BARS + ")" + (if (comp.nbars >= SetupScore.MIN_RATED_BARS) " — OK" else " — too short"))
        if (kind == SetupScore.NA_INCOMPLETE) out.add("Some of the score's inputs could not be computed from the price history")
        out.add("The score is only given to liquid stocks with a year of history, ranked against the market")
        return out
    }
    const val NA_NO_TABLE = "NO_TABLE"

    // ------------------------------------------------------------------ reasons (3-6 lines)
    fun trendLine(c: Double, sma50: Double): String {
        val d = (c / sma50 - 1) * 100.0
        return if (c > sma50) "Price " + Fmt.rs(c) + " is " + Fmt.p1(d) + "% above its 50-day average " + Fmt.rs(sma50)
        else "Price " + Fmt.rs(c) + " is " + Fmt.p1(-d) + "% below its 50-day average " + Fmt.rs(sma50) + " — a buy candidate needs a close above it"
    }

    const val UCLOCK_LINE = "Closed locked at its upper circuit today — such days are skipped by the rule"

    fun staleLine(c: Double, day: Int): String =
        "Score from the last scan, on the close of " + Fmt.rs(c) + (if (day > 0) " (" + Clock.dayLabel(day) + ")" else "") + "; the price has moved since"

    /** One line per component: what it is, how it compares, the points it earns. */
    fun componentLine(k: Int, comp: ScoreComp, p: Double): String {
        val beats = Math.round(p * 100.0).coerceIn(0, 100)
        val pts = 20.0 * p
        val ptsTxt = if (pts >= 10.0) " — earns " + Fmt.p1(pts) + " of 20 points" else " — only " + Fmt.p1(pts) + " of 20 points"
        return when (k) {
            0 -> "Risk-adjusted 6-month momentum is stronger than in about $beats% of liquid stocks" + ptsTxt
            1 -> "6-month return (leaving out the last month) " + s1(comp.r6 * 100.0) + ", ahead of about $beats% of liquid stocks" + ptsTxt
            2 -> "12-month return (leaving out the last month) " + s1(comp.r12 * 100.0) + ", ahead of about $beats% of liquid stocks" + ptsTxt
            3 -> {
                val below = (1 - comp.prox52) * 100.0
                (if (below <= 0.05) "At its 52-week high " + Fmt.rs(comp.hi252) else Fmt.p1(below) + "% below its 52-week high " + Fmt.rs(comp.hi252)) +
                    ", closer to its high than about $beats% of liquid stocks" + ptsTxt
            }
            else -> Fmt.p1((comp.low52 - 1) * 100.0) + "% above its 52-week low " + Fmt.rs(comp.lo252) + ", further from its low than about $beats% of liquid stocks" + ptsTxt
        }
    }

    fun waitFor(e: ScoreEntry): String {
        val c = e.comp
        return when {
            e.label != "WAIT" -> ""
            e.score >= SetupScore.BUY_MIN && !(c.close > c.sma50) -> "a close back above the 50-day average (" + Fmt.rs(c.sma50) + ") while the score stays at 85 or more"
            e.score >= SetupScore.BUY_MIN && c.uclock -> "a day that does not close locked at the upper circuit, with the score still at 85 or more"
            else -> "a score of 85 or more — it is ranked " + e.rank + " of " + e.of + " today" + (if (!(c.close > c.sma50)) ", and a close above the 50-day average (" + Fmt.rs(c.sma50) + ")" else "")
        }
    }

    // ------------------------------------------------------------------ history and evidence
    fun histText(b: ScoreHistory.Band): String {
        val ex = b.excessAvgPct
        val vs = if (ex >= 0) s2(ex) + " more than the average liquid stock" else Fmt.p1(-ex) + "% behind the average liquid stock"
        val rel = if (reliable(b.excessT)) "a statistically reliable difference (t = " + t1(b.excessT) + ")" else "a difference that is not statistically reliable (t = " + t1(b.excessT) + ")"
        return "Since 2022, stocks scoring " + bandName(b.key) + " returned " + s1(b.avgPct) + " on average and " + s1(b.medianPct) + " at the median over the next " +
            ScoreHistory.HORIZON_DAYS + " trading days after charges (" + Fmt.group(b.n.toLong()) + " cases); 1 in 20 lost " + Fmt.p1(-b.worstPct) + "% or more. " +
            "That is " + vs + " over the same days, " + rel + "."
    }

    const val HIST_NA = "No history shown: this stock is not rated."

    fun evidence(label: String, b: ScoreHistory.Band?): String = when (label) {
        "BUY" -> "Tested on NSE 2022–26 with this exact plan: " + s2(ScoreHistory.Plan.EXPECTANCY_PCT) + " per trade after 0.25% costs (about " +
            s1(ScoreHistory.PLAN_AVG_REALISTIC_PCT) + " at realistic costs), " + Math.round(ScoreHistory.Plan.WIN_PCT) + "% winners, median trade " +
            Math.round(ScoreHistory.Plan.MEDIAN_PCT).let { (if (it < 0) "-" else "+") + Math.abs(it) } + "%. A ₹1 lakh 4-slot portfolio made " +
            Fmt.p1(ScoreHistory.Robust.CAGR_MIN) + "–" + Fmt.p1(ScoreHistory.Robust.CAGR_MAX) + "% a year depending on the start date (median about " +
            Math.round(ScoreHistory.Robust.CAGR_MEDIAN) + "%, drawdown " + Math.round(ScoreHistory.Robust.MDD_MEDIAN) + "%) against the Nifty's " +
            Fmt.p1(ScoreHistory.Portfolio.NIFTY_CAGR_PCT) + "% and about " + Math.round(ScoreHistory.EQUAL_WEIGHT_CAGR_PCT) + "% for an equal-weight basket. " +
            "Random stocks above their 50-day average with the same exits did better, so size small and follow the stop."
        "WAIT" -> if (b == null) "WAIT is not a buy signal." else
            "WAIT is not a buy signal. Since 2022, stocks scoring " + bandName(b.key) + " did " +
                (if (b.excessAvgPct >= 0) s2(b.excessAvgPct) + " better" else Fmt.p1(-b.excessAvgPct) + "% worse") + " than the average liquid stock over " + ScoreHistory.HORIZON_DAYS +
                " trading days, " + (if (reliable(b.excessT)) "a statistically reliable difference" else "which is not statistically reliable") + " (t = " + t1(b.excessT) + ")."
        "AVOID" -> "Scores below 55 lagged the average liquid stock by about " + Math.round(ScoreHistory.LOW_LAG_PTS) + " point over " + ScoreHistory.HORIZON_DAYS +
            " sessions (behind in " + ScoreHistory.LAG_YEARS_NEG + " of " + ScoreHistory.LAG_YEARS + " years; 2022–26: " + Fmt.p1(ScoreHistory.LOW_LAG_OOS_PTS) + "). " +
            "They still averaged " + s1(ScoreHistory.LOW_RAW_AVG_PCT) + ", so this means likely to underperform, not likely to fall. The lag was weaker among the most liquid stocks."
        else -> "Not rated, so there is no tested record to show."
    }
}
