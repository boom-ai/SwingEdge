"""Robustness of the two survivors from 07 (washout dip, turn of month). usage: python3 -I 08_verify.py <work dir>"""
import sys, os, numpy as np, pandas as pd
WORK = sys.argv[1]
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
o, h, l, c, v, to, craw = (W[k] for k in ['o', 'h', 'l', 'c', 'v', 'to_cr', 'craw'])
dates = c.index
START, SPLIT = pd.Timestamp('2013-01-01'), pd.Timestamp('2022-01-01')
IS = (dates >= START) & (dates < SPLIT); OOS = dates >= SPLIT
sma = lambda x, n: x.rolling(n, min_periods=int(n * 0.9)).mean()
pc = c.shift(1); ret1 = c / pc - 1; s50, s200 = sma(c, 50), sma(c, 200)
d = c.diff(); ag = d.clip(lower=0).ewm(alpha=.5, adjust=False, min_periods=6).mean(); al = (-d).clip(lower=0).ewm(alpha=.5, adjust=False, min_periods=6).mean()
rsi2 = 100 - 100 / (1 + ag / al.replace(0, 1e-12))
elig = (to.rolling(20, min_periods=15).mean() >= 25) & (craw >= 50) & s200.notna()
band = ((ret1 - 0.05).abs() < 0.003) | ((ret1 - 0.10).abs() < 0.003) | ((ret1 - 0.20).abs() < 0.003)
buyable = elig & ~((c >= h * 0.9995) & band)
up = c > s200; breadth = (c > s50).where(elig).mean(axis=1)
m126 = c.shift(5) / c.shift(131) - 1
lead = up & (m126.where(elig).rank(axis=1, pct=True) >= 0.8)
tr = np.maximum(h - l, np.maximum((h - pc).abs(), (l - pc).abs())); atr = tr.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
def ev(mask, score, hold=5, ent='c', cost=0.0025, topn=4, label=''):
    f = (c.shift(-hold) / (c if ent == 'c' else o.shift(-1)) - 1) - cost
    m = (mask & buyable).fillna(False)
    rk = score.where(m).rank(axis=1, ascending=False)
    top = f.where(m & (rk <= topn)).mean(axis=1).dropna()
    out = []
    for lab, per in (('IS', IS), ('OOS', OOS)):
        t = top[per[top.index.get_indexer(top.index)] if False else top.index.isin(dates[per])]
        out.append('%s days=%d avg=%.2f%% win=%.0f%% t=%.1f worst=%.1f%%' % (lab, len(t), t.mean() * 100, (t > 0).mean() * 100, t.mean() / (t.std() / np.sqrt(max(len(t), 1))), t.min() * 100))
    print(('%-58s' % label), ' | '.join(out))
    return top
print('--- washout dip (top 4 by lowest RSI2), hold 5, close entry')
for bt in (0.20, 0.25, 0.30, 0.35, 0.50, 1.01):
    for rt in (5, 10, 20):
        ev((up & (rsi2 < rt)).mul(breadth < bt, axis=0), -rsi2, label='breadth<%.2f RSI2<%d' % (bt, rt))
print('--- washout variants (breadth<0.25, RSI2<10)')
base = (up & (rsi2 < 10)).mul(breadth < 0.25, axis=0)
for hold in (3, 4, 5, 6, 8): ev(base, -rsi2, hold=hold, label='hold %d' % hold)
ev(base, -rsi2, ent='o', label='next-open entry'); ev(base, -rsi2, cost=0.004, label='cost 0.40%'); ev(base, -rsi2, topn=2, label='top 2'); ev(base, -rsi2, topn=8, label='top 8')
ev(base, m126, label='rank by 6m momentum instead'); ev((elig & (rsi2 < 10)).mul(breadth < 0.25, axis=0) & ~up, -rsi2, label='control: stocks BELOW 200DMA')
t = ev(base, -rsi2, label='base')
print('signal days per year:', t.groupby(t.index.year).size().to_dict())
print('avg % per year:', (t.groupby(t.index.year).mean() * 100).round(2).to_dict())
# non-overlap: only first day of each washout cluster + every 5th day
days = t.index; keep = []; last = None
for dt in days:
    if last is None or (dates.get_loc(dt) - dates.get_loc(last)) >= 5: keep.append(dt); last = dt
tt = t[keep]; print('non-overlapping entries: n=%d avg=%.2f%% win=%.0f%% | IS %.2f%% (n=%d) OOS %.2f%% (n=%d)' % (len(tt), tt.mean() * 100, (tt > 0).mean() * 100, tt[tt.index < SPLIT].mean() * 100, (tt.index < SPLIT).sum(), tt[tt.index >= SPLIT].mean() * 100, (tt.index >= SPLIT).sum()))
# with a 2-ATR stop (daily bars, stop first, gap at open)
def stop_ret(mask, score, hold=5, k=2.0, cost=0.0025, topn=4):
    m = (mask & buyable).fillna(False); rk = score.where(m).rank(axis=1, ascending=False); sel = (m & (rk <= topn)).values
    C, O, L, A = c.values, o.values, l.values, atr.values; res = {}
    for ti, si in zip(*np.where(sel)):
        if ti + hold >= len(C): continue
        e = C[ti, si]; st = e - k * A[ti, si]; r = None
        for j in range(1, hold + 1):
            if np.isnan(C[ti + j, si]): break
            if O[ti + j, si] <= st: r = O[ti + j, si] / e - 1; break
            if L[ti + j, si] <= st: r = st / e - 1; break
            if j == hold: r = C[ti + j, si] / e - 1
        if r is not None: res.setdefault(dates[ti], []).append(r - cost)
    s = pd.Series({k_: np.mean(v_) for k_, v_ in res.items()}).sort_index()
    return s
for k in (1.5, 2.0, 3.0):
    s = stop_ret(base, -rsi2, k=k); print('with %.1f ATR stop: IS %.2f%%  OOS %.2f%%  worst day %.1f%%' % (k, s[s.index < SPLIT].mean() * 100, s[s.index >= SPLIT].mean() * 100, s.min() * 100))
print('--- turn of month')
mon = pd.Series(dates.month, index=dates); me = (mon != mon.shift(-1)).values
for off, nm in ((0, 'last close of month'), (1, '2nd-last close'), (-1, '1st close of new month'), (2, '3rd-last close')):
    flag = pd.Series(me, index=dates).shift(-off if off > 0 else -off * -1 if off < 0 else 0)
    flag = pd.Series(me, index=dates).shift(-off) if off > 0 else (pd.Series(me, index=dates).shift(1) if off < 0 else pd.Series(me, index=dates))
    ev(lead.mul(flag.fillna(False).values, axis=0), m126, label='leaders top4, enter ' + nm)
ev(lead, m126, label='control: leaders top4 on ANY day')
ev(up.mul(me, axis=0), m126 * 0 + np.random.RandomState(1).rand(*c.shape), label='control: random uptrend stocks at month end')
for hold in (3, 4, 5, 8): ev(lead.mul(me, axis=0), m126, hold=hold, label='month-end leaders hold %d' % hold)
ev(lead.mul(me, axis=0), m126, ent='o', label='month-end leaders next-open'); ev(lead.mul(me, axis=0), m126, topn=10, label='month-end leaders top 10')
t = ev(lead.mul(me, axis=0), m126, label='base'); print('avg % per year:', (t.groupby(t.index.year).mean() * 100).round(2).to_dict())
