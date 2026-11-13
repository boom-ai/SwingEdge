package com.boomai.swingedge.core

/**
 * In-memory symbol search over every known NSE symbol (symbols.csv merged with universe.csv and aliases.csv).
 * Immutable once built; [find] is thread-safe and allocation-light. Nothing here throws.
 */
class SymbolIndex private constructor(
    private val syms: Array<String>,
    private val names: Array<String>,
    private val tracked: BooleanArray,
    /** lower-case symbol without punctuation */
    private val symKey: Array<String>,
    /** per name variant: its normalised words, and the words joined without spaces */
    private val nameWords: Array<Array<Array<String>>>,
    private val nameKey: Array<Array<String>>,
    /** fewest words among the name variants (a closer name match ranks first within a tier) */
    private val nameLen: IntArray,
    /** IST epoch day of listing (0 unknown) and board ("EQ" | "SME") from the stock list */
    private val listed: IntArray = IntArray(syms.size),
    private val board: Array<String> = Array(syms.size) { StockList.EQ }
) {
    val size: Int get() = syms.size

    fun nameOf(sym: String): String? {
        val k = bySym[sym] ?: return null
        return names[k]
    }

    fun has(sym: String): Boolean = bySym.containsKey(sym)

    /** OLD -> current symbol (NSE's symbol-change list, chains followed; only targets in this list). */
    private var changed: Map<String, String> = emptyMap()
    /** OLD -> merger, and the old company names of merged stocks (normalised words) */
    private var corp: Map<String, CorpAction> = emptyMap()
    private var corpNames: List<Pair<List<String>, CorpAction>> = emptyList()

    private fun setChanges(nse: Map<String, String>, acts: List<CorpAction>) {
        val m = HashMap<String, String>()
        for (old in nse.keys) {
            if (bySym.containsKey(old)) continue
            var cur = old; var hops = 0
            while (!bySym.containsKey(cur) && hops < 8) { cur = nse[cur] ?: break; hops++ }
            if (bySym.containsKey(cur) && cur != old) m[old] = cur
        }
        val c = HashMap<String, CorpAction>(); val names = ArrayList<Pair<List<String>, CorpAction>>()
        for (a in acts) {
            if (bySym.containsKey(a.old)) continue
            val to = if (bySym.containsKey(a.to)) a.to else m[a.to] ?: continue
            val fixed = CorpAction(a.old, to, a.num, a.den, a.extra?.let { e -> if (bySym.containsKey(e)) e else m[e] }, a.exNum, a.exDen, a.oldName, a.effective)
            c[a.old] = fixed
            val w = cwords(a.oldName)
            if (w.isNotEmpty()) names.add(Pair(w, fixed))
        }
        changed = m; corp = c; corpNames = names
    }

    private fun clean(raw: String): String = raw.trim().uppercase()
        .removePrefix("NSE:").removePrefix("BSE:").trim()
        .replace(Regex("(\\.NS|\\.BO|[- ](EQ|BE|SM|ST))$"), "").trim()

    private fun cwords(s: String): List<String> = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP }

    /** The merger behind what was typed (old symbol or old company name), when its shares were swapped for another stock's. */
    fun actionFor(raw: String): CorpAction? {
        try {
            val up = clean(raw)
            if (up.isEmpty() || bySym.containsKey(up)) return null
            corp[up]?.let { return it }
            val qw = cwords(up)
            if (qw.size < 2) return null
            for ((w, a) in corpNames) if (w.size >= qw.size && qw.indices.all { i -> if (i == qw.size - 1) w[i].startsWith(qw[i]) else w[i] == qw[i] }) return a
            return null
        } catch (t: Throwable) { return null }
    }

    /**
     * Maps what a user typed or a broker exported to the real NSE symbol: the symbol itself when it exists; else the
     * single symbol that starts with it ("OLA" -> "OLAELEC"); else the single company whose name starts with those words
     * ("SESHAASAI" -> "STYL", "GLOBAL HEALTH" -> "GLOBALHEALTH"). Null when nothing or more than one stock fits, so a
     * wrong guess is never made.
     */
    fun resolve(raw: String): String? {
        try {
            val up = clean(raw)
            if (up.isEmpty()) return null
            if (bySym.containsKey(up)) return up
            // official renames and mergers first: an old symbol must never be guessed into a different company by prefix
            changed[up]?.let { return it }
            actionFor(up)?.let { return it.to }
            val key = compact(up)
            if (key.length < 3) return null
            var hit = -1; var many = false
            for (k in syms.indices) if (symKey[k] == key) { if (hit >= 0) many = true; hit = k }
            if (hit >= 0 && !many) return syms[hit]
            hit = -1; many = false
            for (k in syms.indices) if (symKey[k].startsWith(key)) { if (hit >= 0 && syms[hit] != syms[k]) many = true; hit = k }
            if (hit >= 0 && !many) return syms[hit]
            val qw = up.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in STOP }
            if (qw.isEmpty()) return null
            hit = -1; many = false
            for (k in syms.indices) {
                var any = false
                for (v in nameWords[k]) {
                    if (v.size < qw.size) continue
                    var okAll = true
                    for (w in qw.indices) { val same = if (w == qw.size - 1) v[w].startsWith(qw[w]) else v[w] == qw[w]; if (!same) { okAll = false; break } }
                    if (okAll) { any = true; break }
                }
                if (!any) for (nk in nameKey[k]) if (nk.startsWith(key.lowercase())) { any = true; break }
                if (any) { if (hit >= 0 && syms[hit] != syms[k]) many = true; hit = k }
            }
            return if (hit >= 0 && !many) syms[hit] else null
        } catch (t: Throwable) {
            return null
        }
    }

    /** Listing day (IST epoch day) from the stock list, 0 when unknown. */
    fun listedOf(sym: String): Int { val k = bySym[sym] ?: return 0; return listed[k] }
    fun boardOf(sym: String): String { val k = bySym[sym] ?: return StockList.EQ; return board[k] }
    fun trackedOf(sym: String): Boolean { val k = bySym[sym] ?: return false; return tracked[k] }

    /** Symbols listed within the last [days] days of [today] (newest first, ties by symbol), at most [max]. */
    fun newListings(today: Int, days: Int, max: Int = 100): List<Map<String, Any?>> {
        try {
            val idx = ArrayList<Int>()
            for (k in syms.indices) { val l = listed[k]; if (l > 0 && today - l in 0..days) idx.add(k) }
            idx.sortWith(Comparator { a, b -> if (listed[a] != listed[b]) listed[b] - listed[a] else syms[a].compareTo(syms[b]) })
            val out = ArrayList<Map<String, Any?>>()
            for (k in idx) {
                if (out.size >= max) break
                out.add(linkedMapOf("sym" to syms[k], "name" to names[k], "listedDay" to StockPage.yyyymmdd(listed[k]), "board" to board[k], "tracked" to tracked[k]))
            }
            return out
        } catch (t: Throwable) {
            return emptyList()
        }
    }

    /** Rows listed within the last [NEW_DAYS] days of [today]. */
    fun newCount(today: Int): Int { var n = 0; for (l in listed) if (l > 0 && today - l in 0..NEW_DAYS) n++; return n }

    private val bySym = HashMap<String, Int>(syms.size * 2).also { m -> for ((k, s) in syms.withIndex()) m[s] = k }

    /** Up to [max] matches, best first: exact symbol, symbol prefix, name word-prefix, symbol contains, name contains. */
    fun find(qRaw: String, max: Int = MAX_RESULTS, today: Int = 0): List<Map<String, Any?>> {
        try {
            val q = if (qRaw.length > MAX_QUERY) qRaw.substring(0, MAX_QUERY) else qRaw
            val qSym = compact(q)
            if (qSym.length < 2) return emptyList()
            val qWords = words(q)
            val qName = qWords.joinToString("")
            val n = syms.size
            val tier = IntArray(n)
            var hits = 0
            for (k in 0 until n) {
                val sk = symKey[k]
                var t = 0
                if (sk == qSym) t = 1
                else if (sk.startsWith(qSym)) t = 2
                else if (qWords.isNotEmpty() && wordPrefix(qWords, nameWords[k])) t = 3
                else if (sk.contains(qSym)) t = 4
                else if (qName.length >= 2 && nameKey[k].any { it.contains(qName) }) t = 5
                tier[k] = t
                if (t > 0) hits++
            }
            if (hits == 0) return emptyList()
            val idx = ArrayList<Int>(hits)
            for (k in 0 until n) if (tier[k] > 0) idx.add(k)
            idx.sortWith(Comparator { a, b ->
                when {
                    tier[a] != tier[b] -> tier[a] - tier[b]
                    tracked[a] != tracked[b] -> if (tracked[a]) -1 else 1
                    nameLen[a] != nameLen[b] -> nameLen[a] - nameLen[b]
                    syms[a].length != syms[b].length -> syms[a].length - syms[b].length
                    else -> syms[a].compareTo(syms[b])
                }
            })
            val out = ArrayList<Map<String, Any?>>(minOf(max, idx.size))
            for (k in idx) {
                if (out.size >= max) break
                val l = listed[k]
                out.add(linkedMapOf("sym" to syms[k], "name" to names[k], "tracked" to tracked[k],
                    "listedDay" to (if (l > 0) StockPage.yyyymmdd(l) else 0), "isNew" to (l > 0 && today > 0 && today - l in 0..NEW_DAYS), "board" to board[k]))
            }
            return out
        } catch (t: Throwable) {
            return emptyList()
        }
    }

    private fun wordPrefix(q: Array<String>, variants: Array<Array<String>>): Boolean {
        for (w in variants) {
            var all = true
            for (x in q) {
                var hit = false
                for (y in w) if (y.startsWith(x)) { hit = true; break }
                if (!hit) { all = false; break }
            }
            if (all) return true
        }
        return false
    }

    companion object {
        const val MAX_RESULTS = 25
        const val MAX_QUERY = 80
        /** "isNew": listed within this many days */
        const val NEW_DAYS = 365
        private val STOP = setOf("ltd", "limited")

        /** Lower-case ASCII letters and digits only. */
        fun compact(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch0 in s) {
                val ch = if (ch0 in 'A'..'Z') ch0 + 32 else ch0
                if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch)
            }
            return sb.toString()
        }

        /** Normalised words: lower-case, punctuation as separators, "ltd"/"limited" dropped. */
        fun words(s: String): Array<String> {
            val out = ArrayList<String>(6)
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    val w = sb.toString()
                    if (w !in STOP) out.add(w)
                    sb.setLength(0)
                }
            }
            for (ch0 in s) {
                val ch = if (ch0 in 'A'..'Z') ch0 + 32 else ch0
                if (ch in 'a'..'z' || ch in '0'..'9') sb.append(ch) else flush()
            }
            flush()
            return out.toTypedArray()
        }

        /**
         * @param symbolsCsv the bundled every-NSE-symbol list (may be null/partial)
         * @param universe scanned symbols with their names (in list order)
         * @param extraNames more "SYMBOL,Name" pairs (aliases.csv); a symbol may appear several times
         */
        fun build(symbolsCsv: String?, universe: List<String>, universeNames: Map<String, String>, extraNames: String?): SymbolIndex =
            build(try { StockList.parse(symbolsCsv).rows } catch (t: Throwable) { emptyList() }, universe, universeNames, extraNames)

        /** Same, from already-parsed stock list rows (the live list). */
        fun build(rows: List<ListRow>, universe: List<String>, universeNames: Map<String, String>, extraNames: String?,
                  changesCsv: String? = null, corpCsv: String? = null): SymbolIndex {
            val order = ArrayList<String>()
            val primary = HashMap<String, String>()
            val primaryRank = HashMap<String, Int>()
            val variants = HashMap<String, LinkedHashSet<String>>()
            val listedBy = HashMap<String, Int>()
            val boardBy = HashMap<String, String>()
            /** rank: 0 symbols.csv, 1 universe.csv, 2 aliases.csv. A real company name beats one that repeats the symbol. */
            fun addName(sym: String, nameRaw: String, rank: Int) {
                val name = nameRaw.replace("\uFEFF", "").trim().trim('"').trim()
                if (!variants.containsKey(sym)) { variants[sym] = LinkedHashSet(); order.add(sym) }
                if (name.isEmpty()) return
                variants[sym]!!.add(name)
                val r = if (name.equals(sym, true)) rank + 10 else rank
                val cur = primaryRank[sym]
                if (cur == null || r < cur) { primary[sym] = name; primaryRank[sym] = r }
            }
            fun cleanSym(raw: String): String? = StockList.cleanSym(raw)
            try {
                for (row in rows) {
                    val sym = cleanSym(row.sym) ?: continue
                    addName(sym, row.name, 0)
                    if (!listedBy.containsKey(sym)) { listedBy[sym] = row.listed; boardBy[sym] = row.board }
                }
            } catch (t: Throwable) {}
            try {
                for (sym in universe) addName(sym, universeNames[sym] ?: "", 1)
            } catch (t: Throwable) {}
            try {
                if (extraNames != null) for (raw in extraNames.split('\n')) {
                    val line = raw.trim()
                    val k = line.indexOf(',')
                    if (k <= 0) continue
                    val sym = cleanSym(line.substring(0, k)) ?: continue
                    addName(sym, line.substring(k + 1), 2)
                }
            } catch (t: Throwable) {}
            val uni = universe.toHashSet()
            val n = order.size
            val syms = Array(n) { order[it] }
            val names = Array(n) { primary[order[it]] ?: order[it] }
            val tracked = BooleanArray(n) { uni.contains(order[it]) }
            val symKey = Array(n) { compact(order[it]) }
            val nameWords = Array(n) { k -> (variants[order[k]] ?: LinkedHashSet()).map { words(it) }.toTypedArray() }
            val nameKey = Array(n) { k -> nameWords[k].map { it.joinToString("") }.toTypedArray() }
            val nameLen = IntArray(n) { k -> nameWords[k].minOfOrNull { it.size } ?: 99 }
            val listed = IntArray(n) { listedBy[order[it]] ?: 0 }
            val board = Array(n) { if (boardBy[order[it]] == StockList.SME) StockList.SME else StockList.EQ }
            val ix = SymbolIndex(syms, names, tracked, symKey, nameWords, nameKey, nameLen, listed, board)
            try { ix.setChanges(SymbolChanges.parseNse(changesCsv), SymbolChanges.parseCorp(corpCsv)) } catch (t: Throwable) {}
            return ix
        }
    }
}
