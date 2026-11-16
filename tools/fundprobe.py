import urllib.request, json, sys, re
def get(u, n=600, hdr=None):
    h={"User-Agent":"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36","Accept":"application/json,text/plain,*/*"}
    if hdr: h.update(hdr)
    try:
        r=urllib.request.urlopen(urllib.request.Request(u,headers=h),timeout=40)
        b=r.read()
        print("OK",r.status,len(b),u); print(b[:n].decode("utf8","replace").replace("\n","\\n")); return b
    except Exception as e:
        print("ERR",u,e); return None
print("== NAVAll"); b=get("https://www.amfiindia.com/spages/NAVAll.txt",800)
print("== mfapi"); get("https://api.mfapi.in/mf/122639",300); get("https://api.mfapi.in/mf/122639/latest",300)
print("== AUM amfi")
for u in ["https://www.amfiindia.com/api/average-aum-schemewise?strType=1&fyId=2&periodId=22&MF_ID=0",
 "https://www.amfiindia.com/api/average-aum-schemewise?strType=1&fyId=0&periodId=0&MF_ID=0",
 "https://www.amfiindia.com/modules/AverageAUMDetails",
 "https://portal.amfiindia.com/spages/AUMReport.txt",
 "https://www.amfiindia.com/research-information/aum-data/average-aum",
 "https://api.mfapi.in/mf/search?q=parag"]:
    get(u,500)
print("== holdings candidates")
for u in ["https://api.kuvera.in/mf/api/v4/fund_schemes/PPFAS-FLEXI-CAP-DIRECT-GROWTH.json",
 "https://api.kuvera.in/mf/api/v4/fund_schemes/INF879O01027.json",
 "https://api.kuvera.in/mf/api/v5/fund_schemes/list.json",
 "https://mf.captnemo.in/kuvera/INF879O01027",
 "https://groww.in/v1/api/data/mf/web/v1/scheme/search/parag-parikh-flexi-cap-fund-direct-growth",
 "https://groww.in/v1/api/data/mf/web/v3/scheme/search/parag-parikh-flexi-cap-fund-direct-growth",
 "https://api.mfdata.in/api/v1/schemes/122639","https://mfdata.in/api/v1/schemes/122639",
 "https://www.valueresearchonline.com/api/funds/","https://api.etmoney.com/",
 "https://www.moneycontrol.com/mc/widget/mfnavonetime/getMfNavData",
 "https://api.mfapi.in/mf/122639/holdings"]:
    get(u,1500)
