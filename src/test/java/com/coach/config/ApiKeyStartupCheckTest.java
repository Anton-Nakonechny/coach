package com.coach.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the startup warning that surfaces a missing {@code ANTHROPIC_API_KEY} on
 * every launch path. Uses {@link ApplicationContextRunner} (fresh context created
 * inside each test method, within {@link OutputCaptureExtension}'s capture window) so
 * the startup log is reliably observed.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyStartupCheckTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void warnsWhenApiKeyBlank(CapturedOutput output) {
        runner.withPropertyValues("coach.anthropic-api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).contains("ANTHROPIC_API_KEY is not set");
                });
    }

    @Test
    void silentWhenApiKeyPresent(CapturedOutput output) {
        runner.withPropertyValues("coach.anthropic-api-key=sk-test-abc")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).doesNotContain("ANTHROPIC_API_KEY is not set");
                });
    }

    @EnableConfigurationProperties(AppConfig.class)
    @Import(ApiKeyStartupCheck.class)
    static class Config { }
}
