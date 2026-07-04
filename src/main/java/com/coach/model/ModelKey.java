package com.coach.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ModelKey {
    SONNET_4_6("sonnet-4-6"),
    OPUS_4_8("opus-4-8"),
    OPUS_4_7("opus-4-7"),
    HAIKU_4_5("haiku-4-5");

    private final String value;

    ModelKey(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    @JsonCreator
    public static ModelKey fromValue(String value) {
        for (ModelKey k : values()) {
            if (k.value.equals(value)) return k;
        }
        throw new IllegalArgumentException("Unknown model key: " + value);
    }
}
