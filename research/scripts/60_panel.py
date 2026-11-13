"""Round 6 proposal checks: analyst-panel managers FOR vs AGAINST, 60-session excess; vetoes inside BUY; new listings."""
import sys, os, numpy as np, pandas as pd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib import import_module
cm = import_module('40_common')
F, S, dates, reg, U, nw_t, dayavg = cm.F, cm.S, cm.dates, cm.reg, cm.U, cm.nw_t, cm.dayavg
g = cm.g; C, CR = cm.C, cm.CR
T, N = C.shape
t0, ts, GAP = cm.t0, cm.tsplit, cm.GAP
B_OOS = GAP - 61
FA = g('fa60')
Rated = U & ~np.isnan(S)
ew = dayavg(Rated, FA)
EX = FA - ew[:, None]
SMA20, SMA50, SMA200, SMA200_21 = g('sma20'), g('sma50'), g('sma200'), g('sma200_21')
ATR, VOL, TO, RS, VD, RSI2 = g('atr14'), g('vol126'), g('to20'), g('rsavg'), g('voldry'), g('rsi2')
def xr(x, m): return pd.DataFrame(np.where(m, x, np.nan)).rank(axis=1, pct=True).values
pVOL = xr(VOL, Rated); pTO = xr(TO, Rated); atrp = ATR / C; pATR = xr(atrp, Rated)
ext = (C - SMA50) / ATR; pEXT = xr(ext, Rated)
BUY = Rated & (S >= 85) & (C > SMA50) & ~cm.UCL
M = {}
M['Trend'] = ((C > SMA50) & (SMA50 > SMA200) & (SMA200 > SMA200_21), (C < SMA200) & (SMA200 < SMA200_21))
M['Momentum/RS'] = (RS >= 0.7, RS <= 0.3)
M['Risk(vol126 pct)'] = (pVOL <= 1/3, pVOL >= 2/3)
M['Risk(ATR% pct)'] = (pATR <= 1/3, pATR >= 2/3)
M['Liquidity(TO20 pct)'] = (pTO >= 2/3, pTO <= 1/3)
M['Timing(ext SMA50/ATR)'] = ((ext > 0) & (ext <= 2), pEXT >= 0.9)
M['Timing(RSI2)'] = (RSI2 <= 10, RSI2 >= 90)
M['Participation(voldry)'] = (VD >= 1.2, VD <= 0.8)
per = {'IS': (t0, ts), 'OOS': (ts, B_OOS + 1)}
rows = []
def grp(mask, a, b):
    return dayavg(mask, EX)[a:b]
for name, (fo, ag) in M.items():
    for P, (a, b) in per.items():
        for univ, base in (('all', Rated), ('BUY', BUY)):
            f = grp(base & fo, a, b); x = grp(base & ag, a, b); d = f - x
            nf = (base & fo)[a:b].sum(); na = (base & ag)[a:b].sum()
            rows.append(dict(manager=name, per=P, univ=univ, shareFOR=round(nf / max(base[a:b].sum(), 1) * 100), shareAG=round(na / max(base[a:b].sum(), 1) * 100),
                             exFOR=np.nanmean(f) * 100, exAG=np.nanmean(x) * 100, spread=np.nanmean(d) * 100, t=nw_t(d, 60),
                             tAG=nw_t(x, 60)))
R = pd.DataFrame(rows).round(2); R.to_csv('/home/claude/research/out_r6/managers.csv', index=False)
pd.set_option('display.width', 250); print(R.to_string(index=False))
# regime manager: time-series, raw EW forward 60 return
n = reg['n50'].values; b50 = reg['breadth50'].values
rf = (n > reg['n50_sma200'].values) & (b50 > 0.5); ra = (n < reg['n50_sma200'].values) & (b50 < 0.4)
for P, (a, b) in per.items():
    e = ew[a:b]; print('REGIME', P, 'FOR days', rf[a:b].mean().round(2), 'EW fwd60', np.nanmean(e[rf[a:b]]) * 100, 'AG days', ra[a:b].mean().round(2), np.nanmean(e[ra[a:b]]) * 100, 'neutral', np.nanmean(e[~rf[a:b] & ~ra[a:b]]) * 100)
# new listings: first bar of stock after 2010-09 (exclude panel start), 60..250 bars, liquid filter
first = np.argmax(~np.isnan(C), axis=0); valid = first > 60
nb = g('nbars'); C0 = C[first, np.arange(N)]
since = C / C0 - 1
hiL = pd.DataFrame(C).cummax().values; ddL = C / hiL - 1
NL = (nb >= 60) & (nb <= 250) & valid[None, :] & F['liq']
exNL = FA - ew[:, None]
for P, (a, b) in per.items():
    m = NL[a:b]; print('NEWLIST', P, 'stock-days', m.sum(), 'stocks', m.any(0).sum(), 'ex', np.nanmean(dayavg(NL, exNL)[a:b]) * 100, 't', nw_t(dayavg(NL, exNL)[a:b], 60))
cut = np.nanquantile(since[t0:ts][NL[t0:ts]], [1/3, 2/3]); cutd = np.nanquantile(ddL[t0:ts][NL[t0:ts]], [1/3, 2/3])
print('cuts since', cut, 'dd from listing high', cutd)
for lab, X, cc in (('since', since, cut), ('ddHigh', ddL, cutd)):
    for P, (a, b) in per.items():
        out = []
        for k, m in enumerate((X < cc[0], (X >= cc[0]) & (X < cc[1]), X >= cc[1])):
            s = dayavg(NL & m, exNL)[a:b]; out.append((round(np.nanmean(s) * 100, 2), round(nw_t(s, 60), 2), int((NL & m)[a:b].sum())))
        d = dayavg(NL & (X >= cc[1]), exNL)[a:b] - dayavg(NL & (X < cc[0]), exNL)[a:b]
        print('NL', lab, P, 'T1/T2/T3 (ex,t,n)', out, 'T3-T1', round(np.nanmean(d) * 100, 2), 't', round(nw_t(d, 60), 2))
