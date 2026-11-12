package io.freemytv;

import android.content.Context;
import android.os.Handler;

import java.util.List;
import java.util.function.Consumer;

/** What the page files can ask of the settings screen (implemented by SetView). Every call is on the UI thread. */
interface SetHost {
    Context context();
    SettingsActivity activity();
    Handler handler();
    SetState state();
    boolean running();
    void push(String pageId);                 // via SetPages.build
    void push(String pageId, SetPage.Factory f);   // dynamic pages (pickers, cities, albums)
    void pop();
    void refresh();                           // re-run onRefresh/why of the top page, keep focus by id, invalidate
    void focus(String rowId);
    void toast(String text, boolean warning);
    void confirm(String title, List<String> body, String action, boolean destructive, boolean focusAction,
                 Runnable yes);
    void askText(String title, String hint, String action, boolean secret, String initial,
                 Consumer<String> done);   // done on the UI thread; the sheet then closes
    void announce(String text);
    void preview();                           // activity.showPreview()
    void shake();                             // the unavailable shake on the focused row
    void fault(String tag, RuntimeException e);
}
