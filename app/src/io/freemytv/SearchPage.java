package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Buscar: the remote's keyboard on the left, the results on the right (recent searches and trending while the query
 * is short). Bluetooth keyboards type straight in; the SEARCH key starts voice input when the TV has a recognizer.
 */
final class SearchPage extends ExplorePage implements BrowseStack.Host {
    private static final int COLS = 7, ROWS = 6, MAX_RECENT = 8, MAX_QUERY = 60;
    private static final long DEBOUNCE_MS = 400;
    private static final String FILE = "explore-searches.json";
    /** Key ids per cell: a character, or SPACE / DEL (each two cells wide). */
    private static final String SPACE = " ", DEL = "\b";
    private static final String[][] KEYS = {
            {"a", "b", "c", "d", "e", "f", "g"},
            {"h", "i", "j", "k", "l", "m", "n"},
            {"o", "p", "q", "r", "s", "t", "u"},
            {"v", "w", "x", "y", "z", "ç", "'"},
            {"1", "2", "3", "4", "5", "6", "7"},
            {"8", "9", "0", SPACE, SPACE, DEL, DEL}};

    private final ExploreImages images;
    private final Tmdb tmdb;
    private Layer layer;
    private Keyboard keyboard;
    private Label note;
    private BrowseStack stack;
    private final Row recents, trending, results, people;
    private final List<Row> shownRows = new ArrayList<>();
    private String query = "";
    private int keyRow, keyCol, zone = Z_TOPBAR, searchGen;
    private Tmdb.Ticket searchTicket, trendTicket;
    private final ArrayList<String> recent = new ArrayList<>();
    private boolean built, recentLoaded;
    private final Runnable debounce = ExploreView.guard(host, "search", this::search);
    private final RectF rect = new RectF();

    SearchPage(ExploreView v) {
        super(v);
        images = v.images();
        tmdb = Tmdb.get(v.getContext());
        recents = new Row("recents", Catalog.CHIP, null, Ui.tr("Recent searches"));
        trending = new Row("trending", Catalog.POSTER, null, Ui.tr("Trending today"));
        results = new Row("results", Catalog.POSTER, null, Ui.tr("Movies and shows"));
        people = new Row("people", Catalog.CAST, null, Ui.tr("People"));
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
        stack = new BrowseStack(layer, 0, c, host.painter, images, host.watchArt(), this);
        stack.setExitLeft(true);
        stack.setGeometry(744 * k, 120 * k, 120 * k, 116 * k);
        keyboard = new Keyboard(c);
        layer.add(keyboard, 0, 0, 720, 1080);
        note = new Label(c, Ui.text(Ui.medium, 28 * k, CardPainter.DIM), 40 * k);
        layer.add(note, 744, 110, 1080, 60);
        loadRecents();
        showIdle();
    }

    // MARK: - Query

    private void showIdle() {
        searchGen++;
        if (searchTicket != null) searchTicket.cancel();
        note.set("");
        stack.setGeometry(744 * k, 120 * k, 120 * k, 116 * k);
        shownRows.clear();
        if (!recent.isEmpty()) {
            Card[] cards = new Card[recent.size()];
            for (int i = 0; i < cards.length; i++) {
                Card c = new Card();
                c.type = Card.CHIP;
                c.title = recent.get(i);
                cards[i] = c;
            }
            recents.set(cards, cards.length);
            recents.state = Row.READY;
            host.painter.prepare(recents, 0, recents.count);
            shownRows.add(recents);
        }
        shownRows.add(trending);
        loadTrending();
        stack.setRows(shownRows);
    }

    private void loadTrending() {
        if (trending.state == Row.READY && trending.count > 0) return;
        if (!Tmdb.hasKey()) {
            note.set(Tmdb.rejected() ? Ui.tr("The TMDB key was refused. Check it in Home Settings.")
                    : Ui.tr("Add a TMDB key in Home Settings to explore."));
            trending.state = Row.READY;
            return;
        }
        final int gen = generation;
        String[] paths = {TmdbPaths.trending("all", false, 1)};
        ExploreModel.Page cached = tmdb.peek(paths);
        if (cached != null) fill(trending, cached, false);
        else trending.state = Row.LOADING;
        if (trendTicket != null) trendTicket.cancel();
        trendTicket = tmdb.page(paths, Tmdb.SOON, (value, status, fresh) -> {
            if (stale(gen)) return;
            if (value != null) fill(trending, value, false);
            else if (trending.count == 0) trending.state = status == Tmdb.OK ? Row.READY : Row.FAILED;
            stack.rowChanged(trending);
        });
    }

    private void fill(Row r, ExploreModel.Page value, boolean append) {
        ArrayList<Card> titles = new ArrayList<>();
        if (value.items != null) {
            for (ExploreModel.Title t : value.items) {
                if (t == null || t.kind == ExploreModel.PERSON) continue;
                titles.add(BrowsePage.titleCard(t, Catalog.POSTER, 0, false));
            }
        }
        Card[] arr = titles.toArray(new Card[0]);
        if (append) r.append(arr, arr.length);
        else r.set(arr, arr.length);
        r.page = Math.max(append ? r.page : 0, value.page > 0 ? value.page : 1);
        r.totalPages = Math.max(1, value.totalPages);
        r.state = Row.READY;
    }

    private void queryChanged() {
        keyboard.queryChanged();
        host.handler().removeCallbacks(debounce);
        if (query.trim().length() < 2) {
            showIdle();
            return;
        }
        host.handler().postDelayed(debounce, DEBOUNCE_MS);
    }

    private void search() {
        final String q = query.trim();
        if (q.length() < 2 || !Tmdb.hasKey()) return;
        if (searchTicket != null) searchTicket.cancel();
        final int gen = ++searchGen;
        results.set(new Card[0], 0);
        results.state = Row.LOADING;
        results.page = 0;
        people.set(new Card[0], 0);
        people.state = Row.READY;
        note.set("");
        stack.setGeometry(744 * k, 120 * k, 120 * k, 116 * k);
        shownRows.clear();
        shownRows.add(results);
        shownRows.add(people);
        stack.setRows(shownRows);
        searchTicket = tmdb.search(q, 1, (value, status, fresh) -> {
            if (gen != searchGen || !q.equals(query.trim())) return;
            deliver(q, value, status, 1);
        });
    }

    private void deliver(String q, ExploreModel.Page value, int status, int page) {
        if (value == null || value.items == null) {
            if (page == 1) {
                results.state = status == Tmdb.OK ? Row.READY : Row.FAILED;
                if (status == Tmdb.OFFLINE) host.toast(Ui.tr("No connection. Showing what was saved."));
                noResults(q);
            }
            return;
        }
        ArrayList<ExploreModel.Title> titles = new ArrayList<>(), persons = new ArrayList<>();
        for (ExploreModel.Title t : value.items) {
            if (t == null) continue;
            if (t.kind == ExploreModel.PERSON) persons.add(t);
            else titles.add(t);
        }
        ExploreModel.Title[] sorted = titles.toArray(new ExploreModel.Title[0]);
        Arrays.sort(sorted, (a, b) -> Float.compare(b.popularity, a.popularity));
        Card[] cards = new Card[sorted.length];
        for (int i = 0; i < sorted.length; i++) cards[i] = BrowsePage.titleCard(sorted[i], Catalog.POSTER, 0, false);
        if (page == 1) results.set(cards, cards.length);
        else results.append(cards, cards.length);
        results.page = page;
        results.totalPages = Math.max(1, value.totalPages);
        results.state = Row.READY;
        Card[] ps = new Card[persons.size()];
        for (int i = 0; i < ps.length; i++) {
            ExploreModel.Title t = persons.get(i);
            Card c = new Card();
            c.type = Card.PERSON;
            c.kind = ExploreModel.PERSON;
            c.id = t.id;
            c.title = t.title;
            c.sub = t.character == null ? "" : t.character;
            c.image = t.profile;
            c.imageSpec = ExploreImages.PROFILE;
            c.ref = t;
            ps[i] = c;
        }
        if (page == 1) people.set(ps, ps.length);
        else people.append(ps, ps.length);
        people.state = Row.READY;
        if (page == 1 && results.count == 0 && people.count == 0) {
            noResults(q);
            return;
        }
        stack.rowChanged(results);
        stack.rowChanged(people);
    }

    private void noResults(String q) {
        note.set(Ui.trf("No results for “{query}”", "query", q));
        stack.setGeometry(744 * k, 190 * k, 190 * k, 116 * k);
        shownRows.clear();
        shownRows.add(trending);
        loadTrending();
        stack.setRows(shownRows);
    }

    private void type(String s) {
        if (query.length() >= MAX_QUERY) return;
        query = query + s;
        queryChanged();
    }

    private void backspace() {
        if (query.isEmpty()) return;
        query = query.substring(0, query.length() - 1);
        queryChanged();
    }

    private void clear() {
        if (query.isEmpty()) return;
        query = "";
        queryChanged();
    }

    void voiceResult(String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.length() > MAX_QUERY) t = t.substring(0, MAX_QUERY);
        query = t;
        keyboard.queryChanged();
        host.handler().removeCallbacks(debounce);
        if (query.length() >= 2) search();
        else showIdle();
        host.wake();
    }

    void startVoice() {
        host.startVoice(TmdbPaths.LANG);
    }

    // MARK: - Recent searches (filesDir/explore-searches.json, written on Safe.IO)

    private void loadRecents() {
        final File f = new File(context().getFilesDir(), FILE);
        final int gen = generation;
        Safe.IO.execute(Safe.wrap("searches read", () -> {
            final ArrayList<String> list = new ArrayList<>();
            if (f.exists() && f.length() < 64 * 1024) {
                try (InputStream in = new FileInputStream(f)) {
                    byte[] data = new byte[(int) f.length()];
                    int n = 0;
                    while (n < data.length) {
                        int r = in.read(data, n, data.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    JSONArray a = new JSONArray(new String(data, 0, n, StandardCharsets.UTF_8));
                    for (int i = 0; i < a.length() && list.size() < MAX_RECENT; i++) {
                        String s = a.optString(i, "").trim();
                        if (!s.isEmpty() && s.length() <= MAX_QUERY) list.add(s);
                    }
                } catch (IOException | org.json.JSONException e) {
                    Safe.log("searches", e);
                }
            }
            Safe.post(host.handler(), "searches", () -> {
                if (stale(gen)) return;
                recentLoaded = true;
                recent.clear();
                recent.addAll(list);
                if (query.trim().length() < 2) showIdle();
            });
        }));
    }

    /** A result was opened: its query goes to the front of the recent ones. */
    private void remember() {
        String q = query.trim();
        if (q.length() < 2) return;
        for (int i = recent.size() - 1; i >= 0; i--) if (recent.get(i).equalsIgnoreCase(q)) recent.remove(i);
        recent.add(0, q);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        final String json = new JSONArray(recent).toString();
        final File dir = context().getFilesDir();
        Safe.IO.execute(Safe.wrap("searches write", () -> {
            File part = new File(dir, FILE + ".part"), out = new File(dir, FILE);
            try (FileOutputStream o = new FileOutputStream(part)) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
                o.getFD().sync();
            } catch (IOException e) {
                Safe.log("searches", e);
                return;
            }
            if (!part.renameTo(out)) Safe.log("searches", new IOException());
        }));
    }

    // MARK: - BrowseStack.Host

    @Override public void onActivate(Row r, Card c, RectF from) {
        switch (c.type) {
            case Card.CHIP:
                host.click().open();
                query = c.title == null ? "" : c.title;
                keyboard.queryChanged();
                host.handler().removeCallbacks(debounce);
                search();
                return;
            case Card.TITLE:
                host.click().open();
                if (r != trending) remember();
                host.push(new DetailPage(host, c.kind, c.id, false).withTitle(c.ref instanceof ExploreModel.Title ? (ExploreModel.Title) c.ref : null));
                return;
            case Card.PERSON:
                host.click().open();
                remember();
                host.push(GridPage.person(host, c.id));
                return;
            default:
        }
    }

    @Override public void onLongPress(Row r, Card c, RectF from) {
        if (c.type == Card.TITLE && (c.kind == ExploreModel.MOVIE || c.kind == ExploreModel.TV)) {
            if (r != trending) remember();
            BrowsePage.titleMenu(host, tmdb, c, from, () -> onActivate(r, c, from));
            return;
        }
        onActivate(r, c, from);
    }

    @Override public void onFocus(Row r, Card c) {
        host.click().move();
    }

    @Override public void onNeedRow(Row r, int priority) {
        if (r == trending && r.state == Row.EMPTY) loadTrending();
    }

    @Override public void onNeedMore(Row r) {
        if (r != results || results.page != 1 || results.totalPages < 2) return;
        final String q = query.trim();
        final int gen = searchGen;
        results.page = 2;   // asked once
        searchTicket = tmdb.search(q, 2, (value, status, fresh) -> {
            if (gen != searchGen || !q.equals(query.trim())) return;
            if (value != null) deliver(q, value, status, 2);
        });
    }

    @Override public void wake() {
        host.wake();
    }

    // MARK: - ExplorePage

    @Override boolean onKey(int keyCode, KeyEvent e, boolean longPress) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        int dx = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP) dy = -1;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) dy = 1;
        if (!ok && dx == 0 && dy == 0) return typed(keyCode, e);
        if (zone == Z_ROWS && !stack.hasFocus()) {
            toKeyboard();
            return true;
        }
        if (zone == Z_ROWS) {
            if (ok) return stack.activate(longPress);
            int res = stack.onKey(dx, dy);
            if (res == BrowseStack.EXIT_LEFT) toKeyboard();
            else if (res == BrowseStack.EXIT_UP) host.focusTopBar();
            return true;
        }
        if (zone != Z_KEYBOARD) {
            focusFromTop();
            return true;
        }
        if (ok) {
            String key = KEYS[keyRow][keyCol];
            host.click().open();
            if (DEL.equals(key)) {
                if (longPress) clear();
                else backspace();
            } else if (!longPress || !SPACE.equals(key)) {
                type(key);
            }
            return true;
        }
        if (dx != 0) {
            int c = keyCol;
            String was = KEYS[keyRow][c];
            do {
                c += dx;
            } while (c >= 0 && c < COLS && KEYS[keyRow][c].equals(was) && (was.equals(SPACE) || was.equals(DEL)));
            if (c >= COLS) {
                if (!stack.shownRows().isEmpty()) {
                    zone = Z_ROWS;
                    keyboard.invalidate();
                    stack.enter(true, 744 * k);
                }
                return true;
            }
            if (c < 0) return true;
            keyCol = c;
            host.click().move();
            keyboard.invalidate();
            return true;
        }
        int r = keyRow + dy;
        if (r < 0) {
            host.focusTopBar();
            return true;
        }
        if (r >= ROWS) return true;
        keyRow = r;
        host.click().move();
        keyboard.invalidate();
        return true;
    }

    /** A Bluetooth keyboard: printable characters, DEL and SPACE go into the query. */
    private boolean typed(int keyCode, KeyEvent e) {
        if (e == null || e.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            backspace();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            type(" ");
            return true;
        }
        int ch = e.getUnicodeChar();
        if (ch <= 0 || Character.isISOControl(ch)) return false;
        type(new String(Character.toChars(ch)).toLowerCase(Locale.ROOT));
        return true;
    }

    private void toKeyboard() {
        stack.leave();
        zone = Z_KEYBOARD;
        keyboard.invalidate();
        host.click().move();
    }

    @Override boolean back() {
        if (zone == Z_ROWS) {
            toKeyboard();
            return true;
        }
        zone = Z_TOPBAR;
        keyboard.invalidate();
        return false;
    }

    @Override void focusFromTop() {
        if (zone == Z_ROWS) stack.leave();
        zone = Z_KEYBOARD;
        keyRow = 0;
        keyboard.invalidate();
    }

    @Override void blur() {
        if (zone == Z_ROWS) stack.leave();
        zone = Z_TOPBAR;
        keyboard.invalidate();
    }

    @Override boolean atTop() {
        return true;
    }

    @Override boolean step(float dt) {
        keyboard.step();
        return stack.step(dt);
    }

    @Override float pageScroll() {
        return stack.scroll();
    }

    @Override void shown() {
        host.backdrop().show(null, true);
        host.backdrop().setDim(0);
        host.backdrop().setParallax(0);
        if (host.topFocused()) {
            if (zone == Z_ROWS) stack.leave();
            zone = Z_TOPBAR;
            keyboard.invalidate();
        } else if (zone == Z_TOPBAR) {
            focusFromTop();
        }
        if (!recentLoaded) loadRecents();
    }

    @Override void hidden() {
        host.handler().removeCallbacks(debounce);
    }

    @Override void destroy() {
        super.destroy();
        host.handler().removeCallbacks(debounce);
        if (searchTicket != null) searchTicket.cancel();
        if (trendTicket != null) trendTicket.cancel();
        stack.destroy();
    }

    @Override void imagesArrived() {
        stack.imagesArrived();
    }

    @Override void reload() {
        if (trending.state == Row.FAILED || trending.count == 0) {
            trending.state = Row.EMPTY;
            loadTrending();
            stack.rowChanged(trending);
        }
        if (results.state == Row.FAILED && query.trim().length() >= 2) search();
    }

    // MARK: - TalkBack (keys 4001…, the query 4100)

    @Override void a11yItems(List<Integer> out) {
        out.add(G_KEYBOARD * 1000 + 100);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (c > 0 && KEYS[r][c].equals(KEYS[r][c - 1]) && KEYS[r][c].length() == 1 && (KEYS[r][c].equals(SPACE) || KEYS[r][c].equals(DEL))) continue;
                out.add(G_KEYBOARD * 1000 + r * COLS + c + 1);
            }
        }
        if (!note.text().isEmpty()) out.add(G_KEYBOARD * 1000 + 101);
        stack.a11yItems(G_ROWS, out);
    }

    @Override String a11yText(int id) {
        if (id == G_KEYBOARD * 1000 + 100) return query.isEmpty() ? Ui.tr("Search movies, shows and people") : query;
        if (id == G_KEYBOARD * 1000 + 101) return note.text();
        if (id / 1000 == G_KEYBOARD) {
            int i = id % 1000 - 1;
            if (i < 0 || i >= ROWS * COLS) return "";
            String key = KEYS[i / COLS][i % COLS];
            return SPACE.equals(key) ? Ui.tr("Space") : DEL.equals(key) ? Ui.tr("Delete") : key;
        }
        return stack.a11yText(id);
    }

    @Override boolean a11yBounds(int id, Rect r) {
        if (id == G_KEYBOARD * 1000 + 100) {
            r.set(Math.round(96 * k), Math.round(140 * k), Math.round(680 * k), Math.round(212 * k));
            return true;
        }
        if (id == G_KEYBOARD * 1000 + 101) {
            r.set(note.getLeft(), note.getTop(), note.getRight(), note.getBottom());
            return true;
        }
        if (id / 1000 == G_KEYBOARD) {
            int i = id % 1000 - 1;
            if (i < 0 || i >= ROWS * COLS) return false;
            keyboard.keyRect(i / COLS, i % COLS, rect);
            rect.round(r);
            return true;
        }
        return stack.a11yBounds(id, r);
    }

    @Override int a11yFocused() {
        if (zone == Z_KEYBOARD) {
            int c = keyCol;
            while (c > 0 && KEYS[keyRow][c].equals(KEYS[keyRow][c - 1]) && (KEYS[keyRow][c].equals(SPACE) || KEYS[keyRow][c].equals(DEL))) c--;
            return G_KEYBOARD * 1000 + keyRow * COLS + c + 1;
        }
        if (zone == Z_ROWS) return stack.a11yFocused(G_ROWS);
        return -1;
    }

    @Override void a11yFocus(int id) {
        if (id / 1000 == G_KEYBOARD) {
            int i = id % 1000 - 1;
            if (i < 0 || i >= ROWS * COLS) return;
            if (zone == Z_ROWS) stack.leave();
            zone = Z_KEYBOARD;
            keyRow = i / COLS;
            keyCol = i % COLS;
            keyboard.invalidate();
            return;
        }
        zone = Z_ROWS;
        keyboard.invalidate();
        stack.a11yFocus(id);
    }

    @Override void a11yClick(int id) {
        if (id / 1000 == G_KEYBOARD) {
            a11yFocus(id);
            if (id % 1000 - 1 < ROWS * COLS) onKey(KeyEvent.KEYCODE_DPAD_CENTER, null, false);
            return;
        }
        zone = Z_ROWS;
        stack.a11yClick(id);
    }

    /** The query field and the keys; a hardware layer over a solid background (cards slide out under it). */
    private final class Keyboard extends View {
        private final Paint field, placeholder, key, keyFocused, fill = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path backspace;
        private final RectF box = new RectF();
        private String shownQuery = "", spaceLabel = "", placeholderText = "";
        private float queryW, spaceW, fieldHalf, keyHalf;
        private int gen = -1;
        private final float[] charW = new float[ROWS * COLS];

        Keyboard(Context c) {
            super(c);
            field = Ui.text(Ui.medium, 34 * k, CardPainter.INK);
            placeholder = Ui.text(Ui.medium, 30 * k, Ui.alpha(CardPainter.INK, 0.5f));
            key = Ui.text(Ui.medium, 28 * k, CardPainter.INK);
            keyFocused = Ui.text(Ui.medium, 28 * k, CardPainter.FOCUS_TEXT);
            backspace = ExploreIcons.backspace(34 * k);
            fieldHalf = Ui.halfCap(field);
            keyHalf = Ui.halfCap(key);
            setLayerType(LAYER_TYPE_HARDWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            texts();
        }

        void step() {
            if (gen != Ui.generation) {
                texts();
                invalidate();
            }
        }

        private void texts() {
            gen = Ui.generation;
            spaceLabel = Ui.tr("Space");
            spaceW = key.measureText(spaceLabel);
            placeholderText = TextUtils.ellipsize(Ui.tr("Search movies, shows and people"), new TextPaint(placeholder),
                    528 * k, TextUtils.TruncateAt.END).toString();
            for (int r = 0; r < ROWS; r++) for (int c = 0; c < COLS; c++) charW[r * COLS + c] = key.measureText(KEYS[r][c]);
            queryChanged();
        }

        /** The query's tail that fits the field (made when it changes, never while drawing). */
        void queryChanged() {
            String q = query;
            float max = 528 * k;
            if (field.measureText(q) > max) {
                int n = field.breakText(q, false, max - field.measureText("…"), null);
                q = "…" + q.substring(q.length() - n);
            }
            shownQuery = q;
            queryW = field.measureText(q);
            invalidate();
        }

        void keyRect(int r, int c, RectF out) {
            int first = c, last = c;
            String id = KEYS[r][c];
            if (SPACE.equals(id) || DEL.equals(id)) {
                while (first > 0 && KEYS[r][first - 1].equals(id)) first--;
                while (last < COLS - 1 && KEYS[r][last + 1].equals(id)) last++;
            }
            float x = (96 + first * 84) * k, y = (240 + r * 76) * k;
            out.set(x, y, x + ((last - first + 1) * 84 - 8) * k, y + 68 * k);
        }

        @Override protected void onDraw(Canvas c) {
            int save = c.save();
            try {
                draw0(c);
            } catch (RuntimeException e) {
                ExploreView.crashed(this, "keyboard draw", e);
            } finally {
                c.restoreToCount(save);
            }
        }

        private void draw0(Canvas c) {
            c.drawColor(CardPainter.BG);
            boolean focused = zone == Z_KEYBOARD;
            box.set(96 * k, 140 * k, 680 * k, 212 * k);
            fill.setColor(CardPainter.PILL);
            c.drawRoundRect(box, 36 * k, 36 * k, fill);
            float base = box.centerY() + fieldHalf;
            if (shownQuery.isEmpty()) c.drawText(placeholderText, box.left + 28 * k, base, placeholder);
            else c.drawText(shownQuery, box.left + 28 * k, base, field);
            if (focused) {
                fill.setColor(CardPainter.INK);
                float cx = box.left + 28 * k + (shownQuery.isEmpty() ? 0 : queryW + 4 * k);
                c.drawRect(cx, box.centerY() - 20 * k, cx + 2 * k, box.centerY() + 20 * k, fill);
            }
            for (int r = 0; r < ROWS; r++) {
                for (int col = 0; col < COLS; col++) {
                    String id = KEYS[r][col];
                    if (col > 0 && id.equals(KEYS[r][col - 1]) && (SPACE.equals(id) || DEL.equals(id))) continue;
                    keyRect(r, col, box);
                    boolean f = focused && r == keyRow && KEYS[keyRow][keyCol].equals(id)
                            && (keyCol == col || SPACE.equals(id) || DEL.equals(id));
                    if (f) {
                        float gx = box.width() * 0.03f, gy = box.height() * 0.03f;
                        box.inset(-gx, -gy);
                    }
                    fill.setColor(f ? CardPainter.FOCUS : CardPainter.PILL);
                    c.drawRoundRect(box, 14 * k, 14 * k, fill);
                    Paint p = f ? keyFocused : key;
                    if (DEL.equals(id)) {
                        icon.setColor(f ? CardPainter.FOCUS_TEXT : CardPainter.INK);
                        c.save();
                        c.translate(box.centerX() - 17 * k, box.centerY() - 17 * k);
                        c.drawPath(backspace, icon);
                        c.restore();
                    } else if (SPACE.equals(id)) {
                        c.drawText(spaceLabel, box.centerX() - spaceW / 2, box.centerY() + keyHalf, p);
                    } else {
                        c.drawText(id, box.centerX() - charW[r * COLS + col] / 2, box.centerY() + keyHalf, p);
                    }
                }
            }
        }
    }
}
