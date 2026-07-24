package de.elite12.musikbot.clientv2.audio;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
