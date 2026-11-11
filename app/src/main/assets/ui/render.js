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
    portfolio: '<rect x="3.5" y="7" width="17" height="12.5" rx="2.5"/><path d="M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7M3.5 12.5h17"/>',
    alerts: '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2.2 2.2 0 0 0 4 0"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" transform="translate(1.2 1.2) scale(.9)"/>',
    up: '<path d="M7 17 17 7M9 7h8v8"/>',
    down: '<path d="M7 7l10 10M17 9v8H9"/>',
    half: '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5a8.5 8.5 0 0 0 0 17z" fill="currentColor"/>',
    scissors: '<circle cx="6.5" cy="7" r="2.5"/><circle cx="6.5" cy="17" r="2.5"/><path d="M8.6 8.4 20 17M8.6 15.6 20 7"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>',
    chev: '<path d="M9 6l6 6-6 6"/>',
    warn: '<path d="M12 4 2.8 19.5h18.4zM12 10v4.5M12 17.2v.3"/>',
    info: '<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5.5M12 7.6v.3"/>',
    sun: '<circle cx="12" cy="12" r="3.6"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4"/>',
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.4-5.5M19.5 4.5v4h-4"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    file: '<path d="M7 3.5h7l4 4V20.500H7zM14 3.500V8h4"/><path d="M12 11.500v5M9.8 14.500l2.2 2.2 2.2-2.2"/>',
    paste: '<rect x="5.5" y="5" width="13" height="15.5" rx="2"/><path d="M9 5V3.500h6V5M9 11h6M9 15h4"/>',
    trash: '<path d="M5 7h14M9.5 7V4.500h5V7M7 7l.8 12.500h8.400L17 7"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    pause: '<path d="M9 6v12M15 6v12"/>',
    flask: '<path d="M9.5 3.500h5M10.5 3.500v6L5 19a1 1 0 0 0 .9 1.500h12.200A1 1 0 0 0 19 19l-5.5-9.500v-6"/>',
    shield: '<path d="M12 3.5 5 6v5.500c0 4.2 3 7.4 7 9 4-1.6 7-4.8 7-9V6z"/>',
    moon: '<path d="M19.5 14.500A8 8 0 0 1 9.5 4.500a8 8 0 1 0 10 10z"/>',
    minus: '<path d="M6 12h12"/>',
    logo: '<path d="M3 17.500l5-6 4 3.5 8.5-10" stroke="#3FD68F" stroke-width="2.4"/><path d="M15.5 5h5v5" stroke="#3FD68F" stroke-width="2.4"/><path d="M3 21h18" stroke="#2C3B4D" stroke-width="2"/>'
  };
  function ic(name) { return '<svg class="ic" viewBox="0 0 24 24" aria-hidden="true">' + (IC[name] || IC.info) + '</svg>'; }

  /* ---------- small shared pieces ---------- */
  function tag(kind, label, icon) {
    return '<span class="tag ' + kind + (icon ? '' : ' plain') + '">' + (icon ? ic(icon) : '') + esc(label) + '</span>';
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
  function sortedHoldings(S) {
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
    newSignals(S).forEach(function (g) { out.push({ type: 'signal', it: g }); });
    return out;
  }
  function counts(S) {
    var items = actItems(S), c = { sell: 0, buy: 0, tradeActs: 0, holdActs: 0 };
    items.forEach(function (x) {
      if (x.type === 'signal') c.buy++; else { c.sell++; if (x.type === 'trade') c.tradeActs++; else c.holdActs++; }
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
    return { kind: 'waiting', icon: 'clock', title: 'Nothing to act on right now',
      text: 'Buy ideas usually arrive around 2:45 pm, when the day\'s price bar is nearly complete. Exits and your portfolio are checked every 15 minutes.',
      short: 'No buy ideas right now. They usually arrive around 2:45 pm.' };
  }

  /* ---------- header + tab bar ---------- */
  function header(S, ui) {
    var open = S.marketOpen === true, closed = S.marketOpen === false;
    var note = T(S.marketNote).trim() || (open ? 'Market open' : closed ? 'Market closed' : 'Market status unknown');
    note = note.split(' \u00b7 ').slice(0, 2).join(' \u00b7 '); /* header keeps status + time; the rest is shown on Today */
    var issues = deviceIssues(ui.device).length;
    var h = '<div class="topbar"><div class="brand">' + ic('logo') + 'SwingEdge</div>' +
      '<div class="mkt ' + (open ? 'open' : closed ? 'closed' : '') + '"><i class="dot"></i><span class="clip">' + esc(note) + '</span></div>' +
      '<button type="button" class="iconbtn" data-c="settings" aria-label="Settings' + (issues ? ', phone setup needs attention' : '') + '">' + ic('gear') + (issues ? '<i class="pip"></i>' : '') + '</button></div>';
    if (isBusy(S)) {
      var p = N(S.busy.pct);
      h += '<div class="busyline' + (p === null ? ' indet' : '') + '"><i' + (p === null ? '' : ' style="width:' + Math.max(0, Math.min(100, p)) + '%"') + '></i></div>';
    }
    return h;
  }
  var TABS = [['today', 'Today'], ['trades', 'Trades'], ['portfolio', 'Portfolio'], ['alerts', 'Alerts']];
  function tabbar(S, ui) {
    var c = counts(S), nOpen = openTrades(S).length;
    return TABS.map(function (t) {
      var badge = '';
      if (t[0] === 'today' && (c.sell + c.buy) > 0) badge = '<span class="badge' + (c.sell ? '' : ' g') + '">' + (c.sell + c.buy) + '</span>';
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
      '<div class="hd"><div class="who"><div class="sym">' + (exp ? tag('trim', 'TEST IDEA') : tag('buy', 'BUY', 'up')) + '<b>' + tx(g.sym) + '</b></div>' +
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
      '<div class="hd"><div class="who"><div class="sym">' + tag(a.cls, a.label, a.icon) + '<b>' + tx(t.sym) + '</b></div>' +
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
      '<div class="hd"><div class="who"><div class="sym">' + tag(v.cls, v.label, v.icon) + '<b>' + tx(h.sym) + '</b></div>' +
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
  function today(S, ui) {
    var now = ui.now, items = actItems(S), bs = buyStatus(S, now);
    var h = banner(ui) + statusPanel(S, ui, now) + glance(S);
    h += '<div class="sec"><h2>Act now</h2>' + (items.length ? '<span class="cnt">' + items.length + '</span>' : '') + '</div>';
    if (!items.length) {
      h += '<div class="empty"><div class="ei">' + ic(bs.icon) + '</div><h3>' + esc(bs.title) + '</h3><p>' + esc(bs.text) + '</p>' +
        (bs.link ? '<div class="btns">' + btn('strategies', null, bs.link, 'sm') + '</div>' : '') + '</div>';
      var ot = openTrades(S).length;
      if (ot) h += '<div class="hint hintpad">' + ic('check') + '<span class="t">' + ot + ' open trade' + (ot > 1 ? 's are' : ' is') + ' on HOLD. Nothing to do on ' + (ot > 1 ? 'them' : 'it') + '.</span></div>';
      return h;
    }
    var hasBuy = false, hasSell = false;
    items.forEach(function (x) {
      var c;
      if (x.type === 'trade') c = tradeActCard(x.it, ui);
      else if (x.type === 'holding') c = holdingActCard(x.it, ui);
      else c = signalCard(x.it, ui, now);
      if (x.type === 'signal') { if (!hasBuy) c = c.replace('<article ', '<article id="first-buy" '); hasBuy = true; }
      else { if (!hasSell) c = c.replace('<article ', '<article id="first-sell" '); hasSell = true; }
      h += c;
    });
    if (!hasBuy) h += hintLine(bs);
    return h;
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
      '<div class="hd"><div class="who"><div class="sym"><b>' + tx(t.sym) + '</b></div>' +
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
    var h = '<div class="sec"><h2>Open trades</h2><span class="cnt">' + open.length + (max !== null && max > 0 ? ' of ' + cnt(max) + ' max' : '') + '</span></div>';
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
      '<div class="hd"><div class="who"><div class="sym">' + vt + '<b>' + tx(h.sym) + '</b></div>' +
      '<div class="sub">' + qtyStr(h.qty) + ' shares · avg ' + px(h.avg) + (T(h.name).trim() && T(h.name).trim() !== sym ? ' · ' + esc(T(h.name).trim()) : '') + '</div></div>';
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
  function portfolio(S, ui) {
    var p = O(S.portfolio), hs = sortedHoldings(S);
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
    h += '<div class="sec"><h2>Holdings</h2><span class="cnt">' + hs.length + '</span></div>';
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
  function alerts(S, ui) { return feed(S, ui) + strategies(S, ui); }

  function view(S, ui) {
    S = O(S); ui = O(ui);
    if (ts(ui.now) === null) ui.now = Date.now();
    if (ui.tab === 'trades') return trades(S, ui);
    if (ui.tab === 'portfolio') return portfolio(S, ui);
    if (ui.tab === 'alerts') return alerts(S, ui);
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
  function sheet(kind, S, ui, data) {
    S = O(S); ui = O(ui); data = O(data);
    var id = T(data.id);
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
        field('sym', 'NSE symbol', '', { mode: 'text', caps: true, ph: 'e.g. RELIANCE', max: 24 }) +
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
    N: N, T: T, O: O, A: A, esc: esc, rs: rs, px: px, pc: pc, cnt: cnt, hm: hm, dm: dm, rel: rel, ts: ts, ic: ic,
    header: function (S, ui) { return header(O(S), O(ui)); },
    tabbar: function (S, ui) { return tabbar(O(S), O(ui)); },
    view: view, sheet: sheet, deviceList: deviceList, deviceIssues: deviceIssues,
    takeCalc: takeCalc, closeCalc: closeCalc, settingsCalc: settingsCalc, isBusy: function (S) { return isBusy(O(S)); },
    actItems: function (S) { return actItems(O(S)); }
  };
  root.SE = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this);
