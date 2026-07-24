package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.audio.AudioFrameBroadcaster;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DiscordAudioConnectionLifecycleRegistryTest {

    @Test
    void managerReplacementDetachesOldBindingAndGuildRemovalDetachesCurrentBinding() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(2, ignored -> {});
        AtomicInteger detachments = new AtomicInteger();
        DiscordAudioConnectionLifecycleRegistry<FakeManager> registry =
                new DiscordAudioConnectionLifecycleRegistry<>(
                        manager -> new DiscordAudioConnectionLifecycle(
                                broadcaster,
                                manager.connection,
                                () -> {}
                        ),
                        (manager, lifecycle) -> {
                            detachments.incrementAndGet();
                            lifecycle.detach();
                        }
                );
        FakeManager firstManager = new FakeManager();
        FakeManager replacementManager = new FakeManager();

        DiscordAudioConnectionLifecycle first = registry.lifecycleFor(1L, firstManager);
        assertNotNull(first.beginJoin());
        assertEquals(1, broadcaster.subscriberCount());
        assertSame(first, registry.lifecycleFor(1L, firstManager));

        DiscordAudioConnectionLifecycle replacement = registry.lifecycleFor(1L, replacementManager);

        assertNotSame(first, replacement);
        assertEquals(1, detachments.get());
        assertNull(firstManager.connection.sendingHandler);
        assertEquals(0, broadcaster.subscriberCount());

        registry.remove(1L);
        assertEquals(2, detachments.get());
    }

    private static final class FakeManager {
        private final FakeConnection connection = new FakeConnection();
    }

    private static final class FakeConnection implements DiscordAudioConnectionLifecycle.Connection {
        private AudioSendHandler sendingHandler;

        @Override
        public ConnectionStatus status() {
            return ConnectionStatus.NOT_CONNECTED;
        }

        @Override
        public AudioSendHandler sendingHandler() {
            return sendingHandler;
        }

        @Override
        public void setSendingHandler(AudioSendHandler handler) {
            sendingHandler = handler;
        }

        @Override
        public void close() {}
    }
}
