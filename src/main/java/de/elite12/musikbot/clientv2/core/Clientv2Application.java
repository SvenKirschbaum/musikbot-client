package de.elite12.musikbot.clientv2.core;

import de.elite12.musikbot.clientv2.services.ConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("de.elite12.musikbot.clientv2")
public class Clientv2Application implements CommandLineRunner {

    @Autowired
    private ConnectionService connectionService;

    public static void main(String[] args) {
        SpringApplication.run(Clientv2Application.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        Thread.currentThread().join();
    }
}
