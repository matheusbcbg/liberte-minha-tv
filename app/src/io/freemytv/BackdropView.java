package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;

/**
 * Explore's full-screen picture: two slots (hardware layers, each rasterized once with its scrims) that crossfade
 * with a little sideways parallax, and the Dim over them (a solid view, alpha only). Not a View itself: it adds its
 * three views at index 0..2 of the host.
 */
final class BackdropView {
    private static final float FADE_S = 0.6f, SLIDE = 40, BLEED = 48;

    private final ExploreImages images;
    private final Slot a, b;
    private final View dim;
    private final float k;
    private Slot front, back;
    /** The picture asked for and not shown yet (waiting for its image). */
    private String pendingPath;
    private int pendingSpec;
    private boolean pendingStrong, pending, pendingAnimate;
    private float fade = 1;     // 0..1 progress of the running crossfade (1 = at rest)
    private boolean fadeOut;    // the running fade goes to nothing (no picture)
    private float parallax;

    BackdropView(ViewGroup host, ExploreImages img) {
        images = img;
        Context c = host.getContext();
        k = ExploreView.scaleOf(c);
        a = new Slot(c, k);
        b = new Slot(c, k);
        dim = new View(c);
        dim.setBackgroundColor(CardPainter.BG);
        dim.setAlpha(0);
        dim.setVisibility(View.INVISIBLE);
        dim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(a, 0, new ExplorePage.Pos(-BLEED, 0, 1920 + 2 * BLEED, 1080));
        host.addView(b, 1, new ExplorePage.Pos(-BLEED, 0, 1920 + 2 * BLEED, 1080));
        host.addView(dim, 2, new ExplorePage.Pos(0, 0, 1920, 1080));
        a.setAlpha(0);
        b.setAlpha(0);
        a.setVisibility(View.INVISIBLE);
        b.setVisibility(View.INVISIBLE);
        front = a;
        back = b;
    }

    void show(String heroPath, boolean animate) {
        show(heroPath, ExploreImages.HERO, false, animate);
    }

    /** A picture of any spec (the detail page's tinted TINY poster), with the detail page's stronger left scrim. */
    void show(String path, int spec, boolean strong, boolean animate) {
        if (path == null) {
            pending = false;
            if (front.path == null) return;
            startFade(null, 0, false, animate);
            return;
        }
        if (path.equals(front.path) && spec == front.spec && fade >= 1 && !fadeOut) {
            pending = false;
            if (front.strong != strong) {
                front.strong = strong;
                front.invalidate();
            }
            // Its picture may have been trimmed or evicted while the layer showed it: imagesArrived redraws it.
            if (images.get(spec, path) == null) images.want(spec, path, ExploreImages.NOW);
            return;
        }
        if (pending && path.equals(pendingPath) && spec == pendingSpec) return;
        if (images.get(spec, path) != null) {
            pending = false;
            startFade(path, spec, strong, animate);
            return;
        }
        pending = true;
        pendingPath = path;
        pendingSpec = spec;
        pendingStrong = strong;
        pendingAnimate = animate;
        images.want(spec, path, ExploreImages.NOW);
    }

    /** Back on screen: the picture in front is asked for again if it left the memory cache meanwhile. */
    void refresh() {
        if (front.path != null && images.get(front.spec, front.path) == null) images.want(front.spec, front.path, ExploreImages.NOW);
    }

    /** The path showing (or about to), for the pages' checks. */
    String current() {
        return pending ? pendingPath : front.path;
    }

    private void startFade(String path, int spec, boolean strong, boolean animate) {
        if (path == null) {
            // A crossfade still running leaves only the picture in front, which then fades away.
            back.set(null, 0, false);
            back.setAlpha(0);
            back.setVisibility(View.INVISIBLE);
            fadeOut = true;
            fade = animate ? 0 : 1;
            if (!animate) {
                front.set(null, 0, false);
                front.setAlpha(0);
                front.setVisibility(View.INVISIBLE);
            }
            return;
        }
        fadeOut = false;
        Slot in = back;
        back = front;
        front = in;
        in.set(path, spec, strong);
        in.setVisibility(View.VISIBLE);
        in.setTranslationY(parallax);
        if (animate && back.path != null) {
            fade = 0;
            apply();
        } else if (animate) {
            fade = 0;   // nothing under it: a plain fade in from the background
            apply();
        } else {
            fade = 1;
            apply();
        }
    }

    private void apply() {
        float e = fade * fade * (3 - 2 * fade);
        if (fadeOut) {
            front.setAlpha(1 - e);
            front.setTranslationX(-SLIDE * k * e);
            if (fade >= 1) {
                front.set(null, 0, false);
                front.setVisibility(View.INVISIBLE);
            }
            return;
        }
        // The upper slot (b) is the one that fades: coming in over the old picture, or going away off the new one.
        // Both layers never sit half-transparent over the background together.
        boolean under = back.path != null;
        if (!under) front.setAlpha(e);
        else if (front == b) {
            front.setAlpha(e);
            back.setAlpha(1);
        } else {
            front.setAlpha(1);
            back.setAlpha(1 - e);
        }
        front.setTranslationX(SLIDE * k * (1 - e));
        if (under) back.setTranslationX(-SLIDE * k * e);
        if (fade >= 1) {
            front.setTranslationX(0);
            back.setAlpha(0);
            back.setTranslationX(0);
            back.setVisibility(View.INVISIBLE);
            back.set(null, 0, false);
        }
    }

    void setParallax(float dy) {
        dy = Math.max(-380 * k, Math.min(0, dy));
        if (dy == parallax) return;
        parallax = dy;
        a.setTranslationY(dy);
        b.setTranslationY(dy);
    }

    void setDim(float d) {
        d = Ui.clamp(d);
        if (dim.getAlpha() == d) return;
        dim.setAlpha(d);
        int vis = d <= 0.001f ? View.INVISIBLE : View.VISIBLE;
        if (dim.getVisibility() != vis) dim.setVisibility(vis);
    }

    boolean step(float dt) {
        if (fade >= 1) return false;
        fade = Math.min(1, fade + dt / FADE_S);
        apply();
        return fade < 1;
    }

    /** Pictures came in: start the crossfade that waited for one, redraw a slot that was drawn without its picture. */
    void imagesArrived() {
        if (pending && images.get(pendingSpec, pendingPath) != null) {
            pending = false;
            startFade(pendingPath, pendingSpec, pendingStrong, pendingAnimate);
        }
        if (front.path != null && !front.drawnWithImage) front.invalidate();
    }

    /** True while a crossfade runs or a picture is awaited. */
    boolean busy() {
        return fade < 1;
    }

    void clear() {
        pending = false;
        fade = 1;
        fadeOut = false;
        for (Slot s : new Slot[] {a, b}) {
            s.set(null, 0, false);
            s.setAlpha(0);
            s.setVisibility(View.INVISIBLE);
        }
    }

    /** One full-bleed picture with its scrims, recorded once into its layer. */
    private final class Slot extends View {
        String path;
        int spec;
        boolean strong, drawnWithImage;
        private final float k;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint scrim = new Paint(Paint.DITHER_FLAG), tint = new Paint();
        private LinearGradient left, leftStrong, bottom, top;
        private final Rect src = new Rect();
        private final RectF dst = new RectF();

        Slot(Context c, float k) {
            super(c);
            this.k = k;
            setLayerType(LAYER_TYPE_HARDWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            tint.setColor(0xB30B0B0D);
        }

        void set(String path, int spec, boolean strong) {
            this.path = path;
            this.spec = spec;
            this.strong = strong;
            drawnWithImage = false;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float x = BLEED * k;
            left = new LinearGradient(x, 0, x + 1240 * k, 0, 0xF00B0B0D, 0x000B0B0D, Shader.TileMode.CLAMP);
            leftStrong = new LinearGradient(x, 0, x + 1240 * k, 0, 0xF20B0B0D, 0x000B0B0D, Shader.TileMode.CLAMP);
            bottom = new LinearGradient(0, 520 * k, 0, 1080 * k, 0x000B0B0D, CardPainter.BG, Shader.TileMode.CLAMP);
            top = new LinearGradient(0, 0, 0, 200 * k, 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "backdrop draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            if (path == null || left == null) return;
            ExploreImages.Img img = images.get(spec, path);
            drawnWithImage = img != null;
            int w = getWidth(), h = getHeight();
            c.drawColor(CardPainter.BG);
            if (img != null && img.bitmap != null) {
                float bw = img.bitmap.getWidth(), bh = img.bitmap.getHeight();
                float screenW = 1920 * k * 1.04f, screenH = 1080 * k * 1.04f;
                // Cover: crop the picture to the screen's shape.
                float sw = bw, sh = bh;
                if (bw / bh > screenW / screenH) sw = bh * screenW / screenH;
                else sh = bw * screenH / screenW;
                src.set(Math.round((bw - sw) / 2), Math.round((bh - sh) / 2), Math.round((bw + sw) / 2), Math.round((bh + sh) / 2));
                float cx = w / 2f, cy = 540 * k;
                dst.set(cx - screenW / 2, cy - screenH / 2, cx + screenW / 2, cy + screenH / 2);
                c.drawBitmap(img.bitmap, src, dst, image);
                if (spec == ExploreImages.TINY) c.drawRect(0, 0, w, h, tint);
            }
            scrim.setShader(strong ? leftStrong : left);
            c.drawRect(0, 0, w, h, scrim);
            scrim.setShader(bottom);
            c.drawRect(0, 520 * k, w, h, scrim);
            scrim.setShader(top);
            c.drawRect(0, 0, w, 200 * k, scrim);
            scrim.setShader(null);
        }
    }
}
