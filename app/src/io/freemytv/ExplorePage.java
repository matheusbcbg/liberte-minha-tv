package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

/**
 * One page of Explore (a root tab, a detail, a grid, the search): its own layer of Views, its own focus, its own
 * loads. ExploreView keeps the stack, steps the frames and routes the keys.
 */
abstract class ExplorePage {
    /** Zones of the focus (spec §1.4). */
    static final int Z_TOPBAR = 0, Z_HERO = 1, Z_ROWS = 2, Z_BUTTONS = 3, Z_OVERVIEW = 4, Z_KEYBOARD = 5, Z_GRID = 6, Z_CHIPS = 7;
    /** TalkBack groups: id = group·1000 + index + 1. */
    static final int G_TOPBAR = 1, G_HERO = 2, G_DETAIL = 3, G_KEYBOARD = 4, G_GRID = 5, G_CHIPS = 6, G_ROWS = 10;

    final ExploreView host;
    final float k;
    /** Root pages are the top bar's; pushed pages sit over one. */
    boolean root;
    /** Loads answered after this changed belong to a page state that is gone. */
    int generation;

    ExplorePage(ExploreView host) {
        this.host = host;
        k = host.k();
    }

    abstract ViewGroup layer();
    abstract void build();
    abstract boolean onKey(int keyCode, KeyEvent e, boolean longPress);
    /** True if it consumed Back. */
    abstract boolean back();

    /** Advances animations; true while something still moves. */
    boolean step(float dt) { return false; }
    void shown() {}
    void hidden() {}
    void destroy() { generation++; }

    /** The HERO picture the backdrop should show for this page, or null. */
    String backgroundPath() { return null; }

    void a11yItems(List<Integer> out) {}
    String a11yText(int id) { return ""; }
    boolean a11yBounds(int id, Rect r) { return false; }
    int a11yFocused() { return -1; }
    void a11yFocus(int id) {}
    void a11yClick(int id) {}
    void save(Bundle out) {}

    // MARK: - Beyond the frozen API (WS2's own)

    /** The page's vertical scroll (the top bar hides with it). */
    float pageScroll() { return 0; }
    /** Pictures came in (ExploreImages.Listener, once per frame at most). */
    void imagesArrived() {}
    /** The focus comes down from the top bar. */
    void focusFromTop() {}
    /** The top bar took the focus. */
    void blur() {}
    /** True while this page's focus is at the very top (the hero or nothing), for the hero timer. */
    boolean atTop() { return true; }
    /** The network came back, the providers or the key changed: reload what failed. */
    void reload() {}
    /** Continue Watching changed (permission, observer). */
    void watchChanged() {}

    /** Quero assistir changed (this process): pages showing it build it again. */
    void watchlistChanged() {}
    void resume() {}
    void pause() {}

    Context context() {
        return host.getContext();
    }

    boolean stale(int gen) {
        return gen != generation;
    }

    // MARK: - Layout helpers

    /** A child's place in the 1920×1080 layout (times k at layout time). */
    static final class Pos extends ViewGroup.LayoutParams {
        float x, y, w, h;

        Pos(float x, float y, float w, float h) {
            super(Math.round(w), Math.round(h));
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** A page's layer: children laid out at fixed places, never measured again (moving is translation/alpha). */
    static class Layer extends ViewGroup {
        private final float k;

        Layer(Context c, float k) {
            super(c);
            this.k = k;
            setClipChildren(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void add(View v, float x, float y, float w, float h) {
            addView(v, new Pos(x, y, w, h));
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof Pos) {
                    Pos p = (Pos) lp;
                    c.measure(MeasureSpec.makeMeasureSpec(Math.round(p.w * k), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.round(p.h * k), MeasureSpec.EXACTLY));
                }
            }
            setMeasuredDimension(w, h);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (!(lp instanceof Pos)) continue;
                Pos p = (Pos) lp;
                int x = Math.round(p.x * k), y = Math.round(p.y * k);
                c.layout(x, y, x + Math.round(p.w * k), y + Math.round(p.h * k));
            }
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
            return p instanceof Pos;
        }
    }

    /** A centred message (no key, refused key, couldn't load, no results), drawn once. */
    static final class Notice extends View {
        private final TextPaint paint;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float k;
        private StaticLayout layout;
        private String text = "";
        private boolean card;

        Notice(Context c, float k, boolean card) {
            super(c);
            this.k = k;
            this.card = card;
            paint = new TextPaint(Ui.text(Ui.medium, 28 * k, CardPainter.INK));
            fill.setColor(CardPainter.PILL);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(GONE);
        }

        void setSize(float size, int color) {
            paint.setTextSize(size * k);
            paint.setColor(color);
            text = "";
            layout = null;
        }

        void show(String t) {
            if (t == null || t.isEmpty()) {
                setVisibility(GONE);
                text = "";
                return;
            }
            setVisibility(VISIBLE);
            if (t.equals(text) && layout != null) return;
            text = t;
            layout = null;
            makeLayout(getWidth());
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            makeLayout(w);
        }

        /** Built when the text or the size changes, never while drawing. */
        private void makeLayout(int width) {
            if (text.isEmpty() || width <= 0) return;
            int pad = Math.round((card ? 48 : 0) * k);
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width - 2 * pad))
                    .setAlignment(card ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(6 * k, 1).setMaxLines(4).build();
        }

        String text() {
            return getVisibility() == VISIBLE ? text : "";
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                if (text.isEmpty() || layout == null) return;
                int pad = Math.round((card ? 48 : 0) * k);
                float h = layout.getHeight();
                float top = card ? (getHeight() - h) / 2 : 0;
                if (card) {
                    box.set(0, top - 40 * k, getWidth(), top + h + 40 * k);
                    c.drawRoundRect(box, 28 * k, 28 * k, fill);
                }
                c.translate(pad, top);
                layout.draw(c);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "notice draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }
    }

    /** One line of text (a page title), its baseline at the view's top + baseline. Drawn once (no layer needed). */
    static final class Label extends View {
        private final Paint paint;
        private final float baseline;
        private String text = "";

        Label(Context c, Paint paint, float baselinePx) {
            super(c);
            this.paint = paint;
            baseline = baselinePx;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(String t) {
            t = t == null ? "" : t;
            if (t.equals(text)) return;
            text = t;
            invalidate();
        }

        String text() {
            return text;
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            try {
                if (!text.isEmpty()) c.drawText(text, 0, baseline, paint);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "label draw", e);
            }
        }
    }
}
