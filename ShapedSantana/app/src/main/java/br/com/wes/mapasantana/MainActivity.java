package br.com.wes.mapasantana;
import android.app.*;import android.os.*;import android.view.*;import android.widget.*;import android.graphics.*;import android.content.*;
import org.json.*;import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.zip.GZIPInputStream;
public class MainActivity extends Activity{
 static final double MIN=.035,MAX=350,CX=-42.54,CY=-19.38;
 LinearLayout root;TextView label,info;SeekBar slider;Mapa map;boolean busy=false;
 static String query="[out:json][timeout:90];area[\"name\"=\"Santana do Paraíso\"][\"boundary\"=\"administrative\"][\"admin_level\"=\"8\"]->.a;way[\"highway\"](area.a);out tags geom;";
 static double x(double lon){return(lon-CX)*104.9;}static double y(double lat){return(CY-lat)*111.2;}
 static double value(int p){return MIN*Math.pow(MAX/MIN,p/100.0);}
 static int position(double z){return Math.max(0,Math.min(100,(int)(100*Math.log(z/MIN)/Math.log(MAX/MIN))));}
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(0xff071623);getWindow().setNavigationBarColor(0xff071623);
 root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xff071623);
 TextView title=new TextView(this);title.setText("SANTANA DO PARAÍSO  •  RUAS");title.setTextColor(0xffe4fcf4);title.setTextSize(20);title.setPadding(25,25,8,5);title.setTypeface(null,1);root.addView(title);
 TextView sub=new TextView(this);sub.setText("Mapa vetorial • ruas preservadas ao afastar");sub.setTextColor(0xff91b8b3);sub.setTextSize(12);sub.setPadding(25,0,8,12);root.addView(sub);
 map=new Mapa(this);root.addView(map,new LinearLayout.LayoutParams(-1,0,1));
 label=new TextView(this);label.setTextColor(-1);label.setPadding(20,8,8,2);root.addView(label);
 LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);
 Button minus=new Button(this);minus.setText("−");controls.addView(minus);
 slider=new SeekBar(this);slider.setMax(100);slider.setProgress(position(1));controls.addView(slider,new LinearLayout.LayoutParams(0,60,1));
 Button plus=new Button(this);plus.setText("+");controls.addView(plus);root.addView(controls);
 LinearLayout bottom=new LinearLayout(this);bottom.setGravity(Gravity.CENTER_VERTICAL);
 info=new TextView(this);info.setText("Preparando ruas...");info.setTextColor(0xffa5c3c8);info.setTextSize(11);info.setPadding(14,0,0,8);bottom.addView(info,new LinearLayout.LayoutParams(0,-2,1));
 Button reset=new Button(this);reset.setText("Centro");bottom.addView(reset);Button update=new Button(this);update.setText("Atualizar");bottom.addView(update);root.addView(bottom);
 TextView credit=new TextView(this);credit.setText("© OpenStreetMap contributors • ODbL");credit.setTextColor(0xff85a3b4);credit.setTextSize(10);credit.setPadding(14,0,0,9);root.addView(credit);setContentView(root);
 slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean from){map.zoom=value(p);map.invalidate();showZoom();}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
 minus.setOnClickListener(v->slider.setProgress(Math.max(0,slider.getProgress()-3)));plus.setOnClickListener(v->slider.setProgress(Math.min(100,slider.getProgress()+3)));
 reset.setOnClickListener(v->{map.panX=map.panY=0;slider.setProgress(position(1));map.invalidate();});
 update.setOnClickListener(v->load(true));showZoom();load(false);
 }
 void showZoom(){if(map==null||label==null)return;double km=map.widthKm();label.setText("Largura visível ≈ "+(km<1?String.format(java.util.Locale.US,"%.0f m",km*1000):String.format(java.util.Locale.US,"%.1f km",km)));}
 void status(String s){runOnUiThread(()->info.setText(s));}
 static byte[] read(InputStream f)throws Exception{try(InputStream in=f;ByteArrayOutputStream b=new ByteArrayOutputStream()){byte[] buf=new byte[16384];int n;while((n=in.read(buf))!=-1){b.write(buf,0,n);if(b.size()>30000000)throw new IOException("Arquivo grande demais");}return b.toByteArray();}}
 void load(boolean force){if(busy)return;busy=true;new Thread(()->{try{byte[] bytes=null;File cache=new File(getFilesDir(),"vias.json");if(!force){if(cache.exists())bytes=read(new FileInputStream(cache));if(bytes==null)try{bytes=read(getAssets().open("roads.json"));}catch(Exception e){}}
 if(bytes==null){status("Baixando ruas reais...");bytes=download();try(FileOutputStream o=new FileOutputStream(cache)){o.write(bytes);}}
 JSONArray a=new JSONObject(new String(bytes,StandardCharsets.UTF_8)).getJSONArray("elements");ArrayList<Via> roads=new ArrayList<>();float minx=Float.MAX_VALUE,maxx=-Float.MAX_VALUE,miny=Float.MAX_VALUE,maxy=-Float.MAX_VALUE;
 for(int i=0;i<a.length();i++){JSONObject w=a.optJSONObject(i);if(w==null||!w.optString("type").equals("way"))continue;JSONArray g=w.optJSONArray("geometry");if(g==null||g.length()<2)continue;Via via=new Via();via.path=new Path();JSONObject tags=w.optJSONObject("tags");via.name=tags==null?"":tags.optString("name");String kind=tags==null?"":tags.optString("highway");via.rank=kind.equals("primary")||kind.equals("trunk")||kind.equals("motorway")?2:kind.equals("secondary")||kind.equals("tertiary")?1:0;
 for(int j=0;j<g.length();j++){JSONObject p=g.getJSONObject(j);float xx=(float)x(p.getDouble("lon")),yy=(float)y(p.getDouble("lat"));if(j==0)via.path.moveTo(xx,yy);else via.path.lineTo(xx,yy);if(j==g.length()/2){via.midX=xx;via.midY=yy;}minx=Math.min(minx,xx);maxx=Math.max(maxx,xx);miny=Math.min(miny,yy);maxy=Math.max(maxy,yy);}roads.add(via);}
 if(roads.size()<30)throw new IOException("Não foi possível obter as ruas do município");
 final float a1=minx,a2=maxx,b1=miny,b2=maxy;
 runOnUiThread(()->{map.roads=roads;map.minX=a1;map.maxX=a2;map.minY=b1;map.maxY=b2;map.calc();map.invalidate();info.setText(roads.size()+" trechos • zoom livre • arraste o mapa");showZoom();});
 }catch(Exception e){status("Falha ao obter ruas. Toque em Atualizar.");}finally{busy=false;}}).start();}
 byte[] download()throws Exception{String[] endpoints={"https://overpass.kumi.systems/api/interpreter","https://overpass-api.de/api/interpreter","https://overpass.nchc.org.tw/api/interpreter"};Exception last=null;
 for(String endpoint:endpoints){HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(endpoint).openConnection();c.setDoOutput(true);c.setRequestMethod("POST");c.setConnectTimeout(10000);c.setReadTimeout(55000);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");c.setRequestProperty("Accept-Encoding","gzip");c.setRequestProperty("User-Agent","SantanaMapa/1.0");byte[] data=("data="+URLEncoder.encode(query,"UTF-8")).getBytes(StandardCharsets.UTF_8);try(OutputStream o=c.getOutputStream()){o.write(data);}if(c.getResponseCode()!=200)throw new IOException("HTTP "+c.getResponseCode());InputStream stream=c.getInputStream();if("gzip".equalsIgnoreCase(c.getContentEncoding()))stream=new GZIPInputStream(stream);byte[] v=read(stream);if(new JSONObject(new String(v,StandardCharsets.UTF_8)).optJSONArray("elements")==null)throw new IOException("Resposta inválida");return v;}catch(Exception e){last=e;}finally{if(c!=null)c.disconnect();}}
 throw new IOException("Não foi possível acessar o serviço de mapas",last);
 }
 static class Via{Path path;int rank;String name;float midX,midY;}
 class Mapa extends View{
 ArrayList<Via> roads=new ArrayList<>();Paint paint=new Paint(3);float minX=-15,maxX=15,minY=-12,maxY=12,midX=0,midY=0,base=1,panX=0,panY=0,lastX,lastY;double zoom=1;ScaleGestureDetector pinch;
 Mapa(Context c){super(c);pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector s){double next=Math.max(MIN,Math.min(MAX,zoom*s.getScaleFactor()));float ratio=(float)(next/zoom);panX=s.getFocusX()-getWidth()/2f-(s.getFocusX()-getWidth()/2f-panX)*ratio;panY=s.getFocusY()-getHeight()/2f-(s.getFocusY()-getHeight()/2f-panY)*ratio;zoom=next;slider.setProgress(position(zoom));invalidate();return true;}});}
 void calc(){midX=(minX+maxX)/2;midY=(minY+maxY)/2;if(getWidth()>0&&getHeight()>0){base=Math.min(getWidth()/Math.max(1,maxX-minX),getHeight()/Math.max(1,maxY-minY))*.84f;}}
 @Override protected void onSizeChanged(int w,int h,int ow,int oh){calc();showZoom();}
 double widthKm(){return getWidth()>0?getWidth()/Math.max(.0001,base*zoom):30;}
 @Override protected void onDraw(Canvas c){c.drawColor(0xff081928);float sc=(float)(base*zoom);if(sc<=0)return;
 c.save();c.translate(getWidth()/2f+panX,getHeight()/2f+panY);c.scale(sc,sc);c.translate(-midX,-midY);
 paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
 // Every road is rendered at every zoom: there is intentionally no zoom-dependent filtering.
 for(int level=0;level<3;level++){paint.setColor(level==2?0xffffcf76:level==1?0xff4ae6c5:0xff9cb9c8);paint.setStrokeWidth(Math.max(.7f/sc,level==2?.019f:.009f));for(Via v:roads)if(v.rank==level)c.drawPath(v.path,paint);}
 c.restore();paint.setColor(0xff9abdc3);paint.setTextSize(25);paint.setStyle(Paint.Style.FILL);c.drawText("Santana do Paraíso",22,36,paint);
 if(zoom>6){paint.setColor(0xffedfefa);paint.setTextSize(22);int count=0;for(Via v:roads){if(v.name==null||v.name.isEmpty()||v.name.length()>30)continue;float xx=(v.midX-midX)*sc+getWidth()/2f+panX,yy=(v.midY-midY)*sc+getHeight()/2f+panY;if(xx<45||xx>getWidth()-45||yy<20||yy>getHeight()-20)continue;if(++count%4==0)c.drawText(v.name,xx,yy,paint);if(count>600)break;}}
 }
 @Override public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);switch(e.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=e.getX();lastY=e.getY();return true;case MotionEvent.ACTION_MOVE:if(!pinch.isInProgress()&&e.getPointerCount()==1){panX+=e.getX()-lastX;panY+=e.getY()-lastY;invalidate();}lastX=e.getX();lastY=e.getY();return true;case MotionEvent.ACTION_POINTER_UP:lastX=e.getX();lastY=e.getY();return true;}return true;}
 }
}