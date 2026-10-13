package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.data.Prefs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

/** Starts the server after the phone boots or the app is updated, when the user wants it. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GitphinBoot";

    @Override public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
                // The server needs the app files; BOOT_COMPLETED follows when the user unlocks.
                if (um != null && !um.isUserUnlocked()) return;
            }
            if (!Prefs.getStartOnBoot(c)) return;
            Intent i = new Intent(c, GitphinService.class).setAction(GitphinService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "Cannot start the server: " + e);
        }
    }
}
