package com.vasthread.webviewtv.reconstructed;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-height vertical paging, with one WebView and no video-frame copies. */
public final class ChannelSwipeAnimation extends FrameLayout {
    private final View playback;
    private final LinearLayout card;
    private final TextView direction, name, hint;
    private final float scale;
    private ValueAnimator animator;
    private Runnable pendingSwitch;
    private boolean turning;

    public ChannelSwipeAnimation(Context context, View playback) {
        super(context);
        this.playback = playback;
        scale = UiScale.landscape(context, 960);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setClipChildren(true);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(px(24), px(18), px(24), px(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC182438);
        background.setCornerRadius(px(22));
        background.setStroke(px(1), 0xFF456A91);
        card.setBackground(background);
        direction = label(14, 0xFF9AD0FF);
        name = label(26, 0xFFF4F7FF);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hint = label(13, 0xFFB5C6DE);
        card.addView(direction, new LinearLayout.LayoutParams(-1, px(26)));
        card.addView(name, new LinearLayout.LayoutParams(-1, px(42)));
        card.addView(hint, new LinearLayout.LayoutParams(-1, px(25)));
        addView(card, new FrameLayout.LayoutParams(px(380), -2, Gravity.CENTER));
        setVisibility(INVISIBLE);
        setAlpha(0);
    }

    public void drag(float distance, boolean ready, String channelName) {
        completeTurn();
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(); return; }
        float height = playback.getHeight();
        float offset = Math.max(-height, Math.min(height, distance));
        playback.setTranslationY(offset);
        // The next page follows immediately behind the outgoing playback page.
        card.setTranslationY(offset + (distance < 0 ? height : -height));
        setLabel(direction, distance < 0 ? "↑  下一个频道" : "↓  上一个频道");
        setLabel(name, channelName);
        setLabel(hint, ready ? "松开切换频道" : "继续滑动切换频道");
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
        hint.setText("正在切换频道");
        final float height = playback.getHeight();
        final float start = playback.getTranslationY();
        final float end = start < 0 ? -height : height;
        animatePage(240, fraction -> {
            float offset = start + (end - start) * fraction;
            playback.setTranslationY(offset);
            card.setTranslationY(offset - end);
        }, () -> {
            Runnable action = pendingSwitch;
            pendingSwitch = null;
            if (action != null) action.run();
            // Enter from the opposite edge, continuing the same page direction.
            playback.setTranslationY(-end);
            animatePage(240, fraction -> {
                playback.setTranslationY(-end * (1 - fraction));
                card.setTranslationY(end * fraction);
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
        final float offset = playback.getTranslationY(), cardOffset = card.getTranslationY(), opacity = getAlpha();
        animatePage(duration, fraction -> {
            playback.setTranslationY(offset * (1 - fraction));
            card.setTranslationY(cardOffset + offset * -fraction);
            setAlpha(opacity * (1 - fraction));
        }, this::reset);
    }

    public void reset() {
        stopAnimator();
        pendingSwitch = null;
        turning = false;
        playback.setTranslationY(0);
        card.setTranslationY(0);
        setAlpha(0);
        setVisibility(INVISIBLE);
    }

    private void stopAnimator() {
        if (animator != null) { animator.cancel(); animator.removeAllUpdateListeners(); animator = null; }
    }

    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
    private int px(float value) { return Math.max(1, Math.round(value * scale)); }
    private void setLabel(TextView view, String text) {
        // Drag events should only update transforms, not request identical text layouts.
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }
    private TextView label(int size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        return view;
    }
}
