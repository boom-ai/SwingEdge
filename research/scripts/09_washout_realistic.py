"""Washout-dip rule simulated the way the app trades it: no repeat entry in a stock already held, optional cap on concurrent positions.
usage: python3 -I 09_washout_realistic.py"""
import numpy as np, pandas as pd
W = pd.read_pickle('work/wide_adj.pkl'); o,h,l,c,v,to,craw=(W[k] for k in ['o','h','l','c','v','to_cr','craw'])
uni=[s.strip() for s in open('universe.txt') if s.strip() and s.strip() in c.columns]
c=c[uni]; o=o[uni]; h=h[uni]; l=l[uni]; v=v[uni]
dates=c.index; SPLIT=pd.Timestamp('2022-01-01'); START=pd.Timestamp('2011-01-01')
sma=lambda x,n:x.rolling(n,min_periods=int(n*0.9)).mean()
s50,s200=sma(c,50),sma(c,200); d=c.diff(); pc=c.shift(1)
ag=d.clip(lower=0).ewm(alpha=.5,adjust=False,min_periods=6).mean(); al=(-d).clip(lower=0).ewm(alpha=.5,adjust=False,min_periods=6).mean()
rsi2=100-100/(1+ag/al.replace(0,1e-12))
tr=np.maximum(h-l,np.maximum((h-pc).abs(),(l-pc).abs())); atr=tr.rolling(14).mean()
liq=((c*v).shift(1).rolling(20,min_periods=20).mean()>=25e7)&(c>=50)&s200.notna()
b_all=(c>s50).where(s50.notna()).mean(axis=1); b_liq=(c>s50).where(liq).mean(axis=1)
C,O,L,A,R=c.values,o.values,l.values,atr.values,rsi2.values
def sim(br,thr,rsimax=10.0,hold=5,stopk=3.0,cap=12.0,maxpos=None,new_per_day=4,cost=0.25,skip_reentry=True):
    cand=((c>s200)&(rsi2<rsimax)&liq).values; brv=br.values; busy=np.full(C.shape[1],-1); open_until=[]; res=[]
    for t in range(len(dates)-hold):
        if dates[t]<START or not (brv[t]<thr): continue
        open_until=[x for x in open_until if x>=t]
        js=np.where(cand[t])[0]; js=js[np.argsort(R[t,js])]; taken=0
        for j in js:
            if taken>=new_per_day: break
            if maxpos is not None and len(open_until)>=maxpos: break
            if skip_reentry and busy[j]>=t: continue
            e=C[t,j]; dist=min(stopk*A[t,j],e*cap/100) if stopk>0 else 1e18; st=e-max(dist,e*0.01); r=None; ex=t+hold
            for k in range(1,hold+1):
                if np.isnan(C[t+k,j]): ex=t+k-1; r=C[t+k-1,j]/e-1; break
                if stopk>0 and O[t+k,j]<=st: r=O[t+k,j]/e-1; ex=t+k; break
                if stopk>0 and L[t+k,j]<=st: r=st/e-1; ex=t+k; break
                if k==hold: r=C[t+k,j]/e-1
            busy[j]=ex; open_until.append(ex); res.append((dates[t],r*100-cost)); taken+=1
    s=pd.Series([x[1] for x in res],index=[x[0] for x in res])
    return s
def show(lab,s):
    a,b=s[s.index<SPLIT],s[s.index>=SPLIT]
    yr=s.groupby(s.index.year).mean().round(1).to_dict()
    print('%-58s n=%4d avg=%+.2f%% win=%.0f%% | IS %+.2f (n=%d) OOS %+.2f (n=%d) | worst yr %s'%(lab,len(s),s.mean(),(s>0).mean()*100,a.mean(),len(a),b.mean(),len(b),min(yr.items(),key=lambda x:x[1])))
    return yr
show('engine-like: breadth all-574 <25%, 3ATR stop, 4 new/day',sim(b_all,0.25))
show('  same, no stop',sim(b_all,0.25,stopk=0))
show('  same, re-entry allowed (research style)',sim(b_all,0.25,stopk=0,skip_reentry=False))
show('breadth liquid-only <25%, 3ATR stop',sim(b_liq,0.25))
show('breadth liquid-only <20%, 3ATR stop',sim(b_liq,0.20))
show('breadth liquid-only <25%, no stop',sim(b_liq,0.25,stopk=0))
for mp in (4,):
    for br,nm in ((b_liq,'liquid'),(b_all,'all574')):
        for thr in (0.25,0.20,0.15):
            for stopk in (0,3.0):
                yr=show('MAX %d POSITIONS, breadth %s <%.2f, stop %s'%(mp,nm,thr,stopk or 'none'),sim(br,thr,stopk=stopk,maxpos=mp))
print(yr)
for hold in (3,5,8): show('max4, liquid<0.20, no stop, hold %d'%hold,sim(b_liq,0.20,stopk=0,maxpos=4,hold=hold))
show('max4, liquid<0.20, no stop, RSI2<5',sim(b_liq,0.20,stopk=0,maxpos=4,rsimax=5))
show('max4, liquid<0.20, no stop, cost 0.40',sim(b_liq,0.20,stopk=0,maxpos=4,cost=0.40))
