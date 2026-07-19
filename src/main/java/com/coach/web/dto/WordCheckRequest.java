package com.coach.web.dto;

import java.util.List;

/**
 * Request body for {@code POST /api/spanish/words/check}. {@code hintsUsed} is a
 * per-index flag (aligned with {@code answers}) marking words whose full hint was
 * revealed; absent/short entries default to false.
 */
public record WordCheckRequest(String setId, List<String> answers, List<Boolean> hintsUsed) {}
