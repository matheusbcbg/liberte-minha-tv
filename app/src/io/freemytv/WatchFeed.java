package io.freemytv;

import android.content.Context;
import android.database.ContentObserver;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Continue Watching for Explore: WatchNext read off the main thread, and a watch on the TV's lists while Explore
 * shows, so the row follows what was just watched without a reload. Titles never leave the TV (no TMDB lookups).
 */
final class WatchFeed {
    private WatchFeed() {}

    /** Main thread. allowed: READ_TV_LISTINGS is granted (else the row offers to ask for it). */
    interface Listener { void onWatching(List<WatchNext.Item> items, boolean allowed); }

    private static final long DEBOUNCE_MS = 2000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor LOADER;

    static {
        LOADER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), Safe.threads("explore-watch"));
        LOADER.allowCoreThreadTimeOut(true);
    }

    private static ContentObserver observer;     // main thread
    private static Runnable onChange;
    private static final Runnable FIRE = () -> {
        Runnable r = onChange;
        if (r == null) return;
        try {
            r.run();
        } catch (RuntimeException e) {
            Safe.log("watch feed", e);
        }
    };

    /** Reads the list on a worker; l runs on the main thread. */
    static void load(Context c, Listener l) {
        Context app = c.getApplicationContext();
        LOADER.execute(Safe.wrap("watch feed", () -> {
            boolean allowed = WatchNext.allowed(app);
            List<WatchNext.Item> items;
            try {
                items = allowed ? new ArrayList<>(WatchNext.load(app)) : new ArrayList<WatchNext.Item>();
            } catch (RuntimeException e) {   // revoked between the check and the query, or a broken provider
                Safe.log("watch feed", e);
                if (e instanceof SecurityException) allowed = false;
                items = new ArrayList<>();   // an answer all the same: the row never waits forever
            }
            List<WatchNext.Item> out = Collections.unmodifiableList(items);
            boolean ok = allowed;
            if (l != null) Safe.post(MAIN, "watch feed", () -> l.onWatching(out, ok));
        }));
    }

    /** Main thread: onChange runs (on the main thread) 2 s after the TV's Watch Next list or preview channels change. */
    static void observe(Context c, Runnable onChange) {
        WatchFeed.onChange = onChange;
        if (observer != null) return;
        ContentObserver o = new ContentObserver(MAIN) {
            @Override
            public void onChange(boolean selfChange) {
                MAIN.removeCallbacks(FIRE);
                MAIN.postDelayed(FIRE, DEBOUNCE_MS);
            }
        };
        boolean any = false;
        for (Uri uri : new Uri[] {TvContract.WatchNextPrograms.CONTENT_URI, TvContract.PreviewPrograms.CONTENT_URI}) {
            try {
                c.getContentResolver().registerContentObserver(uri, true, o);
                any = true;
            } catch (SecurityException | IllegalArgumentException e) {   // no permission, or no TV provider here
                Safe.log("watch feed observe", e);
            }
        }
        if (any) observer = o;
    }

    static void stop(Context c) {
        MAIN.removeCallbacks(FIRE);
        onChange = null;
        if (observer == null) return;
        try {
            c.getContentResolver().unregisterContentObserver(observer);
        } catch (RuntimeException e) {
            Safe.log("watch feed stop", e);
        }
        observer = null;
    }
}
