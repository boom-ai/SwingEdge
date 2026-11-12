import pandas as pd, numpy as np, sys
OUT='/home/claude/research/out_r4_review'
R=pd.read_csv(f'{OUT}/C_variants_raw.csv'); B=pd.read_csv(f'{OUT}/C_bench.csv')
bm=B.groupby(['period','bench']).cagr.median(); bmd=B.groupby(['period','bench']).mdd.median()
lab={'V0':'baseline 4sl score X10','V1':'low-vol rank','V2':'low-ATR% rank','V3':'turnover rank 101-300','V4':'turnover top100','V5':'8 slots','V6':'12 slots','V7':'60d hold 8sl','V8':'60d hold+5ATR stop','V9':'60d hold+20% stop','V10':'BUY>=70','V11':'breadth>=50%'}
rows=[]
for v in lab:
    r={'vid':v,'variant':lab[v]}
    for p in ('IS','OOS'):
        d=R[(R.vid==v)&(R.period==p)&R.null.isna()]; 
        a=R[(R.vid==v)&(R.period==p)&(R.null=='A')]; b=R[(R.vid==v)&(R.period==p)&(R.null=='B')]
        med=d.cagr.median()
        r[p+'_cagr']=round(med,1); r[p+'_min..max']=f'{d.cagr.min():.0f}..{d.cagr.max():.0f}'; r[p+'_mdd']=round(d.mdd.median(),0)
        r[p+'_pctA']=round(100*(a.cagr<med).mean()); r[p+'_pctB']=round(100*(b.cagr<med).mean())
        r[p+'_nullA_med']=round(a.cagr.median(),1); r[p+'_nullB_med']=round(b.cagr.median(),1)
        r[p+'_trades']=int(d.trades.median())
    rows.append(r)
T=pd.DataFrame(rows); T.to_csv(f'{OUT}/C_summary.csv',index=False)
pd.set_option('display.width',250); print(T.to_string())
print({k:round(v,1) for k,v in bm.items()}); print({k:round(v,1) for k,v in bmd.items()})
d=R[(R.vid=='V0')&(R.period=='OOSr')&R.null.isna()]; print('V0 researcher window OOSr median',d.cagr.median(), d.cagr.min(), d.cagr.max())
# null-wide: probability random beats Nifty
for p in ('IS','OOS'):
    n=R[(R.period==p)&R.null.notna()]; print(p,'random(A,B) P(cagr>Nifty median)', round((n.cagr>bm[(p,'Nifty')]).mean(),3), ' P(>EW)', round((n.cagr>bm[(p,'EW')]).mean(),3))
    for k in ('A','B'):
        n=R[(R.period==p)&(R.null==k)]; print(' null',k,'P>Nifty',round((n.cagr>bm[(p,'Nifty')]).mean(),3),'median',round(n.cagr.median(),1),'p95',round(n.cagr.quantile(.95),1),'mdd med',round(n.mdd.median()))
