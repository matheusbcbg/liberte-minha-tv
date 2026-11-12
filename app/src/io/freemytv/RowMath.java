package io.freemytv;

/** Explore's row and page arithmetic. Pure Java (no android.*), unit-tested by tests/RowMathTest.java. */
final class RowMath {
    private RowMath() {}

    /** Key repeats closer together than this are dropped (a held arrow moves at most ~14 cards a second). */
    static final long REPEAT_MS = 70;

    /**
     * The row's horizontal scroll for the focused card: the smallest one that keeps the card's right edge at or before
     * screenW − x0 − 0.4·step (so the next card peeks), clamped so the first card sits at x0 and the last one never
     * leaves an empty gap. Card i's left edge on screen is x0 + i·step − scroll, which then always stays ≥ x0.
     */
    static float scrollTarget(int focus, int count, float step, float cardW, float x0, float screenW) {
        if (count <= 0 || focus <= 0) return 0;
        focus = Math.min(focus, count - 1);
        float limit = screenW - x0 - 0.4f * step;
        float want = x0 + focus * step + cardW - limit;
        float max = Math.max(0, (count - 1) * step + cardW + 2 * x0 - screenW);
        return Math.max(0, Math.min(want, max));
    }

    /** The index whose centre is nearest x (the first one on a tie); 0 for an empty list. */
    static int nearest(float[] centers, int count, float x) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, centers.length); i++) {
            float d = Math.abs(centers[i] - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** True when the focus is within the last 6 cards and another page may be asked for. */
    static boolean needMore(int focus, int count, int page, int totalPages, int maxPages) {
        return count > 0 && focus >= count - 6 && page < totalPages && page < maxPages;
    }

    static int wrap(int i, int n) {
        if (n <= 0) return 0;
        int r = i % n;
        return r < 0 ? r + n : r;
    }

    /**
     * The page's vertical scroll that brings row `row` to its band: row 0's top at bandRow0, any other row's at
     * bandOthers; a negative row (the hero) is scroll 0. Never negative.
     */
    static float pageScrollTarget(int row, float[] rowTops, float bandRow0, float bandOthers) {
        if (row < 0 || rowTops == null || rowTops.length == 0) return 0;
        row = Math.min(row, rowTops.length - 1);
        return Math.max(0, rowTops[row] - (row == 0 ? bandRow0 : bandOthers));
    }

    static boolean acceptRepeat(long lastMs, long nowMs) {
        return nowMs - lastMs >= REPEAT_MS;
    }
}
