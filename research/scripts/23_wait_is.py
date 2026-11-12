"""Round 4, step 3b: inside the K3 top bands (>=70, >=85), do entry-timing / regime conditions change the forward excess?
IN-SAMPLE ONLY (signal days 2013-2021, purged). usage: python3 -I 23_wait_is.py <out_r4 dir>. Writes wait_conditions_IS.csv.
"""
import sys, os, numpy as np, pandas as pd
OUT = sys.argv[1]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl'))
dates, reg = M['dates'], M['reg']; T = len(dates)
g = lambda k: F[k].astype(np.float64)
W = pd.read_pickle(os.path.join(os.path.dirname(OUT.rstrip('/')), 'work', 'wide_adj.pkl')); Cc = W['c'].values; del W
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
def pct(x): return pd.DataFrame(np.where(U, x, np.nan)).rank(axis=1, pct=True, method='average').values
S = 100 * (pct(g('ram')) + pct(g('r6_1')) + pct(g('r12_1')) + pct(Cc / g('hi252')) + pct(Cc / g('lo252'))) / 5
sma50, sma200 = g('sma50'), g('sma200')
ext50 = Cc / sma50 - 1; off20 = 1 - Cc / g('hi20c'); rsi2 = g('rsi2'); r1 = g('ret1'); atrp = g('atr14') / Cc
br = reg['breadth50'].values[:, None] * np.ones((1, Cc.shape[1])); n50 = reg['n50'].values
n200 = (n50 > reg['n50_sma200'].values)[:, None] * np.ones((1, Cc.shape[1]), bool)
n50up = (n50 > reg['n50_sma50'].values)[:, None] * np.ones((1, Cc.shape[1]), bool)
conds = {'all': np.ones_like(S, bool), 'ext50>0.25': ext50 > 0.25, 'ext50 0.10-0.25': (ext50 > 0.10) & (ext50 <= 0.25), 'ext50<=0.10': ext50 <= 0.10,
         'below_sma50': Cc < sma50, 'below_sma200': Cc < sma200, 'off20<2%': off20 < 0.02, 'off20 3-8%': (off20 >= 0.03) & (off20 <= 0.08), 'off20>10%': off20 > 0.10,
         'rsi2<10': rsi2 < 10, 'rsi2>90': rsi2 > 90, 'day>+5%': r1 > 0.05, 'atr%>5': atrp > 0.05, 'atr%<2.5': atrp < 0.025,
         'breadth<25': br < 0.25, 'breadth25-50': (br >= 0.25) & (br < 0.5), 'breadth>=50': br >= 0.5,
         'n50<sma200': ~n200, 'n50>sma200': n200, 'n50<sma50': ~n50up, 'n50>sma50': n50up}
def nw_t(x, L):
    x = x[~np.isnan(x)]; n = len(x)
    if n < 30: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(L, n - 1) + 1): v += 2 * (1 - k / (L + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n)
rows = []
for Hh in (20, 60):
    r = np.where(U, g(f'fb{Hh}'), np.nan); m = np.nanmean(r, 1); X = r - 0.0025 - m[:, None]; R = r - 0.0025
    isd = np.zeros(T, bool); isd[t0:tsplit] = True; isd[np.arange(T) + Hh >= tsplit] = False
    for thr in (70, 85):
        for cn, cm in conds.items():
            msk = (S >= thr) & cm & isd[:, None] & ~np.isnan(X)
            cnt = msk.sum(1); dm = np.where(cnt > 0, np.where(msk, X, 0).sum(1) / np.maximum(cnt, 1), np.nan)
            dr = np.where(cnt > 0, np.where(msk, R, 0).sum(1) / np.maximum(cnt, 1), np.nan)
            rows.append(dict(H=Hh, score_ge=thr, cond=cn, n=int(msk.sum()), days=int((cnt > 0).sum()), avg_ex=100 * np.nanmean(dm), t=nw_t(dm, Hh),
                             avg_raw=100 * np.nanmean(dr), med_raw=100 * np.median(R[msk]) if msk.any() else np.nan, p05_raw=100 * np.percentile(R[msk], 5) if msk.any() else np.nan))
d = pd.DataFrame(rows); d.round(3).to_csv(os.path.join(OUT, 'wait_conditions_IS.csv'), index=False)
pd.set_option('display.width', 250); print(d.round(2).to_string())
