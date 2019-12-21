package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.clientDTO.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class YoutubePlayer extends AbstractPlayer {

    private Logger logger = LoggerFactory.getLogger(YoutubePlayer.class);

    @Override
    public Set<String> getSupportedTypes() {
        return Set.of("youtube");
    }

    @Override
    public void play(Song song) {
        logger.info(String.format("Play: %s",song.toString()));
    }

    @Override
    public void stop() {
        logger.info("Stop");
    }

    @Override
    public void pause() {
        logger.info("Pause");
    }
}
