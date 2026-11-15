#!/usr/bin/env python3
"""Sector / industry of every NSE stock: NSE's 4-level classification (macro > sector > industry > basic industry)
from the quote API, cached in OUT; broad index-list industries fill gaps.
usage: industries.py SYMBOLS.csv OUT.csv MAX_NEW"""
import sys, csv, json, time, urllib.request, concurrent.futures as cf
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
def get(u):
    for k in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": UA, "Accept": "application/json, */*"}), timeout=40) as r: return r.read()
        except Exception:
            time.sleep(2 + 3 * k)
    return None
syms_path, out, mx = sys.argv[1], sys.argv[2], int(sys.argv[3])
syms = [r["SYMBOL"].strip().upper() for r in csv.DictReader(open(syms_path)) if r.get("BOARD", "MAIN").strip().upper() != "SME"]
have = {}
try:
    for r in csv.DictReader(open(out)): have[r["SYMBOL"]] = r
except FileNotFoundError: pass
broad = {}
for name in ("niftytotalmarket_list", "niftymicrocap250_list"):
    b = get("https://nsearchives.nseindia.com/content/indices/ind_%s.csv" % name)
    if b:
        for r in csv.DictReader(b.decode("utf-8", "replace").splitlines()): broad.setdefault(r["Symbol"].strip().upper(), r["Industry"].strip())
todo = [s for s in syms if s not in have or not have[s].get("BASIC")][:mx]
print("industries: have", len(have), "todo", len(todo))
def work(s):
    b = get("https://www.nseindia.in/api/quote-equity?symbol=" + urllib.request.quote(s))
    try:
        ii = json.loads(b).get("industryInfo") or {}
        return s, {"MACRO": ii.get("macro", ""), "SECTOR": ii.get("sector", ""), "INDUSTRY": ii.get("industry", ""), "BASIC": ii.get("basicIndustry", "")}
    except Exception:
        return s, None
got = 0
with cf.ThreadPoolExecutor(4) as ex:
    for s, r in ex.map(work, todo):
        if r and r["SECTOR"]:
            have[s] = dict(SYMBOL=s, **r); got += 1
print("fetched", got)
for s in syms:
    if s not in have and s in broad: have[s] = {"SYMBOL": s, "MACRO": "", "SECTOR": broad[s], "INDUSTRY": "", "BASIC": ""}
rows = [have[s] for s in sorted(have) if s in set(syms)]
with open(out, "w", newline="") as f:
    w = csv.DictWriter(f, ["SYMBOL", "MACRO", "SECTOR", "INDUSTRY", "BASIC"]); w.writeheader()
    for r in rows: w.writerow({k: (r.get(k) or "").replace(",", " ") for k in ["SYMBOL", "MACRO", "SECTOR", "INDUSTRY", "BASIC"]})
print("rows", len(rows), "with basic industry", sum(1 for r in rows if r.get("BASIC")))
