package com.coach.web.dto;

import com.coach.model.ModelKey;

import java.util.List;

/** One stored message as returned by the history endpoint. */
public record MessageItem(
        Role role,
        String content,
        ModelKey model,
        String effort,
        List<AttachmentMeta> attachments,
        List<SentenceItem> sentences,
        QuizQuestion question
) {
    public MessageItem withSentences(List<SentenceItem> s) {
        return new MessageItem(role, content, model, effort, attachments, s, question);
    }

    public MessageItem withQuestion(QuizQuestion q) {
        return new MessageItem(role, content, model, effort, attachments, sentences, q);
    }
}
