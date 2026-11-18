package com.rightnews.live;
import android.content.Context;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
// v1.8: state boundaries for UsMapView. Source is the widely-used
// simplified PublicaMundi us-states.json (Leaflet choropleth
// tutorial dataset): a FeatureCollection of 52 features, each with
// properties.name and Polygon/MultiPolygon geometry in [lon, lat]
// order. A failure leaves the map in its "couldn't load" state but
// never breaks the scoreboard or the state list, which come from
// civicAPI alone. Puerto Rico is in that file but has no
// name->code entry in STATE_NAMES, so it is skipped: it is not
// part of the 2026 midterms.
// v1.11 (suggestion 3): the boundary file is saved to app-private
// storage on first download and reused from there on later
// launches, so the map no longer re-downloads on every refresh —
// only the first launch after install (or after a data clear)
// pays the download. A corrupt or incomplete file falls back to
// the network, never to a half-drawn map. Both paths share
// parse(), so both are held to the same >=50-states bar.
public class MapData {
    private static final String FILE = "us_states.json";
    private static List<UsMapView.StateShape> cached;
    public static synchronized List<UsMapView.StateShape> fetchShapes(Context ctx) {
        if (cached != null) return cached;
        if (ctx != null) {
            try {
                String saved = readAll(ctx.openFileInput(FILE));
                List<UsMapView.StateShape> fromFile = parse(saved);
                if (fromFile != null) { cached = fromFile; return cached; }
            } catch (Exception ignored) {}
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://raw.githubusercontent.com/PublicaMundi/MappingAPI/master/data/geojson/us-states.json").openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "KenzElections/2.0.2 (Android)");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream());
            List<UsMapView.StateShape> out = parse(body);
            if (out == null) return null;
            cached = out;
            if (ctx != null) {
                try {
                    OutputStream os = ctx.openFileOutput(FILE, Context.MODE_PRIVATE);
                    os.write(body.getBytes("UTF-8")); os.close();
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
    private static List<UsMapView.StateShape> parse(String body) throws Exception {
        if (body == null || body.isEmpty()) return null;
        Map<String,Object> top = Json.obj(Json.parse(body));
        Map<String,String> nameToCode = new HashMap<>();
        for (Map.Entry<String,String> en : ResultsFetcher.STATE_NAMES.entrySet()) nameToCode.put(en.getValue(), en.getKey());
        List<UsMapView.StateShape> out = new ArrayList<>();
        for (Object o : Json.arr(top.get("features"))) {
            Map<String,Object> feat = Json.obj(o);
            Map<String,Object> props = Json.obj(feat.get("properties"));
            String name = Json.str(props.get("name"), "");
            String code = nameToCode.get(name);
            if (code == null) continue; // Puerto Rico and anything unknown
            Map<String,Object> geom = Json.obj(feat.get("geometry"));
            String type = Json.str(geom.get("type"), "");
            List<Object> coords = Json.arr(geom.get("coordinates"));
            UsMapView.StateShape s = new UsMapView.StateShape();
            s.name = name; s.code = code;
            if ("Polygon".equals(type)) s.polys.add(parsePolygon(coords));
            else if ("MultiPolygon".equals(type)) {
                for (Object poly : coords) s.polys.add(parsePolygon(Json.arr(poly)));
            }
            // Centroid = average of the largest polygon's exterior ring.
            List<float[]> biggest = null;
            for (List<float[]> poly : s.polys) {
                if (!poly.isEmpty() && (biggest == null || poly.get(0).length > biggest.get(0).length)) biggest = poly;
            }
            if (biggest != null) {
                float[] ring = biggest.get(0);
                double sx = 0, sy = 0; int n = 0;
                for (int i = 0; i + 1 < ring.length; i += 2) { sx += ring[i]; sy += ring[i + 1]; n++; }
                if (n > 0) { s.cx = (float) (sx / n); s.cy = (float) (sy / n); }
            }
            out.add(s);
        }
        if (out.size() < 50) return null; // incomplete payload, do not draw a half map
        return out;
    }
    private static List<float[]> parsePolygon(List<Object> rings) {
        List<float[]> out = new ArrayList<>();
        for (Object r : rings) {
            List<Object> pts = Json.arr(r);
            float[] ring = new float[pts.size() * 2];
            int i = 0;
            for (Object pt : pts) {
                List<Object> xy = Json.arr(pt);
                ring[i++] = xy.size() > 0 ? (float) Json.num(xy.get(0), 0) : 0;
                ring[i++] = xy.size() > 1 ? (float) Json.num(xy.get(1), 0) : 0;
            }
            out.add(ring);
        }
        return out;
    }
    private static String readAll(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        try { in.close(); } catch (Exception ignored) {}
        return sb.toString();
    }
}
