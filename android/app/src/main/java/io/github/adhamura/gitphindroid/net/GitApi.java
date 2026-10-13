package io.github.adhamura.gitphindroid.net;

import io.github.adhamura.gitphindroid.data.Prefs;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Small client for the local Gitphin HTTP API on the loopback address. Call it off the main thread. */
public final class GitApi {
    /** Answer of one call. ok is true only for HTTP 2xx with "ok":true. */
    public static final class Result {
        public boolean ok;
        public int code;
        public JSONObject json;   // null when the answer was not JSON
        public String error = ""; // text for the user when not ok
    }

    private final String base;

    public GitApi(Context c) {
        base = "http://127.0.0.1:" + Prefs.getPort(c);
    }

    /** URL-encodes one query value (UTF-8, space as %20). */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            return s; // UTF-8 always exists
        }
    }

    public Result get(String pathAndQuery) {
        return call("GET", pathAndQuery, null);
    }

    public Result post(String pathAndQuery, byte[] body) {
        return call("POST", pathAndQuery, body);
    }

    private Result call(String method, String pathAndQuery, byte[] body) {
        Result r = new Result();
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL(base + pathAndQuery).openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(60000);
            h.setRequestMethod(method);
            h.setUseCaches(false);
            if (body != null) {
                h.setDoOutput(true);
                h.setFixedLengthStreamingMode(body.length);
                h.setRequestProperty("Content-Type", "application/octet-stream");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(body);
                }
            }
            r.code = h.getResponseCode();
            InputStream in = r.code >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = in == null ? "" : readAll(in);
            try {
                r.json = new JSONObject(text);
            } catch (Exception ignored) {
                r.json = null;
            }
            boolean jsonOk = r.json != null && r.json.optBoolean("ok", false);
            r.ok = r.code >= 200 && r.code < 300 && jsonOk;
            if (!r.ok) {
                String e = r.json != null ? r.json.optString("error", "") : "";
                r.error = !e.isEmpty() ? e : ("HTTP " + r.code);
            }
        } catch (IOException e) {
            r.ok = false;
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
        return r;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
