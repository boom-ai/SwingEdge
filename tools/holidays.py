#!/usr/bin/env python3
"""NSE trading holidays -> data/holidays.json (equity segment, weekdays only).

usage: holidays.py API_JSON OUT_JSON
  API_JSON = answer of https://www.nseindia.com/api/holiday-master?type=trading (may be missing / garbage).
  OUT_JSON = data/holidays.json; its current days are kept for every year the API answer does not cover (the committed
  fallback: 2026 from the NSE circular, 2027 fixed-date days marked provisional until NSE publishes the list).
Never fails the workflow: on a bad API answer the existing file is left as it is.
"""
import sys, json, datetime as dt

def day(s):
    for f in ("%d-%b-%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try: return dt.datetime.strptime(s.strip(), f).date()
        except Exception: pass
    return None

api_path, out_path = sys.argv[1], sys.argv[2]
try: cur = json.load(open(out_path))
except Exception: cur = {"days": []}
got = {}
try:
    a = json.load(open(api_path))
    for x in a.get("CM", []) or []:
        d = day(x.get("tradingDate", ""))
        if d and d.weekday() < 5: got[d.isoformat()] = (x.get("description") or "").strip()
except Exception as e:
    print("NSE holiday API answer not usable:", e)
if len(got) < 5:
    print("kept", out_path, "(%d days; API gave %d)" % (len(cur.get("days", [])), len(got))); sys.exit(0)
years = {k[:4] for k in got}
days = {x["date"]: x.get("name", "") for x in cur.get("days", []) if x.get("date", "")[:4] not in years}
days.update(got)
ist = dt.datetime.utcnow() + dt.timedelta(hours=5, minutes=30)
src = "NSE API holiday-master?type=trading (CM) for %s; other years: committed fallback" % ",".join(sorted(years))
out = {"source": src, "updatedAt": ist.strftime("%Y-%m-%d %H:%M IST"), "count": len(days), "days": [{"date": k, "name": days[k]} for k in sorted(days)]}
json.dump(out, open(out_path, "w"), indent=1); open(out_path, "a").write("\n")
print("holidays:", len(days), "years from API:", sorted(years))
