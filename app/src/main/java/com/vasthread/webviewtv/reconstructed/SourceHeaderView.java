package com.vasthread.webviewtv.reconstructed;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared single-line header: title left, website and source count right. */
final class SourceHeaderView extends LinearLayout {
    final TextView name, website, source;
    private final LinearLayout origin;
    private final int titleGap, minimumDomain;

    SourceHeaderView(Context context,float scale,float titleSize,float sourceSize,int titleColor,int sourceColor) {
        super(context);
        setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
        setClickable(false);setFocusable(false);
        titleGap=Math.max(1,Math.round(12*scale));
        minimumDomain=Math.max(1,Math.round(48*scale));
        name=label(context,titleSize,titleColor,Gravity.LEFT|Gravity.CENTER_VERTICAL);
        addView(name,new LayoutParams(0,-2));
        origin=new LinearLayout(context);origin.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        website=label(context,sourceSize,sourceColor,Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        source=label(context,sourceSize,sourceColor,Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        LayoutParams domain=new LayoutParams(0,-2,1);
        domain.rightMargin=Math.max(1,Math.round(8*scale));
        origin.addView(website,domain);
        origin.addView(source,new LayoutParams(-2,-2));
        LayoutParams group=new LayoutParams(0,-2);group.leftMargin=titleGap;
        addView(origin,group);
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        if(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED) {
            int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
            int counter=(int)Math.ceil(source.getPaint().measureText(source.getText().toString()))
                    +source.getPaddingLeft()+source.getPaddingRight();
            int gap=((LayoutParams)website.getLayoutParams()).rightMargin;
            int titleDesired=(int)Math.ceil(name.getPaint().measureText(name.getText().toString()))
                    +name.getPaddingLeft()+name.getPaddingRight();
            int titleLimit=Math.max(0,available-counter-gap-titleGap-minimumDomain);
            int titleWidth=Math.min(titleDesired,titleLimit);
            ((LayoutParams)name.getLayoutParams()).width=titleWidth;
            ((LayoutParams)origin.getLayoutParams()).width=Math.max(0,available-titleWidth-titleGap);
        }
        super.onMeasure(widthSpec,heightSpec);
    }

    private static TextView label(Context context,float size,int color,int gravity) {
        TextView text=new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);text.setTextColor(color);
        text.setGravity(gravity);text.setSingleLine(true);text.setIncludeFontPadding(false);
        text.setEllipsize(TextUtils.TruncateAt.END);text.setTypeface(Typeface.DEFAULT);
        return text;
    }
}
