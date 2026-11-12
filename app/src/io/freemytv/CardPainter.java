package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Draws Explore's cards (every row style) with the focus look: scale, lift, shadow at the resting size, rim, one sheen
 * sweep, the picture's parallax and its fade-in. Paints, gradients and the TOP 10 numerals are made here once; texts
 * are cut in prepare(), never while drawing. Zero allocations in draw().
 */
final class CardPainter {
    static final int BG = 0xFF0B0B0D, INK = 0xFFF5F5F7, DIM = 0xB3F5F5F7, FAINT = 0x80F5F5F7, PILL = 0x2EFFFFFF,
            SELECTED = 0x33FFFFFF, FOCUS = 0xFFF5F5F7, FOCUS_TEXT = 0xFF1D1D1F, PLACEHOLDER = 0xFF1F1F22,
            SKELETON = 0x14FFFFFF, SHEET = 0xF21C1C1E, META = 0xD9F5F5F7;
    /** Two-stop gradients of the genre tiles, picked by genre id. */
    private static final int[][] GENRE_COLORS = {
            {0xFF5B2A86, 0xFF2D1B4E}, {0xFFB23A48, 0xFF5C1A26}, {0xFF1E6091, 0xFF0D2F4F}, {0xFF2A9D8F, 0xFF134E48},
            {0xFFE76F51, 0xFF7A2E1C}, {0xFF6A4C93, 0xFF2E2147}, {0xFF3A86FF, 0xFF16337A}, {0xFFD4A017, 0xFF6B4F07},
            {0xFF8338EC, 0xFF3A1470}, {0xFF2B9348, 0xFF0F4A20}, {0xFFC9184A, 0xFF5E0B22}, {0xFF4A5568, 0xFF1F2430}};

    //                          POSTER WIDE TOP10 CONT GENRE SERV CAST PROV SEAS EPIS INFO CHIP
    private static final float[] W = {220, 400, 340, 400, 320, 300, 150, 260, 180, 320, 1728, 0};
    private static final float[] H = {330, 225, 300, 225, 180, 169, 150, 146, 270, 180, 0, 56};
    private static final float[] R = {14, 16, 14, 16, 18, 18, 75, 18, 14, 14, 0, 28};
    private static final float[] STEP = {248, 432, 360, 432, 348, 328, 190, 284, 208, 352, 0, 16};
    private static final float[] SCALE = {1.08f, 1.06f, 1.08f, 1.06f, 1.06f, 1.06f, 1.10f, 1.06f, 1.08f, 1.06f, 1f, 1.06f};
    private static final float[] PITCH = {450, 360, 420, 360, 270, 270, 300, 236, 410, 330, 0, 110};
    private static final int STYLES = 12;
    static final float TOP10_NUMERAL = 140, INFO_LINE = 80, INFO_FOOT = 32;

    private final Context context;
    final float k;
    final Paint header, cardTitle, cardSub, smallTitle, smallSub, placeholderText, genreName, serviceName, chipText,
            infoLabel, infoValue, infoFoot, moreText, grantTitle;
    private final TextPaint grantSub;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            sheen = new Paint(Paint.ANTI_ALIAS_FLAG), numeral = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Shadows shadows = new Shadows();
    private final LinearGradient[] rims = new LinearGradient[STYLES], sheens = new LinearGradient[STYLES];
    private final LinearGradient[] genres = new LinearGradient[GENRE_COLORS.length];
    private final Bitmap[] numerals = new Bitmap[10];
    private final RectF cell = new RectF(), tmp = new RectF();
    private final float sheenBand;
    // A matched Continue Watching card's title logo (watchLogo): the bottom gradient in unit height, placed per card
    // with one reused matrix.
    private final LinearGradient logoShade = new LinearGradient(0, 0.55f, 0, 1, 0x00000000, 0xB3000000, Shader.TileMode.CLAMP);
    private final Matrix logoMatrix = new Matrix();
    private final Paint logoShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG),
            logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF logoBox = new RectF();
    /** Set by draw() for watchLogo: how far above the inset the logo sits (the progress bar's room), and its alpha. */
    private float logoLift, logoAlpha = 1;
    private final float subOffset, titleOffset, moreHalf, genreHalf, chipHalf;

    CardPainter(Context c, float k) {
        context = c.getApplicationContext();
        this.k = k;
        header = Ui.text(Ui.semibold, 30 * k, Ui.alpha(INK, 0.92f));
        cardTitle = Ui.text(Ui.medium, 24 * k, INK);
        cardSub = Ui.numbers(Ui.regular, 20 * k, DIM);
        smallTitle = Ui.text(Ui.semibold, 22 * k, INK);
        smallSub = Ui.numbers(Ui.regular, 19 * k, DIM);
        placeholderText = Ui.text(Ui.medium, 22 * k, Ui.alpha(INK, 0.7f));
        placeholderText.setTextAlign(Paint.Align.CENTER);
        genreName = Ui.text(Ui.semibold, 32 * k, 0xFFFFFFFF);
        serviceName = Ui.text(Ui.medium, 22 * k, INK);
        chipText = Ui.text(Ui.medium, 26 * k, INK);
        chipText.setTextAlign(Paint.Align.CENTER);
        infoLabel = Ui.text(Ui.regular, 20 * k, DIM);
        infoValue = Ui.numbers(Ui.regular, 24 * k, INK);
        infoFoot = Ui.text(Ui.regular, 20 * k, FAINT);
        moreText = Ui.text(Ui.semibold, 26 * k, INK);
        moreText.setTextAlign(Paint.Align.CENTER);
        grantTitle = Ui.text(Ui.semibold, 26 * k, INK);
        grantSub = new TextPaint(Ui.text(Ui.regular, 20 * k, DIM));
        shadow.setMaskFilter(new BlurMaskFilter(24 * k, BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        sheenBand = 120 * k;
        titleOffset = 40 * k;
        subOffset = 68 * k;
        double a = Math.toRadians(35);
        float dx = (float) Math.sin(a) * sheenBand, dy = (float) Math.cos(a) * sheenBand;
        for (int s = 0; s < STYLES; s++) {
            float h = Math.max(1, H[s] * k);
            rims[s] = new LinearGradient(0, -h / 2, 0, h / 2, 0x80FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP);
            sheens[s] = new LinearGradient(-dx / 2, -dy / 2, dx / 2, dy / 2, new int[] {0, 0x30FFFFFF, 0},
                    new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP);
        }
        float gw = W[Catalog.GENRE] * k, gh = H[Catalog.GENRE] * k;
        for (int i = 0; i < GENRE_COLORS.length; i++) {
            genres[i] = new LinearGradient(0, 0, gw, gh, GENRE_COLORS[i][0], GENRE_COLORS[i][1], Shader.TileMode.CLAMP);
        }
        moreHalf = Ui.halfCap(moreText);
        genreHalf = Ui.halfCap(genreName);
        chipHalf = Ui.halfCap(chipText);
        makeNumerals();
    }

    /** The TOP 10 numerals, once: outlined, faint, wide enough to reach behind the left 40% of the poster. */
    private void makeNumerals() {
        int bw = Math.max(1, Math.round((TOP10_NUMERAL + 0.4f * 200) * k)), bh = Math.max(1, Math.round(300 * k));
        Paint p = Ui.text(Ui.displaySemibold, 260 * k, 0x1AFFFFFF);
        p.setTextAlign(Paint.Align.RIGHT);
        Paint stroke = Ui.text(Ui.displaySemibold, 260 * k, 0x59FFFFFF);
        stroke.setTextAlign(Paint.Align.RIGHT);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4 * k);
        for (int n = 1; n <= 10; n++) {
            String s = Integer.toString(n);
            float tw = p.measureText(s), max = bw - 12 * k, sx = tw > max ? max / tw : 1;
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(sx, 1, bw, 0);
            c.drawText(s, bw - 6 * k, bh - 4 * k, p);
            c.drawText(s, bw - 6 * k, bh - 4 * k, stroke);
            b.prepareToDraw();
            numerals[n - 1] = b;
        }
    }

    float width(int style) { return W[clampStyle(style)] * k; }
    float height(int style) { return H[clampStyle(style)] * k; }
    float step(int style) { return STEP[clampStyle(style)] * k; }
    float pitch(int style) { return PITCH[clampStyle(style)] * k; }
    float focusScale(int style) { return SCALE[clampStyle(style)]; }

    /** The x of the picture inside the card box (TOP 10 cards have the numeral first). */
    float imageLeft(int style) {
        return style == Catalog.TOP10 ? TOP10_NUMERAL * k : 0;
    }

    /** A card's width: chips are measured, everything else has its style's. */
    float cardWidth(Row r, Card card) {
        return r.style == Catalog.CHIP ? card.chipW : width(r.style);
    }

    /** INFO rows: header, two columns of label over value, then the footer lines. */
    float infoHeight(Row r) {
        int lines = r.lines == null ? 0 : r.lines.length;
        return 52 * k + (r.count + 1) / 2 * INFO_LINE * k + lines * INFO_FOOT * k + 24 * k;
    }

    private static int clampStyle(int s) {
        return s < 0 || s >= STYLES ? 0 : s;
    }

    /** Cuts the cards' texts to their widths (UI thread, outside draw); done again for a new language or scale. */
    void prepare(Row r, int from, int to) {
        if (r == null) return;
        from = Math.max(0, from);
        to = Math.min(to, r.count);
        int gen = Ui.generation;
        int style = r.style;
        for (int i = from; i < to; i++) {
            Card c = r.cards[i];
            if (c == null || (c.textGen == gen && c.preparedK == k)) continue;
            c.textGen = gen;
            c.preparedK = k;
            String title = c.title == null ? "" : c.title, sub = c.sub == null ? "" : c.sub;
            float w = textWidth(style);
            switch (style) {
                case Catalog.CAST:
                    c.titleText = cut(title, smallTitle, w);
                    c.subText = cut(sub, smallSub, w);
                    c.titleW = smallTitle.measureText(c.titleText.toString());
                    c.subW = smallSub.measureText(c.subText.toString());
                    break;
                case Catalog.SEASON:
                case Catalog.EPISODE:
                    c.titleText = cut(title, smallTitle, w);
                    c.subText = cut(sub, smallSub, w);
                    break;
                case Catalog.PROVIDER:
                    c.titleText = cut(title, serviceName, w - 110 * k);
                    c.subText = cut(sub, cardSub, w - 110 * k);
                    break;
                case Catalog.SERVICE:
                    c.titleText = cut(title, serviceName, w);
                    c.line1 = cut(title, genreName, w - 40 * k);
                    c.line1W = genreName.measureText(c.line1.toString());
                    break;
                case Catalog.GENRE:
                    c.line1 = cut(title, genreName, w - 44 * k);
                    break;
                case Catalog.CHIP:
                    c.titleText = cut(title, chipText, 600 * k);
                    c.chipW = chipText.measureText(c.titleText.toString()) + 56 * k;
                    break;
                case Catalog.INFO:
                    c.titleText = cut(title, infoLabel, 820 * k);
                    c.subText = cut(sub, infoValue, 820 * k);
                    break;
                default:
                    c.titleText = cut(title, cardTitle, w);
                    c.subText = cut(sub, cardSub, w);
                    break;
            }
            if (c.type == Card.GRANT) {
                c.line1 = cut(title, grantTitle, width(style) - 48 * k);
                StaticLayout.Builder b = StaticLayout.Builder.obtain(sub, 0, sub.length(), grantSub,
                        Math.max(1, Math.round(width(style) - 48 * k)));
                b.setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL);
                c.layout = b.build();
            } else if (c.type == Card.MORE) {
                c.line1 = cut(title, moreText, width(style) - 24 * k);
            } else {
                // The placeholder's text: the title on two centred lines inside the card.
                float inner = Math.max(40 * k, (style == Catalog.TOP10 ? 200 * k : width(style)) - 28 * k);
                twoLines(c, title, inner);
            }
        }
    }

    private float textWidth(int style) {
        switch (style) {
            case Catalog.TOP10: return 200 * k;
            case Catalog.CAST: return 180 * k;
            default: return width(style);
        }
    }

    private static String cut(String s, Paint p, float w) {
        if (s.isEmpty()) return "";
        return TextUtils.ellipsize(s, p instanceof TextPaint ? (TextPaint) p : new TextPaint(p), Math.max(1, w),
                TextUtils.TruncateAt.END).toString();
    }

    private void twoLines(Card c, String title, float inner) {
        if (title.isEmpty()) {
            c.line1 = c.line2 = "";
            c.line1W = c.line2W = 0;
            return;
        }
        int n = placeholderText.breakText(title, true, inner, null);
        if (n >= title.length()) {
            c.line1 = title;
            c.line2 = "";
        } else {
            int sp = title.lastIndexOf(' ', n);
            int end = sp > 0 ? sp : n;
            c.line1 = title.substring(0, end).trim();
            c.line2 = cut(title.substring(end).trim(), placeholderText, inner);
        }
    }

    // MARK: - Drawing

    /** The row's title above its cards (baseline at row top + 34). */
    void drawHeader(Canvas c, String title, float x, float rowTop) {
        if (title != null && !title.isEmpty()) c.drawText(title, x, rowTop + 34 * k, header);
    }

    /** A grey stand-in card while the row loads (static: no pulse, no frames). */
    void drawSkeleton(Canvas c, int style, float x, float y) {
        float w = style == Catalog.CHIP ? 160 * k : width(style), h = height(style), r = R[clampStyle(style)] * k;
        fill.setShader(null);
        fill.setColor(SKELETON);
        float left = x + imageLeft(style);
        if (style == Catalog.TOP10) w = 200 * k;
        c.drawRoundRect(left, y, left + w, y + h, r, r, fill);
    }

    /**
     * One card. (x, y): its resting top-left in the view; f: focus 0..1 (springy); v: the focus spring's velocity;
     * sweep: the sheen's progress 0..1 (outside that, none); img: its TMDB picture (or null), watchImg/watchShader: a
     * Continue Watching picture; now: uptime ms (fade-in).
     */
    void draw(Canvas c, Row r, Card card, float x, float y, float f, float v, float sweep, ExploreImages.Img img,
              Bitmap watchImg, BitmapShader watchShader, long now) {
        draw(c, r, card, x, y, f, v, sweep, img, watchImg, watchShader, now, null);
    }

    /** As above; logo: a matched Continue Watching card's title logo (loaded), drawn over the picture, under the bar. */
    void draw(Canvas c, Row r, Card card, float x, float y, float f, float v, float sweep, ExploreImages.Img img,
              Bitmap watchImg, BitmapShader watchShader, long now, ExploreImages.Img logo) {
        int style = clampStyle(r.style);
        if (style == Catalog.INFO) return;   // drawn whole by drawInfo
        float fa = Ui.clamp(f);
        float bw = style == Catalog.CHIP ? card.chipW : (style == Catalog.TOP10 ? 200 * k : W[style] * k);
        float bh = H[style] * k, r0 = R[style] * k;
        float left = x + imageLeft(style);
        float s = 1 + (SCALE[style] - 1) * f, lift = 8 * k * f;
        float cx = left + bw / 2, cy = y + bh / 2 - lift;
        float dim = card.dim ? 0.45f : 1f;

        if (style == Catalog.TOP10 && card.rank >= 1 && card.rank <= 10) {
            numeral.setAlpha(Math.round(255 * dim));
            c.drawBitmap(numerals[card.rank - 1], x, y, numeral);
        }

        if (fa > 0.01f && style != Catalog.CHIP) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2 + 14 * k, bw / 2, bh / 2 + 14 * k);
            shadow.setColor(Ui.alpha(0x8C000000, fa * dim));
            shadows.draw(c, tmp, r0, 24 * k, shadow);
            c.restore();
        }
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float rr = r0 * s;

        if (style == Catalog.CHIP) {
            drawChip(c, card, fa, dim, rr);
            return;
        }
        if (card.type == Card.MORE || card.type == Card.GRANT) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(card.type == Card.MORE ? 0x24FFFFFF : PLACEHOLDER, dim));
            c.drawRoundRect(cell, rr, rr, fill);
            if (card.type == Card.MORE) {
                drawLine(c, card.line1, cell.centerX(), cell.centerY() + moreHalf, moreText, 1);
            } else {
                c.save();
                c.translate(cell.left, cell.top);
                c.scale(s, s);
                if (card.line1 != null) c.drawText(card.line1.toString(), 24 * k, 48 * k, grantTitle);
                if (card.layout != null) {
                    c.translate(24 * k, 66 * k);
                    card.layout.draw(c);
                }
                c.restore();
            }
            drawFocus(c, style, cx, cy, s, bw, bh, r0, fa, sweep);
            drawBelow(c, style, card, left, y, bh, fa, dim);
            return;
        }

        if (style == Catalog.GENRE) {
            int g = Math.abs(card.id) % genres.length;
            c.save();
            c.translate(cell.left, cell.top);
            c.scale(s, s);
            fill.setShader(genres[g]);
            fill.setAlpha(Math.round(255 * dim));
            c.drawRoundRect(0, 0, bw, bh, r0, r0, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            if (card.line1 != null) c.drawText(card.line1.toString(), 22 * k, bh - 26 * k, genreName);
            c.restore();
            drawFocus(c, style, cx, cy, s, bw, bh, r0, fa, sweep);
            return;
        }

        if (style == Catalog.SERVICE) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(card.color != 0 ? card.color | 0xFF000000 : 0xFF2C2C2E, dim));
            c.drawRoundRect(cell, rr, rr, fill);
            if (img != null) {
                float lw = 88 * k * s;
                tmp.set(cell.centerX() - lw / 2, cell.centerY() - lw / 2, cell.centerX() + lw / 2, cell.centerY() + lw / 2);
                drawImage(c, img.bitmap, img.shader, tmp, 20 * k * s, 0, fadeAlpha(img, now) * dim);
            } else if (card.line1 != null) {
                c.save();
                c.translate(cell.centerX(), cell.centerY());
                c.scale(s, s);
                genreName.setAlpha(Math.round(255 * dim));
                c.drawText(card.line1.toString(), -card.line1W / 2, genreHalf, genreName);
                genreName.setAlpha(255);
                c.restore();
            }
            drawFocus(c, style, cx, cy, s, bw, bh, r0, fa, sweep);
            drawBelow(c, style, card, left, y, bh, fa, dim);
            return;
        }

        if (style == Catalog.PROVIDER) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(fa > 0.5f ? SELECTED : PILL, dim));
            c.drawRoundRect(cell, rr, rr, fill);
            c.save();
            c.translate(cell.left, cell.top);
            c.scale(s, s);
            tmp.set(18 * k, (bh - 64 * k) / 2, 82 * k, (bh + 64 * k) / 2);
            if (img != null) {
                drawImage(c, img.bitmap, img.shader, tmp, 14 * k, 0, fadeAlpha(img, now) * dim);
            } else {
                fill.setColor(Ui.alpha(card.color != 0 ? card.color | 0xFF000000 : 0xFF2C2C2E, dim));
                c.drawRoundRect(tmp, 14 * k, 14 * k, fill);
            }
            serviceName.setAlpha(Math.round(255 * dim));
            cardSub.setAlpha(Math.round(0xB3 * dim));
            if (card.titleText != null) c.drawText(card.titleText.toString(), 98 * k, bh / 2 - 4 * k, serviceName);
            if (card.subText != null) c.drawText(card.subText.toString(), 98 * k, bh / 2 + 26 * k, cardSub);
            serviceName.setAlpha(255);
            cardSub.setAlpha(0xB3);
            c.restore();
            drawFocus(c, style, cx, cy, s, bw, bh, r0, fa, sweep);
            return;
        }

        // Picture cards: POSTER, WIDE, TOP10, CONTINUE, CAST, SEASON, EPISODE.
        Bitmap bmp = null;
        BitmapShader shader = null;
        float alpha = 1;
        if (watchImg != null && watchShader != null) {
            bmp = watchImg;
            shader = watchShader;
        } else if (img != null) {
            bmp = img.bitmap;
            shader = img.shader;
            alpha = fadeAlpha(img, now);
        }
        if (bmp == null || alpha < 1) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(PLACEHOLDER, dim));
            c.drawRoundRect(cell, rr, rr, fill);
            if (bmp == null && card.line1 != null) {
                float base = card.line2 != null && card.line2.length() > 0 ? cell.centerY() - 2 * k : cell.centerY() + 8 * k;
                drawLine(c, card.line1, cell.centerX(), base, placeholderText, dim);
                if (card.line2 != null && card.line2.length() > 0) drawLine(c, card.line2, cell.centerX(), base + 28 * k, placeholderText, dim);
            }
        }
        if (bmp != null && shader != null) {
            float dx = Math.max(-6 * k, Math.min(6 * k, -v * 2.5f * k));
            drawImage(c, bmp, shader, cell, rr, dx, alpha * dim);
        }
        if (logo != null && bmp != null) {
            logoLift = card.progress >= 0 ? 16 * k * s : 0;   // above the 6 px bar and a 10 px gap
            logoAlpha = alpha * dim;
            watchLogo(c, cell, fa, logo);
            logoLift = 0;
            logoAlpha = 1;
        }
        if (card.progress >= 0) {
            float inset = 16 * k * s, bar = 6 * k * s, bottom = cell.bottom - inset;
            fill.setShader(null);
            fill.setColor(0x59FFFFFF);
            tmp.set(cell.left + inset, bottom - bar, cell.right - inset, bottom);
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
            fill.setColor(0xFFFFFFFF);
            tmp.right = tmp.left + Math.max(bar, tmp.width() * Math.min(1, card.progress));
            c.drawRoundRect(tmp, bar / 2, bar / 2, fill);
        }
        drawFocus(c, style, cx, cy, s, bw, bh, r0, fa, sweep);
        drawBelow(c, style, card, left, y, bh, fa, dim);
    }

    private void drawChip(Canvas c, Card card, float fa, float dim, float rr) {
        fill.setShader(null);
        fill.setColor(blend(card.dim ? SELECTED : PILL, FOCUS, fa));
        c.drawRoundRect(cell, rr, rr, fill);
        chipText.setColor(blend(INK, FOCUS_TEXT, fa));
        if (card.titleText != null) {
            c.drawText(card.titleText.toString(), cell.centerX(), cell.centerY() + chipHalf, chipText);
        }
        chipText.setColor(INK);
    }

    /** Rim and sheen, drawn at the resting size inside the card's scale (the gradients are made once per style). */
    private void drawFocus(Canvas c, int style, float cx, float cy, float s, float bw, float bh, float r0, float fa, float sweep) {
        if (fa <= 0.01f) return;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        if (sweep >= 0 && sweep < 1) {
            // The band crosses the card once along the 35° diagonal: the canvas moves, the shape stays.
            double a = Math.toRadians(35);
            float reach = (bw + bh) / 2 + sheenBand;
            float d = -reach + 2 * reach * smooth(sweep);
            float ox = (float) Math.sin(a) * d, oy = (float) Math.cos(a) * d;
            c.translate(ox, oy);
            sheen.setShader(sheens[style]);
            sheen.setAlpha(Math.round(255 * fa));
            c.drawRoundRect(tmp.left - ox, tmp.top - oy, tmp.right - ox, tmp.bottom - oy, r0, r0, sheen);
            c.translate(-ox, -oy);
        }
        rim.setShader(rims[style]);
        rim.setAlpha(Math.round(255 * fa));
        rim.setStrokeWidth(2 * k / s);
        c.drawRoundRect(tmp, r0, r0, rim);
        c.restore();
    }

    /** The texts under a card (POSTER and TOP10 only when focused, the others always). */
    private void drawBelow(Canvas c, int style, Card card, float left, float y, float bh, float fa, float dim) {
        float bottom = y + bh;
        switch (style) {
            case Catalog.POSTER:
            case Catalog.TOP10:
                if (fa <= 0.01f) return;
                textPair(c, card, left, bottom + titleOffset + 8 * k * fa, bottom + subOffset + 8 * k * fa, cardTitle, cardSub, fa * dim);
                return;
            case Catalog.WIDE:
            case Catalog.CONTINUE:
                if (card.type == Card.GRANT) return;
                textPair(c, card, left, bottom + titleOffset, bottom + subOffset, cardTitle, cardSub, dim);
                return;
            case Catalog.SERVICE:
                if (card.titleText != null) {
                    serviceName.setAlpha(Math.round(255 * dim));
                    c.drawText(card.titleText.toString(), left, bottom + 36 * k, serviceName);
                    serviceName.setAlpha(255);
                }
                return;
            case Catalog.CAST:
                if (card.titleText != null) {
                    float tw = card.titleW, sw = card.subW;
                    float center = left + W[Catalog.CAST] * k / 2;
                    smallTitle.setAlpha(Math.round(255 * dim));
                    c.drawText(card.titleText.toString(), center - tw / 2, bottom + 38 * k, smallTitle);
                    smallTitle.setAlpha(255);
                    if (card.subText != null) c.drawText(card.subText.toString(), center - sw / 2, bottom + 64 * k, smallSub);
                }
                return;
            case Catalog.SEASON:
            case Catalog.EPISODE:
                textPair(c, card, left, bottom + 36 * k, bottom + 62 * k, smallTitle, smallSub, dim);
                return;
            default:
        }
    }

    private void textPair(Canvas c, Card card, float x, float y1, float y2, Paint title, Paint sub, float a) {
        int ta = title.getAlpha(), sa = sub.getAlpha();
        title.setAlpha(Math.round(ta * a));
        sub.setAlpha(Math.round(sa * a));
        if (card.titleText != null && card.titleText.length() > 0) c.drawText(card.titleText.toString(), x, y1, title);
        if (card.subText != null && card.subText.length() > 0) c.drawText(card.subText.toString(), x, y2, sub);
        title.setAlpha(ta);
        sub.setAlpha(sa);
    }

    private void drawLine(Canvas c, CharSequence s, float cx, float baseline, Paint p, float a) {
        if (s == null || s.length() == 0) return;
        int pa = p.getAlpha();
        p.setAlpha(Math.round(pa * a));
        Paint.Align was = p.getTextAlign();
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(s.toString(), cx, baseline, p);
        p.setTextAlign(was);
        p.setAlpha(pa);
    }

    /** The picture cropped at its centre to the cell, 4% larger (so the parallax never shows an edge), slid by dx. */
    void drawImage(Canvas c, Bitmap b, BitmapShader shader, RectF cell, float r, float dx, float alpha) {
        if (b == null || shader == null || alpha <= 0) return;
        float iw = b.getWidth(), ih = b.getHeight();
        if (iw <= 0 || ih <= 0) return;
        float scale = Math.max(cell.width() / iw, cell.height() / ih) * 1.04f;
        c.save();
        c.translate(cell.centerX() + dx, cell.centerY());
        c.scale(scale, scale);
        c.translate(-iw / 2, -ih / 2);
        float l = iw / 2 + (cell.left - cell.centerX() - dx) / scale, t = ih / 2 + (cell.top - cell.centerY()) / scale;
        float rr = r / scale;
        image.setShader(shader);
        image.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRoundRect(l, t, l + cell.width() / scale, t + cell.height() / scale, rr, rr, image);
        image.setShader(null);
        image.setAlpha(255);
        c.restore();
    }

    /** The INFO block: label over value, two columns, then the footer lines. */
    void drawInfo(Canvas c, Row r, float x, float rowTop) {
        float top = rowTop + 52 * k, col = 864 * k;
        for (int i = 0; i < r.count; i++) {
            Card card = r.cards[i];
            float cx = x + (i % 2) * col, cy = top + (i / 2) * INFO_LINE * k;
            if (card.titleText != null) c.drawText(card.titleText.toString(), cx, cy + 22 * k, infoLabel);
            if (card.subText != null) c.drawText(card.subText.toString(), cx, cy + 54 * k, infoValue);
        }
        if (r.lines == null) return;
        float y = top + (r.count + 1) / 2 * INFO_LINE * k + 20 * k;
        for (CharSequence line : r.lines) {
            if (line != null) c.drawText(line.toString(), x, y, infoFoot);
            y += INFO_FOOT * k;
        }
    }

    /** 0..1 over the 160 ms after the picture arrived; 1 for any time base that doesn't match (never a stuck fade). */
    static float fadeAlpha(ExploreImages.Img img, long now) {
        long age = now - img.arrived;
        if (age < 0 || age >= 160) return 1;
        return Math.max(0.05f, age / 160f);
    }

    /** True while the picture is still fading in (the row needs another frame). */
    static boolean fading(ExploreImages.Img img, long now) {
        long age = now - img.arrived;
        return age >= 0 && age < 160;
    }

    private static float smooth(float t) {
        t = Ui.clamp(t);
        return t * t * (3 - 2 * t);
    }

    static int blend(int a, int b, float t) {
        t = Ui.clamp(t);
        int out = 0;
        for (int sh = 0; sh < 32; sh += 8) {
            int x = (a >>> sh) & 255, y = (b >>> sh) & 255;
            out |= Math.round(x + (y - x) * t) << sh;
        }
        return out;
    }

    /**
     * A matched Continue Watching card's TMDB title logo, bottom-left inside the card (card: its rectangle as drawn, at
     * its focus scale), at most 40% of its width and 28% of its height, inset 16, over a bottom gradient across the
     * lower 45% that keeps it readable on any still. f: the card's focus (0..1); the logo doesn't change with it.
     */
    void watchLogo(Canvas c, RectF card, float f, ExploreImages.Img logo) {
        if (logo == null || logo.bitmap == null || card.width() <= 0 || card.height() <= 0 || logoAlpha <= 0) return;
        float s = card.width() / (W[Catalog.CONTINUE] * k), rr = R[Catalog.CONTINUE] * k * s;
        logoMatrix.setScale(1, card.height());
        logoMatrix.postTranslate(0, card.top);
        logoShade.setLocalMatrix(logoMatrix);
        logoShadePaint.setShader(logoShade);
        logoShadePaint.setAlpha(Math.round(255 * Math.min(1, logoAlpha)));
        c.drawRoundRect(card, rr, rr, logoShadePaint);
        float bw = logo.bitmap.getWidth(), bh = logo.bitmap.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float scale = Math.min(card.width() * 0.40f / bw, card.height() * 0.28f / bh);
        float inset = 16 * k * s, left = card.left + inset, bottom = card.bottom - inset - logoLift;
        logoBox.set(left, bottom - bh * scale, left + bw * scale, bottom);
        logoPaint.setAlpha(Math.round(255 * Math.min(1, logoAlpha)));
        c.drawBitmap(logo.bitmap, null, logoBox, logoPaint);
    }
}
