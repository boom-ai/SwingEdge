"""Backtest engine + all candidate strategies. usage: python3 -I 04_backtest.py <work dir> <out dir>
Realism rules: signal on day D close with data <= D; entry (a) D close or (b) D+1 open; liquidity 20d avg turnover >= 25cr and
(unadjusted) price >= 50; stop first when stop & target in same bar; gap-through-stop exits at the open; max hold 6 sessions
(exit at the close of D+6; 5-day strategies exit at close of D+5); no overlapping trades per stock; cost 0.25% / 0.40% of entry value.
"""
import sys, os, numpy as np, pandas as pd, numba as nb
WORK, OUT = sys.argv[1:3]
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
o, h, l, c, v, to, craw = (W[k] for k in ['o', 'h', 'l', 'c', 'v', 'to_cr', 'craw'])
dates = c.index; syms = np.array(c.columns); T, N = c.shape
n50 = pd.read_pickle(os.path.join(WORK, 'nifty50.pkl')).Close.reindex(dates).ffill()
START = pd.Timestamp('2013-01-01'); SPLIT = pd.Timestamp('2022-01-01')
# ---------------- indicators ----------------
def sma(x, n): return x.rolling(n, min_periods=int(n * 0.9)).mean()
pc = c.shift(1)
sma5, sma20, sma50, sma200 = sma(c, 5), sma(c, 20), sma(c, 50), sma(c, 200)
d = c.diff()
ag = d.clip(lower=0).ewm(alpha=0.5, adjust=False, min_periods=10).mean(); al = (-d).clip(lower=0).ewm(alpha=0.5, adjust=False, min_periods=10).mean()
rsi2 = 100 - 100 / (1 + ag / al.replace(0, 1e-12))
tr = np.maximum(h - l, np.maximum((h - pc).abs(), (l - pc).abs()))
atr = tr.ewm(alpha=1 / 14, adjust=False, min_periods=14).mean()
ibs = ((c - l) / (h - l).replace(0, np.nan)).fillna(0.5)
vavg = v.shift(1).rolling(20, min_periods=15).mean(); vr = v / vavg
ret1 = c / pc - 1
elig = (to.rolling(20, min_periods=15).mean() >= 25) & (craw >= 50) & sma200.notna()
# likely upper-circuit lock at the close (cannot be bought): close == high and gain at a 5/10/20% band
band = ((ret1 - 0.05).abs() < 0.003) | ((ret1 - 0.10).abs() < 0.003) | ((ret1 - 0.20).abs() < 0.003)
elig &= ~((c >= h * 0.9995) & band)
up200 = c > sma200
mom126 = c.shift(5) / c.shift(131) - 1; mom63 = c.shift(5) / c.shift(68) - 1
def xrank(x, mask): return x.where(mask).rank(axis=1, pct=True)
reg = {'none': pd.Series(True, index=dates), 'n50>50dma': n50 > n50.rolling(50).mean(), 'n50>200dma': n50 > n50.rolling(200).mean()}
# ---------------- strategies: name -> (mask, score, exit params) ----------------
S = {}
def add(name, mask, score, hold=6, stop=0.0, tgt=0.0, ma=False, fam=None):
    S[name] = dict(mask=(mask & elig).values, score=score.values, hold=hold, stop=stop, tgt=tgt, ma=ma, fam=fam or name[0])
A = up200
e10 = c.ewm(span=10, adjust=False, min_periods=30).mean(); e20 = c.ewm(span=20, adjust=False, min_periods=40).mean()
e50 = c.ewm(span=50, adjust=False, min_periods=100).mean(); e200 = c.ewm(span=200, adjust=False, min_periods=250).mean()
g = ((e10 - e20) / e20).abs()
setup = (e10 > e50) & (e10 > e200) & (g < 0.005) & (g < g.shift(2))
add('Y1 EMA10 converging on EMA20 (<0.5%), EMA10 above EMA50 & EMA200, hold 6', setup, -g, hold=6)
add('Y2 same, hold 10', setup, -g, hold=10)
add('Y3 same + close above both EMAs today (breakout bar), hold 6', setup & (c > e10) & (c > e20) & (c > pc), vr, hold=6)
add('Y4 Y3 with 2ATR stop', setup & (c > e10) & (c > e20) & (c > pc), vr, hold=6, stop=2.0)
rng = np.random.default_rng(7)
add('BASE random liquid >200DMA, hold 6', A, pd.DataFrame(rng.random((T, N)), index=dates, columns=c.columns), hold=6, fam='BASE')
# ---------------- trade simulator ----------------
@nb.njit(cache=False)
def simulate(ts, js, mode_b, hold, stopk, tgtR, ma, O, H, L, C, S5, ATR):
    n = len(ts); T = C.shape[0]
    ret = np.full(n, np.nan); xd = np.full(n, -1); ed = np.full(n, -1)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        if mode_b:
            ep = O[t + 1, j]
            if np.isnan(ep): continue
        else:
            ep = C[t, j]
        a = ATR[t, j]
        stop = ep - stopk * a if stopk > 0 else -1.0
        tgt = ep + tgtR * stopk * a if tgtR > 0 else 1e18
        px = np.nan; lastc = ep; x = last
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            entry_day = mode_b and dd == t + 1
            if (not entry_day) and O[dd, j] <= stop: px = O[dd, j]; x = dd; break
            if L[dd, j] <= stop: px = stop; x = dd; break
            if (not entry_day) and O[dd, j] >= tgt: px = O[dd, j]; x = dd; break
            if H[dd, j] >= tgt: px = tgt; x = dd; break
            lastc = C[dd, j]
            if (ma and C[dd, j] > S5[dd, j]) or dd == last: px = C[dd, j]; x = dd; break
        ret[i] = px / ep - 1; xd[i] = x; ed[i] = t + 1 if mode_b else t
    return ret, xd, ed
Oa, Ha, La, Ca, S5a, ATRa = (x.values.astype(np.float64) for x in (o, h, l, c, sma5, atr))
Cff = c.ffill().values
def portfolio(tr_all, t0, t1, cost, maxpos=4):
    """tr_all: all signals (not overlap-filtered) sorted by (t, -score). Returns daily equity series (mark-to-market at closes)."""
    eq = np.ones(t1 - t0 + 1); cash = 1.0; pos = []   # pos: [j, alloc, ep, xd, ret]
    tt = tr_all.t.values; lo = np.searchsorted(tt, np.arange(t0, t1 + 2))
    J, R, X, E = tr_all.j.values, tr_all.ret.values, tr_all.xd.values, tr_all.ed.values
    EP = tr_all.ep.values
    pending = []
    for t in range(t0, t1 + 1):
        # mode b entries decided yesterday enter at today's open
        for (j, r, x, ep) in pending:
            basis = cash + sum(p[1] for p in pos); alloc = min(0.25 * basis, cash)
            if alloc > 0 and len(pos) < maxpos: cash -= alloc; pos.append([j, alloc, ep, x, r])
        pending = []
        keep = []
        for p in pos:
            if p[3] <= t: cash += p[1] * (1 + p[4] - cost)
            else: keep.append(p)
        pos = keep
        held = {p[0] for p in pos}; free = maxpos - len(pos)
        for i in range(lo[t - t0], lo[t - t0 + 1]):
            if free <= 0: break
            if J[i] in held or np.isnan(R[i]): continue
            if E[i] == t:
                basis = cash + sum(p[1] for p in pos); alloc = min(0.25 * basis, cash)
                if alloc <= 0: break
                cash -= alloc; pos.append([J[i], alloc, EP[i], X[i], R[i]])
            else: pending.append((J[i], R[i], X[i], EP[i]))
            held.add(J[i]); free -= 1
        eq[t - t0] = cash + sum(p[1] * Cff[t, p[0]] / p[2] for p in pos)
    return pd.Series(eq, index=dates[t0:t1 + 1])
def run(name, regime, mode_b):
    s = S[name]; m = s['mask'] & reg[regime].values[:, None]
    ts, js = np.nonzero(m); keep = dates[ts] >= START; ts, js = ts[keep], js[keep]
    ret, xd, ed = simulate(ts, js, mode_b, s['hold'], s['stop'], s['tgt'], s['ma'], Oa, Ha, La, Ca, S5a, ATRa)
    df = pd.DataFrame({'t': ts, 'j': js, 'ret': ret, 'xd': xd, 'ed': ed, 'score': s['score'][ts, js]})
    df = df[df.ret.notna()]
    df['ep'] = np.where(mode_b, Oa[df.t.values + 1, df.j.values] if len(df) else [], Ca[df.t.values, df.j.values])
    df = df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
    # no-overlap per stock
    d2 = df.sort_values(['j', 't']); acc = np.zeros(len(d2), bool); lastx = -1; lastj = -1
    for i, (j, t, x) in enumerate(zip(d2.j.values, d2.t.values, d2.xd.values)):
        if j != lastj: lastj = j; lastx = -1
        if t >= lastx: acc[i] = True; lastx = x
    df['acc'] = pd.Series(acc, index=d2.index)
    return df
def boot_ci(net, month, B=2000, seed=1):
    g = pd.DataFrame({'r': net, 'm': month}).groupby('m').r.agg(['sum', 'count'])
    if len(g) < 6: return (np.nan, np.nan, np.nan)
    r = np.random.default_rng(seed); idx = r.integers(0, len(g), (B, len(g)))
    means = g['sum'].values[idx].sum(1) / g['count'].values[idx].sum(1)
    return (np.percentile(means, 2.5), np.percentile(means, 97.5), net.mean() / means.std())
def stats(tr, cost):
    net = tr.ret.values - cost
    if len(net) == 0: return dict(n=0)
    mon = dates[tr.t.values].to_period('M')
    lo_, hi_, tcl = boot_ci(net, mon)
    gp = net[net > 0].sum(); gl = -net[net < 0].sum()
    return dict(n=len(net), win=100 * (net > 0).mean(), avg=100 * net.mean(), med=100 * np.median(net), pf=gp / gl if gl > 0 else np.nan,
                hold=(tr.xd - tr.t).mean(), ci_lo=100 * lo_ if lo_ == lo_ else np.nan, ci_hi=100 * hi_ if hi_ == hi_ else np.nan, t_clust=tcl)
def pstats(eq):
    yrs = (eq.index[-1] - eq.index[0]).days / 365.25
    return dict(cagr=100 * (eq.iloc[-1] ** (1 / yrs) - 1), mdd=100 * (eq / eq.cummax() - 1).min())
t_start = int(np.searchsorted(dates, START)); t_split = int(np.searchsorted(dates, SPLIT))
periods = {'IS': (t_start, t_split - 1), 'OOS': (t_split, T - 1), 'ALL': (t_start, T - 1)}
rows = []; yrows = []
for name in S:
    for regime in reg:
        for mode_b in (False, True):
            df = run(name, regime, mode_b); entry = 'b:next open' if mode_b else 'a:close'
            if False: df.assign(date=dates[df.t.values], sym=syms[df.j.values]).to_pickle(os.path.join(OUT, 'trades_%s_%s.pkl' % (name.split()[0], entry[0])))
            for cost in (0.0025, 0.0040):
                for pn, (a, b) in periods.items():
                    tr = df[(df.t >= a) & (df.t <= b) & df.acc]
                    st = stats(tr, cost)
                    eq = portfolio(df[(df.t >= a) & (df.t <= b)], a, b, cost)
                    st.update(pstats(eq)); st.update(strategy=name, fam=S[name]['fam'], regime=regime, entry=entry, cost=cost * 100, period=pn)
                    rows.append(st)
                    if pn == 'ALL':
                        eq.to_frame('eq').to_pickle(os.path.join(OUT, 'eq_%s_%s_%s_%.2f.pkl' % (name.split()[0], regime.replace('>', 'gt'), entry[0], cost * 100)))
                        yr = dates[tr.t.values].year; net = tr.ret.values - cost; ey = eq.groupby(eq.index.year).last(); ey = ey / ey.shift(1).fillna(1.0) - 1
                        for y in np.unique(yr):
                            k = net[yr == y]
                            yrows.append(dict(strategy=name, regime=regime, entry=entry, cost=cost * 100, year=y, n=len(k), win=100 * (k > 0).mean(), avg=100 * k.mean(), port_ret=100 * ey.get(y, np.nan)))
            print(name, regime, entry, len(df), flush=True)
    pd.DataFrame(rows).to_csv(os.path.join(OUT, 'results.csv'), index=False); pd.DataFrame(yrows).to_csv(os.path.join(OUT, 'by_year.csv'), index=False)
# index reference
n5 = n50[dates >= START]; print('Nifty50 CAGR', pstats(n5 / n5.iloc[0]), 'IS', pstats(n5[:SPLIT] / n5.iloc[0]), 'OOS', pstats(n5[SPLIT:] / n5[SPLIT:].iloc[0]))
