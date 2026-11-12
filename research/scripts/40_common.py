"""Shared loader for the independent review (prefix 40_). Read-only use of out_r4 and work."""
import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
WORK, R4, OUT = '/home/claude/research/work', '/home/claude/research/out_r4', '/home/claude/research/out_r4_review'
F = np.load(os.path.join(R4, 'feat.npz')); SC = np.load(os.path.join(R4, 'score.npz')); M = pd.read_pickle(os.path.join(R4, 'meta.pkl'))
dates, reg, syms = M['dates'], M['reg'], M['syms']; T = len(dates)
S = SC['S'].astype(np.float64)
g = lambda k: F[k].astype(np.float64)
W = pd.read_pickle(os.path.join(WORK, 'wide_adj.pkl'))
O, H, L, C, CR = (np.ascontiguousarray(W[k].values, dtype=np.float64) for k in ('o', 'h', 'l', 'c', 'craw')); del W
N = C.shape[1]
U = F['liq'] & ~np.isnan(F['sma200']) & ~np.isnan(F['hi252'])
t0 = int(np.searchsorted(dates, pd.Timestamp('2013-01-01'))); tsplit = int(np.searchsorted(dates, pd.Timestamp('2022-01-01')))
tend = int(np.searchsorted(dates, pd.Timestamp('2026-08-01')))
GAP = int(np.searchsorted(dates, pd.Timestamp('2025-06-20')))       # first bar after the 32-session hole in the panel
ATR, SMA50, SMA200, VOL126, TO20 = g('atr14'), g('sma50'), g('sma200'), g('vol126'), g('to20')
UCL = F['uclock']
yrs = dates.year.values
def nw_t(x, Lg):
    x = np.asarray(x, float); x = x[~np.isnan(x)]; n = len(x)
    if n < 20: return np.nan
    e = x - x.mean(); v = e @ e / n
    for k in range(1, min(Lg, n - 1) + 1): v += 2 * (1 - k / (Lg + 1)) * (e[k:] @ e[:-k]) / n
    return x.mean() / np.sqrt(v / n)
def dayavg(mask, X):
    m = mask & ~np.isnan(X); c = m.sum(1)
    return np.where(c > 0, np.where(m, X, 0).sum(1) / np.maximum(c, 1), np.nan)
