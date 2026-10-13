package io.github.adhamura.gitphindroid.ui.main;

import io.github.adhamura.gitphindroid.ui.pixel.PixelPanel;
import io.github.adhamura.gitphindroid.ui.pixel.PixelSpinner;
import io.github.adhamura.gitphindroid.ui.pixel.Ui;

import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Pull down at the top of the page: a pixel panel with a spinner slides down, then the page reloads. */
final class PullRefresh implements WebGesture {
    private final WebGestureLayout host;
    private final WebView web;
    private final Runnable refresh;
    private final PixelPanel panel;
    private final PixelSpinner spinner;
    private final float d, slop, trigger;
    private float downX, downY, height;
    private boolean atTop, checked, refreshing;
    private int token;

    PullRefresh(WebGestureLayout host, WebView web, Runnable refresh) {
        this.host = host;
        this.web = web;
        this.refresh = refresh;
        d = host.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
        trigger = 80 * d;
        panel = new PixelPanel(host.getContext(), 3);
        panel.setInnerPadding(6);
        spinner = new PixelSpinner(host.getContext());
        panel.addView(spinner, Ui.wrap());
        panel.setVisibility(View.GONE);
        panel.setContentDescription("Refreshing");
        host.addOverlay(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override public void onDown(MotionEvent e) {
        downX = e.getX();
        downY = e.getY();
        atTop = false;
        checked = false;
        final int t = ++token;
        if (refreshing || web.getScrollY() != 0) {
            checked = true;
            return;
        }
        // The page scrolls inside the document: look at the element under the finger and its parents.
        String js = "(function(x,y){var e=document.elementFromPoint(x,y);"
                + "while(e){if(e.scrollTop>0)return 0;e=e.parentElement;}"
                + "return (window.pageYOffset||0)>0?0:1;})(" + Math.round(downX / d) + ","
                + Math.round(downY / d) + ")";
        try {
            web.evaluateJavascript(js, v -> {
                if (t != token) return;
                checked = true;
                if (v != null && v.trim().equals("1")) atTop = true;
                // no answer (page not ready): only the top third counts
                else if (v == null || v.equals("null")) atTop = downY < host.getHeight() / 3f;
            });
        } catch (RuntimeException ignored) {
            checked = true;
            atTop = downY < host.getHeight() / 3f;
        }
    }

    @Override public boolean onInterceptMove(MotionEvent e) {
        if (!checked || !atTop || refreshing) return false;
        float dy = e.getY() - downY, dx = e.getX() - downX;
        if (dy > slop * 2 && dy > Math.abs(dx) * 2) {
            panel.setVisibility(View.VISIBLE);
            panel.setAlpha(1f);
            spinner.setSpinning(false);
            panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            height = panel.getMeasuredHeight();
            move(dy);
            return true;
        }
        return false;
    }

    private void move(float dy) {
        float rest = 12 * d;
        float ty = -height + Math.min(dy * 0.6f, height + rest);
        panel.setTranslationY(ty);
        spinner.setProgress(Math.min(1.5f, dy / trigger));
    }

    @Override public void onTouch(MotionEvent e) {
        int a = e.getActionMasked();
        float dy = e.getY() - downY;
        if (a == MotionEvent.ACTION_MOVE) {
            move(Math.max(0, dy));
        } else if (a == MotionEvent.ACTION_UP && dy > trigger) {
            refreshing = true;
            spinner.setSpinning(true);
            panel.animate().translationY(12 * d).setDuration(120).start();
            refresh.run();
            panel.postDelayed(this::done, 4000); // the page-finished signal may not come
        } else {
            hide();
        }
    }

    /** The reload ended (or took too long): slide the panel away. */
    void done() {
        if (!refreshing) return;
        refreshing = false;
        hide();
    }

    private void hide() {
        panel.animate().translationY(-height - 4 * d).setDuration(150)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }
}
