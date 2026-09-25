package com.coach.noam;

/**
 * A word to register in noam via {@link NoamGateway#createLexemes}. {@code translation}
 * may be null or blank — noam backfills the gloss on its own.
 */
public record LexemeDraft(String surface, String translation) { }
