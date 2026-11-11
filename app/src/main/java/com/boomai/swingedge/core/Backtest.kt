package com.boomai.swingedge.core

class BtTrade(
    val strategyId: String, val sym: String, val entryDay: Int, val exitDay: Int,
    val entry: Double, val sl: Double, val t1: Double, val t2: Double,
    val netPct: Double, val oos: Boolean, val complete: Boolean
)

class StratStats {
    var n = 0
    var winPct = 0.0
    var avgPct = 0.0
    var pf = 0.0
    var maxDdPct = 0.0
    var oosN = 0
    var oosAvgPct = 0.0
    /** Length of the out-of-sample period in months (for the plain-English verdict). */
    var oosMonths = 0
    var testMonths = 0
}

class BtResult(val stats: Map<String, StratStats>, val trades: List<BtTrade>, val symbols: Int, val days: Int, val millis: Long)

/**
 * Walk-forward simulation over cached daily bars. For each day D (in date order) every strategy is evaluated through
 * Strategies.evaluate() with features/context built from bars <= D only, entered at D's close, and then walked forward
 * with ExitModel. A strategy holds at most one trade per symbol at a time and opens at most BT_MAX_NEW_PER_DAY per day.
 */
object Backtest {

    fun run(universe: List<Series>, index: Series?, costPct: Double, progress: ((Int) -> Unit)? = null): BtResult {
        val t0 = System.nanoTime()
        val strategies = Strategies.all
        val ns = strategies.size
        val trades = ArrayList<BtTrade>()
        val stats = LinkedHashMap<String, StratStats>()
        for (st in strategies) stats[st.id] = StratStats()
        val nu = universe.size
        if (nu == 0) return BtResult(stats, trades, 0, 0, 0)

        // calendar = sorted distinct days over the universe
        var minDay = Int.MAX_VALUE
        var maxDay = Int.MIN_VALUE
        for (s in universe) if (s.n > 0) {
            if (s.d[0] < minDay) minDay = s.d[0]
            if (s.d[s.n - 1] > maxDay) maxDay = s.d[s.n - 1]
        }
        if (minDay > maxDay || maxDay - minDay > 20000) return BtResult(stats, trades, nu, 0, 0)
        val present = BooleanArray(maxDay - minDay + 1)
        for (s in universe) for (k in 0 until s.n) present[s.d[k] - minDay] = true
        var nd = 0
        for (b in present) if (b) nd++
        val cal = IntArray(nd)
        var q = 0
        for (k in present.indices) if (present[k]) cal[q++] = minDay + k

        val firstEval = Params.MIN_BARS - 1
        if (nd <= firstEval) return BtResult(stats, trades, nu, nd, (System.nanoTime() - t0) / 1_000_000)
        val nEval = nd - firstEval
        val split = firstEval + Math.floor(nEval * (1.0 - Params.OOS_FRACTION)).toInt()
        val splitDay = cal[minOf(split, nd - 1)]

        val ptr = IntArray(nu)
        val idx = IntArray(nu)
        val scratch = DoubleArray(nu)
        val busyUntil = Array(ns) { IntArray(nu) { Int.MIN_VALUE } }
        // exit days of the trades each strategy currently holds: a real account has only a few slots (BT_MAX_CONCURRENT)
        val openExits = Array(ns) { ArrayList<Int>() }
        val found = Array(ns) { ArrayList<Setup>() }
        val foundK = Array(ns) { ArrayList<Int>() }
        val feat = Feat()
        val ctx = Ctx()
        val pos = Pos()
        var ip = 0
        var lastPct = -1

        for (ci in firstEval until nd) {
            val day = cal[ci]
            for (k in 0 until nu) {
                val s = universe[k]
                var p = ptr[k]
                while (p < s.n && s.d[p] < day) p++
                ptr[k] = p
                idx[k] = if (p < s.n && s.d[p] == day) p else -1
            }
            // regime from the latest index bar on or before this day
            if (index != null) {
                while (ip + 1 < index.n && index.d[ip + 1] <= day) ip++
                ctx.regime = if (index.n > 0 && index.d[ip] <= day) Strategies.regime(index, ip) else 0
            } else ctx.regime = 0
            ctx.sessionFraction = 1.0
            Strategies.fillCutoffs(ctx, universe, idx, scratch)

            for (k in 0 until nu) {
                val i = idx[k]
                if (i < 0) continue
                val s = universe[k]
                if (!feat.compute(s, i)) continue
                for (si in 0 until ns) {
                    val su = Strategies.evaluate(strategies[si], s, i, feat, ctx) ?: continue
                    found[si].add(su)
                    foundK[si].add(k)
                }
            }
            for (si in 0 until ns) {
                val list = found[si]
                if (list.isEmpty()) continue
                val ks = foundK[si]
                var taken = 0
                val used = BooleanArray(list.size)
                val oe = openExits[si]
                var q = oe.size - 1
                while (q >= 0) { if (oe[q] < day) oe.removeAt(q); q-- }
                while (taken < Params.BT_MAX_NEW_PER_DAY && oe.size < Params.BT_MAX_CONCURRENT) {
                    var best = -1
                    for (j in list.indices) {
                        if (used[j]) continue
                        if (best < 0 || list[j].rank > list[best].rank) best = j
                    }
                    if (best < 0) break
                    used[best] = true
                    val k = ks[best]
                    if (day <= busyUntil[si][k]) continue
                    val su = list[best]
                    val s = universe[k]
                    val i = idx[k]
                    pos.open(su.entry, su.sl, su.t1, su.t2, 0.5)
                    var j = i + 1
                    while (j < s.n && !pos.closed) {
                        val dayNo = j - i + 1
                        ExitModel.step(pos, s.o[j], s.h[j], s.l[j], s.c[j], dayNo >= strategies[si].holdDays)
                        j++
                    }
                    val exitDay = if (pos.closed) s.d[j - 1] else Int.MAX_VALUE
                    busyUntil[si][k] = exitDay
                    oe.add(exitDay)
                    trades.add(
                        BtTrade(su.strategyId, su.sym, day, exitDay, su.entry, su.sl, su.t1, su.t2,
                            ExitModel.netPct(pos, costPct), ci >= split, pos.closed)
                    )
                    taken++
                }
                list.clear()
                ks.clear()
            }
            if (progress != null) {
                val pct = (ci - firstEval + 1) * 100 / nEval
                if (pct >= lastPct + 5) {
                    lastPct = pct
                    try {
                        progress(pct)
                    } catch (t: Throwable) {
                    }
                }
            }
        }

        // ---- statistics per strategy
        val lastDay = cal[nd - 1]
        for (st in strategies) {
            val ss = stats[st.id]!!
            ss.oosMonths = maxOf(1, Math.round((lastDay - splitDay) / 30.4).toInt())
            ss.testMonths = maxOf(1, Math.round((lastDay - cal[firstEval]) / 30.4).toInt())
            val mine = trades.filter { it.strategyId == st.id && it.complete }.sortedWith(compareBy({ it.exitDay }, { it.entryDay }, { it.sym }))
            if (mine.isEmpty()) continue
            var wins = 0
            var sum = 0.0
            var gp = 0.0
            var gl = 0.0
            var oosN = 0
            var oosSum = 0.0
            var cum = 0.0
            var peak = 0.0
            var dd = 0.0
            for (t in mine) {
                sum += t.netPct
                if (t.netPct > 0) {
                    wins++
                    gp += t.netPct
                } else gl -= t.netPct
                if (t.oos) {
                    oosN++
                    oosSum += t.netPct
                }
                cum += t.netPct * Params.BT_POSITION_WEIGHT
                if (cum > peak) peak = cum
                if (peak - cum > dd) dd = peak - cum
            }
            ss.n = mine.size
            ss.winPct = wins * 100.0 / mine.size
            ss.avgPct = sum / mine.size
            ss.pf = if (gl > 0) minOf(gp / gl, 99.0) else if (gp > 0) 99.0 else 0.0
            ss.maxDdPct = dd
            ss.oosN = oosN
            ss.oosAvgPct = if (oosN > 0) oosSum / oosN else 0.0
        }
        return BtResult(stats, trades, nu, nd, (System.nanoTime() - t0) / 1_000_000)
    }
}

/** The LIVE/PAUSED decision and its plain-English explanation. Thresholds are in Params.GATE_*. */
object Gate {
    fun passes(s: StratStats): Boolean =
        s.n >= Params.GATE_MIN_TRADES && s.avgPct >= Params.GATE_MIN_AVG_PCT && s.pf >= Params.GATE_MIN_PF &&
            s.oosN >= Params.GATE_MIN_OOS_TRADES && s.oosAvgPct > Params.GATE_MIN_OOS_AVG_PCT

    fun why(st: Strategy, s: StratStats): String {
        val recent = "the last " + Fmt.plural(s.oosMonths, "month")
        val period = "the " + Fmt.plural(s.testMonths, "month") + " tested"
        return when {
            !st.enabled() -> "Switched off in this version of the app."
            s.n == 0 -> "No trades at all in $period — nothing to judge it on."
            s.n < Params.GATE_MIN_TRADES ->
                "Only " + Fmt.plural(s.n, "test trade") + " in $period — too few to trust (needs " + Params.GATE_MIN_TRADES + ")."
            s.avgPct < 0 -> "Lost " + Fmt.p1(-s.avgPct) + "% per trade after charges over $period."
            s.avgPct < Params.GATE_MIN_AVG_PCT ->
                "Made only " + Fmt.p1(s.avgPct) + "% per trade after charges over $period — too thin (needs " + Params.GATE_MIN_AVG_PCT + "%)."
            s.pf < Params.GATE_MIN_PF ->
                "Profits were only " + Fmt.p1(s.pf) + "× the losses (needs " + Params.GATE_MIN_PF + "×)."
            s.oosN < Params.GATE_MIN_OOS_TRADES ->
                "Only " + Fmt.plural(s.oosN, "trade") + " in $recent — too few to confirm it still works."
            s.oosAvgPct <= Params.GATE_MIN_OOS_AVG_PCT ->
                if (s.oosAvgPct < 0) "Lost " + Fmt.p1(-s.oosAvgPct) + "% per trade after charges in $recent."
                else "Made nothing per trade after charges in $recent."
            else -> "Passed: " + s.n + " test trades, " + Math.round(s.winPct) + "% winners, " + Fmt.pct(s.avgPct) +
                " per trade after charges, and still positive in $recent (" + Fmt.pct(s.oosAvgPct) + ")."
        }
    }
}
