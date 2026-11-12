package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.LeadingMarginSpan;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The settings' own sheets and toast (SPEC3 §2.8), drawn on SetView's canvas: a confirmation sheet (what will
 * happen, the action, Cancel), a text sheet over the activity's overlay EditText (a city, a typed key) and a toast.
 * They replace every AlertDialog and stock Toast. Layouts are made when a sheet opens or the theme changes.
 */
final class SetSheet {
    private static final int NONE = 0, CONFIRM = 1, TEXT = 2;
    private static final float PANEL_W = 960, PAD = 60, INNER_W = 840, FIELD_H = 80, BUTTON_H = 66, BUTTON_PITCH = 78,
            RADIUS = 40, TOAST_TOP = 60, TOAST_H = 64, TOAST_LINE = 38, TOAST_PAD = 32, TOAST_MAX_W = 1200;
    private static final long CLOSE_MS = 150, TOAST_FADE_MS = 200, TOAST_HOLD_MS = 5000, IME_RETRY_MS = 300,
            IME_GIVE_UP_MS = 1200;

    private final SetView view;
    private int kind = NONE;
    private String title = "", action = "", hint = "";
    private final List<String> body = new ArrayList<>();
    private boolean destructive, secret;
    private Runnable yes;
    private Consumer<String> done;
    /** -1: the text field; 0: the action; 1: Cancel. */
    private int focus;
    private boolean okDown, backDown;
    private final Spring open = new Spring(0.30f, 0.90f, 0);
    private final Spring[] buttonFocus = {new Spring(0.24f, 0.86f, 0), new Spring(0.24f, 0.86f, 0)};
    private long closingAt;   // uptime the close began (0: not closing)
    private int closingKind;
    // Laid out (design units for geometry, pixels in the layouts).
    private StaticLayout titleLayout;
    private final List<StaticLayout> bodyLayouts = new ArrayList<>();
    private final String[] buttons = new String[2];
    private float panelTop, panelH, titleTop, bodyTop, fieldTop, buttonsTop;
    private final RectF rect = new RectF();

    // The toast.
    private String toastText;
    private boolean toastWarning;
    private StaticLayout toastLayout;
    private float toastW, toastH;
    private long toastIn, toastOut;   // uptimes: shown, and fading out (0: held)
    private final Runnable toastEnd = new Runnable() {   // posted as itself, so a newer toast can take it back
        @Override public void run() {
            try {
                toastOut = SystemClock.uptimeMillis();
                invalidate();
            } catch (RuntimeException e) {
                view.fault("toast", e);
            }
        }
    };
    private final Runnable imeRetry = new Runnable() {
        @Override public void run() {
            try {
                EditText f = field();
                if (kind != TEXT || closingAt != 0 || f == null || focus != -1) return;
                InputMethodManager imm = imm();
                if (imm != null && !(imm.isActive(f) && imm.isAcceptingText())) {
                    imm.showSoftInput(f, InputMethodManager.SHOW_FORCED);
                }
            } catch (RuntimeException e) {
                view.fault("ime", e);
            }
        }
    };
    private final Runnable imeCheck = new Runnable() {
        @Override public void run() {
            try {
                EditText f = field();
                if (kind != TEXT || closingAt != 0 || f == null || focus != -1) return;
                InputMethodManager imm = imm();
                // No keyboard came up: say how to bring it (OK asks again).
                if (imm == null || !imm.isAcceptingText()) f.setHint(Ui.tr("Press OK to type"));
            } catch (RuntimeException e) {
                view.fault("ime", e);
            }
        }
    };

    SetSheet(SetView view) {
        this.view = view;
    }

    // MARK: - State

    /** A sheet is open and takes the keys (not while it closes). */
    boolean active() {
        return kind != NONE && closingAt == 0;
    }

    /** Anything of it is drawn (a sheet, a closing one, the toast). */
    boolean shown() {
        return kind != NONE || closingAt != 0 || toastShowing();
    }

    boolean toastShowing() {
        return toastLayout != null;
    }

    void confirm(String t, List<String> lines, String act, boolean destroy, boolean focusAction, Runnable onYes) {
        closeNow();
        kind = CONFIRM;
        title = t == null ? "" : t;
        body.clear();
        if (lines != null) for (String s : lines) if (s != null && !s.isEmpty()) body.add(s);
        action = act == null ? "" : act;
        destructive = destroy;
        yes = onYes;
        done = null;
        focus = focusAction ? 0 : 1;
        opened();
    }

    void askText(String t, String h, String act, boolean isSecret, String initial, Consumer<String> onDone) {
        closeNow();
        kind = TEXT;
        title = t == null ? "" : t;
        body.clear();
        hint = h == null ? "" : h;
        action = act == null ? "" : act;
        destructive = false;
        secret = isSecret;
        yes = null;
        done = onDone;
        focus = -1;
        opened();
        showField(initial == null ? "" : initial);
    }

    private void opened() {
        okDown = backDown = false;
        open.snap(0);
        open.target = 1;
        for (int i = 0; i < 2; i++) buttonFocus[i].snap(focus == i ? 1 : 0);
        layout();
        view.announce(title);
        invalidate();
    }

    /** Cancel (Back, the scrim, the button): the sheet closes, nothing runs. */
    void cancel() {
        close();
    }

    private void close() {
        if (kind == NONE) return;
        hideField();
        closingKind = kind;
        closingAt = SystemClock.uptimeMillis();
        kind = NONE;
        yes = null;
        done = null;
        invalidate();
    }

    /** Gone at once (another sheet replaces it, or the screen stops). */
    void closeNow() {
        if (kind == TEXT) hideField();
        kind = NONE;
        closingAt = 0;
        yes = null;
        done = null;
    }

    void dismiss() {
        closeNow();
        view.handler().removeCallbacks(toastEnd);
        toastLayout = null;
        toastText = null;
    }

    // MARK: - The toast

    void toast(String text, boolean warning) {
        if (text == null || text.isEmpty()) return;
        toastText = text;
        toastWarning = warning;
        toastIn = SystemClock.uptimeMillis();
        toastOut = 0;
        layoutToast();
        view.handler().removeCallbacks(toastEnd);
        view.handler().postDelayed(toastEnd, TOAST_FADE_MS + TOAST_HOLD_MS);
        view.announce(text);
        invalidate();
    }

    // MARK: - Layout

    /** After a size or theme change (and when a sheet opens). */
    void layout() {
        SetTheme t = view.theme();
        if (t == null) return;
        float k = t.k;
        if (kind != NONE || closingAt != 0) {
            titleLayout = SetTheme.layout(title, textPaint(t.sheetTitle), INNER_W * k, 2, Layout.Alignment.ALIGN_CENTER,
                    54 * k);
            bodyLayouts.clear();
            String bullet = "•  ";
            int indent = Math.round(t.sheetBody.measureText(bullet));
            for (String line : body) {
                SpannableString s = new SpannableString(bullet + line);
                s.setSpan(new LeadingMarginSpan.Standard(0, indent), 0, s.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
                bodyLayouts.add(SetTheme.layout(s, t.sheetBody, INNER_W * k, 4, Layout.Alignment.ALIGN_NORMAL, 42 * k));
            }
            buttons[0] = SetTheme.fit(action, t.button, (INNER_W - 60) * k);
            buttons[1] = SetTheme.fit(Ui.tr("Cancel"), t.button, (INNER_W - 60) * k);
            float y = PAD;
            titleTop = y;
            y += titleLayout.getHeight() / k + 28;
            bodyTop = y;
            for (StaticLayout l : bodyLayouts) y += l.getHeight() / k + 14;
            if (!bodyLayouts.isEmpty()) y += 14;
            fieldTop = y;
            if (kind == TEXT || closingKind == TEXT && kind == NONE) y += FIELD_H + 36;
            buttonsTop = y;
            y += BUTTON_PITCH + BUTTON_H;
            panelH = y + PAD;
            panelTop = Math.max(40, (1080 - panelH) / 2);
            if (kind == TEXT) placeField();
        }
        layoutToast();
    }

    private void layoutToast() {
        SetTheme t = view.theme();
        if (t == null || toastText == null) return;
        float k = t.k, icon = toastWarning ? SetTheme.SMALL_ICON + 16 : 0;
        float maxText = TOAST_MAX_W - 2 * TOAST_PAD - icon;
        float natural = t.toast.measureText(toastText) / k;
        float textW = Math.min(maxText, natural + 1);
        toastLayout = SetTheme.layout(toastText, t.toast, textW * k, 3, Layout.Alignment.ALIGN_NORMAL, TOAST_LINE * k);
        float widest = 0;
        for (int i = 0; i < toastLayout.getLineCount(); i++) widest = Math.max(widest, toastLayout.getLineWidth(i));
        toastW = Math.min(TOAST_MAX_W, widest / k + 2 * TOAST_PAD + icon);
        toastH = TOAST_H + TOAST_LINE * (toastLayout.getLineCount() - 1);
    }

    private static android.text.TextPaint textPaint(android.graphics.Paint p) {
        return p instanceof android.text.TextPaint ? (android.text.TextPaint) p : new android.text.TextPaint(p);
    }

    // MARK: - The text field (the activity's overlay EditText)

    private EditText field() {
        SettingsActivity a = view.activity();
        return a == null ? null : a.textField();
    }

    private InputMethodManager imm() {
        return (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void showField(String initial) {
        EditText f = field();
        SetTheme t = view.theme();
        if (f == null) return;
        f.setBackground(null);
        f.setSaveEnabled(false);
        f.setSingleLine(true);
        f.setTypeface(Ui.medium);
        if (t != null) {
            f.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * t.k);
            f.setTextColor(t.ink);
            f.setHintTextColor(t.secondary);
            int pad = Math.round(24 * t.k);
            f.setPadding(pad, 0, pad, 0);
        }
        f.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int ime = action.equals(Ui.tr("Search")) ? EditorInfo.IME_ACTION_SEARCH : EditorInfo.IME_ACTION_DONE;
        f.setImeOptions(ime | EditorInfo.IME_FLAG_NO_FULLSCREEN | (secret ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        f.setHint(hint);
        f.setText(initial);
        f.setSelection(f.getText().length());
        f.setOnEditorActionListener((v, actionId, event) -> {
            try {
                if (kind != TEXT || closingAt != 0) return false;
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH
                        || actionId == EditorInfo.IME_ACTION_GO || enter) {
                    if (event == null || event.getAction() == KeyEvent.ACTION_UP || actionId != EditorInfo.IME_NULL) {
                        submit();
                    }
                    return true;
                }
                return false;
            } catch (RuntimeException e) {
                view.fault("text-action", e);
                return true;
            }
        });
        // If the activity hands the keys to the field first: ↓ still goes to the buttons.
        f.setOnKeyListener((v, keyCode, event) -> {
            try {
                if (kind != TEXT || closingAt != 0 || focus != -1) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) toButtons();
                    return true;
                }
                return false;
            } catch (RuntimeException e) {
                view.fault("text-key", e);
                return true;
            }
        });
        placeField();
        f.setVisibility(View.VISIBLE);
        focusField();
    }

    /** The field over the drawn field box (pixels, in the screen view's parent). */
    private void placeField() {
        EditText f = field();
        SetTheme t = view.theme();
        if (f == null || t == null) return;
        float k = t.k;
        int width = Math.round(INNER_W * k), height = Math.round(FIELD_H * k);
        ViewGroup.LayoutParams lp = f.getLayoutParams();
        if (lp == null) lp = new ViewGroup.LayoutParams(width, height);
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            if (lp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) lp).setMargins(0, 0, 0, 0);
            f.setLayoutParams(lp);
        }
        float x = view.getX() + (960 - INNER_W / 2) * k, y = view.getY() + (panelTop + fieldTop) * k;
        f.setX(x);
        f.setY(y);
        // Before its first layout getLeft() is 0; once laid out, setX keeps the place whatever the gravity.
        f.post(() -> {
            try {
                if (kind == TEXT) {
                    f.setX(x);
                    f.setY(y);
                }
            } catch (RuntimeException e) {
                view.fault("text-place", e);
            }
        });
    }

    private void focusField() {
        EditText f = field();
        if (f == null) return;
        focus = -1;
        f.requestFocus();
        f.post(() -> {
            try {
                if (kind != TEXT || focus != -1) return;
                InputMethodManager imm = imm();
                if (imm != null) imm.showSoftInput(f, InputMethodManager.SHOW_IMPLICIT);
            } catch (RuntimeException e) {
                view.fault("ime", e);
            }
        });
        view.handler().removeCallbacks(imeRetry);
        view.handler().removeCallbacks(imeCheck);
        view.handler().postDelayed(imeRetry, IME_RETRY_MS);
        view.handler().postDelayed(imeCheck, IME_GIVE_UP_MS);
        invalidate();
    }

    private void toButtons() {
        EditText f = field();
        focus = 0;
        buttonFocus[0].target = 1;
        if (f != null) {
            InputMethodManager imm = imm();
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        }
        view.requestFocus();
        view.spokenMoved();
        invalidate();
    }

    private void hideField() {
        view.handler().removeCallbacks(imeRetry);
        view.handler().removeCallbacks(imeCheck);
        EditText f = field();
        if (f == null) return;
        try {
            InputMethodManager imm = imm();
            if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
            f.setOnEditorActionListener(null);
            f.setOnKeyListener(null);
            f.setText("");   // a typed key doesn't stay in the field
            f.setVisibility(View.GONE);
        } finally {
            view.requestFocus();
        }
    }

    private void submit() {
        if (kind != TEXT) return;
        EditText f = field();
        String text = f == null ? "" : f.getText().toString().trim();
        if (text.isEmpty()) {
            focusField();
            return;
        }
        Consumer<String> d = done;
        close();
        if (d != null) d.accept(text);
    }

    private void press(int i) {
        if (i == -1) {
            focusField();
            return;
        }
        if (i == 1) {
            cancel();
            return;
        }
        if (kind == TEXT) {
            submit();
            return;
        }
        Runnable r = yes;
        close();
        if (r != null) r.run();
    }

    // MARK: - Keys, pointer, TalkBack

    /** True when the sheet took the key. Typing keys go on to the field. */
    boolean key(KeyEvent e) {
        if (!active()) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (kind == TEXT && focus == -1) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) toButtons();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    if (!down) focusField();   // the keyboard again
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (down) backDown = e.getRepeatCount() == 0;
                    else if (backDown && !e.isCanceled()) cancel();
                    if (!down) backDown = false;
                    return true;
                default:
                    return false;   // letters, Enter, the cursor's ← →: the field's
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (focus > 0) moveTo(focus - 1);
                    else if (kind == TEXT) focusField();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && focus < 1) moveTo(focus + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;   // only a press that began on the sheet counts
                } else {
                    boolean go = okDown && !e.isCanceled() && view.keyClick();
                    okDown = false;
                    if (go) press(focus);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) cancel();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void moveTo(int i) {
        if (i == focus) return;
        focus = i;
        view.clickMove();
        view.spokenMoved();
        invalidate();
    }

    /** A click (design units): a button presses it, the field takes the focus, the scrim cancels. */
    void tap(float x, float y) {
        if (!active()) return;
        int i = itemAt(x, y);
        if (i == -2) {
            cancel();
        } else if (i != -3) {
            if (i >= 0) focus = i;
            press(i);
        }
    }

    void hover(float x, float y) {
        if (!active()) return;
        int i = itemAt(x, y);
        if (i >= 0 && i != focus) moveTo(i);
    }

    /** -1 field, 0/1 buttons, -2 outside the panel, -3 elsewhere on the panel. */
    private int itemAt(float x, float y) {
        float left = 960 - PANEL_W / 2, top = panelTop;
        if (x < left || x > left + PANEL_W || y < top || y > top + panelH) return -2;
        float il = 960 - INNER_W / 2, ir = 960 + INNER_W / 2;
        if (x >= il && x <= ir) {
            if (kind == TEXT && y >= top + fieldTop && y <= top + fieldTop + FIELD_H) return -1;
            for (int i = 0; i < 2; i++) {
                float bt = top + buttonsTop + i * BUTTON_PITCH;
                if (y >= bt && y <= bt + BUTTON_H) return i;
            }
        }
        return -3;
    }

    /** TalkBack's items while the sheet shows: 1001… (the field first in a text sheet). */
    void items(List<Integer> out) {
        if (!active()) return;
        if (kind == TEXT) out.add(1001);
        out.add(1002);
        out.add(1003);
    }

    String text(int id) {
        if (!active()) return "";
        if (id == 1001) {
            EditText f = field();
            String typed = f == null || secret ? "" : f.getText().toString();
            return title + ", " + (typed.isEmpty() ? hint : typed);
        }
        if (id == 1002) {
            StringBuilder b = new StringBuilder(action);
            if (focus == 0 || id == focusedId()) {
                b.append(". ").append(title);
                for (String s : body) b.append(". ").append(s);
            }
            return b.toString();
        }
        if (id == 1003) return Ui.tr("Cancel");
        return "";
    }

    boolean bounds(int id, Rect out, float k) {
        if (!active()) return false;
        float il = 960 - INNER_W / 2;
        if (id == 1001 && kind == TEXT) {
            rect.set(il, panelTop + fieldTop, il + INNER_W, panelTop + fieldTop + FIELD_H);
        } else if (id == 1002 || id == 1003) {
            float bt = panelTop + buttonsTop + (id - 1002) * BUTTON_PITCH;
            rect.set(il, bt, il + INNER_W, bt + BUTTON_H);
        } else {
            return false;
        }
        out.set(Math.round(rect.left * k), Math.round(rect.top * k), Math.round(rect.right * k), Math.round(rect.bottom * k));
        return true;
    }

    int focusedId() {
        return focus == -1 ? 1001 : 1002 + focus;
    }

    void focusId(int id) {
        if (id == 1001 && kind == TEXT) focusField();
        else if (id == 1002 || id == 1003) {
            if (focus == -1) toButtons();
            moveTo(id - 1002);
        }
    }

    void click(int id) {
        if (!active()) return;
        if (id == 1001) press(-1);
        else if (id == 1002 || id == 1003) press(id - 1002);
    }

    /** The press spring: OK held on a button. */
    boolean pressing() {
        return active() && okDown && focus >= 0;
    }

    // MARK: - Drawing (no allocation)

    /** Advances the springs; true while something still moves. */
    boolean step(float dt) {
        boolean moving = false;
        if (kind != NONE) moving |= open.step(dt);
        for (int i = 0; i < 2; i++) {
            buttonFocus[i].target = active() && focus == i ? 1 : 0;
            moving |= buttonFocus[i].step(dt);
        }
        long now = SystemClock.uptimeMillis();
        if (closingAt != 0) {
            if (now - closingAt >= CLOSE_MS) {
                closingAt = 0;
            } else {
                moving = true;
            }
        }
        if (toastLayout != null) {
            if (toastOut != 0 && now - toastOut >= TOAST_FADE_MS) {
                toastLayout = null;
                toastText = null;
            } else if (toastOut != 0 || now - toastIn < TOAST_FADE_MS) {
                moving = true;
            }
        }
        return moving;
    }

    void draw(Canvas c, SetTheme t, SetIcons small, boolean pressed, float pressValue) {
        if (kind != NONE || closingAt != 0) drawSheet(c, t, pressValue);
        if (toastLayout != null) drawToast(c, t, small);
    }

    private float sheetAlpha() {
        if (closingAt != 0) {
            return Math.max(0, 1 - (SystemClock.uptimeMillis() - closingAt) / (float) CLOSE_MS);
        }
        return Math.max(0, Math.min(1, open.value));
    }

    private void drawSheet(Canvas c, SetTheme t, float pressValue) {
        float k = t.k, a = sheetAlpha();
        if (a <= 0 || titleLayout == null) return;
        int save = c.save();
        try {
            t.fill.setShader(null);
            t.fill.setColor(SetTheme.alpha(t.scrim, a));
            c.drawRect(0, 0, c.getWidth(), c.getHeight(), t.fill);
            float scale = closingAt != 0 ? 1 : 0.96f + 0.04f * Math.min(1, open.value);
            float left = (960 - PANEL_W / 2) * k, top = panelTop * k, right = (960 + PANEL_W / 2) * k,
                    bottom = (panelTop + panelH) * k;
            c.scale(scale, scale, 960 * k, (top + bottom) / 2);
            // Shadow, panel, the material's hairlines.
            rect.set(left, top + t.materialShadowDy * k, right, bottom + t.materialShadowDy * k);
            int shadow = t.materialShadow.getColor();
            t.materialShadow.setColor(SetTheme.alpha(shadow, a));
            t.shadows.draw(c, rect, RADIUS * k, 40 * k, t.materialShadow);
            t.materialShadow.setColor(shadow);
            rect.set(left, top, right, bottom);
            t.fill.setColor(SetTheme.alpha(t.sheet, a));
            c.drawRoundRect(rect, RADIUS * k, RADIUS * k, t.fill);
            hairlines(c, t, rect, RADIUS * k, a);
            // Title and body.
            int s2 = c.save();
            c.translate((960 - INNER_W / 2) * k, top + titleTop * k);
            titleLayout.getPaint().setColor(SetTheme.alpha(t.ink, a));
            titleLayout.draw(c);
            c.restoreToCount(s2);
            float y = top + bodyTop * k;
            t.sheetBody.setColor(SetTheme.alpha(t.ink, a));
            for (int i = 0; i < bodyLayouts.size(); i++) {
                StaticLayout l = bodyLayouts.get(i);
                int s3 = c.save();
                c.translate((960 - INNER_W / 2) * k, y);
                l.draw(c);
                c.restoreToCount(s3);
                y += l.getHeight() + 14 * k;
            }
            boolean text = kind == TEXT || closingAt != 0 && closingKind == TEXT;
            if (text) {
                rect.set((960 - INNER_W / 2) * k, top + fieldTop * k, (960 + INNER_W / 2) * k,
                        top + (fieldTop + FIELD_H) * k);
                t.fill.setColor(SetTheme.alpha(t.row, a));
                c.drawRoundRect(rect, 16 * k, 16 * k, t.fill);
                if (focus == -1 && closingAt == 0) {
                    t.stroke.setColor(SetTheme.alpha(t.focus, a));
                    float w = t.stroke.getStrokeWidth();
                    t.stroke.setStrokeWidth(2 * k);
                    c.drawRoundRect(rect, 16 * k, 16 * k, t.stroke);
                    t.stroke.setStrokeWidth(w);
                }
            }
            for (int i = 0; i < 2; i++) {
                float f = buttonFocus[i].value;
                float bt = top + (buttonsTop + i * BUTTON_PITCH) * k;
                float bl = (960 - INNER_W / 2) * k, br = (960 + INNER_W / 2) * k, cy = bt + BUTTON_H / 2 * k;
                int s4 = c.save();
                float sc = 1 + 0.04f * f;
                if (i == focus && pressValue > 0) sc = sc + (0.985f - sc) * pressValue;
                if (f > 0.01f) {
                    rect.set(bl, bt + t.focusShadowDy * k, br, bt + (BUTTON_H + t.focusShadowDy) * k);
                    t.rowShadow.setColor(SetTheme.alpha(t.focusShadow, a * f));
                    t.shadows.draw(c, rect, BUTTON_H / 2 * k, 34 * k, t.rowShadow);
                }
                c.scale(sc, sc, (bl + br) / 2, cy);
                rect.set(bl, bt, br, bt + BUTTON_H * k);
                t.fill.setColor(SetTheme.alpha(SetTheme.blend(t.row, t.focus, f), a));
                c.drawRoundRect(rect, BUTTON_H / 2 * k, BUTTON_H / 2 * k, t.fill);
                int color = i == 0 && destructive ? t.red : SetTheme.blend(t.ink, t.focusInk, f);
                t.button.setColor(SetTheme.alpha(color, a));
                String label = buttons[i] == null ? "" : buttons[i];
                c.drawText(label, bl + 30 * k, cy + t.buttonHalf, t.button);
                c.restoreToCount(s4);
            }
        } finally {
            c.restoreToCount(save);
        }
    }

    /** MATERIAL's top hairline (fading over 24 px) and bottom one, on a rounded rect. */
    static void hairlines(Canvas c, SetTheme t, RectF r, float radius, float a) {
        int alpha = Math.round(255 * Math.max(0, Math.min(1, a)));
        int save = c.save();
        c.translate(0, r.top);
        t.hairTop.setAlpha(alpha);
        c.drawRoundRect(r.left, 0, r.right, r.height(), radius, radius, t.hairTop);
        c.restoreToCount(save);
        save = c.save();
        c.translate(0, r.bottom - 24 * t.k);
        t.hairBottom.setAlpha(alpha);
        c.drawRoundRect(r.left, 24 * t.k - r.height(), r.right, 24 * t.k, radius, radius, t.hairBottom);
        c.restoreToCount(save);
    }

    private void drawToast(Canvas c, SetTheme t, SetIcons small) {
        long now = SystemClock.uptimeMillis();
        float a, dy;
        if (toastOut != 0) {
            a = Math.max(0, 1 - (now - toastOut) / (float) TOAST_FADE_MS);
            dy = 0;
        } else {
            float p = Math.min(1, (now - toastIn) / (float) TOAST_FADE_MS);
            a = p;
            dy = -12 * (1 - p);
        }
        if (a <= 0) return;
        float k = t.k;
        float left = (960 - toastW / 2) * k, top = (TOAST_TOP + dy) * k, right = (960 + toastW / 2) * k,
                bottom = top + toastH * k, radius = TOAST_H / 2 * k;
        int save = c.save();
        try {
            rect.set(left, top + t.materialShadowDy * k, right, bottom + t.materialShadowDy * k);
            int shadow = t.materialShadow.getColor();
            t.materialShadow.setColor(SetTheme.alpha(shadow, a));
            t.shadows.draw(c, rect, radius, 40 * k, t.materialShadow);
            t.materialShadow.setColor(shadow);
            rect.set(left, top, right, bottom);
            t.fill.setShader(null);
            t.fill.setColor(SetTheme.alpha(t.material, a));
            c.drawRoundRect(rect, radius, radius, t.fill);
            hairlines(c, t, rect, radius, a);
            float x = left + TOAST_PAD * k;
            if (toastWarning && small != null) {
                small.draw(c, "warning", x + SetTheme.SMALL_ICON / 2 * k, top + TOAST_H / 2 * k,
                        SetTheme.alpha(t.orange, a));
                x += (SetTheme.SMALL_ICON + 16) * k;
            }
            // The first line's centre on the capsule's first 64 px.
            float lineTop = toastLayout.getLineTop(0), lineBottom = toastLayout.getLineBottom(0);
            float ty = top + TOAST_H / 2 * k - (lineTop + lineBottom) / 2;
            c.translate(x, ty);
            t.toast.setColor(SetTheme.alpha(t.ink, a));
            toastLayout.draw(c);
        } finally {
            c.restoreToCount(save);
        }
    }

    private void invalidate() {
        view.sheetChanged();
    }
}
