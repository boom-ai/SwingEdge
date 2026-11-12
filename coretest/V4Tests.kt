package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** SPEC4: live track record of the score's calls (cohorts, plans P1/P2, replay, upgrade rule, persistence). */
object V4Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v4-plans" to ::plans, "v4-cohorts" to ::cohorts, "v4-upgrade-rule" to ::upgradeRule, "v4-replay-exact-timing" to ::replayTiming,
        "v4-app" to ::app, "v4-files" to ::files, "v4-concurrent" to ::concurrent
    )

    private fun day(y: Int, m: Int, d: Int) = java.time.LocalDate.of(y, m, d).toEpochDay().toInt()

    private fun act(app: SwingApp, name: String, args: String): Map<String, Any?> {
        val txt = app.action(name, args)
        val r = Json.parseObject(txt)
        T.check(r != null && r["ok"] is Boolean, "action $name returns {ok:..}: " + txt.take(100))
        T.check(!txt.contains("NaN") && !txt.contains("Infinity"), "no NaN/Infinity in $name")
        return r ?: emptyMap()
    }

    // ------------------------------------------------------------------ constructed bars
    private fun weekdays(n: Int): IntArray {
        val out = IntArray(n)
        var d = day(2025, 1, 6)
        var k = 0
        while (k < n) { if (Clock.isWeekday(d)) out[k++] = d; d++ }
        return out
    }

    private val DAYS = weekdays(140)
    private const val DI = 60     // bar index of signal day D

    /** Flat bars (o=c=100, h=101, l=99) with [mod] applied by session offset m = index - DI; null from mod = no bar. */
    private fun mk(sym: String, upTo: Int = 139, mod: (Int, DoubleArray) -> DoubleArray? = { _, b -> b }): Series {
        val s = Series(sym, 150)
        for (i in 0..upTo) {
            val b = mod(i - DI, doubleArrayOf(100.0, 101.0, 99.0, 100.0)) ?: continue
            s.add(DAYS[i], b[0], b[1], b[2], b[3], 1e6)
        }
        return s
    }

    private fun cal(upTo: Int = 139) = Cal(DAYS.copyOfRange(0, upTo + 1))

    private class Out(val p1: PlanOut, val p2: PlanOut)

    private fun run(s: Series, group: Int = TrackEngine.G_BUY, c: Cal = cal()): Out {
        val pk = Pick(s.sym, DAYS[DI], group, 2.0, 100.0)
        if (group >= TrackEngine.G_AVOID) pk.p1.status = TrackEngine.ST_NA
        TrackEngine.evaluate(pk, s, TrackEngine.smaArray(s), c)
        return Out(pk.p1, pk.p2)
    }

    private fun out(o: PlanOut, status: Int, fill: Double, exit: Double, ses: Int, reason: Int, msg: String) {
        T.eq(TrackEngine.STATUS[o.status], TrackEngine.STATUS[status], "$msg status")
        T.near(o.fill, fill, 1e-9, "$msg fill")
        T.near(o.exit, exit, 1e-9, "$msg exit")
        T.eq(o.sessions, ses, "$msg sessions")
        T.eq(o.reason, reason, "$msg reason")
    }

    fun plans() {
        val C = TrackEngine.ST_CLOSED
        val R = TrackEngine
        // SMA50 of the flat bars is exactly 100; ATR of the pick is 2 -> zone top 101, P1 stop E-6, P2 stop E-10
        // 1. flat: no stop, never below SMA50 -> time exit at session 60 close
        var o = run(mk("FLAT"))
        out(o.p1, C, 100.0, 100.0, 60, R.R_TIME, "flat P1")
        out(o.p2, C, 100.0, 100.0, 60, R.R_TIME, "flat P2")
        // 2. gap through the P1 stop on session 3 (open 92 <= 94): exit at the open; P2 (stop 90) holds to the time exit
        o = run(mk("GAP") { m, b -> if (m == 3) doubleArrayOf(92.0, 92.5, 91.5, 92.0) else b })
        out(o.p1, C, 100.0, 92.0, 3, R.R_GAP, "gap P1")
        out(o.p2, C, 100.0, 100.0, 60, R.R_TIME, "gap P2 holds")
        // 3. gap through the P2 stop (open 88 <= 90) on session 4
        o = run(mk("GAP2") { m, b -> if (m == 4) doubleArrayOf(88.0, 89.0, 87.0, 88.5) else b })
        out(o.p1, C, 100.0, 88.0, 4, R.R_GAP, "deep gap P1")
        out(o.p2, C, 100.0, 88.0, 4, R.R_GAP, "deep gap P2")
        // 4. stop on the entry day: low 93 <= 94 -> exit at the stop (not the low); P2 untouched
        o = run(mk("STOP1") { m, b -> if (m == 1) doubleArrayOf(100.0, 101.0, 93.0, 95.0) else b })
        out(o.p1, C, 100.0, 94.0, 1, R.R_STOP, "entry-day stop P1")
        out(o.p2, C, 100.0, 100.0, 60, R.R_TIME, "entry-day stop P2 holds")
        // 5. stop intraday without a gap (open above the stop, low through it) on session 7
        o = run(mk("STOP7") { m, b -> if (m == 7) doubleArrayOf(97.0, 97.5, 89.0, 96.0) else b })
        out(o.p1, C, 100.0, 94.0, 7, R.R_STOP, "intraday stop P1")
        out(o.p2, C, 100.0, 90.0, 7, R.R_STOP, "intraday stop P2 (E-5 ATR)")
        // 6. close below SMA50 on session 5 -> exit at the next open (session 6 opens 101)
        o = run(mk("SMA") { m, b -> when (m) { 5 -> doubleArrayOf(100.0, 100.5, 99.0, 99.5); 6 -> doubleArrayOf(101.0, 101.5, 100.0, 100.0); else -> b } })
        out(o.p1, C, 100.0, 101.0, 6, R.R_SMA, "SMA50 exit next open P1")
        out(o.p2, C, 100.0, 100.0, 60, R.R_TIME, "P2 ignores SMA50")
        // 7. rising closes: time exit at the close of session 60
        o = run(mk("RISE") { m, b -> if (m >= 1) { val c = 100 + 0.05 * m; doubleArrayOf(c - 0.02, c + 1, c - 1, c) } else b })
        out(o.p1, C, 100.03, 103.0, 60, R.R_TIME, "time exit P1")
        out(o.p2, C, 100.03, 103.0, 60, R.R_TIME, "time exit P2")
        // 8. open above the zone (101.5 > 100 + 0.5*2): P1 skipped, P2 fills at the open
        o = run(mk("ZONE") { m, b -> if (m == 1) doubleArrayOf(101.5, 102.0, 100.5, 101.0) else b })
        out(o.p1, R.ST_SKIPPED, 0.0, 0.0, 0, R.R_NONE, "open above the zone P1")
        out(o.p2, C, 101.5, 100.0, 60, R.R_TIME, "open above the zone P2")
        // open exactly at the zone top fills
        o = run(mk("ZONE2") { m, b -> if (m == 1) doubleArrayOf(101.0, 101.0, 100.0, 100.5) else b })
        T.eq(o.p1.status, C, "open at the zone top fills"); T.near(o.p1.fill, 101.0, 0.0, "fill at 101")
        // 9. no bar on D+1 (the session exists): no trade in either plan
        val noD1 = mk("NOD1") { m, b -> if (m == 1) null else b }
        o = run(noD1)
        out(o.p1, R.ST_SKIPPED, 0.0, 0.0, 0, R.R_NONE, "no bar on D+1 P1")
        T.eq(o.p2.status, R.ST_SKIPPED, "no bar on D+1 P2")
        // 10. AVOID group: pure hold, the -50% low on session 10 does not stop it
        o = run(mk("HOLD") { m, b -> if (m == 10) doubleArrayOf(60.0, 61.0, 50.0, 60.0) else b }, TrackEngine.G_AVOID)
        T.eq(o.p1.status, R.ST_NA, "AVOID has no P1")
        out(o.p2, C, 100.0, 100.0, 60, R.R_TIME, "AVOID hold ignores stops")
        // 11. stops appearing after session 20: > 10 sessions later -> closed at its last close; <= 10 -> still open
        o = run(mk("GONE", upTo = DI + 20) { m, b -> if (m == 20) doubleArrayOf(100.0, 101.0, 99.0, 99.0 + 1.5) else b })
        out(o.p2, C, 100.0, 100.5, 20, R.R_DROP, "dropped stock P2 closed at its last close")
        o = run(mk("GONE2", upTo = DI + 20), c = cal(DI + 30))
        T.eq(o.p2.status, R.ST_OPEN, "10 sessions without bars: still open"); T.eq(o.p2.sessions, 20, "open: 20 sessions so far")
        o = run(mk("GONE3", upTo = DI + 20), c = cal(DI + 31))
        T.eq(o.p2.status, C, "11 sessions without bars: closed"); T.eq(o.p2.reason, R.R_DROP, "reason DROP")
        // 12. pending: no session after D yet; then the bars arrive one by one (pure function of bars)
        o = run(mk("PEND", upTo = DI), c = cal(DI))
        out(o.p1, R.ST_OPEN, 0.0, 0.0, 0, R.R_NONE, "pending fill")
        o = run(mk("PEND2", upTo = DI + 10), c = cal(DI + 10))
        out(o.p1, R.ST_OPEN, 100.0, 0.0, 10, R.R_NONE, "open after 10 sessions")
        // the session exists but the stock's bar is not in yet (feed lag): still pending, not skipped
        o = run(mk("LAG", upTo = DI), c = cal(DI + 3))
        T.eq(o.p1.status, R.ST_OPEN, "bar not in yet: pending")
        // 13. no bar on session 60 but later bars exist: time exit at the last close before it
        o = run(mk("NO60") { m, b -> when (m) { 60 -> null; 59 -> doubleArrayOf(100.0, 101.0, 99.5, 100.7); else -> b } })
        out(o.p2, C, 100.0, 100.7, 60, R.R_TIME, "no bar on session 60")
        // 14. a re-evaluation of a closed pick changes nothing; incremental = all at once
        val inc = Pick("INC", DAYS[DI], TrackEngine.G_BUY, 2.0, 100.0)
        val sInc = mk("INC") { m, b -> if (m == 33) doubleArrayOf(97.0, 97.5, 92.0, 96.0) else b }
        for (u in DI..139) { val h = sInc.head(u + 1); TrackEngine.evaluate(inc, h, TrackEngine.smaArray(h), cal(u)) }
        val all = run(sInc)
        T.check(inc.p1.same(all.p1) && inc.p2.same(all.p2), "bar-by-bar evaluation = one-shot evaluation")
        T.eq(TrackEngine.evaluate(inc, sInc, TrackEngine.smaArray(sInc), cal()), false, "closed pick: no change")
        // 15. returns net of costs
        val pk = Pick("X", DAYS[DI], TrackEngine.G_BUY, 2.0, 100.0)
        pk.p1.set(PlanOut(C, 100.0, 92.0, 3, R.R_GAP)); pk.p2.set(PlanOut(C, 100.0, 110.0, 60, R.R_TIME))
        val g1 = TrackEngine.g(listOf(pk), TrackEngine.G_BUY, 1, 0.25)
        T.near(g1["avgPct"] as Double, -8.25, 1e-9, "P1 net of 0.25% costs")
        T.near(TrackEngine.g(listOf(pk), TrackEngine.G_BUY, 2, 0.4)["avgPct"] as Double, 9.6, 1e-9, "P2 net of 0.4% costs")
        T.eq(TrackEngine.recentRow(pk, 0.25)["retPct"], 9.75, "recent row retPct (P2, the app plan)")
    }

    // ------------------------------------------------------------------ cohorts
    private fun synthTable(seed: Long, n: Int = 300, day: Int = 20400): ScoreTable {
        val r = java.util.Random(seed)
        val items = ArrayList<Pair<String, ScoreComp>>()
        for (k in 0 until n) {
            val q = r.nextDouble()
            fun c() = q + r.nextGaussian() * 0.08
            val sma = if (r.nextInt(3) == 0) 110.0 else 90.0
            val r6 = c(); val r12 = c(); val ram = c(); val prox = 0.5 + 0.5 * c(); val low = 1 + c()
            items.add(Pair("S%03d".format(k), ScoreComp(300, day, 100.0, 99.0, 101.0, 100.0, 100.0, sma, 2.0 + k * 0.01, r6, r12, 0.02, ram,
                100.0 / prox, 100.0 / low, prox, low, false)))
        }
        return SetupScore.buildTable(items, 0L)
    }

    fun cohorts() {
        for (seed in 1L..6L) {
            val t = synthTable(seed)
            T.check(t.usable, "synthetic table usable")
            val d = 20400 + seed.toInt()
            val c = TrackEngine.cohorts(t, d)
            fun grp(g: Int) = c.filter { it.group == g }
            val buys = t.ranked.filter { it.label == "BUY" }
            T.eq(grp(0).map { it.sym }, buys.map { it.sym }, "BUY = every BUY entry (seed $seed, ${buys.size})")
            val ctrl = grp(1)
            if (buys.isNotEmpty()) T.eq(ctrl.size, maxOf(buys.size, 4), "CONTROL size = max(#BUY, 4)") else T.eq(ctrl.size, 0, "no BUY -> no CONTROL")
            T.check(ctrl.all { e -> val x = t.entries[e.sym]!!; x.label != "BUY" && x.comp.close > x.comp.sma50 }, "CONTROL: rated, above SMA50, not BUY")
            T.eq(ctrl.map { it.sym }.toSet().size, ctrl.size, "CONTROL distinct")
            val avoid = grp(2)
            val nAvoid = t.ranked.count { it.label == "AVOID" }
            T.eq(avoid.size, minOf(30, nAvoid), "AVOID up to 30")
            val worst = t.ranked.filter { it.label == "AVOID" }.sortedWith(compareBy<ScoreEntry> { it.score }.thenBy { it.sym }).take(30).map { it.sym }
            T.eq(avoid.map { it.sym }, worst, "AVOID = lowest scores first")
            val actl = grp(3)
            T.eq(actl.size, avoid.size, "AVOID_CTRL same size")
            T.check(actl.all { t.entries[it.sym]!!.label != "AVOID" && t.entries[it.sym]!!.rated }, "AVOID_CTRL: rated non-AVOID")
            T.check(c.all { it.atr == t.entries[it.sym]!!.comp.atr14 && it.close == 100.0 && it.day == d }, "pick carries ATR and close of D")
            T.check(avoid.all { it.p1.status == TrackEngine.ST_NA } && grp(0).all { it.p1.status == TrackEngine.ST_OPEN }, "plans per group")
            // deterministic: same day -> same draw (also from a rebuilt table); other day -> another draw
            val again = TrackEngine.cohorts(synthTable(seed), d)
            T.eq(again.map { it.sym + it.group }, c.map { it.sym + it.group }, "deterministic cohorts")
            val other = TrackEngine.cohorts(t, d + 1)
            T.check(other.filter { it.group == 1 }.map { it.sym } != ctrl.map { it.sym } || ctrl.isEmpty(), "another day draws other controls")
            T.check(TrackEngine.seed(d, 1) != TrackEngine.seed(d, 3), "seed depends on the group")
        }
        val small = synthTable(9, 60)
        T.check(!small.usable && TrackEngine.cohorts(small, 1).isEmpty(), "unusable table -> no cohort")
    }

    // ------------------------------------------------------------------ upgrade rule
    /** [days] signal days x [per] BUY and CONTROL closed P1 trades; BUY gross mean [buyMu]%, CONTROL [ctrlMu]%, noise sd [sd]%. */
    fun synthPicks(days: Int, per: Int, buyMu: Double, ctrlMu: Double, sd: Double, seed: Long, day0: Int = 20000): ArrayList<Pick> {
        val r = java.util.Random(seed)
        val out = ArrayList<Pick>()
        for (d in 0 until days) for (g in 0..1) for (k in 0 until per) {
            val pk = Pick("SY$k", day0 + d, g, 2.0, 100.0)
            val ret = (if (g == 0) buyMu else ctrlMu) + r.nextGaussian() * sd
            pk.p1.set(PlanOut(TrackEngine.ST_CLOSED, 100.0, 100.0 * (1 + ret / 100.0), 20, TrackEngine.R_TIME))
            pk.p2.set(PlanOut(TrackEngine.ST_CLOSED, 100.0, 100.0 * (1 + ret / 100.0), 60, TrackEngine.R_TIME))
            out.add(pk)
        }
        return out
    }

    fun upgradeRule() {
        val good = synthPicks(30, 6, 3.0, 0.5, 6.0, 1)
        val p = TrackEngine.proof(good, 0.25, 20000)
        T.check(p.upgraded && p.t >= 2 && p.days == 30 && p.buyN == 180 && p.ctrlN == 180, "real edge upgrades: t=${p.t} k=${p.days}")
        // t from per-day mean differences, by hand
        val diffs = (0 until 30).map { d -> val b = good.filter { it.day == 20000 + d && it.group == 0 }; val c = good.filter { it.day == 20000 + d && it.group == 1 }
            b.sumOf { it.p1.exit / 100 - 1 } / b.size * 100 - c.sumOf { it.p1.exit / 100 - 1 } / c.size * 100 }
        val mu = diffs.average(); val sdv = Math.sqrt(diffs.sumOf { (it - mu) * (it - mu) } / (diffs.size - 1))
        T.near(p.edge, mu, 1e-9, "edge = mean of per-day differences"); T.near(p.t, mu / (sdv / Math.sqrt(30.0)), 1e-9, "t = mean / (sd / sqrt(k))")
        T.check(ScoreTexts.trackNeedText(p).startsWith("Met:"), "needText when met")
        var noUp = 0
        for (s in 2L..21L) { val q = TrackEngine.proof(synthPicks(30, 6, 1.0, 1.0, 6.0, s), 0.25, 20000); if (!q.upgraded) noUp++ }
        T.check(noUp >= 17, "noise rarely upgrades ($noUp of 20 seeds stay unproven)")
        T.check(!TrackEngine.proof(synthPicks(30, 6, 1.0, 1.0, 6.0, 5), 0.25, 20000).upgraded, "noise seed 5 does not upgrade")
        T.check(!TrackEngine.proof(synthPicks(18, 10, 3.0, 0.5, 6.0, 1), 0.25, 20000).upgraded, "under 20 signal days: no upgrade")
        T.check(!TrackEngine.proof(synthPicks(30, 4, 3.0, 0.5, 2.0, 1), 0.25, 20000).upgraded, "under 150 trades: no upgrade")
        T.check(!TrackEngine.proof(synthPicks(30, 6, -0.5, -4.0, 3.0, 1), 0.25, 20000).upgraded, "BUY mean below 0: no upgrade even with an edge")
        val few = TrackEngine.proof(synthPicks(5, 2, 1.0, 1.0, 2.0, 1), 0.25, 20000)
        T.eq(ScoreTexts.trackNeedText(few), "Needs 150 closed trades in each group (now 10 and 10) and an edge with t ≥ 2.", "needText progress")
        T.eq(TrackEngine.proof(emptyList(), 0.25, 0).upgraded, false, "empty record")
        // open trades do not count
        val open = synthPicks(30, 6, 3.0, 0.5, 6.0, 1)
        for (pk in open) if (pk.group == 0) pk.p2.status = TrackEngine.ST_OPEN
        T.check(!TrackEngine.proof(open, 0.25, 20000).upgraded, "open trades do not count")
        T.check(ScoreTexts.liveEvidence(p).contains("Live record on this phone since"), "live evidence text")
    }

    // ------------------------------------------------------------------ replay exactness + timing (574 x 520)
    fun bigUniverse(n: Int): List<Series> {
        val y = FakeYahoo()
        val r = java.util.Random(42)
        val syms = (0 until n).map { "U%03d".format(it) }
        for (s in syms) y.profiles[s] = Profile(-0.15 + r.nextDouble() * 0.4, 0.8 + r.nextDouble() * 1.7, start = 100.0 + r.nextDouble() * 900)
        val days = y.tradingDaysUpTo(y.baseDay + 900, 520)
        return syms.mapIndexed { k, sym ->
            val s = Series(sym, 530)
            val skip = if (k % 50 == 7) days[300] else -1         // a few stocks miss a day
            val from = if (k % 97 == 3) 200 else 0                 // a few list later
            for ((i, d) in days.withIndex()) { if (i < from || d == skip) continue; val b = y.bar(sym, d)!!; s.add(d, Math.rint(b[0] * 100) / 100, Math.rint(b[1] * 100) / 100, Math.rint(b[2] * 100) / 100, Math.rint(b[3] * 100) / 100, b[4]) }
            s
        }
    }

    fun replayTiming() {
        val uni = bigUniverse(574)
        val cal = Cal.of(uni)
        T.eq(cal.size, 520, "calendar of 520 sessions")
        // the per-day table (matched by day) is bit-identical to the live table built on series that end that day
        for (j in listOf(260, 301, 400, 459)) {
            val d = cal.days[j]
            val a = TrackEngine.tableAt(uni, d)
            val liveItems = uni.mapNotNull { s -> val i = s.indexOf(d); if (i < 0) null else Pair(s.sym, SetupScore.compute(s.head(i + 1))) }
            val b = SetupScore.buildTable(liveItems, 0L)
            T.eq(a.ranked.size, b.ranked.size, "rated count day $j")
            var same = 0
            for (e in b.entries.values) {
                val x = a.entries[e.sym]
                if (e.rated && (x == null || x.raw != e.raw || x.score != e.score || x.label != e.label || x.rank != e.rank)) T.check(false, "day $j ${e.sym}: ${x?.score} vs ${e.score}") else same++
            }
            T.check(same == b.entries.size, "day $j: all entries identical ($same)")
            T.check(j < 259 || a.usable, "day $j usable")
        }
        val t0 = System.currentTimeMillis()
        val pcts = ArrayList<Int>()
        val out = TrackEngine.replay(uni) { pcts.add(it) }
        val ms = System.currentTimeMillis() - t0
        println("      replay 574 x 520: ${out.days} signal days, ${out.picks.size} picks, $ms ms")
        T.check(ms < 5000, "replay under 5 s on the JVM ($ms ms)")
        T.eq(out.days, 520 - 252 - 60, "every day with 253 bars and 60 sessions after it")
        T.check(pcts.isNotEmpty() && pcts.last() == 100 && pcts.zipWithNext().all { it.first <= it.second }, "progress 0..100 rising")
        T.check(out.picks.all { it.p2.status != TrackEngine.ST_OPEN && it.p1.status != TrackEngine.ST_OPEN }, "replay picks are all final")
        // a replay day = cohorts of the live table of that day, with the same outcomes as a live evaluation
        val d = out.toDay
        val live = TrackEngine.cohorts(TrackEngine.tableAt(uni, d), d)
        val rep = out.picks.filter { it.day == d }
        T.eq(rep.map { it.sym + "/" + it.group }, live.map { it.sym + "/" + it.group }, "replay cohort = live cohort for the same day")
        for (pk in live) { val s = uni.first { it.sym == pk.sym }; TrackEngine.evaluate(pk, s, TrackEngine.smaArray(s), cal) }
        T.check(rep.indices.all { rep[it].p1.same(live[it].p1) && rep[it].p2.same(live[it].p2) }, "replay outcomes = live outcomes")
        val buys = out.picks.count { it.group == 0 }
        val ctrl = out.picks.count { it.group == 1 }
        T.check(buys > 0 && ctrl >= buys, "replay has BUY ($buys) and CONTROL ($ctrl) picks")
        val st = TrackStore(); st.setReplay(out)
        val v = TrackEngine.view(st, 0.25, false)
        println("      replay P1 buy " + v.obj("replay").obj("P1").obj("buy") + " edge " + v.obj("replay").num("edgePct") + " t " + v.obj("replay").num("edgeT"))
        T.check(!TrackEngine.proof(st.live, 0.25, 0).upgraded && v.obj("live").bool("upgraded") == false, "replay never upgrades")
    }

    // ------------------------------------------------------------------ app
    private fun trackOf(app: SwingApp, where: String) = SimTests.state(app, where).obj("track")

    fun app() {
        val p = V3Tests.bigPlatform()
        val y = p.yahoo
        y.shortHistory.clear()    // the fake's short histories slide with "today"; a real listing date does not
        val d0 = day(2026, 10, 7)
        p.setTime(d0, 15 * 60 + 45)
        val app = SwingApp(p)
        var tr = trackOf(app, "fresh")
        T.check(tr.int("startedDay") == 0 && tr.int("lastDay") == 0 && !tr.bool("replayDone") && tr.list("recent").isEmpty() &&
            tr.obj("live").obj("P1").obj("buy").int("n") == 0 && tr.obj("replay").int("fromDay") == 0, "empty track before data")
        T.check(tr.obj("live").str("needText").startsWith("Needs 150"), "needText before data")
        T.eq(act(app, "scanNow", "{}")["ok"], true, "scan after the close")
        val t = app.debugScoreTable()!!
        val store = app.debugTrack()
        T.eq(store.lastDay, d0, "cohort for the completed bar of today")
        val c0 = store.live.filter { it.day == d0 }
        T.eq(c0.filter { it.group == 0 }.map { it.sym }, t.ranked.filter { it.label == "BUY" }.map { it.sym }, "BUY cohort = every BUY of the live table")
        println("      cohort ${Clock.dayLabel(d0)}: " + (0..3).map { g -> c0.count { it.group == g } })
        T.check(c0.count { it.group == 0 } >= 1 && c0.count { it.group == 1 } >= 4, "cohort has BUY and CONTROL")
        T.check(c0.all { it.p1.status == TrackEngine.ST_OPEN && it.p2.status == TrackEngine.ST_OPEN || it.group >= 2 }, "all open before D+1")
        tr = trackOf(app, "after first scan")
        T.check(tr.bool("replayDone") && tr.obj("replay").int("fromDay") > 0 && tr.obj("replay").obj("P1").obj("buy").int("closed") > 0, "replay ran on its own once the cache was deep")
        T.eq(tr.int("startedDay"), StockPage.yyyymmdd(d0), "startedDay")
        T.check(tr.list("recent").size in 1..40, "recent rows")
        val n0 = store.live.size
        T.eq(act(app, "scanNow", "{}")["ok"], true, "second scan same day")
        T.eq(store.live.size, n0, "two scans on the same bar day -> one cohort")
        p.setTime(d0 + 1, 10 * 60 + 5)
        app.tick("manual")
        T.check(store.live.size == n0 && store.lastDay == d0, "next morning (partial bar): still one cohort for D")
        // fills at the open of D+1 (pure function of bars, from the partial day's first bar)
        val d1 = y.nextTradingDay(d0)
        p.setTime(d1, 15 * 60 + 40)
        app.tick("manual")
        T.check(store.lastDay == d1 && store.live.count { it.day == d1 } > 0, "after the close of D+1: its cohort")
        for (pk in store.live.filter { it.day == d0 }) {
            val o = y.bar(pk.sym, d1)!![0]
            val o2 = Math.rint(o * 100) / 100
            if (pk.group <= 1) {
                val zone = pk.close + 0.5 * pk.atr
                T.check(if (o2 > zone) pk.p1.status == TrackEngine.ST_SKIPPED else pk.p1.fill == o2, "P1 fill at the open of D+1 or skipped: ${pk.sym}")
            }
            T.check(pk.p2.fill == o2 || pk.p2.status == TrackEngine.ST_CLOSED, "P2 fill at the open of D+1: ${pk.sym} ${pk.p2.fill} $o2")
        }
        // restart keeps the live record
        val before = store.encodeLive()
        val app2 = SwingApp(p)
        T.eq(app2.debugTrack().encodeLive(), before, "forward.json round trip")
        T.eq(Json.stringify(trackOf(app2, "restart")), Json.stringify(trackOf(app, "before restart")), "restart: same track state")
        T.check(app2.debugTrack().replayDone && app2.debugTrack().replay.size == store.replay.size, "replay reloaded, not recomputed")
        // ~75 sessions later: everything from D and D+1 is final, and the replay of those days gives the same outcomes
        val late = y.tradingDaysUpTo(d1 + 120, 400).first { it > d1 + 100 }
        p.setTime(late, 15 * 60 + 45)
        T.eq(act(app2, "scanNow", "{}")["ok"], true, "late scan")
        val s2 = app2.debugTrack()
        val early = s2.live.filter { it.day == d0 || it.day == d1 }
        T.check(early.isNotEmpty() && early.all { it.done() }, "D and D+1 picks are final after 60+ sessions")
        T.check(early.filter { it.group <= 1 }.all { it.p1.status != TrackEngine.ST_CLOSED || it.p1.sessions in 1..61 }, "P1 holds at most 60 sessions (+ next open)")
        T.check(early.all { it.p2.status != TrackEngine.ST_CLOSED || it.p2.sessions in 1..60 }, "P2 holds at most 60 sessions")
        val r = act(app2, "replayTrack", "{}")
        T.eq(r["ok"], true, "replayTrack")
        for (dd in listOf(d0, d1)) {
            val lv = s2.live.filter { it.day == dd }.sortedWith(compareBy<Pick> { it.group }.thenBy { it.sym })
            val rp = s2.replay.filter { it.day == dd }.sortedWith(compareBy<Pick> { it.group }.thenBy { it.sym })
            T.eq(rp.map { it.sym + "/" + it.group }, lv.map { it.sym + "/" + it.group }, "replay cohort = live cohort for ${Clock.dayLabel(dd)}")
            T.check(rp.size == lv.size && rp.indices.all { rp[it].p1.same(lv[it].p1) && rp[it].p2.same(lv[it].p2) }, "replay outcomes = live outcomes for ${Clock.dayLabel(dd)}")
        }
        tr = trackOf(app2, "late")
        val g = tr.obj("live").obj("P1").obj("buy")
        T.check(g.int("closed") > 0 && g.int("n") >= g.int("closed") + g.int("open"), "live P1 BUY stats: $g")
        T.check(tr.obj("live").bool("upgraded") == false, "not upgraded on a few days")
        val rec = tr.list("recent").map { it.asObj() }
        T.check(rec.zipWithNext().all { it.first.int("day") >= it.second.int("day") }, "recent newest first")
        T.check(rec.all { it.str("plan") == "P2" }, "recent picks shown under the app plan P2")

        // upgrade: a strong live record turns BUY into proven; a strong replay never does
        val buySym = app2.debugScoreTable()!!.ranked.firstOrNull { it.label == "BUY" }?.sym
        T.check(buySym != null, "a BUY in the late table")
        if (buySym != null) {
            val keep = ArrayList(s2.live)
            s2.live.clear()
            s2.replay = synthPicks(60, 8, 4.0, 0.0, 5.0, 3)
            app2.debugTrackChanged()
            var call = V2Tests.info(app2, buySym, "strong replay").obj("call")
            T.check(call.str("label") == "BUY" && !call.bool("proven") && call.str("evidence").contains("Tested on NSE"), "replay never upgrades")
            T.check(trackOf(app2, "strong replay").obj("replay").num("edgeT") >= 2.0, "replay edge t >= 2 shown")
            s2.live.addAll(synthPicks(30, 6, 3.0, 0.5, 6.0, 1, day0 = 19000))
            app2.debugTrackChanged()
            call = V2Tests.info(app2, buySym, "upgraded").obj("call")
            T.check(call.str("label") == "BUY" && call.bool("proven") && call.str("evidence").startsWith("Live record on this phone"), "upgraded BUY: proven, plain BUY, live evidence: " + call.str("evidence").take(80))
            T.check(trackOf(app2, "upgraded").obj("live").bool("upgraded"), "track.live.upgraded")
            val other = app2.debugScoreTable()!!.ranked.firstOrNull { it.label == "WAIT" }?.sym
            if (other != null) T.check(!V2Tests.info(app2, other, "wait").obj("call").bool("proven"), "WAIT stays unproven")
            s2.live.clear(); s2.live.addAll(synthPicks(30, 6, 1.0, 1.0, 6.0, 5, day0 = 19000)); app2.debugTrackChanged()
            T.check(!V2Tests.info(app2, buySym, "noise").obj("call").bool("proven"), "noise: not proven")
            s2.live.clear(); s2.live.addAll(keep); app2.debugTrackChanged()
        }
        // reset clears the live record only
        T.eq(act(app2, "resetTrack", "{}")["ok"], true, "resetTrack")
        tr = trackOf(app2, "reset")
        T.check(tr.int("startedDay") == 0 && tr.obj("live").obj("P1").obj("buy").int("n") == 0 && tr.list("recent").isEmpty() && tr.bool("replayDone"), "reset: live empty, replay kept")
        T.check(SwingApp(p).debugTrack().live.isEmpty(), "reset persisted")
    }

    fun files() {
        val p = V3Tests.bigPlatform()
        p.files["forward.json"] = "{not json"
        p.files["replay.json"] = "[1,2,"
        val app = SwingApp(p)
        T.check(app.debugTrack().live.isEmpty() && !app.debugTrack().replayDone, "corrupt files -> empty record")
        SimTests.state(app, "corrupt files")
        // a file with some bad rows keeps the good ones
        val st = TrackStore()
        st.addCohort(20400, synthPicks(1, 3, 1.0, 1.0, 1.0, 1, 20400))
        val good = st.encodeLive()
        val broken = good.replace("\"p\":[", "\"p\":[[\"BAD\",\"x\"],[\"\",1,0,1,1,1,1,0,0,0,0,0,0,0,0,0,0],42,null,[\"NEG\",20400,0,\"-1\",\"100\",20400,100,0,0,0,0,0,0,0,0,0,0],")
        val st2 = TrackStore(); st2.loadLive(broken)
        T.eq(st2.live.size, st.live.size, "bad rows skipped, good rows kept")
        T.eq(st2.encodeLive(), good, "good rows round-trip exactly")
        T.check(st2.startedDay == 20400 && st2.lastDay == 20400, "days kept")
        val st3 = TrackStore(); st3.loadLive("{\"v\":1,\"p\":\"zzz\"}"); T.check(st3.live.isEmpty(), "wrong types tolerated")
        val st4 = TrackStore(); st4.loadReplay("{\"v\":99,\"done\":true,\"from\":1,\"p\":[[0,0,1,5,3,1,5,3]]}"); T.check(!st4.replayDone && st4.replay.isEmpty(), "old replay version -> recompute")
        val st5 = TrackStore(); st5.loadReplay("{\"v\":${TrackEngine.REPLAY_VERSION},\"done\":true,\"from\":20000,\"to\":20001,\"p\":[[0,0,1,50000,3,1,-20000,9],[\"x\"],[1,7,1,1,1,1,1,1]]}")
        T.check(st5.replayDone && st5.replay.size == 1 && Math.abs(st5.replay[0].p1.gross(0.0) - 0.05) < 1e-12 && st5.replay[0].p2.sessions == 9, "replay rows decoded, bad rows skipped")
        // cap: 6,000 live picks, oldest finished first
        val big = TrackStore()
        for (d in 0 until 210) big.addCohort(20000 + d, synthPicks(1, 50, 1.0, 1.0, 1.0, d.toLong(), 20000 + d))
        T.eq(big.live.size, TrackEngine.MAX_LIVE, "live capped at 20,000")
        T.eq(big.live.minOf { it.day }, 20000 + 10, "oldest finished dropped first")
        val mix = TrackStore()
        for (d in 0 until 210) {
            val ps = synthPicks(1, 25, 1.0, 1.0, 1.0, d.toLong(), 20000 + d)
            for (k in 0 until 50) { val a = Pick("AV$k", 20000 + d, 2 + k % 2, 2.0, 100.0); a.p1.status = TrackEngine.ST_NA; a.p2.set(PlanOut(TrackEngine.ST_CLOSED, 100.0, 99.0, 60, 3)); ps.add(a) }
            mix.addCohort(20000 + d, ps)
        }
        T.eq(mix.live.size, TrackEngine.MAX_LIVE, "mixed: capped")
        T.check(mix.live.count { it.group <= 1 } == 210 * 50 && mix.live.filter { it.group >= 2 }.minOf { it.day } == 20000 + 20, "finished AVOID-group picks dropped before BUY/CONTROL")
        // writes failing do not break the app
        p.failWrites = true
        p.setTime(day(2026, 10, 7), 15 * 60 + 45)
        T.eq(act(app, "scanNow", "{}")["ok"], true, "scan with failing writes")
        p.failWrites = false
        SimTests.state(app, "after failing writes")
    }

    fun concurrent() {
        val p = V3Tests.bigPlatform()
        p.setTime(day(2026, 10, 7), 15 * 60 + 45)
        val app = SwingApp(p)
        T.eq(act(app, "scanNow", "{}")["ok"], true, "first scan")
        val errs = java.util.Collections.synchronizedList(ArrayList<String>())
        var sawBusy = 0
        var sawLabel = ""
        fun check(name: String, txt: String) {
            val o = Json.parseObject(txt)
            if (o == null || o["ok"] !is Boolean) errs.add("$name: bad " + txt.take(80))
            if (txt.contains("Something went wrong") || txt.contains("NaN") || txt.contains("Infinity")) errs.add("$name: " + txt.take(120))
        }
        val threads = listOf(
            Thread { for (k in 0 until 4) check("replayTrack", app.action("replayTrack", "{}")) },
            Thread { for (k in 0 until 40) check("stockInfo", app.action("stockInfo", """{"sym":"UP%02d"}""".format(1 + k % 90))) },
            Thread { for (k in 0 until 40) check("search", app.action("search", """{"q":"up"}""")) },
            Thread {
                for (k in 0 until 400) {
                    val o = Json.parseObject(app.stateJson())
                    if (o == null || o["track"] !is Map<*, *>) errs.add("stateJson: bad")
                    else if (o.obj("track").bool("busyReplay")) { sawBusy++; sawLabel = o.obj("busy").str("label") }
                    Thread.sleep(1)
                }
            }
        )
        threads.forEach { it.start() }
        threads.forEach { it.join(120_000) }
        T.check(threads.none { it.isAlive }, "threads finished")
        T.check(errs.isEmpty(), "no errors while replaying: " + errs.take(3))
        println("      stateJson saw busyReplay $sawBusy times, label '$sawLabel'")
        if (sawBusy > 0) T.eq(sawLabel, "Replaying the last year…", "busy label during replay")
        val tr = SimTests.state(app, "after concurrent replay").obj("track")
        T.check(tr.bool("replayDone") && !tr.bool("busyReplay"), "replay done, not busy")
    }
}
