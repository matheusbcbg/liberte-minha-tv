package io.freemytv;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Explore's TMDB paths (after https://api.tmdb.org/3): plain Java, no android.* or org.json, so tests run on the JDK.
 * Parameters always come in the same order with the page last, so one request has one cache name. Paths never hold
 * the key: Tmdb adds it when it opens the connection.
 */
final class TmdbPaths {
    private TmdbPaths() {}

    static final String REGION = "BR", LANG = "pt-BR", LANG_EN = "en-US", TZ = "America/Rio_Branco";
    static final int MOVIE = 1, TV = 2;

    private static final String BASE = "language=" + LANG + "&include_adult=false";
    /** Subscription, free and with ads: what a TV app streams (rent and buy are only information). */
    private static final String STREAMABLE = "watch_region=" + REGION + "&with_watch_monetization_types=flatrate%7Cfree%7Cads";
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    private static final TimeZone ZONE = TimeZone.getTimeZone(TZ);
    private static final ThreadLocal<SimpleDateFormat> DATE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(ZONE);
            return f;
        }
    };

    private static String kindName(int kind) {
        return kind == TV ? "tv" : "movie";
    }

    /** /trending/{all|movie|tv}/{day|week}. */
    static String trending(String media, boolean week, int page) {
        String m = "movie".equals(media) || "tv".equals(media) ? media : "all";
        return "/trending/" + m + "/" + (week ? "week" : "day") + "?" + BASE + "&page=" + Math.max(1, page);
    }

    /** /movie/{popular|top_rated|now_playing|upcoming} with region=BR; /tv/{on_the_air|airing_today} with the time zone. */
    static String list(int kind, String endpoint, int page) {
        StringBuilder b = new StringBuilder(96).append('/').append(kindName(kind)).append('/').append(endpoint).append('?').append(BASE);
        if (kind == MOVIE) b.append("&region=").append(REGION);
        else if ("on_the_air".equals(endpoint) || "airing_today".equals(endpoint)) b.append("&timezone=").append(TZ);
        return b.append("&page=").append(Math.max(1, page)).toString();
    }

    static Discover discover(int kind) {
        return new Discover(kind);
    }

    /** /discover/{movie|tv}: each setter only fills a field, and path() writes them in one fixed order. */
    static final class Discover {
        private final int kind;
        private boolean streamable;
        private String providers, genres, origin, originalLanguage, sort, dateGte, dateLte, airGte, airLte;
        private int type = -1, minVotes = -1;
        private float minRating = -1;

        Discover(int kind) {
            this.kind = kind == TV ? TV : MOVIE;
        }

        /** watch_region=BR and flatrate|free|ads. */
        Discover streamable() { streamable = true; return this; }
        /** with_watch_providers ("8|119"); implies the region and the monetization types. */
        Discover providers(String idsPipe) { providers = idsPipe == null || idsPipe.isEmpty() ? null : idsPipe; if (providers != null) streamable = true; return this; }
        /** with_genres ("99", "99,80" for both). */
        Discover genres(String ids) { genres = ids; return this; }
        Discover origin(String country) { origin = country; return this; }
        Discover originalLanguage(String lang) { originalLanguage = lang; return this; }
        /** TV type: 2 miniseries, 3 reality. */
        Discover type(int tvType) { type = tvType; return this; }
        Discover sort(String s) { sort = s; return this; }
        Discover minVotes(int n) { minVotes = n; return this; }
        Discover minRating(float r) { minRating = r; return this; }
        /** primary_release_date for movies, first_air_date for shows. */
        Discover dateGte(String d) { dateGte = d; return this; }
        Discover dateLte(String d) { dateLte = d; return this; }
        /** air_date (an episode aired), with timezone=America/Rio_Branco. */
        Discover airGte(String d) { airGte = d; return this; }
        Discover airLte(String d) { airLte = d; return this; }

        String path(int page) {
            StringBuilder b = new StringBuilder(256).append("/discover/").append(kindName(kind)).append('?').append(BASE);
            if (streamable) b.append('&').append(STREAMABLE);
            if (providers != null) b.append("&with_watch_providers=").append(providers.replace("|", "%7C"));
            if (genres != null) b.append("&with_genres=").append(genres);
            if (origin != null) b.append("&with_origin_country=").append(origin);
            if (originalLanguage != null) b.append("&with_original_language=").append(originalLanguage);
            if (type >= 0 && kind == TV) b.append("&with_type=").append(type);
            if (minVotes >= 0) b.append("&vote_count.gte=").append(minVotes);
            if (minRating >= 0) b.append("&vote_average.gte=").append(rating(minRating));
            String date = kind == MOVIE ? "primary_release_date" : "first_air_date";
            if (dateGte != null) b.append('&').append(date).append(".gte=").append(dateGte);
            if (dateLte != null) b.append('&').append(date).append(".lte=").append(dateLte);
            if (kind == TV && airGte != null) b.append("&air_date.gte=").append(airGte);
            if (kind == TV && airLte != null) b.append("&air_date.lte=").append(airLte);
            if (kind == TV && (airGte != null || airLte != null)) b.append("&timezone=").append(TZ);
            if (sort != null) b.append("&sort_by=").append(sort);
            return b.append("&page=").append(Math.max(1, page)).toString();
        }
    }

    /** 7.8 → "7.8", 7 → "7" (never a comma, whatever the JVM's locale). */
    private static String rating(float r) {
        int tenths = Math.round(r * 10);
        return tenths % 10 == 0 ? String.valueOf(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }

    /** Everything the detail page shows, in one request. */
    static String details(int kind, int id) {
        return "/" + kindName(kind) + "/" + id + "?language=" + LANG
                + "&append_to_response=credits,videos,images,watch/providers,recommendations,similar,external_ids,"
                + (kind == TV ? "content_ratings" : "release_dates")
                + "&include_image_language=pt,en,null&include_video_language=pt,en";
    }

    /** The English title, overview and tagline, when TMDB has none in Portuguese. */
    static String detailsEnglish(int kind, int id) {
        return "/" + kindName(kind) + "/" + id + "?language=" + LANG_EN;
    }

    static String season(int tv, int n) {
        return "/tv/" + tv + "/season/" + n + "?language=" + LANG;
    }

    static String person(int id) {
        return "/person/" + id + "?language=" + LANG + "&append_to_response=combined_credits";
    }

    /** The person's English biography, when there is none in Portuguese. */
    static String personEnglish(int id) {
        return "/person/" + id + "?language=" + LANG_EN;
    }

    static String search(String q, int page) {
        String query;
        try {
            query = URLEncoder.encode(q == null ? "" : q.trim(), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            query = "";
        }
        return "/search/multi?query=" + query + "&region=" + REGION + "&language=" + LANG + "&include_adult=false&page=" + Math.max(1, page);
    }

    /**
     * /search/movie (year= only when over 0) or /search/tv (never a year: an episode's date isn't the show's), page 1:
     * WatchMatch's lookup. Memory only, 15 minutes, like every /search/ path.
     */
    static String searchTitle(int kind, String q, int year) {
        StringBuilder b = new StringBuilder(128).append("/search/").append(kindName(kind)).append("?query=").append(encode(q));
        if (kind != TV && year > 0) b.append("&year=").append(year);
        return b.append("&region=").append(REGION).append("&language=").append(LANG).append("&include_adult=false&page=1").toString();
    }

    private static String encode(String q) {
        try {
            return URLEncoder.encode(q == null ? "" : q.trim(), "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    static String genres(int kind) {
        return "/genre/" + kindName(kind) + "/list?language=" + LANG;
    }

    static String providers(int kind) {
        return "/watch/providers/" + kindName(kind) + "?language=" + LANG + "&watch_region=" + REGION;
    }

    /** The same path for another page: its page= replaced (always last), or added. */
    static String withPage(String path, int page) {
        if (path == null) return null;
        int p = Math.max(1, page);
        int at = path.lastIndexOf("page=");
        if (at > 0 && (path.charAt(at - 1) == '&' || path.charAt(at - 1) == '?') && path.indexOf('&', at) < 0) {
            return path.substring(0, at) + "page=" + p;
        }
        return path + (path.indexOf('?') >= 0 ? "&" : "?") + "page=" + p;
    }

    /** yyyy-MM-dd today in Rio Branco. */
    static String today(long nowMs) {
        return DATE.get().format(new Date(nowMs));
    }

    /** yyyy-MM-dd deltaDays from today in Rio Branco (negative: days ago). */
    static String day(long nowMs, int deltaDays) {
        Calendar c = Calendar.getInstance(ZONE, Locale.US);
        c.setTimeInMillis(nowMs);
        c.add(Calendar.DAY_OF_MONTH, deltaDays);
        return DATE.get().format(c.getTime());
    }

    /** How long an answer stays fresh (after that it is shown, then fetched again). */
    static long ttlMs(String path) {
        if (path == null) return 0;
        if (path.startsWith("/search/")) return 15 * 60_000L;
        if (path.startsWith("/trending/")) return path.contains("/week") ? 12 * HOUR : 2 * HOUR;
        if (path.contains("language=" + LANG_EN) || path.startsWith("/person/") || path.startsWith("/genre/")
                || path.startsWith("/watch/providers/")) return 7 * DAY;
        if (path.startsWith("/discover/")) return path.contains("sort_by=vote_average") ? DAY : 6 * HOUR;
        if (path.startsWith("/movie/top_rated") || path.startsWith("/tv/top_rated")) return DAY;
        if (path.startsWith("/movie/popular") || path.startsWith("/tv/popular") || path.startsWith("/movie/now_playing")
                || path.startsWith("/movie/upcoming") || path.startsWith("/tv/on_the_air") || path.startsWith("/tv/airing_today")) return 6 * HOUR;
        if (path.startsWith("/movie/") || path.startsWith("/tv/")) return DAY;   // details and seasons
        return 6 * HOUR;
    }

    /** What kind of request it is, the only part of a path ever logged. */
    static String label(String path) {
        if (path == null) return "none";
        if (path.startsWith("/search/")) return "search";
        if (path.startsWith("/trending/")) return "trending";
        if (path.startsWith("/discover/")) return "discover";
        if (path.startsWith("/genre/")) return "genres";
        if (path.startsWith("/watch/providers/")) return "providers";
        if (path.startsWith("/person/")) return path.contains("language=" + LANG_EN) ? "person-en" : "person";
        if (path.startsWith("/movie/") || path.startsWith("/tv/")) {
            if (path.contains("/season/")) return "season";
            int slash = path.indexOf('/', 1), end = path.indexOf('?');
            String second = slash < 0 ? "" : path.substring(slash + 1, end < 0 ? path.length() : end);
            if (!second.isEmpty() && Character.isDigit(second.charAt(0))) return path.contains("language=" + LANG_EN) ? "details-en" : "details";
            return "list";
        }
        return "tmdb";
    }
}
