package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioFrameBroadcasterTest {

    @Test
    void publishesEachFrameToEverySubscriber() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler first = broadcaster.subscribe();
        SpotifyAudioSendHandler second = broadcaster.subscribe();

        broadcaster.publish(new byte[]{1, 2});

        assertArrayEquals(new byte[]{1, 2}, first.provide20MsAudio().array());
        assertArrayEquals(new byte[]{1, 2}, second.provide20MsAudio().array());
    }

    @Test
    void lateSubscriberReceivesOnlyFutureFrames() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler early = broadcaster.subscribe();
        broadcaster.publish(new byte[]{1});

        SpotifyAudioSendHandler late = broadcaster.subscribe();
        broadcaster.publish(new byte[]{2});

        assertArrayEquals(new byte[]{1}, early.provide20MsAudio().array());
        assertArrayEquals(new byte[]{2}, early.provide20MsAudio().array());
        assertArrayEquals(new byte[]{2}, late.provide20MsAudio().array());
        assertFalse(late.canProvide());
    }

    @Test
    void slowSubscriberDropsOldestWithoutAffectingAnotherSubscriber() {
        AtomicLong drops = new AtomicLong();
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(2, drops::addAndGet);
        SpotifyAudioSendHandler slow = broadcaster.subscribe();
        SpotifyAudioSendHandler current = broadcaster.subscribe();

        broadcaster.publish(new byte[]{1});
        assertArrayEquals(new byte[]{1}, current.provide20MsAudio().array());
        broadcaster.publish(new byte[]{2});
        assertArrayEquals(new byte[]{2}, current.provide20MsAudio().array());
        broadcaster.publish(new byte[]{3});

        assertArrayEquals(new byte[]{2}, slow.provide20MsAudio().array());
        assertArrayEquals(new byte[]{3}, slow.provide20MsAudio().array());
        assertEquals(1, drops.get());
    }

    @Test
    void resetClearsEverySubscriberQueue() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler first = broadcaster.subscribe();
        SpotifyAudioSendHandler second = broadcaster.subscribe();
        broadcaster.publish(new byte[]{1});

        broadcaster.reset();

        assertFalse(first.canProvide());
        assertFalse(second.canProvide());
        assertEquals(0, broadcaster.maxSubscriberQueueDepth());
    }

    @Test
    void reportsCurrentMaximumSubscriberQueueDepth() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler slow = broadcaster.subscribe();
        SpotifyAudioSendHandler current = broadcaster.subscribe();

        broadcaster.publish(new byte[]{1});
        current.provide20MsAudio();
        broadcaster.publish(new byte[]{2});

        assertEquals(2, broadcaster.maxSubscriberQueueDepth());
        slow.provide20MsAudio();
        assertEquals(1, broadcaster.maxSubscriberQueueDepth());
        slow.close();
        assertEquals(1, broadcaster.maxSubscriberQueueDepth());
        current.provide20MsAudio();
        assertEquals(0, broadcaster.maxSubscriberQueueDepth());
    }

    @Test
    void consumingFromOneSubscriberDoesNotConsumeFromAnother() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler first = broadcaster.subscribe();
        SpotifyAudioSendHandler second = broadcaster.subscribe();
        broadcaster.publish(new byte[]{1});

        assertArrayEquals(new byte[]{1}, first.provide20MsAudio().array());

        assertFalse(first.canProvide());
        assertTrue(second.canProvide());
        assertArrayEquals(new byte[]{1}, second.provide20MsAudio().array());
    }

    @Test
    void closeUnsubscribesAndClearsOnlyThatHandler() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler closed = broadcaster.subscribe();
        SpotifyAudioSendHandler active = broadcaster.subscribe();
        broadcaster.publish(new byte[]{1});

        closed.close();
        closed.close();

        assertEquals(1, broadcaster.subscriberCount());
        assertFalse(closed.canProvide());
        assertArrayEquals(new byte[]{1}, active.provide20MsAudio().array());
        broadcaster.publish(new byte[]{2});
        assertFalse(closed.canProvide());
        assertArrayEquals(new byte[]{2}, active.provide20MsAudio().array());
    }

    @Test
    void handlerReturnsNullWhenEmptyAndReportsPcm() {
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(10, ignored -> {});
        SpotifyAudioSendHandler handler = broadcaster.subscribe();

        assertFalse(handler.canProvide());
        assertNull(handler.provide20MsAudio());
        assertFalse(handler.isOpus());
    }

    @Test
    void dequeueCannotInterleaveWithOverflowReplacement() throws Exception {
        ControlledOfferQueue frames = new ControlledOfferQueue();
        frames.add(new byte[]{1});
        frames.blockNextRejectedOffer();
        AtomicLong drops = new AtomicLong();
        SpotifyAudioSendHandler handler = new SpotifyAudioSendHandler(
                new AudioFrameBroadcaster(1, ignored -> {}),
                frames
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publish = executor.submit(() -> handler.enqueue(new byte[]{2}, drops::addAndGet));
            assertTrue(frames.rejectedOffer.await(1, TimeUnit.SECONDS));

            CountDownLatch dequeueStarted = new CountDownLatch(1);
            Future<ByteBuffer> dequeue = executor.submit(() -> {
                frames.consumer.set(Thread.currentThread());
                dequeueStarted.countDown();
                return handler.provide20MsAudio();
            });
            assertTrue(dequeueStarted.await(1, TimeUnit.SECONDS));
            awaitBlockedOrPolled(frames.consumer.get(), frames.consumerPolled);
            assertEquals(1, frames.consumerPolled.getCount());

            frames.releaseRejectedOffer.countDown();

            publish.get(1, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{2}, dequeue.get(1, TimeUnit.SECONDS).array());
            assertEquals(1, drops.get());
        } finally {
            frames.releaseRejectedOffer.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeDuringPublishLeavesNoFrameAvailable() throws Exception {
        CountDownLatch overflowReported = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicLong drops = new AtomicLong();
        AudioFrameBroadcaster broadcaster = new AudioFrameBroadcaster(1, count -> {
            overflowReported.countDown();
            await(releasePublish);
            drops.addAndGet(count);
        });
        SpotifyAudioSendHandler handler = broadcaster.subscribe();
        broadcaster.publish(new byte[]{1});

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> publish = executor.submit(() -> broadcaster.publish(new byte[]{2}));
            assertTrue(overflowReported.await(1, TimeUnit.SECONDS));

            Future<?> close = executor.submit(handler::close);
            assertDoesNotThrow(() -> close.get(1, TimeUnit.SECONDS));
            assertEquals(0, broadcaster.subscriberCount());
            assertFalse(handler.canProvide());
            releasePublish.countDown();

            publish.get(1, TimeUnit.SECONDS);
        } finally {
            releasePublish.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, drops.get());
        assertEquals(0, broadcaster.subscriberCount());
        assertFalse(handler.canProvide());
        assertNull(handler.provide20MsAudio());
        broadcaster.publish(new byte[]{3});
        assertFalse(handler.canProvide());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void awaitBlockedOrPolled(Thread thread, CountDownLatch polled) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.BLOCKED
                && polled.getCount() != 0
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(thread.getState() == Thread.State.BLOCKED || polled.getCount() == 0);
    }

    private static final class ControlledOfferQueue extends ArrayBlockingQueue<byte[]> {

        private final CountDownLatch rejectedOffer = new CountDownLatch(1);
        private final CountDownLatch releaseRejectedOffer = new CountDownLatch(1);
        private final CountDownLatch consumerPolled = new CountDownLatch(1);
        private final AtomicBoolean blockRejectedOffer = new AtomicBoolean();
        private final AtomicReference<Thread> consumer = new AtomicReference<>();

        private ControlledOfferQueue() {
            super(1);
        }

        private void blockNextRejectedOffer() {
            blockRejectedOffer.set(true);
        }

        @Override
        public boolean offer(byte[] frame) {
            boolean offered = super.offer(frame);
            if (!offered && blockRejectedOffer.compareAndSet(true, false)) {
                rejectedOffer.countDown();
                await(releaseRejectedOffer);
            }
            return offered;
        }

        @Override
        public byte[] poll() {
            if (Thread.currentThread() == consumer.get()) {
                consumerPolled.countDown();
            }
            return super.poll();
        }
    }
}
