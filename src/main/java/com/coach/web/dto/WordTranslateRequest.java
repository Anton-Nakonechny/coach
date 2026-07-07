package com.coach.web.dto;

import com.coach.model.ModelKey;

/** Request body for {@code POST /api/spanish/words/translate}. */
public record WordTranslateRequest(String words, ModelKey model, String effort) {}
