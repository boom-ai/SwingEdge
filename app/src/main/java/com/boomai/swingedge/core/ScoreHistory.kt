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

    const val EXIT_RULE = "Buy at the next open only inside the entry zone. Stop-loss 5 ATR below your buy price; otherwise hold 60 trading days, then sell. Targets are reference levels, not sell orders."
    const val LOW_PRICE_LINE = "Priced under ₹50: scored against the 574 liquid stocks, but the tested history behind these numbers covers ₹50+ stocks only, and low-priced stocks move in bigger jumps"
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
            score < SetupScore.AVOID_BELOW -> "Scores " + scoreTxt(score) + " of 100 — weak momentum, but still above its 200-day average, so not an avoid"
            else -> "Scores " + scoreTxt(score) + " of 100 — above average, but under the 85 a buy candidate needs"
        }
        else -> "Scores " + scoreTxt(score) + " of 100 and trades below its 200-day average — the group that has lagged the market"
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
    const val LIST_FAILED = "Could not download the NSE stock list — keeping the current list. It will try again tomorrow."

    fun evidence(label: String, b: ScoreHistory.Band?): String = when (label) {
        "BUY" -> R5.BUY_EVIDENCE
        "WAIT" -> if (b == null) "WAIT is not a buy signal." else
            "WAIT is not a buy signal. Since 2022, stocks scoring " + bandName(b.key) + " did " +
                (if (b.excessAvgPct >= 0) s2(b.excessAvgPct) + " better" else Fmt.p1(-b.excessAvgPct) + "% worse") + " than the average liquid stock over " + ScoreHistory.HORIZON_DAYS +
                " trading days, " + (if (reliable(b.excessT)) "a statistically reliable difference" else "which is not statistically reliable") + " (t = " + t1(b.excessT) + ")."
        "AVOID" -> R5.AVOID_EVIDENCE
        else -> "Not rated, so there is no tested record to show."
    }

    // ------------------------------------------------------------------ live track record (SPEC4)
    const val REPLAY_BUSY = "Replaying the last year…"
    const val REPLAY_NO_HISTORY = "Not enough price history yet to replay — needs about 15 months of daily bars for most stocks. Try again after a few scans."

    private fun dateTxt(day: Int): String = try {
        val d = java.time.LocalDate.ofEpochDay(day.toLong())
        d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1] + " " + d.year
    } catch (t: Throwable) { "" }
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Progress toward the upgrade rule (state `track.live.needText`). */
    fun trackNeedText(p: TrackProof): String {
        val need = TrackEngine.UPGRADE_MIN_TRADES
        return when {
            p.upgraded -> "Met: " + p.buyN + " and " + p.ctrlN + " closed trades; BUY beat the random picks by " + s2(p.edge) +
                " a trade (t = " + Fmt.p1(p.t) + " over " + p.days + " signal days)."
            p.buyN < need || p.ctrlN < need -> "Needs " + need + " closed trades in each group (now " + p.buyN + " and " + p.ctrlN + ") and an edge with t ≥ 2."
            else -> "Has " + need + "+ closed trades in each group; the edge is " + s2(p.edge) + " a trade with t = " + t1(p.t) + " over " + p.days +
                " signal days (needs t ≥ 2 over at least " + TrackEngine.UPGRADE_MIN_DAYS + " days, and a positive BUY average)."
        }
    }

    /** `call.evidence` for BUY once the live record has met the upgrade rule. */
    fun liveEvidence(p: TrackProof): String =
        "Live record on this phone since " + dateTxt(p.startedDay) + ": " + Fmt.group(p.buyN.toLong()) + " closed BUY trades with this plan averaged " +
            s2(p.buyAvg) + " after costs (median " + s2(p.buyMedian) + ", " + Math.round(p.buyWin) + "% winners), against " + s2(p.ctrlAvg) +
            " for " + Fmt.group(p.ctrlN.toLong()) + " random stocks above their 50-day average picked on the same days — " + s2(p.edge) +
            " a trade better, t = " + Fmt.p1(p.t) + " over " + p.days + " signal days. Past results can fail to repeat; size small and follow the stop."
}

/**
 * Round 5 figures (research/out_r5/app_numbers.json; pre-registered in out_r5/PREREG.md). Out-of-sample window
 * 2022-01-03 .. 2025-01-29 (signals touching the 2025 data hole excluded), 0.50% round-trip costs unless stated.
 */
object R5 {
    const val BUY_EVIDENCE = "Tested on NSE 2022–Jan 2025 with this exact plan, at realistic 0.5% costs: 937 trades, 50% won, " +
        "+3.6% average, +0.1% median, 1 in 20 lost 28% or more, about 51 trading days held. A ₹1 lakh 4-stock account made a median 27% a year " +
        "(6% to 56% depending on the start date, drawdown about 35%), against 10.3% for the Nifty and 19.8% for an equal-weight basket of liquid stocks. " +
        "Random uptrend stocks with the same exits averaged +3.8% per trade, so the score itself showed no proven edge; and this plan was picked as the best of 11. " +
        "Size small, follow the stop, and check the live record."
    const val AVOID_EVIDENCE = "Score below 55 and price below the 200-day average: since 2022 such stocks lagged the average liquid stock by 1.6 points over the next " +
        "60 trading days (behind in 3 of 4 years; 2013–2025: 1.7 points, 11 of 13 years). Against the biggest stocks the lag was smaller (1.0 point). " +
        "They still rose 1.7% on average, so this means likely to lag, not likely to fall."
}

/** Every user-facing text of the SPEC5 analyst panel and new-listing card. Pure; never throws on NaN (callers check). */
object PanelTexts {
    const val BASE_RATE = "Stocks listed 2022–25 lagged the average liquid stock by about 4% per 60 trading days in their first year (183 stocks). Not a forecast for this one."
    const val CANNOT = "Could not be computed for this stock."

    /** absolute fallbacks when no scan ranking exists */
    const val MOM_ABS_FOR = 0.25
    const val MOM_ABS_AGAINST = -0.05
    const val VOL_ABS_FOR = 0.02
    const val VOL_ABS_AGAINST = 0.035
    const val ATR_ABS_FOR = 2.5
    const val ATR_ABS_AGAINST = 5.0
    const val TO_FOR_CR = 100.0
    const val STRETCH_MAX = 6.0

    fun name(id: String): String = when (id) {
        "trend" -> "Trend"; "momentum" -> "Momentum"; "risk" -> "Risk"; "liquidity" -> "Liquidity"
        "timing" -> "Timing"; "market" -> "Market"; else -> "New listing"
    }

    fun evidence(id: String): String = when (id) {
        "trend" -> "tested, weak"
        "momentum" -> "= the score"
        "risk" -> "no edge (describes risk)"
        "liquidity" -> "for execution only"
        "timing" -> "no edge"
        "market" -> "no edge — weak markets were followed by rebounds"
        else -> "base rate only"
    }

    private fun ok(vararg x: Double) = x.all { !it.isNaN() && !it.isInfinite() }
    private fun ap(x: Double) = Fmt.p1(Math.abs(x)) + "%"              // 5.4%
    private fun above(x: Double) = if (x >= 0) "above" else "below"
    private fun pctOf(p: Double) = Math.round(p * 100.0).coerceIn(0, 100).toString() + "%"
    private fun atrs(x: Double) = Fmt.p1(Math.abs(x)) + " ATR"
    private fun scoreTxt(s: Double) = Fmt.p1(s).removeSuffix(".0")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    fun dateTxt(day: Int): String = try {
        val d = java.time.LocalDate.ofEpochDay(day.toLong())
        d.dayOfMonth.toString() + " " + MONTHS[d.monthValue - 1] + " " + d.year
    } catch (t: Throwable) { "" }
    private fun needs(what: String, bars: Int, has: Int) = "Not enough history: needs " + bars + " trading days for " + what + " (has " + has + ")."

    // ------------------------------------------------------------------ managers
    fun trend(v: PanelVals): String {
        val slope = if (v.slope200 > 0) "rising" else if (v.slope200 < 0) "falling" else "flat"
        return "Price " + ap(v.dist200) + " " + above(v.dist200) + " the 200-day average " + Fmt.rs(v.sma200) + ", which is " + slope +
            " (" + Fmt.pct(v.slope200) + " in 21 days); 50-day " + (if (v.sma50 > v.sma200) "above" else "below") + " 200-day"
    }
    fun trendNA(has: Int) = needs("a rising or falling 200-day average", 221, has)

    fun momentum(v: PanelVals, ranked: Boolean): String {
        val r = "6-month return " + Fmt.pct(v.r6 * 100.0) + (if (ok(v.r12)) " and 12-month return " + Fmt.pct(v.r12 * 100.0) else "") + " (leaving out the last month)"
        return if (ranked) r + ", ahead of about " + pctOf(v.momP) + " of liquid stocks" + (if (ok(v.r12)) " on average" else "; no 12-month return yet")
        else r + "; no market ranking yet, so judged on the level: for at +25% or more, against at -5% or less"
    }
    fun momentumNA(has: Int) = needs("a 6-month return", 127, has)

    fun risk(v: PanelVals): String {
        val swing = when {
            ok(v.volP) -> "Swings about " + Fmt.p1(v.vol * 100.0) + "% a day, " + (if (v.volP <= 0.5) "calmer than about " + pctOf(1 - v.volP) else "wilder than about " + pctOf(v.volP)) + " of liquid stocks"
            ok(v.vol) -> "Swings about " + Fmt.p1(v.vol * 100.0) + "% a day (no market ranking yet: calm under 2%, wild over 3.5%)"
            else -> "Average daily range (ATR) " + Fmt.p1(v.atrPct) + "% of the price (too new for 6-month swings: calm under 2.5%, wild over 5%)"
        }
        val size = if (v.qty >= 1 && ok(v.riskRs)) ", " + Fmt.rs0(v.riskRs) + " at risk for your size (" + Fmt.plural(v.qty, "share") + ")"
            else if (ok(v.budgetRs) && v.budgetRs > 0) "; at your risk setting (" + Fmt.rs0(v.budgetRs) + " a trade) not even 1 share fits" else ""
        return swing + "; a 5-ATR stop is " + Fmt.p1(v.stopPct) + "% (" + Fmt.rs(v.stopRs) + " a share) below" + size
    }
    fun riskNA(has: Int) = needs("the daily range", 15, has)

    fun liquidity(v: PanelVals): String = "Trades about ₹" + Fmt.p1(v.to20) + " cr a day (20-day average) — " + when {
        v.to20 >= TO_FOR_CR -> "deep enough to buy and sell without moving the price"
        v.to20 < SetupScore.MIN_TO20_CR -> "under ₹" + Fmt.num(SetupScore.MIN_TO20_CR) + " cr, so orders can move the price; use limit orders"
        else -> "fine for small orders; ₹" + Fmt.num(TO_FOR_CR) + " cr or more counts as deep"
    }
    fun liquidityNA(has: Int) = needs("the 20-day turnover", 20, has)

    fun timing(v: PanelVals, stretched: Boolean): String {
        val base = "Price " + atrs(v.stretch) + " " + above(v.stretch) + " its 50-day average " + Fmt.rs(v.sma50)
        return base + when {
            stretched -> " — stretched (over " + Fmt.num(STRETCH_MAX) + " ATR" + (if (ok(v.stretchP90)) ", or past the 90th percentile of liquid stocks, " + atrs(v.stretchP90) else "") + ")"
            v.stretch >= 0 && v.stretch <= 2 -> " — not stretched"
            v.stretch < 0 -> " — not stretched, but not in an uptrend either"
            else -> " — extended, not yet stretched"
        }
    }
    fun timingNA(has: Int) = needs("the 50-day average", 50, has)

    fun market(v: PanelVals): String =
        "Nifty " + ap(v.niftyDist) + " " + above(v.niftyDist) + " its 200-day average; " + pctOf(v.breadth) + " of liquid stocks above their 50-day average"
    fun marketNA(haveNifty: Boolean) = if (!haveNifty) "No Nifty history with a 200-day average yet." else "Market breadth needs a full scan first."

    fun newListing(v: PanelVals): String =
        (if (v.listedKnown) "Listed " + dateTxt(v.listedDay) + ", " else "First traded bar " + dateTxt(v.listedDay) + ", ") + v.bars + " trading days ago" +
            (if (ok(v.sinceListPct)) " (" + Fmt.pct(v.sinceListPct) + " from the listing-day open)" else "") + ". " + BASE_RATE
    fun notNew(v: PanelVals): String =
        (if (v.listedKnown) "Listed " + dateTxt(v.listedDay) + " — " else "More than a year of trading (" + v.bars + " days here) — ") + "the new-listing base rate does not apply"

    fun pro(id: String, text: String) = text
    fun con(id: String, text: String) = text

    // ------------------------------------------------------------------ chair
    fun chairText(label: String, headline: String, nFor: Int, nAgainst: Int, nNeutral: Int): String {
        val h = headline.trim().trimEnd('.')
        val head = if (h.isEmpty()) "The call comes from the tested score, not from the vote."
            else "The call comes from the tested score, not from the vote: " + h.substring(0, 1).lowercase() + h.substring(1) + "."
        return head + " The managers split " + nFor + " for, " + nAgainst + " against, " + nNeutral + " neutral; none showed a reliable edge on its own, so they explain the stock but cannot change the call."
    }

    fun flipUp(label: String, e: ScoreEntry?, naKind: String?, bars: Int, to20: Double, proven: Boolean): String {
        val c = e?.comp
        return when {
            label == "BUY" -> if (proven) "Nothing higher: BUY is the top call." else "Nothing higher: BUY is the top call. It turns proven only once the live record on this phone meets the upgrade rule."
            label == "WAIT" && e != null && c != null -> when {
                e.score >= SetupScore.BUY_MIN && !(c.close > c.sma50) -> "A close above the 50-day average " + Fmt.rs(c.sma50) + ", with the score still 85 or more, would make it BUY."
                e.score >= SetupScore.BUY_MIN && c.uclock -> "A day that does not close locked at the upper circuit, with the score still 85 or more, would make it BUY."
                else -> "A score of 85 or more (now " + scoreTxt(e.score) + ", ranked " + e.rank + " of " + e.of + ")" +
                    (if (!(c.close > c.sma50) && c.sma50 > 0) " and a close above the 50-day average " + Fmt.rs(c.sma50) else "") + " would make it BUY."
            }
            label == "AVOID" && e != null && c != null ->
                (if (c.sma200 > 0) "A close above the 200-day average " + Fmt.rs(c.sma200) + ", or a" else "A") + " score of 55 or more (now " + scoreTxt(e.score) + "), would make it WAIT."
            label == "NA" -> when (naKind) {
                SetupScore.NA_SHORT -> "Gets a score after " + Fmt.plural(Math.max(1, SetupScore.MIN_RATED_BARS - bars), "more trading day") + " (needs " + SetupScore.MIN_RATED_BARS + "), if turnover stays at ₹" + Fmt.num(SetupScore.MIN_TO20_CR) + " cr a day or more."
                SetupScore.NA_ILLIQUID -> "Turnover of ₹" + Fmt.num(SetupScore.MIN_TO20_CR) + " cr a day or more (now ₹" + Fmt.p1(if (ok(to20)) to20 else 0.0) + " cr) would get it a score."
                SetupScore.NA_CHEAP -> "Needs a price of ₹" + Fmt.num(SetupScore.MIN_PRICE) + " or more, or a full year of history, to get a score."
                SetupScore.NA_INCOMPLETE -> "Gets a score once its price history is complete."
                else -> "Gets a score after the next full market scan."
            }
            else -> ""
        }
    }

    fun flipDown(label: String, e: ScoreEntry?): String {
        val c = e?.comp
        return when {
            label == "BUY" && e != null && c != null -> "A close below the 50-day average " + Fmt.rs(c.sma50) + ", or a score under 85 (now " + scoreTxt(e.score) + ", ranked " + e.rank + " of " + e.of +
                "), would make it WAIT" + (if (c.sma200 > 0) "; AVOID needs a score under 55 and a close below the 200-day average " + Fmt.rs(c.sma200) + "." else ".")
            label == "WAIT" && e != null && c != null -> when {
                !(c.sma200 > 0) -> "A score under 55 (now " + scoreTxt(e.score) + ") would make it AVOID."
                e.score < SetupScore.AVOID_BELOW -> "A close below the 200-day average " + Fmt.rs(c.sma200) + " would make it AVOID (the score, " + scoreTxt(e.score) + ", is already under 55)."
                else -> "A score under 55 (now " + scoreTxt(e.score) + ") together with a close below the 200-day average " + Fmt.rs(c.sma200) + " would make it AVOID."
            }
            label == "AVOID" -> "Nothing lower: AVOID is the bottom call."
            else -> ""
        }
    }

    // ------------------------------------------------------------------ rebuttals: one template per sensible (manager, opponent) pair
    fun rebuttal(me: String, stance: String, opp: String, v: PanelVals): String {
        val f = stance == "FOR"
        val nifty = { "the Nifty is " + ap(v.niftyDist) + " " + above(v.niftyDist) + " its 200-day average and " + pctOf(v.breadth) + " of liquid stocks are above their 50-day" }
        return when (me) {
            "trend" -> when (opp) {
                "risk" -> if (f && ok(v.atrPct, v.dist200, v.slope200, v.stopPct)) "Yes, its daily range (ATR) is " + Fmt.p1(v.atrPct) + "% of the price, but price is " + ap(v.dist200) + " above a 200-day average that rose " + ap(v.slope200) + " in 21 days; the 5-ATR stop (" + Fmt.p1(v.stopPct) + "% below) is sized for those swings."
                    else if (!f && ok(v.atrPct, v.dist200, v.slope200)) "A calm daily range (ATR " + Fmt.p1(v.atrPct) + "% of the price) does not fix the direction: price is " + ap(v.dist200) + " below a 200-day average that fell " + ap(v.slope200) + " in 21 days." else ""
                "timing" -> if (f && ok(v.stretch, v.d50v200)) "Stretched " + atrs(v.stretch) + " above the 50-day average, but the trend underneath holds: the 50-day is " + ap(v.d50v200) + " above a rising 200-day."
                    else if (!f && ok(v.dist200)) "Not stretched, but price is " + ap(v.dist200) + " below a falling 200-day average " + Fmt.rs(v.sma200) + "." else ""
                "momentum" -> if (f && ok(v.momP, v.dist200)) "Its returns are ahead of only " + pctOf(v.momP) + " of liquid stocks, but price is " + ap(v.dist200) + " above a rising 200-day average."
                    else if (!f && ok(v.momP, v.dist200, v.slope200)) "Returns are ahead of " + pctOf(v.momP) + " of liquid stocks, but price is " + ap(v.dist200) + " below a 200-day average that fell " + ap(v.slope200) + " in 21 days." else ""
                "market" -> if (f && ok(v.niftyDist, v.breadth, v.dist200)) "The market is weak (" + nifty() + "), but this stock is " + ap(v.dist200) + " above its own rising 200-day average."
                    else if (!f && ok(v.niftyDist, v.breadth, v.dist200)) "The market is healthy (" + nifty() + "), but this stock is " + ap(v.dist200) + " below its falling 200-day average." else ""
                "liquidity" -> if (f && ok(v.to20, v.dist200)) "Thin trading (₹" + Fmt.p1(v.to20) + " cr a day) affects fills, not the trend: price is " + ap(v.dist200) + " above a rising 200-day average." else ""
                "newlisting" -> if (f && ok(v.dist200)) "Only " + v.bars + " trading days since listing, but price is already " + ap(v.dist200) + " above a rising 200-day average." else ""
                else -> ""
            }
            "momentum" -> when (opp) {
                "risk" -> if (f && ok(v.vol, v.volP, v.r6, v.momP)) "It swings " + Fmt.p1(v.vol * 100.0) + "% a day, more than about " + pctOf(v.volP) + " of liquid stocks, but its 6-month return of " + Fmt.pct(v.r6 * 100.0) + " keeps it ahead of " + pctOf(v.momP) + " — and the score's risk-adjusted part already discounts those swings."
                    else if (!f && ok(v.momP)) "Calm, but its returns are ahead of only " + pctOf(v.momP) + " of liquid stocks." else ""
                "timing" -> if (f && ok(v.stretch, v.r6, v.momP)) "Stretched " + atrs(v.stretch) + " above the 50-day average — a stretch built by a 6-month return of " + Fmt.pct(v.r6 * 100.0) + ", ahead of " + pctOf(v.momP) + " of liquid stocks."
                    else if (!f && ok(v.momP)) "Not stretched, but its returns are ahead of only " + pctOf(v.momP) + " of liquid stocks." else ""
                "market" -> if (f && ok(v.niftyDist, v.breadth, v.momP)) "The market is weak (" + nifty() + "), yet this stock's returns are ahead of " + pctOf(v.momP) + " of liquid stocks."
                    else if (!f && ok(v.momP)) "The market is healthy, but this stock's returns are ahead of only " + pctOf(v.momP) + " of liquid stocks." else ""
                "trend" -> if (f && ok(v.dist200, v.momP)) "Price is " + ap(v.dist200) + " below a falling 200-day average, but its returns are still ahead of " + pctOf(v.momP) + " of liquid stocks."
                    else if (!f && ok(v.momP)) "Price is above a rising 200-day average, but its returns are ahead of only " + pctOf(v.momP) + " of liquid stocks." else ""
                "newlisting" -> if (f && ok(v.r6, v.momP)) "Only " + v.bars + " trading days of history, but its 6-month return of " + Fmt.pct(v.r6 * 100.0) + " is ahead of " + pctOf(v.momP) + " of liquid stocks." else ""
                else -> ""
            }
            "risk" -> when (opp) {
                "momentum", "trend" -> if (!f && ok(v.atrPct, v.stopPct, v.stopRs)) "The " + opp + " is real, but with a daily range (ATR) of " + Fmt.p1(v.atrPct) + "% the 5-ATR stop sits " + Fmt.p1(v.stopPct) + "% (" + Fmt.rs(v.stopRs) + " a share) below" +
                        (if (v.qty >= 1 && ok(v.riskRs)) " — " + Fmt.rs0(v.riskRs) + " at risk at your size." else ".")
                    else if (f && ok(v.atrPct, v.stopPct)) "The " + opp + " is weak, but the daily range is small (ATR " + Fmt.p1(v.atrPct) + "% of the price), so a 5-ATR stop is only " + Fmt.p1(v.stopPct) + "% away." else ""
                else -> ""
            }
            "timing" -> when (opp) {
                "trend", "momentum" -> if (!f && ok(v.stretch, v.sma50)) "The " + opp + " is real, but price is " + atrs(v.stretch) + " above the 50-day average " + Fmt.rs(v.sma50) +
                        (if (ok(v.rsi2)) " (RSI(2) " + Fmt.p1(v.rsi2) + ")" else "") + (if (ok(v.stretchP90)) "; 90% of liquid stocks are within " + atrs(v.stretchP90) + "." else ".")
                    else if (f && ok(v.stretch, v.sma50)) "Weak " + opp + ", but price is only " + atrs(v.stretch) + " from its 50-day average " + Fmt.rs(v.sma50) + " — not stretched." else ""
                else -> ""
            }
            "market" -> when (opp) {
                "trend", "momentum" -> if (ok(v.niftyDist, v.breadth)) {
                    val what = if (opp == "trend") (if (!f) "The stock is in an uptrend" else "The stock is in a downtrend") else (if (!f) "The stock's returns are strong" else "The stock's returns are weak")
                    if (!f) what + ", but " + nifty() + "." else what + ", but the market is healthy: " + nifty() + "."
                } else ""
                else -> ""
            }
            "liquidity" -> if (!f && ok(v.to20)) "Whatever the setup, at ₹" + Fmt.p1(v.to20) + " cr a day an order can move the price; the plan assumes a fill inside the entry zone, so use limit orders." else ""
            "newlisting" -> if (!f) "With only " + v.bars + " trading days since listing the record is thin: stocks listed 2022–25 lagged the average liquid stock by about 4% per 60 trading days in their first year." else ""
            else -> ""
        }
    }
}

private const val RENAMED_NOTE = "These were not NSE symbols, so no prices could be loaded. They now use the exchange symbols; prices and verdicts follow from the next scan."
fun ScoreTexts.renamedLine(from: List<String>, to: List<String>): String =
    from.indices.joinToString(", ") { from[it] + " → " + to[it] } + ". " + RENAMED_NOTE

/** "GSPL (25 shares) merged: now 19 GUJENERGY + 6 GSPLTRANS. Check the counts in your broker app." */
fun ScoreTexts.mergedLine(a: CorpAction, qty: Double): String {
    val q = a.newQty(qty); val e = a.extraQty(qty)
    fun n(x: Double) = if (x == Math.floor(x)) x.toLong().toString() else String.format(java.util.Locale.US, "%.2f", x)
    val parts = n(q) + " " + a.to + (if (a.extra != null && e > 0) " + " + n(e) + " " + a.extra else "")
    return a.old + " (" + n(qty) + " shares) no longer trades — it merged (" + a.num + " new per " + a.den + " old" +
        (if (a.extra != null) ", plus " + a.exNum + " " + a.extra + " per " + a.exDen + " " + a.to else "") +
        "). Now tracked as " + parts + ". Check the counts in your broker app and edit if they differ."
}
