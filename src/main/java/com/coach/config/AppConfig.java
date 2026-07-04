package com.coach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Application configuration bound from {@code coach.*} properties.
 *
 * @param anthropicApiKey  Anthropic API key (from {@code ANTHROPIC_API_KEY}); blank in tests.
 * @param maxTokens        non-streaming output cap (kept under SDK HTTP timeouts).
 * @param conversationsDir directory holding per-conversation JSONL files.
 * @param upload           file-attachment limits.
 */
@ConfigurationProperties(prefix = "coach")
public record AppConfig(
        String anthropicApiKey,
        int maxTokens,
        String conversationsDir,
        Upload upload
) {

    /**
     * Attachment limits (bound from {@code coach.upload.*}).
     *
     * @param maxFileSizeBytes       per-file cap (also per extracted zip entry).
     * @param maxFilesPerMessage     cap on total attachments after zip expansion.
     * @param maxZipEntries          cap on entries in a single zip.
     * @param maxTotalExtractedBytes cap on total uncompressed bytes per zip (bomb defense).
     * @param allowedMimeTypes       accepted attachment MIME types.
     */
    public record Upload(
            long maxFileSizeBytes,
            int maxFilesPerMessage,
            int maxZipEntries,
            long maxTotalExtractedBytes,
            List<String> allowedMimeTypes
    ) { }
}
