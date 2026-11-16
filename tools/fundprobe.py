import urllib.request, json, sys
H={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","Accept":"application/json, text/plain, */*"}
def get(u, n=600, ref=None):
    h=dict(H)
    if ref: h["Referer"]=ref
    try:
        r=urllib.request.urlopen(urllib.request.Request(u,headers=h),timeout=40)
        b=r.read(); print("OK",r.status,len(b),u); print(b[:n].decode("utf8","replace").replace("\n","\\n")); return b
    except Exception as e:
        print("ERR",u,e); return None
A="https://www.amfiindia.com"; R=A+"/aum-data/average-aum"
print("== AUM")
b=get(A+"/api/average-aum-fundwise",800,R)
b=get(A+"/api/average-aum-fundwise?fyId=1",800,R)
for fy in (1,2):
  for p in (1,2,3,4):
    get(A+f"/api/average-aum-schemewise?strType=Categorywise&fyId={fy}&periodId={p}&MF_ID=0",1500,R)
print("== kuvera/groww")
get("https://api.kuvera.in/mf/api/v4/fund_schemes/PP001ZG-GR.json",3000)
get("https://api.kuvera.in/mf/api/v5/fund_schemes/PP001ZG-GR.json",3000)
get("https://api.kuvera.in/mf/api/v3/funds/PP001ZG-GR.json",800)
for u in ["https://groww.in/v1/api/search/v1/entity?app=false&entity_type=scheme&page=0&q=parag%20parikh%20flexi&size=5",
 "https://groww.in/v1/api/search/v3/query/global/st_p_query?page=0&query=parag%20parikh%20flexi&size=5&web=true",
 "https://groww.in/v1/api/data/mf/web/v1/scheme/search/parag-parikh-long-term-value-fund-direct-growth",
 "https://groww.in/v1/api/data/mf/web/v1/scheme/search/parag-parikh-flexi-cap-fund-direct-growth"]:
    b=get(u,2500)
