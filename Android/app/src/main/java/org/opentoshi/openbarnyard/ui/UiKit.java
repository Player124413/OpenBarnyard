package org.opentoshi.openbarnyard.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Small framework-only UI helpers. The launcher deliberately avoids
 * AndroidX/AppCompat: zero external dependencies means the manual
 * aapt2/d8 build (Android/build.py) works offline with just the SDK,
 * and the APK stays tiny on every phone.
 */
public final class UiKit {
    public static final int COLOR_BG      = Color.rgb(0x1C, 0x2B, 0x1E); // deep pasture green
    public static final int COLOR_CARD    = Color.rgb(0xFF, 0xFD, 0xF4); // cream
    public static final int COLOR_TEXT    = Color.rgb(0x2B, 0x21, 0x18);
    public static final int COLOR_SUBTEXT = Color.rgb(0x6B, 0x5D, 0x4F);
    public static final int COLOR_BARN    = Color.rgb(0xB3, 0x39, 0x2E); // barn red
    public static final int COLOR_GREEN   = Color.rgb(0x3E, 0x7C, 0x4F);
    public static final int COLOR_SKY     = Color.rgb(0x3D, 0x7E, 0xAA);
    public static final int COLOR_AMBER   = Color.rgb(0xD9, 0x8A, 0x2B);

    private UiKit() {}

    public static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** A scrollable vertical screen with the launcher background. */
    public static final class Screen {
        public final ScrollView root;
        public final LinearLayout col;
        Screen(ScrollView root, LinearLayout col) { this.root = root; this.col = col; }
    }

    public static Screen screen(Context ctx) {
        ScrollView sv = new ScrollView(ctx);
        sv.setBackgroundColor(COLOR_BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 14);
        col.setPadding(pad, pad, pad, pad);
        sv.addView(col, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Screen(sv, col);
    }

    public static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, dp(ctx, 14)));
        int pad = dp(ctx, 14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 12));
        card.setLayoutParams(lp);
        return card;
    }

    public static TextView title(Context ctx, int stringRes) {
        return titleText(ctx, ctx.getString(stringRes));
    }

    public static TextView titleText(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
        return t;
    }

    public static TextView subtitle(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(Color.rgb(0xC9, 0xD6, 0xC4));
        t.setTextSize(13);
        t.setPadding(0, 0, 0, dp(ctx, 12));
        return t;
    }

    public static TextView h2(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(ctx, 6));
        return t;
    }

    public static TextView body(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(COLOR_TEXT);
        t.setTextSize(14);
        return t;
    }

    public static TextView small(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(COLOR_SUBTEXT);
        t.setTextSize(12.5f);
        return t;
    }

    public static TextView mono(Context ctx, String text) {
        TextView t = body(ctx, text);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(12);
        t.setTextIsSelectable(true);
        return t;
    }

    public static Button button(Context ctx, String text, int color, View.OnClickListener onClick) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(rounded(color, dp(ctx, 12)));
        b.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4));
        b.setLayoutParams(lp);
        if (onClick != null) b.setOnClickListener(onClick);
        return b;
    }

    public static Button smallButton(Context ctx, String text, int color, View.OnClickListener onClick) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12.5f);
        b.setAllCaps(false);
        b.setBackground(rounded(color, dp(ctx, 9)));
        b.setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7));
        if (onClick != null) b.setOnClickListener(onClick);
        return b;
    }

    public static View spacer(Context ctx, int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)));
        return v;
    }

    /** Horizontal row with even-weight children added later. */
    public static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    public static LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(4, 4, 4, 4);
        return lp;
    }
}
