package de.elite12.musikbot.clientv2.events;

import org.springframework.context.ApplicationEvent;

public class StopSongEvent extends ApplicationEvent {
    public StopSongEvent(Object source) {
        super(source);
    }
}
