package de.elite12.musikbot.clientv2.player;

import com.google.gson.JsonArray;
import com.neovisionaries.i18n.CountryCode;
import com.wrapper.spotify.SpotifyApi;
import com.wrapper.spotify.exceptions.SpotifyWebApiException;
import com.wrapper.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import com.wrapper.spotify.model_objects.miscellaneous.Device;
import com.wrapper.spotify.model_objects.specification.Track;
import com.wrapper.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;
import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinished;
import de.elite12.musikbot.shared.clientDTO.Song;
import de.elite12.musikbot.shared.util.SongIDParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

@Component
public class SpotifyPlayer implements Player {

    private Logger logger = LoggerFactory.getLogger(SpotifyPlayer.class);

    private SpotifyApi spotifyApi;

    @Autowired
    private Clientv2ServiceProperties properties;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private boolean paused = false;

    private Timer timer = null;
    private Instant endtime = null;
    private Duration remaining = null;

    @PostConstruct
    private void postConstruct() throws IOException, SpotifyWebApiException {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(properties.getSpotifyapiid())
                .setClientSecret(properties.getSpotifyapisecret())
                .setRefreshToken(properties.getSpotifyrefreshtoken())
                .build();

        this.refreshToken();

        //Transfer
        Device[] devices = this.spotifyApi.getUsersAvailableDevices().build().execute();
        Device device = null;
        boolean found = false;
        for(Device d:devices) {
            if(d.getName().equalsIgnoreCase("Musikbot")) {
                found = true;
                device = d;
            }
        }

        if(!found) {
            throw new SpotifyWebApiException("Musikbot spotifyd has not been found");
        }

        JsonArray jsonArray = new JsonArray(1);
        jsonArray.add(device.getId());

        this.spotifyApi.transferUsersPlayback(jsonArray).build().execute();

        this.stop();
    }

    private void refreshToken() throws IOException, SpotifyWebApiException {
        AuthorizationCodeRefreshRequest request = this.spotifyApi.authorizationCodeRefresh().build();
        AuthorizationCodeCredentials credentials = request.execute();

        this.spotifyApi.setAccessToken(credentials.getAccessToken());

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    refreshToken();
                } catch (IOException | SpotifyWebApiException e) {
                    logger.error("Error refreshing Token", e);
                    System.exit(1);
                }
            }
        }, (credentials.getExpiresIn() - 60) * 1000);
    }

    @Override
    public Set<String> getSupportedTypes() {
        return Set.of("spotify");
    }

    @Override
    public void play(Song song) {
        this.logger.info(String.format("Play: %s",song.toString()));
        try {
            String sid = SongIDParser.getSID(song.getSonglink());
            Track track = this.spotifyApi.getTrack(sid).market(CountryCode.DE).build().execute();
            JsonArray jsonArray = new JsonArray(1);
            jsonArray.add("spotify:track:"+sid);
            this.spotifyApi.startResumeUsersPlayback().uris(jsonArray).build().execute();

            this.cancelTimer();
            this.endtime = Instant.now().plusMillis(track.getDurationMs());
            this.startTimer();
            this.paused = false;
        } catch (IOException | SpotifyWebApiException e) {
            logger.error("Error stopping spotify playback" ,e);
        }
    }

    @Override
    public void stop() {
        this.logger.info("Stop");
        this.paused = false;
        try {
            this.cancelTimer();
            this.spotifyApi.pauseUsersPlayback().build().execute();
        } catch (IOException | SpotifyWebApiException e) {
            logger.error("Error stopping spotify playback" ,e);
        }
    }

    @Override
    public void pause() {
        this.logger.info("Pause");
        try {
            if(!this.paused) {
                this.spotifyApi.pauseUsersPlayback().build().execute();
                this.cancelTimer();
                this.remaining = Duration.between(Instant.now(),this.endtime);
            }
            else {
                this.spotifyApi.startResumeUsersPlayback().build().execute();
                this.endtime=Instant.now().plus(this.remaining);
                this.startTimer();
            }
            this.paused=!this.paused;
        } catch (IOException | SpotifyWebApiException e) {
            logger.error("Error stopping spotify playback" ,e);
        }
    }

    private void cancelTimer() {
        if(this.timer != null) {
            this.timer.cancel();
            this.timer = null;
        }
    }

    private void startTimer() {
        this.timer = new Timer();
        this.timer.schedule(new TimerTask() {
            @Override
            public void run() {
                finished();
            }
        }, Date.from(this.endtime));
    }

    private void finished() {
        this.applicationEventPublisher.publishEvent(new SongFinished(this));
    }
}
