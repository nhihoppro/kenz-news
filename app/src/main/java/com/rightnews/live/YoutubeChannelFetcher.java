package com.rightnews.live;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// v1.36: video fallback when a channel's RSS feed fails. The
// feeds endpoint (youtube.com/feeds/videos.xml) intermittently
// refuses requests — the user's 2026-11-04 case had all 9
// channel feeds failing at once on his phone while the site's
// HTML pages still loaded (the LIVE check, which uses HTML,
// kept working). The desktop channel /videos page carries the
// same recent videos as lockup blocks (richItemRenderer ->
// lockupViewModel), verified 2026-11-04 from the VM for Fox
// News and Sean Le TV (30 blocks each, real titles, views +
// relative times).
// v1.44 CORRECTION to the old mobile note below: the claim
// that the mobile /videos page "returns a JS shell with no
// video data" is WRONG for the current page. The phone's
// 2026-11-04 14:40 test proved BOTH www routes dead (feed
// 0/9, desktop /videos 0/9) while the m.youtube.com LIVE
// check kept working — the phone's connection to
// www.youtube.com fails while m.youtube.com answers. And the
// mobile /videos page, fetched from the VM 2026-11-04, is
// server-rendered with 30 compactVideoRenderer blocks (real
// titles + relative times, data embedded with \xNN escapes).
// So the mobile page now runs BEFORE the desktop page (see
// MainActivity.runVideoFallback); desktop stays as last
// resort for channels mobile misses. Per channel: at most 15
// videos, read capped at 2MB. Titles are JSON-unescaped;
// times come from the relative "X ago" metadata (English +
// Vietnamese), unparseable ones sort last (time 0) while the
// stable sort keeps their page order.
public class YoutubeChannelFetcher {
    private static final int MAX_VIDEOS = 15;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern BLOCK = Pattern.compile("\"richItemRenderer\":\\{\"content\":\\{\"lockupViewModel\":");
    private static final Pattern WATCH_ID = Pattern.compile("\"watchEndpoint\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern TITLE = Pattern.compile("\"lockupMetadataViewModel\":\\{\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    // v1.37 FIX: the old pattern anchored to the start of the
    // metadataParts array, so it only ever matched the FIRST
    // part (the view count, "65K") — the relative time in the
    // second part never matched and every fallback video got
    // time 0. Matching any text/content pair in the block and
    // taking the first one that parses as a relative time
    // fixes the sort (the view count parses to 0 and is
    // skipped; nothing else in a lockup block looks like a
    // relative time).
    private static final Pattern META_PART = Pattern.compile("\\{\"text\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    // v1.44: mobile page patterns. The mobile /videos page
    // embeds its JSON with \xNN escapes, so fetchMobile
    // unescapes the whole body first; then each video is a
    // compactVideoRenderer object with videoId, a title runs[]
    // and a publishedTimeText runs[] (same relative-time text
    // as desktop, English + Vietnamese).
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final Pattern MOB_BLOCK = Pattern.compile("\"compactVideoRenderer\":\\{");
    private static final Pattern MOB_VID = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"");
    private static final Pattern MOB_TITLE = Pattern.compile("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern MOB_TIME = Pattern.compile("\"publishedTimeText\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    public static List<NewsItem> fetchMobile(String channelId, String channelName) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://m.youtube.com/channel/" + channelId + "/videos").openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", MOBILE_UA);
        c.setInstanceFollowRedirects(true);
        InputStream in = c.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int total = 0, n;
        try {
            while ((n = in.read(buf)) != -1 && total < MAX_BYTES) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                total += n;
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        String html = unescapeHex(sb.toString());
        List<NewsItem> out = new ArrayList<>();
        Matcher bm = MOB_BLOCK.matcher(html);
        int searchFrom = 0;
        while (out.size() < MAX_VIDEOS && bm.find(searchFrom)) {
            int next = html.indexOf("\"compactVideoRenderer\"", bm.end());
            String block = html.substring(bm.start(), next < 0 ? html.length() : next);
            Matcher vm = MOB_VID.matcher(block);
            if (!vm.find()) { searchFrom = bm.end(); continue; }
            String vid = vm.group(1);
            Matcher tm = MOB_TITLE.matcher(block);
            if (!tm.find()) { searchFrom = bm.end(); continue; }
            String title = jsonUnescape(tm.group(1));
            if (title == null || title.trim().isEmpty()) { searchFrom = bm.end(); continue; }
            long time = 0;
            Matcher mm = MOB_TIME.matcher(block);
            if (mm.find()) time = parseRelativeTime(jsonUnescape(mm.group(1)));
            out.add(new NewsItem(title, "https://www.youtube.com/watch?v=" + vid, channelName, "", time,
                    "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg"));
            out.get(out.size() - 1).channelId = channelId;
            searchFrom = bm.end();
        }
        return out;
    }
    private static String unescapeHex(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int j = 0; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\' && j + 3 < s.length() && s.charAt(j + 1) == 'x') {
                try {
                    int code = Integer.parseInt(s.substring(j + 2, j + 4), 16);
                    sb.append((char) code);
                    j += 3;
                } catch (Exception ignored) { sb.append(ch); }
            } else sb.append(ch);
        }
        return sb.toString();
    }
    public static List<NewsItem> fetch(String channelId, String channelName) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/channel/" + channelId + "/videos").openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", TranslateUtil.chromeUa());
        c.setInstanceFollowRedirects(true);
        InputStream in = c.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int total = 0, n;
        try {
            while ((n = in.read(buf)) != -1 && total < MAX_BYTES) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                total += n;
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        String html = sb.toString();
        List<NewsItem> out = new ArrayList<>();
        Matcher bm = BLOCK.matcher(html);
        int searchFrom = 0;
        while (out.size() < MAX_VIDEOS && bm.find(searchFrom)) {
            int next = html.indexOf("\"richItemRenderer\"", bm.end());
            String block = html.substring(bm.start(), next < 0 ? html.length() : next);
            Matcher vm = WATCH_ID.matcher(block);
            if (!vm.find()) { searchFrom = bm.end(); continue; }
            String vid = vm.group(1);
            Matcher tm = TITLE.matcher(block);
            if (!tm.find()) { searchFrom = bm.end(); continue; }
            String title = jsonUnescape(tm.group(1));
            if (title == null || title.trim().isEmpty()) { searchFrom = bm.end(); continue; }
            long time = 0;
            Matcher mm = META_PART.matcher(block);
            while (mm.find()) {
                long t = parseRelativeTime(jsonUnescape(mm.group(1)));
                if (t > 0) { time = t; break; }
            }
            out.add(new NewsItem(title, "https://www.youtube.com/watch?v=" + vid, channelName, "", time,
                    "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg"));
            out.get(out.size() - 1).channelId = channelId;
            searchFrom = bm.end();
        }
        return out;
    }
    // Standalone JSON string unescape (same rules as the v1.26
    // TranslateUtil fix): \\ \" \/ \n \t \r \b \f and U+XXXX;
    // U+200B becomes a space, U+FEFF is dropped.
    public static String jsonUnescape(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < s.length(); j++) {
            char ch = s.charAt(j);
            if (ch == '\\' && j + 1 < s.length()) {
                char esc = s.charAt(j + 1);
                if (esc == 'u' && j + 5 < s.length()) {
                    try {
                        int code = Integer.parseInt(s.substring(j + 2, j + 6), 16);
                        if (code == 0x200B) sb.append(' ');
                        else if (code != 0xFEFF) sb.append((char) code);
                        j += 5;
                    } catch (Exception ignored) { j++; }
                } else if (esc == 'n' || esc == 't' || esc == 'r') { sb.append(' '); j++; }
                else if (esc == 'b' || esc == 'f') { j++; }
                else { sb.append(esc); j++; }
            } else sb.append(ch);
        }
        return sb.toString().replace((char) 0x200B, ' ').replace((char) 0xFEFF, ' ').trim().replaceAll("  +", " ");
    }
    // "29 min ago", "3 hours ago", "Streamed 5 hours ago",
    // "3 giờ trước", "Đã phát trực tiếp 2 ngày trước" ->
    // approximate epoch millis. Anything else -> 0.
    private static long parseRelativeTime(String s) {
        if (s == null) return 0;
        String t = s.replace('\u00a0', ' ').trim().toLowerCase(Locale.US);
        t = t.replaceFirst("^(streamed|đã phát trực tiếp)\\s+", "");
        if (t.equals("just now") || t.equals("mới đây")) return System.currentTimeMillis();
        Matcher m = Pattern.compile("(\\d+)\\s*(seconds?|secs?|sec|minutes?|mins?|min|hours?|hrs?|hr|days?|weeks?|months?|years?|giây|phút|giờ|ngày|tuần|tháng|năm)\\b").matcher(t);
        if (!m.find()) return 0;
        long num;
        try { num = Long.parseLong(m.group(1)); } catch (Exception ignored) { return 0; }
        String u = m.group(2);
        long mult;
        if (u.startsWith("sec") || u.equals("giây")) mult = 1;
        else if (u.startsWith("min") || u.equals("phút")) mult = 60;
        else if (u.startsWith("hr") || u.startsWith("hour") || u.equals("giờ")) mult = 3600;
        else if (u.startsWith("day") || u.equals("ngày")) mult = 86400;
        else if (u.startsWith("week") || u.equals("tuần")) mult = 604800;
        else if (u.startsWith("month") || u.equals("tháng")) mult = 2592000L;
        else if (u.startsWith("year") || u.equals("năm")) mult = 31536000L;
        else return 0;
        return System.currentTimeMillis() - num * mult * 1000L;
    }
}
