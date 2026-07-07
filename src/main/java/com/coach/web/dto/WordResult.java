package com.coach.web.dto;

/**
 * One graded entry in a check response: english clue, revealed Spanish, correctness, and
 * whether the full hint was used. Tri-state (client-derived): green = correct && !fullHint,
 * yellow = correct && fullHint, red = !correct.
 */
public record WordResult(String english, String spanish, boolean correct, boolean fullHint) {}
