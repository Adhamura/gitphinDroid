package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.NetUtil;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** The Stop / Copy URL / QR actions of the server notification. */
final class NotificationActions {
    private NotificationActions() {}

    static final String ACTION_COPY = "io.github.adhamura.gitphindroid.COPY_URL";

    static void add(Context c, Notification.Builder b) {
        PendingIntent stop = PendingIntent.getService(c, 2,
                new Intent(c, GitphinService.class).setAction(GitphinService.ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent copy = PendingIntent.getService(c, 3,
                new Intent(c, GitphinService.class).setAction(ACTION_COPY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent qr = PendingIntent.getActivity(c, 4,
                new Intent(c, MainActivity.class).putExtra("show_qr", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.addAction(new Notification.Action.Builder(null, c.getString(R.string.notif_stop), stop).build());
        b.addAction(new Notification.Action.Builder(null, c.getString(R.string.notif_copy), copy).build());
        b.addAction(new Notification.Action.Builder(null, c.getString(R.string.notif_qr), qr).build());
    }

    /** Wi-Fi URL when Wi-Fi access is on and an address exists, else the local one. */
    static String bestUrl(Context c) {
        int port = Prefs.getPort(c);
        boolean lan = Prefs.getLan(c);
        if (lan) {
            for (String[] r : NetUtil.addressRows(c, port, true, false)) {
                if (r[0].startsWith("PC ") && !r[0].equals("PC USB") && !r[0].equals("PC tether")
                        && r[1].startsWith("http")) return r[1] + "/";
            }
        }
        return "http://127.0.0.1:" + port + "/";
    }

    static void copyUrl(Context c) {
        try {
            String url = bestUrl(c);
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Gitphin URL", url));
            Toast.makeText(c, "Copied " + url, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // clipboard not available
        }
    }
}
