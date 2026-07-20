package com.coach;

import com.coach.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the Coach chat client. Wraps the Anthropic Messages API and
 * serves the vanilla-JS frontend from {@code src/main/resources/static}.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class CoachWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoachWebApplication.class, args);
    }
}
