package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Início's app grid (SPEC2 §2.1, §2.3, §2.4): the tile painter, each tile's motion state (kept by key, so it follows
 * the tile when the order changes), the grid page with one View per row, and the lifted tile of edit mode. Sizes are in
 * the 1920 × 1080 layout and drawn times k. Nothing here allocates while drawing.
 */
final class AppTiles {
    private AppTiles() {}

    static final int COLUMNS = HomeLayout.COLUMNS;
    /** Column x = X0 + COL·c; tile W × H, radius RADIUS; row pitch PITCH; the Dock's top DOCK_TOP. */
    static final float X0 = 80, COL = 300, W = 260, H = 146, RADIUS = 16, PITCH = 246, DOCK_TOP = 724;
    static final float GROW = 0.15f, LIFT_Y = 6, LABEL_DY = 52, LABEL_W = 300;
    /** How far a row View reaches above and below its tiles: a reflow slide (±1.5 pitch), the label and the shadow. */
    static final float EXT = 380;
    static final long SHEEN_MS = 550;

    static int row(int i) { return i / COLUMNS; }
    static int col(int i) { return i % COLUMNS; }

    // Tile kinds, picked once per model in TilePainter.prepare.
    static final int T_ART = 0, T_LOGO = 1, T_EXPLORE = 2, T_LIVE = 3, T_INPUT = 4, T_SETTINGS = 5, T_FOLDER = 6, T_PLAIN = 7;

    /** One tile's state on screen. Springs in px (slides, bumps) or 0..1. */
    static final class Cell {
        StartModel.Tile tile;
        String key;
        int type = T_PLAIN;
        final Spring focus = new Spring(0.30f, 0.80f, 0), press = new Spring(0.30f, 0.80f, 0),
                slideX = new Spring(0.28f, 0.86f, 0), slideY = new Spring(0.28f, 0.86f, 0),
                tilt = new Spring(0.35f, 0.55f, 0), bumpX = new Spring(0.25f, 0.60f, 0), bumpY = new Spring(0.25f, 0.60f, 0),
                dim = new Spring(0.20f, 1f, 1);
        boolean tiltVertical;
        long sheenAt = -1;
        // Prepared outside draw (TilePainter.prepare).
        CharSequence label = "", inner = "";
        float innerW, symbolSize, symbolW;
        String symbol;
        Bitmap logoRest, logoFocus;
        boolean whiteLogo;
        LinearGradient brand;
        // A folder's mini-grid: its first 6 apps.
        StartModel.Tile[] mini;
        int[] miniType;
        Bitmap[] miniLogo;
        LinearGradient[] miniBrand;
        float[] miniSymbol;

        boolean step(float dt, long now) {
            boolean busy = focus.step(dt);
            busy |= press.step(dt);
            busy |= slideX.step(dt);
            busy |= slideY.step(dt);
            busy |= tilt.step(dt);
            busy |= bumpX.step(dt);
            busy |= bumpY.step(dt);
            busy |= dim.step(dt);
            if (sheenAt >= 0) {
                if (now - sheenAt < SHEEN_MS + 20) busy = true;
                else sheenAt = -1;
            }
            return busy;
        }

        void snap() {
            focus.snap(focus.target);
            press.snap(0);
            slideX.snap(0);
            slideY.snap(0);
            tilt.snap(0);
            bumpX.snap(0);
            bumpY.snap(0);
            dim.snap(dim.target);
            sheenAt = -1;
        }
    }

    /** The tiles of one grid (the main one, or an open folder's) in visible order, with their motion. Main thread. */
    static final class Grid {
        final float top;
        final ArrayList<Cell> cells = new ArrayList<>();
        private final HashMap<String, Cell> byKey = new HashMap<>();
        private final ArrayList<Cell> scratch = new ArrayList<>();
        int focus = -1, lifted = -1;
        /** The grid has the remote's focus (else no tile is focused). */
        boolean active;
        boolean editing;
        /** The 10 s idle dim (0..1, 400 ms). */
        final Spring idle = new Spring(0.40f, 1f, 0);
        private boolean[] dirty = new boolean[8];
        private boolean allDirty = true;
        final float k;

        Grid(float top, float k) {
            this.top = top;
            this.k = k;
        }

        int size() { return cells.size(); }
        int rows() { return (cells.size() + COLUMNS - 1) / COLUMNS; }
        int lastRow() { return Math.max(0, rows() - 1); }
        StartModel.Tile tile(int i) { return i >= 0 && i < cells.size() ? cells.get(i).tile : null; }
        String key(int i) { return i >= 0 && i < cells.size() ? cells.get(i).key : null; }

        int indexOf(String key) {
            if (key == null) return -1;
            for (int i = 0; i < cells.size(); i++) if (key.equals(cells.get(i).key)) return i;
            return -1;
        }

        List<String> keys() {
            List<String> out = new ArrayList<>(cells.size());
            for (Cell c : cells) out.add(c.key);
            return out;
        }

        List<StartModel.Tile> tiles() {
            List<StartModel.Tile> out = new ArrayList<>(cells.size());
            for (Cell c : cells) out.add(c.tile);
            return out;
        }

        /** Slot of tile i, in layout units (its resting top-left). */
        float slotX(int i) { return X0 + COL * col(i); }
        float slotY(int i) { return top + PITCH * row(i); }

        /**
         * The new list; cells are kept by key, so a tile that moved slides from its old slot when animate (the reflow
         * after a hide, a model delivery while focus rests). Focus and lifted are the caller's to fix.
         */
        void setTiles(List<StartModel.Tile> tiles, TilePainter p, boolean animate) {
            HashMap<String, Integer> old = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) old.put(cells.get(i).key, i);
            scratch.clear();
            HashMap<String, Cell> next = new HashMap<>();
            for (StartModel.Tile t : tiles) {
                if (t == null || t.key == null || next.containsKey(t.key)) continue;
                Cell c = byKey.get(t.key);
                if (c == null) {
                    c = new Cell();
                    c.key = t.key;
                }
                if (c.tile != t) {
                    c.tile = t;
                    p.prepare(c);
                }
                next.put(t.key, c);
                scratch.add(c);
            }
            cells.clear();
            cells.addAll(scratch);
            scratch.clear();
            byKey.clear();
            byKey.putAll(next);
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                Integer was = old.get(c.key);
                if (animate && was != null && was != i) slideFrom(c, was, i);
                else if (!animate) {
                    c.slideX.snap(0);
                    c.slideY.snap(0);
                }
            }
            allDirty = true;
        }

        /** Moves tile from → to (final index); every tile between them glides from its old slot. */
        void move(int from, int to) {
            int n = cells.size();
            if (from < 0 || from >= n || to < 0 || to >= n || from == to) return;
            Cell moved = cells.remove(from);
            cells.add(to, moved);
            int lo = Math.min(from, to), hi = Math.max(from, to);
            for (int i = lo; i <= hi; i++) {
                Cell c = cells.get(i);
                if (c == moved) {
                    if (lifted != to) slideFrom(c, from, to);
                    continue;
                }
                slideFrom(c, from < to ? i + 1 : i - 1, i);
            }
            allDirty = true;
        }

        void slideFrom(Cell c, int was, int now) {
            float max = 1.5f * PITCH * k;
            c.slideX.value = clamp(c.slideX.value + (slotX(was) - slotX(now)) * k, max);
            c.slideY.value = clamp(c.slideY.value + (slotY(was) - slotY(now)) * k, max);
            c.slideX.target = 0;
            c.slideY.target = 0;
        }

        private static float clamp(float v, float max) {
            return Math.max(-max, Math.min(max, v));
        }

        /** Focus, edit and lift targets of every tile, from focus/lifted/active/editing. */
        void retarget() {
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                boolean on = (active && i == focus) || i == lifted;
                c.focus.target = on ? 1 : 0;
                if (!on || i == lifted) c.press.target = 0;
                c.dim.target = editing && i != lifted ? 0.75f : 1;
            }
            allDirty = true;
        }

        /** Focus just arrived on tile i moving by (dx, dy): the tilt nudge and one sheen sweep. */
        void arrive(int i, int dx, int dy) {
            if (i < 0 || i >= cells.size()) return;
            Cell c = cells.get(i);
            if (dx != 0 || dy != 0) {
                c.tiltVertical = dy != 0;
                c.tilt.value = dy != 0 ? dy : -dx;
                c.tilt.target = 0;
            }
            c.sheenAt = SystemClock.uptimeMillis();
            markRow(row(i));
        }

        /** 10 px toward the blocked side and back. */
        void bump(int i, int dx, int dy) {
            if (i < 0 || i >= cells.size()) return;
            Cell c = cells.get(i);
            float v = 420 * k;
            if (dx != 0) c.bumpX.velocity = dx * v;
            if (dy != 0) c.bumpY.velocity = dy * v;
            markRow(row(i));
        }

        void press(int i, boolean down) {
            if (i < 0 || i >= cells.size()) return;
            cells.get(i).press.target = down ? 1 : 0;
            markRow(row(i));
        }

        void markRow(int r) {
            if (r < 0) return;
            if (r >= dirty.length) {
                allDirty = true;
                return;
            }
            dirty[r] = true;
        }

        void markAll() {
            allDirty = true;
        }

        boolean dirty(int r) {
            return allDirty || (r >= 0 && r < dirty.length && dirty[r]);
        }

        void clearDirty() {
            allDirty = false;
            int rows = rows();
            if (dirty.length < rows) dirty = new boolean[rows + 8];
            else for (int i = 0; i < dirty.length; i++) dirty[i] = false;
        }

        void snapAll() {
            for (Cell c : cells) c.snap();
            idle.snap(idle.target);
            allDirty = true;
        }

        boolean step(float dt) {
            long now = SystemClock.uptimeMillis();
            boolean busy = false;
            if (idle.step(dt)) {
                busy = true;
                allDirty = true;
            }
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i).step(dt, now)) {
                    busy = true;
                    markRow(row(i));
                }
            }
            return busy;
        }
    }

    /** The tvOS "regular" material without blur (§2.6): fill, top and bottom hairlines, a cached shadow. */
    static final class Material {
        private final float k;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), shadow = new Paint(Paint.ANTI_ALIAS_FLAG),
                top = new Paint(Paint.ANTI_ALIAS_FLAG), bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Shadows shadows = new Shadows();
        private final LinearGradient topShade, bottomShade;
        private final Matrix m = new Matrix();
        private final RectF tmp = new RectF();

        Material(float k) {
            this.k = k;
            shadow.setMaskFilter(new BlurMaskFilter(Math.max(1, 40 * k), BlurMaskFilter.Blur.NORMAL));
            topShade = new LinearGradient(0, 0, 0, 1, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            bottomShade = new LinearGradient(0, 0, 0, 1, 0x00000000, 0x26000000, Shader.TileMode.CLAMP);
            top.setStyle(Paint.Style.STROKE);
            top.setStrokeWidth(1.5f * k);
            top.setShader(topShade);
            bottom.setStyle(Paint.Style.STROKE);
            bottom.setStrokeWidth(1 * k);
            bottom.setShader(bottomShade);
        }

        void draw(Canvas c, RectF r, float radius, float alpha) {
            if (alpha <= 0.004f) return;
            tmp.set(r);
            tmp.offset(0, 20 * k);
            shadow.setColor(Ui.alpha(0x8C000000, alpha));
            shadows.draw(c, tmp, radius, 40 * k, shadow);
            fill.setColor(Ui.alpha(0xD91C1C1E, alpha));
            c.drawRoundRect(r, radius, radius, fill);
            int a = Math.round(255 * alpha);
            float inset = 0.75f * k;
            tmp.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
            m.setScale(1, 24 * k);
            m.postTranslate(0, r.top);
            topShade.setLocalMatrix(m);
            top.setAlpha(a);
            c.drawRoundRect(tmp, radius, radius, top);
            m.setScale(1, 24 * k);
            m.postTranslate(0, r.bottom - 24 * k);
            bottomShade.setLocalMatrix(m);
            bottom.setAlpha(a);
            c.drawRoundRect(tmp, radius, radius, bottom);
        }
    }

    /** Draws tiles (§2.1, §2.4). Paints and gradients are made once; per-tile texts and logo copies in prepare(). */
    static final class TilePainter {
        private static final int EXPLORE_A = 0xFF3A3A42, EXPLORE_B = 0xFF111114, LIVE_A = 0xFF1E3A5F, LIVE_B = 0xFF0D1B2A,
                INPUT_A = 0xFF2C2C2E, INPUT_B = 0xFF1C1C1E, SETTINGS_A = 0xFF0A84FF, SETTINGS_B = 0xFF5E5CE6,
                FOLDER = 0x33FFFFFF, PLAIN = 0xFF2C2C2E, CONNECTED = 0xFF30D158;
        /** HomeView's shelf looks for the brands it styles (TILE_STYLE): top, bottom, 1 = white logo. */
        private static final HashMap<String, int[]> STYLE = new HashMap<>();
        static {
            int[] netflix = {0xFF0A0A0A, 0xFF000000, 0}, youtube = {0xFF1C1C1E, 0xFF09090A, 0},
                    globo = {0xFFFF8A1F, 0xFFFF2D5A, 1}, prime = {0xFF2AA8FF, 0xFF0A68D4, 1},
                    disney = {0xFF173584, 0xFF08143F, 0}, max = {0xFF5C30E8, 0xFF221684, 0},
                    apple = {0xFF1C1C1E, 0xFF000000, 0};
            STYLE.put("com.netflix.ninja", netflix);
            STYLE.put("com.google.android.youtube.tv", youtube);
            STYLE.put("com.amazon.firetv.youtube", youtube);
            STYLE.put("com.globo.globotv", globo);
            STYLE.put("com.amazon.amazonvideo.livingroom", prime);
            STYLE.put("com.amazon.firebat", prime);
            STYLE.put("com.amazon.avod", prime);
            STYLE.put("com.disney.disneyplus", disney);
            STYLE.put("com.wbd.stream", max);
            STYLE.put("com.hbo.hbonow", max);
            STYLE.put("com.apple.atve.androidtv.appletv", apple);
            STYLE.put("com.apple.atve.sony.appletv", apple);
            STYLE.put("com.apple.atve.amazon.appletv", apple);
        }
        static final float MINI_W = 70, MINI_H = 39, MINI_GAP = 8, MINI_R = 6;

        final Context context;
        final float k;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
                bitmap = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                shadow = new Paint(Paint.ANTI_ALIAS_FLAG), deepShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint label, inner24, inner30;
        private final TextPaint labelText, inner24Text, inner30Text;
        private final float labelHalf, inner24Cap, inner30Half;
        private final Shadows shadows = new Shadows();
        private final Matrix m = new Matrix(), cam = new Matrix();
        private final Camera camera = new Camera();
        private final RectF r = new RectF(), t = new RectF();
        private final LinearGradient explore, live, input, settings, sheen, sweep, rim;
        private final HashMap<Integer, LinearGradient> brands = new HashMap<>();
        private final HashMap<Bitmap, Bitmap[]> logos = new HashMap<>();
        private final PorterDuffColorFilter white = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);

        TilePainter(Context c, float k) {
            context = c.getApplicationContext();
            this.k = k;
            label = Ui.text(Ui.medium, 26 * k, CardPainter.INK);
            label.setTextAlign(Paint.Align.CENTER);
            labelText = new TextPaint(label);
            inner24 = Ui.text(Ui.semibold, 24 * k, CardPainter.INK);
            inner24Text = new TextPaint(inner24);
            inner30 = Ui.text(Ui.semibold, 30 * k, CardPainter.INK);
            inner30Text = new TextPaint(inner30);
            labelHalf = Ui.halfCap(label);
            inner24Cap = Ui.cap(inner24);
            inner30Half = Ui.halfCap(inner30);
            shadow.setMaskFilter(new BlurMaskFilter(Math.max(1, 34 * k), BlurMaskFilter.Blur.NORMAL));
            deepShadow.setMaskFilter(new BlurMaskFilter(Math.max(1, 44 * k), BlurMaskFilter.Blur.NORMAL));
            stroke.setStyle(Paint.Style.STROKE);
            // Unit gradients, placed per tile with a local matrix (no allocation while drawing).
            explore = new LinearGradient(0, 0, 1, 1, EXPLORE_A, EXPLORE_B, Shader.TileMode.CLAMP);
            live = new LinearGradient(0, 0, 1, 1, LIVE_A, LIVE_B, Shader.TileMode.CLAMP);
            input = new LinearGradient(0, 0, 1, 1, INPUT_A, INPUT_B, Shader.TileMode.CLAMP);
            settings = new LinearGradient(0, 0, 1, 1, SETTINGS_A, SETTINGS_B, Shader.TileMode.CLAMP);
            sheen = new LinearGradient(0, 0, 0.45f, 0.45f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            sweep = new LinearGradient(0, 0, 1, 1, new int[] {0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}, new float[] {0, 0.5f, 1},
                    Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, 0, 1, 0x80FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP);
        }

        /** Main thread, once per tile and model: its kind, texts cut to size, symbol sizes, crisp logo copies. */
        void prepare(Cell c) {
            StartModel.Tile tile = c.tile;
            c.type = typeOf(tile);
            String name = tile.label == null ? "" : tile.label;
            c.label = TextUtils.ellipsize(name, labelText, LABEL_W * k, TextUtils.TruncateAt.END);
            c.symbol = null;
            c.inner = "";
            c.innerW = 0;
            c.logoRest = c.logoFocus = null;
            c.brand = null;
            c.mini = null;
            switch (c.type) {
                case T_EXPLORE:
                    c.symbol = "sparkles";
                    c.symbolSize = symbolSize("sparkles", 52 * k);
                    c.symbolW = Symbols.width(context, "sparkles", c.symbolSize);
                    c.inner = TextUtils.ellipsize(name, inner30Text, (W - 40) * k - c.symbolW - 14 * k, TextUtils.TruncateAt.END);
                    c.innerW = inner30.measureText(c.inner, 0, c.inner.length());
                    break;
                case T_LIVE:
                    c.symbol = tile.symbol != null ? tile.symbol : "live_tv";
                    c.symbolSize = symbolSize(c.symbol, 56 * k);
                    break;
                case T_INPUT:
                    c.symbol = tile.symbol != null ? tile.symbol : HomeApps.deviceSymbol(name);
                    c.symbolSize = symbolSize(c.symbol, 56 * k);
                    c.inner = TextUtils.ellipsize(name, inner24Text, (W - 32) * k, TextUtils.TruncateAt.END);
                    c.innerW = inner24.measureText(c.inner, 0, c.inner.length());
                    break;
                case T_SETTINGS:
                    c.symbol = "sparkles";
                    c.symbolSize = symbolSize("sparkles", 52 * k);
                    c.inner = TextUtils.ellipsize(Ui.APP_SHORT, inner24Text, (W - 32) * k, TextUtils.TruncateAt.END);
                    c.innerW = inner24.measureText(c.inner, 0, c.inner.length());
                    break;
                case T_PLAIN:
                    c.inner = TextUtils.ellipsize(name, inner24Text, (W - 32) * k, TextUtils.TruncateAt.END);
                    c.innerW = inner24.measureText(c.inner, 0, c.inner.length());
                    break;
                case T_LOGO:
                    prepareLogo(c);
                    break;
                case T_FOLDER:
                    prepareMini(c);
                    break;
                default:
                    break;
            }
            if (c.symbol != null) Symbols.warm(context, c.symbol, c.symbolSize);
        }

        /** After a new model: forget the logo copies of logos no tile uses any more (a reload may bring new bitmaps). */
        void keepLogos(List<StartModel.Tile> tiles) {
            if (logos.isEmpty()) return;
            java.util.HashSet<Bitmap> used = new java.util.HashSet<>();
            for (StartModel.Tile t : tiles) {
                if (t == null) continue;
                if (t.logo != null) used.add(t.logo);
                if (t.folderApps != null) for (StartModel.Tile a : t.folderApps) if (a != null && a.logo != null) used.add(a.logo);
            }
            logos.keySet().retainAll(used);
        }

        private float symbolSize(String name, float height) {
            float s = Symbols.pointSizeForHeight(context, name, height);
            return s > 0 ? s : height / 1.2f;
        }

        static int typeOf(StartModel.Tile t) {
            if (t == null) return T_PLAIN;
            String key = t.key == null ? "" : t.key;
            if (key.startsWith(HomeLayout.FOLDER)) return T_FOLDER;
            if (HomeLayout.EXPLORE.equals(key)) return T_EXPLORE;
            if (HomeLayout.LIVE.equals(key)) return T_LIVE;
            if (HomeLayout.SETTINGS.equals(key)) return T_SETTINGS;
            if (t.input || key.startsWith(HomeLayout.INPUT)) return T_INPUT;
            if (t.logo != null && (t.brand != 0 || STYLE.containsKey(t.pkg))) return T_LOGO;
            if (t.art != null && t.shader != null) return T_ART;
            return T_PLAIN;
        }

        private LinearGradient brandOf(StartModel.Tile tile) {
            int[] style = STYLE.get(tile.pkg);
            int top, bottom;
            if (style != null) {
                top = style[0];
                bottom = style[1];
            } else {
                bottom = tile.brand != 0 ? tile.brand | 0xFF000000 : tile.color | 0xFF000000;
                float[] hsv = new float[3];
                Color.colorToHSV(bottom, hsv);
                hsv[2] = Math.min(1, hsv[2] + 0.08f);
                top = Color.HSVToColor(hsv);
            }
            int id = top * 31 + bottom;
            LinearGradient g = brands.get(id);
            if (g == null) {
                g = new LinearGradient(0, 0, 0, 1, top, bottom, Shader.TileMode.CLAMP);
                brands.put(id, g);
            }
            return g;
        }

        private void prepareLogo(Cell c) {
            StartModel.Tile tile = c.tile;
            int[] style = STYLE.get(tile.pkg);
            c.whiteLogo = style != null && style[2] == 1;
            c.brand = brandOf(tile);
            Bitmap[] copies = logos.get(tile.logo);
            if (copies == null) {
                float ink = tile.item != null ? tile.item.logoInk : 0.3f;
                float scale = (float) (tile.logoScale > 0 ? tile.logoScale : 1);
                float bw = W * k * (1 + GROW), bh = H * k * (1 + GROW);
                copies = new Bitmap[] {crisp(tile.logo, bw, bh, ink, scale, 1 / (1 + GROW)), crisp(tile.logo, bw, bh, ink, scale, 1)};
                logos.put(tile.logo, copies);
            }
            c.logoRest = copies[0];
            c.logoFocus = copies[1];
        }

        /** HomeView.crispLogo's sizing: the same amount of ink for every logo, never past 76% × 58% of the tile. */
        private static Bitmap crisp(Bitmap src, float bw, float bh, float ink, float logoScale, float factor) {
            if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
            float lw = src.getWidth(), lh = src.getHeight();
            float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, ink))) * logoScale;
            scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh)) * factor;
            try {
                return Ui.downscale(src, Math.max(1, Math.round(lw * scale)), Math.max(1, Math.round(lh * scale)));
            } catch (RuntimeException e) {
                return src;
            }
        }

        private void prepareMini(Cell c) {
            StartModel.Tile[] apps = c.tile.folderApps;
            int n = apps == null ? 0 : Math.min(6, apps.length);
            c.mini = new StartModel.Tile[n];
            c.miniType = new int[n];
            c.miniLogo = new Bitmap[n];
            c.miniBrand = new LinearGradient[n];
            c.miniSymbol = new float[n];
            for (int i = 0; i < n; i++) {
                StartModel.Tile a = apps[i];
                c.mini[i] = a;
                int type = typeOf(a);
                if (type == T_FOLDER) type = T_PLAIN;
                c.miniType[i] = type;
                if (type == T_LOGO) {
                    c.miniBrand[i] = brandOf(a);
                    float ink = a.item != null ? a.item.logoInk : 0.3f;
                    c.miniLogo[i] = crisp(a.logo, MINI_W * k, MINI_H * k, ink, a.logoScale > 0 ? a.logoScale : 1, 1);
                } else if (type == T_INPUT || type == T_LIVE || type == T_EXPLORE || type == T_SETTINGS) {
                    String s = symbolFor(a, type);
                    c.miniSymbol[i] = symbolSize(s, 22 * k);
                    Symbols.warm(context, s, c.miniSymbol[i]);
                }
            }
        }

        private static String symbolFor(StartModel.Tile t, int type) {
            switch (type) {
                case T_LIVE: return t.symbol != null ? t.symbol : "live_tv";
                case T_INPUT: return t.symbol != null ? t.symbol : HomeApps.deviceSymbol(t.label == null ? "" : t.label);
                default: return "sparkles";
            }
        }

        /**
         * Draws cell c with its resting top-left at (x, y) px. alpha: the row's (edit dim, idle dim are applied here);
         * lift 0..1 (edit mode's lifted tile); offsets: apply the slide and bump springs (not for the LiftView).
         */
        void draw(Canvas c, Cell cell, float x, float y, float idle, boolean withLabel, float lift, boolean offsets) {
            float f = Math.max(0, cell.focus.value), fc = Ui.clamp(f);
            float w = W * k, h = H * k, rad = RADIUS * k;
            float s = 1 + GROW * f - 0.07f * cell.press.value + 0.03f * idle * fc + 0.08f * lift;
            float a = Ui.clamp(cell.dim.value) * (1 - 0.2f * idle * (1 - fc));
            if (a <= 0.004f) return;
            float dx = offsets ? cell.slideX.value + cell.bumpX.value : 0, dy = offsets ? cell.slideY.value + cell.bumpY.value : 0;
            float cx = x + w / 2 + dx, cy = y + h / 2 + dy - LIFT_Y * k * fc;
            int save = c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            if (fc > 0.01f || lift > 0.01f) {
                t.set(-w / 2, -h / 2, w / 2, h / 2);
                if (lift > 0.01f) {
                    t.offset(0, (20 + 8 * lift) * k);
                    deepShadow.setColor(Ui.alpha(0x66000000, Math.max(fc, lift) * a));
                    shadows.draw(c, t, rad, 44 * k, deepShadow);
                } else {
                    t.offset(0, 20 * k * fc);
                    shadow.setColor(Ui.alpha(0x66000000, fc * a));
                    shadows.draw(c, t, rad, 34 * k, shadow);
                }
            }
            float tilt = cell.tilt.value;
            if (Math.abs(tilt) > 0.002f) {
                camera.save();
                if (cell.tiltVertical) camera.rotateX(4 * tilt);
                else camera.rotateY(4 * tilt);
                camera.getMatrix(cam);
                camera.restore();
                c.concat(cam);
            }
            int ia = Math.round(255 * a);
            body(c, cell, w, h, rad, ia, -8 * k * tilt * (cell.tiltVertical ? 0 : 1), s);
            r.set(-w / 2, -h / 2, w / 2, h / 2);
            if (fc < 0.99f) {
                stroke.setShader(null);
                stroke.setStrokeWidth(1 * k);
                stroke.setColor(Ui.alpha(0x1FFFFFFF, (1 - fc) * a));
                c.drawRoundRect(r, rad, rad, stroke);
            }
            if (fc > 0.01f) {
                m.setScale(w, h);
                m.postTranslate(-w / 2, -h / 2);
                sheen.setLocalMatrix(m);
                fill.setShader(sheen);
                fill.setAlpha(Math.round(255 * fc * a));
                c.drawRoundRect(r, rad, rad, fill);
                if (cell.sheenAt >= 0) {
                    float p = Ui.clamp((SystemClock.uptimeMillis() - cell.sheenAt) / (float) SHEEN_MS);
                    if (p < 1) {
                        float o = p * 1.6f - 0.6f;
                        m.setScale(0.6f * w, 0.6f * h);
                        m.postTranslate(-w / 2 + o * w, -h / 2 + o * h);
                        sweep.setLocalMatrix(m);
                        fill.setShader(sweep);
                        c.drawRoundRect(r, rad, rad, fill);
                    }
                }
                fill.setShader(null);
                m.setScale(1, h);
                m.postTranslate(0, -h / 2);
                rim.setLocalMatrix(m);
                stroke.setShader(rim);
                stroke.setStrokeWidth(1.5f * k);
                stroke.setAlpha(Math.round(255 * fc * a));
                c.drawRoundRect(r, rad, rad, stroke);
                stroke.setShader(null);
            }
            c.restoreToCount(save);
            if (withLabel && fc > 0.01f && cell.label.length() > 0) {
                label.setAlpha(Math.round(255 * fc * a));
                c.drawText(cell.label, 0, cell.label.length(), cx, y + dy + h + LABEL_DY * k, label);
            }
        }

        /** The tile's face in a box centred on 0, 0 (the canvas already scaled by s). */
        private void body(Canvas c, Cell cell, float w, float h, float rad, int alpha, float artDx, float s) {
            StartModel.Tile tile = cell.tile;
            r.set(-w / 2, -h / 2, w / 2, h / 2);
            switch (cell.type) {
                case T_ART:
                    drawArt(c, tile, r, rad, alpha, artDx, 1.04f);
                    break;
                case T_LOGO: {
                    gradient(c, cell.brand, r, rad, alpha, false);
                    boolean rest = s < 1 + GROW / 2;
                    Bitmap logo = rest ? cell.logoRest : cell.logoFocus;
                    if (logo == null) logo = cell.logoRest != null ? cell.logoRest : tile.logo;
                    if (logo != null) {
                        float lw = logo.getWidth(), lh = logo.getHeight();
                        if (!rest) {
                            lw /= 1 + GROW;
                            lh /= 1 + GROW;
                        }
                        t.set(-lw / 2 + artDx * 0.5f, -lh / 2, lw / 2 + artDx * 0.5f, lh / 2);
                        bitmap.setAlpha(alpha);
                        bitmap.setColorFilter(cell.whiteLogo ? white : null);
                        c.drawBitmap(logo, null, t, bitmap);
                        bitmap.setColorFilter(null);
                    }
                    break;
                }
                case T_EXPLORE: {
                    gradient(c, explore, r, rad, alpha, true);
                    float group = cell.symbolW + 14 * k + cell.innerW, left = -group / 2;
                    Symbols.draw(context, c, cell.symbol, left + cell.symbolW / 2, 0, cell.symbolSize, Ui.alpha(CardPainter.INK, alpha / 255f));
                    inner30.setAlpha(alpha);
                    c.drawText(cell.inner, 0, cell.inner.length(), left + cell.symbolW + 14 * k, inner30Half, inner30);
                    break;
                }
                case T_LIVE:
                    gradient(c, live, r, rad, alpha, true);
                    Symbols.draw(context, c, cell.symbol, 0, 0, cell.symbolSize, Ui.alpha(CardPainter.INK, alpha / 255f));
                    break;
                case T_INPUT:
                case T_SETTINGS: {
                    gradient(c, cell.type == T_INPUT ? input : settings, r, rad, alpha, true);
                    Symbols.draw(context, c, cell.symbol, 0, -16 * k, cell.symbolSize, Ui.alpha(CardPainter.INK, alpha / 255f));
                    inner24.setAlpha(alpha);
                    c.drawText(cell.inner, 0, cell.inner.length(), -cell.innerW / 2, 28 * k + inner24Cap, inner24);
                    if (cell.type == T_INPUT && tile.connected) {
                        fill.setShader(null);
                        fill.setColor(Ui.alpha(CONNECTED, alpha / 255f));
                        c.drawCircle(w / 2 - 13 * k, -h / 2 + 13 * k, 5 * k, fill);
                    }
                    break;
                }
                case T_FOLDER:
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(FOLDER, alpha / 255f));
                    c.drawRoundRect(r, rad, rad, fill);
                    drawMini(c, cell, alpha);
                    break;
                default:
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(PLAIN, alpha / 255f));
                    c.drawRoundRect(r, rad, rad, fill);
                    inner24.setAlpha(alpha);
                    c.drawText(cell.inner, 0, cell.inner.length(), -cell.innerW / 2, inner24Cap / 2, inner24);
                    break;
            }
        }

        private void gradient(Canvas c, LinearGradient g, RectF box, float rad, int alpha, boolean diagonal) {
            if (g == null) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(PLAIN, alpha / 255f));
            } else {
                m.setScale(diagonal ? box.width() : 1, box.height());
                m.postTranslate(diagonal ? box.left : 0, box.top);
                g.setLocalMatrix(m);
                fill.setShader(g);
                fill.setAlpha(alpha);
            }
            c.drawRoundRect(box, rad, rad, fill);
            fill.setShader(null);
        }

        /** The tile's banner over box, `over` times larger (no edge shows while the art slides by dx). */
        private void drawArt(Canvas c, StartModel.Tile tile, RectF box, float rad, int alpha, float dx, float over) {
            Bitmap b = tile.art;
            if (b == null || tile.shader == null || b.getWidth() <= 0 || b.getHeight() <= 0) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(PLAIN, alpha / 255f));
                c.drawRoundRect(box, rad, rad, fill);
                return;
            }
            float bw = box.width() * over, bh = box.height() * over;
            m.setScale(bw / b.getWidth(), bh / b.getHeight());
            m.postTranslate(box.centerX() - bw / 2 + dx, box.centerY() - bh / 2);
            tile.shader.setLocalMatrix(m);
            art.setShader(tile.shader);
            art.setAlpha(alpha);
            c.drawRoundRect(box, rad, rad, art);
            art.setShader(null);
        }

        private void drawMini(Canvas c, Cell cell, int alpha) {
            if (cell.mini == null) return;
            float mw = MINI_W * k, mh = MINI_H * k, gap = MINI_GAP * k, mr = MINI_R * k;
            float gw = 3 * mw + 2 * gap, gh = 2 * mh + gap;
            for (int i = 0; i < cell.mini.length; i++) {
                StartModel.Tile a = cell.mini[i];
                float left = -gw / 2 + (i % 3) * (mw + gap), top = -gh / 2 + (i / 3) * (mh + gap);
                t.set(left, top, left + mw, top + mh);
                switch (cell.miniType[i]) {
                    case T_ART:
                        drawArt(c, a, t, mr, alpha, 0, 1);
                        break;
                    case T_LOGO: {
                        gradient(c, cell.miniBrand[i], t, mr, alpha, false);
                        Bitmap logo = cell.miniLogo[i];
                        if (logo != null) {
                            float lw = logo.getWidth(), lh = logo.getHeight(), mx = t.centerX(), my = t.centerY();
                            r.set(mx - lw / 2, my - lh / 2, mx + lw / 2, my + lh / 2);
                            bitmap.setAlpha(alpha);
                            int[] style = STYLE.get(a.pkg);
                            bitmap.setColorFilter(style != null && style[2] == 1 ? white : null);
                            c.drawBitmap(logo, null, r, bitmap);
                            bitmap.setColorFilter(null);
                        }
                        break;
                    }
                    case T_LIVE:
                    case T_INPUT:
                    case T_EXPLORE:
                    case T_SETTINGS: {
                        int type = cell.miniType[i];
                        gradient(c, type == T_LIVE ? live : type == T_INPUT ? input : type == T_EXPLORE ? explore : settings, t, mr, alpha, true);
                        Symbols.draw(context, c, symbolFor(a, type), t.centerX(), t.centerY(), cell.miniSymbol[i],
                                Ui.alpha(CardPainter.INK, alpha / 255f));
                        break;
                    }
                    default:
                        fill.setShader(null);
                        fill.setColor(Ui.alpha(PLAIN, alpha / 255f));
                        c.drawRoundRect(t, mr, mr, fill);
                        break;
                }
            }
        }
    }

    /** One row of a grid: draws its tiles at their slots (focused one last), in its parent's coordinates. */
    static final class GridRowView extends View {
        private final Grid grid;
        private final TilePainter painter;
        private final StartHost host;
        int row;

        GridRowView(Context c, Grid grid, TilePainter painter, StartHost host, int row) {
            super(c);
            this.grid = grid;
            this.painter = painter;
            this.host = host;
            this.row = row;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setWillNotDraw(false);
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                host.fault("grid draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            float k = grid.k;
            c.translate(0, -getTop());
            int from = row * COLUMNS, to = Math.min(grid.size(), from + COLUMNS);
            float idle = grid.idle.value;
            int top = grid.focus >= from && grid.focus < to && grid.focus != grid.lifted ? grid.focus : -1;
            for (int i = from; i < to; i++) {
                if (i == grid.lifted || i == top) continue;
                drawCell(c, i, idle, k);
            }
            if (top >= 0) drawCell(c, top, idle, k);   // the focused tile over its neighbours
        }

        private void drawCell(Canvas c, int i, float idle, float k) {
            painter.draw(c, grid.cells.get(i), grid.slotX(i) * k, grid.slotY(i) * k, idle, true, 0, true);
        }
    }

    /**
     * The grid's container (the main one under the Top Shelf, or inside the folder sheet): a 1920 × 1080 box whose rows
     * reach below it; it moves by translationY (the page scroll) and fades as one, with a hardware layer only then.
     */
    static final class GridPage extends ViewGroup {
        final Grid grid;
        private final TilePainter painter;
        private final StartHost host;
        private final float k;
        private final ArrayList<GridRowView> rows = new ArrayList<>();

        GridPage(Context c, Grid grid, TilePainter painter, StartHost host, float k) {
            super(c);
            this.grid = grid;
            this.painter = painter;
            this.host = host;
            this.k = k;
            setClipChildren(false);
            setClipToPadding(false);
            setWillNotDraw(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public boolean hasOverlappingRendering() {
            return getLayerType() != LAYER_TYPE_NONE;
        }

        /** As many row Views as the grid has rows (after a new list). */
        void sync() {
            int want = grid.rows();
            boolean changed = false;
            while (rows.size() < want) {
                GridRowView v = new GridRowView(getContext(), grid, painter, host, rows.size());
                rows.add(v);
                addView(v, new ExplorePage.Pos(0, grid.top + PITCH * v.row - EXT, 1920, H + 2 * EXT));
                changed = true;
            }
            while (rows.size() > want) {
                GridRowView v = rows.remove(rows.size() - 1);
                removeView(v);
                changed = true;
            }
            if (changed) {
                layoutRows();
                requestLayout();
            }
            invalidateRows();
        }

        void invalidateRows() {
            grid.markAll();
            for (GridRowView v : rows) v.invalidate();
        }

        /** Rows out of sight (after a scroll of s layout units) go INVISIBLE; editing keeps them all. */
        void cull(float s) {
            for (GridRowView v : rows) {
                float top = grid.top + PITCH * v.row - s;
                boolean on = grid.editing || (top + H + 120 > -40 && top - 60 < 1080 + 40);
                int vis = on ? VISIBLE : INVISIBLE;
                if (v.getVisibility() != vis) v.setVisibility(vis);
            }
        }

        boolean step(float dt) {
            boolean busy = grid.step(dt);
            for (GridRowView v : rows) if (grid.dirty(v.row)) v.invalidate();
            grid.clearDirty();
            return busy;
        }

        private void layoutRows() {
            for (GridRowView v : rows) {
                ExplorePage.Pos p = (ExplorePage.Pos) v.getLayoutParams();
                int x = Math.round(p.x * k), y = Math.round(p.y * k);
                v.layout(x, y, x + Math.round(p.w * k), y + Math.round(p.h * k));
            }
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                ExplorePage.Pos p = (ExplorePage.Pos) c.getLayoutParams();
                c.measure(MeasureSpec.makeMeasureSpec(Math.round(p.w * k), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(p.h * k), MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            layoutRows();
        }

        @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
            return p instanceof ExplorePage.Pos;
        }

        @Override protected void dispatchDraw(Canvas c) {
            int save = c.save();
            try {
                super.dispatchDraw(c);
            } catch (RuntimeException e) {
                host.fault("grid page draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }
    }

    /**
     * Edit mode's lifted tile, over everything but the menu: centred in its 380 × 260 box, moved to its slot by
     * translationX/Y and wobbling by setRotation (both composited: no repaint while it only moves).
     */
    static final class LiftView extends View {
        private final TilePainter painter;
        private final StartHost host;
        Cell cell;
        float lift;

        LiftView(Context c, TilePainter painter, StartHost host) {
            super(c);
            this.painter = painter;
            this.host = host;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            if (cell == null) return;
            int save = c.save();
            try {
                float k = painter.k;
                painter.draw(c, cell, getWidth() / 2f - W * k / 2, getHeight() / 2f - H * k / 2, 0, false, Ui.clamp(lift), false);
            } catch (RuntimeException e) {
                host.fault("lift draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }
    }
}
