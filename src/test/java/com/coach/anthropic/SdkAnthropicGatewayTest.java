package com.coach.anthropic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the request params built by {@link SdkAnthropicGateway} — this sits
 * beyond the {@code ChatApiTest} mock boundary, so the built params are inspected
 * directly instead of through HTTP.
 */
class SdkAnthropicGatewayTest {

    @Test
    void buildParams_withSystemPrompt_addsCacheableSystemBlock() {
        var params = SdkAnthropicGateway.buildParams("claude-sonnet-4-6", 100, "SYSTEM PROMPT",
                List.of(new ApiMessage("user", List.of(new TextBlock("hi")))), Map.of());

        var blocks = params.system().orElseThrow().asBetaTextBlockParams();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).text()).isEqualTo("SYSTEM PROMPT");
        assertThat(blocks.get(0).cacheControl()).isPresent();
    }

    @Test
    void buildParams_withBlankSystemPrompt_omitsSystem() {
        var params = SdkAnthropicGateway.buildParams("claude-sonnet-4-6", 100, " ",
                List.of(new ApiMessage("user", List.of(new TextBlock("hi")))), Map.of());

        assertThat(params.system()).isEmpty();
    }
}
