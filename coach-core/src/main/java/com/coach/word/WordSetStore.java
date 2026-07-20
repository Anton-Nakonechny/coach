package com.coach.word;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ephemeral in-memory store for 字 word-quiz sets. Sets are keyed by a random id
 * and expire after ~60 minutes. Nothing is persisted — a server restart loses all
 * in-flight sets, so callers handle the resulting 404.
 */
@Component
public class WordSetStore {

    private static final long TTL_SECONDS = 3600;
    private static final int MAX_SIZE = 500;

    private final ConcurrentHashMap<String, StoredSet> sets = new ConcurrentHashMap<>();

    private record StoredSet(Instant created, List<WordPair> pairs) {}

    /** Store a new set of pairs; returns the generated set id. */
    public String put(List<WordPair> pairs) {
        evictExpired();
        if (sets.size() >= MAX_SIZE) evictOldest();
        String id = UUID.randomUUID().toString().replace("-", "");
        sets.put(id, new StoredSet(Instant.now(), List.copyOf(pairs)));
        return id;
    }

    /**
     * Retrieve and remove the set identified by {@code setId} (single-use).
     * Returns empty if unknown or expired.
     */
    public Optional<List<WordPair>> take(String setId) {
        StoredSet stored = sets.remove(setId);
        if (stored == null) return Optional.empty();
        if (Instant.now().isAfter(stored.created().plusSeconds(TTL_SECONDS)))
            return Optional.empty();
        return Optional.of(stored.pairs());
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(TTL_SECONDS);
        sets.entrySet().removeIf(e -> e.getValue().created().isBefore(cutoff));
    }

    private void evictOldest() {
        sets.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().created()))
                .map(Map.Entry::getKey)
                .ifPresent(sets::remove);
    }
}
