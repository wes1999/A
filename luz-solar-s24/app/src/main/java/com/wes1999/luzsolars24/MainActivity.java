package com.wes1999.luzsolars24;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BG=Color.rgb(11,17,28), CARD=Color.rgb(21,31,48);
    private static final int TEXT=Color.rgb(240,244,250), MUTED=Color.rgb(164,181,205), ACCENT=Color.rgb(246,187,102);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body, modeArea;
    private TextView headerStatus, previewText, eventText, latText;
    private View previewLight;
    private Button[] tabs = new Button[4];
    private LightSurface lightSurface;
    private boolean showingLight=false, screenHold=false, userLocation=false, expired=false;
    private int mode=0, manualK=2700, manualB=48, maxB=80, dawnStart=360, duskStart=1080, cycleClockStart=0, selectedDay, selectedMonth, selectedYear, accelerateHours=6, sessionChoice=5;
    private double lat=-19.36, lon=-42.52;
    private long accelerateStart=0, lightSessionEnds=0;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private SunMath.Plan cachedPlan;
    private String planKey="";
    private SunMath.Light lastOutput=new SunMath.Light(2700,48);
    private double lastAltitude=0, lastVirtualMinute=0;
    private float lastScreenBrightness=-2;
    private final Runnable ticker=new Runnable(){
        @Override public void run() {
            updateLive();
            handler.postDelayed(this,1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        android.content.SharedPreferences sp=getPreferences(MODE_PRIVATE);
        manualK=sp.getInt("manualK",2700);
        manualB=sp.getInt("manualB",48);
        maxB=sp.getInt("maxB",80);
        mode=sp.getInt("mode",0);
        dawnStart=sp.getInt("dawnStart",360);
        duskStart=sp.getInt("duskStart",1080);
        cycleClockStart=sp.getInt("cycleClockStart",0);
        Calendar startingDate=Calendar.getInstance();
        selectedDay=sp.getInt("selectedDay",startingDate.get(Calendar.DAY_OF_MONTH));
        selectedMonth=sp.getInt("selectedMonth",startingDate.get(Calendar.MONTH)+1);
        selectedYear=sp.getInt("selectedYear",startingDate.get(Calendar.YEAR));
        accelerateHours=sp.getInt("accHours",6);
        screenHold=sp.getBoolean("screenHold",false);
        lat=Double.longBitsToDouble(sp.getLong("lat",Double.doubleToLongBits(-19.36)));
        lon=Double.longBitsToDouble(sp.getLong("lon",Double.doubleToLongBits(-42.52)));
        userLocation=sp.getBoolean("userLocation",false);
        accelerateStart=SystemClock.elapsedRealtime();
        renderControls();
        handler.post(ticker);
    }

    private void save() {
        getPreferences(MODE_PRIVATE).edit().putInt("manualK",manualK).putInt("manualB",manualB)
            .putInt("maxB",maxB).putInt("mode",mode).putInt("dawnStart",dawnStart)
            .putInt("duskStart",duskStart).putInt("cycleClockStart",cycleClockStart)
            .putInt("selectedDay",selectedDay).putInt("selectedMonth",selectedMonth).putInt("selectedYear",selectedYear)
            .putInt("accHours",accelerateHours)
            .putBoolean("screenHold",screenHold)
            .putBoolean("userLocation",userLocation)
            .putLong("lat",Double.doubleToLongBits(lat))
            .putLong("lon",Double.doubleToLongBits(lon)).apply();
    }

    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String value,int size,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
        if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);
        t.setLineSpacing(dp(2),1.0f);return t;
    }
    private void addSpace(LinearLayout parent,int h){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private void addText(LinearLayout parent,String label,int size,int color,boolean bold) {
        parent.addView(text(label,size,color,bold));
    }
    private LinearLayout column() {
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;
    }
    private LinearLayout row() {
        LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private LinearLayout card(LinearLayout dest) {
        LinearLayout c=column();c.setPadding(dp(17),dp(17),dp(17),dp(17));
        c.setBackground(shape(CARD,18));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(13);
        dest.addView(c,lp);return c;
    }
    private Button button(String name,boolean primary) {
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(name);b.setTextSize(14);
        b.setTextColor(primary?BG:TEXT);
        b.setPadding(dp(10),dp(5),dp(10),dp(5));
        b.setBackground(shape(primary?ACCENT:Color.rgb(45,60,81),12));
        b.setMinHeight(dp(46));return b;
    }
    private void renderControls() {
        showingLight=false;expired=false;
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setPhysicalBrightness(-1);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        body=column();body.setPadding(dp(17),dp(22),dp(17),dp(25));
        scroll.addView(body);
        setContentView(scroll);

        TextView title=text("LUZ SOLAR  S24",25,TEXT,true);body.addView(title);
        addSpace(body,5);
        addText(body,"Luminária de tela • Ciclo solar astronômico",13,MUTED,false);
        addSpace(body,17);

        HorizontalScrollView hscroll=new HorizontalScrollView(this);hscroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow=row();
        String[] names={"Manual","Sol real","Ciclo 24h","Acelerado"};
        for(int i=0;i<names.length;i++){
            final int index=i;
            Button tab=button(names[i],i==mode);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(43));p.rightMargin=dp(7);
            tabRow.addView(tab,p);tabs[i]=tab;
            tab.setOnClickListener(v->{mode=index;save();renderControls();});
        }
        hscroll.addView(tabRow);body.addView(hscroll);addSpace(body,17);
        LinearLayout preview=card(body);
        addText(preview,"PRÉVIA DA LUZ",12,ACCENT,true);addSpace(preview,10);
        FrameLayout pv=new FrameLayout(this);
        pv.setBackground(shape(Color.BLACK,16));
        previewLight=new View(this);previewLight.setBackground(shape(Color.rgb(255,180,90),16));
        pv.addView(previewLight,new FrameLayout.LayoutParams(-1,dp(112)));
        previewText=text("",14,Color.WHITE,true);
        previewText.setGravity(Gravity.CENTER);
        pv.addView(previewText,new FrameLayout.LayoutParams(-1,dp(112)));
        preview.addView(pv);addSpace(preview,12);
        headerStatus=text("",13,MUTED,false);preview.addView(headerStatus);
        addSpace(preview,10);
        Button start=button("INICIAR ILUMINAÇÃO EM TELA CHEIA",true);
        preview.addView(start,new LinearLayout.LayoutParams(-1,dp(50)));
        start.setOnClickListener(v->startLight());

        modeArea=card(body);
        if(mode==0) buildManual();
        if(mode==1) buildReal();
        if(mode==2) buildCustom();
        if(mode==3) buildAccelerated();

        LinearLayout timelineCard=card(body);
        addText(timelineCard,"CICLO DE 24 HORAS",13,ACCENT,true);
        addSpace(timelineCard,8);
        SolarTimeline timeline=new SolarTimeline(this);
        timelineCard.addView(timeline,new LinearLayout.LayoutParams(-1,dp(86)));
        addText(timelineCard,"As cores representam a progressão aproximada do Sol. Os tempos são astronômicos.",11,MUTED,false);

        LinearLayout advanced=card(body);
        addText(advanced,"CONTROLE DE TELA",14,TEXT,true);addSpace(advanced,12);
        buildSlider(advanced,"Limite máximo de brilho da tela",maxB,20,100,1,v->{maxB=v;save();updateLive();});
        addSpace(advanced,12);
        CheckBox hold=new CheckBox(this);
        hold.setText("Manter a tela ligada durante a iluminação");hold.setTextColor(TEXT);
        hold.setTextSize(13);hold.setChecked(screenHold);
        hold.setOnCheckedChangeListener((button,checked)->{screenHold=checked;save();});
        advanced.addView(hold);
        addSpace(advanced,5);
        addText(advanced,"Se estiver desmarcado, vale o tempo normal de suspensão da tela do Android. À noite a tela pode entrar em repouso.",12,MUTED,false);
        addSpace(advanced,12);
        addText(advanced,"Duração da sessão de iluminação",13,TEXT,true);addSpace(advanced,7);
        Spinner durations=spinner(new String[]{"30 minutos","1 hora","6 horas","12 horas","24 horas","Sem limite"},sessionChoice,v->sessionChoice=v);
        advanced.addView(durations,new LinearLayout.LayoutParams(-1,dp(50)));

        LinearLayout safety=card(body);
        addText(safety,"ATENÇÃO À TELA AMOLED",12,ACCENT,true);
        addSpace(safety,8);
        addText(safety,"O brilho e os Kelvin são aproximados, não medidos em lux. Uso prolongado com muito brilho pode aquecer o Galaxy S24, consumir bateria e aumentar o desgaste da tela. Isso não substitui iluminação adequada para plantas.",12,MUTED,false);
        updateLive();
    }

    private interface IntUpdate {void onValue(int v);}
    private void buildSlider(LinearLayout parent,String title,int initial,int min,int max,int step,IntUpdate callback) {
        LinearLayout r=row();
        TextView label=text(title,13,TEXT,true);
        r.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text(initial+(title.contains("Kelvin")?" K":"%"),14,ACCENT,true);
        r.addView(value);parent.addView(r);
        SeekBar s=new SeekBar(this);
        s.setMax((max-min)/step);s.setProgress((initial-min)/step);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        parent.addView(s);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar seek,int progress,boolean fromUser) {
                int x=min+progress*step;
                value.setText(x+(title.contains("Kelvin")?" K":"%"));
                if(fromUser)callback.onValue(x);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
    }

    private interface Selected {void onChange(int index);}
    private Spinner spinner(String[] items,int initial,Selected select) {
        Spinner s=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);s.setSelection(initial,false);
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> parent,View v,int index,long id){select.onChange(index);}
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
        });
        return s;
    }

    private void buildManual() {
        addText(modeArea,"AJUSTE MANUAL",15,ACCENT,true);addSpace(modeArea,5);
        addText(modeArea,"Escolha a cor e a intensidade da própria tela AMOLED.",12,MUTED,false);
        addSpace(modeArea,17);
        buildSlider(modeArea,"Brilho",manualB,0,100,1,v->{manualB=v;save();updateLive();});
        addSpace(modeArea,14);
        buildSlider(modeArea,"Temperatura Kelvin",manualK,1800,10000,100,v->{manualK=v;save();updateLive();});
        addSpace(modeArea,9);
        LinearLayout rr=row();
        for(int kelvin:new int[]{2700,4000,6500}) {
            Button p=button(kelvin+" K",false);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);
            lp.rightMargin=dp(5);rr.addView(p,lp);
            p.setOnClickListener(v->{manualK=kelvin;save();renderControls();});
        }
        modeArea.addView(rr);
    }

    private void buildReal() {
        addText(modeArea,"SOL REAL • DATA E LOCALIZAÇÃO",15,ACCENT,true);
        addSpace(modeArea,7);
        latText=text("",13,MUTED,false);modeArea.addView(latText);
        addSpace(modeArea,9);
        Button gps=button("USAR MINHA LOCALIZAÇÃO",false);modeArea.addView(gps);
        gps.setOnClickListener(v->requestLocation());
        addSpace(modeArea,9);
        Button coordinates=button("DEFINIR LATITUDE E LONGITUDE MANUALMENTE",false);
        modeArea.addView(coordinates);
        coordinates.setOnClickListener(v->showCoordinatesDialog());
        addSpace(modeArea,13);
        addText(modeArea,"Eventos calculados para a data de hoje",13,TEXT,true);
        eventText=text("",12,MUTED,false);modeArea.addView(eventText);
    }

    private void buildCustom() {
        addText(modeArea,"CICLO ASTRONÔMICO PERSONALIZADO",15,ACCENT,true);
        addSpace(modeArea,7);
        addText(modeArea,"Escolha a data de referência, o começo do ciclo e os horários do Sol. As fases do crepúsculo são calculadas pela latitude.",12,MUTED,false);
        addSpace(modeArea,15);

        addText(modeArea,"DATA DO DIA SIMULADO (DD/MM/AAAA)",12,TEXT,true);
        addSpace(modeArea,6);
        Button date=button(selectedDateText(),false);
        modeArea.addView(date,new LinearLayout.LayoutParams(-1,dp(48)));
        date.setOnClickListener(v->chooseSimulationDate());
        addSpace(modeArea,6);
        Button today=button("USAR A DATA DE HOJE",false);
        modeArea.addView(today,new LinearLayout.LayoutParams(-1,dp(42)));
        today.setOnClickListener(v->{
            Calendar c=now();
            selectedDay=c.get(Calendar.DAY_OF_MONTH);
            selectedMonth=c.get(Calendar.MONTH)+1;
            selectedYear=c.get(Calendar.YEAR);
            cachedPlan=null;save();renderControls();
        });
        addSpace(modeArea,17);

        addText(modeArea,"HORÁRIO DE INÍCIO DO CICLO DE 24 HORAS",12,TEXT,true);
        addSpace(modeArea,6);
        Button startAt=button("ESCOLHER HORÁRIO: "+clockHHmm(cycleClockStart),false);
        modeArea.addView(startAt,new LinearLayout.LayoutParams(-1,dp(48)));
        startAt.setOnClickListener(v->chooseCycleClockStart());
        addSpace(modeArea,8);
        Button useNow=button("USAR O HORÁRIO ATUAL DO S24",true);
        modeArea.addView(useNow,new LinearLayout.LayoutParams(-1,dp(48)));
        useNow.setOnClickListener(v->{
            Calendar current=Calendar.getInstance();
            cycleClockStart=current.get(Calendar.HOUR_OF_DAY)*60+current.get(Calendar.MINUTE);
            save();
            renderControls();
        });
        addSpace(modeArea,9);
        addText(modeArea,"No horário escolhido, o relógio do ciclo começa às 00h00 e completa 24 horas reais. Se usar a hora atual, o ciclo começa agora.",12,MUTED,false);
        addSpace(modeArea,17);

        addText(modeArea,"Início do amanhecer astronômico (Sol a −18°)",12,TEXT,true);
        addSpace(modeArea,6);
        Button dawn=button(SunMath.clock(dawnStart),false);
        modeArea.addView(dawn,new LinearLayout.LayoutParams(-1,dp(45)));
        dawn.setOnClickListener(v->chooseTime(dawnStart,true));
        addSpace(modeArea,14);
        addText(modeArea,"Início do entardecer civil (pôr do Sol)",12,TEXT,true);
        addSpace(modeArea,6);
        Button dusk=button(SunMath.clock(duskStart),false);
        modeArea.addView(dusk,new LinearLayout.LayoutParams(-1,dp(45)));
        dusk.setOnClickListener(v->chooseTime(duskStart,false));
        addSpace(modeArea,12);
        eventText=text("",12,MUTED,false);modeArea.addView(eventText);
        addSpace(modeArea,12);
        Button editLoc=button("EDITAR LOCALIZAÇÃO PARA OS CÁLCULOS",false);
        modeArea.addView(editLoc);editLoc.setOnClickListener(v->showCoordinatesDialog());
        addSpace(modeArea,19);
        addText(modeArea,"RESTAURAR CONFIGURAÇÕES",12,ACCENT,true);
        addSpace(modeArea,8);
        Button resetCycle=button("VOLTAR O CICLO 24H AO PADRÃO",false);
        modeArea.addView(resetCycle,new LinearLayout.LayoutParams(-1,dp(48)));
        resetCycle.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Restaurar Ciclo 24h?")
            .setMessage("A data voltará para hoje, o início será 00:00, o amanhecer às 06:00 e o entardecer às 18:00. As outras configurações serão mantidas.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Restaurar",(dialog,which)->{
                resetCycleDefaults();save();renderControls();
            }).show());
        addSpace(modeArea,9);
        Button resetAll=button("RESTAURAR TODO O APLICATIVO",false);
        modeArea.addView(resetAll,new LinearLayout.LayoutParams(-1,dp(48)));
        resetAll.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("Restaurar todos os padrões?")
            .setMessage("Isso redefinirá data, horários, brilho, Kelvin, localização, limites, temporizador e demais preferências. Deseja continuar?")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Restaurar tudo",(dialog,which)->resetAllDefaults())
            .show());
    }

    private void buildAccelerated() {
        addText(modeArea,"SIMULAÇÃO ACELERADA",15,ACCENT,true);
        addSpace(modeArea,7);
        addText(modeArea,"Um dia astronômico completo passa em menos tempo. O relógio virtual começa em 00h00.",12,MUTED,false);
        addSpace(modeArea,13);
        addText(modeArea,"Quanto tempo deve durar o dia simulado?",13,TEXT,true);
        addSpace(modeArea,7);
        int index=accelerateHours==1?0:accelerateHours==6?1:accelerateHours==12?2:3;
        Spinner speeds=spinner(new String[]{"1 hora","6 horas","12 horas","24 horas"},index,i->{
            accelerateHours=new int[]{1,6,12,24}[i];accelerateStart=SystemClock.elapsedRealtime();save();
        });
        modeArea.addView(speeds,new LinearLayout.LayoutParams(-1,dp(50)));
        addSpace(modeArea,11);
        Button restart=button("REINICIAR À MEIA-NOITE",false);
        modeArea.addView(restart);
        restart.setOnClickListener(v->{accelerateStart=SystemClock.elapsedRealtime();updateLive();});
        addSpace(modeArea,12);
        addText(modeArea,"Quando o relógio virtual completar 24 horas, o ciclo começa novamente.",12,MUTED,false);
    }

    private void chooseTime(int start,boolean morning) {
        new TimePickerDialog(this,(v,h,m)->{
            if(morning)dawnStart=h*60+m;else duskStart=h*60+m;
            save();renderControls();
        },start/60,start%60,true).show();
    }

    private String clockHHmm(int minute) {
        return String.format(Locale.forLanguageTag("pt-BR"),"%02d:%02d",minute/60,minute%60);
    }

    private void resetCycleDefaults() {
        Calendar current=Calendar.getInstance();
        selectedDay=current.get(Calendar.DAY_OF_MONTH);
        selectedMonth=current.get(Calendar.MONTH)+1;
        selectedYear=current.get(Calendar.YEAR);
        cycleClockStart=0;
        dawnStart=6*60;
        duskStart=18*60;
        cachedPlan=null;
        planKey="";
    }

    private void resetAllDefaults() {
        getPreferences(MODE_PRIVATE).edit().clear().apply();
        resetCycleDefaults();
        manualK=2700;
        manualB=48;
        maxB=80;
        mode=2;
        accelerateHours=6;
        accelerateStart=SystemClock.elapsedRealtime();
        sessionChoice=5;
        lightSessionEnds=0;
        screenHold=false;
        lat=-19.36;
        lon=-42.52;
        userLocation=false;
        if(locationManager!=null&&locationListener!=null) {
            try {locationManager.removeUpdates(locationListener);} catch(Exception ignored) {}
        }
        save();
        renderControls();
    }

    private String selectedDateText() {
        return String.format(Locale.forLanguageTag("pt-BR"),"%02d/%02d/%04d",
            selectedDay,selectedMonth,selectedYear);
    }

    private void chooseSimulationDate() {
        DatePickerDialog dialog=new DatePickerDialog(this,(picker,year,month,day)->{
            selectedDay=day;selectedMonth=month+1;selectedYear=year;
            cachedPlan=null;planKey="";save();renderControls();
        },selectedYear,selectedMonth-1,selectedDay);
        Calendar min=Calendar.getInstance(),max=Calendar.getInstance();
        min.clear();min.set(1900,Calendar.JANUARY,1);
        max.clear();max.set(2100,Calendar.DECEMBER,31);
        dialog.getDatePicker().setMinDate(min.getTimeInMillis());
        dialog.getDatePicker().setMaxDate(max.getTimeInMillis());
        dialog.show();
    }

    private void chooseCycleClockStart() {
        new TimePickerDialog(this,(picker,hour,minute)->{
            cycleClockStart=hour*60+minute;
            save();renderControls();
        },cycleClockStart/60,cycleClockStart%60,true).show();
    }

    private Calendar referenceDay() {
        Calendar c=now();
        if(mode==2) {
            c.set(Calendar.DAY_OF_MONTH,1);
            c.set(Calendar.YEAR,selectedYear);
            c.set(Calendar.MONTH,selectedMonth-1);
            c.set(Calendar.DAY_OF_MONTH,selectedDay);
        }
        return c;
    }

    private void showCoordinatesDialog() {
        LinearLayout layout=column();layout.setPadding(dp(22),dp(12),dp(22),0);
        EditText a=new EditText(this);a.setSingleLine();a.setHint("Latitude, ex.: -19.36");
        a.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        a.setText(String.format(Locale.US,"%.5f",lat));layout.addView(a);
        EditText b=new EditText(this);b.setSingleLine();b.setHint("Longitude, ex.: -42.52");
        b.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        b.setText(String.format(Locale.US,"%.5f",lon));layout.addView(b);
        new AlertDialog.Builder(this).setTitle("Localização para o Sol")
            .setView(layout).setNegativeButton("Cancelar",null).setPositiveButton("Aplicar",(d,w)->{
                try {
                    double la=Double.parseDouble(a.getText().toString().replace(',','.'));
                    double lo=Double.parseDouble(b.getText().toString().replace(',','.'));
                    if(la < -90||la > 90||lo < -180||lo > 180)throw new Exception();
                    lat=la;lon=lo;userLocation=true;cachedPlan=null;save();renderControls();
                }catch(Exception e){new AlertDialog.Builder(this).setMessage("Coordenadas inválidas.").setPositiveButton("OK",null).show();}
            }).show();
    }

    private void requestLocation() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
           checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},101);
            return;
        }
        try {
            if(locationManager==null)locationManager=(LocationManager)getSystemService(Context.LOCATION_SERVICE);
            Location best=null;
            for(String p:locationManager.getProviders(true)) {
                Location loc=locationManager.getLastKnownLocation(p);
                if(loc!=null&&(best==null||loc.getTime()>best.getTime()))best=loc;
            }
            if(best!=null)storeLocation(best);
            if(locationListener!=null)locationManager.removeUpdates(locationListener);
            locationListener=loc->{storeLocation(loc);};
            if(locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,60000,100,locationListener);
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,60000,100,locationListener);
            if(best==null)new AlertDialog.Builder(this).setMessage("Aguardando localização. Você também pode informar a latitude e longitude manualmente.").setPositiveButton("OK",null).show();
        } catch(Exception e) {
            new AlertDialog.Builder(this).setMessage("Não foi possível obter o GPS. Informe as coordenadas manualmente.").setPositiveButton("OK",null).show();
        }
    }
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] results) {
        super.onRequestPermissionsResult(req,perms,results);
        if(req==101 && results.length>0)requestLocation();
    }
    private void storeLocation(Location loc) {
        lat=loc.getLatitude();lon=loc.getLongitude();userLocation=true;cachedPlan=null;save();
        if(!showingLight)updateLive();
    }

    private Calendar now() { return Calendar.getInstance(); }
    private SunMath.Plan getPlan(Calendar today) {
        String key=today.get(Calendar.YEAR)+"-"+today.get(Calendar.DAY_OF_YEAR)+"/"+lat+"/"+lon+"/"+today.getTimeZone().getID();
        if(cachedPlan==null || !key.equals(planKey)) {
            cachedPlan=SunMath.events(today,lat,lon);planKey=key;
        }
        return cachedPlan;
    }
    private double virtualMinute() {
        if(mode!=3) {
            Calendar c=now();
            double local=c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE)+c.get(Calendar.SECOND)/60.0;
            if(mode==2) return (local-cycleClockStart+1440.0)%1440.0;
            return local;
        }
        double cycleMs=accelerateHours*3600000.0;
        return ((SystemClock.elapsedRealtime()-accelerateStart)%((long)cycleMs))/cycleMs*1440.0;
    }
    private double altitudeFor(Calendar today,double minute) {
        if(mode==2) return SunMath.altitudeCustom(today,minute,lat,lon,getPlan(today),dawnStart,duskStart);
        return SunMath.altitudeAtMinute(today,minute,lat,lon);
    }
    private SunMath.Light lightFor(Calendar today,double minute) {
        if(mode==0)return new SunMath.Light(manualK,manualB);
        return SunMath.lightForAltitude(altitudeFor(today,minute));
    }

    private void updateLive() {
        Calendar today=referenceDay();
        double minute=virtualMinute();
        lastVirtualMinute=minute;
        lastAltitude=mode==0?0:altitudeFor(today,minute);
        lastOutput=lightFor(today,minute);
        if(!showingLight) {
            if(previewLight!=null) {
                int rgb=SunMath.colorForKelvin(lastOutput.kelvin);
                double mix=lastOutput.brightness==0?0:Math.max(.07,lastOutput.brightness/100.0);
                int color=Color.rgb((int)(Color.red(rgb)*mix),(int)(Color.green(rgb)*mix),(int)(Color.blue(rgb)*mix));
                previewLight.setBackground(shape(color,16));
                previewText.setText(lastOutput.kelvin+" K   •   "+Math.round(lastOutput.brightness)+"%");
                previewText.setTextColor(lastOutput.brightness>65?Color.rgb(29,37,51):Color.WHITE);
            }
            if(headerStatus!=null){
                String state = mode==0?"Controle manual":
                    (mode==1?"Hora local: ":"Hora do ciclo: ")+SunMath.clock(minute);
                if(mode==2) state+=" | Data: "+selectedDateText();
                String alt=mode==0?"":String.format(Locale.forLanguageTag("pt-BR")," • Altitude solar %.1f°",lastAltitude);
                headerStatus.setText(state+alt);
            }
            if(latText!=null)latText.setText((userLocation?"Coordenadas definidas":"Localização padrão: Santana do Paraíso (MG)")+
                String.format(Locale.US,"\nLat %.5f°  |  Lon %.5f°",lat,lon));
            if(eventText!=null && (mode==1||mode==2))eventText.setText(eventDescription(today));
        } else {
            if(lightSessionEnds>0 && SystemClock.elapsedRealtime()>=lightSessionEnds && !expired){
                expired=true;returnToControls();
                new AlertDialog.Builder(this).setTitle("Sessão finalizada").setMessage("O temporizador de iluminação terminou.").setPositiveButton("OK",null).show();
                return;
            }
            if(lightSurface!=null) {
                lightSurface.setBackgroundColor(colorForLive());
                double physical=(lastOutput.brightness / 100.0) * (maxB / 100.0);
                if(lastOutput.brightness<=0)physical=0;
                setPhysicalBrightness((float)Math.max(0,Math.min(1,physical)));
                if(screenHold && lastOutput.brightness>0)
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }
    private int colorForLive() {
        if(lastOutput.brightness<0.5)return Color.BLACK;
        int rgb=SunMath.colorForKelvin(lastOutput.kelvin);
        double factor=Math.min(1.0,Math.max(0.04,lastOutput.brightness/30.0));
        return Color.rgb((int)(Color.red(rgb)*factor),(int)(Color.green(rgb)*factor),(int)(Color.blue(rgb)*factor));
    }

    private String eventDescription(Calendar day) {
        SunMath.Plan p=getPlan(day);
        if(!p.valid())return "Alguns crepúsculos não ocorrem nesta data/latitude.";
        double dawnOffset=mode==2?dawnStart-p.dawn[0]:0;
        double duskOffset=mode==2?duskStart-p.dusk[3]:0;
        StringBuilder sb=new StringBuilder();
        if(mode==2)sb.append("Data: ").append(selectedDateText())
            .append(" | Início do ciclo: ").append(SunMath.clock(cycleClockStart))
            .append("\nHorários reais do relógio (já ajustados para o começo do ciclo)\n");
        sb.append("\nMANHÃ\n");
        for(int i=0;i<4;i++) {
            String name=i==3?"Nascer do Sol":("Início "+SunMath.PHASES[i].toLowerCase(Locale.ROOT));
            sb.append("  ").append(SunMath.clock(p.dawn[i]+dawnOffset+(mode==2?cycleClockStart:0))).append("   ").append(name).append('\n');
        }
        sb.append("\nTARDE / NOITE\n");
        for(int i=3;i>=0;i--) {
            String name=i==3?"Pôr do Sol":("Início "+SunMath.PHASES[i].toLowerCase(Locale.ROOT));
            sb.append("  ").append(SunMath.clock(p.dusk[i]+duskOffset+(mode==2?cycleClockStart:0))).append("   ").append(name).append('\n');
        }
        double dawnDuration=p.dawn[3]-p.dawn[0];
        double duskDuration=p.dusk[0]-p.dusk[3];
        sb.append(String.format(Locale.forLanguageTag("pt-BR"),
            "\nCrepúsculos: manhã %.1f min • noite %.1f min",dawnDuration,duskDuration));
        if(mode==2) {
            double customSunrise=dawnStart+dawnDuration;
            double adjustedSunset=duskStart<=dawnStart?duskStart+1440:duskStart;
            if(customSunrise>=adjustedSunset ||
                adjustedSunset+duskDuration>=dawnStart+1440) {
                sb.append("\n⚠ Os horários escolhidos não deixam espaço para todas as fases. Altere amanhecer ou entardecer.");
            }
        }
        return sb.toString();
    }

    private void startLight() {
        if(mode==2) {
            SunMath.Plan p=getPlan(referenceDay());
            double ss=duskStart<=dawnStart?duskStart+1440:duskStart;
            if(p.valid() && (dawnStart+p.dawn[3]-p.dawn[0]>=ss || ss+p.dusk[0]-p.dusk[3]>=dawnStart+1440)) {
                new AlertDialog.Builder(this).setMessage("Os horários personalizados se sobrepõem. Ajuste-os antes de iniciar.").setPositiveButton("OK",null).show();return;
            }
        }
        expired=false;showingLight=true;
        if(mode==3)accelerateStart=SystemClock.elapsedRealtime();
        long[] durations={30,60,360,720,1440,-1};
        long min=durations[sessionChoice];
        lightSessionEnds=min<0?0:SystemClock.elapsedRealtime()+min*60000;
        lightSurface=new LightSurface(this);
        getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
          View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(lightSurface);
        lightSurface.setOnClickListener(v->returnToControls());
        updateLive();
    }
    private void setPhysicalBrightness(float level) {
        if(Math.abs(level-lastScreenBrightness)<0.005f)return;
        WindowManager.LayoutParams p=getWindow().getAttributes();
        p.screenBrightness=level;
        getWindow().setAttributes(p);
        lastScreenBrightness=level;
    }
    private void returnToControls() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lightSurface=null;renderControls();
    }
    @Override public void onBackPressed() {
        if(showingLight)returnToControls();else super.onBackPressed();
    }
    @Override public void onPause() {
        super.onPause();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onResume() {
        super.onResume();handler.postDelayed(()->updateLive(),300);
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(ticker);
        if(locationManager!=null&&locationListener!=null)
            try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}
        setPhysicalBrightness(-1);super.onDestroy();
    }

    private static final class LightSurface extends View {
        public LightSurface(Context context){super(context);setBackgroundColor(Color.BLACK);}
    }

    private final class SolarTimeline extends View {
        private final Paint paint=new Paint(3);
        SolarTimeline(Context c){super(c);}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w=getWidth(),h=getHeight(), top=dp(5), barHeight=dp(44);
            Calendar day=referenceDay();
            double segment=Math.max(1.0,w/240.0);
            for(double x=0;x<w;x+=segment) {
                double min=1440.0*x/w;
                SunMath.Light output=lightFor(day,min);
                int rgb=SunMath.colorForKelvin(output.kelvin);
                double factor=output.brightness<=0?0:Math.max(.07,output.brightness/100);
                paint.setColor(Color.rgb((int)(Color.red(rgb)*factor),(int)(Color.green(rgb)*factor),(int)(Color.blue(rgb)*factor)));
                c.drawRect((float)x,top,(float)Math.min(w,x+segment+1),top+barHeight,paint);
            }
            paint.setColor(MUTED);paint.setTextSize(dp(10));
            c.drawText("00h",0,h-dp(5),paint);
            c.drawText("06h",w*.25f-dp(12),h-dp(5),paint);
            c.drawText("12h",w*.50f-dp(12),h-dp(5),paint);
            c.drawText("18h",w*.75f-dp(12),h-dp(5),paint);
            c.drawText("24h",w-dp(21),h-dp(5),paint);
            double current=virtualMinute();
            paint.setColor(ACCENT);paint.setStrokeWidth(dp(2));
            float px=(float)(w*current/1440.0);
            c.drawLine(px,top,px,top+barHeight,paint);
        }
    }
}