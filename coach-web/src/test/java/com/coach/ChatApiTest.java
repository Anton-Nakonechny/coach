package com.coach;

import com.coach.anthropic.AnthropicBlock;
import com.coach.anthropic.ApiMessage;
import com.coach.anthropic.AttachmentBlock;
import static com.coach.anthropic.MimeType.APPLICATION_PDF;
import static com.coach.anthropic.MimeType.IMAGE_PNG;
import static com.coach.anthropic.MimeType.TEXT_MARKDOWN;
import static com.coach.anthropic.MimeType.TEXT_PLAIN;
import com.coach.anthropic.SdkAnthropicGateway;
import com.coach.anthropic.SdkFileUploadGateway;
import com.coach.anthropic.TextBlock;
import com.coach.anthropic.UploadedFile;
import com.coach.docs.DocFetchGateway;
import com.coach.store.ConversationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Black-box end-to-end tests of the Coach HTTP contract — Java port of
 * {@code backend/tests/test_api.py}. The whole owned stack runs for real
 * (controller, request shaping, JSONL persistence in a tmp dir); only the
 * Anthropic and docs-fetch boundaries are faked via Mockito {@code @MockitoBean}s.
 *
 * <p>Status codes follow idiomatic Spring conventions: bad input → 400 (Bean
 * Validation), unknown model → 400, not found → 404, upstream failure → 500.
 * Error bodies are {@code {"message": ...}}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatApiTest {

    static final Path CONV_DIR;
    static final Path COACHES_DIR;

    static {
        try {
            CONV_DIR = Files.createTempDirectory("coach-test-conv");
            COACHES_DIR = Files.createTempDirectory("coach-test-coaches");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("coach.conversations-dir", CONV_DIR::toString);
        registry.add("coach.coaches-dir", COACHES_DIR::toString);
        // Small caps so limits can be exercised with tiny inputs (allowed-mime-types
        // stays as configured in application.yml).
        registry.add("coach.upload.max-file-size-bytes", () -> 1024);
        registry.add("coach.upload.max-files-per-message", () -> 5);
        registry.add("coach.upload.max-zip-entries", () -> 10);
        registry.add("coach.upload.max-total-extracted-bytes", () -> 8192);
        // Docs snapshots live under the temp coaches dir; a small cap so truncation
        // can be exercised with modest inputs.
        registry.add("coach.docs.cache-dir", () -> COACHES_DIR.resolve("Claude").resolve("docs").toString());
        registry.add("coach.docs.max-chars", () -> 10000);
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

    @Autowired
    ConversationStore store;

    private final Deque<List<AnthropicBlock>> pendingResponses = new ArrayDeque<>();
    private final List<GatewayCall> gatewayCalls = new ArrayList<>();
    private final List<FakeUpload> uploads = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<URI> docFetches = new ArrayList<>();
    private int uploadSeq;
    final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        doAnswer(inv -> {
            gatewayCalls.add(new GatewayCall(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                List.copyOf(inv.getArgument(3)), Map.copyOf(inv.getArgument(4))));
            return pendingResponses.isEmpty()
                ? List.of(new AnthropicBlock("text", "(no scripted response)"))
                : pendingResponses.poll();
        }).when(gateway).createMessage(any(), anyInt(), any(), any(), any());
        doAnswer(inv -> {
            String fn = inv.getArgument(0);
            String mt = inv.getArgument(1);
            byte[] bytes = inv.getArgument(2);
            uploads.add(new FakeUpload(fn, mt, bytes.length));
            return new UploadedFile(String.format("file_test_%04d", ++uploadSeq), mt, fn);
        }).when(fileUploadGateway).upload(any(), any(), any());
        doAnswer(inv -> { deleted.add(inv.getArgument(0, String.class)); return null; })
                .when(fileUploadGateway).delete(any());
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> postChat(Map<String, Object> body) {
        return rest.postForEntity(url("/api/chat"), body, String.class);
    }

    /** A file part for a multipart chat request. */
    record PartFile(String filename, String contentType, byte[] bytes) { }

    record GatewayCall(String modelId, int maxTokens, String system, List<ApiMessage> messages,
                       Map<String, Object> extraBody) {}

    record FakeUpload(String filename, String mediaType, int byteLength) {}

    /** POST {@code /api/chat} as multipart: a JSON {@code request} part + {@code files} parts. */
    private ResponseEntity<String> postChatMultipart(Map<String, Object> request, List<PartFile> files) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        try {
            parts.add("request", new HttpEntity<>(mapper.writeValueAsString(request), jsonHeaders));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        for (PartFile pf : files) {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType(pf.contentType()));
            ByteArrayResource res = new ByteArrayResource(pf.bytes()) {
                @Override
                public String getFilename() {
                    return pf.filename();
                }
            };
            parts.add("files", new HttpEntity<>(res, h));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(url("/api/chat"), new HttpEntity<>(parts, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> resp) {
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> chatBody(String message, String model, String effort, String conversationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        if (model != null) body.put("model", model);
        if (effort != null) body.put("effort", effort);
        if (conversationId != null) body.put("conversationId", conversationId);
        return body;
    }

    private List<String> roles(GatewayCall call) {
        return call.messages().stream().map(ApiMessage::role).toList();
    }

    /** Concatenated text of a turn's {@link TextBlock}s (ignores attachment blocks). */
    private String textOf(ApiMessage message) {
        return message.content().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).text())
                .collect(Collectors.joining());
    }

    /** The last (most recent) message sent to the gateway on the latest call. */
    private ApiMessage lastUserMessage() {
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        return call.messages().get(call.messages().size() - 1);
    }

    /** Build an in-memory zip from alternating {@code name, content} arguments. */
    private static byte[] zip(String... nameThenContentPairs) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameThenContentPairs.length; i += 2) {
                zos.putNextEntry(new ZipEntry(nameThenContentPairs[i]));
                zos.write(nameThenContentPairs[i + 1].getBytes(UTF_8));
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private void queueText(String text) {
        pendingResponses.add(List.of(new AnthropicBlock("text", text)));
    }

    private void queueBlocks(AnthropicBlock... blocks) {
        pendingResponses.add(List.of(blocks));
    }

    // ----------------------------------------------------------------------- //
    // Chat flow + persistence
    // ----------------------------------------------------------------------- //

    @Test
    void chatMintsConversationAndReturnsAnswer() {
        queueText("Hello there.");

        ResponseEntity<String> resp = postChat(chatBody("hi", "opus-4-8", null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.get("answer").asText()).isEqualTo("Hello there.");
        assertThat(body.get("model").asText()).isEqualTo("opus-4-8");
        assertThat(body.get("conversationId").asText()).isNotBlank();
    }

    @Test
    void chatPersistsBothTurnsAsJsonl() throws IOException {
        queueText("an answer");

        String cid = json(postChat(chatBody("a question", "sonnet-4-6", "high", null)))
                .get("conversationId").asText();

        List<String> lines = Files.readAllLines(CONV_DIR.resolve(cid + ".jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode userRec = mapper.readTree(lines.get(0));
        JsonNode asstRec = mapper.readTree(lines.get(1));
        assertThat(userRec.get("role").asText()).isEqualTo("user");
        assertThat(asstRec.get("role").asText()).isEqualTo("assistant");
        assertThat(userRec.get("content").asText()).isEqualTo("a question");
        assertThat(asstRec.get("content").asText()).isEqualTo("an answer");
        // Metadata captured on every line.
        assertThat(userRec.get("model").asText()).isEqualTo("sonnet-4-6");
        assertThat(userRec.get("effort").asText()).isEqualTo("high");
        assertThat(asstRec.get("ts").asText()).isNotBlank();
    }

    @Test
    void chatReusesConversationAndResendsFullHistory() {
        queueText("first");
        queueText("second");

        String cid = json(postChat(chatBody("q1", "opus-4-8", null, null)))
                .get("conversationId").asText();
        ResponseEntity<String> second = postChat(chatBody("q2", "opus-4-8", null, cid));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(second).get("conversationId").asText()).isEqualTo(cid);
        assertThat(store.loadMessages(cid)).hasSize(4);
        GatewayCall secondCall = gatewayCalls.get(1);
        assertThat(roles(secondCall)).containsExactly("user", "assistant", "user");
        assertThat(textOf(secondCall.messages().get(0))).isEqualTo("q1");
        assertThat(textOf(secondCall.messages().get(2))).isEqualTo("q2");
    }

    @Test
    void chatJoinsOnlyTextBlocks() {
        queueBlocks(
                new AnthropicBlock("thinking", ""),
                new AnthropicBlock("text", "Part one. "),
                new AnthropicBlock("text", "Part two."));

        String answer = json(postChat(chatBody("hi", "opus-4-8", null, null)))
                .get("answer").asText();

        assertThat(answer).isEqualTo("Part one. Part two.");
    }

    // ----------------------------------------------------------------------- //
    // Attachments
    // ----------------------------------------------------------------------- //

    @Test
    void chatWithImageAttachmentUploadsAndSendsImageBlock() {
        queueText("I see a red square.");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1};

        ResponseEntity<String> resp = postChatMultipart(
                chatBody("what is this?", "opus-4-8", null, null),
                List.of(new PartFile("square.png", "image/png", png)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Uploaded to the (faked) Files API exactly once, with the right name + type.
        assertThat(uploads).singleElement().satisfies(u -> {
            assertThat(u.filename()).isEqualTo("square.png");
            assertThat(u.mediaType()).isEqualTo("image/png");
        });

        // Claude received a text block + an image block referencing the returned file_id.
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        ApiMessage lastUser = call.messages().get(call.messages().size() - 1);
        assertThat(lastUser.role()).isEqualTo("user");
        assertThat(lastUser.content()).containsExactly(
                new TextBlock("what is this?"),
                new AttachmentBlock("file_test_0001", IMAGE_PNG, "square.png",
                        AttachmentBlock.Kind.IMAGE));
    }

    @Test
    void chatWithPdfSendsDocumentBlock() {
        queueText("ok");

        postChatMultipart(chatBody("summarize", "opus-4-8", null, null),
                List.of(new PartFile("doc.pdf", "application/pdf", new byte[]{'%', 'P', 'D', 'F'})));

        assertThat(lastUserMessage().content()).containsExactly(
                new TextBlock("summarize"),
                new AttachmentBlock("file_test_0001", APPLICATION_PDF, "doc.pdf",
                        AttachmentBlock.Kind.DOCUMENT));
    }

    @Test
    void chatWithTextFileSendsDocumentBlock() {
        queueText("ok");

        postChatMultipart(chatBody("review", "opus-4-8", null, null),
                List.of(new PartFile("notes.md", "text/markdown", "# hi".getBytes(UTF_8))));

        assertThat(lastUserMessage().content()).containsExactly(
                new TextBlock("review"),
                new AttachmentBlock("file_test_0001", TEXT_MARKDOWN, "notes.md",
                        AttachmentBlock.Kind.DOCUMENT));
    }

    @Test
    void imageVsDocumentSelectedByMime() {
        queueText("ok");

        postChatMultipart(chatBody("both", "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1}),
                        new PartFile("b.pdf", "application/pdf", new byte[]{2})));

        assertThat(lastUserMessage().content()).containsExactly(
                new TextBlock("both"),
                new AttachmentBlock("file_test_0001", IMAGE_PNG, "a.png",
                        AttachmentBlock.Kind.IMAGE),
                new AttachmentBlock("file_test_0002", APPLICATION_PDF, "b.pdf",
                        AttachmentBlock.Kind.DOCUMENT));
    }

    @Test
    void attachmentsPersistedInJsonl() throws IOException {
        queueText("ok");

        String cid = json(postChatMultipart(chatBody("look", "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1}))))
                .get("conversationId").asText();

        List<String> lines = Files.readAllLines(CONV_DIR.resolve(cid + ".jsonl"));
        JsonNode userRec = mapper.readTree(lines.get(0));
        assertThat(userRec.get("content").asText()).isEqualTo("look");
        JsonNode atts = userRec.get("attachments");
        assertThat(atts).isNotNull();
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).get("fileId").asText()).isEqualTo("file_test_0001");
        assertThat(atts.get(0).get("filename").asText()).isEqualTo("a.png");
        assertThat(atts.get(0).get("mediaType").asText()).isEqualTo("image/png");
        assertThat(atts.get(0).get("kind").asText()).isEqualTo("IMAGE");
    }

    @Test
    void historyResendReReferencesFileIdsWithoutReupload() {
        queueText("first");
        queueText("second");

        String cid = json(postChatMultipart(chatBody("q1 with file", "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1}))))
                .get("conversationId").asText();
        postChat(chatBody("q2", "opus-4-8", null, cid));

        // Only one upload ever happened — the resent history re-references the file_id.
        assertThat(uploads).hasSize(1);
        GatewayCall secondCall = gatewayCalls.get(1);
        ApiMessage firstUser = secondCall.messages().get(0);
        assertThat(firstUser.content()).containsExactly(
                new TextBlock("q1 with file"),
                new AttachmentBlock("file_test_0001", IMAGE_PNG, "a.png",
                        AttachmentBlock.Kind.IMAGE));
    }

    @Test
    void fileOnlyTurnIsAllowed() {
        queueText("I see it.");

        ResponseEntity<String> resp = postChatMultipart(
                chatBody(null, "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1})));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Just the image block — no empty text block when there's no message.
        assertThat(lastUserMessage().content()).containsExactly(
                new AttachmentBlock("file_test_0001", IMAGE_PNG, "a.png",
                        AttachmentBlock.Kind.IMAGE));
    }

    // ----------------------------------------------------------------------- //
    // Zip expansion + upload security
    // ----------------------------------------------------------------------- //

    @Test
    void zipExpandsIntoIndividualAttachmentsIncludingNested() {
        queueText("ok");
        byte[] zip = zip("top.txt", "a", "sub/nested.txt", "b");

        postChatMultipart(chatBody("here", "opus-4-8", null, null),
                List.of(new PartFile("bundle.zip", "application/zip", zip)));

        // Two files uploaded (including the nested one); the zip itself is not uploaded.
        // The nested entry is flattened to its basename so the Files API filename has no '/'.
        assertThat(uploads).hasSize(2);
        assertThat(uploads.stream().map(FakeUpload::filename))
                .containsExactly("top.txt", "nested.txt");
        assertThat(lastUserMessage().content()).containsExactly(
                new TextBlock("here"),
                new AttachmentBlock("file_test_0001", TEXT_PLAIN, "top.txt",
                        AttachmentBlock.Kind.DOCUMENT),
                new AttachmentBlock("file_test_0002", TEXT_PLAIN, "nested.txt",
                        AttachmentBlock.Kind.DOCUMENT));
    }

    @Test
    void filenameWithForbiddenCharactersIsSanitizedBeforeUpload() {
        queueText("ok");

        // Accented + punctuation the Files API rejects: diacritics fold to ASCII,
        // ':' becomes '_'. So "café: report.png" -> "cafe_ report.png".
        postChatMultipart(chatBody("what is this?", "opus-4-8", null, null),
                List.of(new PartFile("café: report.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'})));

        assertThat(uploads).singleElement().satisfies(u ->
                assertThat(u.filename()).isEqualTo("cafe_ report.png"));
        assertThat(lastUserMessage().content()).containsExactly(
                new TextBlock("what is this?"),
                new AttachmentBlock("file_test_0001", IMAGE_PNG, "cafe_ report.png",
                        AttachmentBlock.Kind.IMAGE));
    }

    @Test
    void zipSkipsMacOsMetadataEntries() {
        queueText("ok");
        // A Finder-created zip bundles a __MACOSX/ tree of AppleDouble (._*) resource-fork
        // files plus .DS_Store — none of which are real attachments.
        byte[] zip = zip(
                "__MACOSX/._photo.png", "appledouble-junk",
                ".DS_Store", "ds-junk",
                "docs/._notes.txt", "appledouble-junk",
                "photo.png", "real");

        postChatMultipart(chatBody("here", "opus-4-8", null, null),
                List.of(new PartFile("bundle.zip", "application/zip", zip)));

        // Only the genuine file is uploaded; the macOS cruft is silently dropped.
        assertThat(uploads).singleElement().satisfies(u ->
                assertThat(u.filename()).isEqualTo("photo.png"));
    }

    @Test
    void zipSlipEntryRejectedWith400() {
        byte[] zip = zip("../evil.txt", "x");

        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("bad.zip", "application/zip", zip)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void nestedZipRejectedWith400() {
        byte[] zip = zip("inner.zip", "whatever");

        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("outer.zip", "application/zip", zip)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void zipTotalExtractedSizeCapReturns400() {
        String oneKb = "a".repeat(1000);
        String[] pairs = new String[18]; // 9 entries × 1000 bytes = 9000 > 8192 cap
        for (int i = 0; i < 9; i++) {
            pairs[i * 2] = "e" + i + ".txt";
            pairs[i * 2 + 1] = oneKb;
        }
        byte[] zip = zip(pairs);

        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("big.zip", "application/zip", zip)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void disallowedEntryInsideZipRejectsWholeRequest() {
        byte[] zip = zip("ok.txt", "x", "bad.exe", "y");

        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("mix.zip", "application/zip", zip)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void fileTooLargeReturns400() {
        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("big.txt", "text/plain", new byte[2000])));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void tooManyFilesReturns400() {
        List<PartFile> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new PartFile("f" + i + ".png", "image/png", new byte[]{1}));
        }

        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null), six);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void disallowedTypeReturns400() {
        ResponseEntity<String> resp = postChatMultipart(chatBody("m", "opus-4-8", null, null),
                List.of(new PartFile("bad.exe", "application/octet-stream", new byte[]{1})));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(uploads).isEmpty();
    }

    @Test
    void archivingConversationDeletesUploadedFiles() {
        queueText("ok");
        String cid = json(postChatMultipart(chatBody("look", "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1}))))
                .get("conversationId").asText();

        rest.exchange(url("/api/conversations/" + cid), HttpMethod.DELETE, null, String.class);

        assertThat(deleted).containsExactly("file_test_0001");
    }

    @Test
    void getConversationReturnsAttachmentMetadata() {
        queueText("ok");
        String cid = json(postChatMultipart(chatBody("look", "opus-4-8", null, null),
                List.of(new PartFile("a.png", "image/png", new byte[]{1}))))
                .get("conversationId").asText();

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));
        JsonNode atts = messages.get(0).get("attachments");
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).get("fileId").asText()).isEqualTo("file_test_0001");
        assertThat(atts.get(0).get("filename").asText()).isEqualTo("a.png");
        assertThat(atts.get(0).get("mediaType").asText()).isEqualTo("image/png");
        assertThat(atts.get(0).get("kind").asText()).isEqualTo("IMAGE");
        // Assistant turn carries no attachments.
        assertThat(messages.get(1).get("attachments")).isEmpty();
    }

    // ----------------------------------------------------------------------- //
    // Per-model request shaping — the "ignored if not applicable" rule
    // ----------------------------------------------------------------------- //

    @Test
    void effortCapableModelSendsThinkingAndEffort() {
        queueText("ok");

        postChat(chatBody("hi", "opus-4-8", "max", null));

        GatewayCall sent = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(sent.modelId()).isEqualTo("claude-opus-4-8");
        assertThat(sent.maxTokens()).isEqualTo(16000);
        assertThat(sent.extraBody()).isEqualTo(Map.of(
                "thinking", Map.of("type", "adaptive"),
                "output_config", Map.of("effort", "max")));
    }

    @Test
    void haikuOmitsEffortAndThinking() {
        queueText("ok");

        postChat(chatBody("hi", "haiku-4-5", "high", null));

        GatewayCall sent = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(sent.modelId()).isEqualTo("claude-haiku-4-5");
        assertThat(sent.extraBody()).isEmpty();
    }

    @Test
    void effortCapableModelWithoutEffortStillSendsThinking() {
        queueText("ok");

        postChat(chatBody("hi", "opus-4-8", null, null));

        GatewayCall sent = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(sent.extraBody()).isEqualTo(Map.of("thinking", Map.of("type", "adaptive")));
    }

    @Test
    void unknownEffortValueIsDropped() {
        queueText("ok");

        postChat(chatBody("hi", "opus-4-8", "ludicrous", null));

        GatewayCall sent = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(sent.extraBody()).isEqualTo(Map.of("thinking", Map.of("type", "adaptive")));
    }

    @Test
    void sonnetSendsThinkingAndEffort() {
        queueText("ok");

        postChat(chatBody("hi", "sonnet-4-6", "high", null));

        GatewayCall sent = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(sent.modelId()).isEqualTo("claude-sonnet-4-6");
        assertThat(sent.extraBody()).isEqualTo(Map.of(
                "thinking", Map.of("type", "adaptive"),
                "output_config", Map.of("effort", "high")));
    }

    // ----------------------------------------------------------------------- //
    // Models endpoint
    // ----------------------------------------------------------------------- //

    @Test
    void modelsEndpointListsFiveModels() {
        JsonNode body = json(rest.getForEntity(url("/api/models"), String.class));

        List<String> keys = new java.util.ArrayList<>();
        Map<String, Boolean> effortSupport = new HashMap<>();
        for (JsonNode m : body.get("models")) {
            keys.add(m.get("key").asText());
            effortSupport.put(m.get("key").asText(), m.get("supportsEffort").asBoolean());
        }
        assertThat(keys).containsExactly("sonnet-4-6", "sonnet-5", "opus-4-8", "fable-5", "haiku-4-5");
        assertThat(effortSupport.get("opus-4-8")).isTrue();
        assertThat(effortSupport.get("haiku-4-5")).isFalse();

        List<String> effortLevels = new java.util.ArrayList<>();
        body.get("effortLevels").forEach(n -> effortLevels.add(n.asText()));
        assertThat(effortLevels).containsExactly("low", "medium", "high", "max");
        assertThat(body.get("defaultModel").asText()).isEqualTo("sonnet-4-6");
    }

    @Test
    void modelsEndpointAllFieldsPerModel() {
        JsonNode body = json(rest.getForEntity(url("/api/models"), String.class));
        Map<String, JsonNode> byKey = new HashMap<>();
        for (JsonNode m : body.get("models")) {
            byKey.put(m.get("key").asText(), m);
        }

        JsonNode sonnet = byKey.get("sonnet-4-6");
        assertThat(sonnet.get("id").asText()).isEqualTo("claude-sonnet-4-6");
        assertThat(sonnet.get("label").asText()).isEqualTo("Sonnet 4.6");
        assertThat(sonnet.get("supportsEffort").asBoolean()).isTrue();
        assertThat(sonnet.get("adaptiveThinking").asBoolean()).isTrue();

        JsonNode fable = byKey.get("fable-5");
        assertThat(fable.get("id").asText()).isEqualTo("claude-fable-5");
        assertThat(fable.get("label").asText()).isEqualTo("Fable 5");
        assertThat(fable.get("supportsEffort").asBoolean()).isTrue();
        assertThat(fable.get("adaptiveThinking").asBoolean()).isTrue();

        JsonNode haiku = byKey.get("haiku-4-5");
        assertThat(haiku.get("supportsEffort").asBoolean()).isFalse();
        assertThat(haiku.get("adaptiveThinking").asBoolean()).isFalse();
    }

    // ----------------------------------------------------------------------- //
    // History endpoints
    // ----------------------------------------------------------------------- //

    @Test
    void listConversationsNewestFirstWithPreview() {
        queueText("a1");
        queueText("a2");

        postChat(chatBody("older question", "opus-4-8", null, null));
        postChat(chatBody("newer question", "opus-4-8", null, null));

        JsonNode items = json(rest.getForEntity(url("/api/conversations"), String.class));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("preview").asText()).isEqualTo("newer question");
        assertThat(items.get(1).get("preview").asText()).isEqualTo("older question");
        items.forEach(item -> assertThat(item.get("conversationId").asText()).isNotBlank());
    }

    @Test
    void getConversationRehydratesMessages() {
        queueText("the answer");
        String cid = json(postChat(chatBody("the question", "sonnet-4-6", null, null)))
                .get("conversationId").asText();

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));

        assertThat(messages.findValuesAsText("role")).containsExactly("user", "assistant");
        assertThat(messages.get(0).get("content").asText()).isEqualTo("the question");
        assertThat(messages.get(1).get("model").asText()).isEqualTo("sonnet-4-6");
    }

    @Test
    void historyRoleFieldIsLowercase() {
        queueText("the answer");
        String cid = json(postChat(chatBody("the question", "sonnet-4-6", null, null)))
                .get("conversationId").asText();

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));

        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
    }

    @Test
    void getUnknownConversationReturns404() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/conversations/does-not-exist"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ----------------------------------------------------------------------- //
    // Error handling
    // ----------------------------------------------------------------------- //

    @Test
    void unknownModelReturns400() {
        ResponseEntity<String> resp = postChat(chatBody("hi", "gpt-9000", null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("gpt-9000");
    }

    @Test
    void unknownRouteReturns404() {
        ResponseEntity<String> resp = rest.getForEntity(url("/no-such-route"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).get("message").asText()).isNotBlank();
    }

    @Test
    void upstreamErrorPropagatesAs500() {
        doThrow(new RuntimeException("upstream boom")).when(gateway).createMessage(any(), anyInt(), any(), any(), any());

        ResponseEntity<String> resp = postChat(chatBody("hi", "opus-4-8", null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(resp).get("message").asText()).contains("upstream boom");
    }

    @Test
    void anthropicTimeoutPropagatesAs500() {
        doThrow(new RuntimeException("Request timed out")).when(gateway).createMessage(any(), anyInt(), any(), any(), any());

        ResponseEntity<String> resp = postChat(chatBody("hi", "opus-4-8", null, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ----------------------------------------------------------------------- //
    // Input validation (idiomatic Spring → 400, where FastAPI returned 422)
    // ----------------------------------------------------------------------- //

    @Test
    void missingMessageFieldReturns400() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "opus-4-8");
        ResponseEntity<String> resp = rest.postForEntity(url("/api/chat"), body, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void missingModelFieldUsesDefault() {
        queueText("ok");

        Map<String, Object> body = new HashMap<>();
        body.put("message", "hi");
        ResponseEntity<String> resp = rest.postForEntity(url("/api/chat"), body, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("model").asText()).isEqualTo("sonnet-4-6");
    }

    @Test
    void emptyMessageReturns400() {
        ResponseEntity<String> resp = postChat(chatBody("", "opus-4-8", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void whitespaceMessageReturns400() {
        ResponseEntity<String> resp = postChat(chatBody("   ", "opus-4-8", null, null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ----------------------------------------------------------------------- //
    // Conversation depth + model switching
    // ----------------------------------------------------------------------- //

    @Test
    void threeTurnConversationResendsFullHistory() {
        queueText("a1");
        queueText("a2");
        queueText("a3");

        String cid = json(postChat(chatBody("q1", "opus-4-8", null, null)))
                .get("conversationId").asText();
        postChat(chatBody("q2", "opus-4-8", null, cid));
        postChat(chatBody("q3", "opus-4-8", null, cid));

        assertThat(store.loadMessages(cid)).hasSize(6);
        assertThat(roles(gatewayCalls.get(2)))
                .containsExactly("user", "assistant", "user", "assistant", "user");
    }

    @Test
    void modelSwitchingMidConversationResendsFullHistory() {
        queueText("first");
        queueText("second");

        String cid = json(postChat(chatBody("q1", "opus-4-8", null, null)))
                .get("conversationId").asText();
        postChat(chatBody("q2", "haiku-4-5", null, cid));

        GatewayCall secondCall = gatewayCalls.get(1);
        assertThat(secondCall.modelId()).isEqualTo("claude-haiku-4-5");
        assertThat(secondCall.extraBody()).isEmpty();
        assertThat(roles(secondCall)).containsExactly("user", "assistant", "user");
    }

    // ----------------------------------------------------------------------- //
    // Security
    // ----------------------------------------------------------------------- //

    @Test
    void pathTraversalConversationIdIsRejected() {
        // Decoded this is "../../etc/passwd". Tomcat rejects the encoded slash
        // before routing (400); the store's basename guard would otherwise yield a
        // 404. Either way it is a client error and never serves a file outside the
        // store. (FastAPI returned 404; both are safe.)
        ResponseEntity<String> resp = rest.getForEntity(
                url("/api/conversations/..%2F..%2Fetc%2Fpasswd"), String.class);
        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ----------------------------------------------------------------------- //
    // Soft-delete / archive
    // ----------------------------------------------------------------------- //

    @Test
    void deleteConversationArchivesAndRemoves() throws IOException {
        queueText("an answer");
        String cid = json(postChat(chatBody("a question", "opus-4-8", null, null)))
                .get("conversationId").asText();
        String original = Files.readString(CONV_DIR.resolve(cid + ".jsonl"));

        ResponseEntity<String> resp = rest.exchange(
                url("/api/conversations/" + cid), HttpMethod.DELETE, null, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("conversationId").asText()).isEqualTo(cid);
        // Original removed from the live store.
        assertThat(store.loadMessages(cid)).isEmpty();
        assertThat(Files.exists(CONV_DIR.resolve(cid + ".jsonl"))).isFalse();
        // Compressed copy round-trips to the original.
        Path gz = CONV_DIR.resolve("archive").resolve(cid + ".jsonl.gz");
        assertThat(Files.exists(gz)).isTrue();
        try (var in = new GZIPInputStream(Files.newInputStream(gz))) {
            assertThat(new String(in.readAllBytes(), UTF_8)).isEqualTo(original);
        }
        // No longer in the sidebar listing.
        assertThat(json(rest.getForEntity(url("/api/conversations"), String.class))).isEmpty();
    }

    @Test
    void deleteUnknownConversationReturns404() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/conversations/deadbeefdeadbeefdeadbeefdeadbeef"),
                HttpMethod.DELETE, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteAllConversationsBundlesAndClears() throws IOException {
        queueText("a1");
        queueText("a2");
        String cid1 = json(postChat(chatBody("first", "opus-4-8", null, null)))
                .get("conversationId").asText();
        String cid2 = json(postChat(chatBody("second", "opus-4-8", null, null)))
                .get("conversationId").asText();

        ResponseEntity<String> resp = rest.exchange(
                url("/api/conversations"), HttpMethod.DELETE, null, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("count").asInt()).isEqualTo(2);
        assertThat(json(rest.getForEntity(url("/api/conversations"), String.class))).isEmpty();
        try (var paths = Files.list(CONV_DIR)) {
            assertThat(paths.filter(p -> p.toString().endsWith(".jsonl")).toList()).isEmpty();
        }
        Path archiveDir = CONV_DIR.resolve("archive");
        List<Path> bundles;
        try (var paths = Files.list(archiveDir)) {
            bundles = paths.filter(p -> p.getFileName().toString().startsWith("all-")
                    && p.toString().endsWith(".zip")).toList();
        }
        assertThat(bundles).hasSize(1);
        try (ZipFile zf = new ZipFile(bundles.get(0).toFile())) {
            Set<String> names = zf.stream().map(ZipEntry::getName).collect(Collectors.toSet());
            assertThat(names).containsExactlyInAnyOrder(cid1 + ".jsonl", cid2 + ".jsonl");
        }
    }

    @Test
    void deleteAllWhenEmptyReturnsZero() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/conversations"), HttpMethod.DELETE, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("count").asInt()).isEqualTo(0);
    }

    // ----------------------------------------------------------------------- //
    // Coach sessions
    // ----------------------------------------------------------------------- //

    static final String OPENING_INSTRUCTION = "Розпочни співбесіду — постав лише перше запитання.";

    private static Path cooDir() {
        return COACHES_DIR.resolve("Chief Operating Officer");
    }

    private static void writeCooPrompt(String name, String content) throws IOException {
        Files.createDirectories(cooDir());
        Files.writeString(cooDir().resolve(name), content);
    }

    /** A new-chat body with a coach selected — blank message, no conversationId. */
    private static Map<String, Object> coachBody(String coachType) {
        Map<String, Object> body = chatBody("", "opus-4-8", null, null);
        body.put("coachType", coachType);
        return body;
    }

    private static Path spanishDir() {
        return COACHES_DIR.resolve("Spanish");
    }

    private static void writeSpanishTopics(String... topics) throws IOException {
        var dir = spanishDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("temas-b1.txt"), String.join("\n", topics), UTF_8);
    }

    private static void writeSpanishTopicsByLevel(List<String> b1, List<String> b2) throws IOException {
        var dir = spanishDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("temas-b1.txt"), String.join("\n", b1), UTF_8);
        Files.writeString(dir.resolve("temas-b2.txt"), String.join("\n", b2), UTF_8);
    }

    /** New-Spanish-chat body; topic omitted when null. */
    private static Map<String, Object> spanishBody(String topic, String words) {
        Map<String, Object> body = chatBody(words != null ? words : "", "sonnet-4-6", null, null);
        body.put("coachType", "spanish");
        if (topic != null) body.put("topic", topic);
        return body;
    }

    private static Path claudeDir() {
        return COACHES_DIR.resolve("Claude");
    }

    private static void writeClaudePrompt(String name, String content) throws IOException {
        var dir = claudeDir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, UTF_8);
    }

    /** New-Claude-Architect-chat body; topic omitted when null. */
    private static Map<String, Object> claudeBody(String topic) {
        Map<String, Object> body = chatBody("", "sonnet-4-6", null, null);
        body.put("coachType", "claude-architect");
        if (topic != null) body.put("topic", topic);
        return body;
    }

    // ── Commit 1: Serve Claude Architect topics from coaches/Claude prompt files ── //

    @Test
    void claudeTopicsEndpointReturnsPromptFileStemsInOrder() throws IOException {
        writeClaudePrompt("2.1 Tool interface design.md", "content");
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        writeClaudePrompt("README.md", "index");
        writeClaudePrompt("notes.txt", "ignored");

        var resp = rest.getForEntity(url("/api/coaches/claude-architect/topics"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).asText()).isEqualTo("1.1 Agentic loops");
        assertThat(body.get(1).asText()).isEqualTo("2.1 Tool interface design");
    }

    @Test
    void claudeTopicsEndpointWithMissingDirReturns500() {
        var resp = rest.getForEntity(url("/api/coaches/claude-architect/topics"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(resp).get("message").asText()).contains("topics");
    }

    // ── Commit 2: Start Claude Architect quiz chats from a selected topic ─── //

    @Test
    void claudeChatStartsQuizWithPersonaAndTopicPrompt() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "STOP_REASON DRILL");

        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("Claude Certified Architect");
        assertThat(call.system()).contains("MANDATORY format");
        assertThat(call.system()).contains("STOP_REASON DRILL");
        assertThat(roles(call)).containsExactly("user");
        assertThat(textOf(lastUserMessage())).isEqualTo("Ask me the first exam question.");

        var cid = json(resp).get("conversationId").asText();
        var history = rest.getForEntity(url("/api/conversations/" + cid), String.class);
        assertThat(json(history).get(0).get("content").asText()).isEqualTo("Ask me the first exam question.");
    }

    @Test
    void claudeChatPersistsPromptFileMetaSidecar() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");

        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var cid = json(resp).get("conversationId").asText();
        var meta = store.coachMeta(cid);
        assertThat(meta).isPresent();
        assertThat(meta.get().coachType().value()).isEqualTo("claude-architect");
        assertThat(meta.get().promptFile()).isEqualTo("1.1 Agentic loops.md");
        assertThat(meta.get().topic()).isNull();
    }

    @Test
    void claudeConversationListItemShowsTopicPreview() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");

        postChat(claudeBody("1.1 Agentic loops"));

        var list = rest.getForEntity(url("/api/conversations"), String.class);
        var item = json(list).get(0);
        assertThat(item.get("preview").asText()).isEqualTo("Claude · 1.1 Agentic loops");
        assertThat(item.get("coachType").asText()).isEqualTo("claude-architect");
    }

    @Test
    void claudeFollowUpIsPassthroughWithPersonaSystemPrompt() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "STOP_REASON DRILL");

        var startResp = json(postChat(claudeBody("1.1 Agentic loops")));
        var cid = startResp.get("conversationId").asText();

        var followUp = chatBody("Next question.", "sonnet-4-6", null, cid);
        postChat(followUp);

        var call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("Claude Certified Architect");
        assertThat(call.system()).contains("STOP_REASON DRILL");
        assertThat(roles(call)).containsExactly("user", "assistant", "user");
        assertThat(textOf(lastUserMessage())).isEqualTo("Next question.");
    }

    @Test
    void claudeChatWithoutTopicReturns400() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");

        var resp = postChat(claudeBody(null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("topic");
        assertThat(CONV_DIR.toFile().list()).isEmpty();
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void claudeChatWithUnknownTopicReturns400() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");

        var resp = postChat(claudeBody("No such topic"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("No such topic");
        assertThat(CONV_DIR.toFile().list()).isEmpty();
    }

    @Test
    void claudeChatWithNonBlankMessageReturns400() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        var body = claudeBody("1.1 Agentic loops");
        body.put("message", "hi");

        var resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("blank");
        assertThat(CONV_DIR.toFile().list()).isEmpty();
    }

    @Test
    void claudeChatOnExistingConversationReturns400() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        var body = claudeBody("1.1 Agentic loops");
        body.put("conversationId", "existing123");

        var resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("new chat");
    }

    @Test
    void claudeChatWithMissingPromptsDirReturns500() {
        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(CONV_DIR.toFile().list()).isEmpty();
        assertThat(gatewayCalls).isEmpty();
    }

    // ── Direct each new quiz's first question at a random topic bullet ── //

    private static final List<String> QUIZ_BULLETS = List.of(
            "MCP server scoping: project-level vs user-level.",
            "Environment variable expansion in .mcp.json for credential management.",
            "Simultaneous tool discovery at connection time.",
            "Configuring shared MCP servers with token expansion.",
            "Exposing content catalogs as MCP resources.");

    /** A topic blueprint whose only {@code - } lines are the five known bullets. */
    private static final String BULLETED_BLUEPRINT = """
            # 2.4 MCP server integration

            Test knowledge of:
            - MCP server scoping: project-level vs user-level.
            - Environment variable expansion in .mcp.json for credential management.
            - Simultaneous tool discovery at connection time.

            Test skills in:
            - Configuring shared MCP servers with token expansion.
            - Exposing content catalogs as MCP resources.
            """;

    @Test
    void claudeChatOpeningTurnTargetsOneTopicBullet() throws IOException {
        writeClaudePrompt("2.4 MCP server integration.md", BULLETED_BLUEPRINT);

        var resp = postChat(claudeBody("2.4 MCP server integration"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var opening = textOf(lastUserMessage());
        assertThat(opening).startsWith("Ask me the first exam question.");
        assertThat(QUIZ_BULLETS).filteredOn(opening::endsWith).hasSize(1);

        var cid = json(resp).get("conversationId").asText();
        var history = rest.getForEntity(url("/api/conversations/" + cid), String.class);
        assertThat(json(history).get(0).get("content").asText()).isEqualTo(opening);
    }

    @Test
    void claudeChatOpeningBulletVariesAcrossChats() throws IOException {
        writeClaudePrompt("2.4 MCP server integration.md", BULLETED_BLUEPRINT);

        var seen = new HashSet<String>();
        for (int i = 0; i < 25; i++) {
            postChat(claudeBody("2.4 MCP server integration"));
            var opening = textOf(lastUserMessage());
            QUIZ_BULLETS.stream().filter(opening::endsWith).forEach(seen::add);
        }

        // 25 draws over 5 bullets land on a single one with probability 5^-24.
        assertThat(seen).hasSizeGreaterThan(1);
    }

    // ── Ground Claude Architect questions in official docs (front-matter sources) ── //

    private static final String DOC_URL_TOOLS =
            "https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview.md";
    private static final String DOC_URL_STOP =
            "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";

    /** A topic blueprint declaring two official-doc sources in its front-matter. */
    private static final String SOURCED_BLUEPRINT = """
            <!-- sources:
            %s
            %s
            -->
            STOP_REASON DRILL""".formatted(DOC_URL_TOOLS, DOC_URL_STOP);

    /** Script the docs fetch to return a distinct body per URL, recording each call. */
    private void scriptDocFetch() {
        doAnswer(inv -> {
            URI url = inv.getArgument(0);
            docFetches.add(url);
            return url.toString().contains("stop-reasons")
                    ? "STOP REASONS DOC BODY" : "TOOL USE DOC BODY";
        }).when(docFetchGateway).fetch(any());
    }

    /** Push every doc snapshot's mtime {@code age} into the past. */
    private static void ageSnapshots(Duration age) throws IOException {
        var past = FileTime.from(Instant.now().minus(age));
        try (var snapshots = Files.list(claudeDir().resolve("docs"))) {
            for (Path snapshot : snapshots.toList())
                Files.setLastModifiedTime(snapshot, past);
        }
    }

    private String lastSystem() {
        return gatewayCalls.get(gatewayCalls.size() - 1).system();
    }

    @Test
    void claudeChatStuffsOfficialDocsIntoSystemPrompt() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();

        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastSystem()).containsSubsequence(
                "Claude Certified Architect",
                "STOP_REASON DRILL",
                "=== OFFICIAL DOCUMENTATION (source of truth): " + DOC_URL_TOOLS + " ===",
                "TOOL USE DOC BODY",
                "=== OFFICIAL DOCUMENTATION (source of truth): " + DOC_URL_STOP + " ===",
                "STOP REASONS DOC BODY");
        assertThat(lastSystem()).doesNotContain("<!-- sources:");
    }

    @Test
    void claudeChatWithoutSourcesLeavesSystemPromptAsBefore() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "STOP_REASON DRILL");

        postChat(claudeBody("1.1 Agentic loops"));

        assertThat(lastSystem()).endsWith("STOP_REASON DRILL");
        assertThat(lastSystem()).doesNotContain("OFFICIAL DOCUMENTATION");
        verifyNoInteractions(docFetchGateway);
    }

    @Test
    void claudeChatSurvivesDocFetchFailure() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        doThrow(new RuntimeException("docs down")).when(docFetchGateway).fetch(any());

        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastSystem()).contains("STOP_REASON DRILL");
        assertThat(lastSystem()).doesNotContain("OFFICIAL DOCUMENTATION");
        assertThat(lastSystem()).doesNotContain("<!-- sources:");
    }

    @Test
    void claudeFollowUpResendsSameDocs() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();

        var cid = json(postChat(claudeBody("1.1 Agentic loops"))).get("conversationId").asText();
        postChat(chatBody("Next question.", "sonnet-4-6", null, cid));

        var first = gatewayCalls.get(gatewayCalls.size() - 2).system();
        assertThat(first).contains("TOOL USE DOC BODY");
        assertThat(lastSystem()).isEqualTo(first);
    }

    @Test
    void claudeSecondConversationReusesDocSnapshots() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();

        postChat(claudeBody("1.1 Agentic loops"));
        postChat(claudeBody("1.1 Agentic loops"));

        assertThat(docFetches).hasSize(2); // one per URL, not per conversation
        assertThat(lastSystem()).contains("TOOL USE DOC BODY");
        try (var snapshots = Files.list(claudeDir().resolve("docs"))) {
            assertThat(snapshots.count()).isEqualTo(2);
        }
    }

    @Test
    void claudeExpiredSnapshotIsRefetched() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        postChat(claudeBody("1.1 Agentic loops"));

        ageSnapshots(Duration.ofHours(25)); // past the 24h TTL
        postChat(claudeBody("1.1 Agentic loops"));

        assertThat(docFetches).hasSize(4);
        assertThat(lastSystem()).contains("TOOL USE DOC BODY");
    }

    @Test
    void claudeStaleSnapshotServesWhenDocFetchFails() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", SOURCED_BLUEPRINT);
        scriptDocFetch();
        postChat(claudeBody("1.1 Agentic loops"));

        ageSnapshots(Duration.ofHours(25));
        doThrow(new RuntimeException("docs down")).when(docFetchGateway).fetch(any());
        var resp = postChat(claudeBody("1.1 Agentic loops"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastSystem()).contains("TOOL USE DOC BODY");
        assertThat(lastSystem()).contains("STOP REASONS DOC BODY");
    }

    @Test
    void claudeChatTruncatesOversizedDocs() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", """
                <!-- sources:
                %s
                -->
                STOP_REASON DRILL""".formatted(DOC_URL_TOOLS));
        doAnswer(inv -> "X".repeat(50_000)).when(docFetchGateway).fetch(any());

        postChat(claudeBody("1.1 Agentic loops"));

        assertThat(lastSystem()).contains("[truncated]");
        assertThat(lastSystem().length()).isLessThan(20_000);
    }

    @Test
    void spanishTopicsEndpointReturnsSectionsByLevelInFileOrder() throws IOException {
        writeSpanishTopicsByLevel(
                List.of("Ser y estar", "", "  Por y para  "),
                List.of("Voz pasiva", "  ", "Estilo indirecto"));

        ResponseEntity<String> resp = rest.getForEntity(url("/api/coaches/spanish/topics"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("level").asText()).isEqualTo("B1");
        assertThat(body.get(0).get("topics")).hasSize(2);
        assertThat(body.get(0).get("topics").get(0).asText()).isEqualTo("Ser y estar");
        assertThat(body.get(0).get("topics").get(1).asText()).isEqualTo("Por y para");
        assertThat(body.get(1).get("level").asText()).isEqualTo("B2");
        assertThat(body.get(1).get("topics")).hasSize(2);
        assertThat(body.get(1).get("topics").get(0).asText()).isEqualTo("Voz pasiva");
        assertThat(body.get(1).get("topics").get(1).asText()).isEqualTo("Estilo indirecto");
    }

    @Test
    void spanishTopicsEndpointWithMissingFileReturns500() {
        // No temas.txt written — spanishDir may not even exist.
        ResponseEntity<String> resp = rest.getForEntity(url("/api/coaches/spanish/topics"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(resp).get("message").asText()).contains("topics");
    }

    // ── Commit 2: Spanish chat with topic and composed practice prompt ─────── //

    @Test
    void spanishChatComposesPracticePromptFromTopicAndWords() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");

        var resp = postChat(spanishBody("Ser y estar", "caber, cavar pala"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cid = json(resp).get("conversationId").asText();

        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("tutor de español").contains("entre paréntesis").contains("Ser y estar");
        assertThat(roles(call)).containsExactly("user");
        String userText = textOf(call.messages().get(0));
        assertThat(userText).contains("Quiero practicar «Ser y estar»");
        assertThat(userText).contains("caber, cavar pala");
        assertThat(userText).contains("una oración en inglés por cada palabra o expresión");
        assertThat(userText).contains("Baraja el orden");
        assertThat(userText).doesNotContain("oraciones en inglés"); // no backend-computed count

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));
        String persisted = messages.get(0).get("content").asText();
        assertThat(persisted).contains("Quiero practicar «Ser y estar»");
        assertThat(persisted).contains("caber, cavar pala");
        assertThat(persisted).contains("una oración en inglés por cada palabra o expresión");
    }

    @Test
    void spanishChatWithAttachmentOnlyReferencesAttachedFile() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");

        var req = spanishBody("Ser y estar", null);
        var resp = postChatMultipart(req, List.of(
                new PartFile("words.txt", "text/plain", "caber\ncavar".getBytes(UTF_8))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        String userText = textOf(call.messages().get(0));
        assertThat(userText).contains("las palabras del archivo adjunto");
        assertThat(userText).contains("una oración en inglés por cada palabra");
        assertThat(call.messages().get(0).content()).hasSize(2); // TextBlock + AttachmentBlock
    }

    @Test
    void spanishChatWithWordsAndAttachmentPrefersTypedWords() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");

        var req = spanishBody("Ser y estar", "caber");
        var resp = postChatMultipart(req, List.of(
                new PartFile("words.txt", "text/plain", "other\nwords".getBytes(UTF_8))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        String userText = textOf(call.messages().get(0));
        assertThat(userText).contains("las siguientes palabras:").contains("caber");
        assertThat(userText).contains("una oración en inglés por cada palabra o expresión");
        assertThat(call.messages().get(0).content()).hasSize(2); // attachment still sent
    }

    @Test
    void spanishChatPersistsTopicMetaSidecar() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");

        String cid = json(postChat(spanishBody("Ser y estar", "caber"))).get("conversationId").asText();

        JsonNode meta = mapper.readTree(Files.readString(CONV_DIR.resolve(cid + ".meta.json")));
        assertThat(meta.get("coachType").asText()).isEqualTo("spanish");
        assertThat(meta.get("topic").asText()).isEqualTo("Ser y estar");
        assertThat(meta.has("promptFile")).isFalse();
    }

    @Test
    void spanishConversationListItemShowsTopicPreview() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");

        postChat(spanishBody("Ser y estar", "caber"));

        JsonNode items = json(rest.getForEntity(url("/api/conversations"), String.class));
        assertThat(items.get(0).get("preview").asText()).isEqualTo("Español · Ser y estar");
        assertThat(items.get(0).get("coachType").asText()).isEqualTo("spanish");
    }

    @Test
    void topiclessSpanishConversationShowsVocabularioPreview() throws IOException {
        // A 字 word-list seeded 語 session has no topic — the sidebar must still read meaningfully.
        queueText("(cráneo) Use this skull.\n(pala) The shovel is big.");

        postChat(spanishBody(null, "cráneo, pala"));

        JsonNode items = json(rest.getForEntity(url("/api/conversations"), String.class));
        assertThat(items.get(0).get("preview").asText()).isEqualTo("Español · Vocabulario");
        assertThat(items.get(0).get("coachType").asText()).isEqualTo("spanish");
    }

    @Test
    void spanishFollowUpIsPassthroughWithPersonaSystemPrompt() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) Only this matches.");
        String cid = json(postChat(spanishBody("Ser y estar", "caber"))).get("conversationId").asText();

        queueText("Muy bien.");
        Map<String, Object> followUp = chatBody("Mi traducción.", "sonnet-4-6", null, cid);
        postChat(followUp);

        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("tutor de español");
        assertThat(roles(call)).containsExactly("user", "assistant", "user");
        assertThat(textOf(call.messages().get(2))).isEqualTo("Mi traducción.");
    }

    @Test
    void spanishChatWithoutTopicReturns400() throws IOException {
        // Topic-less Spanish chat is now valid — used for 字 word-list seeding into a 語 session.
        queueText("(caber) I'm surprised.\n(pala) The shovel.");

        var body = spanishBody(null, "caber, pala");
        var resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("conversationId").asText()).isNotBlank();
        assertThat(gatewayCalls).hasSize(1);
    }

    @Test
    void spanishChatWithUnknownTopicReturns400() throws IOException {
        writeSpanishTopics("Ser y estar");

        var resp = postChat(spanishBody("No existe", "caber"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("No existe");
        try (var paths = Files.list(CONV_DIR)) {
            assertThat(paths.toList()).isEmpty();
        }
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void spanishChatWithBlankMessageAndNoFilesReturns400() throws IOException {
        writeSpanishTopics("Ser y estar");

        var resp = postChat(spanishBody("Ser y estar", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        try (var paths = Files.list(CONV_DIR)) {
            assertThat(paths.toList()).isEmpty();
        }
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void spanishChatOnExistingConversationReturns400() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("ok");
        String cid = json(postChat(chatBody("hello", "sonnet-4-6", null, null))).get("conversationId").asText();

        var body = spanishBody("Ser y estar", "caber");
        body.put("conversationId", cid);
        var resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("new chat");
    }

    @Test
    void topicOnNonSpanishChatReturns400() throws IOException {
        var plain = chatBody("hello", "sonnet-4-6", null, null);
        plain.put("topic", "Ser y estar");
        assertThat(postChat(plain).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        writeSpanishTopics("Ser y estar");
        writeCooPrompt("01-prd.md", "SCENARIO");
        var coo = coachBody("chief-operating-officer");
        coo.put("topic", "Ser y estar");
        assertThat(postChat(coo).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void spanishChatWithMissingTopicsFileReturns500() {
        var resp = postChat(spanishBody("Ser y estar", "caber"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(gatewayCalls).isEmpty();
    }

    // ── Commit 3: Parse Spanish tutor sentence lists ───────────────────────── //

    @Test
    void spanishChatResponseParsesSentencesFromAssistantReply() throws IOException {
        writeSpanishTopics("Ser y estar");
        // blank line between, caber repeated, multi-word hint
        String reply = "(caber) I'm surprised that the shovel hasn't fit in the car.\n"
                + "\n"
                + "(caber) He doubted that it would fit.\n"
                + "(cavar, pala) It's a shame that they haven't finished digging the hole yet.";
        queueText(reply);

        JsonNode resp = json(postChat(spanishBody("Ser y estar", "caber, caber, cavar")));

        assertThat(resp.get("sentences").isArray()).isTrue();
        assertThat(resp.get("sentences")).hasSize(3);
        assertThat(resp.get("sentences").get(0).get("hint").asText()).isEqualTo("caber");
        assertThat(resp.get("sentences").get(0).get("sentence").asText())
                .isEqualTo("I'm surprised that the shovel hasn't fit in the car.");
        assertThat(resp.get("sentences").get(1).get("hint").asText()).isEqualTo("caber");
        assertThat(resp.get("sentences").get(2).get("hint").asText()).isEqualTo("cavar, pala");
        assertThat(resp.get("sentences").get(2).get("sentence").asText())
                .isEqualTo("It's a shame that they haven't finished digging the hole yet.");
    }

    @Test
    void spanishFollowUpResponseParsesSentencesToo() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("(caber) First sentence.");
        String cid = json(postChat(spanishBody("Ser y estar", "caber"))).get("conversationId").asText();

        queueText("(volver) She has become nervous.");
        JsonNode resp = json(postChat(chatBody("Mi traducción.", "sonnet-4-6", null, cid)));

        assertThat(resp.get("sentences")).isNotNull();
        assertThat(resp.get("sentences")).hasSize(1);
        assertThat(resp.get("sentences").get(0).get("hint").asText()).isEqualTo("volver");
    }

    @Test
    void spanishMixedContentReplyHasNullSentences() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("¡Muy bien!\n(caber) Only this matches.");

        JsonNode resp = json(postChat(spanishBody("Ser y estar", "caber")));

        assertThat(resp.get("sentences").isNull()).isTrue();
    }

    @Test
    void plainChatResponseHasNullSentencesEvenWhenReplyMatchesFormat() {
        queueText("(caber) I'm surprised that the shovel hasn't fit in the car.");

        JsonNode resp = json(postChat(chatBody("hello", "sonnet-4-6", null, null)));

        assertThat(resp.get("sentences").isNull()).isTrue();
    }

    @Test
    void getSpanishConversationDerivesSentencesForAssistantMessages() throws IOException {
        writeSpanishTopics("Ser y estar");
        String reply = "(caber) I'm surprised.\n(pala) It's a shame.";
        queueText(reply);
        String cid = json(postChat(spanishBody("Ser y estar", "caber, pala"))).get("conversationId").asText();

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));

        // user message: no sentences
        assertThat(messages.get(0).get("sentences").isNull()).isTrue();
        // assistant message: parsed
        assertThat(messages.get(1).get("sentences").isArray()).isTrue();
        assertThat(messages.get(1).get("sentences")).hasSize(2);

        // raw JSONL must not contain "sentences" key
        String jsonl = Files.readString(CONV_DIR.resolve(cid + ".jsonl"));
        assertThat(jsonl).doesNotContain("\"sentences\"");
    }

    @Test
    void getCooConversationHasNullSentences() throws IOException {
        writeCooPrompt("01-prd.md", "SCENARIO");
        String reply = "(caber) I'm surprised that the shovel hasn't fit in the car.";
        queueText(reply);
        String cid = json(postChat(coachBody("chief-operating-officer"))).get("conversationId").asText();

        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));

        messages.forEach(m -> assertThat(m.get("sentences").isNull()).isTrue());
    }

    @Test
    void unknownCoachTypeReturns400() {
        ResponseEntity<String> resp = postChat(coachBody("guru-9000"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("guru-9000");
    }

    @Test
    void coachChatCreatesConversationWithScenarioSystemPromptAndSyntheticUserTurn() throws IOException {
        writeCooPrompt("00-README.md", "docs only");
        writeCooPrompt("01-prd.md", "SCENARIO BODY");
        queueText("What is your first move?");

        ResponseEntity<String> resp = postChat(coachBody("chief-operating-officer"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        String cid = body.get("conversationId").asText();
        assertThat(cid).isNotBlank();
        assertThat(body.get("answer").asText()).isEqualTo("What is your first move?");

        // Claude got persona + whole scenario file as system, and only the synthetic turn.
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("Chief Operating Officer").contains("SCENARIO BODY");
        assertThat(roles(call)).containsExactly("user");
        assertThat(textOf(call.messages().get(0))).isEqualTo(OPENING_INSTRUCTION);

        // Persisted and rehydrated as a normal visible user message.
        assertThat(Files.readAllLines(CONV_DIR.resolve(cid + ".jsonl"))).hasSize(2);
        JsonNode messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("content").asText()).isEqualTo(OPENING_INSTRUCTION);
    }

    @Test
    void cooSystemPromptEnforcesUkrainianAndBansStageDirections() throws IOException {
        writeCooPrompt("01-prd.md", "SCENARIO BODY");
        queueText("Перше запитання?");

        postChat(coachBody("chief-operating-officer"));

        // The language and style rules are part of the gateway contract on every call.
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("Ukrainian").contains("stage directions");
    }

    @Test
    void normalChatSendsNoSystemPrompt() {
        queueText("ok");

        postChat(chatBody("hi", "opus-4-8", null, null));

        assertThat(gatewayCalls.get(gatewayCalls.size() - 1).system()).isNull();
    }

    @Test
    void coachChatPersistsCoachMetaSidecar() throws IOException {
        writeCooPrompt("01-prd.md", "SCENARIO BODY");
        queueText("opening question");

        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();

        JsonNode meta = mapper.readTree(Files.readString(CONV_DIR.resolve(cid + ".meta.json")));
        assertThat(meta.get("coachType").asText()).isEqualTo("chief-operating-officer");
        assertThat(meta.get("promptFile").asText()).isEqualTo("01-prd.md");
    }

    @Test
    void coachFollowUpReusesStoredScenarioWithoutReselection() throws IOException {
        writeCooPrompt("01-prd.md", "SCENARIO BODY");
        queueText("opening question");
        queueText("follow-up answer");
        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();
        // A new scenario appearing later must not be picked up by follow-up turns.
        writeCooPrompt("99-other.md", "OTHER BODY");

        postChat(chatBody("here is my PRD", "opus-4-8", null, cid));

        GatewayCall secondCall = gatewayCalls.get(1);
        assertThat(secondCall.system()).contains("SCENARIO BODY").doesNotContain("OTHER BODY");
        assertThat(roles(secondCall)).containsExactly("user", "assistant", "user");
    }

    @Test
    void coachScenarioSelectionSkipsReadmeAndNonMarkdown() throws IOException {
        writeCooPrompt("00-README.md", "docs");
        writeCooPrompt("notes.txt", "not a scenario");
        writeCooPrompt(".hidden.md", "junk");
        writeCooPrompt("02-org.md", "ORG SCENARIO");
        queueText("opening question");

        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();

        JsonNode meta = mapper.readTree(Files.readString(CONV_DIR.resolve(cid + ".meta.json")));
        assertThat(meta.get("promptFile").asText()).isEqualTo("02-org.md");
    }

    @Test
    void coachChatWithNoEligibleScenarioReturns500() throws IOException {
        writeCooPrompt("00-README.md", "docs only");

        ResponseEntity<String> resp = postChat(coachBody("chief-operating-officer"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void coachTypeOnExistingConversationReturns400() {
        queueText("a1");
        String cid = json(postChat(chatBody("q1", "opus-4-8", null, null)))
                .get("conversationId").asText();

        Map<String, Object> body = coachBody("chief-operating-officer");
        body.put("conversationId", cid);
        ResponseEntity<String> resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(resp).get("message").asText()).contains("new chat");
        assertThat(store.loadMessages(cid)).hasSize(2);
    }

    @Test
    void coachInitWithNonBlankMessageReturns400() {
        Map<String, Object> body = coachBody("chief-operating-officer");
        body.put("message", "hi");

        ResponseEntity<String> resp = postChat(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void coachConversationListItemShowsScenarioNameAndCoachType() throws IOException {
        writeCooPrompt("03-kpi-okr.md", "SCENARIO BODY");
        queueText("opening question");
        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();

        JsonNode items = json(rest.getForEntity(url("/api/conversations"), String.class));

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("conversationId").asText()).isEqualTo(cid);
        assertThat(items.get(0).get("preview").asText()).isEqualTo("COO · kpi okr");
        assertThat(items.get(0).get("coachType").asText()).isEqualTo("chief-operating-officer");
    }

    @Test
    void plainConversationListItemHasNullCoachType() {
        queueText("ok");
        postChat(chatBody("plain question", "opus-4-8", null, null));

        JsonNode items = json(rest.getForEntity(url("/api/conversations"), String.class));

        assertThat(items.get(0).get("preview").asText()).isEqualTo("plain question");
        assertThat(items.get(0).get("coachType").isNull()).isTrue();
    }

    @Test
    void archivingCoachConversationRemovesMetaSidecar() throws IOException {
        writeCooPrompt("01-prd.md", "SCENARIO BODY");
        queueText("opening question");
        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();
        assertThat(Files.exists(CONV_DIR.resolve(cid + ".meta.json"))).isTrue();

        rest.exchange(url("/api/conversations/" + cid), HttpMethod.DELETE, null, String.class);

        assertThat(Files.exists(CONV_DIR.resolve(cid + ".meta.json"))).isFalse();
        assertThat(Files.exists(CONV_DIR.resolve("archive").resolve(cid + ".jsonl.gz"))).isTrue();
    }

    @Test
    void getFavicon_whenChatPageLoads_servesYellowBrainSvgIcon() {
        var html = rest.getForEntity(url("/"), String.class);
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html.getBody()).contains("<link rel=\"icon\" type=\"image/svg+xml\" href=\"favicon.svg\">");

        var icon = rest.getForEntity(url("/favicon.svg"), String.class);
        assertThat(icon.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(icon.getHeaders().getContentType()).hasToString("image/svg+xml");
        assertThat(icon.getBody()).contains("#FFD43B");
    }

    // ── Commit 3 (new): Parse Claude Architect question replies ────────────── //

    @Test
    void claudeChatResponseParsesQuestionIntoStemAndOptions() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        String reply = "A tricky scenario.\nWhat is the right approach?\n\nA) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.";
        queueText(reply);

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("sentences").isNull()).isTrue();
        JsonNode q = resp.get("question");
        assertThat(q).isNotNull();
        assertThat(q.get("stem").asText()).isEqualTo("A tricky scenario.\nWhat is the right approach?");
        assertThat(q.get("options")).hasSize(4);
        assertThat(q.get("options").get(0).get("letter").asText()).isEqualTo("A");
        assertThat(q.get("options").get(0).get("text").asText()).isEqualTo("Alpha.");
        assertThat(q.get("options").get(1).get("letter").asText()).isEqualTo("B");
        assertThat(q.get("options").get(2).get("letter").asText()).isEqualTo("C");
        assertThat(q.get("options").get(3).get("letter").asText()).isEqualTo("D");
        assertThat(q.get("options").get(3).get("text").asText()).isEqualTo("Delta.");
    }

    @Test
    void claudeFeedbackReplyHasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("Correct — A. Hooks give deterministic ordering of side effects.");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
        assertThat(resp.get("answer").asText()).contains("Correct");
    }

    @Test
    void claudeReplyWithTextAfterOptionsHasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("Stem line.\nA) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.\nExtra trailing line.");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    @Test
    void claudeReplyWithThreeOptionsHasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("Stem line.\nA) Alpha.\nB) Bravo.\nC) Charlie.");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    @Test
    void claudeReplyWithoutStemHasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("A) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    @Test
    void claudeReplyWithWrongLetterOrderHasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("Stem.\nA) Alpha.\nB) Bravo.\nD) Delta.\nC) Charlie.");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    @Test
    void plainChatResponseHasNullQuestionEvenWhenReplyMatchesFormat() {
        queueText("Stem.\nA) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.");

        JsonNode resp = json(postChat(chatBody("hi", "sonnet-4-6", null, null)));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    @Test
    void getClaudeConversationDerivesQuestionsForAssistantMessages() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        String questionReply = "Which is correct?\nA) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.";
        String feedbackReply = "Correct! A is right because hooks are deterministic.";
        queueText(questionReply);
        String cid = json(postChat(claudeBody("1.1 Agentic loops"))).get("conversationId").asText();
        queueText(feedbackReply);
        postChat(chatBody("A) Alpha.", "sonnet-4-6", null, cid));

        var messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));

        // user turns: question null
        assertThat(messages.get(0).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(0).get("question").isNull()).isTrue();
        // first assistant (question-shaped): question non-null
        assertThat(messages.get(1).get("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).get("question")).isNotNull();
        assertThat(messages.get(1).get("question").get("options")).hasSize(4);
        // second user: question null
        assertThat(messages.get(2).get("question").isNull()).isTrue();
        // second assistant (feedback): question null
        assertThat(messages.get(3).get("question").isNull()).isTrue();

        // Raw JSONL on disk must not contain "question"
        try (var lines = Files.lines(CONV_DIR.resolve(cid + ".jsonl"))) {
            lines.forEach(line -> assertThat(line).doesNotContain("\"question\""));
        }
    }

    @Test
    void getSpanishConversationHasNullQuestion() throws IOException {
        writeSpanishTopics("Ser y estar");
        queueText("Stem.\nA) Alpha.\nB) Bravo.\nC) Charlie.\nD) Delta.");

        var startResp = json(postChat(spanishBody("Ser y estar", "caber")));
        var cid = startResp.get("conversationId").asText();

        var messages = json(rest.getForEntity(url("/api/conversations/" + cid), String.class));
        messages.forEach(m -> assertThat(m.get("question").isNull()).isTrue());
    }

    // Issue #9: feedback reply with A-D lines at end must not be parsed as a quiz question
    @Test
    void claudeFeedbackWithTrailingOptionLines_hasNullQuestion() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");
        queueText("""
                The correct answer was A. Here is why each option is right or wrong:
                A) Hooks — correct because they provide deterministic ordering of side effects.
                B) Promises — incorrect because they are resolved asynchronously.
                C) Events — incorrect because emission order is not guaranteed.
                D) Callbacks — incorrect because they do not enforce a specific ordering.""");

        JsonNode resp = json(postChat(claudeBody("1.1 Agentic loops")));

        assertThat(resp.get("question").isNull()).isTrue();
    }

    // Issue #2: unknown coachType in a sidecar must not kill the entire sidebar listing
    @Test
    void listConversations_withUnknownCoachTypeInSidecar_doesNotCrashAndReturnsBothEntries()
            throws IOException {
        queueText("plain answer");
        String goodId = json(postChat(chatBody("hi", "opus-4-8", null, null)))
                .get("conversationId").asText();

        String badId = "corrupt-sidecar-000";
        Files.writeString(CONV_DIR.resolve(badId + ".jsonl"),
                "{\"role\":\"user\",\"content\":\"hello\",\"model\":\"opus-4-8\"}\n");
        Files.writeString(CONV_DIR.resolve(badId + ".meta.json"),
                "{\"coachType\":\"unknown-future-coach\"}");

        ResponseEntity<String> resp = rest.getForEntity(url("/api/conversations"), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var ids = new ArrayList<String>();
        json(resp).forEach(item -> ids.add(item.get("conversationId").asText()));
        assertThat(ids).contains(goodId);
        assertThat(ids).contains(badId);
    }

    // Issue #3: file attachments on a Claude Architect start must be rejected
    @Test
    void claudeArchitectChatStart_withFileAttachment_returns400() throws IOException {
        writeClaudePrompt("1.1 Agentic loops.md", "content");

        var resp = postChatMultipart(claudeBody("1.1 Agentic loops"),
                List.of(new PartFile("notes.txt", "text/plain", "data".getBytes(UTF_8))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // Issue #8: a failed attachment upload must not leave an orphan .meta.json sidecar
    @Test
    void spanishChatStart_withDisallowedAttachmentType_leavesNoOrphanSidecar() throws IOException {
        writeSpanishTopics("Ser y estar");

        var resp = postChatMultipart(spanishBody("Ser y estar", null),
                List.of(new PartFile("data.bin", "application/octet-stream", new byte[10])));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        long metaCount = Files.exists(CONV_DIR)
                ? Files.list(CONV_DIR).filter(p -> p.toString().endsWith(".meta.json")).count()
                : 0L;
        assertThat(metaCount).isZero();
    }

    // Issue #10: removing a scenario file must not expose internal paths in the 500 response
    @Test
    void coachFollowUp_whenScenarioFileRemoved_returns500WithoutInternalPath() throws IOException {
        writeCooPrompt("01-scenario.md", "SCENARIO CONTENT");
        queueText("opening answer");
        String cid = json(postChat(coachBody("chief-operating-officer")))
                .get("conversationId").asText();

        Files.delete(cooDir().resolve("01-scenario.md"));

        ResponseEntity<String> resp = postChat(chatBody("follow-up?", "opus-4-8", null, cid));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String message = json(resp).get("message").asText();
        assertThat(message).doesNotContain(COACHES_DIR.toString());
    }

    // ----------------------------------------------------------------------- //
    // 字 word-quiz mode — ephemeral translate → check, no JSONL persistence
    // ----------------------------------------------------------------------- //

    private ResponseEntity<String> postTranslate(Map<String, Object> body) {
        return rest.postForEntity(url("/api/spanish/words/translate"), body, String.class);
    }

    private ResponseEntity<String> postCheck(Map<String, Object> body) {
        return rest.postForEntity(url("/api/spanish/words/check"), body, String.class);
    }

    @Test
    void spanishWordsTranslate_happyPath_returnsSetIdAndItems() {
        queueText("(caber en) fit\n(cabar) dig\n(pala) shovel\n(cráneo) skull");

        var resp = postTranslate(Map.of("words", "caber en, cabar, pala, cráneo"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp);
        assertThat(node.get("setId").asText()).isNotBlank();
        assertThat(node.get("items")).hasSize(4);

        // All four english values present (order varies due to shuffle)
        List<String> englishList = new ArrayList<>();
        node.get("items").forEach(item -> englishList.add(item.get("english").asText()));
        assertThat(englishList).containsExactlyInAnyOrder("fit", "dig", "shovel", "skull");

        // Hints: ceil(len/4) leading chars of each word kept; rest → · (U+00B7); spaces preserved
        Map<String, String> expectedHint = Map.of(
                "fit",    "ca··· e·",   // caber en
                "dig",    "ca···",                  // cabar
                "shovel", "p···",                        // pala
                "skull",  "cr····");           // cráneo
        node.get("items").forEach(item -> {
            String eng = item.get("english").asText();
            assertThat(item.get("hint").asText()).isEqualTo(expectedHint.get(eng));
        });

        assertThat(gatewayCalls).hasSize(1);
    }

    @Test
    void spanishWordsTranslate_hintRevealsStartOfEachWord() {
        // Each word shows its first letter(s); longer words reveal more (ceil(len/4)).
        queueText("(ser compatible) to be compatible\n(corresponder) to reciprocate");

        var resp = postTranslate(Map.of("words", "ser compatible, corresponder"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, String> hintByEnglish = new java.util.HashMap<>();
        json(resp).get("items").forEach(item ->
                hintByEnglish.put(item.get("english").asText(), item.get("hint").asText()));
        assertThat(hintByEnglish.get("to be compatible")).isEqualTo("s·· com·······");
        assertThat(hintByEnglish.get("to reciprocate")).isEqualTo("cor·········");
    }

    @Test
    void spanishWordsTranslate_stripsTranslationBeforeSplittingOnComma() {
        // A comma inside the (ignored) translation must not fabricate a 5th "Spanish" word.
        // The scripted reply even offers a 5th line; the fix keeps the LLM input at 4 tokens.
        queueText("(Tomar medidas) to take measures\n(Jugárselo) to risk it all\n"
                + "(Estar regulado) to be regulated\n(Fiarse) to trust\n"
                + "(Arriesgarlo todo) to put everything on the line");

        String words = "Tomar medidas — вживати заходів\n"
                + "Jugárselo — ризикувати всім, ставити все на карту\n"
                + "Estar regulado — бути врегульованим\n"
                + "Fiarse — довіряти";
        var resp = postTranslate(Map.of("words", words));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("items")).hasSize(4);
        assertThat(gatewayCalls).hasSize(1);

        // The LLM receives exactly the four clean Spanish tokens — no Cyrillic, no phantom line.
        GatewayCall call = gatewayCalls.get(0);
        assertThat(textOf(call.messages().get(0)))
                .isEqualTo("Tomar medidas\nJugárselo\nEstar regulado\nFiarse");
    }

    @Test
    void spanishWordsCheck_lenientGrading_flagsCorrectAndWrong() {
        queueText("(caber en) fit\n(cabar) dig\n(pala) shovel\n(cráneo) skull");
        var translateResp = json(postTranslate(Map.of("words", "caber en, cabar, pala, cráneo")));
        String setId = translateResp.get("setId").asText();

        // Build answers in the shuffled order the translate response returned
        Map<String, String> answerByEnglish = new HashMap<>();
        answerByEnglish.put("fit",    "caber en");  // exact match → correct
        answerByEnglish.put("dig",    "wrong");      // deliberate miss → incorrect
        answerByEnglish.put("shovel", "PALA");       // case-insensitive → correct
        answerByEnglish.put("skull",  "CRANEO");     // accent-insensitive → correct
        List<String> answers = new ArrayList<>();
        for (JsonNode item : translateResp.get("items"))
            answers.add(answerByEnglish.getOrDefault(item.get("english").asText(), ""));

        Map<String, Object> checkBody = new HashMap<>();
        checkBody.put("setId", setId);
        checkBody.put("answers", answers);
        var checkResp = json(postCheck(checkBody));

        assertThat(checkResp.get("results")).hasSize(4);
        long correctCount = 0;
        for (JsonNode result : checkResp.get("results")) {
            if (result.get("correct").asBoolean()) correctCount++;
            assertThat(result.get("spanish").asText()).isNotBlank();  // original revealed
        }
        assertThat(correctCount).isEqualTo(3);
        assertThat(gatewayCalls).hasSize(1);  // no extra gateway call for check
    }

    @Test
    void spanishWordsTranslate_itemsCarryFullSpanish() {
        // Each item ships the full Spanish word (for click-to-reveal) alongside the masked hint.
        queueText("(caber en) to fit\n(cráneo) skull");

        var resp = json(postTranslate(Map.of("words", "caber en, cráneo")));

        Map<String, String> spanishByEnglish = new HashMap<>();
        Map<String, String> hintByEnglish = new HashMap<>();
        for (JsonNode item : resp.get("items")) {
            spanishByEnglish.put(item.get("english").asText(), item.get("spanish").asText());
            hintByEnglish.put(item.get("english").asText(), item.get("hint").asText());
        }
        assertThat(spanishByEnglish.get("to fit")).isEqualTo("caber en");
        assertThat(spanishByEnglish.get("skull")).isEqualTo("cráneo");
        assertThat(hintByEnglish.get("skull")).isEqualTo("cr····");  // hint stays masked
    }

    @Test
    void spanishWordsCheck_fullHintUsed_setsFullHintFlag() {
        queueText("(caber en) to fit\n(cráneo) skull");
        var translateResp = json(postTranslate(Map.of("words", "caber en, cráneo")));
        String setId = translateResp.get("setId").asText();

        // Answer both correctly (using the payload's full word); flag only the first row.
        List<String> answers = new ArrayList<>();
        List<Boolean> hintsUsed = new ArrayList<>();
        for (JsonNode item : translateResp.get("items")) {
            answers.add(item.get("spanish").asText());
            hintsUsed.add(answers.size() == 1);
        }

        Map<String, Object> checkBody = new HashMap<>();
        checkBody.put("setId", setId);
        checkBody.put("answers", answers);
        checkBody.put("hintsUsed", hintsUsed);
        var results = json(postCheck(checkBody)).get("results");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("correct").asBoolean()).isTrue();
        assertThat(results.get(0).get("fullHint").asBoolean()).isTrue();
        assertThat(results.get(1).get("correct").asBoolean()).isTrue();
        assertThat(results.get(1).get("fullHint").asBoolean()).isFalse();
        assertThat(gatewayCalls).hasSize(1);  // grading uses no LLM
    }

    @Test
    void spanishWordsCheck_fullHintButWrong_isIncorrect() {
        queueText("(cráneo) skull");
        var translateResp = json(postTranslate(Map.of("words", "cráneo")));
        String setId = translateResp.get("setId").asText();

        Map<String, Object> checkBody = new HashMap<>();
        checkBody.put("setId", setId);
        checkBody.put("answers", List.of("wrong"));
        checkBody.put("hintsUsed", List.of(true));
        var result = json(postCheck(checkBody)).get("results").get(0);

        assertThat(result.get("correct").asBoolean()).isFalse();  // red dominates
        assertThat(result.get("fullHint").asBoolean()).isTrue();
    }

    @Test
    void spanishWordsCheck_withExpiredSetId_returns404() {
        Map<String, Object> body = new HashMap<>();
        body.put("setId", "nonexistentsetid000");
        body.put("answers", List.of());

        var resp = postCheck(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(json(resp).get("message").asText()).contains("expired");
    }

    @Test
    void spanishWordsCheck_isEphemeral_leavesNoConversationFiles() throws IOException {
        queueText("(cráneo) skull\n(pala) shovel");
        var translateResp = json(postTranslate(Map.of("words", "cráneo, pala")));
        String setId = translateResp.get("setId").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("setId", setId);
        body.put("answers", List.of("cráneo", "pala"));
        postCheck(body);

        // No JSONL or meta.json files — the quiz is entirely ephemeral
        if (Files.exists(CONV_DIR)) {
            try (var paths = Files.list(CONV_DIR)) {
                assertThat(paths
                        .filter(p -> p.toString().endsWith(".jsonl")
                                || p.toString().endsWith(".meta.json"))
                        .toList()).isEmpty();
            }
        }
        assertThat(json(rest.getForEntity(url("/api/conversations"), String.class))).isEmpty();
    }

    @Test
    void spanishWordsTranslate_withBlankWords_returns400() {
        var resp = postTranslate(Map.of("words", "   "));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(gatewayCalls).isEmpty();
    }

    @Test
    void spanishWordsTranslate_withUnparseableOutput_returns500() {
        queueText("This is just prose that does not match the format at all.");

        var resp = postTranslate(Map.of("words", "caber, pala"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(json(resp).get("message").asText()).containsIgnoringCase("parse");
    }

    @Test
    void spanishWordsTranslate_stripsSurroundingParentheses() {
        // Each entry wrapped in parens; stored original and LLM input must be unwrapped.
        queueText("(fundirse el dinero) to blow all one's money\n(bahía) bay");

        var resp = json(postTranslate(Map.of("words", "(fundirse el dinero)\n(bahía)")));

        Map<String, String> spanishByEnglish = new HashMap<>();
        for (JsonNode item : resp.get("items"))
            spanishByEnglish.put(item.get("english").asText(), item.get("spanish").asText());
        assertThat(spanishByEnglish.get("to blow all one's money")).isEqualTo("fundirse el dinero");
        assertThat(spanishByEnglish.get("bay")).isEqualTo("bahía");

        // The LLM receives the clean tokens — no parentheses.
        assertThat(textOf(gatewayCalls.get(0).messages().get(0)))
                .isEqualTo("fundirse el dinero\nbahía");
    }

    @Test
    void spanishWordsCheck_parenthesisedInput_gradesCleanAnswerCorrect() {
        queueText("(fundirse el dinero) to blow all one's money\n(astutos) cunning");
        var t = json(postTranslate(Map.of("words", "(fundirse el dinero)\n(astutos)")));

        Map<String, String> ans = Map.of(
                "to blow all one's money", "fundirse el dinero",
                "cunning", "astutos");
        List<String> answers = new ArrayList<>();
        for (JsonNode item : t.get("items"))
            answers.add(ans.get(item.get("english").asText()));

        Map<String, Object> body = new HashMap<>();
        body.put("setId", t.get("setId").asText());
        body.put("answers", answers);
        var check = json(postCheck(body));

        long correct = 0;
        for (JsonNode r : check.get("results"))
            if (r.get("correct").asBoolean()) correct++;
        assertThat(correct).isEqualTo(2);
    }

    @Test
    void spanishChatWithoutTopic_wordsSeed_persistsAndSystemHasNoTema() throws IOException {
        queueText("(cráneo) Use this skull.\n(pala) The shovel is big.");

        var resp = postChat(spanishBody(null, "cráneo, pala"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = json(resp);
        assertThat(node.get("sentences").isArray()).isTrue();

        // Conversation is persisted
        String cid = node.get("conversationId").asText();
        assertThat(Files.exists(CONV_DIR.resolve(cid + ".jsonl"))).isTrue();

        // System prompt has the tutor but no "Tema de práctica" clause
        GatewayCall call = gatewayCalls.get(gatewayCalls.size() - 1);
        assertThat(call.system()).contains("tutor de español");
        assertThat(call.system()).doesNotContain("Tema de práctica");
    }

    @Test
    void indexHtml_modeButtons_haveTooltipAttributes() {
        var html = rest.getForObject(url("/index.html"), String.class);
        assertThat(html).contains("data-tooltip=\"language mode\"");
        assertThat(html).contains("data-tooltip=\"words mode\"");
    }

    @Test
    void styleCss_containsTooltipRule() {
        var css = rest.getForObject(url("/style.css"), String.class);
        assertThat(css).contains("[data-tooltip]");
    }

    @Test
    void scriptJs_sentenceCardClick_appendsTrailingNewline() {
        var js = rest.getForObject(url("/script.js"), String.class);
        assertThat(js).contains("card.dataset.sentence + '\\n'");
    }

    @Test
    void scriptJs_markedRenderer_opensLinksInNewTab() {
        var js = rest.getForObject(url("/script.js"), String.class);
        assertThat(js).contains("target=\"_blank\" rel=\"noopener noreferrer\"");
    }
}
