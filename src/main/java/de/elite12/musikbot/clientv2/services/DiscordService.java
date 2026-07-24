package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.NoListenerEvent;
import de.elite12.musikbot.clientv2.events.SongFinishedEvent;
import de.elite12.musikbot.clientv2.events.StartSongEvent;
import de.elite12.musikbot.clientv2.events.StopSongEvent;
import de.elite12.musikbot.clientv2.audio.SpotifyAudioPipeline;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Objects;

import static net.dv8tion.jda.api.requests.GatewayIntent.GUILD_VOICE_STATES;

@Service
@ConditionalOnProperty(
        value = "discord.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DiscordService extends ListenerAdapter {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private final JDA JDA;
    private final SpotifyAudioPipeline pipeline;
    private final DiscordAudioConnectionLifecycleRegistry<AudioManager> audioLifecycles;

    public DiscordService(Clientv2ServiceProperties properties, SpotifyAudioPipeline pipeline) throws InterruptedException {
        this.pipeline = pipeline;
        this.audioLifecycles = new DiscordAudioConnectionLifecycleRegistry<>(
                this::createLifecycle,
                this::detachLifecycle
        );
        JDABuilder builder = JDABuilder.create(properties.getDiscordToken(), EnumSet.of(GUILD_VOICE_STATES));
        builder.setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()));

        //Disable Cache for not needed Features explicitly
        builder.disableCache(CacheFlag.ACTIVITY, CacheFlag.EMOJI, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS);

        //Retrieve events in this service
        builder.addEventListeners(this);
        //Clear current Activity
        builder.setActivity(null);

        this.JDA = builder.build();

        this.JDA.updateCommands().addCommands(
                Commands.slash("join", "Instruct the Bot to join your current voice channel"),
                Commands.slash("leave", "Instruct the Bot to leave the current voice channel")
        ).queue();

        this.JDA.awaitReady();
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getName().equals("join")) {
            this.onJoinCommand(event);
        }
        if (event.getName().equals("leave")) {
            this.onLeaveCommand(event);
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        AudioChannelUnion currentChannel = Objects.requireNonNull(event.getGuild().getSelfMember().getVoiceState()).getChannel();
        //Check if last member in channel
        if (currentChannel != null && (Objects.equals(event.getChannelJoined(), currentChannel) || Objects.equals(event.getChannelLeft(), currentChannel))) {
            if (currentChannel.getMembers().size() <= 1) {
                lifecycleFor(event.getGuild().getAudioManager()).disconnect();
            }
        }

        //Check if bot has been disconnected
        if (event.getMember().getIdLong() == this.JDA.getSelfUser().getIdLong() && event.getNewValue() == null) {
            lifecycleFor(event.getGuild().getAudioManager()).disconnected();
        }
    }

    @Override
    public void onGuildLeave(@NotNull GuildLeaveEvent event) {
        audioLifecycles.remove(event.getGuild().getIdLong());
    }

    private void onLeaveCommand(@NotNull SlashCommandInteractionEvent event) {
        //Defer Reply
        event.deferReply().queue();
        //Get InteractionHook to later reply
        InteractionHook interactionHook = event.getHook();
        //The Member who caused the Event
        Member eventMember = event.getMember();

        //First condition is sufficient for eventMember to not be null due to the API contract, but for better IDE integration we check explicitly for null too
        if (!event.isFromGuild() || eventMember == null) {
            interactionHook.editOriginal("This can only be used from inside a Guild!").queue();
            return;
        }

        Guild guild = Objects.requireNonNull(event.getGuild());
        AudioManager audioManager = guild.getAudioManager();
        boolean connected = audioManager.isConnected();
        lifecycleFor(audioManager).disconnect();

        if (!connected) {
            interactionHook.editOriginal("I am not currently in a voice channel!").queue();
            return;
        }

        interactionHook.editOriginal("Will do!").queue();
    }

    private void onJoinCommand(@NotNull SlashCommandInteractionEvent event) {
        //Defer Reply
        event.deferReply().queue();
        //Get InteractionHook to later reply
        InteractionHook interactionHook = event.getHook();
        //The Member who caused the Event
        Member eventMember = event.getMember();

        //First condition is sufficient for eventMember to not be null due to the API contract, but for better IDE integration we check explicitly for null too
        if (!event.isFromGuild() || eventMember == null) {
            interactionHook.editOriginal("This can only be used from inside a Guild!").queue();
            return;
        }

        //Cant be Null when the application is correctly configured
        GuildVoiceState voiceState = Objects.requireNonNull(eventMember.getVoiceState());

        if (!voiceState.inAudioChannel()) {
            interactionHook.editOriginal("You have to join a Audio-Channel yourself first!").queue();
            return;
        }

        Guild guild = Objects.requireNonNull(event.getGuild());
        AudioManager audioManager = guild.getAudioManager();
        // Disable Auto-Reconnect, to prevent race condition when disconnecting due to being moved into an empty channel
        audioManager.setAutoReconnect(false);
        AudioChannelUnion channel = voiceState.getChannel();

        DiscordAudioConnectionLifecycle lifecycle = lifecycleFor(audioManager);
        DiscordAudioConnectionLifecycle.JoinAttempt attempt = lifecycle.beginJoin();
        if (attempt == null) {
            interactionHook.editOriginal("I am already connected or connecting to a voice channel!").queue();
            return;
        }

        try {
            audioManager.openAudioConnection(channel);
        } catch (InsufficientPermissionException insufficientPermissionException) {
            lifecycle.openFailed(attempt);
            interactionHook.editOriginal("I dont have permissions to join your channel!").queue();
            return;
        } catch (RuntimeException failure) {
            lifecycle.openFailed(attempt);
            throw failure;
        }

        interactionHook.editOriginal("Will do!").queue();
    }

    private DiscordAudioConnectionLifecycle lifecycleFor(AudioManager audioManager) {
        return audioLifecycles.lifecycleFor(audioManager.getGuild().getIdLong(), audioManager);
    }

    private DiscordAudioConnectionLifecycle createLifecycle(AudioManager audioManager) {
        DiscordAudioConnectionLifecycle lifecycle = new DiscordAudioConnectionLifecycle(
                pipeline.broadcaster(),
                new DiscordAudioConnectionLifecycle.Connection() {
                    @Override
                    public ConnectionStatus status() {
                        return audioManager.getConnectionStatus();
                    }

                    @Override
                    public AudioSendHandler sendingHandler() {
                        return audioManager.getSendingHandler();
                    }

                    @Override
                    public void setSendingHandler(AudioSendHandler handler) {
                        audioManager.setSendingHandler(handler);
                    }

                    @Override
                    public void close() {
                        audioManager.closeAudioConnection();
                    }
                },
                this::checkNoListeners
        );
        audioManager.setConnectionListener(lifecycle);
        return lifecycle;
    }

    private void detachLifecycle(AudioManager audioManager, DiscordAudioConnectionLifecycle lifecycle) {
        if (audioManager.getConnectionListener() == lifecycle) {
            audioManager.setConnectionListener(null);
        }
        lifecycle.detach();
    }

    private void checkNoListeners() {
        if (this.JDA.getAudioManagers().stream().noneMatch(AudioManager::isConnected)) {
            this.applicationEventPublisher.publishEvent(new NoListenerEvent(this));
        }
    }

    @EventListener
    public void onSongStart(StartSongEvent event) {
        this.JDA.getPresence().setActivity(Activity.listening(event.getSong().getTitle()));
    }

    @EventListener
    public void onSongStop(StopSongEvent event) {
        this.JDA.getPresence().setActivity(null);
    }

    @EventListener
    public void onSongFinished(SongFinishedEvent event) {
        this.JDA.getPresence().setActivity(null);
    }
}
