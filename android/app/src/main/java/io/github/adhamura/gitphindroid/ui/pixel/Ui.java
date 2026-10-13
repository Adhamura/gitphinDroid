package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers shared by the screens (settings, guide, main). */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams wrap() {
        return lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match() {
        return lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams wrapTop(Context c, int topDp) {
        LinearLayout.LayoutParams p = wrap();
        p.topMargin = dp(c, topDp);
        return p;
    }

    public static LinearLayout.LayoutParams fieldLp(Context c) {
        LinearLayout.LayoutParams p = match();
        p.topMargin = dp(c, 4);
        return p;
    }

    public static LinearLayout.LayoutParams wrapTopMatch(Context c, int topDp) {
        LinearLayout.LayoutParams p = match();
        p.topMargin = dp(c, topDp);
        return p;
    }

    /** Fills the rest of a vertical column. */
    public static LinearLayout.LayoutParams fillV() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    /** An empty view that takes the free width of a row. Its height is 0: a plain View with
     *  WRAP_CONTENT takes all the height it is offered and would stretch the row. */
    public static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        return v;
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    /** Pixel-font caption above a field. */
    public static View label(Context c, String t) {
        PixelTextView tv = new PixelTextView(c);
        tv.setScale(1);
        tv.setColor(c.getColor(R.color.px_muted));
        tv.setText(t);
        tv.setLayoutParams(wrapTop(c, 8));
        return tv;
    }

    /** System-font text; the size follows the UI size setting. */
    public static TextView body(Context c, CharSequence t, int sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextSize(UiScale.sp(c, sp));
        tv.setTextColor(color);
        return tv;
    }

    private static GradientDrawable box(Context c, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(c.getColor(R.color.px_code));
        g.setStroke(dp(c, 2), stroke);
        return g;
    }

    public static EditText input(Context c, int type) {
        EditText e = new EditText(c);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(UiScale.sp(c, 14));
        e.setTextColor(c.getColor(R.color.px_fg));
        e.setMinHeight(dp(c, 32 * UiScale.factor(c)));
        e.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] {android.R.attr.state_focused}, box(c, c.getColor(R.color.px_focus)));
        sl.addState(new int[] {}, box(c, c.getColor(R.color.px_bd)));
        e.setBackground(sl);
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(c.getColor(R.color.px_fg));
            cur.setSize(dp(c, 2), dp(c, 18));
            e.setTextCursorDrawable(cur);
        }
        return e;
    }

    /** A card added to a column, 12dp below (its own 4dp shadow makes the 16dp gap). */
    public static PixelCard card(LinearLayout parent, String title) {
        PixelCard c = new PixelCard(parent.getContext(), title);
        LinearLayout.LayoutParams p = match();
        p.bottomMargin = dp(parent.getContext(), 12);
        parent.addView(c, p);
        return c;
    }

    /** Pads the root view by the system bars, the cutout and the keyboard. */
    public static void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = in.left; t = in.top; r = in.right; bt = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                bt = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, bt);
            return insets;
        });
    }

    /** The chrome title bar of a sub screen: a Back button and a pixel title; returns the
     *  whole screen (bar, border, body). The status bar area takes the chrome color. */
    public static LinearLayout screen(Activity a, String title, View body) {
        return screen(a, title, body, true);
    }

    public static LinearLayout screen(Activity a, String title, View body, boolean backButton) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(a.getColor(R.color.px_chrome));
        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(a, 12), dp(a, 6), dp(a, 12), dp(a, 6));
        if (backButton) {
            PixelButton back = new PixelButton(a, "Back");
            back.setOnClickListener(v -> a.finish());
            bar.addView(back, wrap());
        }
        PixelTextView t = new PixelTextView(a);
        t.setScale(3);
        t.setColor(a.getColor(R.color.px_on_chrome));
        t.setText(title);
        LinearLayout.LayoutParams tl = wrap();
        tl.leftMargin = dp(a, backButton ? 12 : 0);
        tl.topMargin = dp(a, backButton ? 0 : 10);
        tl.bottomMargin = dp(a, backButton ? 0 : 10);
        bar.addView(t, tl);
        root.addView(bar, match());
        View border = new View(a);
        border.setBackgroundColor(a.getColor(R.color.px_on_chrome));
        root.addView(border, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 2)));
        root.addView(body, fillV());
        applyInsets(root);
        return root;
    }
}
