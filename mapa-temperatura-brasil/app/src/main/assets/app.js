"use strict";
const $=id=>document.getElementById(id);
const norm=s=>String(s||"").normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().trim();
const escapeHtml=s=>String(s||"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const setStatus=s=>{$("status").textContent=s;};
const map=L.map("map",{preferCanvas:true,zoomControl:false,attributionControl:false,minZoom:3,maxZoom:14,worldCopyJump:false,zoomSnap:.25});
L.control.zoom({position:"bottomright"}).addTo(map);
map.fitBounds([[-34,-74],[6,-34]]);
const renderer=L.canvas({padding:.3});
const db=new Map(), temperatures=new Map(),detailLayers=new Map(),stateBounds={};
let overviewLayer,dfLayer,stateOutline,selected=null,lastSnapshot=null,loading=false;
const DATA_URL="https://raw.githubusercontent.com/wes1999/A/dados-temperaturas/data/temperaturas.json";
const CACHE_KEY="temperaturas-brasil-nacional-v4";
const colors=[[-10,"#7132a1"],[0,"#7132a1"],[5,"#2446b7"],[10,"#24ace3"],[15,"#21bd80"],[20,"#e5d82f"],[30,"#f68d26"],[40,"#cf2434"],[50,"#8f1033"]];
function hexToRgb(h){return [1,3,5].map(i=>parseInt(h.substr(i,2),16));}
function toColor(t){
 if(t==null||!Number.isFinite(t))return "#d6e0e7";
 for(let i=1;i<colors.length;i++){
  if(t<=colors[i][0]){
   const a=colors[i-1],b=colors[i],f=Math.max(0,Math.min(1,(t-a[0])/(b[0]-a[0]))));
   const x=hexToRgb(a[1]),y=hexToRgb(b[1]);
   return "#"+x.map((v,j)=>Math.round(v+(y[j]-v)*f).toString(16).padStart(2,"0")).join("");
  }
 }
 return colors[colors.length-1][1];
}
function registerGeo(geo){
 for(const f of geo.features||[]){
  const p=f.properties||{},id=String(p.id||"");
  if(id&&Number.isFinite(Number(p.lat))&&Number.isFinite(Number(p.lon)))
   db.set(id,{id,lat:Number(p.lat),lon:Number(p.lon),p});
 }
}
function valueAt(p){const v=temperatures.get(String(p.id));return v==null?null:v;}
function style(f){
 const p=f.properties||{},id=String(p.id||""),isDF=id==="5300108";
 return {
  renderer,fill:true,fillColor:toColor(temperatures.get(id)),
  fillOpacity:isDF&&dfLayer?0:1,color:"#fff",
  weight:map.getZoom()>8?.7:.32,opacity:.9,
  interactive:!isDF
 };
}
function refreshStyles(){
 if(overviewLayer)overviewLayer.setStyle(style);
 for(const l of detailLayers.values())if(l&&l.setStyle)l.setStyle(style);
 if(dfLayer)dfLayer.setStyle(style);
}
function featureClicked(e){
 const p=e.target.feature.properties||{};
 selected=p;
 showDetail(p);
}
function addLayer(geo,kind){
 const layer=L.geoJSON(geo,{renderer,style,onEachFeature:(f,l)=>{l.on("click",featureClicked);}});
 if(kind==="base"){overviewLayer=layer;layer.addTo(map);}
 else if(kind==="df"){dfLayer=layer;layer.addTo(map);}
 return layer;
}
function timeBR(iso){
 if(!iso)return "indisponível";
 const d=new Date(iso);
 return Number.isFinite(d.getTime())?
  d.toLocaleString("pt-BR",{day:"2-digit",month:"2-digit",hour:"2-digit",minute:"2-digit"}):
  "indisponível";
}
function showDetail(p){
 if(!p)return;
 $("detail").hidden=false;
 $("type").textContent=p.kind==="ra"?"Região administrativa do DF":
   p.id==="2605459"?"Distrito estadual de Pernambuco":"Município de "+p.uf;
 $("name").textContent=p.nm;
 const t=valueAt(p);
 $("temperature").textContent=t==null?"Sem dados":t.toFixed(1).replace(".",",")+" °C";
 $("temperature").style.color=toColor(t);
 $("source").textContent=t==null?
   "Nenhuma atualização nacional disponível. Conecte-se e toque em Atualizar tudo.":
   "Temperatura estimada pelo modelo NOAA GFS (grade de 0,25°, a 2 m), no ponto representativo deste território. Modelo válido em "+timeBR(lastSnapshot&&lastSnapshot.valid_time_utc)+". Esta não é uma medição por estação local.";
}
$("closeDetail").onclick=()=>{$("detail").hidden=true;selected=null;};
function boundsIntersect(a,b){
 return a&&b&&a[0]<=b.getNorth()&&a[2]>=b.getSouth()&&a[1]<=b.getEast()&&a[3]>=b.getWest();
}
function reorderOutline(){if(stateOutline)stateOutline.bringToFront();}
function showDetailLayers(){
 if(!overviewLayer)return;
 const z=map.getZoom(),bounds=map.getBounds();
 if(z<6.5){
  for(const layer of detailLayers.values())if(layer&&layer.remove)map.removeLayer(layer);
 }else{
  for(const [uf,b] of Object.entries(stateBounds)){
   if(uf==="DF")continue;
   if(boundsIntersect(b,bounds)){
    if(detailLayers.has(uf)){
     const l=detailLayers.get(uf);if(l&&l.addTo)l.addTo(map);
     continue;
    }
    detailLayers.set(uf,{placeholder:true});
    fetch("mapa/estados/"+uf+".geojson").then(r=>{
     if(!r.ok)throw Error("Malha "+uf+": "+r.status);return r.json();
    }).then(g=>{
     const l=addLayer(g);
     detailLayers.set(uf,l);
     if(map.getZoom()>=6.5&&boundsIntersect(b,map.getBounds()))l.addTo(map);
     reorderOutline();
    }).catch(()=>detailLayers.delete(uf));
   }else{
    const l=detailLayers.get(uf);
    if(l&&l.remove)map.removeLayer(l);
   }
  }
 }
 if(dfLayer&&!map.hasLayer(dfLayer))dfLayer.addTo(map);
 reorderOutline();
}
function coverageIds(){
 return [...db.keys()].filter(id=>id!=="5300108"&&id!=="4300001"&&id!=="4300002");
}
function applyNational(snapshot,fromCache=false){
 if(!snapshot||snapshot.schema!==1||!snapshot.values||snapshot.municipios!==5569||snapshot.regioes_administrativas_df!==37)
  throw Error("Formato do cadastro nacional inválido");
 const ids=coverageIds();
 if(ids.length!==5607)throw Error("Cadastro geográfico incompleto: "+ids.length+" pontos");
 const next=new Map(),values=snapshot.values;
 for(const id of ids){
  if(!Object.prototype.hasOwnProperty.call(values,id))throw Error("Falta temperatura no território "+id);
  const t=values[id];
  if(typeof t!=="number"||!Number.isFinite(t)||t< -40||t>65)
   throw Error("Temperatura inválida no território "+id);
  next.set(id,t);
 }
 // Publicação atômica: só atualiza a cor quando TODOS os territórios foram validados.
 temperatures.clear();
 for(const [id,t] of next)temperatures.set(id,t);
 lastSnapshot=snapshot;
 refreshStyles();
 if(selected)showDetail(selected);
 const valid=timeBR(snapshot.valid_time_utc),updated=timeBR(snapshot.updated_at_utc);
 setStatus((fromCache?"Dados salvos • ":"Mapa nacional sincronizado • ")+
  "5.569 municípios + 37 RAs + Noronha • modelo válido "+valid+
  (fromCache?"":" • publicado "+updated));
 if(!fromCache){
  try{localStorage.setItem(CACHE_KEY,JSON.stringify(snapshot));}catch(e){}
 }
}
async function fetchNational(showMessage=true){
 if(loading)return;
 loading=true;
 if(showMessage)setStatus("Consultando atualização nacional dos 5.569 municípios e 37 regiões administrativas…");
 try{
  if(!navigator.onLine)throw Error("sem conexão com a internet");
  const ctl=new AbortController(),timeout=setTimeout(()=>ctl.abort(),25000);
  let response;
  try{
   response=await fetch(DATA_URL+"?r="+Date.now(),{cache:"no-store",signal:ctl.signal});
  }finally{clearTimeout(timeout);}
  if(!response.ok)throw Error("HTTP "+response.status);
  const fresh=await response.json();
  if(lastSnapshot&&new Date(fresh.updated_at_utc)<new Date(lastSnapshot.updated_at_utc))
   throw Error("servidor retornou versão mais antiga; cores anteriores preservadas");
  applyNational(fresh);
 }catch(err){
  setStatus("Não foi possível sincronizar: "+err.message+
    (temperatures.size?" • "+temperatures.size+" temperaturas anteriores preservadas.":" • sem dados nacionais salvos."));
 }finally{loading=false;}
}
async function loadData(){
 try{
  const [overview,df,bounds,outlines]=await Promise.all([
   fetch("mapa/overview.geojson").then(r=>r.json()),
   fetch("mapa/df.geojson").then(r=>r.json()),
   fetch("mapa/estados_bounds.json").then(r=>r.json()),
   fetch("mapa/uf_outlines.geojson").then(r=>r.json())
  ]);
  Object.assign(stateBounds,bounds);
  registerGeo(overview);registerGeo(df);
  addLayer(overview,"base");
  addLayer(df,"df");
  stateOutline=L.geoJSON(outlines,{interactive:false,style:{fill:false,color:"#155c8a",weight:1.2,opacity:.9}}).addTo(map);
  showDetailLayers();
  let restored=false;
  try{
   const c=JSON.parse(localStorage.getItem(CACHE_KEY)||"null");
   if(c){applyNational(c,true);restored=true;}
  }catch(err){console.warn("Cache antigo incompatível:",err);}
  if(!restored){
   try{
    const r=await fetch("temperaturas_inicial.json");
    if(r.ok){applyNational(await r.json(),true);restored=true;}
   }catch(_){}
  }
  await fetchNational(!restored);
  setInterval(()=>fetchNational(false),60*60*1000);
 }catch(err){setStatus("Erro ao carregar malha territorial: "+err.message);}
}
let moveTimer;
map.on("moveend zoomend",()=>{
 clearTimeout(moveTimer);
 // Zoom só troca a RESOLUÇÃO do desenho, jamais o dado meteorológico.
 moveTimer=setTimeout(showDetailLayers,280);
});
$("refresh").onclick=()=>fetchNational(true);
$("loc").onclick=()=>fetchNational(true);
$("all").onclick=()=>map.fitBounds([[-34,-74],[6,-34]]);
$("df").onclick=()=>map.flyTo([-15.78,-47.91],10,{duration:.7});
function searchList(q){
 if(q.length<2)return [];
 const a=norm(q),found=[...db.values()].filter(x=>norm(x.p.nm).includes(a));
 found.sort((x,y)=>{
  const i=norm(x.p.nm).startsWith(a),j=norm(y.p.nm).startsWith(a);
  return i===j?x.p.nm.localeCompare(y.p.nm):i?-1:1;
 });
 return found.slice(0,30);
}
function chooseSearch(x){
 $("results").hidden=true;
 $("search").value=x.p.nm;
 map.flyTo([x.lat,x.lon],x.p.kind==="ra"?12:9,{duration:.6});
 selected=x.p;
 showDetail(x.p);
}
function search(){
 const v=searchList($("search").value);
 $("results").replaceChildren();
 if(!v.length){$("results").hidden=true;return;}
 for(const x of v){
  const b=document.createElement("button");
  b.innerHTML=escapeHtml(x.p.nm)+"<small>"+
   (x.p.kind==="ra"?"Região administrativa, DF":x.id==="2605459"?"Distrito estadual, PE":"Município, "+escapeHtml(x.p.uf))+
   "</small>";
  b.onclick=()=>chooseSearch(x);
  $("results").appendChild(b);
 }
 $("results").hidden=false;
}
$("search").addEventListener("input",search);
$("go").onclick=()=>{const a=searchList($("search").value);if(a.length)chooseSearch(a[0]);};
$("search").addEventListener("keydown",e=>{if(e.key==="Enter")$("go").click();});
window.addEventListener("online",()=>fetchNational(true));
loadData();
