package com.rightnews.live;
import android.content.Context;
import android.view.*;
import android.widget.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.*;
// v1.15: name-only rows (● enabled / ○ disabled) with swipe-left to
// reveal Sửa / Xoá. The swipe is handled on the name card itself:
// horizontal drags slide the card left (up to the 144dp button
// strip) and snap open/closed on release; vertical drags are left
// to the ListView (the card does not disallow interception until
// a horizontal drag clearly dominates, so list scrolling still
// works when the gesture starts on a source row). A tap on a
// closed card toggles the source; a tap on an open card just
// closes it. Only one row is open at a time; every bind starts
// closed, and opening a row closes all other visible rows, so an
// open state can never leak onto another source.
public class SourceAdapter extends BaseAdapter {
    public interface Listener {
        void onToggle(Source s);
        void onEdit(Source s);
        void onDelete(Source s);
    }
    private final Context c; private List<Source> items; private Map<String,String> status;
    private Listener listener;
    // v1.16: no openKey/openCard state any more. The v1.15 version
    // tracked the open row by URL + view reference, which can
    // desync across ListView recycling and leave buttons showing
    // on rows nobody swiped (the user's screenshot 2). Now every
    // bind starts closed, and opening a row closes every other
    // VISIBLE row by walking the ListView's children — no stale
    // reference can leak an open state onto another source.
    private ListView listView;
    SourceAdapter(Context c, List<Source> items, Map<String,String> st) { this.c = c; this.items = items; status = st; }
    public void setListener(Listener l) { listener = l; }
    // Status is still tracked (callers pass it in) but no longer
    // shown: rows are name-only by design.
    public void setStatus(Map<String,String> st) { status = st; notifyDataSetChanged(); }
    public int getCount() { return items.size(); }
    public Object getItem(int p) { return items.get(p); }
    public long getItemId(int p) { return p; }
    public View getView(int p, View v, ViewGroup parent) {
        if (v == null) v = LayoutInflater.from(c).inflate(R.layout.item_source, parent, false);
        if (parent instanceof ListView) listView = (ListView) parent;
        final Source s = items.get(p);
        final View card = v.findViewById(R.id.srcCard);
        // v1.16: force the card to full row width. The v1.15 card
        // could measure narrower than the row (leaving the buttons
        // permanently visible beside it); pinning it to the
        // ListView width guarantees the buttons stay hidden until
        // a swipe slides the card aside.
        if (parent != null && parent.getWidth() > 0) {
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null && lp.width != parent.getWidth()) { lp.width = parent.getWidth(); card.setLayoutParams(lp); }
        }
        final float d = c.getResources().getDisplayMetrics().density;
        final int buttons = (int) (144 * d);
        // v1.22: cardBg (dark-grey pills in dark mode, not
        // the black tint that vanished into the black bg — see
        // GlassUi.cardBg). Text follows the theme with it.
        card.setBackground(GlassUi.cardBg(c, 28f));
        TextView srcName = v.findViewById(R.id.txtSourceName);
        // v1.22: the status dot is colour-coded, not just filled
        // vs hollow in the name's colour (at 15sp the two looked
        // almost identical). Green filled = on, grey hollow = off
        // — colour AND shape both signal it, so it reads at a
        // glance and does not rely on colour alone.
        SpannableString nameSp = new SpannableString((s.enabled ? "● " : "○ ") + s.name);
        nameSp.setSpan(new ForegroundColorSpan(s.enabled ? 0xFF22C55E : 0xFF9CA3AF), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        srcName.setText(nameSp);
        srcName.setTextColor(Theme.bodyText(c));
        // v2.0.2: state line under the name, mirroring the
        // Settings rows (title + state): on/off + how many
        // categories it covers. v3.21: the feed host is gone —
        // a technical detail nobody checks a source by.
        TextView srcState = v.findViewById(R.id.txtSourceState);
        if (srcState != null) {
            // v3.2: one outlet = one row — show how many categories
            // (feeds) it covers, not one category per row.
            srcState.setText((s.enabled ? "Đang bật • " : "Đã tắt • ") + s.feeds.size() + " chuyên mục");
            srcState.setTextColor(Theme.noteText(c));
        }
        // v3.1: the Switch mirrors the model (listener detached
        // while binding, so setChecked never fires a toggle).
        // A tap flips the Switch, then onToggle flips the model
        // to match; the rebind sets the same state back.
        final Switch sw = v.findViewById(R.id.swSource);
        if (sw != null) {
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    if (on != s.enabled && listener != null) listener.onToggle(s);
                }
            });
        }
        final View btnEdit = v.findViewById(R.id.btnSourceEdit);
        final View btnDelete = v.findViewById(R.id.btnSourceDelete);
        // Every bind starts closed — an open state never
        // survives recycling onto another source. v1.19: the
        // buttons start FULLY transparent, not just covered. The
        // card is translucent in every glass mode, so buttons left
        // at alpha 1 bled through it as a faded ghost underneath
        // even when the row was closed (the user's screenshot).
        // They fade in only as the card actually slides aside.
        card.setTranslationX(0);
        btnEdit.setAlpha(0f);
        btnDelete.setAlpha(0f);
        btnEdit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View b) {
                card.setTranslationX(0);
                btnEdit.setAlpha(0f);
                btnDelete.setAlpha(0f);
                if (listener != null) listener.onEdit(s);
            }
        });
        btnDelete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View b) {
                card.setTranslationX(0);
                btnEdit.setAlpha(0f);
                btnDelete.setAlpha(0f);
                if (listener != null) listener.onDelete(s);
            }
        });
        card.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startTx;
            boolean moved, horizontal;
            public boolean onTouch(View v2, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY();
                        startTx = v2.getTranslationX();
                        moved = false; horizontal = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!horizontal && Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 10 * d) {
                            // Vertical scroll: let the ListView take
                            // it (it will send CANCEL, which resets).
                            return false;
                        }
                        if (Math.abs(dx) > 10 * d) {
                            horizontal = true; moved = true;
                            v2.getParent().requestDisallowInterceptTouchEvent(true);
                            float tx = startTx + dx;
                            if (tx > 0) tx = 0;
                            if (tx < -buttons) tx = -buttons;
                            v2.setTranslationX(tx);
                            // Fade the buttons in with the slide:
                            // closed = invisible, fully open = solid.
                            float a = -tx / (float) buttons;
                            if (a < 0) a = 0; if (a > 1) a = 1;
                            btnEdit.setAlpha(a);
                            btnDelete.setAlpha(a);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        if (!moved) {
                            if (startTx < -10 * d) {
                                // Tap on an open card: just close it.
                                v2.animate().translationX(0).setDuration(200).start();
                                btnEdit.animate().alpha(0f).setDuration(200).start();
                                btnDelete.animate().alpha(0f).setDuration(200).start();
                            } else if (listener != null) {
                                listener.onToggle(s);
                            }
                            return true;
                        }
                        if (v2.getTranslationX() < -buttons / 2) {
                            // Close every other visible row first
                            // (walk the ListView, not a stored
                            // reference, so recycling cannot leave
                            // a second row open) — and hide their
                            // buttons too, or they would ghost
                            // through their (translucent) cards.
                            if (listView != null) {
                                for (int i = 0; i < listView.getChildCount(); i++) {
                                    View other = listView.getChildAt(i).findViewById(R.id.srcCard);
                                    if (other != null && other != v2) {
                                        other.setTranslationX(0);
                                        View op = (View) other.getParent();
                                        if (op != null) {
                                            View oe = op.findViewById(R.id.btnSourceEdit);
                                            View od = op.findViewById(R.id.btnSourceDelete);
                                            if (oe != null) oe.setAlpha(0f);
                                            if (od != null) od.setAlpha(0f);
                                        }
                                    }
                                }
                            }
                            v2.animate().translationX(-buttons).setDuration(200).start();
                            btnEdit.animate().alpha(1f).setDuration(200).start();
                            btnDelete.animate().alpha(1f).setDuration(200).start();
                        } else {
                            v2.animate().translationX(0).setDuration(200).start();
                            btnEdit.animate().alpha(0f).setDuration(200).start();
                            btnDelete.animate().alpha(0f).setDuration(200).start();
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        v2.setTranslationX(startTx);
                        float ca = -startTx / (float) buttons;
                        if (ca < 0) ca = 0; if (ca > 1) ca = 1;
                        btnEdit.setAlpha(ca);
                        btnDelete.setAlpha(ca);
                        return true;
                }
                return false;
            }
        });
        return v;
    }
}
