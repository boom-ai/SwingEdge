package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

object FactorTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf("factor-helpers" to ::helpers, "factor-planted" to ::planted, "factor-noise" to ::noise, "factor-csv" to ::csv)

    private fun market(n: Int, days: Int, seed: Long, drift: (Int) -> Double): List<MarketStock> {
        val rnd = java.util.Random(seed)
        return (0 until n).map { j ->
            val s = Series("S$j", days + 1); var p = 100.0 + j; var d = 20000
            val mu = drift(j)
            for (k in 0 until days) {
                while (!Clock.isWeekday(d)) d++
                val o = p; p *= 1 + mu + rnd.nextGaussian() * 0.015
                s.add(d, o, maxOf(o, p) * 1.005, minOf(o, p) * 0.995, p, 2e6 + rnd.nextInt(1000000)); d++
            }
            MarketStock("S$j", Bhav.NSE, "", "", "EQ", "Stock $j", s, 0)
        }
    }

    fun helpers() {
        val r = FactorModel.ranks(doubleArrayOf(3.0, 1.0, Double.NaN, 2.0, 2.0))
        T.check(r[1] == 0.0 && r[0] == 1.0 && r[2].isNaN() && r[3] == 0.5 && r[4] == 0.5, "ranks: ties averaged, NaN kept: ${r.toList()}")
        val a = DoubleArray(80) { it.toDouble() }; val b = DoubleArray(80) { -it.toDouble() }
        T.check(Math.abs(FactorModel.corr(a, a) - 1) < 1e-9 && Math.abs(FactorModel.corr(a, b) + 1) < 1e-9, "corr of ranks = ±1")
        T.check(FactorModel.corr(DoubleArray(10) { it.toDouble() }, DoubleArray(10) { it.toDouble() }).isNaN(), "too few stocks = no IC")
    }

    /** persistent drift (some stocks trend up, some down): momentum / trend factors must carry the weight and win out of sample */
    fun planted() {
        val st = market(160, 270, 7L) { j -> (j % 9 - 4) * 0.0006 }
        val r = FactorModel.build(st, emptyMap(), setOf("S1", "S2"))
        val w = r.stats.associate { it.f.id to it.weight }
        T.check(r.universe == 160 && r.rated.first().rank == 1 && r.rated.last().rank == 160, "every liquid stock rated and ranked")
        T.check((w["mom3"] ?: 0.0) > 0 || (w["clen"] ?: 0.0) > 0 || (w["trend"] ?: 0.0) > 0, "a momentum/trend factor gets positive weight: $w")
        T.check(r.oosDays >= 10 && r.oosSpread > 0 && r.oosHit > 50, "walk-forward: top 10% beat the market (${r.oosDays} days, spread ${"%.4f".format(r.oosSpread)}, hit ${r.oosHit})")
        val topDrift = r.rated.take(16).map { (it.sym.drop(1).toInt() % 9 - 4) }.average()
        T.check(topDrift > 1.5, "top-rated are the high-drift stocks (avg drift bucket ${"%.2f".format(topDrift)})")
        T.check(r.rated.all { it.rating in 0..100 } && r.rated.first().rating >= 95, "ratings 0-100, best near 100")
        T.check(r.rated.first { it.sym == "S1" }.fno, "F&O flag carried")
    }

    /** pure noise: no factor should survive the t-stat gate on most seeds, and the out-of-sample spread is small */
    fun noise() {
        val st = market(150, 270, 11L) { 0.0 }
        val r = FactorModel.build(st, emptyMap(), emptySet())
        val used = r.stats.count { it.weight != 0.0 }
        T.check(used <= 3, "noise market: few factors pass the evidence gate ($used)")
        T.check(Math.abs(r.oosSpread) < 0.01, "noise market: no real out-of-sample edge (${"%.4f".format(r.oosSpread)})")
    }

    fun csv() {
        val r = FactorModel.build(market(120, 260, 3L) { j -> (j % 5 - 2) * 0.0008 }, mapOf("S1" to "Banks", "S6" to "Banks", "S11" to "Banks"), setOf("S3"))
        val text = FactorModel.encode(r, 123L)
        val p = FactorModel.parse(text)
        T.check(p != null && p.rows.size == r.rated.size && p.rows[0].sym == r.rated[0].sym && p.rows[0].rating == r.rated[0].rating, "rating.csv round trip")
        p!!
        T.check(p.factors.size == FactorModel.NF && p.weights.indices.all { Math.abs(p.weights[it] - r.stats[it].weight) < 1e-3 }, "weights survive the round trip")
        val v = FactorModel.list(p, false, "", "top", 10)
        T.check(v["count"] == r.rated.size && (v["rows"] as List<*>).size == 10, "list view: count + limit")
        val f = FactorModel.list(p, true, "", "top", 10); T.check(f["count"] == 1, "F&O filter")
        val b = FactorModel.list(p, false, "", "bottom", 3); T.check(((b["rows"] as List<*>)[0] as Map<*, *>)["sym"] == r.rated.last().sym, "bottom = worst first")
        val row = FactorModel.row(p, p.rows[0], true); T.check((row["factors"] as List<*>).size == FactorModel.NF && row["of"] == r.rated.size, "stock row with factor breakdown")
        T.check(FactorModel.parse("junk") == null && FactorModel.parse(null) == null && FactorModel.parse(text.replace(",v=1,", ",v=9,")) == null, "bad files rejected")
        T.check(FactorModel.list(null, false, "", "top", 5)["none"] == true, "no file = none")
    }
}
