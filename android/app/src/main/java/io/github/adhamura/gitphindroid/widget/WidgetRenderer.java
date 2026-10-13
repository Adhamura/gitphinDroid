package io.github.adhamura.gitphindroid.widget;

import io.github.adhamura.gitphindroid.data.ActivityLog;
import io.github.adhamura.gitphindroid.server.GitphinService;
import io.github.adhamura.gitphindroid.server.ServiceState;
import io.github.adhamura.gitphindroid.ui.pixel.PixelFont;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Calendar;
import java.util.Locale;

/** Draws the widget picture in the pixel look (RemoteViews cannot use custom views). */
public final class WidgetRenderer {
    private WidgetRenderer() {}

    private static final int ORANGE = 0xFFFF8200, LIGHT = 0xFFFFD9A8, MUTED = 0xFFA08A6A, BLACK = 0xFF000000;
    private static final int BTN_DP = 56;

    static Bitmap render(Context c, int wDp, int hDp) {
        float d = c.getResources().getDisplayMetrics().density;
        PixelFont.load(c);
        int w = Math.max(1, Math.round(wDp * d)), h = Math.max(1, Math.round(hDp * d));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setStyle(Paint.Style.FILL);
        p.setColor(BLACK);
        cv.drawRect(0, 0, w, h, p);
        float b = Math.max(1, Math.round(2 * d));
        p.setColor(ORANGE);
        cv.drawRect(0, 0, w, b, p);
        cv.drawRect(0, h - b, w, h, p);
        cv.drawRect(0, 0, b, h, p);
        cv.drawRect(w - b, 0, w, h, p);

        float pad = Math.round(8 * d);
        float btnW = Math.round(BTN_DP * d);
        float mainW = w - btnW;
        float top = pad + Math.max(0, (h - 60 * d) / 2f);
        int s2 = Math.max(1, Math.round(2 * d)), s1 = Math.max(1, Math.round(d));

        p.setColor(ORANGE);
        PixelFont.draw(cv, "Gitphin", pad, top, s2, p);
        p.setColor(LIGHT);
        PixelFont.draw(cv, ServiceState.label(), pad, top + 8 * s2 + 4 * d, s2, p);

        ActivityLog.Entry last = ActivityLog.latest(c);
        String via = last != null && !last.via.isEmpty() ? last.via : ServiceState.via;
        drawIcon(cv, iconFor(via), mainW - pad - 12 * s2 * 0.75f, top, Math.max(1, Math.round(1.5f * d)), p);
        p.setColor(MUTED);
        String line = last == null ? "No activity yet" : lineOf(last);
        float y = top + 16 * s2 + 10 * d;
        PixelFont.draw(cv, fit(line, (mainW - 2 * pad) / s1), pad, y, s1, p);

        // button
        float bx = w - btnW + 2 * d, by = b + 4 * d, bw = btnW - 6 * d, bh = h - 2 * b - 8 * d;
        boolean on = GitphinService.state == GitphinService.STATE_RUNNING
                || GitphinService.state == GitphinService.STATE_STARTING;
        p.setColor(on ? LIGHT : ORANGE);
        cv.drawRect(bx, by, bx + bw, by + bh, p);
        String lab = on ? "Stop" : "Start";
        int sb = Math.max(1, Math.round(1.5f * d));
        p.setColor(BLACK);
        PixelFont.draw(cv, lab, Math.round(bx + (bw - PixelFont.width(lab) * sb) / 2f),
                Math.round(by + (bh - 8 * sb) / 2f), sb, p);
        return bmp;
    }

    static String lineOf(ActivityLog.Entry e) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(e.time);
        String t = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        String what = e.kind + (e.repo.isEmpty() ? "" : " " + e.repo);
        if ("server".equals(e.kind) && !e.text.isEmpty()) what = e.text;
        return what + " - " + t;
    }

    /** Shortens with ".." until the pixel text fits the given width in font pixels. */
    private static String fit(String s, float fontPx) {
        String t = s;
        while (t.length() > 3 && PixelFont.width(t) > fontPx) {
            s = s.substring(0, s.length() - 1);
            t = s + "..";
        }
        return t;
    }

    public static String iconFor(String via) {
        switch (via == null ? "" : via) {
            case "usb": case "tether": return PixelIcon.USB;
            case "wifi": return PixelIcon.WIFI;
            default: return PixelIcon.PHONE;
        }
    }

    /** Draws a "W:hex" icon spec with the top-left at (x0, y0), s px per icon pixel. */
    static void drawIcon(Canvas cv, String spec, float x0, float y0, float s, Paint p) {
        try {
            int colon = spec.indexOf(':');
            int width = Integer.parseInt(spec.substring(0, colon));
            String hex = spec.substring(colon + 1);
            int per = (width + 3) / 4;
            int n = hex.length() / per;
            p.setColor(ORANGE);
            for (int r = 0; r < n; r++) {
                int v = Integer.parseInt(hex.substring(r * per, (r + 1) * per), 16);
                for (int x = 0; x < width; x++) {
                    if (((v >> (per * 4 - 1 - x)) & 1) == 0) continue;
                    cv.drawRect(x0 + x * s, y0 + r * s, x0 + (x + 1) * s, y0 + (r + 1) * s, p);
                }
            }
        } catch (RuntimeException ignored) {
            // bad spec: draw nothing
        }
    }
}
