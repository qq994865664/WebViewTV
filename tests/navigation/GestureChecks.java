package com.vasthread.webviewtv.navigationtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import com.vasthread.webviewtv.reconstructed.PlayerControlsView;
import com.vasthread.webviewtv.reconstructed.ChannelSwipeAnimation;
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
    private View playback;
    private ChannelSwipeAnimation animation;
    private float cx, cy, distance;
    private long time;
    private int checks;
    private int committed;
    private float outgoingUp, incomingUp, incomingDown;
    private int sampling;

    GestureChecks(Instrumentation instrumentation, Activity activity) {
        this.instrumentation = instrumentation;
        this.activity = activity;
    }

    int run() {
        instrumentation.runOnMainSync(() -> {
            root = new FrameLayout(activity);
            playback = new View(activity);
            root.addView(playback, new FrameLayout.LayoutParams(-1, -1));
            animation = new ChannelSwipeAnimation(activity, playback);
            root.addView(animation, new FrameLayout.LayoutParams(-1, -1));
            controls = new PlayerControlsView(activity, new PlayerControlsView.Listener() {
                public void onChannelSwipe(int step) { actions.add("channel:" + step); animation.finish(() -> committed++); }
                public void onSourceSwipe(int step) { actions.add("source:" + step); }
                public void onChannelDrag(float distance, boolean ready) { animation.drag(distance, ready, "Test channel"); }
                public void onChannelDragCancelled() { animation.cancelDrag(); }
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
        checkOnMain(() -> {
            animation.reset(); committed = 0;
            root.getViewTreeObserver().addOnPreDrawListener(() -> {
                float y = playback.getTranslationY();
                if (sampling == 1 && committed == 0) outgoingUp = Math.min(outgoingUp, y);
                if (sampling == 1 && committed == 1) incomingUp = Math.max(incomingUp, y);
                if (sampling == 2 && committed == 3) incomingDown = Math.min(incomingDown, y);
                return true;
            });
            sampling = 1;
            down(); move(0, -distance, 100);
            verify("page follows full finger distance", Math.abs(playback.getTranslationY()+distance)<1 && animation.getVisibility()==View.VISIBLE);
            expect("preview must not switch");
            up(0, -distance, 250);
            expect("one committed gesture", "channel:1");
            verify("old page stays until exit", committed == 0);
        });
        waitForPage();
        checkOnMain(() -> {
            verify("outgoing page continues upward", outgoingUp < -distance);
            verify("next page enters from below once", committed == 1 && incomingUp > 0);
            verifyRest("committed page completes");
            sampling = 0;
        });
        checkOnMain(() -> {
            down(); move(0, distance*.4f, 100);
            verify("short drag follows finger", playback.getTranslationY()>0);
            up(0, distance*.4f, 250); expect("short drag only rebounds");
        });
        SystemClock.sleep(300); instrumentation.waitForIdleSync();
        checkOnMain(() -> verifyRest("cancel returns to origin"));
        checkOnMain(() -> {
            swipe(0, -distance, 250); expect("first rapid swipe", "channel:1");
            down(); move(0, distance, 100);
            verify("new drag replaces old animation", playback.getTranslationY()>0);
            up(0, distance, 250); expect("second rapid swipe", "channel:-1");
            verify("rapid drag completes preceding request once", committed == 2);
            controls.cancelGesture(); animation.reset();
            verifyRest("panel reset removes animation");
            down(); move(0, -distance, 100);
            controls.cancelGesture(); animation.reset();
            up(0, -distance, 250);
            expect("interrupted drag does not change channel");
        });
        SystemClock.sleep(350); instrumentation.waitForIdleSync();
        checkOnMain(() -> {
            verifyRest("cancelled animator cannot move playback later");
            verify("cancelled pending page cannot change channel later", committed == 2);
            sampling = 2;
            swipe(0, distance, 250); expect("downward page", "channel:-1");
        });
        waitForPage();
        checkOnMain(() -> {
            verify("previous page enters from above", committed == 3 && incomingDown < 0);
            verifyRest("downward page completes");
        });
        return checks;
    }

    private void waitForPage() {
        float durationScale = android.provider.Settings.Global.getFloat(activity.getContentResolver(), "animator_duration_scale", 1);
        SystemClock.sleep((long)(600 * Math.max(1, durationScale)) + 100);
        instrumentation.waitForIdleSync();
    }

    private void checkOnMain(Runnable check) {
        Throwable[] failure = new Throwable[1];
        instrumentation.runOnMainSync(() -> { try { check.run(); } catch (Throwable error) { failure[0]=error; } });
        if (failure[0]!=null) throw new AssertionError("Animation regression", failure[0]);
    }
    private void verify(String label, boolean condition) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private void verifyRest(String label) {
        verify(label, Math.abs(playback.getTranslationY())<.01f && animation.getVisibility()!=View.VISIBLE && animation.getAlpha()==0);
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
