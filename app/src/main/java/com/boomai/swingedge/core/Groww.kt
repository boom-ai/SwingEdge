package com.boomai.swingedge.core

/**
 * Tick-by-tick public quotes used by the Groww web app (no login): index levels in one batch call, option
 * contract last prices in one batch call, and the option chain (only to learn contract ids). NSE's own API is
 * CDN-cached 10–60 s, so these are used for the live screen; NSE stays the fallback. Pure Kotlin, never throws.
 */
object Groww {
    const val INDEX_URL = "https://groww.in/v1/api/stocks_data/v1/tr_live_delayed/segment/CASH/latest_aggregated"
    const val PRICES_URL = "https://groww.in/v1/api/stocks_fo_data/v1/tr_live_prices/exchange/NSE/segment/FNO/latest_prices_batch"
    fun chainUrl(sym: String, isoExpiry: String) = "https://groww.in/v1/api/option_chain_service/v1/option_chain/${sym.lowercase()}?expiry=$isoExpiry"

    /** Ticker names shown in the app -> Groww index symbols. */
    val TICKER = listOf("NIFTY" to "Nifty 50", "BANKNIFTY" to "Bank Nifty", "FINNIFTY" to "Fin Nifty", "MIDCPNIFTY" to "Midcap Nifty", "INDIAVIX" to "India VIX")

    fun indexBody(syms: List<String>) = Json.stringify(mapOf("exchangeAggReqMap" to mapOf("NSE" to mapOf("priceSymbolList" to emptyList<String>(), "indexSymbolList" to syms))))

    private fun num(v: Any?): Double? = when (v) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** Index batch -> ticker rows in TICKER order (name, last, chg, pct, high, low, tsMs). */
    fun parseIndices(text: String?): List<Map<String, Any?>> = try {
        val m = ((((Json.parseObject(text ?: "")?.get("exchangeAggRespMap") as? Map<*, *>)?.get("NSE") as? Map<*, *>)?.get("indexLivePointsMap")) as? Map<*, *>) ?: emptyMap<Any, Any>()
        TICKER.mapNotNull { (sym, name) ->
            val r = m[sym] as? Map<*, *> ?: return@mapNotNull null
            val v = num(r["value"]) ?: return@mapNotNull null
            if (v <= 0) return@mapNotNull null
            linkedMapOf("name" to name, "sym" to sym, "last" to v, "chg" to Fno.r2(num(r["dayChange"]) ?: 0.0), "pct" to Fno.r2(num(r["dayChangePerc"]) ?: 0.0),
                "high" to num(r["high"]), "low" to num(r["low"]), "ts" to ((num(r["tsInMillis"]) ?: 0.0) * 1000).toLong())
        }
    } catch (t: Throwable) { emptyList() }

    /** Contract ids per strike from the chain: strike -> (CE id, PE id). Strikes come x100. */
    fun parseIds(text: String?): Map<Int, Pair<String, String>> = try {
        val rows = ((Json.parseObject(text ?: "")?.get("optionChain") as? Map<*, *>)?.get("optionChains") as? List<*>).orEmpty()
        val out = HashMap<Int, Pair<String, String>>()
        for (r in rows) {
            val m = r as? Map<*, *> ?: continue
            val k = num(m["strikePrice"]) ?: continue
            val ce = (m["callOption"] as? Map<*, *>)?.get("growwContractId") as? String ?: continue
            val pe = (m["putOption"] as? Map<*, *>)?.get("growwContractId") as? String ?: continue
            out[Math.round(k / 100).toInt()] = Pair(ce, pe)
        }
        out
    } catch (t: Throwable) { emptyMap() }

    /** Batch prices -> id -> (ltp, tsMs, dayChange). */
    fun parsePrices(text: String?): Map<String, Triple<Double, Long, Double>> = try {
        val o = Json.parseObject(text ?: "") ?: emptyMap()
        val out = HashMap<String, Triple<Double, Long, Double>>()
        for ((k, v) in o) {
            val m = v as? Map<*, *> ?: continue
            val l = num(m["ltp"]) ?: continue
            if (l <= 0) continue
            out[k] = Triple(l, ((num(m["lastTradeTime"]) ?: num(m["tsInMillis"]) ?: 0.0) * 1000).toLong(), num(m["dayChange"]) ?: 0.0)
        }
        out
    } catch (t: Throwable) { emptyMap() }

    /** NSE chain + live last prices -> a fresh chain. Bid/ask keep NSE's spread around the live price (tick 0.05). */
    fun overlay(base: FnoChain, spot: Double?, live: Map<Int, Pair<Triple<Double, Long, Double>?, Triple<Double, Long, Double>?>>, now: Long, ts: String): FnoChain {
        fun q(old: FnoQuote?, lv: Triple<Double, Long, Double>?): FnoQuote? {
            if (lv == null) return old
            val l = lv.first
            val half = if (old != null && old.ask > old.bid && old.bid > 0) Math.min((old.ask - old.bid) / 2, Math.max(0.05, l * 0.02)) else Math.max(0.05, l * 0.002)
            val bid = Math.max(0.05, tick(l - half)); val ask = Math.max(bid + 0.05, tick(l + half))
            return FnoQuote(l, Fno.r2(bid), Fno.r2(ask), old?.iv ?: 0.0, old?.oi ?: 0.0, old?.vol ?: 0.0, if (old != null) Fno.r2(old.chg + (l - old.ltp)) else lv.third)
        }
        val ce = HashMap(base.ce); val pe = HashMap(base.pe)
        for ((k, v) in live) { q(base.ce[k], v.first)?.let { ce[k] = it }; q(base.pe[k], v.second)?.let { pe[k] = it } }
        return FnoChain(base.sym, base.expiry, spot ?: base.spot, ts, now, base.strikes, ce, pe, base.expiries)
    }
    private fun tick(x: Double) = Math.round(x * 20) / 20.0
}
