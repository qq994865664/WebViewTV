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

/** Lightweight native feedback; never captures video frames or delays a playback request. */
public final class ChannelSwipeAnimation extends FrameLayout {
    private final View playback;
    private final LinearLayout card;
    private final TextView direction, name, hint;
    private final float scale;
    private ValueAnimator animator;

    public ChannelSwipeAnimation(Context context, View playback) {
        super(context);
        this.playback = playback;
        scale = UiScale.landscape(context, 960);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackgroundColor(0x38040A14);
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(px(24), px(18), px(24), px(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2182438);
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
        stopAnimator();
        if (!ValueAnimator.areAnimatorsEnabled()) { reset(); return; }
        float limit = Math.min(px(90), playback.getHeight() * .16f);
        float offset = Math.max(-limit, Math.min(limit, distance * .28f));
        playback.setTranslationY(offset);
        card.setTranslationY(offset * .45f);
        setLabel(direction, distance < 0 ? "↑  下一个频道" : "↓  上一个频道");
        setLabel(name, channelName);
        setLabel(hint, ready ? "松开切换频道" : "继续滑动切换频道");
        setAlpha(Math.min(1f, Math.abs(distance) / px(100)));
        setVisibility(VISIBLE);
    }

    public void finish() {
        hint.setText("正在切换频道");
        settle(260);
    }

    public void cancelDrag() { settle(180); }

    private void settle(long duration) {
        stopAnimator();
        if (!ValueAnimator.areAnimatorsEnabled() || getVisibility() != VISIBLE) { reset(); return; }
        final float offset = playback.getTranslationY(), opacity = getAlpha();
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(value -> {
            float fraction = (float)value.getAnimatedValue();
            playback.setTranslationY(offset * (1 - fraction));
            card.setTranslationY(offset * .45f * (1 - fraction));
            setAlpha(opacity * (1 - fraction));
            if (fraction >= 1) setVisibility(INVISIBLE);
        });
        animator.start();
    }

    public void reset() {
        stopAnimator();
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
