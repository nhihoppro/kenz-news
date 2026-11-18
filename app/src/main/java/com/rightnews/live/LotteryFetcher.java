package com.rightnews.live;
import java.io.*;
import java.net.*;
import java.util.*;
// v3.3: lottery results (Xổ số 3 miền) from Minh Ngọc
// (minhngoc.net.vn) — the one public source that has both
// live results and dated pages. The xsmm.vn developer API
// (api.xsmm.vn) is IP-whitelisted and unusable from a phone.
// Pages: today = /kqxs/<slug>.html, a date =
// /kqxs/<slug>/DD-MM-YYYY.html. Each page lists draw blocks
// newest-first (MB: 1 block/day, 7 days; MT: ~3 days;
// MN: ~2 days). A block is one province's prize table
// (MB: one block, header = draw code like 10LG-5LG-...;
// MT/MN: header = province code like XSHCM). Dates are
// NOT printed on the page, so blocks are dated by walking
// backwards from the anchor date using the fixed weekday
// draw schedule (verified against the 16/11/2026 pages).
// Pure Java (no Android imports) so the parser can be
// unit-tested on the VM with a fixture.
public class LotteryFetcher {
    public static final int MB = 0, MT = 1, MN = 2;
    public static final String[] REGION_NAMES = {"Miền Bắc", "Miền Trung", "Miền Nam"};
    public static final String[] REGION_SLUGS = {"mien-bac", "mien-trung", "mien-nam"};
    // Draw start, minutes from midnight: MN 16:15, MT 17:15, MB 18:15.
    public static final int[] DRAW_START_MIN = {18 * 60 + 15, 17 * 60 + 15, 16 * 60 + 15};
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class PrizeRow {
        public String label;
        public List<String> numbers = new ArrayList<>();
    }
    public static class Draw {
        public String provinceCode = "";
        public String province = "";
        public List<PrizeRow> prizes = new ArrayList<>();
        // Đầu đuôi rows: {chục, số, đơn vị} x10, raw cell text.
        public List<String[]> dauDuoi = new ArrayList<>();
        // v3.5: the draw's date, stamped when the block is
        // dated in place() — null only for undated blocks.
        public Calendar date = null;
    }
    public static class DayResult {
        public int region;
        public Calendar date;
        public List<Draw> draws = new ArrayList<>();
        public List<Draw> latestDraws = new ArrayList<>();
        // v3.7: every dated block on the fetched page, newest
        // first; and, before today's draw, one draw per station
        // scheduled TODAY, each with its latest previous draw
        // ("kỳ trước của đúng đài đó"), filled by todayPrevious.
        public List<Draw> allDraws = new ArrayList<>();
        public List<Draw> previousDraws = new ArrayList<>();
        public boolean drawDone;
        public String error = null;
    }

    // ---- province codes (page header) -> display name ----
    private static final Map<String, String> PROVINCE_NAMES = new HashMap<>();
    static {
        // Miền Nam (21)
        PROVINCE_NAMES.put("XSHCM", "TP.HCM"); PROVINCE_NAMES.put("XSDT", "Đồng Tháp");
        PROVINCE_NAMES.put("XSCM", "Cà Mau"); PROVINCE_NAMES.put("XSBT", "Bến Tre");
        PROVINCE_NAMES.put("XSVT", "Vũng Tàu"); PROVINCE_NAMES.put("XSBL", "Bạc Liêu");
        PROVINCE_NAMES.put("XSDN", "Đồng Nai"); PROVINCE_NAMES.put("XSCT", "Cần Thơ");
        PROVINCE_NAMES.put("XSST", "Sóc Trăng"); PROVINCE_NAMES.put("XSTN", "Tây Ninh");
        PROVINCE_NAMES.put("XSAG", "An Giang"); PROVINCE_NAMES.put("XSBTH", "Bình Thuận");
        PROVINCE_NAMES.put("XSVL", "Vĩnh Long"); PROVINCE_NAMES.put("XSBD", "Bình Dương");
        PROVINCE_NAMES.put("XSTV", "Trà Vinh"); PROVINCE_NAMES.put("XSLA", "Long An");
        PROVINCE_NAMES.put("XSBP", "Bình Phước"); PROVINCE_NAMES.put("XSHG", "Hậu Giang");
        PROVINCE_NAMES.put("XSTG", "Tiền Giang"); PROVINCE_NAMES.put("XSKG", "Kiên Giang");
        PROVINCE_NAMES.put("XSDL", "Đà Lạt");
        // Miền Trung (14)
        PROVINCE_NAMES.put("XSH", "Thừa Thiên Huế"); PROVINCE_NAMES.put("XSTTH", "Thừa Thiên Huế");
        PROVINCE_NAMES.put("XSPY", "Phú Yên"); PROVINCE_NAMES.put("XSDLK", "Đắk Lắk");
        PROVINCE_NAMES.put("XSQNM", "Quảng Nam"); PROVINCE_NAMES.put("XSDNG", "Đà Nẵng");
        PROVINCE_NAMES.put("XSKH", "Khánh Hòa"); PROVINCE_NAMES.put("XSBDI", "Bình Định");
        PROVINCE_NAMES.put("XSQT", "Quảng Trị"); PROVINCE_NAMES.put("XSQB", "Quảng Bình");
        PROVINCE_NAMES.put("XSGL", "Gia Lai"); PROVINCE_NAMES.put("XSNT", "Ninh Thuận");
        PROVINCE_NAMES.put("XSQNG", "Quảng Ngãi"); PROVINCE_NAMES.put("XSDNO", "Đắk Nông");
        PROVINCE_NAMES.put("XSKT", "Kon Tum");
    }
    // Weekday draw schedule (Calendar.SUNDAY=1..SATURDAY=7).
    // Verified against the 14–16/11/2026 pages. Note Sunday MT
    // really draws 3 provinces (Huế, Khánh Hòa, Kon Tum) even
    // though the page footer lists only 2.
    private static final String[][] MN_SCHEDULE = {
        /* Sun */ {"XSTG", "XSKG", "XSDL"},
        /* Mon */ {"XSHCM", "XSDT", "XSCM"},
        /* Tue */ {"XSBT", "XSVT", "XSBL"},
        /* Wed */ {"XSDN", "XSCT", "XSST"},
        /* Thu */ {"XSTN", "XSAG", "XSBTH"},
        /* Fri */ {"XSVL", "XSBD", "XSTV"},
        /* Sat */ {"XSHCM", "XSLA", "XSBP", "XSHG"},
    };
    private static final String[][] MT_SCHEDULE = {
        /* Sun */ {"XSH", "XSKH", "XSKT"},
        /* Mon */ {"XSH", "XSPY"},
        /* Tue */ {"XSDLK", "XSQNM"},
        /* Wed */ {"XSDNG", "XSKH"},
        /* Thu */ {"XSBDI", "XSQT", "XSQB"},
        /* Fri */ {"XSGL", "XSNT"},
        /* Sat */ {"XSDNG", "XSQNG", "XSDNO"},
    };
    // MB issuer rotation (for the card label).
    private static final String[] MB_ISSUER = {
        /* Sun */ "Thái Bình", /* Mon */ "Hà Nội", /* Tue */ "Quảng Ninh",
        /* Wed */ "Bắc Ninh", /* Thu */ "Hà Nội", /* Fri */ "Hải Phòng", /* Sat */ "Nam Định",
    };
    private static final String[] PRIZE_LABELS = {
        "Giải ĐB", "Giải nhất", "Giải nhì", "Giải ba", "Giải tư",
        "Giải năm", "Giải sáu", "Giải bảy", "Giải 8", "Giải tám",
    };

    public static String urlFor(int region, Calendar date, Calendar today) {
        String slug = REGION_SLUGS[region];
        if (sameDay(date, today)) return "https://www.minhngoc.net.vn/kqxs/" + slug + ".html";
        return "https://www.minhngoc.net.vn/kqxs/" + slug + "/" + fmt(date) + ".html";
    }
    public static String fmt(Calendar c) {
        return String.format(Locale.US, "%02d-%02d-%04d",
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR));
    }
    public static String dateKey(Calendar c) { return fmt(c); }
    public static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
    public static Calendar latestCompletedDate(int region, Calendar now) {
        Calendar c = (Calendar) now.clone();
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (mins < DRAW_START_MIN[region]) c.add(Calendar.DAY_OF_MONTH, -1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    private static Set<String> expectedProvinces(int region, Calendar date) {
        if (region == MB) return null;
        String[][] sched = region == MT ? MT_SCHEDULE : MN_SCHEDULE;
        int dow = date.get(Calendar.DAY_OF_WEEK); // 1=Sun..7=Sat
        Set<String> s = new HashSet<>();
        for (String code : sched[dow - 1]) s.add(code);
        return s;
    }
    public static String mbIssuer(Calendar date) {
        return MB_ISSUER[date.get(Calendar.DAY_OF_WEEK) - 1];
    }
    // v3.8: next draw start for this region — today at
    // DRAW_START_MIN if it has not started yet, else
    // tomorrow at the same time. Pure Calendar math (the
    // alarm scheduler in LotteryNotifier uses it).
    public static Calendar nextDrawTime(int region, Calendar now) {
        Calendar t = (Calendar) now.clone();
        int start = DRAW_START_MIN[region];
        t.set(Calendar.HOUR_OF_DAY, start / 60);
        t.set(Calendar.MINUTE, start % 60);
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (!t.after(now)) t.add(Calendar.DAY_OF_MONTH, 1);
        return t;
    }
    // v3.5: is this region drawing RIGHT NOW? The draw
    // starts at DRAW_START_MIN and runs ~30-50 min; the
    // live poll uses the same window (from 15 min before).
    public static boolean isLiveNow(int region, Calendar now) {
        int mins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int start = DRAW_START_MIN[region];
        return mins >= start && mins <= start + 50;
    }
    // v3.7: before today's draw, the tab shows TODAY's
    // stations (per the weekday schedule), each with its
    // latest previous draw — the last time THAT station drew
    // ("kỳ trước của đúng đài đó"). E.g. on Tuesday the MN
    // tab shows Bến Tre / Vũng Tàu / Bạc Liêu with last
    // Tuesday's numbers, never TP.HCM (Monday/Saturday).
    // firstPage is the already-fetched result for `today`
    // (its allDraws are scanned first); dated pages are
    // walked back (max 14 days) for stations whose previous
    // draw is older than the page covers. Stations with no
    // previous draw found are left out. Runs on the refresh
    // worker thread (network I/O).
    public static List<Draw> todayPrevious(DayResult firstPage, Calendar today) {
        int region = firstPage.region;
        List<String> codes = todayCodes(region, today);
        Map<String, Draw> found = new HashMap<>();
        scanTodayPrevious(region, firstPage.allDraws, codes, today, found);
        Calendar day = (Calendar) today.clone();
        for (int back = 1; back <= 14 && found.size() < codes.size(); back++) {
            day.add(Calendar.DAY_OF_MONTH, -1);
            DayResult r = fetch(region, day);
            if (r.error != null) continue;
            scanTodayPrevious(region, r.allDraws, codes, today, found);
        }
        List<Draw> out = new ArrayList<>();
        for (String code : codes) {
            Draw d = found.get(code);
            if (d != null) out.add(d);
        }
        return out;
    }
    // The stations scheduled for `date`, in schedule order.
    // MB draws once a day, so its single "station" is "MB".
    private static List<String> todayCodes(int region, Calendar date) {
        List<String> codes = new ArrayList<>();
        if (region == MB) { codes.add("MB"); return codes; }
        String[][] sched = region == MT ? MT_SCHEDULE : MN_SCHEDULE;
        for (String code : sched[date.get(Calendar.DAY_OF_WEEK) - 1]) codes.add(code);
        return codes;
    }
    // MB blocks carry the draw code, not a province code, so
    // the key is just "MB" (one draw per day); MT/MN key by
    // province code.
    private static String previousKey(int region, Draw d) {
        return region == MB ? "MB" : d.provinceCode;
    }
    private static void scanTodayPrevious(int region, List<Draw> candidates, List<String> codes,
                                          Calendar today, Map<String, Draw> found) {
        Set<String> want = new HashSet<>(codes);
        for (Draw d : candidates) {
            String key = previousKey(region, d);
            if (!want.contains(key) || found.containsKey(key)) continue;
            if (d.date == null || !d.date.before(today)) continue;
            found.put(key, d);
        }
    }

    public static DayResult fetch(int region, Calendar date) {
        DayResult res = new DayResult();
        res.region = region;
        res.date = (Calendar) date.clone();
        Calendar today = Calendar.getInstance();
        Calendar latest = latestCompletedDate(region, Calendar.getInstance());
        res.drawDone = !date.after(latest);
        String html;
        try {
            html = download(urlFor(region, date, today));
        } catch (Exception e) {
            res.error = "Không tải được kết quả — kiểm tra mạng rồi thử lại.";
            return res;
        }
        List<Draw> blocks = parseBlocks(normalize(html), region);
        if (blocks.isEmpty()) {
            res.error = "Không đọc được bảng kết quả (trang nguồn đổi dạng).";
            return res;
        }
        Calendar anchor = sameDay(date, today) ? latest : (Calendar) date.clone();
        Map<String, List<Draw>> byDate = new LinkedHashMap<>();
        assignDates(blocks, region, anchor, byDate);
        List<Draw> mine = byDate.get(dateKey(date));
        if (mine != null) res.draws.addAll(mine);
        List<Draw> lat = byDate.get(dateKey(latest));
        if (lat != null) res.latestDraws.addAll(lat);
        // v3.7: every dated block, newest first, with its
        // date stamped — the todayPrevious walk scans these.
        // MB labels come from each block's own date now.
        for (List<Draw> l : byDate.values()) res.allDraws.addAll(l);
        if (region == MB) for (Draw d : res.allDraws) d.province = mbIssuer(d.date);
        if (res.drawDone && res.draws.isEmpty())
            res.error = "Không thấy kết quả ngày " + fmt(date) + " trên trang nguồn.";
        return res;
    }

    // Assign each parsed block (newest-first) to a date, walking
    // backwards from the anchor using the weekday schedule.
    static void assignDates(List<Draw> blocks, int region, Calendar anchor,
                            Map<String, List<Draw>> byDate) {
        Calendar cursor = (Calendar) anchor.clone();
        Set<String> used = new HashSet<>();
        for (Draw d : blocks) {
            Calendar tryDate = (Calendar) cursor.clone();
            boolean placed = false;
            for (int step = 0; step < 4 && !placed; step++) {
                Set<String> expected = expectedProvinces(region, tryDate);
                if (expected == null) {
                    place(byDate, tryDate, d);
                    placed = true;
                } else if (expected.contains(d.provinceCode)
                        && !used.contains(dateKey(tryDate) + "|" + d.provinceCode)) {
                    place(byDate, tryDate, d);
                    used.add(dateKey(tryDate) + "|" + d.provinceCode);
                    placed = true;
                }
                if (!placed) tryDate.add(Calendar.DAY_OF_MONTH, -1);
            }
            if (placed) {
                cursor = tryDate;
                // MB: exactly one block per day, so the next block
                // must belong to an earlier day.
                if (region == MB) cursor.add(Calendar.DAY_OF_MONTH, -1);
            }
        }
    }
    private static void place(Map<String, List<Draw>> byDate, Calendar date, Draw d) {
        d.date = (Calendar) date.clone();
        String k = dateKey(date);
        List<Draw> l = byDate.get(k);
        if (l == null) { l = new ArrayList<>(); byDate.put(k, l); }
        l.add(d);
    }

    static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }

    // Raw HTML -> one cell per line. <br> and cell ends
    // become line breaks; every other tag becomes a space.
    // </tr> is deliberately NOT a break: </td> already ends
    // the cell, and a second break would insert a phantom
    // empty line between rows. Empty lines are KEPT (as "")
    // because an empty cell is a real cell in the đầu đuôi
    // table — dropping it would shift every later row.
    static List<String> normalize(String html) {
        String s = html;
        s = s.replaceAll("(?i)<br[^>]*>", "\n");
        s = s.replaceAll("(?i)</(td|th|table|div|p|li|h[1-6]|ul|ol|section|article)>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
             .replace("&#39;", "'").replace("&quot;", "\"");
        s = s.replace("|", "\n").replace("*", "");
        List<String> lines = new ArrayList<>();
        for (String ln : s.split("\n", -1)) {
            String t = ln.trim().replaceAll("  +", " ");
            if (t.equals("---")) continue;
            lines.add(t);
        }
        return lines;
    }

    // A line that starts a new draw block, or null. MB blocks
    // start with the draw code (10LG-5LG-...); MT/MN blocks
    // start with a province code that must be in the map
    // (so footer mentions like XSMB/XSMT never open a block).
    static String matchBlockStart(String ln, int region) {
        if (region == MB) {
            if (ln.matches("(\\d+[A-Z]{2}-)+\\d+[A-Z]{2}")) return ln;
            return null;
        }
        String first = ln.split("\\s+")[0];
        // v3.19: Bến Tre's block header on Minh Ngọc is XSBTR,
        // not XSBT — without this alias the whole Bến Tre draw
        // was silently dropped (Tuesday showed only Vũng Tàu +
        // Bạc Liêu). Return the schedule code so the draw also
        // matches todayCodes/scanTodayPrevious (which use XSBT).
        if ("XSBTR".equals(first)) return "XSBT";
        if (PROVINCE_NAMES.containsKey(first)) return first;
        return null;
    }
    static String matchPrizeLabel(String ln) {
        for (String lab : PRIZE_LABELS) {
            if (ln.startsWith(lab)) return "Giải tám".equals(lab) ? "Giải 8" : lab;
        }
        return null;
    }
    // Pull digit-only tokens out of text into the row.
    // Returns true when at least one number was added.
    static boolean addNumbers(PrizeRow row, String text) {
        if (text == null || text.isEmpty()) return false;
        boolean added = false;
        for (String tok : text.split("[\\s,]+")) {
            if (tok.matches("\\d+")) { row.numbers.add(tok); added = true; }
        }
        return added;
    }

    static List<Draw> parseBlocks(List<String> lines, int region) {
        List<Draw> out = new ArrayList<>();
        Draw cur = null;
        PrizeRow curRow = null;
        boolean inDD = false;
        List<String> ddLines = new ArrayList<>();
        for (String raw : lines) {
            String ln = raw.trim();
            // Empty lines are real (empty) cells inside the đầu
            // đuôi table; everywhere else they are ignored.
            if (ln.isEmpty()) {
                if (inDD) ddLines.add("");
                continue;
            }
            if (ln.startsWith("- ")) ln = ln.substring(2).trim();
            if (ln.isEmpty()) continue;
            String code = matchBlockStart(ln, region);
            if (code != null) {
                if (cur != null) { finishDraw(cur, ddLines); if (region != MB) sortPrizesAscending(cur); out.add(cur); }
                cur = new Draw();
                cur.provinceCode = code;
                if (region != MB) cur.province = PROVINCE_NAMES.get(code);
                curRow = null;
                inDD = false;
                ddLines = new ArrayList<>();
                continue;
            }
            if (cur == null) continue;
            if (ln.equals("Chục") || ln.startsWith("Chục ")) { inDD = true; curRow = null; continue; }
            if (inDD) {
                if (ln.equals("Số") || ln.equals("Đ.Vị") || ln.equals("Đơn vị")) continue;
                ddLines.add(ln);
                continue;
            }
            String label = matchPrizeLabel(ln);
            if (label != null) {
                curRow = new PrizeRow();
                curRow.label = label;
                cur.prizes.add(curRow);
                addNumbers(curRow, ln.substring(label.length()).trim());
                continue;
            }
            if (curRow != null) addNumbers(curRow, ln);
        }
        if (cur != null) { finishDraw(cur, ddLines); if (region != MB) sortPrizesAscending(cur); out.add(cur); }
        return out;
    }
    // v3.18: MN/MT results are read smallest-first — Giải 8
    // (2 digits) at the top, Giải ĐB (6 digits) at the bottom
    // (the user's correction: "thường là giải 8 trước"). The
    // source page lists them biggest-first, so reorder here at
    // parse time: every consumer (card + dò vé) then shows the
    // usual order. MB is different — its smallest prize is
    // Giải bảy and its convention IS biggest-first (ĐB on top)
    // — so MB keeps the source order.
    private static int prizeRank(String label) {
        if ("Giải 8".equals(label)) return 0;
        if ("Giải bảy".equals(label)) return 1;
        if ("Giải sáu".equals(label)) return 2;
        if ("Giải năm".equals(label)) return 3;
        if ("Giải tư".equals(label)) return 4;
        if ("Giải ba".equals(label)) return 5;
        if ("Giải nhì".equals(label)) return 6;
        if ("Giải nhất".equals(label)) return 7;
        if ("Giải ĐB".equals(label)) return 8;
        return 9;
    }
    // v3.32: canonical prize lists in DRAW order (smallest
    // first — every draw starts at the smallest prize and
    // ends at Giải ĐB), the digit count and the full count
    // of each prize (stable per region; MN counts match the
    // parsed 17/11 sample), and the "which prize is being
    // drawn right now" finder for the rolling effect.
    public static final String[] PRIZES_MN_MT = {"Giải 8", "Giải bảy", "Giải sáu", "Giải năm", "Giải tư", "Giải ba", "Giải nhì", "Giải nhất", "Giải ĐB"};
    public static final String[] PRIZES_MB = {"Giải bảy", "Giải sáu", "Giải năm", "Giải tư", "Giải ba", "Giải nhì", "Giải nhất", "Giải ĐB"};
    public static int prizeDigits(int region, String label) {
        if ("Giải 8".equals(label)) return 2;
        if ("Giải bảy".equals(label)) return region == MB ? 2 : 3;
        if ("Giải sáu".equals(label)) return region == MB ? 3 : 4;
        if ("Giải năm".equals(label)) return 4;
        if ("Giải tư".equals(label)) return region == MB ? 4 : 5;
        if ("Giải ĐB".equals(label)) return region == MB ? 5 : 6;
        return 5; // Giải ba, Giải nhì, Giải nhất
    }
    public static int prizeCount(int region, String label) {
        if (region == MB) {
            if ("Giải bảy".equals(label)) return 4;
            if ("Giải sáu".equals(label)) return 3;
            if ("Giải năm".equals(label)) return 6;
            if ("Giải tư".equals(label)) return 4;
            if ("Giải ba".equals(label)) return 6;
            if ("Giải nhì".equals(label)) return 2;
            if ("Giải nhất".equals(label)) return 2;
            return 1; // Giải ĐB
        }
        if ("Giải 8".equals(label)) return 1;
        if ("Giải bảy".equals(label)) return 1;
        if ("Giải sáu".equals(label)) return 3;
        if ("Giải năm".equals(label)) return 4;
        if ("Giải tư".equals(label)) return 7;
        if ("Giải ba".equals(label)) return 2;
        if ("Giải nhì".equals(label)) return 2;
        if ("Giải nhất".equals(label)) return 2;
        return 1; // Giải ĐB
    }
    public static class RollingPrize { public String label; public int digits; }
    public static RollingPrize rollingPrize(Draw d, int region) {
        if (d == null || d.prizes == null || d.prizes.isEmpty()) return null;
        // The prize being drawn: the smallest-rank prize that
        // is empty or still short of its full count.
        String bestLabel = null;
        int bestRank = Integer.MAX_VALUE;
        for (PrizeRow r : d.prizes) {
            int have = r.numbers == null ? 0 : r.numbers.size();
            if (have < prizeCount(region, r.label)) {
                int rk = prizeRank(r.label);
                if (rk < bestRank) { bestRank = rk; bestLabel = r.label; }
            }
        }
        if (bestLabel != null) {
            RollingPrize rp = new RollingPrize();
            rp.label = bestLabel;
            rp.digits = prizeDigits(region, bestLabel);
            return rp;
        }
        // No empty/partial row — the smallest canonical prize
        // not present in the draw at all (the source page
        // omits undrawn prizes entirely).
        String[] all = region == MB ? PRIZES_MB : PRIZES_MN_MT;
        for (String lab : all) {
            boolean present = false;
            for (PrizeRow r : d.prizes) if (lab.equals(r.label)) { present = true; break; }
            if (!present) {
                RollingPrize rp = new RollingPrize();
                rp.label = lab;
                rp.digits = prizeDigits(region, lab);
                return rp;
            }
        }
        return null;
    }
    private static void sortPrizesAscending(Draw d) {
        // Stable insertion sort (tiny list, no extra imports):
        // unknown labels keep their relative order.
        for (int i = 1; i < d.prizes.size(); i++) {
            PrizeRow r = d.prizes.get(i);
            int j = i - 1;
            while (j >= 0 && prizeRank(d.prizes.get(j).label) > prizeRank(r.label)) {
                d.prizes.set(j + 1, d.prizes.get(j));
                j--;
            }
            d.prizes.set(j + 1, r);
        }
    }
    // Đầu đuôi section: repeating triples [chục][số][đơn vị];
    // the middle cell is a single digit 0-9.
    private static void finishDraw(Draw d, List<String> ddLines) {
        for (int i = 0; i + 2 < ddLines.size() && d.dauDuoi.size() < 10; i += 3) {
            String digit = ddLines.get(i + 1);
            if (!digit.matches("\\d")) break;
            d.dauDuoi.add(new String[]{ddLines.get(i), digit, ddLines.get(i + 2)});
        }
    }
}
