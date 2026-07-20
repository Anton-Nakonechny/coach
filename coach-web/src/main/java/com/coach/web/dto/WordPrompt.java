package com.coach.web.dto;

/**
 * One item in a translate response: the english meaning, a masked hint, and the full
 * Spanish word (revealed client-side when the user clicks the hint icon).
 */
public record WordPrompt(String english, String hint, String spanish) {}
