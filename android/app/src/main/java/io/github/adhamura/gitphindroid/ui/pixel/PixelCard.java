package io.github.adhamura.gitphindroid.ui.pixel;

import io.github.adhamura.gitphindroid.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.widget.LinearLayout;

/** Card in the web page style: 2dp border, hard 4dp shadow, chrome title strip. */
public final class PixelCard extends LinearLayout {
    private final Paint paint = new Paint();
    private final float d;
    private final int cCard, cBd, cShadow;
    private final LinearLayout content;

    public PixelCard(Context c, String title) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        cCard = c.getColor(R.color.px_card);
        cBd = c.getColor(R.color.px_bd);
        cShadow = c.getColor(R.color.px_shadow);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setOrientation(VERTICAL);
        int b = Math.round(2 * d);
        setPadding(b, b, b + Math.round(4 * d), b + Math.round(4 * d));

        LinearLayout strip = new LinearLayout(c);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackgroundColor(c.getColor(R.color.px_chrome));
        strip.setPadding(dp(16), dp(8), dp(16), dp(8));
        PixelTextView t = new PixelTextView(c);
        t.setScale(2);
        t.setColor(c.getColor(R.color.px_on_chrome));
        t.setText(title);
        strip.addView(t, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        content = new LinearLayout(c);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(12));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    private int dp(int v) { return Math.round(v * d); }

    /** The body of the card: add the fields here. */
    public LinearLayout content() { return content; }

    @Override protected void onDraw(Canvas cv) {
        float s = 4 * d, w = getWidth() - s, h = getHeight() - s;
        paint.setColor(cShadow);
        cv.drawRect(s, s, s + w, s + h, paint);
        paint.setColor(cBd);
        cv.drawRect(0, 0, w, h, paint);
        paint.setColor(cCard);
        cv.drawRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, paint);
    }
}
