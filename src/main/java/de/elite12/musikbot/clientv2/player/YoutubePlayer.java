package de.elite12.musikbot.clientv2.player;

import com.github.kiulian.downloader.YoutubeDownloader;
import com.github.kiulian.downloader.downloader.request.RequestVideoInfo;
import com.github.kiulian.downloader.downloader.response.Response;
import com.github.kiulian.downloader.model.videos.VideoInfo;
import de.elite12.musikbot.clientv2.events.SongFinishedEvent;
import de.elite12.musikbot.shared.clientDTO.Song;
import de.elite12.musikbot.shared.util.SongIDParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(
        value = "player.youtube.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class YoutubePlayer extends MediaPlayerEventAdapter implements Player, MediaListPlayerEventListener {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private static final String[] vlcj_options = {
            "--no-video",
            "--network-caching=300",
            "--no-xlib",
            "--no-metadata-network-access"
    };

    private final Logger logger = LoggerFactory.getLogger(YoutubePlayer.class);

    private final MediaPlayerFactory factory;
    private final MediaPlayer player;
    private final YoutubeDownloader downloader;

    public YoutubePlayer() {
        this.factory = new MediaPlayerFactory(vlcj_options);
        this.player = this.factory.mediaPlayers().newEmbeddedMediaPlayer();
        this.player.events().addMediaPlayerEventListener(this);
        this.player.subitems().events().addMediaListPlayerEventListener(this);

        this.downloader = new YoutubeDownloader();
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
        logger.info(String.format("Play: %s", song.toString()));

        try {
            Response<VideoInfo> videoInfo = downloader.getVideoInfo(new RequestVideoInfo(SongIDParser.getVID(song.getSonglink())));

            if (!videoInfo.ok()) throw new Exception("Unable to load videoInfo", videoInfo.error());

            logger.debug("Available Formats: %s".formatted(videoInfo.data().formats().toString()));

            String audioURL = videoInfo.data().audioFormats().get(0).url();
            this.player.media().play(audioURL);
        } catch (Exception e) {
            logger.warn("Youtube Parsing failed, falling back to VLC-Lua", e);
            this.player.media().play(song.getSonglink());
        }
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
        this.applicationEventPublisher.publishEvent(new SongFinishedEvent(this));
    }

    @Override
    public void mediaListPlayerFinished(MediaListPlayer mediaListPlayer) {
        logger.info("Playback finished");
        this.applicationEventPublisher.publishEvent(new SongFinishedEvent(this));
    }

    @Override
    public void nextItem(MediaListPlayer mediaListPlayer, MediaRef mediaRef) {}

    @Override
    public void stopped(MediaListPlayer mediaListPlayer) {}
}
