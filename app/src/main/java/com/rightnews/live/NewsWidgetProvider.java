package com.rightnews.live;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.text.SimpleDateFormat;
import java.util.*;
// v3.12: home-screen widget — the newest headlines from the
// news cache (the same file MainActivity reads). No periodic
// update: the widget repaints whenever anyone saves fresh
// cache (MainActivity refresh, Splash first fetch,
// NewsJobService background check) via updateAll().
// v3.13: the widget follows the APP theme (Theme.isLight) —
// with the new "Theo hệ thống" default that means it follows
// the device too. A device theme flip also repaints it via
// onReceive(CONFIGURATION_CHANGED); if the system never
// delivers that broadcast, the next cache save or Settings
// theme change repaints it instead.
// v3.14: two fixes + customization (Settings → WIDGET).
// (1) THE LOADING-SPINNER BUG: buildViews used
// TranslateUtil.displayTitleSync, which waits up to 8s per
// uncached title on a network translate call — 4 fresh
// headlines could stall onUpdate for 32s on the main thread,
// and the launcher gave up and showed its loading spinner
// (his 13:14 screenshot: white box + spinner, no headlines).
// The widget now uses displayTitleCacheOnly (cached
// Vietnamese title, else the original — no network, no wait)
// and each headline is wrapped so one bad item cannot kill
// the whole RemoteViews.
// (2) TRANSPARENT LIKE THE APP: the card is now translucent
// glass (60% by default, like the app's bars) instead of
// solid, so the wallpaper shows through; true blur is not
// possible in a widget, translucency is the closest match.
// (3) CUSTOMIZATION in Settings: transparency 60/80/100%,
// headline count 2/4/6, widget theme Theo app / Luôn tối /
// Luôn sáng.
public class NewsWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context c, AppWidgetManager awm, int[] ids) {
        // v3.36: paint the exact ids the system hands us, not
        // just the ComponentName — a freshly added widget gets
        // its RemoteViews on this very call, even if the
        // ComponentName lookup lags behind.
        try {
            RemoteViews rv = buildViews(c);
            for (int id : ids) awm.updateAppWidget(id, rv);
        } catch (Exception ignored) {}
        updateAll(c);
    }
    @Override public void onEnabled(Context c) {
        updateAll(c);
    }
    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (Intent.ACTION_CONFIGURATION_CHANGED.equals(i.getAction())) updateAll(c);
    }
    public static void updateAll(Context c) {
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(c);
            ComponentName cn = new ComponentName(c, NewsWidgetProvider.class);
            if (awm.getAppWidgetIds(cn).length == 0) return;
            awm.updateAppWidget(cn, buildViews(c));
        } catch (Exception ignored) {}
    }
    // v3.14: widget prefs (kenzelections). Alpha is stored as
    // an index: 0 = 60% (trong suốt, default), 1 = 80% (mờ),
    // 2 = 100% (đục). Count 2/4/6, default 4. Theme 0 = theo
    // app (default), 1 = luôn tối, 2 = luôn sáng.
    public static int widgetAlphaPct(Context c) {
        int i = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("widget_alpha", 0);
        return i == 1 ? 80 : i == 2 ? 100 : 60;
    }
    public static void setWidgetAlphaIdx(Context c, int i) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("widget_alpha", i).apply();
    }
    public static int widgetAlphaIdx(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("widget_alpha", 0);
    }
    public static int widgetCount(Context c) {
        int n = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("widget_count", 4);
        return (n == 2 || n == 6) ? n : 4;
    }
    public static void setWidgetCount(Context c, int n) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("widget_count", n).apply();
    }
    public static int widgetThemeMode(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("widget_theme", 0);
    }
    public static void setWidgetThemeMode(Context c, int m) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("widget_theme", m).apply();
    }
    public static boolean widgetLight(Context c) {
        int m = widgetThemeMode(c);
        if (m == 1) return false;
        if (m == 2) return true;
        return Theme.isLight(c);
    }
    // v3.36: buildViews must NEVER throw — updateAll() swallows
    // every exception, so one bad headline used to drop the
    // whole update and leave the launcher on its white
    // placeholder + spinner. Now any failure falls back to a
    // minimal card (title + "open the app" line) instead of
    // nothing, so the widget always shows content.
    private static RemoteViews buildViews(Context c) {
        try {
            return buildViewsInner(c);
        } catch (Exception e) {
            try {
                RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_news);
                rv.setInt(R.id.widgetRoot, "setBackgroundResource", R.drawable.widget_bg);
                rv.setTextViewText(R.id.widgetHead1, "Mở app Kenz News để tải tin.");
                rv.setTextColor(R.id.widgetHead1, 0xFFFFFFFF);
                Intent it = new Intent(c, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(c, 0, it,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
                return rv;
            } catch (Exception ignored) {
                return new RemoteViews(c.getPackageName(), R.layout.widget_news);
            }
        }
    }
    private static RemoteViews buildViewsInner(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_news);
        // v3.14: glass card — translucency + theme from prefs.
        boolean light = widgetLight(c);
        int alpha = widgetAlphaPct(c);
        int bg;
        if (light) bg = alpha == 60 ? R.drawable.widget_bg_l60 : alpha == 80 ? R.drawable.widget_bg_l80 : R.drawable.widget_bg_light;
        else bg = alpha == 60 ? R.drawable.widget_bg_d60 : alpha == 80 ? R.drawable.widget_bg_d80 : R.drawable.widget_bg;
        rv.setInt(R.id.widgetRoot, "setBackgroundResource", bg);
        rv.setTextColor(R.id.widgetTitle, light ? 0xFF0A1B4A : 0xFFFFFFFF);
        rv.setTextColor(R.id.widgetTime, light ? 0xFF64748B : 0xFFB9C6E0);
        rv.setInt(R.id.widgetDivider, "setBackgroundColor", light ? 0xFFE2E8F0 : 0xFF334155);
        List<NewsItem> items = NewsStore.loadCache(c);
        int count = widgetCount(c);
        int[] headIds = {R.id.widgetHead1, R.id.widgetHead2, R.id.widgetHead3, R.id.widgetHead4, R.id.widgetHead5, R.id.widgetHead6};
        int[] rowIds = {R.id.widgetRow1, R.id.widgetRow2, R.id.widgetRow3, R.id.widgetRow4, R.id.widgetRow5, R.id.widgetRow6};
        for (int i = 0; i < 6; i++) {
            // v3.14: a row shows only when it is inside the
            // chosen count AND there is a headline for it; a
            // row with no headline hides instead of leaving a
            // blank red dot.
            boolean show = i < count && i < items.size();
            rv.setViewVisibility(rowIds[i], show ? android.view.View.VISIBLE : android.view.View.GONE);
            if (!show) continue;
            try {
                String t = TranslateUtil.displayTitleCacheOnly(c, items.get(i).key(), items.get(i).title);
                rv.setTextViewText(headIds[i], t == null ? "" : t);
            } catch (Exception ignored) {
                rv.setTextViewText(headIds[i], items.get(i).title == null ? "" : items.get(i).title);
            }
            rv.setTextColor(headIds[i], light ? 0xFF1E293B : 0xFFFFFFFF);
        }
        if (items.isEmpty()) {
            rv.setViewVisibility(R.id.widgetRow1, android.view.View.VISIBLE);
            rv.setTextViewText(R.id.widgetHead1, "Chưa có tin — mở app để tải.");
            rv.setTextColor(R.id.widgetHead1, light ? 0xFF1E293B : 0xFFFFFFFF);
        }
        rv.setTextViewText(R.id.widgetTime, "Cập nhật " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        Intent it = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
        return rv;
    }
}
