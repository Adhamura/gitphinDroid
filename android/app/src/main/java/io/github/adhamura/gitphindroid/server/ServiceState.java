package io.github.adhamura.gitphindroid.server;

import io.github.adhamura.gitphindroid.widget.GitphinTileService;
import io.github.adhamura.gitphindroid.widget.GitphinWidget;

import android.content.Context;

/** Short state text shared by the notification, the tile and the widget. */
public final class ServiceState {
    private ServiceState() {}

    /** "Pushing 45%" while a transfer shows, else null. */
    static volatile String progTitle;
    /** "usb", "tether", "wifi", "phone" or "". */
    public static volatile String via = "";

    public static String label() {
        switch (GitphinService.state) {
            case GitphinService.STATE_RUNNING: return progTitle != null ? progTitle : "Running";
            case GitphinService.STATE_STARTING: return "Starting";
            case GitphinService.STATE_ERROR: return "Error";
            default: return "Stopped";
        }
    }

    static String viaLabel(String v) {
        switch (v == null ? "" : v) {
            case "usb": case "tether": return "USB";
            case "wifi": return "Wi-Fi";
            case "phone": return "Phone";
            default: return "";
        }
    }

    /** "Running · USB" style text for the tile. */
    public static String longLabel() {
        String s = label();
        String v = viaLabel(via);
        if (GitphinService.state == GitphinService.STATE_RUNNING && progTitle == null && !v.isEmpty()) {
            s += " · " + v;
        }
        return s;
    }

    static void changed(Context c, boolean force) {
        GitphinTileService.refresh(c);
        GitphinWidget.requestUpdate(c, force);
    }
}
