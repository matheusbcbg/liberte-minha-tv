package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayDeque;

/**
 * Início's context menu (SPEC2 §2.3, §3.6): anchored under (or over) the item, MATERIAL, a header with the item's
 * name, rows with a sliding focus pill. A row that owns others ("Mover para ▸") replaces the rows in place with its
 * own; Back goes back to the main ones. A full-screen veil under it, drawn by the same View; no layer (alpha goes
 * through the paints). Texts are cut to size when the rows change, never while drawing.
 */
final class StartMenu extends View {
    static final class Spec {
        String title; String[] rows; boolean[] destructive; int[] submenuOf;   // submenuOf[i] = row index that owns row i, -1 top level
        RectF anchor; Listener listener;
        interface Listener { void onPick(int row); }    // main; the menu closes first
    }

    private static final float W = 520, HEADER = 64, ROW = 72, PAD = 12, RADIUS = 30, INSET = 8, PILL_R = 22, TEXT_X = 28;
    private static final int MAX_ROWS = 32, DESTRUCTIVE = 0xFFFF453A;

    private final StartHost host;
    private final float k;
    private final Context context;
    private final AppTiles.Material material;
    private final Paint veil = new Paint(), pill = new Paint(Paint.ANTI_ALIAS_FLAG), headerPaint, rowPaint;
    private final TextPaint headerText, rowText;
    private final float headerHalf, rowHalf, chevronSize;
    private final Spring shown = new Spring(0.30f, 0.86f, 0), pillY = new Spring(0.28f, 0.82f, 0);
    private final RectF panel = new RectF(), anchor = new RectF(), tmp = new RectF();
    private Spec spec;
    private boolean open, okDown, backDown;
    private int level = -1, focus;
    /** The rows at this level (their indexes in spec.rows) and their texts. */
    private final int[] shownRows = new int[MAX_ROWS];
    private final CharSequence[] texts = new CharSequence[MAX_ROWS];
    private final boolean[] owners = new boolean[MAX_ROWS];
    private int count;
    private CharSequence header = "";

    StartMenu(Context c, StartHost h) {
        super(c);
        context = c.getApplicationContext();
        host = h;
        k = ExploreView.scaleOf(c);
        material = new AppTiles.Material(k);
        headerPaint = Ui.text(Ui.semibold, 24 * k, CardPainter.DIM);
        headerText = new TextPaint(headerPaint);
        rowPaint = Ui.text(Ui.medium, 28 * k, CardPainter.INK);
        rowText = new TextPaint(rowPaint);
        headerHalf = Ui.halfCap(headerPaint);
        rowHalf = Ui.halfCap(rowPaint);
        chevronSize = 24 * k;
        Symbols.warm(context, "chevron_right", chevronSize);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
    }

    @Override public boolean hasOverlappingRendering() {
        return false;
    }

    void show(Spec s) {
        if (s == null || s.rows == null || s.rows.length == 0) return;
        spec = s;
        if (s.anchor != null) anchor.set(s.anchor);
        else anchor.set(700 * k, 400 * k, 1220 * k, 500 * k);
        open = true;
        okDown = backDown = false;
        setLevel(-1, 0);
        pillY.snap(focus);
        shown.snap(0);
        shown.target = 1;
        setVisibility(VISIBLE);
        invalidate();
        host.a11yChanged();
        host.wake();
    }

    void close(boolean animate) {
        if (!open && getVisibility() != VISIBLE) return;
        open = false;
        okDown = backDown = false;
        shown.target = 0;
        if (!animate) {
            shown.snap(0);
            setVisibility(INVISIBLE);
        }
        invalidate();
        host.a11yChanged();
        host.wake();
    }

    boolean isOpen() {
        return open;
    }

    /** The rows owned by `owner` (-1: the top level); focus on `focusRow` of them. */
    private void setLevel(int owner, int focusRow) {
        level = owner;
        count = 0;
        int n = spec.rows.length;
        float textW = (W - 2 * PAD - 2 * TEXT_X) * k;
        for (int i = 0; i < n && count < MAX_ROWS; i++) {
            int of = spec.submenuOf != null && i < spec.submenuOf.length ? spec.submenuOf[i] : -1;
            if (of != owner) continue;
            boolean owns = false;
            if (spec.submenuOf != null) for (int j = 0; j < spec.submenuOf.length && !owns; j++) owns = spec.submenuOf[j] == i;
            shownRows[count] = i;
            owners[count] = owns;
            String row = spec.rows[i] == null ? "" : spec.rows[i];
            texts[count] = TextUtils.ellipsize(row, rowText, owns ? textW - chevronSize * 1.4f : textW, TextUtils.TruncateAt.END);
            count++;
        }
        String title = owner >= 0 ? "‹ " + (spec.rows[owner] == null ? "" : spec.rows[owner]) : spec.title == null ? "" : spec.title;
        header = TextUtils.ellipsize(title, headerText, textW, TextUtils.TruncateAt.END);
        focus = Math.max(0, Math.min(count - 1, focusRow));
        place();
    }

    /** Under the anchor, or over it when it wouldn't fit above 1020; x clamped into the safe area. */
    private void place() {
        float h = (2 * PAD + HEADER + ROW * count) * k, w = W * k;
        float top = anchor.bottom + 16 * k;
        if (top + h > 1020 * k) top = anchor.top - 16 * k - h;
        top = Math.max(60 * k, Math.min(1020 * k - h, top));
        float left = Math.max(80 * k, Math.min((1840 - W) * k, anchor.left));
        panel.set(left, top, left + w, top + h);
    }

    /** The remote's keys while open; true when used. */
    boolean key(KeyEvent e) {
        if (!open) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int to = Math.max(0, Math.min(count - 1, focus + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    if (to != focus) {
                        focus = to;
                        pillY.target = focus;
                        invalidate();
                        host.a11yChanged();
                        host.wake();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                // Picks on release, and only after a fresh press here (the hold that opened the menu doesn't pick).
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) pick(focus);
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
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

    private void back() {
        if (level >= 0) {
            int owner = level, at = 0;
            setLevel(-1, 0);
            for (int i = 0; i < count; i++) if (shownRows[i] == owner) at = i;
            focus = at;
            pillY.snap(focus);
            invalidate();
            host.a11yChanged();
            host.wake();
            return;
        }
        close(true);
    }

    private void pick(int i) {
        if (i < 0 || i >= count || spec == null) return;
        int row = shownRows[i];
        if (owners[i]) {
            setLevel(row, 0);
            pillY.snap(focus);
            invalidate();
            host.a11yChanged();
            host.wake();
            return;
        }
        Spec s = spec;
        close(true);
        if (s.listener != null) s.listener.onPick(row);
    }

    boolean step(float dt) {
        boolean busy = shown.step(dt);
        busy |= pillY.step(dt);
        if (busy) invalidate();
        if (!open && !busy && getVisibility() == VISIBLE && shown.value < 0.01f) setVisibility(INVISIBLE);
        return busy;
    }

    // MARK: - TalkBack

    int a11yCount() {
        return open ? count : 0;
    }

    String a11yText(int i) {
        if (i < 0 || i >= count || spec == null) return "";
        String row = spec.rows[shownRows[i]] == null ? "" : spec.rows[shownRows[i]];
        return i == 0 ? header + ". " + row : row;
    }

    boolean a11yBounds(int i, RectF out) {
        if (!open || i < 0 || i >= count) return false;
        rowRect(i, out);
        return true;
    }

    int a11yFocused() {
        return open ? focus : -1;
    }

    void a11yFocus(int i) {
        if (i < 0 || i >= count) return;
        focus = i;
        pillY.target = i;
        invalidate();
        host.wake();
    }

    void a11yClick(int i) {
        pick(i);
    }

    /** The row under (x, y) px, or -1 (a mouse). */
    int hit(float x, float y) {
        if (!open) return -1;
        for (int i = 0; i < count; i++) {
            rowRect(i, tmp);
            if (tmp.contains(x, y)) return i;
        }
        return -1;
    }

    boolean contains(float x, float y) {
        return open && panel.contains(x, y);
    }

    private void rowRect(int i, RectF out) {
        float top = panel.top + (PAD + HEADER + ROW * i) * k;
        out.set(panel.left + PAD * k, top, panel.right - PAD * k, top + ROW * k);
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        int save = c.save();
        try {
            draw0(c);
        } catch (RuntimeException e) {
            host.fault("menu draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void draw0(Canvas c) {
        float a = Ui.clamp(shown.value);
        if (a <= 0.004f || spec == null) return;
        veil.setColor(Ui.alpha(0x59000000, a));
        c.drawRect(0, 0, getWidth(), getHeight(), veil);
        float s = 0.94f + 0.06f * Math.max(0, Math.min(1.02f, shown.value));
        float px = Math.max(panel.left, Math.min(panel.right, anchor.centerX()));
        float py = panel.top >= anchor.bottom ? panel.top : panel.bottom;
        c.scale(s, s, px, py);
        material.draw(c, panel, RADIUS * k, a);
        int ia = Math.round(255 * a);
        headerPaint.setAlpha(Math.round(ia * (CardPainter.DIM >>> 24) / 255f));
        c.drawText(header, 0, header.length(), panel.left + (PAD + TEXT_X) * k, panel.top + (PAD + HEADER / 2) * k + headerHalf, headerPaint);
        // The pill under the focused row.
        float pf = pillY.value;
        float pt = panel.top + (PAD + HEADER + ROW * pf) * k;
        tmp.set(panel.left + INSET * k, pt + INSET / 2 * k, panel.right - INSET * k, pt + (ROW - INSET / 2) * k);
        pill.setColor(Ui.alpha(CardPainter.FOCUS, a));
        c.drawRoundRect(tmp, PILL_R * k, PILL_R * k, pill);
        for (int i = 0; i < count; i++) {
            float top = panel.top + (PAD + HEADER + ROW * i) * k, cy = top + ROW * k / 2;
            boolean focused = i == focus;
            boolean red = spec.destructive != null && shownRows[i] < spec.destructive.length && spec.destructive[shownRows[i]];
            int color = red ? DESTRUCTIVE : focused ? CardPainter.FOCUS_TEXT : CardPainter.INK;
            rowPaint.setColor(Ui.alpha(color, a));
            c.drawText(texts[i], 0, texts[i].length(), panel.left + (PAD + TEXT_X) * k, cy + rowHalf, rowPaint);
            if (owners[i]) {
                Symbols.draw(context, c, "chevron_right", panel.right - (PAD + TEXT_X) * k - chevronSize * 0.4f, cy, chevronSize,
                        Ui.alpha(focused ? CardPainter.FOCUS_TEXT : CardPainter.DIM, a));
            }
        }
    }

    /**
     * The hint chip (edit mode, discoverability) and the toasts, in the same place at the bottom (§2.3): MATERIAL, h 56,
     * top 992, radius 28, padding 28. A toast shows 5 s and goes before the hint; both fade in and out in 200 ms.
     */
    static final class StartHint extends View {
        private static final float TOP = 52, H = 56, PADDING = 28, FADE_MS = 200, TOAST_MS = 5000;
        private final float k;
        private final AppTiles.Material material;
        private final Paint text;
        private final TextPaint measure;
        private final float half;
        private final RectF chip = new RectF();
        private final ArrayDeque<String> toasts = new ArrayDeque<>();
        private String hint, toast;
        private long hintUntil, toastAt;
        /** What shows now and its fade (0..1); a new text waits for the old one to fade out. */
        private CharSequence showing;
        private String showingSource;
        private float fade, width;

        StartHint(Context c) {
            super(c);
            k = ExploreView.scaleOf(c);
            material = new AppTiles.Material(k);
            text = Ui.text(Ui.medium, 24 * k, CardPainter.INK);
            measure = new TextPaint(text);
            half = Ui.halfCap(text);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        /** ms 0: until clearHint(). */
        void hint(String text, long ms) {
            hint = text == null || text.isEmpty() ? null : text;
            hintUntil = ms <= 0 ? 0 : SystemClock.uptimeMillis() + ms;
            kick();
        }

        void clearHint() {
            hint = null;
            kick();
        }

        void toast(String text) {
            if (text == null || text.isEmpty()) return;
            if (toasts.size() >= 4) toasts.pollFirst();
            toasts.add(text);
            kick();
        }

        /** The toast or the hint showing now (TalkBack reads it through announcements, not as an item). */
        String current() {
            return showingSource;
        }

        private void kick() {
            if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
            invalidate();
        }

        private String wanted(long now) {
            if (toast != null && now - toastAt >= TOAST_MS) toast = null;
            if (toast == null && !toasts.isEmpty()) {
                toast = toasts.pollFirst();
                toastAt = now;
            }
            if (toast != null) return toast;
            if (hint != null && hintUntil != 0 && now >= hintUntil) hint = null;
            return hint;
        }

        boolean step(float dt) {
            long now = SystemClock.uptimeMillis();
            String want = wanted(now);
            float d = dt * 1000 / FADE_MS;
            boolean busy = false;
            if (want != null && want.equals(showingSource)) {
                if (fade < 1) {
                    fade = Math.min(1, fade + d);
                    busy = true;
                }
            } else if (showingSource != null && fade > 0) {
                fade = Math.max(0, fade - d);   // the old text fades out first
                busy = true;
            } else {
                showingSource = want;
                fade = 0;
                if (want != null) {
                    showing = TextUtils.ellipsize(want, measure, 1600 * k, TextUtils.TruncateAt.END);
                    width = text.measureText(showing, 0, showing.length());
                    busy = true;
                }
            }
            if (busy) invalidate();
            // A toast or a timed hint still has to end: frames keep coming while either is up.
            boolean waiting = toast != null || !toasts.isEmpty() || (hint != null && hintUntil != 0);
            if (!busy && showingSource == null && !waiting && getVisibility() == VISIBLE) setVisibility(INVISIBLE);
            return busy || waiting;
        }

        @Override protected void onDraw(Canvas c) {
            if (showing == null || fade <= 0.004f) return;
            int save = c.save();
            try {
                float w = width + 2 * PADDING * k, cx = getWidth() / 2f;
                chip.set(cx - w / 2, TOP * k, cx + w / 2, (TOP + H) * k);
                material.draw(c, chip, H * k / 2, fade);
                text.setAlpha(Math.round(255 * fade));
                c.drawText(showing, 0, showing.length(), cx - width / 2, chip.centerY() + half, text);
            } catch (RuntimeException e) {
                if (getParent() instanceof StartHost) ((StartHost) getParent()).fault("hint draw", e);
                else Safe.log("hint draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }
    }
}
