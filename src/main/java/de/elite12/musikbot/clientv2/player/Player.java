package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.clientv2.data.SongTypes;
import de.elite12.musikbot.clientv2.events.PlayCommandEvent;

import java.util.Set;

public interface Player {
    Set<SongTypes> getSupportedTypes();

    void play(PlayCommandEvent song);

    void stop();

    void pause();
}
