package com.rightnews.live;
import java.util.*;
// v3.2: one outlet = one source = one switch. An outlet fetches
// many categories through its feeds (each feed = one RSS URL +
// its category); the old model (one Source per category feed)
// made the Sources tab 36 rows of near-identical names.
// url/category mirror feeds[0] (the primary feed) so old code
// that reads s.url / s.category keeps working; youtubeSources
// builds single-feed Sources, which behave exactly as before.
public class Source {
    public String name, url;
    public boolean enabled = true;
    public String status = "Chưa cập nhật";
    public String category = "";
    public List<Feed> feeds = new ArrayList<>();
    public static class Feed {
        public String url, category;
        public Feed(String u, String c) { url = u; category = c == null ? "" : c; }
    }
    // Multi-feed outlet: no feeds yet, addFeed fills them.
    public Source(String n) { name = n; url = ""; }
    public Source(String n, String u) { name = n; url = u; addFeed(u, ""); }
    public Source(String n, String u, String cat) { name = n; url = u; category = cat; addFeed(u, cat); }
    public void addFeed(String u, String cat) {
        feeds.add(new Feed(u, cat));
        if (feeds.size() == 1) { url = u; category = cat == null ? "" : cat; }
    }
}
