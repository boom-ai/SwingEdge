"""Build markdown tables + universe.txt. usage: python3 -I 06_tables.py <work> <out> <universe.txt>"""
import sys, os, numpy as np, pandas as pd
WORK, OUT, UNI = sys.argv[1:4]
r = pd.read_csv(os.path.join(OUT, 'results.csv')); y = pd.read_csv(os.path.join(OUT, 'by_year.csv')); dg = pd.read_csv(os.path.join(OUT, 'diag.csv'))
r['s'] = r.strategy.str.split().str[0]; y['s'] = y.strategy.str.split().str[0]
L = []
def md(df): 
    L.append('| ' + ' | '.join(map(str, df.columns)) + ' |'); L.append('|' + '---|' * len(df.columns))
    for row in df.itertuples(index=False): L.append('| ' + ' | '.join(('%.2f' % v) if isinstance(v, float) else str(v) for v in row) + ' |')
    L.append('')
L.append('### T0. Strategy key\n'); md(r[['s', 'strategy']].drop_duplicates().rename(columns={'s': 'id'}))
def main(cost, regime='none'):
    x = r[(r.cost == cost) & (r.regime == regime)]; out = []
    for (s, e), g in x.groupby(['s', 'entry'], sort=False):
        g = g.set_index('period'); row = dict(id=s, entry=e[0])
        for p in ('IS', 'OOS'):
            q = g.loc[p]; row.update({f'{p} n': int(q.n), f'{p} win%': q.win, f'{p} avg%': q.avg, f'{p} med%': q['med'], f'{p} 95%CI': '%.2f..%.2f' % (q.ci_lo, q.ci_hi), f'{p} t': q.t_clust,
                                      f'{p} PF': q.pf, f'{p} hold': q.hold, f'{p} CAGR%': q.cagr, f'{p} MDD%': q.mdd})
        out.append(row)
    return pd.DataFrame(out)
L.append('### T1. All candidates, no regime filter, cost 0.25% (IS = 2013-2021, OOS = 2022 - Nov 2026)\nentry a = D close, b = D+1 open. avg/med = net % per trade; CI/t = month-clustered bootstrap; CAGR/MDD = 4-slot portfolio.\n'); md(main(0.25))
L.append('### T2. Same, cost 0.40%\n'); md(main(0.40)[['id', 'entry', 'IS n', 'IS win%', 'IS avg%', 'IS 95%CI', 'IS PF', 'IS CAGR%', 'IS MDD%', 'OOS n', 'OOS win%', 'OOS avg%', 'OOS 95%CI', 'OOS PF', 'OOS CAGR%', 'OOS MDD%']])
x = r[(r.cost == 0.25) & (r.period != 'ALL')]
p = x.pivot_table(index=['s', 'entry'], columns=['regime', 'period'], values='avg'); p.columns = [f'{a} {b} avg%' for a, b in p.columns]
p2 = x.pivot_table(index=['s', 'entry'], columns=['regime', 'period'], values='cagr'); p2.columns = [f'{a} {b} CAGR%' for a, b in p2.columns]
L.append('### T3. Market-regime filter (Nifty 50 above its 50DMA / 200DMA) vs none, cost 0.25%: avg net % per trade\n'); md(p.reset_index().assign(entry=lambda d: d.entry.str[0]))
L.append('### T4. Same: 4-slot portfolio CAGR %\n'); md(p2.reset_index().assign(entry=lambda d: d.entry.str[0]))
for e in ('a:close', 'b:next open'):
    q = y[(y.cost == 0.25) & (y.regime == 'none') & (y.entry == e)]
    L.append(f'### T5{e[0]}. By calendar year, entry {e}, no regime filter, cost 0.25%: avg net % per trade\n'); md(q.pivot_table(index='s', columns='year', values='avg').reset_index())
    L.append(f'### T6{e[0]}. By calendar year, entry {e}: 4-slot portfolio return %\n'); md(q.pivot_table(index='s', columns='year', values='port_ret').reset_index())
    L.append(f'### T7{e[0]}. By calendar year, entry {e}: number of trades\n'); md(q.pivot_table(index='s', columns='year', values='n').fillna(0).astype(int).reset_index())
L.append('### T8. Clustering diagnostics (cost 0.25%, no regime filter). day_wtd = every signal day weighted equally; top4 = only the 4 best-ranked signals per day (what a 4-slot account can actually take)\n'); md(dg)
# IS-only selection per family
sel = r[(r.cost == 0.25) & (r.entry == 'a:close') & (r.period == 'IS') & (r.fam != 'BASE')].sort_values('t_clust', ascending=False).groupby('fam').head(1)
out = []
for q in sel.itertuples():
    for e in ('a:close', 'b:next open'):
        for cst in (0.25, 0.40):
            g = r[(r.strategy == q.strategy) & (r.regime == q.regime) & (r.entry == e) & (r.cost == cst)].set_index('period')
            out.append(dict(family=q.fam, chosen=q.s, regime=q.regime, entry=e[0], cost=cst, IS_n=int(g.loc['IS'].n), IS_avg=g.loc['IS'].avg, IS_t=g.loc['IS'].t_clust, IS_CAGR=g.loc['IS'].cagr,
                            OOS_n=int(g.loc['OOS'].n), OOS_win=g.loc['OOS'].win, OOS_avg=g.loc['OOS'].avg, OOS_CI='%.2f..%.2f' % (g.loc['OOS'].ci_lo, g.loc['OOS'].ci_hi), OOS_t=g.loc['OOS'].t_clust, OOS_PF=g.loc['OOS'].pf, OOS_CAGR=g.loc['OOS'].cagr, OOS_MDD=g.loc['OOS'].mdd))
L.append('### T9. Honest selection: per family the variant+regime with the best IN-SAMPLE clustered t-stat (entry a, cost 0.25%), then reported out-of-sample unchanged\n'); md(pd.DataFrame(out))
open(os.path.join(OUT, 'tables.md'), 'w').write('\n'.join(L))
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
liq = (W['to_cr'].rolling(20, min_periods=15).mean().iloc[-1] >= 25) & (W['craw'].iloc[-1] >= 50)
u = sorted(liq.index[liq]); open(UNI, 'w').write('\n'.join(u) + '\n'); print(len(u), W['c'].index[-1])
print(pd.DataFrame(out)[['family','chosen','regime','entry','cost','IS_avg','IS_t','OOS_avg','OOS_t','OOS_CAGR','OOS_MDD']].round(2).to_string())
