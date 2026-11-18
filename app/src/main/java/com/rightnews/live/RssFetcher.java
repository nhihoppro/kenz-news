package com.rightnews.live;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
public class RssFetcher {
    public static class Result {
        public List<NewsItem> items = new ArrayList<>();
        public Map<String,String> status = new HashMap<>();
    }
    public static Result fetch(List<Source> sources) {
        Result r = new Result();
        // v3.2: one outlet, many feeds — fetch every feed of
        // every enabled outlet; each item carries its feed's
        // category (item.source stays the outlet name).
        for (Source s : sources) {
            if (!s.enabled) { r.status.put(s.url, "Đã tắt"); continue; }
            for (Source.Feed fd : s.feeds) {
                try {
                    List<NewsItem> got = fetchOne(fd.url, s.name, fd.category);
                    r.items.addAll(got);
                    r.status.put(fd.url, got.isEmpty() ? "Không có tin (feed rỗng / sai định dạng)" : "OK — " + got.size() + " tin");
                } catch (Exception e) {
                    r.status.put(fd.url, "Lỗi: " + e.getClass().getSimpleName());
                }
            }
        }
        // Dedupe by link, newest first.
        Map<String,NewsItem> seen = new LinkedHashMap<>();
        for (NewsItem it : r.items) if (!seen.containsKey(it.key())) seen.put(it.key(), it);
        r.items = new ArrayList<>(seen.values());
        Collections.sort(r.items, new Comparator<NewsItem>() {
            public int compare(NewsItem a, NewsItem b) { return Long.compare(b.time, a.time); }
        });
        return r;
    }
    // v1.40: parallel variant for the video tab. fetch() runs
    // sources sequentially — 9 YouTube feeds at up to 12s
    // connect + 15s read each could stall the tab for minutes
    // on a slow connection (the user's 2026-11-04 11:44-11:47
    // case: "Đang tải video…" for 3+ minutes, never finishing).
    // In parallel the phase is bounded by the single slowest
    // feed, and each thread is joined with a timeout so a hung
    // lookup can never stall the callback forever. Per-source
    // slots keep threads from sharing mutable state; merging,
    // dedupe and sort are identical to fetch().
    public static Result fetchParallel(final List<Source> sources) {
        final Result r = new Result();
        // v3.2: flatten (outlet, feed) pairs — one thread per feed.
        final List<Source> jobSources = new ArrayList<>();
        final List<Source.Feed> jobFeeds = new ArrayList<>();
        for (Source s : sources) for (Source.Feed fd : s.feeds) { jobSources.add(s); jobFeeds.add(fd); }
        final int n = jobSources.size();
        final List<NewsItem>[] perItems = new List[n];
        final String[] perStatus = new String[n];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final Source s = jobSources.get(i);
            final Source.Feed fd = jobFeeds.get(i);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    if (!s.enabled) { perStatus[idx] = "Đã tắt"; perItems[idx] = new ArrayList<NewsItem>(); return; }
                    try {
                        List<NewsItem> got = fetchOne(fd.url, s.name, fd.category);
                        perItems[idx] = got;
                        perStatus[idx] = got.isEmpty() ? "Không có tin (feed rỗng / sai định dạng)" : "OK — " + got.size() + " tin";
                    } catch (Exception e) {
                        perItems[idx] = new ArrayList<NewsItem>();
                        perStatus[idx] = "Lỗi: " + e.getClass().getSimpleName();
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            try { t.join(35000); } catch (InterruptedException ignored) {}
        }
        for (int i = 0; i < n; i++) {
            if (perItems[i] != null) r.items.addAll(perItems[i]);
            r.status.put(jobFeeds.get(i).url, perStatus[i] != null ? perStatus[i] : "Lỗi: timeout");
        }
        Map<String,NewsItem> seen = new LinkedHashMap<>();
        for (NewsItem it : r.items) if (!seen.containsKey(it.key())) seen.put(it.key(), it);
        r.items = new ArrayList<>(seen.values());
        Collections.sort(r.items, new Comparator<NewsItem>() {
            public int compare(NewsItem a, NewsItem b) { return Long.compare(b.time, a.time); }
        });
        return r;
    }
    private static List<NewsItem> fetchOne(String url, String name, String category) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000); conn.setReadTimeout(15000);
        // v1.19: AP News sits behind Cloudflare, which flags
        // the app's custom UA as a bot; a browser UA stands a
        // better chance on a real phone's network. v1.35: the
        // same for YouTube — the feeds endpoint intermittently
        // refuses the custom UA (the user's 2026-11-04 10:35
        // case: all 9 channel feeds failed at once while news
        // feeds still worked). Other feeds keep the Kenz UA
        // that already works for them.
        conn.setRequestProperty("User-Agent", (url.contains("apnews.com") || url.contains("youtube.com")) ? TranslateUtil.chromeUa() : "KenzNews/0.0.3-nightly (Android RSS reader)");
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        try { return parse(in, name, category); } finally { try { in.close(); } catch (Exception ignored) {} }
    }
    private static List<NewsItem> parse(InputStream in, String source, String category) throws Exception {
        List<NewsItem> out = new ArrayList<>();
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, null);
        String title = "", link = "", desc = "", image = ""; long time = 0; boolean inItem = false;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("item".equals(name) || "entry".equals(name)) { inItem = true; title = ""; link = ""; desc = ""; image = ""; time = 0; }
                else if (inItem) {
                    if ("title".equals(name)) title = readText(p);
                    else if ("link".equals(name)) {
                        String href = p.getAttributeValue(null, "href");
                        if (href != null && !href.isEmpty()) link = href; else link = readText(p);
                    } else if ("description".equals(name) || "summary".equals(name) || "content".equals(name)) desc = readText(p);
                    else if ("pubDate".equals(name) || "published".equals(name) || "updated".equals(name)) time = parseDate(readText(p));
                    else if ("enclosure".equals(name)) {
                        // Empty element: do NOT readText(), just take attributes.
                        String u = p.getAttributeValue(null, "url");
                        String ty = p.getAttributeValue(null, "type");
                        if (u != null && (ty == null || ty.startsWith("image")) && isImageUrl(u)) image = u;
                    } else if (name.endsWith("thumbnail") || name.endsWith("content")) {
                        String u = p.getAttributeValue(null, "url");
                        if (u != null && isImageUrl(u) && image.isEmpty()) image = u;
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String name = p.getName();
                if (("item".equals(name) || "entry".equals(name)) && inItem) {
                    inItem = false;
                    if (image.isEmpty()) image = extractImg(desc);
                    if (title != null && !title.trim().isEmpty()) {
                        NewsItem ni = new NewsItem(clean(title), link, source, clean(desc), time, image);
                        ni.category = category == null ? "" : category;
                        out.add(ni);
                    }
                }
            }
            ev = p.next();
        }
        return out;
    }
    private static String readText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ev = p.next();
        while (ev != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) sb.append(p.getText());
            ev = p.next();
        }
        return sb.toString();
    }
    private static boolean isImageUrl(String u) {
        String l = u.toLowerCase(java.util.Locale.US);
        return l.contains(".jpg") || l.contains(".jpeg") || l.contains(".png") || l.contains(".webp") || l.contains(".gif") || l.contains("image");
    }
    private static String extractImg(String html) {
        if (html == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']").matcher(html);
        // v3.34: the description HTML is entity-escaped, so an
        // image URL with query params came back as "...?a=1&amp;b=2"
        // and the fetch 404'd — that was one more reason some items
        // never got a photo.
        if (m.find()) return m.group(1).replace("&amp;", "&");
        m = java.util.regex.Pattern.compile("<img[^>]+data-src=[\"']([^\"']+)[\"']").matcher(html);
        if (m.find()) return m.group(1).replace("&amp;", "&");
        return "";
    }
    private static String decodeEntities(String s) {
        if (s == null) return "";
        String t = s.replace("&quot;", "\"").replace("&#34;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
        // Numeric entities &#123; — best effort, leave unknown ones as-is.
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(t);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int code = Integer.parseInt(m.group(1));
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) code)));
            }
            m.appendTail(sb); t = sb.toString();
        } catch (Exception ignored) {}
        return t;
    }
    private static String clean(String s) {
        if (s == null) return "";
        String t = decodeEntities(s).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 300) t = t.substring(0, 300) + "…";
        return t;
    }
    private static long parseDate(String s) {
        if (s == null) return 0;
        String[] pats = {"EEE, dd MMM yyyy HH:mm:ss z", "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String pat : pats) {
            try { return new SimpleDateFormat(pat, Locale.US).parse(s.trim()).getTime(); }
            catch (Exception ignored) {}
        }
        return 0;
    }
    // v1.5: video items are the ones whose link is a video page, or whose
    // title/description is explicitly a video. No video is invented: if a
    // feed has no such item, the Video tab honestly says so.
    public static boolean isVideo(NewsItem it) {
        String hay = ((it.link == null ? "" : it.link) + " " + (it.title == null ? "" : it.title)
                + " " + (it.desc == null ? "" : it.desc)).toLowerCase(Locale.US);
        if (hay.contains("/video") || hay.contains("video:")) return true;
        // v1.6: everything from a dedicated video feed counts, and so
        // do WATCH:/VIDEO: titles (Breitbart, Daily Caller style).
        // v3.2: the feed's category says so now (the source is the
        // outlet name, e.g. "Fox News", not "Fox News — Video").
        if ("Video".equals(it.category)) return true;
        String t = (it.title == null ? "" : it.title).toLowerCase(Locale.US).trim();
        return t.startsWith("watch:") || t.startsWith("watch ") || t.startsWith("video:") || t.startsWith("video ");
    }
    public static boolean isElection(NewsItem it) {
        String t = ((it.title == null ? "" : it.title) + " " + (it.desc == null ? "" : it.desc)).toLowerCase(Locale.US);
        String[] keys = {"election", "bầu cử", "senate", "thượng viện", "house", "hạ viện",
            "governor", "thống đốc", "poll", "thăm dò", "midterm", "giữa kỳ", "vote", "bỏ phiếu"};
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }
}
