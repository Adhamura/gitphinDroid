package io.github.adhamura.gitphindroid.ui.pixel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Pixel-art icon from a "W:hex" spec (3 hex digits per row for W=12, MSB = leftmost pixel). */
public final class PixelIcon extends View {
    public static final String USB = "12:1f81681683fc2042043fc060060060";
    public static final String WIFI = "12:3fcc031f86060f0108000060060";
    public static final String PHONE = "12:1f81081081081081081081f81681f8";
    /** Check mark, 12x7. */
    public static final String CHECK = "12:00600c018630360" + "1c0080";
    /** Filled triangles, 12x6. */
    public static final String CHEV_DOWN = "12:fff7fe3fc1f80f0060";
    public static final String CHEV_UP = "12:0600f01f83fc7fefff";
    /** "<" arrow, 8x12. */
    public static final String BACK = "8:03060c1830606030180c0603";

    private final Paint paint = new Paint();
    private final float density;
    private float scale; // px per icon pixel
    private final Context ctx;
    private int w, rows;
    private boolean[] bits = new boolean[0];

    public PixelIcon(Context c) {
        super(c);
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        scale = UiScale.pixelScale(c, 1) * density;
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFF8200);
    }

    public void setIcon(String spec) {
        w = 0;
        rows = 0;
        bits = new boolean[0];
        try {
            int colon = spec.indexOf(':');
            int width = Integer.parseInt(spec.substring(0, colon));
            String hex = spec.substring(colon + 1);
            int per = (width + 3) / 4;
            int n = hex.length() / per;
            boolean[] b = new boolean[n * width];
            for (int r = 0; r < n; r++) {
                int v = Integer.parseInt(hex.substring(r * per, (r + 1) * per), 16);
                for (int x = 0; x < width; x++) {
                    b[r * width + x] = ((v >> (per * 4 - 1 - x)) & 1) != 0;
                }
            }
            w = width;
            rows = n;
            bits = b;
        } catch (RuntimeException ignored) {
            // bad spec: draw nothing
        }
        requestLayout();
        invalidate();
    }

    /** dp per icon pixel. */
    public void setScale(float dpPerPixel) {
        scale = UiScale.pixelScale(ctx, dpPerPixel) * density;
        requestLayout();
        invalidate();
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize(Math.round(w * scale), wSpec),
                resolveSize(Math.round(rows * scale), hSpec));
    }

    @Override protected void onDraw(Canvas cv) {
        for (int r = 0; r < rows; r++) {
            for (int x = 0; x < w; x++) {
                if (!bits[r * w + x]) continue;
                cv.drawRect(Math.round(x * scale), Math.round(r * scale),
                        Math.round((x + 1) * scale), Math.round((r + 1) * scale), paint);
            }
        }
    }
}
