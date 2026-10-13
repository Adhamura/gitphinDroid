package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/** Pixel spinner: 8 square dots on a ring, one bright dot runs round with a short tail. */
public final class PixelSpinner extends View {
    private static final int[][] POS = {{1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}, {0, 0}};
    private final Paint paint = new Paint();
    private final float dot; // px of one dot
    private boolean spinning = true;
    private float fixedPos;  // 0..8 head position when not spinning

    public PixelSpinner(Context c) {
        super(c);
        dot = 4 * UiScale.factor(c) * c.getResources().getDisplayMetrics().density;
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(c.getColor(R.color.px_fg));
    }

    void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** Spinning runs by time; otherwise the head follows setProgress (0..1 = one turn). */
    public void setSpinning(boolean on) {
        spinning = on;
        invalidate();
    }

    public void setProgress(float turns) {
        fixedPos = (turns * 8f) % 8f;
        invalidate();
    }

    @Override protected void onMeasure(int w, int h) {
        int side = Math.round(dot * 5);
        setMeasuredDimension(resolveSize(side, w), resolveSize(side, h));
    }

    @Override protected void onDraw(Canvas cv) {
        int head = spinning ? (int) (SystemClock.uptimeMillis() / 90 % 8) : (int) fixedPos;
        float step = dot * 2f;
        for (int i = 0; i < 8; i++) {
            int age = (head - i + 8) % 8; // 0 = head
            int alpha = age == 0 ? 255 : age == 1 ? 190 : age == 2 ? 120 : 50;
            paint.setAlpha(alpha);
            float x = Math.round(POS[i][0] * step), y = Math.round(POS[i][1] * step);
            cv.drawRect(x, y, x + dot, y + dot, paint);
        }
        if (spinning && isShown()) postInvalidateOnAnimation();
    }
}
