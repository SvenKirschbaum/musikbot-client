package de.elite12.musikbot.clientv2.player;

import com.google.gson.JsonArray;
import com.neovisionaries.i18n.CountryCode;
import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinishedEvent;
import de.elite12.musikbot.shared.SongTypes;
import de.elite12.musikbot.shared.dtos.SongDTO;
import jakarta.annotation.PostConstruct;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.credentials.AuthorizationCodeCredentials;
import se.michaelthelin.spotify.model_objects.miscellaneous.CurrentlyPlayingContext;
import se.michaelthelin.spotify.model_objects.miscellaneous.Device;
import se.michaelthelin.spotify.model_objects.specification.Track;
import se.michaelthelin.spotify.requests.authorization.authorization_code.AuthorizationCodeRefreshRequest;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

@Component
@ConditionalOnProperty(
        value = "player.spotify.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SpotifyPlayer implements Player {

    private static final Logger logger = LoggerFactory.getLogger(SpotifyPlayer.class);

    private SpotifyApi spotifyApi;

    @Autowired
    private Clientv2ServiceProperties properties;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private TaskScheduler taskScheduler;

    private String deviceId = "";

    private boolean paused = false;

    private ScheduledFuture<?> timer = null;
    private Instant endtime = null;
    private Duration remaining = null;

    @PostConstruct
    private void postConstruct() {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(properties.getSpotifyapiid())
                .setClientSecret(properties.getSpotifyapisecret())
                .setRefreshToken(properties.getSpotifyrefreshtoken())
                .build();

        try {
            this.refreshToken();

            CurrentlyPlayingContext currentlyPlayingContext = this.spotifyApi.getInformationAboutUsersCurrentPlayback().build().execute();

            if(currentlyPlayingContext != null) {
                if(currentlyPlayingContext.getIs_playing()) this.stop();
            }

            //Transfer
            Device[] devices = this.spotifyApi.getUsersAvailableDevices().build().execute();
            boolean found = false;
            for (Device d : devices) {
                if (d.getName().equalsIgnoreCase(properties.getSpotifyDeviceName())) {
                    found = true;
                    this.deviceId = d.getId();
                }
            }

            if (!found) {
                throw new SpotifyWebApiException("Musikbot spotifyd has not been found");
            }

            if(currentlyPlayingContext != null) {
                if(!currentlyPlayingContext.getDevice().getId().equals(this.deviceId)) {
                    JsonArray jsonArray = new JsonArray(1);
                    jsonArray.add(this.deviceId);
                    this.spotifyApi.transferUsersPlayback(jsonArray).build().execute();
                }
            }

        } catch(Exception e) {
            logger.error("Error initializing Spotifyplayer", e);
            System.exit(1);
        }
    }

    private void refreshToken() throws IOException, SpotifyWebApiException, ParseException {
        AuthorizationCodeRefreshRequest request = this.spotifyApi.authorizationCodeRefresh().build();
        AuthorizationCodeCredentials credentials = request.execute();

        this.spotifyApi.setAccessToken(credentials.getAccessToken());

        this.taskScheduler.schedule(() -> {
            try {
                refreshToken();
            } catch (IOException | SpotifyWebApiException | ParseException e) {
                logger.error("Error refreshing Token", e);
                System.exit(1);
            }
        }, Instant.now().plusSeconds(credentials.getExpiresIn() - 60));
    }

    @Override
    public Set<SongTypes> getSupportedTypes() {
        return Set.of(SongTypes.SPOTIFY_TRACK);
    }

    @Override
    public void play(SongDTO song) {
        logger.info(String.format("Play: %s", song.toString()));
        try {
            Track track = this.spotifyApi.getTrack(song.getId()).market(CountryCode.DE).build().execute();

            if (!track.getIsPlayable()) {
                throw new SpotifyWebApiException("Provided Track is not playable");
            }

            JsonArray jsonArray = new JsonArray(1);
            jsonArray.add("spotify:track:" + song.getId());
            this.spotifyApi.startResumeUsersPlayback().device_id(this.deviceId).uris(jsonArray).build().execute();

            this.cancelTimer();
            logger.debug(String.format("Song Duration: %dms", track.getDurationMs()));
            this.endtime = Instant.now().plusMillis(track.getDurationMs());
            logger.debug(String.format("End Time: %s", this.endtime.toString()));
            this.startTimer();
            this.paused = false;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            logger.error("Error starting spotify playback", e);
            this.cancelTimer();
            this.applicationEventPublisher.publishEvent(new SongFinishedEvent(this));
        }
    }

    @Override
    public void stop() {
        logger.info("Stop");
        this.paused = false;
        try {
            if(this.timer != null) {
                this.cancelTimer();
                this.spotifyApi.pauseUsersPlayback().build().execute();
            }
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            logger.error("Error stopping spotify playback" ,e);
        }
    }

    @Override
    public void pause() {
        logger.info("Pause");
        try {
            if(!this.paused) {
                this.spotifyApi.pauseUsersPlayback().build().execute();
                this.cancelTimer();
                this.remaining = Duration.between(Instant.now(),this.endtime);
            }
            else {
                this.spotifyApi.startResumeUsersPlayback().device_id(this.deviceId).build().execute();
                this.endtime=Instant.now().plus(this.remaining);
                this.startTimer();
            }
            this.paused=!this.paused;
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            logger.error("Error stopping spotify playback" ,e);
        }
    }

    private void cancelTimer() {
        if(this.timer != null) {
            this.timer.cancel(false);
            this.timer = null;
        }
    }

    private void startTimer() {
        this.timer = this.taskScheduler.schedule(this::finished, this.endtime);
    }

    private void finished() {
        logger.info("Playback finished");
        this.applicationEventPublisher.publishEvent(new SongFinishedEvent(this));
    }
}
