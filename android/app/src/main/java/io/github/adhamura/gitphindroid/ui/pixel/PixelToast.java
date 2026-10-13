package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Toast in the web page style. Falls back to a normal Toast when there is no live activity. */
public final class PixelToast {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View current;

    private PixelToast() {}

    public static void show(Context ctx, String text) {
        Context app = ctx.getApplicationContext();
        Runnable r = () -> showNow(ctx, app, text);
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static void showNow(Context ctx, Context app, String text) {
        Activity a = activityOf(ctx);
        // A finishing activity cannot show the toast long enough: use the system toast.
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
            return;
        }
        float d = a.getResources().getDisplayMetrics().density;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (current != null && current.getParent() instanceof ViewGroup) {
            ((ViewGroup) current.getParent()).removeView(current);
        }
        PixelPanel panel = new PixelPanel(a, 4);
        panel.setInnerPadding(12);
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(UiScale.sp(a, 14));
        t.setTextColor(a.getColor(R.color.px_fg));
        panel.addView(t, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int bottom = 0;
        WindowInsets ins = decor.getRootWindowInsets();
        if (ins != null) bottom = ins.getSystemWindowInsetBottom();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int side = Math.round(16 * d);
        lp.setMargins(side, 0, side, bottom + Math.round(24 * d));
        decor.addView(panel, lp);
        current = panel;
        MAIN.postDelayed(() -> {
            if (panel.getParent() instanceof ViewGroup) ((ViewGroup) panel.getParent()).removeView(panel);
            if (current == panel) current = null;
        }, 2500);
    }
}
