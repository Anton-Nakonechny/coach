package com.coach.docs;

import com.coach.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocsService}'s front-matter parsing, official-doc grounding,
 * graceful degradation, and truncation. The HTTP boundary ({@link DocFetchGateway}) is
 * a Mockito mock; behavior is characterized against the current implementation.
 */
@ExtendWith(MockitoExtension.class)
class DocsServiceTest {

    @Mock
    DocFetchGateway gateway;

    @Mock
    DocsCache cache;

    private static final String GROUNDING_MARK = "official documentation and are your source of truth";
    private static final String DELIM = "=== OFFICIAL DOCUMENTATION (source of truth): ";

    /** Make the cache a transparent pass-through so these tests exercise DocsService only. */
    @BeforeEach
    void passThroughCache() {
        lenient().when(cache.fetch(any(), any())).thenAnswer(inv -> {
            Function<URI, String> loader = inv.getArgument(1);
            return loader.apply(inv.getArgument(0));
        });
    }

    private DocsService docsService(int maxChars) {
        var config = new AppConfig(null, 0, null, null, null, new AppConfig.Docs(null, null, maxChars));
        return new DocsService(gateway, cache, config);
    }

    @Test
    void withoutSourcesBlockReturnsBlueprintUnchanged() {
        var blueprint = "STOP_REASON DRILL\nWrite five questions.";

        assertThat(docsService(10_000).withOfficialDocs(blueprint)).isEqualTo(blueprint);
        verifyNoInteractions(gateway);
    }

    @Test
    void oneSourceStripsFrontMatterAndAppendsGroundedSection() {
        var url = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        when(gateway.fetch(URI.create(url))).thenReturn("STOP REASONS DOC BODY");
        var blueprint = "<!-- sources:\n" + url + "\n-->\nSTOP_REASON DRILL";

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).startsWith("STOP_REASON DRILL");
        assertThat(result).doesNotContain("<!-- sources:");
        assertThat(result).contains(GROUNDING_MARK);
        assertThat(result).contains(DELIM + url + " ===");
        assertThat(result).contains("STOP REASONS DOC BODY");
    }

    @Test
    void twoSourcesAreFetchedInOrderAndBothAppended() {
        var first = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        var second = "https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview.md";
        when(gateway.fetch(URI.create(first))).thenReturn("STOP REASONS DOC BODY");
        when(gateway.fetch(URI.create(second))).thenReturn("TOOL USE DOC BODY");
        var blueprint = "<!-- sources:\n" + first + "\n" + second + "\n-->\nTOPIC BLUEPRINT";

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).contains("STOP REASONS DOC BODY", "TOOL USE DOC BODY");
        assertThat(result.indexOf("STOP REASONS DOC BODY")).isLessThan(result.indexOf("TOOL USE DOC BODY"));

        var captor = ArgumentCaptor.forClass(URI.class);
        verify(gateway, times(2)).fetch(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(URI.create(first), URI.create(second));
    }

    @Test
    void blankAndNonHttpsLinesAreIgnored() {
        var url = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        when(gateway.fetch(URI.create(url))).thenReturn("STOP REASONS DOC BODY");
        var blueprint = """
                <!-- sources:
                    %s

                http://insecure.example.com/page.md
                a stray comment line
                -->
                TOPIC BLUEPRINT""".formatted(url);

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).contains("STOP REASONS DOC BODY");
        assertThat(result).startsWith("TOPIC BLUEPRINT");
        verify(gateway, times(1)).fetch(URI.create(url));
    }

    @Test
    void malformedUrlIsSkippedAndValidUrlStillFetched() {
        var valid = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        when(gateway.fetch(URI.create(valid))).thenReturn("STOP REASONS DOC BODY");
        var blueprint = "<!-- sources:\nhttps://exa mple.com/bad.md\n" + valid + "\n-->\nTOPIC BLUEPRINT";

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).contains("STOP REASONS DOC BODY");
        verify(gateway, times(1)).fetch(URI.create(valid));
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void failedFetchIsOmittedButRemainingDocSurvives() {
        var down = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        var up = "https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview.md";
        when(gateway.fetch(URI.create(down))).thenThrow(new UncheckedIOException(new IOException("HTTP 503")));
        when(gateway.fetch(URI.create(up))).thenReturn("TOOL USE DOC BODY");
        var blueprint = "<!-- sources:\n" + down + "\n" + up + "\n-->\nTOPIC BLUEPRINT";

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).contains(GROUNDING_MARK);
        assertThat(result).contains("TOOL USE DOC BODY");
        assertThat(result).doesNotContain(DELIM + down + " ===");
    }

    @Test
    void allFetchesFailingLeavesStrippedBlueprintWithNoDocsSection() {
        var url = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        when(gateway.fetch(URI.create(url))).thenThrow(new RuntimeException("docs down"));
        var blueprint = "<!-- sources:\n" + url + "\n-->\nTOPIC BLUEPRINT";

        var result = docsService(10_000).withOfficialDocs(blueprint);

        assertThat(result).isEqualTo("TOPIC BLUEPRINT");
        assertThat(result).doesNotContain(GROUNDING_MARK);
    }

    @Test
    void oversizedDocIsTruncatedAndLaterSourcesAreNotFetched() {
        var big = "https://platform.claude.com/docs/en/api/handling-stop-reasons.md";
        var second = "https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview.md";
        when(gateway.fetch(URI.create(big))).thenReturn("X".repeat(2_000));
        var blueprint = "<!-- sources:\n" + big + "\n" + second + "\n-->\nTOPIC BLUEPRINT";

        var result = docsService(1_000).withOfficialDocs(blueprint);

        assertThat(result).endsWith("\n[truncated]");
        verify(gateway, times(1)).fetch(URI.create(big));
        verify(gateway, never()).fetch(URI.create(second));
    }
}
