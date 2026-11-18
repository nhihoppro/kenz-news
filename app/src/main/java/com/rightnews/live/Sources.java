package com.rightnews.live;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
public class Sources {
    // v3.2: one outlet = one source. Each outlet carries its
    // feeds (one RSS URL per category it covers) — 12 outlets,
    // 36 feeds, the same feed URLs as v3.0, just regrouped.
    // Feed URLs verified live from the VM 2026-11-17 (Fox
    // business/tech/sports return 200 directly; us/world/
    // entertainment/health/science/opinion/videos go through
    // moxie.foxnews.com/google-publisher/*.xml — the moxie
    // links are used directly, no redirect). CNN / AP / CNBC /
    // ESPN / Verge / CBS Sports feeds follow each outlet's
    // standard RSS pattern, unverifiable from the VM (same IP
    // blocks as CNN/AP Politics) — a failing feed only shows
    // its error in the Sources tab on his phone.
    public static List<Source> defaults() {
        List<Source> l = new ArrayList<>();
        Source fox = new Source("Fox News");
        fox.addFeed("https://feeds.foxnews.com/foxnews/latest", "Chính trị");
        fox.addFeed("https://feeds.foxnews.com/foxnews/politics", "Chính trị");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/us.xml", "Tin Mỹ");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/world.xml", "Thế giới");
        fox.addFeed("https://feeds.foxnews.com/foxnews/business", "Kinh tế");
        fox.addFeed("https://feeds.foxnews.com/foxnews/tech", "Công nghệ");
        fox.addFeed("https://feeds.foxnews.com/foxnews/sports", "Thể thao");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/entertainment.xml", "Giải trí");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/health.xml", "Sức khỏe");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/science.xml", "Khoa học");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/opinion.xml", "Ý kiến");
        fox.addFeed("https://moxie.foxnews.com/google-publisher/videos.xml", "Video");
        l.add(fox);
        Source cnn = new Source("CNN");
        cnn.addFeed("https://rss.cnn.com/rss/cnn_allpolitics.rss", "Chính trị");
        cnn.addFeed("https://rss.cnn.com/rss/cnn_world.rss", "Thế giới");
        cnn.addFeed("https://rss.cnn.com/rss/money_latest.rss", "Kinh tế");
        cnn.addFeed("https://rss.cnn.com/rss/cnn_tech.rss", "Công nghệ");
        cnn.addFeed("https://rss.cnn.com/rss/cnn_showbiz.rss", "Giải trí");
        cnn.addFeed("https://rss.cnn.com/rss/cnn_health.rss", "Sức khỏe");
        l.add(cnn);
        Source ap = new Source("AP News");
        ap.addFeed("https://apnews.com/hub/politics/feed", "Chính trị");
        ap.addFeed("https://apnews.com/hub/world-news/feed", "Thế giới");
        ap.addFeed("https://apnews.com/hub/science/feed", "Khoa học");
        l.add(ap);
        Source nyp = new Source("New York Post");
        nyp.addFeed("https://nypost.com/politics/feed/", "Chính trị");
        nyp.addFeed("https://nypost.com/us-news/feed/", "Tin Mỹ");
        nyp.addFeed("https://nypost.com/entertainment/feed/", "Giải trí");
        nyp.addFeed("https://nypost.com/opinion/feed/", "Ý kiến");
        nyp.addFeed("https://nypost.com/video/feed/", "Video");
        l.add(nyp);
        Source cbs = new Source("CBS News");
        cbs.addFeed("https://www.cbsnews.com/latest/rss/politics", "Chính trị");
        cbs.addFeed("https://www.cbsnews.com/latest/rss/us", "Tin Mỹ");
        cbs.addFeed("https://www.cbssports.com/rss/headlines/", "Thể thao");
        l.add(cbs);
        l.add(new Source("NBC News", "https://feeds.nbcnews.com/nbcnews/public/politics", "Chính trị"));
        l.add(new Source("Breitbart", "https://www.breitbart.com/feed/", "Chính trị"));
        l.add(new Source("Daily Caller", "https://dailycaller.com/feed/", "Chính trị"));
        l.add(new Source("Washington Examiner", "https://www.washingtonexaminer.com/feed/", "Chính trị"));
        l.add(new Source("CNBC", "https://www.cnbc.com/id/10001147/device/rss/rss.html", "Kinh tế"));
        l.add(new Source("ESPN", "https://www.espn.com/espn/rss/news", "Thể thao"));
        l.add(new Source("The Verge", "https://www.theverge.com/rss/index.xml", "Công nghệ"));
        return l;
    }
    public static List<Source> load(Context c) {
        SharedPreferences p = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE);
        String saved = p.getString("sources", "");
        List<Source> out = defaults();
        if (saved == null || saved.isEmpty()) return out;
        // Format: name|url|enabled;... one row per outlet (url =
        // its first feed). v3.0/v3.1 saved one row per FEED, so
        // an upgrading install has 36 rows: each matches one of
        // the outlet's feeds by URL. An outlet is on when ANY of
        // its saved feeds was on, off only when every saved feed
        // was off; outlets with no saved row keep the default.
        Set<String> mentioned = new HashSet<>();
        Set<String> enabledUrls = new HashSet<>();
        try {
            for (String part : saved.split("\n")) {
                if (part.trim().isEmpty()) continue;
                String[] f = part.split("\\|", -1);
                if (f.length >= 2) {
                    String url = f[1];
                    boolean en = f.length < 3 || !"0".equals(f[2]);
                    Source owner = null;
                    for (Source d : out) {
                        for (Source.Feed fd : d.feeds) if (fd.url.equals(url)) { owner = d; break; }
                        if (owner != null) break;
                    }
                    if (owner != null) {
                        mentioned.add(url);
                        if (en) enabledUrls.add(url);
                    } else {
                        Source s = new Source(f[0], url);
                        s.enabled = en;
                        out.add(s);
                    }
                }
            }
            for (Source d : out) {
                boolean anyMentioned = false, anyEnabled = false;
                for (Source.Feed fd : d.feeds) {
                    if (mentioned.contains(fd.url)) {
                        anyMentioned = true;
                        if (enabledUrls.contains(fd.url)) anyEnabled = true;
                    }
                }
                if (anyMentioned) d.enabled = anyEnabled;
            }
        } catch (Exception ignored) {}
        return out;
    }
    public static void save(Context c, List<Source> list) {
        StringBuilder sb = new StringBuilder();
        for (Source s : list) {
            sb.append(s.name.replace("|", " ").replace("\n", " ")).append("|")
              .append(s.url).append("|").append(s.enabled ? "1" : "0").append("\n");
        }
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit()
            .putString("sources", sb.toString()).apply();
    }
}
