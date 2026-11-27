package com.boomai.swingedge.scan

import com.boomai.swingedge.core.Bhav
import com.boomai.swingedge.core.BhavDay
import com.boomai.swingedge.core.Json
import com.boomai.swingedge.core.AdjEvent
import com.boomai.swingedge.core.MarketHistory
import com.boomai.swingedge.core.MarketStock
import com.boomai.swingedge.core.MarketScan
import com.boomai.swingedge.core.StockList
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
