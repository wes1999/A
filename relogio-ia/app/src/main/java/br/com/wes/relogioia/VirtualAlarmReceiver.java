package br.com.wes.relogioia;
import android.app.*;
import android.content.*;
import android.os.*;
public class VirtualAlarmReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent in) {
    NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
    String ch="virtual_alarm";
    if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(ch,"Despertador virtual",NotificationManager.IMPORTANCE_HIGH));
    Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,ch):new Notification.Builder(c);
    b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Despertador virtual").setContentText("O relógio virtual chegou à hora programada.").setAutoCancel(true);
    nm.notify(9001,b.build());
    c.getSharedPreferences("clock_alarm",0).edit().putBoolean("enabled",false).apply();
  }
}