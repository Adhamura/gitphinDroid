package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.LinearLayout;

/** Plain panel in the web page style: 2dp border and a hard shadow, no blur, square corners. */
public final class PixelPanel extends LinearLayout {
    private final Paint paint = new Paint();
    private final float d;
    private final float shadow;
    private final int cCard, cBd, cShadow;

    public PixelPanel(Context c, int shadowDp) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        shadow = shadowDp * d;
        cCard = c.getColor(R.color.px_card);
        cBd = c.getColor(R.color.px_bd);
        cShadow = c.getColor(R.color.px_shadow);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setOrientation(VERTICAL);
    }

    /** Sets the inner padding in dp, on top of the border and the shadow. */
    public void setInnerPadding(int dp) {
        int b = Math.round(2 * d) + Math.round(dp * d);
        setPadding(b, b, b + Math.round(shadow), b + Math.round(shadow));
    }

    @Override protected void onDraw(Canvas cv) {
        float w = getWidth() - shadow, h = getHeight() - shadow;
        paint.setColor(cShadow);
        cv.drawRect(shadow, shadow, shadow + w, shadow + h, paint);
        paint.setColor(cBd);
        cv.drawRect(0, 0, w, h, paint);
        paint.setColor(cCard);
        cv.drawRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, paint);
    }
}
