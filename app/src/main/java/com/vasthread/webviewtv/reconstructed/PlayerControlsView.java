package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Single-finger playback gestures, confined to the native playback surface. */
public final class PlayerControlsView extends View {
    public interface Listener {
        void onChannelSwipe(int step);
        void onSourceSwipe(int step);
    }

    private final Listener listener;
    private final int touchSlop;
    private final float swipeDistance;
    private float startX, startY;
    private boolean tracking, moved;
    private long downTime;

    public PlayerControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDistance = Math.max(touchSlop * 2, 48 * getResources().getDisplayMetrics().density);
        setClickable(true);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) { tracking = false; return super.onTouchEvent(event); }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = event.getPointerCount() == 1;
                moved = false;
                startX = event.getX();
                startY = event.getY();
                downTime = event.getEventTime();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        recordMovement(event.getHistoricalX(i), event.getHistoricalY(i));
                    }
                    recordMovement(event.getX(), event.getY());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return true;
                tracking = false;
                recordMovement(event.getX(), event.getY());
                float dx = event.getX() - startX, dy = event.getY() - startY;
                float horizontal = Math.abs(dx), vertical = Math.abs(dy);
                if (horizontal >= swipeDistance && horizontal > vertical * 1.4f) {
                    listener.onSourceSwipe(dx < 0 ? 1 : -1);
                } else if (vertical >= swipeDistance && vertical > horizontal * 1.4f) {
                    listener.onChannelSwipe(dy < 0 ? 1 : -1);
                } else if (!moved && event.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout()) {
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    private void recordMovement(float x, float y) {
        if (Math.abs(x - startX) > touchSlop || Math.abs(y - startY) > touchSlop) moved = true;
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) tracking = false;
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        tracking = false;
    }

    @Override protected void onDetachedFromWindow() {
        tracking = false;
        super.onDetachedFromWindow();
    }
}
