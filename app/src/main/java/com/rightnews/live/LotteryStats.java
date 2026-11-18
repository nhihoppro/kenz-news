package com.rightnews.live;

import java.util.*;

// Nightly: lottery statistics over the last N days for one
// region — 2-digit frequency, longest-absent ("lô gan") and
// head/tail frequency. Walks backwards day by day and harvests
// every dated block on each fetched page, so a handful of
// fetches usually covers 30 days. Pure Java (no Android
// imports); the caller runs compute() off the UI thread and
// posts the result back.
public class LotteryStats {
    public static class Result {
        public int region;
        public int daysCounted;
        public int daysRequested;
        public Map<String, Integer> freq = new HashMap<>();
        public Map<String, Integer> lastSeenDaysAgo = new HashMap<>();
        public int[] headFreq = new int[10];
        public int[] tailFreq = new int[10];
    }

    public interface Cb { void onDone(Result r); }

    private static Result cached = null;
    private static String cachedKey = "";

    public static void compute(final int region, final int days, final Cb cb) {
        final String key = region + "|" + LotteryFetcher.dateKey(Calendar.getInstance()) + "|" + days;
        if (cached != null && key.equals(cachedKey)) { cb.onDone(cached); return; }
        new Thread(new Runnable() {
            public void run() {
                Result r = computeSync(region, days);
                cached = r;
                cachedKey = key;
                cb.onDone(r);
            }
        }).start();
    }

    private static Result computeSync(int region, int days) {
        Result r = new Result();
        r.region = region;
        r.daysRequested = days;
        Map<String, List<LotteryFetcher.Draw>> byDay = new LinkedHashMap<>();
        Calendar today = Calendar.getInstance();
        Calendar day = (Calendar) today.clone();
        int attempts = 0;
        while (countDays(byDay) < days && attempts < days + 15) {
            String dk = LotteryFetcher.dateKey(day);
            List<LotteryFetcher.Draw> have = byDay.get(dk);
            if (have == null || have.isEmpty()) {
                LotteryFetcher.DayResult res = LotteryFetcher.fetch(region, day);
                if (res.error == null) harvest(res.allDraws, byDay);
                if (res.draws != null && !res.draws.isEmpty()) {
                    List<LotteryFetcher.Draw> l = byDay.get(dk);
                    if (l == null) { l = new ArrayList<>(); byDay.put(dk, l); }
                    for (LotteryFetcher.Draw d : res.draws) if (!l.contains(d)) l.add(d);
                }
            }
            day.add(Calendar.DAY_OF_MONTH, -1);
            attempts++;
        }
        // Newest days first (dateKey is DD-MM-YYYY).
        List<String> keys = new ArrayList<>(byDay.keySet());
        Collections.sort(keys, new Comparator<String>() {
            public int compare(String a, String b) { return dateOf(b).compareTo(dateOf(a)); }
        });
        count(r, byDay, keys, days, today);
        return r;
    }

    // Count 2-digit endings over the newest `days` days that
    // have draws. Package-private so a driver can test it with
    // synthetic draws (the network walk above can't run on the
    // VM — LotteryFetcher.download fails from a JVM here).
    static void count(Result r, Map<String, List<LotteryFetcher.Draw>> byDay,
                      List<String> keysNewestFirst, int days, Calendar today) {
        int counted = 0;
        for (String dk : keysNewestFirst) {
            if (counted >= days) break;
            List<LotteryFetcher.Draw> draws = byDay.get(dk);
            if (draws == null || draws.isEmpty()) continue;
            int daysAgo = daysBetween(dateOf(dk), today);
            for (LotteryFetcher.Draw d : draws) {
                for (LotteryFetcher.PrizeRow pr : d.prizes) {
                    for (String num : pr.numbers) {
                        String two = lastTwo(num);
                        if (two == null) continue;
                        Integer f = r.freq.get(two);
                        r.freq.put(two, f == null ? 1 : f + 1);
                        Integer ls = r.lastSeenDaysAgo.get(two);
                        if (ls == null || daysAgo < ls) r.lastSeenDaysAgo.put(two, daysAgo);
                        r.headFreq[two.charAt(0) - '0']++;
                        r.tailFreq[two.charAt(1) - '0']++;
                    }
                }
            }
            counted++;
        }
        r.daysCounted = counted;
        // Never seen in range: report as absent for the whole window.
        for (int i = 0; i < 100; i++) {
            String two = String.format(Locale.US, "%02d", i);
            if (!r.lastSeenDaysAgo.containsKey(two)) r.lastSeenDaysAgo.put(two, days);
        }
    }

    private static void harvest(List<LotteryFetcher.Draw> draws, Map<String, List<LotteryFetcher.Draw>> byDay) {
        if (draws == null) return;
        for (LotteryFetcher.Draw d : draws) {
            if (d.date == null) continue;
            String dk = LotteryFetcher.dateKey(d.date);
            List<LotteryFetcher.Draw> l = byDay.get(dk);
            if (l == null) { l = new ArrayList<>(); byDay.put(dk, l); }
            if (!l.contains(d)) l.add(d);
        }
    }

    private static int countDays(Map<String, List<LotteryFetcher.Draw>> byDay) {
        int n = 0;
        for (List<LotteryFetcher.Draw> l : byDay.values()) if (l != null && !l.isEmpty()) n++;
        return n;
    }

    private static String lastTwo(String num) {
        if (num == null) return null;
        String t = num.trim();
        if (t.length() < 2) return null;
        String two = t.substring(t.length() - 2);
        for (int i = 0; i < two.length(); i++) if (!Character.isDigit(two.charAt(i))) return null;
        return two;
    }

    private static Calendar dateOf(String dateKey) {
        try {
            String[] p = dateKey.split("-");
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[0]), 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c;
        } catch (Exception e) {
            return Calendar.getInstance();
        }
    }

    private static int daysBetween(Calendar a, Calendar b) {
        long ms = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) (ms / 86400000L);
    }
}
