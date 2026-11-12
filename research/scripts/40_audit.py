"""Review step A/F: independent plain-loop re-derivation of the score and 60-session forward return for ~200 random (stock, date) pairs,
plus survivorship-inside-window accounting, duplicate-symbol and adjustment-event checks.  usage: python3 -I 40_audit.py <work> <out_r4> <out_review>"""
import sys, os, math, numpy as np, pandas as pd
WORK, R4, OUT = sys.argv[1:4]
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl')); O, H, L, C, TO, CR = (W[k].values for k in ('o', 'h', 'l', 'c', 'to_cr', 'craw'))
F = np.load(os.path.join(R4, 'feat.npz')); SC = np.load(os.path.join(R4, 'score.npz')); S = SC['S'].astype(float)
M = pd.read_pickle(os.path.join(R4, 'meta.pkl')); dates = M['dates']; syms = M['syms']; T, N = C.shape
own = [np.nonzero(~np.isnan(C[:, j]))[0] for j in range(N)]
rng = np.random.default_rng(7)
t_lo = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); t_hi = int(np.searchsorted(dates, pd.Timestamp('2026-08-01')))
test_dates = sorted(rng.choice(np.arange(t_lo, t_hi), 40, replace=False))

def comps(j, t):
    """plain loops on own bars up to and including day t. returns None if not in universe / undefined."""
    idx = own[j]; i = int(np.searchsorted(idx, t, side='right')) - 1
    if i < 0 or idx[i] != t: return None
    nb = i + 1
    if nb < 252: return None
    ix = idx[:i + 1]
    to20 = sum(TO[ix[k], j] for k in range(i - 19, i + 1)) / 20.0
    if not (to20 >= 25 and CR[t, j] >= 50): return 'notU'
    c = lambda k: C[ix[k], j]
    out = {'U': True}
    if i < 252: return out | {'ok': False}
    r6 = c(i - 21) / c(i - 126) - 1; r12 = c(i - 21) / c(i - 252) - 1
    rets = [c(k) / c(k - 1) - 1 for k in range(i - 125, i + 1)]
    mu = sum(rets) / len(rets); var = sum((x - mu) ** 2 for x in rets) / (len(rets) - 1); vol = math.sqrt(var)
    hi = max(H[ix[k], j] for k in range(i - 251, i + 1)); lo = min(L[ix[k], j] for k in range(i - 251, i + 1))
    ram = r6 / (vol * math.sqrt(105)) if vol > 0 else float('nan')
    vals = {'ram': ram, 'r6': r6, 'r12': r12, 'prox': c(i) / hi, 'low': c(i) / lo}
    return out | {'ok': not any(math.isnan(v) for v in vals.values()), **vals}

rows = []; maxdiff = 0
for t in test_dates:
    comp = {}
    for j in range(N):
        r = comps(j, t)
        if isinstance(r, dict): comp[j] = r
    good = {j: r for j, r in comp.items() if r['ok']}
    keys = ['ram', 'r6', 'r12', 'prox', 'low']
    arrs = {k: np.array([good[j][k] for j in good]) for k in keys}; ids = list(good)
    n = len(ids)
    def pct(k, v):
        a = arrs[k]; return ((a < v).sum() + ((a == v).sum() + 1) / 2.0) / n
    cand = [j for j in ids]
    pick = rng.choice(cand, size=min(5, len(cand)), replace=False)
    for j in pick:
        g = good[j]; raw = (100.0 * (pct('ram', g['ram']) + pct('r6', g['r6']) + pct('r12', g['r12']) + pct('prox', g['prox']) + pct('low', g['low']))) / 5.0
        mine = np.rint(raw * 10.0) / 10.0
        # forward return, own plain loop: entry open at panel day t+1, exit = last available close in (t, t+60]
        e = O[t + 1, j]; ex = np.nan
        for d in range(t + 1, t + 61):
            if not np.isnan(C[d, j]): ex = C[d, j]
        fw = ex / e - 1 if not (np.isnan(e) or np.isnan(ex)) else np.nan
        rows.append(dict(date=dates[t], sym=syms[j], n_univ=n, mine=mine, stored=S[t, j], fwd_mine=fw, fwd_stored=float(F['fb60'][t, j])))
R = pd.DataFrame(rows)
R['dS'] = (R.mine - R.stored).abs(); R['dF'] = (R.fwd_mine - R.fwd_stored).abs()
print('pairs', len(R), 'max |score diff|', R.dS.max(), 'n score diff>0.15', (R.dS > 0.15).sum(), 'max |fwd diff|', np.nanmax(R.dF), 'nan fwd mine/stored', R.fwd_mine.isna().sum(), R.fwd_stored.isna().sum())
R.to_csv(os.path.join(OUT, 'A_rederive.csv'), index=False)
# universe size on those dates vs stored
for t in test_dates[:5]:
    print(dates[t].date(), 'stored U count', int((~np.isnan(S[t])).sum()))

# ---- survivorship inside window: classify U observations
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
Cc = C
lastbar = pd.DataFrame(np.where(~np.isnan(C), np.arange(T)[:, None], np.nan)).ffill().to_numpy()
cat = np.full((T, N), -1)
tt = np.arange(T)[:, None]
no_next = np.zeros((T, N), bool); no_next[:-1] = np.isnan(O[1:]); 
lb60 = np.full((T, N), np.nan); lb60[:-60] = lastbar[60:]
stale = lb60 < (tt + 60)          # last bar before D+60 => exit price is stale ffill
nobar_after = lb60 <= tt
valid = U & (tt < T - 60) & (tt >= t_lo)
s_ = S
res = []
for nm, m in (('<55', s_ < 55), ('>=55', s_ >= 55), ('>=85', s_ >= 85), ('all', np.ones_like(m := s_ < 55, bool))):
    v = valid & m & ~np.isnan(s_) if nm != 'all' else valid
    res.append(dict(group=nm, n=int(v.sum()), pct_no_D1_open=100 * (v & no_next).mean() * v.size / max(v.sum(), 1),
                    pct_exit_stale=100 * (v & stale & ~no_next).sum() / v.sum(), pct_nobar_after_D=100 * (v & nobar_after).sum() / v.sum()))
print(pd.DataFrame(res).round(3).to_string())
# forward return for stale-exit observations
fb = F['fb60'].astype(float)
for nm, m in (('<55', S < 55), ('>=85', S >= 85)):
    v = valid & m
    for lab, mm in (('stale exit', stale & ~no_next), ('normal', ~stale & ~no_next)):
        x = fb[v & mm]; x = x[~np.isnan(x)]
        print(nm, lab, len(x), 'mean %.2f%% median %.2f%% p5 %.1f%%' % (100 * x.mean(), 100 * np.median(x), 100 * np.percentile(x, 5)))
# how many S-valid obs have NaN fb60 and what happens next to those stocks (days where stock has bar at D but nothing after)
v = valid & ~np.isnan(S) & np.isnan(fb)
print('S valid with NaN fb60:', int(v.sum()), 'of', int((valid & ~np.isnan(S)).sum()), 'in <55:', int((v & (S < 55)).sum()), '>=85:', int((v & (S >= 85)).sum()))

# ---- duplicated histories (same close on >=30 shared dates)
from collections import defaultdict
pairs = defaultdict(int)
for t in range(t_lo, T, 3):
    row = C[t]; ok = np.nonzero(~np.isnan(row))[0]
    d = defaultdict(list)
    for j in ok: d[round(row[j], 4)].append(j)
    for v_, js in d.items():
        if 1 < len(js) <= 4:
            for a in range(len(js)):
                for b in range(a + 1, len(js)): pairs[(js[a], js[b])] += 1
dup = sorted([(k, v_) for k, v_ in pairs.items() if v_ >= 30], key=lambda x: -x[1])
print('pairs with identical close on >=30 of sampled days:', len(dup))
for (a, b), v_ in dup[:20]: print(syms[a], syms[b], v_, 'bars overlap', int((~np.isnan(C[:, a]) & ~np.isnan(C[:, b])).sum()))
# ---- adjustment events: volume jump test
adj = pd.read_csv(os.path.join(WORK, 'adjustments.csv'), parse_dates=['date'])
symi = {s: i for i, s in enumerate(syms)}; rows = []
for _, r in adj.iterrows():
    j = symi.get(r.sym)
    if j is None: continue
    t = int(np.searchsorted(dates, r.date))
    idx = own[j]; i = int(np.searchsorted(idx, t))
    if i < 25 or i + 5 >= len(idx): continue
    raw = W['craw'].values[:, j]; vraw = TO[:, j] / np.where(raw > 0, raw, np.nan) * 1e7
    vb = np.nanmean(vraw[idx[i - 20:i]]); va = np.nanmean(vraw[idx[i:i + 5]])
    rows.append(dict(sym=r.sym, date=r.date.date(), gap=r.gap, factor=r.factor, matched=r.ratio_matched, avg_to_before=r.avg_to_cr_before, vol_jump=va / vb if vb > 0 else np.nan, expected=1 / r.gap))
A = pd.DataFrame(rows); A['suspect'] = (A.vol_jump < 0.5 * A.expected) | (A.gap > 1.2)
A.to_csv(os.path.join(OUT, 'F_adjustment_check.csv'), index=False)
print('adj events', len(A), 'suspect (vol did not scale like a split, or up-gap)', int(A.suspect.sum()), '; liquid (>=25cr before) suspect', int((A.suspect & (A.avg_to_before >= 25)).sum()))
print(A[A.suspect & (A.avg_to_before >= 25)].round(2).to_string())
print('up-gap events', int((A.gap > 1).sum()))
