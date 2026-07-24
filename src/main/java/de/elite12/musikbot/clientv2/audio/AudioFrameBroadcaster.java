package de.elite12.musikbot.clientv2.audio;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

public final class AudioFrameBroadcaster {

    private final int capacity;
    private final LongConsumer droppedFrames;
    private final Set<SpotifyAudioSendHandler> subscribers = ConcurrentHashMap.newKeySet();

    public AudioFrameBroadcaster(int capacity, LongConsumer droppedFrames) {
        this.capacity = capacity;
        this.droppedFrames = droppedFrames;
    }

    public SpotifyAudioSendHandler subscribe() {
        SpotifyAudioSendHandler handler = new SpotifyAudioSendHandler(
                this,
                new ArrayBlockingQueue<>(capacity)
        );
        subscribers.add(handler);
        return handler;
    }

    public void publish(byte[] frame) {
        for (SpotifyAudioSendHandler subscriber : subscribers) {
            subscriber.enqueue(frame, droppedFrames);
        }
    }

    public void reset() {
        for (SpotifyAudioSendHandler subscriber : subscribers) {
            subscriber.clear();
        }
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
