package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.SongTypes;
import de.elite12.musikbot.shared.dtos.SongDTO;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DummyPlayer implements Player {
    @Override
    public Set<SongTypes> getSupportedTypes() {
        return Set.of();
    }

    @Override
    public void play(SongDTO song) {

    }

    @Override
    public void stop() {

    }

    @Override
    public void pause() {

    }
}
