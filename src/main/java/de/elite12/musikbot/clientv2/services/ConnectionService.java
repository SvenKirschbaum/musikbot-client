package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinished;
import de.elite12.musikbot.shared.clientDTO.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.NoSuchElementException;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class ConnectionService extends StompSessionHandlerAdapter implements ApplicationListener<SongFinished> {

    private final Logger logger = LoggerFactory.getLogger(ConnectionService.class);

    @Autowired
    private Clientv2ServiceProperties clientv2ServiceProperties;

    @Autowired
    private PlayerService playerService;

    @Autowired
    private VolumeService volumeService;

    @Autowired
    private ApplicationContext appContext;

    @Autowired
    private TaskScheduler messageBrokerTaskScheduler;

    private final WebSocketStompClient webSocketStompClient;
    private StompSession session;

    public ConnectionService() {
        webSocketStompClient = new WebSocketStompClient(new StandardWebSocketClient());
        webSocketStompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @PostConstruct
    public void postConstruct() {
        webSocketStompClient.setTaskScheduler(messageBrokerTaskScheduler);
        webSocketStompClient.setDefaultHeartbeat(new long[]{30000,30000});
        this.webSocketStompClient.connect(clientv2ServiceProperties.getServerurl(),this);
    }

    @PreDestroy
    public void preDestroy() {
        this.webSocketStompClient.stop();
    }

    @Override
    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
        this.session = session;
        logger.info("Connected to Server");
        session.subscribe("/user/queue/reply",this);
        session.subscribe("/user/queue/command",this);
        session.send("/app/client/auth",new AuthRequest(this.clientv2ServiceProperties.getClientkey()));
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        try {
            String type = headers.getFirst("type");
            if(type == null) throw new ClassNotFoundException("Type Header invalid");
            return Class.forName("de.elite12.musikbot.shared.clientDTO."+type);
        } catch (ClassNotFoundException e) {
            logger.error("Error parsing Message Type", e);
            return Object.class;
        }
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
        if(payload instanceof AuthResponse) {
            AuthResponse authResponse = (AuthResponse) payload;
            if(!authResponse.isSuccess())  {
                logger.error("Authorization failed");
                this.shutdown();
            }
            else {
                this.requestSong();
            }
        }
        if(payload instanceof SimpleCommand) {
            SimpleCommand command = (SimpleCommand) payload;
            switch (command.getCommand()) {
                case PAUSE:
                    playerService.pause();
                    break;
                case STOP:
                    playerService.stop();
                    break;
                case SHUTDOWN:
                    logger.warn("Received Shutdown Signal");
                    this.shutdown();
                    break;
            }
        }
        if(payload instanceof VolumeCommand) {
            VolumeCommand command = (VolumeCommand) payload;
            volumeService.setVolume(command.getVolume());
        }
        if(payload instanceof SimpleResponse) {
            SimpleResponse simpleResponse = (SimpleResponse) payload;
            switch (simpleResponse.getResponse()) {
                case NO_SONG_AVAILABLE:
                    break;
            }
        }
        if(payload instanceof Song) {
            Song song = (Song) payload;
            try {
                playerService.play(song);
            }
            catch(NoSuchElementException e) {
                logger.error(String.format("Error playing Song %s",song.getSonglink()),e);
                this.requestSong();
            }
        }
    }

    public void requestSong() {
        this.session.send("/app/client/song", new byte[0]);
    }

    @Override
    public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
        this.logger.error("Exception", exception);
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
        if(exception instanceof ConnectionLostException) {
            //TODO reconnect;
        }
        this.logger.error("Error Connecting to Server", exception);
        System.exit(1);
    }

    @Override
    public void onApplicationEvent(SongFinished songFinished) {
        this.requestSong();
    }

    private void shutdown() {
        new Timer().schedule(new TimerTask() {

            @Override
            public void run() {SpringApplication.exit(appContext, () -> 0);
            }
        }, 2500);
        new Timer().schedule(new TimerTask() {

            @Override
            public void run() {
                try {
                    Runtime.getRuntime().exec("kill 1");
                } catch (IOException e) {
                    System.exit(-1);
                }
            }
        }, 10000);
    }
}
