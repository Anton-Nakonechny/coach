package com.coach.docs;

import com.coach.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DocsCache}'s on-disk snapshot behavior: fresh hits skip the
 * loader, stale or missing entries (re)load and store, load failures are never cached,
 * and cache keys are stable 16-hex filenames. Uses a JUnit {@code @TempDir} as the
 * cache directory; the loader is a plain counting function (no HTTP).
 */
class DocsCacheTest {

    private static final URI URL = URI.create("https://platform.claude.com/docs/en/api/overview.md");

    private DocsCache cache(Path dir, Duration ttl) {
        var config = new AppConfig(null, 0, null, null, null, new AppConfig.Docs(dir.toString(), ttl, 0));
        return new DocsCache(config);
    }

    /** A loader that records how many times it ran and returns a fixed body. */
    private static Function<URI, String> counting(AtomicInteger calls, String body) {
        return url -> {
            calls.incrementAndGet();
            return body;
        };
    }

    @Test
    void missLoadsStoresAndReturns(@TempDir Path dir) throws IOException {
        var calls = new AtomicInteger();

        var result = cache(dir, Duration.ofHours(1)).fetch(URL, counting(calls, "DOC BODY"));

        assertThat(result).isEqualTo("DOC BODY");
        assertThat(calls).hasValue(1);
        assertThat(cachedFiles(dir)).singleElement()
                .matches(p -> p.getFileName().toString().matches("[0-9a-f]{16}\\.md"));
    }

    @Test
    void freshHitDoesNotCallLoaderAgain(@TempDir Path dir) {
        var calls = new AtomicInteger();
        var cache = cache(dir, Duration.ofHours(1));

        cache.fetch(URL, counting(calls, "DOC BODY"));
        var second = cache.fetch(URL, counting(calls, "SHOULD NOT LOAD"));

        assertThat(second).isEqualTo("DOC BODY");
        assertThat(calls).hasValue(1);
    }

    @Test
    void staleEntryIsReloaded(@TempDir Path dir) throws IOException {
        var calls = new AtomicInteger();
        var cache = cache(dir, Duration.ofHours(1));
        cache.fetch(URL, counting(calls, "OLD BODY"));
        backdate(only(cachedFiles(dir)), Duration.ofHours(2));

        var second = cache.fetch(URL, counting(calls, "NEW BODY"));

        assertThat(second).isEqualTo("NEW BODY");
        assertThat(calls).hasValue(2);
    }

    @Test
    void loaderFailureIsNotCached(@TempDir Path dir) {
        var cache = cache(dir, Duration.ofHours(1));

        assertThatThrownBy(() -> cache.fetch(URL, url -> { throw new UncheckedIOException(new IOException("down")); }))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(cachedFiles(dir)).isEmpty();

        var calls = new AtomicInteger();
        assertThat(cache.fetch(URL, counting(calls, "RECOVERED"))).isEqualTo("RECOVERED");
        assertThat(calls).hasValue(1);
    }

    @Test
    void distinctUrlsUseDistinctFilesAndSameUrlReusesOne(@TempDir Path dir) {
        var other = URI.create("https://platform.claude.com/docs/en/api/other.md");
        var calls = new AtomicInteger();
        var cache = cache(dir, Duration.ofHours(1));

        cache.fetch(URL, counting(calls, "A"));
        cache.fetch(URL, counting(calls, "A"));
        cache.fetch(other, counting(calls, "B"));

        assertThat(cachedFiles(dir)).hasSize(2);
    }

    @Test
    void createsMissingCacheDirectory(@TempDir Path dir) {
        var nested = dir.resolve("does/not/exist/yet");

        cache(nested, Duration.ofHours(1)).fetch(URL, url -> "DOC BODY");

        assertThat(cachedFiles(nested)).hasSize(1);
    }

    private static List<Path> cachedFiles(Path dir) {
        if (Files.notExists(dir))
            return List.of();
        try (var stream = Files.list(dir)) {
            return stream.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path only(List<Path> files) {
        assertThat(files).hasSize(1);
        return files.get(0);
    }

    private static void backdate(Path file, Duration by) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(by)));
    }
}
