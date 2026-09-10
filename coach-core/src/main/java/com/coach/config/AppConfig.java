package com.coach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Application configuration bound from {@code coach.*} properties.
 *
 * @param anthropicApiKey  Anthropic API key (from {@code ANTHROPIC_API_KEY}); blank in tests.
 * @param maxTokens        non-streaming output cap (kept under SDK HTTP timeouts).
 * @param conversationsDir directory holding per-conversation JSONL files.
 * @param coachesDir       root folder of coach scenario prompts (one subfolder per coach).
 * @param requestTimeout   maximum time for a complete Anthropic API call; generous for image reviews.
 * @param upload           file-attachment limits.
 * @param docs             official-documentation grounding settings.
 * @param noam             noam vocabulary-platform integration.
 */
@ConfigurationProperties(prefix = "coach")
public record AppConfig(
        String anthropicApiKey,
        int maxTokens,
        String conversationsDir,
        String coachesDir,
        Duration requestTimeout,
        Upload upload,
        Docs docs,
        @DefaultValue Noam noam
) {

    /**
     * Official-doc grounding settings (bound from {@code coach.docs.*}).
     *
     * @param cacheDir directory holding fetched doc-page snapshots (gitignored).
     * @param ttl      snapshot freshness window before a refetch.
     * @param maxChars cap on the total documentation section appended to a system prompt.
     */
    public record Docs(
            String cacheDir,
            Duration ttl,
            int maxChars
    ) { }

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

    /**
     * noam vocabulary-platform integration (bound from {@code coach.noam.*}).
     *
     * @param baseUrl   root of the noam REST API, e.g. http://localhost:8080/api/v1.
     * @param profileId study profile whose known-set filters study items; hardcoded for v1.
     * @param userId    owning noam user, used only for server-side write-backs; never sent to the browser.
     */
    public record Noam(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String profileId,
            @DefaultValue("") String userId
    ) { }
}
