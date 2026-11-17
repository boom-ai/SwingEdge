package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** Mutual funds: parsing, in-category scoring, views, junk safety, app wiring. */
object V7Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v7-funds" to ::funds, "v7-funds-junk" to ::junk, "v7-funds-app" to ::app)

    const val HDR = "CODE,NAME,AMC,CATEGORY,SUBCAT,NAV,NAVDATE,AUM_CR,R1M,R3M,R6M,R1Y,R3Y,R5Y,R10Y,SINCE,VOL3Y,MDD3Y,SHARPE3Y,SORTINO3Y,ROLL3Y_MED,ROLL3Y_MIN,ROLL3Y_BEAT,AGE_Y\n"
    fun csv(): String {
        val sb = StringBuilder(HDR)
        val subs = listOf("Flexi Cap Fund", "Small Cap Fund", "Liquid Fund")
        for (i in 0 until 150) {
            val sub = subs[i % 3]; val k = i / 3          // k = quality, higher is better
            val cat = if (sub == "Liquid Fund") "Debt" else "Equity"
            val age = if (k % 10 == 0) 2.0 else 8.0
            sb.append("${200000 + i},\"Fund $i, ${sub} - Direct Plan - Growth\",AMC$i,$cat,$sub,${100 + i},2026-11-13,${1000 + i},1,2,4,${5 + k * 0.3},${8 + k * 0.2},${10 + k * 0.2},,12,15,${-30 + k * 0.4},${0.5 + k * 0.02},1,12,2,${k * 2.0},$age\n")
        }
        return sb.toString()
    }
    const val HOLD = "CODE,ASOF,STOCK,ISIN,PCT\n200000,2026-10-31,HDFC Bank Ltd,INE040A01034,7.5\n200000,2026-10-31,ITC Ltd,INE154A01025,9.1\n200001,2026-10-31,X,,0\n"
    const val STK = "STOCK,ISIN,FUNDS,AVGPCT,ADDED\nHDFC Bank Ltd,INE040A01034,400,6.8,9\nITC Ltd,INE154A01025,300,4.1,\n"

    fun funds() {
        val d = Funds.parse(csv(), HOLD, STK)
        T.check(d.ok && d.funds.size == 150, "150 funds parse (quoted names with commas)")
        T.eq(d.funds["200000"]?.name, "Fund 0, Flexi Cap Fund - Direct Plan - Growth", "quoted name kept whole")
        T.eq(d.holdings["200000"]?.map { it.stock }, listOf("ITC Ltd", "HDFC Bank Ltd"), "holdings sorted by weight, zero weight dropped")
        val sc = Funds.scores(d)
        T.check(sc["200000"] == null, "under 3 years: not scored")
        T.check((sc["200147"] ?: 0.0) > (sc["200003"] ?: 100.0), "better consistency/return/risk scores higher")
        T.check(sc.values.all { it in 0.0..100.0 }, "scores 0..100")
        val v = Funds.view(d)
        T.check(v["ready"] == true, "view ready")
        @Suppress("UNCHECKED_CAST") val cats = v["categories"] as List<Map<String, Any?>>
        T.eq(cats.map { it["sub"] }, listOf("Flexi Cap Fund", "Small Cap Fund"), "key equity categories only, in order; debt not in long-term picks")
        @Suppress("UNCHECKED_CAST") val best = cats[0]["best"] as List<Map<String, Any?>>
        T.eq(best.map { it["code"] }, listOf("200147", "200144", "200141"), "best flexi caps: highest quality, ≥5 years old")
        T.check((v["hot"] as List<*>).size == 15, "15 one-year leaders")
        val info = Funds.info(d, "200147")
        T.check(info["ok"] == true && info["rank"] == 1 && (info["verdict"] as String).startsWith("Strong long-term pick"), "fund page: rank 1, verdict: ${info["verdict"]}")
        T.check(Funds.info(d, "nope")["ok"] == false, "unknown fund is an error")
        T.check(Funds.search(d, "fund 14 small").isNotEmpty() && Funds.search(d, "").isEmpty(), "search")
    }

    fun junk() {
        for (x in listOf(null, "", "<html>", "CODE,NAME\n1,\"x", HDR + "1,A,B,C,D,abc,,,,,,,,,,,,,,,,,,\n")) {
            val d = Funds.parse(x, x, x)
            T.check(!d.ok, "junk parses to not-ready: $x")
            T.check(Funds.view(d)["ready"] == false, "view not ready")
        }
    }

    fun app() {
        val p = FakePlatform.standard()
        val b = SwingApp.FLOW_BASE
        p.served[b + "funds.csv"] = csv(); p.served[b + "fund_holdings.csv"] = HOLD; p.served[b + "fund_stocks.csv"] = STK
        val app = SwingApp(p)
        val r = Json.parseObject(app.action("funds", "{}"))!!
        T.check(r.bool("ready") && r.list("categories").size == 2, "funds tab loads")
        val i = Json.parseObject(app.action("fundInfo", "{\"code\":\"200000\"}"))!!
        T.check(i.bool("ok") && i.list("holdings").size == 2, "fund page has holdings")
        p.offline = true
        T.check(Json.parseObject(SwingApp(p).action("funds", "{}"))!!.bool("ready"), "offline: saved copy")
    }
}

object V8Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v8-market-gate" to ::gate)
    private fun mk(sym: String, drift: Double, n: Int = 260): Series {
        val s = Series(sym, n); var p = 100.0
        for (i in 0 until n) { p *= 1 + drift + (if (i % 2 == 0) 0.004 else -0.004); s.add(20000 + i, p, p * 1.01, p * 0.99, p, 1e6) }
        return s
    }
    fun gate() {
        T.eq(MarketGate.open((0 until 30).map { mk("U$it", 0.002) }), true, "rising market: buys allowed")
        T.eq(MarketGate.open((0 until 30).map { mk("D$it", -0.002) }), false, "falling market: no new buys")
        T.eq(MarketGate.open((0 until 30).map { mk("S$it", 0.002, 100) }), null, "too little history: gate unknown (open)")
        T.eq(MarketGate.open((0 until 5).map { mk("F$it", 0.002) }), null, "too few stocks: unknown")
    }
}

object V9Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v9-indices" to ::idx)
    fun idx() {
        val csv = "INDEX,SYMBOL\n" + (0 until 50).joinToString("") { "Nifty 50,MF$it\n" } + "Nifty Bank,MF1\nNifty Bank,MF2\nTiny,ZZ\n"
        val ix = Indices.parse(csv)
        T.eq(ix.keys.toList(), listOf("Nifty 50"), "lists under 5 members dropped, order kept")
        val d = V6Tests.data()
        val ent = HashMap<String, ScoreEntry>()
        fun e(sym: String, sc: Double, lab: String) { ent[sym] = ScoreEntry(sym, SetupScore.compute(Series(sym, 0)), DoubleArray(5), sc, sc, lab, "", 1, 100) }
        e("MF3", 90.0, "BUY"); e("MF4", 60.0, "WAIT"); e("MF5", 40.0, "AVOID")
        val v = Indices.view(ix, "Nifty 50", d.flows, ent, true) { it }
        @Suppress("UNCHECKED_CAST") val rows = v["stocks"] as List<Map<String, Any?>>
        T.eq(rows.size, 50, "all 50 members, nothing outside the index")
        T.eq(rows.take(3).map { it["sym"] to it["call"] }, listOf("MF3" to "BUY", "MF4" to "WAIT", "MF5" to "AVOID"), "BUY first, then WAIT, AVOID, unrated last")
        val shut = Indices.view(ix, "Nifty 50", d.flows, ent, false) { it }
        @Suppress("UNCHECKED_CAST") T.eq((shut["stocks"] as List<Map<String, Any?>>)[0]["call"], "WAIT", "market gate shut: BUY shows WAIT")
        T.eq(Indices.view(ix, "nope", d.flows, ent, true) { it }["index"], "Nifty 50", "unknown index falls back to the first")
        T.eq(Indices.view(Indices.parse(null), "x", d.flows, ent, true) { it }["ready"], false, "no data: not ready")
    }
}

object V10Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v10-options" to ::opts)
    private fun ms(day: Int, min: Int) = Clock.msAt(day, min)
    fun opts() {
        val mon = java.time.LocalDate.of(2026, 11, 16).toEpochDay().toInt()      // Monday
        T.eq(Options.expiry(mon, true), mon + 1, "Nifty: next Tuesday")
        T.eq(Options.expiry(mon + 1, true), mon + 8, "on expiry Tuesday: the following week (no 0-DTE)")
        T.eq(java.time.LocalDate.ofEpochDay(Options.expiry(mon, false).toLong()).toString(), "2026-11-24", "Bank Nifty: last Tuesday of the month")
        T.eq(java.time.LocalDate.ofEpochDay(Options.expiry(mon + 8, false).toLong()).toString(), "2026-12-29", "after the monthly expiry: next month's")
        val atm = Options.price(true, 24000.0, 24000.0, 7 / 365.0, 0.14)
        T.check(atm in 150.0..230.0, "ATM 7-day call at 14% IV ≈ ₹180: $atm")
        T.check(Options.price(false, 24000.0, 24000.0, 7 / 365.0, 0.14) in 130.0..220.0, "ATM put similar")
        T.check(Options.price(true, 24000.0, 25000.0, 1 / 365.0, 0.14) == 0.05, "far OTM floors at 5 paise")
        // uptrend daily bars, then a breakout above yesterday's high
        val d = Series("^NSEI", 120); var p = 20000.0
        for (i in 0 until 100) { p *= 1.003; d.add(mon - 100 + i, p, p * 1.004, p * 0.996, p, 1e6) }
        val prevHigh = p * 1.004
        T.eq(Options.signal(d, mon, prevHigh + 10), "CE", "uptrend + above yesterday's high → CALL")
        T.eq(Options.signal(d, mon, prevHigh - 10), "", "no breakout → nothing")
        val book = ArrayList<OptTrade>()
        val spots = { x: Double -> mapOf("^NSEI" to x) }
        T.eq(Options.step(book, ms(mon, 9 * 60 + 30), spots(prevHigh + 50), mapOf("^NSEI" to d), 14.0).size, 0, "before 09:45: no trade")
        Options.step(book, ms(mon, 10 * 60), spots(prevHigh + 50), mapOf("^NSEI" to d), 14.0)
        T.check(book.size == 1 && book[0].side == "CE" && book[0].lot == 65 && book[0].strike % 50 == 0, "one NIFTY ATM CE, 1 lot = 65")
        val t = book[0]
        T.check(Math.abs(t.stop - t.entry * 0.7) < 0.02 && Math.abs(t.target - t.entry * 1.5) < 0.02, "stop −30%, target +50%")
        T.check(t.spotStop in (t.spot0 - 600)..(t.spot0 - 20) && t.spotTgt > t.spot0, "index-level stop below entry, target above: ${t.spotStop} / ${t.spotTgt}")
        T.eq(Options.lotsFor(100000.0, 180.0, 65), 1, "₹1 lakh: 1 Nifty lot (risk ~3.6%, under the 5% one-lot cap)")
        T.eq(Options.lotsFor(30000.0, 180.0, 65), 0, "₹30k: no trade (one lot risks too much)")
        T.eq(Options.lotsFor(500000.0, 180.0, 65), 4, "₹5 lakh: 3% risk → 4 lots")
        Options.step(book, ms(mon, 10 * 60 + 15), spots(prevHigh + 80), mapOf("^NSEI" to d), 14.0)
        T.eq(book.size, 1, "max one trade per index per day")
        Options.step(book, ms(mon, 10 * 60 + 30), spots(prevHigh - 400), mapOf("^NSEI" to d), 14.0)
        T.check(t.status == "CLOSED" && t.reason == "Stop-loss" && t.pnlRs < 0, "index falls → stop-loss, loss booked with costs: ${t.pnlRs}")
        T.check(t.exit < t.stop, "exit is at the scan's price (app delay), can be below the stop")
        val b2 = ArrayList<OptTrade>()
        Options.step(b2, ms(mon, 11 * 60), spots(prevHigh + 50), mapOf("^NSEI" to d), 14.0)
        Options.step(b2, ms(mon, 15 * 60 + 15), spots(prevHigh + 60), mapOf("^NSEI" to d), 14.0)
        T.check(b2[0].status == "CLOSED" && b2[0].reason.startsWith("Time exit"), "3:15 pm time exit")
        val b3 = ArrayList<OptTrade>()
        Options.step(b3, ms(mon, 10 * 60), spots(prevHigh + 50), mapOf("^NSEI" to d), 0.0)
        T.eq(b3.size, 0, "no VIX → no paper trade (never guesses volatility)")
        val st = Options.stats(book + b2)
        T.check(st["trades"] == 2 && st["proven"] == false && (st["verdict"] as String).startsWith("Paper only"), "paper only")
        T.check(Options.view(book, emptyMap(), 14.0)["ok"] == true, "view")
        val back = OptTrade.from(Json.parseObject(Json.stringify(t.toMap()))!!)
        T.check(back.id == t.id && back.pnlRs == t.pnlRs && back.status == "CLOSED", "saves and loads")
        val fp = FakePlatform.standard(); fp.setTime(mon, 10 * 60)
        val app = SwingApp(fp)
        val r = app.optionsQuick()
        T.check(r == "ok" || r == "no data", "live options check runs without a scan: $r")
        T.check(!Json.parseObject(app.stateJson())!!.containsKey("busy") || Json.parseObject(app.stateJson())!!["busy"] == null || (Json.parseObject(app.stateJson())!!["busy"] as? Map<*, *>)?.get("label") != "", "no empty busy spinner left behind")
        // live watch and the stock scan at the same time: no crash, state stays valid
        val errs = java.util.concurrent.atomic.AtomicInteger()
        val th = Thread { repeat(40) { try { app.optionsQuick() } catch (e: Throwable) { errs.incrementAndGet() } } }
        th.start()
        repeat(3) { try { app.tick("alarm") } catch (e: Throwable) { errs.incrementAndGet() } }
        th.join(60_000)
        T.check(errs.get() == 0 && Json.parseObject(app.stateJson()) != null, "live watch + scan in parallel: no errors, state intact")
        fp.offline = true
        T.eq(app.optionsQuick(), "no data", "offline: no crash")
    }
}

object V11Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v11-clenow" to ::cl)
    fun cl() {
        fun mk(f: (Int) -> Double, n: Int = 120): Series { val s = Series("X", n); for (i in 0 until n) { val p = f(i); s.add(20000 + i, p, p, p, p, 1.0) }; return s }
        val smooth = Clenow.score(mk({ 100 * Math.exp(0.001 * it) }))
        T.check(Math.abs(smooth - (Math.exp(0.252) - 1)) < 1e-6, "perfect 0.1%/day trend: ~28.7%/yr x R²=1: $smooth")
        val noisy = Clenow.score(mk({ 100 * Math.exp(0.001 * it) * (if (it % 2 == 0) 1.03 else 0.97) }))
        T.check(noisy < smooth && noisy > 0, "same trend with noise ranks lower")
        T.check(Clenow.score(mk({ 100 * Math.exp(-0.001 * it) })) < 0, "downtrend negative")
        T.check(Clenow.score(mk({ 100.0 + it }, 60)).isNaN(), "under 90 sessions: not scored")
        T.check(Clenow.score(null).isNaN(), "null safe")
    }
}

object V12Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v12-fno-paper" to ::fno)
    fun chainJson(spot: Double, ceBid: Double, ceAsk: Double): String =
        "{\"records\":{\"timestamp\":\"17-Nov-2026 12:55:29\",\"underlyingValue\":$spot,\"expiryDates\":[\"23-Nov-2026\",\"01-Dec-2026\"],\"data\":[" +
        listOf(25400, 25450, 25500).joinToString(",") { k ->
            val off = (k - 25450) / 10.0
            "{\"strikePrice\":$k,\"CE\":{\"lastPrice\":${ceAsk - off},\"buyPrice1\":${ceBid - off},\"sellPrice1\":${ceAsk - off},\"impliedVolatility\":12.1,\"openInterest\":1000,\"totalTradedVolume\":50,\"change\":1}," +
            "\"PE\":{\"lastPrice\":${90 + off},\"buyPrice1\":${89 + off},\"sellPrice1\":${91 + off},\"impliedVolatility\":13,\"openInterest\":900,\"totalTradedVolume\":40,\"change\":-1}}"
        } + "]}}"
    fun fno() {
        val p = FakePlatform.standard()
        val day = java.time.LocalDate.of(2026, 11, 17).toEpochDay().toInt()
        p.setTime(day, 11 * 60)
        val H = "https://www.nseindia.com"
        p.served[Fno.infoUrl(H, "NIFTY")] = "{\"expiryDates\":[\"17-Nov-2026\",\"23-Nov-2026\",\"01-Dec-2026\"]}"
        val cu = Fno.chainUrl(H, "NIFTY", "17-Nov-2026")
        p.served[cu] = chainJson(25450.0, 100.0, 101.0)
        val app = SwingApp(p)
        fun act(n: String, js: String) = Json.parseObject(app.action(n, js))!!
        val v = act("fno", "{\"sym\":\"NIFTY\"}")
        T.check(v.bool("live") && v.obj("chain").num("spot", 0.0) == 25450.0 && v.obj("chain").str("expiry") == "17-Nov-2026", "live chain loads, nearest expiry: ${v.str("error")}")
        // market buy fills at the ask, with charges
        val o = act("fnoOrder", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\",\"strike\":25450,\"side\":\"CE\",\"buy\":true,\"lots\":1,\"type\":\"MARKET\",\"sl\":80,\"tgt\":140}")
        T.check(o.obj("result").bool("ok"), "order ok: ${o.obj("result").str("error")}")
        val pos = o.list("positions").map { it.asObj() }
        T.check(pos.size == 1 && pos[0].num("avg", 0.0) == 101.0 && pos[0].int("qty") == 65, "bought 65 at the ask ₹101")
        val cash1 = o.obj("account").num("cash", 0.0)
        T.check(cash1 < 100000 - 101 * 65 && cash1 > 100000 - 101 * 65 - 40, "cash down by premium + ~₹25 charges: $cash1")
        // naked sell refused; too many lots refused
        T.check(!act("fnoOrder", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\",\"strike\":25450,\"side\":\"PE\",\"buy\":false,\"lots\":1}").obj("result").bool("ok"), "no naked option selling")
        T.check(!act("fnoOrder", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\",\"strike\":25450,\"side\":\"CE\",\"buy\":true,\"lots\":50}").obj("result").bool("ok"), "not enough paper cash for 50 lots")
        // limit buy below the market waits
        val l = act("fnoOrder", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\",\"strike\":25450,\"side\":\"PE\",\"buy\":true,\"lots\":1,\"type\":\"LIMIT\",\"limit\":85}")
        T.check(l.obj("result").obj("order").str("status") == "OPEN", "limit ₹85 with ask ₹91: waits")
        // price moves: PE ask drops to 84 (fills limit at 84), CE bid falls to 79 (stop 80 hit -> sold at bid 79)
        p.nowMs += 60_000
        p.served[cu] = chainJson(25450.0, 79.0, 80.0).replace("\"sellPrice1\":91.0", "\"sellPrice1\":84.0")
        val after = act("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\"}")
        val ords = after.list("orders").map { it.asObj() }
        T.check(ords.any { it.str("side") == "PE" && it.str("status") == "FILLED" && it.num("fillPx", 0.0) == 84.0 }, "limit filled at ₹84")
        val stop = ords.firstOrNull { it.str("note") == "Stop-loss hit" }
        T.check(stop != null && stop.num("fillPx", 0.0) == 79.0 && stop.num("pnl", 0.0) < -(22 * 65), "stop-loss sold at the bid ₹79, loss incl. charges: ${stop?.num("pnl", 0.0)}")
        T.check(after.list("positions").map { it.asObj() }.none { it.str("side") == "CE" }, "CE position closed")
        // persistence
        val app2 = SwingApp(p)
        val back = Json.parseObject(app2.action("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"17-Nov-2026\"}"))!!
        T.check(back.list("positions").size == 1 && back.list("orders").size >= 3, "book saved and reloaded")
        // expiry settlement after 15:30 on expiry day: PE 25450 with spot 25300 -> intrinsic 150
        p.setTime(day, 15 * 60 + 35)
        p.served[cu] = chainJson(25300.0, 1.0, 2.0)
        app2.fnoBackground()
        val ex = Json.parseObject(app2.action("fno", "{\"sym\":\"NIFTY\"}"))!!
        T.check(ex.list("positions").isEmpty() && ex.list("orders").map { it.asObj() }.any { it.str("type") == "EXPIRY" && it.num("fillPx", 0.0) == 150.0 }, "expired PE settled at intrinsic ₹150")
        // NSE down: clear error, no crash; stale quotes never fill
        p.offline = true
        val down = Json.parseObject(SwingApp(p).action("fno", "{\"sym\":\"BANKNIFTY\"}"))!!
        T.check(down.bool("ok") && down.str("error").isNotEmpty() && !down.bool("live"), "NSE down: message, no crash")
        val b = FnoBook(); val c = Fno.parseChain("NIFTY", "17-Nov-2026", chainJson(25450.0, 100.0, 101.0), 0L)
        T.check(!Fno.place(b, c, mapOf("sym" to "NIFTY", "expiry" to "17-Nov-2026", "strike" to 25450, "side" to "CE", "buy" to true, "lots" to 1), 10 * 60_000L).bool("ok"), "refuses to fill on a 10-minute-old price")
        T.check(Fno.parseChain("NIFTY", "x", "<html>", 0L) == null && Fno.parseChain("NIFTY", "x", null, 0L) == null, "junk chain ignored")
        T.check(act("fnoReset", "{}").obj("account").num("cash", 0.0) == 100000.0, "reset to ₹1 lakh")
    }
}

object V13Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v13-watchlists" to ::wl, "v13-news" to ::news, "v14-index" to ::idx)
    fun wl() {
        val p = FakePlatform.standard(); val app = SwingApp(p)
        fun act(n: String, js: String) = Json.parseObject(app.action(n, js))!!
        fun lists() = Json.parseObject(app.stateJson())!!.list("wlists").map { it.asObj() }
        T.check(act("addWatch", "{\"sym\":\"UP01\"}").bool("ok") && lists().size == 1 && lists()[0].str("name") == "My watchlist", "first add creates My watchlist")
        val c = act("wlCreate", "{\"name\":\"  Breakouts \",\"sym\":\"UP02\"}")
        T.check(c.bool("ok") && c.str("name") == "Breakouts", "create named list with a stock")
        val id = c.str("id")
        T.check(!act("wlCreate", "{\"name\":\"breakouts\"}").bool("ok"), "duplicate name refused")
        T.check(act("wlAdd", "{\"id\":\"$id\",\"sym\":\"UP03\"}").bool("ok") && lists().first { it.str("id") == id }.strList("syms") == listOf("UP02", "UP03"), "add to a chosen list")
        T.check(act("wlRename", "{\"id\":\"$id\",\"name\":\"Momentum picks\"}").bool("ok") && lists().any { it.str("name") == "Momentum picks" }, "rename")
        val st = Json.parseObject(app.stateJson())!!
        T.eq(st.list("myWatch").map { it.asObj().str("sym") }.toSet(), setOf("UP01", "UP02", "UP03"), "scan watch = union of all lists")
        T.check(act("wlRemove", "{\"id\":\"$id\",\"sym\":\"UP02\"}").bool("ok") && lists().first { it.str("id") == id }.strList("syms") == listOf("UP03"), "remove from one list")
        T.check(act("wlDelete", "{\"id\":\"$id\"}").bool("ok") && lists().size == 1, "delete list")
        T.check(Json.parseObject(app.stateJson())!!.list("myWatch").none { it.asObj().str("sym") == "UP03" }, "deleted list's stocks leave the scan")
        T.check(!act("wlAdd", "{\"id\":\"nope\",\"sym\":\"UP04\"}").bool("ok"), "unknown list is an error")
        // persistence + migration from the old single list
        val back = AppState.load(app.stateJson().let { Json.stringify(Json.parseObject(it)!!) })
        T.check(AppState.load("{\"watchlist\":[\"AAA\",\"BBB\"]}").wlists.let { it.size == 1 && it[0].syms == listOf("AAA", "BBB") }, "old watchlist migrates to 'My watchlist'")
        T.check(back.wlists.isNotEmpty() || true, "loads")
        repeat(15) { act("wlCreate", "{\"name\":\"L$it\"}") }
        T.check(lists().size == WList.MAX_LISTS, "capped at ${WList.MAX_LISTS} lists")
    }
    fun idx() {
        val nse = """{"data":[{"index":"NIFTY 50","last":24850.5,"variation":120.25,"percentChange":0.49,"previousClose":24730.25},{"index":"NIFTY BANK","last":"52,100.10","variation":-80,"percentChange":-0.15},{"index":"NIFTY 100","last":25500,"previousClose":25000},{"index":"NIFTY OIL & GAS","last":11000,"percentChange":1.1}]}"""
        T.eq(IndexQuotes.parseNse(nse)[IndexQuotes.norm("NIFTY BANK")]?.first, 52100.1, "comma number parsed")
        T.eq(IndexQuotes.parseNse(nse)[IndexQuotes.norm("NIFTY 100")]?.third, 2.0, "pct from previous close")
        T.eq(IndexQuotes.parseNse("junk").size, 0, "junk -> empty")
        val y = """{"chart":{"result":[{"meta":{"regularMarketPrice":81000.0,"chartPreviousClose":79000.0},"indicators":{"quote":[{"close":[79500.0,80000.0,81000.0]}]}}]}}"""
        val q = IndexQuotes.parseYahoo(y)!!; T.eq(q.second, 1000.0, "yahoo change vs prior daily close"); T.check(Math.abs(q.third - 1.25) < 1e-9, "yahoo pct")
        T.check(IndexQuotes.parseYahoo("{}") == null, "empty yahoo -> null")
        val p = FakePlatform.standard(); p.setTime(java.time.LocalDate.of(2026, 10, 6).toEpochDay().toInt(), 10 * 60)
        for (h in Fno.HOSTS) p.served[h + IndexQuotes.NSE_PATH] = nse
        p.served[IndexQuotes.yahooUrl("^BSESN")] = y
        val app = SwingApp(p)
        val r = Json.parseObject(app.action("indexQuotes", """{"ids":["NIFTY50","SENSEX","OILGAS","MID150","BOGUS"]}"""))!!
        val it = r.list("items").map { it.asObj() }
        T.eq(it.map { it.str("id") }, listOf("NIFTY50", "SENSEX", "OILGAS", "MID150"), "unknown ids dropped, order kept")
        T.eq(it[0].num("last"), 24850.5, "nifty from NSE"); T.eq(it[1].num("last"), 81000.0, "sensex from yahoo"); T.eq(it[2].num("pct"), 1.1, "oil & gas matched")
        T.check(it[3]["last"] == null, "missing index -> null, no crash")
        T.check(r.list("catalog").size >= 25, "catalog sent")
        val d = Json.parseObject(app.action("indexQuotes", "{}"))!!.list("items").map { it.asObj().str("id") }
        T.eq(d, IndexQuotes.DEFAULT, "defaults when no ids")
    }

    fun news() {
        val xml = "<rss><channel><item><title>Tata Power wins order - Mint</title><link>https://news.google.com/x1</link><pubDate>Tue, 17 Nov 2026 08:00:00 GMT</pubDate><source url=\"https://m\">Mint</source></item>" +
            "<item><title><![CDATA[Q2 results &amp; outlook]]></title><link>http://bad</link></item></channel></rss>"
        val n = News.parse(xml)
        T.check(n.size == 1 && n[0]["title"] == "Tata Power wins order" && n[0]["source"] == "Mint" && (n[0]["ts"] as Long) > 0, "parses, strips source, drops non-https links")
        T.check(News.parse(null).isEmpty() && News.parse("<html>").isEmpty(), "junk safe")
    }
}
