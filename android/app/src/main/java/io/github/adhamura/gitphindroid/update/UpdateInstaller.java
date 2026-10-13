package io.github.adhamura.gitphindroid.update;

import io.github.adhamura.gitphindroid.ui.pixel.PixelToast;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Downloads the APK of a release and hands it to the system installer. */
public final class UpdateInstaller {
    /** Called on the downloading thread; done and total are in bytes (total 0 = unknown). */
    public interface Progress { void onProgress(long done, long total); }

    /** Called when the installer reports a result (on the main thread). */
    public interface ResultListener { void onResult(boolean ok, String message); }

    static final String ACTION_RESULT = "io.github.adhamura.gitphindroid.UPDATE_RESULT";
    private static final int TIMEOUT_MS = 15_000;
    private static volatile ResultListener listener;

    private UpdateInstaller() {}

    public static void setResultListener(ResultListener l) { listener = l; }

    static File apkFile(Context c) { return new File(c.getCacheDir(), "update.apk"); }

    /** Deletes a left-over download. */
    public static void cleanup(Context c) {
        File f = apkFile(c);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /** Downloads the release APK to getCacheDir()/update.apk and checks its size. Blocking. */
    public static File download(Context c, ReleaseInfo r, Progress p) throws IOException {
        if (r.apkUrl == null) throw new IOException("This release has no APK file.");
        File out = apkFile(c);
        HttpURLConnection con = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        try {
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("User-Agent", "gitphin-droid");
            con.setRequestProperty("Accept", "application/octet-stream");
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("The download failed: HTTP " + code + ".");
            long total = r.apkSize > 0 ? r.apkSize : con.getContentLengthLong();
            long done = 0;
            try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                long lastTick = 0;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && now - lastTick > 100) {
                        lastTick = now;
                        p.onProgress(done, total);
                    }
                }
            }
            if (p != null) p.onProgress(done, total);
            if (done == 0 || (r.apkSize > 0 && done != r.apkSize)) {
                out.delete();
                throw new IOException("The download is incomplete (" + done + " of " + r.apkSize + " bytes).");
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        } finally {
            con.disconnect();
        }
        return out;
    }

    /** Needs the "install unknown apps" permission on API 26 and up. */
    public static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    /** Starts the install session. The result comes to {@link InstallResultReceiver}. Blocking. */
    public static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("gitphin.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                s.fsync(os);
            }
            Intent i = new Intent(c, InstallResultReceiver.class).setAction(ACTION_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (RuntimeException ignored) { }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Cannot start the installation: " + e.getMessage());
        }
    }

    static void reportResult(Context c, boolean ok, String message) {
        if (!ok) cleanup(c);
        ResultListener l = listener;
        if (l != null) l.onResult(ok, message);
        else if (!ok) PixelToast.show(c, message);
    }
}
