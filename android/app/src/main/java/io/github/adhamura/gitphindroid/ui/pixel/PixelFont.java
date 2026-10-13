package io.github.adhamura.gitphindroid.ui.pixel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The 5x8 pixel font of the web page (PXFONT in index.html), drawn with one rect per pixel. */
public final class PixelFont {
    private PixelFont() {}

    private static String data; // null until loaded
    private static boolean tried;

    public static synchronized boolean load(Context c) {
        if (data != null) return true;
        if (tried) return false;
        tried = true;
        try (InputStream in = c.getApplicationContext().getAssets().open("webui/index.html")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            Matcher m = Pattern.compile("const PXFONT = \"([^\"]+)\"").matcher(bo.toString("UTF-8"));
            if (m.find() && m.group(1).length() >= 95 * 8) data = m.group(1);
        } catch (Exception ignored) {
            // fall back to the system font
        }
        return data != null;
    }

    /** Rows of one glyph (8 values of 5 bits). */
    private static int[] rows(char ch) {
        int code = ch - 32;
        if (code < 0 || code > 94) code = 31;
        int[] r = new int[8];
        for (int i = 0; i < 8; i++) r[i] = Character.digit(data.charAt(code * 8 + i), 32) & 31;
        return r;
    }

    /** Width of the text in font pixels (the height is always 8). */
    public static int width(String t) {
        if (data == null) return 1;
        return Math.max(1, run(t, null, 0, 0, 0, null) - 1);
    }

    /** Draws the text with its top-left corner at (x0, y0); scale is px per font pixel. */
    public static void draw(Canvas cv, String t, float x0, float y0, float scale, Paint p) {
        if (data == null) return;
        run(t, cv, x0, y0, scale, p);
    }

    private static int run(String t, Canvas cv, float x0, float y0, float s, Paint p) {
        int x = 0;
        for (int k = 0; k < t.length(); ) {
            int cp = t.codePointAt(k);
            k += Character.charCount(cp);
            if (cp == ' ') { x += 3; continue; }
            int[] r = rows(cp > 0xFFFF ? '￿' : (char) cp);
            int lo = 5, hi = -1;
            for (int row : r) {
                for (int b = 0; b < 5; b++) {
                    if ((row >> (4 - b) & 1) != 0) { lo = Math.min(lo, b); hi = Math.max(hi, b); }
                }
            }
            if (hi < 0) { x += 3; continue; }
            if (cv != null) {
                for (int y = 0; y < 8; y++) {
                    for (int b = lo; b <= hi; b++) {
                        if ((r[y] >> (4 - b) & 1) != 0) {
                            float px = x0 + (x + b - lo) * s, py = y0 + y * s;
                            cv.drawRect(px, py, px + s, py + s, p);
                        }
                    }
                }
            }
            x += hi - lo + 2;
        }
        return x;
    }
}
