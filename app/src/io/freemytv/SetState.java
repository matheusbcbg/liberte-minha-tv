package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * What the settings show that other processes write: the home screen mode and its switch (home-state.json, written by
 * HomeMode/ModeJob in the main process), the update check (update.json), the picks' status (recs-status.json) and the
 * sleep timer. The one poll owner (SPEC3 §4.3): SetView calls poll() every pollMs() while it runs and refreshes the
 * top page when it returns true; rows read the cached values here, never the files.
 *
 * The bodies are the legacy SettingsView's (SettingsActivity.java: poll :230-278, busy…stalledText :485-528, metric
 * and ago :768-782, the picks' status :650-671, updates :1524-1562, pause :419-424), statement order kept.
 */
final class SetState {
    private final Context c;
    private final String installed;   // this app's version (an update starts a new process)
    private JSONObject state, recs, updates;
    private boolean hasKey;
    private int sleepLeft;   // the sleep timer's minutes left, read with the state, not on every refresh
    private long pendingAt;     // uptime of the switch asked here, until its job shows it's busy (or 10 s go by)
    private long requestedAt;   // the switch asked for: its stamp, to tell whether its job ever started
    private boolean stalled;    // it didn't: a TV maker's background blocker (stalledText says what to do)
    private boolean wasBusy;    // the job was busy at the last poll (its end is announced)
    private boolean wasWorking; // busy() at the last poll, pending included (a switch asked here ends)
    private boolean askedHere, endedHere;
    private long checkAskedAt, installAskedAt;   // OK on Updates, on the wall clock as the file's "at"
    private int whyState = -1;
    private String pollStamp = "";
    private String announcement;   // said once by whoever takes it (takeAnnouncement)

    SetState(Context context) {
        Context app = context.getApplicationContext();
        c = app != null ? app : context;
        installed = Updates.installedVersion(c);
        read();
    }

    /** The files and the timer, as poll reads them; also the first read, so the first frame is right. */
    private void read() {
        state = HomeMode.state(c);
        sleepLeft = SleepTimer.minutesLeft(c);
        hasKey = Recs.hasKey(c);
        recs = Recs.status(c);
        updates = Updates.state(c);
    }

    /** Reads home-state.json, update.json and the picks' status; true when something shown changed. */
    boolean poll() {
        read();
        boolean nowBusy = !busyText().isEmpty();
        if (nowBusy) {
            pendingAt = 0;
            stalled = false;
        } else if (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt >= 10_000) {
            // Asked 10 s ago and its job never ran: say why, rather than quietly showing Original again (#364).
            pendingAt = 0;
            if (!ModeJob.started(c, requestedAt)) {
                stalled = true;
                AppLog.w("the switch's job didn't start" + (RemoteKeys.TCL ? " (TCL's AUTO_START not allowed?)" : ""));
                announcement = stalledText();
            }
        }
        // What can change the reasons away from a key: a switch (or the watchdog's revert) and the overlay's and the
        // listener's checks. In the stamp, so the top page works its reasons out again.
        int whyNow = (freeActive() ? 1 : 0) | (Capabilities.refused(c, Capabilities.OVERLAY) ? 2 : 0)
                | (Capabilities.refused(c, Capabilities.LISTENER) ? 4 : 0);
        whyState = whyNow;
        // A switch just ended: TalkBack reads how it went (the newest notice).
        if (wasBusy && !nowBusy) {
            String newest = newestNotice();
            if (!newest.isEmpty()) announcement = newest;
        }
        wasBusy = nowBusy;
        boolean working = busy();
        if (wasWorking && !working && askedHere) {   // the switch asked in this process is over (mode.result)
            askedHere = false;
            endedHere = !stalled;
        }
        wasWorking = working;
        // Something shown changed: also My Photos' count (Immich's arrive while it shows), Trakt's sign-in steps and
        // the minute (the notices' "{n} min ago").
        String stamp = state.toString() + recs.toString() + updates.toString() + ":" + sleepLeft + ":" + whyNow
                + ":" + hasKey + ":" + pendingAt + ":" + stalled + ":" + working + ":" + updatesValue()
                + ":" + endedHere + ":" + traktStamp() + ":" + System.currentTimeMillis() / 60_000
                + ":" + Photos.files(c).size();
        boolean changed = !stamp.equals(pollStamp);
        pollStamp = stamp;
        return changed;
    }

    private String traktStamp() {
        if (!Trakt.available()) return "";
        return Trakt.code() + "|" + Trakt.busy() + "|" + Trakt.signedIn(c) + "|" + Trakt.message();
    }

    /** 500 while a switch or an update is on its way, else 2000. */
    int pollMs() {
        return busy() || updating() ? 500 : 2000;
    }

    // MARK: - The mode switch

    /** A switch under way, or asked here less than 10 s ago and not started yet. */
    boolean busy() {
        return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
    }

    /** Asked here, its job not busy yet ("Starting…"). */
    boolean pending() {
        return busyText().isEmpty() && pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000;
    }

    /** The switch asked here never started (stalledText says why and what to do). */
    boolean stalled() {
        return stalled;
    }

    /** The watchdog took the TV back to its original home screen. */
    boolean reverted() {
        return state.optBoolean("homeReverted");
    }

    boolean freeActive() {
        return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
    }

    /** The mode in the state file, "original" when there is none (the legacy choice's "already" test). */
    String mode() {
        return state.optString("mode", HomeMode.ORIGINAL);
    }

    /** Memory is measured two minutes after a switch: until then its cells say it's coming. */
    boolean memorySoon() {
        return System.currentTimeMillis() - state.optLong("since") < 180_000;
    }

    /**
     * The mode in use was measured at its switch, but its memory wasn't, well after it should have been (the job
     * didn't run, ADB was away, or its home screen wasn't in front then). Only after a switch: before one, asking
     * would bring up the TV's ADB prompt.
     */
    private boolean memoryMissing() {
        String mode = state.optString("mode");
        JSONObject metrics = state.optJSONObject("metrics");
        JSONObject m = metrics == null ? null : metrics.optJSONObject(mode);
        if (m == null || !state.has("since") || memorySoon()) return false;
        if (HomeMode.FREE.equals(mode) ? !freeActive() : state.optString("originalHome").isEmpty()) return false;
        return !m.has("memAvailableMB") || !m.has("homePssMB");
    }

    /** Either home screen was measured at least once. */
    boolean hasMetrics() {
        JSONObject metrics = state.optJSONObject("metrics");
        return metrics != null && (metrics.optJSONObject(HomeMode.ORIGINAL) != null || metrics.optJSONObject(HomeMode.FREE) != null);
    }

    /** A switch asked in this process ended (mode.result shows it until resultSeen). */
    boolean switchEndedHere() {
        return endedHere;
    }

    /** The person has seen the result (the mode page was left): mode.result goes. */
    void resultSeen() {
        if (!busy()) endedHere = false;
    }

    /** The step under way ("" when none, or when its stamp is over 10 minutes old: a job that died). */
    String busyText() {
        long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
        boolean stale = now < at || now - at > 10 * 60_000L;
        return stale ? "" : state.optString("busy");
    }

    /** The busy step is the TV's ADB prompt: the progress row says what to press. */
    boolean adbPrompt() {
        return busyText().equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."));
    }

    /**
     * Why a switch never started, and what to do. TCL's TclAppBoot blocks the job until its permission is allowed
     * from a computer, and an uninstall takes that back (#364, #375): the README's command, for both of its names.
     */
    String stalledText() {
        return RemoteKeys.TCL
                ? Ui.trf("TCL blocks Free My TV from running in the background, so nothing changed. From a computer, "
                        + "run this, then try again: {command}", "command",
                        "adb shell \"appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow\"")
                : Ui.tr("The TV didn't let Free My TV run in the background, so nothing changed. Try again in a minute.");
    }

    /** The last switch's error ("" when it went well). */
    String error() {
        return state.optString("error");
    }

    /** The newest notice's text, or "". */
    String newestNotice() {
        JSONArray notices = state.optJSONArray("notices");
        JSONObject newest = notices == null || notices.length() == 0 ? null : notices.optJSONObject(0);
        return newest == null ? "" : newest.optString("text");
    }

    /** {text, ago} of every notice, newest first. */
    List<String[]> notices() {
        List<String[]> out = new ArrayList<>();
        JSONArray notices = state.optJSONArray("notices");
        for (int i = 0; notices != null && i < notices.length(); i++) {
            JSONObject o = notices.optJSONObject(i);
            if (o == null || o.optString("text").isEmpty()) continue;
            out.add(new String[] {o.optString("text"), ago(o.optLong("at"))});
        }
        return out;
    }

    /**
     * A cell of Speed and memory: "640 ms", "1.52 GB", "…" (the mode just switched to: its memory comes two minutes
     * later) or "—" (not measured).
     */
    String metric(boolean free, String key) {
        JSONObject metrics = state.optJSONObject("metrics");
        JSONObject m = metrics == null ? null : metrics.optJSONObject(free ? HomeMode.FREE : HomeMode.ORIGINAL);
        boolean current = HomeMode.FREE.equals(state.optString("mode")) == free;
        boolean soon = !busy() && memorySoon() && !key.equals("homeMs") && current;
        return metric(m, key, soon);
    }

    private static String metric(JSONObject m, String key, boolean soon) {
        if (m == null || !m.has(key)) return soon ? "…" : "—";
        long v = m.optLong(key);
        if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
        if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
        return Ui.trf("{n} MB", "n", v);
    }

    private static String ago(long at) {
        long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 1) return Ui.tr("now");
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
    }

    /** Asks for the switch (SA:2354-2361): ModeJob.APPLY in the main process; poll follows it. */
    void requestMode(String mode) {
        if (busy()) return;
        requestedAt = HomeMode.request(c, mode);
        pendingAt = SystemClock.uptimeMillis();
        stalled = false;
        askedHere = true;
        endedHere = false;
        wasWorking = true;
    }

    // MARK: - The picks

    /** Picks › Status's value: "12 titles", "Fetching titles…" or "Error". */
    String recsShort() {
        if (!hasKey) return Ui.tr("Set Up…");
        if (!recs.optString("error").isEmpty()) return Ui.tr("Error");
        int count = recs.optInt("count");
        return count > 0 ? Ui.plural("plural|one:{n} title|other:{n} titles", count) : Ui.tr("Fetching titles…");
    }

    /** The picks' status in full (SA:650-662). */
    String recsText() {
        String error = recs.optString("error");
        int count = recs.optInt("count");
        if (!hasKey) return Ui.tr("No TMDB key: add yours in Home Settings > TMDB key.");
        if (error.equals(Recs.NO_KNOWN_APPS)) return Ui.tr("None of the TV's streaming apps can give picks.");
        if (error.equals(Recs.NO_APPS)) return Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
        if (!error.isEmpty()) return Ui.trf("Error: {error}.", "error", error);
        if (count > 0) return Ui.trf("On · {n} titles from the TV's apps, new ones every hour.", "n", count);
        return Ui.tr("Fetching titles…");
    }

    // MARK: - The sleep timer

    /** Minutes left on the sleep timer, 0 when off (read by poll and sleepChanged). */
    int sleepLeft() {
        return sleepLeft;
    }

    /** The timer was just set here: read it again now rather than at the next poll. */
    void sleepChanged() {
        sleepLeft = SleepTimer.minutesLeft(c);
    }

    // MARK: - Updates (SA:1524-1562)

    /**
     * OK on Updates: installs one that's ready, else checks now. Both run as jobs in the main process, as the week's
     * check does, so one process writes the state; poll reads it.
     */
    void updateNow() {
        updates = Updates.state(c);
        long now = System.currentTimeMillis();
        if (Updates.ready(updates, installed)) {
            if (now - installAskedAt < 5000) return;   // Android's installer is on its way
            installAskedAt = now;
            ModeJob.schedule(c, ModeJob.INSTALL, "", 0);
        } else {
            if (Updates.busy(updates) || checkAsked()) return;
            checkAskedAt = now;
            ModeJob.schedule(c, ModeJob.UPDATE, "", 0);
        }
    }

    /** OK asked for a check that hasn't started yet (the job writes "checking" as it starts). */
    private boolean checkAsked() {
        long now = System.currentTimeMillis();
        return now - checkAskedAt < 10_000 && updates.optLong("at") < checkAskedAt;
    }

    /** The Updates row: what the last check found, or "Checking…" from OK until the check starts. */
    String updatesValue() {
        if (checkAsked()) return Ui.tr("Checking…");
        if (Updates.BUILD.equals(updates.optString("state"))) {
            return Ui.trf("{version} available", "version", updates.optString("version"));
        }
        return Updates.summary(updates, installed);
    }

    /** What OK on Updates does now. */
    String updatesAbout() {
        if (Updates.BUILD.equals(updates.optString("state"))) {
            return Ui.trf("This copy was built on a computer: build it again there to get {version}.", "version",
                    updates.optString("version"));
        }
        return updateReady() ? Ui.tr("OK installs it; Android asks first.") : Ui.tr("OK checks for a new version.");
    }

    /** A downloaded update, newer than this app, waits for OK (About's badge). */
    boolean updateReady() {
        return Updates.ready(updates, installed);
    }

    /** A check or an install on its way or under way: poll reads the file more often. */
    boolean updating() {
        return Updates.busy(updates) || System.currentTimeMillis() - Math.max(checkAskedAt, installAskedAt) < 10_000;
    }

    // MARK: - Lifecycle

    /** Settings leaves the screen: a missing memory measurement is asked again (SA:419-424). */
    void pause() {
        if (!busy() && memoryMissing()) {   // measured once the home screen is back in front
            AppLog.w("memory of " + state.optString("mode") + " missing: measuring again");
            ModeJob.schedule(c, ModeJob.MEMORY, state.optString("mode"), 5_000);
        }
    }

    /**
     * What TalkBack should say about the switch since the last call (the stalled text, or the newest notice when a
     * switch ended), once; null when nothing. Whoever reads it first says it, so it is never said twice.
     */
    String takeAnnouncement() {
        String a = announcement;
        announcement = null;
        return a;
    }

    /** The reasons' inputs at the last poll (mode, overlay and listener refusals), for whoever caches reasons. */
    int whyState() {
        return whyState;
    }
}
