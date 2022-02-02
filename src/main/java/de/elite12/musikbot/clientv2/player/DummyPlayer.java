package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.clientDTO.Song;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DummyPlayer implements Player {
    @Override
    public Set<String> getSupportedTypes() {
        return Set.of();
    }

    @Override
    public void play(Song song) {

    }

    @Override
    public void stop() {

    }

    @Override
    public void pause() {

    }
}
