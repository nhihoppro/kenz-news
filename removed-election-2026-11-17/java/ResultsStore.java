package com.rightnews.live;
import android.content.Context;
import java.io.*;
import java.util.*;
// v1.24: last good election results, saved to an app-private
// file on every results post (see MainActivity.refresh) and
// loaded at startup. civicAPI fails at random on election
// night (502s, timeouts — verified 2026-11-04), and v1.21's
// stale-keeping only worked WITHIN one process: after an
// install/restart the first refresh had no previous data, so
// a failed first load left an empty grey map (the 08:52
// screenshot: House timeout, Senate 502, Governor timeout,
// no data at all). With this file, a failed refresh after a
// restart keeps the last good data per race, labelled stale
// by the existing v1.21 code — exactly as if the process had
// never restarted. The saved data carries the API's own
// lastUpdated timestamp, so the scoreboard note still shows
// how old it is; nothing here is ever presented as fresh.
// Format (same esc/tab style as NewsStore): one race line
//   R <label> <demSeats> <repSeats> <demVotes> <repVotes>
//     <reporting> <lastUpdated> <testData 1/0> <majorityNote>
// then one line per region:
//   S <raceIdx> <code> <name> <leaderParty> <leaderName>
//     <winnerParty> <candidatesText> <reporting> <demPercent>
//     <repPercent> <demVotes> <repVotes> <isHouse 1/0>
//     <demDistricts> <repDistricts> <totalDistricts>
// save() merges: only races with ok=true in the new list
// replace their saved slot, so a partially failed refresh
// never wipes the saved data of a race that failed this
// time. Parsing is defensive — one bad line is skipped, a
// missing/corrupt file just means "no cache", never a crash.
public class ResultsStore {
    private static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " "); }
    private static String unNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private static double numD(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
    private static long numL(String s, long def) { try { return Long.parseLong(s); } catch (Exception e) { return def; } }
    private static int numI(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    public static void save(Context c, List<ResultsFetcher.Result> fresh) {
        try {
            List<ResultsFetcher.Result> cur = load(c);
            if (fresh != null) for (int i = 0; i < fresh.size() && i < cur.size(); i++) {
                if (fresh.get(i) != null && fresh.get(i).ok) cur.set(i, fresh.get(i));
            }
            FileOutputStream out = c.openFileOutput("results_cache.txt", Context.MODE_PRIVATE);
            PrintWriter w = new PrintWriter(new OutputStreamWriter(out, "UTF-8"));
            for (int i = 0; i < cur.size(); i++) {
                ResultsFetcher.Result r = cur.get(i);
                if (r == null || !r.ok) continue;
                w.println("R\t" + esc(r.label) + "\t" + r.demSeats + "\t" + r.repSeats + "\t" + r.demVotes + "\t" + r.repVotes
                        + "\t" + r.reporting + "\t" + esc(r.lastUpdated) + "\t" + (r.testData ? "1" : "0") + "\t" + esc(r.majorityNote));
                if (r.regions == null) continue;
                for (ResultsFetcher.StateResult s : r.regions) {
                    w.println("S\t" + i + "\t" + esc(s.code) + "\t" + esc(s.name) + "\t" + esc(s.leaderParty) + "\t" + esc(s.leaderName)
                            + "\t" + esc(s.winnerParty) + "\t" + esc(s.candidatesText) + "\t" + s.reporting + "\t" + s.demPercent + "\t" + s.repPercent
                            + "\t" + s.demVotes + "\t" + s.repVotes + "\t" + (s.isHouse ? "1" : "0") + "\t" + s.demDistricts + "\t" + s.repDistricts + "\t" + s.totalDistricts);
                }
            }
            w.close();
        } catch (Exception ignored) {}
    }
    public static List<ResultsFetcher.Result> load(Context c) {
        List<ResultsFetcher.Result> out = new ArrayList<>();
        String[] labels = {"Hạ viện", "Thượng viện", "Thống đốc"};
        for (String lb : labels) { ResultsFetcher.Result r = new ResultsFetcher.Result(); r.label = lb; out.add(r); }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.openFileInput("results_cache.txt"), "UTF-8"));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            br.close();
            // Pass 1: race lines (slot order is House, Senate,
            // Governor — the same order fetchAll returns, and
            // the order save() writes them in).
            int idx = -1;
            for (String l : lines) {
                String[] f = l.split("\t", -1);
                if (f.length < 10 || !"R".equals(f[0])) continue;
                idx++;
                if (idx >= out.size()) continue;
                ResultsFetcher.Result r = out.get(idx);
                if (!f[1].isEmpty()) r.label = f[1];
                r.demSeats = numI(f[2], 0); r.repSeats = numI(f[3], 0);
                r.demVotes = numL(f[4], 0); r.repVotes = numL(f[5], 0);
                r.reporting = numD(f[6], -1); r.lastUpdated = f[7];
                r.testData = "1".equals(f[8]); r.majorityNote = f[9];
                r.regions = new ArrayList<>();
                r.ok = true;
            }
            // Pass 2: region lines, attached to their race slot.
            for (String l : lines) {
                String[] f = l.split("\t", -1);
                if (f.length < 17 || !"S".equals(f[0])) continue;
                int ridx = numI(f[1], -1);
                if (ridx < 0 || ridx >= out.size() || !out.get(ridx).ok) continue;
                ResultsFetcher.StateResult s = new ResultsFetcher.StateResult();
                s.code = f[2]; s.name = f[3];
                s.leaderParty = unNull(f[4]); s.leaderName = unNull(f[5]); s.winnerParty = unNull(f[6]);
                s.candidatesText = f[7];
                s.reporting = numD(f[8], 0); s.demPercent = numD(f[9], 0); s.repPercent = numD(f[10], 0);
                s.demVotes = numL(f[11], 0); s.repVotes = numL(f[12], 0);
                s.isHouse = "1".equals(f[13]);
                s.demDistricts = numI(f[14], 0); s.repDistricts = numI(f[15], 0); s.totalDistricts = numI(f[16], 0);
                out.get(ridx).regions.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
