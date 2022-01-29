package de.elite12.musikbot.clientv2.util;

import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.nio.ByteBuffer;

public class AudioSource implements AudioSendHandler {

    private static final int fragmentSize = (int) (AudioSendHandler.INPUT_FORMAT.getFrameSize() * Math.ceil(AudioSendHandler.INPUT_FORMAT.getFrameRate() / 50));
    private final TargetDataLine targetDataLine;

    public AudioSource() throws LineUnavailableException {
        this.targetDataLine = AudioSystem.getTargetDataLine(AudioSendHandler.INPUT_FORMAT);
        this.targetDataLine.open();
        this.targetDataLine.start();
    }

    @Override
    public boolean canProvide() {
        return this.targetDataLine.available() > AudioSource.fragmentSize;
    }

    @Nullable
    @Override
    public ByteBuffer provide20MsAudio() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(AudioSource.fragmentSize);
        this.targetDataLine.read(byteBuffer.array(), 0, AudioSource.fragmentSize);
        return byteBuffer;
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    public void destroy() {
        this.targetDataLine.stop();
        this.targetDataLine.close();
    }
}
