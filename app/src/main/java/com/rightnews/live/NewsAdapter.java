package com.rightnews.live;
import android.content.Context;
import android.content.res.Configuration;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
public class NewsAdapter extends BaseAdapter {
    private final Context c; private List<NewsItem> items;
    NewsAdapter(Context c, List<NewsItem> items) { this.c = c; this.items = items; }
    public void setItems(List<NewsItem> it) { items = it; notifyDataSetChanged(); }
    public int getCount() { return items.size(); }
    public Object getItem(int p) { return items.get(p); }
    public long getItemId(int p) { return p; }
    // v3.1: two view types — big card (0) and compact row (1).
    // ListView recycling must never hand a compact row a big
    // card's view (or vice versa), so the type is declared.
    // v3.24: landscape also uses compact rows — a big card is
    // ~280dp tall on a ~360dp-tall screen, so landscape showed
    // ~1 story; compact rows show ~4. The "Dạng xem tin"
    // setting still rules portrait; rotation rebinds the rows
    // (see MainActivity.applyOrientationLayout).
    private boolean isCompact() {
        return Theme.compactView(c) || c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    public int getViewTypeCount() { return 2; }
    public int getItemViewType(int p) { return isCompact() ? 1 : 0; }
    // v3.34: ViewHolder — getView ran findViewById x4 on every
    // bind while scrolling; the holder drops that to zero. The
    // card background is also cached per theme (a fresh instance
    // per row via getConstantState().newDrawable(), so no sharing).
    private static class Holder { ImageView img; TextView title, desc, meta; }
    private static android.graphics.drawable.Drawable cardBgProto;
    private static boolean cardBgLight;
    private android.graphics.drawable.Drawable cardBg() {
        boolean light = Theme.isLight(c);
        if (cardBgProto == null || cardBgLight != light) {
            cardBgProto = GlassUi.cardBg(c, 28f);
            cardBgLight = light;
        }
        android.graphics.drawable.Drawable.ConstantState st = cardBgProto.getConstantState();
        return st == null ? GlassUi.cardBg(c, 28f) : st.newDrawable();
    }
    public View getView(int p, View v, ViewGroup parent) {
        Holder h;
        if (v == null) {
            v = LayoutInflater.from(c).inflate(
                    isCompact() ? R.layout.item_news_compact : R.layout.item_news, parent, false);
            h = new Holder();
            h.img = (ImageView) v.findViewById(R.id.imgThumb);
            h.title = (TextView) v.findViewById(R.id.txtTitle);
            h.desc = (TextView) v.findViewById(R.id.txtDesc);
            h.meta = (TextView) v.findViewById(R.id.txtMeta);
            v.setTag(h);
        } else h = (Holder) v.getTag();
        NewsItem it = items.get(p);
        // v1.24: dark-mode readability. applyCard's glass() in
        // dark Realistic is 40% white over the pure-black bg — a
        // dark-grey card — but item_news.xml text is fixed dark
        // (#111827 etc.), so titles sat dark-on-dark (08:52
        // screenshot: meta nearly invisible). Like the v1.22
        // state/source cards, news cards now use cardBg (solid
        // slate-800 in dark mode; in light mode cardBg IS barChip
        // and glass() light tints equal barTint light tints, so
        // light mode looks identical) with theme text colours.
        v.setBackground(cardBg());
        // v3.34: compact thumbs are 96x72dp — decode to 192px,
        // not 800px (8x less memory, faster decode, less GC).
        ImageLoader.load(h.img, it.imageUrl, isCompact() ? 192 : 800);
        // v1.5: show the Vietnamese title when it has been translated
        // (cached); original stays while loading or on failure.
        String displayTitle = it.title;
        if (TranslateUtil.translateTitlesEnabled(c)) {
            String vi = TranslateUtil.titleVi(it.key());
            if (vi != null && !vi.isEmpty()) displayTitle = vi;
        }
        h.title.setText(displayTitle);
        h.desc.setText(it.desc == null ? "" : it.desc);
        String when = it.time > 0 ? new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(it.time)) : "";
        h.meta.setText(it.source + (when.isEmpty() ? "" : " • " + when));
        // v3.1: font size (Nhỏ / Vừa / Lớn) — applied here so
        // both layouts follow the setting.
        h.title.setTextSize(Theme.titleSize(c));
        h.desc.setTextSize(Theme.descSize(c));
        h.meta.setTextSize(Theme.metaSize(c));
        // v1.24: theme text (light on the dark card in dark mode,
        // dark on the light card in light mode) — the layout's
        // fixed dark colours were the other half of the
        // dark-on-dark bug above. Video cards use this adapter
        // too, so they get the same fix.
        h.title.setTextColor(Theme.bodyText(c));
        h.desc.setTextColor(Theme.subText(c));
        h.meta.setTextColor(Theme.noteText(c));
        return v;
    }
}
