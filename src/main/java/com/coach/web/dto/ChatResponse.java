package com.coach.web.dto;

import com.coach.model.ModelKey;

/** Response for a completed chat turn. */
public record ChatResponse(
        String answer,
        ModelKey model,
        String conversationId
) { }
