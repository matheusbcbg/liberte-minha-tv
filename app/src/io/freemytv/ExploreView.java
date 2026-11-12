package io.freemytv;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Explore's root view: the backdrop, the page stack (root pages from the top bar, pushed detail/grid/service pages),
 * the top bar and the overlay (synopsis sheet, toasts). It owns the one frame callback, posted only while something
 * moves, and routes the remote's keys.
 */
final class ExploreView extends ViewGroup implements Choreographer.FrameCallback, Spoken.Source, ExploreImages.Listener {
    /** At most this many pages: the root and 7 pushed ones (the oldest pushed one goes first). */
    static final int MAX_STACK = 8;
    private static final long OK_HOLD_MS = 500, DWELL_MS = 300, HOLD_POLL_MS = 1000, GRANT_WAIT_MS = 12_000;

    private final ExploreActivity activity;
    private final float k;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExploreImages images;
    private final BackdropView backdrop;
    private final TopBar topBar;
    private final Overlay overlay;
    /** Holding OK on a title: watch, Quero assistir, more info (Início's menu, on top of everything). */
    private final StartMenu menu;
    private final Spoken spoken;
    private final WatchArt watchArt;
    final CardPainter painter;

    private final ExplorePage[] roots = new ExplorePage[Catalog.PAGE_NAMES.length];
    private final ArrayList<ExplorePage> pushed = new ArrayList<>();
    private int root = -1;
    private ExplorePage current, outgoing;
    private boolean outgoingDies, popping;
    private final Spring transition = new Spring(0.38f, 0.90f, 1);
    private boolean topFocus, running, framePending, broken, destroyed;
    private long lastFrame, lastKeyAt, lastMoveAt;
    private boolean okDown, okHeld, backDown;
    private KeyEvent okEvent;
    private final RectF tmp = new RectF();
    private final Rect tmpRect = new Rect();
    private final Runnable networkBack = guard(this, "network", this::networkBack);
    private final Runnable watchObserved = guard(this, "watching", this::watchChanged);
    private final Runnable dwell = guard(this, "dwell", this::dwell);
    private final Runnable longOk = guard(this, "hold", this::longOk);
    private long grantUntil;
    private final Runnable grantPoll = guard(this, "grant", this::grantPoll);

    ExploreView(ExploreActivity a, Bundle saved) {
        super(a);
        activity = a;
        k = scaleOf(a);
        setClipChildren(false);
        setWillNotDraw(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        images = ExploreImages.acquire(a);
        painter = new CardPainter(a, k);
        watchArt = new WatchArt(a, this::onImages);
        watchArt.setScale(k * 424f / WatchArt.W);
        backdrop = new BackdropView(this, images);
        topBar = new TopBar(a);
        addView(topBar, new ExplorePage.Pos(0, 0, 1920, 132));
        overlay = new Overlay(a, k);
        addView(overlay, new ExplorePage.Pos(0, 0, 1920, 1080));
        menu = new StartMenu(a, new MenuHost());
        addView(menu, new ExplorePage.Pos(0, 0, 1920, 1080));
        spoken = new Spoken(this, this);
        Watchlist.load(a, null);
        if (saved != null) restore(saved);
    }

    // MARK: - Helpers shared by the pages

    /** The layout scale: the screen's long side over 1920. */
    static float scaleOf(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float w = Math.max(dm.widthPixels, dm.heightPixels);
        return w > 0 ? w / 1920f : 1;
    }

    /**
     * A draw, key or frame threw: log the class only, then close Explore (posted, never from inside the draw). The
     * Views Início borrows (RowView, HeroView, BackdropView) call this too: there it swaps to the classic home screen.
     */
    static void crashed(View v, String tag, Throwable e) {
        Safe.log(tag, e);
        Context c = v.getContext();
        if (c instanceof ExploreActivity) ((ExploreActivity) c).fail();
        else if (c instanceof HomeActivity) new Handler(Looper.getMainLooper()).post(() -> ((HomeActivity) c).startFault(tag));
    }

    /** A Handler callback that can't take Explore down silently: a throw logs its class and closes Explore. */
    static Runnable guard(View v, String tag, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                crashed(v, tag, e);
            }
        };
    }

    void markBroken() {
        broken = true;
    }

    /** The best installed, enabled streaming offer: flatrate, then free, then ads, then TMDB's display priority. */
    static ExploreModel.Offer bestOffer(ExploreModel.Details d) {
        if (d == null || d.offers == null) return null;
        BrProviders.Snapshot snap = BrProviders.snapshot();
        ExploreModel.Offer best = null;
        for (ExploreModel.Offer o : d.offers) {
            if (!playable(o, snap)) continue;
            if (best == null || o.type < best.type || (o.type == best.type && o.priority < best.priority)) best = o;
        }
        return best;
    }

    /** Streamable here: a known service, flatrate/free/ads, installed on this TV and not turned off. */
    static boolean playable(ExploreModel.Offer o, BrProviders.Snapshot snap) {
        if (o == null || o.service == null || o.type > ExploreModel.ADS) return false;
        if (!ProviderTable.streamable(o.service, o.providerId, o.type)) return false;
        return (o.installed || snap.installed(o.service.key)) && snap.enabled(o.service.key) && snap.pkg(o.service.key) != null;
    }

    static boolean streamableOffer(ExploreModel.Offer o) {
        return o != null && o.type <= ExploreModel.ADS && (o.service == null || ProviderTable.streamable(o.service, o.providerId, o.type));
    }

    static String serviceLabel(ExploreModel.Offer o) {
        if (o == null) return "";
        if (o.service != null && o.service.label != null) return o.service.label;
        return o.name == null ? "" : o.name;
    }

    /** "2026-12-12" → "12 de dez." (the catalogue's short pattern); "" if it doesn't parse. UI thread, outside draw. */
    static String shortDate(String ymd) {
        if (ymd == null || ymd.length() < 10) return "";
        try {
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Date d = in.parse(ymd.substring(0, 10));
            return d == null ? "" : Ui.datePattern("format|d MMM").format(d.getTime());
        } catch (java.text.ParseException | RuntimeException e) {
            return "";
        }
    }

    /** The year of "yyyy-MM-dd", or 0. */
    static int yearOf(String ymd) {
        if (ymd == null || ymd.length() < 4) return 0;
        try {
            return Integer.parseInt(ymd.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    float k() { return k; }
    BackdropView backdrop() { return backdrop; }
    TopBar topBar() { return topBar; }
    Handler handler() { return handler; }
    Click click() { return activity.click(); }
    ExploreActivity activity() { return activity; }
    ExploreImages images() { return images; }
    WatchArt watchArt() { return watchArt; }
    long lastKeyAt() { return lastKeyAt; }
    boolean topFocused() { return topFocus; }
    boolean running() { return running; }
    Spoken spoken() { return spoken; }
    ExplorePage currentPage() { return current; }
    boolean isCurrent(ExplorePage p) { return p == current; }

    // MARK: - Routing and the page stack

    void route(String target, boolean fromNewIntent) {
        if (destroyed) return;
        try {
            if (target != null && (target.startsWith("movie/") || target.startsWith("tv/"))) {
                int slash = target.indexOf('/');
                int kind = target.startsWith("tv/") ? ExploreModel.TV : ExploreModel.MOVIE;
                int id = Integer.parseInt(target.substring(slash + 1));
                if (root < 0) selectRoot(Catalog.HOME, false);
                push(new DetailPage(this, kind, id, true));
                return;
            }
            if (target != null && target.startsWith("page:")) {
                String name = target.substring(5);
                for (int i = 0; i < Catalog.PAGE_NAMES.length; i++) {
                    if (!Catalog.PAGE_NAMES[i].equals(name)) continue;
                    clearPushed();
                    selectRoot(i, root >= 0);
                    if (topFocus && current != null) {
                        topFocus = false;
                        topBar.setFocus(-1);
                        current.focusFromTop();
                    }
                    return;
                }
            }
            if (root < 0) {
                selectRoot(Catalog.HOME, false);
                return;
            }
            if (fromNewIntent && activity.stoppedFor() > ExploreActivity.RESET_MS) {
                clearPushed();
                selectRoot(Catalog.HOME, false);
                if (roots[Catalog.HOME] instanceof BrowsePage) {
                    if (topFocus) {   // the hero takes the focus (or resetToTop gives it back to the bar, on Início)
                        topFocus = false;
                        topBar.setFocus(-1);
                    }
                    ((BrowsePage) roots[Catalog.HOME]).resetToTop();
                }
            }
        } catch (RuntimeException e) {
            Safe.log("route", e);
            if (root < 0) selectRoot(Catalog.HOME, false);
        }
    }

    void selectRoot(int page) {
        selectRoot(page, true);
    }

    private void selectRoot(int page, boolean animate) {
        if (page < 0 || page >= roots.length) return;
        handler.removeCallbacks(dwell);
        boolean hadPushed = !pushed.isEmpty();
        clearPushed();
        topBar.setSelected(page);
        if (page == root && current == roots[page] && !hadPushed) return;
        ExplorePage p = roots[page];
        if (p == null) {
            p = page == Catalog.SEARCH ? new SearchPage(this) : BrowsePage.root(this, page);
            p.root = true;
            p.build();
            roots[page] = p;
            addLayer(p);
        }
        root = page;
        show(p, animate ? 0 : -1, false);
    }

    void push(ExplorePage p) {
        if (destroyed || p == null) return;
        if (pushed.size() >= MAX_STACK - 1) {
            ExplorePage oldest = pushed.remove(0);
            if (oldest == outgoing) finishTransition();
            removeLayer(oldest);
        }
        p.root = false;
        p.build();
        addLayer(p);
        pushed.add(p);
        if (topFocus) {
            topFocus = false;
            topBar.setFocus(-1);
        }
        show(p, 1, false);
    }

    void pop() {
        if (pushed.isEmpty()) return;
        ExplorePage p = pushed.remove(pushed.size() - 1);
        ExplorePage under = pushed.isEmpty() ? roots[root] : pushed.get(pushed.size() - 1);
        if (under == null) under = roots[Catalog.HOME];
        show(under, 2, true);
        // The popped page goes once the transition is over (show made it the outgoing one).
        if (outgoing != p) removeLayer(p);
    }

    private void clearPushed() {
        if (pushed.isEmpty()) return;
        for (ExplorePage p : pushed) {
            if (p == current) current = null;
            if (p == outgoing) {
                outgoing = null;
            }
            removeLayer(p);
        }
        pushed.clear();
    }

    private void addLayer(ExplorePage p) {
        ViewGroup layer = p.layer();
        if (layer.getParent() == null) addView(layer, indexOfChild(topBar), new ExplorePage.Pos(0, 0, 1920, 1080));
        layer.setVisibility(INVISIBLE);
    }

    private void removeLayer(ExplorePage p) {
        if (p == null) return;
        try {
            p.hidden();
            p.destroy();
        } catch (RuntimeException e) {
            Safe.log("page destroy", e);
        }
        ViewGroup layer = p.layer();
        if (layer.getParent() == this) removeView(layer);
    }

    /** mode: -1 no animation, 0 crossfade (tabs), 1 push (slides up), 2 pop. */
    private void show(ExplorePage p, int mode, boolean popped) {
        if (p == current) {
            p.layer().setVisibility(VISIBLE);
            p.layer().setAlpha(1);
            p.layer().setTranslationY(0);
            p.shown();
            wake();
            return;
        }
        if (outgoing != null) finishTransition();
        ExplorePage old = current;
        current = p;
        ViewGroup layer = p.layer();
        layer.setVisibility(VISIBLE);
        if (old != null) {
            old.pause();
            old.hidden();
        }
        if (mode < 0 || old == null) {
            if (old != null) {
                old.layer().setVisibility(INVISIBLE);
                if (popped) removeLayer(old);
            }
            layer.setAlpha(1);
            layer.setTranslationY(0);
            transition.snap(1);
        } else {
            outgoing = old;
            outgoingDies = popped;
            popping = mode == 2;
            transition.snap(0);
            transition.target = 1;
            layer.setLayerType(LAYER_TYPE_HARDWARE, null);
            old.layer().setLayerType(LAYER_TYPE_HARDWARE, null);
            applyTransition(mode);
        }
        transitionMode = mode;
        topBar.setScroll(p.root ? p.pageScroll() : 1e6f);
        if (!p.root) topBar.setFocus(-1);
        else topBar.setSelected(root);
        p.shown();
        if (running) p.resume();
        spoken.focusMoved();
        wake();
    }

    private int transitionMode;

    private void applyTransition(int mode) {
        float t = Ui.clamp(transition.value);
        ViewGroup in = current.layer();
        in.setAlpha(t);
        in.setTranslationY(mode == 1 ? 40 * k * (1 - t) : 0);
        if (outgoing != null) {
            ViewGroup out = outgoing.layer();
            out.setAlpha(1 - t);
            out.setTranslationY(mode == 2 ? 40 * k * t : 0);
        }
    }

    private void finishTransition() {
        transition.snap(1);
        if (current != null) {
            ViewGroup in = current.layer();
            in.setAlpha(1);
            in.setTranslationY(0);
            in.setLayerType(LAYER_TYPE_NONE, null);
        }
        ExplorePage out = outgoing;
        outgoing = null;
        if (out != null) {
            ViewGroup l = out.layer();
            l.setVisibility(INVISIBLE);
            l.setAlpha(1);
            l.setTranslationY(0);
            l.setLayerType(LAYER_TYPE_NONE, null);
            if (outgoingDies) removeLayer(out);
        }
        outgoingDies = false;
    }

    // MARK: - Lifecycle

    void resume() {
        running = true;
        lastFrame = 0;
        images.setListener(this);
        try {
            WatchFeed.observe(activity, watchObserved);
            Tmdb.get(activity).addNetworkListener(networkBack);
        } catch (RuntimeException e) {
            Safe.log("resume", e);
        }
        if (current != null) current.resume();
        // Início may have changed the list meanwhile (its own process): read the file again if it did.
        Watchlist.load(getContext(), items -> {
            if (!destroyed) listChanged();
        });
        backdrop.refresh();
        for (ExplorePage p : roots) if (p != null) p.watchChanged();
        spoken.readAgain();
        wake();
    }

    void pause() {
        running = false;
        if (framePending) Choreographer.getInstance().removeFrameCallback(this);
        framePending = false;
        handler.removeCallbacks(dwell);
        handler.removeCallbacks(longOk);
        handler.removeCallbacks(grantPoll);
        okDown = okHeld = backDown = false;
        images.clearListener(this);
        images.viewportMoved();   // the image jobs asked for so far sink below what comes next
        try {
            WatchFeed.stop(activity);
            Tmdb.get(activity).removeNetworkListener(networkBack);
        } catch (RuntimeException e) {
            Safe.log("pause", e);
        }
        if (current != null) current.pause();
        if (outgoing != null) finishTransition();
    }

    void destroy() {
        destroyed = true;
        pause();
        clearPushed();
        for (int i = 0; i < roots.length; i++) {
            if (roots[i] != null) removeLayer(roots[i]);
            roots[i] = null;
        }
        watchArt.close();
        handler.removeCallbacksAndMessages(null);
    }

    void providersChanged() {
        if (destroyed) return;
        for (ExplorePage p : roots) if (p != null) p.reload();
        for (ExplorePage p : pushed) p.reload();
        wake();
    }

    private void networkBack() {
        if (destroyed) return;
        images.networkBack();   // pictures that failed while offline may load now
        for (ExplorePage p : roots) if (p != null) p.reload();
        for (ExplorePage p : pushed) p.reload();
        wake();
    }

    void watchChanged() {
        if (destroyed) return;
        for (ExplorePage p : roots) if (p != null) p.watchChanged();
    }

    void voiceResult(String text) {
        ExplorePage s = roots[Catalog.SEARCH];
        if (s instanceof SearchPage) ((SearchPage) s).voiceResult(text);
    }

    void save(Bundle out) {
        out.putInt("explore.root", Math.max(0, root));
        for (int i = pushed.size() - 1; i >= 0; i--) {
            ExplorePage p = pushed.get(i);
            if (p instanceof DetailPage) {
                DetailPage d = (DetailPage) p;
                out.putString("explore.detail", (d.kind == ExploreModel.TV ? "tv/" : "movie/") + d.id);
                out.putBoolean("explore.fromHome", d.fromHome);
                break;
            }
        }
    }

    private void restore(Bundle saved) {
        try {
            int r = saved.getInt("explore.root", Catalog.HOME);
            selectRoot(Math.max(0, Math.min(r, roots.length - 1)), false);
            String detail = saved.getString("explore.detail");
            if (detail != null && detail.matches("(movie|tv)/\\d{1,9}")) {
                int kind = detail.startsWith("tv/") ? ExploreModel.TV : ExploreModel.MOVIE;
                push(new DetailPage(this, kind, Integer.parseInt(detail.substring(detail.indexOf('/') + 1)),
                        saved.getBoolean("explore.fromHome", false)));
            }
        } catch (RuntimeException e) {
            Safe.log("restore", e);
            if (root < 0) selectRoot(Catalog.HOME, false);
        }
    }

    // MARK: - Frames

    void wake() {
        if (!running || framePending || broken || destroyed) return;
        framePending = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        framePending = false;
        if (!running || broken || destroyed) return;
        try {
            float dt = lastFrame == 0 ? 1 / 60f : Math.max(0, Math.min(0.05f, (frameTimeNanos - lastFrame) / 1e9f));
            lastFrame = frameTimeNanos;
            images.frame();
            boolean busy = topBar.step(dt);
            busy |= backdrop.step(dt);
            busy |= overlay.step(dt);
            busy |= menu.step(dt);
            if (outgoing != null) {
                busy |= transition.step(dt);
                applyTransition(transitionMode);
                busy |= outgoing.step(dt);
                if (transition.value >= 0.995f && Math.abs(transition.velocity) < 0.05f) finishTransition();
                else busy = true;
            }
            if (current != null) {
                busy |= current.step(dt);
                topBar.setScroll(current.root ? current.pageScroll() : 1e6f);
            }
            if (busy) wake();
            else lastFrame = 0;
        } catch (RuntimeException e) {
            crashed(this, "frame", e);
        }
    }

    @Override public void onImages() {
        if (destroyed) return;
        try {
            backdrop.imagesArrived();
            if (current != null) current.imagesArrived();
            if (outgoing != null) outgoing.imagesArrived();
        } catch (RuntimeException e) {
            crashed(this, "images", e);
        }
        wake();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent e) {
        if (broken || destroyed) return true;
        try {
            return key(e);
        } catch (RuntimeException ex) {
            crashed(this, "key", ex);
            return true;
        }
    }

    private boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        long now = SystemClock.uptimeMillis();
        lastKeyAt = now;
        wake();
        if (menu.isOpen()) {
            okDown = okHeld = backDown = false;   // the hold that opened it ends in the menu
            handler.removeCallbacks(longOk);
            if (!menu.key(e) && down && (code == KeyEvent.KEYCODE_HOME)) menu.close(false);
            return true;
        }
        if (overlay.sheetOpen()) return sheetKey(e, code, down);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!down) return true;
                if (e.getRepeatCount() > 0) {
                    if (!RowMath.acceptRepeat(lastMoveAt, now)) return true;
                    images.viewportMoved();
                }
                lastMoveAt = now;
                if (okDown) {   // another key while OK is held: no long press
                    handler.removeCallbacks(longOk);
                    okDown = false;
                }
                move(code, e);
                spoken.focusMoved();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        okEvent = e;
                        handler.removeCallbacks(longOk);
                        handler.postDelayed(longOk, OK_HOLD_MS);
                        if (topFocus) topBar.press(true);
                    } else if (okDown && !okHeld && e.isLongPress()) {
                        handler.removeCallbacks(longOk);
                        longOk();
                    }
                } else {
                    handler.removeCallbacks(longOk);
                    topBar.press(false);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) ok(e);
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && e.getRepeatCount() == 0 && !topFocus && current != null) {
                    current.onKey(KeyEvent.KEYCODE_DPAD_CENTER, e, true);
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down && !topFocus && current != null) {
                    current.onKey(code, e, false);
                    spoken.focusMoved();
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                if (down && e.getRepeatCount() == 0) {
                    if (current instanceof SearchPage) ((SearchPage) current).startVoice();
                    else openSearch();
                }
                return true;
            case KeyEvent.KEYCODE_VOICE_ASSIST:
                if (current instanceof SearchPage) {
                    if (down && e.getRepeatCount() == 0) ((SearchPage) current).startVoice();
                    return true;
                }
                return false;
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
                if (down && !topFocus && current != null && current.onKey(code, e, false)) return true;
                return false;
        }
    }

    private void move(int code, KeyEvent e) {
        if (topFocus) {
            int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (dx != 0) {
                int i = Math.max(0, Math.min(TopBar.ITEMS - 1, topBar.focus() + dx));
                if (i != topBar.focus()) {
                    topBar.setFocus(i);
                    click().move();
                    handler.removeCallbacks(dwell);
                    handler.postDelayed(dwell, DWELL_MS);
                }
                return;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && current != null) {
                handler.removeCallbacks(dwell);
                int page = TopBar.pageOf(topBar.focus());
                if (page != root) selectRoot(page);
                topFocus = false;
                topBar.setFocus(-1);
                current.focusFromTop();
                click().move();
            }
            return;
        }
        if (current != null) current.onKey(code, e, false);
    }

    private void ok(KeyEvent e) {
        if (topFocus) {
            handler.removeCallbacks(dwell);
            click().open();
            int page = TopBar.pageOf(topBar.focus());
            selectRoot(page);
            return;
        }
        if (current != null) current.onKey(KeyEvent.KEYCODE_DPAD_CENTER, e, false);
    }

    /** 300 ms on a tab: it opens. */
    private void dwell() {
        if (topFocus && topBar.focus() >= 0) selectRoot(TopBar.pageOf(topBar.focus()));
    }

    private void longOk() {
        if (!okDown || okHeld) return;
        okHeld = true;
        topBar.press(false);
        if (topFocus || current == null) return;
        current.onKey(KeyEvent.KEYCODE_DPAD_CENTER, okEvent, true);
    }

    /** The Back ladder (spec §1.4). */
    private void back() {
        if (overlay.sheetOpen()) {
            overlay.closeSheet();
            return;
        }
        if (!pushed.isEmpty()) {
            ExplorePage p = pushed.get(pushed.size() - 1);
            if (p.back()) return;
            if (p instanceof DetailPage && ((DetailPage) p).fromHome) {
                activity.leave();
                return;
            }
            pop();
            return;
        }
        if (current == null) {
            activity.leave();
            return;
        }
        if (!topFocus) {
            if (current.back()) return;
            focusTopBar();
            return;
        }
        if (root != Catalog.HOME) {
            selectRoot(Catalog.HOME);
            topBar.setFocus(TopBar.itemOf(Catalog.HOME));
            return;
        }
        activity.leave();
    }

    /** A root page's focus goes up to the top bar. */
    void focusTopBar() {
        if (current == null || !current.root) return;
        topFocus = true;
        current.blur();
        topBar.setFocus(TopBar.itemOf(root));
        spoken.focusMoved();
        wake();
    }

    void openSearch() {
        clearPushed();
        selectRoot(Catalog.SEARCH);
        topFocus = false;
        topBar.setFocus(-1);
        if (current != null) current.focusFromTop();
    }

    void startVoice(String language) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            if (i.resolveActivity(activity.getPackageManager()) == null) return;
            activity.startActivityForResult(i, ExploreActivity.REQUEST_VOICE);
        } catch (ActivityNotFoundException | SecurityException e) {
            Safe.log("voice", e);
        }
    }

    private boolean sheetKey(KeyEvent e, int code, boolean down) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) overlay.scrollSheet(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) overlay.scrollSheet(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) okDown = true;
                else if (!down) {
                    if (okDown && !e.isCanceled()) overlay.closeSheet();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) overlay.closeSheet();
                    backDown = false;
                }
                return true;
            default:
                return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
        }
    }

    // MARK: - Actions shared by the pages

    /**
     * Holding OK on a film or show: "Watch on …" (its best installed offer, when known), Quero assistir (add or
     * remove), and "More info" (open, when given). d may be null (details not loaded yet).
     */
    void titleMenu(int kind, int id, String title, String backdropPath, String poster, int year, ExploreModel.Details d,
                   RectF from, Runnable open) {
        if (id <= 0 || (kind != ExploreModel.MOVIE && kind != ExploreModel.TV)) {
            if (open != null) open.run();
            return;
        }
        final ExploreModel.Offer o = bestOffer(d);
        final RectF at = from == null ? new RectF(700 * k, 400 * k, 1220 * k, 500 * k) : new RectF(from);
        final ArrayList<String> rows = new ArrayList<>();
        final ArrayList<Runnable> acts = new ArrayList<>();
        final boolean on = Watchlist.contains(kind, id);
        if (o != null) {
            rows.add(Ui.trf("Watch on {service}", "service", serviceLabel(o)));
            acts.add(() -> watch(d, o, this, at));
        }
        rows.add(on ? Ui.tr("Remove from Want to Watch") : Ui.tr("Add to Want to Watch"));
        final String name = title == null ? "" : title;
        acts.add(() -> {
            String back = backdropPath, post = poster;
            int y = year;
            if (d != null && d.t != null) {
                if (back == null) back = d.t.backdrop;
                if (post == null) post = d.t.poster;
                if (y <= 0) y = d.t.year;
            }
            boolean added = Watchlist.toggle(getContext(), kind, id, name, back, post, y);
            toast(added ? Ui.trf("{title} is in Want to Watch.", "title", name) : Ui.trf("{title} left Want to Watch.", "title", name));
            listChanged();
        });
        if (open != null) {
            rows.add(Ui.tr("More info"));
            acts.add(open);
        }
        StartMenu.Spec spec = new StartMenu.Spec();
        spec.title = name;
        spec.rows = rows.toArray(new String[0]);
        spec.destructive = new boolean[rows.size()];
        spec.submenuOf = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) spec.submenuOf[i] = -1;
        spec.anchor = at;
        spec.listener = row -> {
            try {
                if (row >= 0 && row < acts.size()) acts.get(row).run();
            } catch (RuntimeException e) {
                crashed(this, "title menu", e);
            }
        };
        menu.show(spec);
        spoken.focusMoved();
        wake();
    }

    /** Every page alive hears that Quero assistir changed. */
    void listChanged() {
        for (ExplorePage p : roots) if (p != null) p.watchlistChanged();
        for (ExplorePage p : pushed) if (p != null) p.watchlistChanged();
    }

    /** What StartMenu asks of its root, here. */
    private final class MenuHost implements StartHost {
        @Override public void wake() { ExploreView.this.wake(); }
        @Override public void openItem(HomeApps.Item it, RectF from) {}
        @Override public void openExplore(String target, RectF from) {}
        @Override public void openLink(String pkg, String label, String link, RectF from) {}
        @Override public void resumeWatching(WatchNext.Item it, RectF from) {}
        @Override public void hideWatching(WatchNext.Item it) {}
        @Override public void showMenu(StartMenu.Spec spec) { menu.show(spec); }
        @Override public void toast(String text) { ExploreView.this.toast(text); }
        @Override public void openRecents() {}
        @Override public void openControlCenter() {}
        @Override public void exitShelf(boolean up) {}
        @Override public void a11yChanged() { spoken.focusMoved(); }
        @Override public void fault(String tag, RuntimeException e) { crashed(ExploreView.this, tag, e); }
    }

    void toast(String text) {
        overlay.toast(text);
        wake();
    }

    void showSheet(String title, String text) {
        overlay.openSheet(title, text);
        spoken.focusMoved();
        wake();
    }

    /** Opens the offer's app on the title (Watchmode's link when known, else the app's home with a hint). */
    void watch(ExploreModel.Details d, ExploreModel.Offer o, View from, RectF r) {
        if (o == null || o.service == null) return;
        BrProviders.Snapshot snap = BrProviders.snapshot();
        String label = serviceLabel(o);
        String pkg = snap.pkg(o.service.key);
        if (pkg == null) {
            toast(Ui.trf("{app} isn't installed on this TV.", "app", label));
            return;
        }
        int kind = d != null && d.t != null ? d.t.kind : ExploreModel.MOVIE, id = d != null && d.t != null ? d.t.id : 0;
        String link = null;
        try {
            link = id > 0 ? TitleLinks.get(activity).cached(kind, id, o.service.key) : null;
        } catch (RuntimeException e) {
            Safe.log("links", e);
        }
        click().open();
        HomeApps.Item it = HomeApps.app(pkg, label, link);
        View v = from != null ? from : this;
        RectF rr = r != null ? r : tmp;
        if (r == null) tmp.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f + 1, getHeight() / 2f + 1);
        boolean opened = HomeApps.open(activity, it, v, Math.round(rr.left), Math.round(rr.top),
                Math.round(rr.width()), Math.round(rr.height()));
        if (!opened) {
            toast(Ui.trf("Couldn't open {app}.", "app", label));
            return;
        }
        if (link == null && d != null && d.t != null && d.t.title != null) {
            toast(Ui.trf("Opening {app}: search for “{title}”", "app", label, "title", d.t.title));
        }
    }

    /** Opens the YouTube app on a trailer (no network call of ours). */
    void trailer(String key, View from, RectF r) {
        if (key == null || !key.matches("^[A-Za-z0-9_-]{6,20}$")) return;
        click().open();
        HomeApps.Item it = HomeApps.app("com.google.android.youtube.tv", "YouTube", "https://www.youtube.com/watch?v=" + key);
        RectF rr = r != null ? r : tmp;
        if (r == null) tmp.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f + 1, getHeight() / 2f + 1);
        boolean opened = HomeApps.open(activity, it, from != null ? from : this, Math.round(rr.left), Math.round(rr.top),
                Math.round(rr.width()), Math.round(rr.height()));
        if (!opened) toast(Ui.trf("Couldn't open {app}.", "app", "YouTube"));
    }

    /** OK on a Continue Watching card: resume it in its app. */
    void resumeWatching(WatchNext.Item it, RectF r) {
        click().open();
        boolean opened = false;
        try {
            opened = WatchLaunch.open(activity, it, this, r);
        } catch (RuntimeException e) {
            Safe.log("resume watching", e);
        }
        if (!opened) toast(Ui.trf("Couldn't open {app}.", "app", it.app == null ? it.pkg : it.app));
    }

    /** OK on the "Permitir" card: the TV's own ADB grant, then Android's dialog if that didn't take within 12 s. */
    void requestWatchGrant() {
        click().open();
        if (WatchNext.allowed(activity)) {
            watchChanged();
            return;
        }
        try {
            HomeMode.requestWatchGrant(activity);
        } catch (RuntimeException e) {
            Safe.log("grant", e);
        }
        toast(Ui.tr("Asking for permission…"));
        grantUntil = SystemClock.uptimeMillis() + GRANT_WAIT_MS;
        handler.removeCallbacks(grantPoll);
        handler.postDelayed(grantPoll, HOLD_POLL_MS);
    }

    private void grantPoll() {
        if (destroyed) return;
        try {
            if (WatchNext.allowed(activity)) {
                watchChanged();
                return;
            }
            if (SystemClock.uptimeMillis() < grantUntil) {
                handler.postDelayed(grantPoll, HOLD_POLL_MS);
                return;
            }
            activity.requestPermissions(new String[] {WatchNext.PERMISSION}, ExploreActivity.REQUEST_WATCH);
        } catch (RuntimeException e) {
            Safe.log("grant poll", e);
        }
    }

    // MARK: - Layout and drawing

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

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof ExplorePage.Pos;
    }

    @Override protected void dispatchDraw(Canvas c) {
        int save = c.save();
        try {
            c.drawColor(CardPainter.BG);
            super.dispatchDraw(c);
        } catch (RuntimeException e) {
            crashed(this, "draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    // MARK: - TalkBack (group 1: top bar, 9: the sheet, the rest: the page's)

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    @Override public void items(List<Integer> out) {
        if (overlay.sheetOpen()) {
            out.add(9001);
            return;
        }
        if (current == null) return;
        if (current.root && topBar.getVisibility() == VISIBLE) for (int i = 0; i < TopBar.ITEMS; i++) out.add(1000 + i + 1);
        current.a11yItems(out);
    }

    @Override public String text(int id) {
        if (id == 9001) return overlay.sheetText();
        if (id / 1000 == ExplorePage.G_TOPBAR) {
            int i = id % 1000 - 1;
            return Ui.trf("{name}, tab {i} of {n}", "name", topBar.label(i), "i", i + 1, "n", TopBar.ITEMS);
        }
        return current == null ? "" : current.a11yText(id);
    }

    @Override public boolean bounds(int id, Rect out) {
        if (id == 9001) return overlay.sheetBounds(out);
        if (id / 1000 == ExplorePage.G_TOPBAR) {
            if (!topBar.itemBounds(id % 1000 - 1, tmp)) return false;
            tmp.round(out);
            return true;
        }
        return current != null && current.a11yBounds(id, out);
    }

    @Override public int focused() {
        if (overlay.sheetOpen()) return 9001;
        if (topFocus) return 1000 + topBar.focus() + 1;
        return current == null ? -1 : current.a11yFocused();
    }

    @Override public void focus(int id) {
        if (id == 9001 || current == null) return;
        if (id / 1000 == ExplorePage.G_TOPBAR) {
            if (!topFocus) focusTopBar();
            topBar.setFocus(id % 1000 - 1);
            wake();
            return;
        }
        if (topFocus) {
            topFocus = false;
            topBar.setFocus(-1);
        }
        current.a11yFocus(id);
        wake();
    }

    @Override public void click(int id) {
        if (id == 9001) {
            overlay.closeSheet();
            return;
        }
        if (id / 1000 == ExplorePage.G_TOPBAR) {
            click().open();
            selectRoot(TopBar.pageOf(id % 1000 - 1));
            return;
        }
        if (current != null) current.a11yClick(id);
        wake();
    }

    /** The synopsis sheet and the toasts; a hardware layer only while something shows. */
    private final class Overlay extends View {
        private final float k;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), toastText, sheetTitle;
        private final TextPaint sheetText;
        private final Spring sheet = new Spring(0.38f, 0.90f, 0);
        private StaticLayout sheetLayout;
        private String sheetTitleText = "", sheetBody = "", toastLine = "";
        private float toastW, sheetScroll, toastHalf;
        private long toastAt = -1;
        private boolean open;
        private final RectF box = new RectF();
        private static final float SW = 1100, SH = 640, TOAST_IN = 0.2f, TOAST_HOLD = 2.6f;

        Overlay(Context c, float k) {
            super(c);
            this.k = k;
            toastText = Ui.text(Ui.medium, 26 * k, CardPainter.INK);
            sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, CardPainter.INK);
            sheetText = new TextPaint(Ui.text(Ui.regular, 28 * k, CardPainter.INK));
            toastHalf = Ui.halfCap(toastText);
            setVisibility(INVISIBLE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        boolean sheetOpen() {
            return open;
        }

        String sheetText() {
            return sheetTitleText.isEmpty() ? sheetBody : sheetTitleText + ". " + sheetBody;
        }

        boolean sheetBounds(Rect out) {
            if (!open) return false;
            sheetBox(box);
            box.round(out);
            return true;
        }

        void openSheet(String title, String text) {
            sheetTitleText = title == null ? "" : title;
            sheetBody = text == null ? "" : text;
            sheetLayout = StaticLayout.Builder.obtain(sheetBody, 0, sheetBody.length(), sheetText, Math.round((SW - 112) * k))
                    .setLineSpacing(12 * k, 1).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            sheetScroll = 0;
            open = true;
            sheet.target = 1;
            showing();
            invalidate();
        }

        void closeSheet() {
            if (!open) return;
            open = false;
            sheet.target = 0;
            invalidate();
            spoken.focusMoved();
            ExploreView.this.wake();
        }

        void scrollSheet(int dir) {
            if (sheetLayout == null) return;
            float visible = (SH - 160) * k, max = Math.max(0, sheetLayout.getHeight() - visible);
            float next = Math.max(0, Math.min(max, sheetScroll + dir * 120 * k));
            if (next == sheetScroll) return;
            sheetScroll = next;
            invalidate();
        }

        void toast(String text) {
            if (text == null || text.isEmpty()) return;
            toastLine = text;
            toastW = toastText.measureText(text);
            toastAt = SystemClock.uptimeMillis();
            showing();
            invalidate();
        }

        private void showing() {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setLayerType(LAYER_TYPE_HARDWARE, null);
            }
        }

        boolean step(float dt) {
            boolean busy = sheet.step(dt);
            if (busy) invalidate();
            boolean toastOn = false;
            if (toastAt >= 0) {
                float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
                if (age > TOAST_HOLD + TOAST_IN) {
                    toastAt = -1;
                    invalidate();
                } else {
                    toastOn = true;
                    if (age < TOAST_IN || age > TOAST_HOLD) invalidate();
                }
            }
            if (!toastOn && !open && sheet.value < 0.01f && !busy && getVisibility() == VISIBLE) {
                setVisibility(INVISIBLE);
                setLayerType(LAYER_TYPE_NONE, null);
            }
            return busy || toastOn;
        }

        private void sheetBox(RectF out) {
            float w = SW * k, h = SH * k, cx = 960 * k, cy = 540 * k;
            out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                crashed(this, "overlay draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            float sv = Ui.clamp(sheet.value);
            if (sv > 0.01f && sheetLayout != null) {
                fill.setColor(Ui.alpha(0x99000000, sv));
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
                sheetBox(box);
                float s = 0.94f + 0.06f * sv;
                c.save();
                c.scale(s, s, box.centerX(), box.centerY());
                fill.setColor(Ui.alpha(CardPainter.SHEET, sv));
                c.drawRoundRect(box, 28 * k, 28 * k, fill);
                int ta = Math.round(255 * sv);
                sheetTitle.setAlpha(ta);
                if (!sheetTitleText.isEmpty()) c.drawText(sheetTitleText, box.left + 56 * k, box.top + 88 * k, sheetTitle);
                c.clipRect(box.left, box.top + 120 * k, box.right, box.bottom - 40 * k);
                c.translate(box.left + 56 * k, box.top + 120 * k - sheetScroll);
                sheetText.setAlpha(ta);
                sheetLayout.draw(c);
                c.restore();
            }
            if (toastAt >= 0) {
                float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
                float a = age < TOAST_IN ? age / TOAST_IN : age > TOAST_HOLD ? 1 - (age - TOAST_HOLD) / TOAST_IN : 1;
                a = Ui.clamp(a);
                float w = toastW + 64 * k, h = 64 * k, cx = 960 * k, top = 130 * k;
                fill.setColor(Ui.alpha(0xF22C2C2E, a));
                c.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, fill);
                toastText.setAlpha(Math.round(255 * a));
                c.drawText(toastLine, cx - toastW / 2, top + h / 2 + toastHalf, toastText);
            }
        }
    }
}
