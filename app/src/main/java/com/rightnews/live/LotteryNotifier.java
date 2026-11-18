package com.rightnews.live;
import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

// v3.8: lottery draw-start notifications. One AlarmManager
// alarm per region (setAndAllowWhileIdle — inexact, may land
// a few minutes late in Doze, which is fine: a draw runs
// 30-50 min). The alarm receiver posts the notification and
// schedules the next day's alarm for that region. Alarms are
// (re)scheduled whenever the app starts and whenever the
// settings change; BootReceiver re-schedules after reboot.
// Prefs live in "kenzelections" next to the news notify pref.
public class LotteryNotifier {
    public static final String CHANNEL_ID = "xo-so";
    private static final String PREF_MASTER = "lottery_notify";
    private static final String PREF_REGION = "lottery_notify_region_";
    private static final int ALARM_REQ = 4000;
    private static final int NOTIF_ID = 3000;

    public static boolean isMasterOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean(PREF_MASTER, true);
    }
    public static void setMasterOn(Context c, boolean v) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean(PREF_MASTER, v).apply();
    }
    public static boolean isRegionOn(Context c, int region) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean(PREF_REGION + region, true);
    }
    public static void setRegionOn(Context c, int region, boolean v) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean(PREF_REGION + region, v).apply();
    }
    public static boolean isEnabled(Context c, int region) {
        return isMasterOn(c) && isRegionOn(c, region);
    }
    private static PendingIntent alarmIntent(Context c, int region) {
        Intent i = new Intent(c, LotteryAlarmReceiver.class);
        i.setAction("com.rightnews.live.LOTTERY_DRAW");
        i.putExtra("region", region);
        return PendingIntent.getBroadcast(c, ALARM_REQ + region, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static void scheduleRegion(Context c, int region) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!isEnabled(c, region)) { cancelRegion(c, region); return; }
        long at = LotteryFetcher.nextDrawTime(region, Calendar.getInstance()).getTimeInMillis();
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c, region));
    }
    public static void cancelRegion(Context c, int region) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(alarmIntent(c, region));
    }
    public static void scheduleAll(Context c) {
        for (int r = 0; r < 3; r++) scheduleRegion(c, r);
    }
    public static void cancelAll(Context c) {
        for (int r = 0; r < 3; r++) cancelRegion(c, r);
    }
    // Post the "draw started" notification. Called by the
    // alarm receiver; also safe to call directly.
    public static void notifyNow(Context c, int region) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Xổ số", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(c, MainActivity.class);
            i.putExtra("open_tab", 2);
            i.putExtra("lottery_region", region);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 5000 + region, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String name = LotteryFetcher.REGION_NAMES[region];
            Notification.Builder b = new Notification.Builder(c, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_tab_lottery)
                    .setContentTitle("Xổ số " + name + " bắt đầu quay")
                    .setContentText("Kết quả đang cập nhật trực tiếp — chạm để xem.")
                    .setContentIntent(pi).setAutoCancel(true);
            nm.notify(NOTIF_ID + region, b.build());
        } catch (Exception ignored) {}
    }
}
