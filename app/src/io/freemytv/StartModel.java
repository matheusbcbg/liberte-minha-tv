package io.freemytv;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Início's apps, inputs and folders (SPEC2 §3.7): loaded on one worker (LOADER), delivered to the main thread as an
 * immutable {@link Snapshot} in the visible order, and saved there too (quietly, so the home screen isn't recreated).
 * Every job catches Throwable (Safe.Job); deliveries go through Safe.post. Logs carry no keys, labels or paths.
 */
final class StartModel {
    static final class Tile {
        final String key, label, pkg;          // pkg null for explore/live/settings/folders; inputs: null
        final HomeApps.Item item;              // opened by StartHost.openItem; null for folders
        final Bitmap art;                      // 384x216 banner or icon card; null for drawn tiles
        final BitmapShader shader;             // CLAMP, for art
        final Bitmap logo;                     // SHELF apps: crisp logo on the brand gradient
        final float logoScale;
        final int brand;
        final String symbol;                   // drawn tiles: "sparkles", "live_tv", "hdmi", "gamepad"
        final int color;                       // brand or average colour
        final Bitmap blur;                     // 32x18 for BANNER; null for drawn tiles
        final boolean input, connected;
        final Tile[] folderApps;               // folders: present apps in order; null otherwise

        Tile(String key, String label, String pkg, HomeApps.Item item, Bitmap art, BitmapShader shader, Bitmap logo,
             float logoScale, int brand, String symbol, int color, Bitmap blur, boolean input, boolean connected, Tile[] folderApps) {
            this.key = key; this.label = label; this.pkg = pkg; this.item = item; this.art = art; this.shader = shader;
            this.logo = logo; this.logoScale = logoScale; this.brand = brand; this.symbol = symbol; this.color = color;
            this.blur = blur; this.input = input; this.connected = connected; this.folderApps = folderApps;
        }

        boolean folder() {
            return folderApps != null;
        }
    }

    static final class Snapshot {
        final List<Tile> tiles;                // visible order; index < 6 is the Dock
        final HomeLayout layout;               // a copy, owned by the receiver
        final int generation;

        Snapshot(List<Tile> tiles, HomeLayout layout, int generation) {
            this.tiles = tiles; this.layout = layout; this.generation = generation;
        }
    }

    interface Listener { void onModel(Snapshot s); }   // main thread

    /** Drawn tiles' colours (§2.1, the gradients' first stop), for the Top Shelf's wash. */
    static final int EXPLORE_COLOR = 0xFF3A3A42, LIVE_COLOR = 0xFF1E3A5F, INPUT_COLOR = 0xFF2C2C2E,
            SETTINGS_COLOR = 0xFF0A84FF, FOLDER_COLOR = 0xFF2C2C2E;

    private static final ThreadPoolExecutor LOADER = Safe.pool("home-start", 1);
    private static final long STALE_MS = 15 * 60_000L, CHANGED_DELAY_MS = 800;
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    // Main thread.
    private boolean started, held, loading, again, dirty = true, closed;
    private Snapshot pending;
    private long loadedAt;
    private int loadedVersion = Integer.MIN_VALUE, loadedGeneration = Integer.MIN_VALUE;
    /** Bumped by every save(): a load that began before a save is thrown away (it read the old order). */
    private int saves;
    private LauncherApps launcherApps;
    private TvInputManager tvInputs;

    // LOADER thread only.
    private volatile List<String> presentKeys;
    /** key → {art, blur, colour}: the blur copy is made again only when the art bitmap changed. */
    private final Map<String, Object[]> washes = new HashMap<>();

    private final Runnable reloadChanged = () -> {
        if (started && !closed) load(false);
    };

    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { changed(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { changed(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { changed(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { changed(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { changed(); }
    };

    private final TvInputManager.TvInputCallback inputs = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { changed(); }
        @Override public void onInputAdded(String inputId) { changed(); }
        @Override public void onInputRemoved(String inputId) { changed(); }
    };

    StartModel(Context c, Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    /** Main thread: app and input changes reload (debounced) while started. */
    void start() {
        if (started || closed) return;
        started = true;
        try {
            if (launcherApps == null) launcherApps = (LauncherApps) app.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps != null) launcherApps.registerCallback(packages, main);
        } catch (RuntimeException e) {   // some projectors ship without the launcherapps service
            Safe.log("start apps callback", e);
        }
        try {
            if (tvInputs == null) tvInputs = (TvInputManager) app.getSystemService(Context.TV_INPUT_SERVICE);
            if (tvInputs != null) tvInputs.registerCallback(inputs, main);
        } catch (RuntimeException e) {
            Safe.log("start inputs callback", e);
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(reloadChanged);
        try {
            if (launcherApps != null) launcherApps.unregisterCallback(packages);
        } catch (RuntimeException e) {
            Safe.log("start apps callback", e);
        }
        try {
            if (tvInputs != null) tvInputs.unregisterCallback(inputs);
        } catch (RuntimeException e) {
            Safe.log("start inputs callback", e);
        }
    }

    private void changed() {
        dirty = true;
        if (!started) return;
        main.removeCallbacks(reloadChanged);
        main.postDelayed(reloadChanged, CHANGED_DELAY_MS);
    }

    /** Main thread. Loads when forced or stale (callbacks fired, Prefs.version or Ui.generation moved, 15 min old). */
    void load(boolean force) {
        if (closed) return;
        boolean stale = force || dirty || Prefs.version != loadedVersion || Ui.generation != loadedGeneration
                || SystemClock.elapsedRealtime() - loadedAt > STALE_MS || loadedAt == 0;
        if (!stale) return;
        if (loading) {
            again = true;
            return;
        }
        loading = true;
        dirty = false;
        final int version = Prefs.version, uiGeneration = Ui.generation, saveMark = saves;
        LOADER.execute(new Safe.Job("home-start load", 0) {
            @Override
            void work() {
                Snapshot s = null;
                try {
                    s = build();
                } finally {
                    Snapshot out = s;
                    Safe.post(main, "home-start", () -> loaded(out, version, uiGeneration, saveMark));
                }
            }
        });
    }

    private void loaded(Snapshot s, int version, int uiGeneration, int saveMark) {
        loading = false;
        if (closed) return;
        if (s != null) {
            loadedAt = SystemClock.elapsedRealtime();
            loadedVersion = version;
            loadedGeneration = uiGeneration;
        } else {
            dirty = true;   // failed: the next load(false) tries again
        }
        boolean stale = saveMark != saves;
        if (again || stale) {
            again = false;
            load(true);
            if (stale) return;   // read before a save: its order is old, the new load brings the saved one
        }
        if (s != null) deliver(s);
    }

    private void deliver(Snapshot s) {
        if (held) {
            pending = s;
            return;
        }
        pending = null;
        listener.onModel(s);
    }

    /** Main thread: while editing or a menu is open, deliveries wait; false delivers the latest. */
    void hold(boolean held) {
        this.held = held;
        if (!held && pending != null && !closed) {
            Snapshot p = pending;
            pending = null;
            listener.onModel(p);
        }
    }

    /** Main thread: saved on LOADER, quietly (no recreate), with the Dock mirrored into the classic shelf. */
    void save(HomeLayout layout) {
        if (layout == null || closed) return;
        saves++;
        final HomeLayout copy = layout.copy();
        LOADER.execute(new Safe.Job("home-start save", 0) {
            @Override
            void work() {
                String text = copy.serialize();
                if (!text.equals(Prefs.startLayout())) Prefs.setStartLayout(text);   // nothing written when nothing changed
                List<String> present = presentKeys;
                if (present == null) return;
                List<String> dock = copy.dockPackages(copy.visible(present));
                if (!dock.equals(Prefs.shelf())) Prefs.setShelfQuietly(dock);
            }
        });
    }

    /** Main thread. COMPLETE also lets the blur copies go (they're made again on the next load). */
    void trim(int level) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return;
        LOADER.execute(new Safe.Job("home-start trim", 0) {
            @Override
            void work() {
                washes.clear();
            }
        });
    }

    /** Main thread: no more deliveries (the view is going). */
    void close() {
        stop();
        closed = true;
        pending = null;
    }

    // MARK: - Loading (LOADER)

    private Snapshot build() {
        List<HomeApps.Item> apps;
        try {
            apps = HomeApps.allApps(app);
        } catch (RuntimeException e) {
            Safe.log("home-start apps", e);
            apps = new ArrayList<>();
        }
        List<HomeApps.Item> inputItems;
        try {
            inputItems = HomeApps.allInputs(app);
        } catch (RuntimeException e) {
            Safe.log("home-start inputs", e);
            inputItems = new ArrayList<>();
        }
        Map<String, HomeApps.Item> items = new LinkedHashMap<>();
        HomeApps.Item explore = HomeApps.explore(null);
        HomeApps.Item settings = HomeApps.settings();
        settings.label = Ui.APP_SHORT;
        settings.symbol = "sparkles";
        items.put(HomeLayout.EXPLORE, explore);
        List<String> appKeys = new ArrayList<>();
        for (HomeApps.Item it : apps) {
            if (it == null || it.pkg == null || items.containsKey(it.pkg)) continue;
            items.put(it.pkg, it);
            appKeys.add(it.pkg);
        }
        List<String> named = new ArrayList<>(), unnamed = new ArrayList<>();
        boolean live = false;
        for (HomeApps.Item it : inputItems) {
            if (it == null) continue;
            if (it.kind == HomeApps.LIVE_TV) {
                if (!live) items.put(HomeLayout.LIVE, it);
                live = true;
                continue;
            }
            String key = inputKeyOf(it);
            if (key == null || items.containsKey(key)) continue;
            items.put(key, it);
            boolean generic = it.label == null || it.label.equals("HDMI " + it.number);
            (generic ? unnamed : named).add(key);
        }
        items.put(HomeLayout.SETTINGS, settings);

        List<String> shelf;
        try {
            shelf = HomeApps.shelfPackages(app);
        } catch (RuntimeException e) {
            shelf = new ArrayList<>();
        }
        // Present keys in the seed's order: new installs are placed in it (apps A-Z).
        LinkedHashSet<String> present = new LinkedHashSet<>();
        present.add(HomeLayout.EXPLORE);
        for (String p : shelf) if (items.containsKey(p)) present.add(p);
        if (live) present.add(HomeLayout.LIVE);
        present.addAll(named);
        present.addAll(appKeys);
        present.addAll(unnamed);
        present.add(HomeLayout.SETTINGS);
        List<String> presentList = new ArrayList<>(present);
        presentKeys = Collections.unmodifiableList(presentList);

        String text = Prefs.startLayout();
        long reset = Prefs.startLayoutReset();
        HomeLayout layout = HomeLayout.parse(text);
        boolean write = false;
        if (layout == null || layout.resetStamp < reset) {
            layout = HomeLayout.seed(shelf, named, appKeys, unnamed);
            layout.resetStamp = reset;
            write = !appKeys.isEmpty();
        }
        // An empty app list is a failed query, not a TV without apps: folders and slots are left alone then.
        if (!appKeys.isEmpty() && layout.reconcile(presentList)) write = true;
        String now = layout.serialize();
        if (write && !now.equals(text)) Prefs.setStartLayout(now);

        List<String> visible = layout.visible(presentList);
        List<Tile> tiles = new ArrayList<>(visible.size());
        for (String key : visible) {
            Tile t;
            if (HomeLayout.isFolder(key)) {
                HomeLayout.Folder f = layout.folders.get(key);
                if (f == null) continue;
                List<Tile> inside = new ArrayList<>();
                for (String k : layout.folderVisible(key, presentList)) {
                    Tile a = tile(k, items.get(k));
                    if (a != null) inside.add(a);
                }
                if (inside.isEmpty()) continue;
                t = new Tile(key, f.name, null, null, null, null, null, 1, 0, null, FOLDER_COLOR, null, false, false,
                        inside.toArray(new Tile[0]));
            } else {
                t = tile(key, items.get(key));
            }
            if (t != null) tiles.add(t);
        }
        washes.keySet().retainAll(items.keySet());
        return new Snapshot(Collections.unmodifiableList(tiles), layout.copy(), GENERATION.incrementAndGet());
    }

    private static String inputKeyOf(HomeApps.Item it) {
        String key = HomeLayout.inputKey(it.inputId);
        if (key != null) return key;
        return it.key == null ? null : HomeLayout.inputKey(it.key);   // an id the format can't keep: by its port ("hdmi3")
    }

    private Tile tile(String key, HomeApps.Item it) {
        if (it == null) return null;
        switch (key) {
            case HomeLayout.EXPLORE:
                return new Tile(key, it.label, null, it, null, null, null, 1, 0, "sparkles", EXPLORE_COLOR, null, false, false, null);
            case HomeLayout.LIVE:
                return new Tile(key, it.label, null, it, null, null, null, 1, 0, "live_tv", LIVE_COLOR, null, false, false, null);
            case HomeLayout.SETTINGS:
                return new Tile(key, Ui.APP_SHORT, null, it, null, null, null, 1, 0, "sparkles", SETTINGS_COLOR, null, false, false, null);
            default:
                break;
        }
        if (HomeLayout.isInput(key)) {
            String symbol = it.symbol != null ? it.symbol : "hdmi";
            return new Tile(key, it.label, null, it, null, null, null, 1, 0, symbol, INPUT_COLOR, null, true, it.connected, null);
        }
        Bitmap art = it.tile;
        Object[] w = wash(key, art);
        Bitmap blur = (Bitmap) w[1];
        int color = it.brand != 0 ? it.brand : (Integer) w[2];
        return new Tile(key, it.label == null ? key : it.label, it.pkg, it, art, it.tileShader, it.logo, it.logoScale,
                it.brand, null, color, blur, false, false, null);
    }

    /** {art, 32x18 blur copy, average colour}, cached while the art is the same bitmap. */
    private Object[] wash(String key, Bitmap art) {
        Object[] w = washes.get(key);
        if (w != null && w[0] == art) return w;
        Bitmap blur = null;
        int color = INPUT_COLOR;
        if (art != null) {
            try {
                if (!art.isRecycled()) {
                    Bitmap mid = Ui.downscale(art, 128, 72);
                    blur = Ui.downscale(mid, 32, 18);
                    if (mid != art && mid != blur) mid.recycle();
                    if (blur == art) blur = Bitmap.createBitmap(art);   // never hand out (or later recycle) the tile itself
                    color = colorOf(blur);
                }
            } catch (RuntimeException e) {   // a hardware or odd bitmap: no wash, the drawn colour
                Safe.log("home-start wash", e);
                blur = null;
            }
        }
        w = new Object[] {art, blur, color};
        washes.put(key, w);
        return w;
    }

    /** ExploreImages.colorOf's recipe (private there): an 8x5 average, saturation ×1.5 + 0.08, value 0.45..0.85. */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        if (tiny != b) tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
