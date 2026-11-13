"""Score-neutral manager test: excess vs same-day same-score-band (10-pt) mean. Does a manager add anything beyond the score?"""
import runpy, numpy as np, pandas as pd, sys
sys.argv = ['x']
src = open('scripts/60_panel.py').read().split("per = {'IS'")[0]
ns = {'__file__': '/home/claude/research/scripts/60_panel.py'}; exec(compile(src, '60_panel', 'exec'), ns)
EX, S, Rated, dayavg, nw_t, M, BUY = ns['EX'], ns['S'], ns['Rated'], ns['dayavg'], ns['nw_t'], ns['M'], ns['BUY']
t0, ts, B = ns['t0'], ns['ts'], ns['B_OOS'] + 1
bnd = np.floor(np.where(np.isnan(S), -1, S) / 10)
RX = np.full_like(EX, np.nan)
for b in range(0, 11):
    m = Rated & (bnd == b); mu = dayavg(m, EX); RX = np.where(m, EX - mu[:, None], RX)
rows = []
for name, (fo, ag) in M.items():
    for P, (a, b) in {'IS': (t0, ts), 'OOS': (ts, B)}.items():
        f = dayavg(Rated & fo, RX)[a:b]; x = dayavg(Rated & ag, RX)[a:b]; d = f - x
        rows.append(dict(manager=name, per=P, rxFOR=np.nanmean(f)*100, rxAG=np.nanmean(x)*100, spread=np.nanmean(d)*100, t=nw_t(d, 60), tAG=nw_t(x, 60)))
# AVOID rule vs trend-AGAINST overlay in low scores, and WAIT band
SMA200, SMA200_21, C = ns['SMA200'], ns['SMA200_21'], ns['C']
for P, (a, b) in {'IS': (t0, ts), 'OOS': (ts, B)}.items():
    w = Rated & (S >= 55) & (S < 85)
    tA = M['Trend'][1]
    d = dayavg(w & tA, EX)[a:b] - dayavg(w & ~tA, EX)[a:b]
    print('WAIT band 55-85: trendAGAINST vs rest', P, round(np.nanmean(d)*100, 2), 't', round(nw_t(d, 60), 2), 'share', round((w & tA)[a:b].sum() / w[a:b].sum()*100, 1))
print(pd.DataFrame(rows).round(2).to_string(index=False))
