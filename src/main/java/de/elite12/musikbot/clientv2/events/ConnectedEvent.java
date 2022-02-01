package de.elite12.musikbot.clientv2.events;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class ConnectedEvent extends ApplicationEvent {
    public ConnectedEvent(Object source) {
        super(source);
    }
}
