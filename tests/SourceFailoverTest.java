import com.vasthread.webviewtv.reconstructed.SourceFailover;

/** Deterministic playback-clock coverage; no network or wall-clock sleeps. */
public final class SourceFailoverTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    public static void main(String[] args) {
        for (int seconds : new int[]{0, 5, 10, 15, 30}) {
            SourceFailover f = new SourceFailover();
            f.configure(seconds);
            f.start(3, 0, 0, false);
            // Some box WebViews report actual media-clock advancement sparsely.
            // 3s is slower than the old 2.5s stability cutoff but faster than any timeout.
            for (long time = 3000; time <= 180000; time += 3000) {
                check(f.next(time - 1) == SourceFailover.WAIT, "playing before heartbeat: " + seconds + "/" + time);
                f.progress(time);
                check(f.next(time) == SourceFailover.WAIT, "playing after heartbeat: " + seconds + "/" + time);
            }
            check(f.remaining(180000) == f.sourceBudget(), "healthy playback has a full stall budget");
            check(f.next(180000 + f.sourceBudget() - 1) == SourceFailover.WAIT, "later outage respects configured timeout");
            check(f.next(180000 + f.sourceBudget()) == (seconds == 0 ? SourceFailover.MANUAL_TIMEOUT : 1),
                    "later genuine outage still ends or changes source");
        }
        SourceFailover f = new SourceFailover();
        f.configure(30); f.start(4, 0, 0, false);
        check(f.next(30000) == 1, "first failed source switches");
        f.start(4, 1, 30000, true);
        check(f.next(59999) == SourceFailover.WAIT, "retry preserves remaining round budget");
        check(f.next(60000) == SourceFailover.ROUND_LIMIT, "no playback still stops at sixty seconds");
        check(f.next(61000) == SourceFailover.WAIT, "failure reported only once");

        f.start(4, 0, 0, false); f.start(4, 1, 30000, true);
        f.progress(59000);
        check(f.next(60000) == SourceFailover.WAIT, "late genuine playback cancels old waiting round");
        check(f.attemptedCount() == 2, "one frame does not clear attempted-source history");
        check(f.next(89000) == 2, "brief recovery retains attempted-source history");
        f.start(4, 2, 89000, true);
        check(f.next(119000) == SourceFailover.ROUND_LIMIT, "new outage retains cumulative sixty-second limit");

        f.start(3, 0, 0, false); f.start(3, 1, 30000, true);
        f.progress(31000); f.progress(32000); f.progress(33000);
        check(f.attemptedCount() == 1, "sustained recovery resets source history");
        f.pause(34000); f.progress(90000);
        check(f.remaining(95000) == 29000, "paused callbacks do not renew deadline");
        f.resume(94000);
        check(f.next(122999) == SourceFailover.WAIT, "resume preserves budget");
        check(f.next(123000) == 2, "pause does not disable later recovery");
        System.out.println("PASS " + checks + " source watchdog assertions");
    }
}
