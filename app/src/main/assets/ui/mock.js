/* SwingEdge desktop mock — installed only when the real `Android` bridge is absent.
   Implements every bridge method over an in-memory state.
   URL params: ?scenario=empty|paused|closed|broken  ?busy=scan|backtest
   Stock page: stockInfo/getChart work for any symbol; ZZ* fails, SLOW answers after 6 s, FAILWATCH cannot be added to the watchlist or targeted.
   SPEC3: stockInfo.call + myTarget, state.ideas + state.targets, setTarget / clearTarget.
   Screenshots: ?call=buy|wait|avoid (force the verdict on every stockInfo)  ?sheet=chartfull&sym=X[&zoom=60][&pan=0][&cross=40][&range=2Y|All|3M][&levels=0]
                ?sheet=target&sym=X[&t1=950&stop=870]  ?sheet=stock&sym=X */
(function () {
  'use strict';
  if (typeof window === 'undefined' || window.Android) return;

  var qs = {};
  try {
    String(window.location.search || '').replace(/^\?/, '').split('&').forEach(function (kv) {
      if (!kv) return;
      var i = kv.indexOf('=');
      var k = i < 0 ? kv : kv.slice(0, i);
      qs[decodeURIComponent(k)] = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1));
    });
  } catch (e) { /* ignore */ }
  var scenario = qs.scenario || 'default';

  var MIN = 60000, HR = 3600000, DAY = 86400000;
  var T0 = Date.now();

  function defaultSettings() {
    return { capital: 100000, riskPct: 0.75, maxOpenTrades: 4, costPct: 0.25, notifyBuy: true, notifySell: true, notifyBrief: true };
  }

  function strategies(mode) {
    var list = [
      { id: 'pullback', label: 'Trend pullback', desc: 'Stock in an uptrend dips to its 20-day average and turns up on volume.',
        verdict: 'LIVE', testedAt: T0 - 2 * DAY - 3 * HR,
        stats: { n: 212, winPct: 58, avgPct: 0.62, pf: 1.48, maxDdPct: 9.4, oosN: 64, oosAvgPct: 0.41 },
        why: 'Positive after charges on both the older data and the most recent unseen year.' },
      { id: 'breakout', label: '20-day breakout', desc: 'Closes above the 20-day high with volume at least 1.5× average.',
        verdict: 'LIVE', testedAt: T0 - 2 * DAY - 3 * HR,
        stats: { n: 148, winPct: 49, avgPct: 0.81, pf: 1.36, maxDdPct: 12.1, oosN: 41, oosAvgPct: 0.33 },
        why: 'Wins less than half the time but winners are larger than losers. Passes after charges.' },
      { id: 'rsi2', label: 'Oversold bounce', desc: 'Short-term oversold reading inside a long-term uptrend.',
        verdict: 'PAUSED', testedAt: T0 - 2 * DAY - 3 * HR,
        stats: { n: 301, winPct: 61, avgPct: -0.08, pf: 0.96, maxDdPct: 17.8, oosN: 92, oosAvgPct: -0.21 },
        why: 'Lost money after charges on the most recent unseen year. No live buys until a re-test passes.' },
      { id: 'gapfill', label: 'Gap and hold', desc: 'Gap-up on results day that holds its opening range.',
        verdict: 'UNTESTED', testedAt: 0,
        stats: { n: 0, winPct: 0, avgPct: 0, pf: 0, maxDdPct: 0, oosN: 0, oosAvgPct: 0 },
        why: 'Not tested yet on this phone. Run a test to see if it qualifies.' }
    ];
    if (mode === 'paused') {
      list[0].verdict = 'PAUSED';
      list[0].stats = { n: 212, winPct: 51, avgPct: -0.12, pf: 0.93, maxDdPct: 14.2, oosN: 64, oosAvgPct: -0.35 };
      list[0].why = 'Average result after charges turned negative in the latest test. Paused until a re-test passes.';
      list[1].verdict = 'PAUSED';
      list[1].stats = { n: 148, winPct: 42, avgPct: 0.05, pf: 1.02, maxDdPct: 16.9, oosN: 41, oosAvgPct: -0.18 };
      list[1].why = 'Edge on the recent unseen year is below zero after charges. Paused.';
    }
    if (mode === 'empty') {
      list.forEach(function (s) {
        s.verdict = 'UNTESTED'; s.testedAt = 0;
        s.stats = { n: 0, winPct: 0, avgPct: 0, pf: 0, maxDdPct: 0, oosN: 0, oosAvgPct: 0 };
        s.why = 'Not tested yet on this phone. Run a test to see if it qualifies.';
      });
    }
    return list;
  }

  function signals() {
    return [
      { id: 'sg-1', sym: 'TATAPOWER', name: 'Tata Power Company Ltd', strategyId: 'pullback', strategyLabel: 'Trend pullback',
        createdAt: T0 - 9 * MIN, validTill: T0 + 36 * MIN, ltp: 436.2, entry: 435.0, entryMax: 438.5, sl: 421.9, t1: 448.1, t2: 461.2,
        qty: 57, investRs: 24795, riskRs: 809, rewardRs: 1431, holdDays: 6,
        edge: { n: 212, winPct: 58, avgPct: 0.62, pf: 1.48 },
        reasons: ['Above 50 and 200-day averages; pulled back 4.1% to the 20-day average', 'Today closed in the top third of its range on 1.4× volume', 'Stop sits below the swing low at ₹423'], status: 'NEW' },
      { id: 'sg-2', sym: 'CUMMINSIND', name: 'Cummins India Ltd', strategyId: 'breakout', strategyLabel: '20-day breakout',
        createdAt: T0 - 9 * MIN, validTill: T0 + 36 * MIN, ltp: 3862.4, entry: 3855.0, entryMax: 3884.0, sl: 3748.0, t1: 3962.0, t2: 4069.0,
        qty: 7, investRs: 26985, riskRs: 816, rewardRs: 1430, holdDays: 6,
        edge: { n: 148, winPct: 49, avgPct: 0.81, pf: 1.36 },
        reasons: ['Closed above the 20-day high of ₹3,848', 'Volume 1.9× the 20-day average', 'Nifty regime is BULL'], status: 'NEW' },
      { id: 'sg-3', sym: 'BEL', name: 'Bharat Electronics Ltd', strategyId: 'pullback', strategyLabel: 'Trend pullback',
        createdAt: T0 - 9 * MIN, validTill: T0 + 36 * MIN, ltp: 302.85, entry: 302.0, entryMax: 304.4, sl: 293.3, t1: 310.7, t2: 319.4,
        qty: 86, investRs: 25972, riskRs: 813, rewardRs: 1431, holdDays: 5,
        edge: { n: 212, winPct: 58, avgPct: 0.62, pf: 1.48 },
        reasons: ['Third higher low in a row above the 20-day average', 'Defence index up 1.2% today'], status: 'NEW' },
      { id: 'sg-0', sym: 'IRCTC', name: 'Indian Railway Catering & Tourism Corp', strategyId: 'breakout', strategyLabel: '20-day breakout',
        createdAt: T0 - DAY, validTill: T0 - DAY + HR, ltp: 812, entry: 810, entryMax: 816, sl: 788, t1: 832, t2: 854,
        qty: 34, investRs: 27540, riskRs: 817, rewardRs: 1427, holdDays: 6,
        edge: { n: 148, winPct: 49, avgPct: 0.81, pf: 1.36 }, reasons: ['Expired yesterday'], status: 'EXPIRED' }
    ];
  }

  function trades() {
    return [
      { id: 'tr-1', sym: 'POLYCAB', name: 'Polycab India Ltd', strategyLabel: 'Trend pullback', status: 'OPEN', openedAt: T0 - 5 * DAY,
        entry: 6420.0, qty: 4, sl: 6235.0, t1: 6605.0, t2: 6790.0, day: 6, maxDays: 6, ltp: 6198.5, pnlRs: -950, pnlPct: -3.7,
        action: 'EXIT', actionNote: 'Price is below the stop-loss of ₹6,235. Sell at market now.', t1Done: false, closedAt: 0, exitPrice: 0, result: '' },
      { id: 'tr-2', sym: 'HAL', name: 'Hindustan Aeronautics Ltd', strategyLabel: '20-day breakout', status: 'OPEN', openedAt: T0 - 2 * DAY,
        entry: 4512.0, qty: 6, sl: 4388.0, t1: 4636.0, t2: 4760.0, day: 3, maxDays: 6, ltp: 4648.3, pnlRs: 749, pnlPct: 2.77,
        action: 'BOOK_HALF', actionNote: 'Target 1 reached. Sell 3 shares and move the stop on the rest to ₹4,512.', t1Done: false, closedAt: 0, exitPrice: 0, result: '' },
      { id: 'tr-3', sym: 'COALINDIA', name: 'Coal India Ltd', strategyLabel: 'Trend pullback', status: 'OPEN', openedAt: T0 - 1 * DAY,
        entry: 478.4, qty: 52, sl: 464.0, t1: 492.8, t2: 507.2, day: 2, maxDays: 6, ltp: 484.15, pnlRs: 236, pnlPct: 0.95,
        action: 'HOLD', actionNote: 'On track. Stop-loss stays at ₹464.', t1Done: false, closedAt: 0, exitPrice: 0, result: '' },
      { id: 'tr-4', sym: 'MARICO', name: 'Marico Ltd', strategyLabel: 'Trend pullback', status: 'OPEN', openedAt: T0 - 8 * DAY,
        entry: 642.0, qty: 38, sl: 642.0, t1: 661.0, t2: 680.0, day: 6, maxDays: 6, ltp: 664.9, pnlRs: 870, pnlPct: 3.57,
        action: 'TIME_EXIT', actionNote: 'Day 6 of 6. Target 2 was not reached. Sell the remaining 19 shares before 15:20.', t1Done: true, closedAt: 0, exitPrice: 0, result: '' },
      { id: 'tr-9', sym: 'TITAN', name: 'Titan Company Ltd', strategyLabel: 'Trend pullback', status: 'CLOSED', openedAt: T0 - 12 * DAY,
        entry: 3410, qty: 7, sl: 3310, t1: 3510, t2: 3610, day: 5, maxDays: 6, ltp: 3588, pnlRs: 1186, pnlPct: 4.97,
        action: 'HOLD', actionNote: '', t1Done: true, closedAt: T0 - 7 * DAY, exitPrice: 3588, result: 'T2 nearly hit' }
    ];
  }

  function holdings() {
    return [
      { sym: 'YESBANK', name: 'Yes Bank Ltd', qty: 900, avg: 24.6, ltp: 19.82, valueRs: 17838, pnlRs: -4302, pnlPct: -19.43, dayChgPct: -2.1,
        verdict: 'SELL', strength: 22, headline: 'Below 200-day average and falling for 3 weeks', stop: 0, bookAt: 0,
        reasons: ['Closed below the 200-day average for 14 sessions', 'Each bounce has made a lower high', 'Money here could work harder elsewhere'], dataOk: true },
      { sym: 'ZEEL', name: 'Zee Entertainment Enterprises Ltd', qty: 120, avg: 268.0, ltp: 131.4, valueRs: 15768, pnlRs: -16392, pnlPct: -50.97, dayChgPct: -0.8,
        verdict: 'SELL', strength: 22, headline: 'Broke the ₹134 support on heavy volume', stop: 0, bookAt: 0,
        reasons: ['New 52-week low this week', 'Long-term trend is down'], dataOk: true },
      { sym: 'ITC', name: 'ITC Ltd', qty: 150, avg: 301.5, ltp: 468.9, valueRs: 70335, pnlRs: 25110, pnlPct: 55.52, dayChgPct: 0.35,
        verdict: 'TRIM', strength: 81, headline: 'Up 55% and stretched 11% above the 50-day average', stop: 441.0, bookAt: 482.0,
        reasons: ['Position is now 27% of the portfolio', 'Momentum is slowing: lower highs on RSI', 'Book a third near ₹482 and trail the rest'], dataOk: true },
      { sym: 'TATAMOTORS', name: 'Tata Motors Ltd', qty: 40, avg: 912.0, ltp: 934.6, valueRs: 37384, pnlRs: 904, pnlPct: 2.48, dayChgPct: -1.42,
        verdict: 'WATCH', strength: 48, headline: 'Sitting on the 50-day average — next 2 days decide', stop: 908.0, bookAt: 0,
        reasons: ['A close below ₹908 turns this into a SELL', 'Sector is weak this week'], dataOk: true },
      { sym: 'RELIANCE', name: 'Reliance Industries Ltd', qty: 20, avg: 2456.0, ltp: 2918.45, valueRs: 58369, pnlRs: 9249, pnlPct: 18.83, dayChgPct: 0.62,
        verdict: 'HOLD', strength: 70, headline: 'Uptrend intact above the 50-day average', stop: 2790.0, bookAt: 3080.0,
        reasons: ['Above 50 and 200-day averages', 'Higher highs and higher lows since August'], dataOk: true },
      { sym: 'HDFCBANK', name: 'HDFC Bank Ltd', qty: 30, avg: 1588.0, ltp: 1702.3, valueRs: 51069, pnlRs: 3429, pnlPct: 7.2, dayChgPct: 0.18,
        verdict: 'HOLD', strength: 70, headline: 'Steady; trend up, no stretch', stop: 1638.0, bookAt: 1790.0,
        reasons: ['Holding above the 20-day average', 'Relative strength vs Nifty improving'], dataOk: true },
      { sym: 'INFY', name: 'Infosys Ltd', qty: 25, avg: 1490.0, ltp: 1534.75, valueRs: 38369, pnlRs: 1119, pnlPct: 3.0, dayChgPct: -0.44,
        verdict: 'HOLD', strength: 70, headline: 'Range-bound, above long-term average', stop: 1472.0, bookAt: 1610.0,
        reasons: ['Trading between ₹1,480 and ₹1,600 for 5 weeks'], dataOk: true },
      { sym: 'SBIN', name: 'State Bank of India', qty: 60, avg: 742.0, ltp: 801.2, valueRs: 48072, pnlRs: 3552, pnlPct: 7.98, dayChgPct: 1.05,
        verdict: 'HOLD', strength: 70, headline: 'Fresh 20-day high today', stop: 768.0, bookAt: 846.0,
        reasons: ['Breakout with volume 1.3× average'], dataOk: true },
      { sym: 'NEWLISTCO', name: 'Newly Listed Company Ltd', qty: 15, avg: 720.0, ltp: 0, valueRs: 0, pnlRs: 0, pnlPct: 0, dayChgPct: 0,
        verdict: 'HOLD', strength: 70, headline: '', stop: 0, bookAt: 0, reasons: [], dataOk: false }
    ];
  }

  function portfolioOf(hs) {
    var inv = 0, val = 0, day = 0, sell = 0, trim = 0;
    hs.forEach(function (h) {
      if (!h || h.dataOk === false) return;
      inv += h.qty * h.avg; val += h.valueRs;
      day += h.valueRs * (h.dayChgPct / 100) / (1 + h.dayChgPct / 100);
      if (h.verdict === 'SELL') sell++;
      if (h.verdict === 'TRIM') trim++;
    });
    return { investedRs: Math.round(inv), valueRs: Math.round(val), pnlRs: Math.round(val - inv),
      pnlPct: inv ? Math.round((val - inv) / inv * 10000) / 100 : 0, dayPnlRs: Math.round(day), sellCount: sell, trimCount: trim };
  }

  function scorecard() {
    return { n: 9, winPct: 56, netRs: 3840, avgPct: 0.71, paperN: 23, paperWinPct: 52, paperAvgPct: 0.44,
      recent: [
        { sym: 'TITAN', closedAt: T0 - 7 * DAY, result: 'Closed near T2', pnlRs: 1186, pnlPct: 4.97, paper: false },
        { sym: 'LT', closedAt: T0 - 8 * DAY, result: 'Stop-loss hit', pnlRs: -782, pnlPct: -3.02, paper: false },
        { sym: 'BHARTIARTL', closedAt: T0 - 9 * DAY, result: 'T1 hit, rest at entry', pnlRs: 0, pnlPct: 1.45, paper: true },
        { sym: 'DIXON', closedAt: T0 - 11 * DAY, result: 'Time exit on day 6', pnlRs: 214, pnlPct: 0.82, paper: false },
        { sym: 'PFC', closedAt: T0 - 12 * DAY, result: 'Stop-loss hit', pnlRs: 0, pnlPct: -2.9, paper: true },
        { sym: 'SIEMENS', closedAt: T0 - 15 * DAY, result: 'T2 hit', pnlRs: 1522, pnlPct: 5.6, paper: false }
      ] };
  }

  function feed() {
    return [
      { ts: T0 - 9 * MIN, kind: 'buy', title: 'BUY TATAPOWER ₹435–438.50', body: 'SL ₹421.90 · T1 ₹448.10 · T2 ₹461.20 · 57 shares' },
      { ts: T0 - 9 * MIN, kind: 'buy', title: 'BUY CUMMINSIND ₹3,855–3,884', body: 'SL ₹3,748 · T1 ₹3,962 · T2 ₹4,069 · 7 shares' },
      { ts: T0 - 9 * MIN, kind: 'buy', title: 'BUY BEL ₹302–304.40', body: 'SL ₹293.30 · T1 ₹310.70 · T2 ₹319.40 · 86 shares' },
      { ts: T0 - 24 * MIN, kind: 'sell', title: 'EXIT POLYCAB now', body: 'Price ₹6,198.50 is below the stop-loss ₹6,235.' },
      { ts: T0 - 54 * MIN, kind: 'sell', title: 'HAL reached Target 1', body: 'Sell half (3 shares) and move the stop to ₹4,512.' },
      { ts: T0 - 3 * HR, kind: 'warn', title: 'YESBANK moved to SELL', body: 'Below the 200-day average for 14 sessions.' },
      { ts: T0 - 5 * HR, kind: 'brief', title: 'Morning brief', body: 'Regime BULL. 3 open trades, 2 portfolio SELL calls. Buy ideas come around 14:45.' },
      { ts: T0 - DAY - 1 * HR, kind: 'info', title: 'Scan finished', body: 'OK · 186/190 stocks. 4 symbols had no data.' },
      { ts: T0 - DAY - 2 * HR, kind: 'buy', title: 'BUY IRCTC ₹810–816', body: 'Expired without being taken.' },
      { ts: T0 - DAY - 6 * HR, kind: 'brief', title: 'Morning brief', body: 'Regime BULL. 2 open trades.' },
      { ts: T0 - 2 * DAY - 3 * HR, kind: 'info', title: 'Backtest finished', body: '2 strategies LIVE, 1 PAUSED, 1 UNTESTED.' },
      { ts: T0 - 2 * DAY - 4 * HR, kind: 'warn', title: 'Oversold bounce paused', body: 'Lost money after charges on the recent unseen year.' },
      { ts: T0 - 7 * DAY, kind: 'sell', title: 'TITAN closed +₹1,186', body: 'Closed near Target 2 at ₹3,588.' }
    ];
  }

  /* ---------- stock model: one deterministic 500-bar series per symbol drives search, stockInfo, getChart, myWatch ---------- */
  var UNI = [
    ['RELIANCE', 'Reliance Industries Ltd', 2918.45, 1], ['TCS', 'Tata Consultancy Services Ltd', 4120.3, 1], ['HDFCBANK', 'HDFC Bank Ltd', 1702.3, 1],
    ['INFY', 'Infosys Ltd', 1534.75, 1], ['ICICIBANK', 'ICICI Bank Ltd', 1288.9, 1], ['HINDUNILVR', 'Hindustan Unilever Ltd', 2395.6, 1],
    ['ITC', 'ITC Ltd', 468.9, 1], ['SBIN', 'State Bank of India', 801.2, 1], ['BHARTIARTL', 'Bharti Airtel Ltd', 1712.4, 1],
    ['KOTAKBANK', 'Kotak Mahindra Bank Ltd', 1895.2, 1], ['LT', 'Larsen & Toubro Ltd', 3540.5, 1], ['AXISBANK', 'Axis Bank Ltd', 1104.0, 1],
    ['ASIANPAINT', 'Asian Paints Ltd', 2318.7, 1], ['MARUTI', 'Maruti Suzuki India Ltd', 12480.0, 1], ['SUNPHARMA', 'Sun Pharmaceutical Industries Ltd', 1764.2, 1],
    ['TITAN', 'Titan Company Ltd', 3588.0, 1], ['BAJFINANCE', 'Bajaj Finance Ltd', 7120.5, 1], ['WIPRO', 'Wipro Ltd', 512.3, 1],
    ['ULTRACEMCO', 'UltraTech Cement Ltd', 11240.0, 1], ['NESTLEIND', 'Nestle India Ltd', 2288.4, 1], ['ONGC', 'Oil & Natural Gas Corporation Ltd', 262.8, 1],
    ['NTPC', 'NTPC Ltd', 372.5, 1], ['POWERGRID', 'Power Grid Corporation of India Ltd', 318.2, 1], ['TATAMOTORS', 'Tata Motors Ltd', 934.6, 1],
    ['TATASTEEL', 'Tata Steel Ltd', 162.4, 1], ['TATAPOWER', 'Tata Power Company Ltd', 436.2, 1], ['TATACONSUM', 'Tata Consumer Products Ltd', 1108.9, 1],
    ['TATAELXSI', 'Tata Elxsi Ltd', 6240.0, 0], ['TATACOMM', 'Tata Communications Ltd', 1795.3, 0], ['M&M', 'Mahindra & Mahindra Ltd', 2986.0, 1],
    ['HCLTECH', 'HCL Technologies Ltd', 1648.5, 1], ['TECHM', 'Tech Mahindra Ltd', 1522.8, 1], ['ADANIENT', 'Adani Enterprises Ltd', 2410.7, 1],
    ['ADANIPORTS', 'Adani Ports and Special Economic Zone Ltd', 1356.2, 1], ['COALINDIA', 'Coal India Ltd', 484.15, 1], ['BEL', 'Bharat Electronics Ltd', 302.85, 1],
    ['HAL', 'Hindustan Aeronautics Ltd', 4648.3, 1], ['POLYCAB', 'Polycab India Ltd', 6198.5, 1], ['CUMMINSIND', 'Cummins India Ltd', 3862.4, 1],
    ['FEDERALBNK', 'Federal Bank Ltd', 212.4, 1], ['TVSMOTOR', 'TVS Motor Company Ltd', 3105.0, 1], ['IRCTC', 'Indian Railway Catering & Tourism Corp Ltd', 812.0, 1],
    ['ZEEL', 'Zee Entertainment Enterprises Ltd', 131.4, 1], ['YESBANK', 'Yes Bank Ltd', 19.82, 1], ['MARICO', 'Marico Ltd', 664.9, 1],
    ['DIXON', 'Dixon Technologies (India) Ltd', 14820.0, 1], ['PFC', 'Power Finance Corporation Ltd', 468.0, 1], ['SIEMENS', 'Siemens Ltd', 6985.0, 1],
    ['HDFCLIFE', 'HDFC Life Insurance Company Ltd', 712.4, 1], ['ILLIQTEXTILE', 'Illiq Textile Mills Ltd', 41.2, 0]
  ];
  var UBY = {}; UNI.forEach(function (u) { UBY[u[0]] = u; });
  var DAYCHG = {}; holdings().forEach(function (h) { DAYCHG[h.sym] = h.dayChgPct; });
  var NIFTY_RET = { d21: 1.6, d63: 4.3, d126: 7.9 };
  function hash(s) { var h = 7, i; for (i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 1000003; return h; }
  function mkRnd(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; var t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
  function r2(x) { return Math.round(x * 100) / 100; }
  function emaOf(c, k) {
    var out = [], a = 2 / (k + 1), i, s = 0, e = 0;
    for (i = 0; i < c.length; i++) {
      if (i < k - 1) { s += c[i]; out.push(0); continue; }
      if (i === k - 1) { s += c[i]; e = s / k; } else e = e + a * (c[i] - e);
      out.push(r2(e));
    }
    return out;
  }
  function lastOf(a) { return a[a.length - 1]; }
  function ago(n) { return n === 0 ? 'today' : n === 1 ? '1 day ago' : n + ' days ago'; }
  var models = {};
  function model(sym) {
    if (models[sym]) return models[sym];
    var u = UBY[sym], base = u ? u[2] : 60 + hash(sym) % 3400, n = 500, rnd = mkRnd(hash(sym) * 7 + 13), i;
    var drift = (rnd() - 0.4) * 0.0018, vol = 0.011 + rnd() * 0.011, p = 100, raw = [];
    for (i = 0; i < n; i++) { p *= 1 + drift + (rnd() + rnd() + rnd() - 1.5) * vol * 1.5; raw.push(p); }
    var k = base / raw[n - 1], c = [], o = [], h = [], l = [], v = [], d = [];
    for (i = 0; i < n; i++) c.push(r2(raw[i] * k));
    if (DAYCHG[sym] != null) c[n - 2] = r2(c[n - 1] / (1 + DAYCHG[sym] / 100));
    var turn = /^ILLIQ/.test(sym) ? 3 : 40 + rnd() * 300, v0 = turn * 1e7 / base;
    for (i = 0; i < n; i++) {
      var op = i ? r2(c[i - 1] * (1 + (rnd() - 0.5) * 0.008)) : c[0];
      o.push(op); h.push(r2(Math.max(op, c[i]) * (1 + rnd() * 0.01))); l.push(r2(Math.min(op, c[i]) * (1 - rnd() * 0.01)));
      v.push(Math.round(v0 * (0.5 + rnd() * 1.1) * (1 + Math.abs(c[i] / (i ? c[i - 1] : c[i]) - 1) * 18)));
    }
    // dates: n weekdays ending on the latest trading day (IST)
    var IST = 19800000, dt = new Date(Date.now() + IST), day;
    while (dt.getUTCDay() === 0 || dt.getUTCDay() === 6) dt = new Date(dt.getTime() - 86400000);
    for (i = n - 1; i >= 0; i--) {
      d[i] = Date.UTC(dt.getUTCFullYear(), dt.getUTCMonth(), dt.getUTCDate(), 3, 45);
      dt = new Date(dt.getTime() - 86400000);
      while (dt.getUTCDay() === 0 || dt.getUTCDay() === 6) dt = new Date(dt.getTime() - 86400000);
    }
    var ld = new Date(d[n - 1] + IST), barDay = ld.getUTCFullYear() * 10000 + (ld.getUTCMonth() + 1) * 100 + ld.getUTCDate();
    var E = { 10: emaOf(c, 10), 20: emaOf(c, 20), 50: emaOf(c, 50), 100: emaOf(c, 100), 200: emaOf(c, 200) };
    var ltp = c[n - 1], prev = c[n - 2], e = {}, dist = {};
    [10, 20, 50, 100, 200].forEach(function (q) { e['e' + q] = lastOf(E[q]); dist['e' + q] = r2((ltp / e['e' + q] - 1) * 100); });
    function crossOf(a, b, maxBack) {
      var cur = a[n - 1] >= b[n - 1] ? 'ABOVE' : 'BELOW', ds = -1, j;
      for (j = 0; j <= maxBack; j++) { var ix = n - 1 - j; if (ix < 1 || !(b[ix - 1] > 0)) break; if ((a[ix] >= b[ix]) !== (a[ix - 1] >= b[ix - 1])) { ds = j; break; } }
      return { state: cur, daysSince: ds };
    }
    var cr = crossOf(E[10], E[20], 60), gap = (e.e10 - e.e20) / e.e20 * 100, gap2 = (E[10][n - 3] - E[20][n - 3]) / E[20][n - 3] * 100;
    var conv = Math.abs(gap) < Math.abs(gap2);
    var gd = crossOf(E[50], E[200], 480);
    var stackLab = e.e10 > e.e20 && e.e20 > e.e50 && e.e50 > e.e100 && e.e100 > e.e200 ? 'BULLISH' : e.e10 < e.e20 && e.e20 < e.e50 && e.e50 < e.e100 && e.e100 < e.e200 ? 'BEARISH' : 'MIXED';
    // RSI (Wilder) and ATR
    function rsi(per) {
      var up = 0, dn = 0, j;
      for (j = 1; j <= per; j++) { var ch = c[j] - c[j - 1]; if (ch > 0) up += ch; else dn -= ch; }
      up /= per; dn /= per;
      for (j = per + 1; j < n; j++) { var cc = c[j] - c[j - 1]; up = (up * (per - 1) + Math.max(cc, 0)) / per; dn = (dn * (per - 1) + Math.max(-cc, 0)) / per; }
      return dn === 0 ? 100 : 100 - 100 / (1 + up / dn);
    }
    var atr = 0, j2; for (j2 = 1; j2 < n; j2++) { var tr = Math.max(h[j2] - l[j2], Math.abs(h[j2] - c[j2 - 1]), Math.abs(l[j2] - c[j2 - 1])); atr = j2 < 15 ? atr + tr / 14 : (atr * 13 + tr) / 14; }
    function ret(b) { return r2((ltp / c[n - 1 - b] - 1) * 100); }
    var rets = { d5: ret(5), d21: ret(21), d63: ret(63), d126: ret(126), d250: ret(250) };
    var vsN = { d21: r2(rets.d21 - NIFTY_RET.d21), d63: r2(rets.d63 - NIFTY_RET.d63), d126: r2(rets.d126 - NIFTY_RET.d126) };
    var hi52 = Math.max.apply(null, h.slice(-250)), lo52 = Math.min.apply(null, l.slice(-250)), hi20 = Math.max.apply(null, h.slice(-20)), lo20 = Math.min.apply(null, l.slice(-20));
    var avgV = Math.round(v.slice(-21, -1).reduce(function (a, b) { return a + b; }, 0) / 20), volu = v[n - 1];
    var turnCr = r2(volu * ltp / 1e7), liquid = turnCr >= 25;
    var resist = hi20; if (hi52 > ltp && hi52 / ltp - 1 <= 0.03) resist = hi52; else if (hi20 <= ltp && hi52 > ltp) resist = hi52;
    var trail = Math.max.apply(null, c.slice(-22)) - 3 * atr;
    var rsi14 = r2(rsi(14)), rsi2 = r2(rsi(2));
    var strength = Math.max(0, Math.min(100, Math.round(50 + (stackLab === 'BULLISH' ? 25 : stackLab === 'BEARISH' ? -25 : 0) + (ltp > e.e200 ? 10 : -10) + (ltp > e.e50 ? 8 : -8) + Math.max(-15, Math.min(15, rets.d63)))));
    var vlab = strength >= 65 ? 'HOLD' : strength >= 45 ? 'WATCH' : strength >= 30 ? 'TRIM' : 'SELL';
    var vhead = { HOLD: 'Uptrend intact: price is above the 50 and 200 EMA', WATCH: 'Trend is mixed; the next few closes matter', TRIM: 'Trend is fading: price is losing its longer averages', SELL: 'Trend has broken: price is below its key averages' }[vlab];
    var pctTxt = function (x) { return (x >= 0 ? '+' : '−') + Math.abs(x).toFixed(1) + '%'; };
    var rup = function (x) { return '₹' + Math.round(x).toLocaleString('en-IN'); };
    var checks = [
      { label: 'Above the 200 EMA', ok: ltp > e.e200, text: rup(ltp) + ' vs ' + rup(e.e200) + ' (' + pctTxt(dist.e200) + ')' },
      { label: 'Above the 50 EMA', ok: ltp > e.e50, text: rup(ltp) + ' vs ' + rup(e.e50) + ' (' + pctTxt(dist.e50) + ')' },
      { label: '10 EMA above the 20 EMA', ok: cr.state === 'ABOVE', text: 'Gap ' + pctTxt(gap) },
      { label: '50 EMA above the 200 EMA', ok: gd.state === 'ABOVE', text: rup(e.e50) + ' vs ' + rup(e.e200) },
      { label: 'Not overheated (RSI below 70)', ok: rsi14 < 70, text: 'RSI (14) is ' + Math.round(rsi14) },
      { label: 'Within 10% of the 52-week high', ok: ltp / hi52 - 1 > -0.1, text: pctTxt((ltp / hi52 - 1) * 100) + ' from ' + rup(hi52) },
      { label: 'Volume at or above its 20-day average', ok: volu >= avgV, text: (volu / avgV).toFixed(2) + '× the average' },
      { label: 'Liquid enough', ok: liquid, text: '₹' + turnCr.toFixed(1) + ' crore traded today (screen needs ₹25 crore+)' },
      { label: 'Positive 3-month return', ok: rets.d63 > 0, text: pctTxt(rets.d63) + ' over 63 trading days' }
    ];
    var xmatch = cr.state === 'ABOVE' && cr.daysSince === 0 && e.e10 > e.e50 && e.e10 > e.e100 && e.e10 > e.e200 && liquid;
    var xtxt = xmatch ? 'Crossed today with the 10 EMA above the 50, 100 and 200 EMA' :
      cr.state === 'BELOW' ? 'The 10 EMA is below the 20 EMA, so there is no upward cross' :
      cr.daysSince === 0 ? 'Crossed today but the 10 EMA is not above all the longer averages' :
      cr.daysSince > 0 ? 'Crossed ' + ago(cr.daysSince) + ' — the screen only lists crosses from the last 3 bars' : 'No cross in the last 60 bars';
    var setups = [{ id: 'EMA_CROSS', label: 'Fresh 10/20 EMA cross', match: xmatch && cr.daysSince <= 2, text: xtxt }];
    var hiPrev = Math.max.apply(null, h.slice(-21, -1));
    strategies('default').filter(function (s) { return s.verdict === 'LIVE'; }).forEach(function (s) {
      var m = false, t = 'Does not qualify today';
      if (s.id === 'pullback') { m = ltp > e.e200 && ltp > e.e50 && ltp <= e.e20 * 1.01; t = m ? 'Uptrend with price back at the 20 EMA' : ltp <= e.e200 ? 'Price is below the 200 EMA, so there is no uptrend' : 'Price is not pulled back to the 20 EMA (' + pctTxt(dist.e20) + ' from it)'; }
      if (s.id === 'breakout') { m = ltp > hiPrev && volu / avgV >= 1.5; t = m ? 'Closed above the 20-day high on strong volume' : ltp > hiPrev ? 'Above the 20-day high but volume is only ' + (volu / avgV).toFixed(1) + '× average' : 'Below the 20-day high of ' + rup(hiPrev); }
      setups.push({ id: s.id.toUpperCase(), label: s.label, match: m, text: t });
    });
    var nm = u ? u[1] : sym.charAt(0) + sym.slice(1).toLowerCase() + ' Ltd';
    var crossText = '10 EMA ' + (cr.state === 'ABOVE' ? 'above' : 'below') + ' 20 EMA' + (cr.daysSince >= 0 ? ' for ' + cr.daysSince + ' day' + (cr.daysSince === 1 ? '' : 's') : ' for over 60 days');
    var crossFull = cr.daysSince === 0 ? '10 EMA crossed ' + (cr.state === 'ABOVE' ? 'above' : 'below') + ' the 20 EMA today' :
      cr.daysSince > 0 ? '10 EMA crossed ' + (cr.state === 'ABOVE' ? 'above' : 'below') + ' the 20 EMA ' + ago(cr.daysSince) : '10 EMA has been ' + (cr.state === 'ABOVE' ? 'above' : 'below') + ' the 20 EMA for more than 60 days';
    var info = {
      ok: true, sym: sym, name: nm, tracked: !u || u[3] === 1, barDay: barDay, bars: n,
      ltp: ltp, prevClose: prev, dayChgPct: r2((ltp / prev - 1) * 100), open: o[n - 1], dayHigh: h[n - 1], dayLow: l[n - 1],
      volume: volu, avgVol20: avgV, volRatio: r2(volu / avgV), turnoverCr: turnCr, liquid: liquid,
      ema: e, emaDist: dist,
      stack: { label: stackLab, text: stackLab === 'BULLISH' ? '10 > 20 > 50 > 100 > 200: every average is stacked in order' : stackLab === 'BEARISH' ? '10 < 20 < 50 < 100 < 200: every average is stacked against the price' : 'The averages are not in a clean order, so the trend is mixed' },
      cross: { state: cr.state, daysSince: cr.daysSince, gapPct: r2(gap), converging: conv, text: crossFull + ' and the gap is ' + (conv ? 'narrowing' : 'widening') },
      golden: { state: gd.state, daysSince: gd.daysSince, text: '50 EMA ' + (gd.state === 'ABOVE' ? 'above' : 'below') + ' the 200 EMA' + (gd.daysSince >= 0 ? ' for ' + gd.daysSince + ' days' : ' for the whole 2-year history') },
      rsi14: rsi14, rsi2: rsi2, atr: r2(atr), atrPct: r2(atr / ltp * 100), ret: rets, vsNifty: vsN,
      high52: hi52, low52: lo52, fromHighPct: r2((ltp / hi52 - 1) * 100), fromLowPct: r2((ltp / lo52 - 1) * 100), high20: hi20, low20: lo20,
      levels: { support: lo20, resistance: resist, trailStop: r2(trail) },
      strength: strength, verdict: { label: vlab, headline: vhead }, checks: checks, setups: setups,
      summary: nm + ' last traded at ' + rup(ltp) + ', ' + pctTxt(dist.e200) + ' from its 200 EMA. ' + (stackLab === 'BULLISH' ? 'The averages are stacked in order, which is how a steady uptrend looks on the chart. ' : stackLab === 'BEARISH' ? 'The averages are stacked against the price, which is how a steady downtrend looks. ' : 'The averages are tangled, so there is no clean trend. ') +
        'RSI (14) is ' + Math.round(rsi14) + '. The picture would change on a close below the ' + rup(lo20) + ' support or above the ' + rup(resist) + ' resistance.' + (liquid ? '' : ' Trading is thin here, so prices can jump between trades.')
    };
    return (models[sym] = { c: c, o: o, h: h, l: l, v: v, d: d, E: E, info: info, crossText: crossText, strength: strength });
  }

  /* ---------- SPEC3: the buy verdict ("call"), ideas and targets ---------- */
  var FORCE = { RELIANCE: 'BUY', HDFCBANK: 'BUY', TCS: 'BUY', SBIN: 'BUY', LT: 'BUY', SUNPHARMA: 'BUY', TITAN: 'BUY', BHARTIARTL: 'BUY', ITC: 'BUY', MARUTI: 'BUY',
    TATAMOTORS: 'WAIT', INFY: 'WAIT', POLYCAB: 'WAIT', HAL: 'WAIT', DIXON: 'WAIT', YESBANK: 'AVOID', ZEEL: 'AVOID', ILLIQTEXTILE: 'AVOID' };
  var HIST = {
    BUY: { n: 33334, winPct: 51.3, avgPct: 4.41, medianPct: 0.69, worstPct: -30.2, horizonDays: 60, text: 'Since 2022, stocks scoring 85–100 returned +4.4% on average and +0.7% at the median over the next 60 trading days after charges (33,334 cases); 1 in 20 lost 30.2% or more. That is +1.51% more than the average liquid stock over the same days, a difference that is not statistically reliable (t = 1.5).' },
    WAIT: { n: 80160, winPct: 51.8, avgPct: 4.72, medianPct: 1.66, worstPct: -25.1, horizonDays: 60, text: 'Since 2022, stocks scoring 70–84 returned +4.7% on average and +1.7% at the median over the next 60 trading days after charges (80,160 cases); 1 in 20 lost 25.1% or more. That is +0.86% more than the average liquid stock over the same days, a difference that is not statistically reliable (t = 1.6).' },
    AVOID: { n: 164592, winPct: 49.7, avgPct: 1.99, medianPct: -0.11, worstPct: -23.8, horizonDays: 60, text: 'Since 2022, stocks scoring 0–39 returned +2.0% on average and -0.1% at the median over the next 60 trading days after charges (164,592 cases); 1 in 20 lost 23.8% or more. That is 1.5% behind the average liquid stock over the same days, a statistically reliable difference (t = -3.2).' }
  };
  var EVID = {
    BUY: 'Tested on NSE 2022–26 with this exact plan: +1.75% per trade after 0.25% costs (about +1.5% at realistic costs), 32% winners, median trade -3%. A ₹1 lakh 4-slot portfolio made 3.6–10.9% a year depending on the start date (median about 6%, drawdown -53%) against the Nifty\'s 7.5% and about 13% for an equal-weight basket. Random stocks above their 50-day average with the same exits did better, so size small and follow the stop.',
    WAIT: 'WAIT is not a buy signal. Since 2022, stocks scoring 70–84 did +0.86% better than the average liquid stock over 60 trading days, which is not statistically reliable (t = 1.6).',
    AVOID: 'Scores below 55 lagged the average liquid stock by about 1 point over 60 sessions (behind in 13 of 14 years; 2022–26: -0.9). They still averaged +2.4%, so this means likely to underperform, not likely to fall. The lag was weaker among the most liquid stocks.'
  };
  function ptsOf(score, h) {
    var labs = ['6-month momentum (risk-adjusted)', '6-month return', '12-month return', 'Near its 52-week high', 'Above its 52-week low'], out = [], i;
    for (i = 0; i < 5; i++) out.push({ label: labs[i], pts: Math.max(0, Math.min(20, Math.round((score / 5 + ((h >> i) % 7) - 3) * 10) / 10)), max: 20 });
    return out;
  }
  function pTxt(x) { return (x >= 0 ? '+' : '−') + Math.abs(x).toFixed(1) + '%'; }
  function callOf(sym, force) {
    var m = model(sym), i = m.info, ltp = i.ltp, e = i.ema, atr = i.atr, h = hash(sym), lab;
    if (force) lab = force.toUpperCase();
    else if (FORCE[sym]) lab = FORCE[sym];
    else if (!i.liquid || ltp < e.e200 || i.stack.label === 'BEARISH') lab = 'AVOID';
    else if (i.stack.label === 'BULLISH' && i.rsi14 < 72) lab = 'BUY';
    else lab = 'WAIT';
    var score = lab === 'BUY' ? Math.min(97, 85 + h % 12) : lab === 'WAIT' ? 55 + h % 30 : Math.max(8, Math.min(54, Math.round(i.strength * 0.4) + h % 15));
    var hz = 60, c = { label: lab, proven: lab === 'AVOID', evidence: EVID[lab] || '', band: '', points: [], exitRule: lab === 'AVOID' ? '' : 'Sell if it closes below its 50-day average, or after 60 trading days. Targets are reference levels, not sell orders.', score: score, headline: '', reasons: [], entryLow: 0, entryHigh: 0, stop: 0, t1: 0, t2: 0, stopPct: 0, t1Pct: 0, t2Pct: 0, rr: 0, horizonDays: hz,
      qty: 0, investRs: 0, riskRs: 0, hist: JSON.parse(JSON.stringify(HIST[lab])), waitFor: '', caveat: 'Rule-based and tested on past data. Losses happen; the stop limits them, it does not remove them.' };
    c.hist.horizonDays = hz;
    var near = -i.fromHighPct;
    if (lab === 'BUY') {
      c.headline = near < 6 ? 'Strong leader near its high — qualifies as a buy setup' : 'Steady uptrend with room to run — qualifies as a buy setup';
      c.reasons = ['Above its 200 EMA by ' + Math.abs(i.emaDist.e200).toFixed(1) + '%: the long-term trend is up', 'The 10, 20, 50, 100 and 200 EMAs are stacked in order', 'Only ' + near.toFixed(1) + '% below its 52-week high: leaders tend to keep leading',
        i.vsNifty.d63 > 0 ? 'Beat the Nifty by ' + i.vsNifty.d63.toFixed(1) + ' points over 3 months' : 'Kept pace with the Nifty over 3 months', 'RSI (14) is ' + Math.round(i.rsi14) + ': strong but not stretched', 'Trades about ₹' + Math.round(i.turnoverCr) + ' crore a day: easy to enter and exit'];
    } else if (lab === 'WAIT') {
      c.headline = 'Decent stock, but not a good entry right now';
      c.reasons = [ltp > e.e200 ? 'Above its 200 EMA: the long-term trend has not broken' : 'Close to its 200 EMA: the trend is undecided', ltp > e.e20 * 1.04 ? 'Price is ' + (i.emaDist.e20).toFixed(1) + '% above its 20 EMA: stretched, so the risk to a stop is large' : 'Price is sitting on its 20 EMA, but the averages are not stacked in order',
        i.rsi14 >= 65 ? 'RSI (14) is ' + Math.round(i.rsi14) + ': short-term momentum is stretched' : 'RSI (14) is ' + Math.round(i.rsi14) + ': momentum is neutral', 'Lost points because 3-month strength is only ' + pTxt(i.ret.d63)];
      c.waitFor = 'a pullback toward ₹' + Math.round(e.e20) + ' (the 20 EMA), or a close above ₹' + Math.round(i.high20 * 1.01) + ' on above-average volume';
    } else {
      c.headline = 'Fails the trend and quality checks';
      c.reasons = [ltp < e.e200 ? 'Below its 200 EMA by ' + Math.abs(i.emaDist.e200).toFixed(1) + '%: the long-term trend is down' : 'The averages are tangled, so there is no clean trend', 'Down ' + Math.abs(i.ret.d63).toFixed(1) + '% over 3 months' + (i.ret.d63 > 0 ? ' despite a bounce' : ''), i.liquid ? 'RSI (14) is ' + Math.round(i.rsi14) + ': no sign of a base yet' : 'Trades only ₹' + i.turnoverCr.toFixed(1) + ' crore a day: too thin for a clean exit'];
    }
    if (lab !== 'AVOID') {
      var dist = Math.max(ltp * 0.04, Math.min(ltp * 0.09, atr * 2.4)), lo, hi;
      if (lab === 'BUY') { lo = ltp * 0.992; hi = ltp * 1.006; } else { lo = Math.min(ltp * 0.99, e.e20 * 0.995); hi = Math.min(ltp * 0.998, e.e20 * 1.01); }
      c.entryLow = r2(lo); c.entryHigh = r2(Math.max(hi, lo * 1.004));
      var m1 = 1.4 + (h % 6) * 0.14; c.stop = r2(ltp - dist); c.t1 = r2(ltp + dist * m1); c.t2 = r2(ltp + dist * (m1 + 1.3));
      c.stopPct = r2((c.stop / ltp - 1) * 100); c.t1Pct = r2((c.t1 / ltp - 1) * 100); c.t2Pct = r2((c.t2 / ltp - 1) * 100); c.rr = r2((c.t1 - ltp) / (ltp - c.stop));
      if (lab === 'BUY') {
        var st0 = (typeof state === 'object' && state && state.settings) || defaultSettings(), qty = Math.floor(st0.capital * st0.riskPct / 100 / (ltp - c.stop));
        qty = Math.max(0, Math.min(qty, Math.floor(st0.capital * 0.25 / ltp)));
        c.qty = qty; c.investRs = Math.round(qty * ltp); c.riskRs = Math.round(qty * (ltp - c.stop));
      }
    }
    c.band = c.score >= 85 ? '85-100' : c.score >= 70 ? '70-84' : c.score >= 55 ? '55-69' : c.score >= 40 ? '40-54' : '0-39';
    c.points = ptsOf(c.score, h);
    return c;
  }
  function ideasList() {
    var out = [];
    UNI.forEach(function (u) {
      if (u[3] !== 1) return;
      var m = model(u[0]), c = callOf(u[0]);
      if (c.label !== 'BUY' || !m.info.liquid) return;
      out.push({ sym: u[0], name: u[1], ltp: m.info.ltp, dayChgPct: m.info.dayChgPct, score: c.score, label: 'BUY', headline: c.headline, entryLow: c.entryLow, entryHigh: c.entryHigh,
        stop: c.stop, t1: c.t1, t2: c.t2, rr: c.rr, horizonDays: c.horizonDays, spark: m.c.slice(-30), barDay: m.info.barDay });
    });
    out.sort(function (a, b) { return b.score - a.score; });
    return out.slice(0, 15);
  }
  function targetRow(sym, target, stop, note, ref, setAt, status, hitAt) {
    var m = model(sym), i = m.info, ltp = i.ltp, prog = 0;
    if (target > 0 && ref > 0 && target !== ref) prog = Math.max(0, Math.min(100, (ltp - ref) / (target - ref) * 100));
    return { sym: sym, name: i.name, ltp: ltp, dayChgPct: i.dayChgPct, target: target, stop: stop, note: note || '', setAt: setAt, refPrice: ref, status: status, hitAt: hitAt || 0,
      toTargetPct: target > 0 ? r2((target / ltp - 1) * 100) : 0, toStopPct: stop > 0 ? r2((stop / ltp - 1) * 100) : 0, progressPct: r2(prog) };
  }
  function defaultTargets() {
    return [
      targetRow('SBIN', 790, 724, 'book half here', 748, T0 - 6 * DAY, 'HIT_TARGET', T0 - 2 * HR),
      targetRow('INFY', 1700, 1540, '', 1590, T0 - 9 * DAY, 'HIT_STOP', T0 - 27 * MIN),
      targetRow('RELIANCE', 3150, 2790, 'next resistance', 2870, T0 - 3 * DAY, 'ACTIVE', 0),
      targetRow('HAL', 4900, 4380, '', 4560, T0 - 2 * DAY, 'ACTIVE', 0),
      targetRow('ITC', 440, 0, 'buy more if it falls here', 470, T0 - 4 * DAY, 'ACTIVE', 0)
    ];
  }
  function validSym(sym) { return /^[A-Z0-9&\-]{1,20}$/.test(sym) && !/^ZZ/.test(sym); }
  function watchRow(sym) {
    var m = model(sym), i = m.info;
    return { sym: sym, name: i.name, ltp: i.ltp, dayChgPct: i.dayChgPct, spark: m.c.slice(-30), strength: m.strength, stack: i.stack.label, crossText: m.crossText, dataOk: true };
  }
  function defaultWatch() {
    var w = ['DIXON', 'SIEMENS', 'BHARTIARTL'].map(watchRow);
    w.push({ sym: 'NEWLISTCO', name: 'Newly Listed Company Ltd', ltp: 0, dayChgPct: 0, spark: [], strength: 0, stack: 'MIXED', crossText: '', dataOk: false });
    return w;
  }
  function normQ(s) { return String(s || '').toLowerCase().replace(/[^a-z0-9& ]/g, ' ').replace(/\b(ltd|limited)\b/g, ' ').replace(/\s+/g, ' ').trim(); }
  function doSearch(q) {
    var nq = normQ(q), qq = nq.replace(/ /g, '');
    if (qq.length < 2) return [];
    var words = nq.split(' '), out = [];
    UNI.forEach(function (u, idx) {
      var s = u[0].toLowerCase(), nn = normQ(u[1]), nw = nn.split(' '), rank = 99;
      if (s === qq) rank = 0;
      else if (s.indexOf(qq) === 0) rank = 1;
      else if (words.every(function (w) { return nw.some(function (x) { return x.indexOf(w) === 0; }); })) rank = 2;
      else if (s.indexOf(qq) >= 0) rank = 3;
      else if (nn.indexOf(nq) >= 0) rank = 4;
      if (rank < 99) out.push({ r: rank, i: idx, u: u });
    });
    out.sort(function (a, b) { return a.r - b.r || a.i - b.i; });
    return out.slice(0, 25).map(function (x) { return { sym: x.u[0], name: x.u[1], tracked: x.u[3] === 1 }; });
  }

  function baseState() {
    var hs = holdings();
    return {
      now: T0, marketOpen: true, marketNote: 'Open · closes 15:30',
      lastScanAt: T0 - 9 * MIN, lastScanStatus: 'OK · 186/190 stocks', nextScanAt: T0 + 6 * MIN,
      busy: null, settings: defaultSettings(),
      regime: { label: 'BULL', niftyLtp: 24862.35, niftyChgPct: 0.42, note: 'Nifty above 50 & 200 DMA' },
      signals: signals(), trades: trades(), holdings: hs, portfolio: portfolioOf(hs),
      strategies: strategies('default'), scorecard: scorecard(), feed: feed()
    };
  }

  function build() {
    var s = baseState();
    s.watch = [{"sym": "POLYCAB", "name": "Polycab India", "ltp": 7421.5, "dayChgPct": 0.84, "ema10": 7310.18, "ema20": 7287.91, "ema50": 7050.42, "ema100": 6753.57, "ema200": 6234.06, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [6793.67, 6831.83, 6881.61, 6954.13, 7047.55, 7023.8, 7049.12, 7034.39, 7113.22, 7064.86, 7013.98, 7045.74, 7137.69, 7131.28, 7199.21, 7258.65, 7341.03, 7304.14, 7394.78, 7488.31, 7564.42, 7669.29, 7598.78, 7540.88, 7630.58, 7723.86, 7834.68, 7790.09, 7868.05, 7966.51], "status": "CROSSED"}, {"sym": "CUMMINSIND", "name": "Cummins India", "ltp": 3980.1, "dayChgPct": 1.92, "ema10": 3920.4, "ema20": 3908.46, "ema50": 3781.09, "ema100": 3621.89, "ema200": 3343.28, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [3639.8, 3687.02, 3737.32, 3724.68, 3720.53, 3745.94, 3713.11, 3681.23, 3723.49, 3706.23, 3727.53, 3701.36, 3702.01, 3698.98, 3670.37, 3683.35, 3726.91, 3747.97, 3776.0, 3778.39, 3791.95, 3787.94, 3823.6, 3876.73, 3931.37, 3902.19, 3946.06, 3956.24, 3922.06, 3908.4], "status": "CROSSED"}, {"sym": "FEDERALBNK", "name": "Federal Bank", "ltp": 212.4, "dayChgPct": -0.21, "ema10": 209.21, "ema20": 208.58, "ema50": 201.78, "ema100": 193.28, "ema200": 178.42, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [195.44, 198.23, 199.73, 198.15, 198.96, 197.85, 196.22, 198.27, 201.11, 201.76, 202.97, 201.74, 200.29, 199.81, 198.28, 200.11, 199.02, 199.39, 197.78, 196.86, 196.64, 197.21, 196.76, 197.53, 198.55, 197.46, 198.27, 196.89, 198.15, 200.16], "status": "CROSSED"}, {"sym": "BEL", "name": "Bharat Electronics", "ltp": 412.3, "dayChgPct": 1.1, "ema10": 406.12, "ema20": 404.88, "ema50": 391.69, "ema100": 375.19, "ema200": 346.33, "gapPct": 0.31, "daysSince": 1, "barDay": 20261111, "spark": [378.39, 381.31, 378.29, 383.79, 381.26, 379.45, 377.69, 378.49, 375.61, 378.4, 378.06, 383.0, 384.12, 386.53, 385.62, 389.21, 389.31, 394.22, 393.7, 396.02, 394.42, 399.79, 402.83, 403.3, 408.73, 413.43, 410.0, 408.92, 406.58, 404.66], "status": "CROSSED"}, {"sym": "TVSMOTOR", "name": "TVS Motor Company", "ltp": 3105.0, "dayChgPct": 0.4, "ema10": 3058.43, "ema20": 3049.11, "ema50": 2949.75, "ema100": 2825.55, "ema200": 2608.2, "gapPct": 0.31, "daysSince": 2, "barDay": 20261111, "spark": [2862.71, 2847.25, 2879.3, 2855.93, 2895.02, 2913.89, 2953.09, 2941.98, 2956.18, 2954.82, 2980.74, 2985.24, 3014.99, 3046.99, 3083.29, 3097.81, 3108.35, 3142.0, 3159.08, 3187.24, 3231.81, 3278.73, 3275.25, 3293.51, 3302.84, 3292.95, 3292.89, 3314.85, 3342.59, 3363.38], "status": "CROSSED"}];
    s.watch.forEach(function (r) { // keep the EMA-cross rows consistent with the stock page
      var m = model(r.sym), f = m.info.ltp / r.ltp, key;
      ['ltp', 'ema10', 'ema20', 'ema50', 'ema100', 'ema200'].forEach(function (k2) { r[k2] = r2(r[k2] * f); });
      r.spark = r.spark.map(function (x) { return r2(x * f); }); r.dayChgPct = m.info.dayChgPct;
    });
    s.myWatch = defaultWatch();
    s.ideas = ideasList(); s.targets = defaultTargets();
    if (scenario === 'empty') { s.watch = []; s.myWatch = []; s.ideas = []; s.targets = []; }
    if (scenario === 'empty') {
      s.signals = []; s.trades = []; s.holdings = []; s.feed = [];
      s.portfolio = portfolioOf([]);
      s.strategies = strategies('empty');
      s.scorecard = { n: 0, winPct: 0, netRs: 0, avgPct: 0, paperN: 0, paperWinPct: 0, paperAvgPct: 0, recent: [] };
      s.lastScanAt = 0; s.lastScanStatus = 'Not scanned yet';
      s.regime = { label: 'NEUTRAL', niftyLtp: 0, niftyChgPct: 0, note: 'Waiting for first scan' };
    } else if (scenario === 'paused') {
      s.signals = [];
      s.strategies = strategies('paused');
      s.trades = s.trades.filter(function (t) { return t.status === 'CLOSED'; });
      s.holdings = s.holdings.filter(function (h) { return h.verdict === 'HOLD' || h.verdict === 'WATCH'; });
      s.portfolio = portfolioOf(s.holdings);
      s.regime = { label: 'BEAR', niftyLtp: 23104.8, niftyChgPct: -1.12, note: 'Nifty below 50 & 200 DMA' };
      s.feed = s.feed.filter(function (f) { return f.kind !== 'buy' && f.kind !== 'sell'; });
      s.feed.unshift({ ts: T0 - 2 * HR, kind: 'warn', title: 'All strategies paused', body: 'No new buy ideas until a re-test passes. Exit and portfolio calls continue.' });
    } else if (scenario === 'closed') {
      // next weekday 09:15 IST, so the note and the next-scan time agree with the real calendar
      var IST = 19800000, d0 = Math.floor((T0 + IST) / DAY), nextOpen = 0;
      for (var k = 0; k < 8 && !nextOpen; k++) {
        var cand = (d0 + k) * DAY + 9 * HR + 15 * MIN - IST, wd = new Date(cand + IST).getUTCDay();
        if (cand > T0 && wd !== 0 && wd !== 6) nextOpen = cand;
      }
      s.marketOpen = false;
      s.marketNote = 'Closed · opens ' + ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][new Date(nextOpen + IST).getUTCDay()] + ' 09:15';
      s.signals = s.signals.map(function (g) { g.status = g.status === 'NEW' ? 'EXPIRED' : g.status; return g; });
      s.trades = s.trades.filter(function (t) { return t.action === 'HOLD'; });
      s.holdings = s.holdings.filter(function (h) { return h.verdict === 'HOLD' || h.verdict === 'WATCH'; });
      s.portfolio = portfolioOf(s.holdings);
      s.lastScanAt = T0 - 17 * HR; s.nextScanAt = nextOpen;
      s.regime = { label: 'NEUTRAL', niftyLtp: 24512.1, niftyChgPct: -0.06, note: 'Nifty between 50 & 200 DMA' };
    } else if (scenario === 'broken') {
      var LONG = 'Extremely Long Company Name That Never Ends And Keeps Going Without Any Spaces'.replace(/ /g, '') + ' Industries & Holdings (India) Private Limited Formerly Known As Something Else Entirely';
      s = {
        now: T0, marketOpen: 'yes', marketNote: null,
        lastScanAt: 'NaN', lastScanStatus: 'ERR · timeout after 30000ms · <b>query1.finance.yahoo.com</b> unreachable, retried query2, still failing with a very long status line',
        nextScanAt: null, busy: { label: null, pct: 'abc' },
        regime: { label: 'SIDEWAYS', niftyLtp: 'NaN', niftyChgPct: null },
        signals: [
          { id: 'bx-1', sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, strategyLabel: LONG, ltp: 123456789012.55, entry: 99999999.99, entryMax: null, sl: 'NaN', t1: 1e15, t2: 1e18,
            qty: 123456789, investRs: 9.87e15, riskRs: -5, rewardRs: 'lots', holdDays: null, edge: null, reasons: 'not-an-array', status: 'NEW' },
          { id: 'bx-2', status: 'NEW' },
          null,
          { id: 'bx-3', sym: '<img src=x onerror=alert(1)>', name: '<script>alert(1)</script>', status: 'NEW', ltp: 0, entry: 0, entryMax: 0, sl: 0, t1: 0, t2: 0, edge: { n: 'x', winPct: Infinity }, reasons: [null, 42, { a: 1 }, '<b>bold</b>'] }
        ],
        trades: [
          { id: 'bt-1', sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, status: 'OPEN', entry: 100, qty: 1e9, sl: 100, t1: 100, t2: 100, day: 99, maxDays: 0, ltp: 100, pnlRs: -1.5e13, pnlPct: -99999.123, action: 'EXIT', actionNote: LONG + ' ' + LONG },
          { id: 'bt-2', status: 'OPEN', action: 'DANCE' },
          { status: 'OPEN', sym: null, entry: '12.5', ltp: '13', sl: '11', t1: '14', t2: '15', day: '2', maxDays: '6', action: 'BOOK_HALF' },
          'garbage', 42
        ],
        holdings: [
          { sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, qty: 1e12, avg: 1e9, ltp: 1e9, valueRs: 1e21, pnlRs: -1e21, pnlPct: 1e9, dayChgPct: 'NaN', verdict: 'SELL', strength: 22, headline: LONG, stop: -1, bookAt: null, reasons: [LONG, LONG], dataOk: true },
          { sym: 'NOVERDICT', verdict: 'MAYBE', dataOk: 'false' },
          { verdict: 'TRIM', strength: 81 },
          null,
          { sym: 'NODATA', name: 'No data co', qty: 5, avg: 10, dataOk: false }
        ],
        portfolio: { investedRs: 'NaN', valueRs: null, pnlRs: 1e19, sellCount: 'two' },
        strategies: [
          { id: 's1', label: LONG, desc: null, verdict: 'LIVE', testedAt: 'yesterday', stats: null, why: LONG + '. ' + LONG },
          { id: 's2', verdict: 'WHO_KNOWS', stats: { n: 1e12, winPct: 250, avgPct: -1e6, pf: 'NaN', maxDdPct: null } },
          null
        ],
        scorecard: { n: null, winPct: 'NaN', netRs: -1e15, recent: [{ sym: null, closedAt: -5, pnlRs: 'x', paper: 'maybe' }, null, { sym: 'OK', closedAt: 8.7e15, result: LONG, pnlRs: 1e14, pnlPct: 1e6, paper: true }] },
        watch: [{"sym": "POLYCAB", "name": "Polycab India", "ltp": 7421.5, "dayChgPct": 0.84, "ema10": 7310.18, "ema20": 7287.91, "ema50": 7050.42, "ema100": 6753.57, "ema200": 6234.06, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [6793.67, 6831.83, 6881.61, 6954.13, 7047.55, 7023.8, 7049.12, 7034.39, 7113.22, 7064.86, 7013.98, 7045.74, 7137.69, 7131.28, 7199.21, 7258.65, 7341.03, 7304.14, 7394.78, 7488.31, 7564.42, 7669.29, 7598.78, 7540.88, 7630.58, 7723.86, 7834.68, 7790.09, 7868.05, 7966.51], "status": "CROSSED"}, {"sym": "CUMMINSIND", "name": "Cummins India", "ltp": 3980.1, "dayChgPct": 1.92, "ema10": 3920.4, "ema20": 3908.46, "ema50": 3781.09, "ema100": 3621.89, "ema200": 3343.28, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [3639.8, 3687.02, 3737.32, 3724.68, 3720.53, 3745.94, 3713.11, 3681.23, 3723.49, 3706.23, 3727.53, 3701.36, 3702.01, 3698.98, 3670.37, 3683.35, 3726.91, 3747.97, 3776.0, 3778.39, 3791.95, 3787.94, 3823.6, 3876.73, 3931.37, 3902.19, 3946.06, 3956.24, 3922.06, 3908.4], "status": "CROSSED"}, {"sym": "FEDERALBNK", "name": "Federal Bank", "ltp": 212.4, "dayChgPct": -0.21, "ema10": 209.21, "ema20": 208.58, "ema50": 201.78, "ema100": 193.28, "ema200": 178.42, "gapPct": 0.31, "daysSince": 0, "barDay": 20261111, "spark": [195.44, 198.23, 199.73, 198.15, 198.96, 197.85, 196.22, 198.27, 201.11, 201.76, 202.97, 201.74, 200.29, 199.81, 198.28, 200.11, 199.02, 199.39, 197.78, 196.86, 196.64, 197.21, 196.76, 197.53, 198.55, 197.46, 198.27, 196.89, 198.15, 200.16], "status": "CROSSED"}, {"sym": "BEL", "name": "Bharat Electronics", "ltp": 412.3, "dayChgPct": 1.1, "ema10": 406.12, "ema20": 404.88, "ema50": 391.69, "ema100": 375.19, "ema200": 346.33, "gapPct": 0.31, "daysSince": 1, "barDay": 20261111, "spark": [378.39, 381.31, 378.29, 383.79, 381.26, 379.45, 377.69, 378.49, 375.61, 378.4, 378.06, 383.0, 384.12, 386.53, 385.62, 389.21, 389.31, 394.22, 393.7, 396.02, 394.42, 399.79, 402.83, 403.3, 408.73, 413.43, 410.0, 408.92, 406.58, 404.66], "status": "CROSSED"}, {"sym": "TVSMOTOR", "name": "TVS Motor Company", "ltp": 3105.0, "dayChgPct": 0.4, "ema10": 3058.43, "ema20": 3049.11, "ema50": 2949.75, "ema100": 2825.55, "ema200": 2608.2, "gapPct": 0.31, "daysSince": 2, "barDay": 20261111, "spark": [2862.71, 2847.25, 2879.3, 2855.93, 2895.02, 2913.89, 2953.09, 2941.98, 2956.18, 2954.82, 2980.74, 2985.24, 3014.99, 3046.99, 3083.29, 3097.81, 3108.35, 3142.0, 3159.08, 3187.24, 3231.81, 3278.73, 3275.25, 3293.51, 3302.84, 3292.95, 3292.89, 3314.85, 3342.59, 3363.38], "status": "CROSSED"}],
        myWatch: [
          { sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, ltp: 123456789012.55, dayChgPct: 'NaN', spark: [1, 'x', null, 5, 9, 2, 7], strength: 1e9, stack: 'WHATEVER', crossText: LONG + ' ' + LONG, dataOk: true },
          { sym: 'NODATA', name: null, dataOk: false },
          { sym: '<img src=x onerror=alert(1)>', name: '<b>x</b>', ltp: 'abc', spark: 'no', stack: null, crossText: null },
          null, 'junk', { name: 'no symbol' },
          { sym: 'OKCO', name: 'Fine Company Ltd', ltp: 250.5, dayChgPct: -1.2, spark: [250, 251, 249, 248, 247, 246, 245], strength: 50, stack: 'MIXED', crossText: '10 EMA below 20 EMA for 4 days', dataOk: true }
        ],
        ideas: [
          { sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, ltp: 1e12, dayChgPct: 'NaN', score: 1e9, label: 'MAYBE', headline: LONG, entryLow: 'x', entryHigh: null, stop: -1, t1: 1e18, t2: 0, rr: 'lots', horizonDays: 'x', spark: [1, 'x', null], barDay: 5 },
          null, 'junk', { name: 'no symbol' }, { sym: '<img src=x onerror=alert(1)>', name: '<b>x</b>', score: 'NaN', label: null }, { sym: 'OKIDEA', name: 'Fine Co', ltp: 250.5, dayChgPct: 1.2, score: 77, label: 'BUY', entryLow: 248, entryHigh: 252, stop: 238, t1: 270, t2: 285, rr: 1.7, horizonDays: 60, spark: [250, 251, 249, 248, 247, 246, 245], headline: 'Fine' }
        ],
        targets: [
          { sym: 'SUPERLONGSYMBOLNAME-EQ', name: LONG, ltp: 1e12, dayChgPct: 'NaN', target: 1e15, stop: -3, note: LONG, setAt: 'x', refPrice: 0, status: 'HIT_TARGET', hitAt: 8.7e15, toTargetPct: 'NaN', toStopPct: null, progressPct: 1e9 },
          null, 'junk', { name: 'no symbol' }, { sym: '<img src=x onerror=alert(1)>', name: '<b>x</b>', target: 'abc', status: 'WHATEVER' },
          { sym: 'OKTGT', name: 'Fine Co', ltp: 250.5, dayChgPct: -1.2, target: 270, stop: 238, note: 'ok', setAt: T0 - DAY, refPrice: 250, status: 'ACTIVE', hitAt: 0, toTargetPct: 7.8, toStopPct: -5, progressPct: 3 },
          { sym: 'OKSTOP', name: 'Stopped Co', ltp: 90, dayChgPct: -3, target: 120, stop: 95, note: '', setAt: T0 - 2 * DAY, refPrice: 100, status: 'HIT_STOP', hitAt: T0 - HR, toTargetPct: 33, toStopPct: 5.5, progressPct: 0 }
        ],
        feed: [
          { ts: T0 - MIN, kind: 'explode', title: LONG, body: LONG + ' ' + LONG },
          { ts: 'NaN', kind: null, title: null, body: null },
          null,
          { ts: -1, kind: 'buy', title: '<i>html</i> & stuff' },
          { ts: 9e18, kind: 'sell', title: 'Far future' }
        ]
      };
    }
    return s;
  }

  var state = build();
  var device = { notifications: false, batteryUnrestricted: false, exactAlarms: true };
  if (scenario === 'closed') device = { notifications: true, batteryUnrestricted: true, exactAlarms: true };

  function touch() { if (typeof state.now === 'number') state.now = Date.now(); }
  function push(kind, title, body) {
    if (!Array.isArray(state.feed)) state.feed = [];
    state.feed.unshift({ ts: Date.now(), kind: kind, title: title, body: body || '' });
    if (state.feed.length > 100) state.feed.length = 100;
  }
  function notify() { try { if (typeof window.onStateChanged === 'function') window.onStateChanged(); } catch (e) { /* ignore */ } }
  function finish(name, res, delay) {
    setTimeout(function () {
      try { if (typeof window.onActionResult === 'function') window.onActionResult(name, JSON.stringify(res)); } catch (e) { /* ignore */ }
      notify();
    }, delay == null ? 450 : delay);
  }
  function long(name, label, ms, done) {
    if (state.busy) { finish(name, { ok: false, error: 'Already busy: ' + (state.busy.label || 'working') }, 200); return; }
    var start = Date.now();
    state.busy = { label: label, pct: 0 };
    notify();
    var iv = setInterval(function () {
      var p = Math.min(100, Math.round((Date.now() - start) / ms * 100));
      state.busy = { label: label, pct: p };
      if (p >= 100) {
        clearInterval(iv);
        state.busy = null;
        var res = done() || { ok: true };
        finish(name, res, 0);
      }
    }, 300);
  }
  function parseImport(text) {
    var added = 0, skipped = [];
    String(text || '').split(/\r?\n/).forEach(function (line) {
      var raw = line.trim();
      if (!raw) return;
      var cells = raw.split(/[,\t;]+|\s{2,}|\s+(?=[\d.])/).map(function (c) { return c.trim(); }).filter(Boolean);
      var sym = (cells[0] || '').toUpperCase().replace(/[^A-Z0-9&\-]/g, '');
      var qty = parseFloat(cells[1]), avg = parseFloat(cells[2]);
      if (/^(SYMBOL|STOCK|INSTRUMENT|SCRIP)/.test(sym) && !(qty > 0)) return; // header
      if (!sym || !(qty > 0) || !(avg > 0)) { skipped.push(raw); return; }
      upsert(sym, qty, avg); added++;
    });
    return { ok: true, added: added, skipped: skipped };
  }
  function upsert(sym, qty, avg) {
    if (!Array.isArray(state.holdings)) state.holdings = [];
    state.holdings = state.holdings.filter(function (h) { return !h || h.sym !== sym; });
    state.holdings.push({ sym: sym, name: sym, qty: qty, avg: avg, ltp: 0, valueRs: 0, pnlRs: 0, pnlPct: 0, dayChgPct: 0,
      verdict: 'HOLD', strength: 70, headline: '', stop: 0, bookAt: 0, reasons: [], dataOk: false });
    try { state.portfolio = portfolioOf(state.holdings); } catch (e) { /* ignore */ }
  }

  var actions = {
    scanNow: function () {
      long('scanNow', 'Scanning 190 stocks…', 3200, function () {
        state.lastScanAt = Date.now(); state.nextScanAt = Date.now() + 15 * MIN;
        state.lastScanStatus = 'OK · 187/190 stocks';
        push('info', 'Scan finished', 'OK · 187/190 stocks.');
        return { ok: true };
      });
    },
    runBacktest: function () {
      long('runBacktest', 'Backtesting 4 strategies…', 8000, function () {
        (state.strategies || []).forEach(function (s) {
          if (!s) return;
          s.testedAt = Date.now();
          if (s.verdict === 'UNTESTED') {
            s.verdict = 'PAUSED';
            s.stats = { n: 37, winPct: 46, avgPct: -0.31, pf: 0.84, maxDdPct: 11.2, oosN: 9, oosAvgPct: -0.6 };
            s.why = 'Too few trades and negative after charges. Stays paused.';
          }
        });
        push('info', 'Backtest finished', 'Strategy verdicts updated.');
        return { ok: true };
      });
    },
    addHolding: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      if (!sym || !(a.qty > 0) || !(a.avg > 0)) return finish('addHolding', { ok: false, error: 'Symbol, quantity and average price are required' });
      if (sym === 'FAIL') return finish('addHolding', { ok: false, error: 'FAIL is not in the NSE list' });
      upsert(sym, Number(a.qty), Number(a.avg));
      finish('addHolding', { ok: true });
    },
    removeHolding: function (a) {
      state.holdings = (state.holdings || []).filter(function (h) { return !h || h.sym !== a.sym; });
      try { state.portfolio = portfolioOf(state.holdings); } catch (e) { /* ignore */ }
      finish('removeHolding', { ok: true });
    },
    importHoldings: function (a) { finish('importHoldings', parseImport(a.text), 700); },
    takeTrade: function (a) {
      var g = (state.signals || []).filter(function (x) { return x && x.id === a.signalId; })[0];
      if (!g) return finish('takeTrade', { ok: false, error: 'Signal not found or expired' });
      g.status = 'TAKEN';
      var price = a.price > 0 ? Number(a.price) : g.entry, qty = a.qty > 0 ? Number(a.qty) : g.qty;
      state.trades.unshift({ id: 'tr-' + Date.now(), sym: g.sym, name: g.name, strategyLabel: g.strategyLabel, status: 'OPEN', openedAt: Date.now(),
        entry: price, qty: qty, sl: g.sl, t1: g.t1, t2: g.t2, day: 1, maxDays: g.holdDays, ltp: g.ltp,
        pnlRs: Math.round((g.ltp - price) * qty), pnlPct: Math.round((g.ltp - price) / price * 10000) / 100,
        action: 'HOLD', actionNote: 'Just opened. Stop-loss at ₹' + g.sl + '.', t1Done: false, closedAt: 0, exitPrice: 0, result: '' });
      finish('takeTrade', { ok: true });
    },
    skipSignal: function (a) {
      (state.signals || []).forEach(function (x) { if (x && x.id === a.signalId) x.status = 'SKIPPED'; });
      finish('skipSignal', { ok: true });
    },
    closeTrade: function (a) {
      var t = (state.trades || []).filter(function (x) { return x && x.id === a.tradeId; })[0];
      if (!t) return finish('closeTrade', { ok: false, error: 'Trade not found' });
      if (!(a.price > 0)) return finish('closeTrade', { ok: false, error: 'Exit price is required' });
      t.status = 'CLOSED'; t.closedAt = Date.now(); t.exitPrice = Number(a.price);
      t.pnlRs = Math.round((t.exitPrice - t.entry) * t.qty); t.pnlPct = Math.round((t.exitPrice - t.entry) / t.entry * 10000) / 100;
      t.result = 'Closed by you';
      if (state.scorecard && Array.isArray(state.scorecard.recent)) {
        state.scorecard.recent.unshift({ sym: t.sym, closedAt: t.closedAt, result: t.result, pnlRs: t.pnlRs, pnlPct: t.pnlPct, paper: false });
        state.scorecard.n = (state.scorecard.n || 0) + 1; state.scorecard.netRs = (state.scorecard.netRs || 0) + t.pnlRs;
      }
      finish('closeTrade', { ok: true });
    },
    saveSettings: function (a) {
      if (!(a.capital > 0)) return finish('saveSettings', { ok: false, error: 'Capital must be more than zero' });
      state.settings = { capital: a.capital, riskPct: a.riskPct, maxOpenTrades: a.maxOpenTrades, costPct: a.costPct,
        notifyBuy: !!a.notifyBuy, notifySell: !!a.notifySell, notifyBrief: !!a.notifyBrief };
      finish('saveSettings', { ok: true });
    },
    clearFeed: function () { state.feed = []; finish('clearFeed', { ok: true }); },
    search: function (a) {
      var q = String(a.q == null ? '' : a.q);
      setTimeout(function () { finish('search', { ok: true, q: q, results: doSearch(q) }, 0); }, 70);
    },
    stockInfo: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      if (/^ZZ/.test(sym) || !/^[A-Z0-9&\-]{1,20}$/.test(sym)) return finish('stockInfo', { ok: false, sym: sym, error: 'No price data found for ' + sym + '. Check the symbol.' }, 500);
      var res = JSON.parse(JSON.stringify(model(sym).info));
      res.asOf = Date.now(); res.live = scenario !== 'closed';
      res.call = callOf(sym, /^(buy|wait|avoid)$/.test(qs.call || '') ? qs.call : '');
      var mt = (state.targets || []).filter(function (x) { return x && x.sym === sym; })[0];
      res.myTarget = mt ? { target: mt.target, stop: mt.stop, note: mt.note, status: mt.status } : { target: 0, stop: 0, note: '', status: '' };
      res.isHolding = (state.holdings || []).some(function (h) { return h && h.sym === sym; });
      res.inWatch = (state.myWatch || []).some(function (w) { return w && w.sym === sym; });
      finish('stockInfo', res, sym === 'SLOW' ? 6000 : 400);
    },
    getChart: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      if (/^ZZ/.test(sym) || !/^[A-Z0-9&\-]{1,20}$/.test(sym)) return finish('getChart', { ok: false, sym: sym, error: 'No price data found for ' + sym + '. Check the symbol.' }, 350);
      var m = model(sym), nb = [130, 260, 500].indexOf(Number(a.bars)) >= 0 ? Number(a.bars) : 130, tl = function (arr) { return arr.slice(-nb); };
      var h0 = (state.holdings || []).filter(function (x) { return x.sym === sym; })[0];
      var g0 = (state.signals || []).filter(function (x) { return x.sym === sym; })[0];
      var t0 = (state.trades || []).filter(function (x) { return x.sym === sym; })[0];
      var lv = [];
      if (t0 && t0.status === 'OPEN') lv = [{ kind: 'entry', label: 'Bought', v: t0.entry }, { kind: 'stop', label: 'Stop-loss', v: t0.sl }, { kind: 'target', label: 'Target 1', v: t0.t1 }, { kind: 'target', label: 'Target 2', v: t0.t2 }];
      else if (g0) lv = [{ kind: 'entry', label: 'Entry', v: g0.entry }, { kind: 'stop', label: 'Stop-loss', v: g0.sl }, { kind: 'target', label: 'Target 1', v: g0.t1 }, { kind: 'target', label: 'Target 2', v: g0.t2 }];
      else if (h0) lv = [{ kind: 'entry', label: 'Your avg', v: h0.avg }, { kind: 'stop', label: 'Trailing stop', v: h0.stop }, { kind: 'target', label: 'Book near', v: h0.bookAt }];
      var i = m.info;
      setTimeout(function () {
        finish('getChart', { ok: true, sym: sym, name: i.name, d: tl(m.d), o: tl(m.o), h: tl(m.h), l: tl(m.l), c: tl(m.c), v: tl(m.v),
          ema10: tl(m.E[10]), ema20: tl(m.E[20]), ema50: tl(m.E[50]), ema100: tl(m.E[100]), ema200: tl(m.E[200]), sma50: tl(m.E[50]), sma200: tl(m.E[200]), levels: lv,
          stats: { ltp: i.ltp, ret5: i.ret.d5, ret21: i.ret.d21, ret63: i.ret.d63, ret126: i.ret.d126, sma50: i.ema.e50, sma200: i.ema.e200, rsi14: i.rsi14, high52: i.high52, low52: i.low52 } }, 0);
      }, 300);
    },
    addWatch: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      if (!validSym(sym)) return finish('addWatch', { ok: false, error: sym + ' is not an NSE symbol I can find' });
      if (sym === 'FAILWATCH') return finish('addWatch', { ok: false, error: 'Your watchlist is full (50 stocks). Remove one first.' });
      if (!Array.isArray(state.myWatch)) state.myWatch = [];
      if (state.myWatch.some(function (w) { return w && w.sym === sym; })) return finish('addWatch', { ok: true });
      if (state.myWatch.length >= 50) return finish('addWatch', { ok: false, error: 'Your watchlist is full (50 stocks). Remove one first.' });
      state.myWatch.push(watchRow(sym));
      finish('addWatch', { ok: true });
    },
    setTarget: function (a) {
      var sym = String(a.sym || '').toUpperCase(), t = Number(a.target) || 0, sp = Number(a.stop) || 0;
      if (!validSym(sym)) return finish('setTarget', { ok: false, error: sym + ' is not an NSE symbol I can find' });
      if (sym === 'FAILWATCH') return finish('setTarget', { ok: false, error: 'You already have 50 targets. Remove one first.' });
      if (!(t > 0 || sp > 0)) return finish('setTarget', { ok: false, error: 'Enter a target price or a stop price' });
      if (t < 0 || sp < 0 || t > 1e7 || sp > 1e7) return finish('setTarget', { ok: false, error: 'Prices must be above 0' });
      if (!Array.isArray(state.targets)) state.targets = [];
      state.targets = state.targets.filter(function (x) { return !x || x.sym !== sym; });
      if (state.targets.length >= 50) return finish('setTarget', { ok: false, error: 'You already have 50 targets. Remove one first.' });
      state.targets.unshift(targetRow(sym, t, sp, String(a.note || '').slice(0, 80), model(sym).info.ltp, Date.now(), 'ACTIVE', 0));
      finish('setTarget', { ok: true });
    },
    clearTarget: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      state.targets = (state.targets || []).filter(function (x) { return !x || x.sym !== sym; });
      finish('clearTarget', { ok: true });
    },
    removeWatch: function (a) {
      var sym = String(a.sym || '').toUpperCase();
      state.myWatch = (state.myWatch || []).filter(function (w) { return !w || w.sym !== sym; });
      finish('removeWatch', { ok: true });
    }
  };

  window.Android = {
    __mock: true,
    getState: function () { touch(); return JSON.stringify(state); },
    action: function (name, argsJson) {
      var a = {};
      try { a = JSON.parse(argsJson || '{}') || {}; } catch (e) { a = {}; }
      if (actions[name]) {
        try { actions[name](a); } catch (e) { finish(name, { ok: false, error: 'Mock error: ' + (e && e.message) }); }
      } else finish(name, { ok: false, error: 'Unknown action ' + name });
    },
    pickHoldingsFile: function () {
      setTimeout(function () {
        window.Android.action('importHoldings', JSON.stringify({ text: 'Symbol,Qty,Avg\nWIPRO,40,512.3\nAXISBANK,18,1104\nTotal,,\nbad line here\nLT,6,3540.5' }));
      }, 500);
    },
    requestNotifications: function () { setTimeout(function () { device.notifications = true; }, 400); },
    openBatterySettings: function () { setTimeout(function () { device.batteryUnrestricted = true; }, 400); },
    openExactAlarmSettings: function () { setTimeout(function () { device.exactAlarms = true; }, 400); },
    deviceStatus: function () { return JSON.stringify(device); }
  };

  if (qs.busy === 'scan') setTimeout(function () { state.busy = { label: 'Scanning 190 stocks…', pct: 42 }; notify(); }, 0);
  if (qs.busy === 'backtest') setTimeout(function () { state.busy = { label: 'Backtesting 4 strategies…', pct: 67 }; notify(); }, 0);
})();
