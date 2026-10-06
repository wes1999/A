package br.com.wes.relogioia;
import android.app.*;
import android.content.*;
import android.os.*;
import java.time.*;
import java.util.*;
public class VirtualAlarmReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent in) {
    if (Intent.ACTION_BOOT_COMPLETED.equals(in.getAction())) { reschedule(c); return; }
    NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
    String ch="virtual_alarm";
    if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(ch,"Despertador virtual",NotificationManager.IMPORTANCE_HIGH));
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,ch):new Notification.Builder(c);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Despertador virtual").setContentText("O relógio virtual chegou à hora programada.").setAutoCancel(true).setDefaults(Notification.DEFAULT_ALL);
    nm.notify(9001,b.build());
    c.getSharedPreferences("clock_alarm",0).edit().putBoolean("enabled",false).apply();
  }
  static void reschedule(Context c){
    android.content.SharedPreferences a=c.getSharedPreferences("clock_alarm",0);
    if(!a.getBoolean("enabled",false)) return;
    try{
      MainActivity.ClockEngine e=new MainActivity.ClockEngine(c);
      LocalDateTime now=e.now().time, target=now.withHour(a.getInt("hour",0)).withMinute(a.getInt("minute",0)).withSecond(0).withNano(0);
      if(!target.isAfter(now)) target=target.plusDays(1);
      long delay=Math.max(1000L,(target.toEpochSecond(java.time.ZoneOffset.UTC)-now.toEpochSecond(java.time.ZoneOffset.UTC))*1000L/60L);
      Intent i=new Intent(c,VirtualAlarmReceiver.class);
      PendingIntent pi=PendingIntent.getBroadcast(c,9001,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
      ((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+delay,pi);
    }catch(Exception ignored){}
  }
}