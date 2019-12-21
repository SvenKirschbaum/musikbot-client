package de.elite12.musikbot.clientv2;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Clientv2Application implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(Clientv2Application.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        while(true) {
            System.out.println("Application running");
            Thread.sleep(2000);
        }
    }
}
