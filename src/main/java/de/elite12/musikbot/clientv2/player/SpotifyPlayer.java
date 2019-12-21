package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.clientDTO.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SpotifyPlayer extends AbstractPlayer {

    private Logger logger = LoggerFactory.getLogger(SpotifyPlayer.class);

    @Override
    public Set<String> getSupportedTypes() {
        return Set.of("spotify");
    }

    @Override
    public void play(Song song) {
        this.logger.info(String.format("Play: %s",song.toString()));
    }

    @Override
    public void stop() {
        this.logger.info("Stop");
    }

    @Override
    public void pause() {
        this.logger.info("Pause");
    }
}
