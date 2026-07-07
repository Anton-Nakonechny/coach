package com.coach.web.dto;

/** One item in a translate response: the english meaning and a masked hint of the Spanish. */
public record WordPrompt(String english, String hint) {}
