package com.boomai.swingedge.core

class Advice(
    val verdict: String, val headline: String, val stop: Double, val bookAt: Double,
    val reasons: List<String>, val dataOk: Boolean, val belowStop: Boolean
)

/** Health check of one holding. Thresholds are in Params.PF_*. */
object Advisor {
    const val HOLD = "HOLD"
    const val TRIM = "TRIM"
    const val SELL = "SELL"
    const val WATCH = "WATCH"

    /** Trailing level known at the END of bar i: max(highest close of 22 bars - 3 ATR, lowest low of the last 10 bars). */
    fun trailingStop(s: Series, i: Int): Double {
        val atr = Ind.atr(s.h, s.l, s.c, i, Params.ATR_PERIOD)
        val hc = Ind.highest(s.c, i, Params.PF_TRAIL_LOOKBACK)
        val sw = Ind.lowest(s.l, i, Params.PF_SWING_LOOKBACK)
        if (atr.isNaN() || hc.isNaN()) return Double.NaN
        val trail = hc - Params.PF_TRAIL_ATR * atr
        return if (sw.isNaN()) trail else maxOf(trail, sw)
    }

    /**
     * @param s daily bars with today's (possibly partial) bar last
     * @param index Nifty bars aligned the same way, or null
     * @param sessionFraction fraction of today's session elapsed (1 for a finished day)
     * @param weightPct this holding's share of the portfolio value in %, or 0 if unknown
     */
    fun check(s: Series?, index: Series?, sessionFraction: Double, weightPct: Double): Advice {
        if (s == null || s.n < Params.PF_MIN_BARS) {
            val have = s?.n ?: 0
            return Advice(
                WATCH, if (have == 0) "No price data yet — cannot judge this one" else "Too little price history to judge ($have days)",
                0.0, 0.0,
                listOf("Needs at least " + Params.PF_MIN_BARS + " trading days of prices", "Check the symbol or try a scan when online"),
                false, false
            )
        }
        val i = s.n - 1
        val c = s.c[i]
        val atr = Ind.atr(s.h, s.l, s.c, i - 1, Params.ATR_PERIOD)
        val sma50 = Ind.sma(s.c, i, 50)
        val sma200 = Ind.sma(s.c, i, 200)
        val sma50Prev = Ind.sma(s.c, i - Params.PF_SMA50_SLOPE_BARS, 50)
        val rsi = Ind.rsi(s.c, i, 14)
        var stop = trailingStop(s, i - 1)
        if (stop.isNaN() || atr.isNaN() || sma50.isNaN()) {
            return Advice(WATCH, "Price data is incomplete — cannot judge this one", 0.0, 0.0,
                listOf("Some recent prices are missing", "Will re-check on the next scan"), false, false)
        }
        if (stop < 0) stop = 0.0
        val belowStop = c < stop
        val avgVol = Ind.sma(s.v, i - 1, 20)
        val rv = Ind.relVol(s.v[i], if (avgVol.isNaN()) 0.0 else avgVol, sessionFraction)
        val dayChg = (c / s.c[i - 1] - 1) * 100.0

        // objective level to book profit
        val look = minOf(Params.PF_BOOK_LOOKBACK, i)
        val priorHigh = Ind.highest(s.h, i - 1, look)
        val bookAt = if (!priorHigh.isNaN() && priorHigh > c * 1.01 && priorHigh <= c + 6 * atr) priorHigh else c + Params.PF_BOOK_ATR * atr
        val bookIsHigh = bookAt == priorHigh

        // relative strength vs Nifty over ~3 months
        var rs = Double.NaN
        val r = Ind.ret(s.c, i, Params.PF_RS_LOOKBACK)
        if (index != null && index.n > Params.PF_RS_LOOKBACK && !r.isNaN()) {
            val ri = Ind.ret(index.c, index.n - 1, Params.PF_RS_LOOKBACK)
            if (!ri.isNaN()) rs = (r - ri) * 100.0
        }
        val has200 = !sma200.isNaN()
        val sma50Falling = !sma50Prev.isNaN() && sma50 < sma50Prev
        val ext = (c / sma50 - 1) * 100.0

        var verdict = HOLD
        var headline: String
        val reasons = ArrayList<String>(4)

        val sellTrend = has200 && c < sma200 && (sma50 < sma200 || sma50Falling)
        val sellStop = c < stop * (1 - Params.PF_SELL_BELOW_STOP_PCT / 100.0) && rv >= Params.PF_SELL_RELVOL
        val sellWeak = !rs.isNaN() && rs <= Params.PF_RS_WEAK_PTS && c < sma50
        val trimExt = ext > Params.PF_TRIM_ABOVE_50DMA_PCT
        val trimRsi = !rsi.isNaN() && rsi > Params.PF_TRIM_RSI
        val trimBlow = (s.h[i] - s.l[i]) >= Params.PF_BLOWOFF_RANGE_ATR * atr && rv >= Params.PF_BLOWOFF_RELVOL && dayChg >= Params.PF_BLOWOFF_UP_PCT
        val trimWeight = weightPct > Params.PF_MAX_WEIGHT_PCT

        when {
            sellTrend -> {
                verdict = SELL
                headline = if (sma50 < sma200) "Trend broken — below the 200-day average with the 50-day under it"
                else "Trend broken — below the 200-day average and the 50-day is falling"
                reasons.add("Price " + Fmt.rs(c) + " is under the 200-day average " + Fmt.rs(sma200))
                reasons.add(if (sma50 < sma200) "50-day average (" + Fmt.rs(sma50) + ") is below the 200-day — a downtrend"
                else "50-day average has turned down over the last " + Params.PF_SMA50_SLOPE_BARS + " sessions")
            }
            sellStop -> {
                verdict = SELL
                headline = "Broke its trailing stop " + Fmt.rs(stop) + " on heavy volume"
                reasons.add("Price " + Fmt.rs(c) + " is more than " + Fmt.p1(Params.PF_SELL_BELOW_STOP_PCT) + "% under the stop " + Fmt.rs(stop))
                reasons.add("Volume about " + Fmt.p1(rv) + "× normal — sellers are in control")
            }
            sellWeak -> {
                verdict = SELL
                headline = "Lagging Nifty by " + Math.round(-rs) + " points in 3 months and below its 50-day average"
                reasons.add("3-month return trails Nifty by " + Math.round(-rs) + " percentage points")
                reasons.add("Price " + Fmt.rs(c) + " is under the 50-day average " + Fmt.rs(sma50))
            }
            trimBlow -> {
                verdict = TRIM
                headline = "Blow-off day: " + Fmt.pct(dayChg) + " on " + Fmt.p1(rv) + "× volume — book part"
                reasons.add("Today's range is over " + Fmt.p1(Params.PF_BLOWOFF_RANGE_ATR) + "× its usual daily move")
                reasons.add("Such spikes often cool off — selling a part locks in the gain")
            }
            trimExt -> {
                verdict = TRIM
                headline = "Stretched " + Math.round(ext) + "% above its 50-day average — book part"
                reasons.add("Price " + Fmt.rs(c) + " vs 50-day average " + Fmt.rs(sma50))
                reasons.add("Stocks this far above the average usually pull back or pause")
            }
            trimRsi -> {
                verdict = TRIM
                headline = "Overbought (14-day RSI " + Math.round(rsi) + ") — book part"
                reasons.add("RSI above " + Math.round(Params.PF_TRIM_RSI) + " means it has run up very fast")
                reasons.add("Price is " + Fmt.pct(ext) + " vs its 50-day average")
            }
            trimWeight -> {
                verdict = TRIM
                headline = Math.round(weightPct).toString() + "% of your portfolio is in this one stock — trim to cut risk"
                reasons.add("A single holding above " + Math.round(Params.PF_MAX_WEIGHT_PCT) + "% makes the whole portfolio depend on it")
                reasons.add(if (c > sma50) "Trend is still up (above the 50-day average " + Fmt.rs(sma50) + ")"
                else "Price is under the 50-day average " + Fmt.rs(sma50))
            }
            belowStop -> {
                verdict = WATCH
                headline = "Below its trailing stop " + Fmt.rs(stop) + " — sell if it does not recover"
                reasons.add("Price " + Fmt.rs(c) + " slipped under the stop, but without heavy volume so far")
                reasons.add(if (c > sma50) "Still above the 50-day average " + Fmt.rs(sma50) else "Also under the 50-day average " + Fmt.rs(sma50))
            }
            c < sma50 || (has200 && c < sma200) -> {
                verdict = WATCH
                headline = if (c < sma50) "Below its 50-day average — the uptrend is on hold" else "Below its 200-day average — long-term trend is weak"
                reasons.add("Price " + Fmt.rs(c) + " vs 50-day average " + Fmt.rs(sma50))
                if (has200) reasons.add(if (c > sma200) "Still above the 200-day average " + Fmt.rs(sma200) else "Under the 200-day average " + Fmt.rs(sma200))
            }
            else -> {
                headline = "Uptrend intact — hold with stop " + Fmt.rs(stop)
                reasons.add("Price " + Fmt.rs(c) + " is above the 50-day average " + Fmt.rs(sma50))
                if (has200) reasons.add(if (sma50 > sma200) "50-day average is above the 200-day — a healthy trend" else "50-day average is still under the 200-day — early recovery")
            }
        }
        if (!rs.isNaN() && reasons.size < 3 && !(verdict == SELL && sellWeak && !sellTrend && !sellStop)) {
            reasons.add(if (rs >= 0) "Beat Nifty by " + Math.round(rs) + " points over 3 months" else "Trails Nifty by " + Math.round(-rs) + " points over 3 months")
        }
        if (reasons.size < 4) {
            reasons.add(
                if (verdict == SELL || belowStop) "Trailing stop was " + Fmt.rs(stop) + "; next level to watch on a bounce: " + Fmt.rs(bookAt)
                else "Stop " + Fmt.rs(stop) + " · consider booking some near " + Fmt.rs(bookAt) + (if (bookIsHigh) " (previous high)" else " (about 2 average days' move)")
            )
        }
        return Advice(verdict, headline, stop, bookAt, reasons, true, belowStop)
    }
}

class ImportRow(val sym: String, var qty: Double, var avg: Double)
class ImportResult(val rows: List<ImportRow>, val skipped: List<String>)

/** Heuristic parser for broker holdings exports and pasted text. Never throws. */
object HoldingsImport {
    private val SYM_STRONG = setOf("symbol", "tradingsymbol", "trading symbol", "instrument", "ticker", "scrip", "nse symbol", "stock symbol", "scrip symbol", "scrip code", "nse code", "security symbol")
    private val SYM_WEAK = setOf("stock", "stock name", "company", "company name", "name", "security", "security name", "scrip name", "instrument name", "script", "script name", "share", "share name")
    private val QTY_EXCLUDE = listOf("value", "amount", "amt", "price", "pledge", "discrepant", "long term", "unsettled", "blocked", "lock", "p l", "pnl", "percent", "rate", "cost")
    private val AVG_EXCLUDE = listOf("value", "amount", "amt", "ltp", "current", "market", "close", "closing", "p l", "pnl", "change", "chg", "day", "invested", "last", "qty", "quantity", "52")
    private val JUNK = setOf("TOTAL", "GRANDTOTAL", "GRAND TOTAL", "SUBTOTAL", "SUB TOTAL", "SUMMARY", "NET", "EQUITY", "HOLDINGS", "NA", "N/A", "-", "SYMBOL")
    private val SYM_RE = Regex("^[A-Z0-9&][A-Z0-9&\\-]{0,24}$")

    fun normName(s: String): String {
        var x = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        x = x.replace(Regex("\\b(limited|ltd|the|india|company|co|corporation|corp)\\b"), " ")
        return x.replace(Regex("\\s+"), " ").trim()
    }

    fun cleanSymbol(raw: String): String {
        var x = raw.trim().trim('"', '\'', '`').trim().uppercase()
        x = x.replace(" ", " ")
        for (p in arrayOf("NSE:", "BSE:", "NSE_EQ|", "NSE|", "NSE-", "NSE ")) if (x.startsWith(p)) x = x.substring(p.length).trim()
        var changed = true
        while (changed) {
            changed = false
            for (sfx in arrayOf(".NS", ".BO", ".NSE", "-EQ", "-BE", "-BZ", "-BL", " EQ", " BE", ":NSE")) {
                if (x.length > sfx.length && x.endsWith(sfx)) {
                    x = x.substring(0, x.length - sfx.length).trim()
                    changed = true
                }
            }
        }
        return x
    }

    fun parseNumber(raw: String?): Double? {
        if (raw == null) return null
        var x = raw.trim().trim('"', '\'').trim()
        if (x.isEmpty()) return null
        var neg = false
        if (x.startsWith("(") && x.endsWith(")")) {
            neg = true
            x = x.substring(1, x.length - 1)
        }
        x = x.replace("₹", "").replace(" ", "").replace(",", "").replace(" ", "")
        x = x.replace(Regex("(?i)^(rs\\.?|inr)"), "").replace(Regex("/-$"), "")
        if (x.endsWith("%")) return null
        if (x.isEmpty() || !Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(x)) return null
        val d = x.toDoubleOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return if (neg) -d else d
    }

    /** Quote-aware split on a single delimiter character. */
    fun splitLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        var k = 0
        while (k < line.length) {
            val ch = line[k]
            if (inQ) {
                if (ch == '"') {
                    if (k + 1 < line.length && line[k + 1] == '"') {
                        sb.append('"')
                        k++
                    } else inQ = false
                } else sb.append(ch)
            } else if (ch == '"') inQ = true
            else if (ch == delim) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            } else sb.append(ch)
            k++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun splitWs(line: String): List<String> = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun normHeader(s: String): String = s.lowercase().replace(Regex("[^a-z0-9%]+"), " ").trim()

    private fun qtyScore(h: String): Int {
        if (QTY_EXCLUDE.any { h.contains(it) }) return 0
        return when {
            h == "qty" || h == "quantity" || h == "net qty" || h == "total qty" || h == "net quantity" || h == "total quantity" -> 5
            h.contains("available") && (h.contains("qty") || h.contains("quantity")) -> 4
            h.contains("qty") || h.contains("quantity") -> 3
            h == "holding" || h == "holdings" || h == "shares" || h == "units" || h == "no of shares" || h == "balance" -> 2
            else -> 0
        }
    }

    private fun avgScore(h: String): Int {
        if (AVG_EXCLUDE.any { h.contains(it) }) return 0
        return when {
            h.contains("avg") || h.contains("average") -> 5
            h == "buy price" || h == "purchase price" || h == "cost price" || h == "buy rate" || h == "purchase rate" || h == "buying price" -> 4
            h == "rate" || h == "cost" || h == "price" || h == "buy" -> 2
            else -> 0
        }
    }

    private fun investedScore(h: String): Int =
        if (h.contains("invested") || h == "buy value" || h == "cost value" || h == "investment" || h == "purchase value" || h == "holding cost") 1 else 0

    private val ISIN_RE = Regex("^IN[A-Z0-9]{10}$")

    private fun resolveSymbol(cell: String, nameToSym: Map<String, String>, known: Set<String>): String? {
        val x = cleanSymbol(cell)
        if (x.isEmpty() || JUNK.contains(x)) return null
        if (known.contains(x)) return x
        val nm = normName(cell)
        if (nm.isNotEmpty()) nameToSym[nm]?.let { return it }
        // brokers often shorten names ("BEHARI LAL ENG", "MANIPAL HEALTH"): accept a company whose name starts
        // with the same words (last word may be cut short), but only when exactly one company fits
        if (nm.length >= 6 && cell.trim().contains(' ')) {
            val toks = nm.split(' ')
            var hit: String? = null
            var many = false
            for ((k, v) in nameToSym) {
                val kt = k.split(' ')
                if (kt.size < toks.size) continue
                var okAll = true
                for (i in toks.indices) {
                    val same = if (i == toks.size - 1) kt[i].startsWith(toks[i]) else kt[i] == toks[i]
                    if (!same) { okAll = false; break }
                }
                if (okAll) { if (hit != null && hit != v) many = true; hit = v }
            }
            if (hit != null && !many) return hit
        }
        if (SYM_RE.matches(x) && x.any { it in 'A'..'Z' } && !ISIN_RE.matches(x)) return x
        return null
    }

    fun parse(textIn: String?, nameToSym: Map<String, String>, known: Set<String>): ImportResult {
        val skipped = ArrayList<String>()
        val merged = LinkedHashMap<String, ImportRow>()
        try {
            var text = textIn ?: ""
            if (text.startsWith("﻿")) text = text.substring(1)
            val lines = text.split(Regex("\r\n|\n|\r"))
            val nonBlank = ArrayList<Pair<Int, String>>()
            for ((n, ln) in lines.withIndex()) {
                val t = ln.replace("﻿", "").trim()
                if (t.isEmpty() || t.all { it == ',' || it == ';' || it == '\t' || it == '|' || it == ' ' || it == '-' || it == '=' }) continue
                nonBlank.add(Pair(n + 1, t))
            }
            if (nonBlank.isEmpty()) return ImportResult(emptyList(), skipped)
            // delimiter: the candidate present in most of the first lines
            val sample = nonBlank.take(12)
            var delim: Char? = null
            var bestCount = 0
            for (cand in charArrayOf('\t', ';', '|', ',')) {
                val cnt = sample.count { it.second.indexOf(cand) >= 0 }
                if (cnt > bestCount && cnt * 2 >= sample.size) {
                    bestCount = cnt
                    delim = cand
                }
            }
            val rows = nonBlank.map { (n, t) ->
                var cells = if (delim != null && (t.indexOf(delim) >= 0 || t.indexOf('"') >= 0)) splitLine(t, delim) else splitWs(t)
                if (cells.size == 1) {
                    // a lone "A,B,C" style line inside otherwise space-separated text
                    for (cand in charArrayOf('\t', ';', '|', ',')) if (t.indexOf(cand) >= 0) {
                        cells = splitLine(t, cand)
                        break
                    }
                }
                Triple(n, t, cells)
            }
            // header row
            var hdr = -1
            var cSym = -1
            var cQty = -1
            var cAvg = -1
            var cInv = -1
            for (ri in 0 until minOf(rows.size, 20)) {
                val cells = rows[ri].third.map { normHeader(it) }
                var s = -1
                var sStrong = false
                var q = -1
                var qs = 0
                var a = -1
                var az = 0
                var inv = -1
                for ((ci, h) in cells.withIndex()) {
                    if (SYM_STRONG.contains(h)) {
                        if (!sStrong) {
                            s = ci
                            sStrong = true
                        }
                    } else if (SYM_WEAK.contains(h) && s < 0) s = ci
                    val x = qtyScore(h)
                    if (x > qs) {
                        qs = x
                        q = ci
                    }
                    val y = avgScore(h)
                    if (y > az) {
                        az = y
                        a = ci
                    }
                    if (inv < 0 && investedScore(h) > 0) inv = ci
                }
                if (s >= 0 && q >= 0) {
                    hdr = ri; cSym = s; cQty = q; cAvg = a; cInv = inv
                    break
                }
            }
            for (ri in (hdr + 1) until rows.size) {
                val (lineNo, raw, cells) = rows[ri]
                var sym: String? = null
                var qty: Double? = null
                var avg: Double? = null
                if (hdr >= 0) {
                    val symCell = cells.getOrNull(cSym) ?: ""
                    if (symCell.isBlank() || JUNK.contains(symCell.trim().uppercase()) || symCell.trim().lowercase().startsWith("total")) continue
                    sym = resolveSymbol(symCell, nameToSym, known)
                    qty = parseNumber(cells.getOrNull(cQty))
                    if (cAvg >= 0) avg = parseNumber(cells.getOrNull(cAvg))
                    if ((avg == null || avg <= 0) && cInv >= 0) {
                        val inv = parseNumber(cells.getOrNull(cInv))
                        if (inv != null && qty != null && qty > 0) avg = inv / qty
                    }
                } else {
                    // headerless: first symbol-looking cell, then the next two numbers are qty and average price
                    var at = -1
                    for ((ci, cell) in cells.withIndex()) {
                        if (parseNumber(cell) != null) continue
                        val r = resolveSymbol(cell, nameToSym, known)
                        if (r != null) {
                            sym = r
                            at = ci
                            break
                        }
                    }
                    if (sym == null && cells.isNotEmpty()) {
                        val first = cells[0].trim().lowercase()
                        if (first.startsWith("total") || first.startsWith("grand")) continue
                    }
                    if (at >= 0) {
                        val nums = ArrayList<Double>()
                        for (ci in at + 1 until cells.size) parseNumber(cells[ci])?.let { nums.add(it) }
                        qty = nums.getOrNull(0)
                        avg = nums.getOrNull(1)
                    }
                }
                val shown = if (raw.length > 60) raw.substring(0, 60) + "…" else raw
                when {
                    sym == null -> skipped.add("Line $lineNo: no stock symbol found — $shown")
                    qty == null || !(qty > 0) -> skipped.add("Line $lineNo: $sym — no quantity")
                    qty > 1e9 || (avg != null && avg > 1e7) -> skipped.add("Line $lineNo: $sym — numbers look wrong")
                    else -> {
                        // a missing buy price is accepted as 0: the holding is still tracked, just without P&L
                        val av = if (avg != null && avg > 0) avg else 0.0
                        val ex = merged[sym]
                        if (ex == null) merged[sym] = ImportRow(sym, qty, av)
                        else if (av <= 0) skipped.add("Line $lineNo: $sym — no average price (already listed above)")
                        else if (ex.avg <= 0) { ex.qty = qty; ex.avg = av }
                        else {
                            val tq = ex.qty + qty
                            ex.avg = (ex.avg * ex.qty + av * qty) / tq
                            ex.qty = tq
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            skipped.add("Could not read part of the text (" + (t.javaClass.simpleName ?: "error") + ")")
        }
        return ImportResult(merged.values.toList(), if (skipped.size > 50) skipped.subList(0, 50) else skipped)
    }
}
