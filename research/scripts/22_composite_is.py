"""Round 4, step 3a: candidate composites evaluated on IN-SAMPLE data only (signal days 2013-2021, purged).
usage: python3 -I 22_composite_is.py <out_r4 dir>.  Prints band tables; writes composite_candidates_IS.csv.  Runtime ~1 min.
Candidates (all equal / integer weights; components in [0,1]):
  K1 = 100 * (TTcount/7 + ramPct) / 2
  K2 = 100 * (TTcount/7 + ramPct + rs12Pct + prox52Pct) / 4
  K3 = 100 * (ramPct + rs6Pct + rs12Pct + prox52Pct + low52Pct) / 5     (pure cross-sectional ranks)
  K4 = 100 * (TTcount/7 + ramPct + prox52Pct) / 3
  K5 = ramPct * 100 alone (single-ingredient reference)
"""
import sys, os, numpy as np, pandas as pd
OUT = sys.argv[1]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl'))
dates, reg = M['dates'], M['reg']; T = len(dates)
g = lambda k: F[k].astype(np.float64)
W = pd.read_pickle(os.path.join(os.path.dirname(OUT.rstrip('/')), 'work', 'wide_adj.pkl')); Cc = W['c'].values; del W
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
sma50, sma150, sma200 = g('sma50'), g('sma150'), g('sma200')
ttc = ((Cc > sma150) & (Cc > sma200)).astype(float) + (sma150 > sma200) + (sma200 > g('sma200_21')) + ((sma50 > sma150) & (sma50 > sma200)) \
      + (Cc > sma50) + (Cc >= 1.30 * g('lo252')) + (Cc >= 0.75 * g('hi252'))
def pct(x): return pd.DataFrame(np.where(U, x, np.nan)).rank(axis=1, pct=True, method='average').values
ramP, rs6P, rs12P = pct(g('ram')), pct(g('r6_1')), pct(g('r12_1'))
proxP = pct(Cc / g('hi252')); lowP = pct(Cc / g('lo252'))
K = {'K1': 100 * (ttc / 7 + ramP) / 2, 'K2': 100 * (ttc / 7 + ramP + rs12P + proxP) / 4,
     'K3': 100 * (ramP + rs6P + rs12P + proxP + lowP) / 5, 'K4': 100 * (ttc / 7 + ramP + proxP) / 3, 'K5': 100 * ramP}
def nw_t(x, L):
    x = x[~np.isnan(x)]; n = len(x)
    if n < 30: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(L, n - 1) + 1): v += 2 * (1 - k / (L + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n)
BANDS = [(0, 40), (40, 55), (55, 70), (70, 85), (85, 101)]
br = reg['breadth50'].values
rows = []
for Hh in (20, 40, 60):
    r = np.where(U, g(f'fb{Hh}'), np.nan); m = np.nanmean(r, 1); X = r - 0.0025 - m[:, None]
    isd = np.zeros(T, bool); isd[t0:tsplit] = True; isd[np.arange(T) + Hh >= tsplit] = False
    for kn, s in K.items():
        s = np.where(U, s, np.nan)
        for (a, b) in BANDS:
            for regn, rm in (('all', np.ones(T, bool)), ('breadth>=50', br >= 0.5), ('breadth<50', br < 0.5)):
                msk = (s >= a) & (s < b) & isd[:, None] & rm[:, None] & ~np.isnan(X)
                cnt = msk.sum(1); dm = np.where(cnt > 0, np.where(msk, X, 0).sum(1) / np.maximum(cnt, 1), np.nan)
                vals = X[msk]
                rows.append(dict(comp=kn, band=f'{a}-{min(b,100)}', regime=regn, H=Hh, n=len(vals), per_day=cnt[isd & rm].mean(),
                                 avg_ex=100 * np.nanmean(dm), t_nw=nw_t(dm[isd & rm], Hh), med_ex=100 * np.median(vals) if len(vals) else np.nan))
        print(kn, Hh, flush=True)
d = pd.DataFrame(rows); d.round(3).to_csv(os.path.join(OUT, 'composite_candidates_IS.csv'), index=False)
pd.set_option('display.width', 250)
print(d.pivot_table(index=['comp', 'band'], columns=['regime', 'H'], values='avg_ex').round(2).to_string())
print(d.pivot_table(index=['comp', 'band'], columns=['regime', 'H'], values='t_nw').round(1).to_string())
print(d[d.regime == 'all'].pivot_table(index=['comp', 'band'], columns='H', values='per_day').round(1).to_string())
