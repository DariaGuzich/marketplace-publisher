package com.example.publisher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
public class PublisherApplication {

    public static void main(String[] args) {
        SpringApplication.run(PublisherApplication.class, args);
    }

    /** Расписание включено, если publisher.scheduling-enabled=true (в тестах выключено). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "publisher.scheduling-enabled", havingValue = "true")
    static class SchedulingConfig {
    }
}
