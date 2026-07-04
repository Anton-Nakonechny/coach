package com.coach.coach;

import com.coach.web.dto.SentenceItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Spanish tutor reply into ordered hint+sentence pairs.
 * All-or-nothing: returns empty if any non-blank line fails the format.
 */
public class SentenceParser {

    private static final Pattern LINE = Pattern.compile("^\\((.+?)\\)\\s+(.+)$");

    public static List<SentenceItem> parse(String content) {
        List<SentenceItem> result = new ArrayList<>();
        for (String raw : content.split("\\R", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) return List.of();
            result.add(new SentenceItem(m.group(1), m.group(2)));
        }
        return result;
    }
}
