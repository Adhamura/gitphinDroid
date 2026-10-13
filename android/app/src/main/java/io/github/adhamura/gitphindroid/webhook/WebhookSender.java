package io.github.adhamura.gitphindroid.webhook;

import io.github.adhamura.gitphindroid.data.Prefs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends the server's events (push, repo_created, repo_deleted) to the webhook URLs of the settings.
 * One background thread. Push events of one repo that come within 2 s are merged into one
 * (first "before", last "after" per ref). Delivery: 10 s timeouts, 3 tries with 2 / 8 / 30 s backoff.
 * The body is signed like GitHub does when a secret is set: X-Gitphin-Signature-256: sha256=HMAC.
 */
public final class WebhookSender {
    static final long DEBOUNCE_MS = 2000;
    private static final long MAX_HOLD_MS = 10000; // a steady stream still goes out after this
    private static final long[] BACKOFF_S = {2, 8, 30};
    private static final int TIMEOUT_MS = 10000;
    private static final String ZERO = "0000000000000000000000000000000000000000";

    private static WebhookSender instance;

    public static synchronized WebhookSender get(Context c) {
        if (instance == null) instance = new WebhookSender(c.getApplicationContext());
        return instance;
    }

    /** Result of the last delivery to one URL. */
    public static final class Status {
        public long time;      // ms since epoch, 0 = never sent
        public int code;       // HTTP status, 0 = no answer
        public String error = "";
        public int tries;      // tries used so far for the last event
        public boolean retrying;

        public boolean ok() { return code >= 200 && code < 300; }
    }

    private static final class Pending {
        final Map<String, String[]> refs = new LinkedHashMap<>();
        final List<JSONObject> heads = new ArrayList<>();
        String via = "";
        long first;
        ScheduledFuture<?> timer;
    }

    private final Context ctx;
    private final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "webhook-sender");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Pending> pending = new HashMap<>(); // repo -> merged push (ex thread only)
    private final Map<String, Status> status = new HashMap<>();

    private WebhookSender(Context c) {
        ctx = c;
        ex.setRemoveOnCancelPolicy(true);
        try {
            JSONObject o = new JSONObject(Prefs.getWebhookStatus(c));
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String u = it.next();
                JSONObject e = o.getJSONObject(u);
                Status s = new Status();
                s.time = e.optLong("t");
                s.code = e.optInt("code");
                s.error = e.optString("err", "");
                s.tries = e.optInt("try");
                status.put(u, s);
            }
        } catch (Exception ignored) { }
    }

    /** From the C worker thread: parse and hand over, never blocks. */
    public void enqueue(String json) {
        try {
            ex.execute(() -> handle(json));
        } catch (RuntimeException ignored) { }
    }

    private void handle(String json) {
        try {
            JSONObject e = new JSONObject(json);
            if (Prefs.getWebhooks(ctx).isEmpty()) return;
            if (!"push".equals(e.optString("event"))) {
                deliver(e.toString(), e.optString("event"), UUID.randomUUID().toString());
                return;
            }
            String repo = e.optString("repo");
            Pending p = pending.get(repo);
            long now = System.currentTimeMillis();
            if (p == null) {
                p = new Pending();
                p.first = now;
                pending.put(repo, p);
            }
            JSONArray refs = e.optJSONArray("refs");
            for (int i = 0; refs != null && i < refs.length(); i++) {
                JSONObject r = refs.getJSONObject(i);
                String name = r.getString("ref");
                String[] ba = p.refs.get(name);
                if (ba == null) p.refs.put(name, new String[] {r.getString("before"), r.getString("after")});
                else ba[1] = r.getString("after");
            }
            JSONObject h = e.optJSONObject("head_commit");
            if (h != null) p.heads.add(h);
            p.via = e.optString("via", p.via);
            if (p.timer != null) p.timer.cancel(false);
            long wait = Math.max(0, Math.min(DEBOUNCE_MS, p.first + MAX_HOLD_MS - now));
            p.timer = ex.schedule(() -> flush(repo), wait, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) { }
    }

    private void flush(String repo) {
        Pending p = pending.remove(repo);
        if (p == null) return;
        try {
            JSONArray refs = new JSONArray();
            java.util.Set<String> afters = new java.util.HashSet<>();
            for (Map.Entry<String, String[]> en : p.refs.entrySet()) {
                String[] ba = en.getValue();
                if (ba[0].equals(ba[1])) continue; // changed and changed back
                JSONObject r = new JSONObject();
                r.put("ref", en.getKey());
                r.put("before", ba[0]);
                r.put("after", ba[1]);
                refs.put(r);
                afters.add(ba[1]);
            }
            if (refs.length() == 0) return;
            JSONObject e = new JSONObject();
            e.put("event", "push");
            e.put("repo", repo);
            e.put("refs", refs);
            for (int i = p.heads.size() - 1; i >= 0; i--) { // the newest commit that is a tip now
                if (afters.contains(p.heads.get(i).optString("id")) && !ZERO.equals(p.heads.get(i).optString("id"))) {
                    e.put("head_commit", p.heads.get(i));
                    break;
                }
            }
            e.put("via", p.via);
            e.put("server", "gitphin");
            e.put("time", System.currentTimeMillis() / 1000);
            deliver(e.toString(), "push", UUID.randomUUID().toString());
        } catch (Exception ignored) { }
    }

    private void deliver(String body, String event, String id) {
        for (String url : Prefs.getWebhooks(ctx)) attempt(url, body, event, id, 1);
    }

    /** One try; on failure the next one is scheduled (the thread is not blocked while waiting). */
    private void attempt(String url, String body, String event, String id, int n) {
        Status s = post(url, body, event, id, Prefs.getWebhookSecret(ctx));
        s.tries = n;
        boolean retry = !s.ok() && n < BACKOFF_S.length + 1 && !permanent(s.code);
        s.retrying = retry;
        record(url, s);
        if (retry) {
            try {
                ex.schedule(() -> attempt(url, body, event, id, n + 1), BACKOFF_S[n - 1], TimeUnit.SECONDS);
            } catch (RuntimeException ignored) { }
        }
    }

    private static boolean permanent(int code) {
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }

    /** A single POST (no retry). Never throws. */
    static Status post(String url, String body, String event, String id, String secret) {
        Status s = new Status();
        s.time = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "gitphin-droid");
            c.setRequestProperty("X-Gitphin-Event", event);
            c.setRequestProperty("X-Gitphin-Delivery", id);
            if (secret != null && !secret.isEmpty()) {
                c.setRequestProperty("X-Gitphin-Signature-256", "sha256=" + hmacHex(secret, data));
            }
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            s.code = c.getResponseCode();
            try { // drain, so the connection can be reused
                java.io.InputStream in = s.code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null) { byte[] buf = new byte[1024]; while (in.read(buf) >= 0) { /* skip */ } in.close(); }
            } catch (Exception ignored) { }
            if (!s.ok()) s.error = "HTTP " + s.code;
        } catch (Exception e) {
            s.code = 0;
            String m = e.getMessage();
            s.error = e.getClass().getSimpleName() + (m != null ? ": " + m : "");
        } finally {
            if (c != null) c.disconnect();
        }
        return s;
    }

    static String hmacHex(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private synchronized void record(String url, Status s) {
        status.put(url, s);
        try {
            JSONObject o = new JSONObject();
            List<String> urls = Prefs.getWebhooks(ctx);
            for (Map.Entry<String, Status> en : status.entrySet()) {
                if (!urls.contains(en.getKey())) continue;
                Status v = en.getValue();
                JSONObject e = new JSONObject();
                e.put("t", v.time);
                e.put("code", v.code);
                e.put("err", v.error);
                e.put("try", v.tries);
                o.put(en.getKey(), e);
            }
            Prefs.setWebhookStatus(ctx, o.toString());
        } catch (Exception ignored) { }
    }

    /** The last delivery to url, or null when none was made. */
    public synchronized Status lastResult(String url) {
        return status.get(url);
    }

    public synchronized void forget(String url) {
        status.remove(url);
        try {
            JSONObject o = new JSONObject(Prefs.getWebhookStatus(ctx));
            o.remove(url);
            Prefs.setWebhookStatus(ctx, o.toString());
        } catch (Exception ignored) { }
    }

    public interface TestDone {
        void done(List<String> urls, List<Status> results);
    }

    /** Sends {"event":"ping",...} to every URL now (one try each, in parallel); done runs on the main thread. */
    public void sendTest(TestDone done) {
        final List<String> urls = Prefs.getWebhooks(ctx);
        final String secret = Prefs.getWebhookSecret(ctx);
        new Thread(() -> {
            final List<Status> res = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) res.add(null);
            List<Thread> ts = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) {
                final int k = i;
                Thread t = new Thread(() -> {
                    String id = UUID.randomUUID().toString();
                    String body;
                    try {
                        JSONObject e = new JSONObject();
                        e.put("event", "ping");
                        e.put("server", "gitphin");
                        e.put("message", "Test from gitphinDroid");
                        e.put("time", System.currentTimeMillis() / 1000);
                        body = e.toString();
                    } catch (Exception x) {
                        body = "{\"event\":\"ping\"}";
                    }
                    Status s = post(urls.get(k), body, "ping", id, secret);
                    s.tries = 1;
                    record(urls.get(k), s);
                    synchronized (res) { res.set(k, s); }
                }, "webhook-test");
                t.setDaemon(true);
                t.start();
                ts.add(t);
            }
            for (Thread t : ts) {
                try { t.join(); } catch (InterruptedException ignored) { }
            }
            new Handler(Looper.getMainLooper()).post(() -> done.done(urls, res));
        }, "webhook-test-main").start();
    }
}
