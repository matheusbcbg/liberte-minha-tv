package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Brazil's streaming services: TMDB's provider ids and names → the TV app that plays them. Plain Java (tested on the
 * JDK). Keys are HomeApps.SHELF's, so Home Settings' Picks from switches (Prefs.serviceOff) apply here too.
 * Ids drift and names change, so a provider is matched by id first, then by name (whole words, after plain()).
 */
final class ProviderTable {
    private ProviderTable() {}

    static final class Service {
        final String key, label;
        final String[] packages;
        final int[] ids;
        final int color;
        /** Words that name it on TMDB and Watchmode, after plain() (whole words). */
        final String[] words;
        /** Its store's ids (rent or buy only): matched, never streamable. */
        final int[] storeIds;

        Service(String key, String label, String[] packages, int[] ids, int color, String[] words, int[] storeIds) {
            this.key = key;
            this.label = label;
            this.packages = packages;
            this.ids = ids;
            this.color = color;
            this.words = words;
            this.storeIds = storeIds;
        }
    }

    private static String[] s(String... v) { return v; }
    private static int[] n(int... v) { return v; }

    private static final int OTHER = 0xFF2C2C2E;

    /**
     * In row order: Netflix, Prime Video, Max, Disney+, Globoplay, Apple TV, Paramount+, Claro tv+, then the rest.
     * Packages checked on the TCL (adb pm list packages, 2026-11): netflix, prime (livingroom), max (wbd), disney,
     * globoplay, appletv, paramount (com.cbs.ca), claro (br.com.claro.now.smarttvclient), youtube. MUBI, Looke and
     * Pluto TV aren't installed there: their packages are the Play Store's, and the app's label matches them anyway.
     */
    static final Service[] TABLE = {
        new Service("netflix", "Netflix", s("com.netflix.ninja"), n(8, 1796), 0xFFE50914, s("netflix"), n()),
        new Service("prime", "Prime Video", s("com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod"),
                n(119, 9, 2100), 0xFF1A98FF, s("prime video", "amazon prime"), n(10)),
        new Service("max", "Max", s("com.wbd.stream", "com.hbo.hbonow"), n(1899, 384), 0xFF6C3BE0, s("max"), n()),
        new Service("disney", "Disney+", s("com.disney.disneyplus"), n(337), 0xFF1838C8, s("disney"), n()),
        new Service("globoplay", "Globoplay", s("com.globo.globotv"), n(307), 0xFFFF5A1F, s("globoplay"), n()),
        new Service("appletv", "Apple TV", s("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv", "com.apple.atve.amazon.appletv"),
                n(350), 0xFFB8BCC6, s("apple tv"), n(2)),
        new Service("paramount", "Paramount+", s("com.cbs.ott", "com.cbs.ca"), n(531, 2303), 0xFF0064FF, s("paramount"), n()),
        new Service("claro", "Claro tv+", s("br.com.claro.now.smarttvclient"), n(167), 0xFFDA291C, s("claro"), n()),
        new Service("crunchyroll", "Crunchyroll", s("com.crunchyroll.crunchyroid"), n(283), 0xFFF47521, s("crunchyroll"), n()),
        new Service("youtube", "YouTube", s("com.google.android.youtube.tv"), n(188, 192), 0xFFFF0033, s("youtube"), n()),
        new Service("mubi", "MUBI", s("com.mubi"), n(11), OTHER, s("mubi"), n()),
        new Service("looke", "Looke", s(), n(47), OTHER, s("looke"), n()),
        new Service("pluto", "Pluto TV", s("tv.pluto.android"), n(300), OTHER, s("pluto tv"), n()),
    };

    /**
     * Services whose TMDB (JustWatch) offers in Brazil aren't to be trusted: Claro tv+ is listed as "subscription" for
     * almost every new film (its pay-TV channels' on-demand, not the app's catalogue), and its rentals often aren't in
     * the app either. Their offers are left out of Explore, the picks and Where to watch; the app itself stays on Início.
     */
    static final java.util.Set<String> UNRELIABLE = java.util.Collections.singleton("claro");

    /** This TMDB offer is one of UNRELIABLE's (by id or name: "Claro tv+", "Claro video", "Claro Now"). */
    static boolean unreliable(int providerId, String name) {
        Service s = match(providerId, name);
        if (s != null && UNRELIABLE.contains(s.key)) return true;
        String p = " " + plain(name == null ? "" : name) + " ";
        return p.contains(" claro ");
    }

    /** TABLE's row by key, or null. */
    static Service byKey(String key) {
        if (key == null) return null;
        for (Service s : TABLE) if (s.key.equals(key)) return s;
        return null;
    }

    /**
     * The service of a TMDB provider. A subscription resold inside another app ("HBO Max Amazon Channel", "Paramount+
     * Apple TV Channel") is that app's: Prime Video or Apple TV; any other " Channel" is no app's (null). Then by id,
     * then by name ("Max" only as a whole word: not Cinemax).
     */
    static Service match(int providerId, String name) {
        String p = name == null ? "" : " " + plain(name) + " ";
        if (p.contains(" channel ")) {
            if (p.contains(" amazon channel ")) return byKey("prime");
            if (p.contains(" apple tv channel ") || p.contains(" apple tv plus channel ")) return byKey("appletv");
            return null;
        }
        if (providerId > 0) {
            for (Service s : TABLE) {
                for (int id : s.ids) if (id == providerId) return s;
                for (int id : s.storeIds) if (id == providerId) return s;
            }
        }
        if (p.trim().isEmpty()) return null;
        for (Service s : TABLE) for (String w : s.words) if (p.contains(" " + w + " ")) return s;
        return null;
    }

    /**
     * Whether this offer plays in the service's app: a subscription, free or with ads (ExploreModel FLATRATE, FREE,
     * ADS), and not the service's store (Apple TV: 350 streams, 2 is the store; Prime Video: 10 is the store).
     */
    static boolean streamable(Service s, int providerId, int type) {
        if (s == null || type < ExploreModel.FLATRATE || type > ExploreModel.ADS) return false;
        for (int id : s.storeIds) if (id == providerId) return false;
        return !"appletv".equals(s.key) || providerId == 350 || providerId <= 0;
    }

    /** "Claro vídeo" and "CLARO VIDEO", "Disney+" and "Disney Plus": without case, accents or punctuation (as Recs.plain). */
    static String plain(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace("+", " plus").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
