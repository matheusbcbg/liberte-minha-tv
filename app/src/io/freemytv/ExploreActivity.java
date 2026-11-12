package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.view.KeyEvent;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Explorar: TMDB browsing (Brazil) in its own process (:explore), so nothing that happens here counts toward the home
 * screen's crash counter: no uncaught-exception handler of the home screen here, no shared picks object, no Prefs writes.
 */
public final class ExploreActivity extends Activity {
    static final String EXTRA_TARGET = "io.freemytv.explore.TARGET";
    static final int REQUEST_VOICE = 41, REQUEST_WATCH = 42;
    private static final Pattern TARGET = Pattern.compile("^(page:[a-z]{3,10}|(movie|tv)/\\d{1,9})$");
    /** Stopped this long, Explore closes itself (the next open starts fresh and small). */
    private static final long STALE_MS = 30 * 60_000L;
    /** Back after this long without a target: Explore starts over at Início's hero. */
    static final long RESET_MS = 10 * 60_000L;
    private static int resumedCount;   // Explore activities between onResume and onPause (UI thread)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishStale = this::finish;
    private ExploreView view;
    private Click click;
    private long stoppedAt;

    /**
     * target: null or "" → Início; "page:<home|movies|shows|docs|animation|services|search>"; "<movie|tv>/<1-9 digits>"
     * → detail (fromHome). Anything else is ignored.
     */
    static Intent intentFor(Context c, String target) {
        Intent i = new Intent(c, ExploreActivity.class);
        if (target != null && TARGET.matcher(target).matches()) i.putExtra(EXTRA_TARGET, target);
        return i;
    }

    /** The intent's target, validated again (an extra can come from anywhere that can start us). */
    private static String target(Intent i) {
        if (i == null) return null;
        String t;
        try {
            t = i.getStringExtra(EXTRA_TARGET);
        } catch (RuntimeException e) {   // a malformed extras bundle
            return null;
        }
        return t != null && TARGET.matcher(t).matches() ? t : null;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.reload();
        Ui.init(this);
        click = new Click(this);
        view = new ExploreView(this, savedInstanceState);
        setContentView(view);
        // After the first frame the view paints its own background: the window's is one full-screen fill too many.
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                view.post(() -> getWindow().setBackgroundDrawable(null));
                return true;
            }
        });
        if (savedInstanceState == null) view.route(target(getIntent()), false);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        view.route(target(intent), true);
    }

    @Override protected void onStart() {
        super.onStart();
        handler.removeCallbacks(finishStale);
    }

    @Override protected void onResume() {
        super.onResume();
        Prefs.reload();
        BrProviders.refresh(this, view::providersChanged);
        resumedCount++;
        view.images().setBudget(48 << 20);
        view.resume();
    }

    @Override protected void onPause() {
        resumedCount = Math.max(0, resumedCount - 1);
        view.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        stoppedAt = SystemClock.elapsedRealtime();
        if (resumedCount == 0) view.images().setBudget(8 << 20);   // not under a newer Explore that is in front
        handler.removeCallbacks(finishStale);
        handler.postDelayed(finishStale, STALE_MS);
    }

    /** How long Explore has been stopped (0 if it never was). */
    long stoppedFor() {
        return stoppedAt == 0 ? 0 : SystemClock.elapsedRealtime() - stoppedAt;
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        view.save(out);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Tmdb.get(this).trim(level);
        view.images().trim(level);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(finishStale);
        view.destroy();
        click.release();
        view.images().release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (RemoteKeys.onKey(this, e)) return true;   // the remote's app buttons keep working here
        if (view.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_VOICE || resultCode != RESULT_OK || data == null) return;
        try {
            ArrayList<String> found = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (found != null && !found.isEmpty() && found.get(0) != null) view.voiceResult(found.get(0));
        } catch (RuntimeException e) {
            Safe.log("voice", e);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_WATCH) view.watchChanged();
    }

    /** Leaves for the home screen with no animation of our own. */
    void leave() {
        finish();
        overridePendingTransition(0, 0);
    }

    Click click() {
        return click;
    }

    /** A draw, key or frame threw: Explore closes rather than keep a broken screen (never the home screen's process). */
    void fail() {
        if (view != null) view.markBroken();
        if (!isFinishing()) handler.post(this::finish);
    }
}
