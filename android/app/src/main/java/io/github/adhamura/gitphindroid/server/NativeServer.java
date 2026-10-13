package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.data.ActivityLog;
import io.github.adhamura.gitphindroid.webhook.WebhookSender;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public final class NativeServer {
    static { System.loadLibrary("gitphin"); }

    private NativeServer() {}

    /** wifi: WIFI_OFF (Wi-Fi clients get 403), WIFI_PASSWORD (HTTP Basic with user and password;
     *  user "" = any user name) or WIFI_OPEN (no sign-in). Loopback and USB tethering never need a sign-in. */
    static final int WIFI_OFF = 0, WIFI_PASSWORD = 1, WIFI_OPEN = 2;

    /** root: absolute dir that the server calls "/ext". Returns 0 when started, else a negative errno. */
    static native int nativeStart(String root, String bindAddr, int port, int wifi,
                                  String user, String password);

    /** Repos folder; "" = default. Call before nativeStart. */
    static native void nativeSetGitDir(String path);

    /** Blocks until the worker thread ended. */
    public static native void nativeStop();

    public static native boolean nativeIsRunning();

    /** JSON: {"running":true,"requests":12,"last":"GET /api/info","activity":0,"bind":"127.0.0.1","port":8080} */
    public static native String nativeStatus();

    static native void nativeSetDevice(String name, String version, int batteryPct, boolean charging);

    /** Set by GitphinService.onCreate, read by native code through collect(). */
    static volatile Context appContext;

    /** Called from native code (the worker thread) for each webhook event; only queues it. Never throws. */
    static void onEvent(String json) {
        try {
            Context ctx = appContext;
            if (ctx != null && json != null) {
                try { ActivityLog.onEvent(ctx, json); } catch (Throwable ignored) { }
                WebhookSender.get(ctx).enqueue(json);
            }
        } catch (Throwable ignored) { }
    }

    /** Called from native code (any thread). kind = "info" or "wifi". Returns JSON text; never throws, never returns null. */
    static String collect(String kind) {
        Context ctx = appContext;
        if (ctx == null) return "{}";
        JSONObject o = new JSONObject();
        try {
            if ("wifi".equals(kind)) collectWifi(ctx, o);
            else if ("info".equals(kind)) collectInfo(ctx, o);
        } catch (Throwable t) {
            // return what we have
        }
        return o.toString();
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Throwable ignored) { }
    }

    private static void collectInfo(Context ctx, JSONObject o) {
        String host = null;
        try { host = Settings.Global.getString(ctx.getContentResolver(), "device_name"); } catch (Throwable ignored) { }
        if (host == null || host.isEmpty()) host = Build.MANUFACTURER + " " + Build.MODEL;
        put(o, "hostname", host);
        put(o, "os", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        put(o, "kernel", System.getProperty("os.version"));
        put(o, "arch", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        put(o, "cpu_count", Runtime.getRuntime().availableProcessors());
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            put(o, "mem_total", mi.totalMem);
            put(o, "mem_available", mi.availMem);
        } catch (Throwable ignored) { }
        put(o, "uptime_s", SystemClock.elapsedRealtime() / 1000);
        put(o, "user", "");

        JSONArray ips = new JSONArray();
        put(o, "ips", ips);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    JSONObject e = new JSONObject();
                    e.put("iface", ni.getName());
                    e.put("cidr", ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength());
                    ips.put(e);
                }
            }
        } catch (Throwable ignored) { }

        JSONArray conns = new JSONArray();
        put(o, "connections", conns);
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            String ssid = wifiSsid(ctx);
            for (Network n : cm.getAllNetworks()) {
                try {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null) continue;
                    String type, name;
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { type = "vpn"; name = "VPN"; }
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        type = "802-11-wireless"; name = ssid != null ? ssid : "Wi-Fi";
                    }
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { type = "802-3-ethernet"; name = "Ethernet"; }
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) { type = "mobile"; name = "Mobile data"; }
                    else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) { type = "bluetooth"; name = "Bluetooth"; }
                    else { type = "usb"; name = "USB tethering"; }
                    LinkProperties lp = cm.getLinkProperties(n);
                    String dev = lp != null && lp.getInterfaceName() != null ? lp.getInterfaceName() : "";
                    if (type.equals("usb") && !dev.isEmpty() && !(dev.startsWith("rndis") || dev.startsWith("usb") || dev.startsWith("ncm"))) {
                        name = "Network";
                    }
                    JSONObject e = new JSONObject();
                    e.put("name", name);
                    e.put("type", type);
                    e.put("device", dev);
                    conns.put(e);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    /** SSID without quotes, or null when unknown. */
    @SuppressWarnings("deprecation")
    private static String wifiSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return null;
            String s = wi.getSSID();
            if (s == null || s.isEmpty() || s.equals("<unknown ssid>") || s.equals("0x")) return null;
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static void collectWifi(Context ctx, JSONObject o) throws Exception {
        boolean connected = false;
        WifiInfo wi = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { connected = true; break; }
            }
            if (connected) {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                wi = wm.getConnectionInfo();
                if (wi == null || wi.getNetworkId() == -1 && wi.getRssi() <= -127) connected = false;
            }
        } catch (Throwable t) {
            connected = false;
        }
        if (!connected || wi == null) {
            o.put("connected", false);
            o.put("note", "Wi-Fi is not connected.");
            return;
        }
        o.put("connected", true);
        String ssid = wifiSsid(ctx);
        o.put("ssid", ssid == null ? JSONObject.NULL : ssid);
        int pct = Math.max(0, Math.min(100, 2 * (wi.getRssi() + 100)));
        o.put("signal", String.valueOf(pct));
        o.put("rate", wi.getLinkSpeed() + " Mb/s");
        o.put("password", JSONObject.NULL);
        o.put("password_source", JSONObject.NULL);
        String note;
        if (ssid != null) {
            note = "Android does not let apps read saved Wi-Fi passwords. To share it, use Settings > Wi-Fi > Share (QR code).";
        } else if (hasLocation(ctx)) {
            note = "Turn on location to show the Wi-Fi name.";
        } else {
            note = "Allow location for Gitphin to show the Wi-Fi name (Gitphin Settings > Collector).";
        }
        o.put("note", note);
    }

    public static boolean hasLocation(Context ctx) {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            || ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
