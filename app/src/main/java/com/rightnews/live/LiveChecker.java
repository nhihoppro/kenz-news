package com.rightnews.live;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
// v1.25: LIVE section for the Video tab — which of the app's
// news channels are streaming live right now. No API key is
// involved (the Video tab deliberately avoids the YouTube
// Data API), so liveness comes from each channel's /live page
// on the mobile site, verified live 2026-11-04 from this VM:
//   live channel:    canonical = https://www.youtube.com/watch?v=<id>
//                    + videoDetails carries "isLive":true
//                    (Fox: "WATCH LIVE: 2026 Midterm Election
//                    Results"; NY Post: "Live: 2026 Midterm
//                    Election Night…"; Wash. Examiner: "LIVE:
//                    Trump hosts a cybercrime roundtable")
//   not-live channel: canonical = https://www.youtube.com/channel/<id>/live
//                    (Breitbart, Daily Caller at the same time)
//                    and no isLive marker at all.
// So the rule is: canonical watch URL AND videoDetails
// isLive — both signals, never one alone, so a featured VOD
// can never be presented as LIVE. The mobile page is used
// because it is half the desktop size (~660KB vs ~1.4MB) and
// its canonical link sits at ~2KB: a not-live channel costs
// one ~2KB read, a live one ~605KB (up to videoDetails).
// Reading stops as soon as the verdict is known, and is
// capped at 900KB so a changed page shape cannot turn one
// check into an unbounded download. One thread per channel,
// joined in parallel, so the whole check
// takes as long as the slowest channel, not the sum.
// A failed check (HTTP error, timeout, unparseable page) is
// reported as error, NOT as "not live" — unknown is not the
// same as offline, and MainActivity's section text keeps
// the two apart.
public class LiveChecker {
    public static class LiveChannel {
        public String name, channelId, videoId, title, error;
    }
    // Mobile Chrome UA: the mobile /live page is what was
    // verified above (small page, early canonical link).
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    // Same 5 outlets as MainActivity.youtubeSources (names +
    // ids must match — the RSS list and the LIVE check are two
    // views of the same channels).
    private static final String[][] CHANNELS = {
        {"Fox News", "UCXIJgqnII2ZOINSWNOGFThA"},
        {"Breitbart", "UCDOIXHGP4D7GMtYvnZ0ldwQ"},
        {"New York Post", "UCrvhNP_lWuPIP6QZzJmM-bw"},
        {"Daily Caller", "UCP4BdwPKyYnfRLjSNfGAPRA"},
        {"Washington Examiner", "UC8BxSGcBKriJvoeyKOnJ6tA"},
        // v1.30: CNN, CBS, NBC (IDs verified as above). The /live
        // check itself was verified for all three 2026-11-04:
        // CNN live ("Live: Midterm candidate HQ feeds…") and
        // NBC live ("WATCH: Steve Kornacki analyzes…") both
        // detected, CBS correctly detected as not live.
        {"CNN", "UCupvZG-5ko_eiXAupbDfxWw"},
        {"CBS News", "UC8p1vwvWtl6T73JiExfWs1g"},
        {"NBC News", "UCeY0bbntWzzVIaj2z3QigXg"},
        // v1.31: Sean Le TV (see MainActivity note — the live
        // streamer, not the empty SLE channel). /live page
        // verified 2026-11-04: correctly detected as not live
        // at check time (canonical = channel /live).
        {"Sean Le TV", "UCFKux_Nnxx2bOLZsoAk0-pw"},
        // v3.0: general news channels, so the Video tab is
        // news, not just election — on his order ("video thì
        // nhiều video tin tức hơn ko chỉ về bầu cử nữa").
        // IDs corrected 2026-11-17 (my first guesses were
        // one letter off — AP ends ...tgA, Reuters is
        // UChqUTb7kYRX8-EiaN3XFrSQ) and verified via each
        // channel's m.youtube.com/videos page: 31 video
        // blocks each, same as ABC News.
        {"ABC News", "UCBi2mrWuNuyYy4gbM6fU18Q"},
        {"Associated Press", "UC52X5wxOL_s5yw0dQk7NtgA"},
        {"Reuters", "UChqUTb7kYRX8-EiaN3XFrSQ"},
    };
    public static List<LiveChannel> checkAll() {
        final LiveChannel[] arr = new LiveChannel[CHANNELS.length];
        Thread[] threads = new Thread[CHANNELS.length];
        for (int i = 0; i < CHANNELS.length; i++) {
            final int idx = i;
            threads[i] = new Thread(new Runnable() {
                public void run() { arr[idx] = checkOne(CHANNELS[idx][0], CHANNELS[idx][1]); }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            try { threads[i].join(); } catch (InterruptedException ignored) {}
        }
        List<LiveChannel> out = new ArrayList<>();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) out.add(arr[i]);
            else { LiveChannel lc = new LiveChannel(); lc.name = CHANNELS[i][0]; lc.channelId = CHANNELS[i][1]; lc.error = "Interrupted"; out.add(lc); }
        }
        return out;
    }
    private static LiveChannel checkOne(String name, String channelId) {
        LiveChannel lc = new LiveChannel();
        lc.name = name; lc.channelId = channelId;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://m.youtube.com/channel/" + channelId + "/live").openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", MOBILE_UA);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) { lc.error = "HTTP " + c.getResponseCode(); return lc; }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] chunk = new byte[8192];
            String canonical = null;
            String videoId = null;
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 900000) break;
                byte[] b = buf.toByteArray();
                if (canonical == null) {
                    canonical = findCanonical(b);
                    if (canonical != null) {
                        if (canonical.contains("/channel/")) break; // not live: verdict known, stop (~2KB)
                        videoId = extractVideoId(canonical);
                        if (videoId == null) { lc.error = "Parse"; return lc; }
                    }
                } else if (videoId != null) {
                    // Live candidate: wait for videoDetails, then
                    // title + isLive flag decide.
                    int vd = indexOf(b, "\"videoDetails\"".getBytes("UTF-8"));
                    if (vd >= 0 && b.length - vd > 2000) break;
                }
            }
            try { in.close(); } catch (Exception ignored) {}
            if (canonical == null) { lc.error = "Parse"; return lc; }
            if (videoId == null) return lc; // not live, no error
            byte[] b = buf.toByteArray();
            int vd = indexOf(b, "\"videoDetails\"".getBytes("UTF-8"));
            if (vd < 0) { lc.error = "Parse"; return lc; }
            int segEnd = Math.min(b.length, vd + 4000);
            String seg = new String(b, vd, segEnd - vd, "UTF-8");
            if (!seg.contains("\"isLive\":true")) return lc; // canonical watch, but not a live stream
            String title = extractJsonTitle(seg);
            lc.videoId = videoId;
            lc.title = (title == null || title.isEmpty()) ? null : title;
            return lc;
        } catch (Exception e) {
            lc.error = e.getClass().getSimpleName();
            return lc;
        }
    }
    // <link rel="canonical" href="..."> — returns the href, or
    // null if not buffered yet.
    private static String findCanonical(byte[] b) throws Exception {
        int i = indexOf(b, "rel=\"canonical\" href=\"".getBytes("UTF-8"));
        if (i < 0) return null;
        int start = i + "rel=\"canonical\" href=\"".length();
        int end = indexOfFrom(b, (byte) '"', start);
        if (end < 0) return null;
        return new String(b, start, end - start, "UTF-8");
    }
    private static String extractVideoId(String url) {
        int v = url.indexOf("v=");
        if (v < 0) return null;
        int end = url.indexOf('&', v + 2);
        if (end < 0) end = url.length();
        String id = url.substring(v + 2, end);
        return id.length() == 11 ? id : null;
    }
    // "title":"..." inside a JSON segment, with quote, backslash and
    // unicode escapes decoded. Returns null if no title is buffered.
    private static String extractJsonTitle(String seg) {
        int t = seg.indexOf("\"title\":\"");
        if (t < 0) return null;
        int i = t + "\"title\":\"".length();
        StringBuilder sb = new StringBuilder();
        while (i < seg.length()) {
            char ch = seg.charAt(i);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && i + 1 < seg.length()) {
                char esc = seg.charAt(i + 1);
                if (esc == '"') { sb.append('"'); i += 2; continue; }
                if (esc == '\\') { sb.append('\\'); i += 2; continue; }
                if (esc == 'u' && i + 5 < seg.length()) {
                    try { sb.append((char) Integer.parseInt(seg.substring(i + 2, i + 6), 16)); i += 6; continue; }
                    catch (Exception ignored) {}
                }
                i += 2; continue;
            }
            sb.append(ch); i++;
        }
        return null;
    }
    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    private static int indexOfFrom(byte[] hay, byte target, int from) {
        for (int i = from; i < hay.length; i++) if (hay[i] == target) return i;
        return -1;
    }
}
