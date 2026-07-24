package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPipelineStateTest {

    @Test
    void startsNotReadyWithoutFrameTimestampsOrFailure() {
        AudioPipelineState state = new AudioPipelineState(() -> 0);

        assertFalse(state.isReady());
        assertEquals(AudioPipelineState.ReaderState.STOPPED, state.getReaderState());
        assertEquals(0, state.getGeneration());
        assertTrue(state.getReadinessFailure().isEmpty());
        assertEquals(0, state.getLatestFrameAgeMillis());
    }

    @Test
    void validOutputMakesPipelineReadyAndRecordsMonotonicTimestamps() {
        AtomicLong now = new AtomicLong(Duration.ofSeconds(1).toNanos());
        AudioPipelineState state = new AudioPipelineState(now::get);

        state.recordSourceFrame();
        now.addAndGet(Duration.ofMillis(4).toNanos());
        state.recordOutputFrame();

        assertTrue(state.isReady());
        assertEquals(AudioPipelineState.ReaderState.READING, state.getReaderState());
        assertEquals(Duration.ofSeconds(1).toNanos(), state.getLatestSourceFrameNanos());
        assertEquals(Duration.ofMillis(1004).toNanos(), state.getLatestOutputFrameNanos());
    }

    @Test
    void failureMakesPipelineNotReadyUntilNextValidOutput() {
        AudioPipelineState state = new AudioPipelineState(() -> 10);
        state.recordOutputFrame();

        state.failReadiness("FIFO unavailable");

        assertFalse(state.isReady());
        assertEquals(AudioPipelineState.ReaderState.FAILED, state.getReaderState());
        assertEquals("FIFO unavailable", state.getReadinessFailure().orElseThrow());

        state.recordOutputFrame();

        assertTrue(state.isReady());
        assertTrue(state.getReadinessFailure().isEmpty());
    }

    @Test
    void latestFrameAgeIsNonNegativeWhenClockMovesBackward() {
        AtomicLong now = new AtomicLong(Duration.ofSeconds(2).toNanos());
        AudioPipelineState state = new AudioPipelineState(now::get);
        state.recordOutputFrame();

        now.set(Duration.ofSeconds(1).toNanos());

        assertEquals(0, state.getLatestFrameAgeMillis());
    }

    @Test
    void subscriberAndBufferValuesArePublishedAtomically() {
        AudioPipelineState state = new AudioPipelineState(() -> 0);

        state.setSubscribers(3);
        state.setBufferedMillis(180);
        state.setSchedulerLatenessMillis(24);
        state.setGeneration(7);
        state.setReaderState(AudioPipelineState.ReaderState.RETRYING);

        assertEquals(3, state.getSubscribers());
        assertEquals(180, state.getBufferedMillis());
        assertEquals(24, state.getSchedulerLatenessMillis());
        assertEquals(7, state.getGeneration());
        assertEquals(AudioPipelineState.ReaderState.RETRYING, state.getReaderState());
        assertThrows(IllegalArgumentException.class, () -> state.setSchedulerLatenessMillis(-1));
    }

    @Test
    void telemetryReasonsHaveFixedLowCardinalityValues() {
        assertEquals("catch_up", AudioPipelineTelemetry.DropReason.CATCH_UP.telemetryValue());
        assertEquals("subscriber_overflow",
                AudioPipelineTelemetry.DropReason.SUBSCRIBER_OVERFLOW.telemetryValue());
        assertEquals("generation_reset",
                AudioPipelineTelemetry.DropReason.GENERATION_RESET.telemetryValue());
        assertEquals("io_failure", AudioPipelineTelemetry.RecoveryReason.IO_FAILURE.telemetryValue());
        assertEquals("conversion_failure",
                AudioPipelineTelemetry.RecoveryReason.CONVERSION_FAILURE.telemetryValue());
    }

    @Test
    void outputAndFailureTransitionsAlwaysPublishCoherentReaderStatus() throws Exception {
        AudioPipelineState state = new AudioPipelineState(() -> 10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 500; iteration++) {
                CyclicBarrier start = new CyclicBarrier(3);
                Future<?> output = executor.submit(() -> {
                    await(start);
                    state.recordOutputFrame();
                });
                Future<?> failure = executor.submit(() -> {
                    await(start);
                    state.failReadiness("FIFO unavailable");
                });

                await(start);
                output.get();
                failure.get();

                AudioPipelineState.ReaderStatus status = state.getReaderStatus();
                assertEquals(status.state() == AudioPipelineState.ReaderState.FAILED,
                        status.failure().isPresent(), status.toString());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void gaugeCleanupAttemptsEveryRegistrationAfterFailure() {
        AtomicInteger closed = new AtomicInteger();
        IllegalStateException expected = new IllegalStateException("first");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> AudioPipelineTelemetry.closeAll(
                        () -> {
                            closed.incrementAndGet();
                            throw expected;
                        },
                        () -> {
                            closed.incrementAndGet();
                            throw expected;
                        },
                        closed::incrementAndGet,
                        closed::incrementAndGet
                ));

        assertSame(expected, failure);
        assertEquals(4, closed.get());
    }

    @Test
    void schedulerLatenessRejectsNegativeDurations() {
        try (AudioPipelineTelemetry telemetry = new AudioPipelineTelemetry(new AudioPipelineState(() -> 0))) {
            assertThrows(IllegalArgumentException.class,
                    () -> telemetry.schedulerLateness(Duration.ofMillis(-1)));
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
