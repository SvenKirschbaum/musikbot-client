package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.events.ShutdownCommandEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class ShutdownService implements ApplicationListener<ShutdownCommandEvent> {

    private final Logger logger = LoggerFactory.getLogger(ShutdownService.class);

    @Autowired
    private ApplicationContext appContext;

    @Override
    public void onApplicationEvent(ShutdownCommandEvent event) {
        logger.warn("Received Shutdown Signal");
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                SpringApplication.exit(appContext, () -> 0);
            }
        }, 2500);
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec("kill 1");
                } catch (IOException e) {
                    System.exit(-1);
                }
            }
        }, 10000);
    }
}
