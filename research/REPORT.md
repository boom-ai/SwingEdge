# Long-only swing strategies (hold <= 6 sessions) on liquid NSE cash equities: do any have an edge after costs?

Backtest window 2013-01-01 to 2026-11-09 (signals need 6 further sessions, so the last few days produce no trades).
In-sample (IS) = 2013-2021 (65%), out-of-sample (OOS) = 2022 to Nov 2026 (35%). All numbers are net of costs unless stated.

## 0. Bottom line

**No strategy is LIVE-worthy.** Of 20 variants in 6 families, tested with 3 regime settings, 2 entry conventions and 2 cost levels:

* Every family except A fails out-of-sample at the IS-chosen settings (T9): breakout (C), strong-day continuation (F), weekly momentum rotation (D), short-term reversal (E) and momentum-pullback (B) have OOS average net per trade between -0.34% and +0.13% at the close entry and are all negative at the next-open entry.
* **A2 (close > 200DMA, RSI(2) < 5, exit on first close above the 5DMA or day 6, no stop) is MARGINAL, not live-worthy.** Per-trade it is positive in both halves (IS +0.37%, OOS +0.30% at close entry and 0.25% cost, no regime filter; month-clustered t about 2.2 in each half). But:
  1. The edge sits in cluster days. About 41% of trades fall on the busiest 10% of signal days (market-wide sell-offs). Weighting each signal day equally, OOS average is -0.04% (close entry) and -0.17% (next-open entry). The 4 best-ranked signals per day, which is all a 4-slot account can take, average +0.15% OOS at the close and +0.04% at the next open (T8).
  2. The 4-slot portfolio made 7.3% CAGR IS and 8.1% OOS at the close entry with -37% / -28% max drawdown. Nifty 50 buy-and-hold (price index, no dividends) made 12.6% IS and 7.5% OOS. At the next-open entry the portfolio made 0.8% / 0.2%. At 0.40% cost it is negative for both entries (-0.6%/-1.1% at close).
  3. The close-entry number assumes the signal is known and filled at the official close. A 15:00-15:20 scan sees a different price, so real results lie between the two entry conventions.
  4. Recent years are flat to negative for the portfolio: 2024 -1.1%, 2025 -2.4%, 2026 YTD -0.2% (close entry); -13.9%, -8.1%, +0.4% (next open).
* **A6 (same signal with RSI(2) < 10, exit only at the day-6 close) is MARGINAL and weaker.** Per-trade +0.36% IS / +0.34% OOS at close, but CIs include zero, win rate is about 51%, portfolio drawdowns are -42% to -50%, and 2025 / 2026 YTD portfolio returns are -9.5% / -18.2%.
* The user's report that mean reversion lost money recently is consistent with the data: A1 (RSI2 < 10) portfolio -5.2% in 2025 and -1.6% in 2026 YTD; adding a 2-ATR stop (A5) lowers the average trade in both halves; IBS < 0.2 (A4) loses in every period.
* Stops and regime filters did not rescue anything. A stop reduced A's expectancy. Regime filters raised A2's per-trade average slightly (IS-chosen filter Nifty > 200DMA: OOS +0.38%) but lowered the 4-slot portfolio CAGR (5.6% OOS, -35% drawdown).
* The baseline (random liquid stock above its 200DMA, 6 days) earns +0.16% IS / +0.07% OOS per trade at the close entry and -0.06% / -0.14% at the next open. Most candidates are indistinguishable from it.

Practical reading for a Rs 1,00,000 account: none of these rules is expected to beat simply holding an index fund, and several lose money steadily. If A2 is shipped at all it should be labelled experimental, with the realistic numbers in section 4.

## 1. Data and its limits

* **Source 1 (prices, volume, the universe): github.com/tilak999/NSE-Data-bank**, commit 2d9f33c (9 Nov 2026). Official NSE full bhavcopies `data/sec_bhavdata_full_DDMMYYYY.csv`, 4,053 files, 2010-06-10 to 2026-11-09, series EQ/BE/BZ, 4,394 symbols including delisted ones. Prices are UNADJUSTED.
* **Source 2 (Nifty 50 index, rename stitching): github.com/BennyThadikaran/eod2_data**, commit 5d94770 (9 Nov 2026). `daily/nifty 50.csv` from 2012-02-21. Its stock files claim split/bonus adjustment but this snapshot is not reliably adjusted (e.g. SHRIRAMFIN 5:1 split on 2025-01-10 and INDIAMART 1:1 bonus on 2023-06-21 appear as raw gaps), so its stock prices were NOT used. Its files were used only to stitch symbol renames (old raw symbol matched to the new name by identical daily volumes; 412 segments, `work/renames_detected.csv`).
* **Own corporate-action adjustment** (`scripts/03_build_adj_panel.py`): an overnight move with open/prev close < 0.75 or > 1.40, confirmed by close/prev close < 0.80 or > 1.30, is treated as a split / bonus / demerger / consolidation. Earlier prices are multiplied by the nearest standard ratio when within 6% (827 events), otherwise by the observed gap itself, which neutralises the event (43 events, 6 of them in liquid stocks, mostly demergers). Log: `work/adjustments.csv`.
* **Panel**: 1,707 symbols that ever had 20-day average turnover >= Rs 25 crore; 3,976 sessions. Stocks passing the liquidity + price filter on a median day: about 90 in 2013, 140 in 2015-16, 200 in 2017-18, 310 in 2021-22, 540 in 2024, 600 in 2026. Turnover is computed as close x volume.
* **Regime index**: Nifty 50 price index. Backtest starts 2013 so its 200DMA exists.

Limits that matter:
1. Small bonuses (1:4 or smaller, gap above -25%) and all dividends are not adjusted. They appear as small false losses, which biases long results slightly down.
2. Neutralising unmatched gaps also erases any genuine overnight crash larger than 25% in a liquid stock. These are very rare but the bias is upward.
3. Survivorship: delisted and merged stocks are in the raw files, so the universe is largely survivorship-free. A stock renamed and later removed from eod2 appears as two separate histories (it loses 200 sessions of warm-up, nothing else).
4. ETF-like tickers were removed with a name pattern (386 symbols, `work/excluded_etf_like.csv`); the pattern also removed a handful of real small caps (e.g. SKYGOLD, SMLISUZU, BONDADA).
5. The fixed nominal Rs 25 crore threshold makes the universe grow over time; early years have about 90 names.
6. Sessions on weekends (Muhurat etc.) and two thin files (2022-05-04, 2024-11-18) were dropped.
7. NSE's "close" is the last-30-minute VWAP. The open is on average 0.20% above the previous close across liquid stock-days (median also 0.20%), which is why the next-open entry is systematically worse.
8. Not modelled: taxes (short-term capital gains), integer share sizes, lower-circuit days on which a stop cannot be filled (optimistic for stop strategies), fixed per-order charges beyond the percentage cost.

## 2. Method

* Signal at day D close using data up to D. Entry (a) D close, (b) D+1 open. Eligibility at D: 20-day average turnover >= Rs 25 crore, unadjusted close >= Rs 50, 200DMA available, and not locked at an upper circuit (close = high with a gain at the 5/10/20% band).
* Exits on daily bars from D+1: gap through stop exits at the open; stop inside the bar exits at the stop; stop is checked before target; then target; then close-based exits. Time exit at the close of D+6 (D+5 for the 5-day strategies D, E, F). For entry (b) the stop/target can trigger on the entry day itself.
* Stops are k x ATR(14, Wilder) at D below the entry price; target = entry + 2 x that distance where stated.
* One trade per stock at a time. Cost 0.25% (and 0.40%) of entry value per round trip.
* Portfolio: at most 4 positions, each 25% of realised equity, best signal rank first, daily mark-to-market for drawdown. D charges the full cost every week even when a name is retained (slightly pessimistic).
* Confidence intervals: bootstrap over calendar months of entry (trades on the same days are highly correlated, so a plain t-stat overstates significance). `t` = mean / bootstrap standard error.
* Selection: per family the variant + regime with the best IS clustered t (entry a, 0.25%) was fixed and then read out-of-sample (T9). Nothing was tuned on OOS data. The full grid is 20 variants; no further parameter search was done.
* Baseline: every liquid stock above its 200DMA, held 6 sessions, random rank for the portfolio.

## 3. Verdicts

| Family | Verdict | Reason |
|---|---|---|
| A2 RSI(2)<5 above 200DMA, exit close>5DMA | **MARGINAL** | Positive per trade IS and OOS at both entries, but edge is in cluster days; 4-slot portfolio does not beat the index and is negative at 0.40% cost or next-open entry |
| A6 RSI(2)<10 above 200DMA, time exit | **MARGINAL (weaker)** | Similar per-trade average, CI includes zero, -42% to -50% drawdowns, negative 2025-26 |
| A1 RSI(2)<10, exit close>5DMA | REJECT | OOS +0.12% (CI -0.15..0.38); day-weighted negative |
| A3 three down closes | REJECT | About +0.1% at close, negative at next open |
| A4 IBS<0.2 | REJECT | Negative everywhere |
| A5 RSI(2)<10 with 2-ATR stop | REJECT | Stop removes the edge (about 0.0%) |
| B1-B4 momentum-leader pullback | REJECT | About 0.0% at close, -0.2% to -0.5% at next open |
| C20/C55/C250 volume breakout | REJECT | OOS about 0.0% at close, -0.4% to -0.5% at next open |
| D63/D126 weekly top-4 momentum | REJECT | IS weak, OOS about zero or negative, -60% or worse drawdowns |
| E1/E2 5-day losers | REJECT | Not different from baseline |
| F1/F2 strong-day continuation | REJECT | Looked good IS at close (+0.45%), failed OOS (-0.12%); strongly negative at next open (overfit / regime-specific, and the gain is given back overnight) |

## 4. Exact rules of the marginal strategies

Common definitions (daily bars, split/bonus-adjusted prices; `C[t]` close, `H`, `L`, `O`):
```
SMA(n)[t]    = mean(C[t-n+1 .. t])
RSI2[t]      : d = C[t]-C[t-1]; gain = max(d,0); loss = max(-d,0)
               avgGain[t] = 0.5*gain + 0.5*avgGain[t-1]   (Wilder, period 2; seed with avgGain = gain of the first bar,
               avgLoss[t] = 0.5*loss + 0.5*avgLoss[t-1]    use at least 10 bars of history before trusting the value)
               RSI2 = 100 - 100/(1 + avgGain/avgLoss)      (avgLoss == 0 -> RSI2 = 100)
AvgTurnover20[t] = mean over the last 20 sessions of (unadjusted close * volume)   in rupees
Eligible[t]  = AvgTurnover20[t] >= 25e7  AND  unadjusted C[t] >= 50  AND  SMA(200)[t] exists (>= 180 bars)
               AND NOT (C[t] >= 0.9995*H[t] AND |C[t]/C[t-1]-1 - b| < 0.003 for b in {0.05, 0.10, 0.20})
```

### A2 (marginal; the only one worth showing, as experimental)
```
SIGNAL on day D (evaluate as late as possible; the backtest uses the official close):
    Eligible[D] AND C[D] > SMA(200)[D] AND RSI2[D] < 5
RANK:   lowest RSI2[D] first
SKIP:   stock already held; no free slot (max 4 positions, 25% of equity each)
ENTRY:  buy at D close (convention a) or at D+1 open (convention b)
STOP:   none            TARGET: none
EXIT:   on the first day X in D+1 .. D+6 where C[X] > SMA(5)[X]  -> sell at X close
        otherwise sell at the close of D+6
```
Stats to display (2013 - Nov 2026, no regime filter):

| | close entry, 0.25% | next-open entry, 0.25% | close entry, 0.40% |
|---|---|---|---|
| Trades (all signals) | 15,468 (about 1,100 a year) | 15,468 | 15,468 |
| Win % | 63.8 | 61.6 | 62.1 |
| Avg net per trade | +0.33% (IS +0.37, OOS +0.30) | +0.27% (IS +0.33, OOS +0.22) | +0.18% (IS +0.22, OOS +0.15) |
| Median net per trade | +1.05% | +0.87% | +0.90% |
| Profit factor | 1.21 | 1.18 | 1.11 |
| Avg hold | 3.6 sessions | 3.6 | 3.6 |
| OOS 95% CI of avg | +0.05..+0.56 | -0.00..+0.47 | -0.10..+0.41 |
| **Avg of the 4 top-ranked signals per day** | **IS +0.18%, OOS +0.15%** | **IS +0.08%, OOS +0.04%** | about +0.03% / 0.00% |
| 4-slot portfolio CAGR / max DD | 7.6% / -37% | 0.6% / -48% | -0.8% / -55% |

The number to show a 4-slot user is the top-4 line (roughly +0.1% per trade, about 62% winners, losers larger than winners), not the all-signal average.
Worst years for the portfolio (close entry): 2022 -15%, 2018 -11%, 2015 -7%, 2019 -6%.

### A6 (marginal, weaker; not recommended)
```
SIGNAL: Eligible[D] AND C[D] > SMA(200)[D] AND RSI2[D] < 10      RANK: lowest RSI2 first
ENTRY:  D close (a) or D+1 open (b)       STOP/TARGET: none       EXIT: close of D+6
```
Close entry, 0.25%: IS 12,191 trades, 52.4% wins, +0.36% (CI -0.07..0.77), PF 1.18; OOS 15,805 trades, 50.6% wins, +0.34% (CI -0.05..0.72), PF 1.16. Top-4 per day: IS +0.24%, OOS +0.18% (close), +0.13% / +0.06% (next open). Portfolio CAGR 9.5% IS / 8.9% OOS at close with -50% / -42% drawdown; 2025 -9.5%, 2026 YTD -18.2%.

## 5. Universe
`/home/claude/research/universe.txt`: 574 NSE tickers that passed the liquidity (20-day average turnover >= Rs 25 crore) and price (>= Rs 50) filter on 2026-11-09, ETF-like tickers excluded. It includes recent listings that do not yet have a 200DMA.

## 6. Reproduce
```
python3 -I scripts/01_parse_raw.py data/NSE-Data-bank/data work/raw.pkl
python3 -I scripts/02_build_panel.py data/eod2_data/daily work/raw.pkl work    # only its renames_detected.csv is used; it runs out of memory after writing it
python3 -I scripts/03_build_adj_panel.py work/raw.pkl data/eod2_data/daily work
python3 -I scripts/04_backtest.py work out      # out/results.csv, out/by_year.csv, out/trades_*.pkl, out/eq_*.pkl
python3 -I scripts/05_diag.py work out          # out/diag.csv
python3 -I scripts/06_tables.py work out universe.txt
```
`02c_build_panel_lean.py` is an abandoned intermediate (it trusted eod2 adjustment) and is not part of the pipeline.

## 7. Result tables
### T0. Strategy key

| id | strategy |
|---|---|
| A1 | A1 RSI2<10, exit C>5DMA |
| A2 | A2 RSI2<5, exit C>5DMA |
| A3 | A3 3 down closes, exit C>5DMA |
| A4 | A4 IBS<0.2, exit C>5DMA |
| A5 | A5 RSI2<10, exit C>5DMA, stop 2ATR |
| A6 | A6 RSI2<10, time exit day 6 |
| B1 | B1 leader, near 20DMA, stop1.5ATR tgt2R |
| B2 | B2 leader, 3-8% off 20d high, stop1.5ATR tgt2R |
| B3 | B3 leader, near 20DMA, stop2ATR time |
| B4 | B4 leader, 3-8% off 20d high, stop2ATR time |
| C20 | C20 breakout 20d high, vol2x, stop2ATR |
| C55 | C55 breakout 55d high, vol2x, stop2ATR |
| C250 | C250 breakout 250d high, vol2x, stop2ATR |
| D63 | D63 weekly top4 by 63d return |
| D126 | D126 weekly top4 by 126d return |
| E1 | E1 worst 5% 5d return (>200DMA), hold 5 |
| E2 | E2 worst 4 by 5d return (>200DMA), hold 5 |
| F1 | F1 +4% on 3x vol near high, hold 5 |
| F2 | F2 same & close>200DMA, hold 5 |
| BASE | BASE random liquid >200DMA, hold 6 |

### T1. All candidates, no regime filter, cost 0.25% (IS = 2013-2021, OOS = 2022 - Nov 2026)
entry a = D close, b = D+1 open. avg/med = net % per trade; CI/t = month-clustered bootstrap; CAGR/MDD = 4-slot portfolio.

| id | entry | IS n | IS win% | IS avg% | IS med% | IS 95%CI | IS t | IS PF | IS hold | IS CAGR% | IS MDD% | OOS n | OOS win% | OOS avg% | OOS med% | OOS 95%CI | OOS t | OOS PF | OOS hold | OOS CAGR% | OOS MDD% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | a | 12446 | 63.84 | 0.21 | 0.98 | -0.10..0.47 | 1.40 | 1.14 | 3.55 | 1.42 | -47.59 | 16101 | 61.47 | 0.12 | 0.88 | -0.15..0.38 | 0.91 | 1.08 | 3.63 | 12.74 | -26.85 |
| A1 | b | 12445 | 61.29 | 0.10 | 0.76 | -0.18..0.36 | 0.76 | 1.07 | 3.55 | -5.96 | -60.69 | 16101 | 59.58 | 0.07 | 0.74 | -0.18..0.32 | 0.57 | 1.04 | 3.63 | 2.20 | -31.52 |
| A2 | a | 6743 | 65.19 | 0.37 | 1.11 | 0.01..0.69 | 2.20 | 1.25 | 3.53 | 7.28 | -36.79 | 8725 | 62.67 | 0.30 | 0.97 | 0.05..0.56 | 2.26 | 1.19 | 3.64 | 8.14 | -28.08 |
| A2 | b | 6743 | 63.01 | 0.33 | 0.92 | 0.02..0.62 | 2.15 | 1.22 | 3.53 | 0.82 | -47.95 | 8725 | 60.50 | 0.22 | 0.81 | -0.00..0.47 | 1.84 | 1.14 | 3.64 | 0.15 | -39.26 |
| A3 | a | 14285 | 63.99 | 0.11 | 0.86 | -0.14..0.34 | 0.88 | 1.07 | 3.40 | -8.63 | -86.98 | 18313 | 61.82 | 0.06 | 0.82 | -0.14..0.27 | 0.60 | 1.04 | 3.48 | -1.22 | -53.29 |
| A3 | b | 14285 | 60.55 | -0.04 | 0.63 | -0.27..0.17 | -0.36 | 0.97 | 3.40 | -15.01 | -90.28 | 18308 | 59.20 | -0.06 | 0.61 | -0.25..0.12 | -0.66 | 0.96 | 3.48 | -8.30 | -53.67 |
| A4 | a | 34191 | 59.03 | -0.18 | 0.44 | -0.35..-0.02 | -2.15 | 0.87 | 2.92 | -30.38 | -96.39 | 47342 | 59.11 | -0.15 | 0.46 | -0.35..0.05 | -1.44 | 0.90 | 2.88 | -42.12 | -93.15 |
| A4 | b | 34191 | 53.82 | -0.37 | 0.18 | -0.53..-0.23 | -4.81 | 0.74 | 2.92 | -32.76 | -97.38 | 47340 | 54.00 | -0.34 | 0.21 | -0.51..-0.17 | -3.80 | 0.78 | 2.88 | -33.76 | -86.87 |
| A5 | a | 13360 | 62.89 | 0.04 | 0.97 | -0.26..0.31 | 0.26 | 1.02 | 3.21 | -0.91 | -63.02 | 17276 | 61.04 | 0.01 | 0.89 | -0.24..0.25 | 0.08 | 1.01 | 3.29 | 11.51 | -36.85 |
| A5 | b | 13348 | 60.51 | -0.06 | 0.75 | -0.34..0.20 | -0.43 | 0.97 | 3.22 | -8.63 | -72.95 | 17196 | 59.46 | -0.02 | 0.75 | -0.25..0.22 | -0.18 | 0.99 | 3.31 | -0.15 | -39.53 |
| A6 | a | 12191 | 52.39 | 0.36 | 0.26 | -0.07..0.77 | 1.69 | 1.18 | 6.00 | 9.50 | -49.61 | 15805 | 50.62 | 0.34 | 0.07 | -0.05..0.72 | 1.74 | 1.16 | 6.00 | 8.93 | -41.70 |
| A6 | b | 12190 | 51.13 | 0.27 | 0.12 | -0.15..0.66 | 1.26 | 1.13 | 6.00 | 5.61 | -52.54 | 15805 | 49.73 | 0.28 | -0.04 | -0.09..0.67 | 1.51 | 1.13 | 6.00 | 0.78 | -46.44 |
| B1 | a | 3125 | 46.62 | 0.03 | -0.52 | -0.33..0.38 | 0.14 | 1.01 | 5.16 | -4.95 | -46.13 | 3622 | 46.25 | 0.04 | -0.68 | -0.48..0.56 | 0.16 | 1.02 | 5.07 | 4.66 | -45.80 |
| B1 | b | 3140 | 44.20 | -0.27 | -0.82 | -0.61..0.06 | -1.55 | 0.89 | 5.14 | -14.38 | -76.99 | 3623 | 44.30 | -0.25 | -0.93 | -0.74..0.27 | -0.95 | 0.91 | 5.06 | -11.70 | -59.12 |
| B2 | a | 3652 | 44.30 | -0.17 | -0.80 | -0.53..0.18 | -0.92 | 0.93 | 5.18 | -7.00 | -78.56 | 4772 | 45.93 | 0.05 | -0.74 | -0.50..0.57 | 0.17 | 1.02 | 5.04 | -3.28 | -48.14 |
| B2 | b | 3675 | 41.50 | -0.52 | -1.16 | -0.86..-0.19 | -2.98 | 0.81 | 5.15 | -19.99 | -89.50 | 4801 | 43.41 | -0.29 | -1.16 | -0.82..0.19 | -1.13 | 0.90 | 5.02 | -19.31 | -70.40 |
| B3 | a | 3081 | 48.26 | 0.04 | -0.25 | -0.38..0.44 | 0.19 | 1.02 | 5.62 | -12.43 | -72.73 | 3559 | 48.22 | 0.14 | -0.30 | -0.44..0.72 | 0.46 | 1.05 | 5.60 | 5.63 | -31.78 |
| B3 | b | 3086 | 45.69 | -0.27 | -0.52 | -0.69..0.13 | -1.31 | 0.89 | 5.59 | -19.38 | -86.59 | 3564 | 46.49 | -0.15 | -0.51 | -0.71..0.42 | -0.51 | 0.95 | 5.59 | -5.19 | -42.08 |
| B4 | a | 3569 | 45.70 | -0.17 | -0.56 | -0.58..0.22 | -0.82 | 0.93 | 5.62 | -8.72 | -70.99 | 4680 | 47.93 | 0.09 | -0.31 | -0.52..0.67 | 0.30 | 1.03 | 5.59 | -2.50 | -46.62 |
| B4 | b | 3573 | 42.93 | -0.50 | -0.87 | -0.90..-0.12 | -2.53 | 0.82 | 5.60 | -19.65 | -86.73 | 4687 | 45.51 | -0.23 | -0.70 | -0.84..0.32 | -0.78 | 0.92 | 5.58 | -8.99 | -59.68 |
| C20 | a | 5695 | 46.51 | 0.17 | -0.49 | -0.19..0.50 | 0.96 | 1.07 | 5.49 | -4.10 | -67.28 | 8507 | 45.10 | -0.10 | -0.74 | -0.54..0.31 | -0.45 | 0.96 | 5.44 | -30.02 | -84.06 |
| C20 | b | 5701 | 43.12 | -0.35 | -1.07 | -0.69..-0.05 | -2.14 | 0.87 | 5.41 | -19.65 | -88.98 | 8512 | 42.65 | -0.51 | -1.08 | -0.91..-0.12 | -2.49 | 0.82 | 5.39 | -30.61 | -85.29 |
| C55 | a | 4304 | 45.52 | 0.09 | -0.74 | -0.31..0.47 | 0.42 | 1.03 | 5.46 | -1.47 | -54.08 | 6318 | 45.71 | -0.00 | -0.65 | -0.45..0.42 | -0.00 | 1.00 | 5.43 | -15.96 | -64.60 |
| C55 | b | 4313 | 42.13 | -0.44 | -1.25 | -0.82..-0.07 | -2.32 | 0.85 | 5.38 | -17.94 | -83.74 | 6320 | 43.23 | -0.46 | -1.02 | -0.86..-0.07 | -2.28 | 0.84 | 5.37 | -23.62 | -73.91 |
| C250 | a | 2838 | 45.98 | 0.21 | -0.67 | -0.25..0.64 | 0.93 | 1.08 | 5.46 | 7.18 | -40.28 | 3990 | 46.52 | 0.10 | -0.58 | -0.40..0.58 | 0.41 | 1.04 | 5.43 | -13.60 | -59.48 |
| C250 | b | 2842 | 42.75 | -0.35 | -1.21 | -0.80..0.06 | -1.62 | 0.88 | 5.37 | -12.07 | -70.29 | 3990 | 43.78 | -0.42 | -1.01 | -0.88..0.02 | -1.79 | 0.86 | 5.36 | -18.40 | -69.06 |
| D63 | a | 1760 | 48.58 | 0.23 | -0.18 | -0.29..0.75 | 0.89 | 1.08 | 5.00 | 3.07 | -75.47 | 908 | 44.93 | 0.05 | -0.78 | -0.72..0.82 | 0.13 | 1.02 | 5.00 | -4.71 | -59.33 |
| D63 | b | 1760 | 47.56 | 0.08 | -0.30 | -0.43..0.60 | 0.32 | 1.03 | 5.00 | -4.10 | -78.50 | 908 | 44.49 | -0.06 | -1.03 | -0.78..0.66 | -0.17 | 0.98 | 5.00 | -9.42 | -67.58 |
| D126 | a | 1760 | 46.99 | 0.08 | -0.39 | -0.45..0.63 | 0.31 | 1.03 | 5.00 | -4.25 | -84.17 | 908 | 47.14 | 0.10 | -0.34 | -0.68..0.87 | 0.26 | 1.03 | 5.00 | -2.74 | -59.51 |
| D126 | b | 1760 | 45.57 | -0.12 | -0.61 | -0.65..0.41 | -0.44 | 0.96 | 5.00 | -13.17 | -90.14 | 908 | 45.93 | -0.10 | -0.79 | -0.77..0.59 | -0.29 | 0.97 | 5.00 | -10.91 | -60.80 |
| E1 | a | 5615 | 49.53 | 0.11 | -0.05 | -0.24..0.42 | 0.65 | 1.05 | 5.00 | -16.66 | -90.52 | 7335 | 48.11 | 0.07 | -0.23 | -0.31..0.41 | 0.37 | 1.03 | 5.00 | 1.03 | -49.98 |
| E1 | b | 5614 | 47.33 | -0.09 | -0.32 | -0.43..0.21 | -0.53 | 0.96 | 5.00 | -20.77 | -93.07 | 7335 | 46.07 | -0.15 | -0.44 | -0.50..0.17 | -0.87 | 0.94 | 5.00 | -6.12 | -51.33 |
| E2 | a | 4293 | 49.48 | -0.03 | -0.05 | -0.35..0.28 | -0.17 | 0.99 | 5.00 | -17.60 | -92.18 | 2367 | 49.47 | 0.04 | -0.10 | -0.50..0.56 | 0.13 | 1.01 | 5.00 | 3.69 | -47.04 |
| E2 | b | 4291 | 46.98 | -0.19 | -0.36 | -0.49..0.10 | -1.24 | 0.92 | 5.00 | -24.78 | -95.43 | 2367 | 47.61 | -0.12 | -0.26 | -0.61..0.36 | -0.48 | 0.95 | 5.00 | -5.06 | -45.82 |
| F1 | a | 3124 | 48.18 | 0.34 | -0.25 | -0.07..0.71 | 1.74 | 1.14 | 5.00 | -2.04 | -71.50 | 5946 | 44.94 | -0.19 | -0.69 | -0.62..0.24 | -0.84 | 0.93 | 5.00 | -32.95 | -87.83 |
| F1 | b | 3122 | 45.04 | -0.22 | -0.76 | -0.61..0.13 | -1.19 | 0.92 | 5.00 | -17.39 | -86.50 | 5945 | 43.40 | -0.53 | -0.97 | -0.90..-0.15 | -2.79 | 0.81 | 5.00 | -34.72 | -88.64 |
| F2 | a | 2576 | 48.18 | 0.45 | -0.27 | 0.03..0.84 | 2.20 | 1.19 | 5.00 | 6.19 | -49.94 | 4752 | 45.58 | -0.12 | -0.64 | -0.55..0.31 | -0.56 | 0.95 | 5.00 | -13.88 | -63.28 |
| F2 | b | 2574 | 44.91 | -0.11 | -0.82 | -0.50..0.25 | -0.56 | 0.96 | 5.00 | -7.47 | -67.83 | 4751 | 43.57 | -0.54 | -0.94 | -0.90..-0.17 | -2.89 | 0.81 | 5.00 | -19.76 | -70.44 |
| BASE | a | 45662 | 49.01 | 0.16 | -0.12 | -0.15..0.47 | 1.04 | 1.08 | 6.00 | 1.30 | -62.17 | 58617 | 47.64 | 0.07 | -0.27 | -0.32..0.45 | 0.37 | 1.03 | 6.00 | 4.65 | -40.88 |
| BASE | b | 45657 | 47.28 | -0.06 | -0.32 | -0.35..0.23 | -0.38 | 0.97 | 6.00 | -5.71 | -72.49 | 58611 | 46.14 | -0.14 | -0.45 | -0.50..0.20 | -0.78 | 0.94 | 6.00 | 0.26 | -39.94 |

### T2. Same, cost 0.40%

| id | entry | IS n | IS win% | IS avg% | IS 95%CI | IS PF | IS CAGR% | IS MDD% | OOS n | OOS win% | OOS avg% | OOS 95%CI | OOS PF | OOS CAGR% | OOS MDD% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | a | 12446 | 62.16 | 0.06 | -0.25..0.32 | 1.04 | -7.48 | -67.61 | 16101 | 59.70 | -0.03 | -0.30..0.23 | 0.98 | 2.43 | -34.93 |
| A1 | b | 12445 | 59.48 | -0.05 | -0.33..0.21 | 0.97 | -14.22 | -80.09 | 16101 | 57.79 | -0.08 | -0.33..0.17 | 0.95 | -7.17 | -39.61 |
| A2 | a | 6743 | 63.46 | 0.22 | -0.14..0.54 | 1.14 | -0.63 | -54.87 | 8725 | 60.96 | 0.15 | -0.10..0.41 | 1.09 | -1.10 | -36.19 |
| A2 | b | 6743 | 61.37 | 0.18 | -0.13..0.47 | 1.12 | -6.62 | -64.83 | 8725 | 58.74 | 0.07 | -0.15..0.32 | 1.04 | -8.42 | -48.67 |
| A3 | a | 14285 | 61.88 | -0.04 | -0.29..0.19 | 0.97 | -16.84 | -92.15 | 18313 | 60.03 | -0.09 | -0.29..0.12 | 0.95 | -9.78 | -55.01 |
| A3 | b | 14285 | 58.42 | -0.19 | -0.42..0.02 | 0.88 | -22.65 | -95.03 | 18308 | 57.08 | -0.21 | -0.40..-0.03 | 0.87 | -16.25 | -64.95 |
| A4 | a | 34191 | 56.02 | -0.33 | -0.50..-0.17 | 0.77 | -38.07 | -98.73 | 47342 | 56.25 | -0.30 | -0.50..-0.10 | 0.80 | -47.96 | -95.90 |
| A4 | b | 34191 | 50.73 | -0.52 | -0.68..-0.38 | 0.66 | -40.19 | -99.07 | 47340 | 51.14 | -0.49 | -0.66..-0.32 | 0.69 | -40.44 | -92.11 |
| A5 | a | 13360 | 61.28 | -0.11 | -0.41..0.16 | 0.94 | -10.41 | -76.77 | 17276 | 59.41 | -0.14 | -0.39..0.10 | 0.93 | 0.44 | -43.15 |
| A5 | b | 13348 | 58.76 | -0.21 | -0.49..0.05 | 0.88 | -17.37 | -86.21 | 17196 | 57.74 | -0.17 | -0.40..0.07 | 0.91 | -10.06 | -48.32 |
| A6 | a | 12191 | 51.02 | 0.21 | -0.22..0.62 | 1.10 | 3.39 | -60.37 | 15805 | 49.21 | 0.19 | -0.20..0.57 | 1.08 | 2.87 | -46.08 |
| A6 | b | 12190 | 49.72 | 0.12 | -0.30..0.51 | 1.05 | -0.29 | -64.66 | 15805 | 48.39 | 0.13 | -0.24..0.52 | 1.06 | -4.84 | -50.47 |
| B1 | a | 3125 | 45.54 | -0.12 | -0.48..0.23 | 0.95 | -10.41 | -66.42 | 3622 | 45.44 | -0.11 | -0.63..0.41 | 0.96 | -1.89 | -50.00 |
| B1 | b | 3140 | 43.34 | -0.42 | -0.76..-0.09 | 0.83 | -19.37 | -86.41 | 3623 | 43.39 | -0.40 | -0.89..0.12 | 0.85 | -17.30 | -65.50 |
| B2 | a | 3652 | 43.13 | -0.32 | -0.68..0.03 | 0.88 | -12.51 | -86.18 | 4772 | 45.14 | -0.10 | -0.65..0.42 | 0.96 | -9.51 | -53.89 |
| B2 | b | 3675 | 40.46 | -0.67 | -1.01..-0.34 | 0.76 | -24.78 | -93.26 | 4801 | 42.60 | -0.44 | -0.97..0.04 | 0.85 | -24.51 | -78.49 |
| B3 | a | 3081 | 47.03 | -0.11 | -0.53..0.29 | 0.95 | -17.17 | -83.25 | 3559 | 47.23 | -0.01 | -0.59..0.57 | 0.99 | -0.38 | -36.47 |
| B3 | b | 3086 | 44.78 | -0.42 | -0.84..-0.02 | 0.83 | -23.77 | -91.88 | 3564 | 45.54 | -0.30 | -0.86..0.27 | 0.89 | -10.60 | -50.35 |
| B4 | a | 3569 | 44.35 | -0.32 | -0.73..0.07 | 0.88 | -13.79 | -80.76 | 4680 | 47.05 | -0.06 | -0.67..0.52 | 0.98 | -8.20 | -50.63 |
| B4 | b | 3573 | 41.90 | -0.65 | -1.05..-0.27 | 0.77 | -24.14 | -92.07 | 4687 | 44.57 | -0.38 | -0.99..0.17 | 0.87 | -14.31 | -65.90 |
| C20 | a | 5695 | 45.46 | 0.02 | -0.34..0.35 | 1.01 | -9.46 | -72.21 | 8507 | 44.13 | -0.25 | -0.69..0.16 | 0.91 | -34.42 | -88.34 |
| C20 | b | 5701 | 42.05 | -0.50 | -0.84..-0.20 | 0.82 | -24.20 | -93.27 | 8512 | 41.52 | -0.66 | -1.06..-0.27 | 0.77 | -34.95 | -89.10 |
| C55 | a | 4304 | 44.59 | -0.06 | -0.46..0.32 | 0.98 | -6.52 | -59.19 | 6318 | 44.71 | -0.15 | -0.60..0.27 | 0.95 | -21.05 | -69.25 |
| C55 | b | 4313 | 41.09 | -0.59 | -0.97..-0.22 | 0.80 | -22.19 | -89.90 | 6320 | 42.01 | -0.61 | -1.01..-0.22 | 0.79 | -28.24 | -80.67 |
| C250 | a | 2838 | 45.07 | 0.06 | -0.40..0.49 | 1.02 | 2.51 | -45.43 | 3990 | 45.46 | -0.05 | -0.55..0.43 | 0.98 | -18.52 | -69.08 |
| C250 | b | 2842 | 41.56 | -0.50 | -0.95..-0.09 | 0.83 | -15.96 | -80.11 | 3990 | 42.63 | -0.57 | -1.03..-0.13 | 0.81 | -23.06 | -76.40 |
| D63 | a | 1760 | 47.39 | 0.08 | -0.44..0.60 | 1.03 | -4.21 | -79.19 | 908 | 44.05 | -0.10 | -0.87..0.67 | 0.97 | -11.20 | -69.74 |
| D63 | b | 1760 | 46.53 | -0.07 | -0.58..0.45 | 0.98 | -10.89 | -84.31 | 908 | 43.61 | -0.21 | -0.93..0.51 | 0.94 | -15.59 | -75.89 |
| D126 | a | 1760 | 46.08 | -0.07 | -0.60..0.48 | 0.98 | -11.03 | -88.57 | 908 | 46.26 | -0.05 | -0.83..0.72 | 0.98 | -9.35 | -62.73 |
| D126 | b | 1760 | 44.83 | -0.27 | -0.80..0.26 | 0.92 | -19.33 | -93.17 | 908 | 43.83 | -0.25 | -0.92..0.44 | 0.92 | -16.98 | -69.71 |
| E1 | a | 5615 | 48.12 | -0.04 | -0.39..0.27 | 0.98 | -22.35 | -94.31 | 7335 | 46.88 | -0.08 | -0.46..0.26 | 0.97 | -5.79 | -54.52 |
| E1 | b | 5614 | 46.06 | -0.24 | -0.58..0.06 | 0.90 | -26.18 | -95.85 | 7335 | 44.89 | -0.30 | -0.65..0.02 | 0.88 | -12.47 | -60.67 |
| E2 | a | 4293 | 47.78 | -0.18 | -0.50..0.13 | 0.93 | -23.46 | -95.42 | 2367 | 48.67 | -0.11 | -0.65..0.41 | 0.96 | -3.35 | -50.54 |
| E2 | b | 4291 | 45.82 | -0.34 | -0.64..-0.05 | 0.86 | -30.12 | -97.32 | 2367 | 46.77 | -0.27 | -0.76..0.21 | 0.90 | -11.52 | -53.78 |
| F1 | a | 3124 | 47.12 | 0.19 | -0.22..0.56 | 1.07 | -7.33 | -75.86 | 5946 | 43.84 | -0.34 | -0.77..0.09 | 0.88 | -37.42 | -91.25 |
| F1 | b | 3122 | 44.30 | -0.37 | -0.76..-0.02 | 0.87 | -21.88 | -90.96 | 5945 | 42.35 | -0.68 | -1.05..-0.30 | 0.77 | -39.06 | -91.84 |
| F2 | a | 2576 | 47.13 | 0.30 | -0.12..0.69 | 1.12 | 1.26 | -56.22 | 4752 | 44.51 | -0.27 | -0.70..0.16 | 0.90 | -19.43 | -72.15 |
| F2 | b | 2574 | 44.25 | -0.26 | -0.65..0.10 | 0.91 | -11.78 | -76.90 | 4751 | 42.50 | -0.69 | -1.05..-0.32 | 0.77 | -24.95 | -78.23 |
| BASE | a | 45662 | 47.66 | 0.01 | -0.30..0.32 | 1.01 | -4.72 | -72.90 | 58617 | 46.51 | -0.08 | -0.47..0.30 | 0.97 | -1.30 | -45.96 |
| BASE | b | 45657 | 45.97 | -0.21 | -0.50..0.08 | 0.91 | -11.32 | -81.26 | 58611 | 44.91 | -0.29 | -0.65..0.05 | 0.88 | -5.44 | -45.07 |

### T3. Market-regime filter (Nifty 50 above its 50DMA / 200DMA) vs none, cost 0.25%: avg net % per trade

| s | entry | n50>200dma IS avg% | n50>200dma OOS avg% | n50>50dma IS avg% | n50>50dma OOS avg% | none IS avg% | none OOS avg% |
|---|---|---|---|---|---|---|---|
| A1 | a | 0.28 | 0.22 | 0.23 | 0.19 | 0.21 | 0.12 |
| A1 | b | 0.16 | 0.13 | 0.11 | 0.09 | 0.10 | 0.07 |
| A2 | a | 0.47 | 0.38 | 0.40 | 0.35 | 0.37 | 0.30 |
| A2 | b | 0.39 | 0.26 | 0.34 | 0.21 | 0.33 | 0.22 |
| A3 | a | 0.17 | 0.11 | 0.16 | 0.13 | 0.11 | 0.06 |
| A3 | b | -0.01 | -0.04 | 0.01 | -0.05 | -0.04 | -0.06 |
| A4 | a | -0.19 | -0.12 | -0.17 | -0.09 | -0.18 | -0.15 |
| A4 | b | -0.38 | -0.34 | -0.37 | -0.33 | -0.37 | -0.34 |
| A5 | a | 0.11 | 0.13 | 0.08 | 0.04 | 0.04 | 0.01 |
| A5 | b | 0.00 | 0.06 | -0.00 | -0.05 | -0.06 | -0.02 |
| A6 | a | 0.43 | 0.45 | 0.44 | 0.25 | 0.36 | 0.34 |
| A6 | b | 0.31 | 0.36 | 0.33 | 0.15 | 0.27 | 0.28 |
| B1 | a | -0.04 | 0.07 | 0.05 | 0.00 | 0.03 | 0.04 |
| B1 | b | -0.31 | -0.18 | -0.21 | -0.25 | -0.27 | -0.25 |
| B2 | a | -0.23 | 0.15 | -0.11 | -0.13 | -0.17 | 0.05 |
| B2 | b | -0.57 | -0.20 | -0.45 | -0.48 | -0.52 | -0.29 |
| B3 | a | -0.00 | 0.20 | 0.11 | 0.13 | 0.04 | 0.14 |
| B3 | b | -0.32 | -0.09 | -0.18 | -0.14 | -0.27 | -0.15 |
| B4 | a | -0.21 | 0.20 | -0.11 | -0.15 | -0.17 | 0.09 |
| B4 | b | -0.55 | -0.15 | -0.43 | -0.50 | -0.50 | -0.23 |
| BASE | a | 0.17 | 0.15 | 0.21 | 0.13 | 0.16 | 0.07 |
| BASE | b | -0.06 | -0.11 | -0.01 | -0.12 | -0.06 | -0.14 |
| C20 | a | 0.16 | -0.02 | 0.22 | -0.07 | 0.17 | -0.10 |
| C20 | b | -0.37 | -0.48 | -0.31 | -0.53 | -0.35 | -0.51 |
| C250 | a | 0.24 | 0.18 | 0.26 | 0.15 | 0.21 | 0.10 |
| C250 | b | -0.33 | -0.37 | -0.29 | -0.39 | -0.35 | -0.42 |
| C55 | a | 0.12 | 0.04 | 0.16 | 0.02 | 0.09 | -0.00 |
| C55 | b | -0.42 | -0.45 | -0.35 | -0.47 | -0.44 | -0.46 |
| D126 | a | 0.08 | 0.22 | 0.32 | 0.10 | 0.08 | 0.10 |
| D126 | b | -0.12 | -0.08 | 0.13 | -0.12 | -0.12 | -0.10 |
| D63 | a | 0.15 | -0.13 | 0.39 | -0.34 | 0.23 | 0.05 |
| D63 | b | -0.00 | -0.28 | 0.27 | -0.39 | 0.08 | -0.06 |
| E1 | a | 0.08 | 0.12 | 0.10 | 0.04 | 0.11 | 0.07 |
| E1 | b | -0.12 | -0.16 | -0.12 | -0.21 | -0.09 | -0.15 |
| E2 | a | -0.16 | -0.10 | -0.12 | 0.01 | -0.03 | 0.04 |
| E2 | b | -0.32 | -0.32 | -0.29 | -0.21 | -0.19 | -0.12 |
| F1 | a | 0.37 | -0.00 | 0.48 | -0.11 | 0.34 | -0.19 |
| F1 | b | -0.19 | -0.45 | -0.10 | -0.57 | -0.22 | -0.53 |
| F2 | a | 0.51 | 0.00 | 0.55 | -0.10 | 0.45 | -0.12 |
| F2 | b | -0.07 | -0.47 | -0.03 | -0.60 | -0.11 | -0.54 |

### T4. Same: 4-slot portfolio CAGR %

| s | entry | n50>200dma IS CAGR% | n50>200dma OOS CAGR% | n50>50dma IS CAGR% | n50>50dma OOS CAGR% | none IS CAGR% | none OOS CAGR% |
|---|---|---|---|---|---|---|---|
| A1 | a | -1.27 | 7.97 | 0.06 | 9.27 | 1.42 | 12.74 |
| A1 | b | -8.54 | -0.74 | -6.11 | 2.63 | -5.96 | 2.20 |
| A2 | a | 4.01 | 5.63 | 4.03 | 6.35 | 7.28 | 8.14 |
| A2 | b | -1.97 | -2.16 | -0.86 | 0.73 | 0.82 | 0.15 |
| A3 | a | -9.56 | 4.93 | -4.90 | 12.30 | -8.63 | -1.22 |
| A3 | b | -15.66 | -2.63 | -10.46 | 5.17 | -15.01 | -8.30 |
| A4 | a | -28.07 | -37.88 | -21.78 | -37.66 | -30.38 | -42.12 |
| A4 | b | -29.21 | -33.20 | -23.36 | -33.27 | -32.76 | -33.76 |
| A5 | a | -2.05 | 12.70 | 0.66 | 10.04 | -0.91 | 11.51 |
| A5 | b | -10.32 | 0.00 | -8.02 | -0.30 | -8.63 | -0.15 |
| A6 | a | 5.02 | -2.44 | 2.19 | 7.35 | 9.50 | 8.93 |
| A6 | b | 0.64 | -8.08 | -1.63 | 1.38 | 5.61 | 0.78 |
| B1 | a | -6.01 | -0.22 | -4.47 | 4.56 | -4.95 | 4.66 |
| B1 | b | -12.31 | -9.80 | -7.09 | -9.41 | -14.38 | -11.70 |
| B2 | a | -10.49 | -5.63 | -11.68 | 3.82 | -7.00 | -3.28 |
| B2 | b | -20.17 | -17.29 | -18.18 | -10.07 | -19.99 | -19.31 |
| B3 | a | -8.32 | 4.13 | -0.11 | 9.20 | -12.43 | 5.63 |
| B3 | b | -16.49 | -5.90 | -8.74 | -0.81 | -19.38 | -5.19 |
| B4 | a | -9.47 | -4.11 | -6.08 | 3.33 | -8.72 | -2.50 |
| B4 | b | -17.17 | -7.63 | -14.08 | -7.85 | -19.65 | -8.99 |
| BASE | a | -5.00 | 4.15 | 0.13 | 4.77 | 1.30 | 4.65 |
| BASE | b | -9.80 | -3.10 | -3.80 | -2.32 | -5.71 | 0.26 |
| C20 | a | -3.41 | -12.87 | 1.70 | -4.18 | -4.10 | -30.02 |
| C20 | b | -17.83 | -20.50 | -9.09 | -13.04 | -19.65 | -30.61 |
| C250 | a | 5.35 | -1.32 | 12.35 | -0.57 | 7.18 | -13.60 |
| C250 | b | -11.60 | -10.03 | -2.84 | -9.54 | -12.07 | -18.40 |
| C55 | a | -0.98 | -3.22 | 5.64 | 0.72 | -1.47 | -15.96 |
| C55 | b | -16.04 | -17.77 | -8.12 | -13.62 | -17.94 | -23.62 |
| D126 | a | -3.42 | 2.78 | 6.77 | -0.83 | -4.25 | -2.74 |
| D126 | b | -10.63 | -6.72 | 0.29 | -6.41 | -13.17 | -10.91 |
| D63 | a | -0.84 | -8.51 | 8.81 | -12.04 | 3.07 | -4.71 |
| D63 | b | -6.31 | -12.85 | 4.56 | -12.98 | -4.10 | -9.42 |
| E1 | a | -12.54 | -12.73 | -6.07 | -1.72 | -16.66 | 1.03 |
| E1 | b | -18.46 | -14.51 | -10.36 | -8.92 | -20.77 | -6.12 |
| E2 | a | -10.70 | -12.73 | -13.67 | -1.72 | -17.60 | 3.69 |
| E2 | b | -17.48 | -14.51 | -16.99 | -8.92 | -24.78 | -5.06 |
| F1 | a | 1.36 | -15.70 | 10.72 | -2.35 | -2.04 | -32.95 |
| F1 | b | -11.94 | -20.71 | -4.58 | -9.76 | -17.39 | -34.72 |
| F2 | a | 8.68 | -6.34 | 15.70 | 1.10 | 6.19 | -13.88 |
| F2 | b | -4.20 | -13.50 | 1.57 | -6.87 | -7.47 | -19.76 |

### T5a. By calendar year, entry a:close, no regime filter, cost 0.25%: avg net % per trade

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | -0.05 | 0.70 | 0.05 | 0.13 | 0.31 | -0.65 | -0.13 | 0.05 | 0.59 | -0.45 | 0.73 | 0.19 | -0.15 | 0.08 |
| A2 | 0.31 | 0.95 | 0.29 | -0.08 | 0.62 | -0.59 | -0.10 | -0.03 | 0.84 | -0.27 | 0.65 | 0.46 | 0.11 | 0.25 |
| A3 | -0.18 | 0.56 | -0.17 | -0.25 | 0.25 | -0.65 | -0.20 | 0.05 | 0.51 | -0.56 | 0.57 | 0.15 | -0.25 | 0.09 |
| A4 | -0.42 | 0.07 | -0.46 | -0.35 | -0.01 | -0.88 | -0.19 | -0.07 | -0.00 | -0.63 | 0.37 | -0.13 | -0.51 | -0.14 |
| A5 | -0.15 | 0.57 | 0.01 | -0.30 | 0.14 | -0.84 | -0.12 | -0.08 | 0.39 | -0.51 | 0.65 | 0.09 | -0.32 | -0.06 |
| A6 | -0.36 | 0.89 | 0.44 | 0.20 | 0.61 | -0.95 | -0.05 | 0.40 | 0.83 | -0.35 | 1.23 | 0.52 | -0.04 | -0.03 |
| B1 | -0.42 | 0.54 | 0.15 | -0.39 | 0.67 | -0.49 | -0.43 | 0.17 | 0.24 | -0.70 | 1.09 | 0.05 | -0.16 | -0.21 |
| B2 | -0.75 | -0.17 | 0.19 | -0.59 | 0.57 | -1.04 | -0.55 | 0.10 | 0.05 | -0.56 | 0.91 | 0.35 | -0.53 | -0.17 |
| B3 | -0.55 | 0.57 | -0.23 | -0.39 | 0.79 | -0.59 | -0.37 | 0.39 | 0.33 | -0.58 | 1.13 | 0.32 | -0.07 | -0.28 |
| B4 | -0.91 | -0.14 | -0.07 | -0.36 | 0.73 | -1.18 | -0.46 | 0.15 | -0.03 | -0.41 | 1.04 | 0.40 | -0.58 | -0.21 |
| BASE | -0.69 | 0.61 | -0.17 | -0.18 | 0.55 | -0.98 | -0.28 | 0.52 | 0.60 | -0.47 | 0.95 | 0.30 | -0.61 | -0.29 |
| C20 | -1.41 | 0.75 | -0.07 | -0.48 | 0.48 | -0.55 | 0.15 | 0.35 | 0.55 | -0.38 | 0.72 | -0.16 | -0.61 | -0.31 |
| C250 | -1.55 | 0.23 | 0.19 | -0.69 | 0.58 | -0.52 | 0.04 | 0.25 | 0.56 | -0.28 | 0.72 | -0.01 | 0.17 | -0.42 |
| C55 | -1.43 | 0.62 | 0.04 | -0.40 | 0.41 | -0.77 | -0.17 | 0.07 | 0.47 | -0.40 | 0.63 | -0.04 | -0.30 | -0.24 |
| D126 | 0.19 | 0.86 | 0.41 | -0.81 | 0.77 | -1.11 | -0.17 | 0.01 | 0.64 | -0.92 | 0.40 | 0.72 | 0.07 | 0.25 |
| D63 | 0.01 | 0.91 | 0.88 | -0.49 | 1.09 | -0.83 | 0.10 | -0.03 | 0.48 | 0.14 | 0.18 | -0.43 | 0.03 | 0.40 |
| E1 | -0.78 | 0.74 | 0.08 | -0.36 | 0.24 | -1.28 | -0.23 | 0.57 | 0.52 | -0.46 | 0.90 | 0.10 | -0.44 | -0.15 |
| E2 | -0.25 | 0.38 | 0.39 | -0.47 | 0.08 | -1.08 | -0.26 | 0.13 | 0.68 | -0.43 | 0.84 | 0.30 | -0.49 | -0.17 |
| F1 | -0.80 | 1.02 | 0.17 | -0.41 | 1.01 | -0.47 | 0.40 | 0.07 | 0.60 | -0.72 | 0.83 | -0.06 | -0.80 | -0.40 |
| F2 | -1.03 | 0.96 | 0.42 | -0.34 | 1.03 | -0.56 | 0.62 | 0.11 | 0.69 | -0.52 | 0.81 | -0.07 | -0.84 | -0.41 |

### T6a. By calendar year, entry a:close: 4-slot portfolio return %

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | -8.56 | 15.94 | -19.71 | 2.24 | 13.70 | -15.84 | -8.22 | -7.00 | 59.71 | -18.42 | 95.94 | 20.01 | -5.24 | -1.59 |
| A2 | 2.09 | 23.24 | -6.66 | 3.66 | 15.77 | -11.27 | -5.85 | 0.12 | 59.70 | -15.19 | 78.85 | -1.12 | -2.35 | -0.18 |
| A3 | -16.29 | 18.07 | -31.78 | -31.44 | -2.64 | -42.96 | -4.89 | 21.53 | 49.70 | -25.31 | 74.31 | -7.47 | -14.10 | -7.75 |
| A4 | -34.06 | -22.51 | 0.51 | -33.00 | -28.10 | -55.76 | -18.21 | -33.48 | -35.40 | -54.47 | -10.10 | -55.18 | -33.39 | -39.12 |
| A5 | -10.94 | 23.25 | -9.54 | 7.18 | 9.32 | -26.41 | -3.45 | -19.24 | 37.93 | -16.90 | 92.26 | 21.01 | -17.51 | 6.36 |
| A6 | 0.84 | 31.18 | -10.57 | 11.38 | 8.17 | -28.61 | 11.16 | 13.54 | 76.23 | -10.31 | 65.25 | 33.88 | -9.51 | -18.15 |
| B1 | -21.61 | 11.35 | -14.45 | -8.32 | 44.82 | -22.62 | 3.51 | -21.91 | 2.12 | -13.50 | 48.52 | 18.07 | -25.82 | 9.53 |
| B2 | -22.79 | 0.13 | -2.62 | -43.43 | 12.07 | -40.81 | 10.71 | 22.53 | 35.86 | -26.17 | 10.82 | 26.03 | -33.55 | 16.68 |
| B3 | -28.56 | 10.94 | -25.03 | -17.94 | 33.00 | -28.45 | -3.79 | -30.86 | -1.83 | -6.26 | 17.45 | 15.87 | -17.33 | 9.45 |
| B4 | -31.72 | -1.81 | -20.31 | -17.43 | 30.79 | -37.55 | 13.17 | 2.50 | 5.24 | -1.02 | 6.63 | 17.26 | -28.95 | -0.53 |
| BASE | -17.92 | 20.16 | -34.45 | 2.91 | 36.78 | 0.17 | -13.46 | -11.62 | 61.14 | 4.08 | 16.52 | 1.73 | -22.38 | 13.40 |
| C20 | -34.30 | 11.40 | 32.71 | -17.91 | 48.30 | -31.50 | -26.80 | -7.73 | 25.48 | -34.92 | -1.85 | 10.37 | -59.71 | -38.83 |
| C250 | -22.01 | -6.97 | 8.19 | -6.78 | 83.12 | -13.68 | -7.00 | 0.14 | 73.17 | -13.94 | 14.55 | -23.78 | 15.68 | -44.49 |
| C55 | -28.69 | -6.52 | 19.50 | -18.12 | 78.64 | -7.75 | -22.21 | -8.28 | 14.09 | -30.36 | 0.73 | 42.98 | -31.81 | -38.19 |
| D126 | 6.14 | 48.39 | 11.15 | -41.29 | 38.02 | -48.50 | -18.25 | -10.42 | 26.41 | -40.43 | 14.82 | 33.05 | -1.33 | -0.72 |
| D63 | -0.32 | 44.15 | 43.95 | -31.51 | 58.29 | -41.08 | -4.22 | -7.96 | 12.74 | -3.30 | 2.51 | -21.42 | -2.85 | 4.38 |
| E1 | -26.69 | 10.26 | -11.78 | -10.05 | -16.71 | -50.42 | -25.39 | -31.31 | 42.90 | -37.88 | 37.30 | -2.18 | -15.60 | -3.36 |
| E2 | -19.58 | 1.89 | -23.56 | -45.59 | 17.23 | -58.76 | -4.98 | -17.60 | 35.84 | -23.80 | 20.54 | -38.54 | -14.53 | -21.25 |
| F1 | -14.43 | 38.28 | 37.20 | -15.04 | 31.07 | -36.49 | -19.46 | -35.04 | 38.28 | -53.56 | 0.70 | -1.04 | -29.49 | -56.38 |
| F2 | -8.31 | 46.41 | 26.69 | -20.87 | 50.79 | -29.19 | -2.92 | -11.60 | 39.14 | -35.60 | 23.11 | 6.50 | -17.75 | -34.06 |

### T7a. By calendar year, entry a:close: number of trades

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | 627 | 1277 | 1016 | 1085 | 1714 | 1289 | 935 | 1297 | 3206 | 2083 | 2800 | 5419 | 2656 | 3143 |
| A2 | 343 | 677 | 587 | 540 | 952 | 707 | 476 | 654 | 1807 | 1133 | 1408 | 3066 | 1437 | 1681 |
| A3 | 715 | 1440 | 1109 | 1164 | 2085 | 1465 | 1066 | 1496 | 3745 | 2265 | 3315 | 6176 | 2933 | 3624 |
| A4 | 1527 | 3322 | 2564 | 2941 | 5054 | 3110 | 2489 | 3857 | 9327 | 5791 | 9845 | 15573 | 7069 | 9064 |
| A5 | 679 | 1359 | 1100 | 1177 | 1868 | 1400 | 986 | 1399 | 3392 | 2285 | 2968 | 5840 | 2826 | 3357 |
| A6 | 616 | 1245 | 1007 | 1064 | 1677 | 1263 | 918 | 1266 | 3135 | 2017 | 2760 | 5333 | 2606 | 3089 |
| B1 | 207 | 238 | 254 | 299 | 443 | 388 | 414 | 349 | 533 | 442 | 650 | 928 | 751 | 851 |
| B2 | 216 | 318 | 299 | 351 | 477 | 381 | 378 | 468 | 764 | 672 | 850 | 1322 | 829 | 1099 |
| B3 | 206 | 232 | 248 | 296 | 435 | 382 | 409 | 343 | 530 | 433 | 643 | 912 | 732 | 839 |
| B4 | 212 | 309 | 292 | 338 | 469 | 371 | 370 | 458 | 750 | 656 | 841 | 1299 | 804 | 1080 |
| BASE | 2249 | 4473 | 3643 | 4019 | 6752 | 4507 | 3689 | 5144 | 11186 | 7364 | 11986 | 18231 | 9383 | 11653 |
| C20 | 225 | 411 | 432 | 432 | 876 | 605 | 501 | 804 | 1409 | 1097 | 1898 | 2307 | 1424 | 1781 |
| C250 | 76 | 260 | 196 | 196 | 476 | 233 | 138 | 325 | 938 | 419 | 1078 | 1412 | 366 | 715 |
| C55 | 155 | 336 | 310 | 343 | 687 | 383 | 335 | 613 | 1142 | 761 | 1541 | 1787 | 951 | 1278 |
| D126 | 196 | 196 | 196 | 196 | 196 | 196 | 196 | 200 | 188 | 192 | 192 | 192 | 168 | 164 |
| D63 | 196 | 196 | 196 | 196 | 196 | 196 | 196 | 200 | 188 | 192 | 192 | 192 | 168 | 164 |
| E1 | 249 | 554 | 414 | 470 | 825 | 544 | 438 | 662 | 1459 | 890 | 1551 | 2371 | 1115 | 1408 |
| E2 | 446 | 464 | 462 | 455 | 487 | 461 | 478 | 503 | 537 | 475 | 493 | 539 | 432 | 428 |
| F1 | 101 | 205 | 225 | 207 | 453 | 342 | 264 | 424 | 903 | 711 | 1135 | 1610 | 1136 | 1354 |
| F2 | 58 | 188 | 168 | 171 | 404 | 233 | 155 | 326 | 873 | 532 | 1014 | 1471 | 733 | 1002 |

### T5b. By calendar year, entry b:next open, no regime filter, cost 0.25%: avg net % per trade

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | -0.09 | 0.63 | -0.14 | -0.02 | 0.17 | -0.65 | -0.17 | 0.08 | 0.41 | -0.37 | 0.55 | 0.10 | -0.12 | 0.06 |
| A2 | 0.32 | 0.97 | 0.08 | -0.17 | 0.47 | -0.45 | -0.18 | 0.25 | 0.73 | -0.16 | 0.42 | 0.32 | 0.13 | 0.20 |
| A3 | -0.24 | 0.42 | -0.37 | -0.41 | 0.09 | -0.71 | -0.28 | 0.04 | 0.25 | -0.56 | 0.35 | -0.03 | -0.28 | -0.02 |
| A4 | -0.49 | -0.07 | -0.65 | -0.46 | -0.24 | -0.99 | -0.34 | -0.27 | -0.27 | -0.71 | 0.06 | -0.41 | -0.61 | -0.22 |
| A5 | -0.22 | 0.49 | -0.27 | -0.57 | 0.03 | -0.75 | -0.19 | 0.03 | 0.23 | -0.41 | 0.43 | 0.02 | -0.26 | -0.02 |
| A6 | -0.41 | 0.80 | 0.24 | 0.05 | 0.46 | -0.92 | -0.08 | 0.44 | 0.67 | -0.27 | 1.06 | 0.43 | -0.00 | -0.05 |
| B1 | -0.54 | 0.22 | -0.34 | -0.67 | 0.31 | -0.51 | -0.67 | -0.20 | -0.14 | -0.88 | 0.72 | -0.17 | -0.42 | -0.58 |
| B2 | -0.81 | -0.50 | -0.21 | -1.01 | 0.12 | -1.05 | -0.82 | -0.33 | -0.45 | -0.87 | 0.47 | -0.01 | -0.74 | -0.54 |
| B3 | -0.62 | 0.16 | -0.60 | -0.73 | 0.47 | -0.66 | -0.60 | -0.04 | -0.15 | -0.81 | 0.72 | 0.02 | -0.25 | -0.56 |
| B4 | -0.99 | -0.43 | -0.33 | -0.79 | 0.36 | -1.24 | -0.67 | -0.32 | -0.54 | -0.69 | 0.60 | 0.03 | -0.82 | -0.50 |
| BASE | -0.81 | 0.42 | -0.40 | -0.38 | 0.30 | -1.08 | -0.42 | 0.28 | 0.29 | -0.64 | 0.67 | -0.00 | -0.70 | -0.42 |
| C20 | -1.75 | 0.15 | -0.52 | -0.84 | -0.01 | -0.87 | -0.33 | -0.27 | -0.13 | -0.81 | 0.14 | -0.73 | -0.69 | -0.57 |
| C250 | -1.82 | -0.43 | -0.40 | -0.95 | 0.04 | -0.86 | -0.30 | -0.30 | -0.19 | -0.82 | 0.16 | -0.65 | 0.05 | -0.83 |
| C55 | -1.78 | -0.03 | -0.49 | -0.69 | -0.05 | -1.04 | -0.45 | -0.54 | -0.27 | -0.83 | 0.05 | -0.65 | -0.52 | -0.54 |
| D126 | 0.20 | 0.58 | 0.14 | -1.20 | 0.48 | -1.14 | -0.40 | -0.31 | 0.62 | -0.78 | -0.14 | 0.22 | -0.05 | 0.31 |
| D63 | 0.04 | 0.65 | 0.64 | -0.85 | 0.88 | -0.90 | -0.08 | -0.13 | 0.52 | 0.14 | -0.16 | -0.60 | -0.16 | 0.54 |
| E1 | -0.95 | 0.59 | -0.17 | -0.41 | -0.02 | -1.34 | -0.36 | 0.32 | 0.27 | -0.59 | 0.59 | -0.23 | -0.59 | -0.21 |
| E2 | -0.44 | 0.26 | 0.13 | -0.63 | -0.11 | -1.09 | -0.39 | 0.03 | 0.40 | -0.51 | 0.55 | -0.07 | -0.53 | -0.11 |
| F1 | -1.15 | 0.29 | -0.25 | -0.65 | 0.45 | -0.87 | -0.06 | -0.62 | -0.09 | -1.10 | 0.26 | -0.64 | -0.72 | -0.60 |
| F2 | -1.31 | 0.22 | -0.00 | -0.55 | 0.47 | -0.81 | 0.32 | -0.57 | -0.01 | -0.92 | 0.23 | -0.67 | -0.83 | -0.69 |

### T6b. By calendar year, entry b:next open: 4-slot portfolio return %

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | -11.41 | 10.38 | -31.24 | 0.59 | 2.27 | -17.47 | -14.90 | -12.17 | 34.87 | -27.20 | 59.90 | 2.85 | -4.78 | -2.52 |
| A2 | 0.36 | 24.76 | -15.43 | 6.21 | 1.78 | -17.76 | -9.17 | -4.32 | 31.62 | -16.76 | 52.15 | -13.85 | -8.07 | 0.41 |
| A3 | -20.52 | 6.22 | -38.94 | -34.76 | -12.88 | -41.13 | -14.36 | 11.51 | 40.57 | -29.50 | 39.51 | -13.58 | -18.21 | -4.44 |
| A4 | -38.82 | -30.59 | -17.07 | -37.86 | -32.65 | -53.39 | -18.46 | -38.62 | -18.17 | -45.52 | -16.88 | -45.06 | -33.46 | -14.57 |
| A5 | -10.73 | 11.95 | -20.15 | -9.40 | -5.11 | -34.87 | -15.37 | -6.15 | 25.10 | -24.87 | 50.60 | 9.30 | -22.67 | 3.79 |
| A6 | -0.11 | 25.59 | -13.18 | 7.88 | 2.25 | -29.42 | 6.09 | 11.07 | 63.53 | -6.60 | 47.47 | 15.88 | -19.15 | -16.45 |
| B1 | -17.19 | 1.46 | -21.48 | -23.31 | 6.91 | -21.85 | -0.89 | -31.60 | -13.63 | -25.53 | 28.92 | -19.14 | -36.44 | 1.16 |
| B2 | -19.72 | -16.37 | -21.34 | -54.11 | 1.47 | -35.27 | -3.67 | 3.70 | -15.41 | -22.87 | -16.20 | 2.52 | -39.07 | -18.12 |
| B3 | -28.37 | -6.80 | -30.97 | -24.42 | 11.94 | -26.50 | 5.11 | -39.63 | -20.78 | -6.11 | 8.84 | -5.05 | -23.63 | 2.71 |
| B4 | -28.59 | -16.53 | -25.70 | -37.74 | 17.74 | -36.55 | 0.05 | -19.03 | -16.34 | -10.62 | -0.97 | 6.83 | -40.15 | 8.49 |
| BASE | -19.26 | 10.35 | -37.37 | 1.19 | 23.85 | -8.67 | -16.46 | -21.13 | 39.99 | -6.40 | 10.47 | -13.35 | -16.33 | 2.35 |
| C20 | -39.43 | 3.19 | 4.65 | -34.57 | 24.76 | -40.60 | -26.83 | -26.08 | -18.54 | -39.39 | -25.15 | -24.94 | -6.35 | -47.76 |
| C250 | -25.29 | -8.97 | -9.94 | -20.44 | 25.09 | -17.36 | -11.69 | -17.58 | -14.26 | -24.10 | 7.30 | -35.42 | 19.49 | -41.44 |
| C55 | -31.78 | -16.57 | 3.55 | -26.23 | 25.40 | -21.38 | -24.47 | -25.73 | -29.82 | -41.57 | -2.43 | -18.23 | -17.50 | -34.64 |
| D126 | 6.44 | 29.27 | -1.88 | -51.95 | 20.46 | -49.04 | -26.66 | -23.80 | 26.11 | -35.33 | -10.74 | 3.16 | -5.06 | 3.06 |
| D63 | 1.38 | 25.78 | 29.09 | -43.11 | 44.42 | -43.07 | -11.95 | -12.66 | 15.86 | -2.54 | -12.36 | -27.97 | -9.46 | 10.95 |
| E1 | -33.10 | 7.04 | -21.77 | -7.98 | -24.51 | -50.55 | -29.14 | -32.57 | 33.83 | -34.59 | 20.77 | -9.23 | -6.90 | 7.33 |
| E2 | -29.32 | -9.62 | -23.09 | -52.00 | -6.27 | -57.69 | -17.77 | -18.87 | 23.64 | -26.45 | -17.30 | -31.65 | -26.67 | -13.30 |
| F1 | -19.82 | 9.08 | 20.53 | -23.07 | 3.33 | -42.12 | -27.86 | -46.49 | -4.26 | -61.21 | -11.66 | -8.34 | -8.38 | -56.29 |
| F2 | -11.87 | 16.33 | 14.22 | -26.15 | 22.11 | -30.98 | -3.72 | -27.95 | -1.64 | -46.18 | 6.94 | -3.16 | -5.91 | -37.12 |

### T7b. By calendar year, entry b:next open: number of trades

| s | 2013 | 2014 | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A1 | 627 | 1277 | 1016 | 1085 | 1714 | 1289 | 935 | 1296 | 3206 | 2083 | 2800 | 5419 | 2656 | 3143 |
| A2 | 343 | 677 | 587 | 540 | 952 | 707 | 476 | 654 | 1807 | 1133 | 1408 | 3066 | 1437 | 1681 |
| A3 | 715 | 1440 | 1109 | 1164 | 2085 | 1465 | 1066 | 1496 | 3745 | 2264 | 3315 | 6172 | 2933 | 3624 |
| A4 | 1527 | 3322 | 2564 | 2941 | 5054 | 3110 | 2489 | 3857 | 9327 | 5790 | 9845 | 15573 | 7069 | 9063 |
| A5 | 686 | 1354 | 1105 | 1176 | 1874 | 1397 | 978 | 1384 | 3394 | 2265 | 2980 | 5807 | 2811 | 3333 |
| A6 | 616 | 1245 | 1007 | 1064 | 1677 | 1263 | 918 | 1265 | 3135 | 2017 | 2760 | 5333 | 2606 | 3089 |
| B1 | 208 | 238 | 258 | 300 | 443 | 388 | 421 | 350 | 534 | 442 | 650 | 929 | 750 | 852 |
| B2 | 218 | 318 | 300 | 353 | 481 | 383 | 381 | 470 | 771 | 674 | 855 | 1330 | 833 | 1109 |
| B3 | 206 | 233 | 250 | 296 | 437 | 381 | 408 | 343 | 532 | 432 | 643 | 916 | 734 | 839 |
| B4 | 212 | 309 | 293 | 341 | 469 | 372 | 369 | 459 | 749 | 656 | 842 | 1305 | 805 | 1079 |
| BASE | 2248 | 4473 | 3642 | 4019 | 6750 | 4506 | 3689 | 5144 | 11186 | 7362 | 11985 | 18229 | 9382 | 11653 |
| C20 | 226 | 411 | 431 | 432 | 875 | 605 | 503 | 804 | 1414 | 1099 | 1903 | 2305 | 1424 | 1781 |
| C250 | 76 | 260 | 195 | 196 | 476 | 233 | 138 | 325 | 943 | 419 | 1080 | 1409 | 367 | 715 |
| C55 | 156 | 336 | 309 | 343 | 686 | 384 | 336 | 615 | 1148 | 762 | 1544 | 1784 | 952 | 1278 |
| D126 | 196 | 196 | 196 | 196 | 196 | 196 | 196 | 200 | 188 | 192 | 192 | 192 | 168 | 164 |
| D63 | 196 | 196 | 196 | 196 | 196 | 196 | 196 | 200 | 188 | 192 | 192 | 192 | 168 | 164 |
| E1 | 249 | 554 | 413 | 470 | 825 | 544 | 438 | 662 | 1459 | 890 | 1551 | 2371 | 1115 | 1408 |
| E2 | 446 | 464 | 461 | 455 | 487 | 461 | 478 | 502 | 537 | 475 | 493 | 539 | 432 | 428 |
| F1 | 101 | 205 | 224 | 207 | 453 | 342 | 264 | 423 | 903 | 711 | 1135 | 1609 | 1136 | 1354 |
| F2 | 58 | 188 | 167 | 171 | 404 | 233 | 155 | 325 | 873 | 532 | 1014 | 1470 | 733 | 1002 |

### T8. Clustering diagnostics (cost 0.25%, no regime filter). day_wtd = every signal day weighted equally; top4 = only the 4 best-ranked signals per day (what a 4-slot account can actually take)

| strat | period | n | signal_days | trade_wtd_avg | day_wtd_avg | day_wtd_t | top4_avg | top4_n | share_trades_top10pct_days |
|---|---|---|---|---|---|---|---|---|---|
| A1_a | IS | 12446 | 1804 | 0.21 | -0.03 | -0.41 | 0.14 | 6794 | 0.41 |
| A1_a | OOS | 16101 | 1062 | 0.12 | -0.06 | -0.68 | 0.14 | 4221 | 0.38 |
| A1_b | IS | 12445 | 1804 | 0.10 | -0.15 | -1.97 | 0.03 | 6794 | 0.41 |
| A1_b | OOS | 16101 | 1062 | 0.07 | -0.18 | -2.04 | 0.02 | 4221 | 0.38 |
| A2_a | IS | 6743 | 1486 | 0.37 | 0.11 | 1.14 | 0.17 | 5073 | 0.42 |
| A2_a | OOS | 8725 | 988 | 0.30 | -0.04 | -0.34 | 0.15 | 3759 | 0.41 |
| A2_b | IS | 6743 | 1486 | 0.33 | 0.01 | 0.12 | 0.08 | 5073 | 0.42 |
| A2_b | OOS | 8725 | 988 | 0.22 | -0.17 | -1.60 | 0.04 | 3759 | 0.41 |
| A3_a | IS | 14285 | 1907 | 0.11 | 0.08 | 1.07 | 0.17 | 7289 | 0.36 |
| A3_a | OOS | 18313 | 1090 | 0.07 | -0.05 | -0.63 | 0.06 | 4348 | 0.32 |
| A3_b | IS | 14285 | 1907 | -0.04 | -0.07 | -0.95 | 0.06 | 7289 | 0.36 |
| A3_b | OOS | 18308 | 1090 | -0.06 | -0.23 | -2.93 | -0.04 | 4348 | 0.32 |
| A4_a | IS | 34191 | 2120 | -0.18 | -0.19 | -3.46 | -0.26 | 8276 | 0.33 |
| A4_a | OOS | 47342 | 1126 | -0.15 | -0.14 | -2.23 | -0.52 | 4498 | 0.27 |
| A4_b | IS | 34191 | 2120 | -0.37 | -0.38 | -7.12 | -0.36 | 8276 | 0.33 |
| A4_b | OOS | 47340 | 1126 | -0.34 | -0.35 | -5.90 | -0.41 | 4498 | 0.27 |
| A5_a | IS | 13360 | 1824 | 0.04 | -0.11 | -1.57 | 0.08 | 6794 | 0.42 |
| A5_a | OOS | 17276 | 1067 | 0.01 | -0.18 | -2.07 | 0.02 | 4221 | 0.38 |
| A5_b | IS | 13348 | 1825 | -0.06 | -0.23 | -3.35 | -0.07 | 6794 | 0.42 |
| A5_b | OOS | 17196 | 1066 | -0.02 | -0.26 | -3.17 | -0.09 | 4221 | 0.38 |
| A6_a | IS | 12191 | 1800 | 0.36 | 0.19 | 1.82 | 0.24 | 6794 | 0.40 |
| A6_a | OOS | 15805 | 1064 | 0.34 | 0.10 | 0.78 | 0.18 | 4221 | 0.37 |
| A6_b | IS | 12190 | 1800 | 0.27 | 0.08 | 0.76 | 0.13 | 6794 | 0.40 |
| A6_b | OOS | 15805 | 1064 | 0.28 | -0.02 | -0.16 | 0.06 | 4221 | 0.37 |
| B1_a | IS | 3125 | 1371 | 0.03 | -0.08 | -0.61 | 0.07 | 3633 | 0.28 |
| B1_a | OOS | 3622 | 935 | 0.04 | -0.10 | -0.61 | 0.01 | 3009 | 0.30 |
| B1_b | IS | 3140 | 1372 | -0.27 | -0.32 | -2.61 | -0.18 | 3632 | 0.28 |
| B1_b | OOS | 3623 | 936 | -0.24 | -0.33 | -2.10 | -0.28 | 3009 | 0.30 |
| B2_a | IS | 3652 | 1466 | -0.17 | -0.30 | -2.36 | -0.18 | 4038 | 0.30 |
| B2_a | OOS | 4772 | 992 | 0.05 | -0.05 | -0.32 | -0.14 | 3429 | 0.30 |
| B2_b | IS | 3675 | 1466 | -0.52 | -0.56 | -4.43 | -0.47 | 4037 | 0.30 |
| B2_b | OOS | 4801 | 993 | -0.29 | -0.34 | -2.27 | -0.50 | 3429 | 0.30 |
| B3_a | IS | 3081 | 1365 | 0.04 | -0.06 | -0.40 | 0.09 | 3633 | 0.28 |
| B3_a | OOS | 3559 | 930 | 0.14 | 0.04 | 0.19 | 0.10 | 3009 | 0.30 |
| B3_b | IS | 3086 | 1367 | -0.27 | -0.32 | -2.32 | -0.18 | 3632 | 0.28 |
| B3_b | OOS | 3564 | 932 | -0.15 | -0.23 | -1.27 | -0.20 | 3009 | 0.30 |
| B4_a | IS | 3569 | 1454 | -0.17 | -0.19 | -1.31 | -0.13 | 4038 | 0.30 |
| B4_a | OOS | 4680 | 987 | 0.09 | 0.04 | 0.23 | -0.06 | 3429 | 0.30 |
| B4_b | IS | 3573 | 1456 | -0.50 | -0.50 | -3.44 | -0.43 | 4037 | 0.30 |
| B4_b | OOS | 4687 | 986 | -0.23 | -0.27 | -1.54 | -0.41 | 3429 | 0.30 |
| BASE_a | IS | 45662 | 2197 | 0.17 | 0.08 | 1.06 | 0.09 | 8812 | 0.24 |
| BASE_a | OOS | 58617 | 1130 | 0.07 | 0.09 | 0.96 | 0.05 | 4520 | 0.18 |
| BASE_b | IS | 45657 | 2197 | -0.06 | -0.11 | -1.46 | -0.07 | 8812 | 0.24 |
| BASE_b | OOS | 58611 | 1130 | -0.14 | -0.09 | -0.97 | -0.11 | 4520 | 0.18 |
| C20_a | IS | 5695 | 1653 | 0.17 | 0.08 | 0.59 | 0.03 | 4777 | 0.30 |
| C20_a | OOS | 8507 | 1072 | -0.10 | -0.27 | -2.02 | -0.39 | 3968 | 0.24 |
| C20_b | IS | 5701 | 1652 | -0.35 | -0.40 | -3.10 | -0.44 | 4775 | 0.30 |
| C20_b | OOS | 8512 | 1072 | -0.51 | -0.58 | -4.36 | -0.63 | 3968 | 0.24 |
| C250_a | IS | 2838 | 1184 | 0.21 | 0.10 | 0.57 | 0.16 | 2871 | 0.29 |
| C250_a | OOS | 3990 | 924 | 0.10 | -0.01 | -0.03 | -0.05 | 2984 | 0.27 |
| C250_b | IS | 2842 | 1183 | -0.35 | -0.44 | -2.60 | -0.38 | 2869 | 0.29 |
| C250_b | OOS | 3990 | 924 | -0.42 | -0.43 | -2.38 | -0.44 | 2984 | 0.27 |
| C55_a | IS | 4304 | 1459 | 0.09 | -0.07 | -0.50 | -0.01 | 3969 | 0.29 |
| C55_a | OOS | 6318 | 1032 | -0.00 | -0.18 | -1.21 | -0.16 | 3640 | 0.25 |
| C55_b | IS | 4313 | 1459 | -0.44 | -0.53 | -3.82 | -0.49 | 3967 | 0.29 |
| C55_b | OOS | 6320 | 1031 | -0.46 | -0.54 | -3.69 | -0.51 | 3640 | 0.25 |
| D126_a | IS | 1760 | 440 | 0.08 | 0.08 | 0.31 | 0.08 | 1760 | 0.10 |
| D126_a | OOS | 908 | 227 | 0.10 | 0.10 | 0.27 | 0.10 | 908 | 0.10 |
| D126_b | IS | 1760 | 440 | -0.12 | -0.12 | -0.43 | -0.12 | 1760 | 0.10 |
| D126_b | OOS | 908 | 227 | -0.10 | -0.10 | -0.28 | -0.10 | 908 | 0.10 |
| D63_a | IS | 1760 | 440 | 0.23 | 0.23 | 0.83 | 0.23 | 1760 | 0.10 |
| D63_a | OOS | 908 | 227 | 0.05 | 0.05 | 0.14 | 0.05 | 908 | 0.10 |
| D63_b | IS | 1760 | 440 | 0.08 | 0.08 | 0.30 | 0.08 | 1760 | 0.10 |
| D63_b | OOS | 908 | 227 | -0.06 | -0.06 | -0.17 | -0.06 | 908 | 0.10 |
| E1_a | IS | 5615 | 1981 | 0.11 | 0.10 | 0.85 | -0.23 | 7381 | 0.25 |
| E1_a | OOS | 7335 | 1122 | 0.07 | 0.18 | 1.37 | -0.12 | 4502 | 0.20 |
| E1_b | IS | 5614 | 1981 | -0.09 | -0.07 | -0.55 | -0.36 | 7379 | 0.25 |
| E1_b | OOS | 7335 | 1122 | -0.15 | -0.01 | -0.11 | -0.25 | 4502 | 0.20 |
| E2_a | IS | 4293 | 2084 | -0.03 | -0.03 | -0.27 | -0.12 | 8812 | 0.17 |
| E2_a | OOS | 2367 | 1077 | 0.04 | -0.03 | -0.19 | -0.10 | 4524 | 0.17 |
| E2_b | IS | 4291 | 2084 | -0.19 | -0.20 | -1.60 | -0.26 | 8809 | 0.17 |
| E2_b | OOS | 2367 | 1077 | -0.12 | -0.19 | -1.18 | -0.23 | 4523 | 0.17 |
| F1_a | IS | 3124 | 1359 | 0.34 | 0.26 | 1.54 | 0.19 | 3027 | 0.29 |
| F1_a | OOS | 5946 | 1066 | -0.18 | -0.35 | -2.35 | -0.38 | 3732 | 0.22 |
| F1_b | IS | 3122 | 1358 | -0.22 | -0.25 | -1.54 | -0.33 | 3025 | 0.29 |
| F1_b | OOS | 5945 | 1066 | -0.53 | -0.60 | -4.11 | -0.59 | 3732 | 0.22 |
| F2_a | IS | 2576 | 1161 | 0.45 | 0.36 | 2.02 | 0.29 | 2509 | 0.30 |
| F2_a | OOS | 4752 | 1015 | -0.12 | -0.31 | -1.95 | -0.28 | 3303 | 0.24 |
| F2_b | IS | 2574 | 1160 | -0.11 | -0.13 | -0.72 | -0.22 | 2507 | 0.30 |
| F2_b | OOS | 4751 | 1015 | -0.54 | -0.59 | -3.74 | -0.56 | 3303 | 0.24 |

### T9. Honest selection: per family the variant+regime with the best IN-SAMPLE clustered t-stat (entry a, cost 0.25%), then reported out-of-sample unchanged

| family | chosen | regime | entry | cost | IS_n | IS_avg | IS_t | IS_CAGR | OOS_n | OOS_win | OOS_avg | OOS_CI | OOS_t | OOS_PF | OOS_CAGR | OOS_MDD |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A | A2 | n50>200dma | a | 0.25 | 5878 | 0.47 | 2.95 | 4.01 | 6521 | 63.38 | 0.38 | 0.09..0.68 | 2.55 | 1.26 | 5.63 | -35.11 |
| A | A2 | n50>200dma | a | 0.40 | 5878 | 0.32 | 2.00 | -2.37 | 6521 | 61.55 | 0.23 | -0.06..0.53 | 1.55 | 1.15 | -1.20 | -38.72 |
| A | A2 | n50>200dma | b | 0.25 | 5878 | 0.39 | 2.40 | -1.97 | 6521 | 60.79 | 0.26 | -0.00..0.54 | 1.88 | 1.18 | -2.16 | -34.67 |
| A | A2 | n50>200dma | b | 0.40 | 5878 | 0.24 | 1.49 | -7.99 | 6521 | 58.96 | 0.11 | -0.15..0.39 | 0.82 | 1.07 | -8.50 | -45.15 |
| F | F2 | n50>50dma | a | 0.25 | 2177 | 0.55 | 2.62 | 15.70 | 3444 | 44.80 | -0.10 | -0.50..0.30 | -0.50 | 0.96 | 1.10 | -44.70 |
| F | F2 | n50>50dma | a | 0.40 | 2177 | 0.40 | 1.90 | 11.43 | 3444 | 43.73 | -0.25 | -0.65..0.15 | -1.23 | 0.91 | -3.28 | -50.88 |
| F | F2 | n50>50dma | b | 0.25 | 2177 | -0.03 | -0.14 | 1.57 | 3443 | 42.46 | -0.60 | -0.94..-0.25 | -3.41 | 0.79 | -6.87 | -48.13 |
| F | F2 | n50>50dma | b | 0.40 | 2177 | -0.18 | -0.88 | -2.20 | 3443 | 41.53 | -0.75 | -1.09..-0.40 | -4.27 | 0.74 | -10.91 | -53.84 |
| D | D63 | n50>50dma | a | 0.25 | 1188 | 0.39 | 1.57 | 8.81 | 532 | 43.42 | -0.34 | -1.25..0.49 | -0.78 | 0.90 | -12.04 | -59.88 |
| D | D63 | n50>50dma | a | 0.40 | 1188 | 0.24 | 0.98 | 3.56 | 532 | 42.48 | -0.49 | -1.40..0.34 | -1.11 | 0.85 | -15.61 | -66.90 |
| D | D63 | n50>50dma | b | 0.25 | 1188 | 0.27 | 1.11 | 4.56 | 532 | 43.42 | -0.39 | -1.19..0.38 | -0.96 | 0.88 | -12.98 | -61.07 |
| D | D63 | n50>50dma | b | 0.40 | 1188 | 0.12 | 0.49 | -0.49 | 532 | 42.67 | -0.54 | -1.34..0.23 | -1.33 | 0.84 | -16.51 | -67.82 |
| C | C20 | n50>50dma | a | 0.25 | 4899 | 0.22 | 1.16 | 1.70 | 6455 | 45.17 | -0.07 | -0.47..0.32 | -0.37 | 0.97 | -4.18 | -42.51 |
| C | C20 | n50>50dma | a | 0.40 | 4899 | 0.07 | 0.36 | -2.67 | 6455 | 44.14 | -0.22 | -0.62..0.17 | -1.13 | 0.92 | -8.11 | -46.50 |
| C | C20 | n50>50dma | b | 0.25 | 4904 | -0.31 | -1.73 | -9.09 | 6462 | 42.22 | -0.53 | -0.89..-0.17 | -2.93 | 0.81 | -13.04 | -58.15 |
| C | C20 | n50>50dma | b | 0.40 | 4904 | -0.46 | -2.58 | -13.05 | 6462 | 40.99 | -0.68 | -1.04..-0.32 | -3.75 | 0.76 | -16.64 | -65.35 |
| E | E1 | none | a | 0.25 | 5615 | 0.11 | 0.65 | -16.66 | 7335 | 48.11 | 0.07 | -0.31..0.41 | 0.37 | 1.03 | 1.03 | -49.98 |
| E | E1 | none | a | 0.40 | 5615 | -0.04 | -0.25 | -22.35 | 7335 | 46.88 | -0.08 | -0.46..0.26 | -0.42 | 0.97 | -5.79 | -54.52 |
| E | E1 | none | b | 0.25 | 5614 | -0.09 | -0.53 | -20.77 | 7335 | 46.07 | -0.15 | -0.50..0.17 | -0.87 | 0.94 | -6.12 | -51.33 |
| E | E1 | none | b | 0.40 | 5614 | -0.24 | -1.48 | -26.18 | 7335 | 44.89 | -0.30 | -0.65..0.02 | -1.73 | 0.88 | -12.47 | -60.67 |
| B | B3 | n50>50dma | a | 0.25 | 2337 | 0.11 | 0.48 | -0.11 | 2200 | 46.91 | 0.13 | -0.53..0.84 | 0.37 | 1.05 | 9.20 | -33.05 |
| B | B3 | n50>50dma | a | 0.40 | 2337 | -0.04 | -0.19 | -4.13 | 2200 | 46.14 | -0.02 | -0.68..0.69 | -0.06 | 0.99 | 5.15 | -37.10 |
| B | B3 | n50>50dma | b | 0.25 | 2340 | -0.18 | -0.81 | -8.74 | 2204 | 45.69 | -0.14 | -0.79..0.52 | -0.43 | 0.95 | -0.81 | -43.00 |
| B | B3 | n50>50dma | b | 0.40 | 2340 | -0.33 | -1.49 | -12.43 | 2204 | 44.65 | -0.29 | -0.94..0.37 | -0.90 | 0.89 | -4.53 | -49.24 |
