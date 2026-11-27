package com.boomai.swingedge.core

/**
 * One place for live prices (equities, indices, option/future contracts) fed by several sources.
 *
 *  - Arbitration: m.Stock (when connected) > Groww > NSE > Yahoo. A lower-ranked sample is only used when the
 *    higher-ranked source has not delivered for [ARB_FRESH_MS]; it is still remembered so it can confirm outliers.
 *  - Monotonic: a sample whose timestamp is older than the quote already held never replaces it.
 *  - Outliers: a tick that jumps more than the kind's limit from the last good price (or is outside the circuit band)
 *    is held back until a second sample (later poll or another source) agrees within 1%.
 *  - Change: previous close is fixed by the first source that gives one for that symbol on that IST day, so the
 *    day change no longer flips between sources.
 *  - Staleness is market-hours aware: outside the session nothing is "stale", the last price is simply "closed".
 *  - Health: per source last ok, failures, latency and how many ticks were rejected (Diagnostics → priceHealth).
 * Pure Kotlin, thread safe, never throws.
 */
class PriceHub(private val clock: () -> Long) {

    /** One quote. [ts] is the exchange time when the source gives one ([hasTs]), else the fetch time; [at] is the fetch time. */
    data class PQ(
        val key: String, val kind: String, val ltp: Double, val src: String, val ts: Long, val at: Long, val hasTs: Boolean = false,
        val prevClose: Double = 0.0, val chg: Double = Double.NaN, val pct: Double = Double.NaN,
        val open: Double = 0.0, val high: Double = 0.0, val low: Double = 0.0,
        val bid: Double = 0.0, val ask: Double = 0.0, val depth: Boolean = false, val oi: Double = 0.0, val vol: Double = 0.0,
        val upper: Double = 0.0, val lower: Double = 0.0,
    )

    class Health {
        var okAt = 0L; var failAt = 0L; var oks = 0; var fails = 0; var streak = 0
        var latLast = 0L; var latAvg = 0.0
        var accepted = 0; var shadowed = 0; var outliers = 0; var old = 0; var bad = 0; var lastError = ""
    }

    private class Slot {
        var good: PQ? = null
        var pending: PQ? = null
        var refDay = -1; var refPrev = 0.0; var refSrc = ""
        val bySrc = HashMap<String, PQ>()
    }

    private val lock = Any()
    private val slots = HashMap<String, Slot>()
    private val health = LinkedHashMap<String, Health>()
    private val recentSrc = HashMap<String, Pair<String, Long>>()   // kind -> (src, at) of the last accepted quote

    fun now(): Long = try { clock() } catch (t: Throwable) { System.currentTimeMillis() }

    // ------------------------------------------------------------------------------------------- health

    private fun h(src: String): Health = health.getOrPut(src) { Health() }
    /** Call before a source request; pass the result to [ok] / [fail]. */
    fun begin(): Long = now()
    fun ok(src: String, t0: Long) = synchronized(lock) {
        val t = now(); val x = h(src); x.okAt = t; x.oks++; x.streak = 0
        x.latLast = Math.max(0L, t - t0); x.latAvg = if (x.oks == 1) x.latLast.toDouble() else x.latAvg * 0.8 + x.latLast * 0.2
    }
    fun fail(src: String, t0: Long, why: String) = synchronized(lock) {
        val t = now(); val x = h(src); x.failAt = t; x.fails++; x.streak++; x.latLast = Math.max(0L, t - t0); x.lastError = why.take(120)
    }
    /** Records the outcome of one batch call: ok when it returned anything usable. */
    fun result(src: String, t0: Long, got: Int, why: String = "no prices") { if (got > 0) ok(src, t0) else fail(src, t0, why) }

    // ------------------------------------------------------------------------------------------- feed

    /** Offers a sample. Returns true when it became the quote for its key. */
    fun offer(q0: PQ): Boolean {
        try {
            synchronized(lock) {
                val now = now()
                val hs = h(q0.src)
                if (!(q0.ltp > 0) || q0.ltp.isInfinite() || q0.key.isEmpty()) { hs.bad++; return false }
                // an exchange time in the future is clock skew: clamp it to the fetch time
                val q = if (q0.hasTs && q0.ts > q0.at + 5_000L) q0.copy(ts = q0.at) else if (q0.ts <= 0) q0.copy(ts = q0.at, hasTs = false) else q0
                val s = slots.getOrPut(q.key) { Slot() }
                val day = Clock.epochDay(now)
                val prevSame = s.bySrc[q.src]
                if (prevSame != null && newer(prevSame, q)) { hs.old++; return false }      // out-of-order reply from the same source
                s.bySrc[q.src] = q
                val g = s.good
                // arbitration: a better-ranked source that is still delivering keeps the quote
                if (g != null && rank(g.src) < rank(q.src) && now - g.at <= ARB_FRESH_MS) { hs.shadowed++; return false }
                // monotonic: never replace a newer quote with an older one
                if (g != null && newer(g, q)) { hs.old++; return false }
                // circuit band given by the source
                if (q.upper > 0 && q.lower > 0 && (q.ltp > q.upper * 1.001 || q.ltp < q.lower * 0.999)) { hs.outliers++; return false }
                if (suspicious(s, g, q, now, day) && !confirmed(s, q, now)) { s.pending = q; hs.outliers++; return false }
                s.pending = null
                // previous close: one source per symbol per IST day
                if (s.refDay != day || !(s.refPrev > 0)) {
                    val pc = prevOf(q)
                    if (pc > 0) { s.refPrev = pc; s.refSrc = q.src; s.refDay = day } else if (s.refDay != day) { s.refPrev = 0.0; s.refSrc = "" }
                }
                val chg: Double; val pct: Double
                if (s.refPrev > 0) { chg = q.ltp - s.refPrev; pct = chg / s.refPrev * 100 }
                else { chg = if (q.chg.isNaN()) 0.0 else q.chg; pct = if (q.pct.isNaN()) 0.0 else q.pct }
                s.good = q.copy(prevClose = s.refPrev, chg = r2(chg), pct = r2(pct))
                hs.accepted++
                recentSrc[q.kind] = Pair(q.src, now)
                return true
            }
        } catch (t: Throwable) { return false }
    }

    fun offerAll(qs: Collection<PQ>): Int { var n = 0; for (q in qs) if (offer(q)) n++; return n }

    private fun newer(a: PQ, b: PQ): Boolean =
        if (a.hasTs && b.hasTs) a.ts > b.ts else a.at > b.at

    private fun prevOf(q: PQ): Double = when {
        q.prevClose > 0 -> q.prevClose
        !q.chg.isNaN() && q.ltp - q.chg > 0 && q.chg != 0.0 -> q.ltp - q.chg
        !q.pct.isNaN() && q.pct > -99.0 && q.pct != 0.0 -> q.ltp / (1 + q.pct / 100)
        else -> 0.0
    }

    private fun suspicious(s: Slot, g: PQ?, q: PQ, now: Long, day: Int): Boolean {
        val lim = JUMP[q.kind] ?: 0.08
        if (g != null && now - g.at <= REF_MS && Clock.epochDay(g.at) == day) {
            val d = Math.abs(q.ltp - g.ltp)
            if (d > g.ltp * lim && d > (ABS_MIN[q.kind] ?: 0.0)) return true
        }
        // implausible move against the day's previous close (cash equities only; no circuit is wider than 20%)
        if (q.kind == EQ && s.refDay == day && s.refPrev > 0 && Math.abs(q.ltp / s.refPrev - 1) > 0.2) return true
        return false
    }

    /** A held-back tick is accepted when another sample (a later poll, or another source) agrees within 1%. */
    private fun confirmed(s: Slot, q: PQ, now: Long): Boolean {
        val p = s.pending
        if (p != null && now - p.at <= CONFIRM_MS && close(p.ltp, q.ltp) && (p.src != q.src || q.at - p.at >= 1_000L || (q.hasTs && p.hasTs && q.ts > p.ts))) return true
        for ((src, o) in s.bySrc) if (src != q.src && now - o.at <= CONFIRM_MS && close(o.ltp, q.ltp)) return true
        return false
    }
    private fun close(a: Double, b: Double) = a > 0 && Math.abs(a / b - 1) <= 0.01

    // ------------------------------------------------------------------------------------------- read

    fun get(key: String): PQ? = synchronized(lock) { slots[key]?.good }
    /** The quote when fetched within [maxAgeMs]. */
    fun fresh(key: String, maxAgeMs: Long): PQ? = get(key)?.takeIf { now() - it.at <= maxAgeMs }
    /** A raw sample one source gave (for diagnostics and real depth). */
    fun fromSource(key: String, src: String): PQ? = synchronized(lock) { slots[key]?.bySrc?.get(src) }
    fun clear() = synchronized(lock) { slots.clear(); recentSrc.clear() }

    /** live | delayed | stale | closed. */
    fun state(q: PQ?, now: Long = now()): String {
        if (q == null) return "none"
        if (!Clock.isMarketOpen(now)) return "closed"
        val fetchAge = now - q.at
        if (fetchAge > FETCH_STALE_MS) return "stale"
        val age = if (q.hasTs) now - q.ts else fetchAge
        val opt = q.kind == OPT || q.kind == FUT
        return when {
            age <= (if (opt) 30_000L else 15_000L) -> "live"
            age <= (if (opt) 300_000L else 120_000L) -> "delayed"
            else -> "stale"
        }
    }

    /** UI map for one quote (keeps the old liveQuotes field names). */
    fun view(q: PQ, now: Long = now()): Map<String, Any?> = linkedMapOf("ltp" to q.ltp, "chg" to q.chg, "pct" to q.pct, "ts" to q.ts,
        "src" to q.src, "state" to state(q, now), "prevClose" to (if (q.prevClose > 0) q.prevClose else null))

    /** Source currently feeding quotes of [kind] (last accepted within 2 min), or "". */
    fun inUse(kind: String): String = synchronized(lock) { recentSrc[kind]?.takeIf { now() - it.second < 120_000L }?.first ?: "" }

    fun health(): Map<String, Any?> = synchronized(lock) {
        val now = now()
        val src = LinkedHashMap<String, Any?>()
        for (name in RANK) {
            val x = health[name] ?: continue
            src[name] = linkedMapOf("label" to label(name), "lastOk" to x.okAt, "lastFail" to x.failAt, "ok" to x.oks, "errors" to x.fails, "streak" to x.streak,
                "latencyMs" to x.latLast, "latencyAvgMs" to Math.round(x.latAvg), "accepted" to x.accepted, "shadowed" to x.shadowed,
                "glitchesRejected" to x.outliers + x.old + x.bad, "outliers" to x.outliers, "outOfOrder" to x.old, "bad" to x.bad,
                "state" to srcState(x, now), "lastError" to x.lastError)
        }
        linkedMapOf("at" to now, "marketOpen" to Clock.isMarketOpen(now), "inUse" to linkedMapOf(EQ to inUseNl(EQ, now), IDX to inUseNl(IDX, now), OPT to inUseNl(OPT, now)),
            "sources" to src, "symbols" to slots.size)
    }
    private fun inUseNl(kind: String, now: Long) = recentSrc[kind]?.takeIf { now - it.second < 120_000L }?.first ?: ""

    /** One line for Settings: the source in use and whether it is healthy. */
    fun summary(): Map<String, Any?> = synchronized(lock) {
        val now = now()
        val use = listOf(EQ, OPT, IDX).firstNotNullOfOrNull { k -> recentSrc[k]?.takeIf { now - it.second < 120_000L }?.first } ?: ""
        val x = if (use.isEmpty()) null else health[use]
        val st = if (x == null) "idle" else srcState(x, now)
        val rej = health.values.sumOf { it.outliers + it.old + it.bad }
        val text = when {
            use.isEmpty() -> "No live prices fetched in the last 2 minutes."
            st == "ok" -> "Live prices from ${label(use)}" + (if (x!!.latLast > 0) " · ${x.latLast} ms" else "") + (if (rej > 0) " · $rej bad ticks blocked" else "")
            else -> "${label(use)} is ${if (st == "down") "not answering" else "slow or failing"}" + (if (x!!.lastError.isNotEmpty()) ": ${x.lastError}" else "")
        }
        linkedMapOf("source" to use, "label" to (if (use.isEmpty()) "" else label(use)), "state" to st, "glitchesRejected" to rej, "text" to text)
    }

    // ------------------------------------------------------------------------------------------- option chain

    /** Every strike of an NSE chain as NSE samples (real bid/ask from NSE's book, fetched with the chain). */
    fun offerChain(c: FnoChain): Int {
        val ed = Fno.expDay(c.expiry); if (ed <= 0) return 0
        var n = 0
        for ((side, m) in listOf("CE" to c.ce, "PE" to c.pe)) for ((k, q) in m) {
            if (!(q.ltp > 0)) continue
            if (offer(PQ(optKey(c.sym, ed, k.toDouble(), side), OPT, q.ltp, NSE, c.fetchedAt, c.fetchedAt, false,
                    prevClose = if (q.chg != 0.0 && q.ltp - q.chg > 0) q.ltp - q.chg else 0.0, chg = q.chg,
                    bid = q.bid, ask = q.ask, depth = q.bid > 0 && q.ask >= q.bid, oi = q.oi, vol = q.vol))) n++
        }
        return n
    }

    /**
     * NSE chain + the hub's live quotes for [strikes] -> a fresh chain. A strike priced by a live source takes its last
     * price; bid/ask are the source's real best bid/ask when it gave depth, otherwise NSE's spread re-centred on the live
     * price and marked synth. Every quote carries its own source and fetch time, so fills never use an old strike price
     * just because another strike was refreshed.
     */
    fun overlay(base: FnoChain, spot: Double?, strikes: Collection<Int>, now: Long, ts: String): FnoChain {
        val ed = Fno.expDay(base.expiry)
        fun stamp(x: FnoQuote) = if (x.at > 0) x else FnoQuote(x.ltp, x.bid, x.ask, x.iv, x.oi, x.vol, x.chg, x.src, base.fetchedAt, x.synth)
        val ce = HashMap<Int, FnoQuote>(); val pe = HashMap<Int, FnoQuote>()
        for ((k, v) in base.ce) ce[k] = stamp(v)
        for ((k, v) in base.pe) pe[k] = stamp(v)
        for (k in strikes) for (side in listOf("CE", "PE")) {
            val q = get(optKey(base.sym, ed, k.toDouble(), side)) ?: continue
            if (q.src == NSE || now - q.at > FETCH_STALE_MS) continue
            val old = base.quote(k, side)
            val l = q.ltp
            val bid: Double; val ask: Double; val synth: Boolean
            if (q.depth && q.bid > 0 && q.ask >= q.bid) { bid = q.bid; ask = q.ask; synth = false }
            else {
                val half = if (old != null && old.ask > old.bid && old.bid > 0) Math.min((old.ask - old.bid) / 2, Math.max(0.05, l * 0.02)) else Math.max(0.05, l * 0.002)
                bid = Math.max(0.05, tick(l - half)); ask = Math.max(bid + 0.05, tick(l + half)); synth = true
            }
            val chg = if (q.prevClose > 0) q.chg else if (old != null) r2(old.chg + (l - old.ltp)) else if (q.chg.isNaN()) 0.0 else q.chg
            val fq = FnoQuote(l, r2(bid), r2(ask), old?.iv ?: 0.0, if (q.oi > 0) q.oi else old?.oi ?: 0.0, if (q.vol > 0) q.vol else old?.vol ?: 0.0, chg, q.src, q.at, synth)
            if (side == "CE") ce[k] = fq else pe[k] = fq
        }
        return FnoChain(base.sym, base.expiry, spot ?: base.spot, ts, now, base.strikes, ce, pe, base.expiries)
    }
    private fun tick(x: Double) = Math.round(x * 20) / 20.0

    private fun srcState(x: Health, now: Long): String = when {
        x.streak >= 3 && x.failAt > x.okAt -> "down"
        x.streak > 0 || (x.okAt > 0 && now - x.okAt > 5 * 60_000L) -> "degraded"
        else -> "ok"
    }

    companion object {
        const val MSTOCK = "mstock"; const val GROWW = "groww"; const val NSE = "nse"; const val YAHOO = "yahoo"
        val RANK = listOf(MSTOCK, GROWW, NSE, YAHOO)
        const val EQ = "EQ"; const val IDX = "IDX"; const val OPT = "OPT"; const val FUT = "FUT"
        /** A better source keeps priority while it delivered within this window. */
        const val ARB_FRESH_MS = 10_000L
        /** Outlier reference: the last good price within this window. */
        const val REF_MS = 10 * 60_000L
        const val CONFIRM_MS = 90_000L
        const val FETCH_STALE_MS = 60_000L
        /** Largest move between two samples accepted without confirmation, per kind. */
        val JUMP = mapOf(EQ to 0.08, IDX to 0.03, FUT to 0.08, OPT to 0.40)
        /** Option ticks moving less than this many rupees are never held back (cheap contracts move in big percentages). */
        val ABS_MIN = mapOf(OPT to 5.0, EQ to 0.5, IDX to 0.0, FUT to 1.0)

        fun rank(src: String): Int = RANK.indexOf(src).let { if (it < 0) 99 else it }
        fun label(src: String): String = when (src) { MSTOCK -> "m.Stock"; GROWW -> "Groww"; NSE -> "NSE"; YAHOO -> "Yahoo"; else -> src }
        fun eqKey(sym: String) = "EQ:" + sym.uppercase()
        fun idxKey(sym: String) = "IDX:" + sym.uppercase()
        /** Option contract key: underlying, expiry epoch day, strike, CE|PE. */
        fun optKey(sym: String, expDay: Int, strike: Double, side: String) = "OPT:" + sym.uppercase() + "|" + expDay + "|" + FnoUniverse.strikeText(strike) + "|" + side.uppercase()
        fun optKey(sym: String, expiry: String, strike: Double, side: String) = optKey(sym, Fno.expDay(expiry), strike, side)
        /** Exchange-time fields arrive in seconds or milliseconds depending on the source: normalise to ms. */
        fun normTs(v: Double?): Long = if (v == null || !(v > 0)) 0L else if (v < 1e11) (v * 1000).toLong() else if (v > 1e14) (v / 1000).toLong() else v.toLong()
        private fun r2(x: Double) = Math.rint(x * 100) / 100

        /** m.Stock quote -> hub sample (exchange time when the reply carries one). */
        fun fromBroker(key: String, kind: String, q: MStock.BQuote, now: Long): PQ = PQ(key, kind, q.ltp, MSTOCK, if (q.ts > 0) q.ts else now, now, q.ts > 0,
            prevClose = q.prevClose, open = q.open, high = q.high, low = q.low, bid = q.bid, ask = q.ask, depth = q.depth, oi = q.oi, vol = q.vol,
            upper = q.upper, lower = q.lower)
    }
}
