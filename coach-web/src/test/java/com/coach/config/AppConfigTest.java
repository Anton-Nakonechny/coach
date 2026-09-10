package com.coach.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link AppConfig#noam()} binds to a non-null {@code Noam} with blank
 * fields when a deployment's {@code application.yml} omits the whole
 * {@code coach.noam} block. Without {@code @DefaultValue} on the nested record
 * and its {@code noam} component, Spring's {@code ValueObjectBinder} leaves
 * {@code noam()} {@code null} in that case, and {@code NoamController} calling
 * {@code appConfig.noam().baseUrl()} would NPE into a 500 instead of degrading
 * to blanks (the frontend treats a blank profile id as "noam unavailable").
 */
class AppConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void noamDefaultsToBlanksWhenConfigBlockAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var noam = context.getBean(AppConfig.class).noam();
            assertThat(noam).isNotNull();
            assertThat(noam.baseUrl()).isEmpty();
            assertThat(noam.profileId()).isEmpty();
            assertThat(noam.userId()).isEmpty();
        });
    }

    @EnableConfigurationProperties(AppConfig.class)
    static class Config { }
}
