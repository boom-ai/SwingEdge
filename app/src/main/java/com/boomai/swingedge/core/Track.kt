package com.boomai.swingedge.core

/** One plan's outcome for a pick. Prices in Rs; for replay rows fill = 1 and exit = 1 + gross return. */
class PlanOut(
    var status: Int = TrackEngine.ST_OPEN, var fill: Double = 0.0, var exit: Double = 0.0,
    var sessions: Int = 0, var reason: Int = 0
) {
    fun same(o: PlanOut) = status == o.status && fill == o.fill && exit == o.exit && sessions == o.sessions && reason == o.reason
    fun set(o: PlanOut) { status = o.status; fill = o.fill; exit = o.exit; sessions = o.sessions; reason = o.reason }
    /** Gross return as a fraction (closed, or open marked at [mark]). */
    fun gross(mark: Double): Double = if (fill > 0) (if (status == TrackEngine.ST_CLOSED) exit else mark) / fill - 1 else 0.0
}

/**
 * One recorded call of signal day [day] (epoch day). [atr] and [close] are ATR14 and C at bar D, from the table that picked it.
 * BUY / CONTROL: p1 = plan P1, p2 = plan P2. AVOID / AVOID_CTRL: p1 unused (ST_NA), p2 = pure 60-session hold.
 */
class Pick(val sym: String, val day: Int, val group: Int, val atr: Double, val close: Double) {
    val p1 = PlanOut()
    val p2 = PlanOut()
    /** last bar seen for the stock (for the "stopped appearing" rule when its bars are gone) */
    var seenDay = day
    var seenClose = close
    fun done(): Boolean = p1.status != TrackEngine.ST_OPEN && p2.status != TrackEngine.ST_OPEN
    fun plan(k: Int): PlanOut = if (k == 1) p1 else p2
}

/** Exchange sessions: the sorted bar days that enough of the universe traded on. */
class Cal(val days: IntArray) {
    val size: Int get() = days.size
    fun index(d: Int): Int { val i = java.util.Arrays.binarySearch(days, d); return if (i >= 0) i else -1 }
    /** Number of sessions on or before [d]. */
    fun upTo(d: Int): Int { val i = java.util.Arrays.binarySearch(days, d); return if (i >= 0) i + 1 else -(i + 1) }
    /** Number of sessions strictly after [d]. */
    fun after(d: Int): Int = days.size - upTo(d)

    companion object {
        /** A day is a session when at least a tenth of the series have a bar on it (a lone early quote does not make one). */
        fun of(series: Collection<Series>): Cal {
            val count = HashMap<Int, Int>()
            var n = 0
            for (s in series) { if (s.n <= 0) continue; n++; for (k in 0 until s.n) { val d = s.d[k]; count[d] = (count[d] ?: 0) + 1 } }
            val need = maxOf(1, n / 10)
            val out = count.entries.filter { it.value >= need }.map { it.key }.toIntArray()
            java.util.Arrays.sort(out)
            return Cal(out)
        }
    }
}

/** Live-record figures the stockInfo call needs when the upgrade rule is met. */
class TrackProof(
    val upgraded: Boolean, val startedDay: Int, val buyN: Int, val ctrlN: Int, val buyAvg: Double, val buyMedian: Double,
    val buyWin: Double, val ctrlAvg: Double, val edge: Double, val t: Double, val days: Int
)

/**
 * SPEC4 track record: cohorts of the setup score's calls, their outcomes under plans P1 / P2 (REPORT4 X10), the replay over
 * the cached history and the upgrade rule. Pure functions of bars; never throw on bad data (callers also guard).
 */
object TrackEngine {
    const val ST_OPEN = 0
    const val ST_CLOSED = 1
    const val ST_SKIPPED = 2
    const val ST_NA = 3
    val STATUS = arrayOf("OPEN", "CLOSED", "SKIPPED", "NA")

    const val G_BUY = 0
    const val G_CONTROL = 1
    const val G_AVOID = 2
    const val G_AVOID_CTRL = 3
    val GROUPS = arrayOf("BUY", "CONTROL", "AVOID", "AVOID_CTRL")

    const val R_NONE = 0
    const val R_GAP = 1
    const val R_STOP = 2
    const val R_TIME = 3
    const val R_SMA = 4
    const val R_DROP = 5

    const val K_P1 = 1
    const val K_P2 = 2
    const val K_HOLD = 3

    const val HORIZON = 60
    const val DROP_SESSIONS = 10
    const val MIN_CONTROL = 4
    const val MAX_AVOID = 30
    const val P1_ENTRY_ATR = 0.5
    const val P1_STOP_ATR = 3.0
    const val P2_STOP_ATR = 5.0

    const val UPGRADE_MIN_TRADES = 150
    const val UPGRADE_MIN_DAYS = 20
    const val UPGRADE_MIN_T = 2.0

    const val MAX_LIVE = 20000
    const val MAX_REPLAY = 60000
    const val RECENT_MAX = 40
    /** Bump when the replay's rules change, so an old replay file is recomputed. */
    const val REPLAY_VERSION = 1
    /** Auto replay once most of the universe has this many cached bars. */
    const val REPLAY_MIN_BARS = 300

    private val NEG_INF = Double.NEGATIVE_INFINITY

    // ------------------------------------------------------------------ per-stock arrays
    /** Turnover per bar, Rs crore (close x volume, as the live table uses). */
    fun toArray(s: Series): DoubleArray = DoubleArray(s.n) { s.c[it] * s.v[it] / 1e7 }

    /** SMA50 at every bar (NaN before bar 49), each a fresh oldest-to-newest sum like [SetupScore.compute]. */
    fun smaArray(s: Series): DoubleArray = DoubleArray(s.n) { i ->
        if (i >= 49) { var x = 0.0; for (k in i - 49..i) x += s.c[k]; x / 50 } else Double.NaN
    }

    /** Score components at bar [i] — the same call the live table makes on a series whose last bar is i. */
    fun compAt(s: Series, to: DoubleArray, i: Int): ScoreComp = SetupScore.compute(s.h, s.l, s.c, s.c[i], to, i, i + 1, s.d[i])

    /** Cross-section of day [day]: every series with a bar on that day (matched by day, not index). */
    fun tableAt(series: Collection<Series>, day: Int): ScoreTable {
        val items = ArrayList<Pair<String, ScoreComp>>()
        for (s in series) {
            val i = s.indexOf(day)
            if (i >= SetupScore.MIN_UNIVERSE_BARS - 1) items.add(Pair(s.sym, compAt(s, toArray(s), i)))
        }
        return SetupScore.buildTable(items, 0L)
    }

    // ------------------------------------------------------------------ cohorts
    fun seed(day: Int, group: Int): Long = day.toLong() * 1_000_003L + group * 7919L + 17L

    /** [n] entries of [pool] (already in a fixed order) drawn without replacement with java.util.Random(seed). */
    fun draw(pool: List<ScoreEntry>, n: Int, seed: Long): List<ScoreEntry> {
        val a = ArrayList(pool)
        val r = java.util.Random(seed)
        val k = minOf(n, a.size)
        for (i in 0 until k) { val j = i + r.nextInt(a.size - i); val t = a[i]; a[i] = a[j]; a[j] = t }
        return a.subList(0, k).toList()
    }

    private fun pick(e: ScoreEntry, day: Int, g: Int): Pick {
        val p = Pick(e.sym, day, g, e.comp.atr14, e.comp.close)
        if (g >= G_AVOID) p.p1.status = ST_NA
        return p
    }

    /** SPEC4 cohorts of one day's table (rated = the table's ranked cross-section). Empty when the table is not usable. */
    fun cohorts(t: ScoreTable, day: Int): List<Pick> {
        val out = ArrayList<Pick>()
        if (!t.usable) return out
        val ranked = t.ranked
        val buys = ranked.filter { it.label == "BUY" }
        for (e in buys) out.add(pick(e, day, G_BUY))
        if (buys.isNotEmpty()) {
            val pool = ranked.filter { it.label != "BUY" && it.comp.close > it.comp.sma50 }.sortedBy { it.sym }
            for (e in draw(pool, maxOf(buys.size, MIN_CONTROL), seed(day, G_CONTROL))) out.add(pick(e, day, G_CONTROL))
        }
        val avoid = ranked.filter { it.label == "AVOID" }.sortedWith(compareBy<ScoreEntry> { it.score }.thenBy { it.sym }).take(MAX_AVOID)
        for (e in avoid) out.add(pick(e, day, G_AVOID))
        if (avoid.isNotEmpty()) {
            val pool = ranked.filter { it.label != "AVOID" }.sortedBy { it.sym }
            for (e in draw(pool, avoid.size, seed(day, G_AVOID_CTRL))) out.add(pick(e, day, G_AVOID_CTRL))
        }
        return out
    }

    // ------------------------------------------------------------------ outcomes
    private fun closed(e: Double, x: Double, ses: Int, r: Int) = PlanOut(ST_CLOSED, e, x, ses, r)
    private fun skipped() = PlanOut(ST_SKIPPED, 0.0, 0.0, 0, R_NONE)

    /**
     * Outcome of one plan from the bars ([s] completed daily bars, [sma] its SMA50 array, [cal] the sessions). [kind]: P1, P2
     * or pure hold. Already final outcomes are returned unchanged. Without bars only the "stopped appearing" rule can apply.
     */
    fun evalPlan(kind: Int, pk: Pick, prev: PlanOut, s: Series?, sma: DoubleArray?, cal: Cal): PlanOut {
        if (prev.status != ST_OPEN) return prev
        val d0 = pk.day
        val atr = pk.atr
        val j = cal.index(d0)
        if (s == null || s.n == 0 || j < 0) {
            if (cal.size == 0 || cal.after(maxOf(pk.seenDay, d0)) <= DROP_SESSIONS) return prev
            if (prev.fill <= 0) return skipped()
            val ses = if (j >= 0) (cal.upTo(pk.seenDay) - 1 - j).coerceIn(0, HORIZON) else prev.sessions
            return closed(prev.fill, pk.seenClose, ses, R_DROP)
        }
        if (!(atr > 0) || atr.isInfinite()) return skipped()
        val last = s.lastDay()
        val drop = cal.after(maxOf(last, d0)) > DROP_SESSIONS
        if (j + 1 >= cal.size) return PlanOut(ST_OPEN)
        val d1 = cal.days[j + 1]
        val k1 = s.indexOf(d1)
        if (k1 < 0) return if (last > d1 || drop) skipped() else PlanOut(ST_OPEN)
        val e = s.o[k1]
        if (!(e > 0)) return skipped()
        if (kind == K_P1 && e > pk.close + P1_ENTRY_ATR * atr) return skipped()
        val stop = when (kind) { K_P1 -> e - P1_STOP_ATR * atr; K_P2 -> e - P2_STOP_ATR * atr; else -> NEG_INF }
        var lastK = k1
        var reached = 0
        for (m in 1..HORIZON) {
            val jm = j + m
            if (jm >= cal.size) break
            val dk = cal.days[jm]
            val k = s.indexOf(dk)
            if (k < 0) {
                if (last < dk) break                       // its bars have not arrived yet
                reached = m
                if (m == HORIZON) return closed(e, s.c[lastK], m, R_TIME)   // did not trade on session 60: last close before it
                continue
            }
            lastK = k
            reached = m
            if (kind != K_HOLD) {
                if (m > 1 && s.o[k] <= stop) return closed(e, s.o[k], m, R_GAP)
                if (s.l[k] <= stop) return closed(e, stop, m, R_STOP)
            }
            if (m == HORIZON) return closed(e, s.c[k], m, R_TIME)
            if (kind == K_P1 && sma != null && k < sma.size && s.c[k] < sma[k]) {
                if (k + 1 < s.n) {
                    val ses = (cal.upTo(s.d[k + 1]) - 1 - j).coerceAtLeast(m + 1)
                    return closed(e, s.o[k + 1], ses, R_SMA)
                }
                return if (drop) closed(e, s.c[k], m, R_DROP) else PlanOut(ST_OPEN, e, 0.0, m, R_NONE)
            }
        }
        if (drop) return closed(e, s.c[s.n - 1], reached, R_DROP)
        return PlanOut(ST_OPEN, e, 0.0, reached, R_NONE)
    }

    /** Re-evaluates an open pick from its bars. Returns true if anything changed. */
    fun evaluate(pk: Pick, s: Series?, sma: DoubleArray?, cal: Cal): Boolean {
        if (pk.done()) return false
        var changed = false
        if (s != null && s.n > 0 && s.lastDay() > pk.seenDay) { pk.seenDay = s.lastDay(); pk.seenClose = s.c[s.n - 1]; changed = true }
        val k1 = if (pk.group >= G_AVOID) 0 else K_P1
        val k2 = if (pk.group >= G_AVOID) K_HOLD else K_P2
        if (k1 != 0) { val o = evalPlan(k1, pk, pk.p1, s, sma, cal); if (!o.same(pk.p1)) { pk.p1.set(o); changed = true } }
        val o2 = evalPlan(k2, pk, pk.p2, s, sma, cal)
        if (!o2.same(pk.p2)) { pk.p2.set(o2); changed = true }
        return changed
    }

    // ------------------------------------------------------------------ replay
    class ReplayOut(val picks: List<Pick>, val fromDay: Int, val toDay: Int, val days: Int)

    /**
     * Cohorts and outcomes for every session of the cached history that has at least 60 sessions after it, using the table of
     * that day's cross-section. Components are computed once per stock and day with the live formula (so a day's table is
     * bit-identical to the live one); [progress] gets 0..100.
     */
    fun replay(series: List<Series>, progress: ((Int) -> Unit)? = null): ReplayOut {
        val ss = series.filter { it.n > 0 }
        val cal = Cal.of(ss)
        val to = ss.map { toArray(it) }
        val sma = HashMap<String, DoubleArray>()
        val bySym = HashMap<String, Series>()
        for (s in ss) bySym[s.sym] = s
        val ptr = IntArray(ss.size)
        val picks = ArrayList<Pick>()
        var from = 0
        var toDay = 0
        var days = 0
        val lastSig = cal.size - 1 - HORIZON
        var lastPct = -1
        for (j in 0..lastSig) {
            val d = cal.days[j]
            val items = ArrayList<Pair<String, ScoreComp>>()
            for (x in ss.indices) {
                val s = ss[x]
                var p = ptr[x]
                while (p < s.n && s.d[p] < d) p++
                ptr[x] = p
                if (p < s.n && s.d[p] == d && p >= SetupScore.MIN_UNIVERSE_BARS - 1) items.add(Pair(s.sym, compAt(s, to[x], p)))
            }
            if (items.size >= Params.SCORE_MIN_RATED) {
                val t = SetupScore.buildTable(items, 0L)
                if (t.usable) {
                    val c = cohorts(t, d)
                    for (pk in c) {
                        val s = bySym[pk.sym]
                        val a = if (s == null) null else sma.getOrPut(pk.sym) { smaArray(s) }
                        evaluate(pk, s, a, cal)
                    }
                    picks.addAll(c)
                    if (from == 0) from = d
                    toDay = d
                    days++
                }
            }
            val pct = if (lastSig <= 0) 100 else (j + 1) * 100 / (lastSig + 1)
            if (pct != lastPct) { lastPct = pct; try { progress?.invoke(pct) } catch (t: Throwable) {} }
        }
        return ReplayOut(picks, from, toDay, days)
    }

    // ------------------------------------------------------------------ statistics
    /** Linear-interpolation percentile of sorted [a] (q in 0..1). */
    fun quantile(a: DoubleArray, q: Double): Double {
        if (a.isEmpty()) return 0.0
        val pos = q * (a.size - 1)
        val lo = Math.floor(pos).toInt().coerceIn(0, a.size - 1)
        val hi = minOf(lo + 1, a.size - 1)
        return a[lo] + (a[hi] - a[lo]) * (pos - lo)
    }

    /** G object for one group under plan [kind] (1 = p1, else p2): net-of-cost stats over closed trades. */
    fun g(picks: List<Pick>, group: Int, kind: Int, costPct: Double): Map<String, Any?> {
        var n = 0; var open = 0
        val rets = ArrayList<Double>()
        var ses = 0L
        for (pk in picks) {
            if (pk.group != group) continue
            val o = pk.plan(kind)
            if (o.status == ST_NA) continue
            n++
            if (o.status == ST_OPEN) open++
            else if (o.status == ST_CLOSED) { rets.add(o.gross(0.0) * 100.0 - costPct); ses += o.sessions }
        }
        val a = rets.toDoubleArray()
        java.util.Arrays.sort(a)
        val c = a.size
        val m = LinkedHashMap<String, Any?>()
        m["n"] = n; m["open"] = open; m["closed"] = c
        m["winPct"] = if (c > 0) Fmt.r1(a.count { it > 0 } * 100.0 / c) else 0.0
        m["avgPct"] = if (c > 0) Fmt.r2(a.sum() / c) else 0.0
        m["medianPct"] = if (c > 0) Fmt.r2(quantile(a, 0.5)) else 0.0
        m["worstPct"] = if (c > 0) Fmt.r2(quantile(a, 0.05)) else 0.0
        m["avgSessions"] = if (c > 0) Fmt.r1(ses.toDouble() / c) else 0.0
        return m
    }

    class Edge(val mean: Double, val t: Double, val days: Int)

    /** Per-signal-day mean P1 return BUY minus CONTROL (closed trades) over days that have both; t = mean / (sd / sqrt(k)). */
    fun edge(picks: List<Pick>, costPct: Double): Edge {
        val sums = HashMap<Int, DoubleArray>()   // day -> [buySum, buyN, ctrlSum, ctrlN]
        for (pk in picks) {
            if (pk.group != G_BUY && pk.group != G_CONTROL) continue
            if (pk.p2.status != ST_CLOSED) continue   // round 5: the app's plan is P2
            val a = sums.getOrPut(pk.day) { DoubleArray(4) }
            val r = pk.p2.gross(0.0) * 100.0 - costPct
            if (pk.group == G_BUY) { a[0] += r; a[1] += 1.0 } else { a[2] += r; a[3] += 1.0 }
        }
        val diffs = ArrayList<Double>()
        for (d in sums.keys.sorted()) { val a = sums[d]!!; if (a[1] > 0 && a[3] > 0) diffs.add(a[0] / a[1] - a[2] / a[3]) }
        val k = diffs.size
        if (k == 0) return Edge(0.0, 0.0, 0)
        val mean = diffs.sum() / k
        if (k < 2) return Edge(mean, 0.0, k)
        var ss = 0.0
        for (x in diffs) ss += (x - mean) * (x - mean)
        val sd = Math.sqrt(ss / (k - 1))
        val t = if (sd > 0) mean / (sd / Math.sqrt(k.toDouble())) else 0.0
        return Edge(if (mean.isNaN()) 0.0 else mean, if (t.isNaN() || t.isInfinite()) 0.0 else t, k)
    }

    /** The upgrade rule (forward records only): >= 150 closed P2 trades in BUY and CONTROL, k >= 20 days, edge > 0 with t >= 2, BUY mean > 0. */
    fun proof(live: List<Pick>, costPct: Double, startedDay: Int): TrackProof {
        val gb = g(live, G_BUY, 2, costPct)
        val gc = g(live, G_CONTROL, 2, costPct)
        val e = edge(live, costPct)
        val nb = gb["closed"] as Int
        val nc = gc["closed"] as Int
        val buyMean = buyMeanRaw(live, costPct)
        val up = nb >= UPGRADE_MIN_TRADES && nc >= UPGRADE_MIN_TRADES && e.days >= UPGRADE_MIN_DAYS && e.mean > 0 && e.t >= UPGRADE_MIN_T && buyMean > 0
        return TrackProof(up, startedDay, nb, nc, gb["avgPct"] as Double, gb["medianPct"] as Double, gb["winPct"] as Double,
            gc["avgPct"] as Double, e.mean, e.t, e.days)
    }

    private fun buyMeanRaw(live: List<Pick>, costPct: Double): Double {
        var s = 0.0; var n = 0
        for (pk in live) if (pk.group == G_BUY && pk.p2.status == ST_CLOSED) { s += pk.p2.gross(0.0) * 100.0 - costPct; n++ }
        return if (n > 0) s / n else 0.0
    }

    private fun groupsMap(picks: List<Pick>, costPct: Double): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["P1"] = linkedMapOf("buy" to g(picks, G_BUY, 1, costPct), "control" to g(picks, G_CONTROL, 1, costPct))
        m["P2"] = linkedMapOf("buy" to g(picks, G_BUY, 2, costPct), "control" to g(picks, G_CONTROL, 2, costPct))
        m["avoid"] = g(picks, G_AVOID, 2, costPct)
        m["avoidCtrl"] = g(picks, G_AVOID_CTRL, 2, costPct)
        val e = edge(picks, costPct)
        m["edgePct"] = Fmt.r2(e.mean)
        m["edgeT"] = Fmt.r2(e.t)
        return m
    }

    private fun ymd(d: Int) = if (d > 0) StockPage.yyyymmdd(d) else 0

    fun recentRow(pk: Pick, costPct: Double): Map<String, Any?> {
        val kind = 2   // round 5: P2 is the app's plan for BUY and CONTROL; AVOID groups use P2 too (pure hold)
        val o = pk.plan(kind)
        val ret = when (o.status) {
            ST_CLOSED -> o.gross(0.0) * 100.0 - costPct
            ST_OPEN -> if (o.fill > 0) o.gross(pk.seenClose) * 100.0 - costPct else 0.0
            else -> 0.0
        }
        return linkedMapOf(
            "sym" to pk.sym, "group" to GROUPS[pk.group], "day" to ymd(pk.day), "plan" to (if (kind == 1) "P1" else "P2"),
            "status" to STATUS[o.status.coerceIn(0, 2)], "fill" to Fmt.r2(o.fill), "exit" to (if (o.status == ST_CLOSED) Fmt.r2(o.exit) else 0.0),
            "retPct" to Fmt.r2(ret), "sessions" to o.sessions
        )
    }

    /** The state's `track` object (SPEC4), every field present. */
    fun view(store: TrackStore, costPct: Double, busyReplay: Boolean): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["startedDay"] = ymd(store.startedDay)
        m["lastDay"] = ymd(store.lastDay)
        m["replayDone"] = store.replayDone
        m["busyReplay"] = busyReplay
        val live = groupsMap(store.live, costPct)
        val pr = proof(store.live, costPct, store.startedDay)
        live["upgraded"] = pr.upgraded
        live["needText"] = ScoreTexts.trackNeedText(pr)
        m["live"] = live
        val rep = groupsMap(store.replay, costPct)
        rep["fromDay"] = ymd(store.replayFrom)
        rep["toDay"] = ymd(store.replayTo)
        m["replay"] = rep
        val order = compareByDescending<Pick> { it.day }.thenBy { it.group }.thenBy { it.sym }
        m["recent"] = store.live.sortedWith(order).take(RECENT_MAX).map { recentRow(it, costPct) }
        return m
    }
}

/** Live picks (forward.json) and the replay (replay.json). Not thread-safe: used under the app lock only. */
class TrackStore {
    val live = ArrayList<Pick>()
    var replay: List<Pick> = emptyList()
    var startedDay = 0
    var lastDay = 0
    var replayDone = false
    var replayFrom = 0
    var replayTo = 0

    fun addCohort(day: Int, picks: List<Pick>) {
        if (day <= lastDay) return
        live.addAll(picks)
        lastDay = day
        if (startedDay == 0) startedDay = day
        cap()
    }

    fun resetLive() { live.clear(); startedDay = 0; lastDay = 0 }

    /**
     * At most MAX_LIVE picks: drop the oldest finished ones first — finished AVOID / AVOID_CTRL picks before finished BUY /
     * CONTROL ones (those carry the upgrade evidence; the AVOID groups add ~60 picks a day) — then the oldest.
     */
    fun cap() {
        if (live.size <= TrackEngine.MAX_LIVE) return
        live.sortWith(compareBy<Pick> { it.day }.thenBy { it.group }.thenBy { it.sym })
        var extra = live.size - TrackEngine.MAX_LIVE
        for (pass in 0..1) {
            val it = live.iterator()
            while (it.hasNext() && extra > 0) {
                val pk = it.next()
                if (pk.done() && (pass == 1 || pk.group >= TrackEngine.G_AVOID)) { it.remove(); extra-- }
            }
        }
        while (extra > 0 && live.isNotEmpty()) { live.removeAt(0); extra-- }
    }

    fun setReplay(r: TrackEngine.ReplayOut) {
        replay = if (r.picks.size > TrackEngine.MAX_REPLAY) r.picks.subList(r.picks.size - TrackEngine.MAX_REPLAY, r.picks.size).toList() else r.picks
        replayFrom = if (replay.isEmpty()) 0 else replay.first().day
        replayTo = r.toDay
        replayDone = r.days > 0
    }

    // ------------------------------------------------------------------ persistence
    private fun planCells(o: PlanOut): List<Any?> = listOf(o.status, Fmt.r2(o.fill), (o.exit * 10000.0).let { Math.round(it) / 10000.0 }, o.sessions, o.reason)

    /** forward.json: {"v":1,"start":d,"last":d,"p":[[sym,day,group,atr,close,seenDay,seenClose, s1,f1,x1,n1,r1, s2,f2,x2,n2,r2]]}. */
    fun encodeLive(): String {
        val rows = ArrayList<Any?>(live.size)
        for (pk in live) {
            val r = ArrayList<Any?>(17)
            r.add(pk.sym); r.add(pk.day); r.add(pk.group); r.add(pk.atr.toString()); r.add(pk.close.toString())
            r.add(pk.seenDay); r.add(Fmt.r2(pk.seenClose))
            r.addAll(planCells(pk.p1)); r.addAll(planCells(pk.p2))
            rows.add(r)
        }
        return Json.stringify(linkedMapOf("v" to 1, "start" to startedDay, "last" to lastDay, "p" to rows))
    }

    private fun num(x: Any?): Double = when (x) {
        is Double -> x
        is Number -> x.toDouble()
        is String -> x.toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    private fun plan(r: List<Any?>, at: Int): PlanOut? {
        val st = num(r[at]); val f = num(r[at + 1]); val x = num(r[at + 2]); val n = num(r[at + 3]); val rs = num(r[at + 4])
        if (st.isNaN() || f.isNaN() || x.isNaN() || n.isNaN() || rs.isNaN()) return null
        val s = st.toInt()
        if (s < 0 || s > 3 || f < 0 || x < 0 || n < 0 || n > 1000) return null
        if (s == TrackEngine.ST_CLOSED && !(f > 0 && x > 0)) return null
        return PlanOut(s, f, x, n.toInt(), rs.toInt().coerceIn(0, 5))
    }

    /** Tolerant: bad rows are skipped, a bad file gives an empty record. */
    fun loadLive(text: String?) {
        live.clear(); startedDay = 0; lastDay = 0
        val m = try { Json.parseObject(text) } catch (t: Throwable) { null } ?: return
        try {
            startedDay = m.int("start").coerceAtLeast(0)
            lastDay = m.int("last").coerceAtLeast(0)
            for (x in m.list("p")) {
                try {
                    val r = x as? List<Any?> ?: continue
                    if (r.size < 17) continue
                    val sym = r[0] as? String ?: continue
                    if (sym.isEmpty() || sym.length > 25) continue
                    val day = num(r[1]); val g = num(r[2]); val atr = num(r[3]); val c = num(r[4])
                    if (day.isNaN() || g.isNaN() || !(atr > 0) || !(c > 0) || atr.isInfinite() || c.isInfinite()) continue
                    val gi = g.toInt()
                    if (gi < 0 || gi > 3 || day < 1) continue
                    val p1 = plan(r, 7) ?: continue
                    val p2 = plan(r, 12) ?: continue
                    val pk = Pick(sym, day.toInt(), gi, atr, c)
                    val sd = num(r[5]); val sc = num(r[6])
                    if (!sd.isNaN() && sd >= day) pk.seenDay = sd.toInt()
                    if (sc > 0) pk.seenClose = sc
                    pk.p1.set(p1); pk.p2.set(p2)
                    if (gi >= TrackEngine.G_AVOID) pk.p1.status = TrackEngine.ST_NA else if (pk.p1.status == TrackEngine.ST_NA) continue
                    if (pk.p2.status == TrackEngine.ST_NA) continue
                    live.add(pk)
                } catch (t: Throwable) { /* skip the row */ }
            }
            if (lastDay == 0 && live.isNotEmpty()) lastDay = live.maxOf { it.day }
            if (startedDay == 0 && live.isNotEmpty()) startedDay = live.minOf { it.day }
            cap()
        } catch (t: Throwable) { /* keep what loaded */ }
    }

    private fun rCell(o: PlanOut): List<Any?> =
        listOf(o.status, if (o.status == TrackEngine.ST_CLOSED) Math.round(o.gross(0.0) * 1e6) else 0L, o.sessions)

    /** replay.json: {"v":ver,"done":b,"from":d,"to":d,"p":[[dayOffset,group, s1,gross1e6,n1, s2,gross1e6,n2]]} (no symbols). */
    fun encodeReplay(): String {
        val base = replayFrom
        val rows = ArrayList<Any?>(replay.size)
        for (pk in replay) {
            val r = ArrayList<Any?>(8)
            r.add(pk.day - base); r.add(pk.group); r.addAll(rCell(pk.p1)); r.addAll(rCell(pk.p2))
            rows.add(r)
        }
        return Json.stringify(linkedMapOf("v" to TrackEngine.REPLAY_VERSION, "done" to replayDone, "from" to replayFrom, "to" to replayTo, "p" to rows))
    }

    private fun rPlan(r: List<Any?>, at: Int): PlanOut? {
        val s = num(r[at]); val g = num(r[at + 1]); val n = num(r[at + 2])
        if (s.isNaN() || g.isNaN() || n.isNaN() || s < 0 || s > 3 || n < 0 || n > 1000 || g <= -1e6) return null
        val si = s.toInt()
        return if (si == TrackEngine.ST_CLOSED) PlanOut(si, 1.0, 1.0 + g / 1e6, n.toInt(), 0) else PlanOut(si, 0.0, 0.0, n.toInt(), 0)
    }

    fun loadReplay(text: String?) {
        replay = emptyList(); replayDone = false; replayFrom = 0; replayTo = 0
        val m = try { Json.parseObject(text) } catch (t: Throwable) { null } ?: return
        try {
            if (m.int("v") != TrackEngine.REPLAY_VERSION) return      // old rules: recompute
            val from = m.int("from")
            val out = ArrayList<Pick>()
            for (x in m.list("p")) {
                try {
                    val r = x as? List<Any?> ?: continue
                    if (r.size < 8) continue
                    val off = num(r[0]); val g = num(r[1])
                    if (off.isNaN() || g.isNaN() || off < 0 || g < 0 || g > 3) continue
                    val p1 = rPlan(r, 2) ?: continue
                    val p2 = rPlan(r, 5) ?: continue
                    val pk = Pick("", from + off.toInt(), g.toInt(), 1.0, 1.0)
                    pk.p1.set(p1); pk.p2.set(p2)
                    if (pk.group >= TrackEngine.G_AVOID) pk.p1.status = TrackEngine.ST_NA
                    out.add(pk)
                    if (out.size >= TrackEngine.MAX_REPLAY) break
                } catch (t: Throwable) {}
            }
            replay = out
            replayFrom = from
            replayTo = m.int("to")
            replayDone = m.bool("done") && out.isNotEmpty()
        } catch (t: Throwable) {
            replay = emptyList(); replayDone = false
        }
    }
}
