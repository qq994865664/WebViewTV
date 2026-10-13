package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Non-interactive five-second banner, separate from the channel guide. */
public final class ChannelInfoView extends LinearLayout {
    private final float scale;
    private final TextView name, source, nowProgramme, nextProgramme;
    private final Runnable dismiss = this::hide;

    public ChannelInfoView(Context context) {
        super(context);
        scale = UiScale.landscape(context, 960);
        setOrientation(VERTICAL);
        setPadding(px(26), px(8), px(26), px(10));
        setFocusable(false);
        setClickable(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC142238);
        background.setCornerRadius(px(20));
        background.setStroke(px(1), 0x665F7898);
        setBackground(background);
        setElevation(px(12));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(px(760), px(108), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = px(24);
        setLayoutParams(params);
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(VERTICAL);
        title.addView(label("正在播放", 11, 0xFF78B9FF), new LayoutParams(-1, px(18)));
        name = label("", 20, 0xFFF4F7FF);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.addView(name, new LayoutParams(-1, px(28)));
        heading.addView(title, new LayoutParams(0, -1, 1));
        source = label("", 14, 0xFF9CAEC8);
        source.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        heading.addView(source, new LayoutParams(px(100), -1));
        addView(heading, new LayoutParams(-1, px(50)));
        nowProgramme = label("", 13, 0xFFC7DCF3);
        nextProgramme = label("", 12, 0xFF9CAEC8);
        addView(nowProgramme, new LayoutParams(-1, px(18)));
        addView(nextProgramme, new LayoutParams(-1, px(18)));
        setVisibility(GONE);
    }

    public void setChannel(String title, int sourceIndex, int count) {
        name.setText(title);
        source.setText(count > 0 ? "源 " + (sourceIndex + 1) + " / " + count : "暂无播放源");
    }

    /** Guide refreshes update content without extending or reopening the banner. */
    public void setProgramme(String now, String next) {
        nowProgramme.setText(now == null || now.isEmpty() ? "" : "现在 · " + now);
        nextProgramme.setText(next == null || next.isEmpty() ? "" : "接下来 · " + next);
    }

    public void showForChannel() {
        removeCallbacks(dismiss);
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0);
        setTranslationY(px(8));
        animate().alpha(1).translationY(0).setDuration(120).start();
        postDelayed(dismiss, 5000);
    }

    public void hide() {
        removeCallbacks(dismiss);
        animate().cancel();
        setVisibility(GONE);
    }

    @Override protected void onDetachedFromWindow() { hide(); super.onDetachedFromWindow(); }
    private int px(float value) { return Math.max(1, Math.round(value * scale)); }
    private TextView label(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }
}
