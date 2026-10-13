package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.data.Prefs;

import android.content.Context;

/** The "UI size" setting (Prefs ui_size): normal or large. All Pixel* views follow it. */
public final class UiScale {
    private UiScale() {}

    public static boolean large(Context c) {
        return "large".equals(Prefs.getUiSize(c));
    }

    /** Size of boxes, buttons, bars: 1.0 or 1.5. */
    public static float factor(Context c) {
        return large(c) ? 1.5f : 1f;
    }

    /** Pixel font scale (dp per font pixel) for a base scale: always whole numbers
     *  (1 to 2, 2 to 3, 3 to 4) so the pixels stay crisp. */
    static float pixelScale(Context c, float base) {
        return large(c) ? base + Math.max(1, (int) (base / 2)) : base;
    }

    /** Size of system-font text: +25% when large. */
    public static float sp(Context c, float sp) {
        return large(c) ? sp * 1.25f : sp;
    }

    /** Is the screen 600dp or more wide? */
    public static boolean wide(Context c) {
        return c.getResources().getConfiguration().screenWidthDp >= 600;
    }
}
