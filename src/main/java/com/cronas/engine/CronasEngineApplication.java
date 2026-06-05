package com.cronas.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Enable scheduling for the application 
public class CronasEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(CronasEngineApplication.class, args);
    }
}