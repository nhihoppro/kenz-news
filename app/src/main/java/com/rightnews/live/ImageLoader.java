package com.rightnews.live;
import android.graphics.*;
import android.os.*;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
/** Manual-build image loader: no Glide/Coil available (zero deps).
 *  Downsamples to the view's real size, caches by URL, and checks
 *  the view tag before setting, so recycled list rows never flash
 *  the wrong photo.
 *  v3.34: the old loader was the scroll-jank source. It spawned a
 *  new Thread per row (two HTTP fetches each: bounds, then decode),
 *  so a fast fling started dozens of threads — and the thumb
 *  started GONE, then flipped VISIBLE when the bitmap landed,
 *  growing the row by 168dp (big card) and shifting the whole
 *  list. That pop-in is the "giật giật" in the user's 21:07
 *  video. Now: a 4-thread pool, one fetch per URL (bytes read
 *  once, bounds + decode from the byte array), in-flight dedupe
 *  (one fetch serves every row waiting on the same URL), an LRU
 *  cache capped at 48 bitmaps, and a browser UA (some image CDNs
 *  refuse the Kenz UA — one reason some items never got a photo).
 *  The thumb keeps its space while loading (placeholder
 *  background, no layout shift); it only collapses (GONE) when
 *  the item truly has no URL, or the fetch failed. */
public class ImageLoader {
    private static final int MAX_CACHE = 48;
    private static final Map<String, Bitmap> cache = Collections.synchronizedMap(
        new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > MAX_CACHE; }
        });
    private static final Executor pool = Executors.newFixedThreadPool(4);
    // URL -> views waiting on that fetch (tag-checked on delivery).
    private static final Map<String, List<ImageView>> pending = new HashMap<String, List<ImageView>>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    public static void load(final ImageView v, final String url) { load(v, url, 800); }
    public static void load(final ImageView v, final String url, final int maxPx) {
        if (url == null || url.isEmpty()) { v.setImageBitmap(null); v.setVisibility(ImageView.GONE); return; }
        v.setTag(url);
        Bitmap hit = cache.get(url);
        if (hit != null) { v.setImageBitmap(hit); v.setVisibility(ImageView.VISIBLE); return; }
        // Reserve the space while loading — the placeholder
        // (thumb_round background) shows, and the row never
        // changes size when the bitmap lands (no scroll jump).
        v.setImageBitmap(null);
        v.setVisibility(ImageView.VISIBLE);
        synchronized (pending) {
            List<ImageView> w = pending.get(url);
            if (w != null) { w.add(v); return; }
            List<ImageView> list = new ArrayList<ImageView>(); list.add(v); pending.put(url, list);
        }
        pool.execute(new Runnable() { public void run() { fetch(url, maxPx); } });
    }
    private static void fetch(final String url, int maxPx) {
        Bitmap bmp = null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", TranslateUtil.chromeUa());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192]; int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            try { in.close(); } catch (Exception ignored) {}
            byte[] data = buf.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            int sample = 1;
            while (o.outWidth / sample > maxPx || o.outHeight / sample > maxPx) sample *= 2;
            o2.inSampleSize = sample;
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Exception ignored) {}
        final Bitmap done = bmp;
        if (done != null) cache.put(url, done);
        final List<ImageView> waiters;
        synchronized (pending) { waiters = pending.remove(url); }
        main.post(new Runnable() { public void run() {
            if (waiters == null) return;
            for (ImageView v : waiters) {
                if (!url.equals(v.getTag())) continue;
                if (done != null) { v.setImageBitmap(done); v.setVisibility(ImageView.VISIBLE); }
                else v.setVisibility(ImageView.GONE); // fetch failed: collapse, one jump, rare.
            }
        }});
    }
}
