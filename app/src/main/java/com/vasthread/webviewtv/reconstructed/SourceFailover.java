package com.vasthread.webviewtv.reconstructed;

/** Foreground playback watchdog. Times are monotonic milliseconds, supplied by the caller. */
public final class SourceFailover {
    public static final int WAIT = -1, EXHAUSTED = -2;
    private int timeoutSeconds = 15, current;
    private boolean[] attempted = new boolean[0];
    private long lastProgress;
    private boolean exhausted;

    public static boolean validTimeout(int seconds) {
        return seconds == 0 || seconds == 5 || seconds == 10 || seconds == 15 || seconds == 30;
    }
    public void configure(int seconds) {
        if (!validTimeout(seconds)) throw new IllegalArgumentException("Unsupported source timeout");
        timeoutSeconds = seconds;
    }
    public void start(int count, int source, long now, boolean retry) {
        if (!retry || attempted.length != count) attempted = new boolean[count];
        current = source;
        if (current >= 0 && current < count) attempted[current] = true;
        exhausted = false;
        lastProgress = now;
    }
    public void progress(long now) {
        // A new period of actual playback allows recovery from a later, separate outage.
        java.util.Arrays.fill(attempted, false);
        if (current >= 0 && current < attempted.length) attempted[current] = true;
        exhausted = false;
        lastProgress = now;
    }
    public void defer(long now) { lastProgress = now; }
    public int next(long now) {
        if (timeoutSeconds == 0 || exhausted || attempted.length == 0
                || now - lastProgress < timeoutSeconds * 1000L) return WAIT;
        for (int step = 1; step < attempted.length; step++) {
            int candidate = (current + step) % attempted.length;
            if (!attempted[candidate]) return candidate;
        }
        exhausted = true;
        return EXHAUSTED;
    }
}
