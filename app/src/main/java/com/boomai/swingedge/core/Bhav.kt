package com.boomai.swingedge.core

/**
 * One equity row of an exchange's daily bhavcopy. Prices in rupees as printed (unadjusted); [prev] is the exchange's
 * previous close, which the exchange adjusts on the ex-date of a split / bonus / rights issue (used to back-adjust history).
 * [code] = BSE scrip code (empty for NSE); [turnover] in rupees (NaN when the file has none).
 */
class BhavRow(
    val exch: String, val sym: String, val code: String, val isin: String, val series: String, val name: String,
    val o: Double, val h: Double, val l: Double, val c: Double, val prev: Double, val vol: Double, val turnover: Double
)

/** One parsed bhavcopy file. [day] = IST epoch day of the session (0 when the file carried no date). */
class BhavDay(val day: Int, val exch: String, val rows: List<BhavRow>, val format: String)

/**
 * Parsers for the official daily files, chosen by their header (never by file name). Pure; never throws.
 *  - NSE UDiFF  BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv  (TradDt, TckrSymb, SctySrs, ISIN, OpnPric … PrvsClsgPric, TtlTradgVol, TtlTrfVal)
 *  - BSE UDiFF  BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.CSV  (same columns; FinInstrmId = scrip code, SctySrs = group)
 *  - NSE sec_bhavdata_full_DDMMYYYY.csv                     (SYMBOL, SERIES, DATE1, PREV_CLOSE … TTL_TRD_QNTY, TURNOVER_LACS; no ISIN)
 *  - BSE EQ_ISINCODE_DDMMYY.CSV (pre-2024)                   (SC_CODE, SC_NAME, SC_GROUP, … PREVCLOSE, NO_OF_SHRS, NET_TURNOV, ISIN_CODE)
 */
object Bhav {
    const val NSE = "NSE"
    const val BSE = "BSE"
    /** NSE series kept: main board (EQ), trade-for-trade (BE, BZ), SME (SM, ST). Earlier = preferred for a duplicate symbol. */
    val NSE_SERIES = listOf("EQ", "BE", "BZ", "SM", "ST")

    private val MON = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** Equity share ISIN of an Indian company: INE + issuer + security type "01". */
    fun equityIsin(isin: String): Boolean = isin.length == 12 && isin.startsWith("INE") && isin.substring(7, 9) == "01"

    private fun num(s: String?): Double {
        val t = (s ?: return Double.NaN).trim().replace(",", "")
        if (t.isEmpty() || t == "-") return Double.NaN
        return t.toDoubleOrNull()?.takeIf { !it.isInfinite() } ?: Double.NaN
    }

    /** 2026-11-27, 27-Nov-2026, 27-NOV-26, 27/11/2026, 20261127 -> epoch day; 0 if not a date. */
    fun parseDay(raw: String?): Int {
        try {
            val s = (raw ?: return 0).trim().trim('"').trim()
            if (s.length == 8 && s.all { it.isDigit() }) return java.time.LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt()).toEpochDay().toInt()
            val p = s.split('-', '/', ' ').filter { it.isNotEmpty() }
            if (p.size != 3) return 0
            val y: Int; val m: Int; val d: Int
            if (p[0].length == 4) { y = p[0].toInt(); m = p[1].toInt(); d = p[2].toInt() }
            else {
                d = p[0].toInt()
                val mi = MON.indexOf(p[1].uppercase().take(3))
                m = if (mi >= 0) mi + 1 else p[1].toInt()
                y = p[2].toInt().let { if (it < 100) 2000 + it else it }
            }
            return java.time.LocalDate.of(y, m, d).toEpochDay().toInt()
        } catch (t: Throwable) { return 0 }
    }

    private fun header(line: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        for ((k, h) in StockList.splitCsv(line.replace("﻿", "")).withIndex()) m[h.trim().uppercase().trimStart('_')] = k
        return m
    }

    /**
     * Parses any supported file. [isinBySym] fills the ISIN of NSE sec_bhavdata_full rows (that file has none; NSE's
     * EQUITY_L list has them). Null when the header is not a known bhavcopy or no equity row is usable.
     */
    fun parse(text: String?, isinBySym: Map<String, String> = emptyMap()): BhavDay? {
        try {
            if (text == null || text.length < 50) return null
            val lines = text.split('\n')
            val ix = header(lines[0])
            fun f(row: List<String>, col: String): String? = ix[col]?.let { row.getOrNull(it) }
            val out = ArrayList<BhavRow>()
            var day = 0
            val format: String
            val exch: String
            when {
                ix.containsKey("TCKRSYMB") && ix.containsKey("CLSPRIC") -> {
                    format = "udiff"
                    var ex = ""
                    for (k in 1 until lines.size) {
                        val line = lines[k].trimEnd('\r'); if (line.isBlank()) continue
                        val r = StockList.splitCsv(line)
                        val src = (f(r, "SRC") ?: "").trim().uppercase()
                        val sg = (f(r, "SGMT") ?: "CM").trim().uppercase()
                        val tp = (f(r, "FININSTRMTP") ?: "STK").trim().uppercase()
                        if (sg != "CM" || tp != "STK") continue
                        val isBse = src == BSE
                        if (ex.isEmpty()) ex = if (isBse) BSE else NSE
                        val series = (f(r, "SCTYSRS") ?: "").trim().uppercase()
                        val isin = (f(r, "ISIN") ?: "").trim().uppercase()
                        if (isBse) { if (!equityIsin(isin)) continue } else if (series !in NSE_SERIES) continue
                        if (day == 0) day = parseDay(f(r, "TRADDT"))
                        out.add(BhavRow(if (isBse) BSE else NSE, (f(r, "TCKRSYMB") ?: "").trim().uppercase(), if (isBse) (f(r, "FININSTRMID") ?: "").trim() else "",
                            isin, series, (f(r, "FININSTRMNM") ?: "").trim(),
                            num(f(r, "OPNPRIC")), num(f(r, "HGHPRIC")), num(f(r, "LWPRIC")), num(f(r, "CLSPRIC")), num(f(r, "PRVSCLSGPRIC")),
                            num(f(r, "TTLTRADGVOL")), num(f(r, "TTLTRFVAL"))))
                    }
                    exch = ex.ifEmpty { NSE }
                }
                ix.containsKey("SYMBOL") && ix.containsKey("CLOSE_PRICE") && ix.containsKey("SERIES") -> {
                    format = "nsefull"; exch = NSE
                    for (k in 1 until lines.size) {
                        val line = lines[k].trimEnd('\r'); if (line.isBlank()) continue
                        val r = StockList.splitCsv(line)
                        val series = (f(r, "SERIES") ?: "").trim().uppercase()
                        if (series !in NSE_SERIES) continue
                        val sym = (f(r, "SYMBOL") ?: "").trim().uppercase()
                        if (day == 0) day = parseDay(f(r, "DATE1"))
                        val lacs = num(f(r, "TURNOVER_LACS"))
                        out.add(BhavRow(NSE, sym, "", isinBySym[sym] ?: "", series, "",
                            num(f(r, "OPEN_PRICE")), num(f(r, "HIGH_PRICE")), num(f(r, "LOW_PRICE")), num(f(r, "CLOSE_PRICE")), num(f(r, "PREV_CLOSE")),
                            num(f(r, "TTL_TRD_QNTY")), lacs * 1e5))
                    }
                }
                ix.containsKey("SC_CODE") && ix.containsKey("CLOSE") -> {
                    format = "bseold"; exch = BSE
                    for (k in 1 until lines.size) {
                        val line = lines[k].trimEnd('\r'); if (line.isBlank()) continue
                        val r = StockList.splitCsv(line)
                        val isin = (f(r, "ISIN_CODE") ?: "").trim().uppercase()
                        if (!equityIsin(isin)) continue
                        if (day == 0) day = parseDay(f(r, "TRADING_DATE"))
                        val code = (f(r, "SC_CODE") ?: "").trim()
                        val nm = (f(r, "SC_NAME") ?: "").trim()
                        out.add(BhavRow(BSE, "", code, isin, (f(r, "SC_GROUP") ?: "").trim().uppercase(), nm,
                            num(f(r, "OPEN")), num(f(r, "HIGH")), num(f(r, "LOW")), num(f(r, "CLOSE")), num(f(r, "PREVCLOSE")),
                            num(f(r, "NO_OF_SHRS")), num(f(r, "NET_TURNOV"))))
                    }
                }
                else -> return null
            }
            val ok = out.filter { it.c > 0 && (it.sym.isNotEmpty() || it.code.isNotEmpty()) }
            if (ok.isEmpty()) return null
            return BhavDay(day, exch, ok, format)
        } catch (t: Throwable) { return null }
    }

    /** NSE EQUITY_L.csv (SYMBOL, NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, …) -> symbol -> ISIN. */
    fun isinMap(equityL: String?): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val lines = (equityL ?: return out).split('\n')
            val ix = header(lines[0])
            val s = ix["SYMBOL"] ?: return out
            val i = ix["ISIN NUMBER"] ?: ix["ISIN"] ?: return out
            for (k in 1 until lines.size) {
                val r = StockList.splitCsv(lines[k].trimEnd('\r'))
                val sym = r.getOrNull(s)?.trim()?.uppercase() ?: continue
                val isin = r.getOrNull(i)?.trim()?.uppercase() ?: continue
                if (sym.isNotEmpty() && isin.length == 12) out[sym] = isin
            }
        } catch (t: Throwable) {}
        return out
    }
}
