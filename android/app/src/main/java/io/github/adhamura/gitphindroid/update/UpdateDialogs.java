package io.github.adhamura.gitphindroid.update;

import io.github.adhamura.gitphindroid.ui.pixel.PixelDialog;
import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;

/** The dialogs of the update flow: offer, permission, download, error. */
final class UpdateDialogs {
    private UpdateDialogs() {}

    static void offer(Activity a, ReleaseInfo r) {
        if (a.isFinishing() || a.isDestroyed()) return;
        String notes = ReleaseNotes.plain(r.notes, 10);
        String msg = "Gitphin " + r.version + " (you have " + UpdateChecker.installedVersion(a) + ")"
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        PixelDialog.Builder b = PixelDialog.with(a).title("Update available").message(msg).stacked(true);
        if (r.apkUrl != null) b.primary("Download and install", () -> start(a, r));
        else b.primary("Open release page", () -> openPage(a, r.pageUrl));
        b.button("Skip this version", () -> UpdatePrefs.setSkipped(a, r.version));
        b.button("Later", null);
        b.show();
    }

    /** Checks the install permission, then downloads and installs. */
    private static void start(Activity a, ReleaseInfo r) {
        if (!UpdateInstaller.canInstall(a)) {
            PixelDialog.with(a).title("Allow installing")
                    .message("Android needs your OK before Gitphin can install its own update. "
                            + "Turn on \"Allow from this source\" on the next screen, go back, and tap "
                            + "\"Check for updates\" again.")
                    .primary("Open settings", () -> openUnknownSources(a))
                    .button("Cancel", null).show();
            return;
        }
        PixelDialog.Progress prog = PixelDialog.progress(a, "Downloading update");
        prog.setMessage("Gitphin " + r.version);
        new Thread(() -> {
            try {
                File apk = UpdateInstaller.download(a, r, (done, total) -> a.runOnUiThread(() -> {
                    prog.setProgress((int) Math.min(done, Integer.MAX_VALUE),
                            (int) Math.min(total, Integer.MAX_VALUE));
                    prog.setMessage("Gitphin " + r.version + ": " + (done / 1024) + " KB"
                            + (total > 0 ? " of " + (total / 1024) + " KB" : ""));
                }));
                a.runOnUiThread(() -> installNow(a, r, apk, prog));
            } catch (IOException e) {
                a.runOnUiThread(() -> {
                    prog.dismiss();
                    error(a, r.pageUrl, e.getMessage());
                });
            }
        }, "gitphin-update-download").start();
    }

    private static void installNow(Activity a, ReleaseInfo r, File apk, PixelDialog.Progress prog) {
        prog.setMessage("Starting the installer...");
        prog.setProgress(0, 0);
        UpdateInstaller.setResultListener((ok, message) -> {
            prog.dismiss();
            if (ok) return;
            if (!a.isFinishing() && !a.isDestroyed()) error(a, r.pageUrl, message);
            else PixelToast.show(a, message);
        });
        new Thread(() -> {
            try {
                UpdateInstaller.install(a, apk);
                // The system shows its prompt; the dialog closes when the receiver reports.
                a.runOnUiThread(() -> new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(prog::dismiss, 3000));
            } catch (IOException e) {
                a.runOnUiThread(() -> {
                    prog.dismiss();
                    error(a, r.pageUrl, e.getMessage());
                });
            }
        }, "gitphin-update-install").start();
    }

    static void error(Activity a, String pageUrl, String message) {
        if (a.isFinishing() || a.isDestroyed()) return;
        PixelDialog.with(a).title("Update failed")
                .message(message == null ? "Unknown error." : message)
                .primary("Open release page", () -> openPage(a, pageUrl))
                .button("Close", null).show();
    }

    static void openPage(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url == null ? UpdateChecker.RELEASES_PAGE : url)));
        } catch (RuntimeException e) {
            PixelToast.show(a, "Cannot open the browser.");
        }
    }

    private static void openUnknownSources(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (RuntimeException e) {
            PixelToast.show(a, "Cannot open the settings.");
        }
    }
}
