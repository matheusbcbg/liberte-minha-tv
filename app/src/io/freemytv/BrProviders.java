package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of ProviderTable's services this TV has, worked out off the main thread (PackageManager) and kept as an
 * immutable snapshot the UI reads freely. Saved to filesDir/explore-installed.json so a cold start has it at once.
 */
final class BrProviders {
    private BrProviders() {}

    private static final String FILE = "explore-installed.json";
    /**
     * In the snapshot (Trailer needs YouTube), never in the rows or MINE: YouTube's TMDB catalogue is rentals and
     * Premium; Claro tv+'s lists films its app doesn't have (ProviderTable.UNRELIABLE).
     */
    private static final Set<String> NOT_A_ROW = new HashSet<>(java.util.Arrays.asList("youtube", "claro"));

    static final class Snapshot {
        private final Map<String, String> pkgs;     // key → installed package
        private final Set<String> enabled;           // installed and not switched off
        private final String ids;
        private final List<ProviderTable.Service> services;

        Snapshot(Map<String, String> pkgs, Set<String> enabled) {
            this.pkgs = Collections.unmodifiableMap(pkgs);
            this.enabled = Collections.unmodifiableSet(enabled);
            StringBuilder b = new StringBuilder();
            List<ProviderTable.Service> list = new ArrayList<>();
            for (ProviderTable.Service s : ProviderTable.TABLE) {
                if (!enabled.contains(s.key) || NOT_A_ROW.contains(s.key)) continue;
                list.add(s);
                for (int id : s.ids) b.append(b.length() > 0 ? "|" : "").append(id);
            }
            ids = b.toString();
            services = Collections.unmodifiableList(list);
        }

        /** Its app is on the TV (switched off or not). */
        boolean installed(String key) { return key != null && pkgs.containsKey(key); }
        /** The package that plays it here, or null. */
        String pkg(String key) { return key == null ? null : pkgs.get(key); }
        /** TMDB ids of the installed, enabled services, "8|1796|119|…" ("" for none). */
        String installedIds() { return ids; }
        /** The installed, enabled services, in ProviderTable order (YouTube left out). */
        List<ProviderTable.Service> installedServices() { return services; }
        /** Installed and not switched off in Home Settings > Picks from. */
        boolean enabled(String key) { return key != null && enabled.contains(key); }

        boolean sameAs(Snapshot o) {
            return o != null && pkgs.equals(o.pkgs) && enabled.equals(o.enabled);
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(new HashMap<String, String>(), new HashSet<String>());
    private static volatile boolean loadedFile;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The last one worked out: UI-safe, never PackageManager. */
    static Snapshot snapshot() {
        return snapshot;
    }

    /** Works it out again on a worker; done (if not null) runs on the main thread when it changed or was first read. */
    static void refresh(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        Safe.IO.execute(Safe.wrap("providers", () -> {
            File file = new File(app.getFilesDir(), FILE);
            if (!loadedFile) {
                loadedFile = true;
                Snapshot saved = read(file);
                if (saved != null && snapshot.pkgs.isEmpty()) {
                    snapshot = saved;
                    notify(done);
                }
            }
            Snapshot fresh = compute(app);
            if (fresh.sameAs(snapshot)) return;
            snapshot = fresh;
            write(file, fresh);
            notify(done);
        }));
    }

    private static void notify(Runnable done) {
        if (done != null) Safe.post(MAIN, "providers", done);
    }

    private static Snapshot compute(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> pkgs = new HashMap<>();
        List<ProviderTable.Service> missing = new ArrayList<>();
        for (ProviderTable.Service s : ProviderTable.TABLE) {
            String found = null;
            for (String p : s.packages) {
                if (HomeApps.launchIntent(pm, p) != null) {
                    found = p;
                    break;
                }
            }
            if (found != null) pkgs.put(s.key, found);
            else missing.add(s);
        }
        // Packages we don't know (another build, a new app): the TV app named like the service.
        if (!missing.isEmpty()) {
            List<String[]> apps = HomeApps.launchable(c);
            for (ProviderTable.Service s : missing) {
                String label = ProviderTable.plain(s.label);
                for (String[] a : apps) {
                    if (a[0].equals(c.getPackageName()) || pkgs.containsValue(a[0])) continue;
                    String name = " " + ProviderTable.plain(a[1]) + " ";
                    boolean same = name.trim().equals(label);
                    for (int i = 0; !same && i < s.words.length; i++) same = name.contains(" " + s.words[i] + " ");
                    if (same) {
                        pkgs.put(s.key, a[0]);
                        break;
                    }
                }
            }
        }
        Set<String> enabled = new HashSet<>();
        for (String key : pkgs.keySet()) if (!Prefs.serviceOff(key)) enabled.add(key);
        return new Snapshot(pkgs, enabled);
    }

    private static Snapshot read(File file) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int n = 0, r;
            while (n < data.length && (r = in.read(data, n, data.length - n)) > 0) n += r;
            JSONObject o = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
            JSONObject p = o.optJSONObject("pkgs");
            Map<String, String> pkgs = new HashMap<>();
            Set<String> enabled = new HashSet<>();
            if (p != null) {
                for (Iterator<String> i = p.keys(); i.hasNext(); ) {
                    String key = i.next();
                    if (ProviderTable.byKey(key) != null) pkgs.put(key, p.optString(key));
                }
            }
            org.json.JSONArray e = o.optJSONArray("enabled");
            for (int i = 0; e != null && i < e.length(); i++) if (pkgs.containsKey(e.optString(i))) enabled.add(e.optString(i));
            return new Snapshot(pkgs, enabled);
        } catch (Exception e) {
            Safe.log("providers read", e);
            return null;
        }
    }

    private static void write(File file, Snapshot s) {
        try {
            JSONObject o = new JSONObject();
            o.put("pkgs", new JSONObject(s.pkgs));
            org.json.JSONArray e = new org.json.JSONArray();
            for (String key : s.enabled) e.put(key);
            o.put("enabled", e);
            writeAtomically(file, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Safe.log("providers write", e);
        }
    }

    /** Writes to name.<pid>.part (one per process: :home and :explore share the caches), then renames it over the file. */
    static void writeAtomically(File file, byte[] data) throws IOException {
        File part = new File(file.getPath() + "." + android.os.Process.myPid() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(data);
            out.flush();
        }
        if (!part.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("rename");
        }
    }
}
