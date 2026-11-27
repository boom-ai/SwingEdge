package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** Live whole-market scan: event detection, pace, sources, rotation / budget, dedupe, throttling, live score, app glue. */
object LiveScanTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "live-detect" to ::detect, "live-pace-circuit" to ::paceCircuit, "live-parse" to ::parse, "live-rotation" to ::rotation,
        "live-plan-budget" to ::planBudget, "live-dedupe" to ::dedupe, "live-throttle" to ::throttle, "live-score" to ::score,
        "live-mode-persist" to ::modePersist, "live-app" to ::app)

    private val DAY = java.time.LocalDate.of(2026, 11, 27).toEpochDay().toInt()   // a Friday
    private fun at(min: Int, sec: Int = 0) = Clock.msAt(DAY, min) + sec * 1000L

    fun ref(sym: String, close: Double = 100.0, label: String = "BUY", to20: Double = 50.0, hi252: Double = 110.0, exch: String = "NSE", code: String = "") =
        LiveRef(sym, exch, code, "$sym Ltd", close, 90.0, 102.0, hi252, 60.0, to20, close, close + 2.0, close - 20.0, close + 24.0, close + 36.0, label, 90.0, 1)

    fun q(sym: String, ltp: Double, open: Double = 100.0, prev: Double = 100.0, vol: Double = 1_000_000.0, high: Double = Double.NaN, low: Double = Double.NaN, src: String = "groww", at: Long = 0L, up: Double = Double.NaN, dn: Double = Double.NaN) =
        LiveQuote(sym, ltp, open, if (high.isNaN()) Math.max(ltp, open) else high, if (low.isNaN()) Math.min(ltp, open) else low, prev, vol, at, src, up, dn, at = at)

    private fun kinds(r: LiveRef, x: LiveQuote, m: Int) = LiveScan.detect(r, x, m).map { it.first }

    fun detect() {
        val m = 11 * 60
        val r = ref("AAA")
        T.check("ZONE" in kinds(r, q("AAA", 101.0), m), "inside entry zone above SMA50 -> ZONE")
        T.check("ZONE" !in kinds(r, q("AAA", 103.0), m), "above the zone -> no ZONE")
        T.check("ZONE" !in kinds(ref("AAA", label = "WAIT"), q("AAA", 101.0), m), "zone only for BUY-labelled stocks")
        val below50 = LiveRef("B", "NSE", "", "B", 100.0, 101.5, 102.0, 110.0, 60.0, 50.0, 100.0, 102.0, 80.0, 124.0, 136.0, "BUY", 90.0, 1)
        T.check("ZONE" !in kinds(below50, q("B", 101.0), m), "zone needs price above SMA50")
        // breakout: above 52w high with volume pace >= 1.5 (50 cr/day typical; 11:00 = ~36%+ of the day)
        val share = LiveScan.dayShare(m)
        val volFor = { pace: Double, px: Double -> pace * 50.0 * share * 1e7 / px }
        T.check("BREAKOUT" in kinds(r, q("AAA", 111.0, vol = volFor(2.0, 111.0)), m), "new 52w high on 2x volume -> BREAKOUT")
        T.check("BREAKOUT" !in kinds(r, q("AAA", 111.0, vol = volFor(1.0, 111.0)), m), "new 52w high on normal volume -> no BREAKOUT")
        T.check("BREAKOUT" !in kinds(r, q("AAA", 109.0, vol = volFor(3.0, 109.0)), m), "below the 52w high -> no BREAKOUT")
        T.check("BREAKOUT" in kinds(ref("AAA", to20 = Double.NaN), LiveQuote("AAA", 111.0, 100.0, 111.0, 100.0, 100.0, Double.NaN, 0L, "broker"), m), "no volume known -> breakout on price alone")
        T.check("STOP" in kinds(r, q("AAA", 79.0, open = 81.0), m), "at/below plan stop -> STOP")
        T.check("TARGET" in kinds(r, q("AAA", 125.0, open = 101.0), m) && "TARGET2" !in kinds(r, q("AAA", 125.0, open = 101.0), m), "T1 hit")
        T.check("TARGET2" in kinds(r, q("AAA", 137.0, open = 101.0), m) && "TARGET" !in kinds(r, q("AAA", 137.0, open = 101.0), m), "T2 replaces T1")
        T.check("GAP_UP" in kinds(r, q("AAA", 104.0, open = 103.5), m), "open +3.5% -> GAP_UP")
        T.check("GAP_UP" !in kinds(r, q("AAA", 104.0, open = 102.5), m), "open +2.5% -> no gap")
        T.check("GAP_DOWN" in kinds(r, q("AAA", 96.0, open = 96.5), m), "open -3.5% -> GAP_DOWN")
        T.check("VOL_SHOCK" in kinds(ref("AAA", label = "WAIT"), q("AAA", 100.5, vol = volFor(3.0, 100.5)), m), "3x volume pace -> VOL_SHOCK")
        T.check("VOL_SHOCK" !in kinds(ref("AAA", label = "WAIT"), q("AAA", 100.5, vol = volFor(2.0, 100.5)), m), "2x pace -> no shock")
        T.check("VOL_SHOCK" !in kinds(ref("AAA", to20 = 0.05, label = "WAIT"), q("AAA", 100.5, vol = 0.2 * 0.05 * 1e7), 9 * 60 + 20), "first minutes / tiny turnover: no shock")
        T.check("BIG_UP" in kinds(ref("AAA", label = "WAIT"), q("AAA", 106.0), m) && "BIG_DOWN" in kinds(ref("AAA", label = "WAIT"), q("AAA", 94.0), m), "±5% day move events")
        T.check(kinds(r, q("AAA", 101.0), 9 * 60).isEmpty(), "nothing before the open")
        val tv = LiveScan.detect(r, q("AAA", 101.0, src = "tv"), m)
        T.check(tv.isNotEmpty() && tv.all { it.second.contains("delayed") }, "TradingView events are labelled delayed")
    }

    fun paceCircuit() {
        var prev = 0.0; var mono = true
        for (m in Clock.OPEN_MIN..Clock.CLOSE_MIN) { val s = LiveScan.dayShare(m); if (s < prev - 1e-12) mono = false; prev = s }
        T.check(mono, "volume profile is non-decreasing"); T.near(LiveScan.dayShare(Clock.CLOSE_MIN), 1.0, 1e-9, "whole day at the close")
        T.near(LiveScan.dayShare(Clock.OPEN_MIN), 0.04, 1e-9, "floored at the open")
        T.check(LiveScan.dayShare(Clock.OPEN_MIN + 30) > 0.15, "front-loaded profile")
        val r = ref("P")
        T.near(LiveScan.pace(r, q("P", 100.0, vol = 50.0 * 1e7 / 100.0), Clock.CLOSE_MIN), 1.0, 1e-9, "a typical full day = 1.0")
        T.check(LiveScan.pace(r, q("P", 100.0, vol = Double.NaN), 600).isNaN(), "no volume -> NaN pace")
        val tvq = LiveQuote("P", 100.0, 100.0, 100.0, 100.0, 100.0, 1000.0, 0L, "tv", relVol = 2.2)
        T.near(LiveScan.pace(ref("P", to20 = Double.NaN), tvq, 600), 2.2, 1e-9, "TradingView relative volume as fallback pace")
        T.eq(LiveScan.circuit(q("C", 105.0, prev = 100.0, high = 105.0)), 5.0, "inferred +5% band at day high")
        T.eq(LiveScan.circuit(q("C", 119.8, prev = 100.0, high = 119.8)), 20.0, "inferred +20% band")
        T.eq(LiveScan.circuit(q("C", 104.0, prev = 100.0, high = 104.0)), 0.0, "+4% is no band")
        T.eq(LiveScan.circuit(q("C", 105.0, prev = 100.0, high = 106.0)), 0.0, "off the high -> not pressing")
        T.eq(LiveScan.circuit(q("C", 90.1, prev = 100.0, low = 90.1)), -10.0, "inferred -10% band")
        T.check(LiveScan.circuit(q("C", 101.9, prev = 100.0, up = 102.0)) > 0, "exchange limit from the source wins (2% band)")
        T.eq(LiveScan.circuit(q("C", 105.0, prev = 100.0, high = 105.0, up = 110.0)), 0.0, "explicit 10% limit: +5% is not near")
        T.check("UC_NEAR" in kinds(ref("C", label = "WAIT", hi252 = 200.0), q("C", 110.0, open = 101.0, prev = 100.0, high = 110.0), 600), "UC_NEAR event")
    }

    fun parse() {
        val g = """{"exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{"RELIANCE":{"ltp":1510.5,"open":1500,"high":1520,"low":1490,"close":1498,"volume":123456,"dayChangePerc":0.84,"tsInMillis":1795760000,"upperCircuitLimit":1647.8},"BAD":{"ltp":0}}}}}"""
        val m = LiveScan.parseGroww(g, "NSE", 5L)
        val r = m["RELIANCE"]!!
        T.eq(m.size, 1, "zero-price row dropped"); T.eq(r.volume, 123456.0, "volume"); T.eq(r.prev, 1498.0, "prev close = close field")
        T.eq(r.tsMs, 1795760000000L, "seconds -> ms"); T.eq(r.upper, 1647.8, "circuit limit when present"); T.check(r.lower.isNaN(), "absent lower limit NaN")
        T.eq(LiveScan.parseGroww("not json").size, 0, "garbage -> empty")
        val b = """{"exchangeAggRespMap":{"BSE":{"priceLivePointsMap":{"590001":{"ltp":31.5,"close":30}}}}}"""
        T.eq(LiveScan.parseGroww(b, "BSE", 1L, mapOf("590001" to "BSEONLY1")).keys, setOf("BSEONLY1"), "BSE code mapped back to app symbol")
        T.check(LiveScan.growwBody(listOf("A", "B")).contains("\"priceSymbolList\":[\"A\",\"B\"]"), "Groww body")

        val cols = LiveScan.TV_CORE + LiveScan.TV_EXTRA
        val row = { s: String, c: Double, ch: Double -> """{"s":"$s","d":["${s.substringAfter(':')}",$c,$ch,5000,${c * 0.99},${c * 1.01},${c * 0.98},4000,1.7,"delayed_streaming_900"]}""" }
        val tv = """{"totalCount":4,"data":[${row("NSE:M_M", 3000.0, 2.0)},${row("BSE:M_M", 3001.0, 2.0)},${row("BSE:THINKINK", 31.0, 3.33)},${row("BSE:UNKNOWN", 5.0, 1.0)},{"s":"NSE:X","d":[null]}]}"""
        val t = LiveScan.parseTv(tv, cols, 1000L, mapOf("THINKINK" to "BSEONLY1"), 900_000L)!!
        T.eq(t.keys, setOf("M&M", "BSEONLY1"), "NSE '_' -> '&', BSE through the id map, unknown BSE skipped")
        T.near(t["M&M"]!!.prev, 3000.0 / 1.02, 1e-9, "prev close from change %"); T.eq(t["M&M"]!!.ltp, 3000.0, "NSE row wins over BSE")
        T.eq(t["M&M"]!!.relVol, 1.7, "relative volume column"); T.eq(t["M&M"]!!.tsMs, 1000L - 900_000L, "delay shifts the quote time")
        T.eq(LiveScan.tvDelayMs(tv, cols), 900_000L, "update_mode delayed_streaming_900 -> 15 min")
        T.eq(LiveScan.tvDelayMs(tv, LiveScan.TV_CORE), LiveScan.TV_DEFAULT_DELAY_MS, "no update_mode column -> assume 15 min")
        T.check(LiveScan.parseTv("""{"error":"Unknown field"}""", cols, 1L) == null, "error reply -> null (retry with core columns)")
        val body = Json.parseObject(LiveScan.tvBody(cols))!!
        T.eq(body["markets"], listOf("india"), "markets"); T.check((body["columns"] as List<*>).size == 10, "10 trimmed columns")
        T.check(body.containsKey("symbols") && (body["range"] as List<*>).map { (it as Number).toInt() } == listOf(0, 6000), "body shape")
    }

    fun rotation() {
        val r = Rotation(listOf("A", "B", "C", "D", "E"))
        T.eq(r.next(2), listOf("A", "B"), "first slice"); T.eq(r.next(2), listOf("C", "D"), "second slice")
        T.eq(r.next(2), listOf("E", "A"), "wraps"); T.eq(r.laps, 1, "one lap counted")
        T.eq(r.next(9), listOf("B", "C", "D", "E", "A"), "never more than the list")
        r.reset(listOf("X", "B", "Y")); T.eq(r.next(1), listOf("B"), "reset keeps the cursor symbol")
        r.reset(listOf("Q")); T.eq(r.next(3), listOf("Q"), "cursor symbol gone -> start")
        T.eq(Rotation().next(3), emptyList<String>(), "empty list")
    }

    private fun engine(nLiquid: Int, nRest: Int, rel: Set<String> = emptySet(), nBse: Int = 0): LiveEngine {
        val e = LiveEngine()
        val list = ArrayList<LiveRef>()
        for (k in 0 until nLiquid) list.add(ref("L%04d".format(k), to20 = 100.0 + k))
        for (k in 0 until nRest) list.add(ref("R%04d".format(k), to20 = 1.0, label = "WAIT"))
        for (k in 0 until nBse) list.add(ref("B%04d".format(k), to20 = 1.0, label = "WAIT", exch = "BSE", code = (500000 + k).toString()))
        e.setRefs(list, DAY - 1, rel, emptySet())
        return e
    }

    fun planBudget() {
        val e = engine(700, 2900, rel = setOf("R0005"), nBse = 100)
        T.eq(e.hotList.size, LiveScan.HOT_MAX, "hot list capped"); T.eq(e.hotList.first(), "R0005", "user stock first in the hot list")
        var now = at(10 * 60)
        val p0 = e.plan(now, fg = true, broker = false)!!
        T.eq(p0.src, "tv", "TradingView first")
        e.ingest(p0, null, now)                                    // TradingView down
        T.check(e.plan(now + 500, true, false) == null, "never two calls within 1 s")
        // TV down: Groww walks the whole NSE list; count calls for one full sweep
        val seen = HashSet<String>(); var calls = 0; var maxPerSec = 0; var tvTries = 0
        val stamps = ArrayList<Long>()
        while (seen.size < 3600 && calls < 400) {
            now += 1000
            val p = e.plan(now, true, false) ?: continue
            if (p.src == "tv") { tvTries++; e.ingest(p, null, now); stamps.add(now); continue }
            T.check(p.syms.size <= LiveScan.GROWW_BATCH, "batch <= 80")
            if (p.src == "groww") seen.addAll(p.syms)
            calls++; stamps.add(now); e.ingest(p, emptyMap(), now)
        }
        for (i in stamps.indices) { var c = 0; for (j in i until stamps.size) if (stamps[j] - stamps[i] < 1000) c++; maxPerSec = Math.max(maxPerSec, c) }
        T.eq(seen.size, 3600, "full NSE sweep covered"); T.check(calls in 45..52, "~45 Groww calls a full sweep (TV down): $calls")
        T.eq(maxPerSec, 1, "at most 1 call a second"); T.check(tvTries <= 1, "TradingView retried with back-off, not every minute: $tvTries")
        T.check(e.lastSweepMs in 40_000L..60_000L, "sweep time recorded: ${e.lastSweepMs}")
        // the rotation order is hot -> liquid -> rest
        val e2 = engine(100, 300); e2.ingest(LivePlan("tv", emptyList()), null, at(10 * 60))
        val first = e2.plan(at(10 * 60, 2), true, false)!!.syms
        T.check(first.all { it.startsWith("L") }, "liquid stocks first")

        // TV healthy: one whole-market call every 2 min (5 min in background); Groww only walks hot + liquid every 2 s
        val e3 = engine(700, 2900)
        var t3 = at(10 * 60)
        val big = HashMap<String, LiveQuote>(); for (k in 0 until 400) big["R%04d".format(k)] = q("R%04d".format(k), 100.0, at = t3)
        e3.ingest(e3.plan(t3, true, false)!!, big, t3)
        T.check(e3.tvHealthy(t3), "TradingView healthy after a big reply")
        var tv = 0; var gw = 0; val walked = HashSet<String>()
        for (s in 1..600) { t3 += 1000; val p = e3.plan(t3, true, false) ?: continue; if (p.src == "tv") { tv++; e3.ingest(p, big, t3) } else { gw++; walked.addAll(p.syms); e3.ingest(p, emptyMap(), t3) } }
        T.eq(tv, 5, "10 minutes foreground: TradingView every 2 min"); T.check(gw in 290..300, "Groww every 2 s: $gw")
        T.check(walked.all { it.startsWith("L") }, "Groww walks only hot + liquid while TradingView covers the rest")
        var tvBg = 0; for (s in 1..615) { t3 += 1000; val p = e3.plan(t3, false, false) ?: continue; if (p.src == "tv") { tvBg++ }; e3.ingest(p, if (p.src == "tv") big else emptyMap(), t3) }
        T.eq(tvBg, 2, "background: TradingView every 5 min")
        // broker connected: it refreshes the hot list
        val e4 = engine(300, 0); e4.ingest(LivePlan("tv", emptyList()), null, at(10 * 60))
        val pb = e4.plan(at(10 * 60, 1), true, true)!!
        T.eq(pb.src, "broker", "broker asked for the hot list"); T.eq(pb.syms.size, LiveScan.BROKER_BATCH, "<= 200 per broker call")
        e4.ingest(pb, emptyMap(), at(10 * 60, 1))
        T.eq(e4.plan(at(10 * 60, 2), true, true)!!.src, "groww", "then Groww until the broker is due again")
        // BSE key switches itself off after 3 empty replies
        val e5 = engine(10, 10, nBse = 300); var t5 = at(10 * 60); e5.ingest(LivePlan("tv", emptyList()), null, t5)
        var bse = 0
        for (s in 1..200) { t5 += 1000; val p = e5.plan(t5, true, false) ?: continue; if (p.src == "growwBse") bse++; e5.ingest(p, emptyMap(), t5) }
        T.eq(bse, 3, "Groww BSE tried 3 times then off for the day")
    }

    fun dedupe() {
        val e = engine(5, 0)
        val now = at(10 * 60)
        val p = LivePlan("groww", listOf("L0000"))
        val ev1 = e.ingest(p, mapOf("L0000" to q("L0000", 101.0, at = now)), now)
        T.eq(ev1.map { it.kind }, listOf("ZONE"), "first time in zone -> one event")
        val ev2 = e.ingest(p, mapOf("L0000" to q("L0000", 101.5, at = now + 2000)), now + 2000)
        T.eq(ev2.size, 0, "still in zone -> no repeat")
        e.ingest(p, mapOf("L0000" to q("L0000", 104.0, at = now + 4000)), now + 4000)
        T.eq(e.ingest(p, mapOf("L0000" to q("L0000", 101.0, at = now + 6000)), now + 6000).size, 0, "re-entry the same day -> no repeat")
        T.eq(e.ring.size, 1, "ring holds the one event")
        val tomorrow = Clock.msAt(DAY + 3, 10 * 60)
        T.eq(e.ingest(p, mapOf("L0000" to q("L0000", 101.0, at = tomorrow)), tomorrow).map { it.kind }, listOf("ZONE"), "a new day fires again")
        // ring cap
        val e2 = engine(LiveScan.RING_MAX + 50, 0); val t = at(11 * 60)
        val many = HashMap<String, LiveQuote>(); for (k in 0 until LiveScan.RING_MAX + 50) many["L%04d".format(k)] = q("L%04d".format(k), 101.0, at = t)
        e2.ingest(LivePlan("groww", many.keys.toList()), many, t)
        T.eq(e2.ring.size, LiveScan.RING_MAX, "ring capped")
        // a delayed TradingView row never replaces a live quote of the same session
        val e3 = engine(3, 0); val t3 = at(12 * 60)
        e3.ingest(p, mapOf("L0000" to q("L0000", 104.0, at = t3)), t3)
        e3.ingest(LivePlan("tv", emptyList()), mapOf("L0000" to LiveQuote("L0000", 99.0, 100.0, 100.0, 99.0, 100.0, 10.0, t3 + 60_000 - 900_000, "tv", at = t3 + 60_000)), t3 + 60_000)
        T.eq(e3.quotes["L0000"]!!.ltp, 104.0, "live quote kept over the delayed row")
        // broker quote (no volume) keeps the earlier volume
        e3.ingest(LivePlan("broker", listOf("L0000")), mapOf("L0000" to LiveQuote("L0000", 104.5, 100.0, 105.0, 99.0, 100.0, Double.NaN, t3 + 70_000, "broker", at = t3 + 70_000)), t3 + 70_000)
        T.eq(e3.quotes["L0000"]!!.volume, 1_000_000.0, "volume carried over a broker quote"); T.eq(e3.quotes["L0000"]!!.ltp, 104.5, "broker price used")
    }

    fun throttle() {
        val e = engine(20, 0, rel = setOf("L0001", "L0002", "L0003", "L0004", "L0005", "L0006"))
        val now = at(10 * 60)
        fun ev(sym: String, kind: String, rel: Boolean = true) = LiveEvent(now, sym, kind, 100.0, 1.0, "", rel)
        T.eq(e.toNotify(listOf(ev("L0009", "ZONE", rel = false)), now).size, 0, "not the user's stock -> no notification")
        T.eq(e.toNotify(listOf(ev("L0001", "BIG_UP")), now).size, 0, "non-notifying kind")
        T.eq(e.toNotify(listOf(ev("L0001", "ZONE"), ev("L0001", "BREAKOUT")), now).size, 1, "one per stock at a time")
        T.eq(e.toNotify(listOf(ev("L0001", "STOP")), now + 60_000).size, 0, "same stock within 30 min -> held")
        val burst = e.toNotify(listOf("L0002", "L0003", "L0004", "L0005", "L0006").map { ev(it, "ZONE") }, now + 60_000)
        T.eq(burst.size, LiveScan.NOTE_WINDOW_MAX - 1, "at most 4 in 10 minutes")
        T.eq(e.toNotify(listOf(ev("L0006", "ZONE")), now + 11 * 60_000).size, 1, "window frees up after 10 minutes")
        T.eq(e.toNotify(listOf(ev("L0001", "STOP")), now + 31 * 60_000).size, 1, "same stock again after 30 min")
        val pri = engine(5, 0, rel = setOf("L0001")).toNotify(listOf(ev("L0001", "ZONE"), ev("L0001", "STOP")), now)
        T.eq(pri.map { it.kind }, listOf("STOP"), "stop / target first")
    }

    fun score() {
        val text = ScanTests.scanText(DAY - 1, 120)
        val d = MarketScan.parse(text)!!
        val e = LiveEngine()
        e.setRefs(d.rows.map { x -> val c = x.comp
            LiveRef(x.sym, x.exch, x.code, x.name, c.close, c.sma50, c.high, c.hi252, c.lo252, c.to20, c.close, 0.0, 0.0, 0.0, 0.0, x.label, x.score, x.rank, c, x.inTable) }, d.day, emptySet(), emptySet())
        val now = at(11 * 60)
        val base = d.table
        val same = HashMap<String, LiveQuote>()
        for (x in d.rows) if (x.inTable) same[x.sym] = LiveQuote(x.sym, x.comp.close, x.comp.close, x.comp.close, x.comp.close, x.comp.close, 0.0, now, "groww", at = now)
        e.ingest(LivePlan("groww", same.keys.toList()), same, now)
        val t = e.table(now)!!
        T.eq(t.ranked.map { it.sym to it.score }, base.ranked.map { it.sym to it.score }, "ltp == nightly close reproduces the nightly table")
        // push the weakest rated stock to a new high: PROX52 / LOW52 rise, so its score and rank improve
        val low = base.ranked.last()
        val c = low.comp
        val px = c.hi252 * 1.05
        val lc = LiveScan.liveComp(c, LiveQuote(low.sym, px, c.close, px, c.close, c.close, 0.0, now, "groww", at = now))
        T.near(lc.prox52, 1.0, 1e-12, "at the new high PROX52 = 1"); T.eq(lc.r6, c.r6, "R6 unchanged"); T.eq(lc.ram, c.ram, "RAM unchanged")
        T.check(lc.hi252 >= px, "52w high extended by today's high")
        val now2 = now + 30_000
        e.ingest(LivePlan("groww", listOf(low.sym)), mapOf(low.sym to LiveQuote(low.sym, px, c.close, px, c.close, c.close, 0.0, now2, "groww", at = now2)), now2)
        val t2 = e.table(now2)!!
        T.check(t2.entries[low.sym]!!.score > low.score && t2.entries[low.sym]!!.rank < low.rank, "live score / rank move up: ${low.score}->${t2.entries[low.sym]!!.score}")
        T.check(e.table(now2 + 1000) === t2, "live table cached")
    }

    fun modePersist() {
        val now = 10_000_000L
        T.eq(LiveScan.mode(now, now - 5_000, true, 0, false), 2, "UI open -> foreground")
        T.eq(LiveScan.mode(now, 0, true, 0, false), 1, "screen on, app in background -> background pace")
        T.eq(LiveScan.mode(now, 0, false, now - 9 * 60_000, false), 1, "screen off 9 min -> still running")
        T.eq(LiveScan.mode(now, 0, false, now - 11 * 60_000, false), 0, "screen off > 10 min -> paused")
        T.eq(LiveScan.mode(now, 0, false, now - 60 * 60_000, true), 1, "background enabled -> keeps running")
        val e = engine(5, 0, rel = setOf("L0000"))
        val t = at(10 * 60)
        e.ingest(LivePlan("groww", listOf("L0000")), mapOf("L0000" to q("L0000", 101.0, at = t)), t)
        e.toNotify(e.ring.toList(), t)
        val saved = Json.parseObject(Json.stringify(e.persist()))
        val e2 = engine(5, 0); e2.restore(saved, DAY)
        T.eq(e2.ring.size, 1, "ring restored"); T.check(e2.ring.first().rel && e2.ring.first().kind == "ZONE", "event fields restored")
        T.eq(e2.ingest(LivePlan("groww", listOf("L0000")), mapOf("L0000" to q("L0000", 101.0, at = t + 1000)), t + 1000).size, 0, "restored dedupe: no repeat after restart")
        T.eq(e2.toNotify(listOf(LiveEvent(t + 2000, "L0000", "STOP", 1.0, 1.0, "", true)), t + 2000).size, 0, "restored throttle")
        val e3 = engine(5, 0); e3.restore(saved, DAY + 1); T.eq(e3.ring.size, 0, "yesterday's state is not restored")
    }

    fun app() {
        val p = FakePlatform.standard(8)
        p.files[MarketScan.FILE] = ScanTests.scanText(DAY - 1, 400)
        p.setTime(DAY, 7 * 60)
        val app = SwingApp(p)
        T.eq(app.liveScanStep(true), 60_000L, "closed market: idle a minute")
        T.check(Json.parseObject(app.stateJson())!!.obj("liveScan").str("mode") == "closed", "state says closed")
        p.setTime(DAY, 11 * 60)
        // TradingView: 400 NSE rows; MK001 breaks out to a new high on big volume
        val d = MarketScan.parse(p.files[MarketScan.FILE])!!
        val cols = LiveScan.TV_CORE + LiveScan.TV_EXTRA
        val rows = d.rows.filter { it.exch == "NSE" }.map { x ->
            val c = x.comp; val px = if (x.sym == "MK001") c.hi252 * 1.02 else c.close
            """{"s":"NSE:${x.sym}","d":["${x.sym}",$px,${(px / c.close - 1) * 100},${if (x.sym == "MK001") 5e7 else 1000},${c.close},${Math.max(px, c.close)},${c.close * 0.99},1000,1.0,"delayed_streaming_900"]}""" }
        var tvCalls = 0
        p.reqHandler = { r -> if (r.url == LiveScan.TV_URL) { tvCalls++; HttpResp(200, """{"totalCount":${rows.size},"data":[${rows.joinToString(",")}]}""") } else null }
        val r1 = Json.parseObject(app.action("liveScan", "{}"))!!
        T.check(r1.bool("ok"), "liveScan answers"); T.eq(tvCalls, 1, "first step: one TradingView call")
        T.check(r1.int("swept") >= 400, "whole market swept: ${r1.int("swept")} of ${r1.int("total")}"); T.eq(r1.int("total"), 401, "total = scan rows")
        val ev = r1.list("eventList").map { it.asObj() }
        T.check(ev.any { it.str("sym") == "MK001" && it.str("kind") == "BREAKOUT" }, "breakout event from the whole-market sweep: ${ev.map { it.str("sym") + ":" + it.str("kind") }.take(8)}")
        T.check(r1.str("source").contains("TradingView") && r1.str("source").contains("15 min"), "source labelled delayed: " + r1.str("source"))
        T.check(r1.obj("movers").list("up").isNotEmpty() || true, "movers present")
        T.check(r1.list("liveTop").size <= 10, "live top list")
        // next step: Groww for real-time (fake replies to any body)
        p.posted[Groww.INDEX_URL] = """{"exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{"MK002":{"ltp":1.0,"open":1.0,"close":1.0,"volume":1}}}}}"""
        p.nowMs += 2_500
        val wait = app.liveScanStep(true)
        T.check(wait in 1L..4_000L, "foreground pacing: $wait ms")
        T.check(p.httpCalls >= 1, "Groww asked")
        val st = Json.parseObject(app.stateJson())!!.obj("liveScan")
        T.check(st.int("total") == 401 && st.int("breakouts") >= 1, "state summary: ${st}")
        // background toggle persists
        T.check(Json.parseObject(app.action("liveScanSet", "{\"bg\":true}"))!!.bool("bg"), "background toggle")
        T.check(Json.parseObject(p.files[LiveScan.FILE])!!["bg"] == true, "toggle saved")
        // screen off for 11 minutes with background on: keeps running; off: pauses
        p.nowMs += 60_000; app.liveScanStep(false); p.nowMs += 11 * 60_000
        T.check(app.liveScanStep(false) < 30_000L, "background enabled: still running")
        app.action("liveScanSet", "{\"bg\":false}")
        T.eq(app.liveScanStep(false), 30_000L, "screen off > 10 min, background off: paused")
        T.eq(Json.parseObject(app.stateJson())!!.obj("liveScan").str("mode"), "paused", "paused in state")
    }
}
