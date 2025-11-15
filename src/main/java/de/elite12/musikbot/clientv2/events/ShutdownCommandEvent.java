package de.elite12.musikbot.clientv2.events;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class ShutdownCommandEvent extends ApplicationEvent {

    public ShutdownCommandEvent(Object source) {
        super(source);
    }
}
