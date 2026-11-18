package com.rightnews.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Nightly OTA: the app fetches a small version.json from the
// kenz-news GitHub repo, compares versionCode with its own,
// and when a newer nightly exists shows the changelog with
// Huỷ / Tải về. Download runs through DownloadManager with an
// in-app progress dialog, then the system installer opens the
// APK. Android still asks the user to tap Cài đặt once, and
// "install unknown apps" must be allowed once for Kenz News.
public class UpdateChecker {
    public static final String VERSION_URL = "https://raw.githubusercontent.com/nhihoppro/kenz-news/master/version.json";
    private static final String PREFS = "kenzelections";
    private static final String APK_FILE = "KenzNews-update.apk";
    private static boolean checkedThisRun = false;

    public static class OtaInfo {
        public int versionCode;
        public String versionName = "";
        public String apkUrl = "";
        public String changelog = "";
    }

    // ---- prefs ----
    public static boolean otaEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ota_enabled", true);
    }

    public static void setOtaEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ota_enabled", on).apply();
    }

    public static int currentVersionCode(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(),
                        android.content.pm.PackageManager.PackageInfoFlags.of(0)).getLongVersionCode();
            }
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) { return 0; }
    }

    public static String currentVersionName(Context ctx) {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    // ---- entry points ----
    // Launch check: once per process, only when the toggle is on.
    public static void checkOnLaunch(final Activity act) {
        if (checkedThisRun) return;
        checkedThisRun = true;
        if (!otaEnabled(act)) return;
        check(act, false);
    }

    // Manual check from Settings: always reports the result.
    public static void checkNow(final Activity act) { check(act, true); }

    private static void check(final Activity act, final boolean manual) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String json = downloadText(VERSION_URL, 8000);
                    final OtaInfo info = parse(json);
                    if (info == null || info.versionCode <= 0 || info.apkUrl.length() == 0) {
                        if (manual) toast(act, "Không kiểm tra được — thử lại sau");
                        return;
                    }
                    final int myVc = currentVersionCode(act);
                    if (info.versionCode <= myVc) {
                        if (manual) toast(act, "Đã là bản mới nhất (" + currentVersionName(act) + ")");
                        return;
                    }
                    if (!manual) {
                        int dismissed = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("ota_dismissed_vc", -1);
                        if (dismissed == info.versionCode) return;
                    }
                    act.runOnUiThread(new Runnable() { public void run() { showUpdateDialog(act, info); } });
                } catch (Exception e) {
                    if (manual) toast(act, "Không kiểm tra được — thử lại sau");
                }
            }
        }).start();
    }

    private static void showUpdateDialog(final Activity act, final OtaInfo info) {
        if (act.isFinishing()) return;
        String msg = "Phiên bản mới: " + info.versionName + "\n\n" + info.changelog;
        AlertDialog dlg = new AlertDialog.Builder(act, Theme.dialogTheme(act))
                .setTitle("Có bản cập nhật mới")
                .setMessage(msg)
                .setNegativeButton("Huỷ", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("ota_dismissed_vc", info.versionCode).apply();
                    }
                })
                .setPositiveButton("Tải về", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { startDownload(act, info); }
                })
                .show();
        Theme.styleDialog(dlg, act);
    }

    private static void startDownload(final Activity act, final OtaInfo info) {
        // This version was already downloaded (e.g. the user went
        // to allow "install unknown apps" and came back) — install
        // it directly instead of downloading again.
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long pendingId = p.getLong("ota_pending_download", -1);
        int pendingVc = p.getInt("ota_pending_vc", -1);
        if (pendingId >= 0 && pendingVc == info.versionCode) {
            DownloadManager dm0 = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm0 != null && dm0.getUriForDownloadedFile(pendingId) != null) {
                installApk(act, pendingId);
                return;
            }
        }
        DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { toast(act, "Không dùng được trình tải trên máy này"); return; }
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl));
        req.setTitle("Kenz News " + info.versionName);
        req.setDescription("Đang tải bản cập nhật");
        // VISIBILITY_HIDDEN needs DOWNLOAD_WITHOUT_NOTIFICATION,
        // a signature|privileged permission a normal app can never
        // hold — enqueue() threw SecurityException and the
        // download never started ("Không bắt đầu tải được",
        // reported 2026-11-18). A normal app must use a visible
        // notification; the in-app progress dialog still shows
        // the live % + MB, so the system one only appears when
        // the download finishes.
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(true);
        req.setDestinationInExternalFilesDir(act, Environment.DIRECTORY_DOWNLOADS, APK_FILE);
        long id;
        try { id = dm.enqueue(req); } catch (Exception e) { android.util.Log.e("KenzNewsOTA", "enqueue failed", e); toast(act, "Không bắt đầu tải được"); return; }
        p.edit().putLong("ota_pending_download", id).putInt("ota_pending_vc", info.versionCode).apply();
        showProgress(act, dm, id, info);
    }

    private static void showProgress(final Activity act, final DownloadManager dm, final long id, final OtaInfo info) {
        final LinearLayout lay = new LinearLayout(act);
        lay.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        lay.setPadding(pad, pad, pad, pad);
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        final TextView txt = new TextView(act);
        txt.setText("Đang tải… 0%");
        txt.setPadding(0, pad / 2, 0, 0);
        lay.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lay.addView(txt);
        final AlertDialog dlg = new AlertDialog.Builder(act, Theme.dialogTheme(act))
                .setTitle("Đang tải bản cập nhật " + info.versionName)
                .setView(lay)
                .setCancelable(false)
                .setNegativeButton("Hủy tải", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        dm.remove(id);
                        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ota_pending_download").remove("ota_pending_vc").apply();
                    }
                })
                .show();
        Theme.styleDialog(dlg, act);
        new Thread(new Runnable() {
            public void run() {
                boolean done = false;
                while (!done) {
                    DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                    Cursor c = null;
                    try {
                        c = dm.query(q);
                        if (c != null && c.moveToFirst()) {
                            int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                            long soFar = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                            long total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                            final int pct = total > 0 ? (int) (soFar * 100 / total) : 0;
                            final String label = "Đang tải… " + pct + "%" + (total > 0 ? " (" + fmtMb(soFar) + " / " + fmtMb(total) + ")" : "");
                            act.runOnUiThread(new Runnable() { public void run() { bar.setProgress(pct); txt.setText(label); } });
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                done = true;
                                act.runOnUiThread(new Runnable() { public void run() { try { dlg.dismiss(); } catch (Exception ignored) {} installApk(act, id); } });
                            } else if (status == DownloadManager.STATUS_FAILED) {
                                done = true;
                                act.runOnUiThread(new Runnable() { public void run() { try { dlg.dismiss(); } catch (Exception ignored) {} toast(act, "Tải thất bại — thử lại sau"); } });
                            }
                        } else {
                            done = true; // entry gone (user cancelled)
                        }
                    } catch (Exception e) { done = true; }
                    finally { if (c != null) c.close(); }
                    if (!done) { try { Thread.sleep(300); } catch (InterruptedException ignored) {} }
                }
            }
        }).start();
    }

    private static void installApk(final Activity act, long downloadId) {
        DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = dm != null ? dm.getUriForDownloadedFile(downloadId) : null;
        if (uri == null) { toast(act, "Không tìm thấy file đã tải"); return; }
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, "Bật \"Cho phép cài đặt ứng dụng không xác định\" cho Kenz News rồi quay lại bấm Tải về", Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName()));
            try { act.startActivity(i); } catch (Exception e) { toast(act, "Không mở được trang cài đặt quyền"); }
            return;
        }
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("ota_pending_download").remove("ota_pending_vc").apply();
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.startActivity(install); } catch (Exception e) { toast(act, "Không mở được trình cài đặt"); }
    }

    // ---- helpers ----
    private static String fmtMb(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void toast(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() { public void run() { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show(); } });
    }

    private static String downloadText(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "KenzNews-OTA");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    // Minimal parser for our known version.json shape (no JSON lib).
    private static OtaInfo parse(String json) {
        if (json == null) return null;
        OtaInfo info = new OtaInfo();
        info.versionCode = extractInt(json, "\"versionCode\"");
        info.versionName = extractString(json, "\"versionName\"");
        info.apkUrl = extractString(json, "\"apkUrl\"");
        info.changelog = unescape(extractString(json, "\"changelog\""));
        return info;
    }

    private static int extractInt(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return -1;
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return -1;
        int e = c + 1;
        while (e < json.length() && (json.charAt(e) == ' ' || json.charAt(e) == '\t')) e++;
        int s = e;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        if (e == s) return -1;
        try { return Integer.parseInt(json.substring(s, e)); } catch (Exception ex) { return -1; }
    }

    private static String extractString(String json, String key) {
        int i = json.indexOf(key);
        if (i < 0) return "";
        int c = json.indexOf(':', i + key.length());
        if (c < 0) return "";
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\' && j + 1 < json.length()) { sb.append(ch); sb.append(json.charAt(j + 1)); j++; continue; }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
