#!/usr/bin/env python3
"""Observações reais de temperatura METAR, complementares ao GFS.

A estação é associada apenas ao polígono municipal/RA que contém suas
coordenadas. Municípios sem estação com observação recente mantêm sua
estimativa GFS. A NOAA publica o cache METAR em um único arquivo gz.
"""
import csv
import datetime as dt
import gzip
import io
import json
import math
import os
import pathlib
import zipfile

import requests
from shapely.geometry import Point, shape
from shapely.strtree import STRtree

CACHE_URL = "https://aviationweather.gov/data/cache/metars.cache.csv.gz"
UA = {"User-Agent":"TemperaturasBrasil-METAR/1.0 (educational; https://github.com/wes1999/A)"}
MAX_AGE_SECONDS = 2.5 * 3600


def _geo_from_apk(apk_path):
    if not os.path.isfile(apk_path):
        raise RuntimeError("APK de cadastro não encontrado")
    with zipfile.ZipFile(apk_path) as apk:
        geo = []
        for filename in ("overview.geojson", "df.geojson"):
            candidates=[p for p in apk.namelist() if p.endswith("/assets/mapa/"+filename)]
            if len(candidates)!=1:
                raise RuntimeError("Base geográfica ausente: "+filename)
            geo.extend(json.loads(apk.read(candidates[0]))["features"])
    geoms=[]
    codes=[]
    excluded={"5300108","4300001","4300002"}
    for f in geo:
        name=str((f.get("properties") or {}).get("id",""))
        if not name or name in excluded:
            continue
        geom=shape(f["geometry"])
        if geom.is_empty:
            continue
        geoms.append(geom)
        codes.append(name)
    return geoms, codes


def _parse_dt(raw):
    s=(raw or "").strip()
    if not s:
        return None
    try:
        parsed=dt.datetime.fromisoformat(s.replace("Z","+00:00"))
        if parsed.tzinfo is None:
            parsed=parsed.replace(tzinfo=dt.timezone.utc)
        return parsed.astimezone(dt.timezone.utc)
    except Exception:
        try:
            return dt.datetime.fromtimestamp(float(s),tz=dt.timezone.utc)
        except Exception:
            return None


def get_metars(now=None):
    now=now or dt.datetime.now(dt.timezone.utc)
    r=requests.get(CACHE_URL,headers=UA,timeout=(20,70))
    r.raise_for_status()
    if len(r.content)>20_000_000:
        raise RuntimeError("Cache METAR inesperadamente grande")
    csvtext=gzip.decompress(r.content).decode("utf-8-sig","replace")
    # O cache começa com metadados em linhas iniciadas em '#'.
    lines=csvtext.splitlines()
    pos=next((i for i,line in enumerate(lines) if ("station_id" in line and
              ("latitude" in line or "lat" in line) and "temp_c" in line)),None)
    if pos is None:
        raise RuntimeError("Cabeçalho do cache METAR não encontrado")
    reader=csv.DictReader(lines[pos:])
    latest={}
    for row in reader:
        try:
            lat=float(row.get("latitude") or row.get("lat") or "")
            lon=float(row.get("longitude") or row.get("lon") or "")
            temp=float(row.get("temp_c") or "")
            when=_parse_dt(row.get("observation_time") or row.get("observation") or "")
            station=str(row.get("station_id") or "").strip().upper()
            if not (-34.5<=lat<=6 and -75.5<=lon<=-32):
                continue
            if not (-20<temp<55) or when is None or not station:
                continue
            delta=(now-when).total_seconds()
            if delta < -1800 or delta>MAX_AGE_SECONDS:
                continue
            if station not in latest or when>latest[station]["when"]:
                latest[station]={"station":station,"lat":lat,"lon":lon,
                                 "temperature":round(temp,1),"when":when}
        except (ValueError,TypeError,OverflowError):
            continue
    if not latest:
        raise RuntimeError("Nenhuma observação brasileira METAR recente")
    return list(latest.values())


def observed_by_territory(apk_path,valid_ids):
    geoms,codes=_geo_from_apk(apk_path)
    tree=STRtree(geoms)
    stations=get_metars()
    observations={}
    for st in stations:
        pt=Point(st["lon"],st["lat"])
        for idx in tree.query(pt):
            idx=int(idx)
            poly=geoms[idx]
            if not poly.covers(pt):
                continue
            id=codes[idx]
            if id not in valid_ids:
                continue
            meta={"temp":st["temperature"],"station":st["station"],
                  "observed_at_utc":st["when"].strftime("%Y-%m-%dT%H:%M:%SZ")}
            old=observations.get(id)
            if old is None or meta["observed_at_utc"]>old["observed_at_utc"]:
                observations[id]=meta
    return observations, len(stations)


def merge_into_snapshot(snapshot,apk_path):
    ids=set(snapshot["values"])
    observed,station_count=observed_by_territory(apk_path,ids)
    for id,info in observed.items():
        snapshot["values"][id]=info["temp"]
    snapshot["observed"]=observed
    snapshot["observed_count"]=len(observed)
    snapshot["metar_stations_considered"]=station_count
    snapshot["observations_source"]="NOAA Aviation Weather Center METAR"
    print("METARs brasileiros recentes:",station_count,
          "| territórios com estação em seu polígono:",len(observed),flush=True)
    return snapshot
