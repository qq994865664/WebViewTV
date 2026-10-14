package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

/** A single passive connection card that becomes actionable on terminal failure. */
public final class PlaybackStatusView extends LinearLayout {
    private final float scale;
    private final TextView heading, stage, timing, source;
    private final ProgressBar budget;
    private final LinearLayout actions;
    private final TextView retry,next,channels;
    public PlaybackStatusView(Context context,Runnable retryAction,Runnable nextAction,Runnable channelsAction){
        super(context);scale=UiScale.landscape(context,960);setOrientation(VERTICAL);setPadding(px(26),px(20),px(26),px(20));
        setBackground(shape(TvTheme.PANEL,TvTheme.BORDER));setVisibility(GONE);
        setLayoutParams(new FrameLayout.LayoutParams(px(520),-2,Gravity.CENTER));
        heading=label(18,TvTheme.INK);stage=label(23,TvTheme.INK);source=label(14,TvTheme.ACCENT);timing=label(15,TvTheme.MUTED);
        source.setSingleLine(true);source.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(heading);addView(source);addView(stage);addView(timing);
        budget=new ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal);budget.setMax(1000);budget.setIndeterminate(false);
        budget.setProgressTintList(ColorStateList.valueOf(TvTheme.ACCENT));budget.setProgressBackgroundTintList(ColorStateList.valueOf(TvTheme.BORDER));
        LayoutParams bar=new LayoutParams(-1,px(5));bar.topMargin=px(14);addView(budget,bar);
        actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER);LayoutParams row=new LayoutParams(-1,UiScale.isTelevision(context)?px(52):Math.max(px(52),Math.round(48*getResources().getDisplayMetrics().density)));row.topMargin=px(16);addView(actions,row);
        retry=button("重试",retryAction);next=button("下一个来源",nextAction);channels=button("选择频道",channelsAction);
    }
    public void loading(String name,String domain,int index,int count,int percent,long remaining,boolean willSwitch,boolean buffering){
        position(buffering);
        heading.setText(name);heading.setVisibility(VISIBLE);
        source.setText(domain+" · 来源 "+(index+1)+"/"+count);
        stage.setText("正在加载..."+percent+"%");
        long seconds=(remaining+999)/1000;
        timing.setText(willSwitch ? seconds+" 秒后自动切换直播源" : "剩余等待 "+seconds+" 秒");
        budget.setProgress(percent*10,true);budget.setVisibility(VISIBLE);actions.setVisibility(GONE);setVisibility(VISIBLE);
    }
    public void failure(String name,String domain,int index,String message,int count,boolean focus){
        position(false);
        heading.setText(name);heading.setVisibility(VISIBLE);stage.setText(message);
        source.setText(domain+" · 来源 "+(index+1)+"/"+count);
        timing.setText("");timing.setVisibility(GONE);budget.setVisibility(GONE);actions.setVisibility(VISIBLE);
        next.setVisibility(count>1?VISIBLE:GONE);setVisibility(VISIBLE);
        if(focus){if(count>1)next.requestFocus();else retry.requestFocus();}
    }
    private void position(boolean buffering){
        timing.setVisibility(VISIBLE);
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)getLayoutParams();
        int gravity=buffering?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.CENTER;
        if(p.gravity!=gravity){p.gravity=gravity;p.bottomMargin=buffering?px(24):0;setLayoutParams(p);}
    }
    public void hide(){setVisibility(GONE);clearFocus();}
    private TextView label(int size,int color){TextView t=new TextView(getContext());t.setTextSize(TypedValue.COMPLEX_UNIT_PX,UiScale.text(getContext(),size,scale));t.setTextColor(color);t.setPadding(0,px(4),0,px(4));return t;}
    private TextView button(String title,Runnable action){TextView t=label(15,TvTheme.INK);t.setText(title);t.setGravity(Gravity.CENTER);t.setFocusable(true);t.setFocusableInTouchMode(true);t.setClickable(true);t.setOnClickListener(v->action.run());t.setBackground(shape(TvTheme.CARD,TvTheme.BORDER));t.setOnFocusChangeListener((v,f)->t.setBackground(shape(f?TvTheme.FOCUS:TvTheme.CARD,f?TvTheme.ACCENT:TvTheme.BORDER)));LayoutParams p=new LayoutParams(0,-1,1);p.setMargins(px(3),0,px(3),0);actions.addView(t,p);return t;}
    private GradientDrawable shape(int fill,int border){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(px(16));g.setStroke(px(1),border);return g;}
    private int px(int v){return Math.max(1,Math.round(v*scale));}
}
