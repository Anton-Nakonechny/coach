package com.coach.noam;

import com.coach.config.AppConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The only class in {@code coach-web} that talks to noam, the separate vocabulary
 * platform. Write-backs go through here so noam's {@code userId} never reaches the
 * browser — reads happen browser-direct against noam, with one exception:
 * {@link #isAvailable()} runs a narrow server-side reachability probe (no
 * {@code userId} involved) so server-side callers can gate their own noam
 * side-effects synchronously before touching noam.
 *
 * <p>Modeled on {@code com.coach.docs.DocFetchGateway}: a {@link HttpClient} field
 * built once, explicit timeouts, and a narrow public surface that doubles as the
 * test seam ({@code @MockitoBean} in {@code ChatApiTest}, a real in-process
 * {@code HttpServer} in {@code NoamGatewayTest}).
 */
@Component
public class NoamGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NoamGateway.class);

    /** noam's {@code lexemeIds} maxItems for the bulk lexeme-states call. */
    private static final int MAX_CHUNK = 500;

    /** Drafts per {@code POST /lexemes} request — each item costs one sidecar call in noam. */
    private static final int LEXEME_CHUNK = 25;

    /** How long a cached {@link #isAvailable()} outcome is trusted before re-probing. */
    private static final Duration PROBE_TTL = Duration.ofSeconds(60);

    /** Connect/read budget for the availability probe — runs inside a user-facing turn. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AppConfig.Noam config;
    private final ObjectMapper mapper;
    private volatile Probe probe;

    public NoamGateway(AppConfig config, ObjectMapper mapper) {
        this.config = config.noam();
        this.mapper = mapper;
    }

    private record LexemeStatesBody(List<String> lexemeIds, String state, String source) { }

    private record ReviewBody(String lexemeId, String grade, String source) { }

    private record Probe(Instant checkedAt, boolean ok) { }

    private record CreateLexemesBody(List<LexemeItem> lexemes) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record LexemeItem(String surface, String language, String translation, String refLanguage,
            String register, String region) { }

    private record CreateLexemesResponse(List<CreatedLexeme> lexemes, List<FailedLexeme> failed) { }

    private record CreatedLexeme(String lexemeId, boolean created, String displayText) { }

    private record FailedLexeme(String surface, String reason) { }

    /** Whether noam is configured and reachable. Never throws; false on any doubt. */
    public boolean isAvailable() {
        if (config.baseUrl().isBlank()) return false;
        if (config.userId().isBlank()) return false;

        var cached = probe;
        if (cached != null && Duration.between(cached.checkedAt(), Instant.now()).compareTo(PROBE_TTL) < 0)
            return cached.ok();

        var fresh = new Probe(Instant.now(), probeReachable());
        probe = fresh;
        return fresh.ok();
    }

    private boolean probeReachable() {
        try {
            var request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/documents?language=es"))
                    .timeout(PROBE_TIMEOUT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Bulk-set lexeme states. Chunks at 500 ids (noam's maxItems). No-op on an empty list. */
    public void setLexemeStates(List<String> lexemeIds, String state) {
        if (lexemeIds.isEmpty()) return;
        for (int start = 0; start < lexemeIds.size(); start += MAX_CHUNK) {
            var chunk = lexemeIds.subList(start, Math.min(start + MAX_CHUNK, lexemeIds.size()));
            put("/users/" + config.userId() + "/lexeme-states",
                    new LexemeStatesBody(chunk, state, "MANUAL_BULK"));
        }
    }

    /** Record one review event. */
    public void recordReview(String lexemeId, String grade) {
        post("/users/" + config.userId() + "/reviews", new ReviewBody(lexemeId, grade, "EXAM"));
    }

    /**
     * Upsert {@code drafts} in noam and return their lexeme ids, positionally aligned with the
     * input. An entry is null when its id could not be determined (a per-chunk noam failure, or
     * a response noam sent that could not be aligned back to the request). Never throws — a
     * caller that cannot register a word must still serve the user's quiz or chat.
     */
    public List<String> createLexemes(List<LexemeDraft> drafts) {
        if (drafts.isEmpty()) return List.of();

        var results = new ArrayList<String>(drafts.size());
        for (int start = 0; start < drafts.size(); start += LEXEME_CHUNK) {
            var chunk = drafts.subList(start, Math.min(start + LEXEME_CHUNK, drafts.size()));
            results.addAll(createLexemeChunk(chunk));
        }
        return results;
    }

    private List<String> createLexemeChunk(List<LexemeDraft> chunk) {
        var items = chunk.stream().map(NoamGateway::toLexemeItem).toList();
        try {
            var responseBody = postForBody("/lexemes", new CreateLexemesBody(items));
            var response = mapper.readValue(responseBody, CreateLexemesResponse.class);
            return alignLexemeIds(chunk, response);
        } catch (NoamUnavailableException | JsonProcessingException e) {
            log.warn("Failed to register {} word(s) with noam: {}", chunk.size(), e.toString());
            return nullsFor(chunk.size());
        }
    }

    private static LexemeItem toLexemeItem(LexemeDraft draft) {
        var translation = draft.translation() == null || draft.translation().isBlank() ? null : draft.translation();
        var refLanguage = translation == null ? null : "en";
        return new LexemeItem(draft.surface(), "es", translation, refLanguage, "NEUTRAL", "ES-Spain");
    }

    /**
     * {@code lexemes[]} holds the successes in request order; every other request item appears
     * in {@code failed[]}, keyed by its original surface. Mis-pairing here would report one
     * word's SRS grade to a different lexeme, so a response that cannot be trusted to line up
     * (the sanity guard) is discarded wholesale rather than partially matched.
     */
    private static List<String> alignLexemeIds(List<LexemeDraft> chunk, CreateLexemesResponse response) {
        var lexemes = response.lexemes() == null ? List.<CreatedLexeme>of() : response.lexemes();
        var failed = response.failed() == null ? List.<FailedLexeme>of() : response.failed();
        if (lexemes.size() + failed.size() != chunk.size()) return nullsFor(chunk.size());

        var failedSurfaces = failed.stream().map(FailedLexeme::surface)
                .collect(Collectors.toCollection(ArrayList::new));
        var results = new ArrayList<String>(chunk.size());
        var lexemeIterator = lexemes.iterator();
        for (var draft : chunk) {
            if (failedSurfaces.remove(draft.surface())) {
                results.add(null);
            } else if (lexemeIterator.hasNext()) {
                results.add(lexemeIterator.next().lexemeId());
            } else {
                return nullsFor(chunk.size());
            }
        }
        return results;
    }

    private static List<String> nullsFor(int size) {
        return new ArrayList<>(Collections.nCopies(size, null));
    }

    private void put(String path, Object body) {
        send(request(path).PUT(HttpRequest.BodyPublishers.ofString(writeJson(body))));
    }

    private void post(String path, Object body) {
        send(request(path).POST(HttpRequest.BodyPublishers.ofString(writeJson(body))));
    }

    private String postForBody(String path, Object body) {
        return sendForBody(request(path).POST(HttpRequest.BodyPublishers.ofString(writeJson(body))));
    }

    private HttpRequest.Builder request(String path) {
        try {
            return HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
        } catch (IllegalArgumentException e) {
            throw new NoamUnavailableException("noam base URL is not configured", e);
        }
    }

    private String writeJson(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build noam request body", e);
        }
    }

    /** Releases the underlying {@link HttpClient}'s resources. */
    @Override
    public void close() {
        client.close();
    }

    private void send(HttpRequest.Builder requestBuilder) {
        sendForBody(requestBuilder);
    }

    private String sendForBody(HttpRequest.Builder requestBuilder) {
        try {
            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) return response.body();
            throw new NoamUnavailableException("noam returned HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new NoamUnavailableException("noam request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoamUnavailableException("noam request interrupted", e);
        }
    }
}
