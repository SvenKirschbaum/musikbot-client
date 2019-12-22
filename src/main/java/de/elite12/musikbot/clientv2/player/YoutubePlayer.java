package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.clientv2.events.SongFinished;
import de.elite12.musikbot.shared.clientDTO.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.media.MediaRef;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.list.MediaListPlayer;
import uk.co.caprica.vlcj.player.list.MediaListPlayerEventListener;

import javax.annotation.PreDestroy;
import java.util.Set;

@Component
public class YoutubePlayer extends MediaPlayerEventAdapter implements Player, MediaListPlayerEventListener {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private static final String[] vlcj_options = {
            "--no-video",
            "--network-caching=60000",
            "--file-caching=500",
            "--no-xlib",
            "--no-metadata-network-access"
    };

    private Logger logger = LoggerFactory.getLogger(YoutubePlayer.class);

    private MediaPlayerFactory factory;
    private MediaPlayer player;

    public YoutubePlayer() {
        this.factory = new MediaPlayerFactory(vlcj_options);
        this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
        this.player.events().addMediaPlayerEventListener(this);
        this.player.subitems().events().addMediaListPlayerEventListener(this);
    }

    @PreDestroy
    private void preDestroy() {
        this.player.release();
        this.factory.release();
    }

    @Override
    public Set<String> getSupportedTypes() {
        return Set.of("youtube");
    }

    @Override
    public void play(Song song) {
        logger.info(String.format("Play: %s",song.toString()));
        this.player.media().play(song.getSonglink());
    }

    @Override
    public void stop() {
        logger.info("Stop");
        this.player.controls().stop();
    }

    @Override
    public void pause() {
        logger.info("Pause");
        this.player.controls().pause();
    }


    @Override
    public void error(MediaPlayer mediaPlayer) {
        logger.error("MediaPlayer reported Error");
        this.applicationEventPublisher.publishEvent(new SongFinished(this));
    }

    @Override
    public void mediaListPlayerFinished(MediaListPlayer mediaListPlayer) {
        logger.info("Playback finished");
        this.applicationEventPublisher.publishEvent(new SongFinished(this));
    }

    @Override
    public void nextItem(MediaListPlayer mediaListPlayer, MediaRef mediaRef) {}

    @Override
    public void stopped(MediaListPlayer mediaListPlayer) {}
}
