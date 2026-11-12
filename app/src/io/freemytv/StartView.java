package io.freemytv;

import android.app.AlertDialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Início, the tvOS-style home screen ("Moderno", SPEC2): the Top Shelf (TopShelfView's Views at indexes 0..5), the
 * grid of every app, input and folder (its first row is the Dock), the status row, and over them the folder sheet, the
 * lifted tile, the hint chip, the context menu and Recent Apps. Built like ExploreView: plain child Views at fixed
 * places, one frame callback posted only while something moves, hardware layers only on what moves as a whole.
 * Every entry point catches RuntimeException and hands it to fault(), which swaps to the classic home screen in this
 * process (HomeActivity.startFault) instead of crashing.
 */
final class StartView extends ViewGroup implements Choreographer.FrameCallback, StartHost {
    private static final int Z_STATUS = 0, Z_SHELF = 1, Z_GRID = 2, Z_FOLDER = 3;
    private static final int G_STATUS = 1, G_SHELF = 2, G_GRID = 3, G_FOLDER = 4, G_MENU = 5, G_RECENTS = 6;
    private static final long HOLD_MS = 500, MOVE_MS = 70, DWELL_MS = 400, IDLE_MS = 10_000, HINT_DWELL_MS = 2500,
            HINT_SHOW_MS = 4000, BURST_MS = 1500, TICK_MS = 15_000, SHELF_WAIT_MS = 1500, LONG_TAP_MS = 500;
    private static final float DOCK_SCROLL = 574, FOCUSED_DROP = 320;
    private static final int EDIT_HINTS = 3;
    private static final ThreadPoolExecutor NOTICES = Safe.pool("home-notices", 1);

    // Menu actions.
    private static final int A_EDIT = 1, A_TOP = 2, A_OFF_TOP = 3, A_MOVE_TO = 4, A_NEW_FOLDER = 5, A_TO_FOLDER = 6,
            A_OUT = 7, A_INFO = 8, A_HIDE = 9, A_RENAME = 10, A_UNGROUP = 11;

    private final HomeActivity activity;
    private final float k;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Click click;
    private final ExploreImages images;
    private final WatchArt watchArt;
    private final CardPainter cards;
    private final TopShelfView topShelf;
    private final StatusView status;
    /** Deep in the grid, the rows going up fade under the clock instead of running into it. */
    private final TopScrim scrim;
    private final AppTiles.TilePainter tiles;
    private final AppTiles.Grid grid;
    private final AppTiles.GridPage page;
    private final FolderSheet folder;
    private final AppTiles.LiftView lift;
    private final StartMenu.StartHint hint;
    private final StartMenu menu;
    private final RecentLayer recentLayer;
    private RecentApps recents;
    private final Spoken spoken;
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final SharedPreferences ui;
    private final StartModel model;
    private final TopShelfFeed feed;
    private final Paint wall = new Paint();

    private boolean running, framePending, broken, destroyed, faultPosted;
    private long lastFrame, lastMoveAt, resumedAt;

    // Focus.
    private int zone = Z_GRID, statusFrom = Z_GRID, shelfState = -1;
    private final Spring scroll = new Spring(0.42f, 1f, 0), expand = new Spring(0.38f, 0.90f, 0);

    // The model.
    private HomeLayout layout;
    private StartModel.Snapshot pendingModel;
    private String pendingFocusKey;
    private boolean modelHeld, reloadAfterEdit, allAppsPending, prefetched;
    /** The layout last saved, and whether a snapshot held back from before that save may still come (and be old). */
    private String savedText;
    private boolean awaitFresh;

    // The Top Shelf's content.
    private String wantedKey, shownKey;
    private int shelfType;
    private Card[] upNext;
    private int upNextCount;
    private List<WatchNext.Item> watching;
    private boolean watchAllowed;
    private int picksHour = -1;

    // OK and Back press bookkeeping (HomeView.onKey).
    private boolean okDown, okHeld, holdArmed, backDown;

    // Edit mode.
    private boolean editing, editInFolder;
    private List<String> origin;
    private int liftIndex = -1;
    private final Spring liftAmount = new Spring(0.30f, 0.72f, 0), liftX = new Spring(0.28f, 0.86f, 0),
            liftY = new Spring(0.28f, 0.86f, 0);

    // Drawing and the pointer.
    private float appliedScroll = Float.NaN, appliedAlpha = -1;
    private boolean overPhoto, overPhotoSet;
    private float downX, downY;
    private boolean longTapFired;
    private int hovered = -1;
    private final RectF tmp = new RectF(), tmp2 = new RectF();
    private final Rect tmpRect = new Rect();

    private final Runnable holdRun = guard("hold", this::hold);
    private final Runnable dwell = guard("dwell", this::dwell);
    private final Runnable idleRun = guard("idle", this::idle);
    private final Runnable editHintRun = guard("edit hint", this::editHint);
    private final Runnable shelfWait = guard("shelf wait", this::shelfWait);
    private final Runnable detailsLater = guard("details", this::detailsLater);
    private final Runnable longTap = guard("long tap", this::longTap);
    private final Runnable watchObserved = guard("watching", this::loadWatching);
    private final Runnable networkBack = guard("network", this::networkBack);
    private final Runnable tick = guard("tick", this::tick);
    private final Runnable shelfQuiet = guard("shelf quiet", this::shelfQuiet);
    private int detailsStep;

    StartView(HomeActivity a) {
        super(a);
        activity = a;
        k = ExploreView.scaleOf(a);
        setClipChildren(false);
        setWillNotDraw(false);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(Ui.APP_NAME);
        click = new Click(a);
        images = ExploreImages.acquire(a);
        try {
            cards = new CardPainter(a, k);
            watchArt = new WatchArt(a, guard("watch art", this::watchArtArrived));
            watchArt.setScale(k * 424f / WatchArt.W);
            // The Top Shelf first: its Views go at indexes 0..5, under everything else.
            topShelf = new TopShelfView(this, this, images, watchArt, cards);
            tiles = new AppTiles.TilePainter(a, k);
            grid = new AppTiles.Grid(AppTiles.DOCK_TOP, k);
            page = new AppTiles.GridPage(a, grid, tiles, this, k);
            addView(page, new ExplorePage.Pos(0, 0, 1920, 1080));
            scrim = new TopScrim(a);
            addView(scrim, new ExplorePage.Pos(0, 0, 1920, 240));
            data = HomeData.get(a);
            status = new StatusView(a, this, data);
            addView(status, new ExplorePage.Pos(900, 40, 980, 96));
            folder = new FolderSheet(a, this, tiles);
            addView(folder, new ExplorePage.Pos(0, 0, 1920, 1080));
            lift = new AppTiles.LiftView(a, tiles, this);
            addView(lift, new ExplorePage.Pos(0, 0, 380, 260));
            hint = new StartMenu.StartHint(a);
            addView(hint, new ExplorePage.Pos(0, 940, 1920, 140));
            menu = new StartMenu(a, this);
            addView(menu, new ExplorePage.Pos(0, 0, 1920, 1080));
            recentLayer = new RecentLayer(a);
            addView(recentLayer, new ExplorePage.Pos(0, 0, 1920, 1080));
            spoken = new Spoken(this, new Source());
            recs = Recs.get(a);
            recs.setLight(true);
            playing = new NowPlaying.Watch(a, guard("now playing", this::onPlaying));
            ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
            try {
                Tmdb.get(a).setCaps(40, 16);
            } catch (RuntimeException e) {
                Safe.log("tmdb caps", e);
            }
            feed = new TopShelfFeed(a, new FeedListener());
            model = new StartModel(a, s -> {
                try {
                    onModel(s);
                } catch (RuntimeException e) {
                    fault("model", e);
                }
            });
            model.start();
            model.load(true);
        } catch (RuntimeException | Error e) {
            // HomeActivity shows the classic home screen instead; what was already taken is given back.
            try {
                images.release();
                click.release();
            } catch (RuntimeException ignored) {
            }
            throw e;
        }
    }

    // MARK: - Helpers

    private Runnable guard(String tag, Runnable r) {
        return () -> {
            if (destroyed || broken) return;
            try {
                r.run();
            } catch (RuntimeException e) {
                fault(tag, e);
            }
        };
    }

    private static int id(int group, int i) {
        return group * 1000 + i + 1;
    }

    private AppTiles.Grid editGrid() {
        return editInFolder ? folder.grid : grid;
    }

    /** The focused tile's rectangle on screen as drawn when focused (1.15×, lifted 6): menus and launches start there. */
    private void focusRect(AppTiles.Grid g, int i, boolean inFolder, RectF out) {
        if (inFolder) folder.slotRect(i, out);
        else {
            float x = grid.slotX(i) * k, y = grid.slotY(i) * k + page.getTranslationY();
            out.set(x, y, x + AppTiles.W * k, y + AppTiles.H * k);
        }
        float cx = out.centerX(), cy = out.centerY() - AppTiles.LIFT_Y * k;
        float w = out.width() * (1 + AppTiles.GROW) / 2, h = out.height() * (1 + AppTiles.GROW) / 2;
        out.set(cx - w, cy - h, cx + w, cy + h);
    }

    private void updateHold() {
        boolean held = editing || menu.isOpen();
        if (held != modelHeld) {
            modelHeld = held;
            model.hold(held);
            handler.removeCallbacks(shelfQuiet);
            if (held) shelfQuiet();
        }
        if (!held && pendingModel != null) {
            StartModel.Snapshot s = pendingModel;
            pendingModel = null;
            apply(s);
        }
    }

    /** No carousel auto-advance while a menu or an edit is open (§3.4-4): it counts as a key every 4 s. */
    private void shelfQuiet() {
        if (!running || !(editing || menu.isOpen())) return;
        topShelf.keyed();
        handler.removeCallbacks(shelfQuiet);
        handler.postDelayed(shelfQuiet, 4000);
    }

    // MARK: - Lifecycle (HomeActivity)

    void resume() {
        if (destroyed || broken || running) return;
        running = true;
        lastFrame = 0;
        long now = SystemClock.uptimeMillis();
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;   // one Home press sent as a burst (Xiaomi)
        resumedAt = now;
        images.setListener(imagesListener);
        images.setBudget(24L << 20);
        data.addListener(dataListener);
        recs.addListener(recsListener);
        try {
            data.refreshIfStale();
            recs.refreshIfStale();
            Photos.importInbox(getContext(), null);
            ImmichPhotos.syncIfDue(getContext(), () -> {});
            WatchFeed.observe(activity, watchObserved);
            Tmdb.get(activity).addNetworkListener(networkBack);
        } catch (RuntimeException e) {
            Safe.log("start resume", e);
        }
        loadWatching();
        playing.start();
        status.resume();
        topShelf.resume();
        if (!again) model.load(false);
        updatePicks();
        if (!prefetched && grid.size() > 0) prefetch();
        checkNotices();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        restartIdle();
        if (zone == Z_STATUS) status.setFocused(true);
        spoken.readAgain();
        wake();
    }

    void pause() {
        if (!running) return;
        if (editing) drop();   // moves are never lost
        menu.close(false);
        updateHold();
        if (recents != null) recents.closeNow();
        running = false;
        if (framePending) Choreographer.getInstance().removeFrameCallback(this);
        framePending = false;
        for (Runnable r : new Runnable[] {tick, holdRun, dwell, idleRun, editHintRun, shelfWait, detailsLater, longTap, shelfQuiet}) {
            handler.removeCallbacks(r);
        }
        releasePress();
        okDown = okHeld = holdArmed = backDown = false;
        images.clearListener(imagesListener);
        images.viewportMoved();
        try {
            WatchFeed.stop(activity);
            Tmdb.get(activity).removeNetworkListener(networkBack);
        } catch (RuntimeException e) {
            Safe.log("start pause", e);
        }
        playing.stop();
        data.removeListener(dataListener);
        recs.removeListener(recsListener);
        status.pause();
        topShelf.pause();
    }

    void stop() {
        if (destroyed) return;
        images.setBudget(6L << 20);
        images.trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);   // keeps the current hero
        topShelf.pause();
    }

    void destroy() {
        if (destroyed) return;
        try {
            pause();
        } catch (RuntimeException e) {
            Safe.log("start destroy", e);
        }
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        try {
            model.close();
            feed.close();
        } catch (RuntimeException e) {
            Safe.log("start destroy", e);
        }
        watchArt.close();
        images.release();
        click.release();
    }

    void trim(int level) {
        if (destroyed) return;
        images.trim(level);
        try {
            Tmdb.get(activity).trim(level);
            feed.trim(level);
            WatchMatch.get(activity).trim(level);
            model.trim(level);
        } catch (RuntimeException e) {
            Safe.log("start trim", e);
        }
    }

    /** Home: back to Explorar at the top, the shelf on its content at once; an edit is dropped and saved. */
    void reset(boolean animate) {
        if (destroyed || broken) return;
        wake();
        if (editing) drop();
        menu.close(!animate ? false : true);
        if (folder.isOpen()) folder.close(animate);
        if (recents != null) recents.close();
        updateHold();
        releasePress();
        okDown = okHeld = holdArmed = backDown = false;
        handler.removeCallbacks(dwell);
        zone = Z_GRID;
        statusFrom = Z_GRID;
        status.setFocused(false);
        int was = grid.focus;
        grid.focus = grid.size() > 0 ? 0 : -1;
        grid.active = true;
        grid.retarget();
        if (was != grid.focus) grid.arrive(grid.focus, 0, 0);
        setShelfState(TopShelfView.BROWSE);
        expand.target = 0;
        scroll.target = 0;
        if (!animate) {
            expand.snap(0);
            scroll.snap(0);
        }
        StartModel.Tile t = grid.tile(0);
        if (t != null) followTile(t, animate);
        spoken.focusMoved();
    }

    /** The remote's All Apps key: the top, then row 1's first tile. */
    void showAllApps() {
        if (destroyed || broken) return;
        reset(false);
        if (grid.size() > AppTiles.COLUMNS) focusGrid(AppTiles.COLUMNS, 0, 0);
        else allAppsPending = grid.size() == 0;
    }

    void showRecents() {
        if (destroyed || broken) return;
        openRecents();
    }

    // MARK: - Frames

    @Override public void wake() {
        if (!running || framePending || broken || destroyed) return;
        framePending = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePending = false;
        if (!running || broken || destroyed) return;
        try {
            float dt = lastFrame == 0 ? 1 / 60f : Math.max(1 / 60f, Math.min(0.05f, (frameTimeNanos - lastFrame) / 1e9f));
            lastFrame = frameTimeNanos;
            images.frame();
            boolean busy = scroll.step(dt);
            busy |= expand.step(dt);
            busy |= page.step(dt);
            busy |= stepLift(dt);
            busy |= topShelf.step(dt);
            busy |= status.step(dt);
            busy |= folder.step(dt);
            busy |= menu.step(dt);
            busy |= hint.step(dt);
            if (recents != null) {
                boolean r = recents.step(dt);
                if (r) recentLayer.invalidate();
                boolean show = recents.visible();
                if (show != (recentLayer.getVisibility() == VISIBLE)) recentLayer.setVisibility(show ? VISIBLE : INVISIBLE);
                busy |= r;
            }
            applyMotion();
            if (busy || editing) wake();
            else lastFrame = 0;
        } catch (RuntimeException e) {
            fault("frame", e);
        }
    }

    /** The page scroll, the BROWSE ↔ FOCUSED move and the Top Shelf's fade, as View properties (no repaint). */
    private void applyMotion() {
        float s = scroll.value, v = Ui.clamp(expand.value);
        float ty = -s * k, alpha = 1;
        if (shelfType == TopShelfFeed.CAROUSEL) {
            ty += FOCUSED_DROP * k * v;
            alpha = 1 - v;
        } else if (shelfType == TopShelfFeed.UP_NEXT) {
            alpha = 1 - 0.5f * v;
        }
        page.setTranslationY(ty);
        if (alpha != appliedAlpha) {
            appliedAlpha = alpha;
            int vis = alpha <= 0.002f ? INVISIBLE : VISIBLE;
            if (page.getVisibility() != vis) page.setVisibility(vis);
            page.setAlpha(alpha);
            int layer = alpha < 0.999f && alpha > 0.002f ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (page.getLayerType() != layer) page.setLayerType(layer, null);
        }
        page.cull(s);
        float sa = folder.isOpen() ? 0 : Ui.clamp((s - DOCK_SCROLL - 20) / 80f);
        if (scrim.getAlpha() != sa) {
            scrim.setAlpha(sa);
            int vis = sa <= 0.002f ? INVISIBLE : VISIBLE;
            if (scrim.getVisibility() != vis) scrim.setVisibility(vis);
        }
        float shelfScroll = folder.isOpen() ? Math.max(s, DOCK_SCROLL) : s;
        if (shelfScroll != appliedScroll) {
            appliedScroll = shelfScroll;
            topShelf.setScroll(shelfScroll);
        }
        boolean over = s < 200 && shelfType != 0 && !folder.isOpen();
        if (!overPhotoSet || over != overPhoto) {
            overPhotoSet = true;
            overPhoto = over;
            status.setOverPhoto(over);
        }
    }

    /** The page's colour at the top, fading out by 240: drawn once per size, moved only by its alpha. */
    private static final class TopScrim extends View {
        private final Paint paint = new Paint(Paint.DITHER_FLAG);

        TopScrim(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
            setAlpha(0);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[] {CardPainter.BG, CardPainter.BG, CardPainter.BG & 0x00FFFFFF}, new float[] {0, 0.55f, 1},
                    android.graphics.Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }

    private boolean stepLift(float dt) {
        if (lift.getVisibility() != VISIBLE) return false;
        boolean busy = liftAmount.step(dt);
        busy |= liftX.step(dt);
        busy |= liftY.step(dt);
        float s = (editInFolder ? folder.scroll() : scroll.value) * k;
        lift.setTranslationX(liftX.value - lift.getWidth() / 2f);
        lift.setTranslationY(liftY.value - s - lift.getHeight() / 2f);
        if (editing) {
            float t = (SystemClock.uptimeMillis() % 260) / 260f;
            lift.setRotation((float) (2 * Math.sin(2 * Math.PI * t)) * Ui.clamp(liftAmount.value));
        } else {
            lift.setRotation(0);
        }
        if (lift.lift != liftAmount.value) {
            lift.lift = liftAmount.value;
            lift.invalidate();
        }
        // The lifted cell is also its grid's: Grid.step moves its springs; here only the repaint.
        AppTiles.Cell cell = lift.cell;
        if (cell != null && !(cell.focus.settled() && cell.press.settled() && cell.tilt.settled())) {
            busy = true;
            lift.invalidate();
        }
        if (!editing && !busy && liftAmount.value < 0.02f) {
            lift.setVisibility(INVISIBLE);
            lift.cell = null;
            return false;
        }
        return busy || editing;
    }

    /** Where slot i's centre is in its grid's content (px, before the scroll). */
    private float slotCenterX(AppTiles.Grid g, int i) {
        return (g.slotX(i) + AppTiles.W / 2) * k;
    }

    private float slotCenterY(AppTiles.Grid g, int i) {
        return (g.slotY(i) + AppTiles.H / 2 - AppTiles.LIFT_Y) * k;
    }

    // MARK: - Faults (§3.9-1)

    @Override public void fault(String tag, RuntimeException e) {
        AppLog.w(tag + ": " + (e == null ? "null" : e.getClass().getSimpleName()));
        broken = true;
        if (faultPosted || destroyed) return;
        faultPosted = true;
        handler.post(() -> {
            try {
                activity.startFault(tag);
            } catch (RuntimeException ex) {
                Safe.log("start fault", ex);
            }
        });
    }

    // MARK: - The model

    private void onModel(StartModel.Snapshot s) {
        if (destroyed || s == null) return;
        if (editing || menu.isOpen()) {
            pendingModel = s;   // applied after the drop or once the menu closes
            return;
        }
        apply(s);
    }

    private void apply(StartModel.Snapshot s) {
        if (awaitFresh) {
            // Built before our last save (held while editing or in a menu): its order is old; the next load is ours.
            awaitFresh = false;
            if (s.layout != null && savedText != null && !savedText.equals(s.layout.serialize())) {
                model.load(true);
                return;
            }
        }
        layout = s.layout;
        boolean first = grid.size() == 0;
        String focusKey = pendingFocusKey != null ? pendingFocusKey : grid.key(grid.focus);
        pendingFocusKey = null;
        String shownTileKey = shownKey;
        StartModel.Tile shownBefore = shownTileKey == null ? null : grid.tile(grid.indexOf(shownTileKey));
        grid.setTiles(s.tiles == null ? Collections.<StartModel.Tile>emptyList() : s.tiles, tiles, !first);
        page.sync();
        if (s.tiles != null) tiles.keepLogos(s.tiles);
        int i = grid.indexOf(focusKey);
        if (i < 0) i = grid.size() == 0 ? -1 : Math.max(0, Math.min(grid.size() - 1, first ? 0 : grid.focus));
        grid.focus = i;
        grid.active = zone == Z_GRID;
        grid.retarget();
        if (first) grid.snapAll();
        if (folder.isOpen()) {
            StartModel.Tile f = folder.folder();
            StartModel.Tile now = f == null ? null : grid.tile(grid.indexOf(f.key));
            if (now == null || !folder.refresh(now)) closeFolder(false);
        }
        if (allAppsPending && grid.size() > AppTiles.COLUMNS) {
            allAppsPending = false;
            if (zone == Z_GRID) i = AppTiles.COLUMNS;
            grid.focus = i;
            grid.retarget();
        }
        retargetScroll();
        if (first) scroll.snap(scroll.target);
        // The shelf: the Dock tile's content right away on the first model; again when that tile itself changed.
        StartModel.Tile t = grid.tile(dockFocus());
        if (t != null) {
            StartModel.Tile shownNow = shownTileKey == null ? null : grid.tile(grid.indexOf(shownTileKey));
            if (first || shownKey == null || !t.key.equals(shownKey) && zone == Z_GRID && AppTiles.row(grid.focus) == 0
                    || shownNow != shownBefore && t.key.equals(shownKey) && shelfType == TopShelfFeed.BANNER) {
                showShelfFor(t, !first);
            }
        }
        if (zone == Z_GRID) setShelfState(AppTiles.row(Math.max(0, grid.focus)) == 0 ? TopShelfView.BROWSE : TopShelfView.AWAY);
        if (!prefetched && running) prefetch();
        spoken.focusMoved();
        wake();
    }

    /** The Dock index whose content the shelf shows (the focused one; Explorar's when focus is elsewhere). */
    private int dockFocus() {
        int i = editing && !editInFolder ? liftIndex : grid.focus;
        return i >= 0 && i < AppTiles.COLUMNS ? i : 0;
    }

    private void prefetch() {
        int n = Math.min(AppTiles.COLUMNS, grid.size());
        if (n == 0) return;
        List<StartModel.Tile> dock = new ArrayList<>(n);
        for (int i = 0; i < n; i++) dock.add(grid.tile(i));
        prefetched = !TopShelfFeed.networkBlocked();   // not yet known at the first model: asked again later
        try {
            feed.prefetch(dock);
        } catch (RuntimeException e) {
            Safe.log("prefetch", e);
        }
    }

    /** A new order of the main grid or of the open folder: into the layout, saved quietly (StartModel mirrors "shelf"). */
    private void saveOrder(boolean inFolder, List<String> keys) {
        if (layout == null) return;
        if (!inFolder) {
            layout.setVisible(keys);
        } else {
            StartModel.Tile f = folder.folder();
            HomeLayout.Folder hf = f == null ? null : layout.folders.get(f.key);
            if (hf == null) return;
            // Present apps take the new order; absent ones keep their places.
            java.util.HashSet<String> present = new java.util.HashSet<>(keys);
            List<String> out = new ArrayList<>(hf.apps.size());
            int next = 0;
            for (String key : hf.apps) {
                if (present.contains(key)) {
                    if (next < keys.size()) out.add(keys.get(next++));
                } else {
                    out.add(key);
                }
            }
            while (next < keys.size()) out.add(keys.get(next++));
            hf.apps.clear();
            hf.apps.addAll(out);
        }
        saveLayout();
    }

    /** Saves the layout quietly; a snapshot held back meanwhile is checked against it before it's applied. */
    private void saveLayout() {
        savedText = layout.serialize();
        if (modelHeld || pendingModel != null) awaitFresh = true;
        model.save(layout);
    }

    /** After a change that makes new tiles (folders, hidden apps): save, then a fresh snapshot. */
    private void saveAndReload() {
        if (layout == null) return;
        saveLayout();
        if (editing) reloadAfterEdit = true;
        else model.load(true);
    }

    // MARK: - Top Shelf content (§3.4)

    private void setShelfState(int state) {
        if (state == shelfState) return;
        shelfState = state;
        topShelf.setState(state);
        expand.target = state == TopShelfView.FOCUSED ? 1 : 0;
        wake();
    }

    private void showShelfFor(StartModel.Tile t, boolean animate) {
        handler.removeCallbacks(shelfWait);
        handler.removeCallbacks(detailsLater);
        if (t == null) return;
        int type;
        try {
            type = feed.typeOf(t);
        } catch (RuntimeException e) {
            Safe.log("shelf type", e);
            type = TopShelfFeed.BANNER;
        }
        if (wantedKey != null && !wantedKey.equals(t.key)) feed.cancel();
        wantedKey = t.key;
        if (type == TopShelfFeed.UP_NEXT && upNext != null && upNextCount > 0) {
            topShelf.showUpNext(upNext, upNextCount, animate);
            shownKey = t.key;
            shelfType = TopShelfFeed.UP_NEXT;
        } else if (type == TopShelfFeed.CAROUSEL) {
            feed.want(t);   // onShelf answers (from its cache at once, or later); the old content stays meanwhile
            handler.postDelayed(shelfWait, SHELF_WAIT_MS);
        } else {
            banner(t, animate);
        }
        a11yChanged();
        wake();
    }

    private void banner(StartModel.Tile t, boolean animate) {
        topShelf.showBanner(t, line2(t), animate);
        shownKey = t.key;
        shelfType = TopShelfFeed.BANNER;
    }

    /** The banner's second line (§2.8). */
    private static String line2(StartModel.Tile t) {
        String key = t.key == null ? "" : t.key;
        if (HomeLayout.EXPLORE.equals(key)) return Ui.tr("Movies and shows from all your apps");
        if (HomeLayout.SETTINGS.equals(key)) return Ui.tr("Home Settings");
        if (key.startsWith(HomeLayout.FOLDER)) {
            return Ui.plural("plural|one:{n} app|other:{n} apps", t.folderApps == null ? 0 : t.folderApps.length);
        }
        if (t.input || key.startsWith(HomeLayout.INPUT)) return t.connected ? Ui.tr("Connected") : null;
        return null;
    }

    /** A carousel that hasn't answered in 1.5 s shows the banner meanwhile (it still replaces it when it comes). */
    private void shelfWait() {
        StartModel.Tile t = grid.tile(grid.indexOf(wantedKey));
        if (t != null && !wantedKey.equals(shownKey)) banner(t, true);
        wake();
    }

    /** The carousel's later items' details, as it advances (TopShelfView pages by itself every 8 s). */
    private void detailsLater() {
        if (wantedKey == null || !wantedKey.equals(shownKey) || shelfType != TopShelfFeed.CAROUSEL || !running) return;
        detailsStep++;
        feed.details(wantedKey, 2 * detailsStep);
        if (detailsStep < 2) handler.postDelayed(detailsLater, 16_000);
    }

    /** The Dock focus rested 400 ms (or the lifted tile did, in the Dock). */
    private void dwell() {
        int i = editing && !editInFolder ? liftIndex : grid.focus;
        boolean dock = editing ? !editInFolder && i >= 0 && i < AppTiles.COLUMNS
                : zone == Z_GRID && i >= 0 && i < AppTiles.COLUMNS;
        if (!dock) return;
        StartModel.Tile t = grid.tile(i);
        if (t != null) followTile(t, true);
    }

    /** The shelf for Dock tile t: kept if it already shows t (a carousel asked for another tile is cancelled). */
    private void followTile(StartModel.Tile t, boolean animate) {
        if (!t.key.equals(shownKey)) {
            showShelfFor(t, animate);
        } else if (!t.key.equals(wantedKey)) {
            handler.removeCallbacks(shelfWait);
            feed.cancel();
            wantedKey = t.key;
        }
    }

    private void scheduleDwell() {
        handler.removeCallbacks(dwell);
        handler.postDelayed(dwell, DWELL_MS);
    }

    private final class FeedListener implements TopShelfFeed.Listener {
        @Override public void onShelf(String key, TopShelfFeed.Item[] items) {
            if (destroyed || broken) return;
            try {
                if (key == null || !key.equals(wantedKey)) return;
                handler.removeCallbacks(shelfWait);
                StartModel.Tile t = grid.tile(grid.indexOf(key));
                if (items == null || items.length == 0) {
                    if (t != null) banner(t, true);
                } else {
                    boolean animate = !key.equals(shownKey) || shelfType != TopShelfFeed.CAROUSEL;
                    topShelf.showCarousel(key, items, animate);
                    shownKey = key;
                    shelfType = TopShelfFeed.CAROUSEL;
                    feed.details(key, 0);
                    detailsStep = 0;
                    handler.removeCallbacks(detailsLater);
                    handler.postDelayed(detailsLater, 12_000);
                }
                if (zone == Z_SHELF && !topShelf.focusable()) exitShelf(false);
                a11yChanged();
                wake();
            } catch (RuntimeException e) {
                fault("shelf", e);
            }
        }

        @Override public void onDetails(String key, int index, ExploreModel.Details d) {
            if (destroyed || broken) return;
            try {
                if (key != null && key.equals(shownKey)) topShelf.setDetails(key, index, d);
                wake();
            } catch (RuntimeException e) {
                fault("details", e);
            }
        }

        @Override public void onUpNext(Card[] c, int n) {
            if (destroyed || broken) return;
            try {
                upNext = c;
                upNextCount = c == null ? 0 : Math.max(0, Math.min(n, c.length));
                if (HomeLayout.EXPLORE.equals(wantedKey)) {
                    StartModel.Tile t = grid.tile(grid.indexOf(HomeLayout.EXPLORE));
                    if (upNextCount > 0) {
                        topShelf.showUpNext(upNext, upNextCount, shelfType != TopShelfFeed.UP_NEXT);
                        shownKey = HomeLayout.EXPLORE;
                        shelfType = TopShelfFeed.UP_NEXT;
                    } else if (t != null) {
                        banner(t, true);
                    }
                }
                if (zone == Z_SHELF && !topShelf.focusable()) exitShelf(false);
                a11yChanged();
                wake();
            } catch (RuntimeException e) {
                fault("up next", e);
            }
        }
    }

    // MARK: - Data (parity with HomeView, §3.7)

    private final HomeData.Listener dataListener = this::onData;

    private void onData() {
        if (destroyed || broken) return;
        try {
            status.update();
            wake();
        } catch (RuntimeException e) {
            fault("data", e);
        }
    }

    private final Recs.Listener recsListener = () -> {
        if (destroyed || broken) return;
        try {
            updatePicks();
            prefetch();
            wake();
        } catch (RuntimeException e) {
            fault("recs", e);
        }
    };

    private final ExploreImages.Listener imagesListener = this::onImages;

    private void onImages() {
        if (destroyed || broken) return;
        try {
            topShelf.imagesArrived();
        } catch (RuntimeException e) {
            fault("images", e);
        }
        wake();
    }

    private void watchArtArrived() {
        topShelf.imagesArrived();
        wake();
    }

    /** recs.now() touches its list without a lock: main thread only, once per onRecs and when the hour changes. */
    private void updatePicks() {
        try {
            picksHour = (int) (System.currentTimeMillis() / 3_600_000L);
            feed.setPicks(new ArrayList<>(recs.now(null)));
        } catch (RuntimeException e) {
            Safe.log("picks", e);
        }
    }

    private void loadWatching() {
        if (destroyed) return;
        WatchFeed.load(activity, (items, allowed) -> {
            if (destroyed || broken) return;
            try {
                watching = items;
                watchAllowed = allowed;
                feed.watching(items, allowed);
                wake();
            } catch (RuntimeException e) {
                fault("watching", e);
            }
        });
    }

    private void networkBack() {
        images.networkBack();
        StartModel.Tile t = grid.tile(grid.indexOf(wantedKey));
        if (t != null && shelfType != TopShelfFeed.CAROUSEL && feed.typeOf(t) == TopShelfFeed.CAROUSEL) showShelfFor(t, true);
    }

    private void onPlaying() {
        status.setPlaying(playing.text);
        wake();
    }

    private void tick() {
        if (!running) return;
        playing.start();   // a listener allowed after the screen showed
        data.refreshIfStale();
        recs.refreshIfStale();
        checkNotices();
        status.update();
        if ((int) (System.currentTimeMillis() / 3_600_000L) != picksHour) updatePicks();   // new picks every hour
        handler.postDelayed(tick, TICK_MS);
        wake();
    }

    /** New notices from the main process (mode switches, the check after a boot) from the last 24 h (HomeView's). */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        final SharedPreferences prefs = ui;
        NOTICES.execute(Safe.wrap("notices", () -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = prefs.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) prefs.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) Safe.post(handler, "notices", () -> {
                if (destroyed || broken) return;
                for (String t : fresh) toast(t);
            });
        }));
    }

    // MARK: - Keys (§3.2)

    boolean onKey(KeyEvent e) {
        if (broken || destroyed) return true;
        try {
            return key(e);
        } catch (RuntimeException ex) {
            fault("key", ex);
            return true;
        }
    }

    private void noteKey() {
        restartIdle();
        handler.removeCallbacks(editHintRun);
        try {
            topShelf.keyed();
        } catch (RuntimeException e) {
            fault("keyed", e);
        }
    }

    private void restartIdle() {
        if (grid.idle.target != 0 || grid.idle.value != 0) {
            grid.idle.snap(0);   // any key restores at once
            folder.grid.idle.snap(0);
            page.invalidateRows();
        }
        handler.removeCallbacks(idleRun);
        handler.postDelayed(idleRun, IDLE_MS);
    }

    private void idle() {
        if (!running || editing || menu.isOpen()) return;
        grid.idle.target = 1;
        folder.grid.idle.target = 1;
        wake();
    }

    private boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        long now = SystemClock.uptimeMillis();
        wake();
        noteKey();
        if (recents != null && recents.isOpen()) {   // not as it fades out: an OK then is the home screen's
            int was = recents.focus();
            boolean handled = recents.key(e);
            recentLayer.invalidate();
            if (recents.focus() != was) spoken.focusMoved();
            if (!recents.visible() || recents.count() == 0) spoken.readAgain();
            return handled;
        }
        if (menu.isOpen()) {
            boolean handled = menu.key(e);
            updateHold();
            return handled;
        }
        if (down && code == KeyEvent.KEYCODE_APP_SWITCH && !"off".equals(Prefs.recents())) {
            openRecents();
            return true;
        }
        if (editing) return editKey(e, code, down, now);
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (down && !ok && (holdArmed || okDown)) {   // another key while OK is held: no hold, no open
            handler.removeCallbacks(holdRun);
            holdArmed = false;
            okDown = false;
            releasePress();
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (!down) return true;
                if (e.getRepeatCount() > 0) {
                    if (now - lastMoveAt < MOVE_MS) return true;
                    images.viewportMoved();
                }
                lastMoveAt = now;
                int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                move(e, dx, dy);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Acts on release, and only for a press seen here; held 500 ms (or the system's long press): the menu.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdRun);
                        holdArmed = canHold();
                        press(true);
                        if (holdArmed) handler.postDelayed(holdRun, HOLD_MS);
                    } else if (okDown && holdArmed && !okHeld && e.isLongPress()) {
                        handler.removeCallbacks(holdRun);
                        hold();
                    }
                } else {
                    handler.removeCallbacks(holdRun);
                    holdArmed = false;
                    press(false);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                // On a tile or a card it is the hold (the carousel's buttons have no menu).
                if (zone != Z_GRID && zone != Z_FOLDER && !(zone == Z_SHELF && shelfType == TopShelfFeed.UP_NEXT)) return false;
                if (down && e.getRepeatCount() == 0) openHoldMenu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                // Plays only in CAROUSEL FOCUSED ("Assistir"); elsewhere the key goes on to the system.
                if (zone != Z_SHELF || shelfType != TopShelfFeed.CAROUSEL || !topShelf.focusable()) return false;
                if (down && e.getRepeatCount() == 0) topShelf.play();
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                // As HomeView: TCL's and Hisense's gear is a Remote Buttons choice (RemoteKeys runs first).
                if (RemoteKeys.HISENSE || RemoteKeys.TCL) return false;
                if (down && e.getRepeatCount() == 0) {
                    tmp.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f + 1, getHeight() / 2f + 1);
                    openItem(HomeApps.tvSettings(), tmp);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private boolean canHold() {
        switch (zone) {
            case Z_GRID: return grid.focus >= 0 && grid.focus < grid.size();
            case Z_FOLDER: return folder.focus() >= 0 && folder.focus() < folder.grid.size();
            case Z_SHELF: return topShelf.focusable();
            default: return false;
        }
    }

    /** The pressed look (1.08) while OK is down on a tile. */
    private void press(boolean down) {
        if (zone == Z_GRID) grid.press(grid.focus, down);
        else if (zone == Z_FOLDER) folder.grid.press(folder.focus(), down);
        wake();
    }

    private void releasePress() {
        if (grid.focus >= 0) grid.press(grid.focus, false);
        if (folder.focus() >= 0) folder.grid.press(folder.focus(), false);
    }

    private void hold() {
        if (!okDown || okHeld) return;
        okHeld = true;
        press(false);
        if (zone == Z_SHELF) {
            if (!topShelf.hold()) okHeld = false;   // nothing to hold there: the release still activates
            return;
        }
        openHoldMenu();
    }

    private void openHoldMenu() {
        if (zone == Z_GRID && grid.focus >= 0) showTileMenu(grid.focus, false, false);
        else if (zone == Z_FOLDER && folder.focus() >= 0) showTileMenu(folder.focus(), true, false);
        else if (zone == Z_SHELF) topShelf.hold();
    }

    private void move(KeyEvent e, int dx, int dy) {
        switch (zone) {
            case Z_FOLDER: {
                int was = folder.focus();
                folder.key(e);
                if (folder.focus() != was) {
                    click.move();
                    scheduleEditHint();
                    spoken.focusMoved();
                }
                break;
            }
            case Z_SHELF: {
                int r = topShelf.move(dx, dy);
                if (r == TopShelfView.MOVE_USED) {
                    click.move();
                    if (dx != 0 && shelfType == TopShelfFeed.CAROUSEL && shownKey != null) {   // paging by hand: the rest now
                        handler.removeCallbacks(detailsLater);
                        for (int i = 2; i < 6; i += 2) feed.details(shownKey, i);
                    }
                    spoken.focusMoved();
                } else if (r == TopShelfView.MOVE_EXIT_UP) {
                    toStatus(Z_SHELF);
                } else if (r == TopShelfView.MOVE_EXIT_DOWN) {
                    exitShelf(false);
                }
                break;
            }
            case Z_STATUS:
                if (dy > 0) fromStatus();
                break;
            default:
                moveGrid(dx, dy);
                break;
        }
        wake();
    }

    private void moveGrid(int dx, int dy) {
        int n = grid.size(), i = grid.focus;
        if (n == 0 || i < 0) {
            if (dy < 0) toStatus(Z_GRID);
            return;
        }
        int j = HomeLayout.nav(i, dx, dy, n, AppTiles.COLUMNS);
        if (j < 0) {   // up from the Dock: into this tile's shelf (the dwell ends now), else the status row
            StartModel.Tile t = grid.tile(i);
            if (t != null && (!t.key.equals(shownKey) || !t.key.equals(wantedKey))) {
                handler.removeCallbacks(dwell);
                followTile(t, true);
            }
            if (topShelf.focusable() && (t == null || t.key.equals(shownKey))) enterShelf();
            else toStatus(Z_GRID);
            return;
        }
        if (j == i || j >= n) {
            grid.bump(i, dx, dy);
            return;
        }
        focusGrid(j, dx, dy);
        click.move();
    }

    /** Focus on grid tile j (arriving by dx, dy): scroll, the shelf's state, the dwell and the hint timer. */
    private void focusGrid(int j, int dx, int dy) {
        if (j < 0 || j >= grid.size()) return;
        zone = Z_GRID;
        status.setFocused(false);
        int was = grid.focus;
        grid.focus = j;
        grid.active = true;
        grid.retarget();
        if (j != was) grid.arrive(j, dx, dy);
        retargetScroll();
        if (AppTiles.row(j) == 0) {
            setShelfState(TopShelfView.BROWSE);
            StartModel.Tile t = grid.tile(j);
            if (t != null && (!t.key.equals(wantedKey) || !t.key.equals(shownKey))) scheduleDwell();
        } else {
            handler.removeCallbacks(dwell);
            setShelfState(TopShelfView.AWAY);
        }
        scheduleEditHint();
        spoken.focusMoved();
        wake();
    }

    /** The page scroll for the focused (or lifted) row (§2.3). */
    private void retargetScroll() {
        int i = editing && !editInFolder ? liftIndex : grid.focus;
        float s = 0;
        if (zone == Z_GRID || editing && !editInFolder) {
            int r = AppTiles.row(Math.max(0, i));
            s = r == 0 ? 0 : r <= 3 ? DOCK_SCROLL : DOCK_SCROLL + AppTiles.PITCH * (r - 3);
            // The focused tile's name sits under it (LABEL_DY past the grown tile): its row scrolls up far enough for
            // the name to clear the bottom edge, the last row too (its name was cut off by the screen's edge).
            float below = AppTiles.H * (1 + AppTiles.GROW) + AppTiles.LABEL_DY + 72;
            if (r > 0) s = Math.max(s, AppTiles.DOCK_TOP + AppTiles.PITCH * r + below - 1080);
            float max = Math.max(0, AppTiles.DOCK_TOP + AppTiles.PITCH * grid.lastRow() + below - 1080);
            s = Math.max(0, Math.min(max, s));
        } else if (zone == Z_FOLDER) {
            s = scroll.target;   // the page stays where it was under the folder
        }
        scroll.target = s;
        wake();
    }

    private void enterShelf() {
        zone = Z_SHELF;
        grid.active = false;
        grid.retarget();
        status.setFocused(false);
        handler.removeCallbacks(dwell);
        setShelfState(TopShelfView.FOCUSED);
        retargetScroll();
        click.move();
        spoken.focusMoved();
    }

    @Override public void exitShelf(boolean up) {
        if (destroyed || broken) return;
        if (up) {
            toStatus(Z_SHELF);
            return;
        }
        int i = Math.max(0, Math.min(grid.focus, Math.min(AppTiles.COLUMNS, grid.size()) - 1));
        zone = Z_GRID;
        setShelfState(TopShelfView.BROWSE);
        if (grid.size() > 0) {
            grid.focus = i;
            grid.active = true;
            grid.retarget();
            grid.arrive(i, 0, 1);
        }
        retargetScroll();
        click.move();
        spoken.focusMoved();
        wake();
    }

    private void toStatus(int from) {
        statusFrom = from;
        zone = Z_STATUS;
        grid.active = false;
        grid.retarget();
        status.setFocused(true);
        setShelfState(from == Z_SHELF ? TopShelfView.FOCUSED : TopShelfView.BROWSE);
        click.move();
        spoken.focusMoved();
        wake();
    }

    private void fromStatus() {
        status.setFocused(false);
        if (statusFrom == Z_SHELF && topShelf.focusable()) {
            zone = Z_SHELF;
            setShelfState(TopShelfView.FOCUSED);
        } else {
            zone = Z_GRID;
            if (grid.focus < 0 && grid.size() > 0) grid.focus = 0;
            grid.active = true;
            grid.retarget();
            setShelfState(TopShelfView.BROWSE);
        }
        retargetScroll();
        click.move();
        spoken.focusMoved();
        wake();
    }

    private void activate() {
        wake();
        switch (zone) {
            case Z_STATUS:
                openControlCenter();
                break;
            case Z_SHELF:
                topShelf.activate();
                break;
            case Z_FOLDER: {
                int i = folder.focus();
                focusRect(folder.grid, i, true, tmp);
                openTile(folder.focusedTile(), tmp);
                break;
            }
            default: {
                int i = grid.focus;
                if (i < 0) return;
                focusRect(grid, i, false, tmp);
                openTile(grid.tile(i), tmp);
                break;
            }
        }
    }

    private void openTile(StartModel.Tile t, RectF from) {
        if (t == null) return;
        if (t.key != null && t.key.startsWith(HomeLayout.FOLDER)) {
            openFolder(t, from);
            return;
        }
        if (t.item == null) {
            toast(Ui.trf("Couldn't open {app}.", "app", t.label == null ? "" : t.label));
            return;
        }
        openItem(t.item, from);
    }

    /** The Back ladder (§3.2), after the overlays that take Back themselves. */
    private void back() {
        if (zone == Z_FOLDER || folder.isOpen()) {
            closeFolder(true);
            click.move();
            return;
        }
        if (zone == Z_SHELF) {
            exitShelf(false);
            return;
        }
        if (zone == Z_STATUS) {
            fromStatus();
            return;
        }
        if (grid.focus >= AppTiles.COLUMNS) {
            int c = Math.min(AppTiles.col(grid.focus), Math.min(AppTiles.COLUMNS, grid.size()) - 1);
            focusGrid(c, 0, -1);
            click.move();
        }
        // On the Dock: nothing, no sound (the home screen never finishes).
    }

    // MARK: - Folders

    private void openFolder(StartModel.Tile t, RectF from) {
        if (t.folderApps == null || t.folderApps.length == 0) return;
        click.open();
        handler.removeCallbacks(dwell);
        folder.open(t, from);
        zone = Z_FOLDER;
        grid.active = false;
        grid.retarget();
        setShelfState(TopShelfView.AWAY);
        scheduleEditHint();
        spoken.focusMoved();
        wake();
    }

    private void closeFolder(boolean animate) {
        StartModel.Tile f = folder.folder();
        folder.close(animate);
        if (zone != Z_FOLDER) return;
        zone = Z_GRID;
        int i = f == null ? -1 : grid.indexOf(f.key);
        if (i < 0) i = Math.max(0, Math.min(grid.size() - 1, grid.focus));
        if (grid.size() > 0) focusGrid(i, 0, 0);
        else {
            grid.active = true;
            grid.retarget();
        }
    }

    // MARK: - Context menu (§3.6)

    @Override public void showMenu(StartMenu.Spec spec) {
        if (destroyed || broken || spec == null) return;
        handler.removeCallbacks(holdRun);
        holdArmed = false;
        okDown = false;
        releasePress();
        menu.show(spec);
        updateHold();
        spoken.focusMoved();
        wake();
    }

    /** The menu of grid (or folder) tile i; fromEdit: Play/Pause or MENU while it's lifted (no "Editar Início"). */
    private void showTileMenu(final int i, final boolean inFolder, final boolean fromEdit) {
        final AppTiles.Grid g = inFolder ? folder.grid : grid;
        final StartModel.Tile t = g.tile(i);
        if (t == null || t.key == null) return;
        final String key = t.key;
        boolean isFolder = key.startsWith(HomeLayout.FOLDER), isExplore = HomeLayout.EXPLORE.equals(key),
                isSettings = HomeLayout.SETTINGS.equals(key), isLive = HomeLayout.LIVE.equals(key),
                isInput = t.input || key.startsWith(HomeLayout.INPUT);
        boolean isApp = !isFolder && !isExplore && !isSettings && !isLive && !isInput && t.pkg != null;
        boolean dock = !inFolder && i < AppTiles.COLUMNS;
        final List<String> rows = new ArrayList<>();
        final List<Integer> acts = new ArrayList<>(), owners = new ArrayList<>();
        final List<Boolean> red = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        int moveTo = -1;
        if (!fromEdit) addRow(rows, acts, owners, red, targets, Ui.tr("Edit Home Screen"), A_EDIT, -1, false, null);
        if (!inFolder) {
            addRow(rows, acts, owners, red, targets, dock ? Ui.tr("Remove from the top row") : Ui.tr("Move to the top row"),
                    dock ? A_OFF_TOP : A_TOP, -1, false, null);
        }
        if (isApp || isInput || isLive) {
            moveTo = rows.size();
            addRow(rows, acts, owners, red, targets, Ui.tr("Move to"), A_MOVE_TO, -1, false, null);
            addRow(rows, acts, owners, red, targets, Ui.tr("New folder"), A_NEW_FOLDER, moveTo, false, null);
            String current = inFolder && folder.folder() != null ? folder.folder().key : null;
            if (layout != null) {
                for (HomeLayout.Folder f : layout.folders.values()) {
                    if (f == null || f.key == null || f.key.equals(current)) continue;
                    addRow(rows, acts, owners, red, targets, f.name == null ? "" : f.name, A_TO_FOLDER, moveTo, false, f.key);
                }
            }
            if (inFolder) addRow(rows, acts, owners, red, targets, Ui.tr("menu|Home Screen"), A_OUT, moveTo, false, null);
        }
        if (isApp) addRow(rows, acts, owners, red, targets, Ui.tr("App info"), A_INFO, -1, false, null);
        if (isApp) addRow(rows, acts, owners, red, targets, Ui.tr("Hide app"), A_HIDE, -1, true, null);
        if (isInput || isLive) addRow(rows, acts, owners, red, targets, Ui.tr("Hide input"), A_HIDE, -1, true, null);
        if (isFolder) {
            addRow(rows, acts, owners, red, targets, Ui.tr("Rename folder"), A_RENAME, -1, false, null);
            addRow(rows, acts, owners, red, targets, Ui.tr("Ungroup folder"), A_UNGROUP, -1, false, null);
        }
        if (rows.isEmpty()) return;
        StartMenu.Spec spec = new StartMenu.Spec();
        spec.title = t.label == null ? "" : t.label;
        spec.rows = rows.toArray(new String[0]);
        spec.destructive = new boolean[rows.size()];
        spec.submenuOf = new int[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            spec.destructive[r] = red.get(r);
            spec.submenuOf[r] = owners.get(r);
        }
        RectF anchor = new RectF();
        if (fromEdit) {
            float cx = liftX.value, cy = liftY.value - (inFolder ? folder.scroll() : scroll.value) * k;
            float w = AppTiles.W * k * 1.23f / 2, h = AppTiles.H * k * 1.23f / 2;
            anchor.set(cx - w, cy - h, cx + w, cy + h);
        } else {
            focusRect(g, i, inFolder, anchor);
        }
        spec.anchor = anchor;
        spec.listener = row -> {
            if (destroyed || broken) return;
            try {
                if (row < 0 || row >= acts.size()) return;
                perform(acts.get(row), key, inFolder, targets.get(row));
            } catch (RuntimeException e) {
                fault("menu pick", e);
            }
        };
        showMenu(spec);
    }

    private static void addRow(List<String> rows, List<Integer> acts, List<Integer> owners, List<Boolean> red,
                               List<String> targets, String text, int act, int owner, boolean destructive, String target) {
        rows.add(text);
        acts.add(act);
        owners.add(owner);
        red.add(destructive);
        targets.add(target);
    }

    private void perform(int act, String key, boolean inFolder, String target) {
        if (layout == null) return;
        if (editing) drop();   // a menu from edit mode acts on where the tile was dropped
        AppTiles.Grid g = inFolder ? folder.grid : grid;
        int i = g.indexOf(key);
        StartModel.Tile t = g.tile(i);
        if (t == null) return;
        String label = t.label == null ? "" : t.label;
        switch (act) {
            case A_EDIT:
                startEdit(i, inFolder, -1);
                break;
            case A_TOP: {
                int to = HomeLayout.topTarget(grid.size());
                if (to < 0 || inFolder) return;
                int from = i;
                if (from != to) {
                    grid.lifted = to;   // the lifted tile doesn't slide: the LiftView carries it
                    grid.move(from, to);
                    grid.lifted = -1;
                    saveOrder(false, grid.keys());
                }
                startEdit(to, false, from);
                break;
            }
            case A_OFF_TOP: {
                int to = HomeLayout.offTopTarget(grid.size());
                if (to < 0 || inFolder || to == i) return;
                grid.move(i, to);
                page.sync();
                saveOrder(false, grid.keys());
                focusGrid(to, 0, 1);
                click.move();
                break;
            }
            case A_NEW_FOLDER:
                newFolder(t, inFolder);
                break;
            case A_TO_FOLDER:
                toFolder(t, inFolder, target);
                break;
            case A_OUT:
                moveOut(t);
                break;
            case A_INFO:
                appInfo(t);
                break;
            case A_HIDE:
                hide(t, inFolder);
                break;
            case A_RENAME:
                rename(t);
                break;
            case A_UNGROUP:
                ungroup(t);
                break;
            default:
                break;
        }
        wake();
    }

    private void newFolder(StartModel.Tile t, boolean inFolder) {
        List<String> taken = new ArrayList<>();
        for (HomeLayout.Folder f : layout.folders.values()) if (f != null && f.name != null) taken.add(f.name);
        String name = t.pkg != null && t.pkg.startsWith("com.tcl.") && !taken.contains("TCL") ? "TCL"
                : layout.nextFolderName(Ui.tr("Folder {n}"), taken);
        if (inFolder) layout.moveOut(t.key);
        String fk = layout.newFolder(t.key, name);
        if (fk == null) return;
        HomeLayout.Folder hf = layout.folders.get(fk);
        if (hf != null && hf.name != null) name = hf.name;
        pendingFocusKey = fk;
        if (inFolder) closeFolder(true);
        toast(Ui.trf("{app} moved to {folder}", "app", t.label == null ? "" : t.label, "folder", name));
        click.open();
        saveAndReload();
    }

    private void toFolder(StartModel.Tile t, boolean inFolder, String fk) {
        if (fk == null) return;
        layout.moveToFolder(t.key, fk);
        HomeLayout.Folder hf = layout.folders.get(fk);
        if (hf == null || !hf.apps.contains(t.key)) return;   // refused (not a package or an input)
        removeLocally(t.key, inFolder);
        toast(Ui.trf("{app} moved to {folder}", "app", t.label == null ? "" : t.label, "folder", hf.name == null ? "" : hf.name));
        click.open();
        saveAndReload();
    }

    /** "Tela de Início": out of the folder, right after it, lifted so the arrows place it. */
    private void moveOut(StartModel.Tile t) {
        int at = layout.moveOut(t.key);
        if (at < 0) return;
        List<StartModel.Tile> list = grid.tiles();
        list.add(Math.max(0, Math.min(list.size(), at)), t);
        folder.close(true);
        zone = Z_GRID;
        grid.setTiles(list, tiles, true);
        page.sync();
        int i = grid.indexOf(t.key);
        saveLayout();
        reloadAfterEdit = true;
        focusGrid(i, 0, 0);
        startEdit(i, false, -1);
    }

    private void appInfo(StartModel.Tile t) {
        if (t.pkg == null) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", t.pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            click.open();
        } catch (RuntimeException e) {   // ActivityNotFoundException, SecurityException
            Safe.log("app info", e);
            toast(Ui.trf("Couldn't open {app}.", "app", t.label == null ? "" : t.label));
        }
    }

    private void hide(StartModel.Tile t, boolean inFolder) {
        if (HomeLayout.EXPLORE.equals(t.key) || HomeLayout.SETTINGS.equals(t.key)) return;
        layout.hide(t.key);
        removeLocally(t.key, inFolder);
        toast(Ui.trf("{app} is hidden. Home Settings > Hidden apps shows it again.", "app", t.label == null ? "" : t.label));
        saveAndReload();
    }

    /** The tile leaves its grid with a reflow; focus on its nearest neighbour. */
    private void removeLocally(String key, boolean inFolder) {
        AppTiles.Grid g = inFolder ? folder.grid : grid;
        int i = g.indexOf(key);
        if (i < 0) return;
        List<StartModel.Tile> list = g.tiles();
        list.remove(i);
        if (inFolder) {
            if (list.isEmpty()) {
                closeFolder(true);
                return;
            }
            g.setTiles(list, tiles, true);
            folder.setFocus(Math.min(i, list.size() - 1), 0, 0);
            ((AppTiles.GridPage) folder.page()).sync();
            return;
        }
        g.setTiles(list, tiles, true);
        page.sync();
        if (list.isEmpty()) {
            grid.focus = -1;
            grid.retarget();
            return;
        }
        focusGrid(Math.min(i, list.size() - 1), 0, 0);
    }

    private void rename(StartModel.Tile t) {
        final String fk = t.key;
        try {
            final EditText edit = new EditText(activity);
            edit.setSingleLine(true);
            edit.setFilters(new InputFilter[] {new InputFilter.LengthFilter(24)});
            edit.setHint(Ui.tr("Folder name"));
            edit.setText(t.label == null ? "" : t.label);
            edit.setSelectAllOnFocus(true);
            edit.selectAll();
            new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("Rename folder"))
                    .setView(edit)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (destroyed || broken || layout == null) return;
                        try {
                            String name = edit.getText() == null ? "" : edit.getText().toString().trim();
                            if (name.isEmpty()) return;   // an empty name keeps the old one
                            if (name.length() > 24) name = name.substring(0, 24);
                            layout.rename(fk, name);
                            pendingFocusKey = fk;
                            saveAndReload();
                        } catch (RuntimeException e) {
                            fault("rename", e);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (RuntimeException e) {
            Safe.log("rename", e);
            toast(Ui.tr("Couldn't rename the folder."));
        }
    }

    private void ungroup(StartModel.Tile t) {
        int i = grid.indexOf(t.key);
        layout.dissolve(t.key);
        if (i >= 0) {
            List<StartModel.Tile> list = grid.tiles();
            list.remove(i);
            if (t.folderApps != null) {
                int at = i;
                for (StartModel.Tile a : t.folderApps) if (a != null) list.add(Math.min(at++, list.size()), a);
            }
            grid.setTiles(list, tiles, true);
            page.sync();
            focusGrid(Math.max(0, Math.min(i, grid.size() - 1)), 0, 0);
        }
        click.open();
        saveAndReload();
    }

    // MARK: - Edit mode (§3.8)

    /** Lifts tile i of the main grid or the open folder; from ≥ 0: the LiftView starts at that slot (Mover para o topo). */
    private void startEdit(int i, boolean inFolder, int from) {
        AppTiles.Grid g = inFolder ? folder.grid : grid;
        if (i < 0 || i >= g.size()) return;
        editing = true;
        editInFolder = inFolder;
        origin = g.keys();
        liftIndex = i;
        g.lifted = i;
        g.focus = i;
        g.editing = true;
        g.active = true;
        g.retarget();
        lift.cell = g.cells.get(i);
        lift.cell.focus.snap(1);
        lift.cell.press.snap(0);
        int start = from >= 0 ? from : i;
        liftX.snap(slotCenterX(g, start));
        liftY.snap(slotCenterY(g, start));
        liftX.target = slotCenterX(g, i);
        liftY.target = slotCenterY(g, i);
        liftAmount.snap(0);
        liftAmount.target = 1;
        lift.setVisibility(VISIBLE);
        lift.invalidate();
        if (!inFolder) {
            zone = Z_GRID;
            status.setFocused(false);
            retargetScroll();
            if (i < AppTiles.COLUMNS) {
                setShelfState(TopShelfView.BROWSE);
                scheduleDwell();
            } else {
                setShelfState(TopShelfView.AWAY);
            }
        } else {
            folder.retargetScroll();
        }
        handler.removeCallbacks(editHintRun);
        hint.hint(Ui.tr("← → ↑ ↓ to move · OK to drop · Back to cancel"), 0);
        click.open();
        updateHold();
        page.invalidateRows();
        spoken.focusMoved();
        wake();
    }

    private boolean editKey(KeyEvent e, int code, boolean down, long now) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (!down) return true;
                if (e.getRepeatCount() > 0 && now - lastMoveAt < HomeLayout.EDIT_REPEAT_MS) return true;
                lastMoveAt = now;
                int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                editMove(dx, dy);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // A fresh press drops; the repeats and the release of the press that lifted it do nothing.
                if (down && e.getRepeatCount() == 0) drop();
                okDown = okHeld = false;
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) cancelEdit();
                    backDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && e.getRepeatCount() == 0) showTileMenu(liftIndex, editInFolder, true);
                return true;
            default:
                return false;
        }
    }

    private void editMove(int dx, int dy) {
        AppTiles.Grid g = editGrid();
        int n = g.size(), i = liftIndex;
        int j = HomeLayout.step(i, dx, dy, n, AppTiles.COLUMNS);
        if (j < 0 || j >= n || j == i) {
            float v = 420 * k;   // the edge bump, on the lifted tile
            if (dx != 0) liftX.velocity = dx * v;
            if (dy != 0) liftY.velocity = dy * v;
            wake();
            return;
        }
        g.lifted = j;
        g.move(i, j);
        liftIndex = j;
        g.focus = j;
        g.retarget();
        liftX.target = slotCenterX(g, j);
        liftY.target = slotCenterY(g, j);
        if (editInFolder) {
            folder.retargetScroll();
        } else {
            page.sync();
            retargetScroll();
            if (j < AppTiles.COLUMNS) {
                setShelfState(TopShelfView.BROWSE);
                scheduleDwell();   // resting 400 ms in the Dock: the shelf previews it
            } else {
                handler.removeCallbacks(dwell);
                setShelfState(TopShelfView.AWAY);
            }
        }
        click.move();
        spoken.focusMoved();
        wake();
    }

    /** OK (and Home, pause, a menu action): the tile lands where it is; saved if the order changed. */
    private void drop() {
        if (!editing) return;
        AppTiles.Grid g = editGrid();
        List<String> keys = g.keys();
        boolean changed = origin == null || !keys.equals(origin);
        if (changed) saveOrder(editInFolder, keys);   // before the hold ends, so a held old snapshot is caught
        finishEdit(g);
    }

    /** Back: every tile glides back to where it was. */
    private void cancelEdit() {
        if (!editing) return;
        AppTiles.Grid g = editGrid();
        String liftedKey = g.key(liftIndex);
        if (origin != null && !g.keys().equals(origin)) {
            List<StartModel.Tile> now = g.tiles(), back = new ArrayList<>(now.size());
            for (String key : origin) {
                int at = g.indexOf(key);
                if (at >= 0) back.add(now.get(at));
            }
            for (StartModel.Tile t : now) if (!back.contains(t)) back.add(t);
            g.setTiles(back, tiles, true);
        }
        int i = g.indexOf(liftedKey);
        if (i >= 0) {
            liftIndex = i;
            g.focus = i;
            liftX.target = slotCenterX(g, i);
            liftY.target = slotCenterY(g, i);
        }
        finishEdit(g);
        click.move();
    }

    private void finishEdit(AppTiles.Grid g) {
        editing = false;
        g.lifted = -1;
        g.editing = false;
        g.retarget();
        if (g.focus >= 0) {
            AppTiles.Cell c = g.cells.get(g.focus);
            c.slideX.snap(0);
            c.slideY.snap(0);
        }
        liftAmount.target = 0;
        hint.clearHint();
        handler.removeCallbacks(dwell);
        boolean inFolder = editInFolder;
        editInFolder = false;
        if (inFolder) {
            folder.retargetScroll();
        } else {
            page.sync();
            int i = g.focus;
            if (i >= 0) {
                zone = Z_GRID;
                retargetScroll();
                setShelfState(AppTiles.row(i) == 0 ? TopShelfView.BROWSE : TopShelfView.AWAY);
                StartModel.Tile t = grid.tile(i);
                if (AppTiles.row(i) == 0 && t != null && (!t.key.equals(shownKey) || !t.key.equals(wantedKey))) scheduleDwell();
            }
        }
        page.invalidateRows();
        liftIndex = -1;
        origin = null;
        updateHold();
        if (reloadAfterEdit && !editing) {
            reloadAfterEdit = false;
            model.load(true);
        }
        spoken.focusMoved();
        wake();
    }

    // MARK: - Hints

    private void scheduleEditHint() {
        handler.removeCallbacks(editHintRun);
        if (editing || ui.getInt("editHints", 0) >= EDIT_HINTS) return;
        if (zone != Z_GRID && zone != Z_FOLDER) return;
        handler.postDelayed(editHintRun, HINT_DWELL_MS);
    }

    /** 2.5 s on a tile, the first 3 times ever: how to edit. */
    private void editHint() {
        if (!running || editing || menu.isOpen() || zone != Z_GRID && zone != Z_FOLDER) return;
        int n = ui.getInt("editHints", 0);
        if (n >= EDIT_HINTS) return;
        ui.edit().putInt("editHints", n + 1).apply();
        hint.hint(Ui.tr("Hold OK to edit the Home Screen"), HINT_SHOW_MS);
        wake();
    }

    // MARK: - StartHost

    @Override public void openItem(HomeApps.Item it, RectF from) {
        if (it == null || destroyed) return;
        click.open();
        RectF r = from != null ? from : tmp2;
        if (from == null) tmp2.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f + 1, getHeight() / 2f + 1);
        boolean opened;
        try {
            opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top), Math.round(r.width()), Math.round(r.height()));
        } catch (RuntimeException e) {
            Safe.log("open", e);
            opened = false;
        }
        if (!opened) toast(Ui.trf("Couldn't open {app}.", "app", it.label == null ? "" : it.label));
    }

    @Override public void openExplore(String target, RectF from) {
        openItem(HomeApps.explore(target), from);
    }

    @Override public void openLink(String pkg, String label, String link, RectF from) {
        if (pkg == null) return;
        openItem(HomeApps.app(pkg, label == null ? pkg : label, link), from);
    }

    @Override public void resumeWatching(WatchNext.Item it, RectF from) {
        if (it == null) return;
        click.open();
        boolean opened = false;
        try {
            opened = WatchLaunch.open(activity, it, this, from);
        } catch (RuntimeException e) {
            Safe.log("resume watching", e);
        }
        if (!opened) toast(Ui.trf("Couldn't open {app}.", "app", it.app == null ? it.pkg : it.app));
    }

    @Override public void hideWatching(WatchNext.Item it) {
        if (it == null) return;
        String key = WatchNext.hideKey(it);
        Set<String> hidden = Prefs.watchHidden();
        hidden.add(key);
        Prefs.setWatchHidden(hidden);
        if (watching != null) {
            List<WatchNext.Item> list = new ArrayList<>(watching.size());
            for (WatchNext.Item x : watching) if (!key.equals(WatchNext.hideKey(x))) list.add(x);
            watching = list;
            feed.watching(list, watchAllowed);
        }
        toast(Ui.tr("Hidden. Settings > Home Settings shows them again."));
    }

    @Override public void toast(String text) {
        if (text == null || text.isEmpty() || destroyed) return;
        hint.toast(text);
        announceForAccessibility(text);
        wake();
    }

    @Override public void openRecents() {
        if (destroyed || broken) return;
        if (recents == null) {
            recents = new RecentApps(activity, new RecentApps.Host() {
                @Override public void open(HomeApps.Item item) {
                    handler.post(guard("recents open", () -> {
                        tmp2.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f + 1, getHeight() / 2f + 1);
                        openItem(item, tmp2);
                    }));
                }

                @Override public void repaint() {
                    recentLayer.postInvalidate();
                    handler.post(guard("recents", () -> {
                        wake();
                        spoken.readAgain();
                    }));
                }
            }, k);
        }
        if (editing) drop();
        menu.close(false);
        updateHold();
        recents.setBackdrop(null);
        recents.open();
        recentLayer.setVisibility(VISIBLE);
        recentLayer.invalidate();
        spoken.readAgain();
        wake();
    }

    @Override public void openControlCenter() {
        click.open();
        try {
            activity.startActivity(new Intent(activity, QuickPanelActivity.class).putExtra(QuickPanelActivity.EXTRA_ANCHOR, "end"));
        } catch (RuntimeException e) {
            Safe.log("control center", e);
            toast(Ui.trf("Couldn't open {app}.", "app", Ui.tr("Control Center")));
        }
    }

    @Override public void a11yChanged() {
        if (destroyed) return;
        spoken.focusMoved();
    }

    // MARK: - Layout and drawing

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (h > 0) wall.setShader(Ui.gradient(h, 0xFF141417, CardPainter.BG));
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof ExplorePage.Pos) {
                ExplorePage.Pos p = (ExplorePage.Pos) lp;
                c.measure(MeasureSpec.makeMeasureSpec(Math.round(p.w * k), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(p.h * k), MeasureSpec.EXACTLY));
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof ExplorePage.Pos) {
                ExplorePage.Pos p = (ExplorePage.Pos) lp;
                int x = Math.round(p.x * k), y = Math.round(p.y * k);
                c.layout(x, y, x + Math.round(p.w * k), y + Math.round(p.h * k));
            } else {
                c.layout(0, 0, r - l, b - t);
            }
        }
    }

    @Override protected void onDraw(Canvas c) {
        try {
            if (wall.getShader() != null) c.drawRect(0, 0, getWidth(), getHeight(), wall);
            else c.drawColor(CardPainter.BG);
        } catch (RuntimeException e) {
            fault("wall draw", e);
        }
    }

    @Override protected void dispatchDraw(Canvas c) {
        int save = c.save();
        try {
            super.dispatchDraw(c);
        } catch (RuntimeException e) {
            fault("draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    /** Recent Apps' full-screen layer: RecentApps draws itself here while it shows. */
    private final class RecentLayer extends View {
        RecentLayer(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
        }

        @Override protected void onDraw(Canvas c) {
            if (recents == null) return;
            int save = c.save();
            try {
                recents.draw(c, getWidth(), getHeight());
            } catch (RuntimeException e) {
                fault("recents draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }
    }

    // MARK: - TalkBack (§3.11)

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private boolean recentsOpen() {
        return recents != null && recents.isOpen();
    }

    private final class Source implements Spoken.Source {
        @Override public void items(List<Integer> out) {
            try {
                if (recentsOpen()) {
                    for (int i = 0; i < Math.max(1, recents.count()); i++) out.add(id(G_RECENTS, i));
                } else if (menu.isOpen()) {
                    for (int i = 0; i < menu.a11yCount(); i++) out.add(id(G_MENU, i));
                } else if (folder.isOpen()) {
                    for (int i = 0; i < folder.grid.size(); i++) out.add(id(G_FOLDER, i));
                } else {
                    out.add(id(G_STATUS, 0));
                    if (topShelf.focusable()) for (int i = 0; i < topShelf.a11yCount(); i++) out.add(id(G_SHELF, i));
                    for (int i = 0; i < grid.size(); i++) out.add(id(G_GRID, i));
                }
            } catch (RuntimeException e) {
                fault("a11y items", e);
            }
        }

        @Override public String text(int id) {
            try {
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS:
                        if (recents == null) return "";
                        return recents.count() == 0 ? Ui.tr("Recent Apps") + ". " + recents.emptyText() : recents.spoken(i);
                    case G_MENU: return menu.a11yText(i);
                    case G_STATUS: {
                        String s = status.a11yText();
                        return s == null || s.isEmpty() ? Ui.tr("Control Center") : s;
                    }
                    case G_SHELF: {
                        String s = topShelf.a11yText(i);
                        return s == null ? "" : s;
                    }
                    case G_GRID: return tileText(grid, i, false);
                    case G_FOLDER: return tileText(folder.grid, i, true);
                    default: return "";
                }
            } catch (RuntimeException e) {
                fault("a11y text", e);
                return "";
            }
        }

        private String tileText(AppTiles.Grid g, int i, boolean inFolder) {
            StartModel.Tile t = g.tile(i);
            if (t == null) return "";
            String name = t.label == null ? "" : t.label;
            int row = AppTiles.row(i), col = AppTiles.col(i);
            if (editing && i == liftIndex && (inFolder == editInFolder)) {
                return Ui.trf("Moving {app}, row {row}, position {n}", "app", name, "row", row + 1, "n", col + 1);
            }
            if (t.key != null && t.key.startsWith(HomeLayout.FOLDER)) {
                int n = t.folderApps == null ? 0 : t.folderApps.length;
                return Ui.trf("{folder}, folder, {count}", "folder", name, "count", Ui.plural("plural|one:{n} app|other:{n} apps", n));
            }
            int inRow = Math.min(AppTiles.COLUMNS, g.size() - row * AppTiles.COLUMNS);
            if (!inFolder && row == 0) return Ui.trf("{app}, top row, {n} of {total}", "app", name, "n", col + 1, "total", inRow);
            return Ui.trf("{app}, row {row}, {n} of {total}", "app", name, "row", inFolder ? row + 1 : row + 1, "n", col + 1, "total", inRow);
        }

        @Override public boolean bounds(int id, Rect out) {
            try {
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS:
                        if (recents == null) return false;
                        if (recents.count() == 0) recents.emptyBounds(tmp);
                        else if (!recents.bounds(i, tmp)) return false;
                        break;
                    case G_MENU:
                        if (!menu.a11yBounds(i, tmp)) return false;
                        break;
                    case G_STATUS:
                        if (!status.buttonRect(tmp)) return false;
                        break;
                    case G_SHELF:
                        if (!topShelf.a11yBounds(i, tmp)) return false;
                        break;
                    case G_GRID: {
                        if (i < 0 || i >= grid.size()) return false;
                        float x = grid.slotX(i) * k, y = grid.slotY(i) * k + page.getTranslationY();
                        tmp.set(x, y, x + AppTiles.W * k, y + AppTiles.H * k);
                        squeeze(tmp);
                        break;
                    }
                    case G_FOLDER:
                        if (i < 0 || i >= folder.grid.size()) return false;
                        folder.slotRect(i, tmp);
                        squeeze(tmp);
                        break;
                    default:
                        return false;
                }
                tmp.round(out);
                return true;
            } catch (RuntimeException e) {
                fault("a11y bounds", e);
                return false;
            }
        }

        /** Off-screen items go into a 24k strip at the edge they are hidden past (HomeView). */
        private void squeeze(RectF r) {
            float h = getHeight(), strip = 24 * k;
            if (r.bottom <= 0) r.set(r.left, 0, r.right, strip);
            else if (r.top >= h) r.set(r.left, h - strip, r.right, h);
        }

        @Override public int focused() {
            if (recentsOpen()) return id(G_RECENTS, recents.count() == 0 ? 0 : recents.focus());
            if (menu.isOpen()) return menu.a11yFocused() < 0 ? -1 : id(G_MENU, menu.a11yFocused());
            if (folder.isOpen()) return folder.focus() < 0 ? -1 : id(G_FOLDER, editing && editInFolder ? liftIndex : folder.focus());
            switch (zone) {
                case Z_STATUS: return id(G_STATUS, 0);
                case Z_SHELF: {
                    int f = topShelf.a11yFocused();
                    return f < 0 ? -1 : id(G_SHELF, f);
                }
                default: {
                    int i = editing ? liftIndex : grid.focus;
                    return i < 0 ? -1 : id(G_GRID, i);
                }
            }
        }

        @Override public void focus(int id) {
            if (editing) return;
            try {
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS:
                        if (recents != null) recents.setFocus(i);
                        recentLayer.invalidate();
                        break;
                    case G_MENU:
                        menu.a11yFocus(i);
                        break;
                    case G_FOLDER:
                        folder.setFocus(i, 0, 0);
                        break;
                    case G_STATUS:
                        if (zone != Z_STATUS) toStatus(zone == Z_SHELF ? Z_SHELF : Z_GRID);
                        break;
                    case G_SHELF:
                        if (zone != Z_SHELF) enterShelf();
                        topShelf.a11yFocus(i);
                        break;
                    case G_GRID:
                        focusGrid(i, 0, 0);
                        break;
                    default:
                        break;
                }
                wake();
            } catch (RuntimeException e) {
                fault("a11y focus", e);
            }
        }

        @Override public void click(int id) {
            try {
                if (editing) {   // TalkBack's click while moving: drop
                    drop();
                    return;
                }
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS:
                        if (recents != null) recents.openFocused();
                        break;
                    case G_MENU:
                        menu.a11yClick(i);
                        updateHold();
                        break;
                    case G_FOLDER:
                        folder.setFocus(i, 0, 0);
                        zone = Z_FOLDER;
                        activate();
                        break;
                    case G_STATUS:
                        openControlCenter();
                        break;
                    case G_SHELF:
                        topShelf.a11yClick(i);
                        break;
                    case G_GRID:
                        focusGrid(i, 0, 0);
                        activate();
                        break;
                    default:
                        break;
                }
                wake();
            } catch (RuntimeException e) {
                fault("a11y click", e);
            }
        }

        @Override public boolean holdable(int id) {
            try {
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS: return recentsOpen() && recents.stoppable(i);
                    case G_GRID: return !editing && i >= 0 && i < grid.size();
                    case G_FOLDER: return !editing && i >= 0 && i < folder.grid.size();
                    case G_SHELF: return topShelf.a11yHoldable(i);
                    default: return false;
                }
            } catch (RuntimeException e) {
                fault("a11y holdable", e);
                return false;
            }
        }

        @Override public void hold(int id) {
            try {
                int group = id / 1000, i = id % 1000 - 1;
                switch (group) {
                    case G_RECENTS:
                        if (recents != null) recents.closeFocused();
                        break;
                    case G_GRID:
                        focusGrid(i, 0, 0);
                        showTileMenu(i, false, false);
                        break;
                    case G_FOLDER:
                        folder.setFocus(i, 0, 0);
                        showTileMenu(i, true, false);
                        break;
                    case G_SHELF:
                        topShelf.a11yHold(i);
                        break;
                    default:
                        break;
                }
                wake();
            } catch (RuntimeException e) {
                fault("a11y hold", e);
            }
        }
    }

    // MARK: - Mouse (§3.11)

    /** What's under (x, y): an a11y id (group·1000 + index + 1) or -1. */
    private int hitAt(float x, float y) {
        if (menu.isOpen()) {
            int r = menu.hit(x, y);
            return r < 0 ? -1 : id(G_MENU, r);
        }
        if (folder.isOpen()) {
            int i = folder.hit(x, y, tmp);
            return i < 0 ? -1 : id(G_FOLDER, i);
        }
        if (status.buttonRect(tmp) && tmp.contains(x, y)) return id(G_STATUS, 0);
        float ty = page.getTranslationY();
        for (int i = 0; i < grid.size(); i++) {
            float gx = grid.slotX(i) * k, gy = grid.slotY(i) * k + ty;
            if (x >= gx && x <= gx + AppTiles.W * k && y >= gy && y <= gy + AppTiles.H * k) return id(G_GRID, i);
        }
        if (topShelf.focusable() || zone == Z_SHELF) {
            int s = topShelf.hit(x, y);
            if (s >= 0) return id(G_SHELF, s);
        }
        return -1;
    }

    /** The scroll a grid tile would need: hover never moves focus onto one that would scroll the page. */
    private boolean wouldScroll(int i) {
        int r = AppTiles.row(i);
        float s = r == 0 ? 0 : r <= 3 ? DOCK_SCROLL : DOCK_SCROLL + AppTiles.PITCH * (r - 3);
        float max = Math.max(0, AppTiles.DOCK_TOP + AppTiles.PITCH * grid.lastRow() + AppTiles.H + 46 - 1080);
        s = Math.max(0, Math.min(max, s));
        return Math.abs(s - scroll.target) > 0.5f;
    }

    @Override public boolean onHoverEvent(MotionEvent ev) {
        if (broken || destroyed) return true;
        try {
            int a = ev.getActionMasked();
            if (a != MotionEvent.ACTION_HOVER_MOVE && a != MotionEvent.ACTION_HOVER_ENTER) return true;
            if (editing || Math.abs(scroll.value - scroll.target) > 1 || recentsOpen()) return true;
            int code = hitAt(ev.getX(), ev.getY());
            if (code == hovered) return true;
            hovered = code;
            if (code < 0) return true;
            int group = code / 1000, i = code % 1000 - 1;
            switch (group) {
                case G_MENU:
                    menu.a11yFocus(i);
                    break;
                case G_FOLDER:
                    if (i != folder.focus()) {
                        folder.setFocus(i, 0, 0);
                        click.move();
                    }
                    break;
                case G_GRID:
                    if (!wouldScroll(i) && (zone != Z_GRID || i != grid.focus)) {
                        focusGrid(i, 0, 0);
                        click.move();
                    }
                    break;
                case G_STATUS:
                    if (zone != Z_STATUS) toStatus(zone == Z_SHELF ? Z_SHELF : Z_GRID);
                    break;
                case G_SHELF:
                    if (zone == Z_SHELF) topShelf.a11yFocus(i);
                    break;
                default:
                    break;
            }
            wake();
        } catch (RuntimeException e) {
            fault("hover", e);
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (broken || destroyed) return true;
        try {
            if (editing) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getX();
                    downY = ev.getY();
                    longTapFired = false;
                    handler.removeCallbacks(longTap);
                    if ((ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                        longTapFired = true;
                        pointerMenu(downX, downY);
                    } else {
                        handler.postDelayed(longTap, LONG_TAP_MS);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(ev.getX() - downX, ev.getY() - downY) > 24 * k) handler.removeCallbacks(longTap);
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longTap);
                    if (!longTapFired && Math.hypot(ev.getX() - downX, ev.getY() - downY) < 24 * k) tap(ev.getX(), ev.getY());
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longTap);
                    return true;
                default:
                    return true;
            }
        } catch (RuntimeException e) {
            fault("touch", e);
            return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent ev) {
        if (broken || destroyed) return true;
        try {
            if (editing || (ev.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) return super.onGenericMotionEvent(ev);
            if (ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY) {
                pointerMenu(ev.getX(), ev.getY());
                return true;
            }
            if (ev.getActionMasked() != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(ev);
            float v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (v == 0) return true;
            int dir = v > 0 ? -1 : 1;
            noteKey();
            if (menu.isOpen() || recentsOpen()) return true;
            if (zone == Z_SHELF && ev.getY() < 700 * k) {
                if (topShelf.move(dir, 0) == TopShelfView.MOVE_USED) click.move();   // pages the carousel
            } else if (zone == Z_FOLDER) {
                int n = folder.grid.size(), i = folder.focus(), j = HomeLayout.nav(i, 0, dir, n, AppTiles.COLUMNS);
                if (j >= 0 && j != i && j < n) {
                    folder.setFocus(j, 0, dir);
                    click.move();
                }
            } else if (zone == Z_GRID) {
                moveGrid(0, dir);
            }
            spoken.focusMoved();
            wake();
            return true;
        } catch (RuntimeException e) {
            fault("wheel", e);
            return true;
        }
    }

    /** A tap: focus what's there, then act on it; outside an open menu, it closes. */
    private void tap(float x, float y) {
        noteKey();
        int code = hitAt(x, y);
        if (menu.isOpen()) {
            if (code >= 0) menu.a11yClick(code % 1000 - 1);
            else if (!menu.contains(x, y)) menu.close(true);
            updateHold();
            return;
        }
        if (recentsOpen() || code < 0) return;
        int group = code / 1000, i = code % 1000 - 1;
        switch (group) {
            case G_FOLDER:
                folder.setFocus(i, 0, 0);
                zone = Z_FOLDER;
                activate();
                break;
            case G_STATUS:
                if (zone != Z_STATUS) toStatus(zone == Z_SHELF ? Z_SHELF : Z_GRID);
                openControlCenter();
                break;
            case G_SHELF:
                if (zone != Z_SHELF) enterShelf();
                topShelf.a11yFocus(i);
                topShelf.a11yClick(i);
                break;
            case G_GRID:
                focusGrid(i, 0, 0);
                activate();
                break;
            default:
                break;
        }
        wake();
    }

    private void longTap() {
        longTapFired = true;
        pointerMenu(downX, downY);
    }

    /** Right click or a 500 ms press: the item's menu. */
    private void pointerMenu(float x, float y) {
        if (editing || menu.isOpen() || recentsOpen()) return;
        int code = hitAt(x, y);
        if (code < 0) return;
        int group = code / 1000, i = code % 1000 - 1;
        if (group == G_GRID) {
            focusGrid(i, 0, 0);
            showTileMenu(i, false, false);
        } else if (group == G_FOLDER) {
            folder.setFocus(i, 0, 0);
            showTileMenu(i, true, false);
        } else if (group == G_SHELF && topShelf.a11yHoldable(i)) {
            if (zone != Z_SHELF) enterShelf();
            topShelf.a11yHold(i);
        }
        wake();
    }
}
