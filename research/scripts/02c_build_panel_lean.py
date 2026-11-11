"""Lean panel build (the original 02_build_panel.py ran out of memory at the final concat).
Same logic, but (1) reuses work/renames_detected.csv produced by 02_build_panel.py, (2) keeps only symbols whose
20-day average turnover ever reached >= 20 crore (the backtest filter is 25 crore), (3) writes wide matrices.
 * Symbols present in eod2_data: split/bonus-adjusted OHLC from eod2; turnover from raw bhavcopy.
 * Symbols absent from eod2 (delisted/merged): raw bhavcopy with heuristic split adjustment.
usage: python3 -I 02c_build_panel_lean.py <eod2 daily dir> <raw.pkl> <work dir>
"""
import os, sys, numpy as np, pandas as pd
EOD, RAW, OUT = sys.argv[1:4]
START = pd.Timestamp('2010-06-10')
raw = pd.read_pickle(RAW)
raw = raw[(raw.close > 0) & (raw.vol > 0)].copy()
ren = pd.read_csv(os.path.join(OUT, 'renames_detected.csv'), parse_dates=['from', 'to'])
newsym = raw.symbol.values.copy(); sv = raw.symbol.values; dv = raw.date.values
for old, g in ren.groupby('old'):
    m_old = sv == old
    for new, a, b in zip(g.new, g['from'], g['to']):
        newsym[m_old & (dv >= np.datetime64(a)) & (dv <= np.datetime64(b))] = new
raw['symbol'] = newsym
raw = raw.drop_duplicates(['symbol', 'date'])
raw = raw[raw.date.dt.dayofweek < 5].sort_values(['symbol', 'date'])
mx = raw.groupby('symbol').to_lacs.transform(lambda s: s.rolling(20).mean().max())
raw = raw[mx >= 2000.0]
print('liquid-ever symbols', raw.symbol.nunique(), 'rows', len(raw), flush=True)
rawg = {s: g for s, g in raw.groupby('symbol')}
parts = []; surv = set(); resid = []
for f in sorted(os.listdir(EOD)):
    if not f.endswith('.csv'): continue
    name = f[:-4].upper()
    if name not in rawg: continue
    p = os.path.join(EOD, f)
    with open(p) as fh: hdr = fh.readline()
    if 'P/E' in hdr: continue
    d = pd.read_csv(p, usecols=['Date', 'Open', 'High', 'Low', 'Close', 'Volume'], parse_dates=['Date'])
    d = d[d.Date >= START].dropna(subset=['Close']).drop_duplicates('Date').sort_values('Date')
    d = d[(d.Close > 0) & (d.Volume > 0) & (d.Date.dt.dayofweek < 5)]
    if len(d) < 30: continue
    d.columns = ['date', 'o', 'h', 'l', 'c', 'vraw']
    r = rawg[name]
    d = d.merge(r[['date', 'close', 'to_lacs']], on='date', how='left')
    fac = (d['close'] / d['c']).bfill().ffill().fillna(1.0)
    d['to_cr'] = np.where(d.to_lacs.notna(), d.to_lacs / 100.0, d.vraw * d.c * fac / 1e7)
    d['v'] = d.vraw * fac; d['sym'] = name; d['src'] = 'eod2'
    parts.append(d[['sym', 'date', 'o', 'h', 'l', 'c', 'v', 'to_cr', 'src']]); surv.add(name)
RATIOS = np.array([1/2, 1/3, 1/4, 1/5, 1/6, 1/8, 1/10, 1/20, 2/3, 3/5, 2/5, 3/4, 2, 5, 10])
adj = []; nd = 0
for name, r in rawg.items():
    if name in surv or len(r) < 30: continue
    gap = (r.open / r.close.shift(1)).values
    f = np.ones(len(r))
    for i in np.where((gap < 0.70) | (gap > 1.6))[0]:
        g = gap[i]; k = RATIOS[np.argmin(np.abs(np.log(RATIOS / g)))]
        if abs(np.log(k / g)) < 0.08:
            f[:i] *= (1 / k); adj.append((name, r.date.iloc[i].date(), round(g, 3), round(k, 3)))
    parts.append(pd.DataFrame({'sym': name, 'date': r.date.values, 'o': r.open.values / f, 'h': r.high.values / f,
        'l': r.low.values / f, 'c': r.close.values / f, 'v': r.vol.values * f, 'to_cr': r.to_lacs.values / 100.0, 'src': 'raw'}))
    nd += 1
pd.DataFrame(adj, columns=['sym', 'date', 'gap', 'ratio']).to_csv(os.path.join(OUT, 'heuristic_adjustments_lean.csv'), index=False)
panel = pd.concat(parts, ignore_index=True)
panel = panel[(panel.o > 0) & (panel.h > 0) & (panel.l > 0) & (panel.c > 0)]
panel['h'] = panel[['o', 'h', 'c']].max(axis=1); panel['l'] = panel[['o', 'l', 'c']].min(axis=1)
panel = panel.sort_values(['sym', 'date']).reset_index(drop=True)
print('eod2 symbols', len(surv), 'raw-only (delisted/unmatched)', nd, 'heuristic adj', len(adj))
# residual unexplained overnight gaps (demergers etc.)
pc = panel.groupby('sym').c.shift(1); g = panel.o / pc
res = panel[(g < 0.75) | (g > 1.4)].assign(gap=g[(g < 0.75) | (g > 1.4)])
res.to_csv(os.path.join(OUT, 'residual_gaps.csv'), index=False)
print('residual gaps', len(res))
wide = {k: panel.pivot(index='date', columns='sym', values=k).astype('float64') for k in ['o', 'h', 'l', 'c', 'v', 'to_cr']}
pd.to_pickle(wide, os.path.join(OUT, 'wide.pkl'))
pd.to_pickle(panel.groupby('sym').src.first(), os.path.join(OUT, 'src.pkl'))
idx = {}
for nm in ['nifty 50', 'nifty 500']:
    d = pd.read_csv(os.path.join(EOD, nm + '.csv'), usecols=['Date', 'Open', 'High', 'Low', 'Close'], parse_dates=['Date'])
    d = d.drop_duplicates('Date').set_index('Date').sort_index(); idx[nm.upper()] = d[d.index.dayofweek < 5]
pd.to_pickle(idx, os.path.join(OUT, 'index.pkl'))
print(wide['c'].shape, panel.date.min(), panel.date.max()); print(panel.groupby('src').sym.nunique())
