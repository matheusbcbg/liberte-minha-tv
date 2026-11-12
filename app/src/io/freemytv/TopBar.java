package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Explore's top bar: a centred capsule with the magnifier and the tabs, and a flowing focus lens. A hardware layer:
 * it records only while the lens moves; hiding on scroll is translation and alpha.
 * Item 0 is the magnifier (Buscar), items 1..6 the pages Catalog.HOME..SERVICES.
 */
final class TopBar extends View {
    static final int ITEMS = 7;
    private static final float H = 132, CAPSULE_Y = 36, CAPSULE_H = 64, PILL_H = 52, PAD = 26, GAP = 6, ICON = 28;

    private final float k;
    private final Paint label, labelFocused, fill = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path magnifier;
    private final String[] labels = new String[ITEMS];
    private final float[] lefts = new float[ITEMS], widths = new float[ITEMS], textW = new float[ITEMS], restW = new float[ITEMS];
    private final Spring lensX = new Spring(0.36f, 0.78f, 0), lensW = new Spring(0.36f, 0.78f, 0);
    private final Spring press = new Spring(0.2f, 1f, 0), presence = new Spring(0.3f, 1f, 0);
    private final RectF rect = new RectF();
    private float capsuleL, capsuleR, labelBase;
    private int selected = 1, focus = -1, labelsGen = -1;
    private float scroll;
    private boolean placed;

    TopBar(Context c) {
        super(c);
        k = ExploreView.scaleOf(c);
        label = Ui.text(Ui.medium, 28 * k, CardPainter.INK);
        labelFocused = Ui.text(Ui.semibold, 28 * k, CardPainter.FOCUS_TEXT);
        magnifier = ExploreIcons.magnifier(ICON * k);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        prepareLabels();
    }

    /** Page constant → item index. */
    static int itemOf(int page) {
        return page == Catalog.SEARCH ? 0 : page + 1;
    }

    /** Item index → page constant. */
    static int pageOf(int item) {
        return item <= 0 ? Catalog.SEARCH : Math.min(Catalog.SERVICES, item - 1);
    }

    /** The labels and their widths, once per language (never while drawing). */
    private void prepareLabels() {
        String[] names = null;
        try {
            names = Catalog.tabLabels();
        } catch (RuntimeException ignored) {
        }
        String[] fallback = {Ui.tr("explore|Home"), Ui.tr("Movies"), Ui.tr("Shows"), Ui.tr("Documentaries"),
                Ui.tr("Animation"), Ui.tr("explore|Services"), Ui.tr("Search")};
        labels[0] = fallback[6];
        for (int i = 1; i < ITEMS; i++) {
            int page = i - 1;
            String n = names != null && page < names.length ? names[page] : null;
            labels[i] = n == null || n.isEmpty() ? fallback[page] : n;
        }
        float x = 0;
        for (int i = 0; i < ITEMS; i++) {
            textW[i] = i == 0 ? ICON * k : labelFocused.measureText(labels[i]);
            restW[i] = i == 0 ? ICON * k : label.measureText(labels[i]);
            widths[i] = textW[i] + 2 * (i == 0 ? 22 : PAD) * k;
            lefts[i] = x;
            x += widths[i] + (i < ITEMS - 1 ? GAP * k : 0);
        }
        float inner = x, w = 1920 * k;
        capsuleL = (w - inner) / 2 - 6 * k;
        capsuleR = capsuleL + inner + 12 * k;
        for (int i = 0; i < ITEMS; i++) lefts[i] += capsuleL + 6 * k;
        labelBase = (CAPSULE_Y + CAPSULE_H / 2) * k + Ui.halfCap(label);
        labelsGen = Ui.generation;
        int target = focus >= 0 ? focus : selected;
        lensX.snap(lefts[target]);
        lensW.snap(widths[target]);
        invalidate();
    }

    String label(int item) {
        return item >= 0 && item < ITEMS ? labels[item] : "";
    }

    void setSelected(int page) {
        int item = itemOf(page);
        if (item == selected) return;
        selected = item;
        if (focus < 0) moveLens(item);
        invalidate();
    }

    int selected() {
        return selected;
    }

    void setFocus(int index) {
        if (index >= ITEMS) index = ITEMS - 1;
        if (index == focus) return;
        boolean was = focus >= 0;
        focus = index;
        presence.target = index >= 0 ? 1 : 0;
        moveLens(index >= 0 ? index : selected);
        if (!was && index >= 0 && !placed) {
            lensX.snap(lefts[index]);
            lensW.snap(widths[index]);
        }
        placed = true;
        applyScroll();
        invalidate();
    }

    int focus() {
        return focus;
    }

    int count() {
        return ITEMS;
    }

    /** OK held on a pill: the lens squashes 4%. */
    void press(boolean down) {
        press.target = down ? 1 : 0;
        invalidate();
    }

    private void moveLens(int item) {
        lensX.target = lefts[item];
        lensW.target = widths[item];
    }

    boolean step(float dt) {
        if (labelsGen != Ui.generation) prepareLabels();
        boolean busy = lensX.step(dt);
        busy |= lensW.step(dt);
        busy |= press.step(dt);
        busy |= presence.step(dt);
        if (busy) invalidate();
        return busy;
    }

    /** The page's scroll: the bar slides up and fades over the first 220 px, unless it has the focus. */
    void setScroll(float s) {
        scroll = s;
        applyScroll();
    }

    private void applyScroll() {
        float f = focus >= 0 ? 0 : Ui.clamp(scroll / (220 * k));
        setTranslationY(-H * k * f);
        setAlpha(1 - f);
        int vis = f >= 0.999f ? INVISIBLE : VISIBLE;
        if (getVisibility() != vis) setVisibility(vis);
    }

    /** A pill's rectangle in the parent's coordinates. */
    boolean itemBounds(int i, RectF out) {
        if (i < 0 || i >= ITEMS || getVisibility() != VISIBLE) return false;
        float top = (CAPSULE_Y + (CAPSULE_H - PILL_H) / 2) * k + getTranslationY();
        out.set(lefts[i], top, lefts[i] + widths[i], top + PILL_H * k);
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        int save = c.save();
        try {
            draw0(c);
        } catch (RuntimeException e) {
            ExploreView.crashed(this, "top bar draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void draw0(Canvas c) {
        fill.setColor(CardPainter.PILL);
        float top = CAPSULE_Y * k, h = CAPSULE_H * k;
        c.drawRoundRect(capsuleL, top, capsuleR, top + h, h / 2, h / 2, fill);
        float pillTop = (CAPSULE_Y + (CAPSULE_H - PILL_H) / 2) * k, ph = PILL_H * k;
        if (focus < 0 || presence.value < 0.99f) {
            // The selected page's pill (under the lens it is the lens).
            rect.set(lefts[selected], pillTop, lefts[selected] + widths[selected], pillTop + ph);
            fill.setColor(Ui.alpha(CardPainter.SELECTED, 1 - presence.value));
            c.drawRoundRect(rect, ph / 2, ph / 2, fill);
        }
        float pv = Ui.clamp(presence.value);
        if (pv > 0.01f) {
            float stretch = 1 + Math.min(0.06f, Math.abs(lensX.velocity) / (6000 * k));
            float scale = 1 + 0.06f * pv;
            float w = lensW.value * stretch * scale, lh = ph * scale * (1 - 0.04f * Ui.clamp(press.value));
            float cx = lensX.value + lensW.value / 2, cy = pillTop + ph / 2;
            rect.set(cx - w / 2, cy - lh / 2, cx + w / 2, cy + lh / 2);
            fill.setColor(Ui.alpha(CardPainter.FOCUS, pv));
            c.drawRoundRect(rect, lh / 2, lh / 2, fill);
        }
        for (int i = 0; i < ITEMS; i++) {
            boolean focused = i == focus && pv > 0.5f;
            int color = focused ? CardPainter.FOCUS_TEXT : i == selected ? CardPainter.INK : Ui.alpha(CardPainter.INK, 0.7f);
            float cx = lefts[i] + widths[i] / 2;
            if (i == 0) {
                icon.setColor(color);
                float s = ICON * k;
                c.save();
                c.translate(cx - s / 2, pillTop + (ph - s) / 2);
                c.drawPath(magnifier, icon);
                c.restore();
                continue;
            }
            Paint p = focused ? labelFocused : label;
            if (!focused) p.setColor(color);
            float tw = focused ? textW[i] : restW[i];
            c.drawText(labels[i], cx - tw / 2, labelBase, p);
        }
        label.setColor(CardPainter.INK);
    }
}
