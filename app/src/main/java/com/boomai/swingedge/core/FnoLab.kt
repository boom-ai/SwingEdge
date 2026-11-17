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
}

class LabSignal(val strat: String, val side: String, val why: String)

class LabTrade(var id: String = "", var strat: String = "", var sym: String = "", var expiry: String = "", var strike: Int = 0, var side: String = "",
               var lot: Int = 0, var entry: Double = 0.0, var at: Long = 0, var day: Int = 0, var why: String = "", var stopPct: Double = 30.0,
               var tgtPct: Double = 50.0, var bids: ArrayList<Double> = ArrayList(), var status: String = "OPEN", var exitPx: Double = 0.0,
               var pnl: Double = 0.0, var spot: Double = 0.0, var liveHit: String = "") {
    val qty get() = lot
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "strat" to strat, "sym" to sym, "expiry" to expiry, "strike" to strike, "side" to side,
        "lot" to lot, "entry" to entry, "at" to at, "day" to day, "why" to why, "stopPct" to stopPct, "tgtPct" to tgtPct, "bids" to bids.toList(),
        "status" to status, "exitPx" to exitPx, "pnl" to pnl, "spot" to spot, "liveHit" to liveHit)
    companion object {
        fun from(m: Map<String, Any?>) = LabTrade(m.str("id"), m.str("strat"), m.str("sym"), m.str("expiry"), m.int("strike"), m.str("side"), m.int("lot"),
            m.num("entry", 0.0), m.long("at"), m.int("day"), m.str("why"), m.num("stopPct", 30.0), m.num("tgtPct", 50.0),
            ArrayList(m.list("bids").mapNotNull { (it as? Number)?.toDouble() }.take(FnoLab.MAX_PATH)), m.str("status").ifEmpty { "OPEN" },
            m.num("exitPx", 0.0), m.num("pnl", 0.0), m.num("spot", 0.0), m.str("liveHit"))
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
              val stats: MutableMap<String, MutableMap<String, VarStat>> = LinkedHashMap(), val fired: MutableMap<String, Int> = HashMap(), var seq: Int = 0) {
    fun toMap(): Map<String, Any?> = linkedMapOf("seq" to seq, "open" to open.map { it.toMap() }, "closed" to closed.map { it.toMap() },
        "stats" to stats.mapValues { (_, v) -> v.mapValues { it.value.toList() } }, "fired" to fired)
    companion object {
        fun from(m: Map<String, Any?>?): LabBook {
            val b = LabBook()
            if (m == null) return b
            b.seq = m.int("seq")
            @Suppress("UNCHECKED_CAST") for (x in m.list("open")) (x as? Map<String, Any?>)?.let { b.open.add(LabTrade.from(it)) }
            @Suppress("UNCHECKED_CAST") for (x in m.list("closed")) (x as? Map<String, Any?>)?.let { b.closed.add(LabTrade.from(it)) }
            (m["stats"] as? Map<*, *>)?.forEach { (k, v) -> val mm = LinkedHashMap<String, VarStat>(); (v as? Map<*, *>)?.forEach { (vk, vv) -> mm[vk.toString()] = VarStat.from(vv) }; b.stats[k.toString()] = mm }
            (m["fired"] as? Map<*, *>)?.forEach { (k, v) -> (v as? Number)?.let { b.fired[k.toString()] = it.toInt() } }
            return b
        }
    }
}

object FnoLab {
    const val MAX_PATH = 420
    const val MAX_CLOSED = 300
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
        Strat("EXPIRY", "Expiry-day breakout", "Nifty expiry day, 13:30–14:30: a 5-min close beyond the day's range so far buys in that direction."),
    )
    fun stratName(id: String) = STRATS.firstOrNull { it.id == id }?.name ?: id
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

    private fun ema(c: DoubleArray, n: Int, upto: Int): Double {
        if (upto < 0) return 0.0
        val k = 2.0 / (n + 1); var e = c[0]
        for (i in 1..upto) e = c[i] * k + e * (1 - k)
        return e
    }

    /** Signals that would fire now (minute [m]) for one index, ignoring ones already fired today. Uses completed bars only. */
    fun signals(sym: String, bars: IntraBars, m: Int, fired: Set<String>, expiryToday: Boolean = false): List<LabSignal> {
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
                if (bars.min[li] >= 9 * 60 + 30 && (hi - lo) / open * 100 <= 0.6) {
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
                    if (r >= 0.4 && bars.h[li] >= hiP) out.add(LabSignal("FIRSTHR", "CE", "First hour up ${f1(r)}% and a new intraday high"))
                    else if (r <= -0.4 && bars.l[li] <= loP) out.add(LabSignal("FIRSTHR", "PE", "First hour down ${f1(-r)}% and a new intraday low"))
                }
            }
        }
        if (f("GAPDN") && sym == "NIFTY" && m in (9 * 60 + 20)..(9 * 60 + 35) && gap <= -0.5) out.add(LabSignal("GAPDN", "CE", "Opened ${f1(gap)}% below yesterday's close"))
        if (f("GAPFADE") && m in (9 * 60 + 30)..(9 * 60 + 45) && Math.abs(gap) >= 0.7) {
            if (gap > 0 && last < open) out.add(LabSignal("GAPFADE", "PE", "Gap up ${f1(gap)}% is failing: now below the open"))
            else if (gap < 0 && last > open) out.add(LabSignal("GAPFADE", "CE", "Gap down ${f1(-gap)}% is failing: now above the open"))
        }
        if (f("TREND") && m in (10 * 60 + 30)..(13 * 60 + 30) && li >= 22) {
            val e = ema(bars.c, 20, li); val e3 = ema(bars.c, 20, li - 3)
            if (move >= 0.3 && last > e && e > e3) out.add(LabSignal("TREND", "CE", "Up ${f1(move)}% from the open, above a rising 20-bar average"))
            else if (move <= -0.3 && last < e && e < e3) out.add(LabSignal("TREND", "PE", "Down ${f1(-move)}% from the open, below a falling 20-bar average"))
        }
        if (f("LATE") && m in (13 * 60)..(13 * 60 + 45)) {
            if (move >= 0.6) out.add(LabSignal("LATE", "CE", "Up ${f1(move)}% from the open by afternoon"))
            else if (move <= -0.6) out.add(LabSignal("LATE", "PE", "Down ${f1(-move)}% from the open by afternoon"))
        }
        if (f("EXPIRY") && expiryToday && sym == "NIFTY" && m in (13 * 60 + 30)..(14 * 60 + 30) && li >= 2) {
            val hiP = (0 until li).maxOf { bars.h[it] }; val loP = (0 until li).minOf { bars.l[it] }
            if (last > hiP) out.add(LabSignal("EXPIRY", "CE", "Expiry day: 5-min close above the day's high ${f0(hiP)}"))
            else if (last < loP) out.add(LabSignal("EXPIRY", "PE", "Expiry day: 5-min close below the day's low ${f0(loP)}"))
        }
        return out
    }

    private fun f0(x: Double) = String.format(java.util.Locale.US, "%.0f", x)
    private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

    fun atm(c: FnoChain): Int = c.strikes.minByOrNull { Math.abs(it - c.spot) } ?: 0

    /** Stop/target learned so far for strategy [key] (shrunk mean; default until 8 trades). */
    fun bestExit(b: LabBook, key: String): Pair<Double, Double> {
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
        val (px, why) = replay(t.entry, path, t.stopPct, t.tgtPct)
        t.exitPx = Fno.r2(px); t.status = why; t.pnl = Fno.r2(net(t.entry, px, t.qty))
        val st = b.stats.getOrPut(key(t)) { LinkedHashMap() }
        for (s in STOPS) for (g in TGTS) st.getOrPut(vkey(s, g)) { VarStat() }.add(net(t.entry, replay(t.entry, path, s, g).first, t.qty))
        st.getOrPut("live") { VarStat() }.add(t.pnl)
        b.open.remove(t); b.closed.add(0, t)
        while (b.closed.size > MAX_CLOSED) b.closed.removeAt(b.closed.size - 1)
    }

    /** "WORKING" (n>=20, mean>0, t>=1.5), "FAILING" (n>=20, mean<0), "UNPROVEN" (n>=20 otherwise), else "LEARNING". */
    fun status(b: LabBook, key: String): String {
        val v = b.stats[key]?.get("live") ?: return "LEARNING"
        if (v.n < 20) return "LEARNING"
        return if (v.mean > 0 && v.t >= 1.5) "WORKING" else if (v.mean < 0) "FAILING" else "UNPROVEN"
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
    fun liveCheck(t: LabTrade, bid: Double): String? {
        if (t.liveHit.isNotEmpty() || bid <= 0) return null
        if (bid <= t.entry * (1 - t.stopPct / 100)) { t.liveHit = "STOP"; return "STOP" }
        if (t.tgtPct > 0 && bid >= t.entry * (1 + t.tgtPct / 100)) { t.liveHit = "TARGET"; return "TARGET" }
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
                "exitPx" to t.exitPx, "pnl" to if (t.status == "OPEN") Fno.r2(net(t.entry, bid, t.qty)) else t.pnl, "spot" to t.spot, "stratStatus" to status(b, k), "day" to t.day)
        }
        val today = (b.open.filter { it.day == day } + b.closed.filter { it.day == day }).sortedByDescending { it.at }
        val strats = ArrayList<Map<String, Any?>>()
        for (s in STRATS) for (sym in INDEX.keys) {
            if ((s.id == "GAPDN" || s.id == "EXPIRY") && sym != "NIFTY") continue
            val k = s.id + "|" + sym; val v = b.stats[k]?.get("live") ?: VarStat(); val be = bestExit(b, k)
            val bv = b.stats[k]?.get(vkey(be.first, be.second))
            strats.add(linkedMapOf("id" to s.id, "name" to s.name, "rule" to s.rule, "sym" to sym, "n" to v.n, "wins" to v.wins, "avg" to Fno.r2(v.mean),
                "total" to Fno.r2(v.sum), "t" to Fno.r2(v.t), "status" to status(b, k), "stopPct" to be.first, "tgtPct" to be.second,
                "bestAvg" to Fno.r2(bv?.mean ?: 0.0), "bestN" to (bv?.n ?: 0)))
        }
        val all = VarStat(); b.closed.forEach { all.add(it.pnl) }
        return linkedMapOf("ok" to true, "today" to today.map { tv(it) }, "recent" to b.closed.filter { it.day != day }.take(20).map { tv(it) },
            "strategies" to strats, "record" to linkedMapOf("n" to all.n, "wins" to all.wins, "total" to Fno.r2(all.sum), "avg" to Fno.r2(all.mean)))
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
