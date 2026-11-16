#!/usr/bin/env python3
"""Mutual-fund data for the Funds tab (stdlib only).

usage: funds.py CACHE_DIR OUT_DIR

Writes OUT_DIR/funds.csv, fund_holdings.csv, fund_stocks.csv.

funds.csv  one row per open-ended DIRECT / GROWTH scheme (AMFI NAVAll list + categories, mfapi.in NAV history,
           AMFI scheme-wise average AUM)
  CODE,NAME,AMC,CATEGORY,SUBCAT,NAV,NAVDATE,AUM_CR,R1M,R3M,R6M,R1Y,R3Y,R5Y,R10Y,SINCE,VOL3Y,MDD3Y,SHARPE3Y,
  SORTINO3Y,ROLL3Y_MED,ROLL3Y_MIN,ROLL3Y_BEAT,AGE_Y
  returns in % (R1Y and shorter absolute; R3Y/R5Y/R10Y/SINCE CAGR); VOL3Y annualised stdev of weekly returns;
  MDD3Y worst peak-to-trough over 3y (negative %); Sharpe/Sortino use 6.5% risk-free on weekly returns;
  ROLL3Y_* over weekly-sampled rolling 3y CAGRs; ROLL3Y_BEAT = % of those windows the fund beat the median of
  its SUBCAT peers for the same window (empty for "Index Funds": peers track different indices).
fund_holdings.csv  CODE,ASOF,STOCK,ISIN,PCT   top 15 equity holdings per equity/hybrid fund (Groww public JSON)
fund_stocks.csv    STOCK,ISIN,FUNDS,AVGPCT,ADDED   across the full equity portfolios (>=3 funds)
  STOCK is the NSE symbol when the company could be matched to NSE's EQUITY_L list (which also supplies ISIN).
Never raises on partial data; unavailable pieces are left empty and logged.
"""
import sys, os, re, io, csv, json, gzip, math, time, bisect, random, datetime as dt
import statistics as st
import urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
T0 = time.time()
DEADLINE = 105 * 60          # stop starting new network work after this many seconds
RF = 0.065
COLS = ("CODE,NAME,AMC,CATEGORY,SUBCAT,NAV,NAVDATE,AUM_CR,R1M,R3M,R6M,R1Y,R3Y,R5Y,R10Y,SINCE,VOL3Y,MDD3Y,"
        "SHARPE3Y,SORTINO3Y,ROLL3Y_MED,ROLL3Y_MIN,ROLL3Y_BEAT,AGE_Y").split(",")


def log(*a):
    print("[%5ds]" % (time.time() - T0), *a, flush=True)


def http(url, tries=4, timeout=60, referer=None, binary=False):
    h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    if referer: h["Referer"] = referer
    last = None
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
                b = r.read()
            return b if binary else b.decode("utf-8", "replace")
        except Exception as e:
            last = e
            code = getattr(e, "code", 0)
            if code in (400, 404): break
            time.sleep(1.5 * (i + 1) + random.random())
    raise last


def jget(url, **kw):
    return json.loads(http(url, **kw))


# ---------------------------------------------------------------- AMFI list
SUB_ALIAS = {
    "elss- tax saver fund": "ELSS", "elss": "ELSS", "elss - tax saver fund": "ELSS",
    "sectoral fund": "Sectoral/Thematic", "thematic fund": "Sectoral/Thematic", "sectoral/ thematic": "Sectoral/Thematic",
    "sectoral/thematic": "Sectoral/Thematic",
    "balanced advantage fund/ dynamic asset allocation": "Dynamic Asset Allocation or Balanced Advantage",
    "dynamic asset allocation or balanced advantage": "Dynamic Asset Allocation or Balanced Advantage",
    "equity savings fund": "Equity Savings", "multi asset allocation fund": "Multi Asset Allocation",
    "short term fund": "Short Duration Fund", "medium term fund": "Medium Duration Fund",
    "medium to long term fund": "Medium to Long Duration Fund", "long term fund": "Long Duration Fund",
    "ultra short term fund": "Ultra Short Duration Fund", "ultra short to short term fund": "Ultra Short Duration Fund",
    "dynamic term fund": "Dynamic Bond", "dynamic bond fund": "Dynamic Bond",
    "floating interest rates fund": "Floater Fund", "banking and psu fund": "Banking and PSU Debt Fund",
    "foF overseas": "FoF Overseas", "fund of funds investing overseas": "FoF Overseas", "fof overseas": "FoF Overseas",
    "children\u2019s fund": "Children's Fund", "childrens' fund": "Children's Fund", "children's fund": "Children's Fund",
}


def canon_sub(s):
    return SUB_ALIAS.get(s.strip().lower(), s.strip())

def parse_navall(txt):
    out = []
    amc = ""; cat = None
    for ln in txt.splitlines():
        ln = ln.strip()
        if not ln or ln.startswith("Scheme Code"): continue
        if ";" not in ln:
            m = re.match(r"^(Open Ended|Close Ended|Interval Fund)[^(]*\((.*)\)\s*$", ln)
            if m:
                cat = (m.group(1), m.group(2).strip())
            else:
                amc = re.sub(r"\s*Mutual Fund\s*$", "", ln, flags=re.I).strip()
            continue
        f = [x.strip() for x in ln.split(";")]
        if len(f) < 6 or not f[0].isdigit() or cat is None or cat[0] != "Open Ended": continue
        if len(f) >= 8:
            code, name, plan, opt, nav, date = f[0], f[3], f[4], f[5], f[6], f[7]
        else:
            code, name, nav, date = f[0], f[3], f[4], f[5]
            plan = "Direct Plan" if "direct" in name.lower() else "Regular"
            opt = "Growth Option" if "growth" in name.lower() else "IDCW"
        if "direct" not in plan.lower() or not opt.lower().startswith("growth"): continue
        nl = name.lower()
        if re.search(r"idcw|dividend|bonus|segregated|side pocket|\bfmp\b|fixed (maturity|term)|\betf\b", nl): continue
        try:
            nv = float(nav); d = dt.datetime.strptime(date, "%d-%b-%Y").date()
        except Exception:
            continue
        left, _, right = cat[1].partition(" - ")
        full = cat[1].lower()
        if "etf" in full or "exchange traded" in full: continue
        if re.search(r"(fof|fund of funds?)[^a-z]*(scheme)?[^a-z]*\(?domestic", full): continue
        ll = left.lower()
        if "index" in ll:
            broad, sub = "Index/ETF-like", "Index Funds"
        else:
            sub = (right.strip() or left.strip())
            if "equity" in ll: broad = "Equity"
            elif "debt" in ll: broad = "Debt"
            elif "hybrid" in ll: broad = "Hybrid"
            elif "solution" in ll or "children" in ll or "retirement" in ll: broad = "Solution Oriented"
            else: broad = "Other"
            sub = canon_sub(sub)
            if sub.lower() == "index funds": broad = "Index/ETF-like"
        out.append(dict(CODE=code, NAME=name, AMC=amc, CATEGORY=broad, SUBCAT=sub, NAV=nv, NAVD=d))
    return out


# ---------------------------------------------------------------- NAV history cache
def ld_hist(path):
    try:
        with gzip.open(path, "rt") as f: return json.load(f)
    except Exception:
        return None


def sv_hist(path, o):
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with gzip.open(path + ".tmp", "wt") as f: json.dump(o, f, separators=(",", ":"))
        os.replace(path + ".tmp", path)
    except Exception as e:
        log("cache write fail", path, e)


def fetch_hist(code):
    j = jget("https://api.mfapi.in/mf/" + code, tries=4, timeout=90)
    d = {}
    for r in j.get("data") or []:
        try:
            x = dt.datetime.strptime(r["date"], "%d-%m-%Y").date().toordinal()
            v = float(r["nav"])
            if v > 0: d[x] = v
        except Exception:
            pass
    ks = sorted(d)
    return ks, [d[k] for k in ks]


def get_history(fund, cdir, today):
    code = fund["CODE"]; p = os.path.join(cdir, "nav", code + ".json.gz")
    c = ld_hist(p)
    last = fund["NAVD"].toordinal()
    need = True
    if c and c.get("d"):
        age = today - c.get("f", 0)
        gap = last - c["d"][-1]
        if age <= 6 + int(code) % 3 and 0 <= gap <= 4:
            need = False
            if gap > 0:  # append today's point from NAVAll
                c["d"].append(last); c["n"].append(fund["NAV"])
                sv_hist(p, c)
        elif gap < 0: need = False
    if not need: return c["d"], c["n"], "cache"
    if time.time() - T0 > DEADLINE:
        return (c["d"], c["n"], "stale") if c and c.get("d") else (None, None, "skip")
    try:
        ks, ns = fetch_hist(code)
        if len(ks) < 5: raise ValueError("empty history")
        sv_hist(p, {"f": today, "d": ks, "n": ns})
        return ks, ns, "fetch"
    except Exception as e:
        if c and c.get("d"): return c["d"], c["n"], "stale"
        return None, None, "fail"


# ---------------------------------------------------------------- metrics
def addm(d, m):
    y = d.year + (d.month - 1 + m) // 12; mo = (d.month - 1 + m) % 12 + 1
    import calendar
    return dt.date(y, mo, min(d.day, calendar.monthrange(y, mo)[1]))


def nav_on(ds, ns, t, tol=12):
    i = bisect.bisect_right(ds, t) - 1
    if i < 0 or ds[i] < t - tol: return None
    return ns[i]


def f2(x):
    return "" if x is None or (isinstance(x, float) and (math.isnan(x) or math.isinf(x))) else "%.2f" % x


def metrics(ds, ns, last_ord):
    m = {}
    last = dt.date.fromordinal(ds[-1]); nv = ns[-1]
    for key, mo in (("R1M", 1), ("R3M", 3), ("R6M", 6), ("R1Y", 12)):
        p = nav_on(ds, ns, addm(last, -mo).toordinal())
        m[key] = (nv / p - 1) * 100 if p else None
    for key, yrs in (("R3Y", 3), ("R5Y", 5), ("R10Y", 10)):
        p = nav_on(ds, ns, addm(last, -12 * yrs).toordinal())
        m[key] = ((nv / p) ** (1 / yrs) - 1) * 100 if p else None
    age = (ds[-1] - ds[0]) / 365.25
    m["AGE_Y"] = age
    m["SINCE"] = ((nv / ns[0]) ** (1 / age) - 1) * 100 if age >= 1 else None
    # 3y risk stats
    t3 = addm(last, -36).toordinal()
    for k in ("VOL3Y", "MDD3Y", "SHARPE3Y", "SORTINO3Y"): m[k] = None
    if nav_on(ds, ns, t3) is not None:
        grid = [ds[-1] - 7 * k for k in range(156, -1, -1)]
        px = [nav_on(ds, ns, t) for t in grid]
        if all(px):
            r = [px[i + 1] / px[i] - 1 for i in range(len(px) - 1)]
            sd = st.pstdev(r) if len(r) > 2 else 0
            mu = st.fmean(r)
            m["VOL3Y"] = sd * math.sqrt(52) * 100
            rfw = RF / 52
            dd = math.sqrt(sum(min(0, x - rfw) ** 2 for x in r) / len(r))
            if sd > 1e-9: m["SHARPE3Y"] = (mu * 52 - RF) / (sd * math.sqrt(52))
            if dd > 1e-9: m["SORTINO3Y"] = (mu * 52 - RF) / (dd * math.sqrt(52))
        i0 = bisect.bisect_left(ds, t3)
        pk = 0; mdd = 0
        for v in ns[i0:]:
            if v > pk: pk = v
            elif pk: mdd = min(mdd, v / pk - 1)
        m["MDD3Y"] = mdd * 100
    return m


def rolling(ds, ns, anchor):
    """3y CAGR (%) per weekly grid point t = anchor - 7k; None where unavailable."""
    out = []
    first = ds[0]
    kmax = (anchor - first) // 7
    for k in range(0, kmax + 1):
        t = anchor - 7 * k
        t0 = t - 1096
        if t0 < first or t > ds[-1] + 12:
            out.append(None); continue
        a = nav_on(ds, ns, t0); b = nav_on(ds, ns, t)
        out.append(((b / a) ** (365.25 / 1096) - 1) * 100 if a and b else None)
    return out


# ---------------------------------------------------------------- AUM
def norm_scheme(n):
    n = n.lower().replace("&", " and ")
    n = re.sub(r"\b(direct|regular|plan|growth|idcw|dividend|payout|reinvestment|option|bonus|daily|weekly|"
               r"fortnightly|monthly|quarterly|half|yearly|annual|annually|institutional|retail|plus|"
               r"transfer|of|income|distribution|cum|capital|withdrawal)\b", " ", n)
    return re.sub(r"[^a-z0-9]+", " ", n).strip()


def load_aum():
    """AMFI scheme-wise average AUM (rupees lakh in source) -> {amfi_code: scheme total in crore}, label"""
    A = "https://www.amfiindia.com"; R = A + "/aum-data/average-aum"
    fy = jget(A + "/api/average-aum-fundwise", referer=R)["data"]
    for y in fy[:2]:
        per = jget(A + "/api/average-aum-fundwise?fyId=%d" % y["id"], referer=R)["data"]
        for p in per["periods"][:2]:
            try:
                j = jget(A + "/api/average-aum-schemewise?strType=Categorywise&fyId=%d&periodId=%d&MF_ID=0" % (y["id"], p["id"]),
                         referer=R, timeout=120)
                rows = j.get("data") or []
                if len(rows) > 50:
                    return rows, p["period"] + " " + y["financial_year"]
            except Exception as e:
                log("aum period fail", y["id"], p["id"], e)
    raise RuntimeError("no AUM period")


def aum_map(rows):
    tot = {}; by_code = {}
    for c in rows:
        for s in c.get("schemes") or []:
            a = s.get("AverageAumForTheMonth") or {}
            try:
                v = sum(float(x or 0) for x in a.values())
            except Exception:
                continue
            k = (c.get("Mfname"), c.get("SchemeCat_Desc"), norm_scheme(s.get("SchemeNAVName", "")))
            tot[k] = tot.get(k, 0) + v
            by_code[str(s.get("AMFI_Code"))] = k
    return {code: tot[k] / 100.0 for code, k in by_code.items()}   # lakh -> crore


# ---------------------------------------------------------------- holdings (Groww public JSON)
def clean_q(name):
    q = re.sub(r"\b(direct plan|direct|growth option|growth|plan|option)\b", " ", name, flags=re.I)
    return re.sub(r"[\s\-]+", " ", q).strip()


def groww_find(code, name):
    base = clean_q(name)
    for q in (base + " Direct Growth", base):
        j = jget("https://groww.in/v1/api/search/v1/entity?app=false&entity_type=scheme&page=0&size=8&q="
                 + urllib.parse.quote(q), tries=3, timeout=30)
        for c in j.get("content") or []:
            if str(c.get("scheme_code")) == code and c.get("search_id"): return c["search_id"]
    return None


ISIN_RE = re.compile(r"^IN[A-Z0-9]{10}$")


def groww_holdings(slug):
    j = jget("https://groww.in/v1/api/data/mf/web/v1/scheme/search/" + urllib.parse.quote(slug), tries=3, timeout=40)
    out = []; asof = None
    for r in j.get("holdings") or []:
        try:
            if str(r[3]).upper() != "EQUITY": continue
            pct = float(r[8])
            isin = next((x for x in r if isinstance(x, str) and ISIN_RE.match(x)), "")
            out.append([str(r[2]).strip(), isin, pct])
            asof = asof or str(r[1])[:10]
        except Exception:
            pass
    return asof, out, j.get("aum")


def get_holdings(fund, cdir, today):
    code = fund["CODE"]; p = os.path.join(cdir, "hold", code + ".json")
    c = None
    try: c = json.load(open(p))
    except Exception: pass
    if c and c.get("slug") and today - c.get("f", 0) < 3 + int(code) % 3 and c.get("h"):
        return c
    if time.time() - T0 > DEADLINE: return c
    try:
        slug = (c or {}).get("slug") or groww_find(code, fund["NAME"])
        if not slug:
            return c if c else {"slug": None, "f": today, "h": []}
        asof, h, aum = groww_holdings(slug)
        n = {"slug": slug, "f": today, "asof": asof, "h": h, "aum": aum}
        if c and c.get("asof") and asof and c["asof"][:7] != asof[:7] and c.get("h"):
            n["prev_asof"], n["prev_h"] = c["asof"], c["h"]
        elif c and c.get("prev_h"):
            n["prev_asof"], n["prev_h"] = c.get("prev_asof"), c["prev_h"]
        os.makedirs(os.path.dirname(p), exist_ok=True)
        json.dump(n, open(p, "w"), separators=(",", ":"))
        return n
    except Exception as e:
        return c


def nname(n):
    n = n.lower().replace("&", " and ")
    n = re.sub(r"[^a-z0-9 ]+", " ", n)
    n = re.sub(r"\b(the|limited|ltd|company|co|corporation|corp|eq|new fv.*)\b", " ", n)
    return re.sub(r"\s+", " ", n).strip()


def load_nse():
    t = http("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv", tries=3, timeout=40)
    by_name = {}; by_isin = {}
    for r in csv.DictReader(io.StringIO(t)):
        r = {k.strip(): (v or "").strip() for k, v in r.items() if k}
        isin = r.get("ISIN NUMBER", ""); sym = r.get("SYMBOL", "")
        if isin and sym:
            by_name.setdefault(nname(r.get("NAME OF COMPANY", "")), (sym, isin)); by_isin[isin] = sym
    return by_name, by_isin


# ---------------------------------------------------------------- main
def main():
    cdir, odir = sys.argv[1], sys.argv[2]
    os.makedirs(cdir, exist_ok=True); os.makedirs(odir, exist_ok=True)
    today = dt.datetime.utcnow().date().toordinal()
    try:
        funds = parse_navall(http("https://www.amfiindia.com/spages/NAVAll.txt", tries=5, timeout=120))
    except Exception as e:
        log("NAVAll failed, leaving data untouched:", e); return
    log("NAVAll direct-growth open-ended schemes:", len(funds))
    funds = [f for f in funds if f["NAVD"].toordinal() >= today - 10]
    log("with NAV in last 10 days:", len(funds))

    # AUM
    aum = {}
    try:
        rows, label = load_aum()
        aum = aum_map(rows); log("AUM period:", label, "codes:", len(aum))
    except Exception as e:
        log("AUM unavailable:", e)

    # histories
    stat = {}
    def work(f):
        ds, ns, s = get_history(f, cdir, today); return f, ds, ns, s
    kept = []
    with ThreadPoolExecutor(8) as ex:
        for i, (f, ds, ns, s) in enumerate(ex.map(work, funds)):
            stat[s] = stat.get(s, 0) + 1
            if ds and len(ds) > 20 and ds[-1] - ds[0] >= 360:
                f["ds"], f["ns"] = ds, ns; kept.append(f)
            if (i + 1) % 300 == 0: log("histories", i + 1, stat)
    log("histories done", stat, "usable:", len(kept))
    if len(kept) < 300:
        log("too few funds, not overwriting funds.csv"); return

    anchor = max(f["ds"][-1] for f in kept)
    for f in kept:
        m = metrics(f["ds"], f["ns"], anchor)
        f.update(m)
        f["roll"] = rolling(f["ds"], f["ns"], anchor) if m["AGE_Y"] >= 3.5 else None
    # peer medians per SUBCAT per window
    peers = {}
    for f in kept:
        if f["roll"] and f["SUBCAT"].lower() != "index funds": peers.setdefault(f["SUBCAT"], []).append(f)
    med = {}
    for sub, fl in peers.items():
        if len(fl) < 3: continue
        K = max(len(x["roll"]) for x in fl)
        arr = []
        for k in range(K):
            v = [x["roll"][k] for x in fl if k < len(x["roll"]) and x["roll"][k] is not None]
            arr.append(st.median(v) if len(v) >= 3 else None)
        med[sub] = arr
    for f in kept:
        f["ROLL3Y_MED"] = f["ROLL3Y_MIN"] = f["ROLL3Y_BEAT"] = None
        r = f.get("roll")
        if not r: continue
        v = [x for x in r if x is not None]
        if len(v) < 26: continue
        f["ROLL3Y_MED"] = st.median(v); f["ROLL3Y_MIN"] = min(v)
        arr = med.get(f["SUBCAT"])
        if arr:
            n = b = 0
            for k, x in enumerate(r):
                if x is not None and k < len(arr) and arr[k] is not None:
                    n += 1; b += x > arr[k]
            if n >= 26: f["ROLL3Y_BEAT"] = 100.0 * b / n
    for f in kept:
        a = aum.get(f["CODE"])
        f["AUM_CR"] = a if a else None
        f["NAVDATE"] = f["NAVD"].isoformat()

    kept.sort(key=lambda f: (f["CATEGORY"], f["SUBCAT"], f["NAME"]))
    with open(os.path.join(odir, "funds.csv.tmp"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(COLS)
        for f in kept:
            w.writerow([f["CODE"], f["NAME"], f["AMC"], f["CATEGORY"], f["SUBCAT"], "%.4f" % f["NAV"], f["NAVDATE"]]
                       + [f2(f.get(k)) for k in COLS[7:]])
    os.replace(os.path.join(odir, "funds.csv.tmp"), os.path.join(odir, "funds.csv"))
    log("funds.csv rows:", len(kept), "with AUM:", sum(1 for f in kept if f["AUM_CR"]),
        "with ROLL3Y_BEAT:", sum(1 for f in kept if f["ROLL3Y_BEAT"] is not None))

    # holdings
    try:
        do_holdings(kept, cdir, odir, today)
    except Exception as e:
        log("holdings stage crashed:", repr(e))
        for fn, hdr in (("fund_holdings.csv", "CODE,ASOF,STOCK,ISIN,PCT"), ("fund_stocks.csv", "STOCK,ISIN,FUNDS,AVGPCT,ADDED")):
            p = os.path.join(odir, fn)
            if not os.path.exists(p): open(p, "w").write(hdr + "\n")


def do_holdings(kept, cdir, odir, today):
    targets = [f for f in kept if f["CATEGORY"] in ("Equity", "Hybrid")]
    log("holdings targets:", len(targets))
    res = {}
    with ThreadPoolExecutor(6) as ex:
        for i, (f, c) in enumerate(zip(targets, ex.map(lambda f: get_holdings(f, cdir, today), targets))):
            res[f["CODE"]] = c
            if (i + 1) % 200 == 0: log("holdings", i + 1)
    ok = {k: v for k, v in res.items() if v and v.get("h")}
    log("holdings found for", len(ok), "of", len(targets), "funds")
    # AUM sanity (AMFI vs Groww)
    rat = []
    fm = {f["CODE"]: f for f in kept}
    for k, v in ok.items():
        a = fm[k].get("AUM_CR")
        if a and v.get("aum"): rat.append(a / float(v["aum"]))
    if rat: log("AUM AMFI/Groww ratio median %.3f over %d funds" % (st.median(rat), len(rat)))
    hdr_h = "CODE,ASOF,STOCK,ISIN,PCT"; hdr_s = "STOCK,ISIN,FUNDS,AVGPCT,ADDED"
    if len(ok) < 50:
        log("holdings source unusable; writing headers only")
        for fn, h in (("fund_holdings.csv", hdr_h), ("fund_stocks.csv", hdr_s)):
            p = os.path.join(odir, fn)
            if not os.path.exists(p): open(p, "w").write(h + "\n")
        return
    try:
        by_name, by_isin = load_nse(); log("NSE EQUITY_L names:", len(by_name))
    except Exception as e:
        by_name, by_isin = {}, {}; log("NSE list unavailable:", e)

    import difflib
    alias = {"zomato": "eternal", "shriram transport finance": "shriram finance",
             "ge t and d india": "ge vernova t and d india", "jindal steel and power": "jindal steel",
             "l and t finance holdings": "l and t finance", "gmr airports infrastructure": "gmr airports",
             "adani transmission": "adani energy solutions", "iifl wealth management": "360 one wam",
             "pvr": "pvr inox", "affle india": "affle 3i"}
    keys = list(by_name)
    memo = {}
    miss = {}

    def ident(name, isin):
        if isin and isin in by_isin: return by_isin[isin], isin
        k = nname(name)
        if k in memo: return memo[k]
        h = by_name.get(k) or by_name.get(alias.get(k, "\0"))
        if not h and keys:
            m = difflib.get_close_matches(k, keys, n=1, cutoff=0.9)
            if m: h = by_name[m[0]]
        memo[k] = h or (name, isin)
        return memo[k]

    rows = []; agg = {}; added = {}; anyprev = False; unm = 0; tot = 0
    asofs = {}
    for code in sorted(ok):
        c = ok[code]; h = sorted(c["h"], key=lambda x: -x[2])
        asof = c.get("asof") or ""; asofs[asof[:7]] = asofs.get(asof[:7], 0) + 1
        ids = [ident(n, i) + (p,) for n, i, p in h]
        for sym, isin, p in ids[:15]:
            rows.append([code, asof, sym, isin, "%.2f" % p])
        prev = None
        if c.get("prev_h"):
            prev = {ident(n, i)[0] for n, i, p in c["prev_h"]}; anyprev = True
        seen = set()
        for sym, isin, p in ids:
            tot += 1; unm += (not isin)
            if not isin: miss[sym] = miss.get(sym, 0) + 1
            if sym in seen: continue
            seen.add(sym)
            a = agg.setdefault(sym, [isin, 0, 0.0]); a[1] += 1; a[2] += p
            if not a[0] and isin: a[0] = isin
            if prev is not None and sym not in prev: added[sym] = added.get(sym, 0) + 1
    log("holding rows:", len(rows), "ASOF months:", asofs, "unmatched ISIN %.1f%%" % (100.0 * unm / max(tot, 1)), "prev month available:", anyprev)
    log("top unmatched:", sorted(miss.items(), key=lambda x: -x[1])[:30])
    with open(os.path.join(odir, "fund_holdings.csv"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(hdr_h.split(","))
        w.writerows(rows)
    srows = [[s, a[0], a[1], "%.2f" % (a[2] / a[1]), (added.get(s, 0) if anyprev else "")]
             for s, a in agg.items() if a[1] >= 3]
    srows.sort(key=lambda r: (-r[2], -float(r[3])))
    with open(os.path.join(odir, "fund_stocks.csv"), "w", newline="") as fh:
        w = csv.writer(fh); w.writerow(hdr_s.split(","))
        w.writerows(srows)
    log("fund_stocks rows:", len(srows))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        import traceback; traceback.print_exc()
        log("fatal, exiting 0 so the workflow keeps going:", repr(e))
    log("done")
