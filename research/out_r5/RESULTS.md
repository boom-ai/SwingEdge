# Round 5 results (12 Nov 2026) — pre-registered in PREREG.md, run by scripts/50_round5.py, app numbers by 51_app_numbers.py

OOS = 2022-01-03 .. 2025-01-29 (signals whose feature/forward window touches the 2025-05/06 hole excluded; ~3.1 years). IS = 2013–2021. Primary cost 0.50% round trip. Null A = random rated stock above SMA50 (not upper-locked), same exits/slots/costs, 150 seeds × 4 offsets = 600 runs. Benchmarks OOS (median over 20 offsets): Nifty 10.3% CAGR (DD −15%), equal-weight liquid basket 19.8% (DD −21%). IS: Nifty 12.8% (−38%), EW 12.5% (−53%).
Checks: bar-count SMA method reproduces stored SMA50 (max rel diff 6e-8). P1 on the round-4 window: n 2221 / win 31.6% / avg +1.64% / median −3.16% at 0.25% vs REPORT4 2144 / 31.8 / +1.75 / −3.15 (small difference from the one-trade-per-stock bookkeeping; medians match). Per-trade "t" below is iid and overstates significance (trades overlap in time).

## Decisions
1. **AVOID = score < 55 AND close < SMA200** (pre-registered rule: weakest-link OOS t −1.16 vs −0.25 for score<55; TW lag −0.97 vs −0.15). Union is indistinguishable from score<55. Even the chosen rule is NOT significant vs the turnover-weighted benchmark OOS (t −1.2); full-period t −2.3.
2. **Plan P2** (next open within 0.5 ATR, fixed 5-ATR stop with gap-through exit, 60-session hold). Score 88.8 vs P3 70.0, P1 54.4. **Real-money bar failed.** OOS is contaminated (P2 = round-4 V8, picked after seeing 2022–26): OOS null percentiles 81 (4 slots) / 74 (8 slots) are below the ~94 that the best of 11 random variants reaches by chance. Per trade OOS, P2 on BUY signals earned +3.62% vs +3.77% for random above-SMA50 stocks with the same exits: **no OOS stock-picking edge.** Only the IS result (+5.08 vs +2.87) is clean evidence, and IS drawdowns were −56% to −65%.
3. **Selection: keep highest score.** ATR-low did not beat it in all four cells (it lowered drawdowns: IS 8-slot −44 vs −56, OOS −24/−28 vs −31/−34, at slightly lower CAGR). Score-first beat random-within-BUY only at the 58th–79th percentile — not distinguishable from random.
4. **Breadth exposure: rejected.** It cut IS drawdown (14–20 pts) but in OOS reduced CAGR by 8–19 pts with zero drawdown benefit.

## Q1 AVOID: 60-session excess (gross, %), NW t lag 60

| def | period | flagged % | vs EW mean (t) | yrs neg EW | vs TW mean (t) | yrs neg TW | raw mean / median |
|---|---|---|---|---|---|---|---|
| A0 S<55 | IS | 56 | -1.03 (-3.4) | 8/9 | -0.35 (-1.1) | 6/9 | +2.44 / +1.22 |
| A0 S<55 | OOS | 55 | -0.80 (-1.9) | 3/4 | -0.15 (-0.2) | 3/4 | +3.07 / +1.15 |
| A0 S<55 | FULL | 55 | -0.99 (-4.0) | 11/13 | -0.34 (-1.2) | 9/13 | +2.53 / +0.96 |
| AU S<55 OR <SMA200 | IS | 59 | -1.06 (-3.8) | 8/9 | -0.38 (-1.2) | 6/9 | +2.60 / +1.46 |
| AU S<55 OR <SMA200 | OOS | 56 | -0.81 (-1.9) | 3/4 | -0.16 (-0.3) | 3/4 | +3.11 / +1.22 |
| AU S<55 OR <SMA200 | FULL | 58 | -1.01 (-4.4) | 11/13 | -0.36 (-1.3) | 9/13 | +2.64 / +1.12 |
| AI S<55 AND <SMA200 | IS | 31 | -1.65 (-3.1) | 8/9 | -0.97 (-1.8) | 7/9 | +1.49 / +0.37 |
| AI S<55 AND <SMA200 | OOS | 25 | -1.62 (-2.3) | 3/4 | -0.97 (-1.2) | 4/4 | +1.69 / +0.49 |
| AI S<55 AND <SMA200 | FULL | 28 | -1.70 (-4.0) | 11/13 | -1.05 (-2.3) | 11/13 | +1.42 / +0.24 |

Q1 qualifies: {'A0 S<55': np.True_, 'AU S<55 OR <SMA200': np.True_, 'AI S<55 AND <SMA200': np.True_}; weakest-link OOS t: {'A0 S<55': np.float64(-0.25), 'AU S<55 OR <SMA200': np.float64(-0.26), 'AI S<55 AND <SMA200': np.float64(-1.16)} -> CHOSEN: AI S<55 AND <SMA200


## Q2 per-trade (one open trade per stock), net of cost

| plan | period | cost | n | win% | avg | median | p05 | avg sess | PF | t | null-A avg (all cands) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 | IS | 0.50 | 1822 | 31.9 | +2.44 | -2.65 | -14.9 | 24.6 | 1.56 | 5.0 | +1.36 |
| P1 | IS | 0.25 | 1822 | 32.7 | +2.69 | -2.40 | -14.6 | 24.6 | 1.64 | 5.6 | +1.61 |
| P1 | OOS | 0.50 | 1310 | 32.2 | +1.55 | -3.72 | -16.3 | 24.9 | 1.30 | 3.0 | +1.50 |
| P1 | OOS | 0.25 | 1310 | 33.1 | +1.80 | -3.47 | -16.1 | 24.9 | 1.36 | 3.5 | +1.75 |
| P2 | IS | 0.50 | 1253 | 51.8 | +5.08 | +0.87 | -24.8 | 51.9 | 1.76 | 6.7 | +2.87 |
| P2 | IS | 0.25 | 1253 | 52.4 | +5.33 | +1.12 | -24.5 | 51.9 | 1.81 | 7.1 | +3.12 |
| P2 | OOS | 0.50 | 937 | 50.3 | +3.62 | +0.13 | -27.8 | 51.4 | 1.45 | 4.3 | +3.77 |
| P2 | OOS | 0.25 | 937 | 50.6 | +3.87 | +0.38 | -27.6 | 51.4 | 1.49 | 4.6 | +4.02 |
| P3 | IS | 0.50 | 1365 | 42.9 | +4.35 | -2.81 | -21.8 | 42.3 | 1.71 | 6.3 | +2.34 |
| P3 | IS | 0.25 | 1365 | 43.4 | +4.60 | -2.56 | -21.6 | 42.3 | 1.77 | 6.7 | +2.59 |
| P3 | OOS | 0.50 | 985 | 42.6 | +3.34 | -4.67 | -23.1 | 43.7 | 1.45 | 4.4 | +2.95 |
| P3 | OOS | 0.25 | 985 | 42.6 | +3.59 | -4.42 | -22.8 | 43.7 | 1.50 | 4.7 | +3.20 |

Benchmarks (median over 20 offsets): {'IS': {'nifty_cagr': 12.834394124394528, 'nifty_mdd': -38.43985342533347, 'ew_cagr': 12.486453426655729, 'ew_mdd': -52.877952362015805}, 'OOS': {'nifty_cagr': 10.308470078340036, 'nifty_mdd': -15.287425083363804, 'ew_cagr': 19.838615653834047, 'ew_mdd': -20.69260472478004}}

## Q2 portfolios (₹1L, highest score first; CAGR median over 20 start offsets [min..max]; DD median / worst; null A = 600 runs at 0.50%)

| plan | period | slots | cost | CAGR [range] | maxDD med / worst | trades | null-A median | pct vs null A |
|---|---|---|---|---|---|---|---|---|
| P1 | IS | 4 | 0.50 | 19.9 [18.1..21.5] | -66 / -66 | 374 | 4.1 | 100 |
| P1 | IS | 4 | 0.25 | 22.8 [21.0..24.3] | -64 / -64 | 374 | 4.1 | 100 |
| P1 | IS | 8 | 0.50 | 22.7 [20.9..24.3] | -46 / -46 | 712 | 5.1 | 100 |
| P1 | IS | 8 | 0.25 | 25.6 [23.7..27.1] | -42 / -42 | 712 | 5.1 | 100 |
| P1 | IS | 8 | 0.65 | 21.1 [19.3..22.7] | -48 / -48 | 712 | 5.1 | 100 |
| P1 | OOS | 4 | 0.50 | -0.5 [-4.3..6.7] | -47 / -52 | 124 | 14.2 | 11 |
| P1 | OOS | 4 | 0.25 | 1.9 [-1.9..9.2] | -45 / -50 | 124 | 14.2 | 16 |
| P1 | OOS | 8 | 0.50 | 1.1 [-4.3..6.5] | -42 / -47 | 253 | 14.3 | 7 |
| P1 | OOS | 8 | 0.25 | 3.7 [-1.9..9.1] | -40 / -45 | 253 | 14.3 | 12 |
| P1 | OOS | 8 | 0.65 | -0.3 [-5.8..5.0] | -43 / -48 | 253 | 14.3 | 5 |
| P2 | IS | 4 | 0.50 | 29.4 [15.6..38.4] | -63 / -65 | 174 | 7.8 | 100 |
| P2 | IS | 4 | 0.25 | 30.8 [16.9..39.8] | -62 / -64 | 174 | 7.8 | 100 |
| P2 | IS | 8 | 0.50 | 24.3 [20.0..29.7] | -56 / -62 | 343 | 8.7 | 100 |
| P2 | IS | 8 | 0.25 | 25.6 [21.4..31.0] | -55 / -61 | 343 | 8.7 | 100 |
| P2 | IS | 8 | 0.65 | 23.5 [19.3..28.8] | -57 / -63 | 343 | 8.7 | 100 |
| P2 | OOS | 4 | 0.50 | 26.7 [6.4..55.5] | -34 / -43 | 56 | 15.9 | 81 |
| P2 | OOS | 4 | 0.25 | 28.0 [7.5..56.9] | -34 / -42 | 56 | 15.9 | 84 |
| P2 | OOS | 8 | 0.50 | 22.4 [8.1..43.7] | -31 / -40 | 114 | 16.8 | 74 |
| P2 | OOS | 8 | 0.25 | 23.7 [9.3..45.0] | -30 / -39 | 114 | 16.8 | 80 |
| P2 | OOS | 8 | 0.65 | 21.7 [7.4..42.9] | -31 / -40 | 114 | 16.8 | 70 |
| P3 | IS | 4 | 0.50 | 25.6 [23.5..26.3] | -63 / -63 | 200 | 8.5 | 100 |
| P3 | IS | 4 | 0.25 | 27.2 [25.1..27.9] | -61 / -61 | 200 | 8.5 | 100 |
| P3 | IS | 8 | 0.50 | 24.6 [22.3..26.2] | -59 / -59 | 401 | 8.4 | 100 |
| P3 | IS | 8 | 0.25 | 26.2 [23.9..27.7] | -57 / -57 | 401 | 8.4 | 100 |
| P3 | IS | 8 | 0.65 | 23.7 [21.4..25.2] | -60 / -60 | 401 | 8.4 | 100 |
| P3 | OOS | 4 | 0.50 | 10.2 [-1.2..19.9] | -38 / -44 | 63 | 14.0 | 37 |
| P3 | OOS | 4 | 0.25 | 11.5 [0.0..21.2] | -38 / -43 | 63 | 14.0 | 41 |
| P3 | OOS | 8 | 0.50 | 14.4 [7.6..23.8] | -34 / -39 | 125 | 15.9 | 43 |
| P3 | OOS | 8 | 0.25 | 15.8 [8.9..25.2] | -33 / -38 | 125 | 15.9 | 49 |
| P3 | OOS | 8 | 0.65 | 13.7 [6.9..23.0] | -34 / -39 | 125 | 15.9 | 40 |

Q2 eligible {'P1': np.True_, 'P2': np.True_, 'P3': np.True_}; score (mean pctA) {'P1': 54.37499999999999, 'P2': 88.83333333333333, 'P3': 69.95833333333333} -> CHOSEN P2 (eligible); real-money bar passed: False

## Q3 selection within BUY (P2, 0.50%)

| rule | period | slots | CAGR [range] | DD med |
|---|---|---|---|---|
| S-high | IS | 4 | 29.4 [15.6..38.4] | -63 | pct vs random 79
| ATR-low | IS | 4 | 27.6 [19.4..34.2] | -57 | pct vs random 72
| random | IS | 4 | 22.9 [13.1..36.7] (5-95%) | -55 | pct vs random 50
| S-high | IS | 8 | 24.3 [20.0..29.7] | -56 | pct vs random 64
| ATR-low | IS | 8 | 26.6 [21.1..30.9] | -44 | pct vs random 79
| random | IS | 8 | 22.8 [17.7..29.9] (5-95%) | -50 | pct vs random 50
| S-high | OOS | 4 | 26.7 [6.4..55.5] | -34 | pct vs random 74
| ATR-low | OOS | 4 | 21.9 [7.1..44.6] | -28 | pct vs random 61
| random | OOS | 4 | 18.3 [-1.8..38.9] (5-95%) | -34 | pct vs random 50
| S-high | OOS | 8 | 22.4 [8.1..43.7] | -31 | pct vs random 58
| ATR-low | OOS | 8 | 21.0 [12.8..32.2] | -24 | pct vs random 51
| random | OOS | 8 | 20.8 [4.8..38.0] (5-95%) | -29 | pct vs random 50

Q3 CHOSEN S-high; selection adds nothing vs random (IS): False

## Q4 breadth exposure (P2, S-high, 0.50%)  share of days full/half/none: IS [68, 18, 14]  OOS [69, 21, 10]

| period | slots | base CAGR / DD | gated CAGR [range] / DD med (worst) | ΔDD | ΔCAGR lost | ratio |
|---|---|---|---|---|---|---|
| IS | 4 | 29.4 / -63 | 23.9 [21.4..25.0] / -49 (-49) | +14.0 | +5.4 | 2.58 |
| IS | 8 | 24.3 / -56 | 24.8 [21.4..26.1] / -36 (-43) | +19.8 | -0.5 | inf |
| OOS | 4 | 26.7 / -34 | 7.6 [0.8..26.3] / -34 (-43) | +0.2 | +19.1 | 0.01 |
| OOS | 8 | 22.4 / -31 | 14.6 [7.7..25.0] / -31 (-44) | -0.5 | +7.8 | -0.07 |

Q4 ADOPT breadth exposure: False

## Pseudo-code of rules that differ from REPORT4 (everything else unchanged: score, universe, BUY, ranking)
```
SMA200 = mean(C[i-199..i])  (bars, not days; needs i >= 199; always defined for rated stocks since nbars >= 252)
Label (in this order):
  NOT RATED  as REPORT4
  BUY        S >= 85 AND C[D] > SMA50 AND NOT UCLOCK                       (unchanged)
  AVOID      S < 55 AND C[D] < SMA200                                     (CHANGED: was S < 55)
  WAIT       every other rated case (includes S < 55 with C[D] >= SMA200)
Trade plan for a BUY on day D (ATR = ATR14 at bar D, simple mean)           (CHANGED: replaces X10)
  shown before fill: entryMax = C[D] + 0.5*ATR; stopEst = C[D] - 5*ATR; horizon = 60 sessions; no targets shown
  fill: E = O[D+1]; if no bar on D+1 or E > entryMax: no trade
  STOP = E - 5*ATR (fixed, never moved)
  for each session k = D+1 .. D+60 on which the stock has a bar, in this order:
     1. if k > D+1 and O[k] <= STOP:  exit at O[k]          (gap through the stop)
     2. if L[k] <= STOP:              exit at STOP
     3. if k == D+60:                 exit at C[k]
  if the stock has no bar on D+60: exit at its last close before D+60
  (no SMA50 exit, no target exit)
  one open trade per stock; cost assumption 0.50% of entry value per round trip
Selection: highest S first, ties symbol ascending (unchanged). Exposure: none (unchanged; breadth gate rejected).
```
