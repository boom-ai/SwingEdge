package com.boomai.swingedge.core

/**
 * Multi-factor stock rating (nightly job -> data/rating.csv; read by the app).
 *
 * 1. For every liquid NSE stock and every 2nd session of the last year, 15 factors are computed from daily bars
 *    (momentum, trend, 52-week-high proximity, volatility, liquidity, volume behaviour, reversal, sector strength...).
 * 2. Each factor is ranked across stocks on that day and compared with the next [H] sessions' return (rank IC).
 * 3. Weights = information ratio of each factor's IC history, shrunk and kept only when the evidence is real
 *    (overlap-corrected t >= [MIN_T]); a factor that predicted the wrong way gets a negative weight.
 * 4. Walk-forward check: on each test day the weights come only from IC days whose forward window had already ended.
 *    The out-of-sample result (top 10% vs all, hit rate, composite IC) is published with the file.
 * 5. Today's rating = percentile (0-100) of the weighted composite among liquid stocks; rank 1 = best.
 * Pure Kotlin, no I/O.
 */
object FactorModel {
    const val FILE = "rating.csv"
    const val URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/rating.csv"
    const val MAGIC = "#SWINGEDGE_RATING"
    const val VERSION = 1
    const val H = 10                 // forward horizon (sessions)
    const val STEP = 2               // sample every 2nd session
    const val START = 130            // bars of history needed before the first sample
    const val MIN_PRICE = 20.0
    const val MIN_TURNOVER = 2e7     // ₹2 crore a day (20-day average)
    const val MIN_T = 1.5            // overlap-corrected t-stat for a factor to get weight
    const val MIN_STOCKS = 50

    class Factor(val id: String, val name: String, val text: String)
    val FACTORS = listOf(
        Factor("mom6", "6-month momentum", "Return from 6 months ago to 1 month ago (skips the last month)."),
        Factor("mom3", "3-month momentum", "Return over the last 3 months."),
        Factor("rev5", "1-week pullback", "Minus the last 5 days' return: high when the stock just dipped."),
        Factor("hi52", "Near 52-week high", "Close as a share of the highest high of the last year (6 months minimum)."),
        Factor("trend", "Above 50-day average", "How far the close is above its 50-day average."),
        Factor("stack", "50 over 200-day average", "50-day average over the 200-day (100-day when history is short)."),
        Factor("clen", "Trend quality", "Clenow: annualised slope of a 90-day log regression × its R² (steady, straight trends score high)."),
        Factor("lowvol", "Low volatility", "Minus the 60-day standard deviation of daily returns."),
        Factor("liq", "Liquidity", "Log of the 20-day average traded value."),
        Factor("volx", "Volume pick-up", "10-day average volume over 60-day average volume."),
        Factor("updv", "Up-day volume", "Share of the last 20 days' volume traded on up days (accumulation)."),
        Factor("rsi", "RSI(14)", "14-day relative strength index."),
        Factor("dd", "Small drawdown", "Minus the fall from the 60-day peak close."),
        Factor("sect", "Sector strength", "Average 3-month return of the stock's sector."),
        Factor("relsect", "Beats its sector", "3-month return minus the sector average.")
    )
    val NF = FACTORS.size

    class FactorStat(val f: Factor) {
        val ics = ArrayList<Double>()
        var weight = 0.0
        /** mean IC and t-stat as published in rating.csv (the app has no IC history) */
        var pMean: Double? = null
        var pT: Double? = null
        val mean: Double get() = if (ics.isEmpty()) 0.0 else ics.average()
        val sd: Double get() { if (ics.size < 2) return 0.0; val m = mean; return Math.sqrt(ics.sumOf { (it - m) * (it - m) } / (ics.size - 1)) }
        val icir: Double get() = if (sd > 0) mean / sd else 0.0
        /** t-stat corrected for overlapping forward windows (H / STEP overlapping samples) */
        val tstat: Double get() = icir * Math.sqrt(ics.size / (H.toDouble() / STEP))
    }

    class Rated(
        val sym: String, val name: String, val exch: String, val sector: String, val fno: Boolean,
        val close: Double, val rating: Int, var rank: Int, val score: Double,
        /** factor percentiles 0..100 (NaN = unavailable), in FACTORS order */
        val pct: DoubleArray, val r1m: Double, val r3m: Double
    )

    class Result(
        val asof: Int, val sessions: Int, val universe: Int, val stats: List<FactorStat>,
        val oosDays: Int, val oosSpread: Double, val oosHit: Double, val oosIc: Double, val oosTopRet: Double, val oosAllRet: Double,
        val rated: List<Rated>
    )

    // ---------------------------------------------------------------- features of one stock

    class Feat(val s: Series) {
        val n = s.n
        val ret = DoubleArray(n) { if (it > 0 && s.c[it - 1] > 0) s.c[it] / s.c[it - 1] - 1 else 0.0 }
        private val to = DoubleArray(n) { s.c[it] * s.v[it] }
        private fun sma(a: DoubleArray, i: Int, w: Int): Double { if (i + 1 < w) return Double.NaN; var t = 0.0; for (k in i - w + 1..i) t += a[k]; return t / w }
        fun turnover20(i: Int) = sma(to, i, 20)
        fun r(i: Int, back: Int): Double = if (i - back >= 0 && s.c[i - back] > 0) s.c[i] / s.c[i - back] - 1 else Double.NaN

        fun values(i: Int): DoubleArray {
            val v = DoubleArray(NF) { Double.NaN }
            val c = s.c[i]
            if (i >= 126 && s.c[i - 126] > 0) v[0] = s.c[i - 21] / s.c[i - 126] - 1
            v[1] = r(i, 63)
            v[2] = -r(i, 5)
            var hh = 0.0; for (k in maxOf(0, i - 251)..i) hh = maxOf(hh, s.h[k]); if (i >= 120 && hh > 0) v[3] = c / hh
            val s50 = sma(s.c, i, 50); if (s50 > 0) v[4] = c / s50 - 1
            val slow = if (i + 1 >= 200) sma(s.c, i, 200) else sma(s.c, i, 100); if (s50 > 0 && slow > 0) v[5] = s50 / slow - 1
            if (i >= 90) {
                var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var ok = true
                for (k in 0 until 90) { val p = s.c[i - 89 + k]; if (p <= 0) { ok = false; break }; val y = Math.log(p); val x = k.toDouble(); sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y }
                if (ok) {
                    val m = 90.0; val b = (m * sxy - sx * sy) / (m * sxx - sx * sx)
                    val r2d = (m * sxx - sx * sx) * (m * syy - sy * sy)
                    val r2 = if (r2d > 0) Math.pow(m * sxy - sx * sy, 2.0) / r2d else 0.0
                    v[6] = (Math.pow(Math.exp(b), 250.0) - 1) * r2
                }
            }
            if (i >= 60) { var m = 0.0; for (k in i - 59..i) m += ret[k]; m /= 60; var q = 0.0; for (k in i - 59..i) q += (ret[k] - m) * (ret[k] - m); v[7] = -Math.sqrt(q / 59) }
            val t20 = turnover20(i); if (t20 > 0) v[8] = Math.log(t20)
            val v10 = sma(s.v, i, 10); val v60 = sma(s.v, i, 60); if (v60 > 0) v[9] = v10 / v60
            if (i >= 20) { var up = 0.0; var all = 0.0; for (k in i - 19..i) { all += s.v[k]; if (ret[k] > 0) up += s.v[k] }; if (all > 0) v[10] = up / all }
            if (i >= 15) {
                var g = 0.0; var l = 0.0
                for (k in i - 13..i) { val d = s.c[k] - s.c[k - 1]; if (d > 0) g += d else l -= d }
                v[11] = if (l <= 0) 100.0 else 100 - 100 / (1 + g / l)
            }
            if (i >= 60) { var pk = 0.0; for (k in i - 59..i) pk = maxOf(pk, s.c[k]); if (pk > 0) v[12] = c / pk - 1 }
            // 13, 14 (sector) filled cross-sectionally
            return v
        }
    }

    // ---------------------------------------------------------------- cross-sectional helpers

    /** percentile ranks 0..1 of finite values (ties averaged); NaN stays NaN */
    fun ranks(x: DoubleArray): DoubleArray {
        val idx = x.indices.filter { !x[it].isNaN() && !x[it].isInfinite() }.sortedBy { x[it] }
        val out = DoubleArray(x.size) { Double.NaN }
        if (idx.size < 2) { idx.forEach { out[it] = 0.5 }; return out }
        var k = 0
        while (k < idx.size) {
            var j = k; while (j + 1 < idx.size && x[idx[j + 1]] == x[idx[k]]) j++
            val r = (k + j) / 2.0 / (idx.size - 1)
            for (m in k..j) out[idx[m]] = r
            k = j + 1
        }
        return out
    }

    /** Pearson correlation of two rank vectors over pairs where both are finite */
    fun corr(a: DoubleArray, b: DoubleArray): Double {
        var n = 0; var sa = 0.0; var sb = 0.0
        for (k in a.indices) if (!a[k].isNaN() && !b[k].isNaN()) { n++; sa += a[k]; sb += b[k] }
        if (n < MIN_STOCKS) return Double.NaN
        val ma = sa / n; val mb = sb / n; var cov = 0.0; var va = 0.0; var vb = 0.0
        for (k in a.indices) if (!a[k].isNaN() && !b[k].isNaN()) { val x = a[k] - ma; val y = b[k] - mb; cov += x * y; va += x * x; vb += y * y }
        return if (va > 0 && vb > 0) cov / Math.sqrt(va * vb) else Double.NaN
    }

    private class Day(val p: Int, val day: Int, val members: IntArray, val pct: Array<DoubleArray>, val fwd: DoubleArray)

    /** composite of a day's percentile matrix with weights; NaN factors count as the middle (0.5) */
    private fun composite(pct: Array<DoubleArray>, k: Int, w: DoubleArray): Double {
        var s = 0.0; var any = false
        for (f in 0 until NF) if (w[f] != 0.0) { val v = pct[f][k]; s += w[f] * ((if (v.isNaN()) 0.5 else v) - 0.5); any = true }
        return if (any) s else Double.NaN
    }

    private fun weightsFrom(stats: List<FactorStat>, ics: List<DoubleArray>): DoubleArray {
        val w = DoubleArray(NF)
        for (f in 0 until NF) {
            val v = ics.map { it[f] }.filter { !it.isNaN() }
            if (v.size < 6) continue
            val m = v.average(); val sd = Math.sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
            if (sd <= 0) continue
            val ir = m / sd; val t = ir * Math.sqrt(v.size / (H.toDouble() / STEP))
            if (Math.abs(t) >= MIN_T) w[f] = ir * v.size / (v.size + 10.0)   // shrink toward 0 when history is short
        }
        return w
    }

    // ---------------------------------------------------------------- the run

    fun build(stocks: List<MarketStock>, sectors: Map<String, String>, fno: Set<String>): Result {
        val have = stocks.filter { it.s.n > START + H && it.exch == Bhav.NSE && (it.series == "EQ" || it.series == "BE" || it.series == "") }
        if (have.isEmpty()) return Result(0, 0, 0, FACTORS.map { FactorStat(it) }, 0, 0.0, 0.0, 0.0, 0.0, 0.0, emptyList())
        // calendar of sessions
        val days = java.util.TreeSet<Int>(); for (x in have) for (k in 0 until x.s.n) days.add(x.s.d[k])
        val cal = days.toIntArray(); val pos = HashMap<Int, Int>(); for ((k, d) in cal.withIndex()) pos[d] = k
        val nd = cal.size
        val feats = have.map { Feat(it.s) }
        // bar index of each stock at each calendar position (-1 when it did not trade that day)
        val at = Array(have.size) { j -> val a = IntArray(nd) { -1 }; val s = have[j].s; for (k in 0 until s.n) pos[s.d[k]]?.let { a[it] = k }; a }
        val secOf = have.map { sectors[it.sym] ?: "" }

        fun dayMatrix(p: Int, withFwd: Boolean): Day? {
            val mem = ArrayList<Int>()
            for (j in have.indices) {
                val i = at[j][p]; if (i < START) continue
                val s = have[j].s
                if (s.c[i] < MIN_PRICE || !(feats[j].turnover20(i) >= MIN_TURNOVER)) continue
                if (withFwd && (p + H >= nd || at[j][p + H] < 0)) continue
                mem.add(j)
            }
            if (mem.size < MIN_STOCKS) return null
            val raw = Array(NF) { DoubleArray(mem.size) { Double.NaN } }
            for ((k, j) in mem.withIndex()) { val v = feats[j].values(at[j][p]); for (f in 0 until NF) raw[f][k] = v[f] }
            // sector factors: average 3-month return per sector (sectors with 3+ members)
            val sSum = HashMap<String, Double>(); val sCnt = HashMap<String, Int>()
            for ((k, j) in mem.withIndex()) { val sc = secOf[j]; val r = raw[1][k]; if (sc.isNotEmpty() && !r.isNaN()) { sSum[sc] = (sSum[sc] ?: 0.0) + r; sCnt[sc] = (sCnt[sc] ?: 0) + 1 } }
            for ((k, j) in mem.withIndex()) {
                val sc = secOf[j]; val cnt = sCnt[sc] ?: 0
                if (cnt >= 3) { val avg = sSum[sc]!! / cnt; raw[13][k] = avg; if (!raw[1][k].isNaN()) raw[14][k] = raw[1][k] - avg }
            }
            val pct = Array(NF) { ranks(raw[it]) }
            val fwd = DoubleArray(mem.size) { Double.NaN }
            if (withFwd) for ((k, j) in mem.withIndex()) {
                val s = have[j].s; val i = at[j][p]; val e = at[j][p + H]
                if (s.c[i] > 0) { val r = s.c[e] / s.c[i] - 1; if (Math.abs(r) < 0.6) fwd[k] = r }   // >60% in 2 weeks = bad data, dropped
            }
            return Day(p, cal[p], mem.toIntArray(), pct, fwd)
        }

        // 1-2. IC history
        val stats = FACTORS.map { FactorStat(it) }
        val icDays = ArrayList<Pair<Day, DoubleArray>>()
        var p = START
        while (p + H < nd) {
            val d = dayMatrix(p, true)
            if (d != null) {
                val fr = ranks(d.fwd)
                val ic = DoubleArray(NF) { corr(d.pct[it], fr) }
                for (f in 0 until NF) if (!ic[f].isNaN()) stats[f].ics.add(ic[f])
                icDays.add(Pair(d, ic))
            }
            p += STEP
        }
        // 4. walk-forward test
        var oosN = 0; var spread = 0.0; var hits = 0; var icSum = 0.0; var topSum = 0.0; var allSum = 0.0
        for ((k, pair) in icDays.withIndex()) {
            val (d, _) = pair
            val prior = icDays.subList(0, k).filter { it.first.p + H <= d.p }.map { it.second }
            val w = weightsFrom(stats, prior)
            if (w.all { it == 0.0 }) continue
            val comp = DoubleArray(d.members.size) { composite(d.pct, it, w) }
            val ok = d.fwd.indices.filter { !d.fwd[it].isNaN() && !comp[it].isNaN() }
            if (ok.size < MIN_STOCKS) continue
            val top = ok.sortedByDescending { comp[it] }.take(maxOf(5, ok.size / 10))
            val tr = top.map { d.fwd[it] }.average(); val ar = ok.map { d.fwd[it] }.average()
            oosN++; spread += tr - ar; if (tr > ar) hits++; topSum += tr; allSum += ar
            icSum += corr(ranks(comp), ranks(d.fwd)).let { if (it.isNaN()) 0.0 else it }
        }
        // 3. final weights on the whole history
        val w = weightsFrom(stats, icDays.map { it.second })
        for (f in 0 until NF) stats[f].weight = w[f]
        // 5. today
        val last = nd - 1
        val today = dayMatrix(last, false)
        val rated = ArrayList<Rated>()
        if (today != null) {
            val comp = DoubleArray(today.members.size) { composite(today.pct, it, w) }
            val cr = ranks(comp)
            for ((k, j) in today.members.withIndex()) {
                val x = have[j]; val i = at[j][last]
                val pc = DoubleArray(NF) { val v = today.pct[it][k]; if (v.isNaN()) Double.NaN else Math.round(v * 1000) / 10.0 }
                val rt = if (cr[k].isNaN()) 0 else Math.round(cr[k] * 100).toInt()
                rated.add(Rated(x.sym, x.name, x.exch, secOf[j], x.sym in fno, x.s.c[i], rt, 0, comp[k], pc, feats[j].r(i, 21), feats[j].r(i, 63)))
            }
            rated.sortWith(compareByDescending<Rated> { if (it.score.isNaN()) Double.NEGATIVE_INFINITY else it.score }.thenBy { it.sym })
            for ((k, r) in rated.withIndex()) r.rank = k + 1
        }
        return Result(cal[last], nd, rated.size, stats, oosN, if (oosN > 0) spread / oosN else 0.0, if (oosN > 0) hits * 100.0 / oosN else 0.0,
            if (oosN > 0) icSum / oosN else 0.0, if (oosN > 0) topSum / oosN else 0.0, if (oosN > 0) allSum / oosN else 0.0, rated)
    }

    /** Contribution of each factor to a stock's composite (weight × centred percentile), largest first. */
    fun drivers(w: DoubleArray, pct: DoubleArray): List<Pair<Int, Double>> =
        (0 until NF).filter { w[it] != 0.0 && !pct[it].isNaN() }.map { Pair(it, w[it] * (pct[it] / 100.0 - 0.5)) }.sortedByDescending { Math.abs(it.second) }

    // ---------------------------------------------------------------- file format

    private fun r2(x: Double) = if (x.isNaN()) "" else (Math.round(x * 100) / 100.0).toString()
    private fun r4(x: Double) = if (x.isNaN()) "" else (Math.round(x * 10000) / 10000.0).toString()
    private fun q(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    private fun iso(d: Int) = if (d > 0) java.time.LocalDate.ofEpochDay(d.toLong()).toString() else ""
    val COLS = listOf("RANK", "SYMBOL", "NAME", "SECTOR", "FNO", "CLOSE", "RATING", "R1M", "R3M") + FACTORS.map { it.id.uppercase() }

    fun encode(r: Result, builtAt: Long): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append(",v=").append(VERSION).append(",asof=").append(iso(r.asof)).append(",builtAt=").append(builtAt)
            .append(",sessions=").append(r.sessions).append(",universe=").append(r.universe).append(",h=").append(H)
            .append(",oosDays=").append(r.oosDays).append(",oosSpread=").append(r4(r.oosSpread)).append(",oosHit=").append(r2(r.oosHit))
            .append(",oosIc=").append(r4(r.oosIc)).append(",oosTop=").append(r4(r.oosTopRet)).append(",oosAll=").append(r4(r.oosAllRet)).append('\n')
        for (s in r.stats) sb.append("#FACTOR,").append(s.f.id).append(',').append(q(s.f.name)).append(',').append(r4(s.mean)).append(',').append(r4(s.icir))
            .append(',').append(r2(s.tstat)).append(',').append(r4(s.weight)).append(',').append(s.ics.size).append(',').append(q(s.f.text)).append('\n')
        sb.append(COLS.joinToString(",")).append('\n')
        for (x in r.rated) sb.append(listOf(x.rank.toString(), x.sym, q(x.name), q(x.sector), if (x.fno) "1" else "0", r2(x.close), x.rating.toString(), r4(x.r1m), r4(x.r3m))
            .plus(x.pct.map { if (it.isNaN()) "" else (Math.round(it)).toString() }).joinToString(",")).append('\n')
        return sb.toString()
    }

    class Parsed(val meta: Map<String, String>, val factors: List<FactorStat>, val rows: List<Rated>, val bySym: Map<String, Rated>) {
        val asof: Int get() = Bhav.parseDay(meta["asof"])
        val weights: DoubleArray get() = DoubleArray(NF) { k -> factors.firstOrNull { it.f.id == FACTORS[k].id }?.weight ?: 0.0 }
        fun d(k: String) = meta[k]?.toDoubleOrNull() ?: 0.0
    }

    /** Parses rating.csv; null when it is not one. Never throws. */
    fun parse(text: String?): Parsed? {
        try {
            if (text == null || !text.startsWith(MAGIC)) return null
            val lines = text.split('\n').map { it.trimEnd('\r') }
            val meta = HashMap<String, String>()
            for (kv in lines[0].split(',').drop(1)) { val k = kv.indexOf('='); if (k > 0) meta[kv.substring(0, k)] = kv.substring(k + 1) }
            if ((meta["v"]?.toIntOrNull() ?: 0) != VERSION) return null
            val fs = ArrayList<FactorStat>(); val rows = ArrayList<Rated>(); var ix: Map<String, Int>? = null
            for (l in lines.drop(1)) {
                if (l.isBlank()) continue
                val r = StockList.splitCsv(l)
                when {
                    l.startsWith("#FACTOR,") -> {
                        val f = FACTORS.firstOrNull { it.id == r[1] } ?: Factor(r[1], r.getOrElse(2) { r[1] }, r.getOrElse(8) { "" })
                        val s = FactorStat(f); s.weight = r.getOrNull(6)?.toDoubleOrNull() ?: 0.0
                        s.pMean = r.getOrNull(3)?.toDoubleOrNull() ?: 0.0; s.pT = r.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                        fs.add(s)
                    }
                    l.startsWith("#") -> {}
                    ix == null -> { val m = HashMap<String, Int>(); for ((k, h) in r.withIndex()) m[h.trim()] = k; if (!listOf("SYMBOL", "RATING", "RANK").all { m.containsKey(it) }) return null; ix = m }
                    else -> {
                        val m = ix!!
                        fun s(c: String) = m[c]?.let { r.getOrNull(it)?.trim() } ?: ""
                        fun d(c: String) = s(c).toDoubleOrNull() ?: Double.NaN
                        val sym = s("SYMBOL").uppercase(); if (sym.isEmpty() || sym.length > 25) continue
                        rows.add(Rated(sym, s("NAME").ifEmpty { sym }, Bhav.NSE, s("SECTOR"), s("FNO") == "1", d("CLOSE"), s("RATING").toIntOrNull() ?: 0, s("RANK").toIntOrNull() ?: 0, Double.NaN,
                            DoubleArray(NF) { d(FACTORS[it].id.uppercase()) }, d("R1M"), d("R3M")))
                    }
                }
            }
            if (ix == null || rows.isEmpty()) return null
            return Parsed(meta, fs, rows, rows.associateBy { it.sym })
        } catch (t: Throwable) { return null }
    }
    fun publishedMean(s: FactorStat): Double = s.pMean ?: s.mean
    fun publishedT(s: FactorStat): Double = s.pT ?: s.tstat

    // ---------------------------------------------------------------- app views

    private fun pr(x: Double) = if (x.isNaN()) null else Math.round(x * 1000) / 10.0
    fun factorList(p: Parsed): List<Map<String, Any?>> = p.factors.map { s ->
        linkedMapOf("id" to s.f.id, "name" to s.f.name, "text" to s.f.text, "ic" to Math.round(publishedMean(s) * 1000) / 1000.0, "t" to Math.round(publishedT(s) * 10) / 10.0,
            "weight" to Math.round(s.weight * 1000) / 1000.0, "used" to (s.weight != 0.0))
    }
    fun meta(p: Parsed): Map<String, Any?> = linkedMapOf("asof" to p.meta["asof"], "builtAt" to (p.meta["builtAt"]?.toLongOrNull() ?: 0L), "sessions" to p.d("sessions").toInt(),
        "universe" to p.d("universe").toInt(), "h" to p.d("h").toInt(), "oosDays" to p.d("oosDays").toInt(), "oosSpreadPct" to pr(p.d("oosSpread")), "oosHit" to p.d("oosHit"),
        "oosIc" to Math.round(p.d("oosIc") * 1000) / 1000.0, "oosTopPct" to pr(p.d("oosTop")), "oosAllPct" to pr(p.d("oosAll")))

    fun row(p: Parsed, x: Rated, withDrivers: Boolean): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("sym" to x.sym, "name" to x.name, "sector" to x.sector, "fno" to x.fno, "close" to (if (x.close.isNaN()) null else x.close),
            "rating" to x.rating, "rank" to x.rank, "of" to p.rows.size, "r1m" to pr(x.r1m), "r3m" to pr(x.r3m))
        val dr = drivers(p.weights, x.pct)
        m["plus"] = dr.filter { it.second > 0 }.take(3).map { FACTORS[it.first].name }
        m["minus"] = dr.filter { it.second < 0 }.take(2).map { FACTORS[it.first].name }
        if (withDrivers) m["factors"] = (0 until NF).map { k ->
            linkedMapOf("id" to FACTORS[k].id, "name" to FACTORS[k].name, "pct" to (if (x.pct[k].isNaN()) null else x.pct[k]), "weight" to Math.round(p.weights[k] * 1000) / 1000.0,
                "contrib" to (if (x.pct[k].isNaN() || p.weights[k] == 0.0) 0.0 else Math.round(p.weights[k] * (x.pct[k] / 100.0 - 0.5) * 1000) / 1000.0))
        }
        return m
    }

    /** list view: [fno] only F&O, [sector] filter, [side] "top" (best first) or "bottom" (worst first), [limit] */
    fun list(p: Parsed?, fno: Boolean, sector: String, side: String, limit: Int): Map<String, Any?> {
        if (p == null) return linkedMapOf("ok" to true, "none" to true)
        var rows = p.rows.asSequence().filter { (!fno || it.fno) && (sector.isEmpty() || it.sector == sector) }.toList()
        if (side == "bottom") rows = rows.reversed()
        val secs = p.rows.groupBy { it.sector }.filter { it.key.isNotEmpty() && it.value.size >= 3 }
            .map { (k, v) -> linkedMapOf("name" to k, "n" to v.size, "avg" to Math.round(v.map { it.rating }.average())) }.sortedByDescending { it["avg"] as Long }
        return linkedMapOf("ok" to true, "none" to false, "meta" to meta(p), "factors" to factorList(p), "sectors" to secs,
            "count" to rows.size, "rows" to rows.take(limit.coerceIn(1, 500)).map { row(p, it, false) })
    }
}

/** Phone side: rating.csv cached in a file, refreshed at most every 3 hours when a screen asks for it. */
class RatingDesk(private val p: Platform) {
    @Volatile var data: FactorModel.Parsed? = null
        private set
    private var fetchedAt = 0L
    private var loaded = false

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        try { data = FactorModel.parse(p.read(FactorModel.FILE)) } catch (t: Throwable) {}
    }

    @Synchronized fun refresh(now: Long, force: Boolean = false): Boolean {
        load()
        if (!force && now - fetchedAt in 0 until GAP_MS) return false
        fetchedAt = now
        val text = try { p.httpGetAll(listOf(FactorModel.URL)).getOrNull(0) } catch (t: Throwable) { null } ?: return false
        val d = FactorModel.parse(text) ?: return false
        val cur = data
        if (cur != null && d.asof < cur.asof) return false
        try { p.write(FactorModel.FILE, text) } catch (t: Throwable) {}
        data = d
        return true
    }

    fun list(now: Long, fno: Boolean, sector: String, side: String, limit: Int): Map<String, Any?> {
        try { refresh(now) } catch (t: Throwable) {}
        load()
        return FactorModel.list(data, fno, sector, side, limit)
    }

    fun of(now: Long, sym: String): Map<String, Any?> {
        try { refresh(now) } catch (t: Throwable) {}
        load()
        val d = data ?: return linkedMapOf("ok" to true, "none" to true)
        val x = d.bySym[sym.uppercase()] ?: return linkedMapOf("ok" to true, "none" to false, "rated" to false, "sym" to sym, "meta" to FactorModel.meta(d))
        val m = LinkedHashMap(FactorModel.row(d, x, true)); m["ok"] = true; m["none"] = false; m["rated"] = true; m["meta"] = FactorModel.meta(d)
        return m
    }

    /** rating / rank of a symbol without network (for lists built elsewhere) */
    fun peek(sym: String): FactorModel.Rated? { load(); return data?.bySym?.get(sym.uppercase()) }

    companion object { const val GAP_MS = 3L * 3600 * 1000 }
}
