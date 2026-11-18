package com.rightnews.live;
import android.app.*;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.*;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.*;
public class NewsJobService extends JobService {
    public static final int JOB_ID = 1001;
    public static void schedule(Context c) {
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(JOB_ID,
                    new android.content.ComponentName(c, NewsJobService.class))
                .setPeriodic(15 * 60 * 1000L)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build();
            js.schedule(job);
        } catch (Exception ignored) {}
    }
    public static void cancel(Context c) {
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            js.cancel(JOB_ID);
        } catch (Exception ignored) {}
    }
    // v3.12: the 15-min check runs when news notify is on OR
    // when keyword alerts exist (either one alone is enough
    // reason to check); otherwise the job is cancelled.
    public static void ensureScheduled(Context c) {
        if (NewsStore.notifyOn(c) || !NewsStore.keywords(c).isEmpty()) schedule(c);
        else cancel(c);
    }
    // v3.12: accent-insensitive match (same rule as the
    // in-app search) so "kinh te" matches "Kinh tế".
    private static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(java.util.Locale.US).replace('\u0111', 'd');
    }
    private static List<NewsItem> keywordHits(Context c, List<NewsItem> fresh) {
        List<String> kws = NewsStore.keywords(c);
        List<NewsItem> out = new ArrayList<>();
        if (kws.isEmpty() || fresh == null) return out;
        for (NewsItem it : fresh) {
            String hay = norm(it.title) + " " + norm(it.desc == null ? "" : it.desc);
            for (String k : kws) {
                if (!norm(k).isEmpty() && hay.contains(norm(k))) { out.add(it); break; }
            }
        }
        return out;
    }
    @Override public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    List<Source> src = Sources.load(NewsJobService.this);
                    RssFetcher.Result r = RssFetcher.fetch(src);
                    if (!r.items.isEmpty()) {
                        Set<String> seen = NewsStore.seen(NewsJobService.this);
                        List<NewsItem> fresh = new ArrayList<>();
                        for (NewsItem it : r.items) if (!seen.contains(it.key())) fresh.add(it);
                        NewsStore.saveCache(NewsJobService.this, r.items);
                        // v3.12: the home-screen widget reads the
                        // same cache — repaint it.
                        NewsWidgetProvider.updateAll(NewsJobService.this);
                        Set<String> all = new HashSet<>(seen);
                        for (NewsItem it : r.items) all.add(it.key());
                        NewsStore.saveSeen(NewsJobService.this, all);
                        // v3.12: news notify only fires when its
                        // own toggle is on (the job may now run
                        // for keyword alerts alone).
                        if (!fresh.isEmpty() && NewsStore.notifyOn(NewsJobService.this)) notifyNew(fresh);
                        List<NewsItem> hits = keywordHits(NewsJobService.this, fresh);
                        if (!hits.isEmpty()) notifyKeywords(hits);
                    }
                } catch (Exception ignored) {}
                jobFinished(params, false);
            }
        }).start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
    private void notifyNew(List<NewsItem> fresh) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("tin-moi", "Tin mới", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            NewsItem first = fresh.get(0);
            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, "tin-moi")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(fresh.size() == 1 ? "Có tin mới" : "Có " + fresh.size() + " tin mới")
                .setContentText(TranslateUtil.displayTitleSync(this, first.key(), first.title))
                .setContentIntent(pi).setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= 26) nm.notify(2001, b.build());
            else nm.notify(2001, b.build());
        } catch (Exception ignored) {}
    }
    // v3.12: keyword alerts — fresh headlines containing a
    // watched keyword. Own channel ("tu-khoa") so the user
    // can silence these without losing news notify.
    private void notifyKeywords(List<NewsItem> hits) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("tu-khoa", "Từ khóa theo dõi", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            NewsItem first = hits.get(0);
            Intent i = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, "tu-khoa")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(hits.size() == 1 ? "Tin khớp từ khóa theo dõi" : "Có " + hits.size() + " tin khớp từ khóa")
                .setContentText(TranslateUtil.displayTitleSync(this, first.key(), first.title))
                .setContentIntent(pi).setAutoCancel(true);
            nm.notify(2002, b.build());
        } catch (Exception ignored) {}
    }
}
