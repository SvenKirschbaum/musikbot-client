package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SoftwareVolumeTest {

    @Test
    void defaultsToOneHundredPercent() {
        SoftwareVolume volume = new SoftwareVolume();
        byte[] frame = samples((short) 20_000, (short) -20_000);

        volume.apply(frame);

        assertEquals(100, volume.percent());
        assertArrayEquals(samples((short) 20_000, (short) -20_000), frame);
    }

    @Test
    void zeroPercentMutesSamples() {
        SoftwareVolume volume = new SoftwareVolume();
        volume.setPercent(0);
        byte[] frame = samples((short) 20_000, (short) -20_000);

        volume.apply(frame);

        assertArrayEquals(samples((short) 0, (short) 0), frame);
    }

    @Test
    void scalesSignedBigEndianSamplesInPlace() {
        SoftwareVolume volume = new SoftwareVolume();
        volume.setPercent(50);
        byte[] frame = samples((short) 20_000, (short) -20_000);

        volume.apply(frame);

        ByteBuffer result = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        assertEquals(10_000, result.getShort());
        assertEquals(-10_000, result.getShort());
    }

    @Test
    void roundsScaledSamplesToIntegers() {
        SoftwareVolume volume = new SoftwareVolume();
        volume.setPercent(50);
        byte[] frame = samples((short) 3, (short) -3);

        volume.apply(frame);

        assertArrayEquals(samples((short) 2, (short) -1), frame);
    }

    @Test
    void clampsPercentBelowZero() {
        SoftwareVolume volume = new SoftwareVolume();

        volume.setPercent(-1);

        assertEquals(0, volume.percent());
    }

    @Test
    void clampsPercentAboveOneHundred() {
        SoftwareVolume volume = new SoftwareVolume();

        volume.setPercent(101);

        assertEquals(100, volume.percent());
    }

    @Test
    void fullVolumeKeepsSamplesAtSaturationLimits() {
        SoftwareVolume volume = new SoftwareVolume();
        volume.setPercent(100);
        byte[] frame = samples(Short.MAX_VALUE, Short.MIN_VALUE);

        volume.apply(frame);

        assertArrayEquals(samples(Short.MAX_VALUE, Short.MIN_VALUE), frame);
    }

    private static byte[] samples(short... samples) {
        ByteBuffer frame = ByteBuffer.allocate(samples.length * Short.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (short sample : samples) {
            frame.putShort(sample);
        }
        return frame.array();
    }
}
