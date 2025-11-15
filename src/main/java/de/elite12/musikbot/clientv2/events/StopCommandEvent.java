package de.elite12.musikbot.clientv2.events;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class StopCommandEvent extends ApplicationEvent {

    public StopCommandEvent(Object source) {
        super(source);
    }
}
