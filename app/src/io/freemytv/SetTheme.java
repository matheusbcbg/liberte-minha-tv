package io.freemytv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * The settings' look (SPEC3 §2.2, §2.3): colours, every Paint and gradient, made once per (scale, appearance) in make(),
 * never while drawing. Dark uses the new home's tokens (CardPainter), so a change there reaches the settings. Light
 * shows only in the Classic layout with a light theme, whose home is light too.
 */
final class SetTheme {
    // Geometry of the left pane, in design units (§2.1).
    static final float PANE_CX = 500, PLATTER_L = 290, PLATTER_T = 232, PLATTER_R = 710, PLATTER_B = 652,
            PLATTER_RADIUS = 92, ICON_SIZE = 210, ICON_CY = 442, DESC_TOP = 700, DESC_W = 640, DESC_LINE = 38,
            BIG_BASELINE = 732, DESC_TOP_UNDER_BIG = 764, CAPTION_BASELINE = 1000, TITLE_BASELINE = 116,
            TITLE_W = 1400, SMALL_ICON = 30;

    final boolean dark;
    final float k;
    final int bg, title, ink, secondary, chevron, row, rowEdge, focus, focusInk, focusSecondary, focusChevron,
            focusShadow, material, materialTop, sheet, scrim, green, orange, red, switchOff, switchOffFocused, link,
            white = 0xFFFFFFFF;
    final float focusShadowDy, materialShadowDy = 20, platterShadowDy;

    final Paint wall = new Paint(), fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG), spinnerBig = new Paint(Paint.ANTI_ALIAS_FLAG),
            platter = new Paint(Paint.ANTI_ALIAS_FLAG), brand = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), hairTop = new Paint(Paint.ANTI_ALIAS_FLAG),
            hairBottom = new Paint(Paint.ANTI_ALIAS_FLAG), rowShadow = new Paint(Paint.ANTI_ALIAS_FLAG),
            materialShadow = new Paint(Paint.ANTI_ALIAS_FLAG), platterShadow = new Paint(Paint.ANTI_ALIAS_FLAG),
            bitmap = new Paint();
    final Paint paneTitle, rowTitle, rowTitleSmall, value, valueNumbers, index, header, headerRight, metricTitle,
            badge, caption, bigNumber, sheetTitle, button;
    final TextPaint desc, step, sheetBody, toast, linkText;
    final float rowTitleHalf, rowTitleSmallHalf, valueHalf, indexHalf, metricHalf, badgeHalf, buttonHalf;
    final Shadows shadows = new Shadows();

    private SetTheme(float k, boolean dark) {
        this.k = k;
        this.dark = dark;
        bg = dark ? CardPainter.BG : 0xFFF5F5F7;
        title = dark ? CardPainter.FAINT : 0xFF6E6E73;
        ink = dark ? CardPainter.INK : 0xFF1D1D1F;
        secondary = dark ? CardPainter.DIM : 0xFF6E6E73;
        chevron = dark ? 0x66F5F5F7 : 0xFFAEAEB2;
        row = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        rowEdge = dark ? 0x14FFFFFF : 0x14000000;
        focus = dark ? CardPainter.FOCUS : 0xFF1D1D1F;
        focusInk = dark ? CardPainter.FOCUS_TEXT : 0xFFF5F5F7;
        focusSecondary = dark ? 0xFF6E6E73 : 0xFFAEAEB2;
        focusChevron = 0xFF8E8E93;
        focusShadow = dark ? 0x66000000 : 0x33000000;
        focusShadowDy = dark ? 20 : 12;
        platterShadowDy = 14;
        material = dark ? 0xD91C1C1E : 0xF5FFFFFF;
        materialTop = dark ? 0x40FFFFFF : 0xB3FFFFFF;
        sheet = dark ? CardPainter.SHEET : 0xFAFFFFFF;
        scrim = dark ? 0x99000000 : 0x66000000;
        green = dark ? 0xFF30D158 : 0xFF34C759;
        orange = dark ? 0xFFFF9F0A : 0xFFC93400;
        red = dark ? 0xFFFF453A : 0xFFD70015;
        switchOff = dark ? 0x29FFFFFF : 0x1F000000;
        switchOffFocused = dark ? 0x29000000 : 0x33FFFFFF;
        link = dark ? 0xFF64D2FF : 0xFF0071A4;

        int height = Math.max(2, Math.round(1080 * k));
        wall.setShader(Ui.gradient(height, dark ? 0xFF141417 : 0xFFFFFFFF, dark ? CardPainter.BG : 0xFFE8E8ED));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(3.5f * k);
        spinner.setStyle(Paint.Style.STROKE);
        spinner.setStrokeCap(Paint.Cap.ROUND);
        spinner.setStrokeWidth(3 * k);
        spinnerBig.setStyle(Paint.Style.STROKE);
        spinnerBig.setStrokeCap(Paint.Cap.ROUND);
        spinnerBig.setStrokeWidth(8 * k);
        platter.setShader(new LinearGradient(0, PLATTER_T * k, 0, PLATTER_B * k,
                dark ? 0xFF3A3A3C : 0xFFFFFFFF, dark ? 0xFF2C2C2E : 0xFFF2F2F7, Shader.TileMode.CLAMP));
        // 135°: from the top-left corner to the bottom-right one (SPEC2's tile of the app).
        brand.setShader(new LinearGradient(PLATTER_L * k, PLATTER_T * k, PLATTER_R * k, PLATTER_B * k,
                0xFF0A84FF, 0xFF5E5CE6, Shader.TileMode.CLAMP));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
        rim.setShader(new LinearGradient(0, PLATTER_T * k, 0, PLATTER_B * k, 0x80FFFFFF, 0x14FFFFFF,
                Shader.TileMode.CLAMP));
        // The material's hairlines, in the panel's own coordinates (drawn translated to its top, or bottom − 24).
        hairTop.setStyle(Paint.Style.STROKE);
        hairTop.setStrokeWidth(1.5f * k);
        hairTop.setShader(new LinearGradient(0, 0, 0, 24 * k, materialTop, materialTop & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        hairBottom.setStyle(Paint.Style.STROKE);
        hairBottom.setStrokeWidth(1 * k);
        hairBottom.setShader(new LinearGradient(0, 0, 0, 24 * k, 0x00000000, 0x26000000, Shader.TileMode.CLAMP));
        rowShadow.setColor(focusShadow);
        rowShadow.setMaskFilter(new BlurMaskFilter((dark ? 34 : 30) * k, BlurMaskFilter.Blur.NORMAL));
        materialShadow.setColor(dark ? 0x8C000000 : 0x33000000);
        materialShadow.setMaskFilter(new BlurMaskFilter(40 * k, BlurMaskFilter.Blur.NORMAL));
        platterShadow.setColor(0x1F000000);
        platterShadow.setMaskFilter(new BlurMaskFilter(40 * k, BlurMaskFilter.Blur.NORMAL));
        bitmap.setFilterBitmap(false);   // the QR code: each module stays a sharp square

        paneTitle = Ui.text(Ui.displaySemibold, 48 * k, title);
        paneTitle.setTextAlign(Paint.Align.CENTER);
        rowTitle = Ui.text(Ui.medium, 34 * k, ink);
        rowTitleSmall = Ui.text(Ui.medium, 31 * k, ink);
        value = Ui.text(Ui.regular, 31 * k, secondary);
        value.setTextAlign(Paint.Align.RIGHT);
        valueNumbers = Ui.numbers(Ui.regular, 31 * k, secondary);
        valueNumbers.setTextAlign(Paint.Align.RIGHT);
        index = Ui.numbers(Ui.regular, 31 * k, secondary);
        header = Ui.text(Ui.semibold, 26 * k, title);
        headerRight = Ui.text(Ui.semibold, 26 * k, title);
        headerRight.setTextAlign(Paint.Align.RIGHT);
        metricTitle = Ui.text(Ui.medium, 31 * k, ink);
        badge = Ui.text(Ui.semibold, 23 * k, 0xFFFFFFFF);
        badge.setTextAlign(Paint.Align.CENTER);
        caption = Ui.text(Ui.regular, 23 * k, title);
        caption.setTextAlign(Paint.Align.CENTER);
        bigNumber = Ui.numbers(Ui.displaySemibold, 57 * k, ink);
        bigNumber.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 44 * k, ink);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        button = Ui.text(Ui.medium, 34 * k, ink);
        desc = new TextPaint(Ui.text(Ui.regular, 29 * k, secondary));
        step = new TextPaint(Ui.text(Ui.medium, 31 * k, ink));
        sheetBody = new TextPaint(Ui.text(Ui.regular, 31 * k, ink));
        toast = new TextPaint(Ui.text(Ui.medium, 27 * k, ink));
        linkText = new TextPaint(Ui.numbers(Ui.medium, 25 * k, link));
        rowTitleHalf = Ui.halfCap(rowTitle);
        rowTitleSmallHalf = Ui.halfCap(rowTitleSmall);
        valueHalf = Ui.halfCap(value);
        indexHalf = Ui.halfCap(index);
        metricHalf = Ui.halfCap(metricTitle);
        badgeHalf = Ui.halfCap(badge);
        buttonHalf = Ui.halfCap(button);
    }

    static SetTheme make(float k, boolean dark) {
        return new SetTheme(k > 0 ? k : 1, dark);
    }

    /** Dark with the Modern home (always dark) or at night; light only for Classic with a light (or daytime) theme. */
    static boolean dark(Context c) {
        try {
            return StartSettings.modern() || HomeData.get(c).isNight(System.currentTimeMillis());
        } catch (RuntimeException e) {
            Safe.log("settings: theme", e);
            return true;
        }
    }

    /** The window's background before the first frame (SettingsActivity). */
    static int bg(boolean dark) {
        return dark ? CardPainter.BG : 0xFFF5F5F7;
    }

    /** a → b by t (each channel, alpha included). */
    static int blend(int a, int b, float t) {
        if (t <= 0) return a;
        if (t >= 1) return b;
        int out = 0;
        for (int s = 0; s < 32; s += 8) {
            int x = (a >>> s) & 255, y = (b >>> s) & 255;
            out |= Math.round(x + (y - x) * t) << s;
        }
        return out;
    }

    /** color with its alpha multiplied by a. */
    static int alpha(int color, float a) {
        return Ui.alpha(color, a);
    }

    /** Wrapped text whose lines are `lineHeight` apart, at most maxLines (the last one ellipsized). Not in draw. */
    static StaticLayout layout(CharSequence text, TextPaint paint, float width, int maxLines,
                               Layout.Alignment align, float lineHeight) {
        CharSequence t = text == null ? "" : text;
        int w = Math.max(1, Math.round(width));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float natural = fm.descent - fm.ascent;
        return StaticLayout.Builder.obtain(t, 0, t.length(), paint, w)
                .setAlignment(align)
                .setLineSpacing(Math.max(0, lineHeight - natural), 1f)
                .setIncludePad(false)
                .setMaxLines(Math.max(1, maxLines))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** text cut with "…" to fit width at paint's size (at refresh, never in draw). */
    static String fit(String text, Paint paint, float width) {
        if (text == null || text.isEmpty()) return "";
        if (width <= 0) return "";
        if (paint.measureText(text) <= width) return text;
        TextPaint tp = paint instanceof TextPaint ? (TextPaint) paint : new TextPaint(paint);
        return TextUtils.ellipsize(text, tp, width, TextUtils.TruncateAt.END).toString();
    }
}
