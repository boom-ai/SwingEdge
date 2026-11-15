#!/usr/bin/env python3
"""Daily money-flow table for every NSE stock from NSE's full bhavcopy (prices, turnover, delivery).

usage: flows.py BHAV_DIR OUT.csv [TODAY yyyy-mm-dd]
Downloads missing sec_bhavdata_full_DDMMYYYY.csv files (last ~70 sessions) into BHAV_DIR, then writes one row per stock.
Columns (money in rupees crore, % as percent):
  SYMBOL, CLOSE, R1, R5, R20       close and % change over 1 / 5 / 20 sessions
  TO1, TO20                        turnover today and 20-session average
  DLV1, DLV20                      delivery % today and 20-session average
  NET5, NET20                      delivered money on up days minus down days, last 5 / 20 sessions
  FLOW20                           NET20 / (20 x 60-session average turnover): net money in as a share of normal trading
  CMF20                            Chaikin money flow, 20 sessions (-1..1)
  DSURGE                           delivered value, 10-session average / 60-session average
  N, ASOF                          sessions available, last session date
"""
import sys, os, csv, time, datetime as dt, urllib.request, math

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
URL = "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_%s.csv"
SERIES = {"EQ", "BE", "BZ", "SM", "ST"}

def fetch(day, d):
    p = os.path.join(d, day.strftime("%Y%m%d") + ".csv")
    if os.path.exists(p) and os.path.getsize(p) > 1000: return p
    if os.path.exists(p + ".none"): return None
    try:
        req = urllib.request.Request(URL % day.strftime("%d%m%Y"), headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=40) as r: b = r.read()
        if len(b) < 1000 or b"SYMBOL" not in b[:200]: raise ValueError("not a bhavcopy")
        open(p, "wb").write(b); return p
    except Exception as e:
        # a holiday (404) is remembered; today may simply not be published yet
        if "404" in str(e) and day < dt.date.today() - dt.timedelta(days=1): open(p + ".none", "w").close()
        return None

def num(x):
    try:
        x = x.strip()
        return float(x) if x not in ("", "-") else math.nan
    except Exception: return math.nan

def main():
    d, out = sys.argv[1], sys.argv[2]
    today = dt.date.fromisoformat(sys.argv[3]) if len(sys.argv) > 3 else dt.date.today()
    os.makedirs(d, exist_ok=True)
    files, day, tries = [], today, 0
    while len(files) < 70 and tries < 120:
        if day.weekday() < 5:
            p = fetch(day, d)
            if p: files.append((day, p))
            time.sleep(0.2)
        day -= dt.timedelta(days=1); tries += 1
    files.sort()
    print("sessions:", len(files), files[0][0] if files else None, "->", files[-1][0] if files else None)
    if len(files) < 25: sys.exit("too few sessions")
    days = [f[0] for f in files]
    S = {}  # sym -> list of (i, close, high, low, prev, turnover cr, delivery frac)
    for i, (day, p) in enumerate(files):
        with open(p, newline="", encoding="utf-8", errors="replace") as f:
            rd = csv.reader(f); hdr = [h.strip().upper() for h in next(rd)]
            ix = {h: k for k, h in enumerate(hdr)}
            for row in rd:
                if len(row) < len(hdr): continue
                ser = row[ix["SERIES"]].strip()
                if ser not in SERIES: continue
                sym = row[ix["SYMBOL"]].strip().upper()
                c, h, l, pc = num(row[ix["CLOSE_PRICE"]]), num(row[ix["HIGH_PRICE"]]), num(row[ix["LOW_PRICE"]]), num(row[ix["PREV_CLOSE"]])
                to = num(row[ix["TURNOVER_LACS"]]) / 100.0
                dp = num(row[ix["DELIV_PER"]])
                if not (c > 0) or math.isnan(to): continue
                S.setdefault(sym, []).append((i, c, h, l, pc, to, dp / 100.0 if dp == dp else math.nan))
    last = len(files) - 1
    rows = []
    for sym, b in S.items():
        if b[-1][0] < last - 2: continue                 # not traded in the last 3 sessions
        n = len(b)
        c = [x[1] for x in b]; to = [x[5] for x in b]
        def ret(k): return 100 * (c[-1] / c[-1 - k] - 1) if n > k and c[-1 - k] > 0 else math.nan
        def avg(xs): xs = [x for x in xs if x == x]; return sum(xs) / len(xs) if xs else math.nan
        dv = [x[5] * x[6] if x[6] == x[6] else math.nan for x in b]
        sgn = [(1 if x[1] > x[4] else -1 if x[1] < x[4] else 0) for x in b]
        def net(k): return sum(s * v for s, v in zip(sgn[-k:], dv[-k:]) if v == v)
        to60 = avg(to[-60:])
        mf = [((x[1] - x[3]) - (x[2] - x[1])) / (x[2] - x[3]) * x[5] if x[2] > x[3] else 0.0 for x in b[-20:]]
        t20 = sum(to[-20:])
        d10, d60 = avg(dv[-10:]), avg(dv[-60:])
        rows.append({
            "SYMBOL": sym, "CLOSE": round(c[-1], 2), "R1": round(ret(1), 2), "R5": round(ret(5), 2), "R20": round(ret(20), 2),
            "TO1": round(to[-1], 3), "TO20": round(avg(to[-20:]), 3),
            "DLV1": round(100 * b[-1][6], 1) if b[-1][6] == b[-1][6] else "", "DLV20": round(100 * avg([x[6] for x in b[-20:]]), 1) if avg([x[6] for x in b[-20:]]) == avg([x[6] for x in b[-20:]]) else "",
            "NET5": round(net(5), 3), "NET20": round(net(20), 3),
            "FLOW20": round(net(20) / (20 * to60), 4) if to60 > 0 else "",
            "CMF20": round(sum(mf) / t20, 4) if t20 > 0 else "",
            "DSURGE": round(d10 / d60, 3) if d60 == d60 and d60 > 0 and d10 == d10 else "",
            "N": n, "ASOF": days[b[-1][0]].isoformat()})
    for r in rows:
        for k, v in r.items():
            if isinstance(v, float) and v != v: r[k] = ""
    rows.sort(key=lambda r: r["SYMBOL"])
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, list(rows[0].keys())); w.writeheader(); w.writerows(rows)
    print("stocks:", len(rows))
    # keep only the files we still need
    keep = {os.path.basename(p) for _, p in files}
    for x in os.listdir(d):
        if x.endswith(".csv") and x not in keep: os.remove(os.path.join(d, x))

if __name__ == "__main__": main()
