package com.boomai.swingedge.core

/**
 * Black-76 on live option prices: price, delta, implied volatility (bisection, robust), and the ATM straddle / implied
 * move of a chain. The forward is spot * e^(rT); discounting at r. Pure Kotlin, never throws; NaN when undefined.
 */
object Black76 {
    const val RATE = 0.065

    private fun ncdf(x: Double): Double {
        // Abramowitz-Stegun 26.2.17 (error < 7.5e-8)
        val t = 1.0 / (1.0 + 0.2316419 * Math.abs(x))
        val d = 0.3989422804014327 * Math.exp(-x * x / 2)
        val p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        return if (x >= 0) 1 - p else p
    }

    fun price(call: Boolean, f: Double, k: Double, t: Double, vol: Double, r: Double = RATE): Double {
        if (!(f > 0) || !(k > 0) || !(t > 0) || !(vol > 0)) return Double.NaN
        val sd = vol * Math.sqrt(t)
        val d1 = (Math.log(f / k) + sd * sd / 2) / sd; val d2 = d1 - sd
        val df = Math.exp(-r * t)
        return if (call) df * (f * ncdf(d1) - k * ncdf(d2)) else df * (k * ncdf(-d2) - f * ncdf(-d1))
    }

    fun delta(call: Boolean, f: Double, k: Double, t: Double, vol: Double, r: Double = RATE): Double {
        if (!(f > 0) || !(k > 0) || !(t > 0) || !(vol > 0)) return Double.NaN
        val sd = vol * Math.sqrt(t)
        val d1 = (Math.log(f / k) + sd * sd / 2) / sd
        val df = Math.exp(-r * t)
        return if (call) df * ncdf(d1) else -df * ncdf(-d1)
    }

    /** Implied volatility (annual, e.g. 0.14) from a premium; NaN when the premium is below intrinsic or not solvable. */
    fun iv(call: Boolean, prem: Double, f: Double, k: Double, t: Double, r: Double = RATE): Double {
        if (!(prem > 0) || !(f > 0) || !(k > 0) || !(t > 0)) return Double.NaN
        val df = Math.exp(-r * t)
        val intrinsic = df * Math.max(0.0, if (call) f - k else k - f)
        if (prem < intrinsic - 1e-9 || prem >= df * (if (call) f else k)) return Double.NaN
        var lo = 1e-4; var hi = 5.0
        if (price(call, f, k, t, hi, r) < prem) return Double.NaN
        repeat(80) {
            val mid = (lo + hi) / 2
            if (price(call, f, k, t, mid, r) > prem) hi = mid else lo = mid
            if (hi - lo < 1e-6) return (lo + hi) / 2
        }
        return (lo + hi) / 2
    }

    /** Years from [now] to 15:30 IST on [expDay]; a small floor keeps expiry-day maths finite. */
    fun years(expDay: Int, now: Long): Double = Math.max(Clock.msAt(expDay, Clock.CLOSE_MIN) - now, 15 * 60_000L) / (365.0 * Clock.DAY_MS)

    fun forward(spot: Double, t: Double, r: Double = RATE) = spot * Math.exp(r * t)
}
