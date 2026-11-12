package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ShelfMergeTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static ShelfMerge.Ref r(int kind, int id, String backdrop) {
        return new ShelfMerge.Ref(kind, id, backdrop, kind == 1 ? "link" + id : null);
    }

    private static String ids(List<ShelfMerge.Ref> l) {
        StringBuilder b = new StringBuilder();
        for (ShelfMerge.Ref x : l) b.append(b.length() == 0 ? "" : ",").append(x.kind).append(':').append(x.id);
        return b.toString();
    }

    public static void main(String[] args) {
        List<ShelfMerge.Ref> picks = Arrays.asList(r(1, 1, "/a.jpg"), r(2, 2, "/b.jpg"), r(1, 3, "/c.jpg"), r(1, 4, "/d.jpg"));
        List<ShelfMerge.Ref> cat = Arrays.asList(r(1, 10, "/x.jpg"), r(2, 2, "/dup.jpg"), r(1, 11, "y.jpg"), r(2, 12, null),
                r(2, 13, "/z.jpg"), r(1, 14, "/w.jpg"), r(1, 15, "/v.jpg"), r(1, 16, "/u.jpg"), r(1, 3, "/c2.jpg"));
        List<ShelfMerge.Ref> m = ShelfMerge.merge(picks, cat, 3, 6, 2);
        check(ids(m).equals("1:1,2:2,1:3,1:10,2:13,1:14"), "picks first, capped at 3, then the catalogue: " + ids(m));
        check(m.size() == 6, "cap 6");
        check("link1".equals(m.get(0).link), "a pick keeps its link");
        check(m.get(1).backdrop.equals("/b.jpg"), "the pick's copy wins the dedupe");
        for (ShelfMerge.Ref x : m) check(x.backdrop.startsWith("/"), "every backdrop is a path");

        List<ShelfMerge.Ref> noSlash = ShelfMerge.merge(Arrays.asList(r(1, 1, "a.jpg"), r(1, 2, "")), Arrays.asList(r(1, 3, "/ok.jpg"), r(2, 4, "/ok2.jpg")), 3, 6, 2);
        check(ids(noSlash).equals("1:3,2:4"), "backdrops without / dropped: " + ids(noSlash));
        check(ShelfMerge.merge(Arrays.asList(r(1, 1, "/a.jpg"), r(1, 1, "/a.jpg")), null, 3, 6, 2).isEmpty(), "fewer than min → empty");
        check(ShelfMerge.merge(null, null, 3, 6, 2).isEmpty(), "nothing");
        check(ShelfMerge.merge(new ArrayList<ShelfMerge.Ref>(), Arrays.asList(r(1, 1, "/a"), r(2, 1, "/b")), 3, 6, 2).size() == 2,
                "same id, other kind: both stay");
        check(ShelfMerge.merge(picks, null, 0, 6, 0).isEmpty(), "maxPicks 0 takes no pick");
        check(ShelfMerge.merge(picks, cat, 6, 2, 1).size() == 2, "cap below picks");
        System.out.println("ShelfMerge: " + assertions + " assertions passed");
    }
}
