"""Round 4, step 2: single-ingredient tests.  usage: python3 -I 21_ingredients.py <out_r4 dir>
Universe U[D] = liquid (20d avg turnover >= 25 cr, unadjusted close >= 50) AND >= 252 own bars (SMA200 and 52w high defined).
excess_i(D,H) = r_i(D,H) - cost - mean_{k in U[D]} r_k(D,H)   (same entry convention; benchmark carries no cost)
Each group's statistic is computed on the DAILY cross-sectional mean (one number per signal day), t-stat = Newey-West (lag H).
IS = signal days 2013-01-01..2021-12-31 whose exit day is also <= 2021-12-31 (purged); OOS = signal days >= 2022-01-01.
Writes ingredients_IS.csv and ingredients_OOS.csv separately (composite design used IS only), plus regime_IS/OOS.csv.
Runtime about 2 min.
"""
import sys, os, numpy as np, pandas as pd
OUT = sys.argv[1]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl'))
dates, reg = M['dates'], M['reg']; T = len(dates)
g = lambda k: F[k].astype(np.float64)
liq = F['liq']; U = liq & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
HS = (10, 20, 40, 60); COST = 0.0025

def nw_t(x, L):
    x = x[~np.isnan(x)]; n = len(x)
    if n < 30: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(L, n - 1) + 1):
        v += 2 * (1 - k / (L + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n) if v > 0 else np.nan

# --- reconstruct close from features we need: load panel close directly
W = pd.read_pickle(os.path.join(os.path.dirname(OUT.rstrip('/')), 'work', 'wide_adj.pkl'))
Cc = W['c'].values; del W
sma50, sma150, sma200 = g('sma50'), g('sma150'), g('sma200')
feats = {}
feats['rs6'] = g('rs6'); feats['rs12'] = g('rs12'); feats['rsavg'] = g('rsavg'); feats['ram'] = g('ram')
feats['prox52'] = Cc / g('hi252'); feats['ext50'] = Cc / sma50 - 1; feats['tight'] = -g('tight'); feats['voldry'] = -g('voldry')
feats['lowvol126'] = -g('vol126'); feats['r21'] = g('r21'); feats['off20'] = 1 - Cc / g('hi20c'); feats['rsi2'] = g('rsi2'); feats['rsi5'] = g('rsi5')
feats['above52wlow'] = Cc / g('lo252') - 1
# conditions
c1 = (Cc > sma150) & (Cc > sma200); c2 = sma150 > sma200; c3 = sma200 > g('sma200_21'); c4 = (sma50 > sma150) & (sma50 > sma200)
c5 = Cc > sma50; c6 = Cc >= 1.30 * g('lo252'); c7 = Cc >= 0.75 * g('hi252')
ttc = (c1.astype(int) + c2 + c3 + c4 + c5 + c6 + c7).astype(float); ttc[~U] = np.nan
TT = (ttc == 7)
stage2 = (Cc > sma150) & (sma150 > g('sma150_20'))
stage2_early = stage2 & (g('minrel150_120') <= 1.0)
bo20 = (Cc > g('hi20h_prev')) & (g('vr50') >= 1.5)
bo_tight = bo20 & (np.r_[np.full((1, Cc.shape[1]), np.nan), g('tight')[:-1]] <= 0.8)
relidx6 = g('r6_1') > reg['n50_r6_1'].values[:, None]; relidx12 = g('r12_1') > reg['n50_r12_1'].values[:, None]
off20 = feats['off20']; rsi2 = feats['rsi2']
off20 = feats['off20']; rsi2 = feats['rsi2']; ext50 = feats['ext50']; prox52 = feats['prox52']
binaries = {'TT': TT, 'stage2': stage2, 'stage2_early': stage2_early, 'stage2_late': stage2 & ~stage2_early, 'bo20_vol': bo20, 'bo20_vol_tightbase': bo_tight,
            'beats_nifty_6_1': relidx6, 'beats_nifty_12_1': relidx12, 'TT_pullback3_8': TT & (off20 >= 0.03) & (off20 <= 0.08),
            'TT_rsi2_lt10': TT & (rsi2 < 10), 'TT_ext50_gt20': TT & (ext50 > 0.20), 'TT_near_high_lt5': TT & (prox52 >= 0.95)}

def excess(Hh, ent):
    r = g(f'f{ent}{Hh}'); r = np.where(U, r, np.nan)
    m = np.nanmean(r, axis=1)
    return r - COST - m[:, None], r - COST, m

def period_rows(Hh):
    isd = np.zeros(T, bool); isd[t0:tsplit] = True; isd[np.arange(T) + Hh >= tsplit] = False
    oos = np.zeros(T, bool); oos[tsplit:] = True
    return {'IS': isd, 'OOS': oos}

def describe(mask, X, R, per, Hh):
    out = {}
    Xm = np.where(mask, X, np.nan)
    with np.errstate(all='ignore'):
        cnt = (mask & ~np.isnan(X)).sum(1); dm = np.where(cnt > 0, np.nansum(Xm, 1) / np.maximum(cnt, 1), np.nan)
        rm = np.where(cnt > 0, np.nansum(np.where(mask, R, np.nan), 1) / np.maximum(cnt, 1), np.nan)
    for pn, pm in per.items():
        sel = mask & pm[:, None] & ~np.isnan(X); vals = X[sel]
        d = dm[pm]; n_days = int((~np.isnan(d)).sum())
        if len(vals) == 0: out[pn] = None; continue
        out[pn] = dict(n=int(len(vals)), days=n_days, avg_ex=100 * np.nanmean(d), t_nw=nw_t(d, Hh), avg_raw=100 * np.nanmean(rm[pm]),
                       med_ex=100 * np.median(vals), p25_ex=100 * np.percentile(vals, 25), p75_ex=100 * np.percentile(vals, 75),
                       hit_ex=100 * (vals > 0).mean()), dm
    return out

QQ = {}
for name, x in feats.items():
    pr = pd.DataFrame(np.where(U, x, np.nan)).rank(axis=1, pct=True, method='average').values
    QQ[name] = (np.ceil(pr * 5).astype(np.float32), np.ceil(pr * 10).astype(np.float32) if name in ('rsavg', 'prox52', 'ram') else None)
del feats
rows = {'IS': [], 'OOS': []}; spreads = {'IS': [], 'OOS': []}
for ent in ('b', 'a'):
    for Hh in HS:
        X, R, mU = excess(Hh, ent); per = period_rows(Hh)
        def emit(name, grp, mask):
            res = describe(mask, X, R, per, Hh)
            for pn in per:
                if res[pn] is None: continue
                st, dm = res[pn]
                rows[pn].append(dict(ingredient=name, group=grp, entry=ent, H=Hh, **st))
            return {pn: (res[pn][1] if res[pn] else None) for pn in per}
        for name, (q, dq) in QQ.items():
            dms = {}
            for k in range(1, 6): dms[k] = emit(name, f'Q{k}', q == k)
            for pn, pm in per.items():
                sp = dms[5][pn] - dms[1][pn]; sp = sp[pm]
                spreads[pn].append(dict(ingredient=name, entry=ent, H=Hh, q5_minus_q1=100 * np.nanmean(sp), t_nw=nw_t(sp, Hh)))
            if dq is not None:
                for k in range(1, 11): emit(name, f'D{k:02d}', dq == k)
        for k in range(0, 8): emit('tt_count', f'{k}of7', ttc == k)
        for name, b in binaries.items():
            emit(name, 'yes', b & U); emit(name, 'no', ~b & U)
        print(ent, Hh, flush=True)
for pn in ('IS', 'OOS'):
    pd.DataFrame(rows[pn]).round(4).to_csv(os.path.join(OUT, f'ingredients_{pn}.csv'), index=False)
    pd.DataFrame(spreads[pn]).round(4).to_csv(os.path.join(OUT, f'ingredient_spreads_{pn}.csv'), index=False)

# ---- regime: time-series test on the universe-average raw forward return (entry b, no cost)
rr = {'IS': [], 'OOS': []}
br = reg['breadth50'].values; n50 = reg['n50'].values
states = {'n50>sma50': n50 > reg['n50_sma50'].values, 'n50>sma200': n50 > reg['n50_sma200'].values,
          'breadth<25': br < 0.25, 'breadth25-50': (br >= 0.25) & (br < 0.5), 'breadth50-75': (br >= 0.5) & (br < 0.75), 'breadth>=75': br >= 0.75}
for Hh in HS:
    X, R, mU = excess(Hh, 'b'); per = period_rows(Hh)
    ttd = describe(TT & U, X, R, per, Hh)  # not used directly
    TTx = np.where(TT & U, X, np.nan)
    with np.errstate(all='ignore'): tt_daily = np.nanmean(TTx, 1)
    for nm, s in states.items():
        for val in (True, False):
            if nm.startswith('breadth') and not val: continue
            for pn, pm in per.items():
                m = pm & (s == val) & ~np.isnan(mU)
                if m.sum() < 30: continue
                rr[pn].append(dict(state=nm, value=val, H=Hh, days=int(m.sum()), univ_avg_raw=100 * mU[m].mean(), t_nw=nw_t(mU[m], Hh),
                                   TT_avg_excess=100 * np.nanmean(tt_daily[m]), TT_t=nw_t(tt_daily[m], Hh)))
for pn in rr: pd.DataFrame(rr[pn]).round(4).to_csv(os.path.join(OUT, f'regime_{pn}.csv'), index=False)
print('done')
