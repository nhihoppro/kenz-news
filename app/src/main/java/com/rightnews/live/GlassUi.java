package com.rightnews.live;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
/** OriginOS-style 3 glass levels, manual-View version.
 *  Realistic = most transparent + strongest specular.
 *  Natural   = frosted. Soft = most opaque / cheapest.
 *  Off       = solid surface, no glass (accessibility / battery saver). */
public class GlassUi {
    public static final String[] MODES = {"Realistic", "Natural", "Soft", "Tắt"};
    public static int mode(Context c) {
        return c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE).getInt("glass_mode", 0);
    }
    public static void cycle(Context c) {
        SharedPreferences p = c.getSharedPreferences("kenzelections", Context.MODE_PRIVATE);
        p.edit().putInt("glass_mode", (mode(c) + 1) % MODES.length).apply();
    }
    public static Drawable glass(Context c, float radiusDp) {
        float density = c.getResources().getDisplayMetrics().density;
        int m = mode(c);
        int tint;
        if (Theme.isLight(c)) {
            if (m == 0) tint = 0x99FFFFFF;
            else if (m == 1) tint = 0xCCFFFFFF;
            else if (m == 2) tint = 0xE6FFFFFF;
            else tint = 0xFFFFFFFF;
        } else {
            if (m == 0) tint = 0x66FFFFFF;
            else if (m == 1) tint = 0xA6FFFFFF;
            else if (m == 2) tint = 0xD9FFFFFF;
            else tint = 0xFFFFFFFF;
        }
        int stroke = Theme.isLight(c) ? 0x330A1B4A : 0x99FFFFFF;
        return new GlassDrawable(tint, radiusDp * density, stroke);
    }
    // v1.10: bars and buttons use the MENU's own tint rule, not
    // the old white-chip tint: dark mode = black tints, light
    // mode = white tints, per glass level, Off = solid surface.
    // That is what "synchronised with the menu" means in code:
    // Theme.barTint is the single source of truth, used by
    // BlurView (menu + header), by applyBar (article/settings
    // headers) and by Theme.chipDrawable (all small buttons).
    // Cards deliberately keep glass() (light cards, dark text in
    // both themes) so article titles stay readable — cards are
    // not bars and are not part of this synchronisation.
    public static Drawable barChip(Context c, float radiusDp) {
        float density = c.getResources().getDisplayMetrics().density;
        return new GlassDrawable(Theme.barTint(c, mode(c)), radiusDp * density, Theme.barStroke(c));
    }
    // v1.22: the background for source + state cards (short info
    // cards, not long article titles). Light mode is barChip
    // unchanged (near-white pills on the light blue-grey bg).
    // Dark mode is NOT barChip's black tint: black tint over the
    // pure-black bg is still black, so v1.19's dark cards were
    // invisible except for their thin white border — text
    // floating on black with a border, not pills. The menu only
    // looks dark-grey (not black) because its real blur shows
    // blurred content behind it; a static card has no content
    // behind it, so to match the menu's APPEARANCE (dark-grey
    // frosted pill) rather than its tint value (black), dark
    // cards use solid slate-800 (#1E293B) with the same white
    // stroke. News/video cards deliberately keep the light glass
    // (applyCard) for long-title readability — that split (long
    // text = light, short text = dark) is intentional, not an
    // oversight.
    public static Drawable cardBg(Context c, float radiusDp) {
        if (Theme.isLight(c)) return barChip(c, radiusDp);
        float density = c.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF1E293B);
        d.setStroke(1, Theme.barStroke(c));
        d.setCornerRadius(radiusDp * density);
        return d;
    }
    public static void applyBar(Context c, View v) {
        v.setBackground(barChip(c, 32f));
        v.setElevation(6f * c.getResources().getDisplayMetrics().density);
        v.setClipToOutline(true);
    }
    public static void applyCard(Context c, View v) {
        v.setBackground(glass(c, 28f));
    }
    public static class GlassDrawable extends Drawable {
        private final int tint; private final float radius;
        private final Paint tintPaint = new Paint();
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path(); private final RectF rect = new RectF();
        GlassDrawable(int tint, float radius, int strokeColor) {
            this.tint = tint; this.radius = radius;
            tintPaint.setColor(tint);
            strokePaint.setStyle(Paint.Style.STROKE); strokePaint.setStrokeWidth(1f);
            strokePaint.setColor(strokeColor);
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); if (b.width() <= 0 || b.height() <= 0) return;
            rect.set(b); clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save(); canvas.clipPath(clip);
            canvas.drawRect(rect, tintPaint);
            Paint spec = new Paint();
            spec.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                    0x66FFFFFF, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawRect(rect, spec);
            canvas.restore();
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }
        @Override public void getOutline(Outline o) { o.setRoundRect(getBounds(), radius); }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter f) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
