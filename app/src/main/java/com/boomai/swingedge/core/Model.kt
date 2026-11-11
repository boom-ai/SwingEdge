package com.boomai.swingedge.core

/** Persisted app state. Every class reads defensively from a parsed JSON map and never throws. */

class Settings {
    var capital = Params.DEF_CAPITAL
    var riskPct = Params.DEF_RISK_PCT
    var maxOpenTrades = Params.DEF_MAX_OPEN
    var costPct = Params.DEF_COST_PCT
    var notifyBuy = true
    var notifySell = true
    var notifyBrief = true

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "capital" to capital, "riskPct" to riskPct, "maxOpenTrades" to maxOpenTrades, "costPct" to costPct,
        "notifyBuy" to notifyBuy, "notifySell" to notifySell, "notifyBrief" to notifyBrief
    )

    /** Applies whichever valid fields are present; returns an error text or null. */
    fun apply(m: Map<String, Any?>, strict: Boolean): String? {
        if (m.containsKey("capital")) {
            val v = m.num("capital", Double.NaN)
            if (v.isNaN() || v < 1000 || v > 1e10) { if (strict) return "Capital must be at least ₹1,000" } else capital = v
        }
        if (m.containsKey("riskPct")) {
            val v = m.num("riskPct", Double.NaN)
            if (v.isNaN() || v < 0.05 || v > 5) { if (strict) return "Risk per trade must be between 0.05% and 5%" } else riskPct = v
        }
        if (m.containsKey("maxOpenTrades")) {
            val v = m.num("maxOpenTrades", Double.NaN)
            if (v.isNaN() || v < 1 || v > 20) { if (strict) return "Max open trades must be between 1 and 20" } else maxOpenTrades = v.toInt()
        }
        if (m.containsKey("costPct")) {
            val v = m.num("costPct", Double.NaN)
            if (v.isNaN() || v < 0 || v > 3) { if (strict) return "Charges must be between 0% and 3%" } else costPct = v
        }
        if (m["notifyBuy"] is Boolean) notifyBuy = m.bool("notifyBuy")
        if (m["notifySell"] is Boolean) notifySell = m.bool("notifySell")
        if (m["notifyBrief"] is Boolean) notifyBrief = m.bool("notifyBrief")
        return null
    }
}

class Signal {
    var id = ""; var sym = ""; var name = ""; var strategyId = ""; var strategyLabel = ""
    var createdAt = 0L; var validTill = 0L; var day = 0
    var ltp = 0.0; var entry = 0.0; var entryMax = 0.0; var sl = 0.0; var t1 = 0.0; var t2 = 0.0
    var qty = 0; var investRs = 0.0; var riskRs = 0.0; var rewardRs = 0.0; var holdDays = Params.HOLD_DAYS
    var edgeN = 0; var edgeWinPct = 0.0; var edgeAvgPct = 0.0; var edgePf = 0.0
    var reasons: List<String> = emptyList()
    var status = "NEW"

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "sym" to sym, "name" to name, "strategyId" to strategyId, "strategyLabel" to strategyLabel,
        "createdAt" to createdAt, "validTill" to validTill,
        "ltp" to Fmt.r2(ltp), "entry" to Fmt.r2(entry), "entryMax" to Fmt.r2(entryMax), "sl" to Fmt.r2(sl), "t1" to Fmt.r2(t1), "t2" to Fmt.r2(t2),
        "qty" to qty, "investRs" to Fmt.r2(investRs), "riskRs" to Fmt.r2(riskRs), "rewardRs" to Fmt.r2(rewardRs), "holdDays" to holdDays,
        "edge" to linkedMapOf("n" to edgeN, "winPct" to Fmt.r1(edgeWinPct), "avgPct" to Fmt.r2(edgeAvgPct), "pf" to Fmt.r2(edgePf)),
        "reasons" to reasons, "status" to status
    )

    fun persist(): Map<String, Any?> {
        val m = LinkedHashMap(toMap())
        m["day"] = day
        return m
    }

    companion object {
        fun from(m: Map<String, Any?>): Signal? {
            val s = Signal()
            s.id = m.str("id"); s.sym = m.str("sym")
            if (s.id.isEmpty() || s.sym.isEmpty()) return null
            s.name = m.str("name"); s.strategyId = m.str("strategyId"); s.strategyLabel = m.str("strategyLabel")
            s.createdAt = m.long("createdAt"); s.validTill = m.long("validTill"); s.day = m.int("day", Clock.epochDay(s.createdAt))
            s.ltp = m.num("ltp"); s.entry = m.num("entry"); s.entryMax = m.num("entryMax"); s.sl = m.num("sl"); s.t1 = m.num("t1"); s.t2 = m.num("t2")
            s.qty = m.int("qty"); s.investRs = m.num("investRs"); s.riskRs = m.num("riskRs"); s.rewardRs = m.num("rewardRs")
            s.holdDays = m.int("holdDays", Params.HOLD_DAYS)
            val e = m.obj("edge")
            s.edgeN = e.int("n"); s.edgeWinPct = e.num("winPct"); s.edgeAvgPct = e.num("avgPct"); s.edgePf = e.num("pf")
            s.reasons = m.strList("reasons")
            s.status = m.str("status", "NEW").let { if (it in setOf("NEW", "TAKEN", "SKIPPED", "EXPIRED")) it else "EXPIRED" }
            return s
        }
    }
}

class Trade {
    var id = ""; var sym = ""; var name = ""; var strategyId = ""; var strategyLabel = ""; var signalId = ""
    var paper = false
    var status = "OPEN"
    var openedAt = 0L; var entryDay = 0
    var entry = 0.0; var qty = 0
    /** Current stop (moves to entry after T1); sl0 is the original stop. */
    var sl = 0.0; var sl0 = 0.0; var t1 = 0.0; var t2 = 0.0
    var day = 1; var maxDays = Params.HOLD_DAYS
    var ltp = 0.0; var pnlRs = 0.0; var pnlPct = 0.0
    var action = "HOLD"; var actionNote = ""
    var t1Done = false; var t1Px = 0.0; var t1Day = 0
    /** Sticky exit call for a real trade: 0 none, else ExitModel.STOP / T2 / TIME. */
    var exitDue = 0
    /** Last fully finished daily bar already applied to this trade. */
    var lastBarDay = 0
    /** Last action the user was notified about (so each change notifies once). */
    var notified = ""
    var closedAt = 0L; var exitPrice = 0.0; var result = ""

    /** Shares sold at T1 under the exit model. */
    fun halfQty(): Int = if (qty >= 2) qty / 2 else 0
    fun halfFrac(): Double = if (qty > 0) halfQty().toDouble() / qty else 0.0

    /** P&L in rupees after charges if the rest is sold at [px]. */
    fun pnlAt(px: Double, costPct: Double): Double {
        val half = if (t1Done) halfQty() else 0
        val gross = half * (t1Px - entry) + (qty - half) * (px - entry)
        return gross - entry * qty * costPct / 100.0
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "sym" to sym, "name" to name, "strategyLabel" to strategyLabel, "status" to status, "openedAt" to openedAt,
        "entry" to Fmt.r2(entry), "qty" to qty, "sl" to Fmt.r2(sl), "t1" to Fmt.r2(t1), "t2" to Fmt.r2(t2),
        "day" to day, "maxDays" to maxDays, "ltp" to Fmt.r2(ltp), "pnlRs" to Fmt.r2(pnlRs), "pnlPct" to Fmt.r2(pnlPct),
        "action" to action, "actionNote" to actionNote, "t1Done" to t1Done, "closedAt" to closedAt,
        "exitPrice" to Fmt.r2(exitPrice), "result" to result
    )

    fun persist(): Map<String, Any?> {
        val m = LinkedHashMap(toMap())
        m["strategyId"] = strategyId; m["signalId"] = signalId; m["paper"] = paper; m["entryDay"] = entryDay
        m["sl0"] = sl0; m["t1Px"] = t1Px; m["t1Day"] = t1Day; m["exitDue"] = exitDue; m["lastBarDay"] = lastBarDay; m["notified"] = notified
        m["entry"] = entry; m["sl"] = sl; m["t1"] = t1; m["t2"] = t2; m["exitPrice"] = exitPrice
        return m
    }

    companion object {
        fun from(m: Map<String, Any?>): Trade? {
            val t = Trade()
            t.id = m.str("id"); t.sym = m.str("sym")
            t.entry = m.num("entry"); t.qty = m.int("qty")
            if (t.id.isEmpty() || t.sym.isEmpty() || !(t.entry > 0) || t.qty < 1) return null
            t.name = m.str("name"); t.strategyId = m.str("strategyId"); t.strategyLabel = m.str("strategyLabel"); t.signalId = m.str("signalId")
            t.paper = m.bool("paper"); t.status = if (m.str("status") == "CLOSED") "CLOSED" else "OPEN"
            t.openedAt = m.long("openedAt"); t.entryDay = m.int("entryDay", Clock.epochDay(t.openedAt))
            t.sl = m.num("sl"); t.sl0 = m.num("sl0", t.sl); t.t1 = m.num("t1"); t.t2 = m.num("t2")
            t.day = m.int("day", 1); t.maxDays = m.int("maxDays", Params.HOLD_DAYS)
            t.ltp = m.num("ltp", t.entry); t.pnlRs = m.num("pnlRs"); t.pnlPct = m.num("pnlPct")
            t.action = m.str("action", "HOLD").let { if (it in setOf("HOLD", "BOOK_HALF", "EXIT", "TIME_EXIT")) it else "HOLD" }
            t.actionNote = m.str("actionNote"); t.t1Done = m.bool("t1Done"); t.t1Px = m.num("t1Px", t.t1); t.t1Day = m.int("t1Day")
            t.exitDue = m.int("exitDue"); t.lastBarDay = m.int("lastBarDay", t.entryDay); t.notified = m.str("notified")
            t.closedAt = m.long("closedAt"); t.exitPrice = m.num("exitPrice"); t.result = m.str("result")
            return t
        }
    }
}

class Holding {
    var sym = ""; var name = ""; var qty = 0.0; var avg = 0.0
    var ltp = 0.0; var prevClose = 0.0
    var verdict = "WATCH"; var headline = "Waiting for the first price check"
    var stop = 0.0; var bookAt = 0.0
    var reasons: List<String> = listOf("Added — prices load on the next scan", "Verdict appears once data is in")
    var dataOk = false
    /** "VERDICT@day" of the last verdict notification, and the day a stop-cross was last notified. */
    var notifiedKey = ""
    var lastVerdict = ""
    var stopCrossDay = 0
    var belowStop = false

    fun valueRs(): Double = if (ltp > 0) ltp * qty else avg * qty
    fun pnlRs(): Double = if (ltp > 0 && avg > 0) (ltp - avg) * qty else 0.0
    fun dayChgPct(): Double = if (ltp > 0 && prevClose > 0) (ltp / prevClose - 1) * 100.0 else 0.0

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "sym" to sym, "name" to name, "qty" to qty, "avg" to Fmt.r2(avg), "ltp" to Fmt.r2(ltp), "valueRs" to Fmt.r2(valueRs()),
        "pnlRs" to Fmt.r2(pnlRs()), "pnlPct" to Fmt.r2(if (avg > 0 && ltp > 0) (ltp / avg - 1) * 100.0 else 0.0), "dayChgPct" to Fmt.r2(dayChgPct()),
        "verdict" to verdict, "headline" to headline, "stop" to Fmt.r2(stop), "bookAt" to Fmt.r2(bookAt), "reasons" to reasons, "dataOk" to dataOk
    )

    fun persist(): Map<String, Any?> {
        val m = LinkedHashMap(toMap())
        m["avg"] = avg; m["prevClose"] = prevClose; m["notifiedKey"] = notifiedKey; m["lastVerdict"] = lastVerdict
        m["stopCrossDay"] = stopCrossDay; m["belowStop"] = belowStop
        return m
    }

    companion object {
        fun from(m: Map<String, Any?>): Holding? {
            val h = Holding()
            h.sym = m.str("sym"); h.qty = m.num("qty"); h.avg = m.num("avg")
            if (h.sym.isEmpty() || !(h.qty > 0)) return null
            h.name = m.str("name", h.sym); h.ltp = m.num("ltp"); h.prevClose = m.num("prevClose")
            h.verdict = m.str("verdict", "WATCH").let { if (it in setOf("HOLD", "TRIM", "SELL", "WATCH")) it else "WATCH" }
            h.headline = m.str("headline", h.headline); h.stop = m.num("stop"); h.bookAt = m.num("bookAt")
            val r = m.strList("reasons")
            if (r.isNotEmpty()) h.reasons = r
            h.dataOk = m.bool("dataOk"); h.notifiedKey = m.str("notifiedKey"); h.lastVerdict = m.str("lastVerdict")
            h.stopCrossDay = m.int("stopCrossDay"); h.belowStop = m.bool("belowStop")
            return h
        }
    }
}

class StratState {
    var verdict = "UNTESTED"
    var testedAt = 0L
    var n = 0; var winPct = 0.0; var avgPct = 0.0; var pf = 0.0; var maxDdPct = 0.0; var oosN = 0; var oosAvgPct = 0.0
    var why = "Not tested yet on this phone. It is tested automatically once price history has loaded."

    fun statsMap(): Map<String, Any?> = linkedMapOf(
        "n" to n, "winPct" to Fmt.r1(winPct), "avgPct" to Fmt.r2(avgPct), "pf" to Fmt.r2(pf),
        "maxDdPct" to Fmt.r1(maxDdPct), "oosN" to oosN, "oosAvgPct" to Fmt.r2(oosAvgPct)
    )

    fun persist(): Map<String, Any?> = linkedMapOf("verdict" to verdict, "testedAt" to testedAt, "stats" to linkedMapOf(
        "n" to n, "winPct" to winPct, "avgPct" to avgPct, "pf" to pf, "maxDdPct" to maxDdPct, "oosN" to oosN, "oosAvgPct" to oosAvgPct), "why" to why)

    companion object {
        fun from(m: Map<String, Any?>): StratState {
            val s = StratState()
            s.verdict = m.str("verdict", "UNTESTED").let { if (it in setOf("LIVE", "PAUSED", "UNTESTED")) it else "UNTESTED" }
            s.testedAt = m.long("testedAt")
            val st = m.obj("stats")
            s.n = st.int("n"); s.winPct = st.num("winPct"); s.avgPct = st.num("avgPct"); s.pf = st.num("pf")
            s.maxDdPct = st.num("maxDdPct"); s.oosN = st.int("oosN"); s.oosAvgPct = st.num("oosAvgPct")
            s.why = m.str("why", s.why)
            if (s.testedAt == 0L) s.verdict = "UNTESTED"
            return s
        }
    }
}

class FeedItem(val ts: Long, val kind: String, val title: String, val body: String) {
    fun toMap(): Map<String, Any?> = linkedMapOf("ts" to ts, "kind" to kind, "title" to title, "body" to body)
}

class AppState {
    var version = SCHEMA
    val settings = Settings()
    val signals = ArrayList<Signal>()
    /** Real and paper trades, open and closed, oldest first. */
    val trades = ArrayList<Trade>()
    val holdings = ArrayList<Holding>()
    val strategies = LinkedHashMap<String, StratState>()
    /** Newest first. */
    val feed = ArrayList<FeedItem>()
    var lastScanAt = 0L
    var lastScanStatus = "Not scanned yet"
    var lastScanSlot = -1L
    var lastBriefDay = 0
    var lastEodDay = 0
    var holidayDay = 0
    var lastBacktestAt = 0L
    var lastTradingDay = 0
    var regimeLabel = "NEUTRAL"
    var niftyLtp = 0.0
    var niftyChgPct = 0.0
    var regimeNote = "Waiting for Nifty data"
    var seq = 0L
    var noLiveWarnDay = 0
    var offlineWarnDay = 0

    fun strat(id: String): StratState = strategies.getOrPut(id) { StratState() }

    fun persist(): Map<String, Any?> = linkedMapOf(
        "v" to version, "settings" to settings.toMap(),
        "signals" to signals.map { it.persist() }, "trades" to trades.map { it.persist() }, "holdings" to holdings.map { it.persist() },
        "strategies" to strategies.mapValues { it.value.persist() }, "feed" to feed.map { it.toMap() },
        "lastScanAt" to lastScanAt, "lastScanStatus" to lastScanStatus, "lastScanSlot" to lastScanSlot,
        "lastBriefDay" to lastBriefDay, "lastEodDay" to lastEodDay, "holidayDay" to holidayDay, "lastBacktestAt" to lastBacktestAt,
        "lastTradingDay" to lastTradingDay,
        "regime" to linkedMapOf("label" to regimeLabel, "niftyLtp" to niftyLtp, "niftyChgPct" to niftyChgPct, "note" to regimeNote),
        "seq" to seq, "noLiveWarnDay" to noLiveWarnDay, "offlineWarnDay" to offlineWarnDay
    )

    companion object {
        const val SCHEMA = 1
        const val MAX_FEED = 100
        const val MAX_SIGNALS = 80
        const val MAX_CLOSED = 400

        /** Builds state from file text; any missing/corrupt part falls back to defaults. Never throws. */
        fun load(text: String?): AppState {
            val st = AppState()
            try {
                val m = Json.parseObject(text) ?: return st
                st.settings.apply(m.obj("settings"), false)
                for (x in m.list("signals")) try { Signal.from(x.asObj())?.let { st.signals.add(it) } } catch (t: Throwable) {}
                for (x in m.list("trades")) try { Trade.from(x.asObj())?.let { st.trades.add(it) } } catch (t: Throwable) {}
                for (x in m.list("holdings")) try {
                    Holding.from(x.asObj())?.let { h -> if (st.holdings.none { it.sym == h.sym }) st.holdings.add(h) }
                } catch (t: Throwable) {}
                for ((k, v) in m.obj("strategies")) try { st.strategies[k] = StratState.from(v.asObj()) } catch (t: Throwable) {}
                for (x in m.list("feed")) {
                    val f = x.asObj()
                    val kind = f.str("kind", "info").let { if (it in setOf("buy", "sell", "brief", "info", "warn")) it else "info" }
                    if (st.feed.size < MAX_FEED) st.feed.add(FeedItem(f.long("ts"), kind, f.str("title"), f.str("body")))
                }
                st.lastScanAt = m.long("lastScanAt"); st.lastScanStatus = m.str("lastScanStatus", st.lastScanStatus)
                st.lastScanSlot = m.long("lastScanSlot", -1); st.lastBriefDay = m.int("lastBriefDay"); st.lastEodDay = m.int("lastEodDay")
                st.holidayDay = m.int("holidayDay"); st.lastBacktestAt = m.long("lastBacktestAt"); st.lastTradingDay = m.int("lastTradingDay")
                val r = m.obj("regime")
                st.regimeLabel = r.str("label", "NEUTRAL").let { if (it in setOf("BULL", "NEUTRAL", "BEAR")) it else "NEUTRAL" }
                st.niftyLtp = r.num("niftyLtp"); st.niftyChgPct = r.num("niftyChgPct"); st.regimeNote = r.str("note", st.regimeNote)
                st.seq = m.long("seq"); st.noLiveWarnDay = m.int("noLiveWarnDay"); st.offlineWarnDay = m.int("offlineWarnDay")
            } catch (t: Throwable) {
                // keep whatever loaded
            }
            return st
        }
    }
}
