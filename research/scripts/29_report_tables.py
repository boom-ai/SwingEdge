"""Builds the markdown tables used in REPORT4.md from the out_r4 CSVs. usage: python3 -I 29_report_tables.py <out_r4 dir> -> report_tables.md"""
import sys, os, numpy as np, pandas as pd
OUT = sys.argv[1]; R = []
def md(df, fl=2):
    df = df.copy()
    for c in df.columns:
        if df[c].dtype.kind == 'f': df[c] = df[c].map(lambda v: '' if pd.isna(v) else f'{v:.{fl}f}')
    return '| ' + ' | '.join(map(str, df.columns)) + ' |\n|' + '---|' * len(df.columns) + '\n' + '\n'.join('| ' + ' | '.join(map(str, r)) + ' |' for r in df.values) + '\n'
# T1 ingredients: Q5/Q1 or yes, 20 and 60 days, entry b
for pn in ('IS', 'OOS'):
    d = pd.read_csv(os.path.join(OUT, f'ingredients_{pn}.csv')); d = d[(d.entry == 'b') & d.H.isin([20, 60])]
    s = pd.read_csv(os.path.join(OUT, f'ingredient_spreads_{pn}.csv')); s = s[(s.entry == 'b') & s.H.isin([20, 60])]
    rows = []
    for ing in ['ram', 'rs6', 'rs12', 'rsavg', 'prox52', 'above52wlow', 'ext50', 'r21', 'lowvol126', 'tight', 'voldry', 'off20', 'rsi2', 'rsi5']:
        r = {'ingredient (Q5 = high)': ing}
        for H in (20, 60):
            q = d[(d.ingredient == ing) & (d.H == H)].set_index('group')
            sp = s[(s.ingredient == ing) & (s.H == H)].iloc[0]
            r[f'Q1 {H}d'] = q.loc['Q1', 'avg_ex']; r[f'Q5 {H}d'] = q.loc['Q5', 'avg_ex']; r[f'Q5-Q1 {H}d'] = sp.q5_minus_q1; r[f't {H}d'] = sp.t_nw
        r['Q5 med 60d'] = q.loc['Q5', 'med_ex']; r['Q5 p25..p75 60d'] = f"{q.loc['Q5', 'p25_ex']:.1f}..{q.loc['Q5', 'p75_ex']:.1f}"
        rows.append(r)
    R.append(f'### T1-{pn}. Continuous ingredients, quintiles, excess % net of 0.25%, entry D+1 open ({pn})\n\n' + md(pd.DataFrame(rows)))
    rows = []
    for ing in ['TT', 'stage2', 'stage2_early', 'stage2_late', 'beats_nifty_6_1', 'beats_nifty_12_1', 'bo20_vol', 'bo20_vol_tightbase', 'TT_pullback3_8',
                'TT_rsi2_lt10', 'TT_ext50_gt20', 'TT_near_high_lt5']:
        r = {'condition': ing}
        for H in (20, 60):
            q = d[(d.ingredient == ing) & (d.H == H)].set_index('group')
            r[f'yes {H}d'] = q.loc['yes', 'avg_ex']; r[f't {H}d'] = q.loc['yes', 't_nw']; r[f'no {H}d'] = q.loc['no', 'avg_ex']
        r['yes n'] = int(q.loc['yes', 'n']); r['yes med 60d'] = q.loc['yes', 'med_ex']
        rows.append(r)
    R.append(f'### T2-{pn}. Binary ingredients, excess % net of 0.25%, entry D+1 open ({pn})\n\n' + md(pd.DataFrame(rows)))
    tt = d[(d.ingredient == 'tt_count')].pivot_table(index='group', columns='H', values='avg_ex').reset_index()
    tt.columns = ['trend-template conditions met'] + [f'{c}d' for c in tt.columns[1:]]
    R.append(f'### T2b-{pn}. Trend-template count (0-7 of 7), excess %\n\n' + md(tt))
    g = pd.read_csv(os.path.join(OUT, f'regime_{pn}.csv')); g = g[g.H == 60][['state', 'value', 'days', 'univ_avg_raw', 't_nw', 'TT_avg_excess', 'TT_t']]
    R.append(f'### T3-{pn}. Regime (60d): universe-average raw return (no cost) and trend-template excess by market state\n\n' + md(g))
# composite candidates IS
c = pd.read_csv(os.path.join(OUT, 'composite_candidates_IS.csv')); c = c[c.regime == 'all']
cp = c.pivot_table(index='comp', columns=['band', 'H'], values='avg_ex')
rows = []
for k in cp.index:
    r = {'candidate': k}
    for b in ['0-40', '40-55', '55-70', '70-85', '85-100']: r[b] = cp.loc[k, (b, 60)]
    r['top t'] = c[(c.comp == k) & (c.band == '85-100') & (c.H == 60)].t_nw.iloc[0]
    rows.append(r)
R.append('### T4. Candidate composites, IN-SAMPLE only, 60d excess % by score band (entry D+1 open)\n\n' + md(pd.DataFrame(rows)))
# bands final
B = pd.read_csv(os.path.join(OUT, 'bands_all.csv'))
for kind in ('excess', 'raw'):
    for ent in ('b', 'a'):
        x = B[(B.entry == ent) & (B.H == 60) & (B.cost == 0.25) & (B.kind == kind) & B.period.isin(['IS', 'OOS']) &
              B.group.isin(['0-39', '40-54', '55-69', '70-84', '85-100', 'BUY(no gate)', '>=85 & below SMA50', '>=85 & Nifty<SMA200', 'ALL U'])]
        x = x[['period', 'group', 'n', 'daily_avg', 't_nw', 'avg', 'win', 'median', 'p25', 'p75', 'p05']]
        x.loc[x.group == 'ALL U', 't_nw'] = np.nan
        R.append(f'### T5-{kind}-{ent}. Final score bands, 60 sessions, {kind} % net of 0.25%, entry {"D+1 open" if ent == "b" else "D close"}\n\n' + md(x))
x = B[(B.cost == 0.25) & (B.kind == 'excess') & B.group.isin(['0-39', '40-54', '55-69', '70-84', '85-100', 'BUY(no gate)']) & B.period.isin(['IS', 'OOS'])]
xp = x.pivot_table(index=['period', 'group'], columns=['entry', 'H'], values='daily_avg').round(2)
xp.columns = [f'{"open" if e == "b" else "close"} {h}d' for e, h in xp.columns]
R.append('### T6. Band excess % (mean of daily averages) by horizon and entry convention\n\n' + md(xp.reset_index()))
# exits IS
E = pd.read_csv(os.path.join(OUT, 'exits_all.csv'))
for pn in ('IS', 'OOS'):
    e = E[(E.signals == 'BUY(no gate)') & (E.entry == 'b') & (E.cost == 0.25) & (E.period == pn) & (E.gapmax == -1)]
    e = e[['scheme', 'n', 'win', 'exp', 'median', 'p05', 'worst', 'hold', 'exp_per_day', 'pf', 'hit_t1', 'hit_t2', 'stopped']]
    R.append(f'### T7-{pn}. Exit schemes on BUY signals (no gate), entry D+1 open, cost 0.25%, one trade per stock at a time ({pn})\n\n' + md(e, 3))
P = pd.read_csv(os.path.join(OUT, 'plan_trades.csv'))
p = P[(P.signals == 'nogate') & (((P.plan == 'X10') & (P.gapmax == 0.5)) | ((P.plan == 'X0') & (P.gapmax == -1)))]
p = p[['plan', 'entry', 'cost', 'period', 'n', 'win', 'exp', 'median', 'p25', 'p75', 'p05', 'worst', 'hold', 'pf', 't_daily', 'stopped', 'touch_t1', 'touch_t2']]
R.append('### T8. Recommended plan X10 (with 0.5-ATR entry rule) and the pure 60-day hold X0, all BUY signals, one trade per stock at a time\n\n' + md(p))
# portfolio
PF = pd.read_csv(os.path.join(OUT, 'portfolio.csv')); PF['strat'] = PF.strategy.str.replace(r' seed\d', '', regex=True)
g = PF.groupby(['strat', 'signals', 'plan', 'entry', 'cost', 'slots', 'period'])[['cagr', 'mdd', 'worst_year', 'trades_per_year']].median().reset_index()
rows = []
for (st, sig, pl), _ in g.groupby(['strat', 'signals', 'plan']):
    for ent in ('b', 'a'):
        for cost in (0.25, 0.40):
            for slots in (4, 8):
                r = {'portfolio': f'{st} {pl} {"gate" if sig == "gated" else "no gate"}', 'entry': 'open' if ent == 'b' else 'close', 'cost': f'{cost:.2f}', 'slots': slots}
                for pn in ('IS', 'OOS', 'FULL'):
                    q = g[(g.strat == st) & (g.signals == sig) & (g.plan == pl) & (g.entry == ent) & (g.cost == cost) & (g.slots == slots) & (g.period == pn)]
                    if len(q) == 0: continue
                    q = q.iloc[0]; r[f'{pn} CAGR'] = q.cagr; r[f'{pn} MDD'] = q.mdd
                    if pn == 'FULL': r['worst yr'] = q.worst_year; r['trades/yr'] = q.trades_per_year
                rows.append(r)
R.append('### T9. Rs 1,00,000 portfolio, equal weight, top score first (RANDOM = median of 5 seeds, random liquid stock, same plan X10)\n\n' + md(pd.DataFrame(rows), 1))
BM = pd.read_csv(os.path.join(OUT, 'benchmarks.csv'))
R.append('### T10. Benchmarks over the same spans\n\n' + md(BM, 1))
Y = pd.read_csv(os.path.join(OUT, 'portfolio_years.csv')); Y['strategy'] = Y.strategy.str.replace(r' seed\d', '', regex=True)
s = Y[(Y.strategy.isin(['SCORE', 'RANDOM'])) & (Y.signals == 'nogate') & (Y.cost == 0.25) & (Y.entry == 'b')]
yp = s.groupby(['strategy', 'plan', 'slots', 'year']).ret.median().unstack('year')
yp.index = [f'{a} {b} {int(c)} slots, open' for a, b, c in yp.index]
bb = Y[Y.strategy.str.contains('Nifty|EW')].pivot_table(index='strategy', columns='year', values='ret')
yy = pd.concat([yp, bb]).reset_index().rename(columns={'index': 'calendar-year return %'})
yy.columns = [str(c) for c in yy.columns]
R.append('### T11. Calendar-year returns %, no regime gate, entry D+1 open, cost 0.25% (2013 and 2026 partial)\n\n' + md(yy, 1))
C_ = pd.read_csv(os.path.join(OUT, 'concentration.csv'))
cy = C_[C_.cut == 'year'].pivot_table(index='value', columns='group', values='daily_avg')[['85-100', 'BUY(no gate)', '0-39']]
cy['EW universe raw 60d'] = C_[(C_.cut == 'year') & (C_.group == '85-100')].set_index('value').univ_raw
R.append('### T12. 60d excess % by signal year (entry open, 0.25%)\n\n' + md(cy.reset_index().rename(columns={'value': 'year'})))
cs = C_[C_.cut != 'year'][['group', 'cut', 'value', 'n', 'daily_avg', 't_nw', 'median']]
cs = cs[cs.group.isin(['85-100', 'BUY(no gate)', '0-39'])]
R.append('### T13. 60d excess % by size proxy (rank of 20d turnover within the universe) and by regime\n\n' + md(cs))
for f, t in (('diag_taken.csv', 'T14. Trades taken by the portfolio vs all BUY signals (X10, open, 0.25%)'), ('diag_subband.csv', 'T15. Plan X10 per-trade by score inside BUY'),
             ('diag_beta.csv', 'T16. Daily-return regression of the BUY portfolio (X10, no gate, open, 0.25%) on Nifty and on the EW universe; alpha annualised %')):
    R.append(f'### {t}\n\n' + md(pd.read_csv(os.path.join(OUT, f)), 3))
W_ = pd.read_csv(os.path.join(OUT, 'wait_conditions_IS.csv')); W_ = W_[(W_.H == 60) & (W_.score_ge == 85)][['cond', 'n', 'days', 'avg_ex', 't', 'avg_raw', 'med_raw']]
R.append('### T17. IN-SAMPLE: conditions inside score >= 85, 60d (used to define WAIT)\n\n' + md(W_))
pdp = pd.read_csv(os.path.join(OUT, 'path_dependence_summary.csv')); pdp = pdp[~pdp.plan.str.startswith('RANDOM')]   # single-seed random rows superseded by T19
R.append('### T18. Path dependence: CAGR % over 20 start dates (offset 0..57 sessions), entry open, cost 0.25%, no gate\n\n' + md(pdp, 1))
R.append('### T19. SCORE portfolio vs 100 random-stock portfolios (same plan, slots, start jitter), CAGR %\n\n' + md(pd.read_csv(os.path.join(OUT, 'random_null_summary.csv')), 1))
open(os.path.join(OUT, 'report_tables.md'), 'w').write('\n'.join(R)); print('ok', len(R))
