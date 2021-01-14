package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinished;
import de.elite12.musikbot.shared.clientDTO.SimpleCommand;
import de.elite12.musikbot.shared.clientDTO.Song;
import de.elite12.musikbot.shared.clientDTO.VolumeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketHttpHeaders;
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
public class ConnectionService implements ApplicationListener<SongFinished>, StompFrameHandler {

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

    @Autowired
    private OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager;

    private final WebSocketStompClient webSocketStompClient;

    private StompSession session;

    public ConnectionService() {
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        webSocketStompClient = new WebSocketStompClient(webSocketClient);
        webSocketStompClient.setMessageConverter(new MappingJackson2MessageConverter());
    }

    @PostConstruct
    public void postConstruct() {
        webSocketStompClient.setTaskScheduler(messageBrokerTaskScheduler);
        webSocketStompClient.setDefaultHeartbeat(new long[]{30000,30000});

        WebSocketHttpHeaders webSocketHttpHeaders = new WebSocketHttpHeaders();
        webSocketHttpHeaders.setBearerAuth(oAuth2AuthorizedClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("musikbot").principal("musikbot").build()).getAccessToken().getTokenValue());

        ListenableFuture<StompSession> connect = this.webSocketStompClient.connect(clientv2ServiceProperties.getServerurl(), webSocketHttpHeaders, new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession _session, StompHeaders connectedHeaders) {
                session = _session;
                logger.info("Connected to Server");
                session.subscribe("/topic/client", ConnectionService.this);
                requestSong();
            }

            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                logger.error("Exception", exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                if (exception instanceof ConnectionLostException) {
                    //TODO reconnect;
                }
                logger.error("Error Connecting to Server", exception);
                System.exit(1);
            }
        });
    }

    @PreDestroy
    public void preDestroy() {
        this.webSocketStompClient.stop();
    }

    public void requestSong() {
        this.session.send("/musikbot/client", new SimpleCommand(SimpleCommand.CommandType.REQUEST_SONG));
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
        if(payload instanceof SimpleCommand) {
            SimpleCommand command = (SimpleCommand) payload;
            switch (command.getCommand()) {
                case PAUSE -> playerService.pause();
                case STOP -> playerService.stop();
                case SHUTDOWN -> {
                    logger.warn("Received Shutdown Signal");
                    this.shutdown();
                }
            }
        }
        if(payload instanceof VolumeCommand) {
            VolumeCommand command = (VolumeCommand) payload;
            volumeService.setVolume(command.getVolume());
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
}
