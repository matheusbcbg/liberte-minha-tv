package io.freemytv;

/** Explore's plain data: built on workers, immutable once delivered to the main thread. */
final class ExploreModel {
    private ExploreModel() {}

    static final int MOVIE = 1, TV = 2, PERSON = 3;
    static final int FLATRATE = 0, FREE = 1, ADS = 2, RENT = 3, BUY = 4;

    static final class Title {
        int kind, id, year, votes;
        float vote, popularity;
        String title, original, overview, poster, backdrop, profile, date, character;
        int[] genres;

        /** kind:id, the dedupe key. */
        String key() {
            return kind + ":" + id;
        }

        /** "movie", "tv" or "person". */
        String kindPath() {
            return kind == TV ? "tv" : kind == PERSON ? "person" : "movie";
        }
    }

    static final class Page {
        Title[] items;
        int page, totalPages;
    }

    static final class Credit {
        int id;
        String name, character, profile;
    }

    static final class Video {
        String key, name, lang;
    }

    static final class Season {
        int number, episodes;
        String name, poster, airDate;
    }

    static final class Episode {
        int number, runtime;
        String name, overview, still, airDate;
    }

    static final class SeasonDetails {
        int tv, number;
        Episode[] episodes;
    }

    static final class Offer {
        int type, providerId, priority;
        String name, logo;
        ProviderTable.Service service;   // null = unknown
        boolean installed;
    }

    static final class Details {
        Title t;
        String tagline, status, cert, logo, backdrop, originalLanguage, countries, releaseBr, director, creators, network;
        int runtime, seasons, episodes, lastYear;
        String[] genreNames;
        Credit[] cast;
        Video trailer;
        Offer[] offers;                  // sorted: installed streamable, other streamable, rent, buy
        Title[] recommended, similar;
        Season[] seasonList;
        boolean englishFilled;
    }

    static final class Person {
        int id;
        String name, profile, biography, department;
        Title[] credits;
    }

    static final class Genre {
        int id, kind;
        String name;
    }

    static final class Provider {
        int id, priority;
        String name, logo;
    }
}
