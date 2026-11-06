import urllib.request,urllib.parse,json,gzip,sys,os
q='[out:json][timeout:90];area["name"="Santana do Paraíso"]["boundary"="administrative"]["admin_level"="8"]->.a;way["highway"](area.a);out tags geom;'
for url in ['https://overpass.kumi.systems/api/interpreter','https://overpass-api.de/api/interpreter']:
 try:
  req=urllib.request.Request(url,data=urllib.parse.urlencode({'data':q}).encode(),headers={'User-Agent':'SantanaMap/1.0','Accept-Encoding':'gzip'})
  with urllib.request.urlopen(req,timeout=45) as r: b=r.read()
  if b[:2]==b'\x1f\x8b':b=gzip.decompress(b)
  obj=json.loads(b);n=sum(x.get('type')=='way' and len(x.get('geometry',[]))>1 for x in obj.get('elements',[]))
  if n<30:raise ValueError('too few roads')
  os.makedirs('app/src/main/assets',exist_ok=True)
  open('app/src/main/assets/roads.json','wb').write(b)
  print('Bundled',n,'roads')
  break
 except Exception as e:print('Skipping offline preloading:',e,file=sys.stderr)
