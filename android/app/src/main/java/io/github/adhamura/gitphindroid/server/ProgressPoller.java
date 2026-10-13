package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.data.ActivityLog;
import io.github.adhamura.gitphindroid.data.Prefs;
import io.github.adhamura.gitphindroid.net.NetUtil;

import android.app.NotificationManager;
import android.content.Context;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the native status, shows transfer progress and logs each finished transfer. */
final class ProgressPoller {
    private final Context ctx;
    private String lastTitle, lastText;
    private boolean shown;
    // the transfer in progress
    private String seenState = "", seenLine = "", seenVia = "", seenRepo = "";
    private boolean seenLive, recorded;

    private static final Pattern GIT_PATH = Pattern.compile("/git/([^/?\\s]+?)(?:\\.git)?(?:/|\\?|\\s|$)");
    private static final Pattern API_NAME = Pattern.compile("/api/git/[^\\s]*[?&]name=([^&\\s]+)");

    ProgressPoller(Context c) { ctx = c; }

    /** True while the progress shows in the notification (it then owns the notification). */
    boolean isShowing() { return shown; }

    void reset() { shown = false; lastTitle = lastText = null; }

    static String repoOf(String last) {
        if (last == null) return "";
        Matcher m = API_NAME.matcher(last);
        if (m.find()) return m.group(1);
        m = GIT_PATH.matcher(last);
        return m.find() ? m.group(1) : "";
    }

    /** Returns true while a transfer is shown. */
    boolean poll() {
        JSONObject st = null, pr = null;
        try {
            st = new JSONObject(NativeServer.nativeStatus());
            pr = st.optJSONObject("prog");
        } catch (Exception | Error ignored) {
            // status not readable: treat as idle
        }
        String via = st == null ? "" : st.optString("via", "");
        boolean viaChanged = !via.equals(ServiceState.via);
        ServiceState.via = via;
        boolean show = pr != null && pr.optBoolean("show", false);
        if (!show) {
            endTransfer();
            if (shown) {
                shown = false;
                lastTitle = lastText = null;
                ServiceState.progTitle = null;
                notifyBase();
                ServiceState.changed(ctx, false);
            } else if (viaChanged) {
                ServiceState.changed(ctx, false);
            }
            return false;
        }
        shown = true;
        boolean live = pr.optBoolean("live", false);
        int pct = pr.optInt("pct", -1);
        String rate = pr.optString("rate", "");
        String state = pr.optString("state", "");
        String line = pr.optString("line", "");
        track(state, line, via, repoOf(st.optString("last", "")), live);
        String title = state + (pct >= 0 ? " " + pct + "%" : "");
        String text = line + (rate.isEmpty() ? "" : " · " + rate);
        String v = ServiceState.viaLabel(via);
        if (!v.isEmpty()) text += " · via " + v;
        String key = live + "|" + pct;
        if (title.equals(lastTitle) && (text + key).equals(lastText)) return true;
        lastTitle = title;
        lastText = text + key;
        ServiceState.progTitle = title;
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(
                ServiceNotifications.NOTIF_ID, ServiceNotifications.progress(ctx, title, text, live, pct));
        ServiceState.changed(ctx, false);
        return true;
    }

    private void notifyBase() {
        int port = Prefs.getPort(ctx);
        String text = NetUtil.addressLines(ctx, port, Prefs.getLan(ctx), false);
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(
                ServiceNotifications.NOTIF_ID, ServiceNotifications.ongoing(ctx, text));
    }

    private void track(String state, String line, String via, String repo, boolean live) {
        if (seenLive && !live && !recorded) {
            record(); // live -> not live: "Done"
        }
        if (live && recorded) { // a new transfer in the same display
            recorded = false;
            seenRepo = "";
        }
        seenState = state;
        seenLine = line;
        if (!via.isEmpty()) seenVia = via;
        if (!repo.isEmpty()) seenRepo = repo;
        seenLive = live;
    }

    private void endTransfer() {
        if (!recorded && !seenState.isEmpty()) record();
        seenState = seenLine = seenVia = seenRepo = "";
        seenLive = false;
        recorded = false;
    }

    private void record() {
        recorded = true;
        ActivityLog.transfer(ctx, seenState, seenLine, seenVia, seenRepo);
    }
}
