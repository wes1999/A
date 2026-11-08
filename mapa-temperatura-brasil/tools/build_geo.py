#!/usr/bin/env python3
"""Prepara dados vetoriais para o APK. A malha é baixada somente durante a compilação."""
import os, json, zipfile, tempfile, pathlib, collections, sys, time
import requests, shapefile
from shapely.geometry import shape, mapping, Polygon, MultiPolygon
from shapely.ops import transform
from pyproj import Transformer

ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/"app"/"src"/"main"/"assets"/"mapa"
STATES=OUT/"estados"
STATES.mkdir(parents=True, exist_ok=True)
IBGE_BASE="https://geoftp.ibge.gov.br/organizacao_do_territorio/malhas_territoriais/malhas_municipais/municipio_2025/Brasil/"
def download(url,dest):
    if dest.exists() and dest.stat().st_size>1_000_000: return
    print("Baixando",url,flush=True)
    session=requests.Session()
    for attempt in range(4):
        try:
            with session.get(url,stream=True,timeout=(40,150)) as r:
                r.raise_for_status()
                with open(dest,"wb") as f:
                    for chunk in r.iter_content(1024*1024):
                        if chunk:f.write(chunk)
            if dest.stat().st_size<10000: raise RuntimeError("Download incompleto")
            return
        except Exception:
            if attempt==3:raise
            time.sleep(10*(attempt+1))
def extract(zip_path,folder):
    with zipfile.ZipFile(zip_path) as z: z.extractall(folder)
    shp=list(folder.rglob("*.shp"))
    if not shp:raise RuntimeError("ZIP sem SHP: "+str(zip_path))
    return max(shp,key=lambda p:p.stat().st_size)
def load_shapes(shp):
    reader=shapefile.Reader(str(shp), encoding="utf-8",encodingErrors="replace")
    for rec in reader.iterShapeRecords():
        try:
            g=shape(rec.shape.__geo_interface__)
            if g.is_empty:continue
            d=rec.record.as_dict()
            yield d,g
        except Exception as e:
            print("Registro descartado:",repr(e),flush=True)
def obj(geom,props):
    # Coordenadas 5 casas decimais (precisão métrica). Reduz tamanho do APK
    # sem trocar os polígonos por versões geométricas de baixa resolução.
    def compact(v):
        if isinstance(v,(tuple,list)):
            return [compact(x) for x in v]
        if isinstance(v,float):
            return round(v,5)
        return v
    g=mapping(geom)
    g["coordinates"]=compact(g["coordinates"])
    return {"type":"Feature","properties":props,"geometry":g}
def save(path,features):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps({"type":"FeatureCollection","features":features},ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    print(path.relative_to(ROOT),len(features),"features",round(path.stat().st_size/1024/1024,2),"MiB",flush=True)
def get(d,*names):
    lc={str(k).lower():v for k,v in d.items()}
    for k in names:
        v=lc.get(k.lower())
        if v is not None and str(v).strip():return str(v).strip()
    return ""
def geo_props(g,code,name,uf,kind="municipio"):
    p=g.representative_point()
    return {"id":code,"nm":name,"uf":uf,"kind":kind,"lat":round(p.y,5),"lon":round(p.x,5)}
def fix(g):
    if not g.is_valid:
        try:g=g.buffer(0)
        except Exception:pass
    if g.geom_type=="GeometryCollection":
        poly=[x for x in g.geoms if x.geom_type in ("Polygon","MultiPolygon")]
        if not poly:return None
        from shapely.ops import unary_union
        g=unary_union(poly)
    return g if (not g.is_empty and g.geom_type in ("Polygon","MultiPolygon")) else None
def main():
    with tempfile.TemporaryDirectory(prefix="ibge2025_") as tmp:
        tmp=pathlib.Path(tmp)
        munzip=tmp/"municipios.zip"
        download(IBGE_BASE+"BR_Municipios_2025.zip",munzip)
        shp=extract(munzip,tmp/"municipios")
        overview=[];by_uf=collections.defaultdict(list);bounds={}
        counts=collections.Counter()
        print("Processando malha IBGE 2025:",shp,flush=True)
        for idx,(d,g) in enumerate(load_shapes(shp)):
            cd=get(d,"CD_MUN","CD_GEOCMU","GEOCODIGO")
            nm=get(d,"NM_MUN","NOME")
            uf=get(d,"SIGLA_UF","UF")
            if not uf and cd:
                number=cd[:2]
                uf={"11":"RO","12":"AC","13":"AM","14":"RR","15":"PA","16":"AP","17":"TO","21":"MA","22":"PI","23":"CE","24":"RN","25":"PB","26":"PE","27":"AL","28":"SE","29":"BA","31":"MG","32":"ES","33":"RJ","35":"SP","41":"PR","42":"SC","43":"RS","50":"MS","51":"MT","52":"GO","53":"DF"}.get(number,"")
            if not cd or not nm or not uf:continue
            g=fix(g)
            if g is None:continue
            p=geo_props(g,cd,nm,uf)
            overview.append(obj(g.simplify(.00035,preserve_topology=True),p))
            by_uf[uf].append(obj(g.simplify(.00006,preserve_topology=True),p))
            counts[uf]+=1
            bb=g.bounds
            if uf not in bounds:bounds[uf]=[bb[1],bb[0],bb[3],bb[2]]
            else:
                b=bounds[uf];bounds[uf]=[min(b[0],bb[1]),min(b[1],bb[0]),max(b[2],bb[3]),max(b[3],bb[2])]
            if idx%1000==0:print(idx,"polígonos processados",flush=True)
        if len(overview)<5500:raise RuntimeError(f"Malha incompleta: {len(overview)}")
        save(OUT/"overview.geojson",overview)
        for uf,features in by_uf.items():save(STATES/(uf+".geojson"),features)
        (OUT/"estados_bounds.json").write_text(json.dumps(bounds,ensure_ascii=False,separators=(",",":")),encoding="utf-8")
        ufzip=tmp/"ufs.zip"
        download(IBGE_BASE+"BR_UF_2025.zip",ufzip)
        ufs=extract(ufzip,tmp/"ufs")
        borders=[]
        df_mask=None
        for d,g in load_shapes(ufs):
            g=fix(g)
            if g is None:continue
            nm=get(d,"SIGLA_UF","UF","NM_UF")
            if nm=="DF": df_mask=g
            borders.append(obj(g.simplify(.00015,preserve_topology=True),{"uf":nm}))
        save(OUT/"uf_outlines.geojson",borders)
    if df_mask is None: raise RuntimeError("Polígono IBGE 2025 do DF ausente")
    build_df(df_mask)
def build_df(df_mask):
    # Priorizar explicitamente a malha alinhada pelo usuário quando ela for
    # adicionada à pasta inputs do projeto, sem alterar seus limites internos.
    user_zip=ROOT/"inputs"/"df_alinhado.zip"
    features=[]
    if user_zip.exists():
        print("Usando a malha DF alinhada pelo usuário",flush=True)
        with tempfile.TemporaryDirectory() as td:
            shp=extract(user_zip,pathlib.Path(td))
            tr=Transformer.from_crs("EPSG:31983","EPSG:4326",always_xy=True)
            for i,(d,g) in enumerate(load_shapes(shp)):
                g=fix(transform(tr.transform,g))
                if g is None:continue
                nm=get(d,"ra_nome","NOME","RA_NOME","REGIAO_ADM","nome_ra")
                if not nm:raise RuntimeError("Malha DF não contém nomes para todas as RAs.")
                cd=get(d,"ra_codigo","CODIGO","CD_RA")
                p=geo_props(g,"ra_"+(cd or str(i+1)),nm,"DF","ra")
                features.append(obj(g.simplify(.00006,preserve_topology=True),p))
    else:
        print("ATENÇÃO: DF alinhado ausente. Usando provisoriamente IDE-DF.",flush=True)
        url="https://www.geoservicos.ide.df.gov.br/arcgis/rest/services/Publico/LIMITES/FeatureServer/1/query"
        r=requests.get(url,params={"where":"1=1","outFields":"*","outSR":4326,"f":"geojson"},timeout=120)
        r.raise_for_status()
        data=r.json()
        if "features" not in data:raise RuntimeError("IDE-DF retornou: "+str(data)[:500])
        for i,f in enumerate(data["features"]):
            d=f.get("properties",{})
            g=fix(shape(f["geometry"]))
            if g is None:continue
            g=fix(g.intersection(df_mask))
            if g is None:continue
            nm=get(d,"ra_nome","RA_NOME","name","nome","ra")
            if not nm:raise RuntimeError("Nome RA ausente: "+str(d)[:200])
            cd=get(d,"ra_codigo","CODIGO","ra")
            p=geo_props(g,"ra_"+(cd or str(i+1)),nm,"DF","ra")
            features.append(obj(g.simplify(.00015,preserve_topology=True),p))
    if len(features)<33:raise RuntimeError(f"Quantidade de RAs insuficiente: {len(features)}")
    save(OUT/"df.geojson",features)
    (OUT/"dados_info.json").write_text(json.dumps({"df_source":"usuario" if user_zip.exists() else "IDE-DF","ra_count":len(features),"ibge_year":2025},ensure_ascii=False),encoding="utf-8")
if __name__=="__main__":main()
