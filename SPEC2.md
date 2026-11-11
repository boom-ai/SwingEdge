# SwingEdge — contract addendum v2: stock search, stock page, personal watchlist
(SPEC.md still applies. This file is FIXED for this round: three engineers build against it in parallel. Do not change it; report problems.)

## New bundled asset
`app/src/main/assets/symbols.csv` — every NSE-listed equity, one per line `SYMBOL,Company Name` (no header, UTF-8, no quotes; commas inside names removed). ~2,000+ lines. Read by core via `Platform.asset("symbols.csv")`. May be missing or partial: core must cope (fall back to universe.csv + aliases.csv names).

## New / changed actions (core implements, UI calls via `Android.action(name, argsJson)`; results arrive at `window.onActionResult(name, json)`)

### `search` {"q": "tata mot"} — no network, must be fast (<50 ms)
→ `{"ok":true,"q":"tata mot","results":[{"sym":"TATAMOTORS","name":"Tata Motors Ltd","tracked":true}]}`
- Max 25 results. Ranking: exact symbol, symbol prefix, name word-prefix (every query word is a prefix of some word in the name, any order), symbol contains, name contains. Case-insensitive; ignore punctuation and "ltd/limited".
- `tracked` = symbol is in the scanned universe. Empty/1-char query → empty results.
- UI must match a result to the latest query using the echoed `q`.

### `stockInfo` {"sym": "TATAMOTORS"} — may use the network (5–15 s worst case); any NSE symbol, not only tracked ones
Uses cached daily history when it is fresh for today, otherwise fetches 2y daily; always tries a live quote (range=5d&interval=15m). Never throws.
Failure: `{"ok":false,"sym":"X","error":"No price data found for X. Check the symbol."}`.
Success (every field always present; numbers are plain; unknown number = 0; arrays may be empty):
```json
{"ok":true,"sym":"TATAMOTORS","name":"Tata Motors Ltd","tracked":true,"isHolding":false,"inWatch":false,
 "asOf":0,                     // epoch ms of the price used
 "live":true,                  // false = last completed day's close (no live quote available)
 "barDay":20261111,            // yyyymmdd of the latest bar used for indicators
 "bars":498,                   // daily bars available (indicators needing more bars than this are 0)
 "ltp":0,"prevClose":0,"dayChgPct":0,"open":0,"dayHigh":0,"dayLow":0,
 "volume":0,"avgVol20":0,"volRatio":0,"turnoverCr":0,"liquid":true,
 "ema":{"e10":0,"e20":0,"e50":0,"e100":0,"e200":0},
 "emaDist":{"e10":0,"e20":0,"e50":0,"e100":0,"e200":0},      // price vs each EMA in % (positive = price above)
 "stack":{"label":"BULLISH","text":"10 > 20 > 50 > 100 > 200: every average is stacked in order"},   // BULLISH | MIXED | BEARISH
 "cross":{"state":"ABOVE","daysSince":3,"gapPct":0.42,"converging":false,
          "text":"10 EMA crossed above the 20 EMA 3 days ago and the gap is widening"},
          // state ABOVE|BELOW (10 EMA vs 20 EMA); daysSince = bars since the last cross (0 = latest bar, -1 = more than 60 bars / unknown);
          // gapPct = (e10-e20)/e20*100 signed; converging = |gap| smaller than 2 bars ago
 "golden":{"state":"ABOVE","daysSince":120,"text":"50 EMA above the 200 EMA for 120 days"},   // 50 vs 200 EMA, same conventions
 "rsi14":0,"rsi2":0,"atr":0,"atrPct":0,
 "ret":{"d5":0,"d21":0,"d63":0,"d126":0,"d250":0},            // % returns over trading days
 "vsNifty":{"d21":0,"d63":0,"d126":0},                         // stock return minus Nifty return, % points
 "high52":0,"low52":0,"fromHighPct":0,"fromLowPct":0,"high20":0,"low20":0,
 "levels":{"support":0,"resistance":0,"trailStop":0},         // 20-day low, 20-day high (or 52w high if within 3%), chandelier stop (22-day highest close − 3×ATR)
 "strength":0,                                                  // same 0–100 trend-strength score as holdings
 "verdict":{"label":"HOLD","headline":""},                    // what the portfolio check would say if you held it: HOLD|WATCH|TRIM|SELL
 "checks":[{"label":"Above the 200 EMA","ok":true,"text":"₹934 vs ₹812 (+15.0%)"}],     // 6–9 plain-English technical checks, most important first
 "setups":[{"id":"EMA_CROSS","label":"Fresh 10/20 EMA cross","match":false,"text":"Crossed 3 days ago — day-0 only"}],
          // one entry per screen/strategy the app knows: EMA_CROSS (the Setups-tab screen) and each ENABLED strategy; text says why it does / does not qualify today
 "summary":"Two or three plain sentences: trend, momentum, what would change the picture."}
```
Wording rules: plain English, ₹ levels, no "guaranteed/sure shot/risk-free", no buy/sell instruction in `summary` (describe, don't advise); a not-liquid stock says so.

### `getChart` {"sym": "X"} — CHANGED
- Must now work for ANY NSE symbol (fetch if not cached), same failure shape as stockInfo.
- Adds arrays `ema100` (and keeps `ema10`,`ema20`,`ema50`,`ema200`, `sma50`,`sma200`, `d,o,h,l,c`, `levels`, `stats`), plus new array `v` (volume per bar).
- New optional arg `"bars": 130|260|500` (default 130) = how many bars to return.

### `addWatch` {"sym":"X"} / `removeWatch` {"sym":"X"}
Personal watchlist, persisted, max 50 symbols, any NSE symbol (validated like addHolding). → `{"ok":true}` or error.
Watchlist symbols get a live quote on EVERY scan (like holdings).

## State JSON additions (SPEC.md schema plus):
```json
"myWatch": [{"sym":"","name":"","ltp":0,"dayChgPct":0,"spark":[0],"strength":0,"stack":"BULLISH",
             "crossText":"10 EMA above 20 EMA for 3 days","dataOk":true}]
```
(`watch` — the EMA-cross screen — is unchanged: sym,name,ltp,dayChgPct,ema10,ema20,ema50,ema100,ema200,gapPct,daysSince,barDay,spark,status.)

## UI requirements (summary; UI engineer's brief has the detail)
- Search entry point always visible in the header; results as you type; recent searches kept in memory.
- A full "stock page" for any symbol (opened from search, from any symbol tap anywhere in the app, from Setups rows): price header, chart with EMA toggles and 6M/1Y/2Y range, technical checklist, EMA table with % distance, 10/20 convergence card, returns, vs Nifty, levels, verdict-if-held, which setups it matches, buttons Add to watchlist / Add to holdings.
- "My watchlist" section on the Setups tab above the EMA-cross screen.
