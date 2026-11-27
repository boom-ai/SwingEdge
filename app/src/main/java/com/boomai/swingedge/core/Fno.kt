package com.boomai.swingedge.core

/*
 * F&O paper trading on NSE's live option chain (www.nseindia.com option-chain-v3: bid, ask, LTP, IV, OI per strike).
 * Paper money only. Options BUY to open and SELL to close (no naked selling: that needs real margin).
 * Fills are realistic: market BUY fills at the ask, market SELL at the bid; limit orders fill only when the live
 * ask/bid crosses the limit; stop-loss triggers when the bid falls to the stop and then sells at the bid.
 * Charges: ₹20 per order, STT 0.1% of sell premium, exchange 0.0355%, SEBI 0.0001%, GST 18% on fees, stamp 0.003% on buys.
 * Positions still open at expiry are settled at intrinsic value. Pure Kotlin, never throws.
 */

/** [src] = price source (nse, groww, mstock); [at] = when that price was fetched (0 = with the chain); [synth] = bid/ask estimated around the last price (no live depth). */
class FnoQuote(val ltp: Double, val bid: Double, val ask: Double, val iv: Double, val oi: Double, val vol: Double, val chg: Double,
               val src: String = "nse", val at: Long = 0L, val synth: Boolean = false)

class FnoChain(val sym: String, val expiry: String, val spot: Double, val ts: String, val fetchedAt: Long,
               val strikes: List<Int>, val ce: Map<Int, FnoQuote>, val pe: Map<Int, FnoQuote>, val expiries: List<String>) {
    fun quote(strike: Int, side: String): FnoQuote? = if (side == "CE") ce[strike] else pe[strike]
}

class FnoPos(var id: String = "", var sym: String = "", var expiry: String = "", var strike: Int = 0, var side: String = "",
             var lots: Int = 0, var lot: Int = 0, var avg: Double = 0.0, var sl: Double = 0.0, var tgt: Double = 0.0,
             var openedAt: Long = 0, var last: Double = 0.0, var bid: Double = 0.0) {
    val qty get() = lots * lot
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "sym" to sym, "expiry" to expiry, "strike" to strike, "side" to side, "lots" to lots,
        "lot" to lot, "qty" to qty, "avg" to avg, "sl" to sl, "tgt" to tgt, "openedAt" to openedAt, "last" to last, "bid" to bid,
        "pnl" to Fno.r2((bid - avg) * qty))
    companion object { fun from(m: Map<String, Any?>) = FnoPos(m.str("id"), m.str("sym"), m.str("expiry"), m.int("strike"), m.str("side"), m.int("lots"),
        m.int("lot"), m.num("avg", 0.0), m.num("sl", 0.0), m.num("tgt", 0.0), m.long("openedAt"), m.num("last", 0.0), m.num("bid", 0.0)) }
}

class FnoOrder(var id: String = "", var sym: String = "", var expiry: String = "", var strike: Int = 0, var side: String = "",
               var buy: Boolean = true, var lots: Int = 0, var lot: Int = 0, var type: String = "MARKET", var limit: Double = 0.0,
               var sl: Double = 0.0, var tgt: Double = 0.0, var status: String = "OPEN", var at: Long = 0, var fillPx: Double = 0.0,
               var fillAt: Long = 0, var note: String = "", var charges: Double = 0.0, var pnl: Double = 0.0) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "sym" to sym, "expiry" to expiry, "strike" to strike, "side" to side, "buy" to buy,
        "lots" to lots, "lot" to lot, "type" to type, "limit" to limit, "sl" to sl, "tgt" to tgt, "status" to status, "at" to at,
        "fillPx" to fillPx, "fillAt" to fillAt, "note" to note, "charges" to charges, "pnl" to pnl)
    companion object { fun from(m: Map<String, Any?>) = FnoOrder(m.str("id"), m.str("sym"), m.str("expiry"), m.int("strike"), m.str("side"),
        m.bool("buy"), m.int("lots"), m.int("lot"), m.str("type").ifEmpty { "MARKET" }, m.num("limit", 0.0), m.num("sl", 0.0), m.num("tgt", 0.0),
        m.str("status").ifEmpty { "OPEN" }, m.long("at"), m.num("fillPx", 0.0), m.long("fillAt"), m.str("note"), m.num("charges", 0.0), m.num("pnl", 0.0)) }
}

class FnoBook(var cash: Double = Fno.START_CASH, var start: Double = Fno.START_CASH, val pos: MutableList<FnoPos> = ArrayList(),
              val orders: MutableList<FnoOrder> = ArrayList(), var seq: Int = 0) {
    fun toMap(): Map<String, Any?> = linkedMapOf("cash" to cash, "start" to start, "seq" to seq, "pos" to pos.map { it.toMap() }, "orders" to orders.map { it.toMap() })
    companion object {
        fun from(m: Map<String, Any?>?): FnoBook {
            if (m == null) return FnoBook()
            val b = FnoBook(m.num("cash", Fno.START_CASH), m.num("start", Fno.START_CASH), seq = m.int("seq"))
            @Suppress("UNCHECKED_CAST") for (x in m.list("pos")) (x as? Map<String, Any?>)?.let { b.pos.add(FnoPos.from(it)) }
            @Suppress("UNCHECKED_CAST") for (x in m.list("orders")) (x as? Map<String, Any?>)?.let { b.orders.add(FnoOrder.from(it)) }
            return b
        }
    }
}

class FnoEvent(val title: String, val body: String)

object Fno {
    const val START_CASH = 100000.0
    /** Lot sizes; NIFTY/BANKNIFTY built in, every other F&O underlying added from NSE's fo_mktlots.csv at runtime. */
    val LOT: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap(mapOf("NIFTY" to 65, "BANKNIFTY" to 30))
    val INDICES = setOf("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTYNXT50")
    val HOSTS = listOf("https://www.nseindia.in", "https://www.nseindia.com")   // .com 301-redirects to .in
    const val MAX_ORDERS_KEPT = 300
    const val STALE_MS = 90_000L          // never fill on a quote older than this

    fun r2(x: Double) = Math.rint(x * 100) / 100

    fun infoUrl(host: String, sym: String) = "$host/api/option-chain-contract-info?symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}"
    fun chainUrl(host: String, sym: String, expiry: String) = "$host/api/option-chain-v3?type=${if (INDICES.contains(sym)) "Indices" else "Equity"}&symbol=${java.net.URLEncoder.encode(sym, "UTF-8")}&expiry=$expiry"

    fun parseExpiries(text: String?): List<String> = try {
        (Json.parseObject(text ?: "") ?: emptyMap()).list("expiryDates").mapNotNull { it as? String }
    } catch (t: Throwable) { emptyList() }

    private fun q(m: Map<String, Any?>?): FnoQuote? {
        if (m == null) return null
        val ltp = m.num("lastPrice", 0.0)
        return FnoQuote(ltp, m.num("buyPrice1", 0.0), m.num("sellPrice1", 0.0), m.num("impliedVolatility", 0.0), m.num("openInterest", 0.0),
            m.num("totalTradedVolume", 0.0), m.num("change", 0.0))
    }

    fun parseChain(sym: String, expiry: String, text: String?, now: Long): FnoChain? {
        try {
            val root = Json.parseObject(text ?: return null) ?: return null
            val r = root.obj("records")
            val spot = r.num("underlyingValue", 0.0)
            val rows = r.list("data")
            if (!(spot > 0) || rows.isEmpty()) return null
            val ce = HashMap<Int, FnoQuote>(); val pe = HashMap<Int, FnoQuote>()
            for (x in rows) {
                @Suppress("UNCHECKED_CAST") val m = x as? Map<String, Any?> ?: continue
                val k = m.num("strikePrice", 0.0).toInt()
                if (k <= 0) continue
                @Suppress("UNCHECKED_CAST") q(m["CE"] as? Map<String, Any?>)?.let { ce[k] = it }
                @Suppress("UNCHECKED_CAST") q(m["PE"] as? Map<String, Any?>)?.let { pe[k] = it }
            }
            val strikes = (ce.keys + pe.keys).toSortedSet().toList()
            return FnoChain(sym, expiry, spot, r.str("timestamp"), now, strikes, ce, pe, r.list("expiryDates").mapNotNull { it as? String })
        } catch (t: Throwable) { return null }
    }

    /** expiry "23-Nov-2026" -> epoch day, or -1 */
    fun expDay(e: String): Int = try {
        java.time.LocalDate.parse(e, java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)).toEpochDay().toInt()
    } catch (t: Throwable) { -1 }

    fun charges(buy: Boolean, px: Double, qty: Int): Double {
        val turn = px * qty
        val exch = turn * 0.000355; val sebi = turn * 0.000001
        val gst = (20 + exch + sebi) * 0.18
        val stt = if (buy) 0.0 else turn * 0.001
        val stamp = if (buy) turn * 0.00003 else 0.0
        return r2(20 + exch + sebi + gst + stt + stamp)
    }

    // ------------------------------------------------------------------ orders

    fun place(b: FnoBook, c: FnoChain?, a: Map<String, Any?>, now: Long): Map<String, Any?> {
        val sym = a.str("sym").uppercase(); val side = a.str("side").uppercase(); val strike = a.int("strike"); val expiry = a.str("expiry")
        val buy = a.bool("buy"); val lots = a.int("lots"); val type = a.str("type").uppercase().ifEmpty { "MARKET" }
        val limit = a.num("limit", 0.0); val sl = a.num("sl", 0.0); val tgt = a.num("tgt", 0.0)
        val lot = LOT[sym] ?: return err("Not an F&O symbol (lot size unknown yet)")
        if (side != "CE" && side != "PE") return err("Pick CE or PE")
        if (lots !in 1..50) return err("Lots must be 1 to 50")
        if (type != "MARKET" && type != "LIMIT") return err("Order type must be MARKET or LIMIT")
        if (type == "LIMIT" && !(limit > 0)) return err("Enter a limit price")
        if (c == null || c.sym != sym || c.expiry != expiry) return err("No live price for this contract. Refresh and try again.")
        if (now - c.fetchedAt > STALE_MS) return err("Live price is old. Refresh first.")
        val qt = c.quote(strike, side) ?: return err("Strike not in the live chain")
        if (buy) {
            if (sl > 0 && tgt > 0 && sl >= tgt) return err("Stop-loss must be below target")
            val ref = if (type == "LIMIT") limit else qt.ask
            if (sl > 0 && sl >= ref) return err("Stop-loss must be below the buy price")
            val need = ref * lots * lot + 60
            val reserved = b.orders.filter { it.status == "OPEN" && it.buy }.sumOf { (if (it.type == "LIMIT") it.limit else 0.0) * it.lots * it.lot }
            if (need > b.cash - reserved) return err("Not enough paper cash: need ₹${Math.round(need)}, free ₹${Math.round(b.cash - reserved)}")
        } else {
            val p = b.pos.firstOrNull { it.sym == sym && it.expiry == expiry && it.strike == strike && it.side == side }
                ?: return err("No position to sell (selling options without holding them needs real margin)")
            if (lots > p.lots) return err("You hold ${p.lots} lots")
        }
        b.seq++
        val o = FnoOrder("O${b.seq}", sym, expiry, strike, side, buy, lots, lot, type, r2(limit), r2(sl), r2(tgt), "OPEN", now)
        b.orders.add(0, o)
        val ev = ArrayList<FnoEvent>()
        tryFill(b, o, c, now, ev)
        trim(b)
        return linkedMapOf("ok" to true, "order" to o.toMap(), "events" to ev.map { linkedMapOf("title" to it.title, "body" to it.body) })
    }

    private fun err(m: String): Map<String, Any?> = linkedMapOf("ok" to false, "error" to m)

    private fun trim(b: FnoBook) { while (b.orders.size > MAX_ORDERS_KEPT) { val i = b.orders.indexOfLast { it.status != "OPEN" }; if (i < 0) break; b.orders.removeAt(i) } }

    /** Fills [o] if the live quote allows. */
    private fun tryFill(b: FnoBook, o: FnoOrder, c: FnoChain, now: Long, ev: MutableList<FnoEvent>) {
        if (o.status != "OPEN" || c.sym != o.sym || c.expiry != o.expiry || now - c.fetchedAt > STALE_MS) return
        val qt = c.quote(o.strike, o.side) ?: return
        if (qt.at > 0 && now - qt.at > STALE_MS) return     // this strike's own price is old even if the chain is fresh
        val px = if (o.buy) qt.ask else qt.bid
        if (!(px > 0)) return                          // no seller / buyer right now
        if (o.type == "LIMIT" && (if (o.buy) px > o.limit else px < o.limit)) return
        val fill = if (o.type == "LIMIT") (if (o.buy) minOf(px, o.limit) else maxOf(px, o.limit)) else px
        val qty = o.lots * o.lot
        val ch = charges(o.buy, fill, qty)
        if (o.buy) {
            if (fill * qty + ch > b.cash) { o.status = "REJECTED"; o.note = "Not enough paper cash at fill"; return }
            b.cash = r2(b.cash - fill * qty - ch)
            val p = b.pos.firstOrNull { it.sym == o.sym && it.expiry == o.expiry && it.strike == o.strike && it.side == o.side }
            if (p == null) b.pos.add(FnoPos("P" + o.id, o.sym, o.expiry, o.strike, o.side, o.lots, o.lot, fill, o.sl, o.tgt, now, qt.ltp, qt.bid))
            else { p.avg = r2((p.avg * p.qty + fill * qty) / (p.qty + qty)); p.lots += o.lots; if (o.sl > 0) p.sl = o.sl; if (o.tgt > 0) p.tgt = o.tgt }
        } else {
            val p = b.pos.firstOrNull { it.sym == o.sym && it.expiry == o.expiry && it.strike == o.strike && it.side == o.side }
            if (p == null || p.lots < o.lots) { o.status = "REJECTED"; o.note = "Position no longer open"; return }
            b.cash = r2(b.cash + fill * qty - ch)
            o.pnl = r2((fill - p.avg) * qty - ch)
            p.lots -= o.lots
            if (p.lots <= 0) b.pos.remove(p)
        }
        o.status = "FILLED"; o.fillPx = r2(fill); o.fillAt = now; o.charges = ch
        ev.add(FnoEvent("Paper ${if (o.buy) "BOUGHT" else "SOLD"} ${o.sym} ${o.strike} ${o.side}", "${o.lots} lot (${qty}) at ₹${f2(fill)}${if (!o.buy) ", P&L ₹${f2(o.pnl)}" else ""}${if (o.note.isNotEmpty()) " · ${o.note}" else ""}"))
    }

    private fun f2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)

    /** Mark positions, fill pending limit orders, trigger stop-loss / target, settle expired contracts. */
    fun tick(b: FnoBook, chains: List<FnoChain>, now: Long): List<FnoEvent> {
        val ev = ArrayList<FnoEvent>()
        try {
            for (c in chains) {
                for (o in b.orders.toList()) if (o.status == "OPEN") tryFill(b, o, c, now, ev)
                for (p in b.pos.toList()) {
                    if (p.sym != c.sym || p.expiry != c.expiry || now - c.fetchedAt > STALE_MS) continue
                    val qt = c.quote(p.strike, p.side) ?: continue
                    if (qt.at > 0 && now - qt.at > STALE_MS) continue
                    p.last = qt.ltp; if (qt.bid > 0) p.bid = qt.bid
                    val why = when {
                        p.sl > 0 && qt.bid > 0 && qt.bid <= p.sl -> "Stop-loss hit"
                        p.tgt > 0 && qt.bid >= p.tgt -> "Target hit"
                        else -> ""
                    }
                    if (why.isNotEmpty()) {
                        b.seq++
                        val o = FnoOrder("O${b.seq}", p.sym, p.expiry, p.strike, p.side, false, p.lots, p.lot, "MARKET", status = "OPEN", at = now, note = why)
                        b.orders.add(0, o); tryFill(b, o, c, now, ev)
                    }
                }
            }
            // expiry settlement: after 15:30 on (or any time after) expiry day, at intrinsic value from the last known spot
            val day = Clock.epochDay(now); val min = Clock.minuteOfDay(now)
            for (p in b.pos.toList()) {
                val ed = expDay(p.expiry)
                if (ed < 0 || day < ed || (day == ed && min < 15 * 60 + 30)) continue
                val spot = chains.firstOrNull { it.sym == p.sym }?.spot ?: continue
                val intr = maxOf(0.0, if (p.side == "CE") spot - p.strike else p.strike - spot)
                val qty = p.qty; val ch = if (intr > 0) charges(false, intr, qty) else 0.0
                b.cash = r2(b.cash + intr * qty - ch)
                b.seq++
                b.orders.add(0, FnoOrder("O${b.seq}", p.sym, p.expiry, p.strike, p.side, false, p.lots, p.lot, "EXPIRY", status = "FILLED", at = now,
                    fillPx = r2(intr), fillAt = now, note = "Expired, settled at intrinsic", charges = ch, pnl = r2((intr - p.avg) * qty - ch)))
                b.pos.remove(p)
                ev.add(FnoEvent("Paper ${p.sym} ${p.strike} ${p.side} expired", "Settled at ₹${f2(intr)}"))
            }
            trim(b)
        } catch (t: Throwable) {}
        return ev
    }

    fun cancel(b: FnoBook, id: String): Map<String, Any?> {
        val o = b.orders.firstOrNull { it.id == id && it.status == "OPEN" } ?: return err("Order not open")
        o.status = "CANCELLED"
        return linkedMapOf("ok" to true)
    }

    fun setLevels(b: FnoBook, id: String, sl: Double, tgt: Double): Map<String, Any?> {
        val p = b.pos.firstOrNull { it.id == id } ?: return err("Position not found")
        if (sl > 0 && tgt > 0 && sl >= tgt) return err("Stop-loss must be below target")
        p.sl = r2(maxOf(0.0, sl)); p.tgt = r2(maxOf(0.0, tgt))
        return linkedMapOf("ok" to true)
    }

    fun summary(b: FnoBook): Map<String, Any?> {
        val mtm = b.pos.sumOf { it.bid * it.qty }
        val realized = b.orders.filter { it.status == "FILLED" && !it.buy }.sumOf { it.pnl }
        val unreal = b.pos.sumOf { (it.bid - it.avg) * it.qty }
        val today = Clock.epochDay(System.currentTimeMillis())
        return linkedMapOf("cash" to r2(b.cash), "start" to b.start, "value" to r2(b.cash + mtm), "realized" to r2(realized), "unrealized" to r2(unreal),
            "total" to r2(b.cash + mtm - b.start))
    }

    /** chain around ATM for the screen */
    fun chainView(c: FnoChain?, width: Int = 8, now: Long = 0L): Map<String, Any?>? {
        if (c == null) return null
        val atm = c.strikes.minByOrNull { Math.abs(it - c.spot) } ?: return null
        val i = c.strikes.indexOf(atm)
        val ks = c.strikes.subList(maxOf(0, i - width), minOf(c.strikes.size, i + width + 1))
        // Black-76 on the live last price: implied volatility (%) and delta per strike
        val ed = expDay(c.expiry); val tNow = if (now > 0) now else c.fetchedAt
        val ty = if (ed > 0 && c.spot > 0) Black76.years(ed, tNow) else 0.0
        val fwd = if (ty > 0) Black76.forward(c.spot, ty) else 0.0
        fun qm(x: FnoQuote?, k: Int, call: Boolean): Map<String, Any?>? {
            if (x == null) return null
            val v = if (fwd > 0) Black76.iv(call, x.ltp, fwd, k.toDouble(), ty) else Double.NaN
            val d = if (!v.isNaN()) Black76.delta(call, fwd, k.toDouble(), ty, v) else Double.NaN
            return linkedMapOf("ltp" to x.ltp, "bid" to x.bid, "ask" to x.ask, "iv" to x.iv, "oi" to x.oi, "chg" to x.chg,
                "ivLive" to (if (v.isNaN()) null else r2(v * 100)), "delta" to (if (d.isNaN()) null else Math.rint(d * 1000) / 1000),
                "src" to x.src, "synth" to x.synth)
        }
        val ceOi = c.strikes.sumOf { c.ce[it]?.oi ?: 0.0 }; val peOi = c.strikes.sumOf { c.pe[it]?.oi ?: 0.0 }
        val pain = c.strikes.minByOrNull { k -> c.strikes.sumOf { s -> (c.ce[s]?.oi ?: 0.0) * maxOf(0, k - s) + (c.pe[s]?.oi ?: 0.0) * maxOf(0, s - k) } }
        val ivs = listOfNotNull(c.ce[atm]?.iv, c.pe[atm]?.iv).filter { it > 0 }
        val maxOi = ks.maxOfOrNull { maxOf(c.ce[it]?.oi ?: 0.0, c.pe[it]?.oi ?: 0.0) } ?: 0.0
        val stra = (c.ce[atm]?.ltp ?: 0.0) + (c.pe[atm]?.ltp ?: 0.0)
        val atmIvs = listOfNotNull(c.ce[atm]?.let { if (fwd > 0) Black76.iv(true, it.ltp, fwd, atm.toDouble(), ty) else null },
            c.pe[atm]?.let { if (fwd > 0) Black76.iv(false, it.ltp, fwd, atm.toDouble(), ty) else null }).filter { !it.isNaN() }
        return linkedMapOf("sym" to c.sym, "expiry" to c.expiry, "spot" to c.spot, "ts" to c.ts, "fetchedAt" to c.fetchedAt, "atm" to atm,
            "expiries" to c.expiries.take(6), "lot" to (LOT[c.sym] ?: 0), "pcr" to (if (ceOi > 0) r2(peOi / ceOi) else null), "maxPain" to pain,
            "atmIv" to (if (ivs.isNotEmpty()) r2(ivs.average()) else null), "maxOi" to maxOi,
            "atmIvLive" to (if (atmIvs.isNotEmpty()) r2(atmIvs.average() * 100) else null),
            "straddle" to (if (c.ce[atm] != null && c.pe[atm] != null) r2(stra) else null),
            "impliedMovePct" to (if (c.ce[atm] != null && c.pe[atm] != null && c.spot > 0) r2(stra / c.spot * 100) else null),
            "synthetic" to (ks.any { c.ce[it]?.synth == true || c.pe[it]?.synth == true }),
            "src" to (ks.mapNotNull { c.ce[it]?.src }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""),
            "rows" to ks.map { k -> linkedMapOf("k" to k, "ce" to qm(c.ce[k], k, true), "pe" to qm(c.pe[k], k, false)) })
    }
}
