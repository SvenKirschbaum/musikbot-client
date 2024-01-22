package de.elite12.musikbot.clientv2.events;

import de.elite12.musikbot.shared.ClientDTO;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class CommandEvent extends ApplicationEvent {
    private ClientDTO command;

    public CommandEvent(Object source, ClientDTO command) {
        super(source);
        this.command = command;
    }
}
