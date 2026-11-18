package com.rightnews.live;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
// v1.38: system-permission helpers. Two of the app's features
// depend on SYSTEM-level access, not runtime permissions, so
// "granted all permissions" can still leave them off:
// notifications (NotificationManager) and picture-in-picture
// (AppOps OP_PICTURE_IN_PICTURE, toggled in the system's
// special-access page). These helpers read the live state
// (for the Settings "Quyền hệ thống" row) and open the exact
// system pages (for the PiP failure dialog and the
// first-launch prompt). PiP settings exist from API 26, the
// app's minSdk, so no version guard is needed there.
public class Perms {
    public static boolean notificationsOn(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Exception e) { return false; }
    }
    public static boolean pipAllowed(Context c) {
        try {
            AppOpsManager am = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return am != null && am.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }
    public static void openPipSettings(Activity a) {
        try {
            // The Settings constant for this page is hidden API,
            // so the action string is used literally (the
            // Settings app handles it from API 26).
            Intent i = new Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS");
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Exception e) {
            openAppDetails(a);
        }
    }
    public static void openNotifSettings(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            a.startActivity(i);
        } catch (Exception e) {
            openAppDetails(a);
        }
    }
    // v3.29: battery-optimization exemption — the other half of
    // screen-off playback. A partial wake lock keeps the CPU on,
    // but Doze can still starve the player of network unless
    // the app is exempt. isIgnoringBatteryOptimizations reads
    // the live state (API 23+, the app's minSdk is 26).
    public static boolean batteryOptIgnored(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) { return true; }
    }
    // Direct request: the system shows its own Allow/Deny
    // dialog and reports back via onActivityResult (9005).
    public static void openBatteryOptRequest(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivityForResult(i, 9005);
        } catch (Exception e) {
            openBatteryOptSettings(a);
        }
    }
    // The settings list page (no result) — the permanent path
    // from the Settings "Quyền hệ thống" row.
    public static void openBatteryOptSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openAppDetails(a);
        }
    }
    private static void openAppDetails(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {}
    }
}
