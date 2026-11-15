#!/usr/bin/env python3
"""Bulk and block deals: today's NSE files appended to OUT, last 45 days kept.
usage: deals.py OUT.csv"""
import sys, csv, datetime as dt, urllib.request
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}
out = sys.argv[1]
have = {}
try:
    for r in csv.DictReader(open(out)): have[tuple(r[k] for k in ("DATE", "KIND", "SYMBOL", "CLIENT", "SIDE", "QTY"))] = r
except FileNotFoundError: pass
for kind in ("bulk", "block"):
    try:
        b = urllib.request.urlopen(urllib.request.Request("https://nsearchives.nseindia.com/content/equities/%s.csv" % kind, headers={"User-Agent": UA}), timeout=40).read().decode("utf-8", "replace")
    except Exception as e:
        print(kind, "failed", e); continue
    for r in csv.reader(b.splitlines()[1:]):
        if len(r) < 7: continue
        try:
            d, m, y = r[0].strip().split("-"); date = "%s-%02d-%s" % (y, MON[m.upper()[:3]], d.zfill(2))
            row = {"DATE": date, "KIND": kind.upper(), "SYMBOL": r[1].strip().upper(), "CLIENT": r[3].strip().replace(",", " ")[:60],
                   "SIDE": r[4].strip().upper(), "QTY": str(int(float(r[5].replace(",", "")))), "PRICE": str(round(float(r[6].replace(",", "")), 2))}
        except Exception: continue
        have[tuple(row[k] for k in ("DATE", "KIND", "SYMBOL", "CLIENT", "SIDE", "QTY"))] = row
cut = (dt.date.today() - dt.timedelta(days=45)).isoformat()
rows = sorted((r for r in have.values() if r["DATE"] >= cut), key=lambda r: (r["DATE"], r["SYMBOL"]), reverse=True)
with open(out, "w", newline="") as f:
    w = csv.DictWriter(f, ["DATE", "KIND", "SYMBOL", "CLIENT", "SIDE", "QTY", "PRICE"]); w.writeheader(); w.writerows(rows)
print("deals:", len(rows))
