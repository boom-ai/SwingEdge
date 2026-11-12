"""Round 4 shared code: trade simulator (copy of 24_eval.sim plus touch flags for t1/t2), Newey-West t, portfolio simulator."""
import numpy as np, pandas as pd, numba as nb

def nw_t(x, Lg):
    x = np.asarray(x, float); x = x[~np.isnan(x)]; n = len(x)
    if n < 30: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(Lg, n - 1) + 1): v += 2 * (1 - k / (Lg + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n)

@nb.njit(cache=False)
def sim(ts, js, mode_b, hold, stopk, tgtR, trail, cexit, half, gapmax, O, H, L, C, ATR, SMA50, E20):
    """Same rules as 24_eval.sim. Extra outputs: touch1/touch2 = the HIGH reached entry+2R / entry+3R (R = 3*ATR if stopk=0)
    at any time before the exit (reference levels, not sold); entry price; exit day."""
    n = len(ts); T = C.shape[0]
    ret = np.full(n, np.nan); xd = np.full(n, -1); epx = np.full(n, np.nan); tch1 = np.zeros(n); tch2 = np.zeros(n); stopped = np.zeros(n)
    for i in range(n):
        t = ts[i]; j = js[i]; last = t + hold
        if last >= T: continue
        a = ATR[t, j]
        if np.isnan(a): continue
        if mode_b:
            ep = O[t + 1, j]
            if np.isnan(ep): continue
            if gapmax >= 0 and ep > C[t, j] + gapmax * a: continue
        else:
            ep = C[t, j]
        R = stopk * a
        Rref = R if stopk > 0 else 3.0 * a
        stop = ep - R if stopk > 0 else -1.0
        if mode_b and stopk > 0 and ep <= stop: continue
        t1 = ep + 2.0 * R if half else 1e18
        tgt = ep + tgtR * R if (tgtR > 0 and stopk > 0) else 1e18
        hh = ep; lastc = ep; px = np.nan; x = last; done_half = False; book = 0.0; pend_open = False
        for dd in range(t + 1, last + 1):
            if np.isnan(C[dd, j]):
                if dd == last: px = lastc; x = dd
                continue
            entry_day = mode_b and dd == t + 1
            if pend_open:
                px = O[dd, j]; x = dd; break
            if trail > 0 and not entry_day:
                ns = hh - trail * ATR[dd - 1, j]
                if ns > stop: stop = ns
            if (not entry_day) and O[dd, j] <= stop: px = O[dd, j]; x = dd; stopped[i] = 1; break
            if L[dd, j] <= stop: px = stop; x = dd; stopped[i] = 1; break
            if H[dd, j] >= ep + 2.0 * Rref: tch1[i] = 1
            if H[dd, j] >= ep + 3.0 * Rref: tch2[i] = 1
            if half and not done_half:
                if (not entry_day) and O[dd, j] >= t1: book = O[dd, j]; done_half = True; stop = max(stop, ep)
                elif H[dd, j] >= t1: book = t1; done_half = True; stop = max(stop, ep)
            if (not entry_day) and O[dd, j] >= tgt: px = O[dd, j]; x = dd; break
            if H[dd, j] >= tgt: px = tgt; x = dd; break
            hh = max(hh, H[dd, j]); lastc = C[dd, j]
            if dd == last: px = C[dd, j]; x = dd; break
            trig = (cexit == 1 and C[dd, j] < SMA50[dd, j]) or (cexit == 2 and C[dd, j] < E20[dd, j])
            if trig:
                if mode_b and dd + 1 <= T - 1: pend_open = True
                else: px = C[dd, j]; x = dd; break
        if np.isnan(px): continue
        r = px / ep - 1
        if half and done_half: r = 0.5 * (book / ep - 1) + 0.5 * r
        ret[i] = r; xd[i] = x; epx[i] = ep
    return ret, xd, epx, tch1, tch2, stopped

def portfolio(tr, t0, t1, cost, maxpos, Cff, dates):
    """tr: DataFrame of ALL candidate trades (t, j, ret, xd, ed, ep, score) sorted by (t, -score); ed = entry day index.
    Equal weight: each new position gets min(basis/maxpos, cash), basis = cash + cost basis of open positions.
    Mode b entries (ed = t+1) are queued at the signal close and filled at the next open before that day's exits are processed.
    Returns daily equity (mark-to-market at closes, forward-filled prices), number of trades taken, and average slots used."""
    eq = np.ones(t1 - t0 + 1); cash = 1.0; pos = []
    tt = tr.t.values; lo = np.searchsorted(tt, np.arange(t0, t1 + 2))
    J, R, X, E, EP = tr.j.values, tr.ret.values, tr.xd.values, tr.ed.values, tr.ep.values
    pending = []; ntr = 0; used = 0.0; taken = []
    for t in range(t0, t1 + 1):
        for (j, r, x, ep, i0) in pending:
            basis = cash + sum(p[1] for p in pos); alloc = min(basis / maxpos, cash)
            if alloc > 1e-12 and len(pos) < maxpos: cash -= alloc; pos.append([j, alloc, ep, x, r]); ntr += 1; taken.append(i0)
        pending = []; keep = []
        for p in pos:
            if p[3] <= t: cash += p[1] * (1 + p[4] - cost)
            else: keep.append(p)
        pos = keep
        held = {p[0] for p in pos}; free = maxpos - len(pos)
        for i in range(lo[t - t0], lo[t - t0 + 1]):
            if free <= 0: break
            if J[i] in held or np.isnan(R[i]): continue
            if E[i] == t:
                basis = cash + sum(p[1] for p in pos); alloc = min(basis / maxpos, cash)
                if alloc <= 1e-12: break
                cash -= alloc; pos.append([J[i], alloc, EP[i], X[i], R[i]]); ntr += 1; taken.append(i)
            else: pending.append((J[i], R[i], X[i], EP[i], i))
            held.add(J[i]); free -= 1
        used += len(pos)
        eq[t - t0] = cash + sum(p[1] * Cff[t, p[0]] / p[2] for p in pos)
    return pd.Series(eq, index=dates[t0:t1 + 1]), ntr, used / (t1 - t0 + 1) / maxpos, taken

def pstats(eq):
    yrs = (eq.index[-1] - eq.index[0]).days / 365.25
    ey = eq.groupby(eq.index.year).last(); prev = ey.shift(1); prev.iloc[0] = eq.iloc[0]
    yr = ey / prev - 1
    return dict(cagr=100 * (eq.iloc[-1] / eq.iloc[0]) ** (1 / yrs) - 100, mdd=100 * (eq / eq.cummax() - 1).min(), worst_year=100 * yr.min(),
                worst_year_at=int(yr.idxmin())), yr
