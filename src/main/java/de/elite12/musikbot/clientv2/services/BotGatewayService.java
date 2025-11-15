package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.*;
import de.elite12.musikbot.proto.*;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.grpc.client.ChannelBuilderOptions;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.grpc.client.interceptor.security.BearerTokenAuthenticationInterceptor;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

@Service
public class BotGatewayService {

    private final Logger logger = LoggerFactory.getLogger(BotGatewayService.class);
    private final BotGatewayGrpc.BotGatewayStub botGateway;
    @Autowired
    private Clientv2ServiceProperties clientv2ServiceProperties;
    @Autowired
    private OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Nullable
    private StreamObserver<BotEvent> session;

    public BotGatewayService(GrpcChannelFactory channels) {
        this.botGateway = BotGatewayGrpc.newStub(
                channels.createChannel(
                        "localhost:9090",
                        ChannelBuilderOptions.defaults().withInterceptors(
                                List.of(new BearerTokenAuthenticationInterceptor(() ->
                                        oAuth2AuthorizedClientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId("musikbot").principal("musikbot").build()).getAccessToken().getTokenValue()
                                ))
                        )
                )
        );
    }

    @EventListener
    public void afterStartup(ContextRefreshedEvent event) {
        this.connect();
    }

    @PreDestroy
    public void preDestroy() {
        if (this.session != null) this.session.onCompleted();
    }

    private void connect() {
        //clientv2ServiceProperties.getServerurl()
        this.session = this.botGateway.connect(new BotSession());
    }

    @EventListener
    public void onRequestSongEvent(@NotNull RequestSongEvent event) {
        if (this.session != null) {
            this.session.onNext(BotEvent.newBuilder().setSongRequest(SongRequest.getDefaultInstance()).build());
        }
    }

    @EventListener
    public void onNoListenerEvent(@NotNull NoListenerEvent event) {
        if (this.session != null) {
            this.session.onNext(BotEvent.newBuilder().setNoListeners(NoListeners.getDefaultInstance()).build());
        }
    }

    private class BotSession implements ClientResponseObserver<BotEvent, BotCommand> {

        @Override
        public void onNext(BotCommand botCommand) {
            switch (botCommand.getCommandCase()) {
                case PAUSE -> {
                    applicationEventPublisher.publishEvent(new PauseCommandEvent(this));
                }
                case STOP -> {
                    applicationEventPublisher.publishEvent(new StopCommandEvent(this));
                }
                case SHUTDOWN -> {
                    applicationEventPublisher.publishEvent(new ShutdownCommandEvent(this));
                }
                case VOLUME -> {
                    applicationEventPublisher.publishEvent(new VolumeCommandEvent(this, (short) botCommand.getVolume().getVolume()));
                }
                case PLAY -> {
                    applicationEventPublisher.publishEvent(PlayCommandEvent.fromProto(this, botCommand.getPlay().getSong()));
                }
                case COMMAND_NOT_SET -> {
                    logger.warn("Command not set");
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            logger.error("Exception", throwable);
            session = null;
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    connect();
                }
            }, 5000);
        }

        @Override
        public void onCompleted() {
            logger.error("Connection closed");
            session = null;
            new Timer().schedule(new TimerTask() {
                @Override
                public void run() {
                    connect();
                }
            }, 5000);
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<BotEvent> clientCallStreamObserver) {
            clientCallStreamObserver.setOnReadyHandler(() -> {
                logger.info("Connected to Server");
                applicationEventPublisher.publishEvent(new ConnectedEvent(this));
            });
        }
    }
}
