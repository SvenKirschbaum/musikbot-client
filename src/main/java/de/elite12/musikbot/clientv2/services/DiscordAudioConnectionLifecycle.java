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
    private boolean disconnectCompletionReported;

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
        disconnectCompletionReported = false;
        try {
            connection.setSendingHandler(handler);
        } catch (RuntimeException failure) {
            release(attempt);
            throw failure;
        }

        state = State.OPENING;
        return attempt;
    }

    synchronized boolean open(JoinAttempt attempt, Runnable openConnection) {
        if (attempt == null || attempt.opened || state == State.DETACHED || currentAttempt != attempt) {
            return false;
        }
        attempt.opened = true;
        try {
            openConnection.run();
        } catch (RuntimeException failure) {
            if (currentAttempt == attempt && state != State.DETACHED) {
                release(attempt);
                state = State.IDLE;
            }
            throw failure;
        }
        return state != State.DETACHED && currentAttempt == attempt;
    }

    void detach(boolean completeDisconnect) {
        boolean completed = false;
        synchronized (this) {
            if (state == State.DETACHED) {
                return;
            }
            boolean active = state != State.IDLE || currentAttempt != null;
            release(currentAttempt);
            state = State.DETACHED;
            if (completeDisconnect && active) {
                completed = markDisconnectCompleteLocked();
            }
        }
        if (completed) {
            disconnectComplete.run();
        }
    }

    void disconnect() {
        boolean completed = false;
        synchronized (this) {
            if (state == State.CLOSING || state == State.DETACHED) {
                return;
            }
            if (state == State.IDLE && connection.status() == ConnectionStatus.NOT_CONNECTED) {
                completed = markDisconnectCompleteLocked();
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
            if (state == State.DETACHED) {
                return;
            }
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
            if (state == State.DETACHED || status == ConnectionStatus.AUDIO_REGION_CHANGE) {
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
            return markDisconnectCompleteLocked();
        }
        return false;
    }

    private boolean markDisconnectCompleteLocked() {
        if (disconnectCompletionReported) {
            return false;
        }
        disconnectCompletionReported = true;
        return true;
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
        private boolean opened;

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
        CLOSING,
        DETACHED
    }
}
