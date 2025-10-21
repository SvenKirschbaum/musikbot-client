package de.elite12.musikbot.clientv2.events;

import org.springframework.context.ApplicationEvent;

public class NoListenerEvent extends ApplicationEvent {
    public NoListenerEvent(Object source) {
        super(source);
    }
}
