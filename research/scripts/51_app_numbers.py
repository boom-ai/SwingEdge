"""Round 5: build out_r5/app_numbers.json from out_r5/r5_raw.json (no new computation)."""
import json
R = json.load(open('/home/claude/research/out_r5/r5_raw.json'))
a = R['avoid_choice']; q = R['q1'][a]; p = R['plan_choice']; T = R['q2_trades']; P = R['q2_port']; B = R['bench']['OOS']
r1 = lambda x, d=1: round(float(x), d)
tr = lambda c: {k: (r1(v, 2) if isinstance(v, float) else v) for k, v in T[f'{p}|OOS|{c}'].items() if k in ('n', 'winPct', 'avgPct', 'medianPct', 'p05', 'avgSessions', 'profitFactor')}
pf = lambda s: {'cagrMedianPct': r1(P[f'{p}|OOS|{s}|0.005']['cagr']), 'cagrRangePct': [r1(P[f'{p}|OOS|{s}|0.005']['cmin']), r1(P[f'{p}|OOS|{s}|0.005']['cmax'])],
                'cagrMedianPctAt025': r1(P[f'{p}|OOS|{s}|0.0025']['cagr']), 'maxDrawdownPctMedian': r1(P[f'{p}|OOS|{s}|0.005']['mdd_med']), 'maxDrawdownPctWorst': r1(P[f'{p}|OOS|{s}|0.005']['mdd_worst']),
                'percentileVsMatchedNull': r1(P[f'{p}|OOS|{s}|0.005']['pctA'], 0), 'matchedNullCagrMedianPct': r1(P[f'{p}|OOS|{s}|0.005']['nullA_med'])}
out = {
 'generated': '2026-11-12', 'source': 'research/out_r5 (round 5, pre-registered in out_r5/PREREG.md)', 'descriptiveOnly': True, 'provenForRealMoney': R['realmoney'],
 'oosWindow': {'from': '2022-01-03', 'to': '2025-01-29', 'note': 'signals touching the 2025-05-05..2025-06-20 data hole excluded; ~3.1 years'},
 'avoid': {'rule': 'S < 55 AND C < SMA200', 'horizonSessions': 60, 'lagVsEqualWeightPct': r1(q['OOS']['EW']['mean'], 2), 'tVsEqualWeight': r1(q['OOS']['EW']['t']),
           'lagVsTurnoverWeightedPct': r1(q['OOS']['TW']['mean'], 2), 'tVsTurnoverWeighted': r1(q['OOS']['TW']['t']),
           'yearsNegativeVsEqualWeight': [q['OOS']['EW']['yneg'], q['OOS']['EW']['ytot']], 'yearsNegativeVsTurnoverWeighted': [q['OOS']['TW']['yneg'], q['OOS']['TW']['ytot']],
           'flaggedSharePct': r1(q['OOS']['flagged_pct'], 0), 'rawAvgPct': r1(q['OOS']['raw_mean'], 2), 'rawMedianPct': r1(q['OOS']['raw_median'], 2),
           'full2013to2025': {'lagVsEqualWeightPct': r1(q['FULL']['EW']['mean'], 2), 't': r1(q['FULL']['EW']['t']), 'lagVsTurnoverWeightedPct': r1(q['FULL']['TW']['mean'], 2), 'tTW': r1(q['FULL']['TW']['t']),
                              'yearsNegativeEW': [q['FULL']['EW']['yneg'], q['FULL']['EW']['ytot']], 'yearsNegativeTW': [q['FULL']['TW']['yneg'], q['FULL']['TW']['ytot']]}},
 'buyPlan': {'id': p, 'name': 'next-open entry within 0.5 ATR, fixed 5-ATR disaster stop, hold 60 sessions', 'perTradeOOS': {'cost0.50': tr(0.005), 'cost0.25': tr(0.0025)},
             'matchedNullPerTradeAvgPctAt050': r1(T[f'{p}|OOS|0.005']['nullA_avg'], 2),
             'portfolio4SlotOOS': pf(4), 'portfolio8SlotOOS': pf(8), 'niftyCagrPct': r1(B['nifty_cagr']), 'niftyMaxDrawdownPct': r1(B['nifty_mdd']), 'equalWeightCagrPct': r1(B['ew_cagr']), 'equalWeightMaxDrawdownPct': r1(B['ew_mdd']),
             'contaminationNote': 'Plan chosen after round 4 saw 2022-26 among 11 variants; best-of-11 random variants median null percentile ~94, so OOS percentiles below ~94 are consistent with no edge.'},
 'selection': {'rule': 'highest score first; ties symbol ascending', 'changedFromREPORT4': False},
 'exposure': {'rule': 'none (all slots usable regardless of breadth)', 'testedAndRejected': 'slots full if breadth50>=0.50, half if 0.30-0.50, none below 0.30'},
 'costAssumptionRoundTripPct': 0.50}
json.dump(out, open('/home/claude/research/out_r5/app_numbers.json', 'w'), indent=1); print(json.dumps(out, indent=1))
