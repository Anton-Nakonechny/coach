package com.coach.web;

import com.coach.config.AppConfig;
import com.coach.web.dto.NoamConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing configuration for the noam vocabulary platform. The noam user id
 * stays server-side (used only for write-backs) and is never returned here.
 */
@RestController
@RequestMapping("/api/noam")
public class NoamController {

    private final AppConfig config;

    public NoamController(AppConfig config) {
        this.config = config;
    }

    @GetMapping("/config")
    public NoamConfigResponse config() {
        var noam = config.noam();
        return new NoamConfigResponse(noam.baseUrl(), noam.profileId());
    }
}
