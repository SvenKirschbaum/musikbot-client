package de.elite12.musikbot.clientv2.data;

import de.elite12.musikbot.proto.SongType;

public enum SongTypes {
    YOUTUBE_VIDEO,
    SPOTIFY_TRACK;

    public static SongTypes fromProto(SongType songType) {
        return switch (songType) {
            case YOUTUBE -> YOUTUBE_VIDEO;
            case SPOTIFY -> SPOTIFY_TRACK;
            default -> throw new IllegalArgumentException("Unknown Song Type: " + songType);
        };
    }

    public SongType toProto() {
        return switch (this) {
            case YOUTUBE_VIDEO -> SongType.YOUTUBE;
            case SPOTIFY_TRACK -> SongType.SPOTIFY;
        };
    }
}
