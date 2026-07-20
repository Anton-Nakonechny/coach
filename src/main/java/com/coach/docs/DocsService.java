package com.coach.docs;

import com.coach.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounds Claude Architect topics in official Anthropic documentation: a topic
 * blueprint may start with a {@code <!-- sources: ... -->} front-matter block listing
 * doc-page URLs (one per line); those pages are fetched and appended to the blueprint
 * as clearly delimited source-of-truth sections. A page that cannot be fetched is
 * skipped — the quiz degrades to blueprint-only rather than failing.
 */
@Component
public class DocsService {

    private static final Logger log = LoggerFactory.getLogger(DocsService.class);

    /** Leading front-matter block declaring official-doc source URLs. */
    private static final Pattern SOURCES_BLOCK =
            Pattern.compile("\\A<!--\\s*sources:\\s*(.*?)-->\\s*", Pattern.DOTALL);

    private static final String GROUNDING = """
            The OFFICIAL DOCUMENTATION sections below are excerpts of Anthropic's current \
            official documentation and are your source of truth. Ground every question, \
            correct answer, and explanation in them; when the topic outline above and the \
            documentation disagree, the documentation wins. Never invent API parameters, \
            field names, or behaviors the documentation does not state.""";

    private final DocFetchGateway gateway;
    private final DocsCache cache;
    private final int maxChars;

    public DocsService(DocFetchGateway gateway, DocsCache cache, AppConfig config) {
        this.gateway = gateway;
        this.cache = cache;
        this.maxChars = config.docs().maxChars();
    }

    /** Blueprint with its sources front-matter replaced by fetched official-doc sections. */
    public String withOfficialDocs(String blueprint) {
        Matcher matcher = SOURCES_BLOCK.matcher(blueprint);
        if (matcher.find())
            return blueprint.substring(matcher.end()) + docsSection(parseSources(matcher.group(1)));
        return blueprint;
    }

    /** Trimmed {@code https://} URLs from the front-matter body, one per line. */
    private static List<URI> parseSources(String block) {
        return block.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("https://"))
                .map(DocsService::tryUri)
                .flatMap(Optional::stream)
                .toList();
    }

    /** Parse one source line, skipping (with a warning) any malformed URL. */
    private static Optional<URI> tryUri(String line) {
        try {
            return Optional.of(URI.create(line));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping malformed doc source URL: {}", line);
            return Optional.empty();
        }
    }

    /** Delimited section per resolvable URL, capped at {@code maxChars}; empty when none resolve. */
    private String docsSection(List<URI> sources) {
        var sb = new StringBuilder();
        // Reserve room for the GROUNDING preamble + "\n\n" separator prepended below,
        // so the returned section as a whole stays within maxChars.
        int budget = Math.max(0, maxChars - GROUNDING.length() - 2);
        for (URI url : sources) {
            Optional<String> content = fetchQuietly(url);
            if (content.isEmpty()) continue;
            sb.append("\n\n=== OFFICIAL DOCUMENTATION (source of truth): ").append(url)
                    .append(" ===\n\n").append(content.get());
            if (sb.length() >= budget) {
                sb.setLength(budget);
                sb.append("\n[truncated]");
                break;
            }
        }
        if (sb.isEmpty()) return "";
        return "\n\n" + GROUNDING + sb;
    }

    private Optional<String> fetchQuietly(URI url) {
        try {
            return Optional.of(cache.fetch(url, gateway::fetch));
        } catch (RuntimeException e) {
            log.warn("Skipping unavailable doc {}: {}", url, e.toString());
            return Optional.empty();
        }
    }
}
