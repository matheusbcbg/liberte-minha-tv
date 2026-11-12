package io.freemytv;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * A vertical stack of Explore rows: the focus (row and card), each row's horizontal scroll, the page's vertical
 * scroll, the pool of 8 RowViews bound to the rows on screen, lazy loading and the picture wants. Shared by the root
 * pages, the detail page and the search results.
 */
final class BrowseStack {
    static final int CONSUMED = 0, EXIT_UP = 1, EXIT_DOWN = 2, EXIT_LEFT = 3, IGNORED = 4;
    private static final int POOL = 8;
    private static final long SWEEP_MS = 550;

    interface Host {
        void onActivate(Row r, Card c, RectF from);
        void onLongPress(Row r, Card c, RectF from);
        void onFocus(Row r, Card c);
        void onNeedRow(Row r, int priority);
        void onNeedMore(Row r);
        void wake();
    }

    /** What the stack keeps per row: focus springs per card, horizontal scroll, the bump, where it sits. */
    private static final class State {
        Spring[] focus = new Spring[0];
        final Spring scroll = new Spring(0.34f, 1f, 0);
        final Spring bump = new Spring(0.25f, 0.6f, 0);
        int index;
        long sweepAt = -1;
        float top, targetTop;
        boolean placed;
        RowView view;
        int version = -1;
    }

    private final ViewGroup parent;
    private final CardPainter painter;
    private final ExploreImages images;
    private final Host host;
    private final RowView[] pool = new RowView[POOL];
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final IdentityHashMap<Row, State> states = new IdentityHashMap<>();
    private final Spring page = new Spring(0.42f, 1f, 0);
    private final float k, screenW, screenH;
    private float x0, firstTop, band0, bandN;
    private float motionDy, motionAlpha = 1;
    private int focusRow = -1;
    private boolean focused, exitLeft, destroyed;
    private float[] tops = new float[0];
    private float[] centers = new float[64];
    private int a11yBase = ExplorePage.G_ROWS;
    private final RectF rect = new RectF();

    BrowseStack(ViewGroup parent, int index, Context c, CardPainter p, ExploreImages img, WatchArt art, Host host) {
        this.parent = parent;
        painter = p;
        images = img;
        this.host = host;
        k = p.k;
        screenW = 1920 * k;
        screenH = 1080 * k;
        x0 = 96 * k;
        for (int i = 0; i < POOL; i++) {
            RowView v = new RowView(c, p, img, art);
            v.setVisibility(ViewGroup.INVISIBLE);
            parent.addView(v, Math.min(index + i, parent.getChildCount()), new ExplorePage.Pos(0, 0, 1920, 620));
            pool[i] = v;
        }
    }

    /** In px: the cards' left margin, row 0's top at rest, where row 0's top goes when focused, and where the others go. */
    void setGeometry(float x0, float firstTop, float bandRow0, float bandOthers) {
        this.x0 = x0;
        this.firstTop = firstTop;
        band0 = bandRow0;
        bandN = bandOthers;
        layoutTops(true);
        retarget();
    }

    /** Left at column 0 leaves the stack (the search results go back to the keyboard). */
    void setExitLeft(boolean exit) {
        exitLeft = exit;
    }

    void setRows(List<Row> list) {
        Row was = focusedRow();
        int wasIndex = focusRow;
        rows.clear();
        rows.addAll(list);
        IdentityHashMap<Row, State> keep = new IdentityHashMap<>();
        for (Row r : rows) {
            State st = states.get(r);
            keep.put(r, st != null ? st : new State());
        }
        for (State st : states.values()) if (!keep.containsValue(st) && st.view != null) st.view.unbind();
        states.clear();
        states.putAll(keep);
        rebuildShown();
        if (focused) {
            int i = was == null ? -1 : shown.indexOf(was);
            if (i < 0) i = Math.max(0, Math.min(wasIndex, shown.size() - 1));
            focusRow = shown.isEmpty() ? -1 : i;
            if (focusRow < 0) focused = false;
            else focusCard(shown.get(focusRow), stateOf(shown.get(focusRow)).index, false);
        }
        for (Row r : rows) syncCards(r);
        retarget();
        bindViews();
        wants();
        host.wake();
    }

    /** A row's cards or state changed (UI thread). */
    void rowChanged(Row r) {
        State st = states.get(r);
        if (st == null) return;
        if (focused && focusedRow() == r && (r.count == 0 || st.index >= r.count)) {
            // Its focused card is going away: no card of it may stay raised for when it comes back.
            if (st.index < st.focus.length) {
                Spring f = st.focus[st.index];
                f.target = 0;
                f.value = 0;
                f.velocity = 0;
            }
            blurRow(r);
        }
        syncCards(r);
        Row was = focusedRow();
        rebuildShown();
        if (focused) {
            int i = was == null ? -1 : shown.indexOf(was);
            if (i < 0) {   // the focused row went away: the one that took its place
                i = Math.max(0, Math.min(focusRow, shown.size() - 1));
                if (shown.isEmpty()) {
                    focused = false;
                    focusRow = -1;
                } else {
                    focusRow = i;
                    Row nr = shown.get(i);
                    focusCard(nr, Math.min(stateOf(nr).index, Math.max(0, nr.count - 1)), false);
                }
            } else {
                focusRow = i;
                if (r == was && r.count > 0 && st.index >= r.count) focusCard(r, r.count - 1, false);
                else if (r == was && r.count > 0) {
                    st.focus[st.index].target = 1;
                    if (st.view != null) st.view.focusIndex = st.index;
                }
            }
        }
        if (st.view != null) {
            st.view.focusIndex = focused && focusedRow() == r ? st.index : -1;
            st.view.invalidate();
        }
        retarget();
        bindViews();
        wants();
        host.wake();
    }

    private void syncCards(Row r) {
        State st = stateOf(r);
        if (st.version == r.version) return;
        st.version = r.version;
        if (st.focus.length < r.count) {
            Spring[] grown = new Spring[Math.max(r.count, st.focus.length * 2)];
            System.arraycopy(st.focus, 0, grown, 0, st.focus.length);
            for (int i = st.focus.length; i < grown.length; i++) grown[i] = new Spring(0.30f, 0.80f, 0);
            st.focus = grown;
            if (st.view != null) st.view.bind(r, grown, st.scroll, x0);   // a bound view keeps the array it was given
        }
        if (r.count == 0) {   // emptied (a new search, another season): its next cards start from the first
            st.scroll.target = 0;
            st.scroll.value = 0;
            st.scroll.velocity = 0;
        }
        st.index = Math.max(0, Math.min(st.index, r.count - 1));
        int[] vr = visibleCards(r, st.scroll.target);
        painter.prepare(r, vr[0], Math.min(r.count, vr[1] + 8));
    }

    private State stateOf(Row r) {
        State st = states.get(r);
        if (st == null) {
            st = new State();
            states.put(r, st);
        }
        return st;
    }

    private void rebuildShown() {
        shown.clear();
        for (Row r : rows) if (r.visible()) shown.add(r);
        layoutTops(false);
    }

    private void layoutTops(boolean snap) {
        if (tops.length < shown.size()) tops = new float[Math.max(8, shown.size() * 2)];
        float y = firstTop;
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            State st = stateOf(r);
            tops[i] = y;
            st.targetTop = y;
            if (snap || !st.placed) {
                st.top = y;
                st.placed = true;
            }
            y += rowHeight(r);
        }
    }

    private float rowHeight(Row r) {
        return r.style == Catalog.INFO ? painter.infoHeight(r) : painter.pitch(r.style);
    }

    private float[] topsArray() {
        float[] out = new float[shown.size()];
        System.arraycopy(tops, 0, out, 0, out.length);
        return out;
    }

    private void retarget() {
        if (!focused || focusRow < 0) page.target = 0;
        else page.target = RowMath.pageScrollTarget(focusRow, topsArray(), band0, bandN);
    }

    /** Scrolls the page back to its top (the hero) without changing which card each row remembers. */
    void scrollHome() {
        page.target = 0;
        host.wake();
    }

    // MARK: - Keys and focus

    int onKey(int dx, int dy) {
        if (!focused || focusRow < 0 || focusRow >= shown.size()) return IGNORED;
        Row r = shown.get(focusRow);
        State st = stateOf(r);
        if (dx != 0) {
            if (r.state != Row.READY || r.count == 0 || r.style == Catalog.INFO) {
                return dx < 0 && exitLeft ? EXIT_LEFT : CONSUMED;
            }
            int ni = st.index + dx;
            if (ni < 0 && exitLeft) return EXIT_LEFT;
            if (ni < 0 || ni >= r.count) {
                st.bump.velocity += (dx > 0 ? 1 : -1) * 10 * k * 42;   // a nudge out and back, about 10 px
                st.bump.target = 0;
                host.wake();
                return CONSUMED;
            }
            focusCard(r, ni, true);
            if (RowMath.needMore(ni, r.count, r.page, r.totalPages, r.spec != null ? r.spec.maxPages : r.totalPages)) host.onNeedMore(r);
            wants();
            host.wake();
            return CONSUMED;
        }
        if (dy == 0) return IGNORED;
        int target = focusRow + dy;
        if (target < 0) return EXIT_UP;
        if (target >= shown.size()) return EXIT_DOWN;
        float x = cardCenterOnScreen(r, st.index);
        blurRow(r);
        focusRow = target;
        Row nr = shown.get(target);
        focusCard(nr, nearestIndex(nr, x), true);
        retarget();
        wants();
        host.wake();
        return CONSUMED;
    }

    /** OK on the focused card (longPress: held ≥ 500 ms or Play). */
    boolean activate(boolean longPress) {
        Card c = focusedCard();
        Row r = focusedRow();
        if (c == null || r == null) return false;
        focusedBounds(rect);
        if (longPress) host.onLongPress(r, c, rect);
        else host.onActivate(r, c, rect);
        return true;
    }

    void enter(boolean fromAbove, float screenX) {
        if (shown.isEmpty()) return;
        focused = true;
        focusRow = fromAbove ? 0 : shown.size() - 1;
        Row r = shown.get(focusRow);
        focusCard(r, nearestIndex(r, screenX), true);
        retarget();
        wants();
        host.wake();
    }

    /** Focus a given row (by its key) and card, for restoring a page. */
    boolean enterAt(String rowKey, int index) {
        for (int i = 0; i < shown.size(); i++) {
            if (!shown.get(i).key.equals(rowKey)) continue;
            focused = true;
            focusRow = i;
            Row r = shown.get(i);
            focusCard(r, Math.max(0, Math.min(index, r.count - 1)), false);
            retarget();
            wants();
            host.wake();
            return true;
        }
        return false;
    }

    void leave() {
        if (focused && focusRow >= 0 && focusRow < shown.size()) blurRow(shown.get(focusRow));
        focused = false;
        retarget();
        host.wake();
    }

    private void blurRow(Row r) {
        State st = stateOf(r);
        if (st.index < st.focus.length) st.focus[st.index].target = 0;
        st.sweepAt = -1;
        if (st.view != null) {
            st.view.focusIndex = -1;
            st.view.sweep = -1;
            st.view.invalidate();
        }
    }

    private void focusCard(Row r, int index, boolean notify) {
        State st = stateOf(r);
        syncCards(r);
        if (r.count == 0) {
            st.index = 0;
            return;
        }
        index = Math.max(0, Math.min(index, r.count - 1));
        if (st.index < st.focus.length && st.index != index) st.focus[st.index].target = 0;
        st.index = index;
        st.focus[index].target = 1;
        st.sweepAt = SystemClock.uptimeMillis();
        st.scroll.target = horizontalTarget(r, index);
        if (st.view != null) {
            st.view.focusIndex = index;
            st.view.invalidate();
        }
        int[] vr = visibleCards(r, st.scroll.target);
        painter.prepare(r, vr[0], Math.min(r.count, vr[1] + 8));
        if (notify) host.onFocus(r, r.cards[index]);
    }

    private float horizontalTarget(Row r, int index) {
        if (r.style == Catalog.INFO) return 0;
        if (r.style != Catalog.CHIP) {
            // The right margin is always 96 (the search results start further right, at their own x0).
            return RowMath.scrollTarget(index, r.count, painter.step(r.style), painter.width(r.style), x0, mathWidth());
        }
        // Chips have their own widths: the same rule over their real edges.
        float gap = painter.step(Catalog.CHIP), left = 0, total = 0;
        for (int i = 0; i < r.count; i++) {
            if (i == index) left = total;
            total += r.cards[i].chipW + (i < r.count - 1 ? gap : 0);
        }
        float right = left + r.cards[index].chipW, limit = mathWidth() - x0 - 0.4f * 200 * k;
        float want = x0 + right - limit, max = Math.max(0, total + 2 * x0 - mathWidth());
        return index == 0 ? 0 : Math.max(0, Math.min(want, max));
    }

    /** The screen width RowMath's symmetric margins see: x0 on the left, 96 on the right. */
    private float mathWidth() {
        return screenW - 96 * k + x0;
    }

    private float leftOf(Row r, int i) {
        if (r.style != Catalog.CHIP) return i * painter.step(r.style);
        float x = 0, gap = painter.step(Catalog.CHIP);
        for (int j = 0; j < i && j < r.count; j++) x += r.cards[j].chipW + gap;
        return x;
    }

    private float cardCenterOnScreen(Row r, int i) {
        if (r.count == 0) return x0;
        State st = stateOf(r);
        return x0 + leftOf(r, i) - st.scroll.target + painter.imageLeft(r.style) + cardWidth(r, i) / 2;
    }

    private float cardWidth(Row r, int i) {
        if (r.style == Catalog.CHIP) return r.cards[i].chipW;
        return r.style == Catalog.TOP10 ? 200 * k : painter.width(r.style);
    }

    private int nearestIndex(Row r, float screenX) {
        if (r.count == 0) return 0;
        if (centers.length < r.count) centers = new float[r.count * 2];
        State st = stateOf(r);
        for (int i = 0; i < r.count; i++) {
            centers[i] = x0 + leftOf(r, i) - st.scroll.target + painter.imageLeft(r.style) + cardWidth(r, i) / 2;
        }
        return RowMath.nearest(centers, r.count, screenX);
    }

    boolean hasFocus() {
        return focused && focusRow >= 0 && focusRow < shown.size();
    }

    Row focusedRow() {
        return hasFocus() ? shown.get(focusRow) : null;
    }

    Card focusedCard() {
        Row r = focusedRow();
        if (r == null || r.count == 0) return null;
        int i = stateOf(r).index;
        return i >= 0 && i < r.count ? r.cards[i] : null;
    }

    int focusedIndex() {
        Row r = focusedRow();
        return r == null ? -1 : stateOf(r).index;
    }

    /** The focused card's rectangle on screen (at its resting size and place). */
    boolean focusedBounds(RectF out) {
        Row r = focusedRow();
        if (r == null || r.count == 0) return false;
        return cardBounds(r, stateOf(r).index, out);
    }

    private boolean cardBounds(Row r, int i, RectF out) {
        State st = stateOf(r);
        if (i < 0 || i >= r.count) return false;
        float y = st.top - page.value + 52 * k;
        float x = x0 + leftOf(r, i) - st.scroll.value + painter.imageLeft(r.style);
        out.set(x, y, x + cardWidth(r, i), y + painter.height(r.style));
        return true;
    }

    float scroll() {
        return page.value;
    }

    /** The row list as shown (visible rows, in order). */
    List<Row> shownRows() {
        return shown;
    }

    int indexOf(Row r) {
        return shown.indexOf(r);
    }

    /** Moves the focus to a row (Temporadas, Onde assistir), its remembered card. */
    void focusRow(Row r) {
        int i = shown.indexOf(r);
        if (i < 0) return;
        if (hasFocus()) blurRow(shown.get(focusRow));
        focused = true;
        focusRow = i;
        focusCard(r, stateOf(r).index, true);
        retarget();
        wants();
        host.wake();
    }

    // MARK: - Frames

    boolean step(float dt) {
        if (destroyed) return false;
        boolean busy = page.step(dt);
        long now = SystemClock.uptimeMillis();
        float pv = page.value;
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            State st = stateOf(r);
            if (st.top != st.targetTop) {
                float d = st.targetTop - st.top;
                st.top = Math.abs(d) < 0.5f ? st.targetTop : st.top + d * Math.min(1, dt * 18);   // ~200 ms collapse
                busy = true;
            }
            RowView v = st.view;
            boolean moved = st.scroll.step(dt);
            moved |= st.bump.step(dt);
            for (int j = 0; j < Math.min(r.count, st.focus.length); j++) {
                Spring s = st.focus[j];
                if (s.value != s.target || s.velocity != 0) moved |= s.step(dt);
            }
            float sweep = -1;
            if (st.sweepAt >= 0) {
                long age = now - st.sweepAt;
                if (age < SWEEP_MS) {
                    sweep = age / (float) SWEEP_MS;
                    moved = true;
                } else {
                    st.sweepAt = -1;
                    if (v != null && v.sweep >= 0) moved = true;
                }
            }
            if (v != null) {
                v.sweep = sweep;
                v.bump = st.bump.value;
                v.setTranslationY(st.top - pv + motionDy);
                if (moved) v.invalidate();
            }
            busy |= moved;
        }
        if (bindViews()) wants();
        return busy;
    }

    /** Binds the pool's views to the rows on screen; true if a binding changed. */
    private boolean bindViews() {
        float pv = page.value, lo = -500 * k, hi = screenH + 200 * k;
        boolean changed = false;
        // Unbind the rows that left.
        for (int i = 0; i < POOL; i++) {
            RowView v = pool[i];
            if (v.row == null) continue;
            State st = states.get(v.row);
            int at = shown.indexOf(v.row);
            boolean keep = st != null && at >= 0 && st.top - pv < hi && st.top - pv + rowHeight(v.row) > lo;
            if (!keep) {
                if (st != null) st.view = null;
                v.unbind();
                changed = true;
            }
        }
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            State st = stateOf(r);
            float y = st.top - pv;
            if (st.view != null || y >= hi || y + rowHeight(r) <= lo) continue;
            RowView free = null;
            for (RowView v : pool) if (v.row == null) { free = v; break; }
            if (free == null) break;
            st.view = free;
            free.bind(r, st.focus, st.scroll, x0);
            free.focusIndex = focused && focusRow == i ? st.index : -1;
            free.setTranslationY(y + motionDy);
            free.setAlpha(motionAlpha);
            changed = true;
        }
        if (changed) lazyLoad();
        return changed;
    }

    /** Rows within 2 rows of the screen are asked for (EMPTY means never asked). */
    private void lazyLoad() {
        float pv = page.target;
        int first = -1, last = -1;
        for (int i = 0; i < shown.size(); i++) {
            float y = stateOf(shown.get(i)).targetTop - pv;
            if (y < screenH && y + rowHeight(shown.get(i)) > 0) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) {
            first = 0;
            last = Math.min(2, shown.size() - 1);
        }
        // Collected first: a host may answer from its cache at once, and that changes the list being walked.
        need.clear();
        needPriority.clear();
        for (int i = Math.max(0, first - 2); i <= Math.min(shown.size() - 1, last + 2); i++) {
            Row r = shown.get(i);
            if (r.state == Row.EMPTY) {
                need.add(r);
                needPriority.add(i >= first && i <= last ? Tmdb.NOW : Tmdb.SOON);
            }
        }
        if (need.isEmpty() || asking) return;
        asking = true;
        try {
            ArrayList<Row> rows = new ArrayList<>(need);
            ArrayList<Integer> priorities = new ArrayList<>(needPriority);
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).state == Row.EMPTY) host.onNeedRow(rows.get(i), priorities.get(i));
        } finally {
            asking = false;
        }
    }

    private final ArrayList<Row> need = new ArrayList<>();
    private final ArrayList<Integer> needPriority = new ArrayList<>();
    private boolean asking;

    /** Asks for the pictures: the cards on screen and 2 more now, 6 more soon, the first 8 of the rows next to the screen. */
    private void wants() {
        if (destroyed) return;
        lazyLoad();
        float pv = page.target;
        for (int i = 0; i < shown.size(); i++) {
            Row r = shown.get(i);
            if (r.state != Row.READY || r.count == 0) continue;
            State st = stateOf(r);
            float y = st.targetTop - pv, h = rowHeight(r);
            boolean onScreen = y < screenH && y + h > 0;
            boolean near = !onScreen && y < screenH + h * 1.2f && y + h > -h * 1.2f;
            if (!onScreen && !near) continue;
            int[] vr = visibleCards(r, st.scroll.target);
            if (onScreen) {
                painter.prepare(r, vr[0], Math.min(r.count, vr[1] + 8));
                for (int j = vr[0]; j < Math.min(r.count, vr[1] + 2); j++) want(r.cards[j], ExploreImages.NOW);
                for (int j = vr[1] + 2; j < Math.min(r.count, vr[1] + 8); j++) want(r.cards[j], ExploreImages.SOON);
            } else {
                for (int j = vr[0]; j < Math.min(r.count, vr[0] + 8); j++) want(r.cards[j], ExploreImages.SOON);
            }
        }
    }

    private void want(Card c, int priority) {
        if (c != null && c.image != null) images.want(c.imageSpec, c.image, priority);
    }

    private final int[] vr = new int[2];

    private int[] visibleCards(Row r, float scroll) {
        vr[0] = vr[1] = 0;
        if (r.count == 0) return vr;
        if (r.style == Catalog.CHIP || r.style == Catalog.INFO) {
            vr[1] = r.count;
            return vr;
        }
        float step = painter.step(r.style);
        vr[0] = Math.max(0, (int) Math.floor((scroll - step) / step));
        vr[1] = Math.min(r.count, Math.max(vr[0], (int) Math.ceil((scroll + screenW - x0) / step) + 1));
        return vr;
    }

    /** Pictures came in: only the rows still waiting for one record again. */
    void imagesArrived() {
        for (RowView v : pool) if (v.row != null && v.missing > 0) v.invalidate();
    }

    /** The page's own slide (push/pop) or fade, applied to every row view. */
    void setMotion(float dy, float alpha) {
        motionDy = dy;
        motionAlpha = alpha;
        float pv = page.value;
        for (RowView v : pool) {
            if (v.row == null) continue;
            State st = states.get(v.row);
            if (st != null) v.setTranslationY(st.top - pv + dy);
            v.setAlpha(alpha);
        }
    }

    // MARK: - TalkBack (ids: (groupBase + row)·1000 + card + 1)

    void a11yItems(int groupBase, List<Integer> out) {
        a11yBase = groupBase;
        for (int i = 0; i < shown.size() && i < 40; i++) {
            Row r = shown.get(i);
            State st = stateOf(r);
            if (st.view == null || r.state != Row.READY) continue;
            int[] range = visibleCards(r, st.scroll.value);
            for (int j = range[0]; j < Math.min(range[1], 999); j++) out.add((groupBase + i) * 1000 + j + 1);
        }
    }

    String a11yText(int id) {
        Row r = rowOf(id);
        int j = id % 1000 - 1;
        if (r == null || j < 0 || j >= r.count) return "";
        Card c = r.cards[j];
        StringBuilder b = new StringBuilder();
        if (c.title != null) b.append(c.title);
        if (c.sub != null && !c.sub.isEmpty()) b.append(", ").append(c.sub);
        if (j == 0 && r.title != null && !r.title.isEmpty()) b.insert(0, r.title + ": ");
        return b.toString();
    }

    boolean a11yBounds(int id, Rect out) {
        Row r = rowOf(id);
        int j = id % 1000 - 1;
        if (r == null || !cardBounds(r, j, rect)) return false;
        if (rect.right < 0 || rect.left > screenW || rect.bottom < 0 || rect.top > screenH) return false;
        rect.round(out);
        return true;
    }

    int a11yFocused(int groupBase) {
        if (!hasFocus() || focusRow >= 40) return -1;
        return (groupBase + focusRow) * 1000 + stateOf(shown.get(focusRow)).index + 1;
    }

    void a11yFocus(int id) {
        Row r = rowOf(id);
        int j = id % 1000 - 1;
        if (r == null || j < 0 || j >= r.count) return;
        if (hasFocus()) blurRow(shown.get(focusRow));
        focused = true;
        focusRow = shown.indexOf(r);
        focusCard(r, j, true);
        retarget();
        wants();
        host.wake();
    }

    /** TalkBack's click on a card. */
    void a11yClick(int id) {
        a11yFocus(id);
        activate(false);
    }

    private Row rowOf(int id) {
        int i = id / 1000 - a11yBase;
        return i >= 0 && i < shown.size() ? shown.get(i) : null;
    }

    void destroy() {
        destroyed = true;
        for (Row r : rows) if (r.ticket != null) r.ticket.cancel();
        for (RowView v : pool) {
            v.unbind();
            parent.removeView(v);
        }
        states.clear();
        rows.clear();
        shown.clear();
    }
}
