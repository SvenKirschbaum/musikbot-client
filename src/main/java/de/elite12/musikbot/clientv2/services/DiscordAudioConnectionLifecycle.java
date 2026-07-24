package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.audio.AudioFrameBroadcaster;
import de.elite12.musikbot.clientv2.audio.SpotifyAudioSendHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import org.jetbrains.annotations.NotNull;

final class DiscordAudioConnectionLifecycle implements ConnectionListener {

    private final AudioFrameBroadcaster broadcaster;
    private final Connection connection;
    private final Runnable disconnectComplete;

    private State state = State.IDLE;
    private JoinAttempt currentAttempt;

    DiscordAudioConnectionLifecycle(
            AudioFrameBroadcaster broadcaster,
            Connection connection,
            Runnable disconnectComplete
    ) {
        this.broadcaster = broadcaster;
        this.connection = connection;
        this.disconnectComplete = disconnectComplete;
    }

    synchronized JoinAttempt beginJoin() {
        if (state != State.IDLE
                || connection.status() != ConnectionStatus.NOT_CONNECTED
                || connection.sendingHandler() != null) {
            return null;
        }

        SpotifyAudioSendHandler handler = broadcaster.subscribe();
        JoinAttempt attempt = new JoinAttempt(handler);
        currentAttempt = attempt;
        try {
            connection.setSendingHandler(handler);
        } catch (RuntimeException failure) {
            release(attempt);
            throw failure;
        }

        state = State.OPENING;
        return attempt;
    }

    synchronized void openFailed(JoinAttempt attempt) {
        if (currentAttempt == attempt) {
            release(attempt);
            state = State.IDLE;
        }
    }

    synchronized void detach() {
        release(currentAttempt);
        state = State.IDLE;
    }

    void disconnect() {
        boolean completed = false;
        synchronized (this) {
            if (state == State.CLOSING) {
                return;
            }
            if (state == State.IDLE && connection.status() == ConnectionStatus.NOT_CONNECTED) {
                completed = true;
            } else {
                state = State.CLOSING;
                release(currentAttempt);
                connection.close();
            }
        }
        if (completed) {
            disconnectComplete.run();
        }
    }

    void disconnected() {
        boolean completed;
        synchronized (this) {
            if (connection.status() != ConnectionStatus.NOT_CONNECTED) {
                if (state != State.IDLE) {
                    state = State.CLOSING;
                    release(currentAttempt);
                }
                return;
            }
            completed = completeDisconnectLocked();
        }
        if (completed) {
            disconnectComplete.run();
        }
    }

    @Override
    public void onStatusChange(@NotNull ConnectionStatus status) {
        boolean completed = false;
        synchronized (this) {
            if (status == ConnectionStatus.AUDIO_REGION_CHANGE) {
                return;
            } else if (status == ConnectionStatus.CONNECTED) {
                if (state == State.OPENING) {
                    state = State.CONNECTED;
                }
            } else if (connection.status() == ConnectionStatus.NOT_CONNECTED) {
                completed = completeDisconnectLocked();
            }
        }
        if (completed) {
            disconnectComplete.run();
        }
    }

    private boolean completeDisconnectLocked() {
        if (state != State.IDLE || currentAttempt != null) {
            release(currentAttempt);
            state = State.IDLE;
            return true;
        }
        return false;
    }

    private void release(JoinAttempt attempt) {
        if (attempt == null) {
            return;
        }
        SpotifyAudioSendHandler handler = attempt.handler;
        handler.close();
        if (connection.sendingHandler() == handler) {
            connection.setSendingHandler(null);
        }
        if (currentAttempt == attempt) {
            currentAttempt = null;
        }
    }

    static final class JoinAttempt {
        private final SpotifyAudioSendHandler handler;

        private JoinAttempt(SpotifyAudioSendHandler handler) {
            this.handler = handler;
        }
    }

    interface Connection {
        ConnectionStatus status();

        AudioSendHandler sendingHandler();

        void setSendingHandler(AudioSendHandler handler);

        void close();
    }

    private enum State {
        IDLE,
        OPENING,
        CONNECTED,
        CLOSING
    }
}
