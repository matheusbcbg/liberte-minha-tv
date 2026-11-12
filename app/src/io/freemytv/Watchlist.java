package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "Quero assistir": films and shows the person keeps for later, added by holding OK on a title (Explore and Início's
 * Top Shelf) or with the detail page's button. Newest first, at most MAX. Kept in filesDir/watchlist.json, which
 * :home and :explore share: each process keeps a copy in memory and reads the file again when it changed (by its
 * modification time), on Safe.IO. Only TMDB ids, titles and picture paths: nothing leaves the TV.
 */
final class Watchlist {
    private Watchlist() {}

    static final int MAX = 100;
    private static final String FILE = "watchlist.json";
    private static final int MAX_FILE = 256 << 10;

    static final class Item {
        final int kind, id, year;
        final String title, backdrop, poster;
        final long at;

        Item(int kind, int id, String title, String backdrop, String poster, int year, long at) {
            this.kind = kind;
            this.id = id;
            this.title = title == null ? "" : title;
            this.backdrop = backdrop != null && backdrop.startsWith("/") ? backdrop : null;
            this.poster = poster != null && poster.startsWith("/") ? poster : null;
            this.year = year;
            this.at = at;
        }

        String key() {
            return kind + ":" + id;
        }
    }

    interface Listener {
        /** Main thread: the list changed (here or in the other process). */
        void onWatchlist(List<Item> items);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Main thread. */
    private static List<Item> items = Collections.emptyList();
    /** Main thread: writes not finished yet (a read meanwhile would bring back the list before them). */
    private static int writing;
    /** The file's stamp (modified time ^ length) that memory reflects; -1 never read. */
    private static volatile long seen = -1;
    private static final Map<Listener, Boolean> LISTENERS = new WeakHashMap<>();

    /** Main thread: the copy in memory (possibly not read yet: empty). */
    static List<Item> cached() {
        return items;
    }

    /** Main thread. */
    static boolean contains(int kind, int id) {
        for (Item it : items) if (it.kind == kind && it.id == id) return true;
        return false;
    }

    /** Main thread: l hears every change while it's alive (held weakly: a page that goes needn't remove itself). */
    static void listen(Listener l) {
        if (l != null) LISTENERS.put(l, Boolean.TRUE);
    }

    /** Reads the file again if it changed since (off the main thread); done gets the list on the main thread. */
    static void load(Context c, final Listener done) {
        final File file = new File(c.getApplicationContext().getFilesDir(), FILE);
        Safe.IO.execute(Safe.wrap("watchlist read", () -> {
            final List<Item> read = stamp(file) == seen ? null : read(file);
            Safe.post(MAIN, "watchlist", () -> {
                if (read != null && writing == 0) {
                    items = Collections.unmodifiableList(read);
                    notifyAll(done);
                } else if (done != null) {
                    done.onWatchlist(items);
                }
            });
        }));
    }

    private static long stamp(File file) {
        return file.isFile() ? file.lastModified() ^ file.length() : 0;
    }

    /**
     * Main thread: adds the title (first) or takes it off; true when it's on the list now. The file is written on
     * Safe.IO; every listener hears it at once.
     */
    static boolean toggle(Context c, int kind, int id, String title, String backdrop, String poster, int year) {
        if (id <= 0 || (kind != ExploreModel.MOVIE && kind != ExploreModel.TV)) return false;
        List<Item> next = new ArrayList<>(items.size() + 1);
        boolean had = false;
        for (Item it : items) {
            if (it.kind == kind && it.id == id) had = true;
            else next.add(it);
        }
        if (!had) next.add(0, new Item(kind, id, title, backdrop, poster, year, System.currentTimeMillis()));
        while (next.size() > MAX) next.remove(next.size() - 1);
        items = Collections.unmodifiableList(next);
        write(c, items);
        notifyAll(null);
        return !had;
    }

    private static void notifyAll(Listener also) {
        List<Listener> ls = new ArrayList<>(LISTENERS.keySet());
        if (also != null && !ls.contains(also)) ls.add(also);
        for (Listener l : ls) {
            try {
                l.onWatchlist(items);
            } catch (RuntimeException e) {
                Safe.log("watchlist listener", e);
            }
        }
    }

    private static List<Item> read(File file) {
        List<Item> out = new ArrayList<>();
        long stamp = stamp(file);
        if (!file.isFile() || file.length() > MAX_FILE) {
            seen = stamp;
            return out;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream((int) Math.max(256, file.length()));
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            JSONObject o = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
            JSONArray a = o.optJSONArray("items");
            for (int i = 0; a != null && i < a.length() && out.size() < MAX; i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                int kind = e.optInt("t"), id = e.optInt("i");
                if (id <= 0 || (kind != ExploreModel.MOVIE && kind != ExploreModel.TV)) continue;
                out.add(new Item(kind, id, e.optString("n", ""), e.optString("b", null), e.optString("p", null),
                        e.optInt("y"), e.optLong("a")));
            }
            seen = stamp;
        } catch (Exception e) {   // IOException, JSONException: an unreadable file reads as an empty list
            Safe.log("watchlist read", e);
        }
        return out;
    }

    private static void write(Context c, final List<Item> list) {
        final File file = new File(c.getApplicationContext().getFilesDir(), FILE);
        writing++;
        Safe.IO.execute(Safe.wrap("watchlist write", () -> {
            try {
                JSONArray a = new JSONArray();
                for (Item it : list) {
                    JSONObject o = new JSONObject().put("t", it.kind).put("i", it.id).put("n", it.title).put("a", it.at);
                    if (it.backdrop != null) o.put("b", it.backdrop);
                    if (it.poster != null) o.put("p", it.poster);
                    if (it.year > 0) o.put("y", it.year);
                    a.put(o);
                }
                byte[] data = new JSONObject().put("v", 1).put("items", a).toString().getBytes(StandardCharsets.UTF_8);
                BrProviders.writeAtomically(file, data);
                seen = stamp(file);
            } catch (Exception e) {
                Safe.log("watchlist write", e);
            } finally {
                Safe.post(MAIN, "watchlist written", () -> writing--);
            }
        }));
    }

    /** A WIDE title card for Explore's row and Início's Top Shelf: "Série · 2013 · Quero assistir". */
    static Card card(Item it) {
        Card c = new Card();
        c.type = Card.TITLE;
        c.kind = it.kind;
        c.id = it.id;
        c.title = it.title;
        c.image = it.backdrop != null ? it.backdrop : it.poster;
        c.imageSpec = ExploreImages.WIDE;
        String kind = it.kind == ExploreModel.TV ? Ui.tr("Series") : Ui.tr("Movie");
        c.sub = it.year > 0 ? kind + " · " + it.year : kind;
        c.ref = it;
        return c;
    }
}
