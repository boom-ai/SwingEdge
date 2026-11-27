package com.boomai.swingedge.core

/**
 * Whole-market scan (NSE + BSE, every listed equity). The daily job (scan/ScanMain.kt, .github/workflows/scan.yml) turns
 * the exchanges' bhavcopies into split-adjusted daily series ([MarketHistory]), scores them with the same code the phone
 * uses ([SetupScore], [MarketGate], [Clenow], [SetupScore.levels]) and writes data/scan.csv ([MarketScan.encode]). The
 * phone downloads that file, rebuilds the very same score table from it ([MarketScanData.table]) and merges it with its
 * own live scan of the bundled universe, holdings and watchlist (SwingApp.buildScores).
 */

/** One stock's adjusted history. [code] = BSE scrip code (also set for an NSE stock back-filled from BSE). */
class MarketStock(
    val sym: String, val exch: String, val code: String, val isin: String, val series: String, val name: String,
    val s: Series,
    /** corporate-action adjustments applied (splits, bonuses, rights) */
    val adjustments: Int
)

object MarketHistory {
    /** Sessions kept: R12_1 needs 253 bars; a little more for safety. */
    const val SESSIONS = 275
    /** prevClose / last close outside 1 +- this = a corporate action on that ex-date (dividends are not adjusted). */
    const val ADJ_MIN = 0.01
    /** A stock must have traded within the last this many sessions to be listed. */
    const val ACTIVE_SESSIONS = 10
    private val SYM_RE = Regex("^[A-Z0-9&][A-Z0-9&\\-]*$")

    private class Raw(val day: Int, val r: BhavRow)

    /** The preferred file of each exchange and day (UDiFF beats the older formats; then more rows). */
    private fun pick(days: List<BhavDay>, exch: String): Map<Int, BhavDay> {
        val out = HashMap<Int, BhavDay>()
        for (b in days) {
            if (b.exch != exch || b.day <= 0) continue
            val cur = out[b.day]
            if (cur == null || (b.format == "udiff" && cur.format != "udiff") || (b.format == cur.format && b.rows.size > cur.rows.size)) out[b.day] = b
        }
        return out
    }

    /**
     * Builds the adjusted series of every active stock from parsed daily files (any order, duplicates allowed).
     * Sessions are the days NSE published a file (a BSE file of a day without an NSE file is ignored). A stock listed on
     * both exchanges (same latest ISIN) is kept once, as its NSE symbol; the BSE bars from before its NSE listing are
     * prepended. A BSE-only stock gets its BSE ticker as symbol, or its scrip code when that ticker is not a valid app
     * symbol or is already an NSE symbol ([nseKnown] = every NSE symbol the app knows, e.g. data/symbols.csv).
     */
    fun build(days: List<BhavDay>, nseNames: Map<String, String> = emptyMap(), nseKnown: Set<String> = emptySet(), sessions: Int = SESSIONS): List<MarketStock> {
        val nseBy = pick(days, Bhav.NSE)
        if (nseBy.isEmpty()) return emptyList()
        val sess = nseBy.keys.sorted().takeLast(sessions)
        val sessSet = sess.toHashSet()
        val bseBy = pick(days, Bhav.BSE).filterKeys { it in sessSet }
        val activeFrom = sess[maxOf(0, sess.size - ACTIVE_SESSIONS)]

        val nse = HashMap<String, ArrayList<Raw>>()
        for (d in sess) {
            val best = HashMap<String, BhavRow>()
            for (r in nseBy[d]!!.rows) {
                if (r.sym.isEmpty()) continue
                val cur = best[r.sym]
                if (cur == null || Bhav.NSE_SERIES.indexOf(r.series) < Bhav.NSE_SERIES.indexOf(cur.series)) best[r.sym] = r
            }
            for ((sym, r) in best) nse.getOrPut(sym) { ArrayList() }.add(Raw(d, r))
        }
        val bse = HashMap<String, ArrayList<Raw>>()
        for (d in bseBy.keys.sorted()) {
            val seen = HashSet<String>()
            for (r in bseBy[d]!!.rows) {
                val key = r.code.ifEmpty { r.isin }
                if (key.isEmpty() || !seen.add(key)) continue
                bse.getOrPut(key) { ArrayList() }.add(Raw(d, r))
            }
        }
        // latest ISIN of every active NSE stock -> its symbol
        val nseByIsin = HashMap<String, String>()
        for ((sym, rows) in nse) {
            if (rows.last().day < activeFrom) continue
            val isin = rows.lastOrNull { it.r.isin.isNotEmpty() }?.r?.isin ?: continue
            nseByIsin[isin] = sym
        }
        val out = ArrayList<MarketStock>()
        val backfill = HashMap<String, Pair<String, ArrayList<Raw>>>()
        val bseOnly = ArrayList<Pair<String, ArrayList<Raw>>>()
        for ((code, rows) in bse) {
            val to = nseByIsin[rows.last().r.isin]
            if (to != null) { if (!backfill.containsKey(to) || rows.size > backfill[to]!!.second.size) backfill[to] = Pair(code, rows) }
            else bseOnly.add(Pair(code, rows))
        }
        for ((sym, rows0) in nse) {
            if (rows0.last().day < activeFrom) continue
            var rows: List<Raw> = rows0
            var junction = -1
            var bfCode = ""
            val bf = backfill[sym]
            if (bf != null) {
                val first = rows0[0].day
                val before = bf.second.filter { it.day < first }
                if (before.isNotEmpty()) { junction = before.size; rows = before + rows0; bfCode = bf.first }
            }
            val last = rows0.last().r
            val isin = rows0.lastOrNull { it.r.isin.isNotEmpty() }?.r?.isin ?: ""
            val (s, adj) = series(sym, rows, junction)
            out.add(MarketStock(sym, Bhav.NSE, bfCode, isin, last.series, nseNames[sym]?.takeIf { it.isNotBlank() } ?: last.name.ifEmpty { sym }, s, adj))
        }
        val taken = HashSet<String>(nse.keys); taken.addAll(nseKnown)
        bseOnly.sortBy { it.first }
        for ((code, rows) in bseOnly) {
            if (rows.last().day < activeFrom) continue
            val last = rows.last().r
            val id = last.sym.trim().uppercase()
            val sym = if (id.isNotEmpty() && id.length <= 25 && SYM_RE.matches(id) && !taken.contains(id)) id else code
            if (sym.isEmpty() || !taken.add(sym)) continue
            val (s, adj) = series(sym, rows, -1)
            out.add(MarketStock(sym, Bhav.BSE, code, last.isin, last.series, last.name.ifEmpty { sym }, s, adj))
        }
        out.sortBy { it.sym }
        return out
    }

    /** Bars oldest first, back-adjusted on every ex-date the exchange's previous close reveals (not across [junction]). */
    private fun series(sym: String, rows: List<Raw>, junction: Int): Pair<Series, Int> {
        val n = rows.size
        val o = DoubleArray(n); val h = DoubleArray(n); val l = DoubleArray(n); val c = DoubleArray(n); val v = DoubleArray(n)
        for ((k, x) in rows.withIndex()) { o[k] = x.r.o; h[k] = x.r.h; l[k] = x.r.l; c[k] = x.r.c; v[k] = x.r.vol }
        var adj = 0
        for (k in 1 until n) {
            if (k == junction) continue
            val pv = rows[k].r.prev; val lc = c[k - 1]
            if (!(pv > 0) || !(lc > 0)) continue
            val f = pv / lc
            if (Math.abs(f - 1) <= ADJ_MIN || f < 0.02 || f > 50) continue
            for (j in 0 until k) { o[j] *= f; h[j] *= f; l[j] *= f; c[j] *= f; v[j] /= f }
            adj++
        }
        val s = Series(sym, maxOf(1, n))
        for (k in 0 until n) s.add(rows[k].day, o[k], h[k], l[k], c[k], v[k])
        return Pair(s, adj)
    }
}

/** One stock of the whole-market scan. [inTable] = part of the cross-section the score table is built from. */
class ScanRow(
    val sym: String, val exch: String, val code: String, val isin: String, val name: String, val series: String, val sector: String,
    val inTable: Boolean, val comp: ScoreComp, val clenow: Double,
    /** as the job scored it (the phone re-derives them from [MarketScanData.table]) */
    val score: Double, val label: String, val rank: Int,
    /** last closes (oldest first), only for high-scoring rows; empty otherwise */
    val spark: DoubleArray
)

/** A parsed (or freshly built) whole-market scan. [day] = epoch day of the cross-section; [gate] = market gate (null = unknown). */
class MarketScanData(val day: Int, val builtAt: Long, val gate: Boolean?, val rows: List<ScanRow>) {
    val bySym: Map<String, ScanRow> = HashMap<String, ScanRow>(rows.size * 2).also { m -> for (r in rows) m[r.sym] = r }
    val nseCount: Int = rows.count { it.exch == Bhav.NSE }
    val bseCount: Int = rows.count { it.exch == Bhav.BSE }
    /** The score table of the cross-section, rebuilt exactly as the job built it. */
    val table: ScoreTable by lazy { SetupScore.buildTable(rows.filter { it.inTable }.map { Pair(it.sym, it.comp) }, builtAt) }
    fun items(): List<Pair<String, ScoreComp>> = rows.filter { it.inTable }.map { Pair(it.sym, it.comp) }
}

object MarketScan {
    const val FILE = "scan.csv"
    const val URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/scan.csv"
    const val MAGIC = "#SWINGEDGE_SCAN"
    const val VERSION = 1
    /** A download must have at least this many rows to replace the saved copy. */
    const val MIN_ROWS = 500
    /** Rows at or above this score carry a spark (any of them may become a BUY in the phone's merged table). */
    const val SPARK_MIN_SCORE = 75.0
    const val SPARK_N = 30

    val COLS = listOf("SYMBOL", "EXCH", "CODE", "ISIN", "NAME", "SERIES", "SECTOR", "ASOF", "INT", "NBARS", "CLOSE", "PREV", "HIGH", "RAW",
        "TO20", "SMA50", "SMA200", "ATR14", "R6", "R12", "VOL126", "RAM", "HI252", "LO252", "PROX52", "LOW52", "UC", "CLENOW",
        "SCORE", "LABEL", "RANK", "ENTRY_LOW", "ENTRY_HIGH", "STOP", "T1", "T2", "SPARK")

    /**
     * Ideas order (REPORT9, shared by the phone and the job): the BUY entries of [t] (best scores first, at most 4 x [max]),
     * then ordered by Clenow trend quality (unscorable last), score, symbol; the first [max].
     */
    fun ideaOrder(t: ScoreTable, clenow: (String) -> Double, max: Int): List<ScoreEntry> {
        val cands = SetupScore.topBuys(t, max * 4)
        return cands.sortedWith(compareByDescending<ScoreEntry> { clenow(it.sym).let { c -> if (c.isNaN()) -1e9 else c } }.thenByDescending { it.score }.thenBy { it.sym })
            .take(max)
    }

    /**
     * Scores the whole market like SwingApp.buildScores does: the cross-section is every stock whose latest bar is on the
     * most common latest day; the gate is the equal-weight index of the liquid stocks of that cross-section.
     */
    fun score(stocks: List<MarketStock>, sectors: Map<String, String>, builtAt: Long): MarketScanData {
        val have = stocks.filter { it.s.n > 0 }
        if (have.isEmpty()) return MarketScanData(0, builtAt, null, emptyList())
        val count = HashMap<Int, Int>()
        for (x in have) count[x.s.lastDay()] = (count[x.s.lastDay()] ?: 0) + 1
        val ref = count.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).key
        val comps = HashMap<String, ScoreComp>()
        for (x in have) comps[x.sym] = SetupScore.compute(x.s)
        val inT = have.filter { it.s.lastDay() >= ref }
        val t = SetupScore.buildTable(inT.map { Pair(it.sym, comps[it.sym]!!) }, builtAt)
        val gate = MarketGate.open(inT.filter { comps[it.sym]!!.liquid }.map { it.s })
        val inSet = inT.map { it.sym }.toHashSet()
        val rows = have.map { x ->
            val c = comps[x.sym]!!
            val e = if (inSet.contains(x.sym)) t.entries[x.sym] else null
            val sc = if (e != null && e.rated) e.score else 0.0
            val spark = if (e != null && e.rated && sc >= SPARK_MIN_SCORE) DoubleArray(minOf(SPARK_N, x.s.n)) { k -> Fmt.r2(x.s.c[x.s.n - minOf(SPARK_N, x.s.n) + k]) } else DoubleArray(0)
            ScanRow(x.sym, x.exch, x.code, x.isin, x.name, x.series, sectors[x.sym] ?: "", inSet.contains(x.sym), c, Clenow.score(x.s),
                sc, e?.label ?: "NA", e?.rank ?: 0, spark)
        }
        return MarketScanData(ref, builtAt, gate, rows)
    }

    // ------------------------------------------------------------------ file format

    private fun exact(x: Double): String = if (x.isNaN() || x.isInfinite()) "" else (x + 0.0).toString()
    private fun short(x: Double): String {
        if (x.isNaN() || x.isInfinite()) return ""
        if (x == 0.0) return "0"
        return java.math.BigDecimal(x).round(java.math.MathContext(7)).stripTrailingZeros().toPlainString()
    }
    private fun q(s: String): String = if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\n", " ").replace("\"", "\"\"") + "\"" else s

    /** CSV text of a scan. Component inputs of cross-section members are written exactly (the phone's table is bit-identical). */
    fun encode(d: MarketScanData): String {
        val sb = StringBuilder(d.rows.size * 240 + 200)
        sb.append(MAGIC).append(",v=").append(VERSION).append(",asof=").append(if (d.day > 0) java.time.LocalDate.ofEpochDay(d.day.toLong()).toString() else "")
            .append(",builtAt=").append(d.builtAt).append(",gate=").append(when (d.gate) { true -> "1"; false -> "0"; null -> "" })
            .append(",rows=").append(d.rows.size).append(",nse=").append(d.nseCount).append(",bse=").append(d.bseCount).append('\n')
        sb.append(COLS.joinToString(",")).append('\n')
        for (r in d.rows) {
            val c = r.comp
            val ex = c.inUniverse || c.lowPriceRateable
            val f: (Double) -> String = if (ex) ::exact else ::short
            val lv = if (c.atr14 > 0 && c.close > 0) SetupScore.levels(c.close, c.atr14) else null
            val cells = listOf(r.sym, r.exch, r.code, r.isin, q(r.name), r.series, q(r.sector), if (c.day > 0) StockPage.yyyymmdd(c.day).toString() else "",
                if (r.inTable) "1" else "0", c.nbars.toString(), f(c.close), f(c.prevClose), f(c.high), f(c.rawClose),
                f(c.to20), f(c.sma50), f(c.sma200), f(c.atr14), f(c.r6), f(c.r12), f(c.vol126), f(c.ram), f(c.hi252), f(c.lo252), f(c.prox52), f(c.low52),
                if (c.uclock) "1" else "0", short(r.clenow), if (r.label == "NA") "" else r.score.toString(), r.label, r.rank.toString(),
                if (lv != null) Fmt.r2(c.close).toString() else "", lv?.let { Fmt.r2(it[0]).toString() } ?: "", lv?.let { Fmt.r2(it[1]).toString() } ?: "",
                lv?.let { Fmt.r2(it[2]).toString() } ?: "", lv?.let { Fmt.r2(it[3]).toString() } ?: "", r.spark.joinToString(" ") { it.toString() })
            sb.append(cells.joinToString(",")).append('\n')
        }
        return sb.toString()
    }

    /** scan_meta.json content. */
    fun meta(d: MarketScanData, sessions: Int, adjusted: Int): Map<String, Any?> {
        val t = d.table
        return linkedMapOf("asof" to (if (d.day > 0) java.time.LocalDate.ofEpochDay(d.day.toLong()).toString() else ""), "builtAt" to d.builtAt,
            "rows" to d.rows.size, "nse" to d.nseCount, "bse" to d.bseCount, "inTable" to d.rows.count { it.inTable },
            "liquid" to d.rows.count { it.comp.liquid }, "rated" to t.rated, "buy" to t.ranked.count { it.label == "BUY" },
            "gate" to d.gate, "sessions" to sessions, "adjustedStocks" to adjusted, "version" to VERSION)
    }

    private fun d(s: String?): Double { val t = s?.trim() ?: return Double.NaN; if (t.isEmpty()) return Double.NaN; return t.toDoubleOrNull() ?: Double.NaN }

    /** Parses a scan file; null when it is not one (wrong magic / header, no usable row, fewer than [minRows] rows). Never throws. */
    fun parse(text: String?, minRows: Int = 1): MarketScanData? {
        try {
            if (text == null || !text.startsWith(MAGIC)) return null
            val lines = text.split('\n')
            if (lines.size < 2) return null
            val meta = HashMap<String, String>()
            for (kv in lines[0].trimEnd('\r').split(',').drop(1)) { val k = kv.indexOf('='); if (k > 0) meta[kv.substring(0, k)] = kv.substring(k + 1) }
            if ((meta["v"]?.toIntOrNull() ?: 0) != VERSION) return null
            val day = Bhav.parseDay(meta["asof"])
            if (day <= 0) return null
            val hdr = StockList.splitCsv(lines[1].trimEnd('\r'))
            val ix = HashMap<String, Int>(); for ((k, h) in hdr.withIndex()) ix[h.trim()] = k
            if (!COLS.all { ix.containsKey(it) }) return null
            val rows = ArrayList<ScanRow>(lines.size)
            for (k in 2 until lines.size) {
                val line = lines[k].trimEnd('\r'); if (line.isBlank()) continue
                val r = StockList.splitCsv(line)
                fun s(c: String) = r.getOrNull(ix[c]!!)?.trim() ?: ""
                fun x(c: String) = d(r.getOrNull(ix[c]!!))
                val sym = s("SYMBOL").uppercase()
                if (sym.isEmpty() || sym.length > 25) continue
                val asof = s("ASOF")
                val cday = if (asof.isEmpty()) 0 else Bhav.parseDay(asof)
                val comp = ScoreComp(s("NBARS").toIntOrNull() ?: 0, cday, x("CLOSE").let { if (it.isNaN()) 0.0 else it }, x("PREV").let { if (it.isNaN()) 0.0 else it },
                    x("HIGH").let { if (it.isNaN()) 0.0 else it }, x("RAW").let { if (it.isNaN()) 0.0 else it }, x("TO20"), x("SMA50"), x("ATR14"),
                    x("R6"), x("R12"), x("VOL126"), x("RAM"), x("HI252"), x("LO252"), x("PROX52"), x("LOW52"), s("UC") == "1", sma200 = x("SMA200"))
                val sp = s("SPARK").split(' ').mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
                rows.add(ScanRow(sym, s("EXCH").ifEmpty { Bhav.NSE }, s("CODE"), s("ISIN"), s("NAME").ifEmpty { sym }, s("SERIES"), s("SECTOR"),
                    s("INT") == "1", comp, x("CLENOW"), x("SCORE").let { if (it.isNaN()) 0.0 else it }, s("LABEL").ifEmpty { "NA" }, s("RANK").toIntOrNull() ?: 0, sp))
            }
            if (rows.isEmpty() || rows.size < minRows) return null
            val gate = when (meta["gate"]) { "1" -> true; "0" -> false; else -> null }
            return MarketScanData(day, meta["builtAt"]?.toLongOrNull() ?: 0L, gate, rows)
        } catch (t: Throwable) { return null }
    }
}
