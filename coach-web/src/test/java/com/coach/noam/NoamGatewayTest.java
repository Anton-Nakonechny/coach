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

    private record RecordedRequest(String method, String path, String query, JsonNode body) { }

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
        gateway.close();
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
            requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), node));
            respond(exchange, status, responseBody);
        });
    }

    private NoamGateway gatewayWith(String baseUrl, String userId) {
        var config = new AppConfig(null, 0, null, null, null, null, null,
                new AppConfig.Noam(baseUrl, "profile-1", userId));
        return new NoamGateway(config, mapper);
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

    @Test
    void blankBaseUrlThrowsNoamUnavailableExceptionInsteadOfIllegalArgumentException() {
        var config = new AppConfig(null, 0, null, null, null, null, null,
                new AppConfig.Noam("", "profile-1", "user-1"));
        var unconfiguredGateway = new NoamGateway(config, mapper);

        assertThatThrownBy(() -> unconfiguredGateway.setLexemeStates(List.of("id1"), "KNOWN"))
                .isInstanceOf(NoamUnavailableException.class);
        assertThatThrownBy(() -> unconfiguredGateway.recordReview("id1", "GOOD"))
                .isInstanceOf(NoamUnavailableException.class);

        unconfiguredGateway.close();
    }

    @Test
    void isAvailable_blankBaseUrl_returnsFalseWithoutRequest() {
        recordAndRespond("/documents", 200, "[]");
        var unconfiguredGateway = gatewayWith("", "user-1");

        assertThat(unconfiguredGateway.isAvailable()).isFalse();

        assertThat(requests).isEmpty();
        unconfiguredGateway.close();
    }

    @Test
    void isAvailable_blankUserId_returnsFalseWithoutRequest() {
        recordAndRespond("/documents", 200, "[]");
        var unconfiguredGateway = gatewayWith("http://localhost:" + port, "");

        assertThat(unconfiguredGateway.isAvailable()).isFalse();

        assertThat(requests).isEmpty();
        unconfiguredGateway.close();
    }

    @Test
    void isAvailable_probeReturns200_returnsTrue() {
        recordAndRespond("/documents", 200, "[]");

        assertThat(gateway.isAvailable()).isTrue();

        assertThat(requests).hasSize(1);
        var req = requests.get(0);
        assertThat(req.method()).isEqualTo("GET");
        assertThat(req.path()).isEqualTo("/documents");
        assertThat(req.query()).isEqualTo("language=es");
    }

    @Test
    void isAvailable_probeReturns500_returnsFalse() {
        recordAndRespond("/documents", 500, "boom");

        assertThat(gateway.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_serverDown_returnsFalse() {
        server.stop(0);

        assertThat(gateway.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_secondCallWithinTtl_doesNotProbeAgain() {
        recordAndRespond("/documents", 200, "[]");

        gateway.isAvailable();
        gateway.isAvailable();

        assertThat(requests).hasSize(1);
    }

    @Test
    void createLexemes_emptyList_makesNoRequest() {
        assertThat(gateway.createLexemes(List.of())).isEmpty();

        assertThat(requests).isEmpty();
    }

    @Test
    void createLexemes_allSucceed_returnsIdsInRequestOrder() {
        recordAndRespond("/lexemes", 201, """
                {"lexemes": [
                    {"lexemeId": "id-a", "created": true, "displayText": "a"},
                    {"lexemeId": "id-b", "created": true, "displayText": "b"},
                    {"lexemeId": "id-c", "created": false, "displayText": "c"}
                ], "failed": []}
                """);

        var ids = gateway.createLexemes(List.of(
                new LexemeDraft("a", null), new LexemeDraft("b", null), new LexemeDraft("c", null)));

        assertThat(ids).containsExactly("id-a", "id-b", "id-c");
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(requests.get(0).path()).isEqualTo("/lexemes");
    }

    @Test
    void createLexemes_oneFailedEntry_returnsNullInThatSlot() {
        recordAndRespond("/lexemes", 200, """
                {"lexemes": [
                    {"lexemeId": "id-a", "created": true, "displayText": "a"},
                    {"lexemeId": "id-c", "created": true, "displayText": "c"}
                ], "failed": [{"surface": "b", "reason": "not a word"}]}
                """);

        var ids = gateway.createLexemes(List.of(
                new LexemeDraft("a", null), new LexemeDraft("b", null), new LexemeDraft("c", null)));

        assertThat(ids).containsExactly("id-a", null, "id-c");
    }

    @Test
    void createLexemes_countsDoNotAddUp_returnsAllNulls() {
        recordAndRespond("/lexemes", 201, """
                {"lexemes": [
                    {"lexemeId": "id-a", "created": true, "displayText": "a"},
                    {"lexemeId": "id-b", "created": true, "displayText": "b"}
                ], "failed": []}
                """);

        var ids = gateway.createLexemes(List.of(
                new LexemeDraft("a", null), new LexemeDraft("b", null), new LexemeDraft("c", null)));

        assertThat(ids).containsExactly(null, null, null);
    }

    @Test
    void createLexemes_nonSuccessStatus_returnsNullsWithoutThrowing() {
        recordAndRespond("/lexemes", 422, "{\"message\":\"not a word\"}");

        var ids = gateway.createLexemes(List.of(new LexemeDraft("a", null), new LexemeDraft("b", null)));

        assertThat(ids).containsExactly(null, null);
    }

    @Test
    void createLexemes_blankTranslation_omitsTranslationFields() {
        recordAndRespond("/lexemes", 201, """
                {"lexemes": [{"lexemeId": "id-a", "created": true, "displayText": "a"}], "failed": []}
                """);

        gateway.createLexemes(List.of(new LexemeDraft("a", "  ")));

        var item = requests.get(0).body().get("lexemes").get(0);
        assertThat(item.has("translation")).isFalse();
        assertThat(item.has("refLanguage")).isFalse();
        assertThat(item.get("surface").asText()).isEqualTo("a");
        assertThat(item.get("language").asText()).isEqualTo("es");
        assertThat(item.get("register").asText()).isEqualTo("NEUTRAL");
        assertThat(item.get("region").asText()).isEqualTo("ES-Spain");
    }

    @Test
    void createLexemes_sendsRefLanguageEn() {
        recordAndRespond("/lexemes", 201, """
                {"lexemes": [{"lexemeId": "id-a", "created": true, "displayText": "a"}], "failed": []}
                """);

        gateway.createLexemes(List.of(new LexemeDraft("avestruz", "ostrich")));

        var item = requests.get(0).body().get("lexemes").get(0);
        assertThat(item.get("translation").asText()).isEqualTo("ostrich");
        assertThat(item.get("refLanguage").asText()).isEqualTo("en");
    }

    @Test
    void createLexemes_moreThanChunkSize_splitsIntoSeveralRequests() {
        server.createContext("/lexemes", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            JsonNode node = mapper.readTree(requestBytes);
            requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), node));

            var responseLexemes = new ArrayList<String>();
            node.get("lexemes").forEach(item -> {
                var surface = item.get("surface").asText();
                responseLexemes.add(String.format(
                        "{\"lexemeId\": \"id-%s\", \"created\": true, \"displayText\": \"%s\"}", surface, surface));
            });
            var responseBody = "{\"lexemes\": [" + String.join(",", responseLexemes) + "], \"failed\": []}";
            respond(exchange, 201, responseBody);
        });
        var drafts = IntStream.range(0, 30)
                .mapToObj(i -> new LexemeDraft("word" + i, null))
                .collect(Collectors.toCollection(ArrayList::new));

        var ids = gateway.createLexemes(drafts);

        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).body().get("lexemes")).hasSize(25);
        assertThat(requests.get(1).body().get("lexemes")).hasSize(5);
        assertThat(ids).containsExactlyElementsOf(
                IntStream.range(0, 30).mapToObj(i -> "id-word" + i).toList());
    }
}
