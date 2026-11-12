package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What each Explore page shows, top to bottom (spec 1.5): the hero and the rows, with their TMDB paths. Built when a
 * page is built (titles through Ui.tr then); TMDB's own words (genre names) never go through Ui.tr.
 */
final class Catalog {
    private Catalog() {}

    static final int HOME = 0, MOVIES = 1, SHOWS = 2, DOCS = 3, ANIMATION = 4, SERVICES = 5, SEARCH = 6;
    static final String[] PAGE_NAMES = {"home", "movies", "shows", "docs", "animation", "services", "search"};
    /** Row styles, shared with the UI. */
    static final int POSTER = 0, WIDE = 1, TOP10 = 2, CONTINUE = 3, GENRE = 4, SERVICE = 5, CAST = 6, PROVIDER = 7,
            SEASON = 8, EPISODE = 9, INFO = 10, CHIP = 11;
    /**
     * Where a row's cards come from: TMDB pages (paths), Continue Watching (WatchFeed), the genre lists
     * (Tmdb.genres), the TV's own services (BrProviders) or Brazil's other ones (Tmdb.providers minus the installed).
     */
    static final int SRC_TMDB = 0, SRC_WATCH = 1, SRC_GENRES_MOVIE = 2, SRC_GENRES_TV = 3, SRC_MY_SERVICES = 4, SRC_OTHER_SERVICES = 5,
            SRC_WATCHLIST = 6;
    /**
     * F_MORE: ends with "See all" when there is more than one page. F_DROP_HERO: leaves out the hero's titles.
     * F_FUTURE_ONLY: only dates after today (Rio Branco). F_NEEDS_BACKDROP: only titles with a backdrop (heroes).
     * F_ALTERNATE: two paths (movies and shows), interleaved one by one.
     */
    static final int F_MORE = 1, F_DROP_HERO = 2, F_FUTURE_ONLY = 4, F_NEEDS_BACKDROP = 8, F_ALTERNATE = 16;

    /** The most pages a row, or its "See all" grid, may load. */
    private static final int PAGES = 10;
    /** Cards in a row from one page (TMDB's page size). */
    private static final int ROW = 20;

    static final class RowSpec {
        /** Unique on its page (the UI keeps a row's state by it). */
        final String key;
        final int style, source, flags;
        /** The most cards shown (0: no limit); the most pages loaded. */
        final int limit, maxPages;
        final String title;
        /** Page 1's paths: one, or two (movies, shows) for F_ALTERNATE. Empty for sources other than SRC_TMDB. */
        final String[] paths;

        RowSpec(String key, int style, int source, int flags, int limit, int maxPages, String title, String[] paths) {
            this.key = key;
            this.style = style;
            this.source = source;
            this.flags = flags;
            this.limit = limit;
            this.maxPages = maxPages;
            this.title = title;
            this.paths = paths == null ? new String[0] : paths;
        }

        /** The paths for another page (1-based). */
        String[] paths(int page) {
            if (page <= 1) return paths;
            String[] out = new String[paths.length];
            for (int i = 0; i < paths.length; i++) out[i] = TmdbPaths.withPage(paths[i], page);
            return out;
        }
    }

    private static String[] labels;
    private static int labelsAt = -1;

    /** The top bar's names, by page constant; made again when the language changes. */
    static String[] tabLabels() {
        String[] l = labels;
        if (l == null || labelsAt != Ui.generation) {
            l = new String[] {Ui.tr("explore|Home"), Ui.tr("Movies"), Ui.tr("Shows"), Ui.tr("Documentaries"),
                    Ui.tr("Animation"), Ui.tr("explore|Services"), Ui.tr("Search")};
            labels = l;
            labelsAt = Ui.generation;
        }
        return l;
    }

    // MARK: - Path helpers

    private static final int M = TmdbPaths.MOVIE, T = TmdbPaths.TV;
    private static final String POPULAR = "popularity.desc", BY_VOTE = "vote_average.desc";

    private static TmdbPaths.Discover d(int kind) {
        return TmdbPaths.discover(kind);
    }

    /** One path. */
    private static String[] one(String path) {
        return new String[] {path};
    }

    /** Movies and shows, interleaved (M+T). */
    private static String[] both(TmdbPaths.Discover movie, TmdbPaths.Discover tv) {
        return new String[] {movie.path(1), tv.path(1)};
    }

    private static RowSpec row(String key, int style, int flags, String title, String[] paths) {
        boolean more = (flags & F_MORE) != 0;
        int f = flags | (paths.length > 1 ? F_ALTERNATE : 0);
        return new RowSpec(key, style, SRC_TMDB, f, more ? 0 : ROW * paths.length, more ? PAGES : 1, title, paths);
    }

    private static RowSpec top10(String key, String title, int kind, int votes) {
        return new RowSpec(key, TOP10, SRC_TMDB, 0, 10, 1, title,
                one(d(kind).streamable().sort(POPULAR).minVotes(votes).path(1)));
    }

    private static RowSpec special(String key, int style, int source, String title) {
        return new RowSpec(key, style, source, 0, 0, 1, title, null);
    }

    private static RowSpec hero(String key, int limit, String[] paths) {
        return new RowSpec(key, WIDE, SRC_TMDB, F_NEEDS_BACKDROP | (paths.length > 1 ? F_ALTERNATE : 0), limit, 1, null, paths);
    }

    // MARK: - Pages

    /**
     * The hero carousel, or null (Streamings, Search). Home's falls back to today's trending when no installed
     * service is known; when it comes back empty, the UI asks TmdbPaths.trending("all", false, 1) instead.
     */
    static RowSpec hero(int page, BrProviders.Snapshot s, long now) {
        String mine = s == null ? "" : s.installedIds();
        switch (page) {
            case HOME:
                if (mine.isEmpty()) return hero("hero:home", 8, one(TmdbPaths.trending("all", false, 1)));
                return hero("hero:home", 8, both(d(M).providers(mine).dateGte(TmdbPaths.day(now, -365)).sort(POPULAR),
                        d(T).providers(mine).airGte(TmdbPaths.day(now, -90)).sort(POPULAR)));
            case MOVIES:
                return hero("hero:movies", 6, one(TmdbPaths.list(M, "popular", 1)));
            case SHOWS:
                return hero("hero:shows", 6, one(TmdbPaths.list(T, "popular", 1)));
            case DOCS:
                return hero("hero:docs", 8, both(d(M).streamable().genres("99").sort(POPULAR), d(T).streamable().genres("99").sort(POPULAR)));
            case ANIMATION:
                return hero("hero:animation", 8, both(d(M).streamable().genres("16").sort(POPULAR), d(T).streamable().genres("16").sort(POPULAR)));
            default:
                return null;
        }
    }

    /** The rows under the hero, in order. Rows that need an installed service are left out without one. */
    static List<RowSpec> rows(int page, BrProviders.Snapshot s, long now) {
        List<RowSpec> out = new ArrayList<>();
        String mine = s == null ? "" : s.installedIds();
        String today = TmdbPaths.today(now);
        switch (page) {
            case HOME:
                out.add(special("home:watch", CONTINUE, SRC_WATCH, Ui.tr("Continue Watching")));
                out.add(special("home:watchlist", WIDE, SRC_WATCHLIST, Ui.tr("Want to Watch")));
                out.add(row("home:trending", POSTER, F_MORE | F_DROP_HERO, Ui.tr("Trending today"), one(TmdbPaths.trending("all", false, 1))));
                out.add(top10("home:top-movies", Ui.tr("Top 10 movies on streaming"), M, 50));
                out.add(top10("home:top-shows", Ui.tr("Top 10 shows on streaming"), T, 20));
                if (!mine.isEmpty()) {
                    out.add(row("home:mine", WIDE, F_MORE, Ui.tr("On your apps"), both(d(M).providers(mine).sort(POPULAR), d(T).providers(mine).sort(POPULAR))));
                }
                if (s != null) {
                    for (ProviderTable.Service service : s.installedServices()) {
                        String ids = ids(service.ids);
                        out.add(row("home:service:" + service.key, POSTER, F_MORE, Ui.trf("Popular on {service}", "service", service.label),
                                both(d(M).providers(ids).sort(POPULAR), d(T).providers(ids).sort(POPULAR))));
                    }
                }
                if (!mine.isEmpty()) out.add(newEpisodes("home:new-episodes", mine, now));
                out.add(row("home:theaters", POSTER, F_MORE, Ui.tr("In theaters"), one(TmdbPaths.list(M, "now_playing", 1))));
                out.add(row("home:brazil", POSTER, F_MORE, Ui.tr("Brazilian productions"),
                        both(d(M).streamable().origin("BR").minVotes(10).sort(POPULAR), d(T).streamable().origin("BR").minVotes(10).sort(POPULAR))));
                out.add(row("home:soon", WIDE, F_MORE | F_FUTURE_ONLY, Ui.tr("Coming soon to theaters"), one(TmdbPaths.list(M, "upcoming", 1))));
                out.add(row("home:acclaimed", WIDE, F_MORE, Ui.tr("Critically acclaimed"),
                        one(d(M).streamable().minRating(7.8f).minVotes(1500).sort(BY_VOTE).path(1))));
                out.add(special("home:genres", GENRE, SRC_GENRES_MOVIE, Ui.tr("Browse by genre")));
                out.add(special("home:services", SERVICE, SRC_MY_SERVICES, Ui.tr("Your services")));
                break;
            case MOVIES:
                out.add(row("movies:trending", POSTER, F_MORE, Ui.tr("Trending this week"), one(TmdbPaths.trending("movie", true, 1))));
                out.add(top10("movies:top", Ui.tr("Top 10 movies on streaming"), M, 50));
                out.add(row("movies:new", WIDE, F_MORE, Ui.tr("New on streaming"),
                        one(d(M).streamable().dateGte(TmdbPaths.day(now, -90)).dateLte(today).minVotes(20).sort("primary_release_date.desc").path(1))));
                out.add(row("movies:theaters", POSTER, F_MORE, Ui.tr("In theaters"), one(TmdbPaths.list(M, "now_playing", 1))));
                out.add(row("movies:soon", WIDE, F_MORE | F_FUTURE_ONLY, Ui.tr("Coming soon to theaters"), one(TmdbPaths.list(M, "upcoming", 1))));
                out.add(row("movies:top-rated", POSTER, F_MORE, Ui.tr("Top rated"), one(TmdbPaths.list(M, "top_rated", 1))));
                out.add(row("movies:brazil", POSTER, F_MORE, Ui.tr("Brazilian movies"), one(d(M).streamable().origin("BR").sort(POPULAR).path(1))));
                for (int i = 0; i < MOVIE_GENRES.length; i++) {
                    out.add(row("movies:genre:" + MOVIE_GENRES[i], POSTER, F_MORE, MOVIE_GENRE_NAMES[i],
                            one(d(M).streamable().genres(String.valueOf(MOVIE_GENRES[i])).minVotes(50).sort(POPULAR).path(1))));
                }
                out.add(special("movies:genres", GENRE, SRC_GENRES_MOVIE, Ui.tr("Browse by genre")));
                break;
            case SHOWS:
                out.add(row("shows:trending", POSTER, F_MORE, Ui.tr("Trending this week"), one(TmdbPaths.trending("tv", true, 1))));
                out.add(top10("shows:top", Ui.tr("Top 10 shows on streaming"), T, 20));
                if (!mine.isEmpty()) out.add(newEpisodes("shows:new-episodes", mine, now));
                out.add(row("shows:on-air", POSTER, F_MORE, Ui.tr("On the air"), one(TmdbPaths.list(T, "on_the_air", 1))));
                out.add(row("shows:today", WIDE, F_MORE, Ui.tr("Airing today"), one(TmdbPaths.list(T, "airing_today", 1))));
                out.add(row("shows:top-rated", POSTER, F_MORE, Ui.tr("shows|Top rated"), one(TmdbPaths.list(T, "top_rated", 1))));
                out.add(row("shows:brazil", POSTER, F_MORE, Ui.tr("Brazilian shows"), one(d(T).streamable().origin("BR").sort(POPULAR).path(1))));
                out.add(row("shows:mini", POSTER, F_MORE, Ui.tr("Miniseries"), one(d(T).streamable().type(2).sort(POPULAR).path(1))));
                out.add(row("shows:reality", POSTER, F_MORE, Ui.tr("Reality shows"), one(d(T).streamable().type(3).sort(POPULAR).path(1))));
                for (int i = 0; i < TV_GENRES.length; i++) {
                    out.add(row("shows:genre:" + TV_GENRES[i], POSTER, F_MORE, TV_GENRE_NAMES[i],
                            one(d(T).streamable().genres(String.valueOf(TV_GENRES[i])).minVotes(20).sort(POPULAR).path(1))));
                }
                out.add(special("shows:genres", GENRE, SRC_GENRES_TV, Ui.tr("Browse by genre")));
                break;
            case DOCS:
                out.add(row("docs:movies", POSTER, F_MORE, Ui.tr("Documentary films"), one(d(M).streamable().genres("99").sort(POPULAR).path(1))));
                out.add(row("docs:series", WIDE, F_MORE, Ui.tr("Docuseries"), one(d(T).streamable().genres("99").sort(POPULAR).path(1))));
                out.add(row("docs:top", POSTER, F_MORE, Ui.tr("Top rated"), one(d(M).streamable().genres("99").minVotes(200).sort(BY_VOTE).path(1))));
                out.add(row("docs:crime", WIDE, F_MORE, Ui.tr("True crime"), one(d(T).streamable().genres("99,80").sort(POPULAR).path(1))));
                out.add(row("docs:history", POSTER, F_MORE, Ui.tr("History"), one(d(M).streamable().genres("99,36").sort(POPULAR).path(1))));
                out.add(row("docs:music", POSTER, F_MORE, Ui.tr("Music"), one(d(M).streamable().genres("99,10402").sort(POPULAR).path(1))));
                out.add(row("docs:brazil", POSTER, F_MORE, Ui.tr("Brazilian documentaries"),
                        both(d(M).streamable().genres("99").origin("BR").sort(POPULAR), d(T).streamable().genres("99").origin("BR").sort(POPULAR))));
                if (!mine.isEmpty()) {
                    out.add(row("docs:mine", POSTER, F_MORE, Ui.tr("On your apps"),
                            both(d(M).providers(mine).genres("99").sort(POPULAR), d(T).providers(mine).genres("99").sort(POPULAR))));
                }
                break;
            case ANIMATION:
                out.add(row("animation:movies", POSTER, F_MORE, Ui.tr("Animated movies"), one(d(M).streamable().genres("16").sort(POPULAR).path(1))));
                out.add(row("animation:series", WIDE, F_MORE, Ui.tr("Animated series"), one(d(T).streamable().genres("16").sort(POPULAR).path(1))));
                out.add(row("animation:anime", POSTER, F_MORE, Ui.tr("Anime"), one(d(T).streamable().genres("16").originalLanguage("ja").sort(POPULAR).path(1))));
                out.add(row("animation:family", POSTER, F_MORE, Ui.tr("For the family"), one(d(M).streamable().genres("16,10751").sort(POPULAR).path(1))));
                out.add(row("animation:kids", POSTER, F_MORE, Ui.tr("Kids"), one(d(T).streamable().genres("10762").sort(POPULAR).path(1))));
                out.add(row("animation:top", POSTER, F_MORE, Ui.tr("shows|Top rated"),
                        both(d(M).streamable().genres("16").minVotes(300).sort(BY_VOTE), d(T).streamable().genres("16").minVotes(300).sort(BY_VOTE))));
                out.add(row("animation:classics", POSTER, F_MORE, Ui.tr("Classics"),
                        one(d(M).streamable().genres("16").dateLte("1999-12-31").minVotes(500).sort(BY_VOTE).path(1))));
                if (!mine.isEmpty()) {
                    out.add(row("animation:mine", POSTER, F_MORE, Ui.tr("On your apps"),
                            both(d(M).providers(mine).genres("16").sort(POPULAR), d(T).providers(mine).genres("16").sort(POPULAR))));
                }
                break;
            case SERVICES:
                out.add(special("services:mine", SERVICE, SRC_MY_SERVICES, Ui.tr("Your services")));
                out.add(new RowSpec("services:other", SERVICE, SRC_OTHER_SERVICES, 0, 20, 1, Ui.tr("Other services in Brazil"),
                        new String[] {TmdbPaths.providers(M), TmdbPaths.providers(T)}));
                break;
            default:
                break;
        }
        return Collections.unmodifiableList(out);
    }

    private static RowSpec newEpisodes(String key, String mine, long now) {
        return row(key, WIDE, F_MORE, Ui.tr("New episodes this week"),
                one(d(T).providers(mine).airGte(TmdbPaths.day(now, -6)).airLte(TmdbPaths.day(now, 1)).sort(POPULAR).path(1)));
    }

    /** A service page's hero: its titles, by popularity. */
    static RowSpec providerHero(int[] providerIds) {
        String ids = ids(providerIds);
        return hero("hero:provider:" + ids, 8, both(d(M).providers(ids).sort(POPULAR), d(T).providers(ids).sort(POPULAR)));
    }

    /** A service page's rows, all filtered by its providers. */
    static List<RowSpec> providerRows(int[] providerIds, long now) {
        String ids = ids(providerIds), today = TmdbPaths.today(now), k = "provider:" + ids + ":";
        List<RowSpec> out = new ArrayList<>();
        out.add(row(k + "shows", POSTER, F_MORE, Ui.tr("Popular shows"), one(d(T).providers(ids).sort(POPULAR).path(1))));
        out.add(row(k + "movies", POSTER, F_MORE, Ui.tr("Popular movies"), one(d(M).providers(ids).sort(POPULAR).path(1))));
        out.add(row(k + "new", WIDE, F_MORE, Ui.tr("Latest releases"),
                both(d(M).providers(ids).dateLte(today).minVotes(20).sort("primary_release_date.desc"),
                        d(T).providers(ids).dateLte(today).minVotes(20).sort("first_air_date.desc"))));
        out.add(row(k + "top", POSTER, F_MORE, Ui.tr("Top rated"),
                both(d(M).providers(ids).minVotes(200).sort(BY_VOTE), d(T).providers(ids).minVotes(200).sort(BY_VOTE))));
        out.add(row(k + "docs", POSTER, F_MORE, Ui.tr("Documentaries"),
                both(d(M).providers(ids).genres("99").sort(POPULAR), d(T).providers(ids).genres("99").sort(POPULAR))));
        out.add(row(k + "animation", POSTER, F_MORE, Ui.tr("Animation"),
                both(d(M).providers(ids).genres("16").sort(POPULAR), d(T).providers(ids).genres("16").sort(POPULAR))));
        out.add(row(k + "kids", POSTER, F_MORE, Ui.tr("Kids"), one(d(T).providers(ids).genres("10762").sort(POPULAR).path(1))));
        return Collections.unmodifiableList(out);
    }

    /** A genre grid's path for page 1 (TmdbPaths.withPage for the others): sort 0 popular, 1 top rated, 2 newest. */
    static String[] genreGrid(int kind, int genreId, int sort, long now) {
        TmdbPaths.Discover q = d(kind).streamable().genres(String.valueOf(genreId));
        if (sort == 1) q.sort(BY_VOTE).minVotes(200);
        else if (sort == 2) q.sort(kind == T ? "first_air_date.desc" : "primary_release_date.desc").dateLte(TmdbPaths.today(now));
        else q.sort(POPULAR);
        return one(q.path(1));
    }

    private static String ids(int[] ids) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; ids != null && i < ids.length; i++) b.append(i > 0 ? "|" : "").append(ids[i]);
        return b.toString();
    }

    // TMDB's pt-BR genre names (TMDB text: never through Ui.tr). Explore always asks TMDB in pt-BR.
    private static final int[] MOVIE_GENRES = {28, 12, 35, 18, 27, 53, 878, 10749, 80, 10751, 36, 10752};
    private static final String[] MOVIE_GENRE_NAMES = {"Ação", "Aventura", "Comédia", "Drama", "Terror", "Suspense",
            "Ficção científica", "Romance", "Crime", "Família", "História", "Guerra"};
    private static final int[] TV_GENRES = {18, 35, 80, 10759, 10765, 9648, 10766, 10768};
    private static final String[] TV_GENRE_NAMES = {"Drama", "Comédia", "Crime", "Ação e Aventura",
            "Ficção científica e Fantasia", "Mistério", "Novela", "Guerra e Política"};
}
