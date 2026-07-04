package com.coach.web.dto;

import com.coach.model.CoachType;
import com.coach.model.ModelKey;

/**
 * A single chat turn from the frontend.
 *
 * <p>{@code message} may be blank only when the turn carries at least one file
 * attachment (validated in the controller → 400). {@code model} and {@code effort} may
 * be omitted; the controller substitutes the configured defaults. {@code conversationId}
 * is null for a fresh conversation. {@code coachType} (null → {@link CoachType#NONE})
 * may only be set when starting a new chat with a blank message — the backend supplies
 * the opening instruction itself.
 */
public record ChatRequest(
        String message,
        ModelKey model,
        String effort,
        String conversationId,
        CoachType coachType,
        String topic
) { }
