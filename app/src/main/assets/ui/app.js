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
  var ui = { tab: TABS[qs.tab] ? qs.tab : 'today', expanded: {}, pending: {}, device: null, bannerDismissed: false, importResult: null, now: Date.now(),
    recent: loadRecent(), search: { q: '', sent: null, shown: '', results: null, loading: false, err: '' }, emaOn: { 10: true, 20: true, 50: true, 100: false, 200: true }, watchEdit: false, stock: null };
  var cache = { top: null, view: null, tabs: null };
  var scrollPos = {};
  var stack = [];              // open sheets, top last: {kind, data, pushed, waiting, scroll}
  var sheet = null;            // the top of the stack
  var selfPop = 0;             // popstate events caused by our own history.back()
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
    syncStock();
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
      var ok = res.ok === true;
      var err = typeof res.error === 'string' && res.error.trim() ? res.error.trim() : 'Something went wrong';
      if (name === 'search') { onSearchResult(res, ok, err); return; }
      if (name === 'stockInfo') { onInfoResult(res, ok, err); return; }
      if (name === 'addWatch' || name === 'removeWatch') { onWatchResult(name, ok, err); return; }
      if (name === 'getChart' && onStockChart(res, ok, err)) return;
      clearPending(name, true);
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

  /* ---------- sheets (a stack: search -> stock page -> add holding ...) ---------- */
  function topSync() { sheet = stack.length ? stack[stack.length - 1] : null; }
  function scrollEl() { return elSheet.querySelector('.sb'); }
  function paintSheet(noFocus) {
    elSheet.innerHTML = SE.sheet(sheet.kind, S, ui, sheet.data);
    elSheet.hidden = false;
    if (noFocus) return;
    var f = null;
    if (sheet.kind === 'add' || sheet.kind === 'paste') f = elSheet.querySelector(sheet.kind === 'add' && SE.T(sheet.data.sym) ? 'input[name="qty"]' : 'input,textarea');
    else if (sheet.kind === 'search') f = elSheet.querySelector('input[name="q"]');
    if (f) { try { f.focus(); } catch (e) { /* ignore */ } setTimeout(function () { try { if (sheet && elSheet.contains(f)) f.focus(); } catch (e) { /* ignore */ } }, 80); }
  }
  function openSheet(kind, data, noFocus) {
    try {
      if (sheet) { var sb = scrollEl(); sheet.scroll = sb ? sb.scrollTop : 0; }
      var e = { kind: kind, data: data || {}, pushed: false, waiting: null, scroll: 0 };
      try { history.pushState({ seSheet: stack.length + 1 }, ''); e.pushed = true; } catch (x) { e.pushed = false; }
      stack.push(e); topSync();
      paintSheet(noFocus);
    } catch (e) {
      stack = []; sheet = null; elSheet.hidden = true; elSheet.innerHTML = '';
      toast('Could not open that screen', 'err');
    }
  }
  function selfBack() {
    selfPop++;
    var t = setTimeout(function () { if (selfPop > 0) selfPop--; }, 700);
    try { history.back(); } catch (e) { selfPop--; clearTimeout(t); }
  }
  function closeSheet(fromPop) {
    if (!sheet) return;
    var gone = stack.pop();
    topSync();
    try { if (document.activeElement && elSheet.contains(document.activeElement)) document.activeElement.blur(); } catch (e) { /* ignore */ }
    if (!fromPop && gone.pushed) selfBack();
    if (sheet) {
      try { paintSheet(true); var sb = scrollEl(); if (sb) sb.scrollTop = sheet.scroll || 0; } catch (e) { elSheet.hidden = true; elSheet.innerHTML = ''; stack = []; sheet = null; }
    } else { elSheet.hidden = true; elSheet.innerHTML = ''; }
    render();
  }
  window.addEventListener('popstate', function () {
    if (selfPop > 0) { selfPop--; return; }
    if (sheet) closeSheet(true);
  });
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
    if (k === 'search') { searchEnter(); return; }
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
    if (t.name === 'q') { onSearchInput(t.value); return; }
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
  /* ---------- recent symbols ---------- */
  function loadRecent() {
    try {
      var j = JSON.parse(window.localStorage.getItem('se_recent') || '[]');
      return Array.isArray(j) ? j.filter(function (x) { return x && typeof x.sym === 'string' && x.sym; }).slice(0, 8).map(function (x) { return { sym: String(x.sym).slice(0, 24), name: String(x.name || '').slice(0, 90) }; }) : [];
    } catch (e) { return []; }
  }
  function noteRecent(sym, name) {
    var list = (ui.recent || []).filter(function (x) { return x.sym !== sym; });
    var old = (ui.recent || []).filter(function (x) { return x.sym === sym; })[0];
    list.unshift({ sym: sym, name: String(name || (old && old.name) || '').slice(0, 90) });
    ui.recent = list.slice(0, 8);
    try { window.localStorage.setItem('se_recent', JSON.stringify(ui.recent)); } catch (e) { /* ignore */ }
  }

  /* ---------- search ---------- */
  var searchTimer = null, enterPending = false;
  function searchOpen() { return !!sheet && sheet.kind === 'search'; }
  function paintSearchBody() { if (!searchOpen()) return; var b = $('sr-body'); if (b) b.innerHTML = SE.searchBody(S, ui); }
  function sendSearch(q) {
    var s = ui.search; s.sent = q; s.loading = true;
    try { bridge().action('search', JSON.stringify({ q: q })); }
    catch (e) { s.loading = false; s.results = null; s.err = 'Search is not available. Try again.'; }
  }
  function onSearchInput(val) {
    var s = ui.search, q = String(val || '').trim();
    s.q = String(val || '');
    var clr = elSheet.querySelector('.sclr'); if (clr) clr.hidden = !s.q;
    if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; }
    enterPending = false;
    if (q.length < 2) { s.sent = null; s.shown = ''; s.results = null; s.err = ''; s.loading = false; paintSearchBody(); return; }
    s.err = '';
    searchTimer = setTimeout(function () { searchTimer = null; sendSearch(q); paintSearchBody(); }, 150);
  }
  function onSearchResult(res, ok, err) {
    var s = ui.search;
    if (!s || s.sent === null) return;
    if (typeof res.q === 'string' && res.q !== s.sent) return;       // stale reply for an older query
    s.loading = false; s.shown = s.sent;
    if (ok && Array.isArray(res.results)) { s.results = res.results; s.err = ''; }
    else { s.results = null; s.err = err; }
    paintSearchBody();
    if (enterPending) { enterPending = false; searchEnter(); }
  }
  function searchEnter() {
    var s = ui.search, q = String(s.q || '').trim();
    if (q.length < 2) return;
    if (searchTimer) { clearTimeout(searchTimer); searchTimer = null; sendSearch(q); enterPending = true; paintSearchBody(); return; }
    if (s.sent !== q || s.shown !== q) { enterPending = true; if (s.sent !== q) { sendSearch(q); paintSearchBody(); } return; }
    var first = SE.A(s.results)[0];
    if (first && SE.T(first.sym).trim()) { openStock(SE.T(first.sym).trim(), SE.T(first.name).trim()); return; }
    var look = q.replace(/\s+/g, '').toUpperCase();
    if (/^[A-Z0-9&\-]{1,20}$/.test(look)) openStock(look, '');
  }
  function openSearch(noFocus) {
    if (sheet && sheet.kind === 'search') return;
    ui.search = { q: '', sent: null, shown: '', results: null, loading: false, err: '' };
    enterPending = false;
    openSheet('search', {}, noFocus);
  }

  /* ---------- stock page ---------- */
  var PARTS = { head: 'st-head', price: 'st-price', act: 'st-act', chart: 'st-chart', body: 'st-body' };
  var bootScroll = null;
  function stockOpen() { return !!sheet && sheet.kind === 'stock' && !!ui.stock; }
  function paintStock(parts) {
    if (!stockOpen()) return;
    parts.forEach(function (p) { var el = $(PARTS[p]); if (el) { var h = SE.stockPart(p, S, ui, sheet.data); el.innerHTML = h; el._h = h; } });
    if (bootScroll && ui.stock && (ui.stock.info || ui.stock.err) && (ui.stock.chart || ui.stock.chartErr || ui.stock.err)) applyBootScroll();
  }
  function applyBootScroll() {
    var b = bootScroll; bootScroll = null;
    var sb = scrollEl(); if (!sb || !b) return;
    if (b.at) { var el = $(b.at); if (el) sb.scrollTop = Math.max(0, el.getBoundingClientRect().top - sb.getBoundingClientRect().top + sb.scrollTop - 4); }
    else sb.scrollTop = b.y;
  }
  function watchPending(sym) { return !!(ui.pending['addWatch:' + sym] || ui.pending['removeWatch:' + sym]); }
  function syncStock() {
    try {
      var st = ui.stock;
      if (!stockOpen() || !st.info || typeof st.info !== 'object') return;
      if (Array.isArray(S.myWatch) && !watchPending(st.sym)) {
        var inW = S.myWatch.some(function (x) { return x && typeof x.sym === 'string' && x.sym.toUpperCase() === st.sym; });
        if (inW !== (st.info.inWatch === true)) st.info.inWatch = inW;
      }
      var el = $('st-act'), h = SE.stockPart('act', S, ui, sheet.data);
      if (el && el._h !== h) { el.innerHTML = h; el._h = h; }     // only the two buttons; the rest of the page is left alone
    } catch (e) { /* ignore */ }
  }
  function requestInfo() {
    var st = ui.stock; if (!st) return;
    var tok = st.itok = (st.itok || 0) + 1;
    st.err = ''; st.info = null;
    if (st.itimer) clearTimeout(st.itimer);
    st.itimer = setTimeout(function () {
      if (ui.stock === st && st.itok === tok && !st.info && !st.err) { st.err = 'No reply after 20 seconds. Check your connection and try again.'; paintStock(['head', 'price', 'act', 'chart', 'body']); }
    }, 20000);
    try { bridge().action('stockInfo', JSON.stringify({ sym: st.sym })); }
    catch (e) { st.err = 'Could not reach the app. Try again.'; }
  }
  function requestChart(bars) {
    var st = ui.stock; if (!st) return;
    var tok = st.ctok = (st.ctok || 0) + 1;
    st.inflight = bars; st.chartErr = '';
    if (st.ctimer) clearTimeout(st.ctimer);
    st.ctimer = setTimeout(function () {
      if (ui.stock === st && st.ctok === tok && st.inflight) {
        st.inflight = 0;
        if (st.chart) { st.want = st.range || 130; toast('The chart is taking too long. Try again.', 'err'); } else st.chartErr = 'The chart did not load in time.';
        paintStock(['chart']);
      }
    }, 20000);
    try { bridge().action('getChart', JSON.stringify({ sym: st.sym, bars: bars })); }
    catch (e) { st.inflight = 0; st.chartErr = 'Could not reach the app.'; }
  }
  function openStock(sym, name) {
    sym = String(sym || '').trim().toUpperCase();
    if (!sym) return;
    name = String(name || '').trim();
    if (!name) { var r = (ui.recent || []).filter(function (x) { return x.sym === sym; })[0]; name = r ? r.name : ''; }
    if (!name) { var h = SE.A(S.holdings).concat(SE.A(S.myWatch), SE.A(S.watch)).filter(function (x) { return SE.T(x.sym).toUpperCase() === sym; })[0]; name = h ? SE.T(h.name) : ''; }
    ui.stock = { sym: sym, name: name, info: null, err: '', chart: null, chartErr: '', range: 0, want: 130, inflight: 0 };
    noteRecent(sym, name);
    if (sheet && sheet.kind === 'stock') { sheet.data = { id: sym }; paintSheet(true); }
    else openSheet('stock', { id: sym }, true);
    try { var sb = scrollEl(); if (sb) sb.scrollTop = 0; } catch (e) { /* ignore */ }
    requestInfo(); requestChart(130);
  }
  function onInfoResult(res, ok, err) {
    var st = ui.stock;
    if (!st || st.info) return;
    if (res.sym && String(res.sym).toUpperCase() !== st.sym) return;  // reply for another symbol
    if (st.itimer) { clearTimeout(st.itimer); st.itimer = null; }
    if (ok) { st.info = res; st.err = ''; if (!st.name && typeof res.name === 'string') st.name = res.name; if (res.name) noteRecent(st.sym, String(res.name)); }
    else { st.info = null; st.err = err; }
    paintStock(['head', 'price', 'act', 'chart', 'body']);
  }
  function onStockChart(res, ok, err) {
    var st = ui.stock;
    if (!st || !(st.inflight > 0)) return false;
    if (res.sym && String(res.sym).toUpperCase() !== st.sym) return false;
    if (st.ctimer) { clearTimeout(st.ctimer); st.ctimer = null; }
    var bars = st.inflight; st.inflight = 0;
    if (ok && Array.isArray(res.c)) { st.chart = res; st.range = bars; st.chartErr = ''; }
    else if (st.chart) { st.want = st.range || 130; toast('Chart: ' + err, 'err'); }
    else st.chartErr = err;
    if (ok && st.want !== bars) requestChart(st.want);
    paintStock(['chart']);
    return true;
  }
  function retryStock() {
    var st = ui.stock; if (!st) return;
    requestInfo();
    if (!st.chart && !st.inflight) requestChart(st.want || 130);
    paintStock(['head', 'price', 'act', 'chart', 'body']);
  }

  /* ---------- watchlist ---------- */
  var watchQ = [];
  function watchCall(name, sym, onFail) {
    var key = name + ':' + sym;
    if (ui.pending[key]) return false;
    var rec = { name: name, sym: sym, onFail: onFail, key: key };
    ui.pending[key] = setTimeout(function () {
      delete ui.pending[key];
      var i = watchQ.indexOf(rec); if (i >= 0) watchQ.splice(i, 1);
      if (onFail) onFail();
      toast('Watchlist: no reply yet. Try again.', 'err');
      render(true); paintStock(['act']);
    }, 20000);
    watchQ.push(rec);
    try { bridge().action(name, JSON.stringify({ sym: sym })); }
    catch (e) {
      clearTimeout(ui.pending[key]); delete ui.pending[key];
      var j = watchQ.indexOf(rec); if (j >= 0) watchQ.splice(j, 1);
      if (onFail) onFail();
      toast('Watchlist change could not start', 'err'); render(true); paintStock(['act']);
      return false;
    }
    render(true);
    return true;
  }
  function onWatchResult(name, ok, err) {
    var rec = null;
    for (var i = 0; i < watchQ.length; i++) if (watchQ[i].name === name) { rec = watchQ.splice(i, 1)[0]; break; }
    if (rec) { clearTimeout(ui.pending[rec.key]); delete ui.pending[rec.key]; }
    if (ok) toast(name === 'addWatch' ? 'Added to your watchlist' : 'Removed from your watchlist', 'ok');
    else { if (rec && rec.onFail) rec.onFail(); toast('Watchlist: ' + err, 'err'); }
    render(true); paintStock(['act']);
  }
  function toggleWatch() {
    var st = ui.stock; if (!stockOpen() || !st.info || watchPending(st.sym)) return;
    var want = !(st.info.inWatch === true), name = want ? 'addWatch' : 'removeWatch';
    st.info.inWatch = want;                                   // optimistic; rolled back on failure
    if (!watchCall(name, st.sym, function () { if (st.info) st.info.inWatch = !want; })) st.info.inWatch = !want;
    paintStock(['act']);
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
      case 'chart': openStock(id, el && el.getAttribute('data-name')); break;
      case 'stock': openStock(id, el && el.getAttribute('data-name')); break;
      case 'lookup': openStock(id, ''); break;
      case 'search': openSearch(); break;
      case 'search-clear': {
        var qi = elSheet.querySelector('input[name="q"]');
        if (qi) { qi.value = ''; onSearchInput(''); try { qi.focus(); } catch (e) { /* ignore */ } }
        break;
      }
      case 'recent-clear': ui.recent = []; try { window.localStorage.removeItem('se_recent'); } catch (e) { /* ignore */ } paintSearchBody(); break;
      case 'watch': toggleWatch(); break;
      case 'watch-edit': ui.watchEdit = !ui.watchEdit; render(true); break;
      case 'watch-remove': watchCall('removeWatch', id, null); break;
      case 'stock-add': openSheet('add', { sym: id }); break;
      case 'range': {
        var st = ui.stock, bars = Number(id);
        if (!st || !bars) break;
        if (st.want === bars && (st.range === bars || st.inflight)) break;
        st.want = bars;
        if (!st.inflight) requestChart(bars);
        paintStock(['chart']);
        break;
      }
      case 'ema': if (SE.N(id) !== null) { ui.emaOn[id] = !ui.emaOn[id]; paintStock(['chart']); } break;
      case 'stock-retry': retryStock(); break;
      case 'chart-retry': if (ui.stock) { requestChart(ui.stock.want || 130); paintStock(['chart']); } break;
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
      else if (qs.sheet === 'search') {
        openSearch(true);
        if (qs.q) { var qi2 = elSheet.querySelector('input[name="q"]'); if (qi2) { qi2.value = qs.q; onSearchInput(qs.q); } }
      }
      else if (qs.sheet === 'stock') {
        first = SE.A(S.holdings)[0];
        if (qs.sheetscroll || qs.sheetat) bootScroll = { y: Number(qs.sheetscroll) || 0, at: qs.sheetat || '' };
        openStock(qs.sym || (first ? SE.T(first.sym) : 'RELIANCE'), '');
      }
      else if ({ settings: 1, add: 1, paste: 1, clearfeed: 1 }[qs.sheet]) openSheet(qs.sheet, qs.sheet === 'add' && qs.sym ? { sym: qs.sym } : {}, true);
      else if (qs.sheet === 'chart') { first = SE.A(S.holdings)[0]; openChart(qs.sym || (first ? SE.T(first.sym) : 'RELIANCE')); }
      if (qs.sheetscroll && qs.sheet !== 'stock') { var sb = elSheet.querySelector('.sb'); if (sb) sb.scrollTop = Number(qs.sheetscroll) || 0; }
    }
  } catch (e) { /* ignore */ }
})();
