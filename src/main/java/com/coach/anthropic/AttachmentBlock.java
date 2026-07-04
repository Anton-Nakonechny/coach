package com.coach.anthropic;

/**
 * A file attachment within a turn's content, referenced by its Anthropic Files API
 * {@code file_id}. The {@link Kind} (chosen from the MIME type) decides whether it is
 * sent to Claude as an image block or a document block.
 */
public record AttachmentBlock(String fileId, MimeType mediaType, String filename, Kind kind)
        implements ContentBlock {

    public enum Kind {
        IMAGE,
        DOCUMENT
    }
}
