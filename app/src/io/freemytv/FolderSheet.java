package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * An open folder (SPEC2 §2.3): full screen, BG at 90 % over Início, the folder's name, its apps in the grid's
 * 6-column geometry from y 300 (rows of the same GridRowView), scrolling past 3 rows by the page-scroll rule. Opens
 * with alpha and a 0.96 → 1 scale (a hardware layer only while that runs). The d-pad moves inside it (key);
 * StartView handles OK, holds, Back and edit mode, through the accessors below.
 */
final class FolderSheet extends ViewGroup {
    static final float TOP = 300, TITLE_BASE = 210;

    private final StartHost host;
    private final AppTiles.TilePainter painter;
    private final float k;
    final AppTiles.Grid grid;
    private final AppTiles.GridPage page;
    private final Paint bg = new Paint(), title;
    private final TextPaint titleText;
    private final Spring shown = new Spring(0.38f, 0.90f, 0), scroll = new Spring(0.42f, 1f, 0);
    private StartModel.Tile folder;
    private CharSequence name = "";
    private float nameW;
    private boolean open;

    FolderSheet(Context c, StartHost h, AppTiles.TilePainter p) {
        super(c);
        host = h;
        painter = p;
        k = ExploreView.scaleOf(c);
        grid = new AppTiles.Grid(TOP, k);
        page = new AppTiles.GridPage(c, grid, p, h, k);
        addView(page, new ExplorePage.Pos(0, 0, 1920, 1080));
        title = Ui.text(Ui.displaySemibold, 57 * k, CardPainter.INK);
        titleText = new TextPaint(title);
        setClipChildren(false);
        setWillNotDraw(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(INVISIBLE);
    }

    @Override public boolean hasOverlappingRendering() {
        return getLayerType() != LAYER_TYPE_NONE;
    }

    void open(StartModel.Tile folder, RectF from) {
        if (folder == null) return;
        this.folder = folder;
        open = true;
        fill(folder, false);
        grid.focus = 0;
        grid.active = true;
        grid.lifted = -1;
        grid.editing = false;
        grid.retarget();
        grid.snapAll();
        scroll.snap(0);
        grid.arrive(0, 0, 0);
        if (from != null && getWidth() > 0) {
            setPivotX(Math.max(0, Math.min(getWidth(), from.centerX())));
            setPivotY(Math.max(0, Math.min(getHeight(), from.centerY())));
        } else {
            setPivotX(960 * k);
            setPivotY(540 * k);
        }
        shown.snap(0);
        shown.target = 1;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setVisibility(VISIBLE);
        apply();
        host.a11yChanged();
        host.wake();
    }

    /** A new model: the same folder's apps again (focus kept by key); false when the folder is gone. */
    boolean refresh(StartModel.Tile folder) {
        if (!open) return true;
        if (folder == null || folder.folderApps == null || folder.folderApps.length == 0) return false;
        String was = grid.key(grid.focus);
        this.folder = folder;
        fill(folder, true);
        int i = grid.indexOf(was);
        grid.focus = i >= 0 ? i : Math.max(0, Math.min(grid.size() - 1, grid.focus));
        grid.retarget();
        retargetScroll();
        return true;
    }

    private void fill(StartModel.Tile folder, boolean animate) {
        List<StartModel.Tile> apps = new ArrayList<>();
        if (folder.folderApps != null) for (StartModel.Tile t : folder.folderApps) if (t != null) apps.add(t);
        grid.setTiles(apps, painter, animate);
        page.sync();
        String n = folder.label == null ? "" : folder.label;
        name = TextUtils.ellipsize(n, titleText, 1600 * k, TextUtils.TruncateAt.END);
        nameW = title.measureText(name, 0, name.length());
        drawnScroll = Float.NaN;
        invalidate();
    }

    void close(boolean animate) {
        if (!open && getVisibility() != VISIBLE) return;
        open = false;
        grid.active = false;
        grid.retarget();
        shown.target = 0;
        if (!animate) {
            shown.snap(0);
            setVisibility(INVISIBLE);
            setLayerType(LAYER_TYPE_NONE, null);
        } else {
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }
        apply();
        host.a11yChanged();
        host.wake();
    }

    boolean isOpen() {
        return open;
    }

    StartModel.Tile folder() {
        return folder;
    }

    int focus() {
        return grid.focus;
    }

    StartModel.Tile focusedTile() {
        return grid.tile(grid.focus);
    }

    /** Focus on app i (arriving by dx, dy); the sheet scrolls to keep its row in sight. */
    void setFocus(int i, int dx, int dy) {
        if (grid.size() == 0) return;
        i = Math.max(0, Math.min(grid.size() - 1, i));
        boolean moved = i != grid.focus;
        grid.focus = i;
        grid.retarget();
        if (moved) grid.arrive(i, dx, dy);
        retargetScroll();
        host.wake();
    }

    /** The d-pad inside the folder (on key-down; StartView rate-limits repeats). True when the key was the d-pad. */
    boolean key(KeyEvent e) {
        if (!open) return false;
        int dx = 0, dy = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            default: return false;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int n = grid.size();
        if (n == 0) return true;
        int i = grid.focus, j = HomeLayout.nav(i, dx, dy, n, AppTiles.COLUMNS);
        if (j < 0 || j == i || j >= n) {
            grid.bump(i, dx, dy);
            host.wake();
            return true;
        }
        setFocus(j, dx, dy);
        host.a11yChanged();
        return true;
    }

    /** The scroll rule from y 300: 0 while the focused row is among the first 3, then one pitch per row. */
    void retargetScroll() {
        int r = AppTiles.row(Math.max(0, grid.lifted >= 0 ? grid.lifted : grid.focus));
        float s = r <= 2 ? 0 : AppTiles.PITCH * (r - 2);
        float max = Math.max(0, TOP + AppTiles.PITCH * grid.lastRow() + AppTiles.H + 46 - 1080);
        scroll.target = Math.max(0, Math.min(max, s));
    }

    /** The current scroll, in layout units. */
    float scroll() {
        return scroll.value;
    }

    /** Slot i's resting rectangle on screen (px), without the focus growth. */
    void slotRect(int i, RectF out) {
        float x = grid.slotX(i) * k, y = (grid.slotY(i) - scroll.value) * k;
        out.set(x, y, x + AppTiles.W * k, y + AppTiles.H * k);
    }

    boolean step(float dt) {
        boolean busy = shown.step(dt);
        busy |= scroll.step(dt);
        busy |= page.step(dt);
        apply();
        if (!busy && getLayerType() != LAYER_TYPE_NONE && (shown.value >= 0.999f || shown.value <= 0.001f)) {
            setLayerType(LAYER_TYPE_NONE, null);
        }
        if (!open && shown.value < 0.01f && !busy && getVisibility() == VISIBLE) setVisibility(INVISIBLE);
        return busy;
    }

    private void apply() {
        float v = Ui.clamp(shown.value), s = 0.96f + 0.04f * v;
        setAlpha(v);
        setScaleX(s);
        setScaleY(s);
        page.setTranslationY(-scroll.value * k);
        page.cull(scroll.value);
        if (scroll.value != drawnScroll) {   // the title scrolls with the apps
            drawnScroll = scroll.value;
            invalidate();
        }
    }

    private float drawnScroll = Float.NaN;

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        page.measure(MeasureSpec.makeMeasureSpec(Math.round(1920 * k), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(1080 * k), MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        page.layout(0, 0, Math.round(1920 * k), Math.round(1080 * k));
    }

    @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof ExplorePage.Pos;
    }

    @Override protected void dispatchDraw(Canvas c) {
        int save = c.save();
        try {
            bg.setColor(0xE6000000 | (CardPainter.BG & 0xFFFFFF));
            c.drawRect(0, 0, getWidth(), getHeight(), bg);
            float y = (TITLE_BASE - scroll.value) * k;
            if (name.length() > 0 && y > -60 * k) c.drawText(name, 0, name.length(), getWidth() / 2f - nameW / 2, y, title);
            super.dispatchDraw(c);
        } catch (RuntimeException e) {
            host.fault("folder draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    // MARK: - TalkBack and the mouse

    int a11yCount() {
        return open ? grid.size() : 0;
    }

    /** The app under (x, y) px, or -1. */
    int hit(float x, float y, RectF tmp) {
        if (!open) return -1;
        for (int i = 0; i < grid.size(); i++) {
            slotRect(i, tmp);
            if (tmp.contains(x, y)) return i;
        }
        return -1;
    }

    View page() {
        return page;
    }
}
