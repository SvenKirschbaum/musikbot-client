package de.elite12.musikbot.clientv2.events;

import de.elite12.musikbot.clientv2.data.SongTypes;
import de.elite12.musikbot.proto.Song;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

@Getter
@Setter
@ToString
public class PlayCommandEvent extends ApplicationEvent {

    private String id;
    private String title;
    private SongTypes songType;


    public PlayCommandEvent(Object source) {
        super(source);
    }

    public static PlayCommandEvent fromProto(Object source, Song song) {
        PlayCommandEvent playCommandEvent = new PlayCommandEvent(source);

        playCommandEvent.setId(song.getId());
        playCommandEvent.setTitle(song.getTitle());
        playCommandEvent.setSongType(SongTypes.fromProto(song.getType()));

        return playCommandEvent;
    }
}
