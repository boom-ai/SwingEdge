package com.boomai.swingedge.core

/*
 * F&O lab, statistics layer. Everything that decides "which variant / which exit / whether to show a call" lives here,
 * so the rules in FnoLab stay plain:
 *  - DStat: exponentially decayed sufficient statistics (half-life ~60 trades per key) of return on premium.
 *  - Normal-Inverse-Gamma posterior on the mean return, Beta posterior on the win rate, credible intervals.
 *  - Thompson sampling (seeded, so a day's champion is reproducible) to pick the champion among threshold variants.
 *  - Exit rules: fixed stop/target, trailing stop after an arm level, and time stops, scored by the same posterior.
 *  - Extended filter: 15 features, global logistic weights + per-family offsets shrunk to the global model,
 *    Platt recalibration on recent out-of-sample scores, and a probability gate that is only switched on when its gain on
 *    the oldest 70% of scored trades also holds on the newest 30% (walk-forward).
 *  - Risk layer for shown calls: daily loss caps per rule and overall, one open call per underlying, an index
 *    correlation guard, and no new calls in the last 45 minutes before the 3:15 exit.
 *  - Daily context (previous day H/L/C, last 7 ranges, inside day, ATR%) and the new intraday rule triggers.
 * Pure Kotlin, never throws.
 */

class DStat(var w: Double = 0.0, var sx: Double = 0.0, var sxx: Double = 0.0, var sWin: Double = 0.0, var sCost: Double = 0.0, var n: Int = 0) {
    /** Add one trade's return on premium [r] (pnl / (entry * qty)) and its premium cost per lot; older evidence decays by [lam]. */
    fun add(r: Double, cost: Double, lam: Double = FnoLearn.LAMBDA) {
        if (r.isNaN() || r.isInfinite()) return
        w = w * lam + 1; sx = sx * lam + r; sxx = sxx * lam + r * r; sWin = sWin * lam + (if (r > 0) 1.0 else 0.0); sCost = sCost * lam + Math.max(0.0, cost); n++
    }
    val mean get() = if (w > 0) sx / w else 0.0
    val avgCost get() = if (w > 0) sCost / w else 0.0
    fun toList() = listOf(w, sx, sxx, sWin, sCost, n.toDouble())
    companion object {
        fun from(v: Any?): DStat { val l = (v as? List<*>).orEmpty().map { (it as? Number)?.toDouble() ?: 0.0 }; return if (l.size < 6) DStat() else DStat(l[0], l[1], l[2], l[3], l[4], l[5].toInt()) }
    }
}

/** Posterior summary: mean return with a 90% credible interval, win rate with its interval, ₹ per lot via the average premium. */
class Post(val mu: Double, val lo: Double, val hi: Double, val win: Double, val winLo: Double, val winHi: Double, val neff: Double, val cost: Double, val n: Int) {
    val exp get() = mu * cost
    val expLo get() = lo * cost
    val expHi get() = hi * cost
    /** Interval excludes zero on the positive side. */
    val provenUp get() = n >= FnoLearn.MIN_EVID && lo > 0
    val provenDown get() = n >= FnoLearn.MIN_EVID && hi < 0
}

class ExitRule(val stop: Double, val tgt: Double, val arm: Double = 0.0, val trail: Double = 0.0, val tmin: Int = 0) {
    val key: String get() = FnoLab.vkey(stop, tgt) + (if (trail > 0) "/T${trail.toInt()}@${arm.toInt()}" else "") + (if (tmin > 0) "/@$tmin" else "")
    fun text(): String {
        val s = StringBuilder("stop −${stop.toInt()}%")
        if (trail > 0) s.append(", trail ${trail.toInt()}% after +${arm.toInt()}%")
        if (tgt > 0) s.append(" / target +${tgt.toInt()}%")
        s.append(if (tmin > 0) ", out by ${tmin / 60}:${(tmin % 60).toString().padStart(2, '0')}" else if (tgt <= 0 && trail <= 0) " / hold to 3:15" else "")
        return s.toString()
    }
    companion object {
        fun parse(k: String): ExitRule? {
            val p = k.split('/'); if (p.size < 2) return null
            val s = p[0].toDoubleOrNull() ?: return null; val t = p[1].toDoubleOrNull() ?: return null
            var arm = 0.0; var tr = 0.0; var tm = 0
            for (x in p.drop(2)) { if (x.startsWith("T")) { tr = x.drop(1).substringBefore('@').toDoubleOrNull() ?: 0.0; arm = x.substringAfter('@', "0").toDoubleOrNull() ?: 0.0 } else if (x.startsWith("@")) tm = x.drop(1).toIntOrNull() ?: 0 }
            return ExitRule(s, t, arm, tr, tm)
        }
    }
}

class DailyCtx(val prevH: Double, val prevL: Double, val prevC: Double, val ranges: DoubleArray, val inside: Boolean, val atrPct: Double)

class FeatCtx(val vix: Double? = null, val dow: Int = 3, val dte: Int = 3, val ivRank: Double = 0.0, val pcr: Double = 0.0, val pcr0: Double = 0.0,
              val idxMove: Double? = null, val atrPct: Double = 0.0)

object FnoLearn {
    const val HALF_LIFE = 60.0
    val LAMBDA = Math.pow(0.5, 1.0 / HALF_LIFE)
    // Normal-Inverse-Gamma prior on return on premium: mean 0, ~2 pseudo-trades, prior sd ~0.32 (a typical option-buying spread)
    const val MU0 = 0.0; const val KAPPA0 = 2.0; const val ALPHA0 = 2.0; const val BETA0 = 0.1
    const val MIN_EVID = 5            // trades before a variant may be champion / an interval is reported as evidence
    const val MIN_EXIT = 8            // trades before the exit rule moves off the default

    /* ---------------- posterior ---------------- */
    fun post(d: DStat?): Post {
        val s = d ?: DStat()
        val n = s.w; val xb = s.mean; val ss = Math.max(0.0, s.sxx - n * xb * xb)
        val kn = KAPPA0 + n; val mun = (KAPPA0 * MU0 + n * xb) / kn
        val an = ALPHA0 + n / 2; val bn = BETA0 + ss / 2 + KAPPA0 * n * (xb - MU0) * (xb - MU0) / (2 * kn)
        val scale = Math.sqrt(bn / (an * kn)); val q = tq95(2 * an)
        val a = 1 + s.sWin; val b = 1 + Math.max(0.0, n - s.sWin); val wm = a / (a + b); val wsd = Math.sqrt(a * b / ((a + b) * (a + b) * (a + b + 1)))
        return Post(mun, mun - q * scale, mun + q * scale, wm, Math.max(0.0, wm - 1.645 * wsd), Math.min(1.0, wm + 1.645 * wsd), n, s.avgCost, s.n)
    }
    /** 95th percentile of Student-t with [nu] degrees of freedom (Cornish-Fisher expansion; exact enough for nu >= 3). */
    fun tq95(nu: Double): Double {
        val z = 1.6448536; if (nu <= 0) return 6.0
        return z + (z * z * z + z) / (4 * nu) + (5 * Math.pow(z, 5.0) + 16 * z * z * z + 3 * z) / (96 * nu * nu)
    }
    /** Marsaglia-Tsang gamma(shape, 1) draw. */
    fun gamma(rnd: java.util.Random, shape: Double): Double {
        if (shape < 1) return gamma(rnd, shape + 1) * Math.pow(rnd.nextDouble(), 1 / shape)
        val d = shape - 1.0 / 3; val c = 1 / Math.sqrt(9 * d)
        while (true) {
            var x: Double; var v: Double
            do { x = rnd.nextGaussian(); v = 1 + c * x } while (v <= 0)
            v = v * v * v; val u = rnd.nextDouble()
            if (u < 1 - 0.0331 * x * x * x * x) return d * v
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v
        }
    }
    /** One Thompson draw of the mean return from the NIG posterior. */
    fun draw(d: DStat?, rnd: java.util.Random): Double {
        val s = d ?: DStat()
        val n = s.w; val xb = s.mean; val ss = Math.max(0.0, s.sxx - n * xb * xb)
        val kn = KAPPA0 + n; val mun = (KAPPA0 * MU0 + n * xb) / kn
        val an = ALPHA0 + n / 2; val bn = BETA0 + ss / 2 + KAPPA0 * n * (xb - MU0) * (xb - MU0) / (2 * kn)
        val sigma2 = bn / Math.max(1e-9, gamma(rnd, an))
        return mun + Math.sqrt(sigma2 / kn) * rnd.nextGaussian()
    }
    fun seedOf(strat: String, sym: String, seed: Long): Long = seed * 1_000_003L + (strat + "|" + sym).hashCode().toLong()

    /**
     * Thompson-sampled champion among the threshold variants of [strat] on [sym]. Only variants with [MIN_EVID]+ trades
     * compete; the middle threshold stands in (at the prior mean 0) until one of them beats it. [seed] (the day) makes a
     * day's pick reproducible. Every variant keeps being paper-traded, so challengers are always explored.
     */
    fun thompson(b: LabBook, strat: String, sym: String, seed: Long): String? {
        val l = FnoLab.VARIANTS[strat] ?: return strat
        if (l.size <= 1) return strat
        val mid = FnoLab.vid(strat, l.size / 2)
        val elig = l.indices.map { FnoLab.vid(strat, it) }.filter { (b.post["$it|$sym"]?.get("live")?.n ?: 0) >= MIN_EVID }
        if (elig.isEmpty()) return null
        val rnd = java.util.Random(seedOf(strat, sym, seed))
        var best = mid; var bv = if (elig.contains(mid)) Double.NEGATIVE_INFINITY else 0.0
        for (v in elig) { val x = draw(b.post["$v|$sym"]?.get("live"), rnd); if (x > bv) { bv = x; best = v } }
        return best
    }

    /* ---------------- exits ---------------- */
    val EXTRA_EXITS = listOf(ExitRule(30.0, 0.0, 30.0, 20.0), ExitRule(30.0, 0.0, 30.0, 30.0), ExitRule(30.0, 50.0, tmin = 12 * 60 + 30), ExitRule(30.0, 50.0, tmin = 14 * 60))
    val GRID: List<ExitRule> by lazy { FnoLab.STOPS.flatMap { s -> FnoLab.TGTS.map { t -> ExitRule(s, t) } } + EXTRA_EXITS }

    /** Replay [r] on a bid path; [mins] (minute of day of each bid, may be shorter or empty) drives time stops. */
    fun replay(entry: Double, bids: List<Double>, mins: List<Int>, r: ExitRule): Pair<Double, String> {
        val sl = entry * (1 - r.stop / 100); val tg = if (r.tgt > 0) entry * (1 + r.tgt / 100) else Double.MAX_VALUE
        val armPx = entry * (1 + r.arm / 100); var peak = 0.0
        for (i in bids.indices) {
            val x = bids[i]
            if (x <= sl) return Pair(x, "STOP")
            if (x >= tg) return Pair(x, "TARGET")
            peak = Math.max(peak, x)
            if (r.trail > 0 && peak >= armPx && x <= peak * (1 - r.trail / 100)) return Pair(x, "TRAIL")
            val mi = mins.getOrNull(i)
            if (r.tmin > 0 && mi != null && mi >= r.tmin) return Pair(x, "TIME")
        }
        return Pair(bids.lastOrNull() ?: entry, "TIME")
    }

    fun isIndexSym(sym: String) = Fno.INDICES.contains(sym)
    /** Exit learning is per (variant, index) for indices, pooled per variant across stocks (one stock never builds evidence). */
    fun exitKey(key: String): String { val sym = key.substringAfter('|'); return if (isIndexSym(sym)) key else key.substringBefore('|') + "|STOCKS" }
    fun famKey(strat: String) = FnoLab.baseOf(strat) + "|*"

    /** Best exit for [key] by posterior mean of decayed returns (all rules are replayed on every trade, so no exploration is needed). */
    fun bestExit(b: LabBook, key: String): ExitRule? {
        val st = b.post[exitKey(key)] ?: return null
        if ((st[ExitRule(FnoLab.DEF_STOP, FnoLab.DEF_TGT).key]?.n ?: 0) < MIN_EXIT) return null
        var best: ExitRule? = null; var bv = Double.NEGATIVE_INFINITY
        for (r in GRID) { val d = st[r.key] ?: continue; val m = post(d).mu; if (m > bv + 1e-12) { bv = m; best = r } }
        return best
    }

    /** Teach the posteriors from one closed trade (its premium path is replayed on every exit rule). */
    fun teach(b: LabBook, t: LabTrade, path: List<Double>) {
        val cost = t.entry * t.qty
        if (cost <= 0) return
        val ek = b.post.getOrPut(exitKey(FnoLab.key(t))) { LinkedHashMap() }
        for (r in GRID) ek.getOrPut(r.key) { DStat() }.add(FnoLab.net(t.entry, replay(t.entry, path, t.mins, r).first, t.qty) / cost, cost)
        b.post.getOrPut(FnoLab.key(t)) { LinkedHashMap() }.getOrPut("live") { DStat() }.add(t.pnl / cost, cost)
        if (t.champ) b.post.getOrPut(famKey(t.strat)) { LinkedHashMap() }.getOrPut("live") { DStat() }.add(t.pnl / cost, cost)
    }

    /* ---------------- extended filter ---------------- */
    const val NX = 15
    val FEATX_NAMES = FnoLab.FEAT_NAMES + listOf("day of week", "days to expiry", "IV rank", "put-call ratio", "PCR change today", "index agreeing", "stock's daily ATR%")
    private fun cl(x: Double) = Math.max(-3.0, Math.min(3.0, x))
    fun featuresX(bars: IntraBars, m: Int, side: String, c: FeatCtx): List<Double> {
        val sg = if (side == "CE") 1.0 else -1.0
        return FnoLab.features(bars, m, side, c.vix) + listOf(cl((c.dow - 3) / 2.0), cl((c.dte - 3) / 3.0), cl(c.ivRank),
            if (c.pcr > 0) cl((c.pcr - 1) / 0.3 * sg) else 0.0, if (c.pcr > 0 && c.pcr0 > 0) cl((c.pcr - c.pcr0) / 0.1 * sg) else 0.0,
            c.idxMove?.let { cl(it / 0.3 * sg) } ?: 0.0, if (c.atrPct > 0) cl((c.atrPct - 1.5) / 1.0) else 0.0)
    }
    fun sig(z: Double) = 1 / (1 + Math.exp(-Math.max(-30.0, Math.min(30.0, z))))
    fun rawX(b: LabBook, fam: String, f: List<Double>): Double {
        if (f.size != NX) return 0.0
        val wf = b.wf[fam]; var z = 0.0
        for (i in 0 until NX) z += (b.wx[i] + (wf?.get(i) ?: 0.0)) * f[i]
        return z
    }
    /** Calibrated win probability from the extended model. */
    fun probX(b: LabBook, fam: String, f: List<Double>) = sig(b.cal[0] * rawX(b, fam, f) + b.cal[1])
    /** Entry probability: the calibrated extended model once it has 30+ trades, the basic 8-feature model before that. */
    fun entryProb(b: LabBook, fam: String, f8: List<Double>, fx: List<Double>): Double =
        if (b.nx >= 30 && fx.size == NX) probX(b, fam, fx) else FnoLab.predict(b.w, f8)

    /** SGD on the hierarchical model: global weights + per-family offsets with a stronger pull to zero (shrinkage to the global model). */
    fun learnX(b: LabBook, t: LabTrade) {
        if (t.fx.size != NX) return
        val fam = FnoLab.baseOf(t.strat)
        val y = if (t.pnl > 0) 1.0 else 0.0
        val wt = Math.min(3.0, 0.5 + Math.abs(t.pnl) / Math.max(1.0, t.entry * t.qty * 0.3))
        val lr = 0.12 / Math.sqrt(1.0 + b.nx / 20.0)
        val wf = b.wf.getOrPut(fam) { DoubleArray(NX) }
        val err = y - sig(rawX(b, fam, t.fx))
        for (i in 0 until NX) {
            val g = wt * err * t.fx[i]
            b.wx[i] += lr * (g - (if (i == 0) 0.0 else 0.01 * b.wx[i]))
            wf[i] += lr * (0.5 * g - 0.08 * wf[i])
        }
        b.nx++
        calibrate(b)
    }

    /** Platt scaling: fit p = sigmoid(a*z + c) by Newton's method with Platt's smoothed targets and a light ridge toward (1, 0). */
    fun platt(z: DoubleArray, y: DoubleArray): DoubleArray {
        val np = y.count { it > 0.5 }.toDouble(); val nn = y.size - np
        val tp = (np + 1) / (np + 2); val tn = 1 / (nn + 2)
        var a = 1.0; var c = 0.0; val lam = 1e-2
        fun nll(aa: Double, cc: Double): Double { var f = lam / 2 * ((aa - 1) * (aa - 1) + cc * cc)
            for (i in z.indices) { val zz = aa * z[i] + cc; val tt = if (y[i] > 0.5) tp else tn
                f += tt * Math.log(1 + Math.exp(-zz)).let { if (it.isInfinite()) -zz else it } + (1 - tt) * Math.log(1 + Math.exp(zz)).let { if (it.isInfinite()) zz else it } }
            return f }
        repeat(30) {
            var ga = lam * (a - 1); var gc = lam * c; var haa = lam; var hac = 0.0; var hcc = lam
            for (i in z.indices) {
                val p = sig(a * z[i] + c); val tt = if (y[i] > 0.5) tp else tn; val d = p - tt; val w = Math.max(1e-6, p * (1 - p))
                ga += d * z[i]; gc += d; haa += w * z[i] * z[i]; hac += w * z[i]; hcc += w
            }
            val det = haa * hcc - hac * hac; if (Math.abs(det) < 1e-12) return doubleArrayOf(a, c)
            val da = (hcc * ga - hac * gc) / det; val dc = (haa * gc - hac * ga) / det
            // damped Newton: halve the step until the smoothed log-loss falls (saturated scores make raw steps overshoot)
            val f0 = nll(a, c); var st = 1.0
            while (st > 1e-4 && nll(Math.max(-5.0, Math.min(5.0, a - st * da)), Math.max(-5.0, Math.min(5.0, c - st * dc))) > f0) st /= 2
            if (st <= 1e-4) return doubleArrayOf(a, c)
            a = Math.max(-5.0, Math.min(5.0, a - st * da)); c = Math.max(-5.0, Math.min(5.0, c - st * dc))
        }
        return doubleArrayOf(a, c)
    }
    /** Re-fit the calibration on the newest 200 trades scored out of sample at entry (needs 30). */
    fun calibrate(b: LabBook) {
        val s = b.closed.filter { it.fx.size == NX && it.zx != 0.0 }.take(200)
        if (s.size < 30) { b.cal = doubleArrayOf(1.0, 0.0); return }
        b.cal = platt(s.map { it.zx }.toDoubleArray(), s.map { if (it.pnl > 0) 1.0 else 0.0 }.toDoubleArray())
    }

    /**
     * Walk-forward gate: choose the cut-off on the oldest 70% of scored trades (probabilities recorded at entry, so they are
     * already out of sample), and keep it only if the newest 30% also made more money with it than without it.
     * Returns (gate, train gain, test gain).
     */
    val GATES = listOf(0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65)
    fun walkForward(chrono: List<Pair<Double, Double>>): Triple<Double, Double, Double> {
        if (chrono.size < 30) return Triple(0.0, 0.0, 0.0)
        val cut = (chrono.size * 0.7).toInt(); val tr = chrono.subList(0, cut); val te = chrono.subList(cut, chrono.size)
        val all = tr.sumOf { it.second }; var best = 0.0; var bp = all
        for (g in GATES) {
            val keep = tr.filter { it.first >= g }
            if (keep.size < Math.max(10, tr.size / 5)) continue
            val pn = keep.sumOf { it.second }
            if (pn > bp + Math.max(1.0, Math.abs(bp) * 0.05)) { bp = pn; best = g }
        }
        if (best <= 0) return Triple(0.0, 0.0, 0.0)
        val teAll = te.sumOf { it.second }; val teKeep = te.filter { it.first >= best }
        val gain = teKeep.sumOf { it.second } - teAll
        return if (teKeep.size >= Math.max(3, te.size / 5) && gain > 0) Triple(best, bp - all, gain) else Triple(0.0, bp - all, gain)
    }

    /** IV rank in [-1, 1] from the stored daily history under [key] (records today's value; needs 10 earlier days). */
    fun ivRank(b: LabBook, key: String, day: Int, iv: Double): Double {
        if (iv <= 0) return 0.0
        val h = b.ivh.getOrPut(key) { ArrayList() }
        if (h.size >= 2 && h[h.size - 2].toInt() == day) h[h.size - 1] = iv else { h.add(day.toDouble()); h.add(iv) }
        while (h.size > 120) { h.removeAt(0); h.removeAt(0) }
        val past = (0 until h.size / 2).filter { h[2 * it].toInt() != day }.map { h[2 * it + 1] }
        if (past.size < 10) return 0.0
        return (past.count { it < iv } + 0.5 * past.count { it == iv }) / past.size * 2 - 1
    }
    /** Put-call ratio of total OI on a chain (0 when the chain has no OI). */
    fun pcr(c: FnoChain?): Double {
        if (c == null) return 0.0
        val ce = c.strikes.sumOf { c.ce[it]?.oi ?: 0.0 }; val pe = c.strikes.sumOf { c.pe[it]?.oi ?: 0.0 }
        return if (ce > 0 && pe > 0) pe / ce else 0.0
    }
    /** First PCR seen today for [sym] (recorded on first call of the day). */
    fun pcrOpen(b: LabBook, sym: String, day: Int, pcr: Double): Double {
        if (pcr <= 0) return 0.0
        val x = b.pcr0[sym]
        if (x == null || x.size < 2 || x[0].toInt() != day) { b.pcr0[sym] = listOf(day.toDouble(), pcr); return pcr }
        return x[1]
    }

    /* ---------------- risk layer (shown calls only; paper learning continues) ---------------- */
    const val LAST_ENTRY_GAP = 45
    const val MAX_PER_SYM = 1
    const val CORR_MAX = 2
    const val STRAT_LOSS_PCT = 1.5        // % of capital lost today on one rule's shown calls (per lot, incl. open loss)
    const val DAY_LOSS_PCT = 3.0          // % of capital lost today on all shown calls
    val CORR_SET = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
    private fun shownPnl(t: LabTrade) = if (t.status == "OPEN") FnoLab.net(t.entry, t.bids.lastOrNull() ?: t.entry, t.qty) else t.pnl

    /** Reason a new shown call must be held back, or null. */
    fun riskBlock(b: LabBook, sym: String, side: String, strat: String, day: Int, m: Int, capital: Double): String? {
        if (m >= FnoLab.EXIT_MIN - LAST_ENTRY_GAP) return "late: no new calls in the last 45 minutes"
        val shown = (b.open + b.closed).filter { it.day == day && it.champ && !it.gated }
        val open = b.open.filter { it.day == day && it.champ && !it.gated }
        if (open.count { it.sym == sym } >= MAX_PER_SYM) return "already holding a call on $sym"
        if (CORR_SET.contains(sym) && open.count { CORR_SET.contains(it.sym) && it.side == side } >= CORR_MAX) return "already $CORR_MAX same-direction index calls open"
        val cap = if (capital > 0) capital else 100000.0
        val base = FnoLab.baseOf(strat)
        if (shown.filter { FnoLab.baseOf(it.strat) == base }.sumOf { shownPnl(it) } <= -cap * STRAT_LOSS_PCT / 100) return "${FnoLab.stratName(base)} hit its daily loss cap"
        if (shown.sumOf { shownPnl(it) } <= -cap * DAY_LOSS_PCT / 100) return "daily loss cap hit"
        return null
    }

    /* ---------------- daily context + new rules ---------------- */
    fun dailyCtx(s: Series?, today: Int): DailyCtx? {
        if (s == null) return null
        var e = s.n; while (e > 0 && s.d[e - 1] >= today) e--
        if (e < 8) return null
        val i = e - 1
        val rg = DoubleArray(7) { k -> s.h[i - 6 + k] - s.l[i - 6 + k] }
        var tr = 0.0; val nA = Math.min(14, i)
        for (k in i - nA + 1..i) tr += Math.max(s.h[k] - s.l[k], Math.max(Math.abs(s.h[k] - s.c[k - 1]), Math.abs(s.l[k] - s.c[k - 1])))
        return DailyCtx(s.h[i], s.l[i], s.c[i], rg, s.h[i] < s.h[i - 1] && s.l[i] > s.l[i - 1], if (s.c[i] > 0) tr / nA / s.c[i] * 100 else 0.0)
    }
    /** Central pivot range (top, bottom, width %) from the previous day. */
    fun cpr(d: DailyCtx): Triple<Double, Double, Double> {
        val p = (d.prevH + d.prevL + d.prevC) / 3; val bc = (d.prevH + d.prevL) / 2; val tc = 2 * p - bc
        return Triple(Math.max(tc, bc), Math.min(tc, bc), Math.abs(tc - bc) / p * 100)
    }
    /** NR-n test on the last [n] daily ranges (yesterday narrowest). */
    fun isNr(d: DailyCtx, n: Int): Boolean { val r = d.ranges; val k = Math.min(n, r.size); val y = r[r.size - 1]; return (r.size - k until r.size - 1).all { r[it] > y } }

    /** Supertrend(period, mult) direction (+1 / -1) for every bar (0 before it is defined). */
    fun supertrend(h: DoubleArray, l: DoubleArray, c: DoubleArray, period: Int, mult: Double): IntArray {
        val n = c.size; val dir = IntArray(n)
        if (n <= period) return dir
        var atr = 0.0
        for (i in 1..period) atr += Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])))
        atr /= period
        var fu = (h[period] + l[period]) / 2 + mult * atr; var fl = (h[period] + l[period]) / 2 - mult * atr
        dir[period] = if (c[period] >= (h[period] + l[period]) / 2) 1 else -1
        for (i in period + 1 until n) {
            val tr = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])))
            atr = (atr * (period - 1) + tr) / period
            val mid = (h[i] + l[i]) / 2; val ub = mid + mult * atr; val lb = mid - mult * atr
            fu = if (ub < fu || c[i - 1] > fu) ub else fu
            fl = if (lb > fl || c[i - 1] < fl) lb else fl
            dir[i] = if (dir[i - 1] == 1) (if (c[i] < fl) -1 else 1) else (if (c[i] > fu) 1 else -1)
        }
        return dir
    }

    private fun f0(x: Double) = String.format(java.util.Locale.US, "%.0f", x)
    private fun f2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)

    /** New rule triggers on completed bars up to [li]. [pv] gives a rule's threshold; [scale] widens % thresholds for stocks. */
    fun newSignals(bars: IntraBars, m: Int, li: Int, fired: Set<String>, pv: (String, Double) -> Double, scale: Double): List<LabSignal> {
        val out = ArrayList<LabSignal>()
        val last = bars.c[li]; val d = bars.daily
        val orIdx = (0 until bars.n).filter { bars.min[it] < 9 * 60 + 30 }
        if (d != null && li >= 1 && m in (9 * 60 + 35)..(12 * 60)) {
            if (!fired.contains("CPR") && orIdx.size >= 3 && bars.min[li] >= 9 * 60 + 30) {
                val (top, bot, w) = cpr(d)
                val orh = orIdx.maxOf { bars.h[it] }; val orl = orIdx.minOf { bars.l[it] }
                if (w <= pv("CPR", 0.2)) {
                    val up = Math.max(top, orh); val dn = Math.min(bot, orl)
                    if (last > up && bars.c[li - 1] <= up) out.add(LabSignal("CPR", "CE", "Narrow CPR day (${f2(w)}%): 5-min close ${f0(last)} above the CPR and the opening range"))
                    else if (last < dn && bars.c[li - 1] >= dn) out.add(LabSignal("CPR", "PE", "Narrow CPR day (${f2(w)}%): 5-min close ${f0(last)} below the CPR and the opening range"))
                }
            }
            if (!fired.contains("NR7")) {
                val p = pv("NR7", 7.0); val n = p.toInt()
                if (isNr(d, n) && (p < 7.25 || d.inside)) {
                    val lbl = if (p >= 7.25) "NR7 inside day" else "NR$n day"
                    if (last > d.prevH && bars.c[li - 1] <= d.prevH) out.add(LabSignal("NR7", "CE", "Yesterday was an $lbl: 5-min close ${f0(last)} above its high ${f0(d.prevH)}"))
                    else if (last < d.prevL && bars.c[li - 1] >= d.prevL) out.add(LabSignal("NR7", "PE", "Yesterday was an $lbl: 5-min close ${f0(last)} below its low ${f0(d.prevL)}"))
                }
            }
        }
        if (!fired.contains("STREND") && m in (9 * 60 + 45)..(14 * 60) && li >= 12) {
            val mult = pv("STREND", 3.0)
            val n = li + 1
            val dir = supertrend(bars.h.copyOf(n), bars.l.copyOf(n), bars.c.copyOf(n), 10, mult)
            if (dir[li - 1] != 0 && dir[li] != dir[li - 1]) out.add(LabSignal("STREND", if (dir[li] > 0) "CE" else "PE", "Supertrend(10, ${f1(mult)}) on 5-min flipped " + (if (dir[li] > 0) "up" else "down") + " at ${f0(last)}"))
        }
        if (!fired.contains("DARVAS") && m in (10 * 60)..(14 * 60)) {
            val nb = pv("DARVAS", 9.0).toInt()
            if (li - nb >= 0) {
                val box = (li - nb until li)
                val top = box.maxOf { bars.h[it] }; val bot = box.minOf { bars.l[it] }
                val hgt = (top - bot) / bars.open * 100
                if (hgt <= 0.5 * scale && hgt > 0) {
                    if (last > top) out.add(LabSignal("DARVAS", "CE", "Broke out of a ${nb}-bar box (${f2(hgt)}% tall) at ${f0(last)}"))
                    else if (last < bot) out.add(LabSignal("DARVAS", "PE", "Broke down from a ${nb}-bar box (${f2(hgt)}% tall) at ${f0(last)}"))
                }
            }
        }
        return out
    }
    private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)

    /* ---------------- reporting ---------------- */
    fun postView(d: DStat?): Map<String, Any?> {
        val p = post(d)
        val ev = when { p.n < MIN_EVID -> "LEARNING"; p.provenUp -> "EDGE"; p.provenDown -> "NO_EDGE"; else -> "UNSURE" }
        return linkedMapOf("winP" to Fno.r2(p.win * 100), "winLo" to Fno.r2(p.winLo * 100), "winHi" to Fno.r2(p.winHi * 100),
            "exp" to Fno.r2(p.exp), "expLo" to Fno.r2(p.expLo), "expHi" to Fno.r2(p.expHi), "neff" to Fno.r2(p.neff), "evidence" to ev,
            "evidenceText" to when (ev) { "EDGE" -> "positive after charges (90% interval above zero)"; "NO_EDGE" -> "losing after charges (90% interval below zero)"; "UNSURE" -> "not enough evidence yet: the interval crosses zero"; else -> "not enough evidence yet: under $MIN_EVID trades" })
    }
    private fun rs(x: Double) = (if (x < 0) "−₹" else "₹") + f0(Math.abs(x))
    /** Plain-English lessons with honest uncertainty. */
    fun lessons(b: LabBook): List<String> {
        val out = ArrayList<String>()
        val fams = FnoLab.STRATS.mapNotNull { s -> b.post[famKey(s.id)]?.get("live")?.let { Pair(s, post(it)) } }.filter { it.second.n >= MIN_EVID }
        for ((s, p) in fams.sortedByDescending { it.second.mu }) {
            val iv = "wins ${Math.round(p.win * 100)}% (${Math.round(p.winLo * 100)}–${Math.round(p.winHi * 100)}%), ${rs(p.exp)}/lot expected (${rs(p.expLo)} to ${rs(p.expHi)}) over ${p.n} calls"
            out.add(s.name + ": " + iv + when { p.provenUp -> ". Positive after charges so far."; p.provenDown -> ". Losing after charges: calls stay on paper."; else -> ". Not enough evidence yet." })
        }
        // exits: does a trailing or time stop beat the fixed default anywhere with evidence?
        val beats = HashMap<String, Int>(); var tested = 0
        for ((k, st) in b.post) { if (k.endsWith("|*")) continue
            val def = st[ExitRule(FnoLab.DEF_STOP, FnoLab.DEF_TGT).key] ?: continue; if (def.n < MIN_EXIT) continue
            tested++; val best = bestExit(b, k) ?: continue
            val kind = if (best.trail > 0) "trail" else if (best.tmin > 0) "time" else if (best.tgt <= 0) "hold" else if (best.tgt < FnoLab.DEF_TGT) "quick" else "fixed"
            beats[kind] = (beats[kind] ?: 0) + 1 }
        if (tested > 0) {
            val top = beats.maxByOrNull { it.value }
            if (top != null) out.add("Exits: " + when (top.key) { "trail" -> "a trailing stop after +30% does best"; "time" -> "leaving by a set time does best"; "hold" -> "holding to 3:15 without a target does best"; "quick" -> "taking a quick +30% does best"; else -> "a fixed stop/target does best" } + " on ${top.value} of $tested rule/market pairs with 8+ trades.")
        }
        return out
    }
}

/** Opening a lab trade: exit rule, both filters, calibrated probability, walk-forward gate and the risk layer in one place. Caller holds the lab lock. */
object LabEntry {
    fun open(b: LabBook, sg: LabSignal, sym: String, exp: String, strike: Int, entry: Double, firstBid: Double, now: Long, day: Int, m: Int,
             bars: IntraBars, champ: Boolean, ctx: FeatCtx, capital: Double, spot: Double, cid: String = ""): LabTrade {
        val key = sg.strat + "|" + sym; val rule = FnoLab.bestRule(b, key); val fam = FnoLab.baseOf(sg.strat)
        val fe = FnoLab.features(bars, m, sg.side, ctx.vix); val fx = FnoLearn.featuresX(bars, m, sg.side, ctx)
        val pw = FnoLearn.entryProb(b, fam, fe, fx); val zx = FnoLearn.rawX(b, fam, fx)
        val gatedF = champ && b.gate > 0 && pw < b.gate
        val block = if (champ && !gatedF) FnoLearn.riskBlock(b, sym, sg.side, sg.strat, day, m, capital) ?: "" else ""
        b.seq++
        val t = LabTrade("L${b.seq}", sg.strat, sym, exp, strike, sg.side, Fno.LOT[sym] ?: 0, entry, now, day,
            sg.why + (if (b.trained >= 10) " · win chance " + Math.round(pw * 100) + "%" else ""), rule.stop, rule.tgt, arrayListOf(firstBid),
            spot = spot, champ = champ, feats = fe, p = pw, gated = gatedF || block.isNotEmpty(), cid = cid, mins = arrayListOf(m), fx = fx, zx = zx, block = block)
        t.setRule(rule)
        b.open.add(t)
        return t
    }
    /** Context for the extended features. [iv] = ATM implied volatility from the chain when present (else VIX percentile is the proxy). */
    fun ctx(b: LabBook, sym: String, exp: String, day: Int, chain: FnoChain?, iv: Double, vix: Double?, idx: IntraBars?, bars: IntraBars): FeatCtx {
        val dow = java.time.LocalDate.ofEpochDay(day.toLong()).dayOfWeek.value
        val ivr = if (iv > 0) FnoLearn.ivRank(b, "IV|$sym", day, iv) else if (vix != null && vix > 0) FnoLearn.ivRank(b, "VIX", day, vix) else 0.0
        val pc = FnoLearn.pcr(chain); val p0 = FnoLearn.pcrOpen(b, sym, day, pc)
        val im = if (FnoLearn.isIndexSym(sym) || idx == null || idx.n == 0 || idx.open <= 0) null else (idx.last - idx.open) / idx.open * 100
        return FeatCtx(vix, dow, Math.max(0, Fno.expDay(exp) - day), ivr, pc, p0, im, bars.daily?.atrPct ?: 0.0)
    }
    fun hitText(hit: String) = when (hit) { "STOP" -> "stop hit, exit now"; "TRAIL" -> "trailing stop hit, exit now"; "TIME" -> "time stop, exit now"; else -> "target hit, book it" }
}
