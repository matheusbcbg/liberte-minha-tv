package io.freemytv;

import android.content.Context;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Continue Watching in the new settings (SPEC3 §3.8, ported from SA:1966-2055): the permission asked right after a
 * "No topo do Início" choice that shows Continue Watching (never on leaving a row), the 10 s fallback to Android's own
 * dialog, the lock of Continue Watching › Apps, the scan of the apps that can feed it (on Safe.IO) and their on/off.
 *
 * Process-wide state (one Settings screen at a time, as the legacy view), touched only on the main thread. Every
 * Runnable posted here is guarded: a throw goes to host.fault, never out of the Handler.
 */
final class SetWatch {
    private SetWatch() {}

    /** How long the ADB grant gets before Android's permission dialog asks instead (SA:1966). */
    static final long WATCH_GRANT_MS = 10_000;

    private static boolean watchPickPending, watchGrantPending;
    private static SetHost askHost;   // the host whose handler holds askWatch
    private static final Runnable ASK_WATCH = () -> {
        SetHost h = askHost;
        if (h == null) return;
        try {
            askWatch(h);
        } catch (RuntimeException e) {
            h.fault("settings-ask-watch", e);
        }
    };

    // Continue Watching apps: {package, name}, null until the background scan is done.
    private static List<String[]> apps;
    private static int scan;
    private static int hiddenCount;
    /** The open Apps page's rebuild, between its onEnter and onLeave; null otherwise. */
    private static Runnable onScanned;

    /** r for a Handler (or another module's main-thread callback): a throw goes to host.fault (SPEC3 §4.4). */
    static Runnable guarded(SetHost h, String tag, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                h.fault(tag, e);
            }
        };
    }

    // MARK: - The permission

    /**
     * A choice was just made in pick.top (the bookkeeping of SA:1449-1456): any earlier ask is dropped, and commit then
     * asks for the permission if the new choice shows Continue Watching.
     */
    static void picked(SetHost h) {
        h.handler().removeCallbacks(ASK_WATCH);
        watchGrantPending = false;
        watchPickPending = true;
    }

    /**
     * Asks for Continue Watching's permission once after a choice that shows it (SA:1973-1983): the TV's own ADB first,
     * Android's dialog 10 s later if that didn't come through. Also called by SetView.pause, as the legacy pause did.
     */
    static void commit(SetHost h) {
        if (!watchPickPending) return;
        watchPickPending = false;
        h.handler().removeCallbacks(ASK_WATCH);
        Context c = h.context();
        if (Prefs.showsWatching(Prefs.topContent()) && !WatchNext.allowed(c)) {
            if (h.running()) h.toast(Ui.tr("For Continue Watching, the TV may ask for a permission: choose Allow."), false);
            HomeMode.requestWatchGrant(c);
            watchGrantPending = true;
            askHost = h;
            h.handler().postDelayed(ASK_WATCH, WATCH_GRANT_MS);
        }
    }

    /**
     * Back in front with a grant still pending (the legacy resume, SA:411): the fallback is asked again in 10 s. Safe to
     * call more than once; the Picks page calls it when it opens.
     */
    static void resume(SetHost h) {
        if (!watchGrantPending) return;
        if (askHost != null) askHost.handler().removeCallbacks(ASK_WATCH);
        askHost = h;
        h.handler().postDelayed(ASK_WATCH, WATCH_GRANT_MS);
    }

    /** The ADB grant didn't come through (yet): Android's own permission dialog asks instead (SA:1985-1992). */
    private static void askWatch(SetHost h) {
        if (!h.running()) return;   // still pending: resume asks again
        watchGrantPending = false;
        if (Prefs.showsWatching(Prefs.topContent()) && !WatchNext.allowed(h.context())) {
            h.activity().requestPermissions(new String[] {WatchNext.PERMISSION}, 1);
        }
    }

    /** Continue Watching › Apps is locked while "No topo do Início" shows no Continue Watching (SA:1993). */
    static boolean locked() {
        return !Prefs.showsWatching(Prefs.topContent());
    }

    // MARK: - The apps

    /**
     * Looks for the apps that can feed Continue Watching, on Safe.IO (only here, never on the home screen; SA:1996-2012).
     * When it's done, the open Apps page rebuilds and the top page refreshes (the Apps row's count).
     */
    static void scan(SetHost h) {
        final int mine = ++scan;
        final Context c = h.context().getApplicationContext();
        Safe.IO.execute(Safe.wrap("settings-watch-scan", () -> {
            final List<String[]> found = WatchNext.sources(c);
            h.handler().post(guarded(h, "settings-watch-scan", () -> {
                if (mine != scan) return;
                apps = found;
                if (!h.running()) return;
                Runnable rebuild = onScanned;
                if (rebuild != null) rebuild.run();
                h.refresh();
            }));
        }));
        scannedAt = android.os.SystemClock.uptimeMillis();
    }

    private static long scannedAt = -1;

    /** The Picks page opened: scan again unless a scan started in the last 10 s (a pop back from its sub-pages). */
    static void scanIfStale(SetHost h) {
        if (scannedAt < 0 || android.os.SystemClock.uptimeMillis() - scannedAt > 10_000) scan(h);
    }

    /** The scanned apps ({package, name}, by name), or null while the scan runs. */
    static List<String[]> apps() {
        return apps;
    }

    /** "5 of 7": the apps on, of those found; "…" while scanning (SA:2014-2021). */
    static String count() {
        List<String[]> list = apps;
        if (list == null) return "…";
        Set<String> off = Prefs.watchOff();
        int on = 0;
        for (String[] app : list) if (!off.contains(app[0])) on++;
        return Ui.trf("{on} of {total}", "on", on, "total", list.size());
    }

    static boolean isOn(String pkg) {
        return !Prefs.watchOff().contains(pkg);
    }

    /** OK on an app: on or off in Continue Watching (SA:2048-2054). */
    static void toggle(String pkg) {
        Set<String> off = Prefs.watchOff();
        if (!off.remove(pkg)) off.add(pkg);
        Prefs.setWatchOff(off);
    }

    /** The hidden items when the Apps page opened (SA:2027), for its last row. */
    static int hiddenCount() {
        return hiddenCount;
    }

    /** OK on "Show hidden items (n)": they're all back (SA:2037-2046). */
    static void showHidden(SetHost h) {
        Prefs.setWatchHidden(new HashSet<>());
        hiddenCount = 0;
        h.announce(Ui.tr("Hidden items are back"));
    }

    /** The Apps page opened (SA:2023-2028): counts the hidden items, and rebuilds itself when the scan ends. */
    static void opened(Runnable rebuild) {
        hiddenCount = Prefs.watchHidden().size();
        onScanned = rebuild;
    }

    /** The Apps page is leaving (pop or pause). */
    static void closed(Runnable rebuild) {
        if (onScanned == rebuild) onScanned = null;
    }
}
