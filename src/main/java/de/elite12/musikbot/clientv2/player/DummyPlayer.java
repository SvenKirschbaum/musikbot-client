package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.clientv2.data.SongTypes;
import de.elite12.musikbot.clientv2.events.PlayCommandEvent;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DummyPlayer implements Player {
    @Override
    public Set<SongTypes> getSupportedTypes() {
        return Set.of();
    }

    @Override
    public void play(PlayCommandEvent song) {

    }

    @Override
    public void stop() {

    }

    @Override
    public void pause() {

    }
}
