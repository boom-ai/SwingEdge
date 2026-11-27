package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*
import java.io.File

/** nightly-calls: holidays / next trading day, evening schedule, rule triggers and fills, csv round trip, phone desk, fixture back-test. */
object NightTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "night-holidays" to ::holidays, "night-schedule" to ::schedule, "night-rules" to ::rules, "night-csv" to ::csv,
        "night-desk" to ::desk, "night-build" to ::buildSynthetic, "night-swing-rules" to ::swingRules, "night-conv" to ::convFit, "night-fixture-backtest" to ::fixture).map { (n, f) -> n to { withHolidays(f) } }

    /** Real NSE holidays on for the body (the harness switches them off for the legacy synthetic-calendar simulations). */
    private fun withHolidays(f: () -> Unit) { val was = Holidays.enabled; Holidays.enabled = true; try { f() } finally { Holidays.enabled = was } }

    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()

    fun holidays() {
        val hol = Holidays.BUNDLED
        T.check(Holidays.isHoliday(day(2026, 11, 24), hol), "Guru Nanak Jayanti 24 Nov 2026 is a holiday")
        T.check(!Holidays.isTradingDay(day(2026, 12, 25), hol) && Holidays.isTradingDay(day(2026, 12, 24), hol), "Christmas closed, 24 Dec open")
        T.eq(Holidays.nextTradingDay(day(2026, 11, 23), hol), day(2026, 11, 25), "Mon 23 Nov -> Wed 25 Nov (Tue holiday)")
        T.eq(Holidays.nextTradingDay(day(2026, 12, 24), hol), day(2026, 12, 28), "Thu 24 Dec -> Mon 28 Dec (Fri holiday + weekend)")
        T.eq(Holidays.nextTradingDay(day(2026, 11, 27), hol), day(2026, 11, 30), "Fri 27 Nov -> Mon 30 Nov")
        T.eq(Holidays.nextTradingDay(day(2026, 11, 28), hol), day(2026, 11, 30), "Sat -> Mon")
        T.eq(Holidays.prevTradingDay(day(2026, 11, 25), hol), day(2026, 11, 23), "prev of Wed 25 Nov = Mon 23 Nov")
        T.eq(Holidays.nextTradingDay(day(2026, 3, 2), hol), day(2026, 3, 4), "Holi 3 Mar skipped")
        T.check(!Holidays.isHoliday(day(2026, 11, 8), hol), "a holiday on a Sunday is not a weekday holiday")
        // NSE API answer and our own format
        val api = """{"CM":[{"tradingDate":"26-Jan-2027","weekDay":"Tuesday","description":"Republic Day","Sr_no":1},{"tradingDate":"15-Aug-2027","description":"Independence Day (Sunday)"},{"tradingDate":"09-Mar-2027","description":"Holi"}],"FO":[{"tradingDate":"01-Jan-2027","description":"x"}]}"""
        val m = Holidays.parse(api)
        T.eq(m.keys.toList(), listOf(day(2027, 1, 26), day(2027, 3, 9)), "API: CM list, weekend dates dropped, FO ignored")
        T.eq(m[day(2027, 3, 9)], "Holi", "API description")
        val enc = Holidays.encode(m, "test", "now")
        T.eq(Holidays.parse(enc), m, "our holidays.json round trip")
        T.check(Holidays.parse("<html>Access denied</html>").isEmpty() && Holidays.parse(null).isEmpty() && Holidays.parse("{\"CM\":5}").isEmpty(), "garbage -> empty")
        val committed = Holidays.parse(File("data/holidays.json").readText())
        T.check(committed.size >= 16 && committed.keys.containsAll(Holidays.BUNDLED), "committed data/holidays.json holds the bundled list (${committed.size})")
        val before = Holidays.extra
        try {
            T.check(Holidays.install(api), "install")
            T.check(Holidays.isHoliday(day(2027, 3, 9)) && Holidays.isHoliday(day(2026, 11, 24)), "installed + bundled both count")
            T.check(!Holidays.install("junk"), "junk not installed")
            T.check(Holidays.isHoliday(day(2027, 3, 9)), "junk leaves the last good list")
        } finally { Holidays.extra = before }
        // market clock honours holidays
        T.check(!Clock.isMarketOpen(Clock.msAt(day(2026, 11, 24), 11 * 60)), "market closed on 24 Nov 2026 at 11:00")
        T.check(Clock.isMarketOpen(Clock.msAt(day(2026, 11, 25), 11 * 60)), "market open on 25 Nov 2026 at 11:00")
        T.eq(Clock.epochDay(Clock.nextWake(Clock.msAt(day(2026, 11, 23), 16 * 60))), day(2026, 11, 25), "scan wakes skip the holiday")
    }

    fun schedule() {
        val hol = Holidays.BUNDLED
        T.check(NightCalls.notifyEvening(day(2026, 11, 27), hol) && NightCalls.targetOf(day(2026, 11, 27)) == day(2026, 11, 30), "Fri 27 Nov evening announces Mon 30 Nov")
        T.check(!NightCalls.notifyEvening(day(2026, 11, 28), hol) && !NightCalls.notifyEvening(day(2026, 11, 29), hol), "no Sat/Sun notification")
        T.check(!NightCalls.notifyEvening(day(2026, 11, 23), hol), "Mon 23 Nov: tomorrow is a holiday -> skip")
        T.check(NightCalls.notifyEvening(day(2026, 11, 24), hol) && NightCalls.targetOf(day(2026, 11, 24)) == day(2026, 11, 25), "holiday evening Tue 24 Nov announces Wed 25")
        T.check(!NightCalls.notifyEvening(day(2026, 12, 24), hol) && NightCalls.notifyEvening(day(2026, 12, 25), hol), "Christmas Eve skipped; Christmas (Fri) evening announces Mon 28 Dec")
        T.check(NightCalls.notifyEvening(day(2026, 12, 1), hol), "ordinary Tue evening")
        val fri = day(2026, 11, 27)
        T.eq(NightCalls.nextWake(Clock.msAt(fri, 18 * 60), 0, hol), Clock.msAt(fri, NightCalls.FETCH_MIN), "Fri 18:00 -> 20:35 fetch")
        T.eq(NightCalls.nextWake(Clock.msAt(fri, NightCalls.FETCH_MIN), 0, hol), Clock.msAt(fri, 22 * 60), "after fetch -> 22:00")
        T.eq(NightCalls.nextWake(Clock.msAt(fri, 22 * 60), 0, hol), Clock.msAt(fri, 22 * 60 + 30), "22:00 not yet announced -> retry 22:30")
        T.eq(NightCalls.nextWake(Clock.msAt(fri, 22 * 60), day(2026, 11, 30), hol), Clock.msAt(day(2026, 11, 30), NightCalls.FETCH_MIN), "announced -> Mon 20:35")
        T.eq(NightCalls.nextWake(Clock.msAt(day(2026, 11, 28), 12 * 60), day(2026, 11, 30), hol), Clock.msAt(day(2026, 11, 30), NightCalls.FETCH_MIN), "weekend -> Mon evening")
        T.eq(NightCalls.nextWake(Clock.msAt(day(2026, 11, 23), 23 * 60 + 45), 0, hol), Clock.msAt(day(2026, 11, 24), NightCalls.FETCH_MIN), "Mon 23 Nov skipped; holiday evening wakes")
        T.eq(Clock.hhmm(Clock.minuteOfDay(Clock.msAt(fri, NightCalls.NOTIFY_MIN))), "22:00", "notify at 22:00 IST")
        T.eq(Clock.msAt(fri, NightCalls.NOTIFY_MIN), java.time.ZonedDateTime.of(2026, 11, 27, 22, 0, 0, 0, java.time.ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli(), "22:00 IST in epoch ms")
    }

    private fun series(bars: List<DoubleArray>, start: Int = day(2026, 1, 1)): Series {
        val s = Series("X", bars.size + 1); var d = start
        for (b in bars) { while (!Clock.isWeekday(d)) d++; s.add(d, b[0], b[1], b[2], b[3], if (b.size > 4) b[4] else 1e6); d++ }
        return s
    }

    fun rules() {
        // qty: ₹1,000 risk, capped by ₹1 lakh capital
        T.eq(NightCalls.qty(500.0, 490.0), 100, "risk 10 -> 100 shares")
        T.eq(NightCalls.qty(500.0, 499.0), 200, "capital cap 1,00,000 / 500")
        T.eq(NightCalls.qty(500.0, 480.0, 0.5), 25, "half size")
        T.eq(NightCalls.qty(500.0, 510.0), 100, "short side distance")
        T.eq(NightCalls.qty(0.0, 1.0), 0, "bad price -> 0")
        val buy = NightCalls.Sig("nr7", "BUY", 100.0, 98.0, 104.0, "")
        T.check(NightCalls.simStop(buy, 99.0, 99.9, 97.0, 99.5) == null, "high below trigger: no trade")
        T.near(NightCalls.simStop(buy, 99.0, 105.0, 99.0, 103.0)!!, 2.0 - 0.001 * 100 / 2, 1e-9, "target hit: +2R minus costs")
        T.near(NightCalls.simStop(buy, 99.0, 105.0, 97.5, 103.0)!!, 2.0 - 0.05, 1e-9, "up-close day: the low comes before the trigger, so the stop is not hit")
        T.near(NightCalls.simStop(buy, 99.0, 105.0, 97.5, 103.0, worst = true)!!, -1.0 - 0.05, 1e-9, "worst case: the stop counts whenever the day touched it")
        T.near(NightCalls.simStop(buy, 99.0, 101.0, 97.5, 98.0)!!, -1.0 - 0.05, 1e-9, "down-close day: triggered on the way up, stopped on the way down")
        T.near(NightCalls.simStop(buy, 99.0, 101.0, 99.0, 100.5)!!, 0.25 - 0.05, 1e-9, "no hit: close exit")
        T.near(NightCalls.simStop(buy, 101.0, 102.0, 100.5, 101.5)!!, (101.5 - 101.0) / 3.0 - 0.001 * 101 / 3.0, 1e-9, "gap through the trigger fills at the open")
        T.near(NightCalls.simStop(buy, 97.0, 101.0, 96.0, 100.5)!!, (100.5 - 100.0) / 2.0 - 0.05, 1e-9, "open under the stop before entry: not stopped (not in the trade yet)")
        val sell = NightCalls.Sig("nr7", "SELL", 100.0, 102.0, 96.0, "")
        T.near(NightCalls.simStop(sell, 101.0, 101.5, 95.0, 96.5)!!, 2.0 - 0.05, 1e-9, "short target")
        T.check(NightCalls.simStop(sell, 101.0, 101.5, 100.5, 101.0) == null, "short not triggered")
        // gap rules
        T.check(NightCalls.simGapFade(100.0, 100.1, 100.5, 99.0, 99.5) == null, "0.1% gap: no fade")
        T.near(NightCalls.simGapFade(100.0, 100.5, 100.6, 99.9, 100.2)!!, 1.0 - 0.001 * 100.5 / 0.5, 1e-9, "gap up fills back to the previous close")
        T.near(NightCalls.simGapFade(100.0, 100.5, 101.0, 99.9, 100.2)!!, -1.0 - 0.001 * 100.5 / 0.5, 1e-9, "down-close day: stop one gap above the open comes first")
        T.near(NightCalls.simGapFade(100.0, 99.5, 99.8, 99.2, 99.7)!!, 0.4 - 0.001 * 99.5 / 0.5, 1e-9, "gap down: long, close exit")
        T.check(NightCalls.simGapGo(100.0, 101.0, 2.0, 101.0, 105.0, 100.0, 104.0) == null, "1% gap: no gap-and-go")
        T.near(NightCalls.simGapGo(100.0, 101.0, 2.0, 102.0, 106.0, 101.6, 105.0)!!, 2.0 - 0.001 * 102.2 / 0.8, 1e-9, "gap-and-go target")
        T.check(NightCalls.walk(100.0, 101.0, 99.0, 100.5, true, Double.NaN, 99.5, 102.0) == Pair(100.0, 99.5), "walk: up-close path visits the low first")
        T.check(NightCalls.walk(100.0, 101.0, 99.0, 99.5, true, Double.NaN, 99.5, 101.0) == Pair(100.0, 101.0), "walk: down-close path visits the high first")
        // NR7 / inside day / narrow CPR detection
        val base = ArrayList<DoubleArray>()
        var px = 100.0
        for (k in 0 until 60) { px *= 1.004; val w = 1.5 + (k % 4) * 0.3; base.add(doubleArrayOf(px - 0.5, px + w, px - w, px)) }
        val bars = base + listOf(doubleArrayOf(px, px + 0.6, px - 0.4, px + 0.3))
        val f = NightCalls.Feat(series(bars))
        val i = f.n - 1
        T.check(f.nr7(i) && f.inside(i), "last bar is NR7 and inside")
        T.check(!f.nr7(i - 1), "a normal bar is not NR7")
        val sigs = NightCalls.stopSignals(f, i)
        val n = sigs.firstOrNull { it.rule == "nr7id" }
        T.check(n != null && n.side == "BUY", "uptrend NR7ID -> buy-stop: ${sigs.map { it.rule + it.side }}")
        if (n != null) {
            T.check(n.trigger > bars.last()[1] && n.trigger - bars.last()[1] < 0.2, "trigger just above the high")
            T.near(n.stop, (bars.last()[1] + bars.last()[2]) / 2, 0.05, "stop at the midpoint")
            T.near(n.target - n.trigger, 2 * (n.trigger - n.stop), 0.06, "target 2R")
        }
        T.check(f.cprPct(i) < 0.15, "tight bar -> narrow CPR ${f.cprPct(i)}")
        T.check(f.rsi2[i] in 0.0..100.0 && f.atr[i] > 0 && !f.sma50[i].isNaN() && f.sma200[i].isNaN(), "feature arrays")
        T.near(f.sma50[i], Ind.sma(f.s.c, i, 50), 1e-9, "sma50 matches Ind.sma")
        T.near(f.rsi2[i], Ind.rsi(f.s.c, i, 2, 1000), 1e-6, "rsi2 matches Ind.rsi (Wilder)")
        // swing fill
        val sw = ArrayList<DoubleArray>(); for (k in 0 until 10) sw.add(doubleArrayOf(100.0, 101.0, 99.0, 100.0))
        sw.add(doubleArrayOf(100.0, 100.5, 95.0, 96.0))
        val sf = NightCalls.Feat(series(sw))
        T.near(NightCalls.simSwing(sf, 10, 2.0, 2.0, 3.0, 8, false)!!, -1.0 - 0.0025 * 100 / 4, 1e-9, "swing stop 2 ATR")
        T.check(NightCalls.simSwing(sf, 5, 2.0, 10.0, Double.NaN, 8, false) == null, "trade still open at the end of the data is not counted")
    }

    private fun sample(): NightData {
        val rs = NightCalls.RULES.map { (id, nm, tx) -> RuleStat(id, if (NightCalls.isSwing(id)) "swing" else "intraday", nm, tx) }
        rs[0].trades = 120; rs[0].wins = 54; rs[0].sumR = 9.6; rs[0].sumWorst = -30.0
        val ups = listOf(
            NightSetup("M&M", "Mahindra & Mahindra, Ltd", "nr7", "intraday", "BUY", 3125.5, 3101.0, 3174.5, "15:15", 40, 980.0, true, "After 9:30: buy above ₹3,126, stop ₹3,101", 0.0),
            NightSetup("ITC", "ITC \"Ltd\"", "gapfade", "intraday", "IF GAP", 410.0, 0.0, 410.0, "15:15", 100, 0.0, false, "If it opens ₹411.25–₹414.10 (0.3–1% up): sell", 0.0))
        return NightData(day(2026, 11, 27), day(2026, 11, 30), 1795760813696L, 259, 3400, rs, ups, listOf(IndexLevels("NIFTY", 26310.0, 26100.0, 26250.0)), listOf("note, with comma"))
    }

    fun csv() {
        val d = sample()
        val text = NightCalls.encode(d)
        T.check(text.startsWith("#SWINGEDGE_INTRADAY,v=1,asof=2026-11-27,for=2026-11-30"), "header line")
        val p = NightCalls.parse(text)!!
        T.eq(p.asof, d.asof, "asof"); T.eq(p.forDay, d.forDay, "for"); T.eq(p.sessions, 259, "sessions"); T.eq(p.builtAt, d.builtAt, "builtAt")
        T.eq(p.setups.map { it.sym }, listOf("M&M", "ITC"), "symbols")
        T.eq(p.setups[0].name, "Mahindra & Mahindra, Ltd", "quoted name"); T.eq(p.setups[1].name, "ITC \"Ltd\"", "escaped quotes")
        T.eq(p.setups[0].trigger, 3125.5, "trigger"); T.eq(p.setups[0].qty, 40, "qty"); T.check(p.setups[0].fno && !p.setups[1].fno, "F&O flag")
        T.eq(p.rules.size, NightCalls.RULES.size, "all rules carried"); T.eq(p.rules[0].trades, 120, "rule trades"); T.near(p.rules[0].winPct, 45.0, 1e-9, "win%"); T.near(p.rules[0].avgR, 0.08, 1e-9, "avgR"); T.near(p.rules[0].avgWorst, -0.25, 1e-9, "worst-case avgR")
        T.eq(p.rules[0].status, "TESTED+", "status"); T.eq(p.rules[1].status, "FEW", "no trades -> FEW")
        T.eq(p.index.size, 1, "index row"); T.near(p.index[0].pivot, (26310.0 + 26100.0 + 26250.0) / 3, 1e-6, "pivot")
        T.eq(p.notes, listOf("note, with comma"), "notes")
        T.check(NightCalls.parse(null) == null && NightCalls.parse("404: Not Found") == null && NightCalls.parse(text.replace("v=1", "v=9")) == null, "garbage / other version rejected")
        T.check(NightCalls.parse(text.lines().take(12).joinToString("\n")) == null, "no column header -> rejected")
        T.check(NightCalls.parse(text.replace("for=2026-11-30", "for=2026-11-26")) == null, "for-date not after asof -> rejected")
        T.eq(NightCalls.title(p), "Tomorrow's intraday calls (Mon 30 Nov): 2 setups", "notification title")
        val v = NightCalls.view(p, Clock.msAt(day(2026, 11, 27), 22 * 60), 0)
        T.eq(v["forLabel"], "Mon 30 Nov", "view label"); T.eq((v["setups"] as List<*>).size, 2, "view setups"); T.eq(v["isToday"], false, "not today")
        T.check(Json.parseObject(Json.stringify(v)) != null, "view serialises")
        T.eq(NightCalls.view(null, 0L, 0)["none"], true, "no data view")
        T.eq(NightCalls.fnoSymbols("UNDERLYING ,SYMBOL ,DEC-26\nNIFTY 50 ,NIFTY ,65\nMAHINDRA ,M&M ,200\nRELIANCE INDUSTRIES ,RELIANCE ,500\n"), setOf("M&M", "RELIANCE"), "F&O list without indices")
        val il = NightCalls.indexLevels("Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change\nNifty 50,27-11-2026,26200.1,26310.5,26100.25,26250.0,10\nNifty Bank,27-11-2026,59000,59400,58800,59210,5\nNifty IT,27-11-2026,1,2,1,2,0\n")
        T.eq(il.map { it.name }, listOf("NIFTY", "BANKNIFTY"), "index levels"); T.near(il[0].bc, (26310.5 + 26100.25) / 2, 1e-9, "BC")
        T.check(NightCalls.indexLevels("<html/>").isEmpty(), "bad index file")
        T.eq(NightCalls.fmt(3125.5), "3,126", "indian grouping"); T.eq(NightCalls.fmt(312550.0), "3,12,550", "lakh grouping"); T.eq(NightCalls.fmt(99.5), "99.50", "small price")
    }

    fun desk() {
        val p = FakePlatform()
        val fri = day(2026, 11, 27)
        val good = NightCalls.encode(sample())
        p.served[NightCalls.URL] = good
        p.served[Holidays.URL] = File("data/holidays.json").readText()
        val dk = NightDesk(p)
        p.setTime(fri, 19 * 60); T.check(dk.step(p.nowMs) == null && p.httpCalls == 0, "nothing before 20:35")
        T.eq(dk.nextWake(p.nowMs), Clock.msAt(fri, NightCalls.FETCH_MIN), "wake at 20:35")
        p.setTime(fri, NightCalls.FETCH_MIN); T.check(dk.step(p.nowMs) == null, "20:35: fetch only, no notification")
        T.check(dk.data?.forDay == day(2026, 11, 30) && p.files[NightCalls.FILE] == good, "file downloaded and saved")
        T.check(p.files[Holidays.FILE] != null, "holidays saved")
        p.setTime(fri, 22 * 60); val n = dk.step(p.nowMs)
        T.eq(n?.first, "Tomorrow's intraday calls (Mon 30 Nov): 2 setups", "22:00 notification")
        T.check(dk.step(p.nowMs + 60_000) == null, "posted once")
        T.eq(dk.nextWake(p.nowMs), Clock.msAt(day(2026, 11, 30), NightCalls.FETCH_MIN), "next wake: Monday evening")
        // reload from disk keeps the announced state (reboot)
        val dk2 = NightDesk(p)
        T.check(dk2.step(Clock.msAt(fri, 22 * 60 + 30)) == null, "after a restart the same evening: not posted again")
        T.eq(dk2.view(p.nowMs)["notified"], true, "view knows it was announced")
        // stale file at 22:00: retry until 23:30, then a quiet give-up
        val q = FakePlatform(); q.served[NightCalls.URL] = good.replace("for=2026-11-30", "for=2026-11-28").replace("asof=2026-11-27", "asof=2026-11-26")
        val dq = NightDesk(q); val mon = day(2026, 11, 30)
        q.setTime(mon, 22 * 60); T.check(dq.step(q.nowMs) == null, "old file: no announcement at 22:00")
        q.setTime(mon, 22 * 60 + 30); T.check(dq.step(q.nowMs) == null, "still old at 22:30")
        q.served[NightCalls.URL] = good.replace("for=2026-11-30", "for=2026-12-01").replace("asof=2026-11-27", "asof=2026-11-30")
        q.setTime(mon, 23 * 60); T.eq(dq.step(q.nowMs)?.first, "Tomorrow's intraday calls (Tue 1 Dec): 2 setups", "fresh file at 23:00 -> announced")
        val z = FakePlatform(); z.offline = true; val dz = NightDesk(z)
        z.setTime(mon, NightCalls.LAST_TRY_MIN); T.check(dz.step(z.nowMs)?.second?.contains("did not arrive") == true, "23:30 with nothing: one give-up note")
        z.setTime(day(2026, 11, 23), 22 * 60); T.check(NightDesk(FakePlatform()).step(z.nowMs) == null, "no notification on the evening before a holiday")
        // the app: tick at 22:00 posts through the brief channel, alarms include the evening wakes
        val ap = FakePlatform.standard(); ap.served[NightCalls.URL] = good
        val app = SwingApp(ap)
        ap.setTime(fri, 21 * 60); T.eq(app.nextWakeAt(), Clock.msAt(fri, 22 * 60), "app alarm at 22:00 on a notify evening")
        ap.setTime(fri, 22 * 60); app.tick("alarm")
        T.check(ap.notes.any { it.channel == "brief" && it.title.startsWith("Tomorrow's intraday calls (Mon 30 Nov)") }, "app posted the notification")
        val res = Json.parseObject(app.action("intraday", "{}"))
        T.check(res != null && (res["setups"] as? List<*>)?.size == 2, "intraday read action")
    }

    /** A synthetic market with enough liquid stocks and history for every code path of [NightCalls.build]. */
    fun buildSynthetic() {
        val rnd = java.util.Random(7)
        val stocks = ArrayList<MarketStock>()
        for (k in 0 until 60) {
            val s = Series("S$k", 300); var px = 200.0 + k; var d = day(2025, 9, 1)
            val drift = if (k % 3 == 0) -0.0008 else 0.0012
            for (b in 0 until 270) {
                while (!Holidays.isTradingDay(d)) d++
                val o = px * (1 + rnd.nextGaussian() * 0.006); val c = o * (1 + drift + rnd.nextGaussian() * 0.012)
                val h = maxOf(o, c) * (1 + Math.abs(rnd.nextGaussian()) * 0.006); val l = minOf(o, c) * (1 - Math.abs(rnd.nextGaussian()) * 0.006)
                s.add(d, o, h, l, c, 2e6); px = c; d++
            }
            stocks.add(MarketStock("S$k", Bhav.NSE, "", "", "EQ", "Stock $k", s, 0))
        }
        val d = NightCalls.build(stocks, setOf("S1", "S2"), emptyList(), Holidays.BUNDLED, 1L)
        T.eq(d.forDay, Holidays.nextTradingDay(d.asof, Holidays.BUNDLED), "for = next trading day after asof")
        T.check(d.rules.sumOf { it.trades } > 500, "back-test produced trades: " + d.rules.joinToString { it.id + "=" + it.trades })
        T.check(d.setups.all { it.convN > 0 && !it.conv.isNaN() }, "every listed setup carries its group's tested result")
        T.check(d.setups.count { it.kind == "intraday" } <= NightCalls.MAX_SETUPS && d.setups.filter { it.kind == "intraday" }.groupBy { it.rule }.all { it.value.size <= NightCalls.MAX_PER_RULE }, "caps")
        T.check(d.setups.filter { it.side == "BUY" || it.side == "SELL" }.all { it.qty > 0 && it.riskRs <= 1000.0 + 1e-6 }, "size within ₹1,000 risk")
        val exps = d.setups.filter { it.kind == "intraday" }.map { it.conv }
        T.check(exps.zipWithNext().all { it.first >= it.second }, "ranked by the group's tested result (conviction), not by size")
        T.check(d.setups.all { !it.hc || it.conv > 0.1 }, "high conviction only on clearly positive groups")
        val back = NightCalls.parse(NightCalls.encode(d))!!
        T.check(back.setups.size == d.setups.size && back.setups.zip(d.setups).all { (a, b) -> a.convN == b.convN && a.hc == b.hc && Math.abs(a.conv - b.conv) < 1e-3 }, "conviction survives the file round trip")
        T.check(back.conv.keys == d.conv.keys, "per-rule conviction models in the file")
        T.check(NightCalls.parse(NightCalls.encode(d)) != null, "built file parses")
    }

    /** Stats of every rule on the bundled real-price fixtures (40 NSE stocks x 300 sessions, re-dated onto one calendar). */
    fun fixture() {
        val lines = File("coretest/fixtures/fixture_prices.csv").readLines().drop(1).filter { it.isNotBlank() }
        val by = LinkedHashMap<String, ArrayList<List<String>>>()
        for (l in lines) { val r = l.split(','); if (r[0] != "NIFTY50") by.getOrPut(r[0]) { ArrayList() }.add(r) }
        val stocks = ArrayList<MarketStock>()
        for ((sym, rows) in by) {
            val s = Series(sym, rows.size + 1)
            var d = day(2026, 11, 26); val days = ArrayList<Int>()
            while (days.size < rows.size) { if (Holidays.isTradingDay(d)) days.add(0, d); d-- }
            for ((k, r) in rows.withIndex()) s.add(days[k], r[2].toDouble(), r[3].toDouble(), r[4].toDouble(), r[5].toDouble(), r[6].toDouble())
            stocks.add(MarketStock(sym, Bhav.NSE, "", "", "EQ", sym, s, 0))
        }
        val d = NightCalls.build(stocks, emptySet(), emptyList(), Holidays.BUNDLED, 1L)
        println("   fixture back-test (${stocks.size} stocks, ${d.sessions} sessions):")
        for (r in d.rules) println("     %-8s trades %4d  win %5.1f%%  avgR %+.3f  worst-case %+.3f  %s".format(r.id, r.trades, r.winPct, r.avgR, r.avgWorst, r.status))
        T.check(d.rules.filter { it.kind == "intraday" }.sumOf { it.trades } > 300, "fixture intraday trades")
        T.check(d.rules.all { it.trades == 0 || (it.avgR > -1.5 && it.avgR < 2.5) }, "R within the stop/target bounds")
        T.check(d.rules.all { it.avgWorst <= it.avgR + 1e-9 }, "worst case never above the path model")
    }

    /** 260 days of a slow uptrend, a 15-day tight box, then a high-volume close above the box and the 55-day high. */
    fun swingRules() {
        val s = Series("TST", 300); var d = day(2026, 1, 1); var p = 100.0
        for (k in 0 until 240) { while (!Clock.isWeekday(d)) d++; p *= 1.002; s.add(d, p, p * 1.01, p * 0.99, p, 1e5); d++ }
        val top = p * 1.012
        for (k in 0 until 15) { while (!Clock.isWeekday(d)) d++; s.add(d, p, top, p * 0.99, p * (1 + (k % 3 - 1) * 0.002), 1e5); d++ }
        while (!Clock.isWeekday(d)) d++
        val c = top * 1.03; s.add(d, top, c * 1.002, top * 0.998, c, 4e5)
        val f = NightCalls.Feat(s); val i = s.n - 1
        val ids = NightCalls.swingSignals(f, i, 85.0).map { it.rule }
        T.check("brk" in ids, "55-day breakout on volume fires: $ids")
        T.check("darvas" in ids, "tight-box (Darvas) breakout fires: $ids")
        T.check("hi52" !in ids || f.chi251[i] < c, "52-week high only when the close beats the prior 251 closes")
        T.check("vols" in ids, "4x volume, +3% close in the top of the range = volume surge: $ids")
        val dv = NightCalls.swingSignals(f, i, 85.0).first { it.rule == "darvas" }
        T.check(dv.stop > 0 && dv.target == 2 * dv.stop && dv.stop <= 3 * f.atr[i] + 1e-9, "Darvas stop at the box bottom capped at 3 ATR, target 2R")
        T.check(NightCalls.swingSignals(f, i - 3, 85.0).none { it.rule == "brk" || it.rule == "darvas" }, "no breakout inside the box")
        T.check(NightCalls.priorMax(doubleArrayOf(1.0, 5.0, 2.0, 3.0, 1.0), 5, 2).drop(2).toList() == listOf(5.0, 5.0, 3.0), "priorMax excludes the current bar")
    }

    /** a planted trait: trades with high x[2] win, low lose -> model picks feature 2, validates, top bucket high conviction */
    fun convFit() {
        val rnd = java.util.Random(5)
        val recs = (0 until 1200).map { k -> val x = DoubleArray(6) { rnd.nextDouble() }; TRec(k / 4, x, (x[2] - 0.5) * 2 + rnd.nextGaussian() * 0.5) }
        val m = NightCalls.fitConv(recs)
        T.check(m.feat == 2 && m.dir == 1 && m.validated, "finds the trait that separates winners and confirms it on newer trades (feat ${m.feat})")
        T.check(m.mean[2] > 0.4 && m.mean[0] < -0.4 && m.hc(2) && !m.hc(0), "top third high conviction, bottom third not")
        T.check(m.bucket(0.95) == 2 && m.bucket(0.05) == 0 && m.bucket(Double.NaN) == 1, "buckets")
        val noise = (0 until 1200).map { k -> TRec(k / 4, DoubleArray(6) { rnd.nextDouble() }, rnd.nextGaussian()) }
        val mn = NightCalls.fitConv(noise)
        T.check(!mn.hc(0) && !mn.hc(1) && !mn.hc(2), "pure noise: nothing is high conviction")
        T.check(NightCalls.fitConv(recs.take(100)).feat == -1, "too few trades: one group, the rule's overall record")
    }
}
