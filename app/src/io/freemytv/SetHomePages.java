package io.freemytv;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ajustes: the pages home, hidden, shelf, topButtons, look, widgets, coins and remote, with their pickers (SPEC3 §1.3,
 * WS3). Each row runs the same Prefs/HomeMode calls as the classic view's row (SA:1382-1496, 2351-2481), in the same
 * order and with the same settingsChanged() tail; Início's own rows go only through StartSettings (SPEC2 §3.10).
 * Every onRefresh is cheap: Prefs reads and values worked out when the page was built, never I/O.
 */
final class SetHomePages {
    private SetHomePages() {}

    /** The page for `id`, or null when it isn't one of this file's. */
    static SetPage build(SetHost h, String id) {
        if (id == null) return null;
        switch (id) {
            case "home": return home(h);
            case "hidden": return hidden(h);
            case "shelf": return shelf(h, false);
            case "topButtons": return shelf(h, true);
            case "look": return look(h);
            case "widgets": return widgets(h);
            case "coins": return coins(h);
            case "remote": return remote(h);
            default: return null;
        }
    }

    // MARK: - Shared

    /** A configuration row's description, with "This applies when the Home button opens {app}." outside our mode. */
    private static String applies(SetHost h, String about) {
        SetState s = h.state();
        if (s == null || s.freeActive()) return about;
        return about + "\n" + Ui.trf("This applies when the Home button opens {app}.", "app", Ui.APP_SHORT);
    }

    private static void changed(SetHost h) {
        HomeData.get(h.context()).settingsChanged();
    }

    /** Fills every row's texts once, so the page has its values before the host's first refresh. */
    private static SetPage prime(SetPage p) {
        for (Object e : p.entries) {
            if (e instanceof SetRow && ((SetRow) e).onRefresh != null) ((SetRow) e).onRefresh.run();
        }
        return p;
    }

    /** {value, label} pairs of two parallel arrays, for SetPage.picker. */
    private static List<String[]> pairs(String[] values, String[] labels) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) out.add(new String[] {values[i], labels[i]});
        return out;
    }

    /** The label of `value`, or the first one. */
    private static String label(String[] values, String[] labels, String value) {
        int at = Arrays.asList(values).indexOf(value);
        return labels[at < 0 ? 0 : at];
    }

    /** ← → on a cycling choice: the next or previous value (exactly adjust()'s arithmetic). */
    private static String step(String[] values, String now, int dir) {
        int at = Arrays.asList(values).indexOf(now);
        return values[(at + dir + values.length) % values.length];
    }

    /** OK on a choice: pushes its picker; the opener's icon and description go along. */
    private static void pick(SetHost h, String pickId, String title, String icon, String about, List<String[]> choices,
                             String current, java.util.function.Consumer<String> chosen) {
        h.push(pickId, host -> SetPage.picker(host, pickId, title, icon, about, choices, current, chosen));
    }

    // MARK: - Início (page "home")

    /** No "home" on Xiaomi TVs: their remote sends one Home press as a burst of presses (SA:981-982). */
    private static final String[] RECENTS = RemoteKeys.XIAOMI ? new String[] {"off", "drawer"}
            : new String[] {"off", "drawer", "home"};

    /** Hidden apps counted by the last loadEntries (main thread); -1 before any. */
    private static int hiddenCount = -1;

    private static String[] recentsLabels(boolean modern) {
        return modern
                ? new String[] {Ui.tr("Off"), Ui.tr("recents|In Control Center"), Ui.tr("recents|Control Center and Home twice")}
                : new String[] {Ui.tr("Off"), Ui.tr("recents|In the drawer"), Ui.tr("recents|Drawer and Home twice")};
    }

    private static String[] powerLabels() {
        return new String[] {Ui.tr("TV's choice"), Ui.tr("power|Home screen"), Ui.tr("Live TV")};
    }

    private static SetPage home(SetHost h) {
        final Context c = h.context();
        SetPage p = new SetPage("home", Ui.tr("Home Screen"), "grid");
        p.about = Ui.tr("The home screen's layout, the apps it shows and what the TV shows when it turns on.");
        // Read once per build (a small file): a retry hides it, the next build reads it again.
        final String[] notice = {StartSettings.fallbackNotice(c)};
        // Shelf and top-button counts: PackageManager reads, once per build (the page is built again on the way back).
        final int shelfCount = SetLists.shelfCount(c), topCount = HomeApps.topButtonKeys().size();

        final SetRow retry = SetRow.action("home.notice", StartSettings.retryLabel(), () -> {
            StartSettings.retryModern(c);
            notice[0] = null;
            h.toast(Ui.tr("Done. Press Home on the remote to see it."), false);
            h.refresh();
            h.focus("home.layout");
        });
        retry.refresh(() -> {
            retry.hidden = notice[0] == null;
            retry.tone = SetRow.TONE_WARN;
            retry.icon = "warning";
            retry.about = notice[0] == null ? "" : notice[0];
        });
        p.add(retry);

        final Runnable flipLayout = () -> {
            StartSettings.setLayoutIndex(1 - StartSettings.layoutIndex());
            h.refresh();   // the other layout's rows swap in; SetView re-themes on refresh (§2.3)
        };
        final SetRow layout = SetRow.choice("home.layout", StartSettings.layoutTitle(), flipLayout, dir -> flipLayout.run()).twoValues();
        layout.refresh(() -> {
            layout.value = StartSettings.layoutLabels()[StartSettings.layoutIndex()];
            layout.about = applies(h, Ui.tr("Modern: a grid of every app and, at the top, what's on the focused app. Classic: the earlier home screen, with the shelf and the top buttons."));
        });
        p.add(layout.icon("grid"));

        final SetRow previews = SetRow.toggle("home.previews", StartSettings.previewsTitle(), () -> {
            StartSettings.setPreviews(!StartSettings.previews());
            h.refresh();
        });
        previews.refresh(() -> {
            previews.hidden = !StartSettings.modern();
            previews.on = StartSettings.previews();
            previews.about = applies(h, StartSettings.previewsDescription());
        });
        p.add(previews.icon("photo"));

        final SetRow hidden = SetRow.nav("home.hidden", StartSettings.hiddenTitle(), () -> h.push("hidden"));
        hidden.refresh(() -> {
            hidden.hidden = !StartSettings.modern();
            hidden.value = hiddenCount > 0 ? Ui.plural("plural|one:{n} hidden|other:{n} hidden", hiddenCount) : "";
            hidden.about = applies(h, Ui.tr("The apps you hid on the home screen. Press OK on one to bring it back."));
        });
        p.add(hidden.icon("grid"));

        final SetRow shelf = SetRow.nav("home.shelf", Ui.tr("Shelf"), () -> h.push("shelf"));
        shelf.refresh(() -> {
            shelf.hidden = StartSettings.modern();
            shelf.value = Ui.plural("plural|one:{n} app|other:{n} apps", shelfCount);
            shelf.about = applies(h, Ui.tr("The apps on the home screen's shelf, and their order."));
        });
        p.add(shelf.icon("shelf"));

        final SetRow top = SetRow.nav("home.top", Ui.tr("settings|Top Buttons"), () -> h.push("topButtons"));
        top.refresh(() -> {
            top.hidden = StartSettings.modern();
            top.value = Ui.plural("plural|one:{n} app|other:{n} apps", topCount);
            top.about = applies(h, Ui.tr("The round buttons at the top of the home screen, such as Live TV and Inputs."));
        });
        p.add(top.icon("dots"));

        p.header(Ui.tr("Show on the home screen"));

        final SetRow system = SetRow.toggle("home.system", Ui.tr("System apps"), () -> {
            Prefs.setAndroidApps(!Prefs.androidApps());
            changed(h);
            h.refresh();
        });
        system.refresh(() -> {
            system.on = Prefs.androidApps();
            system.about = applies(h, Ui.tr("Also shows the TV's own apps, such as Files and the maker's."));
        });
        p.add(system.icon("grid"));

        final SetRow status = SetRow.toggle("home.status", Ui.tr("Connection icons"), () -> {
            boolean on = !Prefs.statusIcons();
            Prefs.setStatusIcons(on);
            if (on) SetLists.askBluetooth(h);
            changed(h);
            h.refresh();
        });
        status.refresh(() -> {
            status.on = Prefs.statusIcons();
            status.about = applies(h, Ui.tr("Wi-Fi or cable, Bluetooth headphones and a USB drive, next to the clock."));
        });
        p.add(status.icon("wifi"));

        final SetRow recents = SetRow.choice("home.recents", Ui.tr("Recent Apps"), null, null);
        recents.onOk = () -> pick(h, "pick.recents", recents.title, "cards", recents.about,
                pairs(RECENTS, recentsLabels(StartSettings.modern())), Prefs.recents(), v -> {
                    Prefs.setRecents(v);
                    changed(h);
                });
        recents.refresh(() -> {
            recents.value = label(RECENTS, recentsLabels(StartSettings.modern()), Prefs.recents());
            recents.about = applies(h, Ui.tr("A list of the apps you opened last, to go back to one or close it."));
        });
        p.add(recents.icon("cards"));

        p.gap();

        final SetRow power = SetRow.choice("home.powerOn", Ui.tr("When the TV turns on"), null, null);
        power.onOk = () -> pick(h, "pick.powerOn", power.title, "power", power.about,
                pairs(Prefs.POWER_ON, powerLabels()), Prefs.powerOn(),
                Prefs::setPowerOn);   // read at the next full start (HomeMode.bootCheck): no settingsChanged
        power.refresh(() -> {
            power.value = label(Prefs.POWER_ON, powerLabels(), Prefs.powerOn());
            power.about = applies(h, Ui.tr("What shows when the TV starts from off (not when it wakes from standby)."));
        });
        p.add(power.icon("power").need(Capabilities.NEED_LIVE_TV));

        p.gap();

        final SetRow reset = SetRow.destructive("home.reset", StartSettings.resetTitle(), () -> h.confirm(
                StartSettings.resetConfirm(),
                java.util.Collections.singletonList(Ui.tr("Folders are undone and hidden apps come back.")),
                Ui.tr("Reset"), true, false, () -> {
                    StartSettings.resetLayout();
                    hiddenCount = 0;
                    h.refresh();
                }));
        reset.refresh(() -> {
            reset.hidden = !StartSettings.modern();
            reset.about = applies(h, Ui.tr("Puts every app back where the home screen first had it. No app is uninstalled."));
        });
        p.add(reset.icon("reset"));

        // The hidden-apps count, on StartSettings' own worker (only Moderno shows it).
        if (StartSettings.modern()) {
            StartSettings.loadEntries(c, list -> {
                try {
                    hiddenCount = countHidden(list);
                    if (h.running()) h.refresh();
                } catch (RuntimeException e) {
                    h.fault("settings-hidden-count", e);
                }
            });
        }
        return prime(p);
    }

    private static int countHidden(List<StartSettings.Entry> list) {
        int n = 0;
        for (StartSettings.Entry e : list) if (e.hidden) n++;
        return n;
    }

    // MARK: - Hidden apps (page "hidden")

    private static SetPage hidden(SetHost h) {
        final SetPage p = new SetPage("hidden", StartSettings.hiddenTitle(), "grid");
        p.about = Ui.tr("Hidden, the app leaves the home screen but stays installed.");
        p.add(SetRow.info("hidden.looking", Ui.tr("Looking for the apps…")).about(p.about));
        StartSettings.loadEntries(h.context(), list -> {
            try {
                hiddenCount = countHidden(list);
                fillHidden(h, p, list);
                if (h.running()) h.refresh();
            } catch (RuntimeException e) {
                h.fault("settings-hidden", e);
            }
        });
        return prime(p);
    }

    /** One switch per app or input, A-Z (on = visible); "No hidden apps" first while none is hidden. */
    private static void fillHidden(SetHost h, SetPage p, List<StartSettings.Entry> list) {
        p.entries.clear();
        final List<StartSettings.Entry> all = new ArrayList<>(list);
        final SetRow none = SetRow.info("hidden.none", StartSettings.noHiddenApps());
        none.refresh(() -> {
            none.hidden = countHidden(all) > 0;
            none.about = applies(h, p.about);
        });
        p.add(none);
        for (final StartSettings.Entry e : all) {
            final SetRow r = SetRow.toggle("hidden." + e.key, e.label, () -> {
                e.hidden = !e.hidden;
                StartSettings.setHidden(e.key, e.hidden);
                hiddenCount = countHidden(all);
                h.refresh();
            });
            r.refresh(() -> {
                r.on = !e.hidden;
                r.value = StartSettings.hiddenValue(e.hidden);
                r.about = applies(h, p.about);
            });
            p.add(r.icon(e.input ? "tv" : "grid"));
        }
        prime(p);
    }

    // MARK: - Shelf and Top Buttons (pages "shelf", "topButtons")

    private static SetPage shelf(SetHost h, boolean top) {
        final Context c = h.context();
        final SetPage p = new SetPage(top ? "topButtons" : "shelf", top ? Ui.tr("settings|Top Buttons") : Ui.tr("Shelf"),
                top ? "dots" : "shelf");
        p.about = top ? Ui.tr("The round buttons at the top of the home screen, such as Live TV and Inputs.")
                : Ui.tr("The apps on the home screen's shelf, and their order.");
        final SetLists.Shelf model = new SetLists.Shelf(c, top);
        final boolean lowRam = !top && HomeApps.lowRam(c);
        final Map<String, SetRow> rows = new HashMap<>();
        final Runnable[] fill = new Runnable[1];
        for (final String[] cand : model.rows) {
            final String key = cand[0];
            final SetRow r = SetRow.ordered((top ? "top." : "shelf.") + key, cand[1], 0, () -> {
                if (!model.toggle(key)) {   // the shelf is full (low-RAM TVs)
                    h.shake();
                    h.toast(Ui.tr("This TV has little memory, so the shelf holds fewer apps."), true);
                    return;
                }
                fill[0].run();
                h.refresh();
                h.focus((top ? "top." : "shelf.") + key);   // focus follows the app into its new section
            }, dir -> {
                if (!model.move(key, dir)) return;
                fill[0].run();
                h.refresh();
                h.focus((top ? "top." : "shelf.") + key);
            });
            r.refresh(() -> {
                int place = model.place(key);
                r.on = place >= 0;
                r.order = place + 1;
                r.about = applies(h, place >= 0
                        ? (top ? Ui.tr("OK takes it off the top. ← → move it.") : Ui.tr("OK takes it off the shelf. ← → move it."))
                        : (top ? Ui.tr("OK adds it at the end of the top.") : Ui.tr("OK adds it at the end of the shelf.")));
                // Only to add it: a button already at the top can still move or go (SA:1155-1162).
                r.need = !top || place >= 0 ? null
                        : HomeApps.TOP_LIVE.equals(key) ? Capabilities.NEED_LIVE_TV
                        : HomeApps.TOP_STORE.equals(key) ? Capabilities.NEED_APP_STORE : null;
            });
            rows.put(key, r);
        }
        // Two sections, rebuilt after each change: the selected ones in order, then the others.
        fill[0] = () -> {
            p.entries.clear();
            if (lowRam) p.add(SetRow.info("shelf.lowRam", Ui.tr("This TV has little memory, so the shelf holds fewer apps.")).about(p.about));
            List<String[]> in = new ArrayList<>(), out = new ArrayList<>();
            for (String[] cand : model.rows) (model.place(cand[0]) >= 0 ? in : out).add(cand);
            if (!in.isEmpty()) {
                p.header(top ? Ui.tr("At the top") : Ui.tr("On the shelf"));
                for (String[] cand : in) p.add(rows.get(cand[0]));
            }
            if (!out.isEmpty()) {
                p.header(top ? Ui.tr("Other buttons") : Ui.tr("Other apps"));
                for (String[] cand : out) p.add(rows.get(cand[0]));
            }
            prime(p);
        };
        fill[0].run();
        return p;
    }

    // MARK: - Aparência e idioma (page "look")

    private static final String[] THEMES = {"auto", "light", "dark"}, CLOCKS = {"auto", "24", "12"},
            UNITS = {"auto", "C", "F"};

    private static String[] themeLabels() {
        return new String[] {Ui.tr("Automatic"), Ui.tr("Light"), Ui.tr("Dark")};
    }

    private static String[] clockLabels() {
        return new String[] {Ui.tr("Automatic"), Ui.tr("24 hours"), Ui.tr("12 hours")};
    }

    private static String[] unitLabels() {
        return new String[] {Ui.tr("unit|Automatic"), "°C", "°F"};
    }

    private static SetPage look(SetHost h) {
        final Context c = h.context();
        SetPage p = new SetPage("look", Ui.tr("Appearance and language"), "appearance");
        p.about = Ui.tr("Language, light or dark theme, clock format and sounds.");

        final SetRow lang = SetRow.choice("l.lang", Ui.tr("Language"), null, null);
        lang.onOk = () -> {
            String[] codes = SetLists.languageChoices();
            String[] names = new String[codes.length];
            for (int i = 0; i < codes.length; i++) names[i] = SetLists.languageLabel(codes[i]);
            pick(h, "pick.language", lang.title, "globe", lang.about, pairs(codes, names), Prefs.language(), v -> {
                Prefs.setLanguage(v);   // Ui.generation moves: the host builds every page again in the new language
                SleepOverlay.update(c);   // the pill speaks it too
            });
        };
        lang.refresh(() -> {
            lang.value = SetLists.languageLabel(Prefs.language());
            lang.about = Ui.trf("{app}'s language. Automatic follows the TV's.", "app", Ui.APP_SHORT);
        });
        p.add(lang.icon("globe"));

        final java.util.function.Consumer<String> setTheme = v -> {
            Prefs.setTheme(v);
            SleepOverlay.update(c);   // the pill's glass follows
            changed(h);
        };
        final SetRow theme = SetRow.choice("l.theme", Ui.tr("Theme"), null, dir -> {
            setTheme.accept(step(THEMES, Prefs.theme(), dir));
            h.refresh();   // SetView re-themes on refresh (§2.3)
        });
        theme.onOk = () -> pick(h, "pick.theme", theme.title, "appearance", theme.about, pairs(THEMES, themeLabels()),
                Prefs.theme(), setTheme);
        theme.refresh(() -> {
            theme.value = label(THEMES, themeLabels(), Prefs.theme());
            String about = Ui.tr("Light or dark, on the classic home screen and the screensaver. Automatic turns dark at sunset in your city.");
            if (StartSettings.modern()) about = about + "\n" + StartSettings.alwaysDark();
            theme.about = applies(h, about);
        });
        p.add(theme.icon("appearance"));

        final java.util.function.Consumer<String> setClock = v -> {
            Prefs.setClockFormat(v);
            changed(h);
        };
        final SetRow clock = SetRow.choice("l.clock", Ui.tr("Clock format"), null, dir -> {
            setClock.accept(step(CLOCKS, Prefs.clockFormat(), dir));
            h.refresh();
        });
        clock.onOk = () -> pick(h, "pick.clock", clock.title, "clock", clock.about, pairs(CLOCKS, clockLabels()),
                Prefs.clockFormat(), setClock);
        clock.refresh(() -> {
            clock.value = label(CLOCKS, clockLabels(), Prefs.clockFormat());
            clock.about = applies(h, Ui.tr("Automatic follows the TV's format."));
        });
        p.add(clock.icon("clock"));

        final java.util.function.Consumer<String> setUnit = v -> {
            Prefs.setTemperature(v);
            changed(h);
        };
        final SetRow temp = SetRow.choice("l.temp", Ui.tr("Temperature"), null, dir -> {
            setUnit.accept(step(UNITS, Prefs.temperature(), dir));
            h.refresh();
        });
        temp.onOk = () -> pick(h, "pick.temp", temp.title, "thermo", temp.about, pairs(UNITS, unitLabels()),
                Prefs.temperature(), setUnit);
        temp.refresh(() -> {
            temp.value = label(UNITS, unitLabels(), Prefs.temperature());
            temp.about = applies(h, Ui.tr("Celsius or Fahrenheit. Automatic follows the TV's region."));
        });
        p.add(temp.icon("thermo"));

        final SetRow sounds = SetRow.toggle("l.sounds", Ui.tr("Navigation sounds"), () -> {
            Prefs.setSounds(!Prefs.sounds());
            changed(h);
            h.refresh();
        });
        sounds.refresh(() -> {
            sounds.on = Prefs.sounds();
            sounds.about = Ui.tr("A soft click as you move and open, on the home screen and here.");
        });
        p.add(sounds.icon("speaker"));
        return prime(p);
    }

    // MARK: - Widgets (page "widgets")

    private static SetPage widgets(SetHost h) {
        final Context c = h.context();
        SetPage p = new SetPage("widgets", Ui.tr("Widgets"), "sun_cloud");
        p.about = Ui.tr("The weather, what's playing and the quotes, on the home screen.");

        final SetRow weather = SetRow.toggle("w.weather", Ui.tr("Weather"), () -> {
            Prefs.setWeather(!Prefs.weather());
            changed(h);
            h.refresh();
        });
        weather.refresh(() -> {
            weather.on = Prefs.weather();
            weather.about = applies(h, Ui.tr("The temperature and forecast for your city, on the home screen and in Control Center."));
        });
        p.add(weather.icon("sun_cloud"));

        final SetRow city = SetRow.nav("w.city", Ui.tr("City"), null);
        city.onOk = () -> SetLists.askCity(h, city.about);
        city.refresh(() -> {
            city.hidden = !Prefs.weather();
            String status = SetLists.cityStatus();
            city.value = !status.isEmpty() ? status : Prefs.city().isEmpty() ? "—" : Prefs.city();
            city.busy = !status.isEmpty();
            city.about = applies(h, Ui.tr("The weather's city. Press OK to look for another."));
        });
        p.add(city.icon("pin"));

        final Runnable flipForecast = () -> {   // both are in memory: nothing to fetch, no settingsChanged
            Prefs.setForecast(Prefs.forecast().equals("days") ? "hours" : "days");
            h.refresh();
        };
        final SetRow forecast = SetRow.choice("w.forecast", Ui.tr("Forecast"), flipForecast, dir -> flipForecast.run()).twoValues();
        forecast.refresh(() -> {
            forecast.hidden = !Prefs.weather();
            forecast.value = Prefs.forecast().equals("days") ? Ui.tr("Next days") : Ui.tr("Next hours");
            forecast.about = applies(h, Ui.tr("Shows the next hours or the next days."));
        });
        p.add(forecast.icon("sun_cloud"));

        p.gap();

        // The listener follows the widget: allowed while it's on, taken back when it goes off (SA:1481-1484).
        final SetRow playing = SetRow.toggle("w.playing", Ui.tr("Now Playing"), () -> {
            Prefs.setNowPlaying(!Prefs.nowPlaying());
            HomeMode.nowPlayingAccess(c);
            changed(h);
            h.refresh();
        });
        playing.refresh(() -> {
            playing.on = Prefs.nowPlaying();
            playing.allowWhenUnavailable = Prefs.nowPlaying();   // refused or outside our mode, it can still go off
            playing.about = applies(h, Ui.trf("The song or video an app is playing, on the home screen. For that, the TV lets {app} see what's playing.",
                    "app", Ui.APP_SHORT));
        });
        p.add(playing.icon("music").need(Capabilities.NEED_LISTENER));

        p.gap();

        final SetRow quotes = SetRow.toggle("w.quotes", Ui.tr("Quotes"), () -> {
            Prefs.setQuotes(!Prefs.quotes());
            changed(h);
            h.refresh();
        });
        quotes.refresh(() -> {
            quotes.on = Prefs.quotes();
            quotes.about = applies(h, Ui.tr("Currency prices on the home screen and in Control Center, updated through the day."));
        });
        p.add(quotes.icon("chart"));

        final SetRow coins = SetRow.nav("w.coins", Ui.tr("Coins"), () -> h.push("coins"));
        coins.refresh(() -> {
            coins.hidden = !Prefs.quotes();
            int on = 0, total = coinNames().length;
            for (int i = 0; i < total; i++) if (Prefs.coin(i)) on++;
            coins.value = Ui.trf("{on} of {total}", "on", on, "total", total);
            coins.about = applies(h, Ui.tr("Which currencies the quotes show."));
        });
        p.add(coins.icon("chart"));

        if (Config.HOME_ASSISTANT) {
            p.gap();
            // HomeAssistant.on reads the token file: once per build, then the switch follows Prefs.
            final boolean configured = HomeAssistant.configured(c);
            final SetRow ha = SetRow.toggle("w.ha", "Home Assistant", () -> {
                // Off: what it read is forgotten, and nothing is asked of the server until it's on again.
                Prefs.setHomeAssistant(!Prefs.homeAssistant());
                if (!Prefs.homeAssistant()) HomeAssistant.forget(c);
                h.refresh();
            });
            ha.refresh(() -> {
                ha.on = Prefs.homeAssistant() && configured;
                ha.about = applies(h, Ui.tr("Your Home Assistant buttons in Control Center."));
            });
            p.add(ha.icon("house").need(Capabilities.NEED_HOME_ASSISTANT));
        }
        return prime(p);
    }

    // MARK: - Moedas (page "coins")

    /** Bitcoin, Ethereum, Solana, and the dollar where prices are in reais (SA:986). */
    private static String[] coinNames() {
        return "BRL".equals(Place.currency()) ? new String[] {"Bitcoin", "Ethereum", "Solana", Ui.tr("Dollar")}
                : new String[] {"Bitcoin", "Ethereum", "Solana"};
    }

    private static SetPage coins(SetHost h) {
        SetPage p = new SetPage("coins", Ui.tr("Coins"), "chart");
        p.about = Ui.tr("Which currencies the quotes show.");
        String[] names = coinNames();
        for (int i = 0; i < names.length; i++) {
            final int coin = i;
            final SetRow r = SetRow.toggle("coin." + i, names[i], () -> {
                Prefs.setCoin(coin, !Prefs.coin(coin));
                changed(h);
                h.refresh();
            });
            r.refresh(() -> {
                // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
                r.on = Prefs.quotes() && Prefs.coin(coin);
                r.about = applies(h, Ui.tr("Which currencies the quotes show."));
            });
            r.reason(() -> Prefs.quotes() ? null : Ui.tr("Turn on the Quotes widget to pick its coins."));
            p.add(r.icon("chart"));
        }
        return prime(p);
    }

    // MARK: - Controle remoto (page "remote")

    private static SetPage remote(SetHost h) {
        final Context c = h.context();
        final SetPage p = new SetPage("remote", Ui.tr("Remote control"), "remote");
        p.about = Ui.tr("Choose the app each app button on the remote opens.");
        p.header(Ui.tr("App buttons"));
        final String[] labels = SetLists.keyLabels();
        int n = Math.min(labels.length, Prefs.KEYS.length);
        for (int i = 0; i < n; i++) {
            final int key = i;
            final SetRow r = SetRow.choice("remote." + i, labels[i], null, dir -> {
                if (SetLists.stepKeyApp(c, key, dir)) h.refresh();
            });
            r.onOk = () -> openKeyPicker(h, key, r.title, r.about);
            r.refresh(() -> {
                r.value = SetLists.keyValue(key);
                String about = Ui.tr("Choose what this button opens. Nothing leaves the button to the TV.");
                if (!RemoteKeys.FILTER_SAFE) about = about + "\n" + Ui.tr("On this TV they work on Free My TV's home screen only.");
                r.about = applies(h, about);
            });
            p.add(r.icon("remote"));
        }
        if (RemoteKeys.TCL) {
            final SetRow tcl = SetRow.info("remote.tcl", Ui.tr("Disney+ and Globoplay"));
            tcl.refresh(() -> {
                tcl.value = Ui.tr("The TV handles them");
                tcl.about = Ui.tr("Disney and Globoplay: the TV itself controls those buttons.");
            });
            p.add(tcl.icon("remote"));
        }
        // The apps a button can open, on Safe.IO: the values show "…" until then.
        SetLists.loadApps(h, () -> {
            if (h.running()) h.refresh();
        });
        return prime(p);
    }

    /** OK on a button: its picker; while the app list still loads, "Looking for the apps…" until it's there. */
    private static void openKeyPicker(SetHost h, int key, String title, String about) {
        final String pickId = "pick.remote." + key;
        final java.util.function.Consumer<String> chosen = pkg -> SetLists.setKeyApp(h.context(), key, pkg);
        final String current = Prefs.keyApp(Prefs.KEYS[key]);
        if (SetLists.apps() != null) {
            pick(h, pickId, title, "remote", about, SetLists.keyChoices(), current, chosen);
            return;
        }
        h.push(pickId, host -> {
            final SetPage waiting = new SetPage(pickId, title, "remote");
            waiting.about = about;
            waiting.add(SetRow.info(pickId + ".looking", Ui.tr("Looking for the apps…")).about(about));
            SetLists.loadApps(host, () -> {
                // The picker's rows, moved into the page already on screen.
                SetPage picker = SetPage.picker(host, pickId, title, "remote", about, SetLists.keyChoices(), current, chosen);
                waiting.entries.clear();
                waiting.entries.addAll(picker.entries);
                if (!host.running()) return;
                host.refresh();
                for (Object e : waiting.entries) {
                    if (e instanceof SetRow && ((SetRow) e).on) { host.focus(((SetRow) e).id); break; }
                }
            });
            return waiting;
        });
    }
}
