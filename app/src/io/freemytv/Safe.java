package io.freemytv;

import android.content.ComponentCallbacks2;
import android.os.Handler;
import android.os.Looper;

import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explore's guard rails (it runs in :explore, which never installs the Watchdog, but a crash would still close it):
 * every background job catches Throwable, every main-thread delivery catches RuntimeException, and logs carry only a
 * tag and an exception's class name, never a message (HttpURLConnection's carry the whole URL, api_key included).
 */
final class Safe {
    private Safe() {}

    /** Small file writes: recent searches, the installed snapshot, links. One thread, in order. */
    static final ExecutorService IO;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong SEQ = new AtomicLong();

    static {
        ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), threads("explore-io"));
        io.allowCoreThreadTimeOut(true);
        IO = io;
    }

    /**
     * A job for a {@link #pool}: run in order of priority (lower first), then the newest generation, then first in,
     * first out. Its body never lets anything escape.
     */
    abstract static class Job implements Runnable {
        final String tag;
        volatile int priority;
        volatile int generation;
        final long seq = SEQ.incrementAndGet();

        Job(String tag, int priority) {
            this.tag = tag;
            this.priority = priority;
        }

        abstract void work() throws Exception;

        @Override
        public final void run() {
            try {
                work();
            } catch (Throwable t) {
                failed(tag, t);
            }
        }
    }

    private static final Comparator<Runnable> ORDER = (a, b) -> {
        int pa = a instanceof Job ? ((Job) a).priority : Integer.MAX_VALUE, pb = b instanceof Job ? ((Job) b).priority : Integer.MAX_VALUE;
        if (pa != pb) return pa < pb ? -1 : 1;
        int ga = a instanceof Job ? ((Job) a).generation : 0, gb = b instanceof Job ? ((Job) b).generation : 0;
        if (ga != gb) return ga > gb ? -1 : 1;
        long sa = a instanceof Job ? ((Job) a).seq : 0, sb = b instanceof Job ? ((Job) b).seq : 0;
        return Long.compare(sa, sb);
    };

    /** r, catching everything it throws (OutOfMemoryError included: then the images are let go). */
    static Runnable wrap(String tag, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                failed(tag, t);
            }
        };
    }

    /** Runs r on the main thread; an exception drops that one result instead of closing Explore. */
    static void post(Handler main, String tag, Runnable r) {
        (main != null ? main : MAIN).post(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log(tag, e);
            }
        });
    }

    /** The tag and the exception's class name: never its message. */
    static void log(String tag, Throwable t) {
        AppLog.w(tag + ": " + (t == null ? "null" : t.getClass().getSimpleName()));
    }

    private static void failed(String tag, Throwable t) {
        try {
            log(tag, t);
            if (t instanceof OutOfMemoryError) {
                MAIN.post(() -> {
                    try {
                        ExploreImages images = ExploreImages.get(null);
                        if (images != null) images.trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                    } catch (RuntimeException e) {
                        log("trim", e);
                    }
                });
            }
        } catch (Throwable ignored) {
            // nothing more can be done: the job is dropped
        }
    }

    /** Daemon threads at background priority, named name-1, name-2… */
    static ThreadFactory threads(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** A fixed pool fed by a priority queue (see {@link Job}); idle threads end after 30 s. Use execute(), never submit(). */
    static ThreadPoolExecutor pool(String name, int threads) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new PriorityBlockingQueue<Runnable>(16, ORDER), threads(name));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
