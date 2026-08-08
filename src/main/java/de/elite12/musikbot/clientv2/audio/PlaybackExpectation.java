package de.elite12.musikbot.clientv2.audio;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

@Component
public final class PlaybackExpectation {

    private final LongSupplier nanoTime;
    private final AtomicReference<State> state = new AtomicReference<>(new State(false, Long.MIN_VALUE));

    public PlaybackExpectation() {
        this(System::nanoTime);
    }

    PlaybackExpectation(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public void playing() {
        state.set(new State(true, nanoTime.getAsLong()));
    }

    public void notPlaying() {
        state.set(new State(false, Long.MIN_VALUE));
    }

    public boolean isExpected() {
        return state.get().expected();
    }

    public boolean isSourceStale(Duration grace, long latestSourceNanos) {
        Objects.requireNonNull(grace, "grace");
        if (grace.isNegative()) {
            throw new IllegalArgumentException("grace must not be negative");
        }
        State current = state.get();
        if (!current.expected()) {
            return false;
        }
        long baseline = Math.max(current.sinceNanos(), latestSourceNanos);
        return Math.max(0, nanoTime.getAsLong() - baseline) > grace.toNanos();
    }

    private record State(boolean expected, long sinceNanos) {
    }
}
