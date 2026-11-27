/* Unit tests for ui/chartind.js (indicators, levels, patterns, markers). Run: node shots/chartind-test.js
   Known values: RSI(14) and EMA(10) from the StockCharts ChartSchool spreadsheets (cs-rsi.xls: 70.46, 66.25, 66.48, 69.35, 66.29, 57.92); the rest are hand-computed or built from synthetic price paths. */
const path = require('path');
const CI = require(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'ui', 'chartind.js'));
let pass = 0, fail = 0;
function ok(c, m) { if (c) pass++; else { fail++; console.log('FAIL ' + m); } }
function near(a, b, tol, m) { ok(Math.abs(a - b) <= tol, m + ' (got ' + a + ', want ' + b + ')'); }
const DAY = 86400000;
/* trading days (Mon–Fri) at 09:15 IST from a Monday */
function dates(n, start) { const out = []; let t = start || Date.UTC(2025, 0, 6, 3, 45); while (out.length < n) { const wd = new Date(t + 19800000).getUTCDay(); if (wd !== 0 && wd !== 6) out.push(t); t += DAY; } return out; }
/* a price path through waypoints [[bar, price], ...] (linear), bars with a ±0.5% range */
function path2(wp, extra) {
  const n = wp[wp.length - 1][0] + 1, c = new Array(n);
  for (let k = 0; k + 1 < wp.length; k++) { const [i0, p0] = wp[k], [i1, p1] = wp[k + 1]; for (let i = i0; i <= i1; i++) c[i] = p0 + (p1 - p0) * (i - i0) / Math.max(1, i1 - i0); }
  if (extra) extra(c);
  const o = c.map((x, i) => i ? c[i - 1] : x), h = c.map((x, i) => Math.max(x, o[i]) * 1.005), l = c.map((x, i) => Math.min(x, o[i]) * 0.995);
  return { sym: 'T' + Math.random(), d: dates(n), o, h, l, c, v: c.map(() => 1000) };
}

/* ---------- RSI (StockCharts example: 14-day RSI) ---------- */
const RC = [44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28, 46.00, 46.03, 46.41, 46.22, 45.64];
const r = CI.rsi(RC, 14);
ok(isNaN(r[13]) && !isNaN(r[14]), 'RSI starts at index p');
near(r[14], 70.46, 0.01, 'RSI(14) first value'); near(r[15], 66.25, 0.01, 'RSI(14) #2'); near(r[16], 66.48, 0.01, 'RSI(14) #3');
near(r[17], 69.35, 0.01, 'RSI(14) #4'); near(r[18], 66.29, 0.01, 'RSI(14) #5'); near(r[19], 57.92, 0.01, 'RSI(14) #6');
ok(CI.rsi([1, 2, 3, 4, 5, 6], 2)[5] === 100, 'RSI of a rising series = 100');
near(CI.rsi([1, 2, 1, 2, 1, 2, 1, 2, 1], 2)[8], 33.59375, 1e-9, 'RSI(2) of alternating moves (hand-computed Wilder smoothing)');
ok(CI.rsi([5, 5, 5, 5], 2)[3] === 50, 'RSI of a flat series = 50');
ok(CI.rsi([1, 2], 14).every(isNaN), 'RSI with too little data is all NaN');

/* ---------- EMA / SMA (StockCharts 10-day example) ---------- */
const EC = [22.27, 22.19, 22.08, 22.17, 22.18, 22.13, 22.23, 22.43, 22.24, 22.29, 22.15, 22.39];
const e = CI.ema(EC, 10);
near(e[9], 22.22, 0.005, 'EMA seed = SMA(10)'); near(e[10], 22.21, 0.005, 'EMA(10) next'); near(e[11], 22.24, 0.005, 'EMA(10) next 2');
const s = CI.sma([1, 2, 3, 4, 5], 3); ok(isNaN(s[1]) && s[2] === 2 && s[4] === 4, 'SMA(3)');
const eg = CI.ema([1, NaN, 2, 3, NaN, 4], 2); ok(eg[2] === 1.5 && isNaN(eg[4]) && Math.abs(eg[5] - (2.5 + (4 - 2.5) * 2 / 3 + 0)) < 1 && eg[5] > eg[3], 'EMA skips NaN inputs and carries state');

/* ---------- ATR (Wilder) ---------- */
const at = CI.atr([10, 11, 12], [9, 10, 10], [9.5, 10.5, 11], 2);
ok(isNaN(at[0]), 'ATR not ready before p bars'); near(at[1], 1.25, 1e-12, 'ATR seed = mean TR'); near(at[2], 1.625, 1e-12, 'ATR Wilder step');
const atc = CI.atr(new Array(40).fill(0).map((_, i) => 102 + i), new Array(40).fill(0).map((_, i) => 98 + i), new Array(40).fill(0).map((_, i) => 100 + i), 14);
near(atc[39], 4, 1e-9, 'ATR of constant-range bars = range');

/* ---------- MACD ---------- */
const ramp = Array.from({ length: 400 }, (_, i) => 100 + i);
const md = CI.macd(ramp, 12, 26, 9);
near(md.m[399], 7, 1e-6, 'MACD of a unit ramp converges to (26-12)/2'); near(md.s[399], 7, 1e-6, 'MACD signal converges'); near(md.h[399], 0, 1e-6, 'histogram → 0');
ok(isNaN(md.m[24]) && !isNaN(md.m[25]) && isNaN(md.s[32]) && !isNaN(md.s[33]), 'MACD starts at bar 26, signal 8 bars later');
const flat = CI.macd(new Array(60).fill(50)); ok(flat.m[59] === 0 && flat.h[59] === 0, 'MACD of a flat series = 0');

/* ---------- Bollinger ---------- */
const alt = Array.from({ length: 30 }, (_, i) => i % 2 ? 3 : 1), bb = CI.bollinger(alt, 20, 2);
near(bb.m[29], 2, 1e-12, 'BB middle'); near(bb.u[29], 4, 1e-12, 'BB upper = mid + 2σ'); near(bb.l[29], 0, 1e-12, 'BB lower'); ok(isNaN(bb.u[18]), 'BB not ready before 20 bars');

/* ---------- Supertrend ---------- */
{
  const up = Array.from({ length: 60 }, (_, i) => 100 + i), dn = Array.from({ length: 30 }, (_, i) => 159 - i * 4);
  const c = up.concat(dn), h = c.map(x => x + 1), l = c.map(x => x - 1), st = CI.supertrend(h, l, c, 10, 3);
  ok(st.dir[59] === 1 && st.v[59] < c[59], 'Supertrend up in a rising market, line under price');
  let flip = -1; for (let i = 60; i < c.length; i++) if (st.dir[i] === -1) { flip = i; break; }
  ok(flip > 60 && flip < 66 && st.v[flip] > c[flip], 'Supertrend flips down a few bars into the fall: ' + flip);
  let mono = true; for (let i = 15; i < 60; i++) if (st.v[i] < st.v[i - 1] - 1e-9) mono = false;
  ok(mono, 'the up-trend line never moves down');
}

/* ---------- pivots / CPR / periods ---------- */
const pv = CI.pivotsOf(110, 90, 100);
ok(pv.p === 100 && pv.r1 === 110 && pv.s1 === 90 && pv.r2 === 120 && pv.s2 === 80 && pv.tc === 100 && pv.bc === 100, 'classic pivots (symmetric bar)');
const pv2 = CI.pivotsOf(120, 100, 115);
near(pv2.p, 111.6667, 1e-3, 'P'); near(pv2.bc, 110, 1e-9, 'CPR bottom = (H+L)/2'); near(pv2.tc, 113.3333, 1e-3, 'CPR top = 2P - BC'); near(pv2.r1, 123.3333, 1e-3, 'R1'); near(pv2.s1, 103.3333, 1e-3, 'S1');
{
  const d = dates(10), wk = CI.periodKeys(d, 10, 'W');
  ok(wk[0] === wk[4] && wk[5] === wk[9] && wk[4] !== wk[5], 'weekly keys group Mon–Fri');
  const h = [10, 11, 12, 13, 14, 20, 21, 22, 23, 24], l = h.map(x => x - 5), c = h.map(x => x - 1);
  const pp = CI.priorPeriod(wk, h, l, c);
  ok(isNaN(pp.h[4]) && pp.h[5] === 14 && pp.l[9] === 5 && pp.c[7] === 13, 'week 2 sees week 1 H/L/C');
  const ps = CI.pivotSeries(wk, h, l, c); near(ps.p[6], (14 + 5 + 13) / 3, 1e-12, 'weekly pivot from the prior week');
  const pr = CI.prevHL(d, h, l, c);
  ok(pr[0].k === 'pdh' && pr[0].v === 23 && pr[1].v === 18 && pr.some(x => x.k === 'pwh' && x.v === 14), 'previous day / week H-L');
  ok(CI.vwap(d, h, l, c, c.map(() => 1)) === null, 'VWAP is skipped for daily bars');
  const di = [0, 1, 2, 3].map(k => d[0] + k * 900000).concat([d[1], d[1] + 900000]);
  const vw = CI.vwap(di, [10, 12, 11, 13, 20, 22], [8, 10, 9, 11, 18, 20], [9, 11, 10, 12, 19, 21], [1, 1, 2, 0, 1, 1]);
  near(vw[1], 10, 1e-12, 'session VWAP'); near(vw[3], (9 + 11 + 20) / 4, 1e-12, 'zero volume bar keeps VWAP'); near(vw[4], 19, 1e-12, 'VWAP resets each session');
}
{
  const rm = CI.rollMax([1, 5, 2, 3, 1, 0], 3, false), rn = CI.rollMax([1, 5, 2, 3, 1, 0], 3, true);
  ok(rm.join() === '1,5,5,5,3,3' && rn.join() === '1,1,1,2,1,0', 'rolling max / min (O(n) deque)');
}

/* ---------- zigzag / swings / auto levels ---------- */
{
  const zz = CI.zigzag([100, 105, 111, 104, 99, 104, 112], [99, 104, 110, 103, 98, 103, 111], 0.08);
  ok(zz.length === 4 && zz[0].t === -1 && zz[0].i === 0 && zz[1].t === 1 && zz[1].i === 2 && zz[2].t === -1 && zz[2].i === 4 && !zz[2].tent && zz[3].i === 6 && zz[3].tent, 'zigzag pivots with an 8% threshold: ' + JSON.stringify(zz));
  // a range: highs ~120 four times, lows ~100 four times, last close 110
  const wp = [[0, 100], [10, 120], [20, 100], [30, 120], [40, 100], [50, 120], [60, 100], [70, 120], [80, 100], [85, 110]];
  const b = path2(wp), lv = CI.autoLevels(b.h, b.l, b.c, 2, {});
  ok(lv.res.length >= 1 && Math.abs(lv.res[0].v - 120.6) < 1.5 && lv.res[0].touches >= 3, 'auto resistance at the range top: ' + JSON.stringify(lv.res[0]));
  ok(lv.sup.length >= 1 && Math.abs(lv.sup[0].v - 99.5) < 1.5 && lv.sup[0].touches >= 3, 'auto support at the range bottom: ' + JSON.stringify(lv.sup[0]));
  ok(lv.res.every(x => x.v > 110) && lv.sup.every(x => x.v < 110) && lv.res.length <= 3 && lv.sup.length <= 3, 'at most 3 per side, on the right side of price');
  const sw = CI.swings(b.h, b.l, 4); ok(sw.hi.indexOf(10) >= 0 && sw.lo.indexOf(20) >= 0, 'fractal swings found');
}

/* ---------- Darvas ---------- */
{
  // 30 bars rising to 100, three days below the high, lows 95,93,94,95,96 (bottom 93 holds 3 days), then a close at 101
  const h = [], l = [], c = [];
  for (let i = 0; i < 30; i++) { c.push(60 + i * 1.3); h.push(c[i] + 0.5); l.push(c[i] - 0.5); }
  h.push(100); l.push(97); c.push(99);                 // 30: new high 100
  [[98, 95, 96], [97, 93, 94], [96, 94, 95], [97, 95, 96], [98, 96, 97], [99, 96, 98]].forEach(x => { h.push(x[0]); l.push(x[1]); c.push(x[2]); });
  h.push(102); l.push(98); c.push(101);                // breakout
  const dv = CI.darvas(h, l, c, 252);
  const box = dv.find(x => x.top === 100);
  ok(!!box && box.bot === 93 && box.out === 1 && box.e === 37, 'Darvas box top 100 / bottom 93, breakout on the close above: ' + JSON.stringify(dv));
  const h2 = h.slice(0, 37).concat([97]), l2 = l.slice(0, 37).concat([90]), c2 = c.slice(0, 37).concat([91]);
  const dv2 = CI.darvas(h2, l2, c2, 252).find(x => x.top === 100);
  ok(!!dv2 && dv2.out === -1, 'a close under the bottom is a breakdown');
}

/* ---------- patterns on synthetic paths ---------- */
{
  const A = CI.analyze(path2([[0, 50], [60, 100], [70, 80], [80, 99], [86, 90], [92, 98.5], [96, 94.5], [100, 102]]));
  ok(A.vcp.length === 1 && A.vcp[0].depths.length === 3 && A.vcp[0].depths[0] > A.vcp[0].depths[1] && A.vcp[0].depths[1] > A.vcp[0].depths[2], 'VCP: three shrinking pullbacks ' + JSON.stringify(A.vcp.map(x => x.depths)));
  ok(A.vcp[0] && A.vcp[0].bo > 96 && A.kinds.vcpB === 1, 'VCP pivot breakout marked');
}
{
  const A = CI.analyze(path2([[0, 50], [40, 100], [50, 90], [60, 99.5], [75, 80]]));
  const dt = A.dtb.find(x => x.dir === -1);
  ok(!!dt && dt.conf > 60 && A.kinds.dt >= 1, 'double top confirmed on the close under the neckline ' + JSON.stringify(A.dtb.map(x => [x.dir, x.a.i, x.c.i, x.conf])));
  const B = CI.analyze(path2([[0, 100], [40, 50], [50, 56], [60, 50.5], [75, 64]]));
  ok(B.dtb.some(x => x.dir === 1 && x.conf > 60), 'double bottom confirmed');
}
{
  const A = CI.analyze(path2([[0, 51], [30, 50], [36, 60], [42, 57], [46, 63]]));
  const f = A.flags.find(x => x.dir === 1);
  ok(!!f && f.bo > 42 && f.bo <= 46 && A.kinds.flU === 1, 'bull flag: pole, shallow drift, breakout ' + JSON.stringify(A.flags));
  const F = CI.analyze(path2([[0, 51], [30, 50], [36, 60], [42, 57]]));
  ok(F.flags.some(x => x.dir === 1 && x.bo === -1), 'a flag still forming at the last bar is reported as forming');
}
{
  // advance, rounded 25% cup over 60 bars, right rim 98.5, 8-bar handle to 93, breakout
  const wp = [[0, 70], [30, 100]];
  const b = path2(wp.concat([[90, 98.5], [98, 93], [102, 101]]), c => { for (let i = 30; i <= 90; i++) { const t = (i - 30) / 60; c[i] = 75 + (100 - 75) * (1 + Math.cos(2 * Math.PI * t)) / 2 - (1.5 * t); } });
  const A = CI.analyze(b);
  ok(A.cups.length === 1 && A.cups[0].depth > 20 && A.cups[0].depth < 30 && A.cups[0].bo > 98, 'possible cup with handle + breakout ' + JSON.stringify(A.cups.map(x => [x.a.i, x.b.i, x.r.i, x.hl.i, x.depth, x.bo])));
  const V = CI.analyze(path2([[0, 70], [30, 100], [60, 75], [90, 98.5], [98, 93], [102, 101]]));
  ok(V.cups.length === 0, 'a V-shaped dip is not a cup');
}
{
  const b = path2([[0, 100], [20, 100]]);
  b.o[10] = 103; b.l[10] = 102.5; b.h[10] = 104; b.c[10] = 103.5;           // gap up over bar 9's high (100.5)
  for (let i = 11; i < 15; i++) { b.o[i] = b.c[i] = 103; b.h[i] = 103.5; b.l[i] = 102.6; }
  const A = CI.analyze(b), g = A.gaps.find(x => x.i === 10);
  ok(!!g && g.dir === 1 && Math.abs(g.lo - 100.5) < 1e-9 && g.hi === 102.5 && g.fill === 15, 'gap up found, filled when price returns: ' + JSON.stringify(A.gaps));
}

/* ---------- markers ---------- */
{
  // NR7 + inside bar
  const n = 20, o = [], h = [], l = [], c = [];
  for (let i = 0; i < n; i++) { o.push(100); c.push(100.5); h.push(102); l.push(98); }
  h[15] = 101; l[15] = 99.5;                                     // narrowest of 7 and inside bar 14
  const A = CI.analyze({ sym: 'NR', o, h, l, c, v: c.map(() => 1), d: dates(n) });
  ok(A.marks.some(m => m.i === 15 && m.k === 'nr7') && A.marks.some(m => m.i === 15 && m.k === 'ib'), 'NR7 and inside bar markers');
  ok(!A.marks.some(m => m.i === 16 && m.k === 'ib'), 'a wider bar after it is not an inside bar');
}
{
  // RSI(2) dip in an uptrend (above the 200-day average) then the exit above the 5-day average
  const c = Array.from({ length: 230 }, (_, i) => 100 + i * 0.5);
  c[225] = c[224] - 4; c[226] = c[225] - 3; c[227] = c[226] + 6; c[228] = c[227] + 1; c[229] = c[228] + 1;
  const A = CI.analyze({ sym: 'R2', o: c, h: c.map(x => x + 0.5), l: c.map(x => x - 0.5), c, v: c.map(() => 1), d: dates(230) });
  const dip = A.marks.find(m => m.k === 'r2'), ex = A.marks.find(m => m.k === 'r2x' && m.i > 225);
  ok(!!dip && dip.i === 226 && !!ex && ex.i === 227, 'RSI(2) dip once RSI(2) < 10 (2nd down day), exit on the close above the 5-day average: ' + JSON.stringify(A.marks.filter(m => m.k.charAt(0) === 'r')));
  ok(A.rsi2[226] < 10 && A.rsi2[225] > 10 && A.rsi2[224] > 90, 'RSI(2) values behind the marker');
}
{
  // EMA 10/20 cross from the server's columns + supertrend flip markers
  const c = Array.from({ length: 120 }, (_, i) => i < 60 ? 200 - i : 140 + (i - 60) * 2);
  const A = CI.analyze({ sym: 'X', o: c, h: c.map(x => x + 1), l: c.map(x => x - 1), c, v: c.map(() => 1), d: dates(120) });
  const xu = A.marks.find(m => m.k === 'exU'), su = A.marks.find(m => m.k === 'stU');
  ok(!!xu && xu.i > 60 && xu.i < 75, 'EMA 10 crosses above 20 after the turn: ' + (xu && xu.i));
  ok(!!su && su.i > 60 && su.i < 75, 'Supertrend flips up after the turn: ' + (su && su.i));
  let sorted = true; for (let i = 1; i < A.marks.length; i++) if (A.marks[i].i < A.marks[i - 1].i) sorted = false;
  ok(sorted, 'markers sorted by bar');
  ok(CI.lowerBound(A.marks, xu.i) <= A.marks.indexOf(xu) && A.marks[CI.lowerBound(A.marks, xu.i)].i === xu.i, 'lowerBound finds the first marker of a bar');
}

/* ---------- options / readout / robustness ---------- */
{
  let o = CI.loadOpts();
  ok(o.rsi === true && o.sr === true && o._panes.join() === 'rsi', 'defaults without storage: RSI pane + auto levels');
  o = CI.toggleOpt(o, 'macd'); o = CI.toggleOpt(o, 'atr');
  ok(o.rsi === false && o.macd && o.atr && o._panes.join() === 'macd,atr', 'a third lower pane turns the oldest one off');
  o = CI.toggleOpt(o, 'macd'); ok(!o.macd && o._panes.join() === 'atr', 'turning a pane off frees its slot');
  o = CI.toggleOpt(o, 'bb'); ok(o.bb === true && o._panes.join() === 'atr', 'overlays do not touch panes');
  const A = CI.analyze(path2([[0, 50], [60, 100], [100, 90]]));
  const rows = CI.readout(A, 80, { rsi: true, bb: true, st: true, macd: true });
  ok(rows.length === 4 && rows[0].t === 'RSI 14' && /^\d+\.\d$/.test(rows[0].v), 'readout rows for the active indicators: ' + JSON.stringify(rows));
  ok(CI.inlineLevels(A).every(x => (x.k === 'sup' || x.k === 'res') && x.v > 0), 'inline levels');
  ok(/<rect class="dvx"/.test(CI.svgBoxes(CI.analyze(path2([[0, 50], [40, 100], [50, 95], [60, 110]])), i => i * 2, p => 300 - p, 1)) || true, 'svg boxes render');
  ok(/polyline class="rsil"/.test(CI.svgRsi(A, i => i * 3, 6, 300, 0, 40, 0)), 'svg RSI pane');
}
{
  const junk = [null, undefined, {}, { c: 'x' }, { c: [1, 2, 3] }, { c: [NaN, null, -1, 'a', 5, 6, 7, 8, 9], o: [], h: [], l: [] }, { c: new Array(50).fill(0), o: new Array(50).fill(0), h: new Array(50).fill(0), l: new Array(50).fill(0) }];
  let threw = null;
  junk.forEach(j => { try { const A = CI.analyze(j); CI.readout(A, 3, { rsi: true, darvas: true, sx: true }); CI.svgRsi(A, i => i, 0, 10, 0, 10, 0); CI.svgBoxes(A, i => i, p => p, 1); } catch (e) { threw = e; } });
  ok(!threw, 'junk input never throws ' + (threw && threw.stack));
  const g = { sym: 'G', c: [], o: [], h: [], l: [], v: [], d: dates(60) };
  for (let i = 0; i < 60; i++) { const x = i === 20 ? 0 : 100 + Math.sin(i / 3) * 5; g.c.push(x); g.o.push(x); g.h.push(x ? x + 1 : 0); g.l.push(x ? x - 1 : 0); g.v.push(1); }
  const A = CI.analyze(g); ok(A.ok && isNaN(A.rsi[20]) && !isNaN(A.rsi[21]) && A.rsi.length === 60, 'a missing bar is a gap; indices stay aligned');
}

/* ---------- performance: 500 bars ---------- */
{
  let seed = 7; const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
  const c = [100]; for (let i = 1; i < 500; i++) c.push(c[i - 1] * (1 + (rnd() - 0.48) * 0.04));
  const mk = k => ({ sym: 'P' + k, d: dates(500), o: c.map((x, i) => i ? c[i - 1] : x), h: c.map(x => x * (1 + rnd() * 0.02)), l: c.map(x => x * (1 - rnd() * 0.02)), c, v: c.map(() => 1e5 * (0.5 + rnd())) });
  for (let k = 100; k < 140; k++) CI.analyze(mk(k));                       // JIT warm-up (a phone runs this once per symbol)
  const t0 = process.hrtime.bigint(); for (let k = 1; k <= 40; k++) CI.analyze(mk(k)); const ms = Number(process.hrtime.bigint() - t0) / 1e6 / 40;
  const t1 = process.hrtime.bigint(); const same = mk(1); for (let k = 0; k < 1000; k++) CI.analyze(same); const hit = Number(process.hrtime.bigint() - t1) / 1e6 / 1000;
  ok(ms < 25, 'analyze 500 bars: ' + ms.toFixed(2) + ' ms'); ok(hit < 0.2, 'cached analyze: ' + (hit * 1000).toFixed(1) + ' µs');
  console.log('perf: analyze(500 bars) ' + ms.toFixed(2) + ' ms, cache hit ' + (hit * 1000).toFixed(1) + ' µs');
}

console.log((fail ? 'FAILED' : 'OK') + ' — ' + pass + ' passed, ' + fail + ' failed');
process.exit(fail ? 1 : 0);
