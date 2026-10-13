package io.github.adhamura.gitphindroid.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

public final class Prefs {
    static final int DEFAULT_PORT = 8080;
    private static final String NAME = "gitphin";
    private static final String CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int getPort(Context c) {
        int p = sp(c).getInt("port", DEFAULT_PORT);
        return (p < 1024 || p > 65535) ? DEFAULT_PORT : p;
    }

    public static boolean getLan(Context c) {
        return sp(c).getBoolean("lan", false);
    }

    static final String DEFAULT_USER = "gitphin";

    /** Does Wi-Fi access ask for a user name and password? (false = open) */
    public static boolean getWifiAuth(Context c) {
        return sp(c).getBoolean("wifi_auth", true);
    }

    public static String getUser(Context c) {
        String u = sp(c).getString("user", null);
        return (u == null || u.isEmpty()) ? DEFAULT_USER : u;
    }

    /** Returns the stored password; creates one on first run. */
    public static synchronized String getPassword(Context c) {
        String pw = sp(c).getString("password", null);
        if (pw == null || pw.isEmpty()) {
            pw = generatePassword();
            sp(c).edit().putString("password", pw).apply();
        }
        return pw;
    }

    /** Repos folder; "" = the app's own folder. */
    public static String getGitDir(Context c) {
        return sp(c).getString("git_dir", "");
    }

    public static void setGitDir(Context c, String path) {
        sp(c).edit().putString("git_dir", path == null ? "" : path).apply();
    }

    /** Start the server when the phone boots (default on). */
    public static boolean getStartOnBoot(Context c) {
        return sp(c).getBoolean("start_on_boot", true);
    }

    public static void setStartOnBoot(Context c, boolean on) {
        sp(c).edit().putBoolean("start_on_boot", on).apply();
    }

    static final String DEFAULT_MDNS_NAME = "gitphin";

    /** Host name for mDNS ("<name>.local"): a-z, 0-9, -. */
    public static String getMdnsName(Context c) {
        String n = sp(c).getString("mdns_name", null);
        return (n == null || !n.matches("[a-z0-9-]{1,30}")) ? DEFAULT_MDNS_NAME : n;
    }

    public static void setMdnsName(Context c, String name) {
        sp(c).edit().putString("mdns_name", name).apply();
    }

    public static void save(Context c, int port, boolean lan, boolean wifiAuth, String user, String password) {
        sp(c).edit().putInt("port", port).putBoolean("lan", lan).putBoolean("wifi_auth", wifiAuth)
                .putString("user", user).putString("password", password).apply();
    }

    /** Webhook URLs (a JSON array string in the "webhooks" pref). */
    public static java.util.List<String> getWebhooks(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(sp(c).getString("webhooks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                String u = a.optString(i, "");
                if (!u.isEmpty() && !out.contains(u)) out.add(u);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static void setWebhooks(Context c, java.util.List<String> urls) {
        sp(c).edit().putString("webhooks", new org.json.JSONArray(urls).toString()).apply();
    }

    /** HMAC secret for the X-Gitphin-Signature-256 header; "" = no signature. */
    public static String getWebhookSecret(Context c) {
        return sp(c).getString("webhook_secret", "");
    }

    public static void setWebhookSecret(Context c, String secret) {
        sp(c).edit().putString("webhook_secret", secret == null ? "" : secret).apply();
    }

    /** Last delivery per URL: a JSON object {url: {"t": ms, "code": http code or 0, "err": "", "try": n}}. */
    public static String getWebhookStatus(Context c) {
        return sp(c).getString("webhook_status", "{}");
    }

    public static void setWebhookStatus(Context c, String json) {
        sp(c).edit().putString("webhook_status", json).apply();
    }

    /** Has the first-start guide been shown? */
    public static boolean getOnboarded(Context c) {
        return sp(c).getBoolean("onboarded", false);
    }

    public static void setOnboarded(Context c, boolean v) {
        sp(c).edit().putBoolean("onboarded", v).apply();
    }

    /** Is the address block of the main top bar open (true) or folded to one line (false)? */
    public static boolean getBarExpanded(Context c) {
        return sp(c).getBoolean("bar_expanded", false);
    }

    public static void setBarExpanded(Context c, boolean v) {
        sp(c).edit().putBoolean("bar_expanded", v).apply();
    }

    /** "normal" (default) or "large". */
    public static String getUiSize(Context c) {
        return "large".equals(sp(c).getString("ui_size", "normal")) ? "large" : "normal";
    }

    public static void setUiSize(Context c, String size) {
        sp(c).edit().putString("ui_size", "large".equals(size) ? "large" : "normal").apply();
    }

    /** Did the user tick "Autostart done" in the guide (Xiaomi/HyperOS)? Cannot be detected. */
    public static boolean getAutostartDone(Context c) {
        return sp(c).getBoolean("autostart_done", false);
    }

    public static void setAutostartDone(Context c, boolean v) {
        sp(c).edit().putBoolean("autostart_done", v).apply();
    }

    public static void setLan(Context c, boolean on) {
        sp(c).edit().putBoolean("lan", on).apply();
    }

    public static String generatePassword() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) sb.append(CHARS.charAt(r.nextInt(CHARS.length())));
        return sb.toString();
    }
}
