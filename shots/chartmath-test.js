/* Unit tests for ui/chartmath.js (pure chart maths). Run: node shots/chartmath-test.js */
const path = require('path');
const CM = require(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'ui', 'chartmath.js'));
let pass = 0, fail = 0;
function ok(c, m) { if (c) pass++; else { fail++; console.log('FAIL ' + m); } }
function near(a, b, e, m) { ok(Math.abs(a - b) <= (e == null ? 1e-9 : e), m + ' (got ' + a + ', want ' + b + ')'); }
const JUNK = [null, undefined, NaN, Infinity, -Infinity, 0, -5, 1e21, '', 'x', '12', [], {}, true];

/* ---- clampView ---- */
let v = CM.clampView(0, 0, 0); ok(v.s === 0 && v.c === 0, '0 bars -> empty view');
v = CM.clampView(5, 10, 1); ok(v.s === 0 && v.c === 1, '1 bar: view is exactly that bar');
v = CM.clampView(0, 3, 20); ok(v.c === 20 && v.s === 0, '20 bars: cannot zoom in below the minimum (20 = all of it)');
v = CM.clampView(0, 3, 500); ok(v.c === 20, '500 bars: min 20 visible');
v = CM.clampView(-50, 100, 500); ok(v.s === 0 && v.c === 100, 'pan before the first bar clamps to 0');
v = CM.clampView(900, 100, 500); ok(v.s === 400 && v.c === 100, 'pan after the last bar clamps to n - c');
v = CM.clampView(0, 9999, 500); ok(v.c === 500 && v.s === 0, 'zoom out beyond the data = all bars');
v = CM.clampView(NaN, NaN, 130); ok(v.c === 130 && v.s === 0, 'NaN view = whole data');
JUNK.forEach((j, k) => { const r = CM.clampView(j, j, j); ok(typeof r.s === 'number' && typeof r.c === 'number' && isFinite(r.s) && isFinite(r.c), 'clampView junk #' + k); });
JUNK.forEach((j, k) => { const r = CM.clampView(j, j, 100); ok(r.s >= 0 && r.s + r.c <= 100 + 1e-9 && r.c >= 20, 'clampView junk window #' + k); });

/* ---- default and ranges ---- */
v = CM.defaultView(500); ok(v.c === 126 && v.s === 374, 'default window = last 126 of 500');
v = CM.defaultView(50); ok(v.c === 50 && v.s === 0, 'default window with 50 bars = all');
v = CM.defaultView(1); ok(v.c === 1, 'default window with 1 bar');
ok(CM.rangeCount('3M', 500) === 63 && CM.rangeCount('1Y', 500) === 252 && CM.rangeCount('All', 500) === 500 && CM.rangeCount('2Y', 500) === 500, 'range bar counts');
ok(CM.rangeCount('3M', 30) === 30 && CM.rangeCount('1Y', 130) === 130, 'range counts clamp to the data');
ok(CM.rangeCount('3M', 10) === 10, 'range with 10 bars = 10');
ok(CM.rangeCount('nope', 200) === 200, 'unknown range = all');
v = CM.rangeView('6M', 500); ok(v.s === 374 && v.c === 126, '6M view is right-aligned');
ok(CM.activeRanges({ s: 374, c: 126 }, 500).join() === '6M', 'active range 6M');
ok(CM.activeRanges({ s: 0, c: 500 }, 500).join() === '2Y,All', 'full 500 bars matches both 2Y and All');
ok(CM.activeRanges({ s: 300, c: 126 }, 500).length === 0, 'a view panned away from the right edge matches no chip');
ok(CM.activeRanges({ s: 440, c: 60 }, 500).length === 0, 'a custom zoom matches no chip');
ok(CM.activeRanges(null, 500).length === 0 && CM.activeRanges({ s: 0, c: 5 }, 0).length === 0, 'active range with junk');

/* ---- zoom ---- */
let z = CM.zoomAt({ s: 374, c: 126 }, 2, 0.5, 500);
near(z.c, 63, 1e-9, 'zoom x2 halves the bars'); near(z.s + 0.5 * z.c, 374 + 63, 1e-9, 'zoom keeps the bar under the anchor fixed');
z = CM.zoomAt({ s: 374, c: 126 }, 2, 1, 500); near(z.s + z.c, 500, 1e-9, 'zoom anchored at the right edge keeps the right edge');
z = CM.zoomAt({ s: 374, c: 126 }, 2, 0, 500); near(z.s, 374, 1e-9, 'zoom anchored at the left edge keeps the left edge');
z = CM.zoomAt({ s: 100, c: 22 }, 10, 0.3, 500); near(z.c, 20, 1e-9, 'zoom in stops at 20 bars');
near(z.s + 0.3 * z.c, 100 + 0.3 * 22, 1e-9, 'anchor stays fixed even when the minimum is hit');
z = CM.zoomAt({ s: 0, c: 400 }, 0.1, 0.5, 500); ok(z.c === 500 && z.s === 0, 'zoom out stops at all bars');
z = CM.zoomAt({ s: 0, c: 20 }, 3, 0.5, 20); ok(z.c === 20 && z.s === 0, 'zoom with exactly 20 bars is a no-op');
z = CM.zoomAt({ s: 0, c: 1 }, 5, 0.5, 1); ok(z.c === 1 && z.s === 0, 'zoom with 1 bar is a no-op');
z = CM.zoomAt({ s: 10, c: 100 }, 0, 0.5, 500); ok(z.c === 100, 'zero factor ignored');
z = CM.zoomAt({ s: 10, c: 100 }, NaN, 0.5, 500); ok(z.c === 100, 'NaN factor ignored');
z = CM.zoomAt({ s: 10, c: 100 }, 2, 7, 500); ok(z.c === 50 && z.s >= 0, 'anchor fraction clamped to 0..1');
z = CM.zoomAt({ s: 10, c: 100 }, 2, NaN, 500); ok(z.c === 50, 'NaN anchor = centre');
// repeated zoom-in/zoom-out returns to a sane range
z = { s: 374, c: 126 }; for (let i = 0; i < 40; i++) z = CM.zoomAt(z, 1.3, 0.8, 500); ok(z.c === 20 && z.s + z.c <= 500 && z.s >= 0, '40 zoom-ins stay valid');
for (let i = 0; i < 60; i++) z = CM.zoomAt(z, 1 / 1.3, 0.2, 500); ok(z.c === 500 && z.s === 0, '60 zoom-outs reach all bars');

/* ---- pan ---- */
let p = CM.panBy({ s: 374, c: 126 }, 100, 300, 500); near(p.s, 374 - 100 / 300 * 126, 1e-9, 'drag right moves back in time');
p = CM.panBy({ s: 374, c: 126 }, -5000, 300, 500); ok(p.s === 374, 'pan left beyond the last bar stays at the end');
p = CM.panBy({ s: 10, c: 126 }, 5000, 300, 500); ok(p.s === 0, 'pan right beyond the first bar stays at 0');
p = CM.panBy({ s: 0, c: 500 }, 80, 300, 500); ok(p.s === 0 && p.c === 500, 'pan with everything visible does nothing');
p = CM.panBy({ s: 5, c: 20 }, 10, 0, 500); ok(p.s === 5, 'zero plot width ignored');
p = CM.panBy({ s: 5, c: 20 }, NaN, 300, 500); ok(p.s === 5, 'NaN drag ignored');
p = CM.panBy({ s: 0, c: 1 }, 50, 300, 1); ok(p.s === 0 && p.c === 1, 'pan with 1 bar');

/* ---- pinch ---- */
let pi = CM.pinchView({ s: 374, c: 126 }, 100, 200, 0.5, 0.5, 500); near(pi.c, 63, 1e-9, 'fingers 2x apart = half the bars');
near(pi.s + 0.5 * pi.c, 374 + 63, 1e-9, 'pinch keeps the midpoint bar under the fingers');
pi = CM.pinchView({ s: 374, c: 126 }, 200, 100, 0.5, 0.5, 500); near(pi.c, 252, 1e-9, 'fingers together = twice the bars');
pi = CM.pinchView({ s: 300, c: 100 }, 100, 100, 0.2, 0.7, 500); near(pi.c, 100, 1e-9, 'same distance = same zoom');
near(pi.s + 0.7 * pi.c, 300 + 0.2 * 100, 1e-9, 'a moved midpoint pans the chart with the fingers');
pi = CM.pinchView({ s: 0, c: 100 }, 100, 1e6, 0.5, 0.5, 500); ok(pi.c === 20, 'extreme pinch-in stops at 20 bars');
pi = CM.pinchView({ s: 0, c: 100 }, 100, 0.0001, 0.5, 0.5, 500); ok(pi.c === 500, 'extreme pinch-out stops at all bars');
pi = CM.pinchView({ s: 40, c: 100 }, 0, 100, 0.5, 0.5, 500); ok(pi.c === 100 && pi.s === 40, 'zero start distance ignored');
pi = CM.pinchView({ s: 40, c: 100 }, 100, NaN, 0.5, 0.5, 500); ok(pi.c === 100, 'NaN distance ignored');

/* ---- shift when longer data arrives ---- */
const oldD = [], newD = [];
for (let i = 0; i < 130; i++) oldD.push(1.7e12 + i * 86400000);
for (let i = 0; i < 500; i++) newD.push(1.7e12 + (i - 370) * 86400000);
ok(CM.shiftForNewData(oldD, newD) === 370, 'new longer history: shift matches by date');
ok(CM.shiftForNewData(oldD, newD.map(x => x + 12345)) === 370, 'dates that do not match fall back to length difference');
ok(CM.shiftForNewData(null, newD) === 500 && CM.shiftForNewData(oldD, []) === 0 && CM.shiftForNewData(null, null) === 0, 'shift with missing data');

/* ---- mapping ---- */
const vw = { s: 100, c: 50 };
near(CM.xOf(100, vw, 10, 300), 10 + 0.5 / 50 * 300, 1e-9, 'first visible bar centre');
near(CM.xOf(149, vw, 10, 300), 10 + 49.5 / 50 * 300, 1e-9, 'last visible bar centre');
for (let i = 100; i < 150; i += 7) ok(CM.idxAt(CM.xOf(i, vw, 10, 300), vw, 10, 300, 500) === i, 'idxAt inverts xOf for bar ' + i);
ok(CM.idxAt(-999, vw, 10, 300, 500) === 0, 'x far left clamps to bar 0');
ok(CM.idxAt(9999, vw, 10, 300, 500) === 499, 'x far right clamps to the last bar');
ok(CM.idxAt(50, vw, 10, 300, 0) === -1 && CM.idxAt(50, vw, 10, 0, 10) === -1 && CM.idxAt(50, null, 10, 300, 10) === -1, 'idxAt with nothing to hit');
ok(CM.idxAt(5, { s: 0, c: 1 }, 0, 100, 1) === 0, 'idxAt with 1 bar');
near(CM.yOf(150, 100, 200, 10, 200), 110, 1e-9, 'yOf middle'); near(CM.priceAt(CM.yOf(137, 100, 200, 10, 200), 100, 200, 10, 200), 137, 1e-9, 'priceAt inverts yOf');
near(CM.yOf(200, 100, 200, 0, 100), 0, 1e-9, 'top of the axis is the max'); near(CM.yOf(100, 100, 200, 0, 100), 100, 1e-9, 'bottom of the axis is the min');

/* ---- price range ---- */
const H = [], L = [], E = [];
for (let i = 0; i < 100; i++) { H.push(110 + i % 10); L.push(100 + i % 10 - 1); E.push(i < 30 ? 0 : 105); }
let r = CM.priceRange(H, L, 0, 99, [E], []); ok(r.ok && r.min < 99 && r.max > 119, 'range covers all bars with padding: ' + r.min.toFixed(2) + '..' + r.max.toFixed(2));
r = CM.priceRange(H, L, 40, 49, [], []); ok(r.min < 100 && r.max > 119 && r.max < 125, 'only the visible slice counts');
r = CM.priceRange([105], [95], 0, 0, [], []); ok(r.ok && r.min < 95 && r.max > 105, 'single bar range');
r = CM.priceRange([100], [100], 0, 0, [], []); ok(r.ok && r.max > r.min && r.min > 0, 'flat single bar still has a usable axis');
r = CM.priceRange([0, 0, 0], [0, 0, 0], 0, 2, [], []); ok(r.ok === false && r.max > r.min, 'all zeros = not ok, usable fallback axis');
r = CM.priceRange([], [], 0, 5, [], []); ok(r.ok === false, 'no data');
r = CM.priceRange([110, 0, 112, NaN, 111], [100, 0, 101, 99, 0], 0, 4, [], []); ok(r.ok && r.min < 100 && r.min > 90 && r.max > 112, 'gaps (0 / NaN) are ignored: ' + r.min.toFixed(2) + '..' + r.max.toFixed(2));
r = CM.priceRange([110, 0], [100, 0], 0, 1, [], []); ok(r.ok && r.min < 100, 'trailing gap ignored');
r = CM.priceRange(H, L, 0, 99, [], [90]); ok(r.min < 90, 'a level just below the bars widens the axis');
r = CM.priceRange(H, L, 0, 99, [], [500]); ok(r.max < 140, 'a wildly distant level does not squash the candles');
r = CM.priceRange(H, L, 0, 99, [], [0, -4, NaN, null, 'x']); ok(r.ok && r.min > 90, 'junk levels ignored');
r = CM.priceRange(H, L, 0, 99, [null, [], [200, 200]], []); ok(r.ok, 'junk series ignored');
r = CM.priceRange(H, L, -5, 9999, [], []); ok(r.ok, 'out-of-range indices are clamped');
r = CM.priceRange(null, null, 0, 3, null, null); ok(r.ok === false, 'null arrays');
r = CM.priceRange([0.02], [0.01], 0, 0, [], []); ok(r.min > 0, 'penny stock axis stays positive');
r = CM.priceRange([10], [0], 0, 0, [], []); ok(r.ok && r.max > r.min, 'only a high is present');
ok(CM.levelSide(5, 10, 20) === -1 && CM.levelSide(25, 10, 20) === 1 && CM.levelSide(15, 10, 20) === 0, 'level side');

/* ---- ticks ---- */
let tk = CM.niceTicks(100, 200, 5); ok(tk.step === 20 && tk.ticks.join() === '100,120,140,160,180,200', 'nice ticks 100..200: ' + tk.ticks.join());
tk = CM.niceTicks(912.3, 947.8, 5); ok(tk.ticks.length >= 3 && tk.ticks.every(t => t >= 912.3 && t <= 947.8), 'ticks stay inside the range: ' + tk.ticks.join());
tk = CM.niceTicks(0.011, 0.019, 5); ok(tk.ticks.length >= 2, 'tiny prices still get ticks');
tk = CM.niceTicks(5, 5, 5); ok(tk.ticks.length === 0, 'zero range = no ticks');
tk = CM.niceTicks(NaN, 5, 5); ok(tk.ticks.length === 0, 'NaN range = no ticks');
tk = CM.niceTicks(0, 1e9, 5); ok(tk.ticks.length > 0 && tk.ticks.length < 41, 'huge range bounded');
ok(CM.fmtTick(12345, 500) === '12,345' && CM.fmtTick(1234567, 1000) === '12,34,567', 'Indian grouping in ticks: ' + CM.fmtTick(1234567, 1000));
ok(CM.fmtTick(912.5, 0.5) === '912.5' && CM.fmtTick(NaN, 1) === '' && CM.fmtTick(912.5, 0.05) === '912.50', 'tick decimals follow the step');
ok(CM.fmtPrice(0) === '—' && CM.fmtPrice(NaN) === '—' && CM.fmtPrice('abc') === '—' && CM.fmtPrice(912.4) === '912.40', 'fmtPrice: ' + CM.fmtPrice(912.4));

/* ---- date ticks ---- */
const IST = 19800000;
function mkDates(n, startY, startM, startD) { // n weekdays from a date, 09:15 IST
  const out = []; let t = Date.UTC(startY, startM, startD, 3, 45);
  while (out.length < n) { const w = new Date(t + IST).getUTCDay(); if (w !== 0 && w !== 6) out.push(t); t += 86400000; }
  return out;
}
const D = mkDates(500, 2025, 0, 1);
let dt = CM.dateTicks(D, { s: 0, c: 500 }, 500, 300, 40); ok(dt.length >= 5 && dt.every(x => /^[A-Z][a-z]{2}( ’\d\d)?$/.test(x.text)), 'zoomed out: month labels: ' + dt.map(x => x.text).join(','));
ok(dt.some(x => /Jan ’26/.test(x.text)), 'January carries the year');
for (let i = 1; i < dt.length; i++) ok((dt[i].i - dt[i - 1].i) * 300 / 500 >= 40 - 1e-9, 'month labels at least 40 px apart #' + i);
dt = CM.dateTicks(D, { s: 440, c: 60 }, 500, 300, 40); ok(dt.length >= 3 && dt.some(x => /^\d+$/.test(x.text)), 'mid zoom: week starts as day numbers: ' + dt.map(x => x.text).join(','));
dt = CM.dateTicks(D, { s: 470, c: 22 }, 500, 300, 40); ok(dt.length >= 3 && dt.every(x => /^\d+( [A-Z][a-z]{2})?$/.test(x.text)), 'zoomed in: day numbers: ' + dt.map(x => x.text).join(','));
for (let i = 1; i < dt.length; i++) ok((dt[i].i - dt[i - 1].i) * 300 / 22 >= 40 - 1e-9, 'day labels at least 40 px apart #' + i);
dt = CM.dateTicks(D, { s: 0, c: 1 }, 1, 300, 40); ok(dt.length <= 1, 'one bar: at most one label');
dt = CM.dateTicks(D.map(() => 0), { s: 0, c: 100 }, 500, 300, 40); ok(dt.length === 0, 'zero dates = no labels');
dt = CM.dateTicks(null, { s: 0, c: 100 }, 500, 300, 40); ok(dt.length === 0, 'missing dates = no labels');
dt = CM.dateTicks(D.map((x, i) => i % 3 === 0 ? 0 : x), { s: 0, c: 100 }, 500, 300, 40); ok(Array.isArray(dt), 'gappy dates tolerated');
dt = CM.dateTicks(D, { s: 0, c: 100 }, 500, 0, 40); ok(dt.length === 0, 'no plot width = no labels');
ok(CM.fmtDate(Date.UTC(2026, 10, 12, 3, 45)) === 'Thu 12 Nov 2026', 'fmtDate IST: ' + CM.fmtDate(Date.UTC(2026, 10, 12, 3, 45)));
ok(CM.fmtDate(0) === '—' && CM.fmtDate(null) === '—' && CM.fmtDate('x') === '—', 'fmtDate junk');

/* ---- label de-overlap ---- */
let dd = CM.dodge([100, 102, 104], 12, 0, 400); ok(dd[1] - dd[0] >= 12 && dd[2] - dd[1] >= 12, 'three close labels are spread: ' + dd.join());
dd = CM.dodge([300, 100, 200], 10, 0, 400); ok(dd[1] === 100 && dd[2] === 200 && dd[0] === 300, 'far apart labels stay and keep their order');
dd = CM.dodge([395, 396, 397], 12, 0, 400); ok(Math.max.apply(null, dd) <= 400 && Math.min.apply(null, dd) >= 0 && Math.abs(dd[1] - dd[0]) >= 12 - 1e-9 && Math.abs(dd[2] - dd[1]) >= 12 - 1e-9, 'labels at the bottom edge are pushed up: ' + dd.join());
dd = CM.dodge([1, 2, 3], 12, 0, 400); ok(Math.min.apply(null, dd) >= 0 && dd[1] - dd[0] >= 12 - 1e-9, 'labels at the top edge are pushed down: ' + dd.join());
dd = CM.dodge([5, 5, 5, 5], 10, 0, 400); ok(dd.slice().sort((a, b) => a - b).every((y, i, a) => i === 0 || y - a[i - 1] >= 10 - 1e-9), 'identical wanted positions are all separated: ' + dd.join());
dd = CM.dodge([10, 20, 30, 40, 50], 30, 0, 60); ok(dd.every(y => y >= 0 && y <= 60), 'not enough room: still inside the bounds, evenly spread: ' + dd.join());
dd = CM.dodge([50], 12, 0, 100); ok(dd.length === 1 && dd[0] === 50, 'one label unchanged');
dd = CM.dodge([], 12, 0, 100); ok(dd.length === 0, 'no labels');
dd = CM.dodge(null, 12, 0, 100); ok(dd.length === 0, 'null labels');
dd = CM.dodge([NaN, 50, Infinity], 12, 0, 100); ok(dd.every(y => isFinite(y)), 'non-finite wanted positions tolerated: ' + dd.join());
dd = CM.dodge([50, 60], 12, 100, 0); ok(dd.every(y => isFinite(y)), 'inverted bounds tolerated');
dd = CM.dodge([40, 42], 0, 0, 100); ok(dd[0] === 40 && dd[1] === 42, 'zero gap = unchanged');
{ // random: no overlaps whenever there is room
  let seed = 7; const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
  let bad = 0;
  for (let t = 0; t < 400; t++) {
    const n = 1 + Math.floor(rnd() * 9), ys = []; for (let i = 0; i < n; i++) ys.push(rnd() * 300);
    const out = CM.dodge(ys, 12, 0, 300), s = out.slice().sort((a, b) => a - b);
    for (let i = 1; i < s.length; i++) if (s[i] - s[i - 1] < 12 - 1e-6) bad++;
    if (out.some(y => y < -1e-9 || y > 300 + 1e-9)) bad++;
  }
  ok(bad === 0, 'random label sets never overlap and stay in bounds (' + bad + ' violations)');
}

/* ---- levels ---- */
const info = { call: { label: 'BUY', entryLow: 910, entryHigh: 925, stop: 862, t1: 968, t2: 1012 }, levels: { support: 880, resistance: 1030, trailStop: 870 } };
let lv = CM.buildLevels(info, { target: 950, stop: 870 });
ok(lv.map(x => x.k).join() === 'zone,stop,t1,t2,sup,res,trail,mytgt,mystop', 'levels in order: ' + lv.map(x => x.k).join());
ok(lv[0].v === 925 && lv[0].v2 === 910, 'entry zone is a band (high, low)');
ok(lv.filter(x => x.k === 'mytgt')[0].t === 'Your target', 'user target is labelled "Your target"');
lv = CM.buildLevels({ call: { label: 'AVOID', stop: 5, t1: 9 }, levels: {} }, null); ok(lv.length === 0, 'AVOID draws no call levels');
lv = CM.buildLevels({ call: { label: 'WAIT', entryLow: 0, entryHigh: 0, stop: 800, t1: 0, t2: 0 } }, {}); ok(lv.length === 1 && lv[0].k === 'stop', 'zero entries are skipped');
lv = CM.buildLevels(null, null); ok(lv.length === 0, 'null info');
lv = CM.buildLevels('junk', 5); ok(lv.length === 0, 'junk info');
lv = CM.buildLevels({ call: 5, levels: [] }, [], { noMine: true }); ok(lv.length === 0, 'junk call/levels');
lv = CM.buildLevels(info, { target: 950 }, { noMine: true }); ok(!lv.some(x => x.k === 'mytgt'), 'noMine option');
lv = CM.buildLevels({ call: { label: 'BUY', entryLow: '910', entryHigh: 'abc', stop: -1, t1: NaN } }, { target: 'x', stop: 0 }); ok(lv.length === 0, 'junk numbers skipped');

/* ---- bar info ---- */
const res = { d: [1.7e12, 1.7e12 + 86400000], o: [100, 101], h: [103, 104], l: [99, 100], c: [102, 103.02], v: [1000, 2500], ema10: [0, 101.5], ema20: [0, 0] };
let bi = CM.barInfo(res, 1, [10, 20, 50]);
ok(bi.c === 103.02 && bi.o === 101 && bi.h === 104 && bi.l === 100 && bi.v === 2500, 'bar values');
near(bi.chg, (103.02 / 102 - 1) * 100, 1e-9, 'day change vs the previous close');
ok(bi.emas.length === 3 && bi.emas[0].v === 101.5 && bi.emas[1].v === null && bi.emas[2].v === null, 'EMA values; zero / missing = null');
bi = CM.barInfo(res, 0, [10]); ok(bi.chg === null, 'first bar has no change');
bi = CM.barInfo(res, 99, [10]); ok(bi.c === null && bi.v === null && bi.d === null, 'out-of-range bar is all nulls');
bi = CM.barInfo(null, 3, null); ok(bi.emas.length === 0 && bi.c === null, 'null response');
bi = CM.barInfo({ c: 'abc', o: [NaN], h: {}, l: null, v: 'x' }, 0, [10]); ok(bi.c === null && bi.o === null, 'junk arrays');
bi = CM.barInfo({ c: [0, 5], v: [0, 0] }, 1, []); ok(bi.chg === null, 'previous close zero = no change');

/* ---- inertia ---- */
ok(CM.flingVelocity([], 100) === 0 && CM.flingVelocity(null, 0) === 0 && CM.flingVelocity([{ x: 1, t: 1 }], 5) === 0, 'no samples = no fling');
let fv = CM.flingVelocity([{ x: 0, t: 0 }, { x: 50, t: 50 }, { x: 100, t: 100 }], 110); near(fv, 1, 1e-9, 'fling velocity px/ms');
ok(CM.flingVelocity([{ x: 0, t: 0 }, { x: 100, t: 50 }], 400) === 0, 'finger rested before lifting = no fling');
ok(Math.abs(CM.flingVelocity([{ x: 0, t: 0 }, { x: 5000, t: 10 }], 12)) <= 4, 'fling speed is capped');
ok(CM.decayVelocity(1, 0) === 1 && CM.decayVelocity(1, 240) < 0.4 && CM.decayVelocity(1, 5000) < 0.001 && CM.decayVelocity(1, -5) === 1, 'velocity decays');


/* ---- dateTicks with typed arrays and precomputed dateMeta (what the canvas uses) ---- */
{
  const F = Float64Array.from(D), meta = CM.dateMeta(F, 500);
  const a = CM.dateTicks(D, { s: 0, c: 500 }, 500, 300, 40), b = CM.dateTicks(F, { s: 0, c: 500 }, 500, 300, 40), c2 = CM.dateTicks(F, { s: 0, c: 500 }, 500, 300, 40, meta);
  ok(a.length === b.length && b.length === c2.length && a.every((x, i) => x.text === b[i].text && x.text === c2[i].text), 'typed array and meta give the same labels as a plain array');
  const g = Float64Array.from(D); g[10] = 0; g[11] = NaN;
  ok(CM.dateTicks(g, { s: 0, c: 500 }, 500, 300, 40, CM.dateMeta(g, 500)).length >= 3, 'gaps in the dates do not break the labels');
  ok(CM.dateMeta(null, 10).n === 0 && CM.dateMeta(F, 0).n === 0, 'dateMeta on junk is empty');
}

console.log((fail ? 'FAILED ' : 'OK — ') + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
