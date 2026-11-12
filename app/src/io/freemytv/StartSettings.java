package io.freemytv;

import android.content.Context;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * What Ajustes do Início shows for Início, the "Moderno" layout (SPEC2 §3.10): the layout picker, Top Shelf previews,
 * Hidden apps, the layout reset and the notice after a fallback to the classic home screen. The settings screen places
 * the rows and calls only these methods; every text comes translated. Main thread unless marked.
 */
final class StartSettings {
    private StartSettings() {}

    /** An app or input of the Hidden apps list. */
    static final class Entry {
        final String key, label;
        final boolean input;
        boolean hidden;

        Entry(String key, String label, boolean input, boolean hidden) {
            this.key = key;
            this.label = label;
            this.input = input;
            this.hidden = hidden;
        }
    }

    /** One thread, first in first out: a toggle is written before the next list is read. */
    private static final ExecutorService WORKER;

    static {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(),
                Safe.threads("start-settings"));
        pool.allowCoreThreadTimeOut(true);
        WORKER = pool;
    }

    // MARK: - Layout and previews

    /** {"Moderno", "Clássico"}: index 0 is Início. */
    static String[] layoutLabels() {
        return new String[] {Ui.tr("layout|Modern"), Ui.tr("layout|Classic")};
    }

    static int layoutIndex() {
        return Prefs.LAYOUT_MODERN.equals(Prefs.homeLayout()) ? 0 : 1;
    }

    /** A Prefs put: the home screen starts over in the chosen layout when it comes back. */
    static void setLayoutIndex(int i) {
        Prefs.setHomeLayout(i == 0 ? Prefs.LAYOUT_MODERN : Prefs.LAYOUT_CLASSIC);
    }

    static boolean previews() {
        return Prefs.topShelfPreviews();
    }

    static void setPreviews(boolean on) {
        Prefs.setTopShelfPreviews(on);
    }

    /** Início is the chosen layout: Shelf and Top Buttons do nothing there (the settings screen dims them). */
    static boolean modern() {
        return layoutIndex() == 0;
    }

    // MARK: - The rows' texts, translated (SPEC2 §3.10), for the settings screen to place

    /** "Home layout" → "Layout do Início". */
    static String layoutTitle() {
        return Ui.tr("Home layout");
    }

    /** "Top Shelf previews" → "Prévias no topo". */
    static String previewsTitle() {
        return Ui.tr("Top Shelf previews");
    }

    static String previewsDescription() {
        return Ui.tr("Pictures from TMDB for your apps at the top. Off: no internet use on the Home Screen.");
    }

    /** "Hidden apps" → "Apps ocultos": the row and the title of its list screen. */
    static String hiddenTitle() {
        return Ui.tr("Hidden apps");
    }

    /** An entry's value: "Oculto" or "Visível". */
    static String hiddenValue(boolean hidden) {
        return hidden ? Ui.tr("Hidden") : Ui.tr("Visible");
    }

    /** The Hidden apps screen with nothing to list. */
    static String noHiddenApps() {
        return Ui.tr("No hidden apps");
    }

    /** "Reset Home Screen layout" → "Restaurar organização do Início". */
    static String resetTitle() {
        return Ui.tr("Reset Home Screen layout");
    }

    /** Its confirmation: "Voltar todos os apps ao lugar original?". */
    static String resetConfirm() {
        return Ui.tr("Put every app back in its original place?");
    }

    /** The reason under Shelf and Top Buttons while modern(): they do nothing in Início. */
    static String notUsedByModern() {
        return Ui.tr("Not used by the Modern layout");
    }

    /** The description of Theme while modern(): it drives only the classic layout and the screensaver. */
    static String alwaysDark() {
        return Ui.tr("Início is always dark.");
    }

    /** The notice row's action: "Tentar o novo Início de novo" (then retryModern). */
    static String retryLabel() {
        return Ui.tr("Try the new Home Screen again");
    }

    // MARK: - Hidden apps

    /**
     * Every app and input Início can show, A-Z, each with whether it's hidden; read on a worker, delivered on the
     * main thread. Explorar and our own tile can't be hidden, so they aren't listed. Empty on any failure.
     */
    static void loadEntries(Context c, java.util.function.Consumer<List<Entry>> done) {
        final Context app = c.getApplicationContext();
        WORKER.execute(Safe.wrap("start entries", () -> {
            List<Entry> out = new ArrayList<>();
            try {
                HomeLayout layout = HomeLayout.parse(Prefs.startLayout());
                Set<String> hidden = layout != null ? layout.hidden : Collections.<String>emptySet();
                Inventory inv = inventory(app);
                for (String[] a : inv.apps) out.add(new Entry(a[0], a[1], false, hidden.contains(a[0])));
                for (String[] in : inv.inputs) out.add(new Entry(in[0], in[1], true, hidden.contains(in[0])));
                final Collator collator = Collator.getInstance(Ui.locale());
                collator.setStrength(Collator.PRIMARY);
                Collections.sort(out, (x, y) -> collator.compare(x.label, y.label));
            } catch (RuntimeException e) {
                Safe.log("start entries", e);
                out.clear();
            }
            Safe.post(null, "start entries", () -> done.accept(out));
        }));
    }

    /** Hides or shows an app or input in Início: writes the layout (seeded first if it was never saved). */
    static void setHidden(String key, boolean hidden) {
        if (key == null || HomeLayout.EXPLORE.equals(key) || HomeLayout.SETTINGS.equals(key)) return;
        final Context app = Prefs.context();
        if (app == null) return;
        WORKER.execute(Safe.wrap("start hide", () -> {
            HomeLayout layout = HomeLayout.parse(Prefs.startLayout());
            Inventory inv = inventory(app);
            if (layout == null) {
                layout = seed(app, inv);
                layout.resetStamp = System.currentTimeMillis();   // a seed of now: Início doesn't seed it again
            }
            if (hidden) layout.hide(key);
            else layout.unhide(key);
            Prefs.setStartLayout(layout.serialize());
            Prefs.setShelfQuietly(layout.dockPackages(layout.visible(inv.present())));
            Safe.post(null, "start hide", Prefs::homeChanged);   // Início rebuilds when it comes back
        }));
    }

    /** "Reset Home Screen layout": every app back in its original place (Início seeds again on return). */
    static void resetLayout() {
        Prefs.resetStartLayout(System.currentTimeMillis());
    }

    // MARK: - Fallback to the classic home screen

    /**
     * "The new Home Screen had a problem, so the classic one is back until {time}.", while Início is picked but kept
     * off after a crash or repeated faults; null otherwise.
     */
    static String fallbackNotice(Context c) {
        if (c == null || !modern()) return null;
        try {
            if (!Watchdog.startBlocked(c)) return null;
            long until = Watchdog.startBlockedUntil(c);
            if (until <= 0) return null;
            return Ui.trf("The new Home Screen had a problem, so the classic one is back until {time}.", "time", when(until));
        } catch (RuntimeException e) {
            Safe.log("start notice", e);
            return null;
        }
    }

    /** "Try the new Home Screen again": the block and the faults go, and the home starts over in Início on return. */
    static void retryModern(Context c) {
        if (c != null) Watchdog.clearStartBlock(c.getApplicationContext());
        Prefs.homeChanged();
    }

    /** "14:32" today; "qui., 7 de nov., 14:32" on another day. */
    private static String when(long at) {
        Calendar now = Calendar.getInstance(), then = Calendar.getInstance();
        then.setTimeInMillis(at);
        boolean today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        String time = Ui.clock(at);
        return today ? time : Ui.datePattern("format|EEE, MMM d").format(at) + ", " + time;
    }

    // MARK: - What Início can show (worker only)

    private static final class Inventory {
        final List<String[]> apps = new ArrayList<>();     // {package, name}, launchable, not HIDDEN
        final List<String[]> inputs = new ArrayList<>();   // {key, name}: Live TV, then the HDMI inputs
        final List<String> namedInputs = new ArrayList<>(), unnamedInputs = new ArrayList<>();

        Set<String> present() {
            Set<String> keys = new LinkedHashSet<>();
            keys.add(HomeLayout.EXPLORE);
            for (String[] a : apps) keys.add(a[0]);
            for (String[] in : inputs) keys.add(in[0]);
            keys.add(HomeLayout.SETTINGS);
            return keys;
        }
    }

    /** The same apps HomeApps.allApps lists (without their cards) and the inputs HomeApps.allInputs gives. */
    private static Inventory inventory(Context c) {
        Inventory inv = new Inventory();
        Set<String> skip = new HashSet<>(java.util.Arrays.asList(HomeApps.LIVE_TV_APPS));
        skip.add(c.getPackageName());
        Set<String> seen = new HashSet<>();
        for (String[] a : HomeApps.launchable(c)) {
            if (skip.contains(a[0]) || HomeApps.isHidden(a[0]) || !seen.add(a[0])) continue;
            String name = HomeApps.shelfName(a[0]);
            inv.apps.add(new String[] {a[0], name != null ? name : a[1]});
        }
        for (HomeApps.Item it : HomeApps.allInputs(c)) {
            if (it.kind == HomeApps.LIVE_TV) {
                inv.inputs.add(new String[] {HomeLayout.LIVE, it.label});
                continue;
            }
            // As StartModel keys it: the input's id, or its port ("hdmi3") for an id the layout's format can't keep
            String key = HomeLayout.inputKey(it.inputId);
            if (key == null && it.key != null) key = HomeLayout.inputKey(it.key);
            if (key == null || !seen.add(key)) continue;
            inv.inputs.add(new String[] {key, it.label});
            // A name given in the TV's settings or reported over HDMI-CEC; else "HDMI n" (HomeApps.inputs)
            if (("HDMI " + it.number).equals(it.label)) inv.unnamedInputs.add(key);
            else inv.namedInputs.add(key);
        }
        return inv;
    }

    /** Início's seed (SPEC2 §3.1), for a layout that was never saved. */
    private static HomeLayout seed(Context c, Inventory inv) {
        List<String> shelf = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (String[] a : inv.apps) installed.add(a[0]);
        for (String pkg : HomeApps.shelfPackages(c)) if (installed.contains(pkg) && !shelf.contains(pkg)) shelf.add(pkg);
        List<String[]> rest = new ArrayList<>();
        for (String[] a : inv.apps) if (!shelf.contains(a[0])) rest.add(a);
        final Collator collator = Collator.getInstance(Ui.locale());
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(rest, (x, y) -> collator.compare(x[1], y[1]));
        List<String> az = new ArrayList<>();
        for (String[] a : rest) az.add(a[0]);
        return HomeLayout.seed(shelf, inv.namedInputs, az, inv.unnamedInputs);
    }
}
