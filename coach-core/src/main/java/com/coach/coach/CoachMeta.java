package com.coach.coach;

import com.coach.model.CoachType;

/** Per-conversation coach selection, persisted as a {@code <id>.meta.json} sidecar. */
public record CoachMeta(CoachType coachType, String promptFile, String topic) {

    /**
     * Reserved System Design topic id for the Fundamentals drill. The drill is not a case
     * file, so its meta carries this as {@code topic} with a null {@code promptFile}.
     */
    public static final String SYSTEM_DESIGN_DRILL_TOPIC = "Fundamentals drill";

    /** Whether this is a System Design Fundamentals-drill chat (as opposed to a case interview). */
    public boolean isSystemDesignDrill() {
        return coachType == CoachType.SYSTEM_DESIGN && SYSTEM_DESIGN_DRILL_TOPIC.equals(topic);
    }

    /** Sidebar label: Spanish shows the topic; COO shows the scenario filename; Claude Architect shows the stem; System Design shows the case or the drill. */
    public String preview() {
        String stem = promptFile != null ? promptFile.replaceFirst("(?i)\\.md$", "") : "";
        String suffix = switch (coachType) {
            // A topic-less Spanish chat (字 word-list practice) has no topic to show.
            case SPANISH -> topic == null || topic.isBlank() ? "Vocabulario" : topic;
            case CLAUDE_ARCHITECT -> stem;
            // The numeric sort prefix (e.g. "01 ") must never reach the screen.
            case JAVA -> stem.replaceFirst("^\\d{2} ", "");
            // A Fundamentals-drill chat has no case file: its topic is the label.
            case SYSTEM_DESIGN -> isSystemDesignDrill() ? topic : stem.replaceFirst("^\\d{2} ", "");
            default -> stem.replaceFirst("^\\d+-", "").replace('-', ' ');
        };
        return coachType.shortLabel() + " · " + suffix;
    }
}
