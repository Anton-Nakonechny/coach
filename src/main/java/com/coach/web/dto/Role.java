package com.coach.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Role {
    USER,
    ASSISTANT;

    @JsonValue
    public String value() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Role from(String v) {
        if (v == null) return null;
        for (Role r : values()) {
            if (r.name().toLowerCase().equals(v)) return r;
        }
        return null;
    }
}
