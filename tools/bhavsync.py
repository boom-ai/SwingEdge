#!/usr/bin/env python3
"""Keeps a rolling folder of official daily bhavcopies (NSE + BSE cash market) for the whole-market scan.

usage: bhavsync.py DIR [SESSIONS=275] [TODAY yyyy-mm-dd]

Per trading day it stores (gzipped, exactly as published):
  nse_YYYYMMDD.csv.gz      NSE UDiFF  BhavCopy_NSE_CM_0_0_0_YYYYMMDD_F_0000.csv.zip   (fallback: nsefull_ = sec_bhavdata_full)
  bse_YYYYMMDD.csv.gz      BSE UDiFF  BhavCopy_BSE_CM_0_0_0_YYYYMMDD_F_0000.CSV
  EQUITY_L.csv             NSE symbol list with ISINs (refreshed weekly; only needed for the nsefull fallback)
Markers: YYYYMMDD.holiday (both NSE formats answered 404 for a day more than 2 days old), bse_YYYYMMDD.none (BSE 404, > 3 days old).
Only missing days are downloaded, so a daily run makes a handful of requests; an empty folder takes ~SESSIONS x 2 requests.
Never deletes a good file it still needs; transient errors (timeouts, 403, 5xx) are retried and leave no marker.
"""
import sys, os, io, gzip, time, zipfile, datetime as dt, urllib.request, urllib.error

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
NSE_UDIFF = "https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_%s_F_0000.csv.zip"
NSE_FULL = "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_%s.csv"
NSE_LIST = "https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv"
BSE_UDIFF = "https://www.bseindia.com/download/BhavCopy/Equity/BhavCopy_BSE_CM_0_0_0_%s_F_0000.CSV"
SLEEP = 0.35
MAX_TRANSIENT = {"nse": 30, "bse": 12}   # give up on a site for this run after this many transient failures (it is blocking us)

class NotFound(Exception): pass
transient = {"nse": 0, "bse": 0}

def site(url): return "bse" if "bseindia" in url else "nse"
def blocked(s): return transient[s] >= MAX_TRANSIENT[s]

def get(url, referer=None, tries=3):
    """Bytes of url; NotFound on 404; None after retries on anything else."""
    for k in range(tries):
        try:
            h = {"User-Agent": UA, "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9"}
            if referer: h["Referer"] = referer
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=45) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404: raise NotFound(url)
            print("  http", e.code, url)
        except Exception as e:
            print("  error", type(e).__name__, e, url)
        time.sleep((2, 6, 15)[k])
    transient[site(url)] += 1
    return None

def gz_write(path, text_bytes):
    tmp = path + ".tmp"
    with gzip.open(tmp, "wb") as f: f.write(text_bytes)
    os.replace(tmp, path)

def good(path): return os.path.exists(path) and os.path.getsize(path) > 2000

def unzip_first(b):
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        return z.read(names[0]) if names else b""

def nse_day(d, folder, today):
    """True if an NSE file for day d is (now) present."""
    ymd = d.strftime("%Y%m%d")
    p1, p2 = os.path.join(folder, "nse_%s.csv.gz" % ymd), os.path.join(folder, "nsefull_%s.csv.gz" % ymd)
    if good(p1) or good(p2): return True
    if os.path.exists(os.path.join(folder, ymd + ".holiday")): return False
    if blocked("nse"): return False
    nf = 0
    try:
        b = get(NSE_UDIFF % ymd); time.sleep(SLEEP)
        if b:
            t = unzip_first(b)
            if b"TckrSymb" in t[:600] and t.count(b"\n") > 500: gz_write(p1, t); return True
            print("  NSE UDiFF not a bhavcopy", ymd, len(b))
    except NotFound: nf += 1
    except zipfile.BadZipFile: print("  NSE UDiFF bad zip", ymd)
    try:
        b = get(NSE_FULL % d.strftime("%d%m%Y")); time.sleep(SLEEP)
        if b and b"SYMBOL" in b[:300] and b.count(b"\n") > 500: gz_write(p2, b); return True
    except NotFound: nf += 1
    if nf == 2 and d < today - dt.timedelta(days=2):
        open(os.path.join(folder, ymd + ".holiday"), "w").close()
    return False

def bse_day(d, folder, today):
    ymd = d.strftime("%Y%m%d")
    p = os.path.join(folder, "bse_%s.csv.gz" % ymd)
    if good(p) or os.path.exists(os.path.join(folder, "bse_%s.none" % ymd)) or blocked("bse"): return
    try:
        b = get(BSE_UDIFF % ymd, referer="https://www.bseindia.com/"); time.sleep(SLEEP)
        if b and b"TckrSymb" in b[:600] and b.count(b"\n") > 500: gz_write(p, b)
        elif b: print("  BSE not a bhavcopy", ymd, len(b))
    except NotFound:
        if d < today - dt.timedelta(days=3): open(os.path.join(folder, "bse_%s.none" % ymd), "w").close()

def main():
    folder = sys.argv[1]
    sessions = int(sys.argv[2]) if len(sys.argv) > 2 else 275
    ist = dt.datetime.now(dt.timezone.utc).replace(tzinfo=None) + dt.timedelta(hours=5, minutes=30)
    today = dt.date.fromisoformat(sys.argv[3]) if len(sys.argv) > 3 else ist.date()
    os.makedirs(folder, exist_ok=True)
    t0 = time.time()
    lst = os.path.join(folder, "EQUITY_L.csv")
    if not good(lst) or time.time() - os.path.getmtime(lst) > 7 * 86400:
        try:
            b = get(NSE_LIST)
            if b and b"SYMBOL" in b[:100] and b.count(b"\n") > 1000: open(lst, "wb").write(b)
        except NotFound: pass
    have, day, tried = [], today, 0
    while len(have) < sessions and tried < int(sessions * 1.6) + 30:
        if day.weekday() < 5:
            # today's files appear in the evening; before 18:00 IST don't even ask
            if not (day == ist.date() and ist.hour < 18):
                if nse_day(day, folder, today):
                    have.append(day)
                    bse_day(day, folder, today)
        day -= dt.timedelta(days=1); tried += 1
    have.sort()
    print("NSE sessions: %d (%s -> %s); transient failures: %s; %.0f s" % (len(have), have[0] if have else None, have[-1] if have else None, transient, time.time() - t0))
    # prune what the window no longer needs (keep markers inside the window so holidays are not re-asked)
    if len(have) >= sessions:
        cut = have[0].strftime("%Y%m%d")
        for x in os.listdir(folder):
            ymd = "".join(ch for ch in x if ch.isdigit())[:8]
            if len(ymd) == 8 and ymd < cut: os.remove(os.path.join(folder, x))
    nb = sum(1 for x in os.listdir(folder) if x.startswith("bse_") and x.endswith(".csv.gz"))
    print("BSE files:", nb)

if __name__ == "__main__": main()
