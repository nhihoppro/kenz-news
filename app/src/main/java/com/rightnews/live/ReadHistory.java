package com.rightnews.live;

import android.content.Context;
import java.io.*;
import java.util.*;

// Nightly: reading history — the articles the user opened.
// Stored as a TSV file (same format as NewsStore's saved
// list), newest read first, capped at 100. A static in-memory
// set of read keys keeps NewsAdapter's dim check cheap (no
// file read per row bind).
public class ReadHistory {
    private static final String FILE = "read_history.txt";
    private static final int MAX = 100;
    private static Set<String> readKeys = null;

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ");
    }

    private static synchronized void ensureKeys(Context c) {
        if (readKeys != null) return;
        readKeys = new HashSet<>();
        for (NewsItem it : loadHistory(c)) readKeys.add(it.key());
    }

    public static synchronized void markRead(Context c, NewsItem it) {
        if (c == null || it == null || it.key() == null) return;
        ensureKeys(c);
        List<NewsItem> h = loadHistory(c);
        String key = it.key();
        for (int i = 0; i < h.size(); i++) if (key.equals(h.get(i).key())) h.remove(i);
        h.add(0, it);
        while (h.size() > MAX) h.remove(h.size() - 1);
        saveHistory(c, h);
        readKeys.add(key);
    }

    public static synchronized List<NewsItem> loadHistory(Context c) {
        List<NewsItem> out = new ArrayList<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.openFileInput(FILE), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length >= 5) {
                    NewsItem ni = new NewsItem(f[0], f[1], f[2], f[3], Long.parseLong(f[4]), f.length >= 6 ? f[5] : "");
                    if (f.length >= 7) ni.channelId = f[6];
                    if (f.length >= 8) ni.category = f[7];
                    out.add(ni);
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveHistory(Context c, List<NewsItem> items) {
        try {
            FileOutputStream out = c.openFileOutput(FILE, Context.MODE_PRIVATE);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
            for (NewsItem it : items) {
                w.println(esc(it.title) + "\t" + esc(it.link) + "\t" + esc(it.source) + "\t" + esc(it.desc) + "\t" + it.time + "\t" + esc(it.imageUrl) + "\t" + esc(it.channelId) + "\t" + esc(it.category));
            }
            w.close();
        } catch (Exception ignored) {}
    }

    public static synchronized boolean isRead(Context c, String key) {
        if (key == null) return false;
        ensureKeys(c);
        return readKeys.contains(key);
    }

    // Remove one article from the history (long-press in the
    // history list).
    public static synchronized void remove(Context c, String key) {
        if (c == null || key == null) return;
        ensureKeys(c);
        List<NewsItem> h = loadHistory(c);
        for (int i = 0; i < h.size(); i++) if (key.equals(h.get(i).key())) h.remove(i);
        saveHistory(c, h);
        readKeys.remove(key);
    }

    public static synchronized void clearHistory(Context c) {
        try { c.deleteFile(FILE); } catch (Exception ignored) {}
        if (readKeys != null) readKeys.clear();
    }
}
