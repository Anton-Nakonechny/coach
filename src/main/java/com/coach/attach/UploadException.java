package com.coach.attach;

/**
 * An attachment validation failure (unsupported type, oversize, too many files,
 * zip-slip, nested zip, decompression bomb). Mapped to HTTP 400 by the web layer.
 */
public class UploadException extends RuntimeException {

    public UploadException(String message) {
        super(message);
    }
}
