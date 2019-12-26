package de.elite12.musikbot.clientv2.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Service
public class VolumeService {

    private static Logger logger = LoggerFactory.getLogger(VolumeService.class);

    private Runtime runtime = Runtime.getRuntime();

    public void setVolume(short volume) {
        try {
            Process exec = runtime.exec(new String[]{"which", "pactl"});

            if(!exec.waitFor(1, TimeUnit.SECONDS)) throw new IllegalThreadStateException("Check didn´t terminate within one Second");

            if(exec.exitValue() != 0) throw new IllegalStateException("The required tool pactl could not be found");

            exec = runtime.exec(new String[]{"pactl", "set-sink-volume", "0", String.format("%d%%", volume)});

            if(!exec.waitFor(1, TimeUnit.SECONDS)) throw new IllegalThreadStateException("Volume Set Command didn´t terminate within one Second");
        } catch (IOException | InterruptedException | IllegalThreadStateException e) {
            logger.error("Exception setting volume", e);
        }
    }
}
