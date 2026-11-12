# Round 5 pre-registration (written 12 Nov 2026 before any round-5 computation)

Data: same panel/features as round 4 (out_r4/feat.npz, score.npz, work/wide_adj.pkl). Code: scripts/50_*.py (reuse r4lib / 40_common / 42_variants logic; no earlier file modified).

## Common definitions
- Hole: GAP = first bar after the 2025-05-05..2025-06-20 hole. A signal on bar t is EXCLUDED if its 60-session forward window or any feature window (≤252 bars) touches the hole: t in [GAP-60, GAP+251]. Hence OOS = signals/portfolio days 2022-01-03 .. bar GAP-61 (≈ Mar 2025), one contiguous window for every test. IS = 2013-01-01 .. 2021-12-31 (per-trade and AVOID: signals t ≤ tsplit-61 for AVOID so forward windows stay in IS; portfolios: window t0..tsplit-1 as in round-4 review).
- Costs: primary 0.50% round trip per trade, secondary 0.25%. 8-slot portfolios also shown at 0.65% (sensitivity only, not used in decisions). AVOID tests are cost-free (no trade).
- Rated = in universe U and score defined. BUY = S ≥ 85 & C > SMA50 & not UCLOCK.
- Matched null A: on each day draw 20 random stocks (without replacement) from {rated, C > SMA50, not UCLOCK, ATR defined}, random rank, same plan/exits/slots/costs. 150 seeds × 4 start offsets (0,15,30,45 sessions) = 600 runs per cell. Plan offsets: 20 offsets (0..57 step 3); reported "CAGR" = median over offsets, range = min..max; percentile = % of the 600 null runs whose CAGR is below the plan's median CAGR.
- Benchmarks: Nifty 50 price index; equal-weight rated-universe daily-rebalanced basket; same windows/offsets (median).
- NW t: Newey-West, Bartlett, lag 60, on daily cross-sectional means (r4lib.nw_t).

## Q1 AVOID (60 sessions, D+1 open → D+60 close, gross)
Definitions: A0 = S<55; AU = S<55 OR C<SMA200; AI = S<55 AND C<SMA200 (all among rated).
Excess per stock-day vs (a) equal-weight mean of rated stocks with a forward return, (b) TO20-weighted mean of the same set. Daily mean over flagged stocks → mean, NW t, years negative (signal-year means), flagged share (flagged / rated stock-days). IS and OOS.
Decision rule: a definition qualifies if IS t ≤ −2 vs EW and OOS mean < 0 vs both benchmarks. Weakest-link t = max(t_EW, t_TW) in OOS. A0 is kept unless a qualifying challenger has an OOS weakest-link t at least 0.5 more negative than A0's AND an OOS lag vs TW at least as large (more negative) as A0's. If both challengers pass, take the more negative weakest-link t. Flagged share is reported; it does not decide.

## Q2 Trade plan (BUY candidates; all plans: fill next open D+1 only if O[D+1] ≤ C[D] + 0.5·ATR14[D]; ATR14 simple mean at D)
- P1 = X10 (REPORT4): stop E−3·ATR, gap-through exit at open, time exit D+60 close, exit next open after a close < SMA50.
- P2: stop E−5·ATR fixed (gap-through: if O[k] ≤ STOP for k > D+1 exit at O[k]; else if L[k] ≤ STOP exit at STOP), time exit at C[D+60]; no MA exit.
- P3: P2 + exit at the next open after a close < SMA100 (SMA100 = mean of the stock's last 100 bars, needs 100 bars; computed like SMA50).
Per-trade: every BUY signal, one open trade per stock (next signal accepted when t ≥ previous exit bar). Stats net of cost: n, win%, mean, median, p05, avg sessions (exit bar − D), profit factor; and matched per-trade null = mean of the same plan over all null-A candidate signals in the window.
Portfolios: 4 and 8 slots, highest score first (r4lib.portfolio logic), IS and OOS, vs Nifty, EW, null A.
Decision rule (0.50% cost): eligible if IS null-A percentile ≥ 90 at both 4 and 8 slots and OOS per-trade mean net > 0. Pick eligible plan with the highest average of (IS pct + OOS pct) over both slot counts; if within 3 points, prefer the simpler plan in order P2, P3, P1. If none eligible, pick by the same score and label "unproven".
Real-money bar (stated now, expected to fail): IS and OOS null-A pct ≥ 95 at both slot counts, OOS median CAGR > EW basket, worst-offset OOS max DD better than −35%.
Contamination: P2 = round-4 V8 (plus 0.5-ATR entry cap) was chosen after seeing 2022–26 among 11 variants; the best of 11 random variants has a median null percentile of 100·0.5^(1/11) ≈ 94. OOS percentiles of P2/P3 below ~94 are therefore consistent with no edge; only IS is clean for P2/P3.

## Q3 Selection within BUY (on the Q2-chosen plan, 4 and 8 slots, 0.50%)
Rules: S-high (score desc, ties symbol), ATR-low (ATR14/C ascending), random (150 seeds, 1 offset each drawn from the 20).
Decision: switch to ATR-low only if its median CAGR exceeds S-high in IS AND OOS at both slot counts AND its IS max DD (median over offsets) is no worse. If neither deterministic rule beats the random median in IS at both slot counts, record "selection adds nothing" and keep S-high (deterministic, matches app).

## Q4 Breadth exposure (on the chosen plan + chosen selection, 4 and 8 slots, 0.50%)
breadth[D] = share of liquid stocks with C > SMA50 on D (reg.breadth50). Allowed open positions for NEW entries on signal day D: slots if breadth ≥ 0.50; floor(slots/2) if 0.30 ≤ breadth < 0.50; 0 if < 0.30. Open positions are never force-closed; each new position still gets basis/slots (idle cash otherwise).
Judge: ΔDD = DD_gated − DD_base (positive = shallower, pts), ΔCAGR = CAGR_base − CAGR_gated (pts lost). Adopt if in BOTH IS and OOS at both slot counts: ΔDD ≥ 5 pts and (ΔCAGR ≤ 0 or ΔDD/ΔCAGR ≥ 1.0). Medians over offsets.

Nothing else will be run except bookkeeping (benchmarks, verification of the SMA100 method against stored SMA50, and reproduction of P1 per-trade numbers against round 4 on the round-4 window).
