package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackExpectationTest {

    @Test
    void expectedPlaybackBecomesStaleOnlyAfterMonotonicGrace() {
        AtomicLong now = new AtomicLong(Duration.ofSeconds(1).toNanos());
        PlaybackExpectation expectation = new PlaybackExpectation(now::get);

        assertFalse(expectation.isExpected());
        assertFalse(expectation.isSourceStale(Duration.ofSeconds(2), Long.MIN_VALUE));

        expectation.playing();
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertFalse(expectation.isSourceStale(Duration.ofSeconds(2), Long.MIN_VALUE));

        now.incrementAndGet();
        assertTrue(expectation.isSourceStale(Duration.ofSeconds(2), Long.MIN_VALUE));

        expectation.notPlaying();
        assertFalse(expectation.isSourceStale(Duration.ZERO, Long.MIN_VALUE));
    }

    @Test
    void sourceArrivalAfterPlaybackStartedRefreshesSilenceGrace() {
        AtomicLong now = new AtomicLong();
        PlaybackExpectation expectation = new PlaybackExpectation(now::get);
        expectation.playing();
        now.set(Duration.ofSeconds(3).toNanos());

        long sourceArrival = now.get();
        now.addAndGet(Duration.ofSeconds(1).toNanos());

        assertFalse(expectation.isSourceStale(Duration.ofSeconds(2), sourceArrival));
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertTrue(expectation.isSourceStale(Duration.ofSeconds(2), sourceArrival));
    }
}
