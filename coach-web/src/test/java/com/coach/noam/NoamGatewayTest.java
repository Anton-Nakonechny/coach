package com.coach.noam;

import com.coach.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link NoamGateway}'s real {@link java.net.http.HttpClient} against an
 * in-process JDK {@link HttpServer} on an ephemeral port — verifying the PUT/POST
 * URL and JSON body shapes, chunking at 500 ids, and non-2xx mapping to
 * {@link NoamUnavailableException}. Mirrors
 * {@code coach-core/src/test/java/com/coach/docs/DocFetchGatewayTest.java}.
 */
class NoamGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private int port;
    private NoamGateway gateway;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    private record RecordedRequest(String method, String path, JsonNode body) { }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        var config = new AppConfig(null, 0, null, null, null, null, null,
                new AppConfig.Noam("http://localhost:" + port, "profile-1", "user-1"));
        gateway = new NoamGateway(config, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void recordAndRespond(String path, int status, String responseBody) {
        server.createContext(path, exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            JsonNode node = requestBytes.length == 0 ? null : mapper.readTree(requestBytes);
            requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), node));
            respond(exchange, status, responseBody);
        });
    }

    @Test
    void setLexemeStatesSendsPutWithLexemeIdsStateAndManualBulkSource() {
        recordAndRespond("/users/user-1/lexeme-states", 200, "{\"updated\":2}");

        gateway.setLexemeStates(List.of("id1", "id2"), "KNOWN");

        assertThat(requests).hasSize(1);
        var req = requests.get(0);
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.path()).isEqualTo("/users/user-1/lexeme-states");
        assertThat(req.body().get("lexemeIds")).extracting(JsonNode::asText).containsExactly("id1", "id2");
        assertThat(req.body().get("state").asText()).isEqualTo("KNOWN");
        assertThat(req.body().get("source").asText()).isEqualTo("MANUAL_BULK");
    }

    @Test
    void setLexemeStatesChunksAt500Ids() {
        recordAndRespond("/users/user-1/lexeme-states", 200, "{\"updated\":1}");
        List<String> ids = IntStream.range(0, 601).mapToObj(i -> "id" + i).collect(Collectors.toCollection(ArrayList::new));

        gateway.setLexemeStates(ids, "IGNORED");

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).body().get("lexemeIds")).hasSize(500);
        assertThat(requests.get(1).body().get("lexemeIds")).hasSize(101);
        var allSent = new ArrayList<String>();
        requests.forEach(r -> r.body().get("lexemeIds").forEach(n -> allSent.add(n.asText())));
        assertThat(allSent).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void setLexemeStatesWithEmptyListIsANoOp() {
        gateway.setLexemeStates(List.of(), "KNOWN");

        assertThat(requests).isEmpty();
    }

    @Test
    void recordReviewSendsPostWithLexemeIdGradeAndExamSource() {
        recordAndRespond("/users/user-1/reviews", 200, "{\"lexemeId\":\"id1\"}");

        gateway.recordReview("id1", "GOOD");

        assertThat(requests).hasSize(1);
        var req = requests.get(0);
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/users/user-1/reviews");
        assertThat(req.body().get("lexemeId").asText()).isEqualTo("id1");
        assertThat(req.body().get("grade").asText()).isEqualTo("GOOD");
        assertThat(req.body().get("source").asText()).isEqualTo("EXAM");
    }

    @Test
    void serverErrorStatusThrowsNoamUnavailableException() {
        recordAndRespond("/users/user-1/lexeme-states", 500, "boom");

        assertThatThrownBy(() -> gateway.setLexemeStates(List.of("id1"), "KNOWN"))
                .isInstanceOf(NoamUnavailableException.class);
    }

    @Test
    void connectionFailureThrowsNoamUnavailableException() {
        server.stop(0);

        assertThatThrownBy(() -> gateway.setLexemeStates(List.of("id1"), "KNOWN"))
                .isInstanceOf(NoamUnavailableException.class);
    }
}
