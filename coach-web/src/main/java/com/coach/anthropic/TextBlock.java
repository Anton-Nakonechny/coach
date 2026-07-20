package com.coach.anthropic;

/** A run of plain text within a turn's content. */
public record TextBlock(String text) implements ContentBlock { }
