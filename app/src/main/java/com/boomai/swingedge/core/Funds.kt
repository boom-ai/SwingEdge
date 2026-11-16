package com.boomai.swingedge.core

/*
 * Mutual funds: direct-growth schemes from AMFI NAVs (data/funds.csv), top holdings (data/fund_holdings.csv) and
 * what funds hold most (data/fund_stocks.csv), all built by the daily GitHub job. Pure Kotlin, never throws.
 *
 * Long-term ranking, inside each category only (comparing a small-cap fund with a liquid fund is meaningless):
 * the mean percentile of 3-year rolling consistency (counted twice: how often the fund beat its category over
 * every 3-year window), 5-year return (3-year when younger), 3-year Sharpe and 3-year max drawdown.
 * Research on fund persistence finds raw past returns barely persist; consistency, risk control and cost do
 * better. Costs (expense ratios) are not in the data yet, so direct plans only.
 */

class Fund(
    val code: String, val name: String, val amc: String, val cat: String, val sub: String,
    val nav: Double, val navDate: String, val aum: Double,
    val r1m: Double, val r3m: Double, val r6m: Double, val r1y: Double, val r3y: Double, val r5y: Double, val r10y: Double, val since: Double,
    val vol: Double, val mdd: Double, val sharpe: Double, val sortino: Double,
    val rollMed: Double, val rollMin: Double, val rollBeat: Double, val age: Double
)

class FundHolding(val code: String, val asof: String, val stock: String, val isin: String, val pct: Double)
class FundStock(val stock: String, val isin: String, val funds: Int, val avgPct: Double, val added: Int)

class FundData(val funds: Map<String, Fund>, val holdings: Map<String, List<FundHolding>>, val stocks: List<FundStock>) {
    val ok: Boolean get() = funds.size >= 100
    val asof: String = funds.values.maxOfOrNull { it.navDate } ?: ""
}

object Funds {
    /** categories shown first, in this order (AMFI sub-category names) */
    val KEY = listOf(
        "Flexi Cap Fund", "Large Cap Fund", "Large & Mid Cap Fund", "Mid Cap Fund", "Small Cap Fund", "Multi Cap Fund",
        "ELSS", "Focused Fund", "Value Fund", "Contra Fund", "Index Funds", "Balanced Advantage Fund", "Aggressive Hybrid Fund", "Multi Asset Allocation Fund"
    )
    const val MIN_AGE_LONG = 5.0

    private fun rows(csv: String?): List<Map<String, String>> {
        if (csv == null) return emptyList()
        val out = ArrayList<Map<String, String>>()
        try {
            val lines = csv.replace("\r", "").replace("﻿", "").split('\n')
            val hdr = splitCsv(lines.firstOrNull() ?: return out).map { it.trim().uppercase() }
            for (k in 1 until lines.size) {
                if (lines[k].isBlank()) continue
                val c = splitCsv(lines[k])
                if (c.size < hdr.size) continue
                val m = HashMap<String, String>(hdr.size * 2)
                for (i in hdr.indices) m[hdr[i]] = c[i].trim()
                out.add(m)
            }
        } catch (t: Throwable) {}
        return out
    }

    private fun splitCsv(line: String): List<String> {
        if (line.indexOf('"') < 0) return line.split(',')
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString())
        return out
    }

    private fun d(m: Map<String, String>, k: String): Double = m[k]?.toDoubleOrNull()?.takeIf { !it.isInfinite() } ?: Double.NaN

    fun parse(fundsCsv: String?, holdCsv: String?, stocksCsv: String?): FundData {
        val f = HashMap<String, Fund>()
        for (m in rows(fundsCsv)) {
            val code = m["CODE"] ?: continue
            val name = m["NAME"] ?: continue
            val nav = d(m, "NAV")
            if (code.isEmpty() || name.isEmpty() || !(nav > 0)) continue
            f[code] = Fund(code, name, m["AMC"] ?: "", m["CATEGORY"] ?: "", m["SUBCAT"] ?: "", nav, m["NAVDATE"] ?: "", d(m, "AUM_CR"),
                d(m, "R1M"), d(m, "R3M"), d(m, "R6M"), d(m, "R1Y"), d(m, "R3Y"), d(m, "R5Y"), d(m, "R10Y"), d(m, "SINCE"),
                d(m, "VOL3Y"), d(m, "MDD3Y"), d(m, "SHARPE3Y"), d(m, "SORTINO3Y"), d(m, "ROLL3Y_MED"), d(m, "ROLL3Y_MIN"), d(m, "ROLL3Y_BEAT"), d(m, "AGE_Y"))
        }
        val h = HashMap<String, ArrayList<FundHolding>>()
        for (m in rows(holdCsv)) {
            val code = m["CODE"] ?: continue
            val p = d(m, "PCT")
            val st = m["STOCK"] ?: continue
            if (code.isEmpty() || st.isEmpty() || !(p > 0)) continue
            h.getOrPut(code) { ArrayList() }.add(FundHolding(code, m["ASOF"] ?: "", st, m["ISIN"] ?: "", p))
        }
        for (l in h.values) l.sortByDescending { it.pct }
        val s = ArrayList<FundStock>()
        for (m in rows(stocksCsv)) {
            val st = m["STOCK"] ?: continue
            val n = d(m, "FUNDS")
            if (st.isEmpty() || !(n > 0)) continue
            s.add(FundStock(st, m["ISIN"] ?: "", n.toInt(), d(m, "AVGPCT"), d(m, "ADDED").let { if (it.isNaN()) -1 else it.toInt() }))
        }
        return FundData(f, h, s)
    }

    // ------------------------------------------------------------------ scoring

    private fun pctRanks(xs: List<Fund>, g: (Fund) -> Double): Map<String, Double> {
        val ok = xs.filter { !g(it).isNaN() }.sortedWith(compareBy<Fund>({ g(it) }, { it.code }))
        val out = HashMap<String, Double>()
        if (ok.size < 3) return out
        for ((i, f) in ok.withIndex()) out[f.code] = 100.0 * (i + 0.5) / ok.size
        return out
    }

    /** Long-term score 0..100 inside the fund's sub-category; NaN when there is too little history. */
    fun scores(data: FundData): Map<String, Double> {
        val out = HashMap<String, Double>()
        for ((_, list) in data.funds.values.groupBy { it.sub }) {
            val elig = list.filter { it.age >= 3.0 }
            if (elig.size < 3) continue
            val cons = pctRanks(elig) { it.rollBeat }
            val ret = pctRanks(elig) { if (!it.r5y.isNaN()) it.r5y else it.r3y }
            val sh = pctRanks(elig) { it.sharpe }
            val dd = pctRanks(elig) { it.mdd }       // less negative ranks higher
            for (f in elig) {
                val parts = ArrayList<Double>()
                cons[f.code]?.let { parts.add(it); parts.add(it) }
                ret[f.code]?.let { parts.add(it) }
                sh[f.code]?.let { parts.add(it) }
                dd[f.code]?.let { parts.add(it) }
                if (parts.size >= 3) out[f.code] = Math.rint(parts.average())
            }
        }
        return out
    }

    private fun nz(x: Double) = if (x.isNaN() || x.isInfinite()) null else Math.rint(x * 100) / 100

    fun fundMap(f: Fund, score: Double?, rank: Int = 0, of: Int = 0): Map<String, Any?> = linkedMapOf(
        "code" to f.code, "name" to f.name, "amc" to f.amc, "cat" to f.cat, "sub" to f.sub, "nav" to f.nav, "navDate" to f.navDate,
        "aum" to nz(f.aum), "r1m" to nz(f.r1m), "r3m" to nz(f.r3m), "r6m" to nz(f.r6m), "r1y" to nz(f.r1y), "r3y" to nz(f.r3y), "r5y" to nz(f.r5y),
        "r10y" to nz(f.r10y), "since" to nz(f.since), "vol" to nz(f.vol), "mdd" to nz(f.mdd), "sharpe" to nz(f.sharpe), "sortino" to nz(f.sortino),
        "rollMed" to nz(f.rollMed), "rollMin" to nz(f.rollMin), "rollBeat" to nz(f.rollBeat), "age" to nz(f.age),
        "score" to (score ?: -1.0), "rank" to rank, "of" to of
    )

    /** The Funds tab: long-term picks per key category, category leaders by 1-year return, what funds hold most. */
    fun view(data: FundData): Map<String, Any?> {
        if (!data.ok) return linkedMapOf("ok" to true, "ready" to false)
        val sc = scores(data)
        val bySub = data.funds.values.groupBy { it.sub }
        val subs = KEY.filter { bySub.containsKey(it) } + bySub.keys.filter { it !in KEY && (bySub[it]?.size ?: 0) >= 5 && (bySub[it]?.first()?.cat == "Equity" || bySub[it]?.first()?.cat == "Hybrid") }.sorted()
        val cats = subs.map { sub ->
            val list = bySub[sub] ?: emptyList()
            val ranked = list.filter { sc[it.code] != null && it.age >= MIN_AGE_LONG }.sortedWith(compareByDescending<Fund> { sc[it.code] }.thenByDescending { it.rollBeat }.thenBy { it.code })
                .ifEmpty { list.filter { sc[it.code] != null }.sortedWith(compareByDescending<Fund> { sc[it.code] }.thenBy { it.code }) }
            val r1 = list.map { it.r1y }.filter { !it.isNaN() }.sorted()
            linkedMapOf(
                "sub" to sub, "cat" to (list.firstOrNull()?.cat ?: ""), "n" to list.size,
                "medR1y" to (if (r1.isEmpty()) null else nz(r1[r1.size / 2])),
                "medR3y" to list.map { it.r3y }.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) null else nz(it[it.size / 2]) },
                "best" to ranked.take(3).mapIndexed { i, f -> fundMap(f, sc[f.code], i + 1, ranked.size) }
            )
        }
        val hot = data.funds.values.filter { (it.cat == "Equity" || it.cat == "Hybrid") && !it.r1y.isNaN() && it.age >= 1.0 }
            .sortedWith(compareByDescending<Fund> { it.r1y }.thenBy { it.code }).take(15).map { fundMap(it, sc[it.code]) }
        val stocks = data.stocks.sortedWith(compareByDescending<FundStock> { it.funds }.thenBy { it.stock }).take(25)
            .map { linkedMapOf("stock" to it.stock, "funds" to it.funds, "avgPct" to nz(it.avgPct), "added" to it.added) }
        val adds = data.stocks.filter { it.added > 0 }.sortedWith(compareByDescending<FundStock> { it.added }.thenBy { it.stock }).take(20)
            .map { linkedMapOf("stock" to it.stock, "funds" to it.funds, "avgPct" to nz(it.avgPct), "added" to it.added) }
        return linkedMapOf(
            "ok" to true, "ready" to true, "asof" to data.asof, "count" to data.funds.size, "categories" to cats, "hot" to hot,
            "fundStocks" to stocks, "fundAdds" to adds, "hasHoldings" to data.holdings.isNotEmpty(), "notes" to FundTexts.NOTES
        )
    }

    fun info(data: FundData, code: String): Map<String, Any?> {
        val f = data.funds[code] ?: return linkedMapOf("ok" to false, "error" to "Fund not found")
        val sc = scores(data)
        val peers = data.funds.values.filter { it.sub == f.sub && sc[it.code] != null }.sortedWith(compareByDescending<Fund> { sc[it.code] }.thenBy { it.code })
        val rank = peers.indexOfFirst { it.code == code } + 1
        val m = LinkedHashMap(fundMap(f, sc[code], rank, peers.size))
        m["ok"] = true
        val hs = data.holdings[code] ?: emptyList()
        m["holdings"] = hs.take(15).map { linkedMapOf("stock" to it.stock, "pct" to nz(it.pct)) }
        m["holdAsof"] = hs.firstOrNull()?.asof ?: ""
        m["top10"] = nz(hs.take(10).sumOf { it.pct })
        m["peers"] = peers.take(5).mapIndexed { i, p -> fundMap(p, sc[p.code], i + 1, peers.size) }
        m["verdict"] = FundTexts.verdict(f, sc[code], rank, peers.size)
        return m
    }

    fun search(data: FundData, q: String): List<Map<String, Any?>> {
        val words = q.lowercase().split(Regex("[^a-z0-9&]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val sc = scores(data)
        return data.funds.values.filter { f -> val n = (f.name + " " + f.amc).lowercase(); words.all { n.contains(it) } }
            .sortedWith(compareByDescending<Fund> { sc[it.code] ?: -1.0 }.thenBy { it.name }).take(30).map { fundMap(it, sc[it.code]) }
    }
}

object FundTexts {
    val NOTES = listOf(
        "Ranks compare a fund only with others in its own category. The long-term score weighs consistency most: how often the fund beat the category median across every 3-year window of its history, then 5-year return, risk-adjusted return (Sharpe) and the worst fall.",
        "Past returns alone are a weak guide: last year's top fund is rarely next year's. Consistent funds with smaller falls have held up better over long periods. All funds shown are direct-growth plans; expense ratios are not compared yet.",
        "Not investment advice. For 5+ year goals, one flexi-cap or index fund plus SIPs is usually enough; add mid/small caps only if you can sit through 30–50% falls."
    )

    fun verdict(f: Fund, score: Double?, rank: Int, of: Int): String {
        if (score == null) return "Too little history to rank (under 3 years)."
        val pos = if (of > 0) "#$rank of $of in ${f.sub}" else f.sub
        val c = f.rollBeat
        val head = when {
            score >= 80 && f.age >= Funds.MIN_AGE_LONG -> "Strong long-term pick ($pos)."
            score >= 60 -> "Solid, above its category ($pos)."
            score >= 40 -> "Middle of its category ($pos)."
            else -> "Below most of its category ($pos)."
        }
        val cons = if (!c.isNaN()) " Beat its category in ${Math.round(c)}% of 3-year windows." else ""
        val dd = if (!f.mdd.isNaN()) " Worst fall in 3 years: ${Math.round(f.mdd)}%." else ""
        return head + cons + dd
    }
}
