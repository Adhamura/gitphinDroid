package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.ActivityLog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The "Pushed to <repo>" notification, one per repo, merged within 5 s windows. */
public final class PushNotifier {
    private PushNotifier() {}

    static final String CHANNEL = "pushes";
    private static final long WINDOW = 5000;

    private static final class State {
        long last;
        final Set<String> refs = new LinkedHashSet<>();
        String firstRef = "", msg = "";
    }

    private static final Map<String, State> STATES = new HashMap<>();

    public static String branchOf(String ref) {
        if (ref.startsWith("refs/heads/")) return ref.substring(11);
        if (ref.startsWith("refs/tags/")) return ref.substring(10);
        return ref;
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Pushes", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        ch.enableVibration(false);
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    public static void post(Context c, String repo, JSONArray refs, String msg) {
        try {
            if (repo == null || repo.isEmpty()) return;
            Context app = c.getApplicationContext();
            String first = "";
            int n = 0;
            State s;
            synchronized (STATES) {
                s = STATES.get(repo);
                long now = System.currentTimeMillis();
                if (s == null || now - s.last > WINDOW) {
                    s = new State();
                    STATES.put(repo, s);
                    s.msg = msg;
                }
                s.last = now;
                if (refs != null) {
                    for (int i = 0; i < refs.length(); i++) {
                        String r = refs.getJSONObject(i).optString("ref");
                        if (s.refs.isEmpty()) s.firstRef = r;
                        s.refs.add(r);
                    }
                }
                first = s.firstRef;
                n = s.refs.size();
            }
            boolean tag = first.startsWith("refs/tags/");
            String branch = branchOf(first);
            String text = (tag ? "tag " : "") + branch + (s.msg.isEmpty() ? "" : ": " + s.msg);
            if (n > 1) text += " and " + (n - 1) + " more refs";
            int id = 1000 + Math.abs(repo.hashCode() % 100000);
            String path = "/#repos/" + repo + (branch.isEmpty() || tag ? "" : "/commits/" + branch);
            PendingIntent pi = PendingIntent.getActivity(app, id, ActivityLog.openIntent(app, path),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            ensureChannel(app);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
            b.setSmallIcon(R.drawable.ic_stat_gitphin)
                    .setContentTitle("Pushed to " + repo)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true);
            ((NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (Throwable ignored) {
            // no permission or bad data: skip
        }
    }
}
