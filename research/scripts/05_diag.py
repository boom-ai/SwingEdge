"""Diagnostics: (1) trade-weighted vs day-weighted mean (clustered signals), (2) mean of the top-4-ranked signals per day,
(3) overnight gap. usage: python3 -I 05_diag.py <work> <out>"""
import sys, os, glob, numpy as np, pandas as pd
WORK, OUT = sys.argv[1:3]
rows = []
for f in sorted(glob.glob(os.path.join(OUT, 'trades_*.pkl'))):
    d = pd.read_pickle(f); nm = os.path.basename(f)[7:-4]
    d = d[d.date >= '2013-01-01']; d['net'] = d.ret - 0.0025
    for pn, x in (('IS', d[d.date < '2022-01-01']), ('OOS', d[d.date >= '2022-01-01'])):
        a = x[x.acc]; top = x.sort_values(['t', 'score'], ascending=[True, False]).groupby('t').head(4)
        dm = a.groupby('t').net.mean()
        rows.append(dict(strat=nm, period=pn, n=len(a), signal_days=a.t.nunique(), trade_wtd_avg=100 * a.net.mean(), day_wtd_avg=100 * dm.mean(),
                         day_wtd_t=dm.mean() / dm.std() * np.sqrt(len(dm)), top4_avg=100 * top.net.mean(), top4_n=len(top),
                         share_trades_top10pct_days=a.groupby('t').size().sort_values(ascending=False).head(max(1, a.t.nunique() // 10)).sum() / max(1, len(a))))
pd.DataFrame(rows).round(3).to_csv(os.path.join(OUT, 'diag.csv'), index=False)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); c, o = W['c'], W['o']
el = (W['to_cr'].rolling(20, min_periods=15).mean() >= 25) & (W['craw'] >= 50)
g = (o.shift(-1) / c - 1).where(el)[c.index >= '2013-01-01'].stack()
print('overnight gap open(D+1)/close(D)-1 over liquid stock-days: mean %.3f%% median %.3f%% n %d' % (100 * g.mean(), 100 * g.median(), len(g)))
