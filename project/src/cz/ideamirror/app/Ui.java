package cz.ideamirror.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

final class Ui {
    static final String VERSION = "0.9.0";
    static final String ROLE_PREFS = "tabule_role", ROLE_BOARD = "board", ROLE_SENDER = "sender";

    static final int BG = Color.rgb(11, 19, 32);
    static final int BG_BOTTOM = Color.rgb(18, 31, 49);
    static final int CARD = Color.rgb(22, 36, 54);
    static final int CARD_STROKE = Color.rgb(40, 62, 88);
    static final int CARD_HI = Color.rgb(19, 46, 56);
    static final int CARD_HI_STROKE = Color.rgb(48, 148, 126);
    static final int PANEL = Color.rgb(28, 46, 68);
    static final int SUBPANEL = Color.rgb(14, 24, 38);
    static final int ACCENT = Color.rgb(56, 217, 169);
    static final int ACCENT_DARK = Color.rgb(7, 28, 28);
    static final int TEXT = Color.rgb(242, 247, 252);
    static final int MUTED = Color.rgb(156, 178, 200);
    static final int DANGER_BG = Color.rgb(62, 30, 42);
    static final int DANGER_STROKE = Color.rgb(152, 62, 84);
    static final int DANGER_TEXT = Color.rgb(255, 176, 186);

    static int dp(Context c, int n) { return (int) (n * c.getResources().getDisplayMetrics().density + .5f); }

    static GradientDrawable rounded(Context c, int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, 1)), stroke);
        return d;
    }

    static GradientDrawable screenBg() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG, BG_BOTTOM});
    }

    static TextView text(Context c, String s, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(TEXT);
        t.setLineSpacing(dp(c, 2), 1.05f);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    static TextView muted(Context c, String s, int sp) {
        TextView t = text(c, s, sp);
        t.setTextColor(MUTED);
        return t;
    }

    static TextView section(Context c, String s) {
        TextView t = text(c, s, 12);
        t.setTextColor(ACCENT);
        t.setTypeface(null, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(c, 6));
        return t;
    }

    static TextView pill(Context c, String s, int fill, int stroke, int textColor) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(textColor);
        t.setTypeface(null, Typeface.BOLD);
        t.setBackground(rounded(c, fill, stroke, 999));
        t.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
        return t;
    }

    private static Button makeButton(Context c, String s, int fill, int stroke, int textColor, boolean bold, Runnable click) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        if (bold) b.setTypeface(null, Typeface.BOLD);
        b.setStateListAnimator(null);
        Drawable bg = rounded(c, fill, stroke, 13);
        int rippleColor = Color.argb(60, 255, 255, 255);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
        b.setMinHeight(dp(c, 46));
        b.setMinimumHeight(dp(c, 46));
        b.setPadding(dp(c, 18), dp(c, 11), dp(c, 18), dp(c, 11));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    static Button button(Context c, String s, Runnable click) {
        return makeButton(c, s, ACCENT, 0, ACCENT_DARK, true, click);
    }

    static Button secondaryButton(Context c, String s, Runnable click) {
        return makeButton(c, s, PANEL, CARD_STROKE, TEXT, false, click);
    }

    static Button dangerButton(Context c, String s, Runnable click) {
        return makeButton(c, s, DANGER_BG, DANGER_STROKE, DANGER_TEXT, true, click);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c, boolean highlight) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, highlight ? CARD_HI : CARD, highlight ? CARD_HI_STROKE : CARD_STROKE, 18));
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout subpanel(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, SUBPANEL, CARD_STROKE, 12));
        int p = dp(c, 12);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams vSpace(Context c, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(c, topDp), 0, dp(c, bottomDp));
        return lp;
    }

    static void prepare(Activity a) {
        a.getWindow().setStatusBarColor(BG);
        a.getWindow().setNavigationBarColor(BG_BOTTOM);
        a.getWindow().getDecorView().setBackground(screenBg());
        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
            a.getWindow().getDecorView().setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return insets;
            });
        }
    }

    static String role(Context c) { return c.getSharedPreferences(ROLE_PREFS, Context.MODE_PRIVATE).getString("role", null); }
    static void setRole(Context c, String role) { c.getSharedPreferences(ROLE_PREFS, Context.MODE_PRIVATE).edit().putString("role", role).apply(); }

    /** Title + subtitle + switch row. */
    static Switch toggleRow(LinearLayout parent, String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = column(c);
        TextView t = text(c, title, 15);
        t.setPadding(0, 0, 0, 0);
        labels.addView(t);
        if (subtitle != null) {
            TextView st = muted(c, subtitle, 12);
            st.setPadding(0, 0, 0, 0);
            labels.addView(st);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.addView(sw);
        parent.addView(row, vSpace(c, 4, 8));
        return sw;
    }

    static String ago(long ms) {
        if (ms <= 0) return "nikdy";
        long d = Math.max(0, System.currentTimeMillis() - ms) / 1000;
        if (d < 60) return "právě teď";
        if (d < 3600) return "před " + (d / 60) + " min";
        if (d < 86400) return "před " + (d / 3600) + " h";
        return "před " + (d / 86400) + " dny";
    }

    static void error(Activity a, String s) { error(a, s, null); }

    static void error(Activity a, String s, Runnable onDismiss) { message(a, "Připojení se nepodařilo", s, onDismiss); }

    static void message(Activity a, String title, String s, Runnable onDismiss) {
        if (!a.isFinishing() && !a.isDestroyed()) {
            new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(s)
                .setPositiveButton("Rozumím", (d, w) -> { if (onDismiss != null) onDismiss.run(); })
                .setOnCancelListener(d -> { if (onDismiss != null) onDismiss.run(); })
                .show();
        }
    }

    static String problem(Throwable e) {
        if (e == null) return "Neznámá chyba";
        String msg = e.getMessage();
        String base = e.getClass().getSimpleName() + (msg == null || msg.isEmpty() ? "" : ": " + msg);
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String cMsg = cause.getMessage();
            String cPart = cause.getClass().getSimpleName() + (cMsg == null || cMsg.isEmpty() ? "" : ": " + cMsg);
            if (!base.contains(cPart)) base += " (" + cPart + ")";
        }
        return base;
    }

    static String info() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
            + " (API " + Build.VERSION.SDK_INT + ") · Tabule Link " + VERSION;
    }

    static void copy(Context c, String label, String s) {
        ((android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText(label, s));
        Toast.makeText(c, "Zkopírováno", Toast.LENGTH_SHORT).show();
    }
}
