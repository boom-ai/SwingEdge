import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '40_common.py')).read())
out = []
def P(s): print(s); out.append(s)
RATED = U & ~np.isnan(S)
# ---- (b) Newey-West vs monthly-cluster t for AVOID and BUY
fb = g('fb60'); mU = np.nanmean(np.where(RATED, fb, np.nan), 1)
def per_mask(a, b): m = np.zeros(T, bool); m[a:b + 1] = True; return m
ym = pd.Series(yrs * 100 + dates.month.values)
BUYm = RATED & (S >= 85) & (C > SMA50) & ~UCL
for nm, mk in (('AVOID<55', RATED & (S < 55)), ('BUY', BUYm)):
    d = dayavg(mk, fb - mU[:, None] - 0.0025)
    for pn, (a, b) in (('FULL', (t0, tend)), ('OOS', (tsplit, tend))):
        s = pd.Series(d[a:b + 1], index=ym.values[a:b + 1]).dropna(); mm = s.groupby(level=0).mean().values
        P(f'{nm} {pn}: daily-NW60 t {nw_t(d[a:b+1],60):+.2f} (n days {int(np.sum(~np.isnan(d[a:b+1])))}) | month-mean series n={len(mm)} mean {100*mm.mean():+.2f} NW(3) t {nw_t(mm,3):+.2f} NW(5) t {nw_t(mm,5):+.2f} | year-block t {(lambda y: y.mean()/(y.std(ddof=1)/np.sqrt(len(y))))(s.groupby(s.index//100).mean().values):+.2f}')
try:
    import statsmodels.api as sm
    x = dayavg(RATED & (S < 55), fb - mU[:, None] - 0.0025)[t0:tend + 1]; x = x[~np.isnan(x)]
    r = sm.OLS(x, np.ones(len(x))).fit(cov_type='HAC', cov_kwds={'maxlags': 60}); P(f'statsmodels HAC(60, Bartlett) AVOID FULL t {r.tvalues[0]:+.3f} vs researcher nw_t {nw_t(x,60):+.3f}')
except Exception as ex: P('statsmodels not available: ' + str(ex)[:60])
# ---- (a2) full-universe score comparison on random dates (float64 own computation, vectorised pandas) vs stored S
rng = np.random.default_rng(11); td = rng.choice(np.arange(t0 + 300, tend), 60, replace=False); mism = 0; tot = 0; tiegap = []
for t in td:
    m = U[t] & ~np.isnan(S[t])
    idx = [np.nonzero(~np.isnan(C[:, j]))[0] for j in np.nonzero(U[t])[0]]
    # (own float64 pipeline for every universe stock on this date)
    vals = []
    for j in np.nonzero(U[t])[0]:
        ix = np.nonzero(~np.isnan(C[:t + 1, j]))[0]; i = len(ix) - 1
        if i < 252: vals.append([np.nan] * 5); continue
        c = C[ix, j]; r6 = c[i - 21] / c[i - 126] - 1; r12 = c[i - 21] / c[i - 252] - 1
        rt = c[i - 125:i + 1] / c[i - 126:i] - 1; vol = rt.std(ddof=1); hi = H[ix[i - 251:i + 1], j].max(); lo = L[ix[i - 251:i + 1], j].min()
        vals.append([r6 / (vol * np.sqrt(105)), r6, r12, c[i] / hi, c[i] / lo])
    A = np.array(vals); ok = ~np.isnan(A).any(1); A = A[ok]; n = len(A); js = np.nonzero(U[t])[0][ok]
    sc = np.zeros(n)
    for k in range(5):
        a = A[:, k]; srt = np.sort(a); lt = np.searchsorted(srt, a, 'left'); le = np.searchsorted(srt, a, 'right'); sc += (lt + ((le - lt) + 1) / 2.0) / n
    mine = np.rint(100.0 * sc / 5.0 * 10.0) / 10.0; st = S[t, js]
    d = np.abs(mine - st); mism += int((d > 0.05).sum()); tot += n
P(f'(F) full-universe float64 re-score on 60 random dates: {tot} stock-days, {mism} with |S diff|>0.05 ({100*mism/tot:.3f}%)')
# ---- (E) costs
slot = 25000.0
def costs(slot, brokerage_per_order):
    stt = 0.001 * slot * 2; txn = 0.0000297 * slot * 2; sebi = 0.000001 * slot * 2; stamp = 0.00015 * slot; dp = 13.5 * 1.18
    br = brokerage_per_order * 2; gst = 0.18 * (br + txn + sebi)
    return stt + txn + sebi + stamp + dp + br + gst
for br in (0, 20):
    c = costs(slot, br); P(f'(E) Rs {slot:,.0f} slot, brokerage Rs {br}/order: round-trip statutory+DP+brokerage = Rs {c:.0f} = {100*c/slot:.3f}%   (Rs 12,500 slot: {100*costs(12500,br)/12500:.3f}%)')
# Corwin-Schultz spread proxy on BUY-signal stocks (mean over 20 bars before signal)
tb, jb = np.nonzero(BUYm & (np.arange(T)[:, None] >= tsplit)); 
lh = np.log(H / L); 
def cs(j, t):
    ix = np.nonzero(~np.isnan(C[t - 30:t + 1, j]))[0] + t - 30
    ix = ix[-21:]
    h, l = H[ix, j], L[ix, j]; beta = (np.log(h[:-1] / l[:-1]) ** 2 + np.log(h[1:] / l[1:]) ** 2); gam = np.log(np.maximum(h[:-1], h[1:]) / np.minimum(l[:-1], l[1:])) ** 2
    k = 3 - 2 * np.sqrt(2); al = (np.sqrt(2 * beta) - np.sqrt(beta)) / k - np.sqrt(gam / k); s = 2 * (np.exp(al) - 1) / (1 + np.exp(al)); s = np.maximum(s, 0); return s.mean()
sel = rng.choice(len(tb), 3000, replace=False); sp = np.array([cs(jb[i], tb[i]) for i in sel])
P(f'(E) Corwin-Schultz high-low spread proxy for OOS BUY-signal stocks (upward biased by volatility): median {100*np.median(sp):.2f}% mean {100*sp.mean():.2f}% p25 {100*np.percentile(sp,25):.2f}% p75 {100*np.percentile(sp,75):.2f}%  -> round-trip crossing cost = 1 full spread if both legs take liquidity')
# ---- multiple testing: max of 11 null percentiles
Rr = pd.read_csv(os.path.join(OUT, 'C_variants_raw.csv'))
for pn in ('IS', 'OOS'):
    nb = Rr[(Rr.period == pn) & (Rr.null == 'B')]; arr = {v: nb[nb.vid == v].cagr.values for v in nb.vid.unique() if v != 'V0'}
    P(f'(C) chance of seeing a variant at least as good vs null-B: if 11 variants were all noise, expected best percentile ~ {100*(1-0.5**(1/11)):.0f}%; P(best of 11 >= 93rd pct) = {100*(1-0.93**11):.0f}%; P(best of 11 >=95th) = {100*(1-0.95**11):.0f}%')
    break
open(os.path.join(OUT, 'F_misc.txt'), 'w').write('\n'.join(out))
