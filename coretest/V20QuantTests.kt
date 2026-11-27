package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** Quant lab: decayed Bayesian selection, Thompson sampling, exit grid, calibrated hierarchical filter, walk-forward gate, new rules, risk layer. */
object V20Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("v20-quant-bayes" to ::bayes, "v20-quant-exits" to ::exits, "v20-quant-filter" to ::filter,
        "v20-quant-rules" to ::rules, "v20-quant-risk" to ::risk, "v20-quant-report" to ::report)
    val day = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()

    fun trade(id: String, strat: String, sym: String, bids: List<Double>, entry: Double = 100.0, champ: Boolean = true, d: Int = day) =
        LabTrade(id, strat, sym, "x", 1, "CE", 65, entry, 0, d, bids = ArrayList(bids), champ = champ)
    fun closeAll(b: LabBook, ts: List<LabTrade>) { for (t in ts) { b.open.add(t); FnoLab.close(b, t) } }
    fun bars(closes: List<Double>, prev: Double, start: Int = 9 * 60 + 15, hw: Double = 2.0): IntraBars {
        val n = closes.size
        return IntraBars(prev, IntArray(n) { start + it * 5 }, DoubleArray(n) { if (it == 0) closes[0] else closes[it - 1] },
            DoubleArray(n) { Math.max(closes[it], if (it == 0) closes[0] else closes[it - 1]) + hw }, DoubleArray(n) { Math.min(closes[it], if (it == 0) closes[0] else closes[it - 1]) - hw }, closes.toDoubleArray())
    }

    fun bayes() {
        // decay: one observation fades to half its weight after HALF_LIFE further trades
        val d = DStat(); d.add(1.0, 100.0); repeat(60) { d.add(0.0, 100.0) }
        T.near(d.sx, 0.5, 1e-9, "half-life 60 trades")
        val dd = DStat(); repeat(2000) { dd.add(0.1, 100.0) }
        T.near(dd.w, 1 / (1 - FnoLearn.LAMBDA), 1e-6, "effective sample size saturates at 1/(1-λ) ≈ 87")
        T.near(dd.mean, 0.1, 1e-9, "decayed mean of constant data")
        // NIG posterior: prior centred at 0; strong data proves itself; mixed data stays unsure
        val p0 = FnoLearn.post(null); T.near(p0.mu, 0.0, 1e-12, "prior mean"); T.check(p0.lo < 0 && p0.hi > 0, "prior interval spans zero")
        val good = DStat(); repeat(40) { good.add(if (it % 4 == 0) -0.3 else 0.5, 6500.0) }
        val pg = FnoLearn.post(good); T.check(pg.lo > 0 && pg.provenUp, "consistent winner: 90% interval above zero ${pg.lo}")
        T.check(pg.mu < good.mean, "posterior mean shrunk toward 0: ${pg.mu} < ${good.mean}")
        val mix = DStat(); repeat(12) { mix.add(if (it % 2 == 0) 0.4 else -0.35, 6500.0) }
        val pm = FnoLearn.post(mix); T.check(pm.lo < 0 && pm.hi > 0, "coin-flip rule: interval crosses zero")
        T.eq(FnoLearn.postView(mix)["evidence"], "UNSURE", "reported as not enough evidence")
        T.check((FnoLearn.postView(mix)["evidenceText"] as String).startsWith("not enough evidence yet"), "plain-English evidence text")
        T.check(Math.abs(FnoLearn.tq95(1e6) - 1.6449) < 1e-3 && Math.abs(FnoLearn.tq95(10.0) - 1.812) < 0.01, "t quantile: normal limit and nu=10")
        // gamma sampler mean (seeded)
        val r = java.util.Random(7); var s = 0.0; repeat(20000) { s += FnoLearn.gamma(r, 3.0) }
        T.near(s / 20000, 3.0, 0.06, "gamma(3) mean")
        // Thompson: deterministic per seed; strong winner chosen; close contenders both explored; empty -> middle
        val b = LabBook()
        closeAll(b, (0 until 12).map { trade("a$it", "ORB#0", "NIFTY", listOf(if (it % 6 == 0) 80.0 else 140.0)) })
        closeAll(b, (0 until 12).map { trade("b$it", "ORB#2", "NIFTY", listOf(if (it % 2 == 0) 80.0 else 115.0)) })
        T.eq(FnoLab.champion(b, "ORB", "NIFTY", 5), FnoLab.champion(b, "ORB", "NIFTY", 5), "same seed, same champion")
        val picks = (0L until 100L).map { FnoLab.champion(b, "ORB", "NIFTY", it) }
        T.check(picks.count { it == "ORB#0" } >= 90, "clear winner wins most draws: ${picks.groupingBy { it }.eachCount()}")
        val c = LabBook()
        closeAll(c, (0 until 8).map { trade("c$it", "TREND#0", "NIFTY", listOf(if (it % 2 == 0) 80.0 else 125.0)) })
        closeAll(c, (0 until 8).map { trade("e$it", "TREND#2", "NIFTY", listOf(if (it % 2 == 0) 80.0 else 124.0)) })
        val cp = (0L until 200L).map { FnoLab.champion(c, "TREND", "NIFTY", it) }.toSet()
        T.check(cp.contains("TREND#0") && cp.contains("TREND#2"), "close contenders are both explored: $cp")
        T.eq(FnoLab.champion(LabBook(), "TREND", "NIFTY", 3), "TREND#1", "no data -> middle threshold")
        // decay: a variant that was good long ago but bad lately loses to a steady one
        val e = LabBook()
        closeAll(e, (0 until 60).map { trade("o$it", "LATE#0", "NIFTY", listOf(150.0)) } + (0 until 150).map { trade("n$it", "LATE#0", "NIFTY", listOf(if (it % 3 == 0) 120.0 else 75.0)) })
        closeAll(e, (0 until 40).map { trade("s$it", "LATE#2", "NIFTY", listOf(if (it % 3 == 0) 80.0 else 120.0)) })
        val all0 = e.stats["LATE#0|NIFTY"]!!["live"]!!
        T.check(all0.mean > 0, "undecayed average still looks positive: ${all0.mean}")
        T.check(FnoLearn.post(e.post["LATE#0|NIFTY"]!!["live"]).mu < 0, "decayed posterior sees the recent losses")
        T.eq(FnoLab.champion(e, "LATE", "NIFTY", 11), "LATE#2", "steady variant becomes champion")
        T.check(FnoLab.status(e, "LATE#0|NIFTY") != "WORKING", "stale edge is not 'Working'")
        // persistence
        val rt = LabBook.from(Json.parseObject(Json.stringify(e.toMap())))
        T.near(rt.post["LATE#0|NIFTY"]!!["live"]!!.sx, e.post["LATE#0|NIFTY"]!!["live"]!!.sx, 1e-4, "posteriors round-trip")
        T.eq(rt.post["LATE#0|NIFTY"]!!["live"]!!.n, 210, "trade count round-trips")
    }

    fun exits() {
        // trailing stop after +30%
        val tr = ExitRule(30.0, 0.0, 30.0, 20.0)
        T.eq(FnoLearn.replay(100.0, listOf(120.0, 140.0, 110.0, 60.0), emptyList(), tr), Pair(110.0, "TRAIL"), "trail 20% from the 140 peak exits at 110")
        T.eq(FnoLearn.replay(100.0, listOf(120.0, 125.0, 90.0), emptyList(), tr).second, "TIME", "trail not armed below +30%")
        // time stop uses the recorded minutes
        val ts = ExitRule(30.0, 50.0, tmin = 12 * 60 + 30)
        T.eq(FnoLearn.replay(100.0, listOf(110.0, 120.0, 130.0, 90.0, 60.0), listOf(600, 700, 750, 800, 840), ts), Pair(130.0, "TIME"), "exit at 12:30")
        T.eq(FnoLearn.replay(100.0, listOf(110.0, 60.0), emptyList(), ts).second, "STOP", "no minutes recorded: time stop inactive, stop still works")
        T.eq(ExitRule.parse(tr.key)!!.key, tr.key, "trail key parses"); T.eq(ExitRule.parse(ts.key)!!.tmin, 12 * 60 + 30, "time key parses")
        T.eq(FnoLearn.GRID.size, FnoLab.STOPS.size * FnoLab.TGTS.size + 4, "grid = 12 fixed + 2 trailing + 2 time")
        // learned: runs that overshoot and then collapse favour the trailing stop
        val b = LabBook()
        closeAll(b, (0 until 12).map { trade("t$it", "ORB#1", "NIFTY", listOf(140.0, 190.0, 250.0, 195.0, 60.0)) })
        val r = FnoLab.bestRule(b, "ORB#1|NIFTY")
        T.check(r.trail == 20.0 && r.arm == 30.0, "trail 20% after +30% learned: ${r.key}")
        T.eq(FnoLab.bestExit(b, "ORB#1|NIFTY"), Pair(30.0, 0.0), "legacy pair view of that rule")
        // stocks pool exit evidence across underlyings
        closeAll(b, (0 until 9).map { trade("s$it", "ORB#1", "STK$it", listOf(140.0, 190.0, 250.0, 195.0, 60.0)) })
        T.check(b.post.containsKey("ORB#1|STOCKS") && !b.post["ORB#1|STK0"].orEmpty().containsKey("30/50"), "stock exits pooled per variant")
        T.eq(FnoLab.bestRule(b, "ORB#1|NEWSTOCK").trail, 20.0, "a new stock uses the pooled exit")
        // a closed trade settles on its own trailing rule; live check sees the trail
        val t = trade("q", "ORB#1", "NIFTY", listOf(100.0)); t.setRule(tr)
        for ((i, x) in listOf(120.0, 140.0).withIndex()) { t.mark(x, 600 + i); T.eq(FnoLab.liveCheck(t, x, 600 + i), null, "no hit at $x") }
        t.mark(110.0, 603); T.eq(FnoLab.liveCheck(t, 110.0, 603), "TRAIL", "live trailing stop")
        T.eq(LabEntry.hitText("TRAIL"), "trailing stop hit, exit now", "alert text")
        val lb = LabBook(); lb.open.add(t); FnoLab.close(lb, t)
        T.check(t.status == "STOP" && t.exitPx == 110.0, "settled on its own trail: ${t.status} ${t.exitPx}")
        val t2 = trade("w", "ORB#1", "NIFTY", listOf(100.0)); t2.setRule(ts); t2.mark(105.0, 12 * 60 + 31)
        T.eq(FnoLab.liveCheck(t2, 105.0, 12 * 60 + 31), "TIME", "live time stop")
        val back = LabTrade.from(Json.parseObject(Json.stringify(t2.toMap()))!!)
        T.check(back.tmin == 12 * 60 + 30 && back.mins.size == back.bids.size, "trade rule + minutes round-trip")
    }

    fun filter() {
        // features
        val bb = bars((0 until 20).map { 25400.0 + it * 5 }, 25350.0)
        val ctx = FeatCtx(14.0, 1, 6, 0.8, 1.3, 1.1, 0.6, 2.5)
        val fc = FnoLearn.featuresX(bb, 11 * 60, "CE", ctx); val fp = FnoLearn.featuresX(bb, 11 * 60, "PE", ctx)
        T.eq(fc.size, FnoLearn.NX, "15 features")
        T.check(fc[11] > 0 && fp[11] < 0 && fc[12] > 0 && fc[13] > 0 && fp[13] < 0, "PCR, PCR change and index agreement are signed by side")
        T.check(fc[8] < 0 && fc[9] > 0 && fc[10] == 0.8 && fc[14] > 0, "day, expiry distance, IV rank, ATR%")
        T.eq(FnoLearn.featuresX(bb, 11 * 60, "CE", FeatCtx())[13], 0.0, "no index trend for index options")
        // IV rank from history
        val b0 = LabBook(); for (k in 0 until 15) FnoLearn.ivRank(b0, "VIX", day - 20 + k, 12.0 + k * 0.1)
        T.check(FnoLearn.ivRank(b0, "VIX", day, 20.0) > 0.9 && FnoLearn.ivRank(b0, "VIX", day, 11.0) < -0.9, "IV rank: today's value vs history, re-recorded same day")
        T.eq(FnoLearn.ivRank(LabBook(), "VIX", day, 14.0), 0.0, "no history -> neutral")
        // Platt calibration: overconfident scores are shrunk, honest scores kept
        val r = java.util.Random(42)
        val z1 = DoubleArray(400) { if (it % 2 == 0) 4.0 else -4.0 }; val y1 = DoubleArray(400) { i -> if (r.nextDouble() < (if (i % 2 == 0) 0.6 else 0.4)) 1.0 else 0.0 }
        val a1 = FnoLearn.platt(z1, y1); T.check(a1[0] in 0.0..0.25, "overconfident model recalibrated: slope ${a1[0]}")
        T.check(Math.abs(FnoLearn.sig(a1[0] * 4 + a1[1]) - 0.6) < 0.08, "calibrated probability ≈ observed 60%")
        val z2 = DoubleArray(1000) { r.nextGaussian() * 1.5 }; val y2 = DoubleArray(1000) { if (r.nextDouble() < FnoLearn.sig(z2[it])) 1.0 else 0.0 }
        val a2 = FnoLearn.platt(z2, y2); T.check(a2[0] in 0.75..1.3 && Math.abs(a2[1]) < 0.25, "honest model left about as is: ${a2.toList()}")
        // hierarchical model: the same feature helps one family and hurts another
        val b = LabBook(); val rr = java.util.Random(3)
        repeat(300) { i ->
            val fam = if (i % 2 == 0) "ORB#1" else "RSI#1"; val x = if (rr.nextBoolean()) 1.5 else -1.5
            val win = if (fam == "ORB#1") x > 0 else x < 0
            val fx = List(FnoLearn.NX) { k -> if (k == 0) 1.0 else if (k == 9) x else 0.0 }
            val t = LabTrade("h$i", fam, "NIFTY", "x", 1, "CE", 65, 100.0, 0, day, bids = arrayListOf(if (win) 140.0 else 70.0), feats = listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), p = 0.5, fx = fx)
            t.zx = FnoLearn.rawX(b, FnoLab.baseOf(fam), fx); b.open.add(t); FnoLab.close(b, t)
        }
        val hi = List(FnoLearn.NX) { k -> if (k == 0) 1.0 else if (k == 9) 1.5 else 0.0 }
        T.check(FnoLearn.probX(b, "ORB", hi) > 0.6 && FnoLearn.probX(b, "RSI", hi) < 0.4, "family offsets learn opposite effects: ${FnoLearn.probX(b, "ORB", hi)} / ${FnoLearn.probX(b, "RSI", hi)}")
        T.check(Math.abs(b.wx[9]) < Math.abs(b.wf["ORB"]!![9]) + Math.abs(b.wf["RSI"]!![9]), "global weight stays small where families disagree")
        T.check(FnoLearn.probX(b, "NEWRULE", hi) in 0.3..0.7, "an unseen family falls back to the global model")
        T.check(b.nx == 300 && b.cal[0] != 1.0, "calibration re-fitted after 30+ scored trades: ${b.cal.toList()}")
        T.eq(FnoLearn.entryProb(b, "ORB", listOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), hi), FnoLearn.probX(b, "ORB", hi), "entry probability uses the calibrated model once trained")
        val rt = LabBook.from(Json.parseObject(Json.stringify(b.toMap())))
        T.check(Math.abs(FnoLearn.probX(rt, "ORB", hi) - FnoLearn.probX(b, "ORB", hi)) < 1e-6, "extended model + calibration persist")
        // walk-forward gate
        val sep = (0 until 60).map { i -> val w = i % 2 == 0; Pair(if (w) 0.7 else 0.3, if (w) 2000.0 else -1500.0) }
        T.check(FnoLearn.walkForward(sep).first > 0, "edge that holds out of sample switches the gate on")
        val flip = (0 until 60).map { i -> val w = i % 2 == 0; val late = i >= 42; Pair(if (w != late) 0.7 else 0.3, if (w) 2000.0 else -1500.0) }
        val wf = FnoLearn.walkForward(flip)
        T.check(wf.first == 0.0 && wf.second > 0 && wf.third < 0, "in-sample gain that fails on the newest 30% keeps the gate off: $wf")
        T.eq(FnoLearn.walkForward(sep.take(20)).first, 0.0, "under 30 trades: no gate")
    }

    fun rules() {
        val narrow = DailyCtx(25500.0, 25300.0, 25402.0, doubleArrayOf(300.0, 280.0, 260.0, 250.0, 240.0, 230.0, 200.0), true, 1.1)
        val (top, bot, w) = FnoLearn.cpr(narrow)
        T.check(top > bot && w < 0.1, "CPR from yesterday: width $w%")
        T.check(FnoLearn.isNr(narrow, 7) && FnoLearn.isNr(narrow, 4), "NR7 / NR4 detected")
        T.check(!FnoLearn.isNr(DailyCtx(1.0, 0.0, 0.5, doubleArrayOf(300.0, 100.0, 260.0, 250.0, 240.0, 230.0, 200.0), false, 1.0), 7), "not NR7 when an earlier day was narrower")
        // CPR + NR7: open inside, then a fresh close above the opening range, the CPR and yesterday's high
        val cl = listOf(25420.0, 25440.0, 25430.0, 25438.0, 25520.0)
        val b = bars(cl, 25402.0); b.daily = narrow
        val sg = FnoLab.signalsAll("NIFTY", b, 9 * 60 + 41, emptySet())
        T.check(sg.any { it.strat == "CPR#1" && it.side == "CE" }, "narrow-CPR breakout call: ${sg.map { it.strat }}")
        T.check(sg.any { it.strat == "NR7#1" && it.side == "CE" } && sg.any { it.strat == "NR7#2" }, "NR7 and NR7-inside breakout calls")
        val wide = DailyCtx(25800.0, 25000.0, 25700.0, narrow.ranges, false, 1.1); val b2 = bars(cl, 25402.0); b2.daily = wide
        val sg2 = FnoLab.signalsAll("NIFTY", b2, 9 * 60 + 41, emptySet())
        T.check(sg2.none { it.strat.startsWith("CPR") } && sg2.none { it.strat == "NR7#2" }, "wide CPR / no inside day: no call")
        T.check(FnoLab.signalsAll("NIFTY", bars(cl, 25402.0), 9 * 60 + 41, emptySet()).none { it.strat.startsWith("CPR") || it.strat.startsWith("NR7") }, "no daily history: daily rules stay quiet")
        T.check(FnoLab.signalsAll("NIFTY", b, 9 * 60 + 41, setOf("CPR#1")).none { it.strat == "CPR#1" }, "fires once a day per variant")
        // Supertrend flip: steady fall then a sharp rally
        val st = (0 until 30).map { if (it < 22) 25600.0 - it * 15 else 25600.0 - 21 * 15 + (it - 21) * 60 }
        val sb = bars(st, 25600.0)
        val dir = FnoLearn.supertrend(sb.h, sb.l, sb.c, 10, 3.0)
        val flip = (11 until st.size).firstOrNull { dir[it] == 1 && dir[it - 1] == -1 }
        T.check(flip != null && flip > 21, "supertrend flips up after the rally starts: $flip ${dir.toList()}")
        val sb2 = bars(st.take(flip!! + 1), 25600.0)
        val ss = FnoLab.signalsAll("NIFTY", sb2, sb2.min[sb2.n - 1] + 6, emptySet())
        T.check(ss.any { it.strat == "STREND#1" && it.side == "CE" }, "Supertrend(10,3) flip call: ${ss.map { it.strat }}")
        // Darvas: 9 tight bars then a breakout bar, after 10:00
        val dv = (0 until 12).map { 25400.0 + (if (it % 2 == 0) 3.0 else -3.0) } + (0 until 9).map { 25400.0 + (if (it % 2 == 0) 4.0 else -4.0) } + listOf(25480.0)
        val db = bars(dv, 25390.0)
        val ds = FnoLab.signalsAll("NIFTY", db, db.min[db.n - 1] + 6, emptySet())
        T.check(ds.any { it.strat == "DARVAS#1" && it.side == "CE" }, "Darvas box breakout call: ${ds.map { it.strat }} at ${db.min[db.n - 1]}")
        T.check(FnoLab.signalsAll("NIFTY", db, 9 * 60 + 55, emptySet()).none { it.strat.startsWith("DARVAS") }, "Darvas waits for 10:00")
        // stocks: % thresholds scale, bar-count thresholds do not
        T.eq(FnoLab.paramText("NR7#2"), "NR7 + inside", "variant labels"); T.eq(FnoLab.paramText("STREND#1"), "×3.0 ATR", "supertrend label")
        T.check(FnoLab.allVids("RELIANCE").containsAll(listOf("CPR#0", "NR7#1", "STREND#2", "DARVAS#0")), "new rules run on stocks too")
        // daily context from a Series
        val s = Series("X", 20); for (k in 0 until 12) s.add(day - 12 + k, 100.0, 102.0 + k % 3, 98.0, 100.0 + k * 0.1, 1000.0)
        s.add(day, 100.0, 105.0, 95.0, 101.0, 1.0)
        val dc = FnoLearn.dailyCtx(s, day)!!
        T.eq(dc.prevC, 100.0 + 11 * 0.1, "today's partial bar is ignored"); T.check(dc.atrPct > 3.5 && dc.atrPct < 5.5, "ATR% ${dc.atrPct}")
        T.eq(FnoLearn.dailyCtx(Series("Y", 1), day), null, "too short -> null")
    }

    fun risk() {
        val b = LabBook(); val m = 11 * 60
        T.eq(FnoLearn.riskBlock(b, "NIFTY", "CE", "ORB#1", day, 14 * 60 + 30, 100000.0)?.startsWith("late"), true, "no new calls in the last 45 minutes")
        T.eq(FnoLearn.riskBlock(b, "NIFTY", "CE", "ORB#1", day, 14 * 60 + 29, 100000.0), null, "14:29 still allowed")
        b.open.add(trade("n1", "ORB#1", "NIFTY", listOf(100.0)))
        T.check(FnoLearn.riskBlock(b, "NIFTY", "PE", "RSI#1", day, m, 100000.0)!!.contains("already holding"), "one open call per underlying")
        b.open.add(trade("b1", "TREND#1", "BANKNIFTY", listOf(100.0)))
        T.check(FnoLearn.riskBlock(b, "FINNIFTY", "CE", "LATE#1", day, m, 100000.0)!!.contains("same-direction"), "correlation guard on index calls")
        T.eq(FnoLearn.riskBlock(b, "FINNIFTY", "PE", "LATE#1", day, m, 100000.0), null, "opposite direction allowed")
        T.eq(FnoLearn.riskBlock(b, "RELIANCE", "CE", "LATE#1", day, m, 100000.0), null, "stocks are outside the index guard")
        val shadow = LabBook(); shadow.open.add(trade("s1", "ORB#0", "NIFTY", listOf(100.0), champ = false))
        T.eq(FnoLearn.riskBlock(shadow, "NIFTY", "CE", "ORB#1", day, m, 100000.0), null, "paper challengers never block shown calls")
        // daily loss caps: realised + open loss, per rule and overall
        val c = LabBook(); c.closed.add(trade("l1", "ORB#1", "TCS", listOf(70.0)).apply { status = "STOP"; pnl = -1600.0 })
        T.check(FnoLearn.riskBlock(c, "INFY", "CE", "ORB#2", day, m, 100000.0)!!.contains("daily loss cap"), "rule loss cap (1.5% of capital)")
        T.eq(FnoLearn.riskBlock(c, "INFY", "CE", "RSI#1", day, m, 100000.0), null, "other rules still allowed")
        c.closed.add(trade("l2", "RSI#1", "TCS", listOf(70.0)).apply { status = "STOP"; pnl = -1500.0 })
        T.eq(FnoLearn.riskBlock(c, "INFY", "CE", "LATE#1", day, m, 100000.0), "daily loss cap hit", "overall cap (3% of capital)")
        T.eq(FnoLearn.riskBlock(c, "INFY", "CE", "LATE#1", day + 1, m, 100000.0), null, "caps reset next day")
        // entry path: a risk-blocked champion call is hidden but still paper-traded for learning
        val e = LabBook(); e.open.add(trade("n1", "ORB#1", "NIFTY", listOf(100.0)))
        val bb = bars((0 until 12).map { 25400.0 + it * 5 }, 25350.0)
        val t = LabEntry.open(e, LabSignal("TREND#1", "CE", "x"), "NIFTY", "23-Nov-2026", 25450, 100.0, 99.0, 0L, day, m, bb, true, FeatCtx(14.0), 100000.0, 25450.0)
        T.check(t.gated && t.block.contains("already holding") && e.open.contains(t), "blocked call kept on paper: ${t.block}")
        e.open.remove(t); e.open.clear(); FnoLab.close(e, t.also { e.open.add(it) })
        T.check(FnoLab.filterView(e)["riskBlocked"] == 1 && FnoLab.filterView(e)["skipped"] == 0, "risk blocks reported apart from filter skips")
    }

    fun report() {
        val b = LabBook()
        closeAll(b, (0 until 10).map { trade("r$it", "ORB#1", "NIFTY", listOf(if (it % 2 == 0) 80.0 else 125.0)) })
        val v = FnoLab.view(b, day, 100000.0, emptyMap())
        @Suppress("UNCHECKED_CAST") val orb = (v["strategies"] as List<Map<String, Any?>>).first { it["id"] == "ORB" && it["sym"] == "NIFTY" }
        T.check(orb.containsKey("avg") && orb.containsKey("n") && orb.containsKey("stopPct") && orb.containsKey("variants"), "legacy keys kept")
        @Suppress("UNCHECKED_CAST") val pv = orb["post"] as Map<String, Any?>
        T.check(pv["evidence"] == "UNSURE" && (pv["expLo"] as Double) < 0 && (pv["expHi"] as Double) > 0, "credible interval on ₹/lot crosses zero: $pv")
        T.check((pv["winP"] as Double) in 40.0..60.0, "posterior win% ≈ 50: ${pv["winP"]}")
        @Suppress("UNCHECKED_CAST") val vars = orb["variants"] as List<Map<String, Any?>>
        T.check(vars.all { it.containsKey("evidence") }, "variants carry posteriors")
        T.check((orb["exitText"] as String).startsWith("stop −"), "exit in words: ${orb["exitText"]}")
        @Suppress("UNCHECKED_CAST") val les = v["lessons"] as List<String>
        T.check(les.any { it.startsWith("Opening-range breakout:") && it.contains("Not enough evidence yet") }, "honest lesson: $les")
        T.check(les.any { it.startsWith("Exits:") }, "exit lesson: $les")
        val g = LabBook(); closeAll(g, (0 until 40).map { trade("g$it", "TREND#1", "NIFTY", listOf(if (it % 5 == 0) 80.0 else 140.0)) })
        T.check(FnoLearn.lessons(g).any { it.contains("Positive after charges") }, "proven rule said plainly")
        @Suppress("UNCHECKED_CAST") val fl = v["filter"] as Map<String, Any?>
        T.check(fl.containsKey("gateTestGain") && fl.containsKey("calibrated") && fl.containsKey("trainedX"), "filter reports walk-forward + calibration")
    }
}
