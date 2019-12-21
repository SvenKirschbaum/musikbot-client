package de.elite12.musikbot.clientv2.events;

import org.springframework.context.ApplicationEvent;

public class SongFinished extends ApplicationEvent {
    public SongFinished(Object source) {
        super(source);
    }
}
