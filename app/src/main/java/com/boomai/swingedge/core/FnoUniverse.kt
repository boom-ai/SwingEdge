package com.boomai.swingedge.core

/**
 * Whole-market F&O: every NSE F&O underlying (indices + ~200 stocks) from NSE's lot-size file, live last prices for
 * all of them in a few batch calls, 5-minute bars built from those samples (back-filled from Yahoo when the app starts
 * late), and monthly option contract ids. Pure Kotlin, never throws.
 */
object FnoUniverse {
    const val LOTS_URL = "https://nsearchives.nseindia.com/content/fo/fo_mktlots.csv"
    const val LOTS_MIRROR = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/fo_lots.csv"
    val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    val YAHOO_INDEX = mapOf("NIFTY" to "^NSEI", "BANKNIFTY" to "^NSEBANK", "FINNIFTY" to "NIFTY_FIN_SERVICE.NS", "MIDCPNIFTY" to "^NSEMDCP50", "NIFTYNXT50" to "^NSMIDCP")

    /** fo_mktlots.csv -> SYMBOL -> lot for the nearest month. */
    fun parseLots(csv: String?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (csv == null) return out
        for (line in csv.lineSequence().drop(1)) {
            val c = line.split(',').map { it.trim() }
            if (c.size < 3) continue
            val sym = c[1].uppercase(); if (sym.isEmpty() || sym == "SYMBOL" || !sym.matches(Regex("[A-Z0-9&\\-]{1,20}"))) continue
            val lot = c.drop(2).firstNotNullOfOrNull { it.toIntOrNull() } ?: continue
            if (lot > 0) out[sym] = lot
        }
        return out
    }

    fun yahooSym(sym: String) = YAHOO_INDEX[sym] ?: "$sym.NS"
    fun isIndex(sym: String) = Fno.INDICES.contains(sym)

    /** Monthly contract = the last expiry of its calendar month. */
    fun isMonthly(expiry: String, expiries: List<String>): Boolean {
        val d = Fno.expDay(expiry); if (d <= 0) return false
        val ld = java.time.LocalDate.ofEpochDay(d.toLong())
        return expiries.none { val e = Fno.expDay(it); e > d && java.time.LocalDate.ofEpochDay(e.toLong()).let { x -> x.year == ld.year && x.month == ld.month } }
    }
    fun strikeText(k: Double) = if (k == Math.floor(k)) k.toLong().toString() else k.toString().trimEnd('0').trimEnd('.')
    /** Monthly option id, e.g. RELIANCE26NOV1360CE. */
    fun contractId(sym: String, expiry: String, strike: Double, side: String): String? {
        val d = Fno.expDay(expiry); if (d <= 0) return null
        val ld = java.time.LocalDate.ofEpochDay(d.toLong())
        return sym + (ld.year % 100).toString().padStart(2, '0') + MONTHS[ld.monthValue - 1] + strikeText(strike) + side
    }

    /** Batch body for the live quote call: stocks in priceSymbolList, indices in indexSymbolList. */
    fun quoteBody(syms: List<String>) = Json.stringify(mapOf("exchangeAggReqMap" to mapOf("NSE" to mapOf(
        "priceSymbolList" to syms.filter { !isIndex(it) }, "indexSymbolList" to syms.filter { isIndex(it) }))))

    class Q(val ltp: Double, val open: Double, val prev: Double, val high: Double, val low: Double, val pct: Double)
    private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }?.takeIf { !it.isNaN() && !it.isInfinite() }
    fun parseQuotes(text: String?): Map<String, Q> = try {
        val nse = ((Json.parseObject(text ?: "")?.get("exchangeAggRespMap") as? Map<*, *>)?.get("NSE") as? Map<*, *>) ?: emptyMap<Any, Any>()
        val out = HashMap<String, Q>()
        for (mk in listOf("priceLivePointsMap", "indexLivePointsMap")) for ((k, v) in (nse[mk] as? Map<*, *>).orEmpty()) {
            val r = v as? Map<*, *> ?: continue
            val l = num(r["ltp"]) ?: num(r["value"]) ?: continue
            if (l <= 0) continue
            val prev = num(r["close"]) ?: l
            out[k.toString()] = Q(l, num(r["open"]) ?: l, prev, num(r["high"]) ?: l, num(r["low"]) ?: l, num(r["dayChangePerc"]) ?: if (prev > 0) (l - prev) / prev * 100 else 0.0)
        }
        out
    } catch (t: Throwable) { emptyMap() }

    /** Today's 5-minute bars for one symbol, filled from live samples and/or a Yahoo back-fill. */
    class Series(var day: Int = 0, var prev: Double = 0.0, var open: Double = 0.0, val bars: java.util.TreeMap<Int, DoubleArray> = java.util.TreeMap(), var filled: Boolean = false) {
        fun sample(m: Int, px: Double) {
            if (m < 9 * 60 + 15 || m > 15 * 60 + 30 || px <= 0) return
            val b = 9 * 60 + 15 + (m - (9 * 60 + 15)) / 5 * 5
            val x = bars[b]
            if (x == null) bars[b] = doubleArrayOf(px, px, px, px) else { x[1] = Math.max(x[1], px); x[2] = Math.min(x[2], px); x[3] = px }
        }
        fun fill(ib: IntraBars) { for (i in 0 until ib.n) bars[ib.min[i]] = doubleArrayOf(ib.o[i], ib.h[i], ib.l[i], ib.c[i]); if (prev <= 0) prev = ib.prevClose; filled = true }
        fun toBars(): IntraBars? {
            if (bars.isEmpty() || prev <= 0) return null
            val ks = bars.keys.toIntArray()
            val o = DoubleArray(ks.size) { bars[ks[it]]!![0] }
            if (open > 0 && ks[0] == 9 * 60 + 15) o[0] = open
            return IntraBars(prev, ks, o, DoubleArray(ks.size) { bars[ks[it]]!![1] }, DoubleArray(ks.size) { bars[ks[it]]!![2] }, DoubleArray(ks.size) { bars[ks[it]]!![3] })
        }
    }
}
