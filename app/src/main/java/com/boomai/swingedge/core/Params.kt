package com.boomai.swingedge.core

/**
 * EVERY tunable number of the trading logic lives here: strategy parameters, the backtest gate,
 * the exit model, liquidity filters, portfolio-check rules and scheduling windows.
 * They are `var` so research can tune them (and tests can force gate outcomes); nothing else in core
 * hard-codes a trading threshold.
 */
object Params {

    // ---------------------------------------------------------------- strategy switches
    /** Set false to disable a strategy completely (it is then shown as PAUSED and never emits). */
    var MOM_PULLBACK_ENABLED = false   // rejected on 2013-2026 NSE data (research/REPORT.md)
    var BREAKOUT_VOL_ENABLED = false   // rejected on 2013-2026 NSE data
    var DIP_UPTREND_ENABLED = false   // replaced by LEADER_DIP: this looser version lost money as a 4-position portfolio
    var WEEKLY_LEADERS_ENABLED = false // rejected on 2013-2026 NSE data

    // ---------------------------------------------------------------- common filters
    /** 20-day average daily turnover floor, in rupees (25 crore). */
    var MIN_AVG_TURNOVER_RS = 25e7
    var MIN_PRICE = 50.0
    /** Bars needed before any strategy is evaluated (200DMA + 1). */
    var MIN_BARS = 201
    /** Max holding period in trading days; the entry day is day 1; time exit at the close of this day. */
    var HOLD_DAYS = 6
    /** Stops closer than this % of entry are widened to it (avoids noise stops and absurd sizes). */
    var MIN_STOP_PCT = 1.0
    /** Universe needs at least this many ranked stocks for cross-sectional (momentum) strategies. */
    var MIN_UNIVERSE_FOR_RANK = 10

    // ---------------------------------------------------------------- regime (^NSEI)
    var REGIME_FAST_DMA = 50
    var REGIME_SLOW_DMA = 200
    /** No new BUY signals when the regime is BEAR. */
    var BLOCK_BUYS_IN_BEAR = true

    // ---------------------------------------------------------------- MOM_PULLBACK
    var MP_MOM_LOOKBACK = 126
    var MP_MOM_SKIP = 5
    /** Top fraction of the universe by momentum that qualifies (0.20 = top 20%). */
    var MP_TOP_FRACTION = 0.20
    var MP_PULLBACK_MIN_PCT = 3.0
    var MP_PULLBACK_MAX_PCT = 8.0
    var MP_NEAR_20DMA_PCT = 2.0
    var MP_STOP_ATR = 1.75
    var MP_STOP_CAP_PCT = 8.0
    var MP_T1_R = 1.0
    var MP_T2_R = 2.0

    // ---------------------------------------------------------------- BREAKOUT_VOL
    var BO_LOOKBACK = 55
    var BO_VOL_MULT = 2.0
    /** Close must be in the top 30% of the day's range => IBS >= 0.70. */
    var BO_MIN_IBS = 0.70
    var BO_MAX_EXT_PCT = 6.0
    var BO_STOP_ATR = 2.0
    var BO_T1_R = 1.0
    var BO_T2_R = 2.0

    // ---------------------------------------------------------------- LEADER_DIP (research/REPORT3.md)
    var LD_ENABLED = true
    var LD_RSI_MAX = 5.0
    var LD_TOP_FRACTION = 0.30
    var LD_STOP_ATR = 3.0
    var LD_STOP_CAP_PCT = 15.0
    var LD_SLOPE_BARS = 10
    var LD_EXIT_SMA = 5
    /** Filled from the long real-data run of this exact rule through the app's engine; null = judge on the phone's data only. */
    var LD_RESEARCH: StratStats? = StratStats().also { it.n = 1228; it.winPct = 61.6; it.avgPct = 0.14; it.pf = 1.07; it.maxDdPct = 64.0; it.oosN = 589; it.oosAvgPct = 0.55 }
    // ---------------------------------------------------------------- DIP_UPTREND
    var DIP_RSI_PERIOD = 2
    var DIP_RSI_MAX = 5.0
    var DIP_IBS_MAX = 0.30
    var DIP_STOP_ATR = 2.0
    var DIP_STOP_CAP_PCT = 8.0
    var DIP_T1_ATR = 0.75
    var DIP_T2_ATR = 1.5
    var DIP_T2_SMA = 5

    // ---------------------------------------------------------------- WEEKLY_LEADERS
    // --- WASHOUT_DIP (research: top-4 avg +1.7% in-sample / +1.25% out-of-sample per 5-day trade after charges, 2013–2026)
    var WASHOUT_ENABLED = true
    var WO_BREADTH_MAX = 0.25
    var WO_RSI_MAX = 10.0
    var WO_STOP_ATR = 3.0
    var WO_STOP_CAP_PCT = 12.0
    // --- MONTHLY_LEADERS (research: top-10 risk-adjusted 6m momentum, 21-day hold: 30%/18% CAGR in/out of sample, drawdowns 35-40%)
    var MONTHLY_LEADERS_ENABLED = true
    var ML_TOP_N = 10
    var ML_LOOKBACK = 126
    var ML_SKIP = 5
    var ML_HOLD_DAYS = 21
    var ML_STOP_ATR = 4.0
    var ML_STOP_CAP_PCT = 15.0
    var WL_TOP_N = 5
    var WL_RET_SHORT = 63
    var WL_RET_LONG = 126
    var WL_WEIGHT_SHORT = 0.5
    var WL_STOP_ATR = 2.0
    var WL_STOP_CAP_PCT = 10.0

    // ---------------------------------------------------------------- indicators
    var ATR_PERIOD = 14
    /** Wilder smoothing (ATR / RSI) is seeded at most this many bars before the bar being evaluated. */
    var WILDER_WINDOW = 150

    // ---------------------------------------------------------------- backtest gate (LIVE only if ALL pass)
    var GATE_MIN_TRADES = 60
    var GATE_MIN_AVG_PCT = 0.25
    var GATE_MIN_PF = 1.2
    var GATE_MIN_OOS_TRADES = 20
    /** Out-of-sample average % per trade must be strictly above this. */
    var GATE_MIN_OOS_AVG_PCT = 0.0
    /** Most recent fraction of test dates treated as out-of-sample. */
    var OOS_FRACTION = 0.35
    /** Re-run the backtest automatically when the stats are older than this many days. */
    var BACKTEST_MAX_AGE_DAYS = 7
    /** Backtest takes at most this many new trades per strategy per day (best rank first). */
    var BT_MAX_NEW_PER_DAY = 4
    /** The backtest holds at most this many trades per strategy at once, like a small real account. */
    var BT_MAX_CONCURRENT = 4
    /** Capital share given to each backtest trade when building the equity curve for max drawdown. */
    var BT_POSITION_WEIGHT = 0.25
    /** Minimum symbols with daily history before a backtest is attempted. */
    var BT_MIN_SYMBOLS = 5

    // ---------------------------------------------------------------- sizing / signals
    var MAX_POSITION_FRACTION = 0.25
    var ENTRY_MAX_MULT = 1.005
    /** Buy window, minutes after midnight IST (14:45 - 15:25). */
    var BUY_WINDOW_START_MIN = 14 * 60 + 45
    var BUY_WINDOW_END_MIN = 15 * 60 + 25
    /** TIME_EXIT is called from this minute of the last holding day (15:00). */
    var TIME_EXIT_FROM_MIN = 15 * 60
    /** A quote older than this many minutes is too stale to base a new BUY on. */
    var STALE_QUOTE_MIN = 40

    // ---------------------------------------------------------------- portfolio check
    // which portfolio rules may call SELL / TRIM (set from the 2011–2026 study; see Portfolio.kt)
    var PF_SELL_ON_FALLING_50 = false
    var PF_SELL_ON_STOP_BREAK = false
    var PF_SELL_ON_REL_WEAK = false
    var PF_TRIM_ON_STRENGTH = false
    var PF_MIN_BARS = 60
    var PF_TRAIL_LOOKBACK = 22
    var PF_TRAIL_ATR = 3.0
    var PF_SWING_LOOKBACK = 10
    var PF_BOOK_LOOKBACK = 250
    var PF_BOOK_ATR = 2.0
    var PF_SELL_BELOW_STOP_PCT = 1.0
    var PF_SELL_RELVOL = 1.2
    var PF_RS_LOOKBACK = 63
    var PF_RS_WEAK_PTS = -15.0
    var PF_TRIM_ABOVE_50DMA_PCT = 22.0
    var PF_TRIM_RSI = 80.0
    var PF_BLOWOFF_RANGE_ATR = 2.5
    var PF_BLOWOFF_RELVOL = 3.0
    var PF_BLOWOFF_UP_PCT = 5.0
    var PF_MAX_WEIGHT_PCT = 20.0
    var PF_SMA50_SLOPE_BARS = 10

    // ---------------------------------------------------------------- data
    var DAILY_BARS_CAP = 520
    var CACHE_SHARDS = 16
    var CHART_BARS = 130
    /** Up to this many stocks, every scan fetches a live quote for all of them; above it, only when buys are possible. */
    var FULL_QUOTE_MAX_UNIVERSE = 250
    /** When no strategy is LIVE, still show (clearly labelled, paper-tracked) setups from enabled strategies that failed the test. */
    /** EMA watchlist: 10-day EMA within this fraction of the 20-day EMA (either side) and converging. */
    var WATCH_EMA_GAP_MAX = 0.005
    var WATCH_MAX = 20
    var EXPERIMENTAL_ENABLED = true
    var EXPERIMENTAL_MAX_PER_DAY = 3
    /** A strategy judged on its long research test is paused if the phone's own recent test has at least this many trades and lost money. */
    var RESEARCH_VETO_MIN_TRADES = 10
    var HTTP_BATCH = 40
    var YAHOO_HOST_1 = "https://query1.finance.yahoo.com"
    var YAHOO_HOST_2 = "https://query2.finance.yahoo.com"
    const val INDEX_SYMBOL = "^NSEI"

    // ---------------------------------------------------------------- defaults for user settings
    const val DEF_CAPITAL = 100000.0
    const val DEF_RISK_PCT = 0.75
    const val DEF_MAX_OPEN = 4
    const val DEF_COST_PCT = 0.25
}
