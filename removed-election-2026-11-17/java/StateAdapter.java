package com.rightnews.live;
import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
// v1.8: one row per state in the election graphics. The bar is the
// two-party split straight from civicAPI: for Senate/Governor it is
// the candidates' vote percents in that state; for House it is the
// share of that state's districts led by each party. Colours are
// civicAPI's own party colours (Dem #4874a3, Rep #c6606b) so the
// graphic matches the API's own map. A state with no votes yet
// shows a grey bar and "Chưa có kết quả" — never a guessed leader.
// v1.13: this is no longer a ListView adapter. The election tab is
// a ScrollView (so the whole tab scrolls above the bottom menu),
// and a ListView cannot live inside one — rows are inflated into
// a LinearLayout container instead, and bind() fills one row.
// Rows are cards now (GlassUi.applyCard, same as news/source
// cards), so their text is always the dark card palette, in both
// themes — never the theme body/note colours, which are light in
// dark mode and would vanish on a light card.
public class StateAdapter {
    public static void bind(View v, ResultsFetcher.StateResult s, Context c) {
        // v1.22: cardBg, same as source rows (dark-grey pills
        // in dark mode — see GlassUi.cardBg — not the black tint
        // that vanished into the black bg). News cards keep the
        // light glass for long-title readability, deliberately.
        v.setBackground(GlassUi.cardBg(c, 28f));
        ((TextView) v.findViewById(R.id.txtStateCode)).setText(s.code);
        ((TextView) v.findViewById(R.id.txtStateCode)).setTextColor(Theme.bodyText(c));
        ((TextView) v.findViewById(R.id.txtStateName)).setText(s.name);
        ((TextView) v.findViewById(R.id.txtStateName)).setTextColor(Theme.bodyText(c));
        // v1.15: one short status line (was three lines: rep% on its
        // own, then a long leader sentence). Reporting % first, then
        // the outcome in as few words as possible — the full detail
        // (leader name, exact percents) still shows in the map note
        // when the card is tapped.
        // v1.24: reporting % rounded to 1 decimal (was the raw
        // double: House reporting is an average over districts,
        // so it printed 0.04666666666666666% and
        // 59.81642857142857% in the 08:57 screenshot).
        String rep = fmt(s.reporting);
        TextView leader = v.findViewById(R.id.txtStateLeader);
        leader.setTextColor(Theme.noteText(c));
        if (s.isHouse) {
            int decided = s.demDistricts + s.repDistricts;
            if (s.leaderParty == null) leader.setText(rep + "% • " + decided + "/" + s.totalDistricts + " khu vực");
            else leader.setText(rep + "% • " + ("Democratic".equals(s.leaderParty) ? "Dân chủ" : "Cộng hòa") + " " + (s.leaderParty.equals("Democratic") ? s.demDistricts : s.repDistricts) + "/" + s.totalDistricts + " khu vực");
        } else if (s.winnerParty != null) {
            leader.setText(rep + "% • ✓ " + ("Democratic".equals(s.winnerParty) ? "Dân chủ" : "Cộng hòa") + " thắng");
        } else if (s.leaderParty == null) {
            leader.setText(rep + "% • Chưa có kết quả");
        } else {
            String pct = "Democratic".equals(s.leaderParty) ? fmt(s.demPercent) : fmt(s.repPercent);
            leader.setText(rep + "% • " + ("Democratic".equals(s.leaderParty) ? "Dân chủ" : "Cộng hòa") + " dẫn " + pct + "%");
        }
        // Bar segments by weight: Dem / Rep / rest (other parties or
        // undecided districts). At 0/0 the whole bar is grey.
        View demBar = v.findViewById(R.id.barDem);
        View repBar = v.findViewById(R.id.barRep);
        View restBar = v.findViewById(R.id.barRest);
        float demW = (float) s.demPercent, repW = (float) s.repPercent;
        float rest = Math.max(0, 100f - demW - repW);
        if (demW + repW <= 0) { demW = 0; repW = 0; rest = 100; }
        demBar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, demW));
        repBar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, repW));
        restBar.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, rest));
        demBar.setVisibility(demW > 0 ? View.VISIBLE : View.GONE);
        repBar.setVisibility(repW > 0 ? View.VISIBLE : View.GONE);
        restBar.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
    }
    private static String fmt(double d) {
        return d == (int) d ? String.valueOf((int) d) : String.format(Locale.US, "%.1f", d);
    }
}
