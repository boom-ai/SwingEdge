package com.boomai.swingedge.core

/** Everything the stock page needs about one symbol. [s] has the latest (possibly partial) bar last. */
class StockInput(
    val sym: String, val name: String, val tracked: Boolean, val isHolding: Boolean, val inWatch: Boolean,
    val s: Series, val index: Series?, val live: Boolean, val asOf: Long, val now: Long,
    /** Cross-sectional context (cut-offs, breadth) from the last full scan; null = none yet. */
    val scanCtx: Ctx?,
    /** 1 BULL, 0 NEUTRAL, -1 BEAR */
    val regime: Int,
    /** this holding's share of the portfolio in % (0 if not held or unknown) */
    val weightPct: Double,
    /** strategy id -> LIVE | PAUSED | UNTESTED */
    val stratVerdicts: Map<String, String>
)

/** Builds the `stockInfo` result and the `myWatch` rows. Pure functions; never throw (callers also guard). */
object StockPage {
    const val CROSS_CAP = 60
    const val GOLDEN_CAP = 250

    private fun z(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else x

    fun yyyymmdd(epochDay: Int): Int = try {
        val d = java.time.LocalDate.ofEpochDay(epochDay.toLong())
        d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    } catch (t: Throwable) { 0 }

    /** Fraction of today's session behind the latest bar (1 for a finished day). Same rule as the holdings check. */
    fun sessionFraction(s: Series, now: Long): Double =
        if (s.n > 0 && s.lastDay() == Clock.epochDay(now) && Clock.minuteOfDay(now) < Clock.CLOSE_MIN) Clock.sessionFraction(now) else 1.0

    /** Index bar at or before [day], or -1. */
    private fun barAtOrBefore(x: Series, day: Int): Int {
        var lo = 0
        var hi = x.n - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (x.d[mid] <= day) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** Stock return minus Nifty return over the same dates, % points; NaN when unknown. */
    fun vsIndex(s: Series, index: Series?, k: Int): Double {
        val i = s.n - 1
        if (index == null || index.n < 2 || i - k < 0) return Double.NaN
        val r = Ind.ret(s.c, i, k)
        if (r.isNaN()) return Double.NaN
        val a = barAtOrBefore(index, s.d[i - k])
        val b = barAtOrBefore(index, s.d[i])
        if (a < 0 || b <= a || !(index.c[a] > 0)) return Double.NaN
        return (r - (index.c[b] / index.c[a] - 1)) * 100.0
    }

    // --------------------------------------------------------------------------------------- stack / cross texts

    class Stack(val label: String, val text: String)

    fun stack(L: EmaLines, i: Int): Stack {
        val have = ArrayList<Pair<Int, Double>>()
        val missing = ArrayList<Int>()
        for (p in Ema.PERIODS) { val v = L.shown(p, i); if (v > 0) have.add(Pair(p, v)) else missing.add(p) }
        if (have.size < 3) return Stack("MIXED", "Only " + Fmt.plural(L.n, "day") + " of history — not enough for the longer averages yet")
        var up = true
        var down = true
        for (k in 1 until have.size) {
            if (!(have[k - 1].second > have[k].second)) up = false
            if (!(have[k - 1].second < have[k].second)) down = false
        }
        val tail = if (missing.isEmpty()) "" else " (the " + missing.joinToString(" and ") { "$it" } + " EMA" + (if (missing.size > 1) "s need" else " needs") + " more history)"
        return when {
            up -> Stack("BULLISH", have.joinToString(" > ") { "${it.first}" } + ": every average is stacked in order" + tail)
            down -> Stack("BEARISH", have.joinToString(" < ") { "${it.first}" } + ": every average is stacked downward" + tail)
            else -> Stack("MIXED", have.sortedByDescending { it.second }.joinToString(" > ") { "${it.first}" } + ": the averages are not in order" + tail)
        }
    }

    private fun daysWord(d: Int) = Fmt.plural(d, "day")

    // --------------------------------------------------------------------------------------- stock page

    fun build(x: StockInput): Map<String, Any?> {
        val s = x.s
        val n = s.n
        val i = n - 1
        val c = s.c[i]
        val frac = sessionFraction(s, x.now)
        val prev = if (n >= 2) s.c[i - 1] else 0.0
        val L = EmaLines(s)
        val e = DoubleArray(5) { L.shown(Ema.PERIODS[it], i) }
        val e10 = e[0]; val e20 = e[1]; val e50 = e[2]; val e100 = e[3]; val e200 = e[4]
        fun dist(v: Double) = if (v > 0) Fmt.r2((c / v - 1) * 100.0) else 0.0

        val avgVol = z(Ind.sma(s.v, i - 1, 20))
        val volRatio = z(Ind.relVol(s.v[i], avgVol, frac))
        val turnover = EmaScreen.turnover20(s)
        val liquid = n >= 21 && turnover >= Params.MIN_AVG_TURNOVER_RS

        val st = stack(L, i)
        // 10 vs 20 EMA
        val cAbove = n >= 1 && L.e10[i] > L.e20[i]
        val cDays = Ema.daysSince(L.e10, L.e20, n, 0, CROSS_CAP)
        val gap = if (L.e20[i] > 0) (L.e10[i] - L.e20[i]) / L.e20[i] * 100.0 else 0.0
        val gap2 = if (n >= 3 && L.e20[i - 2] > 0) (L.e10[i - 2] - L.e20[i - 2]) / L.e20[i - 2] * 100.0 else Double.NaN
        val converging = !gap2.isNaN() && Math.abs(gap) < Math.abs(gap2)
        val side = if (cAbove) "above" else "below"
        val crossText = when {
            n < 3 -> "Only " + daysWord(n) + " of history — too short to read the 10/20 EMA cross"
            cDays == 0 -> "10 EMA crossed $side the 20 EMA on the latest bar (gap " + Fmt.pct(gap) + ")"
            cDays > 0 -> "10 EMA crossed $side the 20 EMA " + daysWord(cDays) + " ago and the gap is " + (if (converging) "narrowing" else "widening") + " (" + Fmt.pct(gap) + ")"
            else -> "10 EMA has been $side the 20 EMA for more than " + daysWord(minOf(CROSS_CAP, maxOf(0, n - 2))) + " (gap " + Fmt.pct(gap) + ")"
        }
        // 50 vs 200 EMA
        val gFirst = maxOf(Ema.firstValid(50), Ema.firstValid(200))
        val gKnown = i >= gFirst
        val gAbove = gKnown && L.e50[i] > L.e200[i]
        val gDays = if (gKnown) Ema.daysSince(L.e50, L.e200, n, gFirst, GOLDEN_CAP) else -1
        val gSide = if (gAbove) "above" else "below"
        val goldenText = when {
            !gKnown -> "Not enough history for the 200 EMA (" + daysWord(n) + "; needs " + Ema.barsNeeded(200) + ")"
            gDays == 0 -> "50 EMA crossed $gSide the 200 EMA on the latest bar"
            gDays > 0 -> "50 EMA $gSide the 200 EMA for " + daysWord(gDays)
            else -> "50 EMA $gSide the 200 EMA for more than " + daysWord(minOf(GOLDEN_CAP, i - gFirst))
        }

        val rsi14 = z(Ind.rsi(s.c, i, 14))
        val rsi2 = z(Ind.rsi(s.c, i, 2))
        val atr = z(Ind.atr(s.h, s.l, s.c, i, Params.ATR_PERIOD))
        fun ret(k: Int) = z(Ind.ret(s.c, i, k) * 100.0)
        val vs21 = vsIndex(s, x.index, 21); val vs63 = vsIndex(s, x.index, 63); val vs126 = vsIndex(s, x.index, 126)
        val high52 = z(Ind.highest(s.h, i, minOf(250, n))); val low52 = z(Ind.lowest(s.l, i, minOf(250, n)))
        val high20 = z(Ind.highest(s.h, i, minOf(20, n))); val low20 = z(Ind.lowest(s.l, i, minOf(20, n)))
        val fromHigh = if (high52 > 0) (c / high52 - 1) * 100.0 else 0.0
        val fromLow = if (low52 > 0) (c / low52 - 1) * 100.0 else 0.0
        val resistance = if (high52 > 0 && high52 <= c * 1.03) high52 else high20

        val adv = try { Advisor.check(s, x.index, frac, x.weightPct) } catch (t: Throwable) { null }
        val trail = if (adv != null && adv.dataOk) adv.stop else 0.0

        val m = LinkedHashMap<String, Any?>()
        m["ok"] = true; m["sym"] = x.sym; m["name"] = x.name; m["tracked"] = x.tracked; m["isHolding"] = x.isHolding; m["inWatch"] = x.inWatch
        m["asOf"] = x.asOf; m["live"] = x.live; m["barDay"] = yyyymmdd(s.d[i]); m["bars"] = n
        m["ltp"] = Fmt.r2(c); m["prevClose"] = Fmt.r2(prev); m["dayChgPct"] = if (prev > 0) Fmt.r2((c / prev - 1) * 100.0) else 0.0
        m["open"] = Fmt.r2(s.o[i]); m["dayHigh"] = Fmt.r2(s.h[i]); m["dayLow"] = Fmt.r2(s.l[i])
        m["volume"] = Math.rint(s.v[i]); m["avgVol20"] = Math.rint(avgVol); m["volRatio"] = Fmt.r2(volRatio)
        m["turnoverCr"] = Fmt.r2(turnover / 1e7); m["liquid"] = liquid
        m["ema"] = linkedMapOf("e10" to Fmt.r2(e10), "e20" to Fmt.r2(e20), "e50" to Fmt.r2(e50), "e100" to Fmt.r2(e100), "e200" to Fmt.r2(e200))
        m["emaDist"] = linkedMapOf("e10" to dist(e10), "e20" to dist(e20), "e50" to dist(e50), "e100" to dist(e100), "e200" to dist(e200))
        m["stack"] = linkedMapOf("label" to st.label, "text" to st.text)
        m["cross"] = linkedMapOf("state" to (if (cAbove) "ABOVE" else "BELOW"), "daysSince" to cDays, "gapPct" to Fmt.r2(gap), "converging" to converging, "text" to crossText)
        m["golden"] = linkedMapOf("state" to (if (gAbove) "ABOVE" else "BELOW"), "daysSince" to gDays, "text" to goldenText)
        m["rsi14"] = Fmt.r1(rsi14); m["rsi2"] = Fmt.r1(rsi2); m["atr"] = Fmt.r2(atr); m["atrPct"] = if (c > 0) Fmt.r2(atr / c * 100.0) else 0.0
        m["ret"] = linkedMapOf("d5" to Fmt.r2(ret(5)), "d21" to Fmt.r2(ret(21)), "d63" to Fmt.r2(ret(63)), "d126" to Fmt.r2(ret(126)), "d250" to Fmt.r2(ret(250)))
        m["vsNifty"] = linkedMapOf("d21" to Fmt.r2(z(vs21)), "d63" to Fmt.r2(z(vs63)), "d126" to Fmt.r2(z(vs126)))
        m["high52"] = Fmt.r2(high52); m["low52"] = Fmt.r2(low52); m["fromHighPct"] = Fmt.r2(fromHigh); m["fromLowPct"] = Fmt.r2(fromLow)
        m["high20"] = Fmt.r2(high20); m["low20"] = Fmt.r2(low20)
        m["levels"] = linkedMapOf("support" to Fmt.r2(low20), "resistance" to Fmt.r2(resistance), "trailStop" to Fmt.r2(trail))
        m["strength"] = if (adv != null && adv.dataOk) adv.strength else 0
        m["verdict"] = linkedMapOf("label" to (adv?.verdict ?: Advisor.WATCH), "headline" to (adv?.headline ?: "Cannot judge this one yet"))

        // ---- checks, most important first
        val checks = ArrayList<Map<String, Any?>>()
        fun chk(label: String, ok: Boolean, text: String) { checks.add(linkedMapOf("label" to label, "ok" to ok, "text" to text)) }
        val notEnough = "Not enough history (" + daysWord(n) + ")"
        val liqText = "₹" + Fmt.p1(turnover / 1e7) + " cr traded a day on average over 20 days (needs ₹" + Fmt.p1(Params.MIN_AVG_TURNOVER_RS / 1e7) + " cr)" +
            (if (c < Params.MIN_PRICE) "; price under ₹" + Fmt.num(Params.MIN_PRICE) else "")
        val liquidOk = liquid && c >= Params.MIN_PRICE
        if (n < 21) chk("Liquid enough to trade", false, notEnough)
        else if (!liquidOk) chk("Not liquid — thinly traded", false, liqText)
        if (e200 > 0) chk(if (c > e200) "Above the 200 EMA" else "Below the 200 EMA", c > e200, Fmt.rs(c) + " vs " + Fmt.rs(e200) + " (" + Fmt.pct(dist(e200)) + ")")
        else chk("Above the 200 EMA", false, "Not enough history (" + daysWord(n) + "; needs " + Ema.barsNeeded(200) + ")")
        if (e50 > 0) chk(if (c > e50) "Above the 50 EMA" else "Below the 50 EMA", c > e50, Fmt.rs(c) + " vs " + Fmt.rs(e50) + " (" + Fmt.pct(dist(e50)) + ")")
        else chk("Above the 50 EMA", false, "Not enough history (" + daysWord(n) + "; needs " + Ema.barsNeeded(50) + ")")
        if (n >= 3) chk(if (cAbove) "10 EMA above the 20 EMA" else "10 EMA below the 20 EMA", cAbove,
            Fmt.rs(L.e10[i]) + " vs " + Fmt.rs(L.e20[i]) + " · " + (if (cDays >= 0) "crossed " + (if (cDays == 0) "on the latest bar" else daysWord(cDays) + " ago") else "no cross in " + daysWord(minOf(CROSS_CAP, maxOf(0, n - 2)))))
        else chk("10 EMA above the 20 EMA", false, notEnough)
        if (gKnown) chk(if (gAbove) "50 EMA above the 200 EMA" else "50 EMA below the 200 EMA", gAbove, Fmt.rs(e50) + " vs " + Fmt.rs(e200) + " · " + goldenText.replaceFirst("50 EMA ", "").replaceFirst("$gSide the 200 EMA ", ""))
        else chk("50 EMA above the 200 EMA", false, "Not enough history (" + daysWord(n) + "; needs " + Ema.barsNeeded(200) + ")")
        if (rsi14 > 0) chk("RSI(14) in a healthy zone", rsi14 in 40.0..70.0, "RSI " + Fmt.p1(rsi14) + when {
            rsi14 > 70 -> " — above 70, overbought"
            rsi14 < 40 -> " — below 40, weak momentum"
            else -> " — between 40 and 70"
        })
        else chk("RSI(14) in a healthy zone", false, notEnough)
        if (avgVol > 0) chk("Volume above its 20-day average", volRatio >= 1.0, Fmt.p1(volRatio) + "× the 20-day average (" + Fmt.num(Math.rint(s.v[i])) + " vs " + Fmt.num(Math.rint(avgVol)) + " shares" + (if (frac < 1.0) ", projected to a full day)" else ")"))
        else chk("Volume above its 20-day average", false, notEnough)
        if (n >= 250) chk("Within 10% of the 52-week high", fromHigh >= -10.0, Fmt.rs(c) + " is " + Fmt.p1(-fromHigh) + "% below the 52-week high " + Fmt.rs(high52))
        else chk("Within 10% of the 52-week high", high52 > 0 && fromHigh >= -10.0, Fmt.rs(c) + " vs " + Fmt.rs(high52) + " high of the " + daysWord(n) + " available (" + Fmt.pct(fromHigh) + ")")
        if (checks.size < 9) {
            if (vs63.isNaN()) chk("Beating Nifty over 3 months", false, if (n < 64) "Not enough history (" + daysWord(n) + "; needs 64)" else "Nifty data not loaded yet")
            else chk("Beating Nifty over 3 months", vs63 > 0, "Stock " + Fmt.pct(ret(63)) + " vs Nifty " + Fmt.pct(ret(63) - vs63) + " (" + (if (vs63 >= 0) "+" else "") + Fmt.p1(vs63) + " points)")
        }
        if (checks.size < 9 && n >= 21 && liquidOk) chk("Liquid enough to trade", true, liqText)
        m["checks"] = checks

        m["setups"] = setups(x, L)
        m["summary"] = summary(x, c, e50, e200, st, cAbove, cDays, rsi14, ret(63), vs63, low20, high20, liquid, turnover)
        return m
    }

    private fun summary(
        x: StockInput, c: Double, e50: Double, e200: Double, st: Stack, cAbove: Boolean, cDays: Int, rsi: Double,
        r63: Double, vs63: Double, low20: Double, high20: Double, liquid: Boolean, turnover: Double
    ): String {
        val n = x.s.n
        val who = x.name.ifBlank { x.sym }
        val s1 = when {
            e200 > 0 && st.label == "BULLISH" -> "$who at " + Fmt.rs(c) + " is above its 200 EMA (" + Fmt.rs(e200) + ") and its EMAs are stacked in a rising order."
            e200 > 0 && st.label == "BEARISH" -> "$who at " + Fmt.rs(c) + " is below its 200 EMA (" + Fmt.rs(e200) + ") and its EMAs are stacked in a falling order."
            e200 > 0 -> "$who at " + Fmt.rs(c) + " is " + (if (c > e200) "above" else "below") + " its 200 EMA (" + Fmt.rs(e200) + ") and " +
                (if (c > e50) "above" else "below") + " its 50 EMA (" + Fmt.rs(e50) + "), with the averages in mixed order."
            e50 > 0 -> "$who at " + Fmt.rs(c) + " is " + (if (c > e50) "above" else "below") + " its 50 EMA (" + Fmt.rs(e50) + "); there is not enough history yet for the 200 EMA."
            else -> "$who at " + Fmt.rs(c) + " has only " + daysWord(n) + " of price history, so the longer averages are not available yet."
        }
        val parts = ArrayList<String>()
        if (rsi > 0) parts.add("RSI(14) is " + Math.round(rsi))
        if (n > 63) parts.add("the 3-month return is " + Fmt.pct(r63) + (if (!vs63.isNaN()) " (" + Fmt.p1(Math.abs(vs63)) + " points " + (if (vs63 >= 0) "ahead of" else "behind") + " Nifty)" else ""))
        if (n >= 3) parts.add("the 10 EMA is " + (if (cAbove) "above" else "below") + " the 20 EMA" + (if (cDays == 0) " after crossing on the latest bar" else if (cDays > 0) " since crossing " + daysWord(cDays) + " ago" else ""))
        val s2 = if (parts.isEmpty()) "" else parts[0].replaceFirstChar { it.uppercase() } +
            (if (parts.size == 3) ", " + parts[1] + " and " + parts[2] else if (parts.size == 2) " and " + parts[1] else "") + "."
        val change = when {
            e50 > 0 && c > e50 -> "a close below the 50 EMA at " + Fmt.rs(e50) + " would weaken the trend; the 20-day low is " + Fmt.rs(low20)
            e50 > 0 -> "a close back above the 50 EMA at " + Fmt.rs(e50) + " would improve the picture; the 20-day high is " + Fmt.rs(high20)
            else -> "a move outside the recent range of " + Fmt.rs(low20) + "–" + Fmt.rs(high20) + " would show the next direction"
        }
        val s3 = if (!liquid) "It is thinly traded (about ₹" + Fmt.p1(turnover / 1e7) + " cr a day), so prices can jump; " + change + "."
        else change.replaceFirstChar { it.uppercase() } + "."
        return listOf(s1, s2, s3).filter { it.isNotBlank() }.joinToString(" ")
    }

    // --------------------------------------------------------------------------------------- setups

    private fun setups(x: StockInput, L: EmaLines): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        fun add(id: String, label: String, match: Boolean, text: String) { out.add(linkedMapOf("id" to id, "label" to label, "match" to match, "text" to text)) }
        val s = x.s
        val n = s.n
        // the Setups-tab screen, same predicate as computeWatch()
        try {
            val g = EmaScreen.gate(s)
            val crossed = Ema.daysSince(L.e10, L.e20, n, 0, CROSS_CAP)
            val maxD = Params.WATCH_MAX_DAYS_SINCE
            val listedFor = if (maxD == 0) "the screen lists day-0 crosses only" else "the screen lists crosses up to " + daysWord(maxD) + " old"
            when {
                g == EmaScreen.SHORT -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "Needs " + EmaScreen.MIN_BARS + " days of history (has $n)")
                g == EmaScreen.CHEAP -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "Price is under ₹" + Fmt.num(Params.MIN_PRICE))
                g == EmaScreen.THIN -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "Too thinly traded: ₹" + Fmt.p1(EmaScreen.turnover20(s) / 1e7) + " cr a day (needs ₹" + Fmt.p1(Params.MIN_AVG_TURNOVER_RS / 1e7) + " cr)")
                else -> {
                    val since = if (EmaScreen.stacked(L)) EmaScreen.since(L) else -1
                    val i = n - 1
                    when {
                        since == 0 -> add("EMA_CROSS", "Fresh 10/20 EMA cross", true, "10 EMA crossed above the 20 EMA on the latest bar and is above the 50, 100 and 200 EMAs")
                        since > 0 -> add("EMA_CROSS", "Fresh 10/20 EMA cross", true, "Crossed " + daysWord(since) + " ago — $listedFor")
                        !(L.e10[i] > L.e20[i]) -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "10 EMA is below the 20 EMA" + (if (crossed >= 0) " (crossed under " + daysWord(crossed) + " ago)" else ""))
                        !EmaScreen.stacked(L) -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "10 EMA is above the 20 EMA but not above all of the 50, 100 and 200 EMAs")
                        else -> add("EMA_CROSS", "Fresh 10/20 EMA cross", false, (if (crossed >= 0) "Crossed " + daysWord(crossed) + " ago" else "Crossed more than " + daysWord(CROSS_CAP) + " ago") + " — $listedFor")
                    }
                }
            }
        } catch (t: Throwable) {
            add("EMA_CROSS", "Fresh 10/20 EMA cross", false, "Could not check this screen")
        }
        // every enabled strategy, judged on the latest bar
        val i = n - 1
        val f = Feat()
        val fok = try { f.compute(s, i) } catch (t: Throwable) { false }
        for (sg in Strategies.all) {
            if (!sg.enabled()) continue
            try {
                val needs = NEEDS_CTX.contains(sg.id)
                if (needs && x.scanCtx == null) { add(sg.id, sg.label, false, "Needs a full scan to compare it with the rest of the market — run a scan first"); continue }
                val ctx = Ctx()
                x.scanCtx?.let { sc ->
                    ctx.momCutoff = sc.momCutoff; ctx.leaderCutoff = sc.leaderCutoff; ctx.breadth = sc.breadth
                    ctx.monthlyCutoff = sc.monthlyCutoff; ctx.dipCutoff = sc.dipCutoff
                }
                ctx.regime = x.regime
                ctx.sessionFraction = sessionFraction(s, x.now)
                val su = if (fok) Strategies.evaluate(sg, s, i, f, ctx) else null
                if (su != null) {
                    val paused = x.stratVerdicts[sg.id] != "LIVE"
                    add(sg.id, sg.label, true, "Qualifies on the latest bar: " + (su.reasons.firstOrNull() ?: "all rules met") +
                        (if (paused) " (this rule is not passing its test right now, so the app does not post it)" else ""))
                } else add(sg.id, sg.label, false, whyNot(sg, s, i, f, fok, ctx))
            } catch (t: Throwable) {
                add(sg.id, sg.label, false, "Could not check this rule")
            }
        }
        return out
    }

    private val NEEDS_CTX = setOf("WASHOUT_DIP", "MONTHLY_LEADERS", "MOM_PULLBACK", "LEADER_DIP", "WEEKLY_LEADERS")
    private const val NOT_RANKED = "Not enough stocks were ranked in the last scan to judge this"

    private fun monthOf(epochDay: Int): Int {
        val d = java.time.LocalDate.ofEpochDay(epochDay.toLong())
        return d.year * 12 + d.monthValue
    }

    /** The first rule (in the strategy's own order) that fails on bar i. */
    private fun whyNot(sg: Strategy, s: Series, i: Int, f: Feat, fok: Boolean, ctx: Ctx): String {
        if (!fok) return if (i < Params.MIN_BARS - 1) "Needs " + Params.MIN_BARS + " days of history (has " + (i + 1) + ")" else "Price data is incomplete"
        if (Params.BLOCK_BUYS_IN_BEAR && ctx.regime < 0 && !sg.allowInBear()) return "Nifty is in a downtrend (BEAR) — this rule does not fire then"
        if (f.c < Params.MIN_PRICE) return "Price is under ₹" + Fmt.num(Params.MIN_PRICE)
        if (!(f.avgTurnover20 >= Params.MIN_AVG_TURNOVER_RS)) return "Too thinly traded: ₹" + Fmt.p1(z(f.avgTurnover20) / 1e7) + " cr a day (needs ₹" + Fmt.p1(Params.MIN_AVG_TURNOVER_RS / 1e7) + " cr)"
        val below200 = "Below its 200-day average " + Fmt.rs(f.sma200)
        val trend = "Needs price above the 50-day average (" + Fmt.rs(f.sma50) + ") and the 50-day above the 200-day (" + Fmt.rs(f.sma200) + ")"
        when (sg.id) {
            "WASHOUT_DIP" -> {
                if (ctx.breadth.isNaN()) return NOT_RANKED
                if (!(ctx.breadth < Params.WO_BREADTH_MAX)) return "Only fires in a market-wide sell-off: " + Math.round(ctx.breadth * 100) + "% of stocks are above their 50-day average (needs under " + Math.round(Params.WO_BREADTH_MAX * 100) + "%)"
                if (!(f.c > f.sma200)) return below200
                val r = Ind.rsi(s.c, i, 2)
                if (r.isNaN() || !(r < Params.WO_RSI_MAX)) return "2-day RSI is " + Fmt.p1(z(r)) + " (needs under " + Fmt.p1(Params.WO_RSI_MAX) + ")"
            }
            "MONTHLY_LEADERS" -> {
                if (ctx.monthlyCutoff.isNaN()) return NOT_RANKED
                if (i < 1 || monthOf(s.d[i]) == monthOf(s.d[i - 1])) return "Only buys on the first trading day of a month"
                if (!(f.c > f.sma200)) return below200
                val sc = Strategies.riskAdjMomentum(s, i)
                if (sc.isNaN() || sc < ctx.monthlyCutoff) return "Not among the " + Params.ML_TOP_N + " steadiest strong stocks of the last 6 months"
            }
            "MOM_PULLBACK" -> {
                if (ctx.momCutoff.isNaN()) return NOT_RANKED
                val mom = Strategies.momentum(s, i)
                if (mom.isNaN() || mom < ctx.momCutoff) return "6-month gain " + Fmt.pct(z(mom) * 100) + " is under the top-" + Math.round(Params.MP_TOP_FRACTION * 100) + "% cut-off (" + Fmt.pct(ctx.momCutoff * 100) + ")"
                if (!(f.c > f.sma50 && f.sma50 > f.sma200)) return trend
                val hi20 = Ind.highest(s.h, i, 20)
                val pb = (hi20 - f.c) / hi20 * 100.0
                val near20 = Math.abs(f.c - f.sma20) / f.sma20 * 100.0
                if (!(pb >= Params.MP_PULLBACK_MIN_PCT && pb <= Params.MP_PULLBACK_MAX_PCT) && near20 > Params.MP_NEAR_20DMA_PCT)
                    return "Not in a " + Fmt.num(Params.MP_PULLBACK_MIN_PCT) + "–" + Fmt.num(Params.MP_PULLBACK_MAX_PCT) + "% dip (now " + Fmt.p1(pb) + "% below the 20-day high " + Fmt.rs(hi20) + ") nor near the 20-day average"
                val body = maxOf(s.o[i - 1], s.c[i - 1])
                if (!(f.c > s.c[i - 1] && f.c > body)) return "Has not turned up: needs a close above yesterday's close and body high " + Fmt.rs(body)
            }
            "BREAKOUT_VOL" -> {
                val level = Ind.highest(s.c, i - 1, Params.BO_LOOKBACK)
                if (level.isNaN() || !(f.c > level)) return "Not at a new " + Params.BO_LOOKBACK + "-day closing high (needs above " + Fmt.rs(z(level)) + ")"
                val ext = (f.c / level - 1) * 100.0
                if (ext > Params.BO_MAX_EXT_PCT) return "Already " + Fmt.p1(ext) + "% above the breakout level (max " + Fmt.p1(Params.BO_MAX_EXT_PCT) + "%)"
                val rv = Ind.relVol(f.v, f.avgVol20, ctx.sessionFraction)
                if (rv < Params.BO_VOL_MULT) return "Volume " + Fmt.p1(rv) + "× the 20-day average (needs " + Fmt.p1(Params.BO_VOL_MULT) + "×)"
                if (Ind.ibs(f.h, f.l, f.c) < Params.BO_MIN_IBS) return "Not closing near the day's high"
            }
            "DIP_UPTREND" -> {
                if (!(f.c > f.sma200 && f.sma50 > f.sma200)) return "Needs a long-term uptrend: price above the 200-day average (" + Fmt.rs(f.sma200) + ") and the 50-day above it"
                if (!(Ind.ibs(f.h, f.l, f.c) < Params.DIP_IBS_MAX)) return "Not closing near the day's low"
                val r = Ind.rsi(s.c, i, Params.DIP_RSI_PERIOD)
                if (r.isNaN() || !(r < Params.DIP_RSI_MAX)) return Params.DIP_RSI_PERIOD.toString() + "-day RSI is " + Fmt.p1(z(r)) + " (needs under " + Fmt.p1(Params.DIP_RSI_MAX) + ")"
            }
            "LEADER_DIP" -> {
                if (ctx.dipCutoff.isNaN()) return NOT_RANKED
                if (!(f.c > f.sma50 && f.sma50 > f.sma200)) return trend
                val p50 = Ind.sma(s.c, i - Params.LD_SLOPE_BARS, 50)
                if (p50.isNaN() || !(f.sma50 > p50)) return "The 50-day average is not rising"
                val mom = Strategies.momentum(s, i)
                if (mom.isNaN() || mom < ctx.dipCutoff) return "Not among the market's strongest stocks (6-month gain " + Fmt.pct(z(mom) * 100) + ", needs " + Fmt.pct(ctx.dipCutoff * 100) + ")"
                val r = Ind.rsi(s.c, i, 2)
                if (r.isNaN() || !(r < Params.LD_RSI_MAX)) return "No sharp dip: 2-day RSI is " + Fmt.p1(z(r)) + " (needs under " + Fmt.p1(Params.LD_RSI_MAX) + ")"
            }
            "WEEKLY_LEADERS" -> {
                if (ctx.leaderCutoff.isNaN()) return NOT_RANKED
                if (i < 1 || Clock.weekIndex(s.d[i]) == Clock.weekIndex(s.d[i - 1])) return "Only buys on the first trading day of a week"
                if (!(f.c > f.sma200)) return below200
                val sc = Strategies.leaderScore(s, i)
                if (sc.isNaN() || sc < ctx.leaderCutoff) return "Not among the " + Params.WL_TOP_N + " strongest stocks over 3–6 months"
            }
        }
        return "Its rules do not line up on the latest bar"
    }

    // --------------------------------------------------------------------------------------- myWatch rows

    fun watchItem(sym: String, name: String, s: Series?, index: Series?, now: Long): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["sym"] = sym; m["name"] = name
        if (s == null || s.n == 0) {
            m["ltp"] = 0.0; m["dayChgPct"] = 0.0; m["spark"] = emptyList<Any?>(); m["strength"] = 0; m["stack"] = "MIXED"
            m["crossText"] = "No price data yet — loads on the next scan"; m["dataOk"] = false
            return m
        }
        val n = s.n
        val i = n - 1
        val c = s.c[i]
        val prev = if (n >= 2) s.c[i - 1] else 0.0
        val L = EmaLines(s)
        val spark = ArrayList<Any?>(30)
        for (j in maxOf(0, n - 30) until n) spark.add(Fmt.r2(s.c[j]))
        val adv = try { Advisor.check(s, index, sessionFraction(s, now), 0.0) } catch (t: Throwable) { null }
        val above = L.e10[i] > L.e20[i]
        val d = Ema.daysSince(L.e10, L.e20, n, 0, CROSS_CAP)
        val side = if (above) "above" else "below"
        m["ltp"] = Fmt.r2(c); m["dayChgPct"] = if (prev > 0) Fmt.r2((c / prev - 1) * 100.0) else 0.0
        m["spark"] = spark; m["strength"] = if (adv != null && adv.dataOk) adv.strength else 0
        m["stack"] = stack(L, i).label
        m["crossText"] = when {
            n < 3 -> "Too little history for the 10/20 EMA cross"
            d == 0 -> "10 EMA just crossed $side 20 EMA"
            d > 0 -> "10 EMA $side 20 EMA for " + daysWord(d)
            else -> "10 EMA $side 20 EMA for " + CROSS_CAP + "+ days"
        }
        m["dataOk"] = true
        return m
    }
}
