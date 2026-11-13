package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

object SimTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "sim-16-trading-days" to ::fullSim, "fresh-install-offline" to ::offline, "paused-emits-nothing" to ::paused,
        "bear-blocks-buys" to ::bear, "max-open-trades" to ::maxOpen, "corrupt-files" to ::corrupt,
        "state-not-blocked" to ::nonBlocking, "actions" to ::actions, "triggers-and-fetching" to ::triggers
    )

    private val BANNED = listOf("guaranteed", "guarantee", "sure shot", "sure-shot", "risk-free", "risk free", "assured", "no risk")
    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()

    // ------------------------------------------------------------------ SPEC schema
    private val SCHEMA: Map<String, Any?> = Json.parse("""{
     "now": 0, "marketOpen": true, "marketNote": "", "lastScanAt": 0, "lastScanStatus": "", "nextScanAt": 0, "busy": null,
     "settings": {"capital":1,"riskPct":0.75,"maxOpenTrades":4,"costPct":0.25,"notifyBuy":true,"notifySell":true,"notifyBrief":true},
     "regime": {"label":"BULL","niftyLtp":0,"niftyChgPct":0.4,"note":""},
     "signals": [{"id":"","sym":"","name":"","strategyId":"","strategyLabel":"","createdAt":0,"validTill":0,
       "ltp":0,"entry":0,"entryMax":0,"sl":0,"t1":0,"t2":0,"qty":0,"investRs":0,"riskRs":0,"rewardRs":0,"holdDays":6,
       "edge":{"n":0,"winPct":0,"avgPct":0,"pf":0},"reasons":[""],"status":"NEW"}],
     "trades": [{"id":"","sym":"","name":"","strategyLabel":"","status":"OPEN","openedAt":0,"entry":0,"qty":0,
       "sl":0,"t1":0,"t2":0,"day":1,"maxDays":6,"ltp":0,"pnlRs":0,"pnlPct":0,
       "action":"HOLD","actionNote":"","t1Done":false,"closedAt":0,"exitPrice":0,"result":""}],
     "holdings": [{"sym":"","name":"","qty":0,"avg":0,"ltp":0,"valueRs":0,"pnlRs":0,"pnlPct":0,"dayChgPct":0,
       "verdict":"HOLD","headline":"","stop":0,"bookAt":0,"reasons":[""],"dataOk":true,"strength":0,"noDataWhy":""}],
     "portfolio": {"investedRs":0,"valueRs":0,"pnlRs":0,"pnlPct":0,"dayPnlRs":0,"sellCount":0,"trimCount":0},
     "strategies": [{"id":"","label":"","desc":"","verdict":"LIVE","testedAt":0,
       "stats":{"n":0,"winPct":0,"avgPct":0,"pf":0,"maxDdPct":0,"oosN":0,"oosAvgPct":0},"why":""}],
     "scorecard": {"n":0,"winPct":0,"netRs":0,"avgPct":0,"paperN":0,"paperWinPct":0,"paperAvgPct":0,
       "recent":[{"sym":"","closedAt":0,"result":"","pnlRs":0,"pnlPct":0,"paper":false}]},
     "feed": [{"ts":0,"kind":"buy","title":"","body":""}],
     "watch": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"ema10":0,"ema20":0,"ema50":0,"ema100":0,"ema200":0,"gapPct":0,"daysSince":0,"barDay":0,"spark":[0],"status":""}],
     "myWatch": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"spark":[0],"strength":0,"stack":"BULLISH","crossText":"","dataOk":true}],
     "ideas": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"score":0,"label":"BUY","headline":"",
                "entryLow":0,"entryHigh":0,"stop":0,"t1":0,"t2":0,"rr":0,"horizonDays":60,"spark":[0],"barDay":0,"proven":false}],
     "targets": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"target":0,"stop":0,"note":"","setAt":0,"refPrice":0,
                  "status":"ACTIVE","hitAt":0,"toTargetPct":0,"toStopPct":0,"progressPct":0}],
     "track": {"startedDay":0,"lastDay":0,"replayDone":true,"busyReplay":false,
       "live": {"P1":{"buy":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"control":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0}},"P2":{"buy":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"control":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0}},"avoid":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"avoidCtrl":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"edgePct":0,"edgeT":0,"upgraded":false,"needText":""},
       "replay": {"P1":{"buy":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"control":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0}},"P2":{"buy":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"control":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0}},"avoid":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"avoidCtrl":{"n":0,"open":0,"closed":0,"winPct":0,"avgPct":0,"medianPct":0,"worstPct":0,"avgSessions":0},"edgePct":0,"edgeT":0,"fromDay":0,"toDay":0},
       "recent": [{"sym":"","group":"BUY","day":0,"plan":"P1","status":"OPEN","fill":0,"exit":0,"retPct":0,"sessions":0}]}
    }""").asObj()

    private val ENUMS = mapOf(
        "regime.label" to setOf("BULL", "NEUTRAL", "BEAR"), "signals.status" to setOf("NEW", "TAKEN", "SKIPPED", "EXPIRED"),
        "trades.status" to setOf("OPEN", "CLOSED"), "trades.action" to setOf("HOLD", "BOOK_HALF", "EXIT", "TIME_EXIT"),
        "holdings.verdict" to setOf("HOLD", "TRIM", "SELL", "WATCH"), "strategies.verdict" to setOf("LIVE", "PAUSED", "UNTESTED"),
        "feed.kind" to setOf("buy", "sell", "brief", "info", "warn"), "myWatch.stack" to setOf("BULLISH", "MIXED", "BEARISH"),
        "ideas.label" to setOf("BUY"), "targets.status" to setOf("ACTIVE", "HIT_TARGET", "HIT_STOP"),
        "track.recent.group" to setOf("BUY", "CONTROL", "AVOID", "AVOID_CTRL"), "track.recent.plan" to setOf("P1", "P2"),
        "track.recent.status" to setOf("OPEN", "CLOSED", "SKIPPED")
    )

    private fun shape(path: String, tpl: Any?, v: Any?, errs: ArrayList<String>) {
        when (tpl) {
            is Map<*, *> -> {
                if (v !is Map<*, *>) { errs.add("$path not an object"); return }
                if (v.keys != tpl.keys) errs.add("$path keys differ: missing ${tpl.keys - v.keys} extra ${v.keys - tpl.keys}")
                for ((k, t) in tpl) if (v.containsKey(k)) shape(if (path.isEmpty()) "$k" else "$path.$k", t, v[k], errs)
            }
            is List<*> -> {
                if (v !is List<*>) { errs.add("$path not an array"); return }
                for (x in v) shape(path, tpl[0], x, errs)
            }
            is Double -> if (v !is Double || v.isNaN() || v.isInfinite()) errs.add("$path not a number: $v")
            is String -> { if (v !is String) errs.add("$path not a string: $v") else ENUMS[path]?.let { if (v !in it) errs.add("$path bad value $v") } }
            is Boolean -> if (v !is Boolean) errs.add("$path not a boolean: $v")
            null -> if (path == "busy" && v != null) {
                val b = v as? Map<*, *>
                if (b == null || b["label"] !is String || b["pct"] !is Double || b.size != 2) errs.add("busy malformed: $v")
            }
        }
    }

    /** Parses stateJson, checks it against the SPEC schema, returns the parsed object. */
    fun state(app: SwingApp, where: String): Map<String, Any?> {
        val txt = app.stateJson()
        val o = Json.parseObject(txt)
        if (o == null) { T.check(false, "stateJson is not valid JSON at $where: " + txt.take(120)); return emptyMap() }
        val errs = ArrayList<String>()
        shape("", SCHEMA, o, errs)
        if (errs.isNotEmpty()) T.check(false, "schema at $where: " + errs.take(4))
        if (o.list("feed").size > 100) T.check(false, "feed over 100 at $where")
        val low = txt.lowercase()
        for (w in BANNED) if (low.contains(w)) T.check(false, "banned word '$w' in state at $where")
        return o
    }

    private fun act(app: SwingApp, name: String, args: String): Map<String, Any?> {
        val r = Json.parseObject(app.action(name, args))
        T.check(r != null && r["ok"] is Boolean, "action $name returns {ok:..}")
        return r ?: emptyMap()
    }

    private class Saved {
        val v = listOf(Params.GATE_MIN_TRADES, Params.GATE_MIN_OOS_TRADES)
        val d = listOf(Params.GATE_MIN_AVG_PCT, Params.GATE_MIN_PF, Params.GATE_MIN_OOS_AVG_PCT)
        fun restore() { Params.GATE_MIN_TRADES = v[0]; Params.GATE_MIN_OOS_TRADES = v[1]; Params.GATE_MIN_AVG_PCT = d[0]; Params.GATE_MIN_PF = d[1]; Params.GATE_MIN_OOS_AVG_PCT = d[2] }
    }
    private fun gatesOpen() { Params.GATE_MIN_TRADES = 1; Params.GATE_MIN_OOS_TRADES = 0; Params.GATE_MIN_AVG_PCT = -100.0; Params.GATE_MIN_PF = 0.0; Params.GATE_MIN_OOS_AVG_PCT = -100.0 }
    private fun gatesShut() { Params.GATE_MIN_TRADES = 1_000_000 }

    /** Three hard down days ending on [last] so that DIP_UPTREND fires on [last] (or a day earlier). */
    private fun scriptDip(y: FakeYahoo, sym: String, last: Int) {
        val days = y.tradingDaysUpTo(last, 4)
        var prev = y.bar(sym, days[0])!![3]
        for (d in days.drop(1)) {
            val c = prev * 0.975
            y.setBar(sym, d, prev, prev * 1.001, c * 0.999, c, 3_000_000.0)
            prev = c
        }
    }

    /** Runs every wake of [d] (or the given window), ticking twice per wake and checking idempotence + invariants. */
    private class Runner(val p: FakePlatform) {
        var app = SwingApp(p)
        var ticks = 0
        val seenSignals = HashSet<String>()
        var onSignal: ((Map<String, Any?>) -> Unit)? = null
        var afterTick: ((Int, Int, Map<String, Any?>) -> Unit)? = null

        private fun fingerprint(s: Map<String, Any?>): String =
            s.list("signals").joinToString { val m = it.asObj(); m.str("id") + m.str("status") } + "#" +
                s.list("trades").joinToString { val m = it.asObj(); m.str("id") + m.str("status") + m.str("action") + m.str("t1Done") } + "#" +
                s.list("holdings").joinToString { val m = it.asObj(); m.str("sym") + m.str("verdict") } + "#" + s.list("feed").size

        fun tickAt(d: Int, min: Int, trigger: String = "alarm"): Map<String, Any?> {
            p.setTime(d, min, 7)
            val r = try { app.tick(trigger) } catch (t: Throwable) { T.check(false, "tick threw $t"); "" }
            T.check(!r.startsWith("Error"), "tick status clean at ${Clock.dayLabel(d)} ${Clock.hhmm(min)}: $r")
            ticks++
            val s1 = state(app, Clock.dayLabel(d) + " " + Clock.hhmm(min))
            // second tick in the same slot must change nothing
            val notes = p.notes.size
            val calls = p.urlCount
            p.setTime(d, min, 31)
            val r2 = app.tick(trigger)
            val s2 = state(app, "repeat")
            if (trigger == "alarm") {
                T.check(p.notes.size == notes, "repeat tick posted no notification at ${Clock.dayLabel(d)} ${Clock.hhmm(min)} (was $r, repeat $r2)")
                T.check(fingerprint(s1) == fingerprint(s2), "repeat tick changed nothing at ${Clock.dayLabel(d)} ${Clock.hhmm(min)}")
                T.check(p.urlCount == calls, "repeat tick made no network calls")
                T.check(r2 == "Nothing due", "repeat tick reports nothing due: $r2")
            }
            invariants(s2, d, min)
            for (x in s2.list("signals")) {
                val m = x.asObj()
                if (seenSignals.add(m.str("id"))) onSignal?.invoke(m)
            }
            val s3 = if (onSignal != null) state(app, "after hooks") else s2
            afterTick?.invoke(d, min, s3)
            return s3
        }

        fun invariants(s: Map<String, Any?>, d: Int, min: Int) {
            val set = s.obj("settings")
            val cap = set.num("capital"); val risk = set.num("riskPct"); val maxOpen = set.int("maxOpenTrades")
            val sigs = s.list("signals").map { it.asObj() }
            val open = s.list("trades").map { it.asObj() }.count { it.str("status") == "OPEN" }
            val fresh = sigs.count { it.str("status") == "NEW" }
            T.check(open + fresh <= maxOpen, "open trades + NEW signals ($open + $fresh) within maxOpenTrades $maxOpen")
            T.check(sigs.map { it.str("sym") + "@" + Clock.epochDay(it.long("createdAt")) }.let { it.size == it.toSet().size }, "one signal per symbol per day")
            for (g in sigs) {
                val cm = Clock.minuteOfDay(g.long("createdAt")); val cd = Clock.epochDay(g.long("createdAt"))
                T.check(cm in Params.BUY_WINDOW_START_MIN..Params.BUY_WINDOW_END_MIN && p.yahoo.isTradingDay(cd), "signal ${g.str("id")} created inside the buy window on a trading day")
                T.check(g.num("riskRs") <= cap * risk / 100 + 0.01 && g.num("investRs") <= cap * 0.25 + 0.01 && g.int("qty") >= 1, "signal ${g.str("id")} sized within risk (${g.num("riskRs")}) and 25% cap")
                T.check(g.num("sl") < g.num("entry") && g.num("entryMax") >= g.num("entry") && g.obj("edge").int("n") >= 1 && g.list("reasons").size >= 2, "signal ${g.str("id")} has stop below entry, edge numbers and reasons")
                T.check(Math.abs(g.num("riskRs") - (g.int("qty") * (g.num("entry") - g.num("sl")) + g.num("investRs") * set.num("costPct") / 100)) < 0.6, "riskRs includes charges")
                T.check(g.str("sym") !in setOf("ERRSYM", "GARB", "DEAD", "THIN", "PENNY", "NEWIPO"), "no signal for broken/illiquid/penny/new symbols: ${g.str("sym")}")
                if (g.str("status") == "NEW") T.check(p.nowMs < g.long("validTill"), "NEW signal not past its validity")
                T.check(g.long("validTill") == Clock.msAt(Clock.nextWeekday(cd), Clock.OPEN_MIN), "validTill is the next weekday 09:15")
            }
        }

        fun runDay(d: Int, restart: Boolean = true) {
            if (restart) app = SwingApp(p)   // a new process every morning: everything must come back from files
            var t = Clock.msAt(d, 0)
            while (true) {
                t = Clock.nextWake(t)
                if (Clock.epochDay(t) != d) break
                p.nowMs = t
                T.check(app.nextWakeAt() > t, "nextWakeAt moves forward")
                tickAt(d, Clock.minuteOfDay(t))
            }
        }
    }

    // ------------------------------------------------------------------ the long simulation
    fun fullSim() {
        val saved = Saved()
        gatesOpen()
        try {
            val p = FakePlatform.standard()
            val y = p.yahoo
            val start = day(2026, 10, 5)             // Monday
            val holiday = day(2026, 10, 14)          // Wednesday, exchange shut, no data
            y.holidays.add(holiday)
            val end = day(2026, 10, 27)
            scriptDip(y, "UP03", day(2026, 10, 7))
            scriptDip(y, "UP07", day(2026, 10, 12))
            scriptDip(y, "UP11", day(2026, 10, 16))
            // a holding that collapses on heavy volume one day
            val crashDay = day(2026, 10, 20)
            val pc = y.bar("UP15", y.tradingDaysUpTo(crashDay, 2)[0])!![3]
            y.setBar("UP15", crashDay, pc * 0.995, pc * 0.997, pc * 0.89, pc * 0.90, 14_000_000.0)

            val run = Runner(p)
            p.setTime(start, 8 * 60 + 40)
            state(run.app, "brand new install")
            T.eq(act(run.app, "saveSettings", """{"maxOpenTrades":20}""")["ok"], true, "settings saved")
            for (h in listOf("UP01" to 10, "DN01" to 20, "XTRA1" to 5, "NOSUCH" to 3, "M&M" to 4, "UP15" to 6))
                T.eq(act(run.app, "addHolding", """{"sym":"${h.first}","qty":${h.second},"avg":250}""")["ok"], true, "holding ${h.first} added")
            var s = state(run.app, "after adding holdings")
            fun hold(st: Map<String, Any?>, sym: String) = st.list("holdings").map { it.asObj() }.first { it.str("sym") == sym }
            T.check(hold(s, "UP01").bool("dataOk") && hold(s, "UP01").num("ltp") > 0, "holding gets prices immediately")
            T.eq(hold(s, "DN01").str("verdict"), "SELL", "falling holding is SELL: " + hold(s, "DN01").str("headline"))
            T.check(hold(s, "XTRA1").bool("dataOk"), "holding outside the universe is fetched too")
            T.check(!hold(s, "NOSUCH").bool("dataOk") && hold(s, "NOSUCH").str("verdict") == "WATCH", "unknown symbol -> WATCH, dataOk false")
            T.check(hold(s, "M&M").bool("dataOk"), "M&M fetched (URL-encoded)")
            T.eq(p.notes.size, 0, "adding holdings in the app does not push notifications")

            // ---- event-driven scripts for three signals
            class Plan(val sym: String) { var sig: Map<String, Any?>? = null; var tradeId = ""; var sigDay = 0; val actions = ArrayList<String>() }
            val a = Plan("UP03"); val b = Plan("UP07"); val c = Plan("UP11")
            run.onSignal = { g ->
                val sym = g.str("sym")
                val sd = Clock.epochDay(g.long("createdAt"))
                val e = g.num("entry"); val sl = g.num("sl"); val t1 = g.num("t1"); val t2 = g.num("t2")
                if (sym == a.sym && a.sig == null) {
                    a.sig = g; a.sigDay = sd
                    T.eq(g.str("strategyId"), "DIP_UPTREND", "scripted dip produced a DIP_UPTREND signal")
                    val r = act(run.app, "takeTrade", """{"signalId":"${g.str("id")}"}""")
                    T.eq(r["ok"], true, "takeTrade ok"); a.tradeId = r.str("tradeId")
                    val d1 = y.nextTradingDay(sd); val d2 = y.nextTradingDay(d1); val d3 = y.nextTradingDay(d2)
                    y.setBar(sym, d1, e, minOf(e * 1.004, t1 - 0.2), e * 0.996, e * 1.002, 2e6)        // quiet day -> HOLD
                    y.setBar(sym, d2, e * 1.002, t1 * 1.002, e * 1.001, minOf(t1 * 0.999, t2 - 0.2), 2e6) // tags T1 -> BOOK_HALF
                    y.setBar(sym, d3, e * 1.004, e * 1.005, e * 0.99, e * 0.993, 2e6)                   // back through cost -> EXIT
                    for (k in 1..6) { val dx = y.tradingDaysUpTo(end, 60).filter { it > d3 }.getOrNull(k - 1) ?: break; y.setBar(sym, dx, e * 0.99, e * 0.995, e * 0.985, e * 0.99, 2e6) }
                } else if (sym == b.sym && b.sig == null) {
                    b.sig = g; b.sigDay = sd
                    val price = Math.round(e * 1.002 * 100) / 100.0
                    val r = act(run.app, "takeTrade", """{"signalId":"${g.str("id")}","price":$price,"qty":3}""")
                    T.eq(r["ok"], true, "takeTrade with own price and quantity"); b.tradeId = r.str("tradeId")
                    var dx = sd
                    for (k in 1..8) { dx = y.nextTradingDay(dx); y.setBar(sym, dx, price * 1.001, minOf(price * 1.003, t1 - 0.2), maxOf(price * 0.997, sl + 0.5), price * 1.001, 2e6) }
                } else if (sym == c.sym && c.sig == null) {
                    c.sig = g; c.sigDay = sd
                    T.eq(act(run.app, "skipSignal", """{"signalId":"${g.str("id")}"}""")["ok"], true, "skipSignal ok")
                    val d1 = y.nextTradingDay(sd)
                    y.setBar(sym, d1, e * 0.999, e * 1.001, sl * 0.99, sl * 0.995, 2e6)                 // straight through the stop
                }
            }
            fun trade(st: Map<String, Any?>, id: String) = st.list("trades").map { it.asObj() }.firstOrNull { it.str("id") == id }
            var holidayNoteSeen = false
            var buyHintSeen = false
            var okStatusSeen = false
            run.afterTick = { d, min, st ->
                for (pl in listOf(a, b)) if (pl.tradeId.isNotEmpty()) trade(st, pl.tradeId)?.let { t ->
                    if (t.str("status") == "OPEN") {
                        val tag = t.str("action") + "@" + (d - pl.sigDay) + "/" + t.int("day")
                        if (pl.actions.lastOrNull()?.substringBefore("@") != t.str("action")) pl.actions.add(tag + "@" + Clock.hhmm(min))
                    }
                }
                if (d == holiday && min > Clock.HOLIDAY_CHECK_MIN && min < Clock.CLOSE_MIN) {
                    if (st.str("marketNote").contains("holiday")) holidayNoteSeen = true
                    T.check(!st.bool("marketOpen"), "marketOpen false on the detected holiday")
                }
                if (d != holiday && min in 600..800 && st.str("marketNote").contains("New buy ideas come at 2:45 pm")) buyHintSeen = true
                if (d != holiday && min > 600 && min < 930) {
                    T.check(st.bool("marketOpen"), "marketOpen true in session")
                    if (Regex("^OK · \\d+/\\d+ stocks$").matches(st.str("lastScanStatus"))) okStatusSeen = true
                    val m = Regex("OK · (\\d+)/(\\d+)").find(st.str("lastScanStatus"))
                    if (m != null) T.check(m.groupValues[2].toInt() == 44 && m.groupValues[1].toInt() == 41, "status counts 41/44 (ERRSYM, GARB, DEAD skipped; H2ONLY via fallback): " + st.str("lastScanStatus"))
                }
                if (min == Clock.FIRST_SCAN_MIN) T.check(st.list("signals").none { val m = it.asObj(); m.str("status") == "NEW" }, "yesterday's untaken signals expired by 09:20")
            }

            var d = start
            var tradingDays = 0
            while (d <= end) {
                if (Clock.isWeekday(d)) {
                    run.runDay(d)
                    if (d != holiday) tradingDays++
                } else {
                    // weekend: alarms should not exist, but stray ticks must be harmless
                    val n0 = p.notes.size
                    run.app = SwingApp(p)
                    p.setTime(d, 11 * 60)
                    T.check(!run.app.tick("alarm").startsWith("Error") && !run.app.tick("open").startsWith("Error") && !run.app.tick("boot").startsWith("Error"), "weekend ticks are harmless")
                    val st = state(run.app, "weekend")
                    T.check(!st.bool("marketOpen") && st.str("marketNote").contains("weekend"), "weekend market note: " + st.str("marketNote"))
                    T.eq(p.notes.size, n0, "no notifications on a weekend")
                    T.eq(Clock.epochDay(run.app.nextWakeAt()), Clock.nextWeekday(d), "next wake is Monday")
                }
                d++
            }
            s = state(run.app, "end of simulation")
            println("      $tradingDays trading days, ${run.ticks} ticks (each repeated), ${run.seenSignals.size} signals, ${p.notes.size} notifications, ${p.urlCount} URLs")
            T.eq(tradingDays, 16, "16 trading days simulated (17 weekdays, one holiday, three weekends)")
            T.check(holidayNoteSeen, "holiday detected and shown in marketNote")
            T.check(buyHintSeen, "marketNote says when buy ideas come")
            T.check(okStatusSeen, "scan status like 'OK · 41/44 stocks'")
            T.check(run.seenSignals.none { it.startsWith("S$holiday-") }, "no signals on the holiday")

            // ---- trade A: signal -> take -> HOLD -> BOOK_HALF -> EXIT at cost, hand-checked P&L
            T.check(a.sig != null && b.sig != null && c.sig != null, "all three scripted dips produced signals")
            if (a.sig != null) {
                val g = a.sig!!
                val e = g.num("entry"); val t1 = g.num("t1"); val q = g.int("qty")
                T.eq(q, SwingApp.positionSize(100000.0, 0.75, 0.25, e, g.num("sl")), "signal quantity follows the sizing formula (rounded prices)")
                T.near(g.num("entryMax"), e * 1.005, 0.011, "entryMax = entry x 1.005")
                T.eq(a.actions.map { it.substringBefore("@") }, listOf("HOLD", "BOOK_HALF", "HOLD", "EXIT").let { if (a.actions.size == 3) listOf("HOLD", "BOOK_HALF", "EXIT") else it }, "A lifecycle HOLD -> BOOK_HALF -> EXIT: ${a.actions}")
                T.check(a.actions.any { it.startsWith("BOOK_HALF@") && it.contains("/3@") }, "BOOK_HALF on trading day 3: ${a.actions}")
                T.check(a.actions.any { it.startsWith("EXIT@") && it.contains("/4@") }, "EXIT on trading day 4: ${a.actions}")
                val tr = trade(s, a.tradeId)!!
                T.check(tr.bool("t1Done") && tr.str("action") == "EXIT" && tr.str("status") == "OPEN" && Math.abs(tr.num("sl") - e) < 0.011, "trade A waits for the user with EXIT, stop at cost")
                T.eq(p.notes.count { it.title == "UP03 reached Target 1" }, 1, "Target-1 notification exactly once")
                T.eq(p.notes.count { it.title == "EXIT UP03 now" }, 1, "EXIT notification exactly once")
                T.check(p.notes.first { it.title == "UP03 reached Target 1" }.body.contains("Sell " + (q / 2) + " share"), "BOOK_HALF text names the half quantity")
                val r = act(run.app, "closeTrade", """{"tradeId":"${a.tradeId}","price":$e}""")
                val want = (q / 2) * (t1 - e) - e * q * 0.25 / 100
                T.near(r.num("pnlRs"), want, 0.02, "A P&L after charges = half x (T1 - entry) - 0.25% of entry turnover")
                T.near(r.num("pnlPct"), want / (e * q) * 100, 0.011, "A P&L %")
            }
            // ---- trade B: own price/qty, flat for six days across the holiday -> TIME_EXIT from 15:00 on day 6
            if (b.sig != null) {
                val tr = trade(s, b.tradeId)!!
                val e = tr.num("entry")
                T.eq(tr.int("qty"), 3, "B quantity as entered"); T.near(e, Math.round(b.sig!!.num("entry") * 1.002 * 100) / 100.0, 0.011, "B entry as entered")
                T.eq(b.actions.map { it.substringBefore("@") }, listOf("HOLD", "TIME_EXIT"), "B lifecycle HOLD -> TIME_EXIT: ${b.actions}")
                val te = b.actions.last()
                T.check(te.contains("/6@15:01"), "TIME_EXIT called on trading day 6 at the 15:01 scan (holiday not counted): $te")
                T.eq(p.notes.count { it.title.startsWith("UP07: time exit") }, 1, "time-exit notification exactly once")
                val px = Math.round(e * 1.001 * 100) / 100.0
                val r = act(run.app, "closeTrade", """{"tradeId":"${b.tradeId}","price":$px}""")
                T.near(r.num("pnlRs"), 3 * (px - e) - e * 3 * 0.25 / 100, 0.011, "B P&L = 3 x (exit - entry) - charges")
                T.eq(act(run.app, "closeTrade", """{"tradeId":"${b.tradeId}","price":$px}""")["ok"], false, "closing twice is refused")
            }
            s = state(run.app, "after closing")
            val sc = s.obj("scorecard")
            T.eq(sc.int("n"), 2, "scorecard counts the two real trades")
            T.eq(s.list("trades").count { it.asObj().str("status") == "CLOSED" }, 2, "two closed trades listed")
            // ---- signal C: skipped -> tracked on paper -> stopped out
            if (c.sig != null) {
                val g = c.sig!!
                val rec = sc.list("recent").map { it.asObj() }.firstOrNull { it.str("sym") == "UP11" && it.bool("paper") && Clock.epochDay(it.long("closedAt")) == y.nextTradingDay(c.sigDay) }
                T.check(rec != null, "skipped signal was tracked as a paper trade and closed")
                if (rec != null) {
                    T.eq(rec.str("result"), "Stop-loss hit", "paper result")
                    T.near(rec.num("pnlPct"), (g.num("sl") - g.num("entry")) / g.num("entry") * 100 - 0.25, 0.02, "paper loss = stop distance + charges")
                }
                T.check(s.list("signals").map { it.asObj() }.first { it.str("id") == g.str("id") }.str("status") == "SKIPPED", "skipped signal stays SKIPPED")
                T.check(s.list("trades").none { it.asObj().str("sym") == "UP11" && it.asObj().long("openedAt") == g.long("createdAt") }, "paper trades are not listed as the user's trades")
            }
            T.check(sc.int("paperN") >= 3, "paper scorecard has results (${sc.int("paperN")} closed, ${sc.num("paperWinPct")}% won, avg ${sc.num("paperAvgPct")}%)")
            T.check(s.list("signals").map { it.asObj() }.any { it.str("status") == "EXPIRED" }, "untaken signals expire")
            // ---- holdings: SELL call once
            T.eq(p.notes.count { it.title == "UP15 moved to SELL" }, 1, "holding collapse notified as SELL exactly once (25 scans that day)")
            T.check(p.notes.count { it.title == "UP15 slipped below its stop" } <= 1, "stop-cross notified at most once")
            T.eq(Clock.epochDay(p.notes.first { it.title == "UP15 moved to SELL" }.at), crashDay, "SELL call came on the crash day")
            T.eq(p.notes.count { it.title.startsWith("DN01") }, 0, "a holding that was already SELL when added is not re-announced")
            T.check(p.notes.groupBy { it.title + "@" + Clock.epochDay(it.at) }.filter { it.key.contains("moved to") || it.key.contains("book part") }.values.all { it.size == 1 }, "no holding verdict notified twice in a day")
            // ---- notifications overall
            val dup = p.notes.groupBy { it.channel + "|" + it.id + "|" + it.title + "|" + it.body }.filter { it.value.size > 1 }
            T.check(dup.isEmpty(), "no duplicate notifications: " + dup.keys.take(2))
            T.eq(p.notes.count { it.id == SwingApp.NID_BRIEF }, 17, "one morning brief per weekday")
            T.eq(p.notes.count { it.id == SwingApp.NID_EOD }, 16, "one day's scorecard per trading day (none on the holiday)")
            T.check(p.notes.filter { it.id == SwingApp.NID_BRIEF }.all { Clock.minuteOfDay(it.at) == Clock.BRIEF_MIN } && p.notes.filter { it.id == SwingApp.NID_EOD }.all { Clock.minuteOfDay(it.at) == Clock.EOD_MIN }, "brief at 08:55, scorecard at 15:40")
            val buys = p.notes.filter { it.channel == "buy" }
            T.eq(buys.size, run.seenSignals.size, "one buy notification per signal")
            T.check(buys.all { it.title.startsWith("BUY ") && it.title.contains("₹") && it.body.contains("SL ₹") && it.body.contains("test trades") }, "buy texts carry levels and tested numbers: " + buys.firstOrNull()?.let { it.title + " | " + it.body })
            T.check(buys.map { it.id }.toSet().size == buys.size, "stable distinct ids per buy idea")
            T.check(p.notes.all { it.channel in setOf("buy", "sell", "brief") && it.title.isNotBlank() && it.body.isNotBlank() && it.body.length < 400 }, "channels valid, texts short")
            val allText = (p.notes.joinToString(" ") { it.title + " " + it.body } + p.files[SwingApp.STATE_FILE]).lowercase()
            T.check(BANNED.none { allText.contains(it) }, "no banned promise words anywhere")
            T.check(p.logs.none { it.contains("failed") || it.contains("threw") }, "no internal failures logged: " + p.logs.filter { it.contains("failed") || it.contains("threw") }.take(2))
            T.check(p.files.keys.count { it.startsWith("daily_cache_") } in 2..Params.CACHE_SHARDS && p.files.values.sumOf { it.length } < 2_000_000, "cache in a few compact files (${p.files.values.sumOf { it.length } / 1024} KB for 47 symbols)")
            T.check(s.list("strategies").all { (it.asObj().str("verdict") == "LIVE" || it.asObj().str("id") == "WASHOUT_DIP") && it.asObj().long("testedAt") > 0 }, "strategies tested automatically (gates opened for this test)")
        } finally {
            saved.restore()
        }
    }

    // ------------------------------------------------------------------ small scenario helper
    private fun dipScenario(p: FakePlatform, syms: List<String>, maxOpen: Int?): Pair<Runner, Map<String, Any?>> {
        val d0 = day(2026, 10, 5)
        for (s in syms) scriptDip(p.yahoo, s, d0 + 2)
        val run = Runner(p)
        p.setTime(d0, 8 * 60 + 30)
        if (maxOpen != null) act(run.app, "saveSettings", """{"maxOpenTrades":$maxOpen}""")
        for (d in d0..d0 + 2) run.runDay(d)
        return Pair(run, state(run.app, "scenario end"))
    }

    fun paused() {
        val saved = Saved()
        gatesShut()
        try {
            val p = FakePlatform.standard()
            var noteSeen = false
            val d0 = day(2026, 10, 5)
            scriptDip(p.yahoo, "UP03", d0 + 2)
            val run = Runner(p)
            run.afterTick = { _, min, st -> if (min in 600..925 && st.str("marketNote").contains("No strategy is passing its test right now")) noteSeen = true }
            for (d in d0..d0 + 2) run.runDay(d)
            val s = state(run.app, "paused end")
            T.eq(s.list("signals").size, 0, "PAUSED strategies emit no signals")
            T.eq(p.notes.count { it.channel == "buy" }, 0, "no buy notifications")
            T.check(s.list("strategies").all { it.asObj().str("verdict") == "PAUSED" && it.asObj().str("why").length > 10 }, "all PAUSED with a reason: " + s.list("strategies").first().asObj().str("why"))
            T.check(noteSeen, "marketNote says honestly that nothing passes")
            T.check(s.list("feed").any { it.asObj().str("body").contains("No strategy is passing its test right now — no new buys. Sell calls and portfolio checks still run.") }, "feed says so too")
            T.check(p.notes.any { it.id == SwingApp.NID_BRIEF && it.body.contains("No strategy is passing") }, "morning brief says so")
        } finally {
            saved.restore()
        }
        // default (strict) gate on a fresh install before any data: UNTESTED
        val q = FakePlatform.standard()
        q.setTime(day(2026, 10, 5), 8 * 60)
        val st = state(SwingApp(q), "untested")
        T.check(st.list("strategies").size == 7 && st.list("strategies").all { it.asObj().str("verdict") == "UNTESTED" }, "UNTESTED before the first run")
    }

    fun bear() {
        val saved = Saved()
        gatesOpen()
        try {
            val p = FakePlatform.standard()
            p.yahoo.profiles["^NSEI"] = Profile(-0.15, 0.5, start = 60000.0, baseVol = 0.0)
            val (_, s) = dipScenario(p, listOf("UP03", "UP05"), 20)
            T.eq(s.obj("regime").str("label"), "BEAR", "regime BEAR when Nifty is below both averages")
            T.check(s.obj("regime").num("niftyLtp") > 0 && s.obj("regime").str("note").contains("only sell-off bounce buys"), "regime note explains")
            T.eq(s.list("signals").size, 0, "no BUY signals in a BEAR regime even with LIVE strategies and a textbook dip")
            T.check(s.list("strategies").any { it.asObj().str("verdict") == "LIVE" } || s.list("strategies").all { it.asObj().obj("stats").int("n") == 0 }, "strategies are not what blocked it")
            // control: same data with a rising index does signal
            val p2 = FakePlatform.standard()
            val (_, s2) = dipScenario(p2, listOf("UP03", "UP05"), 20)
            T.eq(s2.obj("regime").str("label"), "BULL", "control: BULL")
            T.check(s2.list("signals").any { it.asObj().str("sym") == "UP03" } && s2.list("signals").any { it.asObj().str("sym") == "UP05" }, "control: the same dips do signal in a BULL regime")
        } finally {
            saved.restore()
        }
    }

    fun maxOpen() {
        val saved = Saved()
        gatesOpen()
        try {
            val p = FakePlatform.standard()
            val (run, s) = dipScenario(p, listOf("UP02", "UP04", "UP06", "UP08"), 1)
            val perDay = s.list("signals").map { Clock.epochDay(it.asObj().long("createdAt")) }.groupBy { it }.mapValues { it.value.size }
            T.check(perDay.isNotEmpty() && perDay.values.all { it == 1 }, "maxOpenTrades 1 -> at most one new idea at a time: $perDay")
            // take it; the slot stays used by the open trade, so the next day brings nothing new
            val d3 = day(2026, 10, 8)
            val last = s.list("signals").map { it.asObj() }.firstOrNull { it.str("status") == "NEW" }
            if (last != null) {
                T.eq(act(run.app, "takeTrade", """{"signalId":"${last.str("id")}"}""")["ok"], true, "took the idea")
                for (sym in listOf("UP09", "UP10", "UP12")) scriptDip(p.yahoo, sym, d3)
                run.runDay(d3)
                val s3 = state(run.app, "after day 4")
                T.eq(s3.list("signals").count { Clock.epochDay(it.asObj().long("createdAt")) == d3 }, 0, "no new idea while the only slot is an open trade")
                T.check(s3.list("signals").none { it.asObj().str("sym") == last.str("sym") && Clock.epochDay(it.asObj().long("createdAt")) == d3 }, "no repeat signal in a symbol already held")
            } else T.check(false, "expected a NEW signal to take")
        } finally {
            saved.restore()
        }
    }

    // ------------------------------------------------------------------ offline / corrupt / threads
    fun offline() {
        val p = FakePlatform.standard()
        p.offline = true
        val d0 = day(2026, 10, 6)
        p.setTime(d0, 11 * 60)
        val app = SwingApp(p)
        var s = state(app, "brand-new, never ticked, offline")
        T.eq(s.long("lastScanAt"), 0L, "never scanned"); T.eq(s.obj("settings").num("capital"), 100000.0, "default capital"); T.eq(s.obj("settings").num("riskPct"), 0.75, "default risk")
        T.eq(s.obj("settings").num("costPct"), 0.25, "default charges"); T.eq(s.obj("settings").int("maxOpenTrades"), 4, "default max trades")
        T.eq(s.list("strategies").size, 7, "strategies listed even with no data")
        T.check(!app.tick("open").startsWith("Error"), "offline tick does not fail")
        s = state(app, "offline after tick")
        T.eq(s.str("lastScanStatus"), "No internet — showing last data", "offline status line")
        T.eq(act(app, "scanNow", "{}")["ok"], false, "scanNow reports the failure")
        T.eq(act(app, "runBacktest", "{}")["ok"], false, "runBacktest without data reports why")
        val r = act(app, "addHolding", """{"sym":"UP01","qty":5,"avg":300}""")
        T.eq(r["ok"], true, "holding can be added offline")
        s = state(app, "offline holding")
        T.check(!s.list("holdings")[0].asObj().bool("dataOk") && s.obj("portfolio").num("investedRs") == 1500.0, "offline holding shown without prices")
        T.eq(s.list("feed").count { it.asObj().str("kind") == "warn" }, 1, "one offline warning in the feed, not one per attempt")
        for (m in intArrayOf(11 * 60 + 16, 11 * 60 + 31, 14 * 60 + 46, 15 * 60 + 40)) { p.setTime(d0, m); T.check(!app.tick("alarm").startsWith("Error"), "offline alarms harmless") }
        T.eq(state(app, "offline day").list("feed").count { it.asObj().str("kind") == "warn" }, 1, "still one offline warning for the day")
        // back online next morning
        p.offline = false
        p.setTime(d0 + 1, 9 * 60 + 20)
        T.check(app.tick("alarm").contains("OK"), "recovers when the network is back")
        s = state(app, "online again")
        T.check(s.list("holdings")[0].asObj().bool("dataOk") && s.long("lastScanAt") == p.nowMs && s.obj("regime").num("niftyLtp") > 0, "prices and regime filled in")
        // outage in the middle of a day keeps the last data
        val keep = s.list("holdings")[0].asObj().num("ltp")
        p.offline = true
        p.setTime(d0 + 1, 9 * 60 + 31)
        app.tick("alarm")
        s = state(app, "mid-day outage")
        T.check(s.str("lastScanStatus").startsWith("No internet") && s.list("holdings")[0].asObj().num("ltp") == keep && s.list("holdings")[0].asObj().bool("dataOk"), "outage: last prices and verdicts kept")
        T.check(s.long("lastScanAt") < p.nowMs, "lastScanAt still shows the last successful scan")
    }

    fun corrupt() {
        val p = FakePlatform.standard()
        val d0 = day(2026, 10, 6)
        p.setTime(d0, 8 * 60 + 30)
        var app = SwingApp(p)
        act(app, "addHolding", """{"sym":"UP01","qty":5,"avg":300}""")
        act(app, "saveSettings", """{"capital":250000}""")
        p.setTime(d0, 10 * 60 + 1); app.tick("alarm")
        val good = p.files[SwingApp.STATE_FILE]!!
        T.check(Json.parseObject(good)?.int("v") == 1, "state file carries a schema version")
        // 1. truncated state file
        p.files[SwingApp.STATE_FILE] = good.substring(0, good.length / 2)
        app = SwingApp(p)
        var s = state(app, "truncated state")
        T.eq(s.obj("settings").num("capital"), 100000.0, "truncated state -> defaults"); T.eq(s.list("holdings").size, 0, "defaults: no holdings")
        T.check(p.files.containsKey("state.bad.json"), "the unreadable file is kept aside")
        p.setTime(d0, 10 * 60 + 16); T.check(app.tick("alarm").contains("OK"), "keeps working after a corrupt state file")
        // 2. assorted garbage
        for (junk in listOf("", "null", "[]", "42", "\"str\"", "{\"settings\":5,\"signals\":{},\"trades\":[1,null,{\"id\":\"x\"}],\"holdings\":\"no\",\"feed\":[7],\"strategies\":[]}",
            "{\"v\":99,\"settings\":{\"capital\":\"abc\",\"riskPct\":-5,\"maxOpenTrades\":1e99},\"lastScanAt\":\"zzz\",\"regime\":{\"label\":\"MOON\"}}", "\u0000\u0000\u0000")) {
            p.files[SwingApp.STATE_FILE] = junk
            val a2 = SwingApp(p)
            val st = state(a2, "junk state " + junk.take(12))
            T.check(st.obj("settings").num("capital") == 100000.0 && st.obj("settings").num("riskPct") == 0.75 && st.obj("regime").str("label") == "NEUTRAL", "junk state falls back to sane defaults: " + junk.take(20))
            p.nowMs += 60_000; T.check(!a2.tick("manual").startsWith("Error"), "tick after junk state")
        }
        // 3. a good state file survives a restart
        p.files[SwingApp.STATE_FILE] = good
        s = state(SwingApp(p), "restored")
        T.check(s.obj("settings").num("capital") == 250000.0 && s.list("holdings").size == 1 && s.list("holdings")[0].asObj().bool("dataOk"), "valid state file restores settings, holdings and verdicts")
        // 4. corrupt cache shards
        for (k in p.files.keys.filter { it.startsWith("daily_cache_") }) p.files[k] = if (k.endsWith("0.json")) "{\"v\":1,\"s\":{\"UP01\":{\"k\":1,\"b\":\"!!!\"}," else "garbage"
        app = SwingApp(p)
        p.setTime(d0, 11 * 60 + 1)
        T.check(app.tick("alarm").contains("OK"), "corrupt cache files are rebuilt from the network")
        T.check(state(app, "after cache rebuild").list("holdings")[0].asObj().bool("dataOk"), "holding verdict back after cache rebuild")
        // 5. storage that throws
        p.failWrites = true
        p.setTime(d0, 11 * 60 + 16)
        T.check(!app.tick("alarm").startsWith("Error") && Json.parseObject(app.action("clearFeed", "{}"))?.get("ok") == true, "write failures do not break tick/action")
        state(app, "with failing storage")
        p.failWrites = false
        // 6. missing universe asset
        p.assets.clear()
        val a3 = SwingApp(p)
        p.setTime(d0, 11 * 60 + 31)
        T.check(!a3.tick("alarm").startsWith("Error"), "missing universe.csv does not crash")
        T.check(state(a3, "no universe").str("lastScanStatus").contains("Stock list missing"), "status explains the missing list")
    }

    fun nonBlocking() {
        val p = FakePlatform.standard()
        p.setTime(day(2026, 10, 6), 10 * 60 + 1)
        val app = SwingApp(p)
        p.httpDelayMs = 120
        var worst = 0L
        var busySeen = 0
        var labels = HashSet<String>()
        var err: Throwable? = null
        val th = Thread { try { app.tick("alarm") } catch (t: Throwable) { err = t } }
        th.start()
        var polls = 0
        while (th.isAlive) {
            val t0 = System.nanoTime()
            val txt = app.stateJson()
            val o = Json.parseObject(txt)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > worst) worst = ms
            if (o == null) T.check(false, "stateJson invalid during a scan")
            val b = o?.get("busy") as? Map<*, *>
            if (b != null) { busySeen++; labels.add(b["label"].toString()) }
            app.nextWakeAt()
            polls++
            Thread.sleep(15)
        }
        p.httpDelayMs = 0
        T.check(err == null, "tick thread finished cleanly")
        T.check(polls > 20 && worst < 250, "stateJson stayed fast during a slow scan: worst $worst ms over $polls calls")
        T.check(busySeen > polls / 2, "busy shown while scanning ($busySeen of $polls polls): $labels")
        T.check(labels.any { it.contains("Backtesting") } || labels.any { it.contains("price") }, "busy labels describe the work: $labels")
        T.eq(state(app, "after scan")["busy"], null, "busy cleared afterwards")
        // two threads calling tick/action at once are serialised, not corrupted
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        val ths = (0 until 6).map { k -> Thread {
            try {
                for (n in 0 until 15) {
                    val r = if (k % 2 == 0) app.action("addHolding", """{"sym":"UP0${k + 1}","qty":${n + 1},"avg":100}""") else app.tick("manual")
                    if (r.startsWith("Error") || (k % 2 == 0 && Json.parseObject(r)?.get("ok") != true)) errs.add(r)
                    if (Json.parseObject(app.stateJson()) == null) errs.add("bad state")
                }
            } catch (t: Throwable) { errs.add(t.toString()) }
        } }
        ths.forEach { it.start() }; ths.forEach { it.join() }
        T.check(errs.isEmpty(), "concurrent callers: " + errs.take(2))
        T.eq(state(app, "after concurrency").list("holdings").size, 3, "three distinct holdings after concurrent adds")
    }

    fun actions() {
        val saved = Saved()
        gatesOpen()
        try {
            val p = FakePlatform.standard()
            val d0 = day(2026, 10, 5)
            scriptDip(p.yahoo, "UP03", d0 + 2)
            val run = Runner(p)
            val app0 = run.app
            p.setTime(d0, 8 * 60)
            T.eq(Json.parseObject(app0.action("noSuchAction", "{}"))?.get("ok"), false, "unknown action -> ok:false")
            for (bad in listOf("", "not json", "[1,2]", "null", "{\"sym\":")) T.check(Json.parseObject(app0.action("addHolding", bad))?.get("ok") == false, "bad args rejected politely: $bad")
            for (bad in listOf("""{"capital":10}""", """{"riskPct":50}""", """{"maxOpenTrades":0}""", """{"costPct":-1}""", """{"capital":"lots"}"""))
                T.check(act(app0, "saveSettings", bad).let { it["ok"] == false && it.str("error").isNotEmpty() }, "settings out of range rejected: $bad")
            T.eq(state(app0, "settings unchanged").obj("settings").num("capital"), 100000.0, "rejected settings change nothing")
            T.eq(act(app0, "saveSettings", """{"capital":200000,"riskPct":1,"maxOpenTrades":6,"costPct":0.3,"notifyBuy":false,"notifySell":true,"notifyBrief":false}""")["ok"], true, "valid settings saved")
            var s = state(app0, "settings")
            T.eq(s.obj("settings"), mapOf("capital" to 200000.0, "riskPct" to 1.0, "maxOpenTrades" to 6.0, "costPct" to 0.3, "notifyBuy" to false, "notifySell" to true, "notifyBrief" to false), "settings round-trip")
            for (bad in listOf("""{"sym":"","qty":1,"avg":1}""", """{"sym":"UP01","qty":0,"avg":10}""", """{"sym":"UP01","qty":5,"avg":-2}""", """{"sym":"A B","qty":5,"avg":2}""", """{"sym":"UP01"}"""))
                T.eq(act(app0, "addHolding", bad)["ok"], false, "bad holding rejected: $bad")
            T.eq(act(app0, "addHolding", """{"sym":" nse:up01-eq ","qty":5,"avg":300}""")["ok"], true, "decorated symbol accepted")
            T.eq(act(app0, "addHolding", """{"sym":"UP01","qty":8,"avg":310}""")["ok"], true, "same symbol again replaces")
            s = state(app0, "holding")
            T.check(s.list("holdings").size == 1 && s.list("holdings")[0].asObj().num("qty") == 8.0 && s.list("holdings")[0].asObj().str("name") == "Uptrend 1 Ltd", "one holding, updated, named from the universe")
            val imp = act(app0, "importHoldings", Json.stringify(mapOf("text" to "Symbol,Qty,Avg Price\nUP01,10,305.5\nUP02,4,\"1,200.00\"\nXTRA1,2,50\nUptrend 9 Ltd,3,99\n???,1,1\nTotal,19,\n")))
            T.check(imp["ok"] == true && imp.int("added") == 3 && imp.list("skipped").size == 1, "import: 3 added (one by company name), 1 skipped: $imp")
            s = state(app0, "imported")
            T.eq(s.list("holdings").map { it.asObj().str("sym") }, listOf("UP01", "UP02", "XTRA1", "UP09"), "imported holdings present")
            T.check(s.list("holdings").all { it.asObj().bool("dataOk") }, "imported holdings priced right away")
            val pf = s.obj("portfolio")
            val hs = s.list("holdings").map { it.asObj() }
            T.near(pf.num("investedRs"), 10 * 305.5 + 4 * 1200 + 2 * 50 + 3 * 99.0, 0.01, "portfolio invested"); T.near(pf.num("valueRs"), hs.sumOf { it.num("valueRs") }, 0.05, "portfolio value = sum")
            T.near(pf.num("pnlRs"), pf.num("valueRs") - pf.num("investedRs"), 0.02, "portfolio P&L"); T.eq(pf.int("sellCount"), hs.count { it.str("verdict") == "SELL" }, "sellCount")
            T.check(hs.all { Math.abs(it.num("valueRs") - it.num("qty") * it.num("ltp")) < 0.05 && it.num("stop") > 0 && it.num("bookAt") > it.num("ltp") && it.list("reasons").size in 2..4 }, "holding maths, levels and 2-4 reasons")
            T.eq(act(app0, "importHoldings", """{"text":"nothing useful"}""")["ok"], false, "import of junk -> ok:false with message")
            T.eq(act(app0, "removeHolding", """{"sym":"XTRA1"}""")["ok"], true, "removed"); T.eq(act(app0, "removeHolding", """{"sym":"XTRA1"}""")["ok"], false, "removing twice fails politely")
            T.eq(act(app0, "takeTrade", """{"signalId":"nope"}""")["ok"], false, "unknown signal"); T.eq(act(app0, "skipSignal", """{"signalId":"nope"}""")["ok"], false, "unknown signal skip")
            T.eq(act(app0, "closeTrade", """{"tradeId":"nope","price":5}""")["ok"], false, "unknown trade")
            // notification toggles: buy off, sell on
            var sig: Map<String, Any?>? = null
            run.onSignal = { g ->
                if (sig == null && g.str("sym") == "UP03") {
                    sig = g
                    // capital 2,00,000 at 1% risk and 0.3% charges
                    T.eq(g.int("qty"), SwingApp.positionSize(200000.0, 1.0, 0.3, g.num("entry"), g.num("sl")), "size follows the saved settings")
                    val id = g.str("id")
                    T.eq(act(run.app, "takeTrade", """{"signalId":"$id","price":${g.num("sl")}}""")["ok"], false, "buy price at the stop refused")
                    T.eq(act(run.app, "takeTrade", """{"signalId":"$id","qty":0}""")["ok"], false, "zero quantity refused")
                    val big = act(run.app, "takeTrade", """{"signalId":"$id","qty":${g.int("qty") * 40}}""")
                    T.check(big["ok"] == true && big.str("warning").contains("more than your"), "oversized quantity accepted with a risk warning: $big")
                    T.eq(act(run.app, "takeTrade", """{"signalId":"$id"}""")["ok"], false, "cannot take twice"); T.eq(act(run.app, "skipSignal", """{"signalId":"$id"}""")["ok"], false, "cannot skip a taken signal")
                    val tr = state(run.app, "taken").list("trades")[0].asObj()
                    T.check(tr.str("status") == "OPEN" && tr.int("day") == 1 && tr.int("maxDays") == 6 && tr.str("action") == "HOLD" && tr.str("actionNote").contains("Stop-loss ₹"), "new trade: day 1 of 6, HOLD with the stop in the note: " + tr.str("actionNote"))
                    T.near(tr.num("pnlRs"), tr.int("qty") * (tr.num("ltp") - tr.num("entry")) - tr.num("entry") * tr.int("qty") * 0.3 / 100, 0.02, "open P&L already net of charges")
                }
            }
            for (d in d0..d0 + 2) run.runDay(d, restart = false)
            T.check(sig != null, "a signal arrived")
            T.eq(p.notes.count { it.channel == "buy" }, 0, "notifyBuy off -> no buy notification"); T.eq(p.notes.count { it.channel == "brief" }, 0, "notifyBrief off -> no brief")
            s = state(run.app, "toggles")
            T.check(s.list("feed").any { it.asObj().str("kind") == "buy" } && s.list("feed").any { it.asObj().str("kind") == "brief" }, "but the feed still records them")
            // an untaken signal expires at 09:15 next day and can no longer be taken
            val other = s.list("signals").map { it.asObj() }.firstOrNull { it.str("status") == "NEW" }
            if (other != null) {
                p.setTime(d0 + 3, 9 * 60 + 16)
                T.eq(act(run.app, "takeTrade", """{"signalId":"${other.str("id")}"}""")["ok"], false, "expired idea cannot be taken")
                T.eq(state(run.app, "expiry").list("signals").map { it.asObj() }.first { it.str("id") == other.str("id") }.str("status"), "EXPIRED", "marked EXPIRED at 09:15")
            }
            T.eq(act(run.app, "clearFeed", "{}")["ok"], true, "clearFeed"); T.eq(state(run.app, "feed cleared").list("feed").size, 0, "feed empty")
            val bt = act(run.app, "runBacktest", "{}")
            T.eq(bt["ok"], true, "runBacktest action")
            s = state(run.app, "after backtest")
            T.check(s.list("strategies").all { it.asObj().long("testedAt") == p.nowMs } && s.list("feed").any { it.asObj().str("title") == "Backtest finished" }, "backtest stamps strategies and logs to the feed")
            // feed cap
            for (k in 0 until 130) run.app.action("closeTrade", """{"tradeId":"zz$k","price":1}""")
            for (k in 0 until 130) { p.nowMs += 1000; run.app.action("runBacktest", "{}") }
            T.eq(state(run.app, "feed cap").list("feed").size, 100, "feed capped at 100, newest first")
            val f = state(run.app, "feed order").list("feed").map { it.asObj().long("ts") }
            T.check(f == f.sortedDescending(), "feed newest first")
        } finally {
            saved.restore()
        }
    }

    fun triggers() {
        val p = FakePlatform.standard()
        p.keepUrls = true
        val d0 = day(2026, 10, 6)   // Tuesday
        val app = SwingApp(p)
        p.setTime(d0, 7 * 60)
        T.eq(app.tick("alarm"), "Nothing due", "07:00 alarm: nothing due")
        T.eq(p.urlCount, 0, "and no network")
        T.check(app.tick("open").contains("scan"), "first ever open loads data even before the market opens")
        val afterOpen = p.urlCount
        T.check(afterOpen > 90, "history + prices fetched ($afterOpen URLs)")
        T.eq(app.tick("open"), "Nothing due", "second open: nothing due"); T.eq(p.urlCount, afterOpen, "no refetch")
        T.check(p.urls.any { it.contains("/M%26M.NS?range=2y&interval=1d") } && p.urls.any { it.contains("/%5ENSEI?range=5d&interval=15m") } && p.urls.any { it.contains("/BAJAJ-AUTO.NS?") }, "URLs encoded correctly")
        T.check(p.urls.any { it.startsWith("https://query2.finance.yahoo.com/") && it.contains("/H2ONLY.NS?") }, "failed symbols retried on query2")
        T.eq(p.urls.count { it.contains("query2") && it.contains("/DEAD.NS?range=5d") }, 1, "exactly one retry round")
        T.check(p.urls.none { it.contains("query2") && it.contains("/UP01.NS") }, "healthy symbols are not refetched")
        // through the day: daily history once per symbol, intraday every scan
        p.urls.clear()
        var t = Clock.msAt(d0, 7 * 60)
        var scans = 0
        while (true) {
            t = Clock.nextWake(t)
            if (Clock.epochDay(t) != d0) break
            p.nowMs = t
            if (app.tick("alarm").contains("scan")) scans++
        }
        T.eq(scans, 26, "25 quarter-hour scans + the 15:40 end-of-day run")
        T.eq(p.urls.count { it.contains("/UP01.NS?range=2y") }, 0, "daily history not fetched again the same day")
        T.eq(p.urls.count { it.contains("query1") && it.contains("/UP01.NS?range=5d&interval=15m") }, 26, "intraday fetched on every scan")
        // failed history downloads are retried, but at most every 20 minutes (26 scans over ~6.5 h -> well under 26 tries)
        val deadTries = p.urls.count { it.contains("query1") && it.contains("/DEAD.NS?range=2y") }
        T.check(deadTries in 1..20, "symbols without history are retried, but not on every scan ($deadTries)")
        // after the close
        p.setTime(d0, 18 * 60)
        val n0 = p.urlCount
        T.eq(app.tick("open"), "Nothing due", "evening open after the 15:40 run: nothing due"); T.eq(p.urlCount, n0, "no network")
        T.check(app.tick("manual").contains("scan"), "manual trigger always scans"); T.check(p.urlCount > n0, "manual scan used the network")
        // next morning the history is refreshed once
        p.urls.clear()
        p.setTime(d0 + 1, 9 * 60 + 20, 5); app.tick("alarm")
        p.setTime(d0 + 1, 9 * 60 + 31, 5); app.tick("alarm")
        T.eq(p.urls.count { it.contains("query1") && it.contains("/UP01.NS?range=2y") }, 1, "next trading day: history fetched once")
        // a process that was dead at 15:40 catches up when the app is opened in the evening
        val p2 = FakePlatform.standard()
        val app2 = SwingApp(p2)
        p2.setTime(d0, 12 * 60 + 1); app2.tick("alarm")
        p2.setTime(d0, 19 * 60)
        val r = SwingApp(p2).tick("open")
        T.check(r.contains("scan") && r.contains("EOD"), "missed end-of-day run is made up on open: $r")
        T.eq(p2.notes.count { it.id == SwingApp.NID_EOD }, 1, "one scorecard")
        T.eq(SwingApp(p2).tick("open"), "Nothing due", "and only once")
        // automatic re-test: stats older than 7 days, or charges changed
        fun tested(a: SwingApp) = Json.parseObject(a.stateJson())!!.list("strategies").map { it.asObj().long("testedAt") }
        val first = tested(app)
        T.check(first.all { it > 0 } && first.toSet().size == 1, "strategies were tested automatically on the first data load")
        p.setTime(d0 + 6, 10 * 60 + 1); app.tick("alarm")
        T.eq(tested(app), first, "no re-test within 7 days")
        p.setTime(d0 + 8, 10 * 60 + 1); app.tick("alarm")
        T.check(tested(app).all { it == p.nowMs }, "re-tested automatically after 7 days")
        app.action("saveSettings", """{"costPct":0.5}""")
        p.setTime(d0 + 8, 10 * 60 + 16); app.tick("alarm")
        T.check(tested(app).all { it == p.nowMs }, "re-tested on the next scan after charges changed")
        // boot trigger before the open behaves like an alarm
        p2.setTime(d0 + 1, 8 * 60 + 56)
        T.check(SwingApp(p2).tick("boot").contains("brief"), "boot at 08:56 sends the brief")
        T.eq(SwingApp(p2).tick("boot"), "Nothing due", "brief only once")
        p2.setTime(d0 + 1, 10 * 60)
        T.check(!SwingApp(p2).tick("alarm").contains("brief"), "a late start does not send a stale morning brief")
    }
}
