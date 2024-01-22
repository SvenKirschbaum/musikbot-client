package de.elite12.musikbot.clientv2.events;

import de.elite12.musikbot.shared.dtos.SongDTO;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class StartSongEvent extends ApplicationEvent {
    private SongDTO song;

    public StartSongEvent(Object source, SongDTO song) {
        super(source);
        this.song = song;
    }
}
