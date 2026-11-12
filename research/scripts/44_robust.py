"""Review C/E follow-up: cost sensitivity, hole-free OOS window, calendar-year returns, share rounding, for V0, V3, V7, V8 (no new variants)."""
import sys, os, importlib.util, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location('v42', os.path.join(os.path.dirname(os.path.abspath(__file__)), '42_variants.py')); v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
globals().update({k: getattr(v, k) for k in ('V', 'CAND', 'pos', 'frame', 'VOLS', 'PER', 'Cff', 'dates', 'T', 'N', 'GAP', 't0', 'tsplit', 'reg', 'U', 'C', 'CR', 'portfolio', 'pstats', 'np', 'pd')})
OUT = v.OUT
PER2 = dict(PER); PER2['OOSclean'] = (tsplit, GAP - 61)
out = []
def P(s): print(s); out.append(s)
n50 = reg['n50']
def nif(a, b):
    s = n50.iloc[a:b + 1]; return pstats(s / s.iloc[0])[0]
for pn, (a, b) in PER2.items(): P(f'Nifty {pn}: {nif(a,b)["cagr"]:.1f}% mdd {nif(a,b)["mdd"]:.1f}%  ({dates[a].date()} .. {dates[b].date()})')
rows = []
for vid in ('V0', 'V3', 'V7', 'V8'):
    lab, mask, plan, slots, rk = V[vid]
    tt, jj = np.nonzero(mask & CAND); df = frame(pos[tt, jj], plan, VOLS[rk][tt, jj])
    for pn, (a, b) in PER2.items():
        for cost in (0.0025, 0.0045, 0.0065):
            cg = []; md = []
            for off in range(0, 60, 3):
                a2 = a + off; d = df[(df.t >= a2) & (df.t <= b)]
                eq, ntr, util, taken = portfolio(d, a2, b, cost, slots, Cff, dates); st, yr = pstats(eq); cg.append(st['cagr']); md.append(st['mdd'])
            rows.append(dict(vid=vid, period=pn, cost=cost * 100, cagr_med=np.median(cg), cagr_min=min(cg), cagr_max=max(cg), mdd_med=np.median(md)))
    # per-trade expectancy of all (overlapping-excluded) signals at cost levels, OOS
    dd = df[(df.t >= tsplit) & (df.t <= T - 61)]
    for cost in (0.0025, 0.0045, 0.0065):
        net = dd.ret.values - cost; rows.append(dict(vid=vid, period='OOS per-signal', cost=cost * 100, cagr_med=100 * net.mean(), cagr_min=100 * np.median(net), cagr_max=100 * (net > 0).mean(), mdd_med=len(net)))
    # calendar-year returns, off=0, cost .25
    a, b = (t0, T - 1)
    eq, ntr, util, taken = portfolio(df[(df.t >= a) & (df.t <= b)], a, b, 0.0025, slots, Cff, dates)
    ey = eq.groupby(eq.index.year).last(); pr = ey.shift(1); pr.iloc[0] = 1.0
    P(f'{vid} calendar-year returns %: ' + ' '.join(f'{y}:{100*(x-1):+.0f}' for y, x in (ey / pr).items()))
    # share rounding on taken trades (OOS)
    d = df[(df.t >= tsplit) & (df.t <= T - 61)]; eq, ntr, util, tk = portfolio(d, tsplit, T - 61, 0.0025, slots, Cff, dates)
    tr = d.iloc[tk] if len(tk) else d.iloc[:0]
    slot = 100000 / slots
    rawp = tr.ep.values * CR[tr.t.values, tr.j.values] / C[tr.t.values, tr.j.values]
    sh = np.floor(slot / rawp); inv = sh * rawp; P(f'{vid} OOS taken trades {len(tr)}: median raw entry price Rs {np.median(rawp):.0f}, mean unused slot cash from whole shares {100*(1-inv/slot).mean():.2f}% (max {100*(1-inv/slot).max():.1f}%), trades with 0 shares {int((sh<1).sum())}; slot Rs {slot:,.0f}; DP charge Rs 15.93 = {100*15.93/slot:.3f}% of slot')
R = pd.DataFrame(rows); R.round(2).to_csv(os.path.join(OUT, 'E_cost_windows.csv'), index=False); P(R.round(1).to_string())
open(os.path.join(OUT, 'E_robust.txt'), 'w').write('\n'.join(out))
