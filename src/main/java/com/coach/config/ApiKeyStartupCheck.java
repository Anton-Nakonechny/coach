package com.coach.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Warns at startup, on every launch path, when {@code ANTHROPIC_API_KEY} is absent.
 * The app deliberately stays usable without a key (the static UI and {@code /api/models}
 * work); only {@code /api/chat} needs one, so this is a warning, not a hard failure.
 */
@Component
class ApiKeyStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyStartupCheck.class);

    private final AppConfig config;

    ApiKeyStartupCheck(AppConfig config) {
        this.config = config;
    }

    @PostConstruct
    void warnIfKeyMissing() {
        if (config.anthropicApiKey().isBlank())
            log.warn("ANTHROPIC_API_KEY is not set — /api/chat will fail, "
                    + "but the UI and /api/models still work.");
    }
}
