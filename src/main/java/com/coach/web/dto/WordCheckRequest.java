package com.coach.web.dto;

import java.util.List;

/** Request body for {@code POST /api/spanish/words/check}. */
public record WordCheckRequest(String setId, List<String> answers) {}
