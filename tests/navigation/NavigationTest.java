package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;
import java.lang.reflect.Field;
import java.util.Arrays;

/** Runs real Android ListViews and DPAD events; no production hooks or app data. */
public final class NavigationTest extends Instrumentation {
    NavigationActivity activity;
    ListView categories, channels;
    int changes, checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = (NavigationActivity) startActivitySync(new Intent(getTargetContext(), NavigationActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            settle();
            runOnMainSync(() -> {
                categories = (ListView) field("categoryList");
                channels = (ListView) field("channelList");
                channels.getAdapter().registerDataSetObserver(new DataSetObserver() {
                    @Override public void onChanged() { changes++; }
                });
            });
            key(KeyEvent.KEYCODE_DPAD_LEFT);
            // Scroll well beyond the first page by normal remote input.
            for (int i = 0; i < 22; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            roundTrip("deep group and scrolled channels", true);
            // Return at bottom/top boundaries must also keep pixel offsets.
            runOnMainSync(() -> categories.setSelection(categories.getCount() - 1));
            settle(); roundTrip("last group", false);
            runOnMainSync(() -> categories.setSelection(0));
            settle(); roundTrip("first group", false);
            // Empty Favorites transfers to the toolbar, then back to the same group.
            key(KeyEvent.KEYCODE_DPAD_DOWN);
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
