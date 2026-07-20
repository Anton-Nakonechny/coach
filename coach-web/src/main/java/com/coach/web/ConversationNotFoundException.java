package com.coach.web;

/** Raised when a conversation id does not resolve to a stored conversation → 404. */
public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(String message) {
        super(message);
    }
}
