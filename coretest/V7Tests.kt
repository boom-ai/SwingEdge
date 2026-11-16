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
