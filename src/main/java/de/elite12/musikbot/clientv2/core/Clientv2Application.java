package de.elite12.musikbot.clientv2.core;

import de.elite12.musikbot.clientv2.services.ConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication
@ComponentScan("de.elite12.musikbot.clientv2")
@EnableScheduling
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

    @Configuration
    public static class ThreadConfig {
        @Bean
        @Primary
        public TaskExecutor threadPoolTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(8);
            executor.setThreadNamePrefix("default_task_executor_thread");
            executor.initialize();
            return executor;
        }
    }
}
