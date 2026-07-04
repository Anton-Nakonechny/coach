package com.coach.anthropic;

/**
 * A single content block from an Anthropic response (e.g. {@code text} or
 * {@code thinking}) — a tiny domain type that keeps SDK response types out of
 * {@link ClaudeClient}, so the text-joining rule stays unit-testable against a
 * fake gateway (the analog of {@code FakeContentBlock} in the Python suite).
 */
public record AnthropicBlock(String type, String text) {

    /** The {@code text} block type — the only kind {@link ClaudeClient} keeps. */
    public static final String TYPE_TEXT = "text";
}
