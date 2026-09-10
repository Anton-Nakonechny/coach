package com.coach.web;

import com.coach.coach.InvalidRequestException;
import com.coach.config.AppConfig;
import com.coach.noam.NoamGateway;
import com.coach.web.dto.LexemeStatesRequest;
import com.coach.web.dto.NoamConfigResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Browser-facing configuration for the noam vocabulary platform, plus the
 * write-back route for marking lexeme states. The noam user id stays server-side
 * (used only for write-backs, via {@link NoamGateway}) and is never returned here.
 */
@RestController
@RequestMapping("/api/noam")
public class NoamController {

    private static final Set<String> VALID_STATES = Set.of("KNOWN", "IGNORED", "NEW");

    private final AppConfig config;
    private final NoamGateway noamGateway;

    public NoamController(AppConfig config, NoamGateway noamGateway) {
        this.config = config;
        this.noamGateway = noamGateway;
    }

    @GetMapping("/config")
    public NoamConfigResponse config() {
        var noam = config.noam();
        return new NoamConfigResponse(noam.baseUrl(), noam.profileId());
    }

    @PostMapping("/lexeme-states")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lexemeStates(@RequestBody LexemeStatesRequest request) {
        if (request.state() == null || !VALID_STATES.contains(request.state()))
            throw new InvalidRequestException("state must be one of KNOWN, IGNORED, NEW");
        var lexemeIds = request.lexemeIds();
        if (lexemeIds == null || lexemeIds.isEmpty()) return;
        noamGateway.setLexemeStates(lexemeIds, request.state());
    }
}
