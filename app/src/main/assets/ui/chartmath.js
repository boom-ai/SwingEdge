/* SwingEdge — chart maths. Pure functions, no DOM: runs in the WebView and in node (shots/chartmath-test.js).
   View model: v = {s, c}  s = left edge in bar-index units (float), c = number of bars visible (float).
   Bar i is centred at i + 0.5 in those units, so x = L + (i + 0.5 - s) / c * W. Never throws on junk input. */
(function (root) {
  'use strict';
  var IST = 19800000;
  var MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  var WD = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var MINB = 20;                                   // fewest bars the user can zoom in to
  var RANGES = [['3M', 63], ['6M', 126], ['1Y', 252], ['2Y', 500], ['All', 0]];
  var DEFAULT_BARS = 126;                          // "default window" = about 6 months

  function fin(v) { return typeof v === 'number' && isFinite(v); }
  function num(v) { return fin(v) ? v : (typeof v === 'string' && v.trim() !== '' && isFinite(Number(v)) ? Number(v) : null); }
  function pos(v) { var n = num(v); return n !== null && n > 0 ? n : 0; }
  function clamp(x, lo, hi) { return x < lo ? lo : x > hi ? hi : x; }
  function cnt(n) { n = num(n); return n !== null && n > 0 ? Math.floor(n) : 0; }

  /* ---------- view window ---------- */
  function clampView(s, c, n, minBars) {
    n = cnt(n);
    if (n <= 0) return { s: 0, c: 0 };
    var mn = Math.min(minBars > 0 ? minBars : MINB, n);
    c = fin(c) ? c : n; c = clamp(c, mn, n);
    s = fin(s) ? s : n - c; s = clamp(s, 0, n - c);
    return { s: s, c: c };
  }
  function viewOf(v, n, minBars) { v = v || {}; return clampView(v.s, v.c, n, minBars); }
  function defaultView(n) { n = cnt(n); var c = Math.min(DEFAULT_BARS, n); return clampView(n - c, c, n); }
  function rangeCount(key, n) {
    n = cnt(n); var c = n;
    for (var i = 0; i < RANGES.length; i++) if (RANGES[i][0] === key) { c = RANGES[i][1] > 0 ? RANGES[i][1] : n; break; }
    return Math.min(n, Math.max(Math.min(MINB, n), c));
  }
  function rangeView(key, n) { n = cnt(n); var c = rangeCount(key, n); return clampView(n - c, c, n); }
  /* every range chip the current view equals (right-aligned and the same bar count); several when the history is short */
  function activeRanges(v, n) {
    n = cnt(n); var out = [];
    if (n <= 0 || !v) return out;
    v = viewOf(v, n);
    if (v.s + v.c < n - 0.5) return out;
    for (var i = 0; i < RANGES.length; i++) if (Math.abs(v.c - rangeCount(RANGES[i][0], n)) < 0.5) out.push(RANGES[i][0]);
    return out;
  }
  function zoomAt(v, factor, frac, n, minBars) {
    v = viewOf(v, n, minBars);
    if (v.c <= 0 || !(factor > 0) || !isFinite(factor)) return v;
    frac = clamp(fin(frac) ? frac : 0.5, 0, 1);
    var idx = v.s + frac * v.c;
    var c2 = clampView(0, v.c / factor, n, minBars).c;
    return clampView(idx - frac * c2, c2, n, minBars);
  }
  function panBy(v, dxPx, plotW, n, minBars) {
    v = viewOf(v, n, minBars);
    if (v.c <= 0 || !(plotW > 0) || !fin(dxPx)) return v;
    return clampView(v.s - dxPx / plotW * v.c, v.c, n, minBars);
  }
  /* two-finger pinch: d0/d1 = finger distance at the start / now; frac0/frac1 = midpoint position (0..1 of the plot) at the start / now */
  function pinchView(v0, d0, d1, frac0, frac1, n, minBars) {
    v0 = viewOf(v0, n, minBars);
    if (v0.c <= 0 || !(d0 > 0) || !(d1 > 0)) return v0;
    var c2 = clampView(0, v0.c * d0 / d1, n, minBars).c;
    var idx0 = v0.s + clamp(fin(frac0) ? frac0 : 0.5, 0, 1) * v0.c;
    return clampView(idx0 - clamp(fin(frac1) ? frac1 : 0.5, 0, 1) * c2, c2, n, minBars);
  }
  /* after a longer history replaces the one on screen keep the same bars visible: how far indices shifted */
  function shiftForNewData(oldD, newD) {
    if (!Array.isArray(oldD) || !Array.isArray(newD) || !oldD.length || !newD.length) return Math.max(0, (Array.isArray(newD) ? newD.length : 0) - (Array.isArray(oldD) ? oldD.length : 0));
    var last = oldD[oldD.length - 1];
    for (var j = newD.length - 1; j >= 0; j--) if (newD[j] === last) return j - (oldD.length - 1);
    return newD.length - oldD.length;
  }

  /* ---------- mapping ---------- */
  function xOf(i, v, L, W) { return L + (i + 0.5 - v.s) / v.c * W; }
  function idxAt(x, v, L, W, n) {
    n = cnt(n); if (n <= 0 || !(W > 0) || !(v && v.c > 0)) return -1;
    return clamp(Math.floor(v.s + (x - L) / W * v.c), 0, n - 1);
  }
  function yOf(p, mn, mx, T, H) { return T + (mx - p) / (mx - mn) * H; }
  function priceAt(y, mn, mx, T, H) { return mx - (y - T) / H * (mx - mn); }

  /* ---------- price axis ---------- */
  /* fit the visible bars (+ visible EMAs + levels that are not wildly outside). series = array of arrays (EMAs). Zeros / NaN are gaps. */
  function priceRange(H, L, i0, i1, series, levels) {
    var mn = Infinity, mx = -Infinity, i, k, a, x;
    H = H || []; L = L || [];
    i0 = Math.max(0, i0 | 0); i1 = Math.min(Math.max(H.length, L.length) - 1, i1 | 0);
    for (i = i0; i <= i1; i++) {
      x = pos(L[i]); if (x > 0 && x < mn) mn = x;
      x = pos(H[i]); if (x > 0 && x > mx) mx = x;
    }
    if (!(mn < Infinity) || !(mx > -Infinity)) { // only highs or only lows are usable
      if (mn < Infinity) mx = mn; else if (mx > -Infinity) mn = mx; else return { min: 0, max: 1, ok: false };
    }
    if (Array.isArray(series)) for (k = 0; k < series.length; k++) {
      a = series[k]; if (!a) continue;
      for (i = i0; i <= i1 && i < a.length; i++) { x = pos(a[i]); if (x > 0) { if (x < mn) mn = x; if (x > mx) mx = x; } }
    }
    var span = mx - mn, base = Math.max(span, mx * 0.01);
    if (Array.isArray(levels)) for (k = 0; k < levels.length; k++) {
      x = pos(levels[k]);
      if (x > 0 && x >= mn - base * 0.6 && x <= mx + base * 0.6) { if (x < mn) mn = x; if (x > mx) mx = x; }
    }
    var pad = (mx - mn) * 0.07;
    if (!(pad > 0)) pad = mx * 0.01 + 0.01;
    mn -= pad; mx += pad;
    if (mn <= 0) mn = Math.max(mx * 0.001, 0.0001);
    return { min: mn, max: mx, ok: true };
  }
  /* -1 below the axis, 1 above, 0 inside */
  function levelSide(p, mn, mx) { return p < mn ? -1 : p > mx ? 1 : 0; }
  function niceTicks(mn, mx, target) {
    var out = { step: 0, ticks: [] };
    if (!fin(mn) || !fin(mx) || !(mx > mn)) return out;
    target = target > 1 ? target : 5;
    var raw = (mx - mn) / target, mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10)), nm = raw / mag;
    var step = (nm < 1.5 ? 1 : nm < 3 ? 2 : nm < 7 ? 5 : 10) * mag;
    if (!(step > 0) || !isFinite(step)) return out;
    var t = Math.ceil(mn / step) * step, guard = 0;
    for (; t <= mx + step * 1e-9 && guard < 40; t += step, guard++) out.ticks.push(Math.round(t / step) * step);
    out.step = step;
    return out;
  }
  function grp(d) { return d.length <= 3 ? d : d.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + d.slice(-3); }
  function fmtTick(v, step) {
    if (!fin(v)) return '';
    var dec = step >= 1 ? 0 : step >= 0.1 ? 1 : 2, a = Math.abs(v);
    if (a >= 1e7) return (v < 0 ? '-' : '') + (a / 1e7).toFixed(2) + 'Cr';
    var f = a.toFixed(dec).split('.');
    return (v < 0 ? '-' : '') + grp(f[0]) + (f[1] ? '.' + f[1] : '');
  }
  function fmtPrice(v) { var n = num(v); if (n === null || n <= 0) return '—'; return fmtTick(n, n >= 1000 ? 1 : 0.01); }

  /* ---------- date axis ---------- */
  function parts(t) { var d = new Date(t + IST); return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate(), w: d.getUTCDay() }; }
  function fmtDate(t) {
    t = num(t); if (t === null || !(t > 1e11 && t < 4.2e12)) return '—';
    var p = parts(t); return WD[p.w] + ' ' + p.d + ' ' + MON[p.m] + ' ' + p.y;
  }
  function weekNo(t) { return Math.floor(((t + IST) / 86400000 + 3) / 7); }
  function isArr(a) { return Array.isArray(a) || (!!a && typeof a === 'object' && typeof a.length === 'number' && typeof a.BYTES_PER_ELEMENT === 'number'); }
  /* per-bar calendar fields, computed once per data set so dateTicks allocates nothing per bar while panning */
  function dateMeta(d, n) {
    n = cnt(n); var len = isArr(d) ? Math.min(n, d.length) : 0;
    var m = { n: len, ok: new Uint8Array(len), y: new Int16Array(len), m: new Int8Array(len), d: new Int8Array(len), wk: new Int32Array(len) };
    for (var i = 0; i < len; i++) {
      var t = d[i];
      if (t > 1e11 && t < 4.2e12) { var p = parts(t); m.ok[i] = 1; m.y[i] = p.y; m.m[i] = p.m; m.d[i] = p.d; m.wk[i] = weekNo(t); }
    }
    return m;
  }
  /* labels for the visible bars: months when zoomed out, week starts in the middle, day numbers when zoomed in. minGap in px. */
  function dateTicks(d, v, n, plotW, minGap, meta) {
    var out = [];
    n = cnt(n); if (!isArr(d) || !(v && v.c > 0) || n <= 0 || !(plotW > 0)) return out;
    if (!meta || meta.n !== Math.min(n, d.length)) meta = dateMeta(d, n);
    var i0 = Math.max(0, Math.floor(v.s)), i1 = Math.min(n - 1, d.length - 1, Math.ceil(v.s + v.c) - 1);
    var mode = v.c > 75 ? 'month' : v.c > 30 ? 'week' : 'day', per = plotW / v.c;
    var prevM = -1, prevW = -1, cand = [], i, pm, pd, py, lab, major;
    for (i = i0; i <= i1; i++) {
      if (!meta.ok[i]) continue;
      pm = meta.m[i]; pd = meta.d[i]; py = meta.y[i]; lab = null; major = false;
      if (mode === 'month') { if (pm !== prevM && prevM !== -1) { lab = pm === 0 ? 'Jan ’' + String(py).slice(2) : MON[pm]; major = pm === 0; } }
      else if (mode === 'week') {
        var w = meta.wk[i];
        if (pm !== prevM && prevM !== -1) { lab = pd + ' ' + MON[pm]; major = true; }
        else if (w !== prevW && prevW !== -1) lab = String(pd);
        else if (i === i0) lab = pd + ' ' + MON[pm];
        prevW = w;
      } else {
        if (pm !== prevM && prevM !== -1) { lab = pd + ' ' + MON[pm]; major = true; } else lab = i === i0 ? pd + ' ' + MON[pm] : String(pd);
      }
      prevM = pm;
      if (lab) cand.push({ i: i, text: lab, major: major });
    }
    var gap = minGap > 0 ? minGap : 40, placed = [], pass, j;
    for (pass = 0; pass < 2; pass++) {            // year / month-change labels first, the rest fill the gaps
      for (i = 0; i < cand.length; i++) {
        if ((pass === 0) !== cand[i].major) continue;
        var x = (cand[i].i + 0.5 - v.s) * per, need = Math.max(gap, cand[i].text.length * 6.4 + 10), clash = false;
        for (j = 0; j < placed.length; j++) if (Math.abs(placed[j].x - x) < Math.max(need, placed[j].need)) { clash = true; break; }
        if (!clash) { placed.push({ x: x, need: need }); out.push(cand[i]); }
      }
    }
    out.sort(function (a, b) { return a.i - b.i; });
    return out;
  }

  /* ---------- labels that must not overlap ---------- */
  /* ys = wanted centre of each label; returns the y to draw each at (same order), >= gap apart inside [lo, hi] */
  function dodge(ys, gap, lo, hi) {
    var n = Array.isArray(ys) ? ys.length : 0, out = [], idx = [], i, k;
    if (!n) return out;
    if (!(hi >= lo)) hi = lo;
    for (i = 0; i < n; i++) { out.push(fin(ys[i]) ? clamp(ys[i], lo, hi) : lo); idx.push(i); }
    if (!(gap > 0) || n === 1) return out;
    idx.sort(function (a, b) { return out[a] - out[b] || a - b; });
    if ((n - 1) * gap > hi - lo) { for (k = 0; k < n; k++) out[idx[k]] = lo + (hi - lo) * k / (n - 1); return out; }
    var prev = -Infinity, next = Infinity, y;
    for (k = 0; k < n; k++) { i = idx[k]; y = Math.max(out[i], prev + gap, lo); out[i] = y; prev = y; }
    for (k = n - 1; k >= 0; k--) { i = idx[k]; y = Math.min(out[i], next - gap, hi); out[i] = y; next = y; }
    return out;
  }

  /* ---------- levels (from stockInfo.call / levels / myTarget) ---------- */
  function txt(v) { return typeof v === 'string' ? v : ''; }
  function buildLevels(info, my, opts) {
    var out = [], c = info && typeof info === 'object' ? info.call : null, lv = info && typeof info === 'object' ? info.levels : null, v;
    opts = opts || {};
    c = c && typeof c === 'object' ? c : {}; lv = lv && typeof lv === 'object' ? lv : {};
    var lab = txt(c.label);
    if (lab === 'BUY' || lab === 'WAIT') {
      var lo = pos(c.entryLow), hi = pos(c.entryHigh);
      if (lo > 0 && hi > 0) out.push({ k: 'zone', t: 'Entry zone', v: Math.max(lo, hi), v2: Math.min(lo, hi) });
      if ((v = pos(c.stop)) > 0) out.push({ k: 'stop', t: 'Stop', v: v });
      if ((v = pos(c.t1)) > 0) out.push({ k: 't1', t: 'T1', v: v });
      if ((v = pos(c.t2)) > 0) out.push({ k: 't2', t: 'T2', v: v });
    }
    if ((v = pos(lv.support)) > 0) out.push({ k: 'sup', t: 'Support', v: v });
    if ((v = pos(lv.resistance)) > 0) out.push({ k: 'res', t: 'Resist.', v: v });
    if ((v = pos(lv.trailStop)) > 0) out.push({ k: 'trail', t: 'Trail stop', v: v });
    my = my && typeof my === 'object' ? my : {};
    if (opts.noMine !== true) {
      if ((v = pos(my.target)) > 0) out.push({ k: 'mytgt', t: 'Your target', v: v });
      if ((v = pos(my.stop)) > 0) out.push({ k: 'mystop', t: 'Your stop', v: v });
    }
    return out;
  }

  /* ---------- one bar's numbers for the readout ---------- */
  function at(a, i) { return Array.isArray(a) || (a && typeof a.length === 'number' && typeof a !== 'string') ? a[i] : undefined; }
  function barInfo(res, i, emaKeys) {
    res = res && typeof res === 'object' ? res : {};
    var c = pos(at(res.c, i)), o = pos(at(res.o, i)), h = pos(at(res.h, i)), l = pos(at(res.l, i)), pc = i > 0 ? pos(at(res.c, i - 1)) : 0;
    var info = { i: i, d: num(at(res.d, i)), o: o || null, h: h || null, l: l || null, c: c || null,
      chg: c > 0 && pc > 0 ? (c / pc - 1) * 100 : null, v: num(at(res.v, i)), emas: [] };
    var keys = Array.isArray(emaKeys) ? emaKeys : [];
    for (var k = 0; k < keys.length; k++) { var e = pos(at(res['ema' + keys[k]], i)); info.emas.push({ k: keys[k], v: e > 0 ? e : null }); }
    return info;
  }

  /* ---------- inertia ---------- */
  /* samples: [{x, t}] newest last (ms). Returns px/ms over the last ~100 ms, 0 when the finger rested before lifting */
  function flingVelocity(samples, now) {
    if (!Array.isArray(samples) || samples.length < 2) return 0;
    var last = samples[samples.length - 1];
    if (!last || now - last.t > 80) return 0;
    var first = last, i;
    for (i = samples.length - 2; i >= 0; i--) { if (last.t - samples[i].t > 110) break; first = samples[i]; }
    var dt = last.t - first.t;
    if (!(dt > 8)) return 0;
    var v = (last.x - first.x) / dt;
    return fin(v) ? clamp(v, -4, 4) : 0;
  }
  function decayVelocity(v, dtMs) { return v * Math.exp(-Math.max(0, dtMs) / 240); }

  var api = {
    MINB: MINB, RANGES: RANGES, DEFAULT_BARS: DEFAULT_BARS,
    clampView: clampView, viewOf: viewOf, defaultView: defaultView, rangeCount: rangeCount, rangeView: rangeView, activeRanges: activeRanges,
    zoomAt: zoomAt, panBy: panBy, pinchView: pinchView, shiftForNewData: shiftForNewData,
    xOf: xOf, idxAt: idxAt, yOf: yOf, priceAt: priceAt,
    priceRange: priceRange, levelSide: levelSide, niceTicks: niceTicks, fmtTick: fmtTick, fmtPrice: fmtPrice, fmtDate: fmtDate, dateTicks: dateTicks, dateMeta: dateMeta,
    dodge: dodge, buildLevels: buildLevels, barInfo: barInfo, flingVelocity: flingVelocity, decayVelocity: decayVelocity
  };
  root.CM = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this);
