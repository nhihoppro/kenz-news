package com.rightnews.live;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;
// v1.8: a real US map, coloured like Fox News' election map, from
// live civicAPI results. Boundaries are the simplified PublicaMundi
// us-states.json GeoJSON (50 states + DC; Puerto Rico is in that
// file but is NOT in the 2026 midterms, so it is skipped, not drawn
// grey). Colours are civicAPI's own party colours:
//   Dem leading/called  #4874A3, Rep leading/called  #C6606B,
//   region has data but no votes yet  #404040 (civicAPI's own
//   "fill" for an unreported region), state not in this race
//   (e.g. a Senate seat not up in 2026)  light grey #D1D5DB.
// Alaska and Hawaii are drawn as insets at the bottom, the way US
// election maps always do — not at true longitude, which would
// shrink the contiguous states to a strip. Labels: every state
// big enough to carry a 2-letter code gets one on the map; the
// tiniest north-eastern states (NH, RI, CT, NJ, DE, DC, and their
// neighbours when the screen is narrow) are labelled instead by
// the tappable pill row below the map (see MainActivity), because
// their on-map labels would overlap each other. Tapping any state
// (on the map, on a pill, or on its row in the list below) shows
// its detail in txtMapInfo.
// Projection is equirectangular with a cos(37°) longitude squash
// so state shapes look right, not stretched east-west.
public class UsMapView extends View {
    public static class StateShape {
        public String name, code;
        // Each polygon is a list of rings; each ring is a flat
        // float[] {lon,lat,lon,lat,...}. First ring is the exterior,
        // later rings are holes (drawn with EVEN_ODD).
        public List<List<float[]>> polys = new ArrayList<>();
        public float cx, cy; // centroid lon/lat (largest polygon)
    }
    public interface OnStateTap { void onStateTap(String code, ResultsFetcher.StateResult r, boolean inRace); }
    private List<StateShape> shapes;
    private boolean shapesFailed;
    private Map<String,ResultsFetcher.StateResult> results = new HashMap<>();
    private String selected;
    private OnStateTap listener;
    // v1.11 (suggestion 4): paths are built once per view size and
    // reused for every draw. Before this, onDraw rebuilt every
    // state's path three times per frame (fill pass, stroke pass,
    // and size measurement), so scrolling and refreshes redrew
    // thousands of segments for nothing. Colour changes (new
    // results) and selection changes do NOT rebuild paths — only
    // a size change or a new shape set does.
    private static class Cached {
        String code; Path path; float lx, ly, w, h;
    }
    private List<Cached> pathCache;
    private int cacheW, cacheH;
    private List<StateShape> cacheShapes;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public UsMapView(Context c, AttributeSet a) { super(c, a); init(); }
    public UsMapView(Context c) { super(c); init(); }
    private void init() {
        // v1.18: the map MUST be clickable. Before, it was not
        // (no clickable flag, no OnClickListener, only the custom
        // OnStateTap), so onTouchEvent(DOWN) fell through to
        // View.onTouchEvent, which returns false for a
        // non-clickable view — the gesture was never claimed, so
        // ACTION_UP went to the ScrollView instead and no map tap
        // ever fired, on any state. That was the whole "tap does
        // nothing" bug, not a hit-test bug.
        setClickable(true);
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setShadowLayer(3f, 0f, 1f, 0xFF000000);
    }
    public void setShapes(List<StateShape> s) { shapes = s; invalidate(); }
    public void setShapesFailed() { shapesFailed = true; invalidate(); }
    public void setResults(Map<String,ResultsFetcher.StateResult> m) { results = m == null ? new HashMap<String,ResultsFetcher.StateResult>() : m; invalidate(); }
    public void setSelected(String code) { selected = code; invalidate(); }
    public String getSelected() { return selected; }
    public void setOnStateTap(OnStateTap l) { listener = l; }
    // --- projection: main map + AK/HI insets -----------------------
    private static class Proj {
        float sx, sy, ox, oy; // screen = ox + (lon - lon0) * sx ; oy + (lat0 - lat) * sy
        float lon0, lat0;
        float[] to(float lon, float lat) { return new float[]{ox + (lon - lon0) * sx, oy + (lat0 - lat) * sy}; }
        float[] from(float x, float y) { return new float[]{lon0 + (x - ox) / sx, lat0 - (y - oy) / sy}; }
    }
    private Proj mainProj(int w, int h) {
        float mainH = h * 0.82f;
        float sy = mainH / 26f; // lat 24..50
        float sx = sy * 0.80f;  // cos(37°)
        if (59f * sx > w) { sx = w / 59f; sy = sx / 0.80f; }
        Proj p = new Proj();
        p.sx = sx; p.sy = sy; p.lon0 = -125f; p.lat0 = 50f;
        p.ox = (w - 59f * sx) / 2f; p.oy = (mainH - 26f * sy) / 2f;
        return p;
    }
    private Proj akProj(int w, int h) {
        float stripH = h * 0.18f, stripW = w * 0.28f, top = h * 0.82f;
        float sy = stripH / 20f; // lat 51..71
        float sx = sy * 0.80f;
        if (50f * sx > stripW) { sx = stripW / 50f; sy = sx / 0.80f; }
        Proj p = new Proj();
        p.sx = sx; p.sy = sy; p.lon0 = -180f; p.lat0 = 71f;
        p.ox = 0; p.oy = top + (stripH - 20f * sy) / 2f;
        return p;
    }
    private Proj hiProj(int w, int h) {
        float stripH = h * 0.18f, top = h * 0.82f;
        float segX = w * 0.30f, segW = w * 0.18f;
        float sy = stripH / 4f; // lat 18..22
        float sx = sy * 0.80f;
        if (7f * sx > segW) { sx = segW / 7f; sy = sx / 0.80f; }
        Proj p = new Proj();
        p.sx = sx; p.sy = sy; p.lon0 = -161f; p.lat0 = 22f;
        p.ox = segX + (segW - 7f * sx) / 2f; p.oy = top + (stripH - 4f * sy) / 2f;
        return p;
    }
    private Proj projFor(String code, int w, int h) {
        if ("AK".equals(code)) return akProj(w, h);
        if ("HI".equals(code)) return hiProj(w, h);
        return mainProj(w, h);
    }
    private int fillForCode(String code) {
        ResultsFetcher.StateResult r = results.get(code);
        if (r == null) return 0xFFD1D5DB; // not in this race
        if (r.winnerParty != null) return "Democratic".equals(r.winnerParty) ? 0xFF4874A3 : 0xFFC6606B;
        if (r.leaderParty != null) return "Democratic".equals(r.leaderParty) ? 0xFF4874A3 : 0xFFC6606B;
        return 0xFF404040; // has data, no votes yet
    }
    private void ensureCache(int w, int h) {
        if (pathCache != null && cacheW == w && cacheH == h && cacheShapes == shapes) return;
        pathCache = new ArrayList<>();
        cacheW = w; cacheH = h; cacheShapes = shapes;
        if (shapes == null) return;
        for (StateShape s : shapes) {
            Proj p = projFor(s.code, w, h);
            Path path = buildPath(s, p);
            if (path == null) continue;
            path.setFillType(Path.FillType.EVEN_ODD);
            float[] xy = p.to(s.cx, s.cy);
            float[] wh = stateScreenSize(s, p);
            Cached c = new Cached();
            c.code = s.code; c.path = path; c.lx = xy[0]; c.ly = xy[1]; c.w = wh[0]; c.h = wh[1];
            pathCache.add(c);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (shapes == null) {
            drawCenter(canvas, w, h, shapesFailed ? "Không tải được bản đồ — bấm Cập nhật để thử lại." : "Đang tải bản đồ bang…");
            return;
        }
        ensureCache(w, h);
        float d = getResources().getDisplayMetrics().density;
        textPaint.setTextSize(11f * d);
        // Fills first (all states), then strokes, so shared borders
        // stay crisp instead of being painted over by neighbours.
        for (Cached c : pathCache) {
            fillPaint.setColor(fillForCode(c.code));
            canvas.drawPath(c.path, fillPaint);
        }
        for (Cached c : pathCache) {
            if (c.code.equals(selected)) {
                strokePaint.setColor(0xFFFACC15);
                strokePaint.setStrokeWidth(3f * d);
                canvas.drawPath(c.path, strokePaint);
                strokePaint.setColor(0xFFFFFFFF);
                strokePaint.setStrokeWidth(1.5f * d);
            } else {
                canvas.drawPath(c.path, strokePaint);
            }
        }
        // v1.17: labels for every state big enough to carry a
        // 2-letter code (11sp text needs ~16dp wide, ~12dp tall).
        // The old 34x18dp bar skipped medium states outright
        // (UT at 33dp wide, IL/IN/MS/AL at 21-24dp, TN at 14dp
        // tall, and more) — that is the "states without names"
        // the user reported. The truly tiny north-eastern states
        // still cannot carry an on-map label without overlapping
        // each other, so they get the pill row below the map.
        for (Cached c : pathCache) {
            if (c.w < 16f * d || c.h < 12f * d) continue;
            // v1.24: white labels vanish on the light-grey "not
            // in race" fill (#D1D5DB) — the whole 08:52 map was
            // like that. Same rule as the small-state pills:
            // dark text on the light fill, white elsewhere.
            // (The black shadow stays: harmless under dark text,
            // and it is what keeps white labels readable on the
            // mid-tone party fills.)
            textPaint.setColor(fillForCode(c.code) == 0xFFD1D5DB ? 0xFF111827 : 0xFFFFFFFF);
            canvas.drawText(c.code, c.lx, c.ly + 4f * d, textPaint);
        }
    }
    private Path buildPath(StateShape s, Proj p) {
        Path path = new Path();
        boolean any = false;
        for (List<float[]> poly : s.polys) {
            for (float[] ring : poly) {
                if (ring.length < 6) continue;
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    float[] xy = p.to(ring[i], ring[i + 1]);
                    if (i == 0) path.moveTo(xy[0], xy[1]); else path.lineTo(xy[0], xy[1]);
                    any = true;
                }
                path.close();
            }
        }
        return any ? path : null;
    }
    private float[] stateScreenSize(StateShape s, Proj p) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (List<float[]> poly : s.polys) {
            for (float[] ring : poly) {
                for (int i = 0; i + 1 < ring.length; i += 2) {
                    float[] xy = p.to(ring[i], ring[i + 1]);
                    minX = Math.min(minX, xy[0]); maxX = Math.max(maxX, xy[0]);
                    minY = Math.min(minY, xy[1]); maxY = Math.max(maxY, xy[1]);
                }
            }
        }
        if (minX == Float.MAX_VALUE) return new float[]{0, 0};
        return new float[]{maxX - minX, maxY - minY};
    }
    private void drawCenter(Canvas canvas, int w, int h, String msg) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Theme.bodyText(getContext()));
        p.setTextSize(13f * getResources().getDisplayMetrics().density);
        p.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(msg, w / 2f, h / 2f, p);
    }
    // True when the lon/lat point lies in ANY polygon of the
    // shape (exterior ring, minus holes). Multi-polygon states
    // (MI's peninsulas, AK's Aleutians, HI's islands, CA's
    // Channel Islands, …) need every polygon checked, not just
    // the first — the v1.16 inset check only looked at the first
    // polygon, so tapping an Aleutian island or a non-Big-Island
    // Hawaiian island did nothing at all.
    private static boolean shapeContains(StateShape s, float lon, float lat) {
        for (List<float[]> poly : s.polys) {
            if (poly.isEmpty()) continue;
            if (!pointInRing(lon, lat, poly.get(0))) continue;
            boolean inHole = false;
            for (int i = 1; i < poly.size(); i++) if (pointInRing(lon, lat, poly.get(i))) { inHole = true; break; }
            if (!inHole) return true;
        }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        // Claim the gesture on DOWN so UP is delivered here even
        // if the clickable flag is ever lost; the ScrollView can
        // still steal a real scroll on MOVE (it cancels us), so
        // tab scrolling is unaffected and only clean taps select.
        if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (e.getAction() == MotionEvent.ACTION_UP && shapes != null && listener != null) {
            int w = getWidth(), h = getHeight();
            float x = e.getX(), y = e.getY();
            float d = getResources().getDisplayMetrics().density;
            String code = null;
            float[] ll = null;
            if (y >= h * 0.82f) {
                if (x < w * 0.28f) { code = "AK"; ll = akProj(w, h).from(x, y); }
                else if (x >= w * 0.30f && x < w * 0.48f) { code = "HI"; ll = hiProj(w, h).from(x, y); }
            } else {
                ll = mainProj(w, h).from(x, y);
            }
            if (ll != null) {
                if (code != null) {
                    // Inset tap: verify the point is actually inside
                    // that state's shape (all polygons, not just
                    // the first), not just its inset strip.
                    boolean inside = false;
                    for (StateShape s : shapes) {
                        if (code.equals(s.code) && shapeContains(s, ll[0], ll[1])) { inside = true; break; }
                    }
                    if (!inside) code = null;
                }
                if (code == null) code = hitState(ll[0], ll[1]);
                if (code == null) {
                    // v1.17 nearest-poly fallback: the simplified
                    // boundaries can miss a tap by a few px, and
                    // tiny states are easy to miss outright. If
                    // no polygon contains the tap, take the
                    // nearest polygon's centre within 16dp — close
                    // enough to rescue a near-miss on a small
                    // state or island, far too small to steal a
                    // tap meant for water or a neighbouring state.
                    ensureCache(w, h);
                    String nearest = null;
                    float best = Float.MAX_VALUE;
                    for (StateShape s : shapes) {
                        Proj p = projFor(s.code, w, h);
                        for (List<float[]> poly : s.polys) {
                            if (poly.isEmpty()) continue;
                            float[] ring = poly.get(0);
                            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                            for (int i = 0; i + 1 < ring.length; i += 2) {
                                float[] xy = p.to(ring[i], ring[i + 1]);
                                minX = Math.min(minX, xy[0]); maxX = Math.max(maxX, xy[0]);
                                minY = Math.min(minY, xy[1]); maxY = Math.max(maxY, xy[1]);
                            }
                            if (minX == Float.MAX_VALUE) continue;
                            float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f;
                            float dx = x - cx, dy = y - cy, dist2 = dx * dx + dy * dy;
                            if (dist2 < best) { best = dist2; nearest = s.code; }
                        }
                    }
                    if (nearest != null && best <= (16f * d) * (16f * d)) code = nearest;
                }
                if (code != null) {
                    ResultsFetcher.StateResult r = results.get(code);
                    listener.onStateTap(code, r, r != null);
                    return true;
                }
            }
        }
        return super.onTouchEvent(e);
    }
    private String hitState(float lon, float lat) {
        for (StateShape s : shapes) {
            if ("AK".equals(s.code) || "HI".equals(s.code)) continue;
            if (shapeContains(s, lon, lat)) return s.code;
        }
        return null;
    }
    private static boolean pointInRing(float lon, float lat, float[] ring) {
        boolean inside = false;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = ring[i * 2], yi = ring[i * 2 + 1];
            float xj = ring[j * 2], yj = ring[j * 2 + 1];
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }
}
