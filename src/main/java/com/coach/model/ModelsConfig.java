package com.coach.model;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Source of truth for the selectable models — Java port of {@code models_config.py}.
 *
 * <p>{@link #MODELS} is an explicit, ordered list: its order is the render order
 * the frontend uses, and {@link #DEFAULT_MODEL} is selected on load.
 * {@code supportsEffort} / {@code adaptiveThinking} encode the "ignored if not
 * applicable" rule (Haiku rejects the effort parameter and is not configured for
 * adaptive thinking, so neither is sent for it). {@link #BY_KEY} is derived for
 * O(1) lookup. Adding/removing/reordering a model is a single-file change here.
 */
@Component
public class ModelsConfig {

    private static final List<ModelInfo> MODELS = List.of(
            new ModelInfo(ModelKey.SONNET_5, "claude-sonnet-5", "Sonnet 5", true, true),
            new ModelInfo(ModelKey.OPUS_4_8, "claude-opus-4-8", "Opus 4.8", true, true),
            new ModelInfo(ModelKey.OPUS_4_7, "claude-opus-4-7", "Opus 4.7", true, true),
            new ModelInfo(ModelKey.HAIKU_4_5, "claude-haiku-4-5", "Haiku 4.5", false, false)
    );

    private static final Map<ModelKey, ModelInfo> BY_KEY =
            MODELS.stream().collect(Collectors.toMap(ModelInfo::key, Function.identity()));

    // `xhigh` is intentionally omitted (Opus-only). This single static list is
    // valid on every effort-capable model above, so the dropdown never causes a 400.
    private static final List<String> EFFORT_LEVELS = List.of("low", "medium", "high", "max");

    private static final ModelKey DEFAULT_MODEL = ModelKey.SONNET_5;
    private static final String DEFAULT_EFFORT = "medium";

    public List<ModelInfo> models() {
        return MODELS;
    }

    public ModelInfo byKey(ModelKey key) {
        return BY_KEY.get(key);
    }

    public List<String> effortLevels() {
        return EFFORT_LEVELS;
    }

    public ModelKey defaultModel() {
        return DEFAULT_MODEL;
    }

    public String defaultEffort() {
        return DEFAULT_EFFORT;
    }
}
