"""Parse raw NSE full bhavcopy files (tilak999/NSE-Data-bank, data/sec_bhavdata_full_DDMMYYYY.csv)
into one parquet/pickle long table. Data files are only parsed, never executed."""
import os, re, sys, datetime as dt
import pandas as pd, numpy as np
SRC = sys.argv[1]; OUT = sys.argv[2]
rows = []
files = sorted(f for f in os.listdir(SRC) if f.startswith('sec_bhavdata_full_'))
bad = 0
for i, f in enumerate(files):
    m = re.match(r'sec_bhavdata_full_(\d{2})(\d{2})(\d{4})\.csv', f)
    d = dt.date(int(m[3]), int(m[2]), int(m[1]))
    try:
        df = pd.read_csv(os.path.join(SRC, f), skipinitialspace=True, dtype=str)
    except Exception as e:
        print('ERR', f, e); bad += 1; continue
    df.columns = [c.strip() for c in df.columns]
    df = df[df['SERIES'].isin(['EQ', 'BE', 'BZ'])]
    out = pd.DataFrame({
        'date': pd.Timestamp(d),
        'symbol': df['SYMBOL'].str.strip(),
        'series': df['SERIES'],
        'prev': pd.to_numeric(df['PREV_CLOSE'], errors='coerce'),
        'open': pd.to_numeric(df['OPEN_PRICE'], errors='coerce'),
        'high': pd.to_numeric(df['HIGH_PRICE'], errors='coerce'),
        'low': pd.to_numeric(df['LOW_PRICE'], errors='coerce'),
        'close': pd.to_numeric(df['CLOSE_PRICE'], errors='coerce'),
        'vol': pd.to_numeric(df['TTL_TRD_QNTY'], errors='coerce'),
        'to_lacs': pd.to_numeric(df['TURNOVER_LACS'], errors='coerce'),
        'file_date': df['DATE1'].str.strip(),
    })
    rows.append(out)
    if i % 500 == 0: print(i, f, len(out), flush=True)
raw = pd.concat(rows, ignore_index=True)
# sanity: file date vs name date
# Some files are stale copies saved on holidays under the holiday's name: trust the date INSIDE the file.
fd = pd.to_datetime(raw['file_date'], format='%d-%b-%Y', errors='coerce')
mism = (fd != raw['date']).sum()
print('rows', len(raw), 'bad files', bad, 'rows with filename/content date mismatch', mism, 'unparseable', fd.isna().sum())
raw['date'] = fd
raw = raw.dropna(subset=['date', 'close']).drop(columns='file_date')
# one row per (date, symbol): prefer EQ
raw['pri'] = raw['series'].map({'EQ': 0, 'BE': 1, 'BZ': 2})
raw = raw.sort_values(['date', 'symbol', 'pri']).drop_duplicates(['date', 'symbol']).drop(columns='pri')
raw.to_pickle(OUT)
print(raw.shape, raw['date'].min(), raw['date'].max(), raw['symbol'].nunique())
