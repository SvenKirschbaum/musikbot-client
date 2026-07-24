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
    private final AtomicReference<ReaderState> readerState = new AtomicReference<>(ReaderState.STOPPED);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong latestSourceFrameNanos = new AtomicLong(NO_FRAME);
    private final AtomicLong latestOutputFrameNanos = new AtomicLong(NO_FRAME);
    private final AtomicLong bufferedMillis = new AtomicLong();
    private final AtomicLong driftMillis = new AtomicLong();
    private final AtomicInteger subscribers = new AtomicInteger();
    private final AtomicReference<String> readinessFailure = new AtomicReference<>();

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
        readerState.set(ReaderState.READING);
        readinessFailure.set(null);
    }

    public void failReadiness(String reason) {
        readinessFailure.set(Objects.requireNonNull(reason, "reason"));
        readerState.set(ReaderState.FAILED);
    }

    public boolean isReady() {
        return latestOutputFrameNanos.get() != NO_FRAME
                && readerState.get() == ReaderState.READING
                && readinessFailure.get() == null;
    }

    public long getLatestFrameAgeMillis() {
        long latestFrame = latestOutputFrameNanos.get();
        if (latestFrame == NO_FRAME) {
            return 0;
        }
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - latestFrame)).toMillis();
    }

    public ReaderState getReaderState() {
        return readerState.get();
    }

    public void setReaderState(ReaderState readerState) {
        this.readerState.set(Objects.requireNonNull(readerState, "readerState"));
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

    public long getDriftMillis() {
        return driftMillis.get();
    }

    public void setDriftMillis(long driftMillis) {
        this.driftMillis.set(driftMillis);
    }

    public int getSubscribers() {
        return subscribers.get();
    }

    public void setSubscribers(int subscribers) {
        this.subscribers.set(subscribers);
    }

    public Optional<String> getReadinessFailure() {
        return Optional.ofNullable(readinessFailure.get());
    }

    public enum ReaderState {
        STOPPED,
        OPENING,
        READING,
        RETRYING,
        FAILED
    }
}
