package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A title's own page in each service's app (Watchmode's web addresses, which the apps open), looked up only when a
 * detail page with an installed offer comes to rest, and kept in filesDir/explore-links.json (500 titles, 30 days,
 * "none found" too). Never in the "recs" preferences: Recs.save trims Watchmode keys there. At most 60 calls a day;
 * the key travels in the URL, so nothing about a request is ever logged but its status code.
 */
final class TitleLinks {
    private static final String FILE = "explore-links.json";
    private static final String HOST = "api.watchmode.com";
    private static final long TTL = 30L * 24 * 3600_000L;
    private static final int MAX_TITLES = 500, MAX_CALLS = 60;
    private static final long BACKOFF = 10 * 60_000L;

    private static TitleLinks instance;

    static synchronized TitleLinks get(Context c) {
        if (instance == null && c != null) instance = new TitleLinks(c.getApplicationContext());
        return instance;
    }

    /** Refused key's hashCode (Watchmode answered 401 to it), from the file; 0 = none. */
    private static volatile int refused;

    /** A Watchmode key is set and Watchmode hasn't refused it. */
    static boolean on() {
        String key = Recs.watchmodeKey();
        return !key.isEmpty() && (refused == 0 || refused != key.hashCode());
    }

    private static final class Links {
        final long at;
        final Map<String, String> urls;   // service key → https address

        Links(long at, Map<String, String> urls) {
            this.at = at;
            this.urls = urls;
        }
    }

    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Links> links = new ConcurrentHashMap<>();
    private final Set<String> looking = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile boolean loaded;
    private volatile long retryAt;
    private String day = "";
    private int calls;   // today's (Rio Branco), guarded by this

    private TitleLinks(Context app) {
        file = new File(app.getFilesDir(), FILE);
        Safe.IO.execute(Safe.wrap("links read", this::read));
    }

    private static String key(int kind, int id) {
        return (kind == ExploreModel.TV ? "tv" : "movie") + ":" + id;
    }

    /** UI-safe: the title's address in that service's app, or null for none known (the app's home then). */
    String cached(int kind, int id, String serviceKey) {
        if (serviceKey == null) return null;
        Links l = links.get(key(kind, id));
        return l == null ? null : l.urls.get(serviceKey);
    }

    /** Looks the title up once (IDLE priority); onDone on the main thread, whatever happened. */
    void lookup(int kind, int id, Runnable onDone) {
        String k = key(kind, id);
        Links known = links.get(k);
        boolean fresh = known != null && System.currentTimeMillis() - known.at < TTL;
        if (fresh || id <= 0 || !on() || SystemClock.elapsedRealtime() < retryAt || !looking.add(k)) {
            done(onDone);
            return;
        }
        Tmdb.NET.execute(new Safe.Job("links", Tmdb.IDLE) {
            @Override
            void work() {
                try {
                    find(kind, id, k);
                } finally {
                    looking.remove(k);
                    done(onDone);
                }
            }
        });
    }

    private void done(Runnable onDone) {
        if (onDone != null) Safe.post(main, "links", onDone);
    }

    // MARK: - Worker

    private static final class Refused extends IOException {
        Refused() { super("refused"); }
    }

    private static final class NotFound extends IOException {
        NotFound() { super("not found"); }
    }

    private void find(int kind, int id, String k) {
        if (!loaded) read();
        String key = Recs.watchmodeKey();
        if (key.isEmpty()) return;
        String kindName = kind == ExploreModel.TV ? "tv" : "movie";
        try {
            JSONArray sources;
            try {
                sources = new JSONArray(call("/title/" + kindName + "-" + id + "/sources/?regions=" + TmdbPaths.REGION, key));
            } catch (NotFound e) {
                // The one-call form isn't known for this title: search by TMDB id, then its sources (Recs.findLink's two calls).
                JSONObject found = new JSONObject(call("/search/?search_field=tmdb_" + kindName + "_id&search_value=" + id, key));
                JSONArray results = found.optJSONArray("title_results");
                if (results == null || results.length() == 0 || results.optJSONObject(0) == null) {
                    store(k, new HashMap<String, String>());
                    return;
                }
                sources = new JSONArray(call("/title/" + results.optJSONObject(0).optInt("id") + "/sources/?regions=" + TmdbPaths.REGION, key));
            }
            store(k, parse(sources));
        } catch (Refused e) {
            refused = key.hashCode();
            write();
        } catch (IOException | JSONException | RuntimeException e) {
            retryAt = SystemClock.elapsedRealtime() + BACKOFF;   // quota or network: not again right away
            Safe.log("watchmode", e);
        }
    }

    /** Subscription, free and TV-provider sources with an https address, each to its service; resold channels skipped. */
    static Map<String, String> parse(JSONArray sources) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            String type = s.optString("type"), url = s.optString("web_url"), name = s.optString("name");
            if (!(type.equals("sub") || type.equals("free") || type.equals("tve")) || !url.startsWith("https://")) continue;
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("(via") || lower.contains("channel")) continue;
            ProviderTable.Service service = ProviderTable.match(0, name);
            if (service == null) service = squeezed(name);
            if (service != null && !out.containsKey(service.key)) out.put(service.key, url);
        }
        return out;
    }

    /** Watchmode's spellings without spaces ("AppleTV+", "PlutoTV"). */
    private static ProviderTable.Service squeezed(String name) {
        String n = ProviderTable.plain(name).replace(" ", "");
        for (ProviderTable.Service s : ProviderTable.TABLE) {
            if (s.key.equals("max")) continue;   // a word only: never inside another name
            for (String w : s.words) if (n.contains(w.replace(" ", ""))) return s;
        }
        return null;
    }

    private String call(String path, String key) throws IOException {
        synchronized (this) {
            String today = TmdbPaths.today(System.currentTimeMillis());
            if (!today.equals(day)) {
                day = today;
                calls = 0;
            }
            if (calls >= MAX_CALLS) throw new IOException("quota");
            calls++;
        }
        URL url = new URL("https://" + HOST + "/v1" + path + (path.indexOf('?') >= 0 ? "&" : "?") + "apiKey=" + URLEncoder.encode(key, "UTF-8"));
        if (!HOST.equals(url.getHost())) throw new IOException("host");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        int code = c.getResponseCode();
        if (code != 200) {
            AppLog.w("watchmode " + code);
            InputStream err = c.getErrorStream();
            if (err != null) err.close();
            if (code == 401) throw new Refused();
            if (code == 404) throw new NotFound();
            throw new IOException("http");
        }
        return read(c.getInputStream());
    }

    private static String read(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > (1 << 20)) throw new IOException("too big");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            in.close();
        }
    }

    // MARK: - The file

    private void store(String k, Map<String, String> urls) {
        links.put(k, new Links(System.currentTimeMillis(), Collections.unmodifiableMap(urls)));
        if (links.size() > MAX_TITLES) {
            List<Map.Entry<String, Links>> all = new ArrayList<>(links.entrySet());
            Collections.sort(all, (a, b) -> Long.compare(a.getValue().at, b.getValue().at));
            for (int i = 0; i < all.size() - MAX_TITLES; i++) links.remove(all.get(i).getKey());
        }
        write();
    }

    private synchronized void read() {
        if (loaded) return;
        loaded = true;
        if (!file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            JSONObject o = new JSONObject(read(in));
            refused = o.optInt("refused", 0);
            day = o.optString("day", "");
            calls = o.optInt("calls", 0);
            JSONObject titles = o.optJSONObject("links");
            long now = System.currentTimeMillis();
            for (Iterator<String> i = titles == null ? null : titles.keys(); i != null && i.hasNext(); ) {
                String k = i.next();
                JSONObject t = titles.optJSONObject(k);
                if (t == null) continue;
                long at = t.optLong("at");
                if (now - at > TTL || now < at) continue;
                Map<String, String> urls = new HashMap<>();
                JSONObject u = t.optJSONObject("u");
                for (Iterator<String> j = u == null ? null : u.keys(); j != null && j.hasNext(); ) {
                    String service = j.next(), url = u.optString(service);
                    if (url.startsWith("https://") && ProviderTable.byKey(service) != null) urls.put(service, url);
                }
                links.put(k, new Links(at, Collections.unmodifiableMap(urls)));
            }
        } catch (IOException | JSONException | RuntimeException e) {
            Safe.log("links read", e);
        }
    }

    /** On Safe.IO: the whole file again (.part, then renamed). */
    private void write() {
        Safe.IO.execute(Safe.wrap("links write", () -> {
            try {
                JSONObject o = new JSONObject();
                synchronized (this) {
                    o.put("day", day);
                    o.put("calls", calls);
                }
                o.put("refused", refused);
                JSONObject titles = new JSONObject();
                Set<String> keys = new HashSet<>(links.keySet());
                for (String k : keys) {
                    Links l = links.get(k);
                    if (l == null) continue;
                    JSONObject t = new JSONObject();
                    t.put("at", l.at);
                    t.put("u", new JSONObject(l.urls));
                    titles.put(k, t);
                }
                o.put("links", titles);
                BrProviders.writeAtomically(file, o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                Safe.log("links write", e);
            }
        }));
    }
}
