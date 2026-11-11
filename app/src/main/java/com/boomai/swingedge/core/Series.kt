package com.boomai.swingedge.core

/** Daily OHLCV bars of one symbol, oldest first. `d` is the IST epoch day of each bar. Only the first `n` slots are valid. */
class Series(val sym: String, capacity: Int) {
    var n = 0
    var d = IntArray(capacity)
    var o = DoubleArray(capacity)
    var h = DoubleArray(capacity)
    var l = DoubleArray(capacity)
    var c = DoubleArray(capacity)
    var v = DoubleArray(capacity)

    private fun grow() {
        val cap = maxOf(16, d.size * 2)
        d = d.copyOf(cap); o = o.copyOf(cap); h = h.copyOf(cap); l = l.copyOf(cap); c = c.copyOf(cap); v = v.copyOf(cap)
    }

    /** Appends a sanitised bar; ignores it unless close > 0 and the day is after the last bar. Returns true if added. */
    fun add(day: Int, open: Double, high: Double, low: Double, close: Double, vol: Double): Boolean {
        if (!(close > 0) || close.isInfinite()) return false
        if (n > 0 && day <= d[n - 1]) return false
        if (n == d.size) grow()
        var oo = if (open > 0 && !open.isInfinite()) open else close
        var hh = if (high > 0 && !high.isInfinite()) high else maxOf(oo, close)
        var ll = if (low > 0 && !low.isInfinite()) low else minOf(oo, close)
        if (hh < maxOf(oo, close)) hh = maxOf(oo, close)
        if (ll > minOf(oo, close)) ll = minOf(oo, close)
        if (oo.isNaN()) oo = close
        d[n] = day; o[n] = oo; h[n] = hh; l[n] = ll; c[n] = close
        v[n] = if (vol > 0 && !vol.isInfinite()) vol else 0.0
        n++
        return true
    }

    fun lastDay(): Int = if (n > 0) d[n - 1] else Int.MIN_VALUE

    /** Index of the bar for [day], or -1. */
    fun indexOf(day: Int): Int {
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val x = d[mid]
            if (x == day) return mid
            if (x < day) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** Copy of the first [count] bars (a "history up to and including bar count-1" view). */
    fun head(count: Int): Series {
        val k = count.coerceIn(0, n)
        val s = Series(sym, maxOf(k, 1))
        System.arraycopy(d, 0, s.d, 0, k); System.arraycopy(o, 0, s.o, 0, k); System.arraycopy(h, 0, s.h, 0, k)
        System.arraycopy(l, 0, s.l, 0, k); System.arraycopy(c, 0, s.c, 0, k); System.arraycopy(v, 0, s.v, 0, k)
        s.n = k
        return s
    }

    /** New series with the last [cap] bars at most. */
    fun tail(cap: Int): Series {
        if (n <= cap) return this
        val s = Series(sym, cap)
        val from = n - cap
        System.arraycopy(d, from, s.d, 0, cap); System.arraycopy(o, from, s.o, 0, cap); System.arraycopy(h, from, s.h, 0, cap)
        System.arraycopy(l, from, s.l, 0, cap); System.arraycopy(c, from, s.c, 0, cap); System.arraycopy(v, from, s.v, 0, cap)
        s.n = cap
        return s
    }

    // ------------------------------------------------------------ compact text form for the file cache
    // bar = "dayDelta,open,high,low,close,volume" with prices in paise, all base-36, bars joined by ';'

    fun encode(): String {
        val sb = StringBuilder(n * 28)
        var prev = 0
        for (i in 0 until n) {
            if (i > 0) sb.append(';')
            sb.append((d[i] - prev).toString(36)); prev = d[i]
            sb.append(',').append(Math.round(o[i] * 100).toString(36))
            sb.append(',').append(Math.round(h[i] * 100).toString(36))
            sb.append(',').append(Math.round(l[i] * 100).toString(36))
            sb.append(',').append(Math.round(c[i] * 100).toString(36))
            sb.append(',').append(Math.round(v[i]).toString(36))
        }
        return sb.toString()
    }

    companion object {
        /** Decodes [encode] output; malformed bars are skipped, never throws. */
        fun decode(sym: String, text: String): Series {
            val s = Series(sym, 64)
            try {
                var prev = 0
                var pos = 0
                val len = text.length
                val f = LongArray(6)
                while (pos < len) {
                    var end = text.indexOf(';', pos)
                    if (end < 0) end = len
                    var k = 0
                    var p = pos
                    var ok = true
                    while (k < 6 && p <= end) {
                        var q = text.indexOf(',', p)
                        if (q < 0 || q > end) q = end
                        val x = text.substring(p, q).toLongOrNull(36)
                        if (x == null) {
                            ok = false
                            break
                        }
                        f[k++] = x
                        p = q + 1
                    }
                    if (ok && k == 6) {
                        val day = prev + f[0].toInt()
                        prev = day
                        s.add(day, f[1] / 100.0, f[2] / 100.0, f[3] / 100.0, f[4] / 100.0, f[5].toDouble())
                    } else if (k >= 1 && ok) {
                        prev += f[0].toInt()
                    }
                    pos = end + 1
                }
            } catch (t: Throwable) {
                // keep whatever decoded cleanly
            }
            return s
        }
    }
}
