package com.coach.web;

import com.anthropic.errors.AnthropicException;
import com.coach.attach.UploadException;
import com.coach.coach.InvalidRequestException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Translates application exceptions to JSON {@code {"message": ...}} error bodies
 * with idiomatic Spring status codes:
 * <ul>
 *   <li>bad request body (Bean Validation, unknown model at deserialization) → 400</li>
 *   <li>conversation not found, or an unmapped route → 404</li>
 *   <li>any other failure (e.g. upstream Anthropic error) → 500</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(Map.of("message", message == null ? status.getReasonPhrase() : message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("Invalid request");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
    }

    @ExceptionHandler({InvalidRequestException.class, UploadException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return body(HttpStatus.BAD_REQUEST, "upload exceeds the maximum allowed size");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        return body(HttpStatus.BAD_REQUEST, "couldn't read the uploaded files");
    }

    @ExceptionHandler({UncheckedIOException.class, IOException.class})
    public ResponseEntity<Map<String, Object>> handleIo(Exception ex) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
    }

    @ExceptionHandler(AnthropicException.class)
    public ResponseEntity<Map<String, Object>> handleAnthropicSdk(AnthropicException ex) {
        return body(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ConversationNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** An unmapped route (no controller, no static resource) → 404, not the catch-all 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }
}
