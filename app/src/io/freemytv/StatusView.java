package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Início's status row, top right, never scrolled: right to left, the round Control Center button, the clock (its box
 * reserved for "88:88", so nothing moves when the minute changes), the weather, the date, then only when active the
 * sleep timer, what's playing and the status icons. Every text is made and measured on the minute tick or when its
 * data changes (update), never while drawing. A View at Pos(900, 40, 980, 96): x 900..1880, y 40..136.
 */
final class StatusView extends View {
    // Local coordinates (the 1920×1080 layout minus the view's origin, times k).
    private static final float LEFT = 900, TOP = 40, CY = 48, BUTTON_CX = 908, BUTTON_R = 32, CLOCK_RIGHT = 852, GAP = 28,
            SYMBOL_CC = 28, SYMBOL_WEATHER = 30, SYMBOL_SLEEP = 26, ICON = 25, ICON_GAP = 22, PLAYING_W = 260;

    private final StartHost host;
    private final HomeData data;
    private final float k;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint clock, meta, small, fill = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            bars = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallText;
    private final float clockHalf, metaHalf, smallHalf, smallCap;
    private final String ccSymbol;
    private final LinearGradient rimShader;
    private final RectF rect = new RectF();
    private final Spring focus = new Spring(0.26f, 0.85f, 0);

    // What's drawn, made in update() (and setPlaying): texts, their widths and where they start (local x).
    private String clockText = "", dateText = "", tempText, weatherSymbol, sleepText, playingTitle, playingText;
    private float clockW, dateX, tempX, weatherSymbolX, sleepX, sleepSymbolX, playingX, barsX;
    private final String[] icons = new String[3];
    private final float[] iconX = new float[3], iconSize = new float[3];
    private int iconCount;
    private boolean overPhoto, resumed, focused;
    private int textGen = -1;

    // Status icons (Prefs.statusIcons): Wi-Fi or Ethernet, Bluetooth audio, a mounted drive. Listened to only while on.
    private static final ThreadPoolExecutor DEVICES = Safe.pool("home-status", 1);
    private final ConnectivityManager connectivity;
    private final BluetoothAdapter bluetooth;
    private int network;
    private boolean bluetoothConnected, usbConnected, networkRegistered, devicesRegistered, mediaRegistered;

    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            try {
                update();
            } catch (RuntimeException e) {
                host.fault("status tick", e);
                return;
            }
            schedule();
        }
    };

    StatusView(Context c, StartHost h, HomeData data) {
        super(c);
        host = h;
        this.data = data;
        k = ExploreView.scaleOf(c);
        clock = Ui.numbers(Ui.displayMedium, 40 * k, CardPainter.INK);
        clock.setTextAlign(Paint.Align.RIGHT);
        meta = Ui.numbers(Ui.medium, 28 * k, CardPainter.META);
        small = Ui.text(Ui.medium, 24 * k, CardPainter.META);
        smallText = new TextPaint(small);
        bars.setColor(CardPainter.META);
        clockHalf = Ui.halfCap(clock);
        metaHalf = Ui.halfCap(meta);
        smallCap = Ui.cap(small);
        smallHalf = smallCap / 2;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
        rimShader = new LinearGradient(0, -BUTTON_R * k, 0, BUTTON_R * k, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        rim.setShader(rimShader);
        ccSymbol = Symbols.get(c, "toggle") != null ? "toggle" : "settings";
        Symbols.warm(c, ccSymbol, SYMBOL_CC * k);
        Symbols.warm(c, ccSymbol, SYMBOL_CC * k * 1.10f);
        Symbols.warm(c, "bedtime", SYMBOL_SLEEP * k);
        ConnectivityManager cm = null;
        try {
            cm = c.getSystemService(ConnectivityManager.class);
        } catch (RuntimeException ignored) {
        }
        connectivity = cm;
        BluetoothAdapter bt = null;
        try {
            bt = BluetoothAdapter.getDefaultAdapter();
        } catch (RuntimeException ignored) {
        }
        bluetooth = bt;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        update();
    }

    @Override public boolean hasOverlappingRendering() {
        return false;
    }

    void setFocused(boolean f) {
        if (f == focused) return;
        focused = f;
        focus.target = f ? 1 : 0;
        host.wake();
    }

    /** The Control Center button on screen, at its resting size. */
    boolean buttonRect(RectF out) {
        float left = getLeft() + getTranslationX(), top = getTop() + getTranslationY();
        if (getWidth() == 0) {
            left = LEFT * k;
            top = TOP * k;
        }
        out.set(left + (BUTTON_CX - BUTTON_R) * k, top + (CY - BUTTON_R) * k, left + (BUTTON_CX + BUTTON_R) * k,
                top + (CY + BUTTON_R) * k);
        return true;
    }

    /** "Central de controle, 14:32" (made on focus changes by StartView, not in draw). */
    String a11yText() {
        return Ui.trf("Control Center, {time}", "time", clockText);
    }

    /** Clock, date, weather, sleep and status icons: the texts again, measured, then one redraw. Main thread. */
    void update() {
        long now = System.currentTimeMillis();
        if (textGen != Ui.generation) {
            textGen = Ui.generation;
            if (playingTitle != null) fitPlaying();
        }
        clockText = Ui.clock(now);
        dateText = Ui.datePattern("format|EEE, MMM d").format(now);
        HomeData.Weather wx = data == null ? null : data.weather;
        if (Prefs.weather() && wx != null && !Double.isNaN(wx.temp)) {
            tempText = HomeData.degrees(wx.temp);
            String sym = Symbols.weather(wx.code, wx.isDay);
            if (!sym.equals(weatherSymbol)) Symbols.warm(getContext(), sym, SYMBOL_WEATHER * k);
            weatherSymbol = sym;
        } else {
            tempText = null;
            weatherSymbol = null;
        }
        int left = SleepTimer.minutesLeft(getContext());
        sleepText = left > 0 ? Ui.trf("Off in {time}", "time", SleepTimer.duration(left)) : null;
        layoutRow();
        invalidate();
    }

    /** What's playing (null: nothing): the title is cut to 260 once, here. */
    void setPlaying(String title) {
        if (title != null && title.isEmpty()) title = null;
        if (title == null ? playingTitle == null : title.equals(playingTitle)) return;
        playingTitle = title;
        fitPlaying();
        layoutRow();
        invalidate();
    }

    private void fitPlaying() {
        playingText = playingTitle == null ? null
                : TextUtils.ellipsize(playingTitle, smallText, PLAYING_W * k, TextUtils.TruncateAt.END).toString();
    }

    /** Text over a photo gets a soft shadow (set when this changes, never while drawing). */
    void setOverPhoto(boolean over) {
        if (over == overPhoto) return;
        overPhoto = over;
        for (Paint p : new Paint[] {clock, meta, small}) {
            if (over) p.setShadowLayer(6 * k, 0, 1 * k, 0x59000000);
            else p.clearShadowLayer();
        }
        invalidate();
    }

    /** Right to left from the clock: where each piece starts; the ones that don't fit are left out. */
    private void layoutRow() {
        float reserve = clock.measureText(Ui.h24() ? "88:88" : "88:88 PM");
        clockW = Math.max(reserve, clock.measureText(clockText));
        float x = CLOCK_RIGHT * k - clockW - GAP * k;
        if (tempText != null) {
            float tw = meta.measureText(tempText), sw = Symbols.width(getContext(), weatherSymbol, SYMBOL_WEATHER * k);
            tempX = x - tw;
            weatherSymbolX = tempX - 8 * k - sw / 2;
            x = tempX - 8 * k - sw - GAP * k;
        }
        float dw = meta.measureText(dateText);
        dateX = x - dw;
        x = dateX - GAP * k;
        sleepX = -1;
        if (sleepText != null) {
            float tw = small.measureText(sleepText), sw = Symbols.width(getContext(), "bedtime", SYMBOL_SLEEP * k);
            if (x - tw - 8 * k - sw >= 0) {
                sleepX = x - tw;
                sleepSymbolX = sleepX - 8 * k - sw / 2;
                x = sleepX - 8 * k - sw - GAP * k;
            }
        }
        playingX = -1;
        if (playingText != null && Prefs.nowPlaying()) {
            float tw = small.measureText(playingText), bw = NowPlaying.barsWidth(smallCap);
            if (x - tw - 10 * k - bw >= 0) {
                playingX = x - tw;
                barsX = playingX - 10 * k - bw;
                x = barsX - GAP * k;
            }
        }
        iconCount = 0;
        if (Prefs.statusIcons()) {
            String[] want = {usbConnected ? "usb" : null, bluetoothConnected ? "bluetooth" : null,
                    network == 2 ? "ethernet" : network == 1 ? "wifi" : null};
            for (String name : want) {
                if (name == null) continue;
                float size = Symbols.pointSizeForHeight(getContext(), name, ICON * k * iconScale(name));
                float w = Symbols.width(getContext(), name, size);
                if (w <= 0 || x - w < 0) continue;
                Symbols.warm(getContext(), name, size);
                icons[iconCount] = name;
                iconSize[iconCount] = size;
                iconX[iconCount] = x - w / 2;
                iconCount++;
                x -= w + ICON_GAP * k;
            }
        }
    }

    /** Narrow symbols need a little more height to look as big as wide ones. */
    private static float iconScale(String name) {
        if ("bluetooth".equals(name)) return 1.18f;
        if ("usb".equals(name)) return 1.3f;
        return 1;
    }

    boolean step(float dt) {
        if (!focus.step(dt)) return false;
        invalidate();
        return true;
    }

    // MARK: - The minute tick and the status icons

    void resume() {
        resumed = true;
        update();
        schedule();
        startDevices();
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(minuteTick);
        stopDevices();
    }

    private void schedule() {
        handler.removeCallbacks(minuteTick);
        if (!resumed) return;
        long now = System.currentTimeMillis();
        handler.postDelayed(minuteTick, 60_000 - now % 60_000 + 50);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            setNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 2
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 1 : 0);
        }

        @Override public void onLost(Network n) {
            setNetwork(0);
        }
    };

    private void setNetwork(int type) {
        if (type == network) return;
        network = type;
        changed();
    }

    private final BroadcastReceiver devicesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            readDevices();
        }
    };

    /** The drive broadcasts carry a file:// URI, so they get a filter (and a receiver) of their own. */
    private final BroadcastReceiver mediaChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            readDevices();
        }
    };

    private void changed() {
        try {
            layoutRow();
            invalidate();
        } catch (RuntimeException e) {
            host.fault("status icons", e);
        }
    }

    private void startDevices() {
        if (!Prefs.statusIcons()) return;
        readDevices();
        if (connectivity != null && !networkRegistered) {
            try {
                if (connectivity.getActiveNetwork() == null) setNetwork(0);
                connectivity.registerDefaultNetworkCallback(networkCallback, handler);
                networkRegistered = true;
            } catch (RuntimeException ignored) {
            }
        }
        Context c = getContext();
        if (!devicesRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            devicesRegistered = register(c, devicesChanged, f);
        }
        if (!mediaRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_MEDIA_MOUNTED);
            f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            f.addAction(Intent.ACTION_MEDIA_EJECT);
            f.addAction(Intent.ACTION_MEDIA_REMOVED);
            f.addDataScheme("file");
            mediaRegistered = register(c, mediaChanged, f);
        }
    }

    private static boolean register(Context c, BroadcastReceiver r, IntentFilter f) {
        try {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            else c.registerReceiver(r, f);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void stopDevices() {
        if (connectivity != null && networkRegistered) {
            try {
                connectivity.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
            }
            networkRegistered = false;
        }
        Context c = getContext();
        if (devicesRegistered) {
            try {
                c.unregisterReceiver(devicesChanged);
            } catch (RuntimeException ignored) {
            }
            devicesRegistered = false;
        }
        if (mediaRegistered) {
            try {
                c.unregisterReceiver(mediaChanged);
            } catch (RuntimeException ignored) {
            }
            mediaRegistered = false;
        }
    }

    /**
     * Bluetooth audio (A2DP or headset: a Bluetooth remote is always connected) and a mounted removable drive, read on
     * a worker (binder calls a reconnecting remote would otherwise pay for on its first key press).
     */
    private void readDevices() {
        final Context app = getContext().getApplicationContext();
        final BluetoothAdapter bt = bluetooth;
        DEVICES.execute(Safe.wrap("status devices", () -> {
            boolean audio = false;
            try {
                if (bt != null && bt.isEnabled()) {
                    audio = bt.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
                            || bt.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
                }
            } catch (RuntimeException ignored) {   // no BLUETOOTH_CONNECT (Android 12+): no icon
            }
            boolean drive = false;
            try {
                StorageManager sm = (StorageManager) app.getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        if (v.isRemovable() && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            drive = true;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
            final boolean a = audio, d = drive;
            Safe.post(handler, "status devices", () -> {
                if (a == bluetoothConnected && d == usbConnected) return;
                bluetoothConnected = a;
                usbConnected = d;
                changed();
            });
        }));
    }

    // MARK: - Drawing (no allocation, no measuring)

    @Override protected void onDraw(Canvas c) {
        int save = c.save();
        try {
            draw0(c);
        } catch (RuntimeException e) {
            host.fault("status draw", e);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void draw0(Canvas c) {
        Context ctx = getContext();
        float cy = CY * k;
        // The Control Center button: PILL with a faint rim, or FOCUS (scale 1.10) when focused.
        float f = Ui.clamp(focus.value), s = 1 + 0.10f * focus.value;
        float cx = BUTTON_CX * k, r = BUTTON_R * k * s;
        fill.setColor(CardPainter.blend(CardPainter.PILL, CardPainter.FOCUS, f));
        c.drawCircle(cx, cy, r, fill);
        if (f < 0.99f) {
            rim.setAlpha(Math.round(255 * (1 - f)));
            c.save();
            c.translate(cx, cy);
            c.drawCircle(0, 0, r - 0.75f * k, rim);
            c.restore();
        }
        Symbols.draw(ctx, c, ccSymbol, cx, cy, SYMBOL_CC * k * (f > 0.5f ? 1.10f : 1), CardPainter.blend(CardPainter.INK, CardPainter.FOCUS_TEXT, f));
        // The clock, right-aligned in its reserved box.
        c.drawText(clockText, CLOCK_RIGHT * k, cy + clockHalf, clock);
        if (tempText != null) {
            Symbols.draw(ctx, c, weatherSymbol, weatherSymbolX, cy, SYMBOL_WEATHER * k, CardPainter.META);
            c.drawText(tempText, tempX, cy + metaHalf, meta);
        }
        c.drawText(dateText, dateX, cy + metaHalf, meta);
        if (sleepX >= 0) {
            Symbols.draw(ctx, c, "bedtime", sleepSymbolX, cy, SYMBOL_SLEEP * k, CardPainter.META);
            c.drawText(sleepText, sleepX, cy + smallHalf, small);
        }
        if (playingX >= 0) {
            NowPlaying.drawBars(c, barsX, cy + smallHalf, smallCap, bars, false, rect);
            c.drawText(playingText, playingX, cy + smallHalf, small);
        }
        for (int i = 0; i < iconCount; i++) Symbols.draw(ctx, c, icons[i], iconX[i], cy, iconSize[i], Ui.alpha(CardPainter.INK, 0.85f));
    }
}
