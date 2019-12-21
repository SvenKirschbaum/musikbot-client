package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.clientDTO.Song;

import java.util.Set;

public abstract class AbstractPlayer {
    public abstract Set<String> getSupportedTypes();
    public abstract void play(Song song);
    public abstract void stop();
    public abstract void pause();
}
