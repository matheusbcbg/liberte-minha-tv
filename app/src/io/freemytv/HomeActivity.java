package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 *
 * Two layouts: Início (StartView, "Moderno") and the classic one (HomeView, "Clássico"). A fault Início catches swaps
 * it for the classic one in place (startFault), so it never costs a crash; an uncaught one keeps the classic one for
 * 24 h (Watchdog.startBlocked).
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    /** Opens Recent Apps (the Control Center's "Recent Apps" tile). */
    static final String EXTRA_RECENTS = "io.freemytv.RECENTS";
    private HomeView home;
    private StartView start;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        if (useStart()) {
            Watchdog.screen = "start";
            try {
                start = new StartView(this);
                setContentView(start);
            } catch (RuntimeException | OutOfMemoryError e) {
                // Nothing recorded: the classic home screen simply shows this time.
                AppLog.w("start: " + e.getClass().getSimpleName());
                if (start != null) {
                    try {
                        start.destroy();
                    } catch (RuntimeException ignored) {
                    }
                }
                start = null;
            }
        }
        if (start == null) {
            Watchdog.screen = "classic";
            home = new HomeView(this);
            setContentView(home);
        }
        getWindow().setBackgroundDrawable(null);
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_ALL_APPS, false)) showAllApps();
        else if (intent != null && intent.getBooleanExtra(EXTRA_RECENTS, false)) handler.post(this::showRecents);
        // At power-on "Live TV" (#346): the first time the home screen comes up after a full start, in Free My TV mode.
        if (HomeMode.noteHomeShown(this) && SystemClock.elapsedRealtime() < 240_000 && "live".equals(Prefs.powerOn())) {
            org.json.JSONObject s = HomeMode.state(this);
            if (HomeMode.FREE.equals(s.optString("mode")) && !s.optBoolean("homeReverted")) handler.postDelayed(powerOnLive, 10_000);
        }
    }

    /** Início, unless the classic layout is picked or Início is blocked after a problem. */
    private boolean useStart() {
        try {
            return Prefs.LAYOUT_MODERN.equals(Prefs.homeLayout()) && !Watchdog.startBlocked(this);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean resumed, keyPressed, destroyed;

    /** Live TV, unless a key came first or something else is in front by then. */
    private final Runnable powerOnLive = () -> {
        if (!resumed || keyPressed) return;
        AppLog.w("at power-on: Live TV");
        HomeApps.open(this, HomeApps.action(HomeApps.LIVE_TV, HomeApps.TOP_LIVE, Ui.tr("Live TV"), "live_tv"), null);
    };

    /**
     * Início caught a fault (a draw, key, frame or listener threw): the classic home screen takes its place in this
     * process, with a toast. Recorded as a soft fault (3 in 24 h keep Início off), never as a crash.
     */
    void startFault(String tag) {
        if (start == null || destroyed) return;
        AppLog.w("start fault: " + tag);
        try {
            Watchdog.softFault(this);
        } catch (RuntimeException ignored) {
        }
        StartView old = start;
        start = null;
        try {
            old.destroy();
        } catch (RuntimeException ignored) {
        }
        Watchdog.screen = "classic";
        home = new HomeView(this);
        setContentView(home);
        if (resumed) home.resume();
        home.showToast(Ui.tr("The new Home Screen ran into a problem and went back to the classic one."));
    }

    /** A call into Início that threw outside its own guards: same as a fault it caught. */
    private void startThrew(String tag, RuntimeException e) {
        Safe.log(tag, e);
        startFault(tag);
    }

    private void showAllApps() {
        if (start != null) {
            try {
                start.showAllApps();
            } catch (RuntimeException e) {
                startThrew("all apps", e);
            }
        } else if (home != null) {
            home.showAllApps();
        }
    }

    private void showRecents() {
        if (start != null) {
            try {
                start.showRecents();
            } catch (RuntimeException e) {
                startThrew("recents", e);
            }
        } else if (home != null) {
            home.showRecents();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language, city or layout: draw everything again
            return;
        }
        if (start != null) {
            try {
                start.resume();
            } catch (RuntimeException e) {
                startThrew("resume", e);
            }
        } else if (home != null) {
            home.resume();
        }
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        resumed = false;
        if (start != null) {
            try {
                start.pause();
            } catch (RuntimeException e) {
                startThrew("pause", e);
            }
        } else if (home != null) {
            home.pause();
        }
        super.onPause();
    }

    @Override protected void onStop() {
        if (start != null) {
            try {
                start.stop();
            } catch (RuntimeException e) {
                startThrew("stop", e);
            }
        }
        super.onStop();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (start == null && home == null) return;
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) {
            showAllApps();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_RECENTS, false)) {
            showRecents();
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean twice = Prefs.recents().equals("home") && lastHome != 0 && now - lastHome < TWICE_MS;
        lastHome = twice ? 0 : now;
        if (twice) {
            showRecents();
        } else if (start != null) {
            try {
                start.reset(true);
            } catch (RuntimeException e) {
                startThrew("reset", e);
            }
        } else {
            home.reset(true);
        }
    }

    /** When Home was last pressed, and how soon the second press counts (Settings > Home Settings). */
    private long lastHome;
    private static final long TWICE_MS = 700;

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacks(powerOnLive);
        handler.removeCallbacksAndMessages(null);
        if (start != null) {
            try {
                start.destroy();
            } catch (RuntimeException e) {
                Safe.log("destroy", e);
            }
            start = null;
        }
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (start != null) {
            try {
                start.trim(level);
            } catch (RuntimeException e) {
                startThrew("trim", e);
            }
        }
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        keyPressed = true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (start != null) {
            try {
                if (start.onKey(event)) return true;
            } catch (RuntimeException e) {
                startThrew("key", e);
                return true;
            }
        } else if (home != null && home.onKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

}
