package com.coach.noam;

import com.coach.config.AppConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * The only class in {@code coach-web} that talks to noam, the separate vocabulary
 * platform. Write-backs go through here so noam's {@code userId} never reaches the
 * browser — reads happen browser-direct against noam.
 *
 * <p>Modeled on {@code com.coach.docs.DocFetchGateway}: a {@link HttpClient} field
 * built once, explicit timeouts, and a narrow public surface that doubles as the
 * test seam ({@code @MockitoBean} in {@code ChatApiTest}, a real in-process
 * {@code HttpServer} in {@code NoamGatewayTest}).
 */
@Component
public class NoamGateway implements AutoCloseable {

    /** noam's {@code lexemeIds} maxItems for the bulk lexeme-states call. */
    private static final int MAX_CHUNK = 500;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AppConfig.Noam config;
    private final ObjectMapper mapper;

    public NoamGateway(AppConfig config, ObjectMapper mapper) {
        this.config = config.noam();
        this.mapper = mapper;
    }

    private record LexemeStatesBody(List<String> lexemeIds, String state, String source) { }

    private record ReviewBody(String lexemeId, String grade, String source) { }

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

    private void put(String path, Object body) {
        send(request(path).PUT(HttpRequest.BodyPublishers.ofString(writeJson(body))));
    }

    private void post(String path, Object body) {
        send(request(path).POST(HttpRequest.BodyPublishers.ofString(writeJson(body))));
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
        try {
            var response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) return;
            throw new NoamUnavailableException("noam returned HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new NoamUnavailableException("noam request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NoamUnavailableException("noam request interrupted", e);
        }
    }
}
