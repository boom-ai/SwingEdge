#!/usr/bin/env python3
"""NSE index constituents -> data/indices.csv (INDEX,SYMBOL). Missing lists are skipped; a list with < 5 rows is ignored.
usage: indices.py OUT.csv"""
import sys, csv, time, urllib.request
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
LISTS = [  # display name, file stem(s) tried in order
    ("Nifty 50", ["nifty50list"]), ("Nifty Next 50", ["niftynext50list"]), ("Nifty 100", ["nifty100list"]),
    ("Nifty 200", ["nifty200list"]), ("Nifty 500", ["nifty500list"]),
    ("Nifty Midcap 50", ["niftymidcap50list"]), ("Nifty Midcap 100", ["niftymidcap100list"]), ("Nifty Midcap 150", ["niftymidcap150list"]),
    ("Nifty Smallcap 50", ["niftysmallcap50list"]), ("Nifty Smallcap 100", ["niftysmallcap100list"]), ("Nifty Smallcap 250", ["niftysmallcap250list"]),
    ("Nifty Microcap 250", ["niftymicrocap250_list"]), ("Nifty LargeMidcap 250", ["niftylargemidcap250list"]), ("Nifty MidSmallcap 400", ["niftymidsmallcap400list"]),
    ("Nifty Bank", ["niftybanklist"]), ("Nifty Private Bank", ["nifty_privatebanklist", "niftyprivatebanklist"]), ("Nifty PSU Bank", ["niftypsubanklist"]),
    ("Nifty Financial Services", ["niftyfinancelist"]), ("Nifty IT", ["niftyitlist"]), ("Nifty Auto", ["niftyautolist"]), ("Nifty Pharma", ["niftypharmalist"]),
    ("Nifty Healthcare", ["niftyhealthcarelist", "niftyhealthcareindexlist"]), ("Nifty FMCG", ["niftyfmcglist"]), ("Nifty Metal", ["niftymetallist"]),
    ("Nifty Realty", ["niftyrealtylist"]), ("Nifty Energy", ["niftyenergylist"]), ("Nifty Oil & Gas", ["niftyoilgaslist"]), ("Nifty Media", ["niftymedialist"]),
    ("Nifty Consumer Durables", ["niftyconsumerdurableslist"]), ("Nifty PSE", ["niftypselist"]), ("Nifty CPSE", ["niftycpselist"]),
    ("Nifty Infrastructure", ["niftyinfralist"]), ("Nifty India Defence", ["niftyindiadefence_list"]), ("Nifty Commodities", ["niftycommoditieslist"]),
    ("Nifty India Consumption", ["niftyconsumptionlist"]), ("Nifty MNC", ["niftymnclist"]), ("Nifty Services Sector", ["niftyservicelist"]),
    ("Nifty Capital Markets", ["niftycapitalmarkets_list"]), ("Nifty India Manufacturing", ["niftyindiamanufacturing_list"]), ("Nifty EV & New Age Automotive", ["niftyevnewageautomotive_list"]),
    ("Nifty Total Market", ["niftytotalmarket_list"]),
]
rows, ok, miss = [], 0, []
for name, stems in LISTS:
    got = None
    for st in stems:
        for host in ("https://nsearchives.nseindia.com/content/indices/ind_%s.csv", "https://www.niftyindices.com/IndexConstituent/ind_%s.csv"):
            try:
                b = urllib.request.urlopen(urllib.request.Request(host % st, headers={"User-Agent": UA}), timeout=30).read().decode("utf-8", "replace")
                r = list(csv.DictReader(b.splitlines()))
                syms = [x.get("Symbol", "").strip().upper() for x in r if x.get("Symbol")]
                if len(syms) >= 5: got = syms; break
            except Exception:
                pass
            time.sleep(0.3)
        if got: break
    if got:
        ok += 1; rows += [(name, s) for s in got]
    else:
        miss.append(name)
with open(sys.argv[1], "w", newline="") as f:
    w = csv.writer(f); w.writerow(["INDEX", "SYMBOL"]); w.writerows(rows)
print("indices:", ok, "rows:", len(rows), "missing:", miss)
