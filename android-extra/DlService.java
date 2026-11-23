package com.clipgrab.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the app's process (and so the on-phone downloads) alive while any download is running:
 * a foreground service with a small notification, plus a CPU wake lock so the phone doesn't sleep on it.
 * YtNative calls hold() when a download starts and release() when it ends.
 */
public class DlService extends Service {
  private static final String CH = "clipgrab_dl";
  private static int count = 0;
  private static PowerManager.WakeLock wl;

  public static synchronized void hold(Context c) {
    Context a = c.getApplicationContext();
    count++;
    try {
      if (wl == null) {
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clipgrab:dl");
        wl.setReferenceCounted(false);
      }
      if (!wl.isHeld()) wl.acquire(3 * 60 * 60 * 1000L);   // safety timeout: 3 hours
    } catch (Throwable ignore) {}
    try {
      Intent i = new Intent(a, DlService.class);
      if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
    } catch (Throwable ignore) {}   // never let the notification break a download
  }

  public static synchronized void release(Context c) {
    Context a = c.getApplicationContext();
    if (count > 0) count--;
    if (count == 0) {
      try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable ignore) {}
      try { a.stopService(new Intent(a, DlService.class)); } catch (Throwable ignore) {}
    } else {
      try {
        Intent i = new Intent(a, DlService.class);
        if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
      } catch (Throwable ignore) {}
    }
  }

  private static synchronized int n() { return count; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    try {
      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (Build.VERSION.SDK_INT >= 26 && nm != null) {
        NotificationChannel ch = new NotificationChannel(CH, "Downloads in progress", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
      }
      Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
      PendingIntent pi = open == null ? null
          : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      int k = Math.max(1, n());
      Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
      b.setSmallIcon(android.R.drawable.stat_sys_download)
          .setContentTitle("ClipGrab")
          .setContentText(k == 1 ? "Working in the background…" : "Working on " + k + " tasks…")
          .setOngoing(true);
      if (pi != null) b.setContentIntent(pi);
      Notification nf = b.build();
      if (Build.VERSION.SDK_INT >= 29) startForeground(4711, nf, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
      else startForeground(4711, nf);
    } catch (Throwable ignore) {}
    return START_NOT_STICKY;   // if the system kills us, don't come back with a stale notification
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
