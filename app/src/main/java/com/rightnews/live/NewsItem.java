package com.rightnews.live;
public class NewsItem {
    public String title, link, source, desc, imageUrl;
    public String channelId = "";
    // v3.2: the category of the feed this item came from
    // ("" = uncategorized, shows only under "Tất cả").
    public String category = "";
    public long time;
    public NewsItem(String t, String l, String s, String d, long tm) { this(t, l, s, d, tm, ""); }
    public NewsItem(String t, String l, String s, String d, long tm, String img) {
        title = t; link = l; source = s; desc = d; time = tm; imageUrl = img == null ? "" : img;
    }
    public String key() { return link == null || link.isEmpty() ? title : link; }
}
