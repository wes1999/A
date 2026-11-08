#!/usr/bin/env python3
"""Prepara geometrias oficiais; não inventa polígonos ou temperaturas."""
import os, sys, json, glob, zipfile, requests, shapefile, tempfile
from pathlib import Path
from shapely.geometry import shape, mapping
from shapely.ops import unary_union

OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets"
OUT.mkdir(parents=True, exist_ok=True)
TMP = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir())) / "termomapa_ibge"
TMP.mkdir(exist_ok=True)
BASE = "https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/malhas_municipais/municipio_2025/Brasil/"
STATES = {"11":"RO","12":"AC","13":"AM","14":"RR","15":"PA","16":"AP","17":"TO",
"21":"MA","22":"PI","23":"CE","24":"RN","25":"PB","26":"PE","27":"AL","28":"SE","29":"BA",
"31":"MG","32":"ES","33":"RJ","35":"SP","41":"PR","42":"SC","43":"RS","50":"MS",
"51":"MT","52":"GO","53":"DF"}
def fetch_zip(name):
    target = TMP / name
    if not target.exists():
        print("Baixando IBGE", name, flush=True)
        with requests.get(BASE + name, stream=True, timeout=(25, 180)) as r:
            r.raise_for_status()
            with open(target, "wb") as f:
                for chunk in r.iter_content(1024*1024):
                    if chunk: f.write(chunk)
    dest = TMP / name.replace(".zip","")
    if not dest.exists():
        dest.mkdir()
        with zipfile.ZipFile(target) as z: z.extractall(dest)
    matches = glob.glob(str(dest / "**" / "*.shp"), recursive=True)
    if not matches: raise RuntimeError("SHP não encontrado: " + name)
    return next((p for p in matches if "Municipios" in p or "UF" in p),matches[0])
def rnd(o):
    if isinstance(o, (list, tuple)): return [rnd(x) for x in o]
    if isinstance(o, dict): return {k:rnd(v) for k,v in o.items()}
    if isinstance(o, float): return round(o, 5)
    return o
def save(name, features):
    p=OUT/name
    with open(p,"w",encoding="utf-8") as f:
        json.dump({"type":"FeatureCollection","features":features},f,ensure_ascii=False,separators=(",",":"))
    print(name, len(features), round(p.stat().st_size/1048576,2),"MiB",flush=True)
def read_shp(path, type_):
    sf=shapefile.Reader(path,encoding="utf-8")
    fields=[x[0] for x in sf.fields[1:]]
    total=[]
    for idx,rec in enumerate(sf.iterShapeRecords()):
        props=dict(zip(fields,rec.record))
        code=str(props.get("CD_MUN") or props.get("CD_UF") or "")
        if type_=="mun" and (code.startswith("53") or len(code)!=7): continue
        g=shape(rec.shape.__geo_interface__)
        if g.is_empty: continue
        if not g.is_valid: g=g.buffer(0)
        if g.is_empty:continue
        point=g.representative_point()
        if type_=="mun":
            # Precisão cartográfica suficiente para navegação em smartphone,
            # malha original 2025 e contornos simplificados, sem retas inventadas.
            g=g.simplify(0.0018,preserve_topology=True)
            nome=str(props.get("NM_MUN") or props.get("NM_MUNICIP") or code)
            uf=STATES.get(code[:2],"")
        else:
            g=g.simplify(0.0012,preserve_topology=True)
            nome=str(props.get("NM_UF") or code)
            uf=STATES.get(code[:2],"")
        total.append({"type":"Feature","properties":{
            "id":code,"nome":nome,"uf":uf,"pt":[round(point.x,5),round(point.y,5)]},
            "geometry":rnd(mapping(g))})
        if idx%1200==0:print(type_,idx,flush=True)
    return total
muns=read_shp(fetch_zip("BR_Municipios_2025.zip"),"mun")
if len(muns)<5500:raise RuntimeError("Malha incompleta: "+str(len(muns)))
save("municipios.json",muns)
states=read_shp(fetch_zip("BR_UF_2025.zip"),"uf")
save("ufs.json",states)

# DF: fonte pública da IDE/DF para pré-visualização. O app permite
# IMPORTAR o SHP ZIP alinhado do usuário e substituir exatamente esta malha.
RA_URLS=[
"https://www.geoservicos.ide.df.gov.br/arcgis/rest/services/Publico/LIMITES/FeatureServer/1/query",
"https://onda.ibram.df.gov.br/server/rest/services/Hosted/regiao_administrativa_df/FeatureServer/0/query"
]
ras=[]
for url in RA_URLS:
    try:
        print("Consulta RA:", url,flush=True)
        r=requests.get(url,params={"where":"1=1","outFields":"*","returnGeometry":"true","f":"geojson","outSR":4326},timeout=90)
        r.raise_for_status(); data=r.json()
        if len(data.get("features",[]))<30:raise ValueError("Menos de 30 RA")
        for rec in data["features"]:
            pr=rec.get("properties",{})
            nome=next((str(pr[k]) for k in ("ra_nome","RA_NOME","ra","NOME","nome","name") if pr.get(k)), "Região Administrativa")
            g=shape(rec["geometry"])
            if not g.is_valid:g=g.buffer(0)
            if g.is_empty:continue
            pt=g.representative_point()
            ras.append({"type":"Feature","properties":{"id":str(pr.get("ra_codigo",len(ras)+1)),
                "nome":nome,"uf":"DF","pt":[round(pt.x,5),round(pt.y,5)]},
                "geometry":rnd(mapping(g.simplify(0.00065,preserve_topology=True)))})
        if ras:break
    except Exception as exc:
        print("Erro fonte RA:",exc,flush=True)
if not ras:print("AVISO: RAs vazias; usar opção importar ZIP no aplicativo.",flush=True)
save("df_ras.json",ras)
