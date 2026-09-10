package com.coach.web.dto;

/**
 * One client-supplied word for {@code POST /api/spanish/words/seed}. {@code lexemeId} is
 * the noam lexeme this word came from; blank/absent means no grade will be reported for it.
 */
public record SeedItem(String lexemeId, String spanish, String english) {}
