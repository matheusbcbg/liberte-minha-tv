package io.freemytv;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explore's TMDB client (:explore, and Início's Top Shelf and WatchMatch in :home, one instance per process). Answers come back as ExploreModel objects on the main thread, from a
 * memory LRU (main thread only), then a disk cache (cacheDir/explore/json), then the network (api.tmdb.org, 3
 * threads by priority). A stale answer is shown at once and fetched again (stale-while-revalidate). Never logs a
 * URL, a path, a key or an exception's message: only "tmdb <label> <code>" and class names.
 */
final class Tmdb {
    static final int NOW = 0, SOON = 1, IDLE = 2;
    static final int OK = 0, NO_KEY = 1, REJECTED = 2, OFFLINE = 3, FAILED = 4, NOT_FOUND = 5;

    /** Main thread; may fire more than once (stale, then fresh or en-US filled): the last call wins. A non-null value
     *  with a status other than OK is what was saved, shown while the network fails. */
    interface Callback<T> { void done(T value, int status, boolean fresh); }

    static final class Ticket {
        private volatile boolean cancelled;
        void cancel() { cancelled = true; }
        boolean cancelled() { return cancelled; }
    }

    /** JSON requests (and TitleLinks' Watchmode calls, at IDLE). */
    static final ThreadPoolExecutor NET = Safe.pool("explore-net", 3);

    private static final String API = "https://api.tmdb.org/3";
    private static final String HOST = "api.tmdb.org";
    private static final int MAX_BODY = 2 << 20;
    private static final long DAY = 24 * 3600_000L, STALE_MAX = 7 * DAY;
    private static final long DISK_MAX = 10L << 20;
    private static final int LISTS = 160, OBJECTS = 48;

    private static final int PAGE = 0, DETAILS = 1, SEASON = 2, PERSON = 3, GENRES = 4, PROVIDERS = 5, SEARCH = 6;

    private static Tmdb instance;

    static synchronized Tmdb get(Context c) {
        if (instance == null && c != null) instance = new Tmdb(c.getApplicationContext());
        return instance;
    }

    private static volatile int rejectedAtVersion = -1, rejectedKey;

    /** A key is set and TMDB hasn't refused it since the settings were last read. */
    static boolean hasKey() {
        return !Recs.key().isEmpty() && !rejected();
    }

    static boolean rejected() {
        return rejectedAtVersion == Prefs.version && rejectedKey == Recs.key().hashCode();
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File jsonDir;
    private final Cache lists = new Cache(LISTS), objects = new Cache(OBJECTS);
    private final Map<String, Request> pending = new HashMap<>();          // main thread
    private final Map<String, Long> notFound = new ConcurrentHashMap<>();  // path → until (elapsed)
    private final AtomicInteger writes = new AtomicInteger();
    private final List<Runnable> netListeners = new ArrayList<>();        // main thread
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile boolean wasOffline;

    private Tmdb(Context app) {
        this.app = app;
        jsonDir = new File(new File(app.getCacheDir(), "explore"), "json");
        ClaroCatalog.init(app);
    }

    // MARK: - Memory (main thread only)

    private static final class Hit {
        final Object value;
        final long at, ttl;

        Hit(Object value, long at, long ttl) {
            this.value = value;
            this.at = at;
            this.ttl = ttl;
        }

        boolean fresh(long now) {
            return now - at < ttl && now >= at;
        }
    }

    private static final class Cache extends LinkedHashMap<String, Hit> {
        int cap;

        Cache(int cap) {
            super(16, 0.75f, true);
            this.cap = cap;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hit> eldest) {
            return size() > cap;
        }

        void trimTo(int n) {
            Iterator<String> i = keySet().iterator();
            while (size() > n && i.hasNext()) {
                i.next();
                i.remove();
            }
        }
    }

    private Cache cacheFor(int what) {
        return what == DETAILS || what == SEASON || what == PERSON ? objects : lists;
    }

    // MARK: - Public API

    Ticket page(String[] paths, int priority, Callback<ExploreModel.Page> cb) {
        if (paths == null || paths.length == 0) return fail(cb, FAILED);
        return load(PAGE, key(paths), paths, 0, 0, priority, cb);
    }

    ExploreModel.Page peek(String[] paths) {
        if (paths == null || paths.length == 0) return null;
        Hit e = lists.get(key(paths));
        return e != null && e.value instanceof ExploreModel.Page ? (ExploreModel.Page) e.value : null;
    }

    Ticket details(int kind, int id, int priority, Callback<ExploreModel.Details> cb) {
        String path = TmdbPaths.details(kind, id);
        return load(DETAILS, path, new String[] {path}, kind, id, priority, cb);
    }

    ExploreModel.Details peekDetails(int kind, int id) {
        Hit e = objects.get(TmdbPaths.details(kind, id));
        return e != null && e.value instanceof ExploreModel.Details ? (ExploreModel.Details) e.value : null;
    }

    Ticket season(int tv, int n, int priority, Callback<ExploreModel.SeasonDetails> cb) {
        String path = TmdbPaths.season(tv, n);
        return load(SEASON, path, new String[] {path}, tv, n, priority, cb);
    }

    Ticket person(int id, int priority, Callback<ExploreModel.Person> cb) {
        String path = TmdbPaths.person(id);
        return load(PERSON, path, new String[] {path}, id, 0, priority, cb);
    }

    Ticket genres(int kind, Callback<ExploreModel.Genre[]> cb) {
        String path = TmdbPaths.genres(kind);
        return load(GENRES, path, new String[] {path}, kind, 0, SOON, cb);
    }

    /** Brazil's services, movies' and shows' lists merged, by display_priorities.BR. */
    Ticket providers(Callback<ExploreModel.Provider[]> cb) {
        String[] paths = {TmdbPaths.providers(TmdbPaths.MOVIE), TmdbPaths.providers(TmdbPaths.TV)};
        return load(PROVIDERS, key(paths), paths, 0, 0, SOON, cb);
    }

    /** Memory only (15 minutes), never on disk. */
    Ticket search(String query, int page, Callback<ExploreModel.Page> cb) {
        String path = TmdbPaths.search(query, page);
        return load(SEARCH, path, new String[] {path}, 0, 0, NOW, cb);
    }

    /**
     * Movies (with the year when it is over 0) or shows only, page 1: WatchMatch's lookup of a Continue Watching title.
     * Memory only (15 minutes) like search, never on disk; SOON, behind what the screen is waiting for.
     */
    Ticket searchTitle(int kind, String query, int year, Callback<ExploreModel.Page> cb) {
        String path = TmdbPaths.searchTitle(kind, query, year);
        return load(SEARCH, path, new String[] {path}, 0, 0, SOON, cb);
    }

    /**
     * How many answers stay in memory: lists (pages, searches) and objects (details, seasons, people). Trims at once.
     * :home asks for less than Explore (40 and 16).
     */
    void setCaps(int lists, int objects) {
        this.lists.cap = Math.max(1, lists);
        this.objects.cap = Math.max(1, objects);
        this.lists.trimTo(this.lists.cap);
        this.objects.trimTo(this.objects.cap);
    }

    /** onBack runs on the main thread when the network comes back after being lost. */
    void addNetworkListener(Runnable onBack) {
        if (onBack == null || netListeners.contains(onBack)) return;
        netListeners.add(onBack);
        if (netCallback != null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            wasOffline = !online();
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (!wasOffline) return;
                    wasOffline = false;
                    Safe.post(main, "tmdb network", () -> {
                        for (Runnable r : new ArrayList<>(netListeners)) {
                            try {
                                r.run();
                            } catch (RuntimeException e) {
                                Safe.log("tmdb network", e);
                            }
                        }
                    });
                }

                @Override
                public void onLost(Network network) {
                    wasOffline = true;
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            netCallback = cb;
        } catch (RuntimeException e) {   // SecurityException without ACCESS_NETWORK_STATE, a TV without the service
            Safe.log("tmdb network", e);
        }
    }

    void removeNetworkListener(Runnable onBack) {
        netListeners.remove(onBack);
        if (!netListeners.isEmpty() || netCallback == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (RuntimeException e) {
            Safe.log("tmdb network", e);
        }
        netCallback = null;
    }

    /** RUNNING_LOW and worse: half of the answers kept in memory; COMPLETE: none. */
    void trim(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            lists.clear();
            objects.clear();
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            lists.trimTo(lists.size() / 2);
            objects.trimTo(objects.size() / 2);
        }
    }

    // MARK: - Requests

    private static String key(String[] paths) {
        return paths.length == 1 ? paths[0] : paths[0] + "\n" + paths[1];
    }

    private static final class Waiter {
        final Callback<Object> cb;
        final Ticket ticket;

        Waiter(Callback<Object> cb, Ticket ticket) {
            this.cb = cb;
            this.ticket = ticket;
        }
    }

    private final class Request extends Safe.Job {
        final int what, a, b;
        final String key;
        final String[] paths;
        final List<Waiter> waiters = new ArrayList<>();   // guarded by itself
        volatile boolean haveStale;

        Request(int what, String key, String[] paths, int a, int b, int priority) {
            super("tmdb " + TmdbPaths.label(paths[0]), priority);
            this.what = what;
            this.key = key;
            this.paths = paths;
            this.a = a;
            this.b = b;
        }

        boolean allCancelled() {
            synchronized (waiters) {
                for (Waiter w : waiters) if (!w.ticket.cancelled()) return false;
                return true;
            }
        }

        List<Waiter> copy() {
            synchronized (waiters) {
                return new ArrayList<>(waiters);
            }
        }

        @Override
        void work() {
            try {
                fetch(this);
            } catch (Throwable t) {
                Safe.log(tag, t);
                finish(this, null, FAILED, false, 0, true);
                if (t instanceof OutOfMemoryError) throw (OutOfMemoryError) t;   // Safe's job trims the images
            }
        }
    }

    private <T> Ticket fail(Callback<T> cb, int status) {
        Ticket t = new Ticket();
        if (cb != null) Safe.post(main, "tmdb", () -> { if (!t.cancelled()) cb.done(null, status, false); });
        return t;
    }

    @SuppressWarnings("unchecked")
    private <T> Ticket load(int what, String key, String[] paths, int a, int b, int priority, Callback<T> cb) {
        Ticket ticket = new Ticket();
        if (!hasKey()) {
            int status = Recs.key().isEmpty() ? NO_KEY : REJECTED;
            if (cb != null) Safe.post(main, "tmdb", () -> { if (!ticket.cancelled()) cb.done(null, status, false); });
            return ticket;
        }
        Cache cache = cacheFor(what);
        Hit e = cache.get(key);
        boolean stale = false;
        if (e != null) {
            boolean fresh = e.fresh(System.currentTimeMillis());
            Object value = e.value;
            if (cb != null) Safe.post(main, "tmdb", () -> { if (!ticket.cancelled()) ((Callback<Object>) cb).done(value, OK, fresh); });
            if (fresh) return ticket;
            stale = true;
        }
        Waiter w = cb == null ? null : new Waiter((Callback<Object>) cb, ticket);
        Request r = pending.get(key);
        if (r != null) {
            if (w != null) synchronized (r.waiters) { r.waiters.add(w); }
            if (priority < r.priority) {
                if (NET.getQueue().remove(r)) {
                    r.priority = priority;
                    NET.execute(r);
                } else {
                    r.priority = priority;
                }
            }
            return ticket;
        }
        r = new Request(what, key, paths, a, b, priority);
        r.haveStale = stale;
        if (w != null) r.waiters.add(w);
        pending.put(key, r);
        NET.execute(r);
        return ticket;
    }

    /** Delivers on the main thread; `last` ends the request (later loads of the key start a new one). */
    private void finish(Request r, Object value, int status, boolean fresh, long at, boolean last) {
        Safe.post(main, "tmdb", () -> {
            if (last && pending.get(r.key) == r) pending.remove(r.key);
            Cache cache = cacheFor(r.what);
            Object v = value;
            if (v != null) {
                Hit old = cache.get(r.key);
                if (old == null || old.at <= at) cache.put(r.key, new Hit(v, at, ttl(r.paths)));
            } else {
                Hit old = cache.get(r.key);
                if (old != null) v = old.value;   // the network failed: what was saved stays
            }
            for (Waiter w : r.copy()) {
                if (w.ticket.cancelled()) continue;
                try {
                    w.cb.done(v, status, fresh);
                } catch (RuntimeException e) {
                    Safe.log("tmdb deliver", e);
                }
            }
        });
    }

    /** Everyone had cancelled: nothing fetched. Whoever asked since then gets a request of their own. */
    private void dropped(Request r) {
        Safe.post(main, "tmdb", () -> {
            if (pending.get(r.key) == r) pending.remove(r.key);
            List<Waiter> left = new ArrayList<>();
            for (Waiter w : r.copy()) if (!w.ticket.cancelled()) left.add(w);
            if (left.isEmpty()) return;
            Request again = new Request(r.what, r.key, r.paths, r.a, r.b, r.priority);
            again.haveStale = r.haveStale;
            again.waiters.addAll(left);
            pending.put(r.key, again);
            NET.execute(again);
        });
    }

    private static long ttl(String[] paths) {
        long t = Long.MAX_VALUE;
        for (String p : paths) t = Math.min(t, TmdbPaths.ttlMs(p));
        return t;
    }

    // MARK: - Worker

    private static final class Got {
        JSONObject json;
        long at;
        boolean fresh;
    }

    private void fetch(Request r) throws JSONException {
        if (r.allCancelled()) {
            dropped(r);
            return;
        }
        if (!hasKey()) {
            finish(r, null, Recs.key().isEmpty() ? NO_KEY : REJECTED, false, 0, true);
            return;
        }
        boolean disk = r.what != SEARCH;
        int n = r.paths.length;
        Got[] got = new Got[n];
        boolean allFresh = true, anyData = false;
        for (int i = 0; i < n; i++) {
            got[i] = disk ? readDisk(r.paths[i]) : null;
            if (got[i] == null || !got[i].fresh) allFresh = false;
            if (got[i] != null) anyData = true;
        }
        if (allFresh) {
            Object v = parse(r, got, true);
            finish(r, v, v != null ? OK : FAILED, true, oldest(got), true);
            return;
        }
        if (anyData && !r.haveStale) {
            Object v = parse(r, got, false);   // what was saved, at once; the network's answer follows
            if (v != null) finish(r, v, OK, false, oldest(got), false);
        }
        int worst = OK;
        boolean anyFresh = false;
        for (int i = 0; i < n; i++) {
            if (got[i] != null && got[i].fresh) {
                anyFresh = true;
                continue;
            }
            int[] status = new int[1];
            JSONObject json = network(r.paths[i], r, status);
            if (json != null) {
                Got g = new Got();
                g.json = json;
                g.at = System.currentTimeMillis();
                g.fresh = true;
                got[i] = g;
                anyFresh = true;
            } else {
                worst = worse(worst, status[0]);
                allFresh = false;
            }
        }
        boolean fresh = true;
        for (Got g : got) if (g == null || !g.fresh) fresh = false;
        Object v = anyFresh || anyData ? parse(r, got, true) : null;
        int status = anyFresh ? OK : worst;
        if (v == null && status == OK) status = FAILED;
        finish(r, v, status, fresh, oldest(got), true);
    }

    private static long oldest(Got[] got) {
        long at = Long.MAX_VALUE;
        for (Got g : got) {
            if (g == null) return 0;   // a path is missing: the page is partial, so stale (the next load asks again)
            at = Math.min(at, g.at);
        }
        return at == Long.MAX_VALUE ? 0 : at;
    }

    private static int rank(int status) {
        switch (status) {
            case REJECTED: return 5;
            case NO_KEY: return 4;
            case OFFLINE: return 3;
            case FAILED: return 2;
            case NOT_FOUND: return 1;
            default: return 0;
        }
    }

    private static int worse(int a, int b) {
        return rank(b) > rank(a) ? b : a;
    }

    /** A path's JSON for a second request inside a job (the en-US fill): disk if fresh, else the network, else stale disk. */
    private JSONObject side(String path, Request r, boolean network) {
        Got g = readDisk(path);
        if (g != null && (g.fresh || !network)) return g.json;
        if (!network) return null;
        JSONObject json = network(path, r, new int[1]);
        return json != null ? json : g != null ? g.json : null;
    }

    // MARK: - Network

    private static final Object GATE = new Object();
    private static double tokens = 8;
    private static long tokensAt;
    private static volatile long pauseUntil;

    /** 20 requests a second, 8 at once, and nothing while TMDB asked us to wait (429). */
    private static void gate() throws InterruptedException {
        while (true) {
            long wait;
            synchronized (GATE) {
                long now = SystemClock.elapsedRealtime();
                if (pauseUntil > now) {
                    wait = pauseUntil - now;
                } else {
                    tokens = Math.min(8, tokens + (now - tokensAt) * 0.02);
                    tokensAt = now;
                    if (tokens >= 1) {
                        tokens -= 1;
                        return;
                    }
                    wait = (long) Math.ceil((1 - tokens) * 50);
                }
            }
            Thread.sleep(Math.max(1, Math.min(wait, 30_000)));
        }
    }

    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.getActiveNetwork() != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void reject() {
        rejectedKey = Recs.key().hashCode();
        rejectedAtVersion = Prefs.version;
    }

    /** The answer, slimmed and saved (not searches); null with status[0] set when there is none. */
    private JSONObject network(String path, Request r, int[] status) {
        String label = TmdbPaths.label(path);
        int busy = 0;
        for (int attempt = 0; ; attempt++) {
            if (rejected()) { status[0] = REJECTED; return null; }
            String key = Recs.key();
            if (key.isEmpty()) { status[0] = NO_KEY; return null; }
            Long until = notFound.get(path);
            if (until != null && until > SystemClock.elapsedRealtime()) { status[0] = NOT_FOUND; return null; }
            if (!online()) { status[0] = OFFLINE; return null; }
            int code;
            String body = null;
            String retryAfter = null;
            try {
                gate();
                HttpURLConnection c = open(path, key);
                code = c.getResponseCode();
                if (code == 200) body = read(c.getInputStream());
                else {
                    retryAfter = c.getHeaderField("Retry-After");
                    drain(c.getErrorStream());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status[0] = FAILED;
                return null;
            } catch (IOException | RuntimeException e) {
                if (attempt == 0 && r.priority == NOW && sleep(800)) continue;
                AppLog.w("tmdb " + label + " " + e.getClass().getSimpleName());
                status[0] = online() ? FAILED : OFFLINE;
                return null;
            }
            if (code == 200) {
                try {
                    JSONObject json = new JSONObject(body);
                    if (r.what == DETAILS || r.what == PERSON) slim(json);
                    if (r.what != SEARCH) writeDisk(path, json);
                    status[0] = OK;
                    return json;
                } catch (JSONException e) {
                    AppLog.w("tmdb " + label + " json");
                    status[0] = FAILED;
                    return null;
                }
            }
            AppLog.w("tmdb " + label + " " + code);
            if (code == 401) {
                reject();
                status[0] = REJECTED;
                return null;
            }
            if (code == 404) {
                notFound.put(path, SystemClock.elapsedRealtime() + DAY);
                status[0] = NOT_FOUND;
                return null;
            }
            if (code == 429) {
                long seconds = 2;
                try {
                    if (retryAfter != null) seconds = Math.max(1, Math.min(30, Long.parseLong(retryAfter.trim())));
                } catch (NumberFormatException ignored) {
                    seconds = 2;
                }
                long until429 = SystemClock.elapsedRealtime() + seconds * 1000;
                synchronized (GATE) { if (until429 > pauseUntil) pauseUntil = until429; }
                if (++busy <= 3) continue;
                status[0] = FAILED;
                return null;
            }
            if (code >= 500 && attempt == 0 && r.priority == NOW && sleep(2000)) continue;
            status[0] = FAILED;
            return null;
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static HttpURLConnection open(String path, String key) throws IOException {
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3), as Recs.request
        String url = API + path + (bearer ? "" : (path.indexOf('?') >= 0 ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        URL u = new URL(url);
        if (!"https".equals(u.getProtocol()) || !HOST.equals(u.getHost())) throw new IOException("host");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    /** The whole body (the stream closed, the connection kept for the next request), at most 2 MB. */
    private static String read(InputStream in) throws IOException {
        if (in == null) throw new IOException("empty");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("too big");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            in.close();
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try {
            byte[] buffer = new byte[4096];
            int total = 0, n;
            while ((n = in.read(buffer)) > 0 && total < 64 * 1024) total += n;
        } catch (IOException ignored) {
            // the connection is simply not reused
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // closed already
            }
        }
    }

    // MARK: - Disk (cacheDir/explore/json/<sha1(path)>.json, 10 MB)

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private Got readDisk(String path) {
        File f = new File(jsonDir, sha1(path) + ".json");
        if (!f.isFile()) return null;
        long at = f.lastModified(), now = System.currentTimeMillis();
        if (now - at > STALE_MAX) return null;
        try (InputStream in = new FileInputStream(f)) {
            Got g = new Got();
            g.json = new JSONObject(read(in));
            g.at = at;
            g.fresh = now >= at && now - at < TmdbPaths.ttlMs(path);
            return g;
        } catch (IOException | JSONException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    private void writeDisk(String path, JSONObject json) {
        try {
            if (!jsonDir.isDirectory() && !jsonDir.mkdirs()) return;
            BrProviders.writeAtomically(new File(jsonDir, sha1(path) + ".json"), json.toString().getBytes(StandardCharsets.UTF_8));
            if (writes.incrementAndGet() % 50 == 0) prune(jsonDir, DISK_MAX);
        } catch (IOException | RuntimeException e) {
            Safe.log("tmdb disk", e);
        }
    }

    /** Oldest first, until the folder holds at most max bytes; leftover .part files go too. */
    static void prune(File dir, long max) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long[] mtime = new long[files.length];
        Integer[] order = new Integer[files.length];
        long total = 0;
        for (int i = 0; i < files.length; i++) {
            mtime[i] = files[i].lastModified();
            order[i] = i;
            total += files[i].length();
        }
        Arrays.sort(order, (x, y) -> Long.compare(mtime[x], mtime[y]));
        long now = System.currentTimeMillis();
        for (Integer i : order) {
            File f = files[i];
            boolean part = f.getName().endsWith(".part");
            // A fresh .part is a download in progress (in this process or the other one): never pulled from under it.
            if (part && now - mtime[i] < 10 * 60_000L) continue;
            if (total <= max && !part) continue;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }

    // MARK: - Slimming (before the disk write)

    private static void slim(JSONObject o) throws JSONException {
        JSONObject credits = o.optJSONObject("credits");
        if (credits != null) {
            credits.put("cast", first(credits.optJSONArray("cast"), 20));
            credits.put("crew", directors(credits.optJSONArray("crew")));
        }
        JSONObject combined = o.optJSONObject("combined_credits");
        if (combined != null) combined.put("crew", directors(combined.optJSONArray("crew")));
        onlyBr(o.optJSONObject("watch/providers"));
        onlyBr(o.optJSONObject("release_dates"));
        onlyBr(o.optJSONObject("content_ratings"));
        JSONObject images = o.optJSONObject("images");
        if (images != null) {
            images.remove("posters");
            images.put("logos", preferred(images.optJSONArray("logos"), 6, true));
            images.put("backdrops", preferred(images.optJSONArray("backdrops"), 4, false));
        }
        JSONObject videos = o.optJSONObject("videos");
        if (videos != null) {
            JSONArray in = videos.optJSONArray("results"), out = new JSONArray();
            for (int i = 0; in != null && i < in.length(); i++) {
                JSONObject v = in.optJSONObject(i);
                if (v == null || !"YouTube".equals(v.optString("site"))) continue;
                String type = v.optString("type");
                if ("Trailer".equals(type) || "Teaser".equals(type)) out.put(v);
            }
            videos.put("results", out);
        }
        for (String list : new String[] {"recommendations", "similar"}) {
            JSONObject l = o.optJSONObject(list);
            if (l != null) l.put("results", first(l.optJSONArray("results"), 20));
        }
    }

    private static JSONArray first(JSONArray a, int n) {
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length() && out.length() < n; i++) out.put(a.opt(i));
        return out;
    }

    private static JSONArray directors(JSONArray crew) {
        JSONArray out = new JSONArray();
        for (int i = 0; crew != null && i < crew.length(); i++) {
            JSONObject c = crew.optJSONObject(i);
            if (c != null && "Director".equals(c.optString("job"))) out.put(c);
        }
        return out;
    }

    private static void onlyBr(JSONObject o) throws JSONException {
        if (o == null) return;
        Object results = o.opt("results");
        if (results instanceof JSONObject) {
            JSONObject br = ((JSONObject) results).optJSONObject(TmdbPaths.REGION), out = new JSONObject();
            if (br != null) out.put(TmdbPaths.REGION, br);
            o.put("results", out);
        } else if (results instanceof JSONArray) {
            JSONArray a = (JSONArray) results, out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r != null && TmdbPaths.REGION.equals(r.optString("iso_3166_1"))) out.put(r);
            }
            o.put("results", out);
        }
    }

    /** The first n, textless (backdrops) or in our languages (logos) first. */
    private static JSONArray preferred(JSONArray a, int n, boolean logos) {
        JSONArray out = new JSONArray();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; a != null && i < a.length() && out.length() < n; i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null) continue;
                String lang = str(x, "iso_639_1");
                boolean good = logos ? lang == null || "pt".equals(lang) || "en".equals(lang) : lang == null;
                if (good == (pass == 0)) out.put(x);
            }
        }
        return out;
    }

    // MARK: - Parsing (workers)

    private Object parse(Request r, Got[] got, boolean network) throws JSONException {
        JSONObject[] json = new JSONObject[got.length];
        boolean any = false;
        for (int i = 0; i < got.length; i++) {
            json[i] = got[i] == null ? null : got[i].json;
            any |= json[i] != null;
        }
        if (!any) return null;
        switch (r.what) {
            case PAGE:
            case SEARCH:
                return page(json, r.paths);
            case DETAILS:
                return json[0] == null ? null : details(json[0], r, network);
            case SEASON:
                return json[0] == null ? null : season(json[0], r.a);
            case PERSON:
                return json[0] == null ? null : person(json[0], r, network);
            case GENRES:
                return json[0] == null ? null : genres(json[0], r.a);
            case PROVIDERS:
                return providers(json);
            default:
                return null;
        }
    }

    /** A string, or null for a missing, null or empty one (org.json gives "null" for JSON's null). */
    private static String str(JSONObject o, String name) {
        Object v = o == null ? null : o.opt(name);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    private static int kindOf(String path) {
        int q = path.indexOf('?');
        String p = q < 0 ? path : path.substring(0, q);
        if (p.contains("/movie")) return ExploreModel.MOVIE;
        if (p.contains("/tv")) return ExploreModel.TV;
        return 0;
    }

    private static int mediaKind(JSONObject o, int fallback) {
        String m = o.optString("media_type", "");
        if (m.equals("movie")) return ExploreModel.MOVIE;
        if (m.equals("tv")) return ExploreModel.TV;
        if (m.equals("person")) return ExploreModel.PERSON;
        return m.isEmpty() ? fallback : 0;
    }

    private static int year(String date) {
        if (date == null || date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ExploreModel.Title title(JSONObject o, int kind) {
        ExploreModel.Title t = new ExploreModel.Title();
        t.kind = kind;
        t.id = o.optInt("id");
        if (kind == ExploreModel.PERSON) {
            t.title = str(o, "name");
            t.profile = str(o, "profile_path");
        } else {
            boolean movie = kind == ExploreModel.MOVIE;
            t.title = str(o, movie ? "title" : "name");
            t.original = str(o, movie ? "original_title" : "original_name");
            t.date = str(o, movie ? "release_date" : "first_air_date");
            t.year = year(t.date);
            t.poster = str(o, "poster_path");
            t.backdrop = str(o, "backdrop_path");
            t.overview = str(o, "overview");
            t.vote = (float) o.optDouble("vote_average", 0);
            t.votes = o.optInt("vote_count");
        }
        if (t.title == null) t.title = t.original != null ? t.original : "";
        t.popularity = (float) o.optDouble("popularity", 0);
        t.character = str(o, "character");
        JSONArray g = o.optJSONArray("genre_ids");
        if (g == null) {
            JSONArray full = o.optJSONArray("genres");
            if (full != null) {
                t.genres = new int[full.length()];
                for (int i = 0; i < full.length(); i++) {
                    JSONObject x = full.optJSONObject(i);
                    t.genres[i] = x == null ? 0 : x.optInt("id");
                }
            } else {
                t.genres = new int[0];
            }
        } else {
            t.genres = new int[g.length()];
            for (int i = 0; i < g.length(); i++) t.genres[i] = g.optInt(i);
        }
        return t;
    }

    private static List<ExploreModel.Title> titles(JSONArray a, int fallbackKind) {
        List<ExploreModel.Title> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.optBoolean("adult")) continue;
            int kind = mediaKind(o, fallbackKind);
            if (kind == 0 || o.optInt("id") <= 0) continue;
            out.add(title(o, kind));
        }
        return out;
    }

    /** One page, or two (movies, shows) taken one by one, without repeats. */
    private static ExploreModel.Page page(JSONObject[] json, String[] paths) {
        List<List<ExploreModel.Title>> parts = new ArrayList<>();
        ExploreModel.Page p = new ExploreModel.Page();
        for (int i = 0; i < json.length; i++) {
            if (json[i] == null) continue;
            parts.add(titles(json[i].optJSONArray("results"), kindOf(paths[i])));
            p.page = Math.max(p.page, json[i].optInt("page", 1));
            p.totalPages = Math.max(p.totalPages, Math.min(500, json[i].optInt("total_pages", 1)));
        }
        List<ExploreModel.Title> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int most = 0;
        for (List<ExploreModel.Title> l : parts) most = Math.max(most, l.size());
        for (int i = 0; i < most; i++) {
            for (List<ExploreModel.Title> l : parts) {
                if (i < l.size() && seen.add(l.get(i).key())) out.add(l.get(i));
            }
        }
        p.items = out.toArray(new ExploreModel.Title[0]);
        if (p.page == 0) p.page = 1;
        return p;
    }

    /** The title is only in another script (a Korean series' Hangul): TMDB had no Portuguese one. */
    private static boolean foreignScript(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED) return true;
        }
        return false;
    }

    private static String join(List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) b.append(b.length() > 0 ? ", " : "").append(n);
        return b.length() == 0 ? null : b.toString();
    }

    private ExploreModel.Details details(JSONObject o, Request r, boolean network) {
        int kind = r.a == TmdbPaths.TV ? ExploreModel.TV : ExploreModel.MOVIE;
        boolean movie = kind == ExploreModel.MOVIE;
        ExploreModel.Details d = new ExploreModel.Details();
        d.t = title(o, kind);
        d.tagline = str(o, "tagline");
        d.status = str(o, "status");

        // Portuguese missing: the English overview (and title, when the Portuguese one is in another script).
        boolean noOverview = d.t.overview == null;
        boolean foreign = d.t.title != null && d.t.title.equals(d.t.original) && foreignScript(d.t.title);
        if (noOverview || foreign) {
            JSONObject en = side(TmdbPaths.detailsEnglish(kind, r.b), r, network);
            if (en != null) {
                if (noOverview && str(en, "overview") != null) {
                    d.t.overview = str(en, "overview");
                    d.englishFilled = true;
                }
                if (d.tagline == null && noOverview) d.tagline = str(en, "tagline");
                String title = str(en, movie ? "title" : "name");
                if (foreign && title != null) {
                    d.t.title = title;
                    d.englishFilled = true;
                }
            }
        }

        JSONArray g = o.optJSONArray("genres");
        List<String> genres = new ArrayList<>();
        for (int i = 0; g != null && i < g.length(); i++) {
            String name = str(g.optJSONObject(i), "name");
            if (name != null) genres.add(name);
        }
        d.genreNames = genres.toArray(new String[0]);

        if (movie) {
            d.runtime = o.optInt("runtime");
        } else {
            JSONArray run = o.optJSONArray("episode_run_time");
            d.runtime = run != null && run.length() > 0 ? run.optInt(0) : 0;
            if (d.runtime == 0) {
                JSONObject last = o.optJSONObject("last_episode_to_air");
                if (last != null) d.runtime = last.optInt("runtime");
            }
            d.seasons = o.optInt("number_of_seasons");
            d.episodes = o.optInt("number_of_episodes");
            d.lastYear = year(str(o, "last_air_date"));
            JSONArray networks = o.optJSONArray("networks");
            d.network = networks != null && networks.length() > 0 ? str(networks.optJSONObject(0), "name") : null;
            JSONArray created = o.optJSONArray("created_by");
            List<String> names = new ArrayList<>();
            for (int i = 0; created != null && i < created.length(); i++) {
                String name = str(created.optJSONObject(i), "name");
                if (name != null && !names.contains(name)) names.add(name);
            }
            d.creators = join(names);
        }

        String lang = str(o, "original_language");
        d.originalLanguage = languageName(lang);
        JSONArray countries = o.optJSONArray("production_countries");
        List<String> cn = new ArrayList<>();
        for (int i = 0; countries != null && i < countries.length(); i++) {
            String name = str(countries.optJSONObject(i), "name");
            if (name != null) cn.add(name);
        }
        if (cn.isEmpty()) {
            JSONArray origin = o.optJSONArray("origin_country");
            for (int i = 0; origin != null && i < origin.length(); i++) if (!origin.optString(i).isEmpty()) cn.add(origin.optString(i));
        }
        d.countries = join(cn);

        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        List<ExploreModel.Credit> people = new ArrayList<>();
        for (int i = 0; cast != null && i < cast.length() && people.size() < 20; i++) {
            JSONObject c = cast.optJSONObject(i);
            if (c == null || c.optInt("id") <= 0) continue;
            ExploreModel.Credit credit = new ExploreModel.Credit();
            credit.id = c.optInt("id");
            credit.name = str(c, "name");
            credit.character = str(c, "character");
            credit.profile = str(c, "profile_path");
            if (credit.name != null) people.add(credit);
        }
        d.cast = people.toArray(new ExploreModel.Credit[0]);
        JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
        List<String> directors = new ArrayList<>();
        for (int i = 0; crew != null && i < crew.length(); i++) {
            JSONObject c = crew.optJSONObject(i);
            String name = str(c, "name");
            if (c != null && "Director".equals(c.optString("job")) && name != null && !directors.contains(name)) directors.add(name);
        }
        d.director = join(directors);

        if (movie) releaseDates(o.optJSONObject("release_dates"), d);
        else d.cert = contentRating(o.optJSONObject("content_ratings"));

        JSONObject images = o.optJSONObject("images");
        d.logo = bestLogo(images == null ? null : images.optJSONArray("logos"));
        String backdrop = bestBackdrop(images == null ? null : images.optJSONArray("backdrops"));
        d.backdrop = backdrop != null ? backdrop : d.t.backdrop;
        d.trailer = trailer(o.optJSONObject("videos"));
        d.offers = offers(o.optJSONObject("watch/providers"), d.t);

        JSONObject rec = o.optJSONObject("recommendations"), sim = o.optJSONObject("similar");
        List<ExploreModel.Title> recommended = titles(rec == null ? null : rec.optJSONArray("results"), kind);
        List<ExploreModel.Title> similar = titles(sim == null ? null : sim.optJSONArray("results"), kind);
        Set<String> seen = new HashSet<>();
        seen.add(d.t.key());
        recommended = unique(recommended, seen, 20);
        similar = unique(similar, seen, 20);
        if (recommended.isEmpty()) {
            recommended = similar;
            similar = Collections.emptyList();
        }
        d.recommended = recommended.toArray(new ExploreModel.Title[0]);
        d.similar = similar.toArray(new ExploreModel.Title[0]);

        JSONArray seasons = o.optJSONArray("seasons");
        List<ExploreModel.Season> list = new ArrayList<>();
        ExploreModel.Season specials = null;
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            JSONObject s = seasons.optJSONObject(i);
            if (s == null) continue;
            ExploreModel.Season season = new ExploreModel.Season();
            season.number = s.optInt("season_number");
            season.episodes = s.optInt("episode_count");
            season.name = str(s, "name");
            season.poster = str(s, "poster_path");
            season.airDate = str(s, "air_date");
            if (season.number == 0) specials = season;
            else list.add(season);
        }
        if (specials != null && specials.episodes > 0) list.add(specials);   // specials last
        d.seasonList = list.toArray(new ExploreModel.Season[0]);
        return d;
    }

    private static List<ExploreModel.Title> unique(List<ExploreModel.Title> in, Set<String> seen, int max) {
        List<ExploreModel.Title> out = new ArrayList<>();
        for (ExploreModel.Title t : in) {
            if (out.size() >= max) break;
            if (t.kind != ExploreModel.PERSON && seen.add(t.key())) out.add(t);
        }
        return out;
    }

    private static final Locale PT_BR = new Locale("pt", "BR");

    /** "ko" → "Coreano" (the language's name in Portuguese, as TMDB's other text). */
    private static String languageName(String code) {
        if (code == null) return null;
        String name = new Locale(code).getDisplayLanguage(PT_BR);
        if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) return code;
        return name.substring(0, 1).toUpperCase(PT_BR) + name.substring(1);
    }

    /** Brazil's age rating (ClassInd: L, 10…18): the theatrical release (3), then digital (4), then any. */
    private static void releaseDates(JSONObject o, ExploreModel.Details d) {
        JSONArray results = o == null ? null : o.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null || !TmdbPaths.REGION.equals(r.optString("iso_3166_1"))) continue;
            JSONArray dates = r.optJSONArray("release_dates");
            String cert3 = null, cert4 = null, certAny = null, date3 = null, dateFirst = null;
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject x = dates.optJSONObject(j);
                if (x == null) continue;
                String cert = str(x, "certification"), date = str(x, "release_date");
                if (cert != null) cert = cert.trim();
                if (cert != null && cert.isEmpty()) cert = null;
                if (date != null && date.length() >= 10) date = date.substring(0, 10);
                int type = x.optInt("type");
                if (type == 3 && cert3 == null) cert3 = cert;
                if (type == 4 && cert4 == null) cert4 = cert;
                if (certAny == null) certAny = cert;
                if (type == 3 && date3 == null) date3 = date;
                if (dateFirst == null) dateFirst = date;
            }
            d.cert = cert3 != null ? cert3 : cert4 != null ? cert4 : certAny;
            d.releaseBr = date3 != null ? date3 : dateFirst;
            return;
        }
    }

    private static String contentRating(JSONObject o) {
        JSONArray results = o == null ? null : o.optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r != null && TmdbPaths.REGION.equals(r.optString("iso_3166_1"))) {
                String rating = str(r, "rating");
                return rating == null ? null : rating.trim().isEmpty() ? null : rating.trim();
            }
        }
        return null;
    }

    /** Portuguese, then English, then textless; a PNG first; wide enough to be a logo (aspect over 1.2). */
    private static String bestLogo(JSONArray logos) {
        String best = null;
        double bestScore = -1;
        for (int i = 0; logos != null && i < logos.length(); i++) {
            JSONObject l = logos.optJSONObject(i);
            String path = str(l, "file_path");
            if (path == null || l.optDouble("aspect_ratio", 0) <= 1.2) continue;
            String lang = str(l, "iso_639_1");
            int langScore = "pt".equals(lang) ? 3 : "en".equals(lang) ? 2 : lang == null ? 1 : 0;
            if (langScore == 0) continue;
            double score = langScore * 100 + (path.endsWith(".png") ? 10 : 0) + l.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    /** No text written over it, the closest to 1280 wide (what Explore downloads), then the best rated. */
    private static String bestBackdrop(JSONArray list) {
        String best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            String path = str(b, "file_path");
            if (path == null || str(b, "iso_639_1") != null) continue;
            double score = -Math.abs(b.optInt("width") - 1280) * 100.0 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    private static final java.util.regex.Pattern YOUTUBE_KEY = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{6,20}$");

    /** YouTube's trailer: Trailer before Teaser, Portuguese before English, official first, newest first. */
    private static ExploreModel.Video trailer(JSONObject videos) {
        JSONArray results = videos == null ? null : videos.optJSONArray("results");
        List<JSONObject> ok = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject v = results.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site"))) continue;
            String type = v.optString("type"), key = str(v, "key");
            if (!("Trailer".equals(type) || "Teaser".equals(type)) || key == null || !YOUTUBE_KEY.matcher(key).matches()) continue;
            ok.add(v);
        }
        if (ok.isEmpty()) return null;
        Collections.sort(ok, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int c = Boolean.compare(!"Trailer".equals(a.optString("type")), !"Trailer".equals(b.optString("type")));
                if (c != 0) return c;
                c = Boolean.compare(!"pt".equals(a.optString("iso_639_1")), !"pt".equals(b.optString("iso_639_1")));
                if (c != 0) return c;
                c = Boolean.compare(!a.optBoolean("official"), !b.optBoolean("official"));
                if (c != 0) return c;
                return b.optString("published_at").compareTo(a.optString("published_at"));
            }
        });
        JSONObject v = ok.get(0);
        ExploreModel.Video video = new ExploreModel.Video();
        video.key = str(v, "key");
        video.name = str(v, "name");
        video.lang = str(v, "iso_639_1");
        return video;
    }

    private static final String[] TYPES = {"flatrate", "free", "ads", "rent", "buy"};

    /**
     * Brazil's offers: one per service and way (the best kind of the same service once: Netflix and Netflix with
     * Ads), sorted installed streamable, other streamable, rent, buy; each by kind, then TMDB's display priority.
     */
    private static ExploreModel.Offer[] offers(JSONObject providers, ExploreModel.Title t) {
        JSONObject results = providers == null ? null : providers.optJSONObject("results");
        JSONObject br = results == null ? null : results.optJSONObject(TmdbPaths.REGION);
        if (br == null) return new ExploreModel.Offer[0];
        BrProviders.Snapshot snap = BrProviders.snapshot();
        Map<String, ExploreModel.Offer> best = new LinkedHashMap<>();
        ExploreModel.Offer claro = null;   // TMDB's Claro entry (its logo), to show only as Claro's own catalogue says
        for (int type = 0; type < TYPES.length; type++) {
            JSONArray list = br.optJSONArray(TYPES[type]);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                if (p == null || p.optInt("provider_id") <= 0) continue;
                if (ProviderTable.unreliable(p.optInt("provider_id"), p.optString("provider_name", null))) {
                    if (claro == null || (claro.logo == null && p.optString("logo_path", null) != null)) {
                        claro = new ExploreModel.Offer();
                        claro.providerId = p.optInt("provider_id");
                        claro.priority = p.optInt("display_priority", 999);
                        claro.logo = str(p, "logo_path");
                    }
                    continue;
                }
                ExploreModel.Offer o = new ExploreModel.Offer();
                o.type = type;
                o.providerId = p.optInt("provider_id");
                o.priority = p.optInt("display_priority", 999);
                o.name = str(p, "provider_name");
                o.logo = str(p, "logo_path");
                o.service = ProviderTable.match(o.providerId, o.name);
                o.installed = o.service != null && snap.enabled(o.service.key);
                boolean stream = type <= ExploreModel.ADS;
                String key = stream
                        ? (o.service != null && ProviderTable.streamable(o.service, o.providerId, type) ? "s:" + o.service.key : "p:" + o.providerId)
                        : type + ":" + o.providerId;
                ExploreModel.Offer old = best.get(key);
                if (old == null || o.type < old.type || (o.type == old.type && o.priority < old.priority)) best.put(key, o);
            }
        }
        ExploreModel.Offer checked = claro == null || t == null ? null : claroOffer(claro, t, snap);
        if (checked != null) best.put("claro", checked);
        List<ExploreModel.Offer> out = new ArrayList<>(best.values());
        Collections.sort(out, (a, b) -> {
            int ga = group(a), gb = group(b);
            if (ga != gb) return ga - gb;
            if (a.type != b.type) return a.type - b.type;
            return a.priority - b.priority;
        });
        return out.toArray(new ExploreModel.Offer[0]);
    }

    /**
     * Claro tv+ as its own catalogue has the title (ClaroCatalog): included ("Claro tv+ · HBO", a subscription), a
     * rental, or nothing. Unknown (not asked yet, or the search failed): nothing.
     */
    private static ExploreModel.Offer claroOffer(ExploreModel.Offer from, ExploreModel.Title t, BrProviders.Snapshot snap) {
        ClaroCatalog.Verdict v = ClaroCatalog.verdict(t.kind, t.id, t.title, t.original, t.year);
        if (v == null || v.state == ClaroCatalog.NONE) return null;
        ProviderTable.Service s = ProviderTable.byKey("claro");
        ExploreModel.Offer o = new ExploreModel.Offer();
        o.type = v.state == ClaroCatalog.INCLUDED ? ExploreModel.FLATRATE : ExploreModel.RENT;
        o.providerId = s != null && s.ids.length > 0 ? s.ids[0] : from.providerId;
        o.priority = from.priority;
        o.logo = from.logo;
        o.service = s;
        String label = s != null ? s.label : "Claro tv+";
        o.name = v.channel != null ? label + " · " + v.channel : label;
        o.installed = s != null && snap.enabled(s.key);
        return o;
    }

    private static int group(ExploreModel.Offer o) {
        if (o.type <= ExploreModel.ADS) return o.installed && ProviderTable.streamable(o.service, o.providerId, o.type) ? 0 : 1;
        return o.type == ExploreModel.RENT ? 2 : 3;
    }

    private static ExploreModel.SeasonDetails season(JSONObject o, int tv) {
        ExploreModel.SeasonDetails s = new ExploreModel.SeasonDetails();
        s.tv = tv;
        s.number = o.optInt("season_number");
        JSONArray eps = o.optJSONArray("episodes");
        List<ExploreModel.Episode> out = new ArrayList<>();
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            ExploreModel.Episode ep = new ExploreModel.Episode();
            ep.number = e.optInt("episode_number");
            ep.runtime = e.optInt("runtime");
            ep.name = str(e, "name");
            ep.overview = str(e, "overview");
            ep.still = str(e, "still_path");
            ep.airDate = str(e, "air_date");
            out.add(ep);
        }
        s.episodes = out.toArray(new ExploreModel.Episode[0]);
        return s;
    }

    /** A person and their filmography (acting and directing), by popularity, at most 60. */
    private ExploreModel.Person person(JSONObject o, Request r, boolean network) {
        ExploreModel.Person p = new ExploreModel.Person();
        p.id = o.optInt("id", r.a);
        p.name = str(o, "name");
        p.profile = str(o, "profile_path");
        p.biography = str(o, "biography");
        p.department = str(o, "known_for_department");
        if (p.biography == null) {
            JSONObject en = side(TmdbPaths.personEnglish(r.a), r, network);
            if (en != null) p.biography = str(en, "biography");
        }
        JSONObject combined = o.optJSONObject("combined_credits");
        List<ExploreModel.Title> all = new ArrayList<>();
        if (combined != null) {
            all.addAll(titles(combined.optJSONArray("cast"), 0));
            all.addAll(titles(combined.optJSONArray("crew"), 0));
        }
        Map<String, ExploreModel.Title> byKey = new LinkedHashMap<>();
        for (ExploreModel.Title t : all) {
            if (t.kind == ExploreModel.PERSON) continue;
            ExploreModel.Title old = byKey.get(t.key());
            if (old == null) byKey.put(t.key(), t);
            else if (old.character == null && t.character != null) old.character = t.character;
        }
        List<ExploreModel.Title> list = new ArrayList<>(byKey.values());
        Collections.sort(list, (a, b) -> Float.compare(b.popularity, a.popularity));
        if (list.size() > 60) list = list.subList(0, 60);
        p.credits = list.toArray(new ExploreModel.Title[0]);
        return p;
    }

    private static ExploreModel.Genre[] genres(JSONObject o, int kind) {
        JSONArray g = o.optJSONArray("genres");
        List<ExploreModel.Genre> out = new ArrayList<>();
        for (int i = 0; g != null && i < g.length(); i++) {
            JSONObject x = g.optJSONObject(i);
            if (x == null || x.optInt("id") <= 0 || str(x, "name") == null) continue;
            ExploreModel.Genre genre = new ExploreModel.Genre();
            genre.id = x.optInt("id");
            genre.kind = kind == TmdbPaths.TV ? ExploreModel.TV : ExploreModel.MOVIE;
            genre.name = str(x, "name");
            out.add(genre);
        }
        return out.toArray(new ExploreModel.Genre[0]);
    }

    private static ExploreModel.Provider[] providers(JSONObject[] json) {
        Map<Integer, ExploreModel.Provider> byId = new HashMap<>();
        for (JSONObject o : json) {
            JSONArray results = o == null ? null : o.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject x = results.optJSONObject(i);
                if (x == null || x.optInt("provider_id") <= 0) continue;
                ExploreModel.Provider p = new ExploreModel.Provider();
                p.id = x.optInt("provider_id");
                p.name = str(x, "provider_name");
                p.logo = str(x, "logo_path");
                JSONObject priorities = x.optJSONObject("display_priorities");
                p.priority = priorities != null && priorities.has(TmdbPaths.REGION)
                        ? priorities.optInt(TmdbPaths.REGION, 999) : x.optInt("display_priority", 999);
                ExploreModel.Provider old = byId.get(p.id);
                if (p.name != null && (old == null || p.priority < old.priority)) byId.put(p.id, p);
            }
        }
        List<ExploreModel.Provider> out = new ArrayList<>(byId.values());
        Collections.sort(out, (a, b) -> a.priority != b.priority ? a.priority - b.priority : a.id - b.id);
        return out.toArray(new ExploreModel.Provider[0]);
    }
}
