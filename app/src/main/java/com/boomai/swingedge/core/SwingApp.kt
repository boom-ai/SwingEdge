package com.boomai.swingedge.core

import java.util.concurrent.locks.ReentrantLock

/**
 * The whole app behind four methods. tick/action are serialised by one lock; stateJson never takes it and serves the
 * last published snapshot (plus live clock fields and `busy`).
 */
class SwingApp(private val p: Platform) {

    private val lock = ReentrantLock()
    private var st: AppState = AppState()
    @Volatile private var snapshot: Map<String, Any?> = emptyMap()
    @Volatile private var busy: Map<String, Any?>? = null
    @Volatile private var vHolidayDay = 0
    @Volatile private var vAnyLive = false
    @Volatile private var vTested = false

    private val cache = DailyCache(p)
    private var universe: List<String> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private var nameToSym: Map<String, String> = emptyMap()
    private var universeLoaded = false
    /** Series (history + today's bar) and quotes from the latest fetch, kept in memory for quick actions. */
    private val live = HashMap<String, Series>()
    private val quotes = HashMap<String, Intraday>()

    init {
        try {
            val text = try { p.read(STATE_FILE) } catch (t: Throwable) { null }
            st = AppState.load(text)
            if (text != null && text.isNotBlank() && Json.parseObject(text) == null) {
                log("state.json was unreadable — starting from defaults")
                try { p.write("state.bad.json", if (text.length > 200_000) text.substring(0, 200_000) else text) } catch (t: Throwable) {}
            }
        } catch (t: Throwable) {
            st = AppState()
        }
        publish()
    }

    // =========================================================================================== public API

    fun stateJson(): String {
        try {
            val now = now()
            val m = LinkedHashMap<String, Any?>(snapshot)
            m["now"] = now
            m["marketOpen"] = Clock.isMarketOpen(now) && vHolidayDay != Clock.epochDay(now)
            m["marketNote"] = marketNote(now)
            m["nextScanAt"] = Clock.nextWake(now)
            m["busy"] = busy
            return Json.stringify(m)
        } catch (t: Throwable) {
            return try {
                val m = LinkedHashMap(buildSnapshot(AppState()))
                m["busy"] = null
                Json.stringify(m)
            } catch (t2: Throwable) {
                "{}"
            }
        }
    }

    fun nextWakeAt(): Long = try { Clock.nextWake(now()) } catch (t: Throwable) { System.currentTimeMillis() + 15 * 60_000L }

    fun tick(trigger: String): String {
        lock.lock()
        try {
            return tickLocked(trigger)
        } catch (t: Throwable) {
            log("tick failed: $t")
            return "Error: " + (t.message ?: t.javaClass.simpleName)
        } finally {
            busy = null
            try { save() } catch (t: Throwable) {}
            try { publish() } catch (t: Throwable) {}
            lock.unlock()
        }
    }

    fun action(name: String, argsJson: String): String {
        lock.lock()
        try {
            val args = Json.parseObject(argsJson) ?: emptyMap()
            val res = actionLocked(name, args)
            return Json.stringify(res)
        } catch (t: Throwable) {
            log("action $name failed: $t")
            return try { Json.stringify(err("Something went wrong: " + (t.message ?: t.javaClass.simpleName))) } catch (x: Throwable) { "{\"ok\":false,\"error\":\"failed\"}" }
        } finally {
            busy = null
            try { save() } catch (t: Throwable) {}
            try { publish() } catch (t: Throwable) {}
            lock.unlock()
        }
    }

    // =========================================================================================== helpers

    private fun now(): Long = try { p.now() } catch (t: Throwable) { System.currentTimeMillis() }
    private fun log(m: String) { try { p.log(m) } catch (t: Throwable) {} }
    private fun ok(vararg kv: Pair<String, Any?>): Map<String, Any?> { val m = LinkedHashMap<String, Any?>(); m["ok"] = true; for (x in kv) m[x.first] = x.second; return m }
    private fun err(msg: String): Map<String, Any?> = linkedMapOf("ok" to false, "error" to msg)
    private fun setBusy(label: String, pct: Int) { busy = linkedMapOf("label" to label, "pct" to pct.coerceIn(0, 100)) }
    private fun nid(kind: String, key: String): Int = ("$kind:$key").hashCode() and 0x7fffffff

    private fun save() {
        // caps
        while (st.signals.size > AppState.MAX_SIGNALS) st.signals.removeAt(0)
        var closed = st.trades.count { it.status == "CLOSED" }
        if (closed > AppState.MAX_CLOSED) {
            val it = st.trades.iterator()
            while (it.hasNext() && closed > AppState.MAX_CLOSED) if (it.next().status == "CLOSED") { it.remove(); closed-- }
        }
        while (st.feed.size > AppState.MAX_FEED) st.feed.removeAt(st.feed.size - 1)
        p.write(STATE_FILE, Json.stringify(st.persist()))
    }

    private fun feed(kind: String, title: String, body: String) {
        st.feed.add(0, FeedItem(now(), kind, title, body))
        while (st.feed.size > AppState.MAX_FEED) st.feed.removeAt(st.feed.size - 1)
    }

    /** Appends to the feed and, if the user's toggle for the channel is on, posts a phone notification. */
    private fun post(channel: String, id: Int, title: String, body: String, push: Boolean = true) {
        feed(channel, title, body)
        val allowed = when (channel) {
            "buy" -> st.settings.notifyBuy
            "sell" -> st.settings.notifySell
            else -> st.settings.notifyBrief
        }
        if (push && allowed) try { p.notify(channel, id, title, body) } catch (t: Throwable) {}
    }

    private fun loadUniverse() {
        if (universeLoaded) return
        universeLoaded = true
        try {
            val text = p.asset("universe.csv") ?: ""
            val syms = ArrayList<String>()
            val nm = HashMap<String, String>()
            val rev = HashMap<String, String>()
            val seen = HashSet<String>()
            for (raw in text.split('\n')) {
                val line = raw.replace("﻿", "").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val k = line.indexOf(',')
                val sym = (if (k < 0) line else line.substring(0, k)).trim().uppercase()
                val name = if (k < 0) sym else line.substring(k + 1).trim().trim('"')
                if (sym.isEmpty() || sym == "SYMBOL" || sym.length > 25 || sym.contains(' ') || !seen.add(sym)) continue
                syms.add(sym)
                nm[sym] = if (name.isEmpty()) sym else name
                val nn = HoldingsImport.normName(name)
                if (nn.isNotEmpty() && !rev.containsKey(nn)) rev[nn] = sym
            }
            universe = syms; names = nm; nameToSym = rev
        } catch (t: Throwable) {
            log("universe load failed: $t")
        }
    }

    private fun nameOf(sym: String): String = names[sym] ?: sym

    private fun anyLive(): Boolean = Strategies.all.any { it.enabled() && st.strat(it.id).verdict == "LIVE" }

    private fun marketNote(now: Long): String {
        val day = Clock.epochDay(now)
        val min = Clock.minuteOfDay(now)
        if (!Clock.isWeekday(day)) return "Closed · weekend · opens " + Clock.dayLabel(Clock.nextWeekday(day)) + " 09:15"
        if (vHolidayDay == day) return "No trading on the exchange today — looks like a market holiday. Opens " + Clock.dayLabel(Clock.nextWeekday(day)) + " 09:15"
        if (min < Clock.OPEN_MIN) return "Closed · opens 09:15 today"
        if (min >= Clock.CLOSE_MIN) return "Closed · opens " + Clock.dayLabel(Clock.nextWeekday(day)) + " 09:15"
        val tail = when {
            !vAnyLive && !vTested -> "Strategies are not tested yet — no new buys until the test has run"
            !vAnyLive -> NO_LIVE_TEXT
            min < Params.BUY_WINDOW_START_MIN -> "New buy ideas come at 2:45 pm"
            min <= Params.BUY_WINDOW_END_MIN -> "Buy window is open till 3:25 pm"
            else -> "Buy window is over for today"
        }
        return "Open · closes 15:30 · $tail"
    }

    // =========================================================================================== tick

    private fun tickLocked(trigger: String): String {
        val now = now()
        val day = Clock.epochDay(now)
        val min = Clock.minuteOfDay(now)
        val weekday = Clock.isWeekday(day)
        val done = ArrayList<String>()
        expireSignals(now)

        if (weekday && min >= Clock.BRIEF_MIN && min < Clock.OPEN_MIN && st.lastBriefDay != day) {
            st.lastBriefDay = day
            brief(day)
            done.add("brief")
        }
        val slot = Clock.scanSlotKey(now)
        val manual = trigger == "manual"
        val eodDue = weekday && min >= Clock.EOD_MIN && st.lastEodDay != day
        var doScan = manual || eodDue || (slot >= 0 && slot != st.lastScanSlot)
        if (!doScan && slot < 0 && (trigger == "open" || trigger == "boot") && st.lastScanAt < lastCloseBefore(now)) doScan = true
        if (doScan) {
            if (slot >= 0) st.lastScanSlot = slot
            scan(now, manual)
            done.add("scan: " + st.lastScanStatus)
        }
        if (eodDue) {
            st.lastEodDay = day
            if (st.holidayDay != day) {
                eodSummary(day)
                done.add("EOD")
            }
        }
        return if (done.isEmpty()) "Nothing due" else done.joinToString(" · ")
    }

    /** Most recent weekday 15:30 at or before [now]. */
    private fun lastCloseBefore(now: Long): Long {
        var d = Clock.epochDay(now)
        for (k in 0..8) {
            if (Clock.isWeekday(d)) {
                val t = Clock.msAt(d, Clock.CLOSE_MIN)
                if (t <= now) return t
            }
            d--
        }
        return 0L
    }

    private fun expireSignals(now: Long) {
        for (s in st.signals) if (s.status == "NEW" && now >= s.validTill) s.status = "EXPIRED"
    }

    // =========================================================================================== data

    private fun <T> fetchParsed(
        syms: List<String>, url: (String, String) -> String, parse: (String, String?) -> T?,
        label: String, pctFrom: Int, pctTo: Int
    ): HashMap<String, T> {
        val out = HashMap<String, T>()
        if (syms.isEmpty()) return out
        val batch = Params.HTTP_BATCH.coerceAtLeast(1)
        var pos = 0
        while (pos < syms.size) {
            val part = syms.subList(pos, minOf(pos + batch, syms.size))
            val failed = ArrayList<String>()
            val r1 = httpAll(part.map { url(Params.YAHOO_HOST_1, it) })
            for ((k, sym) in part.withIndex()) {
                val v = try { parse(sym, r1.getOrNull(k)) } catch (t: Throwable) { null }
                if (v != null) out[sym] = v else failed.add(sym)
            }
            if (failed.isNotEmpty()) {
                val r2 = httpAll(failed.map { url(Params.YAHOO_HOST_2, it) })
                for ((k, sym) in failed.withIndex()) {
                    val v = try { parse(sym, r2.getOrNull(k)) } catch (t: Throwable) { null }
                    if (v != null) out[sym] = v
                }
            }
            pos += part.size
            setBusy(label, pctFrom + (pctTo - pctFrom) * pos / syms.size)
        }
        return out
    }

    private fun httpAll(urls: List<String>): List<String?> =
        try { p.httpGetAll(urls) } catch (t: Throwable) { log("httpGetAll threw: $t"); emptyList() }

    /** Cached completed days plus any newer per-day bars from the intraday feed (today's partial bar last). */
    private fun buildLive(sym: String, intr: Intraday?): Series? {
        val base = cache.get(sym)
        if (base == null && intr == null) return null
        val cap = (base?.n ?: 0) + (intr?.days?.n ?: 0) + 1
        val s = Series(sym, cap)
        if (base != null) for (k in 0 until base.n) s.add(base.d[k], base.o[k], base.h[k], base.l[k], base.c[k], base.v[k])
        if (intr != null) {
            val x = intr.days
            for (k in 0 until x.n) s.add(x.d[k], x.o[k], x.h[k], x.l[k], x.c[k], x.v[k])
        }
        return if (s.n > 0) s else null
    }

    /** Fetches daily history (when not yet fetched under [dailyKey]) and intraday bars for [syms]; fills `live` and `quotes`. Returns symbols with a quote. */
    private fun fetchSymbols(syms: List<String>, today: Int, dailyKey: Int, force: Boolean, pct0: Int, pct1: Int): Int {
        val needDaily = syms.filter { val k = cache.keyOf(it); k != dailyKey || (force && cache.get(it) == null) }
        val mid = if (needDaily.isEmpty()) pct0 else pct0 + (pct1 - pct0) * 6 / 10
        if (needDaily.isNotEmpty()) {
            val got = fetchParsed(needDaily, { h, s -> Yahoo.dailyUrl(h, s) }, { s, t -> Yahoo.daily(s, t, today) }, "Loading price history…", pct0, mid)
            for (sym in needDaily) {
                val s = got[sym]
                if (s != null) cache.put(sym, s, dailyKey)
                else if (cache.get(sym) == null) cache.put(sym, Series(sym, 1), dailyKey) // remember the failure for today
            }
        }
        val intr = fetchParsed(syms, { h, s -> Yahoo.intradayUrl(h, s) }, { s, t -> Yahoo.intraday(s, t) }, "Getting live prices…", mid, pct1)
        var n = 0
        for (sym in syms) {
            val q = intr[sym]
            if (q != null) { quotes[sym] = q; n++ } else quotes.remove(sym)
            val s = buildLive(sym, q)
            if (s != null) live[sym] = s else live.remove(sym)
        }
        return n
    }

    private fun dailyKeyFor(day: Int): Int {
        var d = day
        while (!Clock.isWeekday(d)) d--
        return d
    }

    // =========================================================================================== scan

    private fun scan(now: Long, manual: Boolean): Boolean {
        val day = Clock.epochDay(now)
        val min = Clock.minuteOfDay(now)
        val weekday = Clock.isWeekday(day)
        setBusy("Scanning…", 1)
        loadUniverse()
        cache.ensureLoaded()
        val idxSym = Params.INDEX_SYMBOL

        // 1. the index first: tells us whether we are online and whether the exchange is trading today
        val iq = fetchParsed(listOf(idxSym), { h, s -> Yahoo.intradayUrl(h, s) }, { s, t -> Yahoo.intraday(s, t) }, "Scanning…", 1, 3)[idxSym]
        if (iq == null) {
            st.lastScanStatus = "No internet — showing last data"
            if (st.offlineWarnDay != day) {
                st.offlineWarnDay = day
                feed("warn", "Could not reach the data source", "No internet or the price feed is down. Showing the last saved data; will retry on the next scan.")
            }
            return false
        }
        val indexToday = iq.days.lastDay() == day
        if (weekday && min >= Clock.HOLIDAY_CHECK_MIN && !indexToday) {
            if (st.holidayDay != day) {
                st.holidayDay = day
                feed("info", "Market looks closed today", "Nifty has not traded since the open — probably an exchange holiday. No new buy ideas today.")
            }
        } else if (indexToday && st.holidayDay == day) st.holidayDay = 0
        if (!indexToday && !manual && st.lastScanAt > iq.lastTsMs + 30 * 60_000L) {
            st.lastScanStatus = "Market closed — no new prices since the last scan"
            return true
        }

        // 2. prices
        val all = LinkedHashSet<String>()
        all.add(idxSym)
        all.addAll(universe)
        for (h in st.holdings) all.add(h.sym)
        for (t in st.trades) if (t.status == "OPEN") all.add(t.sym)
        val key = dailyKeyFor(day)
        fetchSymbols(all.toList(), day, key, manual, 3, 80)
        var okCount = 0
        for (s in universe) if (quotes.containsKey(s) && live.containsKey(s)) okCount++
        cache.retain(all)
        cache.flush()

        // 3. regime
        val idx = live[idxSym]
        updateRegime(idx)
        if (idx != null && idx.n > 0) st.lastTradingDay = idx.lastDay()

        // 4. backtest when missing or stale (before signals, so a fresh install can go LIVE the same day)
        if (backtestDue(now)) runBacktestLocked(now)

        // 5. follow-ups, portfolio, signals
        setBusy("Checking trades and holdings…", 90)
        val complete = { d: Int -> d < day || (d == day && min >= Clock.CLOSE_MIN) }
        followTrades(now, day, min, complete)
        checkHoldings(day, now, null, true)
        val inWindow = weekday && indexToday && st.holidayDay != day &&
            min >= Params.BUY_WINDOW_START_MIN && min <= Params.BUY_WINDOW_END_MIN
        if (inWindow) makeSignals(now, day)

        st.lastScanAt = now
        st.lastScanStatus = when {
            universe.isEmpty() -> "Stock list missing — only holdings were checked"
            okCount == 0 -> "Price feed not responding — showing last data"
            else -> "OK · $okCount/${universe.size} stocks"
        }
        return true
    }

    private fun updateRegime(idx: Series?) {
        if (idx == null || idx.n == 0) return
        val i = idx.n - 1
        st.niftyLtp = idx.c[i]
        st.niftyChgPct = if (i >= 1 && idx.c[i - 1] > 0) (idx.c[i] / idx.c[i - 1] - 1) * 100.0 else 0.0
        if (idx.n < Params.REGIME_SLOW_DMA) {
            st.regimeLabel = "NEUTRAL"
            st.regimeNote = "Not enough Nifty history yet to read the trend"
            return
        }
        val r = Strategies.regime(idx, i)
        val f = Params.REGIME_FAST_DMA
        val s = Params.REGIME_SLOW_DMA
        st.regimeLabel = if (r > 0) "BULL" else if (r < 0) "BEAR" else "NEUTRAL"
        st.regimeNote = if (r > 0) "Nifty above $f & $s DMA" else if (r < 0) "Nifty below $f & $s DMA — new buys are off" else "Nifty between its $f & $s DMA — be selective"
    }

    private fun regimeInt(): Int = when (st.regimeLabel) { "BULL" -> 1; "BEAR" -> -1; else -> 0 }

    // =========================================================================================== backtest

    private fun backtestDue(now: Long): Boolean {
        if (Strategies.all.any { st.strat(it.id).testedAt == 0L }) return true
        return now - st.lastBacktestAt > Params.BACKTEST_MAX_AGE_DAYS * Clock.DAY_MS
    }

    private fun runBacktestLocked(now: Long): String? {
        loadUniverse()
        cache.ensureLoaded()
        val list = ArrayList<Series>()
        for (sym in universe) {
            val s = cache.get(sym) ?: continue
            if (s.n >= Params.MIN_BARS) list.add(s)
        }
        if (list.size < Params.BT_MIN_SYMBOLS) return "Not enough price history yet — run a scan while online first"
        setBusy("Backtesting…", 0)
        val res = Backtest.run(list, cache.get(Params.INDEX_SYMBOL), st.settings.costPct) { pct -> setBusy("Backtesting…", pct) }
        var liveN = 0
        var paused = 0
        for (sg in Strategies.all) {
            val ss = res.stats[sg.id] ?: StratStats()
            val x = st.strat(sg.id)
            val was = x.verdict
            x.n = ss.n; x.winPct = ss.winPct; x.avgPct = ss.avgPct; x.pf = ss.pf; x.maxDdPct = ss.maxDdPct; x.oosN = ss.oosN; x.oosAvgPct = ss.oosAvgPct
            x.testedAt = now
            x.verdict = if (sg.enabled() && Gate.passes(ss)) "LIVE" else "PAUSED"
            x.why = Gate.why(sg, ss)
            if (x.verdict == "LIVE") liveN++ else paused++
            if (was == "LIVE" && x.verdict == "PAUSED") feed("warn", sg.label + " paused", x.why)
        }
        st.lastBacktestAt = now
        feed("info", "Backtest finished", "$liveN LIVE, $paused PAUSED · tested on ${res.symbols} stocks over ${res.days} trading days." +
            if (liveN == 0) " $NO_LIVE_TEXT" else "")
        log("backtest: ${res.symbols} symbols, ${res.days} days, ${res.trades.size} trades, ${res.millis} ms")
        return null
    }

    // =========================================================================================== signals

    /** Shares to buy so that the loss at the stop INCLUDING charges stays within riskPct of capital; 0 if not affordable. */
    private fun sizeFor(entry: Double, sl: Double): Int = positionSize(st.settings.capital, st.settings.riskPct, st.settings.costPct, entry, sl)

    private fun makeSignals(now: Long, day: Int) {
        val liveStrats = Strategies.all.filter { it.enabled() && st.strat(it.id).verdict == "LIVE" }
        if (liveStrats.isEmpty()) {
            if (st.noLiveWarnDay != day) {
                st.noLiveWarnDay = day
                feed("warn", "No new buys today", NO_LIVE_TEXT)
            }
            return
        }
        val uni = ArrayList<Series>()
        for (sym in universe) live[sym]?.let { uni.add(it) }
        if (uni.isEmpty()) return
        val idxArr = IntArray(uni.size) { k -> if (uni[k].lastDay() == day) uni[k].n - 1 else -1 }
        val ctx = Ctx()
        ctx.regime = regimeInt()
        ctx.sessionFraction = Clock.sessionFraction(now)
        Strategies.fillCutoffs(ctx, uni, idxArr, DoubleArray(uni.size))
        val feat = Feat()
        val found = ArrayList<Setup>()
        for ((k, s) in uni.withIndex()) {
            val i = idxArr[k]
            if (i < 0) continue
            val q = quotes[s.sym] ?: continue
            if (now - q.lastTsMs > Params.STALE_QUOTE_MIN * 60_000L) continue
            if (!feat.compute(s, i)) continue
            for (sg in liveStrats) Strategies.evaluate(sg, s, i, feat, ctx)?.let { found.add(it) }
        }
        if (found.isEmpty()) return
        found.sortWith(compareByDescending<Setup> { st.strat(it.strategyId).avgPct }.thenByDescending { it.rank }.thenBy { it.sym })
        var slots = st.settings.maxOpenTrades - st.trades.count { it.status == "OPEN" && !it.paper } - st.signals.count { it.status == "NEW" }
        for (raw in found) {
            if (slots <= 0) break
            // levels are fixed to the paisa here, so the money maths uses exactly what the user is shown
            val su = Setup(raw.strategyId, raw.sym, raw.day, Fmt.r2(raw.entry), Fmt.r2(raw.sl), Fmt.r2(raw.t1), Fmt.r2(raw.t2), raw.rank, raw.reasons)
            if (!(su.sl < su.entry)) continue
            if (st.signals.any { it.sym == su.sym && it.day == day }) continue
            if (st.trades.any { it.status == "OPEN" && it.sym == su.sym }) continue
            val qty = sizeFor(su.entry, su.sl)
            if (qty < 1) continue
            val sg = Strategies.byId(su.strategyId) ?: continue
            val ss = st.strat(su.strategyId)
            val cost = su.entry * qty * st.settings.costPct / 100.0
            val sig = Signal()
            sig.id = "S$day-${su.sym}"
            sig.sym = su.sym; sig.name = nameOf(su.sym); sig.strategyId = sg.id; sig.strategyLabel = sg.label
            sig.createdAt = now; sig.day = day; sig.validTill = Clock.msAt(Clock.nextWeekday(day), Clock.OPEN_MIN)
            sig.ltp = su.entry; sig.entry = su.entry; sig.entryMax = Fmt.r2(su.entry * Params.ENTRY_MAX_MULT)
            sig.sl = su.sl; sig.t1 = su.t1; sig.t2 = su.t2; sig.qty = qty
            sig.investRs = su.entry * qty
            sig.riskRs = qty * (su.entry - su.sl) + cost
            val half = if (qty >= 2) qty / 2 else 0
            sig.rewardRs = if (su.t2 > 0) maxOf(0.0, half * (su.t1 - su.entry) + (qty - half) * (su.t2 - su.entry) - cost) else 0.0
            sig.edgeN = ss.n; sig.edgeWinPct = ss.winPct; sig.edgeAvgPct = ss.avgPct; sig.edgePf = ss.pf
            sig.reasons = su.reasons
            st.signals.add(sig)
            // every signal starts life as a paper trade; it is removed if the user takes the trade for real
            val pt = Trade()
            pt.id = "P" + (++st.seq); pt.paper = true; pt.signalId = sig.id
            pt.sym = su.sym; pt.name = sig.name; pt.strategyId = sg.id; pt.strategyLabel = sg.label
            pt.openedAt = now; pt.entryDay = day; pt.lastBarDay = day
            pt.entry = su.entry; pt.qty = qty; pt.sl = su.sl; pt.sl0 = su.sl; pt.t1 = su.t1; pt.t2 = su.t2; pt.ltp = su.entry
            st.trades.add(pt)
            val levels = if (su.t2 > 0) "SL " + Fmt.rs(su.sl) + " · T1 " + Fmt.rs(su.t1) + " · T2 " + Fmt.rs(su.t2)
            else "SL " + Fmt.rs(su.sl) + " · sell at close of day " + Params.HOLD_DAYS
            post("buy", nid("buy", sig.id), "BUY " + su.sym + " " + Fmt.rs(su.entry) + "–" + Fmt.num(sig.entryMax),
                levels + " · " + Fmt.plural(qty, "share") + " (" + Fmt.rs0(sig.investRs) + ") · " + sg.label + ": " + ss.n + " test trades, " +
                    Math.round(ss.winPct) + "% won, avg " + Fmt.pct(ss.avgPct))
            slots--
        }
    }

    // =========================================================================================== trade follow-up

    private fun dayNo(s: Series, entryDay: Int, upto: Int): Int {
        var n = 1
        for (k in s.n - 1 downTo 0) {
            val d = s.d[k]
            if (d <= entryDay) break
            if (d <= upto) n++
        }
        return n
    }

    private fun followTrades(now: Long, today: Int, min: Int, complete: (Int) -> Boolean) {
        val pos = Pos()
        for (t in st.trades) {
            if (t.status != "OPEN") continue
            val s = live[t.sym] ?: continue
            if (s.n == 0) continue
            val cost = st.settings.costPct
            // --- finished daily bars not yet applied (never the entry day itself)
            for (k in 0 until s.n) {
                val d = s.d[k]
                if (d <= t.lastBarDay || d <= t.entryDay || !complete(d)) continue
                if (t.exitDue == 0) {
                    val dn = dayNo(s, t.entryDay, d)
                    pos.open(t.entry, t.sl, t.t1, t.t2, t.halfFrac())
                    pos.t1Done = t.t1Done; pos.t1Px = t.t1Px
                    val seenLive = !t.paper && t.t1Done && t.t1Day == d
                    // if T1 was already acted on intraday that day, the day's low may predate it: judge the stop on the close only
                    val lo = if (seenLive) s.c[k] else s.l[k]
                    val op = if (seenLive) s.c[k] else s.o[k]
                    ExitModel.step(pos, op, s.h[k], lo, s.c[k], dn >= t.maxDays)
                    if (pos.t1Done && !t.t1Done) { t.t1Done = true; t.t1Px = pos.t1Px; t.t1Day = if (t.paper) d else today; t.sl = pos.sl }
                    if (pos.closed) {
                        if (t.paper) {
                            closeTrade(t, pos.exitPx, Clock.msAt(d, Clock.CLOSE_MIN), ExitModel.resultText(pos.reason, pos.t1Done), cost)
                        } else {
                            t.exitDue = if (pos.reason and ExitModel.STOP != 0) ExitModel.STOP else if (pos.reason and ExitModel.T2 != 0) ExitModel.T2 else ExitModel.TIME
                        }
                    }
                }
                t.lastBarDay = d
                if (t.status != "OPEN") break
            }
            if (t.status != "OPEN") continue
            val i = s.n - 1
            val ltp = s.c[i]
            t.ltp = ltp
            t.day = dayNo(s, t.entryDay, Int.MAX_VALUE).coerceAtMost(99)
            if (t.paper) {
                t.pnlRs = t.pnlAt(ltp, cost); t.pnlPct = t.pnlRs / (t.entry * t.qty) * 100.0
                continue
            }
            // --- today's partial bar (real trades only)
            if (s.d[i] == today && !complete(today) && t.exitDue == 0) {
                val entryToday = t.entryDay == today
                val t1Today = t.t1Done && t.t1Day == today
                val lowSeen = if (entryToday || t1Today) ltp else s.l[i]
                val highSeen = if (entryToday) ltp else s.h[i]
                if (lowSeen <= t.sl) t.exitDue = ExitModel.STOP
                else {
                    if (!t.t1Done && t.t1 > 0 && highSeen >= t.t1) { t.t1Done = true; t.t1Px = t.t1; t.t1Day = today; if (t.entry > t.sl) t.sl = t.entry }
                    if (t.t2 > 0 && highSeen >= t.t2) t.exitDue = ExitModel.T2
                    else if (t.day >= t.maxDays && min >= Params.TIME_EXIT_FROM_MIN) t.exitDue = ExitModel.TIME
                }
            }
            t.pnlRs = t.pnlAt(ltp, cost); t.pnlPct = t.pnlRs / (t.entry * t.qty) * 100.0
            decideAction(t, today)
        }
    }

    private fun decideAction(t: Trade, today: Int) {
        val half = t.halfQty()
        var key: String
        var title = ""
        when {
            t.exitDue == ExitModel.STOP -> {
                t.action = "EXIT"; key = "EXIT_STOP"
                t.actionNote = if (t.t1Done && t.sl >= t.entry) "Price came back to your buy price " + Fmt.rs(t.sl) + " after Target 1. Sell the remaining shares now."
                else "Stop-loss " + Fmt.rs(t.sl) + " was hit (last price " + Fmt.rs(t.ltp) + "). Sell at market now."
                title = "EXIT " + t.sym + " now"
            }
            t.exitDue == ExitModel.T2 -> {
                t.action = "EXIT"; key = "EXIT_T2"
                t.actionNote = "Target 2 " + Fmt.rs(t.t2) + " reached. Sell the remaining shares and book the profit."
                title = t.sym + " hit Target 2 — sell"
            }
            t.exitDue == ExitModel.TIME -> {
                t.action = "TIME_EXIT"; key = "TIME_EXIT"
                t.actionNote = if (t.day <= t.maxDays) "Day " + t.day + " of " + t.maxDays + " — the holding period ends today. Sell before the 3:30 pm close."
                else "The " + t.maxDays + "-day holding period is over. Sell at market."
                title = t.sym + ": time exit — sell today"
            }
            t.t1Done && t.t1Day == today && half >= 1 -> {
                t.action = "BOOK_HALF"; key = "BOOK_HALF"
                t.actionNote = "Target 1 " + Fmt.rs(t.t1) + " reached. Sell " + Fmt.plural(half, "share") + " and move the stop on the rest to your buy price " + Fmt.rs(t.entry) + "."
                title = t.sym + " reached Target 1"
            }
            t.t1Done && t.t1Day == today -> {
                t.action = "HOLD"; key = "T1_COST"
                t.actionNote = "Target 1 " + Fmt.rs(t.t1) + " reached. Move your stop up to your buy price " + Fmt.rs(t.entry) + " and hold for Target 2 " + Fmt.rs(t.t2) + "."
                title = t.sym + " reached Target 1 — move stop to cost"
            }
            else -> {
                t.action = "HOLD"; key = "HOLD"
                val tgt = if (t.t2 <= 0) "sell at the close of day " + t.maxDays else if (t.t1Done) "Target 2 " + Fmt.rs(t.t2) else "Target 1 " + Fmt.rs(t.t1)
                t.actionNote = "Day " + t.day + " of " + t.maxDays + ". Stop-loss " + Fmt.rs(t.sl) + (if (t.t1Done) " (your buy price)" else "") + " · next: " + tgt + "."
            }
        }
        if (key != t.notified) {
            if (key != "HOLD") post("sell", nid("trade", t.id), title, t.actionNote + " P&L now " + Fmt.rsSigned(t.pnlRs) + " after charges.")
            t.notified = key
        }
    }

    private fun closeTrade(t: Trade, px: Double, at: Long, result: String, costPct: Double) {
        t.status = "CLOSED"
        t.exitPrice = px; t.ltp = px; t.closedAt = at; t.result = result
        t.pnlRs = t.pnlAt(px, costPct)
        t.pnlPct = t.pnlRs / (t.entry * t.qty) * 100.0
        t.action = "HOLD"; t.actionNote = ""
    }

    // =========================================================================================== holdings

    private fun checkHoldings(day: Int, now: Long, only: Set<String>?, push: Boolean) {
        if (st.holdings.isEmpty()) return
        for (h in st.holdings) {
            val s = live[h.sym] ?: continue
            if (s.n == 0) continue
            h.ltp = s.c[s.n - 1]
            h.prevClose = if (s.n >= 2) s.c[s.n - 2] else h.prevClose
        }
        var total = 0.0
        for (h in st.holdings) total += h.valueRs()
        val idx = live[Params.INDEX_SYMBOL] ?: cache.get(Params.INDEX_SYMBOL)
        for (h in st.holdings) {
            if (only != null && !only.contains(h.sym)) continue
            if (h.name.isEmpty() || h.name == h.sym) h.name = nameOf(h.sym)
            val s = live[h.sym]
            if (s == null && h.dataOk) continue // keep the last good verdict when this scan had no data for it
            val frac = if (s != null && s.lastDay() == day) Clock.sessionFraction(now).let { if (Clock.minuteOfDay(now) >= Clock.CLOSE_MIN) 1.0 else it } else 1.0
            val weight = if (total > 0) h.valueRs() / total * 100.0 else 0.0
            val a = Advisor.check(s, idx, frac, if (st.holdings.size >= 2) weight else 0.0)
            h.verdict = a.verdict; h.headline = a.headline; h.stop = a.stop; h.bookAt = a.bookAt; h.reasons = a.reasons; h.dataOk = a.dataOk
            if (!a.dataOk) continue
            // h.notifiedKey = "SELL,TRIM,@day": the verdicts already announced today (each at most once a day)
            val dayTag = "@$day"
            if (!h.notifiedKey.endsWith(dayTag)) h.notifiedKey = dayTag
            if ((a.verdict == Advisor.SELL || a.verdict == Advisor.TRIM) && a.verdict != h.lastVerdict && !h.notifiedKey.contains(a.verdict + ",")) {
                h.notifiedKey = a.verdict + "," + h.notifiedKey
                val title = if (a.verdict == Advisor.SELL) h.sym + " moved to SELL" else h.sym + ": book part (TRIM)"
                post("sell", nid("hold", h.sym), title, a.headline + ". Last price " + Fmt.rs(h.ltp) + ", stop " + Fmt.rs(a.stop) + ".", push)
            } else if (a.belowStop && !h.belowStop && a.verdict != Advisor.SELL && h.lastVerdict != Advisor.SELL && h.stopCrossDay != day) {
                h.stopCrossDay = day
                post("sell", nid("hold", h.sym), h.sym + " slipped below its stop", "Last price " + Fmt.rs(h.ltp) + " is under the trailing stop " + Fmt.rs(a.stop) + ". " + a.headline + ".", push)
            }
            h.lastVerdict = a.verdict
            h.belowStop = a.belowStop
        }
    }

    // =========================================================================================== brief / EOD

    private fun brief(day: Int) {
        val open = st.trades.filter { it.status == "OPEN" && !it.paper }
        val sb = StringBuilder()
        sb.append("Nifty trend: ").append(st.regimeLabel).append(". ")
        if (open.isEmpty()) sb.append("No open trades. ") else {
            sb.append(Fmt.plural(open.size, "open trade")).append(". ")
            val act = open.filter { it.action != "HOLD" }
            if (act.isNotEmpty()) sb.append("Needs action: ").append(act.joinToString(", ") { it.sym + " (" + it.action.replace('_', ' ') + ")" }).append(". ")
            val last = open.filter { it.action == "HOLD" && it.day >= it.maxDays - 1 }
            if (last.isNotEmpty()) sb.append("Last day today: ").append(last.joinToString(", ") { it.sym }).append(". ")
        }
        val sell = st.holdings.count { it.verdict == "SELL" }
        val trim = st.holdings.count { it.verdict == "TRIM" }
        if (sell + trim > 0) sb.append("Portfolio: ").append(sell).append(" SELL, ").append(trim).append(" TRIM. ")
        sb.append(
            if (!anyLive()) (if (Strategies.all.all { st.strat(it.id).testedAt == 0L }) "Strategies get tested on the first scan." else NO_LIVE_TEXT)
            else if (st.regimeLabel == "BEAR") "Market is in a downtrend — no new buys today."
            else "New buy ideas, if any, come at 2:45 pm."
        )
        post("brief", NID_BRIEF, "Morning brief · " + Clock.dayLabel(day), sb.toString().trim())
    }

    private fun eodSummary(day: Int) {
        val sigs = st.signals.count { it.day == day }
        val open = st.trades.filter { it.status == "OPEN" && !it.paper }
        val sc = scorecard()
        val sb = StringBuilder()
        sb.append(if (sigs == 0) "No buy ideas today. " else Fmt.plural(sigs, "buy idea") + " today. ")
        if (open.isNotEmpty()) {
            var pnl = 0.0
            for (t in open) pnl += t.pnlRs
            sb.append(Fmt.plural(open.size, "open trade")).append(", P&L ").append(Fmt.rsSigned(pnl)).append(" after charges. ")
            val act = open.filter { it.action != "HOLD" }
            if (act.isNotEmpty()) sb.append("Pending: ").append(act.joinToString(", ") { it.sym + " " + it.action.replace('_', ' ') }).append(". ")
        }
        val n = (sc["n"] as Int)
        val pn = (sc["paperN"] as Int)
        if (n > 0) sb.append("Your closed trades: ").append(n).append(", ").append(Math.round(sc["winPct"] as Double)).append("% won, net ").append(Fmt.rsSigned(sc["netRs"] as Double)).append(". ")
        if (pn > 0) sb.append("Calls you skipped: ").append(pn).append(", ").append(Math.round(sc["paperWinPct"] as Double)).append("% won, avg ").append(Fmt.pct(sc["paperAvgPct"] as Double)).append(". ")
        val sell = st.holdings.count { it.verdict == "SELL" }
        val trim = st.holdings.count { it.verdict == "TRIM" }
        if (sell + trim > 0) sb.append("Portfolio: ").append(sell).append(" SELL, ").append(trim).append(" TRIM.")
        post("brief", NID_EOD, "Day's scorecard · " + Clock.dayLabel(day), sb.toString().trim())
    }

    // =========================================================================================== actions

    private fun actionLocked(name: String, a: Map<String, Any?>): Map<String, Any?> {
        val now = now()
        val day = Clock.epochDay(now)
        expireSignals(now)
        when (name) {
            "scanNow" -> {
                val okScan = scan(now, true)
                return if (okScan) ok("status" to st.lastScanStatus) else err(st.lastScanStatus)
            }
            "runBacktest" -> {
                loadUniverse()
                cache.ensureLoaded()
                if (universe.count { (cache.get(it)?.n ?: 0) >= Params.MIN_BARS } < Params.BT_MIN_SYMBOLS) {
                    if (!scan(now, true)) return err(st.lastScanStatus)
                    if (st.lastBacktestAt == now) return ok("status" to "Backtest finished")
                }
                val e = runBacktestLocked(now)
                return if (e == null) ok("status" to "Backtest finished") else err(e)
            }
            "addHolding" -> {
                loadUniverse()
                val sym = HoldingsImport.cleanSymbol(a.str("sym"))
                val qty = a.num("qty", Double.NaN)
                val avg = a.num("avg", Double.NaN)
                if (sym.isEmpty() || sym.length > 25 || !Regex("^[A-Z0-9&][A-Z0-9&\\-]*$").matches(sym)) return err("Enter a valid NSE symbol, e.g. RELIANCE")
                if (qty.isNaN() || qty <= 0 || qty > 1e9) return err("Quantity must be more than 0")
                if (avg.isNaN() || avg <= 0 || avg > 1e7) return err("Average buy price must be more than 0")
                upsertHolding(sym, qty, avg)
                refreshHoldings(listOf(sym), now, day)
                return ok("sym" to sym)
            }
            "removeHolding" -> {
                val sym = HoldingsImport.cleanSymbol(a.str("sym"))
                val removed = st.holdings.removeAll { it.sym == sym }
                return if (removed) ok() else err("$sym is not in your holdings")
            }
            "importHoldings" -> {
                loadUniverse()
                val res = HoldingsImport.parse(a.str("text"), nameToSym, universe.toHashSet())
                var added = 0
                var updated = 0
                for (r in res.rows) if (upsertHolding(r.sym, r.qty, r.avg)) added++ else updated++
                if (res.rows.isNotEmpty()) refreshHoldings(res.rows.map { it.sym }, now, day)
                if (res.rows.isEmpty()) {
                    val m = LinkedHashMap(err("No holdings found in that text. Expected columns like Symbol, Quantity, Average price."))
                    m["added"] = 0; m["updated"] = 0; m["skipped"] = res.skipped
                    return m
                }
                return ok("added" to added, "updated" to updated, "skipped" to res.skipped)
            }
            "takeTrade" -> {
                val sig = st.signals.firstOrNull { it.id == a.str("signalId") } ?: return err("That buy idea no longer exists")
                if (sig.status == "TAKEN") return err("You already took this trade")
                if (sig.status == "EXPIRED" || now >= sig.validTill) return err("This buy idea has expired")
                val price = if (a.containsKey("price") && a["price"] != null) a.num("price", Double.NaN) else sig.entry
                val qty = if (a.containsKey("qty") && a["qty"] != null) a.num("qty", Double.NaN) else sig.qty.toDouble()
                if (price.isNaN() || price <= 0) return err("Enter the price you bought at")
                if (qty.isNaN() || qty < 1 || qty > 1e8) return err("Quantity must be at least 1")
                if (price <= sig.sl) return err("That price is at or below the stop-loss " + Fmt.rs(sig.sl) + " — this trade is no longer valid")
                val t = Trade()
                t.id = "T" + (++st.seq); t.signalId = sig.id
                t.sym = sig.sym; t.name = sig.name; t.strategyId = sig.strategyId; t.strategyLabel = sig.strategyLabel
                t.openedAt = now; t.entryDay = day; t.lastBarDay = day
                t.entry = price; t.qty = qty.toInt(); t.sl = sig.sl; t.sl0 = sig.sl; t.t1 = sig.t1; t.t2 = sig.t2
                t.ltp = live[sig.sym]?.let { it.c[it.n - 1] } ?: price
                t.pnlRs = t.pnlAt(t.ltp, st.settings.costPct); t.pnlPct = t.pnlRs / (t.entry * t.qty) * 100.0
                t.action = "HOLD"; t.notified = "HOLD"
                t.actionNote = "Day 1 of " + t.maxDays + ". Stop-loss " + Fmt.rs(t.sl) + " · next: " + (if (t.t1 > 0) "Target 1 " + Fmt.rs(t.t1) else "sell at the close of day " + t.maxDays) + "."
                st.trades.removeAll { it.paper && it.signalId == sig.id }
                st.trades.add(t)
                sig.status = "TAKEN"
                val riskRs = t.qty * (t.entry - t.sl) + t.entry * t.qty * st.settings.costPct / 100.0
                val warn = if (riskRs > st.settings.capital * st.settings.riskPct / 100.0 * 1.05)
                    "This size risks " + Fmt.rs0(riskRs) + " incl. charges — more than your " + Fmt.p1(st.settings.riskPct) + "% limit." else ""
                return ok("tradeId" to t.id, "warning" to warn)
            }
            "skipSignal" -> {
                val sig = st.signals.firstOrNull { it.id == a.str("signalId") } ?: return err("That buy idea no longer exists")
                if (sig.status != "NEW") return err("This buy idea is already " + sig.status.lowercase())
                sig.status = "SKIPPED"
                return ok()
            }
            "closeTrade" -> {
                val t = st.trades.firstOrNull { it.id == a.str("tradeId") && !it.paper } ?: return err("Trade not found")
                if (t.status != "OPEN") return err("This trade is already closed")
                var price = a.num("price", Double.NaN)
                if (price.isNaN() || price <= 0) price = t.ltp
                if (!(price > 0) || price > 1e7) return err("Enter the price you sold at")
                val result = when (t.exitDue) {
                    ExitModel.STOP -> if (t.t1Done && t.sl >= t.entry) "T1 hit, rest at entry" else "Stop-loss hit"
                    ExitModel.T2 -> "T2 hit"
                    ExitModel.TIME -> "Time exit on day " + t.maxDays
                    else -> if (t.pnlAt(price, st.settings.costPct) >= 0) "Closed early in profit" else "Closed early at a loss"
                }
                closeTrade(t, price, now, result, st.settings.costPct)
                feed("sell", t.sym + " closed " + Fmt.rsSigned(t.pnlRs), result + " · sold at " + Fmt.rs(price) + " · " + Fmt.pct(t.pnlPct) + " after charges.")
                return ok("pnlRs" to Fmt.r2(t.pnlRs), "pnlPct" to Fmt.r2(t.pnlPct))
            }
            "saveSettings" -> {
                val oldCost = st.settings.costPct
                val tmp = Settings()
                tmp.apply(st.settings.toMap(), false)
                val e = tmp.apply(a, true)
                if (e != null) return err(e)
                st.settings.apply(tmp.toMap(), false)
                if (st.settings.costPct != oldCost) st.lastBacktestAt = 0L // charges changed: re-test on the next scan
                return ok()
            }
            "clearFeed" -> {
                st.feed.clear()
                return ok()
            }
            else -> return err("Unknown action: $name")
        }
    }

    /** @return true if a new holding was added, false if an existing one was replaced. */
    private fun upsertHolding(sym: String, qty: Double, avg: Double): Boolean {
        val ex = st.holdings.firstOrNull { it.sym == sym }
        if (ex != null) {
            ex.qty = qty; ex.avg = avg
            return false
        }
        val h = Holding()
        h.sym = sym; h.name = nameOf(sym); h.qty = qty; h.avg = avg
        st.holdings.add(h)
        return true
    }

    /** Quick price load + verdict for just-added holdings (no notification: the user is looking at the app). */
    private fun refreshHoldings(syms: List<String>, now: Long, day: Int) {
        try {
            setBusy("Checking your holdings…", 5)
            cache.ensureLoaded()
            val need = LinkedHashSet(syms)
            if (!live.containsKey(Params.INDEX_SYMBOL)) need.add(Params.INDEX_SYMBOL)
            fetchSymbols(need.toList(), day, dailyKeyFor(day), true, 5, 90)
            cache.flush()
            checkHoldings(day, now, syms.toHashSet(), false)
        } catch (t: Throwable) {
            log("refreshHoldings failed: $t")
        }
    }

    // =========================================================================================== snapshot

    private fun scorecard(s: AppState = st): Map<String, Any?> {
        var n = 0; var w = 0; var net = 0.0; var sum = 0.0
        var pn = 0; var pw = 0; var psum = 0.0
        val closed = ArrayList<Trade>()
        for (t in s.trades) {
            if (t.status != "CLOSED") continue
            closed.add(t)
            if (t.paper) { pn++; if (t.pnlPct > 0) pw++; psum += t.pnlPct }
            else { n++; if (t.pnlRs > 0) w++; net += t.pnlRs; sum += t.pnlPct }
        }
        closed.sortByDescending { it.closedAt }
        return linkedMapOf(
            "n" to n, "winPct" to (if (n > 0) Fmt.r1(w * 100.0 / n) else 0.0), "netRs" to Fmt.r2(net), "avgPct" to (if (n > 0) Fmt.r2(sum / n) else 0.0),
            "paperN" to pn, "paperWinPct" to (if (pn > 0) Fmt.r1(pw * 100.0 / pn) else 0.0), "paperAvgPct" to (if (pn > 0) Fmt.r2(psum / pn) else 0.0),
            "recent" to closed.take(20).map {
                linkedMapOf("sym" to it.sym, "closedAt" to it.closedAt, "result" to it.result, "pnlRs" to Fmt.r2(it.pnlRs), "pnlPct" to Fmt.r2(it.pnlPct), "paper" to it.paper)
            }
        )
    }

    private fun buildSnapshot(s: AppState): Map<String, Any?> {
        run {
            val now = now()
            val m = LinkedHashMap<String, Any?>()
            m["now"] = now
            m["marketOpen"] = Clock.isMarketOpen(now)
            m["marketNote"] = ""
            m["lastScanAt"] = s.lastScanAt
            m["lastScanStatus"] = s.lastScanStatus
            m["nextScanAt"] = Clock.nextWake(now)
            m["busy"] = null
            m["settings"] = s.settings.toMap()
            m["regime"] = linkedMapOf("label" to s.regimeLabel, "niftyLtp" to Fmt.r2(s.niftyLtp), "niftyChgPct" to Fmt.r2(s.niftyChgPct), "note" to s.regimeNote)
            m["signals"] = s.signals.asReversed().take(30).map { it.toMap() }
            val real = s.trades.filter { !it.paper }
            m["trades"] = real.filter { it.status == "OPEN" }.asReversed().map { it.toMap() } +
                real.filter { it.status == "CLOSED" }.sortedByDescending { it.closedAt }.take(30).map { it.toMap() }
            m["holdings"] = s.holdings.map { it.toMap() }
            var inv = 0.0; var value = 0.0; var dayPnl = 0.0
            for (h in s.holdings) {
                inv += h.avg * h.qty; value += h.valueRs()
                if (h.ltp > 0 && h.prevClose > 0) dayPnl += (h.ltp - h.prevClose) * h.qty
            }
            m["portfolio"] = linkedMapOf(
                "investedRs" to Fmt.r2(inv), "valueRs" to Fmt.r2(value), "pnlRs" to Fmt.r2(value - inv),
                "pnlPct" to (if (inv > 0) Fmt.r2((value / inv - 1) * 100.0) else 0.0), "dayPnlRs" to Fmt.r2(dayPnl),
                "sellCount" to s.holdings.count { it.verdict == "SELL" }, "trimCount" to s.holdings.count { it.verdict == "TRIM" }
            )
            m["strategies"] = Strategies.all.map { sg ->
                val x = s.strat(sg.id)
                val off = !sg.enabled()
                linkedMapOf(
                    "id" to sg.id, "label" to sg.label, "desc" to sg.desc,
                    "verdict" to (if (off) "PAUSED" else x.verdict), "testedAt" to x.testedAt, "stats" to x.statsMap(),
                    "why" to (if (off) "Switched off in this version of the app." else x.why)
                )
            }
            m["scorecard"] = scorecard(s)
            m["feed"] = s.feed.take(AppState.MAX_FEED).map { it.toMap() }
            return m
        }
    }

    private fun publish() {
        vHolidayDay = st.holidayDay
        vAnyLive = anyLive()
        vTested = Strategies.all.any { st.strat(it.id).testedAt != 0L }
        snapshot = buildSnapshot(st)
    }

    companion object {
        const val STATE_FILE = "state.json"
        const val NID_BRIEF = 1001
        const val NID_EOD = 1002
        const val NO_LIVE_TEXT = "No strategy is passing its test right now — no new buys. Sell calls and portfolio checks still run."

        /** qty = floor(capital x riskPct% / (entry - sl + entry x costPct%)), capped at MAX_POSITION_FRACTION of capital. */
        fun positionSize(capital: Double, riskPct: Double, costPct: Double, entry: Double, sl: Double): Int {
            if (!(entry > 0) || !(sl > 0) || !(sl < entry) || !(capital > 0)) return 0
            val perShare = entry - sl + entry * costPct / 100.0
            if (!(perShare > 0)) return 0
            val byRisk = Math.floor(capital * riskPct / 100.0 / perShare + 1e-9)
            val byCap = Math.floor(capital * Params.MAX_POSITION_FRACTION / entry + 1e-9)
            val q = minOf(byRisk, byCap)
            return if (q.isNaN() || q < 1) 0 else if (q > 1e8) 100_000_000 else q.toInt()
        }
    }
}
