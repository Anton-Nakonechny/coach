package com.coach.anthropic;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MimeType {
    IMAGE_JPEG("image/jpeg"),
    IMAGE_PNG("image/png"),
    IMAGE_GIF("image/gif"),
    IMAGE_WEBP("image/webp"),
    APPLICATION_PDF("application/pdf"),
    TEXT_PLAIN("text/plain"),
    TEXT_MARKDOWN("text/markdown"),
    TEXT_CSV("text/csv"),
    APPLICATION_JSON("application/json"),
    APPLICATION_XML("application/xml");

    private final String value;

    MimeType(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static MimeType fromValue(String value) {
        for (MimeType m : values()) {
            if (m.value.equals(value)) return m;
        }
        throw new IllegalArgumentException("Unknown MIME type: " + value);
    }
}
