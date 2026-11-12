package io.freemytv;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * What Início's Top Shelf shows for the focused top-row tile (SPEC2 §3.4): Explorar's Up Next cards, a streaming
 * app's carousel (its picks, then its TMDB catalogue: the same discover paths as Explore's service page, so the disk
 * cache is shared with :explore), or a banner (no network). Main thread, except the merge (one worker). Never touches
 * Views or ExploreImages: the views ask for the pictures. Recs is only read on the main thread (setPicks gets a copy).
 */
final class TopShelfFeed {
    static final int UP_NEXT = 1, CAROUSEL = 2, BANNER = 3;

    static final class Item {
        final ExploreModel.Title title;        // kind, id, title, backdrop, overview
        ExploreModel.Details details;          // null until details() delivers
        final String link, pkg, service;       // Pick.link or null; the app's package; ProviderTable label

        Item(ExploreModel.Title title, String link, String pkg, String service) {
            this.title = title; this.link = link; this.pkg = pkg; this.service = service;
        }
    }

    interface Listener {
        void onShelf(String key, Item[] items);                         // CAROUSEL, ≤ 6; empty → BANNER
        void onDetails(String key, int index, ExploreModel.Details d);
        void onUpNext(Card[] cards, int n);                             // built with BrowsePage.watchCard/titleCard + MORE
    }

    private static final int MAX_ITEMS = 6, MAX_PICKS = 3, MIN_ITEMS = 2, LRU = 12;
    private static final long FRESH_MS = 30 * 60_000L, NET_CHECK_MS = 60_000L, RETRY_MS = 60_000L;
    private static final ThreadPoolExecutor MERGE = Safe.pool("home-shelf", 1);
    private static final Item[] NONE = new Item[0];

    /** One tile's carousel: loading, then its items (empty: BANNER). */
    private static final class Shelf {
        final String key, pkg;
        final ProviderTable.Service service;
        Item[] items;
        boolean done, failed;   // failed: TMDB didn't answer (offline): tried again after RETRY_MS
        long at;
        Tmdb.Ticket page;
        final Tmdb.Ticket[] details = new Tmdb.Ticket[MAX_ITEMS];
        int seq;   // the newest merge wins

        Shelf(String key, String pkg, ProviderTable.Service service) {
            this.key = key;
            this.pkg = pkg;
            this.service = service;
        }
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Shelf> cache = new LinkedHashMap<String, Shelf>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Shelf> eldest) {
            if (size() <= LRU) return false;
            cancelAll(eldest.getValue());
            return true;
        }
    };
    private List<Recs.Pick> picks = new ArrayList<>();
    private List<WatchNext.Item> watch = new ArrayList<>();
    private boolean watchAllowed;
    private String current;
    private boolean closed;
    private Card[] upCards = new Card[0];
    private int upCount, upReal;
    /** Watchdog.networkBlocked as last read on the worker (static: TopShelfView reads it too, never the file on main). */
    private static volatile boolean netBlocked = true;   // until the first check says otherwise
    private long netCheckedAt = -NET_CHECK_MS;
    private boolean netChecking;
    /** Quero assistir changed (here or, read again on resume, in Explore): Up Next again. Held here: listen() is weak. */
    private final Watchlist.Listener listListener = items -> {
        if (!closed) upNextChanged();
    };
    /** Up Next's Quero assistir cards, after Continue Watching. */
    private static final int MAX_LIST = 10;

    TopShelfFeed(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
        Tmdb tmdb = Tmdb.get(app);
        if (tmdb != null) tmdb.setCaps(40, 16);   // :home keeps far fewer answers than Explore
        checkNetwork();
        Watchlist.listen(listListener);
        Watchlist.load(app, null);
    }

    // MARK: - Types

    int typeOf(StartModel.Tile t) {
        if (t == null || t.key == null) return BANNER;
        if (HomeLayout.EXPLORE.equals(t.key)) {
            String mode = Prefs.topContent();
            boolean any = Prefs.showsWatching(mode) || Prefs.TOP_PICKS.equals(mode);
            return any && upReal > 0 ? UP_NEXT : BANNER;
        }
        if (t.pkg == null || !carouselAllowed()) return BANNER;
        ProviderTable.Service s = serviceOf(t.pkg);
        if (s == null) return BANNER;
        Shelf e = cache.get(t.key);
        if (e != null && e.done && e.items.length < MIN_ITEMS && !expired(e, SystemClock.elapsedRealtime())) return BANNER;
        return CAROUSEL;
    }

    private boolean carouselAllowed() {
        checkNetwork();
        return Prefs.topShelfPreviews() && Tmdb.hasKey() && !netBlocked;
    }

    /** The streaming service whose app this is (YouTube's isn't: its titles aren't TMDB's). */
    static ProviderTable.Service serviceOf(String pkg) {
        if (pkg == null) return null;
        for (ProviderTable.Service s : ProviderTable.TABLE) {
            if ("youtube".equals(s.key) || ProviderTable.UNRELIABLE.contains(s.key)) continue;
            for (String p : s.packages) if (p.equals(pkg)) return s;
        }
        return null;
    }

    /** Main thread: no TMDB in :home right now (a recent caught fault), as of the last check (blocked until the first). */
    static boolean networkBlocked() {
        return netBlocked;
    }

    /** Watchdog.networkBlocked reads a small file: on the merge worker, at most once a minute. */
    private void checkNetwork() {
        long now = SystemClock.elapsedRealtime();
        if (netChecking || now - netCheckedAt < NET_CHECK_MS) return;
        netChecking = true;
        netCheckedAt = now;
        MERGE.execute(new Safe.Job("home-shelf gate", 0) {
            @Override
            void work() {
                boolean blocked = true;
                try {
                    blocked = Watchdog.networkBlocked(app);
                } finally {
                    boolean b = blocked;
                    Safe.post(main, "home-shelf", () -> {
                        netChecking = false;
                        netBlocked = b;
                    });
                }
            }
        });
    }

    // MARK: - The focused tile

    /** Main thread, after the dwell: the tile's content (Up Next cards, or its carousel when it has one). */
    void want(StartModel.Tile t) {
        if (closed || t == null || t.key == null) return;
        if (current != null && !current.equals(t.key)) cancel();
        current = t.key;
        if (HomeLayout.EXPLORE.equals(t.key)) {
            rebuildUpNext();
            listener.onUpNext(upCards, upCount);
            return;
        }
        if (typeOf(t) != CAROUSEL) return;
        Shelf e = load(t, Tmdb.SOON);
        if (e != null && e.done) listener.onShelf(e.key, e.items);
    }

    /** The entry for a carousel tile, (re)loading it when missing, old or cancelled. */
    private Shelf load(StartModel.Tile t, int priority) {
        ProviderTable.Service s = serviceOf(t.pkg);
        if (s == null) return null;
        Shelf e = cache.get(t.key);
        long now = SystemClock.elapsedRealtime();
        boolean stale = e != null && e.done && expired(e, now);
        boolean stuck = e != null && !e.done && (e.page == null || e.page.cancelled());
        if (e != null && !stale && !stuck) return e;
        if (e == null) {
            e = new Shelf(t.key, t.pkg, s);
            cache.put(t.key, e);
        }
        final Shelf entry = e;
        final List<Recs.Pick> mine = picksFor(t.pkg);
        Tmdb tmdb = Tmdb.get(app);
        if (tmdb == null) {
            merge(entry, mine, null);
            return entry;
        }
        String[] paths = Catalog.providerHero(s.ids).paths(1);
        entry.page = tmdb.page(paths, priority, (page, status, fresh) -> {
            if (closed) return;
            if (page != null || !entry.done) merge(entry, mine, page);
        });
        return entry;
    }

    /** Done long enough ago to load again: 30 min, or 1 min after TMDB didn't answer (so it comes back with the network). */
    private static boolean expired(Shelf e, long now) {
        return now - e.at > (e.failed ? RETRY_MS : FRESH_MS);
    }

    private List<Recs.Pick> picksFor(String pkg) {
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : picks) {
            if (p == null || p.id <= 0 || p.photo != null || p.backdrop == null) continue;   // Plex (id < 0) and photos
            if (pkg.equals(p.pkg) || pkg.equals(p.servicePkg)) out.add(p);
        }
        return out;
    }

    /** The picks read here (main: Recs' objects stay on it), merged on MERGE through ShelfMerge, delivered on main. */
    private void merge(Shelf e, List<Recs.Pick> mine, ExploreModel.Page page) {
        final int mergeSeq = ++e.seq;
        final boolean failed = page == null;
        final ExploreModel.Title[] catalogue = page == null || page.items == null ? new ExploreModel.Title[0] : page.items;
        final Map<ShelfMerge.Ref, ExploreModel.Title> titles = new IdentityHashMap<>();
        final List<ShelfMerge.Ref> pickRefs = new ArrayList<>();
        for (Recs.Pick p : mine) {
            ExploreModel.Title t = titleOf(p);
            ShelfMerge.Ref r = new ShelfMerge.Ref(t.kind, t.id, t.backdrop, p.link);
            titles.put(r, t);
            pickRefs.add(r);
        }
        MERGE.execute(new Safe.Job("home-shelf merge", 1) {
            @Override
            void work() {
                Item[] items = NONE;
                try {
                    List<ShelfMerge.Ref> catRefs = new ArrayList<>();
                    for (ExploreModel.Title t : catalogue) {
                        if (t == null || (t.kind != ExploreModel.MOVIE && t.kind != ExploreModel.TV)) continue;
                        ShelfMerge.Ref r = new ShelfMerge.Ref(t.kind, t.id, t.backdrop, null);
                        titles.put(r, t);
                        catRefs.add(r);
                    }
                    List<ShelfMerge.Ref> refs = ShelfMerge.merge(pickRefs, catRefs, MAX_PICKS, MAX_ITEMS, MIN_ITEMS);
                    List<Item> out = new ArrayList<>(refs.size());
                    for (ShelfMerge.Ref r : refs) {
                        ExploreModel.Title t = titles.get(r);
                        if (t != null) out.add(new Item(t, r.link, e.pkg, e.service.label));
                    }
                    items = out.toArray(new Item[0]);
                } finally {
                    Item[] result = items;
                    Safe.post(main, "home-shelf", () -> merged(e, mergeSeq, result, failed));
                }
            }
        });
    }

    private void merged(Shelf e, int seq, Item[] items, boolean failed) {
        if (closed || seq != e.seq) return;
        Item[] old = e.items;
        // Details already fetched for the same titles carry over to a fresh merge.
        if (old != null) {
            for (Item it : items) {
                for (Item was : old) {
                    if (was.details != null && was.title.kind == it.title.kind && was.title.id == it.title.id) it.details = was.details;
                }
            }
        }
        e.items = items;
        e.done = true;
        e.failed = failed;
        e.at = SystemClock.elapsedRealtime();
        if (e.key.equals(current) && cache.get(e.key) == e) listener.onShelf(e.key, items);
    }

    /** A pick as the carousel's title (its overview and logo come with details()). */
    private static ExploreModel.Title titleOf(Recs.Pick p) {
        ExploreModel.Title t = new ExploreModel.Title();
        t.kind = "tv".equals(p.kind) ? ExploreModel.TV : ExploreModel.MOVIE;
        t.id = p.id;
        t.title = p.title == null ? "" : p.title;
        t.backdrop = p.backdrop;
        t.poster = p.poster;
        t.genres = new int[0];
        return t;
    }

    /** Main thread: details for the shown item and the next (logo, runtime, certification, offers). */
    void details(String key, int index) {
        if (closed || key == null) return;
        Shelf e = cache.get(key);
        if (e == null || !e.done || e.items.length == 0) return;
        Tmdb tmdb = Tmdb.get(app);
        if (tmdb == null) return;
        int n = e.items.length;
        for (int k = 0; k < 2 && k < n; k++) {
            final int i = ((index + k) % n + n) % n;
            final Item it = e.items[i];
            if (it.details != null) {
                if (k == 0) listener.onDetails(key, i, it.details);
                continue;
            }
            ExploreModel.Details known = tmdb.peekDetails(it.title.kind, it.title.id);
            if (known != null) {
                it.details = known;
                listener.onDetails(key, i, known);
                continue;
            }
            if (i < e.details.length && e.details[i] != null && !e.details[i].cancelled()) continue;   // asked already
            final Item[] items = e.items;
            Tmdb.Ticket ticket = tmdb.details(it.title.kind, it.title.id, Tmdb.SOON, (d, status, fresh) -> {
                if (closed || d == null) return;
                it.details = d;
                if (e.items == items && cache.get(key) == e) listener.onDetails(key, i, d);
            });
            if (i < e.details.length) e.details[i] = ticket;
        }
    }

    /** Main thread, after onRecs and the first resume: the JSON of the Dock's carousel tiles (no images). */
    void prefetch(List<StartModel.Tile> dock) {
        if (closed || dock == null || !carouselAllowed()) return;
        for (StartModel.Tile t : dock) {
            if (t == null || t.pkg == null || serviceOf(t.pkg) == null) continue;
            if (!cache.containsKey(t.key)) load(t, Tmdb.IDLE);
        }
    }

    // MARK: - Inputs from the view

    /** Main thread: a copy of recs.now(null). The carousels are merged again (the TMDB pages come from memory). */
    void setPicks(List<Recs.Pick> picks) {
        if (closed) return;
        List<Recs.Pick> next = picks == null ? new ArrayList<Recs.Pick>() : new ArrayList<>(picks);
        boolean same = next.size() == this.picks.size();
        for (int i = 0; same && i < next.size(); i++) same = next.get(i) == this.picks.get(i);
        this.picks = next;
        if (same) return;
        for (Iterator<Shelf> i = cache.values().iterator(); i.hasNext(); ) {
            Shelf e = i.next();
            if (e.done) e.at = Long.MIN_VALUE / 2;   // stale: the next want() merges again with the new picks
        }
        upNextChanged();
    }

    /** Main thread, from WatchFeed: Continue Watching for Up Next; its TMDB match fills in behind (WatchMatch). */
    void watching(List<WatchNext.Item> items, boolean allowed) {
        if (closed) return;
        watch = items == null ? new ArrayList<WatchNext.Item>() : new ArrayList<>(items);
        watchAllowed = allowed;
        upNextChanged();
        Watchlist.load(app, null);   // Explore may have changed it (another process): listListener hears it
        if (allowed && !watch.isEmpty() && Prefs.showsWatching(Prefs.topContent())) {
            WatchMatch.get(app).fill(watch, () -> {
                if (!closed) upNextChanged();
            });
        }
    }

    private void upNextChanged() {
        rebuildUpNext();
        listener.onUpNext(upCards, upCount);
    }

    /** Continue Watching (minus the hidden), then up to 6 picks, then "More in Explore" when there's anything. */
    private void rebuildUpNext() {
        String mode = Prefs.topContent();
        List<Card> out = new ArrayList<>();
        if (Prefs.showsWatching(mode) && watchAllowed) {
            Set<String> hidden = Prefs.watchHidden();
            for (WatchNext.Item it : watch) {
                if (out.size() >= WatchNext.MAX) break;
                if (it == null || hidden.contains(WatchNext.hideKey(it))) continue;
                out.add(BrowsePage.watchCard(app, it));
            }
        }
        if ((Prefs.showsWatching(mode) || Prefs.TOP_PICKS.equals(mode)) && Tmdb.hasKey()) {
            Set<String> shown = new HashSet<>();
            for (Card c : out) if (c.id > 0) shown.add(c.kind + ":" + c.id);
            int n = 0;
            for (Watchlist.Item it : Watchlist.cached()) {
                if (n >= MAX_LIST) break;
                if (!shown.add(it.key()) || (it.backdrop == null && it.poster == null)) continue;
                Card c = Watchlist.card(it);
                c.sub = Ui.tr("Want to Watch") + " · " + c.sub;
                out.add(c);
                n++;
            }
        }
        if ((Prefs.TOP_BOTH.equals(mode) || Prefs.TOP_PICKS.equals(mode)) && Tmdb.hasKey()) {
            int n = 0;
            for (Recs.Pick p : picks) {
                if (n >= Recs.MAX_CARDS) break;
                if (p == null || p.id <= 0 || p.photo != null || p.backdrop == null || !p.backdrop.startsWith("/")) continue;
                out.add(pickCard(p));
                n++;
            }
        }
        upReal = out.size();
        if (upReal > 0) {
            Card more = new Card();
            more.type = Card.MORE;
            more.title = Ui.tr("More in Explore");
            out.add(more);
        }
        upCards = out.toArray(new Card[0]);
        upCount = upCards.length;
    }

    /** A pick as a WIDE title card; ref is the Recs.Pick (its app, link and backdrop for the menu and the background). */
    private static Card pickCard(Recs.Pick p) {
        Card c = new Card();
        c.type = Card.TITLE;
        c.kind = "tv".equals(p.kind) ? ExploreModel.TV : ExploreModel.MOVIE;
        c.id = p.id;
        c.ref = p;
        c.title = p.title == null ? "" : p.title;
        c.image = p.backdrop;
        c.imageSpec = ExploreImages.WIDE;
        if (p.meta != null && !p.meta.isEmpty()) {
            c.sub = p.meta;
        } else {
            String kind = c.kind == ExploreModel.TV ? Ui.tr("Series") : Ui.tr("Movie");
            c.sub = p.app != null && !p.app.isEmpty() ? kind + " · " + p.app : kind;
        }
        return c;
    }

    // MARK: - Life

    /** Main thread: the focused tile's loads in flight stop (its content stays cached). */
    void cancel() {
        if (current == null) return;
        Shelf e = cache.get(current);
        current = null;
        if (e == null) return;
        if (!e.done && e.page != null) e.page.cancel();
        for (int i = 0; i < e.details.length; i++) {
            if (e.details[i] != null) e.details[i].cancel();
            e.details[i] = null;
        }
    }

    /** RUNNING_LOW and worse: only the current tile's carousel stays; COMPLETE: none. */
    void trim(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            for (Shelf e : cache.values()) cancelAll(e);
            cache.clear();
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            for (Iterator<Map.Entry<String, Shelf>> i = cache.entrySet().iterator(); i.hasNext(); ) {
                Map.Entry<String, Shelf> m = i.next();
                if (m.getKey().equals(current)) continue;
                cancelAll(m.getValue());
                i.remove();
            }
        }
    }

    void close() {
        closed = true;
        for (Shelf e : cache.values()) cancelAll(e);
        cache.clear();
        current = null;
        picks = Collections.emptyList();
        watch = Collections.emptyList();
    }

    private static void cancelAll(Shelf e) {
        if (e.page != null) e.page.cancel();
        for (Tmdb.Ticket t : e.details) if (t != null) t.cancel();
    }
}
