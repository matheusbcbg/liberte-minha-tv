package io.freemytv;

public final class TitleMatchTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        check(TitleMatch.accept("Coringa", 2019, "Coringa", "Joker", 2019, 50, true), "same title, same year");
        check(TitleMatch.accept("Coringa", 0, "Coringa", "Joker", 2019, 1, false), "same title, no year asked");
        check(!TitleMatch.accept("Coringa", 2019, "Coringa: Delírio a Dois", "Joker: Folie à Deux", 2024, 80, true), "a sequel fails");
        check(!TitleMatch.accept("Coringa", 0, "Coringa: Delírio a Dois", "Joker: Folie à Deux", 2024, 80, true), "a sequel fails without a year");
        check("claro video".equals(TitleMatch.plain("Claro Vídeo")), "plain drops accents and case");
        check(TitleMatch.plain("Disney+").equals("disney plus"), "plus");
        check(TitleMatch.plain(null).isEmpty(), "null plain");
        check(TitleMatch.accept("CLARO VÍDEO!", 0, "Claro video", null, 0, 0, false), "plain equality");
        check(TitleMatch.accept("Joker", 2019, "Coringa", "Joker", 2019, 5, false), "the original title passes");
        check(TitleMatch.accept("Joker", 2019, "Coringa", "Joker", 2020, 5, false), "year +1 passes");
        check(!TitleMatch.accept("Joker", 2019, "Coringa", "Joker", 2021, 5, false), "year +2 fails an exact title");
        check(!TitleMatch.accept("Joker", 2019, "Coringa", "Joker", 0, 5, false), "a result without a year fails a known year");
        check(!TitleMatch.accept("Coringa (dublado)", 0, "Coringa", null, 2019, 90, true), "first result without a year asked fails");
        check(TitleMatch.accept("Coringa (dublado)", 2019, "Coringa", null, 2018, 10, true), "first, year -1, popularity 10 passes");
        check(TitleMatch.accept("Coringa (dublado)", 2019, "Coringa", null, 2020, 55.5, true), "first, year +1, popular passes");
        check(!TitleMatch.accept("Coringa (dublado)", 2019, "Coringa", null, 2021, 90, true), "first, year +2 fails");
        check(!TitleMatch.accept("Coringa (dublado)", 2019, "Coringa", null, 2019, 9, true), "first, popularity 9 fails");
        check(!TitleMatch.accept("Coringa (dublado)", 2019, "Coringa", null, 2019, 90, false), "not first fails");
        check(!TitleMatch.accept("", 0, "", "", 0, 100, true), "empty wanted fails");
        check(!TitleMatch.accept(null, 2019, null, null, 2019, 100, true), "null wanted fails");
        check(!TitleMatch.key("com.wbd.stream", "Coringa", true).equals(TitleMatch.key("com.wbd.stream", "Coringa", false)), "key: movie and tv differ");
        check(TitleMatch.key("com.wbd.stream", "Coringa", true).equals(TitleMatch.key("com.wbd.stream", "CORINGA", true)), "key: plain title");
        check(!TitleMatch.key("com.wbd.stream", "Coringa", true).equals(TitleMatch.key("com.netflix.ninja", "Coringa", true)), "key: per app");
        java.util.List<String> q = TitleMatch.queries("Rick and Morty: S7 E3");
        check(q.size() == 2 && q.get(1).equals("Rick and Morty"), "queries: season and episode dropped " + q);
        check(TitleMatch.queries("Rick and Morty - Temporada 7").contains("Rick and Morty"), "queries: temporada dropped");
        check(TitleMatch.queries("Rick and Morty (2013)").contains("Rick and Morty"), "queries: year dropped");
        check(TitleMatch.queries("Rick and Morty: Pickle Rick").get(1).equals("Rick and Morty"), "queries: before the colon");
        check(TitleMatch.queries("Coringa").size() == 1, "queries: a plain title is one search");
        check(TitleMatch.queries(null).isEmpty(), "queries: null");
        check(TitleMatch.accept("Rick & Morty", 0, "Rick and Morty", "Rick and Morty", 2013, 300, true), "& reads as and");
        check(TitleMatch.queries("Rick & Morty").contains("Rick and Morty"), "queries: & as and " + TitleMatch.queries("Rick & Morty"));
        check(!TitleMatch.accept("Rick & Morty", 0, "Rick", null, 2013, 300, false), "core still needs every other word");
        System.out.println("TitleMatch: " + assertions + " assertions passed");
    }
}
