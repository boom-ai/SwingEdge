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
    private val nameLen: IntArray
) {
    val size: Int get() = syms.size

    fun nameOf(sym: String): String? {
        val k = bySym[sym] ?: return null
        return names[k]
    }

    private val bySym = HashMap<String, Int>(syms.size * 2).also { m -> for ((k, s) in syms.withIndex()) m[s] = k }

    /** Up to [max] matches, best first: exact symbol, symbol prefix, name word-prefix, symbol contains, name contains. */
    fun find(qRaw: String, max: Int = MAX_RESULTS): List<Map<String, Any?>> {
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
                out.add(linkedMapOf("sym" to syms[k], "name" to names[k], "tracked" to tracked[k]))
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
        private val SYM_RE = Regex("^[A-Z0-9&][A-Z0-9&\\-]*$")
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
        fun build(symbolsCsv: String?, universe: List<String>, universeNames: Map<String, String>, extraNames: String?): SymbolIndex {
            val order = ArrayList<String>()
            val primary = HashMap<String, String>()
            val primaryRank = HashMap<String, Int>()
            val variants = HashMap<String, LinkedHashSet<String>>()
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
            fun cleanSym(raw: String): String? {
                val s = raw.replace("﻿", "").trim().trim('"').trim().uppercase()
                if (s.isEmpty() || s == "SYMBOL" || s.length > 25 || !SYM_RE.matches(s)) return null
                return s
            }
            try {
                if (symbolsCsv != null) for (raw in symbolsCsv.split('\n')) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val k = line.indexOf(',')
                    val sym = cleanSym(if (k < 0) line else line.substring(0, k)) ?: continue
                    addName(sym, if (k < 0) "" else line.substring(k + 1), 0)
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
            return SymbolIndex(syms, names, tracked, symKey, nameWords, nameKey, nameLen)
        }
    }
}
