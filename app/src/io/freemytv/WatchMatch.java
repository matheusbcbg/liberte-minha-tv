package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Continue Watching matched to TMDB (SPEC2 §3.5, the Apple TV way): each Watch Next item's title is searched once
 * (/search/movie with its year, or /search/tv), accepted only by TitleMatch's strict rule, and its TMDB backdrop and
 * title logo kept for 30 days (a miss for 3). One per process, each with its own file (files/watch-match-home.json,
 * -explore.json). At most 4 searches and 1 details call per item, never again while cached. Titles go to TMDB only as search queries,
 * and nothing about them is ever logged.
 */
final class WatchMatch {
    static final class Match {
        final int kind, id;
        final String backdrop, logo;

        Match(int kind, int id, String backdrop, String logo) {
            this.kind = kind;
            this.id = id;
            this.backdrop = backdrop;
            this.logo = logo;
        }
    }

    private static final long DAY = 24 * 3600_000L, HIT_MS = 30 * DAY, MISS_MS = 3 * DAY;
    private static final int MAX_ENTRIES = 200, PER_FILL = 8, MAX_FILE = 256 << 10;
    private static final ThreadPoolExecutor POOL = Safe.pool("watch-match", 1);

    private static final class Entry {
        final Match match;   // null: TMDB has no title that passes (a miss)
        final long at;

        Entry(Match match, long at) {
            this.match = match;
            this.at = at;
        }

        boolean fresh(long now) {
            return now >= at && now - at < (match != null ? HIT_MS : MISS_MS);
        }
    }

    private static WatchMatch instance;

    /** One per process (main thread). */
    static synchronized WatchMatch get(Context c) {
        if (instance == null) instance = new WatchMatch(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    /** Set by the first read (POOL), before anything on the main thread uses them: this is :home, and its file. */
    private volatile boolean home;
    private volatile File file;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread.
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean loaded, loading, busy, unsaved;
    /** Bumped by every fill: a lookup's late answers and the watchdog below act only for their own fill. */
    private int fillSeq;
    private List<WatchNext.Item> waitingItems;
    private Runnable waitingChanged;

    private WatchMatch(Context app) {
        this.app = app;
    }

    /** Main thread, memory only (never in draw): the item's match, or null (unmatched, a miss, or not looked up yet). */
    Match peek(WatchNext.Item it) {
        if (it == null || it.title == null) return null;
        Entry e = entries.get(TitleMatch.key(it.pkg, it.title, it.movie()));
        return e == null || e.match == null || !e.fresh(System.currentTimeMillis()) ? null : e.match;
    }

    /**
     * Main thread, after every Continue Watching delivery: looks up at most 8 items without an entry (one fill at a time;
     * a call during one runs when it ends, with the latest list). changed runs on the main thread once per fill that
     * matched something new.
     */
    void fill(List<WatchNext.Item> items, Runnable changed) {
        if (items == null || items.isEmpty()) return;
        if (!loaded || busy) {
            waitingItems = new ArrayList<>(items);
            waitingChanged = changed;
            if (!loaded) readFile();
            return;
        }
        if (!enabledNow()) return;
        final List<WatchNext.Item> todo = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        long now = System.currentTimeMillis();
        for (WatchNext.Item it : items) {
            if (todo.size() >= PER_FILL) break;
            if (it == null || it.title == null || TitleMatch.plain(it.title).isEmpty()) continue;
            String key = TitleMatch.key(it.pkg, it.title, it.movie());
            Entry e = entries.get(key);
            if ((e != null && e.fresh(now)) || !keys.add(key)) continue;
            todo.add(it);
        }
        if (todo.isEmpty()) return;
        busy = true;
        if (!home) {
            lookUp(todo, changed);
            return;
        }
        // :home: no TMDB for an hour after a caught fault (a small file read: on the worker).
        POOL.execute(new Safe.Job("watch-match gate", 0) {
            @Override
            void work() {
                boolean blocked = true;
                try {
                    blocked = Watchdog.networkBlocked(app);
                } finally {
                    boolean b = blocked;
                    Safe.post(main, "watch-match", () -> {
                        if (b) finish(false, changed);
                        else lookUp(todo, changed);
                    });
                }
            }
        });
    }

    /** RUNNING_LOW and worse: expired entries leave memory (the rest is ~40 KB). */
    void trim(int level) {
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Entry> i = entries.values().iterator(); i.hasNext(); ) if (!i.next().fresh(now)) i.remove();
    }

    // MARK: - Looking up (main thread, through Tmdb's callbacks)

    private boolean enabledNow() {
        if (!Tmdb.hasKey()) return false;
        return !home || Prefs.topShelfPreviews();
    }

    private void lookUp(List<WatchNext.Item> todo, Runnable changed) {
        Tmdb tmdb = Tmdb.get(app);
        if (tmdb == null || !enabledNow()) {
            finish(false, changed);
            return;
        }
        final int[] left = {todo.size()};
        final boolean[] found = {false};
        final int seq = ++fillSeq;
        // A request that never answers must not hold every later fill back.
        main.postDelayed(() -> {
            try {
                if (busy && fillSeq == seq && left[0] > 0) {
                    left[0] = 0;
                    finish(found[0], changed);
                }
            } catch (RuntimeException e) {
                Safe.log("watch-match timeout", e);
            }
        }, 90_000);
        for (WatchNext.Item it : todo) {
            final String key = TitleMatch.key(it.pkg, it.title, it.movie());
            final boolean movie = it.movie();
            final int kind = movie ? ExploreModel.MOVIE : ExploreModel.TV;
            final int wantYear = movie ? it.year : 0;
            final String title = it.title;
            final boolean[] done = {false};
            Runnable one = () -> {
                if (done[0]) return;
                done[0] = true;
                if (left[0] > 0 && --left[0] == 0 && fillSeq == seq) finish(found[0], changed);
            };
            // Apps don't all say the same: Max can list a show without its season (it reads as a film here) or put the
            // episode in the title. So the item's own kind first, then the other, for the title and its cleaned forms.
            List<int[]> tries = new ArrayList<>();
            List<String> queries = TitleMatch.queries(title);
            int other = kind == ExploreModel.MOVIE ? ExploreModel.TV : ExploreModel.MOVIE;
            for (int q = 0; q < queries.size() && tries.size() < MAX_TRIES; q++) {
                tries.add(new int[] {kind, q});
                if (tries.size() < MAX_TRIES) tries.add(new int[] {other, q});
            }
            attempt(tmdb, key, queries, tries, 0, wantYear, done, found, one);
        }
    }

    /** At most this many searches per item (then 1 details call), only for an item not cached yet. */
    private static final int MAX_TRIES = 4;

    private void attempt(Tmdb tmdb, String key, List<String> queries, List<int[]> tries, int n, int wantYear,
                         boolean[] done, boolean[] found, Runnable one) {
        if (done[0]) return;
        if (n >= tries.size()) {
            miss(key);
            one.run();
            return;
        }
        final int kind = tries.get(n)[0];
        final String query = queries.get(tries.get(n)[1]);
        final int year = kind == ExploreModel.MOVIE ? wantYear : 0;
        final boolean[] searched = {false};
        tmdb.searchTitle(kind, query, year, (page, status, fresh) -> {
            if (done[0] || searched[0]) return;   // a stale answer, then the fresh one: the first decides
            searched[0] = true;
            if (page == null || page.items == null) {
                if (status == Tmdb.NOT_FOUND) attempt(tmdb, key, queries, tries, n + 1, wantYear, done, found, one);
                else one.run();   // offline, refused or failed: nothing recorded, asked again next time
                return;
            }
            ExploreModel.Title hit = null;
            for (int i = 0; i < page.items.length; i++) {
                ExploreModel.Title t = page.items[i];
                if (t == null || t.kind != kind || t.id <= 0) continue;
                if (TitleMatch.accept(query, year, t.title, t.original, t.year, t.popularity, i == 0)) {
                    hit = t;
                    break;
                }
            }
            if (hit == null) {
                attempt(tmdb, key, queries, tries, n + 1, wantYear, done, found, one);
                return;
            }
            final ExploreModel.Title match = hit;
            tmdb.details(kind, match.id, Tmdb.SOON, (d, st, fr) -> {
                if (d == null) {
                    if (!done[0]) one.run();   // asked again next time (the search is in memory for 15 min)
                    return;
                }
                String backdrop = d.backdrop != null ? d.backdrop : match.backdrop;
                Entry e = new Entry(new Match(kind, match.id, path(backdrop), path(d.logo)), System.currentTimeMillis());
                entries.put(key, e);
                unsaved = true;
                if (!done[0]) {
                    found[0] = true;
                    one.run();
                }
            });
        });
    }

    private void miss(String key) {
        entries.put(key, new Entry(null, System.currentTimeMillis()));
        unsaved = true;
    }

    private static String path(String p) {
        return p != null && p.startsWith("/") ? p : null;
    }

    private void finish(boolean found, Runnable changed) {
        busy = false;
        if (unsaved) {   // misses too: they are kept 3 days, so a restart doesn't search them again
            unsaved = false;
            write();
        }
        if (found && changed != null) Safe.post(main, "watch-match changed", changed);
        if (waitingItems != null) {
            List<WatchNext.Item> items = waitingItems;
            Runnable cb = waitingChanged;
            waitingItems = null;
            waitingChanged = null;
            fill(items, cb);
        }
    }

    // MARK: - The file (POOL)

    private void readFile() {
        if (loading) return;
        loading = true;
        POOL.execute(new Safe.Job("watch-match read", 0) {
            @Override
            void work() {
                Map<String, Entry> read = new HashMap<>();
                try {
                    home = inHome();
                    file = new File(app.getFilesDir(), "watch-match-" + (home ? "home" : "explore") + ".json");
                    read = read();
                } finally {
                    Map<String, Entry> out = read;
                    Safe.post(main, "watch-match", () -> {
                        loading = false;
                        loaded = true;
                        for (Map.Entry<String, Entry> e : out.entrySet()) {
                            if (!entries.containsKey(e.getKey())) entries.put(e.getKey(), e.getValue());
                        }
                        if (waitingItems != null && !busy) {
                            List<WatchNext.Item> items = waitingItems;
                            Runnable cb = waitingChanged;
                            waitingItems = null;
                            waitingChanged = null;
                            boolean any = false;
                            for (WatchNext.Item it : items) any |= peek(it) != null;
                            // Matches read from the file show now; the rest are looked up.
                            if (any && cb != null) Safe.post(main, "watch-match changed", cb);
                            fill(items, cb);
                        }
                    });
                }
            }
        });
    }

    private Map<String, Entry> read() {
        Map<String, Entry> out = new HashMap<>();
        File file = this.file;
        if (file == null || !file.isFile() || file.length() > MAX_FILE) return out;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.max(256, file.length()));
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONObject o = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
            if (o.optInt("v") != 3) return out;   // older: misses made before the other kind, cleaned titles and "&" were tried
            JSONArray a = o.optJSONArray("e");
            long now = System.currentTimeMillis();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                String key = e.optString("k", "");
                if (key.isEmpty()) continue;
                int id = e.optInt("i"), kind = e.optInt("t");
                Match m = id > 0 && (kind == ExploreModel.MOVIE || kind == ExploreModel.TV)
                        ? new Match(kind, id, path(e.optString("b", null)), path(e.optString("l", null))) : null;
                Entry entry = new Entry(m, e.optLong("a"));
                if (entry.fresh(now)) out.put(key, entry);
            }
        } catch (Exception e) {   // IOException, JSONException: a broken file is simply forgotten
            Safe.log("watch-match read", e);
        }
        return out;
    }

    /** Main thread: a copy of the newest 200 fresh entries, written on POOL (atomically, a .part per process). */
    private void write() {
        long now = System.currentTimeMillis();
        final List<Map.Entry<String, Entry>> list = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) if (e.getValue().fresh(now)) list.add(e);
        Collections.sort(list, (x, y) -> Long.compare(y.getValue().at, x.getValue().at));
        while (list.size() > MAX_ENTRIES) {
            entries.remove(list.get(list.size() - 1).getKey());
            list.remove(list.size() - 1);
        }
        final List<Object[]> rows = new ArrayList<>(list.size());
        for (Map.Entry<String, Entry> e : list) rows.add(new Object[] {e.getKey(), e.getValue()});
        POOL.execute(new Safe.Job("watch-match write", 1) {
            @Override
            void work() throws Exception {
                JSONArray a = new JSONArray();
                for (Object[] r : rows) {
                    Entry e = (Entry) r[1];
                    JSONObject o = new JSONObject().put("k", r[0]).put("a", e.at);
                    if (e.match != null) {
                        o.put("t", e.match.kind).put("i", e.match.id);
                        if (e.match.backdrop != null) o.put("b", e.match.backdrop);
                        if (e.match.logo != null) o.put("l", e.match.logo);
                    }
                    a.put(o);
                }
                byte[] data = new JSONObject().put("v", 3).put("e", a).toString().getBytes(StandardCharsets.UTF_8);
                File f = file;
                if (f == null) return;
                try {
                    BrProviders.writeAtomically(f, data);
                } catch (IOException e) {
                    Safe.log("watch-match write", e);
                }
            }
        });
    }

    /** This process is :home (Início, the settings) rather than :explore. */
    private static boolean inHome() {
        String name = "";
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[128];
            int n = Math.max(0, in.read(b)), end = 0;
            while (end < n && b[end] != 0) end++;
            name = new String(b, 0, end, StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException ignored) {
            // unknown: treated as :explore's (its own file, no Watchdog gate)
        }
        return name.endsWith(":home");
    }
}
