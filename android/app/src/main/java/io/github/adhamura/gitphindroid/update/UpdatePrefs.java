package io.github.adhamura.gitphindroid.update;

import android.content.Context;
import android.content.SharedPreferences;

/** Update settings and state: "gitphin_update". */
public final class UpdatePrefs {
    private static final String NAME = "gitphin_update";
    static final long DAY_MS = 24L * 60 * 60 * 1000;

    private UpdatePrefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean getAuto(Context c) { return sp(c).getBoolean("auto", true); }

    public static void setAuto(Context c, boolean on) { sp(c).edit().putBoolean("auto", on).apply(); }

    static long getLastCheck(Context c) { return sp(c).getLong("last_check", 0); }

    static void setLastCheck(Context c, long t) { sp(c).edit().putLong("last_check", t).apply(); }

    static String getSkipped(Context c) { return sp(c).getString("skipped", ""); }

    static void setSkipped(Context c, String version) { sp(c).edit().putString("skipped", version).apply(); }
}
