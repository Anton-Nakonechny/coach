package com.coach.web;

import com.coach.anthropic.AttachmentBlock;
import com.coach.anthropic.ClaudeClient;
import com.coach.attach.AttachmentService;
import com.coach.model.ModelKey;
import com.coach.model.ModelsConfig;
import com.coach.store.ConversationStore;
import com.coach.web.dto.ChatRequest;
import com.coach.web.dto.ChatResponse;
import com.coach.web.dto.ConversationItem;
import com.coach.web.dto.MessageItem;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP routes — Java port of the FastAPI routes in {@code app.py}.
 *
 * <p>Request flow for {@code /api/chat}: persist the user turn, call Claude,
 * persist the assistant turn, return the answer. The Anthropic API is stateless,
 * so every turn resends the full history rebuilt from disk.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ClaudeClient claudeClient;
    private final ConversationStore store;
    private final ModelsConfig models;
    private final AttachmentService attachments;

    public ChatController(ClaudeClient claudeClient, ConversationStore store, ModelsConfig models,
                          AttachmentService attachments) {
        this.claudeClient = claudeClient;
        this.store = store;
        this.models = models;
        this.attachments = attachments;
    }

    /** Text-only chat turn (JSON body) — the original contract, unchanged. */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return handle(request, List.of());
    }

    /** Chat turn with file attachments (multipart): a JSON {@code request} part + {@code files}. */
    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatResponse chatMultipart(@Valid @RequestPart("request") ChatRequest request,
                                      @RequestPart(value = "files", required = false) MultipartFile[] files) {
        return handle(request, files == null ? List.of() : List.of(files));
    }

    private ChatResponse handle(ChatRequest request, List<MultipartFile> files) {
        // A turn needs either text or at least one attachment.
        if (!StringUtils.hasText(request.message()) && files.isEmpty()) {
            throw new InvalidRequestException("message must not be blank");
        }

        // An unknown model key is already rejected (400) when Jackson parses ModelKey.
        ModelKey model = request.model() != null
                ? request.model()
                : models.defaultModel();
        // Effort is null when omitted — no defaulting here (the frontend seeds its
        // own default from /api/models). A null/unknown effort is simply not sent.
        String effort = request.effort();

        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : UUID.randomUUID().toString().replace("-", "");

        List<AttachmentBlock> uploaded = attachments.process(files);

        store.appendMessage(conversationId, "user", request.message(), model.value(), effort, uploaded);
        String answer = claudeClient.generate(model, store.apiMessages(conversationId), effort);
        store.appendMessage(conversationId, "assistant", answer, model.value(), effort);

        return new ChatResponse(answer, model, conversationId);
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return Map.of(
                "models", models.models(),
                "effortLevels", models.effortLevels(),
                "defaultModel", models.defaultModel(),
                "defaultEffort", models.defaultEffort());
    }

    @GetMapping("/conversations")
    public List<ConversationItem> listConversations() {
        return store.listConversations();
    }

    @GetMapping("/conversations/{conversationId}")
    public List<MessageItem> getConversation(@PathVariable String conversationId) {
        List<   MessageItem> messages = store.loadMessages(conversationId);
        if (messages.isEmpty()) {
            throw new ConversationNotFoundException("Conversation not found");
        }
        return messages;
    }

    @DeleteMapping("/conversations")
    public Map<String, Object> deleteAllConversations() {
        int count = store.archiveAllConversations();
        return Map.of("status", "archived", "count", count);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(@PathVariable String conversationId) {
        if (!store.archiveConversation(conversationId)) {
            throw new ConversationNotFoundException("Conversation not found");
        }
        return Map.of("status", "archived", "conversationId", conversationId);
    }
}
