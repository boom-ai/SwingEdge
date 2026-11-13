/* SwingEdge — pure rendering. No DOM access in this file: every function takes data and returns an HTML string,
   so it can be fuzz-tested in plain node. All text from state is escaped; any missing/odd value renders as "—". */
(function (root) {
  'use strict';

  var DASH = '—';
  var IST = 19800000, DAYMS = 86400000;
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  /* ---------- safe value helpers ---------- */
  function N(v) {
    if (typeof v === 'number') return isFinite(v) ? v : null;
    if (typeof v === 'string') { var t = v.trim(); if (!t) return null; var n = Number(t); return isFinite(n) ? n : null; }
    return null;
  }
  function T(v) {
    if (typeof v === 'string') return /^\s*(nan|undefined|null|-?infinity)\s*$/i.test(v) ? '' : v;
    if (typeof v === 'number' && isFinite(v)) return String(v);
    return '';
  }
  function O(v) { return (v && typeof v === 'object' && !Array.isArray(v)) ? v : {}; }
  function A(v) { return Array.isArray(v) ? v.filter(function (x) { return x && typeof x === 'object' && !Array.isArray(x); }) : []; }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"'`]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;' }[c];
    });
  }
  function tx(v, fb) { var s = T(v).trim(); return s ? esc(s) : (fb == null ? DASH : fb); }
  function texts(v) { return Array.isArray(v) ? v.map(function (x) { return T(x).trim(); }).filter(Boolean) : []; }

  /* ---------- number formatting (Indian grouping) ---------- */
  function grp(d) {
    if (d.length <= 3) return d;
    return d.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + d.slice(-3);
  }
  function big(a) { // a >= 1e7
    if (a < 1e12) return (a / 1e7).toFixed(2) + ' Cr';
    return a.toExponential(2).replace('e+', 'e');
  }
  function sgn(n, show) { return n < 0 ? '−' : (show && n > 0 ? '+' : ''); }
  function rs(v, signed) {
    var n = N(v); if (n === null) return DASH;
    var a = Math.abs(n), body;
    if (a >= 1e7) body = big(a); else { a = Math.round(a); body = grp(String(a)); if (a === 0) n = 0; }
    return sgn(n, signed) + '₹' + body;
  }
  function pxNum(n) {
    var a = Math.abs(n);
    if (a >= 1e7) return big(a);
    if (a >= 1e5) return grp(String(Math.round(a)));
    var f = a.toFixed(2).split('.');
    return grp(f[0]) + '.' + f[1];
  }
  function px(v) { var n = N(v); if (n === null || n <= 0) return DASH; return '₹' + pxNum(n); }
  function pxBare(v) { var n = N(v); if (n === null || n <= 0) return DASH; return pxNum(n); }
  function pc(v, signed, fixed) {
    var n = N(v); if (n === null) return DASH;
    var a = Math.abs(n), body;
    if (a >= 10000) return sgn(n, signed) + '9,999%+';
    else if (a >= 100) body = grp(String(Math.round(a)));
    else { body = a.toFixed(2); if (!fixed) body = body.replace(/\.?0+$/, ''); if (Number(body) === 0) n = 0; }
    return sgn(n, signed) + body + '%';
  }
  function cnt(v) {
    var n = N(v); if (n === null) return DASH;
    var a = Math.abs(Math.round(n));
    return (n < 0 ? '−' : '') + (a >= 1e7 ? big(a) : grp(String(a)));
  }
  function qtyStr(v) {
    var n = N(v); if (n === null || n <= 0) return DASH;
    if (n >= 1e7) return big(n);
    if (Math.round(n) === n) return grp(String(n));
    return String(Math.round(n * 1000) / 1000);
  }
  function tone(v) { var n = N(v); if (n === null || n === 0) return 'flat'; return n > 0 ? 'up' : 'down'; }
  function inputNum(v) { var n = N(v); if (n === null || n <= 0) return ''; return String(Math.round(n * 100) / 100); }

  /* ---------- time (always IST) ---------- */
  function ts(v) { var n = N(v); return (n !== null && n > 1e11 && n < 4.2e12) ? n : null; }
  function ist(t) { return new Date(t + IST); }
  function p2(n) { return n < 10 ? '0' + n : String(n); }
  function hm(v) { var t = ts(v); if (t === null) return DASH; var d = ist(t); return p2(d.getUTCHours()) + ':' + p2(d.getUTCMinutes()); }
  function dm(v, now) {
    var t = ts(v); if (t === null) return DASH; var d = ist(t);
    var s = d.getUTCDate() + ' ' + MON[d.getUTCMonth()];
    var n = ts(now);
    if (n !== null && ist(n).getUTCFullYear() !== d.getUTCFullYear()) s += ' ' + d.getUTCFullYear();
    return s;
  }
  function dayNo(t) { return Math.floor((t + IST) / DAYMS); }
  function rel(v, now) {
    var t = ts(v), n = ts(now); if (t === null || n === null) return DASH;
    var d = n - t;
    if (d < 0) return upcoming(t, n);
    if (d < 45000) return 'just now';
    if (d < 3600000) return Math.max(1, Math.round(d / 60000)) + ' min ago';
    var dd = dayNo(n) - dayNo(t);
    if (d < 6 * 3600000 || dd === 0) return Math.round(d / 3600000) + ' h ago';
    if (dd === 1) return 'yesterday ' + hm(t);
    if (dd < 7) return dd + ' days ago';
    return dm(t, n);
  }
  function upcoming(t, n) {
    var d = t - n;
    if (d < 60000) return 'in under a minute';
    if (d < 3600000) return 'in ' + Math.round(d / 60000) + ' min';
    var dd = dayNo(t) - dayNo(n);
    if (dd === 0) return 'at ' + hm(t);
    if (dd === 1) return 'tomorrow ' + hm(t);
    if (dd < 7) return WD[ist(t).getUTCDay()] + ' ' + hm(t);
    return dm(t, n);
  }
  function dayLabel(v, now) {
    var t = ts(v), n = ts(now); if (t === null || n === null) return 'Earlier';
    var dd = dayNo(n) - dayNo(t);
    if (dd === 0) return 'Today';
    if (dd === 1) return 'Yesterday';
    if (dd < 0) return dm(t, n);
    return WD[ist(t).getUTCDay()] + ', ' + dm(t, n);
  }

  /* ---------- icons ---------- */
  var IC = {
    today: '<path d="M4 13h3l2.5-7 4 13 2.5-6H20"/>',
    trades: '<path d="M4 19V9M10 19V5M16 19v-8M21 19H3"/>',
    setups: '<path d="M3 17c4 0 5-9 9-9s4 6 9 6"/><path d="M3 9c5 0 6 7 10 7s4-5 8-5"/>',
    chart: '<path d="M4 4.5v15h16"/><path d="M7.5 14.5l3.5-4 3 2.5 4.5-6"/>',
    portfolio: '<rect x="3.5" y="7" width="17" height="12.5" rx="2.5"/><path d="M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7M3.5 12.5h17"/>',
    alerts: '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2.2 2.2 0 0 0 4 0"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" transform="translate(1.2 1.2) scale(.9)"/>',
    up: '<path d="M7 17 17 7M9 7h8v8"/>',
    down: '<path d="M7 7l10 10M17 9v8H9"/>',
    half: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 0 0 17z" fill="currentColor"/>',
    scissors: '<circle cx="6.5" cy="7" r="2.5"/><circle cx="6.5" cy="17" r="2.5"/><path d="M8.6 8.4 20 17M8.6 15.6 20 7"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    flag: '<path d="M5 21V4M5 4h11l-2 4 2 4H5"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>',
    chev: '<path d="M9 6l6 6-6 6"/>',
    warn: '<path d="M12 4 2.8 19.5h18.4zM12 10v4.5M12 17.2v.3"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.3"/>',
    sun: '<circle cx="12" cy="12" r="3.6"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"/>',
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.4-5.5M19.5 4.5v4h-4"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5.500 5.500"/>',
    star: '<path d="M12 3.800l2.600 5.300 5.800.8-4.200 4.100 1 5.800-5.200-2.700-5.200 2.700 1-5.800-4.200-4.100 5.800-.8z"/>',
    file: '<path d="M7 3.5h7l4 4V20.500H7zM14 3.500V8h4"/><path d="M12 11.500v5M9.8 14.500l2.2 2.2 2.2-2.2"/>',
    paste: '<rect x="5.5" y="5" width="13" height="15.5" rx="2"/><path d="M9 5V3.500h6V5M9 11h6M9 15h4"/>',
    trash: '<path d="M5 7h14M9.5 7V4.500h5V7M7 7l.8 12.500h8.400L17 7"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    pause: '<path d="M9 6v12M15 6v12"/>',
    flask: '<path d="M9.5 3.500h5M10.5 3.500v6L5 19a1 1 0 0 0 .9 1.500h12.200A1 1 0 0 0 19 19l-5.5-9.500v-6"/>',
    shield: '<path d="M12 3.5 5 6v5.500c0 4.2 3 7.4 7 9 4-1.6 7-4.8 7-9V6z"/>',
    moon: '<path d="M19.5 14.500A8 8 0 0 1 9.5 4.500a8 8 0 1 0 10 10z"/>',
    minus: '<path d="M6 12h12"/>',
    expand: '<path d="M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7"/>',
    target: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="4.5"/><circle cx="12" cy="12" r=".8" fill="currentColor"/>',
    reset: '<path d="M4.5 12a7.5 7.5 0 1 0 2.4-5.5M4.5 4.5v4h4"/>',
    layers: '<path d="M4 8l8-4 8 4-8 4zM4 12.500l8 4 8-4M4 16.500l8 4 8-4"/>',
    logo: '<path d="M3 17.500l5-6 4 3.5 8.5-10" stroke="#3FD68F" stroke-width="2.4"/><path d="M15.5 5h5v5" stroke="#3FD68F" stroke-width="2.4"/><path d="M3 21h18" stroke="#2C3B4D" stroke-width="2"/>'
  };
  function ic(name) { return '<svg class="ic" viewBox="0 0 24 24" aria-hidden="true">' + (IC[name] || IC.info) + '</svg>'; }

  /* ---------- small shared pieces ---------- */
  function tag(kind, label, icon) {
    return '<span class="tag ' + kind + (icon ? '' : ' plain') + '">' + (icon ? ic(icon) : '') + esc(label) + '</span>';
  }
  function symBtn(sym, name) {
    var x = T(sym).trim();
    if (!x) return '<b>' + DASH + '</b>';
    return '<button type="button" class="symbtn" data-c="stock" data-id="' + esc(x) + '"' + (T(name).trim() ? ' data-name="' + esc(T(name).trim()) + '"' : '') + ' aria-label="Open ' + esc(x) + '"><b>' + esc(x) + '</b>' + ic('chart') + '</button>';
  }
  function btn(cmd, id, label, cls, o) {
    o = o || {};
    var dis = o.disabled || o.busy;
    return '<button type="button" class="btn ' + (cls || '') + '" data-c="' + cmd + '"' + (id != null ? ' data-id="' + esc(id) + '"' : '') +
      (dis ? ' disabled' : '') + '>' + (o.busy ? '<span class="spin"></span>' : (o.icon ? ic(o.icon) : '')) + esc(label) + '</button>';
  }
  function reasonsBlock(key, list, ui, label) {
    var r = texts(list);
    if (!r.length) return '';
    var on = !!O(ui.expanded)[key];
    var h = '<button type="button" class="more' + (on ? ' on' : '') + '" data-c="toggle" data-id="' + esc(key) + '" aria-expanded="' + on + '">' +
      ic('chev') + esc(label || 'Why') + ' (' + r.length + ')</button>';
    if (on) h += '<ul class="reasons">' + r.slice(0, 12).map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>';
    return h;
  }
  function progress(busy) {
    var b = O(busy), p = N(b.pct);
    var indet = p === null;
    if (!indet) p = Math.max(0, Math.min(100, p));
    return '<div class="prog"><div class="pl"><span>' + tx(b.label, 'Working…') + '</span><span class="num">' + (indet ? '' : Math.round(p) + '%') + '</span></div>' +
      '<div class="pb' + (indet ? ' indet' : '') + '"><i' + (indet ? '' : ' style="width:' + p + '%"') + '></i></div></div>';
  }
  function isBusy(S) { return !!(S.busy && typeof S.busy === 'object'); }
  function pend(ui, k) { return !!O(ui.pending)[k]; }

  var VERDICT = {
    SELL: { cls: 'sell', icon: 'down', label: 'SELL', rank: 0 },
    TRIM: { cls: 'trim', icon: 'scissors', label: 'TRIM', rank: 1 },
    WATCH: { cls: 'watch', icon: 'eye', label: 'WATCH', rank: 2 },
    HOLD: { cls: 'hold', icon: 'check', label: 'HOLD', rank: 3 }
  };
  var TACT = {
    EXIT: { cls: 'sell', icon: 'down', label: 'EXIT NOW', rank: 0 },
    TIME_EXIT: { cls: 'sell', icon: 'clock', label: 'TIME EXIT', rank: 1 },
    BOOK_HALF: { cls: 'trim', icon: 'half', label: 'BOOK HALF', rank: 2 },
    HOLD: { cls: 'hold', icon: 'check', label: 'HOLD', rank: 3 }
  };
  var SVERD = {
    LIVE: { cls: 'live', icon: 'check', label: 'LIVE' },
    PAUSED: { cls: 'paused', icon: 'pause', label: 'PAUSED' },
    UNTESTED: { cls: 'untested', icon: 'flask', label: 'UNTESTED' }
  };

  /* ---------- derived lists ---------- */
  function openTrades(S) { return A(S.trades).filter(function (t) { return t.status === 'OPEN'; }); }
  function newSignals(S) { return A(S.signals).filter(function (g) { return g.status === 'NEW'; }); }
  function sortedHoldings(S, key) {
    var num = function (v) { var n = N(v); return n === null ? -Infinity : n; };
    if (key === 'pnl' || key === 'value' || key === 'day' || key === 'strength') {
      var f = key === 'pnl' ? 'pnlPct' : key === 'value' ? 'valueRs' : key === 'strength' ? 'strength' : 'dayChgPct';
      return A(S.holdings).map(function (h, i) { return { h: h, i: i }; }).sort(function (a, b) {
        var na = a.h.dataOk === false ? -Infinity : num(a.h[f]), nb = b.h.dataOk === false ? -Infinity : num(b.h[f]);
        return (nb > na ? 1 : nb < na ? -1 : 0) || a.i - b.i;
      }).map(function (x) { return x.h; });
    }
    return A(S.holdings).map(function (h, i) { return { h: h, i: i }; }).sort(function (a, b) {
      var ra = a.h.dataOk === false ? 4 : (VERDICT[a.h.verdict] ? VERDICT[a.h.verdict].rank : 3.5);
      var rb = b.h.dataOk === false ? 4 : (VERDICT[b.h.verdict] ? VERDICT[b.h.verdict].rank : 3.5);
      return ra - rb || a.i - b.i;
    }).map(function (x) { return x.h; });
  }
  function actItems(S) {
    var out = [];
    openTrades(S).filter(function (t) { return t.action === 'EXIT' || t.action === 'TIME_EXIT' || t.action === 'BOOK_HALF'; })
      .map(function (t, i) { return { t: t, i: i }; })
      .sort(function (a, b) { return TACT[a.t.action].rank - TACT[b.t.action].rank || a.i - b.i; })
      .forEach(function (x) { out.push({ type: 'trade', it: x.t }); });
    ['SELL', 'TRIM'].forEach(function (v) {
      A(S.holdings).forEach(function (h) { if (h.verdict === v && h.dataOk !== false) out.push({ type: 'holding', it: h }); });
    });
    hitTargets(S).forEach(function (t) { out.push({ type: 'target', it: t }); });
    newSignals(S).forEach(function (g) { out.push({ type: 'signal', it: g }); });
    return out;
  }
  function counts(S) {
    var items = actItems(S), c = { sell: 0, buy: 0, tgt: 0, tradeActs: 0, holdActs: 0 };
    items.forEach(function (x) {
      if (x.type === 'target') c.tgt++;
      else if (x.type === 'signal') c.buy++; else { c.sell++; if (x.type === 'trade') c.tradeActs++; else c.holdActs++; }
    });
    return c;
  }
  function deviceIssues(dev) {
    if (!dev || typeof dev !== 'object') return [];
    var out = [];
    if (dev.notifications !== true) out.push('notifications');
    if (dev.batteryUnrestricted !== true) out.push('battery');
    if (dev.exactAlarms !== true) out.push('alarms');
    return out;
  }
  function buyStatus(S, now) {
    var st = A(S.strategies);
    var live = st.filter(function (s) { return s.verdict === 'LIVE'; }).length;
    var tested = st.filter(function (s) { return s.verdict === 'LIVE' || s.verdict === 'PAUSED'; }).length;
    if (!live && !tested) return { kind: 'untested', icon: 'flask', title: 'Strategies are not tested yet',
      text: 'Buy ideas start only after a strategy passes its test on past prices. Run the test once from Strategies; it takes a few minutes.',
      short: 'No buy ideas yet: strategies have not been tested.', link: 'Open Strategies' };
    if (!live) return { kind: 'paused', icon: 'pause', title: 'All strategies are paused',
      text: 'None of them made money after charges in the latest test, so no new buy ideas are being sent. Exit calls and portfolio checks keep running.',
      short: 'No new buys: all strategies are paused after their latest test.', link: 'See why in Strategies' };
    if (S.marketOpen === false) {
      var nx = ts(S.nextScanAt) !== null && ts(now) !== null && ts(S.nextScanAt) > ts(now) ? ' Next scan ' + upcoming(ts(S.nextScanAt), ts(now)) + '.' : '';
      return { kind: 'closed', icon: 'moon', title: 'Market is closed',
        text: (T(S.marketNote).trim() ? T(S.marketNote).trim() + '. ' : '') + 'Nothing to act on until it reopens.' + nx,
        short: 'Market is closed. Buy ideas resume on the next trading day.' };
    }
    var liveNames = st.filter(function (s) { return s.verdict === 'LIVE'; }).map(function (s) { return T(s.label).trim(); }).filter(Boolean);
    var rare = liveNames.length === 1 && /sell-off/i.test(liveNames[0]);
    return { kind: 'waiting', icon: 'clock', title: 'Nothing to act on right now',
      text: (rare ? 'The one rule that passed its test (' + liveNames[0] + ') only buys during market-wide sell-offs, a few weeks a year. ' : '') +
        'Buy and test ideas are checked around 2:45 pm, when the day\'s price bar is nearly complete. Exits and your portfolio are checked every 15 minutes.',
      short: rare ? 'No buy calls: the sell-off bounce rule is waiting for a market-wide sell-off. Test ideas come around 2:45 pm.' : 'No buy ideas right now. They usually arrive around 2:45 pm.' };
  }

  /* ---------- header + tab bar ---------- */
  function header(S, ui) {
    var open = S.marketOpen === true, closed = S.marketOpen === false;
    var note = T(S.marketNote).trim() || (open ? 'Market open' : closed ? 'Market closed' : 'Market status unknown');
    note = note.split(' \u00b7 ').slice(0, 2).join(' \u00b7 '); /* header keeps status + time; the rest is shown on Today */
    var issues = deviceIssues(ui.device).length;
    var h = '<div class="topbar"><div class="brand">' + ic('logo') + 'SwingEdge</div>' +
      '<div class="mkt ' + (open ? 'open' : closed ? 'closed' : '') + '"><i class="dot"></i><span class="clip">' + esc(note) + '</span></div>' +
      '<button type="button" class="iconbtn" data-c="search" aria-label="Search stocks">' + ic('search') + '</button>' +
      '<button type="button" class="iconbtn" data-c="settings" aria-label="Settings' + (issues ? ', phone setup needs attention' : '') + '">' + ic('gear') + (issues ? '<i class="pip"></i>' : '') + '</button></div>';
    if (isBusy(S)) {
      var p = N(S.busy.pct);
      h += '<div class="busyline' + (p === null ? ' indet' : '') + '"><i' + (p === null ? '' : ' style="width:' + Math.max(0, Math.min(100, p)) + '%"') + '></i></div>';
    }
    return h;
  }
  var TABS = [['today', 'Today'], ['setups', 'Setups'], ['trades', 'Trades'], ['portfolio', 'Portfolio'], ['alerts', 'Alerts']];
  function tabbar(S, ui) {
    var c = counts(S), nOpen = openTrades(S).length;
    return TABS.map(function (t) {
      var badge = '';
      if (t[0] === 'today' && (c.sell + c.buy + c.tgt) > 0) badge = '<span class="badge' + (c.sell ? '' : c.tgt ? ' a' : ' g') + '">' + (c.sell + c.buy + c.tgt) + '</span>';
      if (t[0] === 'trades' && c.tradeActs > 0) badge = '<span class="badge">' + c.tradeActs + '</span>';
      if (t[0] === 'portfolio' && c.holdActs > 0) badge = '<span class="badge a">' + c.holdActs + '</span>';
      var on = ui.tab === t[0];
      return '<button type="button" class="tab' + (on ? ' on' : '') + '" data-c="tab" data-id="' + t[0] + '"' + (on ? ' aria-current="page"' : '') + '>' +
        ic(t[0]) + '<span>' + t[1] + (t[0] === 'trades' && nOpen && !badge ? ' · ' + nOpen : '') + '</span>' + badge + '</button>';
    }).join('');
  }

  /* ---------- Today ---------- */
  function banner(ui) {
    var iss = deviceIssues(ui.device);
    if (!iss.length || ui.bannerDismissed) return '';
    var b = '';
    if (iss.indexOf('notifications') >= 0) b += btn('dev-notif', null, 'Allow notifications', 'sm');
    if (iss.indexOf('battery') >= 0) b += btn('dev-battery', null, 'Fix battery limit', 'sm');
    if (iss.indexOf('alarms') >= 0) b += btn('dev-alarm', null, 'Allow exact alarms', 'sm');
    return '<div class="banner"><div class="bh">' + ic('warn') + '<div class="bt"><b>Alerts may not reach you</b>' +
      '<span>' + iss.length + ' phone setting' + (iss.length > 1 ? 's need' : ' needs') + ' fixing.</span></div>' +
      '<button type="button" class="x" data-c="dismiss-banner" aria-label="Hide this">' + ic('x') + '</button></div>' +
      '<div class="btns">' + b + '</div></div>';
  }
  function statusPanel(S, ui, now) {
    var r = O(S.regime), lab = T(r.label).trim().toUpperCase();
    var rc = lab === 'BULL' ? 'bull' : lab === 'BEAR' ? 'bear' : 'neutral';
    var ri = lab === 'BULL' ? 'up' : lab === 'BEAR' ? 'down' : 'minus';
    var busy = isBusy(S), scanning = pend(ui, 'scanNow');
    var chg = N(r.niftyChgPct);
    var h = '<div class="status"><div class="r1"><span class="regime ' + rc + '">' + ic(ri) + '<span>' + (lab ? esc(lab) : 'NO REGIME') + '</span></span>' +
      '<span class="nifty">Nifty <b>' + pxBare(r.niftyLtp) + '</b> <span class="num ' + tone(chg) + '">' + pc(chg, true, true) + '</span></span></div>';
    if (T(r.note).trim()) h += '<div class="rnote">' + esc(T(r.note).trim()) + '</div>';
    var last = ts(S.lastScanAt), next = ts(S.nextScanAt);
    var a = last !== null ? 'Scanned <b>' + esc(rel(last, now)) + '</b>' : '<b>Not scanned yet</b>';
    var nx = next !== null && next > now ? 'Next ' + esc(upcoming(next, now)) : '';
    var stt = T(S.lastScanStatus).trim();
    if (last === null && (!stt || /^not scanned/i.test(stt))) stt = nx ? '' : 'Tap Scan now to run the first scan';
    h += '<div class="r2"><div class="scan"><div class="a">' + a + '</div><div class="b">' + nx + (nx && stt ? ' · ' : '') + esc(stt) + '</div></div>' +
      btn('scan', null, busy || scanning ? 'Working' : 'Scan now', 'sm', { busy: busy || scanning, icon: 'refresh' }) + '</div>';
    if (busy) h += progress(S.busy);
    return h + '</div>';
  }
  function glance(S) {
    var c = counts(S), p = O(S.portfolio), hasH = A(S.holdings).length > 0;
    var day = N(p.dayPnlRs);
    return '<div class="glance">' +
      '<button type="button" class="g" data-c="jump" data-id="first-sell"' + (c.sell ? '' : ' disabled') + '><div class="k ' + (c.sell ? 'down' : '') + '">' + ic('down') + 'To sell</div><div class="v ' + (c.sell ? 'down' : 'z') + '">' + c.sell + '</div></button>' +
      '<button type="button" class="g" data-c="jump" data-id="first-buy"' + (c.buy ? '' : ' disabled') + '><div class="k ' + (c.buy ? 'up' : '') + '">' + ic('up') + 'To buy</div><div class="v ' + (c.buy ? 'up' : 'z') + '">' + c.buy + '</div></button>' +
      '<button type="button" class="g wide" data-c="tab" data-id="portfolio"><div class="k">Portfolio today</div>' +
      (hasH ? '<div class="v s ' + tone(day) + '">' + rs(day, true) + '</div><div class="d">Overall ' + pc(p.pnlPct, true) + '</div>'
        : '<div class="v s z">' + DASH + '</div><div class="d">Add your holdings</div>') +
      '</button></div>';
  }
  function edgeLine(e) {
    e = O(e);
    var n = N(e.n);
    if (n === null || n <= 0) return '<div class="edge none">' + ic('info') + '<span>No tested track record available for this idea. Treat it with extra care.</span></div>';
    return '<div class="edge">' + ic('flask') + '<span>Tested: <b>' + cnt(n) + ' trades</b> · <b>' + pc(e.winPct) + ' won</b> · <b>' + pc(e.avgPct, true) + '</b> avg after charges' +
      (N(e.pf) !== null && N(e.pf) > 0 ? ' · PF ' + (Math.round(N(e.pf) * 100) / 100 > 99 ? '>99' : (Math.round(N(e.pf) * 100) / 100)) : '') + '</span></div>';
  }
  function rrBar(g) {
    var risk = N(g.riskRs), rew = N(g.rewardRs);
    if (risk === null || rew === null || risk <= 0 || rew <= 0) {
      return '<div class="rr"><div class="lab"><span>Risk <b class="down">' + (risk !== null && risk > 0 ? rs(risk) : DASH) + '</b></span><span>Reward at T2 <b class="up">' + (rew !== null && rew > 0 ? rs(rew) : DASH) + '</b></span></div></div>';
    }
    var rp = Math.max(8, Math.min(92, risk / (risk + rew) * 100));
    var ratio = rew / risk;
    return '<div class="rr"><div class="bar" role="img" aria-label="Risk versus reward"><i class="r" style="width:' + rp.toFixed(1) + '%"></i><i class="w" style="width:' + (100 - rp).toFixed(1) + '%"></i></div>' +
      '<div class="lab"><span>Risk <b class="down">' + rs(risk) + '</b></span><span>Reward at T2 <b class="up">' + rs(rew) + '</b> <span class="num mut">1 : ' + (ratio > 99 ? '>99' : ratio.toFixed(1)) + '</span></span></div></div>';
  }
  function signalCard(g, ui, now) {
    var id = T(g.id), busyTake = pend(ui, 'takeTrade:' + id), busySkip = pend(ui, 'skipSignal:' + id);
    var e1 = px(g.entry), e2 = px(g.entryMax);
    var zone = e1 === DASH && e2 === DASH ? DASH : (e2 === DASH || e2 === e1 ? e1 : e1 + ' – ' + pxBare(g.entryMax));
    var vt = ts(g.validTill), hold = N(g.holdDays);
    var facts = '<span><b>' + qtyStr(g.qty) + '</b> shares</span><span>Invest <b>' + rs(g.investRs) + '</b></span>' +
      '<span>Hold up to <b>' + (hold !== null && hold > 0 ? cnt(hold) : DASH) + '</b> days</span>';
    if (vt !== null) facts += vt > now ? '<span>Valid till <b>' + hm(vt) + '</b></span>' : '<span class="mut">Validity passed ' + esc(rel(vt, now)) + '</span>';
    var exp = /^EXPERIMENTAL/i.test(T(g.strategyLabel)); /* strategy failed its test: shown as a paper idea, not a buy call */
    return '<article class="card rail ' + (exp ? 'k-trim' : 'k-buy') + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + (exp ? tag('trim', 'TEST IDEA') : tag('buy', 'BUY', 'up')) + symBtn(g.sym, g.name) + '</div>' +
      '<div class="sub">' + tx(g.name, '') + (T(g.name).trim() && T(g.strategyLabel).trim() ? ' · ' : '') + tx(g.strategyLabel, '') + '</div></div>' +
      '<div class="px"><div class="ltp">' + px(g.ltp) + '</div><div class="ltpl">last price</div></div></div>' +
      '<div class="zone"><span class="k">Buy between</span><span class="v">' + zone + '</span></div>' +
      '<div class="lv"><div><div class="k">Stop-loss</div><div class="v down">' + pxBare(g.sl) + '</div></div>' +
      '<div><div class="k">Target 1</div><div class="v">' + pxBare(g.t1) + '</div></div>' +
      '<div><div class="k">Target 2</div><div class="v">' + pxBare(g.t2) + '</div></div></div>' +
      '<div class="facts">' + facts + '</div>' +
      rrBar(g) + edgeLine(g.edge) +
      reasonsBlock('sig:' + id, g.reasons, ui, 'Why this stock') +
      '<div class="btns">' + btn('take', id, exp ? 'I bought it anyway' : 'I bought it', (exp ? '' : 'pri ') + 'grow', { disabled: !id || busySkip, busy: busyTake, icon: 'check' }) +
      btn('skip', id, 'Skip', 'ghost', { disabled: !id || busyTake, busy: busySkip }) + '</div>' +
      '</div></article>';
  }
  function tradeActCard(t, ui) {
    var a = TACT[t.action] || TACT.HOLD, id = T(t.id);
    var exit = t.action === 'EXIT' || t.action === 'TIME_EXIT';
    return '<article class="card rail k-' + (a.cls === 'sell' ? 'sell' : 'trim') + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + tag(a.cls, a.label, a.icon) + symBtn(t.sym, t.name) + '</div>' +
      '<div class="sub">Your trade · ' + qtyStr(t.qty) + ' shares at ' + px(t.entry) + '</div></div>' +
      '<div class="px"><div class="ltp">' + px(t.ltp) + '</div><div class="ltpl num ' + tone(t.pnlRs) + '">' + rs(t.pnlRs, true) + ' (' + pc(t.pnlPct, true) + ')</div></div></div>' +
      '<div class="note">' + tx(t.actionNote, exit ? 'Sell this position now.' : 'Target 1 reached. Sell half and hold the rest.') + '</div>' +
      '<div class="btns">' + (exit ? btn('close', id, 'Close trade', 'dan grow', { disabled: !id, busy: pend(ui, 'closeTrade:' + id) }) : '') +
      btn('tab', 'trades', 'See trade', exit ? 'ghost' : 'grow') + '</div>' +
      '</div></article>';
  }
  function holdingActCard(h, ui) {
    var v = VERDICT[h.verdict] || VERDICT.HOLD, sym = T(h.sym);
    var sell = h.verdict === 'SELL';
    var facts = '<span><b>' + qtyStr(h.qty) + '</b> shares</span><span>Value <b>' + rs(h.valueRs) + '</b></span>';
    if (N(h.bookAt) > 0) facts += '<span>Book near <b>' + px(h.bookAt) + '</b></span>';
    if (N(h.stop) > 0) facts += '<span>Stop <b>' + px(h.stop) + '</b></span>';
    return '<article class="card rail k-' + v.cls + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + tag(v.cls, v.label, v.icon) + symBtn(h.sym, h.name) + '</div>' +
      '<div class="sub">Your holding' + (T(h.name).trim() ? ' · ' + esc(T(h.name).trim()) : '') + '</div></div>' +
      '<div class="px"><div class="ltp">' + px(h.ltp) + '</div><div class="ltpl num ' + tone(h.pnlRs) + '">' + rs(h.pnlRs, true) + ' (' + pc(h.pnlPct, true) + ')</div></div></div>' +
      '<div class="note">' + tx(h.headline, sell ? 'The checks say sell this holding.' : 'The checks say reduce this holding.') + '</div>' +
      '<div class="facts">' + facts + '</div>' +
      reasonsBlock('act-hold:' + sym, h.reasons, ui, 'Why') +
      '<div class="btns">' + (sell ? btn('sold', sym, 'I sold it', 'dan grow', { disabled: !sym, busy: pend(ui, 'removeHolding:' + sym) }) : '') +
      btn('tab', 'portfolio', 'See in portfolio', sell ? 'ghost' : 'grow') + '</div>' +
      '</div></article>';
  }
  function hintLine(bs) {
    return '<div class="hint hintpad">' + ic(bs.icon) + '<span class="t">' + esc(bs.short) +
      (bs.link ? ' <button type="button" class="linkbtn" data-c="strategies">' + esc(bs.link) + '</button>' : '') + '</span></div>';
  }
  function plan(S, items, bs) {
    var hs = A(S.holdings).filter(function (x) { return x.dataOk !== false; });
    var sells = 0, exits = 0, buys = 0, tests = 0, tgts = 0;
    items.forEach(function (x) {
      if (x.type === 'trade') exits++; else if (x.type === 'holding') sells++; else if (x.type === 'target') tgts++;
      else if (/^EXPERIMENTAL/i.test(T(x.it.strategyLabel))) tests++; else buys++;
    });
    var li = [];
    if (exits) li.push(['down', 'sell', 'Act on ' + exits + ' open trade' + (exits > 1 ? 's' : '') + ' (exit or book)']);
    if (sells) li.push(['down', 'sell', sells + ' holding' + (sells > 1 ? 's' : '') + ' flagged to sell or trim']);
    if (tgts) li.push(['target', 'trim', tgts + ' price target' + (tgts > 1 ? 's' : '') + ' you set ' + (tgts > 1 ? 'were' : 'was') + ' reached']);
    if (buys) li.push(['up', 'buy', buys + ' buy call' + (buys > 1 ? 's' : '') + ' from a rule that passed its test']);
    if (tests) li.push(['flask', 'trim', tests + ' test idea' + (tests > 1 ? 's' : '') + ' (unproven rule, paper-tracked)']);
    if (!exits && !sells && !tgts) li.push(['check', 'ok', hs.length ? 'Nothing to sell: no holding is flagged' : 'Add your holdings to get sell calls']);
    if (!buys && !tests) li.push(['clock', 'mut', bs.short]);
    var weak = hs.filter(function (x) { return x.verdict === 'WATCH'; }).length;
    if (weak) li.push(['info', 'mut', weak + ' holding' + (weak > 1 ? 's are' : ' is') + ' weak (on watch), no action yet']);
    return '<div class="plan"><div class="pk">Today\'s plan</div><ul>' + li.map(function (x) {
      return '<li class="' + x[1] + '">' + ic(x[0]) + '<span>' + esc(x[2]) + '</span></li>';
    }).join('') + '</ul></div>';
  }
  function movers(S) {
    var hs = A(S.holdings).filter(function (x) { return x.dataOk !== false && N(x.dayChgPct) !== null && N(x.ltp) > 0; });
    if (hs.length < 2) return '';
    hs = hs.slice().sort(function (a, b) { return N(b.dayChgPct) - N(a.dayChgPct); });
    var pick = hs.length <= 6 ? hs : hs.slice(0, 3).concat(hs.slice(-3));
    return '<div class="sec"><h2>Your holdings today</h2></div><div class="card mv">' + pick.map(function (x) {
      return '<button type="button" class="mvr" data-c="stock" data-id="' + esc(T(x.sym)) + '" data-name="' + esc(T(x.name)) + '"><b>' + tx(x.sym) + '</b><span class="num">' + px(x.ltp) + '</span><span class="num ' + tone(x.dayChgPct) + '">' + pc(x.dayChgPct, true, true) + '</span></button>';
    }).join('') + '</div>';
  }
  function watchItems(S) { return A(S.watch).filter(function (x) { return T(x.sym).trim() && N(x.ltp) > 0; }); }
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function barDayText(v) {
    var n = N(v);
    if (n === null || n < 20000101 || n > 99991231) return '';
    var m = Math.floor(n / 100) % 100, d = n % 100;
    return m >= 1 && m <= 12 && d >= 1 && d <= 31 ? d + ' ' + MON[m - 1] : '';
  }
  function spark(arr, up) {
    var v = (Array.isArray(arr) ? arr : []).map(N).filter(function (x) { return x !== null && x > 0; });
    if (v.length < 5) return '<span class="spk"></span>';
    var lo = Math.min.apply(null, v), hi = Math.max.apply(null, v), W = 72, H = 28, pts = [], i;
    if (!(hi > lo)) hi = lo + 1;
    for (i = 0; i < v.length; i++) pts.push((i / (v.length - 1) * W).toFixed(1) + ',' + (H - 2 - (v[i] - lo) / (hi - lo) * (H - 4)).toFixed(1));
    return '<svg class="spk ' + (up ? 'u' : 'd') + '" viewBox="0 0 ' + W + ' ' + H + '" aria-hidden="true"><polyline points="' + pts.join(' ') + '"/></svg>';
  }
  function watchList(S) {
    var ws = watchItems(S), n0 = ws.filter(function (x) { return N(x.daysSince) === 0; }).length;
    return '<button type="button" class="card golink" data-c="tab" data-id="setups">' + ic('setups') +
      '<span class="gt"><b>EMA setups</b><span>' + (ws.length ? (n0 ? n0 + ' crossed on the latest bar' : 'None on the latest bar') + (ws.length > n0 ? ' · ' + (ws.length - n0) + ' in the 2 days before' : '') : 'No fresh 10/20 EMA cross right now') + '</span></span>' + ic('chev') + '</button>';
  }
  function emaNum(v) { /* compact EMA value for tight grids: no rupee sign, no decimals above 1,000 */
    var n = N(v); if (n === null || n <= 0) return DASH;
    if (n >= 1e7) return big(n);
    return n >= 1000 ? grp(String(Math.round(n))) : pxNum(n);
  }
  var STACKTAG = { BULLISH: ['hold', 'BULLISH'], MIXED: ['trim', 'MIXED'], BEARISH: ['sell', 'BEARISH'] };
  function stackTag(label, icon) {
    var k = T(label).trim().toUpperCase(), m = STACKTAG[k];
    return m ? tag(m[0], m[1], icon) : (k ? tag('neutral', k.slice(0, 10)) : '');
  }
  function setupRow(x) {
    var sv = (Array.isArray(x.spark) ? x.spark : []).map(N), up = sv.length > 1 && sv[sv.length - 1] !== null && sv[0] !== null ? sv[sv.length - 1] >= sv[0] : true;
    var nm = T(x.name).trim(), sym = T(x.sym).trim();
    var ema = [['10', x.ema10], ['20', x.ema20], ['50', x.ema50], ['100', x.ema100], ['200', x.ema200]];
    return '<button type="button" class="srow" data-c="stock" data-id="' + esc(sym) + '" data-name="' + esc(nm) + '">' +
      '<span class="s1"><span class="wn"><b>' + esc(sym) + '</b><span class="ws">' + (nm && nm !== sym ? esc(nm) : '10 EMA ' + (N(x.gapPct) === null ? '' : Math.abs(N(x.gapPct)).toFixed(2) + '% ') + 'above 20 EMA') + '</span></span>' +
      spark(x.spark, up) + '<span class="sp"><span class="num">' + px(x.ltp) + '</span><span class="num ' + tone(x.dayChgPct) + '">' + pc(x.dayChgPct, true, true) + '</span></span></span>' +
      '<span class="s2 g5">' + ema.map(function (e) { return '<span><i>' + e[0] + ' EMA</i><b class="num">' + emaNum(e[1]) + '</b></span>'; }).join('') + '</span></button>';
  }

  /* ---------- My watchlist (top of the Setups tab) ---------- */
  function wrow(x, edit, ui) {
    var sym = T(x.sym).trim(); if (!sym) return '';
    var nm = T(x.name).trim(), ok = x.dataOk !== false;
    var sv = (Array.isArray(x.spark) ? x.spark : []).map(N), up = sv.length > 1 && sv[sv.length - 1] !== null && sv[0] !== null ? sv[sv.length - 1] >= sv[0] : true;
    var l2 = ok ? (T(x.crossText).trim() ? esc(T(x.crossText).trim()) : '<span class="mut">No 10/20 EMA reading yet</span>') : '<span class="mut">No data yet. It fills in after the next scan.</span>';
    var main = '<button type="button" class="srow wmain" data-c="stock" data-id="' + esc(sym) + '" data-name="' + esc(nm) + '">' +
      '<span class="s1"><span class="wn"><b>' + esc(sym) + '</b><span class="ws">' + (nm && nm !== sym ? esc(nm) : '&nbsp;') + '</span></span>' +
      (ok ? spark(x.spark, up) : '<span class="spk"></span>') +
      '<span class="sp"><span class="num">' + (ok ? px(x.ltp) : DASH) + '</span><span class="num ' + (ok ? tone(x.dayChgPct) : 'flat') + '">' + (ok ? pc(x.dayChgPct, true, true) : '') + '</span></span></span>' +
      '<span class="s2 wl2"><span class="wx clip">' + l2 + '</span>' + (ok ? stackTag(x.stack) : '') + '</span></button>';
    var rm = edit ? '<button type="button" class="wrm" data-c="watch-remove" data-id="' + esc(sym) + '" aria-label="Remove ' + esc(sym) + ' from watchlist"' + (pend(ui, 'removeWatch:' + sym) ? ' disabled' : '') + '>' + ic('trash') + '</button>' : '';
    return '<div class="wrow' + (edit ? ' ed' : '') + '">' + main + rm + '</div>';
  }
  function myWatchSection(S, ui) {
    var rows = A(S.myWatch).filter(function (x) { return T(x.sym).trim(); }), edit = ui.watchEdit === true && rows.length > 0;
    var h = '<div class="sec wsec"><h2>My watchlist</h2>' + (rows.length ? '<span class="cnt">' + rows.length + '</span>' : '') + '<span class="sp"></span>' +
      (rows.length ? '<button type="button" class="linkbtn" data-c="watch-edit">' + (edit ? 'Done' : 'Edit') + '</button>' : '') +
      (rows.length ? btn('search', null, 'Add stock', 'sm', { icon: 'plus' }) : '') + '</div>';
    if (!rows.length) {
      return h + '<div class="empty"><div class="ei">' + ic('star') + '</div><h3>Your watchlist is empty</h3>' +
        '<p>Search any NSE stock, open it and tap “Add to watchlist”. Stocks you add get a fresh price on every scan and their 10/20 EMA status shows here.</p>' +
        '<div class="btns">' + btn('search', null, 'Find a stock', 'sm', { icon: 'search' }) + '</div></div>';
    }
    return h + '<div class="card mv">' + rows.map(function (x) { return wrow(x, edit, ui); }).join('') + '</div>';
  }
  function setups(S, ui) {
    var ws = watchItems(S), asof = '', h = trackRow(S) + ideasBlock(S, 'setups') + myWatchSection(S, ui);
    ws.forEach(function (x) { if (!asof) asof = barDayText(x.barDay); });
    h += '<div class="card setuphd"><h2>Fresh EMA crossovers</h2>' +
      '<div class="crit"><span>' + ic('check') + '10 EMA crossed above 20 EMA</span><span>' + ic('check') + '10 EMA above the 50, 100 and 200 EMA</span><span>' + ic('check') + 'Traded ₹25 crore+ a day</span></div>' +
      (asof ? '<div class="asof">Latest bar: ' + asof + (S.marketOpen === true ? ' · today’s bar is still forming until 3:30 pm' : '') + '</div>' : '') + '</div>';
    if (!ws.length) {
      return h + '<div class="empty"><div class="ei">' + ic('setups') + '</div><h3>No fresh crossovers</h3><p>No liquid stock has its 10-day EMA crossing above the 20-day while above the 50, 100 and 200 right now. ' +
        (N(S.lastScanAt) > 0 ? 'The list refreshes with each scan; tap “Scan now” on Today for live prices.' : 'Run a scan on the Today tab to fill this list.') + '</p></div>';
    }
    [[0, 'Day 0 · crossed on the latest bar'], [1, '1 day ago'], [2, '2 days ago']].forEach(function (grp) {
      var it = ws.filter(function (x) { var d = N(x.daysSince); return (d === null ? 0 : d) === grp[0]; });
      if (!it.length && grp[0] !== 0) return;
      h += '<div class="sec"><h2>' + grp[1] + '</h2>' + (it.length ? '<span class="cnt">' + it.length + '</span>' : '') + '</div>';
      h += it.length ? '<div class="card mv">' + it.map(setupRow).join('') + '</div>' : '<div class="hint hintpad">' + ic('info') + '<span class="t">Nothing crossed on the latest bar.</span></div>';
    });
    return h + '<div class="hint hintpad">' + ic('info') + '<span class="t">A screener for your own chart reading, not buy calls. On NSE data from 2013 this setup did no better than a random uptrend stock over the next 6 days; held about a month it averaged +1.2% per trade against +0.7% for a random uptrend stock, with losing years. Tap a row for the chart.</span></div>';
  }
  function today(S, ui) {
    var now = ui.now, items = actItems(S), bs = buyStatus(S, now);
    var h = banner(ui) + statusPanel(S, ui, now) + glance(S) + plan(S, items, bs);
    h += '<div class="sec"><h2>Act now</h2>' + (items.length ? '<span class="cnt">' + items.length + '</span>' : '') + '</div>';
    if (!items.length) {
      h += '<div class="empty"><div class="ei">' + ic(bs.icon) + '</div><h3>' + esc(bs.title) + '</h3><p>' + esc(bs.text) + '</p>' +
        (bs.link ? '<div class="btns">' + btn('strategies', null, bs.link, 'sm') + '</div>' : '') + '</div>';
      var ot = openTrades(S).length;
      h += ideasBlock(S, 'today') + watchList(S) + movers(S);
      if (ot) h += '<div class="hint hintpad">' + ic('check') + '<span class="t">' + ot + ' open trade' + (ot > 1 ? 's are' : ' is') + ' on HOLD. Nothing to do on ' + (ot > 1 ? 'them' : 'it') + '.</span></div>';
      return h;
    }
    var hasBuy = false, hasSell = false;
    items.forEach(function (x) {
      var c;
      if (x.type === 'trade') c = tradeActCard(x.it, ui);
      else if (x.type === 'holding') c = holdingActCard(x.it, ui);
      else if (x.type === 'target') c = targetHitCard(x.it, ui, now);
      else c = signalCard(x.it, ui, now);
      if (x.type === 'target') { /* no jump anchor */ }
      else if (x.type === 'signal') { if (!hasBuy) c = c.replace('<article ', '<article id="first-buy" '); hasBuy = true; }
      else { if (!hasSell) c = c.replace('<article ', '<article id="first-sell" '); hasSell = true; }
      h += c;
    });
    if (!hasBuy) h += hintLine(bs);
    return h + ideasBlock(S, 'today') + watchList(S);
  }

  /* ---------- Trades ---------- */
  function trackPos(t) {
    var sl = N(t.sl), en = N(t.entry), t1 = N(t.t1), t2 = N(t.t2), l = N(t.ltp);
    if (sl === null || en === null || t1 === null || t2 === null || l === null || l <= 0) return null;
    if (!(sl <= en && en < t1 && t1 < t2) || sl <= 0) return null;
    if (l < sl || (l === sl && sl < en)) return 0;
    if (l <= en && sl === en) return 100 / 3;   // stop already moved up to entry
    if (l >= t2) return 100;
    var seg = [[sl, en, 0], [en, t1, 100 / 3], [t1, t2, 200 / 3]];
    for (var i = 0; i < 3; i++) if (l <= seg[i][1] && seg[i][1] > seg[i][0]) return seg[i][2] + (l - seg[i][0]) / (seg[i][1] - seg[i][0]) * (100 / 3);
    return 100;
  }
  function track(t) {
    var pos = trackPos(t), tn = tone(N(t.ltp) !== null && N(t.entry) !== null ? N(t.ltp) - N(t.entry) : null);
    var h = '<div class="track"><div class="rail"><i class="loss"></i><i class="gain"></i>' +
      '<i class="tick" style="left:0"></i><i class="tick" style="left:33.333%"></i><i class="tick" style="left:66.667%"></i><i class="tick" style="left:100%"></i>';
    if (pos !== null) {
      h += '<i class="mk ' + tn + '" style="left:' + pos.toFixed(1) + '%"></i>' +
        '<span class="flag ' + (pos < 16 ? 'l' : pos > 84 ? 'r' : '') + ' ' + tn + '" style="left:' + pos.toFixed(1) + '%">Now ' + pxBare(t.ltp) + '</span>';
    }
    h += '</div></div><div class="tl">' +
      '<div><div class="k">Stop-loss</div><div class="v down">' + pxBare(t.sl) + '</div></div>' +
      '<div><div class="k">Entry</div><div class="v">' + pxBare(t.entry) + '</div></div>' +
      '<div><div class="k' + (t.t1Done === true ? ' done' : '') + '">Target 1' + (t.t1Done === true ? ' ✓' : '') + '</div><div class="v">' + pxBare(t.t1) + '</div></div>' +
      '<div><div class="k">Target 2</div><div class="v">' + pxBare(t.t2) + '</div></div></div>';
    return h;
  }
  function dayPips(t) {
    var d = N(t.day), m = N(t.maxDays);
    if (d === null || m === null || m <= 0 || d < 0) return '<span>Day ' + (d !== null && d >= 0 ? cnt(d) : DASH) + (m !== null && m > 0 ? ' of ' + cnt(m) : '') + '</span>';
    var h = '<span>Day <b class="num">' + cnt(d) + '</b> of <b class="num">' + cnt(m) + '</b></span>';
    if (m <= 12) {
      h += '<span class="pips" aria-hidden="true">';
      for (var i = 1; i <= m; i++) h += '<i class="' + (i <= d ? (d >= m ? 'f last' : 'f') : '') + '"></i>';
      h += '</span>';
    }
    return h;
  }
  function tradeCard(t, ui) {
    var a = TACT[t.action], id = T(t.id);
    var unknown = !a; if (!a) a = { cls: 'neutral', icon: 'info', label: T(t.action).trim().toUpperCase().slice(0, 14) || 'NO CALL' };
    var rail = a.cls === 'sell' ? 'k-sell' : a.cls === 'trim' ? 'k-trim' : '';
    return '<article class="card rail ' + rail + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + symBtn(t.sym, t.name) + '</div>' +
      '<div class="sub">' + qtyStr(t.qty) + ' shares at ' + px(t.entry) + (T(t.strategyLabel).trim() ? ' · ' + esc(T(t.strategyLabel).trim()) : '') + '</div></div>' +
      '<div class="px pnl"><div class="a ' + tone(t.pnlRs) + '">' + rs(t.pnlRs, true) + '</div><div class="p ' + tone(t.pnlPct) + '">' + pc(t.pnlPct, true, true) + '</div></div></div>' +
      track(t) +
      '<div class="days">' + dayPips(t) + '<span class="sp"></span><span>Opened ' + dm(t.openedAt, ui.now) + '</span></div>' +
      '<div class="act ' + (a.cls === 'sell' ? 'sell' : a.cls === 'trim' ? 'trim' : '') + '"><div class="ah">' + tag(a.cls, a.label, a.icon) + '</div>' +
      '<div class="an">' + tx(t.actionNote, unknown ? 'No instruction received for this trade yet.' : 'No note.') + '</div></div>' +
      '<div class="btns">' + btn('close', id, 'Close trade', (a.cls === 'sell' ? 'dan' : '') + ' grow', { disabled: !id, busy: pend(ui, 'closeTrade:' + id) }) + '</div>' +
      '</div></article>';
  }
  function scorecard(S) {
    var s = O(S.scorecard), n = N(s.n), pn = N(s.paperN);
    return '<div class="card"><div class="score">' +
      '<div><div class="t">' + tag('info', 'REAL') + 'You took</div>' +
      '<div class="big ' + tone(n > 0 ? s.netRs : null) + '">' + (n > 0 ? rs(s.netRs, true) : DASH) + '</div><div class="l">net after charges</div>' +
      '<div class="row"><b>' + (n !== null && n >= 0 ? cnt(n) : DASH) + '</b> closed · <b>' + (n > 0 ? pc(s.winPct) : DASH) + '</b> won</div>' +
      '<div class="row">Avg <b>' + (n > 0 ? pc(s.avgPct, true) : DASH) + '</b> per trade</div></div>' +
      '<div><div class="t">' + tag('neutral', 'PAPER') + 'Not taken</div>' +
      '<div class="big ' + tone(pn > 0 ? s.paperAvgPct : null) + '">' + (pn > 0 ? pc(s.paperAvgPct, true) : DASH) + '</div><div class="l">avg per idea, no money in</div>' +
      '<div class="row"><b>' + (pn !== null && pn >= 0 ? cnt(pn) : DASH) + '</b> tracked · <b>' + (pn > 0 ? pc(s.paperWinPct) : DASH) + '</b> won</div>' +
      '</div>' +
      '</div></div>';
  }
  function recentRows(S, ui) {
    var r = A(O(S.scorecard).recent).slice(0, 40);
    if (!r.length) return '<div class="hint">' + ic('info') + '<span class="t">Closed trades will be listed here, real and paper kept apart.</span></div>';
    return '<div class="rows">' + r.map(function (x) {
      var paper = x.paper === true;
      return '<div class="row2"><div class="l"><div class="a"><b>' + tx(x.sym) + '</b>' + (paper ? tag('neutral', 'PAPER') : tag('info', 'REAL')) + '</div>' +
        '<div class="b">' + dm(x.closedAt, ui.now) + (T(x.result).trim() ? ' · ' + esc(T(x.result).trim()) : '') + '</div></div>' +
        '<div class="r"><div class="a ' + tone(x.pnlPct) + '">' + pc(x.pnlPct, true, true) + '</div><div class="b">' + (paper ? 'paper' : rs(x.pnlRs, true)) + '</div></div></div>';
    }).join('') + '</div>';
  }
  function trades(S, ui) {
    var open = openTrades(S).map(function (t, i) { return { t: t, i: i }; }).sort(function (a, b) {
      var ra = TACT[a.t.action] ? TACT[a.t.action].rank : 4, rb = TACT[b.t.action] ? TACT[b.t.action].rank : 4;
      return ra - rb || a.i - b.i;
    });
    var max = N(O(S.settings).maxOpenTrades);
    var h = targetsSection(S, ui) + '<div class="sec"><h2>Open trades</h2><span class="cnt">' + open.length + (max !== null && max > 0 ? ' of ' + cnt(max) + ' max' : '') + '</span></div>';
    if (!open.length) {
      h += '<div class="empty"><div class="ei">' + ic('trades') + '</div><h3>No open trades</h3>' +
        '<p>When you tap “I bought it” on a buy idea, the trade appears here with its stop-loss, targets and a daily call to hold, book half or exit.</p></div>';
    } else open.forEach(function (x) { h += tradeCard(x.t, ui); });
    h += '<div class="sec"><h2>Scorecard</h2></div>' + scorecard(S);
    h += '<div class="sec"><h2>Recently closed</h2></div>' + recentRows(S, ui);
    return h;
  }

  /* ---------- Portfolio ---------- */
  function importResult(r) {
    if (!r) return '';
    var sk = texts(O(r).skipped), added = N(O(r).added);
    return '<div class="card rail k-' + (O(r).ok === false ? 'sell' : 'info') + ' result"><div class="cb"><div class="rh"><div class="t">' +
      (O(r).ok === false ? '<b>Import failed.</b> ' + tx(O(r).error, 'The file could not be read.')
        : '<b>' + (added !== null ? cnt(added) : '0') + ' holding' + (added === 1 ? '' : 's') + ' imported.</b>' +
          (sk.length ? ' ' + sk.length + ' line' + (sk.length > 1 ? 's were' : ' was') + ' skipped:' : ' No lines skipped.')) +
      '</div><button type="button" class="x" data-c="dismiss-import" aria-label="Hide import result">' + ic('x') + '</button></div>' +
      (sk.length ? '<pre>' + sk.slice(0, 50).map(function (l) { return esc(l.length > 160 ? l.slice(0, 160) + '…' : l); }).join('\n') + (sk.length > 50 ? '\n… and ' + (sk.length - 50) + ' more' : '') + '</pre>' : '') +
      '</div></div>';
  }
  function holdingCard(h, ui) {
    var sym = T(h.sym), nodata = h.dataOk === false;
    var v = VERDICT[h.verdict];
    var vt = nodata ? tag('neutral', 'NO DATA', 'clock') : v ? tag(v.cls, v.label, v.icon) : tag('neutral', T(h.verdict).trim().toUpperCase().slice(0, 12) || 'NO CALL', 'info');
    var rail = nodata || !v ? '' : v.cls === 'sell' ? 'k-sell' : v.cls === 'trim' ? 'k-trim' : v.cls === 'watch' ? 'k-info' : '';
    var out = '<article class="card rail hold ' + rail + (nodata ? ' nodata' : '') + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + vt + symBtn(h.sym, h.name) + '</div>' +
      '<div class="sub">' + qtyStr(h.qty) + ' shares · avg ' + px(h.avg) + (T(h.name).trim() && T(h.name).trim() !== sym ? ' · ' + esc(T(h.name).trim()) : '') + '</div>' +
      (!nodata && N(h.strength) > 0 ? '<div class="str"><span>Trend strength</span><i><b style="width:' + Math.min(100, Math.round(N(h.strength))) + '%" class="' + (N(h.strength) >= 65 ? 'g' : N(h.strength) >= 40 ? 'a' : 'r') + '"></b></i><span class="num">' + Math.round(N(h.strength)) + '</span></div>' : '') + '</div>';
    if (nodata) {
      out += '<div class="px"><div class="ltp mut">' + DASH + '</div><div class="ltpl">no price</div></div></div>' +
        '<div class="nd">' + ic('clock') + '<span>No data yet. Prices and a verdict appear after the next scan.</span></div>';
    } else {
      out += '<div class="px"><div class="ltp">' + px(h.ltp) + '</div><div class="ltpl num ' + tone(h.dayChgPct) + '">' + pc(h.dayChgPct, true, true) + ' today</div></div></div>';
      if (T(h.headline).trim()) out += '<div class="headline">' + esc(T(h.headline).trim()) + '</div>';
      out += '<div class="lv"><div class="w2"><div class="k">P&amp;L <span class="num ' + tone(h.pnlPct) + '">' + pc(h.pnlPct, true) + '</span></div><div class="v ' + tone(h.pnlRs) + '">' + rs(h.pnlRs, true) + '</div></div>' +
        '<div><div class="k">Stop</div><div class="v">' + pxBare(h.stop) + '</div></div>' +
        '<div><div class="k">Book at</div><div class="v">' + pxBare(h.bookAt) + '</div></div></div>';
    }
    var rb = nodata ? '' : reasonsBlock('hold:' + sym, h.reasons, ui, 'Why');
    out += '<div class="foot">' + (rb ? rb.split('</button>')[0] + '</button>' : '<span class="more"></span>') +
      '<button type="button" class="rm" data-c="remove" data-id="' + esc(sym) + '"' + (!sym || pend(ui, 'removeHolding:' + sym) ? ' disabled' : '') + '>' + ic('trash') + 'Remove</button></div>';
    if (rb && rb.indexOf('<ul') >= 0) out += rb.slice(rb.indexOf('<ul'));
    return out + '</div></article>';
  }
  function overview(S) {
    var hs = A(S.holdings).filter(function (x) { return N(x.valueRs) > 0; });
    var total = hs.reduce(function (a, x) { return a + N(x.valueRs); }, 0);
    if (!(total > 0) || hs.length < 2) return '';
    var byVal = hs.slice().sort(function (a, b) { return N(b.valueRs) - N(a.valueRs); });
    var top = byVal.slice(0, 5), rest = total - top.reduce(function (a, x) { return a + N(x.valueRs); }, 0);
    var cols = ['#6BB3FF', '#3FD68F', '#F2B441', '#C58BFF', '#FF8F6B', '#56667A'];
    var segs = top.map(function (x, i) { return { n: T(x.sym), w: N(x.valueRs) / total * 100, c: cols[i] }; });
    if (rest > total * 0.005) segs.push({ n: 'Other ' + (byVal.length - top.length), w: rest / total * 100, c: cols[5] });
    var bar = '<div class="alloc">' + segs.map(function (g) { return '<i style="width:' + Math.max(1, g.w).toFixed(2) + '%;background:' + g.c + '"></i>'; }).join('') + '</div>' +
      '<div class="alleg">' + segs.map(function (g) { return '<span><i style="background:' + g.c + '"></i>' + esc(g.n) + ' <b class="num">' + Math.round(g.w) + '%</b></span>'; }).join('') + '</div>';
    var w1 = segs[0].w, w3 = segs.slice(0, 3).reduce(function (a, g) { return a + (/^Other /.test(g.n) ? 0 : g.w); }, 0);
    var warn = w1 > 20 ? segs[0].n + ' alone is ' + Math.round(w1) + '% of your portfolio. One bad result there moves everything.'
      : w3 > 50 ? 'Your top 3 holdings are ' + Math.round(w3) + '% of the portfolio.' : '';
    var ranked = hs.filter(function (x) { return x.dataOk !== false && N(x.avg) > 0 && N(x.pnlPct) !== null; }).sort(function (a, b) { return N(b.pnlPct) - N(a.pnlPct); });
    var row = function (x) { return '<button type="button" class="wl" data-c="stock" data-id="' + esc(T(x.sym)) + '" data-name="' + esc(T(x.name)) + '"><b>' + tx(x.sym) + '</b><span class="num ' + tone(x.pnlPct) + '">' + pc(x.pnlPct, true) + '</span><span class="num mut">' + rs(x.pnlRs, true) + '</span></button>'; };
    var wl = '';
    if (ranked.length >= 4) {
      var k = Math.min(3, Math.floor(ranked.length / 2));
      wl = '<div class="wlg"><div><div class="k">Best</div>' + ranked.slice(0, k).map(row).join('') + '</div><div><div class="k">Worst</div>' + ranked.slice(-k).reverse().map(row).join('') + '</div></div>';
    }
    var vc = { SELL: 0, TRIM: 0, WATCH: 0, HOLD: 0 };
    A(S.holdings).forEach(function (x) { if (x.dataOk !== false && vc[x.verdict] !== undefined) vc[x.verdict]++; });
    var health = '<div class="hl"><span class="hold">' + vc.HOLD + ' healthy</span><span class="watch">' + vc.WATCH + ' on watch</span><span class="trim">' + vc.TRIM + ' to trim</span><span class="sell">' + vc.SELL + ' to sell</span></div>';
    return '<div class="sec"><h2>Overview</h2></div><div class="card ov">' + health + '<div class="k">Where the money is</div>' + bar +
      (warn ? '<div class="ovw">' + ic('warn') + '<span>' + esc(warn) + '</span></div>' : '') + wl + '</div>';
  }
  function portfolio(S, ui) {
    var p = O(S.portfolio), hs = sortedHoldings(S, ui.sort);
    var sell = N(p.sellCount), trim = N(p.trimCount);
    var h = '';
    if (hs.length) {
      h += '<div class="card"><div class="psum"><div class="k">Portfolio value</div><div class="total">' + rs(p.valueRs) + '</div>' +
        '<div class="g"><div><div class="k">Invested</div><div class="v">' + rs(p.investedRs) + '</div></div>' +
        '<div><div class="k">Total P&amp;L <span class="num ' + tone(p.pnlPct) + '">' + pc(p.pnlPct, true) + '</span></div><div class="v ' + tone(p.pnlRs) + '">' + rs(p.pnlRs, true) + '</div></div>' +
        '<div><div class="k">Today</div><div class="v ' + tone(p.dayPnlRs) + '">' + rs(p.dayPnlRs, true) + '</div></div></div>' +
        '<div class="calls">' + (sell > 0 ? tag('sell', cnt(sell) + ' TO SELL', 'down') : '') + (trim > 0 ? tag('trim', cnt(trim) + ' TO TRIM', 'scissors') : '') +
        (!(sell > 0) && !(trim > 0) ? '<span class="t">No sell or trim calls right now.</span>' : '') + '</div></div></div>';
    }
    h += '<div class="tools">' + btn('add', null, 'Add', '', { icon: 'plus' }) +
      btn('pick', null, 'Import file', '', { icon: 'file', busy: pend(ui, 'importHoldings') }) +
      btn('paste', null, 'Paste', '', { icon: 'paste', disabled: pend(ui, 'importHoldings') }) + '</div>';
    if (ui.importResult) h += '<div style="margin-top:10px">' + importResult(ui.importResult) + '</div>';
    if (hs.length >= 2) h += overview(S);
    h += '<div class="sec"><h2>Holdings</h2><span class="cnt">' + hs.length + '</span></div>';
    if (hs.length >= 2) {
      var sk = ui.sort || 'action';
      h += '<div class="chips">' + [['action', 'By action'], ['strength', 'Strongest'], ['pnl', 'P&L %'], ['value', 'Value'], ['day', 'Today']].map(function (c) {
        return '<button type="button" class="chip' + (sk === c[0] ? ' on' : '') + '" data-c="sort" data-id="' + c[0] + '">' + esc(c[1]) + '</button>';
      }).join('') + '</div>';
    }
    if (!hs.length) {
      h += '<div class="empty"><div class="ei">' + ic('portfolio') + '</div><h3>No holdings added</h3>' +
        '<p>Add the stocks you already own and each scan will check them: hold, watch, trim or sell, with a stop and a level to book at. Import a broker holdings file (CSV) or paste the rows.</p></div>';
    } else hs.forEach(function (x) { h += holdingCard(x, ui); });
    return h;
  }

  /* ---------- Alerts + strategies ---------- */
  var KIND = { buy: ['buy', 'up', 'Buy'], sell: ['sell', 'down', 'Sell'], warn: ['warn', 'warn', 'Warning'], brief: ['brief', 'sun', 'Brief'], info: ['info', 'info', 'Info'] };
  function feed(S, ui) {
    var f = A(S.feed).slice(0, 100), now = ui.now;
    var h = '<div class="sec"><h2>Alerts</h2><span class="cnt">' + f.length + '</span><span class="sp"></span>' +
      (f.length ? '<button type="button" class="linkbtn" data-c="clearfeed"' + (pend(ui, 'clearFeed') ? ' disabled' : '') + '>Clear all</button>' : '') + '</div>';
    if (!f.length) {
      return h + '<div class="empty"><div class="ei">' + ic('alerts') + '</div><h3>No alerts yet</h3>' +
        '<p>Buy ideas, exit calls, the morning brief and scan problems are logged here as they happen, so you can check anything a notification told you.</p></div>';
    }
    var cur = null, open = false;
    f.forEach(function (x) {
      var lab = dayLabel(x.ts, now);
      if (lab !== cur) { if (open) h += '</div>'; h += '<div class="daylab">' + esc(lab) + '</div><div class="rows">'; open = true; cur = lab; }
      var k = KIND[x.kind] || KIND.info;
      h += '<div class="fi ' + k[0] + '"><div class="ico"><span>' + ic(k[1]) + '</span></div><div class="tx">' +
        '<div class="tt"><b>' + tx(x.title, 'Untitled alert') + '</b><time>' + k[2] + ' · ' + hm(x.ts) + '</time></div>' +
        (T(x.body).trim() ? '<div class="bd">' + esc(T(x.body).trim()) + '</div>' : '') + '</div></div>';
    });
    if (open) h += '</div>';
    return h;
  }
  function stratCard(s, ui) {
    var v = SVERD[s.verdict] || { cls: 'neutral', icon: 'info', label: T(s.verdict).trim().toUpperCase().slice(0, 12) || 'UNKNOWN' };
    var st = O(s.stats), n = N(st.n), has = n !== null && n > 0;
    var h = '<article class="card strat"><div class="cb"><div class="sh"><div class="n"><b>' + tx(s.label, 'Unnamed strategy') + '</b>' +
      (T(s.desc).trim() ? '<span>' + esc(T(s.desc).trim()) + '</span>' : '') + '</div>' + tag(v.cls, v.label, v.icon) + '</div>';
    if (has) {
      h += '<div class="lv"><div><div class="k">Trades</div><div class="v sm">' + cnt(n) + '</div></div>' +
        '<div><div class="k">Won</div><div class="v sm">' + pc(st.winPct) + '</div></div>' +
        '<div><div class="k">Avg net</div><div class="v sm ' + tone(st.avgPct) + '">' + pc(st.avgPct, true) + '</div></div>' +
        '<div><div class="k">Worst dip</div><div class="v sm">' + (N(st.maxDdPct) !== null ? pc(Math.abs(N(st.maxDdPct))) : DASH) + '</div></div></div>';
      var pf = N(st.pf), oosN = N(st.oosN);
      h += '<div class="meta">Profit factor <b>' + (pf !== null && pf >= 0 ? (pf > 99 ? '>99' : pf.toFixed(2)) : DASH) + '</b>' +
        (oosN !== null && oosN > 0 ? ' · Recent unseen data: <b>' + cnt(oosN) + '</b> trades, <b class="' + tone(st.oosAvgPct) + '">' + pc(st.oosAvgPct, true) + '</b> avg' : '') + '</div>';
    }
    if (T(s.why).trim()) h += '<div class="why">' + esc(T(s.why).trim()) + '</div>';
    h += '<div class="meta">' + (ts(s.testedAt) !== null ? 'Tested ' + esc(rel(s.testedAt, ui.now)) + ' (' + dm(s.testedAt, ui.now) + ')' : 'Not tested on this phone yet') + '</div>';
    return h + '</div></article>';
  }
  function strategies(S, ui) {
    var st = A(S.strategies), busy = isBusy(S), p = pend(ui, 'runBacktest');
    var live = st.filter(function (s) { return s.verdict === 'LIVE'; }).length;
    var h = '<div class="sec" id="strategies"><h2>Strategies</h2><span class="cnt">' + live + ' of ' + st.length + ' live</span></div>' +
      '<div class="hint" style="padding-bottom:10px">' + ic('shield') + '<span class="t">A strategy sends buy ideas only while its test on past prices is positive after charges. Past results do not promise future ones.</span></div>';
    if (!st.length) h += '<div class="empty"><div class="ei">' + ic('flask') + '</div><h3>No strategies loaded</h3><p>Run the test to load and check the built-in strategies.</p></div>';
    st.forEach(function (s) { h += stratCard(s, ui); });
    if (busy) h += '<div class="card"><div class="cb" style="padding-top:2px">' + progress(S.busy) + '</div></div>';
    h += '<div class="btns" style="margin-top:4px">' + btn('backtest', null, busy || p ? 'Working' : 'Re-test now', 'grow', { busy: busy || p, icon: 'flask' }) + '</div>' +
      '<div class="hint" style="padding-top:10px">' + ic('clock') + '<span class="t">A re-test downloads about two years of prices and takes a few minutes. You can leave this screen.</span></div>';
    return h;
  }
  function alerts(S, ui) { return feed(S, ui) + strategies(S, ui) + '<div class="sec"><h2>Track record</h2></div>' + trackRow(S); }

  /* =====================================================================================================
     v3 (SPEC3): buy verdict card, user targets, ranked ideas, full-screen chart chrome + readouts
     ===================================================================================================== */
  function symEq(a, b) { var x = T(a).trim().toUpperCase(); return !!x && x === T(b).trim().toUpperCase(); }
  function pctOf(v, base) { var a = N(v), b = N(base); return a !== null && b !== null && a > 0 && b > 0 ? (a / b - 1) * 100 : null; }
  function targetOf(S, sym) {
    var list = A(O(S).targets), i;
    for (i = 0; i < list.length; i++) if (symEq(list[i].sym, sym)) return list[i];
    return null;
  }
  /* the user's own target for the stock on screen: state.targets first (source of truth), else stockInfo.myTarget */
  function myTarget(S, ui) {
    var st = O(O(ui).stock), sym = T(st.sym).trim(), r = sym ? targetOf(S, sym) : null, m = r || O(O(st.info).myTarget);
    var t = N(m.target), s = N(m.stop);
    return { target: t !== null && t > 0 ? t : 0, stop: s !== null && s > 0 ? s : 0, note: T(m.note), status: T(m.status).trim().toUpperCase() };
  }
  function hitTargets(S) {
    return A(S.targets).filter(function (t) { var s = T(t.status).trim().toUpperCase(); return T(t.sym).trim() && (s === 'HIT_TARGET' || s === 'HIT_STOP'); })
      .map(function (t, i) { return { t: t, i: i }; })
      .sort(function (a, b) { return (N(b.t.hitAt) || 0) - (N(a.t.hitAt) || 0) || a.i - b.i; })
      .map(function (x) { return x.t; });
  }
  function targetButton(S, ui, sym) {
    var m = myTarget(S, ui), has = m.target > 0 || m.stop > 0, lab;
    if (!has) lab = 'Set target';
    else if (m.status === 'HIT_TARGET') lab = 'Target ' + px(m.target) + ' reached';
    else if (m.status === 'HIT_STOP') lab = 'Stop ' + px(m.stop) + ' reached';
    else lab = (m.target > 0 ? 'Target ' + px(m.target) + ' ✓' : 'Stop ' + px(m.stop) + ' ✓') + (m.target > 0 && m.stop > 0 ? ' · Stop ' + px(m.stop) : '');
    return '<button type="button" class="btn tbtn' + (has ? ' on' : '') + (m.status === 'HIT_TARGET' || m.status === 'HIT_STOP' ? ' hit' : '') + '" data-c="target" data-id="' + esc(sym) + '">' + ic('target') + '<span class="clip">' + esc(lab) + '</span></button>';
  }

  /* ---------- score ring (0-100) ---------- */
  function scoreRing(score, cls, size) {
    var s = N(score), has = s !== null && s > 0; s = has ? Math.max(0, Math.min(100, s)) : 0;
    var px2 = size || 56;
    return '<span class="ring ' + esc(cls || '') + '" style="width:' + px2 + 'px;height:' + px2 + 'px;font-size:' + Math.round(px2 * 0.34) + 'px" role="img" aria-label="' + (has ? 'Score ' + Math.round(s) + ' out of 100' : 'Score not available') + '">' +
      '<svg viewBox="0 0 36 36" aria-hidden="true"><circle class="rb" cx="18" cy="18" r="15.9155"/><circle class="rf" cx="18" cy="18" r="15.9155" stroke-dasharray="' + s.toFixed(1) + ' ' + (100 - s).toFixed(1) + '" transform="rotate(-90 18 18)"/></svg>' +
      '<b class="num">' + (has ? Math.round(s) : DASH) + '</b></span>';
  }

  /* ---------- buy verdict card ---------- */
  var CALLS = { BUY: { cls: 'buy', rail: 'k-buy', icon: 'up' }, WAIT: { cls: 'wait', rail: 'k-trim', icon: 'clock' }, AVOID: { cls: 'avoid', rail: 'k-sell', icon: 'x' }, NA: { cls: 'na', rail: 'k-info', icon: 'info' } };
  var CAVEAT = 'Rule-based and tested on past data. Losses happen; the stop limits them, it does not remove them.';
  function pcs(v) { var n = N(v); return n === null ? '' : pc(n, true, true); }
  /* a collapsed row on the verdict card: tap to open (state in ui.vOpen) */
  function colRow(ui, key, label, sub, inner) {
    var open = !!O(O(ui).vOpen)[key];
    return '<div class="vcol' + (open ? ' open' : '') + '"><button type="button" class="vcb" data-c="vopen" data-id="' + key + '" aria-expanded="' + open + '"><b>' + esc(label) + '</b>' + (sub ? '<em>' + esc(sub) + '</em>' : '') + ic('chev') + '</button>' +
      (open ? '<div class="vcc">' + inner + '</div>' : '') + '</div>';
  }
  function histBlock(c, hz) {
    var h = O(c.hist), n = N(h.n), txt = T(h.text).trim();
    var hasStats = n !== null && n > 0;
    var d = N(h.horizonDays) || N(c.horizonDays) || hz;
    if (!hasStats && !txt) return '<div class="vhist none">' + ic('info') + '<div><b>No tested history for this call</b><p>There is not enough past data in this score band to show how it played out.</p></div></div>';
    var tile = function (k, v, cls) { return '<div class="vt"><div class="k">' + k + '</div><div class="v num ' + (cls || '') + '">' + v + '</div></div>'; };
    var win = N(h.winPct), avg = N(h.avgPct), med = N(h.medianPct), wor = N(h.worstPct);
    return '<div class="vhist"><div class="vhh">' + ic('flask') + '<b>What happened before</b>' + (d ? '<span>over ' + cnt(d) + ' trading days</span>' : '') + '</div>' +
      (txt ? '<p class="vhx">' + esc(txt) + '</p>' : '') +
      (hasStats ? '<div class="vts">' + tile('Gained', win !== null && win > 0 ? pc(win) : DASH) + tile('Average', avg !== null ? pc(avg, true) : DASH, avg === null ? '' : tone(avg)) +
        tile('Median', med !== null ? pc(med, true) : DASH, med === null ? '' : tone(med)) + tile('Worst', wor !== null ? pc(wor, true) : DASH, wor === null ? '' : tone(wor)) + '</div>' +
        '<div class="vhn">' + cnt(n) + ' past cases in the same score band, after charges</div>' : '') + '</div>';
  }
  function pointsBlock(pts) {
    var rows = A(pts).filter(function (p) { return T(p.label).trim(); });
    if (!rows.length) return '';
    return '<div class="vpts">' + rows.map(function (p) {
      var v = N(p.pts), mx = N(p.max) > 0 ? N(p.max) : 20, w = v === null ? 0 : Math.max(0, Math.min(100, v / mx * 100));
      return '<div class="vpt"><span class="l">' + esc(shortPt(p.label)) + '</span><i><b style="width:' + w.toFixed(0) + '%"></b></i><span class="num">' + (v === null ? DASH : v.toFixed(1)) + '<small>/' + mx + '</small></span></div>';
    }).join('') + '</div>';
  }
  function verdictCard(S, ui) {
    var st = O(O(ui).stock), info = st.info;
    if (!isObj(info) || !isObj(info.call)) return '';
    var c = info.call, lab = T(c.label).trim().toUpperCase(), k = CALLS[lab];
    if (!k) return '';
    var ltp = N(info.ltp), sym = T(st.sym).trim();
    var hz = N(c.horizonDays) > 0 ? N(c.horizonDays) : (N(O(c.hist).horizonDays) > 0 ? N(O(c.hist).horizonDays) : 0);
    var head = T(c.headline).trim(), reasons = texts(c.reasons).slice(0, 8);
    var pillText = lab === 'BUY' && c.proven !== true ? 'BUY CANDIDATE' : lab === 'NA' ? 'NOT RATED' : lab;
    var h = '<section class="card rail ' + k.rail + ' vd vd-' + k.cls + '" id="st-vcard" aria-label="' + esc('SwingEdge call for ' + (sym || 'this stock') + ': ' + lab) + '"><div class="vdb">' +
      '<div class="vtop"><div class="vl"><div class="vk">Rule-based call</div>' + bandLine(c.band) + '<span class="vpill ' + k.cls + '">' + ic(k.icon) + pillText + '</span></div>' +
      (lab === 'NA' ? '' : '<div class="vscore">' + scoreRing(c.score, k.cls, 60) + '<span class="vsl">setup score</span></div>') + '</div>' +
      (head ? '<p class="vhead">' + esc(head) + '</p>' : '') +
      (T(c.evidence).trim() && lab !== 'NA' ? '<div class="vevid ' + (c.proven === true ? 'ok' : 'warn') + '">' + ic(c.proven === true ? 'check' : 'info') + '<div><b>' + (c.proven === true ? 'Live record' : 'Tested record, 2022–Jan 2025') + '</b><span>' + esc(T(c.evidence).trim()) + '</span></div></div>' : '') +
      (lab === 'BUY' ? trackLine(S) : '') +
      (lab === 'NA' ? '' : pointsBlock(c.points));
    var lo = N(c.entryLow), hi = N(c.entryHigh), stp = N(c.stop), t1 = N(c.t1), t2 = N(c.t2);
    var levels = lab !== 'AVOID' && ((lo > 0 && hi > 0) || stp > 0 || t1 > 0 || t2 > 0);
    if (levels) {
      var zone = lo > 0 && hi > 0 ? (Math.round(lo * 100) === Math.round(hi * 100) ? px(lo) : px(Math.min(lo, hi)) + ' – ' + pxBare(Math.max(lo, hi))) : DASH;
      var lc = function (kk, v, p, cls) { return '<div><div class="k">' + kk + '</div><div class="v num ' + (cls || '') + '">' + (v > 0 ? pxBare(v) : DASH) + '</div><div class="p num ' + (cls || '') + '">' + (v > 0 ? (pcs(p !== null ? p : pctOf(v, ltp)) || DASH) : '') + '</div></div>'; };
      var rr = N(c.rr);
      h += '<div class="vzone"><span class="k">' + (lab === 'WAIT' ? 'Entry zone if it sets up' : 'Entry zone') + '</span><span class="v num">' + zone + '</span></div>' +
        '<div class="lv vlv">' + lc('Stop-loss', stp, N(c.stopPct), 'down') + lc('Target 1', t1, N(c.t1Pct), 'up') + lc('Target 2', t2, N(c.t2Pct), 'up') + '</div>' +
        '<div class="vmeta"><span>Reward : risk <b class="num">' + (rr !== null && rr > 0 ? '1 : ' + (rr > 99 ? '>99' : rr.toFixed(1)) : DASH) + '</b></span>' +
        '<span>Horizon <b>' + (hz > 0 ? 'about ' + cnt(hz) + ' trading days' : DASH) + '</b></span></div>';
      var q = N(c.qty);
      if (lab === 'BUY' && q !== null && q > 0) {
        h += '<div class="vpos">' + ic('shield') + '<div><b>Position size for your settings</b><span><b class="num">' + qtyStr(q) + '</b> share' + (q === 1 ? '' : 's') + ' · invest <b class="num">' + rs(c.investRs) + '</b> · at risk <b class="num down">' + rs(c.riskRs) + '</b> if the stop hits</span></div></div>';
      }
    } else if (lab === 'AVOID') {
      h += '<div class="vavoid">' + ic('x') + '<span>No entry levels are shown: this stock fails the trend and quality checks.</span></div>';
    }
    if ((lab === 'WAIT' || lab === 'NA') && T(c.waitFor).trim()) h += '<div class="vwait">' + ic('clock') + '<div><b>What would make it a buy candidate</b><span>' + esc(T(c.waitFor).trim()) + '</span></div></div>';
    if (levels && T(c.exitRule).trim()) h += '<div class="vwait">' + ic('flag') + '<div><b>How the tested plan exits</b><span>' + esc(T(c.exitRule).trim()) + '</span></div></div>';
    if (reasons.length) h += colRow(ui, 'why', lab === 'AVOID' ? 'Why it fails' : 'Why this call', reasons.length + (reasons.length === 1 ? ' reason' : ' reasons'), '<ul class="vrs">' + reasons.map(function (r) { return '<li>' + esc(r) + '</li>'; }).join('') + '</ul>');
    h += colRow(ui, 'hist', 'What happened before', hz > 0 ? 'over ' + cnt(hz) + ' trading days' : '', histBlock(c, hz));
    h += '<p class="vcav">' + ic('info') + '<span>' + esc(T(c.caveat).trim() || CAVEAT) + '</span></p>';
    if (levels) {
      h += '<div class="btns">' + btn('target-from-call', sym, 'Set these as my target', 'grow', { icon: 'target', disabled: !sym }) + '</div>' +
        '<p class="vnote">SwingEdge never places orders. You trade with your own broker.</p>';
    }
    return h + '</div></section>';
  }

  /* ---------- targets: sheet ---------- */
  function targetCalc(ltp, target, stop) {
    var l = N(ltp), t = N(target), s = N(stop), ok = l !== null && l > 0;
    var row = function (lab, v, p) { return '<div><span>' + lab + '</span><b class="' + (p === null ? '' : tone(p)) + '">' + (v > 0 && p !== null ? pc(p, true, true) + ' from the last price' : DASH) + '</b></div>'; };
    var h = '<div><span>Last price</span><b>' + (ok ? px(l) : DASH) + '</b></div>' +
      row('Target ' + (t > 0 ? px(t) : DASH), t, ok && t > 0 ? (t / l - 1) * 100 : null) + row('Stop ' + (s > 0 ? px(s) : DASH), s, ok && s > 0 ? (s / l - 1) * 100 : null);
    if (ok && t > 0 && s > 0 && t > l && s < l) h += '<div><span>Reward : risk</span><b>1 : ' + (((l - s) / (t - l)) > 99 ? '>99' : ((l - s) / (t - l)).toFixed(2)) + '</b></div>';
    else if (ok && t > 0 && s > 0 && ((t > l) === (s > l))) h += '<div><span class="mut">The target and the stop are on the same side of the last price.</span></div>';
    return h;
  }
  function qchips(label, list) {
    return '<div class="qrow"><span class="ql">' + esc(label) + '</span>' + list.map(function (x) {
      return '<button type="button" class="qc" data-c="tgt-pct" data-id="' + x[0] + '">' + esc(x[1]) + '</button>';
    }).join('') + '</div>';
  }
  function targetSheet(S, ui, data) {
    var sym = T(data.id).trim().toUpperCase(), ltp = N(data.ltp), cur = targetOf(S, sym);
    var has = !!cur && (N(cur.target) > 0 || N(cur.stop) > 0);
    var tv = data.target !== undefined ? data.target : (cur ? cur.target : 0), sv = data.stop !== undefined ? data.stop : (cur ? cur.stop : 0), nv = data.note !== undefined ? data.note : (cur ? cur.note : '');
    return shell('Target for ' + (sym || 'stock'),
      '<p class="lead">Get an alert when <b>' + esc(sym || 'this stock') + '</b> reaches a price you choose' + (ltp > 0 ? '. Last price <b>' + px(ltp) + '</b>' : '') + '. This tracks the price only; it does not place an order.</p>' +
      (ltp > 0 ? qchips('Target', [['t5', '+5%'], ['t10', '+10%'], ['t20', '+20%']]) : '') +
      field('target', 'Target price', inputNum(tv), { pre: '₹', hint: 'optional if you set a stop' }) +
      (ltp > 0 ? qchips('Stop', [['s5', '−5%'], ['s8', '−8%']]) : '') +
      field('stop', 'Stop price', inputNum(sv), { pre: '₹', hint: 'optional if you set a target' }) +
      field('note', 'Note', T(nv).slice(0, 80), { mode: 'text', max: 80, ph: 'e.g. book half here', enter: 'done', hint: 'optional' }) +
      '<div class="calc" id="calc">' + targetCalc(ltp, tv, sv) + '</div><div class="formerr" id="formerr" hidden></div>' +
      (has ? '<div class="btns"><button type="button" class="btn dan ghost grow" data-c="target-clear" data-id="' + esc(sym) + '">' + ic('trash') + 'Remove this target</button></div>' : ''),
      btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, has ? 'Update target' : 'Save target', 'pri', { icon: 'check' }));
  }

  /* ---------- targets: Trades tab ---------- */
  function targetTrack(tgt, stp, ref, ltp) {
    var ms = [];
    if (stp > 0) ms.push({ k: 'stop', v: stp, l: 'Stop' });
    if (ref > 0) ms.push({ k: 'ref', v: ref, l: 'Set at' });
    if (tgt > 0) ms.push({ k: 'tgt', v: tgt, l: 'Target' });
    if (ms.length < 2) return '';
    ms.sort(function (a, b) { return a.v - b.v; });
    var lo = ms[0].v, hi = ms[ms.length - 1].v;
    var P = function (v) { return hi > lo ? Math.max(0, Math.min(100, (v - lo) / (hi - lo) * 100)) : 50; };
    var fill = '', now = '';
    if (ref > 0 && ltp > 0) {
      var a = P(ref), b = P(ltp), toward = tgt > 0 && (ltp - ref) * (tgt - ref) > 0;
      fill = '<i class="tp-f ' + (toward ? 'g' : 'r') + '" style="left:' + Math.min(a, b).toFixed(1) + '%;width:' + Math.abs(a - b).toFixed(1) + '%"></i>';
      now = '<i class="tp-now" style="left:' + b.toFixed(1) + '%"></i>';
    }
    return '<div class="tprog" role="img" aria-label="' + esc('Price now ' + (ltp > 0 ? pxBare(ltp) : 'unknown') + ', between your stop and your target') + '"><div class="tp-rail"><i class="tp-bar"></i>' + fill +
      ms.map(function (m) { return '<i class="tp-m ' + m.k + '" style="left:' + P(m.v).toFixed(1) + '%"></i>'; }).join('') + now + '</div>' +
      '<div class="tp-cells">' + ms.map(function (m) { return '<div class="tp-c ' + m.k + '"><span class="k">' + m.l + '</span><b class="num">' + px(m.v) + '</b></div>'; }).join('') + '</div></div>';
  }
  function targetRow(x, ui, now) {
    var sym = T(x.sym).trim(), nm = T(x.name).trim(), stt = T(x.status).trim().toUpperCase();
    var tgt = N(x.target) > 0 ? N(x.target) : 0, stp = N(x.stop) > 0 ? N(x.stop) : 0, ref = N(x.refPrice) > 0 ? N(x.refPrice) : 0, ltp = N(x.ltp) > 0 ? N(x.ltp) : 0;
    var hitT = stt === 'HIT_TARGET', hitS = stt === 'HIT_STOP';
    var toT = N(x.toTargetPct), toS = N(x.toStopPct), prog = N(x.progressPct);
    if (toT === null && tgt > 0 && ltp > 0) toT = (tgt / ltp - 1) * 100;
    if (toS === null && stp > 0 && ltp > 0) toS = (stp / ltp - 1) * 100;
    var pill = hitT ? tag('buy', 'HIT TARGET', 'check') : hitS ? tag('sell', 'HIT STOP', 'down') : tag('info', 'ACTIVE', 'target');
    var when = (hitT || hitS) && ts(x.hitAt) !== null ? '<span class="tw">' + esc(dm(x.hitAt, now) + ', ' + hm(x.hitAt)) + '</span>' : (ts(x.setAt) !== null ? '<span class="tw">set ' + esc(dm(x.setAt, now)) + '</span>' : '');
    var pend1 = pend(ui, 'clearTarget:' + sym), conf = !!O(ui.confirmTarget)[sym];
    var main = '<button type="button" class="tmain" data-c="stock" data-id="' + esc(sym) + '" data-name="' + esc(nm) + '" aria-label="' + esc('Open ' + sym) + '">' +
      '<span class="th"><span class="tn"><b>' + esc(sym) + '</b><span class="ws">' + (nm && nm !== sym ? esc(nm) : '&nbsp;') + '</span></span>' +
      '<span class="sp"><span class="num">' + px(ltp) + '</span><span class="num ' + tone(x.dayChgPct) + '">' + (N(x.dayChgPct) === null ? '' : pc(x.dayChgPct, true, true)) + '</span></span></span>' +
      '<span class="tpl">' + pill + when + '</span>' + targetTrack(tgt, stp, ref, ltp) +
      '<span class="tst">' + (hitT ? '<span class="up"><b>Target reached</b></span>' : hitS ? '<span class="down"><b>Stop reached</b></span>' : '') +
      (!hitT && !hitS && tgt > 0 ? '<span>To target <b class="num ' + (toT !== null && toT > 0 ? 'up' : 'flat') + '">' + (toT === null ? DASH : pc(toT, true, true)) + '</b></span>' : '') +
      (!hitT && !hitS && stp > 0 ? '<span>To stop <b class="num ' + (toS !== null && toS < 0 ? 'down' : 'flat') + '">' + (toS === null ? DASH : pc(toS, true, true)) + '</b></span>' : '') +
      (tgt > 0 && prog !== null && !hitT && !hitS ? '<span class="mut">' + Math.round(Math.max(0, Math.min(100, prog))) + '% of the way</span>' : '') + '</span>' +
      (T(x.note).trim() ? '<span class="tnote">' + esc(T(x.note).trim()) + '</span>' : '') + '</button>';
    var act = conf
      ? '<div class="tconf"><span>Remove this target?</span>' + btn('target-clear-yes', sym, 'Remove', 'sm dan', { busy: pend1, disabled: !sym }) + btn('target-clear-no', sym, 'Keep', 'sm ghost') + '</div>'
      : '<div class="tact"><button type="button" class="tclr" data-c="target-clear-ask" data-id="' + esc(sym) + '"' + (!sym || pend1 ? ' disabled' : '') + ' aria-label="' + esc('Clear the target for ' + sym) + '">' + ic('trash') + 'Clear</button></div>';
    return '<article class="card rail trow ' + (hitT ? 'k-hit' : hitS ? 'k-sell' : 'k-info') + '">' + main + act + '</article>';
  }
  function targetsSection(S, ui) {
    var rows = A(S.targets).filter(function (x) { return T(x.sym).trim(); }), now = ui.now;
    rows = rows.map(function (x, i) { return { x: x, i: i }; }).sort(function (a, b) {
      var ha = /^HIT_/.test(T(a.x.status)) ? 0 : 1, hb = /^HIT_/.test(T(b.x.status)) ? 0 : 1;
      return ha - hb || (ha === 0 ? (N(b.x.hitAt) || 0) - (N(a.x.hitAt) || 0) : (N(b.x.setAt) || 0) - (N(a.x.setAt) || 0)) || a.i - b.i;
    }).map(function (o) { return o.x; });
    var h = '<div class="sec" id="targets"><h2>My targets</h2>' + (rows.length ? '<span class="cnt">' + rows.length + '</span>' : '') + '<span class="sp"></span>' +
      (rows.length ? btn('search', null, 'Add', 'sm', { icon: 'plus' }) : '') + '</div>';
    if (!rows.length) {
      return h + '<div class="empty"><div class="ei">' + ic('target') + '</div><h3>No price targets yet</h3>' +
        '<p>Open any stock, tap “Set target”, and enter a target price and an optional stop. SwingEdge checks it on every scan and alerts you when the price gets there.</p>' +
        '<div class="btns">' + btn('search', null, 'Find a stock', 'sm', { icon: 'search' }) + '</div></div>';
    }
    var all = ui.targetsAll === true, shown = all ? rows : rows.slice(0, 3);
    return h + shown.map(function (x) { return targetRow(x, ui, now); }).join('') +
      (rows.length > 3 ? '<button type="button" class="linkbtn tmore" data-c="targets-all">' + (all ? 'Show fewer' : 'Show all ' + rows.length) + '</button>' : '');
  }
  function targetHitCard(t, ui, now) {
    var sym = T(t.sym).trim(), stt = T(t.status).trim().toUpperCase(), hitT = stt === 'HIT_TARGET';
    var lvl = hitT ? N(t.target) : N(t.stop);
    return '<article class="card rail ' + (hitT ? 'k-hit' : 'k-sell') + '"><div class="cb">' +
      '<div class="hd"><div class="who"><div class="sym">' + (hitT ? tag('buy', 'TARGET HIT', 'target') : tag('sell', 'STOP HIT', 'down')) + symBtn(t.sym, t.name) + '</div>' +
      '<div class="sub">Your ' + (hitT ? 'target ' : 'stop ') + px(lvl) + (ts(t.hitAt) !== null ? ' · ' + esc(dm(t.hitAt, now) + ', ' + hm(t.hitAt)) : '') + '</div></div>' +
      '<div class="px"><div class="ltp">' + px(t.ltp) + '</div><div class="ltpl num ' + tone(t.dayChgPct) + '">' + (N(t.dayChgPct) === null ? '' : pc(t.dayChgPct, true, true) + ' today') + '</div></div></div>' +
      '<div class="note">' + esc((sym || 'This stock') + (hitT ? ' reached the target you set. Decide whether to book it, move the target or leave it.' : ' fell to the stop you set. Check the chart and decide what to do.')) + '</div>' +
      (T(t.note).trim() ? '<div class="note sm">Your note: ' + esc(T(t.note).trim()) + '</div>' : '') +
      '<div class="btns">' + btn('stock', sym, 'Open ' + (sym || 'stock'), 'grow', { disabled: !sym }) +
      btn('target-clear', sym, 'Clear target', 'ghost', { disabled: !sym || pend(ui, 'clearTarget:' + sym), busy: pend(ui, 'clearTarget:' + sym) }) + '</div></div></article>';
  }

  /* ---------- ranked ideas ---------- */
  function ideaRow(x, i, full) {
    var sym = T(x.sym).trim(); if (!sym) return '';
    var nm = T(x.name).trim(), lab = T(x.label).trim().toUpperCase() || 'BUY', k = CALLS[lab] || CALLS.BUY;
    var sv = (Array.isArray(x.spark) ? x.spark : []).map(N), up = sv.length > 1 && sv[sv.length - 1] !== null && sv[0] !== null ? sv[sv.length - 1] >= sv[0] : true;
    var lo = N(x.entryLow), hi = N(x.entryHigh);
    var zone = lo > 0 && hi > 0 ? (Math.round(lo) === Math.round(hi) ? emaNum(lo) : emaNum(Math.min(lo, hi)) + '–' + emaNum(Math.max(lo, hi))) : DASH;
    var rr = N(x.rr), hz = N(x.horizonDays);
    return '<button type="button" class="srow idea" data-c="stock" data-id="' + esc(sym) + '" data-name="' + esc(nm) + '">' +
      '<span class="s1"><span class="rk num">' + (i + 1) + '</span><span class="wn"><b>' + esc(sym) + '</b><span class="ws">' + (nm && nm !== sym ? esc(nm) : '&nbsp;') + '</span></span>' +
      spark(x.spark, up) + '<span class="sp"><span class="num">' + px(x.ltp) + '</span><span class="num ' + tone(x.dayChgPct) + '">' + (N(x.dayChgPct) === null ? '' : pc(x.dayChgPct, true, true)) + '</span></span></span>' +
      '<span class="s2 idz">' + scoreRing(x.score, k.cls, 36) + '<span class="idt"><span class="vpill sm ' + k.cls + '">' + esc(lab.slice(0, 5)) + '</span></span>' +
      '<span class="idc idw"><i>Entry zone</i><b class="num">' + zone + '</b></span><span class="idc"><i>Stop</i><b class="num">' + emaNum(x.stop) + '</b></span><span class="idc"><i>Target 1</i><b class="num">' + emaNum(x.t1) + '</b></span></span>' +
      (full && (T(x.headline).trim() || (rr !== null && rr > 0) || hz > 0) ? '<span class="s3 clip">' + ((rr !== null && rr > 0) ? 'Reward : risk 1 : ' + (rr > 99 ? '>99' : rr.toFixed(1)) : '') + (hz > 0 ? ((rr !== null && rr > 0) ? ' · ' : '') + 'about ' + cnt(hz) + ' trading days' : '') + '</span>' : '') +
      '</button>';
  }
  function ideasBlock(S, where) {
    var rows = A(S.ideas).filter(function (x) { return T(x.sym).trim(); }), today = where === 'today';
    var show = today ? rows.slice(0, 5) : rows.slice(0, 15);
    var hz = rows.length ? N(rows[0].horizonDays) : null;
    var h = '<div class="sec" id="ideas"><h2>Best setups now</h2>' + (rows.length ? '<span class="cnt">' + (today && rows.length > 5 ? 'top 5 of ' + rows.length : rows.length) + '</span>' : '') + '<span class="sp"></span>' +
      (today && rows.length ? '<button type="button" class="linkbtn" data-c="tab" data-id="setups">See all</button>' : '') + '</div>';
    if (!rows.length) {
      return today ? h + '<div class="hint hintpad">' + ic('layers') + '<span class="t">The ranked list fills after the next full scan (2:45 pm or Scan now).</span></div>'
        : h + '<div class="empty"><div class="ei">' + ic('layers') + '</div><h3>No ranked setups yet</h3><p>The ranked list fills after the next full scan (2:45 pm or Scan now).</p></div>';
    }
    return h + '<div class="hint hintpad ideanote">' + ic('layers') + '<span class="t">Highest momentum scores today, held about ' + (hz > 0 ? cnt(hz) : 60) + ' trading days. A screen, not proven buy calls: in 2022–26 this list did not beat random uptrend stocks. Separate from the short-term strategy calls.</span></div>' +
      '<div class="card mv">' + show.map(function (x, i) { return ideaRow(x, i, !today); }).join('') + '</div>';
  }

  /* ---------- full-screen chart chrome ---------- */
  var CF_RANGES = ['3M', '6M', '1Y', '2Y', 'All'];
  function cfRangeChips(on, listing) {
    return (listing ? ['3M', 'All'] : CF_RANGES).map(function (k) {
      return '<button type="button" class="rchip' + (k === on ? ' on' : '') + '" data-c="cf-range" data-id="' + k + '" aria-pressed="' + (k === on) + '"><span>' + (listing && k === 'All' ? 'Since listing' : k) + '</span></button>';
    }).join('');
  }
  function cfToggles(ui) {
    var on = emaOn(ui), o = O(O(ui).cfOpts), lv = o.levels !== false, vol = o.vol !== false;
    var e = EMAS.map(function (k) {
      return '<button type="button" class="cfe' + (on[k] ? ' on' : '') + '" data-c="cf-ema" data-id="' + k + '" aria-pressed="' + (on[k] ? 'true' : 'false') + '" style="--c:' + EMA_COL[k] + '"><i></i>' + k + '<em class="sr-only"> day EMA</em></button>';
    }).join('');
    return '<div class="cfrow"><span class="cfl">EMA</span>' + e + '</div>' +
      '<div class="cfrow"><button type="button" class="cfp' + (lv ? ' on' : '') + '" data-c="cf-levels" aria-pressed="' + (lv ? 'true' : 'false') + '">' + ic('target') + 'Levels</button>' +
      '<button type="button" class="cfp' + (vol ? ' on' : '') + '" data-c="cf-vol" aria-pressed="' + (vol ? 'true' : 'false') + '">' + ic('trades') + 'Volume</button>' +
      '<button type="button" class="cfp" data-c="cf-reset">' + ic('reset') + 'Reset</button></div>';
  }
  function cfBars(res) {
    res = O(res);
    return Math.min(nums(res.c).length, nums(res.o).length, nums(res.h).length, nums(res.l).length);
  }
  function cfHead(S, ui, sym) {
    var st = O(ui.stock), info = O(st.info), cf = O(ui.cf), res = O(cf.res), name = T(info.name).trim() || T(st.name).trim() || T(res.name).trim();
    var ltp = N(info.ltp), pct = N(info.dayChgPct);
    if (!(ltp > 0)) { var c = nums(res.c); if (c.length) { ltp = c[c.length - 1]; if (c.length > 1 && c[c.length - 2] > 0 && pct === null) pct = (c[c.length - 1] / c[c.length - 2] - 1) * 100; } }
    return '<div class="cfid"><b class="clip">' + (T(sym).trim() ? esc(T(sym).trim()) : DASH) + '</b><span class="clip">' + (name && name !== T(sym).trim() ? esc(name) : '&nbsp;') + '</span></div>' +
      '<div class="cfpx"><span class="num big">' + px(ltp) + '</span><span class="num ' + tone(pct) + '">' + (pct === null ? '' : arrow(pct) + pc(Math.abs(pct), false, true)) + '</span></div>' +
      '<button type="button" class="iconbtn" data-c="sheet-close" aria-label="Close the full-screen chart">' + ic('x') + '</button>';
  }
  function cfMsg(ui) {
    var cf = O(ui.cf), res = isObj(cf.res) ? cf.res : null;
    if (!res) {
      if (T(cf.err).trim()) return '<div class="cfm">' + ic('warn') + '<span>' + esc(T(cf.err).trim()) + '</span>' + btn('cf-retry', null, 'Retry', 'sm') + '</div>';
      return '<div class="cfm"><span class="spin"></span><span>Loading price history…</span></div>';
    }
    if (cfBars(res) < 5) return '<div class="cfm">' + ic('info') + '<span>Not enough price history to draw a chart.</span></div>';
    if (cf.loading === true && cf.have500 !== true) return '<div class="cfm mini"><span class="spin"></span><span>Loading the longer history…</span></div>';
    return '';
  }
  function cfSheet(S, ui, data) {
    var sym = T(data.id).trim() || T(O(ui.cf).sym).trim(), msg = cfMsg(ui);
    return '<div class="scrim"></div><section class="sheet full cfs" role="dialog" aria-modal="true" aria-label="' + esc((sym || 'Stock') + ' full-screen chart') + '">' +
      '<div class="sh cfh" id="cf-head">' + cfHead(S, ui, sym) + '</div>' +
      '<div class="cfbar"><div class="rc" id="cf-rng" role="group" aria-label="Chart range">' + cfRangeChips(T(O(ui.cfOpts).rangeOn), isObj(O(O(ui.stock).info).listing)) + '</div></div>' +
      '<div class="cfstage" id="cf-stage"><canvas id="cf-canvas" class="cfcv" role="img" aria-label="' + esc((sym || 'Stock') + ' price chart. Pinch to zoom, drag to pan, press and hold for a crosshair.') + '"></canvas>' +
      '<div class="cfro" id="cf-ro" hidden></div><div class="cfmsg" id="cf-msg"' + (msg ? '' : ' hidden') + '>' + msg + '</div></div>' +
      '<div class="cftog" id="cf-tog">' + cfToggles(ui) + '</div>' +
      '<p class="cfhint">Pinch zoom · drag pan · hold for crosshair · double-tap reset</p></section>';
  }
  /* readout for one bar (b = CM.barInfo result) */
  function dateFull(t) { var n = ts(t); if (n === null) return DASH; var d = ist(n); return WD[d.getUTCDay()] + ' ' + d.getUTCDate() + ' ' + MON[d.getUTCMonth()] + ' ' + d.getUTCFullYear(); }
  function cfReadout(b) {
    b = O(b);
    var chg = N(b.chg), v = N(b.v);
    var h = '<div class="rh"><b>' + esc(dateFull(b.d)) + '</b></div>' +
      '<div class="rg"><span>O</span><b class="num">' + pxBare(b.o) + '</b><span>H</span><b class="num">' + pxBare(b.h) + '</b><span>L</span><b class="num">' + pxBare(b.l) + '</b><span>C</span><b class="num">' + pxBare(b.c) + '</b></div>' +
      '<div class="rv"><span>Change</span><b class="num ' + tone(chg) + '">' + (chg === null ? DASH : arrow(chg) + pc(Math.abs(chg), false, true)) + '</b></div>' +
      '<div class="rv"><span>Volume</span><b class="num">' + (v !== null && v > 0 ? volTxt(v) : DASH) + '</b></div>';
    var em = A(b.emas);
    if (em.length) h += '<div class="re">' + em.map(function (e) {
      var k = N(e.k), col = EMA_COL[k] || '#A9B6C4';
      return '<div><span><i style="background:' + col + '"></i>EMA ' + (k === null ? DASH : k) + '</span><b class="num">' + pxBare(e.v) + '</b></div>';
    }).join('') + '</div>';
    return h;
  }
  /* small readout above the inline chart; null = idle hint */
  function croHtml(b) {
    if (!b) return '<span class="cro-i">Press and hold, then drag to read a bar</span><span class="cro-x">' + ic('expand') + 'Tap chart to expand</span>';
    b = O(b);
    var chg = N(b.chg), v = N(b.v);
    return '<span class="cro-a"><b>' + esc(dateFull(b.d)) + '</b><span class="num"> C ' + pxBare(b.c) + '</span> <span class="num ' + tone(chg) + '">' + (chg === null ? '' : arrow(chg) + pc(Math.abs(chg), false, true)) + '</span></span>' +
      '<span class="cro-b num">O ' + pxBare(b.o) + ' · H ' + pxBare(b.h) + ' · L ' + pxBare(b.l) + ' · Vol ' + (v !== null && v > 0 ? volTxt(v) : DASH) + '</span>';
  }

  function view(S, ui) {
    S = O(S); ui = O(ui);
    if (ts(ui.now) === null) ui.now = Date.now();
    if (ui.tab === 'trades') return trades(S, ui);
    if (ui.tab === 'portfolio') return portfolio(S, ui);
    if (ui.tab === 'alerts') return alerts(S, ui);
    if (ui.tab === 'setups') return setups(S, ui);
    return today(S, ui);
  }

  /* ---------- sheets ---------- */
  function shell(title, body, foot) {
    return '<div class="scrim" data-c="sheet-close"></div><section class="sheet" role="dialog" aria-modal="true" aria-label="' + esc(title) + '">' +
      '<div class="grab"></div><div class="sh"><h2>' + esc(title) + '</h2><button type="button" class="iconbtn" data-c="sheet-close" aria-label="Close">' + ic('x') + '</button></div>' +
      '<form class="sb" id="sheetform" novalidate autocomplete="off">' + body + '</form>' + (foot ? '<div class="sf">' + foot + '</div>' : '') + '</section>';
  }
  function field(name, label, value, o) {
    o = o || {};
    return '<label class="fld" data-f="' + name + '"><span class="lb"><span>' + esc(label) + '</span>' + (o.hint ? '<i>' + esc(o.hint) + '</i>' : '') + '</span>' +
      '<span class="inp">' + (o.pre ? '<span class="u">' + esc(o.pre) + '</span>' : '') +
      '<input name="' + name + '" type="text" inputmode="' + (o.mode || 'decimal') + '" value="' + esc(value) + '"' + (o.ph ? ' placeholder="' + esc(o.ph) + '"' : '') +
      (o.caps ? ' autocapitalize="characters" spellcheck="false" autocorrect="off"' : '') + ' maxlength="' + (o.max || 14) + '" enterkeyhint="' + (o.enter || 'next') + '">' +
      (o.post ? '<span class="u post">' + esc(o.post) + '</span>' : '') + '</span>' +
      '<span class="err"></span>' + (o.help ? '<span class="help">' + esc(o.help) + '</span>' : '') + '</label>';
  }
  function takeCalc(g, price, qty) {
    g = O(g);
    var p = N(price), q = N(qty), sl = N(g.sl), t2 = N(g.t2), mx = N(g.entryMax);
    var ok = p !== null && p > 0 && q !== null && q > 0;
    var h = '<div><span>You invest</span><b>' + (ok ? rs(p * q) : DASH) + '</b></div>' +
      '<div><span>Loss if stop-loss ' + pxBare(sl) + ' hits</span><b class="down">' + (ok && sl !== null && sl > 0 && p > sl ? rs((p - sl) * q) : DASH) + '</b></div>' +
      '<div><span>Gain if Target 2 ' + pxBare(t2) + ' hits</span><b class="up">' + (ok && t2 !== null && t2 > p ? rs((t2 - p) * q) : DASH) + '</b></div>';
    if (ok && mx !== null && mx > 0 && p > mx) h += '<div><span class="down">Above the entry zone (max ' + px(mx) + '). Risk grows and reward shrinks.</span></div>';
    if (ok && sl !== null && sl > 0 && p <= sl) h += '<div><span class="down">This price is at or below the stop-loss.</span></div>';
    return h;
  }
  function closeCalc(t, price) {
    t = O(t);
    var p = N(price), e = N(t.entry), q = N(t.qty);
    var ok = p !== null && p > 0 && e !== null && e > 0 && q !== null && q > 0;
    var d = ok ? (p - e) * q : null;
    return '<div><span>Bought</span><b>' + qtyStr(q) + ' × ' + px(e) + '</b></div>' +
      '<div><span>Result before charges</span><b class="' + tone(d) + '">' + (ok ? rs(d, true) + ' (' + pc((p - e) / e * 100, true) + ')' : DASH) + '</b></div>';
  }
  function deviceList(dev) {
    var known = dev && typeof dev === 'object';
    function row(ok, title, good, bad, cmd, cta) {
      var st = !known ? 'unk' : ok === true ? 'ok' : 'no';
      return '<div class="chk ' + st + '"><span class="ci">' + ic(st === 'ok' ? 'check' : st === 'no' ? 'warn' : 'info') + '</span>' +
        '<div class="t"><b>' + title + '</b><span>' + (st === 'ok' ? good : st === 'no' ? bad : 'Could not check this setting.') + '</span></div>' +
        (st === 'ok' ? '<span class="okl">OK</span>' : btn(cmd, null, cta, 'sm')) + '</div>';
    }
    dev = O(dev);
    return row(dev.notifications, 'Notifications', 'Allowed. Alerts can show on your phone.', 'Blocked. You will not see buy or sell alerts.', 'dev-notif', 'Allow') +
      row(dev.batteryUnrestricted, 'Battery', 'Unrestricted. Scans can run in the background.', 'Restricted. Android may stop the 15-minute scans.', 'dev-battery', 'Fix') +
      row(dev.exactAlarms, 'Exact alarms', 'Allowed. Scans run on time.', 'Not allowed. Scans may run late.', 'dev-alarm', 'Allow');
  }
  function toggle(name, on, title, sub) {
    return '<button type="button" class="tog" role="switch" aria-checked="' + (on ? 'true' : 'false') + '" data-c="tog" data-id="' + name + '">' +
      '<span class="t"><b>' + title + '</b><span>' + sub + '</span></span><span class="st">' + (on ? 'On' : 'Off') + '</span><span class="sw"></span></button>';
  }
  function nums(v) { return Array.isArray(v) ? v.map(function (x) { var n = N(x); return n === null ? 0 : n; }) : []; }
  function volTxt(v) {
    var n = N(v); if (n === null || n <= 0) return DASH;
    if (n >= 1e7) return (n / 1e7).toFixed(2) + ' Cr';
    if (n >= 1e5) return (n / 1e5).toFixed(2) + ' L';
    return grp(String(Math.round(n)));
  }
  /* o (optional) = {emas:{10:true,..}, vol:true, levels:[{k:'sup'|'res'|'stop', t, v}]}. Without o this is the original chart. */
  function chartSvg(res, o) {
    var c = nums(res.c), op = nums(res.o), hi = nums(res.h), lo = nums(res.l), d = nums(res.d), m50 = nums(res.sma50), m200 = nums(res.sma200), vv = nums(res.v);
    var n = Math.min(c.length, op.length, hi.length, lo.length);
    if (n < 5) return '<p class="lead">Not enough price history to draw a chart.</p>';
    var useVol = !!(o && o.vol) && vv.length >= n && vv.slice(0, n).some(function (x) { return x > 0; });
    var Wd = 360, L = 6, R = o ? 50 : 52, Tp = 8, pw = Wd - L - R, i;
    var ph = o ? 190 : 202, vTop = Tp + ph + 6, vh = useVol ? 44 : 0;
    var Ht = o ? (useVol ? vTop + vh + 18 : Tp + ph + 22) : 230, labY = Ht - 5;
    var mn = Infinity, mx = -Infinity;
    for (i = 0; i < n; i++) { if (lo[i] > 0 && lo[i] < mn) mn = lo[i]; if (hi[i] > mx) mx = hi[i]; }
    if (!(mx > mn)) { mx = mn * 1.01 + 1; }
    var lv = (o ? A(o.levels) : A(res.levels)).map(function (x) { return { k: T(x.k || x.kind), t: T(x.t || x.label), v: N(x.v) }; }).filter(function (x) { return x.v > 0; });
    var span = mx - mn;
    lv = lv.filter(function (x) { return x.v > mn - span * 0.6 && x.v < mx + span * 0.6; });
    lv.forEach(function (x) { if (x.v < mn) mn = x.v; if (x.v > mx) mx = x.v; });
    var emaKeys = [200, 100, 50, 20, 10].filter(function (k) { return o && O(o.emas)[k]; });
    var span2 = mx - mn;
    emaKeys.forEach(function (k) { var a = nums(res['ema' + k]), lastv = a.length ? a[Math.min(a.length, n) - 1] : 0; if (lastv > 0 && lastv > mn - span2 * 0.25 && lastv < mx + span2 * 0.25) { if (lastv < mn) mn = lastv; if (lastv > mx) mx = lastv; } });
    var pad = (mx - mn) * 0.06; mn -= pad; mx += pad;
    var X = function (k) { return L + (k + 0.5) * pw / n; }, Y = function (v) { return Tp + (mx - v) / (mx - mn) * ph; };
    var lineMode = !!o && n > 300;
    var bw = Math.max(o ? 0.7 : 1, Math.min(5, pw / n * 0.62));
    var g = '<defs><clipPath id="cclip"><rect x="' + L + '" y="' + Tp + '" width="' + pw + '" height="' + ph + '"/></clipPath></defs>';
    for (i = 0; i <= 3; i++) {
      var gv = mn + (mx - mn) * i / 3, gy = Y(gv);
      g += '<line x1="' + L + '" x2="' + (Wd - R) + '" y1="' + gy.toFixed(1) + '" y2="' + gy.toFixed(1) + '" class="grid"/><text x="' + (Wd - R + 4) + '" y="' + (gy + 3.5).toFixed(1) + '" class="ax">' + esc(pxNum(gv)) + '</text>';
    }
    var lastM = -1, lastLX = -99;
    for (i = 0; i < n; i++) {
      var t = ts(d[i]); if (t === null) continue;
      var dt = ist(t), mo = dt.getUTCMonth();
      if (mo !== lastM) {
        if (lastM !== -1 && X(i) < Wd - R - 14 && (!o || X(i) - lastLX >= 36)) {
          g += '<text x="' + X(i).toFixed(1) + '" y="' + labY + '" class="ax">' + MON[mo] + (o && mo === 0 ? ' ’' + String(dt.getUTCFullYear()).slice(2) : '') + '</text>'; lastLX = X(i);
        }
        lastM = mo;
      }
    }
    var line = function (arr, cls) {
      var pts = [], seg = '';
      for (var k = 0; k < n; k++) { if (arr[k] > 0) pts.push(X(k).toFixed(1) + ',' + Y(arr[k]).toFixed(1)); else if (pts.length) { if (pts.length > 1) seg += '<polyline class="' + cls + '" points="' + pts.join(' ') + '"/>'; pts = []; } }
      if (pts.length > 1) seg += '<polyline class="' + cls + '" points="' + pts.join(' ') + '"/>';
      return seg;
    };
    var cl = '';
    if (o) { emaKeys.forEach(function (k) { cl += line(nums(res['ema' + k]), 'e' + k); }); }
    else {
      var x50 = nums(res.ema50), x200 = nums(res.ema200);
      cl += line(x200.length ? x200 : m200, 'ma200') + line(x50.length ? x50 : m50, 'ma50') + line(nums(res.ema20), 'ema20') + line(nums(res.ema10), 'ema10');
    }
    var cs = '';
    if (lineMode) cs += line(c.slice(0, n), 'cls');
    else for (i = 0; i < n; i++) {
      if (!(c[i] > 0)) continue;
      var up = c[i] >= op[i], x = X(i), y1 = Y(Math.max(op[i], c[i])), y2 = Y(Math.min(op[i], c[i]));
      cs += '<line x1="' + x.toFixed(1) + '" x2="' + x.toFixed(1) + '" y1="' + Y(hi[i]).toFixed(1) + '" y2="' + Y(lo[i] > 0 ? lo[i] : c[i]).toFixed(1) + '" class="' + (up ? 'cu' : 'cd') + '"/>' +
        '<rect x="' + (x - bw / 2).toFixed(1) + '" y="' + y1.toFixed(1) + '" width="' + bw.toFixed(1) + '" height="' + Math.max(0.8, y2 - y1).toFixed(1) + '" class="' + (up ? 'bu' : 'bd') + '"/>';
    }
    var ls = '', lbl = '', prevY = -99;
    lv.slice().sort(function (a, b) { return b.v - a.v; }).forEach(function (x) {
      var y = Y(x.v), cls = x.k === 'stop' ? 'lstop' : x.k === 'target' ? 'ltgt' : x.k === 'sup' ? 'lsup' : x.k === 'res' ? 'lres' : x.k === 'trail' ? 'lstop' : 'lent';
      ls += '<line x1="' + L + '" x2="' + (Wd - R) + '" y1="' + y.toFixed(1) + '" y2="' + y.toFixed(1) + '" class="lvl ' + cls + '"/>';
      if (o && x.t) { var ly = Math.max(y - 3, prevY + 10); if (ly > Tp + ph - 2) ly = Tp + ph - 2; prevY = ly; lbl += '<text x="' + (L + 3) + '" y="' + ly.toFixed(1) + '" class="lvt ' + cls + '">' + esc(x.t.slice(0, 18) + ' ' + pxNum(x.v)) + '</text>'; }
    });
    g += '<g clip-path="url(#cclip)">' + cl + cs + ls + '</g>' + lbl;
    if (o) {
      var lc = c[n - 1];
      if (lc > 0 && lc >= mn && lc <= mx) {
        var ly2 = Y(lc);
        g += '<line x1="' + L + '" x2="' + (Wd - R) + '" y1="' + ly2.toFixed(1) + '" y2="' + ly2.toFixed(1) + '" class="lastl"/><rect x="' + (Wd - R + 1) + '" y="' + (ly2 - 7).toFixed(1) + '" width="' + (R - 2) + '" height="14" rx="3" class="lastb"/><text x="' + (Wd - R + 4) + '" y="' + (ly2 + 3.5).toFixed(1) + '" class="lastt">' + esc(pxNum(lc)) + '</text>';
      }
    }
    if (useVol) {
      var maxV = 0; for (i = 0; i < n; i++) if (vv[i] > maxV) maxV = vv[i];
      var vw = Math.max(0.6, Math.min(5, pw / n * 0.7));
      g += '<line x1="' + L + '" x2="' + (Wd - R) + '" y1="' + (vTop + vh) + '" y2="' + (vTop + vh) + '" class="grid"/>';
      for (i = 0; i < n; i++) {
        if (!(vv[i] > 0) || !(maxV > 0)) continue;
        var bh = Math.max(0.6, vv[i] / maxV * vh);
        g += '<rect x="' + (X(i) - vw / 2).toFixed(1) + '" y="' + (vTop + vh - bh).toFixed(1) + '" width="' + vw.toFixed(1) + '" height="' + bh.toFixed(1) + '" class="' + (c[i] >= op[i] ? 'vu' : 'vd') + '"/>';
      }
      g += '<text x="' + (Wd - R + 4) + '" y="' + (vTop + 8) + '" class="ax">' + esc(volTxt(maxV)) + '</text><text x="' + (Wd - R + 4) + '" y="' + (vTop + vh) + '" class="ax">Volume</text>';
    }
    var geo = '', xh = '';
    if (o) { /* geometry for the press-and-hold crosshair (app.js reads these) */
      geo = ' data-n="' + n + '" data-l="' + L + '" data-pw="' + pw + '" data-vw="' + Wd + '" data-tp="' + Tp + '" data-ph="' + ph + '" data-mn="' + mn + '" data-mx="' + mx + '"';
      xh = '<g class="xh" id="xh"><line class="xl" x1="0" x2="0" y1="' + Tp + '" y2="' + (Tp + ph) + '"/><circle class="xd" r="3.4" cx="0" cy="0"/></g>';
    }
    return '<svg class="chart' + (o ? ' big' : '') + '" viewBox="0 0 ' + Wd + ' ' + Ht + '"' + geo + ' role="img" aria-label="' + (useVol ? 'Price chart with volume' : 'Price chart') + '">' + g + xh + '</svg>';
  }
  function chartSheet(sym, ch) {
    var res = O(ch.res), title = sym || 'Chart';
    if (T(ch.err).trim()) return shell(title, '<p class="lead">' + esc(T(ch.err).trim()) + '</p>', btn('sheet-close', null, 'Close', ''));
    if (!ch.res) return shell(title, '<div class="cload"><span class="spin"></span>Loading price history…</div>', btn('sheet-close', null, 'Close', ''));
    var st = O(res.stats), nm = T(res.name).trim();
    var lv = A(res.levels).filter(function (x) { return N(x.v) > 0; });
    var body = (nm && nm !== sym ? '<p class="lead cname">' + esc(nm) + '</p>' : '') +
      '<div class="cpx"><span class="big num">' + px(st.ltp) + '</span><span class="num ' + tone(st.ret5) + '">' + pc(st.ret5, true) + ' in a week</span></div>' +
      chartSvg(res) +
      '<div class="cleg"><span><i class="e10"></i>10-day EMA</span><span><i class="e20"></i>20-day EMA</span><span><i class="s50"></i>50-day EMA</span><span><i class="s200"></i>200-day EMA</span></div>';
    if (lv.length) body += '<div class="clv">' + lv.map(function (x) {
      var k = T(x.kind), cls = k === 'stop' ? 'lstop' : k === 'target' ? 'ltgt' : 'lent';
      var diff = N(st.ltp) > 0 ? (N(x.v) / N(st.ltp) - 1) * 100 : null;
      return '<div class="' + cls + '"><i></i><span class="t">' + tx(x.label) + '</span><b class="num">' + px(x.v) + '</b><span class="num mut">' + (diff === null ? '' : pc(diff, true)) + '</span></div>';
    }).join('') + '</div>';
    var cell = function (k, v, cls) { return '<div><div class="k">' + k + '</div><div class="v num ' + (cls || '') + '">' + v + '</div></div>'; };
    var vs = function (ma) { var a = N(ma), l = N(st.ltp); return a > 0 && l > 0 ? pc((l / a - 1) * 100, true) : DASH; };
    var vt = function (ma) { var a = N(ma), l = N(st.ltp); return a > 0 && l > 0 ? tone(l - a) : ''; };
    body += '<div class="cst">' + cell('1 month', pc(st.ret21, true), tone(st.ret21)) + cell('3 months', pc(st.ret63, true), tone(st.ret63)) + cell('6 months', pc(st.ret126, true), tone(st.ret126)) +
      cell('vs 50-day avg', vs(st.sma50), vt(st.sma50)) + cell('vs 200-day avg', vs(st.sma200), vt(st.sma200)) + cell('RSI (14)', N(st.rsi14) > 0 ? String(Math.round(N(st.rsi14))) : DASH) +
      cell('52-week high', px(st.high52)) + cell('52-week low', px(st.low52)) + cell('From high', N(st.high52) > 0 && N(st.ltp) > 0 ? pc((N(st.ltp) / N(st.high52) - 1) * 100, true) : DASH) + '</div>';
    return shell(title, body, btn('sheet-close', null, 'Close', ''));
  }
  /* ---------- stock page ---------- */
  var EMAS = [10, 20, 50, 100, 200];
  var EMA_COL = { 10: '#F2B441', 20: '#FF8F6B', 50: '#6BB3FF', 100: '#4DD0E1', 200: '#C58BFF' };
  var RANGES = [[130, '6M'], [260, '1Y'], [500, '2Y']];
  function isObj(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }
  function emaOn(ui) { var o = O(ui.emaOn), r = {}; EMAS.forEach(function (k) { r[k] = (k in o) ? !!o[k] : k !== 100; }); return r; }
  /* a value the page can trust: null when unknown (missing, or 0 meaning "not enough bars") */
  function pv(v, need, bars) {
    var n = N(v); if (n === null) return null;
    var b = N(bars);
    if (b !== null && b > 0 && b < need) return null;
    if (n === 0 && !(b !== null && b >= need)) return null;
    return n;
  }
  function croreTxt(v) {
    var n = N(v); if (n === null || n <= 0) return DASH;
    return '₹' + (n >= 1000 ? grp(String(Math.round(n))) : n >= 10 ? n.toFixed(1) : n.toFixed(2)) + ' cr';
  }
  function arrow(n) { return n > 0 ? '▲ ' : n < 0 ? '▼ ' : ''; }
  function skel(cls) { return '<div class="skel ' + (cls || '') + '"></div>'; }
  function skCard(h, lines) {
    var b = '';
    for (var i = 0; i < lines; i++) b += skel('l' + (i % 3));
    return '<section class="card sk-card"><h3 class="skh">' + esc(h) + '</h3>' + b + '</section>';
  }

  function stockHead(sym, ui) {
    var st = O(ui.stock), info = O(st.info), name = T(info.name).trim() || T(st.name).trim();
    return '<div class="sid"><b class="clip">' + (T(sym).trim() ? esc(T(sym).trim()) : DASH) + '</b><span class="clip">' + (name && name !== T(sym).trim() ? esc(name) : '&nbsp;') + '</span></div>' +
      '<button type="button" class="iconbtn" data-c="sheet-close" aria-label="Close">' + ic('x') + '</button>';
  }
  function stockPrice(ui) {
    var st = O(ui.stock);
    if (!isObj(st.info)) {
      if (T(st.err).trim()) return '';
      return '<div class="sk-px"><div class="skel pxb"></div><div class="skel pxs"></div></div>';
    }
    var d = st.info, ltp = N(d.ltp), prev = N(d.prevClose), pct = N(d.dayChgPct);
    var chg = ltp !== null && ltp > 0 && prev !== null && prev > 0 ? ltp - prev : null;
    var cls = tone(pct !== null ? pct : chg);
    var when;
    if (d.live === true) when = '<span class="lv-tag"><i></i>live' + (ts(d.asOf) !== null ? ' · ' + hm(d.asOf) : '') + '</span>';
    else when = '<span class="lc-tag">last close' + (barDayText(d.barDay) ? ' · ' + esc(barDayText(d.barDay)) : '') + '</span>';
    return '<div class="sk-px"><div class="pxl"><span class="big num">' + px(d.ltp) + '</span>' +
      '<span class="chg num ' + cls + '">' + (pct !== null || chg !== null ? arrow(pct !== null ? pct : chg) + (chg !== null ? (Math.abs(chg) > 0 ? px(Math.abs(chg)) : '₹0.00') + ' ' : '') + (pct !== null ? '(' + pc(pct, true, true) + ')' : '') : DASH) + '</span></div>' +
      '<div class="pxm">' + when + (d.liquid === false ? '<span class="nl-tag">' + ic('warn') + 'not liquid</span>' : '') + '</div></div>';
  }
  function stockActions(S, ui) {
    var st = O(ui.stock), sym = T(st.sym).trim();
    if (!isObj(st.info)) {
      if (T(st.err).trim()) return '';
      return '<div class="sk-act"><div class="skel b"></div><div class="skel b"></div></div>';
    }
    var inW = st.info.inWatch === true, busy = pend(ui, 'addWatch:' + sym) || pend(ui, 'removeWatch:' + sym);
    var held = st.info.isHolding === true || A(O(S).holdings).some(function (h) { return T(h.sym).trim().toUpperCase() === sym.toUpperCase(); });
    return '<div class="sk-act"><button type="button" class="btn grow wbtn' + (inW ? ' on' : '') + '" data-c="watch" aria-pressed="' + (inW ? 'true' : 'false') + '"' + (busy ? ' disabled' : '') + '>' +
      (inW ? 'In watchlist ✓' : ic('plus') + 'Add to watchlist') + '</button>' +
      (held ? '<div class="inhold">' + ic('check') + 'In your portfolio</div>' : btn('stock-add', sym, 'Add to holdings', 'grow', { icon: 'portfolio' })) +
      targetButton(S, ui, sym) + '</div>';
  }
  function stockChartPart(ui) {
    var st = O(ui.stock), info = O(st.info), on = emaOn(ui), res = isObj(st.chart) ? st.chart : null;
    var want = N(st.want) || 130, loading = N(st.inflight) > 0;
    if (!res && T(st.err).trim() && !isObj(st.info)) return '';
    var NL = isObj(info.listing), nb = N(O(info.listing).bars) || N(info.bars) || 0;
    var rl = !NL ? RANGES : nb > 130 ? [[130, '6M'], [500, 'Since listing']] : [[500, 'Since listing']];
    var chips = '<div class="rng"><span class="rl">Price</span><span class="rc" role="group" aria-label="Chart range">' + rl.map(function (r) {
      var on = want === r[0] || (NL && nb <= 130);
      return '<button type="button" class="rchip' + (on ? ' on' : '') + '" data-c="range" data-id="' + r[0] + '" aria-pressed="' + on + '"><span>' + r[1] + '</span></button>';
    }).join('') + '</span></div>';
    var lvls = [], lv = O(info.levels);
    if (N(lv.support) > 0) lvls.push({ k: 'sup', t: 'Support', v: lv.support });
    if (N(lv.resistance) > 0) lvls.push({ k: 'res', t: 'Resistance', v: lv.resistance });
    if (N(lv.trailStop) > 0) lvls.push({ k: 'stop', t: 'Trail stop', v: lv.trailStop });
    var body;
    if (res) body = chartSvg(res, { emas: on, vol: true, levels: lvls }) + '<button type="button" class="xpand" data-c="chartfull" aria-label="Expand the chart to full screen">' + ic('expand') + '</button>';
    else if (T(st.chartErr).trim()) body = '<div class="cerr">' + ic('warn') + '<span>' + esc(T(st.chartErr).trim()) + '</span>' + btn('chart-retry', null, 'Retry', 'sm') + '</div>';
    else body = '<div class="skel chartsk"></div>';
    var ema = EMAS.map(function (k) {
      var v = N(O(info.ema)['e' + k]);
      if (!(v > 0) && res) { var a = nums(res['ema' + k]); v = a.length ? a[a.length - 1] : null; }
      if (NL && !(v > 0)) return '';
      return '<button type="button" class="echip' + (on[k] ? ' on' : '') + '" data-c="ema" data-id="' + k + '" aria-pressed="' + (on[k] ? 'true' : 'false') + '" style="--c:' + EMA_COL[k] + '">' +
        '<span class="el"><i></i>EMA ' + k + '</span><span class="ev num">' + emaNum(v) + '</span></button>';
    }).join('');
    var legend = lvls.length ? '<div class="clg">' + lvls.map(function (x) { return '<span class="' + (x.k === 'stop' ? 'lstop' : x.k === 'sup' ? 'lsup' : 'lres') + '"><i></i>' + esc(x.t) + '</span>'; }).join('') + '</div>' : '';
    return chips + (res ? '<div class="cro" id="st-cro" aria-live="off">' + croHtml(null) + '</div>' : '') + '<div class="cwrap' + (loading && res ? ' loading' : '') + (res ? ' live' : '') + '">' + body + (loading && res ? '<div class="cov"><span class="spin"></span>Loading</div>' : '') + '</div>' +
      '<div class="elg" role="group" aria-label="Moving averages">' + ema + '</div>' + legend;
  }

  function daysAgo(n) { n = N(n); if (n === null) return ''; if (n < 0) return 'over 60 days ago'; if (n === 0) return 'today'; return n === 1 ? '1 day ago' : n + ' days ago'; }
  function crossCard(d) {
    var c = O(d.cross), above = c.state === 'ABOVE', below = c.state === 'BELOW';
    var gap = N(c.gapPct), ds = N(c.daysSince), conv = c.converging === true ? 1 : c.converging === false ? 0 : -1;
    var stt = above ? tag('hold', '10 above 20', 'up') : below ? tag('sell', '10 below 20', 'down') : tag('neutral', 'Not clear');
    var big, unit;
    if (ds === null) { big = DASH; unit = ''; }
    else if (ds === 0) { big = 'Today'; unit = 'crossed on the latest bar'; }
    else if (ds < 0) { big = '60+'; unit = 'days, no recent cross'; }
    else { big = String(Math.round(ds)); unit = ds === 1 ? 'day since the cross' : 'days since the cross'; }
    var pos = gap === null ? null : 50 + Math.max(-1, Math.min(1, gap / 2)) * 46;
    var gauge = '<div class="gz"><div class="gt" role="img" aria-label="Gap between the 10 and 20 EMA"><i class="gl"></i><i class="gr"></i><i class="gc"></i>' +
      (pos !== null ? '<i class="gm ' + tone(gap) + '" style="left:' + pos.toFixed(1) + '%"></i>' : '') + '</div>' +
      '<div class="gs"><span>10 below 20</span><span>cross</span><span>10 above 20</span></div></div>';
    var dir = conv === 1 ? '<div class="gdir"><b>→ ←</b> gap narrowing: the two lines are moving together</div>' : conv === 0 ? '<div class="gdir"><b>← →</b> gap widening: the two lines are moving apart</div>' : '';
    return '<section class="card sk-card" id="sk-cross"><h3 class="skh">10 / 20 EMA</h3>' +
      '<div class="xt"><div><div class="k">Now</div><div class="xv">' + stt + '</div></div>' +
      '<div><div class="k">Last cross</div><div class="xv num">' + esc(big) + '</div><div class="xs">' + esc(unit) + '</div></div>' +
      '<div><div class="k">Gap</div><div class="xv num ' + tone(gap) + '">' + (gap === null ? DASH : arrow(gap) + pc(Math.abs(gap), false, true)) + '</div><div class="xs">' + (conv === 1 ? 'narrowing' : conv === 0 ? 'widening' : '') + '</div></div></div>' +
      gauge + dir + (T(c.text).trim() ? '<p class="sk-p">' + esc(T(c.text).trim()) + '</p>' : '') + '</section>';
  }
  function emaTable(d) {
    var e = O(d.ema), dist = O(d.emaDist), ltp = N(d.ltp), st = O(d.stack), g = O(d.golden);
    var NL = isObj(d.listing);
    var rows = EMAS.map(function (k) {
      var v = N(e['e' + k]), dv = N(dist['e' + k]);
      if (!(v > 0)) { v = null; dv = null; if (NL) return ''; }
      else if (dv === null && ltp > 0) dv = (ltp / v - 1) * 100;
      return '<div class="et"><span class="ek"><i style="background:' + EMA_COL[k] + '"></i>EMA ' + k + '</span><span class="num ev">' + (v === null ? DASH : px(v)) + '</span>' +
        '<span class="num ed ' + (dv === null ? 'mut' : tone(dv)) + '">' + (dv === null ? DASH : arrow(dv) + pc(Math.abs(dv), false, true)) + '</span></div>';
    }).join('');
    var gl = g.state === 'ABOVE' ? tag('hold', '50 above 200', 'up') : g.state === 'BELOW' ? tag('sell', '50 below 200', 'down') : tag('neutral', '50 / 200');
    return '<section class="card sk-card" id="sk-ema"><h3 class="skh">Moving averages</h3>' +
      '<div class="et eh"><span>Average</span><span>Value</span><span>Price vs it</span></div>' + rows +
      (NL && !(N(e.e200) > 0) ? '' : '<div class="sr"><div class="srl">' + (stackTag(st.label, T(st.label) === 'BULLISH' ? 'up' : T(st.label) === 'BEARISH' ? 'down' : 'minus') || tag('neutral', 'Stack')) + '<span class="srk">Order of the averages</span></div>' + (T(st.text).trim() ? '<p class="sk-p">' + esc(T(st.text).trim()) + '</p>' : '') + '</div>' +
      '<div class="sr"><div class="srl">' + gl + '<span class="srk">50 / 200 EMA</span></div>' + (T(g.text).trim() ? '<p class="sk-p">' + esc(T(g.text).trim()) + '</p>' : '') + '</div>') + '</section>';
  }
  function checksCard(d) {
    var cs = A(d.checks).slice(0, 14);
    return '<section class="card sk-card" id="sk-checks"><h3 class="skh">Technical checklist</h3>' + (cs.length ? '<ul class="tks">' + cs.map(function (x) {
      var ok = x.ok === true;
      return '<li class="tk ' + (ok ? 'ok' : 'no') + '"><span class="ti">' + ic(ok ? 'check' : 'x') + '</span><div class="tt"><b>' + tx(x.label) + '<em class="sr-only">' + (ok ? ' (passes)' : ' (does not pass)') + '</em></b>' + (T(x.text).trim() ? '<span>' + esc(T(x.text).trim()) + '</span>' : '') + '</div></li>';
    }).join('') + '</ul>' : '<p class="sk-p mut">No checks available for this stock yet.</p>') + '</section>';
  }
  function barRow(label, v, mx, fmt) {
    if (v === null) return '<div class="mr"><span class="ml">' + label + '</span><span class="mb"><i class="mz"></i></span><span class="mv num mut">' + DASH + '</span></div>';
    var w = Math.min(50, Math.abs(v) / mx * 50), t = tone(v);
    return '<div class="mr"><span class="ml">' + label + '</span><span class="mb"><i class="mz"></i><i class="mf ' + t + '" style="left:' + (v >= 0 ? 50 : 50 - w).toFixed(1) + '%;width:' + Math.max(0.8, w).toFixed(1) + '%"></i></span><span class="mv num ' + t + '">' + arrow(v) + fmt(Math.abs(v)) + '</span></div>';
  }
  function momentumCard(d) {
    var r = O(d.ret), vs = O(d.vsNifty), bars = d.bars;
    var R = [['d5', '1 week', 6], ['d21', '1 month', 22], ['d63', '3 months', 64], ['d126', '6 months', 127], ['d250', '1 year', 251]];
    var vals = R.map(function (x) { return pv(r[x[0]], x[2], bars); });
    var mx = Math.max.apply(null, [5].concat(vals.filter(function (x) { return x !== null; }).map(Math.abs)));
    var V = [['d21', '1 month', 22], ['d63', '3 months', 64], ['d126', '6 months', 127]];
    var vv = V.map(function (x) { return pv(vs[x[0]], x[2], bars); });
    var mx2 = Math.max.apply(null, [3].concat(vv.filter(function (x) { return x !== null; }).map(Math.abs)));
    var pts = function (a) { return a.toFixed(1) + ' pts'; };
    var NL = isObj(d.listing), r1 = [], r2 = [];
    R.forEach(function (x, i) { if (!(NL && vals[i] === null)) r1.push(barRow(x[1], vals[i], mx, function (a) { return pc(a, false, true); })); });
    V.forEach(function (x, i) { if (!(NL && vv[i] === null)) r2.push(barRow(x[1], vv[i], mx2, pts)); });
    return '<section class="card sk-card" id="sk-mom"><h3 class="skh">Momentum</h3>' + (r1.length ? '<div class="mh">Price change over</div>' + r1.join('') : '<p class="sk-p mut">Not enough history yet for a return over a full week or more.</p>') +
      (r2.length ? '<div class="mh m2">Compared with the Nifty <span>stock return minus Nifty return</span></div>' + r2.join('') : '') + '</section>';
  }
  function tile(k, v, s, cls) { return '<div class="stt"><div class="k">' + k + '</div><div class="v num ' + (cls || '') + '">' + v + '</div>' + (s ? '<div class="s">' + s + '</div>' : '') + '</div>'; }
  function statsCard(d) {
    var b = d.bars, ltp = N(d.ltp);
    var rsi14 = pv(d.rsi14, 15, b), rsi2 = pv(d.rsi2, 3, b), atr = N(d.atr), atrp = N(d.atrPct);
    var vr = N(d.volRatio), hiD = N(d.high52), loD = N(d.low52), fh = N(d.fromHighPct), fl = N(d.fromLowPct);
    var h20 = N(d.high20), l20 = N(d.low20);
    var rel = function (lvl) { if (!(lvl > 0) || !(ltp > 0)) return ''; var x = (ltp / lvl - 1) * 100; return Math.abs(x) < 0.05 ? 'price is at this level' : 'price is ' + pc(Math.abs(x), false, true) + (x < 0 ? ' below' : ' above'); };
    var rng = N(d.dayLow) > 0 && N(d.dayHigh) > 0 ? emaNum(d.dayLow) + ' – ' + emaNum(d.dayHigh) : DASH;
    var tiles = [
      tile('RSI (14)', rsi14 === null ? DASH : String(Math.round(rsi14)), rsi14 === null ? '' : rsi14 >= 70 ? 'high, above 70' : rsi14 <= 30 ? 'low, below 30' : 'neutral zone'),
      tile('RSI (2)', rsi2 === null ? DASH : String(Math.round(rsi2)), rsi2 === null ? '' : rsi2 <= 10 ? 'very short-term low' : rsi2 >= 90 ? 'very short-term high' : 'mid-range'),
      tile('ATR (14)', atr > 0 ? px(atr) : DASH, atrp > 0 ? pc(atrp, false, true) + ' of price' : ''),
      tile('Volume vs 20-day avg', vr > 0 ? vr.toFixed(2) + '×' : DASH, vr > 0 ? volTxt(d.volume) + ' vs ' + volTxt(d.avgVol20) : '', vr >= 1.5 ? 'up' : ''),
      tile('Turnover', croreTxt(d.turnoverCr), d.liquid === false ? 'thin trading' : 'traded value today'),
      tile('Today’s range (₹)', rng, N(d.open) > 0 ? 'opened at ' + px(d.open) : ''),
      tile('52-week high', hiD > 0 ? px(hiD) : DASH, fh !== null && hiD > 0 ? (Math.abs(fh) < 0.05 ? 'at the high' : pc(Math.abs(fh), false, true) + ' below the high') : ''),
      tile('52-week low', loD > 0 ? px(loD) : DASH, fl !== null && loD > 0 ? (Math.abs(fl) < 0.05 ? 'at the low' : pc(Math.abs(fl), false, true) + ' above the low') : ''),
      tile('20-day high', h20 > 0 ? px(h20) : DASH, rel(h20)),
      tile('20-day low', l20 > 0 ? px(l20) : DASH, rel(l20))
    ];
    if (isObj(d.listing)) tiles = tiles.filter(function (t) { return t.indexOf('class="v num ">' + DASH + '<') < 0; });
    return '<section class="card sk-card" id="sk-stats"><h3 class="skh">Key numbers</h3><div class="sg">' + tiles.join('') + '</div></section>';
  }
  function levelsCard(d) {
    var lv = O(d.levels), ltp = N(d.ltp);
    var R = [['sup', 'Support', 'Recent 20-day low', lv.support], ['res', 'Resistance', 'Recent 20-day high (or 52-week high if close)', lv.resistance], ['stop', 'Trailing stop', 'Highest close of 22 days minus 3 × ATR', lv.trailStop]];
    return '<section class="card sk-card" id="sk-lv"><h3 class="skh">Levels <span>also dashed on the chart</span></h3>' + R.map(function (x) {
      var v = N(x[3]), ok = v > 0, df = ok && ltp > 0 ? (v / ltp - 1) * 100 : null;
      return '<div class="lr"><i class="ls ' + x[0] + '"></i><div class="lt"><b>' + x[1] + '</b><span>' + x[2] + '</span></div><div class="lrv"><b class="num">' + (ok ? px(v) : DASH) + '</b><span class="num ' + (df === null ? 'mut' : tone(df)) + '">' + (df === null ? '' : arrow(df) + pc(Math.abs(df), false, true)) + '</span></div></div>';
    }).join('') + '</section>';
  }
  function heldCard(d) {
    var v = O(d.verdict), vd = VERDICT[v.label], sg = N(d.strength);
    return '<section class="card sk-card" id="sk-held"><h3 class="skh">If you held it <span>same check as your portfolio</span></h3>' +
      '<div class="vh">' + (vd ? tag(vd.cls, vd.label, vd.icon) : tag('neutral', T(v.label).trim().toUpperCase().slice(0, 10) || 'NO CALL', 'info')) + '</div>' +
      (T(v.headline).trim() ? '<p class="sk-p hl">' + esc(T(v.headline).trim()) + '</p>' : '') +
      (sg !== null && sg > 0 ? '<div class="str big"><span>Trend strength</span><i><b style="width:' + Math.min(100, Math.round(sg)) + '%" class="' + (sg >= 65 ? 'g' : sg >= 40 ? 'a' : 'r') + '"></b></i><span class="num">' + Math.min(100, Math.round(sg)) + '</span></div>' : '') +
      '</section>';
  }
  function setupsCard(d) {
    var ss = A(d.setups).slice(0, 12);
    return '<section class="card sk-card" id="sk-setups"><h3 class="skh">Setups it matches</h3>' + (ss.length ? '<ul class="tks">' + ss.map(function (x) {
      var m = x.match === true;
      return '<li class="tk ' + (m ? 'ok' : 'na') + '"><span class="ti">' + ic(m ? 'check' : 'minus') + '</span><div class="tt"><b>' + tx(x.label) + ' ' + (m ? tag('hold', 'MATCH') : tag('neutral', 'NO MATCH')) + '</b>' + (T(x.text).trim() ? '<span>' + esc(T(x.text).trim()) + '</span>' : '') + '</div></li>';
    }).join('') + '</ul>' : '<p class="sk-p mut">No setups to compare against.</p>') + '</section>';
  }
  function stockBody(S, ui) {
    var st = O(ui.stock);
    if (!isObj(st.info)) {
      if (T(st.err).trim()) {
        return '<div class="empty sk-err" role="alert"><div class="ei">' + ic('warn') + '</div><h3>Could not load ' + tx(st.sym) + '</h3><p>' + esc(T(st.err).trim()) + '</p>' +
          '<div class="btns">' + btn('stock-retry', null, 'Retry', 'sm', { icon: 'refresh' }) + btn('sheet-close', null, 'Close', 'sm ghost') + '</div></div>';
      }
      return skCard('10 / 20 EMA', 4) + skCard('Moving averages', 5) + skCard('Technical checklist', 5) + skCard('Momentum', 4);
    }
    var d = st.info;
    return listingCard(d) + crossCard(d) + emaTable(d) + checksCard(d) + momentumCard(d) + statsCard(d) + levelsCard(d) + heldCard(d) + setupsCard(d) +
      '<section class="card sk-card" id="sk-sum"><h3 class="skh">In short</h3>' + (T(d.summary).trim() ? '<p class="sk-p">' + esc(T(d.summary).trim()) + '</p>' : '<p class="sk-p mut">No summary available.</p>') + '</section>' +
      '<p class="sk-foot">' + ic('info') + '<span>Data comes from the last scan or quote. It describes the chart and is not investment advice. Past patterns do not promise what happens next.</span></p>';
  }
  function stockPart(part, S, ui, data) {
    S = O(S); ui = O(ui); data = O(data);
    var sym = T(O(ui.stock).sym) || T(data.id);
    if (part === 'head') return stockHead(sym, ui);
    if (part === 'price') return stockPrice(ui);
    if (part === 'act') return stockActions(S, ui);
    if (part === 'verdict') return verdictCard(S, ui);
    if (part === 'panel') return panelCard(S, ui);
    if (part === 'chart') return stockChartPart(ui);
    return stockBody(S, ui);
  }
  function stockSheet(S, ui, data) {
    var sym = T(O(ui.stock).sym) || T(data.id);
    return '<div class="scrim"></div><section class="sheet full stk" role="dialog" aria-modal="true" aria-label="' + esc((sym || 'Stock') + ' stock page') + '">' +
      '<div class="sh stkh" id="st-head">' + stockHead(sym, ui) + '</div>' +
      '<div class="sb" id="st-scroll"><div id="st-price">' + stockPrice(ui) + '</div><div id="st-act">' + stockActions(S, ui) + '</div><div id="st-verdict">' + verdictCard(S, ui) + '</div><div id="st-panel">' + panelCard(S, ui) + '</div><div id="st-chart" class="st-chart">' + stockChartPart(ui) + '</div><div id="st-body">' + stockBody(S, ui) + '</div></div></section>';
  }

  /* =====================================================================================================
     v5 (SPEC5): analyst panel, new-listing card
     ===================================================================================================== */
  var STANCE = { FOR: { cls: 'for', icon: 'check' }, AGAINST: { cls: 'ag', icon: 'x' }, NEUTRAL: { cls: 'ne', icon: 'minus' } };
  var MGR_IC = { trend: 'setups', momentum: 'up', risk: 'shield', liquidity: 'layers', timing: 'clock', market: 'chart', newlisting: 'star' };
  function mgrs(p) {
    return A(O(p).managers).slice(0, 10).map(function (m, i) {
      var st = T(m.stance).trim().toUpperCase(), sg = N(m.strength);
      return { i: i, id: T(m.id).trim().toLowerCase(), name: T(m.name).trim() || T(m.id).trim() || 'Manager', stance: STANCE[st] ? st : 'NEUTRAL', strength: sg === null ? 0 : Math.max(0, Math.min(3, Math.round(sg))),
        evidence: T(m.evidence).trim(), text: T(m.text).trim(), answers: T(m.answers).trim().toLowerCase(), rebuttal: T(m.rebuttal).trim(), metrics: A(m.metrics).filter(function (x) { return T(x.label).trim() && T(x.value).trim(); }).slice(0, 6) };
    });
  }
  function strongest(ms, stance) {
    var b = null; ms.forEach(function (m) { if (m.stance === stance && (!b || m.strength > b.strength)) b = m; });
    return b;
  }
  function pnlOpen(ui, ms, m) {
    var ov = O(O(ui).pnl);
    if (m.i in ov) return !!ov[m.i];
    var f = strongest(ms, 'FOR'), a = strongest(ms, 'AGAINST');
    return (!!f && f.i === m.i) || (!!a && a.i === m.i);
  }
  function pips(n) { return '<span class="pips" role="img" aria-label="strength ' + n + ' of 3">' + [1, 2, 3].map(function (k) { return '<i' + (k <= n ? ' class="on"' : '') + '></i>'; }).join('') + '</span>'; }
  function pnlRow(ui, ms, m) {
    var o = pnlOpen(ui, ms, m), sc = STANCE[m.stance];
    var h = '<div class="pm ' + sc.cls + (o ? ' open' : '') + '"><button type="button" class="pmb" data-c="pnl" data-id="' + m.i + '" aria-expanded="' + o + '">' +
      '<span class="pmi">' + ic(MGR_IC[m.id] || 'info') + '</span><span class="pmn"><b class="clip">' + esc(m.name) + '</b>' + (m.evidence ? '<em>' + esc(m.evidence) + '</em>' : '') + '</span>' +
      '<span class="pms ' + sc.cls + '">' + ic(sc.icon) + m.stance + '</span>' + pips(m.strength) + ic('chev') + '</button>';
    if (o) {
      var to = ms.filter(function (x) { return x.id && x.id === m.answers; })[0];
      h += '<div class="pmx">' + (m.text ? '<p>' + esc(m.text) + '</p>' : '') +
        (m.metrics.length ? '<div class="pmm">' + m.metrics.map(function (x) { return '<span><i>' + esc(T(x.label).trim()) + '</i><b class="num">' + esc(T(x.value).trim()) + '</b></span>'; }).join('') + '</div>' : '') +
        (m.rebuttal ? '<p class="pmr"><b>Replies to ' + esc(to ? to.name : (m.answers || 'another manager')) + ':</b> ' + esc(m.rebuttal) + '</p>' : '') + '</div>';
    }
    return h + '</div>';
  }
  function voteBar(ms) {
    return '<div class="vbar" aria-hidden="true">' + ms.map(function (m) { return '<i class="' + STANCE[m.stance].cls + '" style="height:' + (5 + m.strength * 9) + 'px"></i>'; }).join('') + '</div>';
  }
  function chairBlock(S, d, ui) {
    var ch = O(O(d.panel).chair), lab = T(ch.label).trim().toUpperCase() || T(O(d.call).label).trim().toUpperCase(), k = CALLS[lab];
    if (!k) return '';
    var proven = ch.proven === true || (!('proven' in ch) && O(d.call).proven === true);
    var pillText = lab === 'BUY' && !proven ? 'BUY CANDIDATE' : lab === 'NA' ? 'NOT RATED' : lab;
    var pros = texts(ch.pros).slice(0, 4), cons = texts(ch.cons).slice(0, 4), up = T(ch.flipUp).trim(), dn = T(ch.flipDown).trim();
    var list = function (t, arr, cls) { return '<div class="pcc ' + cls + '"><h4>' + t + '</h4>' + (arr.length ? '<ul>' + arr.map(function (x) { return '<li>' + esc(x) + '</li>'; }).join('') + '</ul>' : '<p class="mut">' + DASH + '</p>') + '</div>'; };
    return '<div class="pch" id="st-chair"><div class="pchh"><span class="pk">Chair</span><span class="vpill ' + k.cls + '">' + ic(k.icon) + pillText + '</span></div>' +
      (T(ch.text).trim() ? '<p class="pct">' + esc(T(ch.text).trim()) + '</p>' : '') +
      (pros.length || cons.length ? '<div class="pcols">' + list('Pros', pros, 'pro') + list('Cons', cons, 'con') + '</div>' : '') +
      (up || dn ? '<div class="pflip"><h4>What would change the call</h4>' + (up ? '<p class="fu">' + ic('up') + '<span><b>Upgrade</b> ' + esc(up) + '</span></p>' : '') + (dn ? '<p class="fd">' + ic('down') + '<span><b>Downgrade</b> ' + esc(dn) + '</span></p>' : '') + '</div>' : '') + '</div>';
  }
  function panelCard(S, ui) {
    var st = O(O(ui).stock), d = st.info;
    if (!isObj(d) || !isObj(d.panel)) return '';
    var ms = mgrs(d.panel); if (!ms.length) return '';
    var f = ms.filter(function (m) { return m.stance === 'FOR'; }).length, a = ms.filter(function (m) { return m.stance === 'AGAINST'; }).length, n = ms.length - f - a;
    return '<section class="card apn" id="st-pnl" aria-label="Analyst panel"><h3 class="pnh">Analyst panel <span>· ' + ms.length + (ms.length === 1 ? ' manager' : ' managers') + '</span></h3>' +
      voteBar(ms) + '<p class="pnt" id="pnl-tally"><b class="for">' + f + ' for</b> · <b class="ag">' + a + ' against</b> · <b class="ne">' + n + ' neutral</b></p>' +
      '<p class="pnn">' + ic('info') + '<span>The call comes from the tested score. The panel explains it; the vote does not decide.</span></p>' +
      '<div class="pml">' + ms.map(function (m) { return pnlRow(ui, ms, m); }).join('') + '</div>' + chairBlock(S, d, ui) + '</section>';
  }

  /* listing facts for stocks with fewer than 253 bars of history */
  function listingCard(d) {
    var L = d.listing; if (!isObj(L)) return '';
    var ltp = N(L.ltp) > 0 ? N(L.ltp) : N(d.ltp), lp = N(L.listPrice), since = N(L.sinceListingPct);
    if (since === null && lp > 0 && ltp > 0) since = (ltp / lp - 1) * 100;
    var ld = N(L.listedDay), dl = N(L.daysListed), hi = N(L.highSince), lo = N(L.lowSince), dd = N(L.maxDdPct), av = N(L.avgValueCr), nr = N(L.barsToRated), bars = N(L.bars) > 0 ? N(L.bars) : N(d.bars);
    var pos = N(L.rangePos);
    if (hi > 0 && lo > 0 && hi > lo && ltp > 0) pos = (ltp - lo) / (hi - lo);
    else if (pos !== null && pos > 1) pos = pos / 100;
    if (pos !== null) pos = Math.max(0, Math.min(1, pos));
    var badge = 'NEW · ' + (dayNum(ld) ? 'listed ' + dayTxt(ld) : 'listing date not known') + (dl !== null && dl >= 0 ? ' · ' + cnt(dl) + (dl === 1 ? ' day' : ' days') : '');
    var row = function (k, v, s, cls) { return '<div class="lst"><div class="k">' + k + '</div><div class="v num ' + (cls || '') + '">' + v + '</div>' + (s ? '<div class="s">' + s + '</div>' : '') + '</div>'; };
    var tiles = [];
    if (hi > 0) tiles.push(row('High since listing', px(hi), dayNum(L.highDay) ? dayTxt(L.highDay, true) : ''));
    if (lo > 0) tiles.push(row('Low since listing', px(lo), dayNum(L.lowDay) ? dayTxt(L.lowDay, true) : ''));
    if (dd !== null) tiles.push(row('Worst fall from a high', dd === 0 ? '0%' : '−' + pc(Math.abs(dd), false, true), 'peak to trough', dd === 0 ? '' : 'down'));
    if (av !== null && av > 0) tiles.push(row('Average traded', croreTxt(av), 'per day'));
    return '<section class="card sk-card lcard" id="sk-listing"><div class="lbadge">' + ic('star') + '<b>' + esc(badge) + '</b></div>' +
      '<div class="lpx"><div><div class="k">Listing price</div><div class="v num">' + (lp > 0 ? px(lp) : DASH) + '</div></div><span class="la">' + ic('chev') + '</span><div><div class="k">Now</div><div class="v num">' + (ltp > 0 ? px(ltp) : DASH) + '</div></div>' +
      (since !== null ? '<div class="lret ' + tone(since) + '"><span class="num">' + arrow(since) + pc(Math.abs(since), false, true) + '</span><small>since listing</small></div>' : '') + '</div>' +
      (pos !== null ? '<div class="lrng"><div class="lrt" role="img" aria-label="Price is ' + Math.round(pos * 100) + ' percent of the way from the low to the high since listing"><i style="left:' + (pos * 100).toFixed(1) + '%"></i></div><div class="lrs"><span>Low ' + (lo > 0 ? pxBare(lo) : DASH) + '</span><span>High ' + (hi > 0 ? pxBare(hi) : DASH) + '</span></div></div>' : '') +
      (tiles.length ? '<div class="lg">' + tiles.join('') + '</div>' : '') +
      (nr !== null && nr > 0 ? '<p class="lnr">' + ic('clock') + '<span>A rating becomes possible in <b class="num">' + cnt(nr) + '</b> trading days' + (bars > 0 ? ' (' + cnt(bars) + ' of 253 so far)' : '') + '.</span></p>' : '') +
      (T(L.baseRate).trim() ? '<p class="lbr"><b>Base rate</b> ' + esc(T(L.baseRate).trim()) + '</p>' : '') + '</section>';
  }

  /* ---------- search sheet ---------- */
  function lookupSym(q) { var x = T(q).replace(/\s+/g, '').toUpperCase(); return /^[A-Z0-9&\-]{1,20}$/.test(x) ? x : ''; }
  function srRow(x, icon, now) {
    var sym = T(x.sym).trim(); if (!sym) return '';
    var nm = T(x.name).trim(), ld = dayNum(x.listedDay), isNew = x.isNew === true && !!ld;
    var yr = N(now) !== null && ts(now) !== null ? ist(ts(now)).getUTCFullYear() : new Date().getFullYear();
    return '<button type="button" class="srr" data-c="stock" data-id="' + esc(sym) + '" data-name="' + esc(nm) + '">' + (icon ? '<span class="sri">' + ic(icon) + '</span>' : '') +
      '<span class="ssym"><b class="clip">' + esc(sym) + (T(x.board).trim().toUpperCase() === 'SME' ? ' <i class="sme">SME</i>' : '') + '</b><span class="clip">' + (nm && nm !== sym ? esc(nm) : '&nbsp;') + '</span></span>' +
      (isNew ? '<span class="nchip"><b>NEW</b><small>listed ' + dayTxt(x.listedDay, ld.y === yr) + '</small></span>' : '') +
      (x.tracked === true ? '<span class="trk" title="Scanned every 15 minutes">tracked</span>' : '') + ic('chev') + '</button>';
  }
  /* freshness line + quick filters, shown above every search state */
  function listMetaLine(ui) {
    var L = ui.listMeta; if (!isObj(L)) return '';
    var n = N(L.count), up = ts(L.updatedAt), now = ts(ui.now) !== null ? ts(ui.now) : Date.now(), stale = L.stale === true, pendR = pend(ui, 'refreshSymbols');
    var cntTxt = n !== null && n > 0 ? ' · ' + grp(String(Math.round(n))) + ' stocks' : '';
    var when;
    if (up !== null) {
      var dd = dayNo(now) - dayNo(up);
      when = 'updated ' + (dd <= 0 ? 'today' : dd === 1 ? 'yesterday' : dm(up, now)) + ' ' + hm(up);
    } else when = T(L.source) === 'bundled' ? 'is the copy bundled with the app' : 'update time unknown';
    return '<div class="slist' + (stale ? ' stale' : '') + '" id="sr-list">' + ic(stale ? 'warn' : 'check') + '<span>Stock list ' + esc(when) + cntTxt + (stale ? '. It may miss new listings.' : '') + '</span>' +
      (stale ? '<button type="button" class="btn sm" data-c="refresh-symbols"' + (pendR ? ' disabled' : '') + '>' + (pendR ? '<span class="spin"></span>Refreshing' : ic('refresh') + 'Refresh') + '</button>' : '') + '</div>';
  }
  function sFilters(ui) {
    var f = T(O(ui.search).filter) || 'all', nc = N(O(ui.listMeta).newCount);
    var L = [['all', 'All'], ['new', 'New listings' + (nc !== null && nc > 0 ? ' (' + Math.round(nc) + ')' : '')], ['watch', 'Watchlist'], ['held', 'Held']];
    return '<div class="sfs" role="group" aria-label="Quick filters">' + L.map(function (x) {
      return '<button type="button" class="sfc' + (f === x[0] ? ' on' : '') + '" data-c="sfilter" data-id="' + x[0] + '" aria-pressed="' + (f === x[0]) + '">' + esc(x[1]) + '</button>';
    }).join('') + '</div>';
  }
  function filterBody(S, ui, f) {
    var ss = O(ui.search), q = T(ss.q).trim().toLowerCase(), rows, empty;
    if (f === 'new') {
      if (!Array.isArray(ss.newList)) {
        if (T(ss.newErr).trim()) return '<div class="sempty">' + ic('warn') + '<b>New listings are not available</b><span>' + esc(T(ss.newErr).trim()) + '</span></div>';
        return '<div class="srl2">' + skel('srow0') + skel('srow0') + '</div>';
      }
      rows = A(ss.newList).filter(function (x) { return T(x.sym).trim(); }).map(function (x, i) { return { x: Object.assign({}, x, { isNew: true }), i: i }; });
      rows.sort(function (a, b) { return (N(b.x.listedDay) || 0) - (N(a.x.listedDay) || 0) || a.i - b.i; });
      rows = rows.map(function (r) { return r.x; });
      empty = 'No stocks listed in the last year are in the stock list yet.';
    } else if (f === 'watch') { rows = A(S.myWatch); empty = 'Your watchlist is empty. Open any stock and tap Add to watchlist.'; }
    else { rows = A(S.holdings); empty = 'You have no holdings added yet.'; }
    if (q) rows = rows.filter(function (x) { return (T(x.sym) + ' ' + T(x.name)).toLowerCase().indexOf(q) >= 0; });
    rows = rows.filter(function (x) { return T(x.sym).trim(); });
    if (!rows.length) return '<div class="sempty">' + ic('search') + '<b>' + (q ? 'No match in this list' : 'Nothing here') + '</b><span>' + esc(q ? 'Clear the filter to search all NSE stocks.' : empty) + '</span></div>';
    return '<div class="srl2">' + rows.slice(0, 100).map(function (x) { return srRow(x, null, ui.now); }).join('') + '</div>';
  }
  function searchBody(S, ui) {
    S = O(S); ui = O(ui);
    var f = T(O(ui.search).filter) || 'all';
    return listMetaLine(ui) + sFilters(ui) + (f === 'new' || f === 'watch' || f === 'held' ? filterBody(S, ui, f) : searchAll(S, ui));
  }
  function searchAll(S, ui) {
    var ss = O(ui.search), q = T(ss.q).trim();
    if (!q) {
      var rec = A(ui.recent).filter(function (x) { return T(x.sym).trim(); }).slice(0, 8);
      var hs = A(S.holdings).filter(function (x) { return T(x.sym).trim(); }).slice(0, 14), ws = A(S.myWatch).filter(function (x) { return T(x.sym).trim(); }).slice(0, 14);
      var chip = function (x) { return '<button type="button" class="qchip" data-c="stock" data-id="' + esc(T(x.sym).trim()) + '" data-name="' + esc(T(x.name).trim()) + '">' + esc(T(x.sym).trim()) + '</button>'; };
      var h = '';
      if (rec.length) h += '<div class="shd"><h3>Recent</h3><button type="button" class="linkbtn" data-c="recent-clear">Clear</button></div><div class="srl2">' + rec.map(function (x) { return srRow(x, 'clock'); }).join('') + '</div>';
      if (hs.length) h += '<div class="shd"><h3>Your holdings</h3></div><div class="qchips">' + hs.map(chip).join('') + '</div>';
      if (ws.length) h += '<div class="shd"><h3>Your watchlist</h3></div><div class="qchips">' + ws.map(chip).join('') + '</div>';
      if (!h) h = '<div class="sempty">' + ic('search') + '<b>Look up any NSE stock</b><span>Type a symbol or part of a company name, for example “tata mot” or “RELIANCE”.</span></div>';
      else h += '<p class="shint">Type a symbol or part of a company name to search all NSE stocks.</p>';
      return h;
    }
    if (q.length < 2) return '<p class="shint">Type at least 2 letters of a symbol or company name.</p>';
    var res = Array.isArray(ss.results) ? A(ss.results).filter(function (x) { return T(x.sym).trim(); }) : null;
    var look = lookupSym(q);
    var lookBtn = look ? '<div class="btns">' + btn('lookup', look, 'Look up ' + look + ' anyway', 'sm', { icon: 'search' }) + '</div>' : '';
    if (res === null) {
      if (T(ss.err).trim()) return '<div class="sempty">' + ic('warn') + '<b>Search is not available right now</b><span>' + esc(T(ss.err).trim()) + '</span>' + lookBtn + '</div>';
      return '<div class="srl2">' + skel('srow0') + skel('srow0') + skel('srow0') + '</div>';
    }
    if (!res.length) {
      if (ss.loading === true) return '<div class="srl2 stale"><div class="shint">Searching…</div></div>';
      return '<div class="sempty">' + ic('search') + '<b>No NSE stock matches “' + esc(q.slice(0, 40)) + '”</b><span>' +
        (look ? 'Check the spelling, or look it up by this symbol anyway.' : 'Check the spelling, or try the stock’s symbol.') + '</span>' + lookBtn + '</div>';
    }
    return '<div class="srl2' + (ss.loading === true && T(ss.shown) !== q ? ' stale' : '') + '">' + res.slice(0, 25).map(function (x) { return srRow(x, null, ui.now); }).join('') + '</div>';
  }
  function searchSheet(S, ui) {
    var ss = O(ui.search), q = T(ss.q);
    return '<div class="scrim"></div><section class="sheet full srchs" role="dialog" aria-modal="true" aria-label="Search stocks">' +
      '<form class="srch" id="sheetform" role="search" novalidate autocomplete="off"><label class="sinp">' + ic('search') +
      '<input name="q" type="text" inputmode="search" enterkeyhint="search" autocapitalize="none" autocorrect="off" spellcheck="false" maxlength="40" placeholder="Search symbol or company" aria-label="Search NSE stocks" value="' + esc(q) + '">' +
      '<button type="button" class="sclr" data-c="search-clear" aria-label="Clear search"' + (q ? '' : ' hidden') + '>' + ic('x') + '</button></label>' +
      '<button type="button" class="sx" data-c="sheet-close">Close</button></form>' +
      '<div class="sb" id="sr-body">' + searchBody(S, ui) + '</div></section>';
  }

  /* =====================================================================================================
     v4 (SPEC4): live track record of the score's calls
     ===================================================================================================== */
  var TRK_N = 150;
  function bandLine(b) {
    var t = T(b).trim().replace(/(\d)\s*[-–—]\s*(\d)/g, '$1–$2');
    return t ? '<div class="vk vk2">Score band <span class="nw">' + esc(t) + '</span></div>' : '';
  }
  function shortPt(l) {
    return T(l).trim().replace(/-month/g, '-mo').replace(/-week/g, '-wk').replace(/risk-adjusted/g, 'risk-adj.').replace(/^(Near|Above) its /, '$1 ');
  }
  function dayNum(v) {
    var n = N(v); if (n === null) return null;
    var y = Math.floor(n / 10000), m = Math.floor(n / 100) % 100, d = n % 100;
    return (y >= 2000 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31) ? { y: y, m: m, d: d } : null;
  }
  function dayTxt(v, noYear) { var o = dayNum(v); return o ? o.d + ' ' + MON[o.m - 1] + (noYear ? '' : ' ' + o.y) : DASH; }
  function trk(S) { var t = O(O(S).track); return Object.keys(t).length ? t : null; }
  function gC(g) { var n = N(O(g).closed); return n !== null && n > 0 ? n : 0; }
  function trackSummary(S) {
    var t = trk(S); if (!t) return '';
    var L = O(t.live), p1 = O(L.P1), b = O(p1.buy), c = O(p1.control), n = gC(b);
    if (L.upgraded === true) return 'Upgraded: BUY beat random stocks in live trading';
    if (!n) return dayNum(t.startedDay) ? 'Live record since ' + dayTxt(t.startedDay) + ': no closed trades yet' : 'No live trades recorded yet';
    return 'Live since ' + dayTxt(t.startedDay, true) + ': ' + cnt(n) + ' closed, BUY avg ' + pc(b.avgPct, true) + ' vs random ' + pc(c.avgPct, true);
  }
  function trackLine(S) {
    var t = trk(S); if (!t) return '';
    var L = O(t.live), b = O(O(L.P1).buy), c = O(O(L.P1).control), n = gC(b), since = dayNum(t.startedDay) ? ' since ' + dayTxt(t.startedDay) : '';
    return '<div class="vtrk">' + ic('flask') + '<span class="t">Live record' + since + ': ' + (n ? cnt(n) + ' closed, BUY avg <b class="num ' + tone(b.avgPct) + '">' + pc(b.avgPct, true) + '</b> vs random <b class="num ' + tone(c.avgPct) + '">' + pc(c.avgPct, true) + '</b>' : 'no closed trades yet') +
      '. <button type="button" class="linkbtn inl" data-c="track">See the live record</button></span></div>';
  }
  function trackRow(S) {
    var sum = trackSummary(S);
    return '<button type="button" class="card trkrow" data-c="track" aria-label="Open the track record">' + ic('flask') + '<span class="tx"><b>Track record of the buy calls</b><span>' + (sum ? esc(sum) : 'Live results of the score, checked after each close') + '</span></span>' + ic('chev') + '</button>';
  }
  function trkCell(v, fn) { return '<span class="num ' + (fn || '') + '">' + v + '</span>'; }
  function statTable(a, b, la, lb) {
    a = O(a); b = O(b);
    var ca = gC(a), cb = gC(b);
    var row = function (k, fa, fb, tn) {
      return '<div class="tr"><span class="k">' + k + '</span>' + trkCell(ca ? fa : DASH, ca && tn ? tone(a[tn]) : '') + trkCell(cb ? fb : DASH, cb && tn ? tone(b[tn]) : '') + '</div>';
    };
    var sess = function (g) { var n = N(g.avgSessions); return n !== null && n > 0 ? n.toFixed(1) : DASH; };
    return '<div class="trkt"><div class="tr th"><span class="k"></span><span>' + esc(la) + '</span><span>' + esc(lb) + '</span></div>' +
      '<div class="tr"><span class="k">Closed / open</span>' + trkCell(cnt(ca) + ' / ' + cnt(N(a.open) > 0 ? N(a.open) : 0)) + trkCell(cnt(cb) + ' / ' + cnt(N(b.open) > 0 ? N(b.open) : 0)) + '</div>' +
      row('Win rate', pc(a.winPct), pc(b.winPct)) + row('Average', pc(a.avgPct, true), pc(b.avgPct, true), 'avgPct') + row('Median', pc(a.medianPct, true), pc(b.medianPct, true), 'medianPct') +
      row('Worst 5%', pc(a.worstPct, true), pc(b.worstPct, true), 'worstPct') + '<div class="tr"><span class="k">Avg sessions</span>' + trkCell(ca ? sess(a) : DASH) + trkCell(cb ? sess(b) : DASH) + '</div></div>';
  }
  function edgeBox(e, tt) {
    var n = N(e), t = N(tt);
    if (n === null) return '';
    return '<div class="trkedge"><span>BUY minus CONTROL, per trade</span><b class="num ' + tone(n) + '">' + pc(n, true) + '</b><span class="tt">t = <b class="num">' + (t === null ? DASH : t.toFixed(1)) + '</b></span></div>';
  }
  function trkBar(label, n) {
    var c = Math.max(0, n || 0), w = Math.min(100, c / TRK_N * 100);
    return '<div class="prog trkbar' + (c >= TRK_N ? ' done' : '') + '"><div class="pl"><span>' + esc(label) + '</span><span class="num">' + cnt(c) + ' / ' + TRK_N + '</span></div><div class="pb"><i style="width:' + (c > 0 ? Math.max(2, w) : 0).toFixed(0) + '%"></i></div></div>';
  }
  function recentPick(x) {
    var sym = T(x.sym).trim(); if (!sym) return '';
    var g = T(x.group).trim().toUpperCase(), st = T(x.status).trim().toUpperCase(), ret = N(x.retPct), ss = N(x.sessions);
    var r = st === 'CLOSED' ? (ret === null ? DASH : pc(ret, true)) : st === 'SKIPPED' ? 'skipped' : 'open' + (ss !== null && ss >= 0 ? ' · d' + Math.round(ss) : '');
    var gc = g === 'BUY' ? 'buy' : g === 'AVOID' ? 'sell' : 'neutral';
    return '<button type="button" class="wl trkp" data-c="stock" data-id="' + esc(sym) + '" data-name=""><b>' + esc(sym) + '</b><span class="tag plain ' + gc + '">' + esc(g || '—') + '</span><span class="pd">' + dayTxt(x.day, true) + ' · ' + esc(T(x.plan).trim() || DASH) + '</span><span class="num ' + (st === 'CLOSED' ? tone(ret) : 'mut') + '">' + r + '</span></button>';
  }
  function trackSheet(S, ui) {
    S = O(S); ui = O(ui);
    var t = trk(S), busy = isBusy(S) || pend(ui, 'replayTrack') || (t && t.busyReplay === true);
    var foot = btn('sheet-close', null, 'Close', '');
    var h = '<p class="lead">Live results of the buy calls. After each close the app records every BUY and as many random uptrend stocks (CONTROL), and follows them for up to 60 sessions. All after charges.</p>';
    var L = O(t && t.live), R = O(t && t.replay), anyLive = t && (gC(O(L.P1).buy) || gC(O(L.P1).control) || gC(O(L.P2).buy) || gC(O(L.P2).control) || gC(L.avoid) || A(t.recent).length);
    if (!t) {
      h += '<div class="empty"><div class="ei">' + ic('flask') + '</div><h3>No record yet</h3><p>The record starts after the first full scan following a market close. Nothing is recorded or shown until then.</p></div>';
    } else {
      h += '<h3>Live record' + (dayNum(t.startedDay) ? ' <small>since ' + dayTxt(t.startedDay) + '</small>' : '') + '</h3>';
      if (L.upgraded === true) h += '<div class="vevid ok">' + ic('check') + '<div><b>Upgraded to BUY</b><span>BUY beat the random control in live trading, so the label no longer says candidate.</span></div></div>';
      if (!anyLive) h += '<div class="hint hintpad">' + ic('info') + '<span class="t">No picks have closed yet. A pick closes at its exit or after 60 sessions, so the first results take a few weeks.</span></div>';
      h += '<div class="trkh">P1 · earlier plan <small>stop 3 × ATR, exit on a close below the 50-day average</small></div>' + statTable(O(L.P1).buy, O(L.P1).control, 'BUY', 'CONTROL') + (false ? edgeBox(L.edgePct, L.edgeT) : '') +
        '<div class="trkh">P2 · the app’s plan <small>5 × ATR stop, otherwise hold 60 trading days</small></div>' + statTable(O(L.P2).buy, O(L.P2).control, 'BUY', 'CONTROL') + (gC(O(L.P2).buy) && gC(O(L.P2).control) ? edgeBox(L.edgePct, L.edgeT) : '');
      h += '<h3>Progress to the upgrade</h3>' + trkBar('BUY, closed P2 trades', gC(O(L.P2).buy)) + trkBar('CONTROL, closed P2 trades', gC(O(L.P2).control)) +
        (T(L.needText).trim() ? '<p class="trkneed">' + esc(T(L.needText).trim()) + '</p>' : '');
      h += '<h3>AVOID calls <small>should do worse than random</small></h3>' + statTable(L.avoid, L.avoidCtrl, 'AVOID', 'CONTROL');
      var rec = A(t.recent).filter(function (x) { return T(x.sym).trim(); }).slice(0, 20);
      h += '<h3>Recent picks</h3>' + (rec.length ? '<div class="card mv trkl">' + rec.map(recentPick).join('') + '</div>' : '<div class="hint hintpad">' + ic('info') + '<span class="t">No picks recorded yet.</span></div>');
      var hasR = gC(O(R.P1).buy) || gC(O(R.P1).control) || gC(O(R.P2).buy);
      h += '<h3>Replay <small>' + (dayNum(R.fromDay) && dayNum(R.toDay) ? dayTxt(R.fromDay, true) + ' to ' + dayTxt(R.toDay) : 'past 12–18 months') + '</small></h3>' +
        '<div class="hint hintpad">' + ic('warn') + '<span class="t">The replay covers the same period used to design the score, so it does not count toward the upgrade. Only the live record does.</span></div>';
      if (hasR) h += '<div class="trkh">P1 · earlier plan</div>' + statTable(O(R.P1).buy, O(R.P1).control, 'BUY', 'CONTROL') +
        '<div class="trkh">P2 · the app’s plan</div>' + statTable(O(R.P2).buy, O(R.P2).control, 'BUY', 'CONTROL') + (gC(O(R.P2).buy) && gC(O(R.P2).control) ? edgeBox(R.edgePct, R.edgeT) : '') +
        '<div class="trkh">AVOID</div>' + statTable(R.avoid, R.avoidCtrl, 'AVOID', 'CONTROL');
      else h += '<div class="hint hintpad">' + ic('info') + '<span class="t">' + (t.replayDone === true ? 'The replay found no completed picks.' : 'The replay has not run yet.') + '</span></div>';
      if (busy && isBusy(S)) h += '<div class="card"><div class="cb" style="padding-top:2px">' + progress(S.busy) + '</div></div>';
      h += '<div class="btns">' + btn('replay', null, busy ? 'Replaying' : 'Replay', 'grow', { busy: busy, icon: 'refresh' }) +
        btn('resettrack', null, 'Reset live record', 'grow ghost dan', { disabled: busy, icon: 'reset' }) + '</div>';
    }
    return shell('Track record', h, foot);
  }

  function sheet(kind, S, ui, data) {
    S = O(S); ui = O(ui); data = O(data);
    var id = T(data.id);
    if (kind === 'chart') return chartSheet(id, O(ui.chart));
    if (kind === 'stock') return stockSheet(S, ui, data);
    if (kind === 'chartfull') return cfSheet(S, ui, data);
    if (kind === 'target') return targetSheet(S, ui, data);
    if (kind === 'search') return searchSheet(S, ui);
    if (kind === 'track') return trackSheet(S, ui);
    if (kind === 'resettrack') return shell('Reset the live record?', '<p class="lead">All picks recorded on this phone since the live record began will be deleted and the count starts again from zero. The replay is kept. Trades, holdings and strategies are not affected.</p>',
        btn('sheet-close', null, 'Keep it', 'ghost') + btn('submit', null, 'Reset record', 'dan'));
    if (kind === 'take') {
      var g = A(S.signals).filter(function (x) { return T(x.id) === id; })[0];
      if (!g) return shell('Buy idea', '<p class="lead">This buy idea is no longer available.</p>', btn('sheet-close', null, 'Close', ''));
      return shell('Bought ' + (T(g.sym).trim() || 'this stock'),
        '<p class="lead">Enter what you actually paid. SwingEdge will then follow this trade and tell you when to book or exit. Idea: buy <b>' + px(g.entry) + ' – ' + pxBare(g.entryMax) + '</b>, stop-loss <b>' + px(g.sl) + '</b>.</p>' +
        '<div class="two">' + field('price', 'Buy price', inputNum(g.entry), { pre: '₹' }) + field('qty', 'Quantity', inputNum(g.qty), { mode: 'numeric', post: 'shares', enter: 'done', max: 9 }) + '</div>' +
        '<div class="calc" id="calc">' + takeCalc(g, g.entry, g.qty) + '</div><div class="formerr" id="formerr" hidden></div>',
        btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, 'Save trade', 'pri', { icon: 'check' }));
    }
    if (kind === 'close') {
      var t = A(S.trades).filter(function (x) { return T(x.id) === id; })[0];
      if (!t) return shell('Close trade', '<p class="lead">This trade is no longer open.</p>', btn('sheet-close', null, 'Close', ''));
      return shell('Close ' + (T(t.sym).trim() || 'trade'),
        '<p class="lead">Enter the price you sold at. The trade moves to your scorecard and follow-ups stop.</p>' +
        field('price', 'Exit price', inputNum(t.ltp), { pre: '₹', hint: 'last price ' + pxBare(t.ltp), enter: 'done' }) +
        '<div class="calc" id="calc">' + closeCalc(t, t.ltp) + '</div><div class="formerr" id="formerr" hidden></div>',
        btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, 'Close trade', 'dan'));
    }
    if (kind === 'add') {
      return shell('Add holding',
        field('sym', 'NSE symbol', T(data.sym).trim().toUpperCase().slice(0, 24), { mode: 'text', caps: true, ph: 'e.g. RELIANCE', max: 24 }) +
        '<div class="two">' + field('qty', 'Quantity', '', { mode: 'decimal', post: 'shares', max: 10 }) + field('avg', 'Average buy price', '', { pre: '₹', enter: 'done' }) + '</div>' +
        '<div class="formerr" id="formerr" hidden></div>',
        btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, 'Add holding', 'pri', { icon: 'plus' }));
    }
    if (kind === 'paste') {
      return shell('Paste holdings',
        '<p class="lead">Paste rows from your broker or a spreadsheet: symbol, quantity, average price. A header row is fine.</p>' +
        '<label class="fld" data-f="text"><textarea class="ta" name="text" spellcheck="false" autocapitalize="characters" placeholder="RELIANCE, 20, 2456.00&#10;ITC, 150, 301.50&#10;SBIN  60  742"></textarea><span class="err"></span></label>' +
        '<div class="formerr" id="formerr" hidden></div>',
        btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, 'Import rows', 'pri'));
    }
    if (kind === 'remove' || kind === 'sold') {
      var sold = kind === 'sold';
      return shell((sold ? 'Sold ' : 'Remove ') + (id || 'holding') + '?',
        '<p class="lead">' + (sold ? 'This removes <b>' + esc(id) + '</b> from your portfolio list so it is no longer checked.'
          : '<b>' + esc(id) + '</b> will be removed from your portfolio list. This does not sell anything; it only stops the checks.') + '</p>',
        btn('sheet-close', null, 'Keep it', 'ghost') + btn('submit', null, sold ? 'Yes, remove it' : 'Remove', 'dan'));
    }
    if (kind === 'clearfeed') {
      return shell('Clear all alerts?', '<p class="lead">The alert history on this screen will be emptied. Trades, holdings and strategies are not affected.</p>',
        btn('sheet-close', null, 'Keep them', 'ghost') + btn('submit', null, 'Clear alerts', 'dan'));
    }
    if (kind === 'settings') {
      var s = O(S.settings);
      function sv(v) { var n = N(v); return n === null ? '' : String(n); }
      return shell('Settings',
        '<h3>Phone setup</h3><div class="box" id="devlist">' + deviceList(ui.device) + '</div>' +
        '<h3>Money and risk</h3>' +
        field('capital', 'Trading capital', sv(s.capital), { pre: '₹', mode: 'numeric', max: 12 }) +
        '<div class="two">' + field('riskPct', 'Risk per trade', sv(s.riskPct), { post: '%', max: 5 }) + field('maxOpenTrades', 'Max open trades', sv(s.maxOpenTrades), { mode: 'numeric', max: 2 }) + '</div>' +
        field('costPct', 'Charges, round trip', sv(s.costPct), { post: '% of turnover', max: 5, enter: 'done', help: 'Brokerage, STT, stamp duty and other costs for one buy plus one sell. All results are shown after this.' }) +
        '<div class="calc" id="calc">' + settingsCalc(s.capital, s.riskPct) + '</div>' +
        '<h3>Notifications</h3><div class="box">' +
        toggle('notifyBuy', s.notifyBuy !== false, 'Buy ideas', 'When a live strategy finds a setup') +
        toggle('notifySell', s.notifySell !== false, 'Sell and exit calls', 'Stop-loss, targets, time exits, portfolio sells') +
        toggle('notifyBrief', s.notifyBrief !== false, 'Morning brief', 'One summary before the market opens') + '</div>' +
        '<div class="formerr" id="formerr" hidden></div>' +
        '<h3>How this works, and the risk</h3><ul class="risk">' +
        '<li>Signals come from fixed rules that were tested on past prices. The test numbers are shown with every buy idea.</li>' +
        '<li>Results vary. A rule that worked before can stop working, and losing trades are a normal part of this.</li>' +
        '<li>A stop-loss limits a loss but does not remove it. Prices can gap past the stop.</li>' +
        '<li>Only use money you can afford to have at risk. You place every order yourself with your broker.</li>' +
        '<li>This app is a personal tool. It is not SEBI-registered investment advice.</li></ul>',
        btn('sheet-close', null, 'Cancel', 'ghost') + btn('submit', null, 'Save settings', 'pri'));
    }
    return shell('SwingEdge', '<p class="lead">Nothing to show.</p>', btn('sheet-close', null, 'Close', ''));
  }
  function settingsCalc(capital, riskPct) {
    var c = N(capital), r = N(riskPct);
    var ok = c !== null && c > 0 && r !== null && r > 0;
    return '<div><span>Most you can lose on one trade at its stop-loss</span><b>' + (ok ? rs(c * r / 100) : DASH) + '</b></div>';
  }

  var api = {
    N: N, T: T, O: O, A: A, esc: esc, rs: rs, px: px, pc: pc, cnt: cnt, hm: hm, dm: dm, rel: rel, ts: ts, ic: ic, volTxt: volTxt, pxBare: pxBare,
    trackSummary: trackSummary, cfReadout: cfReadout, croHtml: croHtml, cfRangeChips: cfRangeChips, cfToggles: cfToggles, cfHead: function (S, ui, sym) { return cfHead(O(S), O(ui), sym); }, cfMsg: function (ui) { return cfMsg(O(ui)); },
    targetCalc: targetCalc, targetOf: function (S, sym) { return targetOf(S, sym); }, myTarget: function (S, ui) { return myTarget(O(S), O(ui)); }, scoreRing: scoreRing,
    header: function (S, ui) { return header(O(S), O(ui)); },
    tabbar: function (S, ui) { return tabbar(O(S), O(ui)); },
    panelOpen: function (ui, i) { var ms = mgrs(O(O(O(ui).stock).info).panel); var m = ms[i]; return m ? pnlOpen(ui, ms, m) : false; },
    stockPart: stockPart, searchBody: searchBody, view: view, sheet: sheet, deviceList: deviceList, deviceIssues: deviceIssues,
    takeCalc: takeCalc, closeCalc: closeCalc, settingsCalc: settingsCalc, isBusy: function (S) { return isBusy(O(S)); },
    actItems: function (S) { return actItems(O(S)); }
  };
  root.SE = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this);
