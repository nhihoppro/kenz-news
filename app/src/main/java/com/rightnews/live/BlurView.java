package com.rightnews.live;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
// v1.7: a real backdrop blur for the bottom menu, like the screenshot
// the user sent (YouTube's bottom bar: the content behind it is
// visible but blurred, in both light and dark mode). How it works,
// with zero external libraries:
//   1. Snapshot the content view behind the bar (contentRoot, which
//      does NOT contain this bar, so there is no feedback loop),
//      at 1/4 scale, cropped to exactly the strip under this bar.
//   2. Stack-blur that small crop (a ~270x40 bitmap at 1/4 scale —
//      a few ms on the UI thread, no RenderScript, works from API 26).
//   3. Draw the blurred crop stretched over this view (bilinear
//      filtering softens it further), then a translucent tint
//      (dark tint in dark mode, white tint in light mode) and a
//      rounded stroke, like frosted glass.
// Refresh triggers: list scroll (throttled to ~150ms), data refresh,
// and layout. If the snapshot fails, or glass mode is Off, it falls
// back to a plain translucent/solid tint — the bar never goes blank.
public class BlurView extends View {
    private View source;
    private Bitmap blurred;
    private long lastRefresh;
    private boolean pending;
    private static final int DOWNSCALE = 4;
    public BlurView(Context c) { super(c); }
    public BlurView(Context c, AttributeSet a) { super(c, a); }
    public void setSource(View v) { source = v; }
    public void requestBlur() {
        // v1.23: throttle 90ms -> 150ms. With the strip-only
        // snapshot below each blur is already 5-9x cheaper, so the
        // longer interval mainly cuts dropped frames further
        // (6 blurs/s instead of 11 during a scroll). The blur is
        // peripheral and soft, so 150ms staleness during a scroll
        // is imperceptible; the central list scroll is what the
        // eye tracks, and it gets more frame budget this way.
        long now = SystemClock.uptimeMillis();
        if (now - lastRefresh < 150) {
            if (!pending) {
                pending = true;
                postDelayed(new Runnable() { public void run() { pending = false; doRefresh(); } }, 150);
            }
            return;
        }
        doRefresh();
    }
    private void doRefresh() {
        lastRefresh = SystemClock.uptimeMillis();
        if (GlassUi.mode(getContext()) == 3) { setBlurred(null); return; }
        if (source == null || getWidth() <= 0 || getHeight() <= 0
                || source.getWidth() <= 0 || source.getHeight() <= 0) return;
        try {
            // v1.23: draw ONLY the strip under this bar, not the
            // whole source. Before, the full source (270x600 at
            // 1/4 scale, every visible list item) was drawn and
            // then cropped to the bar strip (270x40) — 92% of the
            // drawn pixels were discarded, and the header strip is
            // empty anyway (the lists start below the header, so
            // its strip holds only background). Drawing the strip
            // directly (translate so the strip lands at the bitmap
            // origin, then scale) lets the canvas clip every view
            // outside the strip by bounds alone — no text/image
            // rasterisation for them. The header blur drops from
            // ~15-25ms to under 1ms (empty strip, just a background
            // fill), the menu blur to ~3-7ms (one strip item
            // instead of all of them). The resulting bitmap is the
            // same strip (within 1px integer-division rounding,
            // invisible once blurred), just produced without the
            // waste. Radii per level (Theme.blurRadius: Realistic
            // 10, Natural 7, Soft 4) and the Off skip above are
            // unchanged — the levels already differ in both tint
            // and blur, and Realistic's frosted look is what the
            // user sees and likes. Card glass needs no change
            // either: it never blurs (just tint + stroke, GPU,
            // cheap), so it was never the jank source.
            int[] locSelf = new int[2], locSrc = new int[2];
            getLocationOnScreen(locSelf); source.getLocationOnScreen(locSrc);
            int dxFull = locSelf[0] - locSrc[0];
            int dyFull = locSelf[1] - locSrc[1];
            int sw = Math.max(1, source.getWidth() / DOWNSCALE);
            int sh = Math.max(1, source.getHeight() / DOWNSCALE);
            int dx = Math.max(0, dxFull / DOWNSCALE);
            int dy = Math.max(0, dyFull / DOWNSCALE);
            int cw = Math.min(sw - dx, getWidth() / DOWNSCALE);
            int ch = Math.min(sh - dy, getHeight() / DOWNSCALE);
            if (cw <= 0 || ch <= 0) return; // bar outside the source (layout transition) — keep the previous frame.
            Bitmap crop = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(crop);
            c.drawColor(Theme.bgMidColor(getContext()));
            c.scale(1f / DOWNSCALE, 1f / DOWNSCALE);
            c.translate(-dxFull, -dyFull);
            source.draw(c);
            int radius = Theme.blurRadius(GlassUi.mode(getContext()));
            if (radius > 0) stackBlur(crop, radius);
            setBlurred(crop);
        } catch (Exception ignored) {
            // Keep the previous frame (or just the tint) — never crash
            // the list because a blur snapshot failed.
        }
    }
    private void setBlurred(Bitmap b) {
        if (blurred != null && blurred != b) blurred.recycle();
        blurred = b;
        invalidate();
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) requestBlur();
    }
    // v1.9 safety net for the 23:13 blank-screen bug: even if a
    // layout ever measures this view full-screen again, cap what
    // it draws to menu-bar height so it can never cover content.
    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int maxH = (int) (64f * getResources().getDisplayMetrics().density);
        if (getMeasuredHeight() > maxH) setMeasuredDimension(getMeasuredWidth(), maxH);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float d = getResources().getDisplayMetrics().density;
        float radius = 32f * d;
        RectF rect = new RectF(0, 0, getWidth(), getHeight());
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        if (blurred != null && !blurred.isRecycled()) {
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(blurred, null, rect, p);
        }
        Paint tint = new Paint();
        tint.setColor(Theme.barTint(getContext(), GlassUi.mode(getContext())));
        canvas.drawRect(rect, tint);
        canvas.restore();
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(1f);
        stroke.setColor(Theme.barStroke(getContext()));
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }
    // Stack blur (Mario Klingemann's algorithm, public domain lineage):
    // a fast moving-box blur that approximates a gaussian. Operates
    // in place on the bitmap's pixels.
    private static void stackBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);
        int wm = w - 1, hm = h - 1, div = radius + radius + 1;
        int[] r = new int[w * h], g = new int[w * h], b = new int[w * h];
        int[] vmin = new int[Math.max(w, h)];
        int divsum = (div + 1) >> 1; divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (int i = 0; i < dv.length; i++) dv[i] = i / divsum;
        int yw = 0, yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer, stackstart, rbs, r1 = radius + 1;
        int[] sir;
        int routsum, goutsum, boutsum, rinsum, ginsum, binsum;
        for (int y = 0; y < h; y++) {
            rinsum = goutsum = boutsum = routsum = ginsum = binsum = 0;
            int rsum = 0, gsum = 0, bsum = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p >> 16) & 0xff; sir[1] = (p >> 8) & 0xff; sir[2] = p & 0xff;
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs; gsum += sir[1] * rbs; bsum += sir[2] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
            }
            stackpointer = radius;
            for (int x = 0; x < w; x++) {
                r[yi] = dv[rsum]; g[yi] = dv[gsum]; b[yi] = dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                int p = pix[yw + vmin[x]];
                sir[0] = (p >> 16) & 0xff; sir[1] = (p >> 8) & 0xff; sir[2] = p & 0xff;
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi++;
            }
            yw += w;
        }
        for (int x = 0; x < w; x++) {
            rinsum = goutsum = boutsum = routsum = ginsum = binsum = 0;
            int rsum = 0, gsum = 0, bsum = 0;
            int yp = -radius * w;
            for (int i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi];
                rbs = r1 - Math.abs(i);
                rsum += r[yi] * rbs; gsum += g[yi] * rbs; bsum += b[yi] * rbs;
                if (i > 0) { rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; }
                else { routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; }
                if (i < hm) yp += w;
            }
            yi = x; stackpointer = radius;
            for (int y = 0; y < h; y++) {
                pix[yi] = 0xff000000 | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2];
                if (x == 0) vmin[y] = Math.min(y + r1, hm) * w;
                int p = x + vmin[y];
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p];
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2];
                rsum += rinsum; gsum += ginsum; bsum += binsum;
                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2];
                yi += w;
            }
        }
        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }
}
