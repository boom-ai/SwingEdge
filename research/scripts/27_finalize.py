"""Round 4, step 6: final score_bands.json + fixtures (test vectors) recomputed in float64 strictly from the REPORT4 spec.
usage: python3 -I 27_finalize.py <work dir> <out_r4 dir>       runtime about 1 min
Writes score_bands.json, fixtures.csv, fixture_prices.csv (own-stock OHLCV look-back + NIFTY50 rows), fixture_universe.csv
(component raw values of every rated universe member on each fixture date, so the cross-sectional percentiles can be recomputed).
"""
import sys, os, json, importlib.util, numpy as np, pandas as pd
WORK, OUT = sys.argv[1:3]
spec = importlib.util.spec_from_file_location('f20', os.path.join(os.path.dirname(os.path.abspath(__file__)), '20_features.py'))
f20 = importlib.util.module_from_spec(spec); spec.loader.exec_module(f20)
M = pd.read_pickle(os.path.join(OUT, 'meta.pkl')); SC = np.load(os.path.join(OUT, 'score.npz')); dates, reg, syms = M['dates'], M['reg'], M['syms']
B = pd.read_csv(os.path.join(OUT, 'bands_all.csv')); P = pd.read_csv(os.path.join(OUT, 'plan_trades.csv')); PF = pd.read_csv(os.path.join(OUT, 'portfolio.csv'))
BM = pd.read_csv(os.path.join(OUT, 'benchmarks.csv'))
HZ = 60
# ------------------------------------------------------------------ JSON
def pick(group, period, kind):
    r = B[(B.entry == 'b') & (B.H == HZ) & (B.cost == 0.25) & (B.kind == kind) & (B.period == period) & (B.group == group)].iloc[0]
    return {'n': int(r.n), 'winPct': round(r.win, 1), 'avgPct': round(r.avg, 2), 'medianPct': round(r['median'], 2), 'p25': round(r.p25, 2),
            'p75': round(r.p75, 2), 'worstPct': round(r.p05, 2), 'dailyAvgPct': round(r.daily_avg, 2), 'tNeweyWest': round(r.t_nw, 2)}
bands = {}
for gname, label in (('0-39', '0-39'), ('40-54', '40-54'), ('55-69', '55-69'), ('70-84', '70-84'), ('85-100', '85-100'), ('BUY(no gate)', 'BUY'),
                     ('>=85 & below SMA50', 'score>=85 & close<=SMA50')):
    bands[label] = {f'{p}_{k}': pick(gname, p, k) for p in ('FULL', 'OOS') for k in ('raw', 'excess')}
def plan(period, entry='b', cost=0.25):
    r = P[(P.signals == 'nogate') & (P.plan == 'X10') & (P.gapmax == 0.5) & (P.entry == entry) & (P.cost == cost) & (P.period == period)].iloc[0]
    return {'trades': int(r.n), 'winPct': round(r.win, 1), 'expectancyPct': round(r.exp, 2), 'medianPct': round(r['median'], 2), 'p25': round(r.p25, 2),
            'p75': round(r.p75, 2), 'worstPct': round(r.p05, 2), 'worstSingleTradePct': round(r.worst, 2), 'avgHoldDays': round(r.hold, 1),
            'profitFactor': round(r.pf, 2), 'stopHitPct': round(r.stopped, 1), 't1TouchedPct': round(r.touch_t1, 1), 't2TouchedPct': round(r.touch_t2, 1)}
def port(period, slots, entry='b', cost=0.25):
    r = PF[(PF.strategy == 'SCORE') & (PF.signals == 'nogate') & (PF.plan == 'X10') & (PF.entry == entry) & (PF.cost == cost) & (PF.slots == slots) & (PF.period == period)].iloc[0]
    return {'cagrPct': round(r.cagr, 1), 'maxDrawdownPct': round(r.mdd, 1), 'worstYearPct': round(r.worst_year, 1), 'tradesPerYear': round(r.trades_per_year, 1)}
nif = BM[BM.bench.str.startswith('Nifty')].set_index('period')
J = {'version': 'r4-2026-11-12', 'descriptiveOnly': True,
     'verdict': 'Score bands are monotonic out-of-sample (low scores reliably lag the average liquid stock), but the top band and the BUY rule did not '
                'deliver a statistically reliable edge after costs out-of-sample, and the BUY portfolio (4 or 8 slots) did worse than the Nifty 50 in 2022-2026. '
                'Show the score and these historical numbers as descriptive context, not as a trading instruction.',
     'horizonDays': HZ, 'entryConvention': 'next session open after the signal close (D+1 open)', 'costRoundTripPct': 0.25,
     'definitions': {'raw': 'stock return D+1 open -> close of D+60 minus 0.25% cost', 'excess': 'raw minus the equal-weight average return (no cost) of all rated '
                     'universe stocks over the same days', 'n': 'stock-days, overlapping (one observation per stock per signal day)',
                     'worstPct': '5th percentile', 'dailyAvgPct': 'mean of daily cross-sectional averages', 'tNeweyWest': 't of dailyAvgPct, Newey-West lag 60',
                     'FULL': 'signal days 2013-01-01 .. 2026-08 (last day with 60 forward sessions)', 'OOS': 'signal days 2022-01-01 .. 2026-08'},
     'bands': bands,
     'thresholds': {'NOT_RATED': 'not liquid, fewer than 252 own bars, or a component undefined', 'AVOID': 'score < 55',
                    'BUY': 'score >= 85 AND close > SMA50 AND not upper-circuit-locked (no market-regime gate)', 'WAIT': 'rated, score >= 55, not BUY'},
     'tradePlan': {'rule': 'entry D+1 open only if open <= C[D] + 0.5*ATR14[D]; stop = fill - 3*ATR14[D] (gap through stop exits at the open); '
                           'exit at the next open after any close < SMA50; time exit at the close of D+60; t1 = fill + 6*ATR14[D], t2 = fill + 9*ATR14[D] '
                           'are reference levels (touch rates shown), NOT sell orders',
                   'FULL': plan('FULL'), 'OOS': plan('OOS'), 'IS': plan('IS'), 'OOS_cost0.40': plan('OOS', cost=0.40), 'OOS_closeEntry': plan('OOS', entry='a')},
     'portfolio_100k': {f'{s}slots_{p}': port(p, s) for s in (4, 8) for p in ('IS', 'OOS', 'FULL')},
     'nifty50_price': {p: {'cagrPct': round(nif.loc[p, 'cagr'], 1), 'maxDrawdownPct': round(nif.loc[p, 'mdd'], 1)} for p in ('IS', 'OOS', 'FULL')}}
RN = pd.read_csv(os.path.join(OUT, 'random_null_summary.csv'))
J['portfolioRobustness'] = {'note': 'CAGR % over 20 staggered start dates (SCORE) vs 100 random-stock portfolios with the same plan, slots and start jitter '
                            '(entry D+1 open, cost 0.25%, no gate). Plan X10 is the recommended plan; X0 = pure 60-session hold (not selected in-sample).',
                            'rows': RN.to_dict(orient='records')}
json.dump(J, open(os.path.join(OUT, 'score_bands.json'), 'w'), indent=1)
print('json written')
# ------------------------------------------------------------------ fixtures (float64, from the spec)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
O, H, L, C, V, TO, CR = (W[k].values for k in ('o', 'h', 'l', 'c', 'v', 'to_cr', 'craw')); T, N = C.shape
FE = {}
for j in range(N):
    idx = np.where(~np.isnan(C[:, j]))[0]
    if len(idx) == 0: continue
    FE[j] = (idx, f20.stock_feats(O[idx, j], H[idx, j], L[idx, j], C[idx, j], V[idx, j], CR[idx, j], TO[idx, j]))
def val(j, t, k):
    if j not in FE: return np.nan
    idx, F = FE[j]; p = np.searchsorted(idx, t)
    return F[k][p] if p < len(idx) and idx[p] == t else np.nan
def day_universe(t):
    rows = []
    for j, (idx, F) in FE.items():
        p = np.searchsorted(idx, t)
        if p >= len(idx) or idx[p] != t: continue
        nb = F['nbars'][p]; liq = (F['to20'][p] >= 25) and (CR[t, j] >= 50)
        if not liq or nb < 252: continue
        c = C[t, j]
        rows.append(dict(symbol=syms[j], j=j, nbars=int(nb), ram=F['r6_1'][p] / (F['vol126'][p] * np.sqrt(105)), r6_1=F['r6_1'][p], r12_1=F['r12_1'][p],
                         prox52=c / F['hi252'][p], low52=c / F['lo252'][p]))
    u = pd.DataFrame(rows)
    for k in ('ram', 'r6_1', 'r12_1', 'prox52', 'low52'):
        u['p_' + k] = u[k].rank(pct=True, method='average')        # NaN values excluded from both rank and count
    s = (100.0 * (u.p_ram + u.p_r6_1 + u.p_r12_1 + u.p_prox52 + u.p_low52)) / 5.0
    u['score'] = np.rint(s * 10.0) / 10.0
    return u
S = SC['S']; rng = np.random.default_rng(2026)
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01')))
cands = []
bands_def = [(0, 40), (40, 55), (55, 70), (70, 85), (85, 101)]
years = list(range(2013, 2027))
for bi, (a, b) in enumerate(bands_def):
    for k in range(7):
        y = years[(bi * 7 + k * 2) % len(years)]
        ts_ = np.where(dates.year == y)[0]; t = int(rng.choice(ts_))
        js_ = np.where((S[t] >= a) & (S[t] < b))[0]
        if len(js_) == 0: continue
        cands.append((t, int(rng.choice(js_)), f'band {a}-{min(b, 100) - (1 if b <= 100 else 0)}'))
# special cases
liq_all = np.load(os.path.join(OUT, 'feat.npz'))['liq']; nb_all = np.load(os.path.join(OUT, 'feat.npz'))['nbars']
t = int(np.searchsorted(dates, pd.Timestamp('2024-03-15'))); js_ = np.where(liq_all[t] & (nb_all[t] < 252))[0]; cands.append((t, int(js_[0]), 'special: <252 bars'))
t = int(np.searchsorted(dates, pd.Timestamp('2025-07-01'))); js_ = np.where(liq_all[t] & (nb_all[t] < 120))[0]; cands.append((t, int(js_[0]), 'special: <252 bars'))
t = int(np.searchsorted(dates, pd.Timestamp('2019-05-02'))); js_ = np.where(~liq_all[t] & ~np.isnan(C[t]) & (nb_all[t] > 300))[0]; cands.append((t, int(js_[0]), 'special: illiquid'))
b85 = np.argwhere((S >= 85) & (C <= np.load(os.path.join(OUT, 'feat.npz'))['sma50'])); r = b85[rng.integers(len(b85))]; cands.append((int(r[0]), int(r[1]), 'special: >=85 below SMA50'))
uc = np.argwhere((S >= 85) & np.load(os.path.join(OUT, 'feat.npz'))['uclock'] & (C > np.load(os.path.join(OUT, 'feat.npz'))['sma50']))
r = uc[rng.integers(len(uc))]; cands.append((int(r[0]), int(r[1]), 'special: upper-circuit lock'))
fx = []; uni = []; price_rows = []; ucache = {}
for (t, j, why) in cands:
    if t not in ucache:
        ucache[t] = day_universe(t)
        u = ucache[t]; uu = u[['symbol', 'nbars', 'ram', 'r6_1', 'r12_1', 'prox52', 'low52']].copy(); uu.insert(0, 'date', dates[t].date()); uni.append(uu)
    u = ucache[t]; idx, F = FE[j]; p = np.searchsorted(idx, t); c = C[t, j]
    nb = int(F['nbars'][p]); to20 = F['to20'][p]; liquid = bool(to20 >= 25 and CR[t, j] >= 50)
    r1 = F['ret1'][p]; uclock = bool(c >= H[t, j] * 0.9995 and min(abs(r1 - b_) for b_ in (0.05, 0.10, 0.20)) < 0.003) if not np.isnan(r1) else False
    row = dict(case=why, symbol=syms[j], date=dates[t].date(), nbars=nb, close_raw=CR[t, j], to20_cr=to20, liquid=liquid,
               o=O[t, j], h=H[t, j], l=L[t, j], c=c, ret1=r1, upper_circuit_lock=uclock,
               c_lag21=C[idx[p - 21], j] if p >= 21 else np.nan, c_lag126=C[idx[p - 126], j] if p >= 126 else np.nan, c_lag252=C[idx[p - 252], j] if p >= 252 else np.nan,
               r6_1=F['r6_1'][p], r12_1=F['r12_1'][p], vol126=F['vol126'][p], ram=F['r6_1'][p] / (F['vol126'][p] * np.sqrt(105)),
               hi252=F['hi252'][p], lo252=F['lo252'][p], prox52=c / F['hi252'][p], low52=c / F['lo252'][p], sma50=F['sma50'][p], atr14=F['atr14'][p],
               universe_n=len(u))
    m = u[u.j == j]
    if len(m) and not np.isnan(m.score.iloc[0]):
        m = m.iloc[0]
        for k in ('ram', 'r6_1', 'r12_1', 'prox52', 'low52'): row['p_' + k] = m['p_' + k]
        row['score_raw'] = (100.0 * (m.p_ram + m.p_r6_1 + m.p_r12_1 + m.p_prox52 + m.p_low52)) / 5.0
        sc = m.score; row['score'] = sc
        row['pts_ram'] = round(20 * m.p_ram, 4); row['pts_r6_1'] = round(20 * m.p_r6_1, 4); row['pts_r12_1'] = round(20 * m.p_r12_1, 4)
        row['pts_prox52'] = round(20 * m.p_prox52, 4); row['pts_low52'] = round(20 * m.p_low52, 4)
        if sc < 55: lab = 'AVOID'
        elif sc >= 85 and c > F['sma50'][p] and not uclock: lab = 'BUY'
        else: lab = 'WAIT'
        row['band'] = '0-39' if sc < 40 else '40-54' if sc < 55 else '55-69' if sc < 70 else '70-84' if sc < 85 else '85-100'
    else:
        lab = 'NOT_RATED'; row['score'] = np.nan
    row['label'] = lab
    a_ = F['atr14'][p]
    if lab == 'BUY':
        row['entry_max'] = c + 0.5 * a_; row['stop_est'] = c - 3 * a_; row['t1_est'] = c + 6 * a_; row['t2_est'] = c + 9 * a_
    row['backtest_score'] = float(S[t, j]); row['nifty_close'] = reg['n50'].iloc[t]; row['nifty_sma200'] = reg['n50_sma200'].iloc[t]
    row['regime_note'] = 'Nifty>SMA200' if reg['n50'].iloc[t] > reg['n50_sma200'].iloc[t] else 'Nifty<SMA200'
    fx.append(row)
    lo_ = max(0, p - 299)
    for q in range(lo_, p + 1):
        tt = idx[q]; price_rows.append(dict(symbol=syms[j], date=dates[tt].date(), o=O[tt, j], h=H[tt, j], l=L[tt, j], c=C[tt, j], v=V[tt, j],
                                             close_raw=CR[tt, j], turnover_cr=TO[tt, j]))
fxd = pd.DataFrame(fx)
fxd.to_csv(os.path.join(OUT, 'fixtures.csv'), index=False, float_format='%.10g')
nf = pd.read_pickle(os.path.join(WORK, 'nifty50.pkl'))
pr = pd.DataFrame(price_rows).drop_duplicates(['symbol', 'date'])
n_rows = nf.loc[nf.index <= max(fxd.date.astype('datetime64[ns]'))].reset_index().rename(columns={'Date': 'date', 'Open': 'o', 'High': 'h', 'Low': 'l', 'Close': 'c'})
n_rows['symbol'] = 'NIFTY50'; n_rows['date'] = n_rows.date.dt.date
pr = pd.concat([pr, n_rows[n_rows.date >= pd.Timestamp('2012-02-01').date()][['symbol', 'date', 'o', 'h', 'l', 'c']]])
pr.to_csv(os.path.join(OUT, 'fixture_prices.csv'), index=False, float_format='%.10g')
pd.concat(uni).to_csv(os.path.join(OUT, 'fixture_universe.csv'), index=False, float_format='%.12g')
agree = fxd.dropna(subset=['score']); diff = (agree.score - agree.backtest_score).abs()
print(fxd[['case', 'symbol', 'date', 'score', 'backtest_score', 'label']].to_string())
print('fixtures', len(fxd), 'labels', fxd.label.value_counts().to_dict(), 'max |score - backtest score|', diff.max())
