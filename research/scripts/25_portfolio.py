"""Round 4, step 5: per-trade stats of the chosen plan (X10) incl. entry-zone rule, and portfolio simulation.
usage: python3 -I 25_portfolio.py <work dir> <out_r4 dir>          runtime about 6-8 min
Plan X10: entry next open (b; skipped if open > C[D] + 0.5*ATR14(D)) or signal close (a); initial stop = entry - 3*ATR14(D); exit next open after a close < SMA50 (b) / at that close (a);
          time exit at the close of D+60. Reference levels t1 = entry + 6*ATR14(D) (2R), t2 = entry + 9*ATR14(D) (3R): touch rates reported, not sold.
X0 (pure 60-day time exit) is run alongside as a reference.  Baseline = random liquid stock (U, not upper-circuit-locked), same exit, 5 seeds.
"""
import sys, os, json, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from r4lib import sim, nw_t, portfolio, pstats
WORK, OUT = sys.argv[1:3]
F = np.load(os.path.join(OUT, 'feat.npz')); M = pd.read_pickle(os.path.join(OUT, 'meta.pkl')); SC = np.load(os.path.join(OUT, 'score.npz'))
dates, reg, syms = M['dates'], M['reg'], M['syms']; T = len(dates)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C = (np.ascontiguousarray(W[k].values, dtype=np.float64) for k in ('o', 'h', 'l', 'c')); del W
ATR = F['atr14'].astype(np.float64); SMA50 = F['sma50'].astype(np.float64); DUM = np.zeros_like(C)
S = SC['S'].astype(np.float64); BUYng = SC['BUYng']
gate = (reg['n50'] > reg['n50_sma200']).values
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01'))); HZ = 60
Cff = pd.DataFrame(C).ffill().values
PLANS = {'X10': (3.0, 0.0, 0.0, 1, False), 'X0': (0.0, 0.0, 0.0, 0, False)}
def trades(ts, js, mode_b, plan, gapmax=-1.0, score=None):
    sk, tg, tr, ce, hf = PLANS[plan]
    ret, xd, ep, t1, t2, st = sim(ts, js, mode_b, HZ, sk, tg, tr, ce, hf, gapmax, O, H, L, C, ATR, SMA50, DUM)
    df = pd.DataFrame({'t': ts, 'j': js, 'ret': ret, 'xd': xd, 'ep': ep, 't1': t1, 't2': t2, 'st': st,
                       'score': S[ts, js] if score is None else score})
    df = df.dropna(subset=['ret']); df['ed'] = df.t + (1 if mode_b else 0)
    return df.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
def nonoverlap(df):
    d = df.sort_values(['j', 't']); keep = np.zeros(len(d), bool); lj = -1; lx = -1
    for i, (j, t, x) in enumerate(zip(d.j.values, d.t.values, d.xd.values)):
        if j != lj: lj = j; lx = -1
        if t > lx: keep[i] = True; lx = x
    return d[keep].sort_values(['t', 'score'], ascending=[True, False])
PER = {'IS': (t0, tsplit - 1), 'OOS': (tsplit, T - 1), 'FULL': (t0, T - 1)}
ts_all, js_all = np.nonzero(BUYng); k = ts_all >= t0; ts_all, js_all = ts_all[k], js_all[k]
# ---------------- per-trade stats of the plan ----------------
rows = []; store = {}
for gname in ('gated', 'nogate'):
    m = gate[ts_all] if gname == 'gated' else np.ones(len(ts_all), bool)
    for mode_b in (True, False):
        for plan in ('X10', 'X0'):
            for gapmax in (-1.0, 0.5):
                df = trades(ts_all[m], js_all[m], mode_b, plan, gapmax); store[(gname, mode_b, plan, gapmax)] = df
                nd = nonoverlap(df)
                for cost in (0.0025, 0.0040):
                    for pn, (a, b) in PER.items():
                        bb = b - HZ if pn == 'IS' else b
                        d = nd[(nd.t >= a) & (nd.t <= bb)]; net = d.ret.values - cost
                        hold = (d.xd - d.ed).values
                        dm = pd.Series(net, index=d.t.values).groupby(level=0).mean().reindex(range(a, bb + 1))
                        rows.append(dict(signals=gname, entry='b' if mode_b else 'a', plan=plan, gapmax=gapmax, cost=cost * 100, period=pn, n=len(net),
                                         per_year=len(net) / ((dates[min(bb, T - 1)] - dates[a]).days / 365.25), win=100 * (net > 0).mean(),
                                         exp=100 * net.mean(), median=100 * np.median(net), p25=100 * np.percentile(net, 25), p75=100 * np.percentile(net, 75),
                                         p05=100 * np.percentile(net, 5), worst=100 * net.min(), best=100 * net.max(), hold=hold.mean(),
                                         pf=net[net > 0].sum() / -net[net < 0].sum(), t_daily=nw_t(dm.values, HZ),
                                         stopped=100 * d.st.mean(), touch_t1=100 * d.t1.mean(), touch_t2=100 * d.t2.mean()))
        print('trades', gname, mode_b, flush=True)
P = pd.DataFrame(rows); P.round(3).to_csv(os.path.join(OUT, 'plan_trades.csv'), index=False)
# ---------------- benchmarks ----------------
n50 = reg['n50']; r1 = C / np.vstack([np.full((1, C.shape[1]), np.nan), C[:-1]]) - 1
Uprev = np.vstack([np.zeros((1, C.shape[1]), bool), U[:-1]])
ew = pd.Series(np.nanmean(np.where(Uprev, r1, np.nan), axis=1), index=dates).fillna(0)
bench = {}
for pn, (a, b) in PER.items():
    s = n50.iloc[a:b + 1]; s = s / s.iloc[0]; e = (1 + ew.iloc[a + 1:b + 1]).cumprod(); e = pd.concat([pd.Series([1.0], index=[dates[a]]), e])
    bench[pn] = {'Nifty50 (price)': pstats(s), 'EW liquid universe (no cost)': pstats(e)}
# ---------------- portfolios ----------------
prow = []; yrow = []; eqs = {}
def record(label, gname, mode_b, plan, cost, slots, pn, eq, ntr, util):
    st, yr = pstats(eq)
    yrs = (eq.index[-1] - eq.index[0]).days / 365.25
    prow.append(dict(strategy=label, signals=gname, entry='b' if mode_b else 'a', plan=plan, cost=cost * 100, slots=slots, period=pn,
                     trades=ntr, trades_per_year=ntr / yrs, slot_util=100 * util, **st))
    if pn == 'FULL':
        for y, v in yr.items(): yrow.append(dict(strategy=label, signals=gname, entry='b' if mode_b else 'a', plan=plan, cost=cost * 100, slots=slots, year=y, ret=100 * v))
for (gname, mode_b, plan, gapmax), df in store.items():
    if gapmax != (0.5 if plan == 'X10' else -1.0): continue   # final plan uses the 0.5-ATR entry-zone rule
    for cost in (0.0025, 0.0040):
        for slots in (4, 8):
            for pn, (a, b) in PER.items():
                d = df[(df.t >= a) & (df.t <= b)]
                eq, ntr, util, _ = portfolio(d, a, b, cost, slots, Cff, dates)
                record('SCORE', gname, mode_b, plan, cost, slots, pn, eq, ntr, util)
                if pn == 'FULL': eqs[('SCORE', gname, mode_b, plan, cost, slots)] = eq
    print('portfolio', gname, mode_b, plan, flush=True)
# random baseline: each day 20 random candidates from U (not circuit-locked), random rank; same plan X10
Ucand = U & ~F['uclock']
for seed in range(5):
    rng = np.random.default_rng(100 + seed); ts_l = []; js_l = []
    for t in range(t0, T - 1):
        cand = np.nonzero(Ucand[t])[0]
        if len(cand) == 0: continue
        pick = rng.choice(cand, size=min(20, len(cand)), replace=False); ts_l.append(np.full(len(pick), t)); js_l.append(pick)
    ts_r = np.concatenate(ts_l); js_r = np.concatenate(js_l)
    for gname in ('gated', 'nogate'):
        m = gate[ts_r] if gname == 'gated' else np.ones(len(ts_r), bool)
        for mode_b in (True, False):
            df = trades(ts_r[m], js_r[m], mode_b, 'X10', gapmax=0.5, score=rng.random(int(m.sum())))
            for cost in (0.0025, 0.0040):
                for slots in (4, 8):
                    for pn, (a, b) in PER.items():
                        d = df[(df.t >= a) & (df.t <= b)]
                        eq, ntr, util, _ = portfolio(d, a, b, cost, slots, Cff, dates)
                        record(f'RANDOM seed{seed}', gname, mode_b, 'X10', cost, slots, pn, eq, ntr, util)
    print('random seed', seed, flush=True)
PF = pd.DataFrame(prow); PF.round(3).to_csv(os.path.join(OUT, 'portfolio.csv'), index=False)
pd.DataFrame(yrow).round(3).to_csv(os.path.join(OUT, 'portfolio_years.csv'), index=False)
pd.to_pickle(eqs, os.path.join(OUT, 'portfolio_equity.pkl'))
brow = []
for pn in bench:
    for k_, (st, yr) in bench[pn].items():
        brow.append(dict(bench=k_, period=pn, **st))
        if pn == 'FULL':
            for y, v in yr.items(): yrow.append(dict(strategy=k_, year=y, ret=100 * v))
pd.DataFrame(brow).round(3).to_csv(os.path.join(OUT, 'benchmarks.csv'), index=False)
pd.DataFrame(yrow).round(3).to_csv(os.path.join(OUT, 'portfolio_years.csv'), index=False)
print('done')
