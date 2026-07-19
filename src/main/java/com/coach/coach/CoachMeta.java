package com.coach.coach;

import com.coach.model.CoachType;

/** Per-conversation coach selection, persisted as a {@code <id>.meta.json} sidecar. */
public record CoachMeta(CoachType coachType, String promptFile, String topic) {

    /** Sidebar label: Spanish shows the topic; COO shows the scenario filename; Claude Architect shows the stem. */
    public String preview() {
        String stem = promptFile != null ? promptFile.replaceFirst("(?i)\\.md$", "") : "";
        String suffix = switch (coachType) {
            case SPANISH -> topic;
            case CLAUDE_ARCHITECT -> stem;
            default -> stem.replaceFirst("^\\d+-", "").replace('-', ' ');
        };
        return coachType.shortLabel() + " · " + suffix;
    }
}
