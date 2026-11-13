package com.boomai.swingedge.core

/** A merger: holders of [old] got [num]/[den] shares of [to] each, plus [exNum]/[exDen] of [extra] per [to] share. */
class CorpAction(
    val old: String, val to: String, val num: Int, val den: Int,
    val extra: String?, val exNum: Int, val exDen: Int, val oldName: String, val effective: String
) {
    fun newQty(q: Double): Double = Math.floor(q * num / den + 1e-9)
    fun extraQty(q: Double): Double = if (extra == null || exDen <= 0) 0.0 else Math.floor(newQty(q) * exNum / exDen + 1e-9)
    /** Same money spent, more or fewer shares: the buy price per new share (0 stays 0 = unknown). */
    fun newAvg(avg: Double): Double = if (avg > 0 && num > 0) avg * den / num else 0.0
}

/** NSE's official symbol-change list (symbolchange.csv) plus mergers (corpactions.csv). Nothing here throws. */
object SymbolChanges {
    /** OLD -> NEW from "NAME,OLD,NEW,DATE" rows (header and junk skipped). */
    fun parseNse(csv: String?): Map<String, String> {
        val m = HashMap<String, String>()
        if (csv == null) return m
        try {
            for (raw in csv.split('\n')) {
                val c = splitCsv(raw)
                if (c.size < 3) continue
                val old = StockList.cleanSym(c[c.size - 3]) ?: continue
                val new = StockList.cleanSym(c[c.size - 2]) ?: continue
                if (old == "OLD SYMBOL" || old == new) continue
                m[old] = new
            }
        } catch (t: Throwable) {}
        return m
    }

    fun parseCorp(csv: String?): List<CorpAction> {
        val out = ArrayList<CorpAction>()
        if (csv == null) return out
        try {
            for (raw in csv.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val c = splitCsv(line)
                if (c.size < 9) continue
                val old = StockList.cleanSym(c[0]) ?: continue
                val to = StockList.cleanSym(c[1]) ?: continue
                val num = c[2].trim().toIntOrNull() ?: continue
                val den = c[3].trim().toIntOrNull() ?: continue
                if (num <= 0 || den <= 0) continue
                val ex = StockList.cleanSym(c[4])
                out.add(CorpAction(old, to, num, den, ex, c[5].trim().toIntOrNull() ?: 0, c[6].trim().toIntOrNull() ?: 0, c[7].trim(), c[8].trim()))
            }
        } catch (t: Throwable) {}
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line.replace("\r", "").replace("﻿", "")) {
            when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }
}
