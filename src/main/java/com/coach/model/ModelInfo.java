package com.coach.model;

/**
 * One selectable model's capability entry — the Java analog of an item in the
 * Python {@code MODELS} list.
 *
 * <p>{@code supportsEffort} / {@code adaptiveThinking} encode the "ignored if not
 * applicable" rule: Haiku rejects the effort parameter and is not configured for
 * adaptive thinking, so neither is sent for it.
 *
 * @param key              stable UI/request key (e.g. {@code "sonnet-5"}).
 * @param id               Anthropic model id (e.g. {@code "claude-sonnet-5"}).
 * @param label            human-readable label.
 * @param supportsEffort   whether {@code output_config.effort} may be sent.
 * @param adaptiveThinking whether {@code thinking:{type:adaptive}} may be sent.
 */
public record ModelInfo(
        ModelKey key,
        String id,
        String label,
        boolean supportsEffort,
        boolean adaptiveThinking
) { }
