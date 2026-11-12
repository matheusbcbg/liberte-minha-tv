package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A grid of posters (pushed): a row's "Ver tudo", a genre with its filter chips, or a person's filmography with
 * their header. Six columns; the page scrolls so the focused line's top sits at y 250.
 */
final class GridPage extends ExplorePage {
    private static final int COLS = 6, MAX_PAGES = 10, MAX_PERSON = 60;
    private static final float STEP = 284, PITCH = 430, TOP = 250, CHIPS_Y = 170;
    private static final int T_ROWS = 0, T_GENRE = 1, T_PERSON = 2;

    private final int type;
    private final Catalog.RowSpec spec;
    private final ExploreModel.Genre genre;
    private final int personId;
    private final ExploreImages images;
    private final Tmdb tmdb;
    private Layer layer;
    private Label title, filmography;
    private PersonHeader person;
    private Grid grid;
    private Notice notice;
    private final Row row = new Row("grid", Catalog.POSTER, null, "");
    private final Row chips = new Row("chips", Catalog.CHIP, null, "");
    private Spring[] focus = new Spring[0];
    private final Spring scroll = new Spring(0.42f, 1f, 0);
    private int index, chip, kind, sort, zone = Z_GRID;
    private boolean loading, built;
    private long sweepAt = -1;
    private String[] genrePaths;
    private Tmdb.Ticket ticket;
    private final RectF rect = new RectF();
    private float gridTop = TOP;

    private GridPage(ExploreView v, int type, Catalog.RowSpec spec, ExploreModel.Genre genre, int personId) {
        super(v);
        this.type = type;
        this.spec = spec;
        this.genre = genre;
        this.personId = personId;
        images = v.images();
        tmdb = Tmdb.get(v.getContext());
        kind = genre != null && genre.kind == ExploreModel.TV ? ExploreModel.TV : ExploreModel.MOVIE;
    }

    static GridPage rows(ExploreView v, Catalog.RowSpec spec) {
        return new GridPage(v, T_ROWS, spec, null, 0);
    }

    static GridPage genre(ExploreView v, ExploreModel.Genre g) {
        return new GridPage(v, T_GENRE, null, g, 0);
    }

    static GridPage person(ExploreView v, int personId) {
        return new GridPage(v, T_PERSON, null, null, personId);
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
        if (type == T_PERSON) {
            gridTop = 420;
            person = new PersonHeader(c);
            layer.add(person, 0, 0, 1920, 420);
            filmography = new Label(c, Ui.text(Ui.semibold, 30 * k, Ui.alpha(CardPainter.INK, 0.92f)), 30 * k);
            layer.add(filmography, 96, 366, 1200, 50);
            filmography.set(Ui.tr("Filmography"));
        } else {
            title = new Label(c, Ui.text(Ui.displaySemibold, 52 * k, CardPainter.INK), 130 * k);
            layer.add(title, 96, 0, 1728, 160);
            title.set(type == T_GENRE ? genre.name : spec != null ? spec.title : "");
        }
        grid = new Grid(c);
        layer.add(grid, 0, 0, 1920, 1080);
        notice = new Notice(c, k, true);
        layer.add(notice, 460, 400, 1000, 320);
        if (type == T_GENRE) makeChips();
        load(1);
    }

    private void makeChips() {
        String[] labels = {Ui.tr("Movies"), Ui.tr("Shows"), Ui.tr("Popular"), Ui.tr("Top rated"), Ui.tr("Latest releases")};
        Card[] cards = new Card[labels.length];
        for (int i = 0; i < labels.length; i++) {
            Card c = new Card();
            c.type = Card.CHIP;
            c.id = i;
            c.title = labels[i];
            c.dim = i == (kind == ExploreModel.TV ? 1 : 0) || i == 2 + sort;
            cards[i] = c;
        }
        chips.set(cards, cards.length);
        chips.state = Row.READY;
        host.painter.prepare(chips, 0, chips.count);
    }

    private void load(int page) {
        if (loading) return;
        final int gen = generation;
        loading = true;
        if (page == 1) {
            row.set(new Card[0], 0);
            row.page = 0;
            row.totalPages = 1;
            row.state = Row.LOADING;
            index = 0;
            scroll.target = 0;
        }
        if (type == T_PERSON) {
            ticket = tmdb.person(personId, Tmdb.NOW, (p, status, fresh) -> {
                if (!stale(gen)) deliverPerson(p, status);
            });
            return;
        }
        String[] paths;
        if (type == T_GENRE) {
            if (genrePaths == null) {
                try {
                    genrePaths = Catalog.genreGrid(kind, genre.id, sort, System.currentTimeMillis());
                } catch (RuntimeException e) {
                    Safe.log("catalog", e);
                    genrePaths = new String[0];
                }
            }
            paths = new String[genrePaths.length];
            for (int i = 0; i < paths.length; i++) paths[i] = TmdbPaths.withPage(genrePaths[i], page);
        } else {
            paths = spec.paths(page);
        }
        if (paths == null || paths.length == 0) {
            loading = false;
            row.state = Row.FAILED;
            notice.show(Ui.tr("Couldn't load. Press OK to try again."));
            return;
        }
        ExploreModel.Page cached = page == 1 ? tmdb.peek(paths) : null;
        if (cached != null) deliver(cached, 1, Tmdb.OK, true);
        final int pageNo = page;
        ticket = tmdb.page(paths, page == 1 ? Tmdb.NOW : Tmdb.SOON, (value, status, fresh) -> {
            if (!stale(gen)) deliver(value, pageNo, status, false);
        });
    }

    private void deliver(ExploreModel.Page value, int page, int status, boolean peeked) {
        if (!peeked) loading = false;
        if (value == null || value.items == null) {
            if (row.count == 0 && !peeked) {
                row.state = status == Tmdb.OK ? Row.READY : Row.FAILED;
                if (row.state == Row.FAILED) notice.show(Ui.tr("Couldn't load. Press OK to try again."));
                if (status == Tmdb.OFFLINE) host.toast(Ui.tr("No connection. Showing what was saved."));
            }
            grid.invalidate();
            return;
        }
        ArrayList<Card> cards = new ArrayList<>();
        for (ExploreModel.Title t : value.items) {
            if (t == null || t.kind == ExploreModel.PERSON) continue;
            cards.add(BrowsePage.titleCard(t, Catalog.POSTER, 0, false));
        }
        Card[] arr = cards.toArray(new Card[0]);
        if (page == 1) row.set(arr, arr.length);
        else row.append(arr, arr.length);
        row.page = Math.max(row.page, page);
        row.totalPages = Math.max(1, value.totalPages);
        row.state = Row.READY;
        notice.show(null);
        cardsChanged();
    }

    private void deliverPerson(ExploreModel.Person p, int status) {
        loading = false;
        if (p == null) {
            if (row.count == 0) {
                row.state = Row.FAILED;
                notice.show(Ui.tr("Couldn't load. Press OK to try again."));
            }
            return;
        }
        person.set(p);
        ExploreModel.Title[] credits = p.credits == null ? new ExploreModel.Title[0] : p.credits.clone();
        Arrays.sort(credits, (a, b) -> a == null ? 1 : b == null ? -1 : Float.compare(b.popularity, a.popularity));
        ArrayList<Card> cards = new ArrayList<>();
        for (ExploreModel.Title t : credits) {
            if (t == null || t.kind == ExploreModel.PERSON || cards.size() >= MAX_PERSON) continue;
            Card c = BrowsePage.titleCard(t, Catalog.POSTER, 0, false);
            if (t.character != null && !t.character.isEmpty()) c.sub = t.character;
            cards.add(c);
        }
        Card[] arr = cards.toArray(new Card[0]);
        row.set(arr, arr.length);
        row.page = row.totalPages = 1;
        row.state = Row.READY;
        notice.show(null);
        cardsChanged();
    }

    private void cardsChanged() {
        if (focus.length < row.count) {
            Spring[] grown = new Spring[Math.max(row.count, focus.length * 2)];
            System.arraycopy(focus, 0, grown, 0, focus.length);
            for (int i = focus.length; i < grown.length; i++) grown[i] = new Spring(0.30f, 0.80f, 0);
            focus = grown;
        }
        index = Math.max(0, Math.min(index, row.count - 1));
        if (zone == Z_GRID && row.count > 0) focus[index].target = 1;
        prepareVisible();
        wants();
        grid.invalidate();
        host.wake();
    }

    private int lines() {
        return (row.count + COLS - 1) / COLS;
    }

    private float scrollFor(int line) {
        return line <= 0 ? 0 : Math.max(0, (gridTop + line * PITCH - TOP) * k);
    }

    private void prepareVisible() {
        int line = index / COLS;
        host.painter.prepare(row, Math.max(0, (line - 1) * COLS), Math.min(row.count, (line + 4) * COLS));
    }

    private void wants() {
        int line = index / COLS;
        int firstLine = Math.max(0, line - 1);
        for (int i = firstLine * COLS; i < Math.min(row.count, (line + 3) * COLS); i++) want(i, ExploreImages.NOW);
        for (int i = (line + 3) * COLS; i < Math.min(row.count, (line + 5) * COLS); i++) want(i, ExploreImages.SOON);
    }

    private void want(int i, int priority) {
        Card c = row.cards[i];
        if (c != null && c.image != null) images.want(c.imageSpec, c.image, priority);
    }

    private void focusCard(int i) {
        if (row.count == 0) return;
        i = Math.max(0, Math.min(i, row.count - 1));
        if (index < focus.length) focus[index].target = 0;
        index = i;
        focus[i].target = 1;
        sweepAt = SystemClock.uptimeMillis();
        scroll.target = scrollFor(i / COLS);
        prepareVisible();
        wants();
        int line = i / COLS;
        if (line >= lines() - 2 && row.page < row.totalPages && row.page < MAX_PAGES && type != T_PERSON) load(row.page + 1);
        host.click().move();
        grid.invalidate();
        host.wake();
    }

    // MARK: - ExplorePage

    @Override boolean onKey(int keyCode, KeyEvent e, boolean longPress) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        int page = keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP ? -1
                : keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN ? 1 : 0;
        if (ok && row.state == Row.FAILED && zone != Z_CHIPS) {
            notice.show(null);
            load(1);
            return true;
        }
        if (zone == Z_CHIPS) {
            if (dx != 0) {
                int n = Math.max(0, Math.min(chips.count - 1, chip + dx));
                if (n != chip) {
                    chip = n;
                    host.click().move();
                    grid.invalidate();
                }
            } else if (dy > 0 || page > 0) {
                if (row.count > 0) {
                    zone = Z_GRID;
                    focusCard(Math.min(index, COLS - 1));
                }
            } else if (ok) {
                host.click().open();
                if (!pickChip(chip) && row.state == Row.FAILED) {   // the chosen chip again: try its load once more
                    notice.show(null);
                    load(1);
                }
            } else return dy < 0;
            host.wake();
            return true;
        }
        if (ok) {
            if (row.count == 0) return true;
            Card c = row.cards[index];
            cardBounds(index, rect);
            if (longPress && (c.kind == ExploreModel.MOVIE || c.kind == ExploreModel.TV)) {
                final Card held = c;
                BrowsePage.titleMenu(host, tmdb, c, rect, () -> {
                    host.click().open();
                    host.push(new DetailPage(host, held.kind, held.id, false)
                            .withTitle(held.ref instanceof ExploreModel.Title ? (ExploreModel.Title) held.ref : null));
                });
                return true;
            }
            host.click().open();
            host.push(new DetailPage(host, c.kind, c.id, false).withTitle(c.ref instanceof ExploreModel.Title ? (ExploreModel.Title) c.ref : null));
            return true;
        }
        if (row.count == 0) {
            if (dy < 0 && type == T_GENRE) {
                zone = Z_CHIPS;
                grid.invalidate();
            }
            return dx != 0 || dy != 0 || page != 0;
        }
        int col = index % COLS, line = index / COLS;
        if (dx != 0) {
            int nc = col + dx;
            if (nc >= 0 && nc < COLS && index + dx < row.count) focusCard(index + dx);
            return true;
        }
        if (dy != 0 || page != 0) {
            int nl = line + (page != 0 ? page * 3 : dy);
            if (nl < 0) {
                if (type == T_GENRE && line == 0) {
                    focus[index].target = 0;
                    zone = Z_CHIPS;
                    scroll.target = 0;
                    host.click().move();
                    grid.invalidate();
                    host.wake();
                } else if (line > 0) focusCard(col);
                return true;
            }
            nl = Math.min(nl, lines() - 1);
            if (nl != line) focusCard(Math.min(nl * COLS + col, row.count - 1));
            return true;
        }
        return false;
    }

    /** False when the chip was already the chosen one (nothing changed). */
    private boolean pickChip(int i) {
        if (i <= 1) {
            int k2 = i == 1 ? ExploreModel.TV : ExploreModel.MOVIE;
            if (k2 == kind) return false;
            kind = k2;
        } else {
            if (i - 2 == sort) return false;
            sort = i - 2;
        }
        genrePaths = null;
        generation++;
        if (ticket != null) ticket.cancel();
        loading = false;
        makeChips();
        load(1);
        grid.invalidate();
        return true;
    }

    @Override boolean back() {
        return false;
    }

    @Override boolean step(float dt) {
        boolean busy = scroll.step(dt);
        boolean moved = busy;
        for (int i = 0; i < Math.min(row.count, focus.length); i++) {
            Spring s = focus[i];
            if (s.value != s.target || s.velocity != 0) moved |= s.step(dt);
        }
        if (sweepAt >= 0) {
            if (SystemClock.uptimeMillis() - sweepAt < 550) moved = true;
            else {
                sweepAt = -1;
                moved = true;
            }
        }
        float s = scroll.value;
        if (title != null) title.setTranslationY(-s);
        if (person != null) person.setTranslationY(-s);
        if (filmography != null) filmography.setTranslationY(-s);
        if (host.isCurrent(this)) {
            host.backdrop().setDim(0.85f);
            host.backdrop().setParallax(0);
        }
        if (moved) grid.invalidate();
        return busy || moved;
    }

    @Override float pageScroll() {
        return scroll.value;
    }

    @Override void shown() {
        host.backdrop().show(null, true);
        host.backdrop().setDim(0.85f);
        if (row.count > 0 && zone == Z_GRID) focus[index].target = 1;
    }

    @Override void destroy() {
        super.destroy();
        if (ticket != null) ticket.cancel();
    }

    @Override void imagesArrived() {
        if (grid.missing > 0 || (person != null && !person.drawnPicture)) {
            grid.invalidate();
            if (person != null) person.invalidate();
        }
    }

    @Override void reload() {
        if (row.state == Row.FAILED) {
            notice.show(null);
            load(1);
        }
    }

    private boolean cardBounds(int i, RectF out) {
        if (i < 0 || i >= row.count) return false;
        float x = (96 + (i % COLS) * STEP) * k, y = (gridTop + (i / COLS) * PITCH) * k - scroll.value;
        out.set(x, y, x + 220 * k, y + 330 * k);
        return true;
    }

    private float chipLeft(int i) {
        float x = 96 * k;
        for (int j = 0; j < i; j++) x += chips.cards[j].chipW + (j == 1 ? 40 : 16) * k;
        return x;
    }

    // MARK: - TalkBack (grid 5001…, chips 6001…)

    @Override void a11yItems(List<Integer> out) {
        if (type == T_GENRE) for (int i = 0; i < chips.count; i++) out.add(G_CHIPS * 1000 + i + 1);
        int line = index / COLS;
        for (int i = Math.max(0, (line - 1) * COLS); i < Math.min(row.count, Math.min(999, (line + 3) * COLS)); i++) {
            out.add(G_GRID * 1000 + i + 1);
        }
    }

    @Override String a11yText(int id) {
        int i = id % 1000 - 1;
        if (id / 1000 == G_CHIPS) return i >= 0 && i < chips.count ? chips.cards[i].title : "";
        if (i < 0 || i >= row.count) return "";
        Card c = row.cards[i];
        return c.sub == null || c.sub.isEmpty() ? c.title : c.title + ", " + c.sub;
    }

    @Override boolean a11yBounds(int id, Rect r) {
        int i = id % 1000 - 1;
        if (id / 1000 == G_CHIPS) {
            if (i < 0 || i >= chips.count) return false;
            float x = chipLeft(i), y = CHIPS_Y * k - scroll.value;
            r.set(Math.round(x), Math.round(y), Math.round(x + chips.cards[i].chipW), Math.round(y + 52 * k));
            return true;
        }
        if (!cardBounds(i, rect)) return false;
        rect.round(r);
        return true;
    }

    @Override int a11yFocused() {
        if (zone == Z_CHIPS) return G_CHIPS * 1000 + chip + 1;
        return row.count > 0 ? G_GRID * 1000 + index + 1 : -1;
    }

    @Override void a11yFocus(int id) {
        int i = id % 1000 - 1;
        if (id / 1000 == G_CHIPS) {
            if (zone == Z_GRID && index < focus.length) focus[index].target = 0;
            zone = Z_CHIPS;
            chip = Math.max(0, Math.min(chips.count - 1, i));
            grid.invalidate();
            return;
        }
        zone = Z_GRID;
        focusCard(i);
    }

    @Override void a11yClick(int id) {
        a11yFocus(id);
        onKey(KeyEvent.KEYCODE_DPAD_CENTER, null, false);
    }

    /** The posters (and the genre chips): one plain View, only the visible lines drawn. */
    private final class Grid extends View {
        int missing;

        Grid(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "grid draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            CardPainter p = host.painter;
            long now = SystemClock.uptimeMillis();
            float s = scroll.value;
            if (type == T_GENRE) {
                float y = CHIPS_Y * k - s;
                for (int i = 0; i < chips.count; i++) {
                    p.draw(c, chips, chips.cards[i], chipLeft(i), y - 2 * k, zone == Z_CHIPS && i == chip ? 1 : 0, 0, -1, null, null, null, now);
                }
            }
            float top = gridTop * k - s;
            if (row.state != Row.READY || row.count == 0) {
                missing = 0;
                if (row.state == Row.LOADING) {
                    for (int i = 0; i < 12; i++) p.drawSkeleton(c, Catalog.POSTER, (96 + (i % COLS) * STEP) * k, top + (i / COLS) * PITCH * k);
                }
                return;
            }
            int firstLine = Math.max(0, (int) Math.floor((s - gridTop * k) / (PITCH * k)) - 1);
            int lastLine = (int) Math.ceil((s + 1080 * k - gridTop * k) / (PITCH * k)) + 1;
            int from = firstLine * COLS, to = Math.min(row.count, (lastLine + 1) * COLS);
            int waiting = 0;
            boolean fading = false;
            float sweep = sweepAt >= 0 ? (now - sweepAt) / 550f : -1;
            int top1 = zone == Z_GRID ? index : -1;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = from; i < to; i++) {
                    if ((pass == 0) == (i == top1)) continue;
                    Card card = row.cards[i];
                    float x = (96 + (i % COLS) * STEP) * k, y = top + (i / COLS) * PITCH * k;
                    ExploreImages.Img img = card.image == null ? null : images.get(card.imageSpec, card.image);
                    if (card.image != null && img == null) waiting++;
                    else if (img != null && CardPainter.fading(img, now)) fading = true;
                    Spring f = i < focus.length ? focus[i] : null;
                    p.draw(c, row, card, x, y, f == null ? 0 : f.value, f == null ? 0 : f.velocity, i == top1 ? sweep : -1,
                            img, null, null, now);
                }
            }
            missing = waiting;
            if (fading) postInvalidateOnAnimation();
        }
    }

    /** A person's header: the round photo, the name and four lines of biography. HW layer, drawn once. */
    private final class PersonHeader extends View {
        private final Paint name, fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint bio;
        private String nameText = "";
        private String picture;
        private StaticLayout bioLayout;
        boolean drawnPicture = true;
        private final RectF box = new RectF();

        PersonHeader(Context c) {
            super(c);
            name = Ui.text(Ui.displaySemibold, 56 * k, CardPainter.INK);
            bio = new TextPaint(Ui.text(Ui.regular, 24 * k, CardPainter.DIM));
            setLayerType(LAYER_TYPE_HARDWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(ExploreModel.Person p) {
            nameText = p.name == null ? "" : p.name;
            picture = p.profile;
            String b = p.biography == null ? "" : p.biography;
            bioLayout = StaticLayout.Builder.obtain(b, 0, b.length(), bio, Math.round(1440 * k)).setMaxLines(4)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(8 * k, 1).build();
            if (picture != null) images.want(ExploreImages.PROFILE, picture, ExploreImages.NOW);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            try {
                float d = 240 * k;
                box.set(96 * k, 120 * k, 96 * k + d, 120 * k + d);
                ExploreImages.Img img = picture == null ? null : images.get(ExploreImages.PROFILE, picture);
                drawnPicture = picture == null || img != null;
                if (img != null) host.painter.drawImage(c, img.bitmap, img.shader, box, d / 2, 0, 1);
                else {
                    fill.setColor(CardPainter.PLACEHOLDER);
                    c.drawRoundRect(box, d / 2, d / 2, fill);
                }
                c.drawText(nameText, 380 * k, 196 * k, name);
                if (bioLayout != null) {
                    c.save();
                    c.translate(380 * k, 222 * k);
                    bioLayout.draw(c);
                    c.restore();
                }
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "person draw", e);
            }
        }
    }
}
