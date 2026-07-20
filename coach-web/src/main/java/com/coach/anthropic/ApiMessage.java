package com.coach.anthropic;

import java.util.List;

/**
 * One conversation turn handed to the {@link AnthropicGateway}: a role plus an ordered
 * list of {@link ContentBlock}s (text and/or file attachments). Replaces the old
 * {@code Map<String,String>} {role,content} projection now that turns can carry files.
 */
public record ApiMessage(String role, List<ContentBlock> content) { }
