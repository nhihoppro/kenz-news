package com.rightnews.live;
import android.content.Context;
import java.io.*;
import java.util.*;
public class NewsStore {
    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " "); }
    public static void saveCache(Context c, List<NewsItem> items) {
        try {
            FileOutputStream out = c.openFileOutput("news_cache.txt", Context.MODE_PRIVATE);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
            int n = Math.min(items.size(), 200);
            for (int i = 0; i < n; i++) {
                NewsItem it = items.get(i);
                w.println(esc(it.title) + "\t" + esc(it.link) + "\t" + esc(it.source) + "\t" + esc(it.desc) + "\t" + it.time + "\t" + esc(it.imageUrl) + "\t" + esc(it.category));
            }
            w.close();
        } catch (Exception ignored) {}
    }
    public static List<NewsItem> loadCache(Context c) {
        List<NewsItem> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.openFileInput("news_cache.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5) {
                    NewsItem ni = new NewsItem(f[0], f[1], f[2], f[3], Long.parseLong(f[4]), f.length >= 6 ? f[5] : "");
                    if (f.length >= 7) ni.category = f[6];
                    out.add(ni);
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return out;
    }
    public static Set<String> seen(Context c) {
        return new HashSet<>(c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getStringSet("seen_keys", new HashSet<String>()));
    }
    public static void saveSeen(Context c, Set<String> keys) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putStringSet("seen_keys", keys).apply();
    }
    public static boolean notifyOn(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("notify_on", false);
    }
    public static void setNotifyOn(Context c, boolean v) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("notify_on", v).apply();
    }
    // v3.12: keyword alerts — one keyword per line in the
    // "keyword_alerts" pref; the background job notifies when
    // a fresh headline contains one (accent-insensitive).
    public static List<String> keywords(Context c) {
        List<String> out = new ArrayList<>();
        String raw = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getString("keyword_alerts", "");
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String k = line.trim();
            if (!k.isEmpty() && !out.contains(k)) out.add(k);
        }
        return out;
    }
    public static void setKeywords(Context c, List<String> kws) {
        StringBuilder sb = new StringBuilder();
        for (String k : kws) {
            String t = k == null ? "" : k.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(t);
        }
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putString("keyword_alerts", sb.toString()).apply();
    }
    // v3.1: saved-for-later — same TSV format as the cache,
    // separate file. Keyed by item key (link, else title);
    // toggle returns true when the item is now saved.
    public static List<NewsItem> loadSaved(Context c) {
        List<NewsItem> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.openFileInput("saved_news.txt"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5) {
                    NewsItem it = new NewsItem(f[0], f[1], f[2], f[3], Long.parseLong(f[4]), f.length >= 6 ? f[5] : "");
                    if (f.length >= 7) it.channelId = f[6];
                    if (f.length >= 8) it.category = f[7];
                    out.add(it);
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return out;
    }
    public static void saveSaved(Context c, List<NewsItem> items) {
        try {
            FileOutputStream out = c.openFileOutput("saved_news.txt", Context.MODE_PRIVATE);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
            int n = Math.min(items.size(), 200);
            for (int i = 0; i < n; i++) {
                NewsItem it = items.get(i);
                w.println(esc(it.title) + "\t" + esc(it.link) + "\t" + esc(it.source) + "\t" + esc(it.desc) + "\t" + it.time + "\t" + esc(it.imageUrl) + "\t" + esc(it.channelId) + "\t" + esc(it.category));
            }
            w.close();
        } catch (Exception ignored) {}
    }
    public static boolean isSaved(Context c, String key) {
        if (key == null) return false;
        for (NewsItem it : loadSaved(c)) if (key.equals(it.key())) return true;
        return false;
    }
    public static boolean toggleSaved(Context c, NewsItem it) {
        List<NewsItem> saved = loadSaved(c);
        String key = it.key();
        for (int i = 0; i < saved.size(); i++) {
            if (key.equals(saved.get(i).key())) { saved.remove(i); saveSaved(c, saved); return false; }
        }
        saved.add(0, it);
        saveSaved(c, saved);
        return true;
    }
}
