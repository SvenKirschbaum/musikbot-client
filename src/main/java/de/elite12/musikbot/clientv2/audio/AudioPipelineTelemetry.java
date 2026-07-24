package de.elite12.musikbot.clientv2.audio;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongGauge;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public final class AudioPipelineTelemetry implements AutoCloseable {

    private static final String SCOPE = "de.elite12.musikbot.audio";
    private static final AttributeKey<String> REASON = AttributeKey.stringKey("reason");
    private static final AttributeKey<Long> DROPPED_FRAMES = AttributeKey.longKey("dropped.frames");

    private final Tracer tracer;
    private final LongCounter sourceFrames;
    private final LongCounter outputFrames;
    private final LongCounter droppedFrames;
    private final LongCounter underruns;
    private final LongCounter fifoReopens;
    private final LongCounter conversionFailures;
    private final DoubleHistogram conversionDuration;
    private final DoubleHistogram frameAge;
    private final DoubleHistogram schedulerLateness;
    private final DoubleHistogram reopenDelay;
    private final DoubleHistogram subscriberBufferDuration;
    private final ObservableLongGauge bufferedDurationGauge;
    private final ObservableLongGauge latestFrameAgeGauge;
    private final ObservableLongGauge subscribersGauge;
    private final ObservableLongGauge readyGauge;

    public AudioPipelineTelemetry(AudioPipelineState state) {
        Objects.requireNonNull(state, "state");
        Meter meter = GlobalOpenTelemetry.get().getMeter(SCOPE);
        tracer = GlobalOpenTelemetry.get().getTracer(SCOPE);

        sourceFrames = counter(meter, "spotify.audio.source.frames", "Source frames read", "{frame}");
        outputFrames = counter(meter, "spotify.audio.output.frames", "Output frames published", "{frame}");
        droppedFrames = counter(meter, "spotify.audio.dropped.frames", "Source frames dropped", "{frame}");
        underruns = counter(meter, "spotify.audio.underruns", "Subscriber buffer underruns", "{event}");
        fifoReopens = counter(meter, "spotify.audio.fifo.reopens", "FIFO reopen attempts", "{event}");
        conversionFailures = counter(meter, "spotify.audio.conversion.failures", "PCM conversion failures", "{event}");

        conversionDuration = histogram(meter, "spotify.audio.conversion.duration", "PCM conversion duration");
        frameAge = histogram(meter, "spotify.audio.frame.age", "Published frame age");
        schedulerLateness = histogram(meter, "spotify.audio.scheduler.lateness", "Frame scheduler lateness");
        reopenDelay = histogram(meter, "spotify.audio.fifo.reopen.delay", "FIFO reopen delay");
        subscriberBufferDuration = histogram(meter, "spotify.audio.subscriber.buffer.duration",
                "Subscriber buffered duration");

        bufferedDurationGauge = meter.gaugeBuilder("spotify.audio.buffered.duration")
                .ofLongs()
                .setDescription("Current pipeline buffered duration")
                .setUnit("ms")
                .buildWithCallback(measurement -> measurement.record(state.getBufferedMillis()));
        latestFrameAgeGauge = meter.gaugeBuilder("spotify.audio.latest_frame.age")
                .ofLongs()
                .setDescription("Age of the latest output frame")
                .setUnit("ms")
                .buildWithCallback(measurement -> measurement.record(state.getLatestFrameAgeMillis()));
        subscribersGauge = meter.gaugeBuilder("spotify.audio.subscribers")
                .ofLongs()
                .setDescription("Active audio subscribers")
                .setUnit("{subscriber}")
                .buildWithCallback(measurement -> measurement.record(state.getSubscribers()));
        readyGauge = meter.gaugeBuilder("spotify.audio.ready")
                .ofLongs()
                .setDescription("Whether the audio pipeline is ready")
                .setUnit("1")
                .buildWithCallback(measurement -> measurement.record(state.isReady() ? 1 : 0));
    }

    public void sourceFrames(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        sourceFrames.add(count);
    }

    public void outputFrame(Duration age) {
        outputFrames.add(1);
        frameAge.record(milliseconds(age));
    }

    public void dropped(long count, DropReason reason) {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        droppedFrames.add(count, Objects.requireNonNull(reason, "reason").attributes);
    }

    public void underrun() {
        underruns.add(1);
    }

    public void fifoReopened(Duration delay) {
        fifoReopens.add(1);
        reopenDelay.record(milliseconds(delay));
    }

    public void conversionFailure(Throwable error) {
        Objects.requireNonNull(error, "error");
        conversionFailures.add(1);
    }

    public void conversion(Duration duration) {
        conversionDuration.record(milliseconds(duration));
    }

    /** Records how late frame processing ran relative to its monotonic deadline. */
    public void schedulerLateness(Duration lateness) {
        Objects.requireNonNull(lateness, "lateness");
        if (lateness.isNegative()) {
            throw new IllegalArgumentException("lateness must not be negative");
        }
        schedulerLateness.record(milliseconds(lateness));
    }

    public void subscriberBuffer(Duration duration) {
        subscriberBufferDuration.record(milliseconds(duration));
    }

    public Operation startRecovery(RecoveryReason reason) {
        Span span = tracer.spanBuilder("spotify.audio.recovery")
                .setSpanKind(SpanKind.INTERNAL)
                .setAllAttributes(Objects.requireNonNull(reason, "reason").attributes)
                .startSpan();
        return new Operation(span);
    }

    public Operation startCatchUp(long droppedFrames) {
        if (droppedFrames < 0) {
            throw new IllegalArgumentException("droppedFrames must not be negative");
        }
        Span span = tracer.spanBuilder("spotify.audio.catch_up")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(DROPPED_FRAMES, droppedFrames)
                .startSpan();
        return new Operation(span);
    }

    @Override
    public void close() {
        closeAll(
                bufferedDurationGauge::close,
                latestFrameAgeGauge::close,
                subscribersGauge::close,
                readyGauge::close
        );
    }

    static void closeAll(Runnable... cleanups) {
        Throwable failure = null;
        for (Runnable cleanup : cleanups) {
            try {
                cleanup.run();
            } catch (Throwable exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Gauge cleanup failed", failure);
        }
    }

    private static LongCounter counter(Meter meter, String name, String description, String unit) {
        return meter.counterBuilder(name)
                .setDescription(description)
                .setUnit(unit)
                .build();
    }

    private static DoubleHistogram histogram(Meter meter, String name, String description) {
        return meter.histogramBuilder(name)
                .setDescription(description)
                .setUnit("ms")
                .build();
    }

    private static double milliseconds(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        return Math.max(0, duration.toNanos()) / 1_000_000.0;
    }

    public enum DropReason {
        CATCH_UP("catch_up"),
        SUBSCRIBER_OVERFLOW("subscriber_overflow"),
        GENERATION_RESET("generation_reset");

        private final String telemetryValue;
        private final Attributes attributes;

        DropReason(String telemetryValue) {
            this.telemetryValue = telemetryValue;
            attributes = Attributes.of(REASON, telemetryValue);
        }

        public String telemetryValue() {
            return telemetryValue;
        }
    }

    public enum RecoveryReason {
        IO_FAILURE("io_failure"),
        CONVERSION_FAILURE("conversion_failure");

        private final String telemetryValue;
        private final Attributes attributes;

        RecoveryReason(String telemetryValue) {
            this.telemetryValue = telemetryValue;
            attributes = Attributes.of(REASON, telemetryValue);
        }

        public String telemetryValue() {
            return telemetryValue;
        }
    }

    public static final class Operation {

        private final Span span;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Operation(Span span) {
            this.span = span;
        }

        public void closeSuccess() {
            finish(StatusCode.OK);
        }

        public void closeFailure(Throwable error) {
            finish(StatusCode.ERROR);
        }

        private void finish(StatusCode status) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                span.setStatus(status);
            } finally {
                span.end();
            }
        }
    }
}
