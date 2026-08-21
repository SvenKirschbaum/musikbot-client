package de.elite12.musikbot.clientv2.audio;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

public final class AudioFrameBroadcaster {

    private final int capacity;
    private final LongConsumer droppedFrames;
    private final Runnable emptyPoll;
    private final Set<SpotifyAudioSendHandler> subscribers = ConcurrentHashMap.newKeySet();

    public AudioFrameBroadcaster(int capacity, LongConsumer droppedFrames) {
        this(capacity, droppedFrames, () -> {});
    }

    public AudioFrameBroadcaster(int capacity, LongConsumer droppedFrames, Runnable emptyPoll) {
        this.capacity = capacity;
        this.droppedFrames = droppedFrames;
        this.emptyPoll = emptyPoll;
    }

    public SpotifyAudioSendHandler subscribe() {
        SpotifyAudioSendHandler handler = new SpotifyAudioSendHandler(
                this,
                new ArrayBlockingQueue<>(capacity),
                emptyPoll
        );
        subscribers.add(handler);
        return handler;
    }

    public void publish(byte[] frame) {
        for (SpotifyAudioSendHandler subscriber : subscribers) {
            subscriber.enqueue(frame, droppedFrames);
        }
    }

    public long reset() {
        long discarded = 0;
        for (SpotifyAudioSendHandler subscriber : subscribers) {
            discarded += subscriber.clear();
        }
        return discarded;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public int maxSubscriberQueueDepth() {
        int maximum = 0;
        for (SpotifyAudioSendHandler subscriber : subscribers) {
            maximum = Math.max(maximum, subscriber.queueDepth());
        }
        return maximum;
    }

    void unsubscribe(SpotifyAudioSendHandler handler) {
        subscribers.remove(handler);
        handler.clear();
    }
}
