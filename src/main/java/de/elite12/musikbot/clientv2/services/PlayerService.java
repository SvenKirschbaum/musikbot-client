package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.player.Player;
import de.elite12.musikbot.shared.clientDTO.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.NoSuchElementException;

@Service
public class PlayerService {
    @Autowired
    private ListableBeanFactory listableBeanFactory;

    private Player[] players;

    private Player activeplayer;

    private Logger logger = LoggerFactory.getLogger(PlayerService.class);

    @PostConstruct
    private void postConstruct() {
        this.players = this.listableBeanFactory.getBeansOfType(Player.class).values().toArray(Player[]::new);
        this.activeplayer = this.players[0];
    }

    public void stop() {
        this.activeplayer.stop();
    }

    public void pause() {
        this.activeplayer.pause();
    }

    public void play(Song song) {
        this.activatePlayer(song.getSongtype());
        this.activeplayer.play(song);
    }

    private Player activatePlayer(String type) {
        for(Player player:this.players) {
            if(player.getSupportedTypes().contains(type)) {
                this.activeplayer.stop();
                return this.activeplayer = player;
            }
        }
        throw new NoSuchElementException(String.format("No player supports the requested Songtype: %s", type));
    }
}
