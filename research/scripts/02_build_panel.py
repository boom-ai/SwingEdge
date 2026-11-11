"""Build the research panel.
 * Surviving symbols: split/bonus-ADJUSTED OHLC from BennyThadikaran/eod2_data (daily/*.csv); turnover and
   volume-adjustment factor from the raw bhavcopy (tilak999/NSE-Data-bank).
 * Symbols missing from eod2 (delisted / merged / renamed-and-unmatched): raw bhavcopy, with heuristic split adjustment.
Output: work/panel.pkl (long: sym,date,o,h,l,c,v,to_cr,src), work/index.pkl
"""
import os, sys, numpy as np, pandas as pd
EOD = sys.argv[1]; RAW = sys.argv[2]; OUT = sys.argv[3]
START = pd.Timestamp('2010-06-10')
raw = pd.read_pickle(RAW)
raw = raw[(raw.close > 0) & (raw.vol > 0)].copy()
print('raw', raw.shape)

# ---------- load eod2 ----------
eod = {}; idx = {}
for f in sorted(os.listdir(EOD)):
    if not f.endswith('.csv'): continue
    p = os.path.join(EOD, f)
    with open(p) as fh: hdr = fh.readline()
    name = f[:-4].upper()
    if 'P/E' in hdr:
        if name in ('NIFTY 50', 'NIFTY 500', 'NIFTY 200', 'NIFTY MIDCAP 100', 'NIFTY SMALLCAP 100'):
            d = pd.read_csv(p, usecols=['Date', 'Open', 'High', 'Low', 'Close'], parse_dates=['Date'])
            idx[name] = d.drop_duplicates('Date').set_index('Date').sort_index()
        continue
    d = pd.read_csv(p, usecols=['Date', 'Open', 'High', 'Low', 'Close', 'Volume'], parse_dates=['Date'])
    d = d[d.Date >= START].dropna(subset=['Close']).drop_duplicates('Date').sort_values('Date')
    d = d[(d.Close > 0) & (d.Volume > 0)]
    if len(d) < 30: continue
    eod[name] = d.reset_index(drop=True)
print('eod2 stock files', len(eod), 'indices', {k: (v.index[0].date(), v.index[-1].date(), len(v)) for k, v in idx.items()})

# ---------- map renamed symbols: eod2 keeps old history under the NEW name, raw has the OLD name ----------
raw_syms = set(raw.symbol.unique())
raw['key'] = list(zip(raw.date.values, raw.vol.values.astype('int64')))
key2sym = raw.groupby('key')['symbol'].agg(list).to_dict() if False else None
# build lookup lazily via dict of date -> {vol: [syms]}
bydate = {}
for d, v, s in zip(raw.date.values, raw.vol.values.astype('int64'), raw.symbol.values):
    bydate.setdefault(d, {}).setdefault(v, []).append(s)
raw_dates = set(bydate.keys())
raw_idx = raw.set_index(['symbol', 'date']).sort_index()
rename = {}  # (old_sym) -> list of (new_sym, date_from, date_to)
ren_log = []
for name, d in eod.items():
    have = set(raw_idx.loc[name].index.values) if name in raw_syms else set()
    um = d[[(x in raw_dates) and (x not in have) for x in d.Date.values]]
    guard = 0
    while len(um) > 20 and guard < 6:
        guard += 1
        # vote on the most recent 15 unmatched rows
        votes = {}
        for dt_, v in zip(um.Date.values[-15:], um.Volume.values[-15:].astype('int64')):
            for s in bydate.get(dt_, {}).get(v, []):
                votes[s] = votes.get(s, 0) + 1
        if not votes: break
        old, n = max(votes.items(), key=lambda kv: kv[1])
        if n < 10 or old == name: break
        od = set(raw_idx.loc[old].index.values)
        seg = um[[x in od for x in um.Date.values]]
        if len(seg) < 10: break
        rename.setdefault(old, []).append((name, seg.Date.min(), seg.Date.max()))
        ren_log.append((old, name, seg.Date.min().date(), seg.Date.max().date(), len(seg)))
        have |= od
        um = d[[(x in raw_dates) and (x not in have) for x in d.Date.values]]
print('rename segments found', len(ren_log)); print(ren_log[:15])
pd.DataFrame(ren_log, columns=['old', 'new', 'from', 'to', 'n']).to_csv(os.path.join(OUT, 'renames_detected.csv'), index=False)

# apply renames to raw
raw = raw.drop(columns='key')
newsym = raw.symbol.values.copy()
for old, segs in rename.items():
    m_old = raw.symbol.values == old
    for new, a, b in segs:
        m = m_old & (raw.date.values >= np.datetime64(a)) & (raw.date.values <= np.datetime64(b))
        newsym[m] = new
raw['symbol'] = newsym
raw = raw.drop_duplicates(['symbol', 'date'])
rawg = {s: g.sort_values('date') for s, g in raw.groupby('symbol')}

# ---------- surviving symbols ----------
parts = []; fac_bad = []
for name, d in eod.items():
    d = d.rename(columns={'Date': 'date', 'Open': 'o', 'High': 'h', 'Low': 'l', 'Close': 'c', 'Volume': 'vraw'})
    r = rawg.get(name)
    if r is not None:
        d = d.merge(r[['date', 'close', 'to_lacs']], on='date', how='left')
    else:
        d['close'] = np.nan; d['to_lacs'] = np.nan
    fac = (d['close'] / d['c'])
    # factor is piecewise constant; smooth out rounding noise, fill gaps (later value first: adjustments apply to the past)
    fac = fac.bfill().ffill().fillna(1.0)
    d['fac'] = fac
    d['to_cr'] = np.where(d.to_lacs.notna(), d.to_lacs / 100.0, d.vraw * d.c * fac / 1e7)
    d['v'] = d.vraw * fac
    d['sym'] = name; d['src'] = 'eod2'
    parts.append(d[['sym', 'date', 'o', 'h', 'l', 'c', 'v', 'to_cr', 'src']])
surv = set(eod.keys())

# ---------- delisted / unmatched symbols from raw, heuristic split adjustment ----------
RATIOS = np.array([1/2, 1/3, 1/4, 1/5, 1/6, 1/8, 1/10, 1/20, 2/3, 3/5, 2/5, 3/4, 2, 5, 10])
nd = 0; adj_log = []
last_raw = raw.date.max()
for name, r in rawg.items():
    if name in surv or len(r) < 30: continue
    r = r.copy()
    prevc = r.close.shift(1)
    gap = r.open / prevc
    f = np.ones(len(r))
    for i in np.where((gap < 0.70) | (gap > 1.6))[0]:
        g = gap.iloc[i]
        k = RATIOS[np.argmin(np.abs(np.log(RATIOS / g)))]
        if abs(np.log(k / g)) < 0.08:
            f[:i] *= (1 / k)   # earlier prices divided by 1/k
            adj_log.append((name, r.date.iloc[i].date(), round(g, 3), round(k, 3)))
    d = pd.DataFrame({'sym': name, 'date': r.date.values, 'o': r.open.values / f, 'h': r.high.values / f,
                      'l': r.low.values / f, 'c': r.close.values / f, 'v': r.vol.values * f,
                      'to_cr': r.to_lacs.values / 100.0, 'src': 'raw'})
    parts.append(d); nd += 1
print('raw-only (delisted/unmatched) symbols', nd, 'heuristic adjustments', len(adj_log))
pd.DataFrame(adj_log, columns=['sym', 'date', 'gap', 'ratio']).to_csv(os.path.join(OUT, 'heuristic_adjustments.csv'), index=False)

panel = pd.concat(parts, ignore_index=True)
panel = panel[(panel.o > 0) & (panel.h > 0) & (panel.l > 0) & (panel.c > 0)]
# repair inconsistent bars
panel['h'] = panel[['o', 'h', 'c']].max(axis=1); panel['l'] = panel[['o', 'l', 'c']].min(axis=1)
# drop weekend special sessions (muhurat / mock sessions: thin and not tradable for the scanner)
wk = panel.date.dt.dayofweek >= 5
print('weekend-session rows dropped', wk.sum(), sorted(set(panel.date[wk].dt.date))[:40])
panel = panel[~wk].sort_values(['sym', 'date']).reset_index(drop=True)
panel.to_pickle(os.path.join(OUT, 'panel.pkl'))
for k in idx: idx[k] = idx[k][idx[k].index.dayofweek < 5]
pd.to_pickle(idx, os.path.join(OUT, 'index.pkl'))
print(panel.shape, panel.sym.nunique(), panel.date.min(), panel.date.max())
print(panel.groupby('src').sym.nunique())
