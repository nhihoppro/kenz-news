package com.rightnews.live;
import android.os.*;
import java.io.*;
import java.net.*;
/** Short-text translation via the unofficial gtx endpoint (no API key).
 *  Best-effort only: on ANY failure the callback gets null and the UI
 *  must keep showing the original text. Never cache a failure as a
 *  translation. Whole-page translation is done by ArticleActivity via
 *  the translate.goog proxy instead. */
public class TranslateUtil {
    public interface Cb { void onResult(String vi); }
    // v1.14 FIX — why title translation NEVER worked on device: the
    // old code sent no User-Agent, so Android sent its default
    // Dalvik UA, and Google answers that with a 302 to /sorry (bot
    // check) instead of a translation. The app then parsed the
    // /sorry HTML as JSON, found nothing, and left every title in
    // English. Verified 2026-11-04: Dalvik UA -> 302 /sorry, a
    // Chrome UA -> 200 + Vietnamese. So every gtx request now
    // carries a Chrome UA, follows no redirects, and only parses
    // a 200 body — a block page can never again masquerade as a
    // (missing) translation.
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    public static String chromeUa() { return CHROME_UA; }
    private static String fetchGtx(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            String q = URLEncoder.encode(text, "UTF-8");
            URL u = new URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=vi&dt=t&q=" + q);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", CHROME_UA);
            c.setConnectTimeout(10000); c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return firstSegment(sb.toString());
        } catch (Exception ignored) { return null; }
    }
    public static void toVietnamese(final String text, final Cb cb) {
        if (text == null || text.isEmpty()) { cb.onResult(null); return; }
        new Thread(new Runnable() {
            public void run() {
                final String res = fetchGtx(text);
                new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { cb.onResult(res); } });
            }
        }).start();
    }
    private static String firstSegment(String json) {
        // Response shape: [[["dịch","orig",...]],...] — take the first quoted string.
        // v1.26 FIX — the old code handled escapes by appending the
        // character after the backslash verbatim, so Google's
        // U+200B (zero-width space) escapes came out as the literal
        // text "u200b" in translated titles (seen on a video title
        // in a 2026-11-04 screenshot). This is a real JSON string
        // unescape: \\ \" \/ \n \t \r \b \f and U+XXXX are all
        // decoded; U+200B becomes a space (Google emits it where a
        // word gap belongs, so dropping it would jam two words
        // together) and U+FEFF is dropped; runs of spaces collapse
        // to one. Raw U+200B/U+FEFF chars (not escapes) get the
        // same treatment at the end, and \n/\t/\r become spaces because a list
        // title is a single line.
        try {
            int i = json.indexOf("[[[\"");
            if (i < 0) return null;
            int start = i + 4; StringBuilder sb = new StringBuilder();
            for (int j = start; j < json.length(); j++) {
                char ch = json.charAt(j);
                if (ch == '\\' && j + 1 < json.length()) {
                    char esc = json.charAt(j + 1);
                    if (esc == 'u' && j + 5 < json.length()) {
                        try {
                            int code = Integer.parseInt(json.substring(j + 2, j + 6), 16);
                            if (code == 0x200B) sb.append(' ');
                            else if (code != 0xFEFF) sb.append((char) code);
                            j += 5;
                        } catch (Exception ignored) { j++; }
                    } else if (esc == 'n' || esc == 't' || esc == 'r') { sb.append(' '); j++; }
                    else if (esc == 'b' || esc == 'f') { j++; }
                    else { sb.append(esc); j++; }
                }
                else if (ch == '"') break;
                else sb.append(ch);
            }
            String s = sb.toString().replace((char) 0x200B, ' ').replace((char) 0xFEFF, ' ').trim().replaceAll("  +", " ");
            return s.isEmpty() ? null : s;
        } catch (Exception e) { return null; }
    }
    /** Whole-page translate.goog URL, or null if the original cannot be parsed.
     *  v1.5 FIX: the old code built "host-translate.goog" (a dead host).
     *  The real proxy is "host-dashed.translate.goog" — dot before
     *  translate, not a dash. That one wrong character is why the in-app
     *  "Dịch: VI" button never showed a translated page. Verified 2026-11-03:
     *  the dashed-dot form returns the publisher's page, the dash form
     *  returns nothing. */
    public static String pageUrl(String original) {
        try {
            android.net.Uri u = android.net.Uri.parse(original);
            String host = u.getHost(); if (host == null) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            String q = u.getQuery();
            String base = "https://" + host.replace(".", "-") + ".translate.goog" + path;
            String tail = "_x_tr_sl=auto&_x_tr_tl=vi&_x_tr_hl=vi";
            return base + (q == null || q.isEmpty() ? "?" + tail : "?" + q + "&" + tail);
        } catch (Exception e) { return null; }
    }

    // ---- List-title translation (v1.5, parallel since v1.11) ----
    // Titles in the list are translated too, not only the article page.
    // Results are cached by article key in SharedPreferences, so the
    // next launch shows the Vietnamese title instantly. Original is
    // always kept as fallback. v1.11: three workers pull from the
    // same queue instead of one, so the first screen of titles
    // finishes roughly 3x faster; each worker still waits 200ms
    // between its own calls, so the unofficial gtx endpoint is
    // never hammered with dozens of parallel requests. A failed
    // title is simply left untranslated and re-queued on the next
    // translateTitles call, because the cache never holds it.
    public static final java.util.Map<String, String> TITLE_CACHE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<String, String>());
    private static final java.util.List<NewsItem> TITLE_QUEUE = new java.util.ArrayList<>();
    private static int titleWorkersActive = 0;
    private static final int TITLE_WORKERS = 3;
    public static boolean translateTitlesEnabled(android.content.Context c) {
        return c.getSharedPreferences("kenzelections", android.content.Context.MODE_PRIVATE)
                .getBoolean("translate_titles", true);
    }
    public static void setTranslateTitlesEnabled(android.content.Context c, boolean v) {
        c.getSharedPreferences("kenzelections", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("translate_titles", v).apply();
    }
    public static String titleVi(String key) {
        if (key == null) return null;
        return TITLE_CACHE.get(key);
    }
    // v1.42: notification titles in Vietnamese. The news job and
    // the playback service both show titles that the list would
    // translate, but notifications used the raw original — the
    // user's 2026-11-04 screenshot (CNN live title, English,
    // with the Dừng action) is the playback one. This sync
    // variant is ONLY for background threads (the news job):
    // it waits at most 8s for the live translation, because
    // toVietnamese answers on the main looper — calling this
    // on the main thread would deadlock the wait. The playback
    // service (main thread) instead posts the original first
    // and updates the notification when the translation lands.
    // Follows the same translate-titles toggle as the list;
    // any failure returns the original, never blank.
    public static String displayTitleSync(android.content.Context c, String key, String original) {
        if (original == null || original.isEmpty()) return original;
        if (c == null || !translateTitlesEnabled(c)) return original;
        try {
            loadTitleCache(c);
            String cached = key == null ? null : TITLE_CACHE.get(key);
            if (cached != null && !cached.isEmpty()) return decodeUnicodeEscapes(cached);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] out = new String[1];
            toVietnamese(original, new Cb() { public void onResult(String vi) { out[0] = vi; latch.countDown(); } });
            try { latch.await(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            if (out[0] != null && !out[0].isEmpty()) return decodeUnicodeEscapes(out[0]);
        } catch (Exception ignored) {}
        return original;
    }
    // v3.14: cache-only title, for the widget ONLY — never waits
    // on the network. displayTitleSync above blocks up to 8s per
    // uncached title (CountDownLatch on the translate call), so
    // 4 fresh headlines could stall AppWidgetProvider.onUpdate
    // for 32s on the main thread — and the launcher gives up and
    // shows its loading spinner instead of the widget (his
    // 13:14 screenshot: white box + spinner, no headlines).
    // The widget shows the cached Vietnamese title when there
    // is one, else the original title; the app itself still
    // translates normally.
    public static String displayTitleCacheOnly(android.content.Context c, String key, String original) {
        if (original == null || original.isEmpty()) return original;
        if (c == null || !translateTitlesEnabled(c)) return original;
        try {
            loadTitleCache(c);
            String cached = key == null ? null : TITLE_CACHE.get(key);
            if (cached != null && !cached.isEmpty()) return decodeUnicodeEscapes(cached);
        } catch (Exception ignored) {}
        return original;
    }
    // v3.15: decode literal backslash-u escapes left in stored text.
    // An old translation run cached raw gtx escapes, so a title
    // can show a raw ampersand escape instead of "&"
    // (his 13:24 screenshot). The v1.26 fix only dropped titles
    // containing literal "u200b" — decoding keeps the title and
    // fixes it instead. U+200B becomes a space and U+FEFF is
    // dropped, matching firstSegment.
    public static String decodeUnicodeEscapes(String s) {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'u' && i + 5 < s.length()) {
                    try {
                        int code = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                        if (code == 0x200B) out.append(' ');
                        else if (code != 0xFEFF) out.append((char) code);
                        i += 5;
                        continue;
                    } catch (Exception ignored) {}
                } else if (n == 'n' || n == 't' || n == 'r') { out.append(' '); i++; continue; }
                else if (n == '"') { out.append('"'); i++; continue; }
                else if (n == '/') { out.append('/'); i++; continue; }
                else if (n == '\\') { out.append('\\'); i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }
    public static void loadTitleCache(android.content.Context c) {
        try {
            String saved = c.getSharedPreferences("kenzelections", android.content.Context.MODE_PRIVATE)
                    .getString("title_vi_v1", "");
            if (saved == null || saved.isEmpty()) return;
            for (String line : saved.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    String v = decodeUnicodeEscapes(line.substring(tab + 1));
                    // v1.26: drop titles corrupted by the old
                    // firstSegment decoder (literal "u200b" text)
                    // so they re-translate correctly on next use
                    // instead of staying broken in the cache.
                    if (v.indexOf("u200b") < 0) TITLE_CACHE.put(line.substring(0, tab), v);
                }
            }
        } catch (Exception ignored) {}
    }
    private static void saveTitleCache(android.content.Context c) {
        try {
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : TITLE_CACHE.entrySet())
                sb.append(e.getKey().replace("\t", " ").replace("\n", " ")).append("\t")
                  .append(e.getValue().replace("\t", " ").replace("\n", " ")).append("\n");
            c.getSharedPreferences("kenzelections", android.content.Context.MODE_PRIVATE)
                    .edit().putString("title_vi_v1", sb.toString()).apply();
        } catch (Exception ignored) {}
    }
    public static void translateTitles(final android.content.Context appCtx,
                                       java.util.List<NewsItem> items, final Runnable onUpdate) {
        if (appCtx == null || items == null || !translateTitlesEnabled(appCtx)) return;
        synchronized (TITLE_QUEUE) {
            for (NewsItem it : items) {
                if (it == null || it.key() == null || it.key().isEmpty()) continue;
                if (TITLE_CACHE.containsKey(it.key())) continue;
                boolean queued = false;
                for (NewsItem q : TITLE_QUEUE) if (it.key().equals(q.key())) { queued = true; break; }
                if (!queued) TITLE_QUEUE.add(it);
            }
            // Workers already running will pick the new items up;
            // only start workers when the pool is fully idle, so a
            // second call cannot spawn a second pool on top.
            if (titleWorkersActive > 0) return;
            titleWorkersActive = TITLE_WORKERS;
        }
        Runnable worker = new Runnable() {
            public void run() {
                final Handler mainH = new Handler(Looper.getMainLooper());
                while (true) {
                    NewsItem it;
                    synchronized (TITLE_QUEUE) {
                        if (TITLE_QUEUE.isEmpty()) { titleWorkersActive--; break; }
                        it = TITLE_QUEUE.remove(0);
                    }
                    String vi = translateSync(it.title);
                    if (vi != null && !vi.equals(it.title)) {
                        TITLE_CACHE.put(it.key(), vi);
                        saveTitleCache(appCtx);
                    }
                    if (onUpdate != null) mainH.post(onUpdate);
                    try { Thread.sleep(200); } catch (Exception ignored) {}
                }
            }
        };
        for (int i = 0; i < TITLE_WORKERS; i++) new Thread(worker).start();
    }
    private static String translateSync(String text) {
        return fetchGtx(text);
    }
}
