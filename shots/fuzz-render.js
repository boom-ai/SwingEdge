/* Fuzz test for ui/render.js (pure string rendering). Run: node shots/fuzz-render.js [iterations]
   Takes the mock states, randomly mutilates them (delete fields, nulls, wrong types, huge/NaN values, HTML strings)
   and asserts that every view/sheet renders without throwing, and never leaks "undefined"/"NaN"/"[object" or raw HTML. */
const path = require('path'), fs = require('fs'), vm = require('vm');
const UI = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'ui');
const SE = require(path.join(UI, 'render.js'));
function mockState(scenario) {
  const win = { location: { search: scenario ? '?scenario=' + scenario : '' } };
  const ctx = { window: win, setTimeout() {}, setInterval() {}, clearInterval() {}, Date, JSON, Math, String, Number, Array, Object, parseFloat, decodeURIComponent };
  vm.runInNewContext(fs.readFileSync(path.join(UI, 'mock.js'), 'utf8'), ctx);
  return { state: JSON.parse(win.Android.getState()), device: JSON.parse(win.Android.deviceStatus()) };
}
// real mock replies for the new actions (stockInfo / getChart / search) used as fuzz bases
function mockReply(name, args, scenario) {
  const win = { location: { search: scenario ? '?scenario=' + scenario : '' } };
  let out = null;
  const ctx = { window: win, setTimeout(f) { f(); }, setInterval() {}, clearInterval() {}, Date, JSON, Math, String, Number, Array, Object, parseFloat, decodeURIComponent };
  vm.runInNewContext(fs.readFileSync(path.join(UI, 'mock.js'), 'utf8'), ctx);
  win.onActionResult = (n, j) => { if (n === name) out = JSON.parse(j); }; win.onStateChanged = () => {};
  win.Android.action(name, JSON.stringify(args));
  return out;
}
const STOCKS = ['TATAMOTORS', 'POLYCAB', 'ILLIQTEXTILE', 'NEWSYM', 'M&M', 'YESBANK'].map(sym => ({
  sym, info: mockReply('stockInfo', { sym }), chart: mockReply('getChart', { sym, bars: 260 }), chart2: mockReply('getChart', { sym, bars: 500 })
}));
const FAILED = mockReply('stockInfo', { sym: 'ZZTEST' });
const SEARCHES = [mockReply('search', { q: 'tata' }), mockReply('search', { q: 'zzqx' }), mockReply('search', { q: 'tata mot' })];
let seed = Number(process.env.SEED || 12345);
function rnd() { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; }
function pick(a) { return a[Math.floor(rnd() * a.length)]; }
const EVIL = '<img src=x onerror=alert(1)>"\'`&</script><script>alert(2)</script>';
const JUNK = () => pick([null, undefined, NaN, Infinity, -Infinity, 0, -1, 1e21, -1e21, 1e-9, 4.2e15, '', ' ', 'NaN', 'abc', '12.5', EVIL, 'x'.repeat(600),
  true, false, [], {}, [null], [EVIL, 3, {}], { a: 1 }, 'BULL', 'SELL', 'EXIT', 'OPEN', 'NEW', 'LIVE', Date.now(), Date.now() + 9e5, -Date.now(), () => 1]);
function mutate(v, depth) {
  if (rnd() < 0.10) return JUNK();
  if (Array.isArray(v)) {
    let a = v.map(x => mutate(x, depth + 1));
    if (rnd() < 0.15) a.splice(Math.floor(rnd() * (a.length + 1)), 0, JUNK());
    if (rnd() < 0.10) a = a.concat(a, a);
    return a;
  }
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) { if (rnd() < 0.08) continue; o[k] = mutate(v[k], depth + 1); }
    return o;
  }
  return v;
}
const N = Number(process.argv[2] || 4000);
const bases = ['', 'empty', 'paused', 'closed', 'broken'].map(mockState);
const TABS = ['today', 'setups', 'trades', 'portfolio', 'alerts', undefined, 'zzz'];
const SHEETS = ['take', 'close', 'add', 'paste', 'remove', 'sold', 'clearfeed', 'settings', 'nope'];
let fails = 0, renders = 0;
function check(what, html, info) {
  renders++;
  let why = null;
  if (typeof html !== 'string') why = 'not a string';
  else if (/undefined|NaN|\[object|Infinity|function \(|=> 1/.test(html.split('x'.repeat(600)).join(''))) why = 'leaked ' + html.match(/undefined|NaN|\[object|Infinity|function \(|=> 1/)[0];
  else if (/<script|<img|onerror=alert\(1\)>/i.test(html)) why = 'unescaped HTML';
  if (why && fails++ < 5) { console.log('FAIL', what, why, JSON.stringify(info).slice(0, 300)); const i = html.search(/undefined|NaN|\[object|Infinity|<script|<img/); console.log('   …' + String(html).slice(Math.max(0, i - 160), i + 60)); }
}
function run(S, ui, tag) {
  try {
    check('header', SE.header(S, ui), tag); check('tabbar', SE.tabbar(S, ui), tag);
    for (const t of TABS) { if (ui && typeof ui === 'object') ui.tab = t; check('view:' + t, SE.view(S, ui), tag); }
    const ids = [].concat(S && S.signals, S && S.trades, S && S.holdings).map(x => x && (x.id || x.sym)).concat(['', null, EVIL]);
    for (const k of SHEETS) check('sheet:' + k, SE.sheet(k, S, ui, { id: pick(ids) }), tag);
    const dev = ui && ui.device; check('devlist', SE.deviceList(dev), tag);
    check('takeCalc', SE.takeCalc(pick((S && Array.isArray(S.signals) ? S.signals : []).concat([null])), JUNK(), JUNK()), tag);
    check('closeCalc', SE.closeCalc(pick((S && Array.isArray(S.trades) ? S.trades : []).concat([null])), JUNK()), tag);
    check('settingsCalc', SE.settingsCalc(JUNK(), JUNK()), tag);
    SE.actItems(S); SE.isBusy(S); SE.deviceIssues(dev);
    check('sheet:add-prefill', SE.sheet('add', S, ui, { sym: JUNK() }), tag);
    // stock page, search sheet and watchlist with (mutilated) stockInfo / getChart / search payloads
    const b = pick(STOCKS);
    const mk = () => {
      const mode = rnd();
      const st = mode < 0.12 ? { sym: pick(['ZZTEST', JUNK()]), info: null, err: pick([FAILED.error, JUNK(), 'No reply after 20 seconds.']), chart: null, chartErr: pick(['', 'x', JUNK()]), want: 130, inflight: 0 }
        : mode < 0.22 ? { sym: b.sym, name: pick([JUNK(), 'x'.repeat(300)]), info: null, err: '', chart: rnd() < 0.5 ? mutate(b.chart, 0) : null, want: pick([130, 260, 500, JUNK()]), inflight: pick([0, 130, JUNK()]) }
        : { sym: pick([b.sym, JUNK()]), name: pick(['x'.repeat(300), JUNK(), '']), info: mutate(b.info, 0), chart: rnd() < 0.85 ? mutate(rnd() < 0.5 ? b.chart : b.chart2, 0) : pick([null, JUNK()]), chartErr: pick(['', JUNK()]),
          err: '', want: pick([130, 260, 500, JUNK()]), range: pick([130, 260, JUNK()]), inflight: pick([0, 0, 130, JUNK()]) };
      if (st.info && typeof st.info === 'object' && rnd() < 0.2) st.info.name = 'N'.repeat(300);
      return st;
    };
    const u2 = Object.assign({}, ui && typeof ui === 'object' ? ui : {}, {
      stock: rnd() < 0.05 ? JUNK() : mk(),
      search: rnd() < 0.1 ? JUNK() : { q: pick(['', 'a', 'tata', EVIL, 'x'.repeat(300), JUNK()]), sent: 'tata', shown: pick(['tata', JUNK()]), loading: pick([true, false, JUNK()]), err: pick(['', 'Search is not available', JUNK()]),
        results: pick([null, [], mutate(pick(SEARCHES).results, 0), JUNK(), [{ sym: 'M&M', name: 'N'.repeat(300), tracked: true }, null, { name: 'no sym' }]]) },
      recent: pick([[], [{ sym: 'ITC', name: 'ITC Ltd' }, null, { sym: EVIL, name: JUNK() }], JUNK(), mutate([{ sym: 'TCS', name: 'Tata' }], 0)]),
      emaOn: pick([{ 10: true, 20: false, 50: true, 100: true, 200: true }, JUNK(), {}, { 10: JUNK() }]), watchEdit: pick([true, false, JUNK()]) });
    check('sheet:stock', SE.sheet('stock', S, u2, { id: pick([b.sym, JUNK(), EVIL]) }), tag);
    ['head', 'price', 'act', 'chart', 'body', 'nope'].forEach(part => check('stockPart:' + part, SE.stockPart(part, S, u2, { id: b.sym }), tag));
    check('sheet:search', SE.sheet('search', S, u2, {}), tag);
    check('searchBody', SE.searchBody(S, u2), tag);
    u2.tab = 'setups'; check('view:setups+watch', SE.view(S, u2), tag);
  } catch (e) { if (fails++ < 5) console.log('THROW', tag, e.stack.split('\n').slice(0, 4).join('\n')); }
}
// unmutated states first, then junk roots, then random mutations
bases.forEach((b, i) => run(b.state, { tab: 'today', expanded: {}, pending: {}, device: b.device, now: Date.now() }, 'base' + i));
// unmutated new screens must render completely, with the real numbers in them
{
  const b = bases[0], t = STOCKS[0], u = { tab: 'setups', pending: {}, device: b.device, now: Date.now(), emaOn: { 10: true, 20: true, 50: true, 100: false, 200: true }, stock: { sym: t.sym, info: t.info, chart: t.chart, want: 260, range: 260 }, recent: [{ sym: 'ITC', name: 'ITC Ltd' }], search: { q: 'tata', sent: 'tata', shown: 'tata', results: SEARCHES[0].results } };
  const must = (what, html, re) => { renders++; if (!re.test(html)) { fails++; console.log('FAIL', what, 'missing', re); } };
  const html = SE.sheet('stock', b.state, u, { id: t.sym });
  must('stock page', html, /Tata Motors Ltd/); must('stock page', html, /10 \/ 20 EMA/); must('stock page', html, /Technical checklist/); must('stock page', html, /Setups it matches/); must('stock page', html, /aria-label="Price chart with volume"/);
  must('stock page', html, /Add to watchlist/); must('stock page', html, /In your portfolio/);
  must('search', SE.sheet('search', b.state, u, {}), /TATAMOTORS/); must('watchlist', SE.view(b.state, u), /My watchlist/);
  must('search-empty', SE.searchBody(b.state, Object.assign({}, u, { search: { q: 'zzqx', sent: 'zzqx', shown: 'zzqx', results: [] } })), /No NSE stock matches/);
  must('search-lookup', SE.searchBody(b.state, Object.assign({}, u, { search: { q: 'zzqx', sent: 'zzqx', shown: 'zzqx', results: [] } })), /Look up ZZQX anyway/);
}
[null, undefined, 0, '', 'x', [], [1], true, {}].forEach(j => { run(j, j, 'junkroot'); run(j, {}, 'junkroot2'); });
for (let i = 0; i < N; i++) {
  const b = pick(bases), S = mutate(b.state, 0);
  const exp = {}, pend = {};
  [].concat(S && S.signals, S && S.holdings, S && S.trades).forEach(x => { if (x && rnd() < 0.5) { exp['sig:' + x.id] = 1; exp['hold:' + x.sym] = 1; exp['act-hold:' + x.sym] = 1; pend['takeTrade:' + x.id] = 1; pend['removeHolding:' + x.sym] = 1; pend['closeTrade:' + x.id] = 1; } });
  if (rnd() < 0.3) { pend.scanNow = 1; pend.runBacktest = 1; pend.importHoldings = 1; pend.clearFeed = 1; }
  const ui = { tab: 'today', expanded: rnd() < 0.1 ? JUNK() : exp, pending: rnd() < 0.1 ? JUNK() : pend, device: rnd() < 0.5 ? mutate(b.device, 0) : JUNK(),
    bannerDismissed: rnd() < 0.3, importResult: rnd() < 0.5 ? mutate({ ok: true, added: 3, skipped: ['Total,,', 'bad line', EVIL] }, 0) : JUNK(), now: rnd() < 0.9 ? Date.now() : JUNK() };
  run(S, ui, 'iter' + i + ' seed' + seed);
}
console.log((fails ? 'FAILED ' + fails : 'OK') + ' — ' + renders + ' renders over ' + (N + bases.length + 18) + ' states, 0 tolerated throws');
process.exit(fails ? 1 : 0);
