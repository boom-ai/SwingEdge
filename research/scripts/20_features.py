"""Round 4, step 1: per-stock indicators (computed on each stock's OWN bars, finite windows only, no recursive smoothing),
cross-sectional ranks, regime series and forward returns.  usage: python3 -I 20_features.py <work dir> <out_r4 dir>
Writes <out_r4>/feat.npz (float32 T x N arrays) + meta.pkl.  Runtime about 2-3 min on 2 cores.
The function `stock_feats` is the reference implementation of every per-stock indicator in the REPORT4 spec.
"""
import sys, os, numpy as np, pandas as pd
WORK, OUT = sys.argv[1:3]
os.makedirs(OUT, exist_ok=True)

def roll_mean(x, n): return pd.Series(x).rolling(n, min_periods=n).mean().to_numpy(dtype=float, copy=True)
def roll_max(x, n): return pd.Series(x).rolling(n, min_periods=n).max().to_numpy(dtype=float, copy=True)
def roll_min(x, n): return pd.Series(x).rolling(n, min_periods=n).min().to_numpy(dtype=float, copy=True)
def roll_std(x, n): return pd.Series(x).rolling(n, min_periods=n).std(ddof=1).to_numpy(dtype=float, copy=True)
def lag(x, k):
    y = np.full(len(x), np.nan)
    if k < len(x): y[k:] = x[:len(x) - k]
    return y

def rsi_cutler(c, n):
    """RSI over the last n close-to-close changes, simple sums (Cutler). needs n+1 bars. both sums 0 -> 50."""
    d = np.r_[np.nan, np.diff(c)]
    g = roll_mean(np.where(d > 0, d, 0.0), n); lo = roll_mean(np.where(d < 0, -d, 0.0), n)
    g[:n] = np.nan; lo[:n] = np.nan             # first diff is undefined -> need bars 0..n
    s = g + lo
    return np.where(s > 0, 100.0 * g / np.where(s > 0, s, 1.0), 50.0) * np.where(np.isnan(s), np.nan, 1.0)

def stock_feats(o, h, l, c, v, craw, to_cr):
    """All arrays are one stock's own consecutive bars (oldest first). Returns dict of arrays aligned to bars."""
    n = len(c); F = {}
    pc = lag(c, 1)
    tr = np.where(np.isnan(pc), h - l, np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc))))
    F['atr14'] = roll_mean(tr, 14)                          # simple mean of true range, 14 bars
    F['sma20'] = roll_mean(c, 20); F['sma50'] = roll_mean(c, 50); F['sma150'] = roll_mean(c, 150); F['sma200'] = roll_mean(c, 200)
    F['sma200_21'] = lag(F['sma200'], 21); F['sma150_20'] = lag(F['sma150'], 20)
    F['hi252'] = roll_max(h, 252); F['lo252'] = roll_min(l, 252)
    F['hi20c'] = roll_max(c, 20)                            # highest CLOSE of last 20 bars incl. today
    F['hi20h_prev'] = lag(roll_max(h, 20), 1)               # highest HIGH of the 20 bars before today
    F['r21'] = c / lag(c, 21) - 1
    F['r6_1'] = lag(c, 21) / lag(c, 126) - 1                # 6-1 month momentum
    F['r12_1'] = lag(c, 21) / lag(c, 252) - 1               # 12-1 month momentum
    r1 = c / pc - 1
    F['vol126'] = roll_std(r1, 126)                         # needs 127 bars
    F['vol20'] = roll_std(r1, 20)
    F['rsi2'] = rsi_cutler(c, 2); F['rsi5'] = rsi_cutler(c, 5)
    rng10 = (roll_max(h, 10) - roll_min(l, 10)) / c
    F['rng10'] = rng10
    F['tight'] = rng10 / roll_mean(rng10, 120)              # <1 = contracting vs own last 120 bars (needs 129 bars)
    F['voldry'] = roll_mean(v, 10) / roll_mean(v, 60)
    F['vr50'] = v / lag(roll_mean(v, 50), 1)                # today's volume vs previous 50-bar average
    F['minrel150_120'] = roll_min(c / F['sma150'], 120)     # lowest close/SMA150 over last 120 bars
    F['to20'] = roll_mean(to_cr, 20)                        # Rs crore, unadjusted close * volume
    F['ret1'] = r1
    F['nbars'] = np.arange(1, n + 1, dtype=float)
    return F

if __name__ == '__main__':
    W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
    O, H, L, C, V, TO, CR = (W[k].values for k in ['o', 'h', 'l', 'c', 'v', 'to_cr', 'craw'])
    dates = W['c'].index; syms = np.array(W['c'].columns); T, N = C.shape
    names = None; A = {}
    for j in range(N):
        idx = np.where(~np.isnan(C[:, j]))[0]
        if len(idx) == 0: continue
        F = stock_feats(O[idx, j], H[idx, j], L[idx, j], C[idx, j], V[idx, j], CR[idx, j], TO[idx, j])
        if names is None:
            names = list(F); A = {k: np.full((T, N), np.nan, np.float32) for k in names}
        for k in names: A[k][idx, j] = F[k]
    print('per-stock indicators done', flush=True)
    c = C
    # eligibility (liquid universe) on day D
    r1 = A['ret1']
    band = (np.abs(r1 - 0.05) < 0.003) | (np.abs(r1 - 0.10) < 0.003) | (np.abs(r1 - 0.20) < 0.003)
    uclock = (c >= H * 0.9995) & band
    liq = (A['to20'] >= 25) & (CR >= 50) & ~np.isnan(c)
    # ranks across the liquid universe (pandas average-rank / count)
    def xr(x, m):
        return pd.DataFrame(np.where(m, x, np.nan)).rank(axis=1, pct=True, method='average').values.astype(np.float32)
    A['rs6'] = xr(A['r6_1'], liq); A['rs12'] = xr(A['r12_1'], liq)
    rsavg = np.where(np.isnan(A['rs6']) | np.isnan(A['rs12']), np.nan, (A['rs6'] + A['rs12']) / 2)
    A['rsavg'] = rsavg.astype(np.float32)
    A['ram'] = (A['r6_1'] / (A['vol126'] * np.sqrt(105))).astype(np.float32)   # 6-1 return / volatility scaled to the 105-bar window
    # Nifty (price index) aligned to panel dates
    n50 = pd.read_pickle(os.path.join(WORK, 'nifty50.pkl')).reindex(dates).ffill()
    nc = n50.Close.to_numpy(dtype=float, copy=True)
    ns = pd.Series(nc)
    reg = pd.DataFrame({'n50': nc, 'n50_sma50': ns.rolling(50, min_periods=50).mean().values,
                        'n50_sma200': ns.rolling(200, min_periods=200).mean().values,
                        'n50_r6_1': (ns.shift(21) / ns.shift(126) - 1).values, 'n50_r12_1': (ns.shift(21) / ns.shift(252) - 1).values},
                       index=dates)
    above50 = (c > A['sma50']); b_ok = liq & ~np.isnan(A['sma50'])
    reg['breadth50'] = np.where(b_ok.sum(1) > 0, (above50 & b_ok).sum(1) / np.maximum(b_ok.sum(1), 1), np.nan)
    reg['n_liq'] = liq.sum(1)
    # forward returns, panel-day horizons; missing exit close -> last available close in (D, D+H]
    Cff = pd.DataFrame(C).ffill().to_numpy(dtype=float, copy=True)
    lastbar = pd.DataFrame(np.where(~np.isnan(C), np.arange(T)[:, None], np.nan)).ffill().to_numpy(dtype=float, copy=True)
    O1 = np.full_like(C, np.nan); O1[:-1] = O[1:]
    fwd = {}
    for Hh in (5, 10, 20, 40, 60):
        ex = np.full_like(C, np.nan); ex[:-Hh] = Cff[Hh:]
        lb = np.full_like(C, np.nan); lb[:-Hh] = lastbar[Hh:]
        ok = lb > np.arange(T)[:, None]                     # at least one bar after D
        ex = np.where(ok, ex, np.nan)
        fwd[f'fa{Hh}'] = (ex / C - 1).astype(np.float32)    # entry D close
        fwd[f'fb{Hh}'] = (ex / O1 - 1).astype(np.float32)   # entry D+1 open
    np.savez(os.path.join(OUT, 'feat.npz'), **A, **fwd, liq=liq, uclock=uclock)
    pd.to_pickle(dict(dates=dates, syms=syms, reg=reg), os.path.join(OUT, 'meta.pkl'))
    print('saved', len(A), 'features', T, N, flush=True)
