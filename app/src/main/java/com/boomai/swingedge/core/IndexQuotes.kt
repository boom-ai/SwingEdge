package com.boomai.swingedge.core

/** Live index levels for the Today tiles: one NSE allIndices call covers every NSE index; Yahoo for Sensex / fallbacks. */
object IndexQuotes {
    class Def(val id: String, val label: String, val nse: String?, val yahoo: String?)

    val CATALOG = listOf(
        Def("NIFTY50", "Nifty 50", "NIFTY 50", "^NSEI"),
        Def("BANKNIFTY", "Bank Nifty", "NIFTY BANK", "^NSEBANK"),
        Def("SENSEX", "Sensex", null, "^BSESN"),
        Def("NIFTY100", "Large cap 100", "NIFTY 100", "^CNX100"),
        Def("NEXT50", "Nifty Next 50", "NIFTY NEXT 50", "^NSMIDCP"),
        Def("MID100", "Midcap 100", "NIFTY MIDCAP 100", "NIFTY_MIDCAP_100.NS"),
        Def("MID150", "Midcap 150", "NIFTY MIDCAP 150", null),
        Def("MIDSEL", "Midcap Select", "NIFTY MIDCAP SELECT", null),
        Def("SMALL100", "Smallcap 100", "NIFTY SMALLCAP 100", "^CNXSC"),
        Def("SMALL250", "Smallcap 250", "NIFTY SMALLCAP 250", null),
        Def("NIFTY500", "Nifty 500", "NIFTY 500", "^CRSLDX"),
        Def("FIN", "Financial Services", "NIFTY FINANCIAL SERVICES", "NIFTY_FIN_SERVICE.NS"),
        Def("PVTBANK", "Private Bank", "NIFTY PRIVATE BANK", null),
        Def("PSUBANK", "PSU Bank", "NIFTY PSU BANK", "^CNXPSUBANK"),
        Def("IT", "Nifty IT", "NIFTY IT", "^CNXIT"),
        Def("PHARMA", "Pharma", "NIFTY PHARMA", "^CNXPHARMA"),
        Def("HEALTH", "Healthcare", "NIFTY HEALTHCARE INDEX", null),
        Def("AUTO", "Auto", "NIFTY AUTO", "^CNXAUTO"),
        Def("FMCG", "FMCG", "NIFTY FMCG", "^CNXFMCG"),
        Def("METAL", "Metal", "NIFTY METAL", "^CNXMETAL"),
        Def("ENERGY", "Energy", "NIFTY ENERGY", "^CNXENERGY"),
        Def("OILGAS", "Oil & Gas", "NIFTY OIL & GAS", null),
        Def("REALTY", "Realty", "NIFTY REALTY", "^CNXREALTY"),
        Def("INFRA", "Infra", "NIFTY INFRASTRUCTURE", "^CNXINFRA"),
        Def("MEDIA", "Media", "NIFTY MEDIA", "^CNXMEDIA"),
        Def("CONSDUR", "Consumer Durables", "NIFTY CONSUMER DURABLES", null),
        Def("DEFENCE", "Defence", "NIFTY INDIA DEFENCE", null),
        Def("CPSE", "CPSE", "NIFTY CPSE", null),
        Def("VIX", "India VIX", "INDIA VIX", "^INDIAVIX"),
    )
    val DEFAULT = listOf("NIFTY50", "BANKNIFTY", "SENSEX", "NIFTY100")
    const val NSE_PATH = "/api/allIndices"

    fun norm(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]"), "")

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.replace(",", "").trim().toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** normalized NSE index name -> (last, change, pct) */
    fun parseNse(txt: String?): Map<String, Triple<Double, Double, Double>> {
        val o = Json.parseObject(txt ?: return emptyMap()) ?: return emptyMap()
        val out = HashMap<String, Triple<Double, Double, Double>>()
        for (r in (o["data"] as? List<*>).orEmpty()) {
            val m = r as? Map<*, *> ?: continue
            val name = (m["index"] ?: m["indexSymbol"]) as? String ?: continue
            val last = num(m["last"]) ?: continue
            if (last <= 0) continue
            val prev = num(m["previousClose"])
            val chg = num(m["variation"]) ?: if (prev != null && prev > 0) last - prev else 0.0
            val pct = num(m["percentChange"]) ?: if (prev != null && prev > 0) (last - prev) / prev * 100 else 0.0
            out[norm(name)] = Triple(last, chg, pct)
        }
        return out
    }

    fun yahooUrl(sym: String) = "https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(sym, "UTF-8") + "?range=5d&interval=1d"

    fun parseYahoo(txt: String?): Triple<Double, Double, Double>? {
        val o = Json.parseObject(txt ?: return null) ?: return null
        val res = ((o["chart"] as? Map<*, *>)?.get("result") as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val meta = res["meta"] as? Map<*, *> ?: return null
        val last = num(meta["regularMarketPrice"]) ?: return null
        val prev = num(meta["chartPreviousClose"]) ?: num(meta["previousClose"])
        // range=5d: chartPreviousClose is 5 days back, so take the prior daily close instead
        val closes = (((res["indicators"] as? Map<*, *>)?.get("quote") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("close") as? List<*>
        val cs = closes.orEmpty().mapNotNull { num(it) }
        val pc = if (cs.size >= 2) cs[cs.size - 2] else prev
        if (last <= 0) return null
        val chg = if (pc != null && pc > 0) last - pc else 0.0
        return Triple(last, chg, if (pc != null && pc > 0) chg / pc * 100 else 0.0)
    }
}
