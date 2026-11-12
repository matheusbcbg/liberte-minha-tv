package io.freemytv;

import java.util.Calendar;
import java.util.TimeZone;

public final class TmdbPathsTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void same(String actual, String expected, String reason) {
        check(expected.equals(actual), reason + ": expected " + expected + " but was " + actual);
    }

    private static long utc(int y, int m, int d, int h, int min) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d, h, min, 0);
        return c.getTimeInMillis();
    }

    private static boolean pageLast(String path) {
        int at = path.lastIndexOf("page=");
        return at > 0 && path.indexOf('&', at) < 0 && (path.charAt(at - 1) == '&' || path.charAt(at - 1) == '?');
    }

    public static void main(String[] args) {
        // The same filters in any order give the same path, page last.
        String a = TmdbPaths.discover(TmdbPaths.MOVIE).sort("popularity.desc").genres("28").minVotes(50).streamable().path(2);
        String b = TmdbPaths.discover(TmdbPaths.MOVIE).streamable().minVotes(50).genres("28").sort("popularity.desc").path(2);
        same(a, b, "parameter order must not depend on call order");
        same(a, "/discover/movie?language=pt-BR&include_adult=false&watch_region=BR&with_watch_monetization_types=flatrate%7Cfree%7Cads"
                + "&with_genres=28&vote_count.gte=50&sort_by=popularity.desc&page=2", "discover movie");
        check(pageLast(a), "page last");

        // providers() implies the region and the monetization types; the ids' | is encoded.
        String mine = TmdbPaths.discover(TmdbPaths.TV).providers("8|119|337").sort("popularity.desc").path(1);
        check(mine.contains("watch_region=BR&with_watch_monetization_types=flatrate%7Cfree%7Cads"), "providers implies watch_region");
        check(mine.contains("with_watch_providers=8%7C119%7C337"), "provider ids joined by %7C");
        check(!mine.contains("|"), "no raw pipe");
        check(TmdbPaths.discover(TmdbPaths.MOVIE).streamable().path(1).contains("watch_region=BR"), "streamable adds watch_region");
        check(!TmdbPaths.discover(TmdbPaths.MOVIE).providers("").path(1).contains("watch_region"), "empty providers adds nothing");

        // Dates: movies use primary_release_date, shows first_air_date; air_date adds the time zone, before sort.
        String movieDates = TmdbPaths.discover(TmdbPaths.MOVIE).dateGte("2026-01-01").dateLte("2026-02-01").path(1);
        check(movieDates.contains("primary_release_date.gte=2026-01-01&primary_release_date.lte=2026-02-01"), "movie dates");
        String tvDates = TmdbPaths.discover(TmdbPaths.TV).dateLte("2026-02-01").airGte("2026-01-01").airLte("2026-01-08").sort("popularity.desc").path(1);
        check(tvDates.contains("first_air_date.lte=2026-02-01&air_date.gte=2026-01-01&air_date.lte=2026-01-08&timezone=America/Rio_Branco&sort_by=popularity.desc&page=1"),
                "tv dates and timezone: " + tvDates);
        check(!movieDates.contains("air_date.gte") && !TmdbPaths.discover(TmdbPaths.MOVIE).airGte("2026-01-01").path(1).contains("air_date"),
                "air_date is for shows only");
        check(TmdbPaths.discover(TmdbPaths.MOVIE).minRating(7.8f).path(1).contains("vote_average.gte=7.8&"), "rating with a dot");
        check(TmdbPaths.discover(TmdbPaths.TV).type(2).path(1).contains("with_type=2"), "tv type");
        check(!TmdbPaths.discover(TmdbPaths.MOVIE).type(2).path(1).contains("with_type"), "movie has no type");

        // Lists, trending, details and the rest.
        same(TmdbPaths.trending("all", false, 1), "/trending/all/day?language=pt-BR&include_adult=false&page=1", "trending day");
        same(TmdbPaths.trending("movie", true, 3), "/trending/movie/week?language=pt-BR&include_adult=false&page=3", "trending week");
        same(TmdbPaths.list(TmdbPaths.MOVIE, "now_playing", 1), "/movie/now_playing?language=pt-BR&include_adult=false&region=BR&page=1", "now playing");
        same(TmdbPaths.list(TmdbPaths.TV, "airing_today", 1),
                "/tv/airing_today?language=pt-BR&include_adult=false&timezone=America/Rio_Branco&page=1", "airing today");
        same(TmdbPaths.list(TmdbPaths.TV, "popular", 2), "/tv/popular?language=pt-BR&include_adult=false&page=2", "tv popular");
        String details = TmdbPaths.details(TmdbPaths.MOVIE, 603);
        check(details.startsWith("/movie/603?language=pt-BR&append_to_response=credits,videos,images,watch/providers,recommendations,similar,external_ids,release_dates"),
                "movie details");
        check(TmdbPaths.details(TmdbPaths.TV, 1396).contains("content_ratings") && !TmdbPaths.details(TmdbPaths.TV, 1396).contains("release_dates"), "tv details");
        same(TmdbPaths.detailsEnglish(TmdbPaths.TV, 1396), "/tv/1396?language=en-US", "english details");
        same(TmdbPaths.season(1396, 2), "/tv/1396/season/2?language=pt-BR", "season");
        same(TmdbPaths.person(287), "/person/287?language=pt-BR&append_to_response=combined_credits", "person");
        same(TmdbPaths.search("Cidade de Deus & mais", 1),
                "/search/multi?query=Cidade%20de%20Deus%20%26%20mais&region=BR&language=pt-BR&include_adult=false&page=1", "search encoded");
        same(TmdbPaths.genres(TmdbPaths.TV), "/genre/tv/list?language=pt-BR", "genres");
        same(TmdbPaths.providers(TmdbPaths.MOVIE), "/watch/providers/movie?language=pt-BR&watch_region=BR", "providers");

        // withPage: replaces the last page=, or adds one.
        same(TmdbPaths.withPage(a, 5), a.substring(0, a.length() - 1) + "5", "withPage replaces");
        same(TmdbPaths.withPage("/genre/tv/list?language=pt-BR", 2), "/genre/tv/list?language=pt-BR&page=2", "withPage adds");
        check(TmdbPaths.withPage(TmdbPaths.trending("all", false, 9), 10).endsWith("&page=10"), "withPage two digits");
        check(TmdbPaths.withPage(a, 0).endsWith("&page=1"), "pages start at 1");

        // No key in any path.
        String[] all = {a, mine, tvDates, details, TmdbPaths.search("x", 1), TmdbPaths.person(1), TmdbPaths.season(1, 1),
                TmdbPaths.trending("tv", true, 1), TmdbPaths.list(TmdbPaths.MOVIE, "upcoming", 1), TmdbPaths.providers(TmdbPaths.TV)};
        for (String p : all) {
            check(p.startsWith("/"), "relative to the API: " + p);
            check(!p.contains("api_key") && !p.contains("apiKey") && !p.contains("http"), "no key or host in a path: " + p);
        }

        // TTL classes.
        long h = 3600_000L, d = 24 * h;
        check(TmdbPaths.ttlMs(TmdbPaths.trending("all", false, 1)) == 2 * h, "trending day 2 h");
        check(TmdbPaths.ttlMs(TmdbPaths.trending("movie", true, 1)) == 12 * h, "trending week 12 h");
        check(TmdbPaths.ttlMs(TmdbPaths.list(TmdbPaths.MOVIE, "popular", 1)) == 6 * h, "popular 6 h");
        check(TmdbPaths.ttlMs(TmdbPaths.list(TmdbPaths.MOVIE, "upcoming", 1)) == 6 * h, "upcoming 6 h");
        check(TmdbPaths.ttlMs(TmdbPaths.list(TmdbPaths.TV, "on_the_air", 1)) == 6 * h, "on the air 6 h");
        check(TmdbPaths.ttlMs(a) == 6 * h, "discover by popularity 6 h");
        check(TmdbPaths.ttlMs(TmdbPaths.discover(TmdbPaths.MOVIE).sort("primary_release_date.desc").path(1)) == 6 * h, "discover by date 6 h");
        check(TmdbPaths.ttlMs(TmdbPaths.discover(TmdbPaths.MOVIE).sort("vote_average.desc").path(1)) == d, "discover by vote 24 h");
        check(TmdbPaths.ttlMs(TmdbPaths.list(TmdbPaths.TV, "top_rated", 1)) == d, "top rated 24 h");
        check(TmdbPaths.ttlMs(details) == d, "details 24 h");
        check(TmdbPaths.ttlMs(TmdbPaths.season(1, 1)) == d, "season 24 h");
        check(TmdbPaths.ttlMs(TmdbPaths.detailsEnglish(TmdbPaths.MOVIE, 1)) == 7 * d, "english 7 d");
        check(TmdbPaths.ttlMs(TmdbPaths.person(1)) == 7 * d, "person 7 d");
        check(TmdbPaths.ttlMs(TmdbPaths.genres(TmdbPaths.MOVIE)) == 7 * d, "genres 7 d");
        check(TmdbPaths.ttlMs(TmdbPaths.providers(TmdbPaths.TV)) == 7 * d, "providers 7 d");
        check(TmdbPaths.ttlMs(TmdbPaths.search("x", 1)) == 15 * 60_000L, "search 15 min");

        // Labels: the only thing logged, never an id or a query.
        same(TmdbPaths.label(a), "discover", "label discover");
        same(TmdbPaths.label(details), "details", "label details");
        same(TmdbPaths.label(TmdbPaths.detailsEnglish(TmdbPaths.TV, 5)), "details-en", "label en");
        same(TmdbPaths.label(TmdbPaths.season(5, 1)), "season", "label season");
        same(TmdbPaths.label(TmdbPaths.list(TmdbPaths.MOVIE, "popular", 1)), "list", "label list");
        same(TmdbPaths.label(TmdbPaths.search("segredo", 1)), "search", "label search");
        check(!TmdbPaths.label(TmdbPaths.search("segredo", 1)).contains("segredo"), "no query in a label");

        // Dates in America/Rio_Branco (UTC-5, no daylight saving) around midnight UTC.
        same(TmdbPaths.today(utc(2026, 3, 10, 2, 0)), "2026-03-09", "02:00 UTC is still the day before in Rio Branco");
        same(TmdbPaths.today(utc(2026, 3, 10, 4, 59)), "2026-03-09", "04:59 UTC is 23:59 in Rio Branco");
        same(TmdbPaths.today(utc(2026, 3, 10, 5, 0)), "2026-03-10", "05:00 UTC is midnight in Rio Branco");
        same(TmdbPaths.day(utc(2026, 1, 1, 3, 0), -365), "2024-12-31", "a year back from New Year's Eve evening (still 2025-12-31 there)");
        same(TmdbPaths.day(utc(2026, 3, 1, 2, 0), 1), "2026-03-01", "tomorrow from Feb 28 evening");
        same(TmdbPaths.day(utc(2024, 3, 1, 4, 0), -1), "2024-02-28", "leap year");
        same(TmdbPaths.day(utc(2026, 11, 5, 12, 0), 0), TmdbPaths.today(utc(2026, 11, 5, 12, 0)), "day 0 is today");

        // searchTitle (WatchMatch): movie or tv, the query encoded, a year only for movies and only when known, page 1 last.
        same(TmdbPaths.searchTitle(TmdbPaths.MOVIE, " Coringa: Delírio a Dois ", 2024),
                "/search/movie?query=Coringa%3A%20Del%C3%ADrio%20a%20Dois&year=2024&region=BR&language=pt-BR&include_adult=false&page=1",
                "searchTitle movie with year");
        same(TmdbPaths.searchTitle(TmdbPaths.MOVIE, "Coringa", 0),
                "/search/movie?query=Coringa&region=BR&language=pt-BR&include_adult=false&page=1", "searchTitle movie without year");
        same(TmdbPaths.searchTitle(TmdbPaths.TV, "Law & Order", 2019),
                "/search/tv?query=Law%20%26%20Order&region=BR&language=pt-BR&include_adult=false&page=1", "searchTitle tv never has a year");
        same(TmdbPaths.searchTitle(TmdbPaths.MOVIE, null, -1),
                "/search/movie?query=&region=BR&language=pt-BR&include_adult=false&page=1", "searchTitle null query");
        String st = TmdbPaths.searchTitle(TmdbPaths.TV, "a+b/c?d", 0);
        check(st.contains("query=a%2Bb%2Fc%3Fd&"), "searchTitle encodes + / ?: " + st);
        check(pageLast(st), "searchTitle page last");
        check(!TmdbPaths.searchTitle(TmdbPaths.MOVIE, "x", 1999).contains(" "), "no raw space");
        check(TmdbPaths.ttlMs(TmdbPaths.searchTitle(TmdbPaths.MOVIE, "x", 2000)) == 15 * 60_000L, "searchTitle 15 min");
        same(TmdbPaths.label(TmdbPaths.searchTitle(TmdbPaths.TV, "segredo", 0)), "search", "searchTitle label");
        check(!TmdbPaths.searchTitle(TmdbPaths.MOVIE, "x", 0).equals(TmdbPaths.searchTitle(TmdbPaths.TV, "x", 0)), "movie and tv differ");

        System.out.println("TmdbPaths: " + assertions + " assertions passed");
    }
}
