package io.freemytv;

/**
 * The settings list's geometry (SPEC3 §2.1, §2.5, §2.9), in 1920x1080 design units, pure Java (no android.*) so
 * tests/SetLayoutTest.java runs on the plain JDK. Tops are content positions (the first entry at TOP); a row is drawn
 * at top − scroll. Content heights are measured from TOP, so a page scrolls from 0 to height − (VIEW_BOTTOM − TOP).
 */
final class SetLayout {
    private SetLayout() {}

    static final float LIST_L = 1040, LIST_R = 1840, TOP = 188, ROW_H = 66, PITCH = 78, HEADER_H = 58, GAP = 24,
            VIEW_TOP = 172, VIEW_BOTTOM = 1020, BAND_TOP = 380, BAND_BOTTOM = 720, FADE_TOP = 260, FADE_BOTTOM = 940;
    static final int ROW = 0, HEADER = 1, GAP_ENTRY = 2;
    /** The alpha a row fades to at the window's edges. */
    static final float EDGE_ALPHA = 0.3f;

    /** Writes each entry's top into out (out.length ≥ kinds.length); returns the content height (last bottom − TOP). */
    static float tops(int[] kinds, float[] out) {
        float y = TOP, bottom = TOP;
        for (int i = 0; i < kinds.length; i++) {
            out[i] = y;
            switch (kinds[i]) {
                case ROW:
                    bottom = y + ROW_H;
                    y += PITCH;
                    break;
                case HEADER:
                    bottom = y + HEADER_H;
                    y += HEADER_H;
                    break;
                default:
                    y += GAP;
                    break;
            }
        }
        return bottom - TOP;
    }

    /** The largest scroll of a content this high (0 when it fits in the window). */
    static float maxScroll(float contentHeight) {
        return Math.max(0, contentHeight - (VIEW_BOTTOM - TOP));
    }

    /** The scroll that keeps the row at rowTop inside y BAND_TOP..BAND_BOTTOM, moving as little as it can, clamped. */
    static float scrollFor(float rowTop, float scroll, float contentHeight) {
        float s = scroll, drawn = rowTop - s;
        if (drawn < BAND_TOP) s = rowTop - BAND_TOP;
        else if (drawn + ROW_H > BAND_BOTTOM) s = rowTop + ROW_H - BAND_BOTTOM;
        return clampScroll(s, contentHeight);
    }

    /** The scroll that puts the row at rowTop in the band's middle (a picker opening on its current choice). */
    static float centred(float rowTop, float contentHeight) {
        return clampScroll(rowTop + ROW_H / 2 - (BAND_TOP + BAND_BOTTOM) / 2, contentHeight);
    }

    static float clampScroll(float s, float contentHeight) {
        return Math.max(0, Math.min(maxScroll(contentHeight), s));
    }

    /**
     * A row's alpha: 1 inside the window; a top below FADE_BOTTOM fades linearly to EDGE_ALPHA at VIEW_BOTTOM, and (only
     * when scrolled) a bottom above FADE_TOP fades to EDGE_ALPHA at VIEW_TOP.
     */
    static float alpha(float rowTop, float scroll, boolean scrolled) {
        float top = rowTop - scroll, a = 1;
        if (top > FADE_BOTTOM) {
            float t = Math.min(1, (top - FADE_BOTTOM) / (VIEW_BOTTOM - FADE_BOTTOM));
            a = Math.min(a, 1 - (1 - EDGE_ALPHA) * t);
        }
        float bottom = top + ROW_H;
        if (scrolled && bottom < FADE_TOP) {
            float t = Math.min(1, (FADE_TOP - bottom) / (FADE_TOP - VIEW_TOP));
            a = Math.min(a, 1 - (1 - EDGE_ALPHA) * t);
        }
        return a;
    }

    /** The ROW entry under (x, y) (design units), or −1: headers, gaps, the left pane, outside the list's window. */
    static int hit(float x, float y, int[] kinds, float[] tops, float scroll) {
        if (x < LIST_L || x > LIST_R || y < VIEW_TOP || y > VIEW_BOTTOM) return -1;
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] != ROW) continue;
            float top = tops[i] - scroll;
            if (y >= top && y <= top + ROW_H) return i;
        }
        return -1;
    }
}
