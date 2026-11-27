/* SwingEdge — full-screen interactive chart: <canvas> renderer + gesture controller.
   Pure maths lives in chartmath.js (CM); markup and the readout panel HTML come from render.js (SE).
   Gestures (canvas has touch-action:none, so there is no page scroll to fight with):
     one finger drag = pan (with inertia) · two fingers = pinch zoom around the midpoint · hold ~250 ms then drag = crosshair
     tap (while a crosshair shows) = hide it · double tap = reset to the default window
     mouse: wheel = zoom at the cursor (shift/horizontal wheel = pan) · drag = pan · hover or hold+drag = crosshair · double click = reset
   Drawing happens only inside requestAnimationFrame and only when something changed. */
(function (root) {
  'use strict';
  var CM = root.CM, SE = root.SE, CI = root.CI;
  if (!CM) return;

  var COL = { bg: '#000000', grid: '#161616', axis: '#8A8A8A', text: '#ECECEC', up: '#1FD286', dn: '#FF4B4B', line: '#1FD286', cross: '#FFA028', panel: 'rgba(10,10,10,.96)' };
  var EMA_COL = { 10: '#E5B04C', 20: '#F08A5D', 50: '#6BA6FF', 100: '#4DC4D8', 200: '#B48CF2' };
  var EMA_KEYS = [200, 100, 50, 20, 10];                      // draw order: slowest first
  var LV = {
    zone: { c: '#6BB3FF', d: [] }, stop: { c: '#FF6F6F', d: [5, 3] }, t1: { c: '#3FD68F', d: [5, 3] }, t2: { c: '#8DEBBC', d: [2, 3] },
    sup: { c: '#52C98F', d: [1, 4] }, res: { c: '#F2B441', d: [1, 4] }, trail: { c: '#FF9F9F', d: [1, 4] },
    mytgt: { c: '#FF7AC6', d: [8, 3, 2, 3] }, mystop: { c: '#FF7AC6', d: [8, 3, 2, 3] }
  };
  var NODASH = [];
  var HOLD_MS = 250, SLOP = 8, DBL_MS = 320, DBL_PX = 32;
  var FONT = '"Roboto Condensed", "sans-serif-condensed", "Arial Narrow", Roboto, system-ui, sans-serif';
  /* indicator palette: one accent (blue) for indicator lines, green/red only for direction */
  var IC = { acc: '#4B8BF5', acc2: '#8DB5FA', accA: 'rgba(75,139,245,', amb: '#E5B04C', gray: '#A9AEB5', sup: '#31C48D', res: '#F2555A', grayA: 'rgba(169,174,181,' };
  var AUTO_LV = { sup: { c: '#31C48D', d: [6, 4] }, res: { c: '#F2555A', d: [6, 4] }, prev: { c: '#A9AEB5', d: [2, 3] }, hl52: { c: '#A9AEB5', d: [1, 3] } };
  var PANE_NAME = { rsi: 'RSI 14', rsi2: 'RSI 2', macd: 'MACD 12,26,9', atr: 'ATR 14' };

  function now() { return (root.performance && root.performance.now) ? root.performance.now() : Date.now(); }
  function toF(a, n) {
    var o = new Float64Array(n), i, v;
    if (!a || typeof a.length !== 'number') return o;
    for (i = 0; i < n && i < a.length; i++) { v = a[i]; o[i] = typeof v === 'number' && isFinite(v) ? v : (typeof v === 'string' && v.trim() !== '' && isFinite(Number(v)) ? Number(v) : 0); }
    return o;
  }
  function dist(a, b) { var dx = a.clientX - b.clientX, dy = a.clientY - b.clientY; return Math.sqrt(dx * dx + dy * dy); }

  function create(stage, cfg) {
    cfg = cfg || {};
    var canvas = stage.querySelector('canvas'), ro = stage.querySelector('.cfro');
    if (!canvas || !canvas.getContext) return null;
    var ctx = canvas.getContext('2d');
    var st = {
      w: 0, h: 0, dpr: 1, n: 0, res: null, dRaw: null, meta: null, O: null, H: null, L: null, C: null, V: null, E: {}, hasVol: false,
      view: { s: 0, c: 0 }, opts: { emas: { 10: true, 20: true, 50: true, 100: false, 200: true }, levels: true, vol: true },
      lv: [], lvVals: [], series: [], cross: -1, crossX: 0, crossY: null, frames: 0, dirty: false, raf: 0, fling: 0, flingT: 0, destroyed: false,
      pr: { min: 0, max: 1 }, geo: { l: 6, t: 8, r: 0, b: 0, w: 0, pb: 0, vt: 0, vb: 0, panes: [], bot: 0 }, lastNotified: '', labels: [], crossReadIdx: -2,
      yz: 1, yo: 0, ind: null, io: CI ? CI.loadOpts() : {}, autoLv: [], autoVals: [], autoLabels: [], visMarks: 0, drawMs: 0, menu: null
    };
    var g = { mode: '', t0: 0, x0: 0, y0: 0, lastX: 0, hold: 0, samples: [], rect: null, d0: 0, v0: null, f0: 0, tapT: 0, tapX: 0, tapY: 0, mouse: false, hover: false };

    /* ---------- data and options ---------- */
    function setData(res, o) {
      o = o || {};
      res = res && typeof res === 'object' ? res : null;
      var n = 0;
      if (res) {
        var lens = [res.c, res.o, res.h, res.l].map(function (a) { return a && typeof a.length === 'number' ? a.length : 0; });
        n = Math.min.apply(null, lens);
      }
      var oldN = st.n, oldD = st.dRaw, oldView = st.view;
      st.res = res; st.n = n;
      st.O = toF(res && res.o, n); st.H = toF(res && res.h, n); st.L = toF(res && res.l, n); st.C = toF(res && res.c, n); st.V = toF(res && res.v, n);
      st.hasVol = false; for (var i = 0; i < n; i++) if (st.V[i] > 0) { st.hasVol = true; break; }
      st.E = {}; [10, 20, 50, 100, 200].forEach(function (k) { st.E[k] = toF(res && res['ema' + k], n); });
      st.dRaw = res && res.d && typeof res.d.length === 'number' ? res.d : null;
      st.meta = st.dRaw ? CM.dateMeta(st.dRaw, n) : null;
      if (o.keepView && oldN > 0 && n > 0) {
        var shift = CM.shiftForNewData(oldD, st.dRaw);
        st.view = CM.clampView(oldView.s + shift, oldView.c, n);
      } else st.view = CM.defaultView(n);
      st.cross = -1; st.crossY = null; st.fling = 0; hideReadout();
      st.ind = null; st.indTok = (st.indTok || 0) + 1;
      if (CI && n >= 5) {
        st.ind = CI.cached(res);
        if (!st.ind) {                                    /* first time for this data: draw the candles now, indicators on the next tick */
          var tok = st.indTok;
          setTimeout(function () { if (st.destroyed || tok !== st.indTok) return; analyzeNow(res); afterInd(); }, 0);
        }
      }
      rebuildSeries(); rebuildAuto(); paintMenu(); invalidate();
    }
    function analyzeNow(res) {
      try { st.ind = CI.analyze(res); } catch (e) { st.ind = null; try { console.error('SwingEdge indicators', e && e.stack || e); } catch (e2) { /* ignore */ } }
    }
    function rebuildSeries() {
      st.series = [];
      for (var k = 0; k < EMA_KEYS.length; k++) if (st.opts.emas[EMA_KEYS[k]]) st.series.push(st.E[EMA_KEYS[k]]);
      var A = st.ind, io = st.io;
      if (A && A.ok) {                                   /* overlays that should stay in view when the price axis auto-fits */
        if (io.bb) st.series.push(A.bb.u, A.bb.l);
        if (io.st) st.series.push(A.st.v);
        if (io.vwap && A.vwap) st.series.push(A.vwap);
      }
    }
    /* automatic levels (S/R clusters, previous period H-L, 52-week H/L) as label rows */
    function rebuildAuto() {
      var A = st.ind, io = st.io, out = [], vals = [];
      if (A && A.ok) {
        if (io.sr) {
          A.sr.res.forEach(function (x) { out.push({ k: 'res', cap: 'R ×' + x.touches, t: 'Resistance ' + CM.fmtPrice(x.v) + ' (' + x.touches + ' touches)', v: x.v, c: AUTO_LV.res.c, d: AUTO_LV.res.d }); });
          A.sr.sup.forEach(function (x) { out.push({ k: 'sup', cap: 'S ×' + x.touches, t: 'Support ' + CM.fmtPrice(x.v) + ' (' + x.touches + ' touches)', v: x.v, c: AUTO_LV.sup.c, d: AUTO_LV.sup.d }); });
        }
        if (io.prev) A.prev.forEach(function (x) { out.push({ k: x.k, cap: x.t, t: x.t + ' ' + CM.fmtPrice(x.v), v: x.v, c: AUTO_LV.prev.c, d: AUTO_LV.prev.d }); });
        if (io.hl52 && A.last52.h > 0) {
          var w = A.last52.full ? '52W' : 'Hist.';
          out.push({ k: 'h52', cap: w + ' high', t: w + ' high ' + CM.fmtPrice(A.last52.h), v: A.last52.h, c: AUTO_LV.hl52.c, d: AUTO_LV.hl52.d }, { k: 'l52', cap: w + ' low', t: w + ' low ' + CM.fmtPrice(A.last52.l), v: A.last52.l, c: AUTO_LV.hl52.c, d: AUTO_LV.hl52.d });
        }
      }
      out.forEach(function (x) { if (x.v > 0) vals.push(x.v); });
      st.autoLv = out.filter(function (x) { return x.v > 0; }); st.autoVals = vals;
    }
    function setInd(o) {
      if (!CI) return;
      o = o || {};
      for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k) && st.io[k] !== !!o[k]) st.io = CI.toggleOpt(st.io, k);
      afterInd();
    }
    function toggleInd(id) { if (!CI) return; st.io = CI.toggleOpt(st.io, id); CI.saveOpts(st.io); afterInd(); }
    function afterInd() { rebuildSeries(); rebuildAuto(); layout(); paintMenu(); if (st.cross >= 0) refreshReadout(true); invalidate(); }
    function panesOn() {
      var A = st.ind, out = [];
      if (!A || !A.ok || !CI) return out;
      (st.io._panes || []).forEach(function (p) { if (st.io[p] && out.length < 2) out.push(p); });
      return out;
    }
    function setOpts(o) {
      o = o || {};
      if (o.emas) { for (var k in o.emas) if (Object.prototype.hasOwnProperty.call(o.emas, k)) st.opts.emas[k] = !!o.emas[k]; }
      if (o.levels !== undefined) st.opts.levels = !!o.levels;
      if (o.vol !== undefined) st.opts.vol = !!o.vol;
      rebuildSeries();
      if (st.cross >= 0) refreshReadout(true);
      invalidate();
    }
    function setLevels(list) {
      st.lv = []; st.lvVals = [];
      (Array.isArray(list) ? list : []).forEach(function (x) {
        if (!x || !(x.v > 0)) return;
        var sty = LV[x.k] || LV.res, txt = (x.k === 'zone') ? x.t : x.t + ' ' + CM.fmtPrice(x.v);
        st.lv.push({ k: x.k, t: txt, v: x.v, v2: x.v2 > 0 ? x.v2 : 0, c: sty.c, d: sty.d });
        st.lvVals.push(x.v); if (x.v2 > 0) st.lvVals.push(x.v2);
      });
      invalidate();
    }
    function setView(v) { st.view = CM.viewOf(v, st.n); st.fling = 0; invalidate(); }
    function resetView() { st.view = CM.defaultView(st.n); st.yz = 1; st.yo = 0; st.fling = 0; clearCross(); invalidate(); }
    function setRange(key) { st.view = CM.rangeView(key, st.n); st.yz = 1; st.yo = 0; st.fling = 0; invalidate(); }

    /* ---------- geometry ---------- */
    function layout() {
      var G = st.geo, W = st.w, H = st.h, RA = 56, BA = 21, TP = 8, GAP = 8;
      G.l = 6; G.r = W - RA; G.w = Math.max(10, G.r - G.l); G.t = TP; G.bot = H - BA;
      var avail = H - TP - BA, panes = panesOn(), showVol = st.opts.vol && st.hasVol;
      var pH = panes.length ? Math.max(44, Math.round(avail * (panes.length === 2 ? 0.15 : 0.18))) : 0;
      var vh = showVol ? Math.max(32, Math.round(avail * (panes.length ? 0.12 : 0.17))) : 0;
      while (panes.length && avail - panes.length * (pH + GAP) - (showVol ? vh + GAP : 0) < 140) panes.pop();   /* short screens: price first */
      var bottom = H - BA, k;
      G.panes = [];
      for (k = panes.length - 1; k >= 0; k--) { G.panes.unshift({ id: panes[k], t: bottom - pH, b: bottom }); bottom -= pH + GAP; }
      if (showVol) { G.vb = bottom; G.vt = bottom - vh; G.pb = G.vt - GAP; } else { G.vb = G.vt = bottom; G.pb = panes.length ? bottom : H - BA; }
      G.vol = showVol; G.ph = Math.max(20, G.pb - G.t);
    }
    function resize() {
      if (st.destroyed) return;
      var w = stage.clientWidth || 0, h = stage.clientHeight || 0, dpr = Math.min(3, root.devicePixelRatio || 1);
      if (w === st.w && h === st.h && dpr === st.dpr && canvas.width) return;
      st.w = w; st.h = h; st.dpr = dpr;
      canvas.width = Math.max(1, Math.round(w * dpr)); canvas.height = Math.max(1, Math.round(h * dpr));
      layout(); invalidate();
    }
    function xOf(i) { return CM.xOf(i, st.view, st.geo.l, st.geo.w); }
    function yOfPrice(p) { return CM.yOf(p, st.pr.min, st.pr.max, st.geo.t, st.geo.ph); }

    /* ---------- drawing ---------- */
    function invalidate() {
      st.dirty = true;
      if (!st.raf && !st.destroyed) st.raf = root.requestAnimationFrame(frame);
    }
    function frame(ts) {
      st.raf = 0;
      if (st.destroyed) return;
      if (st.fling) stepFling(ts);
      if (!st.dirty) return;
      st.dirty = false;
      draw();
      st.frames++;
      notifyView();
      if (st.fling) invalidate();
    }
    function flush() { if (st.raf) { root.cancelAnimationFrame(st.raf); st.raf = 0; } st.dirty = true; draw(); st.frames++; notifyView(); }
    function notifyView() {
      if (!cfg.onView) return;
      var key = st.view.s.toFixed(2) + '/' + st.view.c.toFixed(2) + '/' + st.n;
      if (key !== st.lastNotified) { st.lastNotified = key; try { cfg.onView(state()); } catch (e) { /* ignore */ } }
    }
    function stepFling(ts) {
      var t = typeof ts === 'number' ? ts : now(), dt = st.flingT ? Math.min(48, t - st.flingT) : 16;
      st.flingT = t;
      var before = st.view.s;
      st.view = CM.panBy(st.view, st.fling * dt, st.geo.w, st.n);
      st.fling = CM.decayVelocity(st.fling, dt);
      if (Math.abs(st.fling) < 0.02 || Math.abs(st.view.s - before) < 1e-6) st.fling = 0;
      st.dirty = true;
    }
    function text(s, x, y, color, font, align) { ctx.fillStyle = color; ctx.font = font; ctx.textAlign = align || 'left'; ctx.fillText(s, x, y); }

    function draw() {
      var W = st.w, H = st.h, G = st.geo, n = st.n, v = st.view, i, k, tDraw = now();
      layout();
      ctx.setTransform(st.dpr, 0, 0, st.dpr, 0, 0);
      ctx.fillStyle = COL.bg; ctx.fillRect(0, 0, W, H);
      st.labels = [];
      if (n < 5 || W < 60 || H < 60 || !(v.c > 0)) return;
      var i0 = Math.max(0, Math.floor(v.s)), i1 = Math.min(n - 1, Math.ceil(v.s + v.c) - 1);
      var pr = CM.scaleRange(CM.priceRange(st.H, st.L, i0, i1, st.series, st.opts.levels ? st.lvVals : null), st.yz, st.yo);   /* auto levels never stretch the axis; drag the price axis to scale it by hand */
      st.pr = pr;
      var mn = pr.min, mx = pr.max, step = G.w / v.c, ph = G.ph, pt = G.t, pb = G.pb;
      var Y = function (p) { return pt + (mx - p) / (mx - mn) * ph; };

      /* grid + price axis */
      var tk = CM.niceTicks(mn, mx, Math.max(3, Math.round(ph / 62)));
      ctx.lineWidth = 1; ctx.strokeStyle = COL.grid; ctx.beginPath();
      for (i = 0; i < tk.ticks.length; i++) { var gy = Math.round(Y(tk.ticks[i])) + 0.5; ctx.moveTo(G.l, gy); ctx.lineTo(G.r, gy); }
      ctx.stroke();
      var axFont = '600 11px ' + FONT;
      for (i = 0; i < tk.ticks.length; i++) text(CM.fmtTick(tk.ticks[i], tk.step), G.r + 6, Y(tk.ticks[i]) + 4, COL.axis, axFont, 'left');
      /* date axis */
      var dt = st.dRaw ? CM.dateTicks(st.dRaw, v, n, G.w, 46, st.meta) : [];
      ctx.beginPath(); ctx.strokeStyle = COL.grid;
      for (i = 0; i < dt.length; i++) { var dx = Math.round(CM.xOf(dt[i].i, v, G.l, G.w)) + 0.5; ctx.moveTo(dx, pt); ctx.lineTo(dx, G.bot); }
      ctx.stroke();
      var tg0 = -1, tg1 = -1;      /* the crosshair's date tag covers the axis: skip the labels under it */
      if (st.cross >= 0 && st.cross < n) {
        ctx.font = '700 11px ' + FONT;
        var tdw = ctx.measureText(st.dRaw ? CM.fmtDate(st.dRaw[st.cross]) : '#' + (st.cross + 1)).width + 14, tcx = xOf(st.cross);
        tg0 = Math.max(G.l, Math.min(G.r - tdw, tcx - tdw / 2)) - 4; tg1 = tg0 + tdw + 8;
      }
      for (i = 0; i < dt.length; i++) {
        var tx = CM.xOf(dt[i].i, v, G.l, G.w);
        if (tg1 > 0) { var lx = Math.max(G.l + 14, Math.min(G.r - 14, tx)), lw = ctx.measureText(dt[i].text).width / 2; if (lx + lw > tg0 && lx - lw < tg1) continue; }
        text(dt[i].text, Math.max(G.l + 14, Math.min(G.r - 14, tx)), H - 6, dt[i].major ? COL.text : COL.axis, (dt[i].major ? '700 ' : '600 ') + '11px ' + FONT, 'center');
      }

      /* everything in the price pane is clipped to it */
      ctx.save(); ctx.beginPath(); ctx.rect(G.l, pt, G.w, ph); ctx.clip();

      var A = st.ind && st.ind.ok ? st.ind : null, io = st.io, half = Math.max(1, Math.min(16, step * 0.7)) / 2;
      var X = function (j) { return G.l + (j + 0.5 - v.s) * step; };
      if (A) drawUnder(A, io, i0, i1, X, Y, half);

      /* entry zone band, then level lines */
      var lvOn = st.opts.levels, li, L;
      if (lvOn) {
        for (li = 0; li < st.lv.length; li++) {
          L = st.lv[li];
          if (L.k === 'zone' && L.v2 > 0) {
            var za = Y(L.v), zb = Y(L.v2);
            ctx.fillStyle = 'rgba(107,179,255,.14)'; ctx.fillRect(G.l, Math.min(za, zb), G.w, Math.max(1.5, Math.abs(zb - za)));
            ctx.strokeStyle = 'rgba(107,179,255,.55)'; ctx.lineWidth = 1; ctx.setLineDash(NODASH);
            ctx.beginPath(); ctx.moveTo(G.l, Math.round(za) + 0.5); ctx.lineTo(G.r, Math.round(za) + 0.5); ctx.moveTo(G.l, Math.round(zb) + 0.5); ctx.lineTo(G.r, Math.round(zb) + 0.5); ctx.stroke();
          } else if (L.v >= mn && L.v <= mx) {
            ctx.strokeStyle = L.c; ctx.lineWidth = (L.k === 'mytgt' || L.k === 'mystop') ? 1.6 : 1.2; ctx.setLineDash(L.d);
            var ly = Math.round(Y(L.v)) + 0.5;
            ctx.beginPath(); ctx.moveTo(G.l, ly); ctx.lineTo(G.r, ly); ctx.stroke();
          }
        }
        ctx.setLineDash(NODASH);
      }
      if (st.autoLv.length) {
        ctx.lineWidth = 1;
        for (li = 0; li < st.autoLv.length; li++) {
          L = st.autoLv[li]; if (!(L.v >= mn && L.v <= mx)) continue;
          ctx.strokeStyle = L.c; ctx.globalAlpha = 0.75; ctx.setLineDash(L.d);
          var aly = Math.round(Y(L.v)) + 0.5; ctx.beginPath(); ctx.moveTo(G.l, aly); ctx.lineTo(G.r, aly); ctx.stroke();
        }
        ctx.globalAlpha = 1; ctx.setLineDash(NODASH);
      }

      /* EMAs (fastest drawn last = on top) */
      ctx.lineWidth = 1.4; ctx.lineJoin = 'round';
      for (k = 0; k < EMA_KEYS.length; k++) {
        var ek = EMA_KEYS[k]; if (!st.opts.emas[ek]) continue;
        var ea = st.E[ek], open = false;
        ctx.strokeStyle = EMA_COL[ek]; ctx.beginPath();
        for (i = Math.max(0, i0 - 1); i <= Math.min(n - 1, i1 + 1); i++) {
          if (ea[i] > 0) { var ex = G.l + (i + 0.5 - v.s) * step, ey = Y(ea[i]); if (open) ctx.lineTo(ex, ey); else { ctx.moveTo(ex, ey); open = true; } }
          else open = false;
        }
        ctx.stroke();
      }
      if (A) drawOverlays(A, io, i0, i1, X, Y);

      /* candles when few bars are visible, a close line otherwise */
      if (v.c <= 180) {
        var bw = Math.max(1, Math.min(16, step * 0.7)), hw = bw / 2, pass, x, o, c, h, l, up;
        for (pass = 0; pass < 2; pass++) {
          var isUp = pass === 0;
          ctx.strokeStyle = ctx.fillStyle = isUp ? COL.up : COL.dn; ctx.lineWidth = 1;
          ctx.beginPath();
          for (i = i0; i <= i1; i++) {
            c = st.C[i]; if (!(c > 0)) continue;
            o = st.O[i] > 0 ? st.O[i] : c; up = c >= o; if (up !== isUp) continue;
            h = st.H[i] > 0 ? st.H[i] : Math.max(o, c); l = st.L[i] > 0 ? st.L[i] : Math.min(o, c);
            x = Math.floor(G.l + (i + 0.5 - v.s) * step) + 0.5; ctx.moveTo(x, Y(h)); ctx.lineTo(x, Y(l));
          }
          ctx.stroke(); ctx.beginPath();
          for (i = i0; i <= i1; i++) {
            c = st.C[i]; if (!(c > 0)) continue;
            o = st.O[i] > 0 ? st.O[i] : c; up = c >= o; if (up !== isUp) continue;
            x = G.l + (i + 0.5 - v.s) * step; var y1 = Y(Math.max(o, c)), y2 = Y(Math.min(o, c));
            ctx.rect(Math.round(x - hw), y1, Math.max(1, Math.round(bw)), Math.max(1, y2 - y1));
          }
          ctx.fill();
        }
      } else {
        var c0 = 0, c1 = 0; for (i = Math.max(0, i0); i <= Math.min(n - 1, i1); i++) if (st.C[i] > 0) { if (!c0) c0 = st.C[i]; c1 = st.C[i]; }
        var lineCol = c1 >= c0 ? COL.up : COL.dn;
        ctx.strokeStyle = lineCol; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.beginPath();
        var op = false, fx = 0, lx = 0;
        for (i = Math.max(0, i0 - 1); i <= Math.min(n - 1, i1 + 1); i++) {
          if (st.C[i] > 0) { lx = G.l + (i + 0.5 - v.s) * step; var cy = Y(st.C[i]); if (op) ctx.lineTo(lx, cy); else { ctx.moveTo(lx, cy); fx = lx; op = true; } }
          else op = false;
        }
        ctx.stroke();
        if (lx > fx) {
          ctx.lineTo(lx, pb); ctx.lineTo(fx, pb); ctx.closePath();
          var gr = ctx.createLinearGradient(0, G.t || 0, 0, pb);
          gr.addColorStop(0, c1 >= c0 ? 'rgba(49,196,141,.28)' : 'rgba(242,85,90,.26)'); gr.addColorStop(1, 'rgba(15,16,18,0)');
          ctx.fillStyle = gr; ctx.fill();
          if (c0 > 0) { var py = Y(c0); ctx.save(); ctx.strokeStyle = 'rgba(154,160,166,.55)'; ctx.lineWidth = 1; ctx.setLineDash([2, 6]); ctx.beginPath(); ctx.moveTo(G.l, Math.round(py) + 0.5); ctx.lineTo(G.r, Math.round(py) + 0.5); ctx.stroke(); ctx.restore(); }
        }
      }
      st.visMarks = 0;
      if (A) { drawPatterns(A, io, i0, i1, X, Y, half, step); drawMarks(A, io, i0, i1, X, Y, half, step); }
      ctx.restore();

      /* volume strip */
      if (G.vol) {
        var maxV = 0; for (i = i0; i <= i1; i++) if (st.V[i] > maxV) maxV = st.V[i];
        var vh = G.vb - G.vt, vw = Math.max(1, Math.min(14, step * 0.7));
        ctx.strokeStyle = COL.grid; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(G.l, Math.round(G.vb) + 0.5); ctx.lineTo(G.r, Math.round(G.vb) + 0.5); ctx.stroke();
        for (var vp = 0; vp < 2; vp++) {
          ctx.fillStyle = vp === 0 ? 'rgba(49,196,141,.45)' : 'rgba(242,85,90,.45)'; ctx.beginPath();
          for (i = i0; i <= i1; i++) {
            if (!(st.V[i] > 0) || !(maxV > 0)) continue;
            var upv = st.C[i] >= (st.O[i] > 0 ? st.O[i] : st.C[i]); if (upv !== (vp === 0)) continue;
            var bh = Math.max(1, st.V[i] / maxV * vh);
            ctx.rect(Math.round(G.l + (i + 0.5 - v.s) * step - vw / 2), G.vb - bh, Math.max(1, Math.round(vw)), bh);
          }
          ctx.fill();
        }
        if (A && io.vavg && maxV > 0) {
          ctx.strokeStyle = IC.amb; ctx.lineWidth = 1.2; ctx.beginPath(); var vo = false;
          for (i = Math.max(0, i0 - 1); i <= Math.min(n - 1, i1 + 1); i++) {
            var va = A.vavg[i]; if (!(va > 0)) { vo = false; continue; }
            var vy = G.vb - Math.min(1, va / maxV) * vh; if (vo) ctx.lineTo(X(i), vy); else { ctx.moveTo(X(i), vy); vo = true; }
          }
          ctx.stroke();
        }
        text(SE && SE.volTxt ? 'Vol ' + SE.volTxt(maxV) : 'Vol', G.r + 6, G.vt + 10, COL.axis, '600 10px ' + FONT, 'left');
        if (A && io.vavg) text('avg 20', G.r + 6, G.vt + 22, IC.amb, '600 10px ' + FONT, 'left');
      }
      if (A && G.panes.length) drawPanes(A, i0, i1, X, step);

      st.autoLabels = [];
      if (st.autoLv.length) drawAutoTags(Y, mn, mx);

      /* last close tag on the price axis */
      var lastC = st.C[n - 1];
      if (lastC > 0 && lastC >= mn && lastC <= mx) {
        var lcy = Y(lastC), lup = lastC >= (st.n > 1 && st.C[n - 2] > 0 ? st.C[n - 2] : lastC);
        ctx.strokeStyle = 'rgba(169,182,196,.5)'; ctx.lineWidth = 1; ctx.setLineDash([1, 3]); ctx.beginPath(); ctx.moveTo(G.l, Math.round(lcy) + 0.5); ctx.lineTo(G.r, Math.round(lcy) + 0.5); ctx.stroke(); ctx.setLineDash(NODASH);
        ctx.fillStyle = lup ? COL.up : COL.dn; roundRect(G.r + 1, lcy - 9, 53, 18, 4); ctx.fill();
        text(CM.fmtPrice(lastC), G.r + 5, lcy + 4, '#06110C', '700 11px ' + FONT, 'left');
      }

      /* level labels on the right edge, stacked so they never overlap */
      if (lvOn && st.lv.length) drawLevelLabels(Y, mn, mx);
      if (A) drawLegend(A, io, i0, i1);

      /* crosshair */
      if (st.cross >= 0 && st.cross < n) drawCross(Y);
      var dms = now() - tDraw; st.drawMs = st.drawMs ? st.drawMs * 0.8 + dms * 0.2 : dms; st.lastDrawMs = dms;
    }

    /* ---------- indicator layers ---------- */
    function strokeSeries(a, i0, i1, X, Y, color, width, dash) {
      var i, open = false, n = st.n;
      ctx.strokeStyle = color; ctx.lineWidth = width || 1.2; ctx.setLineDash(dash || NODASH); ctx.beginPath();
      for (i = Math.max(0, i0 - 1); i <= Math.min(n - 1, i1 + 1); i++) {
        var y = a[i];
        if (y === y) { var py = Y(y); if (open) ctx.lineTo(X(i), py); else { ctx.moveTo(X(i), py); open = true; } } else open = false;
      }
      ctx.stroke(); ctx.setLineDash(NODASH);
    }
    /* step lines (per-period values): horizontal runs over each bar's width, breaking when the value changes */
    function stepSeries(a, i0, i1, X, Y, half, color, width, dash) {
      var i, n = st.n, prev = NaN;
      ctx.strokeStyle = color; ctx.lineWidth = width || 1; ctx.setLineDash(dash || NODASH); ctx.beginPath();
      for (i = Math.max(0, i0); i <= Math.min(n - 1, i1); i++) {
        var y = a[i]; if (y !== y) { prev = NaN; continue; }
        var py = Math.round(Y(y)) + 0.5;
        if (y !== prev) ctx.moveTo(X(i) - half, py);
        ctx.lineTo(X(i) + half, py); prev = y;
      }
      ctx.stroke(); ctx.setLineDash(NODASH);
    }
    function drawUnder(A, io, i0, i1, X, Y, half) {
      var i, G = st.geo, bw = half * 2;
      /* gaps: shaded until filled */
      if (io.gap) for (i = 0; i < A.gaps.length; i++) {
        var gp = A.gaps[i], ge = gp.fill >= 0 ? gp.fill : st.n - 1;
        if (ge < i0 || gp.i > i1) continue;
        var gx0 = X(gp.i - 1) - half, gx1 = gp.fill >= 0 ? X(ge) + half : G.r, gy0 = Y(gp.hi), gy1 = Y(gp.lo);
        ctx.fillStyle = gp.dir > 0 ? 'rgba(49,196,141,.13)' : 'rgba(242,85,90,.13)'; ctx.fillRect(gx0, gy0, gx1 - gx0, Math.max(1, gy1 - gy0));
      }
      /* Bollinger fill */
      if (io.bb) {
        ctx.fillStyle = IC.accA + '.07)'; ctx.beginPath(); var j0 = Math.max(0, i0 - 1), j1 = Math.min(st.n - 1, i1 + 1), first = -1, last = -1;
        for (i = j0; i <= j1; i++) if (A.bb.u[i] === A.bb.u[i]) { if (first < 0) { ctx.moveTo(X(i), Y(A.bb.u[i])); first = i; } else ctx.lineTo(X(i), Y(A.bb.u[i])); last = i; }
        if (first >= 0) { for (i = last; i >= first; i--) if (A.bb.l[i] === A.bb.l[i]) ctx.lineTo(X(i), Y(A.bb.l[i])); ctx.closePath(); ctx.fill(); }
      }
      /* CPR band (from the previous day) */
      if (io.cpr) {
        ctx.fillStyle = IC.accA + '.22)';
        for (i = i0; i <= i1; i++) { var tc = A.cpr.tc[i], bc = A.cpr.bc[i]; if (tc !== tc) continue; var cy0 = Y(tc), cy1 = Y(bc); ctx.fillRect(X(i) - half, cy0, bw, Math.max(1, cy1 - cy0)); }
      }
      /* Darvas boxes */
      if (io.darvas) for (i = 0; i < A.darvas.length; i++) {
        var b = A.darvas[i]; if (b.e < i0 || b.s > i1) continue;
        var bx0 = X(b.s) - half, bx1 = b.out ? X(b.e) + half : G.r, by0 = Y(b.top), by1 = Y(b.bot);
        ctx.fillStyle = IC.accA + '.10)'; ctx.fillRect(bx0, by0, bx1 - bx0, by1 - by0);
        ctx.strokeStyle = IC.accA + '.75)'; ctx.lineWidth = 1; ctx.strokeRect(Math.round(bx0) + 0.5, Math.round(by0) + 0.5, Math.round(bx1 - bx0), Math.round(by1 - by0));
      }
    }
    function drawOverlays(A, io, i0, i1, X, Y) {
      var i, half = Math.max(1, Math.min(16, st.geo.w / st.view.c * 0.7)) / 2, G = st.geo;
      /* baseline: close of the first visible bar; area above green, below red, with the change since then on the right */
      if (io.base && st.C && i1 > i0) {
        var bv = st.C[i0];
        if (bv > 0) {
          var by = Y(bv);
          for (i = i0; i <= i1; i++) { var cv = st.C[i]; if (!(cv > 0)) continue; var cy = Y(cv); ctx.fillStyle = cv >= bv ? 'rgba(49,196,141,.07)' : 'rgba(242,85,90,.07)'; ctx.fillRect(X(i) - half - 0.5, Math.min(by, cy), half * 2 + 1, Math.abs(cy - by)); }
          ctx.save(); ctx.setLineDash([2, 3]); ctx.strokeStyle = IC.grayA + '.6)'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(G.l, Math.round(by) + 0.5); ctx.lineTo(G.r, Math.round(by) + 0.5); ctx.stroke(); ctx.restore();
          var lc = st.C[i1], pc = lc > 0 ? (lc / bv - 1) * 100 : NaN;
          if (pc === pc) label('Base ' + (pc >= 0 ? '+' : '') + pc.toFixed(1) + '%', G.l + 4, by - 9, pc >= 0 ? COL.up : COL.dn, 'left');
        }
      }
      /* auto trendlines through the last two swing highs / lows, extended into the empty space after the last bar */
      if (io.tl && A.tl) for (i = 0; i < A.tl.length; i++) {
        var t = A.tl[i], xe = st.view.s + st.view.c, ye = t.a.p + t.slope * (xe - t.a.i);
        if (t.a.i > i1) continue;
        ctx.strokeStyle = t.kind === 'res' ? 'rgba(242,85,90,.85)' : 'rgba(49,196,141,.85)'; ctx.lineWidth = 1.3;
        ctx.beginPath(); ctx.moveTo(X(t.a.i), Y(t.a.p)); ctx.lineTo(X(xe), Y(ye)); ctx.stroke();
        ctx.fillStyle = ctx.strokeStyle; [t.a, t.b].forEach(function (q) { ctx.beginPath(); ctx.arc(X(q.i), Y(q.p), 2.5, 0, 6.283); ctx.fill(); });
      }
      if (io.piv) {
        stepSeries(A.piv.r2, i0, i1, X, Y, half, 'rgba(242,85,90,.45)', 1, [3, 3]); stepSeries(A.piv.r1, i0, i1, X, Y, half, 'rgba(242,85,90,.7)', 1, [3, 3]);
        stepSeries(A.piv.s2, i0, i1, X, Y, half, 'rgba(49,196,141,.45)', 1, [3, 3]); stepSeries(A.piv.s1, i0, i1, X, Y, half, 'rgba(49,196,141,.7)', 1, [3, 3]);
        stepSeries(A.piv.p, i0, i1, X, Y, half, IC.grayA + '.85)', 1.2);
        var li = Math.min(st.n - 1, i1);
        if (A.piv.p[li] === A.piv.p[li]) {
          ctx.font = '600 9.5px ' + FONT; ctx.textAlign = 'right';
          [['R2', A.piv.r2], ['R1', A.piv.r1], ['P', A.piv.p], ['S1', A.piv.s1], ['S2', A.piv.s2]].forEach(function (q) { ctx.fillStyle = q[0] === 'P' ? IC.gray : q[0].charAt(0) === 'R' ? IC.res : IC.sup; ctx.fillText(q[0], X(li) - half - 2, Y(q[1][li]) - 2); });
        }
      }
      if (io.hl52) { stepSeries(A.hi52, i0, i1, X, Y, half, IC.grayA + '.55)', 1, [1, 3]); stepSeries(A.lo52, i0, i1, X, Y, half, IC.grayA + '.55)', 1, [1, 3]); }
      if (io.bb) { strokeSeries(A.bb.u, i0, i1, X, Y, IC.acc2, 1); strokeSeries(A.bb.l, i0, i1, X, Y, IC.acc2, 1); strokeSeries(A.bb.m, i0, i1, X, Y, IC.accA + '.6)', 1, [4, 3]); }
      if (io.vwap && A.vwap) strokeSeries(A.vwap, i0, i1, X, Y, IC.amb, 1.4);
      if (io.st) {                                         /* green under price in an uptrend, red above in a downtrend; no line across a flip */
        for (var pass = 0; pass < 2; pass++) {
          var want = pass === 0 ? 1 : -1, open = false;
          ctx.strokeStyle = want > 0 ? COL.up : COL.dn; ctx.lineWidth = 1.6; ctx.beginPath();
          for (i = Math.max(0, i0 - 1); i <= Math.min(st.n - 1, i1 + 1); i++) {
            var sv = A.st.v[i];
            if (sv === sv && A.st.dir[i] === want) { if (open) ctx.lineTo(X(i), Y(sv)); else { ctx.moveTo(X(i), Y(sv)); open = true; } } else open = false;
          }
          ctx.stroke();
        }
      }
    }
    function label(t, x, y, color, align) {
      ctx.font = '700 10px ' + FONT; var w = ctx.measureText(t).width + 8, lx = align === 'right' ? x - w : align === 'center' ? x - w / 2 : x;
      lx = Math.max(st.geo.l + 2, Math.min(st.geo.r - w - 2, lx)); y = Math.max(st.geo.t + 9, Math.min(st.geo.pb - 7, y));
      ctx.fillStyle = COL.panel; roundRect(lx, y - 8, w, 14, 3); ctx.fill();
      ctx.fillStyle = color; ctx.textAlign = 'left'; ctx.fillText(t, lx + 4, y + 2.5);
    }
    function drawPatterns(A, io, i0, i1, X, Y, half, step) {
      var i, G = st.geo, k;
      if (io.vcp) for (i = 0; i < A.vcp.length; i++) {
        var vc = A.vcp[i], p = vc.piv; if (!p.length || p[p.length - 1].i < i0 - 40 || p[0].i > i1) continue;
        ctx.strokeStyle = IC.acc2; ctx.lineWidth = 1.3; ctx.setLineDash([4, 2]); ctx.beginPath();
        for (k = 0; k < p.length; k++) { if (k) ctx.lineTo(X(p[k].i), Y(p[k].p)); else ctx.moveTo(X(p[k].i), Y(p[k].p)); }
        ctx.stroke();
        var pe = vc.bo >= 0 ? vc.bo : Math.min(st.n - 1, vc.pi + 30), py = Math.round(Y(vc.pivot)) + 0.5;
        ctx.setLineDash([2, 2]); ctx.beginPath(); ctx.moveTo(X(p[p.length - 2].i), py); ctx.lineTo(X(pe) + half, py); ctx.stroke(); ctx.setLineDash(NODASH);
        if (step * (p[p.length - 1].i - p[0].i) > 30) label('VCP ' + vc.depths.map(function (d) { return Math.round(d); }).join('→') + '%' + (vc.dry ? ' vol↓' : ''), X(p[0].i), Y(p[0].p) - 10, IC.acc2, 'left');
      }
      if (io.flag) for (i = 0; i < A.flags.length; i++) {
        var f = A.flags[i], fe = f.bo >= 0 ? f.bo : f.e; if (fe < i0 || f.p0.i > i1) continue;
        var fc = f.dir > 0 ? COL.up : COL.dn;
        ctx.strokeStyle = fc; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(X(f.p0.i), Y(f.p0.p)); ctx.lineTo(X(f.p1.i), Y(f.p1.p)); ctx.stroke();
        ctx.lineWidth = 1; ctx.setLineDash([3, 2]); ctx.strokeRect(X(f.s) - half, Y(f.hi), X(f.e) - X(f.s) + 2 * half, Y(f.lo) - Y(f.hi)); ctx.setLineDash(NODASH);
        label((f.dir > 0 ? 'Bull flag' : 'Bear flag') + (f.bo >= 0 ? '' : '?'), X(f.p1.i), f.dir > 0 ? Y(f.p1.p) - 10 : Y(f.p1.p) + 12, fc, 'center');
      }
      if (io.dtb) for (i = 0; i < A.dtb.length; i++) {
        var d = A.dtb[i], de = d.conf >= 0 ? d.conf : st.n - 1; if (de < i0 || d.a.i > i1) continue;
        var dc = d.dir < 0 ? COL.dn : COL.up;
        ctx.strokeStyle = dc; ctx.lineWidth = 1.4; ctx.beginPath(); ctx.moveTo(X(d.a.i), Y(d.a.p)); ctx.lineTo(X(d.b.i), Y(d.b.p)); ctx.lineTo(X(d.c.i), Y(d.c.p)); ctx.stroke();
        var ny = Math.round(Y(d.neck)) + 0.5; ctx.setLineDash([4, 3]); ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(X(d.a.i), ny); ctx.lineTo(X(de) + half, ny); ctx.stroke(); ctx.setLineDash(NODASH);
        var dl = (d.conf >= 0 ? '' : 'Possible ') + (d.dir < 0 ? 'double top' : 'double bottom');
        label(dl.charAt(0).toUpperCase() + dl.slice(1), (X(d.a.i) + X(d.c.i)) / 2, d.dir < 0 ? Math.min(Y(d.a.p), Y(d.c.p)) - 10 : Math.max(Y(d.a.p), Y(d.c.p)) + 12, dc, 'center');
      }
      if (io.cup) for (i = 0; i < A.cups.length; i++) {
        var cu = A.cups[i], ce = cu.bo >= 0 ? cu.bo : st.n - 1; if (ce < i0 || cu.a.i > i1) continue;
        ctx.strokeStyle = IC.acc2; ctx.lineWidth = 1.6; ctx.beginPath();
        ctx.moveTo(X(cu.a.i), Y(cu.a.p)); ctx.quadraticCurveTo(X(cu.a.i), Y(cu.b.p), X(cu.b.i), Y(cu.b.p)); ctx.quadraticCurveTo(X(cu.r.i), Y(cu.b.p), X(cu.r.i), Y(cu.r.p));
        ctx.lineTo(X(cu.hl.i), Y(cu.hl.p)); ctx.lineTo(X(ce), Y(cu.r.p)); ctx.stroke();
        label('Possible cup & handle ' + Math.round(cu.depth) + '%', (X(cu.a.i) + X(cu.r.i)) / 2, Y(cu.b.p) + 14, IC.acc2, 'center');
      }
      if (io.darvas && step >= 3) for (i = 0; i < A.darvas.length; i++) {
        var b = A.darvas[i]; if (b.e < i0 || b.s > i1 || b.out) continue;
        label('Darvas ' + CM.fmtPrice(b.bot) + '–' + CM.fmtPrice(b.top), Math.max(G.l + 2, X(b.s) - half), Y(b.top) - 10, IC.acc2, 'left');
      }
    }
    /* strategy / pattern markers: triangles under (up) or over (down) the bar, stacked when several fall on one bar */
    function drawMarks(A, io, i0, i1, X, Y, half, step) {
      if (!CI) return;
      var on = CI.kindsOn(io), mk = A.marks, j = CI.lowerBound(mk, i0), lastI = -1, upN = 0, dnN = 0, showTxt = step >= 7;
      if (io.darvas) { on.dvU = true; on.dvD = true; }
      for (; j < mk.length && mk[j].i <= i1; j++) {
        var m = mk[j], K = CI.KINDS[m.k]; if (!on[m.k] || !K) continue;
        if ((m.k === 'nr7' || m.k === 'ib') && step < 4) continue;
        if (m.i !== lastI) { lastI = m.i; upN = 0; dnN = 0; }
        var x = X(m.i), hi = st.H[m.i] > 0 ? st.H[m.i] : st.C[m.i], lo = st.L[m.i] > 0 ? st.L[m.i] : st.C[m.i], above = K.g === 'dn' || K.g === 'ring' || K.g === 'tiny';
        var y = above ? Y(hi) - 5 - dnN * 12 : Y(lo) + 5 + upN * 12;
        if (above) dnN++; else upN++;
        st.visMarks++;
        ctx.fillStyle = K.c; ctx.strokeStyle = K.c; ctx.lineWidth = 1.4;
        if (K.g === 'up') { ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - 4.5, y + 7); ctx.lineTo(x + 4.5, y + 7); ctx.closePath(); ctx.fill(); }
        else if (K.g === 'dn') { ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - 4.5, y - 7); ctx.lineTo(x + 4.5, y - 7); ctx.closePath(); ctx.fill(); }
        else if (K.g === 'dot') { ctx.beginPath(); ctx.arc(x, y + 4, 3.5, 0, 6.2832); ctx.fill(); }
        else if (K.g === 'ring') { ctx.beginPath(); ctx.arc(x, y - 4, 3.2, 0, 6.2832); ctx.stroke(); }
        else if (K.g === 'star') { ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x - 5, y + 8); ctx.lineTo(x + 5, y + 8); ctx.closePath(); ctx.fill(); ctx.fillStyle = COL.bg; ctx.fillRect(x - 0.75, y + 3, 1.5, 4); }
        else { ctx.font = '700 8.5px ' + FONT; ctx.textAlign = 'center'; ctx.fillText(K.l, x, y - 1); continue; }
        if (showTxt && K.l) { ctx.font = '700 8.5px ' + FONT; ctx.textAlign = 'center'; ctx.fillStyle = K.c; ctx.fillText(K.l, x, above ? y - 9 : y + 17); if (above) dnN++; else upN++; }
      }
    }
    /* legend: what each visible marker / pattern means (top-left of the price pane) */
    function drawLegend(A, io, i0, i1) {
      if (!CI) return;
      var G = st.geo, on = CI.kindsOn(io), seen = {}, rows = [], j, k;
      if (io.darvas) { on.dvU = true; on.dvD = true; }
      for (j = CI.lowerBound(A.marks, i0); j < A.marks.length && A.marks[j].i <= i1; j++) { k = A.marks[j].k; if (on[k] && !seen[k]) { seen[k] = 1; rows.push(k); } }
      var lines = [];
      if (io.bb) lines.push(['Bollinger 20,2', IC.acc2]);
      if (io.st) lines.push(['Supertrend 10,3', COL.up]);
      if (io.piv) lines.push(['Weekly pivots', IC.gray]);
      if (io.cpr) lines.push(['CPR (prev. day)', IC.acc]);
      if (io.vwap && A.vwap) lines.push(['VWAP', IC.amb]);
      if (!rows.length && !lines.length) return;
      ctx.font = '600 10px ' + FONT; ctx.textAlign = 'left';
      var items = lines.map(function (l) { return { t: l[0], c: l[1], g: 'line' }; }).concat(rows.slice(0, Math.max(0, 5 - lines.length)).map(function (k2) { var K = CI.KINDS[k2]; return { t: K.t, c: K.c, g: K.g, l: K.l }; }));
      var wmax = 0; items.forEach(function (it) { wmax = Math.max(wmax, ctx.measureText(it.t).width); });
      var x = G.l + 4, y = G.t + 4, w = Math.min(G.w * 0.62, wmax + 26), h = items.length * 13 + 6;
      ctx.fillStyle = 'rgba(15,16,18,.78)'; roundRect(x, y, w, h, 5); ctx.fill();
      items.forEach(function (it, q) {
        var cy = y + 10 + q * 13, gx = x + 9;
        ctx.fillStyle = it.c; ctx.strokeStyle = it.c; ctx.lineWidth = 1.4;
        if (it.g === 'line') { ctx.fillRect(gx - 5, cy - 1, 10, 2); }
        else if (it.g === 'up' || it.g === 'star') { ctx.beginPath(); ctx.moveTo(gx, cy - 4); ctx.lineTo(gx - 4, cy + 3); ctx.lineTo(gx + 4, cy + 3); ctx.closePath(); ctx.fill(); }
        else if (it.g === 'dn') { ctx.beginPath(); ctx.moveTo(gx, cy + 3); ctx.lineTo(gx - 4, cy - 4); ctx.lineTo(gx + 4, cy - 4); ctx.closePath(); ctx.fill(); }
        else if (it.g === 'dot') { ctx.beginPath(); ctx.arc(gx, cy, 3, 0, 6.2832); ctx.fill(); }
        else if (it.g === 'ring') { ctx.beginPath(); ctx.arc(gx, cy, 3, 0, 6.2832); ctx.stroke(); }
        else { ctx.font = '700 8.5px ' + FONT; ctx.textAlign = 'center'; ctx.fillText(it.l || '', gx, cy + 3); ctx.textAlign = 'left'; }
        ctx.font = '600 10px ' + FONT; ctx.fillStyle = COL.text; ctx.fillText(it.t, x + 18, cy + 3.5, w - 22);
      });
    }
    /* lower panes (RSI 14 / RSI 2 / MACD / ATR), each with its own scale */
    function drawPanes(A, i0, i1, X, step) {
      var G = st.geo, p, i, n = st.n;
      for (p = 0; p < G.panes.length; p++) {
        var P = G.panes[p], id = P.id, top = P.t + 12, bot = P.b, h = bot - top, mn, mx, a, b2;
        ctx.strokeStyle = COL.grid; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(G.l, Math.round(P.t) + 0.5); ctx.lineTo(G.r, Math.round(P.t) + 0.5); ctx.stroke();
        if (id === 'rsi' || id === 'rsi2') { mn = 0; mx = 100; }
        else {
          mn = Infinity; mx = -Infinity;
          var arrs = id === 'macd' ? [A.macd.m, A.macd.s, A.macd.h] : [A.atr];
          for (a = 0; a < arrs.length; a++) for (i = i0; i <= i1; i++) { var q = arrs[a][i]; if (q === q) { if (q < mn) mn = q; if (q > mx) mx = q; } }
          if (!(mx > mn)) { mn = (mn === Infinity ? 0 : mn) - 1; mx = (mx === -Infinity ? 0 : mx) + 1; }
          if (id === 'macd') { b2 = Math.max(Math.abs(mn), Math.abs(mx)); mn = -b2; mx = b2; } else { var pd = (mx - mn) * 0.1; mn -= pd; mx += pd; }
        }
        var PY = (function (lo, hi) { return function (val) { return top + (hi - val) / (hi - lo) * h; }; })(mn, mx);
        ctx.save(); ctx.beginPath(); ctx.rect(G.l, P.t, G.w, P.b - P.t); ctx.clip();
        var valNow = NaN, last = st.cross >= 0 ? st.cross : Math.min(n - 1, i1), col = IC.acc, sub = '';
        if (id === 'rsi' || id === 'rsi2') {
          var lo = id === 'rsi' ? 30 : 10, hi = id === 'rsi' ? 70 : 90, arr = id === 'rsi' ? A.rsi : A.rsi2;
          ctx.fillStyle = IC.accA + '.07)'; ctx.fillRect(G.l, PY(hi), G.w, PY(lo) - PY(hi));
          ctx.strokeStyle = IC.grayA + '.45)'; ctx.setLineDash([3, 3]); ctx.beginPath(); ctx.moveTo(G.l, Math.round(PY(hi)) + 0.5); ctx.lineTo(G.r, Math.round(PY(hi)) + 0.5); ctx.moveTo(G.l, Math.round(PY(lo)) + 0.5); ctx.lineTo(G.r, Math.round(PY(lo)) + 0.5); ctx.stroke(); ctx.setLineDash(NODASH);
          col = id === 'rsi' ? IC.acc : IC.acc2;
          strokeSeries(arr, i0, i1, X, PY, col, 1.3);
          valNow = arr[last];
          text(String(hi), G.r + 6, PY(hi) + 4, COL.axis, '600 10px ' + FONT, 'left'); text(String(lo), G.r + 6, PY(lo) + 4, COL.axis, '600 10px ' + FONT, 'left');
        } else if (id === 'macd') {
          var bw = Math.max(1, Math.min(10, step * 0.6)), zy = PY(0);
          for (var pass = 0; pass < 2; pass++) {
            ctx.fillStyle = pass === 0 ? 'rgba(49,196,141,.5)' : 'rgba(242,85,90,.5)'; ctx.beginPath();
            for (i = i0; i <= i1; i++) { var hv = A.macd.h[i]; if (hv !== hv || (hv >= 0) !== (pass === 0)) continue; var hy = PY(hv); ctx.rect(Math.round(X(i) - bw / 2), Math.min(zy, hy), Math.max(1, Math.round(bw)), Math.max(1, Math.abs(hy - zy))); }
            ctx.fill();
          }
          ctx.strokeStyle = IC.grayA + '.35)'; ctx.beginPath(); ctx.moveTo(G.l, Math.round(zy) + 0.5); ctx.lineTo(G.r, Math.round(zy) + 0.5); ctx.stroke();
          strokeSeries(A.macd.m, i0, i1, X, PY, IC.acc, 1.3); strokeSeries(A.macd.s, i0, i1, X, PY, IC.amb, 1.1);
          valNow = A.macd.m[last]; sub = A.macd.s[last] === A.macd.s[last] ? ' / ' + fmtN(A.macd.s[last]) : '';
          text(fmtN(mx), G.r + 6, top + 8, COL.axis, '600 10px ' + FONT, 'left'); text('0', G.r + 6, zy + 4, COL.axis, '600 10px ' + FONT, 'left');
        } else {
          col = IC.gray; strokeSeries(A.atr, i0, i1, X, PY, col, 1.3); valNow = A.atr[last];
          text(fmtN(mx), G.r + 6, top + 8, COL.axis, '600 10px ' + FONT, 'left'); text(fmtN(mn), G.r + 6, bot - 2, COL.axis, '600 10px ' + FONT, 'left');
        }
        if (st.cross >= 0) { var cx = Math.round(X(st.cross)) + 0.5; ctx.strokeStyle = COL.cross; ctx.setLineDash([4, 3]); ctx.beginPath(); ctx.moveTo(cx, P.t); ctx.lineTo(cx, P.b); ctx.stroke(); ctx.setLineDash(NODASH); }
        ctx.restore();
        ctx.font = '700 10px ' + FONT; ctx.textAlign = 'left'; ctx.fillStyle = COL.axis; ctx.fillText(PANE_NAME[id], G.l + 4, P.t + 11);
        var nw = ctx.measureText(PANE_NAME[id]).width;
        ctx.fillStyle = col; ctx.fillText(valNow === valNow ? (id === 'rsi' || id === 'rsi2' ? valNow.toFixed(1) : fmtN(valNow)) + sub : '—', G.l + 10 + nw, P.t + 11);
      }
    }
    function fmtN(v) { var a = Math.abs(v); return a >= 1000 ? v.toFixed(0) : a >= 100 ? v.toFixed(1) : v.toFixed(2); }

    function roundRect(x, y, w, h, r) {
      ctx.beginPath(); ctx.moveTo(x + r, y); ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r); ctx.lineTo(x + w, y + h - r);
      ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h); ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r); ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y);
    }

    /* automatic levels: a price tag in the axis gutter + a small caption at the left end of the line (keeps the plot readable) */
    function drawAutoTags(Y, mn, mx) {
      var G = st.geo, list = st.autoLv.filter(function (x) { return x.v >= mn && x.v <= mx; }), i, L;
      if (!list.length) return;
      var ly = CM.dodge(list.map(function (x) { return Y(x.v); }), 15, G.t + 7, G.pb - 7), capY = [];
      for (i = 0; i < list.length; i++) {
        L = list[i];
        ctx.fillStyle = COL.bg; roundRect(G.r + 1, ly[i] - 7, 53, 14, 3); ctx.fill();
        ctx.strokeStyle = L.c; ctx.lineWidth = 1; roundRect(G.r + 1.5, ly[i] - 6.5, 52, 13, 3); ctx.stroke();
        text(CM.fmtPrice(L.v), G.r + 5, ly[i] + 3.5, L.c, '700 10px ' + FONT, 'left');
        var cy = Y(L.v) - 3, clash = false;
        for (var q = 0; q < capY.length; q++) if (Math.abs(capY[q] - cy) < 10) { clash = true; break; }
        if (!clash) {
          capY.push(cy);
          ctx.font = '700 9px ' + FONT; var cw = ctx.measureText(L.cap).width;
        ctx.fillStyle = 'rgba(15,16,18,.7)'; ctx.fillRect(G.l + 2, cy - 8, cw + 4, 10);
          text(L.cap, G.l + 4, cy, L.c, '700 9px ' + FONT, 'left');
        }
        st.autoLabels.push({ t: L.t, y: ly[i], k: L.k });
      }
    }
    function drawLevelLabels(Y, mn, mx) {
      var G = st.geo, items = [], i, L, ys = [];
      for (i = 0; i < st.lv.length; i++) {
        L = st.lv[i];
        var mid = L.k === 'zone' && L.v2 > 0 ? (L.v + L.v2) / 2 : L.v, side = CM.levelSide(mid, mn, mx);
        if (L.k === 'zone' && L.v2 > 0 && side !== 0 && CM.levelSide(L.v, mn, mx) === 0) side = 0;
        items.push({ L: L, side: side, want: side === 0 ? Y(mid) : (side > 0 ? G.t + 10 : G.pb - 10) });
        ys.push(items[i].want);
      }
      var ly = CM.dodge(ys, st.w < 420 ? 19 : 17, G.t + 9, G.pb - 9);
      ctx.font = '700 10.5px ' + FONT; ctx.textAlign = 'left';
      for (i = 0; i < items.length; i++) {
        L = items[i].L;
        var label = (items[i].side > 0 ? '▲ ' : items[i].side < 0 ? '▼ ' : '') + L.t, tw = ctx.measureText(label).width, pw = tw + 12, px = G.r - pw - 5, py = ly[i] - 8;
        ctx.fillStyle = COL.panel; roundRect(px, py, pw, 16, 4); ctx.fill();
        ctx.strokeStyle = L.c; ctx.lineWidth = 1; roundRect(px + 0.5, py + 0.5, pw - 1, 15, 4); ctx.stroke();
        ctx.fillStyle = L.c; ctx.fillText(label, px + 6, ly[i] + 3.5);
        st.labels.push({ t: label, y: ly[i], k: L.k });
      }
    }

    function drawCross(Y) {
      var G = st.geo, i = st.cross, x = Math.round(xOf(i)) + 0.5, cy = st.crossY, c = st.C[i];
      if (cy === null || cy === undefined) cy = c > 0 ? Y(c) : G.t + G.ph / 2;
      cy = Math.max(G.t, Math.min(G.pb, cy));
      ctx.save();
      ctx.strokeStyle = COL.cross; ctx.lineWidth = 1; ctx.setLineDash([4, 3]);
      ctx.beginPath(); ctx.moveTo(x, G.t); ctx.lineTo(x, G.vol ? G.vb : G.pb); ctx.moveTo(G.l, Math.round(cy) + 0.5); ctx.lineTo(G.r, Math.round(cy) + 0.5); ctx.stroke();
      ctx.setLineDash(NODASH);
      if (c > 0) { ctx.fillStyle = COL.text; ctx.beginPath(); ctx.arc(x, Y(c), 3.4, 0, 6.2832); ctx.fill(); ctx.strokeStyle = COL.bg; ctx.lineWidth = 1.5; ctx.stroke(); }
      /* price tag on the axis */
      var price = CM.priceAt(cy, st.pr.min, st.pr.max, G.t, G.ph);
      ctx.fillStyle = COL.text; roundRect(G.r + 1, cy - 9, 53, 18, 4); ctx.fill();
      text(CM.fmtPrice(price), G.r + 5, cy + 4, '#0F1012', '700 11px ' + FONT, 'left');
      /* date tag */
      var dlab = st.dRaw ? CM.fmtDate(st.dRaw[i]) : '#' + (i + 1);
      ctx.font = '700 11px ' + FONT; var dw = ctx.measureText(dlab).width + 14, dx = Math.max(G.l, Math.min(G.r - dw, x - dw / 2));
      ctx.fillStyle = COL.text; roundRect(dx, st.h - 19, dw, 17, 4); ctx.fill();
      text(dlab, dx + dw / 2, st.h - 6.5, '#0F1012', '700 11px ' + FONT, 'center');
      ctx.restore();
    }

    /* ---------- crosshair + readout ---------- */
    function hideReadout() { if (ro) { ro.hidden = true; } st.crossReadIdx = -2; }
    function emaKeysOn() { var out = [], k; for (k = 0; k < EMA_KEYS.length; k++) { if (st.opts.emas[EMA_KEYS[k]]) out.push(EMA_KEYS[k]); } return out.reverse(); }
    function refreshReadout(force) {
      if (!ro || st.cross < 0 || !SE || !SE.cfReadout) return;
      if (!force && st.crossReadIdx === st.cross) return;
      st.crossReadIdx = st.cross;
      ro.innerHTML = SE.cfReadout(CM.barInfo(st.res, st.cross, emaKeysOn())) + indReadout(st.cross);
      ro.hidden = false;
    }
    function escH(s) { return String(s).replace(/[&<>"']/g, function (ch) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]; }); }
    function indReadout(i) {
      if (!CI || !st.ind || !st.ind.ok) return '';
      var rows = CI.readout(st.ind, i, st.io); if (!rows.length) return '';
      var vals = rows.filter(function (r) { return !r.mark; }), marks = rows.filter(function (r) { return r.mark; }), h = '';
      if (vals.length) h += '<div class="re cix">' + vals.map(function (r) { return '<div><span><i style="background:' + r.c + '"></i>' + escH(r.t) + '</span><b class="num">' + escH(r.v) + '</b></div>'; }).join('') + '</div>';
      if (marks.length) h += '<div class="cik">' + marks.map(function (r) { return '<div><i style="background:' + r.c + '"></i>' + escH(r.t) + '</div>'; }).join('') + '</div>';
      return h;
    }
    function setCross(x, y) {
      if (st.n < 1) return;
      var G = st.geo, i = CM.idxAt(Math.max(G.l, Math.min(G.r, x)), st.view, G.l, G.w, st.n);
      if (i < 0) return;
      st.cross = i; st.crossY = y; st.crossX = x;
      if (ro) ro.className = 'cfro ' + (x > st.w * 0.5 ? 'l' : 'r');   // the panel sits on the side away from the finger
      refreshReadout(false);
      invalidate();
    }
    function setCrossIdx(i) {
      if (!(i >= 0 && i < st.n)) return;
      st.cross = Math.floor(i); st.crossY = null;
      var x = xOf(st.cross); if (ro) ro.className = 'cfro ' + (x > st.w * 0.5 ? 'l' : 'r');
      refreshReadout(true); invalidate();
    }
    function clearCross() { if (st.cross >= 0) { st.cross = -1; st.crossY = null; hideReadout(); invalidate(); } }

    /* ---------- gestures ---------- */
    function localPt(cx, cy) { var r = g.rect || canvas.getBoundingClientRect(); return { x: cx - r.left, y: cy - r.top }; }
    function cancelHold() { if (g.hold) { clearTimeout(g.hold); g.hold = 0; } }
    function down(cx, cy, mouse) {
      g.rect = canvas.getBoundingClientRect(); g.mouse = !!mouse;
      var p = localPt(cx, cy);
      g.mode = 'pending'; g.t0 = now(); g.x0 = p.x; g.y0 = p.y; g.lastX = p.x; g.lastY = p.y; g.samples = [{ x: p.x, t: g.t0 }]; st.fling = 0;
      cancelHold();
      if (p.x > st.geo.r && p.y < st.geo.pb) { g.mode = 'yaxis'; g.yz0 = st.yz; clearCross(); return; }   /* price axis: drag to stretch / squeeze */
      g.hold = setTimeout(function () { g.hold = 0; if (g.mode === 'pending') { g.mode = 'cross'; setCross(g.x0, g.y0); } }, HOLD_MS);
    }
    function move(cx, cy) {
      var p = localPt(cx, cy), t = now();
      if (g.mode === 'pending') {
        if (Math.abs(p.x - g.x0) > SLOP || Math.abs(p.y - g.y0) > SLOP) { cancelHold(); g.mode = 'pan'; g.lastX = g.x0; }
        else return;
      }
      if (g.mode === 'yaxis') { st.yz = Math.max(0.1, Math.min(20, g.yz0 * Math.exp((p.y - g.y0) / 160))); invalidate(); return; }
      if (g.mode === 'pan') {
        st.view = CM.panBy(st.view, p.x - g.lastX, st.geo.w, st.n); g.lastX = p.x;
        if (st.yz !== 1 || st.yo !== 0) { st.yo += (p.y - g.lastY) / Math.max(20, st.geo.ph) * st.yz; } g.lastY = p.y;
        g.samples.push({ x: p.x, t: t }); if (g.samples.length > 12) g.samples.shift();
        invalidate();
      } else if (g.mode === 'cross') setCross(p.x, p.y);
    }
    function up() {
      cancelHold();
      var t = now(), mode = g.mode; g.mode = '';
      if (mode === 'yaxis') {                                             // double tap on the axis = back to auto scale
        if (Math.abs(st.yz - g.yz0) < 0.01) { if (t - g.axT < DBL_MS) { st.yz = 1; st.yo = 0; g.axT = 0; invalidate(); } else g.axT = t; }
        return;
      }
      if (mode === 'pan') {
        var vel = CM.flingVelocity(g.samples, t);
        if (Math.abs(vel) > 0.05) { st.fling = vel; st.flingT = 0; invalidate(); }
      } else if (mode === 'pending') {                                    // a tap
        var dbl = t - g.tapT < DBL_MS && Math.abs(g.x0 - g.tapX) < DBL_PX && Math.abs(g.y0 - g.tapY) < DBL_PX;
        if (dbl) { g.tapT = 0; resetView(); if (cfg.onReset) cfg.onReset(); }
        else { g.tapT = t; g.tapX = g.x0; g.tapY = g.y0; if (st.cross >= 0) clearCross(); }
      }
    }

    function onTouchStart(e) {
      if (e.cancelable) e.preventDefault();
      var ts = e.touches;
      if (ts.length === 1) { down(ts[0].clientX, ts[0].clientY, false); }
      else if (ts.length === 2) {
        cancelHold(); g.mode = 'pinch'; g.rect = canvas.getBoundingClientRect();
        g.d0 = Math.max(1, dist(ts[0], ts[1])); g.v0 = { s: st.view.s, c: st.view.c };
        g.f0 = ((ts[0].clientX + ts[1].clientX) / 2 - g.rect.left - st.geo.l) / st.geo.w; st.fling = 0;
        clearCross();
      }
    }
    function onTouchMove(e) {
      if (e.cancelable) e.preventDefault();
      var ts = e.touches;
      if (g.mode === 'pinch' && ts.length >= 2) {
        var f1 = ((ts[0].clientX + ts[1].clientX) / 2 - g.rect.left - st.geo.l) / st.geo.w;
        st.view = CM.pinchView(g.v0, g.d0, Math.max(1, dist(ts[0], ts[1])), g.f0, f1, st.n);
        invalidate();
      } else if (ts.length === 1) move(ts[0].clientX, ts[0].clientY);
    }
    function onTouchEnd(e) {
      if (e.cancelable) e.preventDefault();
      var ts = e.touches;
      if (ts.length === 0) { if (g.mode === 'pinch') { g.mode = ''; } else up(); }
      else if (ts.length === 1 && g.mode === 'pinch') {                    // one finger left: carry on as a pan without a jump
        g.rect = canvas.getBoundingClientRect(); var p = localPt(ts[0].clientX, ts[0].clientY);
        g.mode = 'pan'; g.lastX = p.x; g.lastY = p.y; g.x0 = p.x; g.y0 = p.y; g.samples = [{ x: p.x, t: now() }];
      }
    }
    function onTouchCancel() { cancelHold(); g.mode = ''; }

    /* mouse (desktop testing; WebView on a phone never sends these because touchstart is cancelled) */
    var mouseDown = false;
    function onMouseDown(e) {
      if (e.button !== 0) return;
      e.preventDefault(); mouseDown = true; down(e.clientX, e.clientY, true);
      root.addEventListener('mousemove', onMouseMoveDrag); root.addEventListener('mouseup', onMouseUp);
    }
    function onMouseMoveDrag(e) { if (mouseDown) move(e.clientX, e.clientY); }
    function onMouseUp() { mouseDown = false; root.removeEventListener('mousemove', onMouseMoveDrag); root.removeEventListener('mouseup', onMouseUp); up(); }
    function onHover(e) {
      if (mouseDown) return;
      g.rect = canvas.getBoundingClientRect();
      var p = localPt(e.clientX, e.clientY); setCross(p.x, p.y);
    }
    function onDblClick(e) { e.preventDefault(); resetView(); if (cfg.onReset) cfg.onReset(); }
    function onWheel(e) {
      e.preventDefault();
      var r = canvas.getBoundingClientRect(), x = e.clientX - r.left, G = st.geo, unit = e.deltaMode === 1 ? 16 : 1;
      var dx = e.deltaX * unit, dy = e.deltaY * unit;
      if (e.shiftKey || Math.abs(dx) > Math.abs(dy)) {
        st.view = CM.panBy(st.view, -(dx || dy), G.w, st.n);
      } else {
        var factor = Math.exp(-dy * (e.ctrlKey ? 0.012 : 0.0018));
        st.view = CM.zoomAt(st.view, factor, (x - G.l) / G.w, st.n);
      }
      st.fling = 0; invalidate();
    }
    function onCtx(e) { e.preventDefault(); }

    canvas.addEventListener('touchstart', onTouchStart, { passive: false });
    canvas.addEventListener('touchmove', onTouchMove, { passive: false });
    canvas.addEventListener('touchend', onTouchEnd, { passive: false });
    canvas.addEventListener('touchcancel', onTouchCancel, { passive: true });
    canvas.addEventListener('mousedown', onMouseDown);
    canvas.addEventListener('mousemove', onHover);
    canvas.addEventListener('dblclick', onDblClick);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('contextmenu', onCtx);
    var rob = null;
    if (root.ResizeObserver) { rob = new root.ResizeObserver(function () { resize(); }); rob.observe(stage); }
    root.addEventListener('resize', resize); root.addEventListener('orientationchange', resize);

    /* ---------- Indicators menu (a compact toggle sheet over the chart) ---------- */
    var btn = null;
    function findBtn() { if (!btn || !btn.isConnected) { var sh = stage.closest ? stage.closest('.sheet') : null; btn = (sh || root.document).querySelector ? (sh || root.document).querySelector('#cf-indb') : null; } return btn; }
    function menuHtml() {
      var io = st.io, A = st.ind, h = '<div class="cfimh"><b>Indicators</b><span>' + countOn() + ' on</span><button type="button" class="cfimx" data-ci="_close">Done</button></div>';
      CI.GROUPS.forEach(function (g) {
        var items = g.items.filter(function (it) { return it[0] !== 'vwap' || (A && A.vwap); });   /* VWAP needs intraday bars */
        if (!items.length) return;
        h += '<div class="cfimg">' + escH(g.t) + '</div><div class="cfimr">' + items.map(function (it) {
          var id = it[0], on = !!io[id], sw = swatch(id);
          return '<button type="button" class="cfic' + (on ? ' on' : '') + '" data-ci="' + id + '" aria-pressed="' + on + '">' + sw + '<span>' + escH(it[1]) + '</span></button>';
        }).join('') + '</div>';
      });
      return h + '<p class="cfimn">Patterns are approximate screens, not signals to trade blindly.</p>';
    }
    function swatch(id) {
      var ks = CI.MARK_OF[id], c = { rsi: IC.acc, rsi2: IC.acc2, macd: IC.acc, atr: IC.gray, bb: IC.acc2, st: COL.up, piv: IC.gray, cpr: IC.acc, hl52: IC.gray, vavg: IC.amb, vwap: IC.amb, sr: COL.up, prev: IC.gray, gap: COL.up }[id];
      if (ks && ks.length) c = CI.KINDS[ks[0]].c;
      var g = ks && ks.length ? CI.KINDS[ks[0]].g : 'line';
      return '<i class="g-' + g + '" style="--c:' + (c || IC.acc) + '"></i>';
    }
    function countOn() { var k, c = 0; for (k in st.io) if (k.charAt(0) !== '_' && st.io[k] === true) c++; return c; }
    function paintMenu() {
      var b = findBtn(); if (b && CI) { var c = countOn(), em = b.querySelector('em'); if (em) em.textContent = c ? String(c) : ''; b.setAttribute('aria-expanded', st.menu && !st.menu.hidden ? 'true' : 'false'); }
      if (!st.menu || st.menu.hidden || !CI) return;
      var top = st.menu.scrollTop; st.menu.innerHTML = menuHtml(); st.menu.scrollTop = top;
    }
    function onMenuClick(e) {
      var el = e.target; while (el && el !== st.menu && !(el.getAttribute && el.getAttribute('data-ci'))) el = el.parentNode;
      if (!el || el === st.menu) return;
      e.stopPropagation(); e.preventDefault();
      var id = el.getAttribute('data-ci');
      if (id === '_close') { closeMenu(); return; }
      toggleInd(id);
    }
    function openMenu() {
      if (!CI) return;
      if (!st.menu) {
        st.menu = root.document.createElement('div'); st.menu.className = 'cfim'; st.menu.setAttribute('role', 'dialog'); st.menu.setAttribute('aria-label', 'Indicators');
        st.menu.addEventListener('click', onMenuClick);
        stage.appendChild(st.menu);
      }
      st.menu.hidden = false; clearCross(); paintMenu();
    }
    function closeMenu() { if (st.menu) st.menu.hidden = true; paintMenu(); }
    function toggleMenu() { if (st.menu && !st.menu.hidden) closeMenu(); else openMenu(); }

    function state() {
      var G = st.geo;
      return { n: st.n, s: st.view.s, c: st.view.c, cross: st.cross, w: st.w, h: st.h, dpr: st.dpr, frames: st.frames, mode: st.view.c > 180 ? 'line' : 'candles',
        pmin: st.pr.min, pmax: st.pr.max, plot: { l: G.l, t: G.t, r: G.r, b: G.pb, w: G.w, h: G.ph }, labels: st.labels.slice(), fling: st.fling, vol: !!G.vol,
        emas: emaKeysOn(), levels: st.opts.levels, ranges: CM.activeRanges(st.view, st.n),
        ind: CI ? Object.keys(st.io).filter(function (k) { return k.charAt(0) !== '_' && st.io[k] === true; }) : [], panes: G.panes.map(function (p) { return p.id; }),
        autoLabels: st.autoLabels.slice(), marks: st.visMarks, drawMs: st.drawMs, lastDrawMs: st.lastDrawMs || 0, analyzeMs: st.ind ? st.ind.ms : 0,
        menu: !!(st.menu && !st.menu.hidden), ana: st.ind };
    }
    function destroy() {
      st.destroyed = true; cancelHold();
      if (st.raf) { root.cancelAnimationFrame(st.raf); st.raf = 0; }
      if (rob) { try { rob.disconnect(); } catch (e) { /* ignore */ } }
      root.removeEventListener('resize', resize); root.removeEventListener('orientationchange', resize);
      root.removeEventListener('mousemove', onMouseMoveDrag); root.removeEventListener('mouseup', onMouseUp);
    }
    resize(); paintMenu();
    return { setData: setData, setOpts: setOpts, setLevels: setLevels, setView: setView, setRange: setRange, reset: resetView, setCrossIdx: setCrossIdx, clearCross: clearCross,
      resize: resize, flush: flush, state: state, destroy: destroy, xOf: xOf, yOf: yOfPrice, canvas: canvas,
      setInd: setInd, toggleInd: toggleInd, toggleMenu: toggleMenu, openMenu: openMenu, closeMenu: closeMenu };
  }

  root.SEChart = { create: create };
})(typeof window !== 'undefined' ? window : this);
