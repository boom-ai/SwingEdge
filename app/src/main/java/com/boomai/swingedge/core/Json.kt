package com.boomai.swingedge.core

/**
 * Minimal JSON reader/writer. Objects -> LinkedHashMap<String, Any?>, arrays -> ArrayList<Any?>,
 * numbers -> Double, strings -> String, booleans -> Boolean, null -> null.
 * The writer never emits NaN/Infinity (writes 0) and prints whole numbers without a fraction.
 */
object Json {

    class ParseException(msg: String) : RuntimeException(msg)

    private const val MAX_DEPTH = 200

    /** Strict parse; throws [ParseException] on any malformed input. */
    fun parse(text: String): Any? {
        val r = Reader(text)
        r.skipWs()
        val v = r.value(0)
        r.skipWs()
        if (r.i < r.s.length) throw ParseException("Trailing characters at ${r.i}")
        return v
    }

    /** Lenient parse; returns null on malformed input (and for a JSON null). */
    fun parseOrNull(text: String?): Any? {
        if (text == null) return null
        return try {
            parse(text)
        } catch (t: Throwable) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String?): Map<String, Any?>? = parseOrNull(text) as? Map<String, Any?>

    private class Reader(val s: String) {
        var i = 0
        init {
            if (s.isNotEmpty() && s[0] == '﻿') i = 1
        }

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("Too deep")
            if (i >= s.length) throw ParseException("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || (c in '0'..'9')) num() else throw ParseException("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("Bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Any {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return m
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw ParseException("Expected key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw ParseException("Expected ':' at $i")
                i++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (i >= s.length) throw ParseException("Unexpected end in object")
                val c = s[i++]
                if (c == '}') return m
                if (c != ',') throw ParseException("Expected ',' or '}' at ${i - 1}")
            }
        }

        private fun arr(depth: Int): Any {
            val a = ArrayList<Any?>()
            i++
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return a
            }
            while (true) {
                skipWs()
                a.add(value(depth + 1))
                skipWs()
                if (i >= s.length) throw ParseException("Unexpected end in array")
                val c = s[i++]
                if (c == ']') return a
                if (c != ',') throw ParseException("Expected ',' or ']' at ${i - 1}")
            }
        }

        private fun str(): String {
            i++ // opening quote
            val start = i
            // fast path: no escapes
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    val out = s.substring(start, i)
                    i++
                    return out
                }
                if (c == '\\') break
                if (c < ' ') throw ParseException("Control character in string at $i")
                i++
            }
            val sb = StringBuilder(s.length.coerceAtMost(i - start + 32))
            sb.append(s, start, i)
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw ParseException("Bad escape at end")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("Bad \\u escape")
                                var cp = 0
                                for (k in 0 until 4) {
                                    val h = s[i + k]
                                    val d = when (h) {
                                        in '0'..'9' -> h - '0'
                                        in 'a'..'f' -> h - 'a' + 10
                                        in 'A'..'F' -> h - 'A' + 10
                                        else -> throw ParseException("Bad hex in \\u escape")
                                    }
                                    cp = cp * 16 + d
                                }
                                i += 4
                                sb.append(cp.toChar())
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw ParseException("Control character in string at ${i - 1}")
                    else -> sb.append(c)
                }
            }
            throw ParseException("Unterminated string")
        }

        private fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            val intStart = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == intStart) throw ParseException("Bad number at $start")
            if (i < s.length && s[i] == '.') {
                i++
                val fs = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == fs) throw ParseException("Bad fraction at $start")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                val es = i
                while (i < s.length && s[i] in '0'..'9') i++
                if (i == es) throw ParseException("Bad exponent at $start")
            }
            val d = try {
                s.substring(start, i).toDouble()
            } catch (t: Throwable) {
                throw ParseException("Bad number at $start")
            }
            return if (d.isNaN() || d.isInfinite()) 0.0 else d
        }
    }

    // ------------------------------------------------------------------ writer

    fun stringify(v: Any?): String {
        val sb = StringBuilder(256)
        write(sb, v)
        return sb.toString()
    }

    fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> num(sb, v)
            is Float -> num(sb, v.toDouble())
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Number -> num(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.asList())
            is DoubleArray -> write(sb, v.asList())
            is IntArray -> write(sb, v.asList())
            is LongArray -> write(sb, v.asList())
            else -> quote(sb, v.toString())
        }
    }

    private fun num(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append('0')
            return
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append(d.toLong())
            return
        }
        val a = Math.abs(d)
        if (a >= 1e-3 && a < 1e15) {
            // plain decimal, up to 6 decimals, no exponent, trailing zeros trimmed
            val neg = d < 0
            var scaled = Math.round(a * 1_000_000.0)
            val whole = scaled / 1_000_000
            scaled %= 1_000_000
            if (neg && (whole != 0L || scaled != 0L)) sb.append('-')
            sb.append(whole)
            if (scaled != 0L) {
                sb.append('.')
                val frac = scaled.toString().padStart(6, '0').trimEnd('0')
                sb.append(frac)
            }
            return
        }
        sb.append(d.toString()) // very small / very large: Kotlin prints valid JSON exponent form (e.g. 1.0E-5)
    }

    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c < ' ' || c == ' ' || c == ' ' || c == '\u007F') {
                        sb.append("\\u")
                        val h = c.code.toString(16)
                        for (k in h.length until 4) sb.append('0')
                        sb.append(h)
                    } else sb.append(c) // surrogate pairs pass through verbatim (UTF-16 round-trip)
                }
            }
        }
        sb.append('"')
    }
}

// ---------------------------------------------------------------- typed accessors (never throw)

@Suppress("UNCHECKED_CAST")
internal fun Any?.asObj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()

internal fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()

internal fun Map<String, Any?>.num(key: String, def: Double = 0.0): Double {
    val v = this[key]
    val d = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: def
        else -> def
    }
    return if (d.isNaN() || d.isInfinite()) def else d
}

internal fun Map<String, Any?>.long(key: String, def: Long = 0L): Long {
    val v = this[key]
    return when (v) {
        is Number -> {
            val d = v.toDouble()
            if (d.isNaN() || d.isInfinite()) def else d.toLong()
        }
        is String -> v.trim().toDoubleOrNull()?.toLong() ?: def
        else -> def
    }
}

internal fun Map<String, Any?>.int(key: String, def: Int = 0): Int = long(key, def.toLong()).toInt()

internal fun Map<String, Any?>.str(key: String, def: String = ""): String {
    val v = this[key]
    return when (v) {
        is String -> v
        null -> def
        is Double -> if (v == Math.rint(v)) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}

internal fun Map<String, Any?>.bool(key: String, def: Boolean = false): Boolean {
    val v = this[key]
    return when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.equals("true", true) || v == "1"
        else -> def
    }
}

internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> = this[key].asObj()
internal fun Map<String, Any?>.list(key: String): List<Any?> = this[key].asList()
internal fun Map<String, Any?>.strList(key: String): List<String> = list(key).mapNotNull { it as? String }
