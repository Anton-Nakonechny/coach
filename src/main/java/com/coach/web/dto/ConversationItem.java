package com.coach.web.dto;

/** A conversation summary for the sidebar listing; {@code coachType} is null for plain chats. */
public record ConversationItem(
        String conversationId,
        String preview,
        String coachType
) { }
