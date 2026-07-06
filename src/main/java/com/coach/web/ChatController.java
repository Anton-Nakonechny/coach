package com.coach.web;

import com.coach.anthropic.AttachmentBlock;
import com.coach.anthropic.ClaudeClient;
import com.coach.attach.AttachmentService;
import com.coach.coach.CoachMeta;
import com.coach.coach.CoachService;
import com.coach.coach.QuestionParser;
import com.coach.coach.SentenceParser;
import com.coach.model.CoachType;
import com.coach.model.ModelKey;
import com.coach.model.ModelsConfig;
import com.coach.store.ConversationStore;
import com.coach.web.dto.ChatRequest;
import com.coach.web.dto.ChatResponse;
import com.coach.web.dto.ConversationItem;
import com.coach.web.dto.MessageItem;
import com.coach.web.dto.QuizQuestion;
import com.coach.web.dto.Role;
import com.coach.web.dto.SentenceItem;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

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
    private final CoachService coachService;

    public ChatController(ClaudeClient claudeClient, ConversationStore store, ModelsConfig models,
                          AttachmentService attachments, CoachService coachService) {
        this.claudeClient = claudeClient;
        this.store = store;
        this.models = models;
        this.attachments = attachments;
        this.coachService = coachService;
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
        CoachType coach = request.coachType() != null ? request.coachType() : CoachType.NONE;
        String message = request.message();

        if (StringUtils.hasText(request.topic()) && coach != CoachType.SPANISH && coach != CoachType.CLAUDE_ARCHITECT)
            throw new InvalidRequestException("topic can only be set for the Spanish tutor or the Claude Architect coach");
        if (coach != CoachType.NONE && StringUtils.hasText(request.conversationId()))
            throw new InvalidRequestException("coachType can only be set when starting a new chat");

        if (coach == CoachType.SPANISH) {
            if (!StringUtils.hasText(request.topic()))
                throw new InvalidRequestException("topic is required for the Spanish tutor");
            if (!StringUtils.hasText(message) && files.isEmpty())
                throw new InvalidRequestException("message or files are required for the Spanish tutor");
        } else if (coach == CoachType.CLAUDE_ARCHITECT) {
            if (!StringUtils.hasText(request.topic()))
                throw new InvalidRequestException("topic is required for the Claude Architect coach");
            if (StringUtils.hasText(message))
                throw new InvalidRequestException("message must be blank when starting a coach chat");
            if (!files.isEmpty())
                throw new InvalidRequestException("files are not allowed when starting a Claude Architect chat");
            message = CoachService.CLAUDE_OPENING_INSTRUCTION;
        } else if (coach != CoachType.NONE) {
            if (StringUtils.hasText(message))
                throw new InvalidRequestException("message must be blank when starting a coach chat");
            message = CoachService.OPENING_INSTRUCTION;
        } else if (!StringUtils.hasText(message) && files.isEmpty()) {
            throw new InvalidRequestException("message must not be blank");
        }

        ModelKey model = request.model() != null ? request.model() : models.defaultModel();
        String effort = request.effort();

        String conversationId = StringUtils.hasText(request.conversationId())
                ? request.conversationId()
                : UUID.randomUUID().toString().replace("-", "");

        CoachMeta newMeta = null;
        if (coach == CoachType.SPANISH) {
            // Validates topic membership — throws 400/500 before any persistence.
            newMeta = coachService.startSpanish(request.topic().trim());
            message = coachService.spanishOpeningPrompt(
                    newMeta.topic(),
                    StringUtils.hasText(message) ? message.trim() : null);
        } else if (coach == CoachType.CLAUDE_ARCHITECT) {
            newMeta = coachService.startClaudeArchitect(request.topic().trim());
        } else if (coach != CoachType.NONE) {
            newMeta = coachService.startCoach(coach);
        }

        // Process attachments before writing the sidecar so a rejected file leaves no orphan.
        List<AttachmentBlock> uploaded = attachments.process(files);
        if (newMeta != null)
            store.saveCoachMeta(conversationId, newMeta);
        // Reuse the just-built meta on a coach-start turn; continuing turns read it from disk.
        var meta = newMeta == null ? store.coachMeta(conversationId) : Optional.of(newMeta);
        String system = meta.map(coachService::systemPrompt).orElse(null);

        store.appendMessage(conversationId, "user", message, model.value(), effort, uploaded);
        String answer = claudeClient.generate(model, store.apiMessages(conversationId), effort, system);
        store.appendMessage(conversationId, "assistant", answer, model.value(), effort);

        var sentences = meta
                .filter(m -> m.coachType() == CoachType.SPANISH)
                .map(__ -> parseSentences(answer))
                .orElse(null);
        var question = meta
                .filter(m -> m.coachType() == CoachType.CLAUDE_ARCHITECT)
                .map(__ -> QuestionParser.parse(answer))
                .orElse(null);
        return new ChatResponse(answer, model, conversationId, sentences, question);
    }

    @GetMapping("/coaches/spanish/topics")
    public List<String> spanishTopics() {
        return coachService.spanishTopics();
    }

    @GetMapping("/coaches/claude-architect/topics")
    public List<String> claudeTopics() {
        return coachService.claudeTopics();
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
        List<MessageItem> messages = store.loadMessages(conversationId);
        if (messages.isEmpty())
            throw new ConversationNotFoundException("Conversation not found");
        var coachType = store.coachMeta(conversationId)
                .map(CoachMeta::coachType)
                .orElse(CoachType.NONE);
        if (coachType == CoachType.SPANISH)
            return enrichAssistant(messages, this::parseSentences, MessageItem::withSentences);
        if (coachType == CoachType.CLAUDE_ARCHITECT)
            return enrichAssistant(messages, QuestionParser::parse, MessageItem::withQuestion);
        return messages;
    }

    private <T> List<MessageItem> enrichAssistant(
            List<MessageItem> messages,
            Function<String, T> parse,
            BiFunction<MessageItem, T, MessageItem> enrich) {
        return messages.stream().map(m -> {
            if (m.role() != Role.ASSISTANT) return m;
            T v = parse.apply(m.content());
            return v == null ? m : enrich.apply(m, v);
        }).toList();
    }

    private List<SentenceItem> parseSentences(String text) {
        var parsed = SentenceParser.parse(text);
        return parsed.isEmpty() ? null : parsed;
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
