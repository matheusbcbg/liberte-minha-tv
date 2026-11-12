package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * One settings page (SPEC3 §3.1): a title, an icon for the left pane and its entries (rows, section headers and group
 * gaps), built by a Factory every time it is shown (push, pop, a new language) so its values are always fresh.
 */
final class SetPage {
    interface Factory { SetPage build(SetHost h); }

    /** A group gap (24 px) between rows. */
    static final Object GAP = new Object();

    /** A section header; with colA/colB it also titles the two METRIC columns. */
    static final class Header {
        final String text, colA, colB;

        Header(String t) {
            this(t, null, null);
        }

        Header(String t, String a, String b) {
            text = t == null ? "" : t;
            colA = a;
            colB = b;
        }
    }

    final String id, title, icon;
    String about = "";                // the page's default left-pane text (empty list, headers)
    boolean brand;                    // BRAND platter for the whole page
    final List<Object> entries = new ArrayList<>();   // SetRow, Header or GAP
    Runnable onEnter, onLeave;        // after push / before pop; onLeave also on pause
    Preview preview;                  // custom left pane (key pages), or null

    SetPage(String id, String title, String icon) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.icon = icon == null ? "info" : icon;
    }

    SetPage add(SetRow r) {
        if (r != null) entries.add(r);
        return this;
    }

    SetPage header(String t) {
        entries.add(new Header(t));
        return this;
    }

    SetPage header(String t, String a, String b) {
        entries.add(new Header(t, a, b));
        return this;
    }

    SetPage gap() {
        entries.add(GAP);
        return this;
    }

    /** The row with this id, or null. */
    SetRow row(String id) {
        if (id == null) return null;
        for (Object e : entries) if (e instanceof SetRow && id.equals(((SetRow) e).id)) return (SetRow) e;
        return null;
    }

    /**
     * Every row's onRefresh, then why it is unavailable: its own reason first, else what it needs, each
     * Capabilities.missing asked once per refresh (the legacy screen's once-per-screen rule).
     */
    void refresh(Context c) {
        HashMap<String, String> missing = null;
        for (Object e : entries) {
            if (!(e instanceof SetRow)) continue;
            SetRow r = (SetRow) e;
            if (r.onRefresh != null) r.onRefresh.run();
            String why = null;
            if (r.reason != null) {
                why = r.reason.get();
            } else if (r.need != null) {
                if (missing == null) missing = new HashMap<>();
                if (missing.containsKey(r.need)) {
                    why = missing.get(r.need);
                } else {
                    why = Capabilities.missing(c, r.need);
                    missing.put(r.need, why);
                }
            }
            r.why = why == null || why.isEmpty() ? null : why;
        }
    }

    /** The rows the remote can rest on: visible ones (headers and gaps aren't). */
    List<SetRow> focusable() {
        List<SetRow> out = new ArrayList<>();
        for (Object e : entries) if (e instanceof SetRow && !((SetRow) e).hidden) out.add((SetRow) e);
        return out;
    }

    /**
     * A picker: one OPTION per {key, label} (a third element, when given, is that option's own left-pane text);
     * the current one has the check and the first focus. OK → pick.accept(key), the check shows for 150 ms, then
     * host.pop(). Back pops without a change.
     */
    static SetPage picker(SetHost h, String id, String title, String icon, String about,
                          List<String[]> choices, String current, Consumer<String> pick) {
        SetPage p = new SetPage(id, title, icon);
        p.about = about == null ? "" : about;
        boolean[] chosen = new boolean[1], left = new boolean[1];
        // Left by Back (or a pause) in the 150 ms after a choice: the delayed pop would close the opener instead.
        p.onLeave = () -> left[0] = true;
        if (choices == null) return p;
        for (int i = 0; i < choices.size(); i++) {
            String[] choice = choices.get(i);
            if (choice == null || choice.length < 2) continue;
            String key = choice[0] == null ? "" : choice[0];
            SetRow r = SetRow.option(id + ":" + i, choice[1], key.equals(current), null);
            r.about(choice.length > 2 && choice[2] != null && !choice[2].isEmpty() ? choice[2] : p.about);
            r.onOk = () -> {
                if (chosen[0] || left[0]) return;   // a second OK in the 150 ms does nothing
                chosen[0] = true;
                for (Object e : p.entries) if (e instanceof SetRow) ((SetRow) e).on = false;
                r.on = true;
                if (pick != null) pick.accept(key);
                h.refresh();
                h.handler().postDelayed(() -> {
                    try {
                        if (!left[0] && h.running()) h.pop();
                    } catch (RuntimeException e) {
                        h.fault("picker", e);
                    }
                }, 150);
            };
            p.add(r);
        }
        return p;
    }

    /** A custom left pane (the key pages' QR code): drawn instead of the platter and the description. */
    interface Preview {
        void layout(SetTheme t, float k);
        void draw(Canvas c, SetTheme t, float k, float alpha);   // no allocation
        String spoken();
        boolean moving();             // keeps frames running (a spinner)
    }
}
