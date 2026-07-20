package com.coach.web;

import com.coach.anthropic.ApiMessage;
import com.coach.anthropic.ClaudeClient;
import com.coach.anthropic.TextBlock;
import com.coach.coach.CoachService;
import com.coach.coach.Text;
import com.coach.coach.InvalidRequestException;
import com.coach.model.ModelKey;
import com.coach.model.ModelsConfig;
import com.coach.web.dto.WordCheckRequest;
import com.coach.web.dto.WordCheckResponse;
import com.coach.web.dto.WordPrompt;
import com.coach.web.dto.WordResult;
import com.coach.web.dto.WordTranslateRequest;
import com.coach.web.dto.WordTranslateResponse;
import com.coach.word.WordPair;
import com.coach.word.WordSetStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ephemeral 字 vocabulary quiz: translate a word list → masked hints, then check
 * user answers server-side. Nothing is persisted — no JSONL, no sidecar.
 */
@RestController
@RequestMapping("/api/spanish/words")
public class SpanishWordController {

    private final ClaudeClient claudeClient;
    private final CoachService coachService;
    private final WordSetStore wordSetStore;
    private final ModelsConfig models;

    public SpanishWordController(ClaudeClient claudeClient, CoachService coachService,
                                 WordSetStore wordSetStore, ModelsConfig models) {
        this.claudeClient = claudeClient;
        this.coachService = coachService;
        this.wordSetStore = wordSetStore;
        this.models = models;
    }

    /**
     * Translate a comma/newline-separated list of Spanish words to English, store the
     * pairs ephemerally, and return masked hints so the user can recall the Spanish.
     */
    @PostMapping("/translate")
    public WordTranslateResponse translate(@RequestBody WordTranslateRequest request) {
        List<String> tokens = coachService.parseWordList(request.words());
        if (tokens.isEmpty())
            throw new InvalidRequestException("word list must not be empty");

        ModelKey model = request.model() != null ? request.model() : models.defaultModel();
        String userText = String.join("\n", tokens);
        ApiMessage msg = new ApiMessage("user", List.of(new TextBlock(userText)));
        String answer = claudeClient.generate(model, List.of(msg), request.effort(),
                CoachService.WORD_TRANSLATE_SYSTEM);

        List<WordPair> pairs = coachService.pairTranslations(tokens, answer);
        List<WordPair> shuffled = new ArrayList<>(pairs);
        Collections.shuffle(shuffled);

        String setId = wordSetStore.put(shuffled);
        List<WordPrompt> items = shuffled.stream()
                .map(p -> new WordPrompt(p.english(), coachService.maskHint(p.spanishOriginal()),
                        p.spanishOriginal()))
                .toList();
        return new WordTranslateResponse(setId, items);
    }

    /**
     * Grade user answers for a previously translated set (single-use).
     * Matching is case- and accent-insensitive. No LLM call is made.
     */
    @PostMapping("/check")
    public WordCheckResponse check(@RequestBody WordCheckRequest request) {
        List<WordPair> pairs = wordSetStore.take(request.setId())
                .orElseThrow(() -> new ConversationNotFoundException("Word set expired — start again."));

        List<String> answers = request.answers() != null ? request.answers() : List.of();
        List<Boolean> hintsUsed = request.hintsUsed() != null ? request.hintsUsed() : List.of();
        List<WordResult> results = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            WordPair pair = pairs.get(i);
            String answer = i < answers.size() ? answers.get(i) : "";
            boolean correct = !answer.isBlank()
                    && Text.normalizeKey(answer).equals(Text.normalizeKey(pair.spanishOriginal()));
            boolean fullHint = i < hintsUsed.size() && Boolean.TRUE.equals(hintsUsed.get(i));
            results.add(new WordResult(pair.english(), pair.spanishOriginal(), correct, fullHint));
        }
        return new WordCheckResponse(results);
    }
}
