package io.github.adhamura.gitphindroid.ui.pixel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Text in the pixel font. */
public final class PixelTextView extends View {
    private final Paint paint = new Paint();
    private String text = "";
    private float scale; // px per font pixel
    private float baseDp = 1;
    private final Context ctx;
    private final float density;
    private boolean ok;

    public PixelTextView(Context c) {
        super(c);
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        scale = UiScale.pixelScale(c, 1) * density;
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        ok = PixelFont.load(c);
    }

    public void setText(String t) {
        text = t == null ? "" : t;
        setContentDescription(text);
        requestLayout();
        invalidate();
    }

    /** dp per font pixel. */
    public void setScale(float dpPerPixel) {
        baseDp = dpPerPixel;
        scale = UiScale.pixelScale(ctx, baseDp) * density;
        requestLayout();
        invalidate();
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w, h;
        if (ok) {
            w = Math.round(PixelFont.width(text) * scale);
            h = Math.round(8 * scale);
        } else {
            paint.setTextSize(8 * scale * 1.4f);
            w = Math.round(paint.measureText(text));
            h = Math.round(8 * scale);
        }
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override protected void onDraw(Canvas cv) {
        if (ok) {
            PixelFont.draw(cv, text, 0, 0, scale, paint);
        } else {
            paint.setAntiAlias(true);
            paint.setTextSize(8 * scale * 1.4f);
            cv.drawText(text, 0, 7 * scale, paint);
            paint.setAntiAlias(false);
        }
    }
}
