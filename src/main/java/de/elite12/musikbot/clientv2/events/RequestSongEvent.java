package de.elite12.musikbot.clientv2.events;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class RequestSongEvent extends ApplicationEvent {
    public RequestSongEvent(Object source) {
        super(source);
    }
}
