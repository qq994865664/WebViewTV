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
    private final TextView name, source, website, nowProgramme, nextProgramme;
    private final Runnable dismiss = () -> animate().alpha(0).setDuration(250).withEndAction(() -> setVisibility(GONE)).start();

    public ChannelInfoView(Context context) {
        super(context);
        scale = UiScale.landscape(context, 960);
        setOrientation(VERTICAL);
        setPadding(px(26), px(8), px(26), px(10));
        setFocusable(false);
        setClickable(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(TvTheme.PANEL);
        background.setCornerRadius(px(20));
        background.setStroke(px(1), TvTheme.BORDER);
        setBackground(background);
        setElevation(px(12));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(px(760), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = px(24);
        setLayoutParams(params);
        SourceHeaderView heading = new SourceHeaderView(context,scale,px(25),px(17),TvTheme.INK,TvTheme.MUTED);
        name = heading.name;
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        source = heading.source;
        website = heading.website;
        LayoutParams headerParams=new LayoutParams(-1,-2);headerParams.bottomMargin=px(6);
        addView(heading,headerParams);
        nowProgramme = label("", 16, TvTheme.INK);
        nextProgramme = label("", 15, TvTheme.MUTED);
        addView(nowProgramme, new LayoutParams(-1, -2));
        addView(nextProgramme, new LayoutParams(-1, -2));
        setVisibility(GONE);
    }

    public void setChannel(String title, int sourceIndex, int count, String domain) {
        name.setText(title);
        website.setText(domain);
        source.setText(count > 0 ? "源 " + (sourceIndex + 1) + "/" + count : "暂无播放源");
    }

    /** Guide refreshes update content without extending or reopening the banner. */
    public void setProgramme(String now, String next) {
        nowProgramme.setText(now == null || now.isEmpty() ? "" : "现在 · " + now);
        nowProgramme.setVisibility(now == null || now.isEmpty() ? GONE : VISIBLE);
        nextProgramme.setVisibility(next == null || next.isEmpty() ? GONE : VISIBLE);
        nextProgramme.setText(next == null || next.isEmpty() ? "" : "接下来 · " + next);
    }

    public void showForChannel() { showForRemaining(5000); }

    public void showForRemaining(long remaining) {
        if (remaining <= 0) return;
        removeCallbacks(dismiss);
        animate().withEndAction(null);
        animate().cancel();
        setVisibility(VISIBLE);
        setAlpha(0);
        setTranslationY(0);
        animate().alpha(1).translationY(0).setDuration(150).withEndAction(() -> postDelayed(dismiss, Math.min(5000, remaining))).start();
    }

    public void hide() {
        removeCallbacks(dismiss);
        animate().withEndAction(null);
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
        view.setIncludeFontPadding(false);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }
}
