package com.coach.web.dto;

import java.util.List;

/** Request body for {@code POST /api/spanish/words/seed}. */
public record WordSeedRequest(List<SeedItem> items) {}
