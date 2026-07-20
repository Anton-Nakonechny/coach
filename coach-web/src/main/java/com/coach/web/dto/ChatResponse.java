package com.coach.web.dto;

import com.coach.model.ModelKey;
import com.coach.dto.QuizQuestion;
import com.coach.dto.SentenceItem;

import java.util.List;

/** Response for a completed chat turn. */
public record ChatResponse(
        String answer,
        ModelKey model,
        String conversationId,
        List<SentenceItem> sentences,
        QuizQuestion question
) { }
