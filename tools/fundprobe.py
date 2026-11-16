import urllib.request, json, sys, urllib.parse
H={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36","Accept":"application/json, text/plain, */*"}
def get(u):
    try: return json.loads(urllib.request.urlopen(urllib.request.Request(u,headers=H),timeout=30).read())
    except Exception as e: print("ERR",u,e); return None
for q in ["Parag Parikh Flexi Cap Fund Direct Growth","Nippon India Small Cap Fund Direct Plan Growth","HDFC Balanced Advantage Fund Direct Growth","UTI Nifty 50 Index Fund Direct Growth","Quant Small Cap Fund Direct Plan Growth"]:
    s=get("https://groww.in/v1/api/search/v1/entity?app=false&entity_type=scheme&page=0&q="+urllib.parse.quote(q)+"&size=5")
    print("Q",q,[(c["title"],c["scheme_code"],c["search_id"]) for c in (s or {}).get("content",[])])
d=get("https://groww.in/v1/api/data/mf/web/v1/scheme/search/parag-parikh-long-term-value-fund-direct-growth")
print(list(d.keys()))
h=d["holdings"]; print(len(h)); print(json.dumps(h[:3])); print(d.get("nav"),d.get("nav_date"),d.get("aum"))
for k in d:
    if k not in("holdings","analysis") and isinstance(d[k],(list,dict)): print(k,json.dumps(d[k])[:300])
