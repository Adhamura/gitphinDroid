package io.github.adhamura.gitphindroid.update;

import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import android.app.Activity;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry points of the update check: the automatic one (MainActivity) and the manual one (Settings). */
public final class UpdateFlow {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private UpdateFlow() {}

    /** Checks at most once per 24 hours, silently. Shows a dialog only when a new version exists. */
    public static void autoCheck(Activity a) {
        if (!UpdatePrefs.getAuto(a)) return;
        long now = System.currentTimeMillis();
        long last = UpdatePrefs.getLastCheck(a);
        if (last != 0 && now - last < UpdatePrefs.DAY_MS && now >= last) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        UpdateInstaller.cleanup(a);
        new Thread(() -> {
            try {
                ReleaseInfo r = UpdateChecker.checkForNewer(a);
                UpdatePrefs.setLastCheck(a, System.currentTimeMillis());
                if (r != null && !r.version.equals(UpdatePrefs.getSkipped(a))) {
                    a.runOnUiThread(() -> UpdateDialogs.offer(a, r));
                }
            } catch (IOException | RuntimeException ignored) {
                // offline or GitHub down: stay silent, try again at the next start
            } finally {
                RUNNING.set(false);
            }
        }, "gitphin-update-check").start();
    }

    /** The "Check for updates" button: always answers (toast or dialog). */
    public static void manualCheck(Activity a) {
        if (!RUNNING.compareAndSet(false, true)) {
            PixelToast.show(a, "Already checking...");
            return;
        }
        PixelToast.show(a, "Checking for updates...");
        new Thread(() -> {
            try {
                ReleaseInfo r = UpdateChecker.checkForNewer(a);
                UpdatePrefs.setLastCheck(a, System.currentTimeMillis());
                a.runOnUiThread(() -> {
                    if (r == null) PixelToast.show(a, "You have the newest version");
                    else UpdateDialogs.offer(a, r);
                });
            } catch (IOException | RuntimeException e) {
                String why = e.getMessage() == null ? "no connection" : e.getMessage();
                a.runOnUiThread(() -> PixelToast.show(a, "Cannot check for updates: " + why));
            } finally {
                RUNNING.set(false);
            }
        }, "gitphin-update-check").start();
    }
}
