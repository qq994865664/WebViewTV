package com.vasthread.webviewtv.reconstructed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Full-height vertical paging, without preview cards or video-frame copies. */
public final class ChannelSwipeAnimation extends FrameLayout {
    private final View playback;
    private ValueAnimator animator;
    private Runnable pendingSwitch;
    private boolean turning;

    public ChannelSwipeAnimation(Context context, View playback) {
        super(context);
        this.playback = playback;
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setClipChildren(true);
        setVisibility(INVISIBLE);
        setAlpha(0);
    }

    // Keep the callback signature compatible with PlayerControlsView's drag updates.
    public void drag(float distance, boolean ready, String channelName) {
        completeTurn();
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(); return; }
        float height = playback.getHeight();
        float offset = Math.max(-height, Math.min(height, distance));
        playback.setTranslationY(offset);
        setAlpha(1);
        setVisibility(VISIBLE);
    }

    public void finish(Runnable switchChannel) {
        stopAnimator();
        if (!ValueAnimator.areAnimatorsEnabled() || getVisibility() != VISIBLE) {
            reset(); switchChannel.run(); return;
        }
        pendingSwitch = switchChannel;
        turning = true;
        final float height = playback.getHeight();
        final float start = playback.getTranslationY();
        final float end = start < 0 ? -height : height;
        animatePage(240, fraction -> {
            float offset = start + (end - start) * fraction;
            playback.setTranslationY(offset);
        }, () -> {
            Runnable action = pendingSwitch;
            pendingSwitch = null;
            if (action != null) action.run();
            // Enter from the opposite edge, continuing the same page direction.
            playback.setTranslationY(-end);
            animatePage(240, fraction -> {
                playback.setTranslationY(-end * (1 - fraction));
                setAlpha(1 - fraction);
            }, this::reset);
        });
    }

    public boolean isTurning() { return turning; }

    /** Finish a committed request before a rapid subsequent swipe, once only. */
    public void completeTurn() {
        Runnable action = pendingSwitch;
        pendingSwitch = null;
        reset();
        if (action != null) action.run();
    }

    public void cancelDrag() { if (!turning) settle(180); }

    private interface Frame { void update(float fraction); }

    private void animatePage(long duration, Frame frame, Runnable end) {
        stopAnimator();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(value -> {
            float fraction = (float)value.getAnimatedValue();
            frame.update(fraction);
            if (fraction >= 1) end.run();
        });
        animator.start();
    }

    private void settle(long duration) {
        stopAnimator();
        if (!ValueAnimator.areAnimatorsEnabled() || getVisibility() != VISIBLE) { reset(); return; }
        final float offset = playback.getTranslationY(), opacity = getAlpha();
        animatePage(duration, fraction -> {
            playback.setTranslationY(offset * (1 - fraction));
            setAlpha(opacity * (1 - fraction));
        }, this::reset);
    }

    public void reset() {
        stopAnimator();
        pendingSwitch = null;
        turning = false;
        playback.setTranslationY(0);
        setAlpha(0);
        setVisibility(INVISIBLE);
    }

    private void stopAnimator() {
        if (animator != null) { animator.cancel(); animator.removeAllUpdateListeners(); animator = null; }
    }

    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
}
