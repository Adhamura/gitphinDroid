package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Button in the web page style: 2dp border, hard offset shadow, pixel-font label. */
public final class PixelButton extends View {
    private final Paint paint = new Paint();
    private final float d;
    private int cCard, cFg;
    private final int cBd, cShadow, cFocus;
    private final Context ctx;
    private String label = "";
    private boolean ok;
    private boolean small;

    public PixelButton(Context c, String label) {
        super(c);
        ctx = c;
        d = c.getResources().getDisplayMetrics().density;
        cCard = c.getColor(R.color.px_card);
        cFg = c.getColor(R.color.px_fg);
        cBd = c.getColor(R.color.px_bd);
        cShadow = c.getColor(R.color.px_shadow);
        cFocus = c.getColor(R.color.px_on_chrome);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        ok = PixelFont.load(c);
        setClickable(true);
        setFocusable(true);
        setLabel(label);
    }

    public void setPrimary(boolean primary) {
        cCard = ctx.getColor(primary ? R.color.px_prim_bg : R.color.px_card);
        cFg = ctx.getColor(primary ? R.color.px_prim_fg : R.color.px_fg);
        invalidate();
    }

    /** Primary look with a custom background color (for example a destructive action). */
    void setPrimaryColor(int bg) {
        cCard = bg;
        cFg = ctx.getColor(R.color.px_card);
        invalidate();
    }

    public void setLabel(String t) {
        label = t;
        setContentDescription(t);
        requestLayout();
        invalidate();
    }

    /** Small button for the top bar: font scale 1 (2 when large) and less padding. */
    public void setSmall(boolean small) {
        this.small = small;
        requestLayout();
        invalidate();
    }

    private float ts() { return UiScale.pixelScale(ctx, small ? 1 : 2); }

    private float uf() { return UiScale.large(ctx) ? 1.25f : 1f; }

    private float textW() {
        return ok ? PixelFont.width(label) * ts() * d : label.length() * 4.5f * ts() * d;
    }

    private float padX() { return (small ? 6 : 12) * uf(); }

    private int bodyW() { return Math.round(textW() + (2 * padX() + 4) * d); } // padding and border, both sides

    private float bodyH() { return 8 * ts() * d + (small ? 10 : 16) * uf() * d; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        // body + 2dp room for the pressed offset / shadow
        setMeasuredDimension(resolveSize(bodyW() + Math.round(5 * d), wSpec),
                resolveSize(Math.round(bodyH() + (small ? 8 : 16) * d), hSpec));
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override protected void onDraw(Canvas cv) {
        float bw = bodyW(), bh = bodyH(), b = 2 * d;
        float left = 3 * d, top = (getHeight() - bh - 2 * d) / 2f;
        boolean pressed = isPressed();
        if (pressed) { left += 2 * d; top += 2 * d; }
        else {
            paint.setColor(cShadow);
            cv.drawRect(left + 2 * d, top + 2 * d, left + 2 * d + bw, top + 2 * d + bh, paint);
        }
        paint.setColor(cBd);
        cv.drawRect(left, top, left + bw, top + bh, paint);
        paint.setColor(cCard);
        cv.drawRect(left + b, top + b, left + bw - b, top + bh - b, paint);
        paint.setColor(cFg);
        float tw = textW(), th = 8 * ts() * d;
        float tx = left + (bw - tw) / 2f, ty = top + (bh - th) / 2f;
        if (ok) {
            PixelFont.draw(cv, label, Math.round(tx), Math.round(ty), ts() * d, paint);
        } else {
            paint.setAntiAlias(true);
            paint.setTextSize(th * 0.9f);
            cv.drawText(label, tx, ty + th * 0.8f, paint);
            paint.setAntiAlias(false);
        }
        if (isFocused()) {
            paint.setColor(cFocus);
            float o = 2 * d, l = left - 3 * d, t = top - 3 * d, r = left + bw + 3 * d, bt = top + bh + 3 * d;
            cv.drawRect(l, t, r, t + o, paint);
            cv.drawRect(l, bt - o, r, bt, paint);
            cv.drawRect(l, t, l + o, bt, paint);
            cv.drawRect(r - o, t, r, bt, paint);
        }
    }

    @Override public boolean performClick() {
        return super.performClick();
    }
}
