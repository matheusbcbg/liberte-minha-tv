package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * A root tab (Início, Filmes, Séries, Documentários, Animação, Streamings) or a service's page: the hero carousel and
 * the rows from Catalog, loaded lazily as they come near the screen.
 */
final class BrowsePage extends ExplorePage implements BrowseStack.Host {
    private static final long ADVANCE_MS = 8000, FOLLOW_MS = 700;
    private static final long[] RETRY_MS = {10_000, 30_000, 120_000};
    private static final int HERO_MAX = 8;

    private final int page;
    private final int[] providerIds;
    private final String providerName, providerLogo;
    private final int providerColor;
    private final ExploreImages images;
    private final Tmdb tmdb;

    private Layer layer;
    private HeroView hero;
    private BrowseStack stack;
    private Notice notice;
    private Label title;
    private ProviderHeader header;
    private Catalog.RowSpec heroSpec;
    private final List<Row> rows = new ArrayList<>();
    private ExploreModel.Title[] heroItems = new ExploreModel.Title[0];
    private ExploreModel.Details[] heroDetails = new ExploreModel.Details[0];
    private final ArrayList<Tmdb.Ticket> heroTickets = new ArrayList<>();
    private boolean heroFallback, heroFailed;
    /** How often each title already shows on this page (at most twice). */
    private final HashMap<String, Integer> seen = new HashMap<>();
    private final HashMap<Row, String[]> counted = new HashMap<>();
    private final HashSet<Row> loadingMore = new HashSet<>();
    private int zone = Z_TOPBAR;
    private boolean keyMode, resumed, started, offlineShown, failedPage;
    private String builtIds = "";
    private int retries;
    private String followPath;
    private final Runnable advance = ExploreView.guard(host, "hero timer", this::advance);
    private final Runnable follow = ExploreView.guard(host, "follow", this::follow);
    private final Runnable retry = ExploreView.guard(host, "retry", this::retryPage);
    private final RectF rect = new RectF();
    private float heroBand0;

    private BrowsePage(ExploreView v, int page, int[] ids, String name, String logo, int color) {
        super(v);
        this.page = page;
        providerIds = ids;
        providerName = name;
        providerLogo = logo;
        providerColor = color;
        images = v.images();
        tmdb = Tmdb.get(v.getContext());
    }

    static BrowsePage root(ExploreView v, int page) {
        return new BrowsePage(v, page, null, null, null, 0);
    }

    static BrowsePage provider(ExploreView v, int[] ids, String name, String logo, int color) {
        return new BrowsePage(v, -1, ids == null ? new int[0] : ids, name, logo, color);
    }

    @Override ViewGroup layer() {
        if (layer == null) layer = new Layer(context(), k);
        return layer;
    }

    @Override void build() {
        if (started) return;
        started = true;
        Context c = context();
        layer();
        if (page == Catalog.SERVICES) {
            title = new Label(c, Ui.text(Ui.displaySemibold, 52 * k, CardPainter.INK), 150 * k);
            layer.add(title, 96, 0, 1728, 180);
            title.set(Ui.tr("explore|Services"));
        }
        if (page < 0) {
            header = new ProviderHeader(c);
            layer.add(header, 96, 110, 1200, 110);
        }
        hero = new HeroView(c, images);
        layer.add(hero, 0, 0, 1000, 900);
        stack = new BrowseStack(layer, layer.getChildCount(), c, host.painter, images, host.watchArt(), this);
        notice = new Notice(c, k, true);
        layer.add(notice, 460, page == Catalog.HOME ? 600 : 380, 1000, 320);
        rebuild();
    }

    /** Everything again from Catalog (a new key, new installed services, a page that failed). */
    private void rebuild() {
        generation++;
        for (Tmdb.Ticket t : heroTickets) t.cancel();
        heroTickets.clear();
        for (Row r : rows) if (r.ticket != null) r.ticket.cancel();
        rows.clear();
        seen.clear();
        counted.clear();
        loadingMore.clear();
        heroItems = new ExploreModel.Title[0];
        heroDetails = new ExploreModel.Details[0];
        heroFallback = heroFailed = failedPage = false;
        BrProviders.Snapshot snap = BrProviders.snapshot();
        builtIds = snap.installedIds() == null ? "" : snap.installedIds();
        long now = System.currentTimeMillis();
        keyMode = Tmdb.hasKey();
        List<Catalog.RowSpec> specs;
        try {
            heroSpec = !keyMode ? null : page >= 0 ? Catalog.hero(page, snap, now) : Catalog.providerHero(providerIds);
            specs = page >= 0 ? Catalog.rows(page, snap, now) : Catalog.providerRows(providerIds, now);
        } catch (RuntimeException e) {
            Safe.log("catalog", e);
            heroSpec = null;
            specs = new ArrayList<>();
        }
        if (specs == null) specs = new ArrayList<>();
        for (Catalog.RowSpec s : specs) {
            if (s == null) continue;
            if (!keyMode && s.source != Catalog.SRC_WATCH && s.source != Catalog.SRC_MY_SERVICES) continue;
            rows.add(new Row(s.key, s.style, s, s.title));
        }
        hero.setVisibility(heroSpec != null ? View.VISIBLE : View.GONE);
        hero.setItems(new ExploreModel.Title[0], null);
        if (heroSpec != null) {
            heroBand0 = 526 * k;
            stack.setGeometry(96 * k, 866 * k, heroBand0, 116 * k);
        } else {
            float first = page == Catalog.SERVICES ? 196 : page < 0 ? 250 : 150;
            stack.setGeometry(96 * k, first * k, first * k, 116 * k);
        }
        stack.setRows(rows);
        if (!keyMode) notice.show(Tmdb.rejected() ? Ui.tr("The TMDB key was refused. Check it in Home Settings.")
                : Ui.tr("Add a TMDB key in Home Settings to explore."));
        else notice.show(null);
        if (zone == Z_HERO && heroSpec == null) zone = Z_TOPBAR;
        if (zone == Z_ROWS && !stack.hasFocus()) zone = Z_TOPBAR;
        loadHero();
        if (host.isCurrent(this)) applyBackground();
    }

    void resetToTop() {
        stack.leave();
        stack.scrollHome();
        if (heroSpec != null) {
            zone = Z_HERO;
            hero.setIndex(0, false);
            hero.setFocus(0);
        } else {
            zone = Z_TOPBAR;
            hero.setFocus(-1);
            host.focusTopBar();
        }
        applyBackground();
    }

    // MARK: - Hero

    private void loadHero() {
        if (heroSpec == null) return;
        final int gen = generation;
        String[] paths = heroSpec.paths(1);
        ExploreModel.Page cached = tmdb.peek(paths);
        if (cached != null) deliverHero(cached, Tmdb.OK, false);
        heroTickets.add(tmdb.page(paths, Tmdb.NOW, (value, status, fresh) -> {
            if (!stale(gen)) deliverHero(value, status, false);
        }));
    }

    /** fromFallback: the answer of the trending fallback (Início), not of the hero's own query. */
    private void deliverHero(ExploreModel.Page value, int status, boolean fromFallback) {
        int limit = heroSpec != null && heroSpec.limit > 0 ? Math.min(heroSpec.limit, HERO_MAX) : HERO_MAX;
        ArrayList<ExploreModel.Title> list = new ArrayList<>();
        if (value != null && value.items != null) {
            for (ExploreModel.Title t : value.items) {
                if (t == null || t.backdrop == null || t.kind == ExploreModel.PERSON) continue;
                list.add(t);
                if (list.size() >= limit) break;
            }
        }
        noteStatus(status);
        if (list.isEmpty()) {
            if (value != null && page == Catalog.HOME && !heroFallback) {
                // Nothing on the TV's services (or none known): today's trending instead.
                heroFallback = true;
                final int gen = generation;
                heroTickets.add(tmdb.page(new String[] {TmdbPaths.trending("all", false, 1)}, Tmdb.NOW, (v, s, f) -> {
                    if (!stale(gen)) deliverHero(v, s, true);
                }));
                return;
            }
            if (heroFallback && !fromFallback) return;   // the fallback is on its way: only its answer hides the hero
            if (heroItems.length == 0 && status != Tmdb.OK) {
                heroFailed = true;
                checkPageFailed();
                if (zone == Z_TOPBAR && !host.topFocused() && host.isCurrent(this)) takeFocus(true);
            }
            if (heroItems.length == 0 && value != null && status == Tmdb.OK) {
                heroSpec = null;   // nothing to show: the rows move up
                hero.setVisibility(View.GONE);
                float first = page < 0 ? 250 : 150;
                stack.setGeometry(96 * k, first * k, first * k, 116 * k);
                if (zone == Z_HERO || (zone == Z_TOPBAR && !host.topFocused() && host.isCurrent(this))) takeFocus(true);
            }
            return;
        }
        heroFailed = false;
        ExploreModel.Title[] items = list.toArray(new ExploreModel.Title[0]);
        if (sameItems(items)) return;
        heroItems = items;
        heroDetails = new ExploreModel.Details[items.length];
        for (int i = 0; i < items.length; i++) heroDetails[i] = tmdb.peekDetails(items[i].kind, items[i].id);
        hero.setItems(items, heroDetails);
        hero.setFocus(zone == Z_HERO ? 0 : -1);
        final int gen = generation;
        for (int i = 0; i < items.length; i++) {
            final int at = i;
            final String key = items[i].key();
            heroTickets.add(tmdb.details(items[i].kind, items[i].id, i == 0 ? Tmdb.NOW : Tmdb.IDLE, (d, s, f) -> {
                if (stale(gen) || d == null || at >= heroItems.length || !heroItems[at].key().equals(key)) return;
                heroDetails[at] = d;
                hero.setDetails(at, d);
                host.wake();
            }));
        }
        // Rows already in drop the hero's titles from now on; the next picture is fetched ahead for the carousel.
        if (items.length > 1) images.want(ExploreImages.HERO, items[1].backdrop, ExploreImages.SOON);
        if (host.isCurrent(this) && (zone == Z_HERO || host.topFocused() || zone == Z_TOPBAR)) applyBackground();
        if (zone == Z_TOPBAR && !host.topFocused() && host.isCurrent(this)) takeFocus(true);
        scheduleAdvance();
        host.wake();
    }

    private boolean sameItems(ExploreModel.Title[] items) {
        if (items.length != heroItems.length) return false;
        for (int i = 0; i < items.length; i++) if (!items[i].key().equals(heroItems[i].key())) return false;
        return true;
    }

    private String heroPath() {
        ExploreModel.Title t = hero == null ? null : hero.item(hero.index());
        return t == null ? null : t.backdrop;
    }

    private void scheduleAdvance() {
        host.handler().removeCallbacks(advance);
        if (resumed && hero != null && hero.count() > 1) host.handler().postDelayed(advance, ADVANCE_MS);
    }

    /** Every 8 s while the hero or the top bar has the focus, no key came for 8 s and the next picture is decoded. */
    private void advance() {
        if (!resumed || !host.isCurrent(this) || hero.count() < 2) return;
        long idle = SystemClock.uptimeMillis() - host.lastKeyAt();
        boolean top = zone == Z_HERO || host.topFocused() || zone == Z_TOPBAR;
        if (top && idle >= ADVANCE_MS && stack.scroll() < 1) {
            int next = RowMath.wrap(hero.index() + 1, hero.count());
            ExploreModel.Title t = hero.item(next);
            if (t != null && (t.backdrop == null || images.get(ExploreImages.HERO, t.backdrop) != null)) {
                hero.setIndex(next, true);
                if (t.backdrop != null) host.backdrop().show(t.backdrop, true);
                ExploreModel.Title after = hero.item(RowMath.wrap(next + 1, hero.count()));
                if (after != null && after.backdrop != null) images.want(ExploreImages.HERO, after.backdrop, ExploreImages.SOON);
                host.wake();
            } else if (t != null) {
                images.want(ExploreImages.HERO, t.backdrop, ExploreImages.SOON);
            }
        }
        host.handler().postDelayed(advance, idle >= ADVANCE_MS ? ADVANCE_MS : ADVANCE_MS - idle);
    }

    private void heroMove(int dx) {
        int n = hero.buttons(), b = hero.focus() + dx;
        if (b >= 0 && b < n) {
            hero.setFocus(b);
            host.click().move();
            return;
        }
        if (hero.count() < 2) return;
        hero.setIndex(hero.index() + dx, true);
        hero.setFocus(dx > 0 ? 0 : Math.max(0, hero.buttons() - 1));
        String path = heroPath();
        if (path != null) host.backdrop().show(path, true);
        ExploreModel.Title next = hero.item(RowMath.wrap(hero.index() + dx, hero.count()));
        if (next != null && next.backdrop != null) images.want(ExploreImages.HERO, next.backdrop, ExploreImages.SOON);
        host.click().move();
        scheduleAdvance();
    }

    private void heroActivate(boolean longPress) {
        int i = hero.index();
        ExploreModel.Title t = hero.item(i);
        if (t == null) return;
        ExploreModel.Details d = hero.details(i);
        if (d == null) d = tmdb.peekDetails(t.kind, t.id);
        ExploreModel.Offer o = hero.offer(i);
        if (o == null && d != null) o = ExploreView.bestOffer(d);
        int b = Math.max(0, hero.focus());
        if ((longPress || hero.isWatchButton(b)) && o != null && d != null) {
            hero.buttonBounds(Math.max(0, b), rect);
            host.watch(d, o, host, rect);
            return;
        }
        host.click().open();
        host.push(new DetailPage(host, t.kind, t.id, false).withTitle(t));
    }

    // MARK: - Rows

    @Override public void onNeedRow(Row r, int priority) {
        if (r.state != Row.EMPTY) return;
        load(r, priority, 1);
    }

    @Override public void onNeedMore(Row r) {
        if (r.spec == null || (r.spec.flags & Catalog.F_MORE) != 0 || r.spec.source != Catalog.SRC_TMDB) return;
        if (loadingMore.contains(r) || r.page >= Math.min(r.totalPages, Math.max(1, r.spec.maxPages))) return;
        loadingMore.add(r);
        load(r, Tmdb.NOW, r.page + 1);
    }

    private void load(final Row r, int priority, final int pageNo) {
        final int gen = generation;
        Catalog.RowSpec s = r.spec;
        if (s == null) return;
        if (pageNo == 1) {
            r.state = Row.LOADING;
            r.askedAt = SystemClock.uptimeMillis();
        }
        switch (s.source) {
            case Catalog.SRC_WATCH:
                WatchFeed.load(context(), (items, allowed) -> {
                    if (!stale(gen)) deliverWatch(r, items, allowed);
                });
                break;
            case Catalog.SRC_WATCHLIST:
                deliverWatchlist(r, Watchlist.cached());
                Watchlist.load(context(), items -> {
                    if (!stale(gen)) deliverWatchlist(r, items);
                });
                break;
            case Catalog.SRC_GENRES_MOVIE:
            case Catalog.SRC_GENRES_TV:
                r.ticket = tmdb.genres(s.source == Catalog.SRC_GENRES_TV ? ExploreModel.TV : ExploreModel.MOVIE, (g, st, f) -> {
                    if (!stale(gen)) deliverGenres(r, g, st);
                });
                break;
            case Catalog.SRC_MY_SERVICES:
                deliverMyServices(r, null);
                if (keyMode) {
                    r.ticket = tmdb.providers((p, st, f) -> {
                        if (!stale(gen) && p != null) deliverMyServices(r, p);
                    });
                }
                break;
            case Catalog.SRC_OTHER_SERVICES:
                r.ticket = tmdb.providers((p, st, f) -> {
                    if (!stale(gen)) deliverOtherServices(r, p, st);
                });
                break;
            default: {
                String[] paths = s.paths(pageNo);
                if (pageNo == 1) {
                    ExploreModel.Page cached = tmdb.peek(paths);
                    if (cached != null) deliverPage(r, 1, cached, Tmdb.OK);
                }
                r.ticket = tmdb.page(paths, priority, (value, status, fresh) -> {
                    if (stale(gen)) return;
                    if (pageNo > 1) loadingMore.remove(r);
                    deliverPage(r, pageNo, value, status);
                });
            }
        }
    }

    private void deliverPage(Row r, int pageNo, ExploreModel.Page value, int status) {
        noteStatus(status);
        if ((status == Tmdb.NO_KEY || status == Tmdb.REJECTED) && keyMode && !Tmdb.hasKey()) {
            // The key went away or was refused: the page again in its no-key form (posted: never inside a load).
            final int gen = generation;
            host.handler().post(ExploreView.guard(host, "no key", () -> {
                if (!stale(gen) && keyMode && !Tmdb.hasKey()) rebuild();
            }));
            return;
        }
        if (value == null || value.items == null) {
            if (pageNo == 1 && r.count == 0) {
                r.state = status == Tmdb.OK || status == Tmdb.NOT_FOUND ? Row.READY : Row.FAILED;
                stack.rowChanged(r);
                checkPageFailed();
            }
            return;
        }
        Catalog.RowSpec s = r.spec;
        int flags = s == null ? 0 : s.flags;
        int limit = s != null && s.limit > 0 ? s.limit : Integer.MAX_VALUE;
        String today = (flags & Catalog.F_FUTURE_ONLY) != 0 ? TmdbPaths.today(System.currentTimeMillis()) : null;
        HashSet<String> heroKeys = new HashSet<>();
        if ((flags & Catalog.F_DROP_HERO) != 0) for (ExploreModel.Title t : heroItems) heroKeys.add(t.key());
        if (pageNo == 1) uncount(r);
        ArrayList<Card> cards = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        int base = pageNo == 1 ? 0 : r.count;
        for (ExploreModel.Title t : value.items) {
            if (t == null || base + cards.size() >= limit) continue;
            if (t.kind == ExploreModel.PERSON) continue;
            String key = t.key();
            if (heroKeys.contains(key)) continue;
            if (today != null && !today.isEmpty() && (t.date == null || t.date.compareTo(today) <= 0)) continue;
            boolean wide = r.style == Catalog.WIDE;
            if ((wide || (flags & Catalog.F_NEEDS_BACKDROP) != 0) && t.backdrop == null) continue;
            Integer n = seen.get(key);
            if (n != null && n >= 2) continue;
            cards.add(titleCard(t, r.style, base + cards.size() + 1, today != null));
            keys.add(key);
        }
        for (String key : keys) {
            Integer n = seen.get(key);
            seen.put(key, n == null ? 1 : n + 1);
        }
        String[] had = pageNo == 1 ? null : counted.get(r);
        String[] all = new String[(had == null ? 0 : had.length) + keys.size()];
        if (had != null) System.arraycopy(had, 0, all, 0, had.length);
        for (int i = 0; i < keys.size(); i++) all[(had == null ? 0 : had.length) + i] = keys.get(i);
        counted.put(r, all);
        Card[] arr = cards.toArray(new Card[0]);
        if (pageNo == 1) r.set(arr, arr.length);
        else r.append(arr, arr.length);
        r.page = Math.max(r.page, value.page > 0 ? value.page : pageNo);
        r.totalPages = Math.max(1, value.totalPages);
        if (pageNo == 1 && (flags & Catalog.F_MORE) != 0 && value.totalPages > 1 && r.count > 0) {
            Card more = new Card();
            more.type = Card.MORE;
            more.title = Ui.tr("See all");
            r.append(new Card[] {more}, 1);
        }
        r.state = Row.READY;
        failedPage = false;
        notice.show(keyMode ? null : notice.text());
        stack.rowChanged(r);
    }

    private void uncount(Row r) {
        String[] had = counted.remove(r);
        if (had == null) return;
        for (String key : had) {
            Integer n = seen.get(key);
            if (n == null) continue;
            if (n <= 1) seen.remove(key);
            else seen.put(key, n - 1);
        }
    }

    static Card titleCard(ExploreModel.Title t, int style, int rank, boolean opens) {
        Card c = new Card();
        c.type = Card.TITLE;
        c.kind = t.kind;
        c.id = t.id;
        c.ref = t;
        c.rank = rank;
        c.title = t.title;
        if (style == Catalog.WIDE) {
            c.image = t.backdrop;
            c.imageSpec = ExploreImages.WIDE;
        } else {
            c.image = t.poster;
            c.imageSpec = ExploreImages.POSTER;
        }
        if (opens && t.date != null) {
            String d = ExploreView.shortDate(t.date);
            c.sub = d.isEmpty() ? "" : Ui.trf("Opens {date}", "date", d);
        } else {
            String kind = t.kind == ExploreModel.TV ? Ui.tr("Series") : Ui.tr("Movie");
            c.sub = t.year > 0 ? kind + " · " + t.year : kind;
        }
        return c;
    }

    private void deliverWatch(Row r, List<WatchNext.Item> items, boolean allowed) {
        if (!allowed) {
            Card g = new Card();
            g.type = Card.GRANT;
            g.title = Ui.tr("Allow Continue Watching");
            g.sub = Ui.tr("Press OK so Explore can see what you were watching in your apps.");
            r.set(new Card[] {g}, 1);
        } else {
            int n = items == null ? 0 : Math.min(items.size(), WatchNext.MAX);
            Card[] cards = new Card[n];
            for (int i = 0; i < n; i++) cards[i] = watchCard(context(), items.get(i));
            r.set(cards, n);
            wantLogos(r);
            if (n > 0) {
                // Each item matched to TMDB (once, cached): its still and title logo replace the app's banner.
                final int gen = generation;
                final List<WatchNext.Item> list = items;
                WatchMatch.get(context()).fill(list, ExploreView.guard(host, "watch match", () -> {
                    if (stale(gen) || r.count == 0) return;
                    int m = Math.min(list.size(), WatchNext.MAX);
                    Card[] again = new Card[m];
                    for (int i = 0; i < m; i++) again[i] = watchCard(context(), list.get(i));
                    r.set(again, m);
                    wantLogos(r);
                    stack.rowChanged(r);
                    syncFollow();
                }));
            }
        }
        r.state = Row.READY;
        stack.rowChanged(r);
        syncFollow();
    }

    /** Quero assistir, newest first (an empty list hides the row). */
    private void deliverWatchlist(Row r, List<Watchlist.Item> items) {
        int n = items == null ? 0 : items.size();
        Card[] cards = new Card[n];
        for (int i = 0; i < n; i++) cards[i] = Watchlist.card(items.get(i));
        r.set(cards, n);
        r.state = Row.READY;
        stack.rowChanged(r);
    }

    @Override void watchlistChanged() {
        for (Row r : rows) {
            if (r.spec != null && r.spec.source == Catalog.SRC_WATCHLIST) deliverWatchlist(r, Watchlist.cached());
        }
    }

    /** The title logos of the matched Continue Watching cards (their stills come with the row's own pictures). */
    private void wantLogos(Row r) {
        for (int i = 0; i < r.count; i++) {
            Card c = r.cards[i];
            if (c != null && c.logo != null) images.want(ExploreImages.LOGO, c.logo, i < 5 ? ExploreImages.NOW : ExploreImages.SOON);
        }
    }

    /**
     * A Continue Watching card (Explore's row and Início's Up Next): the title, then "T1, E4 · 23 min restantes · Max";
     * matched to TMDB (WatchMatch, memory only), the film's or show's still and its title logo instead of the app's
     * picture. UI thread.
     */
    static Card watchCard(Context ctx, WatchNext.Item it) {
        Card c = new Card();
        c.type = Card.WATCH;
        c.ref = it;
        c.title = it.title == null ? "" : it.title;
        c.sub = watchLine(it);
        c.progress = it.progress();
        WatchMatch.Match m = ctx == null ? null : WatchMatch.get(ctx).peek(it);
        if (m != null && m.backdrop != null && m.backdrop.startsWith("/")) {
            c.image = m.backdrop;
            c.imageSpec = ExploreImages.WIDE;
            c.kind = m.kind;
            c.id = m.id;
            c.logo = m.logo != null && m.logo.startsWith("/") ? m.logo : null;
        } else if (m != null) {
            c.kind = m.kind;
            c.id = m.id;
        }
        return c;
    }

    /** "T1, E4 · 23 min restantes · Max", "1 h 12 min restantes · Max", "S2 E5 · Max" or "Max". */
    static String watchLine(WatchNext.Item it) {
        StringBuilder meta = new StringBuilder();
        long min = it.progress() >= 0 ? Math.max(1, (it.duration - it.position) / 60_000) : -1;
        String s = number(it.season), e = number(it.episodeNumber);
        boolean episode = !it.movie();
        if (min > 0 && episode && s != null && e != null) {
            meta.append(min >= 60 ? Ui.trf("S{s}, E{e} · {h} h {m} min left", "s", s, "e", e, "h", min / 60, "m", min % 60)
                    : Ui.trf("S{s}, E{e} · {n} min left", "s", s, "e", e, "n", min));
        } else {
            if (it.episode != null) meta.append(it.episode);
            if (min > 0) {
                String left = min >= 60 ? Ui.trf("{h} h {m} min left", "h", min / 60, "m", min % 60) : Ui.trf("{n} min left", "n", min);
                meta.append(meta.length() > 0 ? " · " : "").append(left);
            }
        }
        if (it.app != null) meta.append(meta.length() > 0 ? " · " : "").append(it.app);
        return meta.toString();
    }

    /** A season or episode number as the app wrote it ("4", " 04 "), trimmed; null when it isn't a plain number. */
    private static String number(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 4) return null;
        for (int i = 0; i < t.length(); i++) if (!Character.isDigit(t.charAt(i))) return null;
        try {
            return Integer.toString(Integer.parseInt(t));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void deliverGenres(Row r, ExploreModel.Genre[] genres, int status) {
        noteStatus(status);
        int n = genres == null ? 0 : genres.length;
        Card[] cards = new Card[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            ExploreModel.Genre g = genres[i];
            if (g == null || g.name == null) continue;
            Card c = new Card();
            c.type = Card.GENRE;
            c.id = g.id;
            c.kind = g.kind;
            c.title = g.name;
            c.ref = g;
            cards[m++] = c;
        }
        r.set(cards, m);
        r.state = genres == null && status != Tmdb.OK ? Row.FAILED : Row.READY;
        stack.rowChanged(r);
    }

    private void deliverMyServices(Row r, ExploreModel.Provider[] providers) {
        List<ProviderTable.Service> list;
        try {
            list = BrProviders.snapshot().installedServices();
        } catch (RuntimeException e) {
            list = new ArrayList<>();
        }
        if (list == null) list = new ArrayList<>();
        Card[] cards = new Card[list.size()];
        int m = 0;
        for (ProviderTable.Service s : list) {
            if (s == null) continue;
            Card c = new Card();
            c.type = Card.SERVICE;
            c.title = s.label;
            c.color = s.color;
            c.ref = s;
            c.id = s.ids != null && s.ids.length > 0 ? s.ids[0] : 0;
            c.imageSpec = ExploreImages.PROVIDER;
            c.image = logoFor(s, providers);
            cards[m++] = c;
        }
        r.set(cards, m);
        r.state = Row.READY;
        stack.rowChanged(r);
    }

    private static String logoFor(ProviderTable.Service s, ExploreModel.Provider[] providers) {
        if (providers == null || s.ids == null) return null;
        for (int id : s.ids) for (ExploreModel.Provider p : providers) if (p != null && p.id == id && p.logo != null) return p.logo;
        return null;
    }

    private void deliverOtherServices(Row r, ExploreModel.Provider[] providers, int status) {
        noteStatus(status);
        if (providers == null) {
            r.state = status == Tmdb.OK ? Row.READY : Row.FAILED;
            stack.rowChanged(r);
            return;
        }
        BrProviders.Snapshot snap = BrProviders.snapshot();
        ArrayList<Card> cards = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        for (ExploreModel.Provider p : providers) {
            if (p == null || cards.size() >= 20) continue;
            ProviderTable.Service s = ProviderTable.match(p.id, p.name);
            if (s != null && snap.installed(s.key)) continue;
            if (s != null && !keys.add(s.key)) continue;
            Card c = new Card();
            c.type = Card.SERVICE;
            c.id = p.id;
            c.title = s != null ? s.label : p.name;
            c.color = s != null ? s.color : 0xFF2C2C2E;
            c.ref = p;
            c.dim = true;
            c.imageSpec = ExploreImages.PROVIDER;
            c.image = p.logo;
            cards.add(c);
        }
        Card[] arr = cards.toArray(new Card[0]);
        r.set(arr, arr.length);
        r.state = Row.READY;
        stack.rowChanged(r);
    }

    private void noteStatus(int status) {
        if (status == Tmdb.OFFLINE && !offlineShown && host.isCurrent(this)) {
            offlineShown = true;
            host.toast(Ui.tr("No connection. Showing what was saved."));
        }
    }

    /** Nothing at all came (no cache, every load failed): the centred retry message, and retries after 10 s, 30 s, 2 min. */
    private void checkPageFailed() {
        if (!keyMode || failedPage) return;
        if (heroItems.length > 0) return;
        for (Row r : rows) {
            if (r.spec != null && (r.spec.source == Catalog.SRC_WATCH || r.spec.source == Catalog.SRC_MY_SERVICES)) continue;
            if (r.state == Row.READY && r.count > 0) return;
            if (r.state == Row.LOADING) return;
        }
        if (heroSpec != null && !heroFailed) return;
        failedPage = true;
        notice.show(Ui.tr("Couldn't load. Press OK to try again."));
        if (retries < RETRY_MS.length) host.handler().postDelayed(retry, RETRY_MS[retries++]);
    }

    private void retryPage() {
        if (!failedPage) return;
        host.handler().removeCallbacks(retry);
        rebuild();
    }

    @Override public void onActivate(Row r, Card c, RectF from) {
        switch (c.type) {
            case Card.TITLE:
                host.click().open();
                host.push(new DetailPage(host, c.kind, c.id, false).withTitle(c.ref instanceof ExploreModel.Title ? (ExploreModel.Title) c.ref : null));
                return;
            case Card.WATCH:
                if (c.ref instanceof WatchNext.Item) host.resumeWatching((WatchNext.Item) c.ref, from);
                return;
            case Card.GRANT:
                host.requestWatchGrant();
                return;
            case Card.MORE:
                host.click().open();
                if (r.spec != null) host.push(GridPage.rows(host, r.spec));
                return;
            case Card.GENRE:
                host.click().open();
                if (c.ref instanceof ExploreModel.Genre) host.push(GridPage.genre(host, (ExploreModel.Genre) c.ref));
                return;
            case Card.PERSON:
                host.click().open();
                host.push(GridPage.person(host, c.id));
                return;
            case Card.SERVICE: {
                host.click().open();
                int[] ids;
                String name;
                int color = c.color;
                if (c.ref instanceof ProviderTable.Service) {
                    ProviderTable.Service s = (ProviderTable.Service) c.ref;
                    ids = s.ids == null ? new int[0] : s.ids;
                    name = s.label;
                } else {
                    ids = new int[] {c.id};
                    name = c.title;
                }
                host.push(provider(host, ids, name, c.image, color));
                return;
            }
            default:
        }
    }

    @Override public void onLongPress(Row r, Card c, RectF from) {
        if (c.type == Card.WATCH) {
            // Matched to TMDB: Quero assistir too ("Mais informações" resumes it, as OK does).
            if (c.id > 0) titleMenu(host, tmdb, c, from, () -> onActivate(r, c, from));
            return;
        }
        if (c.type != Card.TITLE) {
            onActivate(r, c, from);
            return;
        }
        titleMenu(host, tmdb, c, from, () -> onActivate(r, c, from));
    }

    /** Holding OK on a title card (any Explore page): Watch on, Quero assistir, More info. */
    static void titleMenu(ExploreView host, Tmdb tmdb, Card c, RectF from, Runnable open) {
        ExploreModel.Details d = tmdb.peekDetails(c.kind, c.id);
        String back = backdropOf(c), poster = null;
        int year = 0;
        if (c.ref instanceof ExploreModel.Title) {
            ExploreModel.Title t = (ExploreModel.Title) c.ref;
            poster = t.poster;
            year = t.year;
        } else if (c.ref instanceof Watchlist.Item) {
            Watchlist.Item w = (Watchlist.Item) c.ref;
            poster = w.poster;
            year = w.year;
        }
        host.titleMenu(c.kind, c.id, c.title, back, poster, year, d, from, open);
    }

    /** The TMDB backdrop path a focused row card can show behind the page (title, Quero assistir, matched Continue Watching). */
    static String backdropOf(Card c) {
        if (c == null) return null;
        if (c.ref instanceof ExploreModel.Title) return ((ExploreModel.Title) c.ref).backdrop;
        if (c.ref instanceof Watchlist.Item) return ((Watchlist.Item) c.ref).backdrop;
        if (c.type == Card.WATCH && c.image != null && c.imageSpec == ExploreImages.WIDE && c.image.startsWith("/")) return c.image;
        return null;
    }

    @Override public void onFocus(Row r, Card c) {
        host.click().move();
        host.handler().removeCallbacks(follow);
        followPath = backdropOf(c);
        if (followPath != null) host.handler().postDelayed(follow, FOLLOW_MS);
    }

    /** Updates the backdrop follow timer when the focused row reloads (e.g. WatchMatch fills in Continue Watching's art). */
    private void syncFollow() {
        if (zone != Z_ROWS) return;
        String next = backdropOf(stack.focusedCard());
        if (next == null ? followPath == null : next.equals(followPath)) return;
        host.handler().removeCallbacks(follow);
        followPath = next;
        if (followPath != null && host.running() && host.isCurrent(this)) host.handler().postDelayed(follow, FOLLOW_MS);
    }

    /** After 700 ms on a card, the background takes its picture. */
    private void follow() {
        if (zone == Z_ROWS && followPath != null && host.isCurrent(this)) {
            host.backdrop().show(followPath, true);
            // A picture already in memory starts its crossfade here, outside a frame: run the frames for it.
            host.wake();
        }
    }

    @Override public void wake() {
        host.wake();
    }

    /** Up past the top: the top bar on a root page; a service page (pushed) has nothing above. */
    private void toTop() {
        if (!root) return;
        hero.setFocus(-1);
        zone = Z_TOPBAR;
        host.focusTopBar();
    }

    private void enterRows() {
        if (stack.shownRows().isEmpty()) return;
        zone = Z_ROWS;
        hero.setFocus(-1);
        stack.enter(true, 96 * k + 110 * k);
        host.click().move();
    }

    // MARK: - ExplorePage

    @Override boolean onKey(int keyCode, KeyEvent e, boolean longPress) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        // The retry is for OK on nothing: a card still shown (Continue Watching, your services) acts as usual.
        if (failedPage && ok && zone != Z_HERO && !(zone == Z_ROWS && stack.focusedCard() != null)) {
            retries = 0;
            retryPage();
            return true;
        }
        int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) dy = -1;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) dy = 1;
        if (zone == Z_HERO) {
            if (dx != 0) heroMove(dx);
            else if (dy < 0) toTop();
            else if (dy > 0) enterRows();
            else if (ok) heroActivate(longPress);
            else return false;
            return true;
        }
        if (zone == Z_ROWS && !stack.hasFocus()) {   // the focused row went away (an empty Continue Watching)
            if (heroSpec != null && hero.count() > 0) {
                zone = Z_HERO;
                hero.setFocus(0);
                applyBackground();
            } else if (!stack.shownRows().isEmpty()) {
                enterRows();
            } else {
                toTop();
            }
            return true;
        }
        if (zone == Z_ROWS) {
            if (ok) return stack.activate(longPress);
            if (dx == 0 && dy == 0) return false;
            int res = stack.onKey(dx, dy);
            if (res == BrowseStack.EXIT_UP) {
                if (heroSpec != null && hero.count() > 0 && hero.getVisibility() == View.VISIBLE) {
                    stack.leave();
                    zone = Z_HERO;
                    hero.setFocus(0);
                    host.handler().removeCallbacks(follow);
                    applyBackground();
                    host.click().move();
                } else if (root) {
                    stack.leave();
                    toTop();
                }
            }
            return true;
        }
        if (dy < 0) {
            toTop();
            return true;
        }
        if (dy > 0 || dx != 0) {
            focusFromTop();
            return true;
        }
        return false;
    }

    @Override boolean back() {
        if (zone == Z_ROWS) {
            stack.leave();
            stack.scrollHome();
            host.handler().removeCallbacks(follow);
            if (heroSpec != null && hero.count() > 0) {
                zone = Z_HERO;
                hero.setFocus(0);
                applyBackground();
                return true;
            }
            if (!root) return false;
            toTop();
            return true;
        }
        if (!root) return false;
        if (zone == Z_HERO) hero.setFocus(-1);
        zone = Z_TOPBAR;
        return false;
    }

    /** The remote came down from the top bar: the hero, else the first row (never waits). */
    @Override void focusFromTop() {
        takeFocus(false);
    }

    /**
     * waitHero: the page opened by itself (not a key), so a hero still loading takes the focus when it comes instead
     * of the rows taking it now.
     */
    private void takeFocus(boolean waitHero) {
        if (heroSpec != null && hero.count() > 0) {
            zone = Z_HERO;
            stack.leave();
            hero.setFocus(0);
            applyBackground();
            return;
        }
        if (waitHero && heroSpec != null && !heroFailed) {
            zone = Z_TOPBAR;
            return;
        }
        if (!stack.shownRows().isEmpty()) {
            enterRows();
            return;
        }
        if (root) host.focusTopBar();
    }

    @Override void blur() {
        hero.setFocus(-1);
        if (zone == Z_ROWS) stack.leave();
        zone = Z_TOPBAR;
        host.handler().removeCallbacks(follow);
        applyBackground();
    }

    @Override boolean atTop() {
        return zone != Z_ROWS;
    }

    private void applyBackground() {
        if (!host.isCurrent(this)) return;
        if (heroSpec == null) {
            host.backdrop().show(null, true);
            return;
        }
        String p = zone == Z_ROWS && followPath != null ? followPath : heroPath();
        if (p != null) host.backdrop().show(p, true);
    }

    @Override String backgroundPath() {
        return zone == Z_ROWS && followPath != null ? followPath : heroPath();
    }

    @Override boolean step(float dt) {
        boolean busy = stack.step(dt);
        busy |= hero.step(dt);
        float s = stack.scroll();
        hero.setMotion(-s, heroSpec != null ? 1 - s / (250 * k) : 0);
        if (header != null) header.setTranslationY(-s);
        if (title != null) title.setTranslationY(-s);
        if (host.isCurrent(this)) {
            BackdropView b = host.backdrop();
            b.setParallax(-0.35f * s);
            float row0 = Math.max(1, 866 * k - heroBand0);
            float dim = heroSpec == null ? 0.6f : s <= row0 ? 0.35f * s / row0 : 0.35f + 0.25f * Ui.clamp((s - row0) / (300 * k));
            b.setDim(dim);
        }
        return busy;
    }

    @Override float pageScroll() {
        return stack.scroll();
    }

    @Override void shown() {
        offlineShown = false;
        boolean again = false;
        for (Row r : rows) {
            if (r.state == Row.FAILED) {
                r.state = Row.EMPTY;
                again = true;
            }
        }
        if (again) stack.setRows(rows);
        if (keyMode != Tmdb.hasKey()) rebuild();
        if (host.topFocused() && root) {   // opened from the top bar: the focus stays up there
            hero.setFocus(-1);
            if (zone == Z_ROWS) stack.leave();
            zone = Z_TOPBAR;
        } else if (zone == Z_TOPBAR) {
            takeFocus(true);
        }
        applyBackground();
        host.wake();
    }

    @Override void hidden() {
        host.handler().removeCallbacks(advance);
        host.handler().removeCallbacks(follow);
    }

    @Override void resume() {
        resumed = true;
        scheduleAdvance();
    }

    @Override void pause() {
        resumed = false;
        host.handler().removeCallbacks(advance);
        host.handler().removeCallbacks(follow);
        host.handler().removeCallbacks(retry);
    }

    @Override void destroy() {
        super.destroy();
        pause();
        for (Tmdb.Ticket t : heroTickets) t.cancel();
        heroTickets.clear();
        stack.destroy();
    }

    @Override void imagesArrived() {
        stack.imagesArrived();
        hero.imagesArrived();
        if (header != null) header.invalidate();
    }

    @Override void reload() {
        BrProviders.Snapshot snap = BrProviders.snapshot();
        String ids = snap.installedIds() == null ? "" : snap.installedIds();
        if (keyMode != Tmdb.hasKey() || !ids.equals(builtIds)) {
            rebuild();
            return;
        }
        boolean again = false;
        for (Row r : rows) {
            if (r.state == Row.FAILED) {
                r.state = Row.EMPTY;
                again = true;
            }
        }
        if (again) stack.setRows(rows);
        if (heroSpec != null && heroItems.length == 0) loadHero();
        else if (heroItems.length > 0) hero.setItems(heroItems, heroDetails);   // installed offers may have changed
        if (failedPage) retryPage();
    }

    @Override void watchChanged() {
        for (Row r : rows) {
            if (r.spec != null && r.spec.source == Catalog.SRC_WATCH && r.state != Row.LOADING) load(r, Tmdb.NOW, 1);
        }
    }

    // MARK: - TalkBack

    @Override void a11yItems(List<Integer> out) {
        if (notice.getVisibility() == View.VISIBLE) out.add(7001);
        if (heroSpec != null && hero.count() > 0 && hero.getAlpha() > 0.5f) {
            for (int b = 0; b < hero.buttons(); b++) out.add(G_HERO * 1000 + b + 1);
        }
        stack.a11yItems(G_ROWS, out);
    }

    @Override String a11yText(int id) {
        if (id == 7001) return notice.text();
        if (id / 1000 == G_HERO) {
            ExploreModel.Title t = hero.item(hero.index());
            String label = hero.buttonLabel(id % 1000 - 1);
            return t == null ? label : t.title + ", " + label;
        }
        return stack.a11yText(id);
    }

    @Override boolean a11yBounds(int id, Rect r) {
        if (id == 7001) {
            r.set(notice.getLeft(), notice.getTop(), notice.getRight(), notice.getBottom());
            return true;
        }
        if (id / 1000 == G_HERO) {
            if (!hero.buttonBounds(id % 1000 - 1, rect)) return false;
            rect.round(r);
            return true;
        }
        return stack.a11yBounds(id, r);
    }

    @Override int a11yFocused() {
        if (zone == Z_HERO && hero.focus() >= 0) return G_HERO * 1000 + hero.focus() + 1;
        if (zone == Z_ROWS) return stack.a11yFocused(G_ROWS);
        return -1;
    }

    @Override void a11yFocus(int id) {
        if (id == 7001) return;
        if (id / 1000 == G_HERO) {
            if (zone == Z_ROWS) stack.leave();
            zone = Z_HERO;
            hero.setFocus(id % 1000 - 1);
            return;
        }
        zone = Z_ROWS;
        hero.setFocus(-1);
        stack.a11yFocus(id);
    }

    @Override void a11yClick(int id) {
        if (id == 7001) {
            if (failedPage) retryPage();
            return;
        }
        if (id / 1000 == G_HERO) {
            a11yFocus(id);
            heroActivate(false);
            return;
        }
        zone = Z_ROWS;
        stack.a11yClick(id);
    }

    /** A service page's header: the brand plate with its logo, and the name. */
    private final class ProviderHeader extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), name;
        private final RectF box = new RectF();
        private final float nameBase;

        ProviderHeader(Context c) {
            super(c);
            name = Ui.text(Ui.displaySemibold, 48 * k, CardPainter.INK);
            nameBase = 55 * k + Ui.halfCap(name);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (providerLogo != null) images.want(ExploreImages.PROVIDER, providerLogo, ExploreImages.NOW);
        }

        @Override protected void onDraw(Canvas c) {
            try {
                float s = 110 * k;
                box.set(0, 0, s, s);
                fill.setColor(providerColor != 0 ? providerColor | 0xFF000000 : 0xFF2C2C2E);
                c.drawRoundRect(box, 24 * k, 24 * k, fill);
                ExploreImages.Img img = providerLogo == null ? null : images.get(ExploreImages.PROVIDER, providerLogo);
                if (img != null) {
                    box.set(11 * k, 11 * k, s - 11 * k, s - 11 * k);
                    host.painter.drawImage(c, img.bitmap, img.shader, box, 20 * k, 0, 1);
                }
                if (providerName != null) c.drawText(providerName, s + 28 * k, nameBase, name);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "provider header draw", e);
            }
        }
    }
}
