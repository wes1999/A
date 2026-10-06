package br.com.wes.relogioia;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.Settings;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class MainActivity extends Activity {
    ClockEngine engine;
    ClockView clockView;
    TextView digital, status, alarmStatus;
    Handler handler = new Handler(Looper.getMainLooper());
    final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new ClockEngine(this);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7001);
        buildUi();
        tick();
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(24,18,24,18);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("RELÓGIO IA");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null,Typeface.BOLD);
        root.addView(title,new LinearLayout.LayoutParams(-1,50));

        clockView = new ClockView(this);
        root.addView(clockView,new LinearLayout.LayoutParams(-1,0,1));

        digital = text("",24,Color.WHITE);
        digital.setGravity(Gravity.CENTER);
        root.addView(digital,new LinearLayout.LayoutParams(-1,65));

        alarmStatus = text("Despertador virtual: desativado",13,Color.rgb(255,190,50));
        alarmStatus.setGravity(Gravity.CENTER);
        root.addView(alarmStatus,new LinearLayout.LayoutParams(-1,38));

        TextView rule = text("1 minuto real = 1 hora virtual  •  24 min = 1 dia",13,Color.LTGRAY);
        rule.setGravity(Gravity.CENTER);
        root.addView(rule,new LinearLayout.LayoutParams(-1,45));

        status = text("",12,Color.GRAY);
        status.setGravity(Gravity.CENTER);
        root.addView(status,new LinearLayout.LayoutParams(-1,45));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button set = button("CONFIGURAR");
        Button backup = button("BACKUP");
        Button restore = button("RESTAURAR");
        Button alarm = button("DESPERTADOR");
        row.addView(set,new LinearLayout.LayoutParams(0,58,1));
        row.addView(backup,new LinearLayout.LayoutParams(0,58,1));
        row.addView(restore,new LinearLayout.LayoutParams(0,58,1));
        root.addView(alarm,new LinearLayout.LayoutParams(-1,58));
        root.addView(row);

        set.setOnClickListener(v->configure());
        backup.setOnClickListener(v->backup());
        restore.setOnClickListener(v->restore());
        alarm.setOnClickListener(v->configureAlarm());
        setContentView(root);
    }

    TextView text(String s,float size,int color){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    Button button(String s){ Button b=new Button(this); b.setText(s); b.setTextSize(12); return b; }

    void tick() {
        ClockEngine.Result r=engine.now();
        digital.setText(r.time.format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"\n"+r.time.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy",new Locale("pt","BR"))));
        status.setText("Velocidade: 60×  •  "+(r.clockChanged ? "⚠ horário do aparelho alterado; referência ajustada" : "sincronizado pelo tempo decorrido"));
        updateAlarmStatus();
        clockView.setTime(r.time);
        handler.postDelayed(this::tick,100);
    }

    void configureAlarm(){
        final EditText h=new EditText(this); h.setHint("Hora virtual (0–23)"); h.setInputType(2); 
        final EditText m=new EditText(this); m.setHint("Minuto virtual (0–59)"); m.setInputType(2);
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.HORIZONTAL); box.setPadding(20,0,20,0);
        box.addView(h,new LinearLayout.LayoutParams(0,70,1)); box.addView(m,new LinearLayout.LayoutParams(0,70,1));
        LocalDateTime n=engine.now().time; h.setText(String.valueOf(n.getHour())); m.setText(String.valueOf(n.getMinute()));
        new AlertDialog.Builder(this).setTitle("Despertador virtual").setMessage("O celular tocará quando o relógio virtual atingir essa hora.\nEx.: 08:30 virtual = 30 segundos reais a partir das 08:00.")
          .setView(box).setPositiveButton("ATIVAR",(d,w)->{
            try {
              int hh=Integer.parseInt(h.getText().toString().trim()), mm=Integer.parseInt(m.getText().toString().trim());
              if(hh<0||hh>23||mm<0||mm>59) throw new Exception();
              scheduleVirtualAlarm(hh,mm);
            } catch(Exception e){ Toast.makeText(this,"Hora inválida.",Toast.LENGTH_LONG).show(); }
          }).setNeutralButton("DESATIVAR",(d,w)->cancelVirtualAlarm()).setNegativeButton("CANCELAR",null).show();
    }
    void scheduleVirtualAlarm(int hh,int mm){
        LocalDateTime now=engine.now().time;
        LocalDateTime target=now.withHour(hh).withMinute(mm).withSecond(0).withNano(0);
        if(!target.isAfter(now)) target=target.plusDays(1);
        long realDelay=Math.max(1000L,(target.toEpochSecond(java.time.ZoneOffset.UTC)-now.toEpochSecond(java.time.ZoneOffset.UTC))*1000L/60L);
        getSharedPreferences("clock_alarm",0).edit().putBoolean("enabled",true).putInt("hour",hh).putInt("minute",mm).apply();
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
        Intent i=new Intent(this,VirtualAlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(this,9001,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+realDelay,pi);
        Toast.makeText(this,"Despertador ativado para "+String.format(Locale.US,"%02d:%02d",hh,mm)+" virtual.",Toast.LENGTH_LONG).show();
    }
    void cancelVirtualAlarm(){
        getSharedPreferences("clock_alarm",0).edit().putBoolean("enabled",false).apply();
        Intent i=new Intent(this,VirtualAlarmReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(this,9001,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        ((AlarmManager)getSystemService(ALARM_SERVICE)).cancel(pi);
        Toast.makeText(this,"Despertador virtual desativado.",Toast.LENGTH_SHORT).show();
    }
    void updateAlarmStatus(){
        android.content.SharedPreferences a=getSharedPreferences("clock_alarm",0);
        if(alarmStatus!=null) alarmStatus.setText(a.getBoolean("enabled",false) ? "Despertador virtual: "+String.format(Locale.US,"%02d:%02d",a.getInt("hour",0),a.getInt("minute",0))+" ✓" : "Despertador virtual: desativado");
    }

    void configure(){
        final EditText date=new EditText(this); date.setHint("Data: 05/10/2026"); date.setText(engine.now().time.toLocalDate().toString());
        final EditText time=new EditText(this); time.setHint("Hora: 12:34"); time.setText(engine.now().time.toLocalTime().withNano(0).toString());
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(20,0,20,0); box.addView(date); box.addView(time);
        new AlertDialog.Builder(this).setTitle("Definir tempo virtual").setMessage("Escolha a data e hora iniciais. Depois o relógio continua sozinho.\nFormato: AAAA-MM-DD e HH:MM")
            .setView(box).setPositiveButton("SALVAR",(d,w)->{
                try {
                    LocalDate ld=LocalDate.parse(date.getText().toString().trim());
                    LocalTime lt=LocalTime.parse(time.getText().toString().trim());
                    engine.set(LocalDateTime.of(ld,lt));
                } catch(Exception e){ Toast.makeText(this,"Data/hora inválida.",Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("CANCELAR",null).show();
    }

    void backup(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/json"); i.putExtra(Intent.EXTRA_TITLE,"relogio-ia-backup.json"); startActivityForResult(i,10);
    }
    void restore(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/json"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,11);
    }
    @Override protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(res!=RESULT_OK||data==null)return;
        try{
            if(req==10){
                try(OutputStream o=getContentResolver().openOutputStream(data.getData())){
                    o.write(engine.exportJson().getBytes(StandardCharsets.UTF_8));
                }
                Toast.makeText(this,"Backup salvo.",Toast.LENGTH_SHORT).show();
            } else if(req==11){
                try(InputStream in=getContentResolver().openInputStream(data.getData())){
                    ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[4096]; int n;
                    while((n=in.read(buf))>0)b.write(buf,0,n);
                    engine.importJson(b.toString("UTF-8"));
                }
                Toast.makeText(this,"Backup restaurado.",Toast.LENGTH_SHORT).show();
            }
        }catch(Exception e){Toast.makeText(this,"Não foi possível usar o arquivo.",Toast.LENGTH_LONG).show();}
    }

    class ClockView extends View {
        Paint p=new Paint(3); LocalDateTime t=LocalDateTime.now();
        ClockView(Context c){super(c);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));}
        void setTime(LocalDateTime x){t=x;invalidate();}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            float cx=getWidth()/2f, cy=getHeight()/2f, radius=Math.min(getWidth(),getHeight())*0.39f;
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(10,10,10)); c.drawCircle(cx,cy,radius,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.DKGRAY); c.drawCircle(cx,cy,radius,p);
            p.setTextAlign(Paint.Align.CENTER); p.setTextSize(radius*.16f); p.setColor(Color.WHITE);
            for(int i=1;i<=12;i++){ double a=Math.toRadians(i*30-90); c.drawText(""+i,cx+(float)Math.cos(a)*radius*.80f,cy+(float)Math.sin(a)*radius*.80f-p.ascent()/2,p); }
            // Virtual minute hand completes 360° every real minute. The hour hand completes 360° every 12 real minutes.
            double minAngle=(t.getMinute()+t.getSecond()/60.0+t.getNano()/60e9)*6.0-90;
            double hourAngle=((t.getHour()%12)+t.getMinute()/60.0+t.getSecond()/3600.0)*30.0-90;
            hand(c,cx,cy,radius*.58f,minAngle,5,Color.rgb(124,255,107));
            hand(c,cx,cy,radius*.40f,hourAngle,9,Color.WHITE);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(255,190,50)); c.drawCircle(cx,cy,8,p);
        }
        void hand(Canvas c,float cx,float cy,float len,double deg,float width,int color){
            double a=Math.toRadians(deg); p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(width);p.setColor(color);
            c.drawLine(cx,cy,cx+(float)Math.cos(a)*len,cy+(float)Math.sin(a)*len,p);
        }
    }

    static class ClockEngine {
        static final String PREF="clock_state";
        SharedPreferences p;
        long refWall, refElapsed;
        LocalDateTime refVirtual;
        boolean changed;
        Context c;
        ClockEngine(Context c){
            this.c=c; p=c.getSharedPreferences(PREF,0);
            if(!p.contains("refVirtual")) set(LocalDateTime.now().withNano(0));
            else load();
        }
        void load(){
            refWall=p.getLong("refWall",System.currentTimeMillis());
            refElapsed=p.getLong("refElapsed",-1);
            refVirtual=LocalDateTime.parse(p.getString("refVirtual","2026-10-05T00:00:00"));
        }
        void set(LocalDateTime v){
            refVirtual=v; refWall=System.currentTimeMillis(); refElapsed=SystemClock.elapsedRealtime();
            changed=false; save();
        }
        Result now(){
            long wall=System.currentTimeMillis(), el=SystemClock.elapsedRealtime();
            double realSeconds;
            if(refElapsed>=0 && el>=refElapsed){
                realSeconds=(el-refElapsed)/1000.0;
                // Wall clock is used only as a drift/change detector while the same boot is active.
                long expected=refWall+(long)(realSeconds*1000.0);
                changed=Math.abs(wall-expected)>5000;
                if(changed){ refWall=wall; refElapsed=el; save(); }
            } else {
                // After reboot elapsedRealtime resets. Use wall-clock delta.
                realSeconds=(wall-refWall)/1000.0;
                if(realSeconds<0){ changed=true; realSeconds=0; }
                refWall=wall; refElapsed=el; save();
            }
            // 1 real second = 1 virtual minute. Thus 60 real seconds = 1 virtual hour.
            LocalDateTime v=refVirtual.plusNanos((long)(realSeconds*60_000_000_000L));
            return new Result(v,changed);
        }
        void save(){p.edit().putLong("refWall",refWall).putLong("refElapsed",refElapsed).putString("refVirtual",refVirtual.toString()).apply();}
        String exportJson(){
            now();
            return "{\n  \"version\":1,\n  \"refWall\":"+refWall+",\n  \"refVirtual\":\""+refVirtual+"\",\n  \"speed\":60\n}";
        }
        void importJson(String s){
            long w=parseLong(s,"refWall");
            String v=parseString(s,"refVirtual");
            if(v==null)throw new IllegalArgumentException();
            refWall=w; refVirtual=LocalDateTime.parse(v); refElapsed=SystemClock.elapsedRealtime(); save();
        }
        long parseLong(String s,String key){String q="\""+key+"\":";int i=s.indexOf(q);if(i<0)return System.currentTimeMillis();i+=q.length();int j=i;while(j<s.length()&&Character.isDigit(s.charAt(j)))j++;return Long.parseLong(s.substring(i,j));}
        String parseString(String s,String key){String q="\""+key+"\":\"";int i=s.indexOf(q);if(i<0)return null;i+=q.length();int j=s.indexOf("\"",i);return j<0?null:s.substring(i,j);}
        static class Result{LocalDateTime time;boolean clockChanged;Result(LocalDateTime t,boolean c){time=t;clockChanged=c;}}
    }
}
