package de.elite12.musikbot.clientv2.events;

import org.springframework.context.ApplicationEvent;

public class StopSong extends ApplicationEvent {
    public StopSong(Object source) {
        super(source);
    }
}
