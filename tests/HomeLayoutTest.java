package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

public final class HomeLayoutTest {
    private static int assertions;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void same(Object actual, Object expected, String reason) {
        check(expected.equals(actual), reason + ": expected " + expected + " but was " + actual);
    }

    private static List<String> list(String... keys) {
        return new ArrayList<>(Arrays.asList(keys));
    }

    static final String NETFLIX = "com.netflix.ninja", YOUTUBE = "com.google.android.youtube.tv",
            PRIME = "com.amazon.amazonvideo.livingroom", DISNEY = "com.disney.disneyplus", MAX = "com.wbd.stream",
            APPLE = "com.apple.atve.androidtv.appletv", PARAMOUNT = "com.cbs.ca", GLOBO = "com.globo.globotv",
            CLARO = "br.com.claro.now.smarttvclient", GALLERY = "com.tcl.gallery", TKEY = "com.tcl.tkey",
            SPOTIFY = "com.spotify.tv.android";
    static final String PC = "input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW15",
            PS5 = "input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW16",
            HDMI3 = "input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW17",
            HDMI4 = "input:com.mediatek.tvinput/.hdmi.HDMIInputService/HW18";

    private static HomeLayout tv() {
        return HomeLayout.seed(list(NETFLIX, YOUTUBE, PRIME, DISNEY, MAX, APPLE, PARAMOUNT, GLOBO), list(PC, PS5),
                list(CLARO, GALLERY, SPOTIFY, TKEY, NETFLIX), list(HDMI3, HDMI4));
    }

    private static List<String> present() {
        return list(HomeLayout.EXPLORE, NETFLIX, YOUTUBE, PRIME, DISNEY, MAX, APPLE, PARAMOUNT, GLOBO, HomeLayout.LIVE, PC, PS5,
                CLARO, GALLERY, SPOTIFY, TKEY, HDMI3, HDMI4, HomeLayout.SETTINGS);
    }

    public static void main(String[] args) {
        // Seed: this TV's Dock, row 1 and the end.
        HomeLayout l = tv();
        List<String> v = l.visible(present());
        same(v.subList(0, 6), list(HomeLayout.EXPLORE, NETFLIX, YOUTUBE, PRIME, DISNEY, MAX), "dock");
        same(v.subList(6, 12), list(APPLE, PARAMOUNT, GLOBO, HomeLayout.LIVE, PC, PS5), "row 1");
        same(v.get(12), CLARO, "row 2 starts with Claro");
        same(v.subList(v.size() - 3, v.size()), list(HDMI3, HDMI4, HomeLayout.SETTINGS), "unnamed inputs, then settings");
        same(v.size(), new HashSet<>(v).size(), "no duplicates in the seed (netflix twice in the input)");

        // Round trip with escaped names and input ids.
        l.newFolder(GALLERY, "TCL, Pasta|1: \"x\"%");
        l.moveToFolder(TKEY, "folder:1");
        l.hide(SPOTIFY);
        l.resetStamp = 1730900000000L;
        String text = l.serialize();
        check(text.startsWith("v1\nL:"), "header");
        check(!text.contains(",TCL,"), "a folder name's comma is escaped");
        HomeLayout back = HomeLayout.parse(text);
        check(back != null, "parses back");
        same(back.serialize(), text, "round trip");
        same(back.saved, l.saved, "saved round trip");
        same(back.folders.get("folder:1").name, "TCL, Pasta|1: \"x\"%", "folder name round trip");
        same(back.folders.get("folder:1").apps, list(GALLERY, TKEY), "folder apps round trip");
        same(new ArrayList<>(back.hidden), list(SPOTIFY), "hidden round trip");
        check(back.resetStamp == 1730900000000L, "reset stamp");
        check(back.saved.contains(PC), "input ids with slashes survive");
        HomeLayout odd = HomeLayout.seed(list("a,b", "c%d", "e:f|g"), list(), list(), list());
        same(HomeLayout.parse(odd.serialize()).saved, odd.saved, "escaped keys round trip");

        // Garbage and other versions.
        check(HomeLayout.parse(null) == null, "null");
        check(HomeLayout.parse("") == null, "empty");
        check(HomeLayout.parse("hello world") == null, "garbage");
        check(HomeLayout.parse("v2\nL:explore") == null, "v2");
        check(HomeLayout.parse("v1\nH:x") == null, "no list line");
        check(HomeLayout.parse("v1\nL:a%zz") == null, "a broken escape");
        check(HomeLayout.parse("v1\nL:a\nR:soon") == null, "a broken stamp");
        check(HomeLayout.parse("v1\nL:a\nFx:n|b") == null, "a broken folder number");
        check(HomeLayout.parse("v1\nL:a\nno colon") == null, "a line without a tag");
        check(HomeLayout.parse("v1\r\nL:a,b\r\n") != null, "CRLF");

        // visible: hidden skipped, new install before settings, duplicates dropped, folders collapsed.
        HomeLayout h = HomeLayout.parse("v1\nL:explore,a,b,a,folder:1,settings\nF1:F|c,d\nH:b");
        same(h.visible(list("explore", "a", "b", "c", "d", "new", "settings")), list("explore", "a", "folder:1", "new", "settings"),
                "hidden, duplicates, folders, new before settings");
        same(h.visible(list("explore", "a", "settings")), list("explore", "a", "settings"), "a folder with no present app hides");
        same(h.visible(list("explore", "a", "c", "settings")), list("explore", "a", "folder:1", "settings"), "one present app keeps it until reconcile");
        HomeLayout moved = HomeLayout.parse("v1\nL:explore,settings,a");
        same(moved.visible(list("explore", "a", "settings", "z")), list("explore", "settings", "a", "z"), "settings moved: new at the end");

        // setVisible keeps an absent key's slot; a reinstall brings it back there.
        HomeLayout s = HomeLayout.seed(list("a", "b", "c", "d"), list(), list(), list());
        List<String> without = s.visible(list("explore", "a", "c", "d", "settings"));
        same(without, list("explore", "a", "c", "d", "settings"), "b absent");
        s.setVisible(HomeLayout.move(without, 3, 1));
        same(s.saved, list("explore", "d", "b", "a", "c", "live", "settings"), "absent b keeps slot 2");
        same(s.visible(list("explore", "a", "b", "c", "d", "settings")), list("explore", "d", "b", "a", "c", "settings"), "reinstalled b returns");
        HomeLayout s2 = HomeLayout.seed(list("a"), list(), list(), list());
        s2.setVisible(list("explore", "a", "fresh", "settings"));
        same(s2.saved, list("explore", "a", "live", "fresh", "settings"), "a new key in setVisible: absent live keeps its slot");

        // move: both directions and bounds.
        List<String> six = list("0", "1", "2", "3", "4", "5");
        same(HomeLayout.move(six, 1, 4), list("0", "2", "3", "4", "1", "5"), "move forward");
        same(HomeLayout.move(six, 4, 1), list("0", "4", "1", "2", "3", "5"), "move back");
        same(HomeLayout.move(six, 0, 99), list("1", "2", "3", "4", "5", "0"), "to clamped");
        same(HomeLayout.move(six, 5, -3), list("5", "0", "1", "2", "3", "4"), "to clamped low");
        same(HomeLayout.move(six, 9, 0), six, "bad from");
        same(six, list("0", "1", "2", "3", "4", "5"), "input unchanged");

        // step (edit mode).
        same(HomeLayout.step(5, 1, 0, 14, 6), 6, "→ from column 5 wraps to the next row");
        same(HomeLayout.step(6, -1, 0, 14, 6), 5, "← from column 0 wraps back");
        same(HomeLayout.step(0, -1, 0, 14, 6), 0, "← at the start stays");
        same(HomeLayout.step(13, 1, 0, 14, 6), 13, "→ at the end stays");
        same(HomeLayout.step(3, 0, -1, 14, 6), 3, "↑ in the Dock stays");
        same(HomeLayout.step(9, 0, -1, 14, 6), 3, "↑ a row");
        same(HomeLayout.step(9, 0, 1, 14, 6), 13, "↓ into a short last row → n-1");
        same(HomeLayout.step(7, 0, 1, 14, 6), 13, "↓ same column in the last row");
        same(HomeLayout.step(6, 0, 1, 14, 6), 12, "↓ column 0");
        same(HomeLayout.step(13, 0, 1, 14, 6), 13, "↓ from the last row stays");
        same(HomeLayout.step(0, 1, 0, 1, 6), 0, "n = 1");
        same(HomeLayout.step(0, 0, 1, 1, 6), 0, "n = 1 down");

        // nav (focus).
        same(HomeLayout.nav(6, -1, 0, 14, 6), 6, "← at column 0 bumps");
        same(HomeLayout.nav(5, 1, 0, 14, 6), 5, "→ at column 5 bumps");
        same(HomeLayout.nav(13, 1, 0, 14, 6), 13, "→ at the last item bumps");
        same(HomeLayout.nav(2, 0, -1, 14, 6), -1, "↑ from row 0 leaves");
        same(HomeLayout.nav(8, 0, -1, 14, 6), 2, "↑ a row");
        same(HomeLayout.nav(4, 0, 1, 14, 6), 10, "↓ a row");
        same(HomeLayout.nav(10, 0, 1, 14, 6), 13, "↓ into a short row: nearest");
        same(HomeLayout.nav(13, 0, 1, 14, 6), 13, "↓ from the last row bumps");
        same(HomeLayout.nav(3, 1, 0, 4, 6), 3, "→ at the end of a short Dock bumps");

        // topTarget / offTopTarget.
        same(HomeLayout.topTarget(3), 2, "top n < 6");
        same(HomeLayout.topTarget(6), 5, "top n = 6");
        same(HomeLayout.topTarget(20), 5, "top n > 6");
        same(HomeLayout.offTopTarget(3), 2, "off n < 6");
        same(HomeLayout.offTopTarget(6), 5, "off n = 6");
        same(HomeLayout.offTopTarget(20), 6, "off n > 6");
        same(HomeLayout.topTarget(0), 0, "top n = 0");

        // Folders.
        HomeLayout f = tv();
        List<String> p = present();
        int galleryAt = f.visible(p).indexOf(GALLERY);
        String fk = f.newFolder(GALLERY, "TCL");
        same(fk, "folder:1", "first folder key");
        same(f.visible(p).indexOf(fk), galleryAt, "the folder takes the slot");
        check(!f.visible(p).contains(GALLERY), "the app is inside");
        check(f.newFolder(HomeLayout.EXPLORE, "x") == null && f.newFolder(HomeLayout.SETTINGS, "x") == null
                && f.newFolder(HomeLayout.LIVE, "x") == null && f.newFolder(fk, "x") == null, "only apps and inputs");
        f.moveToFolder(TKEY, fk);
        same(f.folders.get(fk).apps, list(GALLERY, TKEY), "moveToFolder appends");
        check(!f.saved.contains(TKEY), "moved out of the order");
        f.moveToFolder(PC, fk);
        same(f.folders.get(fk).apps, list(GALLERY, TKEY, PC), "an input fits");
        f.moveToFolder(HomeLayout.EXPLORE, fk);
        check(!f.folders.get(fk).apps.contains(HomeLayout.EXPLORE), "explore refuses");
        int out = f.moveOut(TKEY);
        List<String> fv = f.visible(p);
        same(out, fv.indexOf(fk) + 1, "moveOut: right after its folder");
        same(fv.get(out), TKEY, "moveOut index points at it");
        f.moveToFolder(TKEY, fk);
        int folderAt = f.visible(p).indexOf(fk);
        f.dissolve(fk);
        check(f.folders.isEmpty(), "dissolved");
        same(f.visible(p).subList(folderAt, folderAt + 3), list(GALLERY, PC, TKEY), "dissolve keeps the folder's order, in its place");
        HomeLayout g = tv();
        String g1 = g.newFolder(GALLERY, "A");
        g.moveToFolder(TKEY, g1);
        String g2 = g.newFolder(TKEY, "B");
        same(g2, "folder:2", "second folder");
        same(g.saved.indexOf(g2), g.saved.indexOf(g1) + 1, "a new folder from inside one goes after it");
        g.rename(g2, "  ");
        same(g.folders.get(g2).name, "B", "an empty rename keeps the name");
        g.rename(g2, "Jogos e coisas muito longas demais");
        check(g.folders.get(g2).name.length() <= HomeLayout.MAX_NAME, "rename capped");

        // reconcile: an empty folder goes, a 1-app folder dissolves, new keys are placed before settings.
        HomeLayout r = HomeLayout.parse("v1\nL:explore,a,folder:1,folder:2,settings\nF1:One|b\nF2:Two|c,d\nH:");
        boolean changed = r.reconcile(list("explore", "a", "b", "settings", "n"));
        check(changed, "reconcile changed");
        check(!r.folders.containsKey("folder:1") && !r.folders.containsKey("folder:2"), "both folders gone");
        same(r.visible(list("explore", "a", "b", "settings", "n")), list("explore", "a", "b", "n", "settings"), "dissolved in place, new before settings");
        check(r.saved.contains("c") && r.saved.contains("d"), "absent apps keep their slots");
        check(!r.reconcile(list("explore", "a", "b", "settings", "n")), "reconcile is stable");
        HomeLayout lost = HomeLayout.parse("v1\nL:a");
        lost.reconcile(list("explore", "a", "settings"));
        same(lost.saved, list("explore", "a", "settings"), "explore and settings come back");
        HomeLayout many = HomeLayout.seed(list(), list(), list(), list());
        for (int i = 0; i < 100; i++) many.saved.add(1, "gone" + i);
        many.reconcile(list("explore", "settings"));
        int absent = 0;
        for (String k : many.saved) if (k.startsWith("gone")) absent++;
        same(absent, HomeLayout.MAX_ABSENT, "absent keys capped");
        check(many.saved.contains("gone99") && !many.saved.contains("gone0"), "the lowest absent keys go first");

        // hide / unhide.
        HomeLayout hd = tv();
        hd.hide(HomeLayout.EXPLORE);
        hd.hide(HomeLayout.SETTINGS);
        check(hd.hidden.isEmpty(), "explore and settings refuse");
        hd.hide(CLARO);
        check(!hd.visible(p).contains(CLARO) && hd.hidden.contains(CLARO), "hidden");
        hd.unhide(CLARO);
        List<String> hv = hd.visible(p);
        same(hv.get(hv.size() - 2), CLARO, "unhide before settings");
        same(hv.get(hv.size() - 1), HomeLayout.SETTINGS, "settings stays last");
        String hf = hd.newFolder(GALLERY, "TCL");
        hd.hide(GALLERY);
        check(!hd.folders.containsKey(hf), "hiding a folder's last app removes the folder");

        // nextFolderName.
        HomeLayout nf = new HomeLayout();
        same(nf.nextFolderName("Pasta {n}", list()), "Pasta 1", "first");
        same(nf.nextFolderName("Pasta {n}", list("Pasta 1", "pasta 2", "Pasta 4")), "Pasta 3", "skips taken");
        nf.newFolder("x.y", "Pasta 3");
        same(nf.nextFolderName("Pasta {n}", list("Pasta 1", "Pasta 2")), "Pasta 4", "own names are taken too");

        // inputKey.
        same(HomeLayout.inputKey("com.x/.Hdmi/HW1"), "input:com.x/.Hdmi/HW1", "plain id");
        check(HomeLayout.inputKey("a,b") == null, "comma");
        check(HomeLayout.inputKey("a|b") == null, "bar");
        check(HomeLayout.inputKey("a%2F") == null, "percent");
        check(HomeLayout.inputKey("a\nb") == null, "newline");
        check(HomeLayout.inputKey(null) == null && HomeLayout.inputKey("") == null, "null and empty");

        // dockPackages.
        HomeLayout dk = new HomeLayout();
        same(dk.dockPackages(list("explore", NETFLIX, "folder:1", PC, "live", YOUTUBE, PRIME)), list(NETFLIX, YOUTUBE),
                "only packages of the first 6");

        // 10 000 seeded random step + move calls keep the key set, with no duplicates.
        Random rnd = new Random(20261106L);
        List<String> keys = tv().visible(p);
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        int at = 0;
        for (int i = 0; i < 10_000; i++) {
            int dir = rnd.nextInt(4);
            int dx = dir == 0 ? -1 : dir == 1 ? 1 : 0, dy = dir == 2 ? -1 : dir == 3 ? 1 : 0;
            int to = HomeLayout.step(at, dx, dy, keys.size(), HomeLayout.COLUMNS);
            check(to >= 0 && to < keys.size(), "step stays in range");
            keys = HomeLayout.move(keys, at, to);
            at = to;
            if (i % 1000 == 0) {
                List<String> now = new ArrayList<>(keys);
                Collections.sort(now);
                same(now, sorted, "same keys after " + i + " moves");
            }
        }
        List<String> end = new ArrayList<>(keys);
        Collections.sort(end);
        same(end, sorted, "same keys at the end");
        same(new HashSet<>(keys).size(), keys.size(), "no duplicates");
        HomeLayout rt = tv();
        rt.setVisible(keys);
        same(rt.visible(p), keys, "setVisible after random moves");

        System.out.println("HomeLayout: " + assertions + " assertions passed");
    }
}
