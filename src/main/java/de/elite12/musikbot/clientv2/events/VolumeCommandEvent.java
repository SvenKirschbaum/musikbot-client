package de.elite12.musikbot.clientv2.events;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
@ToString
public class VolumeCommandEvent extends ApplicationEvent {

    private short volume;

    public VolumeCommandEvent(Object source, short volume) {
        super(source);
        this.volume = volume;
    }
}
