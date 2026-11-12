package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * A title's page (pushed): backdrop, logo, meta, synopsis, the watch buttons, then where to watch, seasons and
 * episodes, cast, recommended, similar and the facts. One TMDB call (cached a day) feeds it all.
 */
final class DetailPage extends ExplorePage implements BrowseStack.Host {
    private static final int B_WATCH = 0, B_LOGO = 1, B_TRAILER = 2, B_SEASONS = 3, B_WHERE = 4, B_LIST = 5;
    private static final long LINKS_MS = 800;

    final int kind, id;
    final boolean fromHome;
    private final ExploreImages images;
    private final Tmdb tmdb;
    private Layer layer;
    private Header header;
    private BrowseStack stack;
    private ExploreModel.Title seed;
    private ExploreModel.Details details;
    private final HashMap<String, Row> rowsByKey = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private Row providersRow, seasonsRow, episodesRow;
    private int episodesSeason = -1;
    private Tmdb.Ticket ticket, seasonTicket;
    private int zone = Z_BUTTONS, button;
    private boolean built, linksAsked, failed, quiet;
    private final ArrayList<Btn> buttons = new ArrayList<>();
    private final RectF rect = new RectF();
    private final Runnable links = ExploreView.guard(host, "links", this::lookupLinks);

    /** One button of the row under the synopsis. */
    private static final class Btn {
        int type;
        String label, logo;
        float w;
        ExploreModel.Offer offer;
    }

    DetailPage(ExploreView v, int kind, int id, boolean fromHome) {
        super(v);
        this.kind = kind == ExploreModel.TV ? ExploreModel.TV : ExploreModel.MOVIE;
        this.id = id;
        this.fromHome = fromHome;
        images = v.images();
        tmdb = Tmdb.get(v.getContext());
    }

    /** What the card that opened it already knows (title, pictures): shown at once. */
    DetailPage withTitle(ExploreModel.Title t) {
        seed = t;
        return this;
    }

    @Override ViewGroup layer() {
        if (layer == null) layer = new Layer(context(), k);
        return layer;
    }

    @Override void build() {
        if (built) return;
        built = true;
        Context c = context();
        layer();
        header = new Header(c);
        layer.add(header, 0, 0, 1920, 860);
        stack = new BrowseStack(layer, layer.getChildCount(), c, host.painter, images, host.watchArt(), this);
        stack.setGeometry(96 * k, 826 * k, 826 * k, 116 * k);
        ExploreModel.Details cached = tmdb.peekDetails(kind, id);
        if (cached != null) deliver(cached, Tmdb.OK);
        else header.prepare();
        final int gen = generation;
        ticket = tmdb.details(kind, id, Tmdb.NOW, (d, status, fresh) -> {
            if (stale(gen)) return;
            if (d != null) deliver(d, status);
            else if (details == null) {
                failed = true;
                if (status == Tmdb.OFFLINE) host.toast(Ui.tr("No connection. Showing what was saved."));
                else if (status == Tmdb.REJECTED) host.toast(Ui.tr("The TMDB key was refused. Check it in Home Settings."));
                else if (status == Tmdb.NO_KEY) host.toast(Ui.tr("Add a TMDB key in Home Settings to explore."));
                header.prepare();
            }
        });
    }

    private ExploreModel.Title title() {
        return details != null && details.t != null ? details.t : seed;
    }

    private void deliver(ExploreModel.Details d, int status) {
        details = d;
        failed = false;
        makeButtons();
        header.prepare();
        makeRows();
        settleFocus();
        applyBackground();
        if (host.isCurrent(this) && !linksAsked && ExploreView.bestOffer(d) != null) {
            host.handler().removeCallbacks(links);
            host.handler().postDelayed(links, LINKS_MS);
        }
        host.wake();
    }

    /**
     * The focus while it waits on the buttons: the first one once they exist; with none (nothing streams it here),
     * the synopsis when it is cut, else the first row, so the page is never left without a focus.
     */
    private void settleFocus() {
        if (zone != Z_BUTTONS) return;
        if (focusable() > 0) {
            button = Math.max(0, Math.min(button, focusable() - 1));
            header.setFocus(button);
        } else if (details == null) {
            header.setFocus(-1);
        } else if (header.cut()) {
            zone = Z_OVERVIEW;
            header.setFocus(-2);
        } else if (!stack.shownRows().isEmpty()) {
            quiet = true;   // data came in, not a key: no click
            try {
                enterRows();
            } finally {
                quiet = false;
            }
        } else {
            header.setFocus(-1);
        }
        host.spoken().focusMoved();
    }

    /** Watchmode, once per page that has something installed to play (the buttons upgrade when it answers). */
    private void lookupLinks() {
        if (linksAsked || details == null) return;
        try {
            if (!TitleLinks.on()) return;
            linksAsked = true;
            TitleLinks.get(context()).lookup(kind, id, () -> { });
        } catch (RuntimeException e) {
            Safe.log("links", e);
        }
    }

    private void makeButtons() {
        ExploreModel.Details d = details;
        buttons.clear();
        if (d == null) return;
        BrProviders.Snapshot snap = BrProviders.snapshot();
        ExploreModel.Offer best = ExploreView.bestOffer(d);
        Paint p = header.button;
        if (best != null) {
            Btn b = new Btn();
            b.type = B_WATCH;
            b.offer = best;
            b.label = Ui.trf("Watch on {service}", "service", ExploreView.serviceLabel(best));
            b.w = p.measureText(b.label) + 64 * k + 34 * k;
            buttons.add(b);
            HashSet<String> keys = new HashSet<>();
            keys.add(best.service.key);
            if (d.offers != null) {
                for (ExploreModel.Offer o : d.offers) {
                    if (buttons.size() >= 4 || !ExploreView.playable(o, snap) || !keys.add(o.service.key)) continue;
                    Btn l = new Btn();
                    l.type = B_LOGO;
                    l.offer = o;
                    l.logo = o.logo;
                    l.label = Ui.trf("Watch on {service}", "service", ExploreView.serviceLabel(o));
                    l.w = 68 * k;
                    if (o.logo != null) images.want(ExploreImages.PROVIDER, o.logo, ExploreImages.NOW);
                    buttons.add(l);
                }
            }
        } else if (hasStreamable(d)) {
            Btn b = new Btn();
            b.type = B_WHERE;
            b.label = Ui.tr("Where to watch");
            b.w = p.measureText(b.label) + 64 * k;
            buttons.add(b);
        }
        if (d.trailer != null && d.trailer.key != null && d.trailer.key.matches("^[A-Za-z0-9_-]{6,20}$") && snap.installed("youtube")) {
            Btn b = new Btn();
            b.type = B_TRAILER;
            b.label = Ui.tr("Trailer");
            b.w = p.measureText(b.label) + 64 * k;
            buttons.add(b);
        }
        if (kind == ExploreModel.TV && d.seasonList != null && d.seasonList.length > 0) {
            Btn b = new Btn();
            b.type = B_SEASONS;
            b.label = Ui.tr("Seasons");
            b.w = p.measureText(b.label) + 64 * k;
            buttons.add(b);
        }
        {
            Btn b = new Btn();
            b.type = B_LIST;
            b.label = Watchlist.contains(kind, id) ? Ui.tr("✓ Want to Watch") : Ui.tr("+ Want to Watch");
            b.w = p.measureText(b.label) + 64 * k;
            buttons.add(b);
        }
        button = Math.max(0, Math.min(button, buttons.size() - 1));
        if (buttons.isEmpty() && zone == Z_BUTTONS) button = -1;
    }

    private static boolean hasStreamable(ExploreModel.Details d) {
        if (d.offers == null) return false;
        for (ExploreModel.Offer o : d.offers) if (ExploreView.streamableOffer(o)) return true;
        return false;
    }

    private Row row(String key, int style, String title) {
        Row r = rowsByKey.get(key);
        if (r == null) {
            r = new Row(key, style, null, title);
            rowsByKey.put(key, r);
        }
        r.title = title;
        return r;
    }

    private void makeRows() {
        ExploreModel.Details d = details;
        rows.clear();
        if (d == null) {
            stack.setRows(rows);
            return;
        }
        BrProviders.Snapshot snap = BrProviders.snapshot();
        // 1. Where to watch: installed streamable, then the others (dim), then rent and buy.
        ArrayList<Card> offers = new ArrayList<>();
        if (d.offers != null) {
            for (int pass = 0; pass < 3; pass++) {
                for (ExploreModel.Offer o : d.offers) {
                    if (o == null) continue;
                    boolean play = ExploreView.playable(o, snap), stream = ExploreView.streamableOffer(o);
                    if (pass == 0 && !play || pass == 1 && (play || !stream) || pass == 2 && stream) continue;
                    offers.add(offerCard(o, play, pass));
                }
            }
        }
        providersRow = null;
        if (!offers.isEmpty()) {
            Row r = row("providers", Catalog.PROVIDER, Ui.tr("Where to watch in Brazil"));
            setCards(r, offers);
            rows.add(r);
            providersRow = r;
        }
        // 2. Seasons, and the episodes of the one opened.
        seasonsRow = null;
        if (kind == ExploreModel.TV && d.seasonList != null && d.seasonList.length > 0) {
            ArrayList<Card> seasons = new ArrayList<>();
            boolean onlySpecials = true;
            for (ExploreModel.Season s : d.seasonList) if (s != null && s.number > 0) onlySpecials = false;
            for (ExploreModel.Season s : d.seasonList) {
                if (s == null || (s.number == 0 && !onlySpecials)) continue;
                Card c = new Card();
                c.type = Card.SEASON;
                c.id = s.number;
                c.kind = ExploreModel.TV;
                c.title = s.name != null && !s.name.isEmpty() && s.number == 0 ? s.name : Ui.trf("Season {n}", "n", s.number);
                int year = ExploreView.yearOf(s.airDate);
                String eps = s.episodes > 0 ? Ui.plural("plural|one:{n} episode|other:{n} episodes", s.episodes) : "";
                c.sub = year > 0 ? (eps.isEmpty() ? String.valueOf(year) : eps + " · " + year) : eps;
                c.image = s.poster != null ? s.poster : d.t != null ? d.t.poster : null;
                c.imageSpec = ExploreImages.POSTER;
                c.ref = s;
                seasons.add(c);
            }
            if (!seasons.isEmpty()) {
                Row r = row("seasons", Catalog.SEASON, Ui.tr("Seasons"));
                setCards(r, seasons);
                rows.add(r);
                seasonsRow = r;
                if (episodesRow != null && episodesSeason >= 0) rows.add(episodesRow);
            }
        }
        // 3. Cast.
        if (d.cast != null && d.cast.length > 0) {
            ArrayList<Card> cast = new ArrayList<>();
            for (ExploreModel.Credit cr : d.cast) {
                if (cr == null || cr.name == null || cast.size() >= 20) continue;
                Card c = new Card();
                c.type = Card.PERSON;
                c.id = cr.id;
                c.kind = ExploreModel.PERSON;
                c.title = cr.name;
                c.sub = cr.character == null ? "" : cr.character;
                c.image = cr.profile;
                c.imageSpec = ExploreImages.PROFILE;
                c.ref = cr;
                cast.add(c);
            }
            if (!cast.isEmpty()) {
                Row r = row("cast", Catalog.CAST, Ui.tr("Cast"));
                setCards(r, cast);
                rows.add(r);
            }
        }
        // 4 and 5. Recommended (or similar when there's none), then similar without the ones already shown.
        ExploreModel.Title[] rec = d.recommended != null && d.recommended.length > 0 ? d.recommended : d.similar;
        boolean recIsSimilar = rec == d.similar;
        HashSet<String> shownKeys = new HashSet<>();
        if (rec != null && rec.length > 0) {
            ArrayList<Card> list = new ArrayList<>();
            for (ExploreModel.Title t : rec) {
                if (t == null || t.kind == ExploreModel.PERSON || !shownKeys.add(t.key())) continue;
                list.add(BrowsePage.titleCard(t, Catalog.POSTER, list.size() + 1, false));
            }
            if (!list.isEmpty()) {
                Row r = row("recommended", Catalog.POSTER, Ui.tr("Recommended"));
                setCards(r, list);
                rows.add(r);
            }
        }
        if (!recIsSimilar && d.similar != null && d.similar.length > 0) {
            ArrayList<Card> list = new ArrayList<>();
            for (ExploreModel.Title t : d.similar) {
                if (t == null || t.kind == ExploreModel.PERSON || !shownKeys.add(t.key())) continue;
                list.add(BrowsePage.titleCard(t, Catalog.POSTER, list.size() + 1, false));
            }
            if (!list.isEmpty()) {
                Row r = row("similar", Catalog.POSTER, Ui.tr("Similar"));
                setCards(r, list);
                rows.add(r);
            }
        }
        // 6. The facts, then where the data comes from.
        ArrayList<Card> info = new ArrayList<>();
        ExploreModel.Title t = d.t;
        if (t != null && t.original != null && !t.original.equals(t.title)) info(info, Ui.tr("Original title"), t.original);
        if (d.originalLanguage != null) info(info, Ui.tr("Original language"), languageName(d.originalLanguage));
        if (kind == ExploreModel.MOVIE && d.director != null) info(info, Ui.tr("Directed by {name}").replace("{name}", "").replace(":", "").trim(), d.director);
        if (kind == ExploreModel.TV && d.creators != null) info(info, Ui.tr("Created by {name}").replace("{name}", "").replace(":", "").trim(), d.creators);
        if (d.releaseBr != null) info(info, Ui.tr("Release in Brazil"), longDate(d.releaseBr));
        if (d.runtime > 0) info(info, Ui.tr("Runtime"), HeroView.Meta.runtime(d.runtime));
        if (d.countries != null) info(info, Ui.tr("Countries"), d.countries);
        String status = statusName(d.status);
        if (status != null) info(info, Ui.tr("Status"), status);
        if (kind == ExploreModel.TV && d.network != null) info(info, Ui.tr("info|Network"), d.network);
        if (d.cert != null) info(info, Ui.tr("Age rating"), d.cert);
        Row r = row("info", Catalog.INFO, Ui.tr("Information"));
        setCards(r, info);
        r.lines = new CharSequence[] {Ui.tr("Where-to-watch data: JustWatch"),
                Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.")};
        rows.add(r);
        stack.setRows(rows);
    }

    private void setCards(Row r, List<Card> cards) {
        Card[] arr = cards.toArray(new Card[0]);
        r.set(arr, arr.length);
        r.state = Row.READY;
        r.page = r.totalPages = 1;
        stack.rowChanged(r);
    }

    private static void info(List<Card> out, String label, String value) {
        if (value == null || value.isEmpty()) return;
        Card c = new Card();
        c.type = Card.TITLE;
        c.id = out.size() + 1;
        c.kind = -1;
        c.title = label;
        c.sub = value;
        out.add(c);
    }

    /** TMDB sends the status in English whatever the language; one it may add later isn't shown. */
    private static String statusName(String s) {
        if (s == null) return null;
        switch (s) {
            case "Released": return Ui.tr("status|Released");
            case "Returning Series": return Ui.tr("status|Returning Series");
            case "Ended": return Ui.tr("status|Ended");
            case "Canceled": return Ui.tr("status|Canceled");
            case "In Production": return Ui.tr("status|In Production");
            case "Post Production": return Ui.tr("status|Post Production");
            case "Planned": return Ui.tr("status|Planned");
            case "Rumored": return Ui.tr("status|Rumored");
            case "Pilot": return Ui.tr("status|Pilot");
            default: return null;
        }
    }

    private static String languageName(String code) {
        try {
            Locale l = new Locale(code);
            String name = l.getDisplayLanguage(Ui.locale());
            if (name == null || name.isEmpty() || name.equalsIgnoreCase(code)) return code;
            return name.substring(0, 1).toUpperCase(Ui.locale()) + name.substring(1);
        } catch (RuntimeException e) {
            return code;
        }
    }

    private static String longDate(String ymd) {
        if (ymd == null || ymd.length() < 10) return ymd;
        String s = ExploreView.shortDate(ymd);
        int y = ExploreView.yearOf(ymd);
        return s.isEmpty() ? ymd : y > 0 ? s + " " + y : s;
    }

    private Card offerCard(ExploreModel.Offer o, boolean installed, int pass) {
        Card c = new Card();
        c.type = Card.OFFER;
        c.id = o.providerId * 10 + o.type;
        c.ref = o;
        c.image = o.logo;
        c.imageSpec = ExploreImages.PROVIDER;
        c.color = o.service != null ? o.service.color : 0xFF2C2C2E;
        String name = o.name == null ? ExploreView.serviceLabel(o) : o.name;
        String plain = name.toLowerCase(Locale.ROOT);
        int ch = plain.indexOf(" channel");
        if (ch > 0 && o.service != null) {
            // "Paramount+ Amazon Channel" → "Paramount+ · via Prime Video" (it opens the reseller's app).
            String base = name.substring(0, ch);
            int amazon = base.toLowerCase(Locale.ROOT).lastIndexOf(" amazon");
            int apple = base.toLowerCase(Locale.ROOT).lastIndexOf(" apple tv");
            int cut = Math.max(amazon, apple);
            if (cut > 0) base = base.substring(0, cut);
            name = base + " · " + Ui.trf("via {app}", "app", o.service.label);
        } else if (o.service != null && o.service.label != null) {
            // Claro tv+ keeps the channel it comes with ("Claro tv+ · HBO"), from ClaroCatalog.
            name = o.name != null && o.name.startsWith(o.service.label + " · ") ? o.name : o.service.label;
        }
        c.title = name;
        String type;
        switch (o.type) {
            case ExploreModel.FREE: type = Ui.tr("Free"); break;
            case ExploreModel.ADS: type = Ui.tr("With ads"); break;
            case ExploreModel.RENT: type = Ui.tr("Rent"); break;
            case ExploreModel.BUY: type = Ui.tr("Buy"); break;
            default: type = Ui.tr("Subscription");
        }
        c.sub = pass == 1 ? Ui.tr("Not installed") : type;
        c.dim = pass == 1;
        return c;
    }

    private void applyBackground() {
        if (!host.isCurrent(this)) return;
        ExploreModel.Details d = details;
        ExploreModel.Title t = title();
        String back = d != null && d.backdrop != null ? d.backdrop : t != null ? t.backdrop : null;
        if (back != null) host.backdrop().show(back, ExploreImages.HERO, true, true);
        else if (t != null && t.poster != null) host.backdrop().show(t.poster, ExploreImages.TINY, true, true);
        else if (d != null) host.backdrop().show(null, true);
    }

    // MARK: - Actions

    private void press(Btn b, boolean longPress) {
        if (b == null) return;
        header.buttonBounds(buttons.indexOf(b), rect);
        switch (b.type) {
            case B_WATCH:
            case B_LOGO:
                host.watch(details, b.offer, host, rect);
                return;
            case B_TRAILER:
                host.trailer(details.trailer.key, host, rect);
                return;
            case B_SEASONS:
                if (seasonsRow != null) {
                    host.click().open();
                    zone = Z_ROWS;
                    header.setFocus(-1);
                    stack.focusRow(seasonsRow);
                }
                return;
            case B_LIST: {
                ExploreModel.Title t = title();
                String name = t == null || t.title == null ? "" : t.title;
                boolean added = Watchlist.toggle(context(), kind, id, name, t == null ? null : t.backdrop,
                        t == null ? null : t.poster, t == null ? 0 : t.year);
                host.click().open();
                host.toast(added ? Ui.trf("{title} is in Want to Watch.", "title", name) : Ui.trf("{title} left Want to Watch.", "title", name));
                host.listChanged();
                return;
            }
            case B_WHERE:
                if (providersRow != null) {
                    host.click().open();
                    zone = Z_ROWS;
                    header.setFocus(-1);
                    stack.focusRow(providersRow);
                }
                return;
            default:
        }
    }

    /** The primary action: play on the best installed service. */
    private void watchBest(RectF from) {
        ExploreModel.Offer o = ExploreView.bestOffer(details);
        if (o != null) host.watch(details, o, host, from);
        else if (providersRow != null) {
            zone = Z_ROWS;
            header.setFocus(-1);
            stack.focusRow(providersRow);
        }
    }

    @Override public void onActivate(Row r, Card c, RectF from) {
        switch (c.type) {
            case Card.OFFER: {
                ExploreModel.Offer o = (ExploreModel.Offer) c.ref;
                BrProviders.Snapshot snap = BrProviders.snapshot();
                boolean there = o.service != null && snap.pkg(o.service.key) != null && snap.enabled(o.service.key);
                if (there) host.watch(details, o, host, from);
                else host.toast(Ui.trf("{app} isn't installed on this TV.", "app", o.service != null ? o.service.label : o.name));
                return;
            }
            case Card.SEASON:
                host.click().open();
                openSeason(c.id);
                return;
            case Card.EPISODE:
                watchBest(from);
                return;
            case Card.PERSON:
                host.click().open();
                host.push(GridPage.person(host, c.id));
                return;
            case Card.TITLE:
                if (c.kind != ExploreModel.MOVIE && c.kind != ExploreModel.TV) return;   // the facts block
                host.click().open();
                host.push(new DetailPage(host, c.kind, c.id, false).withTitle(c.ref instanceof ExploreModel.Title ? (ExploreModel.Title) c.ref : null));
                return;
            default:
        }
    }

    private void openSeason(int n) {
        if (seasonsRow == null) return;
        if (episodesRow == null) episodesRow = row("episodes", Catalog.EPISODE, Ui.tr("Episodes"));
        if (episodesSeason == n && episodesRow.state == Row.READY) {
            stack.focusRow(episodesRow);
            return;
        }
        episodesSeason = n;
        episodesRow.title = Ui.trf("Season {n}", "n", n) + " · " + Ui.tr("Episodes");
        episodesRow.set(new Card[0], 0);
        episodesRow.state = Row.LOADING;
        if (!rows.contains(episodesRow)) {
            rows.add(rows.indexOf(seasonsRow) + 1, episodesRow);
            stack.setRows(rows);
        } else {
            stack.rowChanged(episodesRow);
        }
        if (seasonTicket != null) seasonTicket.cancel();
        final int gen = generation, season = n;
        seasonTicket = tmdb.season(id, n, Tmdb.NOW, (sd, status, fresh) -> {
            if (stale(gen) || season != episodesSeason) return;
            deliverSeason(sd, status);
        });
    }

    private void deliverSeason(ExploreModel.SeasonDetails sd, int status) {
        if (sd == null || sd.episodes == null) {
            if (episodesRow.count == 0) {
                episodesRow.state = status == Tmdb.OK ? Row.READY : Row.FAILED;
                stack.rowChanged(episodesRow);
            }
            return;
        }
        ArrayList<Card> list = new ArrayList<>();
        for (ExploreModel.Episode e : sd.episodes) {
            if (e == null) continue;
            Card c = new Card();
            c.type = Card.EPISODE;
            c.id = e.number;
            c.kind = ExploreModel.TV;
            String name = e.name == null ? "" : e.name;
            c.title = "E" + e.number + (name.isEmpty() ? "" : " · " + name);
            String date = ExploreView.shortDate(e.airDate);
            String run = e.runtime > 0 ? Ui.trf("{n} min", "n", e.runtime) : "";
            c.sub = run.isEmpty() ? date : date.isEmpty() ? run : run + " · " + date;
            c.image = e.still;
            c.imageSpec = ExploreImages.STILL;
            c.ref = e;
            list.add(c);
        }
        setCards(episodesRow, list);
        if (zone == Z_ROWS && stack.focusedRow() == seasonsRow) stack.focusRow(episodesRow);   // not if the user moved on
    }

    @Override public void onLongPress(Row r, Card c, RectF from) {
        if (c.type == Card.TITLE && (c.kind == ExploreModel.MOVIE || c.kind == ExploreModel.TV)) {
            BrowsePage.titleMenu(host, tmdb, c, from, () -> onActivate(r, c, from));
            return;
        }
        onActivate(r, c, from);
    }

    @Override public void onFocus(Row r, Card c) {
        if (!quiet) host.click().move();
    }

    @Override public void onNeedRow(Row r, int priority) {
        if (r.state == Row.EMPTY) r.state = Row.READY;
    }

    @Override public void onNeedMore(Row r) {}

    @Override public void wake() {
        host.wake();
    }

    // MARK: - ExplorePage

    private int focusable() {
        return buttons.size();
    }

    @Override boolean onKey(int keyCode, KeyEvent e, boolean longPress) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) dy = -1;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) dy = 1;
        if (zone == Z_ROWS && !stack.hasFocus()) {
            zone = Z_BUTTONS;
            header.setFocus(focusable() > 0 ? Math.max(0, button) : -1);
            return true;
        }
        if (zone == Z_ROWS) {
            if (ok) return stack.activate(longPress);
            if (dx == 0 && dy == 0) return false;
            if (stack.onKey(dx, dy) == BrowseStack.EXIT_UP) {
                stack.leave();
                if (focusable() > 0) {
                    zone = Z_BUTTONS;
                    button = Math.max(0, Math.min(button, focusable() - 1));
                    header.setFocus(button);
                } else if (header.cut()) {
                    zone = Z_OVERVIEW;
                    header.setFocus(-2);
                } else {
                    zone = Z_BUTTONS;
                    header.setFocus(-1);
                }
                host.click().move();
            }
            return true;
        }
        if (zone == Z_OVERVIEW) {
            if (ok) {
                host.click().open();
                ExploreModel.Title t = title();
                host.showSheet(t == null ? "" : t.title, header.overviewText());
            } else if (dy > 0) {
                if (focusable() > 0) {
                    zone = Z_BUTTONS;
                    header.setFocus(button = Math.max(0, button));
                } else enterRows();
                host.click().move();
            } else if (dx == 0 && dy == 0) return false;
            return true;
        }
        // BUTTONS
        if (ok) {
            if (longPress) {
                header.buttonBounds(0, rect);
                watchBest(rect);
                return true;
            }
            if (button >= 0 && button < buttons.size()) press(buttons.get(button), false);
            return true;
        }
        if (dx != 0) {
            int nb = button + dx;
            if (nb >= 0 && nb < focusable()) {
                button = nb;
                header.setFocus(nb);
                host.click().move();
            }
            return true;
        }
        if (dy < 0) {
            if (header.cut()) {
                zone = Z_OVERVIEW;
                header.setFocus(-2);
                host.click().move();
            }
            return true;
        }
        if (dy > 0) {
            enterRows();
            return true;
        }
        return false;
    }

    private void enterRows() {
        if (stack.shownRows().isEmpty()) return;
        float x = 96 * k;
        if (button >= 0 && header.buttonBounds(button, rect)) x = rect.centerX();
        zone = Z_ROWS;
        header.setFocus(-1);
        stack.enter(true, x);
    }

    @Override boolean back() {
        return false;
    }

    @Override boolean step(float dt) {
        boolean busy = stack.step(dt);
        float s = stack.scroll();
        header.setTranslationY(-s);
        header.step();
        if (host.isCurrent(this)) {
            host.backdrop().setParallax(-0.35f * s);
            host.backdrop().setDim(Ui.clamp(s / (500 * k)) * 0.6f);
        }
        return busy;
    }

    @Override float pageScroll() {
        return stack.scroll();
    }

    @Override String backgroundPath() {
        ExploreModel.Details d = details;
        ExploreModel.Title t = title();
        return d != null && d.backdrop != null ? d.backdrop : t != null ? t.backdrop : null;
    }

    @Override void shown() {
        applyBackground();
        settleFocus();
        if (details != null && !linksAsked && ExploreView.bestOffer(details) != null) {
            host.handler().removeCallbacks(links);
            host.handler().postDelayed(links, LINKS_MS);
        }
    }

    @Override void hidden() {
        host.handler().removeCallbacks(links);
    }

    @Override void destroy() {
        super.destroy();
        host.handler().removeCallbacks(links);
        if (ticket != null) ticket.cancel();
        if (seasonTicket != null) seasonTicket.cancel();
        stack.destroy();
    }

    @Override void imagesArrived() {
        stack.imagesArrived();
        header.imagesArrived();
    }

    @Override void watchlistChanged() {
        if (details == null) return;
        makeButtons();
        header.prepare();
        settleFocus();
        host.wake();
    }

    @Override void reload() {
        if (details != null) {
            makeButtons();
            header.prepare();
            makeRows();
            settleFocus();   // the buttons may have changed under the focus
        } else if (failed) {
            failed = false;
            final int gen = generation;
            ticket = tmdb.details(kind, id, Tmdb.NOW, (d, status, fresh) -> {
                if (!stale(gen) && d != null) deliver(d, status);
            });
        }
    }

    // MARK: - TalkBack (group 3: buttons 3001…, the synopsis 3100)

    @Override void a11yItems(List<Integer> out) {
        if (title() != null) out.add(G_DETAIL * 1000 + 99 + 1);
        for (int i = 0; i < focusable(); i++) out.add(G_DETAIL * 1000 + i + 1);
        stack.a11yItems(G_ROWS, out);
    }

    @Override String a11yText(int id) {
        if (id == G_DETAIL * 1000 + 100) {
            ExploreModel.Title t = title();
            String over = header.overviewText();
            return (t == null ? "" : t.title + ". " + header.metaText() + ". ") + over;
        }
        if (id / 1000 == G_DETAIL) {
            int i = id % 1000 - 1;
            return i >= 0 && i < buttons.size() ? buttons.get(i).label : "";
        }
        return stack.a11yText(id);
    }

    @Override boolean a11yBounds(int id, Rect r) {
        if (id == G_DETAIL * 1000 + 100) {
            r.set(Math.round(96 * k), Math.round(180 * k + header.getTranslationY()), Math.round(1000 * k),
                    Math.round(640 * k + header.getTranslationY()));
            return true;
        }
        if (id / 1000 == G_DETAIL) {
            if (!header.buttonBounds(id % 1000 - 1, rect)) return false;
            rect.round(r);
            return true;
        }
        return stack.a11yBounds(id, r);
    }

    @Override int a11yFocused() {
        if (zone == Z_OVERVIEW) return G_DETAIL * 1000 + 100;
        if (zone == Z_BUTTONS && button >= 0 && button < focusable()) return G_DETAIL * 1000 + button + 1;
        if (zone == Z_ROWS) return stack.a11yFocused(G_ROWS);
        return -1;
    }

    @Override void a11yFocus(int id) {
        if (id == G_DETAIL * 1000 + 100) {
            if (zone == Z_ROWS) stack.leave();
            zone = Z_OVERVIEW;
            header.setFocus(header.cut() ? -2 : -1);
            return;
        }
        if (id / 1000 == G_DETAIL) {
            if (zone == Z_ROWS) stack.leave();
            zone = Z_BUTTONS;
            button = id % 1000 - 1;
            header.setFocus(button);
            return;
        }
        zone = Z_ROWS;
        header.setFocus(-1);
        stack.a11yFocus(id);
    }

    @Override void a11yClick(int id) {
        a11yFocus(id);
        if (zone == Z_OVERVIEW) {
            ExploreModel.Title t = title();
            host.showSheet(t == null ? "" : t.title, header.overviewText());
        } else if (zone == Z_BUTTONS) {
            if (button >= 0 && button < buttons.size()) press(buttons.get(button), false);
        } else {
            stack.a11yClick(id);
        }
    }

    /** Everything above the rows: logo or title, meta, tagline, synopsis, credit line and the buttons. HW layer. */
    private final class Header extends View {
        private final TextPaint titlePaint, overPaint, taglinePaint;
        final Paint button, buttonFocused, credit, chip, noneText, noneSub;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG), logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final HeroView.Meta.Painter metaPainter;
        private final Path play;
        private StaticLayout titleLayout, overLayout;
        private HeroView.Meta meta;
        private String tagline = "", creditLine = "", overview = "", none = "", noneLine = "", chipText;
        private boolean cut, drawnLogo = true;
        private float chipX, chipY, chipW, noneW;
        private int focus = -1, gen = -1;
        private final RectF box = new RectF();
        private final float buttonHalf, chipHalf;

        Header(Context c) {
            super(c);
            titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 72 * k, CardPainter.INK));
            overPaint = new TextPaint(Ui.text(Ui.regular, 26 * k, CardPainter.INK));
            taglinePaint = new TextPaint(Ui.text(Ui.regular, 24 * k, CardPainter.DIM));
            button = Ui.text(Ui.semibold, 26 * k, CardPainter.INK);
            buttonFocused = Ui.text(Ui.semibold, 26 * k, CardPainter.FOCUS_TEXT);
            credit = Ui.text(Ui.medium, 24 * k, CardPainter.DIM);
            chip = Ui.text(Ui.semibold, 18 * k, CardPainter.INK);
            noneText = Ui.text(Ui.medium, 26 * k, CardPainter.DIM);
            noneSub = Ui.text(Ui.regular, 22 * k, CardPainter.FAINT);
            metaPainter = new HeroView.Meta.Painter(k, Ui.numbers(Ui.medium, 26 * k, CardPainter.META));
            play = ExploreIcons.play(22 * k);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(2.5f * k);
            rim.setColor(0xFFFFFFFF);
            buttonHalf = Ui.halfCap(button);
            chipHalf = Ui.halfCap(chip);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        boolean cut() { return cut; }
        String overviewText() { return overview; }
        String metaText() { return meta == null ? "" : meta.lead + (meta.rating != null ? meta.rating : ""); }

        void step() {
            if (gen != Ui.generation) {
                makeButtons();
                prepare();
                makeRows();
                settleFocus();
            }
        }

        /** Texts and layouts for the current data (UI thread, never while drawing). */
        void prepare() {
            gen = Ui.generation;
            ExploreModel.Title t = title();
            ExploreModel.Details d = details;
            String name = t == null || t.title == null ? "" : t.title;
            titleLayout = StaticLayout.Builder.obtain(name, 0, name.length(), titlePaint, Math.round(1000 * k))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(8 * k, 1).build();
            meta = t == null ? null : HeroView.Meta.build(t, d, true, metaPainter, 1400 * k);
            tagline = d != null && d.tagline != null ? TextUtils.ellipsize(d.tagline, taglinePaint, 1300 * k, TextUtils.TruncateAt.END).toString() : "";
            overview = d != null && d.t != null && d.t.overview != null ? d.t.overview : t != null && t.overview != null ? t.overview : "";
            creditLine = "";
            if (d != null) {
                if (kind == ExploreModel.MOVIE && d.director != null) creditLine = Ui.trf("Directed by {name}", "name", d.director);
                else if (kind == ExploreModel.TV && d.creators != null) creditLine = Ui.trf("Created by {name}", "name", d.creators);
                creditLine = TextUtils.ellipsize(creditLine, new TextPaint(credit), 1300 * k, TextUtils.TruncateAt.END).toString();
            }
            // With a credit line under it, the synopsis keeps to 3 lines so the two never touch.
            int lines = creditLine.isEmpty() ? 4 : 3;
            int w = Math.round(900 * k);
            StaticLayout full = StaticLayout.Builder.obtain(overview, 0, overview.length(), overPaint, w).setLineSpacing(10 * k, 1).build();
            cut = full.getLineCount() > lines;
            overLayout = StaticLayout.Builder.obtain(overview, 0, overview.length(), overPaint, Math.round(cut ? w - 90 * k : w))
                    .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(10 * k, 1)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            if (cut) {
                chipText = Ui.tr("MORE");
                chipW = chip.measureText(chipText) + 28 * k;
                int last = Math.min(lines - 1, overLayout.getLineCount() - 1);
                chipX = 96 * k + overLayout.getLineWidth(last) + 14 * k;
                chipY = 492 * k + overLayout.getLineBaseline(last);
            }
            none = noneLine = "";
            if (d != null && buttons.isEmpty() || d != null && buttons.size() == 1 && buttons.get(0).type == B_TRAILER) {
                if (!hasStreamable(d)) {
                    none = Ui.tr("Not available to stream in Brazil");
                    StringBuilder b = new StringBuilder();
                    HashSet<String> names = new HashSet<>();
                    if (d.offers != null) for (ExploreModel.Offer o : d.offers) {
                        if (o == null || o.type < ExploreModel.RENT) continue;
                        String n = ExploreView.serviceLabel(o);
                        if (n.isEmpty() || !names.add(n) || names.size() > 4) continue;
                        b.append(b.length() > 0 ? ", " : "").append(n);
                    }
                    if (b.length() > 0) noneLine = Ui.trf("Only to rent or buy: {apps}", "apps", b.toString());
                }
            }
            noneW = none.isEmpty() ? 0 : noneText.measureText(none);
            if (d != null && d.logo != null) images.want(ExploreImages.LOGO, d.logo, ExploreImages.NOW);
            invalidate();
        }

        /** -1 none, -2 the MAIS chip, else a button. */
        void setFocus(int f) {
            if (f == focus) return;
            focus = f;
            invalidate();
        }

        void imagesArrived() {
            if (!drawnLogo) invalidate();
            for (Btn b : buttons) if (b.type == B_LOGO) { invalidate(); break; }
        }

        private float buttonsX() {
            return none.isEmpty() ? 96 * k : 96 * k + noneW + 32 * k;
        }

        boolean buttonBounds(int i, RectF out) {
            if (i < 0 || i >= buttons.size()) return false;
            float x = buttonsX();
            for (int j = 0; j < i; j++) x += buttons.get(j).w + 20 * k;
            float top = 700 * k + getTranslationY();
            out.set(x, top, x + buttons.get(i).w, top + 68 * k);
            return true;
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "detail draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            float x = 96 * k;
            ExploreModel.Details d = details;
            boolean logoShown = false;
            drawnLogo = true;
            if (d != null && d.logo != null) {
                ExploreImages.Img img = images.get(ExploreImages.LOGO, d.logo);
                drawnLogo = img != null;
                if (img != null && img.bitmap != null) {
                    float bw = img.bitmap.getWidth(), bh = img.bitmap.getHeight(), s = Math.min(720 * k / bw, 200 * k / bh);
                    box.set(x, 380 * k - bh * s, x + bw * s, 380 * k);
                    c.drawBitmap(img.bitmap, null, box, logoPaint);
                    logoShown = true;
                }
            }
            if (!logoShown && titleLayout != null) {
                c.save();
                c.translate(x, 380 * k - titleLayout.getHeight());
                titleLayout.draw(c);
                c.restore();
            }
            if (d == null && title() == null) {
                fill.setColor(CardPainter.SKELETON);
                c.drawRoundRect(x, 260 * k, x + 480 * k, 380 * k, 16 * k, 16 * k, fill);
            }
            if (meta != null) meta.draw(c, x, 432 * k, metaPainter);
            if (!tagline.isEmpty()) c.drawText(tagline, x, 470 * k, taglinePaint);
            if (overLayout != null) {
                c.save();
                c.translate(x, 492 * k);
                overLayout.draw(c);
                c.restore();
                if (cut) {
                    boolean f = focus == -2;
                    float h = 30 * k;
                    box.set(chipX, chipY - h / 2 - 8 * k, chipX + chipW, chipY + h / 2 - 8 * k);
                    fill.setColor(f ? CardPainter.FOCUS : CardPainter.SELECTED);
                    c.drawRoundRect(box, h / 2, h / 2, fill);
                    chip.setColor(f ? CardPainter.FOCUS_TEXT : CardPainter.INK);
                    c.drawText(chipText, chipX + 14 * k, box.centerY() + chipHalf, chip);
                }
            }
            if (!creditLine.isEmpty()) {
                float below = overLayout == null ? 492 * k : 492 * k + overLayout.getHeight() + 36 * k;
                c.drawText(creditLine, x, Math.min(below, 672 * k), credit);
            }
            float top = 700 * k, h = 68 * k;
            if (!none.isEmpty()) {
                c.drawText(none, x, top + h / 2 + buttonHalf - (noneLine.isEmpty() ? 0 : 14 * k), noneText);
                if (!noneLine.isEmpty()) c.drawText(noneLine, x, top + h / 2 + buttonHalf + 22 * k, noneSub);
            }
            float bx = buttonsX();
            for (int i = 0; i < buttons.size(); i++) {
                Btn b = buttons.get(i);
                boolean f = i == focus;
                float g = f ? 0.03f : 0;
                box.set(bx - b.w * g, top - h * g, bx + b.w * (1 + g), top + h * (1 + g));
                if (b.type == B_LOGO) {
                    fill.setColor(b.offer != null && b.offer.service != null ? b.offer.service.color | 0xFF000000 : CardPainter.PILL);
                    c.drawRoundRect(box, 18 * k, 18 * k, fill);
                    ExploreImages.Img img = b.logo == null ? null : images.get(ExploreImages.PROVIDER, b.logo);
                    if (img != null) {
                        box.inset(6 * k, 6 * k);
                        host.painter.drawImage(c, img.bitmap, img.shader, box, 14 * k, 0, 1);
                        box.inset(-6 * k, -6 * k);
                    }
                    if (f) c.drawRoundRect(box, 18 * k, 18 * k, rim);
                } else {
                    fill.setColor(f ? CardPainter.FOCUS : CardPainter.PILL);
                    c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill);
                    float tx = bx + 32 * k;
                    if (b.type == B_WATCH) {
                        fill.setColor(f ? CardPainter.FOCUS_TEXT : CardPainter.INK);
                        c.save();
                        c.translate(tx, top + (h - 22 * k) / 2);
                        c.drawPath(play, fill);
                        c.restore();
                        tx += 34 * k;
                    }
                    c.drawText(b.label, tx, top + h / 2 + buttonHalf, f ? buttonFocused : button);
                }
                bx += b.w + 20 * k;
            }
        }
    }
}
