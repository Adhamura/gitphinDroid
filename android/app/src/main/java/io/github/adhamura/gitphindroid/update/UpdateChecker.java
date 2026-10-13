package io.github.adhamura.gitphindroid.update;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Asks GitHub for the latest release of the app. Blocking: call it from a background thread. */
public final class UpdateChecker {
    static final String REPO = "Adhamura/gitphinDroid";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 512 * 1024;

    private UpdateChecker() {}

    /** The version of the installed app, for example "1.0.0". */
    public static String installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "0" : pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Returns the newest release if it is newer than the installed app, else null. */
    public static ReleaseInfo checkForNewer(Context c) throws IOException {
        ReleaseInfo r = fetchLatest();
        return Semver.isNewer(r.version, installedVersion(c)) ? r : null;
    }

    static ReleaseInfo fetchLatest() throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(API_URL).openConnection();
        try {
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "gitphin-droid");
            int code = con.getResponseCode();
            if (code == 404) throw new IOException("No release found on GitHub.");
            if (code == 403 || code == 429) throw new IOException("GitHub says: too many requests. Try later.");
            if (code != 200) throw new IOException("GitHub answered HTTP " + code + ".");
            return parse(readAll(con.getInputStream()));
        } finally {
            con.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("The answer from GitHub is too large.");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static ReleaseInfo parse(String json) throws IOException {
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.optString("tag_name", "");
            if (Semver.parse(tag) == null) throw new IOException("The release tag \"" + tag + "\" is not a version.");
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            String apkUrl = null;
            long size = 0;
            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                String url = a.optString("browser_download_url", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk") && url.startsWith("https://")) {
                    apkUrl = url;
                    size = a.optLong("size", 0);
                    break;
                }
            }
            String page = o.optString("html_url", RELEASES_PAGE);
            if (!page.startsWith("https://")) page = RELEASES_PAGE;
            return new ReleaseInfo(version, o.optString("body", ""), page, apkUrl, size);
        } catch (JSONException e) {
            throw new IOException("GitHub sent an answer that the app cannot read.");
        }
    }
}
