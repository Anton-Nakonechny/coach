package com.coach;

import com.coach.anthropic.SdkAnthropicGateway;
import com.coach.anthropic.SdkFileUploadGateway;
import com.coach.docs.DocFetchGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Black-box tests of the MCP (Model Context Protocol) endpoint at {@code POST /mcp}:
 * a Streamable-HTTP JSON-RPC server exposing the Claude Architect quiz as the single
 * MCP prompt {@code claude-architect-quiz(topic)}. The prompt returns the same
 * persona + topic blueprint + official-docs text the web chat uses as its system
 * prompt; the Anthropic gateways are never touched on this path and nothing is
 * persisted. Only the docs-fetch boundary is faked, as in {@link ChatApiTest}.
 *
 * <p>Transport contract: {@code initialize} responds as plain JSON and mints an
 * {@code mcp-session-id} response header; every later call must resend that header
 * and accept both {@code application/json} and {@code text/event-stream} — the
 * JSON-RPC response then arrives as an SSE {@code data:} line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpApiTest {

    static final Path CONV_DIR;
    static final Path COACHES_DIR;

    static {
        try {
            CONV_DIR = Files.createTempDirectory("coach-mcp-test-conv");
            COACHES_DIR = Files.createTempDirectory("coach-mcp-test-coaches");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("coach.conversations-dir", CONV_DIR::toString);
        registry.add("coach.coaches-dir", COACHES_DIR::toString);
        registry.add("coach.docs.cache-dir", () -> COACHES_DIR.resolve("Claude").resolve("docs").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @MockitoBean
    SdkAnthropicGateway gateway;

    @MockitoBean
    SdkFileUploadGateway fileUploadGateway;

    @MockitoBean
    DocFetchGateway docFetchGateway;

    private final List<URI> docFetches = new ArrayList<>();
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        // A response the server never sends must fail the test, not hang the SSE read.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(10));
        rest.getRestTemplate().setRequestFactory(factory);
        wipeDirectory(CONV_DIR);
        wipeDirectory(COACHES_DIR);
    }

    private static void wipeDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.compareTo(a))
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    // ----------------------------------------------------------------------- //
    // Helpers
    // ----------------------------------------------------------------------- //

    private static final String DOC_URL_TOOLS =
            "https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview.md";

    /** A topic blueprint declaring one official-doc source in its front-matter. */
    private static final String SOURCED_BLUEPRINT = """
            <!-- sources:
            %s
            -->
            STOP_REASON DRILL""".formatted(DOC_URL_TOOLS);

    private static void writeClaudePrompt(String name, String content) throws IOException {
        var dir = COACHES_DIR.resolve("Claude");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, UTF_8);
    }

    /** Script the docs fetch to return a fixed body, recording each call. */
    private void scriptDocFetch() {
        doAnswer(inv -> {
            docFetches.add(inv.getArgument(0));
            return "TOOL USE DOC BODY";
        }).when(docFetchGateway).fetch(any());
    }

    private String url() {
        return "http://localhost:" + port + "/mcp";
    }

    /** POST a raw JSON-RPC body; both Accept media types, optional session header. */
    private ResponseEntity<String> mcpPost(String sessionId, String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (sessionId != null) headers.set("mcp-session-id", sessionId);
        return rest.postForEntity(url(), new HttpEntity<>(body, headers), String.class);
    }

    /**
     * The JSON-RPC message in a response body: the payload of the first SSE
     * {@code data:} line when streamed, or the whole body when plain JSON.
     */
    private JsonNode jsonRpc(String body) {
        try {
            for (String line : body.split("\n"))
                if (line.startsWith("data:")) return mapper.readTree(line.substring(5).trim());
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Run the {@code initialize} handshake; the minted session id. */
    private String initializeSession() {
        var resp = mcpPost(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"test","version":"0"}}}""");
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String sessionId = resp.getHeaders().getFirst("mcp-session-id");
        assertThat(sessionId).isNotBlank();
        mcpPost(sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");
        return sessionId;
    }

    /** Call {@code method} in an initialized session; the JSON-RPC response message. */
    private JsonNode mcpCall(String sessionId, int id, String method, String paramsJson) {
        var resp = mcpPost(sessionId, """
                {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}""".formatted(id, method, paramsJson));
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return jsonRpc(resp.getBody());
    }

    // ── Cycle 1: Streamable HTTP transport + prompts/list ─────────────────── //

    @Test
    void initialize_handshake_returnsSessionIdAndServerInfo() {
        var resp = mcpPost(null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18","capabilities":{},
                  "clientInfo":{"name":"test","version":"0"}}}""");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getFirst("mcp-session-id")).isNotBlank();
        var result = jsonRpc(resp.getBody()).path("result");
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("coach");
        assertThat(result.path("capabilities").has("prompts")).isTrue();
    }

    @Test
    void promptsList_afterInitialize_advertisesClaudeArchitectQuizWithRequiredTopicArg() {
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/list", "{}");

        var prompts = message.path("result").path("prompts");
        assertThat(prompts).hasSize(1);
        var prompt = prompts.get(0);
        assertThat(prompt.path("name").asText()).isEqualTo("claude-architect-quiz");
        var arguments = prompt.path("arguments");
        assertThat(arguments).hasSize(1);
        assertThat(arguments.get(0).path("name").asText()).isEqualTo("topic");
        assertThat(arguments.get(0).path("required").asBoolean()).isTrue();
    }

    @Test
    void promptsList_withoutSession_returnsClientError() {
        var resp = mcpPost(null, """
                {"jsonrpc":"2.0","id":2,"method":"prompts/list","params":{}}""");

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── Cycle 2: prompts/get assembles persona + blueprint + official docs ── //

    @Test
    void promptsGet_validTopic_returnsPersonaTopicAndOfficialDocsAsSingleUserMessage() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"1.1 Agentic loops"}}""");

        var messages = message.path("result").path("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("user");
        var text = messages.get(0).path("content").path("text").asText();
        assertThat(text).containsSubsequence(
                "Claude Certified Architect",
                "STOP_REASON DRILL",
                "=== OFFICIAL DOCUMENTATION (source of truth): " + DOC_URL_TOOLS + " ===",
                "TOOL USE DOC BODY");
        assertThat(text).doesNotContain("<!-- sources:");
        assertThat(docFetches).containsExactly(URI.create(DOC_URL_TOOLS));
    }

    @Test
    void promptsGet_validTopic_writesNoConversationFiles() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        var sessionId = initializeSession();

        mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"1.1 Agentic loops"}}""");

        assertThat(CONV_DIR.toFile().list()).isEmpty();
        verifyNoInteractions(gateway, fileUploadGateway);
    }

    // ── Cycle 3: JSON-RPC errors + docs degrade path ───────────────────────── //

    @Test
    void promptsGet_docFetchFails_returnsBlueprintOnlyPrompt() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        doThrow(new RuntimeException("docs down")).when(docFetchGateway).fetch(any());
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"1.1 Agentic loops"}}""");

        var text = message.path("result").path("messages").get(0).path("content").path("text").asText();
        assertThat(text).containsSubsequence("Claude Certified Architect", "STOP_REASON DRILL");
        assertThat(text).doesNotContain("OFFICIAL DOCUMENTATION");
        assertThat(text).doesNotContain("<!-- sources:");
    }

    @Test
    void promptsGet_unknownTopic_returnsInvalidParamsJsonRpcError() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"9.9 No such topic"}}""");

        assertThat(message.has("result")).isFalse();
        assertThat(message.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(message.path("error").path("message").asText()).contains("9.9 No such topic");
    }

    @Test
    void promptsGet_missingTopicArgument_returnsInvalidParamsJsonRpcError() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{}}""");

        assertThat(message.has("result")).isFalse();
        assertThat(message.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(message.path("error").path("message").asText()).contains("topic");
    }

    // ── Cycle 5: partial-topic resolution (Claude Code passes one token only) ── //

    @Test
    void promptsGet_uniqueTopicPrefix_resolvesToFullTopic() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        writeClaudePrompt("2.4 MCP server integration.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"2.4"}}""");

        var text = message.path("result").path("messages").get(0).path("content").path("text").asText();
        assertThat(text).containsSubsequence("Claude Certified Architect", "STOP_REASON DRILL");
        assertThat(message.path("result").path("description").asText())
                .contains("2.4 MCP server integration");
    }

    @Test
    void promptsGet_quotedTopic_stripsQuotesBeforeMatching() throws IOException {
        writeClaudePrompt("2.4 MCP server integration.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"\\"2.4"}}""");

        var text = message.path("result").path("messages").get(0).path("content").path("text").asText();
        assertThat(text).contains("STOP_REASON DRILL");
    }

    @Test
    void promptsGet_ambiguousTopicPrefix_returnsInvalidParamsListingCandidates() throws IOException {
        writeClaudePrompt("2.4 MCP server integration.md", "content");
        writeClaudePrompt("2.4b MCP resources.md", "content");
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"2.4"}}""");

        assertThat(message.has("result")).isFalse();
        assertThat(message.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(message.path("error").path("message").asText())
                .contains("2.4 MCP server integration")
                .contains("2.4b MCP resources");
    }

    @Test
    void promptsGet_exactTopicShadowedByLongerStem_prefersExactMatch() throws IOException {
        writeClaudePrompt("2.4 MCP server integration.md", SOURCED_BLUEPRINT);
        writeClaudePrompt("2.4 MCP server integration advanced.md", "content");
        scriptDocFetch();
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"2.4 MCP server integration"}}""");

        var text = message.path("result").path("messages").get(0).path("content").path("text").asText();
        assertThat(text).contains("STOP_REASON DRILL");
    }

    // ── Cycle 4: topic argument completion ─────────────────────────────────── //

    @Test
    void completionComplete_topicPrefix_returnsMatchingTopics() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        writeClaudePrompt("2.4 MCP server integration.md", "content");
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "completion/complete", """
                {"ref":{"type":"ref/prompt","name":"claude-architect-quiz"},
                 "argument":{"name":"topic","value":"2.4"}}""");

        var values = message.path("result").path("completion").path("values");
        assertThat(values).hasSize(1);
        assertThat(values.get(0).asText()).isEqualTo("2.4 MCP server integration");
    }

    @Test
    void completionComplete_emptyCoachesDir_returnsEmptyCompletionNotError() {
        // coaches/Claude doesn't exist → claudeTopics() throws; complete() must degrade gracefully
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "completion/complete", """
                {"ref":{"type":"ref/prompt","name":"claude-architect-quiz"},
                 "argument":{"name":"topic","value":"1.1"}}""");

        assertThat(message.has("error")).isFalse();
        assertThat(message.path("result").path("completion").path("values")).isEmpty();
    }

    @Test
    void promptsGet_emptyCoachesDir_returnsCleanInternalError() {
        // coaches/Claude doesn't exist → claudeTopics() throws with the dir path in its message;
        // the error must not leak the filesystem path to the MCP client
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":"1.1"}}""");

        assertThat(message.has("result")).isFalse();
        assertThat(message.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(message.path("error").path("message").asText())
                .doesNotContain(COACHES_DIR.toString());
    }

    @Test
    void promptsGet_numericTopicArgument_returnsInvalidParamsError() throws IOException {
        // A JSON number in the topic argument must not ClassCastException → -32603;
        // it should resolve/fail as -32602 INVALID_PARAMS
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        var sessionId = initializeSession();

        var message = mcpCall(sessionId, 2, "prompts/get", """
                {"name":"claude-architect-quiz","arguments":{"topic":42}}""");

        assertThat(message.has("result")).isFalse();
        assertThat(message.path("error").path("code").asInt()).isEqualTo(-32602);
    }
}
