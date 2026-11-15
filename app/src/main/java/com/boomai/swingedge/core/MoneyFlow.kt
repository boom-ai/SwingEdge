package com.boomai.swingedge.core

/*
 * Money flow (REPORT7): where money is going, sector by sector and stock by stock, from NSE's daily bhavcopy
 * (turnover and delivery for every stock), NSE's sector classification and quarterly shareholding filings.
 * Built by the daily GitHub job into data/flows.csv, industries.csv, holders.csv and deals.csv; this file only
 * parses and ranks. Pure Kotlin, never throws (bad rows are skipped).
 *
 * What was tested (2020-07..2026-07, liquid stocks, entry next open):
 *  - sector money flow: stocks in the top-flow sectors beat the bottom ones in both halves; among high-score
 *    (BUY-grade) stocks, those in high-flow sectors beat the rest by ~1.2% (2020-23) and ~0.7% (2023-26) over 60 sessions.
 *  - stock-level money flow alone (delivery, turnover surges, Chaikin flow): no reliable edge in either half.
 *  - stake increases: see MoneyTexts (filled from the shareholding test).
 */

class FlowRow(
    val sym: String, val close: Double, val r1: Double, val r5: Double, val r20: Double,
    val to1: Double, val to20: Double, val dlv1: Double, val dlv20: Double,
    val net5: Double, val net20: Double, val flow20: Double, val cmf20: Double, val dsurge: Double, val asof: String
)

class IndRow(val sym: String, val macro: String, val sector: String, val industry: String, val basic: String)

class HolderRow(
    val sym: String, val qdate: String, val submit: String, val pqdate: String,
    /** latest and previous quarter, % of shares: promoter, FII (foreign institutions), MF, other domestic institutions, small retail */
    val now: DoubleArray, val prev: DoubleArray
) {
    fun delta(k: Int): Double = if (now[k].isNaN() || prev[k].isNaN()) Double.NaN else now[k] - prev[k]
}

class Deal(val date: String, val kind: String, val sym: String, val client: String, val side: String, val qty: Double, val price: Double) {
    val valueCr: Double get() = qty * price / 1e7
}

class FlowData(
    val flows: Map<String, FlowRow>,
    val ind: Map<String, IndRow>,
    val holders: Map<String, HolderRow>,
    val deals: List<Deal>
) {
    val asof: String = flows.values.maxOfOrNull { it.asof } ?: ""
    val ok: Boolean get() = flows.size >= 200
}

object MoneyFlow {
    /** a stock counts for sector flow and rankings only with at least this average daily turnover (rupees crore) */
    const val MIN_TO20_CR = 1.0
    const val MIN_PRICE = 10.0
    const val SECTOR_MIN_STOCKS = 3
    const val HOT_SECTORS = 5
    /** promoter / institution stake change that counts as "raising" (percentage points of all shares) */
    const val PROMOTER_UP = 0.25
    const val INST_UP = 0.5
    val HOLDER_KEYS = arrayOf("PROMOTER", "FII", "MF", "DII", "RETAIL")
    val HOLDER_NAMES = arrayOf("Promoters", "FIIs", "Mutual funds", "Other domestic institutions", "Small retail investors")

    // ------------------------------------------------------------------ parsing

    private fun rows(csv: String?): List<Map<String, String>> {
        if (csv == null) return emptyList()
        val out = ArrayList<Map<String, String>>()
        try {
            val lines = csv.replace("\r", "").replace("﻿", "").split('\n')
            if (lines.isEmpty()) return out
            val hdr = lines[0].split(',').map { it.trim().uppercase() }
            for (k in 1 until lines.size) {
                val l = lines[k]
                if (l.isBlank()) continue
                val c = l.split(',')
                if (c.size < hdr.size) continue
                val m = HashMap<String, String>(hdr.size * 2)
                for (i in hdr.indices) m[hdr[i]] = c[i].trim()
                out.add(m)
            }
        } catch (t: Throwable) {}
        return out
    }

    private fun d(m: Map<String, String>, k: String): Double = m[k]?.toDoubleOrNull() ?: Double.NaN

    fun parse(flowsCsv: String?, indCsv: String?, holdersCsv: String?, dealsCsv: String?): FlowData {
        val f = HashMap<String, FlowRow>()
        for (m in rows(flowsCsv)) {
            val s = m["SYMBOL"]?.uppercase() ?: continue
            val close = d(m, "CLOSE")
            if (s.isEmpty() || !(close > 0)) continue
            f[s] = FlowRow(s, close, d(m, "R1"), d(m, "R5"), d(m, "R20"), d(m, "TO1"), d(m, "TO20"), d(m, "DLV1"), d(m, "DLV20"),
                d(m, "NET5"), d(m, "NET20"), d(m, "FLOW20"), d(m, "CMF20"), d(m, "DSURGE"), m["ASOF"] ?: "")
        }
        val ind = HashMap<String, IndRow>()
        for (m in rows(indCsv)) {
            val s = m["SYMBOL"]?.uppercase() ?: continue
            val sec = m["SECTOR"] ?: ""
            if (s.isEmpty() || sec.isEmpty()) continue
            ind[s] = IndRow(s, m["MACRO"] ?: "", sec, m["INDUSTRY"] ?: "", m["BASIC"] ?: "")
        }
        val h = HashMap<String, HolderRow>()
        for (m in rows(holdersCsv)) {
            val s = m["SYMBOL"]?.uppercase() ?: continue
            if (s.isEmpty()) continue
            h[s] = HolderRow(s, m["QDATE"] ?: "", m["SUBMIT"] ?: "", m["PQDATE"] ?: "",
                DoubleArray(5) { d(m, HOLDER_KEYS[it]) }, DoubleArray(5) { d(m, "P" + HOLDER_KEYS[it]) })
        }
        val deals = ArrayList<Deal>()
        for (m in rows(dealsCsv)) {
            val s = m["SYMBOL"]?.uppercase() ?: continue
            val q = d(m, "QTY"); val p = d(m, "PRICE")
            if (s.isEmpty() || !(q > 0) || !(p > 0)) continue
            deals.add(Deal(m["DATE"] ?: "", m["KIND"] ?: "", s, m["CLIENT"] ?: "", m["SIDE"] ?: "", q, p))
        }
        return FlowData(f, ind, h, deals)
    }

    // ------------------------------------------------------------------ ranking

    fun liquid(r: FlowRow): Boolean = r.to20 >= MIN_TO20_CR && r.close >= MIN_PRICE

    /** Percentile (0..100) of each liquid stock's composite "money coming in": mean rank of FLOW20, CMF20 and DSURGE. */
    fun moneyIn(data: FlowData): Map<String, Double> {
        val liq = data.flows.values.filter { liquid(it) }
        if (liq.size < 30) return emptyMap()
        val parts = listOf<(FlowRow) -> Double>({ it.flow20 }, { it.cmf20 }, { if (it.dsurge > 0) Math.log(it.dsurge) else Double.NaN })
        val sum = HashMap<String, Double>(); val cnt = HashMap<String, Int>()
        for (g in parts) {
            val xs = liq.filter { !g(it).isNaN() }.sortedWith(compareBy<FlowRow>({ g(it) }, { it.sym }))
            if (xs.size < 30) continue
            for ((i, r) in xs.withIndex()) {
                sum[r.sym] = (sum[r.sym] ?: 0.0) + 100.0 * (i + 0.5) / xs.size
                cnt[r.sym] = (cnt[r.sym] ?: 0) + 1
            }
        }
        val out = HashMap<String, Double>()
        for ((s, v) in sum) { val n = cnt[s] ?: 0; if (n >= 2) out[s] = Math.rint(v / n) }
        return out
    }

    class Sector(
        val name: String, val n: Int, val net5: Double, val net20: Double, val to20: Double,
        /** net money in over 20 sessions as % of the sector's normal 20-session turnover */
        val flowPct: Double,
        /** share of the sector's stocks with money coming in (FLOW20 > 0), % */
        val breadth: Double,
        val r20: Double, val rank: Int, val top: List<String>
    )

    fun sectors(data: FlowData, mi: Map<String, Double>): List<Sector> {
        val by = HashMap<String, ArrayList<FlowRow>>()
        for (r in data.flows.values) {
            if (!liquid(r) || r.flow20.isNaN()) continue
            val sec = data.ind[r.sym]?.sector ?: continue
            by.getOrPut(sec) { ArrayList() }.add(r)
        }
        val list = ArrayList<Sector>()
        for ((name, rs) in by) {
            if (rs.size < SECTOR_MIN_STOCKS) continue
            val to20 = rs.sumOf { it.to20 }
            val net20 = rs.sumOf { if (it.net20.isNaN()) 0.0 else it.net20 }
            val net5 = rs.sumOf { if (it.net5.isNaN()) 0.0 else it.net5 }
            // turnover-weighted, like the tested sector flow: sum of net20 over sum of 20 x turnover
            val flowPct = if (to20 > 0) 100.0 * net20 / (20.0 * to20) else Double.NaN
            val breadth = 100.0 * rs.count { it.flow20 > 0 } / rs.size
            val r20s = rs.map { it.r20 }.filter { !it.isNaN() }.sorted()
            val med = if (r20s.isEmpty()) Double.NaN else r20s[r20s.size / 2]
            val top = rs.sortedWith(compareByDescending<FlowRow> { mi[it.sym] ?: -1.0 }.thenByDescending { it.net20 }.thenBy { it.sym }).take(3).map { it.sym }
            list.add(Sector(name, rs.size, r1(net5), r1(net20), r1(to20), r2(flowPct), Math.rint(breadth), r2(med), 0, top))
        }
        val sorted = list.sortedWith(compareByDescending<Sector> { if (it.flowPct.isNaN()) -1e9 else it.flowPct }.thenBy { it.name })
        return sorted.mapIndexed { i, s -> Sector(s.name, s.n, s.net5, s.net20, s.to20, s.flowPct, s.breadth, s.r20, i + 1, s.top) }
    }

    fun r1(x: Double) = if (x.isNaN()) x else Math.rint(x * 10) / 10
    fun r2(x: Double) = if (x.isNaN()) x else Math.rint(x * 100) / 100

    // ------------------------------------------------------------------ JSON for the app

    fun stockMap(r: FlowRow, data: FlowData, mi: Map<String, Double>, name: String, score: ScoreEntry?, sectorRank: Int): Map<String, Any?> {
        val ii = data.ind[r.sym]
        return linkedMapOf(
            "sym" to r.sym, "name" to name, "close" to r.close, "r1" to nz(r.r1), "r5" to nz(r.r5), "r20" to nz(r.r20),
            "to20" to nz(r1(r.to20)), "dlv20" to nz(r.dlv20), "dlv1" to nz(r.dlv1), "net5" to nz(r1(r.net5)), "net20" to nz(r1(r.net20)),
            "flowPct" to nz(r2(r.flow20 * 100)), "moneyIn" to (mi[r.sym] ?: -1.0),
            "sector" to (ii?.sector ?: ""), "industry" to (ii?.basic?.ifEmpty { ii.industry } ?: ""), "sectorRank" to sectorRank,
            "score" to (if (score != null && score.rated) score.score else -1.0), "label" to (score?.label ?: "NA")
        )
    }

    private fun nz(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else x

    fun holderMap(h: HolderRow, name: String, close: Double): Map<String, Any?> = linkedMapOf(
        "sym" to h.sym, "name" to name, "qdate" to h.qdate, "pqdate" to h.pqdate, "filed" to h.submit,
        "now" to h.now.map { nz(it) }, "prev" to h.prev.map { nz(it) }, "delta" to (0 until 5).map { nz(r2(h.delta(it))) },
        "has" to (0 until 5).map { !h.now[it].isNaN() && !h.prev[it].isNaN() }, "close" to close
    )

    fun dealMap(x: Deal, name: String): Map<String, Any?> = linkedMapOf(
        "date" to x.date, "kind" to x.kind, "sym" to x.sym, "name" to name, "client" to x.client, "side" to x.side,
        "qty" to x.qty, "price" to x.price, "valueCr" to r1(x.valueCr)
    )

    private val FUNDISH = Regex("(?i)mutual|\\bfund\\b|\\bmf\\b|insurance|life|pension|capital|asset|investment|investor|securities|portfolio|trust|bank|plc|llc|\\bltd\\b|limited")

    /**
     * The whole Money tab. [scores] are the app's setup scores (only the scanned universe has them); [names] gives
     * company names. Lists are bounded so the reply stays small.
     */
    fun view(data: FlowData, scores: Map<String, ScoreEntry>, names: (String) -> String): Map<String, Any?> {
        if (!data.ok) return linkedMapOf("ok" to true, "ready" to false, "asof" to data.asof)
        val mi = moneyIn(data)
        val secs = sectors(data, mi)
        val secRank = HashMap<String, Int>(); for (s in secs) secRank[s.name] = s.rank
        fun rankOf(sym: String) = data.ind[sym]?.sector?.let { secRank[it] } ?: 0
        fun sm(r: FlowRow) = stockMap(r, data, mi, names(r.sym), scores[r.sym], rankOf(r.sym))
        val liq = data.flows.values.filter { liquid(it) }
        // strongest money coming in: composite percentile, ties by net money
        val inflow = liq.filter { (mi[it.sym] ?: 0.0) >= 90 && it.net20 > 0 }
            .sortedWith(compareByDescending<FlowRow> { mi[it.sym] ?: 0.0 }.thenByDescending { it.net20 }.thenBy { it.sym }).take(25)
        val bigMoney = liq.filter { it.net5 > 0 }.sortedWith(compareByDescending<FlowRow> { it.net5 }.thenBy { it.sym }).take(15)
        // the tested combination: BUY-grade setup score in one of the top money-flow sectors
        val hot = secs.take(HOT_SECTORS).map { it.name }.toHashSet()
        val picks = scores.values.filter { it.rated && it.label == "BUY" }
            .mapNotNull { e -> data.flows[e.sym]?.let { r -> if (data.ind[e.sym]?.sector in hot) Pair(e, r) else null } }
            .sortedWith(compareByDescending<Pair<ScoreEntry, FlowRow>> { it.first.score }.thenByDescending { mi[it.second.sym] ?: 0.0 }.thenBy { it.first.sym })
            .take(15).map { sm(it.second) }
        val outflow = liq.filter { (mi[it.sym] ?: 100.0) <= 10 && it.net20 < 0 }
            .sortedWith(compareBy<FlowRow> { mi[it.sym] ?: 0.0 }.thenBy { it.net20 }.thenBy { it.sym }).take(10)
        return linkedMapOf(
            "ok" to true, "ready" to true, "asof" to data.asof, "stocks" to data.flows.size, "liquid" to liq.size,
            "sectors" to secs.map { s -> linkedMapOf("name" to s.name, "n" to s.n, "net5" to nz(s.net5), "net20" to nz(s.net20), "to20" to nz(s.to20),
                "flowPct" to nz(s.flowPct), "breadth" to nz(s.breadth), "r20" to nz(s.r20), "rank" to s.rank,
                "top" to s.top.map { t -> linkedMapOf("sym" to t, "name" to names(t), "moneyIn" to (mi[t] ?: -1.0)) }) },
            "picks" to picks,
            "inflow" to inflow.map { sm(it) },
            "bigMoney" to bigMoney.map { sm(it) },
            "outflow" to outflow.map { sm(it) },
            "stakes" to stakes(data, names),
            "deals" to deals(data, names),
            "evidence" to MoneyTexts.evidence()
        )
    }

    fun stakes(data: FlowData, names: (String) -> String): Map<String, Any?> {
        val all = data.holders.values.filter { it.qdate.isNotEmpty() && it.pqdate.isNotEmpty() }
        fun close(s: String) = data.flows[s]?.close ?: 0.0
        fun tradable(h: HolderRow) = data.flows[h.sym]?.let { it.to20 >= 0.2 && it.close >= MIN_PRICE } ?: false
        fun list(k: Int, min: Double) = all.filter { tradable(it) && it.delta(k) >= min }
            .sortedWith(compareByDescending<HolderRow> { it.delta(k) }.thenBy { it.sym }).take(30).map { holderMap(it, names(it.sym), close(it.sym)) }
        // promoter not cutting, and FIIs, MFs and other institutions all adding
        val together = all.filter { tradable(it) && !(it.delta(0) < 0) && it.delta(1) > 0 && it.delta(2) > 0 && it.delta(3) >= 0 && (it.delta(1) + it.delta(2)) >= INST_UP }
            .sortedWith(compareByDescending<HolderRow> { it.delta(1) + it.delta(2) + it.delta(3) }.thenBy { it.sym }).take(30).map { holderMap(it, names(it.sym), close(it.sym)) }
        val q = all.groupingBy { it.qdate }.eachCount().maxByOrNull { it.value }?.key ?: ""
        return linkedMapOf(
            "quarter" to q, "companies" to all.size,
            "withInst" to all.count { !it.delta(1).isNaN() },
            "promoters" to list(0, PROMOTER_UP), "fii" to list(1, INST_UP), "mf" to list(2, INST_UP), "together" to together
        )
    }

    fun deals(data: FlowData, names: (String) -> String): List<Map<String, Any?>> {
        val buys = data.deals.filter { it.side == "BUY" && it.valueCr >= 5.0 }
        return buys.sortedWith(compareByDescending<Deal> { it.date }.thenByDescending { it.valueCr }.thenBy { it.sym }).take(40).map { dealMap(it, names(it.sym)) }
    }

    /** Stocks of one sector (or one basic industry inside it), money coming in first. */
    fun sector(data: FlowData, name: String, scores: Map<String, ScoreEntry>, names: (String) -> String): Map<String, Any?> {
        val mi = moneyIn(data)
        val secs = sectors(data, mi)
        val s = secs.firstOrNull { it.name == name } ?: return linkedMapOf("ok" to false, "error" to "No money-flow data for $name")
        val rs = data.flows.values.filter { liquid(it) && data.ind[it.sym]?.sector == name }
            .sortedWith(compareByDescending<FlowRow> { mi[it.sym] ?: -1.0 }.thenByDescending { it.net20 }.thenBy { it.sym })
        val inds = HashMap<String, DoubleArray>()   // basic industry -> net20, to20, n
        for (r in rs) {
            val k = data.ind[r.sym]?.let { it.basic.ifEmpty { it.industry } }?.ifEmpty { null } ?: continue
            val a = inds.getOrPut(k) { DoubleArray(3) }
            a[0] += if (r.net20.isNaN()) 0.0 else r.net20; a[1] += r.to20; a[2] += 1.0
        }
        val indList = inds.entries.filter { it.value[2] >= 2 }.map { (k, a) -> linkedMapOf("name" to k, "n" to a[2].toInt(), "net20" to r1(a[0]),
            "flowPct" to (if (a[1] > 0) r2(100.0 * a[0] / (20.0 * a[1])) else 0.0)) }
            .sortedByDescending { it["flowPct"] as Double }
        return linkedMapOf(
            "ok" to true, "name" to name, "rank" to s.rank, "of" to secs.size, "flowPct" to nz(s.flowPct), "net5" to nz(s.net5), "net20" to nz(s.net20),
            "breadth" to nz(s.breadth), "r20" to nz(s.r20), "industries" to indList,
            "stocks" to rs.take(60).map { stockMap(it, data, mi, names(it.sym), scores[it.sym], s.rank) }
        )
    }

    /** Money-flow and shareholding block for one stock's page; null when there is no data for it. */
    fun forStock(data: FlowData, sym: String): Map<String, Any?>? {
        if (!data.ok) return null
        val r = data.flows[sym]
        val h = data.holders[sym]
        if (r == null && h == null) return null
        val mi = moneyIn(data)
        val secs = sectors(data, mi)
        val sec = data.ind[sym]?.sector
        val s = secs.firstOrNull { it.name == sec }
        return linkedMapOf(
            "asof" to data.asof,
            "flow" to r?.let { stockMap(it, data, mi, sym, null, s?.rank ?: 0) },
            "sector" to (sec ?: ""), "industry" to (data.ind[sym]?.let { it.basic.ifEmpty { it.industry } } ?: ""),
            "sectorRank" to (s?.rank ?: 0), "sectorOf" to secs.size, "sectorFlowPct" to nz(s?.flowPct ?: Double.NaN),
            "holders" to h?.let { holderMap(it, sym, r?.close ?: 0.0) },
            "deals" to data.deals.filter { it.sym == sym }.take(8).map { dealMap(it, sym) },
            "line" to MoneyTexts.stockLine(r, mi[sym], s, secs.size, h)
        )
    }
}

object MoneyTexts {
    const val SECTOR_EVIDENCE = "Tested 2020–2026: stocks in the sectors with the most money coming in did better than those in the sectors money was leaving, in both halves of the test. Among strong-setup (BUY-grade) stocks, those in the top money-flow sectors beat the others by about 1.2% (2020–23) and 0.7% (2023–26) over the next 60 trading days. A tilt, not a guarantee."
    const val STOCK_EVIDENCE = "Money flow of a single stock (delivery, turnover jumps, buying pressure) did not reliably predict its next 20–60 days in the test. Use it to confirm a setup, not as a reason to buy on its own."
    @Volatile var STAKE_EVIDENCE = "Stake changes come from the quarterly shareholding filings, so they are up to 3 months old by the time they are public. In the test, promoters raising their stake by 0.5–2 points was followed by slightly better returns than other stocks, but the difference was too small to rely on."

    fun evidence(): List<String> = listOf(SECTOR_EVIDENCE, STOCK_EVIDENCE, STAKE_EVIDENCE)

    fun cr(x: Double): String = (if (x >= 0) "+" else "−") + "₹" + String.format(java.util.Locale.US, "%,.1f", Math.abs(x)) + " cr"

    fun stockLine(r: FlowRow?, mi: Double?, s: MoneyFlow.Sector?, of: Int, h: HolderRow?): String {
        val parts = ArrayList<String>()
        if (r != null && MoneyFlow.liquid(r) && mi != null) {
            parts.add(when {
                mi >= 90 -> "Money coming in strongly (top 10% of liquid stocks)"
                mi >= 70 -> "More money coming in than going out"
                mi <= 10 -> "Money leaving (bottom 10% of liquid stocks)"
                mi <= 30 -> "More money going out than coming in"
                else -> "Money flow neutral"
            } + ": net " + cr(r.net20) + " delivered in 20 sessions.")
        } else if (r != null) parts.add("Too thinly traded for a money-flow reading (under ₹1 cr a day).")
        if (s != null) parts.add(s.name + " is #" + s.rank + " of " + of + " sectors for money coming in.")
        if (h != null) {
            val ups = (0 until 4).filter { k -> h.delta(k) >= (if (k == 0) MoneyFlow.PROMOTER_UP else MoneyFlow.INST_UP) }.map { MoneyFlow.HOLDER_NAMES[it] }
            val downs = (0 until 4).filter { k -> h.delta(k) <= -(if (k == 0) MoneyFlow.PROMOTER_UP else MoneyFlow.INST_UP) }.map { MoneyFlow.HOLDER_NAMES[it] }
            if (ups.isNotEmpty()) parts.add(ups.joinToString(", ") + " raised their stake last quarter.")
            if (downs.isNotEmpty()) parts.add(downs.joinToString(", ") + " cut their stake last quarter.")
        }
        return parts.joinToString(" ")
    }
}
