# SwingEdge Research Compendium: Strategies Used by Working Traders, Filtered for an Indian Retail Swing Trader (~₹1 lakh)

Compiled 2026-11-27 by the SwingEdge research department.

## 0. How to read this document

### 0.1 Evidence grades

| Grade | Meaning |
|---|---|
| **A** | Peer-reviewed or index-provider/regulator data, replicated across periods or markets. |
| **B** | Published backtest with numbers (book, SSRN working paper, quant blog) but not peer-reviewed, or single-market. |
| **C** | Anecdotal: a famous trader's self-reported results or a charting tradition. No independent test found. |
| **D** | No credible evidence found, or evidence is negative. |

### 0.2 Verification status (read this)

I verified the following **online in this session** (fetched or seen in search results):
- the SEBI F&O and intraday studies;
- the Nifty200 Momentum 30 methodology and returns (NSE Indices whitepaper dated Feb 2026);
- Connors RSI(2) rules (StockCharts ChartSchool);
- the Minervini Trend Template thresholds (ChartMill);
- the Ansari & Khan (2012) Indian momentum paper (abstract level only).

All other rules come from the primary books/papers named in each entry and are reproduced from the author's published description as I know it. I did not re-fetch those books. Where I recall a specific statistic but could not verify it, it is tagged **(unverified recall)**. Treat those as leads, not facts. Where a trader's rules vary between editions or secondary sources, I say so.

Backtests below are **not** SwingEdge backtests. Section 12 lists what to run ourselves before shipping any scanner as a "signal".

### 0.3 Sources verified online

- SEBI press release PR 37/2024 (23 Sep 2024), "93% of individual traders incurred losses in equity F&O between FY22 and FY24": https://www.sebi.gov.in/media-and-notifications/press-releases/sep-2024/updated-sebi-study-reveals-93-of-individual-traders-incurred-losses-in-equity-fando-between-fy22-and-fy24-aggregate-losses-exceed-1-8-lakh-crores-over-three-years_86906.html
- SEBI FY25-FY26 study (Aug 2026): https://www.sebi.gov.in/sebi_data/attachdocs/aug-2026/1787233601328.pdf
- Business Standard on the FY25 study: https://www.business-standard.com/amp/markets/news/net-losses-of-traders-in-fo-widens-in-fy25-sebi-study-125070701221_1.html
- Outlook Business on the FY22-24 study (average loss, cost detail): https://www.outlookbusiness.com/markets/fo-frenzy-over-93-retail-investors-lost-average-rs-2-lakh-in-three-years-says-sebi
- Open Magazine summary of the FY26 study (secondary): https://openthemagazine.com/business/sebi-fo-loss-study-explained-why-9-in-10-retail-traders-lost-91685-crore-in-fy26
- Business Standard on the SEBI intraday cash study (Jul 2024): https://www.business-standard.com/amp/markets/news/over-70-intra-day-traders-incur-losses-during-fy23-reveals-sebi-study-124072401110_1.html
- NSE Indices, Nifty200 Momentum 30 strategy whitepaper (2026): https://www.niftyindices.com/docs/default-source/indices/nifty200-momentum-30/momentum-strategy-whitepaper_2026.pdf
- StockCharts ChartSchool, RSI(2): https://chartschool.stockcharts.com/table-of-contents/trading-strategies-and-models/trading-strategies/rsi-2
- ChartMill, Minervini Trend Template: https://www.chartmill.com/documentation/stock-screener/technical-analysis-trading-strategies/496-Mark-Minervini-Trend-Template-A-Step-by-Step-Guide-for-Beginners
- Ansari & Khan (2012), "Momentum anomaly: evidence from India", Managerial Finance: https://emerald.com/insight/content/doi/10.1108/03074351211193730/full/html

---

## 1. Constraints that shape everything for a ₹1 lakh Indian retail swing trader

These facts decide which strategies are even feasible. Cost figures are from my knowledge of the NSE/SEBI fee schedule and need re-verification before they go into the app (the STT rates especially).

| Constraint | Consequence for strategy selection |
|---|---|
| **Capital ~₹1,00,000** | At 1% risk per trade the risk budget is ₹1,000 per trade. Plan for 4-6 concurrent positions of ₹15-25k each. Index futures need far more capital. NIFTY/BANKNIFTY lot sizes are now much larger than before SEBI's late-2024 contract-size rules (SEBI wanted ₹15 lakh+ contract value; verify current lot sizes). So futures and spreads with defined risk are mostly out of reach. |
| **Cannot short stocks overnight in cash** | Delivery shorting does not exist for retail (no practical SLB). Swing shorts are possible only via stock futures (large lots) or intraday MIS. So the realistic swing universe is **long-only equity delivery**. Short-side setups (parabolic shorts, upthrusts, short RSI2) are mostly informational or a market-regime input. |
| **Delivery costs (equity)** | STT 0.1% on both buy and sell. Also stamp duty about 0.015% on the buy, exchange and SEBI fees, GST, and a DP charge per sell (~₹13-16 per scrip). Round trip is roughly **0.20-0.25% plus slippage**. Brokerage is ₹0 at discount brokers. (From memory; verify.) |
| **Intraday costs** | STT 0.025% on the sell side. Brokerage about ₹20 or 0.03% per order. Round trip is about **0.05-0.10% plus slippage**. |
| **Options costs** | STT on option sell premium and futures was raised effective 1 Oct 2024 (options 0.1%, futures 0.02% per my recall). Flat brokerage of about ₹20 per order. The percentage cost is small on big premiums and large on cheap OTM options. |
| **Circuits and surveillance** | Stocks hit 5/10/20% circuit bands and cannot be exited. ASM/GSM lists, T2T segments and the F&O ban list apply. Scanners must exclude them. |
| **Liquidity** | With ₹20k per position almost any Nifty 500 stock is tradeable. Still require average daily traded value of roughly ₹5 crore or more, to avoid bad fills and operator-driven names. |
| **T+1 settlement** | Since Jan 2023. Sale proceeds are usable next day. Good for rotation. |
| **Data availability** | NSE bhavcopy (EOD OHLCV plus delivery quantity) is free. F&O bhavcopy gives OI and settle price. NSE changed bhavcopy file formats (UDiFF) in 2024, so the scraper must handle that. Intraday data needs a broker API or paid vendor. Corporate actions (splits, bonuses) must be back-adjusted or every backtest is wrong. |

**Implication.** The viable core is an **end-of-day scanner for long-only equity swing/position trades** (holding days to weeks), gated by market regime, with strict position sizing. Everything intraday or options-based is optional and should carry loud risk warnings. Section 11 explains why.

---

## 2. Famous traders and their published methods

Each entry follows the same fields. "App implementation" lists the data needed and the scan logic.

### 2.1 Mark Minervini: SEPA, Trend Template, VCP

**Sources.** *Trade Like a Stock Market Wizard* (2013); *Think & Trade Like a Champion* (2017); interviews in Schwager's *Stock Market Wizards* (2001).

**Description.** SEPA (Specific Entry Point Analysis) combines four filters: trend, fundamentals, catalyst, and a low-risk entry point. The Trend Template restricts you to Stage 2 uptrends. The Volatility Contraction Pattern (VCP) is the entry pattern: a base where price swings contract (for example 25%, then 12%, then 6%) as volume dries up. The buy is a breakout through the last contraction's high (the pivot).

**Trend Template** (thresholds verified via ChartMill; the book's wording is slightly looser on some):
1. Close above the 150-day and 200-day SMA.
2. 150-day SMA above the 200-day SMA.
3. 200-day SMA rising for at least 1 month (Minervini prefers 4-5 months).
4. 50-day SMA above both the 150-day and 200-day.
5. Close above the 50-day SMA.
6. Close at least 30% above the 52-week low. (Some sources say 25%.)
7. Close within 25% of the 52-week high. (Within 10-15% is better.)
8. Relative strength rating of 70 or higher. (80-90+ is preferred.)

**VCP entry/exit rules (from the books):**
- Base of 2-6 contractions. Each pullback is shallower than the one before. Typical depths are 20-35% for the first, then roughly halving.
- Volume contracts across the base. Volume is lowest in the final contraction.
- **Entry:** buy as price trades through the pivot (the high of the last tight area) on volume at least 40-50% above average. Buy within about 5% of the pivot, no further.
- **Stop:** about 7-8% maximum below entry, or below the last contraction's low. Minervini states an average loss of about 6%. He wants low-risk entries where the stop is only 3-5% away.
- **Exit:** sell a partial at 2-3 times risk (about 20-25% gain). Trail the rest. Sell on a close below the 50-day SMA on heavy volume or on a climactic run.
- **Sizing:** fixed risk of about 1.25% (or less) of equity per trade.

**Timeframe.** Daily charts, holds of weeks to months.
**Best regime.** Confirmed market uptrend. Minervini goes to cash in downtrends.
**Evidence: C (with academic support for components).** He self-reports winning the 1997 US Investing Championship with a 155% return and an audited ~220% annual average over 1994-2000. His later claims (2021 championship) are also self-reported **(unverified recall)**. No independent backtest of the full SEPA process exists, because the fundamental and discretionary judgment parts cannot be mechanised. Components have academic backing: 52-week-high proximity (George & Hwang 2004, *J. Finance*) and cross-sectional momentum (Jegadeesh & Titman 1993).
**Failure modes.**
- Whipsaw breakouts in choppy or bear markets.
- Many stocks pass the template but few produce big winners. The win rate is low (about 40-50%) and relies on skew.
- Survivorship in anecdotes.
- Gaps through the stop on Indian small caps with circuit limits.
**App implementation.** Daily OHLCV for the full Nifty 500 (about 252+ days of history for the 200-day MA and 52-week range). RS rating = percentile rank of 12-month (or weighted 3/6/9/12-month) return vs the universe. The template is a pure boolean screen. VCP detection is harder: find successive swing highs and lows in the last 30-60 bars, check that each contraction's %-depth is smaller than the previous, and check that the average volume of the last 10 bars is below the 50-day average. Alert when price closes above the pivot with volume ratio of at least 1.4. Pivot-breakout detection is fully scannable. Fundamentals are optional (needs quarterly EPS data).

### 2.2 William O'Neil: CAN SLIM, cup-with-handle, pivot buys

**Sources.** *How to Make Money in Stocks* (1988, 4th ed. 2009); IBD research; *24 Essential Lessons for Investment Success*.

**Description.** CAN SLIM is a seven-factor screen derived from O'Neil's study of the biggest winners from 1953 onwards:
- C: current quarterly EPS growth of 25% or more.
- A: annual EPS growth of 25% or more across 3 years, with ROE of about 17% or more.
- N: new product, management or a **new price high**.
- S: supply and demand (volume, smaller float).
- L: leader, with an RS rating of 80 or more.
- I: institutional sponsorship.
- M: market direction.

**Cup-with-handle rules:**
- Cup lasts 7-65 weeks (typically 7-26). Depth is 12-33% in normal markets, up to 40-50% in bear markets.
- The cup is U-shaped, not V-shaped. The handle is 1-4 weeks long and pulls back 8-12% (no more than about a third of cup depth). The handle should drift down on drying volume, in the upper half of the cup.
- **Buy point (pivot):** handle high plus a small increment (about 10 cents in the US). Buy on volume at least 40-50% above average.
- **Buy range:** up to 5% above the pivot. Do not chase beyond.
- **Stop:** 7-8% below the buy price, strictly.
- **Profit-taking:** 20-25% gain. Exception: if the stock gains 20% or more within 3 weeks of breakout, hold at least 8 weeks (the "8-week hold rule").

**Market direction (the M).** A rally attempt starts after a low. A **follow-through day** is day 4 or later of the attempt, where the index rises roughly 1.25-2% or more on volume above the prior day. It signals a confirmed uptrend. **Distribution days** (index down 0.2% or more on higher volume) count; four to five in 4-5 weeks warns of a top.

**Timeframe.** Daily/weekly. Holds of weeks to months.
**Best regime.** Confirmed uptrend (after a follow-through day).
**Evidence: B/C.** O'Neil's model-book study is a large in-sample study of past winners (hindsight bias, no control group). IBD's own reports claim outperformance. Independent academic support exists for the building blocks (earnings momentum, 52-week-high momentum). Not independently validated as a complete system.
**Failure modes.** Faulty patterns that fail soon after breakout (most cups fail), late-stage bases, the market turning down within weeks, the subjectivity of "shape".
**App implementation.** Daily OHLCV. Cup detection: find the prior high, the lowest point, the right-side recovery to within 10-15% of the left high, depth 12-33%, and length 7-65 weeks. Handle: a 5-20 day pullback of at most 12% in the upper half of the cup with volume below average. Alert on close above handle high with volume at least 1.4 times the 50-day average. The follow-through day and distribution-day counter run on the Nifty 50 / Nifty 500 index. Fundamentals (EPS growth) are optional.

### 2.3 Nicolas Darvas: Box theory

**Source.** *How I Made $2,000,000 in the Stock Market* (1960).

**Description.** Darvas viewed a stock trending upward as moving in a series of boxes: ranges between a ceiling and a floor. He bought when price broke out of the top of a box on high volume and held until it dropped out of the box floor.

**Rules (as commonly reconstructed from the book):**
- Box top = a high that is not exceeded for 3 consecutive days. Box bottom = a low not violated for 3 days after that.
- **Entry:** buy when price breaks above the box top, near new highs on strong volume.
- **Stop:** just below the box bottom. Raise the stop each time a new, higher box forms.
- Only trade in a rising market. Darvas preferred stocks with strong earnings momentum (he used news as a catalyst) that were making all-time highs.

**Timeframe.** Daily/weekly; holds of weeks to months. **Best regime.** Bull market with narrow leadership.
**Evidence: C.** He claimed $2 million made in about 18 months (1957-58), a story from his own book, with some press coverage. No independent test. Mechanically it is a form of channel breakout, which is trend following, and that has academic support.
**Failure modes.** Whipsaws in sideways markets; boxes defined by an arbitrary 3-day rule; ambiguous box construction.
**App implementation.** Daily OHLCV. Rolling 3-day confirmed highs/lows define boxes. Alert on box-top break with volume above 1.5 times average and stock within 5% of a 52-week high.

### 2.4 Stan Weinstein: Stage analysis and the 30-week MA

**Source.** *Secrets for Profiting in Bull and Bear Markets* (1988).

**Description.** Every stock cycles through four stages, determined by the 30-week moving average (about 150-day):
- **Stage 1:** basing; the MA flattens after a decline.
- **Stage 2:** advancing; price above a rising 30-week MA.
- **Stage 3:** topping; the MA flattens, price chops around it.
- **Stage 4:** declining; price below a falling MA.

**Rules:**
- **Buy:** in Stage 2, on the first breakout above the resistance of the Stage 1 base, on volume at least 2 times (+100%) normal, with the 30-week MA flat or turning up and **Mansfield relative strength** above its zero line (stock RS vs the index above its own moving average).
- **Stop:** below the last significant correction low in the base, or below the 30-week MA.
- **Sell:** when price closes below the 30-week MA, or when it reaches Stage 3 signs.
- A second-chance entry is a low-risk pullback to the breakout level.
- Market filter: only buy when the market (and the stock's group) is in Stage 2.

**Timeframe.** Weekly charts, holds of months to a year. **Best regime.** Early bull market.
**Evidence: B/C.** Simple long-term trend-following rules are supported by academic findings on moving-average timing (Brock, Lakonishok & LeBaron 1992, *J. Finance*; Faber 2007, "A Quantitative Approach to Tactical Asset Allocation"; Gunasekarage & Power 2001 on South Asian markets, including India **(unverified recall)**). No audited track record of Weinstein's exact method exists.
**Failure modes.** Late entries; sideways markets cause repeated whipsaws around the MA; weekly bars give slow exits (large give-backs).
**App implementation.** Weekly OHLCV derived from daily. Stage classification = sign of the 30-week MA slope (over about 5 weeks) plus price location. The Mansfield RS line = (stock/index) divided by its 52-week SMA, minus 1. Alert on first weekly close above base resistance with volume at least 2 times the 10-week average. This is a straightforward, fully mechanisable scanner.

### 2.5 Richard Dennis and the Turtles: Donchian breakouts, N-based sizing

**Sources.** Curtis Faith, *Way of the Turtle* (2007) and the published "Original Turtle Trading Rules" (2003); Michael Covel, *The Complete TurtleTrader* (2007).

**Description.** Dennis taught about 23 novices in 1983-84 a mechanical futures trend-following system. Rules were published in full only in 2003.

**Rules (futures; can be adapted to stocks):**
- **N** = 20-day ATR (the volatility unit). Dollar volatility = N times dollars per point.
- **Unit size** = 1% of account equity divided by dollar volatility of N. So one N move against a unit costs 1% of the account.
- **System 1 entry:** break of the 20-day high (long) or low (short). Skip the signal if the previous System 1 trade was a winner (unless it is a 55-day breakout, which is taken as a failsafe). **Exit:** 10-day low (long) or 10-day high (short).
- **System 2 entry:** break of the 55-day high/low. **Exit:** 20-day low/high.
- **Adding:** add one unit at each half-N move in your favour, up to 4 units per market.
- **Stop:** 2N from the entry (so 2% risk per unit). Move the stop up by half N as each unit is added.
- **Portfolio limits:** 4 units in one market, 6 in closely correlated markets, 10 in loosely correlated markets, 12 in one direction.

**Timeframe.** Daily; holds weeks to months. **Best regime.** Trending (inflationary, crisis, or strong-trend periods).
**Evidence: A/B.** The Turtles' aggregate record is well documented as about $175M profit over 4-5 years (Covel/Faith), though verified details vary. Trend following in futures has strong academic support: Moskowitz, Ooi & Pedersen (2012), "Time series momentum", *J. Financial Economics*; Hurst, Ooi & Pedersen (2017), "A Century of Evidence on Trend-Following Investing". For single stocks in India the evidence is weaker and the edge smaller **(not verified)**.
**Failure modes.** Long flat-to-negative stretches (trend followers had a poor 2010s), 30-50% drawdowns, a win rate of only about 35-45%, sensitivity to costs when trading many markets.
**App implementation.** Daily OHLCV. Donchian channels (20/55-day highs, 10/20-day lows), ATR(20) for sizing and stops. In a long-only equity version for a small account, use the 55-day high as the entry, the 20-day low as the exit, and 2 ATR initial stops. Pyramiding needs capital, so skip it at ₹1 lakh.

### 2.6 Linda Raschke: Holy Grail, 80-20, Turtle Soup

**Sources.** Linda Bradford Raschke and Larry Connors, *Street Smarts: High Probability Short-Term Trading Strategies* (1995); Raschke's interviews in Schwager's *The New Market Wizards* (1992).

**Description.** Short-term (1-5 day) setups mostly derived from the idea that a trending market that pauses offers a low-risk entry, and that failed breakouts reverse fast.

**Rules:**
- **Holy Grail** (a trend continuation pullback):
  - Condition: ADX(14) above 30 and rising (trend is strong).
  - Setup: price pulls back to the 20-day EMA.
  - **Entry:** buy a break of the prior bar's high (the bar that touched the 20 EMA).
  - **Stop:** below the pullback low.
  - **Exit:** at the prior swing high, or trail.
- **Turtle Soup** (a failed 20-day breakout):
  - Condition: price makes a new 20-day low, where the previous 20-day low occurred at least 4 trading days earlier.
  - **Entry (long):** buy a stop a few ticks above the old 20-day low, once price re-penetrates it (a reversal back above).
  - **Stop:** just below the new low (one tick).
  - **Exit:** trail, or take profit at a small target (typically 1-2 times risk).
  - **Turtle Soup Plus One:** the same idea but the failed break happens one day after the new low.
  - Shorts are mirror-image.
- **80-20** (a reversal bar at an extreme): In *Street Smarts*, a prior day that opened in the bottom (top) 20% of its range and closed in the top (bottom) 20%, in the context of a multi-day move in the opposite direction, sets up a reversal entry the next day, using a buy-stop above the prior day's high (sell-stop below the low). **I could not confirm the exact bar-filter wording; implement from the book text, not from this summary.**

**Timeframe.** Daily (also intraday). Holds 1-5 days. **Best regime.** Holy Grail: trending. Turtle Soup: range-bound or choppy markets with false breakouts.
**Evidence: C/B.** Raschke's record is anecdotal (Market Wizards). The Turtle Soup logic (failed breakouts) has been backtested on indices by quant bloggers with mixed results; I did not verify specific figures. Raschke herself has said she adjusted the 1990s patterns as markets changed. This is a warning for pattern decay.
**Failure modes.** Edge decay once widely known, slippage on stop entries, "trending" signals whipsawed in news-driven gaps.
**App implementation.** Daily OHLCV. Holy Grail: ADX(14) above 30, close within 1 ATR of the 20 EMA, and alert on break of the prior day's high. Turtle Soup: new 20-day low (with the prior 20-day low at least 4 bars ago), then close or intraday move back above the old low.

### 2.7 Larry Williams: Volatility breakout, %R, Oops, Smash Day

**Sources.** *Long-Term Secrets to Short-Term Trading* (1999, 2nd ed. 2012); *How I Made One Million Dollars Last Year Trading Commodities* (1973).

**Description.** Williams popularised volatility breakouts (price expanding from the open by a fraction of yesterday's range signals direction for the day) and short-term oscillators.

**Rules:**
- **Volatility breakout:** Buy at today's open plus *k* times yesterday's range (high minus low), with *k* typically between 0.25 and 0.60 (he optimises it per market). Short at open minus *k* times the range. **Exit:** at the next day's open (or at the close), or with a protective stop below the open.
- **Williams %R:** %R = (highest high over 14 bars minus close) / (highest high minus lowest low over 14 bars) times -100. Below -80 is oversold, above -20 is overbought. Used as a filter, not alone.
- **Oops pattern:** price gaps below yesterday's low, then buy when price trades back up to yesterday's low. The mirror image applies to gaps up.
- **Smash Day:** a bar closing in the bottom/top of its range after an extreme move sets up a reversal.

**Timeframe.** Daily (also intraday). **Best regime.** Volatility breakout works in trending, expanding-vol conditions. Oops works in gap-prone sideways markets.
**Evidence: B/C.** Williams won the 1987 Robbins World Cup of Futures Trading with an extraordinary return (about 11,000% from $10,000, widely reported **(unverified recall)**). That is a documented but single-contest result. The volatility-breakout rule has been tested widely in crypto and futures by quant bloggers with positive but regime-dependent results.
**Failure modes.** *k* is curve-fit; gap opens blow through entries in Indian stocks; costs matter when the exit is after one day.
**App implementation.** Daily OHLC plus (for execution) an intraday trigger price. Pre-compute the trigger levels at the end of the day and send them as alerts for the next morning.

### 2.8 Larry Connors: RSI(2), Double 7s, ConnorsRSI

**Sources.** Connors & Alvarez, *Short Term Trading Strategies That Work* (2008); Connors, *High Probability ETF Trading* (2009); the ConnorsRSI definition in Connors Research materials.

**RSI(2) rules (verified via StockCharts ChartSchool):**
- **Long setup:** close above the 200-day SMA, and RSI(2) falls to 5 or below (Connors found lower thresholds gave higher average returns than 10).
- **Entry:** at the close (or the next open).
- **Exit:** close above the 5-day SMA.
- **Short** (mirror): close below the 200-day SMA, RSI(2) at 95 or above, exit when below the 5-day SMA.
- **No stop:** Connors found stops *hurt* performance in this system for stocks and indices. (Practitioners add a catastrophic or time stop.)

**Double 7s:** in an uptrend (close above the 200-day SMA), buy when the close is a 7-day low; exit when the close is a 7-day high.

**ConnorsRSI:** (RSI(3) + RSI of the up/down streak length (2) + percent rank of 1-day ROC over 100 days) divided by 3. Buy below about 10, sell above about 90.

**Timeframe.** Daily; holds 2-7 days. **Best regime.** Dips inside long-term uptrends; low-to-moderate volatility. Fails in persistent crashes.
**Evidence: B.** The book reports tests on thousands of stocks and ETFs over roughly 1995-2007 showing positive expectancy with high win rates (often 70-80%+), with a small average gain per trade **(unverified recall on exact numbers)**. Independent bloggers (QuantifiedStrategies, etc.) show the edge has decayed since about 2008 on US large caps, and that returns per trade of roughly 0.5-1% are thin relative to costs. Academic support: short-term reversal (Jegadeesh 1990; Lehmann 1990).
**Failure modes.**
- Catching a falling knife in a bear market: the 200-day filter helps but does not eliminate this.
- Small per-trade gain versus the ~0.25% Indian delivery round-trip cost.
- Crowded.
- Without stops, individual-stock tail risk (fraud, results shock).
**App implementation.** Daily OHLCV. RSI(2) (Wilder smoothing), SMA(200), SMA(5). Scan the Nifty 50/100 universe, where reversal is more reliable. Add an exclusion: earnings within 3 days. Add a stop at 2 ATR as risk control even though Connors does not.

### 2.9 Kristjan Kullamägi (Qullamaggie): Breakouts, Episodic Pivots, Parabolic Shorts

**Sources.** His blog (qullamaggie.com / "Qullamaggie's blog" posts), his interview in Jack Schwager's *Unknown Market Wizards* (2020), and public posts/videos.

**Description.** A momentum swing trader who trades the best-performing stocks through three setups.

**Rules (as publicly described; his exact numbers vary by post):**
- **Universe:** the top 1-2% of performers over 1, 3 and 6 months. Require an ADR (average daily range) of at least about 4-5% and decent dollar volume.
- **Breakout setup:**
  1. A big prior move (30-100%+ in 1-3 months).
  2. A tight consolidation lasting several days to weeks, with higher lows, along the rising 10- and 20-day MAs.
  3. **Entry:** buy the break of the opening-range high or the consolidation high intraday.
  4. **Stop:** the low of the day (or below the consolidation), typically capped to about 1 ATR.
  5. **Exit:** sell about a third to a half after 3-5 days, move the stop to breakeven, and trail the remainder with a close below the 10-day (or 20-day) MA.
- **Episodic Pivot (EP):** a stock that was flat/neglected gaps up on a major catalyst (earnings, news) on very high volume. Buy the opening-range high of the gap day; stop at the low of day. Best EPs show large earnings/sales surprises.
- **Parabolic short:** a stock that has gone up several days in a row, by extreme amounts (for example 50-100%+ in a few days for mid caps), is shorted on the first sign of failure (for example VWAP failure or the first red day). Quick profit-taking at the 10-day MA. (Not suitable for Indian retail. No overnight shorts.)
- **Sizing:** risk about 0.25-1% of equity per trade, with positions being large relative to the account when stops are tight.

**Timeframe.** Daily scan, intraday entry. Holds days to weeks. **Best regime.** Bull markets with strong momentum leadership. He reduces size or stops trading when breakouts fail.
**Evidence: C.** Self-reported turning a few thousand dollars into tens of millions, discussed in *Unknown Market Wizards*. No independent audit that I could verify. Strong theoretical backing in momentum research and in post-earnings-announcement drift (Ball & Brown 1968; Bernard & Thomas 1989).
**Failure modes.** Few breakouts work (many small losses, a few big wins), requires fast intraday execution and screen time, bad in momentum crashes (Daniel & Moskowitz 2016), and Indian circuit limits break "low of day" stops.
**App implementation.** Daily OHLCV. Rank by 1/3/6-month return, filter ADR(20) at or above 4%, then check for a tight range (for example the 10-day range at most 1.5 times ADR) above a rising 10/20 EMA. Alert on a close above the consolidation high or the opening-range high (if intraday data exists). EP: gap at or above 5-10% with volume at least 3-5 times average, preceded by 60+ days of low-volatility range. This works on daily bars alone for a next-day watchlist.

### 2.10 Pradeep Bonde (Stockbee): Momentum Bursts, Anticipation, Episodic Pivots

**Sources.** Stockbee blog and "Stockbee Market Monitor"; his trainings. Bonde coined the "Episodic Pivot" term used above.

**Description.** Capture the large, short move (a "momentum burst") of 8-40% over 3-5 days that usually starts with a 4%+ range-expansion day. "Anticipation" means buying stocks setting up for that burst *before* it happens (tight ranges, low-volume pullbacks).

**Rules (from Stockbee public scan definitions):**
- **4% breakout scan:** today's close is at least 4% above yesterday's close, volume higher than yesterday's volume, volume above a minimum (for example 100,000 shares), and the stock was quiet before (a narrow range for the past several days). Enter as the stock breaks out, with the stop at the low of day (or below the prior consolidation).
- **Hold:** 3-5 days; exit into strength, or on a close below the 10-day MA.
- **Other Stockbee scans I recall:** the "9M" scan (volume above 9 million and higher than yesterday); the "20% in 5 days" scan; the "range expansion" scan. **(Details are unverified recall; check his blog before implementing.)**
- **Anticipation:** buy ahead of the breakout from a narrow-range, low-volume area under resistance.

**Timeframe.** Daily; holds 3-5 days. **Best regime.** Strong market with positive breadth. **Evidence: C.** Bonde's claims are anecdotal; a few independent traders have shared backtests but I found no peer-reviewed or audited work. The idea overlaps with documented short-term momentum in small caps (and with price/volume breakout studies).
**Failure modes.** Many 4% days are one-day events and reverse; small and illiquid stocks (operator names in India); circuit-limited upside means you can't exit an upper-circuit stock.
**App implementation.** Daily OHLCV. Boolean scan on C/C1 ≥ 1.04, V > V1, V ≥ liquidity floor, plus a narrow range over the prior 5-10 days. Add Indian filters (exclude circuit-band 5%/10% names; require turnover of at least ₹5 crore).

### 2.11 Jesse Livermore: Pivotal points

**Sources.** Edwin Lefèvre, *Reminiscences of a Stock Operator* (1923, a fictionalised account); Livermore's own *How to Trade in Stocks* (1940).

**Description.** Livermore bought when a stock broke out of a congestion area through a **pivotal point**, the price at which the "line of least resistance" changes. He advised buying only when the market and the stock's group were both moving in the same direction, and adding only when the position was already in profit (pyramiding).

**Rules (as presented, not rigidly mechanical):**
- Enter at a pivotal point: a break of consolidation highs on increased volume.
- Test buy: buy a small part first, add if price confirms (about 20% steps).
- Cut losses fast: a loss limit of about 10% of price (in his book) or tighter.
- Never average down.

**Timeframe.** Daily to weekly. **Best regime.** Trending. **Evidence: C.** Livermore went bankrupt several times and died in 1940. His principles survive mostly in the writing of O'Neil, Minervini and Darvas, who each formalised the pivot idea. No mechanical backtest of "Livermore's method" is meaningful.
**Failure modes.** Over-trading; undisciplined sizing; the romanticised narrative.
**App implementation.** Covered by the breakout scanners in 2.1-2.5. No separate module needed.

### 2.12 Wyckoff method: accumulation and distribution, spring and upthrust

**Sources.** Richard Wyckoff's courses (1930s); Pruden, *The Three Skills of Top Trading* (2007); Hank Pruden and Roman Bogomazov on modern schematics; Tom Williams (VSA, see 3.6).

**Description.** Price moves in cycles of accumulation (smart money builds a position in a range after a decline), markup, distribution, and markdown. Phases A-E in the accumulation schematic:
- Phase A: selling climax (SC), automatic rally (AR), secondary test (ST).
- Phase B: range-bound, building cause.
- Phase C: the **spring**, a brief break below range support that fails and quickly recovers (shakeout).
- Phase D: sign of strength (SOS) breaking above resistance, then a last point of support (LPS) pullback.
- Phase E: markup.

**Rules (discretionary; this is the commonly taught form):**
- **Spring entry:** after a break below range support that closes back inside the range quickly (1-3 bars), on a low-volume test. Buy the confirmation (a close back above support, or a break of the spring bar's high).
- **Stop:** below the spring low.
- **Alternative entry:** buy the LPS after the SOS breakout.
- **Target:** based on a point-and-figure "count" (the width of the range projects the upside); this is subjective.
- **Upthrust (distribution):** a brief break above resistance that fails; the mirror image is a short setup.

**Timeframe.** Daily/weekly. **Best regime.** Transitions from range to trend. **Evidence: C/D.** There are no rigorous tests, because identifying phases is subjective. The spring is a form of **failed breakdown (bear trap)**, which has some quantitative support in the Turtle Soup-style literature (see 2.6), but I found no strong published numbers.
**Failure modes.** Subjectivity (two analysts draw different phases), a spring that is not a spring but the start of a real decline.
**App implementation.** Only the spring/upthrust can be coded: detect a range (for example 30-60 days with a height of at most 15-20%), a close below the range low by at most about 3% followed within 3 bars by a close back inside, with a volume contraction. Treat as a low-priority alert.

### 2.13 Al Brooks: Price action

**Sources.** *Trading Price Action: Trends / Trading Ranges / Reversals* (2012); *Reading Price Charts Bar by Bar* (2009).

**Description.** Bar-by-bar reading of context (trend, range, reversal), using concepts like "always-in" direction, signal bars and entry bars, with-trend pullback entries ("H1/H2" in an uptrend, "L1/L2" in a downtrend, where an H2 is the second attempt to resume the trend after a pullback), measured moves, and failed-breakout (trap) reversals. Mostly applied to 5-minute index futures.

**Rules.** Brooks stresses discretionary context over fixed rules. The mechanical core: in a bull trend, buy the break above the high of the second bar-leg of a pullback (H2) with a stop under the pullback low and a target at a measured move or a prior high.
**Timeframe.** Intraday 5-min (also daily). **Best regime.** Clear trend days and clean ranges.
**Evidence: C.** Self-reported; the underlying method is discretionary and untested. **Failure modes.** Very high skill requirement; cannot be turned into a clean scanner without losing what makes it work. **App implementation.** H2/L2 pullback detection on 5-min bars is codable; value is doubtful. Low priority.

### 2.14 Tom DeMark: TD Sequential, TD Setup/Countdown, TD Lines

**Sources.** DeMark, *The New Science of Technical Analysis* (1994); *DeMark Indicators* (Perl, 2008).

**TD Sequential rules (standard published version):**
- **Buy Setup:** 9 consecutive closes, each below the close 4 bars earlier. (A Sell Setup is 9 closes each above the close 4 bars earlier.) A "perfected" setup requires the low of bar 8 or 9 to be below the lows of bars 6 and 7.
- **Countdown:** after a completed setup, count 13 bars in which the close is at or below the low 2 bars earlier (bars need not be consecutive). The 13th signals exhaustion and a possible low.
- **Stop:** below the lowest low of the setup/countdown (TD Risk Level).
- **TD Lines (TD Points):** trendlines drawn through specific highs/lows.

**Timeframe.** Daily, weekly, intraday. **Best regime.** Exhaustion at the end of extended moves. **Evidence: C/D.** Used widely by institutions (Bloomberg has it built in), but independent tests are mixed. Some show a weak mean-reversion edge on the 9 count on indices, and counter-trend entries often hit stops in strong trends. I found no peer-reviewed validation.
**Failure modes.** Counter-trend; a "9" in a strong trend can keep going (extended setups); complexity.
**App implementation.** Daily OHLC. The setup count is trivial to code. Use as an *exit/caution flag* (setup 9 on the upside means taking profits) rather than an entry system.

### 2.15 Andreas Clenow: Momentum ranking

**Sources.** *Stocks on the Move* (2015); *Following the Trend* (2012); the author's public blog and code (Clenow's momentum score is widely implemented).

**Rules (stocks):**
- **Universe:** index members (S&P 500 in the book). Equivalent in India: Nifty 100/200/500.
- **Momentum score:** annualised exponential-regression slope of the log price over the last 90 days, multiplied by the R-squared of the regression (this rewards smooth trends, penalises jumpy ones).
- **Eligibility filters:** price above the 100-day moving average; no single-day move above 15% in the last 90 days; (optionally) index above its 200-day MA to allow new buys.
- **Sizing:** risk parity, position size = account value times 0.001 (10 basis points) divided by ATR(20). Cap at about 10% of the portfolio.
- **Rebalancing:** rank weekly; buy the top 20% of ranked stocks; replace holdings that fall out of the top 20%, drop below the 100-day MA, or gap more than 15%. Resize about every 2 weeks.
- **Market filter:** no new buys when the S&P 500 is below its 200-day MA.

**Timeframe.** Weekly rebalance; holds weeks to months. **Best regime.** Bull markets; the filter reduces exposure in bears.
**Evidence: B (with A-grade support for momentum itself).** The book shows backtests over roughly 2001-2014 with returns well above the index and drawdowns controlled by the filter **(unverified recall on exact numbers)**. Academic basis is Jegadeesh & Titman (1993) and a vast follow-up literature. The Indian equivalent has index evidence: Nifty200 Momentum 30 (Section 6.5).
**Failure modes.** Momentum crashes after market reversals (Daniel & Moskowitz 2016); heavy turnover; a "smooth trend" stock can still gap on news.
**App implementation.** Daily close for the universe. Per stock: 90-bar regression on ln(price), annualised slope = exp(slope times 252) minus 1, times R². Rank, filter, size by ATR. Fully mechanisable; this is the best fit for a "ranked watchlist" feature in SwingEdge.

### 2.16 Gary Antonacci: Dual momentum

**Source.** *Dual Momentum Investing* (2014); "Absolute Momentum: A Simple Rule-Based Strategy and Universal Trend-Following Overlay" (SSRN, 2012).

**Rules (Global Equities Momentum, GEM):**
- Once a month, compare the trailing 12-month return of US stocks (S&P 500) and non-US stocks (MSCI ACWI ex-US). **Relative momentum** picks the stronger.
- **Absolute momentum:** if the winner's 12-month return beats Treasury bills, hold it for the next month. Otherwise hold aggregate bonds.
- Rebalance monthly. Very low turnover.

**Timeframe.** Monthly. **Best regime.** All; the absolute filter is meant to reduce bear-market losses.
**Evidence: A/B.** Antonacci reports (1974-2013) about 17% annual return with about a 22% maximum drawdown versus higher drawdowns for buy-and-hold **(unverified recall; these are his figures from the book)**. Supported by time-series momentum research (Moskowitz, Ooi & Pedersen 2012) and Faber (2007).
**Failure modes.** Whipsaw around the threshold, tax drag, a small number of signals so one bad year matters.
**India adaptation.** Compare Nifty 50/Nifty Next 50/Midcap 150 ETFs, with gold ETF or liquid fund as the safe asset. Test it yourself, because no Indian GEM test is verified here. **App implementation.** Monthly total-return series. Trivial. Useful as a "what to do with idle capital" module.

### 2.17 Indian traders with public methods (only where verifiable)

| Name | What is publicly documented | Mechanical? | Evidence |
|---|---|---|---|
| **Vijay Kedia**: SMILE | Stated in many interviews: the acronym is explained as **S**mall in size, **M**edium in experience, **L**arge in aspiration, **E**xtra-large in market potential (the exact letter mapping and any other Kedia frameworks are **unverified recall**). He holds for years and avoids macro prediction. | No: this is a business-quality investing philosophy. | C: his track record is widely reported but is not independently audited. |
| **Rakesh Jhunjhunwala** | Public principles from interviews: trade the trend with conviction, buy growth at reasonable valuations, patience, and respect the market. He made big trades in Titan and Lupin. | No. | C. |
| **Zerodha Varsity** (Zerodha, Nithin Kamath) | The most comprehensive free Indian educational resource: modules on technical analysis, options theory, strategies, and risk management. Good for definitions (Greeks, payoffs, margins). Not a source of an edge. | Definitions, payoffs. | N/A (education, not a track record). |
| **9:20 AM short straddle** (popular in India algo and retail communities, widely backtested on TradingView/Streak/Opstra) | Sell the ATM Call and Put on Nifty/BankNifty at about 9:20, with a stop-loss of about 25-30% of each leg, and exit by around 15:15 or at expiry. Many YouTube backtests show positive pre-cost results. | Yes. | C/D: results vary by period and by assumption on slippage and spreads. SEBI's own data shows option-sellers have fewer losers but huge tail losses (Section 11). |
| **Expiry-day option selling with adjustments** (Indian YouTubers, for example the "Iron condor with adjustment" playlists) | Variants of straddle/strangle/condor with rolling adjustments when delta or premium rises. | Partly. | C/D: mostly marketing; survivors tell the story. |
| **Toby Crabel's NR7 / ORB** (applied by Indian Bank Nifty traders) | See Section 3.3 and 3.7. Crabel is American; Indian use is by adoption. | Yes. | B (Crabel's book; Zarattini et al. for ORB). |

I did not find a verifiable, audited Indian trader's mechanical rulebook. Indian "gurus" mostly sell courses. Treat any specific win-rate claim as marketing unless there is a broker-verified P&L.

---
## 3. Setups, chart methods and concepts

Format per item: **Rules** | **TF** (timeframe) | **Regime** | **Evidence** | **Failure** | **App**.

### 3.1 Breakouts (Donchian / 52-week-high / base breakouts)

A breakout buys when price exceeds the highest high of the last N bars (N = 20, 55, 100, 250). Description: the simplest trend-following entry. It is the common core of Turtles, Darvas, O'Neil, Minervini and Kullamägi.
- **Rules.** Entry: close (or intraday trade) above the N-day high. Filter: close above the 200-day SMA, volume at least 1.4 times the 50-day average, and the market gate on. Stop: the lower of 2 ATR(14) below entry or the base low. Exit: close below the N/2-day low (Turtle: 10/20-day low), or trail 3 ATR (Chandelier).
- **TF.** Daily; holds weeks to months.
- **Regime.** Trending bull markets; avoid choppy ranges (breadth below 40% of stocks above their 50-day).
- **Evidence: A/B.** George & Hwang (2004), "The 52-Week High and Momentum Investing", *J. Finance*: stocks near their 52-week high outperform. Trend-following evidence in Section 2.5. Win rate is only 35-45%, so edge comes from skew.
- **Failure.** False breakouts in sideways markets (about half or more of breakouts fail within a few days), gap entries in India, late entries in extended stocks.
- **App.** Daily OHLCV: rolling max, volume ratio, ATR, 200-day SMA, market gate flag. A "distance from the breakout level" metric (reject entries more than about 5% above the pivot, per O'Neil).

### 3.2 Pullbacks to the 10 / 20 / 50 EMA

Buy a leader on a controlled pullback into a rising moving average rather than at the high. Description: lower risk because the stop is close.
- **Rules.** Trend: close above the rising 50-day EMA and 50-day above 200-day (Trend Template pass). Setup: price pulls back to within 0.5 ATR of the 10 EMA (strong, fast trends), 20 EMA (normal), or 50 EMA (deeper) on declining volume. Entry: the day price closes above the prior day's high (an up-bar), or a break of the pullback bar's high (Raschke Holy Grail). Stop: below the pullback low or 1-1.5 ATR below the EMA. Exit: the prior swing high, then trail with the 10 EMA.
- **TF.** Daily; holds 3 days to 3 weeks.
- **Regime.** Orderly uptrends. Fails when the market corrects and the "pullback" is the start of a trend break.
- **Evidence: C (B for the Raschke Holy Grail logic).** No clean academic test of "10/20/50 EMA pullback"; supported only indirectly by time-series momentum and short-term reversal inside trends (Section 4).
- **Failure.** The pullback becomes a trend change; the choice among 10/20/50 is arbitrary; a rising MA in a late-stage trend.
- **App.** Daily OHLCV: EMA(10/20/50), ATR, volume contraction ratio (the last 3 days' volume below the 20-day average). Output a pullback watchlist with trigger price and stop distance.

### 3.3 NR4 / NR7 and inside bars

**NR7:** the day with the narrowest range (high minus low) of the last 7 days; **NR4:** narrowest of the last 4. An **inside bar** has a high below and a low above the prior bar's. Source: Toby Crabel, *Day Trading with Short Term Price Patterns and Opening Range Breakout* (1990). Premise: low volatility is followed by range expansion.
- **Rules.** After an NR7, place a buy-stop above its high and a sell-stop below its low for the next session (or only in the direction of the trend). Stop: the other side of the NR7 bar (or the midpoint). Exit: at the close, at a 1-2 R target, or trail. Stocks with NR7 plus an inside bar ("NR7ID") are the strongest variant per Crabel.
- **TF.** Daily; also applied to Bank Nifty intraday (Indian traders). Holds 1-3 days.
- **Regime.** Trend or pre-event compression; fails in dead markets.
- **Evidence: B/C.** Crabel's book contains his own tests (futures, 1980s); independent replication is limited and edge has likely decayed. Treat as a trigger refinement for breakouts, not a standalone system.
- **Failure.** Direction is a coin toss without a trend filter; a small range means wide relative costs; many NR7s are noise.
- **App.** Daily OHLC only. Boolean for NR7/NR4/inside bar, then a trend filter. Cheap to build; use only combined with 3.1.

### 3.4 Flags, pennants, cup and handle, VCP

These are continuation patterns after a strong move (the "pole"): a flag is a short, shallow, downward-drifting channel; a pennant is a converging triangle; cup and handle and VCP are described in 2.1-2.2.
- **Rules.** Pole: a gain of at least 20-30% within about 10-20 days. Flag: 5-15 days, retracement no more than 38% of the pole (shallower is better), volume contracting. Entry: close above the flag's upper boundary on volume expansion. Stop: below the flag low. Target: measured move (the pole's height added to the breakout), or trail.
- **TF.** Daily. **Regime.** Strong uptrends.
- **Evidence: B/C.** Lo, Mamaysky & Wang (2000), "Foundations of Technical Analysis", *J. Finance*, found that some patterns (for example head-and-shoulders, double bottoms) carry incremental information on US stocks via kernel-based pattern detection; the effect is modest. Bulkowski's *Encyclopedia of Chart Patterns* (2005) reports pattern statistics (for example flags with high success rates) drawn from many US charts, with survivorship and hindsight caveats **(unverified recall on specific figures)**.
- **Failure.** Pattern subjectivity, look-ahead in detection, failure when the market turns.
- **App.** Pole detection (rate-of-change over 10-20 bars), flag regression channel slope and tightness, a volume ratio. Treat all of it as a variant of Qullamaggie's/Minervini's consolidation scan.

### 3.5 Gaps (gap-and-go, gap fill)

- **Gap-and-go:** a gap up (3-10%+) on high relative volume with a catalyst; buy the opening-range high or first pullback. It is the "episodic pivot" in 2.9. **Gap fill:** fade a small gap back to the prior close. Index futures (Nifty/Bank Nifty) fill small gaps frequently; large gaps with news often don't.
- **Rules.** Gap-and-go: gap at or above 5%, volume at least 3 times the average, and a flat base before; entry on the opening-range (5-30 min) high; stop at the day's low. Gap fill: gap of 0.3-1% against the prior close, entry at the open or the first 15-minute confirmation, target the previous close, stop at 1 times the gap size beyond.
- **TF.** Intraday to a few days. **Regime.** Gap-and-go works in momentum markets; gap-fill in range-bound markets with low volatility.
- **Evidence: B/C.** Lou, Polk & Skouras (2019), "A tug of war: Overnight versus intraday expected returns", *J. Financial Economics*: momentum profits accrue overnight, so the gap is where much return is earned; a retail swing trader who enters after the gap gives some of it up. The claim that "most small gaps fill" is widely repeated but I did not verify Indian statistics **(treat as unverified; test on NSE data)**.
- **Failure.** Gap-up traps; fills that take several days; slippage at the open; circuit stocks.
- **App.** Needs daily O/H/L/C/V for the gap-and-go watchlist (the next-morning plan); a live opening-range tracker needs intraday data. Use the EOD-built EP list for swing entries.

### 3.6 Volume: pocket pivots, VSA, delivery percentage

**Pocket pivot** (Gil Morales & Chris Kacher, *Trade Like an O'Neil Disciple*, 2010): a buy signal inside a base or just under it, before the formal breakout. Rules: an up day whose volume exceeds the largest down-day volume of the previous 10 sessions; price at or just above the 10-day (or 50-day) MA, and not extended (within about 5-10% of the base low-risk area). Stop: below the 10-day MA or the recent low. Evidence: C. Failure: signals in weak markets; a single volume spike from a block deal.

**VSA (volume spread analysis)** (Tom Williams, *Master the Markets*; derived from Wyckoff): reads the relation of volume, bar spread and close. Examples: "no demand" bar (up bar, narrow spread, volume below the previous two bars) shows a weak rally; "stopping volume" (a very high-volume down bar closing off the lows) shows absorption. Evidence: C/D (subjective, no systematic test found).

**Delivery percentage (India-specific).** NSE publishes delivery quantity per stock daily. A rising price with a high delivery percentage (relative to the stock's own norm) is read as genuine accumulation. Evidence: D/C. I found no robust study; use only as a secondary confirmation and test it in our own data.

**On-balance volume, accumulation/distribution, up/down volume ratio** are the classic aggregations (Granville 1963; Chaikin). Evidence is weak to modest. **App.** Daily OHLCV plus delivery quantity from the bhavcopy. Pocket pivot is a simple boolean; implement it.

### 3.7 Opening range breakout (ORB)

First described by Crabel (1990). Define the opening range as the high and low of the first 5, 15, 30 or 60 minutes.
- **Rules.** Long above the range high, short below the range low (if allowed). Stop: the opposite side of the range (or midpoint), or about 0.5-1 ATR. Exit: end-of-day, or a target at 1-2 times the range, or a trailing stop. Filter: "stocks in play" (relative volume at least 1.0-1.5 times, a catalyst or gap).
- **TF.** Intraday (5/15/30-min). **Regime.** Trend days and event-driven days; poor in range days.
- **Evidence: B.** Zarattini, Aziz & Barbon (2024), "Can Day Trading Really Be Profitable? Evidence of Sustainable Long-term Profitability from Opening Range Breakout (ORB) Strategies on US Stocks" (SSRN): reports a very large cumulative net return for the 5-minute ORB on high-relative-volume stocks over 2016-2023 versus the index **(unverified recall of the figures; verify before citing)**. It is an SSRN working paper by practitioners, not peer-reviewed, US-only, and sensitive to slippage assumptions.
- **Failure.** False breaks, whipsaw on index options (premium decay), costs and slippage, and the SEBI statistic (Section 11) that most Indian intraday traders lose.
- **Indian application.** Bank Nifty/Nifty 15-minute ORB is popular. For an Indian retail account, the instrument matters: trading the futures needs large capital; buying ATM options adds theta and spread costs.
- **App.** Needs 1/5-minute intraday data feed. Alert only; tag as "advanced/optional".

### 3.8 VWAP strategies

VWAP = cumulative (price times volume) / cumulative volume since the open. Used as the intraday "fair value".
- **Rules (trend version).** Long while price is above VWAP, short while below, entering on a pullback to VWAP and using a close back through VWAP as the stop and exit. **(Mean-reversion version).** Fade extremes more than 1.5-2 standard deviations from VWAP. Institutional use: execution benchmark.
- **TF.** Intraday. **Regime.** Trend version in trend days; fade in range days.
- **Evidence: B.** Zarattini & Aziz (2023), "Volume Weighted Average Price (VWAP): The Holy Grail for Day Trading Systems" (SSRN): long above/short below VWAP on QQQ over 2018-2023 shows strong cumulative returns versus buy-and-hold **(unverified recall of numbers)**. Same caveats as ORB.
- **Failure.** Choppy days cause repeated stop-outs; high trading frequency means costs dominate.
- **App.** Intraday data with volume. Optional.

---

## 4. Mean reversion

### 4.1 RSI(2) and Connors family
See 2.8 for exact rules and the evidence summary. Rank: use inside uptrends in liquid large caps, long only.

### 4.2 Bollinger Bands and the squeeze

**Bands:** 20-day SMA ± 2 standard deviations (John Bollinger, *Bollinger on Bollinger Bands*, 2001). %b = (close minus lower band)/(upper minus lower). BandWidth = (upper minus lower)/middle.
- **Mean-reversion rules.** In an uptrend (above the 200-day SMA), buy a close below the lower band (%b below 0) and exit at the middle band (20-day SMA). Stop: 2 ATR or a time stop of 5-7 days.
- **Squeeze (breakout) rules.** BandWidth at a 6-month low signals compression. Entry: a close outside the band in the direction of the trend (Bollinger's own advice: the band touch is not a signal by itself). John Carter's TTM Squeeze: Bollinger Bands inside Keltner Channels (20, 1.5 ATR) signals compression, entry on release with momentum direction.
- **TF.** Daily. **Regime.** Mean reversion: range or uptrend; breakouts: after compression.
- **Evidence: C/B.** The band mean-reversion variants backtest roughly flat to slightly positive on indices in independent blog tests; the effect is small once costs are included. Bollinger himself states that bands are not standalone signals. Academic: Brock et al. (1992) tested trading-range breaks, with mild support.
- **Failure.** "Walking the band" in strong trends (a lower-band close followed by a fall); squeeze fakeouts.
- **App.** Daily OHLCV; boolean for %b and BandWidth percentile; squeeze needs ATR and the Keltner width.

### 4.3 Short-term reversal (the academic basis)
Jegadeesh (1990) and Lehmann (1990): the previous week's/month's losers outperform winners over the next week/month, but the effect is mostly in less-liquid stocks and is largely consumed by costs. For liquid Indian large caps a ₹1-lakh trader should expect small gains per trade (below 1%) versus about 0.25% round-trip cost.

---

## 5. Relative strength and sector rotation

### 5.1 Relative strength (RS)
- **Rules.** Compute the stock's return over 3/6/9/12 months (a common weighting is 40/20/20/20 for 3, 6, 9, 12; this is IBD-like and varies by source). Convert to a percentile rank across the universe (the "RS rating"). Prefer stocks with an RS rating above 80-90. Alternatively Levy's ratio: price divided by its 27-week average (Robert Levy 1967). The **RS line** = stock price divided by index price; new RS-line highs before the price makes a new high are an early strength signal (O'Neil/Minervini "blue dot").
- **TF.** Daily/weekly. **Regime.** Bull markets; it fails at the start of a new bull when the old leaders lag (momentum crash).
- **Evidence: A.** Jegadeesh & Titman (1993), "Returns to Buying Winners and Selling Losers", *J. Finance*; Levy (1967); India: Ansari & Khan (2012), 1995-2006 sample, report strong momentum profits not explained by CAPM/Fama-French. The Nifty200 Momentum 30 factsheet gives practical numbers (6.5).
- **Failure.** Momentum crashes (Daniel & Moskowitz 2016, "Momentum Crashes", *JFE*) when the market rebounds sharply after a decline; crowding.
- **App.** Daily closes for the whole universe. Cheap and the most robust feature in the compendium. Use it as a first-stage ranking for all other scanners.

### 5.2 Sector/industry rotation
- **Rules.** Rank sector indices (Nifty Bank, IT, Pharma, Auto, FMCG, Metal, Realty, Energy...) by 3-6-month return or RS vs Nifty 500. Only take stock setups in the top 3-4 sectors. Rotate when a sector's RS line breaks its 50-day average.
- **Evidence: A/B.** Moskowitz & Grinblatt (1999), "Do Industries Explain Momentum?", *J. Finance*: industry momentum is significant and explains much of stock momentum. "Business-cycle" rotation models (Stovall) have weak support. Industry momentum is the evidence-backed form.
- **Failure.** Sector index composition is concentrated (Nifty Bank is dominated by a few names); late rotation.
- **App.** Daily sector index closes (NSE sectoral indices), the RS ranking, a heat-map card.

---

## 6. Momentum and trend: the academic and index evidence

This section collects the evidence base behind items 2.1-2.5, 2.15, 2.16, 3.1 and 5.

| # | Evidence | Finding | Grade |
|---|---|---|---|
| 6.1 | Jegadeesh & Titman (1993), *J. Finance* | US stocks: buy 3-12-month winners, hold 3-12 months, earn about 1% per month on a long-short basis in 1965-89. | A |
| 6.2 | Moskowitz, Ooi & Pedersen (2012), *JFE* | Time-series momentum across 58 futures/currencies: positive in each. | A |
| 6.3 | Hurst, Ooi & Pedersen (2017) | A century of trend following (1880-2016) positive across eras. | A |
| 6.4 | Ansari & Khan (2012), *Managerial Finance* | Indian stocks 1995-2006: strong momentum profits; CAPM and Fama-French do not explain them; idiosyncratic risk is positively related to momentum. Exact formation/holding windows not checked (abstract only). | A (single study) |
| 6.5 | NSE **Nifty200 Momentum 30** (whitepaper, data to 27 Feb 2026, TRI, base 1 Apr 2005) | Selection: top 30 of the Nifty 200 by a volatility-normalised 6- and 12-month momentum score, semi-annual rebalance, weights tilt by score and free-float cap (5% cap). Annualised return since inception **19.19% versus 15.08%** for the Nifty 200; 15-year 18.86% versus 12.99%; 10-year 20.16% versus 15.74%; 5-year 18.15% versus 14.42%; 3-year 21.50% versus 17.53%; 1-year 18.18% versus 17.71%. **Maximum drawdown 67.7% versus 63.7%** (to Oct 2008). Return-to-risk was below the Nifty 200 over the last 7, 5, 3 and 1 years. | A (index-provider data; includes back-tested history before launch, so in-sample before about 2020) |
| 6.6 | George & Hwang (2004), *J. Finance* | The 52-week-high proximity explains much of momentum profit. | A |
| 6.7 | Daniel & Moskowitz (2016), *JFE*, "Momentum Crashes" | Momentum suffers severe losses in rebounds after bear markets. | A (failure mode) |
| 6.8 | Barroso & Santa-Clara (2015), *JFE*, "Momentum has its moments" | Scaling momentum exposure by its own realised volatility roughly doubles the Sharpe ratio. | A |
| 6.9 | Faber (2007), "A Quantitative Approach to Tactical Asset Allocation" | 10-month SMA timing on asset classes cut drawdowns. | B |
| 6.10 | Brock, Lakonishok & LeBaron (1992), *J. Finance*; Park & Irwin (2007) survey | Simple MA/trading-range rules showed predictive power for the DJIA 1897-1986; Park & Irwin's survey found about 56 of 95 modern studies positive, but data-snooping concerns remain. | A/B |

**What these say for a ₹1-lakh Indian trader.** (i) Momentum is the best-documented non-trivial edge, including for India. (ii) It is not free: the Nifty200 Momentum 30 had a 67.7% drawdown in 2008, and its risk-adjusted advantage has not held in recent windows. (iii) The index rebalances semi-annually and holds 30 names; an active swing version trades more often and so must beat higher costs, which is not proven by this evidence.

---

## 7. Market regime gates and breadth

**Why.** Almost every long-only momentum method fails in bear markets. A gate that turns the scanner off is the cheapest improvement available. Faber (2007), Brock et al. (1992) and the O'Neil "M" rule all support it.

| Gate | Exact rule | Evidence | Notes |
|---|---|---|---|
| Index trend | Nifty 500 (or Nifty 50) close above its 200-day SMA (or 10-month SMA on monthly closes). | A/B (Faber; Brock et al.) | Simple; late at turning points; whipsaws near the average. |
| Two-speed trend | 50-day SMA above the 200-day SMA, and 200-day rising. | B | Reduces whipsaw; slower. |
| Breadth: % of stocks above their 200-day | Gate on if above about 50% (thresholds are arbitrary; calibrate on our data). | C/B | Needs the full-universe daily data we already hold. |
| Breadth: % above 50-day | Below 20% is "washed out" (potential mean-reversion buys); above 80% is "extended". | C | Good for sizing up/down. |
| Advance/decline line, new highs minus new lows | A/D line making new highs confirms the trend; divergence warns. | C | Classic; no hard rule. |
| Zweig Breadth Thrust | The 10-day EMA of advancing/(advancing + declining) rises from below 0.40 to above 0.615 within 10 days. | B (rare signal, strong forward returns in US history; **unverified recall**) | Few signals, so low statistical power. |
| O'Neil follow-through day / distribution days | See 2.2. | C | Index-level; subjective in practice. |
| India VIX | A level above about 20-25 or a fast spike calls for smaller size. | C | Volatility regime is an input to sizing, not a precise gate. |
| FII/DII flows | Cash-market net FII flow as context. | D/C | Unreliable as a signal. |

**App.** One "market health" score (0-100) combining Nifty 500 vs 200-day, % of stocks above the 200-day and 50-day, the new highs/lows ratio, and India VIX percentile; scanners show "gate on/off" in the UI. This is a high-value feature.

---

## 8. Levels, indicators and chart systems (popular in India)

### 8.1 Support / resistance and supply-demand zones
- **Rules.** Levels from swing highs/lows, prior consolidations and round numbers. Zones: the base before a sharp move (rally-base-rally, drop-base-drop) form demand/supply zones; buy on the first revisit with a stop below the zone. This tradition is popular among Indian retail technicians.
- **Evidence: B/C.** Osler (2000/2003, NY Fed), on FX: published S/R levels predicted intraday trend interruptions better than chance. Lo et al. (2000) for patterns. Zone-based "supply and demand" trading has no independent academic tests.
- **Failure.** Many levels fit any chart; hindsight drawing. **App.** Detect pivots (fractals), cluster them (within 0.5 ATR), count touches, and give distance to the nearest level for use as a stop/target aid.

### 8.2 Fibonacci retracements and extensions
- **Rules.** Retracements of 38.2%, 50% and 61.8% (and 23.6%, 78.6%) of the prior swing; extensions 127.2% and 161.8%. Entry on a reversal signal at the level; stop beyond the next level.
- **Evidence: D/C.** Few rigorous tests; the ones I know show no consistent excess return beyond what ordinary pullback-depth would give, and swing-anchor choice is arbitrary. Treat as a visual tool; if the level coincides with a moving average or prior pivot, "confluence" may add a self-fulfilling effect, but this is unproven.
- **App.** Auto-draw from the last confirmed swing; do not generate signals from Fibonacci alone.

### 8.3 Ichimoku Kinko Hyo
- **Rules (Goichi Hosoda, 1969).** Tenkan-sen = midpoint of the 9-bar high/low; Kijun-sen = midpoint of the 26-bar; Senkou Span A = (Tenkan + Kijun)/2 plotted 26 bars ahead; Span B = midpoint of 52 bars plotted 26 ahead; Chikou = close plotted 26 back. Bullish: price above the cloud, Tenkan crossing above Kijun, Chikou above price. Stop: Kijun or the cloud's lower edge.
- **TF.** Daily/weekly. **Regime.** Trends. **Evidence: D/C.** Academic tests are mixed and mostly poor out of sample (several studies on stock and FX markets). **Failure.** Many lagging components; a tight sideways market is whipsawed. **App.** Computation is easy. As a visual chart overlay, yes; as a signal, no.

### 8.4 Supertrend (very popular in India)
- **Rules (Olivier Seban).** ATR(n) with multiplier m (defaults ATR 10, m 3; many Indian intraday users use 7/3 or 10/2). Upper band = (high+low)/2 + m×ATR; lower band = (high+low)/2 minus m×ATR. Bands only ratchet in the trend direction. Long when close crosses above the upper band; short when below the lower band. The stop and trailing exit is the band itself.
- **TF.** Any; popular on 15-min Bank Nifty and daily. **Regime.** Trending. **Evidence: C/D.** It is a trailing-ATR trend follower, so it shares the (positive) logic of trend following, but standalone backtests I know of show the usual trend-follower profile: many whipsaws, low win rate, and high costs on intraday timeframes **(unverified recall; run it yourself)**.
- **Failure.** Chop; late exits; heavy trading costs on short timeframes. **App.** Daily Supertrend as a trailing stop is reasonable (equivalent to a Chandelier). Not a standalone signal generator.

### 8.5 Pivot points and Central Pivot Range (CPR): the most popular in Indian intraday
- **Classic floor pivots.** PP = (H+L+C)/3 of the prior session; R1 = 2·PP − L; S1 = 2·PP − H; R2 = PP + (H−L); S2 = PP − (H−L); R3 = H + 2(PP−L); S3 = L − 2(H−PP).
- **CPR (popularised in India by Frank Ochoa's *Secrets of a Pivot Boss*, 2010, and Indian educators).** Pivot = (H+L+C)/3; BC = (H+L)/2; TC = 2×Pivot − BC. A **narrow CPR** (width under about 0.1-0.25% of price, thresholds vary) is said to predict a trending day; a wide CPR a range day. A CPR higher than the prior day's signals a bullish bias.
- **Camarilla pivots (Nick Stott).** H4/L4 are breakout levels, H3/L3 reversal levels; derived from the prior range: H3 = C + 0.275×(H−L), H4 = C + 0.55×(H−L) (L3/L4 mirrored).
- **TF.** Intraday; the levels come from the previous day (or week/month for swing). **Evidence: C.** Widely used by floor traders; the narrow-CPR claim lacks rigorous published tests, though it is just a volatility-compression idea (like NR7) and thus plausible. Pivots may work through self-fulfilling clustering of orders. **Failure.** Many levels offer many chances to rationalise; dead days.
- **App.** Compute daily/weekly/monthly pivots and the CPR width as a percent of price (percentile vs the last 60 days) for each stock/index. The data is trivial (EOD OHLC). Label it "context", not "signal".

### 8.6 Heikin-Ashi and Renko
- **Heikin-Ashi.** HA close = (O+H+L+C)/4; HA open = (previous HA open + previous HA close)/2; HA high/low = max/min of the high/low, HA open, HA close. Green candles without lower wicks indicate a strong trend. Rule: exit when HA candle colour flips (or a candle with a wick appears against you). **Renko.** A brick of fixed size (often ATR(14)) forms when price moves that much; the bricks ignore time. Entry/exit on colour change.
- **Evidence: D/C.** Both are smoothing transforms; any edge is the edge of trend-following with extra lag. The **critical backtesting trap**: HA and Renko prices are not tradable prices (HA open/close is not an actual trade price; Renko bricks hide intra-brick paths), so backtests on them overstate performance. **App.** Provide as a chart style; do not backtest signals on them without replaying on real prices.

---

## 9. Options concepts

**Capital reality.** At ₹1 lakh, you can buy index options (premium risk is capped) but you can't sell naked index options safely (margin is a multiple of ₹1 lakh), and defined-risk spreads still need margin and fees. Everything below is background; the SEBI data (Section 11) argue against trading this segment for most retail users.

| Concept | Definition / rule | Evidence | Notes |
|---|---|---|---|
| **OI analysis** | Price up + OI up = long buildup; price down + OI up = short buildup; price up + OI down = short covering; price down + OI down = long unwinding. Strike-wise: the highest Call OI strike acts as resistance, the highest Put OI strike as support. | C/D. Academic work exists on option volume/OI information (Pan & Poteshman 2006 on put-call volume predicting returns using signed, open-buy volume, not retail-visible OI). The retail "highest OI = wall" rule lacks independent tests in India. | Data: F&O bhavcopy OI (EOD) or live option chain. OI of sellers can be hedged, so OI does not reveal intent. |
| **PCR (put-call ratio)** | Total put OI / total call OI (or volume ratio). Common folklore: above about 1.3-1.5 oversold (contrarian bullish); below about 0.7 overbought. | C/D. Contrarian sentiment studies are mixed. | Thresholds vary by index and period. |
| **Max pain** | The strike at which total option-buyer payoff at expiry is minimum (option writers' gain is maximum). Claim: price gravitates there into expiry. | C. Ni, Pearson & Poteshman (2005), *JFE*, "Stock price clustering on option expiration dates", find stocks cluster at strikes near expiry (hedging/manipulation), but the finding does not validate max pain as a predictor for Nifty weekly expiries. | Do not present as a signal. |
| **IV rank / IV percentile** | IV rank = (current IV − 52-week low IV)/(52-week high − low). IV percentile = percent of days in the past year with IV below today. India VIX is the index-level measure. High IV rank favours sellers; low favours buyers. | A/B for the **variance risk premium** (implied vol exceeds realised vol on average; Carr & Wu 2009; Bakshi & Kapadia 2003, mostly US). The premium for Indian index options is not verified here. | The edge is real on average but comes with fat left tails, which is what SEBI's seller data show (huge average loss among losers). |
| **Short straddle / strangle** | Sell ATM (straddle) or OTM (strangle) call and put; profit if the underlying stays in range; loss unlimited. Adjustments: roll the untested side, add hedges, stop at 25-30% of premium. | C/D after costs. | See 11. |
| **Iron condor** | Short strangle plus long further-OTM wings. Max loss = wing width minus credit. | C. | Defined risk, but for retail the margin is still large and fills cost spreads on four legs. |
| **Credit spreads** (bull put / bear call) | Sell one option, buy a further-OTM one in the same expiry. | C. | Same. |
| **Long straddle / option buying** | Buy ATM call and put before a known event; wins only if the move exceeds the premium. | D: implied vol before events already prices the move. | SEBI: about 93% of FY26 traders were "only options buyers" and about 90% of them were loss-makers (Section 11). |
| **Expiry-day strategies** | Zero-DTE selling/buying, gamma scalping. | D for retail. | One report puts about 59% of index options turnover on expiry day (Open Magazine summary of the SEBI FY26 study; not confirmed in the SEBI PDF I fetched, which says only that trading is concentrated near expiry). SEBI's late-2024 measures (single weekly expiry per exchange, higher contract size, upfront premium collection, extra margin on expiry day; verify details) were aimed at exactly this behaviour. |

**App implementation.** Option chain snapshots (OI, change in OI, IV, volume) per strike. Compute PCR, max-OI strikes, IV rank (needs 1 year of IV/VIX history). Show as *context* panels with strong warnings; do not ship "OI signals" as buy/sell recommendations.

---

## 10. Position sizing and risk (the part with the strongest evidence of mattering)

| Tool | Exact rule | Notes / evidence |
|---|---|---|
| **R-multiples** (Van Tharp, *Trade Your Way to Financial Freedom*, 1999) | 1R = the initial risk per trade (entry minus stop times shares). Express every outcome in R. Expectancy = (win% × average win in R) − (loss% × average loss in R). SQN = √N × (mean R / stdev R). | Framework, not an edge (grade n/a). It forces honest stats and is how every serious trader reviews. |
| **Fixed-fractional sizing** | Shares = (equity × risk%) / (entry − stop). Typical risk 0.25-1% per trade (Minervini ~1.25%, Turtles 1% of N-volatility per unit). At ₹1 lakh and 1%, the risk is ₹1,000, so a 7% stop allows about ₹14,300 position size; with ATR stops, a ₹2,000 stock with ATR 40 and a 2-ATR stop (₹80, 4%) allows 12 shares. | Prevents ruin; at ₹1 lakh the minimum realistic risk is the binding constraint, since costs per trade are a larger fraction of the risk budget. |
| **Portfolio heat** | The sum of open risk (to stops) capped at about 5-6% of equity; max 5-6 positions; a sector cap (for example, 2 per sector). | Practitioner convention (C). |
| **Kelly criterion** | f* = p − (1 − p)/b, where p is the win probability and b is the win/loss payoff ratio (or f* = edge/odds). Optimises long-run growth but is brutally sensitive to estimation error; use **half-Kelly or less**. | Kelly 1956; Thorp (2006), "The Kelly Criterion in Blackjack, Sports Betting and the Stock Market". Grade A (math). With uncertain p and b, full Kelly overbets. |
| **ATR stops** | Stop = entry − k × ATR(14) (k = 2-3 for swing trades; Wilder, *New Concepts in Technical Trading Systems*, 1978). | Adapts to each stock's volatility; avoids stops inside normal noise. |
| **Chandelier exit** (Chuck LeBeau) | Trailing stop = highest high since entry (or 22-day) − 3 × ATR(22). | Reasonable default trailing exit. |
| **Trailing stops** | N-day low (Donchian 10/20), MA-based (close below the 10/20/50 EMA), Parabolic SAR, percent trail (for example 20-25%). | Trend-followers' exit style: wide trailing stops let winners run but give back 20-30% of peak gains. |
| **Time stop** | Exit if the stock does not move favourably in N days (for example 5-10). | Useful to free capital; common in Qullamaggie/Minervini practice. |
| **Anti-martingale scaling** | Add to winners (pyramid), never average down (Livermore, Turtles). | Practitioner consensus (C), consistent with trend following. |
| **Volatility-scaled exposure** | Reduce the overall exposure when realised volatility rises (Barroso & Santa-Clara 2015; India VIX). | A. |
| **Drawdown rules** | Cut risk per trade by half after a drawdown of 6-8%; stop trading for a week at 10%. | C, but behaviourally important. |

**Small-account note.** On ₹1 lakh with ~0.25% delivery costs, each round trip of a ₹15k position costs ~₹35-40 plus slippage. A strategy making 1% gross per trade nets about 0.7%, which is acceptable. A strategy making 0.3% gross per trade is a loser. Therefore, **prefer strategies with a larger average gain per trade (breakouts/trend, 5-10%+ winners) over high-frequency small-edge ones**.

---

## 11. What does NOT survive costs (and what SEBI's data say)

### 11.1 SEBI evidence (verified online unless marked)

| Study | Period | Finding |
|---|---|---|
| SEBI PR 37/2024 (23 Sep 2024), individual F&O traders | FY22-FY24 | **93%** of individual traders lost money; aggregate net losses **above ₹1.8 lakh crore** over three years. Secondary reports (Outlook Business) add: average loss of about ₹2 lakh per trader including costs; individuals paid about ₹50,000 crore in transaction costs (about 28% of the net loss; the ratio is the reporter's arithmetic, not SEBI's); only about 7.2% were profitable; about 1% earned over ₹1 lakh after costs. Proprietary traders made about ₹33,000 crore and FPIs about ₹28,000 crore in FY24 (before costs), with 96-97% of those profits from algorithmic trading. |
| SEBI FY25 study (reported Jul 2025, Business Standard) | FY25 | Over **91%** lost; aggregate net loss **₹1,05,603 crore** (up 41% from ₹74,812 crore in FY24), after transaction costs; about 96 lakh unique traders. (Open Magazine quotes a slightly different FY25 total, about ₹1.12 lakh crore, likely a revised base.) |
| SEBI FY25-FY26 study (Aug 2026, sample of 5,050 traders, top-15 brokers; authors call the results indicative and associational) | FY25, FY26 | **88.1%** of sampled traders were loss-makers in FY26 (91.1% in FY25). **About 93% of traders were "only options buyers"**, with about **90%** of them losing, an average loss of about ₹1.3 lakh per loss-maker, and a median return on capital of about **−114%**. **Option sellers**: only about 44% were loss-makers and their median return on capital was about +1%, but their average loss per loss-maker was about **₹51.7 lakh**, and as a group they still had a net loss (about ₹543 crore in FY26). Traders active on more than 100 days were 42% of traders, 94% of turnover and 87% of losses. Higher turnover relative to capital was linked to higher loss rates (turnover about 1,665 times portfolio value for small portfolios, 89.7% losers, versus about 8 times and 62.9% for large portfolios). Only 15.4% of trader-quarters were profitable. Of traders active in all five years of the FY22 cohort, **0.5%** were profitable every year and **65.6%** lost every year. |
| Open Magazine's summary of the same FY26 study (secondary) | FY26 | Reports aggregate net loss of ₹91,685 crore, average loss about ₹1.17 lakh, about ₹25,000 crore of transaction costs paid by individuals in FY26 (about ₹1 lakh crore over FY22-FY26), and about 59% of index-options turnover on expiry day. These specific figures did **not** appear in the SEBI PDF excerpt I could read, so treat them as unconfirmed. |
| SEBI intraday cash-segment study (Jul 2024; Business Standard) | FY23 | **Over 70%** of individual intraday traders lost money (average loss ₹5,371); about 80% of "very frequent" traders (more than 500 trades a year) were loss-makers; traders under 30 were 48% of intraday traders, with 76% of them losing; loss-makers traded more often than profit-makers. |
| SEBI Jan 2023 F&O study (as referenced in the Business Standard article; I did not open the original) | FY22 | About 90% of active F&O traders lost money (the original study's figure was about 89%, **unverified recall**). |

### 11.2 Strategies that do not survive realistic costs for a ₹1-lakh retail account

| Strategy | Why it fails here |
|---|---|
| Intraday scalping/day trading in stocks or index options | SEBI: 70%+ of intraday cash traders lose; the more they trade, the worse the outcome. Slippage and the 0.05-0.10% round trip consume small edges. Published edges (ORB/VWAP) are US-based, slippage-assumption-sensitive and not peer-reviewed. |
| Buying weekly index options (especially OTM/expiry day) | About 90% of option-only buyers lost, with a median return on capital of about −114% in FY26. Theta, wide spreads on cheap strikes, and an implied-vol premium that already prices the expected move. |
| Retail option selling (9:20 straddles, strangles, iron condors with "adjustments") | Selling has a lower loss frequency, but the seller's average loss when it loses is large (about ₹51.7 lakh in the SEBI sample), and the group still lost in aggregate. Margin needs exceed a ₹1 lakh account, so retail sellers are forced into thin, high-turnover trades where one tail event removes years of premium. Backtests shown on social media often ignore slippage, stop-run gaps, and the post-2024 margin/expiry rules. |
| Index futures trading | Contract size is too large for the account; one adverse gap exceeds the 1% risk budget by a multiple. |
| Indicator stacking (RSI + MACD + Supertrend + EMA crossovers) | Many parameters, no edge beyond trend following, certain overfitting. |
| Standalone Ichimoku, Fibonacci, Heikin-Ashi, Renko, Supertrend signals | No independent evidence of edge; HA/Renko backtests are on untradable prices. |
| High-frequency mean reversion with tiny gains per trade (below about 0.5%) | The 0.25% delivery round trip eats half or more. |
| Penny/operator/small-cap momentum chasing | Circuit-limited exits, ASM/GSM surveillance, operator dumps. |
| Tips-based or "guru" trades | No audited records; survivor narrative. |

### 11.3 What the SEBI numbers imply for product design

1. The most reliable "edge" for a retail user is **doing less**: fewer trades, longer holds, defined risk, and no options-seller leverage.
2. Persistence is bad: SEBI found that 90% of traders who lost for two consecutive years and kept trading lost again (the Open Magazine summary of the FY26 study; unconfirmed in the PDF I read) and only 0.5% were profitable every year in the FY22 cohort (SEBI PDF). Product features should surface a trader's own R-multiple stats, costs paid, and turnover relative to capital.
3. Show costs explicitly: STT, brokerage, slippage, and the cumulative cost drag per month.
4. Position the F&O module as high-risk, behind a warning gate, and default the app to equity delivery swing trading.

---

## 12. Validation protocol before any scanner ships as a "strategy"

The compendium's grades come from other people's tests. SwingEdge must run its own before putting a name on a signal.

1. **Data hygiene.** Use adjusted prices (splits, bonuses, rights, demergers). Include delisted/suspended stocks to limit survivorship bias (the Nifty 500 constituents change; use point-in-time membership). Respect circuit limits: a stock closing at upper circuit cannot be bought at the close.
2. **Realistic fills.** Use next-day open or the next 15-minute VWAP for EOD signals. Add slippage of 0.1-0.3% for liquid names and more for mid/small caps. Include the full delivery or intraday fee schedule.
3. **No look-ahead.** The 52-week high, RS rank and pattern must use data up to the signal bar only; pattern detectors that use future swing confirmation must lag by the confirmation period.
4. **In-sample / out-of-sample split.** Fit on a pre-2019 sample, test on 2019-2026 (it includes the 2020 crash, the 2021 rally, 2022 chop, and 2024-25 volatility). Walk-forward where parameters are tuned.
5. **Regime breakdown.** Report results separately for gate-on and gate-off periods; momentum strategies should show disaster in the off periods.
6. **Metrics.** Number of trades, win rate, average win/loss in R, expectancy, profit factor, max drawdown, Sharpe/Sortino, the longest drawdown period, results net of costs and gross. Report the confidence interval (bootstrap) on expectancy. Require at least 100-200 trades before believing anything.
7. **Multiple-testing discipline.** Count the number of variants tried; apply a deflated-Sharpe or at least a Bonferroni-style haircut. Prefer parameters in plateaus, not peaks.
8. **Capital realism.** Simulate a ₹1 lakh account: integer share counts, a minimum risk per trade, a maximum of 5-6 open positions, and cash drag.
9. **Live paper-trade** for 1-3 months before enabling alerts; record alert-to-fill differences.

### 12.1 Scanner architecture sketch

| Layer | Contents | Data |
|---|---|---|
| Universe | Nifty 500, minus ASM/GSM/T2T/ban/low-liquidity (average daily traded value below about ₹5 crore) | NSE bhavcopy, surveillance lists |
| Market gate | Nifty 500 vs 200-day SMA, breadth (% above 200/50-day), highs/lows, India VIX | Daily index and constituent closes |
| Ranker | RS rating (3/6/9/12-month), Clenow score, industry RS, 52-week-high distance | Daily closes |
| Setups | Trend Template; VCP/base breakout; EP/gap; Donchian 55; pullback to 10/20 EMA; RSI(2) dip; pocket pivot | Daily OHLCV (+ delivery quantity) |
| Risk module | ATR stop, share count at 0.5-1% risk, portfolio heat, cost estimate | Daily OHLCV and a fee model |
| Journal/analytics | R-multiples, expectancy, cost paid, turnover/capital | User trades |
| Optional | Intraday ORB/VWAP alerts, option chain context panels | Intraday ticks, option chain |

---

## 13. Ranked shortlist: top 10 for an Indian retail swing trader with about ₹1 lakh

Ranking criteria: (1) strength and independence of evidence, (2) viability with long-only delivery trades, (3) survival after Indian costs at small size, (4) mechanisability in an EOD scanner. "Evidence" cites the strongest source in this document. Ranks 1-4 are the ones I would build first.

| Rank | Strategy | Evidence | Why it ranks here | Key caveat |
|---|---|---|---|---|
| 1 | **Market regime gate** (Nifty 500 above 200-day SMA, breadth, VIX-aware sizing) | A/B: Faber 2007; Brock et al. 1992; Gunasekarage & Power (South Asia, unverified recall); O'Neil follow-through | Not a strategy but the largest risk reducer for every long-only method; trivial to compute. | Late at turns; whipsaw near the average; reduces return in V-shaped recoveries. |
| 2 | **Cross-sectional momentum / RS ranking** (Clenow-style score, 3-12 month RS, liquid Nifty 200/500) | A: Jegadeesh & Titman 1993; Ansari & Khan 2012 (India); NSE Nifty200 Momentum 30 (19.19% vs 15.08% since inception) | The best-documented non-trivial edge, with Indian data. Output is a ranked watchlist, so it also feeds the other setups. | Momentum crashes; the index had a 67.7% drawdown in 2008; risk-adjusted advantage has faded in recent windows. |
| 3 | **Trend Template plus Stage 2 filter** (Minervini 8 criteria; Weinstein 30-week MA) | B/C with academic backing for components (trend, 52-week high) | Cheap boolean screen; the combination of trend, RS and proximity to highs has convergent support. | Anecdotal as a system; passes many stocks; needs a market gate. |
| 4 | **Breakouts from tight bases / 52-week highs** (VCP pivot, O'Neil pivot, Donchian 55-day with ATR stop) | A/B: George & Hwang 2004; Turtles/Hurst et al. for trend following | Low win rate but large winners; per-trade gains big enough to beat 0.25% costs. | 50%+ of breakouts fail; requires disciplined stops and the gate. |
| 5 | **Earnings gap / Episodic pivot with drift** (Kullamägi/Bonde EP; post-earnings-announcement drift) | A (PEAD: Ball & Brown 1968; Bernard & Thomas 1989; US); C for the trader methods | Catalyst-driven moves with real information content; gap day is visible in EOD data so no intraday feed is needed for the watchlist. | Entering after the gap gives up the overnight move (Lou, Polk & Skouras 2019); Indian PEAD evidence not verified here; circuit limits. |
| 6 | **ATR-based sizing and trailing exits** (fixed-fractional 0.5-1%, 2 ATR stop, Chandelier, portfolio heat) | A/B (Kelly/Thorp math; Barroso & Santa-Clara 2015 on vol scaling; Turtles) | Does not generate returns but determines survival; the evidence that most retail traders lose through sizing and behaviour is strong (SEBI). | At ₹1 lakh, integer shares and costs limit precision. |
| 7 | **Sector/industry momentum filter** (top 3-4 Nifty sectoral indices by RS) | A: Moskowitz & Grinblatt 1999 | Industry momentum explains a large part of stock momentum; easy to compute. | Concentrated sector indices; late rotation. |
| 8 | **Pullback entries in leaders** (10/20 EMA; Raschke Holy Grail; pocket pivot) | C/B | Better risk/reward than buying extended breakouts; stops are tight. | No independent test; the pullback may be a trend change. |
| 9 | **RSI(2) / Double-7 dip buying in uptrends** (Connors) | B: Connors/Alvarez 2008; decay documented by independent tests | Rules are exact and tested on a very large sample; a 2-7 day hold fits swing trading. | Small gain per trade versus costs; edge has decayed; no stop in the original. Best on Nifty 50/100 stocks, long only. |
| 10 | **Dual momentum / trend-gated allocation for idle capital** (Nifty vs gold/liquid; Antonacci, Faber) | A/B: Antonacci 2014 (author's numbers), Faber 2007, Moskowitz et al. 2012 | Very low turnover; a sensible use of cash while the equity gate is off; the only strategy here with a small drawdown record. | No Indian-specific test verified here; ETF tracking error and taxes. |

**Not on the list, deliberately:** intraday ORB/VWAP (US evidence only, SEBI says most intraday traders lose), all option strategies (Section 11), Ichimoku/Fibonacci/Supertrend/HA/Renko standalone, TD Sequential as an entry, Wyckoff/Brooks (not codable), CPR (context only).

**Combined swing workflow suggested by the list:** gate on (1) → rank by RS (2) within strong sectors (7) → Trend Template (3) → entry via base breakout (4), earnings gap (5) or pullback (8) → size and stop per (6) → idle capital per (10), with RSI(2) dips (9) as an optional short-horizon add-on in the Nifty 100.

---

## 14. Master index of items (quick reference)

| Item | Section | Evidence | Codable on EOD data | Suited to ₹1 lakh long-only |
|---|---|---|---|---|
| Minervini Trend Template/SEPA/VCP | 2.1 | C (components A) | Yes (VCP partly) | Yes |
| O'Neil CAN SLIM, cup and handle | 2.2 | B/C | Yes (EPS optional) | Yes |
| Darvas boxes | 2.3 | C | Yes | Yes |
| Weinstein stage analysis | 2.4 | B/C | Yes (weekly) | Yes |
| Turtles/Donchian/N sizing | 2.5 | A/B (futures) | Yes | Yes (adapted) |
| Raschke Holy Grail/80-20/Turtle Soup | 2.6 | C/B | Yes | Partly |
| Williams volatility breakout/%R | 2.7 | B/C | Yes (needs intraday trigger) | Partly |
| Connors RSI(2)/Double 7s | 2.8 | B | Yes | Yes (thin per trade) |
| Kullamägi breakouts/EP/parabolic shorts | 2.9 | C | Yes (watchlist) | Long setups only |
| Bonde/Stockbee bursts | 2.10 | C | Yes | Yes (liquid names only) |
| Livermore pivots | 2.11 | C | via breakouts | Yes |
| Wyckoff | 2.12 | C/D | Partly | Marginal |
| Al Brooks | 2.13 | C | Intraday only | No |
| DeMark TD Sequential | 2.14 | C/D | Yes | Exit flag only |
| Clenow momentum | 2.15 | B (A basis) | Yes | Yes |
| Antonacci dual momentum | 2.16 | A/B | Yes (monthly) | Yes |
| Kedia/Jhunjhunwala/Indian options sellers | 2.17 | C/D | Mostly no | Investing philosophy only; sellers no |
| Breakouts | 3.1 | A/B | Yes | Yes |
| EMA pullbacks | 3.2 | C | Yes | Yes |
| NR4/NR7/inside bar | 3.3 | B/C | Yes | Trigger only |
| Flags/pennants | 3.4 | B/C | Partly | Yes |
| Gaps | 3.5 | B/C | Yes | Yes (EP) |
| Volume/pocket pivots/VSA/delivery % | 3.6 | C/D | Yes | Yes |
| ORB | 3.7 | B (US) | No (intraday) | Optional |
| VWAP | 3.8 | B (US) | No (intraday) | Optional |
| Bollinger/squeeze | 4.2 | C/B | Yes | Yes |
| RS and sector rotation | 5 | A | Yes | Yes |
| Regime/breadth | 7 | A/B | Yes | Yes |
| S/R, supply-demand, Fibonacci, Ichimoku, Supertrend, CPR, HA, Renko | 8 | C/D | Yes | Context only |
| OI/PCR/max pain/IV rank/spreads/expiry | 9 | C/D (VRP A in US) | Needs option chain | No (default off) |
| R-multiples/Kelly/ATR/trailing | 10 | A (math) | Yes | Yes (essential) |

---

## 15. Bibliography (primary sources named above)

Books: O'Neil, *How to Make Money in Stocks*; Minervini, *Trade Like a Stock Market Wizard* and *Think & Trade Like a Champion*; Darvas, *How I Made $2,000,000 in the Stock Market*; Weinstein, *Secrets for Profiting in Bull and Bear Markets*; Faith, *Way of the Turtle*; Covel, *The Complete TurtleTrader*; Connors & Raschke, *Street Smarts*; Connors & Alvarez, *Short Term Trading Strategies That Work*; Williams, *Long-Term Secrets to Short-Term Trading*; Crabel, *Day Trading with Short Term Price Patterns and Opening Range Breakout*; Schwager, *Market Wizards* series incl. *Unknown Market Wizards*; Lefèvre, *Reminiscences of a Stock Operator*; Livermore, *How to Trade in Stocks*; Pruden, *The Three Skills of Top Trading*; Williams (T.), *Master the Markets*; Brooks, *Trading Price Action* trilogy; DeMark, *The New Science of Technical Analysis*; Clenow, *Stocks on the Move* and *Following the Trend*; Antonacci, *Dual Momentum Investing*; Bollinger, *Bollinger on Bollinger Bands*; Wilder, *New Concepts in Technical Trading Systems*; Van Tharp, *Trade Your Way to Financial Freedom*; Ochoa, *Secrets of a Pivot Boss*; Morales & Kacher, *Trade Like an O'Neil Disciple*; Bulkowski, *Encyclopedia of Chart Patterns*.

Papers: Jegadeesh & Titman (1993) *J. Finance*; Jegadeesh (1990) *J. Finance*; Lehmann (1990) *QJE*; George & Hwang (2004) *J. Finance*; Moskowitz & Grinblatt (1999) *J. Finance*; Moskowitz, Ooi & Pedersen (2012) *JFE*; Hurst, Ooi & Pedersen (2017); Daniel & Moskowitz (2016) *JFE*; Barroso & Santa-Clara (2015) *JFE*; Brock, Lakonishok & LeBaron (1992) *J. Finance*; Park & Irwin (2007) *J. Economic Surveys*; Lo, Mamaysky & Wang (2000) *J. Finance*; Osler (2000, 2003) FRBNY/*J. Finance*; Faber (2007) SSRN; Antonacci (2012) SSRN; Lou, Polk & Skouras (2019) *JFE*; Ball & Brown (1968); Bernard & Thomas (1989); Ni, Pearson & Poteshman (2005) *JFE*; Pan & Poteshman (2006) *RFS*; Carr & Wu (2009) *RFS*; Thorp (2006); Zarattini, Aziz & Barbon (2024) SSRN (ORB); Zarattini & Aziz (2023) SSRN (VWAP); Ansari & Khan (2012) *Managerial Finance*; Gunasekarage & Power (2001) *Emerging Markets Review*.

Papers/books in this list that are **not** in the verified-online set (Section 0.2) were cited from my own knowledge; check page/figure details before quoting.

Regulator/index data (verified): the SEBI studies and NSE Indices whitepaper listed in Section 0.3.
