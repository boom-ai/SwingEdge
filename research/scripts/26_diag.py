"""Round 4 diagnostics (no new variants chosen from this): trades actually taken by the 4/8-slot portfolios (plan X10, no gate, entry b)
vs all BUY signals; per-trade results by score sub-band inside BUY; yearly portfolio vs benchmarks; beta of the portfolio to Nifty / EW universe.
usage: python3 -I 26_diag.py <work dir> <out_r4 dir>   writes diag_taken.csv, diag_subband.csv, diag_beta.csv. Runtime < 1 min.
"""
import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from r4lib import sim, portfolio, pstats
WORK, OUT = sys.argv[1:3]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl')); SC = np.load(os.path.join(OUT, 'score.npz'))
dates, reg = M['dates'], M['reg']; T = len(dates)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C = (np.ascontiguousarray(W[k].values, dtype=np.float64) for k in ('o', 'h', 'l', 'c')); del W
ATR = F['atr14'].astype(np.float64); SMA50 = F['sma50'].astype(np.float64)
S = SC['S'].astype(np.float64); BUY = SC['BUYng']; U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
Cff = pd.DataFrame(C).ffill().values
ts, js = np.nonzero(BUY); k = ts >= t0; ts, js = ts[k], js[k]
ret, xd, ep, t1, t2, st = sim(ts, js, True, 60, 3.0, 0.0, 0.0, 1, False, 0.5, O, H, L, C, ATR, SMA50, np.zeros_like(C))
df = pd.DataFrame({'t': ts, 'j': js, 'ret': ret, 'xd': xd, 'ep': ep, 'score': S[ts, js], 'atrp': ATR[ts, js] / C[ts, js]}).dropna(subset=['ret'])
df['ed'] = df.t + 1; df = df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
rows = []
for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, T - 1))):
    d = df[(df.t >= a) & (df.t <= b)].reset_index(drop=True)
    rows.append(dict(period=pn, set='all BUY signals (overlapping)', n=len(d), exp=100 * (d.ret - .0025).mean(), win=100 * (d.ret > .0025).mean(),
                     hold=(d.xd - d.ed).mean(), atrp=100 * d.atrp.mean(), score=d.score.mean()))
    for slots in (4, 8):
        eq, ntr, util, taken = portfolio(d, a, b, 0.0025, slots, Cff, dates)
        tk = d.iloc[taken]
        rows.append(dict(period=pn, set=f'taken by {slots}-slot portfolio', n=len(tk), exp=100 * (tk.ret - .0025).mean(), win=100 * (tk.ret > .0025).mean(),
                         hold=(tk.xd - tk.ed).mean(), atrp=100 * tk.atrp.mean(), score=tk.score.mean()))
pd.DataFrame(rows).round(3).to_csv(os.path.join(OUT, 'diag_taken.csv'), index=False); print(pd.DataFrame(rows).round(2).to_string())
# sub-bands inside BUY
sb = []
for pn, (a, b) in (('IS', (t0, tsplit - 61)), ('OOS', (tsplit, T - 1))):
    d = df[(df.t >= a) & (df.t <= b)]
    for lo_, hi_ in ((85, 90), (90, 95), (95, 101)):
        x = d[(d.score >= lo_) & (d.score < hi_)]
        sb.append(dict(period=pn, band=f'{lo_}-{min(hi_, 100)}', n=len(x), exp=100 * (x.ret - .0025).mean(), median=100 * (x.ret - .0025).median(), atrp=100 * x.atrp.mean()))
pd.DataFrame(sb).round(3).to_csv(os.path.join(OUT, 'diag_subband.csv'), index=False); print(pd.DataFrame(sb).round(2).to_string())
# beta of the chosen portfolio (4 and 8 slots, FULL equity curve) to Nifty and to the EW universe
eqs = pd.read_pickle(os.path.join(OUT, 'portfolio_equity.pkl'))
r1 = C / np.vstack([np.full((1, C.shape[1]), np.nan), C[:-1]]) - 1
Uprev = np.vstack([np.zeros((1, C.shape[1]), bool), U[:-1]])
ew = pd.Series(np.nanmean(np.where(Uprev, r1, np.nan), axis=1), index=dates).fillna(0)
nf = reg['n50'].pct_change()
bt = []
for slots in (4, 8):
    e = eqs[('SCORE', 'nogate', True, 'X10', 0.0025, slots)]; pr = e.pct_change().dropna()
    for pn, msk in (('IS', pr.index < '2022-01-01'), ('OOS', pr.index >= '2022-01-01'), ('FULL', pr.index >= '2000-01-01')):
        y = pr[msk]; xs = pd.DataFrame({'nifty': nf.reindex(y.index), 'ew': ew.reindex(y.index)}).fillna(0)
        for nm in ('nifty', 'ew'):
            x = xs[nm].values; b_ = np.cov(y.values, x)[0, 1] / np.var(x, ddof=1); al = (y.values.mean() - b_ * x.mean()) * 250
            bt.append(dict(slots=slots, period=pn, vs=nm, beta=b_, alpha_ann_pct=100 * al, corr=np.corrcoef(y.values, x)[0, 1]))
        # two-factor: nifty + (ew - nifty) = small/mid-cap tilt
        X = np.column_stack([np.ones(len(y)), xs.nifty.values, xs.ew.values - xs.nifty.values]); co = np.linalg.lstsq(X, y.values, rcond=None)[0]
        bt.append(dict(slots=slots, period=pn, vs='nifty + (ew-nifty)', beta=co[1], beta2=co[2], alpha_ann_pct=100 * co[0] * 250))
pd.DataFrame(bt).round(3).to_csv(os.path.join(OUT, 'diag_beta.csv'), index=False); print(pd.DataFrame(bt).round(3).to_string())
