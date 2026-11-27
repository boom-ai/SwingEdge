package com.boomai.swingedge.core

import java.net.URLEncoder

/**
 * Mirae Asset m.Stock "Trading API - Type A" client (https://tradingapi.mstock.com/docs/v1/typeA/).
 *
 * Auth (as documented): POST connect/login (username+password) -> OTP by SMS (or skip when TOTP is enabled) ->
 * POST session/token (api_key, request_token = OTP, checksum) or POST session/verifytotp (api_key, totp) -> access_token.
 * Calls then send  "X-Mirae-Version: 1"  and  "Authorization: token <api_key>:<access_token>".
 * The documented token lives until midnight of the day it was created, so a fresh login is needed every day.
 *
 * Safety: the password is never stored; the API key / access token are written only to the private file [FILE], are
 * never logged, never put in a URL and are scrubbed from every message returned to the UI. Every call is https with a
 * timeout and returns a [Res] instead of throwing. Order placement exists ([placeOrder]) but is NOT wired to an app action.
 */
class MStock(private val p: Platform) {
    class Res<T>(val ok: Boolean, val data: T?, val error: String) {
        companion object {
            fun <T> good(d: T): Res<T> = Res(true, d, "")
            fun <T> bad(msg: String): Res<T> = Res(false, null, msg)
        }
    }

    class BHolding(val sym: String, val name: String, val isin: String, val qty: Double, val avg: Double, val ltp: Double, val prevClose: Double, val token: Long)
    class BPosition(val sym: String, val exchange: String, val product: String, val qty: Double, val avg: Double, val ltp: Double, val pnl: Double, val m2m: Double)
    class BFunds(val available: Double, val used: Double, val clear: Double, val total: Double, val rows: List<Map<String, Any?>>)
    class BQuote(val ltp: Double, val open: Double, val high: Double, val low: Double, val prevClose: Double)
    class MasterRow(val token: Long, val tradingSymbol: String, val name: String)

    // ---- state (persisted in FILE, never logged) ----
    @Volatile private var apiKey = ""
    @Volatile private var clientCode = ""
    @Volatile private var accessToken = ""
    @Volatile private var loginAt = 0L
    @Volatile private var expiresAt = 0L
    /** An OTP was requested at this time (ms); 0 = none pending. Memory only. */
    @Volatile private var otpAskedAt = 0L
    private val lock = Any()
    @Volatile private var master: Map<String, MasterRow>? = null            // app symbol -> row
    @Volatile private var masterByToken: Map<Long, String> = emptyMap()

    init { load() }

    // =========================================================================== status

    fun now(): Long = try { p.now() } catch (t: Throwable) { System.currentTimeMillis() }
    fun hasKey(): Boolean = apiKey.isNotEmpty()
    fun clientCodeSaved(): String = clientCode
    fun expiresAtMs(): Long = expiresAt
    /** True when a session exists and has not passed its expiry. */
    fun connected(): Boolean = accessToken.isNotEmpty() && apiKey.isNotEmpty() && now() < expiresAt
    fun otpPending(): Boolean = otpAskedAt > 0 && now() - otpAskedAt < OTP_WINDOW_MS && !connected()
    fun sessionExpired(): Boolean = accessToken.isNotEmpty() && now() >= expiresAt

    fun status(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        val c = connected()
        m["broker"] = "mStock (Mirae Asset)"
        m["connected"] = c
        m["otpPending"] = otpPending()
        m["apiKeySaved"] = hasKey()
        m["clientCode"] = clientCode
        m["sessionExpiresAt"] = if (c) expiresAt else 0L
        m["message"] = when {
            c -> "Connected to mStock. The login lasts until midnight."
            otpPending() -> "OTP sent to your registered mobile. Enter it to finish connecting."
            sessionExpired() -> "Your mStock session ended at midnight. Connect again to refresh it."
            hasKey() -> "Not connected. Enter your password and OTP/TOTP to connect."
            else -> "Not connected. Add your mStock API key and client code to connect."
        }
        return m
    }

    // =========================================================================== login / session

    /**
     * One entry point for the app action. [totp] set -> login + TOTP verification in one go.
     * Otherwise [otp] empty -> login, an SMS OTP is sent (needsOtp). [otp] set -> finish the session started by that login.
     * Returns true in data when connected, false when an OTP is still needed.
     */
    fun connect(key: String, code: String, password: String, otp: String, totp: String, checksum: String = CHECKSUM): Res<Boolean> {
        try {
            val k = key.trim().ifEmpty { apiKey }
            val c = code.trim().ifEmpty { clientCode }
            if (k.isEmpty()) return Res.bad("Enter your mStock API key first. You create it at trade.mstock.com under Trading APIs.")
            if (k.length > 200 || k.any { it.isWhitespace() }) return Res.bad("That API key does not look right. Copy it again without spaces.")
            if (otp.isNotEmpty() && totp.isEmpty()) {
                if (!otpPending()) return Res.bad("No OTP is waiting. Enter your password to receive a new OTP first.")
                if (!otp.trim().matches(Regex("[0-9]{4,10}"))) return Res.bad("The OTP should be the digits from the SMS.")
                synchronized(lock) { apiKey = k; if (c.isNotEmpty()) clientCode = c }
                return finish(k, form("api_key" to k, "request_token" to otp.trim(), "checksum" to checksum.ifEmpty { CHECKSUM }), "session/token")
            }
            if (c.isEmpty()) return Res.bad("Enter your mStock client code (login ID).")
            if (password.isEmpty()) return Res.bad("Enter your mStock password.")
            if (totp.isNotEmpty() && !totp.trim().matches(Regex("[0-9]{4,10}"))) return Res.bad("The TOTP should be the digits shown in your authenticator app.")
            synchronized(lock) { apiKey = k; clientCode = c; persist() }
            val r = rawCall("POST", "connect/login", form("username" to c, "password" to password), authed = false, key = k)
            val j = r.data
            if (!r.ok || j == null) return Res.bad(r.error)
            if (j.str("status") != "success") return Res.bad(failText(j, "mStock did not accept the client code and password."))
            if (totp.isNotEmpty()) return finish(k, form("api_key" to k, "totp" to totp.trim()), "session/verifytotp")
            otpAskedAt = now()
            return Res.good(false)
        } catch (t: Throwable) {
            return Res.bad("Something went wrong while connecting to mStock.")
        }
    }

    private fun finish(k: String, body: String, path: String): Res<Boolean> {
        val r = rawCall("POST", path, body, authed = false, key = k)
        val j = r.data
        if (!r.ok || j == null) return Res.bad(r.error)
        if (j.str("status") != "success") return Res.bad(failText(j, "mStock did not accept that code."))
        val tok = j.obj("data").str("access_token")
        if (tok.isEmpty()) return Res.bad("mStock accepted the code but sent no session. Try connecting again.")
        val t = now()
        synchronized(lock) {
            apiKey = k; accessToken = tok; loginAt = t
            expiresAt = (Clock.epochDay(t) + 1).toLong() * Clock.DAY_MS - Clock.IST_OFFSET_MS   // next midnight IST
            otpAskedAt = 0L
            persist()
        }
        return Res.good(true)
    }

    /** Ends the session (best effort on the server) and forgets the token. The API key and client code stay unless [forget]. */
    fun disconnect(forget: Boolean = false) {
        try {
            if (connected()) rawCall("GET", "logout", null, authed = true, key = apiKey)
        } catch (t: Throwable) {}
        synchronized(lock) {
            accessToken = ""; loginAt = 0L; expiresAt = 0L; otpAskedAt = 0L
            if (forget) { apiKey = ""; clientCode = "" }
            persist()
        }
    }

    // =========================================================================== read APIs

    fun holdings(): Res<List<BHolding>> {
        val r = authedGet("portfolio/holdings", "")
        val j = r.data ?: return Res.bad(r.error)
        loadMasterCached()
        val out = ArrayList<BHolding>()
        for (e in j.list("data")) {
            val m = e.asObj()
            if (m.isEmpty()) continue
            val token = m.long("instrument_token", 0L)
            val tsym = m.str("tradingsymbol").trim()
            val qty = m.num("quantity") + m.num("t1_quantity")
            val sym = masterByToken[token] ?: symbolLike(tsym)
            out.add(BHolding(sym, tsym, m.str("isin"), qty, m.num("average_price"), m.num("last_price"), m.num("close_price"), token))
        }
        return Res.good(out)
    }

    fun positions(): Res<List<BPosition>> {
        val r = authedGet("portfolio/positions", "")
        val j = r.data ?: return Res.bad(r.error)
        loadMasterCached()
        val d = j.obj("data")
        val out = ArrayList<BPosition>()
        for (e in d.list("net")) {
            val m = e.asObj()
            val q = m.num("quantity")
            if (m.isEmpty() || q == 0.0) continue
            val token = m.long("instrument_token", 0L)
            val sym = masterByToken[token] ?: HoldingsImport.cleanSymbol(m.str("tradingsymbol"))
            out.add(BPosition(sym, m.str("exchange"), m.str("product"), q, m.num("average_price"), m.num("last_price"), m.num("pnl"), m.num("m2m")))
        }
        return Res.good(out)
    }

    fun funds(): Res<BFunds> {
        val r = authedGet("user/fundsummary", "")
        val j = r.data ?: return Res.bad(r.error)
        val rows = j.list("data").map { it.asObj() }.filter { it.isNotEmpty() }
        val row = rows.firstOrNull { it.str("LIMIT_TYPE").equals("CAPITAL", true) } ?: rows.firstOrNull()
            ?: return Res.bad("mStock returned no funds information.")
        return Res.good(BFunds(row.num("AVAILABLE_BALANCE"), row.num("AMOUNT_UTILIZED"), row.num("CLEAR_BALANCE"), row.num("SUM_OF_ALL"), rows))
    }

    /** Order book (read only; not exposed as an app action). */
    fun orders(): Res<List<Map<String, Any?>>> {
        val r = authedGet("orders", "")
        val j = r.data ?: return Res.bad(r.error)
        return Res.good(j.list("data").map { it.asObj() })
    }

    /**
     * Live OHLC/last price for NSE cash symbols (app symbols such as RELIANCE), [MAX_PER_CALL] per request.
     * Symbols the broker does not know are simply absent from the result. [Res.error] is set only when nothing could be read.
     */
    fun quotes(syms: List<String>): Res<Map<String, BQuote>> {
        val want = syms.map { it.trim().uppercase() }.filter { it.matches(SYM_OK) }.distinct().take(MAX_TOTAL)
        if (want.isEmpty()) return Res.good(emptyMap())
        if (!connected()) return Res.bad(notConnectedText())
        loadMasterCached()
        val out = LinkedHashMap<String, BQuote>()
        var firstErr = ""
        var anyOk = false
        // first pass: master name when known, else SYMBOL-EQ; second pass retries the unknown ones as the bare symbol
        val keyOf = HashMap<String, String>()
        for (s in want) keyOf[s] = master?.get(s)?.tradingSymbol ?: "$s-EQ"
        val firstPass = quotePass(want, keyOf, out)
        if (firstPass.error.isNotEmpty()) firstErr = firstPass.error else anyOk = true
        if (firstErr.isEmpty()) {
            val retry = want.filter { !out.containsKey(it) && master?.get(it) == null }
            if (retry.isNotEmpty()) {
                val k2 = HashMap<String, String>(); for (s in retry) k2[s] = s
                val second = quotePass(retry, k2, out)
                if (second.error.isNotEmpty() && out.isEmpty()) firstErr = second.error
            }
        }
        if (!anyOk && out.isEmpty()) return Res.bad(firstErr.ifEmpty { "mStock sent no prices." })
        return Res.good(out)
    }

    private fun quotePass(syms: List<String>, keyOf: Map<String, String>, out: MutableMap<String, BQuote>): Res<Unit> {
        var err = ""
        var okCount = 0
        for (chunk in syms.chunked(MAX_PER_CALL)) {
            val q = chunk.joinToString("&") { "i=" + enc("NSE:" + (keyOf[it] ?: it)) }
            val r = authedGet("instruments/quote/ohlc", q)
            val j = r.data
            if (j == null) { if (err.isEmpty()) err = r.error; if (!connected()) break; continue }
            okCount++
            val byKey = HashMap<String, String>()
            for (s in chunk) byKey[(keyOf[s] ?: s).uppercase()] = s
            for ((k, v) in j.obj("data")) {
                val m = v.asObj()
                val ltp = m.num("last_price")
                if (ltp <= 0) continue
                val ts = k.substringAfter(':').uppercase()
                val sym = byKey[ts] ?: HoldingsImport.cleanSymbol(ts).takeIf { chunk.contains(it) } ?: continue
                val o = m.obj("ohlc")
                out[sym] = BQuote(ltp, o.num("open"), o.num("high"), o.num("low"), o.num("close"))
            }
        }
        return if (okCount == 0 && err.isNotEmpty()) Res.bad(err) else Res.good(Unit)
    }

    // =========================================================================== orders (client only, NOT an app action)

    /** Places an order. Deliberately not reachable from the app; kept for a later, explicitly confirmed feature. */
    fun placeOrder(variety: String, sym: String, buy: Boolean, orderType: String, qty: Int, product: String, validity: String, price: Double, tag: String = ""): Res<String> {
        if (variety != "regular" && variety != "amo") return Res.bad("Order type must be regular or amo.")
        if (!sym.uppercase().matches(SYM_OK)) return Res.bad("That symbol is not valid.")
        if (orderType !in setOf("MARKET", "LIMIT", "SL", "SL-M")) return Res.bad("Unsupported order type.")
        if (product !in setOf("CNC", "NRML", "MIS", "MTF")) return Res.bad("Unsupported product type.")
        if (validity !in setOf("DAY", "IOC")) return Res.bad("Unsupported validity.")
        if (qty < 1 || qty > 1_000_000) return Res.bad("Quantity must be at least 1.")
        if (orderType != "MARKET" && !(price > 0)) return Res.bad("Enter a price for this kind of order.")
        loadMasterCached()
        val ts = master?.get(sym.uppercase())?.tradingSymbol ?: (sym.uppercase() + "-EQ")
        val body = form("exchange" to "NSE", "tradingsymbol" to ts, "transaction_type" to if (buy) "BUY" else "SELL", "order_type" to orderType,
            "quantity" to qty.toString(), "product" to product, "validity" to validity, "price" to (if (orderType == "MARKET") "0" else price.toString()), "tag" to tag.take(20))
        val r = authedPost("orders/$variety", body)
        val j = r.data ?: return Res.bad(r.error)
        val id = j.obj("data").str("order_id")
        return if (id.isEmpty()) Res.bad("mStock did not return an order number.") else Res.good(id)
    }

    // =========================================================================== instrument master

    /** Loads the cached NSE equity list; downloads it from mStock at most once per day when connected. Never throws. */
    fun loadMasterCached(): Boolean {
        try {
            val day = Clock.epochDay(now())
            if (master != null && masterDay == day) return true
            if (master == null) {
                val txt = try { p.read(MASTER_FILE) } catch (t: Throwable) { null }
                if (txt != null) {
                    val d = txt.substringBefore('\n').removePrefix("#day=").trim().toIntOrNull()
                    val rows = if (d != null) parseMaster(txt.substringAfter('\n', "")) else emptyMap()
                    if (d != null && rows.isNotEmpty()) { setMaster(rows, d); if (d == day) return true }
                }
            }
            if (!connected()) return master != null
            val t = now()
            if (t - lastMasterTry < 10 * 60_000L) return master != null
            lastMasterTry = t
            val r = rawCall("GET", "instruments/scriptmaster", null, authed = true, key = apiKey, maxBytes = MASTER_MAX_BYTES, wantText = true)
            val csv = r.text
            if (!r.ok || csv == null) return master != null
            val rows = parseMaster(csv)
            if (rows.isEmpty()) return master != null
            setMaster(rows, day)
            try { p.write(MASTER_FILE, writeCache(day, rows)) } catch (t2: Throwable) {}
            return true
        } catch (t: Throwable) { return master != null }
    }

    @Volatile private var masterDay = -1
    @Volatile private var lastMasterTry = 0L
    private fun setMaster(m: Map<String, MasterRow>, day: Int) {
        val bt = HashMap<Long, String>(m.size * 2)
        for ((k, v) in m) bt[v.token] = k
        masterByToken = bt
        master = m
        masterDay = day
    }

    fun masterSize(): Int = master?.size ?: 0

    private fun writeCache(day: Int, rows: Map<String, MasterRow>): String {
        val sb = StringBuilder(rows.size * 40 + 20)
        sb.append("#day=").append(day).append('\n')
        sb.append("instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\n")
        for ((_, r) in rows) sb.append(r.token).append(",,").append(csvCell(r.tradingSymbol)).append(',').append(csvCell(r.name)).append(",,,,,,EQ,EQ,NSE\n")
        return sb.toString()
    }

    private fun csvCell(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** NSE cash-equity rows of the script master (columns found by header name) -> app symbol -> row. */
    fun parseMaster(csv: String): Map<String, MasterRow> {
        val out = LinkedHashMap<String, MasterRow>()
        try {
            var hdr: List<String>? = null
            var iTok = -1; var iSym = -1; var iName = -1; var iType = -1; var iExch = -1
            for (line in csv.lineSequence()) {
                if (line.isBlank()) continue
                val cells = HoldingsImport.splitLine(line.trimEnd('\r'), ',')
                if (hdr == null) {
                    hdr = cells.map { it.trim().lowercase() }
                    iTok = hdr.indexOf("instrument_token"); iSym = hdr.indexOf("tradingsymbol"); iName = hdr.indexOf("name")
                    iType = hdr.indexOf("instrument_type"); iExch = hdr.indexOf("exchange")
                    if (iTok < 0 || iSym < 0 || iExch < 0) return emptyMap()
                    continue
                }
                if (cells.size <= maxOf(iTok, iSym, iExch)) continue
                if (!cells[iExch].trim().equals("NSE", true)) continue
                val type = if (iType >= 0 && iType < cells.size) cells[iType].trim().uppercase() else "EQ"
                if (type != "EQ" && type != "BE") continue
                val ts = cells[iSym].trim()
                val tok = cells[iTok].trim().toDoubleOrNull()?.toLong() ?: continue
                val sym = HoldingsImport.cleanSymbol(ts)
                if (!sym.matches(SYM_OK)) continue
                val prev = out[sym]
                if (prev != null && !(type == "EQ" && !ts.uppercase().endsWith("-BE"))) continue   // prefer the EQ series
                out[sym] = MasterRow(tok, ts, if (iName >= 0 && iName < cells.size) cells[iName].trim() else "")
            }
        } catch (t: Throwable) { return emptyMap() }
        return out
    }

    // =========================================================================== http plumbing

    private class Raw(val ok: Boolean, val data: Map<String, Any?>?, val text: String?, val error: String)

    private fun authedGet(path: String, query: String): Res<Map<String, Any?>> {
        if (!connected()) return Res.bad(notConnectedText())
        val r = rawCall("GET", if (query.isEmpty()) path else "$path?$query", null, authed = true, key = apiKey)
        if (!r.ok) return Res.bad(r.error)
        return Res.good(r.data ?: emptyMap())
    }

    private fun authedPost(path: String, body: String): Res<Map<String, Any?>> {
        if (!connected()) return Res.bad(notConnectedText())
        val r = rawCall("POST", path, body, authed = true, key = apiKey)
        if (!r.ok) return Res.bad(r.error)
        return Res.good(r.data ?: emptyMap())
    }

    private fun notConnectedText(): String =
        if (sessionExpired()) "Your mStock session ended at midnight. Connect again to refresh it." else "mStock is not connected yet."

    private fun rawCall(method: String, path: String, body: String?, authed: Boolean, key: String, maxBytes: Int = 2_000_000, wantText: Boolean = false): Raw {
        val h = LinkedHashMap<String, String>()
        h["X-Mirae-Version"] = "1"
        h["Accept"] = if (wantText) "text/csv, */*" else "application/json"
        if (body != null) h["Content-Type"] = "application/x-www-form-urlencoded"
        if (authed) h["Authorization"] = "token $key:$accessToken"
        val resp = try { p.httpRequest(method, "$BASE/$path", h, body, maxBytes) } catch (t: Throwable) { null }
            ?: return Raw(false, null, null, "Could not reach mStock. Check your internet connection and try again.")
        val j = Json.parseObject(resp.body)
        if (resp.code in 200..299 && wantText && j == null && resp.body.isNotBlank()) return Raw(true, null, resp.body, "")
        if (j == null) {
            val e = when {
                resp.code == 429 -> "mStock is limiting requests right now. Wait a minute and try again."
                resp.code in 500..599 -> "mStock is having a problem right now (error ${resp.code}). Try again later."
                resp.code == 401 || resp.code == 403 -> authFailure(resp.code, null, authed)
                else -> "mStock sent a reply the app could not read."
            }
            return Raw(false, null, null, e)
        }
        val failed = resp.code !in 200..299 || (j.containsKey("status") && j.str("status").lowercase().let { it == "error" || it == "false" || it == "failed" })
        if (failed) {
            val et = j.str("error_type")
            if (resp.code == 401 || resp.code == 403 || et == "TokenException" || et == "APIKeyException") return Raw(false, null, null, authFailure(resp.code, et, authed))
            if (resp.code == 429) return Raw(false, null, null, "mStock is limiting requests right now. Wait a minute and try again.")
            return Raw(false, null, null, failText(j, "mStock could not complete that request (error ${resp.code})."))
        }
        return Raw(true, j, null, "")
    }

    private fun authFailure(code: Int, errorType: String?, authed: Boolean): String {
        val keyBad = code == 403 || errorType == "APIKeyException"
        if (authed && !keyBad) {
            // the token was rejected: forget it so the UI offers a fresh login
            synchronized(lock) { accessToken = ""; expiresAt = 0L; persist() }
            return "mStock no longer accepts this login. Connect again to refresh it."
        }
        if (keyBad) return "mStock rejected the API key. It may be wrong, expired, or the API subscription is suspended. Check it at trade.mstock.com."
        return "mStock rejected the request. Check your details and try again."
    }

    /** A user-safe message from an mStock error body: server text with any secret removed. */
    private fun failText(j: Map<String, Any?>, fallback: String): String {
        var m = j.str("message").trim()
        if (m.isEmpty()) m = j.str("error").trim()
        if (m.isEmpty() || m.length > 240) return fallback
        return scrub(m)
    }

    private fun scrub(s: String): String {
        var x = s
        for (sec in listOf(apiKey, accessToken)) if (sec.length >= 6) x = x.replace(sec, "***")
        return x
    }

    private fun form(vararg kv: Pair<String, String>): String = kv.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** An NSE-symbol-looking value, or "" when it is a company name (to be resolved by name later). */
    private fun symbolLike(ts: String): String {
        val c = HoldingsImport.cleanSymbol(ts)
        return if (c.matches(SYM_OK) && c.length <= 20 && !ts.contains(' ')) c else ""
    }

    // =========================================================================== persistence

    private fun persist() {
        try {
            p.write(FILE, Json.stringify(linkedMapOf("apiKey" to apiKey, "clientCode" to clientCode, "accessToken" to accessToken, "loginAt" to loginAt, "expiresAt" to expiresAt)))
        } catch (t: Throwable) {}
    }

    private fun load() {
        try {
            val m = Json.parseObject(p.read(FILE)) ?: return
            apiKey = m.str("apiKey"); clientCode = m.str("clientCode"); accessToken = m.str("accessToken")
            loginAt = m.long("loginAt"); expiresAt = m.long("expiresAt")
        } catch (t: Throwable) {}
    }

    companion object {
        const val BASE = "https://api.mstock.trade/openapi/typea"
        const val FILE = "mstock.json"
        const val SYNC_FILE = "mstock_sync.json"
        const val MASTER_FILE = "mstock_master.csv"
        /** The docs show checksum only as the example value "L"; override via the connect action if mStock tells you otherwise. */
        const val CHECKSUM = "L"
        /** Max symbols per quote request. The docs state no limit, so this is conservative. */
        const val MAX_PER_CALL = 50
        const val MAX_TOTAL = 200
        const val OTP_WINDOW_MS = 10 * 60_000L
        const val MASTER_MAX_BYTES = 40_000_000
        private val SYM_OK = Regex("^[A-Z0-9&][A-Z0-9&\\-]{0,24}$")
    }
}
