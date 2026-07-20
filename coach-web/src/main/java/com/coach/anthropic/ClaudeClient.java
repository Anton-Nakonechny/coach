package com.coach.anthropic;

import com.coach.config.AppConfig;
import com.coach.model.ModelInfo;
import com.coach.model.ModelKey;
import com.coach.model.ModelsConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Request shaping for Anthropic — Java port of {@code claude_client.py}.
 *
 * <p>Implements the "ignored if not applicable" rule: {@code thinking:{type:adaptive}}
 * and {@code output_config:{effort}} are only included for models whose flags allow
 * them. These go in {@code extraBody} (raw JSON), mirroring how the Python client
 * uses {@code extra_body} to stay decoupled from SDK typing. Returns only the
 * concatenated {@code text} blocks (thinking blocks dropped).
 */
@Component
public class ClaudeClient {

    private final SdkAnthropicGateway gateway;
    private final ModelsConfig models;
    private final AppConfig config;

    public ClaudeClient(SdkAnthropicGateway gateway, ModelsConfig models, AppConfig config) {
        this.gateway = gateway;
        this.models = models;
        this.config = config;
    }

    /**
     * Shape and send the conversation, returning the assistant's text.
     *
     * @param modelKey one of the keys in {@link ModelsConfig}.
     * @param messages the conversation history (text + attachments) for the stateless API.
     * @param effort   effort level; ignored if the model doesn't support it.
     * @param system   system prompt; null when the conversation has no coach.
     */
    public String generate(ModelKey modelKey, List<ApiMessage> messages, String effort, String system) {
        ModelInfo cfg = models.byKey(modelKey);
        if (cfg == null) {
            throw new IllegalArgumentException("Unknown model: " + modelKey);
        }

        // extraBody is merged straight into the request JSON, so neither field is
        // sent when the model doesn't support it (an empty map → nothing added).
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (cfg.adaptiveThinking()) {
            extraBody.put("thinking", Map.of("type", "adaptive"));
        }
        if (cfg.supportsEffort() && effort != null && models.effortLevels().contains(effort)) {
            extraBody.put("output_config", Map.of("effort", effort));
        }

        List<AnthropicBlock> blocks =
                gateway.createMessage(cfg.id(), config.maxTokens(), system, messages, extraBody);

        return blocks.stream()
                .filter(b -> AnthropicBlock.TYPE_TEXT.equals(b.type()) && b.text() != null)
                .map(AnthropicBlock::text)
                .collect(Collectors.joining());
    }
}
