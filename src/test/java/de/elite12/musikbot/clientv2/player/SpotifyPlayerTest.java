package de.elite12.musikbot.clientv2.player;

import com.neovisionaries.i18n.CountryCode;
import de.elite12.musikbot.clientv2.audio.PlaybackExpectation;
import de.elite12.musikbot.shared.dtos.SongDTO;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.io.IOException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpotifyPlayerTest {

    @Test
    void successfulPlayPauseResumeStopAndFinishDriveExpectation() throws Exception {
        PlaybackExpectation expectation = new PlaybackExpectation();
        SpotifyApi spotify = mock(SpotifyApi.class, RETURNS_DEEP_STUBS);
        SpotifyPlayer player = player(spotify, expectation);
        SongDTO song = mock(SongDTO.class);
        Track track = mock(Track.class);
        when(song.getId()).thenReturn("track-id");
        when(track.getIsPlayable()).thenReturn(true);
        when(track.getDurationMs()).thenReturn(60_000);
        when(spotify.getTrack("track-id").market(CountryCode.DE).build().execute()).thenReturn(track);

        player.play(song);
        assertTrue(expectation.isExpected());

        player.pause();
        assertFalse(expectation.isExpected());

        player.pause();
        assertTrue(expectation.isExpected());

        player.stop();
        assertFalse(expectation.isExpected());

        expectation.playing();
        ReflectionTestUtils.invokeMethod(player, "finished");
        assertFalse(expectation.isExpected());
    }

    @Test
    void failedPlayClearsExpectation() throws Exception {
        PlaybackExpectation expectation = new PlaybackExpectation();
        expectation.playing();
        SpotifyApi spotify = mock(SpotifyApi.class, RETURNS_DEEP_STUBS);
        SpotifyPlayer player = player(spotify, expectation);
        SongDTO song = mock(SongDTO.class);
        Track track = mock(Track.class);
        when(song.getId()).thenReturn("track-id");
        when(track.getIsPlayable()).thenReturn(true);
        when(track.getDurationMs()).thenReturn(60_000);
        when(spotify.getTrack("track-id").market(CountryCode.DE).build().execute()).thenReturn(track);
        when(spotify.startResumeUsersPlayback().device_id("device-id").uris(any()).build().execute())
                .thenThrow(new IOException("play failed"));

        player.play(song);

        assertFalse(expectation.isExpected());
    }

    private static SpotifyPlayer player(SpotifyApi spotify, PlaybackExpectation expectation) {
        SpotifyPlayer player = new SpotifyPlayer();
        ReflectionTestUtils.setField(player, "spotifyApi", spotify);
        ReflectionTestUtils.setField(player, "playbackExpectation", expectation);
        ReflectionTestUtils.setField(player, "applicationEventPublisher", mock(ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(player, "taskScheduler", mock(TaskScheduler.class));
        ReflectionTestUtils.setField(player, "deviceId", "device-id");
        ReflectionTestUtils.setField(player, "endtime", Instant.now().plusSeconds(60));
        return player;
    }
}
