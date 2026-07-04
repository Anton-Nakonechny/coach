package com.coach.web.dto;

import com.coach.anthropic.AttachmentBlock;

/**
 * Per-turn attachment metadata persisted in JSONL and returned by the history endpoint
 * so the UI can re-render file chips/thumbnails on replay.
 */
public record AttachmentMeta(
        String fileId,
        String filename,
        String mediaType,
        AttachmentBlock.Kind kind
) { }
