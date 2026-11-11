package com.boomai.swingedge.core

/** A candidate trade produced by a strategy for one symbol on one bar. t1/t2 of 0 mean "no price target". */
class Setup(
    val strategyId: String,
    val sym: String,
    val day: Int,
    val entry: Double,
    val sl: Double,
    val t1: Double,
    val t2: Double,
    val rank: Double,
    val reasons: List<String>
)

/** Cross-sectional and market context for one trading day, built only from bars up to that day. */
class Ctx {
    /** 1 = BULL, 0 = NEUTRAL, -1 = BEAR. */
    var regime = 0
    /** A stock's skip-month momentum must be >= this to be in the top slice of the universe; NaN = nobody qualifies. */
    var momCutoff = Double.NaN
    /** A stock's blended 63/126-day return must be >= this to be a weekly leader; NaN = nobody qualifies. */
    var leaderCutoff = Double.NaN
    /** Fraction of the session elapsed for the current bar (1.0 for a completed day). */
    var sessionFraction = 1.0
}

/** Per-bar features shared by all strategies. One instance is reused; compute() allocates nothing. */
class Feat {
    var ok = false
    var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0
    var sma20 = 0.0; var sma50 = 0.0; var sma200 = 0.0
    var atr = 0.0
    /** Averages over the 20 bars BEFORE the current bar (so a partial live bar never distorts them). */
    var avgVol20 = 0.0
    var avgTurnover20 = 0.0

    fun compute(s: Series, i: Int): Boolean {
        ok = false
        if (i < Params.MIN_BARS - 1 || i >= s.n) return false
        o = s.o[i]; h = s.h[i]; l = s.l[i]; c = s.c[i]; v = s.v[i]
        sma20 = Ind.sma(s.c, i, 20)
        sma50 = Ind.sma(s.c, i, 50)
        sma200 = Ind.sma(s.c, i, 200)
        atr = Ind.atr(s.h, s.l, s.c, i, Params.ATR_PERIOD)
        avgVol20 = Ind.sma(s.v, i - 1, 20)
        avgTurnover20 = Ind.avgTurnover(s.c, s.v, i - 1, 20)
        ok = !(sma200.isNaN() || atr.isNaN() || avgVol20.isNaN()) && atr > 0 && c > 0
        return ok
    }
}

/**
 * One rule set. `evaluate` sees the series only up to and including bar i (the "current bar": a finished day in the
 * backtest, today's partial bar in the live scan). The SAME object is used by both.
 */
interface Strategy {
    val id: String
    val label: String
    val desc: String
    fun enabled(): Boolean
    fun params(): Map<String, Double>
    fun evaluate(s: Series, i: Int, f: Feat, ctx: Ctx): Setup?
}

object Strategies {
    val all: List<Strategy> = listOf(MomPullback, BreakoutVol, DipUptrend, WeeklyLeaders)
    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }

    /** Common gates + the strategy's own rules. The single entry point for the live scan and the backtest. */
    fun evaluate(st: Strategy, s: Series, i: Int, f: Feat, ctx: Ctx): Setup? {
        if (!st.enabled() || !f.ok) return null
        if (Params.BLOCK_BUYS_IN_BEAR && ctx.regime < 0) return null
        if (f.c < Params.MIN_PRICE) return null
        if (!(f.avgTurnover20 >= Params.MIN_AVG_TURNOVER_RS)) return null
        val su = st.evaluate(s, i, f, ctx) ?: return null
        if (!(su.sl > 0) || !(su.sl < su.entry)) return null
        return su
    }

    /** Stop = entry - atrMult*ATR, no further than capPct below entry and no closer than MIN_STOP_PCT. */
    fun atrStop(entry: Double, atr: Double, atrMult: Double, capPct: Double): Double {
        var dist = atrMult * atr
        val cap = entry * capPct / 100.0
        if (dist > cap) dist = cap
        return entry - floorDist(entry, dist)
    }

    fun floorDist(entry: Double, dist: Double): Double {
        val min = entry * Params.MIN_STOP_PCT / 100.0
        return if (dist < min) min else dist
    }

    /** Skip-month momentum used for the universe ranking; NaN if not enough bars. */
    fun momentum(s: Series, i: Int): Double {
        val a = i - Params.MP_MOM_SKIP
        val b = a - Params.MP_MOM_LOOKBACK
        if (b < 0) return Double.NaN
        val base = s.c[b]
        return if (base > 0) s.c[a] / base - 1 else Double.NaN
    }

    /** Blended 63/126-day return for the weekly-leaders ranking; NaN if not enough bars. */
    fun leaderScore(s: Series, i: Int): Double {
        val r1 = Ind.ret(s.c, i, Params.WL_RET_SHORT)
        val r2 = Ind.ret(s.c, i, Params.WL_RET_LONG)
        if (r1.isNaN() || r2.isNaN()) return Double.NaN
        return Params.WL_WEIGHT_SHORT * r1 + (1 - Params.WL_WEIGHT_SHORT) * r2
    }

    /** Market regime from the index series at bar i: 1 BULL (above both DMAs), -1 BEAR (below both), else 0. */
    fun regime(index: Series?, i: Int): Int {
        if (index == null || i < 0 || i >= index.n) return 0
        val fast = Ind.sma(index.c, i, Params.REGIME_FAST_DMA)
        val slow = Ind.sma(index.c, i, Params.REGIME_SLOW_DMA)
        if (fast.isNaN() || slow.isNaN()) return 0
        val c = index.c[i]
        return if (c > fast && c > slow) 1 else if (c < fast && c < slow) -1 else 0
    }

    /**
     * Fills the cross-sectional cut-offs of [ctx] for one day. universe[k] is evaluated at bar idx[k] (-1 = no bar that
     * day). scratch must hold universe.size doubles. Uses only bars <= idx[k].
     */
    fun fillCutoffs(ctx: Ctx, universe: List<Series>, idx: IntArray, scratch: DoubleArray) {
        // momentum top slice
        var m = 0
        for (k in universe.indices) {
            val i = idx[k]
            if (i < 0) continue
            val x = momentum(universe[k], i)
            if (!x.isNaN()) scratch[m++] = x
        }
        if (m >= Params.MIN_UNIVERSE_FOR_RANK) {
            java.util.Arrays.sort(scratch, 0, m)
            var take = Math.floor(m * Params.MP_TOP_FRACTION).toInt()
            if (take < 1) take = 1
            ctx.momCutoff = scratch[m - take]
        } else ctx.momCutoff = Double.NaN
        // weekly leaders: top N by blended return among stocks above their 200DMA
        m = 0
        for (k in universe.indices) {
            val i = idx[k]
            if (i < 0) continue
            val s = universe[k]
            val x = leaderScore(s, i)
            if (x.isNaN()) continue
            val sma200 = Ind.sma(s.c, i, 200)
            if (sma200.isNaN() || !(s.c[i] > sma200)) continue
            scratch[m++] = x
        }
        if (m >= 1 && m + 0 >= minOf(Params.WL_TOP_N, Params.MIN_UNIVERSE_FOR_RANK)) {
            java.util.Arrays.sort(scratch, 0, m)
            val take = minOf(Params.WL_TOP_N, m)
            ctx.leaderCutoff = scratch[m - take]
        } else ctx.leaderCutoff = Double.NaN
    }
}

// ============================================================================ MOM_PULLBACK
object MomPullback : Strategy {
    override val id = "MOM_PULLBACK"
    override val label = "Momentum pullback"
    override val desc = "Strong 6-month winners in an uptrend that dipped 3–8% and turned up again today."
    override fun enabled() = Params.MOM_PULLBACK_ENABLED
    override fun params(): Map<String, Double> = linkedMapOf(
        "momLookback" to Params.MP_MOM_LOOKBACK.toDouble(), "momSkip" to Params.MP_MOM_SKIP.toDouble(),
        "topFraction" to Params.MP_TOP_FRACTION, "pullbackMinPct" to Params.MP_PULLBACK_MIN_PCT,
        "pullbackMaxPct" to Params.MP_PULLBACK_MAX_PCT, "near20DmaPct" to Params.MP_NEAR_20DMA_PCT,
        "stopAtr" to Params.MP_STOP_ATR, "stopCapPct" to Params.MP_STOP_CAP_PCT, "t1R" to Params.MP_T1_R, "t2R" to Params.MP_T2_R
    )

    override fun evaluate(s: Series, i: Int, f: Feat, ctx: Ctx): Setup? {
        if (ctx.momCutoff.isNaN()) return null
        val mom = Strategies.momentum(s, i)
        if (mom.isNaN() || mom < ctx.momCutoff) return null
        if (!(f.c > f.sma50 && f.sma50 > f.sma200)) return null
        val hi20 = Ind.highest(s.h, i, 20)
        val pb = (hi20 - f.c) / hi20 * 100.0
        val near20 = Math.abs(f.c - f.sma20) / f.sma20 * 100.0
        val pulled = pb >= Params.MP_PULLBACK_MIN_PCT && pb <= Params.MP_PULLBACK_MAX_PCT
        if (!pulled && near20 > Params.MP_NEAR_20DMA_PCT) return null
        val pc = s.c[i - 1]
        val bodyHigh = maxOf(s.o[i - 1], pc)
        if (!(f.c > pc && f.c > bodyHigh)) return null
        val entry = f.c
        val sl = Strategies.atrStop(entry, f.atr, Params.MP_STOP_ATR, Params.MP_STOP_CAP_PCT)
        val r = entry - sl
        val reasons = ArrayList<String>(4)
        reasons.add("Up " + Fmt.p1(mom * 100) + "% over 6 months — in the top " + Math.round(Params.MP_TOP_FRACTION * 100) + "% of the list")
        reasons.add("Uptrend intact: price above 50-day (" + Fmt.rs(f.sma50) + ") and 200-day (" + Fmt.rs(f.sma200) + ") averages")
        reasons.add(if (pulled) "Dipped " + Fmt.p1(pb) + "% from its 20-day high of " + Fmt.rs(hi20)
        else "Resting within " + Fmt.p1(near20) + "% of its 20-day average")
        reasons.add("Turned up today: above yesterday's close and body high (" + Fmt.rs(bodyHigh) + ")")
        return Setup(id, s.sym, s.d[i], entry, sl, entry + Params.MP_T1_R * r, entry + Params.MP_T2_R * r, mom * 100.0, reasons)
    }
}

// ============================================================================ BREAKOUT_VOL
object BreakoutVol : Strategy {
    override val id = "BREAKOUT_VOL"
    override val label = "Volume breakout"
    override val desc = "New 55-day closing high on at least double the usual volume, closing near the day's high."
    override fun enabled() = Params.BREAKOUT_VOL_ENABLED
    override fun params(): Map<String, Double> = linkedMapOf(
        "lookback" to Params.BO_LOOKBACK.toDouble(), "volMult" to Params.BO_VOL_MULT, "minIbs" to Params.BO_MIN_IBS,
        "maxExtPct" to Params.BO_MAX_EXT_PCT, "stopAtr" to Params.BO_STOP_ATR, "t1R" to Params.BO_T1_R, "t2R" to Params.BO_T2_R
    )

    override fun evaluate(s: Series, i: Int, f: Feat, ctx: Ctx): Setup? {
        val level = Ind.highest(s.c, i - 1, Params.BO_LOOKBACK)
        if (level.isNaN() || !(f.c > level)) return null
        if ((f.c / level - 1) * 100.0 > Params.BO_MAX_EXT_PCT) return null
        val rv = Ind.relVol(f.v, f.avgVol20, ctx.sessionFraction)
        if (rv < Params.BO_VOL_MULT) return null
        val ibs = Ind.ibs(f.h, f.l, f.c)
        if (ibs < Params.BO_MIN_IBS) return null
        val entry = f.c
        var sl = maxOf(f.l, entry - Params.BO_STOP_ATR * f.atr)
        sl = entry - Strategies.floorDist(entry, entry - sl)
        val r = entry - sl
        val reasons = ArrayList<String>(4)
        reasons.add("Highest close in " + Params.BO_LOOKBACK + " days — cleared " + Fmt.rs(level))
        reasons.add("Volume about " + Fmt.p1(rv) + "× the 20-day average")
        reasons.add("Closing in the top " + Math.round((1 - ibs) * 100).coerceAtLeast(1) + "% of today's range")
        reasons.add("Only " + Fmt.p1((f.c / level - 1) * 100.0) + "% above the breakout level")
        return Setup(id, s.sym, s.d[i], entry, sl, entry + Params.BO_T1_R * r, entry + Params.BO_T2_R * r, rv * 10.0, reasons)
    }
}

// ============================================================================ DIP_UPTREND
object DipUptrend : Strategy {
    override val id = "DIP_UPTREND"
    override val label = "Dip in an uptrend"
    override val desc = "Sharp 2–3 day dip closing near the day's low in a stock that is in a long-term uptrend; aims for a quick bounce."
    override fun enabled() = Params.DIP_UPTREND_ENABLED
    override fun params(): Map<String, Double> = linkedMapOf(
        "rsiPeriod" to Params.DIP_RSI_PERIOD.toDouble(), "rsiMax" to Params.DIP_RSI_MAX, "ibsMax" to Params.DIP_IBS_MAX,
        "stopAtr" to Params.DIP_STOP_ATR, "stopCapPct" to Params.DIP_STOP_CAP_PCT, "t1Atr" to Params.DIP_T1_ATR,
        "t2Atr" to Params.DIP_T2_ATR, "t2Sma" to Params.DIP_T2_SMA.toDouble()
    )

    override fun evaluate(s: Series, i: Int, f: Feat, ctx: Ctx): Setup? {
        if (!(f.c > f.sma200 && f.sma50 > f.sma200)) return null
        val ibs = Ind.ibs(f.h, f.l, f.c)
        if (!(ibs < Params.DIP_IBS_MAX)) return null
        val rsi = Ind.rsi(s.c, i, Params.DIP_RSI_PERIOD)
        if (rsi.isNaN() || !(rsi < Params.DIP_RSI_MAX)) return null
        val entry = f.c
        val sl = Strategies.atrStop(entry, f.atr, Params.DIP_STOP_ATR, Params.DIP_STOP_CAP_PCT)
        val t1 = entry + Params.DIP_T1_ATR * f.atr
        val sma5 = Ind.sma(s.c, i, Params.DIP_T2_SMA)
        val t2 = maxOf(if (sma5.isNaN()) 0.0 else sma5, entry + Params.DIP_T2_ATR * f.atr)
        val reasons = ArrayList<String>(4)
        reasons.add("Long-term uptrend: above the 200-day average (" + Fmt.rs(f.sma200) + "), 50-day above 200-day")
        reasons.add("Sharply oversold short-term: 2-day RSI at " + Fmt.p1(rsi))
        reasons.add("Closing near the day's low — such dips in uptrends often bounce within days")
        reasons.add("Quick trade: first target only " + Fmt.p1((t1 / entry - 1) * 100) + "% away")
        return Setup(id, s.sym, s.d[i], entry, sl, t1, t2, Params.DIP_RSI_MAX - rsi + (Params.DIP_IBS_MAX - ibs), reasons)
    }
}

// ============================================================================ WEEKLY_LEADERS
object WeeklyLeaders : Strategy {
    override val id = "WEEKLY_LEADERS"
    override val label = "Weekly leaders"
    override val desc = "The 5 strongest stocks of the last 3–6 months, bought on the first trading day of the week and held up to 6 days."
    override fun enabled() = Params.WEEKLY_LEADERS_ENABLED
    override fun params(): Map<String, Double> = linkedMapOf(
        "topN" to Params.WL_TOP_N.toDouble(), "retShort" to Params.WL_RET_SHORT.toDouble(), "retLong" to Params.WL_RET_LONG.toDouble(),
        "weightShort" to Params.WL_WEIGHT_SHORT, "stopAtr" to Params.WL_STOP_ATR, "stopCapPct" to Params.WL_STOP_CAP_PCT
    )

    override fun evaluate(s: Series, i: Int, f: Feat, ctx: Ctx): Setup? {
        if (ctx.leaderCutoff.isNaN()) return null
        // first trading day of the week: the previous bar belongs to an earlier week
        if (i < 1 || Clock.weekIndex(s.d[i]) == Clock.weekIndex(s.d[i - 1])) return null
        if (!(f.c > f.sma200)) return null
        val score = Strategies.leaderScore(s, i)
        if (score.isNaN() || score < ctx.leaderCutoff) return null
        val entry = f.c
        val sl = Strategies.atrStop(entry, f.atr, Params.WL_STOP_ATR, Params.WL_STOP_CAP_PCT)
        val reasons = ArrayList<String>(4)
        reasons.add("Among the " + Params.WL_TOP_N + " strongest stocks on the list over 3–6 months (blended return " + Fmt.pct(score * 100) + ")")
        reasons.add("Above its 200-day average (" + Fmt.rs(f.sma200) + ")")
        reasons.add("First trading day of the week — weekly rotation entry")
        reasons.add("No price target: sell at the close of day " + Params.HOLD_DAYS + " or at the stop")
        return Setup(id, s.sym, s.d[i], entry, sl, 0.0, 0.0, score * 100.0, reasons)
    }
}
