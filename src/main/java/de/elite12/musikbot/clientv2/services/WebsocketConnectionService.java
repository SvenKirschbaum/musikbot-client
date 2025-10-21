package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.CommandEvent;
import de.elite12.musikbot.clientv2.events.ConnectedEvent;
import de.elite12.musikbot.clientv2.events.NoListenerEvent;
import de.elite12.musikbot.clientv2.events.RequestSongEvent;
import de.elite12.musikbot.shared.ClientDTO;
import de.elite12.musikbot.shared.dtos.NoListenerCommand;
import de.elite12.musikbot.shared.dtos.SongRequest;
import jakarta.annotation.PreDestroy;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class WebsocketConnectionService implements StompFrameHandler, StompSessionHandler {

    private final Logger logger = LoggerFactory.getLogger(WebsocketConnectionService.class);

    @Autowired
    private Clientv2ServiceProperties clientv2ServiceProperties;
    @Autowired
    private OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private final WebSocketStompClient webSocketStompClient;

    private StompSession session = null;

    public WebsocketConnectionService(TaskScheduler messageBrokerTaskScheduler) {
        StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
        this.webSocketStompClient = new WebSocketStompClient(webSocketClient);
        this.webSocketStompClient.setMessageConverter(new MappingJackson2MessageConverter());
        this.webSocketStompClient.setTaskScheduler(messageBrokerTaskScheduler);
        this.webSocketStompClient.setDefaultHeartbeat(new long[]{0, 25000});
    }

    @EventListener
    public void afterStartup(ContextRefreshedEvent event) {
        this.connect();
    }

    @PreDestroy
    public void preDestroy() {
        this.webSocketStompClient.stop();
    }

    private void connect() {
        WebSocketHttpHeaders webSocketHttpHeaders = new WebSocketHttpHeaders();
        webSocketHttpHeaders.setBearerAuth(oAuth2AuthorizedClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("musikbot").principal("musikbot").build()).getAccessToken().getTokenValue());
        this.webSocketStompClient.connect(clientv2ServiceProperties.getServerurl(), webSocketHttpHeaders, this);
    }

    @Override
    public @NotNull Type getPayloadType(StompHeaders headers) {
        try {
            String type = headers.getFirst("type");
            if (type == null) throw new ClassNotFoundException("Type Header missing");
            return Class.forName("de.elite12.musikbot.shared.dtos." + type);
        } catch (ClassNotFoundException e) {
            logger.error("Error parsing Message Type", e);
            return Object.class;
        }
    }

    @Override
    public void handleFrame(@NotNull StompHeaders headers, Object payload) {
        this.applicationEventPublisher.publishEvent(new CommandEvent(this, (ClientDTO) payload));
    }

    @Override
    public void afterConnected(StompSession session, @NotNull StompHeaders connectedHeaders) {
        logger.info("Connected to Server");
        session.subscribe("/topic/client", this);
        this.session = session;
        this.applicationEventPublisher.publishEvent(new ConnectedEvent(this));
    }

    @Override
    public void handleException(@NotNull StompSession session, StompCommand command, @NotNull StompHeaders headers, @NotNull byte[] payload, @NotNull Throwable exception) {
        logger.error("Exception", exception);
    }

    @Override
    public void handleTransportError(@NotNull StompSession session, @NotNull Throwable exception) {
        this.session = null;
        if (exception instanceof ConnectionLostException) {
            logger.error("Lost websocket connection", exception);
        } else {
            logger.error("Error Connecting to Server", exception);
        }

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                connect();
            }
        }, 5000);
    }

    private void sendCommand(ClientDTO command) {
        if (this.session != null) this.session.send("/musikbot/client", command);
    }

    @EventListener
    public void onRequestSongEvent(@NotNull RequestSongEvent event) {
        this.sendCommand(new SongRequest());
    }

    @EventListener
    public void onNoListenerEvent(@NotNull NoListenerEvent event) {
        this.sendCommand(new NoListenerCommand());
    }
}
