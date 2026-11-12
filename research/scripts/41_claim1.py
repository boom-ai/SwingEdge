"""Review B: robustness of claim 1 (score < 55 = AVOID). usage python3 -I 41_claim1.py"""
import sys, os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '40_common.py')).read())
out = []
def P(*a):
    s = ' '.join(str(x) for x in a); print(s); out.append(s)
AV = U & (S < 55); RATED = U & ~np.isnan(S)
per = {'FULL': (t0, tend), 'IS': (t0, tsplit - 61), 'OOS': (tsplit, tend)}
def sel(a, b): m = np.zeros(T, bool); m[a:b + 1] = True; return m
clean = np.ones(T, bool); clean[GAP - 60:GAP + 252] = False          # windows touching the 2025 hole
def stat(dm, tm):
    x = dm[tm]; x = x[~np.isnan(x)]
    return x.mean() * 100, nw_t(x, 60), len(x)
for H in (60, 40, 20):
    fb = g(f'fb{H}')
    mU = np.nanmean(np.where(RATED, fb, np.nan), 1)
    w = np.where(RATED & ~np.isnan(fb), TO20, 0.0); mV = (w * np.nan_to_num(fb)).sum(1) / np.where(w.sum(1) > 0, w.sum(1), np.nan)
    P(f'\n===== horizon {H}  (gross of costs; excess = stock fwd return from D+1 open minus benchmark on the same day) =====')
    for pn, (a, b) in per.items():
        tm = sel(a, b)
        r = {}
        for nm, bm in (('EW', mU), ('TurnoverW', mV)):
            for sub, mask in (('<55', AV), ('<40', RATED & (S < 40)), ('40-54', RATED & (S >= 40) & (S < 55)), ('>=85', RATED & (S >= 85))):
                dm = dayavg(mask, fb - bm[:, None]); m_, t_, n_ = stat(dm, tm)
                r[(nm, sub)] = (m_, t_)
        P(pn, 'H%d' % H, ' | '.join(f'{k[0]} {k[1]}: {v[0]:+.2f} (t {v[1]:+.1f})' for k, v in r.items()))
    # clean (exclude contaminated windows)
    tm = sel(t0, tend) & clean; dm = dayavg(AV, fb - mU[:, None]); m_, t_, n_ = stat(dm, tm)
    tm2 = sel(tsplit, tend) & clean; m2, t2, n2 = stat(dm, tm2)
    P(f'  excl. windows touching the 2025-05/06 data hole: FULL <55 {m_:+.2f} (t {t_:+.1f}, {n_} days); OOS(2022-Mar25 only) {m2:+.2f} (t {t2:+.1f}, {n2} days)')
    # non-overlapping, several offsets
    dm = dayavg(AV, fb - mU[:, None]); rows = []
    for off in range(0, H, max(H // 6, 1)):
        idx = np.arange(t0 + off, tend, H); x = dm[idx]; x = x[~np.isnan(x)]
        rows.append((100 * x.mean(), x.mean() / (x.std(ddof=1) / np.sqrt(len(x))), 100 * (x < 0).mean(), len(x)))
    rows = np.array(rows)
    P(f'  non-overlapping every {H}th session, {len(rows)} offsets: mean excess {rows[:,0].round(2).tolist()}  iid t {rows[:,1].round(1).tolist()}  share of days negative {rows[:,2].round(0).tolist()} n~{int(rows[0,3])}')
    if H == 60:
        # by year
        dm = dayavg(AV, fb - mU[:, None]); ys = []
        for y in range(2013, 2027):
            x = dm[(yrs == y) & sel(t0, tend)]; ys.append((y, 100 * np.nanmean(x)))
        P('  by signal year <55 excess vs EW:', ' '.join(f'{y}:{v:+.1f}' for y, v in ys), ' negative years', sum(v < 0 for _, v in ys), 'of', len(ys))
        ys = []
        for y in range(2013, 2027):
            x = dm[(yrs == y) & sel(t0, tend) & clean]; ys.append((y, 100 * np.nanmean(x) if np.isfinite(np.nanmean(x)) else np.nan))
        # tercile by liquidity (rank pct of turnover within day among rated)
        rk = pd.DataFrame(np.where(RATED, TO20, np.nan)).rank(axis=1, pct=True).values
        for tn, (lo, hi) in (('low-liq third', (0, 1 / 3)), ('mid third', (1 / 3, 2 / 3)), ('high-liq third', (2 / 3, 1.01))):
            tmask = RATED & (rk > lo) & (rk <= hi) if lo > 0 else RATED & (rk <= hi)
            mT = np.nanmean(np.where(tmask, fb, np.nan), 1)
            for pn in ('IS', 'OOS', 'FULL'):
                a, b = per[pn]; tm = sel(a, b)
                d1 = dayavg(tmask & (S < 55), fb - mU[:, None]); d2 = dayavg(tmask & (S < 55), fb - mT[:, None])
                P(f'  {tn:14s} {pn:4s} <55 excess vs whole EW {stat(d1, tm)[0]:+.2f} (t {stat(d1, tm)[1]:+.1f}) | vs same-third EW {stat(d2, tm)[0]:+.2f} (t {stat(d2, tm)[1]:+.1f})')
        # vs 200 DMA
        below200 = RATED & (C < SMA200)
        P('  --- score<55 versus simply below the 200-day average (excess vs EW, gross)')
        for pn in ('IS', 'OOS', 'FULL'):
            a, b = per[pn]; tm = sel(a, b); s = []
            for nm, mk in (('S<55', AV), ('below200', below200), ('S<55 & above200', AV & ~below200), ('below200 & S>=55', below200 & (S >= 55)), ('S<55 & below200', AV & below200)):
                d = dayavg(mk, fb - mU[:, None]); s.append(f'{nm}: {stat(d, tm)[0]:+.2f} (t {stat(d, tm)[1]:+.1f}, share {100*mk[a:b+1].sum()/RATED[a:b+1].sum():.0f}%)')
            P(' ', pn, ' | '.join(s))
        # Fama-MacBeth: fwd return on dummies
        for pn in ('IS', 'OOS', 'FULL'):
            a, b = per[pn]; co = []
            for t in range(a, b + 1):
                m = RATED[t] & ~np.isnan(fb[t])
                if m.sum() < 40: continue
                X = np.c_[np.ones(m.sum()), AV[t][m], below200[t][m]]; y = fb[t][m]
                co.append(np.linalg.lstsq(X, y, rcond=None)[0])
            co = np.array(co) * 100
            P(f'  Fama-MacBeth {pn}: S<55 coef {co[:,1].mean():+.2f} (NW t {nw_t(co[:,1],60):+.1f});  below200 coef {co[:,2].mean():+.2f} (NW t {nw_t(co[:,2],60):+.1f})')
        # tails
        P('  --- left tail of 60-session raw (D+1 open->D+60 close, gross) returns')
        for pn in ('IS', 'OOS', 'FULL'):
            a, b = per[pn]; tm = sel(a, b)[:, None]
            res = []
            for nm, mk in (('S<55', AV), ('S 55-84', RATED & (S >= 55) & (S < 85)), ('S>=85', RATED & (S >= 85)), ('all rated', RATED)):
                v = fb[mk & tm]; v = v[~np.isnan(v)]
                ex = (fb - mU[:, None])[mk & tm]; ex = ex[~np.isnan(ex)]
                res.append(f'{nm}: P(<-20%) {100*(v<-.2).mean():.1f}%, P(<-30%) {100*(v<-.3).mean():.1f}%, p5 {100*np.percentile(v,5):.1f}%, P(excess<-20) {100*(ex<-.2).mean():.1f}%, mean raw {100*v.mean():+.2f}, median {100*np.median(v):+.2f}')
            P(' ', pn); [P('    ' + r_) for r_ in res]
        # daily-avg raw of AVOID: share of days when AVOID beat universe
        for pn in ('IS', 'OOS'):
            a, b = per[pn]; d = dayavg(AV, fb - mU[:, None])[a:b + 1]
            P(f'  {pn}: share of signal days on which the <55 group beat the universe average: {100*(d>0).mean():.1f}%')
open(os.path.join(OUT, 'B_claim1.txt'), 'w').write('\n'.join(out))
