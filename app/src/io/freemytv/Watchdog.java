package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Safety net for the :home process (home screen and screensaver): each crash is recorded, and on the 3rd within 5 min
 * the home screen goes back to the TV's original one (requested from the main process) and the app leaves a notice.
 * File: files/home-crashes.txt.
 */
final class Watchdog {
    private Watchdog() {}

    private static final long WINDOW = 5 * 60_000L;
    private static final int LIMIT = 3;
    private static boolean installed;

    static synchronized void install(Context c) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                long now = System.currentTimeMillis();
                // Início (StartView) was showing: the next start builds the classic home screen for 24 h, so the new
                // one costs at most one of the 3 crashes that give the home screen back to the TV.
                if ("start".equals(screen)) writeLong(blockFile(app), now + BLOCK_MS);
            } catch (Throwable ignored) {
            }
            try {
                if (record(app, System.currentTimeMillis()) >= LIMIT) HomeMode.requestRevertHome(app);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Our home screen crashed too often just now: don't retry (avoids a crash loop while the original comes back). */
    static boolean crashedTooOften(Context c) {
        return recent(c, System.currentTimeMillis()).size() >= LIMIT;
    }

    /** After Free My TV mode is turned back on, the count starts over. */
    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        clearStartBlock(c);
    }

    // MARK: - Início (the "Moderno" layout) and its fallback to the classic home screen

    /** What HomeActivity is showing: "start" (Início), "classic" (HomeView) or "" before onCreate picks one. */
    static volatile String screen = "";

    private static final long BLOCK_MS = 24 * 60 * 60_000L, NETWORK_MS = 60 * 60_000L;
    private static final int SOFT_LIMIT = 3;
    private static final int SOFT_MAX_BYTES = 4096;

    /** Início stays off: an uncaught crash while it showed (24 h), or 3 caught faults in 24 h. */
    static boolean startBlocked(Context c) {
        try {
            return startBlockedUntil(c) > System.currentTimeMillis();
        } catch (RuntimeException e) {
            return true;   // can't tell: the classic home screen is the safe one
        }
    }

    /** Until when Início stays off (System.currentTimeMillis), or 0 when it isn't blocked. */
    static long startBlockedUntil(Context c) {
        long now = System.currentTimeMillis();
        long until = readLong(blockFile(c));
        if (until <= now || until > now + BLOCK_MS + 60_000L) until = 0;   // a past stamp, or a clock moved back
        List<Long> faults = softStamps(c, now, BLOCK_MS);
        if (faults.size() >= SOFT_LIMIT) {
            // Blocked until the oldest of the last 3 leaves the 24 h window.
            long third = faults.get(faults.size() - SOFT_LIMIT) + BLOCK_MS;
            if (third > until) until = third;
        }
        return until;
    }

    /** A fault Início caught and survived (HomeActivity.startFault): recorded, never counted as a crash. */
    static synchronized void softFault(Context c) {
        long now = System.currentTimeMillis();
        List<Long> list = softStamps(c, now, BLOCK_MS);
        list.add(now);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, list.size() - 64); i < list.size(); i++) sb.append(list.get(i)).append('\n');
        while (sb.length() > SOFT_MAX_BYTES) sb.delete(0, sb.indexOf("\n") + 1);
        try (FileOutputStream out = new FileOutputStream(softFile(c))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** How many faults Início caught in the last `windowMs`. */
    static int softFaults(Context c, long windowMs) {
        return softStamps(c, System.currentTimeMillis(), windowMs).size();
    }

    /** No TMDB on the home screen for an hour after a caught fault (Top Shelf carousels, matched art). */
    static boolean networkBlocked(Context c) {
        try {
            return softFaults(c, NETWORK_MS) > 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** "Try the new Home Screen again", or Free My TV mode turned on again: the block and the faults go. */
    static void clearStartBlock(Context c) {
        //noinspection ResultOfMethodCallIgnored
        blockFile(c).delete();
        //noinspection ResultOfMethodCallIgnored
        softFile(c).delete();
    }

    private static List<Long> softStamps(Context c, long now, long windowMs) {
        List<Long> list = new ArrayList<>();
        for (long at : readLongs(softFile(c))) if (at <= now && now - at < windowMs) list.add(at);
        return list;
    }

    private static long readLong(File f) {
        List<Long> v = readLongs(f);
        return v.isEmpty() ? 0 : v.get(0);
    }

    private static void writeLong(File f, long v) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write((v + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<Long> readLongs(File f) {
        List<Long> list = new ArrayList<>();
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), SOFT_MAX_BYTES)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try {
                    list.add(Long.parseLong(line.trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return list;
    }

    private static File blockFile(Context c) {
        return new File(c.getFilesDir(), "inicio-block.txt");
    }

    private static File softFile(Context c) {
        return new File(c.getFilesDir(), "inicio-faults.txt");
    }

    private static int record(Context c, long now) throws IOException {
        List<Long> list = recent(c, now);
        list.add(now);
        StringBuilder sb = new StringBuilder();
        for (long at : list) sb.append(at).append('\n');
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return list.size();
    }

    private static List<Long> recent(Context c, long now) {
        List<Long> list = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return list;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) Math.min(f.length(), 4096)];
            int read = in.read(data);
            if (read <= 0) return list;
            for (String line : new String(data, 0, read, StandardCharsets.UTF_8).split("\n")) {
                try {
                    long at = Long.parseLong(line.trim());
                    if (now - at < WINDOW && at <= now) list.add(at);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "home-crashes.txt");
    }
}
