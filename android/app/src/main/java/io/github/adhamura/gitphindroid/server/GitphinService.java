package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.data.ActivityLog;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.MdnsResponder;
import io.github.adhamura.gitphindroid.net.NetUtil;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitphinService extends Service {
    public static final String ACTION_START = "io.github.adhamura.gitphindroid.START";
    public static final String ACTION_RESTART = "io.github.adhamura.gitphindroid.RESTART";
    public static final String ACTION_STOP = "io.github.adhamura.gitphindroid.STOP";

    public static final int STATE_STOPPED = 0, STATE_STARTING = 1, STATE_RUNNING = 2, STATE_ERROR = 3;
    /** State shared with the activity (same process). */
    public static volatile int state = STATE_STOPPED;
    public static volatile String error = null;

    private static final int NOTIF_ID = ServiceNotifications.NOTIF_ID;
    private static final int NOTIF_ERR_ID = ServiceNotifications.NOTIF_ERR_ID;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private boolean receiverRegistered;
    private MdnsResponder mdns;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressPoller poller;

    private final Runnable pollProg = new Runnable() {
        @Override public void run() {
            if (state != STATE_RUNNING) return; // stops; startServer posts it again
            boolean show = poller.poll();
            handler.postDelayed(this, show ? 500 : 2000);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                pushBattery(i);
            } else if (state == STATE_RUNNING) {
                updateNotification(); // cable in/out, tethering on/off: the addresses change
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NativeServer.appContext = getApplicationContext();
        poller = new ProgressPoller(getApplicationContext());
        ServiceNotifications.createChannels(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (NotificationActions.ACTION_COPY.equals(action)) {
            NotificationActions.copyUrl(this);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            // Must still satisfy startForegroundService().
            startFg(ServiceNotifications.ongoing(this, "Stopping..."));
            worker.execute(() -> {
                boolean was = state == STATE_RUNNING;
                stopNative();
                state = STATE_STOPPED;
                error = null;
                if (was) ActivityLog.server(getApplicationContext(), "Server stopped");
                stateChanged();
                stopForeground(true);
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        startFg(ServiceNotifications.ongoing(this, "Starting..."));
        final boolean restart = ACTION_RESTART.equals(action);
        worker.execute(() -> startServer(restart));
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        worker.execute(this::stopNative);
        worker.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- server

    private void startServer(boolean restart) {
        try {
            if (state == STATE_RUNNING && !restart && NativeServer.nativeIsRunning()) {
                updateNotification();
                return;
            }
            state = STATE_STARTING;
            stateChanged();
            error = null;
            stopNative();

            File ext = new File(getFilesDir(), "ext");
            File assetsDir = new File(ext, "apps_assets/gitphin");
            File gitDir = new File(ext, "apps_data/gitphin/git");
            assetsDir.mkdirs();
            gitDir.mkdirs();
            copyAssets(assetsDir);

            boolean lan = Prefs.getLan(this);
            int port = Prefs.getPort(this);
            int wifi = !lan ? NativeServer.WIFI_OFF
                    : (Prefs.getWifiAuth(this) ? NativeServer.WIFI_PASSWORD : NativeServer.WIFI_OPEN);
            boolean ask = wifi == NativeServer.WIFI_PASSWORD;
            String user = ask ? Prefs.getUser(this) : "";
            String pw = ask ? Prefs.getPassword(this) : "";

            registerBattery();
            try {
                NativeServer.nativeSetGitDir(Prefs.getGitDir(this));
            } catch (Throwable ignored) {
                // old library without this call: default folder
            }
            int rc = NativeServer.nativeStart(ext.getAbsolutePath(),
                    "0.0.0.0", port, wifi, user, pw); // USB tethering always; Wi-Fi by mode
            if (rc != 0) {
                fail(describeErrno(rc, port));
                return;
            }
            setLocks(lan);
            startMdns(lan, port);
            state = STATE_RUNNING;
            ActivityLog.server(getApplicationContext(), "Server started on port " + port);
            stateChanged();
            updateNotification();
            handler.removeCallbacks(pollProg);
            handler.postDelayed(pollProg, 500);
        } catch (Throwable t) {
            fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void startMdns(boolean lan, int port) {
        stopMdns();
        if (!lan) return;
        try {
            mdns = new MdnsResponder(this, Prefs.getMdnsName(this), port);
            mdns.start();
        } catch (Throwable t) {
            android.util.Log.w("Gitphin", "mDNS not started: " + t);
            mdns = null;
        }
    }

    private void stopMdns() {
        MdnsResponder m = mdns;
        mdns = null;
        if (m == null) return;
        try { m.stop(); } catch (Throwable ignored) { }
    }

    private void stopNative() {
        stopMdns();
        try {
            if (NativeServer.nativeIsRunning()) NativeServer.nativeStop();
        } catch (Throwable ignored) {
            // library missing: nothing to stop
        }
        releaseLocks();
        unregisterBattery();
    }

    private void fail(String msg) {
        stopMdns();
        error = msg;
        state = STATE_ERROR;
        ActivityLog.server(getApplicationContext(), "Error: " + msg);
        stateChanged();
        releaseLocks();
        unregisterBattery();
        stopForeground(true);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification n = ServiceNotifications.error(this, msg);
        nm.notify(NOTIF_ERR_ID, n);
        stopSelf();
    }

    private static String describeErrno(int rc, int port) {
        int e = -rc;
        if (e == OsConstants.EADDRINUSE) return "Port " + port + " is already in use. Choose another port in Settings.";
        if (e == OsConstants.EACCES) return "No permission to use port " + port + ".";
        String s;
        try { s = Os.strerror(e); } catch (Throwable t) { s = "error"; }
        return "Start failed: " + s + " (errno " + e + ")";
    }

    // ---------------------------------------------------------------- assets

    private void copyAssets(File dir) throws IOException {
        String stamp;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            stamp = code + ":" + pi.lastUpdateTime;
        } catch (Exception e) {
            stamp = "unknown";
        }
        File marker = new File(dir, ".assets_version");
        String old = readSmall(marker);
        boolean force = !stamp.equals(old);
        boolean ok = true;
        // asset name -> file name (see copyWebUi in app/build.gradle)
        String[][] files = {{"webui/index.html", "index.html"}, {"webui/index.html.gzip", "index.html.gz"}};
        for (String[] pair : files) {
            String name = pair[1];
            File dst = new File(dir, name);
            if (!force && dst.exists()) continue;
            try (InputStream in = getAssets().open(pair[0])) {
                File tmp = new File(dir, name + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (!tmp.renameTo(dst)) {
                    dst.delete();
                    tmp.renameTo(dst);
                }
            } catch (java.io.FileNotFoundException missing) {
                dst.delete(); // asset not shipped: do not leave a stale copy
            }
        }
        if (ok) {
            try (OutputStream out = new FileOutputStream(marker)) {
                out.write(stamp.getBytes("UTF-8"));
            }
        }
    }

    private static String readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[256];
            int n = in.read(b);
            return n <= 0 ? "" : new String(b, 0, n, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- battery

    private void registerBattery() {
        if (receiverRegistered) return;
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        receiverRegistered = true;
        pushBattery(sticky);
        registerReceiver(batteryReceiver, new IntentFilter("android.hardware.usb.action.USB_STATE"));
    }

    private void unregisterBattery() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(batteryReceiver); } catch (RuntimeException ignored) { }
        receiverRegistered = false;
    }

    private void pushBattery(Intent i) {
        int pct = 100;
        boolean charging = false;
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) pct = level * 100 / scale;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
        }
        try {
            NativeServer.nativeSetDevice(Build.MANUFACTURER + " " + Build.MODEL,
                    "Android " + Build.VERSION.RELEASE, pct, charging);
        } catch (Throwable ignored) {
            // library missing
        }
    }

    // ---------------------------------------------------------------- locks

    private void setLocks(boolean lan) {
        releaseLocks();
        if (!lan) return;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "gitphin:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gitphin:cpu");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wifiLock != null) { if (wifiLock.isHeld()) wifiLock.release(); wifiLock = null; }
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
    }

    // ---------------------------------------------------------------- notification

    private void updateNotification() {
        if (poller.isShowing()) return; // a transfer owns the notification; the poller restores it
        int port = Prefs.getPort(this);
        String text = NetUtil.addressLines(this, port, Prefs.getLan(this), false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, ServiceNotifications.ongoing(this, text));
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stateChanged() {
        ServiceState.progTitle = null;
        if (state != STATE_RUNNING) ServiceState.via = "";
        ServiceState.changed(getApplicationContext(), true);
    }
}
