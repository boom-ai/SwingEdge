package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** mStock (Mirae Asset) Type A broker integration, against fixtures that mirror the documented JSON shapes. */
object V19Tests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v19-broker-login-otp" to ::loginOtp, "v19-broker-login-totp" to ::loginTotp, "v19-broker-errors" to ::errors,
        "v19-broker-expiry" to ::expiry, "v19-broker-master" to ::master, "v19-broker-quotes" to ::quotes,
        "v19-broker-sync" to ::sync, "v19-broker-actions" to ::actions, "v19-broker-orders" to ::orders)

    const val KEY = "ay3KSecretApiKeyXYZ/MAKg@@"
    const val TOK = "eyJhbGciOiJIUzI1NiJ9.SECRETTOKEN.sig"
    const val PW = "hunter2-Password"
    const val BASE = "https://api.mstock.trade/openapi/typea/"

    fun json(code: Int, s: String) = HttpResp(code, s)
    const val LOGIN_OK = """{"status":"success","data":{"ugid":"x","is_kyc":"true","is_activate":"true","is_password_reset":"true","is_error":"false","cid":"XXXXX","nm":"XXXXX","flag":0}}"""
    val SESSION_OK = """{"status":"success","data":{"user_type":"individual","user_name":"XXXXX","broker":"MIRAE","exchanges":["NSE","NFO"],"products":["CNC","MIS"],"order_types":["MARKET","LIMIT"],"user_id":"538","api_key":"$KEY","access_token":"$TOK","public_token":"p","enctoken":"e","refresh_token":"r","login_time":"2024-09-26 03:34:48","meta":{"demat_consent":"physical"}}}"""
    const val HOLD = """{"status":"success","data":[
      {"tradingsymbol":"UPTREND 1 LTD","exchange":null,"instrument_token":20877,"isin":"INE488V01015","product":null,"price":513.35,"quantity":100,"used_quantity":0,"t1_quantity":5,"realised_quantity":0,"authorised_quantity":0,"collateral_quantity":100,"average_price":513.35,"last_price":774.75,"close_price":659.95,"pnl":0,"day_change":0,"day_change_percentage":0},
      {"tradingsymbol":"UP02-EQ","instrument_token":999999,"isin":"X","quantity":7,"t1_quantity":0,"average_price":250.5,"last_price":260,"close_price":255},
      {"tradingsymbol":"Flat 3 Ltd","instrument_token":0,"quantity":12,"average_price":88,"last_price":90,"close_price":89},
      {"tradingsymbol":"Zero Ltd","instrument_token":1,"quantity":0,"average_price":88},
      {"tradingsymbol":"Mystery Co Ltd","instrument_token":0,"quantity":3,"average_price":10}]}"""
    const val POS = """{"status":"success","data":{"net":[{"tradingsymbol":"YESBANK","exchange":"NSE","instrument_token":11915,"product":"MIS","quantity":100,"average_price":19.05,"close_price":27.65,"last_price":27.65,"value":1905,"pnl":0,"m2m":860},{"tradingsymbol":"IDEA","exchange":"NSE","instrument_token":14366,"product":"MIS","quantity":0,"average_price":7}],"day":null}}"""
    const val FUNDS = """{"status":"success","data":[{"AVAILABLE_BALANCE":"299972678840.29","AMOUNT_UTILIZED":"27395824.71","CLEAR_BALANCE":"199999949998","LIMIT_TYPE":"CAPITAL","SEG":"A","SUM_OF_ALL":"300000024665"}]}"""
    val MASTER = "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\n" +
        "1,1,GOLDSTAR,GOLDSTAR POWER LIMITED,,,,0.05,1,SM,SM,NSE\n" +
        "20877,20877,UP01,\"Uptrend 1, Ltd\",,,,0.05,1,EQ,EQ,NSE\n" +
        "7,7,UP02-EQ,Uptrend 2 Ltd,,,,0.05,1,EQ,EQ,NSE\n" +
        "8,8,M&M-EQ,Mahindra & Mahindra Ltd,,,,0.05,1,EQ,EQ,NSE\n" +
        "9,9,UP01,Uptrend 1 BSE,,,,0.05,1,EQ,EQ,BSE\n" +
        "10,10,NIFTY26NOVFUT,Nifty,,2026-11-27,,0.05,75,FUT,FO,NFO\n"

    /** A little fake mStock server. */
    class Srv(val p: FakePlatform) {
        var loginStatus = LOGIN_OK
        var otpOk = "123456"; var totpOk = "654321"
        var holdings = HOLD; var positions = POS; var funds = FUNDS; var master = MASTER
        var tokenRejected = false; var keyRejected = false
        var quoteBare: Boolean? = null      // null: any key works; true: only bare keys; false: only -EQ keys
        val prices = HashMap<String, Double>()
        init {
            p.reqHandler = { r -> handle(r) }
        }
        fun authOk(r: FakePlatform.Req) = !tokenRejected && r.headers["Authorization"] == "token $KEY:$TOK" && r.headers["X-Mirae-Version"] == "1"
        fun handle(r: FakePlatform.Req): HttpResp? {
            val path = r.url.removePrefix(BASE).substringBefore('?')
            val form = (r.body ?: "").split('&').filter { it.contains('=') }.associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if (keyRejected) return json(403, """{"status":"error","message":"API is suspended/expired for use. Please check your API subscription and try again.","error_type":"APIKeyException","data":null}""")
            when (path) {
                "connect/login" -> return if (form["password"] == PW) json(200, loginStatus) else json(200, """{"status":"error","message":"Invalid username or password","error_type":"MiraeException","data":null}""")
                "session/token" -> return if (form["request_token"] == otpOk && form["api_key"] == KEY && form["checksum"] == "L") json(200, SESSION_OK) else json(200, """{"status":"error","message":"The entered OTP is incorrect. $KEY","error_type":"MiraeException","data":null}""")
                "session/verifytotp" -> return if (form["totp"] == totpOk) json(200, SESSION_OK) else json(400, """{"status":"error","message":"Please enter correct TOTP","error_type":"InputException","data":null}""")
            }
            if (!authOk(r)) return json(401, """{"status":"error","message":"Invalid request. Please try again.","error_type":"TokenException","data":null}""")
            return when (path) {
                "portfolio/holdings" -> json(200, holdings)
                "portfolio/positions" -> json(200, positions)
                "user/fundsummary" -> json(200, funds)
                "instruments/scriptmaster" -> json(200, master)
                "logout" -> json(200, """{"status":"success","data":"Success"}""")
                "instruments/quote/ohlc" -> {
                    val keys = r.url.substringAfter('?').split('&').map { java.net.URLDecoder.decode(it.removePrefix("i="), "UTF-8") }
                    val sb = StringBuilder()
                    for (k in keys) {
                        val sym = k.removePrefix("NSE:").removeSuffix("-EQ")
                        val bare = !k.endsWith("-EQ")
                        if (quoteBare != null && quoteBare != bare) continue
                        val px = prices[sym] ?: continue
                        if (sb.isNotEmpty()) sb.append(',')
                        sb.append("\"").append(k).append("\":{\"instrument_token\":22,\"last_price\":").append(px).append(",\"ohlc\":{\"open\":").append(px - 1).append(",\"high\":").append(px + 2).append(",\"low\":").append(px - 3).append(",\"close\":").append(px - 5).append("}}")
                    }
                    json(200, "{\"status\":\"success\",\"data\":{$sb}}")
                }
                "orders" -> json(200, """{"status":"success","data":[{"order_id":"1","tradingsymbol":"INFY","status":"Rejected"}]}""")
                else -> if (path.startsWith("orders/") && r.method == "POST") json(200, """{"status":"success","data":{"order_id":"1131241001100"}}""") else json(404, "")
            }
        }
    }

    private fun mk(day: Int = 20500, minute: Int = 600): Pair<FakePlatform, Srv> {
        val p = FakePlatform.standard(); p.setTime(day, minute)
        return Pair(p, Srv(p))
    }

    private fun noSecretsLeak(p: FakePlatform, what: String) {
        T.check(p.reqs.none { r -> r.url.contains(KEY) || r.url.contains(TOK) || r.url.contains(PW) }, "$what: no secret in any URL")
        T.check(p.reqs.all { it.url.startsWith("https://") }, "$what: https only")
        T.check(p.logs.none { it.contains(KEY) || it.contains(TOK) || it.contains(PW) }, "$what: no secret in logs")
        T.check(p.files.entries.none { it.value.contains(PW) }, "$what: password never stored")
    }

    fun loginOtp() {
        val (p, _) = mk()
        val b = MStock(p)
        T.check(!b.connected() && !b.hasKey(), "starts disconnected")
        val r1 = b.connect(KEY, "AB1234", PW, "", "")
        T.check(r1.ok && r1.data == false && b.otpPending(), "step 1: login sends OTP, needs OTP")
        val login = p.reqs[0]
        T.eq(login.url, BASE + "connect/login", "login url")
        T.eq(login.headers["X-Mirae-Version"], "1", "version header")
        T.check(login.headers["Authorization"] == null, "no auth header on login")
        T.eq(login.headers["Content-Type"], "application/x-www-form-urlencoded", "form content type")
        T.check(login.body!!.contains("username=AB1234") && login.body!!.contains("password=" + PW), "login form fields")
        val bad = b.connect(KEY, "AB1234", "", "000000", "")
        T.check(!bad.ok && !bad.error.contains(KEY), "wrong OTP: plain message, key scrubbed (${bad.error})")
        val r2 = b.connect(KEY, "AB1234", "", "123456", "")
        T.check(r2.ok && r2.data == true && b.connected(), "step 2: OTP completes session")
        val ses = p.reqs.last()
        T.eq(ses.url, BASE + "session/token", "session url")
        T.check(ses.body!!.contains("api_key=") && ses.body!!.contains("request_token=123456") && ses.body!!.contains("checksum=L"), "session form fields")
        val f = p.files[MStock.FILE]!!
        T.check(f.contains(TOK) && f.contains("AB1234"), "session persisted in its own file")
        val b2 = MStock(p)
        T.check(b2.connected(), "session survives app restart")
        val h = b2.holdings()
        T.check(h.ok, "holdings with restored session")
        T.eq(p.reqs.last().headers["Authorization"], "token $KEY:$TOK", "Authorization header format")
        noSecretsLeak(p, "otp flow")
        val noOtp = MStock(FakePlatform.standard()).connect(KEY, "AB1", "", "123456", "")
        T.check(!noOtp.ok && noOtp.error.contains("OTP"), "OTP without a pending login -> plain error")
    }

    fun loginTotp() {
        val (p, _) = mk()
        val b = MStock(p)
        val r = b.connect(KEY, "AB1234", PW, "", "654321")
        T.check(r.ok && r.data == true && b.connected(), "TOTP: login + verify in one call")
        T.eq(p.reqs.map { it.url.removePrefix(BASE) }, listOf("connect/login", "session/verifytotp"), "TOTP call order")
        T.check(p.reqs[1].body!!.contains("totp=654321"), "totp field")
        val (p2, _) = mk()
        val r2 = MStock(p2).connect(KEY, "AB1234", PW, "", "111111")
        T.check(!r2.ok && r2.error.contains("TOTP"), "wrong TOTP message: ${r2.error}")
        noSecretsLeak(p, "totp flow")
    }

    fun errors() {
        val (p, s) = mk()
        val b = MStock(p)
        T.check(!b.connect("", "", "", "", "").ok, "no key -> asks for it")
        T.check(!b.connect(KEY, "", PW, "", "").ok, "no client code -> asks for it")
        T.check(!b.connect(KEY, "AB1", "", "", "").ok, "no password -> asks for it")
        val badPw = b.connect(KEY, "AB1", "wrong", "", "")
        T.check(!badPw.ok && badPw.error.contains("password", true), "wrong password: ${badPw.error}")
        T.check(!b.connect("bad key", "AB1", PW, "", "").ok, "key with spaces rejected locally")
        T.check(!b.holdings().ok && b.holdings().error.contains("not connected"), "reads before connecting are refused without network")
        val n0 = p.reqs.size; b.holdings(); b.funds(); b.quotes(listOf("UP01")); T.eq(p.reqs.size, n0, "no requests while disconnected")
        b.connect(KEY, "AB1234", PW, "", "654321")
        s.keyRejected = true
        val k = b.funds()
        T.check(!k.ok && k.error.contains("API key") && !k.error.contains(KEY), "403 -> API key message: ${k.error}")
        s.keyRejected = false; s.tokenRejected = true
        val t = b.holdings()
        T.check(!t.ok && t.error.contains("Connect again") && !b.connected(), "401 -> session dropped, reconnect hint: ${t.error}")
        // offline / garbage / server errors never throw
        val (p3, s3) = mk(); val b3 = MStock(p3); b3.connect(KEY, "AB1234", PW, "", "654321")
        p3.offline = true
        T.check(b3.holdings().error.contains("internet"), "offline -> plain message")
        p3.offline = false; s3.holdings = "<html>nope</html>"
        T.check(!b3.holdings().ok, "garbage reply -> error, no throw")
        s3.holdings = """{"status":"success","data":"oops"}"""
        T.check(b3.holdings().ok && b3.holdings().data!!.isEmpty(), "odd data shape -> empty")
        p3.reqHandler = { throw RuntimeException("boom") }
        T.check(!b3.funds().ok, "handler throwing -> error, no throw")
        p3.reqHandler = { HttpResp(429, "") }
        T.check(b3.funds().error.contains("limiting"), "429 -> rate limit message")
        p3.reqHandler = { HttpResp(503, "bad gateway") }
        T.check(b3.funds().error.contains("problem"), "5xx message")
        T.check(!MStock(FakePlatform.standard()).also { it.disconnect() }.connected(), "disconnect when disconnected is harmless")
    }

    fun expiry() {
        val (p, _) = mk(20500, 23 * 60 + 30)   // 23:30 IST
        val b = MStock(p)
        b.connect(KEY, "AB1234", PW, "", "654321")
        T.check(b.connected(), "connected at 23:30")
        p.setTime(20500, 23 * 60 + 59)
        T.check(b.connected(), "still valid at 23:59")
        p.setTime(20501, 1)
        T.check(!b.connected() && b.sessionExpired(), "expired after midnight IST")
        T.check(b.status()["message"].toString().contains("midnight"), "status explains the daily login")
        val n = p.reqs.size
        T.check(!b.holdings().ok && p.reqs.size == n, "expired: refused locally, no network")
        T.check(MStock(p).sessionExpired(), "restored expired session is expired")
    }

    fun master() {
        val b = MStock(FakePlatform.standard())
        val m = b.parseMaster(MASTER)
        T.eq(m.keys.toList(), listOf("UP01", "UP02", "M&M"), "NSE equity rows only (SM, BSE, futures dropped), -EQ stripped")
        T.eq(m["UP01"]!!.name, "Uptrend 1, Ltd", "quoted comma names")
        T.eq(m["UP02"]!!.tradingSymbol, "UP02-EQ", "broker's own trading symbol kept for requests")
        T.check(b.parseMaster("garbage").isEmpty() && b.parseMaster("").isEmpty(), "junk master -> empty")
        val (p, s) = mk(); val c = MStock(p); c.connect(KEY, "AB1234", PW, "", "654321")
        T.check(c.loadMasterCached() && c.masterSize() == 3, "master downloaded")
        val n = p.reqs.size
        c.loadMasterCached(); T.eq(p.reqs.size, n, "cached for the day: no second download")
        val c2 = MStock(p); T.check(c2.loadMasterCached() && c2.masterSize() == 3, "loaded from the file after restart")
        T.eq(p.reqs.size, n, "restart: still no download today")
        p.setTime(20501, 600); c2.disconnect(); 
        T.check(c2.loadMasterCached() && c2.masterSize() == 3, "stale cache still usable when disconnected")
        s.master = "junk"
        val c3 = MStock(p); c3.connect(KEY, "AB1234", PW, "", "654321"); p.setTime(20502, 600)
        T.check(c3.loadMasterCached(), "junk download keeps the old list")
    }

    fun quotes() {
        val (p, s) = mk(); val b = MStock(p); b.connect(KEY, "AB1234", PW, "", "654321")
        for (k in 1..120) s.prices["UP%02d".format(k)] = 100.0 + k
        s.prices["M&M"] = 2500.0
        val r = b.quotes(listOf("UP01", "M&M", "NOPE", "bad sym!", "UP01"))
        T.check(r.ok, "quotes ok")
        val q = r.data!!
        T.eq(q.keys.toList().sorted(), listOf("M&M", "UP01"), "known symbols returned, unknown/invalid absent")
        T.near(q["UP01"]!!.ltp, 101.0, 1e-9, "ltp"); T.near(q["UP01"]!!.prevClose, 96.0, 1e-9, "previous close from ohlc.close"); T.near(q["UP01"]!!.high, 103.0, 1e-9, "high")
        val qr = p.reqs.filter { it.url.contains("quote/ohlc") }
        T.check(qr.all { it.url.contains("i=NSE%3A") || it.url.contains("i=NSE:") }, "instruments passed as repeated i= params")
        T.check(qr.any { it.url.contains("M%26M-EQ") || it.url.contains("M%26M") }, "ampersand in M&M is URL-encoded")
        T.check(qr.none { it.url.contains(KEY) || it.url.contains(TOK) }, "no secrets in quote URLs")
        // master gave the real trading symbol, so UP02 is requested as UP02-EQ
        val syms = (1..120).map { "UP%02d".format(it) }
        val before = p.reqs.count { it.url.contains("quote/ohlc") }
        val big = b.quotes(syms)
        T.eq(big.data!!.size, 120, "120 symbols answered")
        T.eq(p.reqs.count { it.url.contains("quote/ohlc") } - before, 3, "split into 50 + 50 + 20 per request")
        // a market where only bare symbols exist and no master knows them -> retry without -EQ
        val (p2, s2) = mk(); val b2 = MStock(p2); b2.connect(KEY, "AB1234", PW, "", "654321"); s2.master = "x"; s2.quoteBare = true; s2.prices["UP05"] = 50.0
        T.eq(b2.quotes(listOf("UP05")).data?.get("UP05")?.ltp, 50.0, "falls back to bare symbol naming")
        T.check(b.quotes(emptyList()).ok, "empty list ok")
        s.tokenRejected = true
        T.check(!b.quotes(listOf("UP01")).ok, "token rejected -> plain error")
    }

    private fun act(app: SwingApp, name: String, args: String = "{}") = Json.parseObject(app.action(name, args))!!

    fun sync() {
        val (p, s) = mk(); val app = SwingApp(p)
        T.check(act(app, "brokerSync")["ok"] == false, "sync before connecting is refused")
        p.assets["universe.csv"] = p.assets["universe.csv"]!!
        T.check(act(app, "brokerConnect", Json.stringify(mapOf("apiKey" to KEY, "clientCode" to "AB1234", "password" to PW, "totp" to "654321")))["connected"] == true, "connected")
        val r = act(app, "brokerSync")
        T.check(r["ok"] == true, "sync ok: $r")
        T.eq(r.int("holdings"), 3, "3 holdings imported (zero-qty and unknown skipped)")
        T.eq(r.list("skipped"), listOf("Mystery Co Ltd"), "unmatched name reported")
        val pos = r.list("positions")
        T.eq(pos.size, 1, "only open positions")
        T.eq(pos[0].asObj().str("sym"), "YESBANK", "position symbol")
        T.near(r.obj("funds").num("available"), 299972678840.29, 0.01, "funds parsed from strings")
        val st = Json.parseObject(app.stateJson())!!
        val hs = st.list("holdings").map { it.asObj() }.associateBy { it.str("sym") }
        T.check(hs.containsKey("UP01") && hs.containsKey("UP02") && hs.containsKey("FL03"), "holdings in app state: ${hs.keys}")
        T.near(hs["UP01"]!!.num("qty"), 105.0, 1e-9, "qty includes T1 shares")
        T.near(hs["UP01"]!!.num("avg"), 513.35, 1e-9, "average price")
        T.near(hs["UP02"]!!.num("avg"), 250.5, 1e-9, "UP02 matched via symbol fallback")
        // a manual holding stays; a sold broker holding goes
        act(app, "addHolding", """{"sym":"DN01","qty":5,"avg":100}""")
        s.holdings = """{"status":"success","data":[{"tradingsymbol":"UP02-EQ","instrument_token":7,"quantity":9,"average_price":251}]}"""
        val r2 = act(app, "brokerSync")
        T.check(r2["ok"] == true && r2.int("removed") == 2, "sold broker holdings removed: $r2")
        val h2 = Json.parseObject(app.stateJson())!!.list("holdings").map { it.asObj().str("sym") }.toSet()
        T.eq(h2, setOf("UP02", "DN01"), "manual holding kept, broker ones synced")
        // empty broker answer does not wipe holdings
        s.holdings = """{"status":"success","data":[]}"""
        val r3 = act(app, "brokerSync")
        T.check(r3["ok"] == true && r3.int("removed") == 0, "empty answer removes nothing")
        val stt = act(app, "brokerStatus")
        T.check(stt["connected"] == true && stt.obj("lastSync").num("at") > 0, "status carries last sync")
        T.check(!Json.stringify(stt).contains(KEY) && !Json.stringify(stt).contains(TOK) && !Json.stringify(r).contains(TOK), "no secrets in action results")
        s.tokenRejected = true
        val r4 = act(app, "brokerSync"); T.check(r4["ok"] == false && r4.str("error").isNotEmpty(), "token rejected -> plain error")
        noSecretsLeak(p, "sync")
    }

    fun actions() {
        val (p, s) = mk(); val app = SwingApp(p)
        val st = act(app, "brokerStatus")
        T.check(st["ok"] == true && st["connected"] == false && st["apiKeySaved"] == false, "status when fresh")
        val c0 = act(app, "brokerConnect", """{"clientCode":"AB1"}""")
        T.check(c0["ok"] == false && c0.str("error").contains("API key"), "connect without key -> plain error")
        val c1 = act(app, "brokerConnect", Json.stringify(mapOf("apiKey" to KEY, "clientCode" to "AB1234", "password" to PW)))
        T.check(c1["ok"] == true && c1["needsOtp"] == true && c1["connected"] == false, "connect step 1: needsOtp")
        T.check(!Json.stringify(c1).contains(PW), "password not echoed")
        val c2 = act(app, "brokerConnect", Json.stringify(mapOf("otp" to "123456")))
        T.check(c2["ok"] == true && c2["connected"] == true && c2["needsOtp"] == false, "connect step 2 with saved key/client code: $c2")
        // quotes
        s.prices["UP01"] = 321.5; s.prices["UP02"] = 100.0
        val q = act(app, "brokerQuotes", """{"syms":["UP01","UP02","ZZZ"]}""")
        T.check(q["ok"] == true && q.obj("quotes").obj("UP01").num("ltp") == 321.5, "brokerQuotes ltp")
        T.near(q.obj("quotes").obj("UP01").num("pct"), 5.0 / 316.5 * 100 * 1.0 + 0.0 * 0, 0.02, "pct vs previous close")
        T.eq(q.list("missing"), listOf("ZZZ"), "missing listed")
        // liveQuotes now uses the broker, falling back to Groww for what the broker lacks
        p.posted["https://groww.in/v1/api/stocks_data/v1/accord_points/exchange/NSE/segment/CASH/latest_aggregated"] = "x"
        val lq = act(app, "liveQuotes", """{"syms":["UP01"]}""")
        T.near(lq.obj("quotes").obj("UP01").num("ltp"), 321.5, 1e-9, "liveQuotes served by broker when connected")
        // disconnect
        val d = act(app, "brokerDisconnect")
        T.check(d["ok"] == true && d["connected"] == false, "disconnect")
        T.check(p.reqs.any { it.url.endsWith("/logout") }, "logout called")
        T.check(!p.files[MStock.FILE]!!.contains(TOK), "token wiped on disconnect")
        T.check(act(app, "brokerQuotes", """{"syms":["UP01"]}""")["ok"] == false, "quotes refused after disconnect")
        T.check(act(app, "brokerStatus").str("clientCode") == "AB1234", "client code remembered for next login")
        // order placement is NOT an app action
        for (n in listOf("brokerPlaceOrder", "brokerOrder", "placeOrder", "brokerOrders")) T.check(act(app, n, "{}")["ok"] == false, "$n is not exposed")
        T.check(p.reqs.none { it.url.contains("/orders") }, "no order endpoint was ever called by app actions")
        // liveQuotes unchanged when not connected
        val lq2 = act(app, "liveQuotes", """{"syms":["UP01"]}""")
        T.check(lq2["ok"] == true, "liveQuotes still fine when disconnected")
    }

    fun orders() {
        val (p, s) = mk(); val b = MStock(p); b.connect(KEY, "AB1234", PW, "", "654321")
        b.loadMasterCached()
        // read-only hardening: order endpoints are not on the allow-list, so nothing is ever sent
        val n = p.reqs.size
        val r = b.placeOrder("regular", "UP02", true, "LIMIT", 5, "CNC", "DAY", 100.5, "t1")
        T.check(!r.ok && r.error.contains("only reads"), "order blocked: ${r.error}")
        T.check(!b.orders().ok, "order book blocked too")
        T.eq(p.reqs.size, n, "no order request left the app")
        T.check(p.reqs.none { it.url.contains("/orders") }, "no /orders url")
        T.check(!b.placeOrder("regular", "UP02", true, "LIMIT", 0, "CNC", "DAY", 100.0).ok, "qty validated")
        T.check(!b.placeOrder("weird", "UP02", true, "MARKET", 1, "CNC", "DAY", 0.0).ok, "variety validated")
        T.check(s.holdings.isNotEmpty(), "fixture sanity")
    }
}
