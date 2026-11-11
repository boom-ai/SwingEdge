package com.boomai.swingedge.core

/** Mutable state of one position while the exit model walks it bar by bar. Prices are absolute; returns are fractions of entry. */
class Pos {
    var entry = 0.0
    var sl = 0.0
    var t1 = 0.0
    var t2 = 0.0
    /** Fraction of the position sold at T1 (0.5 normally; 0 when the quantity is 1 and T1 only moves the stop). */
    var halfFrac = 0.5
    var t1Done = false
    /** Price at which the T1 half was (assumed) filled. */
    var t1Px = 0.0
    /** Sum of fraction-of-position x fractional return for everything sold so far, before charges. */
    var realized = 0.0
    var closed = false
    var exitPx = 0.0
    /** Bit mask of ExitModel.* events that closed the position. */
    var reason = 0

    fun open(entry: Double, sl: Double, t1: Double, t2: Double, halfFrac: Double): Pos {
        this.entry = entry; this.sl = sl; this.t1 = t1; this.t2 = t2; this.halfFrac = halfFrac
        t1Done = false; t1Px = 0.0; realized = 0.0; closed = false; exitPx = 0.0; reason = 0
        return this
    }

    fun remainingFrac(): Double = if (t1Done) 1.0 - halfFrac else 1.0
}

/**
 * THE exit rules, shared by the backtest, paper tracking and live follow-up.
 * Within one daily bar, in this order:
 *  1. stop: a gap open at/below the stop exits at the open, otherwise a low at/below the stop exits at the stop;
 *  2. T1 (once): sell `halfFrac` at T1 (at the open if it gapped above) and move the stop to the entry price;
 *  3. T2: sell the rest at T2 (at the open if it gapped above);
 *  4. time: on the last holding day sell whatever is left at the close.
 * A target of 0 means "no target". Charges are a flat % of entry turnover per round trip.
 */
object ExitModel {
    const val NONE = 0
    const val T1 = 1
    const val STOP = 2
    const val T2 = 4
    const val TIME = 8
    /** Closed above its short moving average: the bounce the trade was waiting for has happened. */
    const val MA = 16

    fun step(p: Pos, o: Double, h: Double, l: Double, c: Double, lastDay: Boolean, bounceExit: Boolean = false): Int {
        if (p.closed) return NONE
        var ev = NONE
        // 1. stop first
        if (o <= p.sl) {
            close(p, o, STOP)
            return STOP
        }
        if (l <= p.sl) {
            close(p, p.sl, STOP)
            return STOP
        }
        // 2. first target
        if (!p.t1Done && p.t1 > 0 && h >= p.t1) {
            val px = if (o > p.t1) o else p.t1
            p.t1Done = true
            p.t1Px = px
            p.realized += p.halfFrac * (px - p.entry) / p.entry
            if (p.entry > p.sl) p.sl = p.entry
            ev = ev or T1
        }
        // 3. second target
        if (p.t2 > 0 && h >= p.t2) {
            close(p, if (o > p.t2) o else p.t2, T2)
            return ev or T2
        }
        // 3b. bounce exit at the close (rules that sell on the first close above a short average)
        if (bounceExit) {
            close(p, c, MA)
            return ev or MA
        }
        // 4. time exit
        if (lastDay) {
            close(p, c, TIME)
            return ev or TIME
        }
        return ev
    }

    /** Closes whatever is left at [px]. */
    fun close(p: Pos, px: Double, why: Int) {
        if (p.closed) return
        p.realized += p.remainingFrac() * (px - p.entry) / p.entry
        p.closed = true
        p.exitPx = px
        p.reason = why
    }

    /** Net % return on the invested amount after charges. */
    fun netPct(p: Pos, costPct: Double): Double = p.realized * 100.0 - costPct

    fun resultText(reason: Int, t1Done: Boolean): String = when {
        reason and T2 != 0 -> "T2 hit"
        reason and STOP != 0 -> if (t1Done) "T1 hit, rest at entry" else "Stop-loss hit"
        reason and MA != 0 -> "Sold on the bounce"
        reason and TIME != 0 -> "Time exit on day " + Params.HOLD_DAYS
        else -> "Closed"
    }
}
