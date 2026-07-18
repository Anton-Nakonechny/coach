package com.coach.store;

import com.coach.anthropic.ApiMessage;
import com.coach.anthropic.AttachmentBlock;
import com.coach.anthropic.ContentBlock;
import com.coach.anthropic.SdkFileUploadGateway;
import com.coach.anthropic.TextBlock;
import com.coach.coach.CoachMeta;
import com.coach.config.AppConfig;
import static com.coach.anthropic.MimeType.fromValue;
import com.coach.model.CoachType;
import com.coach.model.ModelKey;
import com.coach.web.dto.AttachmentMeta;
import com.coach.web.dto.ConversationItem;
import com.coach.web.dto.MessageItem;
import com.coach.web.dto.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * JSON-Lines per-conversation persistence — Java port of {@code conversation_store.py}.
 *
 * <p>Each conversation is a file {@code <dir>/<id>.jsonl} with one JSON object per
 * line ({@code role/content/model/effort/ts}). {@code apiMessages()} projects to
 * {@code {role, content}} for the API; {@code loadMessages()} returns the full
 * records for the history UI. Soft-delete archives one conversation to a gzip or
 * all of them to a single timestamped zip bundle under {@code archive/}.
 */
@Component
public class ConversationStore {

    private static final DateTimeFormatter BUNDLE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss_SSSSSS").withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper;
    private final Path convDir;
    private final SdkFileUploadGateway fileGateway;

    public ConversationStore(AppConfig config, SdkFileUploadGateway fileGateway, ObjectMapper mapper) {
        this.convDir = Path.of(config.conversationsDir());
        this.fileGateway = fileGateway;
        this.mapper = mapper;
    }

    private Path path(String conversationId) {
        // Guard against path traversal — ids are server-minted hex, but be safe.
        String safe = Path.of(conversationId).getFileName().toString();
        return convDir.resolve(safe + ".jsonl");
    }

    private Path metaPath(String conversationId) {
        String safe = Path.of(conversationId).getFileName().toString();
        return convDir.resolve(safe + ".meta.json");
    }

    private Path archiveDir() {
        return convDir.resolve("archive");
    }

    public void appendMessage(String conversationId, String role, String content,
                              String model, String effort) {
        appendMessage(conversationId, role, content, model, effort, List.of());
    }

    public void appendMessage(String conversationId, String role, String content,
                              String model, String effort, List<AttachmentBlock> attachments) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("role", role);
        record.put("content", content);
        record.put("model", model);
        record.put("effort", effort);
        if (attachments != null && !attachments.isEmpty()) {
            List<Map<String, Object>> atts = new ArrayList<>();
            for (AttachmentBlock a : attachments) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("fileId", a.fileId());
                am.put("filename", a.filename());
                am.put("mediaType", a.mediaType().value());
                am.put("kind", a.kind().name());
                atts.add(am);
            }
            record.put("attachments", atts);
        }
        record.put("ts", Instant.now().toString());
        try {
            Files.createDirectories(convDir);
            try (BufferedWriter w = Files.newBufferedWriter(path(conversationId), UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(mapper.writeValueAsString(record));
                w.write("\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Persist the coach selection for a conversation as a {@code <id>.meta.json} sidecar. */
    public void saveCoachMeta(String conversationId, CoachMeta meta) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("coachType", meta.coachType().value());
        if (meta.promptFile() != null) record.put("promptFile", meta.promptFile());
        if (meta.topic() != null) record.put("topic", meta.topic());
        try {
            Files.createDirectories(convDir);
            Files.writeString(metaPath(conversationId), mapper.writeValueAsString(record), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The coach selection stored for a conversation; empty for plain (coach-less) chats. */
    public Optional<CoachMeta> coachMeta(String conversationId) {
        Path file = metaPath(conversationId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode node = mapper.readTree(Files.readString(file, UTF_8));
            return Optional.of(new CoachMeta(
                    CoachType.fromValue(text(node, "coachType")),
                    text(node, "promptFile"),
                    text(node, "topic")));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Full stored records for a conversation (empty if none). */
    public List<MessageItem> loadMessages(String conversationId) {
        Path file = path(conversationId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<MessageItem> messages = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                var node = mapper.readTree(line);
                String modelStr = text(node, "model");
                messages.add(new MessageItem(
                        Role.from(text(node, "role")),
                        text(node, "content"),
                        modelStr != null ? ModelKey.fromValue(modelStr) : null,
                        text(node, "effort"),
                        attachments(node.get("attachments")),
                        null,
                        null));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return messages;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText(null);
    }

    /** Best-effort delete of a conversation's uploaded files (on archive/delete). */
    private void deleteUploads(String conversationId) {
        for (MessageItem m : loadMessages(conversationId)) {
            for (AttachmentMeta a : m.attachments()) {
                try {
                    fileGateway.delete(a.fileId());
                } catch (RuntimeException ignored) {
                    // Best-effort: a failed remote delete must not block archiving.
                }
            }
        }
    }

    private static List<AttachmentMeta> attachments(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<AttachmentMeta> out = new ArrayList<>();
        for (JsonNode a : node) {
            out.add(new AttachmentMeta(
                    text(a, "fileId"),
                    text(a, "filename"),
                    text(a, "mediaType"),
                    kindFrom(text(a, "kind"))));
        }
        return out;
    }

    private static AttachmentBlock.Kind kindFrom(String v) {
        if (v == null) return null;
        try {
            return AttachmentBlock.Kind.valueOf(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Project stored records to {@link ApiMessage}s for the API: the turn's text (when
     * present) as a {@link TextBlock}, followed by an {@link AttachmentBlock} per stored
     * attachment re-referencing its persisted {@code file_id} (no re-upload).
     */
    public List<ApiMessage> apiMessages(String conversationId) {
        List<ApiMessage> out = new ArrayList<>();
        for (MessageItem m : loadMessages(conversationId)) {
            List<ContentBlock> blocks = new ArrayList<>();
            if (m.content() != null && !m.content().isEmpty()) {
                blocks.add(new TextBlock(m.content()));
            }
            for (AttachmentMeta a : m.attachments()) {
                String mimeStr = a.mediaType();
                blocks.add(new AttachmentBlock(a.fileId(),
                        mimeStr != null ? fromValue(mimeStr) : null,
                        a.filename(), a.kind()));
            }
            out.add(new ApiMessage(m.role() != null ? m.role().value() : null, blocks));
        }
        return out;
    }

    /** Conversations newest-first with a short preview label for the sidebar. */
    public List<ConversationItem> listConversations() {
        if (!Files.exists(convDir)) {
            return List.of();
        }
        record Entry(ConversationItem item, long updatedAt) { }
        List<Entry> entries = new ArrayList<>();
        try {
            List<Path> files;
            try (Stream<Path> stream = Files.list(convDir)) {
                files = stream.filter(p -> p.toString().endsWith(".jsonl")).toList();
            }
            for (Path file : files) {
                String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                // Coach conversations are labeled by their scenario, not the first message
                // (which is the same synthetic instruction in every coach chat).
                Optional<CoachMeta> meta = coachMeta(id);
                String preview;
                if (meta.isPresent()) {
                    preview = meta.get().preview();
                } else {
                    String firstUser = firstUserContent(file);
                    if (firstUser == null) continue;
                    preview = firstUser.isEmpty() ? "(empty)"
                            : firstUser.length() > 60 ? firstUser.substring(0, 60) + "…"
                            : firstUser;
                }
                entries.add(new Entry(
                        new ConversationItem(id, preview,
                                meta.map(m -> m.coachType().value()).orElse(null)),
                        Files.getLastModifiedTime(file).toMillis()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        entries.sort(Comparator.comparingLong(Entry::updatedAt).reversed());
        return entries.stream().map(Entry::item).toList();
    }

    /**
     * First non-blank user message in the file (for the sidebar preview), reading
     * only as far as needed; {@code ""} if the conversation has records but no such
     * message, {@code null} if it has no records at all.
     */
    private String firstUserContent(Path file) throws IOException {
        boolean any = false;
        try (Stream<String> lines = Files.lines(file, UTF_8)) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isBlank()) continue;
                any = true;
                var node = mapper.readTree(line);
                String content = text(node, "content");
                if ("user".equals(text(node, "role")) && content != null && !content.isBlank())
                    return content;
            }
        }
        return any ? "" : null;
    }

    /**
     * Soft-delete one conversation: gzip it into {@code archive/} then remove the
     * original. Returns false if the conversation does not exist.
     */
    public boolean archiveConversation(String conversationId) {
        Path file = path(conversationId);
        if (!Files.exists(file)) {
            return false;
        }
        deleteUploads(conversationId);
        try {
            Path archive = archiveDir();
            Files.createDirectories(archive);
            Path dest = archive.resolve(file.getFileName().toString() + ".gz");
            try (InputStream in = Files.newInputStream(file);
                 OutputStream gz = new GZIPOutputStream(Files.newOutputStream(dest))) {
                in.transferTo(gz);
            }
            Files.delete(file);
            Files.deleteIfExists(metaPath(conversationId));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Soft-delete every conversation: bundle them into a single timestamped zip in
     * {@code archive/} then remove the originals. Returns the number archived.
     */
    public int archiveAllConversations() {
        if (!Files.exists(convDir)) {
            return 0;
        }
        try {
            List<Path> files;
            try (Stream<Path> stream = Files.list(convDir)) {
                files = stream.filter(p -> p.toString().endsWith(".jsonl"))
                        .sorted()
                        .toList();
            }
            if (files.isEmpty()) {
                return 0;
            }
            Path archive = archiveDir();
            Files.createDirectories(archive);
            Path bundle = archive.resolve("all-" + BUNDLE_TS.format(Instant.now()) + ".zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
                for (Path file : files) {
                    zip.putNextEntry(new ZipEntry(file.getFileName().toString()));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            for (Path file : files) {
                String id = file.getFileName().toString().replaceFirst("\\.jsonl$", "");
                deleteUploads(id);
                Files.delete(file);
                Files.deleteIfExists(metaPath(id));
            }
            return files.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
