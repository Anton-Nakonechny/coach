package com.coach.word;

/**
 * An english-translation paired with its original Spanish token, from the 字 word quiz.
 * {@code lexemeId} is the noam lexeme this word came from, or null for a hand-typed list.
 */
public record WordPair(String english, String spanishOriginal, String lexemeId) { }
