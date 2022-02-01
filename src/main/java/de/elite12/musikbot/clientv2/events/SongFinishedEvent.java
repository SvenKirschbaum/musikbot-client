package de.elite12.musikbot.clientv2.events;

import org.springframework.context.ApplicationEvent;

public class SongFinishedEvent extends ApplicationEvent {
    public SongFinishedEvent(Object source) {
        super(source);
    }
}
