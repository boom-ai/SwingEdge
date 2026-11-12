import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '40_common.py')).read())
rng = np.random.default_rng(11); td = rng.choice(np.arange(t0 + 300, tend), 60, replace=False)
rows = []
for t in sorted(td):
    js = np.nonzero(U[t])[0]; vals = []
    for j in js:
        ix = np.nonzero(~np.isnan(C[:t + 1, j]))[0]; i = len(ix) - 1
        if i < 252: vals.append([np.nan] * 5); continue
        c = C[ix, j]; r6 = c[i - 21] / c[i - 126] - 1; r12 = c[i - 21] / c[i - 252] - 1
        rt = c[i - 125:i + 1] / c[i - 126:i] - 1; vol = rt.std(ddof=1); hi = H[ix[i - 251:i + 1], j].max(); lo = L[ix[i - 251:i + 1], j].min()
        vals.append([r6 / (vol * np.sqrt(105)), r6, r12, c[i] / hi, c[i] / lo])
    A = np.array(vals); sc = np.zeros(len(A))
    for k in range(5):
        a = A[:, k]; okk = ~np.isnan(a); srt = np.sort(a[okk]); n = okk.sum()
        lt = np.searchsorted(srt, a, 'left'); le = np.searchsorted(srt, a, 'right'); sc += np.where(okk, (lt + ((le - lt) + 1) / 2.0) / n, np.nan)
    mine = np.rint(100.0 * sc / 5.0 * 10.0) / 10.0
    for jj, m_, s_ in zip(js, mine, S[t, js]):
        rows.append((t, jj, m_, s_))
R = pd.DataFrame(rows, columns=['t', 'j', 'mine', 'stored']); both = R.dropna(); d = (both.mine - both.stored).abs()
print('both defined', len(both), 'mine NaN stored defined', int((R.mine.isna() & R.stored.notna()).sum()), 'stored NaN mine defined', int((R.mine.notna() & R.stored.isna()).sum()))
print('diff>0.05', int((d > .05).sum()), 'diff>0.15', int((d > .15).sum()), 'diff>0.5', int((d > .5).sum()), 'max', d.max())
print(d[d > .05].describe())
bad = both[d > .05]; print(bad.head(10)); print('by date', bad.t.value_counts().head(5).to_dict())
# does mismatch concentrate at date index of hole etc?
print(sorted(set(bad.t))[:20])
flip = ((bad.mine >= 85) != (bad.stored >= 85 - 1e-4)) | ((bad.mine < 55) != (bad.stored < 55 - 1e-4))
print('label flips among mismatches', int(flip.sum()), 'of', len(both), 'rows ->', 100*flip.sum()/len(both), '%')
