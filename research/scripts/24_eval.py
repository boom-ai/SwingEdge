"""Round 4, step 4: FINAL score (fixed from IS work in 22/23) -> band tables IS/OOS/full, concentration, exit schemes.
usage: python3 -I 24_eval.py <work dir> <out_r4 dir>          runtime about 3 min
FINAL SCORE (K3): S = round(100 * mean(pRAM, pR6, pR12, pPROX, pLOW), 1) where each p is the average-rank percentile across U[D].
LABELS: NOT RATED if not in U; AVOID S < 55; BUY S >= 85 and C > SMA50 and Nifty > SMA200(Nifty) (regime gate; 'BUYng' = no gate);
        WAIT otherwise.
Writes: bands_*.csv, score_bands.json (partial; trade plan added by 25), concentration_*.csv, exits_*.csv, signals.pkl
"""
import sys, os, json, numpy as np, pandas as pd, numba as nb
WORK, OUT = sys.argv[1:3]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl'))
dates, reg, syms = M['dates'], M['reg'], M['syms']; T = len(dates)
g = lambda k: F[k].astype(np.float64)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C = (W[k].values for k in ('o', 'h', 'l', 'c')); del W
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
HZ = 60
def pct(x): return pd.DataFrame(np.where(U, x, np.nan)).rank(axis=1, pct=True, method='average').values
S = np.round(100 * (pct(g('ram')) + pct(g('r6_1')) + pct(g('r12_1')) + pct(C / g('hi252')) + pct(C / g('lo252'))) / 5, 1)
S = np.where(U, S, np.nan)
sma50 = g('sma50'); n50 = reg['n50'].values; gate = n50 > reg['n50_sma200'].values
above50 = C > sma50
BUYng = (S >= 85) & above50 & ~F['uclock']
BUY = BUYng & gate[:, None]
AVOID = S < 55
np.savez_compressed(os.path.join(OUT, 'score.npz'), S=S.astype(np.float32), BUY=BUY, BUYng=BUYng)

def nw_t(x, Lg):
    x = x[~np.isnan(x)]; n = len(x)
    if n < 30: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(Lg, n - 1) + 1): v += 2 * (1 - k / (Lg + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n)
def periods(Hh):
    isd = np.zeros(T, bool); isd[t0:tsplit] = True; isd[np.arange(T) + Hh >= tsplit] = False
    oos = np.zeros(T, bool); oos[tsplit:] = True; full = np.zeros(T, bool); full[t0:] = True
    return {'IS': isd, 'OOS': oos, 'FULL': full}
def dstats(msk, X, Hh, pm):
    msk = msk & pm[:, None] & ~np.isnan(X)
    cnt = msk.sum(1); dm = np.where(cnt > 0, np.where(msk, X, 0).sum(1) / np.maximum(cnt, 1), np.nan)
    v = X[msk]
    if len(v) == 0: return dict(n=0)
    return dict(n=int(len(v)), days=int((cnt > 0).sum()), daily_avg=100 * np.nanmean(dm), t_nw=nw_t(dm, Hh), avg=100 * v.mean(), win=100 * (v > 0).mean(),
                median=100 * np.median(v), p25=100 * np.percentile(v, 25), p75=100 * np.percentile(v, 75), p05=100 * np.percentile(v, 5))
BANDS = [(0, 40), (40, 55), (55, 70), (70, 85), (85, 101)]
groups = {f'{a}-{min(b, 100) - (1 if b <= 100 else 0)}': (S >= a) & (S < b) for a, b in BANDS}
groups['BUY(gated)'] = BUY; groups['BUY(no gate)'] = BUYng; groups['>=85 & below SMA50'] = (S >= 85) & ~above50
groups['>=85 & Nifty<SMA200'] = (S >= 85) & ~gate[:, None]; groups['ALL U'] = U.copy()
rows = []
for ent in ('b', 'a'):
    for Hh in (20, 40, 60):
        r = np.where(U, g(f'f{ent}{Hh}'), np.nan); mU = np.nanmean(r, 1)
        for cost in (0.0025, 0.0040):
            for kind, X in (('raw', r - cost), ('excess', r - cost - mU[:, None])):
                for pn, pm in periods(Hh).items():
                    for gn, gm in groups.items():
                        rows.append(dict(entry=ent, H=Hh, cost=cost * 100, kind=kind, period=pn, group=gn, **dstats(gm, X, Hh, pm)))
        print('bands', ent, Hh, flush=True)
B = pd.DataFrame(rows); B.round(3).to_csv(os.path.join(OUT, 'bands_all.csv'), index=False)

# ---------------- score_bands.json (chosen horizon, entry b primary, cost 0.25) ----------------
def jrow(r): return {k: (None if (isinstance(v, float) and np.isnan(v)) else (round(v, 2) if isinstance(v, float) else v)) for k, v in r.items()
                     if k in ('n', 'days', 'win', 'avg', 'median', 'p25', 'p75', 'p05', 'daily_avg', 't_nw')}
J = {'horizonDays': HZ, 'entry': 'next session open after the signal close', 'costRoundTripPct': 0.25,
     'notes': 'n counts overlapping stock-days (one per stock per day). avg/median/p25/p75/p05 pooled over stock-days; daily_avg and t_nw are the '
              'mean of daily cross-sectional averages and its Newey-West t (lag 60). excess = stock return - cost - equal-weight average of the liquid '
              'universe (same days, no cost). p05 is shown as worstPct (5th percentile).',
     'bands': {}}
for gn in list(groups):
    J['bands'][gn] = {}
    for pn in ('FULL', 'OOS', 'IS'):
        for kind in ('raw', 'excess'):
            r = B[(B.entry == 'b') & (B.H == HZ) & (B.cost == 0.25) & (B.kind == kind) & (B.period == pn) & (B.group == gn)].iloc[0].to_dict()
            d = jrow(r); d['worstPct'] = d.pop('p05', None); d['winPct'] = d.pop('win', None); d['avgPct'] = d.pop('avg', None); d['medianPct'] = d.pop('median', None)
            J['bands'][gn][f'{pn}_{kind}'] = d
J['thresholds'] = {'AVOID_below': 55, 'BUY_at_or_above': 85, 'BUY_requires': ['close > SMA50', 'Nifty50 close > its SMA200', 'not upper-circuit locked'],
                   'WAIT': 'eligible, score >= 55 and not BUY'}
json.dump(J, open(os.path.join(OUT, 'score_bands.json'), 'w'), indent=1)

# ---------------- concentration: per year, size proxy, regime ----------------
Hh = HZ; r = np.where(U, g(f'fb{Hh}'), np.nan); mU = np.nanmean(r, 1); X = r - 0.0025 - mU[:, None]
yrs = dates.year.values; crow = []
to20 = g('to20'); torank = pd.DataFrame(np.where(U, to20, np.nan)).rank(axis=1, ascending=False).values
for gn in ('85-100', 'BUY(gated)', 'BUY(no gate)', '0-39'):
    gm = groups[gn]
    for y in range(2013, 2027):
        pm = (yrs == y); crow.append(dict(group=gn, cut='year', value=y, univ_raw=100 * np.nanmean(mU[pm]), **dstats(gm, X, Hh, pm)))
    for nm, sm in (('top100 turnover', torank <= 100), ('rank 101-300', (torank > 100) & (torank <= 300)), ('rank >300', torank > 300)):
        for pn, pm in periods(Hh).items():
            crow.append(dict(group=gn, cut='size:' + nm, value=pn, **dstats(gm & sm, X, Hh, pm)))
    br = reg['breadth50'].values
    for nm, sm in (('n50>sma200', gate), ('n50<sma200', ~gate), ('breadth<50', br < 0.5), ('breadth>=50', br >= 0.5)):
        for pn, pm in periods(Hh).items():
            crow.append(dict(group=gn, cut='regime:' + nm, value=pn, **dstats(gm, X, Hh, pm & sm)))
pd.DataFrame(crow).round(3).to_csv(os.path.join(OUT, 'concentration.csv'), index=False)
print('concentration done', flush=True)

# ---------------- exit schemes on BUY signals ----------------
ATR = g('atr14'); SMA50 = sma50
E20 = np.full_like(C, np.nan)
for j in range(C.shape[1]):
    idx = np.where(~np.isnan(C[:, j]))[0]
    if len(idx) >= 20:
        e = pd.Series(C[idx, j]).ewm(span=20, adjust=False).mean().to_numpy(copy=True); e[:19] = np.nan; E20[idx, j] = e
@nb.njit(cache=False)
def sim(ts, js, mode_b, hold, stopk, tgtR, trail, cexit, half, gapmax, O, H, L, C, ATR, SMA50, E20):
    """stopk: initial stop = ep - stopk*ATR[t] (0 = none); tgtR: target = ep + tgtR*stopk*ATR (0 = none);
    trail: chandelier multiple (0 = none) -> stop_dd = max(stop, HH(entry..dd-1) - trail*ATR[dd-1]);
    cexit: 1 = close < SMA50, 2 = close < EMA20 -> exit at that close (mode a) or next open (mode b);
    half: sell half at 2R (= tgt1) and move stop to entry, remainder to tgtR target / time. gapmax: skip if open > C[t] + gapmax*ATR (mode b, <0 = off).
    Exit at close of t+hold at the latest. Stop is checked before target in a bar; gaps through stop exit at the open."""
    n = len(ts); T = C.shape[0]
    ret = np.full(n, np.nan); xd = np.full(n, -1); mae = np.full(n, np.nan); hit1 = np.zeros(n); hit2 = np.zeros(n); stopped = np.zeros(n)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        a = ATR[t, j]
        if np.isnan(a): continue
        if mode_b:
            ep = O[t + 1, j]
            if np.isnan(ep): continue
            if gapmax >= 0 and ep > C[t, j] + gapmax * a: continue
        else:
            ep = C[t, j]
        R = stopk * a
        stop = ep - R if stopk > 0 else -1.0
        t1 = ep + 2.0 * R if half else 1e18
        tgt = ep + tgtR * R if (tgtR > 0 and stopk > 0) else 1e18
        hh = ep; lastc = ep; px = np.nan; x = last; done_half = False; book = 0.0; lo = ep; pend_open = False
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            entry_day = mode_b and dd == t + 1
            if pend_open:
                px = O[dd, j]; x = dd; break
            if trail > 0 and not entry_day:
                ns = hh - trail * ATR[dd - 1, j]
                if ns > stop: stop = ns
            if (not entry_day) and O[dd, j] <= stop: px = O[dd, j]; x = dd; stopped[i] = 1; lo = min(lo, O[dd, j]); break
            lo = min(lo, L[dd, j])
            if L[dd, j] <= stop: px = stop; x = dd; stopped[i] = 1; break
            if half and not done_half:
                if (not entry_day) and O[dd, j] >= t1: book = O[dd, j]; done_half = True; hit1[i] = 1; stop = max(stop, ep)
                elif H[dd, j] >= t1: book = t1; done_half = True; hit1[i] = 1; stop = max(stop, ep)
            if (not entry_day) and O[dd, j] >= tgt: px = O[dd, j]; x = dd; hit2[i] = 1; break
            if H[dd, j] >= tgt: px = tgt; x = dd; hit2[i] = 1; break
            hh = max(hh, H[dd, j]); lastc = C[dd, j]
            if dd == last: px = C[dd, j]; x = dd; break
            trig = (cexit == 1 and C[dd, j] < SMA50[dd, j]) or (cexit == 2 and C[dd, j] < E20[dd, j])
            if trig:
                if mode_b and dd + 1 <= T - 1: pend_open = True
                else: px = C[dd, j]; x = dd; break
        if np.isnan(px): continue
        r = px / ep - 1
        if half and done_half: r = 0.5 * (book / ep - 1) + 0.5 * r
        ret[i] = r; xd[i] = x; mae[i] = lo / ep - 1
    return ret, xd, mae, hit1, hit2, stopped
SCH = {'X0 time 60d': (0, 0, 0, 0, False), 'X1 2ATR stop, 2R tgt': (2, 2, 0, 0, False), 'X2 2ATR stop, 3R tgt': (2, 3, 0, 0, False),
       'X3 3ATR stop, 2R tgt': (3, 2, 0, 0, False), 'X4 3ATR stop, 3R tgt': (3, 3, 0, 0, False), 'X5 chandelier 3ATR': (3, 0, 3, 0, False),
       'X6 close<SMA50': (0, 0, 0, 1, False), 'X7 close<EMA20': (0, 0, 0, 2, False), 'X8 3ATR stop, half at 2R + BE stop, rest 3R': (3, 3, 0, 0, True),
       'X9 3ATR stop only': (3, 0, 0, 0, False), 'X10 3ATR stop + close<SMA50': (3, 0, 0, 1, False)}
Oa, Ha, La, Ca = (np.ascontiguousarray(x, dtype=np.float64) for x in (O, H, L, C))
erows = []; sig_store = {}
for gname, sig in (('BUY(gated)', BUY), ('BUY(no gate)', BUYng)):
    ts, js = np.nonzero(sig); k = ts >= t0; ts, js = ts[k], js[k]
    for mode_b in (True, False):
        for sn, (sk, tg, tr, ce, hf) in SCH.items():
            for gapmax in ((-1.0, 0.5) if sn.startswith('X8') or sn.startswith('X0') or sn.startswith('X9') else (-1.0,)):
                ret, xd, mae, h1, h2, st = sim(ts, js, mode_b, HZ, float(sk), float(tg), float(tr), ce, hf, gapmax, Oa, Ha, La, Ca, ATR, SMA50, E20)
                df = pd.DataFrame({'t': ts, 'j': js, 'ret': ret, 'xd': xd, 'mae': mae, 'h1': h1, 'h2': h2, 'st': st, 'S': S[ts, js]}).dropna(subset=['ret'])
                # one trade per stock at a time
                df = df.sort_values(['j', 't']); keep = np.zeros(len(df), bool); lj = -1; lx = -1
                for ii, (j, t, x) in enumerate(zip(df.j.values, df.t.values, df.xd.values)):
                    if j != lj: lj = j; lx = -1
                    if t > lx: keep[ii] = True; lx = x
                df = df[keep].sort_values(['t', 'S'], ascending=[True, False])
                sig_store[(gname, mode_b, sn, gapmax)] = df
                for cost in (0.0025, 0.0040):
                    for pn, (a, b) in (('IS', (t0, tsplit - HZ - 1)), ('OOS', (tsplit, T - 1)), ('FULL', (t0, T - 1))):
                        d = df[(df.t >= a) & (df.t <= b)]; net = d.ret.values - cost
                        if len(net) == 0: continue
                        hold = (d.xd - d.t).values - (1 if mode_b else 0)
                        dmean = d.assign(net=net).groupby('t').net.mean()
                        dm_full = np.full(T, np.nan); dm_full[dmean.index.values] = dmean.values
                        erows.append(dict(signals=gname, entry='b' if mode_b else 'a', scheme=sn, gapmax=gapmax, cost=cost * 100, period=pn, n=len(net),
                                          win=100 * (net > 0).mean(), exp=100 * net.mean(), median=100 * np.median(net), p05=100 * np.percentile(net, 5),
                                          worst=100 * net.min(), hold=hold.mean(), exp_per_day=100 * net.mean() / max(hold.mean(), 1),
                                          pf=net[net > 0].sum() / max(-net[net < 0].sum(), 1e-9), t_daily=nw_t(dm_full[a:b + 1], HZ),
                                          hit_t1=100 * d.h1.mean(), hit_t2=100 * d.h2.mean(), stopped=100 * d.st.mean()))
        print('exits', gname, mode_b, flush=True)
pd.DataFrame(erows).round(3).to_csv(os.path.join(OUT, 'exits_all.csv'), index=False)
pd.to_pickle(sig_store, os.path.join(OUT, 'trades_store.pkl'))
print('done')
