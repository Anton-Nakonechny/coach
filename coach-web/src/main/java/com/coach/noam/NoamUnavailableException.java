package com.coach.noam;

/**
 * Thrown when a call to noam fails — non-2xx response, connection failure, or an
 * interrupted request. Maps to HTTP 502 in {@code ApiExceptionHandler}, so a noam
 * outage surfaces as a clean upstream-error response rather than a 500 stack trace.
 */
public class NoamUnavailableException extends RuntimeException {

    public NoamUnavailableException(String message) {
        super(message);
    }

    public NoamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
