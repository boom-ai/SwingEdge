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
    recent: loadRecent(), search: { q: '', sent: null, shown: '', results: null, loading: false, err: '', filter: 'all', newList: null, newErr: '' }, emaOn: { 10: true, 20: true, 50: true, 100: false, 200: true }, watchEdit: false, stock: null, pnl: {}, vOpen: {}, listMeta: null,
    cf: null, cfOpts: { levels: true, vol: true, rangeOn: '6M' }, confirmTarget: {}, cfBoot: null, afterInfo: null, lastTarget: null };
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
      if (sheet && sheet.kind === 'track') repaintTrack();
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
    if (sheet && sheet.kind === 'chartfull') cfMeta();
    // a long action is visibly running once busy is set: hand the button over to `busy`
    if (SE.isBusy(S)) { clearPending('scanNow', true); clearPending('runBacktest', true); clearPending('replayTrack', true); }
    render();
    schedule();
  }
  function schedule() {
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = setTimeout(pull, SE.isBusy(S) ? 3000 : 20000);
  }
  window.onStateChanged = function () { try { pull(); } catch (e) { /* ignore */ } };

  /* ---------- actions ---------- */
  var LONG = { scanNow: 1, runBacktest: 1, replayTrack: 1 };
  var DONE = {
    scanNow: 'Scan finished', runBacktest: 'Re-test finished', addHolding: 'Holding added', removeHolding: 'Holding removed',
    takeTrade: 'Trade saved. Follow-ups are on.', skipSignal: 'Skipped', closeTrade: 'Trade closed', saveSettings: 'Settings saved', clearFeed: 'Alerts cleared',
    setTarget: 'Target saved. You will get an alert when it is reached.', clearTarget: 'Target removed', replayTrack: 'Replay finished', resetTrack: 'Live record reset', refreshSymbols: 'Stock list refreshed'
  };
  var NAMES = {
    scanNow: 'Scan', runBacktest: 'Re-test', addHolding: 'Add holding', removeHolding: 'Remove holding', importHoldings: 'Import',
    takeTrade: 'Save trade', skipSignal: 'Skip', closeTrade: 'Close trade', saveSettings: 'Save settings', clearFeed: 'Clear alerts', setTarget: 'Set target', clearTarget: 'Clear target', replayTrack: 'Replay', resetTrack: 'Reset record', refreshSymbols: 'Refresh stock list'
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
      if (name === 'newListings') { onNewListings(res, ok, err); return; }
      if (name === 'refreshSymbols') {
        clearPending(name, true);
        if (ok) { toast(DONE.refreshSymbols, 'ok'); askListMeta(); if (ui.search && ui.search.filter === 'new') loadNewListings(); } else toast('Refresh failed: ' + err, 'err');
        if (!ok && ui.listMeta) ui.listMeta = Object.assign({}, ui.listMeta);
        render(); paintSearchBody(); return;
      }
      if (name === 'stockInfo') { onInfoResult(res, ok, err); return; }
      if (name === 'addWatch' || name === 'removeWatch') { onWatchResult(name, ok, err); return; }
      if (name === 'getChart' && chartReply(res, ok, err)) return;
      clearPending(name, true);
      if (name === 'setTarget' || name === 'clearTarget') onTargetResult(name, res, ok);
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
      if (name === 'setTarget' || name === 'clearTarget') ui.lastTarget = null;
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
    if (cfInst) unmountChartFull();
    elSheet.innerHTML = SE.sheet(sheet.kind, S, ui, sheet.data);
    if (sheet.kind === 'track') trackHtml = elSheet.innerHTML;
    elSheet.hidden = false;
    if (sheet.kind === 'chartfull') { mountChartFull(); return; }
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
  var trackHtml = '';
  function repaintTrack() {
    if (!sheet || sheet.kind !== 'track' || sheet.waiting) return;
    var h = SE.sheet('track', S, ui, sheet.data);
    if (h === trackHtml && elSheet.firstChild) return;
    var sb = scrollEl(), y = sb ? sb.scrollTop : 0;
    elSheet.innerHTML = h; trackHtml = h;
    sb = scrollEl(); if (sb) sb.scrollTop = y;
  }
  function selfBack() {
    selfPop++;
    var t = setTimeout(function () { if (selfPop > 0) selfPop--; }, 700);
    try { history.back(); } catch (e) { selfPop--; clearTimeout(t); }
  }
  function closeSheet(fromPop) {
    if (!sheet) return;
    var gone = stack.pop();
    if (gone && gone.kind === 'chartfull') { unmountChartFull(); ui.cf = null; }
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
    } else if (k === 'target') {
      var tsym = SE.T(sheet.data.id).trim().toUpperCase();
      var tr = optNum('target'), sr = optNum('stop'), none = tr.empty && sr.empty;
      if (!check([['target', tr.ok && !none, none ? 'Enter a target price or a stop price' : 'Enter a price above 0'], ['stop', sr.ok, 'Enter a price above 0']])) return;
      var note = val('note').trim().slice(0, 80);
      ui.lastTarget = { sym: tsym, target: tr.n, stop: sr.n, note: note };
      sheetBusy('setTarget');
      act('setTarget', { sym: tsym, target: tr.n, stop: sr.n, note: note }, 'setTarget:' + tsym);
    } else if (k === 'resettrack') {
      sheetBusy('resetTrack');
      act('resetTrack', {}, 'resetTrack');
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
  function optNum(name) {
    var raw = val(name).trim();
    if (!raw) return { empty: true, n: 0, ok: true };
    var n = num(name);
    return { empty: false, n: n === null ? 0 : n, ok: n !== null && n > 0 && n < 1e7 };
  }
  function recalc() {
    if (!sheet) return;
    var c = $('calc'); if (!c) return;
    try {
      if (sheet.kind === 'target') { c.innerHTML = SE.targetCalc(sheet.data.ltp, num('target'), num('stop')); return; }
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
    if (ok && res.list && typeof res.list === 'object' && !Array.isArray(res.list)) { ui.listMeta = res.list; paintSearchBody(); }
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
    ui.search = { q: '', sent: null, shown: '', results: null, loading: false, err: '', filter: 'all', newList: null, newErr: '' };
    enterPending = false;
    openSheet('search', {}, noFocus);
    askListMeta();
  }
  function askListMeta() { try { bridge().action('search', JSON.stringify({ q: '' })); } catch (e) { /* ignore */ } }
  function onNewListings(res, ok, err) {
    var s = ui.search; if (!s) return;
    if (ok && Array.isArray(res.results)) { s.newList = res.results; s.newErr = ''; } else { s.newList = null; s.newErr = err; }
    paintSearchBody();
  }
  function loadNewListings() {
    var s = ui.search; if (!s) return;
    s.newErr = '';
    try { bridge().action('newListings', JSON.stringify({ days: 365 })); } catch (e) { s.newErr = 'Could not reach the app. Try again.'; }
  }

  /* ---------- stock page ---------- */
  var PARTS = { head: 'st-head', price: 'st-price', act: 'st-act', verdict: 'st-verdict', panel: 'st-panel', chart: 'st-chart', body: 'st-body' };
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
      if (ui.stock === st && st.itok === tok && !st.info && !st.err) { st.err = 'No reply after 20 seconds. Check your connection and try again.'; paintStock(['head', 'price', 'act', 'verdict', 'panel', 'chart', 'body']); }
    }, 20000);
    try { bridge().action('stockInfo', JSON.stringify({ sym: st.sym })); }
    catch (e) { st.err = 'Could not reach the app. Try again.'; }
  }
  /* ---------- chart requests: the stock page and the full-screen chart share the bridge; replies carry no `bars`, so
     requests for the same symbol are sent one at a time (replies for different symbols are told apart by `sym`) ---------- */
  var chartQ = [], chartBusy = {};
  function ckey(sym) { return String(sym || '').toUpperCase(); }
  function chartPump() {
    for (var i = 0; i < chartQ.length;) {
      var j = chartQ[i], k = ckey(j.sym);
      if (chartBusy[k]) { i++; continue; }
      chartQ.splice(i, 1); chartBusy[k] = j;
      chartStart(j, k);
    }
  }
  function chartStart(j, k) {
    j.timer = setTimeout(function () {
      if (chartBusy[k] !== j) return;
      delete chartBusy[k];
      try { j.done(false, null, '__timeout'); } catch (e) { /* ignore */ }
      chartPump();
    }, 20000);
    try { bridge().action('getChart', JSON.stringify({ sym: j.sym, bars: j.bars })); }
    catch (e) {
      clearTimeout(j.timer); delete chartBusy[k];
      try { j.done(false, null, 'Could not reach the app.'); } catch (e2) { /* ignore */ }
    }
  }
  function chartEnqueue(job) { chartQ.push(job); chartPump(); }
  function chartReply(res, ok, err) {
    var k = res && res.sym ? ckey(res.sym) : '', keys = Object.keys(chartBusy);
    if (!k && keys.length === 1) k = keys[0];
    var j = k ? chartBusy[k] : null;
    if (!j) return false;
    clearTimeout(j.timer); delete chartBusy[k];
    try { j.done(ok && Array.isArray(res.c), res, err); } catch (e) { try { console.error('SwingEdge chart handler', e && e.stack || e); } catch (e2) { /* ignore */ } }
    chartPump();
    return true;
  }
  function requestChart(bars) {
    var st = ui.stock; if (!st) return;
    var tok = st.ctok = (st.ctok || 0) + 1;
    st.inflight = bars; st.chartErr = '';
    chartEnqueue({ sym: st.sym, bars: bars, done: function (ok, res, err) {
      if (ok && bars >= 500) cacheChart(st.sym, res, true);
      if (ui.stock !== st || st.ctok !== tok) return;            // superseded or left the page
      st.inflight = 0;
      if (err === '__timeout') {
        if (st.chart) { st.want = st.range || 130; toast('The chart is taking too long. Try again.', 'err'); } else st.chartErr = 'The chart did not load in time.';
        paintStock(['chart']); return;
      }
      if (ok) { st.chart = res; st.range = bars; st.chartErr = ''; }
      else if (st.chart) { st.want = st.range || 130; toast('Chart: ' + err, 'err'); }
      else st.chartErr = err;
      if (ok && st.want !== bars) requestChart(st.want);
      paintStock(['chart']);
    } });
  }
  function openStock(sym, name) {
    sym = String(sym || '').trim().toUpperCase();
    if (!sym) return;
    name = String(name || '').trim();
    if (!name) { var r = (ui.recent || []).filter(function (x) { return x.sym === sym; })[0]; name = r ? r.name : ''; }
    if (!name) { var h = SE.A(S.holdings).concat(SE.A(S.myWatch), SE.A(S.watch)).filter(function (x) { return SE.T(x.sym).toUpperCase() === sym; })[0]; name = h ? SE.T(h.name) : ''; }
    ui.pnl = {}; ui.vOpen = {};
    ui.stock = { sym: sym, name: name, info: null, err: '', chart: null, chartErr: '', range: 0, want: 130, inflight: 0 };
    ui.afterInfo = null;
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
    paintStock(['head', 'price', 'act', 'verdict', 'panel', 'chart', 'body']);
    if (sheet && sheet.kind === 'chartfull') cfMeta();
    if (ok && typeof ui.afterInfo === 'function') { var fn = ui.afterInfo; ui.afterInfo = null; try { fn(); } catch (e) { /* ignore */ } }
  }
  function retryStock() {
    var st = ui.stock; if (!st) return;
    requestInfo();
    if (!st.chart && !st.inflight) requestChart(st.want || 130);
    paintStock(['head', 'price', 'act', 'verdict', 'panel', 'chart', 'body']);
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

  /* ---------- targets ---------- */
  function round2(x) { return Math.round(x * 100) / 100; }
  function targetLtp(sym) {
    var st = ui.stock, l = st && st.sym === sym && st.info ? SE.N(st.info.ltp) : null;
    if (l > 0) return l;
    var t = SE.targetOf(S, sym); l = t ? SE.N(t.ltp) : null;
    return l > 0 ? l : 0;
  }
  function openTarget(sym, pre) {
    sym = String(sym || '').trim().toUpperCase();
    if (!sym) return;
    var data = { id: sym, ltp: targetLtp(sym) };
    if (pre) { if (pre.target > 0) data.target = pre.target; if (pre.stop > 0) data.stop = pre.stop; if (pre.target > 0 || pre.stop > 0) data.note = ''; }
    openSheet('target', data, true);
    recalc();
  }
  function onTargetResult(name, res, ok) {
    if (!ok) return;
    var lt = ui.lastTarget, st = ui.stock;
    if (name === 'setTarget' && lt && st && st.info && lt.sym === st.sym) {
      st.info.myTarget = { target: lt.target, stop: lt.stop, note: lt.note, status: 'ACTIVE' };
    }
    if (name === 'clearTarget') {
      var sym = lt ? lt.sym : (sheet && sheet.data && sheet.data.id) || '';
      if (ui.clearing) { sym = ui.clearing; ui.clearing = null; }
      if (st && st.info && st.sym === sym) st.info.myTarget = { target: 0, stop: 0, note: '', status: '' };
      if (sym && Array.isArray(S.targets)) S.targets = S.targets.filter(function (x) { return !(x && String(x.sym || '').toUpperCase() === sym); });
      if (sym) delete ui.confirmTarget[sym];
    }
    if (sheet && sheet.kind === 'chartfull') cfMeta();
  }
  function clearTargetNow(sym) {
    sym = String(sym || '').trim().toUpperCase();
    if (!sym) return;
    ui.clearing = sym; ui.lastTarget = { sym: sym };
    if (sheet && sheet.kind === 'target') sheetBusy('clearTarget');
    if (!act('clearTarget', { sym: sym }, 'clearTarget:' + sym)) { ui.clearing = null; if (sheet && sheet.kind === 'target') sheetIdle(''); }
  }

  /* ---------- full-screen chart ---------- */
  var cfCache = {}, cfInst = null, cfLast = 0, cfHeadHtml = '';
  function isChartRes(r) { return !!r && typeof r === 'object' && Array.isArray(r.c) && r.c.length >= 5; }
  function cacheChart(sym, res, full) {
    if (!isChartRes(res)) return;
    cfCache[ckey(sym)] = { res: res, full: !!full };
    var keys = Object.keys(cfCache); while (keys.length > 4) delete cfCache[keys.shift()];
  }
  function cfEmas() { var o = {}; [10, 20, 50, 100, 200].forEach(function (k) { o[k] = !!ui.emaOn[k]; }); return o; }
  function cfLevels() {
    var st = ui.stock; if (!st || !ui.cf) return [];
    try { return CM.buildLevels(st.sym === ui.cf.sym ? st.info : null, SE.myTarget(S, ui)); } catch (e) { return []; }
  }
  function paintCfTog() { var el = $('cf-tog'); if (el) el.innerHTML = SE.cfToggles(ui); }
  function paintCfRange() {
    var el = $('cf-rng'); if (!el) return;
    var h = SE.cfRangeChips(ui.cfOpts.rangeOn, !!(ui.stock && ui.stock.info && ui.stock.info.listing));
    if (el._h !== h) { el.innerHTML = h; el._h = h; }
  }
  function cfMsg() {
    var el = $('cf-msg'); if (!el || !ui.cf) return;
    var h = SE.cfMsg(ui);
    el.innerHTML = h; el.hidden = !h;
  }
  function cfMeta() {            // header (price) and level lines follow the stock info / targets without touching the canvas
    if (!ui.cf || !sheet || sheet.kind !== 'chartfull') return;
    var el = $('cf-head'), h = SE.cfHead(S, ui, ui.cf.sym);
    if (el && h !== cfHeadHtml) { el.innerHTML = h; cfHeadHtml = h; }
    if (cfInst) cfInst.setLevels(cfLevels());
  }
  function onCfView(state) {
    var on = state.ranges || [], cur = ui.cfOpts.rangeOn, key = on.indexOf(cur) >= 0 ? cur : (on[0] || '');
    if (key !== cur) { ui.cfOpts.rangeOn = key; paintCfRange(); }
  }
  function applyCfBoot() {
    var b = ui.cfBoot; ui.cfBoot = null;
    if (!b || !cfInst) return;
    var n = cfInst.state().n;
    if (b.range) { ui.cfOpts.rangeOn = b.range; cfInst.setRange(b.range); }
    else if (b.zoom > 0 || b.pan > 0) {
      var c = b.zoom > 0 ? b.zoom : cfInst.state().c;
      cfInst.setView({ s: n - c - (b.pan > 0 ? b.pan : 0), c: c });
    }
    if (b.cross !== null && b.cross !== undefined && b.cross >= 0) { cfInst.flush(); var stt = cfInst.state(); cfInst.setCrossIdx(Math.floor(stt.s + stt.c) - 1 - b.cross); }
    if (b.levels === false) { ui.cfOpts.levels = false; cfInst.setOpts({ levels: false }); paintCfTog(); }
    paintCfRange();
  }
  function mountChartFull() {
    unmountChartFull();
    var stage = $('cf-stage'); if (!stage || !window.SEChart || !ui.cf) return;
    cfHeadHtml = SE.cfHead(S, ui, ui.cf.sym);
    cfInst = SEChart.create(stage, { onView: onCfView, onReset: function () { ui.cfOpts.rangeOn = '6M'; paintCfRange(); } });
    if (!cfInst) return;
    cfInst.setOpts({ emas: cfEmas(), levels: ui.cfOpts.levels !== false, vol: ui.cfOpts.vol !== false });
    cfInst.setLevels(cfLevels());
    if (isChartRes(ui.cf.res)) cfInst.setData(ui.cf.res);
    var el = $('cf-rng'); if (el) el._h = el.innerHTML;
    if (ui.cf.have500 || ui.cf.err) applyCfBoot();
    onCfView(cfInst.state());
    cfMsg();
  }
  window.__seChart = function () { return cfInst; }; /* read-only handle for the in-browser integration test */
  function unmountChartFull() { if (cfInst) { try { cfInst.destroy(); } catch (e) { /* ignore */ } cfInst = null; } }
  function loadFull() {
    var cf = ui.cf; if (!cf) return;
    cf.loading = true; cf.err = ''; cfMsg();
    chartEnqueue({ sym: cf.sym, bars: 500, done: function (ok, res, err) {
      cf.loading = false;
      if (ok) { cacheChart(cf.sym, res, true); cf.have500 = true; cf.res = res; }
      else cf.err = err === '__timeout' ? 'The longer history did not load in time.' : err;
      if (ui.cf !== cf) return;                                 // closed meanwhile: only the cache keeps it
      if (ok && cfInst) cfInst.setData(res, { keepView: true });
      else if (!ok && cf.res) { toast('Could not load the longer history: ' + cf.err, 'err'); cf.err = ''; }
      if (cfInst) applyCfBoot();
      cfMsg();
    } });
  }
  function openChartFull() {
    var st = ui.stock;
    if (!st || (sheet && sheet.kind === 'chartfull')) return;
    var t = Date.now(); if (t - cfLast < 400) return; cfLast = t;
    var c = cfCache[ckey(st.sym)], res = null, full = false;
    if (c && isChartRes(c.res)) { res = c.res; full = !!c.full; }
    if (isChartRes(st.chart) && (!res || st.chart.c.length > res.c.length)) { res = st.chart; full = st.range === 500 || st.chart.c.length >= 500; }
    ui.cf = { sym: st.sym, res: res, err: '', loading: false, have500: full };
    openSheet('chartfull', { id: st.sym }, true);
    if (!full) loadFull();
  }

  /* ---------- inline chart: press-and-hold crosshair, tap (or the corner button) to expand ----------
     .cwrap has touch-action:pan-y, so the browser keeps vertical panning and hands us horizontal drags. We only take a touch over
     after the 250 ms hold or a clearly horizontal drag; a vertical drag is left alone for the page to scroll. */
  var xh = null, xhSwallow = 0;
  function cwOf(t) { return t && t.closest ? t.closest('.cwrap.live') : null; }
  function xhClear() {
    var g = elSheet.querySelector('.cwrap svg.chart .xh'); if (g) g.setAttribute('class', 'xh');
    var cro = $('st-cro'); if (cro) cro.innerHTML = SE.croHtml(null);
  }
  function xhShow(cx) {
    var cw = xh && xh.cw, svg = cw && cw.querySelector('svg.chart'), res = ui.stock && ui.stock.chart;
    if (!svg || !isChartRes(res)) return;
    var n = Number(svg.getAttribute('data-n')), L = Number(svg.getAttribute('data-l')), pw = Number(svg.getAttribute('data-pw')), vw = Number(svg.getAttribute('data-vw'));
    var tp = Number(svg.getAttribute('data-tp')), ph = Number(svg.getAttribute('data-ph')), mn = Number(svg.getAttribute('data-mn')), mx = Number(svg.getAttribute('data-mx'));
    var r = svg.getBoundingClientRect(), scale = r.width / vw;
    if (!(n > 0) || !(scale > 0) || !(pw > 0)) return;
    var i = Math.max(0, Math.min(n - 1, Math.floor(((cx - r.left) / scale - L) / pw * n)));
    var info = CM.barInfo(res, i, []), x = L + (i + 0.5) * pw / n;
    var g = svg.querySelector('#xh');
    if (g) {
      var ln = g.querySelector('line'), dot = g.querySelector('circle');
      ln.setAttribute('x1', x.toFixed(1)); ln.setAttribute('x2', x.toFixed(1));
      if (info.c > 0 && mx > mn) { dot.setAttribute('cx', x.toFixed(1)); dot.setAttribute('cy', (tp + (mx - info.c) / (mx - mn) * ph).toFixed(1)); dot.style.display = ''; } else dot.style.display = 'none';
      g.setAttribute('class', 'xh on');
    }
    var cro = $('st-cro'); if (cro) cro.innerHTML = SE.croHtml(info);
  }
  function xhStop() { if (xh) { clearTimeout(xh.timer); xh = null; } }
  elSheet.addEventListener('touchstart', function (e) {
    var cw = cwOf(e.target);
    if (!cw || e.touches.length !== 1 || (e.target.closest && e.target.closest('[data-c]'))) { xhStop(); return; }
    var t = e.touches[0];
    xhStop();
    xh = { cw: cw, x0: t.clientX, y0: t.clientY, t0: Date.now(), mode: 'pending', timer: 0, mouse: false };
    xh.timer = setTimeout(function () { if (xh && xh.mode === 'pending') { xh.mode = 'cross'; xhShow(xh.x0); } }, 250);
  }, { passive: true });
  elSheet.addEventListener('touchmove', function (e) {
    if (!xh || xh.mouse) return;
    var t = e.touches[0]; if (!t) return;
    var dx = t.clientX - xh.x0, dy = t.clientY - xh.y0;
    if (xh.mode === 'pending') {
      if (Math.abs(dy) > 8 && Math.abs(dy) * 1.6 >= Math.abs(dx)) { clearTimeout(xh.timer); xh.mode = 'scroll'; return; }       // vertical: the page scrolls
      if (Math.abs(dx) > 10 && Math.abs(dx) > Math.abs(dy) * 1.8) { clearTimeout(xh.timer); xh.mode = 'cross'; }               // clearly horizontal
      else return;
    }
    if (xh.mode === 'cross') { if (e.cancelable) e.preventDefault(); xhShow(t.clientX); }
  }, { passive: false });
  function xhEnd(e) {
    if (!xh || xh.mouse) return;
    var x = xh; xhStop();
    if (x.mode === 'cross') { xhSwallow = Date.now(); if (e && e.cancelable) e.preventDefault(); return; }       // the readout stays until the next tap
    if (x.mode === 'pending') {                                                                                    // a tap
      xhSwallow = Date.now(); if (e && e.cancelable) e.preventDefault();
      if (x.cw.querySelector('.xh.on')) xhClear(); else openChartFull();
    }
  }
  elSheet.addEventListener('touchend', xhEnd, { passive: false });
  elSheet.addEventListener('touchcancel', function () { if (xh && !xh.mouse) xhStop(); }, { passive: true });
  elSheet.addEventListener('mousedown', function (e) {                                                                // desktop: hold or drag to scrub
    var cw = cwOf(e.target);
    if (e.button !== 0 || !cw || (e.target.closest && e.target.closest('[data-c]'))) return;
    xhStop();
    xh = { cw: cw, x0: e.clientX, y0: e.clientY, t0: Date.now(), mode: 'pending', timer: 0, mouse: true };
    xh.timer = setTimeout(function () { if (xh && xh.mode === 'pending') { xh.mode = 'cross'; xhShow(xh.x0); } }, 250);
  });
  window.addEventListener('mousemove', function (e) {
    if (!xh || !xh.mouse) return;
    if (xh.mode === 'pending' && Math.abs(e.clientX - xh.x0) > 4) { clearTimeout(xh.timer); xh.mode = 'cross'; }
    if (xh.mode === 'cross') xhShow(e.clientX);
  });
  window.addEventListener('mouseup', function () {
    if (!xh || !xh.mouse) return;
    var x = xh; xhStop();
    if (x.mode === 'cross') xhSwallow = Date.now();
  });

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
      case 'track': if (!(sheet && sheet.kind === 'track')) openSheet('track', {}, true); break;
      case 'replay': act('replayTrack', {}, 'replayTrack'); repaintTrack(); break;
      case 'resettrack': openSheet('resettrack'); break;
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
      case 'sfilter': if (ui.search && (id === 'all' || id === 'new' || id === 'watch' || id === 'held')) { ui.search.filter = id; if (id === 'new' && !Array.isArray(ui.search.newList)) loadNewListings(); paintSearchBody(); } break;
      case 'refresh-symbols': act('refreshSymbols', {}, 'refreshSymbols'); paintSearchBody(); break;
      case 'pnl': { var pk = Number(id); if (pk >= 0 && ui.stock && ui.stock.info) { var ms0 = SE.panelOpen(ui, pk); ui.pnl[pk] = !ms0; paintStock(['panel']); } break; }
      case 'vopen': if (id === 'why' || id === 'hist') { ui.vOpen[id] = !ui.vOpen[id]; paintStock(['verdict']); } break;
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
      case 'chartfull': openChartFull(); break;
      case 'cf-range': if (cfInst) { ui.cfOpts.rangeOn = id; cfInst.setRange(id); paintCfRange(); } break;
      case 'cf-ema': if (SE.N(id) !== null) { ui.emaOn[id] = !ui.emaOn[id]; var eo = {}; eo[id] = !!ui.emaOn[id]; if (cfInst) cfInst.setOpts({ emas: eo }); paintCfTog(); } break;
      case 'cf-levels': ui.cfOpts.levels = ui.cfOpts.levels === false; if (cfInst) cfInst.setOpts({ levels: ui.cfOpts.levels }); paintCfTog(); break;
      case 'cf-vol': ui.cfOpts.vol = ui.cfOpts.vol === false; if (cfInst) cfInst.setOpts({ vol: ui.cfOpts.vol }); paintCfTog(); break;
      case 'cf-reset': if (cfInst) { cfInst.reset(); ui.cfOpts.rangeOn = '6M'; paintCfRange(); } break;
      case 'cf-retry': loadFull(); break;
      case 'target': openTarget(id); break;
      case 'target-from-call': {
        var ci = ui.stock && ui.stock.info && ui.stock.info.call;
        if (ci) openTarget(id, { target: SE.N(ci.t1) > 0 ? SE.N(ci.t1) : 0, stop: SE.N(ci.stop) > 0 ? SE.N(ci.stop) : 0 });
        break;
      }
      case 'tgt-pct': {
        var kind = String(id).charAt(0), pctv = Number(String(id).slice(1)), base = sheet && sheet.kind === 'target' ? SE.N(sheet.data.ltp) : null;
        if (base > 0 && pctv > 0) {
          var inp = elSheet.querySelector('[name="' + (kind === 't' ? 'target' : 'stop') + '"]');
          if (inp) { inp.value = String(round2(base * (kind === 't' ? 1 + pctv / 100 : 1 - pctv / 100))); fieldErr(inp.name, ''); recalc(); }
        }
        break;
      }
      case 'target-clear': clearTargetNow(id); break;
      case 'targets-all': ui.targetsAll = !ui.targetsAll; render(true); break;
      case 'target-clear-ask': ui.confirmTarget[id] = 1; render(true); break;
      case 'target-clear-no': delete ui.confirmTarget[id]; render(true); break;
      case 'target-clear-yes': clearTargetNow(id); break;
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
    var cw = cwOf(e.target);
    if (cw && !(e.target.closest && e.target.closest('[data-c]'))) { if (Date.now() - xhSwallow > 500) openChartFull(); return; }
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
        if (qs.filter === 'new' && ui.search) { ui.search.filter = 'new'; loadNewListings(); paintSearchBody(); }
        if (qs.q) { var qi2 = elSheet.querySelector('input[name="q"]'); if (qi2) { qi2.value = qs.q; onSearchInput(qs.q); } }
      }
      else if (qs.sheet === 'stock') {
        first = SE.A(S.holdings)[0];
        if (qs.sheetscroll || qs.sheetat) bootScroll = { y: Number(qs.sheetscroll) || 0, at: qs.sheetat || '' };
        openStock(qs.sym || (first ? SE.T(first.sym) : 'RELIANCE'), '');
      }
      else if (qs.sheet === 'chartfull' || qs.sheet === 'target') {
        first = SE.A(S.holdings)[0];
        var bsym = (qs.sym || (first ? SE.T(first.sym) : 'RELIANCE')).toUpperCase();
        openStock(bsym, '');
        if (qs.sheet === 'chartfull') {
          var bn = function (k) { return qs[k] !== undefined && qs[k] !== '' && isFinite(Number(qs[k])) ? Number(qs[k]) : null; };
          ui.cfBoot = { zoom: bn('zoom'), pan: bn('pan'), cross: bn('cross'), range: /^(3M|6M|1Y|2Y|All)$/.test(qs.range || '') ? qs.range : '', levels: qs.levels === '0' ? false : true };
          openChartFull();
        } else ui.afterInfo = function () { openTarget(bsym, qs.t1 ? { target: Number(qs.t1) || 0, stop: Number(qs.stop) || 0 } : null); };
      }
      else if ({ settings: 1, add: 1, paste: 1, clearfeed: 1, track: 1, resettrack: 1 }[qs.sheet]) openSheet(qs.sheet, qs.sheet === 'add' && qs.sym ? { sym: qs.sym } : {}, true);
      else if (qs.sheet === 'chart') { first = SE.A(S.holdings)[0]; openChart(qs.sym || (first ? SE.T(first.sym) : 'RELIANCE')); }
      if (qs.sheetscroll && qs.sheet !== 'stock') { var sb = elSheet.querySelector('.sb'); if (sb) sb.scrollTop = Number(qs.sheetscroll) || 0; }
    }
  } catch (e) { /* ignore */ }
})();
