/* SwingEdge — controller: bridge protocol, polling, events, sheets, toasts. Rendering lives in render.js (window.SE). */
(function () {
  'use strict';
  var SE = window.SE;
  function $(id) { return document.getElementById(id); }
  var elTop = $('top'), elView = $('view'), elTabs = $('tabbar'), elErr = $('errbar'), elSheet = $('sheet-root'), elToasts = $('toasts');

  var qs = {};
  try {
    String(location.search || '').replace(/^\?/, '').split('&').forEach(function (kv) {
      if (!kv) return; var i = kv.indexOf('=');
      qs[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = i < 0 ? '' : decodeURIComponent(kv.slice(i + 1));
    });
  } catch (e) { /* ignore */ }

  var TABS = { today: 1, setups: 1, trades: 1, portfolio: 1, alerts: 1 };
  var S = {}, lastJson = null, lastDevJson = null;
  var ui = { tab: TABS[qs.tab] ? qs.tab : 'today', expanded: {}, pending: {}, device: null, bannerDismissed: false, importResult: null, now: Date.now() };
  var cache = { top: null, view: null, tabs: null };
  var scrollPos = {};
  var sheet = null;            // {kind, data, pushed, waiting}
  var pollTimer = null, touching = false, dirty = false;

  function bridge() { return window.Android; }

  /* ---------- render ---------- */
  function showErr(msg) {
    try { elErr.firstElementChild.textContent = msg; elErr.hidden = false; } catch (e) { /* ignore */ }
  }
  function render(force) {
    if (touching && !force) { dirty = true; return; }
    dirty = false;
    try {
      ui.now = Date.now();
      var h = SE.header(S, ui);
      if (h !== cache.top) { elTop.innerHTML = h; cache.top = h; }
      var t = SE.tabbar(S, ui);
      if (t !== cache.tabs) { elTabs.innerHTML = t; cache.tabs = t; }
      var v = SE.view(S, ui);
      if (v !== cache.view) {
        var st = elView.scrollTop, ae = document.activeElement, fc = null, fid = null;
        if (ae && elView.contains(ae) && ae.getAttribute) { fc = ae.getAttribute('data-c'); fid = ae.getAttribute('data-id'); }
        elView.innerHTML = v; cache.view = v;
        elView.scrollTop = st;
        if (fc) {
          var list = elView.querySelectorAll('[data-c]');
          for (var i = 0; i < list.length; i++) {
            if (list[i].getAttribute('data-c') === fc && list[i].getAttribute('data-id') === fid) { try { list[i].focus({ preventScroll: true }); } catch (e) { /* ignore */ } break; }
          }
        }
      }
      if (renderFailed) { renderFailed = false; elErr.hidden = true; }
    } catch (e) {
      renderFailed = true;
      showErr('Display error. Your data is safe; this screen could not be drawn.');
      try { console.error('SwingEdge render error', e && e.stack || e); } catch (e2) { /* ignore */ }
    }
  }
  var renderFailed = false;

  /* ---------- state polling ---------- */
  function readDevice() {
    try {
      var b = bridge(); if (!b || typeof b.deviceStatus !== 'function') return;
      var j = String(b.deviceStatus());
      if (j === lastDevJson) return;
      var d = JSON.parse(j);
      if (d && typeof d === 'object') {
        lastDevJson = j; ui.device = d;
        var dl = $('devlist'); if (dl) dl.innerHTML = SE.deviceList(ui.device);
      }
    } catch (e) { /* keep the previous value */ }
  }
  function pull() {
    var ok = false;
    try {
      var j = bridge().getState();
      if (typeof j === 'string') {
        if (j !== lastJson) {
          var p = JSON.parse(j);
          if (p && typeof p === 'object' && !Array.isArray(p)) { S = p; lastJson = j; ok = true; }
        } else ok = true;
      }
    } catch (e) { ok = false; }
    if (!ok && lastJson === null) showErr('Could not read app data yet. Retrying.');
    else if (ok && !renderFailed) elErr.hidden = true;
    readDevice();
    // a long action is visibly running once busy is set: hand the button over to `busy`
    if (SE.isBusy(S)) { clearPending('scanNow', true); clearPending('runBacktest', true); }
    render();
    schedule();
  }
  function schedule() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = setTimeout(pull, SE.isBusy(S) ? 3000 : 20000);
  }
  window.onStateChanged = function () { try { pull(); } catch (e) { /* ignore */ } };

  /* ---------- actions ---------- */
  var LONG = { scanNow: 1, runBacktest: 1 };
  var DONE = {
    scanNow: 'Scan finished', runBacktest: 'Re-test finished', addHolding: 'Holding added', removeHolding: 'Holding removed',
    takeTrade: 'Trade saved. Follow-ups are on.', skipSignal: 'Skipped', closeTrade: 'Trade closed', saveSettings: 'Settings saved', clearFeed: 'Alerts cleared'
  };
  var NAMES = {
    scanNow: 'Scan', runBacktest: 'Re-test', addHolding: 'Add holding', removeHolding: 'Remove holding', importHoldings: 'Import',
    takeTrade: 'Save trade', skipSignal: 'Skip', closeTrade: 'Close trade', saveSettings: 'Save settings', clearFeed: 'Clear alerts'
  };
  function clearPending(name, quiet) {
    var hit = false;
    Object.keys(ui.pending).forEach(function (k) {
      if (k === name || k.indexOf(name + ':') === 0) { clearTimeout(ui.pending[k]); delete ui.pending[k]; hit = true; }
    });
    if (hit && !quiet) render();
    return hit;
  }
  function act(name, args, key) {
    key = key || name;
    if (ui.pending[key]) return false;
    if (LONG[name] && SE.isBusy(S)) return false;
    ui.pending[key] = setTimeout(function () {
      delete ui.pending[key];
      if (!LONG[name]) toast((NAMES[name] || name) + ': no reply yet. Check the screen and try again if needed.', 'err');
      if (sheet && sheet.waiting === name) sheetIdle('No reply from the app yet. Try again.');
      render();
    }, LONG[name] ? 12000 : 20000);
    render();
    try {
      bridge().action(name, JSON.stringify(args || {}));
    } catch (e) {
      clearPending(name, true);
      toast((NAMES[name] || name) + ' could not start', 'err');
      if (sheet && sheet.waiting === name) sheetIdle('Could not reach the app. Try again.');
      render();
      return false;
    }
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = setTimeout(pull, 1200);
    return true;
  }
  window.onActionResult = function (name, json) {
    try {
      name = String(name);
      var res = null;
      try { res = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { res = null; }
      if (!res || typeof res !== 'object') res = { ok: false, error: 'Unreadable reply from the app' };
      clearPending(name, true);
      var ok = res.ok === true;
      var err = typeof res.error === 'string' && res.error.trim() ? res.error.trim() : 'Something went wrong';
      if (name === 'getChart') {
        if (ui.chart && (!res.sym || String(res.sym) === ui.chart.sym)) {
          if (chartTimer) { clearTimeout(chartTimer); chartTimer = null; }
          if (ok) ui.chart.res = res; else ui.chart.err = err;
          showChart();
        }
        return;
      }
      if (name === 'importHoldings') {
        ui.importResult = res;
        if (ok) {
          var n = SE.N(res.added) || 0, sk = Array.isArray(res.skipped) ? res.skipped.length : 0;
          toast(n + ' holding' + (n === 1 ? '' : 's') + ' imported' + (sk ? ', ' + sk + ' line' + (sk > 1 ? 's' : '') + ' skipped' : ''), n ? 'ok' : 'err');
          if (ui.tab === 'portfolio') elView.scrollTop = 0;
        } else toast('Import failed: ' + err, 'err');
      } else if (ok) toast(DONE[name] || 'Done', 'ok');
      else toast((NAMES[name] || name) + ' failed: ' + err, 'err');
      if (sheet && sheet.waiting === name) { if (ok) closeSheet(); else sheetIdle(err); }
      render();
    } catch (e) { /* never throw into the shell */ }
  };

  /* ---------- toasts ---------- */
  function toast(msg, kind) {
    try {
      var d = document.createElement('div');
      d.className = 'toast ' + (kind === 'err' ? 'err' : 'ok');
      d.setAttribute('role', kind === 'err' ? 'alert' : 'status');
      msg = String(msg); if (msg.length > 220) msg = msg.slice(0, 220) + '…';
      d.innerHTML = SE.ic(kind === 'err' ? 'warn' : 'check') + '<span>' + SE.esc(msg) + '</span>';
      elToasts.appendChild(d);
      while (elToasts.children.length > 3) elToasts.removeChild(elToasts.firstChild);
      setTimeout(function () { d.className += ' out'; setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 300); }, kind === 'err' ? 5200 : 2600);
    } catch (e) { /* ignore */ }
  }

  /* ---------- sheets ---------- */
  function openSheet(kind, data, noFocus) {
    try {
      if (sheet) { sheet.kind = kind; sheet.data = data || {}; sheet.waiting = null; }
      else {
        sheet = { kind: kind, data: data || {}, pushed: false, waiting: null };
        try { history.pushState({ seSheet: 1 }, ''); sheet.pushed = true; } catch (e) { sheet.pushed = false; }
      }
      elSheet.innerHTML = SE.sheet(kind, S, ui, sheet.data);
      elSheet.hidden = false;
      if (!noFocus && (kind === 'add' || kind === 'paste')) {
        var f = elSheet.querySelector('input,textarea');
        if (f) setTimeout(function () { try { f.focus(); } catch (e) { /* ignore */ } }, 80);
      }
    } catch (e) {
      sheet = null; elSheet.hidden = true; elSheet.innerHTML = '';
      toast('Could not open that screen', 'err');
    }
  }
  function closeSheet(fromPop) {
    if (!sheet) return;
    var pushed = sheet.pushed;
    sheet = null;
    try { if (document.activeElement && elSheet.contains(document.activeElement)) document.activeElement.blur(); } catch (e) { /* ignore */ }
    elSheet.hidden = true; elSheet.innerHTML = '';
    if (!fromPop && pushed) { try { history.back(); } catch (e) { /* ignore */ } }
    render();
  }
  window.addEventListener('popstate', function () { if (sheet) closeSheet(true); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && sheet) closeSheet(); });

  function sheetBusy(name) {
    if (!sheet) return;
    sheet.waiting = name;
    var b = elSheet.querySelector('[data-c="submit"]');
    if (b) { b.disabled = true; if (!b.querySelector('.spin')) b.insertAdjacentHTML('afterbegin', '<span class="spin"></span>'); }
    formErr('');
  }
  function sheetIdle(msg) {
    if (!sheet) return;
    sheet.waiting = null;
    var b = elSheet.querySelector('[data-c="submit"]');
    if (b) { b.disabled = false; var s = b.querySelector('.spin'); if (s) b.removeChild(s); }
    formErr(msg || '');
  }
  function formErr(msg) {
    var f = $('formerr'); if (!f) return;
    f.textContent = msg; f.hidden = !msg;
    if (msg) { try { f.scrollIntoView(false); } catch (e) { /* ignore */ } }
  }
  function val(name) {
    var i = elSheet.querySelector('[name="' + name + '"]');
    return i ? String(i.value || '') : '';
  }
  function num(name) {
    var s = val(name).replace(/[,\s₹]/g, '');
    if (!s || !/^\d*\.?\d+$|^\d+\.$/.test(s)) return null;
    var n = Number(s); return isFinite(n) ? n : null;
  }
  function fieldErr(name, msg) {
    var f = elSheet.querySelector('[data-f="' + name + '"]'); if (!f) return;
    f.className = msg ? 'fld bad' : 'fld';
    var e = f.querySelector('.err'); if (e) e.textContent = msg || '';
  }
  function check(rules) { // [[name, ok, msg]]
    var good = true, first = null;
    rules.forEach(function (r) { fieldErr(r[0], r[1] ? '' : r[2]); if (!r[1]) { good = false; if (!first) first = r[0]; } });
    if (first) { var i = elSheet.querySelector('[name="' + first + '"]'); if (i) { try { i.focus(); } catch (e) { /* ignore */ } } }
    return good;
  }
  function submitSheet() {
    if (!sheet || sheet.waiting) return;
    var k = sheet.kind, id = SE.T(sheet.data.id), a;
    if (k === 'take') {
      var p = num('price'), q = num('qty');
      if (!check([['price', p !== null && p > 0 && p < 1e7, 'Enter the price you paid'], ['qty', q !== null && q >= 1 && Math.round(q) === q && q < 1e8, 'Whole number of shares']])) return;
      sheetBusy('takeTrade');
      if (!act('takeTrade', { signalId: id, price: p, qty: q }, 'takeTrade:' + id) && sheet && !ui.pending['takeTrade:' + id]) sheetIdle('');
    } else if (k === 'close') {
      var xp = num('price');
      if (!check([['price', xp !== null && xp > 0 && xp < 1e7, 'Enter the price you sold at']])) return;
      sheetBusy('closeTrade');
      act('closeTrade', { tradeId: id, price: xp }, 'closeTrade:' + id);
    } else if (k === 'add') {
      var sym = val('sym').trim().toUpperCase(), aq = num('qty'), av = num('avg');
      if (!check([['sym', /^[A-Z0-9&\-]{1,20}$/.test(sym), 'Letters and digits only, e.g. TATAMOTORS'],
        ['qty', aq !== null && aq > 0 && aq < 1e9, 'Enter how many shares'], ['avg', av !== null && av > 0 && av < 1e7, 'Enter your average price']])) return;
      sheetBusy('addHolding');
      act('addHolding', { sym: sym, qty: aq, avg: av }, 'addHolding');
    } else if (k === 'paste') {
      var text = val('text');
      if (!check([['text', text.trim().length > 0, 'Paste at least one row']])) return;
      sheetBusy('importHoldings');
      act('importHoldings', { text: text }, 'importHoldings');
    } else if (k === 'remove' || k === 'sold') {
      sheetBusy('removeHolding');
      act('removeHolding', { sym: id }, 'removeHolding:' + id);
    } else if (k === 'clearfeed') {
      sheetBusy('clearFeed');
      act('clearFeed', {}, 'clearFeed');
    } else if (k === 'settings') {
      var c = num('capital'), r = num('riskPct'), m = num('maxOpenTrades'), cp = num('costPct');
      if (!check([['capital', c !== null && c >= 1000 && c <= 1e10, 'At least ₹1,000'], ['riskPct', r !== null && r >= 0.05 && r <= 5, 'Between 0.05 and 5'],
        ['maxOpenTrades', m !== null && m >= 1 && m <= 20 && Math.round(m) === m, 'Whole number, 1 to 20'], ['costPct', cp !== null && cp >= 0 && cp <= 5, 'Between 0 and 5']])) return;
      a = { capital: c, riskPct: r, maxOpenTrades: m, costPct: cp };
      ['notifyBuy', 'notifySell', 'notifyBrief'].forEach(function (n) {
        var t = elSheet.querySelector('.tog[data-id="' + n + '"]'); a[n] = !!t && t.getAttribute('aria-checked') === 'true';
      });
      sheetBusy('saveSettings');
      act('saveSettings', a, 'saveSettings');
    } else closeSheet();
  }
  function recalc() {
    if (!sheet) return;
    var c = $('calc'); if (!c) return;
    try {
      var id = SE.T(sheet.data.id);
      if (sheet.kind === 'take') c.innerHTML = SE.takeCalc(SE.A(S.signals).filter(function (x) { return SE.T(x.id) === id; })[0], num('price'), num('qty'));
      else if (sheet.kind === 'close') c.innerHTML = SE.closeCalc(SE.A(S.trades).filter(function (x) { return SE.T(x.id) === id; })[0], num('price'));
      else if (sheet.kind === 'settings') c.innerHTML = SE.settingsCalc(num('capital'), num('riskPct'));
    } catch (e) { /* ignore */ }
  }
  elSheet.addEventListener('input', function (e) {
    var t = e.target; if (!t || !t.name) return;
    if (t.name === 'sym') {
      var up = t.value.toUpperCase().replace(/[^A-Z0-9&\-]/g, '');
      if (up !== t.value) { var pos = t.selectionStart; t.value = up; try { t.setSelectionRange(pos, pos); } catch (e2) { /* ignore */ } }
    }
    fieldErr(t.name, '');
    recalc();
  });
  elSheet.addEventListener('submit', function (e) { e.preventDefault(); submitSheet(); });
  elSheet.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') { e.preventDefault(); submitSheet(); }
  });

  /* ---------- commands ---------- */
  function setTab(t, anchor) {
    if (!TABS[t]) return;
    if (t !== ui.tab) { scrollPos[ui.tab] = elView.scrollTop; ui.tab = t; render(true); elView.scrollTop = anchor ? 0 : (scrollPos[t] || 0); }
    else if (!anchor) elView.scrollTop = 0;
    if (anchor) {
      var el = $(anchor);
      if (el) elView.scrollTop = Math.max(0, el.getBoundingClientRect().top - elView.getBoundingClientRect().top + elView.scrollTop - 6);
    }
  }
  function device(fn) {
    try { var b = bridge(); if (b && typeof b[fn] === 'function') b[fn](); } catch (e) { toast('Could not open that setting', 'err'); }
    setTimeout(function () { readDevice(); render(); }, 700);
    setTimeout(function () { readDevice(); render(); }, 2500);
  }
  var chartTimer = null;
  function showChart() { try { if (sheet && sheet.kind === 'chart') elSheet.innerHTML = SE.sheet('chart', S, ui, sheet.data); } catch (e) { /* ignore */ } }
  function openChart(sym) {
    sym = String(sym || '').trim();
    if (!sym) return;
    ui.chart = { sym: sym, res: null, err: '' };
    openSheet('chart', { id: sym });
    if (chartTimer) clearTimeout(chartTimer);
    chartTimer = setTimeout(function () {
      if (ui.chart && ui.chart.sym === sym && !ui.chart.res) { ui.chart.err = 'No reply yet. Close and try again.'; showChart(); }
    }, 15000);
    try { bridge().action('getChart', JSON.stringify({ sym: sym })); }
    catch (e) { ui.chart.err = 'Could not load the chart.'; showChart(); }
  }
  function command(c, id, el) {
    switch (c) {
      case 'tab': setTab(id); break;
      case 'jump': setTab(ui.tab, id); break;
      case 'strategies': setTab('alerts', 'strategies'); break;
      case 'settings': openSheet('settings'); break;
      case 'toggle': if (ui.expanded[id]) delete ui.expanded[id]; else ui.expanded[id] = 1; render(true); break;
      case 'scan': act('scanNow', {}, 'scanNow'); break;
      case 'backtest': act('runBacktest', {}, 'runBacktest'); break;
      case 'take': openSheet('take', { id: id }); break;
      case 'skip': act('skipSignal', { signalId: id }, 'skipSignal:' + id); break;
      case 'close': openSheet('close', { id: id }); break;
      case 'sold': openSheet('sold', { id: id }); break;
      case 'remove': openSheet('remove', { id: id }); break;
      case 'add': openSheet('add'); break;
      case 'paste': openSheet('paste'); break;
      case 'clearfeed': openSheet('clearfeed'); break;
      case 'pick':
        try { bridge().pickHoldingsFile(); } catch (e) { toast('Could not open the file picker', 'err'); }
        break;
      case 'dismiss-banner': ui.bannerDismissed = true; render(true); break;
      case 'dismiss-import': ui.importResult = null; render(true); break;
      case 'dev-notif': device('requestNotifications'); break;
      case 'dev-battery': device('openBatterySettings'); break;
      case 'dev-alarm': device('openExactAlarmSettings'); break;
      case 'sheet-close': closeSheet(); break;
      case 'sort': ui.sort = id; render(true); break;
      case 'chart': openChart(id); break;
      case 'submit': submitSheet(); break;
      case 'tog':
        if (el) {
          var on = el.getAttribute('aria-checked') !== 'true';
          el.setAttribute('aria-checked', on ? 'true' : 'false');
          var st = el.querySelector('.st'); if (st) st.textContent = on ? 'On' : 'Off';
        }
        break;
    }
  }
  document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el !== document && !(el.getAttribute && el.getAttribute('data-c'))) el = el.parentNode;
    if (!el || el === document || el.disabled) return;
    try { command(el.getAttribute('data-c'), el.getAttribute('data-id'), el); }
    catch (err) { try { console.error('SwingEdge command error', err && err.stack || err); } catch (e2) { /* ignore */ } toast('That did not work. Try again.', 'err'); }
  });
  $('errretry').addEventListener('click', function () {
    cache.top = cache.view = cache.tabs = null; lastJson = null; renderFailed = false; elErr.hidden = true; pull();
  });

  // do not swap the DOM under a finger: defer poll-driven renders until the touch ends
  document.addEventListener('touchstart', function () { touching = true; }, { passive: true });
  function touchEnd() { touching = false; if (dirty) setTimeout(function () { if (!touching && dirty) render(); }, 60); }
  document.addEventListener('touchend', touchEnd, { passive: true });
  document.addEventListener('touchcancel', touchEnd, { passive: true });

  document.addEventListener('visibilitychange', function () { if (!document.hidden) pull(); });
  window.addEventListener('focus', function () { readDevice(); render(); });
  setInterval(function () { if (!document.hidden) render(); }, 30000);   // keeps "3 min ago" fresh; no-op when nothing changed

  /* ---------- boot ---------- */
  pull();
  try {
    if (qs.expand) {
      SE.A(S.signals).forEach(function (g) { ui.expanded['sig:' + SE.T(g.id)] = 1; });
      SE.A(S.holdings).forEach(function (h) { ui.expanded['hold:' + SE.T(h.sym)] = 1; ui.expanded['act-hold:' + SE.T(h.sym)] = 1; });
      render(true);
    }
    if (qs.at) setTimeout(function () { setTab(ui.tab, qs.at); }, 0);
    if (qs.scroll) setTimeout(function () { elView.scrollTop = Number(qs.scroll) || 0; }, 0);
    if (qs.sheet) {
      var first;
      if (qs.sheet === 'take') { first = SE.A(S.signals).filter(function (g) { return g.status === 'NEW'; })[0]; openSheet('take', { id: first ? SE.T(first.id) : '' }, true); }
      else if (qs.sheet === 'close') { first = SE.A(S.trades).filter(function (t) { return t.status === 'OPEN'; })[0]; openSheet('close', { id: first ? SE.T(first.id) : '' }, true); }
      else if (qs.sheet === 'remove' || qs.sheet === 'sold') { first = SE.A(S.holdings)[0]; openSheet(qs.sheet, { id: first ? SE.T(first.sym) : '' }, true); }
      else if ({ settings: 1, add: 1, paste: 1, clearfeed: 1 }[qs.sheet]) openSheet(qs.sheet, {}, true);
      else if (qs.sheet === 'chart') { first = SE.A(S.holdings)[0]; openChart(qs.sym || (first ? SE.T(first.sym) : 'RELIANCE')); }
      if (qs.sheetscroll) { var sb = elSheet.querySelector('.sb'); if (sb) sb.scrollTop = Number(qs.sheetscroll) || 0; }
    }
  } catch (e) { /* ignore */ }
})();
