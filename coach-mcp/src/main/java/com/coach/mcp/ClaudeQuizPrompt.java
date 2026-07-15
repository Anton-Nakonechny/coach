package com.coach.mcp;

import com.coach.coach.CoachService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The {@code claude-architect-quiz} MCP prompt: the Claude Architect quiz exposed
 * to MCP clients (Claude Code renders it as a slash command). {@code prompts/get}
 * returns the same persona + topic blueprint + official-docs text the web chat
 * sends as its system prompt, as a single user-role message — the MCP host owns
 * the conversation from there; nothing is persisted here.
 */
@Component
public class ClaudeQuizPrompt {

    public static final String PROMPT_NAME = "claude-architect-quiz";
    static final String TOPIC_ARG = "topic";

    private final CoachService coachService;

    public ClaudeQuizPrompt(CoachService coachService) {
        this.coachService = coachService;
    }

    public McpServerFeatures.SyncPromptSpecification promptSpecification() {
        var prompt = new McpSchema.Prompt(
                PROMPT_NAME,
                "Quiz for the Claude Certified Architect – Foundations exam: coach persona "
                        + "+ topic blueprint + official Anthropic docs.",
                List.of(new McpSchema.PromptArgument(
                        TOPIC_ARG,
                        "Exam topic; any unique fragment of the topic name resolves (e.g. \"2.4\").",
                        true)));
        return new McpServerFeatures.SyncPromptSpecification(
                prompt, (exchange, request) -> getPrompt(request));
    }

    /** Argument completion: topics whose stem contains the typed value, case-insensitively. */
    public McpServerFeatures.SyncCompletionSpecification completionSpecification() {
        return new McpServerFeatures.SyncCompletionSpecification(
                new McpSchema.PromptReference(PROMPT_NAME),
                (exchange, request) -> complete(request));
    }

    private McpSchema.CompleteResult complete(McpSchema.CompleteRequest request) {
        try {
            var typed = request.argument().value().toLowerCase();
            var matches = coachService.claudeTopics().stream()
                    .filter(topic -> topic.toLowerCase().contains(typed))
                    .toList();
            return new McpSchema.CompleteResult(
                    new McpSchema.CompleteResult.CompleteCompletion(matches));
        } catch (Exception e) {
            return new McpSchema.CompleteResult(
                    new McpSchema.CompleteResult.CompleteCompletion(List.of()));
        }
    }

    private McpSchema.GetPromptResult getPrompt(McpSchema.GetPromptRequest request) {
        var typed = topicOf(request)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> invalidParams("Missing required argument: " + TOPIC_ARG));
        try {
            var topic = resolveTopic(typed);
            var meta = coachService.startClaudeArchitect(topic);
            var text = coachService.systemPrompt(meta) + "\n\n"
                    + coachService.claudeOpeningInstruction(meta);
            return new McpSchema.GetPromptResult(
                    "Claude Architect quiz: " + topic,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER, new McpSchema.TextContent(text))));
        } catch (McpError e) {
            throw e;
        } catch (Exception e) {
            throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                    .message("Failed to load Claude Architect quiz.").build();
        }
    }

    private static Optional<String> topicOf(McpSchema.GetPromptRequest request) {
        var arguments = request.arguments();
        if (arguments == null) return Optional.empty();
        var value = arguments.get(TOPIC_ARG);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /**
     * The full topic stem for what the user typed. Clients may deliver a mangled
     * value — Claude Code splits slash-command args on whitespace and passes only
     * the first token, quote characters included — so quotes are stripped and a
     * unique partial match (case-insensitive) resolves too.
     */
    private String resolveTopic(String typed) {
        var wanted = typed.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").trim().toLowerCase();
        if (wanted.isBlank()) throw invalidParams("Missing required argument: " + TOPIC_ARG);
        var topics = coachService.claudeTopics();
        for (var topic : topics)
            if (topic.toLowerCase().equals(wanted)) return topic;
        var matches = topics.stream()
                .filter(topic -> topic.toLowerCase().contains(wanted))
                .toList();
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty()) throw invalidParams("Unknown Claude Architect topic: " + typed);
        throw invalidParams("Ambiguous topic '" + typed + "' — matches: " + String.join(", ", matches));
    }

    private static McpError invalidParams(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
    }
}
