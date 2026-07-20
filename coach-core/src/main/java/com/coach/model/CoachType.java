package com.coach.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CoachType {
    NONE("none"),
    CHIEF_OPERATING_OFFICER("chief-operating-officer"),
    SPANISH("spanish"),
    CLAUDE_ARCHITECT("claude-architect");

    private final String value;

    CoachType(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }

    /** Short human label for sidebar previews. */
    public String shortLabel() {
        return switch (this) {
            case NONE -> "";
            case CHIEF_OPERATING_OFFICER -> "COO";
            case SPANISH -> "Español";
            case CLAUDE_ARCHITECT -> "Claude";
        };
    }

    @JsonCreator
    public static CoachType fromValue(String value) {
        for (CoachType t : values())
            if (t.value.equals(value)) return t;
        throw new IllegalArgumentException("Unknown coach type: " + value);
    }
}
