package com.boomai.swingedge.core

/*
 * Index options, PAPER ONLY. One simple, fixed rule, tracked with the app's real delay (it only acts at its
 * scans: every minute during market hours via the live watch, else the 15-minute scan) and realistic costs, so the record shows what following it would really have done.
 * Premiums are ESTIMATED with Black-Scholes from the index level and India VIX (no free live option-chain feed),
 * plus 1% slippage on each side. Nothing here throws.
 *
 * Rule (fixed in advance, not tuned):
 *  - window 09:45–14:30 IST, at most one trade per index per day, none on expiry day (no 0-DTE)
 *  - CALL when daily close > EMA20 > EMA50 and the index trades above yesterday's high
 *  - PUT  when daily close < EMA20 < EMA50 and the index trades below yesterday's low
 *  - at-the-money strike, nearest expiry that is not today
 *  - stop-loss: premium −30%; target: premium +50%; time exit: first scan at/after 15:15
 */

class OptEvent(val buy: Boolean, val t: OptTrade, val title: String, val body: String)

class OptCfg(val name: String, val yahoo: String, val lot: Int, val step: Int, val weekly: Boolean, val ivMult: Double)

class OptTrade(
    var id: String = "", var idx: String = "", var side: String = "", var strike: Int = 0, var expiry: Int = 0,
    var openedAt: Long = 0, var spot0: Double = 0.0, var entry: Double = 0.0, var stop: Double = 0.0, var target: Double = 0.0,
    var lot: Int = 0, var status: String = "OPEN", var closedAt: Long = 0, var exit: Double = 0.0, var reason: String = "",
    var last: Double = 0.0, var pnlRs: Double = 0.0,
    /** lots for the user's capital, and the index levels where the premium would hit the stop / target */
    var lots: Int = 1, var spotStop: Double = 0.0, var spotTgt: Double = 0.0
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "idx" to idx, "side" to side, "strike" to strike, "expiry" to expiry, "openedAt" to openedAt, "spot0" to spot0,
        "entry" to entry, "stop" to stop, "target" to target, "lot" to lot, "status" to status, "closedAt" to closedAt,
        "exit" to exit, "reason" to reason, "last" to last, "pnlRs" to pnlRs, "lots" to lots, "spotStop" to spotStop, "spotTgt" to spotTgt,
        "limit" to Math.rint(entry * 1.01 * 20) / 20
    )
    companion object {
        fun from(m: Map<String, Any?>): OptTrade = OptTrade(
            m.str("id"), m.str("idx"), m.str("side"), m.int("strike"), m.int("expiry"), m.long("openedAt"), m.num("spot0", 0.0),
            m.num("entry", 0.0), m.num("stop", 0.0), m.num("target", 0.0), m.int("lot"), m.str("status").ifEmpty { "OPEN" },
            m.long("closedAt"), m.num("exit", 0.0), m.str("reason"), m.num("last", 0.0), m.num("pnlRs", 0.0),
            maxOf(1, m.int("lots", 1)), m.num("spotStop", 0.0), m.num("spotTgt", 0.0))
    }
}

object Options {
    val CFG = listOf(
        OptCfg("NIFTY", "^NSEI", 65, 50, true, 1.0),
        OptCfg("BANKNIFTY", "^NSEBANK", 30, 100, false, 1.25)
    )
    const val VIX = "^INDIAVIX"
    const val START_MIN = 9 * 60 + 45
    const val LAST_ENTRY_MIN = 14 * 60 + 30
    const val EXIT_MIN = 15 * 60 + 15
    const val SL = 0.30
    const val TGT = 0.50
    const val SLIP = 0.01
    /** brokerage ₹20 per order x2 + exchange/SEBI/stamp/GST, plus STT 0.1% of sell premium — per lot, round trip */
    const val FIXED_COST = 50.0
    const val PCT_COST = 0.0015
    const val MAX_KEEP = 400

    // ---------------------------------------------------------------- pricing

    private fun ncdf(x: Double): Double {
        // Abramowitz-Stegun 26.2.17
        val t = 1.0 / (1.0 + 0.2316419 * Math.abs(x))
        val d = 0.3989422804014327 * Math.exp(-x * x / 2)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (x >= 0) 1 - p else p
    }

    /** Black-Scholes premium; [years] floored at a few hours so expiry-day maths stays finite. */
    fun price(call: Boolean, spot: Double, strike: Double, years: Double, iv: Double, r: Double = 0.065): Double {
        if (!(spot > 0) || !(strike > 0) || !(iv > 0)) return Double.NaN
        val t = maxOf(years, 2.0 / (252 * 6.25))
        val sq = iv * Math.sqrt(t)
        val d1 = (Math.log(spot / strike) + (r + iv * iv / 2) * t) / sq
        val d2 = d1 - sq
        val v = if (call) spot * ncdf(d1) - strike * Math.exp(-r * t) * ncdf(d2) else strike * Math.exp(-r * t) * ncdf(-d2) - spot * ncdf(-d1)
        return maxOf(0.05, v)
    }

    /** Expiry (epoch day): weekly = next Tuesday strictly after today; monthly = last Tuesday of the month, strictly after today. */
    fun expiry(day: Int, weekly: Boolean): Int {
        if (weekly) {
            var d = day + 1
            while (Clock.dow(d) != 1) d++
            return d
        }
        var m = day
        while (true) {
            val e = lastTuesday(m)
            if (e > day) return e
            m = e + 7
        }
    }

    private fun lastTuesday(day: Int): Int {
        val ld = java.time.LocalDate.ofEpochDay(day.toLong())
        var x = ld.withDayOfMonth(ld.lengthOfMonth())
        while (x.dayOfWeek.value != 2) x = x.minusDays(1)
        return x.toEpochDay().toInt()
    }

    fun yearsTo(nowMs: Long, expiryDay: Int): Double {
        val expMs = expiryDay.toLong() * 86_400_000L + (15 * 60 + 30 - 330) * 60_000L   // 15:30 IST
        return maxOf(0.0, (expMs - nowMs) / (365.0 * 86_400_000.0))
    }

    fun costRs(entry: Double, exit: Double, lot: Int): Double = FIXED_COST + PCT_COST * (entry + exit) * lot

    // ---------------------------------------------------------------- signal

    private fun emaLast(c: DoubleArray, n: Int, len: Int): Double {
        if (n < len) return Double.NaN
        val k = 2.0 / (len + 1); var e = c[0]
        for (i in 1 until n) e = c[i] * k + e * (1 - k)
        return e
    }

    /** "CE", "PE" or "" from daily bars (completed days before today) and the live index level. */
    fun signal(daily: Series?, today: Int, spot: Double): String {
        if (daily == null || !(spot > 0)) return ""
        var n = daily.n
        while (n > 0 && daily.d[n - 1] >= today) n--          // only finished days
        if (n < 60) return ""
        val c = daily.c
        val e20 = emaLast(c, n, 20); val e50 = emaLast(c, n, 50); val cl = c[n - 1]
        val ph = daily.h[n - 1]; val pl = daily.l[n - 1]
        if (cl > e20 && e20 > e50 && spot > ph) return "CE"
        if (cl < e20 && e20 < e50 && spot < pl) return "PE"
        return ""
    }

    // ---------------------------------------------------------------- one scan

    /**
     * Updates the paper book at a scan. [spots] = live index levels by Yahoo symbol; [daily] = daily bars by Yahoo symbol;
     * [vix] = India VIX level (percent). Returns notes for the feed.
     */
    /** risk per trade for sizing: 3% of capital; premium outlay capped at 25% of capital */
    const val RISK_PCT = 3.0
    const val CASH_PCT = 25.0

    fun lotsFor(capital: Double, entry: Double, lot: Int): Int {
        if (!(capital > 0) || !(entry > 0)) return 1
        val riskLot = entry * SL * lot + costRs(entry, entry * (1 - SL), lot)
        val n = minOf(Math.floor(capital * RISK_PCT / 100 / riskLot), Math.floor(capital * CASH_PCT / 100 / (entry * lot))).toInt()
        if (n >= 1) return n
        // one lot is still allowed if it risks at most MAX_ONE_LOT_PCT of capital and fits the cash cap
        return if (riskLot <= capital * MAX_ONE_LOT_PCT / 100 && entry * lot <= capital * CASH_PCT / 100) 1 else 0
    }
    const val MAX_ONE_LOT_PCT = 5.0

    /** Index level at which the estimated premium equals [target] (bisection; NaN if not reachable within ±15%). */
    fun spotFor(call: Boolean, strike: Double, years: Double, iv: Double, target: Double, spot0: Double): Double {
        var lo = spot0 * 0.85; var hi = spot0 * 1.15
        val f = { x: Double -> price(call, x, strike, years, iv) - target }
        if (f(lo) * f(hi) > 0) return Double.NaN
        repeat(60) { val mid = (lo + hi) / 2; if (f(lo) * f(mid) <= 0) hi = mid else lo = mid }
        return Math.rint((lo + hi) / 2)
    }

    fun step(book: MutableList<OptTrade>, nowMs: Long, spots: Map<String, Double>, daily: Map<String, Series?>, vix: Double, capital: Double = 100000.0): List<OptEvent> {
        val notes = ArrayList<OptEvent>()
        try {
            val day = Clock.epochDay(nowMs); val min = Clock.minuteOfDay(nowMs)
            val iv0 = if (vix > 5 && vix < 100) vix / 100.0 else Double.NaN
            // 1. manage open trades
            for (t in book) {
                if (t.status != "OPEN") continue
                val cfg = CFG.firstOrNull { it.name == t.idx } ?: continue
                val spot = spots[cfg.yahoo] ?: continue
                val iv = if (iv0.isNaN()) continue else iv0 * cfg.ivMult
                val px = price(t.side == "CE", spot, t.strike.toDouble(), yearsTo(nowMs, t.expiry), iv)
                if (px.isNaN()) continue
                t.last = r2(px)
                val stale = Clock.epochDay(t.openedAt) != day
                val reason = when {
                    px <= t.stop -> "Stop-loss"
                    px >= t.target -> "Target"
                    min >= EXIT_MIN || stale -> "Time exit 3:15 pm"
                    else -> ""
                }
                if (reason.isNotEmpty()) {
                    val ex = r2(px * (1 - SLIP))
                    t.status = "CLOSED"; t.closedAt = nowMs; t.exit = ex; t.reason = reason
                    t.pnlRs = r2((ex - t.entry) * t.lot - costRs(t.entry, ex, t.lot))
                    notes.add(OptEvent(false, t, "SELL ${t.idx} ${t.strike} ${t.side} now", "${reason}. Sell ${t.lots} lot${if (t.lots == 1) "" else "s"} (${t.lots * t.lot} qty) at market. Est. ₹${f2(ex)}, ${if (t.pnlRs >= 0) "+" else "−"}₹${Math.round(Math.abs(t.pnlRs * t.lots))}."))
                } else t.pnlRs = r2((px * (1 - SLIP) - t.entry) * t.lot - costRs(t.entry, px, t.lot))
            }
            // 2. new entries
            if (min in START_MIN..LAST_ENTRY_MIN && Clock.isWeekday(day) && !iv0.isNaN()) {
                for (cfg in CFG) {
                    if (book.any { it.idx == cfg.name && Clock.epochDay(it.openedAt) == day }) continue
                    val spot = spots[cfg.yahoo] ?: continue
                    val exp = expiry(day, cfg.weekly)
                    val side = signal(daily[cfg.yahoo], day, spot)
                    if (side.isEmpty()) continue
                    val strike = (Math.round(spot / cfg.step) * cfg.step).toInt()
                    val px = price(side == "CE", spot, strike.toDouble(), yearsTo(nowMs, exp), iv0 * cfg.ivMult)
                    if (px.isNaN() || px < 5) continue
                    val en = r2(px * (1 + SLIP))
                    val t = OptTrade(cfg.name + "-" + day + "-" + side, cfg.name, side, strike, exp, nowMs, r2(spot), en, r2(en * (1 - SL)), r2(en * (1 + TGT)), cfg.lot, last = r2(px))
                    val yrs = yearsTo(nowMs, exp); val iv = iv0 * cfg.ivMult
                    t.lots = lotsFor(capital, en, cfg.lot)
                    t.spotStop = spotFor(side == "CE", strike.toDouble(), yrs, iv, t.stop / (1 - SLIP), spot).let { if (it.isNaN()) 0.0 else it }
                    t.spotTgt = spotFor(side == "CE", strike.toDouble(), yrs, iv, t.target / (1 - SLIP), spot).let { if (it.isNaN()) 0.0 else it }
                    book.add(t)
                    if (t.lots >= 1) notes.add(OptEvent(true, t, "BUY ${cfg.name} $strike ${side}",
                        "${t.lots} lot${if (t.lots == 1) "" else "s"} (${t.lots * cfg.lot} qty), limit ₹${f2(Math.rint(en * 1.01 * 20) / 20)}. SL ₹${f2(t.stop)}${if (t.spotStop > 0) " (${cfg.name} ${Math.round(t.spotStop)})" else ""}, target ₹${f2(t.target)}. Exit by 3:15 pm."))
                }
            }
            while (book.size > MAX_KEEP) { val i = book.indexOfFirst { it.status != "OPEN" }; if (i < 0) break; book.removeAt(i) }
        } catch (e: Throwable) { }
        return notes
    }

    private fun r2(x: Double) = Math.rint(x * 100) / 100
    private fun f2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)

    fun stats(book: List<OptTrade>): Map<String, Any?> {
        val c = book.filter { it.status == "CLOSED" }
        val pnl = c.map { it.pnlRs }
        val mean = if (pnl.isEmpty()) 0.0 else pnl.average()
        val sd = if (pnl.size < 2) 0.0 else Math.sqrt(pnl.sumOf { (it - mean) * (it - mean) } / (pnl.size - 1))
        val t = if (sd > 0) mean / (sd / Math.sqrt(pnl.size.toDouble())) else 0.0
        val proven = pnl.size >= 40 && t >= 2.0
        return linkedMapOf("trades" to c.size, "wins" to c.count { it.pnlRs > 0 }, "netRs" to r2(pnl.sum()), "avgRs" to r2(mean),
            "worstRs" to (pnl.minOrNull() ?: 0.0), "t" to r2(t), "proven" to proven,
            "verdict" to when {
                c.size < 40 -> "Paper only. ${c.size} of 40 trades needed before judging."
                proven -> "Paper record positive and statistically solid over ${c.size} trades. Still estimated prices — start with 1 lot."
                else -> "Paper record over ${c.size} trades is not good enough. Don't trade this with real money."
            })
    }

    fun view(book: List<OptTrade>, spots: Map<String, Double>, vix: Double): Map<String, Any?> = linkedMapOf(
        "ok" to true,
        "open" to book.filter { it.status == "OPEN" }.map { it.toMap() },
        "closed" to book.filter { it.status == "CLOSED" }.sortedByDescending { it.closedAt }.take(60).map { it.toMap() },
        "stats" to stats(book),
        "spots" to CFG.map { linkedMapOf("idx" to it.name, "spot" to (spots[it.yahoo] ?: 0.0), "lot" to it.lot, "expiry" to expiryLabel(it)) },
        "vix" to vix,
        "rules" to listOf("Paper only, estimated premiums.", "Buys 09:45–2:30 pm, max 1 per index per day, never on expiry day.",
            "Stop −30%, target +50%, exit by 3:15 pm.", "Live watch checks Nifty & Bank Nifty every minute, 9:40 am–3:20 pm.")
    )

    private fun expiryLabel(c: OptCfg): String = if (c.weekly) "weekly, Tuesday" else "monthly, last Tuesday"
}
