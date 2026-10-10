package de.elite12.musikbot.clientv2.audio;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.health.contributor.Status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpotifyAudioPipelineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsHistoryBelowOneTwentyMillisecondFrame() {
        Clientv2ServiceProperties properties = properties(temporaryDirectory.resolve("audio.pcm"));
        properties.setSpotifyAudioMaxHistory(Duration.ofMillis(19));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> pipeline(properties, mock(AudioPipelineTelemetry.class), () -> 0, ignored -> {}));

        assertTrue(failure.getMessage().contains("spotifyAudioMaxHistory"));
    }

    @Test
    void expectedPlaybackWithoutSourceBecomesDownAfterGraceAndPauseRecoversHealth() throws Exception {
        Path fifo = temporaryDirectory.resolve("silent.pcm");
        assumeFifoAvailable(fifo);
        AtomicLong now = new AtomicLong();
        PlaybackExpectation expectation = new PlaybackExpectation(now::get);
        SpotifyAudioPipeline pipeline = pipeline(properties(fifo), mock(AudioPipelineTelemetry.class),
                expectation, now::get, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
        pipeline.start();
        try {
            awaitState(pipeline.state(), AudioPipelineState.ReaderState.OPENING);
            assertEquals(Status.UP, pipeline.health().getStatus());

            expectation.playing();
            now.addAndGet(SpotifyAudioPipeline.SOURCE_SILENCE_GRACE.toNanos() + 1);
            assertEquals(Status.DOWN, pipeline.health().getStatus());
            assertEquals("Spotify playback expected but source PCM is stale",
                    pipeline.health().getDetails().get("reason"));

            expectation.notPlaying();
            assertEquals(Status.UP, pipeline.health().getStatus());
        } finally {
            pipeline.close();
        }
    }

    @Test
    void sourceArrivalIsRecordedAtReadAndConversionExcludesBlockedReadTime() throws Exception {
        AtomicLong now = new AtomicLong();
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SpotifyAudioPipeline pipeline = pipeline(telemetry, now::get, ignored -> {
            parked.countDown();
            await(release);
        });
        byte[] pcm = pcm(Duration.ofMillis(20), 100);
        ByteArrayInputStream delegate = new ByteArrayInputStream(pcm);
        InputStream delayed = new InputStream() {
            private boolean delayed;

            @Override
            public int read(byte[] target, int offset, int length) {
                if (!delayed) {
                    delayed = true;
                    now.addAndGet(Duration.ofMillis(5).toNanos());
                }
                return delegate.read(target, offset, length);
            }

            @Override
            public int read() {
                return delegate.read();
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> generation = executor.submit(() -> {
                try {
                    pipeline.runGeneration(delayed);
                } catch (IOException exception) {
                    throw new AssertionError(exception);
                }
            });
            assertTrue(parked.await(2, TimeUnit.SECONDS));
            assertEquals(Duration.ofMillis(5).toNanos(), pipeline.state().getLatestSourceArrivalNanos());
            verify(telemetry).conversion(Duration.ZERO);
            release.countDown();
            generation.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void emptySubscriberPollEmitsOneUnderrunPerStaleTransitionOnlyWhileExpected() {
        AtomicLong now = new AtomicLong();
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        PlaybackExpectation expectation = new PlaybackExpectation(now::get);
        SpotifyAudioPipeline pipeline = pipeline(properties(temporaryDirectory.resolve("audio.pcm")), telemetry,
                expectation, now::get, ignored -> {});
        SpotifyAudioSendHandler subscription = pipeline.broadcaster().subscribe();

        assertFalse(subscription.canProvide());
        expectation.playing();
        now.addAndGet(SpotifyAudioPipeline.SOURCE_SILENCE_GRACE.toNanos() + 1);
        assertFalse(subscription.canProvide());
        assertFalse(subscription.canProvide());
        verify(telemetry, times(1)).underrun();

        expectation.notPlaying();
        assertFalse(subscription.canProvide());
        expectation.playing();
        now.addAndGet(SpotifyAudioPipeline.SOURCE_SILENCE_GRACE.toNanos() + 1);
        assertFalse(subscription.canProvide());
        verify(telemetry, times(2)).underrun();
    }

    @Test
    void generationResetCountsAllQueuedSubscriberFrames() throws Exception {
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        SpotifyAudioPipeline pipeline = pipeline(telemetry, () -> 0, ignored -> {});
        pipeline.broadcaster().subscribe();
        pipeline.broadcaster().subscribe();
        pipeline.broadcaster().publish(new byte[]{1});
        pipeline.broadcaster().publish(new byte[]{2});

        pipeline.runGeneration(new ByteArrayInputStream(new byte[0]));

        verify(telemetry).dropped(4, AudioPipelineTelemetry.DropReason.GENERATION_RESET);
    }

    @Test
    void propertiesProvideExactAudioDefaults() {
        Clientv2ServiceProperties properties = new Clientv2ServiceProperties();

        assertEquals(Path.of("/run/musikbot/spotify.pcm"), properties.getSpotifyAudioFifo());
        assertEquals(Duration.ofMillis(250), properties.getSpotifyAudioRetryDelay());
        assertEquals(Duration.ofMillis(200), properties.getSpotifyAudioMaxHistory());
    }

    @Test
    void publishesOneConvertedFrameBeforeCleanEofClearsHistory() throws Exception {
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        AtomicLong now = new AtomicLong();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SpotifyAudioPipeline pipeline = pipeline(telemetry, now::get, ignored -> {
            parked.countDown();
            await(release);
        });
        SpotifyAudioSendHandler subscription = pipeline.broadcaster().subscribe();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> generation = executor.submit(() -> runGeneration(pipeline, pcm(Duration.ofMillis(20), 100)));
            assertTrue(parked.await(2, TimeUnit.SECONDS));
            assertTrue(subscription.canProvide());
            assertEquals(1, pipeline.state().getGeneration());
            assertEquals(AudioPipelineState.ReaderState.READING, pipeline.state().getReaderState());
            assertTrue(pipeline.state().isReady());
            assertEquals(20, pipeline.state().getBufferedMillis());
            verify(telemetry).outputFrame(any(Duration.class));

            release.countDown();
            generation.get(2, TimeUnit.SECONDS);
            assertFalse(subscription.canProvide());
            assertExpectedEofReset(pipeline.state());
            verify(telemetry, never()).startRecovery(any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void eofClearsSubscribersBeforeNextGeneration() throws Exception {
        SpotifyAudioPipeline pipeline = pipeline(mock(AudioPipelineTelemetry.class), () -> 0, ignored -> {});
        SpotifyAudioSendHandler subscription = pipeline.broadcaster().subscribe();

        pipeline.runGeneration(new ByteArrayInputStream(pcm(Duration.ofMillis(20), 100)));
        assertFalse(subscription.canProvide());
        assertExpectedEofReset(pipeline.state());
        pipeline.runGeneration(new ByteArrayInputStream(pcm(Duration.ofMillis(20), 200)));

        assertEquals(2, pipeline.state().getGeneration());
        assertFalse(subscription.canProvide());
        assertExpectedEofReset(pipeline.state());
    }

    @Test
    void fakeClockStallDiscardsEveryOverdueFrameAndPublishesNewestSelection() throws Exception {
        AtomicLong now = new AtomicLong();
        AtomicInteger parks = new AtomicInteger();
        CountDownLatch secondFrame = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        AudioPipelineTelemetry.Operation catchUp = mock(AudioPipelineTelemetry.Operation.class);
        when(telemetry.startCatchUp(39)).thenReturn(catchUp);
        SpotifyAudioPipeline pipeline = pipeline(telemetry, now::get, ignored -> {
            if (parks.getAndIncrement() == 0) {
                now.addAndGet(Duration.ofMillis(800).toNanos());
            } else if (parks.get() == 2) {
                secondFrame.countDown();
                await(release);
            }
        });
        SpotifyAudioSendHandler subscription = pipeline.broadcaster().subscribe();
        byte[] input = pcm(Duration.ofSeconds(1), 12_000);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> generation = executor.submit(() -> runGeneration(pipeline, input));
            assertTrue(secondFrame.await(2, TimeUnit.SECONDS));

            assertArrayEquals(convertedFrame(input, 0), bytes(subscription.provide20MsAudio()));
            assertArrayEquals(convertedFrame(input, 40), bytes(subscription.provide20MsAudio()));
            verify(telemetry).dropped(39, AudioPipelineTelemetry.DropReason.CATCH_UP);
            verify(telemetry, times(2)).sourceFrames(1);
            verify(telemetry).sourceFrames(39);
            var order = inOrder(telemetry, catchUp);
            order.verify(telemetry).startCatchUp(39);
            order.verify(catchUp).closeSuccess();
            order.verify(telemetry).outputFrame(any(Duration.class));
            release.countDown();
            generation.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void configuredHistoryIsCappedAtTenFrames() throws Exception {
        Clientv2ServiceProperties properties = properties(temporaryDirectory.resolve("audio"));
        properties.setSpotifyAudioMaxHistory(Duration.ofSeconds(1));
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        SpotifyAudioPipeline pipeline = pipeline(properties, telemetry, () -> 0,
                ignored -> {});
        SpotifyAudioSendHandler subscription = pipeline.broadcaster().subscribe();

        pipeline.runGeneration(new ByteArrayInputStream(pcm(Duration.ofMillis(240), 1_000)));

        assertFalse(subscription.canProvide(), "EOF must clear even maximum history");
        verify(telemetry, times(2)).dropped(1, AudioPipelineTelemetry.DropReason.SUBSCRIBER_OVERFLOW);
    }

    @Test
    void validFifoWaitingForSpotifydIsHealthyAndCloseWakesBlockedOpen() throws Exception {
        Path fifo = temporaryDirectory.resolve("waiting.pcm");
        assumeFifoAvailable(fifo);
        SpotifyAudioPipeline pipeline = pipeline(properties(fifo), mock(AudioPipelineTelemetry.class),
                System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));

        pipeline.start();
        awaitState(pipeline.state(), AudioPipelineState.ReaderState.OPENING);

        assertEquals(Status.UP, pipeline.health().getStatus());
        assertTrue(pipeline.state().isReady());
        pipeline.close();
        assertFalse(pipeline.workerAlive());
        assertEquals(Status.DOWN, pipeline.health().getStatus());
        assertFalse(pipeline.state().isReady());
    }

    @Test
    void stoppedBeforeValidationAndEofResetAreDown() throws Exception {
        SpotifyAudioPipeline pipeline = pipeline(mock(AudioPipelineTelemetry.class), () -> 0, ignored -> {});

        assertEquals(AudioPipelineState.ReaderState.STOPPED, pipeline.state().getReaderState());
        assertFalse(pipeline.state().isReady());
        assertEquals(Status.DOWN, pipeline.health().getStatus());

        pipeline.runGeneration(new ByteArrayInputStream(pcm(Duration.ofMillis(20), 100)));

        assertExpectedEofReset(pipeline.state());
        assertEquals(Status.DOWN, pipeline.health().getStatus());
    }

    @Test
    void activeReadingIsHealthy() throws Exception {
        Path fifo = temporaryDirectory.resolve("active.pcm");
        assumeFifoAvailable(fifo);
        SpotifyAudioPipeline pipeline = pipeline(properties(fifo), mock(AudioPipelineTelemetry.class),
                () -> 0, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            pipeline.start();
            Future<OutputStream> writer = executor.submit(() -> Files.newOutputStream(fifo));
            try (OutputStream output = writer.get(2, TimeUnit.SECONDS)) {
                output.write(pcm(Duration.ofMillis(40), 500));
                output.flush();
                awaitState(pipeline.state(), AudioPipelineState.ReaderState.READING);
                assertTrue(pipeline.state().isReady());
                assertEquals(Status.UP, pipeline.health().getStatus());
            }
        } finally {
            pipeline.close();
            executor.shutdownNow();
        }
    }

    @Test
    void closeTerminatesAWorkerBlockedReadingFromFifo() throws Exception {
        Path fifo = temporaryDirectory.resolve("reading.pcm");
        assumeFifoAvailable(fifo);
        SpotifyAudioPipeline pipeline = pipeline(properties(fifo), mock(AudioPipelineTelemetry.class),
                System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            pipeline.start();
            Future<OutputStream> writer = executor.submit(() -> Files.newOutputStream(fifo));
            try (OutputStream ignored = writer.get(2, TimeUnit.SECONDS)) {
                awaitGeneration(pipeline.state(), 1);
                pipeline.close();
            }
            assertFalse(pipeline.workerAlive());
        } finally {
            pipeline.close();
            executor.shutdownNow();
        }
    }

    @Test
    void invalidFifoAndStartupVerifierFailureAreDown() throws Exception {
        Path regularFile = Files.createFile(temporaryDirectory.resolve("regular.pcm"));
        AudioPipelineTelemetry invalidTelemetry = mock(AudioPipelineTelemetry.class);
        when(invalidTelemetry.startRecovery(any())).thenReturn(mock(AudioPipelineTelemetry.Operation.class));
        SpotifyAudioPipeline invalid = pipeline(properties(regularFile), invalidTelemetry,
                System::nanoTime, nanos -> Thread.sleep(Duration.ofNanos(nanos)));
        invalid.start();
        try {
            awaitState(invalid.state(), AudioPipelineState.ReaderState.FAILED);
            assertEquals(Status.DOWN, invalid.health().getStatus());
            assertFalse(invalid.state().isReady());
            assertEquals("Spotify audio FIFO I/O failure", invalid.health().getDetails().get("reason"));
        } finally {
            invalid.close();
        }

        IllegalStateException guardFailure = new IllegalStateException("sinc unavailable");
        SpotifyAudioPipeline guarded = new SpotifyAudioPipeline(
                properties(temporaryDirectory.resolve("unused")), new SoftwareVolume(), new AudioPipelineState(),
                mock(AudioPipelineTelemetry.class), new PlaybackExpectation(), System::nanoTime, ignored -> {}, () -> {
                    throw guardFailure;
                }
        );
        guarded.start();

        assertFalse(guarded.workerAlive());
        assertEquals(Status.DOWN, guarded.health().getStatus());
        assertEquals("Spotify PCM converter verification failed", guarded.health().getDetails().get("reason"));
        assertFalse(guarded.health().getDetails().toString().contains(guardFailure.getMessage()));
    }

    @Test
    void unexpectedIoRecoveryEndsOnFirstOutputAndNotAtGenerationReset() throws Exception {
        Path fifo = temporaryDirectory.resolve("late.pcm");
        AtomicLong now = new AtomicLong();
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        AudioPipelineTelemetry.Operation recovery = mock(AudioPipelineTelemetry.Operation.class);
        when(telemetry.startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE)).thenReturn(recovery);
        when(telemetry.startCatchUp(anyLong())).thenReturn(mock(AudioPipelineTelemetry.Operation.class));
        SpotifyAudioPipeline pipeline = pipeline(properties(fifo), telemetry, now::get,
                nanos -> Thread.sleep(Duration.ofNanos(nanos)));

        pipeline.start();
        verify(telemetry, timeout(2_000)).startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
        verify(telemetry, never()).fifoReopened(any());
        now.set(Duration.ofMillis(137).toNanos());
        assumeFifoAvailable(fifo);
        awaitState(pipeline.state(), AudioPipelineState.ReaderState.OPENING);
        assertTrue(pipeline.state().isReady());
        assertEquals(Status.UP, pipeline.health().getStatus());
        verify(recovery, never()).closeSuccess();
        verify(recovery, never()).closeFailure(any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                try (OutputStream output = Files.newOutputStream(fifo)) {
                    output.write(pcm(Duration.ofMillis(40), 500));
                }
                return null;
            });
            verify(recovery, timeout(2_000)).closeSuccess();
            verify(telemetry).fifoReopened(Duration.ofMillis(137));
            writer.get(2, TimeUnit.SECONDS);
            verify(telemetry, times(1)).startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
            verify(recovery, never()).closeFailure(any());
        } finally {
            pipeline.close();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalShutdownClosesOutstandingRecoveryAsFailure() {
        Path missing = temporaryDirectory.resolve("missing.pcm");
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        AudioPipelineTelemetry.Operation recovery = mock(AudioPipelineTelemetry.Operation.class);
        when(telemetry.startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE)).thenReturn(recovery);
        SpotifyAudioPipeline pipeline = pipeline(properties(missing), telemetry, System::nanoTime,
                nanos -> Thread.sleep(Duration.ofNanos(nanos)));

        pipeline.start();
        verify(telemetry, timeout(2_000)).startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
        assertFalse(pipeline.health().getDetails().toString().contains(missing.toString()));
        pipeline.close();

        verify(recovery).closeFailure(any());
    }

    @Test
    void recoveryCannotSucceedBeforePublicationAndOutputUpdatesSucceed() throws Exception {
        Path fifo = temporaryDirectory.resolve("publication-failure.pcm");
        AudioPipelineState state = new AudioPipelineState(() -> 0);
        AudioPipelineTelemetry telemetry = mock(AudioPipelineTelemetry.class);
        AudioPipelineTelemetry.Operation recovery = mock(AudioPipelineTelemetry.Operation.class);
        SoftwareVolume volume = mock(SoftwareVolume.class);
        AtomicReference<SpotifyAudioPipeline> reference = new AtomicReference<>();
        IllegalStateException publicationFailure = new IllegalStateException("publication failed at " + fifo);
        when(telemetry.startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE)).thenReturn(recovery);
        doAnswer(invocation -> {
            reference.get().broadcaster().publish(new byte[]{1});
            return null;
        }).when(volume).apply(any(byte[].class));
        doThrow(publicationFailure)
                .when(telemetry).dropped(1, AudioPipelineTelemetry.DropReason.SUBSCRIBER_OVERFLOW);
        Clientv2ServiceProperties properties = properties(fifo);
        properties.setSpotifyAudioMaxHistory(Duration.ofMillis(20));
        SpotifyAudioPipeline pipeline = new SpotifyAudioPipeline(
                properties, volume, state, telemetry, new PlaybackExpectation(() -> 0), () -> 0,
                nanos -> Thread.sleep(Duration.ofNanos(nanos)), () -> {}
        );
        reference.set(pipeline);
        pipeline.broadcaster().subscribe();

        pipeline.start();
        verify(telemetry, timeout(2_000)).startRecovery(AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
        assumeFifoAvailable(fifo);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                try (OutputStream output = Files.newOutputStream(fifo)) {
                    output.write(pcm(Duration.ofMillis(20), 500));
                }
                return null;
            });
            ArgumentCaptor<Throwable> terminalError = ArgumentCaptor.forClass(Throwable.class);
            verify(recovery, timeout(2_000)).closeFailure(terminalError.capture());
            assertSame(publicationFailure, terminalError.getValue());
            writer.get(2, TimeUnit.SECONDS);
            verify(recovery, never()).closeSuccess();
            verify(telemetry, never()).outputFrame(any());
            assertEquals(Status.DOWN, pipeline.health().getStatus());
            assertEquals("Spotify audio pipeline terminated unexpectedly",
                    pipeline.health().getDetails().get("reason"));
            assertFalse(pipeline.health().getDetails().toString().contains(fifo.toString()));
        } finally {
            pipeline.close();
            executor.shutdownNow();
        }
    }

    private SpotifyAudioPipeline pipeline(
            AudioPipelineTelemetry telemetry,
            java.util.function.LongSupplier clock,
            SpotifyAudioPipeline.Parker parker
    ) {
        return pipeline(properties(temporaryDirectory.resolve("audio.pcm")), telemetry, clock, parker);
    }

    private SpotifyAudioPipeline pipeline(
            Clientv2ServiceProperties properties,
            AudioPipelineTelemetry telemetry,
            java.util.function.LongSupplier clock,
            SpotifyAudioPipeline.Parker parker
    ) {
        return pipeline(properties, telemetry, new PlaybackExpectation(clock), clock, parker);
    }

    private SpotifyAudioPipeline pipeline(
            Clientv2ServiceProperties properties,
            AudioPipelineTelemetry telemetry,
            PlaybackExpectation expectation,
            java.util.function.LongSupplier clock,
            SpotifyAudioPipeline.Parker parker
    ) {
        return new SpotifyAudioPipeline(properties, new SoftwareVolume(), new AudioPipelineState(clock), telemetry,
                expectation, clock, parker, () -> {});
    }

    private static Clientv2ServiceProperties properties(Path fifo) {
        Clientv2ServiceProperties properties = new Clientv2ServiceProperties();
        properties.setSpotifyAudioFifo(fifo);
        properties.setSpotifyAudioRetryDelay(Duration.ofMillis(10));
        return properties;
    }

    private static void runGeneration(SpotifyAudioPipeline pipeline, byte[] input) {
        try {
            pipeline.runGeneration(new ByteArrayInputStream(input));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] pcm(Duration duration, int amplitude) {
        int frames = (int) (44_100 * duration.toNanos() / Duration.ofSeconds(1).toNanos());
        ByteArrayOutputStream output = new ByteArrayOutputStream(frames * 2 * Short.BYTES);
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (frame == 0 ? amplitude : -amplitude);
            writeS16Le(output, sample);
            writeS16Le(output, sample);
        }
        return output.toByteArray();
    }

    private static byte[] convertedFrame(byte[] input, int index) throws Exception {
        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] frame = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            assertEquals(index, converter.discardFrames(index, frame));
            assertTrue(converter.readFrame(frame));
            return frame.clone();
        }
    }

    private static byte[] bytes(ByteBuffer frame) {
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        return bytes;
    }

    private static void writeS16Le(ByteArrayOutputStream output, short sample) {
        output.write(sample);
        output.write(sample >> 8);
    }

    private static void assumeFifoAvailable(Path fifo) throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        Process process;
        try {
            process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        } catch (IOException exception) {
            assumeTrue(false, "mkfifo is unavailable");
            return;
        }
        assumeTrue(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0, "mkfifo failed");
    }

    private static void awaitState(AudioPipelineState state, AudioPipelineState.ReaderState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (state.getReaderState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, state.getReaderState());
    }

    private static void awaitGeneration(AudioPipelineState state, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (state.getGeneration() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, state.getGeneration());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void assertExpectedEofReset(AudioPipelineState state) {
        assertEquals(AudioPipelineState.ReaderState.STOPPED, state.getReaderState());
        assertFalse(state.isReady());
        assertEquals(Long.MIN_VALUE, state.getLatestSourceArrivalNanos());
        assertEquals(Long.MIN_VALUE, state.getLatestOutputFrameNanos());
        assertEquals(0, state.getSchedulerLatenessMillis());
        assertEquals(0, state.getBufferedMillis());
    }
}
