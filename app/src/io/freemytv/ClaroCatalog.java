package io.freemytv;

import android.content.Context;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether Claro tv+ really has a title (ProviderTable.UNRELIABLE): TMDB (JustWatch) lists Claro tv+ as a subscription
 * for almost every new film. Claro's own public catalogue search (clarotvmais.com.br, no login) tells three cases
 * apart: a title that comes with a channel or partner (HBO, Telecine, Universal+…) is included in a package; one in
 * the catalogue without a channel is a rental ("Alugar por R$ 9,90"); one that isn't there isn't on Claro at all.
 * Only the title's name goes to Claro, as a search query. Kept 7 days (a miss 3), in memory and in
 * filesDir/claro-catalog.json (each process its own copy); a failed search keeps Claro hidden and is asked again later.
 */
final class ClaroCatalog {
    private ClaroCatalog() {}

    static final int NONE = 0, RENT = 1, INCLUDED = 2;

    static final class Verdict {
        final int state;
        final String channel;   // INCLUDED: the channel or partner it comes with ("HBO"), or null
        final long at;

        Verdict(int state, String channel, long at) {
            this.state = state;
            this.channel = channel;
            this.at = at;
        }

        boolean fresh(long now) {
            return now >= at && now - at < (state == NONE ? 3 : 7) * 24 * 3600_000L;
        }
    }

    private static final String HOST = "https://www.clarotvmais.com.br";
    private static final String FILE = "claro-catalog.json";
    private static final int MAX = 400;
    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<>();
    private static volatile File file;
    private static volatile boolean read;

    static void init(Context c) {
        if (file == null) file = new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    /**
     * The verdict for a TMDB title. Off the main thread it searches Claro when nothing fresh is known (a few hundred
     * ms, once a week per title); on the main thread, or when the search fails, only what's known (null: unknown).
     */
    static Verdict verdict(int kind, int id, String title, String original, int year) {
        String key = kind + ":" + id;
        long now = System.currentTimeMillis();
        boolean main = Looper.myLooper() == Looper.getMainLooper();
        if (!main) readFile();
        Verdict v = CACHE.get(key);
        if (v != null && v.fresh(now)) return v;
        if (main) return null;
        try {
            v = search(kind, title, original, year);
        } catch (Exception e) {   // offline, refused, changed: Claro stays hidden for this title, asked again next time
            AppLog.w("claro catalogue: " + e.getClass().getSimpleName());
            return null;
        }
        CACHE.put(key, v);
        writeFile();
        return v;
    }

    private static Verdict search(int kind, String title, String original, int year) throws Exception {
        List<String> queries = new ArrayList<>(2);
        if (title != null && !title.trim().isEmpty()) queries.add(title.trim());
        if (original != null && !original.trim().isEmpty() && !TitleMatch.core(original).equals(TitleMatch.core(title))) {
            queries.add(original.trim());
        }
        boolean found = false;
        String channel = null;
        for (String q : queries) {
            JSONArray items = query(q);
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null || !matches(it, kind, title, original, year)) continue;
                found = true;
                JSONArray ch = it.optJSONArray("tvChannels");
                for (int j = 0; ch != null && j < ch.length() && channel == null; j++) {
                    JSONObject c = ch.optJSONObject(j);
                    String name = c == null ? null : c.optString("name", null);
                    if (name != null && !name.trim().isEmpty()) channel = name.trim();
                }
                if (channel != null) break;
            }
            if (channel != null) break;
        }
        long now = System.currentTimeMillis();
        if (channel != null) {
            // Claro's own catalogue (included in every plan) needs no channel name beside "Claro tv+".
            String plain = TitleMatch.plain(channel);
            return new Verdict(INCLUDED, plain.startsWith("claro") ? null : channel, now);
        }
        return new Verdict(found ? RENT : NONE, null, now);
    }

    /** Same kind, same name (TMDB's or the original, "Rick & Morty" = "Rick and Morty"), the year within one. */
    private static boolean matches(JSONObject it, int kind, String title, String original, int year) {
        String type = it.optString("type", "");
        if (kind == ExploreModel.MOVIE ? !"movie".equals(type) : !"tvshow".equals(type)) return false;
        String name = it.optString("title", ""), alt = it.optString("episodeTitle", "");
        boolean same = sameName(name, title, original) || (kind == ExploreModel.MOVIE && sameName(alt, title, original));
        if (!same) return false;
        int y = 0;
        try {
            y = Integer.parseInt(it.optString("releaseYear", "0").trim());
        } catch (NumberFormatException ignored) {
            // no year: the name decides
        }
        return year <= 0 || y <= 0 || Math.abs(y - year) <= 1;
    }

    private static boolean sameName(String claro, String title, String original) {
        String c = TitleMatch.core(claro);
        if (c.isEmpty()) return false;
        return c.equals(TitleMatch.core(title)) || c.equals(TitleMatch.core(original));
    }

    /** Claro's search, the catalogue part only ("vod"), up to 20 results. */
    private static JSONArray query(String q) throws Exception {
        String e = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
        HttpURLConnection c = (HttpURLConnection) new URL(HOST + "/avsclient/1.3/contents/search?query=" + e + "&pageSize=6").openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("x-querystring", "channel=PCTV&query=" + e);
            c.setRequestProperty("x-carouselVod", "pageNumber=0&pageSize=20");
            c.setRequestProperty("x-carouselLive", "pageNumber=0&pageSize=1");
            c.setRequestProperty("x-carouselReplay", "pageNumber=0&pageSize=1");
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > (1 << 20)) throw new java.io.IOException("too large");
                }
            }
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            JSONObject r = o.optJSONObject("response");
            JSONArray carousels = r == null ? null : r.optJSONArray("carousels");
            for (int i = 0; carousels != null && i < carousels.length(); i++) {
                JSONObject car = carousels.optJSONObject(i);
                if (car != null && "vod".equals(car.optString("type"))) return car.optJSONArray("contents");
            }
            return new JSONArray();
        } finally {
            c.disconnect();
        }
    }

    // MARK: - The file (worker threads only)

    private static synchronized void readFile() {
        if (read || file == null) return;
        read = true;
        if (!file.isFile() || file.length() > (256 << 10)) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            if (o.optInt("v") != 1) return;
            JSONObject e = o.optJSONObject("e");
            long now = System.currentTimeMillis();
            for (Iterator<String> i = e == null ? null : e.keys(); i != null && i.hasNext(); ) {
                String k = i.next();
                JSONObject x = e.optJSONObject(k);
                if (x == null) continue;
                Verdict v = new Verdict(x.optInt("s"), x.optString("c", null), x.optLong("a"));
                if (v.fresh(now) && !CACHE.containsKey(k)) CACHE.put(k, v);
            }
        } catch (Exception ex) {
            Safe.log("claro catalogue read", ex);
        }
    }

    private static synchronized void writeFile() {
        if (file == null) return;
        try {
            long now = System.currentTimeMillis();
            List<Map.Entry<String, Verdict>> list = new ArrayList<>(CACHE.entrySet());
            java.util.Collections.sort(list, (a, b) -> Long.compare(b.getValue().at, a.getValue().at));
            JSONObject e = new JSONObject();
            int n = 0;
            for (Map.Entry<String, Verdict> m : list) {
                Verdict v = m.getValue();
                if (!v.fresh(now)) {
                    CACHE.remove(m.getKey());
                    continue;
                }
                if (n++ >= MAX) {
                    CACHE.remove(m.getKey());
                    continue;
                }
                JSONObject x = new JSONObject().put("s", v.state).put("a", v.at);
                if (v.channel != null) x.put("c", v.channel);
                e.put(m.getKey(), x);
            }
            BrProviders.writeAtomically(file, new JSONObject().put("v", 1).put("e", e).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            Safe.log("claro catalogue write", ex);
        }
    }
}
