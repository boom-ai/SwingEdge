package com.boomai.swingedge.core

/** Text formatting helpers (Indian digit grouping, rupee amounts, percentages). */
object Fmt {
    fun r2(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else Math.round(x * 100.0) / 100.0
    fun r1(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else Math.round(x * 10.0) / 10.0

    /** Indian grouping of a non-negative whole number: 1234567 -> 12,34,567. */
    fun group(n: Long): String {
        val s = Math.abs(n).toString()
        if (s.length <= 3) return s
        val head = s.substring(0, s.length - 3)
        val tail = s.substring(s.length - 3)
        val sb = StringBuilder()
        var cnt = 0
        for (k in head.length - 1 downTo 0) {
            if (cnt == 2) {
                sb.append(',')
                cnt = 0
            }
            sb.append(head[k])
            cnt++
        }
        return sb.reverse().toString() + "," + tail
    }

    /** Number with Indian grouping; 2 decimals unless whole. */
    fun num(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return "0"
        val paise = Math.round(Math.abs(x) * 100.0)
        val whole = paise / 100
        val frac = (paise % 100).toInt()
        val sign = if (x < 0 && paise != 0L) "-" else ""
        return sign + group(whole) + if (frac == 0) "" else "." + (if (frac < 10) "0$frac" else "$frac")
    }

    /** ₹1,234.50 (negative as -₹1,234.50). */
    fun rs(x: Double): String {
        val s = num(x)
        return if (s.startsWith("-")) "-₹" + s.substring(1) else "₹$s"
    }

    /** Whole rupees: ₹1,235. */
    fun rs0(x: Double): String = rs(if (x.isNaN() || x.isInfinite()) 0.0 else Math.rint(x))

    /** Signed whole rupees: +₹350 / -₹120. */
    fun rsSigned(x: Double): String {
        val v = if (x.isNaN() || x.isInfinite()) 0.0 else Math.rint(x)
        return if (v >= 0) "+" + rs(v) else rs(v)
    }

    /** One-decimal number: 1.5 */
    fun p1(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return "0.0"
        val t = Math.round(Math.abs(x) * 10.0)
        val sign = if (x < 0 && t != 0L) "-" else ""
        return sign + (t / 10) + "." + (t % 10)
    }

    /** Signed one-decimal percent: +1.5% / -0.3% */
    fun pct(x: Double): String {
        val s = p1(x)
        return (if (s.startsWith("-")) s else "+$s") + "%"
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n " + if (n == 1) one else many
}
