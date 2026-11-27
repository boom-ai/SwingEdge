#!/usr/bin/env python3
"""Markets screen data from NSE's JSON API (cookie session):
  nsecal.py fiidii OUT.csv   -> FII/DII cash-market flows, one row per day appended (DATE,FII_BUY,FII_SELL,FII_NET,DII_BUY,DII_SELL,DII_NET; Rs crore)
  nsecal.py events OUT.csv   -> board meetings / results + corporate actions, today-7 .. today+45 (DATE,SYMBOL,KIND,DETAIL)
Never fails the job: on any error the old file is kept."""
import sys, csv, json, time, datetime as dt, urllib.request, http.cookiejar
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}
jar = http.cookiejar.CookieJar()
op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
HDR = {"User-Agent": UA, "Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.9", "Referer": "https://www.nseindia.com/"}

def warm():
    for u in ("https://www.nseindia.com/", "https://www.nseindia.com/market-data/live-equity-market"):
        try: op.open(urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "text/html"}), timeout=30).read(); time.sleep(1)
        except Exception as e: print("warm", u, e)

def get(path):
    for host in ("https://www.nseindia.com", "https://www.nseindia.in"):
        for k in range(3):
            try:
                b = op.open(urllib.request.Request(host + path, headers=HDR), timeout=40).read().decode("utf-8", "replace")
                return json.loads(b)
            except Exception as e:
                print("get", host + path, e); time.sleep(2 + 3 * k)
    return None

def day(s):
    s = (s or "").strip()
    if len(s) >= 10 and s[4] == "-": s = "%s-%s-%s" % (s[8:10], s[5:7], s[:4])
    try:
        d, m, y = s.split("-")
        return "%s-%02d-%02d" % (y, MON[m.upper()[:3]], int(d)) if not m.isdigit() else "%s-%s-%02d" % (y, m.zfill(2), int(d))
    except Exception:
        try: return dt.date.fromisoformat(s[:10]).isoformat()
        except Exception: return ""

def num(x):
    try: return str(round(float(str(x).replace(",", "")), 2))
    except Exception: return ""

def fiidii(out):
    cols = ["DATE", "FII_BUY", "FII_SELL", "FII_NET", "DII_BUY", "DII_SELL", "DII_NET"]
    have = {}
    try:
        for r in csv.DictReader(open(out)): have[r["DATE"]] = r
    except FileNotFoundError: pass
    warm(); j = get("/api/fiidiiTradeReact")
    if isinstance(j, list):
        row = {}
        for r in j:
            cat = str(r.get("category", "")).upper(); d = day(r.get("date"))
            if not d: continue
            row["DATE"] = d
            p = "FII" if "FII" in cat or "FPI" in cat else "DII" if cat.startswith("DII") else None
            if p: row[p + "_BUY"], row[p + "_SELL"], row[p + "_NET"] = num(r.get("buyValue")), num(r.get("sellValue")), num(r.get("netValue"))
        if row.get("DATE") and (row.get("FII_NET") or row.get("DII_NET")): have[row["DATE"]] = {c: row.get(c, "") for c in cols}
    rows = sorted(have.values(), key=lambda r: r["DATE"])[-400:]
    if not rows: print("fiidii: nothing"); return
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, cols); w.writeheader(); w.writerows(rows)
    print("fiidii:", len(rows), "days, latest", rows[-1]["DATE"])

def kind_of(subject):
    s = subject.upper()
    for k in ("DIVIDEND", "BONUS", "SPLIT", "RIGHTS", "BUYBACK", "BUY BACK"):
        if k in s: return k.replace(" ", "")
    return "ACTION"

def events(out):
    today = dt.date.today(); lo, hi = today - dt.timedelta(days=7), today + dt.timedelta(days=45)
    f, t = lo.strftime("%d-%m-%Y"), hi.strftime("%d-%m-%Y")
    warm(); rows = {}
    bm = get("/api/event-calendar?index=equities&from_date=%s&to_date=%s" % (f, t))
    for r in bm if isinstance(bm, list) else []:
        d, sym = day(r.get("date")), str(r.get("symbol", "")).strip().upper()
        purpose = " ".join(str(r.get("purpose") or r.get("bm_purpose") or "").split())
        if not d or not sym: continue
        k = "RESULTS" if "RESULT" in purpose.upper() else "BOARD"
        rows[(d, sym, k)] = {"DATE": d, "SYMBOL": sym, "KIND": k, "DETAIL": purpose[:120] or "Board meeting"}
    ca = get("/api/corporates-corporateActions?index=equities&from_date=%s&to_date=%s" % (f, t))
    for r in ca if isinstance(ca, list) else []:
        d, sym, sub = day(r.get("exDate")), str(r.get("symbol", "")).strip().upper(), " ".join(str(r.get("subject", "")).split())
        if not d or not sym: continue
        k = kind_of(sub); rows[(d, sym, k)] = {"DATE": d, "SYMBOL": sym, "KIND": k, "DETAIL": ("Ex-date: " + sub)[:120]}
    if not rows: print("events: nothing fetched, old file kept"); return
    out_rows = sorted((r for r in rows.values() if lo.isoformat() <= r["DATE"] <= hi.isoformat()), key=lambda r: (r["DATE"], r["SYMBOL"]))
    with open(out, "w", newline="") as fh:
        w = csv.DictWriter(fh, ["DATE", "SYMBOL", "KIND", "DETAIL"]); w.writeheader(); w.writerows(out_rows)
    print("events:", len(out_rows))

if __name__ == "__main__":
    try: {"fiidii": fiidii, "events": events}[sys.argv[1]](sys.argv[2])
    except Exception as e: print("nsecal failed:", e)
