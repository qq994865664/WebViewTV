package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Runs real Android ListViews and DPAD events; no production hooks or app data. */
public final class NavigationTest extends Instrumentation {
    NavigationActivity activity;
    ListView categories, channels;
    int changes, checks;
    boolean tvUi;

    @Override public void onCreate(Bundle args) { super.onCreate(args); tvUi=args!=null && "1".equals(args.getString("tvUi")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (NavigationActivity) startActivitySync(new Intent(getTargetContext(), NavigationActivity.class)
                .putExtra("tvUi",tvUi).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            setInTouchMode(false);
            settle();
            runOnMainSync(() -> {
                categories = (ListView) field("categoryList");
                channels = (ListView) field("channelList");
                channels.getAdapter().registerDataSetObserver(new DataSetObserver() {
                    @Override public void onChanged() { changes++; }
                });
            });
            for (int i = 0; i < categories.getCount(); i++) {
                if ("全部频道".equals(categories.getAdapter().getItem(i))) throw new AssertionError("All group remains");
            }
            checks++;
            same("only current group channels", new int[]{30}, new int[]{channels.getCount()});
            View panel = (View)field("panel");
            if (activity.menu.getChildCount() != 1 || panel.getHeight() < activity.menu.getHeight() * .9f)
                throw new AssertionError("Menu must use full height without an information panel");
            checks++;
            verifyProgrammePreviewAndRowHeights();
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            verifySmoothCategoryBoundary();
            // Compact TV rows expose more groups per page. Stay inside the list
            // while scrolling deeper, rather than moving into the toolbar.
            int deeperSteps=Math.min(22,categories.getCount()-1-snapshot(categories)[0]);
            if(deeperSteps<=0)throw new AssertionError("Fixture needs groups beyond its first page");
            for (int i = 0; i < deeperSteps; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            roundTrip("deep group and scrolled channels", true);
            // Return at bottom/top boundaries must also keep pixel offsets.
            runOnMainSync(() -> categories.setSelection(categories.getCount() - 1));
            settle(); roundTrip("last group", false);
            int[] groupBeforeTools=snapshot(categories);
            key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_UP);
            assertFocus(categories);
            same("toolbar returns to original group",groupBeforeTools,snapshot(categories));
            runOnMainSync(() -> categories.setSelection(1));
            settle(); roundTrip("first group", false);
            // Empty Favorites transfers to the toolbar, then back to the same group.
            key(KeyEvent.KEYCODE_DPAD_UP);
            int[] emptyBefore = snapshot(categories);
            key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_DPAD_LEFT);
            same("empty favorites", emptyBefore, snapshot(categories));
            // Touch a visible group, then resume with remote Left/Right.
            runOnMainSync(() -> categories.setSelectionFromTop(16, 90));
            settle();
            int[] point = new int[2];
            runOnMainSync(() -> {
                View row = categories.getChildAt(1);
                row.getLocationOnScreen(point);
                point[0] += row.getWidth() / 2; point[1] += row.getHeight() / 2;
            });
            long time = SystemClock.uptimeMillis();
            sendPointerSync(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,point[0],point[1],0));
            sendPointerSync(MotionEvent.obtain(time,time+30,MotionEvent.ACTION_UP,point[0],point[1],0));
            settle();
            int[] touchBefore = viewport(categories);
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            same("touch-to-remote viewport", touchBefore, viewport(categories));
            roundTrip("touch-to-remote cursor", false);
            // A new vertical key must take priority over a pending focus restore.
            int previous = snapshot(categories)[0];
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
            sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            settle();
            same("down immediately after return", new int[]{previous+1}, new int[]{snapshot(categories)[0]});
            String expected = categories.getAdapter().getItem(previous+1).toString();
            if (!expected.equals(field("selectedCategory"))) throw new AssertionError("Group did not follow Down");
            key(KeyEvent.KEYCODE_DPAD_UP);
            same("up after return", new int[]{previous}, new int[]{snapshot(categories)[0]});
            runOnMainSync(() -> {
                activity.menu.setCurrentChannel(35, 0, 1, java.util.Collections.emptySet());
                activity.menu.focusCurrentChannel();
            });
            settle();
            if (!"Group 1".equals(field("selectedCategory"))) throw new AssertionError("Playing group not selected");
            same("playing channel inside its group", new int[]{5}, new int[]{channels.getSelectedItemPosition()});
            runOnMainSync(() -> {
                android.widget.TextView[] tools=(android.widget.TextView[])field("tools");
                if(tools.length!=4 || !tools[0].getText().toString().equals("设置"))throw new AssertionError("Settings must lead four tools");
                tools[3].performClick();
                android.widget.PopupWindow popup=(android.widget.PopupWindow)field("morePopup");
                if(!popup.isShowing())throw new AssertionError("More actions did not open");
                popup.dismiss();
            });
            checks+=2;
            int gestureChecks = new GestureChecks(this, activity).run();
            result.putString("stream", "\nPASS " + checks + " navigation assertions\nPASS " + gestureChecks + " gesture assertions\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Object field(String name) {
        try { Field f=activity.menu.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(activity.menu); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(100); waitForIdleSync(); }
    private TextView rowText(View row, String name) {
        try { Field f=row.getClass().getDeclaredField(name); f.setAccessible(true); return (TextView)f.get(row); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void verifyProgrammePreviewAndRowHeights() {
        runOnMainSync(() -> {
            if (channels.getChildCount()<3 || categories.getChildCount()==0)
                throw new AssertionError("Guide fixture needs several visible rows");
            for (int i=0;i<channels.getChildCount();i++) {
                TextView detail=rowText(channels.getChildAt(i),"detail");
                if (detail.getVisibility()!=View.VISIBLE || !"暂无节目单".contentEquals(detail.getText()))
                    throw new AssertionError("Missing provider must show programme placeholder on every row");
            }
            checks++;
            int groupHeight=categories.getChildAt(0).getHeight();
            int channelHeight=channels.getChildAt(0).getHeight();
            if (groupHeight>=channelHeight)
                throw new AssertionError("Single-line groups must be shorter than two-line channels: "+groupHeight+" / "+channelHeight);
            float scale=(Float)field("scale");
            int previousGroupHeight=tvUi ? Math.max(1,Math.round(40*scale))
                    : Math.max(Math.round(channelHeight*.7f),Math.round(40*categories.getResources().getDisplayMetrics().density));
            if (groupHeight!=Math.round(previousGroupHeight*.7f))
                throw new AssertionError("Group rows must be 70% of their original height: "+groupHeight+" / "+previousGroupHeight);
            int expectedChannelHeight=tvUi ? Math.max(1,Math.round(56*scale)) : (Integer)field("touchHeight");
            if (channelHeight!=expectedChannelHeight)
                throw new AssertionError("Compact groups must preserve two-line channel height");
            checks++;
            TextView[] tools=(TextView[])field("tools");
            int previousToolHeight=(Integer)field("touchHeight");
            View toolBar=(View)tools[0].getParent();
            if (toolBar.getHeight()!=Math.round(previousToolHeight*.5f))
                throw new AssertionError("Bottom toolbar must be half its previous height");
            for (TextView tool:tools) {
                if (Math.abs(tool.getWidth()-tools[0].getWidth())>1)
                    throw new AssertionError("Four compact tools must retain equal widths");
                android.graphics.Paint.FontMetrics metrics=tool.getPaint().getFontMetrics();
                if (tool.getHeight()+1<metrics.descent-metrics.ascent)
                    throw new AssertionError("Compact toolbar clipped its label: "+tool.getText());
            }
            checks++;
            activity.menu.setProgrammeProvider(channel -> {
                String name=channel.optString("name");
                if (name.endsWith(":1")) return null;
                if (name.endsWith(":3")) return " ";
                return "正在播出：测试节目 "+name;
            });
        });
        settle();
        int[] before=snapshot(channels);
        int[] channelRowHeight=new int[1];
        runOnMainSync(() -> {
            boolean unfocusedProgramme=false, placeholder=false;
            channelRowHeight[0]=channels.getChildAt(0).getHeight();
            for (int i=0;i<channels.getChildCount();i++) {
                View row=channels.getChildAt(i);
                if (row.getHeight()!=channelRowHeight[0])
                    throw new AssertionError("Programme rows must have fixed height regardless of available EPG");
                TextView detail=rowText(row,"detail");
                boolean focused=channels.getFirstVisiblePosition()+i==channels.getSelectedItemPosition();
                if (detail.getVisibility()!=View.VISIBLE)
                    throw new AssertionError("Programme preview disappeared on an unfocused channel");
                if (detail.getEllipsize()!=(focused?TextUtils.TruncateAt.MARQUEE:TextUtils.TruncateAt.END)
                        || detail.isSelected()!=focused)
                    throw new AssertionError("Only focused programme text should marquee");
                if ("暂无节目单".contentEquals(detail.getText())) placeholder=true;
                else if (!focused && detail.getText().toString().startsWith("正在播出：测试节目 ")) unfocusedProgramme=true;
            }
            if (!unfocusedProgramme || !placeholder)
                throw new AssertionError("Unselected programme and missing-guide placeholder must both remain visible");
            checks+=2;
        });
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        runOnMainSync(() -> {
            for (int i=0;i<channels.getChildCount();i++) {
                View row=channels.getChildAt(i);
                TextView detail=rowText(row,"detail");
                if (row.getHeight()!=channelRowHeight[0] || detail.getVisibility()!=View.VISIBLE
                        || detail.getEllipsize()!=TextUtils.TruncateAt.END || detail.isSelected())
                    throw new AssertionError("Leaving channel focus must preserve row height and programme preview");
            }
            checks++;
        });
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        same("programme preview focus transfer",before,snapshot(channels));
        runOnMainSync(() -> {
            for (int i=0;i<channels.getChildCount();i++)
                if (channels.getChildAt(i).getHeight()!=channelRowHeight[0])
                    throw new AssertionError("Restoring focus changed channel row height");
            checks++;
        });
    }
    private void verifySmoothCategoryBoundary() {
        java.util.ArrayList<Integer> offsets = new java.util.ArrayList<>();
        android.view.ViewTreeObserver.OnPreDrawListener observer = () -> {
            View first=categories.getChildAt(0);
            if(first!=null)offsets.add(categories.getFirstVisiblePosition()*(first.getHeight()+categories.getDividerHeight())-first.getTop());
            return true;
        };
        runOnMainSync(() -> {
            int child=categories.getChildCount()-1;
            View row=categories.getChildAt(child);
            // Align the last visible row with the bottom, then move one full row.
            // Large touch rows can leave only 1–3 pixels of the next row clipped;
            // that tiny adjustment cannot meaningfully exercise animation frames.
            int top=categories.getHeight()-categories.getPaddingBottom()-row.getHeight();
            categories.setSelectionFromTop(categories.getFirstVisiblePosition()+child,top-categories.getPaddingTop());
        });
        settle();
        runOnMainSync(() -> categories.getViewTreeObserver().addOnPreDrawListener(observer));
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
        SystemClock.sleep(250); waitForIdleSync();
        runOnMainSync(() -> categories.getViewTreeObserver().removeOnPreDrawListener(observer));
        if(new java.util.HashSet<>(offsets).size()<3)throw new AssertionError("Boundary must scroll through intermediate frames: "+offsets);
        for(int i=1;i<offsets.size();i++)if(offsets.get(i)<offsets.get(i-1))throw new AssertionError("Boundary scrolled backward: "+offsets);
        checks++;
    }
    private void key(int key) { sendKeyDownUpSync(key); settle(); }
    private int[] snapshot(ListView list) {
        int[] result = new int[3];
        runOnMainSync(() -> {
            result[0]=list.getSelectedItemPosition(); result[1]=list.getFirstVisiblePosition();
            // ListView can retain an extra row entirely inside the top padding after
            // a layout. Compare the first row intersecting the actual content viewport.
            int child=0;
            while (child+1<list.getChildCount() && list.getChildAt(child).getBottom()<=list.getPaddingTop()) child++;
            result[1]+=child;
            result[2]=list.getChildCount()==0 ? 0 : list.getChildAt(child).getTop();
        });
        return result;
    }
    private int[] viewport(ListView list) { int[] s=snapshot(list); return new int[]{s[1],s[2]}; }
    private void same(String label, int[] expected, int[] actual) {
        if (!Arrays.equals(expected,actual)) throw new AssertionError(label+" expected="+Arrays.toString(expected)+" actual="+Arrays.toString(actual));
        checks++;
    }
    private void roundTrip(String label, boolean scrollChannels) {
        int[] left=snapshot(categories);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertFocus(channels);
        if (scrollChannels) for (int i=0;i<16;i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        int[] right=snapshot(channels);
        String group=(String)field("selectedCategory");
        changes=0;
        for (int i=0;i<5;i++) {
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            assertFocus(categories);
            same(label+" left",left,snapshot(categories));
            key(KeyEvent.KEYCODE_DPAD_RIGHT);
            assertFocus(channels);
            same(label+" right",right,snapshot(channels));
        }
        // Queue alternating keys without frame waits to exercise stale callbacks.
        for (int i=0;i<8;i++) { sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT); sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT); }
        settle();
        same(label+" rapid right",right,snapshot(channels));
        key(KeyEvent.KEYCODE_DPAD_LEFT);
        same(label+" rapid left",left,snapshot(categories));
        if (changes!=0 || !group.equals(field("selectedCategory"))) throw new AssertionError(label+" re-filtered group during focus transfer: "+changes);
        checks++;
    }

    private void assertFocus(ListView list) {
        runOnMainSync(() -> {
            if (!list.hasFocus()) throw new AssertionError("Expected focus on " + list.getContentDescription());
        });
    }
}
