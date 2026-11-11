"""Build the research panel from RAW NSE bhavcopies only (work/raw.pkl from 01_parse_raw.py).
Finding: the eod2_data snapshot is NOT reliably split/bonus adjusted (e.g. SHRIRAMFIN 2025-01-10, INDIAMART 2023-06-21),
so it is used only for (a) symbol-rename stitching (work/renames_detected.csv, made by 02_build_panel.py) and (b) Nifty 50.
Own adjustment: an overnight move with open/prev_close < 0.75 or > 1.40, confirmed by close/prev_close < 0.80 or > 1.30,
is treated as a corporate action (split / bonus / demerger / consolidation): all earlier prices are multiplied by the
nearest standard ratio if within 6%, otherwise by the observed gap itself (i.e. the event is neutralised).
Turnover = close * volume (the TURNOVER_LACS column changes units in 2021).
usage: python3 -I 03_build_adj_panel.py <raw.pkl> <eod2 daily dir> <work dir>
"""
import os, re, sys, numpy as np, pandas as pd
RAW, EOD, OUT = sys.argv[1:4]
raw = pd.read_pickle(RAW)
raw = raw[(raw.close > 0) & (raw.vol > 0) & (raw.open > 0) & (raw.high > 0) & (raw.low > 0)]
raw = raw[raw.date.dt.dayofweek < 5]
nsym = raw.groupby('date').symbol.size()
good = nsym[nsym >= 500].index
print('dates dropped (thin sessions)', sorted(set(nsym.index) - set(good)))
raw = raw[raw.date.isin(good)].copy()
ren = pd.read_csv(os.path.join(OUT, 'renames_detected.csv'), parse_dates=['from', 'to'])
newsym = raw.symbol.values.copy(); sv = raw.symbol.values; dv = raw.date.values
for old, g in ren.groupby('old'):
    m_old = sv == old
    for new, a, b in zip(g.new, g['from'], g['to']):
        newsym[m_old & (dv >= np.datetime64(a)) & (dv <= np.datetime64(b))] = new
raw['symbol'] = newsym
raw = raw.drop_duplicates(['symbol', 'date']).sort_values(['symbol', 'date'])
raw['to_cr'] = raw.close * raw.vol / 1e7
ETF = re.compile(r'BEES|ETF|NIFTY|SENSEX|LIQUID|SILVER|MON100|MAFANG|NV20|N100|HNGSNG|GOLD(?!IAM|STONE)|LOWVOL|ALPHA|MOM\d|MID\d|BSE500|BSLNIF|SHARIA|^CPSE|BOND|GILT|GSEC|LIQ|TBILL|MOVALUE|MOQUALITY|^MOM|^MAK|^ICICIB22|^SBI(?!N$|CARD$|LIFE$)|^KOTAK(?!BANK$)|^HDFC(?!BANK$|LIFE$|AMC$|$)|^UTI(?!AMC$|BANK$)|^AXIS(?!BANK$|CADES$)|^ICICI(?!BANK$|GI$|PRULI$|AMC$)|^ABSL(?!AMC$)|^LIC(?!HSGFIN$|I$)|^NIP|^MIRAE|^DSP|^GROWW|^ZERODHA|^INVESCO|^IVZ|^QUANTUM|^EDEL(?!WEISS$)|^TATA(?!MOTORS$|STEEL$|POWER$|ELXSI$|CHEM$|COMM$|CONSUM$|INVEST$|TECH$|MTRDVR$|GLOBAL$|COFFEE$|SPONGE$|METALI$|STLBSL$|CAP$|MOTORS)|\dD$|^AONE|^BANDHAN.*\d|^BFSI$|^CONS$|^AUTOIETF|^HEALTHY|^INFRAIETF|^ITIETF|^MIDCAP$|^JUNIORBEES|^PSUBANK$|^EQUAL|^MOREALTY|^SML|^TOP\d|^NEXT|^EVINDIA|^MODEFENCE|^MOHEALTH|^MOSMALL|^MOLOWVOL|^MOMENTUM|^MULTICAP$|^FMCGIETF|^COMMOIETF|^FINIETF|^OILIETF|^PVTBANIETF|^METALIETF|^CONSUMIETF|^MNC$|^DIVOPPBEES|^LICNMID|^UNIONGOLD|^BBNPP|^ESILVER|^EGOLD|^EMULTIMQ|^ELM250|^EBANK|^ESG$|^LTGILT|^ABGSEC|^IDFSENS|^SDL\d|^EBBETF')
syms_all = raw.symbol.unique()
etf = [s for s in syms_all if ETF.search(s)]
pd.Series(sorted(etf)).to_csv(os.path.join(OUT, 'excluded_etf_like.csv'), index=False, header=False)
raw = raw[~raw.symbol.isin(etf)]
mx = raw.groupby('symbol').to_cr.transform(lambda s: s.rolling(20).mean().max())
raw = raw[mx >= 25.0]
print('symbols ever liquid (20d avg turnover >= 25cr)', raw.symbol.nunique(), 'rows', len(raw), 'etf-like excluded', len(etf), flush=True)
RATIOS = np.array([1/2, 1/3, 1/4, 1/5, 1/6, 1/7, 1/8, 1/10, 1/11, 1/15, 1/20, 1/25, 1/50, 1/100, 2/3, 3/5, 2/5, 3/4, 4/7, 5/7, 3/8, 5/8, 4/9, 5/9, 2/7, 3/7,
                   3/2, 2, 5/2, 3, 4, 5, 10, 20, 25, 50, 100])
parts = []; adj = []
for name, r in raw.groupby('symbol'):
    if len(r) < 60: continue
    o, h, l, c, v = (r[k].values.astype(float) for k in ['open', 'high', 'low', 'close', 'vol'])
    pc = np.r_[np.nan, c[:-1]]
    go = o / pc; gc = c / pc
    f = np.ones(len(r))
    for i in np.where(((go < 0.75) & (gc < 0.80)) | ((go > 1.40) & (gc > 1.30)))[0]:
        g = go[i]; k = RATIOS[np.argmin(np.abs(np.log(RATIOS / g)))]
        matched = abs(np.log(k / g)) < 0.06
        if not matched: k = g
        f[:i] *= k
        adj.append((name, r.date.iloc[i].date(), round(g, 4), round(k, 4), matched, round(r.to_cr.iloc[max(0, i - 20):i].mean(), 1)))
    parts.append(pd.DataFrame({'sym': name, 'date': r.date.values, 'o': o * f, 'h': h * f, 'l': l * f, 'c': c * f,
                               'v': v / f, 'to_cr': r.to_cr.values, 'craw': c}))
a = pd.DataFrame(adj, columns=['sym', 'date', 'gap', 'factor', 'ratio_matched', 'avg_to_cr_before'])
a.to_csv(os.path.join(OUT, 'adjustments.csv'), index=False)
print('adjustments', len(a), 'ratio-matched', int(a.ratio_matched.sum()), 'neutralised (unmatched)', int((~a.ratio_matched).sum()),
      '| unmatched while liquid(>=25cr)', int(((~a.ratio_matched) & (a.avg_to_cr_before >= 25)).sum()))
panel = pd.concat(parts, ignore_index=True)
panel['h'] = panel[['o', 'h', 'c']].max(axis=1); panel['l'] = panel[['o', 'l', 'c']].min(axis=1)
wide = {k: panel.pivot(index='date', columns='sym', values=k) for k in ['o', 'h', 'l', 'c', 'v', 'to_cr', 'craw']}
pd.to_pickle(wide, os.path.join(OUT, 'wide_adj.pkl'))
d = pd.read_csv(os.path.join(EOD, 'nifty 50.csv'), usecols=['Date', 'Open', 'High', 'Low', 'Close'], parse_dates=['Date'])
d = d.drop_duplicates('Date').set_index('Date').sort_index()
d[d.index.dayofweek < 5].to_pickle(os.path.join(OUT, 'nifty50.pkl'))
print(wide['c'].shape, wide['c'].index[0], wide['c'].index[-1], 'nifty', d.index[0], d.index[-1])
