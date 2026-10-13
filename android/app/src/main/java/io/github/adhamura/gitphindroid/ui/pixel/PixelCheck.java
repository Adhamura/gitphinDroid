package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Check box in the web page style: 16dp square, 2dp border, pixel check mark. */
public final class PixelCheck extends LinearLayout {
    private final Box box;
    private Runnable onChange;

    private static final class Box extends View {
        private final Paint paint = new Paint();
        private final float d;
        private final float f;
        private final int cBd, cBg, cMark, cCode, cFocus;
        boolean on;

        Box(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            f = UiScale.factor(c);
            cBd = c.getColor(R.color.px_bd);
            cBg = c.getColor(R.color.px_prim_bg);
            cMark = c.getColor(R.color.px_prim_fg);
            cCode = c.getColor(R.color.px_code);
            cFocus = c.getColor(R.color.px_focus);
            paint.setAntiAlias(false);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(16 * f * d), Math.round(16 * f * d));
        }

        @Override protected void onDraw(Canvas cv) {
            float s = 16 * f * d, b = 2 * f * d, pd = f * d;
            paint.setColor(cBd);
            cv.drawRect(0, 0, s, s, paint);
            paint.setColor(on ? cBg : cCode);
            cv.drawRect(b, b, s - b, s - b, paint);
            if (on) {
                // check mark on a 12x12 grid of 1dp pixels, inside the border
                paint.setColor(cMark);
                int[][] px = {{2, 6}, {3, 7}, {4, 8}, {5, 7}, {6, 6}, {7, 5}, {8, 4}, {9, 3},
                              {2, 5}, {3, 6}, {4, 7}, {5, 6}, {6, 5}, {7, 4}, {8, 3}};
                for (int[] p : px) {
                    float x = b + p[0] * pd, y = b + p[1] * pd - pd;
                    cv.drawRect(x, y, x + pd, y + pd, paint);
                }
            }
            if (isFocused() || ((View) getParent()).isFocused()) {
                paint.setColor(cFocus);
                float o = 2 * d, l = -3 * d, e = s + 3 * d;
                cv.drawRect(l, l, e, l + o, paint);
                cv.drawRect(l, e - o, e, e, paint);
                cv.drawRect(l, l, l + o, e, paint);
                cv.drawRect(e - o, l, e, e, paint);
            }
        }
    }

    public PixelCheck(Context c, String text) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        float f = UiScale.factor(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setClickable(true);
        setFocusable(true);
        setMinimumHeight(Math.round(32 * f * d));
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        box = new Box(c);
        addView(box, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(UiScale.sp(c, 14));
        tv.setTextColor(c.getColor(R.color.px_fg));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(8 * f * d);
        addView(tv, lp);
        setOnClickListener(v -> {
            setChecked(!box.on);
            if (onChange != null) onChange.run();
        });
    }

    public boolean isChecked() { return box.on; }

    public void setChecked(boolean on) {
        box.on = on;
        box.invalidate();
    }

    public void setOnChange(Runnable r) { onChange = r; }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        box.invalidate();
    }
}
