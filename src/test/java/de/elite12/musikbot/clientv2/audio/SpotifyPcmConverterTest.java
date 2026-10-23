package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyPcmConverterTest {

    @Test
    void convertsOneSecondIntoExactlyFiftyJdaFrames() throws Exception {
        byte[] input = stereoSineS16Le(44_100, 1_000, 1.0, 12_000, 12_000);
        int frames = 0;
        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(partialInput(input))) {
            byte[] output = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            while (converter.readFrame(output)) {
                frames++;
            }
            assertFalse(converter.readFrame(output));
        }

        assertEquals(50, frames);
    }

    @Test
    void producesBigEndianStereoWithSeparatedChannels() throws Exception {
        byte[] input = stereoSineS16Le(44_100, 250, 0.1, 12_000, -8_000);

        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] output = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            assertTrue(converter.readFrame(output));

            ByteBuffer samples = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN);
            int leftPeak = 0;
            int rightTrough = 0;
            while (samples.remaining() >= 2 * Short.BYTES) {
                leftPeak = Math.max(leftPeak, samples.getShort());
                rightTrough = Math.min(rightTrough, samples.getShort());
            }
            assertTrue(leftPeak > 10_000);
            assertTrue(rightTrough < -6_000);
        }
    }

    @Test
    void discardsPartialFinalFrameAtCleanEof() throws Exception {
        byte[] input = stereoSineS16Le(44_100, 1_000, 0.01, 12_000, 12_000);

        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] output = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            assertFalse(converter.readFrame(output));
            assertFalse(converter.readFrame(output));
        }
    }

    @Test
    void aNewConverterRestartsTheStream() throws Exception {
        byte[] input = stereoSineS16Le(44_100, 1_000, 0.04, 12_000, 12_000);
        byte[] first = firstFrame(input);
        byte[] restarted = firstFrame(input);

        assertArrayEquals(first, restarted);
    }

    @Test
    void discardsWholeFramesWithoutAllocatingTheirStorage() throws Exception {
        byte[] input = stereoSineS16Le(44_100, 1_000, 1.0, 12_000, 12_000);

        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] scratch = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            assertEquals(49, converter.discardFrames(49, scratch));
            assertTrue(converter.readFrame(scratch));
            assertFalse(converter.readFrame(scratch));
            assertEquals(0, converter.discardFrames(1, scratch));
        }
    }

    @Test
    void configuredRuntimeProvidesSincInterpolation() {
        assertDoesNotThrow(CorrettoSincVerifier::verify);
    }

    private static byte[] firstFrame(byte[] input) throws Exception {
        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] output = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            assertTrue(converter.readFrame(output));
            return output;
        }
    }

    private static InputStream partialInput(byte[] input) {
        return new FilterInputStream(new ByteArrayInputStream(input)) {
            @Override
            public int read(byte[] target, int offset, int length) throws IOException {
                return super.read(target, offset, Math.min(length, 28));
            }
        };
    }

    private static byte[] stereoSineS16Le(
            int sampleRate,
            double frequency,
            double seconds,
            int leftAmplitude,
            int rightAmplitude
    ) {
        int sampleFrames = (int) Math.round(sampleRate * seconds);
        ByteArrayOutputStream output = new ByteArrayOutputStream(sampleFrames * 2 * Short.BYTES);
        for (int frame = 0; frame < sampleFrames; frame++) {
            double phase = 2.0 * Math.PI * frequency * frame / sampleRate;
            writeS16Le(output, (short) Math.round(leftAmplitude * Math.sin(phase)));
            writeS16Le(output, (short) Math.round(rightAmplitude * Math.sin(phase)));
        }
        return output.toByteArray();
    }

    private static void writeS16Le(ByteArrayOutputStream output, short sample) {
        output.write(sample);
        output.write(sample >> 8);
    }
}
