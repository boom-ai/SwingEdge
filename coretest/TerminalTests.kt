package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** Markets terminal (core/Terminal.kt): parsers on documented shapes, breadth / movers maths, the desk end to end on fakes. */
object TerminalTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "term-global" to ::global, "term-nse" to ::nse, "term-tv" to ::tv, "term-scan-fallback" to ::scanFallback,
        "term-fii" to ::fii, "term-calendar" to ::calendar, "term-news" to ::news, "term-desk" to ::desk, "term-junk" to ::junk)

    const val YH = """{"chart":{"result":[{"meta":{"currency":"USD","symbol":"ES=F","regularMarketPrice":6012.5,"chartPreviousClose":5990.0,"regularMarketTime":1795760000},
      "timestamp":[1,2,3,4],"indicators":{"quote":[{"close":[5995.0,null,6005.25,6012.5],"open":[1,1,1,1]}]}}],"error":null}}"""

    fun global() {
        val g = Terminal.parseGlobal(Terminal.GLOBAL[1], YH)!!
        T.near(g["last"] as Double, 6012.5, 1e-9, "last"); T.near(g["chg"] as Double, 22.5, 1e-9, "chg"); T.near(g["pct"] as Double, 0.38, 1e-9, "pct")
        T.eq((g["spark"] as List<*>).size, 3, "spark skips nulls"); T.eq(g["ts"], 1795760000000L, "ts ms")
        T.check(Terminal.parseGlobal(Terminal.GLOBAL[0], """{"chart":{"result":null,"error":{"code":"Not Found"}}}""") == null, "error -> null")
        val noPrev = Terminal.parseGlobal(Terminal.GLOBAL[0], YH.replace("\"chartPreviousClose\":5990.0,", ""))!!
        T.check(noPrev["pct"] == null && noPrev["chg"] == null, "no previous close -> no change, not NaN")
        T.check(Terminal.globalUrl(Params.YAHOO_HOST_1, "DX-Y.NYB").contains("/v8/finance/chart/DX-Y.NYB?range=1d&interval=15m"), "url")
        val gf = Terminal.parseGift("""{"totalCount":1,"data":[{"s":"NSEIX:NIFTY1!","d":[26120.5,0.42,109.5]}]}""")!!
        T.near(gf["last"] as Double, 26120.5, 1e-9, "gift last"); T.check(Terminal.parseGift("""{"data":[]}""") == null, "gift empty")
    }

    const val ALL = """{"data":[{"key":"BROAD","index":"NIFTY 50","last":26000.5,"variation":120.1,"percentChange":0.46,"advances":"35","declines":"14","unchanged":"1"},
      {"index":"NIFTY MIDCAP 100","last":60000,"variation":-60,"percentChange":-0.1},{"index":"NIFTY SMALLCAP 100","last":19000,"variation":95,"percentChange":0.5},
      {"index":"NIFTY IT","last":38000,"variation":380,"percentChange":1.01,"advances":8,"declines":2},{"index":"NIFTY METAL","last":9000,"variation":-90,"percentChange":-1.0},
      {"index":"INDIA VIX","last":12.3,"variation":-0.2,"percentChange":-1.6},{"index":"BROKEN","last":"x"}],"advances":"1500"}"""

    fun nse() {
        val m = Terminal.parseAllIndices(ALL)
        T.eq(m.size, 6, "rows with a price"); T.eq(m["NIFTY 50"]!!.adv, 35, "advances as string")
        val sec = Terminal.sectorIndices(m)
        T.eq(sec.map { it["name"] }, listOf("IT", "Metal"), "sectoral, best first")
        val groww = listOf(linkedMapOf<String, Any?>("name" to "Nifty 50", "sym" to "NIFTY", "last" to 26010.0, "chg" to 130.0, "pct" to 0.5))
        val ind = Terminal.india(groww, m)
        T.eq(ind.map { it["name"] }, listOf("Nifty 50", "Midcap 100", "Smallcap 100", "India VIX"), "order + gaps from NSE")
        T.eq(ind[0]["src"], "live", "groww wins"); T.eq(ind[1]["src"], "nse", "nse fills")
        T.eq(Terminal.india(emptyList(), emptyMap()), emptyList<Any>(), "nothing")
    }

    fun tvRow(sym: String, close: Double, chg: Double, vol: Double, avg: Double, s50: Double, s200: Double, hi: Double, lo: Double, mcap: Double, sector: String, earn: Long?, high: Double, low: Double) =
        """{"s":"NSE:$sym","d":[$close,$chg,$vol,$avg,$s50,$s200,$hi,$lo,$mcap,"$sector",${earn ?: "null"},$high,$low]}"""

    fun tv() {
        val now = Clock.msAt(20784, 600)
        val earn = (now / 1000) + 3 * 86400
        val rows = listOf(
            tvRow("BIGUP", 110.0, 10.0, 1e6, 2e5, 100.0, 90.0, 111.0, 60.0, 5e11, "Finance", earn, 111.0, 100.0),   // new high, shock 5x, 11 Cr
            tvRow("DOWN", 50.0, -6.0, 2e6, 2e6, 60.0, 70.0, 100.0, 49.8, 2e11, "Finance", null, 53.0, 49.8),        // new low, 10 Cr
            tvRow("FLAT", 200.0, 0.0, 1e5, 1e5, 190.0, 210.0, 300.0, 100.0, 1e12, "Energy", null, 201.0, 199.0),
            tvRow("PENNY", 2.0, 19.0, 1e6, 1e6, 1.5, 1.0, 2.5, 1.0, 1e9, "Energy", null, 2.1, 1.8),                  // 0.2 Cr: not a mover
            """{"s":"NSE:BAD","d":[null,1,2]}""", """{"s":"","d":[1]}""")
        val text = """{"totalCount":6,"data":[${rows.joinToString(",")}]}"""
        val st = Terminal.fromTv(text)
        T.eq(st.size, 4, "rows with a close")
        val b = Terminal.breadth(st)
        T.eq(listOf(b["adv"], b["dec"], b["unch"], b["total"]), listOf(2, 1, 1, 4), "adv/dec/unch")
        T.near(b["above50"] as Double, 75.0, 1e-9, "% above 50 DMA"); T.near(b["above200"] as Double, 50.0, 1e-9, "% above 200 DMA")
        T.eq(b["hi52"], 1, "new highs"); T.eq(b["lo52"], 1, "new lows")
        val mv = Terminal.movers(st, { "$it Ltd" })
        T.eq((mv["gainers"] as List<*>).map { (it as Map<*, *>)["sym"] }, listOf("BIGUP"), "gainers liquid only")
        T.eq((mv["losers"] as List<*>).map { (it as Map<*, *>)["sym"] }, listOf("DOWN"), "losers")
        T.eq((mv["volume"] as List<*>).map { (it as Map<*, *>)["sym"] }, listOf("BIGUP"), "volume shockers")
        T.near(((mv["volume"] as List<*>)[0] as Map<*, *>)["volX"] as Double, 5.0, 1e-9, "5x volume")
        T.eq(((mv["gainers"] as List<*>)[0] as Map<*, *>)["name"], "BIGUP Ltd", "names")
        val sa = Terminal.sectorAgg(st + st, { null })
        T.eq(sa.map { it["name"] }, listOf("Finance", "Energy"), "sectors need 4 stocks, best first")
        val er = Terminal.earnings(st, Clock.epochDay(now), { it })
        T.eq(er.map { it["sym"] }, listOf("BIGUP"), "earnings within 3 weeks")
        T.eq(er[0]["date"], Terminal.iso(Clock.epochDay(now) + 3), "earnings date")
        T.check(TvScanner.body(Terminal.TV_COLS).contains("\"exchange\""), "body filters NSE")
    }

    fun scanFallback() {
        val p = FakePlatform.standard(6)
        val d = MarketScan.parse(SCAN)!!
        val st = Terminal.fromScan(d, mapOf("AAA" to FlowRow("AAA", 0.0, 0.0, 0.0, 0.0, 30.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "")))
        T.eq(st.map { it.sym }, listOf("AAA", "BBB"), "NSE rows only")
        T.near(st[0].pct, 10.0, 1e-9, "pct from prev close"); T.near(st[0].volX, 3.0, 1e-9, "volX from flows")
        T.check(Terminal.isNewHigh(st[0]), "high = 52w high"); T.check(Terminal.isNewLow(st[1]), "close within 1% of 52w low")
        T.eq(Terminal.fromScan(null, emptyMap()).size, 0, "no scan"); p.now()
    }

    val SCAN: String by lazy {
        val cols = MarketScan.COLS
        fun r(m: Map<String, String>) = cols.joinToString(",") { m[it] ?: "" }
        "#SWINGEDGE_SCAN,v=1,asof=2026-11-26,builtAt=1,gate=1,rows=3,nse=2,bse=1\n" + cols.joinToString(",") + "\n" +
            r(mapOf("SYMBOL" to "AAA", "EXCH" to "NSE", "NAME" to "Aaa", "SECTOR" to "IT", "CLOSE" to "110", "PREV" to "100", "HIGH" to "112", "HI252" to "112", "LO252" to "50", "SMA50" to "100", "SMA200" to "90", "TO20" to "10", "NBARS" to "300", "ASOF" to "20261126")) + "\n" +
            r(mapOf("SYMBOL" to "BBB", "EXCH" to "NSE", "NAME" to "Bbb", "CLOSE" to "50.2", "PREV" to "52", "HIGH" to "52", "HI252" to "90", "LO252" to "50", "TO20" to "8", "NBARS" to "300", "ASOF" to "20261126")) + "\n" +
            r(mapOf("SYMBOL" to "CCC", "EXCH" to "BSE", "NAME" to "Ccc", "CLOSE" to "10", "PREV" to "9", "NBARS" to "300", "ASOF" to "20261126")) + "\n"
    }

    fun fii() {
        val csv = "DATE,FII_BUY,FII_SELL,FII_NET,DII_BUY,DII_SELL,DII_NET\n2026-11-24,1,2,-1000.4,3,4,1500\n2026-11-25,1,2,500,3,4,-200\nbad\n"
        val h = Terminal.parseFiiCsv(csv); T.eq(h.size, 2, "csv rows")
        val live = Terminal.parseFiiNse("""[{"category":"DII **","date":"26-Nov-2026","buyValue":"12000.5","sellValue":"10000","netValue":"2000.5"},{"category":"FII/FPI **","date":"26-Nov-2026","buyValue":"9000","sellValue":"11000.25","netValue":"-2000.25"}]""")!!
        T.eq(live.day, Bhav.parseDay("2026-11-26"), "nse date"); T.near(live.fiiNet, -2000.25, 1e-9, "fii net"); T.near(live.diiNet, 2000.5, 1e-9, "dii net")
        val v = Terminal.fiiView(h, live)
        T.eq(v["date"], "2026-11-26", "latest first"); T.eq(v["fii5"], -2501.0, "5-day FII sum (rounded)"); T.eq(v["dii5"], 3300.0, "5-day DII sum")
        T.eq((v["days"] as List<*>).size, 3, "days")
        T.eq(Terminal.fiiView(emptyList(), null)["ready"], false, "empty")
        T.check(Terminal.parseFiiNse("""{"x":1}""") == null && Terminal.parseFiiCsv("junk").isEmpty(), "junk")
    }

    fun calendar() {
        val today = Bhav.parseDay("2026-11-27")
        val ev = Terminal.parseEvents("DATE,SYMBOL,KIND,DETAIL\n2026-11-26,OLD,BOARD,x\n2026-12-01,TCS,DIVIDEND,\"Interim Dividend - Rs 10, per share\"\n2026-11-28,INFY,BOARD,Financial Results\n2027-06-01,FAR,BOARD,x\n", today, { "$it Ltd" })
        T.eq(ev.map { it["sym"] }, listOf("INFY", "TCS"), "window + order"); T.eq(ev[1]["detail"], "Interim Dividend - Rs 10, per share", "quoted detail")
        T.eq(Terminal.parseHolidays("""["2026-11-20","2026-12-25"]""", today).map { it["date"] }, listOf("2026-12-25"), "array of dates")
        T.eq(Terminal.parseHolidays("""{"holidays":[{"date":"25-Dec-2026","description":"Christmas"}]}""", today)[0]["name"], "Christmas", "objects")
        T.eq(Terminal.parseHolidays("""{"2026-12-25":"Christmas","2027-01-26":"Republic Day"}""", today).size, 2, "map form")
        T.eq(Terminal.parseHolidays("nope", today).size, 0, "junk")
        val now = java.time.OffsetDateTime.parse("2026-11-27T00:00:00+05:30").toInstant().toEpochMilli()
        val ec = Terminal.parseEcon("""[{"title":"Non-Farm Employment Change","country":"USD","date":"2026-12-04T08:30:00-05:00","impact":"High","forecast":"150K","previous":"120K"},
           {"title":"Low thing","country":"USD","date":"2026-12-04T08:30:00-05:00","impact":"Low"},{"title":"Old","country":"USD","date":"2026-11-20T08:30:00-05:00","impact":"High"},
           {"title":"AU","country":"AUD","date":"2026-12-04T08:30:00-05:00","impact":"High"}]""", now)
        T.eq(ec.map { it["title"] }, listOf("Non-Farm Employment Change"), "high/medium, majors, upcoming")
    }

    fun news() {
        val rss = """<rss><channel><title>x</title><item><title>Sensex jumps 500 pts; Nifty at record - Moneycontrol</title><link>https://news.google.com/rss/articles/abc?oc=5</link>
          <pubDate>Fri, 27 Nov 2026 05:30:00 GMT</pubDate><source url="https://www.moneycontrol.com">Moneycontrol</source></item>
          <item><title>Older &amp; wiser - ET</title><link>https://x.test/2</link><pubDate>Thu, 26 Nov 2026 05:30:00 GMT</pubDate><source url="x">ET</source></item>
          <item><title><![CDATA[<script>bad</script>Rupee falls]]></title><link>javascript:alert(1)</link></item></channel></rss>"""
        val dup = rss.replace("Older &amp; wiser - ET", "Sensex jumps 500 pts; Nifty at record - Moneycontrol")
        val l = Terminal.parseNews(listOf(rss, dup, null))
        T.eq(l.map { it["title"] }, listOf("Sensex jumps 500 pts; Nifty at record", "Older & wiser"), "titles, source stripped, deduped, newest first, https only")
        T.eq(l[0]["src"], "Moneycontrol", "source"); T.eq(l[0]["at"], java.time.Instant.parse("2026-11-27T05:30:00Z").toEpochMilli(), "time")
    }

    fun desk() {
        val p = FakePlatform.standard(6)
        p.setTime(20784, 11 * 60)
        val g = Terminal.GLOBAL
        for (x in g) p.served[Terminal.globalUrl(Params.YAHOO_HOST_1, x.sym)] = YH
        p.served[MarketsDesk.FILES_BASE + "fiidii.csv"] = "DATE,FII_BUY,FII_SELL,FII_NET,DII_BUY,DII_SELL,DII_NET\n2026-11-25,1,2,-700,3,4,900\n"
        p.served[MarketsDesk.FILES_BASE + "events.csv"] = "DATE,SYMBOL,KIND,DETAIL\n" + Terminal.iso(20786) + ",TCS,BOARD,Financial Results\n"
        p.served[MarketsDesk.FILES_BASE + "holidays.json"] = """["${Terminal.iso(20800)}"]"""
        val tvBody = """{"totalCount":600,"data":[${(1..600).joinToString(",") { tvRow("S$it", 100.0 + it, if (it % 3 == 0) -1.0 else 1.0, 1e6, 4e5, 100.0, 95.0, 200.0, 50.0, 1e11 * it, if (it % 2 == 0) "Finance" else "Energy", null, 102.0 + it, 99.0) }}]}"""
        var tvCalls = 0
        p.reqHandler = { r -> if (r.url == TvScanner.URL) { tvCalls++; T.eq(r.headers["Origin"], "https://in.tradingview.com", "origin header"); HttpResp(200, tvBody) } else null }
        val names = { s: String -> "$s Ltd" }
        val d = MarketsDesk(p, { path -> if (path == "/api/allIndices") ALL else null }, { emptyList() }, { MarketScan.parse(SCAN) }, { emptyMap() }, names)
        val v = d.view("overview", false)
        T.eq(v["ok"], true, "ok")
        T.eq((v["global"] as List<*>).size, g.size, "all global tiles")
        T.eq((v["india"] as List<*>).map { (it as Map<*, *>)["name"] }, listOf("Nifty 50", "Midcap 100", "Smallcap 100", "India VIX"), "india from NSE when Groww is down")
        val b = v["breadth"] as Map<*, *>
        T.eq(b["total"], 600, "TradingView breadth"); T.eq(b["dec"], 200, "decliners"); T.eq((v["src"] as Map<*, *>)["stocks"], "tv", "source tv")
        T.eq((v["fii"] as Map<*, *>)["fii"], -700.0, "fii from csv")
        T.eq((v["events"] as List<*>).map { (it as Map<*, *>)["sym"] }, listOf("TCS"), "events")
        T.eq((v["holidays"] as List<*>).size, 1, "holiday")
        T.eq(p.files["mk_fiidii.csv"]?.startsWith("DATE"), true, "files saved")
        // a second call inside the ages fetches nothing new
        val calls = p.httpCalls; d.view("overview", false); T.eq(p.httpCalls, calls, "cached"); T.eq(tvCalls, 1, "tv once")
        // TradingView down -> end-of-day scan fallback
        p.reqHandler = { null }
        val d2 = MarketsDesk(p, { null }, { emptyList() }, { MarketScan.parse(SCAN) }, { emptyMap() }, names)
        val v2 = d2.view("breadth", false)
        T.eq((v2["src"] as Map<*, *>)["stocks"], "scan", "fallback source"); T.eq((v2["breadth"] as Map<*, *>)["total"], 2, "scan breadth")
        T.eq((v2["src"] as Map<*, *>)["stocksAsof"], "2026-11-26", "as-of date shown")
        // fully offline: still answers
        p.offline = true
        val d3 = MarketsDesk(p, { null }, { emptyList() }, { null }, { emptyMap() }, names)
        val v3 = d3.view("news", true); T.eq(v3["ok"], true, "offline ok"); T.eq((v3["news"] as List<*>).size, 0, "no news")
        T.check(Json.stringify(v3).isNotEmpty() && !Json.stringify(v).contains("NaN"), "serialisable, no NaN")
    }

    fun junk() {
        for (t in listOf(null, "", "{", "[]", "null", "{\"data\":5}", "{\"data\":[{\"s\":5,\"d\":\"x\"}]}", "<rss><item>")) {
            Terminal.parseGlobal(Terminal.GLOBAL[0], t); Terminal.parseAllIndices(t); Terminal.fromTv(t); Terminal.parseFiiNse(t)
            Terminal.parseHolidays(t, 1); Terminal.parseEcon(t, 0); Terminal.parseNews(listOf(t)); Terminal.parseGift(t)
        }
        T.check(true, "no throw on junk")
    }
}
