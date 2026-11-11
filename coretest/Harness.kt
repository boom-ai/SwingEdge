package com.boomai.swingedge.coretest

object T {
    var pass = 0
    var fail = 0
    val failures = ArrayList<String>()
    var current = ""

    fun check(cond: Boolean, msg: String) {
        if (cond) pass++ else {
            fail++
            val line = "[$current] $msg"
            if (failures.size < 80) failures.add(line)
            println("  FAIL $line")
        }
    }

    fun eq(actual: Any?, expected: Any?, msg: String) = check(actual == expected, "$msg — expected <$expected> got <$actual>")
    fun near(actual: Double, expected: Double, tol: Double, msg: String) =
        check(!actual.isNaN() && Math.abs(actual - expected) <= tol, "$msg — expected $expected got $actual")

    fun section(name: String, body: () -> Unit) {
        current = name
        val p0 = pass
        val f0 = fail
        val t0 = System.currentTimeMillis()
        try {
            body()
        } catch (t: Throwable) {
            fail++
            val line = "[$name] threw $t at " + t.stackTrace.take(4).joinToString(" < ")
            failures.add(line)
            println("  FAIL $line")
        }
        println((if (fail == f0) "ok   " else "FAIL ") + name + "  (" + (pass - p0) + " checks, " + (System.currentTimeMillis() - t0) + " ms)")
    }
}

fun main(args: Array<String>) {
    // Tests exercise every strategy; shipped defaults keep the research-rejected ones off.
    com.boomai.swingedge.core.Params.MOM_PULLBACK_ENABLED = true
    com.boomai.swingedge.core.Params.DIP_UPTREND_ENABLED = true
    com.boomai.swingedge.core.Params.BREAKOUT_VOL_ENABLED = true
    com.boomai.swingedge.core.Params.WEEKLY_LEADERS_ENABLED = true
    com.boomai.swingedge.core.Params.DIP_RSI_MAX = 8.0
    if (System.getenv("TIERED") == "1") com.boomai.swingedge.core.Params.FULL_QUOTE_MAX_UNIVERSE = 0
    com.boomai.swingedge.core.Params.EXPERIMENTAL_ENABLED = System.getenv("EXPERIMENTAL") == "1"
    // legacy portfolio rules stay covered by the existing tests
    com.boomai.swingedge.core.Params.PF_SELL_ON_FALLING_50 = true; com.boomai.swingedge.core.Params.PF_SELL_ON_STOP_BREAK = true
    com.boomai.swingedge.core.Params.PF_SELL_ON_REL_WEAK = true; com.boomai.swingedge.core.Params.PF_TRIM_ON_STRENGTH = true
    com.boomai.swingedge.core.Params.BT_MAX_CONCURRENT = 100000   // equality tests compare every setup; the cap is exercised on real data
    val only = args.firstOrNull()
    if (only == "sample") {
        Sample.run()
        return
    }
    val all: List<Pair<String, () -> Unit>> = UnitTests.all() + SimTests.all() + V2Tests.all()
    for ((name, body) in all) if (only == null || name.contains(only, true)) T.section(name, body)
    println()
    println("RESULT: ${T.pass} checks passed, ${T.fail} failed")
    if (T.fail > 0) {
        println("Failures:")
        for (f in T.failures) println("  $f")
        System.exit(1)
    }
}
