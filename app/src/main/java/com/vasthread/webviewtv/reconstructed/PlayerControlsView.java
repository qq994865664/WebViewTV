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
        default void onChannelDrag(float distance, boolean ready) { }
        default void onChannelDragCancelled() { }
    }

    private final Listener listener;
    private final int touchSlop;
    private final float swipeDistance;
    private float startX, startY;
    private boolean tracking, moved, previewing;
    private long downTime;

    public PlayerControlsView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        swipeDistance = Math.max(touchSlop * 2, 48 * getResources().getDisplayMetrics().density);
        setClickable(true);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) { cancelGesture(); return super.onTouchEvent(event); }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelGesture();
                tracking = event.getPointerCount() == 1;
                moved = false;
                startX = event.getX();
                startY = event.getY();
                downTime = event.getEventTime();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                cancelGesture();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (tracking) {
                    for (int i = 0; i < event.getHistorySize(); i++) {
                        recordMovement(event.getHistoricalX(i), event.getHistoricalY(i));
                    }
                    recordMovement(event.getX(), event.getY());
                    preview(event.getX() - startX, event.getY() - startY);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!tracking) return true;
                tracking = false;
                recordMovement(event.getX(), event.getY());
                float dx = event.getX() - startX, dy = event.getY() - startY;
                float horizontal = Math.abs(dx), vertical = Math.abs(dy);
                if (horizontal >= swipeDistance && horizontal > vertical * 1.4f) {
                    cancelPreview();
                    listener.onSourceSwipe(dx < 0 ? 1 : -1);
                } else if (vertical >= swipeDistance && vertical > horizontal * 1.4f) {
                    preview(dx, dy);
                    previewing = false;
                    listener.onChannelSwipe(dy < 0 ? 1 : -1);
                } else {
                    cancelPreview();
                    if (!moved && event.getEventTime() - downTime < ViewConfiguration.getLongPressTimeout()) performClick();
                }
                return true;
            default:
                return true;
        }
    }

    private void recordMovement(float x, float y) {
        if (Math.abs(x - startX) > touchSlop || Math.abs(y - startY) > touchSlop) moved = true;
    }

    private void preview(float dx, float dy) {
        if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.4f) {
            previewing = true;
            listener.onChannelDrag(dy, Math.abs(dy) >= swipeDistance);
        } else cancelPreview();
    }

    private void cancelPreview() {
        if (previewing) { previewing = false; listener.onChannelDragCancelled(); }
    }

    public void cancelGesture() {
        tracking = false;
        cancelPreview();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelGesture();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cancelGesture();
    }

    @Override protected void onDetachedFromWindow() {
        cancelGesture();
        super.onDetachedFromWindow();
    }
}
