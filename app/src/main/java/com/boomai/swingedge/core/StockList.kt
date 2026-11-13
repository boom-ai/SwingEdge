package com.boomai.swingedge.core

/** One row of the every-NSE-symbol list. [listed] = IST epoch day of listing (0 = unknown); [board] = "EQ" or "SME". */
class ListRow(val sym: String, val name: String, val listed: Int, val board: String)

/** A whole stock list and where it came from (SPEC5 §1). [updatedAt] = epoch ms of the download (0 = bundled / unknown). */
class StockListData(val rows: List<ListRow>, val source: String, val updatedAt: Long) {
    val mainCount: Int = rows.count { it.board == StockList.EQ }
}

/** Result of parsing one CSV text. format: "nse" (NSE archive, any board), "four" (SYMBOL,NAME,LISTED,BOARD), "two" (SYMBOL,Name) or "" (nothing usable). */
class ParsedList(val rows: List<ListRow>, val format: String, val html: Boolean, val updatedAt: Long = 0L, val source: String = "")

/**
 * Parsing, validation and the download chain of the NSE stock list. Accepts the NSE archive CSVs (EQUITY_L / SME_EQUITY_L,
 * headers with leading spaces or underscores), the 4-column app format and the old 2-column format. Pure; never throws.
 */
object StockList {
    const val NSE_MAIN_URL = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
    const val NSE_SME_URL = "https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv"
    const val GITHUB_URL = "https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/symbols.csv"
    const val SAVED_FILE = "symbols_live.csv"
    const val EQ = "EQ"
    const val SME = "SME"
    const val SRC_NSE = "nse"
    const val SRC_GITHUB = "github"
    const val SRC_SAVED = "saved"
    const val SRC_BUNDLED = "bundled"
    /** A downloaded list must have at least this many main-board rows to replace the current one. */
    const val MIN_MAIN_ROWS = 1500
    /** The SME file is merged only when it has at least this many rows (else the previous SME rows are kept). */
    const val MIN_SME_ROWS = 100
    /** Daily refresh runs on the first tick at or after this IST minute. */
    const val REFRESH_MIN = 7 * 60 + 30
    const val STALE_MS = 3L * 24 * 3600_000L

    private val SYM_RE = Regex("^[A-Z0-9&][A-Z0-9&\\-]*$")
    private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    fun cleanSym(raw: String): String? {
        val s = raw.replace("﻿", "").trim().trim('"').trim().uppercase()
        if (s.isEmpty() || s == "SYMBOL" || s.length > 25 || !SYM_RE.matches(s)) return null
        return s
    }

    private fun cleanName(raw: String): String = raw.replace("﻿", "").trim().trim('"').trim()

    /** CSV fields of one line ("" quotes and doubled quotes inside them handled). */
    fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var q = false
        var k = 0
        while (k < line.length) {
            val ch = line[k]
            if (q) {
                if (ch == '"') { if (k + 1 < line.length && line[k + 1] == '"') { sb.append('"'); k++ } else q = false }
                else sb.append(ch)
            } else when (ch) {
                '"' -> q = true
                ',' -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(ch)
            }
            k++
        }
        out.add(sb.toString())
        return out
    }

    /** 06-OCT-2008, 27-Jun-25, 2025-06-27 or 27/06/2025 -> IST epoch day; 0 if not a date. */
    fun parseDate(raw: String?): Int {
        try {
            val s = (raw ?: return 0).trim().trim('"').trim()
            if (s.isEmpty()) return 0
            val p = s.split('-', '/', ' ').filter { it.isNotEmpty() }
            if (p.size != 3) return 0
            var y: Int; val m: Int; val d: Int
            if (p[0].length == 4) { y = p[0].toInt(); m = p[1].toInt(); d = p[2].toInt() }
            else {
                d = p[0].toInt()
                val mi = MONTHS.indexOf(p[1].uppercase().take(3))
                m = if (mi >= 0 && p[1].length >= 3) mi + 1 else p[1].toInt()
                y = p[2].toInt()
                if (p[2].length <= 2) y += if (y <= 79) 2000 else 1900
            }
            if (y < 1875 || y > 2200 || m !in 1..12 || d !in 1..31) return 0
            return java.time.LocalDate.of(y, m, d).toEpochDay().toInt()
        } catch (t: Throwable) {
            return 0
        }
    }

    /** dd-MMM-yyyy, upper case (the bundled asset's style); "" for 0. */
    fun formatDate(day: Int): String = try {
        if (day <= 0) "" else {
            val d = java.time.LocalDate.ofEpochDay(day.toLong())
            d.dayOfMonth.toString().padStart(2, '0') + "-" + MONTHS[d.monthValue - 1] + "-" + d.year
        }
    } catch (t: Throwable) { "" }

    fun looksLikeHtml(text: String): Boolean {
        val head = (if (text.length > 600) text.substring(0, 600) else text).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<") || head.contains("<html") || head.contains("<!doctype") || head.contains("<body")
    }

    private fun normHeader(h: String): String =
        h.replace("﻿", "").trim().trim('"').trim().uppercase().replace('_', ' ').replace(Regex("\\s+"), " ")

    /**
     * Parses any supported format. [defaultBoard] is used when the text has no BOARD column (NSE SME file -> "SME").
     * Rows with an invalid symbol are skipped; a symbol listed twice keeps its first row.
     */
    fun parse(text: String?, defaultBoard: String = EQ): ParsedList {
        try {
            if (text == null || text.isBlank()) return ParsedList(emptyList(), "", false)
            if (looksLikeHtml(text)) return ParsedList(emptyList(), "", true)
            val lines = text.split('\n')
            var k = 0
            var meta = ""
            // leading comment lines ("# source=nse updatedAt=123" in the saved file)
            while (k < lines.size) {
                val l = lines[k].replace("﻿", "").trim()
                if (l.isEmpty()) { k++; continue }
                if (l.startsWith("#")) { if (meta.isEmpty()) meta = l; k++; continue }
                break
            }
            if (k >= lines.size) return ParsedList(emptyList(), "", false)
            val first = splitCsv(lines[k].replace("﻿", "").trim()).map { normHeader(it) }
            val headered = first.isNotEmpty() && first[0] == "SYMBOL"
            var iName = 1; var iDate = -1; var iBoard = -1; var iSeries = -1
            var format = "two"
            var restIsName = true
            if (headered) {
                iName = first.indexOfFirst { it == "NAME OF COMPANY" || it == "NAME" || it == "COMPANY NAME" }
                iDate = first.indexOfFirst { it == "DATE OF LISTING" || it == "LISTED" || it == "LISTING DATE" }
                iBoard = first.indexOf("BOARD")
                iSeries = first.indexOf("SERIES")
                format = when {
                    iBoard >= 0 && iDate >= 0 && iName >= 0 -> "four"
                    iDate >= 0 && iName >= 0 && first.contains("NAME OF COMPANY") -> "nse"
                    first.size <= 2 -> "two"
                    else -> ""
                }
                if (format == "") return ParsedList(emptyList(), "", false)
                restIsName = first.size <= 2
                if (iName < 0) iName = 1
                k++
            }
            val seen = HashSet<String>()
            val rows = ArrayList<ListRow>()
            for (j in k until lines.size) {
                val line = lines[j].replace("﻿", "").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (restIsName) {
                    val c = line.indexOf(',')
                    val sym = cleanSym(if (c < 0) line else line.substring(0, c)) ?: continue
                    if (!seen.add(sym)) continue
                    rows.add(ListRow(sym, if (c < 0) "" else cleanName(line.substring(c + 1)), 0, defaultBoard))
                    continue
                }
                val f = splitCsv(line)
                val sym = cleanSym(f[0]) ?: continue
                if (!seen.add(sym)) continue
                val name = if (iName in f.indices) cleanName(f[iName]) else ""
                val listed = if (iDate in f.indices) parseDate(f[iDate]) else 0
                val board = when {
                    iBoard in f.indices -> if (f[iBoard].trim().trim('"').trim().equals(SME, true)) SME else EQ
                    iSeries in f.indices && f[iSeries].trim().uppercase() in setOf("SM", "ST", "SZ") -> SME
                    else -> defaultBoard
                }
                rows.add(ListRow(sym, name, listed, board))
            }
            var updatedAt = 0L
            var source = ""
            if (meta.isNotEmpty()) for (tok in meta.removePrefix("#").trim().split(' ')) {
                val e = tok.indexOf('=')
                if (e <= 0) continue
                val key = tok.substring(0, e); val v = tok.substring(e + 1)
                if (key == "updatedAt") updatedAt = v.toLongOrNull() ?: 0L
                if (key == "source") source = v
            }
            return ParsedList(rows, format, false, updatedAt, source)
        } catch (t: Throwable) {
            return ParsedList(emptyList(), "", false)
        }
    }

    /** NSE main-board file: right header, not HTML, at least [MIN_MAIN_ROWS] rows. Every row is board EQ. */
    fun validNseMain(text: String?): List<ListRow>? {
        val p = parse(text, EQ)
        if (p.html || p.format != "nse") return null
        val rows = p.rows.map { ListRow(it.sym, it.name, it.listed, EQ) }
        return if (rows.size >= MIN_MAIN_ROWS) rows else null
    }

    /** NSE SME file: right header, not HTML, at least [MIN_SME_ROWS] rows. Every row is board SME. */
    fun validNseSme(text: String?): List<ListRow>? {
        val p = parse(text, SME)
        if (p.html || p.format != "nse") return null
        val rows = p.rows.map { ListRow(it.sym, it.name, it.listed, SME) }
        return if (rows.size >= MIN_SME_ROWS) rows else null
    }

    /** 4-column file (GitHub mirror or the saved copy): right header, not HTML, at least [MIN_MAIN_ROWS] EQ rows. */
    fun validFour(text: String?): ParsedList? {
        val p = parse(text, EQ)
        if (p.html || p.format != "four") return null
        return if (p.rows.count { it.board == EQ } >= MIN_MAIN_ROWS) p else null
    }

    /** Main board first, then SME rows whose symbol is not already present. */
    fun merge(main: List<ListRow>, sme: List<ListRow>): List<ListRow> {
        val seen = HashSet<String>(main.size * 2)
        val out = ArrayList<ListRow>(main.size + sme.size)
        for (r in main) if (seen.add(r.sym)) out.add(r)
        for (r in sme) if (seen.add(r.sym)) out.add(r)
        return out
    }

    /**
     * The download chain: NSE (main + SME, one parallel round), else the GitHub mirror. [prev] supplies the SME rows when
     * only the NSE main file is valid. Returns null when nothing valid came back (the caller keeps its list).
     */
    fun download(http: (List<String>) -> List<String?>, prev: List<ListRow>, now: Long): StockListData? {
        try {
            val r = try { http(listOf(NSE_MAIN_URL, NSE_SME_URL)) } catch (t: Throwable) { emptyList<String?>() }
            val main = validNseMain(r.getOrNull(0))
            if (main != null) {
                val sme = validNseSme(r.getOrNull(1)) ?: prev.filter { it.board == SME }
                return StockListData(merge(main, sme), SRC_NSE, now)
            }
            val g = try { http(listOf(GITHUB_URL)) } catch (t: Throwable) { emptyList<String?>() }
            val four = validFour(g.getOrNull(0))
            if (four != null) return StockListData(four.rows, SRC_GITHUB, now)
            return null
        } catch (t: Throwable) {
            return null
        }
    }

    /** The saved-file text: a meta comment line, then the 4-column format. */
    fun encode(d: StockListData): String {
        val sb = StringBuilder(d.rows.size * 48 + 64)
        sb.append("# source=").append(d.source).append(" updatedAt=").append(d.updatedAt).append('\n')
        sb.append("SYMBOL,NAME,LISTED,BOARD\n")
        for (r in d.rows) {
            val nm = r.name.replace("\n", " ").replace("\r", " ")
            sb.append(r.sym).append(',')
            if (nm.contains(',') || nm.contains('"')) sb.append('"').append(nm.replace("\"", "\"\"")).append('"') else sb.append(nm)
            sb.append(',').append(formatDate(r.listed)).append(',').append(r.board).append('\n')
        }
        return sb.toString()
    }

    /** The saved copy, if it is a valid list. */
    fun decodeSaved(text: String?): StockListData? {
        val p = validFour(text) ?: return null
        return StockListData(p.rows, SRC_SAVED, p.updatedAt)
    }
}
