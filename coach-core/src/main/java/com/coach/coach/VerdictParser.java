package com.coach.coach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code ===EVALUACIÓN===} verdict block a 語 tutor reply may end with,
 * separating the graded hint/verdict lines from the prose the user actually reads.
 * Tolerant, never throws: a missing or malformed block degrades to "no verdicts",
 * never to a broken reply.
 */
public class VerdictParser {

    private static final String MARKER = "===EVALUACIÓN===";
    private static final Pattern LINE = Pattern.compile("^\\((.+?)\\)\\s+(.+)$");

    /**
     * Stands in for the prose when a reply is ONLY the verdict block. Persisting/returning
     * an empty string instead would later resend a history message with zero content blocks,
     * which the Anthropic Messages API rejects on every subsequent turn — bricking the chat.
     */
    private static final String NO_PROSE_PLACEHOLDER = "Revisión completada.";

    public record VerdictItem(String hint, String grade) { }

    public record Verdicts(String strippedAnswer, List<VerdictItem> items) { }

    private VerdictParser() { }

    public static Verdicts parse(String answer) {
        String[] lines = answer.split("\\R", -1);
        int markerIdx = -1;
        for (int i = 0; i < lines.length; i++)
            if (lines[i].trim().equals(MARKER)) markerIdx = i;
        if (markerIdx == -1) return new Verdicts(answer, List.of());

        String stripped = String.join("\n", Arrays.copyOfRange(lines, 0, markerIdx)).stripTrailing();
        if (stripped.isEmpty()) stripped = NO_PROSE_PLACEHOLDER;
        List<VerdictItem> items = new ArrayList<>();
        for (int i = markerIdx + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String grade = gradeOf(m.group(2).trim());
            if (grade == null) continue;
            items.add(new VerdictItem(m.group(1), grade));
        }
        return new Verdicts(stripped, items);
    }

    private static String gradeOf(String token) {
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "CORRECTO" -> "GOOD";
            case "PARCIAL" -> "HARD";
            case "INCORRECTO" -> "AGAIN";
            default -> null;
        };
    }
}
