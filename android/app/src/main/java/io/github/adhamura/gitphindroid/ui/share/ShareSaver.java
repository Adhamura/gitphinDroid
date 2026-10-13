package io.github.adhamura.gitphindroid.ui.share;

import android.content.ContentResolver;

import io.github.adhamura.gitphindroid.net.GitApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The background commit loop of the share screen: one commit per file, with progress and one retry on a moved branch. */
final class ShareSaver {
    static final String ZERO = "0000000000000000000000000000000000000000";

    /** The screen the saver reports to. */
    interface Host {
        void ui(Runnable r);
        boolean isGone();
        void progress(String text, int percent);
        void finished(String repo, String folder, int saved, int total, List<String> failures);
    }

    private final GitApi api;
    private final ContentResolver cr;
    private final List<ShareItems.Item> usable;
    private final Map<String, String> tips;
    private final Host host;

    ShareSaver(GitApi api, ContentResolver cr, List<ShareItems.Item> usable, Map<String, String> tips, Host host) {
        this.api = api;
        this.cr = cr;
        this.usable = usable;
        this.tips = tips;
        this.host = host;
    }

    /** Cleans the folder text. Returns null when it is not allowed. */
    static String cleanFolder(String f) {
        StringBuilder sb = new StringBuilder();
        for (String seg : f.trim().replace('\\', '/').split("/")) {
            String s = seg.trim();
            if (s.isEmpty()) continue;
            if (s.equals("..") || s.equals(".")) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    static String uniqueName(String n, Set<String> seen) {
        if (!seen.contains(n)) return n;
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : "";
        for (int k = 2; ; k++) {
            String c = stem + "-" + k + ext;
            if (!seen.contains(c)) return c;
        }
    }

    void run(String theRepo, String theBranch, String folder, List<String> paths,
             List<String> names, String message, String author, String email) {
        int total = usable.size(), ok = 0;
        List<String> failures = new ArrayList<>();
        String tip = tips.containsKey(theBranch) ? tips.get(theBranch) : ZERO;
        for (int i = 0; i < total && !host.isGone(); i++) {
            final int n = i + 1;
            final String shown = names.get(i);
            host.ui(() -> host.progress("Saving " + n + "/" + total + ": " + shown,
                    Math.round(100f * (n - 1) / total)));
            byte[] data;
            try {
                data = ShareItems.bytesOf(cr, usable.get(i));
            } catch (Exception e) {
                failures.add(shown + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            String q = "/api/git/commit-file?name=" + GitApi.enc(theRepo) + "&branch=" + GitApi.enc(theBranch)
                    + "&old=" + tip + "&path=" + GitApi.enc(paths.get(i))
                    + (message.isEmpty() ? "" : "&message=" + GitApi.enc(message))
                    + (author.isEmpty() ? "" : "&author=" + GitApi.enc(author))
                    + (email.isEmpty() ? "" : "&email=" + GitApi.enc(email));
            GitApi.Result r = api.post(q, data);
            if (!r.ok && r.code == 409) {
                // the branch moved: read the new tip and try once more
                String fresh = freshTip(theRepo, theBranch);
                if (fresh != null) {
                    tip = fresh;
                    q = q.replaceFirst("&old=[0-9a-f]{40}", "&old=" + tip);
                    r = api.post(q, data);
                }
            }
            if (r.ok) {
                ok++;
                String c = r.json.optString("commit", "");
                tip = c.length() == 40 ? c : orElse(freshTip(theRepo, theBranch), tip);
            } else {
                failures.add(shown + ": " + r.error);
            }
        }
        tips.put(theBranch, tip);
        final int saved = ok;
        host.ui(() -> host.finished(theRepo, folder, saved, total, failures));
    }

    private static String orElse(String a, String b) {
        return a != null ? a : b;
    }

    /** Current tip of the branch (zero sha when the branch does not exist); null on error. */
    private String freshTip(String theRepo, String theBranch) {
        GitApi.Result r = api.get("/api/git/refs?name=" + GitApi.enc(theRepo));
        if (!r.ok) return null;
        JSONArray arr = r.json.optJSONArray("refs");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && ("refs/heads/" + theBranch).equals(o.optString("name"))) {
                    return o.optString("sha", ZERO);
                }
            }
        }
        return ZERO;
    }
}
