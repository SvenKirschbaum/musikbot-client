package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeFramePacerTest {

    @Test
    void reportsNoDropBeforeDeadline() {
        AtomicLong now = new AtomicLong(100);
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();

        now.addAndGet(RealtimeFramePacer.FRAME_NANOS - 1);

        assertEquals(0, pacer.framesToDrop());
        assertEquals(1, pacer.nanosUntilNextFrame());
    }

    @Test
    void exactBoundaryPublishesCurrentFrameWithoutDrop() {
        AtomicLong now = new AtomicLong();
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();

        now.set(RealtimeFramePacer.FRAME_NANOS);

        assertEquals(0, pacer.framesToDrop());
        assertEquals(RealtimeFramePacer.FRAME_NANOS, pacer.nanosUntilNextFrame());
    }

    @Test
    void reportsOverdueFramesInsteadOfAccumulatingDelay() {
        AtomicLong now = new AtomicLong();
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();
        now.set(Duration.ofMillis(820).toNanos());

        assertEquals(40, pacer.framesToDrop());
        assertTrue(pacer.nanosUntilNextFrame() <= Duration.ofMillis(20).toNanos());
    }

    @Test
    void advancesFromScheduledDeadlinesInsteadOfLateClockReadings() {
        AtomicLong now = new AtomicLong();
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();
        now.set(Duration.ofMillis(25).toNanos());

        assertEquals(0, pacer.framesToDrop());

        assertEquals(Duration.ofMillis(15).toNanos(), pacer.nanosUntilNextFrame());
        now.set(Duration.ofMillis(40).toNanos());
        assertEquals(0, pacer.framesToDrop());
        assertEquals(RealtimeFramePacer.FRAME_NANOS, pacer.nanosUntilNextFrame());
    }

    @Test
    void resetStartsANewGenerationFromCurrentTime() {
        AtomicLong now = new AtomicLong();
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();
        now.set(Duration.ofMillis(820).toNanos());
        assertEquals(40, pacer.framesToDrop());

        now.set(Duration.ofSeconds(2).toNanos());
        pacer.reset();

        assertEquals(0, pacer.framesToDrop());
        assertEquals(RealtimeFramePacer.FRAME_NANOS, pacer.nanosUntilNextFrame());
    }

    @Test
    void handlesNanoTimeWraparound() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - 10);
        RealtimeFramePacer pacer = new RealtimeFramePacer(now::get);
        pacer.reset();

        now.addAndGet(RealtimeFramePacer.FRAME_NANOS);

        assertEquals(0, pacer.framesToDrop());
        assertEquals(RealtimeFramePacer.FRAME_NANOS, pacer.nanosUntilNextFrame());
    }
}
