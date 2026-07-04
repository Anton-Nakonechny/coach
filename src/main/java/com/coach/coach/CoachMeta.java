package com.coach.coach;

import com.coach.model.CoachType;

/** Per-conversation coach selection, persisted as a {@code <id>.meta.json} sidecar. */
public record CoachMeta(CoachType coachType, String promptFile, String topic) {

    /** Sidebar label: Spanish shows the topic; COO shows the scenario filename. */
    public String preview() {
        if (coachType == CoachType.SPANISH)
            return coachType.shortLabel() + " · " + topic;
        String scenario = promptFile
                .replaceFirst("(?i)\\.md$", "")
                .replaceFirst("^\\d+-", "")
                .replace('-', ' ');
        return coachType.shortLabel() + " · " + scenario;
    }
}
