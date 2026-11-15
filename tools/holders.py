#!/usr/bin/env python3
"""Latest vs previous quarter shareholding for every stock, from the parsed filings cache.
usage: holders.py CACHE.csv OUT.csv"""
import sys, csv
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r["QDATE"][5:] in ("03-31", "06-30", "09-30", "12-31")]
by = {}
for r in rows:
    k = (r["SYMBOL"], r["QDATE"])
    if k not in by or r["SUBMIT"] > by[k]["SUBMIT"]: by[k] = r      # latest revision
per = {}
for (s, q), r in by.items(): per.setdefault(s, []).append(r)
F = ["PROMOTER", "FII", "MF", "DII", "RETAIL"]
out = []
for s, lst in per.items():
    lst.sort(key=lambda r: r["QDATE"])
    a = lst[-1]; b = lst[-2] if len(lst) > 1 else None
    row = {"SYMBOL": s, "QDATE": a["QDATE"], "SUBMIT": a["SUBMIT"], "PQDATE": b["QDATE"] if b else ""}
    for f in F:
        row[f] = a.get(f, ""); row["P" + f] = b.get(f, "") if b else ""
    out.append(row)
out.sort(key=lambda r: r["SYMBOL"])
cols = ["SYMBOL", "QDATE", "SUBMIT", "PQDATE"] + F + ["P" + f for f in F]
with open(sys.argv[2], "w", newline="") as f:
    w = csv.DictWriter(f, cols); w.writeheader(); w.writerows(out)
print("holders:", len(out), "with FII data:", sum(1 for r in out if r["FII"] != "" and r["PFII"] != ""))
