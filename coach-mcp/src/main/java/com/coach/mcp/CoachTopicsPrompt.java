package com.coach.mcp;

import com.coach.coach.CoachService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The {@code topics} MCP prompt: lists the Claude Certified Architect exam topics
 * available for quizzing (via {@code claude-architect-quiz}), without starting a quiz.
 * No arguments — appears in Claude Code as {@code /coach:topics}.
 */
@Component
public class CoachTopicsPrompt {

    public static final String PROMPT_NAME = "topics";

    private final CoachService coachService;

    public CoachTopicsPrompt(CoachService coachService) {
        this.coachService = coachService;
    }

    public McpServerFeatures.SyncPromptSpecification promptSpecification() {
        var prompt = new McpSchema.Prompt(
                PROMPT_NAME,
                "List the Claude Certified Architect exam topics you can be quizzed on.",
                List.of());
        return new McpServerFeatures.SyncPromptSpecification(
                prompt, (exchange, request) -> getPrompt());
    }

    private McpSchema.GetPromptResult getPrompt() {
        try {
            var topics = coachService.claudeTopics();
            var text = "Claude Architect exam topics:\n"
                    + String.join("\n", topics.stream().map(t -> "- " + t).toList())
                    + "\n\nPresent this list and ask which topic to start a quiz on "
                    + "(via the claude-architect-quiz prompt).";
            return new McpSchema.GetPromptResult(
                    "Claude Architect topics",
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER, new McpSchema.TextContent(text))));
        } catch (Exception e) {
            throw McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR)
                    .message("Failed to load Claude Architect topics.").build();
        }
    }
}
