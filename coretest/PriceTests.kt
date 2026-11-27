package com.boomai.swingedge.coretest

import com.boomai.swingedge.core.*

/** PriceHub (arbitration, staleness, outliers, change %), m.Stock F&O master and contract quotes, real-depth chain overlay, Black-76. */
object PriceTests {
    fun all(): List<Pair<String, () -> Unit>> = listOf(
        "v20-hub-arbitration" to ::arbitration, "v20-hub-monotonic" to ::monotonic, "v20-hub-outliers" to ::outliers,
        "v20-hub-staleness" to ::staleness, "v20-hub-health" to ::health, "v20-nfo-master" to ::nfoMaster,
        "v20-overlay-depth" to ::overlayDepth, "v20-black76" to ::black76, "v20-groww-samples" to ::growwSamples,
        "v20-totp-only" to ::totpOnly, "v20-app-broker-chain" to ::appChain)

    val DAY = java.time.LocalDate.of(2026, 11, 18).toEpochDay().toInt()     // a Wednesday
    private fun at(min: Int, sec: Int = 0) = Clock.msAt(DAY, min) + sec * 1000L
    private class Clk(var t: Long) { fun hub() = PriceHub { t } }
    private fun eq(sym: String, ltp: Double, src: String, t: Long, prev: Double = 0.0, ts: Long = 0L) =
        PriceHub.PQ(PriceHub.eqKey(sym), PriceHub.EQ, ltp, src, if (ts > 0) ts else t, t, ts > 0, prevClose = prev)

    fun arbitration() {
        val c = Clk(at(600)); val h = c.hub()
        T.check(h.offer(eq("RELIANCE", 1000.0, PriceHub.GROWW, c.t, prev = 990.0)), "first Groww quote accepted")
        T.eq(h.get(PriceHub.eqKey("RELIANCE"))!!.pct, 1.01, "pct from Groww's previous close")
        c.t += 1000
        T.check(h.offer(eq("RELIANCE", 1001.0, PriceHub.MSTOCK, c.t, prev = 980.0)), "m.Stock outranks Groww")
        val q = h.get(PriceHub.eqKey("RELIANCE"))!!
        T.eq(q.src, PriceHub.MSTOCK, "source switched to m.Stock")
        T.eq(q.prevClose, 990.0, "previous close stays with the first source of the day")
        T.eq(q.chg, 11.0, "change against the locked previous close")
        c.t += 1000
        T.check(!h.offer(eq("RELIANCE", 1002.0, PriceHub.GROWW, c.t)), "Groww shadowed while m.Stock is fresh")
        T.eq(h.get(PriceHub.eqKey("RELIANCE"))!!.ltp, 1001.0, "quote unchanged")
        T.eq(h.fromSource(PriceHub.eqKey("RELIANCE"), PriceHub.GROWW)!!.ltp, 1002.0, "shadow sample kept")
        c.t += PriceHub.ARB_FRESH_MS + 1000
        T.check(h.offer(eq("RELIANCE", 1003.0, PriceHub.GROWW, c.t)), "Groww takes over when m.Stock stops delivering")
        T.eq(h.inUse(PriceHub.EQ), PriceHub.GROWW, "source in use")
        T.check(!h.offer(eq("RELIANCE", 1003.5, PriceHub.YAHOO, c.t + 500)), "Yahoo shadowed by fresh Groww")
        // a new IST day takes a new previous close
        c.t = Clock.msAt(DAY + 1, 600)
        T.check(h.offer(eq("RELIANCE", 1010.0, PriceHub.MSTOCK, c.t, prev = 1003.0)), "next day accepted")
        T.eq(h.get(PriceHub.eqKey("RELIANCE"))!!.prevClose, 1003.0, "new day: new previous close")
        // no previous close anywhere: the source's own change is used
        T.check(h.offer(PriceHub.PQ(PriceHub.eqKey("X"), PriceHub.EQ, 50.0, PriceHub.NSE, c.t, c.t, chg = 1.0, pct = Double.NaN)), "nse accepted")
        T.eq(h.get(PriceHub.eqKey("X"))!!.prevClose, 49.0, "previous close derived from the change")
    }

    fun monotonic() {
        val c = Clk(at(600)); val h = c.hub()
        T.check(h.offer(eq("TCS", 3000.0, PriceHub.GROWW, c.t, ts = at(599, 50))), "first")
        c.t += 1000
        T.check(!h.offer(eq("TCS", 2999.0, PriceHub.GROWW, c.t, ts = at(599, 40))), "older exchange time from the same source dropped")
        T.eq(h.get(PriceHub.eqKey("TCS"))!!.ltp, 3000.0, "newer quote kept")
        T.check(h.offer(eq("TCS", 3001.0, PriceHub.GROWW, c.t, ts = at(599, 50))), "same exchange time (no new trade) is fine")
        T.check(!h.offer(eq("TCS", 3002.0, PriceHub.NSE, c.t, ts = at(599, 30))), "older quote from a lower source dropped")
        T.check(h.offer(eq("TCS", 3003.0, PriceHub.GROWW, c.t + 10, ts = at(600, 30) + 3_600_000L)), "future exchange time accepted ...")
        T.check(h.get(PriceHub.eqKey("TCS"))!!.ts <= c.t + 10, "... but clamped to the fetch time")
        val hl = h.health().obj("sources").obj(PriceHub.GROWW)
        T.eq(hl.int("outOfOrder"), 1, "out-of-order counted")
        T.check(hl.int("glitchesRejected") >= 1, "glitches counted")
    }

    fun outliers() {
        val c = Clk(at(600)); val h = c.hub()
        h.offer(eq("INFY", 1000.0, PriceHub.GROWW, c.t, prev = 1000.0))
        c.t += 1000
        T.check(!h.offer(eq("INFY", 1200.0, PriceHub.GROWW, c.t)), "20% tick held back")
        T.eq(h.get(PriceHub.eqKey("INFY"))!!.ltp, 1000.0, "last good price stays")
        c.t += 1000
        T.check(h.offer(eq("INFY", 1199.0, PriceHub.GROWW, c.t)), "confirmed by a second sample")
        T.check(!h.offer(eq("INFY", 600.0, PriceHub.GROWW, c.t + 1000)), "bad print rejected")
        T.check(!h.offer(eq("INFY", 1199.5, PriceHub.GROWW, c.t + 2000).copy(upper = 1100.0, lower = 900.0)), "outside the circuit band rejected")
        T.check(h.offer(eq("INFY", 1050.0, PriceHub.GROWW, c.t + 3000).copy(upper = 1320.0, lower = 1080.0)).not(), "below lower circuit rejected")
        T.check(h.offer(eq("INFY", 1190.0, PriceHub.GROWW, c.t + 4000)), "normal move accepted")
        // a second source confirms at once
        val k = PriceHub.optKey("NIFTY", "23-Nov-2026", 25450.0, "CE")
        fun opt(px: Double, src: String, t: Long) = PriceHub.PQ(k, PriceHub.OPT, px, src, t, t)
        h.offer(opt(100.0, PriceHub.GROWW, c.t))
        T.check(!h.offer(opt(300.0, PriceHub.GROWW, c.t + 1000)), "option tripled: held")
        T.check(h.offer(opt(299.0, PriceHub.MSTOCK, c.t + 1500)), "another source agrees: accepted")
        val k2 = PriceHub.optKey("NIFTY", "23-Nov-2026", 26500.0, "CE")
        h.offer(PriceHub.PQ(k2, PriceHub.OPT, 2.0, PriceHub.GROWW, c.t, c.t))
        T.check(h.offer(PriceHub.PQ(k2, PriceHub.OPT, 6.0, PriceHub.GROWW, c.t + 1000, c.t + 1000)), "cheap option: ₹4 move is not an outlier")
        // index limit is tighter
        val ik = PriceHub.idxKey("NIFTY")
        h.offer(PriceHub.PQ(ik, PriceHub.IDX, 25000.0, PriceHub.GROWW, c.t, c.t))
        T.check(!h.offer(PriceHub.PQ(ik, PriceHub.IDX, 26000.0, PriceHub.GROWW, c.t + 1000, c.t + 1000)), "4% index jump held")
        T.check(!h.offer(PriceHub.PQ(ik, PriceHub.IDX, 0.0, PriceHub.GROWW, c.t + 1000, c.t + 1000)), "zero rejected")
        T.check(!h.offer(PriceHub.PQ(ik, PriceHub.IDX, Double.NaN, PriceHub.GROWW, c.t + 1000, c.t + 1000)), "NaN rejected")
        // after a long gap the old price is no reference (e.g. the first tick of a new session)
        c.t += PriceHub.REF_MS + 60_000
        T.check(h.offer(PriceHub.PQ(ik, PriceHub.IDX, 26000.0, PriceHub.GROWW, c.t, c.t)), "old reference ignored")
        // cash stock more than 20% from its previous close needs confirmation even without a recent tick
        val h2 = Clk(at(600)).hub()
        h2.offer(eq("ABC", 100.0, PriceHub.GROWW, at(600), prev = 100.0))
        T.check(!h2.offer(eq("ABC", 130.0, PriceHub.GROWW, at(630))), "30% from previous close held")
        T.check(h.health().obj("sources").obj(PriceHub.GROWW).int("outliers") >= 4, "outliers counted")
    }

    fun staleness() {
        val c = Clk(at(600)); val h = c.hub()
        h.offer(eq("A", 10.0, PriceHub.GROWW, c.t))
        val q = h.get(PriceHub.eqKey("A"))!!
        T.eq(h.state(q, c.t + 5_000), "live", "fresh")
        T.eq(h.state(q, c.t + 59_000), "delayed", "no new tick for a minute")
        T.eq(h.state(q, c.t + 61_000), "stale", "not refetched for a minute")
        val old = q.copy(ts = c.t - 200_000, hasTs = true)
        T.eq(h.state(old, c.t), "stale", "exchange time 200 s old: stale (frozen feed)")
        val opt = old.copy(kind = PriceHub.OPT)
        T.eq(h.state(opt, c.t), "delayed", "options trade less often: 200 s is only delayed")
        T.eq(h.state(q, Clock.msAt(DAY, 16 * 60)), "closed", "after the close nothing is stale")
        T.eq(h.state(q, Clock.msAt(DAY + 3, 11 * 60)), "closed", "Saturday")
        T.eq(h.state(null, c.t), "none", "no quote")
        T.eq(h.view(q, c.t)["src"], PriceHub.GROWW, "view carries the source")
        c.t += 70_000
        T.check(h.fresh(PriceHub.eqKey("A"), 60_000) == null && h.get(PriceHub.eqKey("A")) != null, "fresh() honours max age")
    }

    fun health() {
        val c = Clk(at(600)); val h = c.hub()
        val t0 = h.begin(); c.t += 120; h.ok(PriceHub.MSTOCK, t0)
        h.offer(eq("A", 10.0, PriceHub.MSTOCK, c.t))
        var s = h.summary()
        T.eq(s["source"], PriceHub.MSTOCK, "summary source"); T.eq(s["state"], "ok", "healthy")
        T.check(s["text"].toString().contains("m.Stock") && s["text"].toString().contains("120 ms"), "summary text: ${s["text"]}")
        repeat(3) { val t1 = h.begin(); c.t += 50; h.fail(PriceHub.MSTOCK, t1, "mStock is limiting requests") }
        s = h.summary()
        T.eq(s["state"], "down", "three failures in a row: down")
        T.check(s["text"].toString().contains("limiting"), "reason shown")
        val hm = h.health().obj("sources").obj(PriceHub.MSTOCK)
        T.eq(hm.int("errors"), 3, "error count"); T.eq(hm.int("ok"), 1, "ok count"); T.eq(hm.long("lastOk"), at(600) + 120, "last ok")
        T.check(hm.containsKey("latencyMs") && hm.containsKey("glitchesRejected"), "latency and glitches reported")
        h.result(PriceHub.GROWW, h.begin(), 0)
        T.eq(h.health().obj("sources").obj(PriceHub.GROWW).int("errors"), 1, "empty batch counts as a failure")
        T.eq(PriceHub.normTs(1795067365.0), 1795067365000L, "seconds -> ms")
        T.eq(PriceHub.normTs(1795067365000.0), 1795067365000L, "ms kept")
        T.eq(PriceHub.normTs(1795067365000000.0), 1795067365000L, "µs -> ms")
    }

    val NFO = "instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\r\n" +
        "22,22,ACC,ACC LIMITED,,,,0.05,1,EQ,EQ,NSE\r\n" +
        "101,101,NIFTY26N2325400CE,,150,2026-11-23,25400,0.05,65,CE,OPTIDX,NFO\r\n" +
        "102,102,NIFTY26N2325400PE,,80,2026-11-23,25400,0.05,65,PE,OPTIDX,NFO\r\n" +
        "103,103,NIFTY26N2325450CE,,120,2026-11-23,25450,0.05,65,CE,OPTIDX,NFO\r\n" +
        "104,104,NIFTY26N2325450PE,,95,2026-11-23,25450,0.05,65,PE,OPTIDX,NFO\r\n" +
        "105,105,NIFTY26N2325500CE,,90,2026-11-23,25500.0,0.05,65,CE,OPTIDX,NFO\r\n" +
        "106,106,NIFTY26N2325500PE,,110,2026-11-23,25500,0.05,65,PE,OPTIDX,NFO\r\n" +
        "107,107,NIFTY26N1725450CE,,1,2026-11-17,25450,0.05,65,CE,OPTIDX,NFO\r\n" +
        "108,108,NIFTY26DEC25450CE,,300,29-Dec-2026,25450,0.05,65,CE,OPTIDX,NFO\r\n" +
        "109,109,NIFTY27JAN25450CE,,400,2027-01-26,25450,0.05,65,CE,OPTIDX,NFO\r\n" +
        "110,110,NIFTY27MAR25450CE,,500,2027-03-30,25450,0.05,65,CE,OPTIDX,NFO\r\n" +
        "111,111,NIFTY26NOVFUT,,25500,2026-11-24,,0.1,65,XX,FUTIDX,NFO\r\n" +
        "112,112,NIFTY26DECFUT,,25600,2026-12-29,,0.1,65,XX,FUTIDX,NFO\r\n" +
        "113,113,NIFTY27JANFUT,,25700,2027-01-26,,0.1,65,XX,FUTIDX,NFO\r\n" +
        "114,114,RELIANCE26NOV1360CE,,18.5,2026-11-24,1360,0.05,500,CE,OPTSTK,NFO\r\n" +
        "115,115,CANBK26NOV52.5PE,,1.2,2026-11-24,52.5,0.05,6750,PE,OPTSTK,NFO\r\n" +
        "116,116,M&M26NOV3500CE,,40,2026-11-24,3500,0.05,200,CE,OPTSTK,NFO\r\n" +
        "830001,542066,SENSEX26N2081000CE,,300,2026-11-20,81000,0.05,20,CE,OPTIDX,BFO\r\n" +
        "830002,542067,RELIANCE26NOV1360CE,,18.4,2026-11-24,1360,0.05,500,CE,OPTSTK,BFO\r\n" +
        "999,999,WEIRDSYMBOL,,1,2026-11-24,100,0.05,1,CE,OPTSTK,NFO\r\n" +
        "998,998,BADEXP26NOV100CE,,1,someday,100,0.05,1,CE,OPTSTK,NFO\r\n"

    fun nfoMaster() {
        T.eq(MStock.underlyingOf("NIFTY2672124150CE", "CE", 24150.0), "NIFTY", "weekly code (docs example)")
        T.eq(MStock.underlyingOf("SENSEX2671677000CE", "CE", 77000.0), "SENSEX", "BSE weekly code (docs example)")
        T.eq(MStock.underlyingOf("NIFTYNXT5025FEB59300CE", "CE", 59300.0), "NIFTYNXT50", "underlying ending in digits")
        T.eq(MStock.underlyingOf("CANBK25FEB52.5PE", "PE", 52.5), "CANBK", "fractional strike")
        T.eq(MStock.underlyingOf("NIFTY26DECFUT", "FUT", 0.0), "NIFTY", "future")
        T.eq(MStock.underlyingOf("M&M26NOV3500CE", "CE", 3500.0), "M&M", "ampersand")
        T.eq(MStock.underlyingOf("NIFTY26DEC25450CE", "CE", 25000.0), null, "strike mismatch -> unknown")
        T.eq(MStock.parseExpiry("2026-11-23"), java.time.LocalDate.of(2026, 11, 23).toEpochDay().toInt(), "iso")
        T.eq(MStock.parseExpiry("29-Dec-2026"), java.time.LocalDate.of(2026, 12, 29).toEpochDay().toInt(), "dd-Mon-yyyy")
        T.eq(MStock.parseExpiry("29DEC2026"), java.time.LocalDate.of(2026, 12, 29).toEpochDay().toInt(), "ddMONyyyy")
        T.eq(MStock.parseExpiry("2026-11-23 00:00:00"), java.time.LocalDate.of(2026, 11, 23).toEpochDay().toInt(), "with time")
        T.eq(MStock.parseExpiry("29/12/2026"), java.time.LocalDate.of(2026, 12, 29).toEpochDay().toInt(), "dd/mm/yyyy")
        T.eq(MStock.parseExpiry("junk"), -1, "junk")
        val b = MStock(FakePlatform.standard())
        val rows = b.parseDerivs(NFO, DAY)
        val syms = rows.map { it.tradingSymbol }.toSet()
        T.check("NIFTY26N2325450CE" in syms && "NIFTY26DEC25450CE" in syms && "NIFTY27JAN25450CE" in syms, "three nearest option expiries kept")
        T.check("NIFTY27MAR25450CE" !in syms, "4th expiry dropped")
        T.check("NIFTY26N1725450CE" !in syms, "expired contract dropped")
        T.check("NIFTY26NOVFUT" in syms && "NIFTY26DECFUT" in syms && "NIFTY27JANFUT" !in syms, "two nearest futures")
        T.check("ACC" !in syms && "WEIRDSYMBOL" !in syms && "BADEXP26NOV100CE" !in syms, "equities and unreadable rows skipped")
        T.check("SENSEX26N2081000CE" in syms, "BFO index options kept")
        val r = rows.first { it.tradingSymbol == "NIFTY26N2325500CE" }
        T.check(r.under == "NIFTY" && r.strike == 25500.0 && r.lot == 65 && r.type == "CE" && r.exchange == "NFO" && r.token == 105L, "row fields")
        T.eq(rows.first { it.tradingSymbol == "CANBK26NOV52.5PE" }.strike, 52.5, "fractional strike")
        T.eq(rows.first { it.tradingSymbol == "NIFTY26NOVFUT" }.type, "FUT", "future type from segment")
        T.eq(b.parseDerivs("garbage", DAY).size, 0, "junk -> empty")
        T.eq(b.parseMaster(NFO).keys.toList(), listOf("ACC"), "equity parser unaffected by F&O rows")
        // download, lookup, cache, restart
        val p = FakePlatform.standard(); p.setTime(DAY, 600)
        val srv = V19Tests.Srv(p); srv.master = NFO
        val m = MStock(p); m.connect(V19Tests.KEY, "AB1234", "", "", "654321")
        val distinct = rows.map { it.tradingSymbol }.toSet().size
        T.check(m.loadMasterCached() && m.derivCount() == distinct, "F&O rows loaded: ${m.derivCount()}")
        val ed = java.time.LocalDate.of(2026, 11, 23).toEpochDay().toInt()
        T.eq(m.deriv("NIFTY", ed, 25450.0, "PE")?.tradingSymbol, "NIFTY26N2325450PE", "lookup by underlying/expiry/strike/side")
        T.eq(m.deriv("RELIANCE", java.time.LocalDate.of(2026, 11, 24).toEpochDay().toInt(), 1360.0, "CE")?.exchange, "NFO", "NSE row preferred over BSE")
        T.eq(m.derivBySymbol("RELIANCE26NOV1360CE")?.under, "RELIANCE", "lookup by trading symbol (= Groww monthly id)")
        T.eq(m.lots()["NIFTY"], 65, "lot sizes from the master"); T.eq(m.lots()["RELIANCE"], 500, "stock lot")
        T.eq(m.expiries("NIFTY").size, 3, "near expiries")
        val n = p.reqs.size
        val m2 = MStock(p)
        T.check(m2.loadMasterCached() && m2.derivCount() == distinct && p.reqs.size == n, "restart: F&O rows from the cache file, no download")
        T.eq(m2.deriv("NIFTY", ed, 25450.0, "CE")?.token, 103L, "cached token")
        // a cache written before F&O rows existed is refreshed once while connected
        val p3 = FakePlatform.standard(); p3.setTime(DAY, 600)
        val s3 = V19Tests.Srv(p3); s3.master = NFO
        p3.files[MStock.MASTER_FILE] = "#day=$DAY\ninstrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange\n22,,ACC,ACC LIMITED,,,,,,EQ,EQ,NSE\n"
        val m3 = MStock(p3); m3.connect(V19Tests.KEY, "", "", "", "654321")
        T.check(m3.loadMasterCached() && m3.derivCount() > 0, "old equity-only cache upgraded")
        val n3 = p3.reqs.size; m3.loadMasterCached(); m3.loadMasterCached()
        T.eq(p3.reqs.size, n3, "and only once")
    }

    private fun chain(fetchedAt: Long) = Fno.parseChain("NIFTY", "23-Nov-2026", V12Tests.chainJson(25450.0, 100.0, 101.0), fetchedAt)!!

    fun overlayDepth() {
        val c = Clk(at(600)); val h = c.hub()
        val base = chain(c.t - 30_000)
        T.check(h.offerChain(base) == 6, "NSE chain enters the hub")
        c.t += 1000
        val ed = Fno.expDay("23-Nov-2026")
        val bq = MStock.BQuote(104.0, 98.0, 106.0, 97.0, 99.0, bid = 103.8, ask = 104.3, oi = 5000.0, vol = 77.0)
        T.check(bq.depth, "depth flag")
        T.check(h.offer(PriceHub.fromBroker(PriceHub.optKey("NIFTY", ed, 25450.0, "CE"), PriceHub.OPT, bq, c.t)), "m.Stock contract quote accepted")
        T.check(h.offer(Groww.optSample(PriceHub.optKey("NIFTY", ed, 25450.0, "PE"), Triple(92.0, 0L, 2.0), c.t)), "Groww contract quote accepted")
        val o = h.overlay(base, 25460.0, listOf(25450), c.t, "Live")
        val ce = o.quote(25450, "CE")!!
        T.check(ce.bid == 103.8 && ce.ask == 104.3 && !ce.synth && ce.src == PriceHub.MSTOCK, "real best bid/ask from m.Stock")
        T.eq(ce.oi, 5000.0, "live OI"); T.eq(ce.at, c.t, "own fetch time")
        T.eq(ce.chg, 4.0, "change vs the previous close locked from NSE's chain (104 - 100), not m.Stock's own close")
        val pe = o.quote(25450, "PE")!!
        T.check(pe.synth && pe.src == PriceHub.GROWW && pe.bid < 92.0 && pe.ask > 92.0, "no depth: estimated spread, marked synth")
        val un = o.quote(25500, "CE")!!
        T.check(un.src == "nse" && un.at == base.fetchedAt && !un.synth, "strike not refreshed keeps NSE's quote and its age")
        T.eq(o.spot, 25460.0, "live spot")
        // fills: a quote older than STALE_MS on an otherwise fresh chain is refused
        val oldBase = chain(c.t - Fno.STALE_MS - 5_000)
        val o2 = h.overlay(oldBase, null, listOf(25450), c.t, "Live")
        val book = FnoBook()
        val r1 = Fno.place(book, o2, mapOf("sym" to "NIFTY", "expiry" to "23-Nov-2026", "strike" to 25500, "side" to "CE", "buy" to true, "lots" to 1), c.t)
        T.check(r1["ok"] == true && book.orders[0].status == "OPEN", "old strike price: order waits instead of filling")
        val r2 = Fno.place(book, o2, mapOf("sym" to "NIFTY", "expiry" to "23-Nov-2026", "strike" to 25450, "side" to "CE", "buy" to true, "lots" to 1), c.t)
        T.check(r2["ok"] == true && book.orders[0].status == "FILLED" && book.orders[0].fillPx == 104.3, "live strike fills at the real ask: ${book.orders[0].toMap()}")
        // chain view exposes source, synth flag and greeks
        val v = Fno.chainView(o, 8, c.t)!!
        val row = v.list("rows").map { it.asObj() }.first { it.int("k") == 25450 }
        T.eq(row.obj("ce").str("src"), PriceHub.MSTOCK, "row source"); T.eq(row.obj("pe")["synth"], true, "row synth flag")
        T.check(row.obj("ce").num("ivLive") > 0 && row.obj("ce").num("delta") in 0.0..1.0 && row.obj("pe").num("delta") in -1.0..0.0, "live IV and delta per strike: ${row.obj("ce")}")
        T.near(v.num("straddle"), 104.0 + 92.0, 1e-9, "ATM straddle"); T.near(v.num("impliedMovePct"), 196.0 / 25460.0 * 100, 0.01, "implied move %")
        T.check(v.num("atmIvLive") > 0 && v["synthetic"] == true, "ATM IV, synthetic flag")
    }

    fun black76() {
        val f = 25500.0; val t = 30 / 365.0
        for (vol in listOf(0.08, 0.15, 0.4)) for (k in listOf(24000.0, 25500.0, 27000.0)) for (call in listOf(true, false)) {
            val px = Black76.price(call, f, k, t, vol)
            T.near(Black76.iv(call, px, f, k, t), vol, 1e-4, "iv round trip call=$call k=$k vol=$vol")
        }
        val c = Black76.price(true, f, 25000.0, t, 0.15); val p = Black76.price(false, f, 25000.0, t, 0.15)
        T.near(c - p, Math.exp(-Black76.RATE * t) * (f - 25000.0), 1e-6, "put-call parity")
        T.near(Black76.delta(true, f, f, t, 0.15) - Black76.delta(false, f, f, t, 0.15), Math.exp(-Black76.RATE * t), 1e-9, "delta parity")
        T.check(Black76.iv(true, 0.01, f, 30000.0, t).let { it.isNaN() || it < 0.5 }, "tiny premium -> low or no iv")
        T.check(Black76.iv(true, 100.0, f, 24000.0, t).isNaN(), "below intrinsic -> NaN")
        T.check(Black76.price(true, f, 25000.0, 0.0, 0.2).isNaN(), "no time -> NaN")
        T.check(Black76.years(DAY, Clock.msAt(DAY, 15 * 60 + 29)) > 0, "expiry day keeps a floor")
    }

    fun growwSamples() {
        val txt = """{"exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{"M&M":{"ltp":3507.3,"close":3359.1,"dayChange":148.2,"dayChangePerc":4.41,"tsInMillis":1795070709,"open":3400}},"indexLivePointsMap":{"NIFTY":{"value":25313.85,"dayChange":85.9,"dayChangePerc":0.34,"tsInMillis":1795067365}}}}}"""
        val s = Groww.samples(txt, 5L)
        val mm = s.first { it.key == PriceHub.eqKey("M&M") }
        T.check(mm.prevClose == 3359.1 && mm.ts == 1795070709000L && mm.hasTs && mm.open == 3400.0, "stock sample: ${mm}")
        val n = s.first { it.key == PriceHub.idxKey("NIFTY") }
        T.near(n.prevClose, 25227.95, 1e-6, "index previous close from day change"); T.eq(n.kind, PriceHub.IDX, "index kind")
        T.eq(Groww.samples("junk", 0L).size, 0, "junk")
        T.eq(Groww.parsePrices("""{"X":{"ltp":1.5,"lastTradeTime":1795067564}}""")["X"]?.second, 1795067564000L, "contract time in ms")
    }

    fun totpOnly() {
        val p = FakePlatform.standard(); p.setTime(DAY, 600)
        V19Tests.Srv(p)
        val m = MStock(p)
        val r = m.connect(V19Tests.KEY, "", "", "", "654321")
        T.check(r.ok && r.data == true && m.connected(), "TOTP alone connects: ${r.error}")
        T.eq(p.reqs.map { it.url.removePrefix(V19Tests.BASE) }, listOf("session/verifytotp"), "one call, no password, no checksum")
        T.check(p.reqs[0].body!!.contains("api_key=") && p.reqs[0].body!!.contains("totp=654321") && !p.reqs[0].body!!.contains("password"), "form fields")
        T.check(!MStock(FakePlatform.standard()).connect(V19Tests.KEY, "", "", "", "").ok, "neither password nor TOTP -> asks")
        T.check(!MStock(FakePlatform.standard()).connect(V19Tests.KEY, "", "", "", "12x").ok, "bad TOTP format")
        val p2 = FakePlatform.standard(); p2.setTime(DAY, 600); V19Tests.Srv(p2)
        val bad = MStock(p2).connect(V19Tests.KEY, "", "", "", "111111")
        T.check(!bad.ok && !bad.error.contains(V19Tests.KEY), "wrong TOTP: clean error")
        T.check(p.files.values.none { it.contains("654321") }, "TOTP not stored")
    }

    /** Whole app: connected m.Stock prices the option chain (with depth) and stocks; diagnostics and settings report it. */
    fun appChain() {
        val p = FakePlatform.standard(); p.setTime(DAY, 600)
        val srv = V19Tests.Srv(p); srv.master = NFO
        val depth = HashMap<String, DoubleArray>()      // NFO symbol -> ltp, bid, ask, oi
        depth["NIFTY26N2325400CE"] = doubleArrayOf(107.0, 106.5, 107.5, 1000.0); depth["NIFTY26N2325400PE"] = doubleArrayOf(84.0, 83.5, 84.5, 900.0)
        depth["NIFTY26N2325450CE"] = doubleArrayOf(102.0, 101.5, 102.5, 1200.0); depth["NIFTY26N2325450PE"] = doubleArrayOf(91.0, 90.5, 91.5, 1100.0)
        depth["NIFTY26N2325500CE"] = doubleArrayOf(97.0, 96.6, 97.4, 800.0); depth["NIFTY26N2325500PE"] = doubleArrayOf(96.0, 95.5, 96.5, 700.0)
        val inner = p.reqHandler!!
        var nfoCalls = 0
        p.reqHandler = { r ->
            if (r.url.contains("instruments/quote/ohlc") && r.url.contains("NFO%3A")) {
                nfoCalls++
                val keys = r.url.substringAfter('?').split('&').map { java.net.URLDecoder.decode(it.removePrefix("i="), "UTF-8") }
                val body = keys.mapNotNull { k -> depth[k.removePrefix("NFO:")]?.let { d ->
                    "\"$k\":{\"instrument_token\":1,\"last_price\":${d[0]},\"ohlc\":{\"open\":1,\"high\":1,\"low\":1,\"close\":${d[0] - 3}},\"oi\":${d[3]},\"depth\":{\"buy\":[{\"price\":${d[1]},\"quantity\":65}],\"sell\":[{\"price\":${d[2]},\"quantity\":130}]}}" } }
                HttpResp(200, "{\"status\":\"success\",\"data\":{" + body.joinToString(",") + "}}")
            } else inner(r)
        }
        val H = "https://www.nseindia.com"
        p.served[Fno.infoUrl(H, "NIFTY")] = "{\"expiryDates\":[\"23-Nov-2026\"]}"
        p.served[Fno.chainUrl(H, "NIFTY", "23-Nov-2026")] = V12Tests.chainJson(25450.0, 100.0, 101.0)
        p.posted[Groww.INDEX_URL] = """{"exchangeAggRespMap":{"NSE":{"priceLivePointsMap":{},"indexLivePointsMap":{"NIFTY":{"value":25461.5,"dayChange":30,"dayChangePerc":0.12}}}}}"""
        val app = SwingApp(p)
        fun act(n: String, a: String = "{}") = Json.parseObject(app.action(n, a))!!
        val con = act("brokerConnect", Json.stringify(mapOf("apiKey" to V19Tests.KEY, "totp" to "654321")))
        T.check(con["connected"] == true, "app connects with TOTP only: $con")
        val r = act("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"23-Nov-2026\"}")
        val rows = r.obj("chain").list("rows").map { it.asObj() }
        val k = rows.first { it.int("k") == 25450 }
        T.check(k.obj("ce").num("ltp") == 102.0 && k.obj("ce").num("bid") == 101.5 && k.obj("ce").num("ask") == 102.5, "chain row uses m.Stock price and depth: ${k.obj("ce")}")
        T.check(k.obj("ce").str("src") == PriceHub.MSTOCK && k.obj("ce")["synth"] == false, "marked real")
        T.check(r.obj("chain")["synthetic"] == false && r.obj("chain").str("ts").startsWith("Live"), "whole chain real and live")
        T.eq(r.obj("chain").num("spot"), 25461.5, "spot from the live index feed")
        T.check(nfoCalls >= 1, "m.Stock asked for NFO contracts")
        T.check(p.reqs.none { it.url.contains("/orders") }, "no order endpoint")
        // paper order fills at m.Stock's real ask
        val o = act("fnoOrder", "{\"sym\":\"NIFTY\",\"expiry\":\"23-Nov-2026\",\"strike\":25450,\"side\":\"CE\",\"buy\":true,\"lots\":1}")
        T.eq(o.obj("result").obj("order").num("fillPx"), 102.5, "fills at the real ask")
        // stocks via m.Stock, diagnostics, settings line
        srv.prices["UP01"] = 321.5
        val lq = act("liveQuotes", """{"syms":["UP01"]}""")
        T.eq(lq.obj("quotes").obj("UP01").str("src"), PriceHub.MSTOCK, "stock quote source")
        val ph = act("priceHealth")
        T.check(ph["ok"] == true && ph.obj("sources").obj(PriceHub.MSTOCK).int("ok") >= 2, "priceHealth: m.Stock ok count: $ph")
        T.check(ph.obj("sources").containsKey(PriceHub.NSE) && ph.obj("broker").int("contracts") > 0, "NSE source and contract count reported")
        T.eq(ph.obj("inUse").str(PriceHub.OPT), PriceHub.MSTOCK, "options from m.Stock")
        val bs = act("brokerStatus")
        T.check(bs.obj("prices").str("text").contains("m.Stock"), "settings line: ${bs.obj("prices")}")
        // m.Stock down: Groww/NSE take over without a frozen chain
        srv.tokenRejected = true
        p.nowMs += 15_000
        val r2 = act("fno", "{\"sym\":\"NIFTY\",\"expiry\":\"23-Nov-2026\"}")
        T.check(r2.obj("chain").list("rows").isNotEmpty(), "chain still shown after m.Stock drops")
    }
}
