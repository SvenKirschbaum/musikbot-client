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
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
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
public class SpotifyPlayer implements Player, HealthIndicator {

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

    private Instant accessTokenExpiration = null;

    @PostConstruct
    private void postConstruct() {
        this.spotifyApi = new SpotifyApi.Builder()
                .setClientId(properties.getSpotifyapiid())
                .setClientSecret(properties.getSpotifyapisecret())
                .setRefreshToken(properties.getSpotifyrefreshtoken())
                .build();

        try {
            this.refreshToken();

            logger.warn("Manually starting spotifyd");
            Runtime rt = Runtime.getRuntime();
            rt.exec(new String[]{"/usr/local/spotifyd/spotifyd", "--no-daemon", "--verbose", "--cache-path", "/spotify-cache", "--username",  "musikbot@elite12.de", "--password", this.spotifyApi.getAccessToken()});

            CurrentlyPlayingContext currentlyPlayingContext = this.spotifyApi.getInformationAboutUsersCurrentPlayback().build().execute();

            if(currentlyPlayingContext != null) {
                if(currentlyPlayingContext.getIs_playing()) this.stop();
            }

            updateSpotifyDevice();
            while (this.deviceId.isEmpty()) {
                logger.warn("Spotifyd not found, retrying in 10 seconds");
                Thread.sleep(10000);
                updateSpotifyDevice();
            }

        } catch(Exception e) {
            logger.error("Error initializing Spotifyplayer", e);
            System.exit(1);
        }
    }

    private void refreshToken() {
        try {
            AuthorizationCodeRefreshRequest request = this.spotifyApi.authorizationCodeRefresh().build();
            AuthorizationCodeCredentials credentials = request.execute();

            this.spotifyApi.setAccessToken(credentials.getAccessToken());
            this.accessTokenExpiration = Instant.now().plusSeconds(credentials.getExpiresIn());

            this.taskScheduler.schedule(this::refreshToken, accessTokenExpiration.minusSeconds(60));
        } catch (RuntimeException | IOException | SpotifyWebApiException | ParseException e) {
            if (this.accessTokenExpiration != null && Instant.now().plusSeconds(5).isBefore(this.accessTokenExpiration)) {
                //We still have some time to refresh the token before it expires
                logger.error("Error refreshing Token, will retry in 5 seconds", e);
                this.taskScheduler.schedule(this::refreshToken, Instant.now().plusSeconds(5));
            } else {
                //No time left, fail fatally
                logger.error("Error refreshing Token, system will exit", e);
                System.exit(1);
            }
        }
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

    @Override
    public Health health() {
        if (this.deviceId.isEmpty()) {
            return Health.down().withDetail("reason", "Musikbot spotifyd has not been found").build();
        } else {
            return Health.up().build();
        }
    }

    @Scheduled(fixedRate = 60000)
    public void updateSpotifyDevice() {
        try {
            Device[] devices = this.spotifyApi.getUsersAvailableDevices().build().execute();
            boolean found = false;
            for (Device d : devices) {
                if (d.getName().equalsIgnoreCase(properties.getSpotifyDeviceName())) {
                    found = true;
                    if (!this.deviceId.equals(d.getId())) {
                        this.deviceId = d.getId();
                        logger.info(String.format("Spotify Device updated: %s", this.deviceId));
                    }
                }
            }

            if (!found) {
                this.deviceId = "";
            }
        } catch (IOException | SpotifyWebApiException | ParseException e) {
            logger.warn("Error updating Spotify Device", e);
            this.deviceId = "";
        }
    }
}
