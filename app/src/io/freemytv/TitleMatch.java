package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Whether a TMDB search result is the Watch Next title (SPEC2 §3.5): an exact title after plain(), the year within one
 * when known; or, for the first result only, a known year within one and a popular title. Plain Java (tested on the JDK).
 */
final class TitleMatch {
    private TitleMatch() {}

    /** The least popularity TMDB gives a title the first result may be taken on its year alone. */
    static final double POPULAR = 10;

    /** Recs.plain's rule, copied (the tests can't load Recs): no case, accents or punctuation; "+" reads " plus". */
    static String plain(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace("+", " plus").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** "and" in the languages apps write titles in: "Rick & Morty" (Max) is TMDB's "Rick and Morty". */
    private static final java.util.Set<String> AND = new java.util.HashSet<>(java.util.Arrays.asList(
            "and", "e", "y", "et", "und", "en", "og", "och", "i", "a"));

    /** plain() without "&" and the words for "and": only for comparing, never a key. */
    static String core(String s) {
        String p = plain(s);
        if (p.isEmpty()) return p;
        StringBuilder b = new StringBuilder(p.length());
        for (String w : p.split(" ")) {
            if (AND.contains(w)) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w);
        }
        return b.length() == 0 ? p : b.toString();
    }

    /** WatchMatch's cache key: the app, a film or a show, and the plain title. */
    static String key(String pkg, String title, boolean movie) {
        return (movie ? "m|" : "t|") + (pkg == null ? "" : pkg) + "|" + plain(title);
    }

    /** "Rick and Morty: S7 E3", "Rick and Morty - Temporada 7", "Rick and Morty (2013)": what follows the show's name. */
    private static final java.util.regex.Pattern DECORATION = java.util.regex.Pattern.compile(
            "(?iu)\\s*(?:[-–—:|•]\\s*)?(?:\\b(?:s|t)\\s?\\d+\\s*[:.]?\\s*e\\s?\\d+|\\b(?:season|temporada|temp\\.|staffel|saison)\\s*\\d+"
                    + "|\\b(?:episode|episódio|episodio|ep\\.?)\\s*\\d+|\\(\\s*\\d{4}\\s*\\)).*$");

    /**
     * The searches worth trying for a Watch Next title, best first: the title as given, then without the season or
     * episode an app may have put in it, then the part before a ":" or " - " (a show's name before an episode's).
     * At most 3, no two the same after plain().
     */
    static java.util.List<String> queries(String title) {
        java.util.List<String> out = new java.util.ArrayList<>(3);
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (title == null) return out;
        String t = title.trim();
        add(out, seen, t);
        if (t.contains("&")) add(out, seen, t.replace("&", "and").replaceAll("\\s+", " "));
        add(out, seen, DECORATION.matcher(t).replaceFirst("").trim());
        for (String sep : new String[] {": ", " - ", " – ", " | "}) {
            int i = t.indexOf(sep);
            if (i > 0) {
                add(out, seen, t.substring(0, i).trim());
                break;
            }
        }
        while (out.size() > 3) out.remove(out.size() - 1);
        return out;
    }

    private static void add(java.util.List<String> out, java.util.Set<String> seen, String q) {
        String p = plain(q);
        if (p.length() >= 2 && seen.add(p)) out.add(q);
    }

    /**
     * wanted / wantYear: the Watch Next title and its year (0 unknown; shows always 0). title / original / year /
     * popularity: the result's. first: it is the search's first result.
     */
    static boolean accept(String wanted, int wantYear, String title, String original, int year, double popularity, boolean first) {
        String w = plain(wanted);
        if (w.isEmpty()) return false;
        boolean near = wantYear > 0 && year > 0 && Math.abs(year - wantYear) <= 1;
        boolean same = w.equals(plain(title)) || w.equals(plain(original))
                || core(wanted).equals(core(title)) || core(wanted).equals(core(original));
        if (same && (wantYear <= 0 || near)) return true;
        return first && near && popularity >= POPULAR;
    }
}
