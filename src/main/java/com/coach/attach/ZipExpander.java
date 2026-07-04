package com.coach.attach;

import com.coach.config.AppConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Expands a zip archive in memory into its individual files (flattening nested
 * folders), then hands the extracted bytes back for upload.
 *
 * <p>Security: rejects path-traversal (zip-slip) entries, nested zip archives, entries
 * of an unsupported type, and enforces per-entry / total-size / entry-count caps to
 * bound decompression (zip-bomb defense). Any violation throws {@link UploadException}
 * (→ HTTP 400) and the whole message is rejected before anything is uploaded.
 */
@Component
public class ZipExpander {

    private final AppConfig config;

    public ZipExpander(AppConfig config) {
        this.config = config;
    }

    /** Extract the archive's files, validating every entry. */
    public List<ExtractedFile> expand(byte[] zipBytes) {
        AppConfig.Upload cfg = config.upload();
        List<ExtractedFile> leaves = new ArrayList<>();
        long totalBytes = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || isMacOsMetadata(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }
                if (++count > cfg.maxZipEntries())
                    throw new UploadException("zip has too many entries (max " + cfg.maxZipEntries() + ")");

                Path name = Path.of(entry.getName()).normalize();
                if (name.isAbsolute() || name.startsWith(".."))
                    throw new UploadException("zip entry escapes the archive: " + entry.getName());

                String leafName = name.getFileName().toString();
                String mediaType = MediaTypes.byFilename(leafName);
                if ("application/zip".equals(mediaType))
                    throw new UploadException("nested zip archives are not allowed: " + entry.getName());
                if (mediaType == null || !cfg.allowedMimeTypes().contains(mediaType))
                    throw new UploadException("unsupported file type in zip: " + entry.getName());

                byte[] bytes = readBounded(zis, cfg.maxFileSizeBytes(), entry.getName());
                totalBytes += bytes.length;
                if (totalBytes > cfg.maxTotalExtractedBytes())
                    throw new UploadException("zip expands beyond the size limit");
                leaves.add(new ExtractedFile(leafName, mediaType, bytes));
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (leaves.isEmpty())
            throw new UploadException("zip contains no files");
        return leaves;
    }

    /**
     * True for macOS archive cruft that Finder injects when zipping: the {@code __MACOSX/}
     * tree of AppleDouble ({@code ._*}) resource-fork stand-ins and {@code .DS_Store}. These
     * carry the same extension as the real file (e.g. {@code ._photo.png}) but hold opaque
     * binary, so they must be dropped before upload rather than sent as bogus images.
     */
    private static boolean isMacOsMetadata(String name) {
        String leaf = StringUtils.getFilename(name);
        return name.startsWith("__MACOSX/")
                || (leaf != null && (leaf.startsWith("._") || leaf.equals(".DS_Store")));
    }

    /** Read the current zip entry fully into memory, failing fast if it exceeds {@code max}. */
    private static byte[] readBounded(InputStream in, long max, String entryName) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > max)
                throw new UploadException("file too large in zip: " + entryName);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
