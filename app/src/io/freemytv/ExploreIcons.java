package io.freemytv;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * Explore's few icons as filled Paths in a size × size box from (0, 0), made once at layout time (never in onDraw):
 * the assets/symbols set has no star, magnifier or backspace.
 */
final class ExploreIcons {
    private ExploreIcons() {}

    /** A five-pointed star (ratings). */
    static Path star(float size) {
        Path p = new Path();
        float cx = size / 2, cy = size * 0.53f, outer = size / 2, inner = outer * 0.42f;
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float r = (i & 1) == 0 ? outer : inner;
            float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
            if (i == 0) p.moveTo(x, y);
            else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    /** A ring with a handle down to the right (search). */
    static Path magnifier(float size) {
        float r = size * 0.33f, t = size * 0.11f, cx = size * 0.42f, cy = size * 0.42f;
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addCircle(cx, cy, r, Path.Direction.CW);
        ring.addCircle(cx, cy, r - t, Path.Direction.CW);
        Path handle = new Path();
        float d = (float) Math.sqrt(0.5);
        float sx = cx + (r - t / 2) * d, sy = cy + (r - t / 2) * d, ex = size * 0.94f, ey = size * 0.94f;
        float nx = -d * t * 0.62f, ny = d * t * 0.62f;   // half the handle's width, across it
        handle.moveTo(sx + nx, sy + ny);
        handle.lineTo(ex + nx, ey + ny);
        handle.lineTo(ex - nx, ey - ny);
        handle.lineTo(sx - nx, sy - ny);
        handle.close();
        Path out = new Path();
        if (!out.op(ring, handle, Path.Op.UNION)) {
            ring.addPath(handle);
            return ring;
        }
        return out;
    }

    /** A play triangle, optically centred. */
    static Path play(float size) {
        Path p = new Path();
        p.moveTo(size * 0.2f, size * 0.1f);
        p.lineTo(size * 0.9f, size * 0.5f);
        p.lineTo(size * 0.2f, size * 0.9f);
        p.close();
        return p;
    }

    /** The backspace key: a tag pointing left with an x cut out of it. */
    static Path backspace(float size) {
        float top = size * 0.2f, bottom = size * 0.8f, left = size * 0.04f, mid = size * 0.3f, right = size * 0.96f;
        Path tag = new Path();
        tag.moveTo(left, size / 2);
        tag.lineTo(mid, top);
        tag.lineTo(right, top);
        tag.lineTo(right, bottom);
        tag.lineTo(mid, bottom);
        tag.close();
        float cx = size * 0.62f, cy = size / 2, arm = size * 0.15f, half = size * 0.045f;
        Path x = new Path();
        bar(x, cx, cy, arm, half, 1);
        bar(x, cx, cy, arm, half, -1);
        Path out = new Path();
        if (!out.op(tag, x, Path.Op.DIFFERENCE)) return tag;
        return out;
    }

    private static void bar(Path p, float cx, float cy, float arm, float half, int dir) {
        Path b = new Path();
        b.addRect(new RectF(cx - arm * 1.414f, cy - half, cx + arm * 1.414f, cy + half), Path.Direction.CW);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setRotate(45 * dir, cx, cy);
        b.transform(m);
        if (!p.op(b, Path.Op.UNION)) p.addPath(b);
    }
}
