package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*
import java.util.Random

object UnitTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "json" to ::json, "indicators" to ::indicators, "clock" to ::clock, "yahoo" to ::yahoo, "series+cache" to ::seriesCache,
        "exitModel" to ::exitModel, "sizing" to ::sizing, "strategies" to ::strategies, "gate" to ::gate,
        "backtest-no-lookahead" to ::noLookAhead, "backtest-equals-live" to ::liveEquals, "backtest-speed" to ::speed,
        "import" to ::importParser, "advisor" to ::advisor, "fmt" to ::fmt
    )

    // ------------------------------------------------------------------ JSON
    fun json() {
        val o = Json.parse("""{"a":1,"b":[true,false,null,-2.5e2,"x"],"c":{"d":"e"},"e":""}""").asObj()
        T.eq(o["a"], 1.0, "number -> Double")
        T.eq(o.list("b"), listOf(true, false, null, -250.0, "x"), "array")
        T.eq(o.obj("c")["d"], "e", "nested object")
        T.eq(Json.parse("  [ ]  "), ArrayList<Any?>(), "empty array with whitespace")
        T.eq(Json.parse("\"\\u0041\\u00e9\\u20B9\\n\\t\\\"\\\\\\/\\b\\f\\r\""), "Aé₹\n\t\"\\/\b\u000C\r", "all escapes incl \\uXXXX")
        T.eq(Json.parse("\"\\ud83d\\ude00\""), "\uD83D\uDE00", "surrogate pair escape")
        val nasty = listOf(
            "", "plain", "quote\" back\\slash /slash", "line\nbreak\r\ttab", "\u0000\u0001\u001f ctrl", "₹1,23,456.50 — M&M <b>",
            "emoji \uD83D\uDE80 रिलायंस 日本", "\u2028\u2029 separators", "{\"looks\":\"like json\"}", "\\u0041 literal", "ends with backslash\\",
            "\uFEFFbom inside", "'single' `tick` \$dollar %percent"
        )
        for (s in nasty) {
            val txt = Json.stringify(linkedMapOf("k" to s, s to listOf(s)))
            val back = Json.parse(txt).asObj()
            T.eq(back["k"], s, "string round-trip")
            T.eq(back.list(s).firstOrNull(), s, "key round-trip")
            T.check(txt.none { it < ' ' }, "no raw control chars in output")
        }
        val r = Random(5)
        for (n in 0 until 300) {
            val sb = StringBuilder()
            for (k in 0 until r.nextInt(30)) sb.append((r.nextInt(0x3000)).toChar())
            val s = sb.toString().filter { !Character.isSurrogate(it) }
            T.eq(Json.parse(Json.stringify(s)), s, "random string round-trip")
        }
        T.eq(Json.stringify(listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)), "[0,0,0]", "NaN/Infinity written as 0")
        T.eq(Json.stringify(linkedMapOf("a" to 1.0, "b" to 2.50, "c" to 1762771234567L, "d" to -0.75, "e" to 100000.0, "f" to 3)), """{"a":1,"b":2.5,"c":1762771234567,"d":-0.75,"e":100000,"f":3}""", "plain numbers")
        T.eq(Json.parse("1762771234567"), 1.762771234567E12, "epoch ms exact")
        T.eq((Json.parse(Json.stringify(1762771234567L)) as Double).toLong(), 1762771234567L, "long round-trip")
        T.eq(Json.stringify(0.1 + 0.2), "0.3", "rounded to 6 decimals")
        T.eq(Json.stringify(-0.0000001), "-1.0E-7", "tiny number still valid JSON")
        T.check(Json.parseOrNull(Json.stringify(1e-7)) != null && Json.parseOrNull(Json.stringify(1e22)) != null, "exponent forms parse back")
        for (bad in listOf("", " ", "{", "[1,2", "{\"a\":}", "{\"a\" 1}", "[1,]", "{,}", "nul", "tru", "\"unterminated", "\"bad \\x escape\"", "\"\\u12G4\"",
            "01x", "1.", "-", "1e", "[1] 2", "{\"a\":1}}", "<html>", "\"tab\tinside\"", "{'a':1}", "NaN", "[Infinity]")) {
            T.check(Json.parseOrNull(bad) == null, "malformed rejected: $bad")
        }
        T.check(Json.parseOrNull("[".repeat(5000) + "]".repeat(5000)) == null, "absurd nesting rejected without crashing")
        T.eq(Json.parse("\uFEFF{\"a\":1}").asObj()["a"], 1.0, "BOM tolerated")
        val m = Json.parseObject("""{"n":"12.5","b":1,"s":7,"x":null}""")!!
        T.eq(m.num("n"), 12.5, "num from string"); T.eq(m.bool("b"), true, "bool from number"); T.eq(m.str("s"), "7", "str from number")
        T.eq(m.num("x", 3.0), 3.0, "default on null"); T.eq(m.int("missing", 4), 4, "default on missing")
    }

    // ------------------------------------------------------------------ indicators (hand-computed)
    fun indicators() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        T.near(Ind.sma(a, 4, 3), 4.0, 1e-12, "SMA(3) of 3,4,5")
        T.near(Ind.sma(a, 2, 3), 2.0, 1e-12, "SMA(3) of 1,2,3")
        T.check(Ind.sma(a, 1, 3).isNaN(), "SMA NaN without history")
        // EMA(3): seed SMA(1,2,3)=2; k=0.5; 4 -> 3; 5 -> 4
        T.near(Ind.ema(a, 3, 3), 3.0, 1e-12, "EMA(3) step 1")
        T.near(Ind.ema(a, 4, 3), 4.0, 1e-12, "EMA(3) step 2")
        T.near(Ind.ema(doubleArrayOf(10.0, 20.0, 30.0, 10.0), 3, 3), 15.0, 1e-12, "EMA(3): seed 20, then 10*0.5+20*0.5")
        // Wilder RSI(2) on 10,11,10,12,13: changes +1,-1,+2,+1; seed g=.5 l=.5; -> g=1.25 l=.25; -> g=1.125 l=.125; RS=9 -> 90
        val c = doubleArrayOf(10.0, 11.0, 10.0, 12.0, 13.0)
        T.near(Ind.rsi(c, 2, 2), 50.0, 1e-9, "RSI(2) seed: equal gain and loss")
        T.near(Ind.rsi(c, 3, 2), 100 - 100 / (1 + 1.25 / 0.25), 1e-9, "RSI(2) after +2 = 83.33")
        T.near(Ind.rsi(c, 4, 2), 90.0, 1e-9, "RSI(2) hand value 90")
        T.near(Ind.rsi(doubleArrayOf(5.0, 6.0, 7.0, 8.0), 3, 2), 100.0, 1e-9, "RSI all gains = 100")
        T.near(Ind.rsi(doubleArrayOf(8.0, 7.0, 6.0, 5.0), 3, 2), 0.0, 1e-9, "RSI all losses = 0")
        T.near(Ind.rsi(doubleArrayOf(5.0, 5.0, 5.0, 5.0), 3, 2), 50.0, 1e-9, "RSI flat = 50")
        T.check(Ind.rsi(c, 1, 2).isNaN(), "RSI NaN without history")
        // classic 14-period RSI example (Wilder's textbook closes) -> 70.46 at the 15th close
        val w = doubleArrayOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03)
        T.near(Ind.rsi(w, 14, 14), 70.46, 0.03, "RSI(14) textbook 70.46")
        T.near(Ind.rsi(w, 15, 14), 66.25, 0.03, "RSI(14) textbook 66.25")
        // ATR(2): TRs 2.5, 2, 2 -> seed 2.25 -> (2.25+2)/2 = 2.125
        val h = doubleArrayOf(10.0, 12.0, 13.0, 14.0)
        val l = doubleArrayOf(9.0, 10.0, 11.0, 13.0)
        val cc = doubleArrayOf(9.5, 11.0, 12.0, 13.5)
        T.near(Ind.trueRange(h, l, cc, 1), 2.5, 1e-12, "TR uses previous close")
        T.near(Ind.atr(h, l, cc, 2, 2), 2.25, 1e-12, "ATR(2) seed")
        T.near(Ind.atr(h, l, cc, 3, 2), 2.125, 1e-12, "ATR(2) Wilder step")
        T.check(Ind.atr(h, l, cc, 1, 2).isNaN(), "ATR NaN without history")
        T.near(Ind.highest(doubleArrayOf(3.0, 9.0, 2.0, 7.0, 5.0), 4, 3), 7.0, 0.0, "highest of last 3")
        T.near(Ind.lowest(doubleArrayOf(3.0, 9.0, 2.0, 7.0, 5.0), 4, 3), 2.0, 0.0, "lowest of last 3")
        T.near(Ind.highest(doubleArrayOf(3.0, 9.0, 2.0, 7.0, 5.0), 4, 5), 9.0, 0.0, "highest of all 5")
        T.near(Ind.ret(doubleArrayOf(100.0, 90.0, 110.0, 121.0), 3, 2), 121.0 / 90 - 1, 1e-12, "2-day return")
        T.check(Ind.ret(a, 1, 2).isNaN(), "return NaN without history")
        T.near(Ind.avgTurnover(doubleArrayOf(100.0, 200.0, 300.0), doubleArrayOf(10.0, 20.0, 30.0), 2, 2), (200 * 20 + 300 * 30) / 2.0, 1e-9, "average turnover")
        T.near(Ind.ibs(10.0, 8.0, 9.5), 0.75, 1e-12, "IBS"); T.near(Ind.ibs(10.0, 10.0, 10.0), 0.5, 0.0, "IBS zero range")
        T.near(Ind.relVol(500.0, 1000.0, 0.5), 1.0, 1e-12, "relVol half session"); T.near(Ind.relVol(100.0, 1000.0, 0.05), 1.0, 1e-12, "relVol floor 0.1")
        T.near(Ind.relVol(3000.0, 1000.0, 1.0), 3.0, 1e-12, "relVol full day"); T.near(Ind.relVol(5.0, 0.0, 1.0), 0.0, 0.0, "relVol zero average")
        // Wilder window: value at i must not depend on bars beyond the window nor on any future bar
        val r = Random(1)
        val big = DoubleArray(400) { 100 + r.nextDouble() * 10 }
        val x1 = Ind.rsi(big, 300, 14); big[350] = 999.0; big[10] = 1.0
        T.near(Ind.rsi(big, 300, 14), x1, 0.0, "RSI ignores future bars and bars before its window")
    }

    // ------------------------------------------------------------------ clock
    fun clock() {
        val tue = java.time.LocalDate.of(2026, 11, 10).toEpochDay().toInt()
        fun ist(day: Int, h: Int, m: Int) = java.time.LocalDate.ofEpochDay(day.toLong()).atTime(h, m).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        T.eq(Clock.msAt(tue, 9 * 60 + 15), ist(tue, 9, 15), "msAt matches java.time IST")
        T.eq(Clock.epochDay(ist(tue, 0, 1)), tue, "epochDay just after IST midnight"); T.eq(Clock.epochDay(ist(tue, 23, 59)), tue, "epochDay before midnight")
        T.eq(Clock.minuteOfDay(ist(tue, 14, 45)), 885, "minute of day")
        T.eq(Clock.dow(tue), 1, "10 Nov 2026 is a Tuesday"); T.check(!Clock.isWeekday(tue + 4) && !Clock.isWeekday(tue + 5) && Clock.isWeekday(tue + 6), "weekend")
        T.check(!Clock.isMarketOpen(ist(tue, 9, 14)) && Clock.isMarketOpen(ist(tue, 9, 15)) && Clock.isMarketOpen(ist(tue, 15, 29)) && !Clock.isMarketOpen(ist(tue, 15, 30)), "market hours 09:15-15:30")
        T.check(!Clock.isMarketOpen(ist(tue + 4, 11, 0)), "closed on Saturday")
        T.eq(Clock.nextWake(ist(tue, 6, 0)), ist(tue, 8, 55), "wake: brief 08:55"); T.eq(Clock.nextWake(ist(tue, 8, 55)), ist(tue, 9, 20), "wake: first scan 09:20")
        T.eq(Clock.nextWake(ist(tue, 9, 20)), ist(tue, 9, 31), "wake: 09:31"); T.eq(Clock.nextWake(ist(tue, 14, 31)), ist(tue, 14, 46), "wake: 14:46")
        T.eq(Clock.nextWake(ist(tue, 15, 16)), ist(tue, 15, 40), "wake: EOD 15:40"); T.eq(Clock.nextWake(ist(tue, 15, 40)), ist(tue + 1, 8, 55), "wake: next morning")
        T.eq(Clock.nextWake(ist(tue + 3, 15, 40)), ist(tue + 6, 8, 55), "wake: Friday evening -> Monday"); T.eq(Clock.nextWake(ist(tue + 4, 12, 0)), ist(tue + 6, 8, 55), "wake: Saturday -> Monday")
        var t = ist(tue, 0, 0); var n = 0
        while (Clock.epochDay(Clock.nextWake(t)) == tue) { t = Clock.nextWake(t); n++ }
        T.eq(n, 27, "27 wakes per trading day (brief + 25 scans + EOD)")
        T.eq(Clock.scanSlot(9 * 60 + 19), -1, "no slot before 09:20"); T.eq(Clock.scanSlot(9 * 60 + 20), 0, "slot 0 at 09:20"); T.eq(Clock.scanSlot(9 * 60 + 30), 0, "slot 0 till 09:30")
        T.eq(Clock.scanSlot(9 * 60 + 31), 1, "slot 1 at 09:31"); T.eq(Clock.scanSlot(15 * 60 + 16), 24, "slot 24 at 15:16"); T.eq(Clock.scanSlot(15 * 60 + 31), -1, "no slot after close")
        T.eq(Clock.scanSlotKey(ist(tue + 4, 11, 0)), -1L, "no slot on weekends")
        T.near(Clock.sessionFraction(ist(tue, 12, 22)), 187.0 / 375, 1e-9, "session fraction"); T.near(Clock.sessionFraction(ist(tue, 16, 0)), 1.0, 0.0, "fraction capped")
        T.check(Clock.weekIndex(tue) == Clock.weekIndex(tue - 1) && Clock.weekIndex(tue - 1) != Clock.weekIndex(tue - 2), "week index changes on Monday")
        T.eq(Clock.dayLabel(tue), "Tue 10 Nov", "day label")
    }

    // ------------------------------------------------------------------ Yahoo parsing + URLs
    fun yahoo() {
        T.eq(Yahoo.dailyUrl(Params.YAHOO_HOST_1, "M&M"), "https://query1.finance.yahoo.com/v8/finance/chart/M%26M.NS?range=2y&interval=1d", "M&M encoded")
        T.eq(Yahoo.intradayUrl(Params.YAHOO_HOST_2, "BAJAJ-AUTO"), "https://query2.finance.yahoo.com/v8/finance/chart/BAJAJ-AUTO.NS?range=5d&interval=15m", "hyphen kept")
        T.eq(Yahoo.dailyUrl(Params.YAHOO_HOST_1, "^NSEI"), "https://query1.finance.yahoo.com/v8/finance/chart/%5ENSEI?range=2y&interval=1d", "index encoded, no .NS")
        T.eq(Yahoo.encode("ARE&M.NS"), "ARE%26M.NS", "ARE&M"); T.eq(Yahoo.encode("A B/c?d"), "A%20B%2Fc%3Fd", "reserved chars")
        for (bad in listOf(null, "", "garbage", "<html>429</html>", "{}", "{\"chart\":null}", "{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}",
            "{\"chart\":{\"result\":[],\"error\":null}}", "{\"chart\":{\"result\":[{\"meta\":{}}],\"error\":null}}",
            "{\"chart\":{\"result\":[{\"timestamp\":[1,2],\"indicators\":{\"quote\":[{}]}}]}}",
            "{\"chart\":{\"result\":[{\"timestamp\":[1,2],\"indicators\":{\"quote\":[{\"close\":[null,null]}]}}]}}",
            "{\"chart\":{\"result\":[{\"timestamp\":\"x\",\"indicators\":5}]}}", "{\"chart\":{\"result\":[{\"timestamp\":[1700000000],\"indicators\":{\"quote\":[{\"close\":[10]}]}}]")) {
            T.check(Yahoo.parse(bad) == null && Yahoo.daily("X", bad, 99999) == null && Yahoo.intraday("X", bad) == null, "rejected: " + (bad ?: "null").take(50))
        }
        val d0 = 20000
        fun ts(day: Int, min: Int) = Clock.msAt(day, min) / 1000
        val txt = """{"chart":{"result":[{"meta":{"regularMarketPrice":106.5,"regularMarketTime":${ts(d0 + 1, 600)}},
            "timestamp":[${ts(d0, 555)},${ts(d0, 570)},${ts(d0, 585)},${ts(d0 + 1, 555)},${ts(d0 + 1, 570)},null],
            "indicators":{"quote":[{"open":[100,101,null,104,null,1],"high":[102,103,null,106,null,1],"low":[99,100.5,null,103,105,1],
            "close":[101,102.5,null,105,106,1],"volume":[1000,null,null,500,700]}]}}],"error":null}}"""
        val q = Yahoo.intraday("X", txt)!!
        T.eq(q.days.n, 2, "two days aggregated; null rows skipped")
        T.eq(listOf(q.days.o[0], q.days.h[0], q.days.l[0], q.days.c[0], q.days.v[0]), listOf(100.0, 103.0, 99.0, 102.5, 1000.0), "day 1 OHLCV, null volume = 0")
        T.eq(listOf(q.days.o[1], q.days.h[1], q.days.l[1], q.days.c[1], q.days.v[1]), listOf(104.0, 106.5, 103.0, 106.5, 1200.0), "day 2: missing open/high filled, meta price folded in")
        T.eq(q.ltp, 106.5, "ltp from meta"); T.eq(q.lastTsMs, ts(d0 + 1, 600) * 1000, "last timestamp from meta")
        val dl = Yahoo.daily("X", txt, d0 + 1)!!
        T.eq(dl.n, 1, "daily drops the bar of 'today'"); T.eq(dl.d[0], d0, "kept day")
    }

    // ------------------------------------------------------------------ series text form + file cache
    fun seriesCache() {
        val s = Series("ABC", 4)
        val r = Random(3)
        var day = 20000
        var px = 1234.55
        for (k in 0 until 520) { day += 1 + r.nextInt(3); px *= 1 + r.nextGaussian() * 0.02; s.add(day, px * 0.99, px * 1.02, px * 0.97, px, Math.rint(r.nextDouble() * 5e7)) }
        T.eq(s.n, 520, "520 bars added (grows)")
        val txt = s.encode()
        T.check(txt.length < 520 * 34, "compact: ${txt.length / 520} chars per bar")
        val b = Series.decode("ABC", txt)
        T.eq(b.n, 520, "decode count")
        var ok = true
        for (k in 0 until 520) if (b.d[k] != s.d[k] || Math.abs(b.c[k] - s.c[k]) > 0.0051 || Math.abs(b.l[k] - s.l[k]) > 0.0051 || b.v[k] != s.v[k]) ok = false
        T.check(ok, "decode equals encode to the paisa")
        T.eq(Series.decode("X", "zz,!!;1,2").n, 0, "garbage decodes to nothing"); T.eq(Series.decode("X", "").n, 0, "empty")
        T.eq(Series.decode("X", txt.substring(0, txt.length / 2)).n in 255..262, true, "truncated text keeps the clean part")
        T.check(!s.add(day, 1.0, 1.0, 1.0, 1.0, 1.0) && !s.add(day + 1, 1.0, 1.0, 1.0, 0.0, 1.0) && !s.add(day + 1, 1.0, 1.0, 1.0, Double.NaN, 1.0), "rejects same day / bad close")
        T.check(s.add(day + 1, Double.NaN, 5.0, 50.0, 10.0, -3.0) && s.h[s.n - 1] == 10.0 && s.l[s.n - 1] == 10.0 && s.o[s.n - 1] == 10.0 && s.v[s.n - 1] == 0.0, "sanitises broken OHLC")
        T.eq(s.indexOf(s.d[77]), 77, "indexOf"); T.eq(s.indexOf(1), -1, "indexOf missing"); T.eq(s.head(10).n, 10, "head"); T.eq(s.tail(100).d[99], s.d[s.n - 1], "tail")
        val p = FakePlatform()
        val c1 = DailyCache(p)
        for (sym in listOf("AAA", "BBB", "M&M", "^NSEI", "CCC", "DDD", "EEE", "FFF")) c1.put(sym, b, 777)
        c1.put("EMPTY", Series("EMPTY", 1), 778)
        c1.flush()
        T.check(p.files.keys.all { it.startsWith("daily_cache_") } && p.files.size in 2..Params.CACHE_SHARDS, "split over ${p.files.size} shard files")
        T.check(p.files.values.all { Json.parseOrNull(it) != null }, "shards are valid JSON")
        val c2 = DailyCache(p)
        T.eq(c2.get("M&M")?.n, 520, "reload from files"); T.eq(c2.keyOf("^NSEI"), 777, "fetch key kept"); T.eq(c2.get("EMPTY"), null, "failed symbol has no series"); T.eq(c2.keyOf("EMPTY"), 778, "but remembers its key")
        val firstFile = p.files.keys.sorted().first()
        p.files[firstFile] = p.files[firstFile]!!.substring(0, 500)
        p.files[p.files.keys.sorted().last()] = "\u0000\u0001 not json"
        val c3 = DailyCache(p)
        T.check(c3.size() < 9, "corrupt shards skipped, rest loads (${c3.size()} of 9) without throwing")
        c3.put("NEW", b, 1); c3.flush()
        T.check(DailyCache(p).get("NEW") != null, "cache usable after corruption")
        c2.retain(setOf("AAA")); c2.flush()
        T.eq(DailyCache(p).size(), 1, "retain drops unused symbols")
    }

    // ------------------------------------------------------------------ exit model (hand-checked)
    fun exitModel() {
        val cost = 0.25
        // entry 100, sl 96, t1 104, t2 108
        var p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        T.eq(ExitModel.step(p, 99.0, 101.0, 95.0, 97.0, false), ExitModel.STOP, "low through stop")
        T.near(p.exitPx, 96.0, 0.0, "filled at the stop"); T.near(ExitModel.netPct(p, cost), -4.25, 1e-9, "-4% - 0.25% charges")
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        ExitModel.step(p, 94.0, 99.0, 93.0, 98.0, false)
        T.near(p.exitPx, 94.0, 0.0, "gap below stop exits at the open"); T.near(ExitModel.netPct(p, cost), -6.25, 1e-9, "gap loss -6.25%")
        // stop is checked before targets in the same bar
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        T.eq(ExitModel.step(p, 100.0, 109.0, 95.5, 108.0, false), ExitModel.STOP, "stop wins when a bar touches both")
        // T1 day, then stopped at cost: 0.5*4% + 0.5*0% - 0.25 = 1.75%
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        T.eq(ExitModel.step(p, 100.0, 105.0, 99.0, 104.5, false), ExitModel.T1, "T1 event")
        T.check(p.t1Done && p.sl == 100.0 && !p.closed, "half booked, stop to cost")
        T.eq(ExitModel.step(p, 101.0, 102.0, 99.5, 100.5, false), ExitModel.STOP, "stopped at cost next day")
        T.near(ExitModel.netPct(p, cost), 1.75, 1e-9, "T1 then cost = +1.75%"); T.eq(ExitModel.resultText(p.reason, p.t1Done), "T1 hit, rest at entry", "result text")
        // T1 then T2: 0.5*4 + 0.5*8 - 0.25 = 5.75
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        ExitModel.step(p, 100.0, 104.0, 99.0, 103.0, false)
        T.eq(ExitModel.step(p, 103.0, 108.5, 102.0, 108.0, false), ExitModel.T2, "T2 event")
        T.near(ExitModel.netPct(p, cost), 5.75, 1e-9, "T1 + T2 = +5.75%")
        // both targets in one bar
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        T.eq(ExitModel.step(p, 101.0, 110.0, 100.5, 109.0, false), ExitModel.T1 or ExitModel.T2, "T1 and T2 same bar"); T.near(ExitModel.netPct(p, cost), 5.75, 1e-9, "same-bar T1+T2")
        // gap above T1 fills at the open: 0.5*5% ...
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        ExitModel.step(p, 105.0, 106.0, 104.5, 105.5, false)
        T.near(p.realized, 0.025, 1e-12, "gap over T1 sells half at the open (105)")
        // time exit on the last day at the close: after T1: 0.5*4 + 0.5*2 - .25 = 2.75 ; without T1: +2 - .25
        T.eq(ExitModel.step(p, 105.0, 106.0, 101.0, 102.0, true), ExitModel.TIME, "time exit")
        T.near(ExitModel.netPct(p, cost), 0.5 * 5 + 0.5 * 2 - 0.25, 1e-9, "time exit after T1")
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.5)
        ExitModel.step(p, 100.0, 103.0, 99.0, 102.0, true)
        T.near(ExitModel.netPct(p, cost), 1.75, 1e-9, "plain time exit +2% - charges"); T.eq(ExitModel.resultText(p.reason, false), "Time exit on day 6", "time text")
        // quantity 1: T1 only moves the stop
        p = Pos().open(100.0, 96.0, 104.0, 108.0, 0.0)
        ExitModel.step(p, 100.0, 105.0, 99.0, 104.0, false)
        T.check(p.t1Done && p.sl == 100.0 && p.realized == 0.0, "qty 1: nothing sold at T1, stop to cost")
        ExitModel.step(p, 104.0, 108.0, 103.0, 107.0, false)
        T.near(ExitModel.netPct(p, cost), 7.75, 1e-9, "qty 1: full size out at T2")
        // no targets (weekly leaders): only stop or time
        p = Pos().open(100.0, 95.0, 0.0, 0.0, 0.5)
        T.eq(ExitModel.step(p, 100.0, 150.0, 99.0, 140.0, false), ExitModel.NONE, "no target => no event")
        T.eq(ExitModel.step(p, 140.0, 141.0, 139.0, 140.0, true), ExitModel.TIME, "then time exit"); T.near(ExitModel.netPct(p, cost), 39.75, 1e-9, "full move kept")
        T.eq(ExitModel.step(p, 1.0, 1.0, 1.0, 1.0, true), ExitModel.NONE, "closed position ignores further bars")
        // Trade rupee P&L agrees with the model: 10 sh @100, half at 104, rest at 100, charges 0.25% of 1000
        val t = Trade(); t.entry = 100.0; t.qty = 10; t.t1 = 104.0; t.t1Done = true; t.t1Px = 104.0
        T.near(t.pnlAt(100.0, cost), 5 * 4.0 - 2.5, 1e-9, "rupee P&L T1 then cost = 17.50")
        t.qty = 11; T.eq(t.halfQty(), 5, "odd quantity sells floor(half)")
        T.near(t.pnlAt(108.0, cost), 5 * 4.0 + 6 * 8.0 - 11 * 100 * 0.0025, 1e-9, "odd quantity T1+T2")
        t.qty = 1; T.eq(t.halfQty(), 0, "qty 1 sells nothing at T1"); T.near(t.pnlAt(108.0, cost), 8.0 - 0.25, 1e-9, "qty 1 P&L")
    }

    // ------------------------------------------------------------------ position sizing
    fun sizing() {
        // capital 1,00,000 risk 0.75% = 750; entry 500 sl 485: per share 15 + 1.25 = 16.25 -> 46; cap 25,000/500 = 50 -> 46
        T.eq(SwingApp.positionSize(100000.0, 0.75, 0.25, 500.0, 485.0), 46, "hand-checked size")
        // tight stop: entry 200 sl 198: per share 2.5 -> 300; cap 25,000/200 = 125
        T.eq(SwingApp.positionSize(100000.0, 0.75, 0.25, 200.0, 198.0), 125, "capped at 25% of capital")
        T.eq(SwingApp.positionSize(100000.0, 0.75, 0.25, 30000.0, 29000.0), 0, "one share would break the risk limit -> skip")
        T.eq(SwingApp.positionSize(100000.0, 0.75, 0.25, 100.0, 100.0), 0, "zero risk rejected"); T.eq(SwingApp.positionSize(100000.0, 0.75, 0.25, 100.0, 120.0), 0, "stop above entry rejected")
        T.eq(SwingApp.positionSize(0.0, 0.75, 0.25, 100.0, 95.0), 0, "no capital"); T.eq(SwingApp.positionSize(1e5, 0.75, 0.25, Double.NaN, 95.0), 0, "NaN entry")
        val r = Random(11)
        var worst = 0.0
        for (k in 0 until 20000) {
            val cap = 5000 + r.nextDouble() * 2e6
            val risk = 0.1 + r.nextDouble() * 3
            val cost = r.nextDouble() * 0.6
            val entry = 50 + r.nextDouble() * 9000
            val sl = entry * (1 - (0.005 + r.nextDouble() * 0.09))
            val q = SwingApp.positionSize(cap, risk, cost, entry, sl)
            val loss = q * (entry - sl) + q * entry * cost / 100
            if (loss > cap * risk / 100 * (1 + 1e-9) || q * entry > cap * 0.25 * (1 + 1e-9) || q < 0) worst = maxOf(worst, loss / (cap * risk / 100))
        }
        T.check(worst == 0.0, "20,000 random cases: loss at stop incl. charges never exceeds riskPct, never above 25% of capital")
    }

    // ------------------------------------------------------------------ strategies on hand-built series
    /** Steady uptrend of n bars ending at `last`, 1% daily range, big volume. */
    private fun trend(sym: String, n: Int, last: Double, dailyPct: Double, vol: Double = 2e6, startDay: Int = 20002, zig: Double = 0.0): Series {
        val s = Series(sym, n + 20)
        var day = startDay
        for (k in 0 until n) {
            while (!Clock.isWeekday(day)) day++
            val c = last / Math.pow(1 + dailyPct / 100, (n - 1 - k).toDouble()) * (1 + (if ((n - 1 - k) % 2 == 1) -zig else 0.0) / 100)
            s.add(day, c * 0.997, c * 1.005, c * 0.995, c, vol)
            day++
        }
        return s
    }

    private fun append(s: Series, o: Double, h: Double, l: Double, c: Double, v: Double) {
        var day = s.lastDay() + 1
        while (!Clock.isWeekday(day)) day++
        s.add(day, o, h, l, c, v)
    }

    private fun eval(st: Strategy, s: Series, ctx: Ctx): Setup? {
        val f = Feat()
        f.compute(s, s.n - 1)
        return Strategies.evaluate(st, s, s.n - 1, f, ctx)
    }

    fun strategies() {
        val bull = Ctx().also { it.regime = 1; it.momCutoff = 0.10; it.leaderCutoff = 0.05 }
        // ---- DIP_UPTREND: uptrend then three hard down days closing on the low
        val dip = trend("DIP", 260, 500.0, 0.15)
        append(dip, 499.0, 500.0, 488.0, 489.0, 2e6); append(dip, 488.0, 489.0, 477.0, 478.0, 2e6); append(dip, 477.0, 478.0, 466.0, 467.0, 2e6)
        val su = eval(DipUptrend, dip, bull)
        T.check(su != null, "DIP fires after 3 down days in an uptrend")
        if (su != null) {
            val i = dip.n - 1
            val atr = Ind.atr(dip.h, dip.l, dip.c, i, 14)
            T.near(su.entry, 467.0, 0.0, "entry = close"); T.near(su.sl, 467.0 - minOf(2 * atr, 467 * 0.08), 1e-9, "stop 2xATR capped 8%")
            T.near(su.t1, 467.0 + 0.75 * atr, 1e-9, "T1 = +0.75 ATR"); T.near(su.t2, maxOf(Ind.sma(dip.c, i, 5), 467 + 1.5 * atr), 1e-9, "T2 = max(5DMA, +1.5 ATR)")
            T.check(su.reasons.size in 2..5 && su.sl < su.entry && su.t1 > su.entry && su.t2 >= su.t1, "sane levels and reasons")
        }
        T.check(eval(DipUptrend, dip, Ctx().also { it.regime = -1 }) == null, "BEAR regime blocks the buy")
        T.check(eval(DipUptrend, dip, Ctx().also { it.regime = 0 }) != null, "NEUTRAL regime still allowed")
        val notLow = trend("DIP2", 260, 500.0, 0.15)
        append(notLow, 499.0, 500.0, 488.0, 489.0, 2e6); append(notLow, 488.0, 489.0, 477.0, 478.0, 2e6); append(notLow, 477.0, 478.0, 466.0, 474.0, 2e6)
        T.check(eval(DipUptrend, notLow, bull) == null, "no DIP when the close is off the low (IBS >= 0.3)")
        val thin = trend("THIN", 260, 500.0, 0.15, vol = 1e5)
        append(thin, 499.0, 500.0, 488.0, 489.0, 1e5); append(thin, 488.0, 489.0, 477.0, 478.0, 1e5); append(thin, 477.0, 478.0, 466.0, 467.0, 1e5)
        T.check(eval(DipUptrend, thin, bull) == null, "turnover under ₹25 crore blocked (₹5 crore here)")
        val penny = trend("PENNY", 260, 40.0, 0.15, vol = 9e7)
        append(penny, 39.9, 40.0, 39.0, 39.1, 9e7); append(penny, 39.0, 39.1, 38.1, 38.2, 9e7); append(penny, 38.1, 38.2, 37.2, 37.3, 9e7)
        T.check(eval(DipUptrend, penny, bull) == null, "price under ₹50 blocked")
        val down = trend("DOWN", 260, 500.0, -0.15)
        append(down, 499.0, 500.0, 488.0, 489.0, 2e6); append(down, 488.0, 489.0, 477.0, 478.0, 2e6)
        T.check(eval(DipUptrend, down, bull) == null, "no DIP below the 200DMA")
        T.check(eval(DipUptrend, trend("SHORT", 150, 500.0, 0.15), bull) == null, "needs 201 bars")

        // ---- BREAKOUT_VOL: flat base then a big-volume close at a new high near the day's high
        val bo = trend("BO", 260, 300.0, 0.0)
        append(bo, 300.0, 309.5, 299.5, 309.0, 5e6)
        val b = eval(BreakoutVol, bo, bull)
        T.check(b != null, "BREAKOUT fires: 55-day closing high, 2.5x volume, close near high")
        if (b != null) {
            val atr = Ind.atr(bo.h, bo.l, bo.c, bo.n - 1, 14)
            T.near(b.sl, maxOf(299.5, 309.0 - 2 * atr), 1e-9, "stop = max(day low, entry - 2 ATR)")
            T.near(b.t1, 309.0 + (309.0 - b.sl), 1e-9, "T1 = +1R"); T.near(b.t2, 309.0 + 2 * (309.0 - b.sl), 1e-9, "T2 = +2R")
        }
        val boLowVol = trend("BO2", 260, 300.0, 0.0); append(boLowVol, 300.0, 309.5, 299.5, 309.0, 3.5e6)
        T.check(eval(BreakoutVol, boLowVol, bull) == null, "volume 1.75x is not enough")
        val boWeak = trend("BO3", 260, 300.0, 0.0); append(boWeak, 300.0, 315.0, 299.5, 304.0, 5e6)
        T.check(eval(BreakoutVol, boWeak, bull) == null, "close not in the top 30% of the range")
        val boFar = trend("BO4", 260, 300.0, 0.0); append(boFar, 300.0, 322.0, 299.5, 321.0, 5e6)
        T.check(eval(BreakoutVol, boFar, bull) == null, "more than 6% above the breakout level")
        // intraday projection: 2.2M shares by 40% of the session projects to 5.5M = 2.75x
        val boLive = trend("BO5", 260, 300.0, 0.0); append(boLive, 300.0, 309.5, 299.5, 309.0, 2.2e6)
        T.check(eval(BreakoutVol, boLive, bull) == null, "partial volume fails when treated as a full day")
        T.check(eval(BreakoutVol, boLive, Ctx().also { it.regime = 1; it.sessionFraction = 0.4 }) != null, "passes when projected for the elapsed session")

        // ---- MOM_PULLBACK: strong trend, 3 red days (~5% off the high), then an up day above yesterday's body
        val mp = trend("MP", 260, 800.0, 0.25)
        append(mp, 800.0, 801.0, 784.0, 786.0, 2e6); append(mp, 786.0, 787.0, 770.0, 772.0, 2e6); append(mp, 772.0, 773.0, 758.0, 760.0, 2e6)
        append(mp, 761.0, 775.0, 760.0, 774.0, 2e6)
        val m = eval(MomPullback, mp, bull)
        T.check(m != null, "MOM_PULLBACK fires on the turn-up day")
        if (m != null) {
            val atr = Ind.atr(mp.h, mp.l, mp.c, mp.n - 1, 14)
            T.near(m.sl, 774.0 - minOf(1.75 * atr, 774 * 0.08), 1e-9, "stop 1.75 ATR capped 8%"); T.near(m.t2 - m.entry, 2 * (m.entry - m.sl), 1e-9, "T2 = +2R")
        }
        T.check(eval(MomPullback, mp, Ctx().also { it.regime = 1; it.momCutoff = 5.0 }) == null, "not in the top momentum slice -> no signal")
        T.check(eval(MomPullback, mp, Ctx().also { it.regime = 1 }) == null, "no universe ranking -> no signal")
        val mpNoTurn = trend("MP2", 260, 800.0, 0.25)
        append(mpNoTurn, 800.0, 801.0, 784.0, 786.0, 2e6); append(mpNoTurn, 786.0, 787.0, 770.0, 772.0, 2e6); append(mpNoTurn, 772.0, 773.0, 758.0, 760.0, 2e6)
        append(mpNoTurn, 761.0, 772.5, 760.0, 771.0, 2e6)
        T.check(eval(MomPullback, mpNoTurn, bull) == null, "close below yesterday's body high (772) -> no signal")

        // ---- WEEKLY_LEADERS: only on the first bar of a week
        var wl = trend("WL", 260, 800.0, 0.25)
        while (Clock.dow(wl.lastDay()) != 4) append(wl, 800.0, 804.0, 797.0, 801.0, 2e6)   // extend to a Friday
        T.check(eval(WeeklyLeaders, wl, bull) == null, "not on a Friday")
        append(wl, 801.0, 806.0, 799.0, 804.0, 2e6)   // Monday
        val w = eval(WeeklyLeaders, wl, bull)
        T.check(w != null && w.t1 == 0.0 && w.t2 == 0.0, "fires on the first trading day of the week, no price targets")
        if (w != null) T.near(w.sl, 804.0 - minOf(2 * Ind.atr(wl.h, wl.l, wl.c, wl.n - 1, 14), 804 * 0.10), 1e-9, "stop 2 ATR")
        T.check(eval(WeeklyLeaders, wl, Ctx().also { it.regime = 1; it.leaderCutoff = 9.0 }) == null, "not among the leaders -> no signal")
        append(wl, 804.0, 808.0, 802.0, 806.0, 2e6)   // Tuesday
        T.check(eval(WeeklyLeaders, wl, bull) == null, "not on Tuesday")

        // ---- universe cut-offs and regime
        val uni = ArrayList<Series>()
        for (k in 0 until 20) uni.add(trend("U$k", 260, 500.0, 0.02 * k))
        val ctx = Ctx()
        Strategies.fillCutoffs(ctx, uni, IntArray(20) { 259 }, DoubleArray(20))
        T.near(ctx.momCutoff, Strategies.momentum(uni[16], 259), 1e-12, "top 20% of 20 = the 4 best; cut-off is the 4th best")
        T.near(ctx.leaderCutoff, Strategies.leaderScore(uni[15], 259), 1e-12, "leaders cut-off is the 5th best")
        T.near(Strategies.momentum(uni[10], 259), uni[10].c[254] / uni[10].c[128] - 1, 1e-12, "momentum = 126-day return skipping the last 5")
        Strategies.fillCutoffs(ctx, uni.take(5), IntArray(5) { 259 }, DoubleArray(5))
        T.check(ctx.momCutoff.isNaN(), "too small a universe -> no momentum ranking")
        T.eq(Strategies.regime(trend("^NSEI", 260, 20000.0, 0.1), 259), 1, "BULL above both DMAs")
        T.eq(Strategies.regime(trend("^NSEI", 260, 20000.0, -0.1), 259), -1, "BEAR below both")
        val mixed = trend("^NSEI", 260, 20000.0, 0.1); append(mixed, 19900.0, 19900.0, 18900.0, 19000.0, 0.0)
        T.eq(Strategies.regime(mixed, mixed.n - 1), 0, "NEUTRAL between the two")
        T.eq(Strategies.regime(trend("^NSEI", 100, 20000.0, 0.1), 99), 0, "NEUTRAL without 200 bars")
        T.eq(Strategies.all.map { it.id }, listOf("WASHOUT_DIP", "MONTHLY_LEADERS", "DIP_UPTREND", "MOM_PULLBACK", "BREAKOUT_VOL", "WEEKLY_LEADERS"), "six strategies registered")
        T.check(Strategies.all.all { it.params().isNotEmpty() && it.label.isNotEmpty() && it.desc.isNotEmpty() }, "labels, descriptions, params present")
        Params.DIP_UPTREND_ENABLED = false
        T.check(eval(DipUptrend, dip, bull) == null, "disabled strategy never fires")
        Params.DIP_UPTREND_ENABLED = true
    }

    // ------------------------------------------------------------------ gate
    fun gate() {
        fun ss(n: Int, avg: Double, pf: Double, oosN: Int, oosAvg: Double) = StratStats().also { it.n = n; it.avgPct = avg; it.pf = pf; it.oosN = oosN; it.oosAvgPct = oosAvg; it.winPct = 55.0; it.oosMonths = 8; it.testMonths = 14 }
        T.check(Gate.passes(ss(60, 0.25, 1.2, 20, 0.01)), "exactly at the thresholds passes")
        T.check(!Gate.passes(ss(59, 1.0, 2.0, 30, 1.0)), "n 59 fails"); T.check(!Gate.passes(ss(100, 0.24, 2.0, 30, 1.0)), "avg 0.24 fails")
        T.check(!Gate.passes(ss(100, 1.0, 1.19, 30, 1.0)), "pf 1.19 fails"); T.check(!Gate.passes(ss(100, 1.0, 2.0, 19, 1.0)), "oosN 19 fails")
        T.check(!Gate.passes(ss(100, 1.0, 2.0, 30, 0.0)), "oos avg 0 fails (must be > 0)"); T.check(!Gate.passes(ss(100, 1.0, 2.0, 30, -0.3)), "negative oos fails")
        T.eq(Gate.why(DipUptrend, ss(100, 1.0, 2.0, 30, -0.3)), "Lost 0.3% per trade after charges in the last 8 months.", "plain-English why")
        T.check(Gate.why(DipUptrend, ss(12, 1.0, 2.0, 3, 1.0)).startsWith("Only 12 test trades"), "why: too few trades")
        T.check(Gate.why(DipUptrend, ss(100, -0.4, 0.8, 30, 1.0)).startsWith("Lost 0.4% per trade after charges"), "why: losing")
        T.check(Gate.why(DipUptrend, ss(100, 1.0, 2.0, 30, 0.5)).startsWith("Passed:"), "why: passed")
        T.eq(listOf(Params.GATE_MIN_TRADES, Params.GATE_MIN_AVG_PCT, Params.GATE_MIN_PF, Params.GATE_MIN_OOS_TRADES, Params.GATE_MIN_OOS_AVG_PCT, Params.OOS_FRACTION),
            listOf<Any>(60, 0.25, 1.2, 20, 0.0, 0.35), "default gate thresholds as specified")
    }

    // ------------------------------------------------------------------ backtest helpers
    fun synthUniverse(n: Int, bars: Int, seed: Long): Pair<List<Series>, Series> {
        val out = ArrayList<Series>()
        val days = ArrayList<Int>()
        var d = 20002
        while (days.size < bars) { if (Clock.isWeekday(d)) days.add(d); d++ }
        fun make(sym: String, drift: Double, vol: Double, start: Double, r: Random, skip: Boolean): Series {
            val s = Series(sym, bars)
            var prev = start
            for (day in days) {
                val o = prev * (1 + r.nextGaussian() * vol / 400)
                val c = prev * (1 + drift / 100 + r.nextGaussian() * vol / 100)
                val h = maxOf(o, c) * (1 + Math.abs(r.nextGaussian()) * vol / 200)
                val l = minOf(o, c) * (1 - Math.abs(r.nextGaussian()) * vol / 200)
                val v = Math.rint(2e6 * (0.5 + r.nextDouble()) * (if (r.nextInt(20) == 0) 3.5 else 1.0))
                prev = c
                if (skip && r.nextInt(60) == 0) continue   // an occasional missing day
                s.add(day, o, h, l, c, v)
            }
            return s
        }
        val r = Random(seed)
        for (k in 0 until n) out.add(make("S%03d".format(k), -0.05 + 0.3 * r.nextDouble(), 1.0 + 2 * r.nextDouble(), 100 + 900 * r.nextDouble(), Random(seed * 1000 + k), k % 7 == 3))
        return Pair(out, make("^NSEI", 0.05, 0.8, 20000.0, Random(seed + 99), false))
    }

    private fun key(t: BtTrade) = t.strategyId + "|" + t.sym + "|" + t.entryDay + "|" + t.entry + "|" + t.sl + "|" + t.t1 + "|" + t.t2

    fun noLookAhead() {
        val (uni, idx) = synthUniverse(40, 500, 42)
        val full = Backtest.run(uni, idx, 0.25)
        T.check(full.trades.size > 100, "backtest produced trades (${full.trades.size}) on synthetic data")
        T.check(Strategies.all.filter { it.id != "WASHOUT_DIP" }.all { st -> full.trades.any { it.strategyId == st.id } }, "every strategy traded: " + Strategies.all.joinToString { st -> st.id + "=" + full.trades.count { it.strategyId == st.id } })
        for (cut in intArrayOf(260, 333, 420, 480)) {
            val cutDay = idx.d[cut]
            // (a) history simply ends at the cut day
            val trunc = Backtest.run(uni.map { s -> s.head((0 until s.n).count { s.d[it] <= cutDay }) }, idx.head(cut + 1), 0.25)
            // (b) every bar after the cut day is replaced by wild nonsense
            val r = Random(cut.toLong())
            fun scramble(s: Series): Series {
                val x = s.head(s.n)
                for (k in 0 until x.n) if (x.d[k] > cutDay) { val f = 0.3 + r.nextDouble() * 3; x.o[k] *= f; x.h[k] *= f * 1.5; x.l[k] *= f * 0.5; x.c[k] *= f; x.v[k] *= 10 * r.nextDouble() }
                return x
            }
            val scr = Backtest.run(uni.map { scramble(it) }, scramble(idx), 0.25)
            val a = full.trades.filter { it.entryDay <= cutDay }.map { key(it) }
            T.check(a.isNotEmpty(), "decisions exist up to cut $cut")
            T.eq(trunc.trades.map { key(it) }, a, "decisions up to day $cut identical when history stops there")
            T.eq(scr.trades.filter { it.entryDay <= cutDay }.map { key(it) }, a, "decisions up to day $cut identical when every later bar is altered")
        }
        // statistics sanity against a direct recomputation
        for (st in Strategies.all) {
            val ss = full.stats[st.id]!!
            val mine = full.trades.filter { it.strategyId == st.id && it.complete }
            T.eq(ss.n, mine.size, "${st.id} n")
            if (mine.isNotEmpty()) {
                T.near(ss.avgPct, mine.sumOf { it.netPct } / mine.size, 1e-9, "${st.id} avg")
                T.near(ss.winPct, mine.count { it.netPct > 0 } * 100.0 / mine.size, 1e-9, "${st.id} win%")
                val gp = mine.filter { it.netPct > 0 }.sumOf { it.netPct }
                val gl = -mine.filter { it.netPct <= 0 }.sumOf { it.netPct }
                if (gl > 0) T.near(ss.pf, minOf(gp / gl, 99.0), 1e-9, "${st.id} profit factor")
                T.eq(ss.oosN, mine.count { it.oos }, "${st.id} oosN")
                T.check(ss.maxDdPct >= 0 && ss.oosN <= ss.n, "${st.id} drawdown non-negative")
            }
        }
        val evalDays = idx.n - (Params.MIN_BARS - 1)
        val oosDays = full.trades.filter { it.oos }.map { it.entryDay }.distinct()
        val isDays = full.trades.filter { !it.oos }.map { it.entryDay }.distinct()
        T.check(oosDays.isNotEmpty() && isDays.isNotEmpty() && oosDays.min() > isDays.max(), "out-of-sample trades are strictly the most recent ones")
        val split = oosDays.min()
        val frac = idx.d.take(idx.n).count { it >= split }.toDouble() / evalDays
        T.check(frac in 0.30..0.36, "out-of-sample is about the last 35% of test dates ($frac)")
        // costs: a higher charge lowers every trade by exactly the difference
        val pricey = Backtest.run(uni, idx, 0.75)
        T.near(pricey.stats["DIP_UPTREND"]!!.avgPct, full.stats["DIP_UPTREND"]!!.avgPct - 0.5, 1e-9, "charges are subtracted per trade")
        // one open trade per symbol per strategy
        var overlap = 0
        for (st in Strategies.all) for ((_, ts) in full.trades.filter { it.strategyId == st.id }.groupBy { it.sym }) {
            val o = ts.sortedBy { it.entryDay }
            for (k in 1 until o.size) if (o[k].entryDay <= o[k - 1].exitDay) overlap++
        }
        T.eq(overlap, 0, "no overlapping trades in the same symbol and strategy")
        T.check(full.trades.groupBy { it.strategyId + it.entryDay }.values.all { it.size <= Params.BT_MAX_NEW_PER_DAY }, "per-day cap respected")
        T.eq(Backtest.run(emptyList(), null, 0.25).trades.size, 0, "empty universe ok")
        T.eq(Backtest.run(uni.map { it.head(150) }, idx.head(150), 0.25).trades.size, 0, "too little history -> no trades, no crash")
    }

    /** For sampled days D: evaluate exactly as the live scan does (series cut at D, D's bar as current bar) and compare with the backtest's entries on D. */
    fun liveEquals() {
        val (uni, idx) = synthUniverse(40, 500, 7)
        val saved = Params.BT_MAX_NEW_PER_DAY
        Params.BT_MAX_NEW_PER_DAY = 1000
        try {
            val full = Backtest.run(uni, idx, 0.25)
            var compared = 0
            var liveOnly = 0
            for (ci in 210 until 500 step 3) {
                val day = idx.d[ci]
                val cutUni = uni.map { s -> s.head((0 until s.n).count { s.d[it] <= day }) }
                val idxArr = IntArray(cutUni.size) { k -> if (cutUni[k].lastDay() == day) cutUni[k].n - 1 else -1 }
                val ctx = Ctx()
                ctx.regime = Strategies.regime(idx.head(ci + 1), ci)
                Strategies.fillCutoffs(ctx, cutUni, idxArr, DoubleArray(cutUni.size))
                val feat = Feat()
                val liveSet = HashMap<String, Setup>()
                for ((k, s) in cutUni.withIndex()) {
                    if (idxArr[k] < 0 || !feat.compute(s, idxArr[k])) continue
                    for (st in Strategies.all) Strategies.evaluate(st, s, idxArr[k], feat, ctx)?.let { liveSet[st.id + "|" + s.sym] = it }
                }
                val bt = full.trades.filter { it.entryDay == day }
                for (t in bt) {
                    val l = liveSet[t.strategyId + "|" + t.sym]
                    T.check(l != null && l.entry == t.entry && l.sl == t.sl && l.t1 == t.t1 && l.t2 == t.t2, "backtest entry ${t.strategyId} ${t.sym} on $day equals the live evaluation")
                    compared++
                }
                // anything live found that the backtest did not enter must be a symbol already in an open backtest trade of that strategy
                for ((k, _) in liveSet) {
                    val (sid, sym) = k.split("|")
                    if (bt.any { it.strategyId == sid && it.sym == sym }) continue
                    liveOnly++
                    T.check(full.trades.any { it.strategyId == sid && it.sym == sym && it.entryDay < day && it.exitDay >= day }, "live-only setup $k on $day is explained by an open position")
                }
            }
            T.check(compared > 50, "compared $compared backtest entries with live evaluation ($liveOnly live setups skipped for open positions)")
        } finally {
            Params.BT_MAX_NEW_PER_DAY = saved
        }
    }

    fun speed() {
        val (uni, idx) = synthUniverse(200, 500, 3)
        Backtest.run(uni.take(20), idx, 0.25)   // warm-up
        var pcts = 0
        val res = Backtest.run(uni, idx, 0.25) { pcts++ }
        println("      backtest 200 symbols x 500 bars: ${res.millis} ms on this JVM, ${res.trades.size} trades, progress callbacks: $pcts")
        T.check(res.millis < 15000, "200 x 500 backtest in ${res.millis} ms (limit 15 s here; a phone is roughly 5-10x slower)")
        T.check(pcts in 10..25, "progress reported in steps")
    }

    // ------------------------------------------------------------------ holdings import
    fun importParser() {
        val names = mapOf(HoldingsImport.normName("Reliance Industries Ltd") to "RELIANCE", HoldingsImport.normName("Infosys Ltd") to "INFY", HoldingsImport.normName("Mahindra & Mahindra Ltd") to "M&M")
        val known = setOf("RELIANCE", "INFY", "M&M", "BAJAJ-AUTO", "TCS", "ITC")
        fun p(t: String) = HoldingsImport.parse(t, names, known)
        fun row(r: ImportResult, sym: String) = r.rows.firstOrNull { it.sym == sym }
        // Zerodha console style
        var r = p("\uFEFFSymbol,ISIN,Sector,Quantity Available,Quantity Discrepant,Quantity Long Term,Quantity Pledged (Margin),Average Price,Previous Closing Price,Unrealized P&L,Unrealized P&L Pct.\n" +
            "RELIANCE,INE002A01018,Energy,10,0,4,0,\"2,450.50\",2500,495,2.02\nM&M,INE101A01026,Auto,5,0,0,0,1510.2,1600,449,5.9\n\nTotal,,,15,,,,,,944,\n")
        T.eq(r.rows.size, 2, "zerodha: 2 rows, total and blank skipped"); T.eq(row(r, "RELIANCE")?.qty, 10.0, "qty from 'Quantity Available', not 'Discrepant'")
        T.eq(row(r, "RELIANCE")?.avg, 2450.5, "quoted number with comma"); T.eq(row(r, "M&M")?.avg, 1510.2, "M&M kept"); T.eq(r.skipped.size, 0, "nothing reported as skipped")
        // Kite holdings style
        r = p("Instrument\tQty.\tAvg. cost\tLTP\tCur. val\tP&L\tNet chg.\tDay chg.\nINFY\t12\t1,480.25\t1,520.00\t18,240.00\t477.00\t2.69%\t0.4%\nBAJAJ-AUTO\t2\t9,100.00\t9,300\t18,600\t400\t2.2%\t-0.1%\n")
        T.eq(row(r, "INFY")?.avg, 1480.25, "TSV with Indian commas"); T.eq(row(r, "BAJAJ-AUTO")?.qty, 2.0, "hyphenated symbol")
        // unknown broker headers, ₹ signs, decorations, semicolons
        r = p("Scrip Name;Net Qty;Buy Avg;Market Value\nNSE:TCS-EQ;7;₹ 3,900.10;27,000\nitc.ns;100;Rs.415;41,500\n\"RELIANCE-EQ\";3;2400;7200\nGRAND TOTAL;;;75,700\n")
        T.eq(r.rows.map { it.sym }, listOf("TCS", "ITC", "RELIANCE"), "decorations stripped (NSE:, -EQ, .ns, quotes)"); T.eq(row(r, "TCS")?.avg, 3900.1, "₹ sign"); T.eq(row(r, "ITC")?.avg, 415.0, "Rs. prefix")
        // company names instead of symbols + invested amount instead of average
        r = p("Stock Name,ISIN,Quantity,Invested Value,Current Value\nReliance Industries Ltd,INE002A01018,4,\"10,000\",\"10,400\"\nInfosys Limited,INE009A01021,10,15000,15500\nSome Unknown Co Ltd,INE000000000,5,500,600\n")
        T.eq(row(r, "RELIANCE")?.avg, 2500.0, "company name mapped to symbol; avg = invested / qty"); T.eq(row(r, "INFY")?.avg, 1500.0, "Infosys Limited -> INFY")
        T.eq(r.skipped.size, 1, "unknown company reported"); T.check(r.skipped[0].contains("Line 4"), "skip reason names the line: " + r.skipped.firstOrNull())
        // bare lines, mixed separators, duplicates merged by weighted average
        r = p("RELIANCE 10 2450.5\nTCS,5,3900\n  infy   4   1,500.00  \nRELIANCE 30 2550.5\n\n---\nhello world\nITC 0 400\nTCS 5\n")
        T.eq(row(r, "RELIANCE")?.qty, 40.0, "duplicates: quantities added"); T.near(row(r, "RELIANCE")!!.avg, (10 * 2450.5 + 30 * 2550.5) / 40, 1e-9, "duplicates: weighted average price")
        T.eq(row(r, "INFY")?.avg, 1500.0, "bare line with spaces and comma number"); T.check(row(r, "ITC") == null, "zero quantity skipped")
        T.check(r.skipped.any { it.contains("ITC") && it.contains("quantity") } && r.skipped.any { it.contains("TCS") && it.contains("average price") }, "reasons given: " + r.skipped)
        T.eq(row(r, "TCS")?.qty, 5.0, "valid TCS line kept despite a later bad one")
        // pipe separated, header not on the first line, serial-number column
        r = p("Holdings statement as on 10-11-2026\nClient: X\nSr|Security|Holding|Rate|Value\n1|TCS|3|3,950.00|11,850.00\n2|ITC|50|410|20,500\nTotal|||| 32,350\n")
        T.eq(r.rows.map { it.sym + ":" + it.qty + "@" + it.avg }, listOf("TCS:3.0@3950.0", "ITC:50.0@410.0"), "header found below preamble; pipe separator")
        for (junk in listOf("", "   \n\n", "just some words here", "\u0000\u0001\u0002", "Symbol,Quantity,Average Price\n", ",,,,\n;;;;")) T.eq(p(junk).rows.size, 0, "junk gives no rows")
        T.eq(HoldingsImport.cleanSymbol(" nse:m&m-eq "), "M&M", "cleanSymbol"); T.eq(HoldingsImport.parseNumber("(1,234.5)"), -1234.5, "bracket negative")
        T.eq(HoldingsImport.parseNumber("₹1,23,456.78"), 123456.78, "Indian grouping"); T.eq(HoldingsImport.parseNumber("12%"), null, "percent is not a number"); T.eq(HoldingsImport.parseNumber("abc"), null, "text")
        T.eq(HoldingsImport.splitLine("a,\"b,c\",\"d \"\"q\"\"\",", ','), listOf("a", "b,c", "d \"q\"", ""), "CSV quoting")
        T.eq(HoldingsImport.parse(null, names, known).rows.size, 0, "null text")
    }

    // ------------------------------------------------------------------ portfolio advisor
    fun advisor() {
        val nifty = trend("^NSEI", 300, 20000.0, 0.05)
        val up = trend("UP", 300, 500.0, 0.12, zig = 0.6)
        var a = Advisor.check(up, nifty, 1.0, 10.0)
        T.eq(a.verdict, "HOLD", "steady uptrend -> HOLD: ${a.headline}"); T.check(a.dataOk && a.stop > 0 && a.stop < 500 && a.bookAt > 500, "stop below price, book level above")
        T.check(a.reasons.size in 2..4, "2-4 reasons")
        T.near(a.stop, Advisor.trailingStop(up, up.n - 2), 1e-9, "stop is the trailing level known before today")
        val i = up.n - 2
        T.near(Advisor.trailingStop(up, i), maxOf(Ind.highest(up.c, i, 22) - 3 * Ind.atr(up.h, up.l, up.c, i, 14), Ind.lowest(up.l, i, 10)), 1e-9, "trailing = max(22d high close - 3 ATR, 10d low)")
        a = Advisor.check(up, nifty, 1.0, 35.0)
        T.eq(a.verdict, "TRIM", "over 20% of the portfolio -> TRIM")
        val dn = trend("DN", 300, 300.0, -0.2)
        a = Advisor.check(dn, nifty, 1.0, 10.0)
        T.eq(a.verdict, "SELL", "downtrend below 200DMA -> SELL: ${a.headline}"); T.check(a.reasons.size in 2..4, "reasons")
        val hot = trend("HOT", 300, 900.0, 0.9)
        a = Advisor.check(hot, nifty, 1.0, 10.0)
        T.eq(a.verdict, "TRIM", "far above the 50DMA / RSI>80 -> TRIM: ${a.headline}")
        // decisive break of the trailing stop on heavy volume
        val brk = trend("BRK", 300, 500.0, 0.12, zig = 0.6)
        append(brk, 498.0, 499.0, 455.0, 458.0, 6e6)
        a = Advisor.check(brk, nifty, 1.0, 10.0)
        T.eq(a.verdict, "SELL", "heavy-volume close under the stop -> SELL: ${a.headline}"); T.check(a.belowStop, "flagged below stop")
        // the same fall on thin volume is only a WATCH
        val soft = trend("SOFT", 300, 500.0, 0.12, zig = 0.6)
        append(soft, 498.0, 499.0, 481.0, 482.0, 1e6)
        a = Advisor.check(soft, nifty, 1.0, 10.0)
        T.eq(a.verdict, "WATCH", "under the stop without volume -> WATCH: ${a.headline}")
        // blow-off day
        val blow = trend("BLOW", 300, 500.0, 0.05, zig = 0.6)
        append(blow, 502.0, 548.0, 501.0, 540.0, 9e6)
        a = Advisor.check(blow, nifty, 1.0, 10.0)
        T.eq(a.verdict, "TRIM", "blow-off day -> TRIM: ${a.headline}")
        // weak vs Nifty and under the 50DMA but above the 200DMA
        val lag = trend("LAG", 230, 620.0, 0.35)
        for (k in 0 until 63) append(lag, lag.c[lag.n - 1], lag.c[lag.n - 1] * 1.002, lag.c[lag.n - 1] * 0.993, lag.c[lag.n - 1] * 0.9965, 2e6)
        val strongNifty = trend("^NSEI", 300, 20000.0, 0.15)
        a = Advisor.check(lag, strongNifty, 1.0, 10.0)
        T.eq(a.verdict, "SELL", "deep 3-month lag vs Nifty under the 50DMA -> SELL: ${a.headline}")
        a = Advisor.check(trend("NEW", 30, 100.0, 0.1), nifty, 1.0, 10.0)
        T.check(a.verdict == "WATCH" && !a.dataOk && a.reasons.size >= 2, "under 60 bars -> WATCH, dataOk false")
        a = Advisor.check(null, null, 1.0, 0.0)
        T.check(a.verdict == "WATCH" && !a.dataOk, "no data -> WATCH")
        a = Advisor.check(trend("MID", 120, 200.0, 0.1, zig = 0.6), null, 0.5, 0.0)
        T.check(a.dataOk && a.verdict == "HOLD", "works with 60-199 bars and no index: ${a.verdict}")
    }

    fun fmt() {
        T.eq(Fmt.rs(1234567.5), "₹12,34,567.50", "Indian grouping"); T.eq(Fmt.rs(435.0), "₹435", "whole rupees"); T.eq(Fmt.rs(-950.25), "-₹950.25", "negative")
        T.eq(Fmt.rs(99999.999), "₹1,00,000", "rounding carries"); T.eq(Fmt.rs(Double.NaN), "₹0", "NaN safe"); T.eq(Fmt.rsSigned(350.4), "+₹350", "signed")
        T.eq(Fmt.pct(-0.34), "-0.3%", "pct"); T.eq(Fmt.pct(1.25), "+1.3%", "pct rounds"); T.eq(Fmt.p1(-0.04), "0.0", "no negative zero"); T.eq(Fmt.plural(1, "share"), "1 share", "singular"); T.eq(Fmt.plural(3, "share"), "3 shares", "plural")
    }
}
