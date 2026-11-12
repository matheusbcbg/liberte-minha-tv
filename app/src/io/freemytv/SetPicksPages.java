package io.freemytv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * SPEC3 §1.3 pages "Destaques e Continuar assistindo" (picks, services, watchApps, accounts) and "Tela de descanso"
 * (saver, photos), with their pickers (pick.top, pick.cards, pick.photoSource, pick.album). The logic is the legacy
 * view's, statement by statement (SA:1040-1083, 1382-1456, 1848-1923, 1966-2087, 2250-2283, 2351-2481); the key pages
 * are SetKeys', Continue Watching's permission and apps SetWatch's.
 *
 * Everything runs on the main thread; onRefresh bodies read only Prefs, cached state and directory stamps.
 */
final class SetPicksPages {
    private SetPicksPages() {}

    /** The page with this id, or null if it isn't one of these. */
    static SetPage build(SetHost h, String id) {
        if (id == null) return null;
        switch (id) {
            case "picks": return picks(h);
            case "services": return services(h);
            case "watchApps": return watchApps(h);
            case "accounts": return accounts(h);
            case "keys.tmdb": return SetKeys.page(h, false);
            case "keys.watchmode": return SetKeys.page(h, true);
            case "saver": return saver(h);
            case "photos": return photos(h);
            default: return null;
        }
    }

    /** "Vale quando o botão Início abre o Liberte minha TV.", added to a configuration row's text while in Original. */
    private static String modeNote(SetHost h, String about) {
        SetState state = h.state();
        if (state == null || state.freeActive()) return about;
        return about + "\n\n" + Ui.trf("This applies when the Home button opens {app}.", "app", Ui.APP_SHORT);
    }

    // MARK: - Destaques e Continuar assistindo

    /** Is a streaming service on the TV switched off in Services (SA:2250-2253), counted when the page is built. */
    private static boolean someServicesOff;

    private static void countServicesOff(Context c) {
        boolean off = false;
        for (String[] service : Recs.installedServices(c)) off |= service[0].indexOf('.') < 0 && Prefs.serviceOff(service[0]);
        someServicesOff = off;
    }

    private static SetPage picks(SetHost h) {
        Context c = h.context();
        countServicesOff(c);
        SetPage p = new SetPage("picks", Ui.tr("settings|Picks and Continue Watching"), "sparkles");
        String setupAbout = Ui.tr("Picks need a free key from TMDB, the film and series site. You send it from your phone, in two minutes.");
        p.about = Ui.tr("What's trending on your apps and what you were watching, at the top of the home screen.");

        final SetRow setup = SetRow.nav("picks.setup", Ui.tr("Turn on Picks"), () -> h.push("keys.tmdb"))
                .icon("key").about(setupAbout);
        setup.value = Ui.tr("Set Up…");
        setup.refresh(() -> setup.hidden = Recs.hasKey(c));
        p.add(setup);

        String topAbout = Ui.tr("What sits at the top of the home screen: the picks, what you were watching, both or nothing.");
        final SetRow top = SetRow.choice("picks.top", Ui.tr("At the top of the home screen"),
                () -> h.push("pick.top", hh -> topPicker(hh, topAbout)), null);
        top.refresh(() -> {
            top.value = topShort(Prefs.topContent());
            top.about = modeNote(h, topAbout);
        });
        p.add(top);

        p.header(Ui.tr("Picks"));
        final SetRow status = SetRow.info("picks.status", Ui.tr("Status")).icon("sparkles");
        status.refresh(() -> {
            status.hidden = !Recs.hasKey(c);
            SetState state = h.state();
            status.value = state == null ? "" : state.recsShort();
            status.about = state == null ? "" : state.recsText();
        });
        p.add(status);

        String cardsAbout = Ui.tr("How many titles each picks tab shows.");
        final SetRow cards = SetRow.choice("picks.cards", Ui.tr("Picks per tab"),
                () -> h.push("pick.cards", hh -> cardsPicker(hh, cardsAbout)), SetPicksPages::stepCards);
        cards.refresh(() -> {
            cards.value = String.valueOf(Prefs.cards());
            cards.about = modeNote(h, cardsAbout);
        });
        p.add(cards);

        String fromAbout = Ui.tr("Which streaming apps the picks come from.");
        final SetRow from = SetRow.nav("picks.from", Ui.tr("Services"), () -> h.push("services"));
        from.refresh(() -> {
            from.value = someServicesOff ? Ui.tr("services|Some") : Ui.tr("services|All");
            from.about = modeNote(h, fromAbout);
        });
        p.add(from);

        String animAbout = Ui.tr("Off, the picks leave out cartoons and anime.");
        final SetRow anim = SetRow.toggle("picks.anim", Ui.tr("Cartoons and anime"),
                () -> Prefs.setHideAnimation(!Prefs.hideAnimation()));   // SA:1390-1391: no settingsChanged
        anim.refresh(() -> {
            anim.on = !Prefs.hideAnimation();
            anim.about = modeNote(h, animAbout);
        });
        p.add(anim);

        p.header(Ui.tr("Continue Watching"));
        String watchAbout = Ui.tr("Which apps show what you were watching.");
        final SetRow watch = SetRow.nav("picks.watch", Ui.tr("settings|Apps"), () -> h.push("watchApps")).icon("play")
                .reason(() -> SetWatch.locked()
                        ? Ui.tr("Pick something with Continue Watching in “At the top of the home screen” first.") : null);
        watch.refresh(() -> {
            watch.value = SetWatch.count();
            watch.about = modeNote(h, watchAbout);
        });
        p.add(watch);

        p.gap();
        final SetRow accounts = SetRow.nav("picks.accounts", Ui.tr("Accounts and keys"), () -> h.push("accounts"))
                .icon("key").about(Ui.tr("The free keys that bring the picks and, if you like, your Trakt account."));
        accounts.refresh(() -> accounts.value = Recs.hasKey(c) ? "" : Ui.tr("Set Up…"));
        p.add(accounts);

        // Opening the page looks for Continue Watching's apps, as Home Settings did (SA:2459-2461).
        p.onEnter = () -> {
            SetWatch.scanIfStale(h);
            SetWatch.resume(h);
        };
        return p;
    }

    /** The value of "No topo do Início": short words (SA:1105-1109, with the Moderno "Nada"). */
    private static String topShort(String top) {
        if (Prefs.TOP_BOTH.equals(top)) return Ui.tr("top|Both");
        if (Prefs.TOP_CONTINUE.equals(top)) return Ui.tr("top|Continue");
        if (Prefs.TOP_NONE.equals(top)) return StartSettings.modern() ? Ui.tr("top|Nothing") : Ui.tr("Clock");
        return Ui.tr("Picks");
    }

    private static SetPage topPicker(SetHost h, String about) {
        List<String[]> choices = new ArrayList<>();
        choices.add(new String[] {Prefs.TOP_BOTH, Ui.tr("top|Picks and Continue Watching")});
        choices.add(new String[] {Prefs.TOP_PICKS, Ui.tr("Picks only")});
        choices.add(new String[] {Prefs.TOP_CONTINUE, Ui.tr("Continue Watching only")});
        choices.add(new String[] {Prefs.TOP_NONE, StartSettings.modern() ? Ui.tr("Nothing at the top") : Ui.tr("Clock only")});
        return SetPage.picker(h, "pick.top", Ui.tr("At the top of the home screen"), "sparkles", about, choices,
                Prefs.topContent(), v -> {
                    // SA:1449-1456, then the permission once, right after the choice (§3.8), never on leaving a row.
                    Prefs.setTopContent(v);
                    SetWatch.picked(h);
                    HomeData.get(h.context()).settingsChanged();
                    SetWatch.commit(h);
                });
    }

    private static SetPage cardsPicker(SetHost h, String about) {
        List<String[]> choices = new ArrayList<>();
        for (int n = 3; n <= Recs.MAX_CARDS; n++) choices.add(new String[] {String.valueOf(n), String.valueOf(n)});
        return SetPage.picker(h, "pick.cards", Ui.tr("Picks per tab"), "sparkles", about, choices,
                String.valueOf(Prefs.cards()), v -> Prefs.setCards(Integer.parseInt(v)));
    }

    /** ←/→ on Destaques por aba (SA:1387-1389): 3 … MAX_CARDS, cycling; no settingsChanged, as there. */
    private static void stepCards(int dir) {
        int n = Prefs.cards() - 3, span = Recs.MAX_CARDS - 3 + 1;
        Prefs.setCards(3 + (n + dir + span) % span);
    }

    /** Services: one switch per streaming service on the TV (SA:2255-2283). */
    private static SetPage services(SetHost h) {
        Context c = h.context();
        SetPage p = new SetPage("services", Ui.tr("Services"), "sparkles");
        String about = Ui.tr("Titles from a service that is off are left out of the picks.");
        p.about = about;
        final List<String[]> list = Recs.installedServices(c);
        if (list.isEmpty()) p.add(SetRow.info("svc.none", Ui.tr("No streaming apps on this TV.")).about(about));
        for (String[] service : list) {
            final String key = service[0];
            final SetRow r = SetRow.toggle("svc." + key, service[1], () -> {
                Prefs.setServiceOff(key, !Prefs.serviceOff(key));
                boolean off = false;   // countServicesOff over the list already read
                for (String[] s : list) off |= s[0].indexOf('.') < 0 && Prefs.serviceOff(s[0]);
                someServicesOff = off;
            }).about(about);
            r.refresh(() -> r.on = !Prefs.serviceOff(key));
            p.add(r);
        }
        return p;
    }

    /** Continue Watching › Apps: one switch per app found by the scan, then the hidden items (SA:2023-2087). */
    private static SetPage watchApps(SetHost h) {
        SetPage p = new SetPage("watchApps", Ui.tr("Continue Watching"), "play");
        String about = Ui.tr("Turns an app's cards in Continue Watching on or off.");
        p.about = about;
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> fillWatchApps(h, p, about);
        SetWatch.opened(rebuild[0]);   // counts the hidden items, as openWatchApps did
        fillWatchApps(h, p, about);
        p.onLeave = () -> SetWatch.closed(rebuild[0]);
        return p;
    }

    /** The rows for the scan's result: built now, and again (in place) when a scan still running ends. */
    private static void fillWatchApps(SetHost h, SetPage p, String about) {
        p.entries.clear();
        List<String[]> apps = SetWatch.apps();
        if (apps == null) {
            p.add(SetRow.info("watch.looking", Ui.tr("Looking for the apps…")).about(about));
            return;
        }
        if (apps.isEmpty() && SetWatch.hiddenCount() == 0) {
            p.add(SetRow.info("watch.none", Ui.tr("No app on this TV shares what you watch.")).about(about));
            return;
        }
        for (String[] app : apps) {
            final String pkg = app[0];
            final SetRow r = SetRow.toggle("watch." + pkg, app[1], () -> SetWatch.toggle(pkg)).about(about);
            r.refresh(() -> r.on = SetWatch.isOn(pkg));
            p.add(r);
        }
        final SetRow hidden = SetRow.action("watch.hidden", "", () -> {
            SetWatch.showHidden(h);
            h.refresh();
        }).about(about);
        hidden.refresh(() -> {
            hidden.hidden = SetWatch.hiddenCount() == 0;
            hidden.title = Ui.trf("Show hidden items ({n})", "n", SetWatch.hiddenCount());
        });
        p.add(hidden);
    }

    // MARK: - Contas e chaves

    private static SetPage accounts(SetHost h) {
        Context c = h.context();
        SetPage p = new SetPage("accounts", Ui.tr("Accounts and keys"), "key");
        p.about = Ui.tr("The free keys that bring the picks and, if you like, your Trakt account.");

        final SetRow tmdb = SetRow.nav("picks.tmdb", Ui.tr("TMDB key"), () -> h.push("keys.tmdb"))
                .about(Ui.tr("The key brings the picks: what's trending on your streaming apps, with their photos on the screensaver."));
        tmdb.refresh(() -> tmdb.value = Recs.key().isEmpty() ? Ui.tr("Set Up…") : keyValue(Recs.key()));
        p.add(tmdb);

        final SetRow watchmode = SetRow.nav("picks.watchmode", Ui.tr("Watchmode key"), () -> h.push("keys.watchmode"))
                .about(Ui.tr("The key makes a pick open the title's page in its app, where the app allows it."));
        watchmode.refresh(() -> watchmode.value = Recs.watchmodeKey().isEmpty() ? Ui.tr("Optional") : keyValue(Recs.watchmodeKey()));
        p.add(watchmode);

        String traktAbout = Ui.tr("With your Trakt account, the picks follow what you watch and want to watch.");
        final SetRow trakt = SetRow.action("picks.trakt", "Trakt", () -> traktOk(h)).icon("person");
        trakt.refresh(() -> {
            trakt.hidden = !Trakt.available();
            trakt.value = traktValue(c);
            trakt.busy = Trakt.busy() && Trakt.code() == null;
            String code = Trakt.code();
            trakt.about = code == null ? traktAbout
                    : code + "\n\n" + Ui.trf("Open {url} on your phone and type this code.", "url", Trakt.url());
        });
        p.add(trakt);
        return p;
    }

    /** A key's value: its last 4 characters, all that's ever shown of it (SA:1036). */
    private static String keyValue(String key) {
        return key.length() < 4 ? Ui.tr("Not set") : "•••• " + key.substring(key.length() - 4);
    }

    /** The Trakt row's value (SA:1057-1065). */
    private static String traktValue(Context c) {
        String code = Trakt.code();
        if (code != null) return Ui.trf("{url}, code {code}", "url", Trakt.url(), "code", code);
        if (Trakt.busy()) return Ui.tr("Connecting…");
        if (Trakt.signedIn(c)) return Trakt.user(c).isEmpty() ? Ui.tr("Signed in") : Trakt.user(c);
        return Trakt.message().isEmpty() ? Ui.tr("Sign in") : Trakt.message();
    }

    /** OK on Trakt: a code to sign in with, or a sheet before signing out (SA:1067-1083). */
    private static void traktOk(SetHost h) {
        Context c = h.context();
        if (!Trakt.signedIn(c)) {
            // `changed` runs on Trakt's thread at every step: the row and TalkBack follow on the main thread.
            final android.os.Handler main = h.handler();
            Trakt.signIn(c, () -> main.post(SetWatch.guarded(h, "settings-trakt", () -> {
                if (!h.running()) return;
                h.refresh();
                h.announce(traktValue(c));
            })));
            h.refresh();
            return;
        }
        String user = Trakt.user(c).isEmpty() ? "Trakt" : Trakt.user(c);
        List<String> body = new ArrayList<>();
        body.add(Ui.trf("Sign out of {name}? The picks go back to TMDB's alone.", "name", user));
        h.confirm("Trakt", body, Ui.tr("Sign out"), true, false, () -> {
            Trakt.signOut(c);
            h.refresh();
        });
    }

    // MARK: - Tela de descanso

    private static boolean albumsAsked, waitingAlbums;

    private static SetPage saver(SetHost h) {
        Context c = h.context();
        askAlbumsOnce(h);
        SetPage p = new SetPage("saver", Ui.tr("settings|Screensaver"), "photo");
        p.about = Ui.tr("What the TV shows when it sits idle: pictures of films and series, and your own photos.");

        p.add(SetRow.action("s.preview", Ui.tr("Preview"), h::preview).icon("play")
                .about(Ui.tr("Shows the screensaver now. Any button comes back here.")));

        String photosAbout = Ui.tr("Your own photos on the screensaver.");
        final SetRow photos = SetRow.nav("s.photos", Ui.tr("My Photos"), () -> h.push("photos"));
        photos.refresh(() -> {
            photos.value = photosValue(c);
            photos.about = modeNote(h, photosAbout);
        });
        p.add(photos);

        String watchAbout = Ui.tr("On, OK on the screensaver opens the film or series it shows. Off, any button just closes it.");
        final SetRow watch = SetRow.toggle("s.watch", Ui.tr("OK opens the title"), () -> {
            Prefs.setSaverWatch(!Prefs.saverWatch());
            HomeData.get(c).settingsChanged();
        });
        watch.refresh(() -> {
            watch.on = Prefs.saverWatch();
            watch.about = modeNote(h, watchAbout);
        });
        p.add(watch);
        return p;
    }

    /** Immich's albums, read once in the background so My Photos' values and the album list are ready (SA:1868-1872). */
    private static void askAlbumsOnce(SetHost h) {
        Context c = h.context();
        if (albumsAsked || !ImmichPhotos.configured(c)) return;
        albumsAsked = true;
        ImmichPhotos.loadAlbums(c, SetWatch.guarded(h, "settings-albums", () -> {
            if (h.running()) h.refresh();
        }));
    }

    /** My Photos' value (SA:1094-1096, 1866-1880). */
    private static String photosValue(Context c) {
        int photos = Photos.files(c).size();
        if (ImmichPhotos.configured(c)) {
            String source = Prefs.photoSource();
            if ("default".equals(source)) return Ui.tr("photos|Default");
            if (!Prefs.photos()) return Ui.tr("Off");   // nothing syncs: OK on a choice turns it on again
            String from = "album".equals(source) ? Ui.trf("Immich: {album}", "album", Prefs.photoAlbumName()) : Ui.tr("Immich: all");
            return photos == 0 ? from : from + " · " + Ui.plural("plural|one:{n} photo|other:{n} photos", photos);
        }
        return photos == 0 ? Ui.tr("photos|None")
                : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off");
    }

    private static String sourceValue() {
        String source = Prefs.photoSource();
        if ("default".equals(source)) return Ui.tr("photos|Default");
        return "album".equals(source) ? Ui.trf("Immich: {album}", "album", Prefs.photoAlbumName()) : Ui.tr("Immich: all");
    }

    private static SetPage photos(SetHost h) {
        Context c = h.context();
        askAlbumsOnce(h);
        SetPage p = new SetPage("photos", Ui.tr("My Photos"), "photo");
        String pageAbout = Ui.tr("Your own photos on the screensaver.");
        p.about = pageAbout;

        final SetRow show = SetRow.toggle("p.show", Ui.tr("Show my photos"), () -> Prefs.setPhotos(!Prefs.photos()))
                .about(Ui.tr("Off, the photos stay stored but don't show."));   // SA:1436-1439: no settingsChanged
        show.refresh(() -> {
            show.hidden = Photos.files(c).isEmpty() && !ImmichPhotos.configured(c);
            show.on = Prefs.photos();
        });
        p.add(show);

        String sourceAbout = Ui.tr("Where the photos come from: the ones sent to the TV, all of Immich's, or an Immich album.");
        final SetRow source = SetRow.choice("p.source", Ui.tr("Source"),
                () -> h.push("pick.photoSource", hh -> sourcePicker(hh, sourceAbout)), null).about(sourceAbout);
        source.refresh(() -> {
            source.hidden = !ImmichPhotos.configured(c);
            source.value = sourceValue();
        });
        p.add(source);

        final SetRow count = SetRow.info("p.count", Ui.tr("Photos on this TV")).about(pageAbout);
        count.refresh(() -> {
            int n = Photos.files(c).size();
            count.value = n == 0 ? Ui.tr("photos|None") : Ui.plural("plural|one:{n} photo|other:{n} photos", n);
        });
        p.add(count);

        p.add(SetRow.info("p.how", Ui.tr("How to send photos")).icon("info")
                .about(Ui.tr("Photos come from a computer on the same network, with the project's photo tool. The project's site shows how.")));

        final SetRow delete = SetRow.destructive("p.delete", Ui.tr("Delete the photos"), () -> askDelete(h)).icon("trash")
                .about(pageAbout);
        delete.refresh(() -> delete.hidden = Photos.files(c).isEmpty());
        p.add(delete);
        return p;
    }

    /** Asks (Cancel focused), then deletes the imported photos, off the main thread (SA:1848-1862). */
    private static void askDelete(SetHost h) {
        Context c = h.context();
        int n = Photos.files(c).size();
        if (n == 0) return;
        List<String> body = new ArrayList<>();
        body.add(Ui.tr("The copies on your computer stay there."));
        h.confirm(Ui.plural("plural|one:Delete {n} photo on this TV?|other:Delete the {n} photos on this TV?", n), body,
                Ui.tr("Delete"), true, false, () -> {
                    final Context app = c.getApplicationContext();
                    final android.os.Handler main = h.handler();
                    Safe.IO.execute(Safe.wrap("settings-photos-remove", () -> {
                        Photos.removeAll(app);
                        main.post(SetWatch.guarded(h, "settings-photos-removed", () -> {
                            if (h.running()) h.refresh();
                        }));
                    }));
                });
    }

    /** Where My Photos come from with an Immich server: the default, all of Immich, or one of its albums (SA:1883-1889). */
    private static SetPage sourcePicker(SetHost h, String about) {
        Context c = h.context();
        SetPage p = new SetPage("pick.photoSource", Ui.tr("Source"), "photo");
        p.about = about;
        String now = Prefs.photoSource();
        p.add(SetRow.option("src.default", Ui.tr("photos|Default"), "default".equals(now), () -> {
            choosePhotoSource(c, "default", "", "");
            h.pop();
        }).about(about));
        p.add(SetRow.option("src.immich", Ui.tr("Immich: all photos"), "immich".equals(now), () -> {
            choosePhotoSource(c, "immich", "", "");
            h.pop();
        }).about(about));
        final SetRow album = SetRow.option("src.album", Ui.tr("Immich albums"), "album".equals(now), () -> openAlbums(h))
                .about(about);
        album.refresh(() -> {
            album.busy = waitingAlbums;
            album.on = "album".equals(Prefs.photoSource());
            album.value = album.on ? Prefs.photoAlbumName() : "";
        });
        p.add(album);
        p.onLeave = () -> waitingAlbums = false;
        return p;
    }

    /** OK on Immich albums: their list, at once if read, else when it comes (SA:1903-1916). */
    private static void openAlbums(SetHost h) {
        if (!ImmichPhotos.albums.isEmpty()) {
            h.push("pick.album", SetPicksPages::albumPicker);
            return;
        }
        if (waitingAlbums) return;
        waitingAlbums = true;
        h.refresh();
        ImmichPhotos.loadAlbums(h.context(), SetWatch.guarded(h, "settings-albums", () -> {
            if (!waitingAlbums) return;
            waitingAlbums = false;
            if (!h.running()) return;
            if (!ImmichPhotos.albums.isEmpty()) h.push("pick.album", SetPicksPages::albumPicker);
            else h.refresh();
        }));
        // The server may not answer (loadAlbums then never calls back): the spinner stops and a toast says so.
        h.handler().postDelayed(SetWatch.guarded(h, "settings-albums-late", () -> {
            if (!waitingAlbums) return;
            waitingAlbums = false;
            if (!h.running()) return;
            h.refresh();
            h.toast(Ui.tr("That didn't work."), true);
        }), 15_000);
    }

    private static SetPage albumPicker(SetHost h) {
        Context c = h.context();
        List<String[]> choices = new ArrayList<>();
        for (ImmichPhotos.Album a : ImmichPhotos.albums) choices.add(new String[] {a.id, a.name});
        String current = "album".equals(Prefs.photoSource()) ? Prefs.photoAlbum() : "";
        String about = Ui.tr("Where the photos come from: the ones sent to the TV, all of Immich's, or an Immich album.");
        return SetPage.picker(h, "pick.album", Ui.tr("Immich albums"), "photo", about, choices, current, id -> {
            String name = "";
            for (String[] choice : choices) if (choice[0].equals(id)) name = choice[1];
            choosePhotoSource(c, "album", id, name);
        });
    }

    /** A choice in My Photos' lists: applied (the photos change at the next home screen; SA:1917-1922). */
    private static void choosePhotoSource(Context c, String source, String id, String name) {
        if (!source.equals(Prefs.photoSource()) || !id.equals(Prefs.photoAlbum())) {
            Prefs.setPhotoSource(source, id, name);
            ImmichPhotos.sourceChanged(c);
        }
        // Also when it's the same choice: My Photos turned off earlier (before the key came) is on again
        Prefs.setPhotos(!"default".equals(source));
    }
}
