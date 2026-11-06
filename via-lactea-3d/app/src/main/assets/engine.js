"use strict";
(() => {
  const canvas=document.getElementById("sky");
  const zoomInput=document.getElementById("zoom");
  const info=document.getElementById("distance");
  const level=document.getElementById("level");
  const help=document.getElementById("tip");
  const starCount=document.getElementById("stars");
  const earthBall=document.getElementById("earthBall");
  const labels=document.getElementById("labels");
  const gl=canvas.getContext("webgl",{alpha:false,antialias:false,depth:false,powerPreference:"low-power"});
  if(!gl){document.getElementById("error").textContent="WebGL indisponível neste dispositivo.";return;}
  const LY_AU=63241.077, KLY_AU=63241077, AU_KM=149597870.7, PI=Math.PI;
  let az=PI/2,tilt=0.15,zoom=10.18,desired=10.18,quality=70000,fps=30;
  let w=1,h=1,last=0,pending=false,dirty=true;
  const rad=PI/180;
  const clamp=(x,a,b)=>Math.min(b,Math.max(a,x));
  const mix=(a,b,t)=>a+(b-a)*t;
  const smooth=t=>{t=clamp(t,0,1);return t*t*(3-2*t);};
  const earthEl=document.getElementById("earthBall");
  const shaderV=String.raw
  // Shader strings are constants; WebGL 1.0 / OpenGL ES 2.0.
  const vs='attribute vec3 aPos; attribute vec3 aColor; attribute float aRadius; attribute float aKind; uniform mat4 uView,uProj; uniform float uH,uTan,uPt; varying vec3 vColor; varying float vKind,vSize; void main(){vec4 v=uView*vec4(aPos,1.);gl_Position=uProj*v;float d=max(.00000001,-v.z);float physical=uH*aRadius/(uTan*d);gl_PointSize=clamp(aRadius>0.?physical:uPt,1.,512.);vColor=aColor;vKind=aKind;vSize=gl_PointSize;}';
  const fs='precision mediump float; varying vec3 vColor; varying float vKind,vSize; uniform float uOpacity; void main(){vec2 p=gl_PointCoord*2.-1.;float rr=dot(p,p);if(rr>1.)discard;float z=sqrt(max(0.,1.-rr));vec3 c=vColor;float alpha=uOpacity;if(vKind>.5){float light=.34+.66*max(0.,dot(normalize(vec3(-.40,.55,.72)),vec3(p,z)));c*=light;if(vKind>1.5 && vKind<2.5){float terrain=sin(p.x*17.+sin(p.y*11.))*sin(p.y*13.-p.x*6.)+.42*sin(p.x*28.+p.y*21.);float land=step(.51,terrain);c=mix(vec3(.04,.23,.55),vec3(.13,.48,.23),land);c*=.35+.65*max(0.,dot(normalize(vec3(-.40,.55,.72)),vec3(p,z)));}if(vKind>.5 && vKind<1.5){c=vec3(1.,.80,.37)*(.64+.36*z);}alpha=1.;}else{alpha*=clamp(z*2.,.1,1.);c*=.6+.4*z;}gl_FragColor=vec4(c,alpha);}';
  function shader(type,code){const x=gl.createShader(type);gl.shaderSource(x,code);gl.compileShader(x);if(!gl.getShaderParameter(x,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(x));return x;}
  function program(v,f){const p=gl.createProgram();gl.attachShader(p,shader(gl.VERTEX_SHADER,v));gl.attachShader(p,shader(gl.FRAGMENT_SHADER,f));gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(p));return p;}
  let pr,prLine;
  try{
    pr=program(vs,fs);
    prLine=program('attribute vec3 aPos; uniform mat4 uView,uProj;void main(){gl_Position=uProj*uView*vec4(aPos,1.);}',
       'precision mediump float;uniform vec4 uInk;void main(){gl_FragColor=uInk;}');
  }catch(e){document.getElementById("error").textContent="Erro gráfico: "+e.message;return;}
  const loc={pos:gl.getAttribLocation(pr,"aPos"),color:gl.getAttribLocation(pr,"aColor"),radius:gl.getAttribLocation(pr,"aRadius"),kind:gl.getAttribLocation(pr,"aKind"),view:gl.getUniformLocation(pr,"uView"),proj:gl.getUniformLocation(pr,"uProj"),height:gl.getUniformLocation(pr,"uH"),tan:gl.getUniformLocation(pr,"uTan"),point:gl.getUniformLocation(pr,"uPt"),opacity:gl.getUniformLocation(pr,"uOpacity")};
  const lineLoc={pos:gl.getAttribLocation(prLine,"aPos"),view:gl.getUniformLocation(prLine,"uView"),proj:gl.getUniformLocation(prLine,"uProj"),ink:gl.getUniformLocation(prLine,"uInk")};
  function matrixPerspective(fovy,aspect,near,far){const f=1/Math.tan(fovy/2),o=new Float32Array(16);o[0]=f/aspect;o[5]=f;o[10]=(far+near)/(near-far);o[11]=-1;o[14]=2*far*near/(near-far);return o;}
  const normalize=a=>{const d=Math.hypot(...a)||1;return a.map(x=>x/d);};
  const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  const dot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
  function matrixLook(eye,target,up){
    const z=normalize(eye.map((v,i)=>v-target[i])),x=normalize(cross(up,z)),y=cross(z,x),m=new Float32Array(16);
    m[0]=x[0];m[1]=y[0];m[2]=z[0];m[4]=x[1];m[5]=y[1];m[6]=z[1];
    m[8]=x[2];m[9]=y[2];m[10]=z[2];m[12]=-dot(x,eye);m[13]=-dot(y,eye);m[14]=-dot(z,eye);m[15]=1;
    return m;
  }
  function matVec(m,p){return [m[0]*p[0]+m[4]*p[1]+m[8]*p[2]+m[12]*p[3],m[1]*p[0]+m[5]*p[1]+m[9]*p[2]+m[13]*p[3],m[2]*p[0]+m[6]*p[1]+m[10]*p[2]+m[14]*p[3],m[3]*p[0]+m[7]*p[1]+m[11]*p[2]+m[15]*p[3]];}
  function project(p,v,proj){
    const t=matVec(proj,matVec(v,[p[0],p[1],p[2],1]));if(t[3]<=0)return null;
    const x=(.5+.5*t[0]/t[3])*w,y=(.5-.5*t[1]/t[3])*h;
    if(x < -250 || x>w+250 || y < -250 || y>h+250)return null;
    return [x,y,Math.abs(t[3])];
  }
  let seed=19231999;function rnd(){seed=(Math.imul(1664525,seed)+1013904223)>>>0;return seed/4294967296;}
  const gauss=()=>Math.sqrt(-2*Math.log(Math.max(0.000001,rnd())))*Math.cos(2*PI*rnd());
  function prepareGalaxy(){
    const maxN=132000,a=new Float32Array(maxN*8);
    for(let i=0;i<maxN;i++){
      let r,theta,z;const q=rnd();
      if(q<.22){r=350+Math.pow(rnd(),.70)*12500;theta=2*PI*rnd();z=gauss()*(700+r*.08);}
      else if(q<.91){
        r=2500+Math.sqrt(rnd())*50000;
        theta=Math.floor(rnd()*4)*PI/2+Math.log(r/4200)*4.35+gauss()*(.075+950/r);
        if(rnd()<.21)theta+=gauss()*.35;
        z=gauss()*(180+r*.007);
      }else{r=3000+Math.sqrt(rnd())*57500;theta=2*PI*rnd();z=gauss()*(3800+r*.065);}
      const j=i*8;a[j]=r*Math.cos(theta)/1000;a[j+1]=r*Math.sin(theta)/1000;a[j+2]=z/1000;
      const type=rnd();let c=type<.15?[.47,.67,1]:type<.71?[.86,.90,1]:type<.94?[1,.81,.56]:[1,.49,.35];
      const b=.38+.62*rnd();a[j+3]=c[0]*b;a[j+4]=c[1]*b;a[j+5]=c[2]*b;a[j+6]=0;a[j+7]=0;
    }
    return a;
  }
  const galaxyData=prepareGalaxy(),galaxyBuffer=gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER,galaxyBuffer);gl.bufferData(gl.ARRAY_BUFFER,galaxyData,gl.STATIC_DRAW);
  const galaxyFocus=gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER,galaxyFocus);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([
    26.3,0,.035,1,.93,.48,0,0,
    0,0,0,1,.81,.52,0,0
  ]),gl.STATIC_DRAW);
  const AU_PER_LY=LY_AU;
  const starCatalog=[
    ["Próxima Centauri",4.2465,217.429,-62.679],
    ["Alfa Centauri",4.37,219.902,-60.834],
    ["Estrela de Barnard",5.96,269.454,4.693],
    ["Sirius",8.60,101.287,-16.716],
    ["Épsilon Eridani",10.50,53.233,-9.458],
    ["Procyon",11.46,114.825,5.225],
    ["Altair",16.73,297.695,8.868],
    ["Vega",25.04,279.234,38.784]
  ].map(s=>{const ra=s[2]*rad,de=s[3]*rad,d=s[1]*AU_PER_LY;return {name:s[0],dist:s[1],xyz:[d*Math.cos(de)*Math.cos(ra),d*Math.cos(de)*Math.sin(ra),d*Math.sin(de)]};});
  const planets=[
    ["Mercúrio",.3871,2439.7,.24,87.97,0,[.66,.68,.70]],
    ["Vênus",.7233,6051.8,1.40,224.70,0,[.96,.79,.48]],
    ["Terra",1,6371,2.02,365.25,1,[.11,.47,.94]],
    ["Marte",1.5237,3389.5,2.64,686.98,0,[.92,.38,.22]],
    ["Júpiter",5.2028,69911,3.33,4332.6,0,[.88,.68,.48]],
    ["Saturno",9.538,58232,4.12,10759,0,[.92,.79,.57]],
    ["Urano",19.191,25362,5.61,30687,0,[.49,.84,.87]],
    ["Netuno",30.07,24622,5.89,60190,0,[.30,.51,.97]]
  ];
  const days=(Date.now()-Date.UTC(2026,0,1))/86400000;
  planets.forEach(p=>{const angle=p[3]+2*PI*days/p[4];p.xyz=[p[1]*Math.cos(angle),p[1]*Math.sin(angle),0];});
  const earth=planets[2];
  const solarEntries=[{name:"Sol",xyz:[0,0,0],r:696340/AU_KM,c:[1,.81,.42],kind:1}];
  for(const p of planets)solarEntries.push({name:p[0],xyz:p.xyz,r:p[2]/AU_KM,c:p[6],kind:p[5]?2:3});
  for(const s of starCatalog)solarEntries.push({name:s.name,xyz:s.xyz,r:0,c:[.78,.90,1],kind:0});
  const solarData=new Float32Array(solarEntries.length*8);
  for(let i=0;i<solarEntries.length;i++){
    const s=solarEntries[i],o=i*8;
    solarData.set([s.xyz[0],s.xyz[1],s.xyz[2],s.c[0],s.c[1],s.c[2],s.r,s.kind],o);
  }
  const solarBuffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,solarBuffer);gl.bufferData(gl.ARRAY_BUFFER,solarData,gl.STATIC_DRAW);
  const orbitBuffers=planets.map(p=>{
    const pts=new Float32Array(128*3);
    for(let j=0;j<128;j++){const a=2*PI*j/128;pts[3*j]=p[1]*Math.cos(a);pts[3*j+1]=p[1]*Math.sin(a);pts[3*j+2]=0;}
    const b=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,b);gl.bufferData(gl.ARRAY_BUFFER,pts,gl.STATIC_DRAW);return b;
  });
  function bindPoints(buffer,view,proj,pt,opacity){
    gl.useProgram(pr);gl.bindBuffer(gl.ARRAY_BUFFER,buffer);
    gl.enableVertexAttribArray(loc.pos);gl.vertexAttribPointer(loc.pos,3,gl.FLOAT,false,32,0);
    gl.enableVertexAttribArray(loc.color);gl.vertexAttribPointer(loc.color,3,gl.FLOAT,false,32,12);
    gl.enableVertexAttribArray(loc.radius);gl.vertexAttribPointer(loc.radius,1,gl.FLOAT,false,32,24);
    gl.enableVertexAttribArray(loc.kind);gl.vertexAttribPointer(loc.kind,1,gl.FLOAT,false,32,28);
    gl.uniformMatrix4fv(loc.view,false,view);gl.uniformMatrix4fv(loc.proj,false,proj);
    gl.uniform1f(loc.height,canvas.height);gl.uniform1f(loc.tan,Math.tan(54*rad/2));
    gl.uniform1f(loc.point,pt);gl.uniform1f(loc.opacity,opacity);
  }
  function orbitDraw(view,proj){gl.useProgram(prLine);gl.uniformMatrix4fv(lineLoc.view,false,view);gl.uniformMatrix4fv(lineLoc.proj,false,proj);
    gl.uniform4f(lineLoc.ink,.25,.42,.63,.30);gl.enableVertexAttribArray(lineLoc.pos);
    planets.forEach((p,i)=>{gl.bindBuffer(gl.ARRAY_BUFFER,orbitBuffers[i]);gl.vertexAttribPointer(lineLoc.pos,3,gl.FLOAT,false,12,0);gl.drawArrays(gl.LINE_LOOP,0,128);});
  }
  function camera(dist,target){
    const eye=[target[0]+dist*Math.sin(tilt)*Math.cos(az),target[1]+dist*Math.sin(tilt)*Math.sin(az),target[2]+dist*Math.cos(tilt)];
    const up=[Math.cos(tilt)*Math.cos(az),Math.cos(tilt)*Math.sin(az),-Math.sin(tilt)];
    return matrixLook(eye,target,up);
  }
  function label(name,pos,view,proj){
    const p=project(pos,view,proj);
    if(!p)return;
    const x=p[0],y=p[1];
    const el=document.createElement("span");el.className="space-label";el.textContent=name;el.style.left=x+"px";el.style.top=y+"px";labels.appendChild(el);
  }
  function fmt(d){
    if(d>=LY_AU){const v=d/LY_AU;return v.toLocaleString("pt-BR",{maximumFractionDigits:v<10?2:0})+" anos-luz";}
    if(d>=.1)return d.toLocaleString("pt-BR",{maximumFractionDigits:2})+" UA";
    return (d*AU_KM).toLocaleString("pt-BR",{maximumFractionDigits:0})+" km";
  }
  function render(){
    dirty=false;
    if(zoom!==desired){zoom+=((desired-zoom)*.19);if(Math.abs(zoom-desired)<.004)zoom=desired;}
    zoomInput.value=desired;
    const distanceAU=Math.pow(10,zoom);
    const galAlpha=smooth((zoom-5.6)/.85),solAlpha=smooth((6.6-zoom)/.85);
    gl.viewport(0,0,canvas.width,canvas.height);gl.clearColor(.003,.007,.017,1);gl.clear(gl.COLOR_BUFFER_BIT);
    labels.replaceChildren();earthEl.style.display="none";
    const viewSize=54*rad,aspect=canvas.width/canvas.height;
    if(galAlpha>.001){
      const blend=smooth((10.1-zoom)/2.35),target=[26.3*blend,0,0],dist=distanceAU/KLY_AU;
      const view=camera(dist,target),proj=matrixPerspective(viewSize,aspect,Math.max(1e-8,dist/50000),Math.max(350,dist*8));
      gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE);
      bindPoints(galaxyBuffer,view,proj,1.70,galAlpha*.80);
      gl.drawArrays(gl.POINTS,0,quality);
      bindPoints(galaxyFocus,view,proj,5.2,galAlpha);
      gl.drawArrays(gl.POINTS,0,2);
      if(zoom>7.1)label("☀ Sol",[26.3,0,.035],view,proj);
      if(zoom>9.05)label("Centro galáctico",[0,0,0],view,proj);
    }
    if(solAlpha>.001){
      const focus=smooth((.85-zoom)/1.52),tar=[earth.xyz[0]*focus,earth.xyz[1]*focus,0];
      const view=camera(distanceAU,tar),proj=matrixPerspective(viewSize,aspect,Math.max(1e-9,distanceAU/50000),Math.max(50,distanceAU*12));
      gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
      if(distanceAU<140)orbitDraw(view,proj);
      bindPoints(solarBuffer,view,proj,3.6,solAlpha);
      gl.drawArrays(gl.POINTS,0,solarEntries.length);
      if(distanceAU<20000)label("☀ Sol",[0,0,0],view,proj);
      if(distanceAU<100)for(const p of planets)label(p[0],p.xyz,view,proj);
      if(distanceAU>15000 && distanceAU<1500000)for(const s of starCatalog)label(s.name,s.xyz,view,proj);
      if(distanceAU<.015){
        const pos=project(earth.xyz,view,proj);
        if(pos){
          const diam=canvas.height*(earth[2]/AU_KM)/(Math.tan(viewSize/2)*pos[2]);
          const maxPoint=gl.getParameter(gl.ALIASED_POINT_SIZE_RANGE)[1];
          if(diam>maxPoint*.8){
            earthEl.style.display="block";earthEl.style.left=pos[0]+"px";earthEl.style.top=pos[1]+"px";
            earthEl.style.width=Math.max(2,Math.min(1600,diam))+"px";
            earthEl.style.height=Math.max(2,Math.min(1600,diam))+"px";
          }
        }
      }
    }
    info.textContent=fmt(distanceAU);
    level.textContent=zoom>7.7?"VIA LÁCTEA":zoom>5.4?"VIZINHANÇA SOLAR":zoom>1.85?"SISTEMA SOLAR":zoom>-.2?"PLANETAS":"PLANETA TERRA";
    if(zoom>8.2)help.textContent="Pontos simulados para representar a estrutura da galáxia";
    else if(zoom>4.5)help.textContent="Estrelas próximas com distância e direção astronômica aproximadas";
    else help.textContent="Distâncias e tamanhos em escala; órbitas circulares e fases ilustrativas";
    if(zoom!==desired)schedule();
  }
  function loop(time){
    pending=false;
    if(time-last <1000/fps-1){schedule();return;}
    last=time;render();
  }
  function schedule(){if(!pending){pending=true;requestAnimationFrame(loop);}}
  function setZoom(x){desired=clamp(x,-4.30,10.38);zoomInput.value=desired;schedule();}
  const active=new Map();let pinch=0;
  canvas.addEventListener("pointerdown",e=>{e.preventDefault();canvas.setPointerCapture(e.pointerId);active.set(e.pointerId,[e.clientX,e.clientY]);pinch=0;});
  canvas.addEventListener("pointermove",e=>{
    if(!active.has(e.pointerId))return;e.preventDefault();
    const old=active.get(e.pointerId);active.set(e.pointerId,[e.clientX,e.clientY]);
    if(active.size===1){az-=.006*(e.clientX-old[0]);tilt=clamp(tilt+.005*(e.clientY-old[1]),0,1.48);schedule();}
    else if(active.size===2){
      const p=[...active.values()],d=Math.hypot(p[0][0]-p[1][0],p[0][1]-p[1][1]);
      if(pinch>0)setZoom(desired-1.45*Math.log10(d/pinch));pinch=d;
    }
  });
  const stop=e=>{active.delete(e.pointerId);pinch=0;};
  canvas.addEventListener("pointerup",stop);canvas.addEventListener("pointercancel",stop);
  canvas.addEventListener("wheel",e=>{e.preventDefault();setZoom(desired+Math.sign(e.deltaY)*.19);},{passive:false});
  zoomInput.addEventListener("input",e=>setZoom(parseFloat(e.target.value)));
  document.querySelectorAll("[data-place]").forEach(b=>b.addEventListener("click",()=>{setZoom(parseFloat(b.dataset.place));}));
  starCount.addEventListener("change",e=>{quality=parseInt(e.target.value,10);schedule();});
  document.getElementById("fps").addEventListener("change",e=>{fps=parseInt(e.target.value,10);schedule();});
  document.getElementById("reset").addEventListener("click",()=>{az=PI/2;tilt=.15;setZoom(10.18);});
  const maxDpr=Math.min(1.5,window.devicePixelRatio||1);
  function resize(){w=window.innerWidth;h=window.innerHeight;canvas.width=Math.floor(w*maxDpr);canvas.height=Math.floor(h*maxDpr);canvas.style.width=w+"px";canvas.style.height=h+"px";schedule();}
  window.addEventListener("resize",resize);
  canvas.addEventListener("webglcontextlost",e=>{e.preventDefault();document.getElementById("error").textContent="Contexto gráfico perdido. Reinicie o aplicativo.";});
  resize();setZoom(10.18);
})();