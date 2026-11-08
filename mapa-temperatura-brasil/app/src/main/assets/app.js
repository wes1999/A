"use strict";
const $ = id => document.getElementById(id);
const escapeHtml = s => String(s || "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const setStatus = s => { $("status").textContent = s; };
const norm = s => String(s||"").normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().trim();
const map = L.map("map",{preferCanvas:true,zoomControl:false,attributionControl:false,minZoom:3,maxZoom:14,worldCopyJump:false,zoomSnap:0.25});
L.control.zoom({position:"bottomright"}).addTo(map);
map.fitBounds([[-34,-74],[6,-34]]);
const renderer=L.canvas({padding:0.3});
const db=new Map(), direct=new Map(), regional=new Map(), estimated=new Map(), detailLayers=new Map(), stateBounds={};
let overviewLayer, dfLayer, stateOutline, selected=null, stopped=false;
let regionStamp=0, busy=false, timer=null;
const TTL_DIRECT=25*60*1000, TTL_REGION=60*60*1000;
const colors=[[-10,"#7132a1"],[0,"#7132a1"],[5,"#2446b7"],[10,"#24ace3"],[15,"#21bd80"],[20,"#e5d82f"],[30,"#f68d26"],[40,"#cf2434"],[50,"#8f1033"]];
function hexToRgb(h){return [1,3,5].map(i=>parseInt(h.substr(i,2),16));}
function toColor(t){
 if(t==null||!Number.isFinite(t))return "#d6e0e7";
 for(let i=1;i<colors.length;i++)if(t<=colors[i][0]){
  let a=colors[i-1],b=colors[i],f=Math.max(0,Math.min(1,(t-a[0])/(b[0]-a[0]))),x=hexToRgb(a[1]),y=hexToRgb(b[1]);
  return "#"+x.map((v,j)=>Math.round(v+(y[j]-v)*f).toString(16).padStart(2,"0")).join("");
 }
 return colors[colors.length-1][1];
}
function representative(p){return {id:String(p.id),lat:Number(p.lat),lon:Number(p.lon),p};}
function registerGeo(geo){(geo.features||[]).forEach(f=>{const p=f.properties||{};if(p.id&&Number.isFinite(+p.lat)&&Number.isFinite(+p.lon))db.set(String(p.id),representative(p));});}
function degreesDistance(a,b){
 const lat=(a.lat-b.lat)*111,lon=(a.lon-b.lon)*111*Math.max(.22,Math.cos(a.lat*Math.PI/180));
 return Math.sqrt(lat*lat+lon*lon);
}
// A temperatura de cada território é calculada pela mesma base,
 // independente do zoom e do polígono de detalhe exibido.
function recomputeEstimates(){
 const refs=[...regional.values()].filter(x=>Number.isFinite(x.v));
 const next=new Map();
 for(const p of db.values()){
  let a=null,b=null,c=null;
  for(const x of refs){
   const d=degreesDistance(p,x);
   const item={x,d};
   if(!a||d<a.d){c=b;b=a;a=item;}
   else if(!b||d<b.d){c=b;b=item;}
   else if(!c||d<c.d){c=item;}
  }
  if(!a)continue;
  let v,stamp;
  if(a.d<.5){v=a.x.v;stamp=a.x.t;}
  else {
   let w=0,total=0;
   for(const item of [a,b,c]){
    if(!item)continue;
    const k=1/Math.max(2,item.d)**2;
    w+=k;total+=k*item.x.v;
   }
   v=total/w;stamp=Math.max(...[a,b,c].filter(Boolean).map(q=>q.x.t));
  }
  if(Number.isFinite(v))next.set(p.id,{v,t:stamp});
 }
 estimated.clear();
 for(const [id,row] of next)estimated.set(id,row);
}
function valueAt(p){
 const exact=direct.get(String(p.id));
 if(exact && Date.now()-exact.t<12*60*60*1000)return {t:exact.v,exact:true,date:exact.t,modelTime:exact.mt};
 const x=estimated.get(String(p.id));
 if(!x)return {t:null,exact:false,date:null};
 const isObserved=regional.has(String(p.id));
 return {t:x.v,exact:isObserved,date:x.t};
}
function style(f){
 const p=f.properties||{}, v=valueAt(p), isDF=String(p.id)==="5300108";
 return {renderer,fill:true,fillColor:toColor(v.t),fillOpacity:isDF&&!!dfLayer?0:1,color:"#fff",weight:map.getZoom()>8?0.7:0.32,opacity:0.9,interactive:!(isDF && !!dfLayer)};
}
function refreshStyles(){
 if(overviewLayer)overviewLayer.setStyle(style);
 for(const layer of detailLayers.values())if(layer&&layer.setStyle)layer.setStyle(style);
 if(dfLayer)dfLayer.setStyle(style);
}
let styleTimer;
function queueStyle(){clearTimeout(styleTimer);styleTimer=setTimeout(refreshStyles,250);}
function featureClicked(e){
 selected=e.target.feature.properties;
 showDetail(selected);
 const d=direct.get(String(selected.id));
 if(!d||Date.now()-d.t>TTL_DIRECT)fetchBatch([representative(selected)],"tap");
}
function addLayer(f,custom){
 const layer=L.geoJSON(f,{renderer,style, onEachFeature:(feat,l)=>{l.on("click",featureClicked);}});
 if(custom==="base"){overviewLayer=layer;layer.addTo(map);}
 else if(custom==="df"){dfLayer=layer;layer.addTo(map);}
 return layer;
}
function prettyTime(ms){return ms?new Date(ms).toLocaleTimeString("pt-BR",{hour:"2-digit",minute:"2-digit"}):"sem atualização";}
function showDetail(p){
 selected=p;$("detail").hidden=false;
 $("type").textContent=p.kind==="ra"?"Região administrativa do DF":"Município de "+p.uf;
 $("name").textContent=p.nm;
 const x=valueAt(p);
 $("temperature").textContent=x.t==null?"Sem dados":x.t.toFixed(1).replace(".",",")+" °C";
 $("temperature").style.color=toColor(x.t);
 $("source").textContent=x.t==null?"Conecte-se à internet para consultar.":(x.exact?"Temperatura estimada pelo modelo no ponto representativo deste território.":"Estimativa regional interpolada; consulte este território para obter o valor específico.")+" Dados Open-Meteo • atualizado às "+prettyTime(x.date)+".";
}
$("closeDetail").onclick=()=>{$("detail").hidden=true;selected=null;};
function boundsIntersect(a,b){
 return a&&b&&a[0]<=b.getNorth()&&a[2]>=b.getSouth()&&a[1]<=b.getEast()&&a[3]>=b.getWest();
}
function showDetailLayers(){
 if(!overviewLayer)return;
 const z=map.getZoom(),bounds=map.getBounds();
 if(z<6.5){for(const layer of detailLayers.values())if(layer&&layer.remove)map.removeLayer(layer);}
 else {
  for(const [uf,b] of Object.entries(stateBounds)){
   if(uf==="DF")continue;
   if(boundsIntersect(b,bounds)){
    if(detailLayers.has(uf)){const item=detailLayers.get(uf);if(item&&item.addTo)item.addTo(map);continue;}
    detailLayers.set(uf,{placeholder:true});
    fetch("mapa/estados/"+uf+".geojson").then(r=>{if(!r.ok)throw Error(r.status);return r.json();}).then(g=>{
     const layer=addLayer(g);detailLayers.set(uf,layer);
     if(map.getZoom()>=6.5&&boundsIntersect(b,map.getBounds()))layer.addTo(map);
     reorderOutline();
    }).catch(()=>detailLayers.delete(uf));
   }else if(detailLayers.has(uf)&&detailLayers.get(uf).remove)map.removeLayer(detailLayers.get(uf));
  }
 }
 if(dfLayer && !map.hasLayer(dfLayer))dfLayer.addTo(map);
 reorderOutline();
 queueStyle();
}
function reorderOutline(){if(stateOutline){stateOutline.bringToFront();}}
function dailyBudget(needed){
 const day=new Date().toISOString().slice(0,10),key="temp-budget",b=JSON.parse(localStorage.getItem(key)||"null");
 const today=b&&b.day===day?b:{day,n:0};
 // A location in a multi-point request counts as a call-equivalent; stay below provider limits.
 if(today.n+needed>8500){setStatus("Limite diário de consultas próximo. Mantendo temperaturas em cache.");return false;}
 today.n+=needed;localStorage.setItem(key,JSON.stringify(today));return true;
}
async function fetchBatch(points,kind){
 points=points.filter(p=>p&&Number.isFinite(p.lat)&&Number.isFinite(p.lon));
 if(!points.length||!navigator.onLine)return;
 const chunks=[];for(let i=0;i<points.length;i+=35)chunks.push(points.slice(i,i+35));
 const fetched=new Map();
 let failures=0;
 for(const chunk of chunks){
  if(!dailyBudget(chunk.length))break;
  const params=new URLSearchParams({
    latitude:chunk.map(x=>x.lat.toFixed(4)).join(","),
    longitude:chunk.map(x=>x.lon.toFixed(4)).join(","),
    current:"temperature_2m",
    timezone:"UTC",
    forecast_days:"1"
  });
  try {
   const ctl=new AbortController(),kill=setTimeout(()=>ctl.abort(),18000);
   let response;
   try{response=await fetch("https://api.open-meteo.com/v1/forecast?"+params,{signal:ctl.signal,cache:"no-store"});}
   finally{clearTimeout(kill);}
   if(!response.ok)throw Error("HTTP "+response.status);
   const payload=await response.json(),arr=Array.isArray(payload)?payload:[payload],now=Date.now();
   arr.forEach((ob,i)=>{
    const t=Number(ob.current&&ob.current.temperature_2m),p=chunk[i];
    if(Number.isFinite(t)&&p)fetched.set(String(p.id),{...p,v:t,t:now,mt:ob.current.time});
   });
  }catch(err){failures++;console.warn("Falha Open-Meteo:",err);}
 }
 if(fetched.size){
  if(kind==="regional"){
   // Troca atômica: o mapa todo recebe a nova rodada meteorológica
   // de uma vez só, em vez de piscar a cada lote recebido.
   for(const [id,row] of fetched)regional.set(id,row);
   recomputeEstimates();
  }else{
   for(const [id,row] of fetched)direct.set(id,row);
  }
  const now=Date.now();
  regionStamp=now;
  queueStyle();
  if(selected)showDetail(selected);
  setStatus((kind==="regional"?"Brasil inteiro":"Área selecionada")+" atualizado às "+prettyTime(now)+" • "+fetched.size+" pontos consultados.");
 }else if(failures){
  setStatus("Consulta meteorológica indisponível. Mantendo as últimas cores.");
 }
 return fetched.size;
}
function chooseSamples(){
 const cells=new Map(),grid=1.75;
 for(const x of db.values()){
  if(x.p.kind==="ra")continue;
  const latbin=Math.floor((x.lat+35)/grid),lonbin=Math.floor((x.lon+75)/grid);
  const key=latbin+":"+lonbin;
  const midLat=-35+(latbin+.5)*grid,midLon=-75+(lonbin+.5)*grid;
  const d=Math.abs(x.lat-midLat)+Math.abs(x.lon-midLon);
  if(!cells.has(key)||d<cells.get(key).d)cells.set(key,{...x,d});
 }
 let a=[...cells.values()].map(x=>({id:x.id,lat:x.lat,lon:x.lon,p:x.p}));
 // Limita o consumo da API gratuita e preserva amostras distribuídas pelo país.
 if(a.length>205){
  a.sort((x,y)=>x.lat===y.lat?x.lon-y.lon:x.lat-y.lat);
  const old=a;a=[];
  for(let i=0;i<205;i++)a.push(old[Math.floor(i*(old.length-1)/204)]);
 }
 // Todas as RAs são amostradas diretamente, independentemente do zoom no DF.
 const ras=[...db.values()].filter(x=>x.p.kind==="ra");
 return a.concat(ras);
}
async function updateRegional(force=false){
 if(busy)return;
 const last=Number(localStorage.getItem("temp-last-regional")||0);
 if(!force && Date.now()-last<TTL_REGION && regional.size)return;
 busy=true;
 try{const a=chooseSamples();setStatus("Atualizando referências climáticas do Brasil…");await fetchBatch(a,"regional");if(regional.size)localStorage.setItem("temp-last-regional",Date.now().toString());}
 finally{busy=false;}
}
async function updateViewport(force=false){
 if(!db.size)return;
 const z=map.getZoom(),b=map.getBounds();
 if(z<5.4)return;
 let a=[...db.values()].filter(x=>b.contains([x.lat,x.lon])&&!(x.p.uf==="DF"&&x.p.kind!=="ra"&&z>=8));
 if(z>=8 && dfLayer){a=a.filter(x=>x.p.uf!=="DF"||x.p.kind==="ra");}
 a.sort((x,y)=>degreesDistance(x,{lat:map.getCenter().lat,lon:map.getCenter().lng})-degreesDistance(y,{lat:map.getCenter().lat,lon:map.getCenter().lng}));
 a=a.slice(0,80).filter(x=>force||!direct.has(x.id)||Date.now()-direct.get(x.id).t>TTL_DIRECT);
 if(a.length){setStatus("Consultando "+a.length+" territórios na área visível…");await fetchBatch(a,"visible");}
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
  dfLayer=addLayer(df,"df");
  stateOutline=L.geoJSON(outlines,{interactive:false,style:{fill:false,color:"#155c8a",weight:1.2,opacity:.9}}).addTo(map);
  // Restore cached exact and coarse temperatures for an immediately useful display.
  try{
    const c=JSON.parse(localStorage.getItem("temp-cache")||"null");
    if(c&&Date.now()-c.stamp<6*60*60*1000){
      for(const r of c.region||[])regional.set(r.id,r);
      for(const r of c.direct||[])direct.set(r.id,r);
      recomputeEstimates();
    }
  }catch(_){}
  showDetailLayers();
  await updateRegional();
  setInterval(()=>{
    try{localStorage.setItem("temp-cache",JSON.stringify({stamp:Date.now(),region:[...regional.values()],direct:[...direct.values()].slice(-600)}));}catch(_){}
    updateRegional();
  },15*60*1000);
  setStatus("Mapa carregado: "+(overview.features||[]).length+" territórios IBGE e "+(df.features||[]).length+" regiões do DF • toque para consultar.");
 }catch(err){setStatus("Erro ao abrir base geográfica: "+err.message);}
}
let moveTimer;
map.on("moveend zoomend",()=>{
 clearTimeout(moveTimer);moveTimer=setTimeout(()=>{showDetailLayers();},250);
});
$("refresh").onclick=async()=>{await updateRegional(true);await updateViewport(true);};
$("loc").onclick=()=>updateViewport(true);
$("all").onclick=()=>map.fitBounds([[-34,-74],[6,-34]]);
$("df").onclick=()=>map.flyTo([-15.78,-47.91],10,{duration:0.7});
function searchList(q){
 if(q.length<2)return [];
 const a=norm(q),v=[...db.values()].filter(x=>norm(x.p.nm).includes(a));
 v.sort((x,y)=>norm(x.p.nm).startsWith(a)===norm(y.p.nm).startsWith(a)?x.p.nm.localeCompare(y.p.nm):norm(x.p.nm).startsWith(a)?-1:1);
 return v.slice(0,30);
}
function chooseSearch(x){
 $("results").hidden=true;$("search").value=x.p.nm;
 map.flyTo([x.lat,x.lon],x.p.kind==="ra"?12:9,{duration:0.6});
 showDetail(x.p);
 const d=direct.get(x.id);if(!d||Date.now()-d.t>TTL_DIRECT)fetchBatch([x],"tap");
}
function search(){
 const v=searchList($("search").value);
 $("results").replaceChildren();
 if(!v.length){$("results").hidden=true;return;}
 for(const x of v){const b=document.createElement("button");b.innerHTML=escapeHtml(x.p.nm)+"<small>"+(x.p.kind==="ra"?"Região administrativa, DF":"Município, "+escapeHtml(x.p.uf))+"</small>";b.onclick=()=>chooseSearch(x);$("results").appendChild(b);}
 $("results").hidden=false;
}
$("search").addEventListener("input",search);
$("go").onclick=()=>{const a=searchList($("search").value);if(a.length)chooseSearch(a[0]);};
$("search").addEventListener("keydown",e=>{if(e.key==="Enter")$("go").click();});
window.addEventListener("online",()=>{setStatus("Conexão restabelecida. Atualizando…");updateRegional(true);});
loadData();
