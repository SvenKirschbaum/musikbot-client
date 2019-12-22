package de.elite12.musikbot.clientv2.player;

import de.elite12.musikbot.shared.clientDTO.Song;

import java.util.Set;

public interface Player {
    public Set<String> getSupportedTypes();
    public void play(Song song);
    public void stop();
    public void pause();
}
