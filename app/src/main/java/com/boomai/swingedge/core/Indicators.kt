package com.boomai.swingedge.core

/**
 * Indicator maths on plain arrays. Every function looks ONLY at indexes <= i (no look-ahead) and
 * returns NaN when there is not enough history. No allocation.
 */
object Ind {
    val NaN = Double.NaN

    /** Simple mean of a[i-n+1..i]. */
    fun sma(a: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i - n + 1 < 0) return NaN
        var s = 0.0
        for (k in i - n + 1..i) s += a[k]
        return s / n
    }

    /** EMA with alpha 2/(n+1), seeded with the SMA of the first n values of the array. */
    fun ema(a: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i < n - 1) return NaN
        var e = sma(a, n - 1, n)
        val k = 2.0 / (n + 1)
        for (j in n..i) e = a[j] * k + e * (1 - k)
        return e
    }

    fun highest(a: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i - n + 1 < 0) return NaN
        var m = a[i - n + 1]
        for (k in i - n + 2..i) if (a[k] > m) m = a[k]
        return m
    }

    fun lowest(a: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i - n + 1 < 0) return NaN
        var m = a[i - n + 1]
        for (k in i - n + 2..i) if (a[k] < m) m = a[k]
        return m
    }

    /** a[i]/a[i-n] - 1 as a fraction. */
    fun ret(a: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i - n < 0) return NaN
        val b = a[i - n]
        return if (b > 0) a[i] / b - 1 else NaN
    }

    fun trueRange(h: DoubleArray, l: DoubleArray, c: DoubleArray, k: Int): Double {
        if (k == 0) return h[0] - l[0]
        val pc = c[k - 1]
        return maxOf(h[k] - l[k], Math.abs(h[k] - pc), Math.abs(l[k] - pc))
    }

    /**
     * Wilder ATR(n) at i: seeded with the plain average of the first n true ranges starting at bar
     * max(1, i - window + 1), then smoothed atr = (atr*(n-1) + tr)/n. With a short series this is the textbook ATR.
     */
    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, i: Int, n: Int, window: Int = Params.WILDER_WINDOW): Double {
        val start = maxOf(1, i - window + 1)
        if (n <= 0 || i - start + 1 < n) return NaN
        var a = 0.0
        for (k in start until start + n) a += trueRange(h, l, c, k)
        a /= n
        for (k in start + n..i) a = (a * (n - 1) + trueRange(h, l, c, k)) / n
        return a
    }

    /**
     * Wilder RSI(n) at i: average gain/loss seeded with the plain mean of the first n changes starting at bar
     * max(1, i - window + 1), then Wilder-smoothed. 100 when there are no losses, 50 when there is no movement.
     */
    fun rsi(c: DoubleArray, i: Int, n: Int, window: Int = Params.WILDER_WINDOW): Double {
        val start = maxOf(1, i - window + 1)
        if (n <= 0 || i - start + 1 < n) return NaN
        var g = 0.0
        var ls = 0.0
        for (k in start until start + n) {
            val d = c[k] - c[k - 1]
            if (d > 0) g += d else ls -= d
        }
        g /= n
        ls /= n
        for (k in start + n..i) {
            val d = c[k] - c[k - 1]
            val up = if (d > 0) d else 0.0
            val dn = if (d < 0) -d else 0.0
            g = (g * (n - 1) + up) / n
            ls = (ls * (n - 1) + dn) / n
        }
        if (ls <= 0.0) return if (g <= 0.0) 50.0 else 100.0
        val rs = g / ls
        return 100.0 - 100.0 / (1.0 + rs)
    }

    /** Mean of close*volume over the n bars ending at i (rupees per day). */
    fun avgTurnover(c: DoubleArray, v: DoubleArray, i: Int, n: Int): Double {
        if (n <= 0 || i - n + 1 < 0) return NaN
        var s = 0.0
        for (k in i - n + 1..i) s += c[k] * v[k]
        return s / n
    }

    /** Internal bar strength (close - low)/(high - low); 0.5 for a zero-range bar. */
    fun ibs(h: Double, l: Double, c: Double): Double {
        val r = h - l
        return if (r > 0) ((c - l) / r).coerceIn(0.0, 1.0) else 0.5
    }

    /**
     * Relative volume with the day's volume so far projected to a full session:
     * volSoFar / (avgVol * max(fraction of session elapsed, 0.1)). 0 when avgVol is not positive.
     */
    fun relVol(volSoFar: Double, avgVol: Double, sessionFraction: Double): Double {
        if (!(avgVol > 0)) return 0.0
        val f = if (sessionFraction.isNaN()) 1.0 else sessionFraction.coerceIn(0.1, 1.0)
        return volSoFar / (avgVol * f)
    }
}
