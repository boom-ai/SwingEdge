package com.boomai.swingedge.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Markets terminal: global futures / rates / FX / commodities, Indian indices, sector heatmap, breadth, movers,
 * 52-week highs and lows, FII/DII cash flows, results & corporate-action calendar, holidays and a headline feed.
 *
 * [Terminal] holds the pure parsers and the maths (tested in coretest/TerminalTests.kt); [MarketsDesk] fetches and
 * caches (every source has its own age; stale sources refresh in the background so the screen never waits), and
 * builds the one `markets` reply. Nothing here throws.
 *
 * Sources: Yahoo v8 chart (global), Groww index batch + NSE /api/allIndices (India indices, sectoral indices),
 * TradingView screener (every NSE stock, ~15 min delayed; data/scan.csv + data/flows.csv end-of-day fallback),
 * data/fiidii.csv + data/events.csv (GitHub flows job) with NSE /api/fiidiiTradeReact live, data/holidays.json,
 * Forex Factory weekly calendar (high-impact events), Google News RSS.
 */
object Terminal {
    // ------------------------------------------------------------------ global (Yahoo v8 chart)
    class G(val sym: String, val name: String, val grp: String, val unit: String = "")
    val GLOBAL = listOf(
        G("YM=F", "Dow fut", "US futures"), G("ES=F", "S&P 500 fut", "US futures"), G("NQ=F", "Nasdaq fut", "US futures"),
        G("^N225", "Nikkei 225", "Asia"), G("^HSI", "Hang Seng", "Asia"),
        G("^TNX", "US 10Y", "Rates & FX", "yld"), G("DX-Y.NYB", "Dollar index", "Rates & FX"), G("INR=X", "USD/INR", "Rates & FX", "inr"),
        G("BZ=F", "Brent", "Commodities", "usd"), G("CL=F", "WTI crude", "Commodities", "usd"), G("GC=F", "Gold", "Commodities", "usd"),
        G("SI=F", "Silver", "Commodities", "usd"), G("BTC-USD", "Bitcoin", "Crypto", "usd"))

    fun globalUrl(host: String, sym: String) = Yahoo.tickerUrl(host, sym, "1d", "15m")

    private fun n(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull() ?: Double.NaN; else -> Double.NaN }.let { if (it.isInfinite()) Double.NaN else it }
    fun r2(x: Double) = if (x.isNaN()) x else Math.round(x * 100.0) / 100.0
    private fun r4(x: Double) = if (x.isNaN()) x else Math.round(x * 10000.0) / 10000.0
    private fun nz(x: Double): Double? = if (x.isNaN() || x.isInfinite()) null else x

    /** Yahoo chart -> one tile (last, change, % change vs the previous close, intraday spark), or null. */
    fun parseGlobal(g: G, text: String?): Map<String, Any?>? = try {
        val res = ((Json.parseObject(text ?: "")?.get("chart") as? Map<*, *>)?.get("result") as? List<*>)?.firstOrNull() as? Map<*, *>
        val meta = res?.get("meta") as? Map<*, *>
        if (meta == null) null else {
            val closes = ((((res["indicators"] as? Map<*, *>)?.get("quote") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("close") as? List<*>).orEmpty().map { n(it) }.filter { !it.isNaN() && it > 0 }
            var last = n(meta["regularMarketPrice"]); if (!(last > 0)) last = closes.lastOrNull() ?: Double.NaN
            var prev = n(meta["chartPreviousClose"]); if (!(prev > 0)) prev = n(meta["previousClose"])
            if (!(last > 0)) null else {
                val chg = if (prev > 0) last - prev else Double.NaN
                val step = Math.max(1, closes.size / 40)
                linkedMapOf("sym" to g.sym, "name" to g.name, "grp" to g.grp, "unit" to g.unit, "last" to r4(last), "chg" to nz(r4(chg)),
                    "pct" to nz(if (prev > 0) r2((last / prev - 1) * 100) else Double.NaN),
                    "ts" to (n(meta["regularMarketTime"]).let { if (it > 0) it.toLong() * 1000 else 0L }),
                    "spark" to closes.filterIndexed { i, _ -> i % step == 0 || i == closes.size - 1 }.map { r4(it) })
            }
        }
    } catch (t: Throwable) { null }

    // ------------------------------------------------------------------ GIFT Nifty (TradingView futures scanner)
    const val GIFT_URL = "https://scanner.tradingview.com/futures/scan"
    val GIFT_BODY: String = Json.stringify(linkedMapOf("symbols" to linkedMapOf("tickers" to listOf("NSEIX:NIFTY1!"), "query" to linkedMapOf("types" to emptyList<String>())),
        "columns" to listOf("close", "change", "change_abs")))
    fun parseGift(text: String?): Map<String, Any?>? = try {
        val d = ((Json.parseObject(text ?: "")?.get("data") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("d") as? List<*>
        val last = n(d?.getOrNull(0))
        if (d == null || !(last > 0)) null else linkedMapOf("name" to "GIFT Nifty", "last" to r2(last), "pct" to nz(r2(n(d.getOrNull(1)))), "chg" to nz(r2(n(d.getOrNull(2)))))
    } catch (t: Throwable) { null }

    // ------------------------------------------------------------------ NSE allIndices
    /** NSE index name -> short label for the sector heatmap. */
    val SECTORAL = linkedMapOf(
        "NIFTY BANK" to "Bank", "NIFTY IT" to "IT", "NIFTY AUTO" to "Auto", "NIFTY PHARMA" to "Pharma", "NIFTY FMCG" to "FMCG",
        "NIFTY METAL" to "Metal", "NIFTY REALTY" to "Realty", "NIFTY ENERGY" to "Energy", "NIFTY MEDIA" to "Media",
        "NIFTY PSU BANK" to "PSU Bank", "NIFTY PRIVATE BANK" to "Pvt Bank", "NIFTY FINANCIAL SERVICES" to "Financials",
        "NIFTY OIL & GAS" to "Oil & Gas", "NIFTY HEALTHCARE INDEX" to "Healthcare", "NIFTY CONSUMER DURABLES" to "Cons Durables",
        "NIFTY INDIA DEFENCE" to "Defence", "NIFTY CHEMICALS" to "Chemicals", "NIFTY CAPITAL MARKETS" to "Capital Mkts",
        "NIFTY INFRASTRUCTURE" to "Infra", "NIFTY PSE" to "PSE", "NIFTY COMMODITIES" to "Commodities", "NIFTY INDIA CONSUMPTION" to "Consumption")
    /** Broad indices taken from NSE when Groww lacks them (and as Groww's fallback). */
    val BROAD = linkedMapOf("NIFTY 50" to "Nifty 50", "NIFTY BANK" to "Bank Nifty", "NIFTY FINANCIAL SERVICES" to "Fin Nifty",
        "NIFTY MIDCAP SELECT" to "Midcap Nifty", "NIFTY MIDCAP 100" to "Midcap 100", "NIFTY SMALLCAP 100" to "Smallcap 100",
        "NIFTY NEXT 50" to "Nifty Next 50", "INDIA VIX" to "India VIX")

    class NseIdx(val name: String, val last: Double, val chg: Double, val pct: Double, val adv: Int, val dec: Int, val unch: Int, val hi52: Double, val lo52: Double)
    fun parseAllIndices(text: String?): Map<String, NseIdx> = try {
        val out = LinkedHashMap<String, NseIdx>()
        for (x in (Json.parseObject(text ?: "")?.get("data") as? List<*>).orEmpty()) {
            val m = x as? Map<*, *> ?: continue
            val k = ((m["index"] ?: m["indexSymbol"]) as? String)?.uppercase()?.trim() ?: continue
            val last = n(m["last"]); if (!(last > 0)) continue
            out[k] = NseIdx(k, last, n(m["variation"]), n(m["percentChange"]), n(m["advances"]).let { if (it.isNaN()) -1 else it.toInt() },
                n(m["declines"]).let { if (it.isNaN()) -1 else it.toInt() }, n(m["unchanged"]).let { if (it.isNaN()) -1 else it.toInt() }, n(m["yearHigh"]), n(m["yearLow"]))
        }
        out
    } catch (t: Throwable) { emptyMap() }

    /** India strip: Groww rows first (tick fresh), NSE fills the gaps; fixed order. */
    fun india(groww: List<Map<String, Any?>>, nse: Map<String, NseIdx>): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val byName = groww.associateBy { it["name"] as? String ?: "" }
        for ((k, lab) in BROAD) {
            val g = byName[lab]
            if (g != null) { out.add(linkedMapOf("name" to lab, "last" to g["last"], "chg" to g["chg"], "pct" to g["pct"], "src" to "live")); continue }
            val x = nse[k] ?: continue
            out.add(linkedMapOf("name" to lab, "last" to r2(x.last), "chg" to nz(r2(x.chg)), "pct" to nz(r2(x.pct)), "src" to "nse"))
        }
        return out
    }

    fun sectorIndices(nse: Map<String, NseIdx>): List<Map<String, Any?>> = SECTORAL.mapNotNull { (k, lab) ->
        val x = nse[k] ?: return@mapNotNull null
        if (x.pct.isNaN()) return@mapNotNull null
        linkedMapOf("name" to lab, "idx" to k, "last" to r2(x.last), "pct" to r2(x.pct), "adv" to x.adv.takeIf { it >= 0 }, "dec" to x.dec.takeIf { it >= 0 })
    }.sortedByDescending { it["pct"] as Double }

    // ------------------------------------------------------------------ stocks (TradingView, scan fallback)
    val TV_COLS = listOf("close", "change", "volume", "average_volume_30d_calc", "SMA50", "SMA200", "price_52_week_high", "price_52_week_low",
        "market_cap_basic", "sector", "earnings_release_next_date", "high", "low")

    /** One stock. pct = % change today. volX = today's volume / normal. valCr = traded value today, Rs crore. */
    class Stk(val sym: String, val sector: String, val close: Double, val pct: Double, val volX: Double, val valCr: Double,
              val sma50: Double, val sma200: Double, val hi52: Double, val lo52: Double, val high: Double, val low: Double,
              val mcapCr: Double, val earnDay: Int, val weight: Double)

    fun fromTv(text: String?): List<Stk> {
        val rows = TvScanner.parse(text, TV_COLS)
        val out = ArrayList<Stk>(rows.size)
        for ((sym, m) in rows) {
            val c = n(m["close"]); if (!(c > 0)) continue
            val v = n(m["volume"]); val av = n(m["average_volume_30d_calc"]); val mc = n(m["market_cap_basic"])
            val ed = n(m["earnings_release_next_date"]).let { if (it > 0) Clock.epochDay(it.toLong() * 1000) else 0 }
            out.add(Stk(sym, (m["sector"] as? String)?.trim() ?: "", c, n(m["change"]), if (v >= 0 && av > 0) v / av else Double.NaN,
                if (v >= 0) c * v / 1e7 else Double.NaN, n(m["SMA50"]), n(m["SMA200"]), n(m["price_52_week_high"]), n(m["price_52_week_low"]),
                n(m["high"]), n(m["low"]), if (mc > 0) mc / 1e7 else Double.NaN, ed, if (mc > 0) mc / 1e7 else Double.NaN))
        }
        return out
    }

    /** End-of-day fallback: NSE rows of the whole-market scan, with today's turnover from flows.csv for volume. */
    fun fromScan(d: MarketScanData?, flows: Map<String, FlowRow>): List<Stk> {
        if (d == null) return emptyList()
        val out = ArrayList<Stk>(d.nseCount)
        for (r in d.rows) {
            if (r.exch != Bhav.NSE) continue
            val c = r.comp; if (!(c.close > 0) || !(c.prevClose > 0)) continue
            val f = flows[r.sym]
            val to1 = f?.to1 ?: Double.NaN; val to20 = f?.to20 ?: c.to20
            out.add(Stk(r.sym, r.sector, c.close, (c.close / c.prevClose - 1) * 100, if (to1 >= 0 && to20 > 0) to1 / to20 else Double.NaN, to1,
                c.sma50, c.sma200, c.hi252, c.lo252, c.high, Double.NaN, Double.NaN, 0, if (c.to20 > 0) c.to20 else Double.NaN))
        }
        return out
    }

    fun isNewHigh(s: Stk) = s.hi52 > 0 && s.high > 0 && s.high >= s.hi52 * 0.9995
    /** Exact with a day low (TradingView); without one (scan) a close within 1% of the 52-week low counts. */
    fun isNewLow(s: Stk) = s.lo52 > 0 && (if (s.low > 0) s.low <= s.lo52 * 1.0005 else s.close <= s.lo52 * 1.01)

    fun breadth(st: List<Stk>): Map<String, Any?> {
        var adv = 0; var dec = 0; var unch = 0; var a50 = 0; var n50 = 0; var a200 = 0; var n200 = 0; var hi = 0; var lo = 0
        for (s in st) {
            if (s.pct.isNaN()) continue
            if (s.pct > 0.0) adv++ else if (s.pct < 0.0) dec++ else unch++
            if (s.sma50 > 0) { n50++; if (s.close > s.sma50) a50++ }
            if (s.sma200 > 0) { n200++; if (s.close > s.sma200) a200++ }
            if (isNewHigh(s)) hi++
            if (isNewLow(s)) lo++
        }
        val tot = adv + dec + unch
        return linkedMapOf("adv" to adv, "dec" to dec, "unch" to unch, "total" to tot, "adRatio" to nz(if (dec > 0) r2(adv.toDouble() / dec) else Double.NaN),
            "above50" to nz(if (n50 > 0) r2(a50 * 100.0 / n50) else Double.NaN), "n50" to n50,
            "above200" to nz(if (n200 > 0) r2(a200 * 100.0 / n200) else Double.NaN), "n200" to n200, "hi52" to hi, "lo52" to lo)
    }

    /** Liquid enough to list as a mover: Rs 5 crore traded today (or a 20-day average of it, end-of-day). */
    const val MOVER_MIN_CR = 5.0
    const val SHOCK_MIN_X = 2.5
    const val SHOCK_MIN_CR = 2.0

    private fun row(s: Stk, name: (String) -> String) = linkedMapOf("sym" to s.sym, "name" to name(s.sym), "ltp" to r2(s.close), "pct" to nz(r2(s.pct)),
        "volX" to nz(r2(s.volX)), "valCr" to nz(r2(s.valCr)), "mcapCr" to nz(Math.rint(s.mcapCr)))

    fun movers(st: List<Stk>, name: (String) -> String, max: Int = 15): Map<String, Any?> {
        fun liq(s: Stk) = (if (s.valCr >= 0) s.valCr else s.weight) >= MOVER_MIN_CR && !s.pct.isNaN()
        val l = st.filter { liq(it) }
        val big = compareByDescending<Stk> { if (it.mcapCr > 0) it.mcapCr else it.weight.let { w -> if (w.isNaN()) 0.0 else w } }
        return linkedMapOf(
            "gainers" to l.filter { it.pct > 0 }.sortedByDescending { it.pct }.take(max).map { row(it, name) },
            "losers" to l.filter { it.pct < 0 }.sortedBy { it.pct }.take(max).map { row(it, name) },
            "volume" to st.filter { it.volX >= SHOCK_MIN_X && it.valCr >= SHOCK_MIN_CR && !it.pct.isNaN() }.sortedByDescending { it.volX }.take(max).map { row(it, name) },
            "highs" to st.filter { isNewHigh(it) }.sortedWith(big).take(40).map { row(it, name) },
            "lows" to st.filter { isNewLow(it) }.sortedWith(big).take(40).map { row(it, name) })
    }

    /** Sector moves from the stocks: weighted (market cap, else turnover) average % change, advances / declines. */
    fun sectorAgg(st: List<Stk>, sectorOf: (String) -> String?): List<Map<String, Any?>> {
        class Acc { var w = 0.0; var wp = 0.0; var adv = 0; var dec = 0; var n = 0 }
        val by = LinkedHashMap<String, Acc>()
        for (s in st) {
            if (s.pct.isNaN()) continue
            val sec = (sectorOf(s.sym)?.takeIf { it.isNotBlank() } ?: s.sector).trim()
            if (sec.isEmpty() || sec.equals("Miscellaneous", true)) continue
            val w = if (s.weight > 0) s.weight else continue
            val a = by.getOrPut(sec) { Acc() }
            a.w += w; a.wp += w * s.pct.coerceIn(-20.0, 20.0); a.n++
            if (s.pct > 0) a.adv++ else if (s.pct < 0) a.dec++
        }
        return by.filter { it.value.n >= 4 && it.value.w > 0 }.map { (k, a) -> linkedMapOf<String, Any?>("name" to k, "pct" to r2(a.wp / a.w), "adv" to a.adv, "dec" to a.dec, "n" to a.n) }
            .sortedByDescending { it["pct"] as Double }
    }

    /** Results dates from the screener: the next [days] days, companies of at least Rs 500 crore, soonest then biggest. */
    fun earnings(st: List<Stk>, today: Int, name: (String) -> String, days: Int = 21, max: Int = 80): List<Map<String, Any?>> =
        st.filter { it.earnDay in today..(today + days) && it.mcapCr >= 500 }
            .sortedWith(compareBy<Stk> { it.earnDay }.thenByDescending { it.mcapCr }).take(max)
            .map { linkedMapOf("date" to iso(it.earnDay), "sym" to it.sym, "name" to name(it.sym), "kind" to "RESULTS", "detail" to "Quarterly results", "mcapCr" to nz(Math.rint(it.mcapCr))) }

    fun iso(day: Int): String = try { java.time.LocalDate.ofEpochDay(day.toLong()).toString() } catch (t: Throwable) { "" }

    // ------------------------------------------------------------------ FII / DII
    class Flow(val day: Int, val fiiBuy: Double, val fiiSell: Double, val fiiNet: Double, val diiBuy: Double, val diiSell: Double, val diiNet: Double)

    /** data/fiidii.csv: DATE,FII_BUY,FII_SELL,FII_NET,DII_BUY,DII_SELL,DII_NET (Rs crore). */
    fun parseFiiCsv(csv: String?): List<Flow> {
        if (csv == null || !csv.startsWith("DATE")) return emptyList()
        val out = ArrayList<Flow>()
        try {
            for (line in csv.replace("\r", "").split('\n').drop(1)) {
                val c = line.split(','); if (c.size < 7) continue
                val d = Bhav.parseDay(c[0]); if (d <= 0) continue
                val v = (1..6).map { c[it].trim().toDoubleOrNull() ?: Double.NaN }
                if (v[2].isNaN() && v[5].isNaN()) continue
                out.add(Flow(d, v[0], v[1], v[2], v[3], v[4], v[5]))
            }
        } catch (t: Throwable) {}
        return out
    }

    /** NSE /api/fiidiiTradeReact: [{category:"FII/FPI *"|"DII *", date:"27-Nov-2026", buyValue, sellValue, netValue}]. */
    fun parseFiiNse(text: String?): Flow? = try {
        val rows = (Json.parseOrNull(text ?: "") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val f = rows.firstOrNull { (it["category"] as? String)?.uppercase()?.contains("FII") == true }
        val d = rows.firstOrNull { (it["category"] as? String)?.uppercase()?.startsWith("DII") == true }
        val day = parseNseDate((f ?: d)?.get("date") as? String)
        if ((f == null && d == null) || day <= 0) null
        else Flow(day, n(f?.get("buyValue")), n(f?.get("sellValue")), n(f?.get("netValue")), n(d?.get("buyValue")), n(d?.get("sellValue")), n(d?.get("netValue")))
    } catch (t: Throwable) { null }

    private val MON = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    /** "27-Nov-2026" / "27-NOV-2026" / ISO / yyyymmdd -> epoch day; 0 when unreadable. */
    fun parseNseDate(s: String?): Int = try {
        val t = (s ?: "").trim()
        val p = t.split('-', ' ')
        if (p.size == 3 && p[1].length >= 3 && p[1][0].isLetter()) {
            val m = MON.indexOf(p[1].substring(0, 3).uppercase()) + 1
            if (m <= 0) 0 else java.time.LocalDate.of(p[2].toInt(), m, p[0].toInt()).toEpochDay().toInt()
        } else Bhav.parseDay(t)
    } catch (t: Throwable) { 0 }

    fun fiiView(hist: List<Flow>, live: Flow?): Map<String, Any?> {
        val by = java.util.TreeMap<Int, Flow>()
        for (f in hist) by[f.day] = f
        if (live != null) by[live.day] = live
        val rows = by.values.toList().reversed()
        if (rows.isEmpty()) return linkedMapOf("ready" to false)
        fun sum(k: Int, sel: (Flow) -> Double) = rows.take(k).map(sel).filter { !it.isNaN() }.sum()
        fun f(x: Double) = nz(Math.rint(x))
        val days = rows.take(15).map { linkedMapOf("date" to iso(it.day), "fii" to f(it.fiiNet), "dii" to f(it.diiNet)) }
        val l = rows.first()
        return linkedMapOf("ready" to true, "date" to iso(l.day), "fii" to f(l.fiiNet), "dii" to f(l.diiNet),
            "fiiBuy" to f(l.fiiBuy), "fiiSell" to f(l.fiiSell), "diiBuy" to f(l.diiBuy), "diiSell" to f(l.diiSell),
            "fii5" to f(sum(5) { it.fiiNet }), "dii5" to f(sum(5) { it.diiNet }), "fii20" to f(sum(20) { it.fiiNet }), "dii20" to f(sum(20) { it.diiNet }),
            "n" to rows.size, "days" to days)
    }

    // ------------------------------------------------------------------ calendar
    /** data/events.csv: DATE,SYMBOL,KIND,DETAIL (from the flows job; NSE board meetings + corporate actions). */
    fun parseEvents(csv: String?, today: Int, name: (String) -> String, ahead: Int = 45, max: Int = 200): List<Map<String, Any?>> {
        if (csv == null || !csv.startsWith("DATE")) return emptyList()
        val out = ArrayList<Pair<Int, Map<String, Any?>>>()
        try {
            for (line in csv.replace("\r", "").split('\n').drop(1)) {
                if (line.isBlank()) continue
                val c = StockList.splitCsv(line); if (c.size < 4) continue
                val d = Bhav.parseDay(c[0]); if (d < today || d > today + ahead) continue
                val sym = c[1].trim().uppercase(); if (sym.isEmpty() || sym.length > 25) continue
                out.add(Pair(d, linkedMapOf("date" to iso(d), "sym" to sym, "name" to name(sym), "kind" to c[2].trim().uppercase().take(12), "detail" to c[3].trim().take(120))))
            }
        } catch (t: Throwable) {}
        return out.sortedWith(compareBy<Pair<Int, Map<String, Any?>>> { it.first }.thenBy { it.second["sym"] as String }).take(max).map { it.second }
    }

    /** holidays.json in any of: ["2026-12-25", ...], [{date, name|description|desc|reason}], {"2026-12-25": "Christmas"}, {"holidays": [...]}. */
    fun parseHolidays(text: String?, today: Int, max: Int = 12): List<Map<String, Any?>> = try {
        val root = Json.parseOrNull(text ?: "")
        val items = ArrayList<Pair<Int, String>>()
        fun add(d: String?, nm: String?) { val day = parseNseDate(d); if (day > 0) items.add(Pair(day, (nm ?: "").trim().take(60))) }
        fun walk(x: Any?) {
            when (x) {
                is List<*> -> for (e in x) when (e) {
                    is String -> add(e, "")
                    is Map<*, *> -> add((e["date"] ?: e["tradingDate"] ?: e["day"]) as? String, (e["name"] ?: e["description"] ?: e["desc"] ?: e["reason"]) as? String)
                }
                is Map<*, *> -> {
                    val inner = x["holidays"] ?: x["NSE"] ?: x["CM"] ?: x["data"]
                    if (inner != null) walk(inner) else for ((k, v) in x) add(k as? String, v as? String)
                }
            }
        }
        walk(root)
        items.filter { it.first >= today }.distinctBy { it.first }.sortedBy { it.first }.take(max).map { linkedMapOf("date" to iso(it.first), "name" to it.second) }
    } catch (t: Throwable) { emptyList() }

    // ------------------------------------------------------------------ economic calendar (Forex Factory weekly JSON)
    const val ECON_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"
    val ECON_CCY = setOf("USD", "CNY", "EUR", "JPY", "GBP", "INR")
    fun parseEcon(text: String?, nowMs: Long, max: Int = 40): List<Map<String, Any?>> = try {
        val out = ArrayList<Pair<Long, Map<String, Any?>>>()
        for (x in (Json.parseOrNull(text ?: "") as? List<*>).orEmpty()) {
            val m = x as? Map<*, *> ?: continue
            val imp = (m["impact"] as? String)?.trim() ?: ""
            if (imp != "High" && imp != "Medium") continue
            val cc = (m["country"] as? String)?.trim()?.uppercase() ?: continue
            if (cc !in ECON_CCY) continue
            val at = try { java.time.OffsetDateTime.parse((m["date"] as? String)?.trim() ?: "").toInstant().toEpochMilli() } catch (t: Throwable) { continue }
            if (at < nowMs - 12 * 3600_000L) continue
            out.add(Pair(at, linkedMapOf("at" to at, "ccy" to cc, "title" to ((m["title"] as? String) ?: "").trim().take(80), "impact" to imp,
                "forecast" to ((m["forecast"] as? String) ?: "").trim().take(16), "previous" to ((m["previous"] as? String) ?: "").trim().take(16),
                "actual" to ((m["actual"] as? String) ?: "").trim().take(16))))
        }
        out.sortedBy { it.first }.take(max).map { it.second }
    } catch (t: Throwable) { emptyList() }

    // ------------------------------------------------------------------ news (Google News RSS)
    val NEWS_URLS = listOf(
        "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("(sensex OR nifty OR \"stock market\" OR \"dalal street\") when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en",
        "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode("(RBI OR rupee OR FII OR \"stocks to watch\" OR IPO) india when:1d", "UTF-8") + "&hl=en-IN&gl=IN&ceid=IN:en")
    private val ITEM = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(x: String, t: String): String = Regex("<$t[^>]*>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(x)?.groupValues?.get(1)?.trim() ?: ""
    fun unescape(s: String): String = s.replace("<![CDATA[", "").replace("]]>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

    fun parseNews(texts: List<String?>, max: Int = 60): List<Map<String, Any?>> {
        val seen = HashSet<String>(); val out = ArrayList<Pair<Long, Map<String, Any?>>>()
        for (text in texts) {
            if (text == null) continue
            try {
                for (mm in ITEM.findAll(text)) {
                    val it = mm.groupValues[1]
                    var title = unescape(tag(it, "title")); val src = unescape(tag(it, "source")).take(40)
                    if (src.isNotEmpty() && title.endsWith(" - $src")) title = title.removeSuffix(" - $src").trim()
                    val link = unescape(tag(it, "link"))
                    if (title.isEmpty() || !link.startsWith("https://")) continue
                    val cut = title.lastIndexOf(" - ")
                    val key = (if (cut > 20 && title.length - cut <= 45) title.substring(0, cut) else title).lowercase().replace(Regex("[^a-z0-9]"), "").take(60)
                    if (!seen.add(key)) continue
                    val at = try { java.time.ZonedDateTime.parse(unescape(tag(it, "pubDate")), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() } catch (t: Throwable) { 0L }
                    out.add(Pair(at, linkedMapOf("title" to title.take(220), "src" to src, "at" to at, "url" to link.take(1500))))
                }
            } catch (t: Throwable) {}
        }
        return out.sortedByDescending { it.first }.take(max).map { it.second }
    }
}

/**
 * Fetching and caching for the Markets screen. [view] answers at once from what it has; sources past their age are
 * refreshed on one background thread (inline on a [SyncPlatform]), so the screen polls and patches itself.
 */
class MarketsDesk(
    private val p: Platform,
    /** NSE JSON GET (path like "/api/allIndices"), null on failure */
    private val nseGet: (String) -> String?,
    /** Groww live index rows (Groww.parseIndices shape) */
    private val growwIdx: () -> List<Map<String, Any?>>,
    private val scan: () -> MarketScanData?,
    private val flows: () -> Map<String, FlowRow>,
    private val nameOf: (String) -> String
) {
    companion object {
        const val FILES_BASE = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/"
        val FILES = listOf("fiidii.csv", "events.csv", "holidays.json")
        const val AGE_IDX = 1_500L
        const val AGE_NSE = 20_000L
        const val AGE_GLOBAL = 20_000L
        const val AGE_STK_OPEN = 180_000L
        const val AGE_STK_CLOSED = 30 * 60_000L
        const val AGE_FILES = 3 * 3600_000L
        const val AGE_FII_LIVE = 30 * 60_000L
        const val AGE_NEWS = 5 * 60_000L
        const val AGE_ECON = 3600_000L
        const val RETRY = 60_000L
    }
    private class Slot<T>(@Volatile var v: T, @Volatile var at: Long = 0L, @Volatile var tried: Long = 0L)
    private val global = Slot<List<Map<String, Any?>>>(emptyList())
    private val gift = Slot<Map<String, Any?>?>(null)
    private val nse = Slot<Map<String, Terminal.NseIdx>>(emptyMap())
    private val india = Slot<List<Map<String, Any?>>>(emptyList())
    private val stocks = Slot<List<Terminal.Stk>>(emptyList())
    private val derived = Slot<Map<String, Any?>>(emptyMap())
    private val files = Slot<Map<String, String>>(emptyMap())
    private val fiiLive = Slot<Terminal.Flow?>(null)
    private val news = Slot<List<Map<String, Any?>>>(emptyList())
    private val econ = Slot<List<Map<String, Any?>>>(emptyList())
    @Volatile private var stkSrc = ""
    @Volatile private var scanKey = ""
    private val running = AtomicBoolean(false)
    private val inline = p is SyncPlatform

    private fun now() = try { p.now() } catch (t: Throwable) { System.currentTimeMillis() }
    private fun <T> due(s: Slot<T>, age: Long, now: Long, force: Boolean): Boolean =
        (now - s.at >= age || (force && now - s.at >= 5_000L)) && now - s.tried >= (if (s.at == 0L) 8_000L else Math.min(RETRY, age))

    init {
        try {
            val m = HashMap<String, String>()
            for (f in FILES) p.read("mk_$f")?.let { m[f] = it }
            files.v = m; files.at = p.read("mk_files_at.txt")?.trim()?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {}
    }

    /** The whole screen's data. [seg] = overview | sectors | breadth | calendar | news (decides what is fetched). */
    fun view(seg: String, force: Boolean): Map<String, Any?> {
        val now = now()
        val open = try { Clock.isMarketOpen(now) } catch (t: Throwable) { false }
        try {
            val jobs = ArrayList<() -> Unit>()
            val gets = ArrayList<Pair<String, (String?) -> Unit>>()
            if (due(india, AGE_IDX, now, force)) jobs.add { india.tried = now(); val g = try { growwIdx() } catch (t: Throwable) { emptyList() }; if (g.isNotEmpty() || nse.v.isNotEmpty()) { india.v = Terminal.india(g, nse.v); india.at = now() } }
            if (due(nse, AGE_NSE, now, force)) { nse.tried = now; jobs.add(0) { val m = Terminal.parseAllIndices(try { nseGet("/api/allIndices") } catch (t: Throwable) { null }); if (m.isNotEmpty()) { nse.v = m; nse.at = now(); india.at = 0 } } }
            if (seg != "news" && due(global, AGE_GLOBAL, now, force)) {
                global.tried = now
                val got = arrayOfNulls<Map<String, Any?>>(Terminal.GLOBAL.size)
                for ((k, g) in Terminal.GLOBAL.withIndex()) gets.add(Pair(Terminal.globalUrl(Params.YAHOO_HOST_1, g.sym)) { t -> got[k] = Terminal.parseGlobal(g, t) })
                gets.add(Pair("", { _ -> val l = got.filterNotNull(); if (l.isNotEmpty()) { global.v = l; global.at = now() } }))
                jobs.add { gift.tried = now(); Terminal.parseGift(try { p.httpRequest("POST", Terminal.GIFT_URL, TvScanner.HEADERS, Terminal.GIFT_BODY, 200_000)?.takeIf { it.code == 200 }?.body } catch (t: Throwable) { null })?.let { gift.v = it; gift.at = now() } }
            }
            if (due(stocks, if (open) AGE_STK_OPEN else AGE_STK_CLOSED, now, force)) { stocks.tried = now; jobs.add { loadStocks() } }
            else if (stocks.v.isEmpty() || stkSrc == "scan") { val k = scanKeyNow(); if (k != scanKey && stkSrc != "tv") fallbackStocks() }
            if (due(files, AGE_FILES, now, force)) {
                files.tried = now
                val got = HashMap<String, String>()
                for (f in FILES) gets.add(Pair(FILES_BASE + f) { t -> if (t != null && (t.startsWith("DATE") || t.trimStart().startsWith("[") || t.trimStart().startsWith("{"))) got[f] = t })
                gets.add(Pair("", { _ -> if (got.isNotEmpty()) { val m = HashMap(files.v); m.putAll(got); files.v = m; files.at = now()
                    try { for ((k, v) in got) p.write("mk_$k", v); p.write("mk_files_at.txt", files.at.toString()) } catch (t: Throwable) {} } }))
            }
            if ((seg == "overview" || seg == "calendar") && due(fiiLive, AGE_FII_LIVE, now, force)) { fiiLive.tried = now; jobs.add { Terminal.parseFiiNse(try { nseGet("/api/fiidiiTradeReact") } catch (t: Throwable) { null })?.let { fiiLive.v = it; fiiLive.at = now() } } }
            if ((seg == "news" || seg == "overview") && due(news, AGE_NEWS, now, force)) {
                news.tried = now
                val got = arrayOfNulls<String>(Terminal.NEWS_URLS.size)
                for ((k, u) in Terminal.NEWS_URLS.withIndex()) gets.add(Pair(u) { t -> got[k] = t })
                gets.add(Pair("", { _ -> val l = Terminal.parseNews(got.toList()); if (l.isNotEmpty()) { news.v = l; news.at = now() } }))
            }
            if (seg == "calendar" && due(econ, AGE_ECON, now, force)) {
                econ.tried = now
                gets.add(Pair(Terminal.ECON_URL) { t -> if (t != null) { econ.v = Terminal.parseEcon(t, now()); econ.at = now() } })
            }
            if (gets.isNotEmpty()) jobs.add(0) {
                val urls = gets.map { it.first }.filter { it.isNotEmpty() }
                val res = try { p.httpGetAll(urls) } catch (t: Throwable) { urls.map { null } }
                var k = 0
                for ((u, f) in gets) { if (u.isEmpty()) { try { f(null) } catch (t: Throwable) {} } else { val r = res.getOrNull(k++); try { f(r) } catch (t: Throwable) {} } }
            }
            if (jobs.isNotEmpty()) run(jobs)
        } catch (t: Throwable) { try { p.log("markets: $t") } catch (x: Throwable) {} }
        return build(seg, now(), open)
    }

    private fun run(jobs: List<() -> Unit>) {
        if (!running.compareAndSet(false, true)) return
        val body = Runnable { try { for (j in jobs) try { j() } catch (t: Throwable) {} } finally { running.set(false) } }
        if (inline) body.run() else try { Thread(body, "markets").apply { isDaemon = true }.start() } catch (t: Throwable) { running.set(false) }
    }

    private fun scanKeyNow(): String = scan()?.let { "${it.day}:${it.builtAt}" } ?: ""
    private fun fallbackStocks() {
        val d = try { scan() } catch (t: Throwable) { null } ?: return
        val l = Terminal.fromScan(d, try { flows() } catch (t: Throwable) { emptyMap() })
        if (l.isEmpty()) return
        scanKey = "${d.day}:${d.builtAt}"; stkSrc = "scan"; stocks.v = l
        derive(l, Terminal.iso(d.day))
    }
    private fun loadStocks() {
        val l = Terminal.fromTv(TvScanner.fetch(p, Terminal.TV_COLS))
        if (l.size >= 500) { stkSrc = "tv"; stocks.v = l; stocks.at = now(); derive(l, ""); return }
        if (stkSrc != "tv" || now() - stocks.at > 2 * 3600_000L) fallbackStocks()
    }
    private fun derive(l: List<Terminal.Stk>, asof: String) {
        val d = try { scan() } catch (t: Throwable) { null }
        val sec: (String) -> String? = { s -> d?.bySym?.get(s)?.sector }
        val today = Clock.epochDay(now())
        derived.v = linkedMapOf("breadth" to Terminal.breadth(l), "movers" to Terminal.movers(l, nameOf), "sectorAgg" to Terminal.sectorAgg(l, sec),
            "earnings" to Terminal.earnings(l, today, nameOf), "asof" to asof, "lowApprox" to l.none { it.low > 0 })
        derived.at = now()
    }

    private fun build(seg: String, now: Long, open: Boolean): Map<String, Any?> {
        val today = Clock.epochDay(now)
        val dv = derived.v
        val f = files.v
        val m = linkedMapOf<String, Any?>("ok" to true, "seg" to seg, "at" to now, "marketOpen" to open,
            "loading" to (running.get() && (india.at == 0L || global.at == 0L || dv.isEmpty())),
            "india" to india.v, "global" to global.v, "gift" to gift.v,
            "sectors" to Terminal.sectorIndices(nse.v), "sectorAgg" to dv["sectorAgg"],
            "breadth" to dv["breadth"], "movers" to dv["movers"],
            "fii" to Terminal.fiiView(Terminal.parseFiiCsv(f["fiidii.csv"]), fiiLive.v),
            "src" to linkedMapOf("india" to india.at, "nse" to nse.at, "global" to global.at, "stocks" to stkSrc, "stocksAt" to stocks.at,
                "stocksAsof" to dv["asof"], "delayMin" to TvScanner.DELAY_MIN, "lowApprox" to dv["lowApprox"], "files" to files.at, "fiiLive" to fiiLive.at,
                "news" to news.at, "econ" to econ.at))
        if (seg == "calendar" || seg == "overview") {
            val ev = Terminal.parseEvents(f["events.csv"], today, nameOf)
            val er = (dv["earnings"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            val have = ev.filter { it["kind"] == "RESULTS" || it["kind"] == "BOARD" }.map { "${it["date"]}|${it["sym"]}" }.toHashSet()
            m["events"] = (ev + er.filter { "${it["date"]}|${it["sym"]}" !in have }.map { x -> x.entries.associate { it.key.toString() to it.value } })
                .sortedBy { it["date"] as? String ?: "" }.take(220)
            m["holidays"] = Terminal.parseHolidays(f["holidays.json"], today)
            m["econ"] = econ.v
        }
        if (seg == "news" || seg == "overview") m["news"] = if (seg == "news") news.v else news.v.take(5)
        return m
    }
}
