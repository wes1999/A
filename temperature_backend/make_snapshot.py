#!/usr/bin/env python3
"""Publica uma fotografia térmica nacional coerente, sem buscar temperaturas por zoom.

Modelo: NOAA GFS 0.25° (temperatura do ar a 2 m). Cada território recebe o valor
interpolado da grade meteorológica no seu ponto representativo. Não se trata de
uma medição em termômetro de cada município.
"""
import datetime as dt
import io
import json
import math
import os
import pathlib
import re
import sys
import tempfile
import time
import zipfile

import requests
import numpy as np
from eccodes import codes_grib_new_from_file, codes_get, codes_get_values, codes_release

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "data" / "temperaturas.json"
POINTS = ROOT / "data" / "pontos.json"
S3 = "https://noaa-gfs-bdp-pds.s3.amazonaws.com"
USER_AGENT = {"User-Agent": "MapaTemperaturasBrasil/1.0 (+https://github.com/wes1999/A)"}


def load_points():
    """Lê os pontos cadastrados no próprio APK IBGE 2025, independentemente do zoom."""
    path = pathlib.Path(os.environ.get("APK_PATH", ""))
    if not path.is_file():
        raise RuntimeError("APK_PATH deve apontar para o APK compilado contendo as malhas.")
    with zipfile.ZipFile(path) as z:
        files = z.namelist()
        def asset(basename):
            matches = [x for x in files if x.endswith("assets/mapa/" + basename)]
            if len(matches) != 1:
                raise RuntimeError("Asset não encontrado no APK: " + basename)
            return json.loads(z.read(matches[0]))
        overview = asset("overview.geojson")
        df = asset("df.geojson")

    points = []
    ignored = {"5300108", "4300001", "4300002"}  # DF amplo e áreas operacionais de lagos
    for f in overview["features"]:
        p = f["properties"]
        if str(p["id"]) in ignored:
            continue
        # Fernando de Noronha é distrito estadual, mas mantém cobertura de temperatura.
        points.append({"id":str(p["id"]), "lat":float(p["lat"]), "lon":float(p["lon"]), "kind":"distrito" if str(p["id"])=="2605459" else "municipio"})
    for f in df["features"]:
        p = f["properties"]
        points.append({"id":str(p["id"]), "lat":float(p["lat"]), "lon":float(p["lon"]), "kind":"ra"})
    n_municipios = sum(x["kind"]=="municipio" for x in points)
    n_ra = sum(x["kind"]=="ra" for x in points)
    if n_municipios != 5569 or n_ra != 37:
        raise RuntimeError(f"Cadastro incompleto: municipios={n_municipios}, RAs={n_ra}")
    if len({p["id"] for p in points}) != len(points):
        raise RuntimeError("IDs repetidos entre territórios.")
    POINTS.parent.mkdir(parents=True, exist_ok=True)
    POINTS.write_text(json.dumps(points,ensure_ascii=False,separators=(",",":")), encoding="utf-8")
    print("Cadastro confirmado: 5569 municípios, 37 RAs e Fernando de Noronha =",len(points),flush=True)
    return points


def fetch_grib():
    now=dt.datetime.now(dt.timezone.utc)
    target=now.replace(minute=0,second=0,microsecond=0)
    base=(target-dt.timedelta(hours=4)).replace(hour=(((target-dt.timedelta(hours=4)).hour)//6)*6)
    session=requests.Session()
    session.headers.update(USER_AGENT)
    last_error=None
    for fallback in range(5):
        cycle=base-dt.timedelta(hours=6*fallback)
        cycle_hour=cycle.hour
        forecast=int((target-cycle).total_seconds()//3600)
        if not 0<=forecast<=120:continue
        stem=f"gfs.{cycle:%Y%m%d}/{cycle_hour:02d}/atmos/gfs.t{cycle_hour:02d}z.pgrb2.0p25.f{forecast:03d}"
        url=f"{S3}/{stem}"
        print("Verificando",url,flush=True)
        try:
            idx=session.get(url+".idx",timeout=28)
            idx.raise_for_status()
            matches=[]
            lines=idx.text.strip().splitlines()
            for i,line in enumerate(lines):
                parts=line.split(":")
                if len(parts)>5 and parts[3]=="TMP" and parts[4]=="2 m above ground":
                    matches.append(i)
            if len(matches)!=1:raise RuntimeError("Campo TMP 2m não localizado no índice")
            k=matches[0]
            start=int(lines[k].split(":")[1])
            end=int(lines[k+1].split(":")[1])-1 if k+1<len(lines) else None
            if end is None or end<start:raise RuntimeError("Offset do próximo registro GRIB ausente")
            size=end-start+1
            if not 10_000<size<20_000_000:raise RuntimeError("Intervalo GRIB fora do esperado "+str(size))
            res=session.get(url,headers={"Range":f"bytes={start}-{end}"},timeout=80)
            if res.status_code!=206:raise RuntimeError("Servidor não respeitou Range: "+str(res.status_code))
            if len(res.content)!=size:raise RuntimeError("Dados GRIB truncados")
            with io.BytesIO(res.content) as data:
                gid=codes_grib_new_from_file(data)
                if gid is None:raise RuntimeError("Não foi possível decodificar GRIB")
                try:
                    ni=int(codes_get(gid,"Ni")); nj=int(codes_get(gid,"Nj"))
                    first_lat=float(codes_get(gid,"latitudeOfFirstGridPointInDegrees"))
                    first_lon=float(codes_get(gid,"longitudeOfFirstGridPointInDegrees"))
                    inc_i=float(codes_get(gid,"iDirectionIncrementInDegrees"))
                    inc_j=float(codes_get(gid,"jDirectionIncrementInDegrees"))
                    scan_i=int(codes_get(gid,"iScansNegatively"))
                    scan_j=int(codes_get(gid,"jScansPositively"))
                    j_consecutive=int(codes_get(gid,"jPointsAreConsecutive"))
                    values=codes_get_values(gid)
                finally:codes_release(gid)
            print("GFS disponível",ni,nj,first_lat,first_lon,inc_i,inc_j,"scans",scan_i,scan_j,j_consecutive,flush=True)
            if ni*nj!=values.size or ni<1000 or nj<500 or j_consecutive:
                raise RuntimeError("Grade GFS inesperada; publicação interrompida.")
            return {
                "grid":values.reshape(nj,ni), "first_lat":first_lat, "first_lon":first_lon,
                "step_lat": inc_j*(1 if scan_j else -1),
                "step_lon": inc_i*(-1 if scan_i else 1),
                "ni":ni, "nj":nj, "cycle":cycle, "valid":target
            }
        except Exception as e:
            last_error=e
            print("Ciclo indisponível:",str(e),flush=True)
    raise RuntimeError(f"Nenhum ciclo GFS acessível: {last_error}")


def interpolate(g,lat,lon):
    n_i,n_j=g["ni"],g["nj"]
    x=((lon+360)%360-g["first_lon"])/g["step_lon"]
    y=(lat-g["first_lat"])/g["step_lat"]
    if not (math.isfinite(x) and math.isfinite(y) and 0<=y<n_j-1):
        raise RuntimeError(f"Coordenada fora da grade: {lat} {lon}")
    x=x % n_i
    ix=int(math.floor(x)); iy=int(math.floor(y))
    dx=x-ix; dy=y-iy
    mat=g["grid"]
    a=float(mat[iy,ix]); b=float(mat[iy,(ix+1)%n_i])
    c=float(mat[iy+1,ix]); d=float(mat[iy+1,(ix+1)%n_i])
    temp=(a*(1-dx)*(1-dy)+b*dx*(1-dy)+c*(1-dx)*dy+d*dx*dy)-273.15
    if not -30<temp<60:raise RuntimeError("Temperatura inconsistente: "+str(temp))
    return round(temp,1)


def main():
    pts=load_points()
    g=fetch_grib()
    values={p["id"]:interpolate(g,p["lat"],p["lon"]) for p in pts}
    snapshot={
        "schema":1,
        "source":"NOAA GFS 0.25° — temperatura estimada a 2 m",
        "model_cycle_utc":g["cycle"].strftime("%Y-%m-%dT%H:%M:%SZ"),
        "valid_time_utc":g["valid"].strftime("%Y-%m-%dT%H:%M:%SZ"),
        "updated_at_utc":dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "municipios":5569,"regioes_administrativas_df":37,"distritos_estaduais":1,
        "coverage":len(values),"temperature_unit":"celsius",
        "values":values
    }
    assert len(values)==5607
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    OUTPUT.write_text(json.dumps(snapshot,ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    print("Fotografia nacional pronta:",OUTPUT,"temperaturas:",len(values),"bytes:",OUTPUT.stat().st_size,flush=True)


if __name__=="__main__":main()
