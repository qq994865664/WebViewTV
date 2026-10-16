package com.vasthread.webviewtv.reconstructed;

/** Foreground playback watchdog. Times are monotonic milliseconds, supplied by the caller. */
public final class SourceFailover {
    public static final int WAIT = -1, EXHAUSTED = -2, ROUND_LIMIT = -3, MANUAL_TIMEOUT = -4;
    public static final long ROUND_MILLIS = 60000;
    private int timeoutSeconds = 15, current;
    private boolean[] attempted = new boolean[0];
    private long lastProgress, roundStarted, pausedAt = -1, stableSince = -1;
    private boolean exhausted;

    public static boolean validTimeout(int seconds) {
        return seconds == 0 || seconds == 5 || seconds == 10 || seconds == 15 || seconds == 30;
    }
    public void configure(int seconds) {
        if (!validTimeout(seconds)) throw new IllegalArgumentException("Unsupported source timeout");
        timeoutSeconds = seconds;
    }
    public void start(int count, int source, long now, boolean retry) {
        if (!retry || attempted.length != count) { attempted = new boolean[count]; roundStarted = now; }
        current = source;
        if (current >= 0 && current < count) attempted[current] = true;
        exhausted = false;
        lastProgress = now;
        pausedAt = -1; stableSince = -1;
    }
    public void progress(long now) {
        if (pausedAt >= 0) return;
        // A new period of actual playback allows recovery from a later, separate outage.
        if (stableSince < 0 || now - lastProgress > 2500) stableSince = now;
        if (now - stableSince >= 2000) {
            java.util.Arrays.fill(attempted, false);
            if (current >= 0 && current < attempted.length) attempted[current] = true;
        }
        // Actual media progress ends the preceding wait even when a slow WebView
        // reports less often than the stability threshold. Keep source-history
        // reset separate: a single frame must not reopen every failed source.
        roundStarted = now;
        exhausted = false;
        lastProgress = now;
    }
    public void pause(long now) { if (pausedAt < 0) pausedAt = now; }
    public void resume(long now) { if (pausedAt >= 0) { long d = Math.max(0, now - pausedAt); lastProgress += d; roundStarted += d; if (stableSince >= 0) stableSince += d; pausedAt = -1; } }
    public long elapsed(long now) { return Math.max(0, (pausedAt >= 0 ? pausedAt : now) - lastProgress); }
    public long sourceBudget() { return (timeoutSeconds == 0 ? 18 : timeoutSeconds) * 1000L; }
    public long remaining(long now) { long t = pausedAt >= 0 ? pausedAt : now; return Math.max(0, Math.min(sourceBudget() - (t - lastProgress), ROUND_MILLIS - (t - roundStarted))); }
    public int attemptedCount() { int n=0; for (boolean b : attempted) if(b)n++; return n; }
    public boolean willSwitch(long now) { long t=pausedAt>=0?pausedAt:now; return timeoutSeconds>0 && attemptedCount()<attempted.length && sourceBudget()-(t-lastProgress)<ROUND_MILLIS-(t-roundStarted); }
    public boolean isStopped() { return exhausted; }
    public int next(long now) {
        if (exhausted || pausedAt >= 0 || attempted.length == 0) return WAIT;
        if (now - roundStarted >= ROUND_MILLIS) { exhausted = true; return ROUND_LIMIT; }
        if (now - lastProgress < sourceBudget()) return WAIT;
        if (timeoutSeconds == 0) { exhausted = true; return MANUAL_TIMEOUT; }
        for (int step = 1; step < attempted.length; step++) {
            int candidate = (current + step) % attempted.length;
            if (!attempted[candidate]) return candidate;
        }
        exhausted = true;
        return EXHAUSTED;
    }
}
