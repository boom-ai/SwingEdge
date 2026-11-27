/* SwingEdge — chart indicators, levels, patterns and strategy markers. Pure functions, no DOM: runs in the WebView (window.CI)
   and in node (shots/chartind-test.js). Everything is computed once per data set (CI.analyze, cached per symbol) in O(n) or
   O(n·w) with small fixed windows, so the renderer only reads arrays while panning.
   Conventions: arrays are Float64Array with NaN = "no value". Bar indices refer to the chart's own arrays (res.c etc).
   Never throws on junk input. */
(function (root) {
  'use strict';
  var IST = 19800000, DAY = 86400000;

  function nanArr(n) { var a = new Float64Array(n > 0 ? n : 0); a.fill(NaN); return a; }
  function ok(x) { return x === x && x !== Infinity && x !== -Infinity; }
  function num(v) { return typeof v === 'number' && isFinite(v) ? v : (typeof v === 'string' && v.trim() !== '' && isFinite(Number(v)) ? Number(v) : NaN); }
  function isArr(a) { return Array.isArray(a) || (!!a && typeof a === 'object' && typeof a.length === 'number' && typeof a.BYTES_PER_ELEMENT === 'number'); }
  function len(a) { return isArr(a) ? a.length : 0; }

  /* ---------------------------------------------------------------- core indicators (clean inputs) */
  /* All of these take plain numeric arrays without gaps (analyze() compacts the data first). */
  function sma(src, p) {
    var n = len(src), out = nanArr(n), s = 0, i;
    if (!(p >= 1)) return out;
    for (i = 0; i < n; i++) { s += src[i]; if (i >= p) s -= src[i - p]; if (i >= p - 1) out[i] = s / p; }
    return out;
  }
  /* EMA seeded with the SMA of the first p values; NaN inputs are skipped (state carries over) */
  function ema(src, p) {
    var n = len(src), out = nanArr(n), k = 2 / (p + 1), e = NaN, cnt = 0, sum = 0, i, x;
    if (!(p >= 1)) return out;
    for (i = 0; i < n; i++) {
      x = src[i]; if (!ok(x)) continue;
      if (cnt < p) { sum += x; cnt++; if (cnt === p) { e = sum / p; out[i] = e; } }
      else { e += (x - e) * k; out[i] = e; }
    }
    return out;
  }
  /* Wilder RSI: first value at index p (average of the first p changes), then Wilder smoothing */
  function rsi(c, p) {
    var n = len(c), out = nanArr(n), ag = 0, al = 0, i, d, g, l;
    if (!(p >= 1) || n <= p) return out;
    for (i = 1; i < n; i++) {
      d = c[i] - c[i - 1]; g = d > 0 ? d : 0; l = d < 0 ? -d : 0;
      if (i <= p) { ag += g; al += l; if (i === p) { ag /= p; al /= p; out[i] = rsv(ag, al); } }
      else { ag = (ag * (p - 1) + g) / p; al = (al * (p - 1) + l) / p; out[i] = rsv(ag, al); }
    }
    return out;
  }
  function rsv(ag, al) { return al === 0 ? (ag === 0 ? 50 : 100) : 100 - 100 / (1 + ag / al); }
  function trueRange(h, l, c) {
    var n = len(c), tr = new Float64Array(n), i;
    for (i = 0; i < n; i++) tr[i] = i === 0 ? h[i] - l[i] : Math.max(h[i] - l[i], Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1]));
    return tr;
  }
  /* Wilder ATR (RMA of the true range), first value at index p-1 */
  function atr(h, l, c, p) {
    var tr = trueRange(h, l, c), n = tr.length, out = nanArr(n), s = 0, a = NaN, i;
    if (!(p >= 1)) return out;
    for (i = 0; i < n; i++) {
      if (i < p) { s += tr[i]; if (i === p - 1) { a = s / p; out[i] = a; } }
      else { a = (a * (p - 1) + tr[i]) / p; out[i] = a; }
    }
    return out;
  }
  function macd(c, f, s, sig) {
    f = f || 12; s = s || 26; sig = sig || 9;
    var n = len(c), ef = ema(c, f), es = ema(c, s), m = nanArr(n), i;
    for (i = 0; i < n; i++) if (ok(ef[i]) && ok(es[i])) m[i] = ef[i] - es[i];
    var sg = ema(m, sig), hist = nanArr(n);
    for (i = 0; i < n; i++) if (ok(m[i]) && ok(sg[i])) hist[i] = m[i] - sg[i];
    return { m: m, s: sg, h: hist };
  }
  /* Bollinger bands: SMA(p) ± k · population standard deviation */
  function bollinger(c, p, k) {
    p = p || 20; k = k || 2;
    var n = len(c), mid = sma(c, p), up = nanArr(n), lo = nanArr(n), i, j, v, d;
    for (i = p - 1; i < n; i++) {
      v = 0; for (j = i - p + 1; j <= i; j++) { d = c[j] - mid[i]; v += d * d; }
      d = Math.sqrt(v / p); up[i] = mid[i] + k * d; lo[i] = mid[i] - k * d;
    }
    return { u: up, m: mid, l: lo };
  }
  /* Supertrend (ATR p, multiplier m) — the TradingView definition. dir: 1 up (line below price), -1 down */
  function supertrend(h, l, c, p, m) {
    p = p || 10; m = m || 3;
    var n = len(c), a = atr(h, l, c, p), line = nanArr(n), dir = new Int8Array(n), fu = NaN, fl = NaN, d = 0, i;
    for (i = 0; i < n; i++) {
      if (!ok(a[i])) continue;
      var hl2 = (h[i] + l[i]) / 2, ub = hl2 + m * a[i], lb = hl2 - m * a[i];
      if (d === 0) { fu = ub; fl = lb; d = c[i] >= hl2 ? 1 : -1; }
      else {
        fu = (ub < fu || c[i - 1] > fu) ? ub : fu;
        fl = (lb > fl || c[i - 1] < fl) ? lb : fl;
        if (d === -1 && c[i] > fu) d = 1; else if (d === 1 && c[i] < fl) d = -1;
      }
      dir[i] = d; line[i] = d === 1 ? fl : fu;
    }
    return { v: line, dir: dir };
  }
  /* rolling highest high / lowest low over w bars ending at i (inclusive) — monotonic deque, O(n) */
  function rollMax(a, w, wantMin) {
    var n = len(a), out = nanArr(n), q = new Int32Array(n), h = 0, t = 0, i;
    for (i = 0; i < n; i++) {
      while (t > h && (wantMin ? a[q[t - 1]] >= a[i] : a[q[t - 1]] <= a[i])) t--;
      q[t++] = i;
      if (q[h] <= i - w) h++;
      out[i] = a[q[h]];
    }
    return out;
  }
  /* classic floor pivots + CPR from one period's high / low / close */
  function pivotsOf(H, L, C) {
    var P = (H + L + C) / 3, bc = (H + L) / 2, tc = 2 * P - bc;
    if (tc < bc) { var t = tc; tc = bc; bc = t; }
    return { p: P, r1: 2 * P - L, s1: 2 * P - H, r2: P + (H - L), s2: P - (H - L), r3: H + 2 * (P - L), s3: L - 2 * (H - P), tc: tc, bc: bc };
  }
  /* per-bar period keys from timestamps (IST): day, week (Mon-based), month. Without dates: 1 / 5 / 21 bars per period. */
  function periodKeys(d, n, kind) {
    var out = new Int32Array(n), i, t;
    for (i = 0; i < n; i++) {
      t = d ? num(d[i]) : NaN;
      if (ok(t) && t > 1e11 && t < 4.2e12) {
        var day = Math.floor((t + IST) / DAY);
        if (kind === 'D') out[i] = day;
        else if (kind === 'W') out[i] = Math.floor((day + 3) / 7);
        else { var dt = new Date(t + IST); out[i] = dt.getUTCFullYear() * 12 + dt.getUTCMonth(); }
      } else out[i] = kind === 'D' ? i : kind === 'W' ? Math.floor(i / 5) : Math.floor(i / 21);
    }
    return out;
  }
  /* for every bar: the previous completed period's H, L, C (NaN for the first period) */
  function priorPeriod(keys, h, l, c) {
    var n = keys.length, PH = nanArr(n), PL = nanArr(n), PC = nanArr(n), ch = NaN, cl = NaN, cc = NaN, ph = NaN, pl = NaN, pcc = NaN, i;
    for (i = 0; i < n; i++) {
      if (i > 0 && keys[i] !== keys[i - 1]) { ph = ch; pl = cl; pcc = cc; ch = NaN; cl = NaN; }
      if (!(ch >= h[i])) ch = h[i];
      if (!(cl <= l[i])) cl = l[i];
      cc = c[i];
      PH[i] = ph; PL[i] = pl; PC[i] = pcc;
    }
    return { h: PH, l: PL, c: PC };
  }
  function pivotSeries(keys, h, l, c) {
    var pp = priorPeriod(keys, h, l, c), n = keys.length, o = { p: nanArr(n), r1: nanArr(n), s1: nanArr(n), r2: nanArr(n), s2: nanArr(n), tc: nanArr(n), bc: nanArr(n) }, i, pv = null;
    for (i = 0; i < n; i++) {
      if (!ok(pp.h[i])) continue;
      if (!pv || keys[i] !== keys[i - 1]) pv = pivotsOf(pp.h[i], pp.l[i], pp.c[i]);    /* once per period */
      o.p[i] = pv.p; o.r1[i] = pv.r1; o.s1[i] = pv.s1; o.r2[i] = pv.r2; o.s2[i] = pv.s2; o.tc[i] = pv.tc; o.bc[i] = pv.bc;
    }
    return o;
  }
  /* session VWAP for intraday bars (resets each IST day). null when the bars are daily. */
  function vwap(d, h, l, c, v) {
    var n = len(c); if (!n || !isArr(d)) return null;
    var keys = periodKeys(d, n, 'D'), intra = false, i;
    for (i = 1; i < n; i++) if (keys[i] === keys[i - 1]) { intra = true; break; }
    if (!intra) return null;
    var out = nanArr(n), pv = 0, vv = 0;
    for (i = 0; i < n; i++) {
      if (i > 0 && keys[i] !== keys[i - 1]) { pv = 0; vv = 0; }
      var vol = v[i] > 0 ? v[i] : 0; pv += (h[i] + l[i] + c[i]) / 3 * vol; vv += vol;
      out[i] = vv > 0 ? pv / vv : c[i];
    }
    return out;
  }

  /* ---------------------------------------------------------------- swings, zigzag, levels */
  /* fractal swing points: bar i is a swing high when its high is the highest of [i-k, i+k] (strict on the left) */
  function swings(h, l, k) {
    var n = len(h), hi = [], lo = [], i, j, isH, isL;
    k = k || 4;
    for (i = k; i < n - k; i++) {
      isH = true; isL = true;
      for (j = i - k; j <= i + k && (isH || isL); j++) {
        if (j === i) continue;
        if (j < i ? h[j] >= h[i] : h[j] > h[i]) isH = false;
        if (j < i ? l[j] <= l[i] : l[j] < l[i]) isL = false;
      }
      if (isH) hi.push(i);
      if (isL) lo.push(i);
    }
    return { hi: hi, lo: lo };
  }
  /* zigzag pivots with a % reversal threshold. [{i, p, t: 1 high | -1 low, tent: last unconfirmed}] */
  function zigzag(h, l, thr) {
    var n = len(h), out = [], dir = 0, hi, lo, hiI = 0, loI = 0, ext = 0, extI = 0, i;
    if (n < 2 || !(thr > 0)) return out;
    hi = h[0]; lo = l[0];
    for (i = 1; i < n; i++) {
      if (dir === 0) {
        if (h[i] > hi) { hi = h[i]; hiI = i; }
        if (l[i] < lo) { lo = l[i]; loI = i; }
        if (hi >= lo * (1 + thr)) {
          if (hiI > loI) { out.push({ i: loI, p: lo, t: -1 }); dir = 1; ext = hi; extI = hiI; }
          else { out.push({ i: hiI, p: hi, t: 1 }); dir = -1; ext = lo; extI = loI; }
        }
      } else if (dir === 1) {
        if (h[i] >= ext) { ext = h[i]; extI = i; }
        else if (l[i] <= ext * (1 - thr)) { out.push({ i: extI, p: ext, t: 1 }); dir = -1; ext = l[i]; extI = i; }
      } else {
        if (l[i] <= ext) { ext = l[i]; extI = i; }
        else if (h[i] >= ext * (1 + thr)) { out.push({ i: extI, p: ext, t: -1 }); dir = 1; ext = h[i]; extI = i; }
      }
    }
    if (dir !== 0) out.push({ i: extI, p: ext, t: dir, tent: true });
    return out;
  }
  /* automatic support / resistance: cluster swing highs + lows of the last `look` bars within tol; score = touches + recency.
     Returns { res: [...], sup: [...] } nearest first, each {v, touches, last} — at most `top` per side. */
  function autoLevels(h, l, c, atrNow, opt) {
    opt = opt || {};
    var n = len(c), out = { res: [], sup: [] };
    if (n < 12) return out;
    var look = opt.look || 250, top = opt.top || 3, from = Math.max(0, n - look), last = c[n - 1];
    var sw = swings(h, l, opt.k || 4), pts = [], i;
    for (i = 0; i < sw.hi.length; i++) if (sw.hi[i] >= from) pts.push({ v: h[sw.hi[i]], i: sw.hi[i] });
    for (i = 0; i < sw.lo.length; i++) if (sw.lo[i] >= from) pts.push({ v: l[sw.lo[i]], i: sw.lo[i] });
    if (!pts.length) return out;
    var tol = Math.max(ok(atrNow) ? atrNow * 0.5 : 0, last * 0.006);
    pts.sort(function (a, b) { return a.v - b.v; });
    var cl = [], cur = null;
    for (i = 0; i < pts.length; i++) {
      var p = pts[i];
      if (cur && p.v - cur.sum / cur.touches <= tol) { cur.sum += p.v; cur.touches++; if (p.i > cur.last) cur.last = p.i; if (p.v > cur.hi) cur.hi = p.v; }
      else { cur = { sum: p.v, touches: 1, last: p.i, hi: p.v, lo: p.v }; cl.push(cur); }
    }
    var span = n - from;
    cl.forEach(function (x) { x.v = x.sum / x.touches; x.score = x.touches + 1.5 * (x.last - from) / span; });
    function pick(list) {
      list.sort(function (a, b) { return b.score - a.score; });
      var chosen = list.filter(function (x) { return x.touches >= 2; }).slice(0, top);
      if (chosen.length < top) chosen = chosen.concat(list.filter(function (x) { return x.touches < 2 && chosen.indexOf(x) < 0; }).sort(function (a, b) { return Math.abs(a.v - last) - Math.abs(b.v - last); }).slice(0, top - chosen.length));
      return chosen.sort(function (a, b) { return Math.abs(a.v - last) - Math.abs(b.v - last); }).map(function (x) { return { v: x.v, touches: x.touches, last: x.last }; });
    }
    var band = last * 0.35;
    out.res = pick(cl.filter(function (x) { return x.v > last && x.v - last < band; }));
    out.sup = pick(cl.filter(function (x) { return x.v < last && last - x.v < band; }));
    return out;
  }
  /* previous day / week / month high and low (the last bar is "today") */
  function prevHL(d, h, l, c) {
    var n = len(c), out = [];
    if (n < 2) return out;
    out.push({ k: 'pdh', t: 'PDH', v: h[n - 2] }, { k: 'pdl', t: 'PDL', v: l[n - 2] });
    var wk = priorPeriod(periodKeys(d, n, 'W'), h, l, c), mo = priorPeriod(periodKeys(d, n, 'M'), h, l, c);
    if (ok(wk.h[n - 1])) out.push({ k: 'pwh', t: 'PWH', v: wk.h[n - 1] }, { k: 'pwl', t: 'PWL', v: wk.l[n - 1] });
    if (ok(mo.h[n - 1])) out.push({ k: 'pmh', t: 'PMH', v: mo.h[n - 1] }, { k: 'pml', t: 'PML', v: mo.l[n - 1] });
    return out;
  }

  /* ---------------------------------------------------------------- patterns */
  /* Darvas boxes (classic): a new `look`-bar high starts a box; the top is set once 3 following days fail to exceed it; the bottom is
     the lowest low after the top, set once 3 following days hold above it. A close above the top = breakout (a new box search starts
     from there), a close below the bottom = breakdown. Returns [{s, top, bot, set, e, out: 1 | -1 | 0}] (out 0 = still open). */
  function darvas(h, l, c, look) {
    var n = len(c), out = [], st = 0, top = 0, ti = 0, bot = 0, bi = 0, box = null, i;
    look = look || 252;
    var hiPrev = rollMax(h, look, false);
    for (i = 1; i < n; i++) {
      if (st === 0) {                                  // looking for a new high
        if (i >= 10 && h[i] >= hiPrev[i - 1]) { st = 1; top = h[i]; ti = i; }
      } else if (st === 1) {                           // waiting for 3 days that do not exceed the top
        if (h[i] > top) { top = h[i]; ti = i; }
        else if (i - ti >= 3) { st = 2; bot = l[ti + 1]; bi = ti + 1; for (var j = ti + 2; j <= i; j++) if (l[j] < bot) { bot = l[j]; bi = j; } if (i - bi >= 3) { box = { s: ti, top: top, bot: bot, set: i, e: n - 1, out: 0 }; out.push(box); st = 3; } }
      } else if (st === 2) {                           // waiting for 3 days that hold above the bottom
        if (h[i] > top) { st = 1; top = h[i]; ti = i; }
        else if (l[i] < bot) { bot = l[i]; bi = i; }
        else if (i - bi >= 3) { box = { s: ti, top: top, bot: bot, set: i, e: n - 1, out: 0 }; out.push(box); st = 3; }
      } else {                                         // a box is set
        if (c[i] > box.top) { box.e = i; box.out = 1; st = 1; top = h[i]; ti = i; box = null; }
        else if (c[i] < box.bot) { box.e = i; box.out = -1; st = 0; box = null; }
      }
    }
    return out;
  }
  /* VCP-style contraction: successive pullbacks (zigzag high→low) each shallower than the one before, inside a base near the highs,
     after a prior advance. Returns [{piv:[{i,p,t}], depths:[%], pivot, pi, bo (breakout index or -1), dry (volume dried up)}] */
  function vcp(h, l, c, v, zz) {
    var out = [], j = 0, n = len(c), q;
    while (j < zz.length - 1) {
      if (zz[j].t !== 1) { j++; continue; }
      var H0 = zz[j].p, legs = [], k = j;
      while (k + 1 < zz.length && zz[k].t === 1 && zz[k + 1].t === -1) {
        var hh = zz[k].p, ll = zz[k + 1].p, d = (hh - ll) / hh, pv = legs.length ? legs[legs.length - 1] : null;
        if (d > 0.4) break;
        if (pv && (hh > H0 * 1.02 || hh < H0 * 0.85 || d > pv.d * 0.8 || ll < pv.lo.p * 0.98)) break;
        legs.push({ hi: zz[k], lo: zz[k + 1], d: d });
        k += 2;
      }
      var i0 = zz[j].i, mn = Infinity;
      for (q = Math.max(0, i0 - 100); q < i0; q++) if (l[q] < mn) mn = l[q];
      var last = legs[legs.length - 1];
      if (legs.length >= 2 && legs[0].d >= 0.08 && last.d <= 0.12 && H0 >= mn * 1.15) {
        var bo = -1;
        for (q = last.lo.i + 1; q < n && q <= last.lo.i + 30; q++) { if (c[q] > last.hi.p) { bo = q; break; } if (l[q] < last.lo.p) break; }
        var piv = []; legs.forEach(function (g) { piv.push(g.hi, g.lo); });
        var a1 = avgV(v, legs[0].hi.i, legs[0].lo.i), a2 = avgV(v, last.hi.i, last.lo.i);
        out.push({ piv: piv, depths: legs.map(function (g) { return Math.round(g.d * 1000) / 10; }), pivot: last.hi.p, pi: last.lo.i, bo: bo, dry: a1 > 0 && a2 < a1 });
        j = k;
      } else j++;
    }
    return out;
  }
  function avgV(v, a, b) { var s = 0, k = 0; for (var i = Math.max(0, a); i <= b && i < v.length; i++) { s += v[i]; k++; } return k ? s / k : 0; }
  /* auto trendlines: through the last two swing highs (resistance) and the last two swing lows (support) of a zigzag,
     extended to the right. A line is dropped once price has closed through it by more than 1 ATR (it no longer matters). */
  function trendlines(c, zz, atrNow) {
    var out = [], n = len(c);
    [1, -1].forEach(function (t) {
      var pts = zz.filter(function (p) { return p.t === t; });
      if (pts.length < 2) return;
      var a = pts[pts.length - 2], b = pts[pts.length - 1];
      if (!(b.i > a.i)) return;
      var sl = (b.p - a.p) / (b.i - a.i), last = a.p + sl * (n - 1 - a.i), tol = ok(atrNow) ? atrNow : Math.abs(last) * 0.02;
      if (t === 1 && c[n - 1] > last + tol) return;
      if (t === -1 && c[n - 1] < last - tol) return;
      out.push({ kind: t === 1 ? 'res' : 'sup', a: { i: a.i, p: a.p }, b: { i: b.i, p: b.p }, slope: sl });
    });
    return out;
  }
  /* bull / bear flags: a sharp pole (zigzag leg ≥ minPole in ≤ 25 bars), then 3–20 bars that retrace ≤ 50% without making a new
     extreme, then a close beyond the flag. Returns [{dir, p0, p1, s, e, hi, lo, bo (index or -1 = forming)}] */
  function flags(h, l, c, zz, atrPct) {
    var out = [], n = len(c), minPole = Math.max(0.12, 5 * (atrPct || 0.02)), j;
    for (j = 0; j + 1 < zz.length; j++) {
      var a = zz[j], b = zz[j + 1];
      if (b.i - a.i > 25 || b.i - a.i < 2) continue;
      var up = a.t === -1, mv = up ? (b.p - a.p) / a.p : (a.p - b.p) / a.p;
      if (!(mv >= minPole)) continue;
      var pole = Math.abs(b.p - a.p), fh = -Infinity, fl = Infinity, bo = -1, k, valid = true, end = Math.min(n - 1, b.i + 20);
      for (k = b.i + 1; k <= end; k++) {
        if (k - b.i >= 4 && (up ? c[k] > fh : c[k] < fl)) { bo = k; break; }
        if (up ? h[k] > b.p : l[k] < b.p) { valid = false; break; }
        if (h[k] > fh) fh = h[k];
        if (l[k] < fl) fl = l[k];
        if (up ? b.p - fl > pole * 0.5 : fh - b.p > pole * 0.5) { valid = false; break; }
      }
      if (!valid || k - b.i < 4) continue;
      if (bo < 0 && end < n - 1) continue;                        // ran out of time without a breakout
      var e = bo >= 0 ? bo - 1 : n - 1;
      if (up ? b.p - fl < pole * 0.08 : fh - b.p < pole * 0.08) continue;   // no real pullback
      out.push({ dir: up ? 1 : -1, p0: a, p1: b, s: b.i + 1, e: e, hi: fh, lo: fl, bo: bo });
    }
    return out;
  }
  /* double top / bottom on zigzag pivots: two peaks within tol, a trough between, confirmed by a close through the neckline */
  function doubleTB(h, l, c, zz) {
    var out = [], n = len(c), j;
    for (j = 0; j + 2 < zz.length; j++) {
      var A = zz[j], B = zz[j + 1], C = zz[j + 2], top = A.t === 1;
      if (C.i - A.i < 10 || C.i - A.i > 130) continue;
      if (Math.abs(C.p - A.p) / A.p > 0.03) continue;
      var depth = top ? (Math.min(A.p, C.p) - B.p) / Math.min(A.p, C.p) : (B.p - Math.max(A.p, C.p)) / B.p;
      if (depth < 0.05) continue;
      var pre = Math.max(0, A.i - 40), q, ext = top ? -Infinity : Infinity;
      for (q = pre; q < A.i; q++) ext = top ? Math.max(ext, h[q]) : Math.min(ext, l[q]);
      if (top ? ext > A.p * 1.005 : ext < A.p * 0.995) continue;        // the first peak must be a local extreme
      var conf = -1, bad = false, lim = Math.min(n - 1, C.i + 40), peak = top ? Math.max(A.p, C.p) : Math.min(A.p, C.p);
      for (q = C.i + 1; q <= lim; q++) {
        if (top ? c[q] < B.p : c[q] > B.p) { conf = q; break; }
        if (top ? h[q] > peak * 1.01 : l[q] < peak * 0.99) { bad = true; break; }
      }
      if (bad) continue;
      if (conf < 0 && lim < n - 1) continue;                       // never confirmed and long gone
      out.push({ dir: top ? -1 : 1, a: A, b: B, c: C, neck: B.p, conf: conf });
    }
    return out;
  }
  /* cup with handle (approximate): rim A, rounded bottom 12–40% deep over ≥ 35 bars, right rim back near A, a shallow handle in the
     upper half, breakout above the right rim. Labelled "possible" in the UI. */
  function cupHandle(h, l, c, zz) {
    var out = [], n = len(c), j, k, q, lastEnd = -1;
    for (j = 0; j < zz.length; j++) {
      var A = zz[j]; if (A.t !== 1 || A.i <= lastEnd) continue;
      for (k = j + 2; k < zz.length; k++) {
        var R = zz[k]; if (R.t !== 1) continue;
        var w = R.i - A.i; if (w > 300) break;
        if (R.p > A.p * 1.05) break;
        if (w < 35 || R.p < A.p * 0.9) continue;
        var bi = A.i, bl = Infinity; for (q = A.i; q <= R.i; q++) if (l[q] < bl) { bl = l[q]; bi = q; }
        var depth = (A.p - bl) / A.p; if (depth < 0.12 || depth > 0.4) continue;
        var pos = (bi - A.i) / w; if (pos < 0.25 || pos > 0.75) continue;
        var lowZone = bl + (A.p - bl) * 0.2, inLow = 0; for (q = A.i; q <= R.i; q++) if (c[q] < lowZone) inLow++;
        if (inLow / w < 0.24) continue;                          // a V (≈20% of the time near the bottom), not a rounded cup
        var hl = Infinity, hi2 = R.i, bo = -1, mid = bl + (A.p - bl) / 2, okH = true;
        for (q = R.i + 1; q < n && q <= R.i + 25; q++) {
          if (c[q] > R.p && q - R.i >= 5) { bo = q; break; }
          if (h[q] > R.p * 1.01) { okH = false; break; }
          if (l[q] < hl) { hl = l[q]; hi2 = q; }
          if (hl < mid || (R.p - hl) / R.p > Math.max(0.12, depth / 3)) { okH = false; break; }
        }
        if (!okH || !(hl < Infinity) || q - R.i < 5) continue;
        if (bo < 0 && q < n) continue;                          // handle went on too long
        out.push({ a: A, b: { i: bi, p: bl }, r: R, hl: { i: hi2, p: hl }, depth: Math.round(depth * 1000) / 10, bo: bo });
        lastEnd = bo >= 0 ? bo : n; break;
      }
    }
    return out;
  }
  function gaps(o, h, l, c, atrA) {
    var out = [], n = len(c), i, j;
    for (i = 1; i < n; i++) {
      var a = ok(atrA[i - 1]) ? atrA[i - 1] : 0, min = Math.max(c[i - 1] * 0.005, a * 0.25);
      if (l[i] - h[i - 1] >= min) {
        var lo = h[i - 1], f = -1; for (j = i + 1; j < n; j++) if (l[j] <= lo) { f = j; break; }
        out.push({ i: i, dir: 1, lo: lo, hi: l[i], fill: f });
      } else if (l[i - 1] - h[i] >= min) {
        var hi = l[i - 1], f2 = -1; for (j = i + 1; j < n; j++) if (h[j] >= hi) { f2 = j; break; }
        out.push({ i: i, dir: -1, lo: h[i], hi: hi, fill: f2 });
      }
    }
    return out;
  }

  /* ---------------------------------------------------------------- marker kinds (legend) */
  var KINDS = {
    dvU: { t: 'Darvas breakout', g: 'up', c: '#31C48D' }, dvD: { t: 'Darvas breakdown', g: 'dn', c: '#F2555A' },
    exU: { t: 'EMA 10 crossed above 20', g: 'up', c: '#E5B04C', l: 'X' }, exD: { t: 'EMA 10 crossed below 20', g: 'dn', c: '#E5B04C', l: 'X' },
    stU: { t: 'Supertrend turned up', g: 'up', c: '#31C48D', l: 'ST' }, stD: { t: 'Supertrend turned down', g: 'dn', c: '#F2555A', l: 'ST' },
    r2: { t: 'RSI(2) < 10 dip in uptrend', g: 'dot', c: '#4B8BF5', l: 'R2' }, r2x: { t: 'RSI(2) dip exit (close > 5-day avg)', g: 'ring', c: '#4B8BF5' },
    b52: { t: '52-week-high breakout on volume', g: 'star', c: '#31C48D', l: '52W' },
    nr7: { t: 'NR7 (narrowest range of 7)', g: 'tiny', c: '#A9AEB5', l: '7' }, ib: { t: 'Inside bar', g: 'tiny', c: '#A9AEB5', l: 'ib' },
    vcpB: { t: 'VCP pivot breakout', g: 'up', c: '#31C48D', l: 'VCP' }, flU: { t: 'Bull-flag breakout', g: 'up', c: '#31C48D', l: 'F' }, flD: { t: 'Bear-flag breakdown', g: 'dn', c: '#F2555A', l: 'F' },
    dt: { t: 'Double top confirmed', g: 'dn', c: '#F2555A', l: 'DT' }, db: { t: 'Double bottom confirmed', g: 'up', c: '#31C48D', l: 'DB' },
    cupB: { t: 'Cup-with-handle breakout (possible)', g: 'up', c: '#31C48D', l: 'C&H' }
  };
  /* which toggle shows which marker kinds */
  var MARK_OF = { darvas: ['dvU', 'dvD'], sdv: ['dvU'], sx: ['exU', 'exD'], sst: ['stU', 'stD'], sr2: ['r2', 'r2x'], b52: ['b52'], nr7: ['nr7', 'ib'],
    vcp: ['vcpB'], flag: ['flU', 'flD'], dtb: ['dt', 'db'], cup: ['cupB'] };

  /* ---------------------------------------------------------------- options (menu model) */
  var GROUPS = [
    { t: 'Lower panes (max 2)', items: [['rsi', 'RSI 14'], ['rsi2', 'RSI 2'], ['macd', 'MACD'], ['atr', 'ATR 14']] },
    { t: 'Overlays', items: [['bb', 'Bollinger 20,2'], ['st', 'Supertrend 10,3'], ['piv', 'Pivots (week)'], ['cpr', 'CPR (day)'], ['hl52', '52W high/low'], ['vavg', 'Volume avg 20'], ['vwap', 'VWAP']] },
    { t: 'Levels', items: [['sr', 'Auto support/resist.'], ['tl', 'Auto trendlines'], ['base', 'Baseline (change since first bar)'], ['prev', 'Prev D/W/M high-low']] },
    { t: 'Patterns', items: [['darvas', 'Darvas boxes'], ['vcp', 'VCP contraction'], ['flag', 'Bull/bear flags'], ['nr7', 'NR7 / inside bars'], ['dtb', 'Double top/bottom'], ['cup', 'Cup & handle'], ['b52', '52W breakout + vol'], ['gap', 'Gaps']] },
    { t: 'Strategy signals', items: [['sx', 'EMA 10/20 cross'], ['sst', 'Supertrend flip'], ['sr2', 'RSI(2) dip (Connors)'], ['sdv', 'Darvas breakouts']] }
  ];
  var PANES = ['rsi', 'rsi2', 'macd', 'atr'];
  var DEFAULTS = { rsi: true, sr: true, tl: true, base: true, vavg: true, darvas: true, flag: true, vcp: true, dtb: true, cup: true, b52: true, st: true };
  var LS_KEY = 'se_cf_ind3', LS_INLINE = 'se_cx_ind3';   /* bumped when the defaults change */
  function ls() { try { return root.localStorage || null; } catch (e) { return null; } }
  function loadOpts() {
    var o = {}, k, s = ls(), j = null;
    for (k in DEFAULTS) o[k] = DEFAULTS[k];
    o._panes = ['rsi'];
    try { j = s ? JSON.parse(s.getItem(LS_KEY) || 'null') : null; } catch (e) { j = null; }
    if (j && typeof j === 'object') {
      for (k in j) if (k !== '_panes' && Object.prototype.hasOwnProperty.call(j, k)) o[k] = j[k] === true;
      o._panes = Array.isArray(j._panes) ? j._panes.filter(function (p) { return PANES.indexOf(p) >= 0 && o[p]; }).slice(-2) : PANES.filter(function (p) { return o[p]; }).slice(0, 2);
    }
    PANES.forEach(function (p) { if (o._panes.indexOf(p) < 0) o[p] = false; });
    return o;
  }
  function saveOpts(o) { var s = ls(); if (!s) return; try { s.setItem(LS_KEY, JSON.stringify(o)); } catch (e) { /* ignore */ } }
  /* flip one option; lower panes keep at most 2 (the oldest one turns off). Returns the new options object. */
  function toggleOpt(o, id) {
    o = o || loadOpts(); o._panes = Array.isArray(o._panes) ? o._panes : [];
    var on = !o[id], i;
    o[id] = on;
    if (PANES.indexOf(id) >= 0) {
      o._panes = (o._panes || []).filter(function (p) { return p !== id; });
      if (on) { o._panes.push(id); while (o._panes.length > 2) { o[o._panes.shift()] = false; } }
    }
    for (i = 0; i < PANES.length; i++) if (o._panes.indexOf(PANES[i]) < 0) o[PANES[i]] = false;
    return o;
  }
  function inlineOpts() {
    var s = ls(), j = null, o = { rsi: true, lv: true, dv: true };
    try { j = s ? JSON.parse(s.getItem(LS_INLINE) || 'null') : null; } catch (e) { j = null; }
    if (j && typeof j === 'object') { o.rsi = j.rsi !== false; o.lv = j.lv !== false; o.dv = j.dv !== false; }
    return o;
  }
  function toggleInline(id) {
    var o = inlineOpts(); if (!(id in o)) return o;
    o[id] = !o[id]; var s = ls(); if (s) { try { s.setItem(LS_INLINE, JSON.stringify(o)); } catch (e) { /* ignore */ } }
    return o;
  }

  /* ---------------------------------------------------------------- analyze: everything for one data set */
  var cache = [], CACHE_MAX = 8;
  function keyOf(res, n) {
    var d = res.d, c = res.c;
    return String(res.sym || '') + '|' + n + '|' + (isArr(d) && d.length ? d[d.length - 1] : '') + '|' + (isArr(c) && c.length ? c[c.length - 1] : '') + '|' + (isArr(c) && c.length ? c[0] + '|' + c[c.length >> 1] : '') + '|' + (isArr(res.h) && res.h.length ? res.h[res.h.length - 1] : '');
  }
  function analyze(res) {
    res = res && typeof res === 'object' ? res : {};
    var n = Math.min(len(res.c), len(res.o), len(res.h), len(res.l));
    var key = keyOf(res, n), i;
    for (i = 0; i < cache.length; i++) if (cache[i].key === key) { var hit = cache.splice(i, 1)[0]; cache.unshift(hit); return hit.a; }
    var t0 = Date.now(), a = compute(res, n);
    a.ms = Date.now() - t0;
    cache.unshift({ key: key, res: res, a: a }); if (cache.length > CACHE_MAX) cache.pop();
    return a;
  }
  /* the cached analysis for this data set, or null (lets the chart draw first and analyse on the next tick) */
  function cached(res) {
    res = res && typeof res === 'object' ? res : {};
    var key = keyOf(res, Math.min(len(res.c), len(res.o), len(res.h), len(res.l)));
    for (var i = 0; i < cache.length; i++) if (cache[i].key === key) return cache[i].a;
    return null;
  }
  function compute(res, n) {
    /* compact the valid bars (close > 0) so every indicator runs on gap-free arrays; results are scattered back by index map */
    var vi = [], i;
    for (i = 0; i < n; i++) { var cc = num(res.c[i]); if (cc > 0) vi.push(i); }
    var m = vi.length, O = new Float64Array(m), H = new Float64Array(m), L = new Float64Array(m), C = new Float64Array(m), V = new Float64Array(m), D = new Array(m);
    var rv = isArr(res.v), rd = isArr(res.d);
    for (i = 0; i < m; i++) {
      var j = vi[i], c = num(res.c[j]), o = num(res.o[j]), h = num(res.h[j]), l = num(res.l[j]), v = rv ? num(res.v[j]) : 0;
      o = o > 0 ? o : c; h = h > 0 ? h : Math.max(o, c); l = l > 0 ? l : Math.min(o, c);
      h = Math.max(h, o, c); l = Math.min(l, o, c);
      O[i] = o; H[i] = h; L[i] = l; C[i] = c; V[i] = v > 0 ? v : 0; D[i] = rd ? res.d[j] : null;
    }
    var A = { n: n, m: m, ok: m >= 5, idx: vi, marks: [], kinds: {} };
    function sc(x) { var out = nanArr(n); if (!x) return out; for (var k = 0; k < m; k++) out[vi[k]] = x[k]; return out; }
    function ix(k) { return vi[k]; }
    if (m < 5) { A.rsi = A.rsi2 = A.atr = A.vavg = A.hi52 = A.lo52 = nanArr(n); A.macd = { m: nanArr(n), s: nanArr(n), h: nanArr(n) }; A.bb = { u: nanArr(n), m: nanArr(n), l: nanArr(n) }; A.st = { v: nanArr(n), dir: new Int8Array(n) };
      A.piv = { p: nanArr(n), r1: nanArr(n), s1: nanArr(n), r2: nanArr(n), s2: nanArr(n) }; A.cpr = { tc: nanArr(n), bc: nanArr(n), p: nanArr(n) }; A.vwap = null;
      A.sr = { res: [], sup: [] }; A.prev = []; A.darvas = []; A.tl = []; A.vcp = []; A.flags = []; A.dtb = []; A.cups = []; A.gaps = []; return A; }

    var r14 = rsi(C, 14), r2 = rsi(C, 2), at = atr(H, L, C, 14), mc = macd(C, 12, 26, 9), bb = bollinger(C, 20, 2), stt = supertrend(H, L, C, 10, 3);
    var va = sma(V, 20), h52 = rollMax(H, 252, false), l52 = rollMax(L, 252, true), s200 = sma(C, 200), s5 = sma(C, 5);
    A.rsi = sc(r14); A.rsi2 = sc(r2); A.atr = sc(at); A.vavg = sc(va); A.hi52 = sc(h52); A.lo52 = sc(l52);
    A.macd = { m: sc(mc.m), s: sc(mc.s), h: sc(mc.h) }; A.bb = { u: sc(bb.u), m: sc(bb.m), l: sc(bb.l) };
    var stDir = new Int8Array(n); for (i = 0; i < m; i++) stDir[vi[i]] = stt.dir[i];
    A.st = { v: sc(stt.v), dir: stDir };
    var wk = pivotSeries(periodKeys(D, m, 'W'), H, L, C), dy = pivotSeries(periodKeys(D, m, 'D'), H, L, C);
    A.piv = { p: sc(wk.p), r1: sc(wk.r1), s1: sc(wk.s1), r2: sc(wk.r2), s2: sc(wk.s2) };
    A.cpr = { tc: sc(dy.tc), bc: sc(dy.bc), p: sc(dy.p) };
    var vw = rd ? vwap(D, H, L, C, V) : null; A.vwap = vw ? sc(vw) : null;
    A.next = pivotsOf(H[m - 1], L[m - 1], C[m - 1]);                    // next session's pivots / CPR from the last bar
    A.last52 = { h: h52[m - 1], l: l52[m - 1], full: m >= 252 };
    var atrNow = at[m - 1];
    A.sr = autoLevels(H, L, C, atrNow, { look: 250, top: 3 });
    A.prev = prevHL(D, H, L, C);

    /* zigzags: a fine one (VCP, flags) and a main one (double tops, cups), thresholds from the median ATR% */
    var ap = [], ap0 = Math.max(14, m - 250);
    for (i = ap0; i < m; i++) if (ok(at[i])) ap.push(at[i] / C[i]);
    ap.sort(function (x, y) { return x - y; });
    var atrPct = ap.length ? ap[ap.length >> 1] : 0.02;
    var zzF = zigzag(H, L, Math.min(0.06, Math.max(0.025, atrPct * 1.3))), zzM = zigzag(H, L, Math.min(0.12, Math.max(0.05, atrPct * 2.5)));
    function mapP(p) { return { i: ix(p.i), p: p.p, t: p.t }; }

    A.tl = trendlines(C, zzM, atrNow).map(function (t) { var a = mapP(t.a), b = mapP(t.b); return { kind: t.kind, a: a, b: b, slope: b.i > a.i ? (b.p - a.p) / (b.i - a.i) : 0 }; });
    var dv = darvas(H, L, C, Math.min(252, Math.max(20, m - 1)));
    A.darvas = dv.map(function (b) { return { s: ix(b.s), set: ix(b.set), e: ix(b.e), top: b.top, bot: b.bot, out: b.out }; });
    var vc = vcp(H, L, C, V, zzF);
    A.vcp = vc.map(function (x) { return { piv: x.piv.map(mapP), depths: x.depths, pivot: x.pivot, pi: ix(x.pi), bo: x.bo >= 0 ? ix(x.bo) : -1, dry: x.dry }; });
    var fl = flags(H, L, C, zzF, atrPct);
    A.flags = fl.map(function (f) { return { dir: f.dir, p0: mapP(f.p0), p1: mapP(f.p1), s: ix(f.s), e: ix(f.e), hi: f.hi, lo: f.lo, bo: f.bo >= 0 ? ix(f.bo) : -1 }; });
    var tb = doubleTB(H, L, C, zzM);
    A.dtb = tb.map(function (x) { return { dir: x.dir, a: mapP(x.a), b: mapP(x.b), c: mapP(x.c), neck: x.neck, conf: x.conf >= 0 ? ix(x.conf) : -1 }; });
    var cu = cupHandle(H, L, C, zzM);
    A.cups = cu.map(function (x) { return { a: mapP(x.a), b: mapP(x.b), r: mapP(x.r), hl: mapP(x.hl), depth: x.depth, bo: x.bo >= 0 ? ix(x.bo) : -1 }; });
    A.gaps = gaps(O, H, L, C, at).map(function (g) { return { i: ix(g.i), dir: g.dir, lo: g.lo, hi: g.hi, fill: g.fill >= 0 ? ix(g.fill) : -1 }; });

    /* markers */
    var mk = A.marks;
    function add(k, ci) { mk.push({ i: ix(ci), k: k }); A.kinds[k] = (A.kinds[k] || 0) + 1; }
    dv.forEach(function (b) { if (b.out === 1) add('dvU', b.e); else if (b.out === -1) add('dvD', b.e); });
    vc.forEach(function (x) { if (x.bo >= 0) add('vcpB', x.bo); });
    fl.forEach(function (f) { if (f.bo >= 0) add(f.dir > 0 ? 'flU' : 'flD', f.bo); });
    tb.forEach(function (x) { if (x.conf >= 0) add(x.dir < 0 ? 'dt' : 'db', x.conf); });
    cu.forEach(function (x) { if (x.bo >= 0) add('cupB', x.bo); });
    var e10 = emaFrom(res.ema10, vi, C, 10), e20 = emaFrom(res.ema20, vi, C, 20);
    var lastB52 = -99, inDip = false;
    var s200x = s200; if (m < 200 && isArr(res.ema200)) { s200x = new Float64Array(m); for (i = 0; i < m; i++) { var ev = num(res.ema200[vi[i]]); s200x[i] = ev > 0 ? ev : NaN; } }
    for (i = 1; i < m; i++) {
      if (ok(e10[i]) && ok(e20[i]) && ok(e10[i - 1]) && ok(e20[i - 1])) {
        if (e10[i - 1] <= e20[i - 1] && e10[i] > e20[i]) add('exU', i); else if (e10[i - 1] >= e20[i - 1] && e10[i] < e20[i]) add('exD', i);
      }
      if (stt.dir[i] && stt.dir[i - 1] && stt.dir[i] !== stt.dir[i - 1]) add(stt.dir[i] > 0 ? 'stU' : 'stD', i);
      var up = ok(s200x[i]) && C[i] > s200x[i];
      if (!inDip && up && ok(r2[i]) && r2[i] < 10) { add('r2', i); inDip = true; }
      else if (inDip && ok(s5[i]) && C[i] > s5[i]) { add('r2x', i); inDip = false; }
      if (i >= 252 && C[i] > h52[i - 1] && ok(va[i - 1]) && V[i] >= 1.5 * va[i - 1] && i - lastB52 > 10) { add('b52', i); lastB52 = i; }
      if (i >= 6) { var rg = H[i] - L[i], mn = Infinity; for (var q = i - 6; q < i; q++) mn = Math.min(mn, H[q] - L[q]); if (rg > 0 && rg < mn) add('nr7', i); }
      if (H[i] <= H[i - 1] && L[i] >= L[i - 1] && (H[i] < H[i - 1] || L[i] > L[i - 1])) add('ib', i);
    }
    mk.sort(function (x, y) { return x.i - y.i; });
    A.atrPct = atrPct;
    return A;
  }
  /* the server's EMA column when it is usable, else computed here */
  function emaFrom(col, vi, C, p) {
    var m = vi.length, out = new Float64Array(m), good = 0, i;
    if (isArr(col)) for (i = 0; i < m; i++) { var x = num(col[vi[i]]); out[i] = x > 0 ? x : NaN; if (x > 0) good++; }
    return good >= m * 0.5 ? out : ema(C, p);
  }

  /* ---------------------------------------------------------------- helpers for renderers */
  /* first index in sorted marks with i >= x (binary search) */
  function lowerBound(marks, x) { var lo = 0, hi = marks.length; while (lo < hi) { var mid = (lo + hi) >> 1; if (marks[mid].i < x) lo = mid + 1; else hi = mid; } return lo; }
  /* which marker kinds are visible for the options */
  function kindsOn(o) { var out = {}, k; o = o || {}; for (k in MARK_OF) if (o[k]) MARK_OF[k].forEach(function (x) { out[x] = true; }); return out; }
  function f2(v) { return ok(v) ? (Math.abs(v) >= 1000 ? v.toFixed(0) : Math.abs(v) >= 100 ? v.toFixed(1) : v.toFixed(2)) : '—'; }
  /* readout rows (label, value, colour) for bar i under options o */
  function readout(A, i, o) {
    var rows = [], k;
    if (!A || !A.ok || !(i >= 0 && i < A.n)) return rows;
    o = o || {};
    function r(t, v, c) { rows.push({ t: t, v: v, c: c || '' }); }
    if (o.rsi) r('RSI 14', ok(A.rsi[i]) ? A.rsi[i].toFixed(1) : '—', '#4B8BF5');
    if (o.rsi2) r('RSI 2', ok(A.rsi2[i]) ? A.rsi2[i].toFixed(1) : '—', '#8DB5FA');
    if (o.macd) r('MACD', f2(A.macd.m[i]) + ' / ' + f2(A.macd.s[i]), '#4B8BF5');
    if (o.atr) r('ATR 14', f2(A.atr[i]), '#A9AEB5');
    if (o.bb) r('BB', f2(A.bb.l[i]) + '–' + f2(A.bb.u[i]), '#8DB5FA');
    if (o.st) r('Supertrend', f2(A.st.v[i]) + (A.st.dir[i] > 0 ? ' ▲' : A.st.dir[i] < 0 ? ' ▼' : ''), A.st.dir[i] > 0 ? '#31C48D' : '#F2555A');
    if (o.piv) r('Pivot (wk)', f2(A.piv.p[i]), '#A9AEB5');
    if (o.cpr) r('CPR', f2(A.cpr.bc[i]) + '–' + f2(A.cpr.tc[i]), '#4B8BF5');
    if (o.hl52) r('52W H/L', f2(A.hi52[i]) + ' / ' + f2(A.lo52[i]), '#A9AEB5');
    if (o.vavg && ok(A.vavg[i])) r('Vol avg 20', volS(A.vavg[i]), '#A9AEB5');
    if (o.vwap && A.vwap) r('VWAP', f2(A.vwap[i]), '#E5B04C');
    var on = kindsOn(o), j = lowerBound(A.marks, i);
    for (; j < A.marks.length && A.marks[j].i === i; j++) { k = A.marks[j].k; if (on[k]) rows.push({ t: KINDS[k].t, v: '', c: KINDS[k].c, mark: true }); }
    if (o.darvas) A.darvas.forEach(function (b) { if (i >= b.s && i <= b.e && b.set <= i) r('Darvas box', f2(b.bot) + '–' + f2(b.top), '#4B8BF5'); });
    return rows;
  }
  function volS(v) { return v >= 1e7 ? (v / 1e7).toFixed(2) + ' Cr' : v >= 1e5 ? (v / 1e5).toFixed(2) + ' L' : String(Math.round(v)); }

  /* ---------------------------------------------------------------- inline (SVG) helpers for render.js chartSvg */
  function esc(s) { return String(s).replace(/[&<>"']/g, function (ch) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]; }); }
  /* Darvas boxes as SVG rects. X(i) bar centre, Y(p) price → y, half = half a bar's width */
  function svgBoxes(A, X, Y, half) {
    var s = '';
    if (!A || !A.ok) return s;
    A.darvas.forEach(function (b) {
      var x0 = X(b.s) - half, x1 = X(b.e) + half, y0 = Y(b.top), y1 = Y(b.bot);
      if (!(x1 > x0) || !ok(y0) || !ok(y1)) return;
      s += '<rect class="dvx" x="' + x0.toFixed(1) + '" y="' + Math.min(y0, y1).toFixed(1) + '" width="' + (x1 - x0).toFixed(1) + '" height="' + Math.max(1, Math.abs(y1 - y0)).toFixed(1) + '"/>';
      if (b.out) {
        var ax = X(b.e), ay = b.out > 0 ? Y(b.top) - 2 : Y(b.bot) + 2, tip = b.out > 0 ? ay - 6 : ay + 6;
        s += '<path class="' + (b.out > 0 ? 'dvu' : 'dvd') + '" d="M' + (ax - 3.5).toFixed(1) + ' ' + ay.toFixed(1) + 'L' + (ax + 3.5).toFixed(1) + ' ' + ay.toFixed(1) + 'L' + ax.toFixed(1) + ' ' + tip.toFixed(1) + 'Z"/>';
      }
    });
    (A.flags || []).forEach(function (f) {
      var fe = f.bo >= 0 ? f.bo : f.e, x0 = X(f.s) - half, x1 = X(fe) + half, y0 = Y(f.hi), y1 = Y(f.lo);
      if (!(x1 > x0) || !ok(y0) || !ok(y1)) return;
      s += '<path class="flp ' + (f.dir > 0 ? 'u' : 'd') + '" d="M' + X(f.p0.i).toFixed(1) + ' ' + Y(f.p0.p).toFixed(1) + 'L' + X(f.p1.i).toFixed(1) + ' ' + Y(f.p1.p).toFixed(1) + '"/>' +
        '<rect class="flg ' + (f.dir > 0 ? 'u' : 'd') + '" x="' + x0.toFixed(1) + '" y="' + Math.min(y0, y1).toFixed(1) + '" width="' + (x1 - x0).toFixed(1) + '" height="' + Math.max(1, Math.abs(y1 - y0)).toFixed(1) + '"/>';
    });
    (A.tl || []).forEach(function (t) {
      var xe = A.n - 1 + 3, ye = t.a.p + t.slope * (xe - t.a.i), ya = Y(t.a.p), yb = Y(ye);
      if (!ok(ya) || !ok(yb)) return;
      s += '<path class="tl ' + t.kind + '" d="M' + X(t.a.i).toFixed(1) + ' ' + ya.toFixed(1) + 'L' + X(xe).toFixed(1) + ' ' + yb.toFixed(1) + '"/>';
    });
    return s;
  }
  /* RSI mini pane: returns {svg} drawn between top and top+h, x from L to L+pw */
  function svgRsi(A, X, L, pw, top, h, n0) {
    if (!A || !A.ok) return '';
    var y = function (v) { return top + (100 - v) / 100 * h; }, pts = [], i, s = '';
    s += '<rect class="rsib" x="' + L + '" y="' + y(70).toFixed(1) + '" width="' + pw + '" height="' + (y(30) - y(70)).toFixed(1) + '"/>';
    s += '<line class="rsig" x1="' + L + '" x2="' + (L + pw) + '" y1="' + y(70).toFixed(1) + '" y2="' + y(70).toFixed(1) + '"/><line class="rsig" x1="' + L + '" x2="' + (L + pw) + '" y1="' + y(30).toFixed(1) + '" y2="' + y(30).toFixed(1) + '"/>';
    for (i = n0 || 0; i < A.n; i++) if (ok(A.rsi[i])) pts.push(X(i).toFixed(1) + ',' + y(A.rsi[i]).toFixed(1));
    if (pts.length > 1) s += '<polyline class="rsil" points="' + pts.join(' ') + '"/>';
    var last = A.rsi[A.n - 1];
    s += '<text class="ax" x="' + (L + pw + 4) + '" y="' + (y(70) + 3).toFixed(1) + '">70</text><text class="ax" x="' + (L + pw + 4) + '" y="' + (y(30) + 3).toFixed(1) + '">30</text>' +
      '<text class="rsit" x="' + (L + 2) + '" y="' + (top + 8) + '">RSI 14' + (ok(last) ? ' ' + esc(last.toFixed(1)) : '') + '</text>';
    return s;
  }
  /* auto levels as chartSvg level rows: [{k:'sup'|'res', t, v}] */
  function inlineLevels(A) {
    var out = [];
    if (!A || !A.ok) return out;
    A.sr.res.forEach(function (x) { out.push({ k: 'res', t: 'R ×' + x.touches, v: Math.round(x.v * 100) / 100 }); });
    A.sr.sup.forEach(function (x) { out.push({ k: 'sup', t: 'S ×' + x.touches, v: Math.round(x.v * 100) / 100 }); });
    return out;
  }

  var api = {
    sma: sma, ema: ema, rsi: rsi, atr: atr, trueRange: trueRange, macd: macd, bollinger: bollinger, supertrend: supertrend, rollMax: rollMax,
    pivotsOf: pivotsOf, periodKeys: periodKeys, priorPeriod: priorPeriod, pivotSeries: pivotSeries, vwap: vwap,
    swings: swings, zigzag: zigzag, autoLevels: autoLevels, prevHL: prevHL,
    darvas: darvas, trendlines: trendlines, vcp: vcp, flags: flags, doubleTB: doubleTB, cupHandle: cupHandle, gaps: gaps,
    analyze: analyze, cached: cached, readout: readout, lowerBound: lowerBound, kindsOn: kindsOn,
    KINDS: KINDS, MARK_OF: MARK_OF, GROUPS: GROUPS, PANES: PANES, DEFAULTS: DEFAULTS,
    loadOpts: loadOpts, saveOpts: saveOpts, toggleOpt: toggleOpt, inlineOpts: inlineOpts, toggleInline: toggleInline,
    svgBoxes: svgBoxes, svgRsi: svgRsi, inlineLevels: inlineLevels
  };
  root.CI = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : this);
