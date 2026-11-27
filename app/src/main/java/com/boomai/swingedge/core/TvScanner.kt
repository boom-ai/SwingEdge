package com.boomai.swingedge.core

/**
 * TradingView's public screener (no login): every NSE stock in one POST. Free data is ~15 minutes delayed
 * (update_mode "delayed_streaming_900"), so screens built on it must say so. Pure Kotlin, never throws.
 * Verified live (GitHub Actions probe): 200, rows {"s":"NSE:SYM","d":[columns in request order]}.
 */
object TvScanner {
    const val URL = "https://scanner.tradingview.com/india/scan"
    val HEADERS = mapOf(
        "Content-Type" to "application/json", "Accept" to "application/json",
        "Origin" to "https://in.tradingview.com", "Referer" to "https://in.tradingview.com/")
    /** Delay of the free feed, minutes (shown on screen). */
    const val DELAY_MIN = 15
    const val MAX_BYTES = 3_000_000

    /** Request body: NSE stocks only (ETFs/funds excluded by type), biggest first, [columns] in order. */
    fun body(columns: List<String>, max: Int = 4000): String = Json.stringify(linkedMapOf(
        "filter" to listOf(
            linkedMapOf("left" to "exchange", "operation" to "equal", "right" to "NSE"),
            linkedMapOf("left" to "type", "operation" to "equal", "right" to "stock")),
        "options" to linkedMapOf("lang" to "en"),
        "markets" to listOf("india"),
        "symbols" to linkedMapOf("query" to linkedMapOf("types" to emptyList<String>()), "tickers" to emptyList<String>()),
        "columns" to columns,
        "sort" to linkedMapOf("sortBy" to "market_cap_basic", "sortOrder" to "desc"),
        "range" to listOf(0, max)))

    /** One call; null on any failure or non-200. */
    fun fetch(p: Platform, columns: List<String>, max: Int = 4000): String? = try {
        val r = p.httpRequest("POST", URL, HEADERS, body(columns, max), MAX_BYTES)
        if (r != null && r.code == 200 && r.body.startsWith("{")) r.body else null
    } catch (t: Throwable) { null }

    /** Response -> rows of (symbol without exchange prefix, column name -> value). Never throws. */
    fun parse(text: String?, columns: List<String>): List<Pair<String, Map<String, Any?>>> = try {
        val out = ArrayList<Pair<String, Map<String, Any?>>>()
        for (x in (Json.parseObject(text ?: "")?.get("data") as? List<*>).orEmpty()) {
            val m = x as? Map<*, *> ?: continue
            val s = (m["s"] as? String)?.trim() ?: continue
            val d = m["d"] as? List<*> ?: continue
            val sym = s.substringAfter(':').uppercase()
            if (sym.isEmpty() || sym.length > 25) continue
            val row = HashMap<String, Any?>(columns.size * 2)
            for ((k, c) in columns.withIndex()) row[c] = d.getOrNull(k)
            out.add(Pair(sym, row))
        }
        out
    } catch (t: Throwable) { emptyList() }
}
