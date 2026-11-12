package io.freemytv;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The settings' system pages (SPEC3 §1.3): the root "Ajustes", the Home button's mode (with the switch's progress and
 * result, §3.5), Speed and memory, the sleep timer, About with its notices and credits, and their pickers. Rows only
 * read SetState's cached values in onRefresh; every action is the legacy SettingsView's statements (§4.7).
 */
final class SetSystem {
    private SetSystem() {}

    /** The page for `id`, or null when it isn't one of these. */
    static SetPage build(SetHost h, String id) {
        if (h == null || h.state() == null || id == null) return null;
        switch (id) {
            case "root": return root(h);
            case "mode": return mode(h);
            case "metrics": return metrics(h);
            case "sleep": return sleep(h);
            case "about": return about(h);
            case "notices": return notices(h);
            case "credits": return credits(h);
            default: return null;
        }
    }

    // MARK: - Root

    private static SetPage root(SetHost h) {
        Context c = h.context();
        SetState st = h.state();
        st.resultSeen();   // built again when the mode page (or any other) was left: the switch's result was seen
        SetPage p = new SetPage("root", Ui.tr("Settings"), "sparkles");

        final SetRow mode = SetRow.nav("mode", Ui.tr("Home button"), () -> h.push("mode"))
                .icon("house").need(Capabilities.NEED_SWITCH).brand()
                .about(Ui.trf("Choose what the remote's Home button opens: {app} or the TV's original home screen.",
                        "app", Ui.APP_SHORT));
        mode.refresh(() -> {
            speak(h);
            if (st.busy()) {
                mode.busy = true;
                mode.tone = SetRow.TONE_NONE;
                mode.value = Ui.tr("Switching…");
            } else if (st.stalled() || !st.error().isEmpty()) {
                mode.busy = false;
                mode.tone = SetRow.TONE_WARN;
                mode.value = Ui.tr("Didn't switch");
            } else {
                mode.busy = false;
                mode.tone = SetRow.TONE_NONE;
                mode.value = st.freeActive() ? Ui.APP_SHORT : Ui.tr("Original");
            }
        });
        p.add(mode);

        final SetRow sleep = SetRow.nav("sleep", Ui.tr("Sleep Timer"), () -> h.push("sleep"))
                .icon("moon").need(Capabilities.NEED_ADB)
                .about(Ui.tr("The TV turns itself off after the time you choose."));
        sleep.refresh(() -> sleep.value = sleepValue(st));
        p.add(sleep);
        p.gap();

        final SetRow home = SetRow.nav("home", Ui.tr("Home Screen"), () -> h.push("home"))
                .icon("grid")
                .about(Ui.tr("The home screen's layout, the apps it shows and what the TV shows when it turns on."));
        home.refresh(() -> {
            String[] labels = StartSettings.layoutLabels();
            int i = StartSettings.layoutIndex();
            home.value = i >= 0 && i < labels.length ? labels[i] : "";
        });
        p.add(home);

        final SetRow picks = SetRow.nav("picks", Ui.tr("settings|Picks and Continue Watching"), () -> h.push("picks"))
                .icon("sparkles")
                .about(Ui.tr("What's trending on your apps and what you were watching, at the top of the home screen."));
        picks.refresh(() -> picks.value = Recs.hasKey(c) ? "" : Ui.tr("Set Up…"));
        p.add(picks);

        p.add(SetRow.nav("widgets", Ui.tr("Widgets"), () -> h.push("widgets"))
                .icon("sun_cloud")
                .about(Ui.tr("The weather, what's playing and the quotes, on the home screen.")));
        p.add(SetRow.nav("saver", Ui.tr("settings|Screensaver"), () -> h.push("saver"))
                .icon("photo")
                .about(Ui.tr("What the TV shows when it sits idle: pictures of films and series, and your own photos.")));
        p.add(SetRow.nav("look", Ui.tr("Appearance and language"), () -> h.push("look"))
                .icon("appearance")
                .about(Ui.tr("Language, light or dark theme, clock format and sounds.")));
        p.add(SetRow.nav("remote", Ui.tr("Remote control"), () -> {
                    HomeMode.ensureRemoteService(h.context(), 0);   // the buttons work from inside other apps through this service
                    h.push("remote");
                })
                .icon("remote").need(Capabilities.NEED_REMOTE_BUTTONS)
                .about(Ui.tr("Choose the app each app button on the remote opens.")));
        p.gap();

        final SetRow about = SetRow.nav("about", Ui.tr("About"), () -> h.push("about"))
                .icon("sparkles").brand()
                .about(Ui.trf("Version, updates, notices and credits for {app}.", "app", Ui.APP_SHORT));
        about.refresh(() -> about.badge = st.updateReady());
        p.add(about);

        // As the drawer's TV Settings: where nothing answers the standard request (an HY300 Pro+ in Free My TV mode,
        // #379), Android TV's own settings, and never a crash.
        p.add(SetRow.external("tv", Ui.tr("TV Settings"),
                        () -> HomeApps.open(h.activity(), HomeApps.tvSettings(), null))
                .icon("tv")
                .about(Ui.tr("Opens the TV's own settings: picture, sound, network and accounts.")));
        return p;
    }

    /** Says the switch's news (stalled, or how it ended) once, after this refresh. */
    private static void speak(SetHost h) {
        String text = h.state().takeAnnouncement();
        if (text != null && !text.isEmpty()) h.handler().post(() -> {
            if (h.running()) h.announce(text);
        });
    }

    // MARK: - Home button (the mode)

    private static SetPage mode(SetHost h) {
        SetState st = h.state();
        SetPage p = new SetPage("mode", Ui.tr("Home button"), "house");
        p.about = Ui.trf("Choose what the remote's Home button opens: {app} or the TV's original home screen.",
                "app", Ui.APP_SHORT);
        String progressAbout = Ui.tr("The switch takes a few minutes, and the screen blinks a few times while it "
                + "measures. You can leave: it carries on.");
        String adbStep = Ui.tr("On the TV: tick “Always allow” and press OK.");

        final SetRow progress = SetRow.progress("mode.progress").icon("house");
        progress.refresh(() -> {
            speak(h);
            progress.hidden = !st.busy();
            progress.busy = true;
            String step = st.busyText();
            progress.title = step.isEmpty() ? Ui.tr("Starting…") : step;
            progress.about = st.adbPrompt() ? progressAbout + "\n\n" + adbStep : progressAbout;
        });
        p.add(progress);

        final SetRow reverted = SetRow.info("mode.reverted", Ui.tr("Back to the original")).icon("warning");
        reverted.tone = SetRow.TONE_WARN;
        reverted.refresh(() -> {
            reverted.hidden = !st.reverted();
            reverted.about = st.newestNotice();
        });
        p.add(reverted);

        // The result: "Pronto" for a switch asked here, "Não trocou" for one that never started or failed. It takes the
        // focus when it appears (TalkBack then reads it, with the whole text).
        final boolean[] resultShown = {resultVisible(st)};
        final SetRow result = SetRow.info("mode.result", Ui.tr("Done"));
        result.refresh(() -> {
            boolean visible = resultVisible(st);
            result.hidden = !visible;
            boolean warn = st.stalled() || !st.error().isEmpty();
            result.tone = warn ? SetRow.TONE_WARN : SetRow.TONE_GOOD;
            result.icon = warn ? "warning" : "check";
            result.title = warn ? Ui.tr("Didn't switch") : Ui.tr("Done");
            result.about = st.stalled() ? st.stalledText() : warn ? st.error() : st.newestNotice();
            if (visible && !resultShown[0]) h.handler().post(() -> {
                if (h.running() && !result.hidden) h.focus("mode.result");
            });
            resultShown[0] = visible;
        });
        p.add(result);

        Runnable toFree = () -> {
            if (st.freeActive()) return;   // the one in use: nothing to do
            List<String> body = Arrays.asList(
                    Ui.trf("The Home button will open {app}, with the new screensaver.", "app", Ui.APP_SHORT),
                    Ui.tr("Some factory apps are turned off. They come back if you pick Original."),
                    Ui.tr("It takes a few minutes, and the screen blinks a few times while it measures."),
                    Ui.tr("If the TV asks “Allow USB debugging?”, tick “Always allow” and press OK."));
            h.confirm(Ui.trf("Use {app}?", "app", Ui.APP_SHORT), body, Ui.trf("Use {app}", "app", Ui.APP_SHORT),
                    false, true, () -> request(h, HomeMode.FREE));
        };
        Runnable toOriginal = () -> {
            // As the legacy choice: "already" is the file's mode, so after the watchdog's revert (the check on Original)
            // OK still asks for Original, which turns the apps back on for good.
            if (HomeMode.ORIGINAL.equals(st.mode())) return;
            List<String> body = Arrays.asList(
                    Ui.tr("The Home button will open the TV's factory home screen again, with its own screensaver."),
                    Ui.tr("The factory apps that were off come back."),
                    Ui.tr("The remote's app buttons and the sleep timer stop working."),
                    Ui.tr("Your settings stay saved."));
            h.confirm(Ui.tr("Go back to the original home screen?"), body, Ui.tr("Go back to the original"),
                    false, true, () -> request(h, HomeMode.ORIGINAL));
        };
        // A row's reason wins over its need (SetPage.refresh), so this one says both: the switch under way, else what
        // NEED_SWITCH says (a phone, a TV model the switch bricks).
        java.util.function.Supplier<String> waiting = () -> st.busy() ? Ui.tr("Wait for the switch to finish.")
                : Capabilities.missing(h.context(), Capabilities.NEED_SWITCH);

        final SetRow free = SetRow.option("mode.free", Ui.APP_SHORT, st.freeActive(), toFree)
                .icon("sparkles").brand().need(Capabilities.NEED_SWITCH).reason(waiting)
                .about(Ui.tr("No ads. Picks, Continue Watching, widgets and the new screensaver. Some factory apps stay "
                        + "off while it's in use."));
        free.refresh(() -> free.on = st.freeActive());
        p.add(free);

        final SetRow original = SetRow.option("mode.original", Ui.tr("The TV's original"), !st.freeActive(), toOriginal)
                .icon("tv").need(Capabilities.NEED_SWITCH).reason(waiting)
                .about(Ui.tr("The TV's factory home screen, as it came. The apps that were off come back."));
        original.refresh(() -> original.on = !st.freeActive());
        p.add(original);
        p.gap();

        final SetRow metrics = SetRow.nav("mode.metrics", Ui.tr("Speed and memory"), () -> h.push("metrics"))
                .icon("gauge")
                .about(Ui.tr("How long each home screen takes to open and how much memory it leaves free, measured at "
                        + "each switch."));
        metrics.refresh(() -> metrics.value = st.memorySoon() ? "…" : "");
        p.add(metrics);

        // Pushed: the focus starts on the status row when one shows, else on the option in use.
        p.onEnter = () -> {
            if (st.busy() || st.reverted() || resultVisible(st)) return;
            h.focus(st.freeActive() ? "mode.free" : "mode.original");
        };
        return p;
    }

    private static boolean resultVisible(SetState st) {
        return !st.busy() && (st.stalled() || st.switchEndedHere() || !st.error().isEmpty());
    }

    /** The sheet's action: the switch is asked, and the person stays here, on its progress. */
    private static void request(SetHost h, String mode) {
        SetState st = h.state();
        if (st.busy()) {
            h.toast(Ui.tr("Wait for the switch to finish."), true);
            return;
        }
        st.requestMode(mode);
        h.refresh();
        h.focus("mode.progress");
    }

    // MARK: - Speed and memory

    private static SetPage metrics(SetHost h) {
        SetState st = h.state();
        SetPage p = new SetPage("metrics", Ui.tr("Speed and memory"), "gauge");
        p.about = Ui.tr("How long each home screen takes to open and how much memory it leaves free, measured at "
                + "each switch.");
        // The column titles only over measurements (a page opened before any switch shows the one line instead).
        if (st.hasMetrics()) p.header("", Ui.tr("Original"), Ui.APP_SHORT);
        String[][] rows = {
                {"m.home", Ui.tr("Home button time"), "homeMs",
                        Ui.tr("How long the home screen takes to appear after the button: the median of three runs.")},
                {"m.free", Ui.tr("TV's free memory"), "memAvailableMB",
                        Ui.tr("How much of the TV's memory is left for apps, two minutes after the switch.")},
                {"m.pss", Ui.tr("Home screen memory"), "homePssMB",
                        Ui.tr("How much memory the home screen itself uses.")},
        };
        for (String[] row : rows) {
            final String key = row[2];
            final SetRow m = SetRow.metric(row[0], row[1]).icon("gauge").about(row[3]);
            m.refresh(() -> {
                m.hidden = !st.hasMetrics();
                m.value = st.metric(false, key);
                m.value2 = st.metric(true, key);
            });
            p.add(m);
        }
        final SetRow none = SetRow.info("m.none", Ui.tr("No measurements yet")).icon("gauge")
                .about(Ui.tr("Measured at each switch, for both home screens.") + " "
                        + Ui.tr("The screen blinks while the Home button is timed."));
        none.refresh(() -> {
            speak(h);
            none.hidden = st.hasMetrics();
        });
        p.add(none);
        return p;
    }

    // MARK: - Sleep timer (SA:1200-1290, 1395-1405)

    private static SetPage sleep(SetHost h) {
        Context c = h.context();
        SetState st = h.state();
        SetPage p = new SetPage("sleep", Ui.tr("Sleep Timer"), "moon");
        p.about = Ui.tr("The TV turns itself off after the time you choose.");

        String timerAbout = Ui.tr("The TV turns itself off when the time runs out.");
        final SetRow timer = SetRow.choice("sleep.timer", Ui.tr("Turn the TV off"), () -> openSleepPicker(h), dir -> {
            SleepTimer.step(h.context(), dir);
            h.state().sleepChanged();
            h.refresh();
        }).icon("moon").need(Capabilities.NEED_ADB);
        timer.refresh(() -> {
            timer.value = sleepValue(st);
            timer.about = withTimeLeft(st, timerAbout);
        });
        p.add(timer);

        String showAbout = Ui.tr("A small clock on screen counts down the minutes left.");
        final SetRow show = SetRow.toggle("sleep.show", Ui.tr("Show the time left"), () -> {
            Prefs.setSleepShown(!Prefs.sleepShown());
            SleepOverlay.update(h.context());
            h.refresh();   // Position comes and goes with it
        }).icon("moon");
        show.refresh(() -> {
            show.on = Prefs.sleepShown();
            show.about = withTimeLeft(st, showAbout);
        });
        p.add(show);

        String placeAbout = Ui.tr("Which corner of the screen the time left shows in.");
        final SetRow place = SetRow.choice("sleep.place", Ui.tr("Position"), () -> openPlacePicker(h), dir -> {
            Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
            SleepOverlay.update(h.context());
            h.refresh();
        }).icon("moon").need(Capabilities.NEED_OVERLAY);
        place.refresh(() -> {
            place.hidden = !Prefs.sleepShown();
            place.value = SleepOverlay.placeName(Prefs.sleepPlace());
            place.about = withTimeLeft(st, placeAbout);
        });
        p.add(place);
        return p;
    }

    private static String sleepValue(SetState st) {
        int left = st.sleepLeft();
        return left == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(left));
    }

    /** While a timer runs, the left pane leads with the time left. */
    private static String withTimeLeft(SetState st, String about) {
        int left = st.sleepLeft();
        return left == 0 ? about : Ui.trf("{time} left", "time", SleepTimer.duration(left)) + "\n\n" + about;
    }

    private static void openSleepPicker(SetHost h) {
        h.push("pick.sleep", hh -> {
            List<String[]> list = new ArrayList<>();
            list.add(new String[] {"0", Ui.tr("Off")});
            for (int m : new int[] {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240}) {
                list.add(new String[] {String.valueOf(m), SleepTimer.duration(m)});
            }
            String now = String.valueOf(SleepTimer.minutesLeft(hh.context()));
            return SetPage.picker(hh, "pick.sleep", Ui.tr("Turn the TV off"), "moon",
                    Ui.tr("The TV turns itself off when the time runs out."), list, now, key -> {
                        SleepTimer.set(hh.context(), Integer.parseInt(key));
                        hh.state().sleepChanged();
                    });
        });
    }

    private static void openPlacePicker(SetHost h) {
        h.push("pick.place", hh -> {
            List<String[]> list = new ArrayList<>();
            for (int i = 0; i < SleepOverlay.PLACES; i++) list.add(new String[] {String.valueOf(i), SleepOverlay.placeName(i)});
            return SetPage.picker(hh, "pick.place", Ui.tr("Position"), "moon",
                    Ui.tr("Which corner of the screen the time left shows in."), list,
                    String.valueOf(Prefs.sleepPlace()), key -> {
                        Prefs.setSleepPlace(Integer.parseInt(key));
                        SleepOverlay.update(hh.context());
                    });
        });
    }

    // MARK: - About

    private static SetPage about(SetHost h) {
        SetState st = h.state();
        SetPage p = new SetPage("about", Ui.tr("About"), "sparkles");
        p.brand = true;
        p.about = Ui.trf("Version, updates, notices and credits for {app}.", "app", Ui.APP_SHORT);
        String what = Ui.trf("{app} replaces the TV's home screen: no ads, with picks and a new screensaver.",
                "app", Ui.APP_SHORT);

        SetRow name = SetRow.info("a.name", Ui.tr("Name")).icon("sparkles").brand().about(what);
        name.value = Ui.APP_NAME;
        p.add(name);
        SetRow version = SetRow.info("a.version", Ui.tr("Version")).icon("sparkles").brand().about(what);
        version.value = versionName(h.context());
        p.add(version);

        final SetRow updates = SetRow.action("a.updates", Ui.tr("Updates"), () -> {
            h.state().updateNow();
            h.refresh();
        }).icon("refresh").brand();
        updates.refresh(() -> {
            updates.value = st.updatesValue();
            updates.busy = st.updating();
            updates.badge = st.updateReady();
            updates.about = st.updatesAbout();
        });
        p.add(updates);

        final SetRow notices = SetRow.nav("a.notices", Ui.tr("Notices"), () -> h.push("notices"))
                .icon("bell").brand()
                .about(Ui.tr("What happened at each switch, newest first."));
        notices.refresh(() -> {
            int n = st.notices().size();
            notices.value = n > 0 ? String.valueOf(n) : "";
        });
        p.add(notices);

        p.add(SetRow.nav("a.credits", Ui.tr("Credits"), () -> h.push("credits"))
                .icon("doc").brand()
                .about(Ui.trf("The services and tools {app} uses.", "app", Ui.APP_SHORT)));
        return p;
    }

    /** "1.3" (the old screen said "Free My TV 1.3": the name is in its own row now). */
    private static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** One row per notice, newest first; its whole text on the left pane. */
    private static SetPage notices(SetHost h) {
        SetState st = h.state();
        SetPage p = new SetPage("notices", Ui.tr("Notices"), "bell");
        p.brand = true;
        p.about = Ui.tr("What happened at each switch, newest first.");
        List<String[]> now = st.notices();
        if (now.isEmpty()) {
            p.add(SetRow.info("n.none", Ui.tr("None yet.")).icon("bell")
                    .about(Ui.tr("What happened at each switch, newest first.")));
            return p;
        }
        // Read once per refresh (by the first row, which refreshes first), so "{n} min ago" follows the minute.
        final List<List<String[]>> latest = new ArrayList<>();
        latest.add(now);
        for (int i = 0; i < now.size(); i++) {
            final int at = i;
            final SetRow r = SetRow.info("n." + i, now.get(i)[0]).icon("bell");
            r.value = now.get(i)[1];
            r.about = now.get(i)[0];
            r.refresh(() -> {
                if (at == 0) latest.set(0, st.notices());
                List<String[]> list = latest.get(0);
                r.hidden = at >= list.size();
                if (r.hidden) return;
                r.title = list.get(at)[0];
                r.value = list.get(at)[1];
                r.about = list.get(at)[0];
            });
            p.add(r);
        }
        return p;
    }

    /** The services and tools used, each with the credit it asks for. */
    private static SetPage credits(SetHost h) {
        SetPage p = new SetPage("credits", Ui.tr("Credits"), "doc");
        p.brand = true;
        p.about = Ui.trf("The services and tools {app} uses.", "app", Ui.APP_SHORT);
        p.add(SetRow.info("c.tmdb", "TMDB").icon("doc")
                .about(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.")));
        p.add(SetRow.info("c.watchmode", "Watchmode").icon("doc")
                .about(Ui.tr("Links to the titles by Watchmode.")));
        p.add(SetRow.info("c.meteo", "Open-Meteo").icon("doc")
                .about(Ui.tr("Weather by Open-Meteo.com (CC BY 4.0).")));
        p.add(SetRow.info("c.binance", "Binance").icon("doc")
                .about(Ui.tr("Quotes by Binance.")));
        p.add(SetRow.info("c.inter", "Inter").icon("doc")
                .about(Ui.tr("Inter typeface, SIL Open Font License.")));
        p.add(SetRow.info("c.qr", "QR").icon("doc")
                .about(Ui.tr("QR code generator after Project Nayuki's (MIT).")));
        p.add(SetRow.info("c.symbols", "Material Symbols").icon("doc")
                .about(Ui.tr("Icons from Material Symbols (Apache 2.0).")));
        return p;
    }
}
