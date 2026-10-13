package io.github.adhamura.gitphindroid.widget;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

/** Home-screen widget: state, last activity, start/stop button. */
public class GitphinWidget extends AppWidgetProvider {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MIN_GAP = 2000;
    private static long lastAt;
    private static boolean pending;

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) {
        update(c, m, id);
    }

    /** Redraws all widgets; without force at most every 2 s. */
    public static void requestUpdate(Context c, boolean force) {
        final Context app = c.getApplicationContext();
        synchronized (GitphinWidget.class) {
            long now = System.currentTimeMillis();
            if (!force && now - lastAt < MIN_GAP) {
                if (!pending) {
                    pending = true;
                    MAIN.postDelayed(() -> {
                        synchronized (GitphinWidget.class) { pending = false; }
                        requestUpdate(app, true);
                    }, MIN_GAP - (now - lastAt));
                }
                return;
            }
            lastAt = now;
        }
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(app);
            int[] ids = m.getAppWidgetIds(new ComponentName(app, GitphinWidget.class));
            for (int id : ids) update(app, m, id);
        } catch (RuntimeException ignored) {
            // widget host not ready
        }
    }

    private static void update(Context c, AppWidgetManager m, int id) {
        try {
            Bundle o = m.getAppWidgetOptions(id);
            int wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180);
            int hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60);
            Bitmap bmp = WidgetRenderer.render(c, wDp, hDp);
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_gitphin);
            rv.setImageViewBitmap(R.id.w_img, bmp);
            rv.setOnClickPendingIntent(R.id.w_img, PendingIntent.getActivity(c, 10,
                    new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            rv.setOnClickPendingIntent(R.id.w_btn, toggleIntent(c));
            m.updateAppWidget(id, rv);
        } catch (RuntimeException ignored) {
            // keep the old picture
        }
    }

    private static PendingIntent toggleIntent(Context c) {
        int st = GitphinService.state;
        boolean on = st == GitphinService.STATE_RUNNING || st == GitphinService.STATE_STARTING;
        Intent i = new Intent(c, GitphinService.class)
                .setAction(on ? GitphinService.ACTION_STOP : GitphinService.ACTION_START);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 11, i, flags)
                : PendingIntent.getService(c, 11, i, flags);
    }
}
