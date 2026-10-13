package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.R;
import io.github.adhamura.gitphindroid.ui.pixel.PixelIcon;
import io.github.adhamura.gitphindroid.ui.pixel.PixelPanel;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.function.BooleanSupplier;

/** Swipe in from the left edge (start within 24dp, more than 96dp to the right, mostly
 *  horizontal): the WebView goes back. A pixel "<" arrow follows the finger. */
final class EdgeSwipeBack implements WebGesture {
    private final WebGestureLayout host;
    private final Runnable goBack;
    private final BooleanSupplier canGoBack;
    private final PixelPanel arrow;
    private final float d, slop;
    private float downX, downY, width;
    private boolean edge;

    EdgeSwipeBack(WebGestureLayout host, Runnable goBack, BooleanSupplier canGoBack) {
        this.host = host;
        this.goBack = goBack;
        this.canGoBack = canGoBack;
        d = host.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        arrow = new PixelPanel(host.getContext(), 3);
        arrow.setInnerPadding(8);
        PixelIcon ic = new PixelIcon(host.getContext());
        ic.setIcon(PixelIcon.BACK);
        ic.setScale(2);
        ic.setColor(host.getContext().getColor(R.color.px_fg));
        arrow.addView(ic, Ui.wrap());
        arrow.setVisibility(View.GONE);
        arrow.setContentDescription("Back");
        host.addOverlay(arrow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
    }

    @Override public void onDown(MotionEvent e) {
        downX = e.getX();
        downY = e.getY();
        edge = downX <= 24 * d && canGoBack.getAsBoolean();
    }

    @Override public boolean onInterceptMove(MotionEvent e) {
        if (!edge) return false;
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (dx > slop * 2 && dx > Math.abs(dy) * 2) {
            arrow.setVisibility(View.VISIBLE);
            arrow.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            width = arrow.getMeasuredWidth();
            follow(dx);
            return true;
        }
        return false;
    }

    private void follow(float dx) {
        arrow.setTranslationX(-width + Math.min(dx * 0.5f, width + 12 * d));
        arrow.setAlpha(Math.min(1f, 0.3f + dx / (96 * d)));
    }

    @Override public void onTouch(MotionEvent e) {
        int a = e.getActionMasked();
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (a == MotionEvent.ACTION_MOVE) {
            follow(Math.max(0, dx));
            return;
        }
        boolean go = a == MotionEvent.ACTION_UP && dx > 96 * d && dx > Math.abs(dy) * 2;
        arrow.animate().translationX(-width - 4 * d).setDuration(150)
                .withEndAction(() -> arrow.setVisibility(View.GONE)).start();
        if (go && canGoBack.getAsBoolean()) goBack.run();
    }
}
