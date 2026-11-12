# SwingEdge — contract addendum v3: buy verdict ("call"), user targets, interactive charts
(SPEC.md and SPEC2.md still apply. This file is FIXED for this round; report problems instead of changing it.)

## 1. `stockInfo` gains a `call` object (always present on ok:true)
```json
"call": {
  "label": "BUY",                 // BUY | WAIT | AVOID   (WAIT = decent stock, not a good entry now; AVOID = fails the trend/quality bar)
  "score": 78,                    // 0–100 setup score (the researched composite); 0 when it cannot be computed
  "headline": "Strong leader near its high — qualifies as a buy setup",
  "reasons": ["…", "…"],          // 3–6 short plain-English lines: what earns points, what loses them
  "entryLow": 0, "entryHigh": 0,  // suggested entry zone (0 when label is AVOID)
  "stop": 0, "t1": 0, "t2": 0,    // stop-loss and two targets in ₹ (0 when label is AVOID)
  "stopPct": 0, "t1Pct": 0, "t2Pct": 0,   // distance from ltp in %, signed
  "rr": 0,                        // reward-to-risk of t1 vs stop from ltp
  "horizonDays": 40,              // intended holding period in trading days
  "qty": 0, "investRs": 0, "riskRs": 0,   // position size from the user's capital/risk settings (0 unless BUY)
  "hist": {"n": 0, "winPct": 0, "avgPct": 0, "medianPct": 0, "worstPct": 0, "horizonDays": 40,
           "text": "Stocks scoring 70+ gained in 58% of cases over the next 40 trading days, +4.1% on average after charges (2013–2026, 9,200 cases)."},
  "waitFor": "",                  // for WAIT: what would turn it into BUY (e.g. "a close above ₹912 on above-average volume")
  "caveat": "Rule-based and tested on past data. Losses happen; the stop limits them, it does not remove them."
}
```
`hist` describes what historically happened to stocks in the same score band (from the research tables baked into the app); n=0 and an explanatory text when unknown.

## 2. State: `ideas` (the ranked buy list produced by the same scoring during full scans)
```json
"ideas": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"score":0,"label":"BUY","headline":"",
           "entryLow":0,"entryHigh":0,"stop":0,"t1":0,"t2":0,"rr":0,"horizonDays":40,"spark":[0],"barDay":20261112}]
```
Top 15 by score among liquid stocks whose label is BUY, refreshed on every full scan (may be empty; also empty until the first full scan). These are screen results with tested statistics, shown on the Today and Setups tabs; they do NOT replace `signals` (signals remain the short-term strategy calls).

## 3. User targets ("target option")
Actions:
- `setTarget` {"sym":"X","target":950.0,"stop":870.0,"note":"optional text"} — target and/or stop (either may be 0/absent, not both); any NSE symbol; replaces an existing ACTIVE target for the symbol; max 50. → `{"ok":true}` or error (e.g. target must be above the last price for an upside target is NOT enforced: a target below price is a "falls to" alert; just validate numbers > 0).
- `clearTarget` {"sym":"X"} → `{"ok":true}`.
State:
```json
"targets": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"target":0,"stop":0,"note":"",
             "setAt":0,"refPrice":0,          // price when the target was set
             "status":"ACTIVE",               // ACTIVE | HIT_TARGET | HIT_STOP
             "hitAt":0,"toTargetPct":0,"toStopPct":0,"progressPct":0}]   // progressPct 0–100 from refPrice to target
```
Target symbols get a live quote on EVERY scan. When ltp crosses the target (in the direction it was set from refPrice) or the stop, status changes once, a notification is posted (channel "sell" for both; title like "TATAPOWER hit your target ₹450") and a feed entry is added. HIT rows stay listed until cleared. `stockInfo` also returns `"myTarget": {"target":0,"stop":0,"note":"","status":""}` (zeros/empty when none).

## 4. Charts (UI only; data comes from the existing `getChart` {sym, bars: 130|260|500} with d,o,h,l,c,v, ema10/20/50/100/200, levels)
- Tapping the chart on the stock page opens a FULL-SCREEN chart view (own sheet kind `chartfull`).
- Full-screen chart is interactive: pinch to zoom the time axis (down to ~20 bars, out to all loaded bars; loads 500 bars), one-finger drag to pan, long-press or tap-and-hold then drag for a crosshair with a readout (date, O, H, L, C, change %, volume, and each visible EMA's value at that bar), double-tap to reset; price axis auto-fits the visible bars; EMA toggles, range chips (3M/6M/1Y/2Y/All), volume strip; the `call` entry zone, stop, t1, t2 and the user's own target/stop drawn as labelled horizontal lines (toggle "Levels").
- The inline chart on the stock page also supports the crosshair readout on press-and-drag, without blocking vertical page scroll.

## v3.1 amendments after the research (REPORT4): the score is DESCRIPTIVE, the buy rule is UNPROVEN
- `call.label` values stay BUY | WAIT | AVOID, plus a fourth: `NA` (not rated: illiquid, < 253 daily bars, or no full-universe ranking available yet). For `NA`: score 0, levels 0, headline says why.
- New fields in `call` (always present):
  - `"proven": false` — true only if the rule beat the benchmark out-of-sample. It is false for BUY and WAIT; true for AVOID (the reliable finding: low scores lagged).
  - `"evidence": "…"` — one or two sentences with the honest out-of-sample record of this label (for BUY: the trade plan's record incl. win rate, average, median, and that a ₹1 lakh portfolio of these calls did not beat the Nifty in 2022–26; for AVOID: how much such stocks lagged and how consistently).
  - `"band": "85-100"`, `"points": [{"label":"6-month momentum (risk-adjusted)","pts":18.2,"max":20}, …5 items]`.
  - `"exitRule": "Sell if it closes below its 50-day average, or after 60 trading days. Targets are reference levels, not sell orders."`
- `horizonDays` is 60. Levels per REPORT4: entryLow = last close (or ltp), entryHigh = close + 0.5×ATR14, stop = close − 3×ATR14, t1 = close + 6×ATR14, t2 = close + 9×ATR14 (simple-mean ATR14). Levels are given for BUY and WAIT (WAIT levels are indicative), zeros for AVOID and NA.
- `hist` comes from the embedded out-of-sample band table (raw, after costs): n, winPct, avgPct, medianPct, worstPct (5th percentile), horizonDays 60, and a `text` that also states the excess over the average liquid stock and whether it is statistically reliable.
- UI wording rule: when `proven` is false and label is BUY, the pill reads "BUY CANDIDATE" and the `evidence` text is shown prominently directly under the headline. Position size (qty/investRs/riskRs) is still given for BUY, sized from the stop.
- `ideas` = rated stocks with label BUY, highest score first (max 15), each with `"proven": false`.
