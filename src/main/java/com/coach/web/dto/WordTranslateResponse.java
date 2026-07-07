package com.coach.web.dto;

import java.util.List;

/** Response body for {@code POST /api/spanish/words/translate}. */
public record WordTranslateResponse(String setId, List<WordPrompt> items) {}
