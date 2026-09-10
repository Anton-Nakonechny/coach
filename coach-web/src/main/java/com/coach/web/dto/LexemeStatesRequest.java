package com.coach.web.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/noam/lexeme-states}. An empty/absent
 * {@code lexemeIds} is a no-op, not an error — the UI fires this even when the
 * user marked nothing.
 */
public record LexemeStatesRequest(List<String> lexemeIds, String state) { }
