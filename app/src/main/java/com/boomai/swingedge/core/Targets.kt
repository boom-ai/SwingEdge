package com.boomai.swingedge.core

/**
 * A user's price target and/or stop for one symbol (SPEC3 §3). One row per symbol; persisted in AppState "targets".
 * Direction comes from [refPrice]: a target at or above it is reached when the price is >= target, a target below it when
 * the price is <= target; a stop at or below it when the price is <= stop, a stop above it when the price is >= stop.
 * refPrice 0 = unknown when set; the first price seen afterwards becomes the reference.
 */
class UserTarget {
    var sym = ""; var name = ""
    var target = 0.0; var stop = 0.0; var note = ""
    var setAt = 0L; var refPrice = 0.0
    var status = ACTIVE
    var hitAt = 0L
    /** Last known price and the close before it (for the state row). */
    var ltp = 0.0; var prevClose = 0.0

    fun toMap(): Map<String, Any?> {
        val px = ltp
        val prog = when {
            status == HIT_TARGET && target > 0 -> 100.0
            target > 0 && refPrice > 0 && px > 0 && target != refPrice -> ((px - refPrice) / (target - refPrice) * 100.0).coerceIn(0.0, 100.0)
            else -> 0.0
        }
        return linkedMapOf(
            "sym" to sym, "name" to name, "ltp" to Fmt.r2(px), "dayChgPct" to (if (px > 0 && prevClose > 0) Fmt.r2((px / prevClose - 1) * 100.0) else 0.0),
            "target" to Fmt.r2(target), "stop" to Fmt.r2(stop), "note" to note, "setAt" to setAt, "refPrice" to Fmt.r2(refPrice),
            "status" to status, "hitAt" to hitAt,
            "toTargetPct" to (if (target > 0 && px > 0) Fmt.r2((target / px - 1) * 100.0) else 0.0),
            "toStopPct" to (if (stop > 0 && px > 0) Fmt.r2((stop / px - 1) * 100.0) else 0.0),
            "progressPct" to Fmt.r2(prog)
        )
    }

    fun persist(): Map<String, Any?> = linkedMapOf(
        "sym" to sym, "name" to name, "target" to target, "stop" to stop, "note" to note, "setAt" to setAt, "refPrice" to refPrice,
        "status" to status, "hitAt" to hitAt, "ltp" to ltp, "prevClose" to prevClose
    )

    /** What [px] does to this ACTIVE row: HIT_TARGET, HIT_STOP or null. Target is checked first. */
    fun hitBy(px: Double): String? {
        if (status != ACTIVE || !(px > 0) || !(refPrice > 0)) return null
        if (target > 0 && (if (target >= refPrice) px >= target else px <= target)) return HIT_TARGET
        if (stop > 0 && (if (stop <= refPrice) px <= stop else px >= stop)) return HIT_STOP
        return null
    }

    companion object {
        const val ACTIVE = "ACTIVE"
        const val HIT_TARGET = "HIT_TARGET"
        const val HIT_STOP = "HIT_STOP"
        const val MAX = 50
        const val NOTE_MAX = 80
        const val PRICE_MAX = 1e7
        private val SYM = Regex("^[A-Z0-9&][A-Z0-9&\\-]*$")

        private fun okPrice(x: Double) = !x.isNaN() && !x.isInfinite() && x >= 0 && x <= PRICE_MAX

        /** Tolerant: junk rows give null. */
        fun from(m: Map<String, Any?>): UserTarget? {
            val t = UserTarget()
            t.sym = m.str("sym").trim().uppercase()
            if (t.sym.isEmpty() || t.sym.length > 25 || !SYM.matches(t.sym)) return null
            t.target = m.num("target"); t.stop = m.num("stop")
            if (!okPrice(t.target) || !okPrice(t.stop) || !(t.target > 0 || t.stop > 0)) return null
            t.name = m.str("name", t.sym).ifBlank { t.sym }
            t.note = m.str("note").take(NOTE_MAX)
            t.setAt = m.long("setAt"); t.refPrice = m.num("refPrice").let { if (okPrice(it)) it else 0.0 }
            t.status = m.str("status", ACTIVE).let { if (it == HIT_TARGET || it == HIT_STOP) it else ACTIVE }
            t.hitAt = if (t.status == ACTIVE) 0L else m.long("hitAt")
            t.ltp = m.num("ltp").let { if (okPrice(it)) it else 0.0 }
            t.prevClose = m.num("prevClose").let { if (okPrice(it)) it else 0.0 }
            return t
        }
    }
}
