package io.freemytv;

/** Page id → the file that builds it (SPEC3 §4.3, frozen: the full id list). */
final class SetPages {
    private SetPages() {}

    /** Every page id, in the order of the doc list below (tests, the coverage script, `--es page`). */
    static final String[] IDS = {
            "root", "mode", "metrics", "sleep", "about", "notices", "credits",
            "home", "hidden", "shelf", "topButtons", "look", "widgets", "coins", "remote",
            "picks", "services", "watchApps", "accounts", "keys.tmdb", "keys.watchmode", "saver", "photos",
    };

    /**
     * Unknown id → root. Ids: root, mode, metrics, sleep, about, notices, credits (SetSystem);
     * home, hidden, shelf, topButtons, look, widgets, coins, remote (SetHomePages);
     * picks, services, watchApps, accounts, keys.tmdb, keys.watchmode, saver, photos (SetPicksPages / SetKeys).
     */
    static SetPage build(SetHost h, String id) {
        SetPage p = null;
        if (id != null) {
            switch (id) {
                case "mode": case "metrics": case "sleep": case "about": case "notices": case "credits":
                    p = SetSystem.build(h, id);
                    break;
                case "home": case "hidden": case "shelf": case "topButtons": case "look": case "widgets":
                case "coins": case "remote":
                    p = SetHomePages.build(h, id);
                    break;
                case "picks": case "services": case "watchApps": case "accounts": case "saver": case "photos":
                    p = SetPicksPages.build(h, id);
                    break;
                case "keys.tmdb":
                    p = SetKeys.page(h, false);
                    break;
                case "keys.watchmode":
                    p = SetKeys.page(h, true);
                    break;
                default:
                    break;
            }
        }
        if (p == null) p = SetSystem.build(h, "root");
        if (p == null) p = new SetPage("root", Ui.tr("Settings"), "info");
        return p;
    }

    /** True for an id of the list above (`--es page` with anything else opens the root). */
    static boolean known(String id) {
        if (id == null) return false;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }
}
