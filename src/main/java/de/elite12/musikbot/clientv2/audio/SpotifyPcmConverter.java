package de.elite12.musikbot.clientv2.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

public final class SpotifyPcmConverter implements Closeable {

    public static final int OUTPUT_FRAME_BYTES = 3_840;

    static final AudioFormat INPUT = new AudioFormat(44_100, 16, 2, true, false);
    static final AudioFormat OUTPUT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            48_000,
            16,
            2,
            2 * Short.BYTES,
            48_000,
            true,
            Map.of("interpolation", "sinc")
    );

    private final AudioInputStream converted;
    private boolean endOfStream;

    public SpotifyPcmConverter(InputStream source) {
        Objects.requireNonNull(source, "source");
        AudioInputStream pcm = new AudioInputStream(source, INPUT, AudioSystem.NOT_SPECIFIED);
        converted = AudioSystem.getAudioInputStream(OUTPUT, pcm);
    }

    public boolean readFrame(byte[] target) throws IOException {
        requireFrameBuffer(target);
        if (endOfStream) {
            return false;
        }

        int offset = 0;
        while (offset < OUTPUT_FRAME_BYTES) {
            int read = converted.read(target, offset, OUTPUT_FRAME_BYTES - offset);
            if (read < 0) {
                endOfStream = true;
                return false;
            }
            offset += read;
        }
        return true;
    }

    public long discardFrames(long count, byte[] scratch) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        requireFrameBuffer(scratch);

        long discarded = 0;
        while (discarded < count && readFrame(scratch)) {
            discarded++;
        }
        return discarded;
    }

    @Override
    public void close() throws IOException {
        converted.close();
    }

    private static void requireFrameBuffer(byte[] buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.length != OUTPUT_FRAME_BYTES) {
            throw new IllegalArgumentException("buffer must contain exactly " + OUTPUT_FRAME_BYTES + " bytes");
        }
    }
}
