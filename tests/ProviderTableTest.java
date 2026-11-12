package io.freemytv;

public final class ProviderTableTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static String key(int id, String name) {
        ProviderTable.Service s = ProviderTable.match(id, name);
        return s == null ? null : s.key;
    }

    private static void is(int id, String name, String expected) {
        String actual = key(id, name);
        check(expected == null ? actual == null : expected.equals(actual),
                "match(" + id + ", " + name + "): expected " + expected + " but was " + actual);
    }

    public static void main(String[] args) {
        // By id first, then by name.
        is(8, "Netflix", "netflix");
        is(1796, "Netflix basic with Ads", "netflix");
        is(0, "Netflix with Ads", "netflix");
        is(999999, "Netflix Standard with Ads", "netflix");
        is(119, "Amazon Prime Video", "prime");
        is(0, "Amazon Prime Video with Ads", "prime");
        is(337, "Disney Plus", "disney");
        is(307, "Globoplay", "globoplay");
        is(531, "Paramount Plus", "paramount");
        is(167, "Claro video", "claro");
        is(0, "Claro tv+", "claro");
        is(0, "Clarovideo", null);   // not the word
        is(350, "Apple TV+", "appletv");
        is(283, "Crunchyroll", "crunchyroll");
        is(0, "Pluto TV", "pluto");
        is(0, "MUBI", "mubi");
        is(0, "Looke", "looke");

        // Resold " channel" subscriptions: the reseller's app, or none.
        is(1825, "HBO Max Amazon Channel", "prime");
        is(0, "Paramount+ Amazon Channel", "prime");
        is(0, "Starz Apple TV Channel", "appletv");
        is(0, "MGM Plus Roku Premium Channel", null);
        is(0, "Looke Amazon Channel", "prime");
        is(0, "Belas Artes à La Carte Channel", null);
        is(8, "Netflix Kids Channel", null);   // the name wins over a known id for a channel

        // "Max" only as a whole word.
        is(1899, "Max", "max");
        is(0, "HBO Max", "max");
        is(0, "MAX", "max");
        is(0, "Cinemax", null);
        is(0, "Maxdome", null);
        is(0, "Max Amazon Channel", "prime");

        // Unknown and empty.
        is(0, "Some Local Service", null);
        is(0, null, null);
        is(0, "", null);

        // Stores are matched, never streamable.
        ProviderTable.Service apple = ProviderTable.match(2, "Apple TV");
        check(apple != null && apple.key.equals("appletv"), "Apple TV store id 2 matches appletv");
        check(!ProviderTable.streamable(apple, 2, ExploreModel.FLATRATE), "Apple TV id 2 is the store");
        check(!ProviderTable.streamable(apple, 2, ExploreModel.RENT), "Apple TV id 2 rent is not streamable");
        check(ProviderTable.streamable(apple, 350, ExploreModel.FLATRATE), "Apple TV+ 350 streams");
        ProviderTable.Service prime = ProviderTable.match(10, "Amazon Video");
        check(prime != null && prime.key.equals("prime"), "Amazon Video store matches prime");
        check(!ProviderTable.streamable(prime, 10, ExploreModel.BUY), "Amazon Video store is not streamable");
        check(ProviderTable.streamable(prime, 119, ExploreModel.FLATRATE), "Prime Video streams");
        ProviderTable.Service netflix = ProviderTable.match(1796, "Netflix basic with Ads");
        check(ProviderTable.streamable(netflix, 1796, ExploreModel.ADS), "Netflix with ads streams");
        check(ProviderTable.streamable(netflix, 8, ExploreModel.FREE), "free streams");
        check(!ProviderTable.streamable(netflix, 8, ExploreModel.RENT), "rent never streams");
        check(!ProviderTable.streamable(netflix, 8, ExploreModel.BUY), "buy never streams");
        check(!ProviderTable.streamable(null, 8, ExploreModel.FLATRATE), "no service, no stream");

        // plain(): accents, case and + folded, as Recs.plain.
        check(ProviderTable.plain("Claro Vídeo").equals("claro video"), "accents");
        check(ProviderTable.plain("Disney+").equals("disney plus"), "plus");
        check(ProviderTable.plain("  HBO-MAX  ").equals("hbo max"), "punctuation and case");
        check(ProviderTable.plain(null).isEmpty(), "null");

        // The table: unique keys, ARGB colours, row order starting as the spec says.
        String[] order = {"netflix", "prime", "max", "disney", "globoplay", "appletv", "paramount", "claro"};
        for (int i = 0; i < order.length; i++) check(ProviderTable.TABLE[i].key.equals(order[i]), "row order " + i);
        for (int i = 0; i < ProviderTable.TABLE.length; i++) {
            ProviderTable.Service s = ProviderTable.TABLE[i];
            check((s.color >>> 24) == 0xFF, "opaque colour for " + s.key);
            check(ProviderTable.byKey(s.key) == s, "byKey " + s.key);
            for (int j = 0; j < i; j++) check(!ProviderTable.TABLE[j].key.equals(s.key), "unique key " + s.key);
            for (int id : s.ids) check(ProviderTable.match(id, null) == s, "id " + id + " matches " + s.key);
        }
        check(ProviderTable.byKey("claro").packages[0].equals("br.com.claro.now.smarttvclient"), "Claro tv+ package seen on the TCL");

        System.out.println("ProviderTable: " + assertions + " assertions passed");
    }
}
