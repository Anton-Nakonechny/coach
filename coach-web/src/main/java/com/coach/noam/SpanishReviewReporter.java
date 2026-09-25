package com.coach.noam;

import com.coach.coach.Text;
import com.coach.coach.VerdictParser.VerdictItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports 語 tutor verdicts (parsed by {@code VerdictParser}) to noam: dedupe within the
 * turn (worst grade wins per word), register any not-yet-known words, then post one
 * review per word. A noam problem must never fail the chat turn — failures are logged
 * and swallowed per word, mirroring {@code SpanishWordController.reportReviews}.
 */
@Component
public class SpanishReviewReporter {

    private static final Logger log = LoggerFactory.getLogger(SpanishReviewReporter.class);

    /** Grade severity, worst first — a repeated word keeps whichever grade sorts earlier. */
    private static final List<String> GRADE_SEVERITY = List.of("AGAIN", "HARD", "GOOD");

    private record WordVerdict(String surface, String grade) { }

    private final NoamGateway noamGateway;

    public SpanishReviewReporter(NoamGateway noamGateway) {
        this.noamGateway = noamGateway;
    }

    public void report(List<VerdictItem> items) {
        Map<String, WordVerdict> byWord = new LinkedHashMap<>();
        for (VerdictItem item : items) {
            for (String rawHint : item.hint().split(",")) {
                String surface = Text.stripEdges(rawHint.trim());
                if (surface.isEmpty()) continue;
                String key = Text.normalizeKey(surface);
                WordVerdict existing = byWord.get(key);
                String grade = existing == null ? item.grade() : worse(existing.grade(), item.grade());
                byWord.put(key, new WordVerdict(existing == null ? surface : existing.surface(), grade));
            }
        }
        if (byWord.isEmpty()) return;

        List<WordVerdict> verdicts = List.copyOf(byWord.values());
        List<LexemeDraft> drafts = verdicts.stream()
                .map(v -> new LexemeDraft(v.surface(), null))
                .toList();
        List<String> lexemeIds = noamGateway.createLexemes(drafts);

        for (int i = 0; i < verdicts.size(); i++) {
            String lexemeId = lexemeIds.get(i);
            if (lexemeId == null) continue;
            try {
                noamGateway.recordReview(lexemeId, verdicts.get(i).grade());
            } catch (NoamUnavailableException e) {
                log.warn("Failed to report 語 verdict review for {}: {}", lexemeId, e.toString());
            }
        }
    }

    private static String worse(String a, String b) {
        return GRADE_SEVERITY.indexOf(a) <= GRADE_SEVERITY.indexOf(b) ? a : b;
    }
}
