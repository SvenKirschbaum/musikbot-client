package de.elite12.musikbot.clientv2.audio;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

@Component
public final class SpotifyAudioPipeline implements HealthIndicator, AutoCloseable {

    private static final int MAX_HISTORY_FRAMES = 10;
    private static final long FRAME_MILLIS = 20;
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final int FILE_TYPE_MASK = 0170000;
    private static final int FIFO_TYPE = 0010000;
    private static final String STARTUP_FAILURE_REASON = "Spotify PCM converter verification failed";
    private static final String TERMINAL_FAILURE_REASON = "Spotify audio pipeline terminated unexpectedly";

    private final Path fifo;
    private final Duration retryDelay;
    private final SoftwareVolume volume;
    private final AudioPipelineState state;
    private final AudioPipelineTelemetry telemetry;
    private final AudioFrameBroadcaster broadcaster;
    private final LongSupplier nanoTime;
    private final Parker parker;
    private final Runnable verifier;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean waitingForWriter = new AtomicBoolean();
    private final AtomicReference<InputStream> activeInput = new AtomicReference<>();
    private final AtomicReference<AudioPipelineTelemetry.Operation> recovery = new AtomicReference<>();
    private final AtomicReference<Throwable> lastUnexpectedFailure = new AtomicReference<>();

    private volatile Thread worker;
    private volatile Throwable terminalFailure;
    private volatile String terminalHealthReason;

    public SpotifyAudioPipeline(
            Clientv2ServiceProperties properties,
            SoftwareVolume volume,
            AudioPipelineState state,
            AudioPipelineTelemetry telemetry
    ) {
        this(properties, volume, state, telemetry, System::nanoTime, LockSupport::parkNanos,
                CorrettoSincVerifier::verify);
    }

    SpotifyAudioPipeline(
            Clientv2ServiceProperties properties,
            SoftwareVolume volume,
            AudioPipelineState state,
            AudioPipelineTelemetry telemetry,
            LongSupplier nanoTime,
            Parker parker,
            Runnable verifier
    ) {
        Objects.requireNonNull(properties, "properties");
        fifo = Objects.requireNonNull(properties.getSpotifyAudioFifo(), "spotifyAudioFifo");
        retryDelay = requirePositive(properties.getSpotifyAudioRetryDelay(), "spotifyAudioRetryDelay");
        Duration maxHistory = requirePositive(properties.getSpotifyAudioMaxHistory(), "spotifyAudioMaxHistory");
        int historyFrames = Math.min(MAX_HISTORY_FRAMES,
                Math.max(1, Math.toIntExact(maxHistory.toMillis() / FRAME_MILLIS)));
        this.volume = Objects.requireNonNull(volume, "volume");
        this.state = Objects.requireNonNull(state, "state");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.parker = Objects.requireNonNull(parker, "parker");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        broadcaster = new AudioFrameBroadcaster(historyFrames,
                count -> telemetry.dropped(count, AudioPipelineTelemetry.DropReason.SUBSCRIBER_OVERFLOW));
    }

    public AudioFrameBroadcaster broadcaster() {
        return broadcaster;
    }

    public SoftwareVolume volume() {
        return volume;
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            verifier.run();
        } catch (RuntimeException | Error failure) {
            terminalHealthReason = STARTUP_FAILURE_REASON;
            terminalFailure = failure;
            state.failReadiness(STARTUP_FAILURE_REASON);
            running.set(false);
            return;
        }

        worker = Thread.ofVirtual().name("spotify-audio-pipeline").start(this::runWorker);
    }

    void runGeneration(InputStream input) throws IOException {
        runGeneration(input, false);
    }

    @Override
    public Health health() {
        AudioPipelineState.ReaderStatus status = state.getReaderStatus();
        Health.Builder health;
        Thread currentWorker = worker;
        if (terminalFailure != null) {
            health = Health.down().withDetail("reason", terminalHealthReason);
        } else if (currentWorker == null || !currentWorker.isAlive()) {
            health = Health.down().withDetail("reason", "Audio pipeline worker is not running");
        } else if (!status.ready()) {
            health = Health.down().withDetail("reason", status.failure().orElse("Audio pipeline failed"));
        } else {
            health = Health.up();
        }
        return health
                .withDetail("readerState", status.state().name())
                .withDetail("generation", state.getGeneration())
                .withDetail("lastFrameAgeMillis", state.getLatestFrameAgeMillis())
                .withDetail("schedulerLatenessMillis", state.getSchedulerLatenessMillis())
                .withDetail("bufferedMillis", state.getBufferedMillis())
                .withDetail("subscribers", state.getSubscribers())
                .build();
    }

    @PreDestroy
    @Override
    public void close() {
        running.set(false);
        IOException closeFailure = closeActiveInput();
        RuntimeException wakeFailure = wakeBlockedOpen();

        Thread currentWorker = worker;
        if (currentWorker != null) {
            currentWorker.interrupt();
            try {
                currentWorker.join(SHUTDOWN_TIMEOUT);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping Spotify audio pipeline", interrupted);
            }
            if (currentWorker.isAlive()) {
                throw new IllegalStateException("Spotify audio pipeline worker did not stop within "
                        + SHUTDOWN_TIMEOUT);
            }
        }
        resetAfterExpectedEof();
        if (closeFailure != null) {
            throw new IllegalStateException("Failed to close Spotify audio FIFO", closeFailure);
        }
        if (wakeFailure != null) {
            throw wakeFailure;
        }
    }

    AudioPipelineState state() {
        return state;
    }

    boolean workerAlive() {
        Thread currentWorker = worker;
        return currentWorker != null && currentWorker.isAlive();
    }

    private void runWorker() {
        try {
            while (running.get()) {
                try {
                    verifyFifo();
                    if (recovery.get() == null) {
                        state.setReaderState(AudioPipelineState.ReaderState.OPENING);
                    }
                    telemetry.fifoReopened(retryDelay);
                    InputStream input = openFifo();
                    if (input == null) {
                        break;
                    }
                    try (input) {
                        runGeneration(input, true);
                    } finally {
                        activeInput.compareAndSet(input, null);
                    }
                } catch (InterruptedIOException failure) {
                    if (running.get()) {
                        recover(failure, AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
                    }
                } catch (IOException failure) {
                    if (running.get()) {
                        recover(failure, AudioPipelineTelemetry.RecoveryReason.IO_FAILURE);
                    }
                } catch (IllegalArgumentException failure) {
                    if (running.get()) {
                        telemetry.conversionFailure(failure);
                        recover(failure, AudioPipelineTelemetry.RecoveryReason.CONVERSION_FAILURE);
                    }
                } catch (RuntimeException | Error failure) {
                    lastUnexpectedFailure.set(failure);
                    terminalHealthReason = TERMINAL_FAILURE_REASON;
                    terminalFailure = failure;
                    state.failReadiness(TERMINAL_FAILURE_REASON);
                    break;
                }

                if (running.get() && recovery.get() != null) {
                    sleepRetryDelay();
                }
            }
        } finally {
            waitingForWriter.set(false);
            closeActiveInput();
            AudioPipelineTelemetry.Operation pending = recovery.getAndSet(null);
            if (pending != null) {
                Throwable failure = lastUnexpectedFailure.get();
                pending.closeFailure(failure == null
                        ? new IllegalStateException("Audio pipeline stopped during recovery")
                        : failure);
            }
        }
    }

    private InputStream openFifo() throws IOException {
        waitingForWriter.set(true);
        try {
            if (!running.get()) {
                return null;
            }
            InputStream input = Files.newInputStream(fifo);
            activeInput.set(input);
            if (!running.get()) {
                input.close();
                activeInput.compareAndSet(input, null);
                return null;
            }
            return input;
        } finally {
            waitingForWriter.set(false);
        }
    }

    private void runGeneration(InputStream input, boolean managed) throws IOException {
        Objects.requireNonNull(input, "input");
        broadcaster.reset();
        state.incrementGeneration();
        RealtimeFramePacer pacer = new RealtimeFramePacer(nanoTime);
        pacer.reset();
        byte[] frame = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
        boolean expectedEof = false;

        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(input)) {
            while (!managed || running.get()) {
                long conversionStarted = nanoTime.getAsLong();
                if (!converter.readFrame(frame)) {
                    expectedEof = true;
                    break;
                }
                state.recordSourceFrame();
                telemetry.sourceFrames(1);

                long framesToDrop = pacer.framesToDrop();
                long latenessNanos = framesToDrop * RealtimeFramePacer.FRAME_NANOS;
                state.setSchedulerLatenessMillis(TimeUnit.NANOSECONDS.toMillis(latenessNanos));
                telemetry.schedulerLateness(Duration.ofNanos(latenessNanos));
                if (framesToDrop > 0) {
                    long discarded = discardFrames(converter, framesToDrop, frame);
                    telemetry.sourceFrames(discarded);
                    telemetry.dropped(discarded, AudioPipelineTelemetry.DropReason.CATCH_UP);
                    if (discarded < framesToDrop) {
                        expectedEof = true;
                        break;
                    }
                    state.recordSourceFrame();
                }

                telemetry.conversion(Duration.ofNanos(elapsedSince(conversionStarted)));
                volume.apply(frame);
                long frameAgeNanos = Math.max(0, nanoTime.getAsLong() - state.getLatestSourceFrameNanos());
                broadcaster.publish(frame.clone());
                state.recordOutputFrame();
                int subscribers = broadcaster.subscriberCount();
                long bufferedMillis = broadcaster.maxSubscriberQueueDepth() * FRAME_MILLIS;
                state.setSubscribers(subscribers);
                state.setBufferedMillis(bufferedMillis);
                telemetry.subscriberBuffer(Duration.ofMillis(bufferedMillis));
                telemetry.outputFrame(Duration.ofNanos(frameAgeNanos));
                completeRecovery();
                park(Duration.ofNanos(pacer.nanosUntilNextFrame()));
            }
        } finally {
            if (expectedEof) {
                resetAfterExpectedEof();
            } else {
                broadcaster.reset();
                state.setBufferedMillis(0);
            }
        }
    }

    private long discardFrames(SpotifyPcmConverter converter, long count, byte[] frame) throws IOException {
        AudioPipelineTelemetry.Operation catchUp = telemetry.startCatchUp(count);
        try {
            long discarded = converter.discardFrames(count, frame);
            catchUp.closeSuccess();
            return discarded;
        } catch (IOException | RuntimeException | Error failure) {
            catchUp.closeFailure(failure);
            throw failure;
        }
    }

    private void recover(Throwable failure, AudioPipelineTelemetry.RecoveryReason reason) {
        lastUnexpectedFailure.set(failure);
        state.failReadiness(switch (reason) {
            case IO_FAILURE -> "Spotify audio FIFO I/O failure";
            case CONVERSION_FAILURE -> "Spotify PCM conversion failure";
        });
        if (recovery.get() == null) {
            recovery.set(telemetry.startRecovery(reason));
        }
    }

    private void completeRecovery() {
        lastUnexpectedFailure.set(null);
        AudioPipelineTelemetry.Operation pending = recovery.getAndSet(null);
        if (pending != null) {
            pending.closeSuccess();
        }
    }

    private void resetAfterExpectedEof() {
        broadcaster.reset();
        state.resetForExpectedEof();
        state.setSubscribers(broadcaster.subscriberCount());
    }

    private void verifyFifo() throws IOException {
        int mode = (Integer) Files.getAttribute(fifo, "unix:mode");
        if ((mode & FILE_TYPE_MASK) != FIFO_TYPE) {
            throw new IOException("Configured Spotify audio endpoint is not a FIFO");
        }
    }

    private IOException closeActiveInput() {
        InputStream input = activeInput.getAndSet(null);
        if (input == null) {
            return null;
        }
        try {
            input.close();
            return null;
        } catch (IOException failure) {
            return failure;
        }
    }

    private RuntimeException wakeBlockedOpen() {
        if (!waitingForWriter.get()) {
            return null;
        }
        try (FileChannel ignored = FileChannel.open(
                fifo,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            // Linux opens a FIFO read/write without blocking and releases a pending reader open.
            return null;
        } catch (IOException failure) {
            if (workerAlive()) {
                return new IllegalStateException("Failed to wake Spotify audio FIFO reader", failure);
            }
            return null;
        }
    }

    private void sleepRetryDelay() {
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void park(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            parker.parkNanos(duration.toNanos());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private long elapsedSince(long started) {
        return Math.max(0, nanoTime.getAsLong() - started);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    @FunctionalInterface
    interface Parker {
        void parkNanos(long nanos) throws InterruptedException;
    }
}
