package io.freemytv;

import android.graphics.RectF;

/**
 * What Início's parts (the grid, the Top Shelf, the status row, the menu, the folder sheet) ask of their root,
 * StartView. Main thread only.
 */
interface StartHost {
    /** Something moves: post a frame. */
    void wake();
    /** Opens an app, input, Live TV or our settings with the scale-up from {@code from} (screen coordinates). */
    void openItem(HomeApps.Item it, RectF from);
    /** Opens Explore at {@code target} ({@code HomeApps.explore(target)}; null = its Início page). */
    void openExplore(String target, RectF from);
    /** Opens a title's page inside its app ({@code Pick.link}). */
    void openLink(String pkg, String label, String link, RectF from);
    /** Resumes a Continue Watching item through its own intent (WatchLaunch.open). */
    void resumeWatching(WatchNext.Item it, RectF from);
    /** Hides a Continue Watching item (Prefs.watchHidden + WatchNext.hideKey); the row shrinks. */
    void hideWatching(WatchNext.Item it);
    /** Opens the context menu anchored to {@code spec.anchor}. */
    void showMenu(StartMenu.Spec spec);
    /** A toast in the hint chip's place (already translated). */
    void toast(String text);
    void openRecents();
    /** QuickPanelActivity with EXTRA_ANCHOR "end". */
    void openControlCenter();
    /** TopShelfView gives focus back: up = to the status row, else to the Dock. */
    void exitShelf(boolean up);
    /** The spoken items or their texts changed. */
    void a11yChanged();
    /** A caught fault in Início (§3.9-1): log the class name only, swap to Clássico once. */
    void fault(String tag, RuntimeException e);
}
