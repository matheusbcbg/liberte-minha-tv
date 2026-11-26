package io.freemytv;

public final class BrowseBackdropTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void eq(String actual, String expected, String reason) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(same, reason + ": expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        // Null card.
        eq(BrowsePage.backdropOf(null), null, "null card");

        // TMDB title card (Trending, Streaming rows): poster row uses t.poster as c.image, backdrop still comes from t.backdrop.
        ExploreModel.Title movie = new ExploreModel.Title();
        movie.kind = ExploreModel.MOVIE;
        movie.id = 475557;
        movie.title = "Coringa";
        movie.poster = "/coringa-poster.jpg";
        movie.backdrop = "/coringa-backdrop.jpg";
        Card posterTitle = new Card();
        posterTitle.type = Card.TITLE;
        posterTitle.ref = movie;
        posterTitle.image = movie.poster;
        posterTitle.imageSpec = ExploreImages.POSTER;
        eq(BrowsePage.backdropOf(posterTitle), "/coringa-backdrop.jpg", "TMDB poster title uses Title.backdrop");

        // Want to Watch (Quero assistir): uses Watchlist.Item.backdrop, never falling back to poster.
        Watchlist.Item saved = new Watchlist.Item(ExploreModel.MOVIE, 475557, "Coringa", "/quero-backdrop.jpg", "/quero-poster.jpg", 2019, 1L);
        Card savedCard = new Card();
        savedCard.type = Card.TITLE;
        savedCard.ref = saved;
        savedCard.image = saved.backdrop;
        savedCard.imageSpec = ExploreImages.WIDE;
        eq(BrowsePage.backdropOf(savedCard), "/quero-backdrop.jpg", "Watchlist card with backdrop");

        Watchlist.Item posterOnly = new Watchlist.Item(ExploreModel.MOVIE, 10, "Sem arte", null, "/only-poster.jpg", 2020, 2L);
        Card posterOnlyCard = new Card();
        posterOnlyCard.type = Card.TITLE;
        posterOnlyCard.ref = posterOnly;
        posterOnlyCard.image = posterOnly.poster;
        posterOnlyCard.imageSpec = ExploreImages.WIDE;
        eq(BrowsePage.backdropOf(posterOnlyCard), null, "Watchlist card without backdrop returns null");

        // Continue Watching (Continuar assistindo): matched to TMDB via WatchMatch -> Card.WATCH with WIDE TMDB backdrop.
        WatchNext.Item watching = new WatchNext.Item("com.wbd.stream", null, null);
        watching.title = "Coringa";
        Card matchedWatch = new Card();
        matchedWatch.type = Card.WATCH;
        matchedWatch.ref = watching;
        matchedWatch.kind = ExploreModel.MOVIE;
        matchedWatch.id = 475557;
        matchedWatch.image = "/coringa-watch-backdrop.jpg";
        matchedWatch.imageSpec = ExploreImages.WIDE;
        eq(BrowsePage.backdropOf(matchedWatch), "/coringa-watch-backdrop.jpg", "matched Continue Watching card (Coringa)");

        // Unmatched Continue Watching card: no TMDB backdrop.
        Card unmatchedWatch = new Card();
        unmatchedWatch.type = Card.WATCH;
        unmatchedWatch.ref = watching;
        eq(BrowsePage.backdropOf(unmatchedWatch), null, "unmatched Continue Watching card");

        // Continue Watching card with non-TMDB image path or wrong spec: rejected.
        Card badPathWatch = new Card();
        badPathWatch.type = Card.WATCH;
        badPathWatch.ref = watching;
        badPathWatch.image = "content://com.wbd.stream/art/1";
        badPathWatch.imageSpec = ExploreImages.WIDE;
        eq(BrowsePage.backdropOf(badPathWatch), null, "Continue Watching card with non-TMDB uri");

        Card wrongSpecWatch = new Card();
        wrongSpecWatch.type = Card.WATCH;
        wrongSpecWatch.ref = watching;
        wrongSpecWatch.image = "/coringa-poster.jpg";
        wrongSpecWatch.imageSpec = ExploreImages.POSTER;
        eq(BrowsePage.backdropOf(wrongSpecWatch), null, "Continue Watching card with non-WIDE spec");

        // Non-title cards (service logo, genre, see all, grant) never drive the backdrop.
        Card service = new Card();
        service.type = Card.SERVICE;
        service.image = "/provider-logo.png";
        service.imageSpec = ExploreImages.PROVIDER;
        eq(BrowsePage.backdropOf(service), null, "service card logo is not a backdrop");

        Card more = new Card();
        more.type = Card.MORE;
        eq(BrowsePage.backdropOf(more), null, "See all card");

        Card grant = new Card();
        grant.type = Card.GRANT;
        eq(BrowsePage.backdropOf(grant), null, "Grant card");

        System.out.println("BrowseBackdropTest: " + assertions + " checks passed");
    }
}
