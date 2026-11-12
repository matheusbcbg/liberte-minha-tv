package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick settings panel a TCL remote's Settings button can open in Free My TV mode (a Remote Buttons choice; TV
 * Settings is the default), over whatever is playing (Google TV's
 * own panel lives in its home app, which is off in our mode): the time, and tiles for the inputs, the TV's picture and
 * sound panels, network, remotes and accessories, accessibility and all settings. Each tile opens the TV's own screen;
 * Inputs lists them right here. Back, or the Settings button again, closes it.
 * Início's Control Center is this panel too, at the top right under the status row (EXTRA_ANCHOR "end", always the
 * night look): the weather and forecast under the clock, the sleep timer, Recent Apps, Home Settings and the Home
 * Assistant tiles join the TV's own screens.
 * Drawn as liquid glass: TVs turn window blur off (and video plays on its own plane, under any blur), so the glass is
 * painted, nearly opaque so it reads over anything: the home screen's look (white with dark ink by day, smoked with
 * white ink at night, Settings > Theme), faint light inside, bright rims, and one focus lens that flows between the tiles.
 */
public class QuickPanelActivity extends Activity {
    /** A tile: its label, icon, accent color and the screens it may open, the first one the TV has wins. */
    private static final class Tile {
        final String label, symbol;
        final int color, kind;
        final Intent[] intents;
        /** A Home Assistant tile's item (its control). */
        HomeApps.Item item;

        Tile(String label, String symbol, int color, Intent... intents) {
            this(K_INTENT, label, symbol, color, intents);
        }

        Tile(int kind, String label, String symbol, int color, Intent... intents) {
            this.kind = kind;
            this.label = label;
            this.symbol = symbol;
            this.color = color;
            this.intents = intents;
        }
    }

    /** What a tile does: open its screens, list the inputs, step the sleep timer, Recent Apps, Home Settings, Home Assistant. */
    private static final int K_INTENT = 0, K_INPUTS = 1, K_SLEEP = 2, K_RECENTS = 3, K_HOME_SETTINGS = 4, K_HOME_ASSISTANT = 5;

    /** "end": the panel opens at the top right, under the Início status row. */
    static final String EXTRA_ANCHOR = "io.freemytv.QUICK_ANCHOR";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    /** The panel in a 1920×1080 space: its size and the margin to the screen's corner (the Control Center's own). */
    private static final float W = 600, H = 800, MARGIN = 40, END_H = 880, END_X = 80, END_Y = 132;

    private PanelView view;
    private Click click;
    private boolean end;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        click = new Click(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float k = dm.widthPixels / 1920f;
        Intent start = getIntent();
        end = start != null && "end".equals(start.getStringExtra(EXTRA_ANCHOR));
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.gravity = Gravity.TOP | (end ? Gravity.END : Gravity.START);
        lp.x = Math.round((end ? END_X : MARGIN) * k);
        lp.y = Math.round((end ? END_Y : MARGIN) * k);
        lp.width = Math.round(W * k);
        lp.height = Math.round((end ? END_H : H) * k);
        getWindow().setAttributes(lp);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        overridePendingTransition(0, 0);   // the panel springs in by itself
        view = new PanelView(this, k, tiles(), end);
        setContentView(view);
        loadHomeAssistant();
    }

    /** Home Assistant's tiles, when it's on: read off the main thread (they read the token file), then added. */
    private void loadHomeAssistant() {
        if (!HomeAssistant.offered()) return;
        final Context app = getApplicationContext();
        Safe.IO.execute(Safe.wrap("quick panel ha", () -> {
            final List<HomeApps.Item> items = HomeAssistant.items(app);
            if (items.isEmpty()) return;
            Safe.post(main, "quick panel ha", () -> {
                if (isFinishing() || isDestroyed() || view == null) return;
                List<Tile> more = new ArrayList<>();
                for (HomeApps.Item it : items) {
                    if (it == null || it.control == null) continue;
                    Tile t = new Tile(K_HOME_ASSISTANT, it.label == null ? "" : it.label, it.symbol != null ? it.symbol : "home", 0xFF64D2FF);
                    t.item = it;
                    more.add(t);
                }
                view.addTiles(more);
            });
        }));
    }

    private List<Tile> tiles() {
        List<Tile> out = new ArrayList<>();
        out.add(new Tile(K_INPUTS, Ui.tr("Inputs"), "hdmi", 0xFF30B0C7));
        out.add(new Tile(K_SLEEP, Ui.tr("Sleep Timer"), "bedtime", 0xFF5E5CE6));
        java.util.Collections.addAll(out,
            new Tile(Ui.tr("Display"), "tv_settings", 0xFFFF9F0A,
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_DISPLAY_SETTINGS)),
            new Tile(Ui.tr("Sound"), "speaker", 0xFFFF375F,
                    new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_SOUND_SETTINGS)),
            new Tile(Ui.tr("Network"), "wifi", 0xFF0A84FF, new Intent(Settings.ACTION_WIFI_SETTINGS)),
            // Remotes & Accessories (Bluetooth): Google TV and TCL answer ACCESSORIES_CONNECTED_DEVICES; on TCL the
            // older DEVICES action is Device Preferences, so it only comes last.
            new Tile(Ui.tr("Bluetooth"), "bluetooth", 0xFF3F7CFF,
                    new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES").setPackage(TV_SETTINGS),
                    new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                    new Intent("com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"),
                    new Intent("com.android.tv.settings.DEVICES").setPackage(TV_SETTINGS)),
            new Tile(Ui.tr("Accessibility"), "accessibility", 0xFF30D158, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        if (!"off".equals(Prefs.recents())) out.add(new Tile(K_RECENTS, Ui.tr("Recent Apps"), "recents", 0xFFFF9F0A));
        out.add(new Tile(K_HOME_SETTINGS, Ui.tr("Home Settings"), "sparkles", 0xFF0A84FF));
        out.add(new Tile(Ui.tr("TV Settings"), "settings", 0xFF8E8E9A, new Intent(Settings.ACTION_SETTINGS)));
        return out;
    }

    /** The Settings button again (RemoteKeys starts this activity a second time): closes the panel. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        view.close();
    }

    /** Leaving for another app or the home screen closes it, like Google TV's panel. */
    @Override protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override protected void onDestroy() {
        click.release();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean open(Intent... intents) {
        for (Intent intent : intents) {
            try {
                startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("quick panel: couldn't open " + intent.getAction() + ": " + e.getClass().getSimpleName());
            }
        }
        return false;
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }


    private final class PanelView extends View implements HomeData.Listener {
        private static final float PAD = 40, GAP = 12, TILE_H = 112, GRID_TOP = 184, ROW_H = 76, ROW_GAP = 10,
                CARD_R = 46, TILE_R = 28, CHIP_R = 25, SYMBOL = 24, CELL_W = 104;
        /** TalkBack's ids: a tile is its index + 1, an Inputs row ROW_IDS + its index + 1. */
        private static final int ROW_IDS = 1000;

        private final float k;
        private final boolean end;
        private final List<Tile> tiles;
        private List<HomeApps.Item> inputs;   // non-null: the Inputs list is showing (or fading out)
        private boolean inputsOpen, closing;
        private int focus, inputFocus;

        // Motion: the panel coming in, the focus lens (position and size), a press, the grid / Inputs list swap, the
        // grid's scroll (more tiles than fit under the weather).
        private final Spring show = new Spring(0.5f, 0.78f, 0);
        private final Spring lensX = new Spring(0.36f, 0.72f, 0), lensY = new Spring(0.36f, 0.72f, 0);
        private final Spring lensW = new Spring(0.36f, 0.8f, 0), lensH = new Spring(0.36f, 0.8f, 0);
        private final Spring press = new Spring(0.22f, 0.6f, 0);
        private final Spring page = new Spring(0.4f, 0.9f, 0);
        private final Spring scroll = new Spring(0.36f, 0.9f, 0);
        private long lastFrame;

        // The home screen's look: white glass with dark ink by day, smoked glass with white ink at night (Início: always).
        private final boolean night;
        private final int ink, focusInk, tileFill;
        private final String inputsTitle = Ui.tr("Inputs"), noInputs = Ui.tr("No inputs");
        private String dateText = "", clockText = "";
        private float clockBearing;
        private final Spoken spoken;
        private final HomeData data;

        private final Paint date, clock, label, rowLabel, title, empty, value;
        // The weather block (only with Prefs.weather() and data), then the quotes line (Prefs.quotes()).
        private final Paint city, temp, condition, dim, cellLabel, cellTemp, cellLow, quotesPaint;
        private final TextPaint quotesText;
        private boolean weatherShown, days;
        private String cityText = "", tempText = "", conditionText = "", highLowText = "", weatherSymbol, quotesLine;
        private float tempBearing, conditionX;
        private final String[] cellText = new String[5], cellTempText = new String[5], cellLowText = new String[5], cellSymbol = new String[5];
        private final float[] cellTempX = new float[5], cellLowX = new float[5];
        private int cells;
        private float gridTop = GRID_TOP, gridBottom;
        private String sleepValue = "";

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint layer = new Paint(), atop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF(), card = new RectF();
        private LinearGradient tint, sheen, cardRim, tileRim, lens;
        private RadialGradient[] glows;
        private final float[] glowX = {0.2f, 0.95f, 0.3f}, glowY = {0.15f, 0.55f, 0.95f};
        // Each chip color's gradient, made once and looked up by color (no boxing in onDraw).
        private final int[] chipColor = new int[16];
        private final LinearGradient[] chipShader = new LinearGradient[16];
        private int chips;
        private float[] tileX = new float[0], tileY = new float[0], labelSize = new float[0];   // labelSize: shrunk to fit long words
        private float tileW;

        private final Runnable frame = new Runnable() {
            @Override public void run() {
                invalidate();
            }
        };
        /** Once a minute: the date and time (and the sleep timer's value), formatted here rather than every frame. */
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                dateText = Ui.date(now);
                clockText = Ui.clock(now);
                clockBearing = Ui.leftBearing(clock, clockText);
                sleepValue();
                invalidate();
                postDelayed(this, 60_000 - now % 60_000);
            }
        };

        PanelView(Context c, float k, List<Tile> tiles, boolean end) {
            super(c);
            this.k = k;
            this.end = end;
            this.tiles = tiles;
            data = HomeData.get(c);
            night = end || data.isNight(System.currentTimeMillis());
            ink = night ? 0xFFFFFFFF : 0xFF1D1D1F;
            focusInk = night ? 0xFF1D1D1F : 0xFFFFFFFF;
            tileFill = night ? 0x17FFFFFF : 0x0D000000;
            date = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.8f));
            clock = Ui.numbers(Ui.displaySemibold, 80 * k, ink);
            label = Ui.text(Ui.semibold, 24 * k, ink);
            rowLabel = Ui.text(Ui.semibold, 26 * k, ink);
            title = Ui.text(Ui.semibold, 30 * k, ink);
            empty = Ui.text(Ui.medium, 24 * k, Ui.alpha(ink, 0.7f));
            value = Ui.numbers(Ui.medium, 22 * k, ink);
            value.setTextAlign(Paint.Align.RIGHT);
            city = Ui.text(Ui.semibold, 24 * k, Ui.alpha(ink, 0.7f));
            temp = Ui.numbers(Ui.light, 72 * k, ink);
            condition = Ui.text(Ui.medium, 24 * k, ink);
            dim = Ui.text(Ui.medium, 22 * k, Ui.alpha(ink, 0.7f));
            cellLabel = Ui.text(Ui.medium, 20 * k, Ui.alpha(ink, 0.7f));
            cellLabel.setTextAlign(Paint.Align.CENTER);
            cellTemp = Ui.numbers(Ui.medium, 22 * k, ink);
            cellLow = Ui.numbers(Ui.medium, 22 * k, Ui.alpha(ink, 0.6f));
            quotesPaint = Ui.numbers(Ui.regular, 22 * k, Ui.alpha(ink, 0.7f));
            quotesText = new TextPaint(quotesPaint);
            rim.setStyle(Paint.Style.STROKE);
            setFocusable(true);
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    if (!inputsOpen) {
                        for (int i = 0; i < tiles.size(); i++) out.add(i + 1);
                        return;
                    }
                    int first = firstRow();
                    for (int i = first; i < Math.min(inputs.size(), first + rowsVisible()); i++) out.add(ROW_IDS + i + 1);
                }
                @Override public String text(int id) {
                    if (id <= ROW_IDS) {
                        if (id - 1 >= tiles.size()) return "";
                        Tile t = tiles.get(id - 1);
                        return t.kind == K_SLEEP ? t.label + ", " + sleepValue : t.label;
                    }
                    int i = id - ROW_IDS - 1;
                    if (inputs == null || i >= inputs.size()) return "";
                    return inputs.get(i).label + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", inputs.size());
                }
                @Override public boolean bounds(int id, Rect out) {
                    if (id <= ROW_IDS) {
                        int i = id - 1;
                        if (inputsOpen || i >= tiles.size() || i >= tileX.length) return false;
                        float y = tileY[i] - scroll.value;
                        if (y + TILE_H * k <= gridTop || y >= gridBottom) return false;
                        out.set(Math.round(tileX[i]), Math.round(y), Math.round(tileX[i] + tileW), Math.round(y + TILE_H * k));
                        return true;
                    }
                    int i = id - ROW_IDS - 1;
                    if (!inputsOpen || i >= inputs.size()) return false;
                    float y = rowTop(i);
                    out.set(Math.round(PAD * k), Math.round(y), Math.round(getWidth() - PAD * k), Math.round(y + ROW_H * k));
                    return true;
                }
                @Override public int focused() { return inputsOpen ? ROW_IDS + inputFocus + 1 : focus + 1; }
                @Override public void focus(int id) {
                    if (id > ROW_IDS) inputFocus = id - ROW_IDS - 1;
                    else focus = Math.max(0, Math.min(id - 1, tiles.size() - 1));
                    aimLens(false);
                    invalidate();
                }
                @Override public void click(int id) { select(); }
            });
            atop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
            for (Tile t : tiles) Symbols.warm(c, t.symbol, SYMBOL * k);
            Symbols.warm(c, "tv", SYMBOL * k);
            sleepValue();
            refreshWeather();
            show.target = 1;
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** Home Assistant's tiles came in: added after the others. */
        void addTiles(List<Tile> more) {
            if (more == null || more.isEmpty()) return;
            for (Tile t : more) Symbols.warm(getContext(), t.symbol, SYMBOL * k);
            tiles.addAll(more);
            layoutTiles();
            aimLens(true);
            invalidate();
            spoken.focusMoved();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            card.set(0, 0, w, h);
            // ~95% opaque: with no blur behind it, the glass has to hold the text on its own, over a white or black screen.
            tint = night ? new LinearGradient(0, 0, 0, h, 0xF22C2C30, 0xF71C1C1E, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, h, 0xF7FBFBFD, 0xF2EBEBF0, Shader.TileMode.CLAMP);
            sheen = new LinearGradient(0, 0, 0, h * 0.42f, night ? 0x1FFFFFFF : 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            cardRim = new LinearGradient(0, 0, w, h, new int[] {0xB3FFFFFF, 0x1AFFFFFF, 0x14FFFFFF, 0x66FFFFFF},
                    new float[] {0, 0.35f, 0.65f, 1}, Shader.TileMode.CLAMP);
            tileRim = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0x40FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0x0A000000, 0x1A000000, Shader.TileMode.CLAMP);
            lens = night ? new LinearGradient(0, 0, 0, TILE_H * k, 0xFFFFFFFF, 0xFFE4E8F4, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, 0, TILE_H * k, 0xFF3A3A3C, 0xFF1D1D1F, Shader.TileMode.CLAMP);
            // Faint light resting in the glass: it doesn't drift, the panel draws only while something moves.
            float r = 380 * k;
            glows = new RadialGradient[] {
                new RadialGradient(0, 0, r, 0x267A4DFF, 0x007A4DFF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r, 0x2022C4FF, 0x0022C4FF, Shader.TileMode.CLAMP),
                new RadialGradient(0, 0, r * 0.85f, 0x1AFF3D8B, 0x00FF3D8B, Shader.TileMode.CLAMP),
            };
            layoutTiles();
            aimLens(true);
        }

        /** The tiles in two columns from gridTop (under the clock, or under the weather); they scroll past gridBottom. */
        private void layoutTiles() {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            gridBottom = h - PAD * k * 0.75f;
            int n = tiles.size();
            if (tileX.length != n) {
                tileX = new float[n];
                tileY = new float[n];
                labelSize = new float[n];
            }
            tileW = (w - 2 * PAD * k - GAP * k) / 2;
            float room = tileW - 40 * k;
            for (int i = 0; i < n; i++) {
                label.setTextSize(24 * k);
                labelSize[i] = 24 * k * Math.min(1, room / Math.max(1, label.measureText(tiles.get(i).label)));
            }
            label.setTextSize(24 * k);
            for (int i = 0; i < n; i++) {
                tileX[i] = PAD * k + (i % 2) * (tileW + GAP * k);
                tileY[i] = gridTop + (i / 2) * (TILE_H + GAP) * k;
            }
            focus = Math.max(0, Math.min(focus, n - 1));
        }

        /** The weather block's texts and positions (on open, on new data, once a minute): never made while drawing. */
        private void refreshWeather() {
            HomeData.Weather wx = data.weather;
            weatherShown = Prefs.weather() && wx != null && !Double.isNaN(wx.temp);
            float y = GRID_TOP * k;
            if (weatherShown) {
                cityText = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
                tempText = HomeData.degrees(wx.temp);
                tempBearing = Ui.leftBearing(temp, tempText);
                conditionText = HomeData.condition(wx.code, wx.isDay);
                highLowText = HomeData.highLow(wx, "  ");
                weatherSymbol = Symbols.weather(wx.code, wx.isDay);
                Symbols.warm(getContext(), weatherSymbol, 56 * k);
                conditionX = (PAD + 76) * k + temp.measureText(tempText) + 18 * k;
                days = Prefs.forecast().equals("days") && !wx.days.isEmpty();
                cells = Math.min(5, days ? wx.days.size() : wx.hours.size());
                for (int i = 0; i < cells; i++) {
                    float cx = PAD * k + CELL_W * k * (i + 0.5f);
                    if (days) {
                        cellText[i] = HomeData.weekday(wx.days.get(i));
                        cellTempText[i] = HomeData.degrees(wx.dayMax.get(i));
                        cellLowText[i] = HomeData.degrees(wx.dayMin.get(i));
                        cellSymbol[i] = Symbols.weather(wx.dayCode.get(i), true);
                        float mw = cellTemp.measureText(cellTempText[i]), lw = cellLow.measureText(cellLowText[i]), gap = 6 * k;
                        cellTempX[i] = cx - (mw + gap + lw) / 2;
                        cellLowX[i] = cellTempX[i] + mw + gap;
                    } else {
                        cellText[i] = HomeData.hour(wx.hours.get(i));
                        cellTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
                        cellLowText[i] = null;
                        cellSymbol[i] = Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
                        cellTempX[i] = cx - cellTemp.measureText(cellTempText[i]) / 2;
                    }
                    Symbols.warm(getContext(), cellSymbol[i], 30 * k);
                }
                y = (GRID_TOP + 220) * k;
            }
            quotesLine = null;
            if (Prefs.quotes()) {
                int[] coins = HomeData.active();
                StringBuilder b = new StringBuilder();
                for (int i : coins) {
                    if (Double.isNaN(data.prices[i])) continue;
                    if (b.length() > 0) b.append("   ");
                    b.append(HomeData.COINS[i]).append(' ').append(HomeData.brl(data.prices[i]));
                    String ch = HomeData.change(data.changes[i]);
                    if (!ch.isEmpty()) b.append(' ').append(ch);
                }
                if (b.length() > 0) {
                    float w = (W - 2 * PAD) * k;
                    quotesLine = TextUtils.ellipsize(b.toString(), quotesText, w, TextUtils.TruncateAt.END).toString();
                    y += 36 * k;
                }
            }
            float top = weatherShown || quotesLine != null ? y + 24 * k : GRID_TOP * k;
            if (top != gridTop) {
                gridTop = top;
                layoutTiles();
                aimLens(true);
            }
        }

        private void sleepValue() {
            int left = SleepTimer.minutesLeft(getContext());
            sleepValue = left > 0 ? SleepTimer.duration(left) : Ui.tr("Off");
        }

        @Override public void onData() {
            refreshWeather();
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            tick.run();
            data.addListener(this);
            data.refreshIfStale();
            spoken.readAgain();
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            removeCallbacks(frame);
            data.removeListener(this);
            super.onDetachedFromWindow();
        }

        void close() {
            if (closing) return;
            closing = true;
            show.target = 0;
            invalidate();
        }

        /** Points the lens at the focused tile (content coordinates), or the focused row of the Inputs list. */
        private void aimLens(boolean snap) {
            if (!inputsOpen && focus >= tileX.length) return;
            float x, y, w, h;
            if (inputsOpen) {
                x = PAD * k;
                y = rowTop(inputFocus);
                w = getWidth() - 2 * PAD * k;
                h = ROW_H * k;
            } else {
                x = tileX[focus];
                y = tileY[focus];
                w = tileW;
                h = TILE_H * k;
                // The grid scrolls so the focused row is whole between gridTop and gridBottom.
                float want = scroll.target;
                if (y - want < gridTop) want = y - gridTop;
                if (y + h - want > gridBottom) want = y + h - gridBottom;
                scroll.target = Math.max(0, want);
                if (snap) scroll.snap(scroll.target);
            }
            if (snap) {
                lensX.snap(x);
                lensY.snap(y);
                lensW.snap(w);
                lensH.snap(h);
            } else {
                lensX.target = x;
                lensY.target = y;
                lensW.target = w;
                lensH.target = h;
            }
        }

        private int rowsVisible() {
            return Math.max(1, (int) ((gridBottom - (gridTop + 58 * k)) / ((ROW_H + ROW_GAP) * k)));
        }

        /** The first Inputs row on screen: the list scrolls to keep the focused one in view. */
        private int firstRow() {
            int rows = rowsVisible();
            return Math.max(0, Math.min(inputFocus - rows + 1, inputs == null ? 0 : inputs.size() - rows));
        }

        private float rowTop(int i) {
            return gridTop + 58 * k + (i - firstRow()) * (ROW_H + ROW_GAP) * k;
        }

        @Override protected void onDraw(Canvas c) {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = show.step(dt);
            moving |= lensX.step(dt) | lensY.step(dt) | lensW.step(dt) | lensH.step(dt);
            moving |= press.step(dt) | page.step(dt) | scroll.step(dt);
            if (closing && show.value < 0.03f) {
                finish();
                return;
            }
            if (!inputsOpen && page.value < 0.01f) inputs = null;

            float s = Math.max(0, show.value);
            c.save();
            // In from its own edge: the left corner's panel from the left, the Control Center from the right.
            float away = (1 - s) * (getWidth() + (end ? END_X : MARGIN) * k);
            c.translate(end ? away : -away, 0);
            int alpha = Math.round(255 * Math.min(1, s * 1.4f));
            c.saveLayerAlpha(card, alpha);
            drawGlass(c);
            drawHeader(c);
            float p = page.value;
            if (p < 0.999f) drawTiles(c, 1 - p);
            if (inputs != null && p > 0.001f) drawInputs(c, p);
            c.restore();
            c.restore();
            if (moving) {
                postOnAnimation(frame);
            } else {
                lastFrame = 0;   // at rest: the next move starts from one frame, not from the time spent still
            }
        }

        /** Tinted glass with faint light inside, a sheen at the top and a bright rim. */
        private void drawGlass(Canvas c) {
            float r = CARD_R * k;
            c.saveLayer(card, layer);
            fill.setAlpha(255);   // a shader is still scaled by the paint's alpha, which the last tile left low
            fill.setShader(tint);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            float w = card.width(), h = card.height();
            for (int i = 0; i < glows.length; i++) {
                c.save();
                c.translate(glowX[i] * w, glowY[i] * h);
                atop.setShader(glows[i]);
                c.drawCircle(0, 0, 400 * k, atop);
                c.restore();
            }
            atop.setShader(null);
            c.restore();
            fill.setShader(sheen);
            c.drawRoundRect(card, r, r, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.6f * k);
            rim.setShader(cardRim);
            rect.set(card);
            rect.inset(0.8f * k, 0.8f * k);
            c.drawRoundRect(rect, r, r, rim);
            rim.setShader(null);
        }

        private void drawHeader(Canvas c) {
            c.drawText(dateText, PAD * k, (PAD + 24) * k, date);
            c.drawText(clockText, PAD * k - clockBearing, (PAD + 116) * k, clock);
            float y = GRID_TOP * k;
            if (weatherShown) {
                float x = PAD * k;
                c.drawText(cityText, x, y + 20 * k, city);
                Symbols.draw(getContext(), c, weatherSymbol, x + 28 * k, y + 72 * k, 56 * k, ink);
                c.drawText(tempText, x + 76 * k - tempBearing, y + 98 * k, temp);
                c.drawText(conditionText, conditionX, y + 62 * k, condition);
                c.drawText(highLowText, conditionX, y + 96 * k, dim);
                for (int i = 0; i < cells; i++) {
                    float cx = x + CELL_W * k * (i + 0.5f);
                    c.drawText(cellText[i], cx, y + 140 * k, cellLabel);
                    Symbols.draw(getContext(), c, cellSymbol[i], cx, y + 168 * k, 30 * k, Ui.alpha(ink, 0.9f));
                    c.drawText(cellTempText[i], cellTempX[i], y + 206 * k, cellTemp);
                    if (cellLowText[i] != null) c.drawText(cellLowText[i], cellLowX[i], y + 206 * k, cellLow);
                }
                y += 220 * k;
            }
            if (quotesLine != null) c.drawText(quotesLine, PAD * k, y + 26 * k, quotesPaint);
        }

        /** The grid: glass tiles, the lens under the focused one, then each tile's colored chip and label (and value). */
        private void drawTiles(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.clipRect(0, gridTop - 12 * k, getWidth(), gridBottom + 8 * k);
            c.translate(0, (1 - a) * -24 * k - scroll.value);
            float r = TILE_R * k, top = gridTop - 24 * k + scroll.value, bottom = gridBottom + 24 * k + scroll.value;
            int n = Math.min(tiles.size(), tileX.length);
            for (int i = 0; i < n; i++) {
                if (tileY[i] > bottom || tileY[i] + TILE_H * k < top) continue;
                rect.set(tileX[i], tileY[i], tileX[i] + tileW, tileY[i] + TILE_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (!inputsOpen) drawLens(c, r);
            for (int i = 0; i < n; i++) {
                if (tileY[i] > bottom || tileY[i] + TILE_H * k < top) continue;
                Tile t = tiles.get(i);
                float on = nearness(tileX[i], tileY[i], tileW);
                float lift = on * (1 - 0.04f * press.value);
                float cx = tileX[i] + tileW / 2, cy = tileY[i] + TILE_H * k / 2;
                c.save();
                c.scale(1 + 0.03f * lift, 1 + 0.03f * lift, cx, cy);
                float chipX = tileX[i] + (18 + CHIP_R) * k, chipY = tileY[i] + (16 + CHIP_R) * k;
                drawChip(c, chipX, chipY, t.color, t.symbol, on);
                int color = mix(ink, focusInk, on);
                label.setColor(color);
                label.setTextSize(labelSize[i]);
                c.drawText(t.label, tileX[i] + 20 * k, tileY[i] + TILE_H * k - 18 * k, label);
                if (t.kind == K_SLEEP) {
                    value.setColor(color);
                    c.drawText(sleepValue, tileX[i] + tileW - 20 * k, chipY + 8 * k, value);
                }
                c.restore();
            }
            label.setTextSize(24 * k);
            c.restoreToCount(saved);
        }

        /** The Inputs list: a title and one glass row per input, the lens on the focused one. */
        private void drawInputs(Canvas c, float a) {
            int saved = c.saveLayerAlpha(card, Math.round(255 * a));
            c.translate(0, (1 - a) * 24 * k);
            c.drawText(inputsTitle, PAD * k, gridTop + 30 * k, title);
            if (inputs.isEmpty()) {
                c.drawText(noInputs, PAD * k, gridTop + 100 * k, empty);
                c.restoreToCount(saved);
                return;
            }
            float r = 24 * k, w = getWidth() - 2 * PAD * k;
            int first = firstRow();
            int last = Math.min(inputs.size(), first + rowsVisible());
            for (int i = first; i < last; i++) {
                float y = rowTop(i);
                rect.set(PAD * k, y, PAD * k + w, y + ROW_H * k);
                fill.setColor(tileFill);
                c.drawRoundRect(rect, r, r, fill);
                drawRim(c, rect, r);
            }
            if (inputsOpen) drawLens(c, r);
            for (int i = first; i < last; i++) {
                HomeApps.Item it = inputs.get(i);
                float y = rowTop(i);
                float on = nearness(PAD * k, y, w);
                float dim = it.connected ? 1 : 0.7f;   // still 4.5:1 on the light glass
                drawChip(c, PAD * k + 46 * k, y + ROW_H * k / 2, it.kind == HomeApps.LIVE_TV ? 0xFFFF9F0A : 0xFF30B0C7,
                        it.symbol != null ? it.symbol : "hdmi", on);
                rowLabel.setColor(Ui.alpha(mix(ink, focusInk, on), Math.max(dim, on)));
                c.drawText(it.label, PAD * k + 92 * k, y + ROW_H * k / 2 + Ui.halfCap(rowLabel), rowLabel);
            }
            c.restoreToCount(saved);
        }

        /** How much the lens covers this tile (1 = resting on it): the label and chip follow it as it flows. */
        private float nearness(float x, float y, float w) {
            float d = Math.abs(lensX.value - x) + Math.abs(lensY.value - y);
            return Math.max(0, 1 - d / (w * 0.6f));
        }

        private void drawRim(Canvas c, RectF r, float radius) {
            c.save();
            c.translate(r.left, r.top);
            rim.setStrokeWidth(1.2f * k);
            rim.setShader(tileRim);
            rect.set(0.6f * k, 0.6f * k, r.width() - 0.6f * k, r.height() - 0.6f * k);
            c.drawRoundRect(rect, radius, radius, rim);
            rim.setShader(null);
            c.restore();
        }

        /**
         * The focus lens: a bright drop of glass that flows to the focused tile, stretching along its way like a
         * liquid and squashing a little when OK is pressed, with a soft glow of the tile's color around it.
         */
        private void drawLens(Canvas c, float radius) {
            float vx = Math.abs(lensX.velocity), vy = Math.abs(lensY.velocity);
            float stretchX = Math.min(0.16f, vx / (9000 * k)), stretchY = Math.min(0.16f, vy / (9000 * k));
            float w = lensW.value * (1 + stretchX - stretchY * 0.5f) * (1 - 0.04f * press.value);
            float h = lensH.value * (1 + stretchY - stretchX * 0.5f) * (1 - 0.04f * press.value);
            float cx = lensX.value + lensW.value / 2, cy = lensY.value + lensH.value / 2;
            int accent = inputsOpen || focus >= tiles.size() ? 0xFF30B0C7 : tiles.get(focus).color;
            for (int i = 3; i >= 1; i--) {
                float g = i * 7 * k;
                rect.set(cx - w / 2 - g, cy - h / 2 - g, cx + w / 2 + g, cy + h / 2 + g);
                fill.setColor(Ui.alpha(accent, 0.10f));
                c.drawRoundRect(rect, radius + g, radius + g, fill);
            }
            rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            c.save();
            c.translate(0, rect.top);
            fill.setAlpha(255);
            fill.setShader(lens);
            rect.offset(0, -rect.top);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            rim.setStrokeWidth(1.4f * k);
            rim.setColor(night ? 0xFFFFFFFF : 0x33000000);
            c.drawRoundRect(rect, radius, radius, rim);
            c.restore();
        }

        /** A circle in the tile's color (lighter at the top, like lit glass) with its white symbol. */
        private void drawChip(Canvas c, float cx, float cy, int color, String symbol, float on) {
            float r = CHIP_R * k;
            LinearGradient lit = null;
            for (int i = 0; i < chips && lit == null; i++) if (chipColor[i] == color) lit = chipShader[i];
            if (lit == null) {
                lit = new LinearGradient(0, -r, 0, r, mix(color, 0xFFFFFFFF, 0.28f), color, Shader.TileMode.CLAMP);
                if (chips < chipColor.length) {
                    chipColor[chips] = color;
                    chipShader[chips++] = lit;
                }
            }
            c.save();
            c.translate(cx, cy);
            fill.setAlpha(255);
            fill.setShader(lit);
            c.drawCircle(0, 0, r, fill);
            fill.setShader(null);
            c.restore();
            rim.setStrokeWidth(1f * k);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.35f + 0.3f * on));
            c.drawCircle(cx, cy, r - 0.5f * k, rim);
            Symbols.draw(getContext(), c, symbol, cx, cy, SYMBOL * k, 0xFFFFFFFF);
        }

        boolean onKey(KeyEvent e) {
            if (closing) return true;
            int code = e.getKeyCode();
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                    if (!down) {
                        if (inputsOpen) {
                            inputsOpen = false;
                            page.target = 0;
                            aimLens(false);
                            invalidate();
                            spoken.focusMoved();
                        } else {
                            close();
                        }
                    }
                    return true;
                case KeyEvent.KEYCODE_NOTIFICATION:
                    if (!down) close();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) move(code);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (down && e.getRepeatCount() == 0) {
                        press.target = 1;
                        invalidate();
                    } else if (!down) {
                        press.target = 0;
                        invalidate();
                        if (spoken.keyClick()) select();   // TalkBack's click may have come first for this press
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void move(int code) {
            int n = tiles.size();
            if (inputsOpen) {
                int before = inputFocus;
                if (code == KeyEvent.KEYCODE_DPAD_UP) inputFocus = Math.max(0, inputFocus - 1);
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) inputFocus = Math.min(inputs.size() - 1, inputFocus + 1);
                if (inputFocus == before) return;
            } else {
                int before = focus;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && focus % 2 == 1) focus--;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && focus % 2 == 0 && focus + 1 < n) focus++;
                if (code == KeyEvent.KEYCODE_DPAD_UP && focus >= 2) focus -= 2;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && focus + 2 < n) focus += 2;
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN && focus % 2 == 0 && focus + 1 < n && focus / 2 < (n - 1) / 2) focus++;
                if (focus == before) return;
            }
            click.move();
            aimLens(false);
            invalidate();
            spoken.focusMoved();
        }

        private void select() {
            if (inputsOpen) {
                click.open();
                if (inputs.isEmpty()) return;
                if (HomeApps.open(QuickPanelActivity.this, inputs.get(inputFocus), this, 0, 0, getWidth(), getHeight())) finish();
                return;
            }
            if (focus < 0 || focus >= tiles.size()) return;
            Tile t = tiles.get(focus);
            click.open();
            switch (t.kind) {
                case K_INPUTS:
                    inputs = HomeApps.allInputs(getContext());
                    inputsOpen = true;
                    inputFocus = 0;
                    page.target = 1;
                    aimLens(false);
                    invalidate();
                    spoken.focusMoved();
                    return;
                case K_SLEEP:
                    // The timer's own steps (the next 5 minutes up to 4 hours), then off.
                    SleepTimer.step(getContext(), 1);
                    sleepValue();
                    invalidate();
                    spoken.focusMoved();
                    return;
                case K_RECENTS:
                    try {
                        startActivity(new Intent(QuickPanelActivity.this, HomeActivity.class)
                                .putExtra(HomeActivity.EXTRA_RECENTS, true)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        finish();
                    } catch (ActivityNotFoundException | SecurityException e) {
                        AppLog.w("quick panel: recents: " + e.getClass().getSimpleName());
                    }
                    return;
                case K_HOME_SETTINGS:
                    if (HomeApps.open(QuickPanelActivity.this, HomeApps.settings(), this, 0, 0, getWidth(), getHeight())) finish();
                    return;
                case K_HOME_ASSISTANT:
                    if (t.item != null && t.item.control != null) HomeAssistant.activate(getContext(), t.item.control, ok -> invalidate());
                    return;
                default:
                    if (open(t.intents)) finish();
            }
        }
    }
}
