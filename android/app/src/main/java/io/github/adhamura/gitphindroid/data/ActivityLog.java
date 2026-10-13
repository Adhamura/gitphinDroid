package io.github.adhamura.gitphindroid.data;

import io.github.adhamura.gitphindroid.server.PushNotifier;
import io.github.adhamura.gitphindroid.ui.main.MainActivity;
import io.github.adhamura.gitphindroid.widget.GitphinWidget;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thread-safe list of recent activity, newest first, kept in the "gitphin_activity" preferences. */
public final class ActivityLog {
    private ActivityLog() {}

    static final String PREFS = "gitphin_activity";
    private static final String KEY = "log";
    private static final int MAX = 200;
    private static final long MAX_AGE = 30L * 24 * 3600 * 1000;
    private static final long MERGE_MS = 5000;

    public static final class Entry {
        public long time;
        public String kind = "", repo = "", via = "", text = "";
        public int objects;
        public long bytes;
    }

    private static final Object LOCK = new Object();
    private static List<Entry> list;
    /** Grows with each change; the screen polls it. */
    public static volatile long version;

    private static final Pattern OBJ = Pattern.compile("(\\d+)\\s*obj");
    private static final Pattern SIZE = Pattern.compile("([\\d.]+)\\s*(B|KB|MB|GB|KiB|MiB|GiB)\\b",
            Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- recording

    public static void server(Context c, String text) {
        Entry e = new Entry();
        e.kind = "server";
        e.text = text;
        add(c, e);
    }

    /** A finished transfer. state is Pushing / Pulling / Transfer; line is like "12 obj, 340 KB". */
    public static void transfer(Context c, String state, String line, String via, String repo) {
        Entry e = new Entry();
        String s = state == null ? "" : state.toLowerCase();
        e.kind = s.startsWith("push") ? "push" : s.startsWith("pull") ? "pull" : "upload";
        e.repo = repo == null ? "" : repo;
        e.via = via == null ? "" : via;
        if (line != null) {
            Matcher m = OBJ.matcher(line);
            if (m.find()) e.objects = Integer.parseInt(m.group(1));
            m = SIZE.matcher(line);
            if (m.find()) {
                try {
                    double v = Double.parseDouble(m.group(1));
                    String u = m.group(2).toUpperCase();
                    double mul = u.startsWith("G") ? 1073741824.0 : u.startsWith("M") ? 1048576.0
                            : u.startsWith("K") ? 1024.0 : 1.0;
                    e.bytes = (long) (v * mul);
                } catch (NumberFormatException ignored) { }
            }
        }
        add(c, e);
    }

    /** A webhook event from the native server. Logs the push and posts the notification. */
    public static void onEvent(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (!"push".equals(o.optString("event"))) return;
            String repo = o.optString("repo");
            JSONArray refs = o.optJSONArray("refs");
            JSONObject head = o.optJSONObject("head_commit");
            Entry e = new Entry();
            e.kind = "push";
            e.repo = repo;
            e.via = o.optString("via", "");
            String msg = head == null ? "" : firstLine(head.optString("message"));
            if (refs != null && refs.length() > 0) {
                JSONObject r = refs.getJSONObject(0);
                String t = PushNotifier.branchOf(r.optString("ref")) + ": "
                        + shortId(r.optString("before")) + " -> " + shortId(r.optString("after"));
                if (!msg.isEmpty()) t += " | " + msg;
                if (refs.length() > 1) t += " +" + (refs.length() - 1) + " more refs";
                e.text = t;
            } else {
                e.text = msg;
            }
            add(c, e);
            PushNotifier.post(c, repo, refs, msg);
        } catch (Throwable ignored) {
            // bad event: ignore
        }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i >= 0 ? s.substring(0, i) : s).trim();
    }

    private static String shortId(String id) {
        if (id == null || id.isEmpty() || id.matches("0+")) return "new";
        return id.length() > 7 ? id.substring(0, 7) : id;
    }

    // ---------------------------------------------------------------- storage

    static void add(Context c, Entry e) {
        Context app = c.getApplicationContext();
        if (e.time == 0) e.time = System.currentTimeMillis();
        synchronized (LOCK) {
            load(app);
            if (!merge(e)) list.add(0, e);
            prune();
            save(app);
            version++;
        }
        GitphinWidget.requestUpdate(app, false);
    }

    /** A push event and its transfer land within seconds: keep one entry. */
    private static boolean merge(Entry e) {
        if (!"push".equals(e.kind) || e.repo.isEmpty()) return false;
        for (int i = 0; i < list.size() && i < 5; i++) {
            Entry o = list.get(i);
            if (Math.abs(e.time - o.time) > MERGE_MS) break;
            if (!"push".equals(o.kind) || !o.repo.equals(e.repo)) continue;
            if (o.text.isEmpty() == e.text.isEmpty()) continue;
            if (o.text.isEmpty()) o.text = e.text;
            if (o.via.isEmpty()) o.via = e.via;
            o.objects = Math.max(o.objects, e.objects);
            o.bytes = Math.max(o.bytes, e.bytes);
            return true;
        }
        return false;
    }

    private static void prune() {
        long min = System.currentTimeMillis() - MAX_AGE;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).time < min || i >= MAX) list.remove(i);
        }
    }

    private static void load(Context c) {
        if (list != null) return;
        list = new ArrayList<>();
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.time = o.optLong("time");
                e.kind = o.optString("kind");
                e.repo = o.optString("repo");
                e.via = o.optString("via");
                e.text = o.optString("text");
                e.objects = o.optInt("objects");
                e.bytes = o.optLong("bytes");
                list.add(e);
            }
        } catch (Exception ignored) {
            // damaged data: start empty
        }
    }

    private static void save(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("time", e.time);
                o.put("kind", e.kind);
                o.put("repo", e.repo);
                o.put("via", e.via);
                o.put("text", e.text);
                o.put("objects", e.objects);
                o.put("bytes", e.bytes);
                a.put(o);
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply();
        } catch (Exception ignored) {
            // not saved
        }
    }

    public static List<Entry> all(Context c) {
        synchronized (LOCK) {
            load(c.getApplicationContext());
            prune();
            return new ArrayList<>(list);
        }
    }

    public static Entry latest(Context c) {
        List<Entry> l = all(c);
        return l.isEmpty() ? null : l.get(0);
    }

    public static void clear(Context c) {
        Context app = c.getApplicationContext();
        synchronized (LOCK) {
            load(app);
            list.clear();
            SharedPreferences.Editor ed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            ed.remove(KEY).apply();
            version++;
        }
        GitphinWidget.requestUpdate(app, false);
    }

    // ---------------------------------------------------------------- helpers

    public static String size(long b) {
        if (b <= 0) return "";
        if (b < 1024) return b + " B";
        if (b < 1048576) return (b / 1024) + " KB";
        if (b < 1073741824L) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /** Intent that opens MainActivity at a page of the web UI. */
    public static Intent openIntent(Context c, String path) {
        return new Intent(c, MainActivity.class).putExtra("open_path", path)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
