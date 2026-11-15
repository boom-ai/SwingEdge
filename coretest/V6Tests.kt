package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** REPORT7: money flow, sectors, stake changes, deals. */
object V6Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v6-flow-parse" to ::parse, "v6-flow-sectors" to ::sectors, "v6-flow-stakes" to ::stakes,
        "v6-flow-junk" to ::junk, "v6-flow-app" to ::app
    )

    val SECTORS = listOf("Automobile and Auto Components", "Forest Materials", "Information Technology", "Healthcare", "Power", "Metals & Mining")

    /** 240 stocks, 40 per sector; sector k's net money falls with k, so sector 0 has the most money coming in. */
    fun flowsCsv(n: Int = 240): String {
        val sb = StringBuilder("SYMBOL,CLOSE,R1,R5,R20,TO1,TO20,DLV1,DLV20,NET5,NET20,FLOW20,CMF20,DSURGE,N,ASOF\n")
        for (i in 0 until n) {
            val k = i % SECTORS.size
            val to20 = 5.0 + (i % 7)
            val net20 = (2.5 - k) * to20 * 0.8 + (i % 5 - 2) * 0.3
            val flow = net20 / (20 * to20)
            sb.append("MF$i,${100 + i},0.5,1.2,${(3 - k) * 1.5},${to20},${to20},45,${40 + k},${net20 / 4},${net20},${flow},${0.1 * (3 - k)},${1.0 + 0.05 * (3 - k)},70,2026-11-13\n")
        }
        sb.append("TINY,5,0,0,0,0.01,0.02,10,10,0,0,0,0,1,70,2026-11-13\n")
        return sb.toString()
    }

    fun indCsv(n: Int = 240): String {
        val sb = StringBuilder("SYMBOL,MACRO,SECTOR,INDUSTRY,BASIC\n")
        for (i in 0 until n) sb.append("MF$i,Macro,${SECTORS[i % SECTORS.size]},Ind ${i % 2},Basic ${(i / 6) % 3}\n")
        return sb.toString()
    }

    const val HOLDERS = "SYMBOL,QDATE,SUBMIT,PQDATE,PROMOTER,FII,MF,DII,RETAIL,PPROMOTER,PFII,PMF,PDII,PRETAIL\n" +
        "MF1,2026-09-30,2026-10-20,2026-06-30,55.0,12.0,8.0,3.0,15.0,54.0,11.0,7.0,3.0,16.0\n" +     // promoter +1, FII +1, MF +1
        "MF2,2026-09-30,2026-10-20,2026-06-30,40.0,10.1,5.0,2.0,20.0,40.0,10.0,5.0,2.0,20.0\n" +     // tiny moves only
        "MF3,2026-09-30,2026-10-21,2026-06-30,60.0,,,,,59.9,,,,\n" +                                 // promoter only, no XBRL
        "MF4,2026-09-30,2026-10-22,2026-06-30,30.0,20.0,10.0,5.0,10.0,31.0,18.0,9.0,5.0,11.0\n"      // promoter down, FII/MF up

    const val DEALS = "DATE,KIND,SYMBOL,CLIENT,SIDE,QTY,PRICE\n" +
        "2026-11-13,BLOCK,MF5,SOME MUTUAL FUND,BUY,1000000,250\n" +
        "2026-11-12,BULK,MF6,A TRADER,SELL,900000,120\n" +
        "2026-11-12,BULK,MF7,SMALL BUYER,BUY,1000,100\n"

    fun data() = MoneyFlow.parse(flowsCsv(), indCsv(), HOLDERS, DEALS)

    fun parse() {
        val d = data()
        T.check(d.ok, "240 stocks parse")
        T.eq(d.flows.size, 241, "every row incl. the illiquid one")
        T.eq(d.asof, "2026-11-13", "as-of date")
        T.eq(d.ind["MF7"]?.sector, SECTORS[1], "sector of MF7")
        T.eq(d.holders.size, 4, "holders")
        T.check(d.holders["MF3"]!!.delta(1).isNaN() && Math.abs(d.holders["MF3"]!!.delta(0) - 0.1) < 1e-9, "missing FII stays unknown, promoter change kept")
        T.eq(d.deals.size, 3, "deals")
        T.check(!MoneyFlow.liquid(d.flows["TINY"]!!), "₹0.02 cr a day is not liquid")
    }

    fun sectors() {
        val d = data()
        val mi = MoneyFlow.moneyIn(d)
        T.check(mi["TINY"] == null, "illiquid stock gets no money-in rank")
        T.check(mi.values.all { it in 0.0..100.0 }, "ranks 0..100")
        val secs = MoneyFlow.sectors(d, mi)
        T.eq(secs.size, 6, "six sectors")
        T.eq(secs.map { it.name }, SECTORS, "ranked by money coming in")
        T.check(secs.zipWithNext().all { (a, b) -> a.flowPct >= b.flowPct }, "flow % descending")
        T.eq(secs.map { it.rank }, (1..6).toList(), "ranks 1..6")
        val s0 = secs[0]
        T.check(s0.net20 > 0 && secs.last().net20 < 0, "money in at the top, out at the bottom")
        T.check(s0.top.size == 3 && s0.top.all { d.ind[it]?.sector == s0.name }, "top stocks belong to the sector")
        // flowPct = 100 x sum net20 / (20 x sum to20)
        val rs = d.flows.values.filter { MoneyFlow.liquid(it) && d.ind[it.sym]?.sector == s0.name }
        val want = 100.0 * rs.sumOf { it.net20 } / (20 * rs.sumOf { it.to20 })
        T.check(Math.abs(s0.flowPct - want) < 0.01, "sector flow % is turnover-weighted: ${s0.flowPct} vs $want")
        // picks: only BUY-grade scores in the top-5 sectors
        val ent = HashMap<String, ScoreEntry>()
        fun e(sym: String, sc: Double, lab: String) { ent[sym] = ScoreEntry(sym, SetupScore.compute(Series(sym, 0)), DoubleArray(5), sc, sc, lab, "", 1, 100) }
        e("MF0", 92.0, "BUY"); e("MF5", 95.0, "BUY"); e("MF6", 70.0, "WAIT"); e("MF12", 88.0, "BUY")
        val v = MoneyFlow.view(d, ent) { "$it Ltd" }
        @Suppress("UNCHECKED_CAST") val picks = (v["picks"] as List<Map<String, Any?>>).map { it["sym"] }
        T.eq(picks, listOf("MF0", "MF12"), "BUY-grade picks in hot sectors only (MF5 is in the last sector), best score first")
        @Suppress("UNCHECKED_CAST") val inflow = v["inflow"] as List<Map<String, Any?>>
        T.check(inflow.isNotEmpty() && inflow.all { ((it["moneyIn"] as Double) >= 90) }, "money-in list is the top decile")
        T.check(v["ready"] == true && (v["evidence"] as List<*>).size == 3, "ready, with the tested evidence lines")
        val sd = MoneyFlow.sector(d, SECTORS[0], ent) { it }
        T.check(sd["ok"] == true && (sd["stocks"] as List<*>).size == 40 && (sd["industries"] as List<*>).size == 3, "sector page: 40 stocks, 3 industries")
        T.check(MoneyFlow.sector(d, "Nope", ent) { it }["ok"] == false, "unknown sector is an error, not a crash")
    }

    fun stakes() {
        val d = data()
        val st = MoneyFlow.stakes(d) { it }
        fun syms(k: String) = (st[k] as List<*>).map { (it as Map<*, *>)["sym"] }
        T.eq(syms("promoters"), listOf("MF1"), "promoters +1 qualifies, +0.1 does not, a cut does not")
        T.eq(syms("fii"), listOf("MF4", "MF1"), "FIIs: +2 then +1")
        T.eq(syms("mf"), listOf("MF1", "MF4"), "MFs: +1 each, by symbol")
        T.eq(syms("together"), listOf("MF1"), "all big money: MF4 is out because promoters sold")
        T.eq(st["quarter"], "2026-09-30", "quarter")
        val deals = MoneyFlow.deals(d) { it }
        T.eq(deals.map { it["sym"] }, listOf("MF5"), "only buys of ₹5 cr or more")
        T.check(Math.abs((deals[0]["valueCr"] as Double) - 25.0) < 1e-9, "10 lakh x ₹250 = ₹25 cr")
        val one = MoneyFlow.forStock(d, "MF1")!!
        T.check((one["line"] as String).contains("raised their stake"), "stock line mentions the stake rise: ${one["line"]}")
        T.check(one["holders"] != null && one["flow"] != null && (one["sectorRank"] as Int) >= 1, "stock block has flow, holders and sector rank")
        T.check((MoneyFlow.forStock(d, "MF4")!!["line"] as String).contains("Promoters cut"), "promoter cut is called out")
        T.eq(MoneyFlow.forStock(d, "ZZZ"), null, "no data, no block")
    }

    fun junk() {
        for (x in listOf(null, "", "garbage", "SYMBOL\n,,,", "<html>Too many</html>", "SYMBOL,CLOSE\nA,abc\nB,-5\n")) {
            val d = MoneyFlow.parse(x, x, x, x)
            T.check(!d.ok && d.flows.isEmpty(), "junk input parses to nothing: $x")
            val v = MoneyFlow.view(d, emptyMap()) { it }
            T.check(v["ready"] == false, "not ready on junk")
        }
        val few = MoneyFlow.parse(flowsCsv(20), indCsv(20), null, null)
        T.check(!few.ok && MoneyFlow.moneyIn(few).isEmpty(), "too few stocks: no ranking")
    }

    fun app() {
        val p = FakePlatform.standard()
        p.setTime(java.time.LocalDate.of(2026, 11, 13).toEpochDay().toInt(), 20 * 60)
        val b = SwingApp.FLOW_BASE
        p.served[b + "flows.csv"] = flowsCsv(); p.served[b + "industries.csv"] = indCsv(); p.served[b + "holders.csv"] = HOLDERS; p.served[b + "deals.csv"] = DEALS
        p.keepUrls = true
        val app = SwingApp(p)
        val r = Json.parseObject(app.action("moneyFlow", "{}"))!!
        T.check(r.bool("ok") && r.bool("ready"), "money tab loads: " + r.str("error"))
        T.eq(r.list("sectors").size, 6, "six sectors")
        val hits = p.urls.count { it.startsWith(b) }
        app.action("moneyFlow", "{}")
        T.eq(p.urls.count { it.startsWith(b) }, hits, "second open within hours does not download again")
        T.check(p.files.containsKey("mf_flows.csv"), "saved for offline use")
        // a new app instance works offline from the saved copy
        p.offline = true
        val app2 = SwingApp(p)
        val r2 = Json.parseObject(app2.action("moneyFlow", "{}"))!!
        T.check(r2.bool("ready") && r2.list("sectors").size == 6, "offline: saved copy")
        p.offline = false
        // a broken download keeps the good copy
        p.served[b + "flows.csv"] = "<html>rate limited</html>"
        p.nowMs += 5L * 3600 * 1000
        val r3 = Json.parseObject(app2.action("moneyFlow", "{\"force\":true}"))!!
        T.check(r3.bool("ready") && r3.list("sectors").size == 6, "bad download keeps the previous data")
        val s = Json.parseObject(app2.action("flowSector", "{\"name\":\"Healthcare\"}"))!!
        T.check(s.bool("ok") && s.list("stocks").size == 40, "sector page from the app")
    }
}
