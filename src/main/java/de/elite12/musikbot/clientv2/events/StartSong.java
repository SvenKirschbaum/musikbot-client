package de.elite12.musikbot.clientv2.events;

import de.elite12.musikbot.shared.clientDTO.Song;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
public class StartSong extends ApplicationEvent
{
    private Song song;
    public StartSong(Object source, Song song) {
        super(source);
        this.song = song;
    }
}
