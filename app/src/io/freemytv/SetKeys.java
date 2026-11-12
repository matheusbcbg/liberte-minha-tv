package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.WindowManager;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The TMDB and Watchmode key pages (SPEC3 §3.7, ported from SA:1564-1846): a one-time page for the phone (KeyPage)
 * behind a QR code on the left pane, the key typed with the remote in a text sheet, and removing a key given on the TV.
 *
 * One key screen at a time, as the legacy view: the state is static and touched only on the main thread. KeyPage's
 * listener runs on its threads: it only posts guarded Runnables, and accept() catches everything, since nothing may
 * escape those threads (:home's watchdog counts it as a home screen crash). The key is never logged or shown.
 */
final class SetKeys {
    private SetKeys() {}

    // The live screen's state (SA:192-199).
    private static KeyPage keyPage;
    private static boolean keyWatchmode;   // the screen is the Watchmode key's, else TMDB's
    private static Bitmap keyQr;
    private static boolean keyChecking, noNetwork, newShown;
    private static String status;           // k.status's text, or null
    private static int statusTone;
    private static long startedAt;          // uptime when the live page started (its 5 minutes)
    private static String minutes = "";
    private static SetHost host;
    private static Panel panel;

    private static final Runnable TICK = () -> {
        SetHost h = host;
        if (h == null) return;
        try {
            tick(h);
        } catch (RuntimeException e) {
            h.fault("settings-key-tick", e);
        }
    };

    static SetPage page(SetHost h, boolean watchmode) {
        Context c = h.context();
        host = h;
        String purpose = purpose(watchmode);
        SetPage p = new SetPage(watchmode ? "keys.watchmode" : "keys.tmdb", title(watchmode), "key");
        p.about = purpose;
        panel = new Panel(c, purpose);
        p.preview = panel;

        final SetRow st = SetRow.info("k.status", "").icon("key");
        st.refresh(() -> {
            boolean here = keyWatchmode == watchmode;
            String text = here ? status : null;
            st.hidden = text == null || text.isEmpty();
            st.title = text == null ? "" : text;
            st.about = st.title;
            st.busy = here && keyChecking;
            st.tone = here && !keyChecking ? statusTone : SetRow.TONE_NONE;
            st.value = here && keyPage != null ? minutes : "";
        });
        p.add(st);

        p.header(Ui.tr("How to"));
        String[] steps = steps(watchmode);
        for (int i = 0; i < steps.length; i++) {
            SetRow r = SetRow.info("k.step" + (i + 1), steps[i]).about(steps[i] + "\n\n" + purpose);
            r.order = i + 1;
            p.add(r);
        }
        p.gap();

        final SetRow fresh = SetRow.action("k.new", Ui.tr("New code"), () -> start(h, watchmode)).icon("refresh");
        fresh.refresh(() -> fresh.hidden = keyPage != null || keyChecking);
        fresh.about(purpose);
        p.add(fresh);

        p.add(SetRow.action("k.type", Ui.tr("Type it with the remote"), () -> askKey(h, watchmode))
                .icon("key").about(purpose));

        final SetRow remove = SetRow.destructive("k.remove", Ui.tr("Remove the key"), () -> askRemove(h, watchmode))
                .icon("trash").about(purpose);
        remove.refresh(() -> remove.hidden = given(watchmode).isEmpty());
        p.add(remove);

        p.onEnter = () -> start(h, watchmode);
        p.onLeave = () -> leave(h);
        return p;
    }

    private static String title(boolean watchmode) {
        return watchmode ? Ui.tr("Watchmode key") : Ui.tr("TMDB key");
    }

    private static String purpose(boolean watchmode) {
        return watchmode ? Ui.tr("The key makes a pick open the title's page in its app, where the app allows it.")
                : Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver.");
    }

    /** Today's three steps (SA:1574-1583), one per row. */
    private static String[] steps(boolean watchmode) {
        return watchmode ? new String[] {
                Ui.tr("Create an account at api.watchmode.com."),
                Ui.tr("Copy the API key it gives you."),
                Ui.tr("Scan this code with your phone and paste the key there.")}
                : new String[] {
                Ui.tr("Create an account at themoviedb.org."),
                Ui.tr("Open Settings → API and request a developer key."),
                Ui.tr("Scan this code with your phone and paste the API Read Access Token (or the API Key) there.")};
    }

    /** The key given on the TV (not one built in from config.properties): only that one can be removed here. */
    private static String given(boolean watchmode) {
        return watchmode ? Prefs.watchmodeKey() : Prefs.tmdbKey();
    }

    // MARK: - The phone's page (SA:1602-1634)

    /** A new page and its code (a new port and token); the old one stops. */
    private static void start(SetHost h, boolean watchmode) {
        stop(h);
        host = h;
        keyWatchmode = watchmode;
        noNetwork = false;
        InetAddress lan = KeyPage.lanAddress(h.context());
        if (lan == null) {
            noNetwork = true;
            setStatus(h, Ui.tr("The TV isn't on a network: connect it, or type the key."), SetRow.TONE_WARN);
        } else {
            try {
                keyPage = new KeyPage(lan, watchmode, listener(h));
                keyQr = qrBitmap(Qr.encode(keyPage.url));
                // While the code waits (5 minutes at most), the TV's screensaver doesn't cover it.
                h.activity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                startedAt = SystemClock.uptimeMillis();
                minutes = minutesLeft();
                h.handler().postDelayed(TICK, 15_000);
                setStatus(h, Ui.tr("Waiting for your phone. The code works for 5 minutes."), SetRow.TONE_NONE);
            } catch (IOException | RuntimeException e) {
                Safe.log("settings-key-page", e);   // the class name only
                stop(h);
                setStatus(h, Ui.tr("That didn't work."), SetRow.TONE_WARN);
            }
        }
        rowsChanged(h, true);
    }

    /** Stops the phone's page (the screen is left, a key came, the TV is paused). */
    private static void stop(SetHost h) {
        if (keyPage != null) keyPage.close();
        keyPage = null;
        keyQr = null;
        h.handler().removeCallbacks(TICK);
        h.activity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** onLeave (pop or pause, SA:427-432): the phone's page answers only while its code shows. */
    private static void leave(SetHost h) {
        if (keyPage != null) {
            stop(h);
            setStatus(h, Ui.tr("This code has expired."), SetRow.TONE_NONE);
            rowsChanged(h, false);
        } else {
            h.handler().removeCallbacks(TICK);
        }
    }

    private static void tick(SetHost h) {
        if (keyPage == null) return;
        String now = minutesLeft();
        if (!now.equals(minutes)) {
            minutes = now;
            if (h.running()) h.refresh();
        }
        h.handler().postDelayed(TICK, 15_000);
    }

    private static String minutesLeft() {
        long left = KeyPage.OPEN_FOR - (SystemClock.uptimeMillis() - startedAt);
        int n = (int) Math.max(1, (left + 59_999) / 60_000);
        return Ui.trf("{n} min left", "n", n);
    }

    /** The QR code's modules as a bitmap, one pixel each, with the light margin readers need (4 modules). */
    private static Bitmap qrBitmap(boolean[][] modules) {
        int quiet = 4, n = modules.length + 2 * quiet;
        int[] pixels = new int[n * n];
        java.util.Arrays.fill(pixels, 0xFFFFFFFF);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) if (modules[y][x]) pixels[(y + quiet) * n + x + quiet] = 0xFF000000;
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
    }

    /** Called on KeyPage's threads: posts only, and lets nothing escape. */
    private static KeyPage.Listener listener(SetHost h) {
        final android.os.Handler main = h.handler();
        return new KeyPage.Listener() {
            @Override public void checking() {
                try {
                    main.post(SetWatch.guarded(h, "settings-key-checking", () -> {
                        keyChecking = true;
                        setStatus(h, checkingText(keyWatchmode), SetRow.TONE_NONE);
                        rowsChanged(h, true);
                    }));
                } catch (Throwable ignored) {
                    // nothing may escape KeyPage's threads
                }
            }

            @Override public String accept(String key, boolean watchmode) {
                return watchmode ? saveWatchmodeKey(h, key) : saveKey(h, key);
            }

            @Override public void closed(KeyPage page, boolean expired) {
                try {
                    main.post(SetWatch.guarded(h, "settings-key-closed", () -> {
                        if (page != keyPage) return;   // an older page, or the key came another way
                        stop(h);
                        if (expired) setStatus(h, Ui.tr("This code has expired."), SetRow.TONE_NONE);
                        rowsChanged(h, true);
                    }));
                } catch (Throwable ignored) {
                    // nothing may escape KeyPage's threads
                }
            }
        };
    }

    private static String checkingText(boolean watchmode) {
        return watchmode ? Ui.tr("Checking the key with Watchmode…") : Ui.tr("Checking the key with TMDB…");
    }

    /** Off the UI thread: checks the key with TMDB and saves it if it works. Null when it's saved, else why not. */
    private static String saveKey(SetHost h, String pasted) {
        String why;
        try {
            String key = Recs.cleanKey(pasted);
            why = key.isEmpty() ? Ui.tr("That doesn't look like a TMDB key.") : Recs.checkKey(key);
            if (why == null) Prefs.setTmdbKey(key);
        } catch (Throwable t) {
            Safe.log("settings-key", t);
            why = Ui.tr("That didn't work.");
        }
        keyChecked(h, why, false, Ui.tr("Saved. The picks arrive in a moment."));
        return why;
    }

    /** As saveKey, for the Watchmode key. */
    private static String saveWatchmodeKey(SetHost h, String pasted) {
        String why;
        try {
            String key = Recs.cleanKey(pasted);
            why = key.isEmpty() ? Ui.tr("That doesn't look like a Watchmode key.") : Recs.checkWatchmodeKey(key);
            if (why == null) Prefs.setWatchmodeKey(key);
        } catch (Throwable t) {
            Safe.log("settings-key", t);
            why = Ui.tr("That didn't work.");
        }
        keyChecked(h, why, true, Ui.tr("Saved. The picks' pages arrive in a moment."));
        return why;
    }

    /** From any thread: back on the main thread with the check's result. */
    private static void keyChecked(SetHost h, String why, boolean watchmode, String saved) {
        try {
            h.handler().post(SetWatch.guarded(h, "settings-key-checked", () -> {
                keyChecking = false;
                if (why == null) Recs.get(h.context()).refreshIfStale();
                if (watchmode == keyWatchmode) {   // not if the other key's screen opened meanwhile
                    if (why == null) {
                        stop(h);   // typed: the phone's page has nothing left to do
                        setStatus(h, saved, SetRow.TONE_GOOD);
                    } else {
                        setStatus(h, why, SetRow.TONE_WARN);
                    }
                }
                rowsChanged(h, true);
            }));
        } catch (Throwable ignored) {
            // nothing may escape KeyPage's threads
        }
    }

    private static void setStatus(SetHost h, String text, int tone) {
        status = text;
        statusTone = tone;
        if (text != null && h.running()) h.announce(text);
    }

    /**
     * The rows and the left pane for now: a new code once the last one is used up (the focus goes there when it
     * appears), typing, and removing a key given on the TV.
     */
    private static void rowsChanged(SetHost h, boolean focusNew) {
        boolean was = newShown;
        newShown = keyPage == null && !keyChecking;
        Panel pane = panel;
        if (pane != null) pane.rebuild();
        if (!h.running()) return;
        h.refresh();
        if (focusNew && newShown && !was) h.focus("k.new");
    }

    // MARK: - Typed with the remote, and removed (SA:1741-1790)

    private static void askKey(SetHost h, boolean watchmode) {
        if (keyChecking) return;
        h.askText(title(watchmode), Ui.tr("Paste or type the key"), Ui.tr("Save"), true, "",
                text -> typedKey(h, watchmode, text));
    }

    private static void typedKey(SetHost h, boolean watchmode, String text) {
        if (text == null || text.trim().isEmpty() || keyChecking) return;
        keyChecking = true;
        keyWatchmode = watchmode;
        setStatus(h, checkingText(watchmode), SetRow.TONE_NONE);
        rowsChanged(h, true);
        Safe.IO.execute(Safe.wrap("settings-key", () -> {
            if (watchmode) saveWatchmodeKey(h, text);
            else saveKey(h, text);
        }));
    }

    /** Asks (Cancel focused: one stray OK keeps it), then forgets the key given on the TV. */
    private static void askRemove(SetHost h, boolean watchmode) {
        List<String> body = new ArrayList<>();
        body.add(watchmode ? Ui.tr("Picks open the app's home page again.")
                : Ui.tr("The picks stop until a new key is added."));
        h.confirm(watchmode ? Ui.tr("Remove the Watchmode key?") : Ui.tr("Remove the TMDB key?"), body,
                Ui.tr("Remove"), true, false, () -> {
                    if (watchmode) Prefs.setWatchmodeKey("");
                    else Prefs.setTmdbKey("");
                    if (keyWatchmode == watchmode) status = null;   // the rows say it
                    rowsChanged(h, false);
                });
    }

    // MARK: - The left pane

    /**
     * The left pane of a key page: the platter turns white and holds the QR code in whole-pixel modules, then how to
     * use it and the page's address for a phone without a camera. Without a network, a warning instead of the code;
     * with the code used up, an empty slot and what the key is for. Everything is built in rebuild, never in draw.
     */
    private static final class Panel implements SetPage.Preview {
        private static final int ORANGE_DARK = 0xFFFF9F0A, ORANGE_LIGHT = 0xFFC93400;
        private final Context context;
        private final String purpose;
        private final Paint platter = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
                slot = new Paint(Paint.ANTI_ALIAS_FLAG), qrPaint = new Paint(), icon = new Paint(Paint.ANTI_ALIAS_FLAG),
                iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), qrBox = new RectF(), slotBox = new RectF();
        private final Path warning = new Path();
        private float k = 1;
        private boolean dark = true, laidOut;
        private TextPaint lead, body, link;
        private int leadColor, bodyColor, linkColor;
        private StaticLayout first, second, url;
        private Bitmap qr;
        private String spoken = "";

        Panel(Context c, String purpose) {
            context = c;
            this.purpose = purpose;
            platter.setColor(0xFFFFFFFF);
            rim.setStyle(Paint.Style.STROKE);
            slot.setColor(0xFFE5E5EA);
            qrPaint.setFilterBitmap(false);   // whole pixels per module, no smoothing
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void layout(SetTheme t, float k) {
            this.k = k;
            // SPEC3 §2.3: dark whenever the modern home is in use, else the classic home's light or dark.
            dark = StartSettings.modern() || HomeData.get(context).isNight(System.currentTimeMillis());
            leadColor = dark ? CardPainter.INK : 0xFF1D1D1F;
            bodyColor = dark ? CardPainter.DIM : 0xFF6E6E73;
            linkColor = dark ? 0xFF64D2FF : 0xFF0071A4;
            lead = new TextPaint(Ui.text(Ui.regular, 29 * k, leadColor));
            body = new TextPaint(Ui.text(Ui.regular, 29 * k, bodyColor));
            link = new TextPaint(Ui.numbers(Ui.medium, 25 * k, linkColor));
            rim.setStrokeWidth(Math.max(1, k));
            rim.setColor(dark ? 0x14FFFFFF : 0x14000000);
            int orange = dark ? ORANGE_DARK : ORANGE_LIGHT;
            icon.setColor(orange);
            iconFill.setColor(orange);
            box.set(290 * k, 232 * k, 710 * k, 652 * k);
            // The warning glyph (SPEC3 §2.7) in the 210 px icon box, centred on the platter.
            float s = 210 * k / 100, ox = 500 * k - 105 * k, oy = 442 * k - 105 * k;
            icon.setStrokeWidth(6 * s);
            warning.reset();
            warning.moveTo(ox + 50 * s, oy + 16 * s);
            warning.lineTo(ox + 86 * s, oy + 80 * s);
            warning.lineTo(ox + 14 * s, oy + 80 * s);
            warning.close();
            warning.moveTo(ox + 50 * s, oy + 40 * s);
            warning.lineTo(ox + 50 * s, oy + 60 * s);
            float side = 360 * k;
            slotBox.set(500 * k - side / 2, 442 * k - side / 2, 500 * k + side / 2, 442 * k + side / 2);
            laidOut = true;
            rebuild();
        }

        /** The texts and the code's place for the state now (main thread, at a change: never while drawing). */
        void rebuild() {
            if (!laidOut) return;
            qr = keyQr;
            KeyPage page = keyPage;
            int width = Math.round(640 * k);
            if (qr != null && page != null) {
                int n = qr.getWidth();
                float side = Math.max(1, (int) (360 * k / n)) * n;   // whole pixels per module
                qrBox.set(500 * k - side / 2, 442 * k - side / 2, 500 * k + side / 2, 442 * k + side / 2);
                String a = Ui.tr("Point your phone's camera at the code.");
                String b = Ui.tr("No camera? On a phone on the same network, open:");
                first = text(a, lead, width, 3);
                second = text(b, body, width, 3);
                url = text(page.url, link, width, 2);
                spoken = a + " " + b + " " + page.url;
            } else if (noNetwork) {
                String a = Ui.tr("The TV isn't on a network: connect it, or type the key.");
                first = text(a, body, width, 7);
                second = null;
                url = null;
                spoken = a;
            } else {
                first = text(purpose, body, width, 7);
                second = null;
                url = null;
                spoken = purpose;
            }
        }

        private StaticLayout text(String s, TextPaint paint, int width, int lines) {
            float extra = Math.max(0, (paint == link ? 34 : 38) * k - paint.getFontSpacing());
            return StaticLayout.Builder.obtain(s, 0, s.length(), paint, Math.max(1, width))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(extra, 1).setIncludePad(false)
                    .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
        }

        @Override public void draw(Canvas c, SetTheme t, float k, float alpha) {
            int a = Math.round(255 * Math.max(0, Math.min(1, alpha)));
            platter.setAlpha(a);
            c.drawRoundRect(box, 92 * this.k, 92 * this.k, platter);
            if (!dark) {
                rim.setAlpha(Math.round(Color.alpha(0x14000000) * a / 255f));
                c.drawRoundRect(box, 92 * this.k, 92 * this.k, rim);
            }
            Bitmap code = qr;
            if (code != null && url != null) {
                qrPaint.setAlpha(a);
                c.drawBitmap(code, null, qrBox, qrPaint);
            } else if (noNetwork) {
                icon.setAlpha(a);
                iconFill.setAlpha(a);
                c.drawPath(warning, icon);
                float s = 210 * this.k / 100;
                c.drawCircle(500 * this.k, 442 * this.k - 105 * this.k + 70 * s, 4 * s, iconFill);
            } else {
                slot.setAlpha(a);
                c.drawRoundRect(slotBox, 24 * this.k, 24 * this.k, slot);
            }
            float y = 700 * this.k;
            if (first != null) y = drawText(c, first, first.getPaint(), y, a);
            if (second != null) y = drawText(c, second, body, y + 16 * this.k, a);
            if (url != null) drawText(c, url, link, y + 8 * this.k, a);
        }

        private float drawText(Canvas c, StaticLayout layout, TextPaint paint, float top, int a) {
            if (layout == null || paint == null) return top;
            int base = paint == lead ? leadColor : paint == link ? linkColor : bodyColor;
            paint.setAlpha(Math.round(Color.alpha(base) * a / 255f));
            int save = c.save();
            try {
                c.translate(180 * k, top);
                layout.draw(c);
            } finally {
                c.restoreToCount(save);
            }
            return top + layout.getHeight();
        }

        @Override public String spoken() {
            return spoken;
        }

        @Override public boolean moving() {
            return false;
        }
    }
}
