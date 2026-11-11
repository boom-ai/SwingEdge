package com.boomai.swingedge.core

/**
 * Full-history EMAs: seeded with the FIRST close of the series and run over every bar. The EMA-cross screen (`watch`),
 * the stock page (`stockInfo`) and the chart (`getChart`) all use these, so they show identical values for the same bar.
 */
object Ema {
    val PERIODS = intArrayOf(10, 20, 50, 100, 200)

    /** out[j] = EMA(period) at bar j, alpha 2/(period+1), seeded with c[0]. */
    fun line(c: DoubleArray, n: Int, period: Int): DoubleArray {
        val m = maxOf(0, minOf(n, c.size))
        val out = DoubleArray(m)
        if (m == 0) return out
        val k = 2.0 / (period + 1)
        var e = c[0]
        out[0] = e
        for (j in 1 until m) {
            e += k * (c[j] - e)
            out[j] = e
        }
        return out
    }

    /** First bar index at which an EMA is shown (before that it is still dominated by the seed and reported as 0). */
    fun firstValid(period: Int): Int = if (period <= 20) 0 else minOf(2 * period, period + 50)

    /** Daily bars needed before the EMA is shown. */
    fun barsNeeded(period: Int): Int = firstValid(period) + 1

    /**
     * Bars since the last sign change of (a - b): 0 = the latest bar is the first bar on the current side.
     * "Above" means a > b; anything else counts as below. Only bars >= [first] are compared.
     * -1 when no change is found within [cap] bars (or there is not enough history).
     */
    fun daysSince(a: DoubleArray, b: DoubleArray, n: Int, first: Int, cap: Int): Int {
        if (n < 2 || n - 1 < first || a.size < n || b.size < n) return -1
        val above = a[n - 1] - b[n - 1] > 0
        for (q in 0..cap) {
            val j = n - 1 - q
            if (j - 1 < maxOf(first, 0)) return -1
            if ((a[j - 1] - b[j - 1] > 0) != above) return q
        }
        return -1
    }
}

/** The five EMA lines of one series. */
class EmaLines(s: Series) {
    val n = s.n
    val e10 = Ema.line(s.c, s.n, 10)
    val e20 = Ema.line(s.c, s.n, 20)
    val e50 = Ema.line(s.c, s.n, 50)
    val e100 = Ema.line(s.c, s.n, 100)
    val e200 = Ema.line(s.c, s.n, 200)

    fun line(period: Int): DoubleArray = when (period) { 10 -> e10; 20 -> e20; 50 -> e50; 100 -> e100; else -> e200 }

    /** Value at bar i, or 0 when the EMA is not shown yet (not enough history). */
    fun shown(period: Int, i: Int): Double = if (i < 0 || i >= n || i < Ema.firstValid(period)) 0.0 else line(period)[i]
}

/**
 * The Setups-tab screen: liquid stocks whose 10-day EMA has just crossed above the 20-day EMA (day 0 = the latest bar,
 * up to WATCH_MAX_DAYS_SINCE days ago) while the 10-day EMA is above the 50-, 100- and 200-day EMAs.
 * The single definition used by computeWatch() and by the stock page's EMA_CROSS setup.
 */
object EmaScreen {
    const val MIN_BARS = 260
    const val PASS = 0
    const val SHORT = 1
    const val CHEAP = 2
    const val THIN = 3

    /** Cheap filters before any EMA is computed: history, price, 20-day average turnover. */
    fun gate(s: Series): Int {
        val n = s.n
        if (n < MIN_BARS) return SHORT
        val c = s.c[n - 1]
        if (!(c >= Params.MIN_PRICE)) return CHEAP
        if (turnover20(s) < Params.MIN_AVG_TURNOVER_RS) return THIN
        return PASS
    }

    /** Mean close x volume of the 20 bars before the latest one (rupees per day); 0 when too short. */
    fun turnover20(s: Series): Double {
        val n = s.n
        if (n < 21) return 0.0
        var tv = 0.0
        for (j in n - 21 until n - 1) tv += s.c[j] * s.v[j]
        return tv / 20.0
    }

    /** 10 EMA above the 20, 50, 100 and 200 EMAs on the latest bar. */
    fun stacked(L: EmaLines): Boolean {
        val i = L.n - 1
        if (i < 0) return false
        val e10 = L.e10[i]
        return e10 > L.e20[i] && e10 > L.e50[i] && e10 > L.e100[i] && e10 > L.e200[i]
    }

    /** Bars since the 10 EMA crossed above the 20 EMA when that is within WATCH_MAX_DAYS_SINCE, else -1. */
    fun since(L: EmaLines): Int {
        val n = L.n
        for (q in 0..Params.WATCH_MAX_DAYS_SINCE) {
            val j = n - 1 - q
            if (j < 1) break
            if (!(L.e10[j] - L.e20[j] > 0)) break
            if (L.e10[j - 1] - L.e20[j - 1] <= 0) return q
        }
        return -1
    }

    /** Whole rule: gate + stack + fresh cross. Returns the bars since the cross, or -1 if the stock is not listed. */
    fun match(s: Series, L: EmaLines): Int {
        if (gate(s) != PASS) return -1
        if (!stacked(L)) return -1
        return since(L)
    }
}
