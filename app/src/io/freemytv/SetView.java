package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * The settings screen (SPEC3 §2, §3): a stack of pages drawn on one Canvas, a list on the right and what the focused
 * row is about on the left. It owns the focus, the scroll, the transitions, the keys, the pointer, TalkBack's items
 * and the poll, and is the SetHost the page files talk to. Everything a page gives it runs inside a guard: a throw
 * swaps in the classic view (fault) instead of crashing :home.
 */
final class SetView extends View implements SetHost, SettingsActivity.Screen, Choreographer.FrameCallback {
    private static final int MAX_DEPTH = 12;
    private static final long PANE_MS = 200, LEGACY_TRANSITION_MS = 150;
    private static final float TAP_SLOP = 24, SHAKE_KICK = 330, BUMP_KICK = 450;

    /** One level of the stack; only the top one has a page built. */
    private static final class Entry {
        final String id;
        final SetPage.Factory factory;
        String focusId;
        float scroll;

        Entry(String id, SetPage.Factory factory) {
            this.id = id;
            this.factory = factory;
        }
    }

    private static final class Anim {
        final Spring focus = new Spring(0.24f, 0.86f, 0), knob = new Spring(0.20f, 1.0f, 0);
    }

    /** A page as laid out: its visible entries, their places and every string as drawn. Made at refresh, never in draw. */
    private static final class Shown {
        final SetPage page;
        String title = "";
        int[] kinds = new int[0];
        float[] tops = new float[0];
        SetRow[] rows = new SetRow[0];
        SetPage.Header[] headers = new SetPage.Header[0];
        String[] label = new String[0], value = new String[0], value2 = new String[0], index = new String[0],
                spoken = new String[0];
        boolean[] small = new boolean[0];
        float[] valueW = new float[0];
        Anim[] anim = new Anim[0];
        int[] rowAt = new int[0];     // ordinal → entry
        int rowCount, focus = -1;
        float height;
        boolean left, anyBusy;        // left: onLeave already ran
        final Spring scroll = new Spring(0.36f, 1.0f, 0);
        final HashMap<String, Anim> anims = new HashMap<>();

        Shown(SetPage page) {
            this.page = page;
        }

        SetRow focused() {
            return focus >= 0 && focus < rows.length ? rows[focus] : null;
        }

        int ordinal(int entry) {
            for (int i = 0; i < rowCount; i++) if (rowAt[i] == entry) return i;
            return -1;
        }

        int entryOf(String rowId) {
            if (rowId == null) return -1;
            for (int i = 0; i < rowCount; i++) if (rowId.equals(rows[rowAt[i]].id)) return rowAt[i];
            return -1;
        }
    }

    /** The left pane for one focused row. */
    private static final class Pane {
        String key = "", icon = "info", source = "";
        boolean brand, spin;
        String big;
        StaticLayout step, desc, why;
        float stepTop, descTop, whyTop;   // design units
        SetPage.Preview preview;
    }

    /** The "Unavailable:" label's colour inside the reason's paragraph; set before each draw (no allocation there). */
    private static final class InkSpan extends CharacterStyle implements UpdateAppearance {
        int color;

        @Override public void updateDrawState(TextPaint tp) {
            tp.setColor(color);
        }
    }

    private final SettingsActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SetState state;
    private final SetSheet sheet;
    private final Spoken spoken;
    private final ArrayList<Entry> stack = new ArrayList<>();
    private final String caption;
    private Click click;
    private SetTheme theme;
    private SetIcons big, small;
    private float k = 1;
    private Shown cur, old;
    private Pane pane = new Pane(), paneOld;
    private long paneAt;                       // uptime the cross-fade began (0: none)
    private final InkSpan whyInk = new InkSpan();
    private boolean running, broken, faultPosted, stopped, frameQueued, refreshing, refreshAgain;
    private String pendingFocus;
    private int refreshes, generation, minute = -1;
    private long lastNanos;
    // Motion.
    private final Spring transition = new Spring(0.30f, 1.0f, 1), press = new Spring(0.15f, 0.9f, 0),
            shake = new Spring(0.25f, 0.40f, 0), bump = new Spring(0.25f, 0.6f, 0);
    private int transitionDir;                 // +1 push, −1 pop
    private long transitionAt;                 // legacy: uptime it began
    private float transitionT = 1;             // 0..1
    // Keys and pointer.
    private boolean okDown, backDown;
    private Shown okOn, backOn;
    private float downX, downY;
    private boolean tapping;
    private int hoverEntry = -1;
    private final RectF rect = new RectF();

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                if (!running || broken) return;
                boolean changed = state.poll();
                int m = (int) (System.currentTimeMillis() / 60_000L);
                if (m != minute) {
                    minute = m;
                    changed |= theme == null || SetTheme.dark(getContext()) != theme.dark;
                }
                if (changed || generation != Ui.generation) refresh();
                handler.postDelayed(this, state.pollMs());
            } catch (RuntimeException e) {
                fault("poll", e);
            }
        }
    };

    SetView(SettingsActivity a, String page) {
        super(a);
        activity = a;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription(Ui.APP_NAME);
        state = new SetState(a);
        sheet = new SetSheet(this);
        String version = "";
        try {
            version = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            AppLog.w("settings: version: " + e.getClass().getSimpleName());
        }
        caption = version == null || version.isEmpty() ? Ui.APP_NAME : Ui.APP_NAME + " · " + version;
        int width = a.getResources().getDisplayMetrics().widthPixels;
        k = width > 0 ? width / 1920f : 1;
        generation = Ui.generation;
        makeTheme(SetTheme.dark(a));
        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) {
                try {
                    if (sheet.active()) sheet.items(out);
                    else if (cur != null) for (int i = 1; i <= cur.rowCount; i++) out.add(i);
                } catch (RuntimeException e) {
                    fault("a11y", e);
                }
            }

            @Override public String text(int id) {
                try {
                    if (id > 1000) return sheet.text(id);
                    Shown s = cur;
                    if (s == null || id < 1 || id > s.rowCount) return "";
                    String t = s.spoken[s.rowAt[id - 1]];
                    return t == null ? "" : t;
                } catch (RuntimeException e) {
                    fault("a11y", e);
                    return "";
                }
            }

            @Override public boolean bounds(int id, Rect out) {
                try {
                    if (id > 1000) return sheet.bounds(id, out, k);
                    if (sheet.active()) return false;
                    return rowBounds(id, out);
                } catch (RuntimeException e) {
                    fault("a11y", e);
                    return false;
                }
            }

            @Override public int focused() {
                if (sheet.active()) return sheet.focusedId();
                Shown s = cur;
                return s == null || s.focus < 0 ? -1 : s.ordinal(s.focus) + 1;
            }

            @Override public void focus(int id) {
                try {
                    if (id > 1000) {
                        sheet.focusId(id);
                        return;
                    }
                    Shown s = cur;
                    if (sheet.active() || s == null || id < 1 || id > s.rowCount) return;
                    setFocus(s.rowAt[id - 1], false);
                } catch (RuntimeException e) {
                    fault("a11y", e);
                }
            }

            @Override public void click(int id) {
                try {
                    if (id > 1000) {
                        sheet.click(id);
                        return;
                    }
                    Shown s = cur;
                    if (sheet.active() || s == null || id < 1 || id > s.rowCount) return;
                    setFocus(s.rowAt[id - 1], false);
                    activate();
                } catch (RuntimeException e) {
                    fault("a11y", e);
                }
            }

            @Override public boolean clickable(int id) {
                if (id > 1000) return true;
                Shown s = cur;
                if (s == null || id < 1 || id > s.rowCount) return false;
                return !s.rows[s.rowAt[id - 1]].readOnly();
            }
        });
        reset(page);
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    // MARK: - SettingsActivity.Screen

    @Override public View view() {
        return this;
    }

    @Override public void resume() {
        try {
            resumeNow();
        } catch (RuntimeException e) {
            fault("resume", e);
        }
    }

    private void resumeNow() {
        if (broken || stopped) return;
        boolean again = cur != null && cur.left;
        running = true;
        mark(true);
        if (click == null) click = new Click(getContext());
        state.poll();
        minute = (int) (System.currentTimeMillis() / 60_000L);
        if (again) rebuildTop(true);   // its onLeave ran at the pause: built again, as if entered
        else refresh();
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, state.pollMs());
        requestFocus();
        spoken.readAgain();
        schedule();
    }

    @Override public void pause() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(poll);
        okDown = backDown = tapping = false;
        try {
            SetWatch.commit(this);
            leave(cur);
            state.pause();
        } catch (RuntimeException e) {
            fault("pause", e);
        } finally {
            mark(false);
        }
    }

    @Override public void stop() {
        if (stopped) return;
        try {
            if (running) pause();
        } finally {
            stopped = true;
            running = false;
            handler.removeCallbacksAndMessages(null);
            Choreographer.getInstance().removeFrameCallback(this);
            frameQueued = false;
            try {
                sheet.dismiss();
                Click c = click;
                click = null;
                if (c != null) c.release();
            } catch (RuntimeException e) {
                AppLog.w("settings: stop: " + e.getClass().getSimpleName());
            }
            mark(false);
        }
    }

    /** The mark a Settings that dies while showing leaves behind (SettingsActivity.SetFaults reads it). */
    private void mark(boolean on) {
        try {
            File f = new File(getContext().getFilesDir(), "settings-open");
            if (on) {
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write((android.os.Process.myPid() + " " + System.currentTimeMillis()).getBytes("UTF-8"));
                }
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("settings: mark: " + e.getClass().getSimpleName());
        }
    }

    /** `--es page <id>`, and the reset after a long while away: the root, then that page. */
    void open(String page) {
        if (broken || stopped) return;
        try {
            sheet.closeNow();
            leave(cur);
            reset(page);
            schedule();
        } catch (RuntimeException e) {
            fault("open", e);
        }
    }

    private void reset(String page) {
        stack.clear();
        old = null;
        transitionT = 1;
        transition.snap(1);
        stack.add(new Entry("root", h -> SetPages.build(h, "root")));
        if (page != null && !"root".equals(page) && SetPages.known(page)) {
            stack.add(new Entry(page, h -> SetPages.build(h, page)));
        }
        rebuildTop(true);
    }

    // MARK: - SetHost

    @Override public Context context() {
        return getContext();
    }

    @Override public SettingsActivity activity() {
        return activity;
    }

    @Override public Handler handler() {
        return handler;
    }

    @Override public SetState state() {
        return state;
    }

    @Override public boolean running() {
        return running && !broken && !stopped;
    }

    @Override public void push(String pageId) {
        push(pageId, h -> SetPages.build(h, pageId));
    }

    @Override public void push(String pageId, SetPage.Factory f) {
        if (broken || stopped || f == null || stack.size() >= MAX_DEPTH) return;
        Entry top = stack.get(stack.size() - 1);
        if (cur != null) {
            SetRow r = cur.focused();
            top.focusId = r == null ? null : r.id;
            top.scroll = cur.scroll.target;
        }
        Entry e = new Entry(pageId == null ? "" : pageId, f);
        stack.add(e);
        SetPage p = build(e);
        show(p, 1, null, 0);
        if (click != null) click.open();
        if (p.onEnter != null) p.onEnter.run();
    }

    @Override public void pop() {
        if (broken || stopped || stack.size() <= 1) return;
        leave(cur);
        stack.remove(stack.size() - 1);
        Entry e = stack.get(stack.size() - 1);
        show(build(e), -1, e.focusId, e.scroll);
    }

    @Override public void refresh() {
        if (broken || stopped || cur == null) return;
        if (refreshing) {
            refreshAgain = true;
            return;
        }
        if (generation != Ui.generation) {   // a new language: every string is made again
            generation = Ui.generation;
            rebuildTop(false);
            return;
        }
        refreshing = true;
        try {
            for (int pass = 0; pass < 2; pass++) {
                refreshAgain = false;
                refreshes++;
                boolean dark = SetTheme.dark(getContext());
                if (theme == null || dark != theme.dark) makeTheme(dark);
                Shown s = cur;
                SetRow r = s.focused();
                s.page.refresh(getContext());
                layout(s, r == null ? null : r.id, false);
                if (!refreshAgain) break;
            }
        } finally {
            refreshing = false;
        }
        applyPendingFocus();
        setPane(false);
        spoken.focusMoved();
        schedule();
    }

    @Override public void focus(String rowId) {
        if (rowId == null || cur == null) return;
        if (refreshing) {
            pendingFocus = rowId;
            return;
        }
        int e = cur.entryOf(rowId);
        if (e >= 0) setFocus(e, false);
    }

    @Override public void toast(String text, boolean warning) {
        if (broken || stopped) return;
        sheet.toast(text, warning);
    }

    @Override public void confirm(String title, List<String> body, String action, boolean destructive,
                                  boolean focusAction, Runnable yes) {
        if (broken || stopped) return;
        okDown = false;
        sheet.confirm(title, body, action, destructive, focusAction, guarded("confirm", yes));
        spoken.focusMoved();
    }

    @Override public void askText(String title, String hint, String action, boolean secret, String initial,
                                  Consumer<String> done) {
        if (broken || stopped) return;
        okDown = false;
        sheet.askText(title, hint, action, secret, initial, text -> {
            try {
                if (done != null) done.accept(text);
            } catch (RuntimeException e) {
                fault("text", e);
            }
        });
        spoken.focusMoved();
    }

    @Override public void announce(String text) {
        if (text != null && !text.isEmpty()) announceForAccessibility(text);
    }

    @Override public void preview() {
        if (broken || stopped) return;
        pause();   // the activity's closePreview resumes it
        activity.showPreview();
    }

    @Override public void shake() {
        shake.snap(0);
        shake.velocity = SHAKE_KICK;
        schedule();
    }

    @Override public void fault(String tag, RuntimeException e) {
        // Never its message: a key or a URL could be in it.
        AppLog.w("settings: " + tag + ": " + (e == null ? "fault" : e.getClass().getSimpleName()));
        broken = true;
        running = false;
        handler.removeCallbacks(poll);
        invalidate();
        if (faultPosted) return;
        faultPosted = true;
        handler.post(() -> activity.useClassic(tag));
    }

    // MARK: - What SetSheet asks

    SetTheme theme() {
        return theme;
    }

    void sheetChanged() {
        if (sheet.active()) okDown = false;
        schedule();
    }

    void spokenMoved() {
        spoken.focusMoved();
    }

    boolean keyClick() {
        return spoken.keyClick();
    }

    void clickMove() {
        if (click != null) click.move();
    }

    private Runnable guarded(String tag, Runnable r) {
        if (r == null) return null;
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                fault(tag, e);
            }
        };
    }

    // MARK: - The stack

    private SetPage build(Entry e) {
        SetPage p = e.factory.build(this);
        return p != null ? p : new SetPage(e.id, "", "info");
    }

    private void leave(Shown s) {
        if (s == null || s.left) return;
        s.left = true;
        if (s.page.onLeave != null) s.page.onLeave.run();
    }

    /** The top page made again by its factory (a new language, the screen back after a pause, a reset). */
    private void rebuildTop(boolean enter) {
        if (stack.isEmpty()) return;
        Entry e = stack.get(stack.size() - 1);
        String focusId = e.focusId;
        float scroll = e.scroll;
        if (cur != null && cur.page.id.equals(e.id)) {
            SetRow r = cur.focused();
            focusId = r == null ? null : r.id;
            scroll = cur.scroll.target;
        }
        SetPage p = build(e);
        show(p, 0, focusId, scroll);
        if (enter && p.onEnter != null) p.onEnter.run();
    }

    /** p becomes the page on screen; dir +1 slides it in as a push, −1 as a pop, 0 swaps it in place. */
    private void show(SetPage p, int dir, String focusId, float scroll) {
        boolean dark = SetTheme.dark(getContext());
        if (theme == null || dark != theme.dark) makeTheme(dark);
        generation = Ui.generation;
        Shown s = new Shown(p);
        Shown before = cur;
        cur = s;
        boolean wasRefreshing = refreshing;
        refreshing = true;
        try {
            refreshes++;
            p.refresh(getContext());
            s.scroll.snap(Math.max(0, scroll));
            layout(s, focusId, true);
        } finally {
            refreshing = wasRefreshing;
        }
        if (p.preview != null) p.preview.layout(theme, k);
        if (dir != 0 && before != null) {
            old = before;
            transitionDir = dir;
            transitionAt = SystemClock.uptimeMillis();
            transitionT = 0;
            transition.snap(0);
            transition.target = 1;
        } else {
            old = null;
            transitionT = 1;
            transition.snap(1);
        }
        okDown = backDown = false;
        hoverEntry = -1;
        bump.snap(0);
        shake.snap(0);
        if (!wasRefreshing) applyPendingFocus();
        setPane(dir != 0);
        if (dir != 0) announce(p.title);
        if (spoken != null) spoken.focusMoved();
        schedule();
    }

    private void applyPendingFocus() {
        String id = pendingFocus;
        pendingFocus = null;
        if (id == null || cur == null) return;
        int e = cur.entryOf(id);
        if (e >= 0) setFocus(e, true);
    }

    // MARK: - Layout (at refresh, never in draw)

    private void makeTheme(boolean dark) {
        theme = SetTheme.make(k, dark);
        big = SetIcons.prepare(SetTheme.ICON_SIZE * k);
        small = SetIcons.prepare(SetTheme.SMALL_ICON * k);
        if (cur != null) {
            SetRow r = cur.focused();
            layout(cur, r == null ? null : r.id, false);
            if (cur.page.preview != null) cur.page.preview.layout(theme, k);
            pane = new Pane();
            paneOld = null;
            setPane(false);
        }
        old = null;
        transitionT = 1;
        transition.snap(1);
        if (sheet != null) sheet.layout();
        invalidate();
    }

    private void layout(Shown s, String keepId, boolean first) {
        SetTheme t = theme;
        List<Object> entries = s.page.entries;
        int oldOrdinal = s.focus < 0 ? -1 : s.ordinal(s.focus);
        // What shows: hidden rows are out, and a gap never leads, ends or doubles.
        int n = 0, max = entries.size();
        int[] kinds = new int[max];
        Object[] what = new Object[max];
        boolean gapBefore = true;
        for (int i = 0; i < max; i++) {
            Object e = entries.get(i);
            if (e instanceof SetRow) {
                if (((SetRow) e).hidden) continue;
                kinds[n] = SetLayout.ROW;
                gapBefore = false;
            } else if (e instanceof SetPage.Header) {
                kinds[n] = SetLayout.HEADER;
                gapBefore = false;
            } else {
                if (gapBefore) continue;
                kinds[n] = SetLayout.GAP_ENTRY;
                gapBefore = true;
            }
            what[n++] = e;
        }
        while (n > 0 && kinds[n - 1] == SetLayout.GAP_ENTRY) n--;
        s.kinds = java.util.Arrays.copyOf(kinds, n);
        s.tops = new float[n];
        s.rows = new SetRow[n];
        s.headers = new SetPage.Header[n];
        s.label = new String[n];
        s.value = new String[n];
        s.value2 = new String[n];
        s.index = new String[n];
        s.spoken = new String[n];
        s.small = new boolean[n];
        s.valueW = new float[n];
        s.anim = new Anim[n];
        s.rowAt = new int[n];
        s.rowCount = 0;
        s.anyBusy = false;
        s.height = SetLayout.tops(s.kinds, s.tops);
        s.title = SetTheme.fit(s.page.title, t.paneTitle, SetTheme.TITLE_W * k);
        int options = 0, option = 0;
        for (int i = 0; i < n; i++) if (what[i] instanceof SetRow && ((SetRow) what[i]).kind == SetRow.OPTION) options++;
        for (int i = 0; i < n; i++) {
            if (what[i] instanceof SetPage.Header) {
                s.headers[i] = (SetPage.Header) what[i];
                s.label[i] = SetTheme.fit(s.headers[i].text, t.header, 440 * k);
                s.value[i] = s.headers[i].colA == null ? "" : SetTheme.fit(s.headers[i].colA, t.headerRight, 180 * k);
                s.value2[i] = s.headers[i].colB == null ? "" : SetTheme.fit(s.headers[i].colB, t.headerRight, 230 * k);
                continue;
            }
            if (!(what[i] instanceof SetRow)) continue;
            SetRow r = (SetRow) what[i];
            s.rows[i] = r;
            s.rowAt[s.rowCount++] = i;
            s.anyBusy |= r.busy;
            Anim a = s.anims.get(r.id);
            if (a == null) {
                a = new Anim();
                a.knob.snap(r.on ? 1 : 0);
                s.anims.put(r.id, a);
            }
            s.anim[i] = a;
            if (r.kind == SetRow.OPTION) option++;
            fitRow(s, i, r, t);
            s.spoken[i] = spokenText(r, option, options);
        }
        // The focus: kept by id; a row that went away hands it to the nearest one above.
        int focus = s.entryOf(keepId);
        if (focus < 0 && s.rowCount > 0) {
            if (first || oldOrdinal < 0) {
                focus = s.rowAt[0];
                if (keepId == null) {
                    for (int i = 0; i < s.rowCount; i++) {
                        SetRow r = s.rows[s.rowAt[i]];
                        if (r.kind == SetRow.OPTION && r.on) {
                            focus = s.rowAt[i];
                            break;
                        }
                    }
                }
            } else {
                focus = s.rowAt[Math.max(0, Math.min(s.rowCount - 1, oldOrdinal - 1))];
            }
        }
        s.focus = focus;
        if (first) {
            for (int i = 0; i < s.rowCount; i++) s.anim[s.rowAt[i]].focus.snap(s.rowAt[i] == focus ? 1 : 0);
            float at = SetLayout.clampScroll(s.scroll.target, s.height);
            if (focus >= 0) {
                at = keepId == null && s.rows[focus].kind == SetRow.OPTION
                        ? SetLayout.centred(s.tops[focus], s.height)
                        : SetLayout.scrollFor(s.tops[focus], at, s.height);
            }
            s.scroll.snap(at);
        } else {
            float at = SetLayout.clampScroll(s.scroll.target, s.height);
            s.scroll.target = focus >= 0 ? SetLayout.scrollFor(s.tops[focus], at, s.height) : at;
        }
    }

    /** The row's strings cut to their share of the width (§2.1): the title up to 62%, the value the rest. */
    private void fitRow(Shown s, int i, SetRow r, SetTheme t) {
        boolean off = r.why != null;
        String value = off ? Ui.tr("Unavailable") : r.kind == SetRow.TOGGLE || r.kind == SetRow.ORDERED ? "" : r.value;
        if (value == null) value = "";
        String title = r.title == null ? "" : r.title;
        s.index[i] = r.kind == SetRow.ORDERED && r.order > 0 ? String.valueOf(r.order) : "";
        if (r.kind == SetRow.METRIC && !off) {
            s.small[i] = true;
            s.label[i] = SetTheme.fit(title, t.metricTitle, 330 * k);
            s.value[i] = SetTheme.fit(value, t.valueNumbers, 170 * k);
            s.value2[i] = SetTheme.fit(r.value2 == null ? "" : r.value2, t.valueNumbers, 230 * k);
            return;
        }
        s.value2[i] = "";
        float inner = (SetLayout.LIST_R - SetLayout.LIST_L - 60) * k;
        float avail = (SetLayout.LIST_R - SetLayout.LIST_L - 30 - valueInset(r)) * k;
        if (r.kind == SetRow.ORDERED) avail -= 50 * k;
        if (r.busy) avail -= 48 * k;
        if (r.badge) avail -= 50 * k;
        float wanted = value.isEmpty() ? 0 : t.value.measureText(value);
        float titleMax = value.isEmpty() ? avail : Math.max(Math.min(avail, inner * 0.62f), avail - wanted - 24 * k);
        boolean smaller = t.rowTitle.measureText(title) > titleMax;
        s.small[i] = smaller;
        s.label[i] = smaller ? SetTheme.fit(title, t.rowTitleSmall, titleMax) : title;
        float titleW = (smaller ? t.rowTitleSmall : t.rowTitle).measureText(s.label[i]);
        s.value[i] = value.isEmpty() ? "" : SetTheme.fit(value, t.value, Math.max(0, avail - titleW - 24 * k));
        s.valueW[i] = s.value[i].isEmpty() ? 0 : t.value.measureText(s.value[i]);
    }

    /** How far from the row's right edge its value ends (design units). */
    private static float valueInset(SetRow r) {
        if (r.why == null && r.kind == SetRow.TOGGLE) return 110;
        switch (r.kind) {
            case SetRow.NAV:
            case SetRow.EXTERNAL:
            case SetRow.OPTION:
            case SetRow.ORDERED:
                return 66;
            case SetRow.CHOICE:
                return r.twoValues ? 30 : 66;
            case SetRow.TOGGLE:
                return 110;
            default:
                return 30;
        }
    }

    /** The row as TalkBack reads it (§3.4). */
    private String spokenText(SetRow r, int option, int options) {
        StringBuilder b = new StringBuilder(r.title == null ? "" : r.title);
        switch (r.kind) {
            case SetRow.TOGGLE:
                b.append(", ").append(r.on ? Ui.tr("On") : Ui.tr("Off"));
                break;
            case SetRow.OPTION:
                if (r.on) b.append(Ui.tr(", selected"));
                b.append(", ").append(Ui.trf("{i} of {n}", "i", option, "n", options));
                break;
            case SetRow.ORDERED:
                if (r.on) b.append(Ui.tr(", selected"));
                if (r.order > 0) b.append(", ").append(r.order);
                break;
            case SetRow.EXTERNAL:
                b.append(Ui.tr(", opens the TV's settings"));
                break;
            case SetRow.METRIC:
                b.setLength(0);
                b.append(Ui.trf("{label}: Original {a}, {app} {b}", "label", r.title, "a", r.value,
                        "app", Ui.APP_SHORT, "b", r.value2));
                break;
            default:
                if (r.value != null && !r.value.isEmpty()) b.append(", ").append(r.value);
                break;
        }
        if (r.why != null) b.append(", ").append(Ui.tr("Unavailable")).append(", ").append(r.why);
        if (r.about != null && !r.about.isEmpty()) b.append(". ").append(r.about);
        return b.toString();
    }

    /** The left pane for the focused row; `fade` cross-fades to it even when the row is the same (a new page). */
    private void setPane(boolean fade) {
        Shown s = cur;
        if (s == null || theme == null) return;
        SetTheme t = theme;
        SetRow r = s.focused();
        Pane p = new Pane();
        p.key = s.page.id + "/" + (r == null ? "" : r.id);
        p.preview = s.page.preview;
        p.icon = r != null && r.icon != null ? r.icon : s.page.icon;
        p.brand = s.page.brand || r != null && r.brand;
        p.spin = r != null && r.busy && (r.kind == SetRow.PROGRESS || "house".equals(p.icon));
        String about = r != null && r.about != null && !r.about.isEmpty() ? r.about : s.page.about;
        if (about == null) about = "";
        String step = r != null && r.kind == SetRow.PROGRESS && r.title != null ? r.title : "";
        String why = r != null && r.why != null ? r.why : "";
        p.source = p.icon + '\n' + p.brand + '\n' + p.spin + '\n' + step + '\n' + why + '\n' + about;
        if (!fade && p.key.equals(pane.key) && p.source.equals(pane.source) && p.preview == pane.preview) return;
        // A short first paragraph with a number in it (the time left, Trakt's code) is the big number.
        int cut = about.indexOf("\n\n");
        if (cut > 0 && cut <= 28) {
            String lead = about.substring(0, cut).trim();
            boolean digit = false;
            for (int i = 0; i < lead.length(); i++) digit |= Character.isDigit(lead.charAt(i));
            if (digit && t.bigNumber.measureText(lead) <= SetTheme.DESC_W * k) {
                p.big = lead;
                about = about.substring(cut + 2).trim();
            }
        }
        float y = p.big != null ? SetTheme.DESC_TOP_UNDER_BIG : SetTheme.DESC_TOP;
        if (!step.isEmpty()) {
            p.step = SetTheme.layout(step, t.step, SetTheme.DESC_W * k, 2, Layout.Alignment.ALIGN_CENTER, 40 * k);
            p.stepTop = y;
            y += p.step.getHeight() / k + 12;
        }
        boolean long8 = r != null && (r.id.equals("mode.result") || r.kind == SetRow.PROGRESS);
        int lines = why.isEmpty() ? (long8 ? 8 : 7) : 4;
        if (p.step != null) lines = Math.min(lines, 5);
        if (!about.isEmpty()) {
            p.desc = SetTheme.layout(about, t.desc, SetTheme.DESC_W * k, lines, Layout.Alignment.ALIGN_CENTER,
                    SetTheme.DESC_LINE * k);
            p.descTop = y;
            y += p.desc.getHeight() / k + 20;
        }
        if (!why.isEmpty()) {
            String label = Ui.tr("Unavailable:");
            SpannableString text = new SpannableString(label + " " + why);
            text.setSpan(whyInk, 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            p.why = SetTheme.layout(text, t.desc, SetTheme.DESC_W * k, 3, Layout.Alignment.ALIGN_CENTER,
                    SetTheme.DESC_LINE * k);
            p.whyTop = y;
        }
        boolean sameRow = p.key.equals(pane.key);
        if (fade || !sameRow) {
            paneOld = pane;
            paneAt = SystemClock.uptimeMillis();
        }
        pane = p;
        schedule();
    }

    // MARK: - Focus and actions

    private void setFocus(int entry, boolean snap) {
        Shown s = cur;
        if (s == null || entry < 0 || entry >= s.rows.length || s.rows[entry] == null) return;
        s.focus = entry;
        float at = SetLayout.scrollFor(s.tops[entry], SetLayout.clampScroll(s.scroll.target, s.height), s.height);
        if (snap) s.scroll.snap(at);
        else s.scroll.target = at;
        setPane(false);
        spoken.focusMoved();
        schedule();
    }

    private void move(int dir) {
        Shown s = cur;
        if (s == null || s.rowCount == 0) return;
        int at = s.ordinal(s.focus), next = at + dir;
        if (at < 0) next = 0;
        if (next < 0 || next >= s.rowCount) {   // no wrap: the list gives a little and comes back
            bump.snap(0);
            bump.velocity = -dir * BUMP_KICK;
            schedule();
            return;
        }
        if (click != null) click.move();
        setFocus(s.rowAt[next], false);
    }

    private boolean locked(SetRow r) {
        return r.why != null && !r.allowWhenUnavailable;
    }

    private void activate() {
        Shown s = cur;
        SetRow r = s == null ? null : s.focused();
        if (r == null) return;
        if (locked(r)) {
            shake();
            toast(r.why, true);
            return;
        }
        if (r.readOnly() || r.onOk == null) {   // there to be read: a flash says the press arrived
            s.anim[s.focus].focus.value = 0.55f;
            schedule();
            return;
        }
        if (click != null && (r.kind == SetRow.TOGGLE || r.kind == SetRow.CHOICE && r.twoValues)) click.open();
        run(s, r.onOk);
    }

    private void step(int dir) {
        Shown s = cur;
        SetRow r = s == null ? null : s.focused();
        if (r == null || r.onStep == null || locked(r)) return;
        if (r.kind != SetRow.TOGGLE && r.kind != SetRow.CHOICE && r.kind != SetRow.ORDERED) return;
        if (click != null) {
            if (r.kind == SetRow.TOGGLE) click.open();
            else click.move();
        }
        run(s, () -> r.onStep.accept(dir));
    }

    /** A row's lambda; the page is refreshed after it unless it did that itself (or left the page). */
    private void run(Shown s, Runnable action) {
        int before = refreshes;
        action.run();
        if (cur == s && before == refreshes && !broken) refresh();
    }

    // MARK: - Keys

    @Override public boolean onKey(KeyEvent e) {
        try {
            return key(e);
        } catch (RuntimeException ex) {
            fault("key", ex);
            return true;
        }
    }

    private boolean key(KeyEvent e) {
        if (broken || stopped || cur == null) return false;
        if (sheet.active()) {
            boolean taken = sheet.key(e);
            schedule();
            return taken;
        }
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okOn = cur;
                        schedule();
                    }
                } else {
                    boolean go = okDown && okOn == cur && !e.isCanceled() && spoken.keyClick();
                    okDown = false;
                    okOn = null;
                    schedule();
                    if (go) activate();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (stack.size() <= 1 && !(backDown && !down)) return false;   // at the root Back leaves
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        backDown = true;
                        backOn = cur;
                    }
                } else {
                    boolean go = backDown && backOn == cur && !e.isCanceled();
                    backDown = false;
                    backOn = null;
                    if (go) pop();
                }
                return true;
            default:
                return false;   // Menu, the media keys
        }
    }

    // MARK: - Pointer (a mouse, an air mouse)

    @Override public boolean onTouchEvent(MotionEvent e) {
        try {
            if (broken || stopped || cur == null) return false;
            float x = e.getX() / k, y = e.getY() / k;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    downY = y;
                    tapping = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(x - downX) > TAP_SLOP || Math.abs(y - downY) > TAP_SLOP) tapping = false;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (tapping && Math.abs(x - downX) <= TAP_SLOP && Math.abs(y - downY) <= TAP_SLOP) tap(x, y);
                    tapping = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    tapping = false;
                    return true;
                default:
                    return true;
            }
        } catch (RuntimeException ex) {
            fault("touch", ex);
            return true;
        }
    }

    private void tap(float x, float y) {
        if (sheet.active()) {
            sheet.tap(x, y);
            schedule();
            return;
        }
        Shown s = cur;
        int hit = SetLayout.hit(x, y, s.kinds, s.tops, s.scroll.value);
        if (hit < 0) return;
        if (hit != s.focus) {
            if (click != null) click.move();
            setFocus(hit, false);
        }
        activate();
    }

    @Override public boolean onHoverEvent(MotionEvent e) {
        try {
            if (broken || stopped || cur == null) return false;
            int action = e.getActionMasked();
            if (action != MotionEvent.ACTION_HOVER_MOVE && action != MotionEvent.ACTION_HOVER_ENTER) {
                if (action == MotionEvent.ACTION_HOVER_EXIT) hoverEntry = -1;
                return true;
            }
            float x = e.getX() / k, y = e.getY() / k;
            if (sheet.active()) {
                sheet.hover(x, y);
                return true;
            }
            Shown s = cur;
            int hit = SetLayout.hit(x, y, s.kinds, s.tops, s.scroll.value);
            if (hit == hoverEntry) return true;   // only when the pointer comes onto another row
            hoverEntry = hit;
            if (hit < 0 || hit == s.focus || transitionT < 1) return true;
            // Never onto a row that would scroll the list: the next one would slide under the pointer.
            float at = s.scroll.target;
            if (SetLayout.scrollFor(s.tops[hit], at, s.height) != at) return true;
            if (click != null) click.move();
            setFocus(hit, false);
            return true;
        } catch (RuntimeException ex) {
            fault("hover", ex);
            return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        try {
            if (broken || stopped || cur == null) return false;
            if ((e.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0
                    && e.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0 && !sheet.active()) move(v > 0 ? -1 : 1);
                return true;
            }
            return super.onGenericMotionEvent(e);
        } catch (RuntimeException ex) {
            fault("wheel", ex);
            return true;
        }
    }

    /** TalkBack's box for a row: where it is drawn; one past the window, a 24 px strip at that edge. */
    private boolean rowBounds(int id, Rect out) {
        Shown s = cur;
        if (s == null || id < 1 || id > s.rowCount) return false;
        float top = s.tops[s.rowAt[id - 1]] - s.scroll.value, bottom = top + SetLayout.ROW_H;
        if (bottom < SetLayout.VIEW_TOP) {
            top = SetLayout.VIEW_TOP;
            bottom = top + 24;
        } else if (top > SetLayout.VIEW_BOTTOM) {
            bottom = SetLayout.VIEW_BOTTOM;
            top = bottom - 24;
        }
        out.set(Math.round(SetLayout.LIST_L * k), Math.round(top * k), Math.round(SetLayout.LIST_R * k),
                Math.round(bottom * k));
        return true;
    }

    // MARK: - Frames

    private void schedule() {
        invalidate();
        if (frameQueued || stopped || broken) return;
        frameQueued = true;
        lastNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        frameQueued = false;
        try {
            if (stopped || broken) return;
            float dt = lastNanos == 0 ? 1 / 60f : (nanos - lastNanos) / 1e9f;
            dt = Math.max(1 / 60f, Math.min(0.05f, dt));
            boolean moving = advance(dt);
            invalidate();
            if (moving && isAttachedToWindow()) {
                lastNanos = nanos;
                frameQueued = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        } catch (RuntimeException e) {
            fault("frame", e);
        }
    }

    /** One step of everything that moves; true while a frame more is needed. */
    private boolean advance(float dt) {
        boolean moving = false;
        long now = SystemClock.uptimeMillis();
        Shown s = cur;
        if (s != null) {
            for (int i = 0; i < s.rowCount; i++) {
                int e = s.rowAt[i];
                Anim a = s.anim[e];
                a.focus.target = e == s.focus ? 1 : 0;
                moving |= a.focus.step(dt);
                a.knob.target = s.rows[e].on ? 1 : 0;
                moving |= a.knob.step(dt);
            }
            moving |= s.scroll.step(dt);
            moving |= s.anyBusy;                                       // a spinner on a row
            moving |= s.page.preview != null && s.page.preview.moving();
        }
        moving |= pane.spin;
        if (old != null) {
            if (Ui.LEGACY) {
                transitionT = Math.min(1, (now - transitionAt) / (float) LEGACY_TRANSITION_MS);
            } else {
                transition.step(dt);
                transitionT = transition.settled() ? 1 : Math.max(0, Math.min(1, transition.value));
            }
            if (transitionT >= 1) old = null;
            else moving = true;
        }
        if (paneOld != null) {
            if (now - paneAt >= PANE_MS) paneOld = null;
            else moving = true;
        }
        boolean pressed = sheet.active() ? sheet.pressing() : okDown && okOn == cur;
        press.target = pressed ? 1 : 0;
        moving |= press.step(dt);
        moving |= shake.step(dt);
        moving |= bump.step(dt);
        moving |= sheet.step(dt);
        return moving;
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        try {
            if (w <= 0 || broken) return;
            float scale = w / 1920f;
            if (theme != null && Math.abs(scale - k) < 0.0005f) return;
            k = scale;
            makeTheme(SetTheme.dark(getContext()));
        } catch (RuntimeException e) {
            fault("size", e);
        }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!stopped && !broken) schedule();
    }

    // MARK: - Draw (no allocation) -- settings-draw-begin

    @Override protected void onDraw(Canvas c) {
        SetTheme t = theme;
        if (t == null) return;
        if (broken) {
            c.drawColor(t.bg);
            return;
        }
        int save = c.save();
        try {
            c.drawRect(0, 0, getWidth(), getHeight(), t.wall);
            Shown s = cur;
            if (s == null) return;
            float in = old != null ? transitionT : 1;
            drawTitle(c, t, s, in);
            drawPane(c, t, s, in);
            drawLists(c, t, s, in);
            if ("root".equals(s.page.id)) {
                t.caption.setColor(SetTheme.alpha(t.title, in));
                c.drawText(caption, SetTheme.PANE_CX * k, SetTheme.CAPTION_BASELINE * k, t.caption);
            } else if (old != null && "root".equals(old.page.id)) {
                t.caption.setColor(SetTheme.alpha(t.title, 1 - in));
                c.drawText(caption, SetTheme.PANE_CX * k, SetTheme.CAPTION_BASELINE * k, t.caption);
            }
            sheet.draw(c, t, small, sheet.pressing(), press.value);
        } catch (RuntimeException e) {
            fault("draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void drawTitle(Canvas c, SetTheme t, Shown s, float in) {
        if (sheet.toastShowing()) return;   // the toast sits where the title is
        float x = 960 * k, y = SetTheme.TITLE_BASELINE * k;
        if (old != null) {
            t.paneTitle.setColor(SetTheme.alpha(t.title, 1 - in));
            c.drawText(old.title, x, y, t.paneTitle);
        }
        t.paneTitle.setColor(SetTheme.alpha(t.title, in));
        c.drawText(s.title, x, y, t.paneTitle);
    }

    private void drawPane(Canvas c, SetTheme t, Shown s, float in) {
        float p = paneOld == null ? 1 : Math.max(0, Math.min(1, (SystemClock.uptimeMillis() - paneAt) / (float) PANE_MS));
        Pane now = pane, was = paneOld;
        // The platter stays where it is; only what it holds (and its tint) changes.
        float platter = (now.preview == null ? p : 0) + (was != null && was.preview == null ? 1 - p : 0);
        float brand = (now.preview == null && now.brand ? p : 0) + (was != null && was.preview == null && was.brand ? 1 - p : 0);
        if (platter > 0.004f) {
            float l = SetTheme.PLATTER_L * k, tp = SetTheme.PLATTER_T * k, r = SetTheme.PLATTER_R * k,
                    b = SetTheme.PLATTER_B * k, radius = SetTheme.PLATTER_RADIUS * k;
            if (!t.dark) {
                rect.set(l, tp + t.platterShadowDy * k, r, b + t.platterShadowDy * k);
                t.platterShadow.setColor(SetTheme.alpha(0x1F000000, platter));
                t.shadows.draw(c, rect, radius, 40 * k, t.platterShadow);
            }
            rect.set(l, tp, r, b);
            t.platter.setAlpha(Math.round(255 * Math.min(1, platter)));
            c.drawRoundRect(rect, radius, radius, t.platter);
            if (brand > 0.004f) {
                t.brand.setAlpha(Math.round(255 * Math.min(1, brand)));
                c.drawRoundRect(rect, radius, radius, t.brand);
            }
            float rim = t.dark ? platter : brand;
            if (rim > 0.004f) {
                t.rim.setAlpha(Math.round(255 * Math.min(1, rim)));
                c.drawRoundRect(rect, radius, radius, t.rim);
            }
        }
        if (was != null && p < 0.5f) drawPaneContent(c, t, was, 1 - 2 * p, 1);
        float a = was == null ? 1 : Math.max(0, 2 * p - 1);
        if (a > 0) drawPaneContent(c, t, now, a, was == null ? 1 : 0.96f + 0.04f * a);
    }

    private void drawPaneContent(Canvas c, SetTheme t, Pane p, float a, float scale) {
        if (p.preview != null) {
            p.preview.draw(c, t, k, a);
            return;
        }
        float cx = SetTheme.PANE_CX * k, cy = SetTheme.ICON_CY * k;
        int ink = SetTheme.alpha(p.brand ? t.white : t.ink, a);
        int save = c.save();
        c.scale(scale, scale, cx, cy);
        big.draw(c, p.icon, cx, cy, ink);
        c.restoreToCount(save);
        if (p.spin) {
            t.spinnerBig.setColor(SetTheme.alpha(p.brand ? t.white : t.secondary, a));
            Ui.spinner(c, cx, cy, 150 * k, spinSeconds(), t.spinnerBig);
        }
        if (p.big != null) {
            t.bigNumber.setColor(SetTheme.alpha(t.ink, a));
            c.drawText(p.big, cx, SetTheme.BIG_BASELINE * k, t.bigNumber);
        }
        if (p.step != null) drawText(c, p.step, cx, p.stepTop * k, t.step, SetTheme.alpha(t.ink, a));
        if (p.desc != null) drawText(c, p.desc, cx, p.descTop * k, t.desc, SetTheme.alpha(t.secondary, a));
        if (p.why != null) {
            whyInk.color = SetTheme.alpha(t.ink, a);
            drawText(c, p.why, cx, p.whyTop * k, t.desc, SetTheme.alpha(t.secondary, a));
        }
    }

    private static void drawText(Canvas c, StaticLayout l, float cx, float top, TextPaint paint, int color) {
        int save = c.save();
        c.translate(cx - l.getWidth() / 2f, top);
        paint.setColor(color);
        l.draw(c);
        c.restoreToCount(save);
    }

    private static float spinSeconds() {
        return (SystemClock.uptimeMillis() % 600_000L) / 1000f * 1.3f;
    }

    private void drawLists(Canvas c, SetTheme t, Shown s, float in) {
        int save = c.save();
        try {
            boolean scrolls = SetLayout.maxScroll(s.height) > 0 || old != null && SetLayout.maxScroll(old.height) > 0;
            c.clipRect((SetLayout.LIST_L - 70) * k, SetLayout.VIEW_TOP * k, getWidth(),
                    scrolls ? SetLayout.VIEW_BOTTOM * k : getHeight());
            if (old != null) {
                float x = Ui.LEGACY ? 0 : -80 * in * transitionDir;
                drawList(c, t, old, 1 - in, x, false);
                x = Ui.LEGACY ? 0 : 80 * (1 - in) * transitionDir;
                drawList(c, t, s, in, x, true);
            } else {
                drawList(c, t, s, 1, 0, true);
            }
        } finally {
            c.restoreToCount(save);
        }
    }

    private void drawList(Canvas c, SetTheme t, Shown s, float alpha, float dx, boolean live) {
        if (alpha <= 0.004f) return;
        float scroll = s.scroll.value - (live ? bump.value : 0);
        boolean scrolled = s.scroll.value > 0.5f;
        int n = s.kinds.length;
        for (int i = 0; i < n; i++) {
            if (i == s.focus || s.kinds[i] == SetLayout.GAP_ENTRY) continue;
            float top = s.tops[i] - scroll;
            if (top > 1080 || top + SetLayout.ROW_H < SetLayout.VIEW_TOP - 20) continue;
            float a = alpha * SetLayout.alpha(s.tops[i], scroll, scrolled);
            if (s.kinds[i] == SetLayout.HEADER) drawHeader(c, t, s, i, top, a, dx);
            else drawRow(c, t, s, i, top, a, dx, false, live);
        }
        if (s.focus >= 0 && s.focus < n) {   // the focused row last: its shadow and its growth go over its neighbours
            float top = s.tops[s.focus] - scroll;
            float a = alpha * SetLayout.alpha(s.tops[s.focus], scroll, scrolled);
            drawRow(c, t, s, s.focus, top, a, dx, true, live);
        }
    }

    private void drawHeader(Canvas c, SetTheme t, Shown s, int i, float top, float a, float dx) {
        float left = (SetLayout.LIST_L + dx) * k, base = (top + 40) * k;
        t.header.setColor(SetTheme.alpha(t.title, a));
        c.drawText(s.label[i], left + 30 * k, base, t.header);
        t.headerRight.setColor(SetTheme.alpha(t.title, a));
        if (!s.value[i].isEmpty()) c.drawText(s.value[i], left + 520 * k, base, t.headerRight);
        if (!s.value2[i].isEmpty()) c.drawText(s.value2[i], left + 770 * k, base, t.headerRight);
    }

    private void drawRow(Canvas c, SetTheme t, Shown s, int i, float top, float a, float dx, boolean focused,
                         boolean live) {
        SetRow r = s.rows[i];
        Anim anim = s.anim[i];
        if (r == null || anim == null || a <= 0.004f) return;
        float f = Math.max(0, Math.min(1, anim.focus.value));
        if (focused && live) dx += shake.value;
        float left = (SetLayout.LIST_L + dx) * k, right = (SetLayout.LIST_R + dx) * k, y = top * k,
                bottom = (top + SetLayout.ROW_H) * k, cy = (y + bottom) / 2, radius = SetLayout.ROW_H / 2 * k;
        boolean off = r.why != null;
        if (f > 0.01f) {   // the shadow at the resting size, under the scale
            rect.set(left, y + t.focusShadowDy * k, right, bottom + t.focusShadowDy * k);
            t.rowShadow.setColor(SetTheme.alpha(t.focusShadow, a * f));
            t.shadows.draw(c, rect, radius, (t.dark ? 34 : 30) * k, t.rowShadow);
        }
        float scale = 1 + 0.04f * f;
        if (focused && live && press.value > 0 && !sheet.shown()) scale += (0.985f - scale) * Math.min(1, press.value);
        int save = c.save();
        c.scale(scale, scale, (left + right) / 2, cy);
        rect.set(left, y, right, bottom);
        t.fill.setShader(null);
        t.fill.setColor(SetTheme.alpha(SetTheme.blend(t.row, t.focus, f), a));
        c.drawRoundRect(rect, radius, radius, t.fill);
        if (f < 0.99f) {   // the resting capsule's hairline
            float edge = t.dark ? y + 0.5f * k : bottom - 0.5f * k;
            float w = t.stroke.getStrokeWidth();
            t.stroke.setStrokeWidth(Math.max(1, k));
            t.stroke.setColor(SetTheme.alpha(t.rowEdge, a * (1 - f)));
            c.drawLine(left + radius, edge, right - radius, edge, t.stroke);
            t.stroke.setStrokeWidth(w);
        }
        int ink = SetTheme.alpha(r.kind == SetRow.DESTRUCTIVE ? t.red : SetTheme.blend(t.ink, t.focusInk, f),
                a * (off ? 0.45f : 1));
        int soft = SetTheme.blend(t.secondary, t.focusSecondary, f);
        if (!off && r.tone == SetRow.TONE_GOOD) soft = t.green;
        else if (!off && r.tone == SetRow.TONE_WARN) soft = t.orange;
        soft = SetTheme.alpha(soft, a * (off ? 0.6f : 1));
        int accessory = SetTheme.alpha(SetTheme.blend(t.chevron, t.focusChevron, f), a * (off ? 0.6f : 1));

        if (r.kind == SetRow.METRIC && !off) {
            t.metricTitle.setColor(ink);
            c.drawText(s.label[i], left + 30 * k, cy + t.metricHalf, t.metricTitle);
            t.valueNumbers.setColor(soft);
            c.drawText(s.value[i], left + 520 * k, cy + t.valueHalf, t.valueNumbers);
            c.drawText(s.value2[i], left + 770 * k, cy + t.valueHalf, t.valueNumbers);
            c.restoreToCount(save);
            return;
        }
        float x = left + 30 * k;
        if (r.kind == SetRow.ORDERED) {
            t.index.setColor(soft);
            c.drawText(s.index[i], x, cy + t.indexHalf, t.index);
            x = left + 80 * k;
        }
        if (s.small[i]) {
            t.rowTitleSmall.setColor(ink);
            c.drawText(s.label[i], x, cy + t.rowTitleSmallHalf, t.rowTitleSmall);
        } else {
            t.rowTitle.setColor(ink);
            c.drawText(s.label[i], x, cy + t.rowTitleHalf, t.rowTitle);
        }
        float valueRight = right - valueInset(r) * k;
        if (!s.value[i].isEmpty()) {
            t.value.setColor(soft);
            c.drawText(s.value[i], valueRight, cy + t.valueHalf, t.value);
        }
        if (r.busy) {
            float sx = s.value[i].isEmpty()
                    ? (valueInset(r) > 30 && r.kind != SetRow.PROGRESS ? valueRight - 12 * k : right - 42 * k)
                    : valueRight - s.valueW[i] - 24 * k;
            t.spinner.setColor(soft);
            Ui.spinner(c, sx, cy, 12 * k, spinSeconds(), t.spinner);
        }
        t.stroke.setColor(accessory);
        switch (r.kind) {
            case SetRow.NAV:
                chevron(c, t, right - 36 * k, cy);
                break;
            case SetRow.CHOICE:
                if (!r.twoValues) chevron(c, t, right - 36 * k, cy);
                break;
            case SetRow.EXTERNAL: {
                float ex = right - 40 * k, h = 9 * k;
                c.drawLine(ex - h, cy + h, ex + h, cy - h, t.stroke);
                c.drawLine(ex - 2 * k, cy - h, ex + h, cy - h, t.stroke);
                c.drawLine(ex + h, cy - h, ex + h, cy + 2 * k, t.stroke);
                break;
            }
            case SetRow.OPTION:
            case SetRow.ORDERED:
                if (r.on) {
                    t.stroke.setColor(SetTheme.alpha(SetTheme.blend(t.ink, t.focusInk, f), a * (off ? 0.45f : 1)));
                    float ox = right - 43 * k;
                    c.drawLine(ox - 10 * k, cy + 1 * k, ox - 3 * k, cy + 8 * k, t.stroke);
                    c.drawLine(ox - 3 * k, cy + 8 * k, ox + 10 * k, cy - 8 * k, t.stroke);
                }
                break;
            case SetRow.TOGGLE:
                if (!off) toggle(c, t, right, cy, f, Math.max(0, Math.min(1, anim.knob.value)), a);
                break;
            default:
                break;
        }
        if (r.badge) {
            float end = right - 58 * k;
            rect.set(end - 34 * k, cy - 17 * k, end, cy + 17 * k);
            t.fill.setColor(SetTheme.alpha(t.red, a));
            c.drawRoundRect(rect, 17 * k, 17 * k, t.fill);
            t.badge.setColor(SetTheme.alpha(t.white, a));
            c.drawText("1", end - 17 * k, cy + t.badgeHalf, t.badge);
        }
        c.restoreToCount(save);
    }

    private void chevron(Canvas c, SetTheme t, float cx, float cy) {
        c.drawLine(cx - 6 * k, cy - 11 * k, cx + 6 * k, cy, t.stroke);
        c.drawLine(cx + 6 * k, cy, cx - 6 * k, cy + 11 * k, t.stroke);
    }

    private void toggle(Canvas c, SetTheme t, float right, float cy, float f, float on, float a) {
        float l = right - 90 * k, r = right - 30 * k;
        rect.set(l, cy - 18 * k, r, cy + 18 * k);
        int offColor = SetTheme.blend(t.switchOff, t.switchOffFocused, f);
        t.fill.setColor(SetTheme.alpha(SetTheme.blend(offColor, t.green, on), a));
        c.drawRoundRect(rect, 18 * k, 18 * k, t.fill);
        t.fill.setColor(SetTheme.alpha(t.white, a));
        c.drawCircle(l + (18 + 24 * on) * k, cy, 15 * k, t.fill);
    }

    // MARK: - End of draw -- settings-draw-end
}
