package io.freemytv;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Color;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explore's pictures from image.tmdb.org: downloaded to cacheDir/explore/img (80 MB), decoded at the size they're
 * drawn focused (so focus never upscales), copied to GPU memory with one BitmapShader each, and kept in a main-thread
 * map under a byte budget. get() is safe while drawing: a HashMap lookup, no lock, no allocation.
 */
final class ExploreImages {
    static final int POSTER = 0, WIDE = 1, HERO = 2, LOGO = 3, PROFILE = 4, PROVIDER = 5, STILL = 6, TINY = 7;
    static final int NOW = 0, SOON = 1;

    static final class Img {
        final Bitmap bitmap;   // HARDWARE
        final BitmapShader shader;
        final int w, h, color;
        /** uptimeMillis when it arrived, for the 160 ms fade-in. */
        final long arrived;
        int lastUse;

        Img(Bitmap bitmap, BitmapShader shader, int color, long arrived) {
            this.bitmap = bitmap;
            this.shader = shader;
            this.w = bitmap.getWidth();
            this.h = bitmap.getHeight();
            this.color = color;
            this.arrived = arrived;
        }
    }

    /** Main thread, at most once per frame. */
    interface Listener { void onImages(); }

    private static final String HOST = "image.tmdb.org";
    private static final String[] SIZE = {"w342", "w780", "w1280", "w500", "w185", "w154", "w300", "w92"};
    private static final int[] W = {238, 424, 1280, 720, 166, 92, 340, 46};
    private static final int[] H = {356, 239, 720, 200, 166, 92, 191, 69};
    private static final int SPECS = 8;
    private static final int MAX_PIXELS = 4_000_000;
    private static final int MAX_FILE = 3 << 20;
    private static final long DISK_MAX = 80L << 20;
    private static final long RETRY_MS = 10 * 60_000L;
    private static final int MAX_HEROES = 4;

    private static final ThreadPoolExecutor IMG = Safe.pool("explore-img", 3);

    private static ExploreImages instance;

    /** The process's loader (null for a null context when there is none yet: Safe's trim on OutOfMemoryError). */
    static synchronized ExploreImages get(Context c) {
        if (instance == null && c != null) instance = new ExploreImages(c.getApplicationContext());
        return instance;
    }

    /**
     * The loader for one Explore activity, given back with release(): a finishing instance can outlive the one that
     * replaced it (singleTask), and must not close the loader the new one uses.
     */
    static synchronized ExploreImages acquire(Context c) {
        ExploreImages i = get(c);
        if (i != null) i.users++;
        return i;
    }

    void release() {
        boolean last;
        synchronized (ExploreImages.class) {
            last = --users <= 0;
        }
        if (last) close();
    }

    private final File dir;
    private final Handler main = new Handler(Looper.getMainLooper());
    @SuppressWarnings("unchecked")
    private final HashMap<String, Img>[] maps = new HashMap[SPECS];
    @SuppressWarnings("unchecked")
    private final HashMap<String, Job>[] pending = new HashMap[SPECS];
    @SuppressWarnings("unchecked")
    private final HashMap<String, Long>[] failed = new HashMap[SPECS];
    private final AtomicInteger writes = new AtomicInteger();
    private long budget = 48L << 20, bytes;
    private int frame, heroes;
    private volatile int generation;
    private volatile boolean closed;
    private int users;   // guarded by ExploreImages.class
    private Listener listener;
    private boolean notifyPosted;
    private final Choreographer.FrameCallback notifier = t -> {
        notifyPosted = false;
        Listener l = listener;
        if (l == null || closed) return;
        try {
            l.onImages();
        } catch (RuntimeException e) {
            Safe.log("images listener", e);
        }
    };

    private ExploreImages(Context app) {
        dir = new File(new File(app.getCacheDir(), "explore"), "img");
        for (int i = 0; i < SPECS; i++) {
            maps[i] = new HashMap<>();
            pending[i] = new HashMap<>();
            failed[i] = new HashMap<>();
        }
    }

    void setBudget(long bytes) {
        budget = Math.max(1L << 20, bytes);
        evict(-1);
    }

    /** The network came back: pictures that failed meanwhile may be asked for again at once. */
    void networkBack() {
        for (HashMap<String, Long> m : failed) m.clear();
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Stops telling l (only if it is still the one told: a newer activity's view may have taken over). */
    void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** DRAW-SAFE: the picture if it's in, else null. Marks it used this frame. */
    Img get(int spec, String path) {
        if (path == null || spec < 0 || spec >= SPECS) return null;
        Img img = maps[spec].get(path);
        if (img != null) img.lastUse = frame;
        return img;
    }

    /** Asks for a picture (main thread, never while drawing). Asking again is free; NOW goes before SOON. */
    void want(int spec, String path, int priority) {
        if (closed || path == null || path.isEmpty() || spec < 0 || spec >= SPECS) return;
        Img img = maps[spec].get(path);
        if (img != null) {
            img.lastUse = frame;
            return;
        }
        Job job = pending[spec].get(path);
        if (job != null) {
            if (priority < job.priority || job.generation != generation) {
                if (IMG.getQueue().remove(job)) {
                    job.priority = Math.min(priority, job.priority);
                    job.generation = generation;
                    IMG.execute(job);
                }
            }
            return;
        }
        Long until = failed[spec].get(path);
        if (until != null) {
            if (SystemClock.elapsedRealtime() < until) return;
            failed[spec].remove(path);
        }
        job = new Job(spec, path, priority, generation);
        pending[spec].put(path, job);
        IMG.execute(job);
    }

    /** The viewport moved (a held key): older SOON jobs sink, jobs 3 or more generations old are dropped. */
    void viewportMoved() {
        generation++;
    }

    /** Once per frame, before drawing. */
    void frame() {
        frame++;
    }

    int width(int spec) {
        return spec >= 0 && spec < SPECS ? W[spec] : 1;
    }

    int height(int spec) {
        return spec >= 0 && spec < SPECS ? H[spec] : 1;
    }

    /** RUNNING_LOW and worse: a 16 MB budget; UI_HIDDEN: only the current hero; COMPLETE: nothing. */
    void trim(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            clear(null);
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            Img hero = null;
            for (Img img : maps[HERO].values()) if (hero == null || img.lastUse > hero.lastUse) hero = img;
            clear(hero);
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            budget = Math.min(budget, 16L << 20);
            evict(-1);
        }
    }

    /** Lets everything go; late answers are dropped. The next get(Context) makes a new loader. */
    void close() {
        closed = true;
        listener = null;
        generation += 3;
        clear(null);
        for (int i = 0; i < SPECS; i++) {
            for (Job j : pending[i].values()) IMG.getQueue().remove(j);
            pending[i].clear();
        }
        synchronized (ExploreImages.class) {
            if (instance == this) instance = null;
        }
    }

    private void clear(Img keep) {
        bytes = 0;
        heroes = 0;
        for (int i = 0; i < SPECS; i++) {
            for (Iterator<Img> it = maps[i].values().iterator(); it.hasNext(); ) {
                Img img = it.next();
                if (img == keep) {
                    bytes += size(img);
                    if (i == HERO) heroes++;
                } else {
                    it.remove();
                }
            }
        }
    }

    private static long size(Img img) {
        return 4L * img.w * img.h;
    }

    /**
     * Over the budget (or with more than 4 heroes), lets go of what wasn't drawn in the last two frames, least
     * recently drawn first. Never recycles: a bitmap may still be in a display list.
     */
    private void evict(int justAddedSpec) {
        while (heroes > MAX_HEROES) {
            if (!dropOldest(HERO, Integer.MAX_VALUE)) break;
        }
        while (bytes > budget) {
            if (!dropOldest(-1, frame - 2)) break;
        }
    }

    private boolean dropOldest(int onlySpec, int before) {
        int bestSpec = -1;
        String bestPath = null;
        int bestUse = Integer.MAX_VALUE;
        for (int i = 0; i < SPECS; i++) {
            if (onlySpec >= 0 && i != onlySpec) continue;
            for (Map.Entry<String, Img> e : maps[i].entrySet()) {
                Img img = e.getValue();
                if (img.lastUse < before && img.lastUse < bestUse) {
                    bestUse = img.lastUse;
                    bestSpec = i;
                    bestPath = e.getKey();
                }
            }
        }
        if (bestPath == null) return false;
        Img gone = maps[bestSpec].remove(bestPath);
        if (gone != null) {
            bytes -= size(gone);
            if (bestSpec == HERO) heroes--;
        }
        return true;
    }

    private void arrived(int spec, String path, Job job, Bitmap bitmap, int color) {
        if (pending[spec].get(path) == job) pending[spec].remove(path);
        if (closed || bitmap == null) return;
        Img img = new Img(bitmap, new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), color, SystemClock.uptimeMillis());
        img.lastUse = frame;
        Img old = maps[spec].put(path, img);
        if (old != null) {
            bytes -= size(old);
            if (spec == HERO) heroes--;
        }
        bytes += size(img);
        if (spec == HERO) heroes++;
        evict(spec);
        if (!notifyPosted && listener != null) {
            notifyPosted = true;
            Choreographer.getInstance().postFrameCallback(notifier);
        }
    }

    private void failedJob(int spec, String path, Job job, boolean retryLater) {
        if (pending[spec].get(path) == job) pending[spec].remove(path);
        if (retryLater) failed[spec].put(path, SystemClock.elapsedRealtime() + RETRY_MS);
    }

    // MARK: - Workers

    private final class Job extends Safe.Job {
        final int spec;
        final String path;

        Job(int spec, String path, int priority, int generation) {
            super("image", priority);
            this.spec = spec;
            this.path = path;
            this.generation = generation;
        }

        @Override
        void work() {
            if (closed || ExploreImages.this.generation - generation >= 3) {
                Safe.post(main, "image", () -> failedJob(spec, path, this, false));
                return;
            }
            Bitmap bitmap = null;
            int color = 0;
            boolean retry = true;
            try {
                File file = fetch(spec, path);
                if (file != null) {
                    Bitmap soft = decode(file, spec);
                    if (soft == null) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();   // a bad file: fetched again after the retry wait
                    } else {
                        color = colorOf(soft);
                        bitmap = Backdrop.gpu(soft);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Safe.log("image", e);
            } catch (OutOfMemoryError e) {
                Safe.log("image", e);
                retry = true;
                Safe.post(main, "image", () -> trim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));
            }
            Bitmap b = bitmap;
            int c = color;
            boolean r = retry;
            Safe.post(main, "image", () -> {
                if (b != null) arrived(spec, path, this, b, c);
                else failedJob(spec, path, this, r);
            });
        }
    }

    private static String fileName(int spec, String path) {
        StringBuilder b = new StringBuilder(SIZE[spec].length() + path.length() + 1).append(SIZE[spec]).append('_');
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            b.append(Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_' ? ch : '_');
        }
        return b.toString();
    }

    /** The file on disk (its mtime touched), else downloaded to .part and renamed; null when it can't be had. */
    private File fetch(int spec, String path) throws IOException {
        if (!path.startsWith("/") || path.contains("..")) return null;
        File file = new File(dir, fileName(spec, path));
        if (file.isFile() && file.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        URL url = new URL("https://" + HOST + "/t/p/" + SIZE[spec] + path);
        if (!HOST.equals(url.getHost())) return null;
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        int code = c.getResponseCode();
        if (code != 200) {
            AppLog.w("image " + SIZE[spec] + " " + code);
            InputStream err = c.getErrorStream();
            if (err != null) err.close();
            return null;
        }
        // One name per process: :home and :explore share this folder, and two downloads of one file never mix.
        File part = new File(dir, file.getName() + "." + android.os.Process.myPid() + ".part");
        long total = 0;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > MAX_FILE) throw new IOException("too big");
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (total == 0 || !part.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return null;
        }
        if (writes.incrementAndGet() % 40 == 0) Tmdb.prune(dir, DISK_MAX);
        return file;
    }

    /** Decoded at the spec's size: cropped to its shape (logos fitted, transparency kept). Software ARGB_8888. */
    private static Bitmap decode(File file, int spec) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        int sw = o.outWidth, sh = o.outHeight;
        if (sw <= 0 || sh <= 0) return null;
        int tw = W[spec], th = H[spec];
        if (spec == LOGO) {
            float scale = Math.min(1f, Math.min((float) tw / sw, (float) th / sh));
            tw = Math.max(1, Math.round(sw * scale));
            th = Math.max(1, Math.round(sh * scale));
        }
        // The crop keeps the whole of one side: the source must stay at least the target on both sides.
        int sample = 1;
        while (sw / (sample * 2) >= tw && sh / (sample * 2) >= th) sample *= 2;
        while ((long) (sw / sample) * (sh / sample) > MAX_PIXELS) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), d);
        if (src == null) return null;
        if (spec == LOGO) {
            Bitmap out = Ui.downscale(src, tw, th);
            if (out != src) src.recycle();
            return out;
        }
        float ratio = (float) tw / th;
        int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
        int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
        cw = Math.max(1, cw);
        ch = Math.max(1, ch);
        int x = (src.getWidth() - cw) / 2;
        int y = spec == PROFILE ? Math.round((src.getHeight() - ch) * 0.12f) : (src.getHeight() - ch) / 2;
        Bitmap crop = cw == src.getWidth() && ch == src.getHeight() ? src : Bitmap.createBitmap(src, x, y, cw, ch);
        if (crop != src) src.recycle();
        Bitmap out = Ui.downscale(crop, tw, th);
        if (out != crop) crop.recycle();
        return out;
    }

    /** WatchArt's recipe: the average of an 8×5 copy, its saturation lifted, its brightness kept in the middle. */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        if (tiny != b) tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return Color.HSVToColor(hsv);
    }
}
