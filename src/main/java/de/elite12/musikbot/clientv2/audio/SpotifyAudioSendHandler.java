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
    private final AtomicBoolean closed = new AtomicBoolean();

    SpotifyAudioSendHandler(AudioFrameBroadcaster owner, BlockingQueue<byte[]> frames) {
        this.owner = owner;
        this.frames = frames;
    }

    @Override
    public boolean canProvide() {
        return !frames.isEmpty();
    }

    @Nullable
    @Override
    public ByteBuffer provide20MsAudio() {
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

    synchronized void enqueue(byte[] frame, LongConsumer droppedFrames) {
        if (closed.get()) {
            return;
        }
        if (!frames.offer(frame)) {
            frames.poll();
            frames.offer(frame);
            droppedFrames.accept(1);
        }
    }

    synchronized void clear() {
        frames.clear();
    }
}
