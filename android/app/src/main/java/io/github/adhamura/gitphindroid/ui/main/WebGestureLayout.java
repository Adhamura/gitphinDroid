package io.github.adhamura.gitphindroid.ui.main;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Holds the WebView and two gestures on top of it: pull down to refresh (at the top of the
 *  page) and swipe in from the left edge to go back. The WebView gets every other touch. */
final class WebGestureLayout extends FrameLayout {
    private final PullRefresh pull;
    private final EdgeSwipeBack back;
    private WebGesture active;

    WebGestureLayout(Context c, WebView web, Runnable refresh, Runnable goBack,
                     java.util.function.BooleanSupplier canGoBack) {
        super(c);
        addView(web, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        pull = new PullRefresh(this, web, refresh);
        back = new EdgeSwipeBack(this, goBack, canGoBack);
    }

    /** The page finished loading: the refresh indicator can go. */
    void refreshDone() { pull.done(); }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            active = null;
            pull.onDown(e);
            back.onDown(e);
        } else if (a == MotionEvent.ACTION_MOVE && active == null && e.getPointerCount() == 1) {
            if (back.onInterceptMove(e)) active = back;
            else if (pull.onInterceptMove(e)) active = pull;
            return active != null;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (active == null) return false;
        WebGesture g = active;
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) active = null;
        g.onTouch(e);
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    /** Overlay views of the gestures are added above the WebView. */
    void addOverlay(View v, LayoutParams lp) { addView(v, lp); }
}
