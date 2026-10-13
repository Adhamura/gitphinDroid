package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Dialog in the web page style: card, 2dp border, hard 6dp shadow, pixel title, pixel buttons. */
public final class PixelDialog {
    private PixelDialog() {}

    public static Builder with(Context c) { return new Builder(c); }

    /** A progress dialog: not cancelable, a message and a determinate bar. */
    public static Progress progress(Context c, String title) { return new Progress(c, title); }

    private static final class Btn {
        final String label;
        final Runnable action;
        final boolean primary, bad;
        Btn(String l, Runnable a, boolean p, boolean b) { label = l; action = a; primary = p; bad = b; }
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A scroll view that never grows over 60% of the screen height. */
    private static final class Scroll extends ScrollView {
        Scroll(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    private static Dialog makeDialog(Context c, PixelPanel panel, boolean cancelable) {
        Dialog dlg = new Dialog(c);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(panel);
        dlg.setCancelable(cancelable);
        dlg.setCanceledOnTouchOutside(cancelable);
        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.6f);
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int body = Math.min(dp(c, 560), m.widthPixels - dp(c, 32));
        w.setLayout(body + dp(c, 6), ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
        return dlg;
    }

    private static PixelPanel makePanel(Context c, String title, LinearLayout body) {
        PixelPanel p = new PixelPanel(c, 6);
        p.setInnerPadding(16);
        if (title != null && !title.isEmpty()) {
            PixelTextView t = new PixelTextView(c);
            t.setScale(2);
            t.setColor(c.getColor(R.color.px_fg));
            t.setText(title);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, 8);
            p.addView(t, lp);
        }
        p.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return p;
    }

    private static TextView text(Context c, CharSequence s, int sp, int color, int topDp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(UiScale.sp(c, sp));
        t.setTextColor(c.getColor(color));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        t.setLayoutParams(lp);
        return t;
    }

    public static final class Builder {
        private final Context c;
        private String title, message, hint;
        private View custom;
        private boolean cancelable = true, stacked;
        private Runnable onCancel;
        private final List<Btn> buttons = new ArrayList<>();

        Builder(Context c) { this.c = c; }

        public Builder title(String t) { title = t; return this; }
        public Builder message(CharSequence m) { message = m == null ? null : m.toString(); return this; }
        Builder hint(String h) { hint = h; return this; }
        public Builder content(View v) { custom = v; return this; }
        public Builder cancelable(boolean b) { cancelable = b; return this; }
        /** Puts the buttons in a column (right aligned), for long labels. */
        public Builder stacked(boolean b) { stacked = b; return this; }
        /** Runs when the dialog is cancelled (back key or outside tap). */
        public Builder onCancel(Runnable r) { onCancel = r; return this; }
        /** A normal button. The action may be null. */
        public Builder button(String label, Runnable action) {
            buttons.add(new Btn(label, action, false, false));
            return this;
        }
        /** The main button. */
        public Builder primary(String label, Runnable action) {
            buttons.add(new Btn(label, action, true, false));
            return this;
        }
        /** A destructive main button, in the bad color. */
        public Builder destructive(String label, Runnable action) {
            buttons.add(new Btn(label, action, true, true));
            return this;
        }

        public Dialog show() {
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            LinearLayout scrolled = new LinearLayout(c);
            scrolled.setOrientation(LinearLayout.VERTICAL);
            if (message != null) scrolled.addView(text(c, message, 14, R.color.px_fg, 8));
            if (hint != null) scrolled.addView(text(c, hint, 12, R.color.px_muted, 4));
            if (custom != null) scrolled.addView(custom);
            Scroll sv = new Scroll(c);
            sv.addView(scrolled, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            body.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            if (stacked) row.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(c, 16);
            body.addView(row, rlp);

            Dialog dlg = makeDialog(c, makePanel(c, title, body), cancelable);
            if (onCancel != null) dlg.setOnCancelListener(d -> onCancel.run());
            for (Btn b : buttons) {
                PixelButton pb = new PixelButton(c, b.label);
                if (b.primary) pb.setPrimary(true);
                if (b.bad) pb.setPrimaryColor(c.getColor(R.color.px_bad));
                pb.setOnClickListener(v -> {
                    dlg.dismiss();
                    if (b.action != null) b.action.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = dp(c, 3); // the button has 5dp room already; this makes the 8dp gap
                row.addView(pb, lp);
            }
            dlg.show();
            return dlg;
        }
    }

    /** Handle for a progress dialog. */
    public static final class Progress {
        private final Dialog dlg;
        private final TextView msg;
        private final PixelProgressBar bar;

        Progress(Context c, String title) {
            LinearLayout body = new LinearLayout(c);
            body.setOrientation(LinearLayout.VERTICAL);
            msg = text(c, "", 14, R.color.px_fg, 8);
            body.addView(msg);
            bar = new PixelProgressBar(c, 12, c.getColor(R.color.px_fg));
            bar.set(-1, true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(c, 12);
            body.addView(bar, lp);
            dlg = makeDialog(c, makePanel(c, title, body), false);
            dlg.show();
        }

        public void setMessage(String s) { msg.setText(s); }

        /** Sets a determinate bar. total 0 or less shows the bouncing block. */
        public void setProgress(int done, int total) {
            bar.set(total <= 0 ? -1 : Math.round(100f * done / total), true);
        }

        public void dismiss() { if (dlg.isShowing()) dlg.dismiss(); }
    }
}
