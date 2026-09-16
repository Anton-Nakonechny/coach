package com.coach.web;

import com.coach.web.dto.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Makes a replayed chat turn a no-op instead of a second turn.
 *
 * <p>A browser cannot tell a request that never left the device from one whose
 * answer was lost on the way back: {@code fetch()} rejects with the same
 * TypeError either way, and for a chat turn the second case covers the whole
 * generation window, since no response headers arrive until the model is done.
 * The offline outbox therefore replays turns the server may already have taken.
 * Each turn carries a client-generated id; the first request to arrive with a
 * given id owns it, and a later one is served that first run's answer rather
 * than appending the turn again — or, for a chat that had no id yet, minting a
 * second conversation for it.
 *
 * <p>In memory only, like {@link com.coach.word.WordSetStore}: this closes the
 * window between a dropped connection and its replay, not a server restart. A
 * turn still generating when its replay arrives is waited on rather than run
 * twice, and a turn that failed is forgotten so the retry it earned goes through.
 */
@Component
public class TurnReplayGuard {

    private static final long TTL_SECONDS = 3600;
    private static final int MAX_SIZE = 500;

    private final ConcurrentHashMap<String, Turn> turns = new ConcurrentHashMap<>();

    private record Turn(Instant started, CompletableFuture<ChatResponse> answer) { }

    /**
     * Run {@code handler} once per {@code turnId}, serving any repeat of that id
     * the same answer. A blank id opts out: the turn is simply handled.
     */
    public ChatResponse once(String turnId, Supplier<ChatResponse> handler) {
        if (!StringUtils.hasText(turnId)) return handler.get();
        evictExpired();
        if (turns.size() >= MAX_SIZE) evictOldest();

        var mine = new Turn(Instant.now(), new CompletableFuture<>());
        var owner = turns.putIfAbsent(turnId, mine);
        if (owner != null) return await(owner);

        try {
            var answer = handler.get();
            mine.answer().complete(answer);
            return answer;
        } catch (Throwable e) {
            // The controller rolls its user turn back on a failure, so nothing was
            // persisted and the id must go too — otherwise the retry the client is
            // entitled to would be answered with this failure for an hour.
            turns.remove(turnId);
            mine.answer().completeExceptionally(e);
            throw e;
        }
    }

    /** Wait for the owning request's answer, rethrowing its failure as our own. */
    private ChatResponse await(Turn owner) {
        try {
            return owner.answer().join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    private void evictExpired() {
        var cutoff = Instant.now().minusSeconds(TTL_SECONDS);
        turns.entrySet().removeIf(e -> e.getValue().started().isBefore(cutoff));
    }

    private void evictOldest() {
        turns.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().started()))
                .map(Map.Entry::getKey)
                .ifPresent(turns::remove);
    }
}
