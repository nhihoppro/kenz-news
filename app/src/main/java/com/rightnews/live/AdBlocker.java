package com.rightnews.live;
import android.content.Context;
import android.net.Uri;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
// v1.6: ad/tracker host blocklist for the in-app reader. A blocked
// request gets an empty response, so the ad script, pixel or creative
// never loads and never phones home. Only third-party ad/tracker
// hosts are listed — the publisher's own domain (foxnews.com,
// nypost.com, breitbart.com, …) is never blocked, because article
// text and images live there. Limit, stated plainly: an ad served
// from the publisher's own domain cannot be told apart from content
// and will still show; a video pre-roll inside the publisher's own
// player is the same case. If a page breaks with blocking on, the
// reader's "Mở ngoài" button opens it in the external browser.
public class AdBlocker {
    private static final String[] BLOCKED = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google", "adsense", "amazon-adsystem.com", "criteo.com",
        "outbrain.com", "taboola.com", "scorecardresearch.com", "quantserve.com",
        "moatads.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "openx.net", "indexww.com", "mookie1.com", "yieldmo.com",
        "smartadserver.com", "adnxs.com", "advertising.com", "ads.yahoo.com",
        "connect.facebook.net", "facebook.net", "google-analytics.com",
        "googletagmanager.com", "doubleverify.com", "integralads.com",
        "hotjar.com", "fullstory.com", "segment.io", "mixpanel.com",
        "amplitude.com", "chartbeat.com", "parsely.com", "liadm.com",
        "bidswitch.net", "casalemedia.com", "mathtag.com", "bluekai.com",
        "krxd.net", "demdex.net", "rlcdn.com", "agkn.com", "adsafeprotected.com"
    };
    public static boolean isAd(String url) {
        if (url == null) return false;
        String host = hostOf(url);
        if (host.isEmpty()) return false;
        for (String b : BLOCKED) if (host.contains(b)) return true;
        return false;
    }
    // v1.11 (suggestion 7): hosts the user reported with "Báo QC"
    // in the reader. Stored in SharedPreferences (in-memory after
    // first read, so checking them on every request costs
    // nothing measurable). A reported host is blocked from the
    // next page load onward — the already-open page keeps what
    // it already loaded, which the report button's toast says
    // plainly. The publisher's own domain is never reported
    // (ArticleActivity filters it out before saving).
    public static Set<String> reportedHosts(Context c) {
        try {
            Set<String> s = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE)
                    .getStringSet("ad_reported_hosts", new HashSet<String>());
            return s == null ? new HashSet<String>() : new HashSet<>(s);
        } catch (Exception e) { return new HashSet<>(); }
    }
    public static boolean isAd(Context c, String url) {
        if (isAd(url)) return true;
        if (c == null) return false;
        String host = hostOf(url);
        if (host.isEmpty()) return false;
        for (String r : reportedHosts(c)) {
            if (r == null || r.isEmpty()) continue;
            if (host.equals(r) || host.endsWith("." + r)) return true;
        }
        return false;
    }
    private static String hostOf(String url) {
        try { Uri u = Uri.parse(url); return u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US); }
        catch (Exception e) { return ""; }
    }
}
