package io.freemytv;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

/**
 * Resuming a Continue Watching item from Explore: a copy of HomeView.linkIntent and openApp (HomeView keeps its own),
 * logging only class names. Another app wrote the link, so it only ever opens that app, never ours or a third one,
 * with no selector and no permissions handed along.
 */
final class WatchLaunch {
    private WatchLaunch() {}

    /** The app's own link as a safe intent, or null if it can't be used. */
    static Intent safeIntent(String uri, String pkg) {
        if (uri == null || pkg == null || HomeMode.PKG.equals(pkg)) return null;
        Intent intent;
        try {
            intent = uri.startsWith("intent:") ? Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri));
        } catch (java.net.URISyntaxException | RuntimeException e) {   // a bad launchFlags= throws NumberFormatException
            Safe.log("continue watching link", e);
            return null;
        }
        if (intent.getComponent() != null && !intent.getComponent().getPackageName().equals(pkg)) return null;
        intent.setSelector(null);
        intent.setPackage(pkg);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
        return intent;
    }

    /**
     * Resumes it where it was left (the app's own link), growing out of r (in from's coordinates); else the app from
     * its start; false if neither opens (the caller shows "Couldn't open {app}.").
     */
    static boolean open(Activity a, WatchNext.Item it, View from, RectF r) {
        if (a == null || it == null || it.pkg == null) return false;
        Bundle options = null;
        if (from != null && r != null) {
            options = ActivityOptions.makeScaleUpAnimation(from, Math.round(r.left), Math.round(r.top),
                    Math.max(1, Math.round(r.width())), Math.max(1, Math.round(r.height()))).toBundle();
        }
        Intent link = safeIntent(it.intent, it.pkg);
        if (link != null && link.resolveActivity(a.getPackageManager()) != null) {
            try {
                a.startActivity(link.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                Safe.log("continue watching link", e);
            }
        }
        Intent launch = HomeApps.launchIntent(a.getPackageManager(), it.pkg);
        if (launch == null) return false;
        try {
            a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Safe.log("continue watching app", e);
            return false;
        }
    }
}
