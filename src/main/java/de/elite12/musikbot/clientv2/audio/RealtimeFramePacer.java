package de.elite12.musikbot.clientv2.audio;

import java.util.Objects;
import java.util.function.LongSupplier;

public final class RealtimeFramePacer {

    public static final long FRAME_NANOS = 20_000_000L;

    private final LongSupplier nanoTime;
    private long nextFrameDeadline;

    public RealtimeFramePacer(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void reset() {
        nextFrameDeadline = nanoTime.getAsLong() + FRAME_NANOS;
    }

    public long framesToDrop() {
        long overdueNanos = nanoTime.getAsLong() - nextFrameDeadline;
        if (overdueNanos < 0) {
            return 0;
        }

        long staleFrames = overdueNanos / FRAME_NANOS;
        nextFrameDeadline += (staleFrames + 1) * FRAME_NANOS;
        return staleFrames;
    }

    public long nanosUntilNextFrame() {
        return Math.max(0, nextFrameDeadline - nanoTime.getAsLong());
    }
}
