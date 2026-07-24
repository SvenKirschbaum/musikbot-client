package de.elite12.musikbot.clientv2.audio;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

@Component
public final class AudioPipelineState {

    private static final long NO_FRAME = Long.MIN_VALUE;

    private final LongSupplier nanoTime;
    private final AtomicReference<ReaderStatus> readerStatus = new AtomicReference<>(
            new ReaderStatus(ReaderState.STOPPED, Optional.empty())
    );
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong latestSourceFrameNanos = new AtomicLong(NO_FRAME);
    private final AtomicLong latestOutputFrameNanos = new AtomicLong(NO_FRAME);
    private final AtomicLong bufferedMillis = new AtomicLong();
    private final AtomicLong schedulerLatenessMillis = new AtomicLong();
    private final AtomicInteger subscribers = new AtomicInteger();

    public AudioPipelineState() {
        this(System::nanoTime);
    }

    public AudioPipelineState(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void recordSourceFrame() {
        setLatestSourceFrameNanos(nanoTime.getAsLong());
    }

    public void recordOutputFrame() {
        setLatestOutputFrameNanos(nanoTime.getAsLong());
        readerStatus.set(new ReaderStatus(ReaderState.READING, Optional.empty()));
    }

    public void failReadiness(String reason) {
        readerStatus.set(new ReaderStatus(
                ReaderState.FAILED,
                Optional.of(Objects.requireNonNull(reason, "reason"))
        ));
    }

    public boolean isReady() {
        return readerStatus.get().ready();
    }

    public void resetForExpectedEof() {
        readerStatus.set(new ReaderStatus(ReaderState.STOPPED, Optional.empty()));
        latestSourceFrameNanos.set(NO_FRAME);
        latestOutputFrameNanos.set(NO_FRAME);
        schedulerLatenessMillis.set(0);
        bufferedMillis.set(0);
    }

    public long getLatestFrameAgeMillis() {
        long latestFrame = latestOutputFrameNanos.get();
        if (latestFrame == NO_FRAME) {
            return 0;
        }
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - latestFrame)).toMillis();
    }

    public ReaderState getReaderState() {
        return readerStatus.get().state();
    }

    public void setReaderState(ReaderState readerState) {
        Objects.requireNonNull(readerState, "readerState");
        if (readerState == ReaderState.FAILED) {
            throw new IllegalArgumentException("Use failReadiness to publish a failure reason");
        }
        readerStatus.set(new ReaderStatus(readerState, Optional.empty()));
    }

    public ReaderStatus getReaderStatus() {
        return readerStatus.get();
    }

    public long getGeneration() {
        return generation.get();
    }

    public void setGeneration(long generation) {
        this.generation.set(generation);
    }

    public long incrementGeneration() {
        return generation.incrementAndGet();
    }

    public long getLatestSourceFrameNanos() {
        return latestSourceFrameNanos.get();
    }

    public void setLatestSourceFrameNanos(long latestSourceFrameNanos) {
        this.latestSourceFrameNanos.set(latestSourceFrameNanos);
    }

    public long getLatestOutputFrameNanos() {
        return latestOutputFrameNanos.get();
    }

    public void setLatestOutputFrameNanos(long latestOutputFrameNanos) {
        this.latestOutputFrameNanos.set(latestOutputFrameNanos);
    }

    public long getBufferedMillis() {
        return bufferedMillis.get();
    }

    public void setBufferedMillis(long bufferedMillis) {
        this.bufferedMillis.set(bufferedMillis);
    }

    public long getSchedulerLatenessMillis() {
        return schedulerLatenessMillis.get();
    }

    public void setSchedulerLatenessMillis(long schedulerLatenessMillis) {
        if (schedulerLatenessMillis < 0) {
            throw new IllegalArgumentException("schedulerLatenessMillis must not be negative");
        }
        this.schedulerLatenessMillis.set(schedulerLatenessMillis);
    }

    public int getSubscribers() {
        return subscribers.get();
    }

    public void setSubscribers(int subscribers) {
        this.subscribers.set(subscribers);
    }

    public Optional<String> getReadinessFailure() {
        return readerStatus.get().failure();
    }

    public record ReaderStatus(ReaderState state, Optional<String> failure) {

        public ReaderStatus {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(failure, "failure");
            if ((state == ReaderState.FAILED) != failure.isPresent()) {
                throw new IllegalArgumentException("Only FAILED reader status has a failure reason");
            }
        }

        public boolean ready() {
            return state == ReaderState.OPENING || state == ReaderState.READING;
        }
    }

    public enum ReaderState {
        STOPPED,
        OPENING,
        READING,
        RETRYING,
        FAILED
    }
}
