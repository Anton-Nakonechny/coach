package com.coach.web.dto;

import java.util.List;

/** Response body for {@code POST /api/spanish/words/check}. */
public record WordCheckResponse(List<WordResult> results) {}
