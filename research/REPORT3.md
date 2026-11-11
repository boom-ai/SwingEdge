# Round 3 (11 Nov 2026): stricter dip rule, EMA 10/20 setup

Same data and method as REPORT.md. Scripts: 10_dip_quality.py, 11_dip_stop.py, 12_ema.py; app-engine run via the Kotlin backtest on the full 2011-2026 panel.

- **Old app dip rule** (above 200-day, 50>200, RSI(2)<5, close near low, 2-ATR stop, 6 days): +0.34% / +0.38% per trade in/out of sample, but a 4-position portfolio lost money (about -3% a year since 2022, -70% drawdown).
- **Leader dip** (price above a rising 50-day average above the 200-day, top 30% 6-month momentum, RSI(2)<5, 3-ATR stop, sell on first close above the 5-day average or day 6): all signals +0.61% / +0.64% per trade, 66% winners, profit factor 1.36. Run through the app's own engine (max 4 new trades a day, highest momentum first, 1,228 trades): +0.14% per trade over 15 years, +0.55% since 2022, profit factor 1.07. That fails the app's gate (needs +0.25% and 1.2), so it ships as a labelled test idea, not a live buy call.
- **EMA 10/20 about to cross up** (gap under 0.5% and closing for 2 days, close above the 20 EMA, above the 200-day): +0.05% / +0.07% per trade over 6 days, the same as a random stock above its 200-day average (+0.16% / +0.07%). The fresh-cross version is no better. Shipped as a watchlist for manual chart reading, with that stated.
