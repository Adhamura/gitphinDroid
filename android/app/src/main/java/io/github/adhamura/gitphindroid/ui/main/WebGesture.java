package io.github.adhamura.gitphindroid.ui.main;

import android.view.MotionEvent;

/** A touch gesture on top of the WebView (see WebGestureLayout). */
interface WebGesture {
    /** A new touch starts. */
    void onDown(MotionEvent e);

    /** A move while the WebView still gets the touch: return true to take the gesture over. */
    boolean onInterceptMove(MotionEvent e);

    /** Touch events after the gesture was taken over. */
    void onTouch(MotionEvent e);
}
