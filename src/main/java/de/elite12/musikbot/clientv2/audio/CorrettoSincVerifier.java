package de.elite12.musikbot.clientv2.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class CorrettoSincVerifier {

    private static final int SAMPLE_RATE = 44_100;
    private static final int SAMPLE_COUNT = SAMPLE_RATE / 10;
    private static final int FREQUENCY = 15_000;
    private static final int AMPLITUDE = 12_000;
    private static final double MINIMUM_RMS_RATIO = 0.89;

    private CorrettoSincVerifier() {
    }

    public static void verify() {
        byte[] input = sineInput();
        double inputRms = inputRms(input);
        double sumOfSquares = 0.0;
        long outputSamples = 0;

        try (SpotifyPcmConverter converter = new SpotifyPcmConverter(new ByteArrayInputStream(input))) {
            byte[] frame = new byte[SpotifyPcmConverter.OUTPUT_FRAME_BYTES];
            if (!converter.readFrame(frame)) {
                throw new IllegalStateException("Sinc verification produced no complete startup frame");
            }
            while (converter.readFrame(frame)) {
                for (int index = 0; index < frame.length; index += Short.BYTES) {
                    int sample = (short) (((frame[index] & 0xff) << 8) | (frame[index + 1] & 0xff));
                    sumOfSquares += (double) sample * sample;
                    outputSamples++;
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Corretto sinc conversion is unavailable", exception);
        }

        double outputRms = Math.sqrt(sumOfSquares / outputSamples);
        double ratio = outputRms / inputRms;
        if (!(ratio > MINIMUM_RMS_RATIO)) {
            throw new IllegalStateException("Corretto sinc conversion RMS ratio too low: " + ratio);
        }
    }

    private static byte[] sineInput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(SAMPLE_COUNT * 2 * Short.BYTES);
        for (int frame = 0; frame < SAMPLE_COUNT; frame++) {
            short sample = (short) Math.round(
                    AMPLITUDE * Math.sin(2.0 * Math.PI * FREQUENCY * frame / SAMPLE_RATE)
            );
            writeS16Le(output, sample);
            writeS16Le(output, sample);
        }
        return output.toByteArray();
    }

    private static double inputRms(byte[] input) {
        double sumOfSquares = 0.0;
        for (int index = 0; index < input.length; index += Short.BYTES) {
            int sample = (short) ((input[index] & 0xff) | (input[index + 1] << 8));
            sumOfSquares += (double) sample * sample;
        }
        return Math.sqrt(sumOfSquares / (input.length / Short.BYTES));
    }

    private static void writeS16Le(ByteArrayOutputStream output, short sample) {
        output.write(sample);
        output.write(sample >> 8);
    }
}
