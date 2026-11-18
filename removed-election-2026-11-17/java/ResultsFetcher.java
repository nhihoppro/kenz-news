package com.rightnews.live;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
// v1.8: live results from civicAPI (free, no key — NOT Google Civic).
// Endpoints verified 2026-11-03 from civicAPI's own frontend bundle
// and by direct fetch:
//   race/87524 House aggregate, race/87528 Senate, race/87535 Governor
//   race/search?startDate=2026-11-03&endDate=2026-11-03&limit=50
// One fetch per race returns BOTH the national scoreboard (top-level
// candidates: seats/votes) AND the per-state/district detail
// (region_results), so the state graphics below cost no extra
// network call. Shape, verified on race 87528:
//   top: percent_reporting, last_updated, test_data, candidates[]
//   region_results: {"ak":{"name":"Alaska","type":"State",
//     "fill":"#404040","percent_reporting":0,"candidates":[
//     {"name":"...","party":"Republican","votes":0,"percent":0,
//      "color":"#c6606b","winner":false,...}]}, ...}
// House region keys are districts ("al-01"); they are aggregated
// to states by the code before '-'. test_data=true must be labelled
// in the UI, never presented as real results. The AP Elections API
// was rejected: it needs a paid key.
public class ResultsFetcher {
    public static class StateResult {
        public String code, name, leaderParty, leaderName, winnerParty;
        // v1.18: every real candidate in this state's race, as one
        // display string ("Name (Party) • Name (Party)…"), built
        // in fillFromCandidates. Senate/Governor regions carry it
        // (statewide races); House states do not (their candidates
        // are per district, not per state — aggregateHouse never
        // sets it, so House detail keeps its district summary).
        public String candidatesText = "";
        public double reporting, demPercent, repPercent;
        public long demVotes, repVotes;
        public boolean isHouse;
        public int demDistricts, repDistricts, totalDistricts;
    }
    public static class Result {
        public String label, majorityNote;
        public int demSeats, repSeats;
        public long demVotes, repVotes;
        public double reporting;
        public String lastUpdated = "";
        public boolean testData, ok;
        public String error = "";
        // v1.21: true when this Result is PREVIOUS good data kept
        // because the latest refresh failed (see MainActivity's
        // merge). ok stays true so the map/list/seats still render
        // it; error holds the failed refresh's error, so the UI
        // can label it as stale instead of presenting it as fresh.
        public boolean stale;
        public List<StateResult> regions = new ArrayList<>();
    }
    private static final int[][] RACES = {{87524, 435, 218}, {87528, 100, 51}, {87535, 0, 0}};
    private static final String[] LABELS = {"Hạ viện", "Thượng viện", "Thống đốc"};
    // raceId order above is House, Senate, Governor; the UI indexes
    // results as House=0, Senate=1, Governor=2, and the state
    // selector uses Senate=0, Governor=1, House=2 — keep both straight.
    // v1.21: the three races run in PARALLEL, not one after
    // another. Before, a slow race (or a timeout) delayed the
    // other two; now the refresh takes as long as the slowest
    // race, not the sum — which matters on election night when
    // civicAPI hangs or 502s at random (verified 2026-11-04: the
    // same race returned 200 then 502 two seconds apart, and a
    // timed-out House request succeeded on the very next try).
    public static List<Result> fetchAll() {
        final Result[] arr = new Result[RACES.length];
        Thread[] threads = new Thread[RACES.length];
        for (int i = 0; i < RACES.length; i++) {
            final int idx = i;
            threads[i] = new Thread(new Runnable() {
                public void run() { arr[idx] = fetchOne(RACES[idx][0], LABELS[idx], RACES[idx][1], RACES[idx][2]); }
            });
            threads[i].start();
        }
        for (int i = 0; i < threads.length; i++) {
            try { threads[i].join(); } catch (InterruptedException ignored) {}
        }
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != null) out.add(arr[i]);
            else { Result r = new Result(); r.label = LABELS[i]; r.error = "Interrupted"; out.add(r); }
        }
        return out;
    }
    // v1.21: one refresh retries ONCE, after 2s, for every failure
    // except "no network" (UnknownHostException — retrying cannot
    // fix DNS, so it fails fast). This covers the election-night
    // pattern exactly: fast 502s from an overloaded gateway and
    // hung connections that answer on the next try (both proven
    // live 2026-11-04, see fetchAll's comment). A retry that also
    // fails keeps the LAST error, which is what the UI shows.
    private static Result fetchOne(int raceId, String label, int total, int toWin) {
        Result r = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            r = attemptOne(raceId, label, total, toWin);
            if (r.ok) return r;
            if ("UnknownHostException".equals(r.error)) return r;
            if (attempt == 0) { try { Thread.sleep(2000); } catch (InterruptedException ignored) {} }
        }
        return r;
    }
    private static Result attemptOne(int raceId, String label, int total, int toWin) {
        Result r = new Result();
        r.label = label;
        // v1.42: short form — the total now lives as the hero
        // number on the scoreboard line itself, so the note
        // only carries the majority threshold (Governor has
        // no threshold, so its note is empty and hidden).
        r.majorityNote = total > 0 ? "Cần " + toWin + " để chiếm đa số" : "";
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://civicapi.org/api/v2/race/" + raceId).openConnection();
            c.setConnectTimeout(12000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "KenzElections/2.0.2 (Android)");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) { r.error = "HTTP " + c.getResponseCode(); return r; }
            String body = readAll(c.getInputStream());
            Map<String,Object> top = Json.obj(Json.parse(body));
            r.reporting = Json.num(top.get("percent_reporting"), -1);
            r.lastUpdated = Json.str(top.get("last_updated"), "");
            r.testData = Json.bool(top.get("test_data"), false);
            for (Object o : Json.arr(top.get("candidates"))) {
                Map<String,Object> cand = Json.obj(o);
                String party = Json.str(cand.get("party"), "");
                String name = Json.str(cand.get("name"), "");
                boolean dem = "Democratic".equals(party) || "Democrats".equals(name);
                boolean rep = "Republican".equals(party) || "Republicans".equals(name);
                if (dem) { r.demSeats = (int) Json.num(cand.get("seats"), 0); r.demVotes = (long) Json.num(cand.get("votes"), 0); }
                else if (rep) { r.repSeats = (int) Json.num(cand.get("seats"), 0); r.repVotes = (long) Json.num(cand.get("votes"), 0); }
            }
            r.regions = parseRegions(Json.obj(top.get("region_results")), raceId == 87524);
            r.ok = true;
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName();
        }
        return r;
    }
    private static List<StateResult> parseRegions(Map<String,Object> regions, boolean house) {
        if (house) return aggregateHouse(regions);
        List<StateResult> out = new ArrayList<>();
        for (Map.Entry<String,Object> en : regions.entrySet()) {
            Map<String,Object> reg = Json.obj(en.getValue());
            StateResult s = new StateResult();
            s.code = en.getKey().toUpperCase(Locale.US);
            s.name = Json.str(reg.get("name"), s.code);
            s.reporting = Json.num(reg.get("percent_reporting"), 0);
            fillFromCandidates(s, Json.arr(reg.get("candidates")));
            out.add(s);
        }
        Collections.sort(out, new Comparator<StateResult>() {
            public int compare(StateResult a, StateResult b) { return a.name.compareTo(b.name); }
        });
        return out;
    }
    private static String partyVn(String party) {
        if ("Democratic".equals(party)) return "Dân chủ";
        if ("Republican".equals(party)) return "Cộng hòa";
        if ("Libertarian".equals(party)) return "Tự do";
        if ("Independent".equals(party)) return "Độc lập";
        if ("Nonpartisan".equals(party)) return "Không đảng phái";
        if ("Green".equals(party)) return "Xanh";
        return party;
    }
    private static void fillFromCandidates(StateResult s, List<Object> cands) {
        double demVotes = 0, repVotes = 0, demPct = 0, repPct = 0;
        String demName = "", repName = "";
        String winParty = null;
        StringBuilder candList = new StringBuilder();
        for (Object o : cands) {
            Map<String,Object> cand = Json.obj(o);
            String party = Json.str(cand.get("party"), "");
            String name = Json.str(cand.get("name"), "");
            double votes = Json.num(cand.get("votes"), 0);
            double pct = Json.num(cand.get("percent"), 0);
            boolean winner = Json.bool(cand.get("winner"), false);
            if ("Democratic".equals(party)) { demVotes = votes; demPct = pct; demName = name; if (winner) winParty = "Democratic"; }
            else if ("Republican".equals(party)) { repVotes = votes; repPct = pct; repName = name; if (winner) winParty = "Republican"; }
            // v1.18: keep every real candidate for the detail note.
            // "Write-in" is a ballot option, not a person, so it is
            // skipped; empty names are skipped too. A state can
            // have several candidates from one party (Alaska has
            // two Republicans) or many third-party candidates
            // (Colorado has nine), so the detail shows the whole
            // list instead of picking a misleading Dem-vs-Rep pair.
            if (name == null || name.trim().isEmpty()) continue;
            if ("Write-in".equalsIgnoreCase(name.trim())) continue;
            String pv = partyVn(party);
            if (candList.length() > 0) candList.append(" • ");
            candList.append(name.trim());
            if (pv != null && !pv.isEmpty()) candList.append(" (").append(pv).append(")");
        }
        s.candidatesText = candList.toString();
        s.demVotes = (long) demVotes; s.repVotes = (long) repVotes;
        s.demPercent = demPct; s.repPercent = repPct;
        s.winnerParty = winParty;
        long total = (long) (demVotes + repVotes);
        if (winParty != null) {
            s.leaderParty = winParty;
            s.leaderName = "Democratic".equals(winParty) ? demName : repName;
        } else if (total > 0) {
            if (demVotes > repVotes) { s.leaderParty = "Democratic"; s.leaderName = demName; }
            else if (repVotes > demVotes) { s.leaderParty = "Republican"; s.leaderName = repName; }
        }
    }
    private static List<StateResult> aggregateHouse(Map<String,Object> districts) {
        Map<String,StateResult> byState = new LinkedHashMap<>();
        for (Map.Entry<String,Object> en : districts.entrySet()) {
            String key = en.getKey();
            int dash = key.indexOf('-');
            if (dash <= 0) continue;
            String code = key.substring(0, dash).toUpperCase(Locale.US);
            Map<String,Object> reg = Json.obj(en.getValue());
            StateResult s = byState.get(code);
            if (s == null) {
                s = new StateResult();
                s.code = code; s.name = STATE_NAMES.get(code);
                if (s.name == null) s.name = code;
                s.isHouse = true;
                byState.put(code, s);
            }
            s.totalDistricts++;
            s.reporting += Json.num(reg.get("percent_reporting"), 0);
            StateResult tmp = new StateResult();
            fillFromCandidates(tmp, Json.arr(reg.get("candidates")));
            s.demVotes += tmp.demVotes; s.repVotes += tmp.repVotes;
            if (tmp.leaderParty != null) {
                if ("Democratic".equals(tmp.leaderParty)) s.demDistricts++;
                else if ("Republican".equals(tmp.leaderParty)) s.repDistricts++;
            }
        }
        List<StateResult> out = new ArrayList<>();
        for (StateResult s : byState.values()) {
            if (s.totalDistricts > 0) {
                s.reporting = s.reporting / s.totalDistricts;
                s.demPercent = 100.0 * s.demDistricts / s.totalDistricts;
                s.repPercent = 100.0 * s.repDistricts / s.totalDistricts;
                if (s.demDistricts > s.repDistricts) s.leaderParty = "Democratic";
                else if (s.repDistricts > s.demDistricts) s.leaderParty = "Republican";
            }
            out.add(s);
        }
        Collections.sort(out, new Comparator<StateResult>() {
            public int compare(StateResult a, StateResult b) { return a.name.compareTo(b.name); }
        });
        return out;
    }
    public static final Map<String,String> STATE_NAMES = new HashMap<>();
    static {
        String[][] pairs = {
            {"AL","Alabama"},{"AK","Alaska"},{"AZ","Arizona"},{"AR","Arkansas"},
            {"CA","California"},{"CO","Colorado"},{"CT","Connecticut"},{"DE","Delaware"},
            {"DC","District of Columbia"},{"FL","Florida"},{"GA","Georgia"},{"HI","Hawaii"},
            {"ID","Idaho"},{"IL","Illinois"},{"IN","Indiana"},{"IA","Iowa"},
            {"KS","Kansas"},{"KY","Kentucky"},{"LA","Louisiana"},{"ME","Maine"},
            {"MD","Maryland"},{"MA","Massachusetts"},{"MI","Michigan"},{"MN","Minnesota"},
            {"MS","Mississippi"},{"MO","Missouri"},{"MT","Montana"},{"NE","Nebraska"},
            {"NV","Nevada"},{"NH","New Hampshire"},{"NJ","New Jersey"},{"NM","New Mexico"},
            {"NY","New York"},{"NC","North Carolina"},{"ND","North Dakota"},{"OH","Ohio"},
            {"OK","Oklahoma"},{"OR","Oregon"},{"PA","Pennsylvania"},{"RI","Rhode Island"},
            {"SC","South Carolina"},{"SD","South Dakota"},{"TN","Tennessee"},{"TX","Texas"},
            {"UT","Utah"},{"VT","Vermont"},{"VA","Virginia"},{"WA","Washington"},
            {"WV","West Virginia"},{"WI","Wisconsin"},{"WY","Wyoming"}
        };
        for (String[] p : pairs) STATE_NAMES.put(p[0], p[1]);
    }
    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        try { in.close(); } catch (Exception ignored) {}
        return sb.toString();
    }
}
