package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;

/**
 * One Explore row on screen (header and cards). A plain View with no layer: only the row whose focus, scroll or
 * pictures changed records again; moving it up and down is its translationY. BrowseStack binds it to a Row.
 */
final class RowView extends View {
    private final CardPainter painter;
    private final ExploreImages images;
    private final WatchArt art;
    Row row;
    private Spring[] focus;
    private Spring scroll;
    private float x0;
    /** Set by BrowseStack: the focused card (-1 none), the edge bump's offset, the sheen's progress (-1 none). */
    int focusIndex = -1;
    float bump;
    float sweep = -1;
    /** Cards with a picture not in yet, at the last draw (an image arrival redraws only rows that wait for one). */
    int missing;

    RowView(Context c, CardPainter p, ExploreImages img, WatchArt art) {
        super(c);
        painter = p;
        images = img;
        this.art = art;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    @Override public boolean hasOverlappingRendering() {
        return false;
    }

    void bind(Row r, Spring[] focus, Spring scroll, float x0) {
        boolean same = row == r;
        row = r;
        this.focus = focus;
        this.scroll = scroll;
        this.x0 = x0;
        if (!same) missing = 1;
        setVisibility(VISIBLE);
        invalidate();
    }

    void unbind() {
        row = null;
        focus = null;
        scroll = null;
        focusIndex = -1;
        sweep = -1;
        setVisibility(INVISIBLE);
    }

    /** True if the row's cards on screen show this picture. */
    boolean uses(int spec, String path) {
        Row r = row;
        if (r == null || path == null) return false;
        int[] range = visibleRange();
        for (int i = range[0]; i < range[1]; i++) {
            Card c = r.cards[i];
            if (c != null && c.imageSpec == spec && path.equals(c.image)) return true;
        }
        return false;
    }

    private final int[] range = new int[2];

    /** [first, last) of the cards inside [x0 − step, screen width] at the current scroll. */
    int[] visibleRange() {
        Row r = row;
        range[0] = range[1] = 0;
        if (r == null || r.count == 0) return range;
        if (r.style == Catalog.CHIP || r.style == Catalog.INFO) {
            range[1] = r.count;
            return range;
        }
        float step = painter.step(r.style), s = scroll == null ? 0 : scroll.value, w = getWidth() > 0 ? getWidth() : 1920 * painter.k;
        int first = (int) Math.floor((s - step) / step);
        int last = (int) Math.ceil((s + w - x0) / step) + 1;
        range[0] = Math.max(0, first);
        range[1] = Math.min(r.count, Math.max(range[0], last));
        return range;
    }

    /** A card's left edge inside the row's content (before the row's scroll). */
    float leftOf(int i) {
        Row r = row;
        if (r == null) return 0;
        if (r.style != Catalog.CHIP) return i * painter.step(r.style);
        float x = 0;
        float gap = painter.step(Catalog.CHIP);
        for (int j = 0; j < i && j < r.count; j++) x += r.cards[j].chipW + gap;
        return x;
    }

    @Override protected void onDraw(Canvas c) {
        int save = c.save();
        try {
            drawRow(c);
        } catch (RuntimeException e) {
            ExploreView.crashed(this, "row draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void drawRow(Canvas c) {
        Row r = row;
        if (r == null) return;
        long now = SystemClock.uptimeMillis();
        float k = painter.k;
        painter.drawHeader(c, r.title, x0, 0);
        float top = 52 * k;
        if (r.style == Catalog.INFO) {
            missing = 0;
            if (r.state == Row.READY) painter.drawInfo(c, r, x0, 0);
            return;
        }
        if (r.state != Row.READY || r.count == 0) {
            missing = 0;
            float step = r.style == Catalog.CHIP ? 176 * k : painter.step(r.style);
            for (int i = 0; i < 6; i++) painter.drawSkeleton(c, r.style, x0 + i * step, top);
            return;
        }
        int[] vr = visibleRange();
        int first = vr[0], last = vr[1];
        float s = scroll == null ? 0 : scroll.value;
        int waiting = 0;
        boolean fading = false;
        int top1 = -1;
        float best = 0.02f;
        if (focus != null) {
            for (int i = first; i < last && i < focus.length; i++) {
                if (focus[i] != null && focus[i].value > best) {
                    best = focus[i].value;
                    top1 = i;
                }
            }
        }
        boolean chips = r.style == Catalog.CHIP;
        float chipX = chips ? 0 : 0, gap = painter.step(Catalog.CHIP);
        float topX = 0;
        for (int i = first; i < last; i++) {
            Card card = r.cards[i];
            float left = chips ? chipX : i * painter.step(r.style);
            if (chips) chipX += card.chipW + gap;
            float x = x0 + left - s + (i == focusIndex ? bump : 0);
            if (i == top1) {
                topX = x;
                continue;
            }
            int st = drawCard(c, r, card, i, x, top, now);
            if (st == 1) waiting++;
            else if (st == 2) fading = true;
        }
        if (top1 >= 0) {
            int st = drawCard(c, r, r.cards[top1], top1, topX, top, now);
            if (st == 1) waiting++;
            else if (st == 2) fading = true;
        }
        missing = waiting;
        if (fading) postInvalidateOnAnimation();
    }

    /** Draws one card; 1 if its picture is still missing, 2 if it is fading in, else 0. */
    private int drawCard(Canvas c, Row r, Card card, int i, float x, float y, long now) {
        if (card == null) return 0;
        Spring sp = focus != null && i < focus.length ? focus[i] : null;
        float f = sp == null ? 0 : sp.value, v = sp == null ? 0 : sp.velocity;
        float sw = i == focusIndex ? sweep : -1;
        ExploreImages.Img img = null, logo = null;
        Bitmap wb = null;
        BitmapShader ws = null;
        int state = 0;
        if (card.type == Card.WATCH && card.ref instanceof WatchNext.Item && art != null) {
            // Matched to TMDB (WatchMatch): its still first, the TV's own picture while it loads (or without a match).
            // Anything wanted and not in yet counts as missing, so its arrival redraws the row.
            if (card.image != null) {
                img = images.get(card.imageSpec, card.image);
                if (img == null) state = 1;
                else if (CardPainter.fading(img, now)) state = 2;
            }
            if (img == null) {
                WatchNext.Item it = (WatchNext.Item) card.ref;
                wb = art.get(it.pictureUri, it.pictureKey, it.bannerKey, it.pkg);
                if (wb != null) ws = art.shader(wb);
                else state = 1;
            } else if (card.logo != null) {
                logo = images.get(ExploreImages.LOGO, card.logo);
                if (logo == null && state == 0) state = 1;
            }
        } else if (card.image != null) {
            img = images.get(card.imageSpec, card.image);
            if (img == null) state = 1;
            else if (CardPainter.fading(img, now)) state = 2;
        }
        painter.draw(c, r, card, x, y, f, v, sw, img, wb, ws, now, logo);
        return state;
    }
}
