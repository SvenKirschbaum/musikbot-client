package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.events.VolumeCommandEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class VolumeService implements ApplicationListener<VolumeCommandEvent> {

    private static final int FADE_STEPS = 10;
    private static final Logger logger = LoggerFactory.getLogger(VolumeService.class);

    @Override
    public void onApplicationEvent(VolumeCommandEvent event) {
        try {
            Process exec = Runtime.getRuntime().exec(new String[]{"pactl", "set-sink-volume", "0", String.format("%d%%", event.getVolume())});

            if (!exec.waitFor(1, TimeUnit.SECONDS))
                throw new IllegalThreadStateException("Volume Set Command didn´t terminate within one Second");
        } catch (IOException | InterruptedException | IllegalThreadStateException e) {
            logger.error("Exception setting volume", e);
        }
    }
}
