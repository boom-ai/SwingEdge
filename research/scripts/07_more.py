"""Follow-up studies. usage: python3 -I 07_more.py <work dir> <out dir>
1) do the app's SELL/TRIM conditions predict weak forward returns?  2) 2-6 week momentum holds  3) more 4-5 day ideas."""
import sys, os, numpy as np, pandas as pd
WORK, OUT = sys.argv[1:3]
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
o, h, l, c, v, to, craw = (W[k] for k in ['o', 'h', 'l', 'c', 'v', 'to_cr', 'craw'])
dates = c.index; T, N = c.shape
n50 = pd.read_pickle(os.path.join(WORK, 'nifty50.pkl')).Close.reindex(dates).ffill()
START, SPLIT = pd.Timestamp('2013-01-01'), pd.Timestamp('2022-01-01')
IS = (dates >= START) & (dates < SPLIT); OOS = dates >= SPLIT
def sma(x, n): return x.rolling(n, min_periods=int(n * 0.9)).mean()
pc = c.shift(1); ret1 = c / pc - 1
s5, s20, s50, s200 = sma(c, 5), sma(c, 20), sma(c, 50), sma(c, 200)
tr = np.maximum(h - l, np.maximum((h - pc).abs(), (l - pc).abs()))
atr = tr.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
d = c.diff()
def rsi(n):
    ag = d.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n * 3).mean(); al = (-d).clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n * 3).mean()
    return 100 - 100 / (1 + ag / al.replace(0, 1e-12))
rsi14, rsi2 = rsi(14), rsi(2)
vavg = v.shift(1).rolling(20, min_periods=15).mean(); vr = v / vavg
ibs = ((c - l) / (h - l).replace(0, np.nan)).fillna(0.5)
elig = (to.rolling(20, min_periods=15).mean() >= 25) & (craw >= 50) & s200.notna()
band = ((ret1 - 0.05).abs() < 0.003) | ((ret1 - 0.10).abs() < 0.003) | ((ret1 - 0.20).abs() < 0.003)
buyable = elig & ~((c >= h * 0.9995) & band)
nret = lambda k: n50.shift(-k) / n50 - 1
def fwd(k, entry='c'):
    if entry == 'c': return c.shift(-k) / c - 1
    return c.shift(-k) / o.shift(-1) - 1
lines = []
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); lines.append(s)

# ------------------------------------------------------------------ 1) SELL / TRIM conditions
P('## 1. Do the SELL / TRIM conditions predict weak returns? (liquid stocks; excess = stock minus average liquid stock, same days)')
hc22 = c.rolling(22, min_periods=18).max()
trail = hc22 - 3 * atr
rel63 = (c / c.shift(63) - 1).sub(n50 / n50.shift(63) - 1, axis=0)
conds = {
 'SELL-A close<200DMA & 50DMA<200DMA': (c < s200) & (s50 < s200),
 'SELL-A first day only': ((c < s200) & (s50 < s200)) & ~((pc < s200.shift(1)) & (s50.shift(1) < s200.shift(1))),
 'SELL-B close below trail (22d high close - 3 ATR), first day': (c < trail) & (pc >= trail.shift(1)),
 'SELL-B on volume >1.5x': (c < trail) & (pc >= trail.shift(1)) & (vr > 1.5),
 'SELL-C 63d rel. return < -15% & close<50DMA': (rel63 < -0.15) & (c < s50),
 'SELL-D close<200DMA (first day)': (c < s200) & (pc >= s200.shift(1)),
 'TRIM-A >20% above 50DMA': c > s50 * 1.20,
 'TRIM-B RSI14>80': rsi14 > 80,
 'TRIM-C up>8% on vol>3x after >30% 3-month run': (ret1 > 0.08) & (vr > 3) & (c / c.shift(63) > 1.30),
 'HOLD-ref close>50DMA>200DMA': (c > s50) & (s50 > s200),
}
rows = []
for k in (20, 60):
    f = fwd(k); base = f.where(elig)
    bm = base.mean(axis=1)
    ex = f.sub(bm, axis=0)
    for nm, m in conds.items():
        m = m & elig
        for lab, per in (('IS', IS), ('OOS', OOS)):
            mm = m.values & per[:, None]
            x = f.values[mm]; e = ex.values[mm]
            x = x[~np.isnan(x)]; e = e[~np.isnan(e)]
            # day-equal-weighted excess (robust to clustering)
            de = ex.where(m)[per].mean(axis=1).dropna()
            rows.append(dict(cond=nm, k=k, per=lab, n=len(x), mean=x.mean() * 100 if len(x) else np.nan, excess=e.mean() * 100 if len(e) else np.nan,
                             day_eq_excess=de.mean() * 100 if len(de) else np.nan, t=de.mean() / (de.std() / np.sqrt(len(de) / k)) if len(de) > 50 else np.nan,
                             pct_neg=(x < 0).mean() * 100 if len(x) else np.nan))
R1 = pd.DataFrame(rows); R1.to_csv(os.path.join(OUT, 'sell_rules.csv'), index=False)
P(R1.round(2).to_string(index=False))

# ------------------------------------------------------------------ 2) longer-hold momentum portfolios
P('\n## 2. Momentum portfolios, 2-6 week holds (equal weight, cost 0.25% per round trip on traded value)')
mom = {'6m (126d skip 5)': c.shift(5) / c.shift(131) - 1, '12-1m': c.shift(21) / c.shift(252) - 1, '3m (63d skip 5)': c.shift(5) / c.shift(68) - 1}
mom['6m/vol'] = mom['6m (126d skip 5)'] / (ret1.rolling(126, min_periods=100).std() * np.sqrt(126))
reg200 = (n50 > n50.rolling(200).mean()).values
cv = c.values; ev = (buyable & (c > s200)).values
def run_port(score, hold, topn, regime, cost=0.0025, per=None, buffer=2.0):
    sc = score.values
    idx = np.where(dates >= START)[0]
    eq = 1.0; w = {}; curve = []; turn = 0.0
    last = idx[0]
    for t in idx:
        # mark to market
        if w:
            r = 0.0
            for j, wt in w.items():
                a, b = cv[t - 1, j], cv[t, j]
                if a > 0 and b > 0: r += wt * (b / a - 1)
            eq *= 1 + r
        if (t - idx[0]) % hold == 0:
            if regime and not reg200[t]: tgt = []
            else:
                s = np.where(ev[t], sc[t], np.nan)
                order = np.argsort(-np.nan_to_num(s, nan=-1e9))
                ok = [j for j in order[: int(topn * buffer)] if not np.isnan(s[j])]
                keep = [j for j in w if j in ok]               # hold existing names while still in the top buffer
                tgt = keep + [j for j in ok if j not in keep][: max(0, topn - len(keep))]
                tgt = tgt[:topn]
            neww = {j: 1.0 / topn for j in tgt}
            tv = sum(abs(neww.get(j, 0) - w.get(j, 0)) for j in set(neww) | set(w)) / 2
            eq *= 1 - tv * cost * 1.0     # 0.25% round trip == charged once per unit of one-way turnover pair
            turn += tv; w = neww
        curve.append(eq)
    s = pd.Series(curve, index=dates[idx])
    return s, turn
def stats(s):
    yrs = (s.index[-1] - s.index[0]).days / 365.25
    cagr = (s.iloc[-1] / s.iloc[0]) ** (1 / yrs) - 1
    dd = (s / s.cummax() - 1).min()
    return cagr * 100, dd * 100
rows = []; curves = {}
for mn, sc in mom.items():
    for hold in (10, 21, 42):
        for topn in (5, 10):
            for regime in (False, True):
                s, turn = run_port(sc, hold, topn, regime)
                a = stats(s[s.index < SPLIT]); b = stats(s[s.index >= SPLIT])
                yr = s.resample('YE').last().pct_change().mul(100).round(1)
                rows.append(dict(mom=mn, hold=hold, top=topn, regime=regime, is_cagr=a[0], is_dd=a[1], oos_cagr=b[0], oos_dd=b[1],
                                 y2023=yr.get(pd.Timestamp('2023-12-31')), y2024=yr.get(pd.Timestamp('2024-12-31')), y2025=yr.get(pd.Timestamp('2025-12-31')), y2026=yr.get(pd.Timestamp('2026-12-31')), turnover_py=turn / ((s.index[-1] - s.index[0]).days / 365.25)))
                curves[(mn, hold, topn, regime)] = s
R2 = pd.DataFrame(rows); R2.to_csv(os.path.join(OUT, 'momentum.csv'), index=False)
P(R2.round(1).to_string(index=False))
nn = n50[dates >= START]
P('Nifty50 price index: IS CAGR/DD %.1f/%.1f  OOS %.1f/%.1f' % (*stats(nn[nn.index < SPLIT]), *stats(nn[nn.index >= SPLIT])))
ew = (c.pct_change().where(elig.shift(1).fillna(False))).mean(axis=1).fillna(0)
ewc = (1 + ew[dates >= START]).cumprod()
P('Equal-weight liquid universe (no costs): IS %.1f/%.1f  OOS %.1f/%.1f' % (*stats(ewc[ewc.index < SPLIT]), *stats(ewc[ewc.index >= SPLIT])))
ny = nn.resample('YE').last().pct_change().mul(100).round(1)
P('Nifty by year:', {k.year: v for k, v in ny.items() if k.year >= 2022})
pd.to_pickle(curves, os.path.join(OUT, 'mom_curves.pkl'))

# ------------------------------------------------------------------ 3) more 4-5 day ideas
P('\n## 3. More 4-5 day ideas (net of 0.25%; "top4/day" = the 4 best-ranked signals each day, averaged per day)')
up = c > s200
gap = o / pc - 1
breadth = (c > s50).where(elig).mean(axis=1)
n50_50 = n50 > n50.rolling(50).mean()
hi250 = c.rolling(250, min_periods=200).max(); hi10 = c.shift(1).rolling(10).max()
rng = h - l; nr7 = rng <= rng.rolling(7).min()
rng5 = (h.rolling(5).max() - l.rolling(5).min()) / c
dom = pd.Series(dates.day, index=dates); month_end = pd.Series(dates.month, index=dates) != pd.Series(dates.month, index=dates).shift(-1)
m126 = c.shift(5) / c.shift(131) - 1
ideas = {
 'G1 gap-down >=2% in uptrend, closes above open': (up & (gap <= -0.02) & (c > o), -gap),
 'G2 gap-down >=3%, close in top 30% of range': (up & (gap <= -0.03) & (ibs >= 0.7), -gap),
 'H1 NR7 then close above 10-day high': (up & nr7.shift(1).fillna(False) & (c > hi10), vr),
 'H2 tight 5-day range (<5%) within 3% of 52w high, up close': (up & (rng5.shift(1) < 0.05) & (c >= hi250 * 0.97) & (c > pc), m126),
 'I1 washout: breadth<25% and stock RSI2<10 above 200DMA': (up & (rsi2 < 10)).mul(breadth < 0.25, axis=0),
 'I2 breadth thrust: breadth crosses above 40% from <25% in 10d; buy leaders': ((up & (m126.where(elig).rank(axis=1, pct=True) >= 0.8)).mul((breadth > 0.40) & (breadth.rolling(10).min() < 0.25) & (breadth.shift(1) <= 0.40), axis=0)),
 'J1 turn of month: buy leaders at last close of month': ((up & (m126.where(elig).rank(axis=1, pct=True) >= 0.8)).mul(month_end.values, axis=0)),
 'K1 earnings-like gap: gap up >=4%, vol >=3x, close>open, close in top 30%': (up & (gap >= 0.04) & (vr >= 3) & (c > o) & (ibs >= 0.7), vr),
 'K2 same, entered after a 2-day pause (day 3 close)': ((up & (gap >= 0.04) & (vr >= 3) & (c > o)).shift(2).fillna(False) & (c > o.shift(2)), vr.shift(2)),
 'L1 RSI2<5 above 200DMA AND Nifty RSI2<10 (market also oversold)': (up & (rsi2 < 5)).mul(((lambda x: 100 - 100 / (1 + x.diff().clip(lower=0).ewm(alpha=.5, adjust=False).mean() / (-x.diff()).clip(lower=0).ewm(alpha=.5, adjust=False).mean().replace(0, 1e-12)))(n50) < 10).values, axis=0),
 'M1 3 down days into 50DMA (within 2%), leader': (up & (c < pc) & (pc < c.shift(2)) & (c.shift(2) < c.shift(3)) & ((c / s50 - 1).abs() < 0.02) & (m126.where(elig).rank(axis=1, pct=True) >= 0.7), m126),
 'BASE any liquid stock above 200DMA': (up, m126 * 0),
}
rows = []
for nm, it in ideas.items():
    m, sc = it if isinstance(it, tuple) else (it, -rsi2)
    m = (m & buyable).fillna(False)
    for hold in (4, 5):
        for ent in ('c', 'o'):
            f = fwd(hold, ent) - 0.0025
            x = f.where(m)
            rk = sc.where(m).rank(axis=1, ascending=False)
            top = f.where(m & (rk <= 4))
            for lab, per in (('IS', IS), ('OOS', OOS)):
                a = x[per].stack().dropna(); td = top[per].mean(axis=1).dropna(); de = x[per].mean(axis=1).dropna()
                rows.append(dict(idea=nm, hold=hold, entry='close' if ent == 'c' else 'next open', per=lab, n=len(a), days=len(de), avg=a.mean() * 100 if len(a) else np.nan,
                                 win=(a > 0).mean() * 100 if len(a) else np.nan, day_eq=de.mean() * 100 if len(de) else np.nan, top4_day=td.mean() * 100 if len(td) else np.nan,
                                 t_top4=td.mean() / (td.std() / np.sqrt(len(td))) if len(td) > 20 else np.nan))
            if hold == 5 and ent == 'c':
                yy = top[OOS].mean(axis=1).dropna()
                rows[-1]['oos_by_year'] = str({y: round(g.mean() * 100, 2) for y, g in yy.groupby(yy.index.year)})
R3 = pd.DataFrame(rows); R3.to_csv(os.path.join(OUT, 'ideas.csv'), index=False)
pd.set_option('display.width', 250); pd.set_option('display.max_colwidth', 90)
P(R3[(R3.hold == 5)].drop(columns=['hold']).round(2).to_string(index=False))
open(os.path.join(OUT, 'results.txt'), 'w').write('\n'.join(lines))
