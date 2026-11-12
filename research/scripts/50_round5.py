"""Round 5: pre-registered tests in out_r5/PREREG.md. usage: python3 -I 50_round5.py   (writes out_r5/r5_raw.json, r5_tables.md)"""
import sys, os, time, json, numpy as np, pandas as pd, numba as nb
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '40_common.py')).read())
from r4lib import nw_t as nw_t4, pstats
from multiprocessing import Pool
OUT = '/home/claude/research/out_r5'
B_OOS = GAP - 61                                  # last OOS signal / portfolio day (2025-01-29)
PER = {'IS': (t0, tsplit - 1), 'OOS': (tsplit, B_OOS)}
RATED = U & ~np.isnan(S)
# ---------- SMA by bars (verify method on SMA50)
def sma_bars(X, n):
    out = np.full_like(X, np.nan)
    for j in range(X.shape[1]):
        col = X[:, j]; ok = ~np.isnan(col)
        if ok.sum() >= n: out[ok, j] = pd.Series(col[ok]).rolling(n).mean().values
    return out
SMA100 = sma_bars(C, 100); chk = sma_bars(C, 50)
m = ~np.isnan(SMA50) & ~np.isnan(chk); rel = np.abs(chk[m] / SMA50[m] - 1)
print('SMA50 method check: max rel diff', rel.max(), 'nan pattern equal share', ((np.isnan(chk)) == np.isnan(SMA50)).mean(), flush=True)

@nb.njit(cache=False)
def sim3(ts, js, hold, stopk, use_ma, gapmax, O, H, L, C, ATR, MA):
    n = len(ts); T = C.shape[0]
    ret = np.full(n, np.nan); xd = np.full(n, -1); epx = np.full(n, np.nan); stopped = np.zeros(n)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        a = ATR[t, j]
        if np.isnan(a): continue
        ep = O[t + 1, j]
        if np.isnan(ep): continue
        if gapmax >= 0 and ep > C[t, j] + gapmax * a: continue
        stop = ep - stopk * a
        lastc = ep; px = np.nan; x = last; pend = False
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            if pend: px = O[dd, j]; x = dd; break
            if dd > t + 1 and O[dd, j] <= stop: px = O[dd, j]; x = dd; stopped[i] = 1; break
            if L[dd, j] <= stop: px = stop; x = dd; stopped[i] = 1; break
            lastc = C[dd, j]
            if dd == last: px = C[dd, j]; x = dd; break
            if use_ma and C[dd, j] < MA[dd, j]:
                if dd + 1 <= T - 1: pend = True
                else: px = C[dd, j]; x = dd; break
        if np.isnan(px): continue
        ret[i] = px / ep - 1; xd[i] = x; epx[i] = ep
    return ret, xd, epx, stopped

CAND = RATED & (C > SMA50) & ~UCL & ~np.isnan(ATR); CAND[:t0] = False; CAND[T - 61:] = False
BUY = CAND & (S >= 85)
ts_c, js_c = np.nonzero(CAND); pos = -np.ones((T, N), np.int32); pos[ts_c, js_c] = np.arange(len(ts_c))
PLANS = {'P1': (3.0, True, SMA50), 'P2': (5.0, False, SMA50), 'P3': (5.0, True, SMA100)}
TAB = {k: sim3(ts_c, js_c, 60, sk, um, 0.5, O, H, L, C, ATR, ma) for k, (sk, um, ma) in PLANS.items()}
Cff = pd.DataFrame(C).ffill().values
atrp = ATR / C

def portfolio2(tr, t0_, t1_, cost, maxpos, cap=None):
    """r4lib.portfolio + optional cap[t] = max open positions allowed when taking NEW entries on signal day t."""
    eq = np.ones(t1_ - t0_ + 1); cash = 1.0; pos_ = []
    tt = tr.t.values; lo = np.searchsorted(tt, np.arange(t0_, t1_ + 2))
    J, R, X, EP = tr.j.values, tr.ret.values, tr.xd.values, tr.ep.values
    pending = []; ntr = 0
    for t in range(t0_, t1_ + 1):
        for (j, r, x, ep) in pending:
            basis = cash + sum(p[1] for p in pos_); alloc = min(basis / maxpos, cash)
            if alloc > 1e-12 and len(pos_) < maxpos: cash -= alloc; pos_.append([j, alloc, ep, x, r]); ntr += 1
        pending = []; keep = []
        for p in pos_:
            if p[3] <= t: cash += p[1] * (1 + p[4] - cost)
            else: keep.append(p)
        pos_ = keep
        held = {p[0] for p in pos_}; lim = maxpos if cap is None else min(maxpos, cap[t]); free = lim - len(pos_)
        for i in range(lo[t - t0_], lo[t - t0_ + 1]):
            if free <= 0: break
            if J[i] in held or np.isnan(R[i]): continue
            pending.append((J[i], R[i], X[i], EP[i])); held.add(J[i]); free -= 1
        eq[t - t0_] = cash + sum(p[1] * Cff[t, p[0]] / p[2] for p in pos_)
    return pd.Series(eq, index=dates[t0_:t1_ + 1]), ntr

def frame(rows, plan, score):
    r, x, e, st = TAB[plan]
    df = pd.DataFrame({'t': ts_c[rows], 'j': js_c[rows], 'ret': r[rows], 'xd': x[rows], 'ep': e[rows], 'score': score}).dropna(subset=['ret'])
    return df.sort_values(['t', 'score', 'j'], ascending=[True, False, True]).reset_index(drop=True)
def run(df, slots, pn, off, cost, cap=None):
    a, b = PER[pn]; a2 = a + off; d = df[(df.t >= a2) & (df.t <= b)]
    eq, ntr = portfolio2(d, a2, b, cost, slots, cap); st = pstats(eq)[0]; return st['cagr'], st['mdd'], ntr
OFFS = list(range(0, 60, 3)); NOFF = [0, 15, 30, 45]
def grid(df, slots, pn, cost, cap=None):
    v = np.array([run(df, slots, pn, o, cost, cap) for o in OFFS]); return dict(cagr=float(np.median(v[:, 0])), cmin=float(v[:, 0].min()), cmax=float(v[:, 0].max()), mdd_med=float(np.median(v[:, 1])), mdd_worst=float(v[:, 1].min()), trades=float(np.median(v[:, 2])))
tb, jb = np.nonzero(BUY)
DF = {p: frame(pos[tb, jb], p, S[tb, jb]) for p in PLANS}
cands = [np.nonzero(CAND[t])[0] for t in range(T)]
def nullA_seed(s):
    rng = np.random.default_rng(55000 + s); tl = []; jl = []
    for t in range(t0, T - 61):
        c_ = cands[t]
        if len(c_): p = rng.choice(c_, size=min(20, len(c_)), replace=False); tl.append(np.full(len(p), t)); jl.append(p)
    tt, jj = np.concatenate(tl), np.concatenate(jl); sc = rng.random(len(tt)); out = []
    for p in PLANS:
        d = frame(pos[tt, jj], p, sc)
        for pn in PER:
            for sl in (4, 8):
                for o in NOFF: out.append((p, pn, sl, o, s) + run(d, sl, pn, o, 0.005)[:2])
    return out
if __name__ == '__main__':
    t_start = time.time(); RES = {}; TABS = []
    def P(s): print(s, flush=True); TABS.append(s)
    P(f'Windows: IS {dates[PER["IS"][0]].date()}..{dates[PER["IS"][1]].date()}  OOS {dates[PER["OOS"][0]].date()}..{dates[PER["OOS"][1]].date()} (hole-free)')
    # ================= Q1 AVOID
    fb = g('fb60'); okf = RATED & ~np.isnan(fb)
    mEW = np.where(okf.sum(1) > 0, np.where(okf, fb, 0).sum(1) / np.maximum(okf.sum(1), 1), np.nan)
    w = np.where(okf, TO20, 0.0); mTW = (w * np.nan_to_num(fb)).sum(1) / np.where(w.sum(1) > 0, w.sum(1), np.nan)
    b200 = RATED & (C < SMA200)
    DEFS = {'A0 S<55': RATED & (S < 55), 'AU S<55 OR <SMA200': RATED & ((S < 55) | (C < SMA200)), 'AI S<55 AND <SMA200': RATED & (S < 55) & (C < SMA200)}
    WQ1 = {'IS': (t0, tsplit - 61), 'OOS': (tsplit, B_OOS), 'FULL': (t0, B_OOS)}
    q1 = {}
    P('\n## Q1 AVOID: 60-session excess (gross, %), NW t lag 60\n\n| def | period | flagged % | vs EW mean (t) | yrs neg EW | vs TW mean (t) | yrs neg TW | raw mean / median |\n|---|---|---|---|---|---|---|---|')
    for nm, mk in DEFS.items():
        q1[nm] = {}
        for pn, (a, b) in WQ1.items():
            r = {'flagged_pct': 100 * mk[a:b + 1].sum() / RATED[a:b + 1].sum()}
            for bn, bm in (('EW', mEW), ('TW', mTW)):
                dm = dayavg(mk, fb - bm[:, None])[a:b + 1]; yy = yrs[a:b + 1]
                ym = pd.Series(dm).groupby(yy).mean()
                r[bn] = dict(mean=100 * np.nanmean(dm), t=nw_t(dm, 60), yneg=int((ym < 0).sum()), ytot=int(ym.notna().sum()))
            v = fb[a:b + 1][mk[a:b + 1]]; v = v[~np.isnan(v)]; r['raw_mean'] = 100 * v.mean(); r['raw_median'] = 100 * np.median(v)
            q1[nm][pn] = r
            P(f"| {nm} | {pn} | {r['flagged_pct']:.0f} | {r['EW']['mean']:+.2f} ({r['EW']['t']:+.1f}) | {r['EW']['yneg']}/{r['EW']['ytot']} | {r['TW']['mean']:+.2f} ({r['TW']['t']:+.1f}) | {r['TW']['yneg']}/{r['TW']['ytot']} | {r['raw_mean']:+.2f} / {r['raw_median']:+.2f} |")
    wl = lambda d: max(q1[d]['OOS']['EW']['t'], q1[d]['OOS']['TW']['t'])
    qual = {d: q1[d]['IS']['EW']['t'] <= -2 and q1[d]['OOS']['EW']['mean'] < 0 and q1[d]['OOS']['TW']['mean'] < 0 for d in DEFS}
    base = 'A0 S<55'; ch = [d for d in DEFS if d != base and qual[d] and wl(d) <= wl(base) - 0.5 and q1[d]['OOS']['TW']['mean'] <= q1[base]['OOS']['TW']['mean']]
    avoid_choice = min(ch, key=wl) if ch else base
    P(f"\nQ1 qualifies: {qual}; weakest-link OOS t: { {d: round(wl(d), 2) for d in DEFS} } -> CHOSEN: {avoid_choice}")
    RES['q1'] = q1; RES['avoid_choice'] = avoid_choice
    # ================= Q2 per-trade
    def one_per_stock(df):
        df = df.sort_values(['j', 't']); keep = np.zeros(len(df), bool); lastj = -1; lastx = -1
        for k, (j, t, x) in enumerate(zip(df.j.values, df.t.values, df.xd.values)):
            if j != lastj: lastj = j; lastx = -1
            if t >= lastx: keep[k] = True; lastx = x
        return df[keep]
    def tstats(df, cost):
        r = df.ret.values - cost; hs = (df.xd - df.t).values
        return dict(n=int(len(r)), winPct=100 * (r > 0).mean(), avgPct=100 * r.mean(), medianPct=100 * np.median(r), p05=100 * np.percentile(r, 5),
                    avgSessions=float(hs.mean()), profitFactor=float(r[r > 0].sum() / -r[r < 0].sum()), t_iid=float(r.mean() / (r.std(ddof=1) / np.sqrt(len(r)))))
    # reproduction check vs REPORT4 (P1, 0.25%, signals 2022 .. T-61)
    rep = one_per_stock(DF['P1'][(DF['P1'].t >= tsplit) & (DF['P1'].t <= T - 61)]); P(f"\nRepro P1 round-4 window: {tstats(rep, 0.0025)}  (REPORT4: n 2144, win 31.8, avg +1.75, median -3.15)")
    q2t = {}
    P('\n## Q2 per-trade (one open trade per stock), net of cost\n\n| plan | period | cost | n | win% | avg | median | p05 | avg sess | PF | t | null-A avg (all cands) |\n|---|---|---|---|---|---|---|---|---|---|---|---|')
    for p in PLANS:
        r_, x_, e_, st_ = TAB[p]
        for pn, (a, b) in (('IS', (t0, tsplit - 1)), ('OOS', (tsplit, B_OOS))):
            d = one_per_stock(DF[p][(DF[p].t >= a) & (DF[p].t <= b)])
            mk = (ts_c >= a) & (ts_c <= b) & ~np.isnan(r_)
            for cost in (0.005, 0.0025):
                s_ = tstats(d, cost); s_['nullA_avg'] = 100 * (np.nanmean(r_[mk]) - cost); s_['stopPct'] = 100 * float(st_[pos[d.t.values, d.j.values]].mean())
                q2t[(p, pn, cost)] = s_
                P(f"| {p} | {pn} | {100*cost:.2f} | {s_['n']} | {s_['winPct']:.1f} | {s_['avgPct']:+.2f} | {s_['medianPct']:+.2f} | {s_['p05']:.1f} | {s_['avgSessions']:.1f} | {s_['profitFactor']:.2f} | {s_['t_iid']:.1f} | {s_['nullA_avg']:+.2f} |")
    RES['q2_trades'] = {f'{k[0]}|{k[1]}|{k[2]}': v for k, v in q2t.items()}
    # benchmarks
    n50 = reg['n50']; r1 = C / np.vstack([np.full((1, N), np.nan), C[:-1]]) - 1; Up = np.vstack([np.zeros((1, N), bool), U[:-1]])
    ew = pd.Series(np.nanmean(np.where(Up, r1, np.nan), axis=1), index=dates).fillna(0); BEN = {}
    for pn, (a, b) in PER.items():
        nf, ef = [], []
        for o in OFFS:
            s_ = n50.iloc[a + o:b + 1]; nf.append(pstats(s_ / s_.iloc[0])[0])
            e = (1 + ew.iloc[a + o + 1:b + 1]).cumprod(); ef.append(pstats(pd.concat([pd.Series([1.0], index=[dates[a + o]]), e]))[0])
        BEN[pn] = dict(nifty_cagr=float(np.median([x['cagr'] for x in nf])), nifty_mdd=float(np.median([x['mdd'] for x in nf])), ew_cagr=float(np.median([x['cagr'] for x in ef])), ew_mdd=float(np.median([x['mdd'] for x in ef])))
    RES['bench'] = BEN; P(f'\nBenchmarks (median over 20 offsets): {BEN}')
    # portfolios
    PF = {}
    for p in PLANS:
        for pn in PER:
            for sl in (4, 8):
                for cost in ((0.005, 0.0025, 0.0065) if sl == 8 else (0.005, 0.0025)): PF[(p, pn, sl, cost)] = grid(DF[p], sl, pn, cost)
    print('plan grids done', (time.time() - t_start) / 60, flush=True)
    with Pool(2) as pool: NA = [r for rr in pool.map(nullA_seed, range(150), chunksize=5) for r in rr]
    NA = pd.DataFrame(NA, columns=['plan', 'period', 'slots', 'off', 'seed', 'cagr', 'mdd']); NA.to_csv(os.path.join(OUT, 'nullA_raw.csv'), index=False)
    print('null A done', (time.time() - t_start) / 60, flush=True)
    P('\n## Q2 portfolios (₹1L, highest score first; CAGR median over 20 start offsets [min..max]; DD median / worst; null A = 600 runs at 0.50%)\n\n| plan | period | slots | cost | CAGR [range] | maxDD med / worst | trades | null-A median | pct vs null A |\n|---|---|---|---|---|---|---|---|---|')
    for k, v in PF.items():
        p, pn, sl, cost = k; nn = NA[(NA.plan == p) & (NA.period == pn) & (NA.slots == sl)].cagr.values
        v['nullA_med'] = float(np.median(nn)); v['pctA'] = float(100 * (nn < v['cagr']).mean())
        P(f"| {p} | {pn} | {sl} | {100*cost:.2f} | {v['cagr']:.1f} [{v['cmin']:.1f}..{v['cmax']:.1f}] | {v['mdd_med']:.0f} / {v['mdd_worst']:.0f} | {v['trades']:.0f} | {v['nullA_med']:.1f} | {v['pctA']:.0f} |")
    RES['q2_port'] = {'|'.join(map(str, k)): v for k, v in PF.items()}
    sc = {}; elig = {}
    for p in PLANS:
        pc = [PF[(p, pn, sl, 0.005)]['pctA'] for pn in PER for sl in (4, 8)]; sc[p] = float(np.mean(pc))
        elig[p] = all(PF[(p, 'IS', sl, 0.005)]['pctA'] >= 90 for sl in (4, 8)) and q2t[(p, 'OOS', 0.005)]['avgPct'] > 0
    pool_ = [p for p in PLANS if elig[p]] or list(PLANS); best = max(pool_, key=lambda p: sc[p])
    for p in ('P2', 'P3', 'P1'):
        if p in pool_ and sc[best] - sc[p] <= 3: plan_choice = p; break
    realmoney = all(PF[(plan_choice, pn, sl, 0.005)]['pctA'] >= 95 for pn in PER for sl in (4, 8)) and all(PF[(plan_choice, 'OOS', sl, 0.005)]['cagr'] > BEN['OOS']['ew_cagr'] and PF[(plan_choice, 'OOS', sl, 0.005)]['mdd_worst'] > -35 for sl in (4, 8))
    P(f"\nQ2 eligible {elig}; score (mean pctA) {sc} -> CHOSEN {plan_choice} ({'eligible' if elig[plan_choice] else 'UNPROVEN'}); real-money bar passed: {realmoney}")
    RES.update(plan_choice=plan_choice, plan_eligible=elig[plan_choice], realmoney=realmoney, plan_scores=sc)
    # ================= Q3 selection
    d_atr = frame(pos[tb, jb], plan_choice, -atrp[tb, jb]); Q3 = {}
    P(f'\n## Q3 selection within BUY ({plan_choice}, 0.50%)\n\n| rule | period | slots | CAGR [range] | DD med |\n|---|---|---|---|---|')
    for pn in PER:
        for sl in (4, 8):
            Q3[('S-high', pn, sl)] = PF[(plan_choice, pn, sl, 0.005)]; Q3[('ATR-low', pn, sl)] = grid(d_atr, sl, pn, 0.005)
            rng = np.random.default_rng(77 + sl); rr = []
            for s in range(150):
                d2 = DF[plan_choice].copy(); d2['score'] = rng.random(len(d2)); d2 = d2.sort_values(['t', 'score'], ascending=[True, False]).reset_index(drop=True)
                rr.append(run(d2, sl, pn, OFFS[int(rng.integers(0, 20))], 0.005)[:2])
            rr = np.array(rr); Q3[('random', pn, sl)] = dict(cagr=float(np.median(rr[:, 0])), cmin=float(np.percentile(rr[:, 0], 5)), cmax=float(np.percentile(rr[:, 0], 95)), mdd_med=float(np.median(rr[:, 1])))
            for rl in ('S-high', 'ATR-low', 'random'):
                v = Q3[(rl, pn, sl)]; v['pct_vs_random'] = float(100 * (rr[:, 0] < v['cagr']).mean())
                P(f"| {rl} | {pn} | {sl} | {v['cagr']:.1f} [{v['cmin']:.1f}..{v['cmax']:.1f}]{' (5-95%)' if rl=='random' else ''} | {v['mdd_med']:.0f} | pct vs random {v['pct_vs_random']:.0f}")
    sw = all(Q3[('ATR-low', pn, sl)]['cagr'] > Q3[('S-high', pn, sl)]['cagr'] for pn in PER for sl in (4, 8)) and all(Q3[('ATR-low', 'IS', sl)]['mdd_med'] >= Q3[('S-high', 'IS', sl)]['mdd_med'] for sl in (4, 8))
    sel_choice = 'ATR-low' if sw else 'S-high'
    nothing = not any(all(Q3[(rl, 'IS', sl)]['cagr'] > Q3[('random', 'IS', sl)]['cagr'] for sl in (4, 8)) for rl in ('S-high', 'ATR-low'))
    P(f'\nQ3 CHOSEN {sel_choice}; selection adds nothing vs random (IS): {nothing}')
    RES.update(sel_choice=sel_choice, sel_adds_nothing=nothing, q3={'|'.join(map(str, k)): v for k, v in Q3.items()})
    # ================= Q4 breadth exposure
    br = reg['breadth50'].values; dsel = DF[plan_choice] if sel_choice == 'S-high' else d_atr; Q4 = {}
    P(f'\n## Q4 breadth exposure ({plan_choice}, {sel_choice}, 0.50%)  share of days full/half/none: IS {[round(100*x) for x in ((br[t0:tsplit]>=.5).mean(), ((br[t0:tsplit]>=.3)&(br[t0:tsplit]<.5)).mean(), (br[t0:tsplit]<.3).mean())]}  OOS {[round(100*x) for x in ((br[tsplit:B_OOS+1]>=.5).mean(), ((br[tsplit:B_OOS+1]>=.3)&(br[tsplit:B_OOS+1]<.5)).mean(), (br[tsplit:B_OOS+1]<.3).mean())]}\n\n| period | slots | base CAGR / DD | gated CAGR [range] / DD med (worst) | ΔDD | ΔCAGR lost | ratio |\n|---|---|---|---|---|---|---|')
    ok = True
    for pn in PER:
        for sl in (4, 8):
            cap = np.where(br >= 0.5, sl, np.where(br >= 0.3, sl // 2, 0)); gq = grid(dsel, sl, pn, 0.005, cap); bq = Q3[(sel_choice, pn, sl)]
            dDD = gq['mdd_med'] - bq['mdd_med']; dC = bq['cagr'] - gq['cagr']; ratio = np.inf if dC <= 0 else dDD / dC
            ok &= dDD >= 5 and (dC <= 0 or ratio >= 1.0); Q4[(pn, sl)] = dict(gated=gq, dDD=dDD, dCAGR=dC, ratio=float(ratio))
            P(f"| {pn} | {sl} | {bq['cagr']:.1f} / {bq['mdd_med']:.0f} | {gq['cagr']:.1f} [{gq['cmin']:.1f}..{gq['cmax']:.1f}] / {gq['mdd_med']:.0f} ({gq['mdd_worst']:.0f}) | {dDD:+.1f} | {dC:+.1f} | {ratio:.2f} |")
    P(f'\nQ4 ADOPT breadth exposure: {ok}'); RES.update(exposure_adopt=bool(ok), q4={'|'.join(map(str, k)): v for k, v in Q4.items()})
    json.dump(RES, open(os.path.join(OUT, 'r5_raw.json'), 'w'), indent=1, default=float)
    open(os.path.join(OUT, 'r5_tables.md'), 'w').write('\n'.join(TABS)); print('total min', (time.time() - t_start) / 60)
