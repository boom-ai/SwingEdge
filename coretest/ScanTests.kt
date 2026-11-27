package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** Whole-market scan: bhavcopy parsing (NSE + BSE), history merge / adjustment, batch-vs-app scoring parity, app ingestion. */
object ScanTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "scan-bhav-parse" to ::parse, "scan-history-merge" to ::merge, "scan-batch-parity" to ::parity, "scan-app-ingest" to ::ingest)

    const val UDIFF_HDR = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4"

    private fun iso(day: Int) = java.time.LocalDate.ofEpochDay(day.toLong()).toString()

    /** One UDiFF line. */
    fun udiff(day: Int, src: String, id: String, isin: String, sym: String, ser: String, name: String,
              o: Double, h: Double, l: Double, c: Double, prev: Double, vol: Double, tp: String = "STK"): String =
        "${iso(day)},${iso(day)},CM,$src,$tp,$id,$isin,$sym,$ser,,,,,$name,$o,$h,$l,$c,$c,$prev,,$c,,,${vol.toLong()},${c * vol},100,F1,1,,,,,"

    fun parse() {
        val d = java.time.LocalDate.of(2026, 11, 27).toEpochDay().toInt()
        val nse = UDIFF_HDR + "\r\n" +
            udiff(d, "NSE", "2885", "INE002A01018", "RELIANCE", "EQ", "RELIANCE INDUSTRIES LTD", 1500.0, 1520.5, 1490.0, 1510.25, 1498.0, 1_000_000.0) + "\r\n" +
            udiff(d, "NSE", "999", "INE999Z01011", "TTFT", "BE", "T FOR T LTD", 10.0, 11.0, 9.5, 10.5, 10.0, 500.0) + "\r\n" +
            udiff(d, "NSE", "998", "INE998Z07011", "NCDX", "N1", "SOME NCD", 1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 5.0) + "\r\n" +
            udiff(d, "NSE", "997", "INF997Z01011", "NIFTYBEES", "EQ", "ETF", 280.0, 281.0, 279.0, 280.5, 280.0, 9.0) + "\r\n"
        val b = Bhav.parse(nse)!!
        T.eq(b.exch, "NSE", "NSE UDiFF detected"); T.eq(b.format, "udiff", "format"); T.eq(b.day, d, "trade date")
        T.eq(b.rows.map { it.sym }, listOf("RELIANCE", "TTFT", "NIFTYBEES"), "EQ/BE kept, N1 debt dropped (NSE keeps every EQ row)")
        val r = b.rows[0]
        T.eq(r.isin, "INE002A01018", "isin"); T.eq(r.c, 1510.25, "close"); T.eq(r.prev, 1498.0, "prev close"); T.eq(r.vol, 1_000_000.0, "volume")
        T.eq(r.h, 1520.5, "high"); T.eq(r.name, "RELIANCE INDUSTRIES LTD", "name")

        val bse = UDIFF_HDR + "\n" +
            udiff(d, "BSE", "500325", "INE002A01018", "RELIANCE", "A", "RELIANCE INDUSTRIES LTD.", 1501.0, 1519.0, 1491.0, 1510.0, 1498.1, 90_000.0) + "\n" +
            udiff(d, "BSE", "590001", "INE590A01015", "THINKINK", "X", "THINK INK, LTD", 30.0, 31.0, 29.0, 30.5, 30.0, 1200.0) + "\n" +
            udiff(d, "BSE", "590002", "INF590A01019", "SOMEETF", "F", "ETF", 30.0, 31.0, 29.0, 30.5, 30.0, 1200.0) + "\n" +
            udiff(d, "BSE", "590003", "INE590B07015", "NCD", "F", "DEBENTURE", 1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 3.0) + "\n" +
            udiff(d, "BSE", "590004", "INE590C01015", "MFUND", "A", "x", 10.0, 10.0, 10.0, 10.0, 10.0, 3.0, tp = "MF") + "\n"
        val bb = Bhav.parse(bse)!!
        T.eq(bb.exch, "BSE", "BSE UDiFF detected")
        T.eq(bb.rows.map { it.code }, listOf("500325", "590001"), "BSE: only equity-share ISINs (INE…01…) of STK instruments")
        T.eq(bb.rows[1].sym, "THINKINK", "BSE ticker"); T.eq(bb.rows[1].series, "X", "BSE group")
        T.check(Bhav.parse(UDIFF_HDR.replace(",", ";")) == null && Bhav.parse("<html>Too many requests</html>" + " ".repeat(80)) == null, "garbage rejected")

        val full = "SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE, CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY, DELIV_PER\n" +
            "RELIANCE, EQ, 27-Nov-2026, 1498.00, 1500.00, 1520.50, 1490.00, 1510.00, 1510.25, 1505.1, 1000000, 15051.00, 50000, 400000, 40.00\n" +
            "RELIANCE, BL, 27-Nov-2026, 1498.00, 1500.00, 1520.50, 1490.00, 1510.00, 1510.25, 1505.1, 1000, 15.00, 5, -, -\n"
        val f = Bhav.parse(full, mapOf("RELIANCE" to "INE002A01018"))!!
        T.eq(f.format, "nsefull", "sec_bhavdata_full detected"); T.eq(f.day, d, "DATE1")
        T.eq(f.rows.size, 1, "block-deal series dropped"); T.eq(f.rows[0].isin, "INE002A01018", "ISIN from EQUITY_L")
        T.near(f.rows[0].turnover, 15051.0 * 1e5, 1e-3, "turnover lakhs -> rupees")
        T.eq(Bhav.isinMap("SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING, PAID UP VALUE, MARKET LOT, ISIN NUMBER, FACE VALUE\nRELIANCE,Reliance Industries Limited,EQ,29-NOV-1995,10,1,INE002A01018,10\n"),
            mapOf("RELIANCE" to "INE002A01018"), "EQUITY_L isin map")
        val old = "SC_CODE,SC_NAME,SC_GROUP,SC_TYPE,OPEN,HIGH,LOW,CLOSE,LAST,PREVCLOSE,NO_TRADES,NO_OF_SHRS,NET_TURNOV,TDCLOINDI,ISIN_CODE,TRADING_DATE,FILLER2,FILLER3\n" +
            "500325,RELIANCE    ,A ,Q,1500.00,1520.00,1490.00,1510.00,1510.00,1498.00,1000,90000,135900000.00,,INE002A01018,27-Nov-26,,\n"
        val o = Bhav.parse(old)!!
        T.eq(o.format, "bseold", "old BSE format"); T.eq(o.day, d, "TRADING_DATE 2-digit year"); T.eq(o.rows[0].code, "500325", "scrip code")
        T.check(Bhav.equityIsin("INE002A01018") && !Bhav.equityIsin("INE002A07018") && !Bhav.equityIsin("INF204K01EY0"), "equity ISIN rule")
        T.eq(Bhav.parseDay("20261127"), d, "yyyymmdd")
    }

    private fun nseDay(day: Int, rows: List<String>) = Bhav.parse(UDIFF_HDR + "\n" + rows.joinToString("\n"))!!.let { BhavDay(day, it.exch, it.rows, it.format) }

    fun merge() {
        val days = (0 until 40).map { 20000 + it }
        val files = ArrayList<BhavDay>()
        for ((k, d) in days.withIndex()) {
            val n = ArrayList<String>(); val b = ArrayList<String>()
            // SPLT: 1:2 split on session 20 (exchange halves the previous close on the ex-date)
            val adj = 100.0 + k
            val raw = if (k < 20) adj * 2 else adj
            val prevRaw = if (k == 0) raw else if (k == 20) (100.0 + k - 1) else (if (k - 1 < 20) (100.0 + k - 1) * 2 else 100.0 + k - 1)
            n.add(udiff(d, "NSE", "1", "INE111A01011", "SPLT", "EQ", "SPLIT LTD", raw, raw, raw, raw, prevRaw, 1000.0))
            // DUAL on both exchanges: kept once, as the NSE symbol
            n.add(udiff(d, "NSE", "2", "INE222A01011", "DUAL", "EQ", "DUAL LTD", 50.0, 50.0, 50.0, 50.0, 50.0, 10.0))
            b.add(udiff(d, "BSE", "500002", "INE222A01011", "DUAL", "A", "DUAL LTD", 50.0, 50.0, 50.0, 50.0, 50.0, 10.0))
            // MOVED: on BSE all along, on NSE from session 30 -> NSE symbol with BSE bars before
            if (k >= 30) n.add(udiff(d, "NSE", "3", "INE333A01011", "MOVED", "EQ", "MOVED LTD", 70.0, 70.0, 70.0, 70.0, if (k == 30) 60.0 else 70.0, 10.0))
            b.add(udiff(d, "BSE", "500003", "INE333A01011", "MOVEDB", "B", "MOVED LTD", 69.0, 69.0, 69.0, 69.0, 69.0, 5.0))
            // BSE-only: one with a clean ticker, one whose ticker is an NSE symbol of another company, one with a bad ticker
            b.add(udiff(d, "BSE", "590001", "INE590A01015", "THINKINK", "X", "THINK INK LTD", 30.0, 31.0, 29.0, 30.5, 30.5, 1200.0))
            b.add(udiff(d, "BSE", "590005", "INE590E01015", "SPLT", "X", "OTHER SPLT LTD", 9.0, 9.0, 9.0, 9.0, 9.0, 100.0))
            b.add(udiff(d, "BSE", "590006", "INE590F01015", "BAD.SYM", "X", "BAD SYM LTD", 9.0, 9.0, 9.0, 9.0, 9.0, 100.0))
            // GONE: stopped trading 15 sessions ago -> not listed
            if (k < 25) n.add(udiff(d, "NSE", "4", "INE444A01011", "GONE", "EQ", "GONE LTD", 20.0, 20.0, 20.0, 20.0, 20.0, 10.0))
            // TWOSER: EQ and BE rows on one day -> EQ preferred
            n.add(udiff(d, "NSE", "5", "INE555A01011", "TWOSER", "BE", "TWO LTD", 11.0, 11.0, 11.0, 11.0, 11.0, 1.0))
            if (k == 39) n.add(udiff(d, "NSE", "5", "INE555A01011", "TWOSER", "EQ", "TWO LTD", 12.0, 12.0, 12.0, 12.0, 11.0, 1.0))
            if (k != 7) files.add(nseDay(d, n))                    // session 7: NSE file missing -> the BSE file of that day is ignored
            if (k != 12) files.add(nseDay(d, b))                   // session 12: BSE file missing -> BSE stocks just have no bar
        }
        files.add(files[3])                                        // a duplicate file is harmless
        val st = MarketHistory.build(files, mapOf("SPLT" to "Split Limited"), setOf("SPLT", "DUAL"), 30)
        val by = st.associateBy { it.sym }
        T.eq(st.map { it.sym }.sorted(), listOf("590005", "590006", "DUAL", "MOVED", "SPLT", "THINKINK", "TWOSER"), "symbols: dual kept once, clash -> code, bad ticker -> code, GONE dropped")
        val s = by["SPLT"]!!
        T.eq(s.name, "Split Limited", "name from the stock list"); T.eq(s.adjustments, 1, "one split found")
        T.eq(s.s.n, 30, "capped at 30 sessions (39 days with an NSE file, last 30 kept)")
        var cont = true
        for (k in 1 until s.s.n) if (Math.abs(s.s.c[k] - s.s.c[k - 1] - (s.s.d[k] - s.s.d[k - 1])) > 1e-9) cont = false
        T.check(cont, "split-adjusted closes continue smoothly: " + s.s.c.take(s.s.n))
        T.near(s.s.v[0], 2000.0, 1e-9, "volume before the split doubled")
        T.near(s.s.c[0] * s.s.v[0], (100.0 + (s.s.d[0] - 20000)) * 2 * 1000.0, 1e-6, "close x volume = printed turnover")
        T.eq(by["DUAL"]!!.exch, "NSE", "dual listing is NSE"); T.eq(by["DUAL"]!!.s.n, 30, "dual: NSE bars only")
        val mv = by["MOVED"]!!
        T.eq(mv.code, "500003", "moved: BSE code kept"); T.eq(mv.s.n, 29, "moved: 20 BSE bars (one BSE file missing) + 10 NSE bars")
        T.eq(mv.adjustments, 0, "no adjustment across the BSE->NSE junction"); T.eq(mv.s.c[0], 69.0, "BSE part unscaled")
        val th = by["THINKINK"]!!
        T.eq(th.exch, "BSE", "BSE-only"); T.eq(th.code, "590001", "code"); T.eq(th.s.n, 29, "no bar for the missing BSE file, none for the NSE-less day")
        T.check(th.s.d.take(th.s.n).none { it == 20007 } && s.s.d.take(s.s.n).none { it == 20007 }, "a day without an NSE file is not a session")
        T.eq(by["TWOSER"]!!.s.c[by["TWOSER"]!!.s.n - 1], 12.0, "EQ row preferred over BE")
        T.eq(MarketHistory.build(files.filter { it.exch == "BSE" }), emptyList<MarketStock>(), "no NSE file -> nothing")
    }

    /** Deterministic synthetic stock: 2-decimal prices, liquid unless [vol] small. */
    fun synth(sym: String, seed: Int, days: List<Int>, drift: Double, vol: Double = 3e6): Series {
        val r = java.util.Random(seed.toLong())
        val s = Series(sym, days.size)
        var p = 80.0 + r.nextInt(400)
        for (d in days) {
            val o = Fmt.r2(p * (1 + r.nextGaussian() * 0.004))
            p *= 1 + drift + r.nextGaussian() * 0.015
            val c = Fmt.r2(p)
            val h = Fmt.r2(maxOf(o, c) * (1 + Math.abs(r.nextGaussian()) * 0.006))
            val l = Fmt.r2(minOf(o, c) * (1 - Math.abs(r.nextGaussian()) * 0.006))
            s.add(d, o, h, l, c, Math.rint(vol * (0.5 + r.nextDouble())))
        }
        return s
    }

    fun weekdays(last: Int, n: Int): List<Int> { val out = ArrayList<Int>(); var d = last; while (out.size < n) { if (Clock.isWeekday(d)) out.add(d); d-- }; return out.reversed() }

    /** Bhavcopy files (NSE UDiFF) for [ss]; the stock at [splitSym] is printed unadjusted with a 1:2 split at [splitAt]. */
    private fun files(ss: List<Series>, days: List<Int>, splitSym: String, splitAt: Int): List<BhavDay> = days.withIndex().map { (k, d) ->
        val lines = ArrayList<String>()
        for ((j, s) in ss.withIndex()) {
            val i = s.indexOf(d); if (i < 0) continue
            val m = if (s.sym == splitSym && k < splitAt) 2.0 else 1.0
            val pm = if (s.sym == splitSym && k - 1 < splitAt && k != splitAt) 2.0 else 1.0
            val prev = if (i > 0) Fmt.r2(s.c[i - 1] * pm) else s.c[i]
            lines.add(udiff(d, "NSE", "$j", "INE%03dA01011".format(j), s.sym, "EQ", "${s.sym} LTD", Fmt.r2(s.o[i] * m), Fmt.r2(s.h[i] * m), Fmt.r2(s.l[i] * m), Fmt.r2(s.c[i] * m), prev, s.v[i] / m))
        }
        nseDay(d, lines)
    }

    fun parity() {
        val days = weekdays(20770, 270)
        val ss = ArrayList<Series>()
        for (k in 0 until 140) ss.add(synth("S%03d".format(k), k, days, (k % 7 - 2) * 0.0006))
        ss.add(synth("THIN", 999, days, 0.002, vol = 2000.0))                       // illiquid
        ss.add(synth("YOUNG", 998, days.takeLast(150), 0.002))                      // too short to rate
        val split = ss[3].sym
        val app = SetupScore.buildTable(ss.map { Pair(it.sym, SetupScore.compute(it)) }, 1L)       // what the phone computes on adjusted series
        val stocks = MarketHistory.build(files(ss, days, split, 200), emptyMap(), emptySet())
        T.eq(stocks.size, ss.size, "every stock built"); T.eq(stocks.first { it.sym == split }.adjustments, 1, "split detected")
        val job = MarketScan.score(stocks, mapOf("S001" to "Banks"), 1L)
        T.check(app.usable && app.rated >= 100, "table usable: ${app.rated}")
        // the job's series equal the originals (exactly, except the split stock: back-adjusted by a float factor)
        var exact = 0; var worst = 0.0
        for (x in stocks) { val o = ss.first { it.sym == x.sym }; T.eq(x.s.n, o.n, "bars ${x.sym}"); for (i in 0 until o.n) { val dd = Math.abs(x.s.c[i] - o.c[i]); if (dd > worst) worst = dd }; if (x.sym != split && (0 until o.n).all { x.s.c[it] == o.c[it] && x.s.v[it] == o.v[it] && x.s.h[it] == o.h[it] }) exact++ }
        T.eq(exact, ss.size - 1, "bit-identical series for every unsplit stock"); T.check(worst < 1e-9, "split stock within 1e-9: $worst")
        // round trip through the file and parity of every entry
        val text = MarketScan.encode(job)
        val back = MarketScan.parse(text)!!
        T.eq(back.rows.size, job.rows.size, "rows round-trip"); T.eq(back.day, days.last(), "asof"); T.eq(back.gate, job.gate, "gate")
        T.eq(back.bySym["S001"]!!.sector, "Banks", "sector")
        val bt = back.table
        var same = 0; var diff = ArrayList<String>()
        for ((sym, e) in app.entries) {
            val b = bt.entries[sym] ?: continue
            val tol = if (sym == split) 0.11 else 0.0
            if (e.label == b.label && Math.abs(e.score - b.score) <= tol && (sym == split || e.rank == b.rank)) same++ else diff.add("$sym ${e.score}/${b.score} ${e.label}/${b.label}")
        }
        T.eq(same, app.entries.size, "batch table == app table for every stock: $diff")
        T.eq(bt.rated, app.rated, "rated count")
        for (r in back.rows) { val e = bt.entries[r.sym]!!; if (e.rated) T.check(r.score == e.score && r.label == e.label && r.rank == e.rank, "csv score/label/rank as rebuilt: ${r.sym}") }
        T.eq(back.bySym["THIN"]!!.label, "NA", "illiquid not rated"); T.eq(back.bySym["YOUNG"]!!.label, "NA", "short history not rated")
        val clA = { s: String -> Clenow.score(ss.first { it.sym == s }) }
        val clB = { s: String -> back.bySym[s]!!.clenow }
        val ia = MarketScan.ideaOrder(app, clA, Params.IDEAS_MAX).map { it.sym }
        val ib = MarketScan.ideaOrder(bt, clB, Params.IDEAS_MAX).map { it.sym }
        T.check(ia.isNotEmpty(), "some ideas"); T.eq(ib, ia, "same ideas in the same order")
        // levels column = BuyCall.levels of the entry
        val top = bt.entries[ia[0]]!!
        val lv = BuyCall.levels(top)
        val line = text.split('\n').first { it.startsWith(ia[0] + ",") }.split(',')
        val ix = MarketScan.COLS.indexOf("STOP")
        T.eq(line[ix].toDouble(), lv[2], "stop column = app stop"); T.eq(line[ix + 1].toDouble(), lv[3], "t1 column = app t1")
        T.check(back.bySym[ia[0]]!!.spark.size == MarketScan.SPARK_N, "top rows carry a spark")
        T.check(MarketScan.parse(text.replaceFirst("v=1", "v=9")) == null && MarketScan.parse(text, 100000) == null && MarketScan.parse("SYMBOL,X\n") == null, "bad version / too few rows / not a scan file rejected")
    }

    /** A scan file of [n] synthetic stocks ending on [last], including BSE-only BSEONLY1 (scrip 590001). */
    fun scanText(last: Int, n: Int): String {
        val days = weekdays(last, 270)
        val stocks = ArrayList<MarketStock>()
        for (k in 0 until n) stocks.add(MarketStock("MK%03d".format(k), "NSE", "", "", "EQ", "Market $k Ltd", synth("MK%03d".format(k), 5000 + k, days, (k % 9 - 2) * 0.0007), 0))
        stocks.add(MarketStock("BSEONLY1", "BSE", "590001", "INE590A01015", "X", "Bse Only, Ltd", synth("BSEONLY1", 77, days, 0.004), 0))
        return MarketScan.encode(MarketScan.score(stocks, emptyMap(), 5L))
    }

    fun ingest() {
        try {
            val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()
            val p = FakePlatform.standard(); p.setTime(day, 16 * 60)
            p.yahoo.profiles["590001"] = Profile(0.2, 1.0); p.yahoo.bseOnly.add("590001"); p.yahoo.profiles["MK010"] = Profile(0.1, 1.0)
            p.served[MarketScan.URL] = "<html>rate limited</html>"
            val app0 = SwingApp(p)
            app0.tick("manual")
            val s0 = Json.parseObject(app0.stateJson())!!
            T.check(s0["marketScan"] == null && p.files[MarketScan.FILE] == null, "garbage download: nothing installed or saved")
            val ideas0 = s0.list("ideas").map { it.asObj().str("sym") }

            p.served[MarketScan.URL] = scanText(day - 1, 620)
            p.setTime(day, 16 * 60 + 10)
            val app = SwingApp(p)
            app.tick("manual")
            val st = Json.parseObject(app.stateJson())!!
            val ms = st.obj("marketScan")
            T.eq(ms.int("stocks"), 621, "whole-market count in state"); T.eq(ms.int("bse"), 1, "BSE count"); T.eq(ms.int("asof"), StockPage.yyyymmdd(day - 1), "as of")
            T.check(st.str("lastScanStatus").contains("whole market 621 stocks"), "status mentions the whole market: " + st.str("lastScanStatus"))
            T.check(p.files[MarketScan.FILE] != null, "scan saved")
            val ideas = st.list("ideas").map { it.asObj() }
            T.check(ideas.any { it.bool("wholeMarket") } , "ideas include whole-market stocks: " + ideas.map { it.str("sym") })
            T.check(ideas.size == Params.IDEAS_MAX || ideas.size >= ideas0.size, "ideas list filled")
            T.check(ideas.filter { it.bool("wholeMarket") }.all { it.list("spark").size == MarketScan.SPARK_N && it.num("stop") > 0 && it.str("label") == "BUY" }, "market ideas have spark and levels")
            val t = app.debugScoreTable()!!
            T.check(t.entries.containsKey("UP01") && t.entries.containsKey("MK001") && t.rated > 600, "merged table: phone universe + market (${t.rated} rated)")
            T.eq(Yahoo.ticker("BSEONLY1"), "590001.BO", "BSE-only stock fetched at its scrip code on BSE")
            val sr = Json.parseObject(app.action("search", "{\"q\":\"BSEONLY\"}"))!!.list("results").map { it.asObj() }
            T.check(sr.any { it.str("sym") == "BSEONLY1" && it.str("board") == "BSE" && it.str("name") == "Bse Only, Ltd" }, "BSE-only stock found in search: $sr")
            T.check(Json.parseObject(app.action("search", "{\"q\":\"MK123\"}"))!!.list("results").isNotEmpty(), "market NSE stock found in search")
            val si = Json.parseObject(app.action("stockInfo", "{\"sym\":\"BSEONLY1\"}"))!!
            T.check(si.bool("ok") || si.str("sym") == "BSEONLY1" && si["error"] == null, "BSE-only stock page opens: " + si.str("error"))
            T.check(si.obj("call").str("label").isNotEmpty(), "BSE-only stock gets a call")
            val mi = Json.parseObject(app.action("stockInfo", "{\"sym\":\"MK010\"}"))!!
            T.check(mi["error"] == null, "market NSE stock page opens: " + mi.str("error"))

            // a later short / broken download keeps the last good file; a fresh app reads the saved copy
            p.served[MarketScan.URL] = scanText(day, 30)
            p.setTime(day, 16 * 60 + 30)
            app.tick("manual")
            T.eq(Json.parseObject(app.stateJson())!!.obj("marketScan").int("stocks"), 621, "too-small download rejected")
            p.offline = true
            val app2 = SwingApp(p)
            T.check(Json.parseObject(app2.action("search", "{\"q\":\"BSEONLY1\"}"))!!.list("results").any { it.asObj().str("sym") == "BSEONLY1" }, "offline: saved scan feeds search")
            p.offline = false
            // a stale scan (older than MARKET_MAX_AGE_DAYS) is not used for ranking
            p.setTime(day + 14, 16 * 60)
            val app3 = SwingApp(p)
            app3.tick("manual")
            T.check(Json.parseObject(app3.stateJson())!!["marketScan"] == null, "stale scan not ranked")
        } finally {
            Yahoo.market = emptyMap()
        }
    }
}
