/* SwingEdge desktop mock — installed only when the real `Android` bridge is absent.
   Implements every bridge method over an in-memory state.
   URL params: ?scenario=empty|paused|closed|broken  ?busy=scan|backtest */
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
        watch: [{ sym: 'POLYCAB', name: 'Polycab India', ltp: 7421.5, dayChgPct: 0.84, ema10: 7350.2, ema20: 7361.9, gapPct: 0.16, status: 'NEAR' }, { sym: 'CUMMINSIND', name: 'Cummins India', ltp: 3980.1, dayChgPct: 1.92, ema10: 3921.4, ema20: 3915.0, gapPct: -0.16, status: 'CROSSED' }, { sym: 'FEDERALBNK', name: 'Federal Bank', ltp: 212.4, dayChgPct: -0.21, ema10: 210.3, ema20: 211.1, gapPct: 0.38, status: 'NEAR' }],
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
    getChart: function (a) {
      var sym = String(a.sym || ''), seed = 7, i;
      for (i = 0; i < sym.length; i++) seed = (seed * 31 + sym.charCodeAt(i)) % 9973;
      var rnd = function () { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; };
      var h0 = (state.holdings || []).filter(function (x) { return x.sym === sym; })[0];
      var g0 = (state.signals || []).filter(function (x) { return x.sym === sym; })[0];
      var t0 = (state.trades || []).filter(function (x) { return x.sym === sym; })[0];
      var ltp = (h0 && h0.ltp) || (g0 && g0.ltp) || (t0 && t0.ltp) || 500, n = 130, c = [], p = ltp * (0.78 + rnd() * 0.2);
      for (i = 0; i < n; i++) { p *= 1 + (rnd() - 0.47) * 0.035; c.push(p); }
      var k = ltp / c[n - 1], d = [], o = [], h = [], l = [], m50 = [], m200 = [], now = Date.now();
      for (i = 0; i < n; i++) {
        c[i] = +(c[i] * k).toFixed(2); var op = +(i ? c[i - 1] * (1 + (rnd() - 0.5) * 0.01) : c[i]).toFixed(2);
        o.push(op); h.push(+(Math.max(op, c[i]) * (1 + rnd() * 0.012)).toFixed(2)); l.push(+(Math.min(op, c[i]) * (1 - rnd() * 0.012)).toFixed(2));
        d.push(now - (n - 1 - i) * 86400000 * 1.45);
        var s = 0, q; for (q = Math.max(0, i - 49); q <= i; q++) s += c[q]; m50.push(i >= 49 ? +(s / 50).toFixed(2) : 0); m200.push(+(ltp * (0.8 + i / n * 0.1)).toFixed(2));
      }
      var lv = [];
      if (t0 && t0.status === 'OPEN') lv = [{ kind: 'entry', label: 'Bought', v: t0.entry }, { kind: 'stop', label: 'Stop-loss', v: t0.sl }, { kind: 'target', label: 'Target 1', v: t0.t1 }, { kind: 'target', label: 'Target 2', v: t0.t2 }];
      else if (g0) lv = [{ kind: 'entry', label: 'Entry', v: g0.entry }, { kind: 'stop', label: 'Stop-loss', v: g0.sl }, { kind: 'target', label: 'Target 1', v: g0.t1 }, { kind: 'target', label: 'Target 2', v: g0.t2 }];
      else if (h0) lv = [{ kind: 'entry', label: 'Your avg', v: h0.avg }, { kind: 'stop', label: 'Trailing stop', v: h0.stop }, { kind: 'target', label: 'Book near', v: h0.bookAt }];
      var e10 = [], e20 = [], x1 = c[0], x2 = c[0]; for (i = 0; i < n; i++) { x1 += 2 / 11 * (c[i] - x1); x2 += 2 / 21 * (c[i] - x2); e10.push(+x1.toFixed(2)); e20.push(+x2.toFixed(2)); }
      setTimeout(function () {
        finish('getChart', { ok: true, sym: sym, ema10: e10, ema20: e20, name: (h0 && h0.name) || (g0 && g0.name) || sym, d: d, o: o, h: h, l: l, c: c, sma50: m50, sma200: m200, levels: lv,
          stats: { ltp: ltp, ret5: 2.1, ret21: 6.4, ret63: 12.8, ret126: 21.5, sma50: m50[n - 1], sma200: m200[n - 1], rsi14: 58, high52: Math.max.apply(null, h), low52: Math.min.apply(null, l) } });
      }, 300);
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
