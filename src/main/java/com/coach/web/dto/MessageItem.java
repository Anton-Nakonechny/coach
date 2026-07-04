package com.coach.web.dto;

import com.coach.model.ModelKey;

import java.util.List;

/** One stored message as returned by the history endpoint. */
public record MessageItem(
        Role role,
        String content,
        ModelKey model,
        String effort,
        List<AttachmentMeta> attachments
) { }
