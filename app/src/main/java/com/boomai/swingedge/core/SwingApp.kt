package com.boomai.swingedge.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * The whole app behind four methods. tick/action are serialised by one lock; stateJson never takes it and serves the
 * last published snapshot (plus live clock fields and `busy`).
 *
 * Read-only actions (search, stockInfo, getChart) do NOT take the lock either, so they answer while a scan runs:
 * `live`/`quotes` are concurrent maps written only by the lock holder (Series objects are never modified once stored),
 * the daily cache has its own short-held monitor, the universe/search index are built once under their own lock and
 * published through volatile fields, and the parts of the app state they need come from [refs], an immutable copy
 * rebuilt on every publish().
 */
class SwingApp(private val p: Platform) {
    @Volatile private var watchItems: List<Map<String, Any?>> = emptyList()
    /** Setup-score ranking of the scanned universe from the latest scan (immutable; replaced as a whole), and its BUY rows. */
    @Volatile private var scoreTable: ScoreTable? = null
    @Volatile private var ideaItems: List<Map<String, Any?>> = emptyList()
    /** market gate from the last scan: true = new buys allowed, false = market below its 200-day average, null = unknown */
    @Volatile private var marketGate: Boolean? = null

    private val lock = ReentrantLock()
    private var st: AppState = AppState()
    @Volatile private var snapshot: Map<String, Any?> = emptyMap()
    @Volatile private var busy: Map<String, Any?>? = null
    @Volatile private var vHolidayDay = 0
    @Volatile private var vAnyLive = false
    @Volatile private var vTested = false

    private val cache = DailyCache(p)
    @Volatile private var universe: List<String> = emptyList()
    @Volatile private var universeSet: Set<String> = emptySet()
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var nameToSym: Map<String, String> = emptyMap()
    @Volatile private var universeLoaded = false
    private val uniLock = Any()
    /** Series (history + today's bar) and quotes from the latest fetch, kept in memory for quick actions. Written only under [lock]. */
    private val live = ConcurrentHashMap<String, Series>()
    private val quotes = ConcurrentHashMap<String, Intraday>()

    /** Immutable view of the bits of [st] that the lock-free read actions need. */
    private class Refs(
        /** sym -> [avg, stop, bookAt, valueRs] */
        val holdings: Map<String, DoubleArray>,
        val holdingsValue: Double,
        val watch: Set<String>,
        /** sym -> [entry, sl, t1, t2] of the first open real trade */
        val trades: Map<String, DoubleArray>,
        /** sym -> [entry, sl, t1, t2] of the first NEW signal */
        val signals: Map<String, DoubleArray>,
        val regime: Int,
        val verdicts: Map<String, String>,
        /** sym -> stockInfo `myTarget` object */
        val targets: Map<String, Map<String, Any?>> = emptyMap(),
        /** capital, riskPct, costPct */
        val sizing: DoubleArray = doubleArrayOf(Params.DEF_CAPITAL, Params.DEF_RISK_PCT, Params.DEF_COST_PCT)
    )
    @Volatile private var refs = Refs(emptyMap(), 0.0, emptySet(), emptyMap(), emptyMap(), 0, emptyMap())
    /** Cut-offs and breadth from the last full scan, for the stock page's strategy checks. */
    @Volatile private var scanCtx: Ctx? = null
    @Volatile private var symIndex: SymbolIndex? = null
    private val idxLock = Any()
    /** SPEC5 stock list behind [symIndex] (replaced as a whole, with the index, under [idxLock]). */
    @Volatile private var stockList: StockListData? = null
    /** Day of the last download attempt (once per day), and whether the latest attempt failed. */
    @Volatile private var listTriedDay = 0
    /** when a symbol's history download last failed (retried after RETRY_FAILED_MS or on a manual scan) */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var listFailed = false
    private val listLock = ReentrantLock()

    /** Daily history (and the last quote) of symbols looked up outside the scanned set; small in-memory LRU. */
    private class Looked(val daily: Series?, val key: Int, val quote: Intraday?, val quoteAt: Long)
    private val lru = object : LinkedHashMap<String, Looked>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Looked>?): Boolean = size > LRU_MAX
    }

    /** SPEC4 track record: written under [lock] only; readers see the published [trackView] / [trackProof]. */
    private val track = TrackStore()
    @Volatile private var trackView: Map<String, Any?> = emptyMap()
    @Volatile private var trackProof: TrackProof? = null
    @Volatile private var replaying = false
    private var trackRev = 0
    private var viewKey = ""
    private var replayTriedDay = 0

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
        try { track.loadLive(p.read(FORWARD_FILE)) } catch (t: Throwable) { log("forward.json unreadable: $t") }
        try { track.loadReplay(p.read(REPLAY_FILE)) } catch (t: Throwable) { log("replay.json unreadable: $t") }
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
            if (replaying) { val tv = LinkedHashMap(trackView); tv["busyReplay"] = true; m["track"] = tv }
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
        if (name == "search" || name == "stockInfo" || name == "getChart" || name == "newListings" || name == "moneyFlow" || name == "flowSector" || name == "funds" || name == "fundInfo" || name == "fundSearch" || name == "indexView" || name == "options" || name.startsWith("fno")) return readAction(name, argsJson)
        if (name == "refreshSymbols") return refreshSymbolsAction()
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
        synchronized(uniLock) {
            if (universeLoaded) return
            try { loadUniverseOnce() } finally { universeLoaded = true }
        }
    }

    private fun loadUniverseOnce() {
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
            // extra "SYMBOL,Company Name" pairs used only to recognise names in imported files (not scanned)
            for (line in (p.asset("aliases.csv") ?: "").split('\n')) {
                val k = line.indexOf(',')
                if (k <= 0) continue
                val sym = line.substring(0, k).trim().uppercase()
                val nn = HoldingsImport.normName(line.substring(k + 1))
                if (sym.isEmpty() || sym.contains(' ') || nn.isEmpty()) continue
                if (!rev.containsKey(nn)) rev[nn] = sym
                if (!nm.containsKey(sym)) nm[sym] = line.substring(k + 1).trim()
            }
            universe = syms; universeSet = syms.toHashSet(); names = nm; nameToSym = rev
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
        // SPEC5: the stock list, once per calendar day from 07:30 (not reported in the status: it changes nothing the user sees)
        if (min >= StockList.REFRESH_MIN && listTriedDay != day) refreshSymbols(false)
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
            if (label.isNotEmpty()) setBusy(label, pctFrom + (pctTo - pctFrom) * pos / syms.size)
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
    private fun fetchSymbols(syms: List<String>, today: Int, dailyKey: Int, force: Boolean, pct0: Int, pct1: Int, quoteOnly: Set<String>? = null): Int {
        val nowMs = p.now()
        loadAlt()
        // a download that failed is retried on a manual scan, and otherwise after RETRY_FAILED_MS (it is never "remembered for the day")
        val needDaily = syms.filter { sym ->
            val k = cache.keyOf(sym)
            val got = cache.get(sym)
            val failed = got == null || got.n == 0
            k != dailyKey || (failed && (force || nowMs - (failedAt[sym] ?: 0L) >= RETRY_FAILED_MS))
        }
        val mid = if (needDaily.isEmpty()) pct0 else pct0 + (pct1 - pct0) * 6 / 10
        if (needDaily.isNotEmpty()) {
            val got = fetchParsed(needDaily, { h, s -> Yahoo.dailyUrl(h, s) }, { s, t -> Yahoo.daily(s, t, today) }, "Loading price history…", pct0, mid)
            for (sym in needDaily) {
                val s = got[sym]
                if (s != null) { cache.put(sym, s, dailyKey); failedAt.remove(sym) }
                else {
                    failedAt[sym] = nowMs
                    if (cache.get(sym) == null) cache.put(sym, Series(sym, 1), dailyKey) // placeholder; retried later (see needDaily)
                }
            }
        }
        findElsewhere(syms, today, dailyKey, force)
        // Live quotes for everything only when needed (buy window with a LIVE strategy, or a manual scan);
        // otherwise just the index, holdings and open trades. History above is still kept for every stock.
        val qsyms = if (quoteOnly == null) syms else syms.filter { quoteOnly.contains(it) }
        val intr = fetchParsed(qsyms, { h, s -> Yahoo.intradayUrl(h, s) }, { s, t -> Yahoo.intraday(s, t) }, "Getting live prices…", mid, pct1)
        if (quoteOnly != null) for (sym in syms) if (!quoteOnly.contains(sym) && !live.containsKey(sym)) buildLive(sym, null)?.let { live[sym] = it }
        var n = 0
        for (sym in qsyms) {
            val q = intr[sym]
            if (q != null) { quotes[sym] = q; n++ } else quotes.remove(sym)
            val s = buildLive(sym, q)
            if (s != null) live[sym] = s else live.remove(sym)
        }
        return n
    }

    // ------------------------------------------------------------------ stocks that are not at "<SYM>.NS"

    /** symbol -> when a search for it last found nothing anywhere (persisted with the found tickers) */
    private val notListed = ConcurrentHashMap<String, Long>()
    private val altTried = ConcurrentHashMap<String, Long>()
    @Volatile private var altLoaded = false

    private fun loadAlt() {
        if (altLoaded) return
        altLoaded = true
        try {
            val m = Json.parseObject(p.read(ALT_FILE) ?: return) ?: return
            for ((k, v) in m.obj("alt")) (v as? String)?.let { Yahoo.alt[k] = it }
            for ((k, v) in m.obj("none")) (v as? Number)?.let { notListed[k] = it.toLong() }
        } catch (t: Throwable) { log("alt tickers not loaded: $t") }
    }

    private fun saveAlt() {
        try { p.write(ALT_FILE, Json.stringify(mapOf("alt" to HashMap(Yahoo.alt), "none" to HashMap(notListed)))) } catch (t: Throwable) {}
    }

    /**
     * Holdings, watchlist and searched stocks with no prices at "<SYM>.NS": tries BSE ("<SYM>.BO"), then a Yahoo search by
     * symbol and company name (NSE first). A hit is remembered so every later scan uses it. A stock found nowhere is marked
     * "not listed" and retried every ALT_RETRY_MS (a new listing starts trading). Bounded: at most ALT_MAX per scan.
     */
    private fun findElsewhere(syms: List<String>, today: Int, dailyKey: Int, force: Boolean) {
        try {
            loadAlt()
            val now = p.now()
            val mine = HashSet<String>()
            for (h in st.holdings) mine.add(h.sym)
            mine.addAll(st.watch)
            val todo = syms.filter { sym ->
                val got = cache.get(sym)
                !sym.startsWith("^") && (got == null || got.n == 0) && (mine.contains(sym) || syms.size <= 3) &&
                    now - (altTried[sym] ?: 0L) >= (if (force) ALT_FORCE_MS else ALT_RETRY_MS)
            }.take(ALT_MAX)
            if (todo.isEmpty()) return
            val host = Params.YAHOO_HOST_1
            var changed = false
            for (sym in todo) {
                altTried[sym] = now
                val name = st.holdings.firstOrNull { it.sym == sym }?.name?.takeIf { it.isNotEmpty() && it != sym } ?: nameOf(sym).takeIf { it != sym }
                val cands = LinkedHashSet<String>()
                val prev = Yahoo.alt[sym]
                if (prev != null) cands.add(prev)
                cands.add("$sym.BO")
                val searches = httpAll(listOfNotNull(Yahoo.searchUrl(host, sym), name?.let { Yahoo.searchUrl(host, it) }))
                for ((i, txt) in searches.withIndex()) for ((t, nm) in Yahoo.searchHits(txt)) {
                    val base = t.substringBeforeLast('.')
                    val sameSym = base.equals(sym, true)
                    // a hit from the name search must share the company's first word; from the symbol search, the symbol itself
                    val sameName = i == 1 && name != null && firstWord(nm) != "" && firstWord(nm) == firstWord(name)
                    if (sameSym || sameName) cands.add(t)
                }
                var found: String? = null
                val list = cands.filter { it != "$sym.NS" }.take(4)
                val res = httpAll(list.map { Yahoo.tickerUrl(host, it, "2y", "1d") })
                for ((k, t) in list.withIndex()) {
                    val s = Yahoo.daily(sym, res.getOrNull(k), today) ?: continue
                    cache.put(sym, s, dailyKey); failedAt.remove(sym)
                    found = t
                    break
                }
                if (found != null) {
                    if (Yahoo.alt[sym] != found) { Yahoo.alt[sym] = found; changed = true }
                    if (notListed.remove(sym) != null) changed = true
                    log("prices for $sym found at $found")
                } else if (prev == null) {
                    notListed[sym] = now; changed = true
                }
            }
            if (changed) saveAlt()
        } catch (t: Throwable) {
            log("findElsewhere failed: $t")
        }
    }

    private fun firstWord(s: String): String = s.lowercase().split(Regex("[^a-z0-9]+")).firstOrNull { it.length >= 3 } ?: ""

    /** Shown on a holding with no price once the app has looked everywhere. */
    private fun noDataWhy(sym: String): String =
        if (notListed.containsKey(sym)) ScoreTexts.notTradingLine(sym) else ""

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
        fixSymbols()
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

        optionsTick(now, iq)

        // 2. prices
        val all = LinkedHashSet<String>()
        all.add(idxSym)
        all.add("^NSEBANK")
        all.addAll(universe)
        for (h in st.holdings) all.add(h.sym)
        for (t in st.trades) if (t.status == "OPEN") all.add(t.sym)
        all.addAll(st.watch)
        for (t in st.targets) all.add(t.sym)
        val key = dailyKeyFor(day)
        val buyWindowNow = weekday && min >= Params.BUY_WINDOW_START_MIN && min <= Params.BUY_WINDOW_END_MIN
        val fullQuotes = manual || (buyWindowNow && (anyLive() || Params.EXPERIMENTAL_ENABLED)) || universe.size <= Params.FULL_QUOTE_MAX_UNIVERSE
        var quoteOnly: HashSet<String>? = null
        if (!fullQuotes) {
            val q = HashSet<String>()
            q.add(idxSym)
            for (h in st.holdings) q.add(h.sym)
            for (t in st.trades) if (t.status == "OPEN") q.add(t.sym)
            q.addAll(st.watch)
            for (t in st.targets) q.add(t.sym)
            quoteOnly = q
        }
        fetchSymbols(all.toList(), day, key, manual, 3, 80, quoteOnly)
        var okCount = 0
        for (s in universe) if (if (fullQuotes) quotes.containsKey(s) && live.containsKey(s) else (cache.get(s)?.n ?: 0) > 0) okCount++
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
        try { checkTargets(now) } catch (e: Throwable) { log("targets: $e") }
        val inWindow = weekday && indexToday && st.holidayDay != day &&
            min >= Params.BUY_WINDOW_START_MIN && min <= Params.BUY_WINDOW_END_MIN
        if (inWindow) makeSignals(now, day)
        try { computeWatch() } catch (e: Throwable) { p.log("watch: " + e) }
        try { scanCtx = buildScanCtx() ?: scanCtx } catch (e: Throwable) { log("scan context: $e") }
        try { buildScores(now) } catch (e: Throwable) { log("score table: $e") }
        try { trackStep(day, min) } catch (e: Throwable) { log("track: $e") }

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
        var note = if (r > 0) "Nifty above $f & $s DMA" else if (r < 0) "Nifty below $f & $s DMA — only sell-off bounce buys" else "Nifty between its $f & $s DMA — be selective"
        // market breadth: what the sell-off bounce rule waits for
        var tot = 0
        var above = 0
        for (sym in universe) {
            val x = live[sym] ?: cache.get(sym) ?: continue
            if (x.n < 50) continue
            val a50 = Ind.sma(x.c, x.n - 1, 50)
            if (a50.isNaN()) continue
            tot++
            if (x.c[x.n - 1] > a50) above++
        }
        if (tot >= 50) {
            val b = Math.round(above * 100.0 / tot)
            val trig = Math.round(Params.WO_BREADTH_MAX * 100)
            note += " · " + b + "% of stocks above their 50-day average" + (if (b < trig) " — market-wide sell-off, bounce buys are on" else " (sell-off bounce buys start under $trig%)")
        }
        st.regimeNote = note
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
            val rs = sg.research()
            if (rs != null) {
                // judged on the 15-year test; the phone's own recent test can only veto it
                val recentBad = ss.n >= Params.RESEARCH_VETO_MIN_TRADES && ss.avgPct < 0
                x.n = rs.n; x.winPct = rs.winPct; x.avgPct = rs.avgPct; x.pf = rs.pf; x.maxDdPct = rs.maxDdPct; x.oosN = rs.oosN; x.oosAvgPct = rs.oosAvgPct
                x.testedAt = now
                x.verdict = if (sg.enabled() && Gate.passes(rs) && !recentBad) "LIVE" else "PAUSED"
                val recent = if (ss.n == 0) "No trades of this kind in the phone's recent data yet." else "Recent check on this phone: " + Fmt.plural(ss.n, "trade") + ", " + Fmt.pct(ss.avgPct) + " per trade."
                x.why = if (!sg.enabled()) "Switched off in this version of the app."
                else if (recentBad) "Passed the 15-year test, but lost " + Fmt.p1(-ss.avgPct) + "% per trade over " + Fmt.plural(ss.n, "recent trade") + " — paused until that recovers."
                else if (!Gate.passes(rs)) "On 15 years of NSE data (2011–2026) it made only " + Fmt.pct(rs.avgPct) + " per trade after charges over " + rs.n + " trades (" + Fmt.pct(rs.oosAvgPct) + " since 2022) — too thin to trust. Shown as a test idea only. " + recent
                else "Passed on 15 years of NSE data (2011–2026): " + rs.n + " trades, " + Math.round(rs.winPct) + "% winners, " + Fmt.pct(rs.avgPct) + " per trade after charges; worst year −0.9% per trade. " + recent
            } else {
            x.n = ss.n; x.winPct = ss.winPct; x.avgPct = ss.avgPct; x.pf = ss.pf; x.maxDdPct = ss.maxDdPct; x.oosN = ss.oosN; x.oosAvgPct = ss.oosAvgPct
            x.testedAt = now
            x.verdict = if (sg.enabled() && Gate.passes(ss)) "LIVE" else "PAUSED"
            x.why = Gate.why(sg, ss)
            }
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

    /**
     * EMA setups: liquid stocks whose 10-day EMA has just crossed above the 20-day EMA (day 0 = the latest bar, up to
     * WATCH_MAX_DAYS_SINCE days ago) while the 10-day EMA is above the 50-, 100- and 200-day EMAs.
     * A list for chart reading — not buy calls.
     */
    private fun computeWatch() {
        val out = ArrayList<Pair<Double, Map<String, Any?>>>()
        for (sym in universe) {
            val s = live[sym] ?: continue
            if (EmaScreen.gate(s) != EmaScreen.PASS) continue
            val L = EmaLines(s)
            val since = EmaScreen.match(s, L)
            if (since < 0) continue
            val n = s.n
            val c = s.c[n - 1]
            val e10 = L.e10[n - 1]; val e20 = L.e20[n - 1]; val e50 = L.e50[n - 1]; val e100 = L.e100[n - 1]; val e200 = L.e200[n - 1]
            val prev = s.c[n - 2]
            val spark = ArrayList<Any?>(30)
            for (j in maxOf(0, n - 30) until n) spark.add(Fmt.r2(s.c[j]))
            val m = linkedMapOf<String, Any?>("sym" to sym, "name" to nameOf(sym), "ltp" to Fmt.r2(c),
                "dayChgPct" to Fmt.r2(if (prev > 0) (c / prev - 1) * 100.0 else 0.0), "ema10" to Fmt.r2(e10), "ema20" to Fmt.r2(e20),
                "ema50" to Fmt.r2(e50), "ema100" to Fmt.r2(e100), "ema200" to Fmt.r2(e200), "gapPct" to Fmt.r2((e10 - e20) / e20 * 100.0),
                "daysSince" to since, "barDay" to StockPage.yyyymmdd(s.d[n - 1]), "spark" to spark, "status" to "CROSSED")
            out.add(Pair(since * 1000.0 - (e10 - e20) / e20 * 100.0, m))
        }
        out.sortBy { it.first }
        watchItems = out.take(Params.WATCH_MAX).map { it.second }
    }

    /** Cross-sectional context of the latest common bar of the scanned universe (what makeSignals would use). */
    private fun buildScanCtx(): Ctx? {
        val uni = ArrayList<Series>()
        for (sym in universe) live[sym]?.let { if (it.n > 0) uni.add(it) }
        if (uni.isEmpty()) return null
        val count = HashMap<Int, Int>()
        for (x in uni) count[x.lastDay()] = (count[x.lastDay()] ?: 0) + 1
        val ref = count.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).key
        val idxArr = IntArray(uni.size) { k -> if (uni[k].lastDay() == ref) uni[k].n - 1 else -1 }
        val ctx = Ctx()
        ctx.regime = regimeInt()
        Strategies.fillCutoffs(ctx, uni, idxArr, DoubleArray(uni.size))
        return ctx
    }

    /**
     * Setup-score table (REPORT4) over the scanned universe, on the latest bar each stock has: today's partial bar when
     * this or an earlier scan today quoted it, else the last completed day. Stocks whose latest bar is older than the most
     * common latest day (suspended, failed fetch) are left out, as the research only ranks stocks that traded on day D.
     * Data notes: the phone has no separate unadjusted series. Yahoo's daily close and volume are both split-adjusted, so
     * close x volume is the printed turnover and the latest close is the printed price; the research's Craw / unadjusted
     * volume are replaced by the series' own close and volume. Built once per scan and published as one immutable object.
     */
    private fun buildScores(now: Long) {
        val have = ArrayList<Series>()
        for (sym in universe) live[sym]?.let { if (it.n > 0) have.add(it) }
        if (have.isEmpty()) return
        val count = HashMap<Int, Int>()
        for (x in have) count[x.lastDay()] = (count[x.lastDay()] ?: 0) + 1
        val ref = count.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).key
        val items = ArrayList<Pair<String, ScoreComp>>()
        val bySym = HashMap<String, Series>()
        for (x in have) {
            if (x.lastDay() < ref) continue
            items.add(Pair(x.sym, SetupScore.compute(x)))
            bySym[x.sym] = x
        }
        val t = SetupScore.buildTable(items, now)
        val proven = trackProof?.upgraded == true
        // tested tilt (REPORT7): among BUY-grade setups, those in the top money-flow sectors did better over 60 sessions
        val hot = try {
            val fd = flowData(false, fetch = false)
            if (fd.ok) { val secs = MoneyFlow.sectors(fd, MoneyFlow.moneyIn(fd)).take(MoneyFlow.HOT_SECTORS).map { it.name }.toHashSet(); { s: String -> fd.ind[s]?.sector?.takeIf { it in secs } } } else null
        } catch (e: Throwable) { null }
        val gate = MarketGate.open(have)
        marketGate = gate
        val cands = if (gate == false) emptyList() else SetupScore.topBuys(t, Params.IDEAS_MAX * 4)
        // REPORT9: qualifying names fill slots in order of Clenow trend quality (eligibility unchanged); unscorable last
        val ordered = cands.sortedWith(compareByDescending<ScoreEntry> { Clenow.score(bySym[it.sym]).let { c -> if (c.isNaN()) -1e9 else c } }.thenByDescending { it.score }.thenBy { it.sym })
        val rows = ordered.take(Params.IDEAS_MAX).map { e ->
            val m = LinkedHashMap(BuyCall.idea(e, nameOf(e.sym), bySym[e.sym], proven))
            m["hotSector"] = hot?.invoke(e.sym) ?: ""
            m["horizonDays"] = HOLD_MAX_DAYS          // FINAL: trail 5 ATR below the highest close, sell after at most 120 sessions
            m
        }
        scoreTable = t
        ideaItems = rows
    }

    // =========================================================================================== track record (SPEC4)

    /** Completed daily bars of [sym]: the cache plus any finished newer day from the latest quote (never a partial day). */
    private fun completedSeries(sym: String, day: Int, min: Int): Series? {
        val base = cache.get(sym)
        val lv = live[sym]
        val from = base?.lastDay() ?: Int.MIN_VALUE
        if (lv == null || lv.n == 0 || lv.lastDay() <= from) return base
        val done = { d: Int -> d < day || (d == day && min >= Clock.CLOSE_MIN) }
        var extra = 0
        for (k in 0 until lv.n) if (lv.d[k] > from && done(lv.d[k])) extra++
        if (extra == 0) return base
        val s = Series(sym, (base?.n ?: 0) + extra)
        if (base != null) for (k in 0 until base.n) s.add(base.d[k], base.o[k], base.h[k], base.l[k], base.c[k], base.v[k])
        for (k in 0 until lv.n) if (lv.d[k] > from && done(lv.d[k])) s.add(lv.d[k], lv.o[k], lv.h[k], lv.l[k], lv.c[k], lv.v[k])
        return if (s.n > 0) s else base
    }

    /**
     * After each scan: records the cohort of the latest completed bar day once (idempotent per bar day), re-evaluates open live
     * picks from the bars, and runs the one-time replay when the cache is deep enough.
     */
    private fun trackStep(day: Int, min: Int) {
        val ser = HashMap<String, Series>()
        for (sym in universe) completedSeries(sym, day, min)?.let { if (it.n > 0) ser[sym] = it }
        if (ser.isEmpty()) return
        val cal = Cal.of(ser.values)
        var changed = false
        val count = HashMap<Int, Int>()
        for (x in ser.values) count[x.lastDay()] = (count[x.lastDay()] ?: 0) + 1
        val d = count.entries.maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).key
        if (d > track.lastDay) {
            val t = TrackEngine.tableAt(ser.values, d)
            if (t.usable) {
                track.addCohort(d, TrackEngine.cohorts(t, d))
                changed = true
            }
        }
        val smas = HashMap<String, DoubleArray>()
        for (pk in track.live) {
            if (pk.done()) continue
            val s = ser[pk.sym] ?: completedSeries(pk.sym, day, min)
            val a = if (s == null) null else smas.getOrPut(pk.sym) { TrackEngine.smaArray(s) }
            if (TrackEngine.evaluate(pk, s, a, cal)) changed = true
        }
        if (changed) { trackRev++; saveForward() }
        if (!track.replayDone && replayTriedDay != day) {
            val deep = universe.count { (cache.get(it)?.n ?: 0) >= TrackEngine.REPLAY_MIN_BARS }
            if (universe.isNotEmpty() && deep * 2 > universe.size) { replayTriedDay = day; runReplay(day) }
        }
    }

    /** Replays the cached history (cache bars are completed days only). Returns the number of signal days covered. */
    private fun runReplay(day: Int): Int {
        replaying = true
        try {
            setBusy(ScoreTexts.REPLAY_BUSY, 0)
            val ser = ArrayList<Series>()
            for (sym in universe) cache.get(sym)?.let { if (it.n > 0) ser.add(it) }
            val out = TrackEngine.replay(ser) { pct -> setBusy(ScoreTexts.REPLAY_BUSY, pct) }
            if (out.days > 0) {
                track.setReplay(out)
                trackRev++
                try { p.write(REPLAY_FILE, track.encodeReplay()) } catch (t: Throwable) { log("replay.json write: $t") }
            }
            return out.days
        } finally {
            replaying = false
        }
    }

    private fun saveForward() {
        try { p.write(FORWARD_FILE, track.encodeLive()) } catch (t: Throwable) { log("forward.json write: $t") }
    }

    private fun refreshTrackView() {
        val cost = st.settings.costPct
        val key = "$trackRev/$cost"
        if (key == viewKey && trackView.isNotEmpty()) return
        trackView = TrackEngine.view(track, cost, false)
        trackProof = TrackEngine.proof(track.live, cost, track.startedDay)
        viewKey = key
    }

    /** For tests. */
    internal fun debugTrack(): TrackStore = track
    internal fun debugTrackChanged() { lock.lock(); try { trackRev++; saveForward(); publish() } finally { lock.unlock() } }

    /** For tests: the table the last scan published. */
    internal fun debugScoreTable(): ScoreTable? = scoreTable

    /** The stockInfo `call` for [s]: the scan table's entry for a universe stock, else [s] ranked into the table. */
    private fun callFor(sym: String, s: Series, r: Refs): Map<String, Any?> {
        val t = scoreTable
        val own = if (universeSet.contains(sym)) t?.entries?.get(sym) else null
        val comp = own?.comp ?: SetupScore.compute(s)
        var kind = comp.naKind()
        var e: ScoreEntry? = null
        if (kind == null) {
            if (t == null || !t.usable) kind = ScoreTexts.NA_NO_TABLE
            else e = own ?: SetupScore.insert(t, sym, comp)
        }
        val z = r.sizing
        val px = if (s.n > 0) s.c[s.n - 1] else 0.0
        return BuyCall.call(e, comp, kind, px, px, t?.rated ?: 0, z[0], z[1], z[2], trackProof)
    }

    // =========================================================================================== user targets

    /**
     * Updates every target's last price and fires each ACTIVE one at most once. Only prices newer than the moment the
     * target was set count (a stale close cannot trigger it); a target set without a known price takes the first price
     * seen as its reference.
     */
    private fun checkTargets(now: Long) {
        for (t in st.targets) {
            val s = live[t.sym] ?: continue
            if (s.n == 0) continue
            val px = s.c[s.n - 1]
            t.ltp = px
            if (s.n >= 2) t.prevClose = s.c[s.n - 2]
            if (t.status != UserTarget.ACTIVE) continue
            if (!(t.refPrice > 0)) { t.refPrice = px; continue }
            val q = quotes[t.sym]
            val pxAt = if (q != null && q.days.n > 0 && q.days.lastDay() == s.lastDay()) q.lastTsMs else Clock.msAt(s.lastDay(), Clock.CLOSE_MIN)
            if (pxAt < t.setAt) continue
            val hit = t.hitBy(px) ?: continue
            t.status = hit
            t.hitAt = now
            val isT = hit == UserTarget.HIT_TARGET
            val lvl = if (isT) t.target else t.stop
            val title = t.sym + " hit your " + (if (isT) "target " else "stop ") + Fmt.rs(lvl)
            val body = "Last price " + Fmt.rs(px) + ". You set this " + (if (isT) "target" else "stop") + " on " + Clock.dayLabel(Clock.epochDay(t.setAt)) +
                " when it was " + Fmt.rs(t.refPrice) + "." + (if (t.note.isNotBlank()) " Your note: " + t.note else "")
            post("sell", nid("target", t.sym), title, body)
        }
    }

    /** A price argument: absent/null/"" = 0; otherwise a finite number 0..1e7, else NaN. */
    private fun priceArg(a: Map<String, Any?>, k: String): Double {
        val v = a[k] ?: return 0.0
        if (v is String && v.isBlank()) return 0.0
        val x = a.num(k, Double.NaN)
        return if (x.isNaN() || x < 0 || x > UserTarget.PRICE_MAX) Double.NaN else x
    }

    private fun setTarget(a: Map<String, Any?>, now: Long): Map<String, Any?> {
        loadUniverse()
        val sym = realSym(HoldingsImport.cleanSymbol(a.str("sym")))
        if (!validSym(sym)) return err("Enter a valid NSE symbol, e.g. RELIANCE")
        val tg = priceArg(a, "target")
        val sp = priceArg(a, "stop")
        if (tg.isNaN() || sp.isNaN()) return err("Prices must be numbers above 0")
        if (!(tg > 0 || sp > 0)) return err("Enter a target price or a stop price")
        val idx = st.targets.indexOfFirst { it.sym == sym }
        if (idx < 0 && st.targets.size >= UserTarget.MAX) return err("You already have " + UserTarget.MAX + " targets — clear one first")
        // reference price: the last known price (fetched if nothing is known yet); 0 = decided at the first quote
        val s = try { obtain(sym, false, now).first?.s } catch (t: Throwable) { null }
        val t = UserTarget()
        t.sym = sym; t.name = try { displayName(sym) } catch (x: Throwable) { sym }
        t.target = tg; t.stop = sp
        t.note = a.str("note").trim().take(UserTarget.NOTE_MAX)
        t.setAt = now
        if (s != null && s.n > 0) {
            t.refPrice = s.c[s.n - 1]; t.ltp = t.refPrice
            if (s.n >= 2) t.prevClose = s.c[s.n - 2]
        }
        if (idx >= 0) st.targets.removeAt(idx)
        st.targets.add(t)
        return ok("sym" to sym)
    }

    private fun makeSignals(now: Long, day: Int) {
        val reallyLive = Strategies.all.filter { it.enabled() && st.strat(it.id).verdict == "LIVE" }
        // Experimental ideas: setups from enabled strategies that have NOT passed their test are still shown,
        // clearly labelled and paper-tracked, so the scorecard shows whether they would have worked.
        var liveStrats = reallyLive
        if (Params.EXPERIMENTAL_ENABLED) liveStrats = Strategies.all.filter { it.enabled() && st.strat(it.id).testedAt > 0L }
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
        val liveIds = reallyLive.map { it.id }.toHashSet()
        found.sortWith(compareByDescending<Setup> { if (liveIds.contains(it.strategyId)) 1 else 0 }.thenByDescending { st.strat(it.strategyId).avgPct }.thenByDescending { it.rank }.thenBy { it.sym })
        var expLeft = Params.EXPERIMENTAL_MAX_PER_DAY - st.signals.count { it.day == day && it.strategyLabel.startsWith("EXPERIMENTAL") }
        var slots = st.settings.maxOpenTrades - st.trades.count { it.status == "OPEN" && !it.paper } - st.signals.count { it.status == "NEW" }
        for (raw in found) {
            if (slots <= 0) break
            val experimental = !liveIds.contains(raw.strategyId)
            if (experimental && expLeft <= 0) continue
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
            sig.sym = su.sym; sig.name = nameOf(su.sym); sig.strategyId = sg.id
            sig.strategyLabel = if (experimental) "EXPERIMENTAL · " + sg.label else sg.label
            sig.createdAt = now; sig.day = day; sig.validTill = Clock.msAt(Clock.nextWeekday(day), Clock.OPEN_MIN)
            sig.ltp = su.entry; sig.entry = su.entry; sig.entryMax = Fmt.r2(su.entry * Params.ENTRY_MAX_MULT)
            sig.sl = su.sl; sig.t1 = su.t1; sig.t2 = su.t2; sig.qty = qty
            sig.investRs = su.entry * qty
            sig.riskRs = qty * (su.entry - su.sl) + cost
            val half = if (qty >= 2) qty / 2 else 0
            sig.rewardRs = if (su.t2 > 0) maxOf(0.0, half * (su.t1 - su.entry) + (qty - half) * (su.t2 - su.entry) - cost) else 0.0
            sig.edgeN = ss.n; sig.edgeWinPct = ss.winPct; sig.edgeAvgPct = ss.avgPct; sig.edgePf = ss.pf
            sig.holdDays = sg.holdDays
            sig.reasons = if (experimental) listOf("Experimental: this rule has NOT passed its test. " + ss.why + " Tracked on paper — the scorecard will show how it does.") + su.reasons else su.reasons
            st.signals.add(sig)
            // every signal starts life as a paper trade; it is removed if the user takes the trade for real
            val pt = Trade()
            pt.id = "P" + (++st.seq); pt.paper = true; pt.signalId = sig.id
            pt.sym = su.sym; pt.name = sig.name; pt.strategyId = sg.id; pt.strategyLabel = sg.label
            pt.openedAt = now; pt.entryDay = day; pt.lastBarDay = day
            pt.maxDays = sg.holdDays
            pt.entry = su.entry; pt.qty = qty; pt.sl = su.sl; pt.sl0 = su.sl; pt.t1 = su.t1; pt.t2 = su.t2; pt.ltp = su.entry
            st.trades.add(pt)
            val levels = if (su.t2 > 0) "SL " + Fmt.rs(su.sl) + " · T1 " + Fmt.rs(su.t1) + " · T2 " + Fmt.rs(su.t2)
            else "SL " + Fmt.rs(su.sl) + " · sell at close of day " + sg.holdDays
            post("buy", nid("buy", sig.id), (if (experimental) "TEST IDEA (unproven) " else "BUY ") + su.sym + " " + Fmt.rs(su.entry) + "–" + Fmt.num(sig.entryMax),
                levels + " · " + Fmt.plural(qty, "share") + " (" + Fmt.rs0(sig.investRs) + ") · " + sg.label + ": " + ss.n + " test trades, " +
                    Math.round(ss.winPct) + "% won, avg " + Fmt.pct(ss.avgPct))
            slots--
            if (experimental) expLeft--
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
                    val xs = Strategies.byId(t.strategyId)?.exitAboveSma ?: 0
                    ExitModel.step(pos, op, s.h[k], lo, s.c[k], dn >= t.maxDays, xs > 0 && s.c[k] > Ind.sma(s.c, k, xs))
                    if (pos.t1Done && !t.t1Done) { t.t1Done = true; t.t1Px = pos.t1Px; t.t1Day = if (t.paper) d else today; t.sl = pos.sl }
                    if (pos.closed) {
                        if (t.paper) {
                            closeTrade(t, pos.exitPx, Clock.msAt(d, Clock.CLOSE_MIN), ExitModel.resultText(pos.reason, pos.t1Done), cost)
                        } else {
                            t.exitDue = if (pos.reason and ExitModel.STOP != 0) ExitModel.STOP else if (pos.reason and ExitModel.T2 != 0) ExitModel.T2 else if (pos.reason and ExitModel.MA != 0) ExitModel.MA else ExitModel.TIME
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
                    else if (!entryToday && min >= Params.TIME_EXIT_FROM_MIN) {
                        val xs = Strategies.byId(t.strategyId)?.exitAboveSma ?: 0
                        if (xs > 0 && ltp > Ind.sma(s.c, i, xs)) t.exitDue = ExitModel.MA
                    }
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
            t.exitDue == ExitModel.MA -> {
                t.action = "EXIT"; key = "EXIT_MA"
                t.actionNote = "It has bounced back above its 5-day average (last price " + Fmt.rs(t.ltp) + "). That was the plan — sell before the 3:30 pm close."
                title = t.sym + " bounced — sell today"
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
            h.verdict = a.verdict; h.headline = a.headline; h.stop = a.stop; h.bookAt = a.bookAt; h.reasons = a.reasons; h.dataOk = a.dataOk; h.strength = a.strength
            h.noDataWhy = if (a.dataOk) "" else noDataWhy(h.sym)
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
            "replayTrack" -> {
                loadUniverse()
                cache.ensureLoaded()
                val n = runReplay(day)
                return if (n > 0) ok("days" to n) else err(ScoreTexts.REPLAY_NO_HISTORY)
            }
            "resetTrack" -> {
                track.resetLive()
                trackRev++
                saveForward()
                return ok()
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
                val sym = realSym(HoldingsImport.cleanSymbol(a.str("sym")))
                val qty = a.num("qty", Double.NaN)
                val avg = a.num("avg", Double.NaN)
                if (sym.isEmpty() || sym.length > 25 || !Regex("^[A-Z0-9&][A-Z0-9&\\-]*$").matches(sym)) return err("Enter a valid NSE symbol, e.g. RELIANCE")
                if (qty.isNaN() || qty <= 0 || qty > 1e9) return err("Quantity must be more than 0")
                if (avg.isNaN() || avg <= 0 || avg > 1e7) return err("Average buy price must be more than 0")
                val act = try { searchIndex().actionFor(HoldingsImport.cleanSymbol(a.str("sym"))) } catch (t: Throwable) { null }
                if (act != null) {
                    val parts = mergerParts(act, qty, avg)
                    for ((s2, q2, a2) in parts) addToHolding(s2, q2, a2)
                    val note = ScoreTexts.mergedLine(act, qty)
                    feed("info", "Merged stock converted", note)
                    refreshHoldings(parts.map { it.first }, now, day)
                    return ok("sym" to act.to, "note" to note)
                }
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
                val ix = try { searchIndex() } catch (t: Throwable) { null }
                // a merged stock keeps its old symbol here so its quantity can be converted below
                val res = HoldingsImport.parse(a.str("text"), nameToSym, universe.toHashSet()) { cell -> ix?.actionFor(cell)?.old ?: ix?.resolve(cell) }
                var added = 0
                var updated = 0
                val loaded = ArrayList<String>()
                val merged = ArrayList<String>()
                for (r in res.rows) {
                    val act = ix?.actionFor(r.sym)
                    if (act == null) {
                        val sym = realSym(r.sym)
                        if (upsertHolding(sym, r.qty, r.avg)) added++ else updated++
                        loaded.add(sym)
                        continue
                    }
                    for ((sym, q, av) in mergerParts(act, r.qty, r.avg)) { if (addToHolding(sym, q, av)) added++ else updated++; loaded.add(sym) }
                    merged.add(ScoreTexts.mergedLine(act, r.qty))
                }
                if (merged.isNotEmpty()) feed("info", "Merged stock converted", merged.joinToString(" "))
                if (loaded.isNotEmpty()) refreshHoldings(loaded.distinct(), now, day)
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
                t.maxDays = sig.holdDays
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
            "addWatch" -> {
                loadUniverse()
                val sym = realSym(HoldingsImport.cleanSymbol(a.str("sym")))
                if (!validSym(sym)) return err("Enter a valid NSE symbol, e.g. RELIANCE")
                if (st.watch.contains(sym)) return ok("sym" to sym)
                if (st.watch.size >= AppState.MAX_WATCH) return err("Your watchlist is full (" + AppState.MAX_WATCH + " stocks) — remove one first")
                st.watch.add(sym)
                if (!live.containsKey(sym)) loadWatchSymbol(sym, now, day)
                return ok("sym" to sym)
            }
            "removeWatch" -> {
                val sym = HoldingsImport.cleanSymbol(a.str("sym"))
                return if (st.watch.remove(sym)) ok("sym" to sym) else err("$sym is not in your watchlist")
            }
            "setTarget" -> return setTarget(a, now)
            "clearTarget" -> {
                val sym = HoldingsImport.cleanSymbol(a.str("sym"))
                if (!validSym(sym)) return err("Enter a valid NSE symbol, e.g. RELIANCE")
                st.targets.removeAll { it.sym == sym }
                return ok("sym" to sym)
            }
            else -> return err("Unknown action: $name")
        }
    }

    /** @return true if a new holding was added, false if an existing one was replaced. */
    /** The real NSE symbol for what the user typed (e.g. OLA -> OLAELEC); unchanged when it already exists or nothing fits uniquely. */
    private fun realSym(sym: String): String = try { searchIndex().resolve(sym) ?: sym } catch (t: Throwable) { sym }

    /**
     * Renames holdings, watchlist entries and targets whose symbol is not an NSE symbol but maps to exactly one
     * (e.g. a holding typed as OLA becomes OLAELEC). Duplicates are merged. Runs at the start of every scan.
     */
    private fun fixSymbols() {
        try {
            val ix = searchIndex()
            if (ix.size < 1000) return
            val renamed = ArrayList<String>()
            val merged = ArrayList<String>()
            for (h in st.holdings.toList()) {
                if (ix.has(h.sym)) continue
                val act = ix.actionFor(h.sym)
                if (act != null) {
                    st.holdings.remove(h)
                    for ((sym, q, av) in mergerParts(act, h.qty, h.avg)) addToHolding(sym, q, av)
                    merged.add(ScoreTexts.mergedLine(act, h.qty))
                    continue
                }
                val to = ix.resolve(h.sym) ?: continue
                if (to == h.sym) continue
                val ex = st.holdings.firstOrNull { it.sym == to }
                if (ex != null) {
                    val q = ex.qty + h.qty
                    if (q > 0 && ex.avg > 0 && h.avg > 0) ex.avg = (ex.avg * ex.qty + h.avg * h.qty) / q
                    ex.qty = q
                    st.holdings.remove(h)
                } else {
                    h.sym = to; h.name = nameOf(to); h.dataOk = false; h.ltp = 0.0
                }
                renamed.add(h.sym + " -> " + to)
            }
            for (i in st.watch.indices.reversed()) {
                val w = st.watch[i]
                if (ix.has(w)) continue
                val to = ix.resolve(w) ?: continue
                if (to == w) continue
                if (st.watch.contains(to)) st.watch.removeAt(i) else st.watch[i] = to
            }
            for (t in st.targets) {
                if (ix.has(t.sym)) continue
                val to = ix.resolve(t.sym) ?: continue
                if (to != t.sym && st.targets.none { it.sym == to }) t.sym = to
            }
            if (merged.isNotEmpty()) feed("info", "Merged stock converted", merged.joinToString(" "))
            if (renamed.isNotEmpty()) feed("info", "Fixed stock symbols", ScoreTexts.renamedLine(renamed.map { it.substringBefore(" ->") }, renamed.map { it.substringAfter("-> ") }))
        } catch (t: Throwable) {
            p.log("fixSymbols: " + t)
        }
    }

    /** What an old holding became after a merger: the new stock (same money, converted count) and any demerged stock. */
    private fun mergerParts(act: CorpAction, qty: Double, avg: Double): List<Triple<String, Double, Double>> {
        val out = ArrayList<Triple<String, Double, Double>>()
        val q = act.newQty(qty)
        if (q > 0) out.add(Triple(act.to, q, act.newAvg(avg)))
        val e = act.extraQty(qty)
        if (act.extra != null && e > 0) out.add(Triple(act.extra, e, 0.0))
        return out
    }

    /** Adds shares to a holding (weighted buy price when both are known; an unknown 0 price makes the result unknown). */
    private fun addToHolding(sym: String, qty: Double, avg: Double): Boolean {
        val ex = st.holdings.firstOrNull { it.sym == sym } ?: return upsertHolding(sym, qty, avg)
        val q = ex.qty + qty
        ex.avg = if (q > 0 && ex.avg > 0 && avg > 0) (ex.avg * ex.qty + avg * qty) / q else 0.0
        ex.qty = q
        ex.dataOk = false
        return false
    }

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

    // =========================================================================================== watchlist / lock-free reads

    private fun validSym(sym: String): Boolean = sym.isNotEmpty() && sym.length <= 25 && SYM_RE.matches(sym)

    /** Prices for a just-added watchlist symbol (re-uses a stock-page lookup when there is one). Runs under the lock. */
    private fun loadWatchSymbol(sym: String, now: Long, day: Int) {
        try {
            val key = dailyKeyFor(day)
            val le = synchronized(lru) { lru[sym] }
            if (le?.daily != null && le.key == key) {
                cache.put(sym, le.daily, key)
                cache.flush()
                val q = if (le.quote != null && Clock.epochDay(le.quoteAt) == day) le.quote else null
                if (q != null) quotes[sym] = q
                buildLive(sym, q)?.let { live[sym] = it }
                return
            }
            setBusy("Loading prices…", 5)
            cache.ensureLoaded()
            fetchSymbols(listOf(sym), day, key, true, 5, 90)
            cache.flush()
        } catch (t: Throwable) {
            log("loadWatchSymbol failed: $t")
        }
    }

    private fun errSym(sym: String, msg: String): Map<String, Any?> = linkedMapOf("ok" to false, "sym" to sym, "error" to msg)

    private fun asset(name: String): String? = try { p.asset(name) } catch (t: Throwable) { null }

    /** Built once, lazily (then replaced as a whole by a list refresh); safe to call from any thread. */
    private fun searchIndex(): SymbolIndex {
        symIndex?.let { return it }
        synchronized(idxLock) {
            symIndex?.let { return it }
            loadUniverse()
            val list = stockList ?: initialList().also { stockList = it }
            val ix = try { SymbolIndex.build(list.rows, universe, names, asset("aliases.csv"), changesCsv(), asset("corpactions.csv")) }
            catch (t: Throwable) { log("search index failed: $t"); SymbolIndex.build(emptyList(), universe, names, null) }
            symIndex = ix
            return ix
        }
    }

    /** NSE's symbol-change list: the latest download, else the bundled copy. */
    private fun changesCsv(): String? {
        val saved = try { p.read(CHANGES_FILE) } catch (t: Throwable) { null }
        return if (saved != null && SymbolChanges.parseNse(saved).size > 500) saved else asset("symbolchange.csv")
    }

    /** The saved download if it is still valid, else the bundled asset. */
    private fun initialList(): StockListData {
        try {
            StockList.decodeSaved(try { p.read(StockList.SAVED_FILE) } catch (t: Throwable) { null })?.let { return it }
        } catch (t: Throwable) {}
        val rows = try { StockList.parse(asset("symbols.csv")).rows } catch (t: Throwable) { emptyList<ListRow>() }
        return StockListData(rows, StockList.SRC_BUNDLED, 0L)
    }

    private fun currentList(): StockListData = listSnapshot().second

    /** The index and the list it was built from, read together (the swap holds [idxLock] only for the two assignments). */
    private fun listSnapshot(): Pair<SymbolIndex, StockListData> {
        val ix0 = searchIndex()
        synchronized(idxLock) { return Pair(symIndex ?: ix0, stockList ?: StockListData(emptyList(), StockList.SRC_BUNDLED, 0L)) }
    }

    /**
     * Downloads the stock list (NSE, else the GitHub mirror) and swaps in a new index. Without [force] it runs once per
     * calendar day; with it (the action) it runs unless today's download already succeeded. Searches keep using the old
     * index until the new one is complete. Returns true when a new list was installed.
     */
    private fun refreshSymbols(force: Boolean): Boolean {
        listLock.lock()
        try {
            val now = now()
            val day = Clock.epochDay(now)
            val cur = currentList()
            val doneToday = cur.updatedAt > 0 && Clock.epochDay(cur.updatedAt) == day && cur.source != StockList.SRC_BUNDLED
            if (doneToday) { listTriedDay = day; return false }
            if (!force && listTriedDay == day) return false
            listTriedDay = day
            val got = StockList.download({ urls -> httpAll(urls) }, cur.rows, now)
            if (got == null) {
                listFailed = true
                log("stock list: no valid download, keeping the ${cur.source} list (${cur.rows.size} rows)")
                return false
            }
            try { p.write(StockList.SAVED_FILE, StockList.encode(got)) } catch (t: Throwable) { log("stock list not saved: $t") }
            loadUniverse()
            try {
                val chg = httpAll(listOf(CHANGES_URL)).firstOrNull()
                if (chg != null && SymbolChanges.parseNse(chg).size > 500) p.write(CHANGES_FILE, chg)
            } catch (t: Throwable) { log("symbol changes not refreshed: $t") }
            val ix = SymbolIndex.build(got.rows, universe, names, asset("aliases.csv"), changesCsv(), asset("corpactions.csv"))
            synchronized(idxLock) { stockList = got; symIndex = ix }
            listFailed = false
            log("stock list: ${got.rows.size} rows from ${got.source}")
            return true
        } catch (t: Throwable) {
            log("stock list refresh failed: $t")
            return false
        } finally {
            listLock.unlock()
        }
    }

    private fun refreshSymbolsAction(): String {
        return try {
            refreshSymbols(true)
            val meta = listMeta(now())
            Json.stringify(ok("updated" to !listFailed, "message" to (if (listFailed) ScoreTexts.LIST_FAILED else ""), "list" to meta))
        } catch (t: Throwable) {
            "{\"ok\":false,\"error\":\"failed\"}"
        }
    }

    /** Search result `list` object. stale: a download older than 3 days, or the bundled list after a failed download. */
    private fun listMeta(now: Long, snap: Pair<SymbolIndex, StockListData> = listSnapshot()): Map<String, Any?> {
        val ix = snap.first
        val l = snap.second
        val stale = if (l.updatedAt > 0) now - l.updatedAt > StockList.STALE_MS else listFailed
        return linkedMapOf("count" to l.rows.size, "updatedAt" to l.updatedAt, "source" to l.source, "stale" to stale, "newCount" to ix.newCount(Clock.epochDay(now)))
    }

    /** Best company name: universe name unless it just repeats the symbol, else the full symbol list. */
    private fun displayName(sym: String): String {
        val n = names[sym]
        if (n != null && n.isNotBlank() && n != sym) return n
        return searchIndex().nameOf(sym) ?: n ?: sym
    }

    private fun readAction(name: String, argsJson: String): String {
        var a: Map<String, Any?> = emptyMap()
        try {
            a = Json.parseObject(argsJson) ?: emptyMap()
            val res = when (name) {
                "search" -> {
                    val now = now()
                    val snap = listSnapshot()
                    ok("q" to a.str("q"), "results" to snap.first.find(a.str("q"), SymbolIndex.MAX_RESULTS, Clock.epochDay(now)), "list" to listMeta(now, snap))
                }
                "newListings" -> {
                    val days = a.int("days", SymbolIndex.NEW_DAYS).let { if (it <= 0) SymbolIndex.NEW_DAYS else minOf(it, 36500) }
                    ok("results" to searchIndex().newListings(Clock.epochDay(now()), days))
                }
                "stockInfo" -> stockInfo(a)
                "moneyFlow" -> {
                    val fd = flowData(a.bool("force"))
                    val v = LinkedHashMap(MoneyFlow.view(fd, scoreTable?.entries ?: emptyMap()) { displayName(it) })
                    v["fetchedAt"] = flowFetchedAt
                    v
                }
                "funds" -> LinkedHashMap(Funds.view(fundData(a.bool("force"))))
                "fundInfo" -> Funds.info(fundData(false), a.str("code"))
                "fundSearch" -> linkedMapOf("ok" to true, "q" to a.str("q"), "results" to Funds.search(fundData(false), a.str("q")))
                "fnoLab" -> labView()
                "fnoTicker" -> fnoTicker()
                "fno", "fnoOrder", "fnoCancel", "fnoLevels", "fnoReset" -> fnoAction(name, a)
                "options" -> synchronized(optLock) { Options.view(optLoad(), optSpots, optVix) }
                "indexView" -> {
                    flowData(false)
                    val idx = Indices.parse(try { p.read("mf_indices.csv") } catch (t: Throwable) { null })
                    Indices.view(idx, a.str("index"), flowCache?.flows ?: emptyMap(), scoreTable?.entries ?: emptyMap(), marketGate != false) { displayName(it) }
                }
                "flowSector" -> MoneyFlow.sector(flowData(false), a.str("name"), scoreTable?.entries ?: emptyMap()) { displayName(it) }
                else -> chart(a)
            }
            return Json.stringify(res)
        } catch (t: Throwable) {
            log("action $name failed: $t")
            return try {
                val e = "Something went wrong: " + (t.message ?: t.javaClass.simpleName)
                Json.stringify(if (name == "search" || name == "newListings") err(e) else errSym(HoldingsImport.cleanSymbol(a.str("sym")), e))
            } catch (x: Throwable) { "{\"ok\":false,\"error\":\"failed\"}" }
        }
    }

    private class Obtained(val s: Series, val quote: Intraday?, val live: Boolean, val asOf: Long)
    private class Fetched(val daily: HashMap<String, Series>, val intr: HashMap<String, Intraday>, val anyResponse: Boolean)

    /** One round on host 1, failures retried once on host 2, like a scan, but without touching `busy` (safe outside the lock). */
    private fun fetchQuiet(dailySyms: List<String>, intrSyms: List<String>, today: Int): Fetched {
        val daily = HashMap<String, Series>()
        val intr = HashMap<String, Intraday>()
        var any = false
        var pending: List<Pair<String, Boolean>> = dailySyms.map { Pair(it, true) } + intrSyms.map { Pair(it, false) }
        for (host in listOf(Params.YAHOO_HOST_1, Params.YAHOO_HOST_2)) {
            if (pending.isEmpty()) break
            val res = httpAll(pending.map { if (it.second) Yahoo.dailyUrl(host, it.first) else Yahoo.intradayUrl(host, it.first) })
            val failed = ArrayList<Pair<String, Boolean>>()
            for ((k, rq) in pending.withIndex()) {
                val text = res.getOrNull(k)
                if (text != null) any = true
                val got = try {
                    if (rq.second) Yahoo.daily(rq.first, text, today)?.let { daily[rq.first] = it; true } ?: false
                    else Yahoo.intraday(rq.first, text)?.let { intr[rq.first] = it; true } ?: false
                } catch (t: Throwable) { false }
                if (!got) failed.add(rq)
            }
            pending = failed
        }
        return Fetched(daily, intr, any)
    }

    private fun join(base: Series?, q: Intraday?, sym: String): Series? {
        if (base == null && q == null) return null
        val s = Series(sym, (base?.n ?: 0) + (q?.days?.n ?: 0) + 1)
        if (base != null) for (k in 0 until base.n) s.add(base.d[k], base.o[k], base.h[k], base.l[k], base.c[k], base.v[k])
        if (q != null) { val x = q.days; for (k in 0 until x.n) s.add(x.d[k], x.o[k], x.h[k], x.l[k], x.c[k], x.v[k]) }
        return if (s.n > 0) s else null
    }

    private fun obtained(s: Series, base: Series?, q: Intraday?): Obtained {
        val isLive = q != null && q.days.n > 0 && s.lastDay() == q.days.lastDay() && (base == null || q.days.lastDay() > base.lastDay())
        return Obtained(s, q, isLive, if (isLive) q!!.lastTsMs else Clock.msAt(s.lastDay(), Clock.CLOSE_MIN))
    }

    /** True for symbols whose history belongs in the persistent cache (scanned, held, watched, traded or the index). */
    private fun member(sym: String): Boolean {
        val r = refs
        return sym == Params.INDEX_SYMBOL || universeSet.contains(sym) || r.holdings.containsKey(sym) || r.watch.contains(sym) || r.trades.containsKey(sym) ||
            r.targets.containsKey(sym)
    }

    /**
     * Daily history + latest bar for any symbol, without the scan lock. [wantQuote]: always try a fresh intraday quote
     * (stock page); otherwise the last scan's data is good enough (chart). Returns the data or an error text.
     */
    private fun obtain(sym: String, wantQuote: Boolean, now: Long): Pair<Obtained?, String> {
        val today = Clock.epochDay(now)
        val key = dailyKeyFor(today)
        val le = synchronized(lru) { lru[sym] }
        if (!wantQuote) {
            live[sym]?.let { if (it.n > 0) return Pair(obtained(it, cache.get(sym), quotes[sym]), "") }
            cache.get(sym)?.let { return Pair(obtained(it, it, null), "") }
            if (le != null && le.key == key) join(le.daily, le.quote, sym)?.let { return Pair(obtained(it, le.daily, le.quote), "") }
        }
        var base: Series? = null
        if (cache.keyOf(sym) == key) base = cache.get(sym)
        if (base == null && le?.daily != null && le.key == key) base = le.daily
        val haveFresh = base != null
        var q: Intraday? = if (le?.quote != null && now - le.quoteAt in 0..QUOTE_REUSE_MS) le.quote else null
        val needIndex = live[Params.INDEX_SYMBOL] == null && cache.get(Params.INDEX_SYMBOL) == null
        val dSyms = ArrayList<String>()
        val iSyms = ArrayList<String>()
        if (!haveFresh) dSyms.add(sym)
        if (q == null) iSyms.add(sym)
        if (needIndex) { dSyms.add(Params.INDEX_SYMBOL); iSyms.add(Params.INDEX_SYMBOL) }
        var anyResponse = true
        if (dSyms.isNotEmpty() || iSyms.isNotEmpty()) {
            val f = fetchQuiet(dSyms, iSyms, today)
            anyResponse = f.anyResponse
            f.daily[Params.INDEX_SYMBOL]?.let { cache.put(Params.INDEX_SYMBOL, it, key) }
            val d = f.daily[sym]
            val fq = f.intr[sym]
            if (fq != null) q = fq
            if (d != null) base = d
            if (member(sym)) {
                if (d != null) cache.put(sym, d, key)
            } else if (d != null || fq != null) {
                val keepDaily = d ?: le?.daily
                synchronized(lru) { lru[sym] = Looked(keepDaily, if (d != null) key else (le?.key ?: 0), q, if (fq != null) now else (le?.quoteAt ?: 0L)) }
            }
            if (d != null || f.daily.containsKey(Params.INDEX_SYMBOL)) cache.flush()
        }
        if (base == null) base = cache.get(sym) ?: le?.daily            // stale history beats none
        if (q == null) q = quotes[sym] ?: le?.quote                      // the last scan's (or lookup's) quote
        val s = join(base, q, sym)
        if (s != null) return Pair(obtained(s, base, q), "")
        live[sym]?.let { if (it.n > 0) return Pair(obtained(it, null, quotes[sym]), "") }
        return Pair(null, if (!anyResponse) "Could not reach the price feed for $sym — check the internet connection and try again."
        else "No price data found for $sym. Check the symbol.")
    }

    private fun indexSeries(): Series? = live[Params.INDEX_SYMBOL] ?: cache.get(Params.INDEX_SYMBOL)

    private fun stockInfo(a: Map<String, Any?>): Map<String, Any?> {
        val raw = a.str("sym")
        val sym = HoldingsImport.cleanSymbol(raw)
        if (!validSym(sym)) return errSym(sym.ifEmpty { raw.trim() }, "Enter a valid NSE symbol, e.g. RELIANCE")
        loadUniverse()
        val now = now()
        val (got, why) = obtain(sym, true, now)
        if (got == null) return errSym(sym, why)
        val r = refs
        val h = r.holdings[sym]
        val weight = if (h != null && r.holdings.size >= 2 && r.holdingsValue > 0) h[3] / r.holdingsValue * 100.0 else 0.0
        val m = LinkedHashMap(StockPage.build(StockInput(sym, displayName(sym), universeSet.contains(sym), h != null, r.watch.contains(sym),
            got.s, indexSeries(), got.live, got.asOf, now, scanCtx, r.regime, weight, r.verdicts)))
        m["call"] = try { callFor(sym, got.s, r) } catch (t: Throwable) { log("call $sym: $t"); BuyCall.call(null, SetupScore.empty(0, 0), ScoreTexts.NA_NO_TABLE, 0.0, 0.0, 0, 0.0, 0.0, 0.0) }
        m["myTarget"] = r.targets[sym] ?: NO_TARGET
        // SPEC5: the analyst panel explains; it reads the finished call and never changes it
        val today = Clock.epochDay(now)
        val listed = try { searchIndex().listedOf(sym) } catch (t: Throwable) { 0 }
        @Suppress("UNCHECKED_CAST") val call = m["call"] as Map<String, Any?>
        m["panel"] = try {
            val t = scoreTable
            val own = if (universeSet.contains(sym)) t?.entries?.get(sym) else null
            val e = if (own != null) own else if (t != null && t.usable) SetupScore.insert(t, sym, SetupScore.compute(got.s)) else null
            Panel.build(PanelInput(got.s, e, t, call, indexSeries(), scanCtx?.breadth ?: Double.NaN, r.sizing[0], r.sizing[1], r.sizing[2], listed, today))
        } catch (t: Throwable) {
            log("panel $sym: $t")
            Panel.empty((call["label"] as? String) ?: "NA", call["proven"] == true, if (got.s.n > 0) StockPage.yyyymmdd(got.s.d[got.s.n - 1]) else 0)
        }
        m["listing"] = Panel.listing(got.s, listed, today)
        m["money"] = try { MoneyFlow.forStock(flowData(false, fetch = false), sym) } catch (t: Throwable) { log("money $sym: $t"); null }
        return m
    }

    // ------------------------------------------------------------------ money flow (data/ files built by the daily job)

    @Volatile private var flowCache: FlowData? = null
    @Volatile private var flowFetchedAt = 0L
    private val flowLock = Any()

    /**
     * The money-flow tables: the saved copy, refreshed from GitHub when older than FLOW_STALE_MS (or forced, at most every
     * FLOW_FORCE_MS). A failed download keeps the old copy. Never throws.
     */
    private fun flowData(force: Boolean, fetch: Boolean = true): FlowData {
        synchronized(flowLock) {
            try {
                if (flowCache == null) {
                    flowFetchedAt = try { p.read(FLOW_META)?.trim()?.toLongOrNull() ?: 0L } catch (t: Throwable) { 0L }
                    flowCache = MoneyFlow.parse(p.read("mf_flows.csv"), p.read("mf_industries.csv"), p.read("mf_holders.csv"), p.read("mf_deals.csv"))
                }
                val now = p.now()
                val age = now - flowFetchedAt
                if (fetch && (age >= FLOW_STALE_MS || (force && age >= FLOW_FORCE_MS) || flowCache?.ok != true)) {
                    val got = httpAll(FLOW_FILES.map { FLOW_BASE + it })
                    var changed = false
                    val fl = got.getOrNull(0)
                    if (fl != null && MoneyFlow.parse(fl, null, null, null).ok) {
                        p.write("mf_flows.csv", fl); changed = true
                        for (k in 1 until FLOW_FILES.size) {
                            val t = got.getOrNull(k) ?: continue
                            if (t.startsWith("SYMBOL,") || t.startsWith("DATE,") || t.startsWith("INDEX,")) p.write("mf_" + FLOW_FILES[k], t)
                        }
                    }
                    flowFetchedAt = now
                    try { p.write(FLOW_META, now.toString()) } catch (t: Throwable) {}
                    if (changed) flowCache = MoneyFlow.parse(p.read("mf_flows.csv"), p.read("mf_industries.csv"), p.read("mf_holders.csv"), p.read("mf_deals.csv"))
                }
            } catch (t: Throwable) {
                log("money flow: $t")
            }
            return flowCache ?: MoneyFlow.parse(null, null, null, null)
        }
    }

    // ------------------------------------------------------------------ options (paper only)

    private val optLock = Any()
    private var optBook: MutableList<OptTrade>? = null
    @Volatile private var optSpots: Map<String, Double> = emptyMap()
    @Volatile private var optVix = 0.0

    private fun optLoad(): MutableList<OptTrade> {
        optBook?.let { return it }
        val l = ArrayList<OptTrade>()
        try {
            val m = Json.parseObject(p.read(OPT_FILE) ?: "")
            if (m != null) { for (x in m.list("trades")) (x as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") l.add(OptTrade.from(it as Map<String, Any?>)) }; optVix = m.num("vix", 0.0) }
        } catch (t: Throwable) { log("options load: $t") }
        optBook = l
        return l
    }

    /**
     * Live options watch (called every ~60 s by the foreground service during 09:40–15:20): fetches Nifty, Bank Nifty
     * and India VIX only, then runs the options rule. Separate from the 15-minute stock scan. Returns a short status.
     */
    fun optionsQuick(): String {
        return try {
            cache.ensureLoaded()
            val got = fetchParsed(listOf(Params.INDEX_SYMBOL), { h, s -> Yahoo.intradayUrl(h, s) }, { s, t -> Yahoo.intraday(s, t) }, "", 1, 1)
            val iq = got[Params.INDEX_SYMBOL] ?: return "no data"
            val ev = optionsStep(p.now(), iq)
            // the phone alert goes out at once; the in-app feed entry is written under the state lock (or queued)
            // REPORT9: the rule lost money in the 2014–2026 backtest, so it is tracked on paper only: no phone alerts
            for (e in ev) optPending.add(e)
            if (optPending.isNotEmpty() && lock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) {
                try { flushOptPending(); save() } finally { lock.unlock() }
            }
            "ok"
        } catch (t: Throwable) { log("optionsQuick: $t"); "error" }
    }

    // ------------------------------------------------------------------ F&O paper trading (live NSE option chain)

    private val fnoLock = Any()
    private var fnoBook: FnoBook? = null
    private val fnoChains = java.util.concurrent.ConcurrentHashMap<String, FnoChain>()
    private val fnoExp = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    private fun fnoLoad(): FnoBook {
        fnoBook?.let { return it }
        val b = try { FnoBook.from(Json.parseObject(p.read(FNO_FILE) ?: "")) } catch (t: Throwable) { FnoBook() }
        fnoBook = b
        return b
    }
    private fun fnoSave(b: FnoBook) { try { p.write(FNO_FILE, Json.stringify(b.toMap())) } catch (t: Throwable) {} }

    @Volatile var nseOkAt = 0L
    @Volatile var nseFailAt = 0L
    private fun fnoGet(urlOf: (String) -> String): String? {
        // NSE's CDN serves cached copies up to a minute old; a unique query parameter gets the live data (10–15 s fresh).
        for (h in Fno.HOSTS) {
            val u = urlOf(h); val bust = u + (if (u.contains('?')) "&" else "?") + "_=" + p.now()
            val t = try { p.httpGetAll(listOf(bust)).firstOrNull() } catch (e: Throwable) { null }
            if (t != null && t.startsWith("{")) { nseOkAt = p.now(); return t }
        }
        nseFailAt = p.now()
        return null
    }

    /** Expiries for [sym] (cached 30 min), dropping today's after 15:30 and past ones. */
    private fun fnoExpiries(sym: String): List<String> {
        val now = p.now()
        val c = fnoExp[sym]
        val list = if (c != null && now - c.first < 30 * 60_000L && c.second.isNotEmpty()) c.second
            else Fno.parseExpiries(fnoGet { Fno.infoUrl(it, sym) }).also { if (it.isNotEmpty()) fnoExp[sym] = Pair(now, it) }
        val day = Clock.epochDay(now); val min = Clock.minuteOfDay(now)
        return list.filter { val d = Fno.expDay(it); d > day || (d == day && min < 15 * 60 + 30) }
    }

    /** Live chain, refetched when older than [maxAgeMs]. Null when NSE does not answer. */
    private val nseBase = java.util.concurrent.ConcurrentHashMap<String, FnoChain>()
    private val growwIds = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<Int, Pair<String, String>>>>()
    @Volatile private var gIdx: Pair<Long, List<Map<String, Any?>>>? = null
    @Volatile var liveOkAt = 0L

    /** Live index levels (Groww batch, ~1 s fresh; cached 700 ms). */
    private fun growwIndices(): List<Map<String, Any?>> {
        val now = p.now(); gIdx?.let { if (now - it.first < 700L) return it.second }
        val got = Groww.parseIndices(try { p.httpPost(Groww.INDEX_URL, Groww.indexBody(Groww.TICKER.map { it.first })) } catch (t: Throwable) { null })
        if (got.isNotEmpty()) { gIdx = Pair(now, got); liveOkAt = now }
        return got
    }

    /**
     * Option chain for [sym]/[expiry]: NSE gives strikes, spreads, IV and OI (refreshed every 45 s); live last prices for the
     * strikes around the money come from a tick-by-tick batch call each time (so the screen and paper fills move every second).
     */
    private fun fnoChain(sym: String, expiry: String, maxAgeMs: Long): FnoChain? {
        val key = "$sym|$expiry"
        val now = p.now()
        val c = fnoChains[key]
        if (c != null && now - c.fetchedAt < Math.min(maxAgeMs, 900L)) return c
        var base = nseBase[key]
        if (base == null || now - base.fetchedAt > Math.max(45_000L, Math.min(maxAgeMs, 90_000L))) {
            Fno.parseChain(sym, expiry, fnoGet { Fno.chainUrl(it, sym, expiry) }, now)?.let { base = it; nseBase[key] = it }
        }
        val b = base ?: return c
        val live = try { liveOverlay(b, now) } catch (t: Throwable) { null }
        val out = live ?: b
        fnoChains[key] = out
        return out
    }

    private fun liveOverlay(b: FnoChain, now: Long): FnoChain? {
        val iso = java.time.LocalDate.ofEpochDay(Fno.expDay(b.expiry).toLong()).toString()
        val ik = b.sym + "|" + iso
        val ids = growwIds[ik]?.takeIf { now - it.first < 30 * 60_000L && it.second.isNotEmpty() }?.second
            ?: Groww.parseIds(try { p.httpGetAll(listOf(Groww.chainUrl(b.sym, iso))).firstOrNull() } catch (t: Throwable) { null }).also { if (it.isNotEmpty()) growwIds[ik] = Pair(now, it) }
        if (ids.isEmpty()) return null
        val spotLive = growwIndices().firstOrNull { it["sym"] == b.sym }?.get("last") as? Double
        val spot = spotLive ?: b.spot
        val near = b.strikes.sortedBy { Math.abs(it - spot) }.take(14).filter { ids.containsKey(it) }
        val want = near.flatMap { listOf(ids[it]!!.first, ids[it]!!.second) }
        if (want.isEmpty()) return null
        val px = Groww.parsePrices(try { p.httpPost(Groww.PRICES_URL, Json.stringify(want)) } catch (t: Throwable) { null })
        if (px.isEmpty()) return null
        liveOkAt = now
        val live = near.associateWith { Pair(px[ids[it]!!.first], px[ids[it]!!.second]) }
        val lt = px.values.maxOfOrNull { it.second } ?: now
        return Groww.overlay(b, spot, live, now, "Live " + Clock.hhmmss(if (lt > 0) lt else now))
    }

    private fun fnoView(b: FnoBook, c: FnoChain?, err: String): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["ok"] = true; m["chain"] = Fno.chainView(c); m["error"] = err
        m["live"] = c != null && p.now() - c.fetchedAt < Fno.STALE_MS
        m["account"] = Fno.summary(b); m["positions"] = b.pos.map { it.toMap() }
        m["orders"] = b.orders.take(40).map { it.toMap() }
        return m
    }

    private fun fnoNotify(ev: List<FnoEvent>) { for ((k, e) in ev.withIndex()) try { p.notify("sell", 7300 + ((e.title.hashCode() + k) and 0xff), e.title, e.body) } catch (t: Throwable) {} }

    private fun fnoAction(name: String, a: Map<String, Any?>): Map<String, Any?> {
        val sym = a.str("sym").uppercase().ifEmpty { "NIFTY" }.let { if (Fno.LOT.containsKey(it)) it else "NIFTY" }
        if (name == "fnoReset") synchronized(fnoLock) { val b = FnoBook(); fnoBook = b; fnoSave(b); return fnoView(b, null, "") }
        val exps = fnoExpiries(sym)
        val expiry = a.str("expiry").takeIf { it in exps } ?: exps.firstOrNull() ?: return synchronized(fnoLock) { fnoView(fnoLoad(), null, "NSE did not answer. Check the internet and try again.") }
        val c = fnoChain(sym, expiry, if (name == "fnoOrder") 4_000L else 2_500L)
        synchronized(fnoLock) {
            val b = fnoLoad()
            val res: Map<String, Any?> = when (name) {
                "fnoOrder" -> Fno.place(b, c, a + mapOf("sym" to sym, "expiry" to expiry), p.now())
                "fnoCancel" -> Fno.cancel(b, a.str("id"))
                "fnoLevels" -> Fno.setLevels(b, a.str("id"), a.num("sl", 0.0), a.num("tgt", 0.0))
                else -> linkedMapOf("ok" to true)
            }
            val ev = Fno.tick(b, listOfNotNull(c) + fnoChains.values.filter { it != c }, p.now())
            fnoNotify(ev)
            fnoSave(b)
            val v = LinkedHashMap(fnoView(b, c, if (c == null) "No live price from NSE right now." else ""))
            v["result"] = res
            return v
        }
    }

    // ------------------------------------------------------------------ F&O lab: learning intraday calls + live ticker
    private val labLock = Any()
    private var labBook: LabBook? = null
    private val labBars = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, IntraBars>>()
    @Volatile private var tickCache: Pair<Long, List<Map<String, Any?>>>? = null
    private fun labLoad(): LabBook { labBook?.let { return it }; val b = try { LabBook.from(Json.parseObject(p.read(LAB_FILE) ?: "")) } catch (t: Throwable) { LabBook() }; labBook = b; return b }
    private fun labSave(b: LabBook) { try { p.write(LAB_FILE, Json.stringify(b.toMap())) } catch (t: Throwable) {} }
    private fun capital() = try { st.settings.capital } catch (t: Throwable) { 100000.0 }

    @Volatile private var labStepAt = 0L
    @Volatile private var labLast = ""
    private fun labView(): Map<String, Any?> {
        // the app is open: run the lab here too, so calls appear even if the phone stopped the background watch
        if (p.now() - labStepAt > 50_000L) fnoLabStep()
        return synchronized(labLock) {
            val b = labLoad(); val v = LinkedHashMap(FnoLab.view(b, Clock.epochDay(p.now()), capital(), fnoChains))
            v["ticker"] = fnoTickerList(); v["at"] = p.now()
            v["scan"] = linkedMapOf("at" to labStepAt, "info" to labLast, "liveOk" to liveOkAt, "nseOk" to nseOkAt, "nseFail" to nseFailAt, "variants" to FnoLab.allVids("NIFTY").size + FnoLab.allVids("BANKNIFTY").size)
            v
        }
    }
    private fun fnoTickerList(): List<Map<String, Any?>> {
        growwIndices().let { if (it.isNotEmpty()) return it }
        val now = p.now(); tickCache?.let { if (now - it.first < 2_500L) return it.second }
        val got = FnoLab.parseIndices(fnoGet { it + "/api/allIndices" })
        if (got.isNotEmpty()) tickCache = Pair(now, got)
        return got.ifEmpty { tickCache?.second ?: emptyList() }
    }
    private fun fnoTicker(): Map<String, Any?> = linkedMapOf("ok" to true, "at" to p.now(), "ticker" to fnoTickerList(), "liveOk" to liveOkAt,
        "chains" to listOf("NIFTY", "BANKNIFTY").mapNotNull { s -> fnoChains.values.filter { it.sym == s }.maxByOrNull { it.fetchedAt }?.let { linkedMapOf("sym" to s, "spot" to it.spot, "at" to it.fetchedAt) } })

    private fun labExpiry(sym: String, forExpiryDay: Boolean): String? {
        val day = Clock.epochDay(p.now())
        val ex = fnoExpiries(sym)
        if (forExpiryDay) return ex.firstOrNull { Fno.expDay(it) == day }
        return ex.firstOrNull { Fno.expDay(it) - day >= 2 } ?: ex.firstOrNull()
    }

    /** Background (every minute from the live watch, 9:15–15:30): fire new calls, follow open ones, close at 15:15 and learn. */
    private val labRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    fun fnoLabStep(): String {
        if (!labRunning.compareAndSet(false, true)) return "busy"
        return try {
            val now = p.now(); val day = Clock.epochDay(now); val m = Clock.minuteOfDay(now)
            val dow = java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value
            labStepAt = now
            if (dow > 5 || m < 9 * 60 + 15 || m > 15 * 60 + 35) { labLast = "Market closed"; return "closed" }
            val events = ArrayList<Pair<String, String>>()
            // 1) follow open trades on the live chain
            val open = synchronized(labLock) { labLoad().open.map { it } }
            for (t in open) {
                val c = if (t.day == day) fnoChain(t.sym, t.expiry, 30_000L) else null
                val q = c?.quote(t.strike, t.side)
                synchronized(labLock) {
                    val b = labLoad()
                    if (q != null && q.bid > 0 && t.bids.size < FnoLab.MAX_PATH) t.bids.add(q.bid)
                    if (q != null) FnoLab.liveCheck(t, q.bid)?.let { hit -> if (t.champ && FnoLab.status(b, FnoLab.key(t)) == "WORKING") events.add(Pair("${t.sym} ${t.strike} ${t.side}: " + (if (hit == "STOP") "stop hit, exit now" else "target hit, book it"), "Bid ₹${Fno.r2(q.bid)} vs entry ₹${t.entry} (${FnoLab.stratName(t.strat)})")) }
                    if (t.day < day || m >= FnoLab.EXIT_MIN) FnoLab.close(b, t)
                }
            }
            // 2) new calls
            if (m in (9 * 60 + 20)..(14 * 60 + 30)) for ((sym, ysym) in FnoLab.INDEX) {
                val cb = labBars[sym]
                val bars = if (cb != null && now - cb.first < 55_000L) cb.second else FnoLab.parseBars(httpAll(listOf(Yahoo.tickerUrl(Params.YAHOO_HOST_1, ysym, "1d", "5m"))).firstOrNull())?.also { labBars[sym] = Pair(now, it) }
                if (bars == null) continue
                labLast = sym + ": " + bars.n + " five-minute bars, last " + Fno.r2(bars.last)
                val fired = synchronized(labLock) { labLoad().fired.filter { it.value == day && it.key.endsWith("|" + sym) }.keys.map { it.substringBefore("|") }.toSet() }
                val expToday = sym == "NIFTY" && labExpiry(sym, true) != null
                for (sg in FnoLab.signalsAll(sym, bars, m, fired, expToday)) {
                    val exp = labExpiry(sym, FnoLab.baseOf(sg.strat) == "EXPIRY") ?: continue
                    val c = fnoChain(sym, exp, 5_000L) ?: continue
                    val k = FnoLab.atm(c); val q = c.quote(k, sg.side) ?: continue
                    if (q.ask <= 0 || now - c.fetchedAt > Fno.STALE_MS) continue
                    synchronized(labLock) {
                        val b = labLoad()
                        b.fired["${sg.strat}|$sym"] = day
                        val key = sg.strat + "|" + sym; val (sp, tp) = FnoLab.bestExit(b, key)
                        b.seq++
                        val champ = FnoLab.champion(b, FnoLab.baseOf(sg.strat), sym) == sg.strat
                        val t = LabTrade("L${b.seq}", sg.strat, sym, exp, k, sg.side, Fno.LOT[sym] ?: 0, q.ask, now, day, sg.why, sp, tp, arrayListOf(q.bid), spot = c.spot, champ = champ)
                        b.open.add(t)
                        if (champ && FnoLab.status(b, key) == "WORKING") events.add(Pair("BUY $sym $k ${sg.side} ≤ ₹${Fno.r2(q.ask * 1.02)}", "${FnoLab.stratName(sg.strat)}: ${sg.why}. Stop ₹${Fno.r2(q.ask * (1 - sp / 100))}" + (if (tp > 0) ", target ₹${Fno.r2(q.ask * (1 + tp / 100))}" else "") + ", exit by 3:15 pm."))
                    }
                }
            }
            synchronized(labLock) { labSave(labLoad()) }
            for ((k, e) in events.withIndex()) try { p.notify("buy", 7500 + ((e.first.hashCode() + k) and 0xff), e.first, e.second) } catch (t: Throwable) {}
            "ok"
        } catch (t: Throwable) { log("fno lab: $t"); labLast = "Error: ${t.javaClass.simpleName}"; "error" } finally { labRunning.set(false) }
    }

    /** True when paper F&O positions or pending orders need fast checks. */
    fun fnoActive(): Boolean = try { synchronized(fnoLock) { val b = fnoLoad(); b.pos.isNotEmpty() || b.orders.any { it.status == "OPEN" } } } catch (t: Throwable) { false }

    /** Background check from the live watch: refresh chains for open contracts, run stops/targets/limits, alert. */
    fun fnoBackground(): String {
        return try {
            val keys = synchronized(fnoLock) { val b = fnoLoad(); (b.pos.map { it.sym to it.expiry } + b.orders.filter { it.status == "OPEN" }.map { it.sym to it.expiry }).distinct() }
            if (keys.isEmpty()) return "idle"
            val chains = keys.mapNotNull { (s, e) -> fnoChain(s, e, 5_000L) }
            synchronized(fnoLock) { val b = fnoLoad(); val ev = Fno.tick(b, chains, p.now()); fnoNotify(ev); fnoSave(b) }
            "ok"
        } catch (t: Throwable) { log("fno bg: $t"); "error" }
    }

    private val optPending = java.util.concurrent.ConcurrentLinkedQueue<OptEvent>()
    private fun optNid(e: OptEvent, k: Int) = 7100 + (e.t.id.hashCode() and 0xff) + k
    /** caller holds [lock] */
    private fun flushOptPending() {
        while (true) { val e = optPending.poll() ?: break; feed("info", "Paper option: " + e.title, e.body) }
    }

    /** From the stock scan (holds [lock]): run the options step and post alerts normally. */
    private fun optionsTick(now: Long, iq: Intraday) {
        flushOptPending()
        for (e in optionsStep(now, iq)) feed("info", "Paper option: " + e.title, e.body)
    }

    /** Fetch Bank Nifty + VIX, step the book under [optLock], save it. Returns the BUY/SELL events. Never throws. */
    private fun optionsStep(now: Long, iq: Intraday): List<OptEvent> {
        try {
            val got = fetchParsed(listOf("^NSEBANK", Options.VIX), { h, s -> Yahoo.intradayUrl(h, s) }, { s, t -> Yahoo.intraday(s, t) }, "", 1, 2)
            val spots = HashMap<String, Double>()
            if (iq.ltp > 0) spots[Params.INDEX_SYMBOL] = iq.ltp
            got["^NSEBANK"]?.let { if (it.ltp > 0) spots["^NSEBANK"] = it.ltp }
            val vix = got[Options.VIX]?.ltp ?: 0.0
            synchronized(optLock) {
                val book = optLoad()
                val notes = Options.step(book, now, spots, mapOf(Params.INDEX_SYMBOL to cache.get(Params.INDEX_SYMBOL), "^NSEBANK" to cache.get("^NSEBANK")), vix, st.settings.capital)
                optSpots = spots
                if (vix > 0) optVix = vix
                p.write(OPT_FILE, Json.stringify(mapOf("vix" to optVix, "trades" to book.map { it.toMap() })))
                return notes
            }
        } catch (t: Throwable) { log("options step: $t"); return emptyList() }
    }

    @Volatile private var fundCache: FundData? = null
    @Volatile private var fundFetchedAt = 0L
    private val fundLock = Any()

    /** Mutual-fund tables: saved copy, refreshed from GitHub when older than FLOW_STALE_MS. Never throws. */
    private fun fundData(force: Boolean): FundData {
        synchronized(fundLock) {
            try {
                if (fundCache == null) {
                    fundFetchedAt = try { p.read(FUND_META)?.trim()?.toLongOrNull() ?: 0L } catch (t: Throwable) { 0L }
                    fundCache = Funds.parse(p.read("mf_funds.csv"), p.read("mf_fund_holdings.csv"), p.read("mf_fund_stocks.csv"))
                }
                val now = p.now(); val age = now - fundFetchedAt
                if (age >= FLOW_STALE_MS || (force && age >= FLOW_FORCE_MS) || fundCache?.ok != true) {
                    val got = httpAll(FUND_FILES.map { FLOW_BASE + it })
                    val fl = got.getOrNull(0)
                    if (fl != null && Funds.parse(fl, null, null).ok) {
                        p.write("mf_funds.csv", fl)
                        for (k in 1 until FUND_FILES.size) { val t = got.getOrNull(k) ?: continue; if (t.startsWith("CODE,") || t.startsWith("STOCK,")) p.write("mf_" + FUND_FILES[k], t) }
                        fundCache = Funds.parse(p.read("mf_funds.csv"), p.read("mf_fund_holdings.csv"), p.read("mf_fund_stocks.csv"))
                    }
                    fundFetchedAt = now
                    try { p.write(FUND_META, now.toString()) } catch (t: Throwable) {}
                }
            } catch (t: Throwable) { log("funds: $t") }
            return fundCache ?: Funds.parse(null, null, null)
        }
    }

    private fun chart(a: Map<String, Any?>): Map<String, Any?> {
        val raw = a.str("sym")
        val sym = HoldingsImport.cleanSymbol(raw)
        if (!validSym(sym)) return errSym(sym.ifEmpty { raw.trim() }, "Enter a valid NSE symbol, e.g. RELIANCE")
        loadUniverse()
        val want = a.int("bars", Params.CHART_BARS).let { if (it <= 0) Params.CHART_BARS else minOf(it, 5000) }
        val (got, why) = obtain(sym, false, now())
        val x = got?.s ?: return errSym(sym, why)
        if (x.n < 2) return errSym(sym, "No price history for $sym yet")
        val from = maxOf(0, x.n - want)
        val L = EmaLines(x)
        val d = ArrayList<Any?>(); val o = ArrayList<Any?>(); val h = ArrayList<Any?>(); val l = ArrayList<Any?>(); val c = ArrayList<Any?>(); val v = ArrayList<Any?>()
        val m50 = ArrayList<Any?>(); val m200 = ArrayList<Any?>()
        val x10 = ArrayList<Any?>(); val x20 = ArrayList<Any?>(); val x50 = ArrayList<Any?>(); val x100 = ArrayList<Any?>(); val x200 = ArrayList<Any?>()
        for (i in from until x.n) {
            d.add(x.d[i].toLong() * Clock.DAY_MS); o.add(Fmt.r2(x.o[i])); h.add(Fmt.r2(x.h[i])); l.add(Fmt.r2(x.l[i])); c.add(Fmt.r2(x.c[i])); v.add(Math.rint(x.v[i]))
            x10.add(Fmt.r2(L.shown(10, i))); x20.add(Fmt.r2(L.shown(20, i))); x50.add(Fmt.r2(L.shown(50, i))); x100.add(Fmt.r2(L.shown(100, i))); x200.add(Fmt.r2(L.shown(200, i)))
            val a50 = Ind.sma(x.c, i, 50); val a200 = Ind.sma(x.c, i, 200)
            m50.add(if (a50.isNaN()) 0.0 else Fmt.r2(a50)); m200.add(if (a200.isNaN()) 0.0 else Fmt.r2(a200))
        }
        val levels = ArrayList<Any?>()
        fun lv(kind: String, label: String, v: Double) { if (v > 0) levels.add(linkedMapOf<String, Any?>("kind" to kind, "label" to label, "v" to Fmt.r2(v))) }
        val r = refs
        val tr = r.trades[sym]
        val sg = r.signals[sym]
        val ho = r.holdings[sym]
        if (tr != null) { lv("entry", "Bought", tr[0]); lv("stop", "Stop-loss", tr[1]); lv("target", "Target 1", tr[2]); lv("target", "Target 2", tr[3]) }
        else if (sg != null) { lv("entry", "Entry", sg[0]); lv("stop", "Stop-loss", sg[1]); lv("target", "Target 1", sg[2]); lv("target", "Target 2", sg[3]) }
        if (ho != null) { lv("entry", "Your avg", ho[0]); if (tr == null && sg == null) { lv("stop", "Trailing stop", ho[1]); lv("target", "Book near", ho[2]) } }
        val last = x.n - 1
        val a50 = Ind.sma(x.c, last, 50); val a200 = Ind.sma(x.c, last, 200); val rsi = Ind.rsi(x.c, last, 14)
        val hi = Ind.highest(x.h, last, minOf(250, x.n)); val lo = Ind.lowest(x.l, last, minOf(250, x.n))
        fun rt(n: Int): Double { val q = Ind.ret(x.c, last, n); return if (q.isNaN()) 0.0 else Fmt.r2(q * 100.0) }
        return ok("sym" to sym, "name" to displayName(sym), "d" to d, "o" to o, "h" to h, "l" to l, "c" to c, "v" to v, "sma50" to m50, "sma200" to m200,
            "ema10" to x10, "ema20" to x20, "ema50" to x50, "ema100" to x100, "ema200" to x200, "levels" to levels,
            "stats" to linkedMapOf<String, Any?>("ltp" to Fmt.r2(x.c[last]), "sma50" to (if (a50.isNaN()) 0.0 else Fmt.r2(a50)), "sma200" to (if (a200.isNaN()) 0.0 else Fmt.r2(a200)),
                "rsi14" to (if (rsi.isNaN()) 0.0 else Fmt.r1(rsi)), "high52" to (if (hi.isNaN()) 0.0 else Fmt.r2(hi)), "low52" to (if (lo.isNaN()) 0.0 else Fmt.r2(lo)),
                "ret5" to rt(5), "ret21" to rt(21), "ret63" to rt(63), "ret126" to rt(126), "bars" to x.n))
    }

    /** `myWatch` rows from the last known prices. Loads the file cache only on a tick/action thread, never on the UI's first getState. */
    private fun myWatch(s: AppState, now: Long): List<Map<String, Any?>> {
        if (s.watch.isEmpty()) return emptyList()
        val canLoad = lock.isHeldByCurrentThread || cache.isLoaded()
        if (lock.isHeldByCurrentThread) loadUniverse()
        val idx = live[Params.INDEX_SYMBOL] ?: if (canLoad) cache.get(Params.INDEX_SYMBOL) else null
        return s.watch.map { sym ->
            val nm = names[sym]?.takeIf { it != sym } ?: symIndex?.nameOf(sym) ?: names[sym] ?: sym
            try {
                val le = synchronized(lru) { lru[sym] }
                val x = live[sym] ?: (if (canLoad) cache.get(sym) else null) ?: le?.let { join(it.daily, it.quote, sym) }
                StockPage.watchItem(sym, nm, x, idx, now)
            } catch (t: Throwable) {
                StockPage.watchItem(sym, nm, null, null, now)
            }
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
                inv += if (h.avg > 0) h.avg * h.qty else h.valueRs(); value += h.valueRs()   // no buy price: counted at current value, so it adds no P&L
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
            m["watch"] = watchItems
            m["myWatch"] = myWatch(s, now)
            m["ideas"] = ideaItems
            m["marketGate"] = marketGate
            m["optOpen"] = try { synchronized(optLock) { optLoad().filter { it.status == "OPEN" }.map { it.toMap() } } } catch (t: Throwable) { emptyList<Any>() }
            m["optRecord"] = try { synchronized(optLock) { Options.stats(optLoad()) } } catch (t: Throwable) { null }
            m["targets"] = s.targets.map { it.toMap() }
            m["track"] = if (trackView.isEmpty()) TrackEngine.view(TrackStore(), s.settings.costPct, false) else trackView
            return m
        }
    }

    private fun publish() {
        vHolidayDay = st.holidayDay
        vAnyLive = anyLive()
        vTested = Strategies.all.any { st.strat(it.id).testedAt != 0L }
        try { refreshTrackView() } catch (t: Throwable) { log("track view: $t") }
        snapshot = buildSnapshot(st)
        try { refs = buildRefs() } catch (t: Throwable) { log("refs: $t") }
    }

    private fun buildRefs(): Refs {
        val hm = HashMap<String, DoubleArray>()
        var total = 0.0
        for (h in st.holdings) { total += h.valueRs(); if (!hm.containsKey(h.sym)) hm[h.sym] = doubleArrayOf(h.avg, h.stop, h.bookAt, h.valueRs()) }
        val tm = HashMap<String, DoubleArray>()
        for (t in st.trades) if (t.status == "OPEN" && !t.paper && !tm.containsKey(t.sym)) tm[t.sym] = doubleArrayOf(t.entry, t.sl, t.t1, t.t2)
        val sm = HashMap<String, DoubleArray>()
        for (g in st.signals) if (g.status == "NEW" && !sm.containsKey(g.sym)) sm[g.sym] = doubleArrayOf(g.entry, g.sl, g.t1, g.t2)
        val vm = HashMap<String, String>()
        for (sg in Strategies.all) vm[sg.id] = if (sg.enabled()) st.strat(sg.id).verdict else "PAUSED"
        val tg = HashMap<String, Map<String, Any?>>()
        for (t in st.targets) tg[t.sym] = linkedMapOf("target" to Fmt.r2(t.target), "stop" to Fmt.r2(t.stop), "note" to t.note, "status" to t.status)
        return Refs(hm, total, st.watch.toHashSet(), tm, sm, regimeInt(), vm, tg,
            doubleArrayOf(st.settings.capital, st.settings.riskPct, st.settings.costPct))
    }

    companion object {
        /** a failed price-history download is retried after this long (or at once on a manual scan) */
        const val CHANGES_URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/symbolchange.csv"
        const val CHANGES_FILE = "symbolchange.csv"
        const val ALT_FILE = "alt_tickers.json"
        const val HOLD_MAX_DAYS = 120
        const val OPT_FILE = "opt_paper.json"
        const val FNO_FILE = "fno_paper.json"
        const val LAB_FILE = "fno_lab.json"
        const val TRAIL_ATR = 5.0
        const val FLOW_BASE = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/"
        val FLOW_FILES = listOf("flows.csv", "industries.csv", "holders.csv", "deals.csv", "indices.csv")
        const val FLOW_META = "mf_fetched.txt"
        val FUND_FILES = listOf("funds.csv", "fund_holdings.csv", "fund_stocks.csv")
        const val FUND_META = "fund_fetched.txt"
        const val FLOW_STALE_MS = 4L * 3600 * 1000
        const val FLOW_FORCE_MS = 5L * 60 * 1000
        const val ALT_MAX = 12
        const val ALT_RETRY_MS = 6L * 3600 * 1000
        const val ALT_FORCE_MS = 10L * 60 * 1000
        const val RETRY_FAILED_MS = 20L * 60 * 1000
        const val STATE_FILE = "state.json"
        const val FORWARD_FILE = "forward.json"
        const val REPLAY_FILE = "replay.json"
        const val NID_BRIEF = 1001
        const val NID_EOD = 1002
        private val SYM_RE = Regex("^[A-Z0-9&][A-Z0-9&\\-]*$")
        private const val LRU_MAX = 20
        /** A stock-page quote younger than this is reused by the next lookup of the same symbol. */
        private const val QUOTE_REUSE_MS = 60_000L
        private val NO_TARGET: Map<String, Any?> = linkedMapOf("target" to 0.0, "stop" to 0.0, "note" to "", "status" to "")
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
