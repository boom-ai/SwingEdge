#!/usr/bin/env python3
"""Shareholding patterns from NSE: promoter % for every filing in a date window (master API), and
FII / mutual-fund / other-domestic-institution / retail % from each filing's XBRL.

usage: shp.py master FROM TO OUT.csv            (dates dd-mm-yyyy; promoter % only, fast)
       shp.py xbrl MASTER.csv KNOWN.csv OUT.csv MAX   (adds category %s for filings not in KNOWN)
"""
import sys, json, csv, re, time, urllib.request, concurrent.futures as cf

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
API = "https://www.nseindia.in/api/corporate-share-holdings-master?index=equities&from_date=%s&to_date=%s"

def get(url, tries=3):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:
            if k == tries - 1: return None
            time.sleep(2 + 3 * k)

MON = {m: i + 1 for i, m in enumerate("JAN FEB MAR APR MAY JUN JUL AUG SEP OCT NOV DEC".split())}
def iso(d):  # 30-SEP-2026 -> 2026-09-30
    try:
        dd, mm, yy = d.strip().split()[0].split("-")
        return "%s-%02d-%s" % (yy, MON[mm.upper()[:3]], dd.zfill(2))
    except Exception:
        return ""

FIELDS = ["SYMBOL", "QDATE", "SUBMIT", "PROMOTER", "FII", "MF", "DII", "RETAIL", "XBRL"]

def master(fr, to, out):
    raw = get(API % (fr, to))
    rows = json.loads(raw) if raw else []
    best = {}
    for r in rows:
        sym = (r.get("symbol") or "").strip().upper()
        q = iso(r.get("date") or "")
        if not sym or not q: continue
        try: pr = float(r.get("pr_and_prgrp") or "nan")
        except ValueError: continue
        sub = iso(r.get("submissionDate") or r.get("broadcastDate") or "")
        k = (sym, q)
        # keep the latest submission (a revision replaces the original)
        if k not in best or sub >= best[k]["SUBMIT"]:
            best[k] = {"SYMBOL": sym, "QDATE": q, "SUBMIT": sub, "PROMOTER": round(pr, 2), "FII": "", "MF": "", "DII": "", "RETAIL": "", "XBRL": r.get("xbrl") or ""}
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, FIELDS); w.writeheader()
        for k in sorted(best): w.writerow(best[k])
    print("master", fr, to, len(rows), "rows ->", len(best), "filings")

PCT = re.compile(rb'<in-bse-shp:ShareholdingAsAPercentageOfTotalNumberOfShares[^>]*contextRef="([A-Za-z]+)_ContextI"[^>]*>([^<]*)<')
def parse_xbrl(b):
    v = {}
    for c, x in PCT.findall(b):
        try: v[c.decode()] = float(x)
        except ValueError: pass
    if "ShareholdingOfPromoterAndPromoterGroup" not in v and "InstitutionsForeign" not in v: return None
    pct = lambda k: round(100 * v.get(k, 0.0), 2)
    return {"FII": pct("InstitutionsForeign"), "MF": pct("MutualFundsOrUTI"),
            "DII": round(pct("InstitutionsDomestic") - pct("MutualFundsOrUTI"), 2),
            "RETAIL": pct("ResidentIndividualShareholdersHoldingNominalShareCapitalUpToRsTwoLakh")}

def xbrl(mpath, known_path, out, mx):
    rows = list(csv.DictReader(open(mpath)))
    known = {}
    try:
        for r in csv.DictReader(open(known_path)):
            if r.get("FII", "") != "": known[(r["SYMBOL"], r["QDATE"], r["SUBMIT"])] = r
    except FileNotFoundError: pass
    todo = [r for r in rows if (r["SYMBOL"], r["QDATE"], r["SUBMIT"]) not in known and r["XBRL"].startswith("http")]
    todo = todo[:mx]
    print("xbrl: %d filings, %d known, fetching %d" % (len(rows), len(known), len(todo)))
    def work(r):
        b = get(r["XBRL"].replace("nsearchives.nseindia.com", "nsearchives.nseindia.in"))
        return r, (parse_xbrl(b) if b else None)
    def save():
        for r in rows:
            k = (r["SYMBOL"], r["QDATE"], r["SUBMIT"])
            if k in known: r.update({x: known[k][x] for x in ("FII", "MF", "DII", "RETAIL")})
        with open(out + ".tmp", "w", newline="") as f:
            w = csv.DictWriter(f, FIELDS); w.writeheader(); w.writerows(rows)
        import os; os.replace(out + ".tmp", out)
    done = 0
    t0 = time.time()
    with cf.ThreadPoolExecutor(8) as ex:
        for r, p in ex.map(work, todo):
            done += 1
            if p: r.update(p); known[(r["SYMBOL"], r["QDATE"], r["SUBMIT"])] = r
            if done % 500 == 0: print(" ", done, "in %.0fs" % (time.time() - t0), flush=True)
            if done % 2000 == 0: save()
    save()
    print("xbrl parsed:", sum(1 for r in rows if r["FII"] != ""), "of", len(rows))

if __name__ == "__main__":
    if sys.argv[1] == "master": master(sys.argv[2], sys.argv[3], sys.argv[4])
    else: xbrl(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]))
