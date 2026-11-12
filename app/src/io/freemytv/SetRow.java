package io.freemytv;

import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * One row of the settings (SPEC3 §2.4): what it shows and what OK and the arrows do. Rows are built by the page files
 * (SetSystem, SetHomePages, SetPicksPages, SetKeys); SetView draws them, moves the focus and calls the lambdas inside
 * its guard. Every visible string is already translated; `onRefresh` recomputes them (cheap, UI thread, no I/O).
 */
final class SetRow {
    static final int NAV = 0, CHOICE = 1, TOGGLE = 2, ACTION = 3, DESTRUCTIVE = 4, EXTERNAL = 5,
            INFO = 6, OPTION = 7, METRIC = 8, PROGRESS = 9, ORDERED = 10;
    static final int TONE_NONE = 0, TONE_GOOD = 1, TONE_WARN = 2;
    final String id;                 // stable, e.g. "home.shelf"
    final int kind;
    String title = "", value = "", value2 = "";  // translated; value2 = METRIC column B
    String about = "";               // left-pane text, translated
    String icon;                     // SetIcons name; null = the page's
    String need;                     // Capabilities.NEED_*, or null
    String why;                      // set by SetPage.refresh; null = available
    boolean on, busy, hidden, badge, brand;   // on: TOGGLE/OPTION/ORDERED state; brand: BRAND platter
    int tone, order;                 // order: ORDERED index (1-based)
    boolean twoValues;               // CHOICE with 2 values: OK flips instead of opening a picker
    boolean allowWhenUnavailable;    // Now Playing can still be turned off
    Runnable onOk;                   // not called while why != null unless allowWhenUnavailable
    IntConsumer onStep;              // ←/→ (dir ±1); null = none
    Supplier<String> reason;         // custom lock, or null
    Runnable onRefresh;              // recompute title/value/on/busy/hidden/badge/tone/about; no I/O

    SetRow(String id, int kind) {
        this.id = id == null ? "" : id;
        this.kind = kind;
    }

    static SetRow nav(String id, String title, Runnable ok) {
        return titled(new SetRow(id, NAV), title, ok);
    }

    /** `open` runs on OK (a picker, or the flip of a two-value choice without `step`); `step` on ←/→, or null. */
    static SetRow choice(String id, String title, Runnable open, IntConsumer step) {
        SetRow r = titled(new SetRow(id, CHOICE), title, open);
        r.onStep = step;
        return r;
    }

    /** OK and ←/→ both run `flip`. */
    static SetRow toggle(String id, String title, Runnable flip) {
        SetRow r = titled(new SetRow(id, TOGGLE), title, flip);
        r.onStep = flip == null ? null : dir -> flip.run();
        return r;
    }

    static SetRow action(String id, String title, Runnable ok) {
        return titled(new SetRow(id, ACTION), title, ok);
    }

    static SetRow destructive(String id, String title, Runnable ok) {
        return titled(new SetRow(id, DESTRUCTIVE), title, ok);
    }

    static SetRow external(String id, String title, Runnable ok) {
        return titled(new SetRow(id, EXTERNAL), title, ok);
    }

    static SetRow info(String id, String title) {
        return titled(new SetRow(id, INFO), title, null);
    }

    static SetRow option(String id, String title, boolean chosen, Runnable choose) {
        SetRow r = titled(new SetRow(id, OPTION), title, choose);
        r.on = chosen;
        return r;
    }

    static SetRow metric(String id, String title) {
        return titled(new SetRow(id, METRIC), title, null);
    }

    /** A step in progress: its title is the step, drawn with a spinner. */
    static SetRow progress(String id) {
        SetRow r = new SetRow(id, PROGRESS);
        r.busy = true;
        return r;
    }

    /** An app in an ordered list (the shelf): `ok` takes it in or out, `move` (±1) moves it. */
    static SetRow ordered(String id, String title, int order, Runnable ok, IntConsumer move) {
        SetRow r = titled(new SetRow(id, ORDERED), title, ok);
        r.order = order;
        r.on = order > 0;
        r.onStep = move;
        return r;
    }

    SetRow about(String text) {
        about = text == null ? "" : text;
        return this;
    }

    SetRow icon(String name) {
        icon = name;
        return this;
    }

    SetRow need(String n) {
        need = n;
        return this;
    }

    SetRow reason(Supplier<String> r) {
        reason = r;
        return this;
    }

    SetRow refresh(Runnable r) {
        onRefresh = r;
        return this;
    }

    SetRow allowWhenUnavailable() {
        allowWhenUnavailable = true;
        return this;
    }

    SetRow twoValues() {
        twoValues = true;
        return this;
    }

    SetRow brand() {
        brand = true;
        return this;
    }

    /** Rows the remote can rest on that do nothing on OK (they are there to be read). */
    boolean readOnly() {
        return kind == INFO || kind == METRIC || kind == PROGRESS;
    }

    private static SetRow titled(SetRow r, String title, Runnable ok) {
        r.title = title == null ? "" : title;
        r.onOk = ok;
        return r;
    }
}
