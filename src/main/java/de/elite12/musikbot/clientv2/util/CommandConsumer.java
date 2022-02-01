package de.elite12.musikbot.clientv2.util;

import de.elite12.musikbot.shared.clientDTO.ClientDTO;
import org.springframework.messaging.simp.stomp.StompSession;

import java.util.concurrent.BlockingQueue;

public class CommandConsumer implements Runnable {


    private final BlockingQueue<ClientDTO> commandQueue;
    private final StompSession session;

    public CommandConsumer(BlockingQueue<ClientDTO> commandQueue, StompSession session) {
        this.commandQueue = commandQueue;
        this.session = session;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ClientDTO command = this.commandQueue.take();
                this.session.send("/musikbot/client", command);
            }
        } catch (InterruptedException ignored) {
        }
    }
}
