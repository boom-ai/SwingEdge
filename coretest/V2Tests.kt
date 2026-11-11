package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*
import java.util.Random

/** SPEC2: search, stockInfo, getChart v2, personal watchlist, lock-free reads. */
object V2Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v2-search" to ::search, "v2-refactor-equivalence" to ::equivalence, "v2-search-speed" to ::searchSpeed, "v2-ema-cross-days" to ::crossDays,
        "v2-stockInfo" to ::stockInfo, "v2-stockInfo-offline" to ::offline, "v2-ema-equal" to ::emaEqual,
        "v2-getChart" to ::chart, "v2-watchlist" to ::watchlist, "v2-reads-not-blocked" to ::notBlocked, "v2-concurrent" to ::concurrent
    )

    private val BANNED = listOf("guaranteed", "guarantee", "sure shot", "sure-shot", "risk-free", "risk free", "assured", "no risk")
    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()

    private fun act(app: SwingApp, name: String, args: String): Map<String, Any?> {
        val txt = app.action(name, args)
        val r = Json.parseObject(txt)
        T.check(r != null && r["ok"] is Boolean, "action $name returns {ok:..}: " + txt.take(100))
        T.check(!txt.contains("NaN") && !txt.contains("Infinity"), "no NaN/Infinity in $name")
        return r ?: emptyMap()
    }

    private fun syms(r: Map<String, Any?>) = r.list("results").map { it.asObj().str("sym") }

    // ------------------------------------------------------------------ stockInfo schema (from SPEC2.md)
    private val INFO: Map<String, Any?> = Json.parse("""{"ok":true,"sym":"","name":"","tracked":true,"isHolding":false,"inWatch":false,
     "asOf":0,"live":true,"barDay":0,"bars":0,
     "ltp":0,"prevClose":0,"dayChgPct":0,"open":0,"dayHigh":0,"dayLow":0,
     "volume":0,"avgVol20":0,"volRatio":0,"turnoverCr":0,"liquid":true,
     "ema":{"e10":0,"e20":0,"e50":0,"e100":0,"e200":0},
     "emaDist":{"e10":0,"e20":0,"e50":0,"e100":0,"e200":0},
     "stack":{"label":"BULLISH","text":""},
     "cross":{"state":"ABOVE","daysSince":3,"gapPct":0.42,"converging":false,"text":""},
     "golden":{"state":"ABOVE","daysSince":120,"text":""},
     "rsi14":0,"rsi2":0,"atr":0,"atrPct":0,
     "ret":{"d5":0,"d21":0,"d63":0,"d126":0,"d250":0},
     "vsNifty":{"d21":0,"d63":0,"d126":0},
     "high52":0,"low52":0,"fromHighPct":0,"fromLowPct":0,"high20":0,"low20":0,
     "levels":{"support":0,"resistance":0,"trailStop":0},
     "strength":0,
     "verdict":{"label":"HOLD","headline":""},
     "checks":[{"label":"","ok":true,"text":""}],
     "setups":[{"id":"EMA_CROSS","label":"","match":false,"text":""}],
     "summary":""}""").asObj()
    private val ENUMS = mapOf("stack.label" to setOf("BULLISH", "MIXED", "BEARISH"), "cross.state" to setOf("ABOVE", "BELOW"),
        "golden.state" to setOf("ABOVE", "BELOW"), "verdict.label" to setOf("HOLD", "WATCH", "TRIM", "SELL"))

    private fun shape(path: String, tpl: Any?, v: Any?, errs: ArrayList<String>) {
        when (tpl) {
            is Map<*, *> -> {
                if (v !is Map<*, *>) { errs.add("$path not an object"); return }
                if (v.keys != tpl.keys) errs.add("$path keys differ: missing ${tpl.keys - v.keys} extra ${v.keys - tpl.keys}")
                for ((k, t) in tpl) if (v.containsKey(k)) shape(if (path.isEmpty()) "$k" else "$path.$k", t, v[k], errs)
            }
            is List<*> -> { if (v !is List<*>) errs.add("$path not an array") else for (x in v) shape(path, tpl[0], x, errs) }
            is Double -> if (v !is Double || v.isNaN() || v.isInfinite()) errs.add("$path not a number: $v")
            is String -> { if (v !is String) errs.add("$path not a string: $v") else ENUMS[path]?.let { if (v !in it) errs.add("$path bad value $v") } }
            is Boolean -> if (v !is Boolean) errs.add("$path not a boolean: $v")
        }
    }

    /** stockInfo with every SPEC2 check: schema, types, counts, wording. */
    private fun info(app: SwingApp, sym: String, where: String): Map<String, Any?> {
        val r = act(app, "stockInfo", """{"sym":"$sym"}""")
        if (r["ok"] != true) return r
        val errs = ArrayList<String>()
        shape("", INFO, r, errs)
        T.check(errs.isEmpty(), "stockInfo schema for $sym at $where: " + errs.take(4))
        T.check(r.list("checks").size in 6..9, "6-9 checks for $sym: ${r.list("checks").size}")
        val st = r.list("setups").map { it.asObj() }
        T.check(st.firstOrNull()?.str("id") == "EMA_CROSS" && st.size == 1 + Strategies.all.count { it.enabled() }, "EMA_CROSS + one setup per enabled strategy: " + st.map { it.str("id") })
        T.check(st.all { it.str("text").isNotBlank() } && r.list("checks").all { it.asObj().str("text").isNotBlank() }, "every check/setup explains itself")
        val sum = r.str("summary")
        val sentences = sum.split(Regex("(?<=[.])\\s+")).filter { it.isNotBlank() }
        T.check(sentences.size in 2..3, "summary is 2-3 sentences for $sym: $sum")
        T.check(!Regex("(?i)\\b(buy|sell|sold|bought|purchase)\\b").containsMatchIn(sum), "summary gives no buy/sell instruction: $sum")
        val low = Json.stringify(r).lowercase()
        for (w in BANNED) T.check(!low.contains(w), "banned word '$w' in stockInfo $sym")
        return r
    }

    // ------------------------------------------------------------------ search
    private const val SYMBOLS_CSV = "﻿SYMBOL,NAME OF COMPANY\n" +
        "TATAMOTORS,Tata Motors Ltd\nTMPV,Tata Motors Passenger Vehicles Ltd\nTATASTEEL,Tata Steel Limited\nTATAPOWER,The Tata Power Company Ltd\n" +
        "LT,Larsen & Toubro Ltd\nLTIM,LTIMindtree Ltd\nLTTS,L&T Technology Services Ltd\nDELTACORP,Delta Corp Ltd\nVOLTAS,Voltas Ltd\nABC,Alto Builders\n" +
        "UP01,Uptrend One Industries Ltd\nSAMENAME,Same Name Industries Ltd\n" +
        "\"QUOTED\",\"Quoted Name Ltd\"\n" +
        "bad sym,Space In Symbol\nWAYTOOLONGSYMBOLNAMEABCDEFGHIJ,Too Long\n,No Symbol\n#comment,ignored\n" +
        "NONAME\n" +
        "RELIANCE,Reliance Industries Ltd\n"

    fun search() {
        // 1. without symbols.csv: universe + aliases only
        val p = FakePlatform.standard()
        p.assets["aliases.csv"] = "ALPINETEX,Alpine Texworld Ltd\nVAML,Vedanta Aluminium Metal Ltd\nVAML,Vedanta Aluminium\n"
        p.setTime(day(2026, 10, 6), 10 * 60)
        var app = SwingApp(p)
        var r = act(app, "search", """{"q":"up0"}""")
        T.eq(r["ok"], true, "search ok"); T.eq(r.str("q"), "up0", "q echoed")
        T.check(syms(r).take(9) == (1..9).map { "UP%02d".format(it) } && r.list("results").all { it.asObj().bool("tracked") }, "symbol prefix, tracked: " + syms(r))
        T.eq(syms(act(app, "search", """{"q":"m&m"}""")).firstOrNull(), "M&M", "punctuation in a symbol (M&M)")
        T.eq(syms(act(app, "search", """{"q":"Bajaj-Auto"}""")).firstOrNull(), "BAJAJ-AUTO", "hyphenated symbol, any case")
        T.eq(syms(act(app, "search", """{"q":"uptrend 1"}""")).take(1), listOf("UP01"), "name word-prefix")
        r = act(app, "search", """{"q":"alpine tex"}""")
        T.check(syms(r) == listOf("ALPINETEX") && !r.list("results")[0].asObj().bool("tracked") && r.list("results")[0].asObj().str("name") == "Alpine Texworld Ltd", "aliases.csv names are searchable, untracked: $r")
        T.eq(syms(act(app, "search", """{"q":"vedanta aluminium"}""")), listOf("VAML"), "a symbol listed twice in aliases appears once")
        for (q in listOf("", " ", "u", "U ", "!!!", "&", ".", "ltd", "Limited", "टाटा", "株式", "\u0000")) T.eq(act(app, "search", Json.stringify(mapOf("q" to q))).list("results").size, 0, "odd query '$q' -> no results")
        T.eq(act(app, "search", "not json").list("results").size, 0, "bad args -> empty results")
        r = act(app, "search", """{"q":12}""")
        T.check(r.str("q") == "12" && syms(r) == listOf("UP12"), "numeric q treated as text: $r")
        val long = "tata ".repeat(4000)
        val t0 = System.nanoTime()
        r = act(app, "search", Json.stringify(mapOf("q" to long)))
        T.check((System.nanoTime() - t0) / 1e6 < 200 && r.str("q") == long, "very long query answered quickly and echoed")
        T.eq(act(app, "search", Json.stringify(mapOf("q" to "Uptrend 1 Ltd."))).list("results").map { it.asObj().str("sym") }.take(1), listOf("UP01"), "'ltd' and punctuation ignored")

        // 2. with symbols.csv (header, BOM, quotes and junk lines tolerated)
        p.assets["symbols.csv"] = SYMBOLS_CSV
        p.assets["universe.csv"] = p.assets["universe.csv"] + "SAMENAME,SAMENAME\n"
        app = SwingApp(p)
        r = act(app, "search", """{"q":"tata mot"}""")
        T.eq(syms(r), listOf("TATAMOTORS", "TMPV"), "'tata mot': symbol prefix first, then name word-prefix")
        T.eq(r.list("results")[0].asObj().str("name"), "Tata Motors Ltd", "name from symbols.csv")
        T.eq(r.list("results")[0].asObj().bool("tracked"), false, "not in the scanned universe -> tracked false")
        T.eq(syms(act(app, "search", """{"q":"lt"}""")), listOf("LT", "LTIM", "LTTS", "VOLTAS", "DELTACORP", "ABC"), "exact, prefix, symbol-contains, name-contains")
        T.eq(syms(act(app, "search", """{"q":"Tata Motors Ltd."}""")), listOf("TATAMOTORS", "TMPV"), "closer name first within a tier")
        T.eq(syms(act(app, "search", """{"q":"power tata"}""")), listOf("TATAPOWER"), "query words in any order")
        T.eq(syms(act(app, "search", """{"q":"l&t tech"}""")), listOf("LTTS"), "& in a name is a separator")
        T.eq(syms(act(app, "search", """{"q":"quoted"}""")), listOf("QUOTED"), "quoted csv fields cleaned")
        T.eq(act(app, "search", """{"q":"quoted"}""").list("results")[0].asObj().str("name"), "Quoted Name Ltd", "quoted name cleaned")
        T.eq(syms(act(app, "search", """{"q":"noname"}""")), listOf("NONAME"), "line without a name -> symbol as name")
        T.check(syms(act(app, "search", """{"q":"space in"}""")).isEmpty() && syms(act(app, "search", """{"q":"too long"}""")).isEmpty(), "invalid symbol lines skipped")
        r = act(app, "search", """{"q":"samename"}""")
        T.check(syms(r) == listOf("SAMENAME") && r.list("results")[0].asObj().str("name") == "Same Name Industries Ltd" && r.list("results")[0].asObj().bool("tracked"), "universe symbol gets the real name from symbols.csv: $r")
        r = act(app, "search", """{"q":"up01"}""")
        T.check(syms(r).first() == "UP01" && r.list("results")[0].asObj().str("name") == "Uptrend One Industries Ltd", "symbols.csv name beats universe name")
        T.check(syms(act(app, "search", """{"q":"reliance"}""")) == listOf("RELIANCE"), "plain lookup")
        // max 25
        val sb = StringBuilder()
        for (k in 0 until 60) sb.append("UPX%02d,Up Extra $k Ltd\n".format(k))
        p.assets["symbols.csv"] = sb.toString()
        T.eq(act(SwingApp(p), "search", """{"q":"up"}""").list("results").size, 25, "at most 25 results")
        // no assets at all
        p.assets.clear()
        T.eq(act(SwingApp(p), "search", """{"q":"up01"}""").list("results").size, 0, "no lists at all -> empty, no crash")
    }

    fun searchSpeed() {
        val p = FakePlatform.standard()
        val r = Random(42)
        val words = listOf("Tata", "Reliance", "Bharat", "India", "Steel", "Power", "Motors", "Bank", "Finance", "Pharma", "Chemicals", "Textiles", "Agro",
            "Infra", "Energy", "Capital", "Holdings", "Industries", "Systems", "Technologies", "Cement", "Sugar", "Paper", "Foods", "Logistics")
        val sb = StringBuilder()
        val seen = HashSet<String>()
        while (seen.size < 2500) {
            val len = 3 + r.nextInt(8)
            val sym = (0 until len).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789&-"[if (it == 0) r.nextInt(26) else r.nextInt(if (r.nextInt(20) == 0) 38 else 36)] }.joinToString("")
            if (!seen.add(sym)) continue
            sb.append(sym).append(',').append((0 until 2 + r.nextInt(4)).joinToString(" ") { words[r.nextInt(words.size)] }).append(" Ltd\n")
        }
        p.assets["symbols.csv"] = sb.toString()
        p.setTime(day(2026, 10, 6), 10 * 60)
        val app = SwingApp(p)
        var t0 = System.nanoTime()
        act(app, "search", """{"q":"tata"}""")
        val first = (System.nanoTime() - t0) / 1e6
        val qs = listOf("ta", "tata mot", "bank", "INFRA", "a", "zz", "power tata", "chem", "s&p", "x9", "hold ind", "ltd", "india bharat steel", "q", "pharma ltd") +
            (0 until 285).map { seen.elementAt(r.nextInt(seen.size)).take(2 + r.nextInt(3)) }
        var worst = 0.0
        var total = 0.0
        var hits = 0
        for (q in qs) {
            t0 = System.nanoTime()
            val res = act(app, "search", Json.stringify(mapOf("q" to q)))
            val ms = (System.nanoTime() - t0) / 1e6
            total += ms
            if (ms > worst) worst = ms
            hits += res.list("results").size
        }
        println("      search over ${seen.size + 44} symbols: first call (builds index) ${"%.1f".format(first)} ms; ${qs.size} queries avg ${"%.2f".format(total / qs.size)} ms, worst ${"%.1f".format(worst)} ms")
        T.check(first < 1000, "index build + first query under 1 s: $first ms")
        T.check(worst < 50, "every query under 50 ms: worst $worst ms")
        T.check(hits > 1000, "queries found things ($hits)")
    }

    // ------------------------------------------------------------------ EMA cross counting (hand-checked)
    private fun series(sym: String, closes: DoubleArray, vol: Double = 3e6, startDay: Int = 20002): Series {
        val s = Series(sym, closes.size + 4)
        var d = startDay
        var prev = closes[0]
        for (c in closes) {
            while (!Clock.isWeekday(d)) d++
            s.add(d, prev, maxOf(prev, c) * 1.003, minOf(prev, c) * 0.997, c, vol)
            prev = c
            d++
        }
        return s
    }

    private fun input(s: Series, idx: Series? = null, ctx: Ctx? = null) =
        StockInput(s.sym, s.sym + " Ltd", false, false, false, s, idx, false, Clock.msAt(s.lastDay(), Clock.CLOSE_MIN), Clock.msAt(s.lastDay() + 1, 600),
            ctx, 1, 0.0, Strategies.all.associate { it.id to "LIVE" })

    fun crossDays() {
        // helper on hand-made lines: 10-line crosses above the 20-line on bar 3; latest bar is 5 -> 2 days ago
        val a = doubleArrayOf(1.0, 1.0, 1.0, 3.0, 3.0, 3.0)
        val b = doubleArrayOf(2.0, 2.0, 2.0, 2.0, 2.0, 2.0)
        T.eq(Ema.daysSince(a, b, 6, 0, 60), 2, "cross 2 bars before the latest")
        T.eq(Ema.daysSince(a, b, 4, 0, 60), 0, "cross on the latest bar = 0")
        T.eq(Ema.daysSince(a, b, 6, 0, 1), -1, "beyond the look-back cap = -1")
        T.eq(Ema.daysSince(a, b, 6, 3, 60), -1, "bars before 'first' are not compared")
        T.eq(Ema.daysSince(b, a, 6, 0, 60), 2, "cross below counts the same way")
        T.eq(Ema.daysSince(doubleArrayOf(1.0, 1.0), doubleArrayOf(1.0, 1.0), 2, 0, 60), -1, "equal lines: no cross")
        T.eq(Ema.daysSince(a, b, 1, 0, 60), -1, "single bar: unknown")
        // EMA values themselves, by hand: period 3 (alpha 0.5) seeded at the first close
        val e = Ema.line(doubleArrayOf(10.0, 12.0, 11.0, 15.0), 4, 3)
        T.check(e[0] == 10.0 && e[1] == 11.0 && e[2] == 11.0 && e[3] == 13.0, "EMA(3) 10,12,11,15 -> 10,11,11,13: " + e.toList())

        // a constructed series: flat 100 for 280 bars, down 1/bar for 10 bars, then up 3/bar for 10 bars
        val c = DoubleArray(300) { k -> if (k < 280) 100.0 else if (k < 290) 100.0 - (k - 279) else 90.0 + (k - 289) * 3.0 }
        val s = series("HANDX", c)
        // independent EMA recursion
        fun naive(p: Int): DoubleArray { val o = DoubleArray(c.size); var x = c[0]; o[0] = x; for (j in 1 until c.size) { x = x + (2.0 / (p + 1)) * (c[j] - x); o[j] = x }; return o }
        val n10 = naive(10); val n20 = naive(20)
        var lastUp = -1
        for (j in 1 until c.size) if (n10[j] > n20[j] && !(n10[j - 1] > n20[j - 1])) lastUp = j
        T.check(lastUp in 291..299, "the 10 EMA crosses back above the 20 EMA during the rise (bar $lastUp)")
        val r = StockPage.build(input(s))
        val cr = r.obj("cross")
        T.eq(cr.str("state"), "ABOVE", "state ABOVE after the rise")
        T.eq(cr.int("daysSince"), 299 - lastUp, "cross.daysSince = bars since the cross (bar $lastUp of 0..299)")
        T.near(r.obj("ema").num("e10"), Math.round(n10[299] * 100) / 100.0, 1e-9, "e10 equals the plain recursion seeded at bar 0")
        T.near(r.obj("ema").num("e20"), Math.round(n20[299] * 100) / 100.0, 1e-9, "e20 equals the plain recursion seeded at bar 0")
        T.near(cr.num("gapPct"), (n10[299] - n20[299]) / n20[299] * 100, 0.006, "gapPct signed")
        T.check(cr.bool("converging") == (Math.abs((n10[299] - n20[299]) / n20[299]) < Math.abs((n10[297] - n20[297]) / n20[297])), "converging compares with 2 bars ago")
        T.check(cr.str("text").contains("crossed above the 20 EMA " + (if (lastUp == 299) "on the latest bar" else Fmt.plural(299 - lastUp, "day"))), "cross text has the day count: " + cr.str("text"))
        // during the dip it was BELOW, counted from the first dip bar
        val dip = series("HANDY", c.copyOf(286))
        val r2 = StockPage.build(input(dip))
        var lastDn = -1
        for (j in 1 until 286) if ((n10[j] > n20[j]) != (n10[j - 1] > n20[j - 1])) lastDn = j
        T.eq(r2.obj("cross").str("state"), "BELOW", "BELOW during the dip")
        T.eq(r2.obj("cross").int("daysSince"), if (lastDn < 0) -1 else 285 - lastDn, "no sign change since the flat stretch (flat counts as below) -> -1 or the count")
        // a cross older than 60 bars -> -1
        val c3 = DoubleArray(400) { k -> 100.0 + k * 0.1 }
        T.eq(StockPage.build(input(series("RAMP", c3))).obj("cross").int("daysSince"), -1, "rising since bar 1 (> 60 bars) -> -1")
        // golden: 50 vs 200 EMA only with 251+ bars
        T.eq(StockPage.build(input(series("SHORT", DoubleArray(200) { 100.0 + it }))).obj("golden").int("daysSince"), -1, "golden unknown under 251 bars")
        // the Setups-tab rule reads the same counter for a fresh cross
        val sw = series("SWX", DoubleArray(300) { k -> if (k < 280) 100.0 + k * 0.3 else if (k < 297) 184.0 - (k - 279) * 0.6 else 176.0 + (k - 296) * 4.0 })
        val L = EmaLines(sw)
        val since = EmaScreen.match(sw, L)
        val ri = StockPage.build(input(sw))
        val em = ri.list("setups").map { it.asObj() }.first { it.str("id") == "EMA_CROSS" }
        T.check(since >= 0 && em.bool("match") && ri.obj("cross").int("daysSince") == since, "EMA_CROSS match ($since) agrees with cross.daysSince (${ri.obj("cross").int("daysSince")}): " + em.str("text"))
    }

    // ------------------------------------------------------------------ the shared EMA helper == the code it replaced
    /** computeWatch()'s original inline loop, verbatim: returns [since, e10, e20, e50, e100, e200] or null if not listed. */
    private fun oldWatch(s: Series): DoubleArray? {
        val k10 = 2.0 / 11.0; val k20 = 2.0 / 21.0; val k50 = 2.0 / 51.0; val k100 = 2.0 / 101.0; val k200 = 2.0 / 201.0
        val keep = Params.WATCH_MAX_DAYS_SINCE + 2
        val g = DoubleArray(keep)
        val n = s.n
        if (n < 260) return null
        val c = s.c[n - 1]
        if (!(c >= Params.MIN_PRICE)) return null
        var tv = 0.0
        for (j in n - 21 until n - 1) tv += s.c[j] * s.v[j]
        if (tv / 20.0 < Params.MIN_AVG_TURNOVER_RS) return null
        var e10 = s.c[0]; var e20 = e10; var e50 = e10; var e100 = e10; var e200 = e10
        java.util.Arrays.fill(g, 0.0)
        for (j in 1 until n) {
            val x = s.c[j]
            e10 += k10 * (x - e10); e20 += k20 * (x - e20); e50 += k50 * (x - e50); e100 += k100 * (x - e100); e200 += k200 * (x - e200)
            if (j >= n - keep) { for (q in keep - 1 downTo 1) g[q] = g[q - 1]; g[0] = e10 - e20 }
        }
        if (!(e10 > e20 && e10 > e50 && e10 > e100 && e10 > e200)) return null
        var since = -1
        for (q in 0..Params.WATCH_MAX_DAYS_SINCE) {
            if (!(g[q] > 0)) break
            if (g[q + 1] <= 0) { since = q; break }
        }
        if (since < 0) return null
        return doubleArrayOf(since.toDouble(), e10, e20, e50, e100, e200)
    }

    /** getChart's original EMA arrays, verbatim (from = n - 130). */
    private fun oldChart(x: Series): List<List<Double>> {
        val from = maxOf(0, x.n - 130)
        val x10 = ArrayList<Double>(); val x20 = ArrayList<Double>(); val x50 = ArrayList<Double>(); val x200 = ArrayList<Double>()
        var e10 = x.c[0]; var e20 = e10; var e50 = e10; var e200 = e10
        for (i in 1 until from) { val v = x.c[i]; e10 += (2.0 / 11.0) * (v - e10); e20 += (2.0 / 21.0) * (v - e20); e50 += (2.0 / 51.0) * (v - e50); e200 += (2.0 / 201.0) * (v - e200) }
        for (i in from until x.n) {
            if (i > 0) { val v = x.c[i]; e10 += (2.0 / 11.0) * (v - e10); e20 += (2.0 / 21.0) * (v - e20); e50 += (2.0 / 51.0) * (v - e50); e200 += (2.0 / 201.0) * (v - e200) }
            x10.add(Fmt.r2(e10)); x20.add(Fmt.r2(e20)); x50.add(if (i >= 100) Fmt.r2(e50) else 0.0); x200.add(if (i >= 250) Fmt.r2(e200) else 0.0)
        }
        return listOf(x10, x20, x50, x200)
    }

    fun equivalence() {
        val r = Random(7)
        var listed = 0
        var mism = 0
        for (k in 0 until 8000) {
            val n = listOf(5, 60, 120, 259, 260, 261, 300, 521)[k % 8]
            val c = DoubleArray(n)
            var x = 50.0 + r.nextDouble() * 500
            val drift = (r.nextDouble() - 0.25) * 0.008
            val dipLen = 3 + r.nextInt(8)
            val jump = 1 + r.nextInt(3)
            for (j in 0 until n) {
                x *= 1 + drift + r.nextGaussian() * 0.01
                // a dip then a rebound in the last bars: makes fresh 10/20 crosses common
                if (k % 2 == 0 && j >= n - dipLen - jump && j < n - jump) x *= 0.99
                if (k % 2 == 0 && j >= n - jump) x *= 1.02 + r.nextDouble() * 0.03
                c[j] = Math.max(1.0, x)
            }
            val s = series("EQ$k", c, vol = if (k % 5 == 0) 1e4 else 3e6)
            val old = oldWatch(s)
            val L = EmaLines(s)
            val since = EmaScreen.match(s, L)
            if (old == null) { if (since >= 0) mism++ }
            else {
                listed++
                val i = n - 1
                if (since != old[0].toInt() || L.e10[i] != old[1] || L.e20[i] != old[2] || L.e50[i] != old[3] || L.e100[i] != old[4] || L.e200[i] != old[5]) mism++
            }
            if (n >= 2) {
                val oc = oldChart(s)
                val from = maxOf(0, n - 130)
                val nc = listOf(10, 20, 50, 200).map { p -> (from until n).map { Fmt.r2(L.shown(p, it)) } }
                if (oc != nc) mism++
            }
        }
        println("      8000 random series: $listed listed by the old screen")
        T.check(listed > 100, "the comparison exercised listed stocks ($listed)")
        T.eq(mism, 0, "EmaScreen/EmaLines give bit-identical results to the replaced computeWatch/getChart code")
    }

    // ------------------------------------------------------------------ stockInfo
    fun stockInfo() {
        val p = FakePlatform.standard()
        p.yahoo.profiles["TINY"] = Profile(0.1, 1.2); p.yahoo.shortHistory["TINY"] = 40
        val d0 = day(2026, 10, 6)
        p.setTime(d0, 10 * 60 + 1)
        val app = SwingApp(p)
        // before any scan: a universe symbol is fetched on demand and stored in the persistent cache
        p.keepUrls = true
        var r = info(app, "UP02", "no scan yet")
        T.check(r["ok"] == true && r.bool("tracked") && r.bool("live") && r.int("bars") > 400, "universe symbol before any scan: ok, tracked, live")
        T.check(p.urls.any { it.contains("/UP02.NS?range=2y&interval=1d") } && p.urls.any { it.contains("/UP02.NS?range=5d&interval=15m") } && p.urls.any { it.contains("/%5ENSEI?range=2y") }, "fetched 2y daily + 5d/15m (and Nifty for comparisons)")
        T.check(p.files.entries.any { it.key.startsWith("daily_cache_") && it.value.contains("\"UP02\"") }, "universe symbol stored in the file cache")
        T.check(r.list("setups").map { it.asObj() }.filter { it.str("id") in setOf("LEADER_DIP", "MONTHLY_LEADERS", "WASHOUT_DIP") }.all { it.str("text").contains("full scan") }, "cross-sectional rules say they need a full scan")
        T.check(r.obj("vsNifty").num("d63") != 0.0, "vs Nifty filled")
        // scan, then a tracked symbol uses the cache (only the quote is fetched)
        T.check(app.tick("alarm").contains("OK"), "scan")
        p.urls.clear()
        r = info(app, "UP01", "after scan")
        T.check(r["ok"] == true && p.urls.none { it.contains("range=2y") } && p.urls.count { it.contains("/UP01.NS?range=5d") } == 1, "fresh cached history used; one quote fetched: " + p.urls)
        T.check(r.list("setups").none { it.asObj().str("text").contains("full scan") }, "after a scan the strategy checks use its context")
        val ltp = r.num("ltp")
        T.check(ltp > 0 && r.num("prevClose") > 0 && Math.abs(r.num("dayChgPct") - Math.round((ltp / r.num("prevClose") - 1) * 10000) / 100.0) < 0.011, "day change maths")
        T.check(r.num("dayHigh") >= ltp && r.num("dayLow") <= ltp && r.num("high52") >= r.num("dayHigh") && r.num("low52") <= r.num("dayLow"), "price ranges consistent")
        T.check(r.obj("levels").num("support") == r.num("low20") && r.obj("levels").num("resistance") >= r.num("high20") - 0.001, "support = 20-day low, resistance >= 20-day high")
        T.eq(r.long("asOf") > Clock.msAt(d0, Clock.OPEN_MIN) && r.long("asOf") <= p.nowMs, true, "asOf = the quote time")
        T.eq(r.int("barDay"), 20261006, "barDay yyyymmdd")
        val e = r.obj("ema")
        T.check(listOf("e10", "e20", "e50", "e100", "e200").all { e.num(it) > 0 }, "all EMAs with 400+ bars")
        T.near(r.obj("emaDist").num("e50"), Math.round((ltp / e.num("e50") - 1) * 10000) / 100.0, 0.011, "emaDist = price vs EMA in %")
        // the portfolio verdict matches what holding it would say
        T.check(act(app, "addHolding", """{"sym":"UP01","qty":10,"avg":100}""")["ok"] == true, "add as holding")
        val st = Json.parseObject(app.stateJson())!!
        val h = st.list("holdings").map { it.asObj() }.first { it.str("sym") == "UP01" }
        r = info(app, "UP01", "as holding")
        T.check(r.bool("isHolding") && r.obj("verdict").str("label") == h.str("verdict") && r.int("strength") == h.int("strength") && Math.abs(r.obj("levels").num("trailStop") - h.num("stop")) < 0.011,
            "verdict/strength/trailStop = holdings check: ${r.obj("verdict")} ${r.int("strength")} ${r.obj("levels")} vs ${h.str("verdict")} ${h.int("strength")} ${h.num("stop")}")
        T.eq(act(app, "addWatch", """{"sym":"UP01"}""")["ok"], true, "watch it")
        T.eq(info(app, "UP01", "watched").bool("inWatch"), true, "inWatch flag")
        // decorated input like addHolding
        T.eq(info(app, " nse:up03-eq ", "decorated").str("sym"), "UP03", "symbol cleaned like addHolding")
        // non-universe symbol: works, not cached to file, kept in memory
        p.urls.clear()
        r = info(app, "XTRA1", "non-universe")
        T.check(r["ok"] == true && !r.bool("tracked") && r.str("name") == "XTRA1" && r.int("bars") > 400, "non-universe symbol ok: " + r.str("name"))
        T.check(p.files.entries.none { it.key.startsWith("daily_cache_") && it.value.contains("\"XTRA1\"") }, "one-off symbol not written to the file cache")
        val calls = p.urlCount
        r = info(app, "XTRA1", "again")
        T.eq(p.urlCount, calls, "second look within a minute: served from memory, no network")
        p.nowMs += 5 * 60_000
        info(app, "XTRA1", "later")
        T.check(p.urls.count { it.contains("/XTRA1.NS?range=2y") } == 1 && p.urls.count { it.contains("/XTRA1.NS?range=5d") } == 2, "later: history from memory, quote refreshed")
        // LRU keeps ~20
        p.yahoo.defaultProfile = Profile(0.1, 1.0)
        for (k in 1..22) T.eq(act(app, "stockInfo", """{"sym":"ONE$k"}""")["ok"], true, "one-off ONE$k")
        p.urls.clear()
        act(app, "stockInfo", """{"sym":"XTRA1"}""")
        T.eq(p.urls.count { it.contains("/XTRA1.NS?range=2y") }, 1, "oldest one-off evicted after 20 others")
        p.yahoo.defaultProfile = null
        // unknown / invalid
        r = act(app, "stockInfo", """{"sym":"NOSUCHX"}""")
        T.check(r["ok"] == false && r.str("sym") == "NOSUCHX" && r.str("error") == "No price data found for NOSUCHX. Check the symbol.", "unknown symbol -> SPEC2 failure: $r")
        for (bad in listOf("""{"sym":"A B"}""", """{"sym":""}""", "{}", "garbage", """{"sym":"^NSEI"}""", """{"sym":"${"X".repeat(30)}"}"""))
            T.check(act(app, "stockInfo", bad).let { it["ok"] == false && it.str("error").isNotEmpty() && it.containsKey("sym") }, "invalid symbol rejected with sym+error: $bad")
        T.eq(act(app, "stockInfo", """{"sym":"ERRSYM"}""")["ok"], false, "chart.error symbol")
        T.eq(act(app, "stockInfo", """{"sym":"GARB"}""")["ok"], false, "garbage responses")
        T.eq(info(app, "H2ONLY", "fallback host")["ok"], true, "fallback host used")
        T.eq(info(app, "NULLY", "null holes")["ok"], true, "null holes in the data")
        T.eq(info(app, "M&M", "ampersand")["ok"], true, "M&M")
        // short histories
        for (sym in listOf("NEWIPO", "TINY")) {
            r = info(app, sym, "short history")
            T.check(r["ok"] == true && r.int("bars") in 25..45, "$sym short history ok (${r.int("bars")} bars)")
            T.check(r.obj("ema").num("e50") == 0.0 && r.obj("ema").num("e100") == 0.0 && r.obj("ema").num("e200") == 0.0 && r.obj("ema").num("e10") > 0, "$sym: long EMAs 0, short EMAs shown")
            T.check(r.obj("emaDist").num("e200") == 0.0 && r.obj("golden").int("daysSince") == -1 && r.obj("golden").str("text").contains("Not enough history"), "$sym: golden unknown")
            T.check(r.list("checks").map { it.asObj() }.any { !it.bool("ok") && it.str("text").contains("Not enough history") }, "$sym: a check says not enough history")
            T.check(r.int("strength") == 0 && r.obj("verdict").str("label") == "WATCH" && r.obj("levels").num("trailStop") == 0.0, "$sym: no verdict without 60 bars")
            T.check(r.obj("ret").num("d250") == 0.0 && r.obj("ret").num("d63") == 0.0 && r.num("high52") > 0, "$sym: long returns 0, range from what exists")
        }
        // a thin stock says so
        r = info(app, "THIN", "illiquid")
        T.check(!r.bool("liquid") && r.list("checks")[0].asObj().let { !it.bool("ok") && it.str("label").contains("liquid") } && r.str("summary").contains("thinly traded"), "thin stock flagged first and in the summary: " + r.list("checks")[0])
        T.check(r.list("setups").map { it.asObj() }.first().str("text").contains("thinly traded"), "EMA screen explains the liquidity filter")
        T.check(info(app, "PENNY", "penny").list("checks")[0].asObj().str("text").contains("price under"), "penny stock: price filter mentioned")
    }

    fun offline() {
        val p = FakePlatform.standard()
        val d0 = day(2026, 10, 6)
        p.setTime(d0, 11 * 60 + 1)
        p.offline = true
        var app = SwingApp(p)
        var r = act(app, "stockInfo", """{"sym":"UP01"}""")
        T.check(r["ok"] == false && r.str("sym") == "UP01" && r.str("error").contains("internet"), "offline, nothing cached: clear error: $r")
        T.eq(act(app, "getChart", """{"sym":"UP01"}""")["ok"], false, "chart too")
        p.offline = false
        app.tick("manual")      // a full-quote scan in every mode (TIERED=1 too)
        T.eq(info(app, "XTRA1", "online")["ok"], true, "one-off looked up online")
        p.offline = true
        p.nowMs += 10 * 60_000
        r = info(app, "UP01", "offline with cache")
        T.check(r["ok"] == true && r.bool("live") && r.long("asOf") < p.nowMs - 9 * 60_000, "offline with cache: last scan's quote, asOf shows its age")
        r = info(app, "XTRA1", "offline one-off")
        T.check(r["ok"] == true && r.bool("live") && r.long("asOf") < p.nowMs - 9 * 60_000, "offline one-off: history and last quote from memory")
        // restart while offline: the file cache still serves tracked symbols
        app = SwingApp(p)
        r = info(app, "UP01", "offline after restart")
        T.check(r["ok"] == true && !r.bool("live") && r.int("bars") > 400, "offline after restart: cached history, live=false, asOf = last close")
        T.eq(Clock.minuteOfDay(r.long("asOf")), Clock.CLOSE_MIN, "asOf is the last close")
        T.eq(act(app, "getChart", """{"sym":"UP01"}""")["ok"], true, "chart from cache offline")
        T.check(act(app, "stockInfo", """{"sym":"XTRA1"}""").let { it["ok"] == false && it.str("error").contains("internet") }, "one-off after restart offline: error")
    }

    // ------------------------------------------------------------------ EMA identity: watch == stockInfo == getChart
    fun emaEqual() {
        val p = FakePlatform.standard()
        val y = p.yahoo
        val dD = day(2026, 10, 6)
        val sym = "UP05"
        // script a fresh 10/20 cross on dD: a short dip then a jump; pick the jump size from the same EMA maths
        val days = y.tradingDaysUpTo(dD, 521)
        val dip = days.takeLast(7).dropLast(1)
        var px = y.bar(sym, days[days.size - 8])!![3]
        for (d in dip) { val c = px * 0.985; y.setBar(sym, d, px, px * 1.002, c * 0.998, c, 3e6); px = c }
        fun closes(mult: Double): DoubleArray = DoubleArray(days.size) { k -> if (k == days.size - 1) Math.round(px * mult * 100) / 100.0 else Math.round(y.bar(sym, days[k])!![3] * 100) / 100.0 }
        var mult = 0.0
        var m = 1.0
        while (m < 1.3) {
            val c = closes(m)
            val e10 = Ema.line(c, c.size, 10); val e20 = Ema.line(c, c.size, 20); val e50 = Ema.line(c, c.size, 50); val e100 = Ema.line(c, c.size, 100); val e200 = Ema.line(c, c.size, 200)
            val k = c.size - 1
            if (e10[k] > e20[k] && e10[k - 1] <= e20[k - 1] && e10[k] > e50[k] && e10[k] > e100[k] && e10[k] > e200[k]) { mult = m + 0.01; break }
            m += 0.005
        }
        T.check(mult > 0, "found a jump that makes a day-0 cross")
        y.setBar(sym, dD, px, px * mult * 1.001, px * 0.999, px * mult, 3e6)
        p.setTime(dD, 16 * 60)
        val app = SwingApp(p)
        T.check(app.tick("manual").contains("OK"), "scan")
        val st = Json.parseObject(app.stateJson())!!
        val w = st.list("watch").map { it.asObj() }.firstOrNull { it.str("sym") == sym }
        T.check(w != null && w.int("daysSince") == 0, "scripted symbol listed on the EMA screen as day 0: " + st.list("watch").map { it.asObj().str("sym") + "/" + it.asObj().int("daysSince") })
        val r = info(app, sym, "ema identity")
        val ch = act(app, "getChart", """{"sym":"$sym","bars":500}""")
        fun last(k: String) = ch.list(k).last() as Double
        val e = r.obj("ema")
        for ((wk, sk, ck) in listOf(Triple("ema10", "e10", "ema10"), Triple("ema20", "e20", "ema20"), Triple("ema50", "e50", "ema50"), Triple("ema100", "e100", "ema100"), Triple("ema200", "e200", "ema200"))) {
            T.check(e.num(sk) > 0 && e.num(sk) == last(ck), "$sk: stockInfo ${e.num(sk)} == chart ${last(ck)}")
            if (w != null) T.check(w.num(wk) == e.num(sk), "$wk: watch ${w.num(wk)} == stockInfo ${e.num(sk)}")
        }
        if (w != null) {
            T.eq(r.int("barDay"), w.int("barDay"), "same bar")
            T.near(w.num("gapPct"), r.obj("cross").num("gapPct"), 1e-9, "same gap")
            T.eq(w.num("ltp"), r.num("ltp"), "same price")
        }
        T.check(r.obj("cross").int("daysSince") == 0 && r.obj("cross").str("state") == "ABOVE", "stockInfo sees the day-0 cross")
        T.check(r.list("setups").map { it.asObj() }.first().let { it.bool("match") && it.str("id") == "EMA_CROSS" }, "EMA_CROSS setup matches")
        // every other universe symbol: chart and stock page agree, and the watch predicate agrees with the setup
        for (s2 in listOf("UP01", "DN02", "FL03", "HB01", "M&M")) {
            val ri = info(app, s2, "agree")
            val cj = act(app, "getChart", """{"sym":"$s2"}""")
            T.check(ri.obj("ema").num("e10") == cj.list("ema10").last() && ri.obj("ema").num("e200") == cj.list("ema200").last(), "$s2: stockInfo EMAs == chart's last bar")
            val listed = st.list("watch").any { it.asObj().str("sym") == s2 }
            T.eq(ri.list("setups").map { it.asObj() }.first().bool("match"), listed, "$s2: EMA_CROSS match == listed on the screen")
        }
    }

    // ------------------------------------------------------------------ getChart v2
    fun chart() {
        val p = FakePlatform.standard()
        p.setTime(day(2026, 10, 6), 10 * 60 + 1)
        val app = SwingApp(p)
        app.tick("alarm")
        val keys = setOf("ok", "sym", "name", "d", "o", "h", "l", "c", "v", "sma50", "sma200", "ema10", "ema20", "ema50", "ema100", "ema200", "levels", "stats")
        fun arrays(r: Map<String, Any?>) = listOf("d", "o", "h", "l", "c", "v", "sma50", "sma200", "ema10", "ema20", "ema50", "ema100", "ema200").map { r.list(it).size }
        var r = act(app, "getChart", """{"sym":"UP01"}""")
        T.eq(r.keys, keys, "chart keys (old ones kept, ema100 and v added)")
        T.check(arrays(r).all { it == 130 }, "default 130 bars: " + arrays(r))
        T.check(r.list("v").all { it is Double && (it as Double) > 0 }, "volume per bar")
        T.check(r.obj("stats").keys == setOf("ltp", "sma50", "sma200", "rsi14", "high52", "low52", "ret5", "ret21", "ret63", "ret126", "bars"), "stats unchanged")
        val n = r.obj("stats").int("bars")
        for ((ask, want) in listOf(260 to 260, 500 to 500, 5000 to n, 0 to 130, -4 to 130)) {
            r = act(app, "getChart", """{"sym":"UP01","bars":$ask}""")
            T.check(arrays(r).all { it == minOf(want, n) }, "bars $ask -> ${minOf(want, n)}: " + arrays(r).toSet())
        }
        r = act(app, "getChart", """{"sym":"UP01","bars":500}""")
        val all = r.list("ema100").map { it as Double }
        T.check(all.take(500 - (n - 150)).all { it == 0.0 } || n - 500 >= 150, "ema100 is 0 before 150 bars of warm-up")
        T.check(all.takeLast(10).all { it > 0 }, "ema100 shown afterwards")
        // any NSE symbol now
        r = act(app, "getChart", """{"sym":"XTRA1","bars":260}""")
        T.check(r["ok"] == true && arrays(r).all { it == 260 }, "non-universe symbol charted (fetched)")
        r = act(app, "getChart", """{"sym":"NOSUCHX"}""")
        T.check(r["ok"] == false && r.str("sym") == "NOSUCHX" && r.str("error").contains("No price data found"), "unknown symbol: stockInfo failure shape")
        T.eq(act(app, "getChart", """{"sym":"NEWIPO"}""").list("c").size, act(app, "getChart", """{"sym":"NEWIPO"}""").obj("stats").int("bars"), "short history: what exists")
        // levels of a holding still drawn
        act(app, "addHolding", """{"sym":"UP02","qty":5,"avg":123.45}""")
        T.check(act(app, "getChart", """{"sym":"UP02"}""").list("levels").any { it.asObj().str("label") == "Your avg" && it.asObj().num("v") == 123.45 }, "holding levels on the chart")
    }

    // ------------------------------------------------------------------ watchlist
    fun watchlist() {
        val savedFull = Params.FULL_QUOTE_MAX_UNIVERSE
        Params.FULL_QUOTE_MAX_UNIVERSE = 0     // tiered quotes: only index/holdings/trades/watchlist get a quote outside the buy window
        try {
            val p = FakePlatform.standard()
            val d0 = day(2026, 10, 6)
            p.setTime(d0, 8 * 60 + 30)
            var app = SwingApp(p)
            var st = SimTests.state(app, "empty watchlist")
            T.eq(st.list("myWatch").size, 0, "myWatch present and empty")
            T.eq(act(app, "addWatch", """{"sym":"xtra1"}""")["ok"], true, "addWatch (lower case)")
            T.eq(act(app, "addWatch", """{"sym":"UP03"}""")["ok"], true, "addWatch UP03")
            T.eq(act(app, "addWatch", """{"sym":"NOSUCHX"}""")["ok"], true, "any well-formed symbol accepted")
            T.eq(act(app, "addWatch", """{"sym":"UP03"}""")["ok"], true, "adding twice is fine")
            for (bad in listOf("""{"sym":"A B"}""", """{"sym":""}""", "{}", """{"sym":"!!"}""")) T.eq(act(app, "addWatch", bad)["ok"], false, "bad symbol rejected: $bad")
            st = SimTests.state(app, "watchlist added")
            val mw = st.list("myWatch").map { it.asObj() }
            T.eq(mw.map { it.str("sym") }, listOf("XTRA1", "UP03", "NOSUCHX"), "order kept, no duplicates")
            T.check(mw[0].bool("dataOk") && mw[0].num("ltp") > 0 && mw[0].list("spark").size == 30 && mw[0].int("strength") > 0 && mw[0].str("crossText").contains("EMA"), "prices load right away: " + mw[0])
            T.check(mw[1].str("name") == "Uptrend 3 Ltd" && mw[1].bool("dataOk"), "universe name")
            T.check(!mw[2].bool("dataOk") && mw[2].num("ltp") == 0.0 && mw[2].list("spark").isEmpty() && mw[2].int("strength") == 0, "unknown symbol: dataOk false with zeros")
            T.eq(act(app, "removeWatch", """{"sym":"NOSUCHX"}""")["ok"], true, "removeWatch")
            T.eq(act(app, "removeWatch", """{"sym":"NOSUCHX"}""")["ok"], false, "removing twice fails politely")
            // watchlist quotes on every scan, even when only holdings get quotes
            p.keepUrls = true
            p.setTime(d0, 10 * 60 + 1); app.tick("alarm")
            p.urls.clear()
            p.setTime(d0, 10 * 60 + 16); app.tick("alarm")
            T.check(p.urls.any { it.contains("/XTRA1.NS?range=5d") } && p.urls.any { it.contains("/UP03.NS?range=5d") }, "watchlist symbols quoted in a non-full scan")
            T.check(p.urls.none { it.contains("/UP04.NS?range=5d") }, "(other universe stocks are not, in this tiered mode)")
            T.check(p.files.entries.any { it.key.startsWith("daily_cache_") && it.value.contains("\"XTRA1\"") }, "watchlist history kept in the file cache")
            val ltp1 = SimTests.state(app, "after scans").list("myWatch").map { it.asObj() }.first().num("ltp")
            T.check(ltp1 > 0, "myWatch price after scans")
            // next day: history fetched once like holdings
            p.urls.clear()
            p.setTime(d0 + 1, 9 * 60 + 20); app.tick("alarm")
            T.eq(p.urls.count { it.contains("query1") && it.contains("/XTRA1.NS?range=2y") }, 1, "next day: watchlist history refreshed once")
            // persistence across restart
            app = SwingApp(p)
            st = SimTests.state(app, "after restart")
            T.eq(st.list("myWatch").map { it.asObj().str("sym") }, listOf("XTRA1", "UP03"), "watchlist survives a restart")
            p.setTime(d0 + 1, 9 * 60 + 31); app.tick("alarm")
            T.check(SimTests.state(app, "restart + tick").list("myWatch").all { it.asObj().bool("dataOk") }, "data back after the first tick")
            T.check(Json.parseObject(p.files[SwingApp.STATE_FILE])!!.strList("watchlist") == listOf("XTRA1", "UP03"), "persisted as 'watchlist'")
            // cap of 50
            for (k in 3..50) T.eq(act(app, "addWatch", """{"sym":"W$k"}""")["ok"], true, "add W$k")
            T.eq(SimTests.state(app, "50").list("myWatch").size, 50, "50 symbols")
            val full = act(app, "addWatch", """{"sym":"W51"}""")
            T.check(full["ok"] == false && full.str("error").contains("50"), "51st refused: $full")
            T.eq(act(app, "addWatch", """{"sym":"W50"}""")["ok"], true, "re-adding an existing one when full is fine")
            // old state files without the field, and junk in it
            val m = LinkedHashMap(Json.parseObject(p.files[SwingApp.STATE_FILE])!!)
            m.remove("watchlist")
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            st = SimTests.state(SwingApp(p), "old state file")
            T.check(st.list("myWatch").isEmpty() && st.long("lastScanAt") > 0, "old state file loads, empty watchlist")
            m["watchlist"] = listOf("", "a b", 5, null, "up01", "UP01", "M&M", "x".repeat(40), mapOf("a" to 1))
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            T.eq(SimTests.state(SwingApp(p), "junk watchlist").list("myWatch").map { it.asObj().str("sym") }, listOf("UP01", "M&M"), "junk entries dropped")
            m["watchlist"] = "nope"
            p.files[SwingApp.STATE_FILE] = Json.stringify(m)
            T.eq(SimTests.state(SwingApp(p), "watchlist not a list").list("myWatch").size, 0, "non-list watchlist ignored")
        } finally {
            Params.FULL_QUOTE_MAX_UNIVERSE = savedFull
        }
    }

    // ------------------------------------------------------------------ threads
    fun notBlocked() {
        val p = FakePlatform.standard()
        p.setTime(day(2026, 10, 6), 10 * 60 + 1)
        val app = SwingApp(p)
        p.assets["symbols.csv"] = SYMBOLS_CSV
        p.httpDelayMs = 300
        var err: Throwable? = null
        val th = Thread { try { app.tick("alarm") } catch (t: Throwable) { err = t } }
        th.start()
        Thread.sleep(150)
        var worstSearch = 0L
        var n = 0
        val tS = System.nanoTime()
        val r = Json.parseObject(app.action("stockInfo", """{"sym":"UP07"}"""))
        val infoMs = (System.nanoTime() - tS) / 1_000_000
        val aliveAfterInfo = th.isAlive
        val tc = System.nanoTime()
        val c = Json.parseObject(app.action("getChart", """{"sym":"XTRA1"}"""))
        val chartMs = (System.nanoTime() - tc) / 1_000_000
        while (th.isAlive && n < 200) {
            val t0 = System.nanoTime()
            val s = Json.parseObject(app.action("search", """{"q":"up0"}"""))
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms > worstSearch) worstSearch = ms
            if (s?.list("results")?.isEmpty() != false) T.check(false, "search answered during the scan")
            n++
            Thread.sleep(10)
        }
        val aliveAtEnd = th.isAlive
        th.join()
        p.httpDelayMs = 0
        println("      during a slow scan: stockInfo $infoMs ms, getChart $chartMs ms, $n searches (worst $worstSearch ms)")
        T.check(err == null, "scan finished cleanly")
        T.check(r?.get("ok") == true && aliveAfterInfo, "stockInfo answered while the scan was still running ($infoMs ms)")
        T.check(c?.get("ok") == true && infoMs < 2000 && chartMs < 2000, "getChart answered during the scan too ($chartMs ms)")
        T.check(n > 5 && worstSearch < 50, "search never waited for the scan lock: $n calls, worst $worstSearch ms (scan still running at the end: $aliveAtEnd)")
        T.eq(SimTests.state(app, "after slow scan")["busy"], null, "busy cleared after the scan (reads did not clear it early or leave it set)")
    }

    fun concurrent() {
        val p = FakePlatform.standard()
        p.yahoo.profiles["TINY"] = Profile(0.1, 1.2); p.yahoo.shortHistory["TINY"] = 40
        p.setTime(day(2026, 10, 6), 10 * 60 + 1)
        p.assets["symbols.csv"] = SYMBOLS_CSV
        val app = SwingApp(p)
        p.httpDelayMs = 15
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        fun check(name: String, txt: String) {
            val o = Json.parseObject(txt)
            if (o == null || o["ok"] !is Boolean) errs.add("$name: bad result " + txt.take(80))
            if (txt.contains("Something went wrong") || txt.contains("NaN")) errs.add("$name: " + txt.take(120))
        }
        val syms = listOf("UP01", "XTRA1", "NOSUCHX", "NEWIPO", "M&M", "DN02", "TINY", "THIN", "H2ONLY", "UP05")
        val threads = listOf(
            Thread { for (k in 0 until 4) { val r = app.tick("manual"); if (r.startsWith("Error")) errs.add("tick: $r") } },
            Thread { for (k in 0 until 25) check("stockInfo", app.action("stockInfo", """{"sym":"${syms[k % syms.size]}"}""")) },
            Thread { for (k in 0 until 25) check("stockInfo2", app.action("stockInfo", """{"sym":"${syms[(k * 3 + 1) % syms.size]}"}""")) },
            Thread { for (k in 0 until 400) check("search", app.action("search", """{"q":"${listOf("up", "tata", "l", "m&m", "lt", "x")[k % 6]}"}""")) },
            Thread { for (k in 0 until 25) check("getChart", app.action("getChart", """{"sym":"${syms[(k * 7) % syms.size]}","bars":${listOf(130, 260, 500)[k % 3]}}""")) },
            Thread { for (k in 0 until 10) { check("addWatch", app.action("addWatch", """{"sym":"${syms[k]}"}""")); check("removeWatch", app.action(if (k % 2 == 0) "removeWatch" else "addWatch", """{"sym":"${syms[k]}"}""")) } },
            Thread { for (k in 0 until 200) { if (Json.parseObject(app.stateJson()) == null) errs.add("stateJson invalid"); Thread.sleep(2) } }
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        p.httpDelayMs = 0
        T.check(errs.isEmpty(), "no errors with scans + stockInfo + search + getChart + watchlist edits in parallel: " + errs.take(3))
        T.check(p.logs.none { it.contains("failed") || it.contains("threw") || it.contains("refs:") || it.contains("scan context") }, "nothing failed internally: " + p.logs.filter { it.contains("failed") }.take(2))
        val st = SimTests.state(app, "after concurrency")
        T.eq(st.list("myWatch").map { it.asObj().str("sym") }.toSet(), setOf("XTRA1", "NEWIPO", "DN02", "THIN", "UP05"), "watchlist edits all applied")
        T.check(st.list("myWatch").map { it.asObj() }.filter { it.str("sym") != "NOSUCHX" }.all { it.bool("dataOk") }, "watch rows have data")
        T.eq(info(app, "UP01", "after concurrency")["ok"], true, "still answering")
    }
}
