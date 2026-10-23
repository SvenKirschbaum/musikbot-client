package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.audio.AudioFrameBroadcaster;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAudioConnectionLifecycleTest {

    private AudioFrameBroadcaster broadcaster;
    private FakeConnection connection;
    private AtomicInteger disconnectCompletions;
    private DiscordAudioConnectionLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        broadcaster = new AudioFrameBroadcaster(2, ignored -> {});
        connection = new FakeConnection();
        disconnectCompletions = new AtomicInteger();
        lifecycle = new DiscordAudioConnectionLifecycle(broadcaster, connection, disconnectCompletions::incrementAndGet);
    }

    @Test
    void failedJoinPreservesAReplacementHandlerByIdentity() {
        AudioSendHandler replacement = new StubHandler();

        assertThrows(IllegalStateException.class, () -> startJoin(() -> {
            connection.sendingHandler = replacement;
            throw new IllegalStateException("open failed");
        }));

        assertSame(replacement, connection.sendingHandler);
        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    void staleLeaveClosesOnlyItsOwnedHandlerAndWaitsForCompletion() {
        assertTrue(startJoin(() -> connection.status = ConnectionStatus.CONNECTING_AWAITING_ENDPOINT));
        AudioSendHandler replacement = new StubHandler();
        connection.sendingHandler = replacement;

        lifecycle.disconnect();

        assertSame(replacement, connection.sendingHandler);
        assertEquals(0, broadcaster.subscriberCount());
        assertEquals(1, connection.closeCalls);
        assertEquals(0, disconnectCompletions.get());

        connection.status = ConnectionStatus.NOT_CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.NOT_CONNECTED);
        assertEquals(1, disconnectCompletions.get());
    }

    @Test
    void overlappingJoinsAreRejectedUntilDisconnectCompletes() {
        AtomicInteger opens = new AtomicInteger();
        Runnable open = () -> {
            opens.incrementAndGet();
            connection.status = ConnectionStatus.CONNECTING_AWAITING_ENDPOINT;
        };

        assertTrue(startJoin(open));
        assertFalse(startJoin(open));
        lifecycle.disconnect();
        assertFalse(startJoin(open));
        assertEquals(1, opens.get());

        connection.status = ConnectionStatus.NOT_CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.NOT_CONNECTED);

        assertTrue(startJoin(open));
        assertEquals(2, opens.get());
    }

    @Test
    void asynchronousConnectionFailureClosesItsOwnedHandler() {
        assertTrue(startJoin(() -> connection.status = ConnectionStatus.CONNECTING_AWAITING_ENDPOINT));
        assertEquals(1, broadcaster.subscriberCount());

        connection.status = ConnectionStatus.NOT_CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.ERROR_CONNECTION_TIMEOUT);

        assertNull(connection.sendingHandler);
        assertEquals(0, broadcaster.subscriberCount());
        assertEquals(1, disconnectCompletions.get());
    }

    @Test
    void audioRegionChangePreservesAttemptThroughReplacementConnection() {
        assertTrue(startJoin(() -> connection.status = ConnectionStatus.CONNECTING_AWAITING_ENDPOINT));
        connection.status = ConnectionStatus.CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.CONNECTED);
        AudioSendHandler ownedHandler = connection.sendingHandler;

        connection.status = ConnectionStatus.NOT_CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.AUDIO_REGION_CHANGE);

        assertSame(ownedHandler, connection.sendingHandler);
        assertEquals(1, broadcaster.subscriberCount());
        assertEquals(0, disconnectCompletions.get());
        assertNull(lifecycle.beginJoin());

        connection.status = ConnectionStatus.CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.CONNECTED);

        assertSame(ownedHandler, connection.sendingHandler);
        assertEquals(1, broadcaster.subscriberCount());
        assertEquals(0, disconnectCompletions.get());
        assertNull(lifecycle.beginJoin());
    }

    @Test
    void disconnectCompletionIsReportedOnlyAfterTerminalStatus() {
        assertTrue(startJoin(() -> connection.status = ConnectionStatus.CONNECTING_AWAITING_ENDPOINT));
        connection.status = ConnectionStatus.CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.CONNECTED);

        lifecycle.disconnect();

        assertEquals(1, connection.closeCalls);
        assertEquals(0, disconnectCompletions.get());
        assertEquals(0, broadcaster.subscriberCount());

        lifecycle.disconnected();
        assertEquals(0, disconnectCompletions.get());

        connection.status = ConnectionStatus.NOT_CONNECTED;
        lifecycle.onStatusChange(ConnectionStatus.NOT_CONNECTED);
        assertEquals(1, disconnectCompletions.get());
    }

    @Test
    void disconnectBetweenBeginAndOpenPreventsStaleOpen() throws Exception {
        CountDownLatch attemptCreated = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> opened = executor.submit(() -> {
                DiscordAudioConnectionLifecycle.JoinAttempt attempt = lifecycle.beginJoin();
                attemptCreated.countDown();
                await(allowOpen);
                return lifecycle.open(attempt, opens::incrementAndGet);
            });

            assertTrue(attemptCreated.await(5, TimeUnit.SECONDS));
            lifecycle.disconnect();
            allowOpen.countDown();

            assertFalse(opened.get());
        }
        assertEquals(0, opens.get());
        assertEquals(0, broadcaster.subscriberCount());
        assertEquals(1, connection.closeCalls);
    }

    @Test
    void detachBetweenBeginAndOpenPermanentlyRejectsStaleLifecycle() throws Exception {
        CountDownLatch attemptCreated = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> opened = executor.submit(() -> {
                DiscordAudioConnectionLifecycle.JoinAttempt attempt = lifecycle.beginJoin();
                attemptCreated.countDown();
                await(allowOpen);
                return lifecycle.open(attempt, opens::incrementAndGet);
            });

            assertTrue(attemptCreated.await(5, TimeUnit.SECONDS));
            lifecycle.detach(false);
            allowOpen.countDown();

            assertFalse(opened.get());
        }

        assertEquals(0, opens.get());
        assertNull(lifecycle.beginJoin());
        lifecycle.disconnect();
        lifecycle.disconnected();
        lifecycle.onStatusChange(ConnectionStatus.NOT_CONNECTED);
        assertEquals(0, disconnectCompletions.get());
        assertEquals(0, broadcaster.subscriberCount());
    }

    @Test
    void openAllowsReentrantStatusCallback() {
        AtomicInteger invalidOpens = new AtomicInteger();
        assertFalse(lifecycle.open(null, invalidOpens::incrementAndGet));
        assertEquals(0, invalidOpens.get());

        DiscordAudioConnectionLifecycle.JoinAttempt attempt = lifecycle.beginJoin();

        assertTrue(lifecycle.open(attempt, () -> {
            connection.status = ConnectionStatus.CONNECTED;
            lifecycle.onStatusChange(ConnectionStatus.CONNECTED);
        }));

        assertFalse(lifecycle.open(attempt, invalidOpens::incrementAndGet));
        assertEquals(0, invalidOpens.get());
        assertEquals(1, broadcaster.subscriberCount());
        assertEquals(0, disconnectCompletions.get());
    }

    private boolean startJoin(Runnable openConnection) {
        DiscordAudioConnectionLifecycle.JoinAttempt attempt = lifecycle.beginJoin();
        return attempt != null && lifecycle.open(attempt, openConnection);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", interrupted);
        }
    }

    private static final class FakeConnection implements DiscordAudioConnectionLifecycle.Connection {
        private ConnectionStatus status = ConnectionStatus.NOT_CONNECTED;
        private AudioSendHandler sendingHandler;
        private int closeCalls;

        @Override
        public ConnectionStatus status() {
            return status;
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
        public void close() {
            closeCalls++;
        }
    }

    private static final class StubHandler implements AudioSendHandler {
        @Override
        public boolean canProvide() {
            return false;
        }

        @Override
        public ByteBuffer provide20MsAudio() {
            return null;
        }

        @Override
        public boolean isOpus() {
            return false;
        }
    }
}
