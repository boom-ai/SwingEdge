"""Round 4: path-dependence of the slot-limited portfolios. Same trades as 25_portfolio.py (no gate, entry D+1 open, cost 0.25%),
start date staggered by 0,3,...,57 sessions after the period start (20 starts per period). usage: python3 -I 30_path.py <work dir> <out_r4 dir>
Writes path_dependence.csv. Runtime ~1 min."""
import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from r4lib import sim, portfolio, pstats
WORK, OUT = sys.argv[1:3]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl')); SC = np.load(os.path.join(OUT, 'score.npz'))
dates, reg = M['dates'], M['reg']; T = len(dates)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C = (np.ascontiguousarray(W[k].values, dtype=np.float64) for k in ('o', 'h', 'l', 'c')); del W
ATR = F['atr14'].astype(np.float64); SMA50 = F['sma50'].astype(np.float64); S = SC['S'].astype(np.float64)
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
Cff = pd.DataFrame(C).ffill().values
ts, js = np.nonzero(SC['BUYng']); k = ts >= t0; ts, js = ts[k], js[k]
rows = []
for plan, args, gap in (('X10', (3.0, 0.0, 0.0, 1, False), 0.5), ('X0', (0.0, 0.0, 0.0, 0, False), -1.0)):
    ret, xd, ep, a1, a2, st = sim(ts, js, True, 60, *args, gap, O, H, L, C, ATR, SMA50, np.zeros_like(C))
    df = pd.DataFrame({'t': ts, 'j': js, 'ret': ret, 'xd': xd, 'ep': ep, 'score': S[ts, js]}).dropna(subset=['ret'])
    df['ed'] = df.t + 1; df = df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
    for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, T - 1))):
        for slots in (4, 8):
            for off in range(0, 60, 3):
                d = df[(df.t >= a + off) & (df.t <= b)]
                eq, ntr, util, _ = portfolio(d, a + off, b, 0.0025, slots, Cff, dates)
                st_, yr = pstats(eq); rows.append(dict(plan=plan, period=pn, slots=slots, offset=off, **st_))
    print(plan, flush=True)
# random-stock baseline (20 random liquid candidates per day, random rank, seed 7) and benchmarks for the same starts
F_U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252']) & ~F['uclock']
rng = np.random.default_rng(7); tl = []; jl = []
for t in range(t0, T - 1):
    cand = np.nonzero(F_U[t])[0]
    if len(cand): pk = rng.choice(cand, size=min(20, len(cand)), replace=False); tl.append(np.full(len(pk), t)); jl.append(pk)
tr_, jr_ = np.concatenate(tl), np.concatenate(jl); rsc = rng.random(len(tr_))
for plan, args, gap in (('X10', (3.0, 0.0, 0.0, 1, False), 0.5), ('X0', (0.0, 0.0, 0.0, 0, False), -1.0)):
    ret, xd, ep, a1, a2, st = sim(tr_, jr_, True, 60, *args, gap, O, H, L, C, ATR, SMA50, np.zeros_like(C))
    df = pd.DataFrame({'t': tr_, 'j': jr_, 'ret': ret, 'xd': xd, 'ep': ep, 'score': rsc}).dropna(subset=['ret'])
    df['ed'] = df.t + 1; df = df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
    for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, T - 1))):
        for slots in (4, 8):
            for off in range(0, 60, 3):
                d = df[(df.t >= a + off) & (df.t <= b)]
                eq, ntr, util, _ = portfolio(d, a + off, b, 0.0025, slots, Cff, dates)
                st_, yr = pstats(eq); rows.append(dict(plan='RANDOM ' + plan, period=pn, slots=slots, offset=off, **st_))
    print('random', plan, flush=True)
U_ = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
r1 = C / np.vstack([np.full((1, C.shape[1]), np.nan), C[:-1]]) - 1; Up = np.vstack([np.zeros((1, C.shape[1]), bool), U_[:-1]])
ew = pd.Series(np.nanmean(np.where(Up, r1, np.nan), axis=1), index=dates).fillna(0)
for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, T - 1))):
    for off in range(0, 60, 3):
        s_ = reg['n50'].iloc[a + off:b + 1]; st_, _ = pstats(s_ / s_.iloc[0]); rows.append(dict(plan='Nifty50 price', period=pn, slots=0, offset=off, **st_))
        e_ = (1 + ew.iloc[a + off + 1:b + 1]).cumprod(); e_ = pd.concat([pd.Series([1.0], index=[dates[a + off]]), e_])
        st_, _ = pstats(e_); rows.append(dict(plan='EW universe', period=pn, slots=0, offset=off, **st_))
d = pd.DataFrame(rows); d.round(3).to_csv(os.path.join(OUT, 'path_dependence.csv'), index=False)
s = d.groupby(['plan', 'period', 'slots']).agg(cagr_min=('cagr', 'min'), cagr_med=('cagr', 'median'), cagr_max=('cagr', 'max'),
                                               mdd_worst=('mdd', 'min'), mdd_med=('mdd', 'median')).round(1)
s.to_csv(os.path.join(OUT, 'path_dependence_summary.csv')); print(s)
