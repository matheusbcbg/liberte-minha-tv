package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Início's Top Shelf, the upper part of the screen that shows content for the focused top-row tile, in one of three
 * templates: CAROUSEL (a streaming app's titles, full bleed: Explore's BackdropView and HeroView), UP_NEXT (the
 * Explorar tile: Continue Watching, picks and "Mais no Explorar" in Explore's RowView and CardPainter, over the selected
 * card's backdrop) and BANNER (everything else: the tile's own art over a wash of its colours, no network).
 * BROWSE while the Dock has focus, FOCUSED once Up gives it focus, AWAY while it isn't shown at all.
 * Not a View: like BackdropView it adds its views to the host at indexes 0..5 (Backdrop A, B, dim; BannerArt;
 * HeroView; the Up Next RowView). Main thread only. Every Handler callback and TMDB answer is guarded (host.fault).
 */
final class TopShelfView {
    static final int BROWSE = 0, FOCUSED = 1, AWAY = 2;
    static final int MOVE_USED = 0, MOVE_BUMP = 1, MOVE_EXIT_UP = 2, MOVE_EXIT_DOWN = 3;

    private static final int NONE = 0;
    private static final long ADVANCE_MS = 8000, FOLLOW_MS = 700, SWEEP_MS = 550;
    private static final float ROW_Y = 300, ROW_H = 420, X0 = 80, CARDS_TOP = 52, PAGE = 574, FADE = 250, UP_NEXT_DIM = 0.25f;
    private static final int STYLE = Catalog.WIDE;

    private final ViewGroup hostView;
    private final StartHost host;
    private final ExploreImages images;
    private final CardPainter painter;
    private final Context context;
    private final float k;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BackdropView backdrop;
    /** BackdropView's two slots (host children 0 and 1): hidden while the page's dim covers them (S ≥ 574, AWAY). */
    private final View slotA, slotB;
    private boolean slotsHidden;
    private final BannerArt banner;
    private final HeroView hero;
    private final RowView rowView;
    private final Row row;

    private int template = NONE, state = BROWSE;
    private String key;
    private float scroll;
    private boolean resumed;
    private long lastKey;
    private int gen = -1;

    // How much of each template shows (crossfades between them) and the hero's compact geometry (BROWSE).
    private final Spring heroShow = new Spring(0.30f, 1f, 0), rowShow = new Spring(0.30f, 1f, 0),
            bannerShow = new Spring(0.30f, 1f, 0), compact = new Spring(0.38f, 0.90f, 1);

    // CAROUSEL
    private TopShelfFeed.Item[] items = new TopShelfFeed.Item[0];
    private final ArrayList<Tmdb.Ticket> tickets = new ArrayList<>();
    private boolean[] asked = new boolean[0];

    // UP_NEXT: per-card focus springs, the row's scroll, the edge bump, the sheen.
    private Spring[] cardFocus = new Spring[0];
    private final Spring rowScroll = new Spring(0.34f, 1f, 0), bump = new Spring(0.25f, 0.6f, 0);
    private int cardIndex;
    private long sweepAt = -1;
    /** a11yFocus before the shelf has focus: the element setState(FOCUSED) goes to (-1: the default). */
    private int pendingFocus = -1;

    private final RectF rect = new RectF();

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            try {
                advanceTick();
            } catch (RuntimeException e) {
                host.fault("shelf advance", e);
            }
        }
    };

    private final Runnable follow = new Runnable() {
        @Override public void run() {
            try {
                followCard(true);
            } catch (RuntimeException e) {
                host.fault("shelf follow", e);
            }
        }
    };

    TopShelfView(ViewGroup host, StartHost h, ExploreImages img, WatchArt art, CardPainter painter) {
        hostView = host;
        this.host = h;
        images = img;
        this.painter = painter;
        context = host.getContext();
        k = ExploreView.scaleOf(context);
        backdrop = new BackdropView(host, img);
        slotA = host.getChildAt(0);
        slotB = host.getChildAt(1);
        banner = new BannerArt(context, h, k);
        host.addView(banner, 3, new ExplorePage.Pos(0, 0, 1920, 1080));
        hero = new HeroView(context, img);
        host.addView(hero, 4, new ExplorePage.Pos(0, 0, 1000, 900));
        rowView = new RowView(context, painter, img, art);
        host.addView(rowView, 5, new ExplorePage.Pos(0, ROW_Y, 1920, ROW_H));
        row = new Row("start:upnext", STYLE, null, Ui.tr("Up Next"));
        row.state = Row.READY;
        rowView.bind(row, cardFocus, rowScroll, X0 * k);
        hero.setCompact(1);
        hero.setFocus(-1);
        hero.setMotion(0, 0);
        rowView.setAlpha(0);
        rowView.setVisibility(View.INVISIBLE);
        banner.setFade(0, 1);
    }

    // MARK: - Content

    /** The Explorar tile's row: Continue Watching (matched to TMDB), picks, then "Mais no Explorar". */
    void showUpNext(Card[] cards, int n, boolean animate) {
        n = cards == null ? 0 : Math.max(0, Math.min(n, cards.length));
        boolean was = template == TopShelfFeed.UP_NEXT;
        String focusedKey = was && cardIndex < row.count && row.cards[cardIndex] != null ? row.cards[cardIndex].key() : null;
        row.title = Ui.tr("Up Next");
        row.set(cards, n);
        row.state = Row.READY;
        growSprings();
        // The same card keeps the focus when the list changes under it (a match came in, an item went).
        int keep = 0;
        for (int i = 0; focusedKey != null && i < row.count; i++) if (focusedKey.equals(row.cards[i].key())) keep = i;
        cardIndex = Math.max(0, Math.min(focusedKey != null ? keep : 0, row.count - 1));
        if (!was) switchTo(TopShelfFeed.UP_NEXT, HomeLayout.EXPLORE, animate);
        for (Spring s : cardFocus) s.target = 0;
        if (state == FOCUSED && row.count > 0) {
            focusCard(cardIndex, false);
        } else {
            rowView.focusIndex = -1;
            rowScroll.target = 0;
            if (!animate || !was) rowScroll.snap(0);
            painter.prepare(row, 0, Math.min(row.count, 8));
            wantCards();
            followCard(animate);
        }
        rowShow.target = row.count > 0 ? 1 : 0;
        if (!animate) rowShow.snap(rowShow.target);
        rowView.invalidate();
        host.a11yChanged();
        host.wake();
    }

    /** A streaming app's titles (≤ 6, each with a backdrop): full bleed, the text column compact while browsing. */
    void showCarousel(String key, TopShelfFeed.Item[] list, boolean animate) {
        if (key == null || list == null || list.length == 0) return;
        int n = 0;
        for (TopShelfFeed.Item it : list) if (it != null && it.title != null) n++;
        if (n == 0) return;
        TopShelfFeed.Item[] clean = new TopShelfFeed.Item[n];
        n = 0;
        for (TopShelfFeed.Item it : list) if (it != null && it.title != null) clean[n++] = it;
        boolean same = template == TopShelfFeed.CAROUSEL && key.equals(this.key) && sameTitles(clean);
        String keep = same && hero.index() < items.length ? items[hero.index()].title.key() : null;
        items = clean;
        if (!same) {
            cancelTickets();
            asked = new boolean[n];
        } else if (asked.length != n) {
            asked = new boolean[n];
        }
        ExploreModel.Title[] titles = new ExploreModel.Title[n];
        ExploreModel.Details[] details = new ExploreModel.Details[n];
        for (int i = 0; i < n; i++) {
            titles[i] = clean[i].title;
            details[i] = clean[i].details;
        }
        hero.setItems(titles, details);
        int index = 0;
        for (int i = 0; keep != null && i < n; i++) if (keep.equals(titles[i].key())) index = i;
        hero.setIndex(index, false);
        if (template != TopShelfFeed.CAROUSEL || !key.equals(this.key)) switchTo(TopShelfFeed.CAROUSEL, key, animate);
        heroButtonFocus();
        backdrop.show(titles[hero.index()].backdrop, animate);
        wantNext();
        scheduleAdvance(ADVANCE_MS);
        host.a11yChanged();
        host.wake();
    }

    private boolean sameTitles(TopShelfFeed.Item[] list) {
        if (list.length != items.length) return false;
        for (int i = 0; i < list.length; i++) if (!list[i].title.key().equals(items[i].title.key())) return false;
        return true;
    }

    /** One carousel item's details (logo, runtime, certification, offers) came in. */
    void setDetails(String key, int index, ExploreModel.Details d) {
        if (d == null || key == null || template != TopShelfFeed.CAROUSEL || !key.equals(this.key)) return;
        if (index < 0 || index >= items.length) return;
        items[index].details = d;
        hero.setDetails(index, d);
        if (state == FOCUSED && index == hero.index()) heroButtonFocus();
        host.a11yChanged();
        host.wake();
    }

    /** Anything else: the tile's art over a wash of its colours; line2 already translated (or null). */
    void showBanner(StartModel.Tile t, String line2, boolean animate) {
        if (t == null) return;
        boolean was = template == TopShelfFeed.BANNER && t.key != null && t.key.equals(key);
        banner.set(t, line2, animate && template == TopShelfFeed.BANNER && !was);
        if (!was) switchTo(TopShelfFeed.BANNER, t.key, animate);
        host.a11yChanged();
        host.wake();
    }

    /** The common part of a template change: what fades in and out, timers, the state's focus. */
    private void switchTo(int type, String newKey, boolean animate) {
        int before = template;
        template = type;
        key = newKey;
        handler.removeCallbacks(follow);
        if (type != TopShelfFeed.CAROUSEL) {
            handler.removeCallbacks(advance);
            cancelTickets();
        }
        heroShow.target = type == TopShelfFeed.CAROUSEL ? 1 : 0;
        rowShow.target = type == TopShelfFeed.UP_NEXT && row.count > 0 ? 1 : 0;
        bannerShow.target = type == TopShelfFeed.BANNER ? 1 : 0;
        if (type == TopShelfFeed.BANNER) {
            // Shown over whatever was there; the backdrop goes once the banner covers it (step).
            if (!animate || before == NONE) {
                backdrop.show(null, false);
                hero.setFocus(-1);
            }
        }
        if (state == FOCUSED && !focusable()) state = BROWSE;
        compact.target = state == FOCUSED && type == TopShelfFeed.CAROUSEL ? 0 : 1;
        if (type != TopShelfFeed.CAROUSEL) hero.setFocus(-1);
        if (!animate) {
            heroShow.snap(heroShow.target);
            rowShow.snap(rowShow.target);
            bannerShow.snap(bannerShow.target);
            compact.snap(compact.target);
            hero.setCompact(compact.value);
        }
        apply();
    }

    String key() {
        return key;
    }

    /** True when Up from the Dock can come here: a carousel or a non-empty Up Next row (a banner never). */
    boolean focusable() {
        if (template == TopShelfFeed.CAROUSEL) return items.length > 0;
        if (template == TopShelfFeed.UP_NEXT) return row.count > 0;
        return false;
    }

    // MARK: - State, scroll and keys

    /** StartView drives it; FOCUSED gives focus to button 0 (carousel) or the card focused last (Up Next). */
    void setState(int s) {
        if (s != BROWSE && s != FOCUSED && s != AWAY) return;
        if (s == FOCUSED && !focusable()) s = BROWSE;
        int before = state;
        state = s;
        if (s == FOCUSED) {
            if (template == TopShelfFeed.CAROUSEL) {
                int b = pendingFocus >= 0 ? pendingFocus : before == FOCUSED ? hero.focus() : 0;
                hero.setFocus(Math.max(0, b));
                compact.target = 0;
            } else if (template == TopShelfFeed.UP_NEXT) {
                focusCard(pendingFocus >= 0 ? pendingFocus : cardIndex, true);
            }
        } else {
            hero.setFocus(-1);
            compact.target = 1;
            if (before == FOCUSED && template == TopShelfFeed.UP_NEXT) blurRow();
        }
        pendingFocus = -1;
        if (s == AWAY) handler.removeCallbacks(advance);
        else if (before == AWAY) scheduleAdvance(ADVANCE_MS);
        apply();
        host.a11yChanged();
        host.wake();
    }

    /** The page's scroll S (0 with the Dock focused, 574 and more in the grid). */
    void setScroll(float s) {
        s = Math.max(0, s);
        if (s == scroll) return;
        scroll = s;
        apply();
    }

    /** FOCUSED only: arrows inside the shelf. */
    int move(int dx, int dy) {
        if (state != FOCUSED) return MOVE_BUMP;
        if (dy < 0) return MOVE_EXIT_UP;
        if (dy > 0) return MOVE_EXIT_DOWN;
        if (dx == 0) return MOVE_BUMP;
        if (template == TopShelfFeed.CAROUSEL) {
            int n = hero.buttons(), b = hero.focus() + dx;
            if (n > 0 && b >= 0 && b < n) {
                hero.setFocus(b);
                host.a11yChanged();
                return MOVE_USED;
            }
            if (items.length < 2) return MOVE_BUMP;
            // Past the first or the last button: the next (or the previous) item, wrapping.
            goTo(hero.index() + dx, true);
            hero.setFocus(dx > 0 ? 0 : 1);
            host.a11yChanged();
            host.wake();
            return MOVE_USED;
        }
        if (template == TopShelfFeed.UP_NEXT) {
            int ni = cardIndex + dx;
            if (ni < 0 || ni >= row.count) {
                bump.velocity += (dx > 0 ? 1 : -1) * 10 * k * 42;   // out and back, about 10 px
                bump.target = 0;
                host.wake();
                return MOVE_BUMP;
            }
            focusCard(ni, true);
            host.a11yChanged();
            host.wake();
            return MOVE_USED;
        }
        return MOVE_BUMP;
    }

    /** OK released while FOCUSED. */
    void activate() {
        if (state != FOCUSED) return;
        if (template == TopShelfFeed.CAROUSEL) {
            int b = hero.focus();
            if (hero.isWatchButton(b)) watch();
            else moreInfo();
        } else if (template == TopShelfFeed.UP_NEXT) {
            openCard(cardIndex);
        }
    }

    /** OK held while FOCUSED: a carousel plays ("Assistir"), a card opens its menu. True if it did something. */
    boolean hold() {
        if (state != FOCUSED) return false;
        if (template == TopShelfFeed.CAROUSEL && items.length > 0) return carouselMenu();
        if (template == TopShelfFeed.UP_NEXT) return cardMenu(cardIndex);
        return false;
    }

    /** MEDIA_PLAY_PAUSE: plays the carousel's title when it has focus. */
    boolean play() {
        if (state != FOCUSED || template != TopShelfFeed.CAROUSEL || items.length == 0) return false;
        watch();
        return true;
    }

    // MARK: - Carousel

    private void goTo(int i, boolean animate) {
        int n = items.length;
        if (n == 0) return;
        i = RowMath.wrap(i, n);
        hero.setIndex(i, animate);
        backdrop.show(items[i].title.backdrop, animate);
        wantNext();
        details(i);
    }

    /** The next item's picture comes early, so the 8 s advance never waits on screen. */
    private void wantNext() {
        int n = items.length;
        if (n < 2) return;
        String path = items[(hero.index() + 1) % n].title.backdrop;
        if (path != null) images.want(ExploreImages.HERO, path, ExploreImages.SOON);
    }

    /**
     * Details (logo, meta, offers) for the item just paged to and the next one, when TopShelfFeed hasn't given them:
     * the memory cache first, then TMDB (only while :home may use it).
     */
    private void details(int i) {
        int n = items.length;
        if (n == 0) return;
        final String forKey = key;
        for (int j = 0; j < 2 && j < n; j++) {
            final int at = (i + j) % n;
            final TopShelfFeed.Item it = items[at];
            if (it.details != null || at >= asked.length || asked[at]) continue;
            asked[at] = true;
            Tmdb tmdb = Tmdb.get(context);
            ExploreModel.Details d = tmdb.peekDetails(it.title.kind, it.title.id);
            if (d != null) {
                setDetails(forKey, at, d);
                continue;
            }
            if (!Tmdb.hasKey() || !Prefs.topShelfPreviews() || TopShelfFeed.networkBlocked()) continue;
            tickets.add(tmdb.details(it.title.kind, it.title.id, Tmdb.SOON, (value, status, fresh) -> {
                try {
                    if (value != null && at < items.length && items[at] == it) setDetails(forKey, at, value);
                } catch (RuntimeException e) {
                    host.fault("shelf details", e);
                }
            }));
        }
    }

    private void cancelTickets() {
        for (Tmdb.Ticket t : tickets) if (t != null) t.cancel();
        tickets.clear();
    }

    /** The focused button after a change of item or details (HeroView keeps it within the buttons it has). */
    private void heroButtonFocus() {
        if (state == FOCUSED && template == TopShelfFeed.CAROUSEL) hero.setFocus(Math.max(0, hero.focus()));
        else hero.setFocus(-1);
    }

    /** "▶ Assistir em …": in the app through the pick's link, else Explore's detail page with that button focused. */
    private void watch() {
        int i = hero.index();
        if (i < 0 || i >= items.length) return;
        TopShelfFeed.Item it = items[i];
        RectF from = from();
        if (it.link != null && it.pkg != null) host.openLink(it.pkg, it.service != null ? it.service : it.pkg, it.link, from);
        else host.openExplore(target(it.title.kind, it.title.id), from);
    }

    /** The carousel's title held: Watch, Quero assistir, More info. */
    private boolean carouselMenu() {
        int i = hero.index();
        if (i < 0 || i >= items.length) return false;
        final ExploreModel.Title t = items[i].title;
        final ArrayList<String> rows = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        final ArrayList<Boolean> red = new ArrayList<>();
        rows.add(Ui.tr("Watch"));
        actions.add(this::watch);
        red.add(false);
        addListRow(t.kind, t.id, t.title, t.backdrop, t.poster, t.year, rows, actions, red);
        rows.add(Ui.tr("More info"));
        actions.add(this::moreInfo);
        red.add(false);
        RectF anchor = from();
        showMenu(t.title, anchor, rows, actions, red);
        return true;
    }

    /** "Adicionar a Quero assistir" or "Remover de Quero assistir" for a TMDB film or show. */
    private void addListRow(final int kind, final int id, final String title, final String backdrop, final String poster,
                            final int year, List<String> rows, List<Runnable> actions, List<Boolean> red) {
        if (id <= 0 || (kind != ExploreModel.MOVIE && kind != ExploreModel.TV)) return;
        rows.add(Watchlist.contains(kind, id) ? Ui.tr("Remove from Want to Watch") : Ui.tr("Add to Want to Watch"));
        actions.add(() -> {
            String name = title == null ? "" : title;
            boolean added = Watchlist.toggle(context, kind, id, name, backdrop, poster, year);
            host.toast(added ? Ui.trf("{title} is in Want to Watch.", "title", name) : Ui.trf("{title} left Want to Watch.", "title", name));
        });
        red.add(false);
    }

    private void showMenu(String title, RectF anchor, List<String> rows, final List<Runnable> actions, List<Boolean> red) {
        StartMenu.Spec spec = new StartMenu.Spec();
        spec.title = title == null ? "" : title;
        spec.rows = rows.toArray(new String[0]);
        spec.destructive = new boolean[rows.size()];
        spec.submenuOf = new int[rows.size()];
        for (int j = 0; j < rows.size(); j++) {
            spec.destructive[j] = red.get(j);
            spec.submenuOf[j] = -1;
        }
        spec.anchor = anchor;
        spec.listener = picked -> {
            try {
                if (picked >= 0 && picked < actions.size()) actions.get(picked).run();
            } catch (RuntimeException e) {
                host.fault("shelf menu", e);
            }
        };
        host.showMenu(spec);
    }

    private void moreInfo() {
        int i = hero.index();
        if (i < 0 || i >= items.length) return;
        host.openExplore(target(items[i].title.kind, items[i].title.id), from());
    }

    private RectF from() {
        RectF out = new RectF();
        if (!focusedRect(out)) out.set(X0 * k, 400 * k, 700 * k, 760 * k);
        return out;
    }

    private static String target(int kind, int id) {
        return (kind == ExploreModel.TV ? "tv/" : "movie/") + id;
    }

    private void scheduleAdvance(long delay) {
        handler.removeCallbacks(advance);
        if (resumed && template == TopShelfFeed.CAROUSEL && items.length >= 2 && state != AWAY) {
            handler.postDelayed(advance, Math.max(100, delay));
        }
    }

    /** Every 8 s without a key: the next item, if its picture is already decoded (else this tick is skipped). */
    private void advanceTick() {
        if (!resumed || template != TopShelfFeed.CAROUSEL || items.length < 2 || state == AWAY) return;
        long idle = SystemClock.uptimeMillis() - lastKey;
        if (idle < ADVANCE_MS) {
            scheduleAdvance(ADVANCE_MS - idle);
            return;
        }
        if (scroll < 1) {
            int next = (hero.index() + 1) % items.length;
            String path = items[next].title.backdrop;
            if (path != null && images.get(ExploreImages.HERO, path) != null) {
                goTo(next, true);
                host.a11yChanged();
                host.wake();
            } else if (path != null) {
                images.want(ExploreImages.HERO, path, ExploreImages.SOON);
            }
        }
        scheduleAdvance(ADVANCE_MS);
    }

    // MARK: - Up Next

    private void growSprings() {
        if (cardFocus.length >= row.count) return;
        Spring[] grown = new Spring[Math.max(row.count, Math.max(8, cardFocus.length * 2))];
        System.arraycopy(cardFocus, 0, grown, 0, cardFocus.length);
        for (int i = cardFocus.length; i < grown.length; i++) grown[i] = new Spring(0.30f, 0.80f, 0);
        cardFocus = grown;
        rowView.bind(row, cardFocus, rowScroll, X0 * k);   // a bound view keeps the array it was given
    }

    private void focusCard(int index, boolean animate) {
        if (row.count == 0) return;
        index = Math.max(0, Math.min(index, row.count - 1));
        if (cardIndex < cardFocus.length && cardIndex != index) cardFocus[cardIndex].target = 0;
        cardIndex = index;
        cardFocus[index].target = 1;
        if (!animate) cardFocus[index].snap(1);
        sweepAt = SystemClock.uptimeMillis();
        rowScroll.target = RowMath.scrollTarget(index, row.count, painter.step(STYLE), painter.width(STYLE), X0 * k, 1920 * k);
        rowView.focusIndex = index;
        rowView.invalidate();
        int[] vr = visibleCards(rowScroll.target);
        painter.prepare(row, vr[0], Math.min(row.count, vr[1] + 8));
        wantCards();
        handler.removeCallbacks(follow);
        handler.postDelayed(follow, FOLLOW_MS);
    }

    /** Back to BROWSE: no card focused, the row from its start, card 0's backdrop. */
    private void blurRow() {
        if (cardIndex < cardFocus.length) cardFocus[cardIndex].target = 0;
        sweepAt = -1;
        rowView.focusIndex = -1;
        rowView.sweep = -1;
        rowScroll.target = 0;
        rowView.invalidate();
        handler.removeCallbacks(follow);
        followCard(true);
        wantCards();
    }

    /** The backdrop behind Up Next: the focused card's (FOCUSED) or card 0's, at HERO size; none: the dark wall. */
    private void followCard(boolean animate) {
        if (template != TopShelfFeed.UP_NEXT) return;
        int i = state == FOCUSED ? cardIndex : 0;
        Card c = i < row.count ? row.cards[i] : null;
        if (c != null && c.type == Card.MORE) return;   // "Mais no Explorar" keeps the picture behind it
        backdrop.show(backdropOf(c), animate);
    }

    private static String backdropOf(Card c) {
        if (c == null || c.image == null || c.imageSpec != ExploreImages.WIDE || !c.image.startsWith("/")) return null;
        return c.image;
    }

    private final int[] range = new int[2];

    private int[] visibleCards(float s) {
        float step = painter.step(STYLE);
        int first = (int) Math.floor((s - step) / step), last = (int) Math.ceil((s + 1920 * k - X0 * k) / step) + 1;
        range[0] = Math.max(0, first);
        range[1] = Math.min(row.count, Math.max(range[0], last));
        return range;
    }

    /** The cards on screen and 2 more now, 6 more soon: their stills and the matched title logos. */
    private void wantCards() {
        int[] vr = visibleCards(rowScroll.target);
        int a = vr[0], b = vr[1];
        for (int j = a; j < Math.min(row.count, b + 8); j++) {
            Card c = row.cards[j];
            if (c == null) continue;
            int p = j < b + 2 ? ExploreImages.NOW : ExploreImages.SOON;
            if (c.image != null) images.want(c.imageSpec, c.image, p);
            if (c.logo != null) images.want(ExploreImages.LOGO, c.logo, p);
        }
        Card first = row.count > 0 ? row.cards[state == FOCUSED ? cardIndex : 0] : null;
        String bg = backdropOf(first);
        if (bg != null) images.want(ExploreImages.HERO, bg, ExploreImages.SOON);
    }

    private void openCard(int i) {
        if (i < 0 || i >= row.count) return;
        Card c = row.cards[i];
        if (c == null) return;
        RectF from = new RectF();
        cardRect(i, from);
        switch (c.type) {
            case Card.WATCH:
                if (c.ref instanceof WatchNext.Item) host.resumeWatching((WatchNext.Item) c.ref, from);
                break;
            case Card.MORE:
                host.openExplore(null, from);
                break;
            default:
                if (c.id > 0) host.openExplore(target(c.kind, c.id), from);
                else if (c.ref instanceof Recs.Pick && ((Recs.Pick) c.ref).pkg != null) {
                    Recs.Pick p = (Recs.Pick) c.ref;
                    host.openLink(p.pkg, p.app != null ? p.app : p.pkg, p.link, from);
                }
        }
    }

    /**
     * Hold OK on a card: Continue Watching "Ir para o filme / a série" (matched only) · "Abrir o app" · "Ocultar de
     * Continuar assistindo" (red); a pick "Mais informações" · "Abrir {app}".
     */
    private boolean cardMenu(int i) {
        if (i < 0 || i >= row.count) return false;
        final Card c = row.cards[i];
        if (c == null || c.type == Card.MORE) return false;
        RectF anchor = new RectF();
        cardRect(i, anchor);
        final ArrayList<String> rows = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        final ArrayList<Boolean> red = new ArrayList<>();
        final RectF from = new RectF(anchor);
        if (c.type == Card.WATCH && c.ref instanceof WatchNext.Item) {
            final WatchNext.Item it = (WatchNext.Item) c.ref;
            if (c.id > 0) {
                rows.add(c.kind == ExploreModel.TV ? Ui.tr("Go to the series") : Ui.tr("Go to the movie"));
                actions.add(() -> host.openExplore(target(c.kind, c.id), from));
                red.add(false);
            }
            if (it.pkg != null) {
                rows.add(Ui.tr("Open the app"));
                actions.add(() -> host.openItem(HomeApps.app(it.pkg, it.app != null ? it.app : it.pkg), from));
                red.add(false);
            }
            if (c.id > 0) addListRow(c.kind, c.id, c.title, c.image, null, 0, rows, actions, red);
            rows.add(Ui.tr("Hide from Continue Watching"));
            actions.add(() -> host.hideWatching(it));
            red.add(true);
        } else {
            if (c.id > 0) {
                rows.add(Ui.tr("More info"));
                actions.add(() -> host.openExplore(target(c.kind, c.id), from));
                red.add(false);
                String back = c.ref instanceof Watchlist.Item ? ((Watchlist.Item) c.ref).backdrop : c.image;
                String poster = c.ref instanceof Watchlist.Item ? ((Watchlist.Item) c.ref).poster : null;
                int year = c.ref instanceof Watchlist.Item ? ((Watchlist.Item) c.ref).year : 0;
                addListRow(c.kind, c.id, c.title, back, poster, year, rows, actions, red);
            }
            if (c.ref instanceof Recs.Pick && ((Recs.Pick) c.ref).pkg != null) {
                final Recs.Pick p = (Recs.Pick) c.ref;
                final String app = p.app != null ? p.app : p.pkg;
                rows.add(Ui.trf("Open {app}", "app", app));
                actions.add(() -> host.openLink(p.pkg, app, p.link, from));
                red.add(false);
            }
        }
        if (rows.isEmpty()) return false;
        StartMenu.Spec spec = new StartMenu.Spec();
        spec.title = c.title == null ? "" : c.title;
        spec.rows = rows.toArray(new String[0]);
        spec.destructive = new boolean[rows.size()];
        spec.submenuOf = new int[rows.size()];
        for (int j = 0; j < rows.size(); j++) {
            spec.destructive[j] = red.get(j);
            spec.submenuOf[j] = -1;
        }
        spec.anchor = anchor;
        spec.listener = picked -> {
            try {
                if (picked >= 0 && picked < actions.size()) actions.get(picked).run();
            } catch (RuntimeException e) {
                host.fault("shelf menu", e);
            }
        };
        host.showMenu(spec);
        return true;
    }

    /** Card i's rectangle on screen, at its resting size. */
    private boolean cardRect(int i, RectF out) {
        if (i < 0 || i >= row.count) return false;
        float x = X0 * k + i * painter.step(STYLE) - rowScroll.value;
        float top = rowView.getTop() + rowView.getTranslationY() + CARDS_TOP * k;
        if (rowView.getHeight() == 0) top = (ROW_Y + CARDS_TOP) * k - scroll;
        out.set(x, top, x + painter.width(STYLE), top + painter.height(STYLE));
        return true;
    }

    // MARK: - Frames and lifecycle

    boolean step(float dt) {
        boolean busy = backdrop.step(dt);
        busy |= hero.step(dt);
        if (compact.step(dt)) busy = true;
        hero.setCompact(compact.value);
        busy |= heroShow.step(dt) | rowShow.step(dt) | bannerShow.step(dt);
        busy |= banner.step(dt);
        if (gen != Ui.generation) {
            gen = Ui.generation;
            row.title = Ui.tr("Up Next");
            int[] vr = visibleCards(rowScroll.target);
            painter.prepare(row, vr[0], Math.min(row.count, vr[1] + 8));
            rowView.invalidate();
        }
        boolean moved = rowScroll.step(dt);
        moved |= bump.step(dt);
        for (int j = 0; j < Math.min(row.count, cardFocus.length); j++) {
            Spring s = cardFocus[j];
            if (s.value != s.target || s.velocity != 0) moved |= s.step(dt);
        }
        float sweep = -1;
        if (sweepAt >= 0) {
            long age = SystemClock.uptimeMillis() - sweepAt;
            if (age < SWEEP_MS) {
                sweep = age / (float) SWEEP_MS;
                moved = true;
            } else {
                sweepAt = -1;
                if (rowView.sweep >= 0) moved = true;
            }
        }
        rowView.sweep = sweep;
        rowView.bump = bump.value;
        if (moved && rowView.getVisibility() == View.VISIBLE) rowView.invalidate();
        busy |= moved;
        // A banner that covers the screen: the picture under it goes (no layer composited for nothing).
        if (template == TopShelfFeed.BANNER && bannerShow.value > 0.999f && backdrop.current() != null) backdrop.show(null, false);
        apply();
        return busy || backdrop.busy();
    }

    /** Pictures came in (ExploreImages or WatchArt). */
    void imagesArrived() {
        backdrop.imagesArrived();
        hero.imagesArrived();
        if (rowView.row != null && rowView.missing > 0) rowView.invalidate();
    }

    void resume() {
        resumed = true;
        lastKey = SystemClock.uptimeMillis();
        backdrop.refresh();
        scheduleAdvance(ADVANCE_MS);
        if (template == TopShelfFeed.UP_NEXT) wantCards();
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(advance);
        handler.removeCallbacks(follow);
        cancelTickets();
        for (int i = 0; i < asked.length; i++) if (items[i].details == null) asked[i] = false;
    }

    /** Any key: the 8 s without one start again. */
    void keyed() {
        lastKey = SystemClock.uptimeMillis();
        if (template == TopShelfFeed.CAROUSEL) scheduleAdvance(ADVANCE_MS);
    }

    /** Alphas, translations and visibilities from the scroll, the state and the template crossfades. */
    private void apply() {
        float page = PAGE * k, d = Math.min(1, scroll / page), fg = 1 - Math.min(1, scroll / (FADE * k));
        float shown = state == AWAY ? 0 : 1;
        backdrop.setDim(shown == 0 ? 1 : Math.max(d, UP_NEXT_DIM * rowShow.value));
        backdrop.setParallax(-0.35f * scroll);
        hideSlots(shown == 0 || d >= 0.999f);
        hero.setMotion(-scroll, Ui.clamp(fg * heroShow.value * shown));
        // The hero's layer only while a carousel shows (§3.12): HWUI frees it with LAYER_TYPE_NONE.
        int heroLayer = heroShow.value > 0.001f || heroShow.target > 0 ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (hero.getLayerType() != heroLayer) hero.setLayerType(heroLayer, null);
        float ra = Ui.clamp(fg * rowShow.value * shown);
        rowView.setTranslationY(-scroll);
        if (rowView.getAlpha() != ra) rowView.setAlpha(ra);
        int vis = ra <= 0.001f ? View.INVISIBLE : View.VISIBLE;
        if (rowView.getVisibility() != vis) {
            rowView.setVisibility(vis);
            if (vis == View.VISIBLE) rowView.invalidate();
        }
        banner.setFade(Ui.clamp(bannerShow.value * (1 - d) * shown), Ui.clamp(fg / Math.max(0.001f, 1 - d)));
    }

    /** The backdrop slots go INVISIBLE (their layers freed) under a full dim; they come back as BackdropView left them. */
    private void hideSlots(boolean hide) {
        if (slotA == null || slotB == null) return;
        if (hide) {
            if (slotA.getVisibility() != View.INVISIBLE) slotA.setVisibility(View.INVISIBLE);
            if (slotB.getVisibility() != View.INVISIBLE) slotB.setVisibility(View.INVISIBLE);
            slotsHidden = true;
        } else if (slotsHidden) {
            boolean waiting = restore(slotA) | restore(slotB);
            slotsHidden = waiting && backdrop.busy();
        }
    }

    /** A slot that shows (alpha > 0) is made visible again; true if it has none yet (a fade may still bring it). */
    private static boolean restore(View v) {
        if (v.getAlpha() <= 0.001f) return true;
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        return false;
    }

    // MARK: - TalkBack and mouse

    /** The shelf's spoken items: the carousel's buttons, or the Up Next cards. */
    int a11yCount() {
        if (template == TopShelfFeed.CAROUSEL) return items.length > 0 ? hero.buttons() : 0;
        if (template == TopShelfFeed.UP_NEXT) return row.count;
        return 0;
    }

    String a11yText(int i) {
        if (template == TopShelfFeed.CAROUSEL) {
            ExploreModel.Title t = hero.item(hero.index());
            String label = hero.buttonLabel(i);
            return t == null || t.title == null ? label : t.title + ", " + label;
        }
        if (template == TopShelfFeed.UP_NEXT && i >= 0 && i < row.count && row.cards[i] != null) {
            Card c = row.cards[i];
            String title = c.title == null ? "" : c.title;
            return c.sub == null || c.sub.isEmpty() ? title : title + ", " + c.sub;
        }
        return "";
    }

    boolean a11yBounds(int i, RectF out) {
        if (template == TopShelfFeed.CAROUSEL) return hero.buttonBounds(i, out);
        if (template == TopShelfFeed.UP_NEXT) return cardRect(i, out);
        return false;
    }

    int a11yFocused() {
        if (state != FOCUSED) return -1;
        if (template == TopShelfFeed.CAROUSEL) return hero.focus();
        if (template == TopShelfFeed.UP_NEXT) return cardIndex;
        return -1;
    }

    /** Moves the shelf's own focus (StartView puts the zone on the shelf); before FOCUSED, it is where it goes. */
    void a11yFocus(int i) {
        if (i < 0 || i >= a11yCount()) return;
        if (state != FOCUSED) {
            pendingFocus = i;
            if (template == TopShelfFeed.UP_NEXT) cardIndex = i;
            return;
        }
        if (template == TopShelfFeed.CAROUSEL) hero.setFocus(i);
        else if (template == TopShelfFeed.UP_NEXT) focusCard(i, true);
        host.wake();
    }

    void a11yClick(int i) {
        if (state != FOCUSED) setState(FOCUSED);
        a11yFocus(i);
        activate();
    }

    boolean a11yHoldable(int i) {
        if (template != TopShelfFeed.UP_NEXT || i < 0 || i >= row.count || row.cards[i] == null) return false;
        return row.cards[i].type != Card.MORE;
    }

    void a11yHold(int i) {
        if (state != FOCUSED) setState(FOCUSED);
        a11yFocus(i);
        hold();
    }

    /** The shelf's element under (x, y) on screen, for a11yFocus; -1 for none. Buttons only while they show. */
    int hit(float x, float y) {
        RectF r = rect;
        if (template == TopShelfFeed.CAROUSEL && compact.value < 0.5f) {
            for (int i = 0; i < hero.buttons(); i++) if (hero.buttonBounds(i, r) && r.contains(x, y)) return i;
        } else if (template == TopShelfFeed.UP_NEXT && rowShow.value > 0.5f) {
            int[] vr = visibleCards(rowScroll.value);
            for (int i = vr[0]; i < vr[1]; i++) if (cardRect(i, r) && r.contains(x, y)) return i;
        }
        return -1;
    }

    /** The focused button or card on screen (FOCUSED only): the menu's anchor and the opening's scale-up. */
    boolean focusedRect(RectF out) {
        if (state != FOCUSED) return false;
        if (template == TopShelfFeed.CAROUSEL) return hero.buttonBounds(Math.max(0, hero.focus()), out);
        if (template == TopShelfFeed.UP_NEXT) return cardRect(cardIndex, out);
        return false;
    }

    // MARK: - BANNER

    /**
     * The BANNER template, full screen: the dark page lit by the app's colour (accentOf its 32x18 copy),
     * BackdropView's bottom scrim; then the crisp art 448x252 at (80, 236) with its shadow, and the label (and a
     * second line) beside it. Crossfades from the banner before. A hardware layer only while it shows.
     */
    static final class BannerArt extends View {
        private static final float ART_X = 80, ART_Y = 236, ART_W = 448, ART_H = 252, ART_R = 24, LABEL_X = 560,
                LABEL_BASE = 400, LINE2_BASE = 452, TEXT_W = 1280, SYMBOL = 120;
        // Drawn tiles' gradients (no banner): Explorar, TV ao Vivo, an input, Liberte minha TV; folders are flat.
        private static final int[] EXPLORE = {0xFF3A3A42, 0xFF111114}, LIVE = {0xFF1E3A5F, 0xFF0D1B2A},
                INPUT = {0xFF2C2C2E, 0xFF1C1C1E}, SETTINGS = {0xFF0A84FF, 0xFF5E5CE6};

        /** One banner's look, made once in set(). */
        private static final class Face {
            final StartModel.Tile tile;
            final String key, label, line2;
            BitmapShader art;
            Shader fill, glow, glow2;
            int wash, flat;
            final BitmapShader[] apps = new BitmapShader[6];
            final Bitmap[] appBitmaps = new Bitmap[6];
            int appCount;

            Face(StartModel.Tile t, String label, String line2) {
                tile = t;
                key = t.key;
                this.label = label;
                this.line2 = line2;
            }
        }

        private final StartHost host;
        private final float k;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), scrim = new Paint(Paint.DITHER_FLAG),
                shadow = new Paint(Paint.ANTI_ALIAS_FLAG), logo = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint label, line2;
        private final Shadows shadows = new Shadows();
        private final RectF screen = new RectF(), box = new RectF(), tmp = new RectF();
        private LinearGradient bottom;
        private Face cur, prev;
        private float mix = 1, alpha = -1, fg = 1;

        BannerArt(Context c, StartHost h, float k) {
            super(c);
            host = h;
            this.k = k;
            label = new TextPaint(Ui.text(Ui.displaySemibold, 56 * k, CardPainter.INK));
            line2 = new TextPaint(Ui.text(Ui.medium, 28 * k, CardPainter.META));
            shadow.setMaskFilter(new BlurMaskFilter(40 * k, BlurMaskFilter.Blur.NORMAL));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setVisibility(INVISIBLE);
        }

        @Override public boolean hasOverlappingRendering() {
            return true;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            screen.set(0, 0, w, h);
            bottom = new LinearGradient(0, 520 * k, 0, 1080 * k, 0x000B0B0D, CardPainter.BG, Shader.TileMode.CLAMP);
        }

        /** A new banner (UI thread): its texts cut and its shaders made here, never while drawing. */
        void set(StartModel.Tile t, String second, boolean animate) {
            if (t == null) return;
            String l = t.label == null ? "" : TextUtils.ellipsize(t.label, label, TEXT_W * k, TextUtils.TruncateAt.END).toString();
            String s = second == null || second.isEmpty() ? null
                    : TextUtils.ellipsize(second, line2, TEXT_W * k, TextUtils.TruncateAt.END).toString();
            Face f = new Face(t, l, s);
            if (t.art != null) {
                f.art = new BitmapShader(t.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            } else if (t.logo != null) {
                int brand = t.brand != 0 ? t.brand | 0xFF000000 : t.color != 0 ? t.color | 0xFF000000 : 0xFF2C2C2E;
                f.fill = new LinearGradient(0, 0, ART_W * k, ART_H * k, CardPainter.blend(brand, 0xFFFFFFFF, 0.12f),
                        CardPainter.blend(brand, 0xFF000000, 0.30f), Shader.TileMode.CLAMP);
                f.wash = brand;
            } else {
                int[] g = gradientOf(t.key);
                if (g != null) {
                    f.fill = new LinearGradient(0, 0, ART_W * k, ART_H * k, g[0], g[1], Shader.TileMode.CLAMP);
                    f.wash = g[0];
                } else {
                    f.flat = 0x33FFFFFF;   // a folder
                    f.wash = 0xFF2C2C2E;
                }
                if (t.symbol != null) Symbols.warm(getContext(), t.symbol, SYMBOL * k);
            }
            if (t.folderApps != null) {
                for (StartModel.Tile a : t.folderApps) {
                    if (a == null || f.appCount >= 6) continue;
                    if (a.art != null) {
                        f.appBitmaps[f.appCount] = a.art;
                        f.apps[f.appCount] = new BitmapShader(a.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    }
                    f.appCount++;
                }
            }
            if (f.wash == 0) f.wash = t.color != 0 ? t.color | 0xFF000000 : 0xFF2C2C2E;
            // The background: the app's colour as a soft light behind its card, on the dark page. The tile's tiny copy
            // stretched over the whole screen read as a pixelated logo (YouTube's white banner).
            int accent = accentOf(t.blur, t.brand != 0 ? t.brand | 0xFF000000 : f.wash);
            float cx = (ART_X + ART_W / 2) * k, cy = (ART_Y + ART_H / 2) * k;
            f.glow = new RadialGradient(cx, cy, 1250 * k,
                    new int[] {Ui.alpha(accent, 0.62f), Ui.alpha(accent, 0.22f), 0x00000000}, new float[] {0, 0.45f, 1},
                    Shader.TileMode.CLAMP);
            f.glow2 = new RadialGradient(1700 * k, 980 * k, 900 * k,
                    new int[] {Ui.alpha(CardPainter.blend(accent, 0xFF000000, 0.35f), 0.30f), 0x00000000}, null,
                    Shader.TileMode.CLAMP);
            boolean fade = animate && cur != null && !cur.key.equals(f.key);
            prev = fade ? cur : null;
            cur = f;
            mix = fade ? 0 : 1;
            invalidate();
        }

        /**
         * The banner's strongest colour: the most common saturated hue in its 32x18 copy (YouTube's red, not its white),
         * else fallback. Kept bright enough to glow on the dark page.
         */
        static int accentOf(Bitmap tiny, int fallback) {
            int out = fallback | 0xFF000000;
            if (tiny != null && !tiny.isRecycled()) {
                try {
                    int w = tiny.getWidth(), h = tiny.getHeight();
                    if (w > 0 && h > 0 && w * h <= 4096) {
                        int[] px = new int[w * h];
                        tiny.getPixels(px, 0, w, 0, 0, w, h);
                        float[] hsv = new float[3];
                        float[] weight = new float[12];
                        long[][] sum = new long[12][4];
                        for (int p : px) {
                            if ((p >>> 24) < 128) continue;
                            android.graphics.Color.colorToHSV(p, hsv);
                            if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
                            int b = Math.min(11, (int) (hsv[0] / 30));
                            weight[b] += hsv[1] * hsv[2];
                            sum[b][0]++;
                            sum[b][1] += (p >> 16) & 0xFF;
                            sum[b][2] += (p >> 8) & 0xFF;
                            sum[b][3] += p & 0xFF;
                        }
                        int best = -1;
                        for (int b = 0; b < 12; b++) if (sum[b][0] >= 3 && (best < 0 || weight[b] > weight[best])) best = b;
                        if (best >= 0) {
                            long n = sum[best][0];
                            out = 0xFF000000 | (int) (sum[best][1] / n) << 16 | (int) (sum[best][2] / n) << 8 | (int) (sum[best][3] / n);
                        }
                    }
                } catch (RuntimeException ignored) {
                    // a bitmap that can't be read: the fallback colour
                }
            }
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(out, hsv);
            if (hsv[1] < 0.15f) return 0xFF3A3A42;   // grey or white: a quiet graphite light
            hsv[2] = Math.max(hsv[2], 0.75f);
            return android.graphics.Color.HSVToColor(hsv);
        }

        private static int[] gradientOf(String key) {
            if (key == null) return INPUT;
            if (key.equals(HomeLayout.EXPLORE)) return EXPLORE;
            if (key.equals(HomeLayout.LIVE)) return LIVE;
            if (key.equals(HomeLayout.SETTINGS)) return SETTINGS;
            if (key.startsWith(HomeLayout.FOLDER)) return null;
            return INPUT;
        }

        /** a: the whole view's alpha (template crossfade × page dim); f: the art and texts' own (the page's fade). */
        void setFade(float a, float f) {
            if (Math.abs(f - fg) > 0.002f || (f == 1 && fg != 1) || (f == 0 && fg != 0)) {
                fg = f;
                invalidate();
            }
            if (a == alpha) return;
            alpha = a;
            setAlpha(a);
            boolean show = a > 0.001f && cur != null;
            int vis = show ? VISIBLE : INVISIBLE;
            if (getVisibility() != vis) setVisibility(vis);
            // A layer (8 MB at 1080p) only while it fades: at full alpha it draws straight to the screen.
            int layer = show && a < 0.999f && !Ui.LEGACY ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
            if (getLayerType() != layer) setLayerType(layer, null);
        }

        boolean step(float dt) {
            if (prev == null) return false;
            mix = Math.min(1, mix + dt / 0.4f);
            if (mix >= 1) prev = null;
            invalidate();
            return prev != null;
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                if (cur == null || bottom == null) return;
                float e = mix * mix * (3 - 2 * mix);
                if (prev != null) drawFace(c, prev, 1, fg * (1 - e));
                drawFace(c, cur, prev != null ? e : 1, fg * (prev != null ? e : 1));
            } catch (RuntimeException ex) {
                host.fault("banner draw", ex);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void drawFace(Canvas c, Face f, float washA, float fgA) {
            StartModel.Tile t = f.tile;
            int wa = Math.round(255 * Ui.clamp(washA));
            if (wa <= 0) return;
            // The wash: the dark page, lit by the app's colour behind its card and faintly in the far corner.
            fill.setShader(null);
            fill.setColor(Ui.alpha(CardPainter.BG, washA));
            c.drawRect(screen, fill);
            if (f.glow != null) {
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(wa);
                fill.setShader(f.glow);
                c.drawRect(screen, fill);
                fill.setShader(f.glow2);
                c.drawRect(screen, fill);
                fill.setShader(null);
            }
            scrim.setShader(bottom);
            scrim.setAlpha(wa);
            c.drawRect(0, 520 * k, screen.right, screen.bottom, scrim);
            scrim.setShader(null);
            if (fgA <= 0.004f) return;
            float r = ART_R * k;
            box.set(ART_X * k, ART_Y * k, (ART_X + ART_W) * k, (ART_Y + ART_H) * k);
            tmp.set(box);
            tmp.offset(0, 20 * k);
            shadow.setColor(Ui.alpha(0x8C000000, fgA));
            shadows.draw(c, tmp, r, 40 * k, shadow);
            int ia = Math.round(255 * Ui.clamp(fgA));
            if (f.art != null && t.art != null) {
                float sx = box.width() / t.art.getWidth(), sy = box.height() / t.art.getHeight();
                c.save();
                c.translate(box.left, box.top);
                c.scale(sx, sy);
                art.setShader(f.art);
                art.setAlpha(ia);
                c.drawRoundRect(0, 0, t.art.getWidth(), t.art.getHeight(), r / sx, r / sy, art);
                art.setShader(null);
                c.restore();
            } else {
                fill.setColor(0xFFFFFFFF);
                if (f.fill != null) {
                    c.save();
                    c.translate(box.left, box.top);
                    fill.setShader(f.fill);
                    fill.setAlpha(ia);
                    c.drawRoundRect(0, 0, box.width(), box.height(), r, r, fill);
                    fill.setShader(null);
                    c.restore();
                } else {
                    fill.setColor(Ui.alpha(f.flat, fgA));
                    c.drawRoundRect(box, r, r, fill);
                }
                if (t.logo != null) {
                    drawLogo(c, t, ia);
                } else if (f.appCount > 0) {
                    drawFolder(c, f, fgA);
                } else if (t.symbol != null) {
                    Symbols.draw(getContext(), c, t.symbol, box.centerX(), box.centerY(), SYMBOL * k, Ui.alpha(CardPainter.INK, fgA));
                }
            }
            int la = label.getAlpha(), sa = line2.getAlpha();
            label.setAlpha(Math.round(la * Ui.clamp(fgA)));
            c.drawText(f.label, LABEL_X * k, LABEL_BASE * k, label);
            label.setAlpha(la);
            if (f.line2 != null) {
                line2.setAlpha(Math.round(sa * Ui.clamp(fgA)));
                c.drawText(f.line2, LABEL_X * k, LINE2_BASE * k, line2);
                line2.setAlpha(sa);
            }
        }

        /** A brand app's crisp logo, centred on its gradient, at most 56% x 44% of the box (times its size tweak). */
        private void drawLogo(Canvas c, StartModel.Tile t, int a) {
            float bw = t.logo.getWidth(), bh = t.logo.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float tweak = t.logoScale > 0 ? Math.min(1.4f, t.logoScale) : 1;
            float s = Math.min(box.width() * 0.56f / bw, box.height() * 0.44f / bh) * tweak;
            float w = bw * s, h = bh * s;
            tmp.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2);
            logo.setAlpha(a);
            c.drawBitmap(t.logo, null, tmp, logo);
        }

        /** A folder: a 3x2 grid of its first apps' art, the grid tile's 70x39 cells scaled to the banner. */
        private void drawFolder(Canvas c, Face f, float a) {
            float s = ART_W / 260f, cw = 70 * k * s, ch = 39 * k * s, gap = 8 * k * s, r = 6 * k * s;
            float gw = 3 * cw + 2 * gap, gh = 2 * ch + gap;
            float x0 = box.centerX() - gw / 2, y0 = box.centerY() - gh / 2;
            int ia = Math.round(255 * Ui.clamp(a));
            for (int i = 0; i < f.appCount && i < 6; i++) {
                float x = x0 + (i % 3) * (cw + gap), y = y0 + (i / 3) * (ch + gap);
                Bitmap b = f.appBitmaps[i];
                if (b != null && f.apps[i] != null) {
                    float sx = cw / b.getWidth(), sy = ch / b.getHeight();
                    c.save();
                    c.translate(x, y);
                    c.scale(sx, sy);
                    art.setShader(f.apps[i]);
                    art.setAlpha(ia);
                    c.drawRoundRect(0, 0, b.getWidth(), b.getHeight(), r / sx, r / sy, art);
                    art.setShader(null);
                    c.restore();
                } else {
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFF2C2C2E, a));
                    c.drawRoundRect(x, y, x + cw, y + ch, r, r, fill);
                }
            }
        }
    }
}
