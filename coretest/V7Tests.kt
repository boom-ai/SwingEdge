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


object V15Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v15-fno-lab" to ::lab)
    /** Yahoo 5m JSON for [day]: closes per bar starting 9:15 IST. */
    fun bars(day: Int, prev: Double, closes: List<Double>): String {
        val t0 = (day.toLong() * 86400L + 9 * 3600 + 15 * 60) - 19800L
        val ts = closes.indices.map { t0 + it * 300 }
        fun arr(f: (Int) -> Double) = closes.indices.joinToString(",") { f(it).toString() }
        return "{\"chart\":{\"result\":[{\"meta\":{\"chartPreviousClose\":$prev},\"timestamp\":[${ts.joinToString(",")}],\"indicators\":{\"quote\":[{" +
            "\"open\":[${arr { if (it == 0) closes[0] else closes[it - 1] }}],\"high\":[${arr { closes[it] + 2 }}],\"low\":[${arr { closes[it] - 2 }}],\"close\":[${arr { closes[it] }}]}]}}]}}"
    }
    fun lab() {
        // unit: parsing + ORB trigger only on a completed bar beyond the 9:15–9:30 range
        val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()
        val cl = listOf(25400.0, 25410.0, 25405.0, 25460.0)          // 9:15, 9:20, 9:25 range; 9:30 bar breaks out
        val b = FnoLab.parseBars(bars(day, 25390.0, cl))!!
        T.eq(b.n, 4, "bars parsed"); T.eq(b.min[0], 9 * 60 + 15, "IST minutes")
        T.check(FnoLab.signals("NIFTY", b, 9 * 60 + 34, emptySet()).none { it.strat == "ORB" }, "no ORB before the breakout bar completes")
        val sg = FnoLab.signals("NIFTY", b, 9 * 60 + 36, emptySet())
        T.check(sg.any { it.strat == "ORB" && it.side == "CE" }, "ORB call on the close above the range: ${sg.map { it.strat }}")
        T.check(FnoLab.signals("NIFTY", b, 9 * 60 + 36, setOf("ORB")).none { it.strat == "ORB" }, "fires once a day")
        T.check(FnoLab.parseBars("junk") == null, "junk bars -> null")
        // replay + learning
        T.eq(FnoLab.replay(100.0, listOf(95.0, 69.0, 200.0), 30.0, 50.0).second, "STOP", "stop first")
        T.eq(FnoLab.replay(100.0, listOf(120.0, 151.0, 60.0), 30.0, 50.0).second, "TARGET", "target first")
        T.eq(FnoLab.replay(100.0, listOf(110.0, 120.0), 30.0, 0.0).first, 120.0, "no target holds to the last bid")
        val lb = LabBook()
        repeat(10) { i -> val t = LabTrade("x$i", "ORB", "NIFTY", "23-Nov-2026", 25450, "CE", 65, 100.0, 0, day, bids = arrayListOf(110.0, 125.0, 135.0, 60.0)); lb.open.add(t); FnoLab.close(lb, t) }
        T.eq(lb.closed.size, 10, "closed trades kept"); T.eq(lb.stats["ORB|NIFTY"]!!.size, FnoLab.STOPS.size * FnoLab.TGTS.size + 1, "every exit pair learned")
        T.eq(FnoLab.bestExit(lb, "ORB|NIFTY").second, 30.0, "learned: +30% target beats the default here")
        T.eq(FnoLab.status(lb, "ORB|NIFTY"), "LEARNING", "under 20 trades = learning")
        T.eq(FnoLab.lots(100000.0, 100.0, 65, 30.0), 1, "1 lot: risk 1,950 ≤ 5%"); T.eq(FnoLab.lots(100000.0, 500.0, 65, 30.0), 0, "too costly -> 0")
        T.check(LabBook.from(Json.parseObject(Json.stringify(lb.toMap()))).stats["ORB|NIFTY"]!!["live"]!!.n == 10, "book round-trips")
        // integration through the app
        val p = FakePlatform.standard(); p.setTime(day, 9 * 60 + 36)
        val H = "https://www.nseindia.com"
        p.served[Fno.infoUrl(H, "NIFTY")] = "{\"expiryDates\":[\"18-Nov-2026\",\"23-Nov-2026\",\"01-Dec-2026\"]}"
        p.served[Fno.chainUrl(H, "NIFTY", "23-Nov-2026")] = V12Tests.chainJson(25450.0, 100.0, 101.0)
        p.served[Yahoo.tickerUrl(Params.YAHOO_HOST_1, "^NSEI", "1d", "5m")] = bars(day, 25390.0, cl)
        p.served[H + "/api/allIndices"] = "{\"data\":[{\"index\":\"NIFTY 50\",\"last\":25460.5,\"variation\":70.5,\"percentChange\":0.28},{\"index\":\"INDIA VIX\",\"last\":12.1,\"variation\":-0.2,\"percentChange\":-1.6}]}"
        val app = SwingApp(p)
        T.eq(app.fnoLabStep(), "ok", "lab step runs")
        var v = Json.parseObject(app.action("fnoLab", "{}"))!!
        val td = v.list("today").map { it.asObj() }
        T.check(td.size == 1 && td[0].str("strat") == "ORB#1" && td[0].str("side") == "CE" && td[0].int("strike") == 25450, "ORB champion call taken at ATM: $td")
        T.eq(v.int("shadowToday"), 2, "the two challenger thresholds are paper-traded in the background")
        T.eq(td[0].num("entry"), 101.0, "entry at the live ask"); T.eq(td[0].str("expiry"), "23-Nov-2026", "skips the expiry that is today")
        T.eq(v.list("ticker").size, 2, "live ticker from NSE"); T.eq(v.list("ticker")[0].asObj().num("last"), 25460.5, "nifty last")
        p.setTime(day, 10 * 60); p.served[Fno.chainUrl(H, "NIFTY", "23-Nov-2026")] = V12Tests.chainJson(25480.0, 160.0, 161.0)
        app.fnoLabStep()
        T.eq(Json.parseObject(app.action("fnoLab", "{}"))!!.list("today")[0].asObj().str("liveHit"), "TARGET", "live target hit seen")
        p.setTime(day, 15 * 60 + 16); app.fnoLabStep()
        v = Json.parseObject(app.action("fnoLab", "{}"))!!
        val c = v.list("today")[0].asObj()
        T.check(c.str("status") == "TARGET" && c.num("pnl") > 0, "closed at target with a profit after charges: $c")
        T.eq(v.obj("record").int("n"), 1, "record counts it")
        T.check(v.list("strategies").any { it.asObj().str("id") == "ORB" && it.asObj().int("n") == 1 && it.asObj().int("tested") == 3 }, "strategy learned from all three variants")
        // champion switches to the variant that has done best
        val lb2 = LabBook(); repeat(6) { i -> val t = LabTrade("y$i", "ORB#0", "NIFTY", "x", 1, "CE", 65, 100.0, 0, day, bids = arrayListOf(150.0)); lb2.open.add(t); FnoLab.close(lb2, t) }
        repeat(6) { i -> val t = LabTrade("z$i", "ORB#1", "NIFTY", "x", 1, "CE", 65, 100.0, 0, day, bids = arrayListOf(60.0)); lb2.open.add(t); FnoLab.close(lb2, t) }
        T.eq(FnoLab.champion(lb2, "ORB", "NIFTY"), "ORB#0", "champion = best learned threshold")
        T.eq(FnoLab.champion(LabBook(), "ORB", "NIFTY"), "ORB#1", "default champion = middle threshold")
        T.eq(Json.parseObject(app.action("fnoTicker", "{}"))!!.list("ticker").size, 2, "ticker action")
    }
}


object V16Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v16-live-feed" to ::live)
    fun live() {
        val idx = """{"segment":"CASH","exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{},"indexLivePointsMap":{"NIFTY":{"close":25227.95,"dayChange":85.9,"dayChangePerc":0.34,"value":25313.85,"tsInMillis":1795067365},"INDIAVIX":{"value":15.99,"dayChange":0.42,"dayChangePerc":2.7,"tsInMillis":1795067365}}}}}"""
        val t = Groww.parseIndices(idx)
        T.eq(t.map { it["name"] }, listOf("Nifty 50", "India VIX"), "indices in ticker order"); T.eq(t[0]["last"], 25313.85, "nifty live value")
        T.eq(Groww.parseIndices("junk").size, 0, "junk -> empty")
        val chain = """{"optionChain":{"optionChains":[{"callOption":{"growwContractId":"NIFTY26NOV25450CE"},"putOption":{"growwContractId":"NIFTY26NOV25450PE"},"strikePrice":2545000}],"expiryDetailsDto":{}}}"""
        T.eq(Groww.parseIds(chain)[25450]?.first, "NIFTY26NOV25450CE", "contract ids by strike")
        val px = """{"NIFTY26NOV25450CE":{"ltp":123.45,"lastTradeTime":1795067564,"dayChange":5},"NIFTY26NOV25450PE":{"ltp":88.1,"lastTradeTime":1795067564}}"""
        T.eq(Groww.parsePrices(px)["NIFTY26NOV25450CE"]?.first, 123.45, "batch ltp")
        // integration: the paper chain takes the live price
        val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()
        val p = FakePlatform.standard(); p.setTime(day, 10 * 60)
        val H = "https://www.nseindia.com"
        p.served[Fno.infoUrl(H, "NIFTY")] = "{\"expiryDates\":[\"23-Nov-2026\"]}"
        p.served[Fno.chainUrl(H, "NIFTY", "23-Nov-2026")] = V12Tests.chainJson(25450.0, 100.0, 101.0)
        p.served[Groww.chainUrl("NIFTY", "2026-11-23")] = chain
        p.posted[Groww.PRICES_URL] = px; p.posted[Groww.INDEX_URL] = idx
        val app = SwingApp(p)
        val r = Json.parseObject(app.action("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"23-Nov-2026\"}"))!!
        val rows = r.obj("chain").list("rows").map { it.asObj() }
        val k = rows.firstOrNull { it.int("k") == 25450 }
        T.check(k != null && k.obj("ce").num("ltp") == 123.45, "chain row shows the live CE price: $k")
        T.check(k != null && k.obj("ce").num("bid") < 123.45 && k.obj("ce").num("ask") > 123.45, "bid/ask around the live price")
        T.check(r.obj("chain").str("ts").startsWith("Live"), "chain marked Live")
        T.eq(Json.parseObject(app.action("fnoTicker", "{}"))!!.list("ticker")[0].asObj().num("last"), 25313.85, "ticker from the live feed")
        p.posted.clear()
        val r2 = Json.parseObject(app.action("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"23-Nov-2026\"}"))!!
        T.check(r2.obj("chain").list("rows").isNotEmpty(), "falls back to NSE when the live feed is down")
        // live stock quotes + market view
        p.posted[Groww.INDEX_URL] = """{"exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{"M&M":{"ltp":3507.3,"close":3359.1,"dayChange":148.2,"dayChangePerc":4.41,"tsInMillis":1795070709}},"indexLivePointsMap":{}}}}"""
        val lq = Json.parseObject(app.action("liveQuotes", """{"syms":["m&m","BAD SYM!"]}"""))!!
        T.eq(((lq["quotes"] as Map<*, *>)["M&M"] as Map<*, *>)["ltp"], 3507.3, "live stock price in one batch")
        val bars = FnoLab.parseBars(V15Tests.bars(day, 25390.0, listOf(25400.0, 25410.0, 25405.0, 25460.0)))
        val mv = FnoLab.marketView("NIFTY", bars, 25470.0, 10 * 60)!!
        T.eq(mv["bias"], "BULLISH", "above open, range and average = bullish"); T.check((mv["plan"] as String).startsWith("Calls only"), "plain plan")
        T.eq(FnoLab.marketView("NIFTY", bars, 25470.0, 14 * 60 + 40)!!["plan"].toString().startsWith("No new trades"), true, "after 2:30 no new trades")
        val cv = Fno.chainView(Fno.parseChain("NIFTY", "23-Nov-2026", V12Tests.chainJson(25450.0, 100.0, 101.0), 0L))!!
        T.check(cv.containsKey("pcr") && cv.containsKey("maxPain"), "chain carries PCR and max pain")
    }
}


object V17Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v17-learning-filter" to ::f)
    fun f() {
        val up = DoubleArray(30) { 100.0 + it }; val dn = DoubleArray(30) { 130.0 - it }
        T.check(FnoLab.rsi(up, 14, 29) > 95 && FnoLab.rsi(dn, 14, 29) < 5, "RSI extremes")
        T.check(Math.abs(FnoLab.rsi(DoubleArray(30) { if (it % 2 == 0) 100.0 else 101.0 }, 14, 29) - 50) < 10, "RSI choppy ~50")
        val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()
        // RSI bounce: fall then a turn up
        val cl = (0 until 25).map { if (it < 24) 25400.0 - it * 12 else 25400.0 - 23 * 12 + 100.0 }
        val b = FnoLab.parseBars(V15Tests.bars(day, 25400.0, cl))!!
        val m = b.min[b.n - 1] + 6
        val sg = FnoLab.signalsAll("NIFTY", b, m, emptySet())
        T.check(sg.any { it.strat.startsWith("RSI#") && it.side == "CE" }, "RSI bounce fires a call: ${sg.map { it.strat }}")
        // features are mirrored for puts
        val fc = FnoLab.features(b, m, "CE", 14.0); val fp = FnoLab.features(b, m, "PE", 14.0)
        T.eq(fc.size, FnoLab.NF, "feature count"); T.check(Math.abs(fc[3] + fp[3]) < 1e-9 && fc[4] == fp[4], "directional features mirror, others equal")
        // learning: winners have feature[3] high, losers low -> weight on it becomes positive and the filter separates them
        val lb = LabBook()
        repeat(80) { i -> val win = i % 2 == 0; val feats = listOf(1.0, 0.0, 0.0, if (win) 1.5 else -1.5, 0.0, 0.0, 0.0, 0.0)
            val t = LabTrade("t$i", "ORB#1", "NIFTY", "x", 1, "CE", 65, 100.0, 0, day, bids = arrayListOf(if (win) 140.0 else 70.0), feats = feats, p = FnoLab.predict(lb.w, feats)); lb.open.add(t); FnoLab.close(lb, t) }
        T.check(lb.w[3] > 0.5, "learned that moving with the trade helps: ${lb.w[3]}")
        T.check(FnoLab.predict(lb.w, listOf(1.0, 0.0, 0.0, 1.5, 0.0, 0.0, 0.0, 0.0)) > 0.7 && FnoLab.predict(lb.w, listOf(1.0, 0.0, 0.0, -1.5, 0.0, 0.0, 0.0, 0.0)) < 0.3, "separates winners from losers")
        T.check(lb.gate > 0, "cut-off raised once it would have saved money: ${lb.gate}")
        T.check((FnoLab.filterView(lb)["learned"] as List<*>).isNotEmpty(), "explains what it learned")
        T.check(Math.abs(LabBook.from(Json.parseObject(Json.stringify(lb.toMap()))).w[3] - lb.w[3]) < 1e-3, "model persists")
        // with no edge in the features the gate stays open
        val lb2 = LabBook(); repeat(60) { i -> val t = LabTrade("u$i", "ORB#1", "NIFTY", "x", 1, "CE", 65, 100.0, 0, day, bids = arrayListOf(if (i % 2 == 0) 140.0 else 70.0), feats = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), p = 0.5); lb2.open.add(t); FnoLab.close(lb2, t) }
        T.eq(lb2.gate, 0.0, "no false filtering without a pattern")
    }
}


object V18Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v18-whole-market" to ::w)
    fun w() {
        val csv = "UNDERLYING ,SYMBOL ,NOV-26 ,DEC-26\nNIFTY 50 ,NIFTY ,65 ,65\nRELIANCE INDUSTRIES LTD ,RELIANCE ,500 ,500\nMAHINDRA & MAHINDRA LTD ,M&M ,200 ,200\nFIN ,FINNIFTY ,60 ,60\n"
        T.eq(FnoUniverse.parseLots(csv), mapOf("NIFTY" to 65, "RELIANCE" to 500, "M&M" to 200, "FINNIFTY" to 60), "lots file parsed")
        T.eq(FnoUniverse.contractId("RELIANCE", "23-Nov-2026", 1360.0, "CE"), "RELIANCE26NOV1360CE", "monthly contract id")
        T.eq(FnoUniverse.contractId("IDEA", "29-Dec-2026", 12.5, "PE"), "IDEA26DEC12.5PE", "decimal strike id")
        T.check(FnoUniverse.isMonthly("23-Nov-2026", listOf("17-Nov-2026", "23-Nov-2026", "01-Dec-2026")) && !FnoUniverse.isMonthly("17-Nov-2026", listOf("17-Nov-2026", "23-Nov-2026")), "monthly = last expiry of the month")
        T.check(FnoUniverse.quoteBody(listOf("RELIANCE", "FINNIFTY")).contains("\"indexSymbolList\":[\"FINNIFTY\"]"), "indices go in the index list")
        val se = FnoUniverse.Series(prev = 100.0); se.sample(9 * 60 + 16, 101.0); se.sample(9 * 60 + 18, 103.0); se.sample(9 * 60 + 21, 99.0)
        val bb = se.toBars()!!
        T.eq(bb.n, 2, "5-min buckets"); T.eq(bb.h[0], 103.0, "bucket high"); T.eq(bb.c[0], 103.0, "bucket close")
        // integration: a stock breakout becomes a paper call on its monthly option
        val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()
        val p = FakePlatform.standard(); p.setTime(day, 9 * 60 + 41)
        val H = "https://www.nseindia.com"
        p.served[FnoUniverse.LOTS_URL] = csv
        p.served[Fno.infoUrl(H, "RELIANCE")] = "{\"expiryDates\":[\"23-Nov-2026\",\"29-Dec-2026\"],\"strikePrice\":[\"1340\",\"1350\",\"1360\",\"1370\"]}"
        p.served[Yahoo.tickerUrl(Params.YAHOO_HOST_1, "RELIANCE.NS", "1d", "5m")] = V15Tests.bars(day, 1340.0, listOf(1345.0, 1348.0, 1346.0, 1366.0, 1367.0))
        p.posted[Groww.INDEX_URL] = "{\"exchangeAggRespMap\":{\"NSE\":{\"priceLivePointsMap\":{\"RELIANCE\":{\"ltp\":1362.0,\"close\":1340.0,\"open\":1345.0,\"dayChangePerc\":1.64}},\"indexLivePointsMap\":{}}}}"
        p.posted[Groww.PRICES_URL] = "{\"RELIANCE26NOV1360CE\":{\"ltp\":18.5,\"lastTradeTime\":1795067564}}"
        val app = SwingApp(p)
        T.eq(app.fnoLabStep(), "ok", "step ok")
        val v = Json.parseObject(app.action("fnoLab", "{}"))!!
        val td = v.list("today").map { it.asObj() }
        T.check(td.any { it.str("sym") == "RELIANCE" && it.str("side") == "CE" && it.int("strike") == 1360 && Math.abs(it.num("entry") - 18.69) < 0.02 }, "RELIANCE call on its monthly option at live price + 1% slippage: $td")
        T.check(v.list("universe").size >= 4, "universe listed"); T.check(v.obj("movers").list("up").isNotEmpty(), "movers listed")
        p.posted[Groww.PRICES_URL] = "{\"RELIANCE26NOV1360CE\":{\"ltp\":30.0,\"lastTradeTime\":1795067600}}"
        p.setTime(day, 10 * 60); app.fnoLabStep()
        p.setTime(day, 15 * 60 + 16); app.fnoLabStep()
        val c = Json.parseObject(app.action("fnoLab", "{}"))!!.list("today").map { it.asObj() }.first { it.str("sym") == "RELIANCE" }
        T.check(c.str("status") == "TARGET" && c.num("pnl") > 0, "followed live and closed at target: $c")
        p.served[Fno.chainUrl(H, "RELIANCE", "23-Nov-2026")] = V12Tests.chainJson(1362.0, 20.0, 21.0)
        val ch = Json.parseObject(app.action("fno", "{\"sym\":\"RELIANCE\"}"))!!
        T.eq(ch.obj("chain").str("sym"), "RELIANCE", "option chain opens for a stock")
    }
}
