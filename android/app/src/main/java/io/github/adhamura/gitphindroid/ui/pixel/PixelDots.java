package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Step indicator: one square dot per step, the current one filled. */
public final class PixelDots extends View {
    private final Paint paint = new Paint();
    private final float d, f;
    private final int cOn, cOff;
    private int count = 1, current;

    public PixelDots(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        f = UiScale.factor(c);
        cOn = c.getColor(R.color.px_fg);
        cOff = c.getColor(R.color.px_muted);
        paint.setAntiAlias(false);
        setContentDescription("Step");
    }

    public void set(int count, int current) {
        this.count = count;
        this.current = current;
        setContentDescription("Step " + (current + 1) + " of " + count);
        invalidate();
    }

    private float dot() { return 10 * f * d; }

    private float gap() { return 6 * f * d; }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(count * dot() + (count - 1) * gap()), Math.round(dot()));
    }

    @Override protected void onDraw(Canvas cv) {
        float s = dot(), b = 2 * d;
        for (int i = 0; i < count; i++) {
            float x = Math.round(i * (s + gap()));
            paint.setColor(i == current ? cOn : cOff);
            cv.drawRect(x, 0, x + s, s, paint);
            if (i != current) {
                paint.setColor(getContext().getColor(R.color.px_page));
                cv.drawRect(x + b, b, x + s - b, s - b, paint);
            }
        }
    }
}
