package com.coach.web.dto;

/** One graded entry in a check response: english clue, revealed Spanish, and correctness flag. */
public record WordResult(String english, String spanish, boolean correct) {}
