# SwingEdge — contract addendum v5: live NSE stock list, analyst panel, new listings (FIXED for this round)

Decisions from the design debate (quant, product, data — see research/out_r6 and the chat):
- The tested call (score + AVOID/BUY/WAIT + plan P2) is NOT changed by the panel. No manager showed a consistent, significant
  effect inside BUY (research/out_r6/managers.csv), so managers argue and explain; they never vote the call up or down.
  Only execution gates may block a BUY (already: upper-circuit lock, entry zone, liquidity).
- New listings (< 253 bars) stay NOT RATED; they get a facts card plus the measured base rate.

## 1. Stock list
- Bundled asset `symbols.csv` now has a header and 4 columns: `SYMBOL,NAME,LISTED,BOARD` (LISTED like `27-Jun-25` or `06-OCT-2008`; BOARD `EQ` or `SME`). The old 2-column format (`SYMBOL,Name`, no header) must still parse.
- Daily refresh (once per calendar day, on the first tick/open after 07:30 IST, and on action `refreshSymbols` {}):
  1. `https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv` and `https://nsearchives.nseindia.com/emerge/corporates/content/SME_EQUITY_L.csv` (direct, phone IP);
  2. fallback `https://raw.githubusercontent.com/boom-ai/SwingEdge/main/data/symbols.csv` (refreshed daily by a GitHub Action, same 4-column format);
  3. else keep the last good list (stored file "symbols_live.csv"), else the bundled asset.
  Validate (≥ 1,500 main-board rows, expected columns, not HTML) before replacing. NSE headers: `SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING,…` (note leading spaces) and SME: `SYMBOL,NAME_OF_COMPANY,SERIES,DATE_OF_LISTING,…`.
- Search rows gain `"listedDay": 20260314` (yyyymmdd, 0 unknown), `"isNew": true` (listed within the last 365 days), `"board": "EQ"|"SME"`.
- Search result gains `"list": {"count": 3218, "updatedAt": 0, "source": "nse"|"github"|"saved"|"bundled", "stale": false, "newCount": 0}` (stale = older than 3 days).
- New action `newListings` {"days": 365} → `{"ok":true,"results":[{sym,name,listedDay,board,tracked}]}` newest first, max 100, no network.

## 2. `stockInfo.panel` (always present; managers that cannot compute say so as NEUTRAL)
```json
"panel": {
  "tally": {"for": 0, "against": 0, "neutral": 0},
  "managers": [{
    "id": "trend",            // trend | momentum | risk | liquidity | timing | market | newlisting
    "name": "Trend",
    "stance": "FOR",          // FOR | AGAINST | NEUTRAL
    "strength": 2,            // 0–3, how far into the tail the stock sits (percentile based)
    "evidence": "tested, weak",   // fixed tag per manager from research/out_r6: trend "tested, weak"; momentum "= the score";
                                  // risk "no edge (describes risk)"; liquidity "for execution only"; timing "no edge";
                                  // market "no edge — weak markets were followed by rebounds"; newlisting "base rate only"
    "text": "Price 5.4% above the 200-day average, which is rising; 50-day above 200-day",
    "answers": "risk",        // id of the strongest opposing manager, "" if none
    "rebuttal": "",           // template-filled with real numbers, "" if none
    "metrics": [{"label": "vs 200-day", "value": "+5.4%"}]
  }],
  "chair": {
    "label": "BUY",           // mirrors call.label ("NA" allowed)
    "proven": false,          // mirrors call.proven
    "text": "The call comes from the tested score, not from the vote: …",
    "pros": ["…"], "cons": ["…"],   // max 4 each, plain words with numbers
    "flipUp": "…", "flipDown": "…"  // what would upgrade / downgrade the call (from the score rules: 85 / 55 / 50-day / 200-day thresholds, with ₹ levels)
  },
  "asOfDay": 20261112
}
```
Manager rules (from the quant proposal; percentiles are vs the scan table's rated universe when available, else absolute thresholds stated in the text):
- trend: FOR if C > SMA50 > SMA200 and SMA200 rising over 21 bars; AGAINST if C < SMA200 and SMA200 falling; else NEUTRAL.
- momentum: average percentile of 6-1 and 12-1 returns ≥ 0.7 FOR, ≤ 0.3 AGAINST.
- risk: 126-day volatility (or ATR%) percentile: bottom third FOR, top third AGAINST; text states the 5-ATR stop distance in % and ₹ at risk for the user's size.
- liquidity: TO20 ≥ ₹100 cr FOR, < ₹25 cr AGAINST (execution), text in ₹ crore/day.
- timing: (C − SMA50)/ATR14: 0–2 FOR (not stretched), ≥ the universe's 90th percentile or > 6 AGAINST (stretched); RSI(2) in metrics.
- market: Nifty vs its 200-day and breadth (% of rated universe above SMA50): above and > 50% FOR, below and < 40% AGAINST; evidence tag says this had no edge.
- newlisting: only when bars < 253: NEUTRAL with listing facts; AGAINST-strength 1 with the base rate text.

## 3. `stockInfo.listing` (null when bars ≥ 253)
```json
"listing": {"isNew": true, "listedDay": 20260314, "daysListed": 243, "bars": 160, "listPrice": 0, "ltp": 0,
            "sinceListingPct": 0, "highSince": 0, "highDay": 0, "lowSince": 0, "lowDay": 0, "rangePos": 0,
            "maxDdPct": 0, "avgValueCr": 0, "barsToRated": 93,
            "baseRate": "Stocks listed 2022–25 lagged the average liquid stock by about 4% per 60 trading days in their first year (183 stocks). Not a forecast for this one."}
```
listedDay from the stock list when known, else the first bar's date; listPrice = first bar's open.
