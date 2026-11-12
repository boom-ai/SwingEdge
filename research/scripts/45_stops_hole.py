"""Review D (stop realism on lower-circuit days, claim 3 targets) and hole-split of plan expectancy."""
import sys, os, importlib.util, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location('v42', os.path.join(os.path.dirname(os.path.abspath(__file__)), '42_variants.py')); v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
for k in ('V', 'CAND', 'pos', 'TAB', 'ts_c', 'js_c', 'Cff', 'dates', 'T', 'N', 'GAP', 't0', 'tsplit', 'O', 'H', 'L', 'C', 'S', 'UCL', 'U', 'ATR', 'SMA50', 'F', 'g', 'sim_r4', 'Z', 'nw_t', 'dayavg'): globals()[k] = getattr(v, k)
OUT = v.OUT; out = []
def P(s): print(s); out.append(s)
BUY = v.BUY & CAND
tb, jb = np.nonzero(BUY & (np.arange(T)[:, None] >= t0))
rows = pos[tb, jb]
r, x, e, st = TAB['X10']
df = pd.DataFrame({'t': tb, 'j': jb, 'ret': r[rows], 'xd': x[rows], 'ep': e[rows], 'st': st[rows], 'S': S[tb, jb]}).dropna(subset=['ret'])
# one trade per stock at a time (researcher's rule)
d = df.sort_values(['j', 't']); keep = np.zeros(len(d), bool); lj = -1; lx = -1
for i, (j, t, xx) in enumerate(zip(d.j.values, d.t.values, d.xd.values)):
    if j != lj: lj = j; lx = -1
    if t > lx: keep[i] = True; lx = xx
df = d[keep].sort_values(['t']).reset_index(drop=True)
P(f'X10 BUY trades (one per stock at a time): {len(df)}  stop-exits {int(df.st.sum())}')
# ---- lower-circuit detection at exit day
prevC = np.vstack([np.full((1, N), np.nan), Cff[:-1]])
def locked_down(t, j):
    c = C[t, j]; pc = prevC[t, j]; 
    if np.isnan(c) or np.isnan(pc): return False, False
    rc = c / pc - 1; lo = L[t, j] / pc - 1; op = O[t, j] / pc - 1
    band = [b for b in (0.05, 0.10, 0.20) if abs(rc + b) < 0.003]
    closed = bool(band) and c <= L[t, j] * 1.001
    openlock = bool(band) and abs(op + band[0]) < 0.003 and abs(rc + band[0]) < 0.003
    return closed, openlock
cl = np.zeros(len(df), bool); ol = np.zeros(len(df), bool)
for i in np.nonzero(df.st.values == 1)[0]:
    cl[i], ol[i] = locked_down(int(df.xd[i]), int(df.j[i]))
df['cl'] = cl; df['ol'] = ol
ex = df.xd.values
def nextopen(i):
    """exit at the open of the next session on which the stock does not open locked-limit-down (max 5 sessions)"""
    j = int(df.j[i]); t = int(df.xd[i]); k = 0
    while k < 5:
        t += 1
        while t < T and np.isnan(C[t, j]): t += 1
        if t >= T: return np.nan
        _, olk = locked_down(t, j); k += 1
        if not olk: return O[t, j]
    return O[t, j]
df['ret_adj'] = df.ret
for i in np.nonzero(cl)[0]:
    px = nextopen(i)
    if not np.isnan(px): df.loc[i, 'ret_adj'] = px / df.ep[i] - 1
df['ret_adj'] = np.minimum(df.ret_adj, df.ret) if False else df.ret_adj   # keep as computed (can be better or worse)
cost = 0.0025
for pn, (a, b) in (('IS', (t0, tsplit - 61)), ('OOS', (tsplit, T - 61)), ('FULL', (t0, T - 61))):
    m = (df.t >= a) & (df.t <= b); dd = df[m]; se = dd[dd.st == 1]
    P(f'{pn}: trades {len(dd)}, stop exits {len(se)} ({100*len(se)/len(dd):.1f}%); stop exits on a day the stock CLOSED limit-down: {int(se.cl.sum())} ({100*se.cl.mean():.1f}% of stop exits, {100*se.cl.sum()/len(dd):.2f}% of trades); opened locked at limit-down: {int(se.ol.sum())}')
    P(f'    expectancy net {100*(dd.ret-cost).mean():+.3f}%  ->  stops-on-limit-down exit at next non-locked open: {100*(dd.ret_adj-cost).mean():+.3f}%  (delta {100*(dd.ret_adj-dd.ret).mean():+.3f} pts; avg loss on affected trades: filled {100*se[se.cl].ret.mean():+.1f}% vs next-open {100*se[se.cl].ret_adj.mean():+.1f}%)')
# gap-through-stop days: how many stop exits happened at the open (gap) -> optimistic if stock gapped down by a lot
gp = df[(df.st == 1)].copy(); gp['gap_exit'] = [O[int(a), int(b)] <= (e[pos[int(c_), int(b)]] if False else 1e18) for a, b, c_ in zip(gp.xd, gp.j, gp.t)]
# stop exits where exit price < stop (gap through): realised loss worse than -3ATR
stopk = 3.0; atrv = ATR[df.t.values, df.j.values]; stoppx = df.ep.values - stopk * atrv
gt = (df.st.values == 1) & (df.ep.values * (1 + df.ret.values) < stoppx - 1e-9)
P(f'stop exits filled BELOW the stop (gap through) : {int(gt.sum())} of {int((df.st==1).sum())}; mean extra slippage vs stop {100*np.mean((df.ep.values*(1+df.ret.values)/stoppx-1)[gt]):.2f}%')
# ---- targets (claim 3): compare X10-like plans with/without 6/9 ATR targets on BUY signals  (researcher's T7: X9 vs X4 etc.) re-derived with our own simulator
import numba as nb
@nb.njit(cache=False)
def sim_tgt(ts, js, hold, stopk, tgtk, cexit, gapmax, O, H, L, C, ATR, SMA50):
    n = len(ts); T = C.shape[0]; ret = np.full(n, np.nan); xd = np.full(n, -1)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        a = ATR[t, j]
        if np.isnan(a): continue
        ep = O[t + 1, j]
        if np.isnan(ep): continue
        if gapmax >= 0 and ep > C[t, j] + gapmax * a: continue
        stop = ep - stopk * a if stopk > 0 else -1.0; tgt = ep + tgtk * a if tgtk > 0 else 1e18
        lastc = ep; px = np.nan; x = last; pend = False
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            ed = dd == t + 1
            if pend: px = O[dd, j]; x = dd; break
            if (not ed) and O[dd, j] <= stop: px = O[dd, j]; x = dd; break
            if L[dd, j] <= stop: px = stop; x = dd; break
            if (not ed) and O[dd, j] >= tgt: px = O[dd, j]; x = dd; break
            if H[dd, j] >= tgt: px = tgt; x = dd; break
            lastc = C[dd, j]
            if dd == last: px = C[dd, j]; x = dd; break
            if cexit == 1 and C[dd, j] < SMA50[dd, j]:
                if dd + 1 <= T - 1: pend = True
                else: px = C[dd, j]; x = dd; break
        if np.isnan(px): continue
        ret[i] = px / ep - 1; xd[i] = x
    return ret, xd
P('\n--- targets: per-trade expectancy net 0.25%, BUY signals, one trade per stock at a time (own simulator, same stop/exit rules)')
def nonover(tt, jj, rr, xx):
    d = pd.DataFrame({'t': tt, 'j': jj, 'ret': rr, 'xd': xx}).dropna(subset=['ret']).sort_values(['j', 't']); keep = np.zeros(len(d), bool); lj = -1; lx = -1
    for i, (j, t, x_) in enumerate(zip(d.j.values, d.t.values, d.xd.values)):
        if j != lj: lj = j; lx = -1
        if t > lx: keep[i] = True; lx = x_
    return d[keep]
res = []
for nm, (sk, tg, cx) in {'X10 (3ATR stop, SMA50 exit)': (3, 0, 1), '+ target 6ATR': (3, 6, 1), '+ target 9ATR': (3, 9, 1), '+ both-style: target 6ATR sells all': (3, 6, 1),
                         'X9 3ATR stop only': (3, 0, 0), 'X9 + target 6ATR': (3, 6, 0), 'X9 + target 9ATR': (3, 9, 0)}.items():
    rr, xx = sim_tgt(tb, jb, 60, float(sk), float(tg), cx, 0.5, O, H, L, C, ATR, SMA50)
    nd = nonover(tb, jb, rr, xx)
    for pn, (a, b) in (('IS', (t0, tsplit - 61)), ('OOS', (tsplit, T - 61))):
        q = nd[(nd.t >= a) & (nd.t <= b)]; net = q.ret.values - 0.0025
        res.append(f'{nm:38s} {pn:4s} n={len(q):5d} exp {100*net.mean():+.2f}%  median {100*np.median(net):+.2f}%  win {100*(net>0).mean():.0f}%  hold {np.mean(q.xd - q.t - 1):.0f}')
for s_ in res: P(s_)
# ---- split of OOS BUY plan expectancy by hole contamination of the signal date
P('\n--- X10 BUY plan expectancy by signal-date block (hole = 32 missing sessions before 2025-06-20)')
net = df.ret.values - 0.0025
blocks = {'2022-01..2025-01 (clean)': (tsplit, GAP - 61), 'fwd window spans hole': (GAP - 60, GAP - 1), 'features spans hole (to +252)': (GAP, GAP + 252), 'after': (GAP + 253, T - 61)}
for nm, (a, b) in blocks.items():
    m = (df.t >= a) & (df.t <= b); 
    if m.sum(): P(f'{nm:34s} {dates[a].date()}..{dates[min(b,T-1)].date()} n={int(m.sum()):5d} exp {100*net[m].mean():+.2f}% median {100*np.median(net[m]):+.2f}% win {100*(net[m]>0).mean():.0f}%')
# BUY excess 60d in the same blocks
fb = g('fb60'); RATED = U & ~np.isnan(S); mU = np.nanmean(np.where(RATED, fb, np.nan), 1); Bm = (S >= 85) & (C > SMA50) & ~UCL
dm = dayavg(Bm & RATED, fb - mU[:, None] - 0.0025)
for nm, (a, b) in blocks.items():
    if b > a: P(f'BUY excess 60d (net .25) {nm:34s}: {100*np.nanmean(dm[a:b+1]):+.2f}%  (t NW {nw_t(dm[a:b+1],60):+.1f}, {int(np.sum(~np.isnan(dm[a:b+1])))} days)')
tm = np.zeros(T, bool); tm[tsplit:GAP - 60] = True; tm[GAP + 253:T - 61] = True
P(f'BUY excess 60d, OOS excluding hole-touched windows: {100*np.nanmean(dm[tm]):+.2f}% (t {nw_t(dm[tm],60):+.1f}, {int(np.sum(~np.isnan(dm[tm])))} days)   [researcher OOS BUY no gate: +1.23 (t 1.10)]')
open(os.path.join(OUT, 'D_stops_hole.txt'), 'w').write('\n'.join(out))
