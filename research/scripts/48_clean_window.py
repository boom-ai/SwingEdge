"""Hole-free OOS window (2022-01 .. 2025-01) for the leading variants: variant vs matched nulls and benchmarks. No new variants."""
import sys, os, importlib.util, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location('v42', os.path.join(os.path.dirname(os.path.abspath(__file__)), '42_variants.py')); v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
V, CAND, pos, frame, VOLS, Cff, dates, T, N, GAP, t0, tsplit, reg, U, C, portfolio, pstats, NA_FILT = (getattr(v, k) for k in ('V', 'CAND', 'pos', 'frame', 'VOLS', 'Cff', 'dates', 'T', 'N', 'GAP', 't0', 'tsplit', 'reg', 'U', 'C', 'portfolio', 'pstats', 'NA_FILT'))
a, b = tsplit, GAP - 61
n50 = reg['n50']; r1 = C / np.vstack([np.full((1, N), np.nan), C[:-1]]) - 1; Up = np.vstack([np.zeros((1, N), bool), U[:-1]])
ew = pd.Series(np.nanmean(np.where(Up, r1, np.nan), axis=1), index=dates).fillna(0)
s = n50.iloc[a:b + 1]; print('window', dates[a].date(), dates[b].date(), 'Nifty', pstats(s / s.iloc[0])[0]['cagr'])
e = (1 + ew.iloc[a + 1:b + 1]).cumprod(); print('EW universe', pstats(pd.concat([pd.Series([1.0], index=[dates[a]]), e]))[0])
for vid in ('V0', 'V3', 'V7', 'V8', 'V9'):
    lab, mask, plan, slots, rk = V[vid]; tt, jj = np.nonzero(mask & CAND); df = frame(pos[tt, jj], plan, VOLS[rk][tt, jj])
    def run(d, off):
        d = d[(d.t >= a + off) & (d.t <= b)]; eq, *_ = portfolio(d, a + off, b, 0.0025, slots, Cff, dates); return pstats(eq)[0]
    med = np.median([run(df, o)['cagr'] for o in range(0, 60, 3)])
    rng = np.random.default_rng(77 + int(vid[1:])); nb = []; na = []
    for sd in range(120):
        d2 = df.copy(); d2['score'] = rng.random(len(d2)); d2 = d2.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True); nb.append(run(d2, int(rng.integers(0, 20)) * 3)['cagr'])
    fl = NA_FILT.get(vid); cm = CAND if fl is None else CAND & fl; cands = [np.nonzero(cm[t])[0] for t in range(T)]
    for sd in range(120):
        tl = []; jl = []
        for t in range(t0, T - 1):
            c_ = cands[t]
            if len(c_): p = rng.choice(c_, size=min(20, len(c_)), replace=False); tl.append(np.full(len(p), t)); jl.append(p)
        tt2, jj2 = np.concatenate(tl), np.concatenate(jl); d3 = frame(pos[tt2, jj2], plan, rng.random(len(tt2))); na.append(run(d3, int(rng.integers(0, 20)) * 3)['cagr'])
    print(f'{vid} {lab}: clean-OOS CAGR median {med:.1f}  null A median {np.median(na):.1f} (variant above {100*(np.array(na)<med).mean():.0f}% of seeds)  null B median {np.median(nb):.1f} (above {100*(np.array(nb)<med).mean():.0f}%)', flush=True)
