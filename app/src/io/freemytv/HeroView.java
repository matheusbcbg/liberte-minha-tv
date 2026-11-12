package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.Locale;

/**
 * The hero's text column over the backdrop: logo (or title), meta line, two lines of synopsis, the buttons and the
 * dots. A hardware layer: recorded once per item; the swap and the page's scroll move it as View properties.
 */
final class HeroView extends View {
    private static final float X = 96, LOGO_BOTTOM = 520, META = 572, OVER_TOP = 616 - 26, BUTTON_TOP = 690, BUTTON_H = 64,
            DOTS_Y = 790, TEXT_W = 900, OVER_W = 820;

    private final ExploreImages images;
    private final float k;
    private final TextPaint titlePaint, overPaint;
    private final Paint metaPaint, button, buttonFocused, fill = new Paint(Paint.ANTI_ALIAS_FLAG), logo = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Meta.Painter metaPainter;
    private final Path play;
    private final RectF rect = new RectF();

    private ExploreModel.Title[] items = new ExploreModel.Title[0];
    private ExploreModel.Details[] details = new ExploreModel.Details[0];
    /** Per item, prepared on the UI thread when the data arrives. */
    private StaticLayout[] titles = new StaticLayout[0], overviews = new StaticLayout[0];
    private Meta[] metas = new Meta[0];
    private String[][] labels = new String[0][];
    private float[][] labelW = new float[0][];
    private ExploreModel.Offer[] offers = new ExploreModel.Offer[0];
    private int index, shownIndex, focus = -1, gen = -1;
    // Swap: 0 = at rest; out for 150 ms, then in for 250 ms.
    private float swapT = -1;
    private boolean swapIn;
    private float motionDy, motionAlpha = 1, swapAlpha = 1, swapDy;
    /** setCompact: 0 in Explore. */
    private float compact;

    HeroView(Context c, ExploreImages img) {
        super(c);
        images = img;
        k = ExploreView.scaleOf(c);
        titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 76 * k, CardPainter.INK));
        overPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, CardPainter.INK));
        metaPaint = Ui.numbers(Ui.medium, 26 * k, CardPainter.META);
        button = Ui.text(Ui.semibold, 26 * k, CardPainter.INK);
        buttonFocused = Ui.text(Ui.semibold, 26 * k, CardPainter.FOCUS_TEXT);
        metaPainter = new Meta.Painter(k, metaPaint);
        play = ExploreIcons.play(22 * k);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Início's Top Shelf: 0 = Explore's geometry (the default; Explore never calls this), 1 = compact: everything 150 up
     * and the buttons hidden. The shift is a View property; the layer records again only while the buttons fade.
     */
    void setCompact(float c) {
        c = Ui.clamp(c);
        if (c == compact) return;
        compact = c;
        applyMotion();
        if (items.length > 0 && buttons() > 0) invalidate();
    }

    /** The hero's items and their details (any may still be null); the texts are made here, once. */
    void setItems(ExploreModel.Title[] items, ExploreModel.Details[] details) {
        int n = items == null ? 0 : items.length;
        String keep = index < this.items.length ? this.items[index].key() : null;
        this.items = n == 0 ? new ExploreModel.Title[0] : items;
        this.details = new ExploreModel.Details[n];
        if (details != null) System.arraycopy(details, 0, this.details, 0, Math.min(n, details.length));
        titles = new StaticLayout[n];
        overviews = new StaticLayout[n];
        metas = new Meta[n];
        labels = new String[n][];
        labelW = new float[n][];
        offers = new ExploreModel.Offer[n];
        for (int i = 0; i < n; i++) prepare(i);
        gen = Ui.generation;
        index = 0;
        for (int i = 0; keep != null && i < n; i++) if (this.items[i].key().equals(keep)) index = i;
        shownIndex = index;
        focus = Math.min(focus, buttons() - 1);
        wantImages();
        invalidate();
    }

    /** One item's details came in. */
    void setDetails(int i, ExploreModel.Details d) {
        if (i < 0 || i >= details.length) return;
        details[i] = d;
        prepare(i);
        if (i == shownIndex) {
            focus = Math.min(focus, buttons() - 1);
            invalidate();
        }
        wantImages();
    }

    private void prepare(int i) {
        ExploreModel.Title t = items[i];
        ExploreModel.Details d = details[i];
        String title = t.title == null ? "" : t.title;
        titles[i] = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, Math.round(TEXT_W * k))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(8 * k, 1).build();
        String over = d != null && d.t != null && d.t.overview != null && !d.t.overview.isEmpty() ? d.t.overview
                : t.overview == null ? "" : t.overview;
        overviews[i] = StaticLayout.Builder.obtain(over, 0, over.length(), overPaint, Math.round(OVER_W * k))
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(10 * k, 1).build();
        metas[i] = Meta.build(t, d, false, metaPainter, TEXT_W * k);
        offers[i] = ExploreView.bestOffer(d);
        String watch = offers[i] == null ? null : Ui.trf("Watch on {service}", "service", ExploreView.serviceLabel(offers[i]));
        String more = Ui.tr("More info");
        labels[i] = watch == null ? new String[] {more} : new String[] {watch, more};
        labelW[i] = new float[labels[i].length];
        for (int j = 0; j < labels[i].length; j++) labelW[i][j] = button.measureText(labels[i][j]);
    }

    private void wantImages() {
        int n = items.length;
        for (int j = 0; j < Math.min(n, 3); j++) {
            int i = (index + j) % n;
            ExploreModel.Details d = details[i];
            if (d != null && d.logo != null) images.want(ExploreImages.LOGO, d.logo, j == 0 ? ExploreImages.NOW : ExploreImages.SOON);
        }
    }

    void setIndex(int i, boolean animate) {
        int n = items.length;
        if (n == 0) return;
        i = RowMath.wrap(i, n);
        if (i == index && swapT < 0) return;
        index = i;
        wantImages();
        if (!animate) {
            shownIndex = i;
            swapT = -1;
            swapAlpha = 1;
            swapDy = 0;
            focus = Math.min(focus, buttons() - 1);
            applyMotion();
            invalidate();
            return;
        }
        if (swapT < 0 || swapIn) {
            swapT = 0;
            swapIn = false;
        }
    }

    int index() { return index; }
    int count() { return items.length; }
    ExploreModel.Title item(int i) { return i >= 0 && i < items.length ? items[i] : null; }
    ExploreModel.Details details(int i) { return i >= 0 && i < details.length ? details[i] : null; }
    /** The installed offer behind "Assistir em …" for item i, or null. */
    ExploreModel.Offer offer(int i) { return i >= 0 && i < offers.length ? offers[i] : null; }

    void setFocus(int b) {
        b = Math.min(b, buttons() - 1);
        if (b == focus) return;
        focus = b;
        invalidate();
    }

    int focus() { return focus; }

    int buttons() {
        return items.length == 0 || shownIndex >= labels.length ? 0 : labels[shownIndex].length;
    }

    /** True if button b of the shown item is "Assistir em …". */
    boolean isWatchButton(int b) {
        return b == 0 && buttons() == 2;
    }

    String buttonLabel(int b) {
        return b >= 0 && b < buttons() ? labels[shownIndex][b] : "";
    }

    /** A button's rectangle in the parent's coordinates. */
    boolean buttonBounds(int i, RectF out) {
        if (i < 0 || i >= buttons()) return false;
        float x = X * k;
        for (int j = 0; j < i; j++) x += buttonWidth(j) + 20 * k;
        float top = BUTTON_TOP * k + getTranslationY() + getTop();
        out.set(x, top, x + buttonWidth(i), top + BUTTON_H * k);
        return true;
    }

    private float buttonWidth(int j) {
        float w = labelW[shownIndex][j] + 64 * k;
        if (isWatchButton(j)) w += 34 * k;
        return w;
    }

    /** The page's scroll: the text fades over 250 px and slides with the page. */
    void setMotion(float dy, float alpha) {
        motionDy = dy;
        motionAlpha = alpha;
        applyMotion();
    }

    private void applyMotion() {
        float a = Ui.clamp(motionAlpha * swapAlpha);
        setTranslationY(motionDy + swapDy - 150 * k * compact);
        setAlpha(a);
        int vis = a <= 0.001f ? INVISIBLE : VISIBLE;
        if (getVisibility() != vis) setVisibility(vis);
    }

    boolean step(float dt) {
        if (gen != Ui.generation && items.length > 0) {
            for (int i = 0; i < items.length; i++) prepare(i);
            gen = Ui.generation;
            invalidate();
        }
        if (swapT < 0) return false;
        swapT += dt;
        if (!swapIn) {
            float t = Ui.clamp(swapT / 0.15f);
            swapAlpha = 1 - t;
            swapDy = 0;
            if (t >= 1) {
                swapIn = true;
                swapT = 0;
                shownIndex = index;
                focus = Math.min(focus, buttons() - 1);
                invalidate();
            }
        } else {
            float t = Ui.clamp(swapT / 0.25f), e = t * t * (3 - 2 * t);
            swapAlpha = e;
            swapDy = 12 * k * (1 - e);
            if (t >= 1) swapT = -1;
        }
        applyMotion();
        return true;
    }

    /** A logo came in: record again if the shown item waited for one. */
    void imagesArrived() {
        if (items.length == 0) return;
        ExploreModel.Details d = details[shownIndex];
        if (d != null && d.logo != null && !drawnLogo) invalidate();
    }

    private boolean drawnLogo;

    @Override protected void onDraw(Canvas c) {
        int save = c.save();
        try {
            draw0(c);
        } catch (RuntimeException e) {
            ExploreView.crashed(this, "hero draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void draw0(Canvas c) {
        float x = X * k;
        if (items.length == 0) {
            // Static skeleton (no pulse: no frames while waiting).
            fill.setColor(CardPainter.SKELETON);
            c.drawRoundRect(x, 400 * k, x + 480 * k, 520 * k, 16 * k, 16 * k, fill);
            c.drawRoundRect(x, 552 * k, x + 420 * k, 576 * k, 8 * k, 8 * k, fill);
            c.drawRoundRect(x, 598 * k, x + 820 * k, 620 * k, 8 * k, 8 * k, fill);
            c.drawRoundRect(x, 634 * k, x + 600 * k, 656 * k, 8 * k, 8 * k, fill);
            drawnLogo = true;
            return;
        }
        int i = Math.min(shownIndex, items.length - 1);
        ExploreModel.Details d = details[i];
        boolean logoShown = false;
        drawnLogo = true;
        if (d != null && d.logo != null) {
            ExploreImages.Img img = images.get(ExploreImages.LOGO, d.logo);
            drawnLogo = img != null;
            if (img != null && img.bitmap != null) {
                float bw = img.bitmap.getWidth(), bh = img.bitmap.getHeight();
                float s = Math.min(640 * k / bw, 200 * k / bh);
                rect.set(x, LOGO_BOTTOM * k - bh * s, x + bw * s, LOGO_BOTTOM * k);
                c.drawBitmap(img.bitmap, null, rect, logo);
                logoShown = true;
            }
        }
        if (!logoShown && titles[i] != null) {
            c.save();
            c.translate(x, LOGO_BOTTOM * k - titles[i].getHeight());
            titles[i].draw(c);
            c.restore();
        }
        if (metas[i] != null) metas[i].draw(c, x, META * k, metaPainter);
        if (overviews[i] != null) {
            c.save();
            c.translate(x, OVER_TOP * k);
            overviews[i].draw(c);
            c.restore();
        }
        float bx = x, top = BUTTON_TOP * k, h = BUTTON_H * k;
        float ba = 1 - compact;   // the buttons' alpha: 1 in Explore
        for (int j = 0; ba > 0.001f && j < labels[i].length; j++) {
            float w = buttonWidth(j);
            boolean f = j == focus;
            fill.setColor(Ui.alpha(f ? CardPainter.FOCUS : CardPainter.PILL, ba));
            if (f) {
                float g = 0.03f * w;   // focused: 1.06
                rect.set(bx - g, top - 0.03f * h, bx + w + g, top + h * 1.03f);
            } else {
                rect.set(bx, top, bx + w, top + h);
            }
            c.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill);
            Paint p = f ? buttonFocused : button;
            int pa = p.getAlpha();
            if (ba < 1) p.setAlpha(Math.round(pa * ba));
            float tx = bx + 32 * k;
            if (isWatchButton(j)) {
                fill.setColor(Ui.alpha(f ? CardPainter.FOCUS_TEXT : CardPainter.INK, ba));
                c.save();
                c.translate(tx, top + (h - 22 * k) / 2);
                c.drawPath(play, fill);
                c.restore();
                tx += 34 * k;
            }
            c.drawText(labels[i][j], tx, top + h / 2 + metaPainter.halfCap(p), p);
            if (ba < 1) p.setAlpha(pa);
            bx += w + 20 * k;
        }
        int n = items.length;
        if (n >= 2) {
            float dx = x, cy = DOTS_Y * k;
            for (int j = 0; j < n; j++) {
                boolean on = j == index;
                fill.setColor(on ? CardPainter.INK : Ui.alpha(CardPainter.INK, 0.35f));
                float w = on ? 28 * k : 10 * k;
                c.drawRoundRect(dx, cy - 5 * k, dx + w, cy + 5 * k, 5 * k, 5 * k, fill);
                dx += w + 8 * k;
            }
        }
    }

    /**
     * A meta line: "Filme · 2024 · 2 h 46 min · Drama", then the star and the rating, then the ClassInd badge.
     * Built when the data arrives; drawn without allocating.
     */
    static final class Meta {
        String lead = "", rating, cert;
        float leadW, ratingW, certW;
        int certColor, certText;
        boolean certRim;

        /** Shared paints and the star (one per view). */
        static final class Painter {
            final float k;
            final Paint text, badge = new Paint(Paint.ANTI_ALIAS_FLAG), badgeText, rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path star;
            final float star0, textHalf, badgeHalf;
            private final java.util.IdentityHashMap<Paint, Float> caps = new java.util.IdentityHashMap<>();

            Painter(float k, Paint text) {
                this.k = k;
                this.text = text;
                star0 = 22 * k;
                star = ExploreIcons.star(star0);
                badgeText = Ui.text(Ui.semibold, 18 * k, 0xFFFFFFFF);
                badgeText.setTextAlign(Paint.Align.CENTER);
                rim.setStyle(Paint.Style.STROKE);
                rim.setStrokeWidth(1.5f * k);
                rim.setColor(0xFFFFFFFF);
                textHalf = Ui.halfCap(text);
                badgeHalf = Ui.halfCap(badgeText);
            }

            /** Ui.halfCap measures under a lock: once per paint (sizes never change after construction). */
            float halfCap(Paint p) {
                Float f = caps.get(p);
                if (f == null) {
                    f = Ui.halfCap(p);
                    caps.put(p, f);
                }
                return f;
            }
        }

        static Meta build(ExploreModel.Title t, ExploreModel.Details d, boolean detail, Painter p, float maxW) {
            Meta m = new Meta();
            if (t == null) return m;
            StringBuilder b = new StringBuilder(t.kind == ExploreModel.TV ? Ui.tr("Series") : Ui.tr("Movie"));
            if (t.year > 0) {
                b.append(" · ").append(t.year);
                if (t.kind == ExploreModel.TV && d != null && d.lastYear > t.year) b.append('–').append(d.lastYear);
            }
            if (d != null && detail) {
                if (t.kind == ExploreModel.MOVIE && d.runtime > 0) b.append(" · ").append(runtime(d.runtime));
                if (t.kind == ExploreModel.TV && d.seasons > 0) {
                    b.append(" · ").append(Ui.plural("plural|one:{n} season|other:{n} seasons", d.seasons));
                }
            }
            String[] genres = d != null ? d.genreNames : null;
            if (genres != null && genres.length > 0) {
                b.append(" · ");
                int n = Math.min(genres.length, detail ? 3 : 2);
                for (int i = 0; i < n; i++) b.append(i > 0 ? ", " : "").append(genres[i]);
            }
            float vote = d != null && d.t != null && d.t.vote > 0 ? d.t.vote : t.vote;
            int votes = d != null && d.t != null ? Math.max(d.t.votes, t.votes) : t.votes;
            m.rating = vote > 0 && votes >= 5 ? String.format(Ui.locale(), "%.1f", vote) : null;
            String cert = d != null ? d.cert : null;
            if (cert != null) cert = cert.trim();
            if (cert != null && cert.length() > 1 && (cert.startsWith("A") || cert.startsWith("a")) && !cert.equalsIgnoreCase("AL")
                    && Character.isDigit(cert.charAt(1))) cert = cert.substring(1);
            if ("AL".equalsIgnoreCase(cert) || "Livre".equalsIgnoreCase(cert)) cert = "L";
            m.cert = cert == null || cert.isEmpty() || cert.length() > 3 ? null : cert.toUpperCase(Locale.ROOT);
            if (m.cert != null) {
                switch (m.cert) {
                    case "L": m.certColor = 0xFF00A651; break;
                    case "10": m.certColor = 0xFF00A3E0; break;
                    case "12": m.certColor = 0xFFF9C80E; m.certText = 0xFF000000; break;
                    case "14": m.certColor = 0xFFF58220; break;
                    case "16": m.certColor = 0xFFE4002B; break;
                    case "18": m.certColor = 0xFF000000; m.certRim = true; break;
                    default: m.certColor = 0xFF48484A;
                }
                if (m.certText == 0) m.certText = 0xFFFFFFFF;
                m.certW = 34 * p.k;
            }
            if (m.rating != null || m.cert != null) b.append(" · ");
            float tail = (m.rating != null ? p.star0 + 8 * p.k + p.text.measureText(m.rating) + 20 * p.k : 0) + m.certW;
            m.lead = TextUtils.ellipsize(b.toString(), new TextPaint(p.text), Math.max(1, maxW - tail), TextUtils.TruncateAt.END).toString();
            m.leadW = p.text.measureText(m.lead);
            if (m.rating != null) m.ratingW = p.text.measureText(m.rating);
            return m;
        }

        static String runtime(int minutes) {
            if (minutes >= 60) return Ui.trf("{h} h {m} min", "h", minutes / 60, "m", minutes % 60);
            return Ui.trf("{n} min", "n", minutes);
        }

        void draw(Canvas c, float x, float baseline, Painter p) {
            Paint t = p.text;
            c.drawText(lead, x, baseline, t);
            x += leadW;
            if (rating != null) {
                int was = p.badge.getColor();
                p.badge.setColor(t.getColor());
                c.save();
                c.translate(x, baseline - p.textHalf - p.star0 / 2);
                c.drawPath(p.star, p.badge);
                c.restore();
                p.badge.setColor(was);
                x += p.star0 + 8 * p.k;
                c.drawText(rating, x, baseline, t);
                x += ratingW + 20 * p.k;
            }
            if (cert != null) {
                float s = 34 * p.k, top = baseline - p.textHalf - s / 2;
                p.badge.setColor(certColor);
                c.drawRoundRect(x, top, x + s, top + s, 6 * p.k, 6 * p.k, p.badge);
                if (certRim) c.drawRoundRect(x + 0.75f * p.k, top + 0.75f * p.k, x + s - 0.75f * p.k, top + s - 0.75f * p.k, 6 * p.k, 6 * p.k, p.rim);
                p.badgeText.setColor(certText);
                c.drawText(cert, x + s / 2, top + s / 2 + p.badgeHalf, p.badgeText);
            }
        }
    }
}
