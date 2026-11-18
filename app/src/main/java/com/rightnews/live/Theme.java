package com.rightnews.live;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
// v1.7: Sáng / Tối. v3.13 adds Theo hệ thống (SYSTEM): the app
// follows the device's own light/dark setting, and the widget
// follows the app. A stored value of -1 (never set) also means
// SYSTEM, so the new default reaches existing installs too —
// anyone who explicitly picked Sáng/Tối keeps that choice
// (their stored 0/1 is untouched). Both themes keep bars
// see-through: the bar tint is translucent in both, and the
// bottom bar additionally carries a real blurred snapshot of
// the news underneath (BlurView). Cards stay light with dark
// text in both themes — that is what keeps article titles
// readable over either background.
public class Theme {
    public static final int DARK = 0, LIGHT = 1, SYSTEM = 2;
    public static int theme(Context c) {
        int t = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("theme", -1);
        return t == -1 ? SYSTEM : t;
    }
    public static boolean isSystemDark(Context c) {
        if ((c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true;
        // v3.32: fallback — some OEMs (HyperOS) do not propagate
        // the system night flag into the app's Configuration even
        // when the phone is dark, so the app stayed light. The
        // system night-mode setting itself is authoritative here.
        try {
            android.app.UiModeManager um = (android.app.UiModeManager)
                    c.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null && um.getNightMode() == android.app.UiModeManager.MODE_NIGHT_YES) return true;
        } catch (Exception ignored) {}
        return false;
    }
    public static boolean isLight(Context c) {
        int t = theme(c);
        return t == LIGHT || (t == SYSTEM && !isSystemDark(c));
    }
    public static String themeModeName(Context c) {
        int t = theme(c);
        return t == LIGHT ? "Sáng" : t == DARK ? "Tối" : "Theo hệ thống";
    }
    // v1.42 (T1): dialog theme following the app theme (see
    // res/values/styles.xml) — pass it to every AlertDialog
    // builder so dialogs stop showing up system-light while
    // the app is dark.
    public static int dialogTheme(Context c) {
        return isLight(c) ? R.style.KenzDialogLight : R.style.KenzDialogDark;
    }
    // v3.13: the Settings theme row cycles all three modes —
    // Tối -> Sáng -> Theo hệ thống -> Tối. MainActivity.onResume
    // already re-runs applyTheme(), so the new mode lands on
    // return; a system theme flip recreates the activities
    // (uiMode is NOT in any configChanges list) and isLight()
    // resolves the new effective theme on create.
    public static void cycleTheme(Context c) {
        int t = theme(c);
        int next = t == DARK ? LIGHT : t == LIGHT ? SYSTEM : DARK;
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("theme", next).apply();
    }
    // v3.13: give an AlertDialog the app's own look — a rounded
    // 20dp window (dialog_bg_*) plus pill buttons (white pill /
    // navy text in dark mode, navy pill / white text in light
    // mode, exactly like the selected tab). Call right after
    // .show(): the window and the buttons only exist once the
    // dialog is shown. The dialog theme in styles.xml carries
    // the same rounding + button style, so a dialog shown
    // without this helper still looks right; this call makes
    // it certain. Every AlertDialog in the app goes through it.
    public static void styleDialog(android.app.AlertDialog d, Context c) {
        if (d == null) return;
        try {
            android.view.Window w = d.getWindow();
            if (w != null) w.setBackgroundDrawableResource(isLight(c) ? R.drawable.dialog_bg_light : R.drawable.dialog_bg_dark);
            android.widget.Button[] btns = {
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE),
                d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE),
                d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)};
            int pad = (int) (12 * c.getResources().getDisplayMetrics().density);
            // v3.36: give the pill buttons breathing room — the
            // button bar packs them nearly touching, so each
            // button gets a 6dp side margin (12dp between two
            // buttons, 6dp to the dialog edge).
            int gap = (int) (6 * c.getResources().getDisplayMetrics().density);
            for (android.widget.Button b : btns) {
                if (b == null) continue;
                b.setBackground(pillDrawable(c));
                b.setTextColor(pillText(c));
                b.setPadding(pad, pad / 2, pad, pad / 2);
                android.view.ViewGroup.LayoutParams p = b.getLayoutParams();
                if (p instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams mp = (android.view.ViewGroup.MarginLayoutParams) p;
                    mp.leftMargin = gap;
                    mp.rightMargin = gap;
                    b.setLayoutParams(mp);
                }
            }
        } catch (Exception ignored) {}
    }
    // v3.1: font size — 0 Nhỏ / 1 Vừa (default) / 2 Lớn.
    // News/video cards read the three sizes; the article
    // reader uses textZoom on its WebView instead.
    public static int fontMode(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("font_size", 1);
    }
    public static void setFontMode(Context c, int m) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putInt("font_size", m).apply();
    }
    public static String fontModeName(Context c) {
        int m = fontMode(c);
        return m == 0 ? "Nhỏ" : m == 2 ? "Lớn" : "Vừa";
    }
    public static float titleSize(Context c) { int m = fontMode(c); return m == 0 ? 15f : m == 2 ? 19f : 17f; }
    public static float descSize(Context c) { int m = fontMode(c); return m == 0 ? 13f : m == 2 ? 15f : 14f; }
    public static float metaSize(Context c) { int m = fontMode(c); return m == 0 ? 11f : m == 2 ? 13f : 12f; }
    public static int textZoom(Context c) { int m = fontMode(c); return m == 0 ? 90 : m == 2 ? 115 : 100; }
    // v3.1: compact news view — big card (default) vs compact
    // row (small thumb right, 1-line desc).
    public static boolean compactView(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getBoolean("view_compact", false);
    }
    public static void setCompactView(Context c, boolean v) {
        c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).edit().putBoolean("view_compact", v).apply();
    }
    // v1.12: dark mode background is pure black, by his order
    // ("làm nền đen đi") — the navy gradient is gone from dark
    // mode entirely. Main, Article and Settings all use this one
    // method, so all three go black together. bgMidColor is the
    // fill BlurView paints under its snapshot, so it goes black
    // too: a blur bar over black must blur black, not navy.
    public static Drawable bgDrawable(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            isLight(c) ? new int[]{0xFFF4F7FC, 0xFFE6EDF7, 0xFFD9E2F0}
                       : new int[]{0xFF000000, 0xFF000000, 0xFF000000});
        d.setCornerRadius(0);
        return d;
    }
    public static int bgMidColor(Context c) { return isLight(c) ? 0xFFE8EEF7 : 0xFF000000; }
    // v3.9: the status bar + navigation bar follow the theme —
    // light mode gets light bars with DARK icons, dark mode
    // black bars with WHITE icons. Before this the app never
    // set either, so the icons stayed white and vanished on
    // the light background (his 11:19 screenshot: white clock
    // and battery on light grey). The bar colours match the
    // theme gradient's top (status) and bottom (navigation),
    // so the bars read as a continuation of the app background
    // — which also makes the loading screen (same gradient)
    // full-screen instead of ending at a mismatched strip.
    public static void applyStatusBar(android.app.Activity a) {
        try {
            boolean light = isLight(a);
            android.view.Window w = a.getWindow();
            w.setStatusBarColor(light ? 0xFFF4F7FC : 0xFF000000);
            w.setNavigationBarColor(light ? 0xFFD9E2F0 : 0xFF000000);
            android.view.View decor = w.getDecorView();
            int vis = decor.getSystemUiVisibility();
            int lightFlags = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (light) vis |= lightFlags;
            else vis &= ~lightFlags;
            decor.setSystemUiVisibility(vis);
        } catch (Exception ignored) {}
    }
    public static int headerText(Context c) { return isLight(c) ? 0xFF0A1B4A : 0xFFFFFFFF; }
    public static int subText(Context c) { return isLight(c) ? 0xFF475569 : 0xFFDDE6F5; }
    public static int bodyText(Context c) { return isLight(c) ? 0xFF1E293B : 0xFFEAF0FF; }
    public static int noteText(Context c) { return isLight(c) ? 0xFF64748B : 0xFFB9C6E0; }
    // v1.24: dark mode was 80% white, which washed out over
    // the blurred menu (bright photo behind it) in the 08:52
    // screenshot — unselected tabs now full white.
    public static int tabUnselected(Context c) { return isLight(c) ? 0xFF334155 : 0xFFFFFFFF; }
    public static int pillText(Context c) { return isLight(c) ? 0xFFFFFFFF : 0xFF0A1B4A; }
    public static Drawable pillDrawable(Context c) {
        if (!isLight(c)) return c.getResources().getDrawable(R.drawable.pill_selected, null);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF0A1B4A);
        d.setCornerRadius(22f * c.getResources().getDisplayMetrics().density);
        return d;
    }
    public static int chipText(Context c) { return isLight(c) ? 0xFF0A1B4A : 0xFFFFFFFF; }
    // v1.25: 144Hz support. Android runs an app at 60Hz unless
    // it asks for a faster display mode, so without this a
    // 144Hz phone still scrolled this app at 60fps. Pick the
    // fastest supported mode at or under 144Hz (the phone's own
    // 144Hz mode on a 144Hz phone; a 120Hz phone gets 120Hz)
    // and ask the window for it; if a phone only offers modes
    // above 144Hz, fall back to its fastest mode rather than
    // 60Hz. Wrapped in try/catch: on a phone with a single
    // mode this is a no-op, never a crash. Call from every
    // activity's onCreate, right after setContentView.
    public static void preferHighRefreshRate(android.app.Activity a) {
        try {
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode[] modes = d.getSupportedModes();
            if (modes == null || modes.length == 0) return;
            android.view.Display.Mode best = null;
            for (android.view.Display.Mode m : modes) {
                float r = m.getRefreshRate();
                if (r <= 144.5f && (best == null || r > best.getRefreshRate())) best = m;
            }
            if (best == null) for (android.view.Display.Mode m : modes)
                if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
            if (best == null || best.getModeId() == d.getMode().getModeId()) return;
            android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            a.getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }
    // v1.10: small buttons follow the menu's tint rule (see
    // GlassUi.barChip): dark = black tint, light = white tint.
    // Selected pills deliberately stay contrasting (white pill
    // in dark mode, navy pill in light mode) exactly like the
    // menu's selected tab — a selected button must stand out
    // from the bar it sits on, not blend into it.
    public static Drawable chipDrawable(Context c) {
        return GlassUi.barChip(c, 22f);
    }
    // Bottom-bar blur tint, per glass mode (Realistic/Natural/Soft/Off).
    // Dark mode uses a dark tint like the screenshot's bar; light mode
    // uses a white tint. Off = solid, no see-through.
    public static int barTint(Context c, int mode) {
        if (isLight(c)) {
            if (mode == 0) return 0x99FFFFFF;
            if (mode == 1) return 0xCCFFFFFF;
            if (mode == 2) return 0xE6FFFFFF;
            return 0xFFFFFFFF;
        }
        if (mode == 0) return 0x66000000;
        if (mode == 1) return 0x99000000;
        if (mode == 2) return 0xCC000000;
        return 0xFF0A1B4A;
    }
    public static int barStroke(Context c) { return isLight(c) ? 0x330A1B4A : 0x66FFFFFF; }
    // Stack-blur radius on the 1/4-scale snapshot.
    public static int blurRadius(int mode) {
        if (mode == 0) return 10;
        if (mode == 1) return 7;
        if (mode == 2) return 4;
        return 0;
    }
}
