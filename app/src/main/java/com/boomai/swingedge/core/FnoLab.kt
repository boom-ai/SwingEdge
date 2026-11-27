package com.boomai.swingedge.core

/*
 * F&O lab: rule-based intraday option-buying calls on Nifty / Bank Nifty that learn from every trade.
 * Each strategy fires at most once per index per day from 5-minute index bars. The call is "taken" on paper at the
 * live ask of the ATM option and followed on the live chain (bid) until 15:15. When a trade ends, every stop/target pair
 * in a small grid is replayed on the same premium path, so each trade teaches which exit works best for that strategy.
 * A strategy is only marked "Working" after enough trades show a positive average after charges; failing ones are
 * hidden from the calls but keep being paper-tested. Pure Kotlin, never throws.
 */

class IntraBars(val prevClose: Double, val min: IntArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray) {
    val n get() = c.size
    val last get() = if (n > 0) c[n - 1] else 0.0
    val open get() = if (n > 0) o[0] else 0.0
    /** Previous days' context (for CPR / NR7 rules and the ATR feature); null when no daily history is at hand. */
    var daily: DailyCtx? = null
}

class LabSignal(val strat: String, val side: String, val why: String)

class LabTrade(var id: String = "", var strat: String = "", var sym: String = "", var expiry: String = "", var strike: Int = 0, var side: String = "",
               var lot: Int = 0, var entry: Double = 0.0, var at: Long = 0, var day: Int = 0, var why: String = "", var stopPct: Double = 30.0,
               var tgtPct: Double = 50.0, var bids: ArrayList<Double> = ArrayList(), var status: String = "OPEN", var exitPx: Double = 0.0,
               var pnl: Double = 0.0, var spot: Double = 0.0, var liveHit: String = "", var champ: Boolean = true,
               var feats: List<Double> = emptyList(), var p: Double = -1.0, var gated: Boolean = false, var cid: String = "",
               var mins: ArrayList<Int> = ArrayList(), var arm: Double = 0.0, var trail: Double = 0.0, var tmin: Int = 0,
               var fx: List<Double> = emptyList(), var zx: Double = 0.0, var block: String = "") {
    val rule get() = ExitRule(stopPct, tgtPct, arm, trail, tmin)
    fun setRule(r: ExitRule) { stopPct = r.stop; tgtPct = r.tgt; arm = r.arm; trail = r.trail; tmin = r.tmin }
    /** Record a live bid with its minute of day (time stops replay on it). */
    fun mark(bid: Double, m: Int) { bids.add(bid); while (mins.size < bids.size - 1) mins.add(-1); mins.add(m) }
    val qty get() = lot
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "strat" to strat, "sym" to sym, "expiry" to expiry, "strike" to strike, "side" to side,
        "lot" to lot, "entry" to entry, "at" to at, "day" to day, "why" to why, "stopPct" to stopPct, "tgtPct" to tgtPct, "bids" to bids.toList(),
        "status" to status, "exitPx" to exitPx, "pnl" to pnl, "spot" to spot, "liveHit" to liveHit, "champ" to champ, "feats" to feats, "p" to p, "gated" to gated, "cid" to cid,
        "mins" to mins.toList(), "arm" to arm, "trail" to trail, "tmin" to tmin, "fx" to fx, "zx" to zx, "block" to block)
    companion object {
        fun from(m: Map<String, Any?>) = LabTrade(m.str("id"), m.str("strat"), m.str("sym"), m.str("expiry"), m.int("strike"), m.str("side"), m.int("lot"),
            m.num("entry", 0.0), m.long("at"), m.int("day"), m.str("why"), m.num("stopPct", 30.0), m.num("tgtPct", 50.0),
            ArrayList(m.list("bids").mapNotNull { (it as? Number)?.toDouble() }.take(FnoLab.MAX_PATH)), m.str("status").ifEmpty { "OPEN" },
            m.num("exitPx", 0.0), m.num("pnl", 0.0), m.num("spot", 0.0), m.str("liveHit"), m["champ"] != false,
            m.list("feats").mapNotNull { (it as? Number)?.toDouble() }, m.num("p", -1.0), m["gated"] == true, m.str("cid"),
            ArrayList(m.list("mins").mapNotNull { (it as? Number)?.toInt() }.take(FnoLab.MAX_PATH)), m.num("arm", 0.0), m.num("trail", 0.0), m.int("tmin"),
            m.list("fx").mapNotNull { (it as? Number)?.toDouble() }, m.num("zx", 0.0), m.str("block"))
    }
}

class VarStat(var n: Int = 0, var sum: Double = 0.0, var sq: Double = 0.0, var wins: Int = 0) {
    fun add(x: Double) { n++; sum += x; sq += x * x; if (x > 0) wins++ }
    val mean get() = if (n > 0) sum / n else 0.0
    val sd get() = if (n > 1) Math.sqrt(Math.max(0.0, (sq - sum * sum / n) / (n - 1))) else 0.0
    val t get() = if (n > 1 && sd > 0) mean / (sd / Math.sqrt(n.toDouble())) else 0.0
    fun toList() = listOf(n, sum, sq, wins)
    companion object { fun from(v: Any?): VarStat { val l = (v as? List<*>).orEmpty().map { (it as? Number)?.toDouble() ?: 0.0 }; return if (l.size < 4) VarStat() else VarStat(l[0].toInt(), l[1], l[2], l[3].toInt()) } }
}

class LabBook(val open: MutableList<LabTrade> = ArrayList(), val closed: MutableList<LabTrade> = ArrayList(),
              val stats: MutableMap<String, MutableMap<String, VarStat>> = LinkedHashMap(), val fired: MutableMap<String, Int> = HashMap(), var seq: Int = 0,
              var w: DoubleArray = DoubleArray(FnoLab.NF), var trained: Int = 0, var gate: Double = 0.0) {
    /** Decayed posteriors: key ("ORB#1|NIFTY", "ORB#1|STOCKS", "ORB|*") -> cell (exit rule key or "live") -> stats. */
    val post: MutableMap<String, MutableMap<String, DStat>> = LinkedHashMap()
    var wx = DoubleArray(FnoLearn.NX); val wf: MutableMap<String, DoubleArray> = LinkedHashMap(); var nx = 0
    var cal = doubleArrayOf(1.0, 0.0)
    val ivh: MutableMap<String, ArrayList<Double>> = LinkedHashMap(); val pcr0: MutableMap<String, List<Double>> = LinkedHashMap()
    var gateTrainGain = 0.0; var gateTestGain = 0.0
    fun toMap(): Map<String, Any?> = linkedMapOf("seq" to seq, "open" to open.map { it.toMap() }, "closed" to closed.map { it.toMap() },
        "stats" to stats.mapValues { (_, v) -> v.mapValues { it.value.toList() } }, "fired" to fired, "w" to w.toList(), "trained" to trained, "gate" to gate,
        "post" to post.mapValues { (_, v) -> v.mapValues { it.value.toList() } }, "wx" to wx.toList(), "wf" to wf.mapValues { it.value.toList() }, "nx" to nx,
        "cal" to cal.toList(), "ivh" to ivh, "pcr0" to pcr0, "gateWF" to listOf(gateTrainGain, gateTestGain))
    companion object {
        fun from(m: Map<String, Any?>?): LabBook {
            val b = LabBook()
            if (m == null) return b
            b.seq = m.int("seq")
            @Suppress("UNCHECKED_CAST") for (x in m.list("open")) (x as? Map<String, Any?>)?.let { b.open.add(LabTrade.from(it)) }
            @Suppress("UNCHECKED_CAST") for (x in m.list("closed")) (x as? Map<String, Any?>)?.let { b.closed.add(LabTrade.from(it)) }
            (m["stats"] as? Map<*, *>)?.forEach { (k, v) -> val mm = LinkedHashMap<String, VarStat>(); (v as? Map<*, *>)?.forEach { (vk, vv) -> mm[vk.toString()] = VarStat.from(vv) }; b.stats[k.toString()] = mm }
            (m["fired"] as? Map<*, *>)?.forEach { (k, v) -> (v as? Number)?.let { b.fired[k.toString()] = it.toInt() } }
            val ww = m.list("w").mapNotNull { (it as? Number)?.toDouble() }
            if (ww.size == FnoLab.NF) b.w = ww.toDoubleArray()
            b.trained = m.int("trained"); b.gate = m.num("gate", 0.0)
            (m["post"] as? Map<*, *>)?.forEach { (k, v) -> val mm = LinkedHashMap<String, DStat>(); (v as? Map<*, *>)?.forEach { (vk, vv) -> mm[vk.toString()] = DStat.from(vv) }; b.post[k.toString()] = mm }
            fun dl(x: Any?) = (x as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toDouble() }
            dl(m["wx"]).takeIf { it.size == FnoLearn.NX }?.let { b.wx = it.toDoubleArray() }
            (m["wf"] as? Map<*, *>)?.forEach { (k, v) -> dl(v).takeIf { it.size == FnoLearn.NX }?.let { b.wf[k.toString()] = it.toDoubleArray() } }
            b.nx = m.int("nx"); dl(m["cal"]).takeIf { it.size == 2 }?.let { b.cal = it.toDoubleArray() }
            (m["ivh"] as? Map<*, *>)?.forEach { (k, v) -> b.ivh[k.toString()] = ArrayList(dl(v)) }
            (m["pcr0"] as? Map<*, *>)?.forEach { (k, v) -> b.pcr0[k.toString()] = dl(v) }
            dl(m["gateWF"]).takeIf { it.size == 2 }?.let { b.gateTrainGain = it[0]; b.gateTestGain = it[1] }
            return b
        }
    }
}

object FnoLab {
    const val MAX_PATH = 420
    const val MAX_CLOSED = 600
    const val EXIT_MIN = 15 * 60 + 15
    val STOPS = listOf(20.0, 30.0, 40.0)
    val TGTS = listOf(30.0, 50.0, 80.0, 0.0)       // 0 = no target, hold to 15:15
    const val DEF_STOP = 30.0
    const val DEF_TGT = 50.0
    val INDEX = mapOf("NIFTY" to "^NSEI", "BANKNIFTY" to "^NSEBANK")

    class Strat(val id: String, val name: String, val rule: String)
    val STRATS = listOf(
        Strat("ORB", "Opening-range breakout", "9:30–11:30: a 5-min close above the 9:15–9:30 high buys a call, below the low buys a put. Skipped when that range is wider than 0.6%."),
        Strat("FIRSTHR", "First-hour momentum", "10:20–10:45: if 9:15–10:15 moved 0.4%+ and price makes a new 5-min high (low), buy a call (put)."),
        Strat("TREND", "Trend day", "10:30–13:30: price 0.3%+ beyond the open and above (below) a rising (falling) 20-bar average buys a call (put)."),
        Strat("LATE", "Afternoon momentum", "13:00–13:45: a move of 0.6%+ from the open buys in that direction."),
        Strat("GAPDN", "Gap-down bounce", "9:20–9:35: if Nifty opened 0.5%+ below yesterday's close, buy a call."),
        Strat("GAPFADE", "Gap fade", "9:30–9:45: a 0.7%+ gap that has already given back ground from the open is faded."),
        Strat("RSI", "RSI bounce", "9:45–2:00: 14-bar RSI on 5-min bars turning back up from oversold buys a call; turning down from overbought buys a put."),
        Strat("EXPIRY", "Expiry-day breakout", "Nifty expiry day, 13:30–14:30: a 5-min close beyond the day's range so far buys in that direction."),
        Strat("CPR", "Narrow-CPR breakout", "9:35–12:00 on a narrow central-pivot-range day: a fresh 5-min close above the CPR and the 9:15–9:30 high buys a call; below both, a put."),
        Strat("NR7", "NR7 / inside-day breakout", "9:35–12:00 after a narrow-range day (NR4, NR7, or NR7 + inside day): a fresh 5-min close above yesterday's high buys a call; below its low, a put."),
        Strat("STREND", "Supertrend flip", "9:45–2:00: Supertrend(10, ×ATR) on 5-min bars flipping up buys a call; flipping down buys a put."),
        Strat("DARVAS", "Darvas box breakout", "10:00–2:00: a 5-min close out of a tight box (the last N bars within 0.5%; 1% for stocks) buys in the breakout direction."),
    )
    fun stratName(id: String) = STRATS.firstOrNull { it.id == baseOf(id) }?.name ?: id
    fun vkey(s: Double, t: Double) = "${s.toInt()}/${t.toInt()}"

    private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.replace(",", "").toDoubleOrNull(); else -> null }?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** Yahoo chart JSON (range=1d, interval=5m) -> today's 5-minute bars with yesterday's close. */
    fun parseBars(text: String?): IntraBars? = try {
        val o = Json.parseObject(text ?: "")
        val r = ((o?.get("chart") as? Map<*, *>)?.get("result") as? List<*>)?.firstOrNull() as? Map<*, *>
        val meta = r?.get("meta") as? Map<*, *>
        val prev = num(meta?.get("chartPreviousClose")) ?: num(meta?.get("previousClose")) ?: 0.0
        val ts = (r?.get("timestamp") as? List<*>).orEmpty()
        val q = (((r?.get("indicators") as? Map<*, *>)?.get("quote") as? List<*>)?.firstOrNull() as? Map<*, *>)
        val oo = (q?.get("open") as? List<*>).orEmpty(); val hh = (q?.get("high") as? List<*>).orEmpty()
        val ll = (q?.get("low") as? List<*>).orEmpty(); val cc = (q?.get("close") as? List<*>).orEmpty()
        val mi = ArrayList<Int>(); val a = ArrayList<Double>(); val b = ArrayList<Double>(); val c = ArrayList<Double>(); val d = ArrayList<Double>()
        for (i in ts.indices) {
            val t = (ts[i] as? Number)?.toLong() ?: continue
            val cl = num(cc.getOrNull(i)) ?: continue
            if (cl <= 0) continue
            val op = num(oo.getOrNull(i)) ?: cl; val hi = num(hh.getOrNull(i)) ?: cl; val lo = num(ll.getOrNull(i)) ?: cl
            mi.add(Clock.minuteOfDay(t * 1000)); a.add(op); b.add(Math.max(hi, Math.max(op, cl))); c.add(Math.min(lo, Math.min(op, cl))); d.add(cl)
        }
        if (d.isEmpty() || prev <= 0) null else IntraBars(prev, mi.toIntArray(), a.toDoubleArray(), b.toDoubleArray(), c.toDoubleArray(), d.toDoubleArray())
    } catch (t: Throwable) { null }

    /** Wilder RSI of closes up to index [upto]. */
    fun rsi(c: DoubleArray, n: Int, upto: Int): Double {
        if (upto < n) return 50.0
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        g /= n; l /= n
        for (i in n + 1..upto) { val d = c[i] - c[i - 1]; g = (g * (n - 1) + Math.max(d, 0.0)) / n; l = (l * (n - 1) + Math.max(-d, 0.0)) / n }
        return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l)
    }

    /* ---------- self-learning filter: logistic model on market conditions at entry ---------- */
    const val NF = 8
    val FEAT_NAMES = listOf("bias", "RSI in trade direction", "distance from 20-bar average", "move from open", "today's range", "time of day", "India VIX", "gap")
    /** Features at entry, signed so that "with the trade" is positive for both calls and puts. */
    fun features(bars: IntraBars, m: Int, side: String, vix: Double?): List<Double> {
        val sg = if (side == "CE") 1.0 else -1.0
        val li = (0 until bars.n).lastOrNull { bars.min[it] + 5 <= m } ?: (bars.n - 1)
        val last = bars.c[li]; val open = bars.open
        val e = ema(bars.c, 20, li)
        val hi = (0..li).maxOf { bars.h[it] }; val lo = (0..li).minOf { bars.l[it] }
        fun cl(x: Double) = Math.max(-3.0, Math.min(3.0, x))
        return listOf(1.0, cl((rsi(bars.c, 14, li) - 50) / 25 * sg), cl((last - e) / e * 100 / 0.3 * sg), cl((last - open) / open * 100 / 0.5 * sg),
            cl((hi - lo) / open * 100 / 1.0 - 1), cl((m - 12 * 60) / 120.0), cl(((vix ?: 14.0) - 14) / 4), cl((open - bars.prevClose) / bars.prevClose * 100 / 0.5 * sg))
    }
    fun predict(w: DoubleArray, f: List<Double>): Double {
        if (f.size != NF) return 0.5
        var z = 0.0; for (i in 0 until NF) z += w[i] * f[i]
        return 1 / (1 + Math.exp(-Math.max(-30.0, Math.min(30.0, z))))
    }
    /** One SGD step on a closed trade (label = profitable after charges), weighted by the size of the result; light L2. */
    fun learn(b: LabBook, t: LabTrade) {
        if (t.feats.size != NF) return
        val y = if (t.pnl > 0) 1.0 else 0.0
        val wt = Math.min(3.0, 0.5 + Math.abs(t.pnl) / Math.max(1.0, t.entry * t.qty * 0.3))
        val lr = 0.15 / Math.sqrt(1.0 + b.trained / 20.0)
        val err = y - predict(b.w, t.feats)
        for (i in 0 until NF) b.w[i] += lr * (wt * err * t.feats[i] - (if (i == 0) 0.0 else 0.01 * b.w[i]))
        b.trained++
        FnoLearn.learnX(b, t)
        retuneGate(b)
    }
    /** Probability cut-off, tuned walk-forward: only switched on when its gain on older trades holds on the newest 30% (needs 30+ scored trades). */
    fun retuneGate(b: LabBook) {
        val scored = b.closed.filter { it.p >= 0 }.take(400).reversed().map { Pair(it.p, it.pnl) }
        val (g, tr, te) = FnoLearn.walkForward(scored)
        b.gate = g; b.gateTrainGain = Fno.r2(tr); b.gateTestGain = Fno.r2(te)
    }
    fun filterView(b: LabBook): Map<String, Any?> {
        val skipped = b.closed.filter { it.gated && it.block.isEmpty() }; val taken = b.closed.filter { it.champ && !it.gated && it.p >= 0 }
        val imp = FEAT_NAMES.indices.drop(1).sortedByDescending { Math.abs(b.w[it]) }.take(3).map { i -> (if (b.w[i] >= 0) "more " else "less ") + FEAT_NAMES[i] + " helps" }
        return linkedMapOf("trained" to b.trained, "gate" to b.gate, "skipped" to skipped.size, "skippedPnl" to Fno.r2(skipped.sumOf { it.pnl }),
            "takenPnl" to Fno.r2(taken.sumOf { it.pnl }), "learned" to if (b.trained >= 10) imp + impX(b) else emptyList<String>(),
            "trainedX" to b.nx, "calibrated" to (b.nx >= 30), "calA" to Fno.r2(b.cal[0]), "calB" to Fno.r2(b.cal[1]), "gateTrainGain" to b.gateTrainGain, "gateTestGain" to b.gateTestGain,
            "riskBlocked" to b.closed.count { it.block.isNotEmpty() }, "riskBlockedPnl" to Fno.r2(b.closed.filter { it.block.isNotEmpty() }.sumOf { it.pnl }))
    }

    /** Strongest extra (non-basic) conditions the extended model has picked up. */
    private fun impX(b: LabBook): List<String> = if (b.nx < 30) emptyList() else (NF until FnoLearn.NX).filter { Math.abs(b.wx[it]) >= 0.15 }
        .sortedByDescending { Math.abs(b.wx[it]) }.take(2).map { i -> (if (b.wx[i] >= 0) "more " else "less ") + FnoLearn.FEATX_NAMES[i] + " helps" }

    private fun ema(c: DoubleArray, n: Int, upto: Int): Double {
        if (upto < 0) return 0.0
        val k = 2.0 / (n + 1); var e = c[0]
        for (i in 1..upto) e = c[i] * k + e * (1 - k)
        return e
    }

    /** Signals that would fire now (minute [m]) for one index, ignoring ones already fired today. Uses completed bars only. */
    /** Challenger thresholds per rule: each variant is paper-traded in parallel; the best one (by learned average) is the champion whose trades are shown as calls. */
    val VARIANTS: Map<String, List<Double>> = mapOf("ORB" to listOf(0.4, 0.6, 0.9), "FIRSTHR" to listOf(0.3, 0.4, 0.6), "TREND" to listOf(0.2, 0.3, 0.5),
        "LATE" to listOf(0.4, 0.6, 0.8), "GAPDN" to listOf(0.3, 0.5, 0.8), "GAPFADE" to listOf(0.5, 0.7, 1.0), "RSI" to listOf(25.0, 30.0, 35.0), "EXPIRY" to listOf(0.0),
        "CPR" to listOf(0.1, 0.2, 0.3), "NR7" to listOf(4.0, 7.0, 7.5), "STREND" to listOf(2.0, 3.0, 4.0), "DARVAS" to listOf(6.0, 9.0, 12.0))
    /** Rules whose parameter is not a % move (never scaled for stocks). */
    val UNSCALED = setOf("RSI", "EXPIRY", "NR7", "STREND", "DARVAS")
    fun vid(strat: String, i: Int) = if ((VARIANTS[strat]?.size ?: 1) <= 1) strat else "$strat#$i"
    fun baseOf(vid: String) = vid.substringBefore('#')
    fun paramOf(vid: String): Double { val b = baseOf(vid); val i = vid.substringAfter('#', "1").toIntOrNull() ?: 1; val l = VARIANTS[b] ?: return 0.0; return l.getOrElse(i) { l[l.size / 2] } }
    fun allVids(sym: String) = STRATS.filter { !((it.id == "GAPDN" || it.id == "EXPIRY") && sym != "NIFTY") }.flatMap { st -> (VARIANTS[st.id] ?: listOf(0.0)).indices.map { vid(st.id, it) } }
    fun paramText(vid: String): String { val b = baseOf(vid); val v = paramOf(vid); return when (b) {
        "ORB" -> "range ≤ ${f1(v)}%"; "RSI" -> "RSI ${v.toInt()}/${(100 - v).toInt()}"; "FIRSTHR" -> "first hour ≥ ${f1(v)}%"; "TREND", "LATE" -> "move ≥ ${f1(v)}%"; "GAPDN", "GAPFADE" -> "gap ≥ ${f1(v)}%"
        "CPR" -> "CPR ≤ ${f1(v)}%"; "NR7" -> if (v >= 7.25) "NR7 + inside" else "NR${v.toInt()}"; "STREND" -> "×${f1(v)} ATR"; "DARVAS" -> "${v.toInt()}-bar box"; else -> "—" } }

    /**
     * Champion variant of [strat] on [sym]: Thompson sampling on the decayed posteriors (seeded by [seed], e.g. the day, so it
     * is reproducible); before any variant has posterior data, the legacy shrunk average; the middle threshold until there is data.
     */
    fun champion(b: LabBook, strat: String, sym: String, seed: Long = 0L): String {
        FnoLearn.thompson(b, strat, sym, seed)?.let { return it }
        val l = VARIANTS[strat] ?: return strat
        if (l.size <= 1) return strat
        var best = vid(strat, l.size / 2); var bv = 0.0
        for (i in l.indices) { val v = b.stats[vid(strat, i) + "|" + sym]?.get("live") ?: continue; if (v.n < 5) continue; val sc = v.sum / (v.n + 5); if (sc > bv) { bv = sc; best = vid(strat, i) } }
        return best
    }

    /** Signals for every variant that would fire now. Variant ids in LabSignal.strat (e.g. "ORB#0"). */
    fun signalsAll(sym: String, bars: IntraBars, m: Int, fired: Set<String>, expiryToday: Boolean = false, scale: Double = 1.0): List<LabSignal> {
        val out = ArrayList<LabSignal>()
        for (v in allVids(sym)) if (!fired.contains(v)) { val b = baseOf(v); val pr = if (UNSCALED.contains(b)) paramOf(v) else paramOf(v) * scale
            for (sg in signals(sym, bars, m, emptySet(), expiryToday, mapOf(b to pr, "_scale" to scale))) if (sg.strat == b) out.add(LabSignal(v, sg.side, sg.why)) }
        return out
    }

    fun signals(sym: String, bars: IntraBars, m: Int, fired: Set<String>, expiryToday: Boolean = false, prm: Map<String, Double> = emptyMap()): List<LabSignal> {
        fun pv(id: String, d: Double) = prm[id] ?: d
        val out = ArrayList<LabSignal>()
        // completed bars: a 5-min bar starting at minute x is complete at x+5
        val done = (0 until bars.n).filter { bars.min[it] + 5 <= m }
        if (done.isEmpty()) return out
        val li = done.last(); val last = bars.c[li]; val open = bars.open; val prev = bars.prevClose
        val gap = (open - prev) / prev * 100; val move = (last - open) / open * 100
        fun f(id: String) = !fired.contains(id)
        if (f("ORB") && m in (9 * 60 + 35)..(11 * 60 + 30)) {
            val orIdx = (0 until bars.n).filter { bars.min[it] < 9 * 60 + 30 }
            if (orIdx.size >= 3) {
                val hi = orIdx.maxOf { bars.h[it] }; val lo = orIdx.minOf { bars.l[it] }
                if (bars.min[li] >= 9 * 60 + 30 && (hi - lo) / open * 100 <= pv("ORB", 0.6)) {
                    if (last > hi) out.add(LabSignal("ORB", "CE", "5-min close ${f0(last)} above the 9:15–9:30 high ${f0(hi)}"))
                    else if (last < lo) out.add(LabSignal("ORB", "PE", "5-min close ${f0(last)} below the 9:15–9:30 low ${f0(lo)}"))
                }
            }
        }
        if (f("FIRSTHR") && m in (10 * 60 + 20)..(10 * 60 + 45)) {
            val fh = (0 until bars.n).filter { bars.min[it] < 10 * 60 + 15 }
            if (fh.size >= 10) {
                val r = (bars.c[fh.last()] - open) / open * 100
                val hiP = (0 until li).maxOf { bars.h[it] }; val loP = (0 until li).minOf { bars.l[it] }
                run {
                    if (r >= pv("FIRSTHR", 0.4) && bars.h[li] >= hiP) out.add(LabSignal("FIRSTHR", "CE", "First hour up ${f1(r)}% and a new intraday high"))
                    else if (r <= -pv("FIRSTHR", 0.4) && bars.l[li] <= loP) out.add(LabSignal("FIRSTHR", "PE", "First hour down ${f1(-r)}% and a new intraday low"))
                }
            }
        }
        if (f("GAPDN") && sym == "NIFTY" && m in (9 * 60 + 20)..(9 * 60 + 35) && gap <= -pv("GAPDN", 0.5)) out.add(LabSignal("GAPDN", "CE", "Opened ${f1(gap)}% below yesterday's close"))
        if (f("GAPFADE") && m in (9 * 60 + 30)..(9 * 60 + 45) && Math.abs(gap) >= pv("GAPFADE", 0.7)) {
            if (gap > 0 && last < open) out.add(LabSignal("GAPFADE", "PE", "Gap up ${f1(gap)}% is failing: now below the open"))
            else if (gap < 0 && last > open) out.add(LabSignal("GAPFADE", "CE", "Gap down ${f1(-gap)}% is failing: now above the open"))
        }
        if (f("TREND") && m in (10 * 60 + 30)..(13 * 60 + 30) && li >= 22) {
            val e = ema(bars.c, 20, li); val e3 = ema(bars.c, 20, li - 3)
            if (move >= pv("TREND", 0.3) && last > e && e > e3) out.add(LabSignal("TREND", "CE", "Up ${f1(move)}% from the open, above a rising 20-bar average"))
            else if (move <= -pv("TREND", 0.3) && last < e && e < e3) out.add(LabSignal("TREND", "PE", "Down ${f1(-move)}% from the open, below a falling 20-bar average"))
        }
        if (f("LATE") && m in (13 * 60)..(13 * 60 + 45)) {
            if (move >= pv("LATE", 0.6)) out.add(LabSignal("LATE", "CE", "Up ${f1(move)}% from the open by afternoon"))
            else if (move <= -pv("LATE", 0.6)) out.add(LabSignal("LATE", "PE", "Down ${f1(-move)}% from the open by afternoon"))
        }
        if (f("RSI") && m in (9 * 60 + 45)..(14 * 60) && li >= 16) {
            val lo = pv("RSI", 30.0); val r0 = rsi(bars.c, 14, li); val r1 = rsi(bars.c, 14, li - 1)
            if (r1 < lo && r0 >= lo) out.add(LabSignal("RSI", "CE", "RSI turned up from ${r1.toInt()} to ${r0.toInt()} (oversold bounce)"))
            else if (r1 > 100 - lo && r0 <= 100 - lo) out.add(LabSignal("RSI", "PE", "RSI turned down from ${r1.toInt()} to ${r0.toInt()} (overbought fade)"))
        }
        if (f("EXPIRY") && expiryToday && sym == "NIFTY" && m in (13 * 60 + 30)..(14 * 60 + 30) && li >= 2) {
            val hiP = (0 until li).maxOf { bars.h[it] }; val loP = (0 until li).minOf { bars.l[it] }
            if (last > hiP) out.add(LabSignal("EXPIRY", "CE", "Expiry day: 5-min close above the day's high ${f0(hiP)}"))
            else if (last < loP) out.add(LabSignal("EXPIRY", "PE", "Expiry day: 5-min close below the day's low ${f0(loP)}"))
        }
        val skip = fired + (if (prm.isEmpty()) emptySet() else STRATS.map { it.id }.filter { !prm.containsKey(it) })
        out.addAll(FnoLearn.newSignals(bars, m, li, skip, { id, d -> pv(id, d) }, prm["_scale"] ?: 1.0))
        return out
    }

    private fun f0(x: Double) = String.format(java.util.Locale.US, "%.0f", x)
    private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

    fun atm(c: FnoChain): Int = c.strikes.minByOrNull { Math.abs(it - c.spot) } ?: 0

    /** Stop/target learned so far for strategy [key] (shrunk mean; default until 8 trades). */
    fun bestExit(b: LabBook, key: String): Pair<Double, Double> { val r = bestRule(b, key); return Pair(r.stop, r.tgt) }
    /** Exit rule for the next trade of [key]: Bayesian decayed scoring over the full grid (incl. trailing and time stops); legacy stats before that. */
    fun bestRule(b: LabBook, key: String): ExitRule = FnoLearn.bestExit(b, key) ?: legacyExit(b, key).let { ExitRule(it.first, it.second) }
    private fun legacyExit(b: LabBook, key: String): Pair<Double, Double> {
        val st = b.stats[key] ?: return Pair(DEF_STOP, DEF_TGT)
        val n = st[vkey(DEF_STOP, DEF_TGT)]?.n ?: 0
        if (n < 8) return Pair(DEF_STOP, DEF_TGT)
        var best = Pair(DEF_STOP, DEF_TGT); var bv = Double.NEGATIVE_INFINITY
        for (s in STOPS) for (t in TGTS) { val v = st[vkey(s, t)] ?: continue; val sc = v.sum / (v.n + 5); if (sc > bv) { bv = sc; best = Pair(s, t) } }
        return best
    }

    /** Replay one stop/target on a bid path: exit price. */
    fun replay(entry: Double, bids: List<Double>, stopPct: Double, tgtPct: Double): Pair<Double, String> {
        val sl = entry * (1 - stopPct / 100); val tg = if (tgtPct > 0) entry * (1 + tgtPct / 100) else Double.MAX_VALUE
        for (x in bids) { if (x <= sl) return Pair(x, "STOP"); if (x >= tg) return Pair(x, "TARGET") }
        return Pair(bids.lastOrNull() ?: entry, "TIME")
    }

    fun net(entry: Double, exit: Double, qty: Int): Double =
        (exit - entry) * qty - Fno.charges(true, entry, qty) - Fno.charges(false, exit, qty)

    fun key(t: LabTrade) = t.strat + "|" + t.sym

    /** Close a trade: settle it on its own stop/target, then teach every grid cell from the same premium path. */
    fun close(b: LabBook, t: LabTrade) {
        val path = if (t.bids.isEmpty()) listOf(t.entry) else t.bids
        val (px, why0) = FnoLearn.replay(t.entry, path, t.mins, t.rule)
        val why = if (why0 == "TRAIL") "STOP" else why0
        t.exitPx = Fno.r2(px); t.status = why; t.pnl = Fno.r2(net(t.entry, px, t.qty))
        FnoLearn.teach(b, t, ArrayList(path))
        val st = b.stats.getOrPut(key(t)) { LinkedHashMap() }
        for (s in STOPS) for (g in TGTS) st.getOrPut(vkey(s, g)) { VarStat() }.add(net(t.entry, replay(t.entry, path, s, g).first, t.qty))
        st.getOrPut("live") { VarStat() }.add(t.pnl)
        // the path has taught every exit pair; keep only the last price so the saved book stays small
        if (t.bids.size > 1) { val lastBid = t.bids.last(); t.bids.clear(); t.bids.add(lastBid) }
        if (t.mins.size > 1) { val lm = t.mins.last(); t.mins.clear(); t.mins.add(lm) }
        b.open.remove(t); b.closed.add(0, t)
        while (b.closed.size > MAX_CLOSED) b.closed.removeAt(b.closed.size - 1)
        learn(b, t)
    }

    /** "WORKING" (n>=20, mean>0, t>=1.5), "FAILING" (n>=20, mean<0), "UNPROVEN" (n>=20 otherwise), else "LEARNING". */
    fun status(b: LabBook, key: String): String {
        val v = b.stats[key]?.get("live") ?: return "LEARNING"
        if (v.n < 20) return "LEARNING"
        val pd = b.post[key]?.get("live")
        val proven = pd == null || pd.n < FnoLearn.MIN_EVID || FnoLearn.post(pd).lo > 0     // decayed posterior must agree (90% interval above zero)
        return if (v.mean > 0 && v.t >= 1.5 && proven) "WORKING" else if (v.mean < 0) "FAILING" else "UNPROVEN"
    }

    /** Lots for a call: risk 2% of capital at the stop, at most 25% of capital in premium; 1 lot allowed if its risk <= 5%. */
    fun lots(capital: Double, entry: Double, lot: Int, stopPct: Double): Int {
        if (capital <= 0 || entry <= 0 || lot <= 0) return 0
        val risk1 = entry * lot * stopPct / 100; val cost1 = entry * lot
        var n = Math.floor(capital * 0.02 / risk1).toInt()
        n = Math.min(n, Math.floor(capital * 0.25 / cost1).toInt())
        if (n < 1 && risk1 <= capital * 0.05 && cost1 <= capital * 0.25) n = 1
        return Math.max(0, n)
    }

    /** Live check of the trade's own stop/target on the newest bid. Returns a one-off event title when it first hits. */
    fun liveCheck(t: LabTrade, bid: Double, m: Int = -1): String? {
        if (t.liveHit.isNotEmpty() || bid <= 0) return null
        if (bid <= t.entry * (1 - t.stopPct / 100)) { t.liveHit = "STOP"; return "STOP" }
        if (t.tgtPct > 0 && bid >= t.entry * (1 + t.tgtPct / 100)) { t.liveHit = "TARGET"; return "TARGET" }
        if (t.trail > 0) { val pk = Math.max(bid, t.bids.maxOrNull() ?: 0.0); if (pk >= t.entry * (1 + t.arm / 100) && bid <= pk * (1 - t.trail / 100)) { t.liveHit = "STOP"; return "TRAIL" } }
        if (t.tmin > 0 && m >= t.tmin) { t.liveHit = "TIME"; return "TIME" }
        return null
    }

    fun view(b: LabBook, day: Int, capital: Double, chains: Map<String, FnoChain>): Map<String, Any?> {
        fun tv(t: LabTrade): Map<String, Any?> {
            val bid = t.bids.lastOrNull() ?: t.entry
            val k = key(t)
            return linkedMapOf("id" to t.id, "strat" to t.strat, "stratName" to stratName(t.strat), "sym" to t.sym, "expiry" to t.expiry, "strike" to t.strike,
                "side" to t.side, "entry" to t.entry, "at" to t.at, "why" to t.why, "stop" to Fno.r2(t.entry * (1 - t.stopPct / 100)),
                "target" to if (t.tgtPct > 0) Fno.r2(t.entry * (1 + t.tgtPct / 100)) else null, "stopPct" to t.stopPct, "tgtPct" to t.tgtPct,
                "lot" to t.lot, "lots" to lots(capital, t.entry, t.lot, t.stopPct), "now" to bid, "status" to t.status, "liveHit" to t.liveHit,
                "exitPx" to t.exitPx, "trailPct" to t.trail, "trailAfter" to t.arm, "exitBy" to t.tmin, "pnl" to if (t.status == "OPEN") Fno.r2(net(t.entry, bid, t.qty)) else t.pnl, "spot" to t.spot, "stratStatus" to status(b, k), "day" to t.day)
        }
        val today = (b.open.filter { it.day == day } + b.closed.filter { it.day == day }).filter { it.champ && !it.gated }.sortedByDescending { it.at }
        val shadowToday = (b.open.count { it.day == day && !it.champ } + b.closed.count { it.day == day && !it.champ })
        val strats = ArrayList<Map<String, Any?>>()
        val lessons = ArrayList<String>()
        for (s in STRATS) for (sym in INDEX.keys) {
            if ((s.id == "GAPDN" || s.id == "EXPIRY") && sym != "NIFTY") continue
            val ch = champion(b, s.id, sym, day.toLong()); val k = "$ch|$sym"
            val v = b.stats[k]?.get("live") ?: VarStat(); val br = bestRule(b, k); val be = Pair(br.stop, br.tgt)
            val bv = b.stats[k]?.get(vkey(be.first, be.second))
            val vars = (VARIANTS[s.id] ?: listOf(0.0)).indices.map { i -> val vk = vid(s.id, i); val vs = b.stats["$vk|$sym"]?.get("live") ?: VarStat()
                linkedMapOf<String, Any?>("param" to paramText(vk), "n" to vs.n, "avg" to Fno.r2(vs.mean), "champ" to (vk == ch)).apply { putAll(FnoLearn.postView(b.post["$vk|$sym"]?.get("live"))) } }
            val tot = vars.sumOf { (it["n"] as Int) }
            strats.add(linkedMapOf("id" to s.id, "name" to s.name, "rule" to s.rule, "sym" to sym, "n" to v.n, "wins" to v.wins, "avg" to Fno.r2(v.mean),
                "total" to Fno.r2(v.sum), "t" to Fno.r2(v.t), "status" to status(b, k), "stopPct" to be.first, "tgtPct" to be.second,
                "bestAvg" to Fno.r2(bv?.mean ?: 0.0), "bestN" to (bv?.n ?: 0), "champion" to paramText(ch), "variants" to vars, "tested" to tot, "exitText" to br.text(), "trailPct" to br.trail, "trailAfter" to br.arm, "exitBy" to br.tmin,
                "post" to FnoLearn.postView(b.post[k]?.get("live"))))
            if (v.n >= 5) { val pv = FnoLearn.post(b.post[k]?.get("live"))
                lessons.add("${s.name} on $sym: best so far is ${paramText(ch)} with ${br.text()} (avg ₹${Fno.r2(v.mean)}/lot over ${v.n} trades" +
                    (if (pv.n >= FnoLearn.MIN_EVID) "; expected ₹${Math.round(pv.exp)} (₹${Math.round(pv.expLo)} to ₹${Math.round(pv.expHi)})" + (if (pv.lo <= 0 && pv.hi >= 0) ", not enough evidence yet" else "") else "") + ")") }
        }
        val all = VarStat(); b.closed.filter { it.champ && !it.gated }.forEach { all.add(it.pnl) }
        return linkedMapOf("ok" to true, "today" to today.map { tv(it) }, "filter" to filterView(b), "recent" to b.closed.filter { it.day != day && it.champ && !it.gated }.take(20).map { tv(it) },
            "strategies" to strats, "lessons" to (FnoLearn.lessons(b) + lessons).take(8), "shadowToday" to shadowToday, "record" to linkedMapOf("n" to all.n, "wins" to all.wins, "total" to Fno.r2(all.sum), "avg" to Fno.r2(all.mean)))
    }

    /** Plain-English read of the index right now, with the levels that would trigger the next call. */
    fun marketView(sym: String, bars: IntraBars?, spot: Double?, m: Int): Map<String, Any?>? {
        if (bars == null || bars.n == 0) return null
        val last = spot ?: bars.last; val open = bars.open; val prev = bars.prevClose
        val orIdx = (0 until bars.n).filter { bars.min[it] < 9 * 60 + 30 }
        val orh = orIdx.maxOfOrNull { bars.h[it] }; val orl = orIdx.minOfOrNull { bars.l[it] }
        val dh = (0 until bars.n).maxOf { bars.h[it] }; val dl = (0 until bars.n).minOf { bars.l[it] }
        val e = ema(bars.c, 20, bars.n - 1)
        val mv = (last - open) / open * 100; val day = (last - prev) / prev * 100
        val up = last > open && last > e; val dn = last < open && last < e
        val bias = if (up && mv >= 0.15) "BULLISH" else if (dn && mv <= -0.15) "BEARISH" else "SIDEWAYS"
        val why = ArrayList<String>()
        why.add((if (mv >= 0) "Up " else "Down ") + f1(Math.abs(mv)) + "% from today's open (" + f1(day) + "% on the day)")
        why.add(if (last > e) "Above its 20-bar average" else "Below its 20-bar average")
        if (orh != null && orl != null) why.add(if (last > orh) "Broke above the 9:15–9:30 high" else if (last < orl) "Broke below the 9:15–9:30 low" else "Still inside the 9:15–9:30 range")
        val plan = when {
            m > 14 * 60 + 30 -> "No new trades after 2:30 pm. Exit open ones by 3:15 pm."
            bias == "BULLISH" -> "Calls only. Buy a call on a 5-min close above " + f0(Math.max(dh, orh ?: dh)) + "; wrong if it falls below " + f0(Math.max(open, orl ?: open)) + "."
            bias == "BEARISH" -> "Puts only. Buy a put on a 5-min close below " + f0(Math.min(dl, orl ?: dl)) + "; wrong if it rises above " + f0(Math.min(open, orh ?: open)) + "."
            else -> "No clear side: wait. A call above " + f0(orh ?: dh) + " or a put below " + f0(orl ?: dl) + " (5-min close)."
        }
        return linkedMapOf("sym" to sym, "bias" to bias, "last" to last, "open" to open, "prevClose" to prev, "dayHigh" to dh, "dayLow" to dl,
            "orHigh" to orh, "orLow" to orl, "ema20" to Fno.r2(e), "why" to why, "plan" to plan)
    }

    /* ---------- live ticker (NSE allIndices) ---------- */
    val TICKER = listOf("NIFTY 50" to "Nifty 50", "NIFTY BANK" to "Bank Nifty", "NIFTY FINANCIAL SERVICES" to "Fin Nifty", "NIFTY MIDCAP SELECT" to "Midcap Nifty", "INDIA VIX" to "India VIX")
    fun parseIndices(text: String?): List<Map<String, Any?>> = try {
        val rows = (Json.parseObject(text ?: "")?.get("data") as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val by = HashMap<String, Map<*, *>>(); for (r in rows) (r["index"] as? String)?.let { by[it.uppercase().trim()] = r }
        TICKER.mapNotNull { (k, lab) ->
            val r = by[k] ?: return@mapNotNull null
            val last = num(r["last"]) ?: return@mapNotNull null
            linkedMapOf("name" to lab, "last" to last, "chg" to (num(r["variation"]) ?: 0.0), "pct" to (num(r["percentChange"]) ?: 0.0),
                "high" to num(r["high"]), "low" to num(r["low"]))
        }
    } catch (t: Throwable) { emptyList() }
}
