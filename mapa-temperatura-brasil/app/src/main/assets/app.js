"use strict";
const $ = id => document.getElementById(id);
const escapeHtml = s => String(s || "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const setStatus = s => { $("status").textContent = s; };
const norm = s => String(s||"").normalize("NFD").replace(/[\u0300-\u036f]/g,"").toLowerCase().trim();
const map = L.map("map",{preferCanvas:true,zoomControl:false,attributionControl:false,minZoom:3,maxZoom:14,worldCopyJump:false,zoomSnap:0.25});
L.control.zoom({position:"bottomright"}).addTo(map);
map.fitBounds([[-34,-74],[6,-34]]);
const renderer=L.canvas({padding:0.3});
const db=new Map(), direct=new Map(), regional=new Map(), detailLayers=new Map(), stateBounds={};
let overviewLayer, dfLayer, stateOutline, selected=null, stopped=false;
let regionStamp=0, busy=false, timer=null;
const TTL_DIRECT=25*60*1000, TTL_REGION=3*60*60*1000;
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
function valueAt(p){
 const exact=direct.get(String(p.id));
 if(exact && Date.now()-exact.t<12*60*60*1000)return {t:exact.v,exact:true,date:exact.t,modelTime:exact.mt};
 if(regional.size===0)return {t:null,exact:false,date:null};
 const v=representative(p), nearest=[...regional.values()].map(x=>({x,d:degreesDistance(x,v)})).sort((a,b)=>a.d-b.d).slice(0,3);
 if(!nearest.length)return {t:null,exact:false,date:null};
 if(nearest[0].d<0.5)return {t:nearest[0].x.v,exact:false,date:nearest[0].x.t};
 let w=0,s=0;nearest.forEach(({x,d})=>{let k=1/Math.max(2,d)**2;w+=k;s+=k*x.v;});
 return {t:s/w,exact:false,date:Math.max(...nearest.map(x=>x.x.t))};
}
function style(f){
 const p=f.properties||{}, v=valueAt(p), isDF=String(p.id)==="5300108";
 return {renderer,fill:true,fillColor:toColor(v.t),fillOpacity:1,color:"#fff",weight:map.getZoom()>8?0.7:0.32,opacity:0.9,interactive:!(isDF && map.getZoom()>=8 && !!dfLayer)};
}
function refreshStyles(){
 if(overviewLayer)overviewLayer.setStyle(style);
 for(const layer of detailLayers.values())layer.setStyle(style);
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
 if(z<6.5){for(const layer of detailLayers.values())map.removeLayer(layer);}
 else {
  for(const [uf,b] of Object.entries(stateBounds)){
   if(uf==="DF")continue;
   if(boundsIntersect(b,bounds)){
    if(detailLayers.has(uf)){detailLayers.get(uf).addTo(map);continue;}
    detailLayers.set(uf,{placeholder:true});
    fetch("mapa/estados/"+uf+".geojson").then(r=>{if(!r.ok)throw Error(r.status);return r.json();}).then(g=>{
     const layer=addLayer(g);detailLayers.set(uf,layer);
     if(map.getZoom()>=6.5&&boundsIntersect(b,map.getBounds()))layer.addTo(map);
     reorderOutline();
    }).catch(()=>detailLayers.delete(uf));
   }else if(detailLayers.has(uf)&&detailLayers.get(uf).remove)map.removeLayer(detailLayers.get(uf));
  }
 }
 if(dfLayer){if(z>=8 && boundsIntersect([-16.1,-48.35,-15.45,-47.25],bounds))dfLayer.addTo(map);else map.removeLayer(dfLayer);}
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
    if(Number.isFinite(t)&&p){
      const row={...p,v:t,t:now,mt:ob.current.time};
      if(kind==="regional")regional.set(String(p.id),row);
      else direct.set(String(p.id),row);
    }
   });
   regionStamp=now;
   queueStyle();
   if(selected)showDetail(selected);
   setStatus((kind==="regional"?"Mapa geral":"Área em detalhe")+" atualizado às "+prettyTime(now)+" • toque em um território.");
  }catch(err){setStatus("Consulta indisponível ("+err.message+"). Mantendo últimas cores.");}
 }
}
function chooseSamples(){
 const cells=new Map();
 for(const x of db.values()){
  if(x.p.kind==="ra")continue;
  const latbin=Math.floor((x.lat+35)/3),lonbin=Math.floor((x.lon+75)/3);
  const key=latbin+":"+lonbin;
  const midLat=-35+latbin*3+1.5,midLon=-75+lonbin*3+1.5,d=Math.abs(x.lat-midLat)+Math.abs(x.lon-midLon);
  if(!cells.has(key)||d<cells.get(key).d)cells.set(key,{...x,d});
 }
 const a=[...cells.values()].map(x=>({id:x.id,lat:x.lat,lon:x.lon,p:x.p}));
 return a.length<=150?a:a.filter((_,i)=>i%Math.ceil(a.length/150)===0);
}
async function updateRegional(){
 if(busy)return;
 const last=Number(localStorage.getItem("temp-last-regional")||0);
 if(Date.now()-last<TTL_REGION && regional.size)return;
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
 if(typeof L==="undefined"){setStatus("Não foi possível carregar o mapa. Verifique a conexão com a internet.");return;}
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
  map.removeLayer(dfLayer);
  stateOutline=L.geoJSON(outlines,{interactive:false,style:{fill:false,color:"#155c8a",weight:1.2,opacity:.9}}).addTo(map);
  // Restore cached exact and coarse temperatures for an immediately useful display.
  try{
    const c=JSON.parse(localStorage.getItem("temp-cache")||"null");
    if(c&&Date.now()-c.stamp<6*60*60*1000){
      for(const r of c.region||[])regional.set(r.id,r);
      for(const r of c.direct||[])direct.set(r.id,r);
    }
  }catch(_){}
  showDetailLayers();
  await updateRegional();
  await updateViewport();
  setInterval(()=>{
    try{localStorage.setItem("temp-cache",JSON.stringify({stamp:Date.now(),region:[...regional.values()],direct:[...direct.values()].slice(-600)}));}catch(_){}
    updateRegional();updateViewport();
  },15*60*1000);
  setStatus("Mapa carregado: "+(overview.features||[]).length+" territórios IBGE e "+(df.features||[]).length+" regiões do DF • toque para consultar.");
 }catch(err){setStatus("Erro ao abrir base geográfica: "+err.message);}
}
let moveTimer;
map.on("moveend zoomend",()=>{
 clearTimeout(moveTimer);moveTimer=setTimeout(()=>{showDetailLayers();updateViewport();},650);
});
$("refresh").onclick=async()=>{await updateRegional();await updateViewport(true);};
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
window.addEventListener("online",()=>{setStatus("Conexão restabelecida. Atualizando…");updateRegional();updateViewport();});
loadData();
