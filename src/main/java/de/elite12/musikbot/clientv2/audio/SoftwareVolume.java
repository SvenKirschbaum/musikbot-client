package de.elite12.musikbot.clientv2.audio;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class SoftwareVolume {

    private final AtomicInteger percent = new AtomicInteger(100);

    public void setPercent(int percent) {
        this.percent.set(Math.clamp(percent, 0, 100));
    }

    public int percent() {
        return percent.get();
    }

    public void apply(byte[] s16beFrame) {
        int currentPercent = percent.get();
        for (int index = 0; index + 1 < s16beFrame.length; index += Short.BYTES) {
            int sample = (short) (((s16beFrame[index] & 0xff) << 8) | (s16beFrame[index + 1] & 0xff));
            int scaled = (int) Math.round(sample * currentPercent / 100.0);
            short saturated = (short) Math.clamp(scaled, Short.MIN_VALUE, Short.MAX_VALUE);
            s16beFrame[index] = (byte) (saturated >> 8);
            s16beFrame[index + 1] = (byte) saturated;
        }
    }
}
