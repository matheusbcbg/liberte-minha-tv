package io.freemytv;

import android.text.StaticLayout;

import java.util.HashSet;

/** One card of an Explore row: built by the pages from ExploreModel objects, drawn by CardPainter. */
final class Card {
    static final int TITLE = 0, WATCH = 1, PERSON = 2, OFFER = 3, GENRE = 4, SERVICE = 5, SEASON = 6, EPISODE = 7, MORE = 8,
            CHIP = 9, GRANT = 10;
    int type, kind, id, imageSpec, color, textGen = -1;
    String image, title, sub;
    /** A matched Continue Watching card's TMDB title logo path (LOGO spec), drawn bottom-left over the still. */
    String logo;
    CharSequence titleText, subText;
    float progress = -1;
    boolean dim;
    int rank;
    Object ref;

    // Prepared by CardPainter.prepare (UI thread, outside draw): the placeholder's two centred lines, a chip's width,
    // a GENRE/SERVICE name fitted inside the card, the INFO value's layout.
    CharSequence line1, line2;
    float line1W, line2W, titleW, subW, chipW;
    StaticLayout layout;
    float preparedK;

    String key() {
        switch (type) {
            case TITLE:
                return kind + ":" + id;
            case WATCH:
                return "w:" + (ref instanceof WatchNext.Item ? WatchNext.hideKey((WatchNext.Item) ref) : title);
            case CHIP:
            case MORE:
            case GRANT:
                return type + ":" + title;
            default:
                return type + ":" + kind + ":" + id + (id == 0 ? ":" + title : "");
        }
    }
}

/** One horizontal row of an Explore page (its spec, its cards and its loading state). */
final class Row {
    static final int EMPTY = 0, LOADING = 1, READY = 2, FAILED = 3;
    final String key;
    final int style;
    final Catalog.RowSpec spec;
    String title;
    Card[] cards = new Card[0];
    int count, page, totalPages, state;
    CharSequence[] lines;
    /** Bumped whenever the cards change: views and texts made from the old ones are made again. */
    int version;
    /** Loads in flight for this row (cancelled when the page goes). */
    Tmdb.Ticket ticket;
    /** The page asked for this row (EMPTY means not asked yet). */
    long askedAt;

    Row(String key, int style, Catalog.RowSpec spec, String title) {
        this.key = key;
        this.style = style;
        this.spec = spec;
        this.title = title;
    }

    /** Replaces the cards (UI thread). Duplicate keys are dropped. */
    void set(Card[] c, int n) {
        cards = new Card[Math.max(n, 4)];
        count = 0;
        append(c, n);
        if (count == 0) version++;
    }

    /** Adds cards at the end (UI thread), skipping keys already in the row. */
    void append(Card[] c, int n) {
        if (c == null) return;
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < count; i++) seen.add(cards[i].key());
        for (int i = 0; i < Math.min(n, c.length); i++) {
            Card card = c[i];
            if (card == null || !seen.add(card.key())) continue;
            if (count == cards.length) {
                Card[] grown = new Card[Math.max(8, cards.length * 2)];
                System.arraycopy(cards, 0, grown, 0, count);
                cards = grown;
            }
            cards[count++] = card;
        }
        version++;
    }

    boolean visible() {
        return state == LOADING || state == EMPTY || (state == READY && (count > 0 || (lines != null && lines.length > 0)));
    }
}
