package io.freemytv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.HashMap;

/**
 * The settings' icons (SPEC3 §2.7): vector shapes on a 100-unit box, strokes 6 units wide with round caps and joins,
 * turned into one filled Path per icon at one pixel size in prepare() (strokes outlined, cut-outs with Path.op), so
 * drawing is one drawPath with one reused Paint. At most two sizes live: the platter's 210 and the toast's 30.
 */
final class SetIcons {
    private static final int SIZES = 2;
    private static final SetIcons[] CACHE = new SetIcons[SIZES];
    private static int next;

    final float size;
    private final HashMap<String, Path> shapes = new HashMap<>(), faint = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Only while building.
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float s;

    /** The icons at this pixel size, built once (the two newest sizes are kept). UI thread. */
    static synchronized SetIcons prepare(float sizePx) {
        float size = Math.max(4, Math.round(sizePx));
        for (SetIcons i : CACHE) if (i != null && i.size == size) return i;
        SetIcons icons = new SetIcons(size);
        CACHE[next] = icons;
        next = (next + 1) % SIZES;
        return icons;
    }

    private SetIcons(float size) {
        this.size = size;
        s = size / 100f;
        paint.setStyle(Paint.Style.FILL);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeCap(Paint.Cap.ROUND);
        outline.setStrokeJoin(Paint.Join.ROUND);
        build();
    }

    boolean has(String name) {
        return name != null && shapes.containsKey(name);
    }

    /** Draws the icon centred on (cx, cy) in color; an unknown name draws "info". No allocation. */
    void draw(Canvas c, String name, float cx, float cy, int color) {
        Path p = name == null ? null : shapes.get(name);
        if (p == null) {
            name = "info";
            p = shapes.get(name);
        }
        if (p == null) return;
        int save = c.save();
        try {
            c.translate(cx - size / 2, cy - size / 2);
            paint.setColor(color);
            c.drawPath(p, paint);
            Path f = faint.get(name);
            if (f != null) {
                paint.setColor(Ui.alpha(color, 0.5f));
                c.drawPath(f, paint);
            }
        } finally {
            c.restoreToCount(save);
        }
    }

    // MARK: - The recipes (100-unit box)

    private void build() {
        Icon i;
        i = icon("house");
        i.line(18, 48, 50, 20, 82, 48);
        i.rrect(28, 44, 72, 82, 6);
        i.rrect(44, 62, 56, 82, 3);
        i.done();

        i = icon("moon");
        Path moon = circle(50, 50, 30);
        moon.op(circle(64, 38, 26), Path.Op.DIFFERENCE);
        i.fill(moon);
        i.done();

        i = icon("grid");
        float gw = 3 * 20 + 2 * 7, gh = 2 * 16 + 7, gx = 50 - gw / 2, gy = 50 - gh / 2;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                float x = gx + col * 27, y = gy + row * 23;
                i.fill(rrect(x, y, x + 20, y + 16, 4));
            }
        }
        i.done();

        i = icon("sparkles");
        i.fill(star(44, 54, 30));
        i.fill(star(74, 26, 12));
        i.done();

        i = icon("sun_cloud");
        i.circle(36, 36, 11);
        for (int a = 0; a < 8; a++) {
            double t = Math.PI / 4 * a;
            float c = (float) Math.cos(t), sn = (float) Math.sin(t);
            i.line(36 + 17 * c, 36 + 17 * sn, 36 + 23 * c, 36 + 23 * sn);
        }
        Path cloud = circle(48, 64, 14);
        cloud.op(circle(64, 58, 18), Path.Op.UNION);
        cloud.op(circle(78, 66, 12), Path.Op.UNION);
        Path base = new Path();
        base.addRect(46 * s, 64 * s, 80 * s, 78 * s, Path.Direction.CW);
        cloud.op(base, Path.Op.UNION);
        // The cloud covers the sun behind it, with a sliver of room.
        i.cut(grown(cloud, 5));
        i.fill(cloud);
        i.done();

        i = icon("pin");
        Path pin = new Path();
        double tangent = Math.toDegrees(Math.acos(20.0 / 46.0));   // 64.2°: where the lines from the tip touch the circle
        float start = (float) (90 + tangent);
        pin.moveTo(50 * s, 86 * s);
        pin.lineTo((float) (50 + 20 * Math.cos(Math.toRadians(start))) * s,
                (float) (40 + 20 * Math.sin(Math.toRadians(start))) * s);
        r.set(30 * s, 20 * s, 70 * s, 60 * s);
        pin.arcTo(r, start, (float) (360 - 2 * tangent), false);
        pin.close();
        i.stroke(pin, 6);
        i.fill(circle(50, 40, 7));
        i.done();

        i = icon("photo");
        i.rrect(16, 24, 84, 76, 8);
        i.line(24, 68, 42, 48, 56, 62, 64, 54, 78, 68);
        i.fill(circle(66, 38, 6));
        i.done();

        i = icon("appearance");
        i.circle(50, 50, 32);
        Path half = new Path();
        r.set(18 * s, 18 * s, 82 * s, 82 * s);
        half.arcTo(r, -90, 180, true);
        half.close();
        i.fill(half);
        i.done();

        i = icon("globe");
        i.circle(50, 50, 32);
        Path ellipse = new Path();
        ellipse.addOval(36 * s, 18 * s, 64 * s, 82 * s, Path.Direction.CW);
        i.stroke(ellipse, 6);
        i.line(18, 50, 82, 50);
        i.done();

        i = icon("clock");
        i.circle(50, 50, 32);
        i.line(50, 50, 50, 30);
        i.line(50, 50, 64, 58);
        i.done();

        i = icon("thermo");
        i.rrect(44, 16, 56, 62, 6);
        i.circle(50, 70, 12);
        i.line(50, 40, 50, 70);
        i.done();

        i = icon("speaker");
        i.fill(poly(22, 40, 36, 40, 54, 26, 54, 74, 36, 60, 22, 60));
        i.arc(54, 50, 12, -45, 90);
        i.arc(54, 50, 22, -45, 90);
        i.done();

        i = icon("remote");
        i.rrect(36, 12, 64, 88, 14);
        i.circle(50, 34, 9);
        i.fill(circle(50, 58, 3));
        i.fill(circle(50, 70, 3));
        i.done();

        i = icon("info");
        i.circle(50, 50, 34);
        i.fill(circle(50, 32, 4));
        i.line(50, 44, 50, 68);
        i.done();

        i = icon("tv");
        i.rrect(14, 22, 86, 70, 8);
        i.line(38, 82, 62, 82);
        i.done();

        i = icon("external");
        i.line(34, 66, 66, 34);
        i.line(42, 34, 66, 34, 66, 58);
        i.done();

        i = icon("play");
        Path play = poly(36, 26, 36, 74, 76, 50);
        i.fill(play);
        i.stroke(play, 6);   // the round joins round its corners
        i.done();

        i = icon("key");
        i.circle(34, 50, 14);
        i.line(48, 50, 84, 50);
        i.line(70, 50, 70, 62);
        i.line(80, 50, 80, 60);
        i.done();

        i = icon("person");
        i.circle(50, 36, 12);
        i.arc(50, 82, 24, 180, 180);
        i.done();

        i = icon("shelf");
        for (int x : new int[]{18, 41, 64}) i.fill(rrect(x, 38, x + 18, 58, 4));
        i.line(16, 70, 84, 70);
        i.done();

        i = icon("dots");
        i.fill(circle(28, 50, 9));
        i.fill(circle(50, 50, 9));
        i.fill(circle(72, 50, 9));
        i.done();

        i = icon("wifi");
        i.arc(50, 72, 12, 225, 90);
        i.arc(50, 72, 24, 225, 90);
        i.arc(50, 72, 36, 225, 90);
        i.fill(circle(50, 72, 4));
        i.done();

        i = icon("cards");
        Path front = rrect(18, 34, 74, 80, 8), back = rrect(26, 26, 82, 72, 8);
        i.stroke(front, 6);
        Path backLine = outlined(back, 6);
        backLine.op(grown(front, 3), Path.Op.DIFFERENCE);   // hidden behind the front card
        faint.put(i.name, backLine);
        i.done();

        i = icon("power");
        i.arc(50, 52, 28, 300, 300);
        i.line(50, 18, 50, 48);
        i.done();

        i = icon("reset");
        i.arc(50, 50, 28, 40, 290);
        i.head(50, 50, 28, 330, 1);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                float x = 39 + col * 13, y = 39 + row * 13;
                i.fill(rrect(x, y, x + 9, y + 9, 2));
            }
        }
        i.done();

        i = icon("music");
        i.line(40, 24, 40, 68);
        i.line(70, 18, 70, 62);
        i.stroke(linePath(40, 24, 70, 18), 8);
        Path note = new Path();
        note.addOval(22 * s, 62 * s, 42 * s, 78 * s, Path.Direction.CW);
        note.addOval(52 * s, 56 * s, 72 * s, 72 * s, Path.Direction.CW);
        i.fill(note);
        i.done();

        i = icon("chart");
        i.line(18, 70, 38, 50, 54, 60, 82, 30);
        i.line(70, 30, 82, 30, 82, 42);
        i.done();

        i = icon("gauge");
        i.arc(50, 58, 34, 180, 180);
        i.line(50, 58, 68, 40);
        i.fill(circle(50, 58, 5));
        i.done();

        i = icon("refresh");
        i.arc(50, 50, 28, 40, 290);
        i.head(50, 50, 28, 330, 1);
        i.done();

        i = icon("bell");
        Path bell = new Path();
        bell.moveTo(30 * s, 66 * s);
        bell.lineTo(30 * s, 44 * s);
        r.set(30 * s, 24 * s, 70 * s, 64 * s);
        bell.arcTo(r, 180, 180, false);
        bell.lineTo(70 * s, 66 * s);
        i.stroke(bell, 6);
        i.line(24, 66, 76, 66);
        i.fill(circle(50, 76, 5));
        i.done();

        i = icon("doc");
        i.rrect(26, 16, 74, 84, 8);
        i.line(36, 36, 64, 36);
        i.line(36, 50, 64, 50);
        i.line(36, 64, 64, 64);
        i.done();

        i = icon("trash");
        i.line(24, 28, 76, 28);
        i.line(42, 20, 58, 20);
        Path can = new Path();
        can.moveTo(30 * s, 28 * s);
        can.lineTo(30 * s, 78 * s);
        r.set(30 * s, 72 * s, 42 * s, 84 * s);
        can.arcTo(r, 180, -90, false);
        can.lineTo(64 * s, 84 * s);
        r.set(58 * s, 72 * s, 70 * s, 84 * s);
        can.arcTo(r, 90, -90, false);
        can.lineTo(70 * s, 28 * s);
        i.stroke(can, 6);
        i.done();

        i = icon("check");
        i.stroke(linePath(26, 52, 43, 68, 74, 34), 8);
        i.done();

        i = icon("warning");
        Path warn = poly(50, 16, 86, 80, 14, 80);
        i.stroke(warn, 6);
        i.line(50, 40, 50, 60);
        i.fill(circle(50, 70, 4));
        i.done();

        i = icon("chevron");
        i.line(40, 26, 62, 50, 40, 74);
        i.done();
    }

    // MARK: - Building blocks

    private Icon icon(String name) {
        return new Icon(name);
    }

    /** One icon being built: strokes are outlined and everything is united into one filled Path. */
    private final class Icon {
        final String name;
        final Path all = new Path();
        Path cut;
        boolean empty = true;

        Icon(String name) {
            this.name = name;
        }

        void line(float... xy) {
            stroke(linePath(xy), 6);
        }

        void rrect(float l, float t, float rt, float b, float radius) {
            stroke(SetIcons.this.rrect(l, t, rt, b, radius), 6);
        }

        void circle(float cx, float cy, float radius) {
            stroke(SetIcons.this.circle(cx, cy, radius), 6);
        }

        void arc(float cx, float cy, float radius, float start, float sweep) {
            Path p = new Path();
            r.set((cx - radius) * s, (cy - radius) * s, (cx + radius) * s, (cy + radius) * s);
            p.arcTo(r, start, sweep, true);
            stroke(p, 6);
        }

        /** An arrowhead at the arc's end (angle `at` in degrees), pointing along the arc's direction (dir ±1). */
        void head(float cx, float cy, float radius, float at, int dir) {
            double a = Math.toRadians(at);
            float x = cx + radius * (float) Math.cos(a), y = cy + radius * (float) Math.sin(a);
            // The tangent at that point, the way the arc goes (clockwise on screen for dir 1).
            float tx = -(float) Math.sin(a) * dir, ty = (float) Math.cos(a) * dir;
            float nx = (float) Math.cos(a), ny = (float) Math.sin(a), len = 12;
            line(x - tx * len + nx * len * 0.8f, y - ty * len + ny * len * 0.8f, x, y,
                    x - tx * len - nx * len * 0.8f, y - ty * len - ny * len * 0.8f);
        }

        void stroke(Path p, float width) {
            add(outlined(p, width));
        }

        void fill(Path p) {
            add(p);
        }

        /** Takes this shape out of what's already there (and only that). */
        void cut(Path p) {
            if (!empty && !all.op(p, Path.Op.DIFFERENCE)) AppLog.w("settings: icon cut failed: " + name);
        }

        private void add(Path p) {
            if (empty) {
                all.set(p);
                empty = false;
            } else if (!all.op(p, Path.Op.UNION)) {
                all.addPath(p);
            }
        }

        void done() {
            shapes.put(name, all);
        }
    }

    private Path outlined(Path p, float width) {
        outline.setStrokeWidth(width * s);
        Path out = new Path();
        outline.getFillPath(p, out);
        return out;
    }

    /** The shape grown by `units` on every side (its outline, stroked twice as wide, united with it). */
    private Path grown(Path p, float units) {
        Path out = outlined(p, 2 * units);
        if (!out.op(p, Path.Op.UNION)) out.addPath(p);
        return out;
    }

    private Path linePath(float... xy) {
        Path p = new Path();
        p.moveTo(xy[0] * s, xy[1] * s);
        for (int j = 2; j + 1 < xy.length; j += 2) p.lineTo(xy[j] * s, xy[j + 1] * s);
        return p;
    }

    private Path poly(float... xy) {
        Path p = linePath(xy);
        p.close();
        return p;
    }

    private Path circle(float cx, float cy, float radius) {
        Path p = new Path();
        p.addCircle(cx * s, cy * s, radius * s, Path.Direction.CW);
        return p;
    }

    private Path rrect(float l, float t, float rt, float b, float radius) {
        Path p = new Path();
        p.addRoundRect(l * s, t * s, rt * s, b * s, radius * s, radius * s, Path.Direction.CW);
        return p;
    }

    /** A four-pointed star with concave sides, centred on (cx, cy). */
    private Path star(float cx, float cy, float radius) {
        float c = radius * 0.14f;
        Path p = new Path();
        p.moveTo(cx * s, (cy - radius) * s);
        p.cubicTo((cx + c) * s, (cy - c * 2) * s, (cx + c * 2) * s, (cy - c) * s, (cx + radius) * s, cy * s);
        p.cubicTo((cx + c * 2) * s, (cy + c) * s, (cx + c) * s, (cy + c * 2) * s, cx * s, (cy + radius) * s);
        p.cubicTo((cx - c) * s, (cy + c * 2) * s, (cx - c * 2) * s, (cy + c) * s, (cx - radius) * s, cy * s);
        p.cubicTo((cx - c * 2) * s, (cy - c) * s, (cx - c) * s, (cy - c * 2) * s, cx * s, (cy - radius) * s);
        p.close();
        return p;
    }
}
