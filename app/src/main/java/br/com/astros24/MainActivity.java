package br.com.astros24;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final long NS = 1000000000L;
    private static final int BG=0xff071020, FG=0xffe9edff, SUB=0xffabb8d5, BLUE=0xff9ab6ff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private HandlerThread thread; private Handler handler;
    private CameraDevice camera; private CameraCaptureSession session;
    private ImageReader reader; private Surface previewSurface;
    private Range<Long> exposureRange; private Range<Integer> isoRange;
    private TextureView preview;
    private Spinner duration, seconds, sensitivity, mode;
    private Button start, stop; private TextView status, info; private ProgressBar bar;
    private volatile boolean running=false, processing=false, cancel=false;
    private volatile boolean gotImage=false, gotResult=false;
    private volatile long actualExposureNs;
    private long selectedExposureNs, startedAt, durationMs, totalExposureNs;
    private int iso, selectedMode, orientation, alignFailures;
    private final ArrayList<File> frames=new ArrayList<>();
    private File folder;
    private boolean ready=false;

    private TextView text(String value, int size, int color) {
        TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setPadding(0, dp(5), 0, dp(5)); return t;
    }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    private Spinner spin(String... choices) {
        Spinner s=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);
        s.setAdapter(a); return s;
    }
    private void message(String s) { runOnUiThread(()->status.setText(s)); }
    private void detail(String s) { runOnUiThread(()->info.setText(s)); }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(BG);
        LinearLayout col=new LinearLayout(this); col.setPadding(dp(18),dp(18),dp(18),dp(28));
        col.setOrientation(LinearLayout.VERTICAL); scroll.addView(col); setContentView(scroll);
        TextView header=text("✦ ASTRO S24",27,FG); header.setTypeface(null,1); col.addView(header);
        col.addView(text("Fotografia do céu noturno • Camera2 • processamento local",13,SUB));
        preview=new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h) { open(); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t) {}
        });
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(230));
        pp.setMargins(0,dp(12),0,dp(12)); col.addView(preview,pp);
        col.addView(text("Tempo total da sessão",15,FG));
        duration=spin("10 minutos","20 minutos","30 minutos"); col.addView(duration);
        col.addView(text("Exposição por foto (limitada pelo S24)",15,FG));
        seconds=spin("10 segundos","5 segundos","15 segundos","20 segundos","30 segundos"); col.addView(seconds);
        col.addView(text("Sensibilidade ISO",15,FG));
        sensitivity=spin("800","400","1600","3200"); col.addView(sensitivity);
        col.addView(text("Combinação das fotos",15,FG));
        mode=spin("Estrelas alinhadas (experimental)","Rastros luminosos"); col.addView(mode);
        start=new Button(this); start.setText("COMEÇAR A FOTOGRAFAR");
        start.setBackgroundTintList(ColorStateList.valueOf(BLUE)); start.setTextColor(BG);
        start.setEnabled(false); col.addView(start); start.setOnClickListener(v->begin());
        stop=new Button(this); stop.setText("PARAR E PROCESSAR"); stop.setVisibility(View.GONE);
        col.addView(stop); stop.setOnClickListener(v->{cancel=true;stop.setEnabled(false);message("Concluindo a fotografia atual…");});
        status=text("Solicitando acesso à câmera…",15,BLUE);col.addView(status);
        info=text("Use um tripé firme. Deixe o aplicativo aberto e a tela ligada durante a sessão.",13,SUB);col.addView(info);
        bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);col.addView(bar);
        col.addView(text("10/20/30 minutos significam várias fotografias somadas, não uma única exposição. Foco infinito disponível quando o sensor permitir.",12,SUB));
        thread=new HandlerThread("AstroCameraThread");thread.start();handler=new Handler(thread.getLooper());
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},33);
        else if(preview.isAvailable())open();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==33 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)open();
        else if(request==33)message("Permissão da câmera necessária.");
    }

    private void open() {
        if(camera!=null || reader!=null || handler==null || !preview.isAvailable())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try {
            CameraManager mgr=(CameraManager)getSystemService(Context.CAMERA_SERVICE);
            String id=null; CameraCharacteristics best=null;
            for(String candidate:mgr.getCameraIdList()) {
                CameraCharacteristics c=mgr.getCameraCharacteristics(candidate);
                Integer facing=c.get(CameraCharacteristics.LENS_FACING);
                if(facing==null || facing!=CameraCharacteristics.LENS_FACING_BACK)continue;
                int[] caps=c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                if(caps==null)continue;
                boolean manual=false;
                for(int cap:caps) if(cap==CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)manual=true;
                if(manual && c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!=null) {
                    id=candidate;best=c;break;
                }
            }
            if(id==null){message("O Android não liberou uma câmera traseira com exposição manual.");return;}
            exposureRange=best.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            isoRange=best.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Integer angle=best.get(CameraCharacteristics.SENSOR_ORIENTATION);
            orientation=angle==null?90:angle;
            StreamConfigurationMap map=best.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if(map==null){message("Formatos da câmera indisponíveis.");return;}
            Size[] all=map.getOutputSizes(ImageFormat.JPEG);
            if(all==null || all.length==0){message("JPEG indisponível.");return;}
            Size selected=all[0];
            for(Size size:all) {
                long px=(long)size.getWidth()*size.getHeight();
                long old=(long)selected.getWidth()*selected.getHeight();
                if(px<=13000000L && (old>13000000L || px>old))selected=size;
            }
            final String chosen=id;
            reader=ImageReader.newInstance(selected.getWidth(),selected.getHeight(),ImageFormat.JPEG,2);
            reader.setOnImageAvailableListener(r->onImage(r),handler);
            detail(String.format(Locale.getDefault(),
                "Câmera %s • JPEG %dx%d • exposição máxima por foto %.2f s",chosen,
                selected.getWidth(),selected.getHeight(),exposureRange.getUpper()/1e9));
            mgr.openCamera(chosen,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice c){camera=c;configure();}
                @Override public void onDisconnected(CameraDevice c){message("Câmera desconectada");if(running)cancel=true;close();}
                @Override public void onError(CameraDevice c,int code){message("Erro de câmera "+code);if(running)cancel=true;close();}
            },handler);
        } catch(Exception ex){message("Não foi possível abrir a câmera: "+ex.getMessage());}
    }
    private void configure() {
        if(camera==null || !preview.isAvailable() || reader==null)return;
        SurfaceTexture tex=preview.getSurfaceTexture(); if(tex==null)return;
        tex.setDefaultBufferSize(1280,720);
        previewSurface=new Surface(tex);
        try {
            camera.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
                @Override public void onConfigured(CameraCaptureSession s){
                    session=s; ready=true;
                    try{
                        CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(previewSurface);
                        b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(b.build(),null,handler);
                    }catch(Exception e){message("Prévia não disponível: "+e.getMessage());}
                    runOnUiThread(()->{start.setEnabled(true);status.setText("Câmera pronta para fotografar.");});
                }
                @Override public void onConfigureFailed(CameraCaptureSession s){message("Não foi possível preparar a câmera.");}
            },handler);
        }catch(Exception e){message("Erro ao configurar câmera: "+e.getMessage());}
    }

    private void begin() {
        if(!ready || running || processing || camera==null){message("Câmera ainda não está pronta.");return;}
        long[] exposureOptions={10,5,15,20,30};
        int[] isoOptions={800,400,1600,3200};
        long desired=exposureOptions[seconds.getSelectedItemPosition()]*NS;
        selectedExposureNs=Math.min(exposureRange.getUpper(),Math.max(exposureRange.getLower(),desired));
        if(selectedExposureNs<NS){message("Esta câmera só aceita exposições inferiores a 1 segundo.");return;}
        iso=isoOptions[sensitivity.getSelectedItemPosition()];
        if(isoRange!=null)iso=Math.min(isoRange.getUpper(),Math.max(isoRange.getLower(),iso));
        selectedMode=mode.getSelectedItemPosition();
        durationMs=(duration.getSelectedItemPosition()+1)*600000L;
        frames.clear(); totalExposureNs=0;alignFailures=0;cancel=false;running=true;
        startedAt=SystemClock.elapsedRealtime();
        File root=new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),"AstroS24");
        folder=new File(root,new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date()));
        if(!folder.mkdirs() && !folder.isDirectory()){running=false;message("Falha criando pasta para fotos.");return;}
        start.setVisibility(View.GONE);stop.setVisibility(View.VISIBLE);stop.setEnabled(true);
        duration.setEnabled(false);seconds.setEnabled(false);sensitivity.setEnabled(false);mode.setEnabled(false);
        message(String.format(Locale.getDefault(),"Iniciando • ISO %d • %.1f s por foto",iso,selectedExposureNs/1e9));
        handler.post(this::capture);
    }
    private void capture(){
        if(!running)return;
        if(cancel || SystemClock.elapsedRealtime()-startedAt>=durationMs){finish();return;}
        if(session==null || camera==null || reader==null){cancel=true;finish();return;}
        gotImage=false;gotResult=false;actualExposureNs=selectedExposureNs;
        try{
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME,selectedExposureNs);
            b.set(CaptureRequest.SENSOR_SENSITIVITY,iso);
            b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE,0.0f);
            b.set(CaptureRequest.FLASH_MODE,CaptureRequest.FLASH_MODE_OFF);
            b.set(CaptureRequest.JPEG_ORIENTATION,orientation);
            b.set(CaptureRequest.JPEG_QUALITY,(byte)95);
            session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){
                @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest req,TotalCaptureResult res){
                    Long actual=res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    if(actual!=null && actual>0)actualExposureNs=actual;
                    gotResult=true;nextIfReady();
                }
                @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest req,CaptureFailure failure){
                    cancel=true;message("A captura falhou. Processando fotos anteriores.");finish();
                }
            },handler);
        }catch(Exception ex){cancel=true;message("Erro na captura: "+ex.getMessage());finish();}
    }
    private void onImage(ImageReader r) {
        Image image=null;
        try{
            image=r.acquireNextImage();
            if(image==null)return;
            if(!running)return;
            ByteBuffer buf=image.getPlanes()[0].getBuffer();
            byte[] content=new byte[buf.remaining()];buf.get(content);
            File shot=new File(folder,String.format(Locale.US,"quadro_%04d.jpg",frames.size()+1));
            try(FileOutputStream out=new FileOutputStream(shot)){out.write(content);}
            frames.add(shot);gotImage=true;nextIfReady();
        }catch(Exception ex){cancel=true;message("Erro salvando quadro: "+ex.getMessage());finish();}
        finally{if(image!=null)image.close();}
    }
    private void nextIfReady(){
        if(!running || !gotImage || !gotResult)return;
        totalExposureNs+=actualExposureNs;
        long elapsed=SystemClock.elapsedRealtime()-startedAt;
        int pct=(int)Math.min(1000L,elapsed*1000L/durationMs);
        runOnUiThread(()->{
            bar.setProgress(pct);
            status.setText("Fotos: "+frames.size()+" • Sessão: "+(elapsed/1000)+" s / "+(durationMs/1000)+" s");
        });
        if(cancel || elapsed>=durationMs)finish();
        else handler.postDelayed(this::capture,200);
    }
    private void finish(){
        if(!running)return;
        running=false;processing=!frames.isEmpty();
        if(frames.isEmpty()){message("Nenhuma foto registrada.");reset();return;}
        final ArrayList<File> toProcess=new ArrayList<>(frames);
        final int stackMode=selectedMode;
        message("Combinando "+toProcess.size()+" fotos. Não feche o aplicativo.");
        runOnUiThread(()->stop.setVisibility(View.GONE));
        worker.execute(()->{
            try {
                File result=new File(folder,"resultado.jpg");
                int[] stats=stack(toProcess,result,stackMode,(done,total)->runOnUiThread(()->{
                    bar.setProgress((int)(done*1000f/total));
                    status.setText("Processando "+done+"/"+total);
                }));
                Uri uri=publish(result);
                if(uri==null)throw new IOException("A Galeria não aceitou a imagem");
                for(File f:toProcess)f.delete();
                message("Foto salva na Galeria → Pictures/AstroS24");
                detail("Resolução "+stats[0]+" × "+stats[1]+" • tempo acumulado "+
                    String.format(Locale.getDefault(),"%.1f s",totalExposureNs/1e9)+
                    " • quadros sem alinhamento confiável: "+stats[2]+"/"+toProcess.size());
            }catch(Throwable ex){
                message("Erro de processamento: "+ex.getMessage());
                detail("As fotos originais continuam salvas em "+folder);
            }finally{processing=false;reset();}
        });
    }
    private void reset(){
        runOnUiThread(()->{
            start.setVisibility(View.VISIBLE);start.setEnabled(ready);stop.setVisibility(View.GONE);
            duration.setEnabled(true);seconds.setEnabled(true);sensitivity.setEnabled(true);mode.setEnabled(true);
            bar.setProgress(0);
        });
    }
    interface Progress {void update(int current,int all);}
    private static int[] stack(List<File> files,File result,int mode,Progress progress)throws IOException{
        BitmapFactory.Options header=new BitmapFactory.Options();header.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(files.get(0).getAbsolutePath(),header);
        if(header.outWidth<=0)throw new IOException("Primeira fotografia inválida");
        float ratio=Math.min(1f,2200f/Math.max(header.outWidth,header.outHeight));
        int w=Math.max(1,Math.round(header.outWidth*ratio)),h=Math.max(1,Math.round(header.outHeight*ratio));
        int total=w*h,tw=Math.max(1,Math.round(320f*w/Math.max(w,h))),th=Math.max(1,Math.round(320f*h/Math.max(w,h)));
        long[] sums=mode==0?new long[total]:null;
        short[] samples=mode==0?new short[total]:null;
        int[] brightest=mode==1?new int[total]:null;
        int[] pixels=new int[total];
        ArrayList<double[]> reference=null;int failures=0;
        for(int idx=0;idx<files.size();idx++){
            BitmapFactory.Options options=new BitmapFactory.Options(); options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            int sample=1;
            while(header.outWidth/sample>w*1.7 || header.outHeight/sample>h*1.7)sample*=2;
            options.inSampleSize=sample;
            Bitmap b=BitmapFactory.decodeFile(files.get(idx).getAbsolutePath(),options);
            if(b==null)throw new IOException("JPEG corrompido: "+files.get(idx).getName());
            Bitmap frame=(b.getWidth()==w && b.getHeight()==h)?b:Bitmap.createScaledBitmap(b,w,h,true);
            if(frame!=b)b.recycle();
            if(mode==0){
                Bitmap thumb=Bitmap.createScaledBitmap(frame,tw,th,false);
                ArrayList<double[]> stars=findStars(thumb);thumb.recycle();
                if(idx==0)reference=stars;
                else{
                    double[] tr=align(reference,stars);
                    if(tr==null)failures++;
                    else{
                        double c=Math.cos(tr[0]),s=Math.sin(tr[0]);
                        Matrix m=new Matrix();
                        m.setValues(new float[]{(float)c,(float)-s,(float)(tr[1]*w/tw),
                            (float)s,(float)c,(float)(tr[2]*h/th),0,0,1});
                        Bitmap moved=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(moved);
                        canvas.drawBitmap(frame,m,new Paint(Paint.FILTER_BITMAP_FLAG));
                        frame.recycle();frame=moved;
                    }
                }
            }
            frame.getPixels(pixels,0,w,0,0,w,h);frame.recycle();
            if(mode==0){
                for(int j=0;j<total;j++){
                    int p=pixels[j];
                    if((p>>>24)==0)continue;
                    sums[j]+=((p>>16)&255)+(((long)((p>>8)&255))<<20)+(((long)(p&255))<<40);
                    samples[j]++;
                }
            }else{
                for(int j=0;j<total;j++){
                    int p=pixels[j],old=brightest[j];
                    int lum=((p>>16)&255)+((p>>8)&255)+(p&255);
                    int prior=((old>>16)&255)+((old>>8)&255)+(old&255);
                    if(lum>prior)brightest[j]=p;
                }
            }
            progress.update(idx+1,files.size());
        }
        if(mode==0){
            long mask=(1L<<20)-1;
            for(int j=0;j<total;j++){
                int n=samples[j]&0xffff;
                if(n==0){pixels[j]=0xff000000;continue;}
                long p=sums[j];
                int r=(int)((p&mask)/n);
                int g=(int)(((p>>20)&mask)/n);
                int b=(int)(((p>>40)&mask)/n);
                pixels[j]=0xff000000|(r<<16)|(g<<8)|b;
            }
        }else System.arraycopy(brightest,0,pixels,0,total);
        Bitmap output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        output.setPixels(pixels,0,w,0,0,w,h);
        try(FileOutputStream out=new FileOutputStream(result)){
            if(!output.compress(Bitmap.CompressFormat.JPEG,95,out))throw new IOException("Erro ao gravar JPEG");
        }finally{output.recycle();}
        return new int[]{w,h,failures};
    }
    private static ArrayList<double[]> findStars(Bitmap bmp){
        int w=bmp.getWidth(),h=bmp.getHeight();int[] px=new int[w*h];
        bmp.getPixels(px,0,w,0,0,w,h);
        int[] lum=new int[px.length];int[] hist=new int[256];
        for(int i=0;i<px.length;i++){
            int p=px[i];int l=(int)(0.25*((p>>16)&255)+0.6*((p>>8)&255)+0.15*(p&255));
            lum[i]=l;hist[l]++;
        }
        int count=0,level=70;
        for(int i=0;i<256;i++){count+=hist[i];if(count>=px.length*0.996){level=Math.max(40,i+8);break;}}
        ArrayList<double[]> candidates=new ArrayList<>();
        for(int y=4;y<h-4;y+=2)for(int x=4;x<w-4;x+=2){
            int k=y*w+x,v=lum[k];if(v<level)continue;
            int bg=(lum[k-4]+lum[k+4]+lum[k-4*w]+lum[k+4*w])/4;
            if(v-bg<15)continue;
            boolean peak=true;
            for(int dy=-2;dy<=2 && peak;dy++)for(int dx=-2;dx<=2;dx++)
                if((dx!=0||dy!=0)&&lum[k+dy*w+dx]>v+2){peak=false;break;}
            if(peak)candidates.add(new double[]{x,y,v});
        }
        candidates.sort((a,b)->Double.compare(b[2],a[2]));
        ArrayList<double[]> out=new ArrayList<>();
        for(double[] p:candidates){
            boolean near=false;
            for(double[] q:out)if(Math.hypot(p[0]-q[0],p[1]-q[1])<6){near=true;break;}
            if(!near)out.add(p);
            if(out.size()==35)break;
        }
        return out;
    }
    // Brute-force rigid registration of bright stars, with angle/shift candidates.
    private static double[] align(ArrayList<double[]> ref,ArrayList<double[]> current){
        if(ref==null || ref.size()<4 || current.size()<4)return null;
        double[] best=null;int high=3;
        for(int deg=-12;deg<=12;deg+=2){
            double a=Math.toRadians(deg),c=Math.cos(a),s=Math.sin(a);
            for(int i=0;i<Math.min(10,ref.size());i++)
                for(int j=0;j<Math.min(10,current.size());j++){
                    double[] r=ref.get(i),p=current.get(j);
                    double dx=r[0]-(c*p[0]-s*p[1]),dy=r[1]-(s*p[0]+c*p[1]);
                    int score=0;
                    for(double[] v:current){
                        double x=c*v[0]-s*v[1]+dx,y=s*v[0]+c*v[1]+dy;
                        for(double[] q:ref){
                            if(Math.abs(q[0]-x)<4 && Math.abs(q[1]-y)<4){score++;break;}
                        }
                    }
                    if(score>high){high=score;best=new double[]{a,dx,dy};}
                }
        }
        return best;
    }
    private Uri publish(File file)throws IOException{
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,"AstroS24_"+System.currentTimeMillis()+".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/AstroS24");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null)return null;
        try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri)){
            if(out==null)throw new IOException("Falha ao abrir Galeria");
            byte[] buf=new byte[65536];int n;
            while((n=in.read(buf))!=-1)out.write(buf,0,n);
            ContentValues done=new ContentValues();done.put(MediaStore.Images.Media.IS_PENDING,0);
            getContentResolver().update(uri,done,null,null);
            return uri;
        }catch(Exception e){
            getContentResolver().delete(uri,null,null);
            throw new IOException(e);
        }
    }
    private void close(){
        ready=false;
        if(session!=null){session.close();session=null;}
        if(camera!=null){camera.close();camera=null;}
        if(reader!=null){reader.close();reader=null;}
        if(previewSurface!=null){previewSurface.release();previewSurface=null;}
    }
    @Override protected void onDestroy(){
        cancel=true;
        if(handler!=null)handler.post(this::close);
        if(thread!=null)thread.quitSafely();
        worker.shutdown();
        super.onDestroy();
    }
    @Override public void onBackPressed(){
        if(running||processing)Toast.makeText(this,"Encerre a captura antes de sair.",Toast.LENGTH_LONG).show();
        else super.onBackPressed();
    }
}
