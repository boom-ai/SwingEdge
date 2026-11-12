"""Round 4: null distribution for the slot-limited portfolio. 100 random-stock portfolios (each: own seed, 20 random liquid
candidates per day, random rank, random start offset 0..57 sessions) per (plan, slots, period); entry D+1 open, cost 0.25%, no gate.
Compared with the SCORE portfolio over 20 start offsets (path_dependence.csv). usage: python3 -I 31_random_dist.py <work> <out_r4>
Writes random_null.csv and random_null_summary.csv. Runtime ~4 min."""
import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from r4lib import sim, portfolio, pstats
WORK, OUT = sys.argv[1:3]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl')); dates = M['dates']; T = len(dates)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C = (np.ascontiguousarray(W[k].values, dtype=np.float64) for k in ('o', 'h', 'l', 'c')); del W
ATR = F['atr14'].astype(np.float64); SMA50 = F['sma50'].astype(np.float64); Cff = pd.DataFrame(C).ffill().values; Z = np.zeros_like(C)
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252']) & ~F['uclock']
cand = [np.nonzero(U[t])[0] for t in range(T)]
rows = []
for seed in range(100):
    rng = np.random.default_rng(1000 + seed); tl = []; jl = []
    for t in range(t0, T - 1):
        c = cand[t]
        if len(c): p = rng.choice(c, size=min(20, len(c)), replace=False); tl.append(np.full(len(p), t)); jl.append(p)
    tr, jr = np.concatenate(tl), np.concatenate(jl); sc = rng.random(len(tr)); off = int(rng.integers(0, 20)) * 3
    for plan, args, gap in (('X10', (3.0, 0.0, 0.0, 1, False), 0.5), ('X0', (0.0, 0.0, 0.0, 0, False), -1.0)):
        ret, xd, ep, a1, a2, st = sim(tr, jr, True, 60, *args, gap, O, H, L, C, ATR, SMA50, Z)
        df = pd.DataFrame({'t': tr, 'j': jr, 'ret': ret, 'xd': xd, 'ep': ep, 'score': sc}).dropna(subset=['ret'])
        df['ed'] = df.t + 1; df = df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
        for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, T - 1))):
            for slots in (4, 8):
                d = df[(df.t >= a + off) & (df.t <= b)]
                eq, ntr, util, _ = portfolio(d, a + off, b, 0.0025, slots, Cff, dates)
                st_, _ = pstats(eq); rows.append(dict(seed=seed, offset=off, plan=plan, period=pn, slots=slots, **st_))
    if seed % 20 == 19: print('seed', seed, flush=True)
R = pd.DataFrame(rows); R.round(3).to_csv(os.path.join(OUT, 'random_null.csv'), index=False)
P = pd.read_csv(os.path.join(OUT, 'path_dependence.csv')); P = P[P.plan.isin(['X10', 'X0'])]
out = []
for (plan, pn, slots), g in R.groupby(['plan', 'period', 'slots']):
    sp = P[(P.plan == plan) & (P.period == pn) & (P.slots == slots)]
    med = sp.cagr.median()
    out.append(dict(plan=plan, period=pn, slots=slots, random_p05=g.cagr.quantile(.05), random_median=g.cagr.median(), random_p95=g.cagr.quantile(.95),
                    random_mdd_median=g.mdd.median(), score_median_over_starts=med, score_min=sp.cagr.min(), score_max=sp.cagr.max(),
                    score_mdd_median=sp.mdd.median(), pct_random_below_score_median=100 * (g.cagr < med).mean()))
S = pd.DataFrame(out).round(1); S.to_csv(os.path.join(OUT, 'random_null_summary.csv'), index=False); print(S.to_string())
