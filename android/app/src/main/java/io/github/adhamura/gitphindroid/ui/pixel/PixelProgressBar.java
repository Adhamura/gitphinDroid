package io.github.adhamura.gitphindroid.ui.pixel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Square pixel progress bar: a frame with a fill, or a bouncing block when indeterminate. */
public final class PixelProgressBar extends View {
    private final Paint paint = new Paint();
    private final float density;
    private final float uf;
    private final int heightDp;
    private int pct;            // 0..100, -1 = indeterminate
    private boolean live = true;

    public PixelProgressBar(Context c, int heightDp, int color) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        uf = UiScale.factor(c);
        this.heightDp = heightDp;
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    /** pct 0..100, or -1 when the size is unknown; live false draws a full bar. */
    public void set(int pct, boolean live) {
        if (pct == this.pct && live == this.live) return;
        this.pct = pct;
        this.live = live;
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize(0, wSpec), Math.round(heightDp * uf * density));
    }

    @Override protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int f = Math.max(1, Math.round((heightDp >= 8 ? 2 : 1) * uf * density));
        cv.drawRect(0, 0, w, f, paint);
        cv.drawRect(0, h - f, w, h, paint);
        cv.drawRect(0, f, f, h - f, paint);
        cv.drawRect(w - f, f, w, h - f, paint);
        int gap = heightDp >= 8 ? Math.max(1, Math.round(density)) : 0;
        int in = f + gap;
        int x0 = in, x1 = w - in, y0 = in, y1 = h - in;
        if (x1 <= x0 || y1 <= y0) return;
        if (!live) {
            cv.drawRect(x0, y0, x1, y1, paint);
        } else if (pct >= 0) {
            int fw = Math.round((x1 - x0) * Math.min(100, pct) / 100f);
            if (fw > 0) cv.drawRect(x0, y0, x0 + fw, y1, paint);
        } else {
            int bw = Math.min(Math.round(24 * density), x1 - x0);
            long t = SystemClock.uptimeMillis() % 3000; // 1.5 s per pass
            float p = t < 1500 ? t / 1500f : (3000 - t) / 1500f;
            int bx = x0 + Math.round((x1 - x0 - bw) * p);
            cv.drawRect(bx, y0, bx + bw, y1, paint);
            if (isShown()) postInvalidateOnAnimation();
        }
    }
}
