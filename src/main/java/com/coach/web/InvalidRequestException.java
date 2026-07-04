package com.coach.web;

/**
 * A client-input error that maps to HTTP 400 with a {@code {"message": ...}} body —
 * used for a blank message with no attachments, and for attachment validation failures
 * (unsupported type, oversize, zip-slip, nested zip, decompression bomb).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
