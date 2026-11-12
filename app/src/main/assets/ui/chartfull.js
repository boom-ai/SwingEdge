/* SwingEdge — full-screen interactive chart: <canvas> renderer + gesture controller.
   Pure maths lives in chartmath.js (CM); markup and the readout panel HTML come from render.js (SE).
   Gestures (canvas has touch-action:none, so there is no page scroll to fight with):
     one finger drag = pan (with inertia) · two fingers = pinch zoom around the midpoint · hold ~250 ms then drag = crosshair
     tap (while a crosshair shows) = hide it · double tap = reset to the default window
     mouse: wheel = zoom at the cursor (shift/horizontal wheel = pan) · drag = pan · hover or hold+drag = crosshair · double click = reset
   Drawing happens only inside requestAnimationFrame and only when something changed. */
(function (root) {
  'use strict';
  var CM = root.CM, SE = root.SE;
  if (!CM) return;

  var COL = { bg: '#0E141B', grid: '#18232F', axis: '#7F8EA0', text: '#E9EEF4', up: '#3FD68F', dn: '#FF6F6F', line: '#CFE5FF', cross: '#A9B6C4', panel: 'rgba(14,20,27,.88)' };
  var EMA_COL = { 10: '#F2B441', 20: '#FF8F6B', 50: '#6BB3FF', 100: '#4DD0E1', 200: '#C58BFF' };
  var EMA_KEYS = [200, 100, 50, 20, 10];                      // draw order: slowest first
  var LV = {
    zone: { c: '#6BB3FF', d: [] }, stop: { c: '#FF6F6F', d: [5, 3] }, t1: { c: '#3FD68F', d: [5, 3] }, t2: { c: '#8DEBBC', d: [2, 3] },
    sup: { c: '#52C98F', d: [1, 4] }, res: { c: '#F2B441', d: [1, 4] }, trail: { c: '#FF9F9F', d: [1, 4] },
    mytgt: { c: '#FF7AC6', d: [8, 3, 2, 3] }, mystop: { c: '#FF7AC6', d: [8, 3, 2, 3] }
  };
  var NODASH = [];
  var HOLD_MS = 250, SLOP = 8, DBL_MS = 320, DBL_PX = 32;
  var FONT = '"Roboto Condensed", "sans-serif-condensed", "Arial Narrow", Roboto, system-ui, sans-serif';

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
      pr: { min: 0, max: 1 }, geo: { l: 6, t: 8, r: 0, b: 0, w: 0, pb: 0, vt: 0, vb: 0 }, lastNotified: '', labels: [], crossReadIdx: -2
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
      rebuildSeries(); invalidate();
    }
    function rebuildSeries() {
      st.series = [];
      for (var k = 0; k < EMA_KEYS.length; k++) if (st.opts.emas[EMA_KEYS[k]]) st.series.push(st.E[EMA_KEYS[k]]);
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
    function resetView() { st.view = CM.defaultView(st.n); st.fling = 0; clearCross(); invalidate(); }
    function setRange(key) { st.view = CM.rangeView(key, st.n); st.fling = 0; invalidate(); }

    /* ---------- geometry ---------- */
    function layout() {
      var G = st.geo, W = st.w, H = st.h, RA = 56, BA = 21, TP = 8;
      G.l = 6; G.r = W - RA; G.w = Math.max(10, G.r - G.l); G.t = TP;
      var showVol = st.opts.vol && st.hasVol, vh = showVol ? Math.max(38, Math.round((H - TP - BA) * 0.17)) : 0, gap = showVol ? 8 : 0;
      G.pb = H - BA - vh - gap; G.vt = G.pb + gap; G.vb = H - BA; G.vol = showVol; G.ph = Math.max(20, G.pb - G.t);
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
      var W = st.w, H = st.h, G = st.geo, n = st.n, v = st.view, i, k;
      layout();
      ctx.setTransform(st.dpr, 0, 0, st.dpr, 0, 0);
      ctx.fillStyle = COL.bg; ctx.fillRect(0, 0, W, H);
      st.labels = [];
      if (n < 5 || W < 60 || H < 60 || !(v.c > 0)) return;
      var i0 = Math.max(0, Math.floor(v.s)), i1 = Math.min(n - 1, Math.ceil(v.s + v.c) - 1);
      var pr = CM.priceRange(st.H, st.L, i0, i1, st.series, st.opts.levels ? st.lvVals : null);
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
      for (i = 0; i < dt.length; i++) { var dx = Math.round(CM.xOf(dt[i].i, v, G.l, G.w)) + 0.5; ctx.moveTo(dx, pt); ctx.lineTo(dx, G.vb); }
      ctx.stroke();
      for (i = 0; i < dt.length; i++) {
        var tx = CM.xOf(dt[i].i, v, G.l, G.w);
        text(dt[i].text, Math.max(G.l + 14, Math.min(G.r - 14, tx)), H - 6, dt[i].major ? COL.text : COL.axis, (dt[i].major ? '700 ' : '600 ') + '11px ' + FONT, 'center');
      }

      /* everything in the price pane is clipped to it */
      ctx.save(); ctx.beginPath(); ctx.rect(G.l, pt, G.w, ph); ctx.clip();

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
        ctx.strokeStyle = COL.line; ctx.lineWidth = 1.5; ctx.beginPath();
        var op = false, fx = 0, lx = 0;
        for (i = Math.max(0, i0 - 1); i <= Math.min(n - 1, i1 + 1); i++) {
          if (st.C[i] > 0) { lx = G.l + (i + 0.5 - v.s) * step; var cy = Y(st.C[i]); if (op) ctx.lineTo(lx, cy); else { ctx.moveTo(lx, cy); fx = lx; op = true; } }
          else op = false;
        }
        ctx.stroke();
        if (lx > fx) { ctx.lineTo(lx, pb); ctx.lineTo(fx, pb); ctx.closePath(); ctx.fillStyle = 'rgba(207,229,255,.06)'; ctx.fill(); }
      }
      ctx.restore();

      /* volume strip */
      if (G.vol) {
        var maxV = 0; for (i = i0; i <= i1; i++) if (st.V[i] > maxV) maxV = st.V[i];
        var vh = G.vb - G.vt, vw = Math.max(1, Math.min(14, step * 0.7));
        ctx.strokeStyle = COL.grid; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(G.l, Math.round(G.vb) + 0.5); ctx.lineTo(G.r, Math.round(G.vb) + 0.5); ctx.stroke();
        for (var vp = 0; vp < 2; vp++) {
          ctx.fillStyle = vp === 0 ? 'rgba(63,214,143,.55)' : 'rgba(255,111,111,.55)'; ctx.beginPath();
          for (i = i0; i <= i1; i++) {
            if (!(st.V[i] > 0) || !(maxV > 0)) continue;
            var upv = st.C[i] >= (st.O[i] > 0 ? st.O[i] : st.C[i]); if (upv !== (vp === 0)) continue;
            var bh = Math.max(1, st.V[i] / maxV * vh);
            ctx.rect(Math.round(G.l + (i + 0.5 - v.s) * step - vw / 2), G.vb - bh, Math.max(1, Math.round(vw)), bh);
          }
          ctx.fill();
        }
        text(SE && SE.volTxt ? 'Vol ' + SE.volTxt(maxV) : 'Vol', G.r + 6, G.vt + 10, COL.axis, '600 10px ' + FONT, 'left');
      }

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

      /* crosshair */
      if (st.cross >= 0 && st.cross < n) drawCross(Y);
    }

    function roundRect(x, y, w, h, r) {
      ctx.beginPath(); ctx.moveTo(x + r, y); ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r); ctx.lineTo(x + w, y + h - r);
      ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h); ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r); ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y);
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
      var ly = CM.dodge(ys, 16, G.t + 9, G.pb - 9);
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
      ctx.beginPath(); ctx.moveTo(x, G.t); ctx.lineTo(x, G.vb); ctx.moveTo(G.l, Math.round(cy) + 0.5); ctx.lineTo(G.r, Math.round(cy) + 0.5); ctx.stroke();
      ctx.setLineDash(NODASH);
      if (c > 0) { ctx.fillStyle = COL.text; ctx.beginPath(); ctx.arc(x, Y(c), 3.4, 0, 6.2832); ctx.fill(); ctx.strokeStyle = COL.bg; ctx.lineWidth = 1.5; ctx.stroke(); }
      /* price tag on the axis */
      var price = CM.priceAt(cy, st.pr.min, st.pr.max, G.t, G.ph);
      ctx.fillStyle = COL.text; roundRect(G.r + 1, cy - 9, 53, 18, 4); ctx.fill();
      text(CM.fmtPrice(price), G.r + 5, cy + 4, '#0B0F14', '700 11px ' + FONT, 'left');
      /* date tag */
      var dlab = st.dRaw ? CM.fmtDate(st.dRaw[i]) : '#' + (i + 1);
      ctx.font = '700 11px ' + FONT; var dw = ctx.measureText(dlab).width + 14, dx = Math.max(G.l, Math.min(G.r - dw, x - dw / 2));
      ctx.fillStyle = COL.text; roundRect(dx, st.h - 19, dw, 17, 4); ctx.fill();
      text(dlab, dx + dw / 2, st.h - 6.5, '#0B0F14', '700 11px ' + FONT, 'center');
      ctx.restore();
    }

    /* ---------- crosshair + readout ---------- */
    function hideReadout() { if (ro) { ro.hidden = true; } st.crossReadIdx = -2; }
    function emaKeysOn() { var out = [], k; for (k = 0; k < EMA_KEYS.length; k++) { if (st.opts.emas[EMA_KEYS[k]]) out.push(EMA_KEYS[k]); } return out.reverse(); }
    function refreshReadout(force) {
      if (!ro || st.cross < 0 || !SE || !SE.cfReadout) return;
      if (!force && st.crossReadIdx === st.cross) return;
      st.crossReadIdx = st.cross;
      ro.innerHTML = SE.cfReadout(CM.barInfo(st.res, st.cross, emaKeysOn()));
      ro.hidden = false;
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
      g.mode = 'pending'; g.t0 = now(); g.x0 = p.x; g.y0 = p.y; g.lastX = p.x; g.samples = [{ x: p.x, t: g.t0 }]; st.fling = 0;
      cancelHold();
      g.hold = setTimeout(function () { g.hold = 0; if (g.mode === 'pending') { g.mode = 'cross'; setCross(g.x0, g.y0); } }, HOLD_MS);
    }
    function move(cx, cy) {
      var p = localPt(cx, cy), t = now();
      if (g.mode === 'pending') {
        if (Math.abs(p.x - g.x0) > SLOP || Math.abs(p.y - g.y0) > SLOP) { cancelHold(); g.mode = 'pan'; g.lastX = g.x0; }
        else return;
      }
      if (g.mode === 'pan') {
        st.view = CM.panBy(st.view, p.x - g.lastX, st.geo.w, st.n); g.lastX = p.x;
        g.samples.push({ x: p.x, t: t }); if (g.samples.length > 12) g.samples.shift();
        invalidate();
      } else if (g.mode === 'cross') setCross(p.x, p.y);
    }
    function up() {
      cancelHold();
      var t = now(), mode = g.mode; g.mode = '';
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
        g.mode = 'pan'; g.lastX = p.x; g.x0 = p.x; g.y0 = p.y; g.samples = [{ x: p.x, t: now() }];
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

    function state() {
      var G = st.geo;
      return { n: st.n, s: st.view.s, c: st.view.c, cross: st.cross, w: st.w, h: st.h, dpr: st.dpr, frames: st.frames, mode: st.view.c > 180 ? 'line' : 'candles',
        pmin: st.pr.min, pmax: st.pr.max, plot: { l: G.l, t: G.t, r: G.r, b: G.pb, w: G.w, h: G.ph }, labels: st.labels.slice(), fling: st.fling, vol: !!G.vol,
        emas: emaKeysOn(), levels: st.opts.levels, ranges: CM.activeRanges(st.view, st.n) };
    }
    function destroy() {
      st.destroyed = true; cancelHold();
      if (st.raf) { root.cancelAnimationFrame(st.raf); st.raf = 0; }
      if (rob) { try { rob.disconnect(); } catch (e) { /* ignore */ } }
      root.removeEventListener('resize', resize); root.removeEventListener('orientationchange', resize);
      root.removeEventListener('mousemove', onMouseMoveDrag); root.removeEventListener('mouseup', onMouseUp);
    }
    resize();
    return { setData: setData, setOpts: setOpts, setLevels: setLevels, setView: setView, setRange: setRange, reset: resetView, setCrossIdx: setCrossIdx, clearCross: clearCross,
      resize: resize, flush: flush, state: state, destroy: destroy, xOf: xOf, yOf: yOfPrice, canvas: canvas };
  }

  root.SEChart = { create: create };
})(typeof window !== 'undefined' ? window : this);
