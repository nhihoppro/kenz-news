package com.rightnews.live;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.util.List;
// v2.0 (ý tưởng 3 — widget): scoreboard + top headline.
// Reads the same caches the app itself saves (ResultsStore
// for seats, NewsStore for the newest headline), so the
// widget never fetches on its own — it refreshes on the
// system's 30-minute tick and whenever the app finishes a
// results refresh (MainActivity calls updateAll then).
// Tap anywhere: the provider writes open_tab=1 (the same
// pref Settings uses) and opens MainActivity, which picks
// it up in onResume and shows the Election tab.
public class ScoreboardWidgetProvider extends AppWidgetProvider {
    public static final String ACTION_OPEN = "com.rightnews.live.OPEN_ELECTION";
    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    @Override public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        if (ACTION_OPEN.equals(i.getAction())) {
            try {
                c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("open_tab", 1).apply();
                Intent main = new Intent(c, MainActivity.class);
                main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                c.startActivity(main);
            } catch (Exception ignored) {}
        }
    }
    public static void updateAll(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, ScoreboardWidgetProvider.class));
            if (ids == null || ids.length == 0) return;
            List<ResultsFetcher.Result> res = ResultsStore.load(c);
            List<NewsItem> news = NewsStore.loadCache(c);
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_scoreboard);
            String[] labels = {"Hạ viện", "Thượng viện", "Thống đốc"};
            int[] views = {R.id.wHouse, R.id.wSenate, R.id.wGovernor};
            for (int i = 0; i < 3; i++) {
                String text = labels[i] + ": chưa có kết quả";
                if (res != null && i < res.size() && res.get(i) != null && res.get(i).ok) {
                    text = labels[i] + " " + res.get(i).demSeats + "–" + res.get(i).repSeats;
                }
                v.setTextViewText(views[i], text);
            }
            String head = "Chưa có tin mới";
            if (news != null && !news.isEmpty() && news.get(0) != null && news.get(0).title != null && !news.get(0).title.isEmpty()) {
                head = news.get(0).title;
            }
            v.setTextViewText(R.id.wHeadline, head);
            String foot = "Cập nhật: " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()) + " • Chạm để mở tab Bầu cử";
            v.setTextViewText(R.id.wFoot, foot);
            Intent open = new Intent(c, ScoreboardWidgetProvider.class);
            open.setAction(ACTION_OPEN);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            v.setOnClickPendingIntent(R.id.widgetRoot, pi);
            for (int id : ids) m.updateAppWidget(id, v);
        } catch (Exception ignored) {}
    }
}
