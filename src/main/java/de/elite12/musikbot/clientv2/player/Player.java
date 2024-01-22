package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.SongTypes;
import de.elite12.musikbot.shared.dtos.SongDTO;

import java.util.Set;

public interface Player {
    Set<SongTypes> getSupportedTypes();

    void play(SongDTO song);

    void stop();

    void pause();
}
