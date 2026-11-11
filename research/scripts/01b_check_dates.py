import os,re,sys,datetime as dt,collections
import pandas as pd
SRC=sys.argv[1]
mis=[];seen=collections.defaultdict(list)
for f in sorted(os.listdir(SRC)):
    m=re.match(r'sec_bhavdata_full_(\d{2})(\d{2})(\d{4})\.csv',f)
    if not m: continue
    d=dt.date(int(m[3]),int(m[2]),int(m[1]))
    with open(os.path.join(SRC,f)) as fh:
        fh.readline(); l=fh.readline().split(',')
    try: fd=dt.datetime.strptime(l[2].strip(),'%d-%b-%Y').date()
    except Exception as e: fd=None
    seen[fd].append(d)
    if fd!=d: mis.append((d,fd))
print(len(mis)); print(mis[:60])
dup={k:v for k,v in seen.items() if len(v)>1}; print('dups',len(dup)); print(list(dup.items())[:20])
idx=pd.read_csv(sys.argv[2],usecols=['Date'],parse_dates=['Date'])
idates=set(x.date() for x in idx['Date'] if x>=pd.Timestamp('2010-06-10'))
fdates=set(k for k in seen if k)
print('index days',len(idates),'file content days',len(fdates))
print('in index not in files',sorted(idates-fdates))
print('in files not index',sorted(fdates-idates))
