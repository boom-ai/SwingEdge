package com.boomai.swingedge.scan

import com.boomai.swingedge.core.Bhav
import com.boomai.swingedge.core.BhavDay
import com.boomai.swingedge.core.Json
import com.boomai.swingedge.core.AdjEvent
import com.boomai.swingedge.core.MarketHistory
import com.boomai.swingedge.core.MarketStock
import com.boomai.swingedge.core.MarketScan
import com.boomai.swingedge.core.StockList
import com.boomai.swingedge.core.Holidays
import com.boomai.swingedge.core.NightCalls
import com.boomai.swingedge.core.FactorModel
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

/**
 * Batch entry point of the whole-market scan (run by .github/workflows/scan.yml; not part of the app).
 *
 * usage: ScanMain BHAV_DIR SYMBOLS_CSV INDUSTRIES_CSV OUT_SCAN_CSV OUT_META_JSON [SESSIONS]
 *   BHAV_DIR holds the daily files tools/bhavsync.py keeps (nse_YYYYMMDD.csv.gz, nsefull_YYYYMMDD.csv.gz, bse_YYYYMMDD.csv.gz,
 *   plain .csv or .zip also read) and optionally EQUITY_L.csv (ISINs for the older NSE format).
 * Exit 0 = written; 2 = not written (too little data, or older than the file already there — the last good file is kept).
 */
fun main(args: Array<String>) {
    if (args.size < 5) { System.err.println("usage: ScanMain BHAV_DIR SYMBOLS_CSV INDUSTRIES_CSV OUT_SCAN_CSV OUT_META_JSON [SESSIONS]"); exitProcess(1) }
    val t0 = System.currentTimeMillis()
    val dir = File(args[0])
    val sessions = args.getOrNull(5)?.toIntOrNull() ?: MarketHistory.SESSIONS
    val isin = Bhav.isinMap(File(dir, "EQUITY_L.csv").takeIf { it.exists() }?.readText())
    val days = ArrayList<BhavDay>()
    var bad = 0
    val files = (dir.listFiles() ?: emptyArray()).filter { it.isFile && Regex("^(nse|nsefull|bse)_\\d{8}\\.(csv|csv\\.gz|zip)$").matches(it.name) }.sortedBy { it.name }
    for (f in files) {
        val text = try { read(f) } catch (t: Throwable) { null }
        val b = Bhav.parse(text, isin)
        if (b == null) { bad++; System.err.println("unreadable: ${f.name}"); continue }
        val fileDay = Bhav.parseDay(Regex("\\d{8}").find(f.name)?.value)
        days.add(if (b.day > 0) b else BhavDay(fileDay, b.exch, b.rows, b.format))
        if (b.day > 0 && fileDay > 0 && b.day != fileDay) System.err.println("note: ${f.name} carries date ${b.day} (file name $fileDay)")
    }
    val nseDays = days.filter { it.exch == Bhav.NSE }.map { it.day }.toSet()
    val bseDays = days.filter { it.exch == Bhav.BSE }.map { it.day }.toSet()
    println("files: ${files.size} read, $bad unreadable; NSE sessions ${nseDays.size}, BSE sessions ${bseDays.size}")
    val list = try { StockList.parse(File(args[1]).takeIf { it.exists() }?.readText()).rows } catch (t: Throwable) { emptyList() }
    val names = HashMap<String, String>(); for (r in list) if (r.name.isNotBlank()) names[r.sym] = r.name
    val sectors = HashMap<String, String>()
    try {
        val lines = File(args[2]).takeIf { it.exists() }?.readLines() ?: emptyList()
        if (lines.isNotEmpty()) {
            val h = StockList.splitCsv(lines[0]).map { it.trim().uppercase() }
            val si = h.indexOf("SYMBOL"); val ci = h.indexOf("SECTOR")
            if (si >= 0 && ci >= 0) for (l in lines.drop(1)) { val r = StockList.splitCsv(l); val s = r.getOrNull(si)?.trim()?.uppercase() ?: continue; val v = r.getOrNull(ci)?.trim() ?: ""; if (v.isNotEmpty()) sectors[s] = v }
        }
    } catch (t: Throwable) { System.err.println("industries not read: $t") }

    val stocks = MarketHistory.build(days, names, list.map { it.sym }.toHashSet(), sessions)
    val data = MarketScan.score(stocks, sectors, System.currentTimeMillis())
    val used = minOf(nseDays.size, sessions)
    val meta = MarketScan.meta(data, used, stocks.count { it.adjustments > 0 })
    println("stocks: ${data.rows.size} (NSE ${data.nseCount}, BSE-only ${data.bseCount}); as of ${meta["asof"]}; rated ${meta["rated"]}, BUY ${meta["buy"]}, gate ${data.gate}; adjusted ${meta["adjustedStocks"]}; ${System.currentTimeMillis() - t0} ms")

    diagnostics(stocks)

    val out = File(args[3])
    try { nightCalls(stocks, dir, out.absoluteFile.parentFile) } catch (t: Throwable) { System.err.println("intraday calls failed: $t"); t.printStackTrace() }
    try { rating(stocks, sectors, out.absoluteFile.parentFile) } catch (t: Throwable) { System.err.println("rating failed: $t"); t.printStackTrace() }
    val old = if (out.exists()) MarketScan.parse(out.readText()) else null
    when {
        data.rows.size < MIN_WRITE_ROWS -> { println("NOT written: only ${data.rows.size} stocks (need $MIN_WRITE_ROWS)"); exitProcess(2) }
        used < MIN_WRITE_SESSIONS -> { println("NOT written: only $used NSE sessions (need $MIN_WRITE_SESSIONS for 12-month scores)"); exitProcess(2) }
        old != null && old.day > data.day -> { println("NOT written: existing file is newer (${old.day} > ${data.day})"); exitProcess(2) }
        old != null && old.day == data.day && data.rows.size < old.rows.size * 8 / 10 -> { println("NOT written: same day as the existing file but ${data.rows.size} stocks vs ${old.rows.size}"); exitProcess(2) }
    }
    val text = MarketScan.encode(data)
    if (MarketScan.parse(text, MarketScan.MIN_ROWS) == null) { println("NOT written: encoded file does not parse back"); exitProcess(2) }
    File(out.path + ".tmp").writeText(text); File(out.path + ".tmp").renameTo(out)
    File(args[4]).writeText(Json.stringify(meta) + "\n")
    println("written ${out.path} (${text.length / 1024} KB)")
}

private const val MIN_WRITE_ROWS = 1000

/**
 * nightly-calls: next-session intraday setups + swing entries with their back-test record -> data/intraday.csv.
 * Reads data/holidays.json (written by the workflow's holiday step, committed fallback otherwise), data/fo_lots.csv and
 * BHAV_DIR/ind_close_all.csv (NSE index closes of the last session, for Nifty / Bank Nifty levels; optional).
 */
private fun nightCalls(stocks: List<MarketStock>, bhavDir: File, dataDir: File) {
    val t0 = System.currentTimeMillis()
    File(dataDir, "holidays.json").takeIf { it.exists() }?.let { Holidays.install(it.readText()) }
    val fno = NightCalls.fnoSymbols(File(dataDir, "fo_lots.csv").takeIf { it.exists() }?.readText())
    val idx = NightCalls.indexLevels(File(bhavDir, "ind_close_all.csv").takeIf { it.exists() }?.readText())
    val d = NightCalls.build(stocks, fno, idx, Holidays.all(), System.currentTimeMillis())
    println("== intraday calls for ${java.time.LocalDate.ofEpochDay(d.forDay.toLong())} (data as of ${java.time.LocalDate.ofEpochDay(d.asof.toLong())}); ${d.stocks} stocks, ${d.sessions} sessions, F&O ${fno.size}, index levels ${idx.map { it.name }}; ${System.currentTimeMillis() - t0} ms")
    for (r in d.rules) println("   %-8s %-8s trades %5d  win %5.1f%%  avgR %+.3f  worst-case avgR %+.3f  totalR %+.1f  %s".format(r.id, r.kind, r.trades, r.winPct, r.avgR, r.avgWorst, r.sumR, r.status))
    for ((k, x) in d.setups.withIndex()) println("   %2d %-12s %-8s %-6s %s".format(k + 1, x.sym, x.rule, x.side, x.cond))
    if (d.asof <= 0) { println("intraday.csv NOT written: no data"); return }
    val f = File(dataDir, NightCalls.FILE)
    val old = if (f.exists()) NightCalls.parse(f.readText()) else null
    if (old != null && old.asof > d.asof) { println("intraday.csv NOT written: existing file is newer"); return }
    val text = NightCalls.encode(d)
    if (NightCalls.parse(text) == null) { println("intraday.csv NOT written: does not parse back"); return }
    File(f.path + ".tmp").writeText(text); File(f.path + ".tmp").renameTo(f)
    println("written ${f.path} (${d.setups.size} setups)")
}

/** Multi-factor rating of every liquid NSE stock, walk-forward tested -> data/rating.csv */
private fun rating(stocks: List<MarketStock>, sectors: Map<String, String>, dataDir: File) {
    val t0 = System.currentTimeMillis()
    val fno = NightCalls.fnoSymbols(File(dataDir, "fo_lots.csv").takeIf { it.exists() }?.readText())
    val r = FactorModel.build(stocks, sectors, fno)
    println("== factor rating: ${r.universe} stocks rated, ${r.sessions} sessions, as of ${if (r.asof > 0) java.time.LocalDate.ofEpochDay(r.asof.toLong()) else "-"}; ${System.currentTimeMillis() - t0} ms")
    for (s in r.stats) println("   %-8s IC %+.4f  ICIR %+.3f  t %+.2f  weight %+.3f  (%d days)  %s".format(s.f.id, s.mean, s.icir, s.tstat, s.weight, s.ics.size, s.f.name))
    println("   walk-forward: %d test days; top 10%% %+.2f%% vs all %+.2f%% per %d sessions (spread %+.2f%%), top beat all on %.0f%% of days, composite IC %+.4f"
        .format(r.oosDays, r.oosTopRet * 100, r.oosAllRet * 100, FactorModel.H, r.oosSpread * 100, r.oosHit, r.oosIc))
    for (x in r.rated.take(15)) println("   %3d %-12s rating %3d  %s".format(x.rank, x.sym, x.rating, x.sector))
    if (r.rated.size < 200) { println("rating.csv NOT written: only ${r.rated.size} stocks"); return }
    val text = FactorModel.encode(r, System.currentTimeMillis())
    if (FactorModel.parse(text) == null) { println("rating.csv NOT written: does not parse back"); return }
    val f = File(dataDir, FactorModel.FILE)
    File(f.path + ".tmp").writeText(text); File(f.path + ".tmp").renameTo(f)
    println("written ${f.path} (${text.length / 1024} KB)")
}

/** Corporate-action diagnostics for the workflow log. */
private fun diagnostics(stocks: List<MarketStock>) {
    val all = stocks.flatMap { it.events }
    val acc = all.filter { it.accepted }
    val hist = java.util.TreeMap<Int, Int>()
    for (x in stocks) { val k = minOf(x.adjustments, 4); hist[k] = (hist[k] ?: 0) + 1 }
    val cand = java.util.TreeMap<Int, Int>()
    for (x in stocks) { val k = minOf(x.events.size, 10); cand[k] = (cand[k] ?: 0) + 1 }
    println("== corporate actions: ${acc.size} applied on ${stocks.count { it.adjustments > 0 }} stocks; ${all.size} prev-close mismatches > ${(MarketHistory.ADJ_MIN * 100).toInt()}%")
    println("   applied per stock (4 = 4+): $hist")
    println("   mismatches per stock (10 = 10+): $cand")
    println("   mismatches by reason: " + all.groupingBy { it.reason }.eachCount().toSortedMap() + "  by exchange: " + all.groupingBy { it.exch + ":" + it.reason }.eachCount().toSortedMap())
    fun line(e: AdjEvent) = "   %-12s %s %s prev %.2f ourClose %.2f open %.2f ratio %.4f %s".format(e.sym, e.exch, java.time.LocalDate.ofEpochDay(e.day.toLong()), e.prev, e.last, e.open, e.ratio, e.reason)
    println("-- applied (top 20 by size):"); acc.sortedByDescending { Math.abs(Math.log(it.ratio)) }.take(20).forEach { println(line(it)) }
    println("-- ignored (top 20 by size):"); all.filter { !it.accepted }.sortedByDescending { Math.abs(Math.log(it.ratio)) }.take(20).forEach { println(line(it)) }
    println("-- ignored, largest 1-8% mismatches (top 20):"); all.filter { it.reason == "small" }.sortedByDescending { Math.abs(Math.log(it.ratio)) }.take(20).forEach { println(line(it)) }
    val share = stocks.count { it.adjustments > 0 } * 100.0 / maxOf(1, stocks.size)
    if (share > 15) println("::warning::corporate-action adjustments on %.0f%% of stocks - check the log above".format(share))
}
private const val MIN_WRITE_SESSIONS = 253

private fun read(f: File): String {
    val bytes = f.readBytes()
    return when {
        f.name.endsWith(".gz") -> GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
        f.name.endsWith(".zip") -> ZipInputStream(bytes.inputStream()).use { z -> var e = z.nextEntry; while (e != null && e.isDirectory) e = z.nextEntry; z.readBytes().toString(Charsets.UTF_8) }
        else -> bytes.toString(Charsets.UTF_8)
    }
}
