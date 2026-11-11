# Round 2 (11 Nov 2026): buy rules, sell rules

Data and method as in REPORT.md (NSE bhavcopies 2010-06 to 2026-11-09, own split adjustment, liquid = 20-day turnover >= Rs 25 cr and price >= Rs 50, charges 0.25% per round trip, in-sample to 2021, out-of-sample 2022 on). Scripts: 07_more.py, 07_round2.py, 08_verify.py, 09_washout_realistic.py.

## Buy rules
- **Washout dip (LIVE in the app).** Only when fewer than 25% of stocks are above their 50-day average: buy up to 4 stocks above their 200-day average with RSI(2) < 10, lowest RSI first, hold 5 days, 3-ATR stop. Max-4-position simulation: 393 trades, 59% winners, +1.09% per trade after charges (in-sample +0.77%, out-of-sample +1.83%), worst year 2020 at -0.9% per trade. Survives next-open entry, 0.40% charges, holds of 3-8 days, top 2/4/8. It fires only in market-wide sell-offs: some years have no signal days at all (2014, 2017, 2021, 2023), others 15-50.
- **Monthly leaders (gated by the phone's own test).** Top stocks by risk-adjusted 6-month momentum held about a month. Beats the index in both periods in most variants (for example top-4 with Nifty above its 50-day average: about 22-24% a year in-sample, 17-22% out-of-sample) but with 30-60% drawdowns and losing years as bad as -40%. Results move a lot between variants.
- Rejected again: 52-week-high breakouts on 10-day holds, leader pullback to the 50-day average, gap-up continuation, turn-of-month (no better than random uptrend stocks out-of-sample). RSI(2) < 5 dips restricted to momentum leaders earn +0.5-0.6% per trade but a 4-slot portfolio of them is negative at 0.40% charges.

## Sell and trim rules (forward return minus the average liquid stock, same days)
- Close below the 200-day average with the 50-day below the 200-day: -0.7% over 20 days and about -2% over 60 days, in both periods (t about -2.4 to -2.8). Kept as SELL.
- Trailing-stop breaks, 3-month laggards, first cross under the 200-day: small and not reliable. Now WATCH, not SELL.
- "Stretched" stocks (over 20% above the 50-day, RSI(14) > 80, blow-off days) went on to beat the average stock by 1-3% over 60 days. Trimming them cost money, so TRIM on strength is off.
