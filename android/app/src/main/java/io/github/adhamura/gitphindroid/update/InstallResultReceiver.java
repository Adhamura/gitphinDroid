package io.github.adhamura.gitphindroid.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Gets the result of the install session. Not exported: only our own PendingIntent reaches it. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @SuppressWarnings("deprecation")
    @Override public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    UpdateInstaller.reportResult(c, false, "The system gave no install prompt.");
                    return;
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    c.startActivity(confirm);
                } catch (RuntimeException e) {
                    UpdateInstaller.reportResult(c, false, "Cannot open the install prompt: " + e.getMessage());
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                UpdateInstaller.reportResult(c, true, "Updated.");
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                UpdateInstaller.reportResult(c, false, "The installation was cancelled.");
                break;
            default:
                UpdateInstaller.reportResult(c, false,
                        "The installation failed" + (msg == null || msg.isEmpty() ? "." : ": " + msg));
        }
    }
}
