package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*
import java.util.Random

/** SPEC5: live NSE stock list, analyst panel, new-listing card. */
object V5Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v5-list-parse" to ::parse, "v5-list-refresh" to ::refresh, "v5-search-meta" to ::searchMeta,
        "v5-panel-rules" to ::panelRules, "v5-panel-app" to ::panelApp, "v5-listing" to ::listing, "v5-list-concurrent" to ::concurrent,
        "v5-resolve-symbols" to ::resolveSymbols, "v5-holding-retry" to ::holdingRetry
    )

    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()
    private fun act(app: SwingApp, name: String, args: String): Map<String, Any?> {
        val txt = app.action(name, args)
        val r = Json.parseObject(txt)
        T.check(r != null && r["ok"] is Boolean, "action $name returns {ok:..}: " + txt.take(100))
        T.check(!txt.contains("NaN") && !txt.contains("Infinity"), "no NaN/Infinity in $name")
        return r ?: emptyMap()
    }
    private fun syms(r: Map<String, Any?>) = r.list("results").map { it.asObj().str("sym") }

    // ------------------------------------------------------------------ list texts
    private const val NSE_HEAD = "SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE\n"
    private const val SME_HEAD = "SYMBOL,NAME_OF_COMPANY,SERIES,DATE_OF_LISTING,PAID_UP_VALUE,MARKET_LOT,ISIN_NUMBER,FACE_VALUE\n"

    fun nseMain(n: Int, prefix: String = "MB", extra: String = ""): String {
        val sb = StringBuilder(NSE_HEAD)
        for (k in 0 until n) sb.append("$prefix$k,Main Board $k Limited,EQ,06-OCT-2008,10,1,INE00$k,10\n")
        return sb.append(extra).toString()
    }
    fun nseSme(n: Int, prefix: String = "SM"): String {
        val sb = StringBuilder(SME_HEAD)
        for (k in 0 until n) sb.append("$prefix$k,Small Co $k Limited,SM,27-Jun-25,10,1600,INE99$k,10\n")
        return sb.toString()
    }
    fun four(n: Int, prefix: String = "GH", extra: String = ""): String {
        val sb = StringBuilder("SYMBOL,NAME,LISTED,BOARD\n")
        for (k in 0 until n) sb.append("$prefix$k,Mirror $k Limited,01-JAN-2010,EQ\n")
        return sb.append(extra).toString()
    }

    /** FakePlatform with the stock-list URLs served from [served] (null = failure); everything else goes to the fake Yahoo. */
    class ListNet(val base: FakePlatform) : Platform {
        val served = HashMap<String, String?>()
        val listHits = java.util.Collections.synchronizedList(ArrayList<String>())
        @Volatile var delayMs = 0L
        override fun now() = base.now()
        override fun httpGetAll(urls: List<String>): List<String?> {
            if (urls.none { it.startsWith("https://nsearchives.") || it.startsWith("https://raw.githubusercontent.") }) return base.httpGetAll(urls)
            listHits.addAll(urls)
            if (delayMs > 0) Thread.sleep(delayMs)
            return urls.map { synchronized(served) { served[it] } }
        }
        override fun read(name: String) = base.read(name)
        override fun write(name: String, text: String) = base.write(name, text)
        override fun notify(channel: String, id: Int, title: String, body: String) = base.notify(channel, id, title, body)
        override fun asset(name: String) = base.asset(name)
        override fun log(msg: String) = base.log(msg)
    }

    // ------------------------------------------------------------------ parsing
    fun parse() {
        val m = StockList.parse(nseMain(3, extra = "QUOTE,\"Comma, Inc Limited\",BE,15-Mar-26,1,1,X,1\nbad sym,Bad,EQ,01-JAN-2000,1,1,X,1\n"))
        T.eq(m.format, "nse", "NSE header with leading spaces recognised")
        T.eq(m.rows.map { it.sym }, listOf("MB0", "MB1", "MB2", "QUOTE"), "rows, invalid symbol skipped")
        T.eq(m.rows[0].listed, day(2008, 10, 6), "06-OCT-2008")
        T.eq(m.rows[3].listed, day(2026, 3, 15), "15-Mar-26")
        T.eq(m.rows[3].name, "Comma, Inc Limited", "quoted name with a comma")
        T.check(m.rows.all { it.board == "EQ" }, "main file -> EQ")
        val s = StockList.parse(nseSme(2), "SME")
        T.eq(s.format, "nse", "SME header with underscores recognised")
        T.check(s.rows.size == 2 && s.rows.all { it.board == "SME" && it.listed == day(2025, 6, 27) }, "SME rows, 27-Jun-25")
        val f = StockList.parse("﻿SYMBOL,NAME,LISTED,BOARD\nAAA,Aaa Ltd,27-Jun-25,SME\nBBB,\"B, B Ltd\",06-OCT-2008,EQ\nCCC,Ccc Ltd,,EQ\nAAA,Dup,01-JAN-2000,EQ\n")
        T.eq(f.format, "four", "4-column format")
        T.check(f.rows.size == 3 && f.rows[0].board == "SME" && f.rows[1].name == "B, B Ltd" && f.rows[2].listed == 0 && f.rows[0].name == "Aaa Ltd", "4-column rows, empty date -> 0, duplicate keeps first")
        val two = StockList.parse("TATAMOTORS,Tata Motors Ltd\nNONAME\nLT,Larsen & Toubro Ltd\n")
        T.check(two.format == "two" && two.rows.map { it.sym } == listOf("TATAMOTORS", "NONAME", "LT") && two.rows[1].name == "" && two.rows.all { it.listed == 0 && it.board == "EQ" }, "old 2-column format")
        T.eq(StockList.parse("SYMBOL,NAME OF COMPANY\nAAA,Aaa Ltd\n").format, "two", "2-column with header")
        for ((d, want) in listOf("06-OCT-2008" to day(2008, 10, 6), "27-Jun-25" to day(2025, 6, 27), "2026-03-14" to day(2026, 3, 14), "31/12/1999" to day(1999, 12, 31),
                "" to 0, "garbage" to 0, "31-FEB-2020" to 0, "12-XYZ-2020" to 0, "01-JAN-95" to day(1995, 1, 1)))
            T.eq(StockList.parseDate(d), want, "date '$d'")
        // validation
        T.check(StockList.validNseMain(nseMain(1500)) != null, "1,500 main rows accepted")
        T.check(StockList.validNseMain(nseMain(1499)) == null, "1,499 main rows rejected")
        T.check(StockList.validNseMain("<!DOCTYPE html><html><body>Access Denied</body></html>") == null, "HTML rejected")
        T.check(StockList.parse("<html>" + nseMain(2000)).html, "HTML detected")
        T.check(StockList.validNseMain(NSE_HEAD) == null && StockList.validNseMain("") == null && StockList.validNseMain(null) == null, "empty/header-only rejected")
        T.check(StockList.validNseMain(nseMain(2000).substring(0, 5000)) == null, "truncated response rejected")
        T.check(StockList.validNseMain(four(2000)) == null && StockList.validFour(nseMain(2000)) == null, "wrong columns rejected")
        T.check(StockList.validNseMain("FOO,BAR\n" + "X,Y\n".repeat(2000)) == null, "unknown header rejected")
        T.check(StockList.validNseSme(nseSme(150)) != null && StockList.validNseSme(nseSme(20)) == null, "SME needs 100 rows")
        T.check(StockList.validFour(four(1500)) != null, "4-column 1,500 EQ accepted")
        val smeHeavy = StringBuilder("SYMBOL,NAME,LISTED,BOARD\n"); for (k in 0 until 2000) smeHeavy.append("S$k,S,01-JAN-2020,${if (k < 1400) "EQ" else "SME"}\n")
        T.check(StockList.validFour(smeHeavy.toString()) == null, "4-column with only 1,400 EQ rejected")
        // round trip
        val d = StockListData(f.rows, "nse", 123456L)
        val back = StockList.parse(StockList.encode(d))
        T.check(back.format == "four" && back.updatedAt == 123456L && back.source == "nse" && back.rows.map { it.sym + it.name + it.listed + it.board } == f.rows.map { it.sym + it.name + it.listed + it.board }, "encode/decode round trip")
        // the shipped asset
        val f0 = java.io.File("app/src/main/assets/symbols.csv")
        if (f0.exists()) {
            val a = StockList.parse(f0.readText())
            T.check(a.format == "four" && a.rows.size == 3218 && a.rows.count { it.board == "SME" } == 583, "bundled asset parses: ${a.rows.size} rows, ${a.rows.count { it.board == "SME" }} SME")
            T.check(a.rows.all { it.listed > 0 }, "every bundled row has a listing day")
            T.check(StockList.validFour(f0.readText()) != null, "bundled asset passes validation")
            T.eq(a.rows.first { it.sym == "20MICRONS" }.listed, day(2008, 10, 6), "20MICRONS listed 06-OCT-2008")
        }
    }

    // ------------------------------------------------------------------ refresh chain
    fun refresh() {
        val fp = FakePlatform.standard()
        fp.assets["symbols.csv"] = "SYMBOL,NAME,LISTED,BOARD\nBUNDLEDCO,Bundled Co Ltd,01-JAN-2000,EQ\n"
        val p = ListNet(fp)
        val d0 = day(2026, 11, 9)   // Monday
        p.served[StockList.NSE_MAIN_URL] = nseMain(1600, extra = "FRESHCO,Fresh Listing Limited,EQ,02-NOV-2026,10,1,X,10\n")
        p.served[StockList.NSE_SME_URL] = nseSme(120)
        p.served[StockList.GITHUB_URL] = four(1700)
        fp.setTime(d0, 7 * 60 + 10)
        val app = SwingApp(p)
        app.tick("alarm")
        T.eq(p.listHits.size, 0, "no list download before 07:30")
        var r = act(app, "search", """{"q":"bundledco"}""")
        T.check(syms(r) == listOf("BUNDLEDCO") && r.obj("list").str("source") == "bundled" && r.obj("list").int("count") == 1 && !r.obj("list").bool("stale"), "bundled list before the refresh: ${r["list"]}")
        fp.setTime(d0, 7 * 60 + 31)
        app.tick("alarm")
        T.eq(p.listHits.toList(), listOf(StockList.NSE_MAIN_URL, StockList.NSE_SME_URL), "first tick after 07:30 fetches both NSE files (not GitHub)")
        r = act(app, "search", """{"q":"freshco"}""")
        T.check(syms(r) == listOf("FRESHCO") && r.list("results")[0].asObj().int("listedDay") == 20261102 && r.list("results")[0].asObj().bool("isNew"), "new list searchable: $r")
        val meta = r.obj("list")
        T.check(meta.str("source") == "nse" && meta.int("count") == 1721 && meta.long("updatedAt") == fp.nowMs && !meta.bool("stale") && meta.int("newCount") == 1, "list meta after NSE: $meta")
        T.check(syms(act(app, "search", """{"q":"bundledco"}""")).isEmpty(), "old list replaced")
        T.eq(act(app, "search", """{"q":"sm1"}""").list("results")[0].asObj().str("board"), "SME", "SME rows merged")
        T.check(StockList.decodeSaved(fp.files[StockList.SAVED_FILE])?.rows?.size == 1721, "saved to symbols_live.csv")
        fp.setTime(d0, 9 * 60)
        app.tick("alarm"); app.tick("open")
        T.eq(p.listHits.size, 2, "once per day: later ticks do not refetch")
        T.eq(act(app, "refreshSymbols", "{}")["ok"], true, "refreshSymbols ok")
        T.eq(p.listHits.size, 2, "refreshSymbols after today's success: no network")
        // day 2: NSE blocked (HTML), SME fine -> GitHub
        p.served[StockList.NSE_MAIN_URL] = "<html><body>Access Denied</body></html>"
        fp.setTime(d0 + 1, 8 * 60)
        app.tick("alarm")
        T.eq(p.listHits.drop(2), listOf(StockList.NSE_MAIN_URL, StockList.NSE_SME_URL, StockList.GITHUB_URL), "NSE rejected -> GitHub fallback")
        r = act(app, "search", """{"q":"gh17"}""")
        T.check(syms(r).contains("GH17") && r.obj("list").str("source") == "github" && r.obj("list").int("count") == 1700, "GitHub list installed: ${r["list"]}")
        // day 3: everything fails -> keep the last good list
        p.served[StockList.GITHUB_URL] = "SYMBOL,NAME,LISTED,BOARD\nONLY,One Row,01-JAN-2020,EQ\n"
        p.served[StockList.NSE_MAIN_URL] = null
        fp.setTime(d0 + 2, 8 * 60)
        app.tick("alarm")
        r = act(app, "search", """{"q":"gh17"}""")
        T.check(syms(r).contains("GH17") && r.obj("list").str("source") == "github" && !r.obj("list").bool("stale"), "failures keep the last good list (1 day old, not stale)")
        val hits = p.listHits.size
        val rr = act(app, "refreshSymbols", "{}")
        T.check(rr["ok"] == true && rr["updated"] == false && rr.str("message").isNotEmpty() && rr.obj("list").str("source") == "github", "refreshSymbols after a failed day retries and says so: $rr")
        T.eq(p.listHits.size, hits + 3, "the action retried NSE and GitHub")
        fp.setTime(d0 + 5, 8 * 60)
        app.tick("alarm")
        T.check(act(app, "search", """{"q":""}""").obj("list").bool("stale"), "older than 3 days -> stale")
        // restart: the saved copy is used, as "saved"
        val app2 = SwingApp(p)
        r = act(app2, "search", """{"q":"gh17"}""")
        T.check(syms(r).contains("GH17") && r.obj("list").str("source") == "saved" && r.obj("list").long("updatedAt") > 0, "restart uses the saved list: ${r["list"]}")
        // a corrupt saved copy falls back to the bundled asset
        fp.files[StockList.SAVED_FILE] = "SYMBOL,NAME,LISTED,BOARD\nX,Y,,EQ\n"
        r = act(SwingApp(p), "search", """{"q":"bundledco"}""")
        T.check(syms(r) == listOf("BUNDLEDCO") && r.obj("list").str("source") == "bundled", "bad saved file -> bundled")
        // SME file broken: previous SME rows kept
        val fp3 = FakePlatform.standard(); val p3 = ListNet(fp3)
        p3.served[StockList.NSE_MAIN_URL] = nseMain(1600); p3.served[StockList.NSE_SME_URL] = nseSme(150)
        fp3.setTime(d0, 8 * 60); val a3 = SwingApp(p3); a3.tick("alarm")
        p3.served[StockList.NSE_MAIN_URL] = nseMain(1600, "NB"); p3.served[StockList.NSE_SME_URL] = "<html>"
        fp3.setTime(d0 + 1, 8 * 60); a3.tick("alarm")
        r = act(a3, "search", """{"q":"sm140"}""")
        T.check(syms(r) == listOf("SM140") && r.obj("list").str("source") == "nse" && r.obj("list").int("count") == 1750 && syms(act(a3, "search", """{"q":"nb5"}""")).contains("NB5"),
            "bad SME file: new main board + previous SME rows: ${r["list"]}")
        // write failure does not stop the swap
        val fp4 = FakePlatform.standard(); val p4 = ListNet(fp4); p4.served[StockList.NSE_MAIN_URL] = nseMain(1600)
        fp4.failWrites = true; fp4.setTime(d0, 8 * 60); val a4 = SwingApp(p4); a4.tick("alarm")
        T.eq(act(a4, "search", """{"q":"mb7"}""").obj("list").str("source"), "nse", "list installed even when it cannot be saved")
    }

    // ------------------------------------------------------------------ search rows, meta, newListings
    fun searchMeta() {
        val p = FakePlatform.standard()
        val sb = StringBuilder("SYMBOL,NAME,LISTED,BOARD\n")
        sb.append("UP01,Uptrend One Industries Ltd,06-OCT-2008,EQ\n")
        for (k in 0 until 130) sb.append("NEW%03d,New Co %d Ltd,%s,%s\n".format(k, k, StockList.formatDate(day(2026, 11, 12) - k * 3), if (k % 3 == 0) "SME" else "EQ"))
        sb.append("OLDIE,Oldie Ltd,13-NOV-2025,EQ\nEDGE,Edge Ltd,12-NOV-2025,EQ\nNODATE,No Date Ltd,,EQ\n")
        p.assets["symbols.csv"] = sb.toString()
        p.setTime(day(2026, 11, 13), 6 * 60)
        val app = SwingApp(p)
        var r = act(app, "search", """{"q":""}""")
        T.check(r["ok"] == true && r.str("q") == "" && r.list("results").isEmpty() && r["list"] is Map<*, *>, "empty query returns the list meta: $r")
        val meta = r.obj("list")
        T.check(meta.int("count") == 134 && meta.str("source") == "bundled" && meta.long("updatedAt") == 0L && !meta.bool("stale"), "bundled meta: $meta")
        T.eq(meta.int("newCount"), 122 + 1, "newCount: listed within 365 days (122 NEW + OLDIE)")
        r = act(app, "search", """{"q":"up01"}""")
        val row = r.list("results")[0].asObj()
        T.eq(row.keys.toList(), listOf("sym", "name", "tracked", "listedDay", "isNew", "board"), "search row keys")
        T.check(row.int("listedDay") == 20081006 && !row.bool("isNew") && row.str("board") == "EQ" && row.bool("tracked"), "old listing row: $row")
        T.check(act(app, "search", """{"q":"new000"}""").list("results")[0].asObj().let { it.bool("isNew") && it.str("board") == "SME" && it.int("listedDay") == 20261112 }, "new SME row")
        T.check(act(app, "search", """{"q":"nodate"}""").list("results")[0].asObj().let { it.int("listedDay") == 0 && !it.bool("isNew") }, "unknown date -> 0, not new")
        T.check(act(app, "search", """{"q":"up02"}""").list("results")[0].asObj().let { it.int("listedDay") == 0 && it.str("board") == "EQ" }, "universe-only symbol: unknown listing, EQ")
        T.check(act(app, "search", """{"q":"oldie"}""").list("results")[0].asObj().bool("isNew") && !act(app, "search", """{"q":"edge"}""").list("results")[0].asObj().bool("isNew"), "365-day boundary")
        val calls = p.urlCount
        r = act(app, "newListings", """{"days":365}""")
        val rows = r.list("results").map { it.asObj() }
        T.eq(rows.size, 100, "newListings max 100")
        T.eq(rows.first().keys.toList(), listOf("sym", "name", "listedDay", "board", "tracked"), "newListings row keys")
        T.check(rows.zipWithNext().all { it.first.int("listedDay") >= it.second.int("listedDay") }, "newest first")
        T.eq(rows.take(2).map { it.str("sym") }, listOf("NEW000", "NEW001"), "newest rows")
        T.eq(p.urlCount, calls, "newListings: no network")
        T.eq(act(app, "newListings", """{"days":30}""").list("results").size, 10, "days filter (1..28 days ago: 10 rows)")
        T.eq(act(app, "newListings", "{}").list("results").size, 100, "default 365 days")
        T.check(act(app, "newListings", "garbage")["ok"] == true, "bad args -> default")
        // tracked flag
        p.assets["symbols.csv"] = "SYMBOL,NAME,LISTED,BOARD\nUP01,Up,01-NOV-2026,EQ\nZZNEW,Zz,02-NOV-2026,SME\n"
        val rs = act(SwingApp(p), "newListings", "{}").list("results").map { it.asObj() }
        T.check(rs.map { it.str("sym") } == listOf("ZZNEW", "UP01") && rs[1].bool("tracked") && !rs[0].bool("tracked"), "tracked flag: $rs")
    }

    // ------------------------------------------------------------------ panel rules on constructed series
    fun series(n: Int, drift: Double, noise: Double, start: Double = 500.0, vol: Double = 3e6, seed: Long = 1, firstDay: Int = day(2024, 1, 1)): Series {
        val r = Random(seed)
        val s = Series("X", n + 1)
        var c = start
        for (k in 0 until n) {
            val o = c * (1 + r.nextGaussian() * noise * 0.3)
            c *= 1 + drift + r.nextGaussian() * noise
            val h = maxOf(o, c) * (1 + Math.abs(r.nextGaussian()) * noise * 0.5)
            val l = minOf(o, c) * (1 - Math.abs(r.nextGaussian()) * noise * 0.5)
            s.add(firstDay + k, o, h, l, c, vol)
        }
        return s
    }

    private fun build(s: Series, nifty: Series? = null, breadth: Double = Double.NaN, call: Map<String, Any?> = linkedMapOf("label" to "NA", "proven" to false, "headline" to "Not rated"),
                      listed: Int = 0, today: Int = 0): Map<String, Any?> =
        Json.parseObject(Json.stringify(Panel.build(PanelInput(s, null, null, call, nifty, breadth, 500000.0, 1.0, 0.25, listed, if (today > 0) today else s.d[s.n - 1] + 1)))) ?: emptyMap()

    private fun mgr(p: Map<String, Any?>, id: String) = p.list("managers").map { it.asObj() }.first { it.str("id") == id }

    fun panelRules() {
        val up = series(400, 0.003, 0.01)
        var p = build(up)
        T.eq(mgr(p, "trend").str("stance"), "FOR", "steady uptrend: trend FOR")
        T.eq(mgr(p, "momentum").str("stance"), "FOR", "uptrend: momentum FOR (absolute levels, no table)")
        T.eq(mgr(p, "liquidity").str("stance"), "FOR", "₹150+ cr a day: liquidity FOR")
        T.eq(mgr(p, "newlisting").str("stance"), "NEUTRAL", "400 bars: not new")
        T.check(mgr(p, "trend").str("text").startsWith("Price ") && mgr(p, "trend").str("text").contains("rising"), "trend text: " + mgr(p, "trend").str("text"))
        T.check(mgr(p, "risk").str("text").contains("5-ATR stop") && mgr(p, "risk").str("text").contains("at risk for your size"), "risk text states the stop and ₹ at risk: " + mgr(p, "risk").str("text"))
        T.check(mgr(p, "timing").list("metrics").any { it.asObj().str("label") == "RSI(2)" }, "timing shows RSI(2)")
        val dn = series(400, -0.003, 0.01, vol = 2e6)
        p = build(dn)
        T.eq(mgr(p, "trend").str("stance"), "AGAINST", "downtrend: trend AGAINST")
        T.eq(mgr(p, "momentum").str("stance"), "AGAINST", "downtrend: momentum AGAINST")
        T.eq(mgr(p, "timing").str("stance"), "NEUTRAL", "below the 50-day: timing NEUTRAL")
        T.eq(mgr(build(series(400, 0.003, 0.01, vol = 1e5)), "liquidity").str("stance"), "AGAINST", "thin: liquidity AGAINST")
        T.eq(mgr(build(series(300, 0.0, 0.01, start = 500.0, vol = 1e6)), "liquidity").str("stance"), "NEUTRAL", "₹25-100 cr: liquidity NEUTRAL")
        T.eq(mgr(build(series(400, 0.0, 0.045)), "risk").str("stance"), "AGAINST", "4.5% daily swings: risk AGAINST")
        T.eq(mgr(build(series(400, 0.0, 0.008)), "risk").str("stance"), "FOR", "0.8% daily swings: risk FOR")
        // timing: last bar pushed far above the 50-day average
        val st = series(300, 0.0, 0.01)
        val k = st.n - 1; val c = st.c[k] * 1.5
        st.c[k] = c; st.h[k] = c; st.o[k] = c
        T.eq(mgr(build(st), "timing").str("stance"), "AGAINST", "> 6 ATR above the 50-day: timing AGAINST")
        // market
        val nUp = series(300, 0.002, 0.005, start = 20000.0, vol = 0.0); val nDn = series(300, -0.002, 0.005, start = 20000.0, vol = 0.0)
        T.eq(mgr(build(up, nUp, 0.6), "market").str("stance"), "FOR", "Nifty above 200-day, breadth 60%: FOR")
        T.eq(mgr(build(up, nDn, 0.3), "market").str("stance"), "AGAINST", "Nifty below, breadth 30%: AGAINST")
        T.eq(mgr(build(up, nUp, 0.45), "market").str("stance"), "NEUTRAL", "breadth 45%: NEUTRAL")
        T.eq(mgr(build(up, null, 0.6), "market").str("stance"), "NEUTRAL", "no Nifty: NEUTRAL")
        T.eq(mgr(build(up, nUp, Double.NaN), "market").str("stance"), "NEUTRAL", "no breadth: NEUTRAL")
        T.eq(mgr(build(up, nUp, 0.6), "market").str("evidence"), "no edge — weak markets were followed by rebounds", "market evidence tag")
        // rule consistency over many random series
        val rnd = Random(9)
        var checked = 0
        val seen = HashMap<String, HashSet<String>>()
        for (t in 0 until 300) {
            val s = series(30 + rnd.nextInt(400), (rnd.nextDouble() - 0.5) * 0.008, 0.004 + rnd.nextDouble() * 0.04, vol = Math.pow(10.0, 4 + rnd.nextDouble() * 3.5), seed = t.toLong())
            val nifty = if (rnd.nextBoolean()) nUp else nDn
            val br = rnd.nextDouble()
            val call = linkedMapOf<String, Any?>("label" to "NA", "proven" to (t % 7 == 0), "headline" to "Scores 50 of 100")
            val before = Json.stringify(call)
            val input = PanelInput(s, null, null, call, nifty, br, 500000.0, 1.0, 0.25, 0, s.d[s.n - 1])
            val txt = Json.stringify(Panel.build(input))
            T.eq(Json.stringify(call), before, "panel never changes the call")
            T.check(!txt.contains("NaN") && !txt.contains("Infinity"), "no NaN in panel")
            val pn = Json.parseObject(txt)!!
            val v = Panel.values(input)
            val ms = pn.list("managers").map { it.asObj() }
            for (m in ms) seen.getOrPut(m.str("id")) { HashSet() }.add(m.str("stance"))
            fun st(id: String) = ms.first { it.str("id") == id }.str("stance")
            val exp = HashMap<String, String>()
            if (!v.sma200.isNaN() && !v.slope200.isNaN() && !v.sma50.isNaN())
                exp["trend"] = if (v.close > v.sma50 && v.sma50 > v.sma200 && v.slope200 > 0) "FOR" else if (v.close < v.sma200 && v.slope200 < 0) "AGAINST" else "NEUTRAL"
            else exp["trend"] = "NEUTRAL"
            exp["liquidity"] = if (v.to20.isNaN()) "NEUTRAL" else if (v.to20 >= 100) "FOR" else if (v.to20 < 25) "AGAINST" else "NEUTRAL"
            exp["timing"] = if (v.stretch.isNaN()) "NEUTRAL" else if (v.stretch > 6) "AGAINST" else if (v.stretch in 0.0..2.0) "FOR" else "NEUTRAL"
            exp["market"] = if (v.niftyDist.isNaN()) "NEUTRAL" else if (v.niftyDist > 0 && br > 0.5) "FOR" else if (v.niftyDist < 0 && br < 0.4) "AGAINST" else "NEUTRAL"
            exp["newlisting"] = if (s.n < 253) "AGAINST" else "NEUTRAL"
            if (!v.vol.isNaN()) exp["risk"] = if (v.vol <= 0.02) "FOR" else if (v.vol >= 0.035) "AGAINST" else "NEUTRAL"
            if (!v.r6.isNaN()) { val a = if (v.r12.isNaN()) v.r6 else (v.r6 + v.r12) / 2; exp["momentum"] = if (a >= 0.25) "FOR" else if (a <= -0.05) "AGAINST" else "NEUTRAL" }
            else exp["momentum"] = "NEUTRAL"
            for ((id, e) in exp) T.eq(st(id), e, "rule for $id (series $t, ${s.n} bars)")
            V2Tests.panelChecks(linkedMapOf("panel" to pn, "call" to Json.parseObject(before), "bars" to s.n.toDouble(),
                "listing" to Json.parseObject(Json.stringify(mapOf("x" to Panel.listing(s, 0, s.d[s.n - 1]))))!!["x"]), "random $t")
            for (m in ms) if (m.str("rebuttal").isNotEmpty()) { checked++; T.check(Regex("[0-9]").containsMatchIn(m.str("rebuttal")), "rebuttal has real numbers: " + m.str("rebuttal")) }
        }
        T.check(checked > 20, "rebuttals were produced ($checked)")
        for (id in V2Tests.MANAGER_IDS.dropLast(1)) T.check(seen[id]!!.size == 3, "$id took all three stances: ${seen[id]}")
        // short histories
        val s160 = series(160, 0.002, 0.012)
        p = build(s160, listed = s160.d[0] - 3)
        T.eq(mgr(p, "trend").str("stance"), "NEUTRAL", "160 bars: trend cannot compute -> NEUTRAL")
        T.check(mgr(p, "trend").str("text").contains("needs 221"), "says why")
        T.check(mgr(p, "newlisting").str("stance") == "AGAINST" && mgr(p, "newlisting").int("strength") == 1 && mgr(p, "newlisting").str("text").contains(PanelTexts.BASE_RATE), "new listing AGAINST-1 with the base rate")
        T.check(mgr(p, "newlisting").str("text").startsWith("Listed "), "listing date from the stock list")
        val s5 = series(5, 0.0, 0.01)
        p = build(s5)
        T.check(p.list("managers").map { it.asObj() }.filter { it.str("id") != "newlisting" }.all { it.str("stance") == "NEUTRAL" && it.str("text").isNotBlank() }, "5 bars: every manager NEUTRAL and says so")
        T.check(p.obj("chair").str("flipUp").isNotEmpty(), "NA flipUp says what gets it rated")
        T.check(Panel.build(PanelInput(Series("E", 1), null, null, emptyMap(), null, Double.NaN, 0.0, 0.0, 0.0, 0, 0)).let { it["managers"] is List<*> }, "empty series: no throw")
        // chair flips with a real entry
        val comp = SetupScore.compute(up)
        val e = ScoreEntry("X", comp, DoubleArray(5) { 0.95 }, 95.0, 95.0, "BUY", "85-100", 3, 500)
        val pb = Json.parseObject(Json.stringify(Panel.build(PanelInput(up, e, null, linkedMapOf("label" to "BUY", "proven" to false, "headline" to "Scores 95"), null, Double.NaN, 5e5, 1.0, 0.25, 0, up.d[up.n - 1]))))!!
        T.check(pb.obj("chair").str("flipDown").contains(Fmt.rs(comp.sma50)) && pb.obj("chair").str("flipDown").contains("ranked 3 of 500") && pb.obj("chair").str("flipDown").contains(Fmt.rs(comp.sma200)),
            "BUY flipDown names ₹ levels and rank: " + pb.obj("chair").str("flipDown"))
        val w = ScoreEntry("X", comp, DoubleArray(5) { 0.6 }, 60.0, 60.0, "WAIT", "55-69", 200, 500)
        val pw = Json.parseObject(Json.stringify(Panel.build(PanelInput(up, w, null, linkedMapOf("label" to "WAIT", "proven" to false, "headline" to "Scores 60"), null, Double.NaN, 5e5, 1.0, 0.25, 0, up.d[up.n - 1]))))!!
        T.check(pw.obj("chair").str("flipUp").contains("ranked 200 of 500") && pw.obj("chair").str("flipDown").contains("200-day average " + Fmt.rs(comp.sma200)) && pw.obj("chair").str("flipDown").contains("AVOID"),
            "WAIT flips: " + pw.obj("chair").str("flipUp") + " / " + pw.obj("chair").str("flipDown"))
        val mom = pw.list("managers").map { it.asObj() }.first { it.str("id") == "momentum" }
        T.check(mom.str("stance") == "NEUTRAL" && mom.str("text").contains("ahead of about 60%"), "momentum uses the entry's percentiles (0.6 -> NEUTRAL): " + mom.str("text"))
    }

    // ------------------------------------------------------------------ panel inside stockInfo (percentiles vs the scan table)
    fun panelApp() {
        val p = V3Tests.bigPlatform()
        p.setTime(day(2026, 10, 7), 15 * 60 + 45)
        val app = SwingApp(p)
        var r = V2Tests.info(app, "UP01", "no table")
        T.eq(r.obj("panel").obj("chair").str("label"), "NA", "no ranking yet: chair NA")
        T.eq(act(app, "scanNow", "{}")["ok"], true, "scan")
        val t = app.debugScoreTable()!!
        var n = 0
        for (e in t.ranked.filterIndexed { i, _ -> i % 9 == 0 }) {
            r = V2Tests.info(app, e.sym, "ranked")
            if (r["ok"] != true) continue
            n++
            val m = r.obj("panel").list("managers").map { it.asObj() }
            val avg = (e.p[1] + e.p[2]) / 2
            T.eq(m[1].str("stance"), if (avg >= 0.7) "FOR" else if (avg <= 0.3) "AGAINST" else "NEUTRAL", "momentum stance from the table percentiles (${e.sym} ${"%.2f".format(avg)})")
            T.check(m[2].str("text").contains("liquid stocks"), "risk ranked against the universe: " + m[2].str("text"))
            T.check(m[5].str("stance") != "NEUTRAL" || m[5].str("text").contains("Nifty"), "market computed after a scan: " + m[5].str("text"))
            T.eq(r.obj("panel").int("asOfDay"), r.int("barDay"), "asOfDay = the page's bar day")
        }
        T.check(n >= 5, "checked $n ranked stocks")
        val s200 = V2Tests.info(app, "S200", "200 bars")
        T.check(s200.obj("listing").int("bars") == s200.int("bars") && s200.obj("call").str("label") == "NA", "200-bar stock: listing card, NA call")
        T.check(V2Tests.info(app, "NEWIPO", "30 bars").let { it.obj("listing").int("barsToRated") == 253 - it.int("bars") }, "30-bar stock: barsToRated")
    }

    // ------------------------------------------------------------------ listing block
    fun listing() {
        val s = series(160, 0.001, 0.02, start = 300.0, vol = 1e6, firstDay = day(2026, 3, 14))
        val listed = day(2026, 3, 14)
        val today = day(2026, 11, 12)
        val l = Json.parseObject(Json.stringify(Panel.listing(s, listed, today)!!))!!
        val errs = ArrayList<String>()
        T.eq(l.keys, V2Tests.LISTING.keys, "listing keys")
        T.check(l.values.none { it == null }, "no nulls")
        T.check(l.bool("isNew") && l.int("listedDay") == 20260314 && l.int("daysListed") == 243 && l.int("bars") == 160 && l.int("barsToRated") == 93, "listing basics: $l")
        T.near(l.num("listPrice"), Fmt.r2(s.o[0]), 1e-9, "listPrice = first open")
        T.near(l.num("ltp"), Fmt.r2(s.c[159]), 1e-9, "ltp")
        val hi = (0 until 160).maxByOrNull { s.h[it] }!!; val lo = (0 until 160).minByOrNull { s.l[it] }!!
        T.check(l.num("highSince") == Fmt.r2(s.h[hi]) && l.int("highDay") == StockPage.yyyymmdd(s.d[hi]) && l.num("lowSince") == Fmt.r2(s.l[lo]) && l.int("lowDay") == StockPage.yyyymmdd(s.d[lo]), "high/low since listing")
        T.check(l.num("rangePos") in 0.0..100.0 && l.num("maxDdPct") >= 0 && l.num("avgValueCr") > 0, "rangePos 0-100, drawdown, value")
        T.near(l.num("sinceListingPct"), Fmt.r2((s.c[159] / s.o[0] - 1) * 100), 1e-9, "since listing %")
        T.eq(l.str("baseRate"), V2Tests.BASE_RATE, "base-rate text exactly as SPEC5")
        T.check(Json.parseObject(Json.stringify(Panel.listing(s, 0, today)!!))!!.int("listedDay") == StockPage.yyyymmdd(s.d[0]), "unknown listing day -> first bar")
        T.check(Panel.listing(s, day(2025, 1, 1), today)!!["isNew"] == false, "listed > 365 days ago -> not new")
        T.check(Panel.listing(series(253, 0.0, 0.01), 0, today) == null && Panel.listing(series(600, 0.0, 0.01), 0, today) == null, "null at >= 253 bars")
        T.check(Panel.listing(series(252, 0.0, 0.01), 0, today) != null, "252 bars still a listing card")
        T.check(errs.isEmpty(), "")
        // stockInfo uses the stock list's listing day
        val p = FakePlatform.standard()
        p.assets["symbols.csv"] = "SYMBOL,NAME,LISTED,BOARD\nNEWIPO,New Listing Ltd,02-OCT-2026,SME\n"
        p.setTime(day(2026, 11, 12), 11 * 60)
        val r = V2Tests.info(SwingApp(p), "NEWIPO", "listed")
        T.check(r.obj("listing").int("listedDay") == 20261002 && r.obj("listing").int("daysListed") == day(2026, 11, 12) - day(2026, 10, 2), "listing day from the stock list: ${r["listing"]}")
    }

    // ------------------------------------------------------------------ refresh swap while searching
    fun concurrent() {
        val fp = FakePlatform.standard()
        fp.assets["symbols.csv"] = "SYMBOL,NAME,LISTED,BOARD\nOLDCO,Old Co Ltd,01-JAN-2000,EQ\n"
        val p = ListNet(fp)
        p.served[StockList.NSE_MAIN_URL] = nseMain(3000, extra = "NEWCO,New Co Ltd,EQ,01-NOV-2026,1,1,X,1\n")
        p.served[StockList.NSE_SME_URL] = nseSme(500)
        p.delayMs = 300
        fp.setTime(day(2026, 11, 12), 10 * 60)
        val app = SwingApp(p)
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        val stopF = java.util.concurrent.atomic.AtomicBoolean(false)
        var worst = 0.0; var count = 0
        val sawOld = java.util.concurrent.atomic.AtomicBoolean(false); val sawNew = java.util.concurrent.atomic.AtomicBoolean(false)
        val threads = (0 until 3).map { k ->
            Thread {
                while (!stopF.get()) {
                    val t0 = System.nanoTime()
                    val txt = app.action("search", if (k == 0) """{"q":"oldco"}""" else if (k == 1) """{"q":"newco"}""" else """{"q":"mb12"}""")
                    val ms = (System.nanoTime() - t0) / 1e6
                    synchronized(errs) { if (ms > worst) worst = ms; count++ }
                    val o = Json.parseObject(txt)
                    if (o == null || o["ok"] != true || o["list"] !is Map<*, *>) { errs.add(txt.take(80)); continue }
                    val src = o.obj("list").str("source")
                    val res = o.list("results").map { it.asObj().str("sym") }
                    if (k == 0) { if (res == listOf("OLDCO")) { sawOld.set(true); if (src != "bundled") errs.add("old rows with source $src") } }
                    if (k == 1 && res == listOf("NEWCO")) { sawNew.set(true); if (src != "nse") errs.add("new rows with source $src") }
                }
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(50)
        val a = Thread { app.action("refreshSymbols", "{}") }
        val b = Thread { app.tick("alarm") }
        a.start(); b.start(); a.join(30_000); b.join(30_000)
        Thread.sleep(50)
        stopF.set(true)
        threads.forEach { it.join(10_000) }
        T.check(errs.isEmpty(), "searches consistent during the swap: " + errs.take(3))
        T.check(sawOld.get() && sawNew.get(), "searches saw the old list, then the new one")
        T.check(worst < 250, "search never waited for the download (worst ${"%.1f".format(worst)} ms over $count calls)")
        T.eq(p.listHits.size, 2, "concurrent refresh + tick downloaded once")
        T.eq(syms(act(app, "search", """{"q":"newco"}""")), listOf("NEWCO"), "new list in place")
    }

    // ------------------------------------------------------------------ user's real case: wrong symbols, failed downloads
    fun resolveSymbols() {
        val f = java.io.File("app/src/main/assets/symbols.csv")
        if (!f.exists()) { T.check(false, "bundled symbols.csv readable from the repo root"); return }
        val ix = SymbolIndex.build(StockList.parse(f.readText()).rows, emptyList(), emptyMap(), null)
        T.check(ix.size > 3000, "real list loaded: " + ix.size)
        T.eq(ix.resolve("OLA"), "OLAELEC", "OLA -> OLAELEC (Ola Electric)")
        T.eq(ix.resolve("SESHAASAI"), "STYL", "SESHAASAI -> STYL (Seshaasai Technologies)")
        T.eq(ix.resolve("BEHARI LAL ENG"), "BLEL", "broker name -> BLEL")
        T.eq(ix.resolve("GLOBALHEALTH"), "MEDANTA", "old symbol GLOBALHEALTH -> MEDANTA (Global Health Ltd renamed its symbol)")
        T.eq(ix.resolve("MEDANTA"), "MEDANTA", "real symbol unchanged")
        T.eq(ix.resolve("BLEL"), "BLEL", "real symbol unchanged (2026 listing)")
        T.eq(ix.resolve("reliance"), "RELIANCE", "case-insensitive")
        T.eq(ix.resolve("TATA"), null, "ambiguous -> no guess")
        T.eq(ix.resolve("ZZQXW"), null, "unknown -> no guess")
        T.eq(ix.resolve(""), null, "empty -> null")
    }

    fun holdingRetry() {
        val p = FakePlatform.standard()
        val app = SwingApp(p)
        val d = day(2026, 10, 6)
        p.setTime(d, 10 * 60)
        p.offline = true
        app.action("addHolding", "{\"sym\":\"UP01\",\"qty\":10,\"avg\":100}")
        app.tick("alarm")
        var h = Json.parseObject(app.stateJson())!!.list("holdings").map { it.asObj() }.first { it.str("sym") == "UP01" }
        T.check(!h.bool("dataOk"), "offline: no data yet")
        // back online: a manual scan must load it at once, not "tomorrow"
        p.offline = false
        p.setTime(d, 10 * 60 + 5)
        act(app, "scanNow", "{}")
        h = Json.parseObject(app.stateJson())!!.list("holdings").map { it.asObj() }.first { it.str("sym") == "UP01" }
        T.check(h.bool("dataOk") && h.num("ltp") > 0, "after the failure, Scan now loads the holding's prices")
        // and the automatic path: a failed download is retried by a later scheduled scan
        val app2 = SwingApp(FakePlatform.standard().also { it.setTime(d, 10 * 60); it.offline = true })
        val p2 = FakePlatform.standard(); p2.setTime(d, 10 * 60)
        val a2 = SwingApp(p2)
        a2.action("addHolding", "{\"sym\":\"UP02\",\"qty\":5,\"avg\":100}")
        p2.offline = true; a2.tick("alarm")
        p2.offline = false
        var t = Clock.msAt(d, 10 * 60)
        repeat(4) { t = Clock.nextWake(t); p2.nowMs = t; a2.tick("alarm") }
        h = Json.parseObject(a2.stateJson())!!.list("holdings").map { it.asObj() }.first { it.str("sym") == "UP02" }
        T.check(h.bool("dataOk") && h.num("ltp") > 0, "a failed download is retried by later scheduled scans the same day")
        T.check(app2.stateJson().isNotEmpty(), "spare app ok")
    }
}
