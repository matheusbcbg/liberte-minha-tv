package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The lists behind the home pages of Ajustes (SPEC3 §4.2, §4.7): the remote buttons and the apps they can open, the
 * shelf and top-button editor, the city search, the languages and the Bluetooth permission. Ported from the classic
 * SettingsView with the statements in their order; only the receivers changed (the host instead of the view). Main
 * thread unless marked; workers run only on Safe.IO and come back through Safe.post.
 */
final class SetLists {
    private SetLists() {}

    // MARK: - Remote buttons (SA:1307-1380)

    /** One label per Prefs.KEYS entry, for the brand of this TV (every brand as the classic view lists them). */
    static String[] keyLabels() {
        if (RemoteKeys.GOOGLE_STREAMER) return new String[] {Ui.tr("YouTube button"), Ui.tr("Netflix button"),
                Ui.tr("Custom button") + " ★"};
        if (RemoteKeys.JVC_SKYWORTH) return new String[] {Ui.tr("Inputs"), Ui.tr("Settings button")};
        if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
        if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
        if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
        if (RemoteKeys.XIAOMI_PATCHWALL_ONLY) return new String[] {Ui.tr("PatchWall button")};
        if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                Ui.tr("Wink button"), Ui.tr("IVI button")};
        if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                Ui.tr("Crunchyroll button"), Ui.tr("Google Play button"), Ui.tr("SonyLIV button")};
        return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                Ui.tr("Prime Video button"), Ui.tr("TCL Channel button"), Ui.tr("Settings button")};
    }

    /** What a remote button can open: "Nothing" (or the brand's default) first, the brand's extras, then the apps A-Z. */
    static final class Apps {
        final List<String> pkgs = new ArrayList<>(), labels = new ArrayList<>();
    }

    /** The last list loadApps made (main thread only); null until the first load ends. */
    private static Apps apps;
    private static boolean appsLoading;
    private static final List<Runnable> appsWaiting = new ArrayList<>();

    /** The last loaded list, or null while the first load runs. */
    static Apps apps() {
        return apps;
    }

    /**
     * Loads the installed TV apps on Safe.IO (HomeApps.launchable), then builds the list on the main thread, keeps it
     * for apps() and runs `done` there. A load already running takes `done` too. `done` may be null.
     */
    static void loadApps(SetHost h, Runnable done) {
        if (done != null) appsWaiting.add(done);
        if (appsLoading) return;
        appsLoading = true;
        final Context app = h.context().getApplicationContext();
        final String own = app.getPackageName();
        Safe.IO.execute(Safe.wrap("settings-apps", () -> {
            List<String[]> found = new ArrayList<>();
            try {
                for (String[] a : HomeApps.launchable(app)) {
                    if (!a[0].equals(own)) found.add(a);
                }
                Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            } catch (RuntimeException e) {
                Safe.log("settings-apps", e);
                found.clear();
            }
            Safe.post(h.handler(), "settings-apps", () -> {
                appsLoading = false;
                apps = build(found);
                List<Runnable> waiting = new ArrayList<>(appsWaiting);
                appsWaiting.clear();
                for (Runnable r : waiting) {
                    try {
                        r.run();
                    } catch (RuntimeException e) {
                        h.fault("settings-apps", e);
                    }
                }
            });
        }));
    }

    /** SA:1337-1380 after the scan: the texts are translated here, on the main thread. */
    private static Apps build(List<String[]> found) {
        Apps a = new Apps();
        a.pkgs.add("");
        // An empty choice leaves the key to the system's receiver.
        a.labels.add(RemoteKeys.GOOGLE_STREAMER ? Ui.tr("Google default")
                : RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
        if (RemoteKeys.JVC_SKYWORTH) {
            a.pkgs.add(Prefs.TV_SHORTCUTS);
            a.labels.add(Ui.tr("Inputs"));
            a.pkgs.add(Prefs.QUICK_SETTINGS);
            a.labels.add(Ui.tr("Quick Settings"));
        }
        if (RemoteKeys.TCL) {
            a.pkgs.add(Prefs.QUICK_PANEL);
            a.labels.add(Ui.tr("Quick Settings"));
            // The Settings button's default, listed even where TV Settings has no launcher entry.
            boolean listed = false;
            for (String[] f : found) listed |= f[0].equals("com.android.tv.settings");
            if (!listed) {
                a.pkgs.add("com.android.tv.settings");
                a.labels.add(Ui.tr("TV Settings"));
            }
        }
        // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
        if (RemoteKeys.XIAOMI) {
            a.pkgs.add(HomeMode.PKG);
            a.labels.add(Ui.APP_SHORT);
        }
        if (RemoteKeys.HISENSE || RemoteKeys.GOOGLE_STREAMER) {
            a.pkgs.add(Prefs.ALL_APPS);
            a.labels.add(Ui.tr("All Apps"));
        }
        for (String[] f : found) {
            a.pkgs.add(f[0]);
            a.labels.add(f[1]);
        }
        return a;
    }

    /** Button `i`'s value: the app's name, "Not installed", or "…" while the list loads (SA:1327-1335). */
    static String keyValue(int i) {
        if (apps == null) return "…";
        int at = apps.pkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
        return at >= 0 ? apps.labels.get(at) : Ui.tr("Not installed");
    }

    /** The picker's choices for a button: {package, name}. Empty while the list loads. */
    static List<String[]> keyChoices() {
        List<String[]> out = new ArrayList<>();
        if (apps == null) return out;
        for (int i = 0; i < apps.pkgs.size(); i++) out.add(new String[] {apps.pkgs.get(i), apps.labels.get(i)});
        return out;
    }

    /** OK in the picker: SA:2380-2386. */
    static void setKeyApp(Context c, int i, String pkg) {
        Prefs.setKeyApp(Prefs.KEYS[i], pkg);
        HomeMode.ensureRemoteService(c, 3_000);
    }

    /** ← → on a button: the next or previous app of the whole list (SA:1414-1420). False while the list loads. */
    static boolean stepKeyApp(Context c, int i, int dir) {
        if (apps == null || apps.pkgs.isEmpty()) return false;
        int n = apps.pkgs.size();
        int at = apps.pkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
        int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
        Prefs.setKeyApp(Prefs.KEYS[i], apps.pkgs.get(next));
        HomeMode.ensureRemoteService(c, 3_000);   // all on Nothing: the service goes off
        return true;
    }

    // MARK: - Shelf and top buttons editor (SA:2089-2149)

    /**
     * The shelf editor's model (also the top buttons', with `top`): what it can hold, the selected ones first in their
     * order, then the rest in the order they came. Every change is saved at once (Prefs.setShelf / setTopButtons).
     */
    static final class Shelf {
        final boolean top;
        /** {key, name}: selected first, in order, then the others. */
        final List<String[]> rows = new ArrayList<>();
        /** The selected keys, in order. */
        final List<String> order = new ArrayList<>();
        private final int max;

        Shelf(Context c, boolean top) {
            this.top = top;
            if (top) {
                order.addAll(HomeApps.topButtonKeys());
                rebuild(HomeApps.topCandidates());
                max = Integer.MAX_VALUE;
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = c.getPackageManager();
                for (String pkg : HomeApps.shelfPackages(c)) if (HomeApps.launchIntent(pm, pkg) != null) order.add(pkg);
                rebuild(HomeApps.shelfCandidates(c));
                max = HomeApps.maxShelf(c);
            }
        }

        /** The 0-based place of `key`, or -1 when it isn't selected. */
        int place(String key) {
            return order.indexOf(key);
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuild(List<String[]> candidates) {
            rows.clear();
            for (String pkg : order) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { rows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!order.contains(cand[0])) rows.add(cand);
        }

        /** Same rows after a change, then saved (SA:2120-2130). */
        private void changed() {
            List<String[]> all = new ArrayList<>(rows);
            rebuild(all);
            if (top) Prefs.setTopButtons(order); else Prefs.setShelf(order);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. False when the shelf is full (low RAM). */
        boolean toggle(String key) {
            if (order.contains(key)) order.remove(key);
            else if (top || order.size() < max) order.add(key);
            else return false;
            changed();
            return true;
        }

        /** ← → : moves a selected app one place earlier or later; false when it can't move. */
        boolean move(String key, int dir) {
            int at = order.indexOf(key), to = at + dir;
            if (at < 0 || to < 0 || to >= order.size()) return false;
            order.remove(at);
            order.add(to, key);
            changed();
            return true;
        }
    }

    /** The apps the shelf shows (installed ones only), as the editor counts them: for the Shelf row's value. */
    static int shelfCount(Context c) {
        PackageManager pm = c.getPackageManager();
        int n = 0;
        for (String pkg : HomeApps.shelfPackages(c)) if (HomeApps.launchIntent(pm, pkg) != null) n++;
        return n;
    }

    // MARK: - City (SA:1498-1522, 1924-1953)

    /** "Searching…" while a search runs, else "". */
    private static String cityStatus = "";

    static String cityStatus() {
        return cityStatus;
    }

    /**
     * OK on Cidade: the text sheet, then the search on Safe.IO; results push `pick.city`, whose choice saves the city
     * and pops back to the opener. None or an error: a toast (never the exception's text).
     */
    static void askCity(SetHost h, String about) {
        if (!cityStatus.isEmpty()) return;
        h.askText(Ui.tr("City"), Ui.tr("Type the city's name"), Ui.tr("Search"), false, "", name -> searchCity(h, name, about));
    }

    private static void searchCity(SetHost h, String name, String about) {
        if (name == null || name.trim().isEmpty()) return;
        cityStatus = Ui.tr("Searching…");
        h.refresh();
        Safe.IO.execute(Safe.wrap("settings-city", () -> {
            List<String[]> found;
            boolean failed = false;
            try {
                found = HomeData.findCity(name);
            } catch (Exception e) {
                found = new ArrayList<>();
                failed = true;
                Safe.log("settings: city search", e);
            }
            final List<String[]> result = found;
            final boolean error = failed;
            Safe.post(h.handler(), "settings-city", () -> {
                try {
                    cityStatus = "";
                    if (!h.running()) return;
                    if (error) {
                        h.toast(Ui.tr("Couldn't search now."), true);
                    } else if (result.isEmpty()) {
                        h.toast(Ui.tr("No city with that name."), true);
                    } else {
                        List<String[]> choices = new ArrayList<>();
                        for (int i = 0; i < result.size(); i++) choices.add(new String[] {String.valueOf(i), result.get(i)[0]});
                        h.push("pick.city", host -> SetPage.picker(host, "pick.city", Ui.tr("Pick the city"), "pin", about,
                                choices, null, key -> {
                                    String[] city = result.get(Integer.parseInt(key));
                                    Prefs.setCity(city[1], city[2], city[3]);
                                    HomeData.get(host.context()).settingsChanged();
                                }));
                    }
                    h.refresh();
                } catch (RuntimeException e) {
                    h.fault("settings-city", e);
                }
            });
        }));
    }

    // MARK: - Languages (SA:1131)

    /** "auto", then English and every language with a catalogue. */
    static String[] languageChoices() {
        String[] all = Ui.languages();
        String[] v = new String[all.length + 1];
        v[0] = "auto";
        System.arraycopy(all, 0, v, 1, all.length);
        return v;
    }

    /** "Automatic", or the language's own name. */
    static String languageLabel(String code) {
        return "auto".equals(code) ? Ui.tr("Automatic") : Ui.languageName(code);
    }

    // MARK: - Bluetooth (SA:1300-1305, SPEC3 §3.8)

    private static final String BLUETOOTH = "android.permission.BLUETOOTH_CONNECT";

    /** Android 12 and newer only let an app see Bluetooth connections with this permission. */
    static boolean needsBluetooth(Context c) {
        return android.os.Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(BLUETOOTH) != PackageManager.PERMISSION_GRANTED;
    }

    /** Connection icons turned on: where the permission is missing, a toast says what comes, and 600 ms later Android asks. */
    static void askBluetooth(SetHost h) {
        if (!needsBluetooth(h.context())) return;
        h.toast(Ui.tr("The TV will ask to see nearby devices: choose Allow."), false);
        h.handler().postDelayed(() -> {
            try {
                if (h.running()) requestBluetooth(h.activity());
            } catch (RuntimeException e) {
                h.fault("settings-bluetooth", e);
            }
        }, 600);
    }

    /** SA:1300-1305. */
    static void requestBluetooth(android.app.Activity activity) {
        if (activity == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31
                && activity.checkSelfPermission(BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] {BLUETOOTH}, 1);
        }
    }
}
