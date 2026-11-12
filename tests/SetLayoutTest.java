package io.freemytv;

/** SetLayout's geometry (SPEC3 §7), on the plain JDK: javac SetLayout.java tests/SetLayoutTest.java, then java. */
public final class SetLayoutTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void near(float got, float want, String what) {
        check(Math.abs(got - want) < 0.001f, what + ": " + got + ", wanted " + want);
    }

    private static final int R = SetLayout.ROW, H = SetLayout.HEADER, G = SetLayout.GAP_ENTRY;
    private static final float WINDOW = SetLayout.VIEW_BOTTOM - SetLayout.TOP;   // 832

    public static void main(String[] args) {
        root();
        tops();
        scroll();
        alpha();
        hit();
        System.out.println("SetLayoutTest: " + assertions + " assertions passed");
    }

    /** The root: 10 rows in groups of 2, 6 and 2 end at 1004, inside the window, and never scroll. */
    private static void root() {
        int[] kinds = {R, R, G, R, R, R, R, R, R, G, R, R};
        float[] tops = new float[kinds.length];
        float height = SetLayout.tops(kinds, tops);
        near(SetLayout.TOP + height, 1004, "the root's bottom");
        check(SetLayout.TOP + height <= SetLayout.VIEW_BOTTOM, "the root fits");
        near(SetLayout.maxScroll(height), 0, "the root doesn't scroll");
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] != R) continue;
            near(SetLayout.scrollFor(tops[i], 0, height), 0, "the root stays put on row " + i);
            near(SetLayout.alpha(tops[i], 0, false), 1, "row " + i + " is whole");
            check(SetLayout.hit(1440, tops[i] + SetLayout.ROW_H / 2, kinds, tops, 0) == i, "row " + i + " is hit");
        }
        near(tops[0], 188, "first row");
        near(tops[3], 188 + 2 * 78 + 24, "first row of the second group");
        near(tops[11], 938, "last row");
    }

    private static void tops() {
        int[] kinds = {H, R, R, G, R};
        float[] tops = new float[kinds.length];
        float height = SetLayout.tops(kinds, tops);
        float[] want = {188, 246, 324, 402, 426};
        for (int i = 0; i < want.length; i++) near(tops[i], want[i], "top " + i);
        near(height, 426 + 66 - 188, "content height");
        near(SetLayout.tops(new int[0], new float[0]), 0, "an empty page");
        near(SetLayout.tops(new int[] {R}, new float[1]), SetLayout.ROW_H, "one row");
        // A trailing gap adds nothing below the last row.
        near(SetLayout.tops(new int[] {R, G}, new float[2]), SetLayout.ROW_H, "a trailing gap");
        near(SetLayout.tops(new int[] {R, H}, new float[2]), SetLayout.PITCH + SetLayout.HEADER_H, "a trailing header");
    }

    /** ↓ through 30 rows and ↑ back: the focused row stays in the band, the scroll inside the content. */
    private static void scroll() {
        int n = 30;
        int[] kinds = new int[n];
        float[] tops = new float[n];
        float height = SetLayout.tops(kinds, tops);
        near(height, 29 * 78 + 66, "30 rows high");
        float max = height - WINDOW;
        near(SetLayout.maxScroll(height), max, "max scroll");
        float s = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < n; j++) {
                int i = pass == 0 ? j : n - 1 - j;
                float before = s;
                s = SetLayout.scrollFor(tops[i], s, height);
                check(s >= 0 && s <= max + 0.001f, "scroll " + s + " inside 0.." + max + " at row " + i);
                check(pass == 0 ? s >= before - 0.001f : s <= before + 0.001f, "the list only follows the focus, row " + i);
                float top = tops[i] - s, bottom = top + SetLayout.ROW_H;
                boolean clamped = s <= 0.001f || s >= max - 0.001f;
                if (!clamped) {
                    check(top >= SetLayout.BAND_TOP - 0.001f && bottom <= SetLayout.BAND_BOTTOM + 0.001f,
                            "row " + i + " in the band: " + top + ".." + bottom);
                }
                check(top >= SetLayout.TOP - 0.001f && bottom <= SetLayout.VIEW_BOTTOM + 0.001f,
                        "row " + i + " on screen: " + top + ".." + bottom);
                near(SetLayout.scrollFor(tops[i], s, height), s, "settled at row " + i);
            }
            near(s, pass == 0 ? max : 0, pass == 0 ? "the end shows the last row" : "back at the top");
        }
        near(SetLayout.clampScroll(-5, height), 0, "never above the top");
        near(SetLayout.clampScroll(1e6f, height), max, "never past the end");
        near(SetLayout.scrollFor(tops[29], 99999, height), max, "a stale scroll is clamped");
        near(SetLayout.scrollFor(tops[0], 0, 66), 0, "a short page stays");
        float c = SetLayout.centred(tops[15], height);
        near(tops[15] - c + SetLayout.ROW_H / 2, (SetLayout.BAND_TOP + SetLayout.BAND_BOTTOM) / 2, "centred");
        near(SetLayout.centred(tops[0], height), 0, "centred, clamped at the top");
        near(SetLayout.centred(tops[29], height), max, "centred, clamped at the end");
    }

    private static void alpha() {
        // Inside the window.
        for (float top = SetLayout.FADE_TOP; top <= SetLayout.FADE_BOTTOM; top += 13) {
            near(SetLayout.alpha(top, 0, true), 1, "whole at " + top);
            near(SetLayout.alpha(top + 500, 500, true), 1, "whole at " + top + ", scrolled");
        }
        // The bottom edge.
        near(SetLayout.alpha(SetLayout.FADE_BOTTOM, 0, false), 1, "the fade starts at 940");
        near(SetLayout.alpha(SetLayout.VIEW_BOTTOM, 0, false), SetLayout.EDGE_ALPHA, "0.3 at the bottom edge");
        near(SetLayout.alpha(SetLayout.VIEW_BOTTOM + 300, 0, false), SetLayout.EDGE_ALPHA, "0.3 past it");
        float last = 1;
        for (float top = SetLayout.FADE_BOTTOM; top <= SetLayout.VIEW_BOTTOM; top += 4) {
            float a = SetLayout.alpha(top, 0, false);
            check(a <= last + 1e-6f && a >= SetLayout.EDGE_ALPHA - 1e-6f, "monotonic at the bottom, " + top);
            last = a;
        }
        near(SetLayout.alpha(980, 0, false), 0.65f, "half-way down the fade");
        // The top edge, only when scrolled.
        float topEdge = SetLayout.VIEW_TOP - SetLayout.ROW_H;   // its bottom at 172
        near(SetLayout.alpha(topEdge, 0, false), 1, "no top fade at rest");
        near(SetLayout.alpha(topEdge, 0, true), SetLayout.EDGE_ALPHA, "0.3 at the top edge");
        near(SetLayout.alpha(SetLayout.FADE_TOP - SetLayout.ROW_H, 0, true), 1, "the top fade starts at 260");
        last = SetLayout.EDGE_ALPHA;
        for (float bottom = SetLayout.VIEW_TOP; bottom <= SetLayout.FADE_TOP; bottom += 4) {
            float a = SetLayout.alpha(bottom - SetLayout.ROW_H + 700, 700, true);
            check(a >= last - 1e-6f && a <= 1 + 1e-6f, "monotonic at the top, " + bottom);
            last = a;
        }
    }

    private static void hit() {
        int[] kinds = {H, R, R, G, R, R, R, R, R, R, R, R, R, R, R, R, R, R, R, R};
        float[] tops = new float[kinds.length];
        float height = SetLayout.tops(kinds, tops);
        float x = (SetLayout.LIST_L + SetLayout.LIST_R) / 2;
        float[] scrolls = {0, 137, SetLayout.maxScroll(height)};
        check(scrolls[2] > 137, "the page scrolls");
        for (float s : scrolls) {
            for (int i = 0; i < kinds.length; i++) {
                float cy = tops[i] - s + (kinds[i] == H ? SetLayout.HEADER_H : kinds[i] == G ? SetLayout.GAP : SetLayout.ROW_H) / 2;
                boolean inWindow = cy >= SetLayout.VIEW_TOP && cy <= SetLayout.VIEW_BOTTOM;
                int got = SetLayout.hit(x, cy, kinds, tops, s);
                if (kinds[i] == R && inWindow) {
                    check(got == i, "row " + i + " at scroll " + s + ": " + got);
                    check(SetLayout.hit(SetLayout.LIST_L, cy, kinds, tops, s) == i, "its left edge");
                    check(SetLayout.hit(SetLayout.LIST_R, cy, kinds, tops, s) == i, "its right edge");
                    check(SetLayout.hit(SetLayout.LIST_L - 1, cy, kinds, tops, s) == -1, "left of the list");
                    check(SetLayout.hit(SetLayout.LIST_R + 1, cy, kinds, tops, s) == -1, "right of the list");
                    check(SetLayout.hit(500, cy, kinds, tops, s) == -1, "the left pane");
                } else if (kinds[i] == R) {
                    check(got == -1, "row " + i + " is outside the window at scroll " + s);
                } else if (kinds[i] == G) {
                    check(got == -1, "a gap at scroll " + s + ": " + got);
                } else {
                    // A header's middle is 29 px down a 58 px block that no row overlaps.
                    check(got == -1, "a header at scroll " + s + ": " + got);
                }
            }
            // Between two rows (the 12 px gap).
            check(SetLayout.hit(x, tops[1] - s + SetLayout.ROW_H + 6, kinds, tops, s) == -1
                    || tops[1] - s + SetLayout.ROW_H + 6 < SetLayout.VIEW_TOP, "between rows");
        }
        check(SetLayout.hit(x, SetLayout.VIEW_TOP - 1, kinds, tops, 137) == -1, "above the window");
        check(SetLayout.hit(x, SetLayout.VIEW_BOTTOM + 1, kinds, tops, 0) == -1, "below the window");
        check(SetLayout.hit(x, 500, new int[0], new float[0], 0) == -1, "an empty page");
    }
}
