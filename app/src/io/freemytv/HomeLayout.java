package io.freemytv;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Início's one ordered list (SPEC2 §3.1): every app, input, folder and our own tile, the first COLUMNS of them the Dock.
 * Plain Java (java.util only), so tests run on the JDK. Not thread-safe: one owner at a time (StartModel's loader
 * builds it, then hands a copy() to the main thread).
 * <ul>
 * <li>{@link #saved}: the order, including keys absent right now (an uninstalled app, an unplugged input), so a
 * reinstall finds its slot again. Never holds a hidden key or an app that is inside a folder.</li>
 * <li>{@link #folders}: "folder:n" → name and apps (packages and inputs only, one level).</li>
 * <li>{@link #hidden}: keys never shown (EXPLORE and SETTINGS can't be hidden).</li>
 * </ul>
 * Saved as plain lines ({@link #serialize}): "v1", then "L:" the order, "F&lt;n&gt;:" name|apps for each folder,
 * "H:" the hidden keys and "R:" the reset stamp; every key and name percent-escaped for % , | : and line breaks.
 */
final class HomeLayout {
    static final int COLUMNS = 6;
    static final long EDIT_REPEAT_MS = 120;
    static final String EXPLORE = "explore", LIVE = "live", SETTINGS = "settings", INPUT = "input:", FOLDER = "folder:";
    /** Absent keys kept in the saved order; past this many, the ones lowest in the list go. */
    static final int MAX_ABSENT = 64;
    /** A folder's name, at most (the rename dialog's limit too). */
    static final int MAX_NAME = 24;

    private static final String VERSION = "v1";

    static final class Folder {
        final String key;
        String name;
        final List<String> apps;

        Folder(String key, String name, List<String> apps) {
            this.key = key;
            this.name = name;
            this.apps = apps;
        }
    }

    final List<String> saved = new ArrayList<>();                       // includes absent keys
    final LinkedHashMap<String, Folder> folders = new LinkedHashMap<>();
    final LinkedHashSet<String> hidden = new LinkedHashSet<>();
    long resetStamp;
    /** The present keys of the last visible()/reconcile(): moveOut() answers a visible index with them. Not saved. */
    private Set<String> lastPresent;

    // MARK: - Keys

    /** "input:" + the TvInputInfo id, or null when the id is missing or holds a character the format can't keep. */
    static String inputKey(String inputId) {
        if (inputId == null || inputId.isEmpty()) return null;
        for (int i = 0; i < inputId.length(); i++) {
            char ch = inputId.charAt(i);
            if (ch == ',' || ch == '|' || ch == '%' || ch == '\n' || ch == '\r') return null;
        }
        return INPUT + inputId;
    }

    static boolean isFolder(String key) {
        return key != null && key.startsWith(FOLDER);
    }

    static boolean isInput(String key) {
        return key != null && key.startsWith(INPUT);
    }

    /** An app's package (not explore, live, settings, an input or a folder). */
    static boolean isPackage(String key) {
        return key != null && !key.isEmpty() && !EXPLORE.equals(key) && !LIVE.equals(key) && !SETTINGS.equals(key)
                && !isInput(key) && !isFolder(key);
    }

    /** What a folder may hold: apps and inputs. */
    static boolean foldable(String key) {
        return isPackage(key) || isInput(key);
    }

    // MARK: - Text

    static HomeLayout parse(String s) {
        if (s == null) return null;
        try {
            String[] lines = s.split("\n", -1);
            if (lines.length == 0 || !VERSION.equals(lines[0].trim())) return null;
            HomeLayout l = new HomeLayout();
            boolean haveList = false;
            Set<String> seen = new HashSet<>();
            for (int n = 1; n < lines.length; n++) {
                String line = lines[n];
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                if (line.isEmpty()) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) return null;
                String tag = line.substring(0, colon), rest = line.substring(colon + 1);
                if (tag.equals("L")) {
                    if (haveList) return null;
                    haveList = true;
                    for (String k : split(rest, ',')) {
                        String key = unescape(k);
                        if (!key.isEmpty() && seen.add(key)) l.saved.add(key);
                    }
                } else if (tag.charAt(0) == 'F' && tag.length() > 1) {
                    int number = Integer.parseInt(tag.substring(1));
                    if (number < 1) return null;
                    int bar = rest.indexOf('|');
                    if (bar < 0) return null;
                    String key = FOLDER + number;
                    if (l.folders.containsKey(key)) return null;
                    List<String> apps = new ArrayList<>();
                    for (String a : split(rest.substring(bar + 1), ',')) {
                        String app = unescape(a);
                        if (foldable(app) && !apps.contains(app)) apps.add(app);
                    }
                    l.folders.put(key, new Folder(key, name(unescape(rest.substring(0, bar)), String.valueOf(number)), apps));
                } else if (tag.equals("H")) {
                    for (String k : split(rest, ',')) {
                        String key = unescape(k);
                        if (!key.isEmpty()) l.hidden.add(key);
                    }
                } else if (tag.equals("R")) {
                    l.resetStamp = Long.parseLong(rest.trim());
                }
                // Any other tag: a line a later v1 added; ignored.
            }
            if (!haveList) return null;
            l.normalize();
            return l;
        } catch (RuntimeException e) {   // a bad number or escape: garbage, the seed takes over
            return null;
        }
    }

    String serialize() {
        StringBuilder b = new StringBuilder(64 + saved.size() * 32).append(VERSION).append('\n').append("L:");
        join(b, saved);
        for (Folder f : folders.values()) {
            b.append("\nF").append(f.key.substring(FOLDER.length())).append(':').append(escape(f.name)).append('|');
            join(b, f.apps);
        }
        b.append("\nH:");
        join(b, hidden);
        return b.append("\nR:").append(resetStamp).toString();
    }

    private static void join(StringBuilder b, Collection<String> keys) {
        boolean first = true;
        for (String k : keys) {
            if (!first) b.append(',');
            b.append(escape(k));
            first = false;
        }
    }

    private static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            String rep = ch == '%' ? "%25" : ch == ',' ? "%2C" : ch == '|' ? "%7C" : ch == ':' ? "%3A"
                    : ch == '\n' ? "%0A" : ch == '\r' ? "%0D" : null;
            if (rep == null) {
                if (b != null) b.append(ch);
                continue;
            }
            if (b == null) b = new StringBuilder(s.length() + 8).append(s, 0, i);
            b.append(rep);
        }
        return b == null ? s : b.toString();
    }

    /** The reverse of escape(); throws IllegalArgumentException on a broken %XX. */
    static String unescape(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '%') {
                b.append(ch);
                continue;
            }
            if (i + 2 >= s.length()) throw new IllegalArgumentException("escape");
            int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("escape");
            b.append((char) (hi * 16 + lo));
            i += 2;
        }
        return b.toString();
    }

    /** One home for every key: a folder's apps leave the order, hidden keys leave both, folders missing either side go. */
    private void normalize() {
        hidden.remove(EXPLORE);
        hidden.remove(SETTINGS);
        for (String k : new ArrayList<>(hidden)) if (isFolder(k)) hidden.remove(k);
        Set<String> inFolders = new HashSet<>();
        for (Folder f : folders.values()) {
            f.apps.removeAll(hidden);
            for (int i = 0; i < f.apps.size(); i++) {
                if (!inFolders.add(f.apps.get(i))) f.apps.remove(i--);   // in an earlier folder already
            }
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < saved.size(); i++) {
            String k = saved.get(i);
            boolean drop = !seen.add(k) || hidden.contains(k) || inFolders.contains(k) || (isFolder(k) && !folders.containsKey(k));
            if (drop) saved.remove(i--);
        }
        for (Folder f : folders.values()) if (!saved.contains(f.key)) addNearEnd(saved, f.key);
    }

    // MARK: - Building

    static HomeLayout seed(List<String> shelf, List<String> namedInputs, List<String> appsAz, List<String> unnamedInputs) {
        HomeLayout l = new HomeLayout();
        LinkedHashSet<String> order = new LinkedHashSet<>();
        order.add(EXPLORE);
        addAll(order, shelf, true);
        order.add(LIVE);
        addAll(order, namedInputs, false);
        addAll(order, appsAz, true);
        addAll(order, unnamedInputs, false);
        order.add(SETTINGS);
        l.saved.addAll(order);
        return l;
    }

    private static void addAll(Set<String> order, List<String> keys, boolean packages) {
        if (keys == null) return;
        for (String k : keys) if (packages ? isPackage(k) : isInput(k)) order.add(k);
    }

    HomeLayout copy() {
        HomeLayout l = new HomeLayout();
        l.saved.addAll(saved);
        for (Folder f : folders.values()) l.folders.put(f.key, new Folder(f.key, f.name, new ArrayList<>(f.apps)));
        l.hidden.addAll(hidden);
        l.resetStamp = resetStamp;
        l.lastPresent = lastPresent;
        return l;
    }

    // MARK: - What shows

    /** Present, not hidden, folders collapsed, in saved order; then present keys never placed, before SETTINGS. */
    List<String> visible(Collection<String> present) {
        Set<String> pres = present == null ? Collections.<String>emptySet() : new HashSet<>(present);
        lastPresent = pres;
        Set<String> inFolders = folderApps();
        List<String> out = new ArrayList<>(saved.size() + 8);
        Set<String> seen = new HashSet<>();
        for (String k : saved) {
            if (isFolder(k)) {
                Folder f = folders.get(k);
                if (f != null && presentApps(f, pres) > 0 && seen.add(k)) out.add(k);
            } else if (pres.contains(k) && !hidden.contains(k) && !inFolders.contains(k) && seen.add(k)) {
                out.add(k);
            }
        }
        List<String> extra = new ArrayList<>();
        if (present != null) {
            for (String k : present) {
                if (k == null || isFolder(k) || hidden.contains(k) || inFolders.contains(k) || !seen.add(k)) continue;
                extra.add(k);
            }
        }
        if (!extra.isEmpty()) {
            int at = !out.isEmpty() && SETTINGS.equals(out.get(out.size() - 1)) ? out.size() - 1 : out.size();
            out.addAll(at, extra);
        }
        return out;
    }

    /** A folder's apps that are present, in order. */
    List<String> folderVisible(String folder, Collection<String> present) {
        List<String> out = new ArrayList<>();
        Folder f = folders.get(folder);
        if (f == null) return out;
        Set<String> pres = present == null ? Collections.<String>emptySet() : new HashSet<>(present);
        for (String k : f.apps) if (pres.contains(k) && !hidden.contains(k)) out.add(k);
        return out;
    }

    /** The folder holding key, or null. */
    Folder folderOf(String key) {
        if (key == null) return null;
        for (Folder f : folders.values()) if (f.apps.contains(key)) return f;
        return null;
    }

    private Set<String> folderApps() {
        Set<String> s = new HashSet<>();
        for (Folder f : folders.values()) s.addAll(f.apps);
        return s;
    }

    private int presentApps(Folder f, Set<String> pres) {
        int n = 0;
        for (String k : f.apps) if (pres.contains(k) && !hidden.contains(k)) n++;
        return n;
    }

    /**
     * Brings the saved order in line with what is installed: folders with fewer than 2 present apps dissolve in place
     * (their absent apps keep the slot), EXPLORE and SETTINGS come back if they went, new keys are placed before
     * SETTINGS (in `present`'s order), and past MAX_ABSENT absent keys the lowest go. True if anything changed.
     */
    boolean reconcile(Collection<String> present) {
        String before = serialize();
        Set<String> pres = present == null ? Collections.<String>emptySet() : new HashSet<>(present);
        normalize();
        for (Folder f : new ArrayList<>(folders.values())) if (presentApps(f, pres) <= 1) dissolve(f.key);
        if (pres.contains(EXPLORE) && !saved.contains(EXPLORE)) saved.add(0, EXPLORE);
        if (pres.contains(SETTINGS) && !saved.contains(SETTINGS)) saved.add(SETTINGS);
        Set<String> placed = new HashSet<>(saved);
        placed.addAll(folderApps());
        if (present != null) {
            for (String k : present) {
                if (k == null || isFolder(k) || hidden.contains(k) || !placed.add(k)) continue;
                addNearEnd(saved, k);
            }
        }
        int absent = 0;
        for (String k : saved) if (!isFolder(k) && !pres.contains(k)) absent++;
        for (int i = saved.size() - 1; i >= 0 && absent > MAX_ABSENT; i--) {
            String k = saved.get(i);
            if (isFolder(k) || pres.contains(k)) continue;
            saved.remove(i);
            absent--;
        }
        lastPresent = pres;
        return !before.equals(serialize());
    }

    /** The shown order after a move: VisibleOrder.replace semantics (absent and hidden keys keep their slots). */
    void setVisible(List<String> visible) {
        if (visible == null) return;
        Set<String> inFolders = folderApps();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String k : visible) {
            if (k == null || hidden.contains(k) || inFolders.contains(k)) continue;
            if (isFolder(k) && !folders.containsKey(k)) continue;
            wanted.add(k);
        }
        List<String> ordered = new ArrayList<>(wanted);
        List<String> out = new ArrayList<>(saved);
        int next = 0;
        for (int slot = 0; slot < out.size() && next < ordered.size(); slot++) {
            if (wanted.contains(out.get(slot))) out.set(slot, ordered.get(next++));
        }
        while (next < ordered.size()) out.add(ordered.get(next++));
        saved.clear();
        saved.addAll(out);
    }

    // MARK: - Grid maths

    static int row(int i) {
        return i / COLUMNS;
    }

    static int col(int i) {
        return i % COLUMNS;
    }

    /** A new list with the item at `from` moved to the final index `to` (clamped). */
    static List<String> move(List<String> visible, int from, int to) {
        List<String> out = new ArrayList<>(visible);
        int n = out.size();
        if (from < 0 || from >= n) return out;
        int t = Math.max(0, Math.min(n - 1, to));
        if (t == from) return out;
        String k = out.remove(from);
        out.add(t, k);
        return out;
    }

    /**
     * Edit mode (§3.8): ←/→ one slot in list order (wrapping across rows); ↑/↓ one row in the same column; ↓ into a
     * shorter last row lands on its last item; ↑ from the Dock and ↓ from the last row stay.
     */
    static int step(int i, int dx, int dy, int n, int columns) {
        if (n <= 0) return 0;
        int cols = Math.max(1, columns);
        int at = Math.max(0, Math.min(n - 1, i));
        if (dx != 0) return Math.max(0, Math.min(n - 1, at + (dx > 0 ? 1 : -1)));
        if (dy < 0) return at < cols ? at : at - cols;
        if (dy > 0) {
            int j = at + cols;
            if (j < n) return j;
            return at / cols < (n - 1) / cols ? n - 1 : at;
        }
        return at;
    }

    /**
     * Focus (§3.2): ←/→ within the row (the ends bump: i comes back); ↑ the row above, -1 from the Dock (leave upward);
     * ↓ the row below at the same column, or the last item of a shorter last row; the last row bumps.
     */
    static int nav(int i, int dx, int dy, int n, int columns) {
        if (n <= 0) return dy < 0 ? -1 : i;
        int cols = Math.max(1, columns);
        int at = Math.max(0, Math.min(n - 1, i));
        if (dx < 0) return at % cols == 0 ? at : at - 1;
        if (dx > 0) return at % cols == cols - 1 || at + 1 >= n ? at : at + 1;
        if (dy < 0) return at < cols ? -1 : at - cols;
        if (dy > 0) {
            int j = at + cols;
            if (j < n) return j;
            return at / cols < (n - 1) / cols ? n - 1 : at;
        }
        return at;
    }

    /** "Move to the top row": the Dock's last slot (or the end of a short list). */
    static int topTarget(int n) {
        return Math.max(0, Math.min(COLUMNS - 1, n - 1));
    }

    /** "Remove from the top row": the first slot after the Dock (the first grid item rises into the Dock). */
    static int offTopTarget(int n) {
        return Math.max(0, Math.min(COLUMNS, n - 1));
    }

    // MARK: - Folders

    /** A new folder holding key, in key's slot (after key's folder when it was in one); its key, or null if refused. */
    String newFolder(String key, String name) {
        if (!foldable(key) || hidden.contains(key)) return null;
        int number = 1;
        while (folders.containsKey(FOLDER + number)) number++;
        String fk = FOLDER + number;
        List<String> apps = new ArrayList<>();
        apps.add(key);
        Folder f = new Folder(fk, name(name, String.valueOf(number)), apps);
        Folder from = folderOf(key);
        if (from != null) {
            from.apps.remove(key);
            int at = saved.indexOf(from.key);
            if (from.apps.isEmpty()) {
                folders.remove(from.key);
                if (at >= 0) saved.set(at, fk);
                else addNearEnd(saved, fk);
            } else if (at >= 0) {
                saved.add(at + 1, fk);
            } else {
                addNearEnd(saved, fk);
            }
        } else {
            int at = saved.indexOf(key);
            if (at >= 0) saved.set(at, fk);
            else addNearEnd(saved, fk);
        }
        folders.put(fk, f);
        return fk;
    }

    /** Appends key to the folder (out of the order, or out of the folder it was in). */
    void moveToFolder(String key, String folder) {
        Folder f = folders.get(folder);
        if (f == null || !foldable(key) || hidden.contains(key) || f.apps.contains(key)) return;
        Folder from = folderOf(key);
        if (from != null) {
            from.apps.remove(key);
            if (from.apps.isEmpty()) removeFolder(from.key);
        }
        saved.remove(key);
        f.apps.add(key);
    }

    /**
     * Takes key out of its folder, right after the folder in the order (in its place, when it was the last app). The new
     * visible index, measured with the present keys of the last visible() or reconcile(); -1 if unknown.
     */
    int moveOut(String key) {
        Folder f = folderOf(key);
        if (f != null) {
            f.apps.remove(key);
            int at = saved.indexOf(f.key);
            if (f.apps.isEmpty()) {
                folders.remove(f.key);
                if (at >= 0) saved.set(at, key);
                else addNearEnd(saved, key);
            } else if (at >= 0) {
                saved.add(at + 1, key);
            } else {
                addNearEnd(saved, key);
            }
        }
        if (lastPresent == null) {
            // No present keys known: count the saved order's folders and keys that aren't hidden.
            int i = 0;
            for (String k : saved) {
                if (k.equals(key)) return i;
                if (!hidden.contains(k)) i++;
            }
            return -1;
        }
        return visible(lastPresent).indexOf(key);
    }

    /** The folder's apps take its place, in order. */
    void dissolve(String folder) {
        Folder f = folders.remove(folder);
        if (f == null) return;
        Set<String> already = new HashSet<>(saved);
        List<String> apps = new ArrayList<>();
        for (String k : f.apps) if (!hidden.contains(k) && already.add(k)) apps.add(k);
        int at = saved.indexOf(folder);
        if (at >= 0) {
            saved.remove(at);
            saved.addAll(at, apps);
        } else {
            for (String k : apps) addNearEnd(saved, k);
        }
    }

    /** An empty name keeps the old one. */
    void rename(String folder, String name) {
        Folder f = folders.get(folder);
        if (f == null) return;
        f.name = name(name, f.name);
    }

    /** pattern ("Pasta {n}", already translated) with the smallest n ≥ 1 that no folder and nothing in `taken` uses. */
    String nextFolderName(String pattern, Collection<String> taken) {
        Set<String> used = new HashSet<>();
        if (taken != null) for (String t : taken) if (t != null) used.add(t.trim().toLowerCase(Locale.ROOT));
        for (Folder f : folders.values()) if (f.name != null) used.add(f.name.trim().toLowerCase(Locale.ROOT));
        String p = pattern == null || pattern.trim().isEmpty() ? "{n}" : pattern;
        for (int n = 1; ; n++) {
            String s = p.contains("{n}") ? p.replace("{n}", String.valueOf(n)) : n == 1 ? p : p + " " + n;
            if (!used.contains(s.trim().toLowerCase(Locale.ROOT))) return s;
        }
    }

    private void removeFolder(String key) {
        folders.remove(key);
        saved.remove(key);
    }

    private static String name(String name, String fallback) {
        String n = name == null ? "" : name.replace('\n', ' ').replace('\r', ' ').trim();
        if (n.isEmpty()) return fallback;
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME).trim() : n;
    }

    // MARK: - Hidden

    /** Out of the order and its folder; EXPLORE, SETTINGS and folders refuse. */
    void hide(String key) {
        if (key == null || key.isEmpty() || EXPLORE.equals(key) || SETTINGS.equals(key) || isFolder(key)) return;
        Folder f = folderOf(key);
        if (f != null) {
            f.apps.remove(key);
            if (f.apps.isEmpty()) removeFolder(f.key);
        }
        saved.remove(key);
        hidden.add(key);
    }

    /** Back at the end of the order, before SETTINGS. */
    void unhide(String key) {
        if (key == null || !hidden.remove(key)) return;
        if (!saved.contains(key) && folderOf(key) == null) addNearEnd(saved, key);
    }

    /** At the end, before SETTINGS when it is last (a SETTINGS the person moved elsewhere stays where it is). */
    private static void addNearEnd(List<String> list, String key) {
        int at = !list.isEmpty() && SETTINGS.equals(list.get(list.size() - 1)) ? list.size() - 1 : list.size();
        list.add(at, key);
    }

    // MARK: - The classic shelf's mirror

    /** The Dock's package keys only (the mirrored "shelf"): no explore, live, inputs, settings or folders. */
    List<String> dockPackages(List<String> visible) {
        List<String> out = new ArrayList<>();
        if (visible == null) return out;
        for (int i = 0; i < Math.min(COLUMNS, visible.size()); i++) {
            String k = visible.get(i);
            if (isPackage(k)) out.add(k);
        }
        return out;
    }
}
