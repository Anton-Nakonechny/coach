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
 * given id and conversation owns it, and a later one is served that first run's
 * answer rather than appending the turn again — or, for a chat that had no id
 * yet, minting a second conversation for it.
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

    private final ConcurrentHashMap<TurnKey, Turn> turns = new ConcurrentHashMap<>();

    private record TurnKey(String turnId, String conversationId) { }

    private record Turn(Instant started, CompletableFuture<ChatResponse> answer) { }

    /**
     * Run {@code handler} once per turn, serving any repeat of that turn the same
     * answer. A blank {@code turnId} opts out: the turn is simply handled.
     *
     * <p>A turn is its id <em>and</em> the conversation it asked to join, because
     * the client can legitimately change the latter: a turn queued before its chat
     * had an id gets one written into it as soon as any turn of that chat lands
     * (adoptMintedConversation), and that rewrite is the client saying where the
     * turn now belongs. Answering on the id alone would hand back the conversation
     * the first run minted — which the client then adopts as the open chat, so the
     * message it really did deliver elsewhere drops out of the model's context and
     * one chat ends up split across two conversations.
     */
    public ChatResponse once(String turnId, String conversationId, Supplier<ChatResponse> handler) {
        if (!StringUtils.hasText(turnId)) return handler.get();
        evictExpired();
        if (turns.size() >= MAX_SIZE) evictOldest();

        var key = new TurnKey(turnId, conversationId);
        var mine = new Turn(Instant.now(), new CompletableFuture<>());
        var owner = turns.putIfAbsent(key, mine);
        if (owner != null) return await(owner);

        try {
            var answer = handler.get();
            mine.answer().complete(answer);
            return answer;
        } catch (Throwable e) {
            // The controller rolls its user turn back on a failure, so nothing was
            // persisted and the id must go too — otherwise the retry the client is
            // entitled to would be answered with this failure for an hour.
            turns.remove(key);
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
