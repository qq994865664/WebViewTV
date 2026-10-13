package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.vasthread.webviewtv.reconstructed.PlayerControlsView;
import java.util.ArrayList;
import java.util.Arrays;

/** Exercise Android touch dispatch, callback counts and overlay isolation. */
final class GestureChecks {
    private final Instrumentation instrumentation;
    private final Activity activity;
    private final ArrayList<String> actions = new ArrayList<>();
    private FrameLayout root;
    private PlayerControlsView controls;
    private View overlay;
    private float cx, cy, distance;
    private long time;
    private int checks;

    GestureChecks(Instrumentation instrumentation, Activity activity) {
        this.instrumentation = instrumentation;
        this.activity = activity;
    }

    int run() {
        instrumentation.runOnMainSync(() -> {
            root = new FrameLayout(activity);
            controls = new PlayerControlsView(activity, new PlayerControlsView.Listener() {
                public void onChannelSwipe(int step) { actions.add("channel:" + step); }
                public void onSourceSwipe(int step) { actions.add("source:" + step); }
            });
            controls.setOnClickListener(v -> actions.add("tap"));
            root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
            overlay = new View(activity);
            overlay.setClickable(true);
            overlay.setVisibility(View.INVISIBLE);
            root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(root);
        });
        instrumentation.waitForIdleSync();
        // Keep failures on the instrumentation thread, without crashing the main looper.
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> {
            try {
                cx = root.getWidth()/2f; cy = root.getHeight()/2f;
                distance = 80 * activity.getResources().getDisplayMetrics().density;
                swipe(0, -distance, 250); expect("up", "channel:1");
                swipe(0, distance, 250); expect("down", "channel:-1");
                swipe(-distance, 0, 250); expect("left", "source:1");
                swipe(distance, 0, 250); expect("right", "source:-1");
                swipe(0, -distance, 1500); expect("slow swipe", "channel:1");
                swipe(-distance, 0, 50); expect("fast swipe", "source:1");
                swipe(1, 1, 100); expect("tap with jitter", "tap");
                swipe(0, distance * .4f, 250); expect("short drag");
                swipe(distance, distance, 250); expect("ambiguous diagonal");
                swipe(distance * .2f, -distance, 250); expect("mostly vertical", "channel:1");
                swipe(0, 0, 2000); expect("long press");
                down(); move(0, -distance, 100); expect("no action before release");
                move(0, -distance*1.5f, 150); move(0, -distance, 200);
                up(0, -distance, 250); expect("one action per stroke", "channel:1");
                down(); move(distance, 0, 100); up(0, 0, 250); expect("drag back is not a tap");
                down(); move(0, -distance, 100);
                event(MotionEvent.ACTION_CANCEL, cx, cy-distance, 150);
                up(0, -distance, 250); expect("cancel");
                down(); secondPointer(); move(0, -distance, 100); up(0, -distance, 250); expect("multi touch");
                down(); controls.onWindowFocusChanged(false); up(0, -distance, 250); expect("window loses focus");
                controls.onWindowFocusChanged(true);
                overlay.setVisibility(View.VISIBLE);
                swipe(0, -distance, 250); swipe(distance, 0, 250); expect("panel blocks playback gestures");
                overlay.setVisibility(View.GONE);
                swipe(0, -distance, 250); expect("gestures resume after panel", "channel:1");
                swipe(0, 0, 100); expect("tap after swipe", "tap");
            } catch (Throwable error) { failure[0] = error; }
        });
        if (failure[0] != null) throw new AssertionError("Gesture regression", failure[0]);
        return checks;
    }

    private void expect(String label, String... expected) {
        if (!actions.equals(Arrays.asList(expected))) throw new AssertionError(label + ": " + actions);
        actions.clear(); checks++;
    }
    private void swipe(float dx, float dy, long duration) { down(); move(dx, dy, duration/2); up(dx, dy, duration); }
    private void down() { time=SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN,cx,cy,0); }
    private void move(float dx, float dy, long delta) { event(MotionEvent.ACTION_MOVE,cx+dx,cy+dy,delta); }
    private void up(float dx, float dy, long delta) { event(MotionEvent.ACTION_UP,cx+dx,cy+dy,delta); }
    private void event(int action, float x, float y, long delta) {
        MotionEvent e=MotionEvent.obtain(time,time+delta,action,x,y,0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(e); e.recycle();
    }
    private void secondPointer() {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i=0; i<2; i++) {
            props[i]=new MotionEvent.PointerProperties(); props[i].id=i; props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords(); coords[i].x=cx+i*30; coords[i].y=cy; coords[i].pressure=1; coords[i].size=1;
        }
        MotionEvent e=MotionEvent.obtain(time,time+20,MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        root.dispatchTouchEvent(e); e.recycle();
    }
}
