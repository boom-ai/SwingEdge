package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** `java -jar core.jar sample` prints example notifications and state from a short synthetic run (for eyeballing the wording). */
object Sample {
    fun run() {
        val keep = listOf(Params.GATE_MIN_TRADES.toDouble(), Params.GATE_MIN_OOS_TRADES.toDouble(), Params.GATE_MIN_AVG_PCT, Params.GATE_MIN_PF, Params.GATE_MIN_OOS_AVG_PCT)

    Params.GATE_MIN_TRADES = 1; Params.GATE_MIN_OOS_TRADES = 0; Params.GATE_MIN_AVG_PCT = -100.0; Params.GATE_MIN_PF = 0.0; Params.GATE_MIN_OOS_AVG_PCT = -100.0
    val p = FakePlatform.standard()
    val d0 = java.time.LocalDate.of(2026, 10, 5).toEpochDay().toInt()
    val app = SwingApp(p)
    p.setTime(d0, 500)
    app.action("addHolding", """{"sym":"DN01","qty":20,"avg":250}"""); app.action("addHolding", """{"sym":"UP01","qty":10,"avg":250}""")
    var took = false
    for (d in d0..d0 + 4) { var t = Clock.msAt(d, 0)
        while (true) { t = Clock.nextWake(t); if (Clock.epochDay(t) != d) break; p.nowMs = t; app.tick("alarm")
            if (!took) { val s = Json.parse(app.stateJson()).asObj().list("signals").firstOrNull().asObj(); if (s.isNotEmpty()) { app.action("takeTrade", "{\"signalId\":\"${s.str("id")}\"}"); took = true } } } }
    val seen = HashSet<String>()
    for (n in p.notes) if (seen.add(n.channel + n.title.take(8))) println("[${n.channel}] ${n.title}\n      ${n.body}")
    val st = Json.parse(app.stateJson()).asObj()
    println("NOTE: " + st.str("marketNote") + " | " + st.str("lastScanStatus"))
    for (h in st.list("holdings")) println("HOLDING " + Json.stringify(h).take(520))
    for (x in st.list("strategies")) println("STRAT " + x.asObj().str("id") + " " + x.asObj().str("verdict") + " " + Json.stringify(x.asObj()["stats"]) + " " + x.asObj().str("why"))
    println("TRADE " + Json.stringify(st.list("trades").firstOrNull()))
    println("SIGNAL " + Json.stringify(st.list("signals").firstOrNull()))
    println("state.json bytes=" + p.files["state.json"]!!.length + " cache bytes=" + p.files.filter { it.key.startsWith("daily") }.values.sumOf { it.length })
        Params.GATE_MIN_TRADES = keep[0].toInt(); Params.GATE_MIN_OOS_TRADES = keep[1].toInt(); Params.GATE_MIN_AVG_PCT = keep[2]; Params.GATE_MIN_PF = keep[3]; Params.GATE_MIN_OOS_AVG_PCT = keep[4]
    }
}
