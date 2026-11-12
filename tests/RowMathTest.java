package io.freemytv;

public final class RowMathTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void near(float actual, float expected, String reason) {
        check(Math.abs(actual - expected) < 0.01f, reason + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        float x0 = 96, w = 1920, step = 248, card = 220;
        // The first cards don't scroll; the first one is always at x0.
        near(RowMath.scrollTarget(0, 20, step, card, x0, w), 0, "first card");
        near(RowMath.scrollTarget(-3, 20, step, card, x0, w), 0, "negative focus");
        near(RowMath.scrollTarget(3, 0, step, card, x0, w), 0, "empty row");
        for (int i = 0; i < 20; i++) {
            float s = RowMath.scrollTarget(i, 20, step, card, x0, w);
            float left = x0 + i * step - s, right = left + card;
            check(left >= x0 - 0.01f, "left edge stays at or after x0 (card " + i + ")");
            check(s >= 0, "scroll never negative");
            if (i < 19) check(right <= w - x0 - 0.4f * step + 0.01f, "the next card peeks (card " + i + ")");
        }
        // Clamped at the end: the last card ends at the right margin, no further.
        float last = RowMath.scrollTarget(19, 20, step, card, x0, w);
        near(last, 19 * step + card + 2 * x0 - w, "last card at the right margin");
        near(RowMath.scrollTarget(25, 20, step, card, x0, w), last, "focus past the end clamps");
        // A short row never scrolls.
        for (int i = 0; i < 4; i++) near(RowMath.scrollTarget(i, 4, step, card, x0, w), 0, "short row");
        // Monotonic.
        float prev = -1;
        for (int i = 0; i < 20; i++) {
            float s = RowMath.scrollTarget(i, 20, 432, 400, x0, w);
            check(s >= prev, "scroll grows with the focus");
            prev = s;
        }

        float[] centers = {100, 300, 500, 700};
        check(RowMath.nearest(centers, 4, 0) == 0, "nearest: before the first");
        check(RowMath.nearest(centers, 4, 310) == 1, "nearest: second");
        check(RowMath.nearest(centers, 4, 400) == 1, "nearest: tie takes the first");
        check(RowMath.nearest(centers, 4, 9000) == 3, "nearest: past the last");
        check(RowMath.nearest(centers, 2, 9000) == 1, "nearest: only count entries");
        check(RowMath.nearest(centers, 0, 50) == 0, "nearest: empty");

        check(RowMath.needMore(14, 20, 1, 5, 10), "needMore near the end");
        check(!RowMath.needMore(13, 20, 1, 5, 10), "needMore not yet");
        check(!RowMath.needMore(19, 20, 5, 5, 10), "needMore: no more pages");
        check(!RowMath.needMore(19, 20, 3, 50, 3), "needMore: maxPages reached");
        check(!RowMath.needMore(0, 0, 0, 5, 10), "needMore: empty row");

        check(RowMath.wrap(0, 8) == 0 && RowMath.wrap(8, 8) == 0 && RowMath.wrap(-1, 8) == 7
                && RowMath.wrap(-9, 8) == 7 && RowMath.wrap(3, 0) == 0, "wrap");

        float[] tops = {866, 1316, 1676};
        near(RowMath.pageScrollTarget(-1, tops, 526, 116), 0, "hero scroll");
        near(RowMath.pageScrollTarget(0, tops, 526, 116), 340, "row 0 band");
        near(RowMath.pageScrollTarget(1, tops, 526, 116), 1200, "row 1 band");
        near(RowMath.pageScrollTarget(7, tops, 526, 116), 1560, "past the last row clamps");
        near(RowMath.pageScrollTarget(0, new float[] {100}, 526, 116), 0, "never negative");
        near(RowMath.pageScrollTarget(0, new float[0], 526, 116), 0, "no rows");

        check(!RowMath.acceptRepeat(1000, 1069), "repeat too soon");
        check(RowMath.acceptRepeat(1000, 1070), "repeat at 70 ms");
        System.out.println("RowMathTest: " + assertions + " checks passed");
    }
}
