package com.coach.web.dto;

/** A conversation summary for the sidebar listing. */
public record ConversationItem(
        String conversationId,
        String preview
) { }
