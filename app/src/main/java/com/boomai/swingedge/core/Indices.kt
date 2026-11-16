package com.boomai.swingedge.core

/** NSE index membership (data/indices.csv from the daily job): index name -> member symbols, in file order. */
object Indices {
    fun parse(csv: String?): LinkedHashMap<String, List<String>> {
        val out = LinkedHashMap<String, ArrayList<String>>()
        if (csv == null) return LinkedHashMap()
        try {
            for ((k, raw) in csv.replace("\r", "").split('\n').withIndex()) {
                if (k == 0 || raw.isBlank()) continue
                val i = raw.lastIndexOf(',')
                if (i <= 0) continue
                val name = raw.substring(0, i).trim().trim('"'); val sym = raw.substring(i + 1).trim().uppercase()
                if (name.isEmpty() || sym.isEmpty()) continue
                val l = out.getOrPut(name) { ArrayList() }
                if (sym !in l) l.add(sym)
            }
        } catch (t: Throwable) {}
        val res = LinkedHashMap<String, List<String>>()
        for ((k, v) in out) if (v.size >= 5) res[k] = v
        return res
    }

    /**
     * One index's members with the app's call: BUY (BUY-grade setup and the market gate open), WAIT, AVOID or NA.
     * Sorted: BUY first, then by setup score, unrated last.
     */
    fun view(idx: Map<String, List<String>>, name: String, flows: Map<String, FlowRow>, scores: Map<String, ScoreEntry>,
             gateOpen: Boolean, names: (String) -> String): Map<String, Any?> {
        val list = idx.keys.map { linkedMapOf("name" to it, "n" to (idx[it]?.size ?: 0)) }
        if (idx.isEmpty()) return linkedMapOf("ok" to true, "ready" to false, "indices" to list)
        val sel = if (idx.containsKey(name)) name else idx.keys.first()
        val rows = (idx[sel] ?: emptyList()).map { s ->
            val f = flows[s]; val e = scores[s]
            val rated = e != null && e.rated
            val call = when {
                !rated -> "NA"
                e!!.label == "BUY" && gateOpen -> "BUY"
                e.label == "BUY" -> "WAIT"
                else -> e.label
            }
            linkedMapOf("sym" to s, "name" to names(s), "close" to (f?.close ?: 0.0),
                "r1" to (f?.r1?.takeIf { !it.isNaN() } ?: 0.0), "r20" to (f?.r20?.takeIf { !it.isNaN() } ?: 0.0),
                "score" to (if (rated) e!!.score else -1.0), "call" to call)
        }.sortedWith(compareBy<Map<String, Any?>> { when (it["call"]) { "BUY" -> 0; "WAIT" -> 1; "AVOID" -> 2; else -> 3 } }
            .thenByDescending { it["score"] as Double }.thenBy { it["sym"] as String })
        return linkedMapOf("ok" to true, "ready" to true, "index" to sel, "indices" to list, "stocks" to rows,
            "buys" to rows.count { it["call"] == "BUY" }, "gateOpen" to gateOpen)
    }
}
