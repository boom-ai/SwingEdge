"""Review C: false-negative hunt, pre-registered in out_r4_review/PREREG.md. usage: python3 -I 42_variants.py   (~6-10 min, 2 procs)"""
import sys, os, time, numpy as np, pandas as pd, numba as nb
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '40_common.py')).read())
from r4lib import sim as sim_r4, portfolio, pstats
from multiprocessing import Pool

@nb.njit(cache=False)
def sim2(ts, js, hold, stopk, stoppct, cexit, gapmax, O, H, L, C, ATR, SMA50):
    """copy of r4lib.sim (entry = next open, mode b) + optional percentage stop; no targets."""
    n = len(ts); T = C.shape[0]
    ret = np.full(n, np.nan); xd = np.full(n, -1); epx = np.full(n, np.nan); stopped = np.zeros(n)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        a = ATR[t, j]
        if np.isnan(a): continue
        ep = O[t + 1, j]
        if np.isnan(ep): continue
        if gapmax >= 0 and ep > C[t, j] + gapmax * a: continue
        if stoppct > 0: stop = ep * (1 - stoppct)
        elif stopk > 0: stop = ep - stopk * a
        else: stop = -1.0
        lastc = ep; px = np.nan; x = last; pend_open = False
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            entry_day = dd == t + 1
            if pend_open:
                px = O[dd, j]; x = dd; break
            if (not entry_day) and O[dd, j] <= stop: px = O[dd, j]; x = dd; stopped[i] = 1; break
            if L[dd, j] <= stop: px = stop; x = dd; stopped[i] = 1; break
            lastc = C[dd, j]
            if dd == last: px = C[dd, j]; x = dd; break
            if cexit == 1 and C[dd, j] < SMA50[dd, j]:
                if dd + 1 <= T - 1: pend_open = True
                else: px = C[dd, j]; x = dd; break
        if np.isnan(px): continue
        ret[i] = px / ep - 1; xd[i] = x; epx[i] = ep
    return ret, xd, epx, stopped

Cff = pd.DataFrame(C).ffill().values
Z = np.zeros_like(C)
BUY = (S >= 85) & (C > SMA50) & ~UCL
CAND = U & (C > SMA50) & ~UCL & ~np.isnan(ATR)
CAND[:t0] = False; CAND[T - 1:] = False
ts_c, js_c = np.nonzero(CAND); pos = -np.ones((T, N), np.int32); pos[ts_c, js_c] = np.arange(len(ts_c))
PLANS = {'X10': dict(hold=60, stopk=3.0, stoppct=0.0, cexit=1, gap=0.5), 'X0': dict(hold=60, stopk=0.0, stoppct=0.0, cexit=0, gap=-1.0),
         'X0s5': dict(hold=60, stopk=5.0, stoppct=0.0, cexit=0, gap=-1.0), 'X0p20': dict(hold=60, stopk=0.0, stoppct=0.20, cexit=0, gap=-1.0)}
TAB = {}
for nm, p in PLANS.items():
    r, x, e, st = sim2(ts_c, js_c, p['hold'], p['stopk'], p['stoppct'], p['cexit'], p['gap'], O, H, L, C, ATR, SMA50)
    TAB[nm] = (r, x, e, st)
# --- verification against the researcher's simulator on BUY signals
tb, jb = np.nonzero(BUY & CAND); 
for nm, (sk, cx, gp) in (('X10', (3.0, 1, 0.5)), ('X0', (0.0, 0, -1.0))):
    r0, x0, e0, *_ = sim_r4(tb, jb, True, 60, sk, 0.0, 0.0, cx, False, gp, O, H, L, C, ATR, SMA50, Z)
    k = pos[tb, jb]; r1, x1, e1, _ = TAB[nm]
    a, b = r0, r1[k]; ok = ~(np.isnan(a) & np.isnan(b)); print('verify', nm, len(a), 'nan-pattern equal', bool((np.isnan(a) == np.isnan(b)).all()), 'max |ret diff|', np.nanmax(np.abs(a - b)), 'xd equal', bool((x0[~np.isnan(a)] == x1[k][~np.isnan(a)]).all()))
rank_to = pd.DataFrame(np.where(U, TO20, np.nan)).rank(axis=1, ascending=False).values
breadth = reg['breadth50'].values
atrp = ATR / C
VOLS = {'score': S, 'lowvol': -VOL126, 'lowatr': -atrp}
V = {  # id: (label, signal mask, plan, slots, ranking)
 'V0': ('baseline: BUY>=85, X10, 4 slots, top score', BUY, 'X10', 4, 'score'),
 'V1': ('rank lowest 126d volatility', BUY, 'X10', 4, 'lowvol'),
 'V2': ('rank lowest ATR%', BUY, 'X10', 4, 'lowatr'),
 'V3': ('turnover rank 101-300 only', BUY & (rank_to > 100) & (rank_to <= 300), 'X10', 4, 'score'),
 'V4': ('turnover rank 1-100 only', BUY & (rank_to <= 100), 'X10', 4, 'score'),
 'V5': ('8 slots', BUY, 'X10', 8, 'score'),
 'V6': ('12 slots', BUY, 'X10', 12, 'score'),
 'V7': ('plain 60-session hold, 8 slots', BUY, 'X0', 8, 'score'),
 'V8': ('60-session hold + 5 ATR stop, 8 slots', BUY, 'X0s5', 8, 'score'),
 'V9': ('60-session hold + 20% stop, 8 slots', BUY, 'X0p20', 8, 'score'),
 'V10': ('BUY threshold 70, X10, 4 slots', (S >= 70) & (C > SMA50) & ~UCL, 'X10', 4, 'score'),
 'V11': ('breadth>=50% only, X10, 4 slots', BUY & (breadth >= 0.5)[:, None], 'X10', 4, 'score')}
# null A filter per variant (candidate pool for random stocks)
NA_FILT = {'V3': (rank_to > 100) & (rank_to <= 300), 'V4': (rank_to <= 100), 'V11': np.repeat((breadth >= 0.5)[:, None], N, 1)}
PER = {'IS': (t0, tsplit - 1), 'OOS': (tsplit, T - 61), 'OOSr': (tsplit, T - 1)}
def frame(rows, plan, score):
    r, x, e, st = TAB[plan]
    df = pd.DataFrame({'t': ts_c[rows], 'j': js_c[rows], 'ret': r[rows], 'xd': x[rows], 'ep': e[rows], 'score': score}).dropna(subset=['ret'])
    df['ed'] = df.t + 1
    return df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
def run(df, slots, pn, off, cost=0.0025):
    a, b = PER[pn]; a2 = a + off
    d = df[(df.t >= a2) & (df.t <= b)]
    eq, ntr, util, _ = portfolio(d, a2, b, cost, slots, Cff, dates)
    st, yr = pstats(eq); return st['cagr'], st['mdd'], st['worst_year'], ntr
def variant_task(vid):
    lab, mask, plan, slots, rk = V[vid]
    tt, jj = np.nonzero(mask & CAND); rows = pos[tt, jj]; df = frame(rows, plan, VOLS[rk][tt, jj])
    out = []
    for pn in ('IS', 'OOS', 'OOSr'):
        if pn == 'OOSr' and vid != 'V0': continue
        for off in range(0, 60, 3):
            c, m, w, n = run(df, slots, pn, off); out.append(dict(vid=vid, period=pn, off=off, cagr=c, mdd=m, worst_year=w, trades=n, nsig=len(df)))
    # null B: random rank among own signals
    rng = np.random.default_rng(5000 + int(vid[1:]))
    for s in range(150):
        d2 = df.copy(); d2['score'] = rng.random(len(d2)); d2 = d2.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
        off = int(rng.integers(0, 20)) * 3
        for pn in ('IS', 'OOS'):
            c, m, w, n = run(d2, slots, pn, off); out.append(dict(vid=vid, period=pn, off=off, cagr=c, mdd=m, worst_year=w, trades=n, null='B', seed=s))
    # null A: random liquid above-SMA50 stock, 20 per day
    fl = NA_FILT.get(vid); cm = CAND if fl is None else (CAND & fl)
    cands = [np.nonzero(cm[t])[0] for t in range(T)]
    for s in range(150):
        rng = np.random.default_rng(9000 + 100 * int(vid[1:]) + s); tl = []; jl = []
        for t in range(t0, T - 1):
            c_ = cands[t]
            if len(c_): p = rng.choice(c_, size=min(20, len(c_)), replace=False); tl.append(np.full(len(p), t)); jl.append(p)
        tt, jj = np.concatenate(tl), np.concatenate(jl); df2 = frame(pos[tt, jj], plan, rng.random(len(tt))); off = int(rng.integers(0, 20)) * 3
        for pn in ('IS', 'OOS'):
            c, m, w, n = run(df2, slots, pn, off); out.append(dict(vid=vid, period=pn, off=off, cagr=c, mdd=m, worst_year=w, trades=n, null='A', seed=s))
    print('done', vid, time.strftime('%X'), flush=True)
    return out
if __name__ == '__main__':
    t_start = time.time()
    with Pool(2) as pool: res = pool.map(variant_task, list(V), chunksize=1)
    R = pd.DataFrame([r for rr in res for r in rr]); R.round(3).to_csv(os.path.join(OUT, 'C_variants_raw.csv'), index=False)
    # benchmarks over identical windows/offsets
    n50 = reg['n50']; r1 = C / np.vstack([np.full((1, N), np.nan), C[:-1]]) - 1
    Uprev = np.vstack([np.zeros((1, N), bool), U[:-1]]); ew = pd.Series(np.nanmean(np.where(Uprev, r1, np.nan), axis=1), index=dates).fillna(0)
    B = []
    for pn, (a, b) in PER.items():
        for off in range(0, 60, 3):
            a2 = a + off; s = n50.iloc[a2:b + 1]; s = s / s.iloc[0]
            e = (1 + ew.iloc[a2 + 1:b + 1]).cumprod(); e = pd.concat([pd.Series([1.0], index=[dates[a2]]), e])
            B.append(dict(period=pn, off=off, bench='Nifty', **pstats(s)[0])); B.append(dict(period=pn, off=off, bench='EW', **pstats(e)[0]))
    pd.DataFrame(B).round(2).to_csv(os.path.join(OUT, 'C_bench.csv'), index=False)
    print('total min', (time.time() - t_start) / 60)
