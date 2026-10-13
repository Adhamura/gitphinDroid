package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Builds the notifications of GitphinService. */
final class ServiceNotifications {
    private ServiceNotifications() {}

    static final String CHANNEL = "server";
    static final int NOTIF_ID = 1, NOTIF_ERR_ID = 2;

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        PushNotifier.ensureChannel(c);
    }

    static Notification.Builder base(Context c, String title, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c).setPriority(Notification.PRIORITY_LOW);
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(R.drawable.ic_stat_gitphin)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false);
    }

    /** The ongoing notification; the content intent opens the app. */
    static Notification ongoing(Context c, String text) {
        Notification.Builder b = base(c, c.getString(R.string.notif_title), text).setOngoing(true);
        NotificationActions.add(c, b);
        return b.build();
    }

    static Notification error(Context c, String msg) {
        return base(c, "Gitphin could not start", msg).build();
    }

    /** Transfer progress in the ongoing notification. */
    static Notification progress(Context c, String title, String text, boolean live, int pct) {
        Notification.Builder b = base(c, title, text).setOngoing(true).setStyle(null);
        if (live) b.setProgress(100, Math.max(0, pct), pct < 0);
        else b.setProgress(0, 0, false);
        NotificationActions.add(c, b);
        return b.build();
    }
}
