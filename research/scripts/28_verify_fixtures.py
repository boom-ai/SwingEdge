"""Clean-room check of the REPORT4 spec: recompute every fixture from fixture_prices.csv + fixture_universe.csv only, with plain loops.
usage: python3 -I 28_verify_fixtures.py <out_r4 dir>"""
import sys, os, csv, math
OUT = sys.argv[1]
px = {}
for r in csv.DictReader(open(os.path.join(OUT, 'fixture_prices.csv'))):
    if r['symbol'] == 'NIFTY50': continue
    px.setdefault(r['symbol'], []).append((r['date'], float(r['o']), float(r['h']), float(r['l']), float(r['c']), float(r['close_raw']), float(r['turnover_cr'])))
uni = {}
for r in csv.DictReader(open(os.path.join(OUT, 'fixture_universe.csv'))):
    uni.setdefault(r['date'], []).append(r)
def pct(vals, x):           # average-rank percentile; vals = defined values incl. x
    less = sum(1 for v in vals if v < x); eq = sum(1 for v in vals if v == x)
    return (less + (eq + 1) / 2.0) / len(vals)
bad = 0; n = 0
for f in csv.DictReader(open(os.path.join(OUT, 'fixtures.csv'))):
    bars = [b for b in sorted(px[f['symbol']]) if b[0] <= f['date']]
    i = len(bars) - 1; C = [b[4] for b in bars]; Hh = [b[2] for b in bars]; Ll = [b[3] for b in bars]
    nb = int(f['nbars']); off = nb - len(bars)       # extract may start after the listing; nbars counts from listing
    to20 = sum(b[6] for b in bars[i - 19:i + 1]) / 20 if i >= 19 else float('nan')
    liquid = to20 >= 25 and bars[i][5] >= 50
    sma50 = sum(C[i - 49:i + 1]) / 50 if i >= 49 else float('nan')
    trs = []
    for k in range(i - 13, i + 1):
        pc = C[k - 1] if k >= 1 else None
        trs.append(Hh[k] - Ll[k] if pc is None else max(Hh[k] - Ll[k], abs(Hh[k] - pc), abs(Ll[k] - pc)))
    atr = sum(trs) / 14
    out = {'liquid': liquid, 'sma50': sma50, 'atr14': atr}
    if nb >= 252 and liquid:
        r6 = C[i - 21] / C[i - 126] - 1; r12 = C[i - 21] / C[i - 252] - 1 if i >= 252 else float('nan')
        rets = [C[k] / C[k - 1] - 1 for k in range(i - 125, i + 1)]; m = sum(rets) / 126
        vol = math.sqrt(sum((x - m) ** 2 for x in rets) / 125); ram = r6 / (vol * math.sqrt(105))
        prox = C[i] / max(Hh[i - 251:i + 1]); low = C[i] / min(Ll[i - 251:i + 1])
        U = uni[f['date']]; me = {'ram': ram, 'r6_1': r6, 'r12_1': r12, 'prox52': prox, 'low52': low}
        ps = []
        for k in ('ram', 'r6_1', 'r12_1', 'prox52', 'low52'):
            vals = [float(u[k]) for u in U if u['symbol'] != f['symbol'] and u[k] not in ('', 'nan')] + [me[k]]
            ps.append(pct(vals, me[k]))
        sc = round((100.0 * (ps[0] + ps[1] + ps[2] + ps[3] + ps[4])) / 5.0, 1)
        r1 = C[i] / C[i - 1] - 1; uclock = C[i] >= Hh[i] * 0.9995 and min(abs(r1 - b) for b in (0.05, 0.10, 0.20)) < 0.003
        lab = 'AVOID' if sc < 55 else ('BUY' if sc >= 85 and C[i] > sma50 and not uclock else 'WAIT')
        out.update(score=sc, label=lab)
    else:
        out.update(score=float('nan'), label='NOT_RATED')
    n += 1
    ok = out['label'] == f['label'] and abs(out['atr14'] - float(f['atr14'])) < 1e-6 * max(1, out['atr14']) and \
         (out['label'] == 'NOT_RATED' or abs(out['score'] - float(f['score'])) < 1e-9)
    if not ok: bad += 1; print('MISMATCH', f['symbol'], f['date'], out, f['score'], f['label'])
print(f'{n} fixtures checked, {bad} mismatches')
