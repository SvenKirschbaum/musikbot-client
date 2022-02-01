package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.events.*;
import de.elite12.musikbot.clientv2.player.Player;
import de.elite12.musikbot.shared.clientDTO.SimpleCommand;
import de.elite12.musikbot.shared.clientDTO.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.NoSuchElementException;

@Service
public class PlayerService {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private final Player[] players;

    private Player activeplayer;

    private final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    public PlayerService(ListableBeanFactory listableBeanFactory) {
        this.players = listableBeanFactory.getBeansOfType(Player.class).values().toArray(Player[]::new);
        this.activeplayer = this.players[0];
    }

    @PostConstruct
    public void postConstruct() {
        this.applicationEventPublisher.publishEvent(new RequestSongEvent(this));
    }

    private void activatePlayer(String type) {
        for (Player player : this.players) {
            if (player.getSupportedTypes().contains(type)) {
                this.activeplayer.stop();
                this.activeplayer = player;
                return;
            }
        }
        throw new NoSuchElementException(String.format("No player supports the requested Songtype: %s", type));
    }

    @PreDestroy
    public void preDestroy() {
        this.activeplayer.stop();
    }

    @EventListener
    public void handleCommandEvent(CommandEvent event) {
        if (event.getCommand() instanceof SimpleCommand) {
            SimpleCommand command = (SimpleCommand) event.getCommand();
            switch (command.getCommand()) {
                case PAUSE -> this.activeplayer.pause();
                case STOP -> {
                    this.activeplayer.stop();
                    this.applicationEventPublisher.publishEvent(new StopSongEvent(this));
                }
            }
        }
        if (event.getCommand() instanceof Song) {
            Song song = (Song) event.getCommand();
            try {
                this.activatePlayer(song.getSongtype());
                this.activeplayer.play(song);
                this.applicationEventPublisher.publishEvent(new StartSongEvent(this, song));
            } catch (NoSuchElementException e) {
                logger.error(String.format("Error playing Song %s", song.getSonglink()), e);
                this.applicationEventPublisher.publishEvent(new RequestSongEvent(this));
            }
        }
    }

    @EventListener
    public void handleSongFinishedEvent(SongFinishedEvent event) {
        this.applicationEventPublisher.publishEvent(new RequestSongEvent(this));
    }
}
