package com.coach.coach;

import com.coach.dto.QuizOption;
import com.coach.dto.QuizQuestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a model reply into a {@link QuizQuestion} when it matches the mandatory
 * question format exactly (D4): ≥5 non-blank trimmed lines, last four are
 * {@code A) … D)} in order, no other option-like lines, at least one stem line.
 * Returns {@code null} on any violation — never throws.
 */
public class QuestionParser {

    private static final Pattern OPTION_LINE = Pattern.compile("^([A-D])\\)\\s+(.+)$");
    private static final String[] EXPECTED_LETTERS = {"A", "B", "C", "D"};

    private QuestionParser() { }

    public static QuizQuestion parse(String content) {
        if (content == null) return null;
        var lines = content.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toList();
        if (lines.size() < 5) return null;

        // Last four must be exactly A, B, C, D option lines in order.
        var optionLines = lines.subList(lines.size() - 4, lines.size());
        var options = new ArrayList<QuizOption>(4);
        for (int i = 0; i < 4; i++) {
            var m = OPTION_LINE.matcher(optionLines.get(i));
            if (!m.matches()) return null;
            if (!m.group(1).equals(EXPECTED_LETTERS[i])) return null;
            options.add(new QuizOption(m.group(1), m.group(2)));
        }

        // No other line in the reply may look like an option line.
        var stemLines = lines.subList(0, lines.size() - 4);
        if (stemLines.isEmpty()) return null;
        for (var line : stemLines)
            if (OPTION_LINE.matcher(line).matches()) return null;

        // Stem must contain a question mark — distinguishes quiz questions from feedback
        // that happens to list A-D explanations at the end.
        if (stemLines.stream().noneMatch(l -> l.contains("?"))) return null;

        return new QuizQuestion(String.join("\n", stemLines), List.copyOf(options));
    }
}
