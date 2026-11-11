# SwingEdge — shared contract (READ FULLY; do not change the contract, report problems instead)

Android app for one user (NSE cash equities, India, IST). Gives 5–6 trading-day swing BUY ideas, SELL/exit calls,
follow-ups on trades he took, and a health check of his existing portfolio. Scans every 15 minutes in market hours
(09:15–15:30 IST, Mon–Fri) and posts phone notifications. Capital default ₹1,00,000, risk per trade 0.75%.

## Hard product rules
- Never use the words "guaranteed", "sure shot", "risk-free" or similar anywhere. Every BUY shows its tested numbers.
- A strategy may emit live BUY signals only while its in-app backtest verdict is LIVE (see Strategies). Otherwise it is PAUSED.
- Nothing may crash: every network/parse/storage failure degrades gracefully and is shown as a status line.
- Money maths always includes charges (delivery round trip default 0.25% of turnover).

## Architecture (fixed)
- Root: /home/claude/SwingEdge (Gradle Android project, single module `app`, package `com.boomai.swingedge`).
- NO third-party or androidx dependencies at all. Kotlin stdlib + Android framework only. minSdk 26, target/compileSdk 34.
  Kotlin 2.0.21, AGP 8.5.2, Gradle 8.9, JDK 17. (This sandbox cannot reach Maven; the APK is built by GitHub Actions.)
- `app/src/main/java/com/boomai/swingedge/core/` — PURE Kotlin, no `android.*`, no `org.json`, no java.time-desugaring issues
  (java.time is fine on API 26+). Owns ALL logic: fetching via Platform, parsing, indicators, strategies, backtest,
  trade follow-up, portfolio advice, persistence format, notification decisions, and building the UI state JSON.
- `app/src/main/java/com/boomai/swingedge/` (non-core) — thin Android shell: WebView activity, JS bridge, scheduling,
  notifications, file storage, HTTP. No trading logic.
- `app/src/main/assets/ui/index.html` (+ optional css/js files beside it) — the whole UI, runs in a WebView, offline assets only
  (no CDN, no web fonts, no external requests).

### core ⇄ shell contract
`core/Platform.kt` already exists (interface Platform). Core exposes exactly:

```kotlin
package com.boomai.swingedge.core
class SwingApp(private val p: Platform) {
    fun stateJson(): String                              // full UI state (schema below). Fast, no network. Never throws.
    fun action(name: String, argsJson: String): String   // {"ok":true,...} or {"ok":false,"error":"..."}; may do network; never throws
    fun tick(trigger: String): String                    // trigger: "alarm"|"manual"|"boot"|"open". Does whatever is due now. Never throws. Returns short status text.
    fun nextWakeAt(): Long                               // epoch ms when the shell should call tick("alarm") next
}
```
All three mutating methods are internally synchronized (one runs at a time); `stateJson()` must not block behind a long
tick/backtest (serve last built snapshot + `busy`). Shell always calls `tick`/`action` on a background thread.
Data source (core builds URLs): Yahoo Finance chart API, e.g.
`https://query1.finance.yahoo.com/v8/finance/chart/RELIANCE.NS?range=2y&interval=1d` and `...?range=5d&interval=15m`
(fallback host query2). Index: `%5ENSEI`. Shell sends header `User-Agent: Mozilla/5.0`.
Files via Platform.read/write (names like "state.json", "daily_cache.json"); asset "universe.csv" lines `SYMBOL,Company Name`.

### UI ⇄ shell bridge (JavascriptInterface object named `Android`)
- `Android.getState(): string` → SwingApp.stateJson()
- `Android.action(name: string, argsJson: string): void` → runs on a background thread; when finished the shell calls
  `window.onActionResult(name, resultJsonString)` and then `window.onStateChanged()` on the UI thread.
- `Android.pickHoldingsFile(): void` → system file picker; shell reads the text and runs action `importHoldings` with `{"text": "<file text>"}`.
- `Android.requestNotifications(): void`, `Android.openBatterySettings(): void`, `Android.openExactAlarmSettings(): void`
- `Android.deviceStatus(): string` → `{"notifications":bool,"batteryUnrestricted":bool,"exactAlarms":bool}`
- Shell calls `window.onStateChanged()` whenever a tick finishes. UI also polls getState every 3 s while `busy` is non-null, else every 20 s.
- When `window.Android` is undefined (desktop browser), the UI must use a built-in mock with realistic sample data.

### Actions (name, args → effect)
- `scanNow` {} · `runBacktest` {} (long; updates `busy`)
- `addHolding` {sym, qty, avg} · `removeHolding` {sym} · `importHoldings` {text} (CSV/TSV/pasted text; heuristic header detection; returns {ok, added, skipped:[..]})
- `takeTrade` {signalId, price?, qty?} (user bought it → becomes an OPEN trade that gets follow-ups)
- `skipSignal` {signalId} · `closeTrade` {tradeId, price} · `saveSettings` {capital, riskPct, maxOpenTrades, costPct, notifyBuy, notifySell, notifyBrief}
- `clearFeed` {}

### State JSON schema (all numbers plain; prices in ₹; times epoch ms; fields always present, arrays may be empty)
```json
{
 "now": 0, "marketOpen": true, "marketNote": "Open · closes 15:30",
 "lastScanAt": 0, "lastScanStatus": "OK · 186/190 stocks", "nextScanAt": 0,
 "busy": null,                         // or {"label":"Backtesting…","pct":40}
 "settings": {"capital":100000,"riskPct":0.75,"maxOpenTrades":4,"costPct":0.25,"notifyBuy":true,"notifySell":true,"notifyBrief":true},
 "regime": {"label":"BULL","niftyLtp":0,"niftyChgPct":0.4,"note":"Nifty above 50 & 200 DMA"},   // BULL|NEUTRAL|BEAR
 "signals": [{"id":"","sym":"","name":"","strategyId":"","strategyLabel":"","createdAt":0,"validTill":0,
   "ltp":0,"entry":0,"entryMax":0,"sl":0,"t1":0,"t2":0,"qty":0,"investRs":0,"riskRs":0,"rewardRs":0,"holdDays":6,
   "edge":{"n":0,"winPct":0,"avgPct":0,"pf":0},"reasons":["",""],"status":"NEW"}],             // NEW|TAKEN|SKIPPED|EXPIRED
 "trades": [{"id":"","sym":"","name":"","strategyLabel":"","status":"OPEN","openedAt":0,"entry":0,"qty":0,
   "sl":0,"t1":0,"t2":0,"day":1,"maxDays":6,"ltp":0,"pnlRs":0,"pnlPct":0,
   "action":"HOLD","actionNote":"","t1Done":false,"closedAt":0,"exitPrice":0,"result":""}],   // action: HOLD|BOOK_HALF|EXIT|TIME_EXIT ; status OPEN|CLOSED
 "holdings": [{"sym":"","name":"","qty":0,"avg":0,"ltp":0,"valueRs":0,"pnlRs":0,"pnlPct":0,"dayChgPct":0,
   "verdict":"HOLD","headline":"","stop":0,"bookAt":0,"reasons":["",""],"dataOk":true}],      // verdict: HOLD|TRIM|SELL|WATCH
 "portfolio": {"investedRs":0,"valueRs":0,"pnlRs":0,"pnlPct":0,"dayPnlRs":0,"sellCount":0,"trimCount":0},
 "strategies": [{"id":"","label":"","desc":"","verdict":"LIVE","testedAt":0,
   "stats":{"n":0,"winPct":0,"avgPct":0,"pf":0,"maxDdPct":0,"oosN":0,"oosAvgPct":0},"why":""}], // LIVE|PAUSED|UNTESTED
 "scorecard": {"n":0,"winPct":0,"netRs":0,"avgPct":0,"paperN":0,"paperWinPct":0,"paperAvgPct":0,
   "recent":[{"sym":"","closedAt":0,"result":"","pnlRs":0,"pnlPct":0,"paper":false}]},
 "feed": [{"ts":0,"kind":"buy","title":"","body":""}]                                          // kind: buy|sell|brief|info|warn ; newest first, max 100
}
```
