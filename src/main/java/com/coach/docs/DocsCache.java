package com.coach.docs;

import com.coach.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * On-disk snapshot cache for fetched documentation pages: a page younger than the
 * configured TTL is served from {@code coach.docs.cache-dir} without hitting the
 * network; otherwise it is (re)loaded, stored, and returned. The cache is best-effort —
 * any read/write failure degrades to a live load rather than propagating. A load
 * failure is never cached, so a transient outage does not poison the snapshot; when a
 * reload fails but a stale snapshot is on disk, that copy is served rather than failing.
 */
@Component
public class DocsCache {

    private static final Logger log = LoggerFactory.getLogger(DocsCache.class);

    private final Path dir;
    private final Duration ttl;

    public DocsCache(AppConfig config) {
        this.dir = Path.of(config.docs().cacheDir());
        this.ttl = config.docs().ttl();
    }

    /**
     * Fresh cached snapshot for {@code url}, else {@code loader}'s result stored then
     * returned. When the reload fails but a stale snapshot is on disk, that copy is
     * served; a failure with no snapshot to fall back on rethrows.
     */
    public String fetch(URI url, Function<URI, String> loader) {
        Path file = dir.resolve(key(url) + ".md");
        Optional<String> fresh = readFresh(file);
        if (fresh.isPresent())
            return fresh.get();
        try {
            String content = loader.apply(url);
            store(file, content);
            return content;
        } catch (RuntimeException e) {
            Optional<String> stale = read(file);
            if (stale.isEmpty())
                throw e;
            log.warn("Serving stale doc snapshot {} after reload failure: {}", file, e.toString());
            return stale.get();
        }
    }

    /** Cached body if the snapshot exists and is younger than the TTL; empty otherwise. */
    private Optional<String> readFresh(Path file) {
        if (isFresh(file))
            return read(file);
        return Optional.empty();
    }

    private boolean isFresh(Path file) {
        try {
            return Files.exists(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(ttl));
        } catch (IOException e) {
            log.warn("Ignoring unreadable doc cache {}: {}", file, e.toString());
            return false;
        }
    }

    /** Snapshot body regardless of age; empty when it is missing or unreadable. */
    private static Optional<String> read(Path file) {
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            log.warn("Ignoring unreadable doc cache {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    private void store(Path file, String content) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content);
        } catch (IOException e) {
            log.warn("Could not write doc cache {}: {}", file, e.toString());
        }
    }

    /** Stable, filesystem-safe cache key: the first 8 bytes of the URL's SHA-256, hex-encoded. */
    private static String key(URI url) {
        byte[] hash = sha256(url.toString());
        var sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++)
            sb.append(String.format("%02x", hash[i]));
        return sb.toString();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
