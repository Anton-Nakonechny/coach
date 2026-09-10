package com.coach.web.dto;

/**
 * Response body for {@code GET /api/noam/config}. Deliberately excludes the noam
 * user id, which is used only for server-side write-backs.
 */
public record NoamConfigResponse(String baseUrl, String profileId) { }
