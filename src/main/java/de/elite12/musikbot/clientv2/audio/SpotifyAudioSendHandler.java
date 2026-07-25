package de.elite12.musikbot.clientv2.audio;

import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

public final class SpotifyAudioSendHandler implements AudioSendHandler, AutoCloseable {

    private final AudioFrameBroadcaster owner;
    private final BlockingQueue<byte[]> frames;
    private final Runnable emptyPoll;
    private final AtomicBoolean closed = new AtomicBoolean();

    SpotifyAudioSendHandler(AudioFrameBroadcaster owner, BlockingQueue<byte[]> frames) {
        this(owner, frames, () -> {});
    }

    SpotifyAudioSendHandler(AudioFrameBroadcaster owner, BlockingQueue<byte[]> frames, Runnable emptyPoll) {
        this.owner = owner;
        this.frames = frames;
        this.emptyPoll = emptyPoll;
    }

    @Override
    public boolean canProvide() {
        boolean available = !frames.isEmpty();
        if (!available) {
            emptyPoll.run();
        }
        return available;
    }

    @Nullable
    @Override
    public synchronized ByteBuffer provide20MsAudio() {
        byte[] frame = frames.poll();
        return frame == null ? null : ByteBuffer.wrap(frame);
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            owner.unsubscribe(this);
        }
    }

    void enqueue(byte[] frame, LongConsumer droppedFrames) {
        boolean dropped = false;
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            if (!frames.offer(frame)) {
                dropped = frames.poll() != null;
                frames.offer(frame);
            }
        }
        if (dropped) {
            droppedFrames.accept(1);
        }
    }

    synchronized int clear() {
        int discarded = frames.size();
        frames.clear();
        return discarded;
    }

    synchronized int queueDepth() {
        return frames.size();
    }
}
