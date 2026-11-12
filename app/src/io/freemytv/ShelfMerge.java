package io.freemytv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A streaming app's Top Shelf carousel (SPEC2 §3.4-2): its picks first, then its catalogue, without repeats, each
 * with a TMDB backdrop path. Plain Java (tested on the JDK).
 */
final class ShelfMerge {
    private ShelfMerge() {}

    static final class Ref {
        final int kind, id;
        final String backdrop, link;

        Ref(int kind, int id, String backdrop, String link) {
            this.kind = kind;
            this.id = id;
            this.backdrop = backdrop;
            this.link = link;
        }
    }

    /** Picks first (≤ maxPicks), then catalogue; dedupe kind:id; backdrop must start with "/"; cap; empty if < min. */
    static List<Ref> merge(List<Ref> picks, List<Ref> catalogue, int maxPicks, int cap, int min) {
        List<Ref> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int fromPicks = 0;
        if (picks != null) {
            for (Ref r : picks) {
                if (out.size() >= cap || fromPicks >= maxPicks) break;
                if (usable(r) && seen.add(r.kind + ":" + r.id)) {
                    out.add(r);
                    fromPicks++;
                }
            }
        }
        if (catalogue != null) {
            for (Ref r : catalogue) {
                if (out.size() >= cap) break;
                if (usable(r) && seen.add(r.kind + ":" + r.id)) out.add(r);
            }
        }
        return out.size() < Math.max(0, min) ? new ArrayList<Ref>() : out;
    }

    private static boolean usable(Ref r) {
        return r != null && r.id > 0 && r.backdrop != null && r.backdrop.startsWith("/") && r.backdrop.length() > 1;
    }
}
